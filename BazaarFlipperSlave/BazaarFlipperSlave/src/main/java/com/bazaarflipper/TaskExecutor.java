package com.bazaarflipper;

import com.bazaarflipper.model.Task;
import com.bazaarflipper.util.DelayUtil;
import com.bazaarflipper.util.NbtUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-tick driven state machine that executes Bazaar tasks received from
 * the Python master server.
 *
 * Each task type (BUY, SELL, CLAIM_ORDERS, CANCEL_ORDER) is implemented as a
 * sequence of {@link Phase} values.  The executor advances one phase per tick
 * only after its randomised anti-ban delay has elapsed.
 *
 * IMPORTANT: Every click must go through
 * {@link #clickSlot(MinecraftClient, GenericContainerScreen, int)}
 * to ensure the anti-ban delay is applied.
 */
public class TaskExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger("BazaarFlipper/Executor");

    // -----------------------------------------------------------------------
    //  Slot constants (0-indexed, matching Hypixel's GenericContainerScreen)
    // -----------------------------------------------------------------------

    /** "Manage Orders" button in Bazaar main menu */
    private static final int SLOT_MANAGE_ORDERS = 49; // 0-indexed (spec says 50, 1-indexed)

    /** "Buy Order" / "Create Sell Offer" in the Product Info menu */
    private static final int SLOT_BUY_ORDER  = 15; // 0-indexed (spec says 16)
    private static final int SLOT_SELL_OFFER = 15; // same slot, different context

    /** "Confirm" slot in the confirmation menu */
    private static final int SLOT_CONFIRM = 13; // 0-indexed (spec says 13 — already 0-indexed here)

    /** "Claim Order" in the order detail menu */
    private static final int SLOT_CLAIM = 10; // 0-indexed (spec says 11)

    // -----------------------------------------------------------------------
    //  State
    // -----------------------------------------------------------------------

    private enum Phase {
        IDLE,

        // --- BUY / SELL shared phases ---
        WAIT_FOR_BAZAAR_MAIN,
        SCAN_FOR_ITEM,
        CLICK_ITEM,
        WAIT_FOR_PRODUCT_INFO,
        CLICK_ORDER_BUTTON,     // BUY ORDER or SELL OFFER
        WAIT_FOR_AMOUNT_MENU,
        CLICK_AMOUNT_PRESET,    // click a preset or wait for sign
        WAIT_SIGN_INPUT,        // sign GUI opened for custom amount
        WAIT_FOR_PRICE_MENU,
        CLICK_PRICE,
        WAIT_FOR_CONFIRM,
        CLICK_CONFIRM,
        DONE,

        // --- CLAIM_ORDERS phases ---
        OPEN_MANAGE_ORDERS,
        WAIT_FOR_ORDERS_MENU,
        SCAN_FILLED_ORDERS,
        CLICK_FILLED_ORDER,
        WAIT_FOR_ORDER_DETAIL,
        CLICK_CLAIM,
        ORDERS_DONE,

        // --- CANCEL_ORDER phases ---
        CANCEL_SCAN,
        CLICK_CANCEL_TARGET,
        CANCEL_DONE
    }

    private final ScreenTracker screenTracker;

    private Phase phase = Phase.IDLE;
    private Task activeTask = null;

    /** Countdown in ticks before we are allowed to act again. */
    private int delayTicks = 0;

    /** Slot index of the order we intend to claim/cancel. */
    private int targetSlot = -1;

    /** How many filled orders we've already claimed in one CLAIM run. */
    private int claimedCount = 0;

    public TaskExecutor(ScreenTracker screenTracker) {
        this.screenTracker = screenTracker;
    }

    // -----------------------------------------------------------------------
    //  Main tick entry point (called from ClientTickEvents.END_CLIENT_TICK)
    // -----------------------------------------------------------------------

    public void tick(MinecraftClient mc, Task task) {
        // Count down delay
        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        // Pick up a new task if we're idle
        if (phase == Phase.IDLE || phase == Phase.DONE || phase == Phase.ORDERS_DONE
                || phase == Phase.CANCEL_DONE) {
            if (task == null || task.isIdle()) return;
            startTask(task);
            return;
        }

        // Execute the current phase
        switch (activeTask.action) {
            case "BUY"          -> tickBuySell(mc, true);
            case "SELL"         -> tickBuySell(mc, false);
            case "CLAIM_ORDERS" -> tickClaimOrders(mc);
            case "CANCEL_ORDER" -> tickCancelOrder(mc);
            default             -> finishTask("Unknown action: " + activeTask.action, false);
        }
    }

    // -----------------------------------------------------------------------
    //  Task bootstrap
    // -----------------------------------------------------------------------

    private void startTask(Task task) {
        LOGGER.info("[BazaarFlipper] Starting task: {}", task);
        activeTask = task;
        targetSlot = task.slot_target;
        claimedCount = 0;

        switch (task.action) {
            case "BUY", "SELL"    -> phase = Phase.WAIT_FOR_BAZAAR_MAIN;
            case "CLAIM_ORDERS"   -> phase = Phase.OPEN_MANAGE_ORDERS;
            case "CANCEL_ORDER"   -> phase = Phase.OPEN_MANAGE_ORDERS;
            default               -> finishTask("Unknown action", false);
        }
        applyDelay(DelayUtil.randomTicks());
    }

    // -----------------------------------------------------------------------
    //  BUY / SELL state machine
    // -----------------------------------------------------------------------

    private void tickBuySell(MinecraftClient mc, boolean isBuy) {
        ScreenTracker.GuiType gui = screenTracker.getCurrentGuiType();

        switch (phase) {

            case WAIT_FOR_BAZAAR_MAIN -> {
                if (gui == ScreenTracker.GuiType.BAZAAR_MAIN
                        || gui == ScreenTracker.GuiType.BAZAAR_ITEM_LIST) {
                    phase = Phase.SCAN_FOR_ITEM;
                    applyDelay(DelayUtil.randomTicks());
                }
                // If not in Bazaar at all, wait — player must open it manually
            }

            case SCAN_FOR_ITEM -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) {
                    phase = Phase.WAIT_FOR_BAZAAR_MAIN;
                    return;
                }
                // If server told us the exact slot, use it
                if (targetSlot >= 0) {
                    phase = Phase.CLICK_ITEM;
                    applyDelay(DelayUtil.randomTicks());
                    return;
                }
                // Otherwise scan slots for matching item ID
                int found = findItemSlot(gcs, activeTask.item_id);
                if (found >= 0) {
                    targetSlot = found;
                    phase = Phase.CLICK_ITEM;
                    applyDelay(DelayUtil.randomTicks());
                } else {
                    LOGGER.warn("[BazaarFlipper] Item '{}' not found in current GUI page.",
                            activeTask.item_id);
                    // Could add search-box logic here; for now abort
                    finishTask("Item not visible in GUI", false);
                }
            }

            case CLICK_ITEM -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) {
                    phase = Phase.WAIT_FOR_BAZAAR_MAIN;
                    return;
                }
                clickSlot(mc, gcs, targetSlot);
                targetSlot = -1;
                phase = Phase.WAIT_FOR_PRODUCT_INFO;
                applyDelay(DelayUtil.randomTicks());
            }

            case WAIT_FOR_PRODUCT_INFO -> {
                if (gui == ScreenTracker.GuiType.BAZAAR_PRODUCT_INFO) {
                    phase = Phase.CLICK_ORDER_BUTTON;
                    applyDelay(DelayUtil.randomTicks());
                }
            }

            case CLICK_ORDER_BUTTON -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
                // Slot 15 (0-indexed) = "Buy Order" or "Create Sell Offer"
                clickSlot(mc, gcs, isBuy ? SLOT_BUY_ORDER : SLOT_SELL_OFFER);
                phase = Phase.WAIT_FOR_AMOUNT_MENU;
                applyDelay(DelayUtil.randomTicks());
            }

            case WAIT_FOR_AMOUNT_MENU -> {
                if (gui == ScreenTracker.GuiType.BAZAAR_AMOUNT
                        || gui == ScreenTracker.GuiType.SIGN_EDIT) {
                    phase = Phase.CLICK_AMOUNT_PRESET;
                    applyDelay(DelayUtil.randomTicks());
                }
            }

            case CLICK_AMOUNT_PRESET -> {
                if (mc.currentScreen instanceof SignEditScreen) {
                    // Handled by the SignEditScreenMixin
                    phase = Phase.WAIT_SIGN_INPUT;
                    return;
                }
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
                // Try to find a preset slot matching our amount
                int presetSlot = findAmountPreset(gcs, activeTask.amount);
                if (presetSlot >= 0) {
                    clickSlot(mc, gcs, presetSlot);
                } else {
                    // Click the "Custom Amount" slot (typically slot 31 on Hypixel)
                    clickSlot(mc, gcs, 31);
                    phase = Phase.WAIT_SIGN_INPUT;
                    applyDelay(DelayUtil.randomTicks());
                    return;
                }
                phase = Phase.WAIT_FOR_PRICE_MENU;
                applyDelay(DelayUtil.randomTicks());
            }

            case WAIT_SIGN_INPUT -> {
                // SignEditScreenMixin calls signInputComplete() when done
                // Nothing to do here — just wait
                if (gui == ScreenTracker.GuiType.BAZAAR_PRICE) {
                    phase = Phase.CLICK_PRICE;
                    applyDelay(DelayUtil.randomTicks());
                }
            }

            case WAIT_FOR_PRICE_MENU -> {
                if (gui == ScreenTracker.GuiType.BAZAAR_PRICE) {
                    phase = Phase.CLICK_PRICE;
                    applyDelay(DelayUtil.randomTicks());
                }
            }

            case CLICK_PRICE -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
                // Slot for "Top Order" / "Best Offer" typically at slot 10 or 11.
                // We prefer "Top Order ± 0.1" which is usually slot 20 on Hypixel.
                // Adjust slot numbers to your server's GUI layout.
                int priceSlot = findBestOfferSlot(gcs, isBuy);
                if (priceSlot >= 0) {
                    clickSlot(mc, gcs, priceSlot);
                } else {
                    // Fallback: custom price (slot 22) — sign input handled by mixin
                    clickSlot(mc, gcs, 22);
                }
                phase = Phase.WAIT_FOR_CONFIRM;
                applyDelay(DelayUtil.randomConfirmTicks()); // longer pause before confirm
            }

            case WAIT_FOR_CONFIRM -> {
                if (gui == ScreenTracker.GuiType.BAZAAR_CONFIRM) {
                    phase = Phase.CLICK_CONFIRM;
                    applyDelay(DelayUtil.randomConfirmTicks());
                }
            }

            case CLICK_CONFIRM -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
                clickSlot(mc, gcs, SLOT_CONFIRM);
                finishTask(null, true);
            }

            default -> LOGGER.warn("[BazaarFlipper] Unexpected phase in BUY/SELL: {}", phase);
        }
    }

    // -----------------------------------------------------------------------
    //  CLAIM_ORDERS state machine
    // -----------------------------------------------------------------------

    private void tickClaimOrders(MinecraftClient mc) {
        ScreenTracker.GuiType gui = screenTracker.getCurrentGuiType();

        switch (phase) {

            case OPEN_MANAGE_ORDERS -> {
                if (gui != ScreenTracker.GuiType.BAZAAR_MAIN) return;
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
                clickSlot(mc, gcs, SLOT_MANAGE_ORDERS);
                phase = Phase.WAIT_FOR_ORDERS_MENU;
                applyDelay(DelayUtil.randomTicks());
            }

            case WAIT_FOR_ORDERS_MENU -> {
                if (gui == ScreenTracker.GuiType.BAZAAR_ORDERS) {
                    phase = Phase.SCAN_FILLED_ORDERS;
                    applyDelay(DelayUtil.randomTicks());
                }
            }

            case SCAN_FILLED_ORDERS -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
                int filled = findFilledOrderSlot(gcs);
                if (filled < 0) {
                    // No more filled orders visible
                    LOGGER.info("[BazaarFlipper] Claimed {} orders. Done.", claimedCount);
                    finishTask(null, true);
                    return;
                }
                targetSlot = filled;
                phase = Phase.CLICK_FILLED_ORDER;
                applyDelay(DelayUtil.randomTicks());
            }

            case CLICK_FILLED_ORDER -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
                clickSlot(mc, gcs, targetSlot);
                phase = Phase.WAIT_FOR_ORDER_DETAIL;
                applyDelay(DelayUtil.randomTicks());
            }

            case WAIT_FOR_ORDER_DETAIL -> {
                // After clicking an order, a detail sub-menu opens (still GenericContainer)
                // Detect by title change or just wait then proceed
                if (gui == ScreenTracker.GuiType.BAZAAR_ORDERS || gui == ScreenTracker.GuiType.OTHER) {
                    phase = Phase.CLICK_CLAIM;
                    applyDelay(DelayUtil.randomTicks());
                }
            }

            case CLICK_CLAIM -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
                clickSlot(mc, gcs, SLOT_CLAIM);
                claimedCount++;
                // Go back to scanning for more
                phase = Phase.SCAN_FILLED_ORDERS;
                applyDelay(DelayUtil.randomTicks(10, 25));
            }

            default -> {}
        }
    }

    // -----------------------------------------------------------------------
    //  CANCEL_ORDER state machine
    // -----------------------------------------------------------------------

    private void tickCancelOrder(MinecraftClient mc) {
        ScreenTracker.GuiType gui = screenTracker.getCurrentGuiType();

        switch (phase) {
            case OPEN_MANAGE_ORDERS -> {
                if (gui != ScreenTracker.GuiType.BAZAAR_MAIN) return;
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
                clickSlot(mc, gcs, SLOT_MANAGE_ORDERS);
                phase = Phase.WAIT_FOR_ORDERS_MENU;
                applyDelay(DelayUtil.randomTicks());
            }
            case WAIT_FOR_ORDERS_MENU -> {
                if (gui == ScreenTracker.GuiType.BAZAAR_ORDERS) {
                    phase = Phase.CANCEL_SCAN;
                    applyDelay(DelayUtil.randomTicks());
                }
            }
            case CANCEL_SCAN -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
                int slot = targetSlot >= 0
                        ? targetSlot
                        : findOrderSlotByItemId(gcs, activeTask.item_id);
                if (slot < 0) {
                    finishTask("No matching order to cancel", false);
                    return;
                }
                targetSlot = slot;
                phase = Phase.CLICK_CANCEL_TARGET;
                applyDelay(DelayUtil.randomTicks());
            }
            case CLICK_CANCEL_TARGET -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
                // Right-click triggers "Cancel Order" on Hypixel
                mc.interactionManager.clickSlot(
                        gcs.getScreenHandler().syncId,
                        targetSlot,
                        1,                    // button 1 = right-click
                        SlotActionType.PICKUP,
                        mc.player);
                applyDelay(DelayUtil.randomTicks());
                finishTask(null, true);
            }
            default -> {}
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers — slot searching
    // -----------------------------------------------------------------------

    /**
     * Scans visible slots of a container for a stack whose Hypixel ID matches
     * {@code itemId}.  Returns the first matching slot index, or -1.
     */
    private int findItemSlot(GenericContainerScreen gcs, String itemId) {
        var handler = gcs.getScreenHandler();
        int rows = handler.getRows();
        int visibleSlots = rows * 9;
        for (int i = 0; i < visibleSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (NbtUtil.matchesId(stack, itemId)) return i;
        }
        return -1;
    }

    /**
     * Finds a preset amount button whose lore or name contains the requested
     * amount as a number.  Hypixel offers presets like "1", "64", "1,000" etc.
     * Returns -1 if no match found.
     */
    private int findAmountPreset(GenericContainerScreen gcs, int amount) {
        String amountStr = String.valueOf(amount);
        var handler = gcs.getScreenHandler();
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString();
            // Hypixel preset buttons show the number in their display name
            if (name.replace(",", "").contains(amountStr)) return i;
        }
        return -1;
    }

    /**
     * Locates "Top Order" (for buys) or "Insta-Sell" / "Best Offer" (for sells)
     * buttons in the price selection menu.
     */
    private int findBestOfferSlot(GenericContainerScreen gcs, boolean isBuy) {
        String keyword = isBuy ? "Top Order" : "Sell Offer";
        var handler = gcs.getScreenHandler();
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (stack.getName().getString().contains(keyword)) return i;
        }
        return -1;
    }

    /**
     * Scans the orders list for any slot whose lore contains "Filled".
     */
    private int findFilledOrderSlot(GenericContainerScreen gcs) {
        var handler = gcs.getScreenHandler();
        int visibleSlots = handler.getRows() * 9;
        for (int i = 0; i < visibleSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (NbtUtil.loreContains(stack, "Filled")) return i;
        }
        return -1;
    }

    /**
     * Scans the orders list for a slot matching the given item ID so we can
     * cancel the right order.
     */
    private int findOrderSlotByItemId(GenericContainerScreen gcs, String itemId) {
        var handler = gcs.getScreenHandler();
        int visibleSlots = handler.getRows() * 9;
        for (int i = 0; i < visibleSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (NbtUtil.matchesId(stack, itemId)) return i;
            // Fallback: check display name contains the human-readable item name
            if (stack.getName().getString().toLowerCase()
                    .contains(itemId.toLowerCase().replace("_", " "))) return i;
        }
        return -1;
    }

    // -----------------------------------------------------------------------
    //  Interaction primitives
    // -----------------------------------------------------------------------

    /**
     * Simulates a left-click on a slot in a GenericContainerScreen.
     * The anti-ban delay is applied BEFORE this call via {@link #applyDelay}.
     */
    private void clickSlot(MinecraftClient mc, GenericContainerScreen gcs, int slot) {
        LOGGER.debug("[BazaarFlipper] Clicking slot {} in '{}'",
                slot, screenTracker.getRawTitle());
        mc.interactionManager.clickSlot(
                gcs.getScreenHandler().syncId,
                slot,
                0,                        // button 0 = left-click
                SlotActionType.PICKUP,
                mc.player
        );
    }

    // -----------------------------------------------------------------------
    //  Lifecycle helpers
    // -----------------------------------------------------------------------

    private void applyDelay(int ticks) {
        delayTicks = ticks;
    }

    private void finishTask(String failReason, boolean success) {
        if (success) {
            LOGGER.info("[BazaarFlipper] Task completed: {}", activeTask.action);
            BazaarFlipperMod.getTaskManager().markTaskConsumed();
        } else {
            LOGGER.warn("[BazaarFlipper] Task failed ({}): {}", failReason, activeTask.action);
            BazaarFlipperMod.getTaskManager().markTaskFailed(failReason);
        }
        phase = Phase.IDLE;
        activeTask = null;
        targetSlot = -1;
        delayTicks = 0;
    }

    /** Called by {@link com.bazaarflipper.mixin.SignEditScreenMixin} when sign input is sent. */
    public void signInputComplete() {
        if (phase == Phase.WAIT_SIGN_INPUT) {
            phase = Phase.WAIT_FOR_PRICE_MENU;
            applyDelay(DelayUtil.randomTicks());
        }
    }

    public Phase getPhase() { return phase; }

    public boolean isWaitingForSignInput() { return phase == Phase.WAIT_SIGN_INPUT; }
}
