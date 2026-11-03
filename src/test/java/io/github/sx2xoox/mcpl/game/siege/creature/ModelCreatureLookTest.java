package io.github.sx2xoox.mcpl.game.siege.creature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * The creature look: pack players see the toy model following the mob (and hit the mob through a hitbox), everyone
 * else sees the vanilla mob; views swap with the pack status; everything it made is removed again.
 */
class ModelCreatureLookTest {

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
    private final List<String> hits = new ArrayList<>();
    private ModelCreatureLook look;

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
        look = new ModelCreatureLook(plugin, audience, (player, body) -> hits.add(player.getName() + ">"
                + body.getUniqueId()));
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

    private Zombie mob(double x, double z, float yaw) {
        return world.spawn(new Location(world, x, 64, z, yaw, 0), Zombie.class);
    }

    private static void assertSees(Player player, List<? extends Entity> entities, boolean sees) {
        for (Entity entity : entities) {
            assertEquals(sees, player.canSee(entity), player.getName() + " sees " + entity.getType());
        }
    }

    @Test
    void packPlayersSeeTheModelOthersTheVanillaMob() {
        Zombie body = mob(5, 5, 0);
        ModelCreatureLook.ModelCreature soldier = look.attach(body, "melee", Color.RED);
        assertEquals(CreatureModels.SOLDIER, soldier.kind());
        assertEquals(2, soldier.displays().size(), "the soldier and his wind-up key");
        for (ItemDisplay display : soldier.displays()) {
            assertFalse(display.isVisibleByDefault(), "a model is never sent to a player without the pack");
            assertFalse(display.isPersistent());
        }
        assertFalse(soldier.hitbox().isVisibleByDefault());

        assertSees(withPack, soldier.displays(), true);
        assertSees(withPack, List.of(soldier.hitbox()), true);
        assertSees(withPack, List.of(body), false);
        assertSees(without, soldier.displays(), false);
        assertSees(without, List.of(soldier.hitbox()), false);
        assertSees(without, List.of(body), true);

        // the pack loads late / fails: the views swap
        audience.set(without, true);
        assertSees(without, soldier.displays(), true);
        assertSees(without, List.of(body), false);
        audience.set(withPack, false);
        assertSees(withPack, soldier.displays(), false);
        assertSees(withPack, List.of(soldier.hitbox()), false);
        assertSees(withPack, List.of(body), true);
        soldier.remove();
    }

    @Test
    void theModelFollowsAndFacesTheMob() {
        Zombie body = mob(5, 5, 0);
        ModelCreatureLook.ModelCreature rabbit = look.attach(body, "RABBIT", null);
        body.teleport(new Location(world, 8, 64, 6, 90, 0));
        server.getScheduler().performTicks(2);
        for (ItemDisplay display : rabbit.displays()) {
            Location at = display.getLocation();
            assertEquals(8, at.getX(), 1e-6);
            assertEquals(6, at.getZ(), 1e-6);
            // the models face -z: turned half round from the mob's yaw so the face looks where the mob looks
            assertEquals(270, Math.floorMod((int) at.getYaw(), 360));
        }
        assertEquals(8, rabbit.hitbox().getLocation().getX(), 1e-6);
        Vector3f lift = rabbit.displays().get(0).getTransformation().getTranslation();
        assertTrue(lift.y > 0.9, "the model's centre sits above the feet");
        rabbit.remove();
    }

    @Test
    void aPackPlayersHitOnTheHitboxIsTheirAttackOnTheMob() {
        Zombie body = mob(5, 5, 0);
        ModelCreatureLook.ModelCreature teddy = look.attach(body, "teddy", null);
        withPack.attackAttempt(teddy.hitbox());
        assertEquals(List.of("A>" + body.getUniqueId()), hits);
        withPack.attackAttempt(body); // a direct hit is the game's business, not forwarded
        assertEquals(1, hits.size());
        teddy.hit();
        teddy.attack();
        server.getScheduler().performTicks(5);
        teddy.remove();
        withPack.attackAttempt(teddy.hitbox());
        assertEquals(1, hits.size(), "nothing after removal");
    }

    @Test
    void eachKindPlaysItsOwnAttackThenTheMotionAgainAndFlinchesWhenHit() {
        ModelCreatureLook.ModelCreature soldier = look.attach(mob(5, 5, 0), "soldier", Color.RED);
        ModelCreatureLook.ModelCreature jelly = look.attach(mob(9, 9, 0), "jelly", null);
        ModelCreatureLook.ModelCreature cannon = look.attach(mob(13, 13, 0), "cannon", Color.BLUE);
        assertSame(CreatureModels.Attack.THRUST, soldier.attackMotion());
        assertSame(CreatureModels.Attack.POUNCE, jelly.attackMotion());
        assertSame(CreatureModels.Attack.RECOIL, cannon.attackMotion());
        soldier.attack();
        jelly.attack();
        cannon.attack();
        assertEquals(1, soldier.attackTick());
        server.getScheduler().performTicks(3);
        int tick = soldier.attackTick();
        assertTrue(tick > 1 && tick < CreatureModels.Attack.THRUST.ticks(), "playing: " + tick);
        Vector3f lunge = translation(soldier);
        Vector3f pounce = translation(jelly);
        Vector3f recoil = translation(cannon);
        assertEquals(expected(CreatureModels.SOLDIER, CreatureModels.Attack.THRUST.at(tick)), lunge);
        assertEquals(expected(CreatureModels.JELLY, CreatureModels.Attack.POUNCE.at(tick)), pounce);
        assertEquals(expected(CreatureModels.CANNON, CreatureModels.Attack.RECOIL.at(tick)), recoil);
        assertTrue(lunge.z < -0.2, "the soldier thrusts forward (-z): " + lunge);
        assertTrue(recoil.z > 0.2, "the cannon kicks back: " + recoil);
        assertTrue(pounce.y > 1.1, "the jelly springs up: " + pounce);
        // every part goes along: the soldier's key leans with him
        assertTrue(soldier.displays().get(1).getTransformation().getTranslation().z < -0.1);

        server.getScheduler().performTicks(CreatureModels.Attack.POUNCE.ticks());
        assertEquals(0, soldier.attackTick(), "over");
        assertEquals(new Vector3f(0, 1, 0), translation(soldier), "back in its own pose");

        jelly.hit();
        assertEquals(1, jelly.hitTick());
        assertTrue(jelly.displays().getFirst().isGlowing(), "a white flash");
        assertTrue(jelly.displays().getFirst().getTransformation().getScale().y < 0.9, "squashed");
        server.getScheduler().performTicks(CreatureModels.HIT_TICKS + 2);
        assertEquals(0, jelly.hitTick());
        assertFalse(jelly.displays().getFirst().isGlowing());
        soldier.remove();
        jelly.remove();
        cannon.remove();
    }

    private static Vector3f translation(ModelCreatureLook.ModelCreature creature) {
        return creature.displays().getFirst().getTransformation().getTranslation();
    }

    private static Vector3f expected(String kind, CreatureModels.Offset offset) {
        return ModelCreatureLook.pose(CreatureModels.CREATURES.get(kind).getFirst(), 0, false, offset)
                .getTranslation();
    }

    @Test
    void removeCleansEverythingAndGivesTheMobBack() {
        int before = world.getEntities().size();
        Zombie body = mob(5, 5, 0);
        ModelCreatureLook.ModelCreature dragon = look.attach(body, "dragon", null);
        ModelCreatureLook.ModelCreature mouse = look.attach(mob(7, 7, 0), "mice", null);
        assertEquals(3, dragon.displays().size(), "body, wings, key");
        assertTrue(look.running());
        server.getScheduler().performTicks(30);
        dragon.remove();
        dragon.remove(); // twice is harmless
        assertTrue(withPack.canSee(body), "the vanilla mob is back for pack players");
        assertTrue(look.running(), "the mouse still needs the ticker");
        mouse.remove();
        assertFalse(look.running());
        assertTrue(audience.listeners.isEmpty(), "unsubscribed");
        assertEquals(before + 2, world.getEntities().stream().filter(e -> !e.isDead()).count(), "only the mobs");
    }

    @Test
    void aMobThatDiesWithoutRemoveIsCleanedUp() {
        Zombie body = mob(5, 5, 0);
        ModelCreatureLook.ModelCreature jelly = look.attach(body, "jelly", null);
        body.remove();
        server.getScheduler().performTicks(2);
        assertTrue(jelly.isRemoved());
        assertEquals(0, look.creatureCount());
    }

    @Test
    void landmarksShowModelsOrABlockSketchAndTakeTheOwnersColour() {
        ModelCreatureLook.Landmark fountain = look.landmark(new Location(world, 0, 64, 0), "fountain", null);
        assertSees(withPack, fountain.displays(), true);
        assertSees(withPack, fountain.fallback(), false);
        assertSees(without, fountain.displays(), false);
        assertSees(without, fountain.fallback(), true);
        assertTrue(fountain.fallback().stream().anyMatch(b -> b.getBlock().getMaterial() == Material.WHITE_WOOL),
                "neutral pennants");
        fountain.owner(Color.fromRGB(0x3C44AA));
        assertTrue(fountain.fallback().stream().anyMatch(b -> b.getBlock().getMaterial() == Material.BLUE_WOOL));
        ModelCreatureLook.Landmark telescope = look.landmark(new Location(world, 20, 64, 0, 90, 0), "telescope",
                Color.RED);
        assertEquals(2, telescope.displays().size(), "the tower and its sweeping scope");
        server.getScheduler().performTicks(45);
        fountain.remove();
        telescope.remove();
        assertFalse(look.running());
        assertThrows(IllegalArgumentException.class, () -> look.landmark(new Location(world, 0, 64, 0), "soldier",
                null));
    }

    @Test
    void kindsAliasesAndPackSources() {
        assertEquals(CreatureModels.MOUSE, CreatureModels.kindOf("MICE"));
        assertEquals(CreatureModels.ARCHER, CreatureModels.kindOf("ranged"));
        assertEquals(CreatureModels.MERCHANT, CreatureModels.kindOf("shop"));
        assertNull(CreatureModels.kindOf("ghost"));
        LivingEntity body = mob(1, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> look.attach(body, "ghost", null));
        assertSame(CreatureSkin.Visual.NONE, CreatureSkin.VANILLA.attach(body, "soldier", null));
        for (ModelRef model : CreatureModels.models()) {
            assertTrue(Files.exists(Path.of("src/main/resourcepack-src/models", model.id() + ".bbmodel")),
                    model.id());
        }
        for (String kind : CreatureModels.CREATURES.keySet()) {
            assertEquals("creature/" + kind, CreatureModels.CREATURES.get(kind).get(0).model(), "body first");
        }
    }
}
