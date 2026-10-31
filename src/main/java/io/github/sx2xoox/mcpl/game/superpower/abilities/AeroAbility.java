package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 바람술사 — mobility and displacement. Never takes fall damage, leaps on a gust that blasts enemies away at both
 * ends, and summons a tornado that lifts enemies, spins them around and slams them into the ground.
 *
 * <p>Look (white, pale mint, aquamarine): gusts are expanding wind rings with a rising swirl and puffs blown
 * outward; the leap trails crisp white speed lines. The tornado is a stack of spinning translucent wind bands that
 * narrow to the ground, with chunks of the ground tumbling around inside and a wind ring marking its reach.
 */
public final class AeroAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("aero", "바람술사", Material.WIND_CHARGE)
            .color(TextColor.color(0xA6F0D8))
            .difficulty(2)
            .passive("바람의 가호", "낙하 피해를 받지 않습니다.")
            .active("돌풍 도약",
                    "바라보는 방향으로 약 {distance}블록 도약합니다. 출발 지점과 착지 지점 반경 {radius}블록의 적에게 "
                            + "{damage} 피해를 주고 밀쳐냅니다.",
                    6)
            .ultimate("토네이도",
                    "{ult_duration}초간 그 자리에 토네이도를 일으킵니다. 반경 {ult_radius}블록에 들어온 적을 "
                            + "{lift_height}블록 높이로 띄워 휘돌린 뒤, 땅에 내리꽂아 {slam_damage} 피해를 줍니다.")
            .param("distance", 10)
            .param("radius", 3)
            .param("damage", 2)
            .param("ult_duration", 4)
            .param("ult_radius", 7)
            .param("lift_height", 5)
            .param("slam_damage", 4)
            .build();

    // Gust leap
    /** Horizontal blocks covered by a flat leap per 1.0 of launch speed (air drag 0.91, ~12 ticks airborne). */
    private static final double DISTANCE_PER_SPEED = 7.5;
    private static final double MIN_LIFT = 0.42;
    private static final double MAX_VELOCITY = 2.5;
    private static final double PUSH_HORIZONTAL = 1.0;
    private static final double PUSH_VERTICAL = 0.38;
    private static final long MAX_AIR_TICKS = 80;
    /** Ticks after which a leap that never left the ground (e.g. under a ceiling) still counts as landed. */
    private static final long STUCK_LANDING_TICKS = 10;

    // Tornado
    private static final long RISE_TICKS = 20;
    private static final double SPIN_PER_TICK = 0.16;
    private static final double ORBIT_BASE = 1.8;
    private static final double ORBIT_STEP = 0.9;
    /** Feedback gain of the orbit steering; low enough to stay stable with a few ticks of client latency. */
    private static final double STEER_GAIN = 0.15;
    private static final double MAX_STEER = 1.2;
    /** Vertical bobbing while spinning; also keeps the vanilla anti-fly check from counting a hover. */
    private static final double BOB_AMPLITUDE = 0.45;
    private static final double BOB_FREQUENCY = 0.3;
    private static final double ESCAPE_MARGIN = 6;
    private static final double SLAM_SPEED = 1.6;
    private static final long SLAM_TIMEOUT_TICKS = 60;
    private static final int FUNNEL_LEVELS = 8;
    private static final double FUNNEL_FLARE = 3.2;

    // Visuals
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xCFFAEE, 0x7FE0C2);
    private static final Particle.DustOptions WIND_DUST = PALETTE.dust(1.3f);
    private static final Particle.DustOptions EDGE_DUST = new Particle.DustOptions(PALETTE.edge(), 1.1f);
    private static final BlockData STREAK = Material.WHITE_CONCRETE.createBlockData();
    private static final BlockData BAND = Material.WHITE_STAINED_GLASS.createBlockData();
    /** Display entities this ability keeps alive at most at once (extras are skipped, particles remain). */
    private static final int MAX_DISPLAYS = 24;
    /** Speed lines around the leaping owner: x/y offsets in the owner's flight frame and their length. */
    private static final float[][] STREAKS = {{0.45f, -0.15f, 1.7f}, {-0.45f, -0.15f, 1.7f}, {0f, 0.55f, 1.3f}};
    private static final float STREAK_THICKNESS = 0.035f;
    private static final int STREAK_GROW_TICKS = 3;
    /** Height fractions of the tornado's wind bands. */
    private static final double[] BAND_LEVELS = {0.15, 0.5, 0.85};
    /** Bars per wind band (a regular polygon). */
    private static final int BAND_SIDES = 6;
    private static final float BAND_THICKNESS = 0.09f;
    /**
     * Radians a wind band turns per tick; alternate bands turn the other way. Small enough that each interpolated
     * step barely cuts the corner (a display's position interpolates in a straight line).
     */
    private static final double BAND_SPIN = 0.15;
    private static final long BAND_GROW_TICKS = 10;
    private static final long BAND_STEP_TICKS = 2;
    private static final int DEBRIS = 4;
    private static final long FUNNEL_FADE_TICKS = 8;

    /** Display entities spawned by this instance that are still alive. */
    private int liveDisplays;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: no fall damage

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (!event.isCancelled() && Combat.isFall(event)) {
            event.setCancelled(true);
            softLanding(player().getLocation());
        }
    }

    /** The wind catches the owner: a cushion of air puffs out around the feet. */
    private void softLanding(Location feet) {
        World world = feet.getWorld();
        Location ground = feet.clone().add(0, 0.15, 0);
        int puffs = Vfx.n(ctx(), 10);
        for (int i = 0; i < puffs; i++) {
            double angle = i * (Math.PI * 2 / puffs);
            // count 0: the offsets become the particle's direction and "extra" its speed.
            world.spawnParticle(Particle.CLOUD, ground.getX(), ground.getY(), ground.getZ(), 0, Math.cos(angle), 0.05,
                    Math.sin(angle), 0.12);
        }
        Vfx.ring(ctx(), ground, 0.7, 12, 0, Particle.DUST, WIND_DUST, false);
        Fx.sound(feet, Sound.ENTITY_BREEZE_LAND, 0.6f, 1.2f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ active: gust leap

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 도약할 수 없습니다");
        }
        Location start = owner.getLocation();
        gust(start);

        double speed = Math.max(0.3, params().num("distance") / DISTANCE_PER_SPEED);
        Vector velocity = owner.getEyeLocation().getDirection().multiply(speed);
        // The leap must land inside the arena: shorten its horizontal part when the border is in the way.
        double travel = Math.hypot(velocity.getX(), velocity.getZ()) * DISTANCE_PER_SPEED;
        double reach = Spots.arenaReach(ctx(), start, new Vector(velocity.getX(), 0, velocity.getZ()), travel);
        if (travel > 1.0E-3 && reach < travel) {
            velocity.setX(velocity.getX() * reach / travel).setZ(velocity.getZ() * reach / travel);
        }
        velocity.setY(Math.max(velocity.getY(), MIN_LIFT));
        owner.setVelocity(Motion.clamp(velocity, MAX_VELOCITY));
        owner.setFallDistance(0);
        Fx.sound(start, Sound.ENTITY_BREEZE_JUMP, 1.0f, 1.0f + Fx.jitter(0.1f));
        Fx.sound(start, Sound.ITEM_ELYTRA_FLYING, 0.25f, 1.6f + Fx.jitter(0.1f));

        SpeedLines lines = new SpeedLines(owner, velocity);
        boolean[] airborne = {false};
        Location[] last = {start.clone()};
        Loop.repeat(ctx(), player(), 1, 1, MAX_AIR_TICKS, tick -> {
            Location now = owner.getLocation();
            boolean landed = Motion.isGrounded(owner) || owner.isInWater() || owner.isClimbing();
            if (!landed) {
                airborne[0] = true;
                lines.follow(owner, now.toVector().subtract(last[0].toVector()));
                flightTrail(last[0], now, tick);
                last[0] = now;
                return true;
            }
            if (airborne[0] || tick >= STUCK_LANDING_TICKS) {
                lines.fade();
                gust(now);
                Fx.sound(now, Sound.ENTITY_BREEZE_LAND, 1.0f, 0.9f + Fx.jitter(0.1f));
                BlockData floor = now.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
                if (!floor.getMaterial().isAir()) {
                    now.getWorld().spawnParticle(Particle.DUST_PILLAR, now.clone().add(0, 0.1, 0), Vfx.n(ctx(), 14),
                            0.6, 0.1, 0.6, 0.1, floor);
                }
                return false;
            }
            return true;
        }, lines::fade);
        return ActivationResult.success();
    }

    /** The air the owner cuts through: a band of mint dust along the path, puffs of cloud and small gusts. */
    private void flightTrail(Location from, Location to, long tick) {
        World world = to.getWorld();
        Vfx.line(ctx(), from.clone().add(0, 0.3, 0), to.clone().add(0, 0.3, 0), 2.0, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(0.9f), false);
        world.spawnParticle(Particle.CLOUD, to.clone().add(0, 0.2, 0), Vfx.n(ctx(), 1), 0.15, 0.1, 0.15, 0.01);
        if (tick % 3 == 0) {
            world.spawnParticle(Particle.SMALL_GUST, to.clone().add(0, 0.6, 0), 1, 0.2, 0.3, 0.2, 0);
        }
    }

    /**
     * Crisp white speed lines around the leaping owner. They follow the owner's flight direction (measured from its
     * movement, since a player's server-side velocity is unreliable): their transformation turns them, and their
     * offsets, along it, so they stay beside and behind the owner whichever way it flies. The displays themselves
     * keep yaw and pitch 0.
     */
    private final class SpeedLines {

        private final List<BlockDisplay> streaks = new ArrayList<>();
        private final long spawnTick;
        private Vector heading;
        private boolean faded;

        SpeedLines(Player owner, Vector velocity) {
            this.heading = velocity.clone();
            this.spawnTick = ctx().currentTick();
            long lifetime = MAX_AIR_TICKS + 5;
            if (!reserveDisplays(STREAKS.length, lifetime)) {
                return;
            }
            Location at = chest(owner);
            for (float[] streak : STREAKS) {
                BlockDisplay display = Displays.block(ctx(), owner, at, STREAK, streakShape(streak, heading, 0.05f),
                        lifetime);
                display.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, display, streakShape(streak, heading, streak[2]),
                        STREAK_GROW_TICKS);
                streaks.add(display);
            }
        }

        /** Moves the lines along with the owner and turns them along {@code motion} (if it moved noticeably). */
        void follow(Player owner, Vector motion) {
            if (faded || streaks.isEmpty()) {
                return;
            }
            if (motion.lengthSquared() > 0.01) {
                heading = motion.clone();
            }
            Location at = chest(owner);
            // Still growing in for the first ticks (re-aiming them must not cut the growth short).
            float grown = Math.min(1f, (ctx().currentTick() - spawnTick) / (float) STREAK_GROW_TICKS);
            for (int i = 0; i < streaks.size(); i++) {
                BlockDisplay streak = streaks.get(i);
                if (streak.isValid()) {
                    Displays.moveTo(streak, at);
                    float[] spec = STREAKS[i];
                    Displays.animate(streak, streakShape(spec, heading, Math.max(0.05f, spec[2] * grown)), 1);
                }
            }
        }

        private Location chest(Player owner) {
            return owner.getLocation().add(0, 1.0, 0);
        }

        void fade() {
            if (faded) {
                return;
            }
            faded = true;
            for (BlockDisplay streak : streaks) {
                if (streak.isValid()) {
                    Displays.shrinkAway(ctx(), player(), streak, 3);
                }
            }
        }
    }

    /**
     * One speed line of {@code length} lying along {@code heading}: {@code streak} gives its offset beside (x) and
     * above (y) the owner in the flight frame, and it trails behind the owner.
     */
    private static Transformation streakShape(float[] streak, Vector heading, float length) {
        Location aim = new Location(null, 0, 0, 0);
        aim.setDirection(heading.lengthSquared() > 1.0E-8 ? heading : new Vector(0, 0, 1));
        float yaw = aim.getYaw();
        float pitch = aim.getPitch();
        // The offset of a transformation is not rotated with the shape: turn it into the flight frame by hand.
        Vector3f offset = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch))
                .transform(new Vector3f(streak[0], streak[1], -streak[2] * 0.6f));
        return Displays.transform(offset.x(), offset.y(), offset.z(), STREAK_THICKNESS, STREAK_THICKNESS, length,
                yaw, pitch, 0);
    }

    /** A burst of wind at {@code center}: damages and blows away nearby enemies. */
    private void gust(Location center) {
        World world = center.getWorld();
        double radius = params().num("radius");
        double damage = params().num("damage");
        for (LivingEntity enemy : ctx().enemiesNear(player(), center, radius)) {
            Combat.hit(ctx(), player(), enemy, damage);
            if (!Combat.isTargetable(ctx(), player(), enemy)) {
                continue;
            }
            Vector away = enemy.getLocation().toVector().subtract(center.toVector()).setY(0);
            if (away.lengthSquared() < 1.0E-4) {
                double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                away = new Vector(Math.cos(angle), 0, Math.sin(angle));
            }
            away.normalize().multiply(PUSH_HORIZONTAL).setY(PUSH_VERTICAL);
            Motion.push(enemy, away, MAX_VELOCITY);
            Location body = Fx.body(enemy);
            world.spawnParticle(Particle.SMALL_GUST, body, 1, 0.2, 0.3, 0.2, 0);
            Vfx.trail(center.clone().add(0, 0.5, 0), body, PALETTE.body(), 4, false);
            Vfx.trail(center.clone().add(0, 0.9, 0), body, PALETTE.edge(), 5, false);
        }
        gustVisual(center, radius);
        Fx.sound(center, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, 1.0f + Fx.jitter(0.1f));
    }

    /**
     * A gust, staged: a burst of wind, a ring of mint dust racing out to {@code radius}, puffs of cloud blown outward
     * along the ground and a swirl of air rising from the middle.
     */
    private void gustVisual(Location center, double radius) {
        World world = center.getWorld();
        Location puff = center.clone().add(0, 0.3, 0);
        world.spawnParticle(Particle.GUST_EMITTER_SMALL, puff, 1);
        Vfx.shockwave(ctx(), player(), center.clone().add(0, 0.15, 0), 0.4, radius, 5, Particle.DUST, WIND_DUST,
                false);
        int rays = Vfx.n(ctx(), 16);
        for (int i = 0; i < rays; i++) {
            double angle = i * (Math.PI * 2 / rays);
            // count 0: the offsets become the particle's direction and "extra" its speed.
            world.spawnParticle(Particle.CLOUD, puff.getX(), puff.getY(), puff.getZ(), 0,
                    Math.cos(angle), 0.02, Math.sin(angle), Math.max(0.15, radius * 0.08));
        }
        Vfx.helix(ctx(), center, 0.6, 1.8, 1.2, 2, 9, ThreadLocalRandom.current().nextDouble(Math.PI * 2),
                Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), false);
    }

    // ------------------------------------------------------------------ ultimate: tornado

    @Override
    public ActivationResult onUltimate() {
        Location base = player().getLocation();
        long duration = Math.max(1, params().ticks("ult_duration"));
        Tornado tornado = new Tornado(base, duration);
        Fx.sound(base, Sound.ENTITY_BREEZE_WIND_BURST, 1.5f, 0.6f);
        Fx.sound(base, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.5f, 0.5f);
        Fx.sound(base, Sound.ENTITY_BREEZE_CHARGE, 1.2f, 0.8f);
        World world = base.getWorld();
        world.spawnParticle(Particle.GUST_EMITTER_LARGE, base.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0, null, true);
        Vfx.shockwave(ctx(), player(), base.clone().add(0, 0.2, 0), 0.6, tornado.radius, 8, Particle.DUST,
                WIND_DUST, true);
        Vfx.shockwave(ctx(), player(), base.clone().add(0, 0.3, 0), 0.3, tornado.radius * 0.8, 8, Particle.CLOUD,
                null, true);
        Loop.repeat(ctx(), player(), 0, 1, duration, tornado);
        return ActivationResult.success();
    }

    /** A stationary tornado that captures enemies entering its radius and spins them around its axis. */
    private final class Tornado implements LongPredicate {

        private final Location base;
        private final World world;
        private final long duration;
        private final double radius;
        private final double lift;
        private final double top;
        private final @Nullable BlockData debris;
        private final Map<UUID, Captive> captives = new LinkedHashMap<>();
        private final List<BlockDisplay> bars = new ArrayList<>();
        private final List<Debris> chunks = new ArrayList<>();
        private int slots;

        Tornado(Location base, long duration) {
            this.base = base.clone();
            this.world = base.getWorld();
            this.duration = duration;
            this.radius = params().num("ult_radius");
            this.lift = Math.max(0, params().num("lift_height"));
            this.top = lift + 3.0;
            BlockData ground = base.clone().subtract(0, 0.5, 0).getBlock().getBlockData();
            this.debris = ground.getMaterial().isAir() ? null : ground;
            spawnBands();
            spawnDebris(ground);
        }

        @Override
        public boolean test(long tick) {
            if (tick % 2 == 0) {
                captureNewcomers();
            }
            drawFunnel(tick);
            if (tick % BAND_STEP_TICKS == 0) {
                turnBands(tick);
            }
            tumbleDebris(tick);
            if (tick % 8 == 0) {
                Fx.sound(base, Sound.ENTITY_BREEZE_WHIRL, 1.4f, 0.7f + Fx.jitter(0.1f));
            }
            Iterator<Captive> it = captives.values().iterator();
            while (it.hasNext()) {
                Captive captive = it.next();
                if (!captive.isHeld()) {
                    it.remove();
                    continue;
                }
                captive.steer();
            }
            if (tick >= duration - 1) {
                captives.values().forEach(Captive::release);
                captives.clear();
                dissipate();
                return false;
            }
            return true;
        }

        private void captureNewcomers() {
            int before = captives.size();
            for (LivingEntity enemy : ctx().enemiesNear(player(), base, radius)) {
                if (captives.containsKey(enemy.getUniqueId())) {
                    continue;
                }
                captives.put(enemy.getUniqueId(), new Captive(enemy, slots++));
                Location at = enemy.getLocation();
                world.spawnParticle(Particle.GUST, at.clone().add(0, 1, 0), 1, 0, 0, 0, 0, null, true);
                Vfx.trail(at.clone().add(0, 0.2, 0), base.clone().add(0, 1.5, 0), PALETTE.edge(), 8, true);
                Fx.sound(at, Sound.ENTITY_BREEZE_INHALE, 1.0f, 1.3f + Fx.jitter(0.1f));
            }
            if (captives.size() > before) {
                player().sendActionBar(Text.mm("<#A6F0D8>≋ 토네이도</#A6F0D8> <gray>" + captives.size()
                        + "명 휘말림</gray>"));
            }
        }

        // ---------------------------------------------------------- funnel visuals

        /** Spiralling air (white and mint motes at every level), gusts, the reach ring and dust at the base. */
        private void drawFunnel(long tick) {
            double spin = tick * 0.45;
            for (int level = 0; level < FUNNEL_LEVELS; level++) {
                double f = level / (double) (FUNNEL_LEVELS - 1);
                double y = base.getY() + f * top;
                double r = funnelRadius(f);
                double angle = spin + level * 0.6;
                for (int side = 0; side < 2; side++) {
                    double a = angle + side * Math.PI;
                    double x = base.getX() + Math.cos(a) * r;
                    double z = base.getZ() + Math.sin(a) * r;
                    if (side == 0) {
                        world.spawnParticle(Particle.CLOUD, x, y, z, 1, 0, 0, 0, 0, null, true);
                    } else {
                        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, y, z, 1, 0, 0, 0, 0,
                                PALETTE.glow(1.4f), true);
                    }
                }
            }
            if (tick % 3 == 0) {
                double f = ThreadLocalRandom.current().nextDouble();
                double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                double r = funnelRadius(f);
                world.spawnParticle(Particle.GUST, base.getX() + Math.cos(a) * r, base.getY() + f * top,
                        base.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0, null, true);
            }
            if (tick % 4 == 0) {
                Location edge = base.clone().add(0, 0.2, 0);
                Vfx.ring(ctx(), edge, radius, 24, spin * 0.3, Particle.DUST, WIND_DUST, true);
                Vfx.ring(ctx(), edge, radius - 0.2, 12, spin * 0.3 + 0.13, Particle.DUST, EDGE_DUST, true);
            }
            if (debris != null && tick % 2 == 0) {
                world.spawnParticle(Particle.BLOCK, base.clone().add(0, 0.6, 0), Vfx.n(ctx(), 3), 1.2, 0.4, 1.2, 0.1,
                        debris, true);
            }
        }

        private double funnelRadius(double heightFraction) {
            return 0.5 + heightFraction * FUNNEL_FLARE;
        }

        /** Spawns the wind bands: a polygon of thin translucent bars at each band level, starting small. */
        private void spawnBands() {
            long lifetime = duration + FUNNEL_FADE_TICKS + 5;
            if (!reserveDisplays(BAND_LEVELS.length * BAND_SIDES, lifetime)) {
                return;
            }
            for (int band = 0; band < BAND_LEVELS.length; band++) {
                Location at = flat(base).add(0, BAND_LEVELS[band] * top, 0);
                for (int side = 0; side < BAND_SIDES; side++) {
                    bars.add(Displays.block(ctx(), player(), at, BAND, barShape(band, side, 0, 0.1), lifetime));
                }
            }
        }

        /** Turns every band a step further (alternate bands the other way), bobbing slightly, grown in at first. */
        private void turnBands(long tick) {
            long next = tick + BAND_STEP_TICKS;
            double grow = Math.min(1.0, (double) next / BAND_GROW_TICKS);
            int index = 0;
            for (int band = 0; band < BAND_LEVELS.length; band++) {
                for (int side = 0; side < BAND_SIDES; side++) {
                    BlockDisplay bar = bars.get(index++);
                    if (bar.isValid()) {
                        Displays.animate(bar, barShape(band, side, next, grow), (int) BAND_STEP_TICKS);
                    }
                }
            }
        }

        /**
         * Bar {@code side} of wind band {@code band} at {@code tick}: one side of a regular polygon around the axis,
         * its radius following the funnel's flare and scaled by {@code grow}.
         */
        private Transformation barShape(int band, int side, long tick, double grow) {
            double direction = band % 2 == 0 ? 1 : -1;
            double circumradius = funnelRadius(BAND_LEVELS[band]) * grow;
            double angle = band * 0.7 + direction * tick * BAND_SPIN * (1 + band * 0.25)
                    + side * (Math.PI * 2 / BAND_SIDES);
            double apothem = circumradius * Math.cos(Math.PI / BAND_SIDES);
            float length = (float) (2 * circumradius * Math.sin(Math.PI / BAND_SIDES)) * 1.04f;
            float bob = (float) (Math.sin(tick * 0.15 + band * 1.3) * 0.25);
            // A display's yaw of `angle` (in degrees) points its long axis along the circle's tangent there.
            return Displays.transform((float) (Math.cos(angle) * apothem), bob, (float) (Math.sin(angle) * apothem),
                    BAND_THICKNESS, BAND_THICKNESS, Math.max(0.01f, length), (float) Math.toDegrees(angle), 0, 0);
        }

        /** Chunks of the ground the tornado stands on, if it is a full block, to tumble around inside. */
        private void spawnDebris(BlockData ground) {
            if (!ground.getMaterial().isOccluding()) {
                return;
            }
            long lifetime = duration + FUNNEL_FADE_TICKS + 5;
            int count = DEBRIS;
            while (count > 0 && !reserveDisplays(count, lifetime)) {
                count--;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < count; i++) {
                float size = (float) random.nextDouble(0.3, 0.5);
                BlockDisplay chunk = Displays.block(ctx(), player(), flat(base).add(0, 0.5, 0), ground,
                        Displays.scaled(0.05f), lifetime);
                chunk.setTeleportDuration(2);
                Displays.animateNextTick(ctx(), player(), chunk, Displays.scaled(size), 6);
                chunks.add(new Debris(chunk, size, i * (Math.PI * 2 / count), random.nextDouble(Math.PI * 2)));
            }
        }

        private void tumbleDebris(long tick) {
            for (Debris chunk : chunks) {
                BlockDisplay display = chunk.display();
                if (!display.isValid()) {
                    continue;
                }
                double f = 0.5 + 0.45 * Math.sin(tick * 0.06 + chunk.phase());
                double r = funnelRadius(f) * 0.8;
                double angle = chunk.angle() + tick * 0.3;
                display.teleport(flat(base).add(Math.cos(angle) * r, f * top, Math.sin(angle) * r));
                if (tick > 6 && tick % 4 == 0) {
                    float turn = tick * 25f;
                    Displays.animate(display, Displays.transform(0, 0, 0, chunk.size(), chunk.size(), chunk.size(),
                            turn, turn * 0.7f, turn * 0.4f), 4);
                }
            }
        }

        /** The tornado ends: the bands fling outward and fade, the debris drops away and a last gust bursts. */
        private void dissipate() {
            int index = 0;
            for (int band = 0; band < BAND_LEVELS.length; band++) {
                for (int side = 0; side < BAND_SIDES; side++) {
                    BlockDisplay bar = bars.get(index++);
                    if (bar.isValid()) {
                        Displays.animate(bar, barShape(band, side, duration + 4, 1.6), 4);
                        ctx().runLater(player(), 4, () -> Displays.shrinkAway(ctx(), player(), bar,
                                (int) FUNNEL_FADE_TICKS - 4));
                    }
                }
            }
            for (Debris chunk : chunks) {
                if (chunk.display().isValid()) {
                    Displays.shrinkAway(ctx(), player(), chunk.display(), (int) FUNNEL_FADE_TICKS);
                }
            }
            world.spawnParticle(Particle.GUST_EMITTER_LARGE, base.clone().add(0, 1.0, 0), 1, 0, 0, 0, 0, null, true);
            Vfx.shockwave(ctx(), player(), base.clone().add(0, 0.3, 0), 1.0, radius, 6, Particle.CLOUD, null, true);
            Fx.sound(base, Sound.ENTITY_BREEZE_SHOOT, 1.4f, 0.6f);
            Fx.sound(base, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 0.8f, 0.7f + Fx.jitter(0.05f));
        }

        /** One enemy caught in the tornado, steered along its own orbit slot. */
        private final class Captive {

            private final LivingEntity entity;
            private final double startAngle;
            private final double startRadius;
            private final double startY;
            private final double orbit;
            private final double height;
            private long age;

            Captive(LivingEntity entity, int slot) {
                this.entity = entity;
                Location at = entity.getLocation();
                double dx = at.getX() - base.getX();
                double dz = at.getZ() - base.getZ();
                this.startAngle = Math.atan2(dz, dx);
                this.startRadius = Math.hypot(dx, dz);
                this.startY = at.getY();
                this.orbit = ORBIT_BASE + (slot % 3) * ORBIT_STEP;
                this.height = 1.0 + lift + (slot % 2) * 0.8;
            }

            boolean isHeld() {
                if (!Combat.isTargetable(ctx(), player(), entity)
                        || !world.equals(entity.getWorld())) {
                    return false;
                }
                // Escaped (e.g. teleported away): let go.
                return entity.getLocation().distanceSquared(base) <= square(radius + lift + ESCAPE_MARGIN);
            }

            private Vector targetAt(long t) {
                double rise = smoothstep(Math.min(1.0, t / (double) RISE_TICKS));
                double angle = startAngle + t * SPIN_PER_TICK;
                double r = startRadius + (orbit - startRadius) * rise;
                double y = startY + (base.getY() + height - startY) * rise
                        + Math.sin(t * BOB_FREQUENCY) * BOB_AMPLITUDE * rise;
                return new Vector(base.getX() + Math.cos(angle) * r, y, base.getZ() + Math.sin(angle) * r);
            }

            void steer() {
                Vector now = targetAt(age);
                Vector next = targetAt(age + 1);
                Vector position = entity.getLocation().toVector();
                // Feed-forward along the orbit plus a gentle pull towards where the entity should be.
                Vector velocity = next.clone().subtract(now).add(next.clone().subtract(position).multiply(STEER_GAIN));
                entity.setVelocity(Motion.clamp(velocity, MAX_STEER));
                entity.setFallDistance(0);
                if (age % 3 == 0) {
                    world.spawnParticle(Particle.CLOUD, entity.getLocation(), 1, 0.2, 0.1, 0.2, 0.01, null, true);
                } else if (age % 3 == 1) {
                    Vfx.dot(entity.getLocation().add(0, 0.2, 0), Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f),
                            true);
                }
                age++;
            }

            void release() {
                if (!isHeld()) {
                    return;
                }
                Vector inward = base.toVector().subtract(entity.getLocation().toVector()).setY(0).multiply(0.12);
                entity.setVelocity(Motion.clamp(inward.setY(-SLAM_SPEED), MAX_VELOCITY));
                entity.setFallDistance(0);
                LivingEntity target = entity;
                boolean[] airborne = {false};
                Loop.repeat(ctx(), player(), 1, 1, SLAM_TIMEOUT_TICKS, tick -> {
                    if (!Combat.isTargetable(ctx(), player(), target)) {
                        return false;
                    }
                    // The slam is the designed damage; vanilla fall damage on top of it would double dip.
                    target.setFallDistance(0);
                    boolean landed = Motion.isGrounded(target) || target.isInWater();
                    if (!landed) {
                        airborne[0] = true;
                        if (tick % 2 == 0) {
                            Vfx.dot(target.getLocation().add(0, target.getHeight(), 0), Particle.CLOUD, null, true);
                        }
                    }
                    if ((landed && (airborne[0] || tick >= STUCK_LANDING_TICKS)) || tick >= SLAM_TIMEOUT_TICKS - 1) {
                        slam(target);
                        return false;
                    }
                    return true;
                });
            }
        }
    }

    /** A chunk of ground tumbling inside the tornado: its display, size, orbit angle and height phase. */
    private record Debris(BlockDisplay display, float size, double angle, double phase) {
    }

    /** The slam: the ground cracks in a ring of dust and debris under a burst of wind. */
    private void slam(LivingEntity target) {
        Location at = target.getLocation();
        World world = at.getWorld();
        Combat.hit(ctx(), player(), target, params().num("slam_damage"));
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        if (!ground.getMaterial().isAir()) {
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 24), 0.5, 0.1, 0.5, 0.15, ground, true);
            world.spawnParticle(Particle.DUST_PILLAR, at.clone().add(0, 0.1, 0), Vfx.n(ctx(), 14), 0.7, 0.1, 0.7, 0.1,
                    ground, true);
        }
        world.spawnParticle(Particle.GUST, at.clone().add(0, 0.3, 0), 1, 0, 0, 0, 0, null, true);
        world.spawnParticle(Particle.CLOUD, at, Vfx.n(ctx(), 10), 0.4, 0.1, 0.4, 0.05, null, true);
        Vfx.shockwave(ctx(), player(), at.clone().add(0, 0.15, 0), 0.3, 1.8, 4, Particle.DUST, WIND_DUST, true);
        Fx.sound(at, Sound.ITEM_MACE_SMASH_GROUND, 1.0f, 0.9f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 0.6f, 0.8f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Claims {@code count} display entities for {@code lifetime} ticks if that stays within {@link #MAX_DISPLAYS}.
     */
    private boolean reserveDisplays(int count, long lifetime) {
        if (count <= 0 || liveDisplays + count > MAX_DISPLAYS) {
            return false;
        }
        liveDisplays += count;
        ctx().runLater(player(), Math.max(1, lifetime), () -> liveDisplays -= count);
        return true;
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }

    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double square(double value) {
        return value * value;
    }
}
