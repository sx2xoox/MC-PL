package io.github.sx2xoox.mcpl.game.superpower.ui;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.UseCooldown;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * The ability item (hotbar slot 9): a configurable base item (iron ingot, in memory of the old "능력자" plugin) drawn
 * with the ability icon's item model, named in the ability color, with the skill descriptions as lore, a
 * {@code use_cooldown} component in the {@code mcpl:ability} cooldown group (so {@code player.setCooldown(group, t)}
 * shows the vanilla cooldown overlay) and a PDC marker holding the ability id.
 */
public final class AbilityItems {

    /** Hotbar slot 9. */
    public static final int SLOT = 8;

    private final NamespacedKey marker;
    private final NamespacedKey cooldownGroup;

    public AbilityItems(Hub hub) {
        this.marker = hub.key("ability_item");
        this.cooldownGroup = hub.key("ability");
    }

    /** The cooldown group shared by every ability item ({@code mcpl:ability}). */
    public Key cooldownGroup() {
        return cooldownGroup;
    }

    public ItemStack create(AbilityInfo info, AbilityParams params, Material base, AbilityTexts texts) {
        ItemStack item = ItemStack.of(base);
        item.setData(DataComponentTypes.ITEM_MODEL, info.icon().getKey());
        item.setData(DataComponentTypes.ITEM_NAME, AbilityTexts.boldName(info)
                .decoration(TextDecoration.ITALIC, false));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(texts.lore(info, params, true)));
        float seconds = (float) Math.max(0.05, params.cooldownSeconds());
        item.setData(DataComponentTypes.USE_COOLDOWN,
                UseCooldown.useCooldown(seconds).cooldownGroup(cooldownGroup).build());
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        item.editPersistentDataContainer(pdc -> pdc.set(marker, PersistentDataType.STRING, info.id()));
        return item;
    }

    public boolean isAbilityItem(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getPersistentDataContainer().has(marker);
    }

    /** The ability id stored in an ability item, or null. */
    public @Nullable String abilityId(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return item.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
    }
}
