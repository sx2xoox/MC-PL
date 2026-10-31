package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 마크 몹 계열 (새 능력): 크리퍼, 셜커, 염소, 거북이. */
class MobNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final double BASE_SPEED = 0.1;
    private static final double BASE_JUMP = 0.42;
    /** The turtle's shell bounces off a wall from at most this far in front of it (its wall probe). */
    private static final double WALL_GAP = 0.3;
    /** What a walking key adds to a player's motion every tick on the ground (vanilla's {@code moveRelative}). */
    private static final double WALK_KEY = 0.13;

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof Display).count();
    }

    private static long blockDisplays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof BlockDisplay).count();
    }

    private static long itemDisplays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof ItemDisplay).count();
    }

    private static double speed(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
    }

    private static double jump(Player player) {
        return player.getAttribute(Attribute.JUMP_STRENGTH).getValue();
    }

    private static double scale(Player player) {
        return player.getAttribute(Attribute.SCALE).getValue();
    }

    private static double knockbackResistance(Player player) {
        return player.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue();
    }

    /**
     * Every display alive keeps yaw and pitch 0: on a real client a display's own rotation turns its model on top of
     * its transformation, so all orientation must live in the transformation.
     */
    private static void assertUnrotated(Scenario s, String what) {
        for (Entity entity : s.world.getEntities()) {
            if (entity instanceof Display display) {
                assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
                assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
            }
        }
    }

    /** The center of a display's model relative to the display's location (the unit cube's center for blocks). */
    private static Vector modelCenter(Display display) {
        Transformation pose = display.getTransformation();
        Vector3f half = display instanceof BlockDisplay ? new Vector3f(0.5f) : new Vector3f();
        // These abilities never set a right rotation; the display mock reports a bogus one (see leftRotation).
        Vector3f center = half.mul(pose.getScale());
        leftRotation(display).transform(center).add(pose.getTranslation());
        return new Vector(center.x(), center.y(), center.z());
    }

    /** Whether the display mock hands back rotations inverted (calibrated once, see {@link #leftRotation}). */
    private static @Nullable Boolean invertedReadBack;

    /**
     * The left rotation a client would apply to {@code display}. MockBukkit's display mock stores the matrix and, when
     * read back, reports its rotation conjugated (inverted) as both the left and the right rotation; this calibrates
     * once against a probe display and undoes the inversion.
     */
    private static Quaternionf leftRotation(Display display) {
        if (invertedReadBack == null) {
            World world = display.getWorld();
            BlockDisplay probe = world.spawn(new Location(world, 0.5, 200, 0.5), BlockDisplay.class);
            Quaternionf set = new Quaternionf().rotateY(1.0f);
            probe.setTransformation(new Transformation(new Vector3f(), set, new Vector3f(1), new Quaternionf()));
            invertedReadBack = probe.getTransformation().getLeftRotation().y() * set.y() < 0;
            probe.remove();
        }
        Quaternionf rotation = new Quaternionf(display.getTransformation().getLeftRotation());
        return invertedReadBack ? rotation.conjugate() : rotation;
    }

    /** The horizontal unit vector a Minecraft yaw faces (yaw 0 = +Z, 90 = -X). */
    private static Vector facing(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians));
    }

    /** Feet exactly on the flat world's surface and a body that touches no block. */
    private static void assertStanding(Scenario s, LivingEntity entity, String what) {
        assertEquals(FlatWorld.SURFACE, entity.getLocation().getY(), 1.0E-6, what + " stands on the surface");
        assertTrue(Spots.isFree(s.world, entity.getLocation(), entity.getWidth(), entity.getHeight()),
                what + " is not inside a block");
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    private static Scenario gambler() {
        // No lucky dodge / double damage: the borrowed ultimate alone decides what happens.
        return Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0).start();
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /**
     * A player's real client against what the server knows of it. A real server never reads a player's motion back
     * ({@code Player#getVelocity} only returns what the server last sent): the client moves by exactly the velocities
     * it is sent, one per client tick, keeps its own motion in between (sliding to a halt like on the ground), is
     * stopped by blocks like a real one, and the server sees where it is {@code lag} ticks late. World physics must be
     * off: this moves the player. Like a real client it may also add a movement key of its own to every move
     * ({@link #hold}), and a cobweb it is in holds it back like vanilla's.
     */
    private static final class LaggyClient {
        /** Marks the velocity the server last sent, to tell whether a skill sent a new one during a tick. */
        private static final double MARK = 1.0E-9;
        /** What is left of a move that starts in a cobweb (vanilla {@code WebBlock}); the motion is lost too. */
        private static final Vector WEBBED = new Vector(0.25, 0.05, 0.25);
        private final Scenario s;
        private final TestPlayer player;
        private final Deque<Location> late = new ArrayDeque<>();
        private final Location at;
        private final Vector velocity;
        private final Map<Integer, Function<Vector, @Nullable Vector>> interference = new HashMap<>();
        private final List<Vector> log = new ArrayList<>();
        private final Vector input = new Vector();
        private Vector sent;
        private int ticks;
        private boolean webbed;

        /** Starts with whatever velocity was already sent (by a skill used just now, outside a tick). */
        LaggyClient(Scenario s, TestPlayer player, int lag) {
            this.s = s;
            this.player = player;
            this.at = player.getLocation();
            for (int i = 0; i < lag; i++) {
                late.addLast(at.clone());
            }
            this.sent = player.getVelocity().clone();
            this.velocity = sent.clone();
        }

        /** Where the player really is (its client). */
        Location at() {
            return at.clone();
        }

        /**
         * What reaches the client of the velocity the server sends during server tick {@code tick} (0-based, counted
         * from this client's first tick): {@code what} maps it to the velocity the client really takes, or to null
         * when the packet is lost (two velocity packets in one client tick: the first is overwritten, so the client
         * keeps sliding on its own motion).
         */
        LaggyClient interfere(int tick, Function<Vector, @Nullable Vector> what) {
            interference.put(tick, what);
            return this;
        }

        /** Every velocity the server sent during this client's ticks, in order. */
        List<Vector> sentVelocities() {
            return List.copyOf(log);
        }

        /**
         * The player holds a movement key: every client tick {@code key} is added to its motion before it moves, like
         * vanilla's {@code moveRelative} (about 0.13 blocks a tick walking on the ground).
         */
        LaggyClient hold(Vector key) {
            input.copy(key);
            return this;
        }

        /** One client tick with what it was sent so far, then one server tick, which may send something new. */
        void tick() {
            velocity.add(input);
            Vector motion = velocity.clone();
            if (webbed) {
                motion.multiply(WEBBED);
                velocity.zero();
            }
            at.add(blockedStep(motion));
            webbed = inCobweb();
            boolean grounded = at.getY() <= FlatWorld.SURFACE;
            if (grounded) {
                at.setY(FlatWorld.SURFACE);
            }
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

            Vector marked = sent.clone().setX(sent.getX() + MARK);
            player.setVelocity(marked);
            s.ticks(1);
            Vector now = player.getVelocity();
            if (Double.compare(now.getX(), marked.getX()) != 0 || Double.compare(now.getY(), marked.getY()) != 0
                    || Double.compare(now.getZ(), marked.getZ()) != 0) {
                sent = now.clone();
                log.add(now.clone());
                Function<Vector, @Nullable Vector> what = interference.get(ticks);
                Vector taken = what == null ? now.clone() : what.apply(now.clone());
                if (taken != null) {
                    velocity.copy(taken);
                }
            }
            ticks++;
        }

        /**
         * How far the client really moves with {@code motion}: like vanilla, axis by axis (y, then x, then z), each up
         * to the first block in the way (the player's own body, scaled as it is now).
         */
        private Vector blockedStep(Vector motion) {
            double half = player.getWidth() / 2;
            BoundingBox box = new BoundingBox(at.getX() - half, at.getY(), at.getZ() - half, at.getX() + half,
                    at.getY() + player.getHeight(), at.getZ() + half);
            double dy = free(box, 0, motion.getY(), 0);
            box.shift(0, dy, 0);
            double dx = free(box, motion.getX(), 0, 0);
            box.shift(dx, 0, 0);
            double dz = free(box, 0, 0, motion.getZ());
            if (dx != motion.getX()) {
                velocity.setX(0);
            }
            if (dz != motion.getZ()) {
                velocity.setZ(0);
            }
            return new Vector(dx, dy, dz);
        }

        /** Whether the player's body is in a cobweb now, which holds back its next move (vanilla's stuck speed). */
        private boolean inCobweb() {
            double half = player.getWidth() / 2 - 1.0E-5;
            double top = at.getY() + player.getHeight() - 1.0E-5;
            for (int x = (int) Math.floor(at.getX() - half); x <= (int) Math.floor(at.getX() + half); x++) {
                for (int y = (int) Math.floor(at.getY() + 1.0E-5); y <= (int) Math.floor(top); y++) {
                    for (int z = (int) Math.floor(at.getZ() - half); z <= (int) Math.floor(at.getZ() + half); z++) {
                        if (s.world.getBlockAt(x, y, z).getType() == Material.COBWEB) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /** The part of a move along one axis that is free of blocks, to a thousandth of a block. */
        private double free(BoundingBox box, double x, double y, double z) {
            double length = Math.abs(x + y + z);
            if (length < 1.0E-9 || !s.world.hasCollisionsIn(box.clone().shift(x, y, z).expand(-1.0E-4))) {
                return x + y + z;
            }
            double lo = 0;
            double hi = 1;
            while ((hi - lo) * length > 1.0E-3) {
                double mid = (lo + hi) / 2;
                if (s.world.hasCollisionsIn(box.clone().shift(x * mid, y * mid, z * mid).expand(-1.0E-4))) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            return (x + y + z) * lo;
        }
    }

    // ================================================================== 크리퍼 creeper

    /**
     * The creeper's blast at an enemy whose hitbox is {@code distance} blocks from the blast center: linear falloff
     * from the full damage at the center to the minimum at the edge, both scaled by {@code multiplier}.
     */
    private static double creeperBlast(Scenario s, double distance, double radius, double multiplier) {
        double max = s.param("blast_damage") * multiplier;
        double min = s.param("blast_min_damage") * multiplier;
        return min + (max - min) * (1 - distance / radius);
    }

    @Test
    void creeperSwellsUpAndBlowsUpTheEnemiesAroundIt() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.assertSucceeded(s.active(), "자폭");
            assertFalse(s.active().succeeded(), "on cooldown (and already fusing)");
            assertEquals(BASE_SPEED * (1 - s.param("fuse_slow") / 100), speed(s.owner), EPSILON,
                    "the hissing creeper is slowed");
            int fuse = s.paramTicks("fuse");
            s.ticks(fuse - 4);
            assertTrue(scale(s.owner) > 1.05, "the creeper swells up: " + scale(s.owner));
            assertUnrotated(s, "fuse bubble");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "still hissing");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 10, "the blast");

            // The enemy's hitbox is 1.7 blocks from the creeper's middle.
            assertEquals(creeperBlast(s, 1.7, s.param("blast_radius"), 1), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is far away");
            assertEquals(s.param("blast_damage") * s.param("self_damage") / 100, s.damageTaken(s.owner), EPSILON,
                    "the creeper takes a part of its own blast");
            s.ticks(2);
            assertUnrotated(s, "blast shell, crater and debris");
            s.ticks(8);
            assertTrue(s.enemy.getLocation().getZ() > 4.0, "the enemy was blown away: " + s.enemy.getLocation());
            s.tickUntilGrounded(s.enemy, 60);
            assertStanding(s, s.enemy, "the blown-away enemy");
            assertEquals(1.0, scale(s.owner), EPSILON, "back to its normal size");
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON);

            s.ticks(60);
            assertEquals(0, displays(s), "the crater and the smoke faded");
            s.finishClean();
        }
    }

    @Test
    void creeperNeverBlowsItselfUpCompletely() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.place(s.enemy, 0.5, -9.5, 0);
            s.place(s.enemy2, 6.5, -9.5, 0);
            s.owner.setHealth(1);
            s.assertSucceeded(s.active(), "자폭");
            s.ticks(s.paramTicks("fuse") + 5);
            assertEquals(1, s.owner.getHealth(), EPSILON, "its own blast never kills it");
            assertTrue(s.session.isAlive(s.owner));
            s.finishClean();
        }
    }

    @Test
    void creeperBlastSparesAlliesAndStopsAtWalls() {
        try (Scenario s = Scenario.teams("creeper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.crossfireAlly();
            s.place(s.enemy2, 0.5, -2.5, 0);
            s.world.wallAlongX(-3, 3, -1, 4, Material.STONE);
            s.assertSucceeded(s.active(), "자폭");
            s.ticks(s.paramTicks("fuse") + 5);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy in the open is hit");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the wall takes the blast for enemy2");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally right next to the enemy is spared");
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));
            assertEquals(BASE_SPEED, speed(s.ally()), EPSILON);
            s.world.wallAlongX(-3, 3, -1, 4, Material.AIR);
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void creeperSneakingPacksGunpowderForABiggerBlast() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.owner.setSneaking(true);
            s.ticks(s.paramTicks("charge_time") / 2);
            assertEquals(1, itemDisplays(s), "a pinch of gunpowder circles the creeper per third of the charge");
            s.ticks(s.paramTicks("charge_time") / 2 + 2);
            assertEquals(3, itemDisplays(s), "fully packed");
            assertUnrotated(s, "gunpowder (it tumbles in its transformation)");
            s.owner.setSneaking(false);

            s.assertSucceeded(s.active(), "자폭");
            s.ticks(s.paramTicks("fuse") + 5);
            double radius = s.param("blast_radius") + s.param("charge_radius");
            double multiplier = 1 + s.param("charge_damage") / 100;
            assertEquals(creeperBlast(s, 1.7, radius, multiplier), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the packed gunpowder makes the blast stronger and wider");
            assertEquals(s.param("blast_damage") * multiplier * s.param("self_damage") / 100,
                    s.damageTaken(s.owner), EPSILON);
            s.ticks(10);
            assertEquals(0, itemDisplays(s), "the gunpowder was used up");

            // Packed again, then left alone: it scatters after a while.
            s.owner.setSneaking(true);
            s.ticks(s.paramTicks("charge_time") + 2);
            s.owner.setSneaking(false);
            s.ticks(s.paramTicks("charge_hold") - 5);
            assertEquals(3, itemDisplays(s), "kept for a moment");
            s.ticks(s.paramTicks("charge_time") + 25);
            assertEquals(0, itemDisplays(s), "scattered");
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void creeperChargedByLightningBlowsUpAgainAndAgainWithoutHurtingItself() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -9.5, 0);
            int lightning = s.world.lightningEffects().size();
            s.assertSucceeded(s.ultimate(), "충전된 크리퍼");
            assertFalse(s.ultimate().succeeded(), "already charged");
            assertTrue(s.world.lightningEffects().size() > lightning, "struck by lightning (effect only)");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the lightning is only a show");

            s.assertSucceeded(s.active(), "자폭");
            s.ticks(2);
            assertUnrotated(s, "charged aura and fuse bubble");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown while charged");
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "no slow while charged");
            s.ticks(s.paramTicks("ult_fuse"));
            double radius = s.param("blast_radius") + s.param("ult_radius");
            double multiplier = 1 + s.param("ult_damage") / 100;
            double one = creeperBlast(s, 1.7, radius, multiplier);
            assertEquals(one, s.damageBy(s.owner, s.enemy), EPSILON, "a charged blast");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a charged creeper does not hurt itself");

            s.place(s.enemy, 0.5, 2.5, 180);
            s.assertSucceeded(s.active(), "자폭 again right away");
            s.ticks(s.paramTicks("ult_fuse") + 2);
            assertEquals(2 * one, s.damageBy(s.owner, s.enemy), EPSILON, "and again");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);

            s.ticks(s.paramTicks("ult_duration") + 10);
            s.assertSucceeded(s.active(), "자폭 after the charge");
            assertTrue(s.ctx().cooldownRemaining(s.owner) > 0, "the cooldown is back");
            s.ticks(s.paramTicks("fuse") + 70);
            assertEquals(0, displays(s), "the charge aura and the craters are gone");
            s.finishClean();
        }
    }

    @Test
    void creeperDeathMidFuseIsSafe() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.assertSucceeded(s.ultimate(), "충전된 크리퍼");
            s.owner.setSneaking(true);
            s.ticks(s.paramTicks("charge_time") + 2);
            s.owner.setSneaking(false);
            s.assertSucceeded(s.active(), "자폭");
            s.ticks(3);
            s.killOwner(s.enemy);
            s.ticks(s.paramTicks("fuse") + 40);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "a dead creeper does not blow up");
            assertEquals(0, displays(s), "the gunpowder, the aura and the bubble went with it");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void creeperFuseLitWhileChargedGoesOffChargedAfterTheChargeRunsOut() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.assertSucceeded(s.ultimate(), "충전된 크리퍼");
            s.ticks(s.paramTicks("ult_duration") - 5);
            s.assertSucceeded(s.active(), "자폭, in the last moments of the charge");
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "a charged fuse: no slow");
            // The short charged fuse outlasts the charge: it used to go off as a plain blast that hurt the creeper.
            s.ticks(s.paramTicks("ult_fuse") + 5);
            double radius = s.param("blast_radius") + s.param("ult_radius");
            double multiplier = 1 + s.param("ult_damage") / 100;
            assertEquals(creeperBlast(s, 1.7, radius, multiplier), s.damageBy(s.owner, s.enemy), EPSILON,
                    "a fuse lit while charged goes off charged");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "and never hurts the charged creeper");
            s.ticks(80);
            s.finishClean();
        }
    }

    @Test
    void creeperGunpowderIsLockedInWhenTheFuseIsLit() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.owner.setSneaking(true);
            s.ticks(s.paramTicks("charge_time") + 2);
            s.owner.setSneaking(false);
            // Lit just before the packed gunpowder would start to scatter: it used to scatter while the fuse burned,
            // so the "full" blast came out a good deal weaker and smaller than shown when it was lit.
            s.ticks(s.paramTicks("charge_hold") - 2);
            s.assertSucceeded(s.active(), "자폭");
            s.ticks(s.paramTicks("fuse") + 5);
            double radius = s.param("blast_radius") + s.param("charge_radius");
            double multiplier = 1 + s.param("charge_damage") / 100;
            assertEquals(creeperBlast(s, 1.7, radius, multiplier), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the blast gets all the gunpowder packed when the fuse was lit");
            assertEquals(s.param("blast_damage") * multiplier * s.param("self_damage") / 100,
                    s.damageTaken(s.owner), EPSILON);
            s.ticks(80);
            s.finishClean();
        }
    }

    // ================================================================== 셜커 shulker

    @Test
    void shulkerShellCutsDamageButRootsItInPlace() {
        try (Scenario s = Scenario.ffa("shulker")) {
            s.place(s.enemy, 0.5, 1.5, 180);
            s.owner.setSneaking(true);
            s.ticks(10);
            assertEquals(0, speed(s.owner), EPSILON, "a shut shulker cannot walk");
            assertEquals(0, jump(s.owner), EPSILON, "or jump");
            assertEquals(1, knockbackResistance(s.owner), EPSILON, "or be knocked back");
            assertEquals(2, blockDisplays(s), "the two halves of the shell");
            assertUnrotated(s, "shell");
            for (Entity entity : s.world.getEntities()) {
                if (entity instanceof BlockDisplay half) {
                    Vector center = modelCenter(half);
                    assertEquals(0, center.getX(), 1.0E-3, "the shell is centered on the shulker");
                    assertEquals(0, center.getZ(), 1.0E-3, "the shell is centered on the shulker");
                }
            }

            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("shell_reduction") / 100), s.damageTaken(s.owner), EPSILON);

            s.owner.setSneaking(false);
            s.ticks(2);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "open again");
            assertEquals(BASE_JUMP, jump(s.owner), EPSILON);
            assertEquals(0, knockbackResistance(s.owner), EPSILON);
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "no reduction out of the shell");
            s.ticks(10);
            assertEquals(0, displays(s), "the shell is put away");
            s.finishClean();
        }
    }

    @Test
    void shulkerMustOpenItsShellToBite() {
        try (Scenario s = Scenario.ffa("shulker")) {
            s.place(s.enemy, 0.5, 1.5, 180);
            double reduced = 1 - s.param("shell_reduction") / 100;
            s.owner.setSneaking(true);
            s.ticks(10);
            assertEquals(0, speed(s.owner), EPSILON, "shut");

            // Trading sword blows from behind the shell's armour used to cost it only 40% of every hit back.
            s.melee(s.owner, s.enemy, 5);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "a bite pops the shell open");
            assertEquals(0, knockbackResistance(s.owner), EPSILON);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "an open shell takes nothing off");
            s.ticks(s.paramTicks("shell_reopen") - 2);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "it stays open for a moment, crouching or not");
            s.ticks(10);
            assertEquals(0, speed(s.owner), EPSILON, "then it shuts again");
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * reduced, s.damageTaken(s.owner), EPSILON);

            // Its bullets are no bite: they fly from the shut shell and land without opening it.
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "유도탄");
            double bullets = s.param("bullet_count") * s.param("bullet_damage");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) >= bullets - EPSILON, 100, "both bullets hit");
            assertEquals(0, speed(s.owner), EPSILON, "still shut");
            s.owner.setSneaking(false);
            s.ticks(20);
            s.finishClean();
        }
    }

    @Test
    void shulkerBulletsHuntTheEnemyDownAndFloatIt() {
        try (Scenario s = Scenario.ffa("shulker")) {
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.owner.setSneaking(true);
            s.ticks(10);
            s.assertSucceeded(s.active(), "유도탄 (fired from the shut shell)");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(3);
            assertUnrotated(s, "bullets and shell");
            double total = s.param("bullet_count") * s.param("bullet_damage");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) >= total - EPSILON, 100, "both bullets hit");
            assertEquals(total, s.damageBy(s.owner, s.enemy), EPSILON);
            PotionEffect floating = s.enemy.getPotionEffect(PotionEffectType.LEVITATION);
            assertTrue(floating != null && floating.getDuration() <= s.paramTicks("levitate"),
                    "floated for " + s.param("levitate") + " s");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.owner.setSneaking(false);
            s.ticks(20);
            assertEquals(0, displays(s), "the bullets and the shell are gone");
            s.finishClean();
        }
    }

    @Test
    void shulkerBulletsNeedSomethingToHunt() {
        try (Scenario s = Scenario.ffa("shulker")) {
            s.place(s.enemy, 0.5, -6.5, 0);
            s.place(s.enemy2, 3.5, -6.5, 0);
            ActivationResult result = s.active();
            assertFalse(result.succeeded(), "nobody in front: " + result.reason());
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown spent");
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void shulkerBulletsPassAlliesBy() {
        try (Scenario s = Scenario.teams("shulker")) {
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "유도탄");
            s.ticks(100);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy is hit");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally beside it is not");
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()), "and never floated");
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void shulkerFortressTurretsKeepFiringAtEnemiesButNeverAtAllies() {
        try (Scenario s = Scenario.teams("shulker")) {
            s.crossfireAlly();
            s.assertSucceeded(s.ultimate(), "셜커 요새");
            s.ticks(10);
            assertTrue(blockDisplays(s) >= 3 * s.param("turret_count"), "turrets rise around the shulker");
            assertUnrotated(s, "turrets");
            s.ticks(s.paramTicks("ult_duration") + s.paramTicks("bullet_life") + 20);
            double enemies = s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2);
            assertTrue(enemies >= 2 * s.param("ult_damage"), "the turrets keep hitting: " + enemies);
            double shots = enemies / s.param("ult_damage");
            assertEquals(Math.round(shots), shots, EPSILON, "bullet by bullet");
            assertTrue(enemies <= s.param("turret_count") * Math.ceil(s.param("ult_duration")
                    / s.param("ult_interval")) * s.param("ult_damage") + EPSILON, "never more than fired");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "never the ally");
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, displays(s), "the turrets sank back into the ground");
            s.finishClean();
        }
    }

    @Test
    void shulkerFortressFloatsEachEnemyOnlySoLong() {
        try (Scenario s = Scenario.ffa("shulker")) {
            s.place(s.enemy2, 0.5, -30.5, 0);
            s.assertSucceeded(s.ultimate(), "셜커 요새");
            int cap = s.paramTicks("ult_max_levitate");
            int granted = 0;
            boolean floated = false;
            double before = 0;
            for (int i = 0; i < s.paramTicks("ult_duration") + s.paramTicks("bullet_life"); i++) {
                s.ticks(1);
                double now = s.damageBy(s.owner, s.enemy);
                if (now > before + EPSILON) {
                    PotionEffect effect = s.enemy.getPotionEffect(PotionEffectType.LEVITATION);
                    if (effect != null) {
                        floated = true;
                        granted += s.paramTicks("ult_levitate");
                        s.enemy.removePotionEffect(PotionEffectType.LEVITATION);
                    }
                    before = now;
                }
            }
            assertTrue(floated, "turret bullets float the enemy");
            assertTrue(s.damageBy(s.owner, s.enemy) / s.param("ult_damage") > cap / (double) s.paramTicks(
                    "ult_levitate"), "more hits than the levitation cap allows");
            assertTrue(granted <= cap, "floated at most " + cap + " ticks, got " + granted);
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void shulkerFortressBorrowedByAGamblerWorks() {
        try (Scenario s = gambler()) {
            AbilityParams shulker = s.ctx().paramsFor("shulker");
            s.assertSucceeded(borrow(s, "shulker"), "borrowed 셜커 요새");
            s.ticks(10);
            assertTrue(blockDisplays(s) >= 3, "turrets rise");
            s.ticks((int) shulker.ticks("ult_duration") + (int) shulker.ticks("bullet_life") + 20);
            assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0, "and fire");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void shulkerDeathMidVolleyIsSafe() {
        try (Scenario s = Scenario.ffa("shulker")) {
            s.owner.setSneaking(true);
            s.ticks(10);
            s.assertSucceeded(s.ultimate(), "셜커 요새");
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "유도탄");
            s.ticks(2);
            s.killOwner(s.enemy2);
            double dealt = s.damageBy(s.owner, s.enemy);
            s.ticks(s.paramTicks("ult_duration") + 40);
            assertEquals(dealt, s.damageBy(s.owner, s.enemy), EPSILON, "nothing fires for a dead shulker");
            assertEquals(0, displays(s), "the shell, the turrets and the bullets went with it");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 염소 goat

    @Test
    void goatJumpsHigherAndShrugsOffFalls() {
        try (Scenario s = Scenario.ffa("goat")) {
            assertEquals(BASE_JUMP * (1 + s.param("jump_boost") / 100), jump(s.owner), EPSILON);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 10.5, 0.5, 0, 0);
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(2);
            // 10.5 blocks: ceil(10.5 - 3) = 8 vanilla fall damage.
            assertEquals(8 * (1 - s.param("fall_reduction") / 100), s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void goatRamsTheFirstEnemyClearAcrossTheField() {
        try (Scenario s = Scenario.ffa("goat")) {
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "박치기");
            assertFalse(s.active().succeeded(), "on cooldown");
            assertTrue(speed(s.owner) < BASE_SPEED * 0.5, "it plants its hooves for the wind-up");
            s.ticks(s.paramTicks("ram_windup") - 1);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "still winding up");
            assertTrue(itemDisplays(s) >= 2, "the horns are lowered");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 30, "the ram");
            assertEquals(s.param("ram_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(25);
            assertStanding(s, s.owner, "the goat after its charge");
            s.tickUntilGrounded(s.enemy, 60);
            assertStanding(s, s.enemy, "the rammed enemy");
            assertTrue(s.enemy.getLocation().getZ() > 4.5 + s.param("ram_knockback") * 0.6,
                    "sent flying: " + s.enemy.getLocation());
            assertEquals(s.param("ram_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "no wall, no extra pain");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS));
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON);
            s.ticks(20);
            assertEquals(0, displays(s), "the horns are put away");
            s.finishClean();
        }
    }

    @Test
    void goatHornsPointWhereTheGoatChargesWithoutRotatingTheDisplays() {
        try (Scenario s = Scenario.ffa("goat")) {
            // Facing west (-X), where no enemy stands: the display rotation would show here.
            s.place(s.owner, 0.5, 0.5, 90);
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 6.5, 9.5, 180);
            Vector forward = facing(90);
            Vector left = new Vector(0, 0, 1);
            s.assertSucceeded(s.active(), "박치기");
            s.ticks(s.paramTicks("ram_windup") - 1);
            assertUnrotated(s, "horns");
            List<ItemDisplay> horns = s.world.getEntities().stream().filter(ItemDisplay.class::isInstance)
                    .map(ItemDisplay.class::cast).toList();
            assertEquals(2, horns.size(), "two horns");
            for (ItemDisplay horn : horns) {
                Vector offset = modelCenter(horn);
                assertTrue(offset.dot(forward) > 0.1, "the horn sits in front of the head: " + offset);
                assertTrue(offset.getY() > 0.05, "above the eyes, out of the goat's view: " + offset);
            }
            double sides = modelCenter(horns.get(0)).dot(left) * modelCenter(horns.get(1)).dot(left);
            assertTrue(sides < 0, "one horn on each side of the head");
            s.ticks(30);
            assertTrue(s.owner.getLocation().getX() < -3, "it charged west: " + s.owner.getLocation());
            assertStanding(s, s.owner, "the goat after its charge");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void goatRamIntoAWallHurtsMore() {
        try (Scenario s = Scenario.ffa("goat")) {
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.world.wallAlongX(-3, 3, 7, 4, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "박치기");
            double both = s.param("ram_damage") + s.param("wall_damage");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) >= both - EPSILON, s.paramTicks("ram_windup") + 30,
                    "the slam into the wall");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS), "staggered");
            s.ticks(30);
            assertEquals(both, s.damageBy(s.owner, s.enemy), EPSILON, "rammed and slammed into the wall, once");
            assertTrue(s.enemy.getLocation().getZ() < 7, "it did not pass through the wall");
            s.tickUntilGrounded(s.enemy, 60);
            assertStanding(s, s.enemy, "the enemy slammed into the wall");
            s.world.wallAlongX(-3, 3, 7, 4, Material.AIR);
            s.ticks(20);
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void goatRamRunsPastAlliesToTheEnemy() {
        try (Scenario s = Scenario.teams("goat")) {
            s.place(s.ally(), 0.5, 2.0, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "박치기");
            s.ticks(s.paramTicks("ram_windup") + 30);
            assertEquals(s.param("ram_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the enemy behind the ally");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally is not rammed");
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));
            s.finishClean();
        }
    }

    @Test
    void goatRamEndsAtItsLengthEvenWhenTheServerSeesTheGoatLate() {
        try (Scenario s = Scenario.ffa("goat")) {
            s.place(s.enemy, 8.5, -6.5, 0);
            s.place(s.enemy2, -8.5, -6.5, 0);
            Location start = s.owner.getLocation();
            s.world.physics().setEnabled(false);
            // The server sees the goat three ticks late (a round trip of about 150 ms): measuring the charge on that
            // view kept sending the goat on for three more blocks after it had long arrived.
            LaggyClient client = new LaggyClient(s, s.owner, 3);
            s.assertSucceeded(s.active(), "박치기");
            for (int i = 0; i < s.paramTicks("ram_windup") + 40; i++) {
                client.tick();
            }
            double travelled = horizontalDistance(start, client.at());
            double length = s.param("ram_distance");
            assertTrue(travelled < length + 0.6, "the ram did not run on past its end: " + travelled);
            assertTrue(travelled > length - 0.5, "but ran its whole length: " + travelled);
            s.world.physics().setEnabled(true);
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, displays(s), "the horns are put away");
            s.finishClean();
        }
    }

    @Test
    void goatRamStillHitsAnEnemyAtTheEndOfTheChargeWhenTheServerSeesTheGoatLate() {
        try (Scenario s = Scenario.ffa("goat")) {
            s.place(s.enemy, 0.5, 7.5, 180);
            s.place(s.enemy2, -8.5, -6.5, 0);
            s.world.physics().setEnabled(false);
            // The whole charge has been sent before the server sees the goat reach the enemy near its end: the ram
            // keeps looking for a victim until the server has seen where the goat went.
            LaggyClient client = new LaggyClient(s, s.owner, 3);
            s.assertSucceeded(s.active(), "박치기");
            for (int i = 0; i < s.paramTicks("ram_windup") + 40; i++) {
                client.tick();
            }
            assertEquals(s.param("ram_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "rammed at the very end");
            s.world.physics().setEnabled(true);
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void goatHornsArePutAwayWhenTheRamHasNowhereToGo() {
        try (Scenario s = Scenario.ffa("goat")) {
            Location center = s.session.arena().center();
            double z = center.getZ();
            while (z < center.getZ() + 200 && s.ctx().isInsideArena(new Location(s.world, center.getX(),
                    FlatWorld.SURFACE, z + 0.5))) {
                z += 0.5;
            }
            // Right at the border, facing out of the arena.
            s.place(s.owner, center.getX(), z, 0);
            s.place(s.enemy, center.getX() + 8, z - 12, 0);
            s.place(s.enemy2, center.getX() - 8, z - 12, 0);
            s.assertSucceeded(s.active(), "박치기");
            s.ticks(s.paramTicks("ram_windup") - 1);
            assertEquals(2, itemDisplays(s), "the horns are lowered");
            // The ram cannot start: the horns used to hang frozen in the air for two more seconds.
            s.ticks(10);
            assertEquals(0, itemDisplays(s), "the horns are put away");
            assertTrue(s.ctx().isInsideArena(s.owner.getLocation()), "the goat stayed inside");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void goatLandslideSweepsEnemiesDownTheSlope() {
        try (Scenario s = Scenario.teams("goat")) {
            s.crossfireAlly();
            double allyZ = s.ally().getLocation().getZ();
            s.assertSucceeded(s.ultimate(), "산사태");
            s.ticks(s.paramTicks("slide_delay") - 2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the horn first, the rocks after");
            s.ticks(4);
            assertUnrotated(s, "horn and boulders");
            s.ticks(120);
            double damage = s.damageBy(s.owner, s.enemy);
            double waves = damage / s.param("boulder_damage");
            assertTrue(damage >= s.param("boulder_damage"), "swept by the landslide");
            assertEquals(Math.round(waves), waves, EPSILON, "once per wave");
            assertTrue(waves <= s.param("slide_waves") + EPSILON, "at most every wave");
            assertTrue(s.damageBy(s.owner, s.enemy2) >= s.param("boulder_damage"), "enemy2 too");
            assertTrue(s.enemy.getLocation().getZ() > 4.5 + 3, "carried down the slope: " + s.enemy.getLocation());
            s.tickUntilGrounded(s.enemy, 60);
            assertStanding(s, s.enemy, "the swept enemy");
            assertStanding(s, s.enemy2, "the other swept enemy");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally is never hit");
            assertEquals(allyZ, s.ally().getLocation().getZ(), 1.0E-3, "or moved");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, displays(s), "the boulders crumbled");
            s.finishClean();
        }
    }

    @Test
    void goatLandslideBreaksOnWalls() {
        try (Scenario s = Scenario.ffa("goat")) {
            s.place(s.enemy, 0.5, 5.5, 180);
            s.place(s.enemy2, 2.0, 7.5, 180);
            s.world.wallAlongX(-5, 5, 3, 2, Material.STONE);
            s.assertSucceeded(s.ultimate(), "산사태");
            s.ticks(140);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "safe behind the wall");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, displays(s));
            s.world.wallAlongX(-5, 5, 3, 2, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void goatLandslideBorrowedByAGamblerWorks() {
        try (Scenario s = gambler()) {
            s.assertSucceeded(borrow(s, "goat"), "borrowed 산사태");
            s.ticks(140);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the rocks hit the enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void goatDeathMidRamOrMidLandslideIsSafe() {
        try (Scenario s = Scenario.ffa("goat")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "산사태");
            s.assertSucceeded(s.active(), "박치기");
            s.ticks(4);
            s.killOwner(s.enemy2);
            s.ticks(160);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "neither the ram nor the rocks land");
            assertEquals(0, displays(s), "horns and boulders went with the goat");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 거북이 turtle

    @Test
    void turtleShellSitsOnItsBackWhicheverWayItTurns() {
        try (Scenario s = Scenario.ffa("turtle")) {
            for (float yaw : new float[] {90, -135, 0}) {
                s.place(s.owner, 0.5, 0.5, yaw);
                s.ticks(5);
                assertUnrotated(s, "back shell at yaw " + yaw);
                assertEquals(3, blockDisplays(s));
                for (Entity entity : s.world.getEntities()) {
                    if (entity instanceof BlockDisplay plate) {
                        Vector offset = modelCenter(plate).add(plate.getLocation().toVector())
                                .subtract(s.owner.getLocation().toVector());
                        assertTrue(offset.clone().setY(0).dot(facing(yaw)) < -0.1,
                                "the plate is on the turtle's back at yaw " + yaw + ": " + offset);
                        assertTrue(offset.getY() > 0.8 && offset.getY() < 1.5, "at back height: " + offset);
                    }
                }
            }
            s.finishClean();
        }
    }

    @Test
    void turtleShellTakesTheStingOutOfHitsFromBehind() {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.ticks(5);
            assertEquals(3, blockDisplays(s), "the turtle wears its shell");
            double reduced = 1 - s.param("back_reduction") / 100;

            s.place(s.enemy, 0.5, -1.5, 0);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * reduced, s.damageTaken(s.owner), EPSILON, "a stab in the back glances off");
            s.clearHits();
            s.ctx().damage(s.enemy, s.owner, 4);
            assertEquals(4 * reduced, s.damageTaken(s.owner), EPSILON, "abilities from behind too");

            s.clearHits();
            s.place(s.enemy, 0.5, 2.5, 180);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "the belly is soft");
            s.clearHits();
            s.place(s.enemy, 3.0, 0.5, 90);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "and so are the sides");
            s.finishClean();
        }
    }

    @Test
    void turtleSpinBowlsOverTheEnemyInItsWay() {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.place(s.enemy2, -8.5, -6.5, 0);
            s.assertSucceeded(s.active(), "껍질 회전");
            assertFalse(s.active().succeeded(), "on cooldown");
            assertEquals(0.65, scale(s.owner), EPSILON, "pulled into the shell");
            s.ticks(2);
            assertUnrotated(s, "spinning shell (it whirls in its transformation)");
            s.melee(s.enemy2, s.owner, 10);
            assertEquals(10 * (1 - s.param("spin_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "the spinning shell takes less damage");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 30, "the shell reaches the enemy");
            assertEquals(s.param("spin_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(40);
            assertEquals(s.param("spin_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "hit once");
            assertTrue(Math.abs(s.enemy.getLocation().getX() - 0.5) > 1.0, "bowled aside: " + s.enemy.getLocation());
            assertStanding(s, s.owner, "the turtle after its spin");
            s.tickUntilGrounded(s.enemy, 60);
            assertStanding(s, s.enemy, "the bowled enemy");
            assertTrue(s.owner.getLocation().getZ() > 8, "the shell slid on: " + s.owner.getLocation());
            assertEquals(1.0, scale(s.owner), EPSILON, "back out of the shell");
            assertEquals(10 * (1 - s.param("spin_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "no fall damage");
            assertEquals(3, blockDisplays(s), "the shell is back on its back");
            s.finishClean();
        }
    }

    @Test
    void turtleSpinRicochetsOffWalls() {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.world.wallAlongX(-3, 3, 4, 3, Material.STONE);
            s.place(s.enemy, 0.5, -3.5, 0);
            s.place(s.enemy2, -8.5, -6.5, 0);
            s.assertSucceeded(s.active(), "껍질 회전");
            s.ticks(60);
            assertEquals(s.param("spin_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "it bounced off the wall and into the enemy behind it");
            assertTrue(s.owner.getLocation().getZ() < 0, "it came back: " + s.owner.getLocation());
            assertStanding(s, s.owner, "the turtle after the ricochet");
            s.tickUntilGrounded(s.enemy, 60);
            assertStanding(s, s.enemy, "the enemy hit by the ricochet");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(1.0, scale(s.owner), EPSILON);
            s.world.wallAlongX(-3, 3, 4, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void turtleSpinEndsAtItsLengthEvenWhenTheServerSeesTheTurtleLate() {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.place(s.enemy, 8.5, -6.5, 0);
            s.place(s.enemy2, -8.5, -6.5, 0);
            Location start = s.owner.getLocation();
            s.world.physics().setEnabled(false);
            s.assertSucceeded(s.active(), "껍질 회전");
            // The server sees the turtle three ticks late (a round trip of about 150 ms): measuring the slide on that
            // view kept sending the shell on for more than two blocks after it had long arrived.
            LaggyClient client = new LaggyClient(s, s.owner, 3);
            for (int i = 0; i < 50; i++) {
                client.tick();
            }
            double travelled = horizontalDistance(start, client.at());
            double length = s.param("spin_distance");
            assertTrue(travelled < length + 0.6, "the shell did not slide on past its end: " + travelled);
            assertTrue(travelled > length - 0.5, "but slid its whole length: " + travelled);
            s.world.physics().setEnabled(true);
            s.ticks(20);
            assertEquals(1.0, scale(s.owner), EPSILON, "back out of the shell");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    /**
     * A real client often covers less than it was sent: a velocity packet is lost (two arrive in one client tick and
     * the first is overwritten), or a hit handled after the spin's push in the same tick knocks the turtle back
     * (vanilla knockback halves the motion and pushes away from the attacker). Once the whole length is sent the shell
     * must just come to rest short of its end, not take that stop for a wall and bounce back off nothing.
     */
    @Test
    void turtleSpinNeverBouncesOffNothingWhenTheClientFallsShort() {
        Function<Vector, @Nullable Vector> lost = velocity -> null;
        assertSpinNeverTurnsBack(0, Map.of(4, lost, 8, lost), "two lost packets");
        assertSpinNeverTurnsBack(3, Map.of(4, lost, 8, lost), "two lost packets, seen three ticks late");
        assertSpinNeverTurnsBack(0, Map.of(6, MobNewAbilitiesTest::knockedBackFromTheFront),
                "a hit from the front right after a push");
    }

    /** Vanilla {@code LivingEntity#knockback} of strength 0.4 on a grounded player hit by someone in front (+Z). */
    private static Vector knockedBackFromTheFront(Vector velocity) {
        return new Vector(velocity.getX() / 2, Math.min(0.4, velocity.getY() / 2 + 0.4), velocity.getZ() / 2 - 0.4);
    }

    private static void assertSpinNeverTurnsBack(int lag, Map<Integer, Function<Vector, @Nullable Vector>> trouble,
            String what) {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.place(s.enemy, 8.5, -6.5, 0);
            s.place(s.enemy2, -8.5, -6.5, 0);
            Location start = s.owner.getLocation();
            Vector ahead = facing(start.getYaw());
            assertEquals(1, ahead.getZ(), EPSILON, "the turtle spins off to +Z (the front knockback assumes it)");
            s.world.physics().setEnabled(false);
            s.assertSucceeded(s.active(), "껍질 회전");
            LaggyClient client = new LaggyClient(s, s.owner, lag);
            trouble.forEach(client::interfere);
            double farthest = 0;
            for (int i = 0; i < 50; i++) {
                client.tick();
                farthest = Math.max(farthest, client.at().toVector().subtract(start.toVector()).dot(ahead));
            }
            for (Vector sent : client.sentVelocities()) {
                assertTrue(sent.dot(ahead) > -EPSILON, what + ": the shell was never sent back (no wall): "
                        + client.sentVelocities());
            }
            double reached = client.at().toVector().subtract(start.toVector()).dot(ahead);
            assertEquals(farthest, reached, 0.05, what + ": the shell rests where it got to, it did not slide back");
            assertTrue(reached > s.param("spin_distance") - 2, what + ": it still slid most of its length: " + reached);
            s.world.physics().setEnabled(true);
            s.ticks(20);
            assertEquals(1.0, scale(s.owner), EPSILON, what + ": back out of the shell");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, what);
            s.finishClean();
        }
    }

    @Test
    void turtleSpinNeverHurtsAllies() {
        try (Scenario s = Scenario.teams("turtle")) {
            s.place(s.ally(), 0.5, 2.5, 180);
            s.assertSucceeded(s.active(), "껍질 회전");
            s.ticks(50);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy is bowled over");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally in the way is not");
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));
            s.finishClean();
        }
    }

    @Test
    void turtleGiantShellShrugsOffHitsAndThornsEveryEnemyAround() {
        try (Scenario s = Scenario.teams("turtle")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, -2.5, 0.5, 270);
            s.crossfireAlly();
            s.assertSucceeded(s.ultimate(), "거대 등껍질");
            assertFalse(s.ultimate().succeeded(), "already carrying it");
            assertFalse(s.active().succeeded(), "too heavy to spin");
            s.ticks(12);
            assertEquals(BASE_SPEED * (1 - s.param("ult_slow") / 100), speed(s.owner), EPSILON, "heavy");
            assertEquals(1, knockbackResistance(s.owner), EPSILON, "unmovable");
            assertTrue(blockDisplays(s) >= 12, "a giant shell: " + blockDisplays(s));
            assertUnrotated(s, "giant shell");

            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("ult_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "almost nothing gets through");
            s.ticks(8);
            double thorns = 10 * s.param("ult_thorns") / 100;
            assertEquals(thorns, s.damageBy(s.owner, s.enemy), EPSILON, "the attacker is thorned");
            assertEquals(thorns, s.damageBy(s.owner, s.enemy2), EPSILON, "and so is every enemy around");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "never the ally");
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));

            s.ticks(s.paramTicks("ult_duration") + 10);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "light again");
            assertEquals(0, knockbackResistance(s.owner), EPSILON);
            assertEquals(3, blockDisplays(s), "only its own shell is left");
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON);
            s.ticks(10);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "no thorns after the ultimate");
            s.finishClean();
        }
    }

    @Test
    void turtleGiantShellOpensWhereTheTurtleLooksAndTurnsWithIt() {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 6.5, 9.5, 180);
            s.place(s.owner, 0.5, 0.5, 90);
            s.assertSucceeded(s.ultimate(), "거대 등껍질");
            s.ticks(20);
            assertGiantShellFaces(s, 90);
            s.place(s.owner, 0.5, 0.5, -90);
            s.ticks(4);
            assertGiantShellFaces(s, -90);
            s.ticks(s.paramTicks("ult_duration"));
            s.finishClean();
        }
    }

    /** The giant shell's plates keep yaw 0, the flanks leave the front (where the turtle looks) open. */
    private static void assertGiantShellFaces(Scenario s, float yaw) {
        assertUnrotated(s, "giant shell at yaw " + yaw);
        Vector front = facing(yaw);
        int flanks = 0;
        int behind = 0;
        for (Entity entity : s.world.getEntities()) {
            if (!(entity instanceof BlockDisplay plate)) {
                continue;
            }
            Vector offset = modelCenter(plate).add(plate.getLocation().toVector())
                    .subtract(s.owner.getLocation().toVector());
            Vector flat = offset.clone().setY(0);
            if (offset.getY() < 0.9 || flat.lengthSquared() < 0.25) {
                continue; // the low rim runs all the way round, the cap sits on top
            }
            flanks++;
            double cos = flat.normalize().dot(front);
            assertTrue(cos < Math.cos(Math.toRadians(35)), "the shell is open in front at yaw " + yaw + ": " + offset);
            if (cos < -0.5) {
                behind++;
            }
        }
        assertTrue(flanks >= 5, "the flanks of the giant shell: " + flanks);
        assertTrue(behind >= 2, "the shell covers the turtle's back: " + behind);
    }

    @Test
    void turtleDeathMidSpinOrUnderTheGiantShellIsSafe() {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.assertSucceeded(s.active(), "껍질 회전");
            s.ticks(3);
            s.killOwner(s.enemy2);
            s.ticks(40);
            assertEquals(0, displays(s), "the shell went with the turtle");
            s.assertNoProblems();
            s.finishClean();
        }
        try (Scenario s = Scenario.ffa("turtle")) {
            s.assertSucceeded(s.ultimate(), "거대 등껍질");
            s.ticks(5);
            s.melee(s.enemy, s.owner, 4);
            s.killOwner(s.enemy2);
            s.ticks(s.paramTicks("ult_duration") + 20);
            assertEquals(0, displays(s), "the giant shell went with the turtle");
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "a dead turtle has no thorns");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    /**
     * The shell meets a wall where its client really is. Judged on the server's view, a lagging turtle sat against
     * the wall for a round trip (four ticks at 150 ms, six at 250 ms) before it bounced off.
     */
    @ParameterizedTest(name = "{0} ms")
    @ValueSource(ints = {0, 150, 250})
    void turtleSpinBouncesOffAWallTheMomentItsClientHitsIt(int ping) {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.world.wallAlongX(-3, 3, 6, 3, Material.STONE);
            s.place(s.enemy, 8.5, -6.5, 0);
            s.place(s.enemy2, -8.5, -6.5, 0);
            s.owner.setPing(ping);
            Location start = s.owner.getLocation();
            s.world.physics().setEnabled(false);
            s.assertSucceeded(s.active(), "껍질 회전");
            double face = 6 - s.owner.getWidth() / 2; // where the shrunk turtle touches the wall
            LaggyClient client = new LaggyClient(s, s.owner, ping / 50);
            List<Double> path = new ArrayList<>();
            double length = 0;
            Location before = client.at();
            for (int i = 0; i < 50; i++) {
                client.tick();
                length += horizontalDistance(before, client.at());
                before = client.at();
                path.add(before.getZ());
            }
            double deepest = path.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            assertTrue(deepest <= face + 1.0E-3, ping + " ms: never into the wall: " + deepest);
            assertTrue(deepest > face - WALL_GAP, ping + " ms: but right up to it: " + deepest);
            long pinned = path.stream().filter(z -> z > deepest - 0.05).count();
            assertTrue(pinned <= 1, ping + " ms: it bounces off at once, not after " + pinned + " ticks: " + path);
            assertTrue(client.at().getZ() < start.getZ(), ping + " ms: it came back: " + client.at());
            assertEquals(s.param("spin_distance"), length, 0.6, ping + " ms: there and back, its whole length");
            s.world.physics().setEnabled(true);
            s.ticks(20);
            assertEquals(1.0, scale(s.owner), EPSILON, ping + " ms: back out of the shell");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.world.wallAlongX(-3, 3, 6, 3, Material.AIR);
            s.finishClean();
        }
    }

    /**
     * Enemies are met where the shell really is. Judged on the server's view, a lagging turtle bowled enemies over a
     * round trip after it had rolled through them: one that stepped aside right after the shell went through it on the
     * turtle's screen got away, one that stepped into the trail the shell had long left was bowled over.
     */
    @ParameterizedTest(name = "{0} ms")
    @ValueSource(ints = {0, 150, 250})
    void turtleSpinBowlsOverWhoeverItsClientRollsThrough(int ping) {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.place(s.enemy, 0.5, 4.5, 180); // in the way
            s.place(s.enemy2, 8.5, -6.5, 0); // out of the way, for now
            s.owner.setPing(ping);
            s.world.physics().setEnabled(false);
            s.assertSucceeded(s.active(), "껍질 회전");
            LaggyClient client = new LaggyClient(s, s.owner, ping / 50);
            boolean dodged = false;
            boolean steppedIn = false;
            for (int i = 0; i < 50; i++) {
                client.tick();
                double z = client.at().getZ();
                if (!dodged && z > 4.5 + 0.6) {
                    // The shell has rolled right through it: it steps aside.
                    s.place(s.enemy, 6.5, 4.5, 180);
                    dodged = true;
                }
                if (!steppedIn && z > 10) {
                    // Into the trail the shell left behind it, out of its reach.
                    s.place(s.enemy2, 0.5, z - 1.8, 180);
                    steppedIn = true;
                }
            }
            assertTrue(dodged && steppedIn, ping + " ms: the shell slid on past both spots");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON,
                    ping + " ms: the one that stepped in behind the shell is not");
            assertEquals(s.param("spin_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    ping + " ms: the enemy it rolled through is bowled over, even though it stepped aside right after");
            s.world.physics().setEnabled(true);
            s.ticks(20);
            assertEquals(1.0, scale(s.owner), EPSILON, ping + " ms: back out of the shell");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    /**
     * The player steers its rolling shell with a key of its own, which the server never sends: it only sees where the
     * shell went, a round trip late. Reckoned from the cast alone (every step sent added up), the shell was judged on
     * the straight line it had long left, further off with every tick, even at 0 ms where the server's view is exact:
     * it missed the enemy it rolled right through and bowled over one two blocks off its path. Re-reckoned from the
     * server's view every tick, it is off by no more than what the key did within one round trip.
     */
    @ParameterizedTest(name = "{0} ms")
    @ValueSource(ints = {0, 150, 250})
    void turtleSpinMeetsEnemiesWhereItsPlayerSteersIt(int ping) {
        try (Scenario s = Scenario.ffa("turtle")) {
            Location through = new Location(s.world, 2.06, FlatWorld.SURFACE, 10.5); // where the steered shell rolls
            Location aside = new Location(s.world, 0.3, FlatWorld.SURFACE, 11.5); // on the straight line it left
            s.place(s.enemy, through.getX(), through.getZ(), 180);
            s.place(s.enemy2, aside.getX(), aside.getZ(), 180);
            s.owner.setPing(ping);
            s.world.physics().setEnabled(false);
            s.assertSucceeded(s.active(), "껍질 회전");
            LaggyClient client = new LaggyClient(s, s.owner, ping / 50).hold(new Vector(WALK_KEY, 0, 0));
            double closest = Double.MAX_VALUE;
            double nearestAside = Double.MAX_VALUE;
            for (int i = 0; i < 40; i++) {
                client.tick();
                closest = Math.min(closest, horizontalDistance(client.at(), through));
                nearestAside = Math.min(nearestAside, horizontalDistance(client.at(), aside));
            }
            assertTrue(closest < 0.5 && nearestAside > 2.0, ping + " ms: the shell really rolls through one, "
                    + "far past the other: " + closest + ", " + nearestAside);
            assertEquals(s.param("spin_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    ping + " ms: the enemy the shell rolled through is bowled over");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON,
                    ping + " ms: the one on the straight line the shell never rolled along is not");
            s.world.physics().setEnabled(true);
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    /**
     * A cobweb on the way holds the shell back (a quarter of every move), which the steps sent never showed: reckoned
     * from them, the shell rolled on through the web on the server's books, bowled over an enemy beyond it while the
     * real shell was still caught three blocks short, and ended its spin blocks early. The web is now in the
     * reckoning, and the server's view keeps it honest.
     */
    @ParameterizedTest(name = "{0} ms")
    @ValueSource(ints = {0, 150, 250})
    void turtleSpinCaughtInACobwebBowlsOverOnlyWhatItReaches(int ping) {
        try (Scenario s = Scenario.ffa("turtle")) {
            Block web = s.world.getBlockAt(0, (int) FlatWorld.SURFACE, 3);
            web.setType(Material.COBWEB);
            s.place(s.enemy, 0.5, 6.5, 180); // beyond the web
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.owner.setPing(ping);
            s.world.physics().setEnabled(false);
            s.assertSucceeded(s.active(), "껍질 회전");
            LaggyClient client = new LaggyClient(s, s.owner, ping / 50);
            double length = 0;
            int held = 0;
            double hitAt = Double.NaN;
            Location before = client.at();
            for (int i = 0; i < 60; i++) {
                client.tick();
                double step = horizontalDistance(before, client.at());
                length += step;
                if (step > 0.05 && step < 0.3) {
                    held++;
                }
                before = client.at();
                if (Double.isNaN(hitAt) && s.damageBy(s.owner, s.enemy) > 0) {
                    hitAt = client.at().getZ();
                }
            }
            assertTrue(held >= 3, ping + " ms: the web held the shell back: " + held);
            assertEquals(s.param("spin_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    ping + " ms: the enemy beyond the web is bowled over");
            assertTrue(6.5 - hitAt < 1.3, ping + " ms: once the shell gets to it, not while it is still caught in the "
                    + "web: the shell was at " + hitAt);
            assertEquals(s.param("spin_distance"), length, 0.6,
                    ping + " ms: the web slows the shell down, it still rolls its whole length");
            web.setType(Material.AIR);
            s.world.physics().setEnabled(true);
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    /**
     * The player holds its back key against the spin. Reckoned from the steps sent alone, the shell bounced off the
     * wall a block short of it (where the steps said it was), even at 0 ms. Re-reckoned from the server's view, it is
     * off by no more than what the key did within one round trip.
     */
    @ParameterizedTest(name = "{0} ms")
    @ValueSource(ints = {0, 150, 250})
    void turtleSpinHeldBackBouncesOffTheWallWhereItReallyIs(int ping) {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.world.wallAlongX(-3, 3, 6, 3, Material.STONE);
            s.place(s.enemy, 8.5, -6.5, 0);
            s.place(s.enemy2, -8.5, -6.5, 0);
            s.owner.setPing(ping);
            s.world.physics().setEnabled(false);
            s.assertSucceeded(s.active(), "껍질 회전");
            double face = 6 - s.owner.getWidth() / 2; // where the shrunk turtle touches the wall
            LaggyClient client = new LaggyClient(s, s.owner, ping / 50).hold(new Vector(0, 0, -WALK_KEY));
            double deepest = client.at().getZ();
            for (int i = 0; i < 50; i++) {
                client.tick();
                deepest = Math.max(deepest, client.at().getZ());
            }
            double slack = WALL_GAP + ping / 50 * WALK_KEY + 0.02;
            assertTrue(deepest <= face + 1.0E-3, ping + " ms: never into the wall: " + deepest);
            assertTrue(deepest > face - slack, ping + " ms: it bounces off the wall, not " + (face - deepest)
                    + " short of it");
            assertTrue(client.at().getZ() < s.owner.getWidth(), ping + " ms: it came back: " + client.at());
            s.world.physics().setEnabled(true);
            s.ticks(20);
            assertEquals(1.0, scale(s.owner), EPSILON, ping + " ms: back out of the shell");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.world.wallAlongX(-3, 3, 6, 3, Material.AIR);
            s.finishClean();
        }
    }
}
