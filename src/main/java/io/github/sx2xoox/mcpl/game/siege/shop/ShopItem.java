package io.github.sx2xoox.mcpl.game.siege.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * The items of the 공성전 v2 shop (docs/siege-v2.md §3). Upgrades of one {@link #line()} replace each other in the same
 * slot (buying the next tier costs the difference); consumables stack in one slot; every other item is owned once.
 * Prices and effects are {@link ShopSettings}.
 */
public enum ShopItem {
    CANDY(Category.BASIC, Material.COOKIE, null, 0, true, 50),
    ARMOR_1(Category.BASIC, Material.LEATHER_CHESTPLATE, "armor", 1, false, 400),
    ARMOR_2(Category.BASIC, Material.CHAINMAIL_CHESTPLATE, "armor", 2, false, 800),
    ARMOR_3(Category.BASIC, Material.IRON_CHESTPLATE, "armor", 3, false, 1300),
    WOODEN_SWORD(Category.ATTACK, Material.WOODEN_SWORD, "sword", 1, false, 350),
    STEEL_SWORD(Category.ATTACK, Material.IRON_SWORD, "sword", 2, false, 850),
    ORB(Category.ATTACK, Material.HEART_OF_THE_SEA, null, 0, false, 900),
    SHOES(Category.UTILITY, Material.LEATHER_BOOTS, null, 0, false, 300),
    HOURGLASS(Category.UTILITY, Material.CLOCK, null, 0, false, 800),
    HAMMER(Category.SIEGE, Material.MACE, null, 0, false, 700),
    WARD(Category.VISION, Material.SPYGLASS, null, 0, true, 75),
    REVIVE_CLOCK(Category.SURVIVAL, Material.RECOVERY_COMPASS, null, 0, false, 600);

    /** The shop's tabs. */
    public enum Category {
        BASIC(Material.COOKIE), ATTACK(Material.IRON_SWORD), UTILITY(Material.LEATHER_BOOTS), SIEGE(Material.MACE),
        VISION(Material.SPYGLASS), SURVIVAL(Material.RECOVERY_COMPASS);

        private final Material icon;

        Category(Material icon) {
            this.icon = icon;
        }

        public Material icon() {
            return icon;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public List<ShopItem> items() {
            List<ShopItem> out = new ArrayList<>();
            for (ShopItem item : ShopItem.values()) {
                if (item.category == this) {
                    out.add(item);
                }
            }
            return out;
        }
    }

    private final Category category;
    private final Material icon;
    private final @Nullable String line;
    private final int tier;
    private final boolean consumable;
    private final int defaultPrice;

    ShopItem(Category category, Material icon, @Nullable String line, int tier, boolean consumable,
            int defaultPrice) {
        this.category = category;
        this.icon = icon;
        this.line = line;
        this.tier = tier;
        this.consumable = consumable;
        this.defaultPrice = defaultPrice;
    }

    public Category category() {
        return category;
    }

    public Material icon() {
        return icon;
    }

    /** The upgrade line ({@code armor}, {@code sword}) or null. */
    public @Nullable String line() {
        return line;
    }

    /** Tier within the line (1 = first). */
    public int tier() {
        return tier;
    }

    public boolean consumable() {
        return consumable;
    }

    int defaultPrice() {
        return defaultPrice;
    }

    /** Settings and message key ({@code armor_1}, {@code candy}, ...). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Same upgrade line. */
    public boolean sameLine(ShopItem other) {
        return line != null && line.equals(other.line);
    }
}
