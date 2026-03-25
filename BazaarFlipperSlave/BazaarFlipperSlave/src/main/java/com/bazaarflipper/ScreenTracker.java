package com.bazaarflipper;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks which GUI screen is currently open by listening to Fabric's
 * ScreenEvents.  Thread-safe via AtomicReference — the task executor
 * running on the client thread reads these values every tick.
 *
 * Hypixel Bazaar screen titles (as plain strings, stripped of formatting):
 *   "Bazaar"                 — main hub menu
 *   "Your Bazaar Orders"     — manage / claim orders
 *   "Co-op Bazaar Orders"    — (ignored here)
 *   "Bazaar ▶ [Category]"   — item-list screen
 *   Contains "Product Info"  — individual item upgrade screen
 *   Contains "How many"      — amount selection (book / sign GUI)
 *   Contains "Set the price" — price selection
 */
public class ScreenTracker {

    public enum GuiType {
        NONE,
        BAZAAR_MAIN,
        BAZAAR_ITEM_LIST,     // Category browsing
        BAZAAR_PRODUCT_INFO,  // Buy/Sell Order menu for a specific item
        BAZAAR_AMOUNT,        // Amount entry (generic container variant)
        BAZAAR_PRICE,         // Price entry (generic container variant)
        BAZAAR_CONFIRM,       // Final confirm button screen
        BAZAAR_ORDERS,        // "Your Bazaar Orders" / manage orders
        SIGN_EDIT,            // Sign / AnvilScreen for custom amount input
        OTHER
    }

    private final AtomicReference<GuiType> currentGui = new AtomicReference<>(GuiType.NONE);
    private final AtomicReference<String> rawTitle = new AtomicReference<>("");
    private volatile Screen activeScreen = null;

    /** Call once during mod initialisation. */
    public void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            activeScreen = screen;
            updateGuiType(screen);
        });

        // Track close by monitoring every opened screen's remove event
        // We reset state when screen becomes null in the tick handler.
    }

    /** Called from the client tick to handle screen == null (closed). */
    public void onTick(Screen currentScreen) {
        if (currentScreen == null && activeScreen != null) {
            activeScreen = null;
            currentGui.set(GuiType.NONE);
            rawTitle.set("");
        }
    }

    // -----------------------------------------------------------------------
    //  Internal parsing
    // -----------------------------------------------------------------------

    private void updateGuiType(Screen screen) {
        if (screen instanceof SignEditScreen) {
            currentGui.set(GuiType.SIGN_EDIT);
            rawTitle.set("SIGN");
            return;
        }

        if (screen instanceof GenericContainerScreen gcs) {
            String title = gcs.getTitle().getString();
            rawTitle.set(title);
            currentGui.set(classifyContainerTitle(title));
            return;
        }

        rawTitle.set(screen.getTitle() != null ? screen.getTitle().getString() : "");
        currentGui.set(GuiType.OTHER);
    }

    /**
     * Maps a raw container title to the closest {@link GuiType}.
     * Hypixel titles use §-codes; we work on the plain-text version.
     */
    private GuiType classifyContainerTitle(String title) {
        if (title == null) return GuiType.OTHER;

        // Strip section-sign color codes for reliable matching
        String plain = title.replaceAll("§[0-9a-fk-or]", "").trim();

        if (plain.equals("Bazaar")) return GuiType.BAZAAR_MAIN;
        if (plain.startsWith("Your Bazaar Orders")) return GuiType.BAZAAR_ORDERS;
        if (plain.startsWith("Bazaar ▶")) return GuiType.BAZAAR_ITEM_LIST;
        if (plain.contains("Product Info")) return GuiType.BAZAAR_PRODUCT_INFO;
        if (plain.contains("How many")) return GuiType.BAZAAR_AMOUNT;
        if (plain.contains("Set the price") || plain.contains("Custom Price")) return GuiType.BAZAAR_PRICE;
        if (plain.contains("Confirm")) return GuiType.BAZAAR_CONFIRM;

        return GuiType.OTHER;
    }

    // -----------------------------------------------------------------------
    //  Accessors (safe to read from client thread)
    // -----------------------------------------------------------------------

    public GuiType getCurrentGuiType() {
        return currentGui.get();
    }

    public String getRawTitle() {
        return rawTitle.get();
    }

    public Screen getActiveScreen() {
        return activeScreen;
    }

    public boolean isInBazaar() {
        GuiType t = currentGui.get();
        return t == GuiType.BAZAAR_MAIN
                || t == GuiType.BAZAAR_ITEM_LIST
                || t == GuiType.BAZAAR_PRODUCT_INFO
                || t == GuiType.BAZAAR_AMOUNT
                || t == GuiType.BAZAAR_PRICE
                || t == GuiType.BAZAAR_CONFIRM
                || t == GuiType.BAZAAR_ORDERS;
    }
}
