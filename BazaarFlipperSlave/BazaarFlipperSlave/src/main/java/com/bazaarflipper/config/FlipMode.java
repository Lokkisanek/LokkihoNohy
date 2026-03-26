package com.bazaarflipper.config;

public enum FlipMode {
    AGGRESSIVE(3, "Aggressive"),
    BALANCED(2, "Balanced"),
    CHILL(1, "Chill"),
    SPECIFIC(1, "Specific");

    public final int maxConcurrentFlips;
    public final String displayName;

    FlipMode(int maxConcurrentFlips, String displayName) {
        this.maxConcurrentFlips = maxConcurrentFlips;
        this.displayName = displayName;
    }

    public FlipMode next() {
        FlipMode[] vals = values();
        return vals[(this.ordinal() + 1) % vals.length];
    }
}
