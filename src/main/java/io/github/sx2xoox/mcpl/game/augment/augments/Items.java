package io.github.sx2xoox.mcpl.game.augment.augments;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Item helpers shared by the resource augments. */
final class Items {

    private Items() {
    }

    /** {@code amount} of {@code material} split into stacks (empty for 0 or less). */
    static ItemStack[] stacks(Material material, int amount) {
        if (amount <= 0) {
            return new ItemStack[0];
        }
        int max = Math.max(1, ItemStack.of(material).getMaxStackSize());
        int count = (amount + max - 1) / max;
        ItemStack[] out = new ItemStack[count];
        for (int i = 0; i < count; i++) {
            out[i] = ItemStack.of(material, Math.min(max, amount - i * max));
        }
        return out;
    }
}
