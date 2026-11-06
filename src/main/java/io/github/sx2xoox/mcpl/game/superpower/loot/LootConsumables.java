package io.github.sx2xoox.mcpl.game.superpower.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * The superpower-only loot items, used with a right-click during the fight: the 궁극기 결정 (ultimate gauge + a
 * configured percentage) and the 재충전 부적 (the active skill's cooldown is over at once). They are ordinary items
 * with a name, lore, enchantment glint and a PDC marker holding their kind; the match decides whether a use counts
 * ({@link io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch#chargeGaugeWithItem}).
 */
public final class LootConsumables {

    /** The consumable kinds. */
    public enum Kind {
        /** Ultimate gauge + {@code loot.ultimate-crystal.gauge} %. */
        ULTIMATE_CRYSTAL("ultimate-crystal", Material.AMETHYST_SHARD, "ULTIMATE_CRYSTAL", "궁극기결정"),
        /** Resets the active skill's cooldown. */
        RECHARGE_CHARM("recharge-charm", Material.PAPER, "RECHARGE_CHARM", "재충전부적");

        private final String key;
        private final Material material;
        private final String id;
        private final String koreanId;

        Kind(String key, Material material, String id, String koreanId) {
            this.key = key;
            this.material = material;
            this.id = id;
            this.koreanId = koreanId;
        }

        /** The message section ({@code loot.<key>}) and PDC value. */
        public String key() {
            return key;
        }

        /** The id used in loot pools ({@code ULTIMATE_CRYSTAL}). */
        public String id() {
            return id;
        }

        /** The base item (a plain item without any vanilla right-click use). */
        public Material material() {
            return material;
        }

        /** The kind named by a loot pool token ({@code ULTIMATE_CRYSTAL}, {@code ultimate-crystal}, {@code 궁극기결정}). */
        public static @Nullable Kind parse(String token) {
            String wanted = normalize(token);
            for (Kind kind : values()) {
                if (normalize(kind.id).equals(wanted) || normalize(kind.koreanId).equals(wanted)) {
                    return kind;
                }
            }
            return null;
        }

        private static @Nullable Kind ofKey(@Nullable String key) {
            for (Kind kind : values()) {
                if (kind.key.equals(key)) {
                    return kind;
                }
            }
            return null;
        }

        private static String normalize(String text) {
            return text.replace("_", "").replace("-", "").replace(" ", "").toLowerCase(Locale.ROOT);
        }
    }

    private final NamespacedKey marker;
    private final Messages messages;

    public LootConsumables(Hub hub, Messages messages) {
        this.marker = hub.key("loot_item");
        this.messages = messages;
    }

    /**
     * A stack of {@code amount} consumables of {@code kind}.
     *
     * @param gaugePercent the gauge the ultimate crystal grants, shown in its lore
     */
    public ItemStack create(Kind kind, int amount, double gaugePercent) {
        ItemStack item = ItemStack.of(kind.material);
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        String base = "loot." + kind.key;
        item.setData(DataComponentTypes.ITEM_NAME, messages.get(base + ".name").decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (Component line : messages.lines(base + ".lore", Messages.tag("amount", gaugePercent))) {
            lore.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        item.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        item.editPersistentDataContainer(pdc -> pdc.set(marker, PersistentDataType.STRING, kind.key));
        return item;
    }

    /** The consumable kind of {@code item}, or null for any other item. */
    public @Nullable Kind kindOf(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return Kind.ofKey(item.getPersistentDataContainer().get(marker, PersistentDataType.STRING));
    }
}
