package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 거인 — a big, sturdy brawler. Permanently larger with extra health and knockback resistance, leaps into the
 * air and slams down on enemies, and can grow into a giant with longer reach and heavier blows.
 *
 * <p>Look: earth and sand. Hits make the titan plant its feet in a puff of dust. The slam is announced by a pale
 * gold ring on the landing spot, then rocks of the ground itself pop up in a travelling ring and sink back while
 * dust drifts down. Growing whips up a dust storm; the giant stomps with dust pillars at every step.
 */
public final class TitanAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("titan", "거인", Material.ANVIL)
            .color(TextColor.color(0xC08A4E))
            .difficulty(1)
            .passive("거구",
                    "몸이 {scale}배 크고 최대 체력이 {bonus_health} 많으며 넉백을 {knockback_resistance}% 덜 받습니다. "
                            + "대신 이동 속도가 {speed_penalty}% 느립니다.")
            .active("대지 강타",
                    "약 {leap_height}블록 높이로 뛰어올랐다가 내리꽂힙니다. 착지 지점 반경 {slam_radius}블록의 적에게 "
                            + "{slam_damage} 피해를 주고 띄우며, 자신은 낙하 피해를 받지 않습니다.",
                    9)
            .ultimate("거대화",
                    "{ult_duration}초간 {ult_scale}배 크기의 거인이 되어 공격 사거리 +{ult_reach}블록, 근접 피해 "
                            + "+{ult_damage_bonus}%, 넉백 면역(끌어당기기·속박 제외)을 얻고 한 칸 높이를 걸어서 오릅니다.")
            .param("scale", 1.3)
            .param("bonus_health", 6)
            .param("knockback_resistance", 50)
            .param("speed_penalty", 5)
            .param("leap_height", 5)
            .param("leap_distance", 5)
            .param("slam_radius", 4)
            .param("slam_damage", 5)
            .param("ult_duration", 10)
            .param("ult_scale", 2)
            .param("ult_reach", 2)
            .param("ult_damage_bonus", 40)
            .build();

    private static final double TAU = Math.PI * 2;

    // Leap physics (vanilla player: gravity 0.08/tick, vertical drag 0.98, horizontal air drag 0.91)
    private static final double GRAVITY = 0.08;
    /** Extra launch speed that makes up for vertical drag so the apex is close to {@code leap_height}. */
    private static final double DRAG_COMPENSATION = 1.1;
    /** Horizontal blocks covered during the whole leap per 1.0 of horizontal launch speed. */
    private static final double DISTANCE_PER_SPEED = 7.5;
    private static final double MAX_LAUNCH = 2.5;
    private static final double DIVE_SPEED = 1.5;
    private static final double DIVE_CARRY = 0.5;
    /** The leap counts as past its apex once it climbs less than this per tick. */
    private static final double APEX_EPSILON = 0.04;
    private static final long MIN_RISE_TICKS = 4;
    private static final long STUCK_LANDING_TICKS = 10;
    private static final long MAX_AIR_TICKS = 100;
    /** Fall damage stays cancelled this long after the slam (late landing packets, small bounces). */
    private static final long FALL_GRACE_TICKS = 10;
    private static final double LAUNCH_UP = 0.75;
    private static final double LAUNCH_OUT = 0.45;
    private static final double MAX_VELOCITY = 2.5;
    private static final int SHOCKWAVE_TICKS = 5;

    // Giant form
    private static final int GROW_STEPS = 5;
    private static final long GROW_INTERVAL = 2;
    private static final double GIANT_STEP_HEIGHT_BONUS = 0.5;
    private static final long FOOTSTEP_PERIOD = 8;
    private static final double STANDING_HEIGHT = 1.8;
    /** Half-width of the column checked for headroom: only the space above the head (and eyes) matters. */
    private static final double HEADROOM_HALF_WIDTH = 0.25;

    // Visuals
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE082, 0xA1887F, 0x5D4037);
    private static final Particle.DustOptions WARNING_DUST = new Particle.DustOptions(PALETTE.core(), 1.4f);
    /** Minimum ticks between two "planted feet" feedbacks when the titan is hit. */
    private static final long STANCE_FEEDBACK_COOLDOWN = 10;
    /** Rocks of the slam's rock wave, at most. */
    private static final int SLAM_ROCKS = 20;
    /** Rocks of the rock wave when the giant reaches full size, at most. */
    private static final int GROWN_ROCKS = 12;
    private static final int ROCK_RISE_TICKS = 3;
    private static final long ROCK_HOLD_TICKS = 8;
    private static final int ROCK_SINK_TICKS = 8;
    /** How far above and below a rock's spot the ground is searched. */
    private static final double ROCK_GROUND_SEARCH = 1.5;
    private static final long DUST_FALL_TICKS = 14;
    /** The dust storm while growing lasts a little longer than the growth itself. */
    private static final long STORM_TICKS = GROW_STEPS * GROW_INTERVAL + 6;
    private static final long GIANT_DUST_PERIOD = 4;

    private boolean leaping;
    private long fallGuardUntil = -1;
    private long lastStance = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
    /**
     * True once the match granted this ability. The Gambler borrows the ultimate on a fresh instance that never
     * gets {@link #onGrant()} nor the suffocation guard of {@link #onTakeDamage}.
     */
    private boolean granted;
    private @Nullable GiantForm giant;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: huge body

    @Override
    public void onGrant() {
        granted = true;
        Player owner = player();
        double scale = params().num("scale");
        if (scale > 0 && Math.abs(scale - 1.0) > 1.0E-3) {
            ctx().addModifier(owner, owner, Attribute.SCALE, scale - 1.0, Operation.ADD_NUMBER, 0);
        }
        double bonusHealth = params().num("bonus_health");
        if (Math.abs(bonusHealth) > 1.0E-3) {
            ctx().addModifier(owner, owner, Attribute.MAX_HEALTH, bonusHealth, Operation.ADD_NUMBER, 0);
            if (bonusHealth > 0) {
                ctx().heal(owner, bonusHealth);
            }
        }
        double resistance = params().num("knockback_resistance") / 100.0;
        if (resistance > 0) {
            ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, Math.min(1.0, resistance),
                    Operation.ADD_NUMBER, 0);
        }
        double penalty = params().num("speed_penalty") / 100.0;
        if (Math.abs(penalty) > 1.0E-3) {
            ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -Math.min(0.9, penalty),
                    Operation.MULTIPLY_SCALAR_1, 0);
        }
    }

    @Override
    public void onRevoke() {
        leaping = false;
        fallGuardUntil = -1;
        giant = null;
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        // A bigger body pokes its head into low ceilings; never let that suffocate the owner.
        if (event.getCause() == DamageCause.SUFFOCATION) {
            event.setCancelled(true);
            return;
        }
        if ((leaping || ctx().currentTick() <= fallGuardUntil) && Combat.isFall(event)) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && event.getDamage() > 0) {
            stance(byEntity.getDamager());
        }
    }

    /** The titan plants its feet against a hit: an arc of dust on the side it came from and a dull thud. */
    private void stance(Entity damager) {
        long now = ctx().currentTick();
        if (now - lastStance < STANCE_FEEDBACK_COOLDOWN) {
            return;
        }
        lastStance = now;
        Player owner = player();
        Location feet = owner.getLocation().add(0, 0.1, 0);
        double dx = damager.getLocation().getX() - feet.getX();
        double dz = damager.getLocation().getZ() - feet.getZ();
        float yaw = dx * dx + dz * dz < 1.0E-6 ? feet.getYaw() : (float) Math.toDegrees(Math.atan2(-dx, dz));
        double radius = owner.getWidth() * 0.5 + 0.25;
        Vfx.arc(ctx(), feet, radius, yaw, 160, 9, Particle.DUST, PALETTE.dust(1.1f), false);
        BlockData ground = groundUnder(owner.getLocation());
        if (ground != null) {
            feet.getWorld().spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 5), radius * 0.5, 0.02, radius * 0.5,
                    0.05, ground);
        }
        Fx.sound(feet, Sound.ENTITY_RAVAGER_STEP, 0.5f, 0.8f + Fx.jitter(0.08f));
    }

    // ------------------------------------------------------------------ active: ground slam

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 도약할 수 없습니다");
        }
        if (leaping) {
            return ActivationResult.fail("이미 도약 중입니다");
        }
        double height = Math.max(0.5, params().num("leap_height"));
        double up = Math.min(MAX_LAUNCH, Math.sqrt(2 * GRAVITY * height) * DRAG_COMPENSATION);
        double forwardSpeed = Math.max(0, params().num("leap_distance")) / DISTANCE_PER_SPEED;
        // Unnormalised: looking down shortens the leap, looking straight down slams in place.
        Vector forward = owner.getLocation().getDirection().setY(0).multiply(forwardSpeed);
        // The slam must land inside the arena: a leap towards the border is shortened.
        double travel = forward.length() * DISTANCE_PER_SPEED;
        double reach = Spots.arenaReach(ctx(), owner.getLocation(), forward, travel);
        if (travel > 1.0E-3 && reach < travel) {
            forward.multiply(reach / travel);
        }
        owner.setVelocity(Motion.clamp(forward.clone().setY(up), MAX_LAUNCH));
        owner.setFallDistance(0);
        leaping = true;

        Location start = owner.getLocation();
        World world = start.getWorld();
        leapStart(start);

        double[] lastY = {start.getY()};
        int[] flatTicks = {0};
        boolean[] airborne = {false};
        boolean[] diving = {false};
        Loop.repeat(ctx(), player(), 1, 1, MAX_AIR_TICKS, tick -> {
            Location now = owner.getLocation();
            owner.setFallDistance(0);
            boolean landed = Motion.isGrounded(owner) || owner.isInWater() || owner.isInLava() || owner.isClimbing();
            if (!landed) {
                airborne[0] = true;
            } else if (airborne[0] || tick >= STUCK_LANDING_TICKS) {
                return false;
            }
            // Two flat ticks in a row: one alone may just be a late movement packet.
            flatTicks[0] = now.getY() - lastY[0] < APEX_EPSILON ? flatTicks[0] + 1 : 0;
            if (!diving[0] && airborne[0] && tick >= MIN_RISE_TICKS && flatTicks[0] >= 2) {
                diving[0] = true;
                Vector carry = forward.clone().multiply(DIVE_CARRY);
                owner.setVelocity(Motion.clamp(carry.setY(-DIVE_SPEED), MAX_LAUNCH));
                Fx.sound(now, Sound.ITEM_MACE_SMASH_AIR, 1.0f, 0.7f + Fx.jitter(0.05f));
                Fx.sound(now, Sound.ENTITY_BREEZE_JUMP, 0.6f, 0.5f);
            }
            lastY[0] = now.getY();
            if (diving[0]) {
                telegraph(now, tick, true);
                diveStreaks(now);
            } else if (tick % 2 == 0) {
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, now.clone().add(0, 0.3, 0), Vfx.n(ctx(), 2),
                        0.25, 0.2, 0.25, 0, PALETTE.fade(1.4f));
                if (airborne[0]) {
                    telegraph(now, tick, false);
                }
            }
            return true;
        }, () -> {
            leaping = false;
            fallGuardUntil = ctx().currentTick() + FALL_GRACE_TICKS;
            owner.setFallDistance(0);
            slam(owner.getLocation());
        });
        return ActivationResult.success();
    }

    /** Take-off: dust pillars burst from the ground around the feet, with a ring of dust clouds. */
    private void leapStart(Location start) {
        World world = start.getWorld();
        Fx.sound(start, Sound.ENTITY_RAVAGER_STEP, 1.2f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(start, Sound.ENTITY_GOAT_LONG_JUMP, 1.0f, 0.6f);
        Fx.sound(start, Sound.ENTITY_BREEZE_JUMP, 0.7f, 0.6f + Fx.jitter(0.05f));
        Location feet = start.clone().add(0, 0.1, 0);
        BlockData ground = groundUnder(start);
        if (ground != null) {
            Vfx.ring(ctx(), feet, 0.8, 7, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_PILLAR, ground,
                    false);
            world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 14), 0.5, 0.1, 0.5, 0.15, ground);
        }
        Vfx.ring(ctx(), feet, 0.65, 10, 0, Particle.CLOUD, null, false);
    }

    /**
     * Marks the slam area on the ground below the owner so everyone sees where it will land: while rising, a faint
     * dust ring follows the titan like a shadow; once it dives, a turning pale gold ring at the slam radius and a
     * counter-turning inner ring, visible from afar.
     */
    private void telegraph(Location from, long tick, boolean diving) {
        World world = from.getWorld();
        RayTraceResult hit = world.rayTraceBlocks(from, new Vector(0, -1, 0), 40, FluidCollisionMode.ALWAYS, true);
        if (hit == null) {
            return;
        }
        Location ground = hit.getHitPosition().toLocation(world).add(0, 0.12, 0);
        double radius = Math.max(0.5, params().num("slam_radius"));
        if (!diving) {
            Vfx.ring(ctx(), ground, radius, (int) Math.max(10, radius * 3), tick * 0.1, Particle.DUST,
                    PALETTE.dust(0.9f), false);
            return;
        }
        Vfx.ring(ctx(), ground, radius, (int) Math.max(16, radius * 6), tick * 0.25, Particle.DUST, WARNING_DUST,
                true);
        Vfx.ring(ctx(), ground, radius * 0.5, (int) Math.max(8, radius * 3), -tick * 0.25,
                Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), true);
    }

    /** Dust streaks shooting up past the diving owner. */
    private void diveStreaks(Location at) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double height = player().getHeight();
        int streaks = Vfx.n(ctx(), 2);
        for (int i = 0; i < streaks; i++) {
            Location from = at.clone().add(random.nextDouble(-0.5, 0.5), random.nextDouble(0, height),
                    random.nextDouble(-0.5, 0.5));
            Vfx.trail(from, from.clone().add(0, 2.4, 0), i == 0 ? PALETTE.core() : PALETTE.body(), 5, false);
        }
    }

    private void slam(Location center) {
        Player owner = player();
        World world = center.getWorld();
        double radius = Math.max(0.5, params().num("slam_radius"));
        double damage = Math.max(0, params().num("slam_damage"));
        BlockData ground = groundUnder(center);
        int struck = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            Combat.hit(ctx(), player(), enemy, damage);
            struck++;
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            Vector away = enemy.getLocation().toVector().subtract(center.toVector()).setY(0);
            if (away.lengthSquared() < 1.0E-4) {
                double angle = ThreadLocalRandom.current().nextDouble(TAU);
                away = new Vector(Math.cos(angle), 0, Math.sin(angle));
            }
            Motion.push(enemy, away.normalize().multiply(LAUNCH_OUT).setY(LAUNCH_UP), MAX_VELOCITY);
            world.spawnParticle(Particle.CRIT, Fx.body(enemy), Vfx.n(ctx(), 10), 0.3, 0.4, 0.3, 0.3);
            if (ground != null) {
                world.spawnParticle(Particle.DUST_PILLAR, enemy.getLocation().add(0, 0.1, 0), Vfx.n(ctx(), 4), 0.2,
                        0, 0.2, 0, ground);
            }
        }
        slamImpact(center, radius, ground);
        if (struck > 0) {
            owner.sendActionBar(Text.mm("<#C08A4E>대지 강타!</#C08A4E> <gray>" + struck + "명 적중</gray>"));
        }
    }

    /**
     * The slam lands: a flash and a blast, a ring of rocks torn from the ground travelling outwards, a dust
     * shockwave on the ground and dust drifting down afterwards.
     */
    private void slamImpact(Location center, double radius, @Nullable BlockData ground) {
        Player owner = player();
        World world = center.getWorld();
        Location low = center.clone().add(0, 0.2, 0);
        world.spawnParticle(Particle.EXPLOSION, low, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.FLASH, low, 1, 0, 0, 0, 0, PALETTE.core());
        Fx.sound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.4f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 1.0f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.BLOCK_DEEPSLATE_BREAK, 1.0f, 0.6f);
        if (ground != null) {
            world.spawnParticle(Particle.BLOCK, low, Vfx.n(ctx(), 30), radius * 0.3, 0.2, radius * 0.3, 0.25, ground);
            Vfx.ring(ctx(), low, 1.0, 8, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_PILLAR, ground,
                    false);
            rockWave(center, radius, ground, 3, SLAM_ROCKS);
        }
        Loop.repeat(ctx(), owner, 0, 1, SHOCKWAVE_TICKS, tick -> {
            double r = radius * (tick + 1) / SHOCKWAVE_TICKS;
            Location ring = center.clone().add(0, 0.2, 0);
            Vfx.ring(ctx(), ring, r, (int) Math.max(10, r * 7), tick * 0.2, Particle.DUST, PALETTE.dust(1.5f), false);
            Vfx.ring(ctx(), ring, r * 0.92, (int) Math.max(6, r * 3), -tick * 0.2, Particle.CLOUD, null, false);
            if (ground != null) {
                Vfx.ring(ctx(), ring, r, (int) Math.max(6, r * 3), tick * 0.35, Particle.BLOCK, ground, false);
            }
            return true;
        });
        // Afterglow: dust settling over the slam area.
        Loop.repeat(ctx(), owner, SHOCKWAVE_TICKS, 1, DUST_FALL_TICKS, tick -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int motes = Vfx.n(ctx(), 3);
            for (int i = 0; i < motes; i++) {
                Location at = Vfx.scatter(center, radius).add(0, random.nextDouble(1.2, 2.6), 0);
                if (ground != null) {
                    Vfx.dot(at, Particle.FALLING_DUST, ground, false);
                } else {
                    Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.2f), false);
                }
            }
            return true;
        });
    }

    /**
     * Rocks made of the ground itself pop up in {@code rows} rings out to {@code radius}, one ring per tick, then
     * sink back into the ground. About {@code maxRocks} rocks (at least three per ring), spread over the rings by
     * their circumference.
     */
    private void rockWave(Location center, double radius, BlockData fallback, int rows, int maxRocks) {
        Player owner = player();
        double total = 0;
        for (int row = 0; row < rows; row++) {
            total += rowRadius(radius, row, rows);
        }
        for (int row = 0; row < rows; row++) {
            double r = rowRadius(radius, row, rows);
            int count = Math.max(3, (int) (maxRocks * r / total));
            ctx().runLater(owner, row, () -> rockRow(center, r, count, fallback));
        }
    }

    private static double rowRadius(double radius, int row, int rows) {
        return radius * (0.35 + 0.65 * (row + 1) / rows);
    }

    private void rockRow(Location center, double radius, int count, BlockData fallback) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        World world = center.getWorld();
        double phase = random.nextDouble(TAU);
        for (int i = 0; i < count; i++) {
            double angle = phase + i * TAU / count + random.nextDouble(-0.15, 0.15);
            Location probe = center.clone().add(Math.cos(angle) * radius, ROCK_GROUND_SEARCH, Math.sin(angle) * radius);
            RayTraceResult hit = world.rayTraceBlocks(probe, new Vector(0, -1, 0), ROCK_GROUND_SEARCH * 2,
                    FluidCollisionMode.NEVER, true);
            if (hit == null || hit.getHitBlock() == null) {
                continue;
            }
            BlockData data = hit.getHitBlock().getType().isOccluding() ? hit.getHitBlock().getBlockData() : fallback;
            Location spot = hit.getHitPosition().toLocation(world);
            rock(spot, data, (float) random.nextDouble(0.35, 0.65));
        }
    }

    /** One rock: shoots up out of the ground tilted, holds, then sinks back in. */
    private void rock(Location spot, BlockData data, float size) {
        Player owner = player();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        spot.setYaw(0);
        spot.setPitch(0);
        float yaw = (float) random.nextDouble(360);
        float pitch = (float) random.nextDouble(-25, 25);
        float roll = (float) random.nextDouble(-25, 25);
        Transformation buried = Displays.transform(0, -size * 0.6f, 0, size * 0.5f, size * 0.5f, size * 0.5f, yaw,
                pitch, roll);
        Transformation raised = Displays.transform(0, size * 0.35f, 0, size, size, size, yaw, pitch, roll);
        Transformation sunk = Displays.transform(0, -size * 0.9f, 0, size * 0.8f, size * 0.8f, size * 0.8f, yaw,
                pitch, roll);
        BlockDisplay rock = Displays.block(ctx(), owner, spot, data, buried,
                ROCK_HOLD_TICKS + ROCK_SINK_TICKS + 2);
        // Torn-up ground, not magic: lit like the ground around it.
        rock.setBrightness(null);
        Displays.animateNextTick(ctx(), owner, rock, raised, ROCK_RISE_TICKS);
        ctx().runLater(owner, ROCK_HOLD_TICKS, () -> Displays.animate(rock, sunk, ROCK_SINK_TICKS));
    }

    // ------------------------------------------------------------------ ultimate: giant form

    @Override
    public ActivationResult onUltimate() {
        if (giant != null) {
            return ActivationResult.fail("이미 거대화한 상태입니다");
        }
        Player owner = player();
        AttributeInstance scaleAttribute = owner.getAttribute(Attribute.SCALE);
        double currentScale = scaleAttribute == null ? 1.0 : scaleAttribute.getValue();
        double growth = Math.max(0, params().num("ult_scale") - currentScale);
        if (growth > 0) {
            double firstStep = currentScale + growth / GROW_STEPS;
            if (!hasHeadroom(owner, firstStep)) {
                return ActivationResult.fail("머리 위 공간이 부족해 거대해질 수 없습니다");
            }
            if (!bodyFits(owner, firstStep)) {
                return ActivationResult.fail("주변 공간이 부족해 거대해질 수 없습니다");
            }
        }
        GiantForm form = new GiantForm(currentScale, growth);
        giant = form;
        form.begin();
        return ActivationResult.success();
    }

    /**
     * True if the whole body fits at {@code scale}. Only checked for a borrowed copy: the real titan may grow while
     * touching a wall because its passive cancels suffocation, a Gambler borrowing the ultimate has no such guard.
     */
    private boolean bodyFits(Player owner, double scale) {
        return granted || Spots.isFree(owner.getWorld(), owner.getLocation(), Body.PLAYER.width() * scale,
                Body.PLAYER.height() * scale);
    }

    /** One activation of the giant form: grows in steps, stomps around, then shrinks back and cleans up. */
    private final class GiantForm {

        private final double baseScale;
        private final double growth;
        private final List<NamespacedKey> buffs = new ArrayList<>();
        private @Nullable NamespacedKey scaleKey;
        private int step;
        private boolean blocked;
        private @Nullable Location lastFootstep;

        GiantForm(double baseScale, double growth) {
            this.baseScale = baseScale;
            this.growth = growth;
        }

        void begin() {
            Player owner = player();
            double reach = params().num("ult_reach");
            if (reach != 0) {
                buffs.add(ctx().addModifier(owner, owner, Attribute.ENTITY_INTERACTION_RANGE, reach,
                        Operation.ADD_NUMBER, 0));
            }
            double damageBonus = params().num("ult_damage_bonus") / 100.0;
            if (damageBonus != 0) {
                buffs.add(ctx().addModifier(owner, owner, Attribute.ATTACK_DAMAGE, Math.max(-0.9, damageBonus),
                        Operation.MULTIPLY_SCALAR_1, 0));
            }
            buffs.add(ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, 1.0, Operation.ADD_NUMBER, 0));
            buffs.add(ctx().addModifier(owner, owner, Attribute.STEP_HEIGHT, GIANT_STEP_HEIGHT_BONUS,
                    Operation.ADD_NUMBER, 0));

            Location at = owner.getLocation();
            Fx.sound(at, Sound.ENTITY_WARDEN_ROAR, 1.2f, 1.1f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 0.6f);
            Fx.sound(at, Sound.ENTITY_IRON_GOLEM_REPAIR, 0.9f, 0.5f + Fx.jitter(0.05f));
            dustStorm(owner);

            long duration = Math.max(1, params().ticks("ult_duration"));
            Loop.repeat(ctx(), player(), 0, 1, duration, this::tick, this::shrink);
        }

        /** A dust storm swirling up around the titan while it grows: three rising, widening spiral arms. */
        private void dustStorm(Player owner) {
            Loop.repeat(ctx(), owner, 0, 1, STORM_TICKS, tick -> {
                Location at = owner.getLocation();
                World world = at.getWorld();
                double scale = Body.scaleOf(owner);
                double height = owner.getHeight();
                int perArm = Vfx.n(ctx(), 5);
                for (int arm = 0; arm < 3; arm++) {
                    for (int k = 0; k < perArm; k++) {
                        double t = (k + (tick * 0.3) % 1.0) / perArm;
                        double angle = tick * 0.45 + arm * TAU / 3 + t * 2.5;
                        double r = (0.5 + 0.9 * t) * scale * 0.8;
                        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at.getX() + Math.cos(angle) * r,
                                at.getY() + t * height * 1.1, at.getZ() + Math.sin(angle) * r, 1, 0, 0, 0, 0,
                                (arm + k) % 2 == 0 ? PALETTE.fade((float) (1.7 - 0.6 * t))
                                        : PALETTE.glow((float) (1.3 - 0.4 * t)), true);
                    }
                }
                BlockData ground = groundUnder(at);
                if (ground != null && tick % 2 == 0) {
                    Vfx.ring(ctx(), at.clone().add(0, 0.15, 0), scale * 0.6, 5, tick * 0.4, Particle.BLOCK, ground,
                            true);
                }
                world.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.2, 0), Vfx.n(ctx(), 2), scale * 0.3, 0.05,
                        scale * 0.3, 0.03, null, true);
                return true;
            });
        }

        private boolean tick(long tick) {
            Player owner = player();
            if (tick % GROW_INTERVAL == 0 && step < GROW_STEPS && growth > 0 && !blocked) {
                grow();
            }
            if (tick % FOOTSTEP_PERIOD == 0) {
                footstep(owner);
            }
            if (step > 0 && tick % GIANT_DUST_PERIOD == 0) {
                giantDust(owner, tick);
            }
            return true;
        }

        private void grow() {
            Player owner = player();
            int next = step + 1;
            double nextScale = baseScale + growth * next / GROW_STEPS;
            if (!hasHeadroom(owner, nextScale) || !bodyFits(owner, nextScale)) {
                blocked = true;
                owner.sendActionBar(Text.mm("<gray>공간이 좁아 더 커지지 못했습니다</gray>"));
                return;
            }
            setStep(next);
            Location at = owner.getLocation();
            Fx.sound(at, Sound.ENTITY_RAVAGER_STEP, 1.2f, 0.5f + next * 0.08f + Fx.jitter(0.03f));
            BlockData ground = groundUnder(at);
            if (ground != null) {
                Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), owner.getWidth() * 0.6, 6,
                        ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_PILLAR, ground, true);
            }
            if (next == GROW_STEPS) {
                grownImpact(owner, at, ground);
            }
        }

        /** Full size: a stomp with a blast, a dust shockwave and a small rock wave. */
        private void grownImpact(Player owner, Location at, @Nullable BlockData ground) {
            at.getWorld().spawnParticle(Particle.EXPLOSION, at.clone().add(0, 0.3, 0), 1, 0, 0, 0, 0, null, true);
            Fx.sound(at, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.2f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_DEEPSLATE_BREAK, 1.0f, 0.5f);
            Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.2, 0), 0.8, 3.4, 6, Particle.DUST, PALETTE.dust(1.6f),
                    true);
            if (ground != null) {
                rockWave(at, 2.8, ground, 2, GROWN_ROCKS);
            }
        }

        private void footstep(Player owner) {
            Location at = owner.getLocation();
            Location previous = lastFootstep;
            lastFootstep = at;
            if (previous == null || !previous.getWorld().equals(at.getWorld()) || step == 0
                    || Math.hypot(at.getX() - previous.getX(), at.getZ() - previous.getZ()) < 0.8
                    || !Motion.isGrounded(owner)) {
                return;
            }
            BlockData ground = groundUnder(at);
            if (ground != null) {
                double spread = owner.getWidth() * 0.5;
                Location feet = at.clone().add(0, 0.1, 0);
                at.getWorld().spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 8), spread, 0.05, spread, 0.1, ground);
                Vfx.ring(ctx(), feet, spread, 3, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_PILLAR,
                        ground, false);
            }
            Fx.sound(at, Sound.ENTITY_RAVAGER_STEP, 0.9f, 0.55f + Fx.jitter(0.05f));
        }

        /** Dust motes swirling low around the giant's feet. */
        private void giantDust(Player owner, long tick) {
            Location at = owner.getLocation();
            double radius = owner.getWidth() * 0.75;
            for (int i = 0; i < 2; i++) {
                double angle = tick * 0.3 + i * Math.PI;
                Vfx.dot(at.clone().add(Math.cos(angle) * radius, 0.25 + 0.2 * i, Math.sin(angle) * radius),
                        Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.3f), false);
            }
        }

        private void shrink() {
            Player owner = player();
            Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.7f);
            Fx.sound(owner.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 1.0f, 0.6f + Fx.jitter(0.05f));
            Loop.repeat(ctx(), player(), 0, GROW_INTERVAL, Math.max(1, step), tick -> {
                setStep(Math.max(0, step - 1));
                shrinkDust(owner);
                return step > 0;
            }, this::end);
        }

        /** Dust drawn back in from a ring around the shrinking titan. */
        private void shrinkDust(Player owner) {
            Location at = owner.getLocation();
            Location chest = at.clone().add(0, owner.getHeight() * 0.5, 0);
            double radius = owner.getWidth() * 1.2 + 0.5;
            int motes = Vfx.n(ctx(), 6);
            double phase = ThreadLocalRandom.current().nextDouble(TAU);
            for (int i = 0; i < motes; i++) {
                double angle = phase + i * TAU / motes;
                Location from = at.clone().add(Math.cos(angle) * radius, 0.2, Math.sin(angle) * radius);
                Vfx.trail(from, chest, i % 2 == 0 ? PALETTE.body() : PALETTE.core(), 6, false);
            }
        }

        private void end() {
            setStep(0);
            Player owner = player();
            for (NamespacedKey key : buffs) {
                ctx().removeModifier(owner, key);
            }
            buffs.clear();
            Location at = owner.getLocation();
            at.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 0.5, 0), Vfx.n(ctx(), 12), 0.4, 0.4, 0.4,
                    0.03);
            Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), 0.8, 10, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.1f),
                    false);
            if (giant == this) {
                giant = null;
            }
        }

        private void setStep(int value) {
            Player owner = player();
            NamespacedKey previous = scaleKey;
            if (previous != null) {
                ctx().removeModifier(owner, previous);
                scaleKey = null;
            }
            step = value;
            if (value > 0) {
                scaleKey = ctx().addModifier(owner, owner, Attribute.SCALE, growth * value / GROW_STEPS,
                        Operation.ADD_NUMBER, 0);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** True if a player at its current position has room above its head for a body of {@code scale}. */
    private static boolean hasHeadroom(Player player, double scale) {
        Location at = player.getLocation();
        BoundingBox column = new BoundingBox(at.getX() - HEADROOM_HALF_WIDTH, at.getY() + 0.1,
                at.getZ() - HEADROOM_HALF_WIDTH, at.getX() + HEADROOM_HALF_WIDTH,
                at.getY() + STANDING_HEIGHT * scale + 0.05, at.getZ() + HEADROOM_HALF_WIDTH);
        return !player.getWorld().hasCollisionsIn(column);
    }

    /** The solid block right under {@code at}, null over air, fluids and plants. */
    private static @Nullable BlockData groundUnder(Location at) {
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        return ground.getMaterial().isSolid() ? ground : null;
    }
}
