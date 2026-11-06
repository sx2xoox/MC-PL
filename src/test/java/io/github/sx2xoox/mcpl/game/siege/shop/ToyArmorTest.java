package io.github.sx2xoox.mcpl.game.siege.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.pack.PackBuilder;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureBodies;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.TestServer;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * 공성전 장난감 병정 갑옷: the looks ({@link ToyArmor#LOOKS}) and the pack files made from the same table by
 * tools/assetgen/models/toy_armor.py agree, the pack keeps every other leather piece vanilla, and dressing keeps the
 * armour's stats while giving it only vanilla looks.
 */
class ToyArmorTest {

    private static final List<String> PIECES = List.of("helmet", "chestplate", "leggings", "boots");
    private static PackBuilder.Built pack;
    private TestServer server;

    @BeforeAll
    static void build() throws IOException {
        pack = PackBuilder.build(Path.of("src/main/resourcepack-src"), "test");
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new TestServer());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void everyTeamAndTierHasItsOwnTrim() {
        Set<String> trims = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (LaneSites.Side side : LaneSites.Side.values()) {
            assertEquals(ToyArmor.MAX_TIER + 1, ToyArmor.LOOKS.get(side).size());
            for (int tier = 0; tier <= ToyArmor.MAX_TIER; tier++) {
                ToyArmor.Look look = ToyArmor.look(side, tier);
                assertTrue(trims.add(look.pattern() + " " + look.material()), "one trim marks one look: " + look);
                assertTrue(ids.add(look.id()));
                assertEquals(side == LaneSites.Side.BLUE ? CreatureBodies.BLUE : CreatureBodies.RED,
                        look.dye());
            }
        }
        assertEquals(ToyArmor.look(LaneSites.Side.RED, 3), ToyArmor.look(LaneSites.Side.RED, 7), "clamped");
        assertEquals(ToyArmor.look(LaneSites.Side.BLUE, 0), ToyArmor.look(LaneSites.Side.BLUE, -1));
    }

    @Test
    void thePackDrawsEveryLookOverTheVanillaLeather() throws IOException {
        JsonObject leather = json("assets/minecraft/equipment/leather.json");
        // the vanilla layers are kept: dyed leather + its overlay, for every layer type the leather asset has
        for (String layer : List.of("humanoid", "humanoid_leggings", "humanoid_baby", "horse_body")) {
            JsonArray layers = leather.getAsJsonObject("layers").getAsJsonArray(layer);
            assertEquals(2, layers.size(), layer);
            assertEquals("minecraft:leather", layers.get(0).getAsJsonObject().get("texture").getAsString());
            assertTrue(layers.get(0).getAsJsonObject().has("dyeable"));
            assertEquals("minecraft:leather_overlay", layers.get(1).getAsJsonObject().get("texture").getAsString());
        }
        Set<String> overrides = new HashSet<>();
        for (JsonElement element : leather.getAsJsonArray("trim_overrides")) {
            JsonObject override = element.getAsJsonObject();
            JsonObject when = override.getAsJsonObject("when");
            assertTrue(when.has("pattern") && when.has("material"), "only the exact pair, never a whole pattern");
            assertTrue(!override.has("palette"), "our texture as drawn");
            overrides.add(when.get("pattern").getAsString() + " " + when.get("material").getAsString() + " "
                    + override.get("texture").getAsString());
        }
        Set<String> looks = new HashSet<>();
        for (List<ToyArmor.Look> side : ToyArmor.LOOKS.values()) {
            for (ToyArmor.Look look : side) {
                looks.add(look.pattern().asString() + " " + look.material().asString() + " mcpl:" + look.id());
                for (String layer : List.of("humanoid", "humanoid_leggings")) {
                    String path = "assets/mcpl/textures/trims/entity/" + layer + "/" + look.id() + ".png";
                    byte[] png = pack.files().get(path);
                    assertNotNull(png, path);
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
                    assertEquals(64, image.getWidth(), path);
                    assertEquals(32, image.getHeight(), path);
                }
            }
        }
        assertEquals(looks, overrides);
    }

    @Test
    void theLeatherIconsSelectTheLooksAndFallBackToVanilla() {
        for (String piece : PIECES) {
            JsonObject model = json("assets/minecraft/items/leather_" + piece + ".json").getAsJsonObject("model");
            assertEquals("minecraft:custom_model_data", model.get("property").getAsString());
            assertEquals(0, model.get("index").getAsInt());
            Set<String> cases = new HashSet<>();
            for (JsonElement element : model.getAsJsonArray("cases")) {
                JsonObject c = element.getAsJsonObject();
                cases.add(c.get("when").getAsString());
                String icon = c.getAsJsonObject("model").get("model").getAsString();
                assertTrue(icon.startsWith("mcpl:item/toy_armor/" + piece + "_"), icon);
                String[] id = icon.split(":", 2);
                assertTrue(pack.files().containsKey("assets/" + id[0] + "/models/" + id[1] + ".json"), icon);
            }
            Set<String> looks = new HashSet<>();
            ToyArmor.LOOKS.values().forEach(side -> side.forEach(look -> looks.add(look.id())));
            assertEquals(looks, cases, piece);
            // the fallback is the 26.3 definition: the trim overlays by material, the dye tint, the plain piece
            JsonObject fallback = model.getAsJsonObject("fallback");
            assertEquals("minecraft:trim_material", fallback.get("property").getAsString());
            assertEquals(11, fallback.getAsJsonArray("cases").size());
            assertEquals("minecraft:item/leather_" + piece,
                    fallback.getAsJsonObject("fallback").get("model").getAsString());
            assertEquals("minecraft:dye", fallback.getAsJsonObject("fallback").getAsJsonArray("tints").get(0)
                    .getAsJsonObject().get("type").getAsString());
        }
    }

    @Test
    void dressingKeepsTheArmourAndItsStatsButOnlyItsLooksChange() {
        ItemStack iron = ItemStack.of(Material.IRON_CHESTPLATE);
        ToyArmor.Look look = ToyArmor.look(LaneSites.Side.BLUE, 2);
        ItemStack dressed = ToyArmor.dressed(iron, "chestplate", look, Component.text("제복"));
        assertNotNull(dressed);
        assertEquals(Material.IRON_CHESTPLATE, dressed.getType(), "the kit's piece, its armour points");
        assertEquals(look.id(), ToyArmor.lookOf(dressed));
        Equippable equippable = dressed.getData(DataComponentTypes.EQUIPPABLE);
        assertNotNull(equippable);
        assertEquals(ToyArmor.LEATHER, equippable.assetId());
        assertEquals(EquipmentSlot.CHEST, equippable.slot());
        assertEquals(Key.key("minecraft", "leather_chestplate"), dressed.getData(DataComponentTypes.ITEM_MODEL),
                "a vanilla item model: players without the pack see a dyed leather chestplate");
        assertEquals(look.dye(), dressed.getData(DataComponentTypes.DYED_COLOR).color());
        assertEquals(look.pattern(), dressed.getData(DataComponentTypes.TRIM).armorTrim().getPattern().key());
        assertEquals(look.material(), dressed.getData(DataComponentTypes.TRIM).armorTrim().getMaterial().key());

        assertNull(ToyArmor.dressed(dressed, "chestplate", look, null), "already dressed: left alone");
        assertNotNull(ToyArmor.dressed(dressed, "chestplate", ToyArmor.look(LaneSites.Side.BLUE, 3), null),
                "a new tier");
        assertNull(ToyArmor.dressed(ItemStack.of(Material.ELYTRA), "chestplate", look, null), "not armour");
        assertNull(ToyArmor.dressed(ItemStack.of(Material.IRON_SWORD), "helmet", look, null));
    }

    @Test
    void aPlayersWornArmourIsDressedAndTheRestOfTheInventoryIsNot() {
        TestPlayer player = new TestPlayer(server, "A");
        server.addPlayer(player);
        player.getInventory().setItem(EquipmentSlot.HEAD, ItemStack.of(Material.IRON_HELMET));
        player.getInventory().setItem(EquipmentSlot.FEET, ItemStack.of(Material.DIAMOND_BOOTS));
        player.getInventory().setItem(0, ItemStack.of(Material.IRON_CHESTPLATE));
        ToyArmor.dress(player, LaneSites.Side.RED, 1, piece -> null);
        // (MockBukkit's player inventory keeps no data components, so the worn pieces' kind is checked here and the
        // components on dressed(...) above)
        Map<EquipmentSlot, Material> worn = Map.of(EquipmentSlot.HEAD, Material.IRON_HELMET,
                EquipmentSlot.FEET, Material.DIAMOND_BOOTS);
        worn.forEach((slot, type) -> assertEquals(type, player.getInventory().getItem(slot).getType(), slot.name()));
        assertNull(ToyArmor.lookOf(player.getInventory().getItem(0)), "only what is worn");
        assertTrue(player.getInventory().getItem(EquipmentSlot.CHEST).isEmpty(), "nothing is added");
    }

    private static JsonObject json(String path) {
        byte[] data = pack.files().get(path);
        assertNotNull(data, path);
        return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
