package io.github.sx2xoox.speedrunbot.brain;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.tag.ItemTags;

/** Inventory queries the brain uses (the player sees its own inventory). */
public final class Inv {
    private final PlayerInventory inv;

    public Inv(PlayerInventory inv) {
        this.inv = inv;
    }

    public int count(Item i) {
        return Actions.count(inv, i);
    }

    public boolean has(Item i) {
        return count(i) > 0;
    }

    public int logs() {
        return Actions.count(inv, st -> ItemTags.LOGS.contains(st.getItem()));
    }

    public int planks() {
        return Actions.count(inv, st -> ItemTags.PLANKS.contains(st.getItem()));
    }

    public int cobble() {
        return count(Items.COBBLESTONE);
    }

    public boolean pickaxe() {
        return has(Items.WOODEN_PICKAXE) || stonePick();
    }

    public boolean stonePick() {
        return has(Items.STONE_PICKAXE) || has(Items.IRON_PICKAXE) || has(Items.DIAMOND_PICKAXE);
    }

    public int buckets() {
        return count(Items.BUCKET) + count(Items.WATER_BUCKET) + count(Items.LAVA_BUCKET);
    }

    public boolean lighter() {
        return has(Items.FLINT_AND_STEEL) || has(Items.FIRE_CHARGE);
    }

    /** Iron still useful for the bucket + lighter, counting ore, ingots and what is already crafted. */
    public int ironEquivalent() {
        return count(Items.IRON_ORE) + count(Items.IRON_INGOT) + Math.min(1, buckets()) * 3 + (has(Items.FLINT_AND_STEEL) ? 1 : 0);
    }

    public int throwaways() {
        return Actions.count(inv, Actions::isThrowaway);
    }

    public int fuelValueItems() {
        // items smeltable with the carried fuel: coal 8, planks/logs 1.5
        return count(Items.COAL) * 8 + count(Items.CHARCOAL) * 8 + (int) ((planks() + logs()) * 1.5);
    }

    public static boolean isPlanks(ItemStack st) {
        return ItemTags.PLANKS.contains(st.getItem());
    }

    public static boolean isLog(ItemStack st) {
        return ItemTags.LOGS.contains(st.getItem());
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(net.minecraft.util.registry.Registry.ITEM.getId(st.getItem()).getPath()).append('x').append(st.getCount());
            }
        }
        return sb.toString();
    }
}
