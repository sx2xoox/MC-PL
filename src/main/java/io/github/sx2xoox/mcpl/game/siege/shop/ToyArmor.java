package io.github.sx2xoox.mcpl.game.siege.shop;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureBodies;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.ItemArmorTrim;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.jspecify.annotations.Nullable;

/**
 * 공성전 장난감 병정 갑옷 (docs/siege-v2.md §7.3): the armour a 공성전 player wears looks like a toy soldier's uniform in
 * the team's colour, fancier with every 장난감 갑옷 tier (0 = the kit armour, I-III bought in the shop).
 *
 * <p>The pieces keep their item type and stats (the {@code games.superpower.kit} armour); only their looks change, and
 * those are vanilla components every client understands, so nothing breaks for players without the pack:
 * <ul>
 *     <li>{@code equippable} asset {@code minecraft:leather} + {@code dyed_color} (team colour) + one exact vanilla
 *     {@code trim} per (team, tier) ({@link #LOOKS}): without the pack it is team-dyed leather armour with a trim that
 *     gets busier with the tier;</li>
 *     <li>the pack's {@code assets/minecraft/equipment/leather.json} (the vanilla layers plus {@code trim_overrides})
 *     draws that exact (pattern, material) pair as our toy uniform texture over the leather, which it hides; leather
 *     armour with any other trim or none (other modes, minions, bots) looks vanilla;</li>
 *     <li>{@code item_model} = the vanilla leather piece and {@code custom_model_data} string 0 = the look id: the pack's
 *     {@code assets/minecraft/items/leather_<piece>.json} selects our icon by it, every other leather piece falls back
 *     to the vanilla definition. No {@code mcpl:} id is ever put on the item (everyone sees its components).</li>
 * </ul>
 * The pack files are made by {@code tools/assetgen/models/toy_armor.py} from the same table.
 */
public final class ToyArmor {

    /** The highest 장난감 갑옷 tier. */
    public static final int MAX_TIER = 3;
    /** The vanilla equipment asset whose trim overrides draw the uniforms. */
    public static final Key LEATHER = Key.key("minecraft", "leather");

    /** The armour pieces, by slot: the vanilla leather item each one is drawn as. */
    static final Map<EquipmentSlot, String> PIECES = new EnumMap<>(Map.of(
            EquipmentSlot.HEAD, "helmet",
            EquipmentSlot.CHEST, "chestplate",
            EquipmentSlot.LEGS, "leggings",
            EquipmentSlot.FEET, "boots"));

    /** The trim pattern of each tier (busier with the tier for players without the pack). */
    static final List<Key> TIER_PATTERNS = List.of(Key.key("minecraft", "sentry"), Key.key("minecraft", "coast"),
            Key.key("minecraft", "bolt"), Key.key("minecraft", "silence"));
    /** The trim material of each team (gold braid on blue, amber resin braid on red). */
    static final Map<LaneSites.Side, Key> TEAM_MATERIALS = new EnumMap<>(Map.of(
            LaneSites.Side.BLUE, Key.key("minecraft", "gold"),
            LaneSites.Side.RED, Key.key("minecraft", "resin")));

    /**
     * One look: the id (custom_model_data string 0, the pack's texture {@code mcpl:<id>}), the trim that marks it and
     * the dye.
     */
    public record Look(String id, Key pattern, Key material, Color dye) {
    }

    /** Every look, by side and tier. */
    public static final Map<LaneSites.Side, List<Look>> LOOKS;

    static {
        Map<LaneSites.Side, List<Look>> looks = new EnumMap<>(LaneSites.Side.class);
        for (LaneSites.Side side : LaneSites.Side.values()) {
            String team = side.name().toLowerCase(Locale.ROOT);
            Color dye = side == LaneSites.Side.BLUE ? CreatureBodies.BLUE : CreatureBodies.RED;
            looks.put(side, List.of(
                    new Look("toy_armor/" + team + "_0", TIER_PATTERNS.get(0), TEAM_MATERIALS.get(side), dye),
                    new Look("toy_armor/" + team + "_1", TIER_PATTERNS.get(1), TEAM_MATERIALS.get(side), dye),
                    new Look("toy_armor/" + team + "_2", TIER_PATTERNS.get(2), TEAM_MATERIALS.get(side), dye),
                    new Look("toy_armor/" + team + "_3", TIER_PATTERNS.get(3), TEAM_MATERIALS.get(side), dye)));
        }
        LOOKS = Map.copyOf(looks);
    }

    private ToyArmor() {
    }

    /** The look of a side's armour at a tier (clamped to 0..{@link #MAX_TIER}). */
    public static Look look(LaneSites.Side side, int tier) {
        return LOOKS.get(side).get(Math.clamp(tier, 0, MAX_TIER));
    }

    /**
     * Dresses the armour {@code player} wears as the side's toy uniform of {@code tier}; pieces already dressed so are
     * left alone (this runs every second). {@code names} gives a piece's item name ({@code helmet}, {@code chestplate},
     * {@code leggings}, {@code boots}) or null to keep the vanilla one.
     */
    public static void dress(Player player, LaneSites.Side side, int tier,
            Function<String, @Nullable Component> names) {
        Look look = look(side, tier);
        PlayerInventory inventory = player.getInventory();
        for (Map.Entry<EquipmentSlot, String> piece : PIECES.entrySet()) {
            ItemStack worn = inventory.getItem(piece.getKey());
            ItemStack dressed = dressed(worn, piece.getValue(), look, names.apply(piece.getValue()));
            if (dressed != null) {
                inventory.setItem(piece.getKey(), dressed);
            }
        }
    }

    /** The shop's icon of a 장난감 갑옷 tier: {@code chestplate} (any chest armour) in the buyer's uniform. */
    public static ItemStack icon(LaneSites.Side side, int tier, ItemStack chestplate) {
        ItemStack dressed = dressed(chestplate, "chestplate", look(side, tier), null);
        return dressed == null ? chestplate : dressed;
    }

    /** The look id an item is dressed in ({@code custom_model_data} string 0), or null. */
    public static @Nullable String lookOf(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        try {
            CustomModelData data = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
            return data == null || data.strings().isEmpty() ? null : data.strings().getFirst();
        } catch (RuntimeException testServer) {
            return null;
        }
    }

    /**
     * A copy of {@code item} (an armour piece worn as {@code piece}) dressed in {@code look}, or null when it is no
     * armour piece of that kind or already wears the look.
     */
    static @Nullable ItemStack dressed(@Nullable ItemStack item, String piece, Look look, @Nullable Component name) {
        if (item == null || item.isEmpty() || !isArmour(item, piece) || look.id().equals(lookOf(item))) {
            return null;
        }
        ItemStack out = item.clone();
        try {
            ArmorTrim trim = trim(look);
            if (trim == null) {
                return null;
            }
            // the piece's own equippable (its slot, sounds, ...) with the leather asset
            Equippable old = out.getData(DataComponentTypes.EQUIPPABLE);
            out.setData(DataComponentTypes.EQUIPPABLE, old == null
                    ? Equippable.equippable(slotOf(piece)).assetId(LEATHER).build()
                    : Equippable.equippable(old.slot())
                            .equipSound(old.equipSound())
                            .assetId(LEATHER)
                            .cameraOverlay(old.cameraOverlay())
                            .allowedEntities(old.allowedEntities())
                            .dispensable(old.dispensable())
                            .swappable(old.swappable())
                            .damageOnHurt(old.damageOnHurt())
                            .equipOnInteract(old.equipOnInteract())
                            .canBeSheared(old.canBeSheared())
                            .shearSound(old.shearSound())
                            .build());
            out.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(look.dye()));
            out.setData(DataComponentTypes.TRIM, ItemArmorTrim.itemArmorTrim(trim).build());
            out.setData(DataComponentTypes.ITEM_MODEL, Key.key("minecraft", "leather_" + piece));
            out.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                    CustomModelData.customModelData().addString(look.id()).build());
            out.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                    .addHiddenComponents(DataComponentTypes.TRIM, DataComponentTypes.DYED_COLOR).build());
            if (name != null) {
                out.setData(DataComponentTypes.ITEM_NAME, name);
            }
        } catch (RuntimeException testServer) {
            // a test server may not know a component: the armour still works, it only keeps its vanilla look
            return null;
        }
        return out;
    }

    private static EquipmentSlot slotOf(String piece) {
        for (Map.Entry<EquipmentSlot, String> entry : PIECES.entrySet()) {
            if (entry.getValue().equals(piece)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException(piece);
    }

    /** True for an armour item of the piece's kind (an elytra or a carved pumpkin is not dressed). */
    private static boolean isArmour(ItemStack item, String piece) {
        return item.getType().name().endsWith("_" + piece.toUpperCase(Locale.ROOT));
    }

    private static @Nullable ArmorTrim trim(Look look) {
        TrimPattern pattern = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).get(look.pattern());
        TrimMaterial material = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL)
                .get(look.material());
        return pattern == null || material == null ? null : new ArmorTrim(material, pattern);
    }
}
