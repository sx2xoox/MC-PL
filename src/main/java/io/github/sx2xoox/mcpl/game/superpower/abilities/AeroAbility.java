package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 바람술사 — mobility and displacement. Never takes fall damage, leaps on a gust that blasts enemies away at both
 * ends, and summons a tornado that lifts enemies, spins them around and slams them into the ground.
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

    private static final Particle.DustOptions WIND_DUST = new Particle.DustOptions(Color.fromRGB(200, 255, 236), 1.3f);

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: no fall damage

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (!event.isCancelled() && Combat.isFall(event)) {
            event.setCancelled(true);
        }
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

        boolean[] airborne = {false};
        Loop.repeat(ctx(), player(), 1, 1, MAX_AIR_TICKS, tick -> {
            Location now = owner.getLocation();
            boolean landed = Motion.isGrounded(owner) || owner.isInWater() || owner.isClimbing();
            if (!landed) {
                airborne[0] = true;
                World world = now.getWorld();
                world.spawnParticle(Particle.CLOUD, now.clone().add(0, 0.2, 0), 2, 0.15, 0.1, 0.15, 0.01);
                if (tick % 3 == 0) {
                    world.spawnParticle(Particle.SMALL_GUST, now.clone().add(0, 0.6, 0), 1, 0.2, 0.3, 0.2, 0);
                }
                return true;
            }
            if (airborne[0] || tick >= STUCK_LANDING_TICKS) {
                gust(now);
                Fx.sound(now, Sound.ENTITY_BREEZE_LAND, 1.0f, 0.9f + Fx.jitter(0.1f));
                return false;
            }
            return true;
        });
        return ActivationResult.success();
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
            enemy.setVelocity(Motion.clamp(away, MAX_VELOCITY));
        }

        Location puff = center.clone().add(0, 0.3, 0);
        world.spawnParticle(Particle.GUST_EMITTER_SMALL, puff, 1);
        int rays = 16;
        for (int i = 0; i < rays; i++) {
            double angle = i * (Math.PI * 2 / rays);
            // count 0: the offsets become the particle's direction and "extra" its speed.
            world.spawnParticle(Particle.CLOUD, puff.getX(), puff.getY(), puff.getZ(), 0,
                    Math.cos(angle), 0.02, Math.sin(angle), Math.max(0.15, radius * 0.08));
        }
        Fx.sound(center, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, 1.0f + Fx.jitter(0.1f));
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
        base.getWorld().spawnParticle(Particle.GUST_EMITTER_LARGE, base.clone().add(0, 0.5, 0), 1);
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
        private final @Nullable BlockData debris;
        private final Map<UUID, Captive> captives = new LinkedHashMap<>();
        private int slots;

        Tornado(Location base, long duration) {
            this.base = base.clone();
            this.world = base.getWorld();
            this.duration = duration;
            this.radius = params().num("ult_radius");
            this.lift = Math.max(0, params().num("lift_height"));
            BlockData ground = base.clone().subtract(0, 0.5, 0).getBlock().getBlockData();
            this.debris = ground.getMaterial().isAir() ? null : ground;
        }

        @Override
        public boolean test(long tick) {
            if (tick % 2 == 0) {
                captureNewcomers();
            }
            drawFunnel(tick);
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
                Fx.sound(base, Sound.ENTITY_BREEZE_SHOOT, 1.4f, 0.6f);
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
                world.spawnParticle(Particle.GUST, at.clone().add(0, 1, 0), 1);
                Fx.sound(at, Sound.ENTITY_BREEZE_INHALE, 1.0f, 1.3f + Fx.jitter(0.1f));
            }
            if (captives.size() > before) {
                player().sendActionBar(Text.mm("<#A6F0D8>≋ 토네이도</#A6F0D8> <gray>" + captives.size()
                        + "명 휘말림</gray>"));
            }
        }

        private void drawFunnel(long tick) {
            double top = lift + 3.0;
            double spin = tick * 0.45;
            for (int level = 0; level < FUNNEL_LEVELS; level++) {
                double f = level / (double) (FUNNEL_LEVELS - 1);
                double y = base.getY() + f * top;
                double r = 0.5 + f * FUNNEL_FLARE;
                double angle = spin + level * 0.6;
                for (int side = 0; side < 2; side++) {
                    double a = angle + side * Math.PI;
                    world.spawnParticle(Particle.CLOUD, base.getX() + Math.cos(a) * r, y,
                            base.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
                }
            }
            if (tick % 3 == 0) {
                double f = ThreadLocalRandom.current().nextDouble();
                double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                double r = 0.5 + f * FUNNEL_FLARE;
                world.spawnParticle(Particle.GUST, base.getX() + Math.cos(a) * r, base.getY() + f * top,
                        base.getZ() + Math.sin(a) * r, 1);
            }
            if (tick % 5 == 0) {
                int points = 28;
                for (int i = 0; i < points; i++) {
                    double a = i * (Math.PI * 2 / points) + spin * 0.3;
                    world.spawnParticle(Particle.DUST, base.getX() + Math.cos(a) * radius, base.getY() + 0.2,
                            base.getZ() + Math.sin(a) * radius, 1, 0, 0.1, 0, 0, WIND_DUST);
                }
            }
            if (debris != null && tick % 2 == 0) {
                world.spawnParticle(Particle.BLOCK, base.clone().add(0, 0.6, 0), 3, 1.2, 0.4, 1.2, 0.1, debris);
            }
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
                    world.spawnParticle(Particle.CLOUD, entity.getLocation(), 1, 0.2, 0.1, 0.2, 0.01);
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

    private void slam(LivingEntity target) {
        Location at = target.getLocation();
        World world = at.getWorld();
        Combat.hit(ctx(), player(), target, params().num("slam_damage"));
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        if (!ground.getMaterial().isAir()) {
            world.spawnParticle(Particle.BLOCK, at, 24, 0.5, 0.1, 0.5, 0.15, ground);
        }
        world.spawnParticle(Particle.GUST, at.clone().add(0, 0.3, 0), 1);
        world.spawnParticle(Particle.CLOUD, at, 10, 0.4, 0.1, 0.4, 0.05);
        Fx.sound(at, Sound.ITEM_MACE_SMASH_GROUND, 1.0f, 0.9f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ helpers

    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double square(double value) {
        return value * value;
    }
}
