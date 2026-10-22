package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
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
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 폭탄마 — area denial with fused explosives. Shrugs off blasts (and rides the blast of its own grenade), lobs a
 * ticking grenade and calls a carpet of falling bombs across the aimed spot.
 *
 * <p>No real explosion ever happens: the grenade is a primed TNT entity with zero yield that is removed right before
 * its fuse would run out, the bombs are block displays, and every blast is applied explicitly to enemies only.
 */
public final class BomberAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("bomber", "폭탄마", Material.TNT)
            .color(TextColor.color(0xFF7A2E))
            .difficulty(2)
            .passive("방폭복",
                    "폭발 피해를 {blast_resist}% 덜 받습니다. 내 수류탄 폭발에 휘말리면 피해 없이 높이 날아오르며"
                            + "(폭탄 점프) 착지 피해도 받지 않습니다.")
            .active("수류탄",
                    "{grenade_fuse}초 뒤 터지는 수류탄을 던집니다. 반경 {grenade_radius}블록 안의 적에게 거리에 따라 "
                            + "{grenade_min_damage}~{grenade_damage} 피해를 주고 밀쳐냅니다(블록 파괴 없음).",
                    8)
            .ultimate("융단폭격",
                    "조준한 지점({carpet_range}블록 이내)을 가로질러 {carpet_duration}초에 걸쳐 폭탄 {carpet_bombs}개를 "
                            + "떨어뜨립니다. 폭탄마다 반경 {carpet_radius}블록의 적에게 {carpet_damage} 피해를 줍니다"
                            + "(한 적당 최대 {carpet_max_hits}회).")
            .param("blast_resist", 70)
            .param("grenade_fuse", 1.5)
            .param("grenade_radius", 4)
            .param("grenade_damage", 7)
            .param("grenade_min_damage", 3)
            .param("carpet_range", 40)
            .param("carpet_duration", 1.5)
            .param("carpet_bombs", 8)
            .param("carpet_radius", 3)
            .param("carpet_damage", 5)
            .param("carpet_length", 12)
            .param("carpet_max_hits", 3)
            .build();

    private static final double GRENADE_SPEED = 1.0;
    private static final double GRENADE_LIFT = 0.22;
    /** The TNT fuse is this much longer than the grenade timer, so vanilla never detonates it first. */
    private static final int FUSE_MARGIN = 8;
    /** Ticks before detonation in which the blast radius is marked on the ground. */
    private static final long WARNING_TICKS = 12;

    /** Fraction of the blast radius within which the owner gets launched by their own grenade. */
    private static final double BLAST_JUMP_REACH = 0.8;
    private static final double BLAST_JUMP_UP = 1.15;
    private static final double BLAST_JUMP_SIDE = 0.55;
    /** The landing after a blast jump is protected from fall damage for at most this long. */
    private static final long BLAST_JUMP_MAX_AIR = 160;

    private static final double BLAST_PUSH = 0.9;
    private static final double BLAST_LIFT = 0.45;
    private static final double MAX_SPEED = 2.5;

    /** Sideways zig-zag of the carpet bombs around the bombing line. */
    private static final double BOMB_LATERAL = 1.4;
    private static final double BOMB_DROP_HEIGHT = 14;
    private static final int BOMB_FALL_TICKS = 10;
    private static final float BOMB_SIZE = 0.8f;
    private static final float BOMB_SPIN_DEGREES = 35f;

    private static final Particle.DustOptions WARNING_DUST = new Particle.DustOptions(Color.fromRGB(255, 60, 40), 1.2f);
    private static final Particle.DustTransition BLAST_DUST =
            new Particle.DustTransition(Color.fromRGB(255, 170, 60), Color.fromRGB(70, 60, 55), 1.6f);
    private static final Color FLASH_COLOR = Color.fromRGB(255, 200, 140);

    /** Cancels the fall damage of the landing after a blast jump. */
    private final LandingGuard blastJump = new LandingGuard();
    private long blastJumpTick;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: blast suit

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (blastJump.absorb(event, ctx().currentTick())) {
            Location feet = player().getLocation();
            feet.getWorld().spawnParticle(Particle.CLOUD, feet, 10, 0.3, 0.05, 0.3, 0.03);
            Fx.sound(feet, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 0.6f, 1.3f + Fx.jitter(0.1f));
            return;
        }
        if (!isExplosive(event)) {
            return;
        }
        double resist = Math.clamp(params().num("blast_resist") / 100.0, 0.0, 1.0);
        event.setDamage(event.getDamage() * (1 - resist));
        Location body = player().getLocation().add(0, player().getHeight() * 0.5, 0);
        body.getWorld().spawnParticle(Particle.SMOKE, body, 8, 0.3, 0.4, 0.3, 0.02);
    }

    @Override
    public void onTick(long tick) {
        if (!blastJump.isArmed() || tick % 2 != 0) {
            return;
        }
        long now = ctx().currentTick();
        blastJump.update(player(), now);
        if (blastJump.isArmed() && now - blastJumpTick < 20) {
            Location feet = player().getLocation();
            feet.getWorld().spawnParticle(Particle.SMOKE, feet, 2, 0.1, 0.05, 0.1, 0.01);
        }
    }

    /** Vanilla explosions, any explosion-typed damage, and the bombs of another 폭탄마. */
    private boolean isExplosive(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.BLOCK_EXPLOSION || cause == DamageCause.ENTITY_EXPLOSION) {
            return true;
        }
        Tag<DamageType> explosion = DamageTypeTags.IS_EXPLOSION;
        if (explosion != null && explosion.isTagged(event.getDamageSource().getDamageType())) {
            return true;
        }
        if (!ctx().isAbilityDamage()) {
            return false;
        }
        Player attacker = ctx().resolveAttacker(event.getDamageSource().getCausingEntity());
        return attacker != null && !attacker.equals(player()) && ctx().abilityOf(attacker) instanceof BomberAbility;
    }

    // ------------------------------------------------------------------ active: grenade

    @Override
    public ActivationResult onActive() {
        Location eye = player().getEyeLocation();
        World world = eye.getWorld();
        Vector look = eye.getDirection();
        Location spawn = eye.clone().add(look.clone().multiply(0.5)).subtract(0, 0.3, 0);
        if (!spawn.getBlock().isPassable()) {
            spawn = eye.clone().subtract(0, 0.3, 0);
        }
        long fuse = Math.max(1, params().ticks("grenade_fuse"));
        Vector velocity = Motion.clamp(look.clone().multiply(GRENADE_SPEED).add(new Vector(0, GRENADE_LIFT, 0)),
                MAX_SPEED);

        TNTPrimed grenade = ctx().track(player(), world.spawn(spawn, TNTPrimed.class, tnt -> {
            tnt.setPersistent(false);
            tnt.setFuseTicks((int) Math.min(Integer.MAX_VALUE - FUSE_MARGIN, fuse + FUSE_MARGIN));
            tnt.setYield(0f); // safety net: even a vanilla detonation would be harmless
            tnt.setIsIncendiary(false);
            tnt.setSource(player());
            tnt.setVelocity(velocity);
        }));
        if (!grenade.isValid()) {
            return ActivationResult.fail("수류탄을 던질 수 없습니다");
        }
        Fx.sound(eye, Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ENTITY_TNT_PRIMED, 0.9f, 1.3f + Fx.jitter(0.05f));

        double radius = Math.max(0.5, params().num("grenade_radius"));
        Loop.repeat(ctx(), player(), 1, 1, fuse, tick -> {
            if (!grenade.isValid()) {
                return false;
            }
            long left = fuse - tick - 1;
            Location at = grenade.getLocation().add(0, 0.5, 0);
            if (tick % 2 == 0) {
                world.spawnParticle(Particle.SMOKE, at.clone().add(0, 0.5, 0), 1, 0.05, 0.05, 0.05, 0.01);
                world.spawnParticle(Particle.SMALL_FLAME, at.clone().add(0, 0.55, 0), 1, 0.02, 0.02, 0.02, 0.01);
            }
            long beepEvery = left > 15 ? 8 : left > 6 ? 4 : 2;
            if (left % beepEvery == 0) {
                float progress = 1f - (float) left / fuse;
                Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.0f + progress);
            }
            if (left <= WARNING_TICKS && left % 4 == 0) {
                Location floor = groundBelow(at, 3);
                drawRing(floor == null ? at : floor, radius, Particle.DUST, WARNING_DUST);
            }
            if (left <= 0) {
                grenade.remove();
                detonate(at, radius, params().num("grenade_damage"), params().num("grenade_min_damage"), true,
                        target -> true);
                blastJump(at, radius);
                return false;
            }
            return true;
        }, () -> {
            if (grenade.isValid()) {
                grenade.remove();
            }
        });
        return ActivationResult.success();
    }

    /** Launches the owner away from their own grenade, without damage and without the landing damage. */
    private void blastJump(Location center, double radius) {
        Player self = player();
        if (!ctx().isAlive(self) || !self.getWorld().equals(center.getWorld())) {
            return;
        }
        double distance = distanceToBox(center, self.getBoundingBox());
        double reach = radius * BLAST_JUMP_REACH;
        if (distance > reach) {
            return;
        }
        double strength = 1 - distance / reach;
        Vector away = self.getLocation().toVector().subtract(center.toVector()).setY(0);
        if (away.lengthSquared() < 0.04) {
            away = self.getLocation().getDirection().setY(0);
        }
        if (away.lengthSquared() < 1.0E-4) {
            away = new Vector(0, 0, 0);
        } else {
            away.normalize().multiply(BLAST_JUMP_SIDE * (0.6 + 0.4 * strength));
        }
        away.setY(BLAST_JUMP_UP * (0.7 + 0.3 * strength));
        self.setVelocity(Motion.clamp(away, MAX_SPEED));
        self.setFallDistance(0f);
        blastJumpTick = ctx().currentTick();
        blastJump.arm(blastJumpTick, BLAST_JUMP_MAX_AIR);

        Location feet = self.getLocation();
        feet.getWorld().spawnParticle(Particle.CLOUD, feet, 14, 0.3, 0.1, 0.3, 0.08);
        Fx.sound(feet, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 0.9f + Fx.jitter(0.1f));
        self.sendActionBar(Text.mm("<#FF7A2E>✸ 폭탄 점프!</#FF7A2E>"));
    }

    // ------------------------------------------------------------------ ultimate: carpet bombing

    @Override
    public ActivationResult onUltimate() {
        Location aim = aimedGround(params().num("carpet_range"));
        if (aim == null) {
            return ActivationResult.fail("조준한 곳이 너무 멀거나 허공입니다");
        }
        Vector forward = player().getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 1.0E-4) {
            double yaw = Math.toRadians(player().getLocation().getYaw());
            forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());

        int bombs = Math.max(1, params().integer("carpet_bombs"));
        long duration = params().ticks("carpet_duration");
        double length = Math.max(0, params().num("carpet_length"));
        double radius = Math.max(0.5, params().num("carpet_radius"));
        double damage = params().num("carpet_damage");
        ThreadLocalRandom random = ThreadLocalRandom.current();

        List<Location> targets = new ArrayList<>(bombs);
        for (int i = 0; i < bombs; i++) {
            double along = bombs == 1 ? 0 : -length / 2 + length * i / (bombs - 1);
            double lateral = (i % 2 == 0 ? 1 : -1) * BOMB_LATERAL * (0.6 + 0.4 * random.nextDouble());
            Location point = aim.clone().add(forward.clone().multiply(along)).add(side.clone().multiply(lateral));
            Location landing = groundBelow(point.clone().add(0, 4, 0), 16);
            targets.add(landing == null ? point : landing);
        }
        long[] releaseAt = new long[bombs];
        for (int i = 0; i < bombs; i++) {
            releaseAt[i] = bombs == 1 ? 0 : Math.round((double) duration * i / (bombs - 1));
        }

        Fx.sound(player().getEyeLocation(), Sound.ENTITY_TNT_PRIMED, 1.0f, 0.6f);
        Fx.sound(aim, Sound.BLOCK_BEACON_DEACTIVATE, 1.6f, 0.5f);
        Fx.sound(aim, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.6f, 0.5f);

        // A target standing still (or frozen by an ally) must not eat every bomb of the carpet.
        Map<UUID, Integer> hits = new HashMap<>();
        int maxHits = Math.max(1, params().integer("carpet_max_hits"));
        List<FallingBomb> falling = new ArrayList<>();
        int[] released = {0};
        long runs = releaseAt[bombs - 1] + BOMB_FALL_TICKS + 2;
        Loop.repeat(ctx(), player(), 0, 1, runs, tick -> {
            while (released[0] < bombs && releaseAt[released[0]] <= tick) {
                Location target = targets.get(released[0]++);
                falling.add(new FallingBomb(target, radius));
            }
            for (Iterator<FallingBomb> it = falling.iterator(); it.hasNext(); ) {
                FallingBomb bomb = it.next();
                if (bomb.fall()) {
                    it.remove();
                    detonate(bomb.ground.clone().add(0, 0.5, 0), radius, damage, damage, false,
                            target -> hits.merge(target.getUniqueId(), 1, Integer::sum) <= maxHits);
                }
            }
            return released[0] < bombs || !falling.isEmpty();
        }, () -> falling.forEach(FallingBomb::discard));
        return ActivationResult.success();
    }

    /** A TNT block display dropping from the sky onto a marked spot, spinning and trailing smoke. */
    private final class FallingBomb {
        private final Location ground;
        private final double height;
        private final double radius;
        private final BlockDisplay display;
        private int age;
        private float yaw;

        FallingBomb(Location ground, double radius) {
            this.ground = ground;
            this.radius = radius;
            World world = ground.getWorld();
            RayTraceResult ceiling = world.rayTraceBlocks(ground.clone().add(0, 0.6, 0), new Vector(0, 1, 0),
                    BOMB_DROP_HEIGHT, FluidCollisionMode.NEVER, true);
            this.height = ceiling == null ? BOMB_DROP_HEIGHT
                    : Math.max(0.5, ceiling.getHitPosition().getY() - ground.getY() - 1.0);
            this.yaw = ThreadLocalRandom.current().nextFloat() * 360f;
            Location top = ground.clone().add(0, 0.4 + height, 0);
            top.setYaw(yaw);
            top.setPitch(0);
            this.display = ctx().track(player(), world.spawn(top, BlockDisplay.class, bomb -> {
                bomb.setPersistent(false);
                bomb.setBlock(Material.TNT.createBlockData());
                bomb.setBrightness(new Display.Brightness(15, 15));
                bomb.setTeleportDuration(1);
                bomb.setTransformation(new Transformation(
                        new Vector3f(-BOMB_SIZE / 2, -BOMB_SIZE / 2, -BOMB_SIZE / 2), new Quaternionf(),
                        new Vector3f(BOMB_SIZE, BOMB_SIZE, BOMB_SIZE), new Quaternionf()));
            }));
            drawRing(ground, radius, Particle.DUST, WARNING_DUST);
            Fx.sound(top, Sound.ENTITY_TNT_PRIMED, 1.2f, 0.8f + Fx.jitter(0.1f));
        }

        /** Advances the fall by one tick; true once the bomb has hit the ground. */
        boolean fall() {
            age++;
            if (age >= BOMB_FALL_TICKS) {
                discard();
                return true;
            }
            double progress = (double) age / BOMB_FALL_TICKS;
            Location at = ground.clone().add(0, 0.4 + height * (1 - progress * progress), 0);
            yaw += BOMB_SPIN_DEGREES;
            at.setYaw(yaw);
            at.setPitch(0);
            if (display.isValid()) {
                display.teleport(at);
            }
            World world = at.getWorld();
            world.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 0.6, 0), 1, 0.05, 0.1, 0.05, 0.01);
            world.spawnParticle(Particle.FLAME, at.clone().add(0, 0.5, 0), 1, 0.05, 0.05, 0.05, 0.01);
            if (age == BOMB_FALL_TICKS / 2) {
                drawRing(ground, radius, Particle.DUST, WARNING_DUST);
            }
            return false;
        }

        void discard() {
            if (display.isValid()) {
                display.remove();
            }
        }
    }

    // ------------------------------------------------------------------ explosions

    /**
     * An explicit, block-safe blast: visuals, then damage falling off linearly from {@code maxDamage} at the center
     * to {@code minDamage} at the edge for every exposed enemy in range that {@code admit} accepts, plus knockback.
     */
    private void detonate(Location center, double radius, double maxDamage, double minDamage, boolean big,
                          Predicate<LivingEntity> admit) {
        World world = center.getWorld();
        world.spawnParticle(big ? Particle.EXPLOSION_EMITTER : Particle.EXPLOSION, center, big ? 1 : 2,
                0.2, 0.2, 0.2, 0);
        world.spawnParticle(Particle.FLASH, center, 1, 0, 0, 0, 0, FLASH_COLOR);
        world.spawnParticle(Particle.FLAME, center, big ? 30 : 16, 0.3, 0.3, 0.3, radius * 0.05);
        world.spawnParticle(Particle.LARGE_SMOKE, center, big ? 16 : 8, 0.4, 0.3, 0.4, 0.06);
        drawRing(center.clone().subtract(0, 0.35, 0), radius, Particle.DUST_COLOR_TRANSITION, BLAST_DUST);
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, big ? 2.2f : 1.6f, (big ? 0.95f : 1.1f) + Fx.jitter(0.08f));
        Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.2f, 0.6f + Fx.jitter(0.1f));

        for (LivingEntity target : ctx().enemiesNear(player(), center, radius)) {
            if (!exposed(center, target) || !admit.test(target)) {
                continue;
            }
            double distance = Math.min(radius, distanceToBox(center, target.getBoundingBox()));
            double proximity = 1 - distance / radius;
            Combat.hit(ctx(), player(), target, minDamage + (maxDamage - minDamage) * proximity);
            if (target.isValid() && !target.isDead()) {
                knockback(center, target, proximity);
            }
        }
    }

    private void knockback(Location center, LivingEntity target, double proximity) {
        Vector away = target.getLocation().toVector().subtract(center.toVector()).setY(0);
        if (away.lengthSquared() < 1.0E-4) {
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            away = new Vector(Math.cos(angle), 0, Math.sin(angle));
        }
        double factor = Motion.knockbackFactor(target);
        away.normalize().multiply(BLAST_PUSH * (0.4 + 0.6 * proximity) * factor);
        away.setY(BLAST_LIFT * (0.5 + 0.5 * proximity) * factor);
        target.setVelocity(Motion.clamp(target.getVelocity().add(away), MAX_SPEED));
    }

    /** True if no solid block stands between the blast and the target's eyes or body center. */
    private static boolean exposed(Location center, LivingEntity target) {
        return Spots.clearPath(center, target.getEyeLocation())
                || Spots.clearPath(center, target.getLocation().add(0, target.getHeight() * 0.5, 0));
    }

    // ------------------------------------------------------------------ helpers

    /** The ground point the owner aims at within {@code range}: an aimed enemy's feet, else the aimed block. */
    private @Nullable Location aimedGround(double range) {
        LivingEntity target = ctx().findTarget(player(), range);
        if (target != null) {
            return target.getLocation();
        }
        Location eye = player().getEyeLocation();
        World world = eye.getWorld();
        RayTraceResult ray = world.rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        if (ray == null) {
            return null;
        }
        Location point = ray.getHitPosition().toLocation(world);
        BlockFace face = ray.getHitBlockFace();
        if (face != null) {
            point.add(face.getDirection().multiply(0.3));
        }
        Location ground = groundBelow(point, 8);
        return ground == null ? point : ground;
    }

    /** The first solid surface at most {@code maxDrop} blocks below {@code point}, null if there is none. */
    private static @Nullable Location groundBelow(Location point, double maxDrop) {
        World world = point.getWorld();
        RayTraceResult down = world.rayTraceBlocks(point, new Vector(0, -1, 0), maxDrop, FluidCollisionMode.NEVER, true);
        return down == null ? null : down.getHitPosition().toLocation(world);
    }

    private static <T> void drawRing(Location center, double radius, Particle particle, T data) {
        int points = (int) Math.max(12, Math.min(40, radius * 7));
        Fx.ring(center.clone().add(0, 0.15, 0), radius, points, 0, particle, data);
    }

    /** Distance from a point to the closest point of a box (0 inside), matching how enemies are found. */
    private static double distanceToBox(Location point, BoundingBox box) {
        double dx = Math.max(Math.max(box.getMinX() - point.getX(), 0), point.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - point.getY(), 0), point.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - point.getZ(), 0), point.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
