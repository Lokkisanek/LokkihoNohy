package com.bazaarflipper.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Provides randomised, human-like delays to reduce the risk of automated
 * action detection.  All delays are expressed in game ticks (20 ticks = 1 s).
 *
 * Delay ranges come from the specification:
 *   - Between any screen change or inventory click: 300–900 ms  →  6–18 ticks
 */
public final class DelayUtil {

    /** Minimum delay between actions in ticks (300 ms at 20 TPS). */
    public static final int MIN_TICKS = 6;

    /** Maximum delay between actions in ticks (900 ms at 20 TPS). */
    public static final int MAX_TICKS = 18;

    /**
     * Extra-long pause used before confirming orders — feels more human
     * (1 500–2 500 ms → 30–50 ticks).
     */
    public static final int CONFIRM_MIN_TICKS = 30;
    public static final int CONFIRM_MAX_TICKS = 50;

    private DelayUtil() {}

    /** Returns a random tick count in the standard [MIN, MAX] range. */
    public static int randomTicks() {
        return ThreadLocalRandom.current().nextInt(MIN_TICKS, MAX_TICKS + 1);
    }

    /**
     * Returns a random tick count in the longer confirm-pause range.
     * Use this before clicking "Confirm" in any order dialogue.
     */
    public static int randomConfirmTicks() {
        return ThreadLocalRandom.current().nextInt(CONFIRM_MIN_TICKS, CONFIRM_MAX_TICKS + 1);
    }

    /**
     * Returns a random tick count in a custom range (inclusive on both ends).
     */
    public static int randomTicks(int minTicks, int maxTicks) {
        return ThreadLocalRandom.current().nextInt(minTicks, maxTicks + 1);
    }
}
