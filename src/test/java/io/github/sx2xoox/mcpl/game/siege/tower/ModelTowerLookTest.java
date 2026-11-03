package io.github.sx2xoox.mcpl.game.siege.tower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * The model look shows the multi-part tower models only to players with the pack and the vanilla box look to everyone
 * else (per-viewer visibility through the superpower test doubles, which model Paper's {@code setVisibleByDefault}).
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
        assertEquals(3, visual.models().size(), "gate body, barrier, shield");
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
        for (String tower : List.of("front", "middle", "core")) {
            ModelTowerLook.ModelVisual visual = spawn(tower);
            assertTrue(visual.models().size() <= 12, tower + ": " + visual.models().size() + " model displays");
            assertTrue(visual.fallback().size() <= 40, tower + ": " + visual.fallback().size() + " fallback boxes");
            visual.remove();
        }
    }

    @Test
    void thePartsUseTheStageModelsInTheTeamColour() {
        ModelTowerLook.ModelVisual visual = spawn("middle");
        // (MockBukkit keeps no data components, so the model each part was given is checked instead)
        assertEquals(Arrays.asList(ref("tower_middle_base_0"), ref("tower_middle_top_0"), ref("tower_middle_ring"),
                ref("tower_middle_ring"), ref("tower_middle_core"), ref("tower_middle_shield")), visual.partModels());
        assertEquals(Color.fromRGB(NamedTextColor.RED.value()), visual.tint());

        visual.health(0.9);
        assertEquals(0, visual.stage());
        visual.health(0.5);
        assertEquals(1, visual.stage());
        assertEquals(ref("tower_middle_base_1"), visual.partModels().get(0));
        assertEquals(ref("tower_middle_top_1"), visual.partModels().get(1));
        visual.health(0.1);
        assertEquals(2, visual.stage());
        assertEquals(ref("tower_middle_top_2"), visual.partModels().get(1));
        assertNull(visual.partModels().get(4), "the data core is knocked out with the top");
        visual.health(1);
        assertEquals(0, visual.stage(), "repaired at half time");
        assertEquals(ref("tower_middle_core"), visual.partModels().get(4));

        visual.destroyed();
        assertEquals(ModelTowerLook.RUIN, visual.stage());
        server.getScheduler().performTicks(20);
        assertEquals(List.of(ref("tower_middle_base_3")), visual.partModels(), "only the ruin is left");
        assertEquals(1, visual.models().size(), "the top collapsed, the rings and the core shattered");
        visual.health(1);
        assertEquals(ModelTowerLook.RUIN, visual.stage(), "a ruin stays a ruin");
        visual.remove();
    }

    @Test
    void everyModelIsInThePackSources() {
        for (ModelRef model : ModelTowerLook.models()) {
            assertTrue(Files.exists(Path.of("src/main/resourcepack-src/models", model.id() + ".bbmodel")),
                    model.id());
        }
        assertTrue(ModelTowerLook.models().contains(ref("tower_core_heart")));
        assertTrue(ModelTowerLook.models().contains(ref("tower_front_barrier_2")));
        assertFalse(ModelTowerLook.models().contains(ref("tower_front_barrier_3")), "the barrier has no ruin");
    }

    @Test
    void theVanillaLookCracksAndFalls() {
        for (String tower : List.of("front", "middle", "core")) {
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
        // yaw 90 faces -x: the gate's barrier (a thin plate across the gate) then runs along z
        ModelTowerLook.ModelVisual visual = spawn("front", 90);
        Material glass = ModelTowerLook.glassOf(visual.tint());
        BlockDisplay barrier = visual.fallback().stream()
                .filter(d -> d instanceof BlockDisplay b && b.getBlock().getMaterial() == glass)
                .map(d -> (BlockDisplay) d)
                .filter(b -> b.getTransformation().getTranslation().y < 1)
                .findFirst().orElseThrow();
        Vector3f across = new Vector3f(1, 0, 0).rotate(barrier.getTransformation().getLeftRotation());
        assertEquals(0, across.x, 1e-4);
        assertEquals(1, Math.abs(across.z), 1e-4);
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
        assertEquals(Material.RED_STAINED_GLASS, ModelTowerLook.glassOf(Color.fromRGB(0xB02E26)));
        assertEquals(Material.BLUE_STAINED_GLASS, ModelTowerLook.glassOf(Color.fromRGB(0x3C44AA)));
        ModelTowerLook look = new ModelTowerLook(plugin, audience);
        assertEquals(6, look.hitboxScale("front", 2.5), 1e-9);
        assertEquals(7, look.hitboxScale("middle", 2.5), 1e-9);
        assertEquals(8, look.hitboxScale("core", 2.5), 1e-9);
        assertEquals(4, look.hitboxScale("core", 1.25), 1e-9, "follows the configured scale");
        assertEquals(2.5, look.hitboxScale("gate", 2.5), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> spawn("gate"));
    }
}
