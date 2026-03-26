package com.bazaarflipper.flip;

import com.bazaarflipper.market.BazaarProduct;

public class FlipTask {
    public final String itemId;
    public final String displayName;
    public final String searchTerm;
    public double buyPrice;
    public double sellPrice;
    public int amount;
    public FlipPhase phase;
    public int subStep;
    public int retries;
    public long startTimeMs;
    public long lastActionMs;

    public static final int MAX_RETRIES = 5;

    public FlipTask(BazaarProduct product, int amount) {
        this.itemId = product.productId;
        this.displayName = product.displayName();
        this.searchTerm = product.searchTerm();
        this.buyPrice = product.ourBuyPrice();
        this.sellPrice = product.ourSellPrice();
        this.amount = amount;
        this.phase = FlipPhase.BUY_NAVIGATE;
        this.subStep = 0;
        this.retries = 0;
        this.startTimeMs = System.currentTimeMillis();
        this.lastActionMs = this.startTimeMs;
    }

    public void advancePhase(FlipPhase next) {
        this.phase = next;
        this.subStep = 0;
        this.retries = 0;
        this.lastActionMs = System.currentTimeMillis();
    }

    public boolean canRetry() {
        return retries < MAX_RETRIES;
    }

    public void retry() {
        retries++;
        subStep = 0;
        lastActionMs = System.currentTimeMillis();
    }

    public double estimatedProfit() {
        double taxRate = 0.01252;
        return (sellPrice * (1.0 - taxRate) - buyPrice) * amount;
    }

    @Override
    public String toString() {
        return String.format("%s [%s step=%d] %dx @buy=%.1f sell=%.1f",
                itemId, phase.name(), subStep, amount, buyPrice, sellPrice);
    }
}
