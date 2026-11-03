package io.github.sx2xoox.mcpl.core.pack;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import net.kyori.adventure.key.Key;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A model of the MCPL resource pack: {@code models/<id>.bbmodel} in {@code src/main/resourcepack-src} becomes the item
 * model definition {@code mcpl:<id>} ({@code assets/mcpl/items/<id>.json}), which an item shows through the
 * {@code item_model} data component (e.g. on an item display). Only players who loaded the pack see it; everyone else
 * sees a missing-model cube, so show such items only to {@link PackAudience#hasPack} players.
 *
 * @param id the model id (file name without {@code .bbmodel})
 */
public record ModelRef(String id) {

    public static final String NAMESPACE = "mcpl";

    public ModelRef {
        if (!id.matches("[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("not a model id: " + id);
        }
    }

    public Key key() {
        return Key.key(NAMESPACE, id);
    }

    /** An item drawn with this model. */
    public ItemStack item() {
        return item(null);
    }

    /** An item drawn with this model, its tinted faces ({@code minecraft:dye} tint) coloured {@code tint}. */
    public ItemStack item(@Nullable Color tint) {
        ItemStack stack = ItemStack.of(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, key());
        if (tint != null) {
            stack.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(tint));
        }
        return stack;
    }
}
