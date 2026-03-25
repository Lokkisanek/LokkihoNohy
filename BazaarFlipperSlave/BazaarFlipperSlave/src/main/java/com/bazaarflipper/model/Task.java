package com.bazaarflipper.model;

/**
 * Represents a task received from the Python master server's /api/task endpoint.
 * Fields map directly to the JSON response payload.
 */
public class Task {

    /** Action type. One of: IDLE, BUY, SELL, CLAIM_ORDERS, CANCEL_ORDER */
    public String action = "IDLE";

    /** Hypixel item ID, e.g. "ENCHANTED_COBBLESTONE" */
    public String item_id = "";

    /** Number of items to buy or sell */
    public int amount = 0;

    /** Limit order price */
    public double price = 0.0;

    /**
     * Optional: if the server already knows the exact slot to click,
     * it provides it here. -1 means "not set — figure it out yourself".
     */
    public int slot_target = -1;

    public boolean isIdle() {
        return action == null || action.equals("IDLE");
    }

    @Override
    public String toString() {
        return String.format("Task{action='%s', item='%s', amount=%d, price=%.1f, slot=%d}",
                action, item_id, amount, price, slot_target);
    }
}
