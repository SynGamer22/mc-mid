package com.bartermarket;

import java.util.Base64;
import org.bukkit.inventory.ItemStack;

public final class ItemStackCodec {
    private ItemStackCodec() {
    }

    public static String serialize(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return null;
        }
        return Base64.getEncoder().encodeToString(itemStack.serializeAsBytes());
    }

    public static ItemStack deserialize(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        return ItemStack.deserializeBytes(bytes);
    }
}
