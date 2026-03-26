package com.bazaarflipper.flip;

public enum FlipPhase {
    IDLE,
    BUY_NAVIGATE,
    BUY_CONFIGURE,
    BUY_WAIT,
    BUY_CLAIM,
    SELL_NAVIGATE,
    SELL_CONFIGURE,
    SELL_WAIT,
    SELL_CLAIM,
    COMPLETE,
    PAUSED,
    PANIC;

    public boolean isActive() {
        return this != IDLE && this != COMPLETE && this != PAUSED && this != PANIC;
    }

    public boolean isBuySide() {
        return this == BUY_NAVIGATE || this == BUY_CONFIGURE || this == BUY_WAIT || this == BUY_CLAIM;
    }

    public boolean isSellSide() {
        return this == SELL_NAVIGATE || this == SELL_CONFIGURE || this == SELL_WAIT || this == SELL_CLAIM;
    }
}
