package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 거인 — a big, sturdy brawler. Permanently larger with extra health and knockback resistance, leaps into the
 * air and slams down on enemies, and can grow into a giant with longer reach and heavier blows.
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
                            + "+{ult_damage_bonus}%, 넉백 면역을 얻고 한 칸 높이를 걸어서 오릅니다.")
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

    private static final Color BRONZE = Color.fromRGB(192, 138, 78);
    private static final Particle.DustOptions BRONZE_DUST = new Particle.DustOptions(BRONZE, 1.4f);
    private static final Particle.DustOptions WARNING_DUST = new Particle.DustOptions(Color.fromRGB(255, 120, 60),
            1.2f);

    private boolean leaping;
    private long fallGuardUntil = -1;
    private @Nullable GiantForm giant;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: huge body

    @Override
    public void onGrant() {
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
        }
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
        Fx.sound(start, Sound.ENTITY_RAVAGER_STEP, 1.2f, 0.6f);
        Fx.sound(start, Sound.ENTITY_GOAT_LONG_JUMP, 1.0f, 0.6f);
        BlockData ground = start.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        if (!ground.getMaterial().isAir()) {
            world.spawnParticle(Particle.BLOCK, start.clone().add(0, 0.1, 0), 20, 0.5, 0.1, 0.5, 0.15, ground);
        }
        world.spawnParticle(Particle.CLOUD, start.clone().add(0, 0.1, 0), 10, 0.4, 0.05, 0.4, 0.05);

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
                Fx.sound(now, Sound.ITEM_MACE_SMASH_AIR, 1.0f, 0.7f);
            }
            lastY[0] = now.getY();
            if (tick % 2 == 0) {
                world.spawnParticle(Particle.DUST, now.clone().add(0, 0.3, 0), 2, 0.25, 0.2, 0.25, 0, BRONZE_DUST);
                if (diving[0]) {
                    telegraph(now);
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

    /** Draws the slam radius on the ground below the diving owner so everyone sees where it will land. */
    private void telegraph(Location from) {
        World world = from.getWorld();
        RayTraceResult hit = world.rayTraceBlocks(from, new Vector(0, -1, 0), 40, FluidCollisionMode.ALWAYS, true);
        if (hit == null) {
            return;
        }
        Location ground = hit.getHitPosition().toLocation(world).add(0, 0.15, 0);
        Fx.ring(ground, params().num("slam_radius"), 20, 0, Particle.DUST, WARNING_DUST);
    }

    private void slam(Location center) {
        Player owner = player();
        World world = center.getWorld();
        double radius = Math.max(0.5, params().num("slam_radius"));
        double damage = Math.max(0, params().num("slam_damage"));
        int struck = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            Combat.hit(ctx(), player(), enemy, damage);
            struck++;
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            Vector away = enemy.getLocation().toVector().subtract(center.toVector()).setY(0);
            if (away.lengthSquared() < 1.0E-4) {
                double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                away = new Vector(Math.cos(angle), 0, Math.sin(angle));
            }
            Motion.push(enemy, away.normalize().multiply(LAUNCH_OUT).setY(LAUNCH_UP), MAX_VELOCITY);
            world.spawnParticle(Particle.CRIT, enemy.getLocation().add(0, enemy.getHeight() * 0.5, 0), 10, 0.3,
                    0.4, 0.3, 0.3);
        }

        BlockData ground = center.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        boolean solid = !ground.getMaterial().isAir();
        Location low = center.clone().add(0, 0.2, 0);
        world.spawnParticle(Particle.EXPLOSION, low, 1, 0, 0, 0, 0);
        if (solid) {
            world.spawnParticle(Particle.BLOCK, low, 40, radius * 0.35, 0.2, radius * 0.35, 0.25, ground);
        }
        Fx.sound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.4f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 0.6f);
        Loop.repeat(ctx(), player(), 0, 1, SHOCKWAVE_TICKS, tick -> {
            double r = radius * (tick + 1) / SHOCKWAVE_TICKS;
            int points = 10 + (int) (tick * 4);
            for (int i = 0; i < points; i++) {
                double angle = i * (Math.PI * 2 / points) + tick * 0.2;
                double x = center.getX() + Math.cos(angle) * r;
                double z = center.getZ() + Math.sin(angle) * r;
                if (solid) {
                    world.spawnParticle(Particle.BLOCK, x, center.getY() + 0.15, z, 1, 0.05, 0.05, 0.05, 0.1,
                            ground);
                }
                if (i % 2 == 0) {
                    world.spawnParticle(Particle.DUST, x, center.getY() + 0.25, z, 1, 0, 0, 0, 0, BRONZE_DUST);
                }
            }
            return true;
        });
        if (struck > 0) {
            owner.sendActionBar(Text.mm("<#C08A4E>대지 강타!</#C08A4E> <gray>" + struck + "명 적중</gray>"));
        }
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
        if (growth > 0 && !hasHeadroom(owner, currentScale + growth / GROW_STEPS)) {
            return ActivationResult.fail("머리 위 공간이 부족해 거대해질 수 없습니다");
        }
        GiantForm form = new GiantForm(currentScale, growth);
        giant = form;
        form.begin();
        return ActivationResult.success();
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
            Fx.sound(at, Sound.ENTITY_WARDEN_ROAR, 1.2f, 1.1f);
            Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 0.6f);
            at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, owner.getHeight() * 0.5, 0), 30, 0.6,
                    owner.getHeight() * 0.4, 0.6, 0, BRONZE_DUST);

            long duration = Math.max(1, params().ticks("ult_duration"));
            Loop.repeat(ctx(), player(), 0, 1, duration, this::tick, this::shrink);
        }

        private boolean tick(long tick) {
            Player owner = player();
            if (tick % GROW_INTERVAL == 0 && step < GROW_STEPS && growth > 0 && !blocked) {
                grow();
            }
            if (tick % FOOTSTEP_PERIOD == 0) {
                footstep(owner);
            }
            return true;
        }

        private void grow() {
            Player owner = player();
            int next = step + 1;
            if (!hasHeadroom(owner, baseScale + growth * next / GROW_STEPS)) {
                blocked = true;
                owner.sendActionBar(Text.mm("<gray>공간이 좁아 더 커지지 못했습니다</gray>"));
                return;
            }
            setStep(next);
            Location at = owner.getLocation();
            Fx.sound(at, Sound.ENTITY_RAVAGER_STEP, 1.2f, 0.5f + next * 0.08f);
            BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
            if (!ground.getMaterial().isAir()) {
                double spread = owner.getWidth() * 0.6;
                at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.1, 0), 12, spread, 0.1, spread, 0.15,
                        ground);
            }
            if (next == GROW_STEPS) {
                at.getWorld().spawnParticle(Particle.EXPLOSION, at.clone().add(0, 0.3, 0), 1, 0, 0, 0, 0);
                Fx.sound(at, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.2f, 0.6f);
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
            BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
            if (!ground.getMaterial().isAir()) {
                double spread = owner.getWidth() * 0.5;
                at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.1, 0), 8, spread, 0.05, spread, 0.1,
                        ground);
            }
            Fx.sound(at, Sound.ENTITY_RAVAGER_STEP, 0.9f, 0.55f + Fx.jitter(0.05f));
        }

        private void shrink() {
            Player owner = player();
            Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.7f);
            Loop.repeat(ctx(), player(), 0, GROW_INTERVAL, Math.max(1, step), tick -> {
                setStep(Math.max(0, step - 1));
                return step > 0;
            }, this::end);
        }

        private void end() {
            setStep(0);
            Player owner = player();
            for (NamespacedKey key : buffs) {
                ctx().removeModifier(owner, key);
            }
            buffs.clear();
            owner.getWorld().spawnParticle(Particle.CLOUD, owner.getLocation().add(0, 0.5, 0), 12, 0.4, 0.4, 0.4,
                    0.03);
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

    /** True if a player at its current position has room above its head for a body of {@code scale}. */
    private static boolean hasHeadroom(Player player, double scale) {
        Location at = player.getLocation();
        BoundingBox column = new BoundingBox(at.getX() - HEADROOM_HALF_WIDTH, at.getY() + 0.1,
                at.getZ() - HEADROOM_HALF_WIDTH, at.getX() + HEADROOM_HALF_WIDTH,
                at.getY() + STANDING_HEIGHT * scale + 0.05, at.getZ() + HEADROOM_HALF_WIDTH);
        return !player.getWorld().hasCollisionsIn(column);
    }
}
