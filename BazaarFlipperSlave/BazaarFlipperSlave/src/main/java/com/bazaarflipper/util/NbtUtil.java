package com.bazaarflipper.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

public final class NbtUtil {

    private NbtUtil() {}

    public static String getHypixelId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return "";
        NbtCompound root = customData.copyNbt();
        if (!root.contains("ExtraAttributes")) return "";
        NbtCompound extra = root.getCompound("ExtraAttributes").orElse(null);
        if (extra == null || !extra.contains("id")) return "";
        return extra.getString("id").orElse("");
    }

    public static boolean matchesId(ItemStack stack, String targetId) {
        if (targetId == null || targetId.isEmpty()) return false;
        return targetId.equalsIgnoreCase(getHypixelId(stack));
    }
}
