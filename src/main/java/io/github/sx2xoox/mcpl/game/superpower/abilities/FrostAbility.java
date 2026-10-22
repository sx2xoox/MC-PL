package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 빙결술사 — crowd control through cold. Melee attackers get chilled, the active throws a fast ice spear that
 * freezes its target, the ultimate locks every nearby enemy inside a temporary ice prison that shatters for damage.
 *
 * <p>Look (white, pale cyan, ice blue): a frost circle forms around the aim and a drilling ice spear in a glassy
 * sheath flies out on a trail of snow; whatever it freezes sprouts a cluster of ice crystals. The ultimate sends a
 * frost ring and a fan of ice shards racing across the ground, ice creeps up every prisoner, and the prisons burst
 * into flying ice chunks.
 */
public final class FrostAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("frost", "빙결술사", Material.BLUE_ICE)
            .color(TextColor.color(0x8EDBFF))
            .difficulty(2)
            .passive("서리 갑옷",
                    "나를 근접 공격한 적은 {chill_duration}초간 구속 I에 걸립니다. 빙결 피해를 받지 않습니다.")
            .active("얼음창",
                    "빠르게 날아가는 얼음창을 던집니다. 맞은 적은 {spear_damage} 피해를 입고 {freeze_duration}초간 "
                            + "얼어붙어 이동 속도가 {freeze_slow}% 줄고 점프할 수 없습니다.",
                    7)
            .ultimate("절대영도",
                    "반경 {ult_radius}블록 안의 적을 {ult_duration}초간 얼음 감옥에 가둬 완전히 얼립니다. "
                            + "얼음이 깨질 때 {ult_damage} 피해를 줍니다. 감옥의 창 사이로는 공격할 수 있습니다.")
            .param("chill_duration", 1.5)
            .param("spear_damage", 4)
            .param("freeze_duration", 2)
            .param("freeze_slow", 80)
            .param("spear_range", 40)
            .param("ult_radius", 8)
            .param("ult_duration", 3)
            .param("ult_damage", 3)
            .build();

    /** Blocks travelled per tick by the ice spear. */
    private static final double SPEAR_SPEED = 2.2;
    /** How close the flight path must pass an enemy's hitbox to count as a hit. */
    private static final double SPEAR_HITBOX = 0.4;
    /** Collision sampling distance along the flight path, so the fast spear never skips a target. */
    private static final double SAMPLE_STEP = 0.45;
    /** Ticks of the shockwave ring expanding from the owner when the ultimate is cast. */
    private static final int SHOCKWAVE_TICKS = 8;

    // Visuals
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xBFF4FF, 0x4FC3F7);
    private static final Particle.DustOptions RIME = new Particle.DustOptions(PALETTE.edge(), 1.0f);
    private static final BlockData PACKED_ICE = Material.PACKED_ICE.createBlockData();
    private static final BlockData BLUE_ICE = Material.BLUE_ICE.createBlockData();
    private static final BlockData CLEAR_ICE = Material.ICE.createBlockData();
    private static final BlockData SHEATH = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
    /** Display entities this ability keeps alive at most at once (extras fall back to particles or are skipped). */
    private static final int MAX_DISPLAYS = 24;
    private static final float SPEAR_THICKNESS = 0.14f;
    private static final float SPEAR_LENGTH = 1.5f;
    private static final float SHEATH_THICKNESS = 0.27f;
    private static final float SHEATH_LENGTH = 1.05f;
    /** Degrees the spear core rolls per tick (the sheath turns the other way, slower). */
    private static final float SPEAR_SPIN = 40f;
    private static final int SPEAR_CRYSTALS = 4;
    private static final int PRISON_CRYSTALS = 3;
    private static final long CRYSTAL_GROW_TICKS = 4;
    private static final long SHELL_GROW_TICKS = 6;
    private static final int SHARDS = 10;
    private static final long SHARD_FLIGHT_TICKS = 8;
    private static final int CHUNKS = 4;
    private static final long CHUNK_FLIGHT_TICKS = 6;

    /** Display entities spawned by this instance that are still alive. */
    private int liveDisplays;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: frost armor

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (isFreezeDamage(event)) {
            event.setCancelled(true);
            return;
        }
        // Ability damage (e.g. Venom's plague, reported as ENTITY_ATTACK) is not a melee attack.
        if (ctx().isAbilityDamage()) {
            return;
        }
        DamageCause cause = event.getCause();
        if (!(event instanceof EntityDamageByEntityEvent byEntity)
                || (cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK)
                || !(byEntity.getDamager() instanceof LivingEntity attacker)
                || !ctx().isEnemy(player(), attacker)) {
            return;
        }
        int ticks = (int) Math.min(Integer.MAX_VALUE, params().ticks("chill_duration"));
        if (ticks <= 0) {
            return;
        }
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 0, false, true, true));
        chillVisual(attacker, ticks);
    }

    private static boolean isFreezeDamage(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.FREEZE) {
            return true;
        }
        Tag<DamageType> freezing = DamageTypeTags.IS_FREEZING;
        return freezing != null && freezing.isTagged(event.getDamageSource().getDamageType());
    }

    /**
     * Cold creeps from the frost mage onto the attacker: a puff of snow and glinting rime around it, then frost
     * rings at its feet for as long as the chill lasts.
     */
    private void chillVisual(LivingEntity attacker, long ticks) {
        Location body = Fx.body(attacker);
        World world = attacker.getWorld();
        Location chest = Fx.body(player());
        Vfx.trail(chest, body, PALETTE.body(), 5, false);
        Vfx.trail(chest.clone().add(0, 0.3, 0), body, PALETTE.edge(), 6, false);
        world.spawnParticle(Particle.SNOWFLAKE, body, Vfx.n(ctx(), 8), 0.25, 0.45, 0.25, 0.02);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 5), 0.3, 0.45, 0.3, 0,
                PALETTE.fade(1.0f));
        world.spawnParticle(Particle.WAX_OFF, body, Vfx.n(ctx(), 4), 0.3, 0.5, 0.3, 0.5);
        Fx.sound(body, Sound.BLOCK_POWDER_SNOW_HIT, 0.8f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.8f + Fx.jitter(0.1f));
        Loop.repeat(ctx(), player(), 5, 5, Math.max(1, ticks / 5), i -> {
            if (!attacker.isValid() || attacker.isDead()) {
                return false;
            }
            Location feet = attacker.getLocation().add(0, 0.1, 0);
            Vfx.ring(ctx(), feet, 0.5, 8, i * 0.4, Particle.DUST, RIME, false);
            Vfx.dot(feet.add(0, 0.3, 0), Particle.SNOWFLAKE, null, false);
            return true;
        });
    }

    // ------------------------------------------------------------------ active: ice spear

    @Override
    public ActivationResult onActive() {
        Location eye = player().getEyeLocation();
        Vector direction = eye.getDirection();
        Spear spear = new Spear(eye, direction);
        long maxTicks = (long) Math.ceil(params().num("spear_range") / SPEAR_SPEED) + 2;
        Loop.repeat(ctx(), player(), 0, 1, maxTicks, spear);

        Fx.sound(eye, Sound.ITEM_TRIDENT_THROW, 1.0f, 1.4f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(eye, Sound.BLOCK_POWDER_SNOW_BREAK, 0.6f, 1.4f + Fx.jitter(0.1f));
        castCircle(eye.clone().add(direction.clone().multiply(0.9)), direction);
        return ActivationResult.success();
    }

    /** A frost circle forms around the aim at the hand: a snowflake ring, a rime ring and a few glints. */
    private void castCircle(Location center, Vector direction) {
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        aimRing(center, direction, 0.45, 10, phase, Particle.SNOWFLAKE, null);
        aimRing(center, direction, 0.32, 8, phase + 0.3, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.8f));
        World world = center.getWorld();
        int glints = Vfx.n(ctx(), 3);
        for (int i = 0; i < glints; i++) {
            world.spawnParticle(Particle.END_ROD, center.getX(), center.getY(), center.getZ(), 0,
                    direction.getX() + Fx.jitter(0.2f), direction.getY() + Fx.jitter(0.2f),
                    direction.getZ() + Fx.jitter(0.2f), 0.15);
        }
    }

    /** A simulated ice spear: a thin ice crystal flying straight until it hits an enemy or a block. */
    private final class Spear implements LongPredicate {

        private final World world;
        private final Location position;
        private final Vector direction;
        private final BlockDisplay core;
        private final @Nullable BlockDisplay sheath;
        private double remaining;

        Spear(Location eye, Vector direction) {
            this.world = eye.getWorld();
            this.direction = direction.clone().normalize();
            this.position = eye.clone();
            this.position.setDirection(this.direction);
            this.remaining = params().num("spear_range");
            long lifetime = (long) Math.ceil(remaining / SPEAR_SPEED) + 6;
            // The display's own yaw and pitch point it along the flight; its transformation only rolls it.
            Location spawn = this.position.clone().add(this.direction.clone().multiply(1.2)).subtract(0, 0.15, 0);
            this.core = Displays.block(ctx(), player(), spawn, PACKED_ICE, coreShape(45), lifetime);
            core.setTeleportDuration(1);
            this.sheath = reserveDisplays(1, lifetime)
                    ? Displays.block(ctx(), player(), spawn, SHEATH, sheathShape(0), lifetime) : null;
            if (sheath != null) {
                sheath.setTeleportDuration(1);
            }
        }

        @Override
        public boolean test(long tick) {
            double step = Math.min(SPEAR_SPEED, remaining);
            if (step <= 1.0E-3) {
                shatter(position, null);
                return false;
            }
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double travel = wall == null ? step : wall.getHitPosition().distance(position.toVector());

            int samples = Math.max(1, (int) Math.ceil(travel / SAMPLE_STEP));
            for (int i = 1; i <= samples; i++) {
                Location probe = position.clone().add(direction.clone().multiply(travel * i / samples));
                List<LivingEntity> enemies = ctx().enemiesNear(player(), probe, SPEAR_HITBOX);
                if (!enemies.isEmpty()) {
                    shatter(probe, enemies.getFirst());
                    return false;
                }
            }
            if (wall != null) {
                shatter(wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.15)), null);
                return false;
            }

            Location from = position.clone();
            position.add(direction.clone().multiply(step));
            remaining -= step;
            fly(from, tick);
            if (remaining <= 1.0E-3) {
                shatter(position, null);
                return false;
            }
            return true;
        }

        /** Moves the drilling spear and leaves a trail of glowing rime and snow. */
        private void fly(Location from, long tick) {
            if (core.isValid()) {
                core.teleport(position);
                Displays.animate(core, coreShape(45 + SPEAR_SPIN * (tick + 1)), 1);
            }
            if (sheath != null && sheath.isValid()) {
                sheath.teleport(position);
                Displays.animate(sheath, sheathShape(-SPEAR_SPIN * 0.5f * (tick + 1)), 1);
            }
            Vfx.line(ctx(), from, position, 1.4, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.8f), false);
            Vector back = direction.clone().multiply(-1);
            int flakes = Vfx.n(ctx(), 2);
            for (int i = 0; i < flakes; i++) {
                world.spawnParticle(Particle.SNOWFLAKE, from.getX(), from.getY(), from.getZ(), 0,
                        back.getX() + Fx.jitter(0.4f), back.getY() + Fx.jitter(0.4f), back.getZ() + Fx.jitter(0.4f),
                        0.05);
            }
            if (tick % 2 == 0) {
                Vfx.dot(from, Particle.END_ROD, null, false);
            }
        }

        private void shatter(Location at, @Nullable LivingEntity target) {
            core.remove();
            if (sheath != null) {
                sheath.remove();
            }
            iceBurst(at, 0.9f, false);
            if (target == null) {
                return;
            }
            Combat.hit(ctx(), player(), target, params().num("spear_damage"));
            if (Combat.isTargetable(ctx(), player(), target)) {
                freeze(target, params().ticks("freeze_duration"), params().num("freeze_slow") / 100.0, false);
                Fx.sound(at, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.0f, 1.1f);
            }
        }
    }

    /** The spear's thin ice core, rolled {@code roll} degrees around its long axis (45 shows a diamond profile). */
    private static Transformation coreShape(float roll) {
        return Displays.transform(0, 0, 0, SPEAR_THICKNESS, SPEAR_THICKNESS, SPEAR_LENGTH, 0, 0, roll % 360f);
    }

    /** The translucent glass sheath around the spear's head, rolled {@code roll} degrees. */
    private static Transformation sheathShape(float roll) {
        return Displays.transform(0, 0, 0.12f, SHEATH_THICKNESS, SHEATH_THICKNESS, SHEATH_LENGTH, 0, 0,
                roll % 360f);
    }

    /** Ice breaking at {@code at}: a cold flash, ice fragments, a bloom of snow and a ring of rime. */
    private void iceBurst(Location at, float size, boolean force) {
        World world = at.getWorld();
        world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, PALETTE.core(), force);
        world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), (int) (16 * size)), 0.25 * size, 0.25 * size,
                0.25 * size, 0.1, PACKED_ICE, force);
        bloom(at, Particle.SNOWFLAKE, (int) (12 * size), 0.12 * size, force);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, Vfx.n(ctx(), (int) (8 * size)), 0.3 * size,
                0.3 * size, 0.3 * size, 0, PALETTE.glow(1.2f), force);
        Vfx.ring(ctx(), at, 0.8 * size, 12, 0, Particle.DUST, RIME, force);
        Fx.sound(at, Sound.BLOCK_GLASS_BREAK, 0.9f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.6f, 1.6f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ ultimate: absolute zero

    @Override
    public ActivationResult onUltimate() {
        double radius = params().num("ult_radius");
        Location origin = player().getLocation();
        List<LivingEntity> targets = ctx().enemiesNear(player(), origin, radius);
        if (targets.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        long duration = Math.max(1, params().ticks("ult_duration"));

        Fx.sound(origin, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 0.5f);
        Fx.sound(origin, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.2f, 0.6f);
        Fx.sound(origin, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.6f);
        Fx.sound(origin, Sound.BLOCK_POWDER_SNOW_BREAK, 1.2f, 0.6f + Fx.jitter(0.05f));
        castAbsoluteZero(origin, radius);

        List<LivingEntity> frozen = List.copyOf(targets);
        for (LivingEntity target : frozen) {
            freeze(target, duration, 1.0, true);
            Vfx.trail(origin.clone().add(0, 1, 0), Fx.body(target), PALETTE.edge(), SHOCKWAVE_TICKS, true);
        }
        player().sendActionBar(Text.mm("<#8EDBFF>❄ " + frozen.size() + "명 빙결!</#8EDBFF>"));

        double damage = params().num("ult_damage");
        ctx().runLater(player(), duration, () -> {
            for (LivingEntity target : frozen) {
                if (Combat.isTargetable(ctx(), player(), target)) {
                    shatterPrison(target);
                    Combat.hit(ctx(), player(), target, damage);
                }
            }
        });
        return ActivationResult.success();
    }

    /**
     * The cast: a cold flash and a snow spiral around the owner, a frost ring racing out to the edge and a fan of ice
     * shards gliding outward along the ground.
     */
    private void castAbsoluteZero(Location origin, double radius) {
        World world = origin.getWorld();
        world.spawnParticle(Particle.FLASH, origin.clone().add(0, 1, 0), 1, 0, 0, 0, 0, PALETTE.core(), true);
        Vfx.helix(ctx(), origin, 0.8, 2.6, 1.5, 2, 14, 0, Particle.SNOWFLAKE, null, true);
        Vfx.helix(ctx(), origin, 0.8, 2.6, 1.5, 2, 10, Math.PI / 2, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(1.1f), true);
        Location ground = origin.clone().add(0, 0.3, 0);
        Loop.repeat(ctx(), player(), 0, 1, SHOCKWAVE_TICKS, tick -> {
            double r = radius * (tick + 1) / SHOCKWAVE_TICKS;
            int points = (int) Math.max(12, Math.min(44, r * 5.5));
            Vfx.ring(ctx(), ground, r, points, tick * 0.2, Particle.SNOWFLAKE, null, true);
            Vfx.ring(ctx(), ground, r - 0.2, points, tick * 0.2 + 0.1, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.3f), true);
            Vfx.ring(ctx(), ground.clone().subtract(0, 0.2, 0), r - 0.45, points / 2, tick * 0.2 + 0.2,
                    Particle.DUST, RIME, true);
            return true;
        });
        launchShards(origin, radius);
    }

    /** Thin ice shards fan out from the owner, glide to the edge of the ultimate and melt away. */
    private void launchShards(Location origin, double radius) {
        long lifetime = SHARD_FLIGHT_TICKS + 6;
        if (!reserveDisplays(SHARDS, lifetime)) {
            return;
        }
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (int i = 0; i < SHARDS; i++) {
            double angle = phase + i * (Math.PI * 2 / SHARDS);
            Vector out = new Vector(Math.cos(angle), 0, Math.sin(angle));
            float yaw = (float) Math.toDegrees(Math.atan2(-out.getX(), out.getZ()));
            Location start = flat(origin).add(out.clone().multiply(0.8)).add(0, 0.4, 0);
            BlockDisplay shard = Displays.block(ctx(), player(), start, i % 2 == 0 ? PACKED_ICE : BLUE_ICE,
                    Displays.transform(0, 0, 0, 0.13f, 0.13f, 1.1f, yaw, -28, 45), lifetime);
            shard.setTeleportDuration((int) SHARD_FLIGHT_TICKS);
            Location end = start.clone().add(out.multiply(radius - 1.0));
            ctx().runLater(player(), 1, () -> {
                if (shard.isValid()) {
                    shard.teleport(end);
                }
            });
            ctx().runLater(player(), SHARD_FLIGHT_TICKS + 1, () -> {
                if (shard.isValid()) {
                    Displays.shrinkAway(ctx(), player(), shard, 4);
                    end.getWorld().spawnParticle(Particle.SNOWFLAKE, end, Vfx.n(ctx(), 3), 0.2, 0.1, 0.2, 0.02,
                            null, true);
                }
            });
        }
    }

    /** The prison bursts: ice fragments, a snow bloom and chunks of ice flying off the prisoner. */
    private void shatterPrison(LivingEntity target) {
        Location body = Fx.body(target);
        World world = target.getWorld();
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 24), 0.6, target.getHeight() * 0.45, 0.6, 0.15,
                PACKED_ICE, true);
        iceBurst(body, 1.3f, true);
        throwChunks(body);
        Fx.sound(body, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.75f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.0f, 1.2f + Fx.jitter(0.1f));
    }

    /** A few ice chunks tumble outward and down from {@code center} and melt. */
    private void throwChunks(Location center) {
        long lifetime = CHUNK_FLIGHT_TICKS + 5;
        if (!reserveDisplays(CHUNKS, lifetime)) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double phase = random.nextDouble(Math.PI * 2);
        for (int i = 0; i < CHUNKS; i++) {
            double angle = phase + i * (Math.PI * 2 / CHUNKS) + random.nextDouble(-0.3, 0.3);
            float size = (float) random.nextDouble(0.22, 0.34);
            BlockDisplay chunk = Displays.block(ctx(), player(), flat(center), i % 2 == 0 ? CLEAR_ICE : PACKED_ICE,
                    Displays.scaled(size), lifetime);
            chunk.setTeleportDuration((int) CHUNK_FLIGHT_TICKS);
            Location end = flat(center).add(Math.cos(angle) * 1.6, -0.7, Math.sin(angle) * 1.6);
            float spinX = (float) random.nextDouble(90, 200);
            float spinY = (float) random.nextDouble(90, 200);
            ctx().runLater(player(), 1, () -> {
                if (chunk.isValid()) {
                    chunk.teleport(end);
                    Displays.animate(chunk, Displays.transform(0, 0, 0, size, size, size, spinY, spinX, 0),
                            (int) CHUNK_FLIGHT_TICKS);
                }
            });
            ctx().runLater(player(), CHUNK_FLIGHT_TICKS + 1, () -> {
                if (chunk.isValid()) {
                    Displays.shrinkAway(ctx(), player(), chunk, 3);
                }
            });
        }
    }

    // ------------------------------------------------------------------ freezing

    /**
     * Freezes {@code target}: movement slowed by {@code slow} (1 = cannot move), no jumping, frost overlay and ice
     * crystals on the body. With {@code imprison} the target is also walled in with temporary ice blocks and ice
     * creeps up its whole body.
     */
    private void freeze(LivingEntity target, long ticks, double slow, boolean imprison) {
        if (ticks <= 0) {
            return;
        }
        boolean grounded = imprison && centerInBlock(target);
        if (imprison) {
            target.setVelocity(new Vector());
        }
        ctx().addModifier(player(), target, Attribute.MOVEMENT_SPEED, -Math.min(1.0, Math.max(0.0, slow)),
                AttributeModifier.Operation.MULTIPLY_SCALAR_1, ticks);
        ctx().addModifier(player(), target, Attribute.JUMP_STRENGTH, -1.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1, ticks);
        // Vanilla frost overlay; stays below the fully-frozen threshold so it never deals vanilla freeze damage.
        int overlay = (int) Math.min(target.getMaxFreezeTicks() - 1L, ticks * 2 + 20);
        if (target.getFreezeTicks() < overlay) {
            target.setFreezeTicks(overlay);
        }
        if (imprison) {
            buildPrison(target, ticks, grounded);
        }

        BlockDisplay shell = imprison ? spawnIceShell(target, ticks + 2) : null;
        List<Crystal> crystals = growCrystals(target, imprison ? PRISON_CRYSTALS : SPEAR_CRYSTALS,
                imprison ? 1.2f : 1.0f, ticks + 6);

        Location body = Fx.body(target);
        target.getWorld().spawnParticle(Particle.SNOWFLAKE, body, Vfx.n(ctx(), 14), 0.35, 0.5, 0.35, 0.05);
        target.getWorld().spawnParticle(Particle.WAX_OFF, body, Vfx.n(ctx(), 5), 0.35, 0.5, 0.35, 0.4);
        Fx.sound(body, Sound.BLOCK_GLASS_PLACE, 1.0f, 0.7f + Fx.jitter(0.05f));

        long checks = Math.max(1, ticks / 2);
        Loop.repeat(ctx(), player(), 2, 2, checks, i -> {
            if (!Combat.isTargetable(ctx(), player(), target)
                    || !target.getWorld().equals(player().getWorld()) || i == checks - 1) {
                thaw(target, shell, crystals, imprison);
                return false;
            }
            Location feet = flat(target.getLocation());
            if (shell != null && shell.isValid()) {
                shell.teleport(feet);
            }
            for (Crystal crystal : crystals) {
                if (crystal.display().isValid()) {
                    crystal.display().teleport(feet.clone().add(crystal.offset()));
                }
            }
            if (i % 5 == 0) {
                Location ground = feet.clone().add(0, 0.1, 0);
                Vfx.ring(ctx(), ground, imprison ? 1.35 : 0.7, imprison ? 16 : 10, i * 0.3, Particle.DUST, RIME,
                        imprison);
                Vfx.ring(ctx(), ground, imprison ? 1.1 : 0.5, 6, i * 0.3 + 0.5, Particle.SNOWFLAKE, null, imprison);
            } else if (i % 2 == 0) {
                Vfx.dot(feet.clone().add(Fx.jitter(0.4f), target.getHeight() * 0.6, Fx.jitter(0.4f)),
                        Particle.SNOWFLAKE, null, false);
            }
            return true;
        });
    }

    /**
     * The freeze ends: the crystals sink back into the ground with a faint crack. A prison's shell and crystals
     * simply vanish, since the prison shatters in a burst of ice at the same moment.
     */
    private void thaw(LivingEntity target, @Nullable BlockDisplay shell, List<Crystal> crystals, boolean imprison) {
        if (shell != null && shell.isValid()) {
            shell.remove();
        }
        for (Crystal crystal : crystals) {
            BlockDisplay display = crystal.display();
            if (!display.isValid()) {
                continue;
            }
            if (imprison) {
                display.remove();
            } else {
                Displays.shrinkAway(ctx(), player(), display, 4);
            }
        }
        if (!imprison && target.isValid()) {
            Location feet = target.getLocation().add(0, 0.4, 0);
            Fx.sound(feet, Sound.BLOCK_GLASS_BREAK, 0.4f, 1.9f + Fx.jitter(0.1f));
            feet.getWorld().spawnParticle(Particle.SNOWFLAKE, feet, Vfx.n(ctx(), 6), 0.3, 0.2, 0.3, 0.03);
        }
    }

    /** Clear ice that creeps up from the feet until it encases the target's whole body. */
    private @Nullable BlockDisplay spawnIceShell(LivingEntity target, long lifetime) {
        if (!reserveDisplays(1, lifetime)) {
            return null;
        }
        float width = (float) target.getBoundingBox().getWidthX() + 0.25f;
        float height = (float) target.getHeight() + 0.2f;
        BlockDisplay shell = Displays.block(ctx(), player(), flat(target.getLocation()), CLEAR_ICE,
                shellShape(width, 0.02f), lifetime);
        Displays.animateNextTick(ctx(), player(), shell, shellShape(width, height), (int) SHELL_GROW_TICKS);
        return shell;
    }

    /** A box of {@code width} x {@code height} standing on the entity location (growing upward from the feet). */
    private static Transformation shellShape(float width, float height) {
        return new Transformation(new Vector3f(-width / 2, -0.05f, -width / 2), new Quaternionf(),
                new Vector3f(width, height, width), new Quaternionf());
    }

    /** One ice crystal frozen onto a target: its display and where it sits relative to the target's feet. */
    private record Crystal(BlockDisplay display, Vector offset) {
    }

    /**
     * Ice crystals jutting out of the ground around the target's feet, tilted outward and growing in over a few
     * ticks. Fewer (or none) when the display budget is spent.
     */
    private List<Crystal> growCrystals(LivingEntity target, int count, float size, long lifetime) {
        int granted = count;
        while (granted > 0 && !reserveDisplays(granted, lifetime)) {
            granted--;
        }
        List<Crystal> crystals = new ArrayList<>(granted);
        if (granted == 0) {
            return crystals;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double spread = target.getWidth() * 0.5 + 0.12;
        double phase = random.nextDouble(Math.PI * 2);
        Location feet = flat(target.getLocation());
        for (int i = 0; i < granted; i++) {
            double angle = phase + i * (Math.PI * 2 / granted) + random.nextDouble(-0.35, 0.35);
            Vector offset = new Vector(Math.cos(angle) * spread, 0, Math.sin(angle) * spread);
            // Tilt each crystal away from the target: a display's yaw points its tilt along that compass direction.
            float yaw = (float) Math.toDegrees(Math.atan2(-offset.getX(), offset.getZ()));
            float tilt = (float) random.nextDouble(18, 38);
            float width = size * (float) random.nextDouble(0.16, 0.26);
            float height = size * (float) random.nextDouble(0.6, 1.0);
            BlockDisplay display = Displays.block(ctx(), player(), feet.clone().add(offset),
                    i % 2 == 0 ? PACKED_ICE : BLUE_ICE,
                    Displays.transform(0, 0, 0, width, 0.01f, width, yaw, tilt, 0), lifetime);
            Displays.animateNextTick(ctx(), player(), display,
                    Displays.transform(0, height * 0.35f, 0, width, height, width, yaw, tilt, 0),
                    (int) CRYSTAL_GROW_TICKS);
            crystals.add(new Crystal(display, offset));
        }
        return crystals;
    }

    /**
     * Moves the target to the middle of its block column when that space is free (and inside the arena), so the
     * prison fits around it. A target standing on the ground is only moved if it would still stand on solid, safe
     * ground there: an enemy at the edge of a block is never shifted over a hole or lava.
     *
     * @return whether the target was on the ground (it then needs no prison floor)
     */
    private boolean centerInBlock(LivingEntity target) {
        boolean grounded = Motion.isGrounded(target);
        Location location = target.getLocation();
        double dx = location.getBlockX() + 0.5 - location.getX();
        double dz = location.getBlockZ() + 0.5 - location.getZ();
        if (Math.abs(dx) < 0.05 && Math.abs(dz) < 0.05) {
            return grounded;
        }
        World world = target.getWorld();
        BoundingBox moved = target.getBoundingBox().shift(dx, 0, dz);
        Location centered = location.clone().add(dx, 0, dz);
        if (world.hasCollisionsIn(moved.clone().expand(-0.01)) || !ctx().isInsideArena(centered)
                || (grounded && !isSupported(world, moved, centered))) {
            return grounded;
        }
        target.teleport(centered);
        return grounded;
    }

    /**
     * True if a body with bounding box {@code box} and feet at {@code feet} would stand on a solid block (the same
     * thin slab {@link Motion#isGrounded} checks) that is neither a fluid nor a hazard.
     */
    private static boolean isSupported(World world, BoundingBox box, Location feet) {
        BoundingBox below = new BoundingBox(box.getMinX() + 0.05, box.getMinY() - 0.15, box.getMinZ() + 0.05,
                box.getMaxX() - 0.05, box.getMinY() - 0.01, box.getMaxZ() - 0.05);
        if (!world.hasCollisionsIn(below)) {
            return false;
        }
        Block ground = feet.clone().subtract(0, 0.01, 0).getBlock();
        Block body = feet.getBlock();
        return !ground.isLiquid() && !Spots.isHazard(ground) && !body.isLiquid() && !Spots.isHazard(body);
    }

    /**
     * Walls the target in with temporary ice: a closed ring at foot level, corner pillars up to its head (the gaps
     * between them are windows to attack through), a roof, and a floor when it is airborne. Cells that are not
     * replaceable or that overlap any living entity are skipped.
     */
    private void buildPrison(LivingEntity target, long ticks, boolean grounded) {
        World world = target.getWorld();
        BoundingBox box = target.getBoundingBox();
        int bx = (int) Math.floor(box.getCenterX());
        int bz = (int) Math.floor(box.getCenterZ());
        int bottom = (int) Math.floor(box.getMinY());
        int top = (int) Math.floor(box.getMaxY() - 1.0E-3);

        BlockData pane = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
        for (int y = bottom; y <= top; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean inside = dx == 0 && dz == 0;
                    boolean corner = dx != 0 && dz != 0;
                    boolean window = y != bottom && !corner;
                    if (inside || window) {
                        continue;
                    }
                    placeIce(world.getBlockAt(bx + dx, y, bz + dz), corner ? PACKED_ICE : pane, ticks);
                }
            }
        }
        placeIce(world.getBlockAt(bx, top + 1, bz), PACKED_ICE, ticks);
        if (!grounded) {
            placeIce(world.getBlockAt(bx, bottom - 1, bz), PACKED_ICE, ticks);
        }
    }

    private void placeIce(Block block, BlockData data, long ticks) {
        if (isOccupied(block)) {
            return;
        }
        ctx().placeTempBlock(block, data, ticks);
    }

    private static boolean isOccupied(Block block) {
        BoundingBox cell = BoundingBox.of(block);
        return !block.getWorld().getNearbyEntities(cell, entity -> entity instanceof LivingEntity).isEmpty();
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

    /** {@code count} (scaled) particles flying outward from {@code at} evenly in all directions at {@code speed}. */
    private void bloom(Location at, Particle particle, int count, double speed, boolean force) {
        World world = at.getWorld();
        int total = Vfx.n(ctx(), count);
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < total; i++) {
            double y = 1 - (i + 0.5) * 2.0 / total;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double angle = i * golden;
            world.spawnParticle(particle, at.getX(), at.getY(), at.getZ(), 0, Math.cos(angle) * r, y,
                    Math.sin(angle) * r, speed, null, force);
        }
    }

    /** A ring of {@code particle} around the aim axis {@code direction}, centred on {@code center}. */
    private <T> void aimRing(Location center, Vector direction, double radius, int points, double phase,
                             Particle particle, @Nullable T data) {
        Vector axis = direction.clone().normalize();
        Vector side = axis.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0E-6) {
            side = new Vector(1, 0, 0);
        }
        side.normalize();
        Vector up = side.clone().crossProduct(axis).normalize();
        int count = Vfx.n(ctx(), points);
        for (int i = 0; i < count; i++) {
            double angle = phase + i * (Math.PI * 2 / count);
            Vector offset = side.clone().multiply(Math.cos(angle) * radius)
                    .add(up.clone().multiply(Math.sin(angle) * radius));
            Vfx.dot(center.clone().add(offset), particle, data, false);
        }
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
