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
 * FIX 2.2 — markTaskConsumed() now POSTs to /api/task/done
 * FIX 2.3 — sendStatus() sends GuiType.name().toLowerCase() instead of raw title
 * FIX 3.2 — readPlayerCoins() strips §-color codes and comma separators
 */
public class TaskManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("BazaarFlipper/TaskManager");

    private static final String BASE_URL   = "http://localhost:8000";
    private static final String TASK_URL   = BASE_URL + "/api/task";
    private static final String STATUS_URL = BASE_URL + "/api/status";
    private static final String DONE_URL   = BASE_URL + "/api/task/done";
    private static final String FAILED_URL = BASE_URL + "/api/task/failed";

    private static final int POLL_MIN_MS       = 1000;
    private static final int POLL_MAX_MS       = 2000;
    private static final int STATUS_INTERVAL_MS = 5000;

    private final HttpClient http;
    private final Gson gson = new Gson();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BazaarFlipper-HTTP");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<Task> currentTask = new AtomicReference<>(idleTask());
    private final AtomicBoolean taskRequestInFlight   = new AtomicBoolean(false);
    private final AtomicBoolean statusRequestInFlight = new AtomicBoolean(false);

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
        LOGGER.info("[BazaarFlipper] TaskManager started — polling {}", TASK_URL);
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
            task.action      = getStr(obj, "action", "IDLE");
            task.item_id     = getStr(obj, "item_id", "");
            task.amount      = obj.has("amount")      ? obj.get("amount").getAsInt()      : 0;
            task.price       = obj.has("price")       ? obj.get("price").getAsDouble()    : 0.0;
            task.slot_target = obj.has("slot_target") ? obj.get("slot_target").getAsInt() : -1;

            if (currentTask.get().isIdle() || taskConsumed) {
                taskConsumed = false;
                currentTask.set(task);
                if (!task.isIdle()) {
                    LOGGER.info("[BazaarFlipper] New task: {}", task);
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

        // FIX 2.3 — report GuiType enum name (e.g. "bazaar_main") not raw title
        String guiType = BazaarFlipperMod.getScreenTracker()
                .getCurrentGuiType()
                .name()
                .toLowerCase();   // e.g. "bazaar_main", "bazaar_orders", "none"

        double coins       = readPlayerCoins(mc);
        List<String> items = collectInventoryIds(mc);

        StatusPayload payload = new StatusPayload(guiType, coins, items);
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
                    LOGGER.warn("[BazaarFlipper] Status POST failed: {}", ex.getMessage());
                    return null;
                });
    }

    // -----------------------------------------------------------------------
    //  Coin parsing (FIX 3.2)
    // -----------------------------------------------------------------------

    /**
     * Reads the player's purse balance from the Hypixel SkyBlock sidebar scoreboard.
     *
     * Hypixel format examples:
     *   "§6Purse: §a1,234,567"
     *   "§6Purse: §a12.3M"
     *   "Coins: 1,234,567"
     *
     * FIX 3.2: strip §-color codes, strip commas, handle both "Purse" and "Coins" labels.
     */
    private double readPlayerCoins(MinecraftClient mc) {
        try {
            var scoreboard = mc.world.getScoreboard();
            var objective  = scoreboard.getObjectiveForSlot(
                    net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
            if (objective == null) return 0;

            for (var entry : scoreboard.getScoreboardEntries(objective)) {
                // Strip §-color/formatting codes
                String name = entry.owner().replaceAll("§[0-9a-fk-or]", "").trim();

                if (name.contains("Purse") || name.contains("Coins")) {
                    // Remove everything that is not a digit, dot, or comma
                    String cleaned = name.replaceAll("[^0-9.,]", "");
                    // Remove comma thousand-separators
                    cleaned = cleaned.replace(",", "");
                    if (!cleaned.isEmpty()) {
                        return Double.parseDouble(cleaned);
                    }
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

    /**
     * FIX 2.2 — also POSTs to /api/task/done so the server can advance its
     * task queue and trigger the auto-SELL logic.
     */
    public void markTaskConsumed() {
        taskConsumed = true;
        currentTask.set(idleTask());
        LOGGER.info("[BazaarFlipper] Task consumed, notifying server.");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DONE_URL))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> {
                    LOGGER.warn("[BazaarFlipper] /api/task/done POST failed: {}", ex.getMessage());
                    return null;
                });
    }

    /**
     * FIX 2.2 — also POSTs to /api/task/failed so the server can log the
     * failure and reset its current task.
     */
    public void markTaskFailed(String reason) {
        LOGGER.warn("[BazaarFlipper] Task failed: {}", reason);
        taskConsumed = true;
        currentTask.set(idleTask());

        String body = "{\"reason\":\"" + reason.replace("\"", "'") + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(FAILED_URL))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> {
                    LOGGER.warn("[BazaarFlipper] /api/task/failed POST failed: {}", ex.getMessage());
                    return null;
                });
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
