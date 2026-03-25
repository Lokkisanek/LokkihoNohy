package com.bazaarflipper.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

/**
 * Utility for extracting Hypixel-specific NBT data from ItemStacks.
 *
 * In Hypixel Skyblock, every custom item carries a "minecraft:custom_data"
 * component (formerly the custom NBT tag).  Inside that lives:
 *   ExtraAttributes {
 *       id: "ENCHANTED_COBBLESTONE"
 *       ...
 *   }
 *
 * MC 1.21.1 uses the Data Component system instead of raw NBT on ItemStack,
 * but Hypixel still stores its payload under DataComponentTypes.CUSTOM_DATA.
 */
public final class NbtUtil {

    private NbtUtil() {}

    /**
     * Returns the Hypixel item ID string (e.g. "ENCHANTED_COBBLESTONE")
     * from the given stack, or an empty string if it cannot be found.
     */
    public static String getHypixelId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";

        // Retrieve the custom data component (replaces old stack.getNbt() in 1.20.5+)
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return "";

        NbtCompound root = customData.copyNbt();
        if (!root.contains("ExtraAttributes")) return "";

        NbtCompound extra = root.getCompound("ExtraAttributes");
        if (!extra.contains("id")) return "";

        return extra.getString("id");
    }

    /**
     * Returns true if the given stack's Hypixel ID matches the target (case-insensitive).
     */
    public static boolean matchesId(ItemStack stack, String targetId) {
        if (targetId == null || targetId.isEmpty()) return false;
        return targetId.equalsIgnoreCase(getHypixelId(stack));
    }

    /**
     * Reads the display name lore as a plain string array for scanning
     * keywords like "Filled" in order-management menus.
     * Returns an empty array on failure.
     */
    public static String[] getLoreLines(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new String[0];
        var loreComponent = stack.get(DataComponentTypes.LORE);
        if (loreComponent == null) return new String[0];

        return loreComponent.lines().stream()
                .map(text -> text.getString())
                .toArray(String[]::new);
    }

    /**
     * Checks if any lore line of the stack contains the given keyword.
     */
    public static boolean loreContains(ItemStack stack, String keyword) {
        for (String line : getLoreLines(stack)) {
            if (line.contains(keyword)) return true;
        }
        return false;
    }
}
