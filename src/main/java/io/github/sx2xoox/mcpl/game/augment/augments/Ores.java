package io.github.sx2xoox.mcpl.game.augment.augments;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;

/** Block helpers shared by the mining augments. */
final class Ores {

    private Ores() {
    }

    /** Any ore block (coal to diamond, deepslate and nether variants, ancient debris). */
    static boolean isOre(Material type) {
        return type.name().endsWith("_ORE") || type == Material.ANCIENT_DEBRIS;
    }

    /** True if the player mines with silk touch (ores drop themselves: no bonus drops). */
    static boolean silkTouch(Player player) {
        return player.getInventory().getItemInMainHand().containsEnchantment(Enchantment.SILK_TOUCH);
    }

    /** True if the player holds a pickaxe (stone drops nothing without one). */
    static boolean pickaxe(Player player) {
        return player.getInventory().getItemInMainHand().getType().name().endsWith("_PICKAXE");
    }
}
