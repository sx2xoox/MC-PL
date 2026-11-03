package io.github.sx2xoox.mcpl.game.siege.tower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.core.pack.ModelRef;
import io.github.sx2xoox.mcpl.core.pack.PackAudience;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.TestServer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * The model look shows the multi-part toy building models (watchtower, music-box core) only to players with the pack
 * and the vanilla box look to everyone else (per-viewer visibility through the superpower test doubles, which model Paper's {@code setVisibleByDefault}).
 */
class ModelTowerLookTest {

    /** A pack status the test sets by hand. */
    private static final class FakeAudience implements PackAudience {
        final Set<UUID> loaded = new HashSet<>();
        final List<Consumer<Player>> listeners = new ArrayList<>();

        @Override
        public boolean hasPack(Player player) {
            return loaded.contains(player.getUniqueId());
        }

        @Override
        public Subscription subscribe(Consumer<Player> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        void set(Player player, boolean has) {
            if (has) {
                loaded.add(player.getUniqueId());
            } else {
                loaded.remove(player.getUniqueId());
            }
            List.copyOf(listeners).forEach(l -> l.accept(player));
        }
    }

    private TestServer server;
    private PluginMock plugin;
    private FlatWorld world;
    private FakeAudience audience;
    private TestPlayer withPack;
    private TestPlayer without;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new TestServer());
        plugin = MockBukkit.createMockPlugin();
        world = new FlatWorld("siege");
        server.addWorld(world);
        audience = new FakeAudience();
        withPack = join("A");
        without = join("B");
        audience.set(withPack, true);
    }

    private TestPlayer join(String name) {
        TestPlayer player = new TestPlayer(server, name);
        server.addPlayer(player);
        return player;
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ModelTowerLook.ModelVisual spawn(String tower) {
        return spawn(tower, 0);
    }

    private ModelTowerLook.ModelVisual spawn(String tower, float yaw) {
        return new ModelTowerLook(plugin, audience).spawn(new Location(world, 10.5, 64, 20.5, yaw, 0), tower,
                NamedTextColor.RED);
    }

    private static void assertSees(Player player, List<Display> displays, boolean sees) {
        for (Display display : displays) {
            assertEquals(sees, player.canSee(display), player.getName() + " sees " + display.getType());
        }
    }

    private static ModelRef ref(String id) {
        return new ModelRef(id);
    }

    @Test
    void modelsForPackPlayersVanillaBlocksForTheRest() {
        ModelTowerLook.ModelVisual visual = spawn("front");
        assertEquals(4, visual.models().size(), "tower body, top, pennant, bubble");
        assertTrue(visual.fallback().size() > 10, "a vanilla silhouette of boxes");
        for (Display model : visual.models()) {
            assertFalse(model.isVisibleByDefault(), "a model is never sent to a player without the pack");
            assertFalse(model.isPersistent());
        }
        assertSees(withPack, visual.models(), true);
        assertSees(withPack, visual.fallback(), false);
        assertSees(without, visual.models(), false);
        assertSees(without, visual.fallback(), true);

        // a late joiner has no pack yet: vanilla look until their pack loads
        TestPlayer late = join("C");
        assertSees(late, visual.models(), false);
        assertSees(late, visual.fallback(), true);
        audience.set(late, true);
        assertSees(late, visual.models(), true);
        assertSees(late, visual.fallback(), false);
        // the pack failed or was replaced: back to vanilla
        audience.set(withPack, false);
        assertSees(withPack, visual.models(), false);
        assertSees(withPack, visual.fallback(), true);
        visual.remove();
    }

    @Test
    void everyTowerIsAFewDisplays() {
        for (String tower : List.of("outer", "inner", "core")) {
            ModelTowerLook.ModelVisual visual = spawn(tower);
            assertTrue(visual.models().size() <= 12, tower + ": " + visual.models().size() + " model displays");
            assertTrue(visual.fallback().size() <= 40, tower + ": " + visual.fallback().size() + " fallback boxes");
            visual.remove();
        }
    }

    @Test
    void thePartsUseTheStageModelsInTheTeamColour() {
        ModelTowerLook.ModelVisual visual = spawn("core");
        // (MockBukkit keeps no data components, so the model each part was given is checked instead)
        assertEquals(Arrays.asList(ref("structure/core_body_0"), ref("structure/core_top_0"),
                ref("structure/core_crown"), ref("structure/core_key"), ref("structure/core_shield")),
                visual.partModels());
        assertEquals(Color.fromRGB(NamedTextColor.RED.value()), visual.tint());

        visual.health(0.9);
        assertEquals(0, visual.stage());
        visual.health(0.5);
        assertEquals(1, visual.stage());
        assertEquals(ref("structure/core_body_1"), visual.partModels().get(0));
        assertEquals(ref("structure/core_top_1"), visual.partModels().get(1));
        visual.health(0.1);
        assertEquals(2, visual.stage());
        assertEquals(ref("structure/core_top_2"), visual.partModels().get(1));
        assertEquals(ref("structure/core_crown"), visual.partModels().get(2), "the crown stays on until the fall");
        visual.health(1);
        assertEquals(0, visual.stage(), "repaired at half time");

        visual.destroyed();
        assertEquals(ModelTowerLook.RUIN, visual.stage());
        server.getScheduler().performTicks(20);
        assertEquals(List.of(ref("structure/core_body_3")), visual.partModels(), "only the ruin is left");
        assertEquals(1, visual.models().size(), "the top collapsed, the crown, key and bubble are gone");
        visual.health(1);
        assertEquals(ModelTowerLook.RUIN, visual.stage(), "a ruin stays a ruin");
        visual.remove();
    }

    @Test
    void laneTowersAreWatchtowersAndThePennantFallsAtHeavyDamage() {
        ModelTowerLook.ModelVisual visual = spawn("top_outer");
        assertEquals(ModelTowerLook.TOWER, visual.look());
        assertEquals(Arrays.asList(ref("structure/tower_body_0"), ref("structure/tower_top_0"),
                ref("structure/tower_flag"), ref("structure/tower_shield")), visual.partModels());
        visual.health(0.2);
        assertNull(visual.partModels().get(2), "the pennant is knocked off with the roof's tip");
        visual.remove();
        assertEquals(ModelTowerLook.CORE, ModelTowerLook.lookOf("blue_core"));
        assertEquals(ModelTowerLook.TOWER, ModelTowerLook.lookOf("middle"));
    }

    @Test
    void everyModelIsInThePackSources() {
        for (ModelRef model : ModelTowerLook.models()) {
            assertTrue(Files.exists(Path.of("src/main/resourcepack-src/models", model.id() + ".bbmodel")),
                    model.id());
        }
        assertTrue(ModelTowerLook.models().contains(ref("structure/core_crown")));
        assertTrue(ModelTowerLook.models().contains(ref("structure/tower_top_2")));
        assertFalse(ModelTowerLook.models().contains(ref("structure/tower_top_3")), "the top has no ruin");
        assertEquals(4 + 3 + 2 + 4 + 3 + 3, ModelTowerLook.models().size());
    }

    @Test
    void theVanillaLookCracksAndFalls() {
        for (String tower : List.of("outer", "core")) {
            ModelTowerLook.ModelVisual visual = spawn(tower);
            long intactBlocks = solidFallbackBlocks(visual);
            visual.health(0.2);
            long damaged = solidFallbackBlocks(visual);
            visual.destroyed();
            long ruin = solidFallbackBlocks(visual);
            assertTrue(intactBlocks >= damaged && damaged > ruin && ruin > 0,
                    tower + ": " + intactBlocks + " " + damaged + " " + ruin);
            visual.remove();
        }
    }

    @Test
    void theFrontFacesTheBaseYaw() {
        // yaw 90 faces -x: the door (a thin plate on the front wall) then lies across x, on the -x side
        ModelTowerLook.ModelVisual visual = spawn("outer", 90);
        BlockDisplay door = visual.fallback().stream()
                .filter(d -> d instanceof BlockDisplay b && b.getBlock().getMaterial() == Material.OAK_PLANKS)
                .map(d -> (BlockDisplay) d)
                .findFirst().orElseThrow();
        Vector3f across = new Vector3f(1, 0, 0).rotate(door.getTransformation().getLeftRotation());
        assertEquals(0, across.x, 1e-4);
        assertEquals(1, Math.abs(across.z), 1e-4);
        assertTrue(door.getTransformation().getTranslation().x < -1.9, "on the front, towards -x");
        visual.remove();
    }

    private static long solidFallbackBlocks(ModelTowerLook.ModelVisual visual) {
        return visual.fallback().stream()
                .filter(d -> d instanceof BlockDisplay b && b.getBlock().getMaterial() != Material.AIR)
                .count();
    }

    @Test
    void shieldHitAndRemoveTrackEveryEntity() {
        int before = world.getEntities().size();
        ModelTowerLook.ModelVisual visual = spawn("core");
        assertTrue(world.getEntities().size() > before);
        visual.shielded(true);
        assertTrue(visual.isShielded());
        visual.hit();
        Display body = visual.models().get(0);
        assertTrue(body.isGlowing(), "a hit flashes");
        server.getScheduler().performTicks(10);
        assertFalse(body.isGlowing(), "the flash ends");
        visual.shielded(false);
        assertFalse(visual.isShielded());
        visual.destroyed();
        server.getScheduler().performTicks(5);

        visual.remove();
        assertEquals(before, world.getEntities().stream().filter(e -> !e.isDead()).count());
        assertTrue(audience.listeners.isEmpty(), "unsubscribed");
        visual.remove(); // twice is harmless
    }

    @Test
    void stagesHitboxesAndUnknownTowers() {
        assertEquals(0, ModelTowerLook.stage(1));
        assertEquals(0, ModelTowerLook.stage(0.7));
        assertEquals(1, ModelTowerLook.stage(0.6));
        assertEquals(2, ModelTowerLook.stage(0.3));
        assertEquals(2, ModelTowerLook.stage(0));
        assertEquals(Material.RED_CONCRETE, ModelTowerLook.coloured(Color.fromRGB(0xB02E26), "_CONCRETE",
                Material.WHITE_CONCRETE));
        assertEquals(Material.BLUE_WOOL, ModelTowerLook.coloured(Color.fromRGB(0x3C44AA), "_WOOL",
                Material.WHITE_WOOL));
        ModelTowerLook look = new ModelTowerLook(plugin, audience);
        assertEquals(6, look.hitboxScale("front", 2.5), 1e-9);
        assertEquals(6, look.hitboxScale("bottom_inner", 2.5), 1e-9);
        assertEquals(7, look.hitboxScale("core", 2.5), 1e-9);
        assertEquals(3.5, look.hitboxScale("core", 1.25), 1e-9, "follows the configured scale");
        // any id gets a look: the rules may name their towers freely
        ModelTowerLook.ModelVisual visual = spawn("gate");
        assertEquals(ModelTowerLook.TOWER, visual.look());
        visual.remove();
    }
}
