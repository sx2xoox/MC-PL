package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.List;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
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
                            + "얼음이 깨질 때 {ult_damage} 피해를 줍니다.")
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
    private static final float SPEAR_THICKNESS = 0.14f;
    private static final float SPEAR_LENGTH = 1.5f;
    /** Ticks of the shockwave ring expanding from the owner when the ultimate is cast. */
    private static final int SHOCKWAVE_TICKS = 8;

    private static final Particle.DustOptions FROST_DUST = new Particle.DustOptions(Color.fromRGB(170, 230, 255), 1.1f);
    private static final Particle.DustTransition ICE_TRAIL =
            new Particle.DustTransition(Color.fromRGB(255, 255, 255), Color.fromRGB(110, 190, 255), 0.9f);

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
        Location body = attacker.getLocation().add(0, attacker.getHeight() * 0.5, 0);
        attacker.getWorld().spawnParticle(Particle.SNOWFLAKE, body, 8, 0.25, 0.45, 0.25, 0.02);
        Fx.sound(body, Sound.BLOCK_POWDER_SNOW_HIT, 0.8f, 1.2f + Fx.jitter(0.1f));
    }

    private static boolean isFreezeDamage(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.FREEZE) {
            return true;
        }
        Tag<DamageType> freezing = DamageTypeTags.IS_FREEZING;
        return freezing != null && freezing.isTagged(event.getDamageSource().getDamageType());
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
        eye.getWorld().spawnParticle(Particle.SNOWFLAKE, eye.clone().add(direction.clone().multiply(0.8)), 6,
                0.1, 0.1, 0.1, 0.03);
        return ActivationResult.success();
    }

    /** A simulated ice spear: a thin ice crystal flying straight until it hits an enemy or a block. */
    private final class Spear implements LongPredicate {

        private final World world;
        private final Location position;
        private final Vector direction;
        private final BlockDisplay visual;
        private double remaining;

        Spear(Location eye, Vector direction) {
            this.world = eye.getWorld();
            this.direction = direction.clone().normalize();
            this.position = eye.clone();
            this.position.setDirection(this.direction);
            this.remaining = params().num("spear_range");
            Location spawn = this.position.clone().add(this.direction.clone().multiply(1.2)).subtract(0, 0.15, 0);
            BlockData ice = Material.PACKED_ICE.createBlockData();
            this.visual = ctx().track(player(), world.spawn(spawn, BlockDisplay.class, display -> {
                display.setPersistent(false);
                display.setBlock(ice);
                display.setBrightness(new Display.Brightness(15, 15));
                display.setTeleportDuration(1);
                display.setTransformation(spearShape());
            }));
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

            position.add(direction.clone().multiply(step));
            remaining -= step;
            if (visual.isValid()) {
                visual.teleport(position);
            }
            world.spawnParticle(Particle.SNOWFLAKE, position, 2, 0.06, 0.06, 0.06, 0.005);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, position, 1, 0.05, 0.05, 0.05, 0, ICE_TRAIL);
            if (remaining <= 1.0E-3) {
                shatter(position, null);
                return false;
            }
            return true;
        }

        private void shatter(Location at, @Nullable LivingEntity target) {
            visual.remove();
            world.spawnParticle(Particle.BLOCK, at, 14, 0.2, 0.2, 0.2, 0.1, Material.PACKED_ICE.createBlockData());
            world.spawnParticle(Particle.SNOWFLAKE, at, 10, 0.15, 0.15, 0.15, 0.06);
            Fx.sound(at, Sound.BLOCK_GLASS_BREAK, 0.9f, 1.5f + Fx.jitter(0.1f));
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

    /**
     * A thin crystal centred on the display's origin with its long axis on the facing direction, rolled 45 degrees
     * so it shows a diamond profile. The block model spans 0..1, so the translation moves its rolled centre
     * (0, thickness / sqrt 2, length / 2) back onto the origin.
     */
    private static Transformation spearShape() {
        Quaternionf roll = new Quaternionf().rotateZ((float) (Math.PI / 4));
        Vector3f translation = new Vector3f(0, -SPEAR_THICKNESS / (float) Math.sqrt(2), -SPEAR_LENGTH / 2);
        Vector3f scale = new Vector3f(SPEAR_THICKNESS, SPEAR_THICKNESS, SPEAR_LENGTH);
        return new Transformation(translation, roll, scale, new Quaternionf());
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
        Loop.repeat(ctx(), player(), 0, 1, SHOCKWAVE_TICKS, tick -> {
            drawShockwave(origin, radius * (tick + 1) / SHOCKWAVE_TICKS);
            return true;
        });

        List<LivingEntity> frozen = List.copyOf(targets);
        for (LivingEntity target : frozen) {
            freeze(target, duration, 1.0, true);
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

    private void drawShockwave(Location origin, double radius) {
        World world = origin.getWorld();
        int points = (int) Math.max(12, Math.min(48, radius * 6));
        double y = origin.getY() + 0.3;
        for (int i = 0; i < points; i++) {
            double angle = i * (Math.PI * 2 / points);
            double x = origin.getX() + Math.cos(angle) * radius;
            double z = origin.getZ() + Math.sin(angle) * radius;
            if (i % 2 == 0) {
                world.spawnParticle(Particle.SNOWFLAKE, x, y, z, 1, 0, 0.05, 0, 0);
            } else {
                world.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0.05, 0, 0, FROST_DUST);
            }
        }
    }

    private void shatterPrison(LivingEntity target) {
        Location body = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        World world = target.getWorld();
        world.spawnParticle(Particle.BLOCK, body, 40, 0.6, target.getHeight() * 0.45, 0.6, 0.15,
                Material.PACKED_ICE.createBlockData());
        world.spawnParticle(Particle.SNOWFLAKE, body, 20, 0.5, 0.6, 0.5, 0.08);
        Fx.sound(body, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.75f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.0f, 1.2f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ freezing

    /**
     * Freezes {@code target}: movement slowed by {@code slow} (1 = cannot move), no jumping, frost overlay and a
     * block of ice on the body. With {@code imprison} the target is also walled in with temporary ice blocks.
     */
    private void freeze(LivingEntity target, long ticks, double slow, boolean imprison) {
        if (ticks <= 0) {
            return;
        }
        if (imprison) {
            centerInBlock(target);
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
            buildPrison(target, ticks);
        }

        BlockDisplay ice = spawnIceShell(target, imprison);
        Location body = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        target.getWorld().spawnParticle(Particle.SNOWFLAKE, body, 16, 0.35, 0.5, 0.35, 0.05);
        Fx.sound(body, Sound.BLOCK_GLASS_PLACE, 1.0f, 0.7f + Fx.jitter(0.05f));

        long checks = Math.max(1, ticks / 2);
        Loop.repeat(ctx(), player(), 2, 2, checks, i -> {
            if (!Combat.isTargetable(ctx(), player(), target)
                    || !target.getWorld().equals(ice.getWorld()) || i == checks - 1) {
                ice.remove();
                return false;
            }
            Location feet = target.getLocation();
            feet.setYaw(0);
            feet.setPitch(0);
            if (ice.isValid()) {
                ice.teleport(feet);
            }
            if (i % 2 == 0) {
                target.getWorld().spawnParticle(Particle.SNOWFLAKE, feet.add(0, target.getHeight() * 0.5, 0), 2,
                        0.3, 0.5, 0.3, 0.01);
            }
            return true;
        });
    }

    /** A translucent ice block around the target's feet (frozen) or its whole body (imprisoned). */
    private BlockDisplay spawnIceShell(LivingEntity target, boolean wholeBody) {
        Location feet = target.getLocation();
        feet.setYaw(0);
        feet.setPitch(0);
        float width = (float) target.getBoundingBox().getWidthX() + 0.25f;
        float height = wholeBody ? (float) target.getHeight() + 0.2f : 0.55f;
        BlockData ice = Material.ICE.createBlockData();
        return ctx().track(player(), target.getWorld().spawn(feet, BlockDisplay.class, display -> {
            display.setPersistent(false);
            display.setBlock(ice);
            display.setTeleportDuration(2);
            display.setTransformation(new Transformation(new Vector3f(-width / 2, -0.05f, -width / 2),
                    new Quaternionf(), new Vector3f(width, height, width), new Quaternionf()));
        }));
    }

    /**
     * Moves the target to the middle of its block column when that space is free (and inside the arena), so the
     * prison fits around it.
     */
    private void centerInBlock(LivingEntity target) {
        Location location = target.getLocation();
        double dx = location.getBlockX() + 0.5 - location.getX();
        double dz = location.getBlockZ() + 0.5 - location.getZ();
        if (Math.abs(dx) < 0.05 && Math.abs(dz) < 0.05) {
            return;
        }
        BoundingBox moved = target.getBoundingBox().shift(dx, 0, dz).expand(-0.01);
        Location centered = location.clone().add(dx, 0, dz);
        if (target.getWorld().hasCollisionsIn(moved) || !ctx().isInsideArena(centered)) {
            return;
        }
        target.teleport(centered);
    }

    /**
     * Walls the target in with temporary ice: the 8 surrounding columns over its height, a roof, and a floor when
     * it is airborne. Cells that are not replaceable or that overlap any living entity are skipped.
     */
    private void buildPrison(LivingEntity target, long ticks) {
        World world = target.getWorld();
        BoundingBox box = target.getBoundingBox();
        int bx = (int) Math.floor(box.getCenterX());
        int bz = (int) Math.floor(box.getCenterZ());
        int bottom = (int) Math.floor(box.getMinY());
        int top = (int) Math.floor(box.getMaxY() - 1.0E-3);

        BlockData pillar = Material.PACKED_ICE.createBlockData();
        BlockData pane = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
        for (int y = bottom; y <= top; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    boolean corner = dx != 0 && dz != 0;
                    placeIce(world.getBlockAt(bx + dx, y, bz + dz), corner ? pillar : pane, ticks);
                }
            }
        }
        placeIce(world.getBlockAt(bx, top + 1, bz), pillar, ticks);
        placeIce(world.getBlockAt(bx, bottom - 1, bz), pillar, ticks);
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
}
