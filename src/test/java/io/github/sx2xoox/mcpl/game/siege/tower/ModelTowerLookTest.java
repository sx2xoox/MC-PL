package io.github.sx2xoox.mcpl.game.siege.tower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.core.pack.PackAudience;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.TestServer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * The model look shows models only to players with the pack and the vanilla look to everyone else (per-viewer
 * visibility through the superpower test doubles, which model Paper's {@code setVisibleByDefault}).
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
        return new ModelTowerLook(plugin, audience).spawn(new Location(world, 10.5, 64, 20.5), tower,
                NamedTextColor.RED);
    }

    private static void assertSees(Player player, List<Display> displays, boolean sees) {
        for (Display display : displays) {
            assertEquals(sees, player.canSee(display), player.getName() + " sees " + display.getType());
        }
    }

    @Test
    void modelsForPackPlayersVanillaBlocksForTheRest() {
        ModelTowerLook.ModelVisual visual = spawn("front");
        assertEquals(3, visual.models().size(), "body, crystal, shield");
        assertFalse(visual.fallback().isEmpty());
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
    void theBodyUsesTheStageModelInTheTeamColour() {
        ModelTowerLook.ModelVisual visual = spawn("middle");
        // (MockBukkit keeps no data components, so the model the body item was given is checked instead)
        assertEquals(Key.key("mcpl", "tower_middle_0"), visual.bodyModel().key());
        assertEquals(Color.fromRGB(NamedTextColor.RED.value()), visual.tint());

        visual.health(0.9);
        assertEquals(0, visual.stage());
        visual.health(0.5);
        assertEquals(1, visual.stage());
        assertEquals(Key.key("mcpl", "tower_middle_1"), visual.bodyModel().key());
        visual.health(0.1);
        assertEquals(2, visual.stage());
        visual.health(1);
        assertEquals(0, visual.stage(), "repaired at half time");

        visual.destroyed();
        assertEquals(ModelTowerLook.RUIN, visual.stage());
        assertEquals(Key.key("mcpl", "tower_middle_3"), visual.bodyModel().key());
        assertEquals(2, visual.models().size(), "the crystal shattered");
        visual.health(1);
        assertEquals(ModelTowerLook.RUIN, visual.stage(), "a ruin stays a ruin");
        visual.remove();
    }

    @Test
    void theVanillaLookCracksAndFalls() {
        ModelTowerLook.ModelVisual visual = spawn("core");
        long intactBlocks = solidFallbackBlocks(visual);
        visual.health(0.2);
        long damaged = solidFallbackBlocks(visual);
        visual.destroyed();
        long ruin = solidFallbackBlocks(visual);
        assertTrue(intactBlocks >= damaged && damaged > ruin && ruin > 0, intactBlocks + " " + damaged + " " + ruin);
        visual.remove();
    }

    private static long solidFallbackBlocks(ModelTowerLook.ModelVisual visual) {
        return visual.fallback().stream()
                .filter(d -> d instanceof org.bukkit.entity.BlockDisplay b && b.getBlock().getMaterial() != Material.AIR)
                .count();
    }

    @Test
    void shieldHitAndRemoveTrackEveryEntity() {
        int before = world.getEntities().size();
        ModelTowerLook.ModelVisual visual = spawn("front");
        assertTrue(world.getEntities().size() > before);
        visual.shielded(true);
        assertTrue(visual.isShielded());
        visual.hit();
        assertTrue(visual.body().isGlowing(), "a hit flashes");
        server.getScheduler().performTicks(10);
        assertFalse(visual.body().isGlowing(), "the flash ends");
        visual.shielded(false);
        assertFalse(visual.isShielded());

        visual.remove();
        assertEquals(before, world.getEntities().stream().filter(e -> !e.isDead()).count());
        assertTrue(audience.listeners.isEmpty(), "unsubscribed");
        visual.remove(); // twice is harmless
    }

    @Test
    void stagesAndUnknownTowers() {
        assertEquals(0, ModelTowerLook.stage(1));
        assertEquals(0, ModelTowerLook.stage(0.7));
        assertEquals(1, ModelTowerLook.stage(0.6));
        assertEquals(2, ModelTowerLook.stage(0.3));
        assertEquals(2, ModelTowerLook.stage(0));
        assertEquals(Material.RED_STAINED_GLASS, ModelTowerLook.glassOf(Color.fromRGB(0xB02E26)));
        assertEquals(Material.BLUE_STAINED_GLASS, ModelTowerLook.glassOf(Color.fromRGB(0x3C44AA)));
        boolean threw = false;
        try {
            spawn("gate");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw);
    }
}
