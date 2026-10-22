package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.DisplayMock;

/** 근접 · 탱커 계열: 광전사, 거인, 수호자, 흡혈귀. */
class MeleeAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    private static final NamespacedKey GOLDEN_APPLE = new NamespacedKey("test", "golden_apple");

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof ItemDisplay).count();
    }

    private static long blockDisplays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof BlockDisplay).count();
    }

    private static List<ItemDisplay> itemDisplaysOf(Scenario s) {
        return s.world.getEntities().stream().filter(ItemDisplay.class::isInstance).map(ItemDisplay.class::cast)
                .toList();
    }

    private static List<BlockDisplay> blockDisplaysOf(Scenario s, @Nullable Material material) {
        return s.world.getEntities().stream().filter(BlockDisplay.class::isInstance).map(BlockDisplay.class::cast)
                .filter(display -> material == null || display.getBlock().getMaterial() == material).toList();
    }

    /**
     * A display must not carry a rotation of its own (it would turn the model on top of its transformation, like
     * the sniper laser that pointed somewhere else on a real client).
     */
    private static void assertUnrotated(Display display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
    }

    /**
     * The display's transformation as one matrix, the way the client applies it. A test double that overrides
     * {@code getTransformation()} keeps it faithfully; MockBukkit's own {@link DisplayMock} keeps only the composed
     * matrix and hands back a wrongly decomposed rotation, so its matrix is read directly instead.
     */
    private static Matrix4f shapeOf(Display display) {
        try {
            if (display.getClass().getMethod("getTransformation").getDeclaringClass() != DisplayMock.class) {
                Transformation shape = display.getTransformation();
                return new Matrix4f().translation(shape.getTranslation()).rotate(shape.getLeftRotation())
                        .scale(shape.getScale()).rotate(shape.getRightRotation());
            }
            Field matrix = DisplayMock.class.getDeclaredField("transformationMatrix");
            matrix.setAccessible(true);
            return new Matrix4f((Matrix4fc) matrix.get(display));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the display's transformation", e);
        }
    }

    /** The unit direction a display's transformation turns the model's +z axis to. */
    private static Vector forwardOf(Display display) {
        Vector3f z = shapeOf(display).transformDirection(new Vector3f(0, 0, 1)).normalize();
        return new Vector(z.x(), z.y(), z.z());
    }

    /** Feet exactly on the flat floor and a body that touches no block (a real client would sink otherwise). */
    private static void assertOnSurface(Scenario s, LivingEntity entity, String what) {
        Location at = entity.getLocation();
        assertEquals(FlatWorld.SURFACE, at.getY(), 1.0E-6, what + " stands exactly on the floor: " + at);
        BoundingBox box = entity.getBoundingBox();
        assertTrue(Spots.isFree(s.world, at, box.getWidthX(), box.getHeight()), what + " is not inside a block");
    }

    /** Absorption hearts like a golden apple's (the max absorption is raised so they are not clamped away). */
    private static void giveAbsorption(Player player, double amount) {
        player.getAttribute(Attribute.MAX_ABSORPTION).addModifier(new AttributeModifier(GOLDEN_APPLE, amount,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        player.setAbsorptionAmount(amount);
    }

    private static void takeAbsorption(Player player) {
        player.getAttribute(Attribute.MAX_ABSORPTION).removeModifier(GOLDEN_APPLE);
        player.setAbsorptionAmount(0);
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    // ------------------------------------------------------------------ 광전사 berserker

    @Test
    void berserkerRageGrowsWithMissingHealth() {
        try (Scenario s = Scenario.ffa("berserker")) {
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "no rage at full health");

            s.clearHits();
            double max = s.maxHealth(s.owner);
            double floor = s.param("rage_health");
            s.owner.setHealth(max - (max - floor) / 2);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("rage_max") / 100 / 2), s.damageBy(s.owner, s.enemy), EPSILON,
                    "half rage");

            s.clearHits();
            s.owner.setHealth(floor);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("rage_max") / 100), s.damageBy(s.owner, s.enemy), EPSILON, "full rage");
            s.finishClean();
        }
    }

    @Test
    void berserkerChargeBowlsEveryEnemyInThePathOnce() {
        try (Scenario s = Scenario.ffa("berserker")) {
            s.place(s.enemy2, 0.5, -6.5, 0);
            s.assertSucceeded(s.active(), "돌진");
            s.ticks(30);
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "struck once");
            assertEquals(s.param("charge_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "nobody behind the charge is hit");
            assertTrue(s.owner.getLocation().getZ() > 4, "the berserker charged forward");
            assertTrue(Math.abs(s.enemy.getLocation().getX() - 0.5) > 0.3, "the enemy is bowled aside");
            assertOnSurface(s, s.owner, "the berserker after the charge");
            assertOnSurface(s, s.enemy, "the bowled enemy after landing");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertFalse(s.active().succeeded(), "on cooldown");
            s.finishClean();
        }
    }

    @Test
    void berserkerChargeStopsAtAWall() {
        try (Scenario s = Scenario.ffa("berserker")) {
            s.place(s.enemy, 8.5, 0.5, 0);
            s.place(s.enemy2, -8.5, 0.5, 0);
            s.world.wallAlongX(-3, 3, 3, 3, Material.STONE);
            s.assertSucceeded(s.active(), "돌진");
            s.ticks(30);
            assertTrue(s.owner.getLocation().getZ() < 2.8, "stopped in front of the wall");
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)));
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "bumping a wall does not hurt");
            s.world.wallAlongX(-3, 3, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    /**
     * The charge at 0, 150 and 250 ms (the server hears where the charging berserker is none, three and five ticks
     * late). Measured on that lagging position, the charge kept sending its velocity until the server saw the
     * berserker at the end, so its client ran on about a block further for every tick of the round trip (here into
     * an enemy standing past the end of the charge, untouched); the enemy in the path was bowled only once the server
     * saw the berserker reach it, long after the client had run through it; and the charge ended where the server last
     * heard of the berserker, blocks behind it.
     */
    @Test
    void berserkerChargeStopsHitsAndEndsWhereALaggingBerserkerReallyIs() {
        double stop = Double.NaN;
        for (int ping : new int[] {0, 150, 250}) {
            try (Scenario s = Scenario.ffa("berserker")) {
                String run = ping + " ms";
                s.place(s.enemy, 0.5, 5.5, 180); // in the path
                s.place(s.enemy2, 0.5, 11.5, 180); // three blocks past the end of the charge
                s.owner.setPing(ping);
                Location start = s.owner.getLocation();
                s.world.physics().setEnabled(false);
                RealClient client = new RealClient(s, s.owner, ping / 50);
                s.assertSucceeded(s.active(), "돌진");
                double bowledFrom = Double.NaN;
                Location skid = null;
                Location skidClient = null;
                for (int i = 0; i < 30; i++) {
                    Location before = client.at();
                    int heard = s.owner.getHeardSounds().size();
                    client.tick();
                    if (Double.isNaN(bowledFrom) && !s.hitsBy(s.owner, s.enemy).isEmpty()) {
                        bowledFrom = before.getZ();
                    }
                    if (skid == null) {
                        skid = soundAt(s.owner, heard, "entity.hoglin.step");
                        skidClient = before;
                    }
                }
                s.world.physics().setEnabled(true);
                Location end = client.at();
                assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "the enemy in the path is bowled: " + run);
                assertTrue(bowledFrom < s.enemy.getLocation().getZ() - 0.6,
                        "as the berserker reaches it, not after running through it: " + run + ", from " + bowledFrom);
                assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the enemy past the end is not touched: " + run);
                assertTrue(end.getZ() < s.enemy2.getLocation().getZ() - 0.6, "nor run into: " + run + ", " + end);
                double travelled = horizontalDistance(start, end);
                double length = s.param("charge_distance");
                assertTrue(travelled > length - 0.3 && travelled < length + 0.8,
                        "the charge runs its length (and a little glide): " + run + ", " + travelled);
                if (Double.isNaN(stop)) {
                    stop = travelled;
                } else {
                    assertEquals(stop, travelled, 0.1, "and stops at the same place whatever the ping: " + run);
                }
                assertEquals(start.getX(), end.getX(), EPSILON, "straight ahead: " + run);
                assertNotNull(skid, "the charge ends in a skid: " + run);
                assertEquals(skidClient.getZ(), skid.getZ(), 0.3,
                        "where the berserker stands when it ends: " + run + ", " + skidClient);
                s.place(s.owner, 0.5, 0.5, 0);
                s.ticks(20);
                assertEquals(0, s.damageTaken(s.owner), EPSILON);
                s.finishClean();
            }
        }
    }

    /**
     * Charging into a wall at 0, 150 and 250 ms: the berserker's client stops at the wall while the steps sent run on
     * past it (the server sees it stand still only a round trip later). The charge stops at the wall, the bump goes
     * off there, and an enemy right behind the wall is never bowled through it: the hit reaches past the body, and it
     * used to reach through the wall the berserker stopped at.
     */
    @Test
    void berserkerChargeNeverBowlsThroughAWallWhateverThePing() {
        for (int ping : new int[] {0, 150, 250}) {
            try (Scenario s = Scenario.ffa("berserker")) {
                String run = ping + " ms";
                s.place(s.enemy, 0.5, 4.35, 180); // right behind the wall
                s.place(s.enemy2, -8.5, 0.5, 0);
                s.world.wallAlongX(-3, 3, 3, 3, Material.STONE);
                s.owner.setPing(ping);
                int heard = s.owner.getHeardSounds().size();
                s.world.physics().setEnabled(false);
                RealClient client = new RealClient(s, s.owner, ping / 50);
                s.assertSucceeded(s.active(), "돌진");
                for (int i = 0; i < 30; i++) {
                    client.tick();
                }
                s.world.physics().setEnabled(true);
                assertTrue(client.at().getZ() > 2.5 && client.at().getZ() < 2.8,
                        "stopped at the wall: " + run + ", " + client.at());
                assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nobody is bowled through the wall: " + run);
                Location bump = soundAt(s.owner, heard, "item.mace.smash_ground");
                assertNotNull(bump, "the charge bumps into the wall: " + run);
                assertEquals(3.3, bump.getZ(), 0.3, "right at the wall: " + run);
                s.world.wallAlongX(-3, 3, 3, 3, Material.AIR);
                s.place(s.owner, 0.5, 0.5, 0);
                s.ticks(20);
                assertEquals(0, s.damageTaken(s.owner), EPSILON);
                s.finishClean();
            }
        }
    }

    /**
     * The charge never runs into the void or a lava pit, whatever the ping: it pulls up at the edge, looking ahead of
     * where the steps already sent carry the berserker. It used to run its whole length straight into the pit.
     */
    @Test
    void berserkerChargePullsUpAtTheEdgeOfALavaPitWhateverThePing() {
        for (int ping : new int[] {0, 150, 250}) {
            try (Scenario s = Scenario.ffa("berserker")) {
                String run = ping + " ms";
                s.place(s.enemy, 10.5, 10.5, 0);
                s.place(s.enemy2, -10.5, 10.5, 0);
                lavaPit(s, 6, Material.LAVA); // the pit starts 5.5 blocks ahead of the berserker (at z = 0.5)
                s.owner.setPing(ping);
                s.world.physics().setEnabled(false);
                RealClient client = new RealClient(s, s.owner, ping / 50);
                s.assertSucceeded(s.active(), "돌진");
                for (int i = 0; i < 30; i++) {
                    client.tick();
                }
                s.world.physics().setEnabled(true);
                assertTrue(client.at().getZ() + s.owner.getWidth() / 2 < 6,
                        "the berserker stops before the pit: " + run + ", " + client.at());
                assertTrue(client.at().getZ() > 3.5, "but still charges up to it: " + run + ", " + client.at());

                s.place(s.owner, 0.5, 4.5, 0); // right at the edge, facing the pit
                s.readyActive();
                ActivationResult result = s.active();
                assertFalse(result.succeeded(), "no charge straight into the pit: " + run);
                assertTrue(String.valueOf(result.reason()).contains("낭떠러지"), "and it says why: " + result.reason());
                lavaPit(s, 6, Material.STONE);
                s.place(s.owner, 0.5, 0.5, 0);
                s.ticks(20);
                assertEquals(0, s.damageTaken(s.owner), EPSILON);
                s.finishClean();
            }
        }
    }

    /**
     * A charge at a slant into a wall, and one that brushes the corner of a pillar, at 0, 150 and 250 ms: the
     * berserker's client slides along the block on the other axis, like any client, and charges on. Checked on the
     * straight line ahead (at the server's lagging height, so stairs did it too), the charge stopped right there as if
     * it had run into a wall, and the enemy further along was never reached.
     */
    @Test
    void berserkerChargeSlidesAlongAWallAndPastAPillarWhateverThePing() {
        for (boolean pillar : new boolean[] {false, true}) {
            for (int ping : new int[] {0, 150, 250}) {
                try (Scenario s = Scenario.ffa("berserker")) {
                    String run = (pillar ? "past a pillar's corner, " : "along a wall, ") + ping + " ms";
                    if (pillar) {
                        wallAlongZ(s, 2, 1, 1, Material.STONE);
                        s.place(s.owner, 0.5, 0.5, -45);
                        s.place(s.enemy, 4.5, 5.0, 180);
                    } else {
                        wallAlongZ(s, 2, -2, 12, Material.STONE);
                        s.place(s.owner, 1.5, 0.5, -15);
                        s.place(s.enemy, 1.4, 6.5, 180);
                    }
                    s.place(s.enemy2, -8.5, 0.5, 0);
                    s.owner.setPing(ping);
                    Location start = s.owner.getLocation();
                    int heard = s.owner.getHeardSounds().size();
                    s.world.physics().setEnabled(false);
                    RealClient client = new RealClient(s, s.owner, ping / 50);
                    s.assertSucceeded(s.active(), "돌진");
                    Location skid = null;
                    Location skidClient = null;
                    for (int i = 0; i < 30; i++) {
                        Location before = client.at();
                        int sounds = s.owner.getHeardSounds().size();
                        client.tick();
                        if (skid == null) {
                            skid = soundAt(s.owner, sounds, "entity.hoglin.step");
                            skidClient = before;
                        }
                    }
                    s.world.physics().setEnabled(true);
                    assertNull(soundAt(s.owner, heard, "item.mace.smash_ground"), "no wall stops it: " + run);
                    Location end = client.at();
                    assertTrue(horizontalDistance(start, end) > 6, "it charges on: " + run + ", " + end);
                    assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "and bowls the enemy further along: " + run);
                    assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
                    assertNotNull(skid, "the charge ends in a skid: " + run);
                    assertTrue(horizontalDistance(skidClient, skid) < 0.3,
                            "where the berserker stands when it ends: " + run + ", " + skidClient + " vs " + skid);
                    wallAlongZ(s, 2, -2, 12, Material.AIR);
                    s.place(s.owner, -4.5, 0.5, 0);
                    s.ticks(20);
                    assertEquals(0, s.damageTaken(s.owner), EPSILON);
                    s.finishClean();
                }
            }
        }
    }

    /**
     * Charging into cobwebs, and into vines, at 0, 150 and 250 ms: the berserker's client crawls through the webs at a
     * quarter of the speed and creeps through the vines. The steps sent ran on as if they were not there, so a
     * berserker stuck in them bowled an enemy waiting blocks behind them (the spider behind its own web) and ended its
     * charge there.
     */
    @Test
    void berserkerChargeCrawlsThroughCobwebsAndVinesWhateverThePing() {
        for (Material type : new Material[] {Material.COBWEB, Material.VINE}) {
            double stop = Double.NaN;
            int above = type == Material.COBWEB ? 1 : 0;
            for (int ping : new int[] {0, 150, 250}) {
                try (Scenario s = Scenario.ffa("berserker")) {
                    String run = type + ", " + ping + " ms";
                    s.place(s.enemy, 0.5, 8.5, 180); // behind them
                    s.place(s.enemy2, -8.5, 0.5, 0);
                    plant(s, 3, 5, above, type);
                    s.owner.setPing(ping);
                    s.world.physics().setEnabled(false);
                    RealClient client = new RealClient(s, s.owner, ping / 50);
                    s.assertSucceeded(s.active(), "돌진");
                    Location skid = null;
                    Location skidClient = null;
                    for (int i = 0; i < 30; i++) {
                        Location before = client.at();
                        int sounds = s.owner.getHeardSounds().size();
                        client.tick();
                        if (skid == null) {
                            skid = soundAt(s.owner, sounds, "entity.hoglin.step");
                            skidClient = before;
                        }
                    }
                    s.world.physics().setEnabled(true);
                    Location end = client.at();
                    assertTrue(end.getZ() > 3.5 && end.getZ() < 6.3,
                            "the berserker is caught in them: " + run + ", " + end);
                    assertEquals(0, s.damageTaken(s.enemy), EPSILON,
                            "so it never reaches the enemy behind them: " + run);
                    assertNotNull(skid, "the charge ends in a skid: " + run);
                    assertEquals(skidClient.getZ(), skid.getZ(), 0.3,
                            "where the berserker is: " + run + ", " + skidClient);
                    if (Double.isNaN(stop)) {
                        stop = end.getZ();
                    } else {
                        assertEquals(stop, end.getZ(), 0.1, "the same whatever the ping: " + run);
                    }
                    plant(s, 3, 5, above, Material.AIR);
                    s.place(s.owner, 0.5, 0.5, 0);
                    s.ticks(20);
                    assertEquals(0, s.damageTaken(s.owner), EPSILON);
                    s.finishClean();
                }
            }
        }
    }

    /** Where {@code listener} heard the first sound named like {@code key} since {@code from}, null if none. */
    private static @Nullable Location soundAt(TestPlayer listener, int from, String key) {
        var heard = listener.getHeardSounds();
        for (int i = from; i < heard.size(); i++) {
            if (heard.get(i).getSound().contains(key) && heard.get(i).getLocation() != null) {
                return heard.get(i).getLocation();
            }
        }
        return null;
    }

    @Test
    void berserkerFrenzyGrantsStrengthSpeedAndLifesteal() {
        try (Scenario s = Scenario.ffa("berserker")) {
            s.assertSucceeded(s.ultimate(), "광폭화");
            PotionEffect strength = s.owner.getPotionEffect(PotionEffectType.STRENGTH);
            PotionEffect speed = s.owner.getPotionEffect(PotionEffectType.SPEED);
            assertNotNull(strength);
            assertNotNull(speed);
            assertEquals(1, strength.getAmplifier(), "strength II");
            assertEquals(1, speed.getAmplifier(), "speed II");

            s.owner.setHealth(10);
            s.melee(s.owner, s.enemy, 6);
            double dealt = s.damageBy(s.owner, s.enemy);
            s.ticks(2);
            assertEquals(10 + dealt * s.param("ult_lifesteal") / 100, s.owner.getHealth(), 1.0E-6,
                    "heals " + s.param("ult_lifesteal") + "% of the melee damage");
            s.ticks(s.paramTicks("ult_duration"));
            double before = s.owner.getHealth();
            s.melee(s.owner, s.enemy, 6);
            s.ticks(2);
            assertEquals(before, s.owner.getHealth(), EPSILON, "no lifesteal after the frenzy");
            s.finishClean();
        }
    }

    @Test
    void berserkerFrenzyLifestealCountsDamageSoakedByAbsorptionHearts() {
        try (Scenario s = Scenario.ffa("berserker")) {
            s.assertSucceeded(s.ultimate(), "광폭화");
            s.owner.setHealth(10);
            giveAbsorption(s.enemy, 4);
            s.melee(s.owner, s.enemy, 6);
            double dealt = s.damageBy(s.owner, s.enemy) + 4; // the recorded final damage leaves out the absorbed 4
            assertEquals(0, s.enemy.getAbsorptionAmount(), EPSILON, "the hit broke through the absorption");
            s.ticks(2);
            assertEquals(10 + dealt * s.param("ult_lifesteal") / 100, s.owner.getHealth(), 1.0E-6,
                    "the absorbed part heals too");
            takeAbsorption(s.enemy);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 거인 titan

    @Test
    void titanIsBigToughAndSlow() {
        try (Scenario s = Scenario.ffa("titan")) {
            assertEquals(s.param("scale"), s.owner.getAttribute(Attribute.SCALE).getValue(), EPSILON);
            assertEquals(20 + s.param("bonus_health"), s.maxHealth(s.owner), EPSILON);
            assertEquals(20 + s.param("bonus_health"), s.owner.getHealth(), EPSILON, "starts at full health");
            assertEquals(s.param("knockback_resistance") / 100,
                    s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue(), EPSILON);
            assertEquals(0.1 * (1 - s.param("speed_penalty") / 100),
                    s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON);
            assertEquals(1.8 * s.param("scale"), s.owner.getBoundingBox().getHeight(), 1.0E-6, "a taller hitbox");
            s.owner.damage(3, DamageSource.builder(DamageType.IN_WALL).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "never suffocates");
            s.finishClean();
        }
    }

    @Test
    void titanLeapSlamsEnemiesAroundTheLandingWithoutHurtingItself() {
        try (Scenario s = Scenario.ffa("titan")) {
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.assertSucceeded(s.active(), "대지 강타");
            s.ticks(3);
            assertTrue(s.owner.getLocation().getY() > FlatWorld.SURFACE + 0.5, "leaps");
            assertFalse(s.active().succeeded(), "cannot leap twice");
            s.tickUntilGrounded(s.owner, 120);
            s.ticks(2);
            assertEquals(s.param("slam_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "slammed once");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is far away");
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + 0.2 || s.enemy.getVelocity().getY() > 0,
                    "launched");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage for the titan");
            assertOnSurface(s, s.owner, "the titan after the slam");
            assertTrue(blockDisplays(s) > 0, "rocks erupt from the ground");
            s.ticks(40);
            assertEquals(0, blockDisplays(s), "the rocks sank back and are gone");
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty() || s.damageTaken(s.enemy)
                    <= s.param("slam_damage") + 1, "a short hop at most");
            s.finishClean();
        }
    }

    @Test
    void titanSlamRocksStandInTheOpenNeverInsideBlocks() {
        try (Scenario s = Scenario.ffa("titan")) {
            s.place(s.enemy, -10.5, 10.5, 0);
            s.place(s.enemy2, -10.5, -10.5, 0);
            // A two block high wall east of the landing spot: the ground search for a rock starts inside it there.
            wall(s, Material.STONE);
            // Looking straight down: the titan leaps straight up and slams down where it stood.
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, 90);
            s.assertSucceeded(s.active(), "대지 강타");
            s.tickUntilGrounded(s.owner, 120);
            s.ticks(4);
            List<BlockDisplay> rocks = blockDisplaysOf(s, null);
            assertFalse(rocks.isEmpty(), "rocks erupt from the open ground");
            for (BlockDisplay rock : rocks) {
                // A rock is lit by the light at its own position: inside a block it would render black.
                assertTrue(rock.getLocation().getBlock().isPassable(),
                        "a rock stands in the open, not inside a block: " + rock.getLocation());
            }
            for (BlockDisplay rock : rocks) {
                Location at = rock.getLocation();
                assertTrue(at.getY() >= FlatWorld.SURFACE && at.getY() < FlatWorld.SURFACE + 0.05,
                        "a rock sits on the floor: " + at);
            }
            s.ticks(40);
            wall(s, Material.AIR);
            s.finishClean();
        }
    }

    /** Two block high blocks east of the titan (x 2 to 6, z -6 to 6). */
    private static void wall(Scenario s, Material type) {
        for (int x = 2; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = 1; y <= 2; y++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + y, z, type);
                }
            }
        }
    }

    @Test
    void titanGiantFormGrowsGrantsBuffsAndShrinksBack() {
        try (Scenario s = Scenario.ffa("titan")) {
            double reach = s.owner.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).getValue();
            s.assertSucceeded(s.ultimate(), "거대화");
            s.ticks(12);
            assertEquals(s.param("ult_scale"), s.owner.getAttribute(Attribute.SCALE).getValue(), 1.0E-6, "grown");
            assertEquals(reach + s.param("ult_reach"),
                    s.owner.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).getValue(), EPSILON);
            // The server clamps knockback resistance to 1 (MockBukkit does not clamp attribute values).
            assertTrue(s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue() >= 1.0, "knockback immune");
            assertEquals(1 + s.param("ult_damage_bonus") / 100,
                    s.owner.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), EPSILON);
            assertFalse(s.ultimate().succeeded(), "already a giant");

            s.ticks(s.paramTicks("ult_duration") + 20);
            assertEquals(s.param("scale"), s.owner.getAttribute(Attribute.SCALE).getValue(), EPSILON,
                    "back to the passive size");
            assertEquals(reach, s.owner.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).getValue(), EPSILON);
            assertEquals(s.param("knockback_resistance") / 100,
                    s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue(), EPSILON);
            assertEquals(1, s.owner.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), EPSILON);
            s.assertSucceeded(s.ultimate(), "거대화 again");
            s.finishClean();
        }
    }

    @Test
    void titanGrowsOnlyAsFarAsTheCeilingAllows() {
        try (Scenario s = Scenario.ffa("titan")) {
            ceiling(s, FlatWorld.FLOOR_Y + 3, Material.STONE);
            assertFalse(s.ultimate().succeeded(), "a 2 block high room: no headroom for a giant");
            ceiling(s, FlatWorld.FLOOR_Y + 3, Material.AIR);

            ceiling(s, FlatWorld.FLOOR_Y + 4, Material.STONE);
            s.assertSucceeded(s.ultimate(), "거대화 under a 3 block ceiling");
            s.ticks(12);
            double scale = s.owner.getAttribute(Attribute.SCALE).getValue();
            assertTrue(scale > s.param("scale") && 1.8 * scale <= 3.0 + 1.0E-6,
                    "grew, but never into the ceiling: " + scale);
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)));
            s.ticks(s.paramTicks("ult_duration") + 20);
            ceiling(s, FlatWorld.FLOOR_Y + 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void titanGrowsEvenWhenHuggingAWall() {
        try (Scenario s = Scenario.ffa("titan")) {
            // The titan's own passive cancels suffocation, so it may grow with its back against a wall.
            s.place(s.owner, 0.5, 1 - 0.3 * s.param("scale"), 0);
            s.world.wallAlongX(-2, 2, 1, 4, Material.STONE);
            s.assertSucceeded(s.ultimate(), "거대화 against a wall");
            s.ticks(12);
            assertEquals(s.param("ult_scale"), s.owner.getAttribute(Attribute.SCALE).getValue(), 1.0E-6, "grown");
            s.ticks(s.paramTicks("ult_duration") + 20);
            s.world.wallAlongX(-2, 2, 1, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void titanGiantFormBorrowedByAGamblerNeedsRoomForTheWholeBody() {
        try (Scenario s = Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0).start()) {
            // A borrowed copy has no suffocation guard: growing into the wall next to the gambler must fail, so the
            // jackpot moves on to another ultimate.
            s.place(s.owner, 0.5, 0.7, 0);
            s.world.wallAlongX(-2, 2, 1, 4, Material.STONE);
            assertFalse(borrow(s, "titan").succeeded(), "no room for a giant beside the wall");
            assertEquals(1.0, s.owner.getAttribute(Attribute.SCALE).getValue(), EPSILON, "did not grow");
            assertTrue(s.owner.getAttribute(Attribute.SCALE).getModifiers().isEmpty());

            s.place(s.owner, 0.5, -3.5, 0);
            s.assertSucceeded(borrow(s, "titan"), "거대화 in the open");
            s.ticks(12);
            assertEquals(s.ctx().paramsFor("titan").num("ult_scale"), s.owner.getAttribute(Attribute.SCALE).getValue(),
                    1.0E-6, "grown in the open");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks((int) s.ctx().paramsFor("titan").ticks("ult_duration") + 20);
            assertEquals(1.0, s.owner.getAttribute(Attribute.SCALE).getValue(), EPSILON, "shrunk back");
            s.world.wallAlongX(-2, 2, 1, 4, Material.AIR);
            s.finishClean();
        }
    }

    private static void ceiling(Scenario s, int y, Material type) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                s.world.setBlock(x, y, z, type);
            }
        }
    }

    // ------------------------------------------------------------------ 수호자 guardian

    @Test
    void guardianTakesLessDamageFromEverything() {
        try (Scenario s = Scenario.ffa("guardian")) {
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("damage_reduction") / 100), s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void guardianShieldBashHitsSlowsAndKnocksBack() {
        try (Scenario s = Scenario.ffa("guardian")) {
            s.place(s.enemy, 0.5, 3.5, 180);
            double enemyZ = s.enemy.getLocation().getZ();
            s.assertSucceeded(s.active(), "방패 돌격");
            s.ticks(10);
            assertEquals(s.param("bash_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            PotionEffect slow = s.enemy.getPotionEffect(PotionEffectType.SLOWNESS);
            assertNotNull(slow, "slowed");
            assertEquals(1, slow.getAmplifier());
            s.ticks(10);
            assertTrue(s.enemy.getLocation().getZ() > enemyZ + 1, "knocked back");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertEquals(0, displays(s), "the charge shield faded");
            assertOnSurface(s, s.owner, "the guardian after the charge");
            assertOnSurface(s, s.enemy, "the bashed enemy after landing");
            s.finishClean();
        }
    }

    @Test
    void guardianChargeShieldFacesTheWayOfTheCharge() {
        try (Scenario s = Scenario.ffa("guardian")) {
            s.place(s.enemy, 10.5, 10.5, 0);
            s.place(s.enemy2, 10.5, -10.5, 0);
            // Charging west (yaw 90): a shield keeping the display's own +z would face south.
            s.place(s.owner, 0.5, 0.5, 90);
            s.assertSucceeded(s.active(), "방패 돌격");
            Vector west = new Vector(-1, 0, 0);
            for (int step = 0; step < 3; step++) {
                List<ItemDisplay> shields = itemDisplaysOf(s);
                assertEquals(1, shields.size(), "the charge shield");
                ItemDisplay shield = shields.getFirst();
                assertUnrotated(shield, "charge shield after " + step + " tick(s)");
                assertTrue(forwardOf(shield).dot(west) > 0.999, "the shield faces the charge: " + forwardOf(shield));
                assertTrue(shield.getLocation().getX() < s.owner.getLocation().getX() - 0.5,
                        "and leads it: " + shield.getLocation());
                s.ticks(1);
            }
            s.ticks(40);
            s.finishClean();
        }
    }

    /**
     * A 150 ms connection (three ticks): the server hears where the charging guardian is three ticks late. Measured
     * on that position, the charge kept sending its velocity until the lagging position reached the end, so the client
     * ran about a block further for every tick of the round trip.
     */
    @Test
    void guardianChargeEndsAtItsLengthEvenWhenTheServerSeesTheGuardianLate() {
        try (Scenario s = Scenario.ffa("guardian")) {
            s.place(s.enemy, 10.5, 10.5, 0);
            s.place(s.enemy2, -10.5, 10.5, 0);
            s.owner.setPing(150);
            Location start = s.owner.getLocation();
            s.world.physics().setEnabled(false);
            RealClient client = new RealClient(s, s.owner, 3);
            s.assertSucceeded(s.active(), "방패 돌격");
            for (int i = 0; i < 30; i++) {
                client.tick();
            }
            s.world.physics().setEnabled(true);
            double travelled = horizontalDistance(start, client.at());
            double length = s.param("bash_distance");
            assertTrue(travelled < length + 0.3, "the charge did not run on past its end: " + travelled);
            assertTrue(travelled > length - 0.5, "but ran its whole length: " + travelled);
            assertEquals(start.getX(), client.at().getX(), EPSILON, "straight ahead");
            s.place(s.owner, 0.5, 0.5, 0);
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    /**
     * The bash goes off where the guardian stops, whatever its ping. It went off where the server last heard of the
     * guardian, about a block short for every tick of the round trip: on every screen the wall of force burst behind
     * the guardian, which then slid on to its stop and past enemies standing there untouched. And an enemy in the
     * way stopped the charge only once the server saw the guardian reach it, long after its client ran through it.
     */
    @Test
    void guardianBashLandsWhereALaggingGuardianStops() {
        for (int ping : new int[] {0, 150, 250}) {
            for (double ahead : new double[] {2.5, 4.5, 5.0, 6.5}) {
                try (Scenario s = Scenario.ffa("guardian")) {
                    String run = ping + " ms, the enemy " + ahead + " blocks ahead";
                    s.place(s.enemy, 0.5, 0.5 + ahead, 180);
                    s.place(s.enemy2, -10.5, 10.5, 0);
                    s.owner.setPing(ping);
                    s.world.physics().setEnabled(false);
                    RealClient client = new RealClient(s, s.owner, ping / 50);
                    s.assertSucceeded(s.active(), "방패 돌격");
                    double wallZ = Double.NaN;
                    double guardianZ = Double.NaN;
                    for (int i = 0; i < 30; i++) {
                        client.tick();
                        List<BlockDisplay> walls = blockDisplaysOf(s, Material.LIGHT_BLUE_STAINED_GLASS);
                        if (Double.isNaN(wallZ) && !walls.isEmpty()) {
                            wallZ = walls.getFirst().getLocation().getZ();
                            guardianZ = client.at().getZ();
                        }
                    }
                    s.world.physics().setEnabled(true);
                    assertEquals(s.param("bash_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "bashed: " + run);
                    assertTrue(client.at().getZ() < s.enemy.getLocation().getZ() - 0.6,
                            "the guardian stops at the enemy, not in or past it: " + run + ", " + client.at());
                    assertEquals(guardianZ + 1.0, wallZ, 0.3,
                            "the wall of force bursts right in front of the guardian: " + run);
                    s.place(s.owner, 0.5, 0.5, 0);
                    s.place(s.enemy, 0.5, 4.5, 180);
                    s.ticks(20);
                    assertEquals(0, s.damageTaken(s.owner), EPSILON);
                    s.finishClean();
                }
            }
        }
    }

    @Test
    void guardianChargeNeverCarriesALaggingGuardianPastTheBorder() {
        try (Scenario s = Scenario.ffa("guardian")) {
            Location center = s.session.arena().center();
            double x = center.getX();
            double z = center.getZ();
            for (double step : new double[] {1, 0.1, 0.01}) {
                while (z < center.getZ() + 500 && s.ctx().isInsideArena(new Location(s.world, x, FlatWorld.SURFACE,
                        z + step))) {
                    z += step;
                }
            }
            double edge = z; // the last point inside, to a hundredth of a block
            // Under 4 blocks from the border: the charge is cut short to a point 0.1 block inside it.
            s.place(s.owner, x, edge - 2.6, 0);
            s.place(s.enemy, x + 12, edge - 20, 0);
            s.place(s.enemy2, x - 12, edge - 20, 0);
            s.owner.setPing(150);
            s.world.physics().setEnabled(false);
            RealClient client = new RealClient(s, s.owner, 3);
            s.assertSucceeded(s.active(), "방패 돌격");
            for (int i = 0; i < 30; i++) {
                client.tick();
            }
            s.world.physics().setEnabled(true);
            assertTrue(s.ctx().isInsideArena(client.at()), "the guardian stops inside the border: " + client.at());
            assertTrue(client.at().getZ() > edge - 0.6, "after running up to it: " + client.at());
            s.place(s.owner, x, edge - 2.6, 0);
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void guardianChargePullsUpAtTheEdgeOfALavaPit() {
        try (Scenario s = Scenario.ffa("guardian")) {
            s.place(s.enemy, 10.5, 10.5, 0);
            s.place(s.enemy2, -10.5, 10.5, 0);
            lavaPit(s, 4, Material.LAVA); // the pit starts 3.5 blocks ahead of the guardian (at z = 0.5)
            s.assertSucceeded(s.active(), "방패 돌격");
            s.ticks(20);
            assertTrue(s.owner.getLocation().getZ() + s.owner.getWidth() / 2 < 4,
                    "the guardian stops before the pit: " + s.owner.getLocation());
            assertTrue(s.owner.getLocation().getZ() > 1.5, "but still charges up to it: " + s.owner.getLocation());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);

            s.place(s.owner, 0.5, 2.5, 0); // right at the edge, facing the pit
            ActivationResult result = s.active();
            assertFalse(result.succeeded(), "no charge straight into the pit");
            assertNotNull(result.reason());
            lavaPit(s, 4, Material.STONE);
            s.place(s.owner, 0.5, 0.5, 0);
            s.finishClean();
        }
    }

    /** A strip of {@code type} in the floor across the guardian's path, from z to z + 2. */
    private static void lavaPit(Scenario s, int z, Material type) {
        for (int x = -3; x <= 3; x++) {
            for (int dz = 0; dz < 3; dz++) {
                s.world.setBlock(x, FlatWorld.FLOOR_Y, z + dz, type);
            }
        }
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** How much a client's move is slowed down after it went through a cobweb (vanilla). */
    private static final Vector WEB_SLOWDOWN = new Vector(0.25, 0.05, 0.25);
    /** The fastest a client on a ladder or a vine moves along each horizontal axis (vanilla). */
    private static final double CLIMB_SPEED = 0.15;

    /** True if the feet at {@code at} are in a ladder, a vine or anything else a client climbs. */
    private static boolean climbing(FlatWorld world, Location at) {
        return Tag.CLIMBABLE.isTagged(world.getBlockAt(at.getBlockX(), (int) Math.floor(at.getY() + 0.01),
                at.getBlockZ()).getType());
    }

    /**
     * True if a body of the given size moving from {@code from} to {@code to} goes through a block of {@code type}
     * (vanilla applies a cobweb's slowdown to anything its box touched on the way).
     */
    private static boolean goesThrough(FlatWorld world, Location from, Location to, double width, double height,
                                       Material type) {
        double half = width / 2 - 0.01;
        int minY = (int) Math.floor(Math.min(from.getY(), to.getY()) + 0.01);
        int maxY = (int) Math.floor(Math.max(from.getY(), to.getY()) + height - 0.01);
        for (int x = (int) Math.floor(Math.min(from.getX(), to.getX()) - half);
             x <= (int) Math.floor(Math.max(from.getX(), to.getX()) + half); x++) {
            for (int z = (int) Math.floor(Math.min(from.getZ(), to.getZ()) - half);
                 z <= (int) Math.floor(Math.max(from.getZ(), to.getZ()) + half); z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (world.getBlockAt(x, y, z).getType() == type) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** A wall of {@code type} along z at {@code x}, from {@code z1} to {@code z2}, three blocks high. */
    private static void wallAlongZ(Scenario s, int x, int z1, int z2, Material type) {
        for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
            for (int y = 0; y < 3; y++) {
                s.world.setBlock(x, FlatWorld.FLOOR_Y + 1 + y, z, type);
            }
        }
    }

    /**
     * Blocks of {@code type} from {@code z1} to {@code z2} across x = -1..1, {@code above} blocks above the floor: a
     * cobweb hangs at head height (one lying on the ground counts as a hazard, and a charge stops before it), a vine
     * grows at the feet.
     */
    private static void plant(Scenario s, int z1, int z2, int above, Material type) {
        for (int x = -1; x <= 1; x++) {
            for (int z = z1; z <= z2; z++) {
                s.world.setBlock(x, FlatWorld.FLOOR_Y + 1 + above, z, type);
            }
        }
    }

    /**
     * A player's real client against what the server knows of it, for the real-client bugs a plain world simulation
     * hides. A real server never reads a player's motion back ({@code Player#getVelocity} only returns what the server
     * last sent it): the client keeps its own velocity (gravity, drag, ground friction; walls stop it flush against
     * them, one horizontal axis at a time, the longer first; on a vine it creeps, and the move after it went through a
     * cobweb is slowed to a crawl) until the server sends a new one, and the server sees the client's position
     * {@code lag} ticks late. World physics must be off: this moves the player.
     */
    private static final class RealClient {
        /** Marks the velocity the server last sent, to tell whether a skill sent a new one. */
        private static final double MARK = 1.0E-9;
        private final Scenario s;
        private final TestPlayer player;
        private final Deque<Location> late = new ArrayDeque<>();
        private final Location at;
        private final Vector velocity = new Vector();
        private Vector marked = new Vector();
        /** The last move went through a cobweb: the next one is slowed down (vanilla's stuck speed multiplier). */
        private boolean webbed;

        /** Create it before the skill is cast: a velocity the cast sets must count as new. */
        RealClient(Scenario s, TestPlayer player, int lag) {
            this.s = s;
            this.player = player;
            this.at = player.getLocation();
            for (int i = 0; i < lag; i++) {
                late.addLast(at.clone());
            }
            mark(new Vector());
        }

        /** Where the player really is (its client). */
        Location at() {
            return at.clone();
        }

        /**
         * One server tick, then one client tick with the velocity the server last set during it (a server sends a
         * player's velocity once per tick, after the plugins ran: one set between two ticks goes out with the next
         * one, unless the plugins replace it first).
         */
        void tick() {
            s.ticks(1);
            Vector now = player.getVelocity();
            if (Double.compare(now.getX(), marked.getX()) != 0 || Double.compare(now.getY(), marked.getY()) != 0
                    || Double.compare(now.getZ(), marked.getZ()) != 0) {
                velocity.copy(now);
                mark(now);
            }
            if (climbing(s.world, at)) {
                // Like the client on a ladder or a vine: at most 0.15 a tick along each horizontal axis.
                velocity.setX(Math.clamp(velocity.getX(), -CLIMB_SPEED, CLIMB_SPEED))
                        .setZ(Math.clamp(velocity.getZ(), -CLIMB_SPEED, CLIMB_SPEED));
            }
            Vector move = velocity.clone();
            if (webbed) {
                // Like the client: the move after going through a cobweb is slowed to a crawl and its motion is lost.
                move.multiply(WEB_SLOWDOWN);
                velocity.zero();
            }
            Location from = at.clone();
            at.setY(at.getY() + move.getY());
            boolean grounded = at.getY() <= FlatWorld.SURFACE;
            if (grounded) {
                at.setY(FlatWorld.SURFACE);
            }
            // One horizontal axis at a time, the longer first like the client: a wall stops the body flush against it.
            if (Math.abs(move.getX()) < Math.abs(move.getZ())) {
                moveAlong(move.getZ(), false);
                moveAlong(move.getX(), true);
            } else {
                moveAlong(move.getX(), true);
                moveAlong(move.getZ(), false);
            }
            webbed = goesThrough(s.world, from, at, player.getWidth(), player.getHeight(), Material.COBWEB);
            double vy = (velocity.getY() - 0.08) * 0.98;
            double friction = grounded ? 0.546 : 0.91;
            velocity.setX(velocity.getX() * friction).setY(grounded && vy < 0 ? 0 : vy)
                    .setZ(velocity.getZ() * friction);
            late.addLast(at.clone());
            Location seen = late.removeFirst();
            Location look = player.getLocation();
            seen.setYaw(look.getYaw());
            seen.setPitch(look.getPitch());
            player.teleport(seen);
            player.setVelocity(marked);
        }

        private void mark(Vector sent) {
            marked = sent.clone().setX(sent.getX() + MARK);
            player.setVelocity(marked);
        }

        /** Moves the body {@code delta} along x (or z) up to the first block; a block stops that axis' motion. */
        private void moveAlong(double delta, boolean alongX) {
            double done = sweep(delta, alongX);
            if (alongX) {
                at.setX(at.getX() + done);
            } else {
                at.setZ(at.getZ() + done);
            }
            if (done != delta) {
                if (alongX) {
                    velocity.setX(0);
                } else {
                    velocity.setZ(0);
                }
            }
        }

        /** How far the body gets along x (or z) towards {@code delta} before a block stops it. */
        private double sweep(double delta, boolean alongX) {
            if (delta == 0 || fits(delta, alongX)) {
                return delta;
            }
            double free = 0;
            double blocked = Math.abs(delta);
            for (int i = 0; i < 30; i++) {
                double mid = (free + blocked) / 2;
                if (fits(Math.copySign(mid, delta), alongX)) {
                    free = mid;
                } else {
                    blocked = mid;
                }
            }
            return Math.copySign(free, delta);
        }

        private boolean fits(double delta, boolean alongX) {
            Location next = at.clone().add(alongX ? delta : 0, 0, alongX ? 0 : delta);
            return Spots.isFree(s.world, next, player.getWidth(), player.getHeight());
        }
    }

    @Test
    void guardianOrbitingShieldsFaceOutwards() {
        try (Scenario s = Scenario.ffa("guardian")) {
            s.assertSucceeded(s.ultimate(), "불굴");
            for (int step = 0; step < 3; step++) {
                s.ticks(1 + step);
                List<ItemDisplay> shields = itemDisplaysOf(s);
                assertEquals(3, shields.size(), "three orbiting shields");
                for (ItemDisplay shield : shields) {
                    assertUnrotated(shield, "orbiting shield");
                    Vector out = shield.getLocation().toVector().subtract(s.owner.getLocation().toVector()).setY(0)
                            .normalize();
                    assertTrue(forwardOf(shield).dot(out) > 0.999, "a shield faces away from the guardian: "
                            + forwardOf(shield) + " vs " + out);
                }
            }
            s.ticks(s.paramTicks("ult_duration") + 10);
            s.finishClean();
        }
    }

    @Test
    void guardianBulwarkShieldsTheOwnerAndAlliesThenEnds() {
        try (Scenario s = Scenario.teams("guardian")) {
            s.place(s.ally(), 2.5, 0.5, 0);
            s.assertSucceeded(s.ultimate(), "불굴");
            assertEquals(s.param("ult_absorption"), s.owner.getAbsorptionAmount(), EPSILON);
            PotionEffect resistance = s.owner.getPotionEffect(PotionEffectType.RESISTANCE);
            assertNotNull(resistance);
            assertEquals(1, resistance.getAmplifier(), "resistance II");
            PotionEffect allyResistance = s.ally().getPotionEffect(PotionEffectType.RESISTANCE);
            assertNotNull(allyResistance, "the nearby ally is shielded");
            assertEquals(0, allyResistance.getAmplifier(), "resistance I");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.RESISTANCE), "enemies are not");
            assertEquals(3, displays(s), "three orbiting shields");
            assertTrue(blockDisplays(s) >= 10, "a glass dome");
            assertFalse(s.ultimate().succeeded(), "already active");

            double health = s.owner.getHealth();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(health, s.owner.getHealth(), EPSILON, "the absorption takes the hit");
            assertTrue(s.owner.getAbsorptionAmount() < s.param("ult_absorption"));
            s.ticks(10);
            assertTrue(displays(s) < 3, "spent shields break");

            s.ticks(s.paramTicks("ult_duration") + 10);
            assertEquals(0, s.owner.getAbsorptionAmount(), EPSILON, "the bonus absorption is gone");
            assertTrue(s.owner.getAttribute(Attribute.MAX_ABSORPTION).getModifiers().isEmpty());
            assertEquals(0, displays(s), "the shields are gone");
            assertEquals(0, blockDisplays(s), "the dome is gone");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 흡혈귀 vampire

    @Test
    void vampireHealsAShareOfEveryHit() {
        try (Scenario s = Scenario.ffa("vampire")) {
            s.owner.setHealth(10);
            s.melee(s.owner, s.enemy, 5);
            s.ticks(2);
            assertEquals(10 + 5 * s.param("lifesteal") / 100, s.owner.getHealth(), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void vampireDrainSiphonsTheAimedEnemyWhileInSight() {
        try (Scenario s = Scenario.ffa("vampire")) {
            s.owner.setHealth(8);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "생명 흡수");
            s.ticks(s.paramTicks("drain_duration") + 5);
            double drained = s.param("drain_damage") * s.param("drain_duration") / s.param("drain_interval");
            assertEquals(drained, s.damageBy(s.owner, s.enemy), EPSILON, "1 every 0.25 s for 2 s");
            assertEquals(8 + drained, s.owner.getHealth(), 1.0E-6, "every drop drained heals the vampire");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void vampireDrainSnapsWhenALineOfSightIsBroken() {
        try (Scenario s = Scenario.ffa("vampire")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "생명 흡수");
            s.ticks(11);
            double before = s.damageBy(s.owner, s.enemy);
            assertTrue(before >= 2, "draining");
            s.world.wallAlongX(-3, 3, 2, 3, Material.STONE);
            s.ticks(40);
            assertTrue(s.damageBy(s.owner, s.enemy) <= before + s.param("drain_damage"),
                    "the wall broke the drain");
            s.world.wallAlongX(-3, 3, 2, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void vampireBloodFeastStealsEverything() {
        try (Scenario s = Scenario.ffa("vampire")) {
            s.assertSucceeded(s.ultimate(), "피의 축제");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.SPEED));
            assertTrue(blockDisplays(s) > 0, "bats circle the vampire");
            s.owner.setHealth(6);
            s.melee(s.owner, s.enemy, 6);
            s.ticks(2);
            assertEquals(6 + 6 * s.param("ult_lifesteal") / 100, s.owner.getHealth(), EPSILON);
            s.ticks(s.paramTicks("ult_duration"));
            s.owner.setHealth(6);
            s.melee(s.owner, s.enemy, 5);
            s.ticks(2);
            assertEquals(6 + 5 * s.param("lifesteal") / 100, s.owner.getHealth(), EPSILON, "back to the passive");
            s.ticks(20);
            assertEquals(0, blockDisplays(s), "the bats flew off");
            s.finishClean();
        }
    }

    @Test
    void vampireBatsFlyHeadFirstAroundTheVampire() {
        try (Scenario s = Scenario.ffa("vampire")) {
            s.assertSucceeded(s.ultimate(), "피의 축제");
            s.ticks(3);
            List<BlockDisplay> bodies = blockDisplaysOf(s, Material.REDSTONE_BLOCK);
            assertEquals(3, bodies.size(), "three bats");
            Map<BlockDisplay, Location> before = new HashMap<>();
            bodies.forEach(body -> before.put(body, body.getLocation()));
            s.ticks(1);
            Location center = s.owner.getLocation();
            for (BlockDisplay body : bodies) {
                assertUnrotated(body, "bat body");
                Vector forward = forwardOf(body);
                Vector radial = body.getLocation().toVector().subtract(center.toVector()).setY(0).normalize();
                assertEquals(0, forward.getY(), 1.0E-6, "a bat flies level");
                assertEquals(0, forward.dot(radial), 0.01, "a bat flies along its circle: " + forward);
                Vector flown = body.getLocation().toVector().subtract(before.get(body).toVector()).setY(0);
                assertTrue(forward.dot(flown.normalize()) > 0.9, "head first: " + forward + " vs " + flown);
                // Both wings are hinged along the bat's flight axis.
                List<BlockDisplay> wings = blockDisplaysOf(s, Material.BLACK_CONCRETE).stream()
                        .filter(wing -> wing.getLocation().distance(body.getLocation()) < 1.0E-6).toList();
                assertEquals(2, wings.size(), "two wings on each bat");
                for (BlockDisplay wing : wings) {
                    assertUnrotated(wing, "bat wing");
                    assertTrue(forwardOf(wing).dot(forward) > 0.999, "a wing's hinge runs along the flight");
                }
            }
            s.ticks(s.paramTicks("ult_duration") + 20);
            assertEquals(0, blockDisplays(s), "the bats flew off");
            s.finishClean();
        }
    }

    @Test
    void vampireLifestealCountsDamageSoakedByAbsorptionHearts() {
        try (Scenario s = Scenario.ffa("vampire")) {
            s.owner.setHealth(10);
            giveAbsorption(s.enemy, 4);
            s.melee(s.owner, s.enemy, 5);
            assertEquals(0, s.enemy.getAbsorptionAmount(), EPSILON, "the hit broke through the absorption");
            s.ticks(2);
            assertEquals(10 + 5 * s.param("lifesteal") / 100, s.owner.getHealth(), EPSILON,
                    "all 5 damage heals, also the 4 the absorption hearts soaked up");
            takeAbsorption(s.enemy);
            s.finishClean();
        }
    }
}
