package com.bazaarflipper.safety;

import com.bazaarflipper.config.FlipperConfig;
import com.bazaarflipper.util.FlipLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Monitors for threats and enforces safety limits.
 * - Nearby player detection
 * - Admin/staff message detection
 * - Session time limits with breaks
 * - Panic mode triggering
 */
public class SafetyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BazaarFlipper");

    private final FlipperConfig config;
    private final FlipLog log;
    private final HumanSimulator humanSim;

    private long sessionStartMs = 0;
    private long breakUntilMs = 0;
    private long lastNearbyCheckMs = 0;
    private boolean panicMode = false;
    private long panicUntilMs = 0;
    private int nearbyPlayerCount = 0;
    private int suspiciousDetections = 0;
    private long lastBreakMs = 0;
    private long nextBreakMs = 0;

    // Chat threat keywords
    private static final String[] ADMIN_KEYWORDS = {
            "[admin]", "[gm]", "[mod]", "[helper]", "[watchdog]",
            "you have been banned", "suspicious activity", "illegal modification", "using macros"
    };

    public SafetyManager(FlipperConfig config, FlipLog log) {
        this.config = config;
        this.log = log;
        this.humanSim = new HumanSimulator();
    }

    public void startSession() {
        sessionStartMs = System.currentTimeMillis();
        panicMode = false;
        suspiciousDetections = 0;
        scheduleNextBreak();
    }

    public void tick(MinecraftClient mc) {
        if (mc.player == null) return;
        long now = System.currentTimeMillis();

        // Session time limit
        if (config.sessionLimitMinutes > 0) {
            long elapsed = (now - sessionStartMs) / 60_000;
            if (elapsed >= config.sessionLimitMinutes) {
                log.log("§eSession limit reached (" + config.sessionLimitMinutes + "m). Taking break.");
                triggerBreak(now);
            }
        }

        // Scheduled breaks
        if (nextBreakMs > 0 && now >= nextBreakMs) {
            log.log("§eScheduled break.");
            triggerBreak(now);
        }

        // Panic mode timeout
        if (panicMode && now >= panicUntilMs) {
            panicMode = false;
            log.log("§aPanic mode ended, resuming.");
        }

        // If in panic, simulate human behavior
        if (panicMode) {
            humanSim.tick(mc);
            return;
        }

        // Check nearby players every 5 seconds
        if (now - lastNearbyCheckMs > 5000) {
            lastNearbyCheckMs = now;
            checkNearbyPlayers(mc);
        }
    }

    private void checkNearbyPlayers(MinecraftClient mc) {
        int count = 0;
        int lookingAtUs = 0;
        double radius = config.nearbyPlayerRadius;

        for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            double dist = mc.player.distanceTo(player);
            if (dist <= radius) {
                count++;
                // Check if they're roughly looking at us
                double dx = mc.player.getX() - player.getX();
                double dz = mc.player.getZ() - player.getZ();
                double angle = Math.toDegrees(Math.atan2(-dx, dz));
                double playerYaw = player.getYaw() % 360;
                if (playerYaw < 0) playerYaw += 360;
                if (angle < 0) angle += 360;
                double diff = Math.abs(playerYaw - angle);
                if (diff > 180) diff = 360 - diff;
                if (diff < 45) lookingAtUs++;
            }
        }

        nearbyPlayerCount = count;

        if (count >= config.nearbyPlayerThreshold && lookingAtUs >= 2) {
            suspiciousDetections++;
            if (suspiciousDetections >= 3) {
                log.log("§c" + count + " players nearby, " + lookingAtUs + " watching! Entering panic mode.");
                triggerPanic(30_000 + ThreadLocalRandom.current().nextLong(30_000));
                suspiciousDetections = 0;
            }
        } else if (count < 2) {
            suspiciousDetections = Math.max(0, suspiciousDetections - 1);
        }
    }

    public void onChatMessage(String message) {
        String lower = message.toLowerCase();
        for (String keyword : ADMIN_KEYWORDS) {
            if (lower.contains(keyword)) {
                log.log("§c§lThreat detected in chat: '" + keyword + "' — PANIC!");
                LOGGER.warn("[BazaarFlipper] Threat keyword '{}' in chat: {}", keyword, message);
                triggerPanic(120_000 + ThreadLocalRandom.current().nextLong(60_000));
                return;
            }
        }
    }

    private void triggerPanic(long durationMs) {
        panicMode = true;
        panicUntilMs = System.currentTimeMillis() + durationMs;
    }

    private void triggerBreak(long now) {
        long breakMs = config.breakDurationMinutes * 60_000L
                + ThreadLocalRandom.current().nextLong(60_000);
        breakUntilMs = now + breakMs;
        sessionStartMs = now + breakMs;
        scheduleNextBreak();
    }

    private void scheduleNextBreak() {
        long intervalMs = (20 + ThreadLocalRandom.current().nextLong(20)) * 60_000L;
        nextBreakMs = System.currentTimeMillis() + intervalMs;
    }

    public boolean shouldPause() {
        return System.currentTimeMillis() < breakUntilMs;
    }

    public boolean shouldPanic() {
        return panicMode;
    }

    public int getNearbyPlayerCount() {
        return nearbyPlayerCount;
    }

    public HumanSimulator getHumanSimulator() {
        return humanSim;
    }

    public void stop(MinecraftClient mc) {
        panicMode = false;
        humanSim.stop(mc);
    }
}
