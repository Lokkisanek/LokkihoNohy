package com.bazaarflipper.util;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScreenClassifier {
    private static final Logger LOGGER = LoggerFactory.getLogger("BazaarFlipper");
    private static boolean debugEnabled = false;

    public enum ScreenType {
        NONE,
        SIGN,
        BAZAAR_MAIN,
        BAZAAR_BROWSE,
        ORDER_SETUP,
        CONFIRM_ORDER,
        MANAGE_ORDERS,
        UNKNOWN_CONTAINER,
        OTHER
    }

    private ScreenClassifier() {}

    public static void setDebug(boolean debug) {
        debugEnabled = debug;
    }

    public static ScreenType classify(Screen screen) {
        if (screen == null) return ScreenType.NONE;
        if (screen instanceof SignEditScreen) return ScreenType.SIGN;
        if (!(screen instanceof HandledScreen<?>)) return ScreenType.OTHER;

        String title = screen.getTitle().getString();
        // Strip color codes and formatting that Hypixel might add
        String stripped = title.replaceAll("§[0-9a-fk-or]", "").trim();
        String lower = stripped.toLowerCase();

        if (debugEnabled) {
            LOGGER.info("[BazaarFlipper] ScreenClassifier raw='{}' stripped='{}' lower='{}'", title, stripped, lower);
        }

        if (lower.contains("confirm")) return ScreenType.CONFIRM_ORDER;
        if (lower.contains("your") && lower.contains("order")) return ScreenType.MANAGE_ORDERS;
        if (lower.contains("manage") && lower.contains("order")) return ScreenType.MANAGE_ORDERS;
        if (lower.contains("how much") || lower.contains("at what price")
                || lower.contains("order setup") || lower.contains("amount")
                || lower.contains("price")) return ScreenType.ORDER_SETUP;
        // Bazaar main = title is exactly or starts with "Bazaar" (no sub-category)
        if (lower.equals("bazaar") || lower.startsWith("bazaar ➜")
                || stripped.equals("Bazaar")) return ScreenType.BAZAAR_MAIN;
        if (lower.contains("bazaar")) return ScreenType.BAZAAR_BROWSE;

        return ScreenType.UNKNOWN_CONTAINER;
    }

    public static String getRawTitle(Screen screen) {
        if (screen == null) return "";
        return screen.getTitle().getString();
    }
}
