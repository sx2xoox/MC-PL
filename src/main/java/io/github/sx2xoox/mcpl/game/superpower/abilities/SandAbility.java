package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 모래술사 — the desert's patience. Fast on sand and slippery to hit (attacks sometimes pass through a body that
 * crumbles into sand), the active blasts a cone of sand that blinds and shoves, and the ultimate opens a quicksand pit
 * that slowly swallows enemies, dragging them towards its middle.
 *
 * <p>Look (pale sand, warm sand, dark tan): the storm is a sweeping front of sand arcs at three heights with sandstone
 * streaks racing out ahead and dust settling afterwards. The pit is a disc of sand around a slowly turning soul-sand
 * whirl, with spiral arms of drifting sand; every trapped enemy sinks into a growing sand collar.
 */
public final class SandAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("sand", "모래술사", Material.SAND)
            .color(TextColor.color(0xE3B95B))
            .difficulty(2)
            .passive("모래의 몸",
                    "모래 위(내 유사 포함)에서 이동 속도가 {sand_speed}% 빨라집니다. 적의 근접·투사체 공격을 "
                            + "{dodge_chance}% 확률로 몸이 모래로 흩어지며 피합니다.")
            .active("모래 폭풍",
                    "전방 {storm_range}블록 부채꼴({storm_angle}°)로 모래 폭풍을 일으킵니다. 휩쓸린 적은 {storm_damage} "
                            + "피해를 입고 {blind_duration}초간 실명되며 밀려납니다.",
                    9)
            .ultimate("유사",
                    "조준한 지점({ult_range}블록 이내)에 {ult_duration}초간 반경 {ult_radius}블록의 유사(流沙)를 만듭니다. "
                            + "빠진 적은 서서히 가라앉아 이동 속도가 {ult_slow}% 줄고 점프할 수 없으며, 가운데로 끌려 들어가고 "
                            + "{ult_interval}초마다 {ult_damage} 피해를 입습니다.")
            .param("sand_speed", 25)
            .param("dodge_chance", 15)
            .param("storm_range", 7)
            .param("storm_angle", 70)
            .param("storm_damage", 3)
            .param("blind_duration", 1.5)
            .param("storm_push", 0.9)
            .param("ult_range", 20)
            .param("ult_duration", 4)
            .param("ult_radius", 4.5)
            .param("ult_slow", 50)
            .param("ult_interval", 0.5)
            .param("ult_damage", 1)
            .param("ult_pull", 1)
            .build();

    private static final String COLOR_TAG = "#E3B95B";
    /** Pale sand, warm sand, dark tan. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF3D6, 0xE3B95B, 0x8D6A3A);
    private static final Particle.DustOptions GRAIN = PALETTE.dust(1.2f);
    private static final Particle.DustOptions GRAIN_FINE = PALETTE.dust(0.8f);
    private static final Particle.DustOptions TAN = new Particle.DustOptions(PALETTE.edge(), 1.3f);
    private static final Particle.DustTransition DRIFT = PALETTE.glow(1.0f);
    private static final Particle.DustTransition DUSK = PALETTE.fade(1.4f);
    private static final BlockData SAND = Material.SAND.createBlockData();
    private static final BlockData SANDSTONE = Material.SANDSTONE.createBlockData();
    private static final BlockData SOUL_SAND = Material.SOUL_SAND.createBlockData();
    private static final Color SHADOW = Color.fromRGB(0x5C4424);

    private static final double MAX_SPEED = 2.0;
    /** Display entities this ability keeps alive at most at once (extras fall back to particles). */
    private static final int MAX_DISPLAYS = 24;

    // passive
    private static final long PASSIVE_PERIOD = 5;
    /**
     * How far below the feet sand still counts. A jump must not take the speed bonus away: a movement speed modifier
     * toggling on every hop also pulses the client's field of view.
     */
    private static final int SAND_REACH = 2;
    private static final long CRUMBLE_TICKS = 8;

    // active: sandstorm
    private static final long STORM_TICKS = 6;
    private static final double STORM_HEIGHT = 2.5;
    /** Targets this close count as inside the cone whatever their angle (they stand in the blast). */
    private static final double POINT_BLANK = 1.2;
    private static final double STORM_LIFT = 0.3;
    private static final int STREAKS = 5;
    private static final long SETTLE_TICKS = 16;

    // ultimate: quicksand
    private static final int GROUND_SNAP = 8;
    private static final double PIT_REACH = 1.5;
    private static final long OPEN_TICKS = 6;
    private static final long SWIRL_PERIOD = 4;
    private static final float SWIRL_STEP = 25f;
    /** Sand collars rise up the trapped enemies' legs at this rate (blocks per tick), up to the waist. */
    private static final float SINK_RATE = 0.02f;
    private static final float SINK_MAX = 1.05f;
    private static final double CENTER_HOLD = 0.35;
    /**
     * The sand tugs its victims towards the middle every this many ticks, instead of every tick, so they keep some
     * momentum of their own and can wade out with effort.
     */
    private static final long TUG_PERIOD = 5;
    /** Roughly how far a ground impulse carries an entity per unit of speed (ground friction 0.546 per tick). */
    private static final double GROUND_GLIDE = 2.2;
    private static final int MAX_COLLARS = 6;
    private static final int SPIRAL_ARMS = 3;
    private static final int SPIRAL_POINTS = 6;
    private static final long COLLAPSE_TICKS = 8;
    /** Each slow/no-jump modifier lasts this long and is renewed halfway while the victim stays caught. */
    private static final long LEASE_TICKS = 20;

    // state
    private @Nullable NamespacedKey speedKey;
    private @Nullable Pit pit;
    /** Display entities spawned by this instance that are still alive. */
    private int liveDisplays;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: body of sand

    @Override
    public void onRevoke() {
        speedKey = null;
        pit = null;
    }

    @Override
    public void onTick(long tick) {
        if (tick % PASSIVE_PERIOD != 0) {
            return;
        }
        Player owner = player();
        boolean sandy = isOnSand(owner) || (pit != null && pit.holds(owner));
        if (sandy && speedKey == null) {
            double bonus = params().num("sand_speed") / 100.0;
            if (bonus > 0) {
                speedKey = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, bonus,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, 0);
            }
        } else if (!sandy && speedKey != null) {
            ctx().removeModifier(owner, speedKey);
            speedKey = null;
        }
        if (sandy && tick % (PASSIVE_PERIOD * 2) == 0) {
            // Sand streams off the owner's heels.
            Location heels = owner.getLocation().add(0, 0.1, 0);
            owner.getWorld().spawnParticle(Particle.FALLING_DUST, heels, Vfx.n(ctx(), 2), 0.2, 0.05, 0.2, 0, SAND);
        }
    }

    /** True if the first solid ground at most {@link #SAND_REACH} blocks below the owner's feet is sand. */
    private static boolean isOnSand(Player owner) {
        Location feet = owner.getLocation();
        World world = feet.getWorld();
        int x = feet.getBlockX();
        int z = feet.getBlockZ();
        int top = Math.min((int) Math.floor(feet.getY() - 0.2), world.getMaxHeight() - 1);
        int bottom = Math.max(world.getMinHeight(), top - SAND_REACH);
        for (int y = top; y >= bottom; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.isLiquid()) {
                return false;
            }
            if (!block.isPassable()) {
                return isSand(block.getType());
            }
        }
        return false;
    }

    private static boolean isSand(Material below) {
        Tag<Material> sand = Tag.SAND;
        if (sand != null && sand.isTagged(below)) {
            return true;
        }
        return switch (below) {
            case SAND, RED_SAND, SUSPICIOUS_SAND, SANDSTONE, RED_SANDSTONE, CUT_SANDSTONE, CUT_RED_SANDSTONE,
                 SMOOTH_SANDSTONE, SMOOTH_RED_SANDSTONE, CHISELED_SANDSTONE, CHISELED_RED_SANDSTONE -> true;
            default -> false;
        };
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || ctx().isAbilityDamage() || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        DamageCause cause = event.getCause();
        if (cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK
                && cause != DamageCause.PROJECTILE) {
            return;
        }
        Entity source = byEntity.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        if (!ctx().isEnemy(player(), source)) {
            return;
        }
        double chance = params().num("dodge_chance");
        if (chance <= 0 || ThreadLocalRandom.current().nextDouble() * 100 >= chance) {
            return;
        }
        event.setCancelled(true);
        crumble();
    }

    /** The owner's body bursts into sand where the blow lands, and a sand silhouette crumbles away. */
    private void crumble() {
        Player owner = player();
        Location body = Fx.body(owner);
        World world = body.getWorld();
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 18), 0.3, 0.5, 0.3, 0.1, SAND);
        world.spawnParticle(Particle.FALLING_DUST, body, Vfx.n(ctx(), 10), 0.35, 0.5, 0.35, 0, SAND);
        world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 8), 0.35, 0.5, 0.35, 0, GRAIN);
        Fx.sound(body, Sound.BLOCK_SAND_BREAK, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ITEM_BRUSH_BRUSHING_SAND_COMPLETE, 0.8f, 1.4f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">모래 회피!</" + COLOR_TAG + ">"));
        if (!reserveDisplays(1, CRUMBLE_TICKS + 2)) {
            return;
        }
        Location feet = owner.getLocation();
        feet.setPitch(0);
        float width = (float) owner.getWidth();
        float height = (float) owner.getHeight();
        BlockDisplay ghost = Displays.block(ctx(), owner, feet, SAND, standingBox(width, height, 0.4f),
                CRUMBLE_TICKS + 2);
        Displays.animateNextTick(ctx(), owner, ghost, standingBox(width * 1.3f, 0.05f, 0.5f), (int) CRUMBLE_TICKS);
    }

    // ------------------------------------------------------------------ active: sandstorm

    @Override
    public ActivationResult onActive() {
        Storm storm = new Storm();
        storm.cast();
        Loop.repeat(ctx(), player(), 0, 1, STORM_TICKS, storm, storm::settle);
        return ActivationResult.success();
    }

    /**
     * The sandstorm: a sand front sweeps out over {@link #STORM_TICKS} ticks along the cone in front of the owner,
     * striking every enemy it reaches once (walls shelter).
     */
    private final class Storm implements LongPredicate {

        private final Location origin;
        private final Location eye;
        private final Vector forward;
        private final float yaw;
        private final double range;
        private final double angle;
        private final Set<UUID> struck = new HashSet<>();
        private final List<BlockDisplay> streaks = new ArrayList<>();
        private final List<Vector> streakRays = new ArrayList<>();
        private final List<Location> streakStarts = new ArrayList<>();
        private final World world;

        Storm() {
            Player owner = player();
            this.origin = owner.getLocation();
            this.eye = owner.getEyeLocation();
            this.forward = Motion.horizontalFacing(origin);
            this.yaw = origin.getYaw();
            this.range = Math.max(1, params().num("storm_range"));
            this.angle = Math.clamp(params().num("storm_angle"), 5.0, 360.0);
            this.world = origin.getWorld();
        }

        void cast() {
            Player owner = player();
            Location hands = eye.clone().add(forward.clone().multiply(0.7)).subtract(0, 0.4, 0);
            world.spawnParticle(Particle.BLOCK, hands, Vfx.n(ctx(), 14), 0.25, 0.25, 0.25, 0.2, SAND);
            world.spawnParticle(Particle.DUST_PLUME, origin.clone().add(0, 0.1, 0), Vfx.n(ctx(), 8), 0.4, 0.05, 0.4,
                    0.04);
            Vfx.arc(ctx(), origin.clone().add(0, 0.15, 0), 1.0, yaw, angle, 10, Particle.DUST, TAN, false);
            Fx.sound(hands, Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(hands, Sound.ITEM_BRUSH_BRUSHING_SAND_COMPLETE, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(hands, Sound.BLOCK_SAND_BREAK, 1.0f, 0.6f + Fx.jitter(0.05f));
            long lifetime = STORM_TICKS + 6;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < STREAKS; i++) {
                if (!reserveDisplays(1, lifetime)) {
                    break;
                }
                double spread = STREAKS == 1 ? 0 : -angle / 2 + angle * i / (STREAKS - 1);
                Vector ray = forward.clone().rotateAroundY(Math.toRadians(-spread * 0.85));
                ray.setY(0.05 + random.nextDouble() * 0.12);
                Location start = flat(origin).add(0, 0.6 + random.nextDouble() * 1.1, 0);
                start.setDirection(ray);
                BlockDisplay streak = Displays.block(ctx(), owner, start, i % 2 == 0 ? SAND : SANDSTONE,
                        Displays.transform(0, 0, 0, 0.08f, 0.08f, 1.2f, 0, 0, 45), lifetime);
                streak.setTeleportDuration(1);
                streaks.add(streak);
                streakRays.add(ray.normalize());
                streakStarts.add(start);
            }
        }

        @Override
        public boolean test(long tick) {
            double radius = range * (tick + 1) / STORM_TICKS;
            drawFront(radius, tick);
            moveStreaks(radius);
            strike(radius);
            return true;
        }

        /** The sand front: arcs at three heights, grains flung outward and dust rolling along the ground. */
        private void drawFront(double radius, long tick) {
            int points = (int) Math.max(6, Math.min(26, radius * angle / 25));
            double[] heights = {0.3, 1.0, 1.7};
            for (int i = 0; i < heights.length; i++) {
                Location level = origin.clone().add(0, heights[i], 0);
                Vfx.arc(ctx(), level, radius, yaw, angle, points, Particle.DUST, i == 1 ? GRAIN : GRAIN_FINE, false);
                Vfx.arc(ctx(), level, Math.max(0.3, radius - 0.5), yaw, angle * 0.9, points / 2,
                        Particle.FALLING_DUST, SAND, false);
            }
            Vfx.arc(ctx(), origin.clone().add(0, 0.6, 0), Math.max(0.3, radius - 0.9), yaw, angle * 0.8, points / 2,
                    Particle.DUST_COLOR_TRANSITION, DUSK, false);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int grains = Vfx.n(ctx(), 6);
            for (int i = 0; i < grains; i++) {
                Vector ray = forward.clone().rotateAroundY(Math.toRadians((random.nextDouble() - 0.5) * angle));
                Location at = origin.clone().add(ray.clone().multiply(radius * random.nextDouble()))
                        .add(0, 0.2 + random.nextDouble() * 1.8, 0);
                // count 0: the offsets become the particle's direction and "extra" its speed.
                world.spawnParticle(Particle.BLOCK, at.getX(), at.getY(), at.getZ(), 0, ray.getX(), 0.05, ray.getZ(),
                        0.4, SAND);
            }
            if (tick % 2 == 0) {
                Location ground = origin.clone().add(forward.clone().multiply(radius * 0.7)).add(0, 0.1, 0);
                world.spawnParticle(Particle.DUST_PLUME, ground, Vfx.n(ctx(), 4), radius * 0.3, 0.05, radius * 0.3,
                        0.03);
            }
            if (tick % 2 == 1) {
                Fx.sound(origin.clone().add(forward.clone().multiply(radius)), Sound.BLOCK_SAND_FALL, 0.7f,
                        0.6f + 0.08f * tick + Fx.jitter(0.05f));
            }
        }

        private void moveStreaks(double radius) {
            for (int i = 0; i < streaks.size(); i++) {
                BlockDisplay streak = streaks.get(i);
                if (!streak.isValid()) {
                    continue;
                }
                Vector ray = streakRays.get(i);
                Location target = streakStarts.get(i).clone()
                        .add(ray.getX() * radius, ray.getY() * radius, ray.getZ() * radius);
                target.setDirection(ray);
                streak.teleport(target);
            }
        }

        private void strike(double radius) {
            Player owner = player();
            double half = Math.toRadians(angle / 2);
            for (LivingEntity enemy : ctx().enemiesNear(owner, origin, radius + 1.5)) {
                if (struck.contains(enemy.getUniqueId()) || !inCone(enemy, radius, half)) {
                    continue;
                }
                struck.add(enemy.getUniqueId());
                Combat.hit(ctx(), owner, enemy, params().num("storm_damage"));
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                long blind = params().ticks("blind_duration");
                if (blind > 0) {
                    enemy.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                            (int) Math.min(Integer.MAX_VALUE, blind), 0, false, true, true));
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(origin.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    away = forward.clone();
                }
                Motion.push(enemy, away.multiply(params().num("storm_push")).setY(STORM_LIFT), MAX_SPEED);
                Location face = enemy.getEyeLocation();
                world.spawnParticle(Particle.BLOCK, face, Vfx.n(ctx(), 14), 0.25, 0.25, 0.25, 0.1, SAND);
                world.spawnParticle(Particle.FALLING_DUST, face, Vfx.n(ctx(), 6), 0.3, 0.3, 0.3, 0, SAND);
                world.spawnParticle(Particle.DUST, Fx.body(enemy), Vfx.n(ctx(), 6), 0.3, 0.45, 0.3, 0, TAN);
                Fx.sound(face, Sound.BLOCK_SAND_HIT, 1.0f, 1.2f + Fx.jitter(0.1f));
                Fx.sound(face, Sound.BLOCK_SAND_BREAK, 0.6f, 1.5f + Fx.jitter(0.1f));
            }
        }

        /** Inside the cone's reach and angle, at a sensible height, and not behind a wall. */
        private boolean inCone(LivingEntity enemy, double radius, double half) {
            Location at = enemy.getLocation();
            Vector offset = at.toVector().subtract(origin.toVector());
            double dy = offset.getY();
            offset.setY(0);
            double distance = offset.length();
            if (distance > radius + enemy.getWidth() / 2 || dy < -STORM_HEIGHT || dy > STORM_HEIGHT) {
                return false;
            }
            if (distance > POINT_BLANK && offset.angle(forward) > half) {
                return false;
            }
            return Spots.clearPath(eye, Fx.body(enemy));
        }

        /** Runs when the storm has swept its full range: the streaks fade and the dust settles. */
        void settle() {
            for (BlockDisplay streak : streaks) {
                if (streak.isValid()) {
                    Displays.shrinkAway(ctx(), player(), streak, 4);
                }
            }
            Loop.repeat(ctx(), player(), 2, 2, SETTLE_TICKS / 2, run -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int motes = Vfx.n(ctx(), 4);
                for (int i = 0; i < motes; i++) {
                    Vector ray = forward.clone().rotateAroundY(Math.toRadians((random.nextDouble() - 0.5) * angle));
                    Location at = origin.clone().add(ray.multiply(range * (0.3 + random.nextDouble() * 0.7)))
                            .add(0, 0.4 + random.nextDouble() * 1.6, 0);
                    Vfx.dot(at, Particle.FALLING_DUST, SAND, false);
                }
                return true;
            });
            if (!struck.isEmpty() && player().isOnline()) {
                player().sendActionBar(Text.mm("<" + COLOR_TAG + ">모래 폭풍 <white>" + struck.size()
                        + "명 적중!</white></" + COLOR_TAG + ">"));
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: quicksand

    @Override
    public ActivationResult onUltimate() {
        Location center = aimPoint(params().num("ult_range"));
        if (center == null) {
            return ActivationResult.fail("유사를 펼칠 땅이 없습니다");
        }
        if (!ctx().isInsideArena(center)) {
            return ActivationResult.fail("경기장 밖에는 유사를 만들 수 없습니다");
        }
        Pit opened = new Pit(center);
        pit = opened;
        opened.open();
        Loop.repeat(ctx(), player(), 0, 1, opened.life, opened, opened::collapse);
        return ActivationResult.success();
    }

    /**
     * Where the pit opens: under the aimed enemy, or where the view ray hits a block (or ends), on the ground at most
     * {@link #GROUND_SNAP} blocks below; null if there is no ground there.
     */
    private @Nullable Location aimPoint(double range) {
        Player owner = player();
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        Location point;
        if (aimed != null) {
            point = aimed.getLocation().add(0, 0.5, 0);
        } else {
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            point = hit == null
                    ? eye.clone().add(direction.clone().multiply(range))
                    : hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.2));
        }
        Double ground = groundBelow(point, GROUND_SNAP);
        if (ground == null) {
            return null;
        }
        Location center = flat(point);
        center.setY(ground);
        return center;
    }

    /**
     * An enemy caught in the pit: its slow and jump modifiers, its sand collar and when it fell in. The modifiers are
     * short leases renewed while it stays caught, so they fade within a second once the pit is gone, even if its owner
     * died.
     */
    private static final class Victim {
        final LivingEntity entity;
        final @Nullable BlockDisplay collar;
        final long since;
        NamespacedKey speedKey;
        NamespacedKey jumpKey;
        long renewAt;

        Victim(LivingEntity entity, NamespacedKey speedKey, NamespacedKey jumpKey, @Nullable BlockDisplay collar,
               long since) {
            this.entity = entity;
            this.speedKey = speedKey;
            this.jumpKey = jumpKey;
            this.collar = collar;
            this.since = since;
            this.renewAt = since + LEASE_TICKS / 2;
        }
    }

    /**
     * The quicksand pit. Every tick it catches enemies standing in it (slowed, no jumping, a sand collar rising up
     * their legs), releases those who got out, and drags the caught ones towards its middle; every
     * {@code ult_interval} it grinds them for damage.
     */
    private final class Pit implements LongPredicate {

        private final Location center;
        private final World world;
        private final double radius;
        private final long life;
        private final long interval;
        private final double slow;
        private final double pull;
        private final Map<UUID, Victim> victims = new LinkedHashMap<>();
        private final Set<UUID> swallowed = new HashSet<>();
        private final List<BlockDisplay> rim = new ArrayList<>();
        private final List<BlockDisplay> whirl = new ArrayList<>();
        private float whirlYaw;
        private boolean closed;

        Pit(Location center) {
            this.center = center;
            this.world = center.getWorld();
            this.radius = Math.max(1, params().num("ult_radius"));
            this.life = Math.max(1, params().ticks("ult_duration"));
            this.interval = Math.max(1, params().ticks("ult_interval"));
            this.slow = Math.clamp(params().num("ult_slow") / 100.0, 0.0, 1.0);
            // The speed of one tug that drags a victim ult_pull blocks per second on average.
            this.pull = Math.max(0, params().num("ult_pull")) * TUG_PERIOD / 20.0 / GROUND_GLIDE;
        }

        void open() {
            Player owner = player();
            long lifetime = life + COLLAPSE_TICKS + 4;
            float size = (float) (radius * 2);
            for (int i = 0; i < 2; i++) {
                if (reserveDisplays(1, lifetime)) {
                    float height = 0.02f + i * 0.005f;
                    BlockDisplay plate = Displays.block(ctx(), owner, center, SAND, plate(0.3f, i * 45f, height),
                            lifetime);
                    Displays.animateNextTick(ctx(), owner, plate, plate(size, i * 45f, height), (int) OPEN_TICKS);
                    rim.add(plate);
                }
            }
            for (int i = 0; i < 2; i++) {
                if (reserveDisplays(1, lifetime)) {
                    BlockDisplay plate = Displays.block(ctx(), owner, center, SOUL_SAND,
                            plate(0.2f, 22.5f + i * 45f, 0.035f + i * 0.005f), lifetime);
                    Displays.animateNextTick(ctx(), owner, plate, plate(size * 0.45f, 22.5f + i * 45f,
                            0.035f + i * 0.005f), (int) OPEN_TICKS);
                    whirl.add(plate);
                }
            }
            Location floor = center.clone().add(0, 0.12, 0);
            Vfx.shockwave(ctx(), owner, floor, 0.4, radius, OPEN_TICKS, Particle.DUST_COLOR_TRANSITION, DRIFT, true);
            world.spawnParticle(Particle.DUST_PLUME, floor, Vfx.n(ctx(), 20), radius * 0.4, 0.05, radius * 0.4, 0.04,
                    null, true);
            world.spawnParticle(Particle.BLOCK, floor, Vfx.n(ctx(), 30), radius * 0.35, 0.1, radius * 0.35, 0.1, SAND,
                    true);
            Vfx.helix(ctx(), center, radius * 0.5, 2.2, 1.0, 3, 12, 0, Particle.FALLING_DUST, SAND, true);
            Fx.sound(center, Sound.BLOCK_SAND_FALL, 1.5f, 0.5f);
            Fx.sound(center, Sound.ENTITY_HUSK_CONVERTED_TO_ZOMBIE, 0.8f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_SOUL_SAND_FALL, 1.2f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.ENTITY_BREEZE_WHIRL, 1.0f, 0.6f);
        }

        /** True if {@code entity} stands in the pit: inside its radius with the feet close to its surface. */
        boolean holds(LivingEntity entity) {
            if (closed) {
                return false;
            }
            Location at = entity.getLocation();
            double dx = at.getX() - center.getX();
            double dz = at.getZ() - center.getZ();
            double dy = at.getY() - center.getY();
            double reach = radius + entity.getWidth() / 2;
            return dx * dx + dz * dz <= reach * reach && dy > -PIT_REACH && dy < PIT_REACH;
        }

        @Override
        public boolean test(long tick) {
            releaseEscaped(tick);
            catchNewcomers(tick);
            drag(tick);
            if (tick % interval == interval - 1) {
                grind();
            }
            draw(tick);
            if (tick % SWIRL_PERIOD == 0) {
                swirl();
            }
            if (tick % 10 == 0) {
                Fx.sound(center, Sound.BLOCK_SAND_IDLE, 1.2f, 0.6f + Fx.jitter(0.1f));
                Fx.sound(center, Sound.BLOCK_SOUL_SAND_STEP, 1.0f, 0.5f + Fx.jitter(0.1f));
            }
            return true;
        }

        /** Victims that got out (or died) are let go; the others' leases are renewed. */
        private void releaseEscaped(long tick) {
            Player owner = player();
            Iterator<Victim> iterator = victims.values().iterator();
            while (iterator.hasNext()) {
                Victim victim = iterator.next();
                if (!Combat.isTargetable(ctx(), owner, victim.entity) || !holds(victim.entity)) {
                    free(victim, true);
                    iterator.remove();
                } else if (tick >= victim.renewAt) {
                    ctx().removeModifier(victim.entity, victim.speedKey);
                    ctx().removeModifier(victim.entity, victim.jumpKey);
                    victim.speedKey = sink(victim.entity);
                    victim.jumpKey = noJump(victim.entity);
                    victim.renewAt = tick + LEASE_TICKS / 2;
                }
            }
        }

        private NamespacedKey sink(LivingEntity enemy) {
            return ctx().addModifier(player(), enemy, Attribute.MOVEMENT_SPEED, -slow,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, LEASE_TICKS);
        }

        private NamespacedKey noJump(LivingEntity enemy) {
            return ctx().addModifier(player(), enemy, Attribute.JUMP_STRENGTH, -1.0,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, LEASE_TICKS);
        }

        private void catchNewcomers(long tick) {
            Player owner = player();
            for (LivingEntity enemy : ctx().enemiesNear(owner, center.clone().add(0, 0.5, 0), radius + 1)) {
                if (victims.containsKey(enemy.getUniqueId()) || !holds(enemy)) {
                    continue;
                }
                long remaining = Math.max(1, life - tick + 1);
                NamespacedKey speed = sink(enemy);
                NamespacedKey jump = noJump(enemy);
                BlockDisplay collar = null;
                if (victims.size() < MAX_COLLARS && reserveDisplays(1, remaining + COLLAPSE_TICKS)) {
                    collar = Displays.block(ctx(), owner, flat(enemy.getLocation()), SAND,
                            collarPose(enemy, 0.05f), remaining + COLLAPSE_TICKS);
                    collar.setTeleportDuration(1);
                }
                victims.put(enemy.getUniqueId(), new Victim(enemy, speed, jump, collar, tick));
                swallowed.add(enemy.getUniqueId());
                Location feet = enemy.getLocation().add(0, 0.1, 0);
                world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 14), 0.35, 0.1, 0.35, 0.08, SAND, true);
                Vfx.ring(ctx(), feet, 0.7, 12, 0, Particle.DUST, TAN, true);
                Fx.sound(feet, Sound.BLOCK_SAND_BREAK, 1.0f, 0.6f + Fx.jitter(0.1f));
                Fx.sound(feet, Sound.BLOCK_MUD_STEP, 0.8f, 0.6f + Fx.jitter(0.1f));
            }
        }

        /**
         * The sand tugs its victims towards the middle and the collars rise. The drag is the pit's crowd control, not
         * knockback: it ignores knockback resistance, and it never lifts anyone.
         */
        private void drag(long tick) {
            for (Victim victim : victims.values()) {
                LivingEntity entity = victim.entity;
                Location at = entity.getLocation();
                Vector to = new Vector(center.getX() - at.getX(), 0, center.getZ() - at.getZ());
                double distance = to.length();
                if (tick % TUG_PERIOD == 0 && distance > CENTER_HOLD && pull > 0) {
                    // A tug towards the middle with a little swirl; never upwards.
                    double speed = Math.min(pull, distance / GROUND_GLIDE);
                    Vector swirl = new Vector(-to.getZ(), 0, to.getX()).multiply(0.3 * speed / distance);
                    Vector tug = to.multiply(speed / distance).add(swirl);
                    double fall = Math.min(0, entity.getVelocity().getY());
                    entity.setVelocity(Motion.clamp(new Vector(tug.getX(), fall, tug.getZ()), MAX_SPEED));
                    if (tick % (TUG_PERIOD * 2) == 0) {
                        Vfx.trail(Fx.body(entity), center.clone().add(0, 0.3, 0), PALETTE.edge(), 8, false);
                    }
                }
                BlockDisplay collar = victim.collar;
                if (collar != null && collar.isValid()) {
                    collar.teleport(flat(at));
                    float sunk = Math.min(SINK_MAX, 0.15f + (tick - victim.since) * SINK_RATE);
                    if (tick % 2 == 0) {
                        Displays.animate(collar, collarPose(entity, sunk), 2);
                    }
                }
                if (tick % 3 == 0) {
                    Location feet = at.clone().add(0, 0.15, 0);
                    Vfx.ring(ctx(), feet, 0.5, 6, tick * 0.4, Particle.FALLING_DUST, SAND, false);
                }
            }
        }

        private void grind() {
            Player owner = player();
            double damage = params().num("ult_damage");
            for (Victim victim : List.copyOf(victims.values())) {
                Combat.hit(ctx(), owner, victim.entity, damage);
                if (Combat.isTargetable(ctx(), owner, victim.entity)) {
                    Location waist = victim.entity.getLocation().add(0, 0.6, 0);
                    world.spawnParticle(Particle.BLOCK, waist, Vfx.n(ctx(), 6), 0.3, 0.2, 0.3, 0.05, SAND);
                    world.spawnParticle(Particle.DUST, waist, Vfx.n(ctx(), 3), 0.3, 0.2, 0.3, 0, TAN);
                    Fx.sound(waist, Sound.BLOCK_SAND_HIT, 0.6f, 0.7f + Fx.jitter(0.1f));
                }
            }
        }

        /** Spiral arms of drifting sand turning into the middle, grains sinking, the rim and a dark centre. */
        private void draw(long tick) {
            double spin = tick * 0.12;
            int points = Vfx.n(ctx(), SPIRAL_POINTS);
            for (int arm = 0; arm < SPIRAL_ARMS; arm++) {
                double base = spin + arm * Math.PI * 2 / SPIRAL_ARMS;
                for (int i = 0; i < points; i++) {
                    double f = (i + 1.0) / points;
                    double r = radius * f;
                    double a = base - f * 2.2;
                    double x = center.getX() + Math.cos(a) * r;
                    double z = center.getZ() + Math.sin(a) * r;
                    double y = center.getY() + 0.12 + f * 0.12;
                    if (i % 2 == 0) {
                        world.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, f > 0.5 ? GRAIN : TAN, true);
                    } else {
                        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, y, z, 1, 0, 0, 0, 0, DUSK, true);
                    }
                }
            }
            if (tick % 2 == 0) {
                Location grain = Vfx.scatter(center, radius).add(0, 1.2, 0);
                world.spawnParticle(Particle.FALLING_DUST, grain, 1, 0, 0, 0, 0, SAND, true);
            }
            if (tick % 3 == 0) {
                world.spawnParticle(Particle.DUST_PLUME, center.clone().add(0, 0.15, 0), 1, 0.2, 0, 0.2, 0.01, null,
                        true);
                world.spawnParticle(Particle.DUST, center.clone().add(0, 0.1, 0), Vfx.n(ctx(), 2), 0.25, 0, 0.25, 0,
                        new Particle.DustOptions(SHADOW, 1.5f), true);
            }
            if (tick % 4 == 0) {
                Location edge = center.clone().add(0, 0.12, 0);
                Vfx.ring(ctx(), edge, radius, (int) Math.min(40, Math.max(14, radius * 6)), -tick * 0.03,
                        Particle.DUST_COLOR_TRANSITION, DRIFT, true);
            }
        }

        /** The dark whirl in the middle turns. */
        private void swirl() {
            whirlYaw += SWIRL_STEP;
            float size = (float) (radius * 2 * 0.45);
            for (int i = 0; i < whirl.size(); i++) {
                BlockDisplay plate = whirl.get(i);
                if (plate.isValid()) {
                    Displays.animate(plate, plate(size, 22.5f + i * 45f + whirlYaw, 0.035f + i * 0.005f),
                            (int) SWIRL_PERIOD);
                }
            }
        }

        private void free(Victim victim, boolean visible) {
            ctx().removeModifier(victim.entity, victim.speedKey);
            ctx().removeModifier(victim.entity, victim.jumpKey);
            BlockDisplay collar = victim.collar;
            if (collar != null && collar.isValid()) {
                Displays.shrinkAway(ctx(), player(), collar, 4);
            }
            if (visible && victim.entity.isValid()) {
                Location feet = victim.entity.getLocation().add(0, 0.3, 0);
                world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 8), 0.3, 0.2, 0.3, 0.06, SAND);
            }
        }

        /** The pit closes: victims are let go, the sand firms up and sinks away in a last burst. */
        void collapse() {
            closed = true;
            if (pit == this) {
                pit = null;
            }
            for (Victim victim : victims.values()) {
                free(victim, false);
            }
            victims.clear();
            for (BlockDisplay plate : rim) {
                if (plate.isValid()) {
                    Displays.shrinkAway(ctx(), player(), plate, (int) COLLAPSE_TICKS);
                }
            }
            for (BlockDisplay plate : whirl) {
                if (plate.isValid()) {
                    Displays.shrinkAway(ctx(), player(), plate, (int) COLLAPSE_TICKS / 2);
                }
            }
            Location floor = center.clone().add(0, 0.2, 0);
            world.spawnParticle(Particle.BLOCK, floor, Vfx.n(ctx(), 30), radius * 0.4, 0.15, radius * 0.4, 0.12, SAND,
                    true);
            world.spawnParticle(Particle.DUST_PLUME, floor, Vfx.n(ctx(), 14), radius * 0.4, 0.05, radius * 0.4, 0.03,
                    null, true);
            Vfx.shockwave(ctx(), player(), floor, radius, radius + 1.0, 4, Particle.DUST, GRAIN, true);
            Fx.sound(center, Sound.BLOCK_SAND_PLACE, 1.4f, 0.6f);
            Fx.sound(center, Sound.BLOCK_SUSPICIOUS_SAND_BREAK, 1.0f, 0.7f + Fx.jitter(0.05f));
            if (!swallowed.isEmpty() && player().isOnline()) {
                player().sendActionBar(Text.mm("<" + COLOR_TAG + ">유사 <white>" + swallowed.size()
                        + "명 빠뜨림!</white></" + COLOR_TAG + ">"));
            }
        }
    }

    /** The sand collar around a trapped enemy's legs: {@code height} blocks of sand rising from the ground. */
    private static Transformation collarPose(LivingEntity entity, float height) {
        float width = (float) entity.getWidth() + 0.45f;
        return new Transformation(new Vector3f(-width / 2, -0.05f, -width / 2), new Quaternionf(),
                new Vector3f(width, Math.max(0.02f, height), width), new Quaternionf());
    }

    /** A box standing on the display's origin, {@code depth} deep along its facing. */
    private static Transformation standingBox(float width, float height, float depth) {
        return new Transformation(new Vector3f(-width / 2, 0, -depth / 2), new Quaternionf(),
                new Vector3f(width, Math.max(0.02f, height), depth), new Quaternionf());
    }

    // ------------------------------------------------------------------ helpers

    /** The top of the first solid block at most {@code depth} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point, int depth) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = Math.min(point.getBlockY(), world.getMaxHeight() - 1);
        int end = Math.max(world.getMinHeight(), start - depth);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable() && block.getRelative(BlockFace.UP).isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

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

    /** A flat square plate of edge {@code size}, turned {@code yaw} degrees, lying {@code height} above the origin. */
    private static Transformation plate(float size, float yaw, float height) {
        return Displays.transform(0, height, 0, size, 0.02f, size, yaw, 0, 0);
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
