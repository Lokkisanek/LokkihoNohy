package com.bazaarflipper.flip;

import com.bazaarflipper.BazaarFlipperMod;
import com.bazaarflipper.config.FlipMode;
import com.bazaarflipper.config.FlipperConfig;
import com.bazaarflipper.market.BazaarApi;
import com.bazaarflipper.market.BazaarProduct;
import com.bazaarflipper.market.MarketAnalyzer;
import com.bazaarflipper.safety.SafetyManager;
import com.bazaarflipper.util.FlipLog;
import com.bazaarflipper.util.ScreenClassifier;
import com.bazaarflipper.util.ScreenClassifier.ScreenType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class FlipEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("BazaarFlipper");

    private final FlipperConfig config;
    private final BazaarApi api;
    private final SafetyManager safety;
    private final FlipLog log;

    private boolean running = false;
    private FlipTask currentTask = null;
    private List<String> itemRotation = new ArrayList<>();
    private int rotationIndex = 0;
    private long nextActionMs = 0;
    private long lastRefreshMs = 0;
    private long lastDebugMs = 0;

    // Sign input coordination
    private volatile String pendingSignValue = null;
    private volatile boolean signCompleted = false;

    public FlipEngine(FlipperConfig config, BazaarApi api, SafetyManager safety, FlipLog log) {
        this.config = config;
        this.api = api;
        this.safety = safety;
        this.log = log;
    }

    public void start() {
        if (config.apiKey.isEmpty()) {
            log.log("§cCannot start: API key not set!");
            chatMsg("§c[BazaarFlipper] Cannot start: API key not set!");
            return;
        }
        if (!api.hasData()) {
            log.log("§eWaiting for market data...");
        }
        running = true;
        safety.startSession();
        ScreenClassifier.setDebug(config.debug);
        refreshItemRotation();
        log.log("§aBot started in " + config.mode.displayName + " mode.");
        chatMsg("§a[BazaarFlipper] Started in " + config.mode.displayName + " mode." +
                (config.debug ? " §e(DEBUG ON)" : ""));
        LOGGER.info("[BazaarFlipper] Engine started, mode={}, debug={}", config.mode, config.debug);
    }

    public void stop(MinecraftClient mc) {
        running = false;
        currentTask = null;
        pendingSignValue = null;
        signCompleted = false;
        safety.stop(mc);
        log.log("§cBot stopped.");
        chatMsg("§c[BazaarFlipper] Stopped.");
        LOGGER.info("[BazaarFlipper] Engine stopped.");
    }

    public boolean isRunning() {
        return running;
    }

    public FlipTask getCurrentTask() {
        return currentTask;
    }

    public String getPendingSignValue() {
        return pendingSignValue;
    }

    public void onSignCompleted() {
        signCompleted = true;
        pendingSignValue = null;
    }

    // -----------------------------------------------------------------------
    //  Main tick
    // -----------------------------------------------------------------------

    public void tick(MinecraftClient mc) {
        if (!running || mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();

        // Debug status every 5 seconds
        if (config.debug && now - lastDebugMs > 5000) {
            lastDebugMs = now;
            String taskInfo = currentTask != null
                    ? currentTask.phase + " step=" + currentTask.subStep + " item=" + currentTask.itemId
                    : "no task";
            String screen = ScreenClassifier.classify(mc.currentScreen).name();
            long delayLeft = Math.max(0, nextActionMs - now);
            String msg = String.format("[DBG] %s | screen=%s | delay=%dms | data=%b | paused=%b | panic=%b",
                    taskInfo, screen, delayLeft, api.hasData(), safety.shouldPause(), safety.shouldPanic());
            LOGGER.info("[BazaarFlipper] {}", msg);
            chatMsg("§7" + msg);
        }

        // Safety checks
        safety.tick(mc);
        if (safety.shouldPanic()) {
            if (currentTask != null && currentTask.phase != FlipPhase.PANIC) {
                log.log("§c§lPANIC — simulating human behavior");
                currentTask.phase = FlipPhase.PANIC;
            }
            safety.getHumanSimulator().tick(mc);
            return;
        }
        if (safety.shouldPause()) return;

        // Wait for delay
        if (now < nextActionMs) return;

        // Refresh item list periodically (every 2 min)
        if (now - lastRefreshMs > 120_000) {
            refreshItemRotation();
            lastRefreshMs = now;
        }

        // If no task or task complete, start next
        if (currentTask == null || currentTask.phase == FlipPhase.COMPLETE) {
            if (currentTask != null && currentTask.phase == FlipPhase.COMPLETE) {
                double profit = currentTask.estimatedProfit();
                log.addProfit(profit);
                log.log("§a§lFlip complete: " + currentTask.itemId
                        + " profit≈" + String.format("%.0f", profit));
            }
            startNextFlip();
            return;
        }

        // Process current task phase
        processPhase(mc);
    }

    // -----------------------------------------------------------------------
    //  Phase router
    // -----------------------------------------------------------------------

    private void processPhase(MinecraftClient mc) {
        switch (currentTask.phase) {
            case BUY_NAVIGATE -> handleBuyNavigate(mc);
            case BUY_CONFIGURE -> handleBuyConfigure(mc);
            case BUY_WAIT -> handleBuyWait(mc);
            case BUY_CLAIM -> handleBuyClaim(mc);
            case SELL_NAVIGATE -> handleSellNavigate(mc);
            case SELL_CONFIGURE -> handleSellConfigure(mc);
            case SELL_WAIT -> handleSellWait(mc);
            case SELL_CLAIM -> handleSellClaim(mc);
            case PANIC -> {
                // Wait for safety manager to clear panic
                if (!safety.shouldPanic()) {
                    log.log("§aResuming after panic.");
                    // Restart current side from navigation
                    if (currentTask.phase.isBuySide() || currentTask.phase == FlipPhase.PANIC) {
                        currentTask.advancePhase(FlipPhase.BUY_NAVIGATE);
                    }
                }
            }
            default -> {}
        }
    }

    // -----------------------------------------------------------------------
    //  BUY NAVIGATE: /bz → search → select item → click buy order
    // -----------------------------------------------------------------------

    private void handleBuyNavigate(MinecraftClient mc) {
        ScreenType screen = ScreenClassifier.classify(mc.currentScreen);
        if (config.debug) LOGGER.info("[BazaarFlipper] BUY_NAV step={} screen={}", currentTask.subStep, screen);

        switch (currentTask.subStep) {
            case 0 -> { // Close any screen, send /bz
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                    setDelay(600, 200);
                    return;
                }
                mc.player.networkHandler.sendChatCommand("bz");
                currentTask.subStep = 1;
                setDelay(1000, 300);
            }
            case 1 -> { // Bazaar main → click search
                if (screen == ScreenType.BAZAAR_MAIN || screen == ScreenType.BAZAAR_BROWSE) {
                    pendingSignValue = currentTask.searchTerm;
                    int slot = findSlotByNameContains(mc, "Search");
                    if (slot < 0) slot = findSlotByName(mc, "Search");
                    if (slot >= 0) {
                        clickSlot(mc, slot);
                        currentTask.subStep = 2;
                        setDelay(500, 150);
                    } else {
                        retryOrFail("Can't find Search button");
                    }
                } else if (screen == ScreenType.NONE) {
                    setDelay(500, 100); // still loading
                } else {
                    retryOrFail("Expected Bazaar, got " + screen);
                }
            }
            case 2 -> { // Sign → mixin handles auto-fill
                if (screen == ScreenType.SIGN) {
                    // Mixin should pick up pendingSignValue
                    setDelay(800, 200);
                    if (signCompleted) {
                        signCompleted = false;
                        currentTask.subStep = 3;
                        setDelay(800, 200);
                    }
                } else if (signCompleted) {
                    signCompleted = false;
                    currentTask.subStep = 3;
                    setDelay(600, 200);
                } else if (screen == ScreenType.BAZAAR_BROWSE) {
                    // Already past sign
                    currentTask.subStep = 3;
                    setDelay(200, 50);
                } else {
                    setDelay(300, 100);
                }
            }
            case 3 -> { // Search results → click item
                if (screen == ScreenType.BAZAAR_BROWSE || screen == ScreenType.BAZAAR_MAIN) {
                    int slot = findSlotByNameContains(mc, currentTask.displayName);
                    if (slot < 0) {
                        // Try partial match
                        String[] words = currentTask.displayName.split(" ");
                        if (words.length > 1) {
                            slot = findSlotByNameContains(mc, words[words.length - 1]);
                        }
                    }
                    if (slot >= 0) {
                        clickSlot(mc, slot);
                        currentTask.subStep = 4;
                        setDelay(500, 150);
                    } else {
                        retryOrFail("Can't find item in search results");
                    }
                } else {
                    setDelay(400, 100);
                }
            }
            case 4 -> { // Item page → click "Create Buy Order" or "Buy Order"
                if (screen == ScreenType.BAZAAR_BROWSE || screen == ScreenType.UNKNOWN_CONTAINER) {
                    int slot = findSlotByNameContains(mc, "Buy Order");
                    if (slot < 0) slot = findSlotByNameContains(mc, "Create Buy");
                    if (slot >= 0) {
                        clickSlot(mc, slot);
                        currentTask.advancePhase(FlipPhase.BUY_CONFIGURE);
                        setDelay(500, 150);
                    } else {
                        retryOrFail("Can't find Buy Order button");
                    }
                } else {
                    setDelay(400, 100);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  BUY CONFIGURE: amount → price → confirm
    // -----------------------------------------------------------------------

    private void handleBuyConfigure(MinecraftClient mc) {
        ScreenType screen = ScreenClassifier.classify(mc.currentScreen);
        if (config.debug) LOGGER.info("[BazaarFlipper] BUY_CFG step={} screen={}", currentTask.subStep, screen);
        switch (currentTask.subStep) {
            case 0 -> { // Order setup → click custom amount or a preset
                if (screen == ScreenType.ORDER_SETUP || screen == ScreenType.UNKNOWN_CONTAINER) {
                    // Try to find "Custom Amount" or click a preset like "64"
                    int slot = findSlotByNameContains(mc, "Custom");
                    if (slot < 0) slot = findSlotByNameContains(mc, String.valueOf(currentTask.amount));
                    if (slot >= 0) {
                        pendingSignValue = String.valueOf(currentTask.amount);
                        clickSlot(mc, slot);
                        currentTask.subStep = 1;
                        setDelay(500, 150);
                    } else {
                        // Maybe the screen already has amount set, look for price or confirm
                        int priceSlot = findSlotByNameContains(mc, "price");
                        if (priceSlot >= 0) {
                            currentTask.subStep = 2;
                        } else {
                            retryOrFail("Can't find amount selector");
                        }
                    }
                } else {
                    setDelay(400, 100);
                }
            }
            case 1 -> { // Sign for amount → auto-fill by mixin
                if (signCompleted || screen != ScreenType.SIGN) {
                    signCompleted = false;
                    currentTask.subStep = 2;
                    setDelay(600, 200);
                } else {
                    setDelay(300, 100);
                }
            }
            case 2 -> { // Back in order setup → click custom price
                if (screen == ScreenType.ORDER_SETUP || screen == ScreenType.UNKNOWN_CONTAINER) {
                    int slot = findSlotByNameContains(mc, "Custom");
                    if (slot < 0) slot = findSlotByNameContains(mc, "price");
                    if (slot < 0) slot = findSlotByNameContains(mc, "Top Order");
                    if (slot >= 0) {
                        pendingSignValue = formatPrice(currentTask.buyPrice);
                        clickSlot(mc, slot);
                        currentTask.subStep = 3;
                        setDelay(500, 150);
                    } else {
                        // Maybe price already set, look for confirm
                        int confirm = findSlotByNameContains(mc, "Confirm");
                        if (confirm >= 0) {
                            currentTask.subStep = 4;
                        } else {
                            retryOrFail("Can't find price selector");
                        }
                    }
                } else {
                    setDelay(400, 100);
                }
            }
            case 3 -> { // Sign for price
                if (signCompleted || screen != ScreenType.SIGN) {
                    signCompleted = false;
                    currentTask.subStep = 4;
                    setDelay(600, 200);
                } else {
                    setDelay(300, 100);
                }
            }
            case 4 -> { // Click confirm
                if (screen == ScreenType.CONFIRM_ORDER || screen == ScreenType.ORDER_SETUP
                        || screen == ScreenType.UNKNOWN_CONTAINER) {
                    int slot = findSlotByNameContains(mc, "Confirm");
                    if (slot >= 0) {
                        clickSlot(mc, slot);
                        log.log("§6Buy order placed: " + currentTask.amount + "x "
                                + currentTask.itemId + " @" + formatPrice(currentTask.buyPrice));
                        currentTask.advancePhase(FlipPhase.BUY_WAIT);
                        setDelay(1500, 500);
                    } else {
                        retryOrFail("Can't find Confirm button");
                    }
                } else {
                    setDelay(400, 100);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  BUY WAIT: periodically check if filled
    // -----------------------------------------------------------------------

    private void handleBuyWait(MinecraftClient mc) {
        ScreenType screen = ScreenClassifier.classify(mc.currentScreen);
        long now = System.currentTimeMillis();
        if (config.debug) LOGGER.info("[BazaarFlipper] BUY_WAIT step={} screen={}", currentTask.subStep, screen);

        switch (currentTask.subStep) {
            case 0 -> { // Wait initial period
                long waitSec = config.orderCheckIntervalSec
                        + ThreadLocalRandom.current().nextInt(15);
                if (now - currentTask.lastActionMs > waitSec * 1000L) {
                    currentTask.subStep = 1;
                    currentTask.lastActionMs = now;
                }
            }
            case 1 -> { // Close screen, open /bz
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                    setDelay(500, 150);
                    return;
                }
                mc.player.networkHandler.sendChatCommand("bz");
                currentTask.subStep = 2;
                setDelay(1000, 300);
            }
            case 2 -> { // Bazaar main → manage orders
                if (screen == ScreenType.BAZAAR_MAIN || screen == ScreenType.BAZAAR_BROWSE) {
                    int slot = findSlotByNameContains(mc, "Orders");
                    if (slot < 0) slot = findSlotByNameContains(mc, "Manage");
                    if (slot >= 0) {
                        clickSlot(mc, slot);
                        currentTask.subStep = 3;
                        setDelay(500, 150);
                    } else {
                        retryOrFail("Can't find Orders button");
                    }
                } else if (screen == ScreenType.NONE) {
                    setDelay(500, 100);
                } else {
                    retryOrFail("Expected Bazaar for orders, got " + screen);
                }
            }
            case 3 -> { // Manage orders → find our order
                if (screen == ScreenType.MANAGE_ORDERS || screen == ScreenType.UNKNOWN_CONTAINER) {
                    int filledSlot = findFilledOrder(mc, currentTask.displayName);
                    if (filledSlot >= 0) {
                        clickSlot(mc, filledSlot);
                        log.log("§aBuy order filled! Claiming...");
                        currentTask.advancePhase(FlipPhase.BUY_CLAIM);
                        setDelay(600, 200);
                    } else {
                        // Not filled — check if we've been undercut
                        BazaarProduct freshData = api.getProduct(currentTask.itemId);
                        if (freshData != null && freshData.topBuyOrderPrice > currentTask.buyPrice + 0.05) {
                            // Someone placed a higher buy order — we're no longer #1
                            log.log("§eUndercut detected! Top buy=" + String.format("%.1f", freshData.topBuyOrderPrice)
                                    + " > our=" + String.format("%.1f", currentTask.buyPrice) + ". Cancelling...");
                            chatMsg("§e[BazaarFlipper] Undercut! Cancelling buy order to re-place.");
                            // Click our order to open cancel dialog
                            int ourSlot = findOrderSlot(mc, currentTask.displayName);
                            if (ourSlot >= 0) {
                                clickSlot(mc, ourSlot);
                                currentTask.subStep = 4; // go to cancel flow
                                setDelay(500, 150);
                            } else {
                                // Order might have disappeared, restart
                                mc.player.closeHandledScreen();
                                currentTask.buyPrice = freshData.ourBuyPrice();
                                currentTask.advancePhase(FlipPhase.BUY_NAVIGATE);
                                setDelay(1000, 300);
                            }
                        } else {
                            mc.player.closeHandledScreen();
                            currentTask.subStep = 0;
                            currentTask.lastActionMs = now;
                            setDelay(500, 150);
                            log.log("§7Buy order not filled yet, still #1.");
                        }
                    }
                } else {
                    setDelay(400, 100);
                }
            }
            case 4 -> { // Cancel undercut buy order
                if (screen == ScreenType.UNKNOWN_CONTAINER || screen == ScreenType.MANAGE_ORDERS
                        || screen == ScreenType.CONFIRM_ORDER) {
                    int cancelSlot = findSlotByNameContains(mc, "Cancel");
                    if (cancelSlot >= 0) {
                        clickSlot(mc, cancelSlot);
                        currentTask.subStep = 5;
                        setDelay(800, 200);
                    } else {
                        // No cancel button found, close and restart
                        mc.player.closeHandledScreen();
                        BazaarProduct fresh = api.getProduct(currentTask.itemId);
                        if (fresh != null) currentTask.buyPrice = fresh.ourBuyPrice();
                        currentTask.advancePhase(FlipPhase.BUY_NAVIGATE);
                        setDelay(1000, 300);
                    }
                } else {
                    setDelay(400, 100);
                }
            }
            case 5 -> { // After cancel, close and re-place with better price
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                    setDelay(500, 150);
                    return;
                }
                BazaarProduct fresh = api.getProduct(currentTask.itemId);
                if (fresh != null) {
                    currentTask.buyPrice = fresh.ourBuyPrice();
                    currentTask.sellPrice = fresh.ourSellPrice();
                }
                log.log("§bRe-placing buy order at " + formatPrice(currentTask.buyPrice));
                currentTask.advancePhase(FlipPhase.BUY_NAVIGATE);
                setDelay(1500, 500);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  BUY CLAIM
    // -----------------------------------------------------------------------

    private void handleBuyClaim(MinecraftClient mc) {
        ScreenType screen = ScreenClassifier.classify(mc.currentScreen);
        if (config.debug) LOGGER.info("[BazaarFlipper] BUY_CLAIM step={} screen={}", currentTask.subStep, screen);
        switch (currentTask.subStep) {
            case 0 -> { // Look for "Claim" button in current screen
                if (screen == ScreenType.UNKNOWN_CONTAINER || screen == ScreenType.MANAGE_ORDERS) {
                    int slot = findSlotByNameContains(mc, "Claim");
                    if (slot >= 0) {
                        clickSlot(mc, slot);
                        currentTask.subStep = 1;
                        setDelay(800, 200);
                    } else {
                        // Already claimed or screen changed, try closing
                        currentTask.subStep = 1;
                        setDelay(300, 100);
                    }
                } else {
                    setDelay(400, 100);
                }
            }
            case 1 -> { // Close screen, move to sell
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                    setDelay(500, 150);
                    return;
                }
                log.log("§aBuy claimed! Starting sell...");
                currentTask.advancePhase(FlipPhase.SELL_NAVIGATE);
                setDelay(1500, 500);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  SELL NAVIGATE: same flow as buy but for selling
    // -----------------------------------------------------------------------

    private void handleSellNavigate(MinecraftClient mc) {
        ScreenType screen = ScreenClassifier.classify(mc.currentScreen);
        if (config.debug) LOGGER.info("[BazaarFlipper] SELL_NAV step={} screen={}", currentTask.subStep, screen);
        switch (currentTask.subStep) {
            case 0 -> {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                    setDelay(600, 200);
                    return;
                }
                mc.player.networkHandler.sendChatCommand("bz");
                currentTask.subStep = 1;
                setDelay(1000, 300);
            }
            case 1 -> {
                if (screen == ScreenType.BAZAAR_MAIN || screen == ScreenType.BAZAAR_BROWSE) {
                    pendingSignValue = currentTask.searchTerm;
                    int slot = findSlotByNameContains(mc, "Search");
                    if (slot < 0) slot = findSlotByName(mc, "Search");
                    if (slot >= 0) {
                        clickSlot(mc, slot);
                        currentTask.subStep = 2;
                        setDelay(500, 150);
                    } else {
                        retryOrFail("Can't find Search (sell)");
                    }
                } else if (screen == ScreenType.NONE) {
                    setDelay(500, 100);
                } else {
                    retryOrFail("Expected Bazaar for sell, got " + screen);
                }
            }
            case 2 -> {
                if (signCompleted || screen != ScreenType.SIGN) {
                    signCompleted = false;
                    currentTask.subStep = 3;
                    setDelay(800, 200);
                } else {
                    setDelay(300, 100);
                }
            }
            case 3 -> {
                if (screen == ScreenType.BAZAAR_BROWSE || screen == ScreenType.BAZAAR_MAIN) {
                    int slot = findSlotByNameContains(mc, currentTask.displayName);
                    if (slot < 0) {
                        String[] words = currentTask.displayName.split(" ");
                        if (words.length > 1) {
                            slot = findSlotByNameContains(mc, words[words.length - 1]);
                        }
                    }
                    if (slot >= 0) {
                        clickSlot(mc, slot);
                        currentTask.subStep = 4;
                        setDelay(500, 150);
                    } else {
                        retryOrFail("Can't find item in search results (sell)");
                    }
                } else {
                    setDelay(400, 100);
                }
            }
            case 4 -> {
                if (screen == ScreenType.BAZAAR_BROWSE || screen == ScreenType.UNKNOWN_CONTAINER) {
                    int slot = findSlotByNameContains(mc, "Sell Order");
                    if (slot < 0) slot = findSlotByNameContains(mc, "Create Sell");
                    if (slot >= 0) {
                        clickSlot(mc, slot);
                        currentTask.advancePhase(FlipPhase.SELL_CONFIGURE);
                        setDelay(500, 150);
                    } else {
                        retryOrFail("Can't find Sell Order button");
                    }
                } else {
                    setDelay(400, 100);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  SELL CONFIGURE: amount → price → confirm
    // -----------------------------------------------------------------------

    private void handleSellConfigure(MinecraftClient mc) {
        ScreenType screen = ScreenClassifier.classify(mc.currentScreen);
        if (config.debug) LOGGER.info("[BazaarFlipper] SELL_CFG step={} screen={}", currentTask.subStep, screen);

        switch (currentTask.subStep) {
            case 0 -> {
                if (screen == ScreenType.ORDER_SETUP || screen == ScreenType.UNKNOWN_CONTAINER) {
                    int slot = findSlotByNameContains(mc, "Custom");
                    if (slot < 0) slot = findSlotByNameContains(mc, String.valueOf(currentTask.amount));
                    if (slot >= 0) {
                        pendingSignValue = String.valueOf(currentTask.amount);
                        clickSlot(mc, slot);
                        currentTask.subStep = 1;
                        setDelay(500, 150);
                    } else {
                        int priceSlot = findSlotByNameContains(mc, "price");
                        if (priceSlot >= 0) {
                            currentTask.subStep = 2;
                        } else {
                            retryOrFail("Can't find amount selector (sell)");
                        }
                    }
                } else {
                    setDelay(400, 100);
                }
            }
            case 1 -> {
                if (signCompleted || screen != ScreenType.SIGN) {
                    signCompleted = false;
                    currentTask.subStep = 2;
                    setDelay(600, 200);
                } else {
                    setDelay(300, 100);
                }
            }
            case 2 -> {
                if (screen == ScreenType.ORDER_SETUP || screen == ScreenType.UNKNOWN_CONTAINER) {
                    int slot = findSlotByNameContains(mc, "Custom");
                    if (slot < 0) slot = findSlotByNameContains(mc, "price");
                    if (slot < 0) slot = findSlotByNameContains(mc, "Top Order");
                    if (slot >= 0) {
                        pendingSignValue = formatPrice(currentTask.sellPrice);
                        clickSlot(mc, slot);
                        currentTask.subStep = 3;
                        setDelay(500, 150);
                    } else {
                        int confirm = findSlotByNameContains(mc, "Confirm");
                        if (confirm >= 0) {
                            currentTask.subStep = 4;
                        } else {
                            retryOrFail("Can't find price selector (sell)");
                        }
                    }
                } else {
                    setDelay(400, 100);
                }
            }
            case 3 -> {
                if (signCompleted || screen != ScreenType.SIGN) {
                    signCompleted = false;
                    currentTask.subStep = 4;
                    setDelay(600, 200);
                } else {
                    setDelay(300, 100);
                }
            }
            case 4 -> {
                if (screen == ScreenType.CONFIRM_ORDER || screen == ScreenType.ORDER_SETUP
                        || screen == ScreenType.UNKNOWN_CONTAINER) {
                    int slot = findSlotByNameContains(mc, "Confirm");
                    if (slot >= 0) {
                        clickSlot(mc, slot);
                        log.log("§6Sell order placed: " + currentTask.amount + "x "
                                + currentTask.itemId + " @" + formatPrice(currentTask.sellPrice));
                        currentTask.advancePhase(FlipPhase.SELL_WAIT);
                        setDelay(1500, 500);
                    } else {
                        retryOrFail("Can't find Confirm button (sell)");
                    }
                } else {
                    setDelay(400, 100);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  SELL WAIT & CLAIM (mirrors buy wait/claim)
    // -----------------------------------------------------------------------

    private void handleSellWait(MinecraftClient mc) {
        ScreenType screen = ScreenClassifier.classify(mc.currentScreen);
        long now = System.currentTimeMillis();
        if (config.debug) LOGGER.info("[BazaarFlipper] SELL_WAIT step={} screen={}", currentTask.subStep, screen);
        switch (currentTask.subStep) {
            case 0 -> {
                long waitSec = config.orderCheckIntervalSec
                        + ThreadLocalRandom.current().nextInt(15);
                if (now - currentTask.lastActionMs > waitSec * 1000L) {
                    currentTask.subStep = 1;
                    currentTask.lastActionMs = now;
                }
            }
            case 1 -> {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                    setDelay(500, 150);
                    return;
                }
                mc.player.networkHandler.sendChatCommand("bz");
                currentTask.subStep = 2;
                setDelay(1000, 300);
            }
            case 2 -> {
                if (screen == ScreenType.BAZAAR_MAIN || screen == ScreenType.BAZAAR_BROWSE) {
                    int slot = findSlotByNameContains(mc, "Orders");
                    if (slot < 0) slot = findSlotByNameContains(mc, "Manage");
                    if (slot >= 0) {
                        clickSlot(mc, slot);
                        currentTask.subStep = 3;
                        setDelay(500, 150);
                    } else {
                        retryOrFail("Can't find Orders button (sell check)");
                    }
                } else if (screen == ScreenType.NONE) {
                    setDelay(500, 100);
                } else {
                    retryOrFail("Expected Bazaar for sell orders, got " + screen);
                }
            }
            case 3 -> {
                if (screen == ScreenType.MANAGE_ORDERS || screen == ScreenType.UNKNOWN_CONTAINER) {
                    int filledSlot = findFilledOrder(mc, currentTask.displayName);
                    if (filledSlot >= 0) {
                        clickSlot(mc, filledSlot);
                        log.log("§aSell order filled! Claiming...");
                        currentTask.advancePhase(FlipPhase.SELL_CLAIM);
                        setDelay(600, 200);
                    } else {
                        // Check if we've been undercut on sell side
                        BazaarProduct freshData = api.getProduct(currentTask.itemId);
                        if (freshData != null && freshData.topSellOrderPrice < currentTask.sellPrice - 0.05) {
                            // Someone placed a lower sell order — we're no longer #1
                            log.log("§eUndercut on sell! Top sell=" + String.format("%.1f", freshData.topSellOrderPrice)
                                    + " < our=" + String.format("%.1f", currentTask.sellPrice) + ". Cancelling...");
                            chatMsg("§e[BazaarFlipper] Undercut on sell! Cancelling to re-place.");
                            int ourSlot = findOrderSlot(mc, currentTask.displayName);
                            if (ourSlot >= 0) {
                                clickSlot(mc, ourSlot);
                                currentTask.subStep = 4;
                                setDelay(500, 150);
                            } else {
                                mc.player.closeHandledScreen();
                                currentTask.sellPrice = freshData.ourSellPrice();
                                currentTask.advancePhase(FlipPhase.SELL_NAVIGATE);
                                setDelay(1000, 300);
                            }
                        } else {
                            mc.player.closeHandledScreen();
                            currentTask.subStep = 0;
                            currentTask.lastActionMs = now;
                            setDelay(500, 150);
                            log.log("§7Sell order not filled yet, still top.");
                        }
                    }
                } else {
                    setDelay(400, 100);
                }
            }
            case 4 -> { // Cancel undercut sell order
                if (screen == ScreenType.UNKNOWN_CONTAINER || screen == ScreenType.MANAGE_ORDERS
                        || screen == ScreenType.CONFIRM_ORDER) {
                    int cancelSlot = findSlotByNameContains(mc, "Cancel");
                    if (cancelSlot >= 0) {
                        clickSlot(mc, cancelSlot);
                        currentTask.subStep = 5;
                        setDelay(800, 200);
                    } else {
                        mc.player.closeHandledScreen();
                        BazaarProduct fresh = api.getProduct(currentTask.itemId);
                        if (fresh != null) currentTask.sellPrice = fresh.ourSellPrice();
                        currentTask.advancePhase(FlipPhase.SELL_NAVIGATE);
                        setDelay(1000, 300);
                    }
                } else {
                    setDelay(400, 100);
                }
            }
            case 5 -> { // After cancel, close and re-place sell
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                    setDelay(500, 150);
                    return;
                }
                BazaarProduct fresh = api.getProduct(currentTask.itemId);
                if (fresh != null) {
                    currentTask.sellPrice = fresh.ourSellPrice();
                }
                log.log("§bRe-placing sell order at " + formatPrice(currentTask.sellPrice));
                currentTask.advancePhase(FlipPhase.SELL_NAVIGATE);
                setDelay(1500, 500);
            }
        }
    }

    private void handleSellClaim(MinecraftClient mc) {
        ScreenType screen = ScreenClassifier.classify(mc.currentScreen);
        if (config.debug) LOGGER.info("[BazaarFlipper] SELL_CLAIM step={} screen={}", currentTask.subStep, screen);

        switch (currentTask.subStep) {
            case 0 -> {
                if (screen == ScreenType.UNKNOWN_CONTAINER || screen == ScreenType.MANAGE_ORDERS) {
                    int slot = findSlotByNameContains(mc, "Claim");
                    if (slot >= 0) {
                        clickSlot(mc, slot);
                        currentTask.subStep = 1;
                        setDelay(800, 200);
                    } else {
                        currentTask.subStep = 1;
                        setDelay(300, 100);
                    }
                } else {
                    setDelay(400, 100);
                }
            }
            case 1 -> {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                    setDelay(500, 150);
                    return;
                }
                currentTask.advancePhase(FlipPhase.COMPLETE);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Task management
    // -----------------------------------------------------------------------

    private void startNextFlip() {
        if (!api.hasData()) {
            if (config.debug) {
                LOGGER.info("[BazaarFlipper] startNextFlip: no market data yet");
                chatMsg("§7[DBG] Waiting for market data...");
            }
            setDelay(5000, 1000);
            return;
        }

        BazaarProduct product = null;

        if (config.mode == FlipMode.SPECIFIC) {
            if (config.specificItemId.isEmpty()) {
                log.log("§cSpecific mode but no item selected!");
                chatMsg("§c[BazaarFlipper] No specific item set!");
                running = false;
                return;
            }
            product = api.getProduct(config.specificItemId);
            if (product == null) {
                log.log("§cItem not found: " + config.specificItemId);
                if (config.debug) chatMsg("§7[DBG] Item not found in API: " + config.specificItemId);
                setDelay(10000, 2000);
                return;
            }
        } else {
            if (itemRotation.isEmpty()) {
                refreshItemRotation();
                if (itemRotation.isEmpty()) {
                    log.log("§eNo profitable items found. Waiting...");
                    if (config.debug) {
                        int totalProducts = api.getAllProducts().size();
                        chatMsg("§7[DBG] No profitable items found (" + totalProducts + " products checked, minMargin=" + config.minMarginPct + "%)");
                    }
                    setDelay(30000, 5000);
                    return;
                }
            }
            String itemId = itemRotation.get(rotationIndex % itemRotation.size());
            rotationIndex = (rotationIndex + 1) % itemRotation.size();
            product = api.getProduct(itemId);
            if (product == null) {
                if (config.debug) chatMsg("§7[DBG] Product null for " + itemId);
                setDelay(5000, 1000);
                return;
            }
        }

        if (product.marginPct() < config.minMarginPct) {
            log.log("§e" + product.productId + " margin too low ("
                    + String.format("%.1f%%", product.marginPct()) + "), skipping.");
            setDelay(5000, 1000);
            return;
        }

        int amount = config.maxFlipAmount(product.ourBuyPrice());
        if (amount <= 0) {
            log.log("§eCan't afford " + product.productId);
            setDelay(5000, 1000);
            return;
        }

        currentTask = new FlipTask(product, amount);
        log.log("§bStarting flip: " + amount + "x " + product.productId
                + " margin=" + String.format("%.1f%%", product.marginPct()));
        setDelay(1000, 300);
    }

    private void refreshItemRotation() {
        if (!api.hasData()) return;
        List<BazaarProduct> best = MarketAnalyzer.findBestFlips(
                api.getAllProducts(), config, config.mode.maxConcurrentFlips);
        itemRotation.clear();
        for (BazaarProduct p : best) {
            itemRotation.add(p.productId);
        }
        if (!itemRotation.isEmpty()) {
            log.log("§bItem rotation: " + String.join(", ", itemRotation));
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private void setDelay(int meanMs, int stdDevMs) {
        long delay = (long) (meanMs + ThreadLocalRandom.current().nextGaussian() * stdDevMs);
        delay = Math.max(meanMs / 3, Math.min(delay, meanMs * 3));
        nextActionMs = System.currentTimeMillis() + delay;
    }

    private void clickSlot(MinecraftClient mc, int slotIndex) {
        if (mc.currentScreen instanceof HandledScreen<?> handled) {
            int syncId = handled.getScreenHandler().syncId;
            mc.interactionManager.clickSlot(syncId, slotIndex, 0,
                    SlotActionType.PICKUP, mc.player);
            if (currentTask != null) currentTask.lastActionMs = System.currentTimeMillis();
        }
    }

    private int findSlotByName(MinecraftClient mc, String exactName) {
        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) return -1;
        var handler = handled.getScreenHandler();
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (!stack.isEmpty() && stack.getName().getString().equals(exactName)) {
                return i;
            }
        }
        return -1;
    }

    private int findSlotByNameContains(MinecraftClient mc, String substring) {
        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) return -1;
        var handler = handled.getScreenHandler();
        String lower = substring.toLowerCase();
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (!stack.isEmpty() && stack.getName().getString().toLowerCase().contains(lower)) {
                return i;
            }
        }
        return -1;
    }

    private int findFilledOrder(MinecraftClient mc, String itemName) {
        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) return -1;
        var handler = handled.getScreenHandler();
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString();
            if (!name.toLowerCase().contains(itemName.toLowerCase())) continue;
            // Check lore for "100%" or "Filled" or "FILLED"
            var loreComp = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            if (loreComp != null) {
                for (var line : loreComp.lines()) {
                    String text = line.getString().toLowerCase();
                    if (text.contains("100%") || text.contains("filled") || text.contains("claim")) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /** Find any order slot matching item name (regardless of fill status) */
    private int findOrderSlot(MinecraftClient mc, String itemName) {
        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) return -1;
        var handler = handled.getScreenHandler();
        String lower = itemName.toLowerCase();
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString().toLowerCase();
            if (name.contains(lower)) return i;
        }
        return -1;
    }

    private void retryOrFail(String reason) {
        if (currentTask == null) return;
        if (currentTask.canRetry()) {
            currentTask.retry();
            log.log("§eRetry (" + currentTask.retries + "): " + reason);
            setDelay(2000, 500);
            // Close screen to reset
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.currentScreen != null) {
                mc.player.closeHandledScreen();
            }
        } else {
            log.log("§c§lFailed: " + reason + " — skipping flip.");
            currentTask.advancePhase(FlipPhase.COMPLETE);
            setDelay(3000, 1000);
        }
    }

    private String formatPrice(double price) {
        if (price == Math.floor(price)) {
            return String.valueOf((long) price);
        }
        return String.format("%.1f", price);
    }

    public List<String> getItemRotation() {
        return itemRotation;
    }

    private void chatMsg(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(net.minecraft.text.Text.literal(msg), false);
        }
    }
}
