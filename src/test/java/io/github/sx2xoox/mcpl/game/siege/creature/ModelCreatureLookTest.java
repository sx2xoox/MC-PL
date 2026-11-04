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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sx2xoox.mcpl.core.pack.ItemDisplayModels;
import io.github.sx2xoox.mcpl.core.pack.ModelRef;
import io.github.sx2xoox.mcpl.core.pack.PackAudience;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.TestServer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
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

    private Sheep mob(double x, double z, float yaw) {
        return world.spawn(new Location(world, x, 64, z, yaw, 0), Sheep.class);
    }

    /** A body as the game makes it for {@code kind} at size 1 (its {@link CreatureModels#BODY_SCALES}). */
    private Sheep body(String kind, double x, double z) {
        Sheep body = mob(x, z, 0);
        body.getAttribute(Attribute.SCALE).setBaseValue(CreatureModels.BODY_SCALES.get(kind));
        return body;
    }

    private static void assertSees(Player player, List<? extends Entity> entities, boolean sees) {
        for (Entity entity : entities) {
            assertEquals(sees, player.canSee(entity), player.getName() + " sees " + entity.getType());
        }
    }

    @Test
    void packPlayersSeeTheModelOthersTheVanillaMob() {
        Sheep body = mob(5, 5, 0);
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
        Sheep body = mob(5, 5, 0);
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
        assertFacesItsYaw(rabbit, body);
        for (float yaw : new float[] {0, 45, 90, 135, 180, -135, -90, -45}) {
            body.teleport(new Location(world, 8, 64, 6, yaw, 0));
            server.getScheduler().performTicks(2);
            assertFacesItsYaw(rabbit, body);
        }
        assertEquals(8, rabbit.hitbox().getLocation().getX(), 1e-6);
        Vector3f lift = rabbit.displays().get(0).getTransformation().getTranslation();
        assertTrue(lift.y > 0.9, "the model's centre sits above the feet");
        rabbit.remove();
    }

    @Test
    void aPackPlayersHitOnTheHitboxIsTheirAttackOnTheMob() {
        Sheep body = mob(5, 5, 0);
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
        ModelCreatureLook.ModelCreature soldier = look.attach(body("soldier", 5, 5), "soldier", Color.RED);
        ModelCreatureLook.ModelCreature jelly = look.attach(body("jelly", 9, 9), "jelly", null);
        ModelCreatureLook.ModelCreature cannon = look.attach(body("cannon", 13, 13), "cannon", Color.BLUE);
        assertEquals(1, soldier.size(), 1e-6, "a body of its kind's size wears the model as built");
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
        Vector3f back = translation(soldier);
        assertTrue(Math.abs(back.y - 1) < 0.05 && Math.abs(back.z) < 1e-4, "back in its own (breathing) pose: " + back);

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
        Sheep body = mob(5, 5, 0);
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

    /**
     * As the client draws them (the item display's half turn included), every part's front (the -z the models are
     * authored with, where the eyes are) points where the mob looks.
     */
    static void assertFacesItsYaw(ModelCreatureLook.ModelCreature creature, LivingEntity body) {
        Location look = body.getLocation();
        Vector3f wanted = new Vector3f((float) look.getDirection().getX(), 0, (float) look.getDirection().getZ())
                .normalize();
        for (ItemDisplay display : creature.displays()) {
            Vector3f front = ItemDisplayModels.front(display.getLocation().getYaw(), display.getTransformation());
            assertTrue(front.dot(wanted) > 0.95f, creature.kind() + " at yaw " + look.getYaw() + " looks " + front
                    + ", not " + wanted);
        }
    }

    @Test
    void theAuthoredFrontIsWhereTheEyesAre() throws Exception {
        // the convention every turn relies on: the models' faces (eyes) are on the -z side (model units, centre 8)
        for (String kind : CreatureModels.CREATURES.keySet()) {
            Path file = Path.of("src/main/resourcepack-src/models/creature/" + kind + ".bbmodel");
            JsonObject model = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            double sum = 0;
            int eyes = 0;
            for (JsonElement element : model.getAsJsonArray("elements")) {
                JsonObject box = element.getAsJsonObject();
                if (box.get("name").getAsString().startsWith("eye")) {
                    sum += box.getAsJsonArray("from").get(2).getAsDouble()
                            + box.getAsJsonArray("to").get(2).getAsDouble();
                    eyes += 2;
                }
            }
            assertTrue(eyes > 0, kind + " has eyes");
            assertTrue(sum / eyes < 8, kind + "'s eyes are on the -z side");
        }
    }

    @Test
    void aDyingMobIsNotShownToPackPlayers() {
        Sheep body = mob(5, 5, 0);
        ModelCreatureLook.ModelCreature soldier = look.attach(body, "melee", Color.BLUE);
        assertFalse(withPack.canSee(body));
        body.setHealth(0); // dies: the death event's listeners remove the look while the body plays its death
        assertTrue(body.isDead());
        soldier.remove();
        assertFalse(withPack.canSee(body),
                "the dying vanilla body (and its vanilla name) never appears in the model's place");
        assertTrue(without.canSee(body));
        assertEquals(0, look.creatureCount());
    }

    @Test
    void aMobThatDiesWithoutRemoveIsCleanedUp() {
        Sheep body = mob(5, 5, 0);
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

    /** {@link CreatureModels#TOPS} is the top of every kind's models as built (bars and numbers float over it). */
    @Test
    void modelTopsMatchTheBuiltModels() throws Exception {
        assertEquals(CreatureModels.CREATURES.keySet(), CreatureModels.TOPS.keySet());
        for (var entry : CreatureModels.CREATURES.entrySet()) {
            double top = 0;
            for (CreatureModels.Part part : entry.getValue()) {
                JsonObject model = JsonParser.parseString(Files.readString(Path.of("src/main/resourcepack-src/models",
                        part.model() + ".bbmodel"))).getAsJsonObject();
                for (JsonElement element : model.getAsJsonArray("elements")) {
                    double y = element.getAsJsonObject().getAsJsonArray("to").get(1).getAsDouble();
                    // tools/assetgen toykit.centred: y = -16 + (blocks - (centre - 1.5 scale)) * 16 / scale
                    top = Math.max(top, (y + 16) * part.scale() / 16 + part.height() - 1.5 * part.scale());
                }
            }
            assertEquals(top, CreatureModels.TOPS.get(entry.getKey()), 0.02, entry.getKey());
        }
        LivingEntity rabbit = body("rabbit", 4, 2);
        ModelCreatureLook.ModelCreature skinned = look.attach(rabbit, "rabbit", null);
        assertEquals(CreatureModels.TOPS.get("rabbit"), look.modelTop(rabbit), 1e-6);
        // a toy made twice as big (the settings' size) is twice as tall
        LivingEntity big = mob(8, 2, 0);
        big.getAttribute(Attribute.SCALE).setBaseValue(CreatureModels.BODY_SCALES.get("rabbit") * 2);
        ModelCreatureLook.ModelCreature twice = look.attach(big, "rabbit", null);
        assertEquals(2 * CreatureModels.TOPS.get("rabbit"), look.modelTop(big), 1e-6);
        assertEquals(2 * CreatureModels.CREATURES.get("rabbit").getFirst().scale(),
                twice.displays().getFirst().getTransformation().getScale().x, 1e-5);
        twice.remove();
        assertTrue(skinned.hitbox().getInteractionHeight() >= CreatureModels.TOPS.get("rabbit") - 1e-6,
                "a pack player hits the model's ears too");
        skinned.remove();
        assertEquals(0, look.modelTop(rabbit), "no model, no top");
    }
}
