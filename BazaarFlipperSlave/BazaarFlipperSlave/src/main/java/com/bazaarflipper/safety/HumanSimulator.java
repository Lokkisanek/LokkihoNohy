package com.bazaarflipper.safety;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates human-like behavior when the bot is idle or in panic mode.
 * Smooth head movements, occasional shifting, small jumps.
 */
public class HumanSimulator {

    private float targetYaw;
    private float targetPitch;
    private boolean isMoving = false;
    private long nextMovementMs = 0;
    private long nextShiftToggleMs = 0;
    private boolean shifting = false;

    public void tick(MinecraftClient mc) {
        if (mc.player == null) return;
        long now = System.currentTimeMillis();

        // Smooth head movement
        if (isMoving) {
            float currentYaw = mc.player.getYaw();
            float currentPitch = mc.player.getPitch();
            float yawDiff = targetYaw - currentYaw;
            float pitchDiff = targetPitch - currentPitch;

            if (Math.abs(yawDiff) < 0.5f && Math.abs(pitchDiff) < 0.5f) {
                isMoving = false;
                nextMovementMs = now + randomRange(2000, 6000);
            } else {
                mc.player.setYaw(currentYaw + yawDiff * 0.08f);
                mc.player.setPitch(currentPitch + pitchDiff * 0.08f);
            }
        } else if (now >= nextMovementMs) {
            startNewHeadMovement(mc);
        }

        // Occasional shift toggle
        if (now >= nextShiftToggleMs) {
            shifting = !shifting;
            mc.options.sneakKey.setPressed(shifting);
            nextShiftToggleMs = now + (shifting ? randomRange(500, 2000) : randomRange(3000, 8000));
        }

        // Rare small jump
        if (ThreadLocalRandom.current().nextInt(600) == 0 && mc.player.isOnGround()) {
            mc.player.jump();
        }
    }

    private void startNewHeadMovement(MinecraftClient mc) {
        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();
        targetYaw = currentYaw + (float) (ThreadLocalRandom.current().nextGaussian() * 30);
        targetPitch = Math.max(-60, Math.min(60,
                currentPitch + (float) (ThreadLocalRandom.current().nextGaussian() * 15)));
        isMoving = true;
    }

    public void stop(MinecraftClient mc) {
        if (mc.options != null) {
            mc.options.sneakKey.setPressed(false);
        }
        shifting = false;
        isMoving = false;
    }

    private long randomRange(long min, long max) {
        return min + ThreadLocalRandom.current().nextLong(max - min);
    }
}
