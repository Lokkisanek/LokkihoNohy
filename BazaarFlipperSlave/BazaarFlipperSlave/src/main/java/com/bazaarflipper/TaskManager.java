package com.bazaarflipper;

import com.bazaarflipper.model.StatusPayload;
import com.bazaarflipper.model.Task;
import com.bazaarflipper.util.NbtUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages all HTTP communication with the Python master server.
 *
 * - Polls  GET  http://localhost:8000/api/task    every 1–2 seconds
 * - Posts  POST http://localhost:8000/api/status  every ~5 seconds
 *
 * Uses java.net.http.HttpClient (async, non-blocking).
 * JSON is handled by the Gson library bundled with Minecraft.
 */
public class TaskManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("BazaarFlipper/TaskManager");

    private static final String BASE_URL   = "http://localhost:8000";
    private static final String TASK_URL   = BASE_URL + "/api/task";
    private static final String STATUS_URL = BASE_URL + "/api/status";

    /** Polling cadence: 1 000–2 000 ms, randomly chosen each cycle. */
    private static final int POLL_MIN_MS = 1000;
    private static final int POLL_MAX_MS = 2000;

    /** Status update cadence: every 5 000 ms. */
    private static final int STATUS_INTERVAL_MS = 5000;

    private final HttpClient http;
    private final Gson gson = new Gson();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BazaarFlipper-HTTP");
                t.setDaemon(true);
                return t;
            });

    /** Latest task received from the master. Never null — defaults to IDLE. */
    private final AtomicReference<Task> currentTask = new AtomicReference<>(idleTask());

    /** Prevents concurrent in-flight requests from piling up. */
    private final AtomicBoolean taskRequestInFlight   = new AtomicBoolean(false);
    private final AtomicBoolean statusRequestInFlight = new AtomicBoolean(false);

    /** Whether a task has been consumed (executor sets this after finishing). */
    private volatile boolean taskConsumed = false;

    public TaskManager() {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    // -----------------------------------------------------------------------
    //  Lifecycle
    // -----------------------------------------------------------------------

    public void startPolling() {
        scheduleNextPoll();
        scheduler.scheduleAtFixedRate(this::sendStatus, 2000, STATUS_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        LOGGER.info("[BazaarFlipper] TaskManager started.");
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    // -----------------------------------------------------------------------
    //  Task polling
    // -----------------------------------------------------------------------

    private void scheduleNextPoll() {
        long delay = POLL_MIN_MS + ThreadLocalRandom.current().nextLong(POLL_MAX_MS - POLL_MIN_MS);
        scheduler.schedule(this::pollTask, delay, TimeUnit.MILLISECONDS);
    }

    private void pollTask() {
        if (!taskRequestInFlight.compareAndSet(false, true)) return;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TASK_URL))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    taskRequestInFlight.set(false);
                    if (resp.statusCode() == 200) {
                        parseAndStoreTask(resp.body());
                    } else {
                        LOGGER.warn("[BazaarFlipper] /api/task returned HTTP {}", resp.statusCode());
                    }
                })
                .exceptionally(ex -> {
                    taskRequestInFlight.set(false);
                    LOGGER.warn("[BazaarFlipper] Task poll failed: {}", ex.getMessage());
                    return null;
                })
                .whenComplete((v, t) -> scheduleNextPoll());
    }

    private void parseAndStoreTask(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            Task task = new Task();
            task.action     = getStr(obj, "action", "IDLE");
            task.item_id    = getStr(obj, "item_id", "");
            task.amount     = obj.has("amount")  ? obj.get("amount").getAsInt()    : 0;
            task.price      = obj.has("price")   ? obj.get("price").getAsDouble()  : 0.0;
            task.slot_target = obj.has("slot_target") ? obj.get("slot_target").getAsInt() : -1;

            // Only replace the current task if it's IDLE or been consumed
            if (currentTask.get().isIdle() || taskConsumed) {
                taskConsumed = false;
                currentTask.set(task);
                if (!task.isIdle()) {
                    LOGGER.info("[BazaarFlipper] New task received: {}", task);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[BazaarFlipper] Failed to parse task JSON: {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Status reporting
    // -----------------------------------------------------------------------

    private void sendStatus() {
        if (!statusRequestInFlight.compareAndSet(false, true)) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            statusRequestInFlight.set(false);
            return;
        }

        // Collect inventory item IDs on the game thread via submit
        // (HttpClient callback runs off the game thread, so we gather data here
        // inside the scheduler thread — for Hypixel, packets are received on
        // the netty thread and stored in the player object, safe to read)
        String guiTitle = BazaarFlipperMod.getScreenTracker().getRawTitle();
        if (guiTitle.isEmpty()) guiTitle = "none";

        double coins = readPlayerCoins(mc);
        List<String> items = collectInventoryIds(mc);

        StatusPayload payload = new StatusPayload(guiTitle, coins, items);
        String body = gson.toJson(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(STATUS_URL))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenRun(() -> statusRequestInFlight.set(false))
                .exceptionally(ex -> {
                    statusRequestInFlight.set(false);
                    LOGGER.warn("[BazaarFlipper] Status post failed: {}", ex.getMessage());
                    return null;
                });
    }

    /**
     * Reads the player's coin balance.
     * Hypixel stores purse balance on the scoreboard sidebar.
     * For simplicity we parse the scoreboard; falls back to 0 on failure.
     */
    private double readPlayerCoins(MinecraftClient mc) {
        // Coin balance is parsed from the scoreboard sidebar display strings.
        // We iterate over the rendered score lines using the display objective.
        try {
            var scoreboard = mc.world.getScoreboard();
            var objective = scoreboard.getObjectiveForSlot(
                    net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
            if (objective == null) return 0;

            for (var scoreEntry : scoreboard.getScoreboardEntries(objective)) {
                String name = scoreEntry.owner();
                if (name.contains("Purse") || name.contains("purse")) {
                    String digits = name.replaceAll("[^0-9.]", "");
                    if (!digits.isEmpty()) return Double.parseDouble(digits);
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /** Collects all Hypixel item IDs currently in the player's inventory. */
    private List<String> collectInventoryIds(MinecraftClient mc) {
        List<String> ids = new ArrayList<>();
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            String id = NbtUtil.getHypixelId(stack);
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    public Task getCurrentTask() {
        return currentTask.get();
    }

    /** Call this when the executor has finished processing a task. */
    public void markTaskConsumed() {
        taskConsumed = true;
        currentTask.set(idleTask());
        LOGGER.info("[BazaarFlipper] Task marked consumed, waiting for next.");
    }

    /** Call this to report a task execution failure back to the master. */
    public void markTaskFailed(String reason) {
        LOGGER.warn("[BazaarFlipper] Task failed: {}", reason);
        taskConsumed = true;
        currentTask.set(idleTask());
        // Optionally POST the reason to /api/status as an error field
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private static Task idleTask() {
        Task t = new Task();
        t.action = "IDLE";
        return t;
    }

    private static String getStr(JsonObject obj, String key, String def) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString()
                : def;
    }

    private static final java.util.concurrent.ThreadLocalRandom ThreadLocalRandom =
            java.util.concurrent.ThreadLocalRandom.current();
}
