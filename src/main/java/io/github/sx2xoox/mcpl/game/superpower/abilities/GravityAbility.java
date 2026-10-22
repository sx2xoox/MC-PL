package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 중력술사 — bends gravity. Melee hits pop enemies into the air for juggle combos, the active drags an aimed enemy
 * right in front of the owner, and the ultimate opens a black hole that swallows nearby enemies before it collapses.
 */
public final class GravityAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("gravity", "중력술사", Material.HEAVY_CORE)
            .color(TextColor.color(0x7D6BFF))
            .difficulty(2)
            .passive("무중력 타격",
                    "근접 공격한 적을 {lift_height}블록 띄웁니다(같은 적은 {lift_cooldown}초마다). "
                            + "띄운 적이 공중에 있는 동안 근접 피해가 {air_bonus}% 증가합니다.")
            .active("끌어당기기",
                    "조준한 적({pull_range}블록 이내)을 중력으로 붙잡아 내 앞으로 끌어오고 {pull_slow}초간 구속 II를 겁니다.",
                    8)
            .ultimate("블랙홀",
                    "조준한 지점({ult_range}블록 이내)에 {ult_duration}초간 블랙홀을 열어 반경 {ult_radius}블록의 적을 "
                            + "빨아들입니다. 블랙홀이 붕괴하면 반경 {blast_radius}블록의 적에게 {blast_damage} 피해를 주고 "
                            + "밀쳐냅니다.")
            .param("lift_height", 1.5)
            .param("lift_cooldown", 1)
            .param("air_bonus", 20)
            .param("pull_range", 25)
            .param("pull_slow", 1)
            .param("ult_range", 30)
            .param("ult_duration", 3)
            .param("ult_radius", 8)
            .param("blast_radius", 4.5)
            .param("blast_damage", 8)
            .build();

    private static final String COLOR_TAG = "#7D6BFF";
    private static final Color VIOLET = Color.fromRGB(0x9D, 0x7B, 0xFF);
    private static final Particle.DustOptions VIOLET_DUST = new Particle.DustOptions(VIOLET, 1.0f);
    private static final Particle.DustOptions EDGE_DUST = new Particle.DustOptions(Color.fromRGB(0x5B, 0x2A, 0xA8), 1.3f);
    private static final Particle.DustTransition DISK_DUST =
            new Particle.DustTransition(Color.fromRGB(0xF0, 0xB8, 0xFF), Color.fromRGB(0x2A, 0x05, 0x4A), 1.1f);
    private static final Color FLASH_COLOR = Color.fromRGB(0xC9, 0x9B, 0xFF);

    /** Hard cap for every velocity this ability applies, so nobody is flung out of the arena. */
    private static final double MAX_SPEED = 2.5;

    // passive
    /** Horizontal push that replaces vanilla knockback on a lifted enemy, keeping it in combo range. */
    private static final double LIFT_PUSH = 0.25;
    /** Upper bound for how long a lifted enemy counts as juggled (it ends earlier once it lands). */
    private static final long JUGGLE_WINDOW_TICKS = 40;
    private static final long CLEANUP_PERIOD_TICKS = 200;

    // active
    private static final int PULL_MAX_TICKS = 24;
    private static final double PULL_SPEED = 1.6;
    private static final double PULL_ARRIVE_DISTANCE = 1.4;
    /** Where in front of the owner the pulled enemy is dropped. */
    private static final double PULL_STOP_DISTANCE = 1.8;

    // ultimate
    /** Core height above the ground: about chest height, so swallowed enemies are held standing on the ground. */
    private static final double CORE_HEIGHT = 1.1;
    private static final int GROUND_SNAP = 8;
    private static final float CORE_SIZE = 1.15f;
    private static final float SHELL_SIZE = 1.75f;
    private static final int POSE_PERIOD = 3;
    private static final int GROW_TICKS = 9;
    private static final int COLLAPSE_TICKS = 6;
    /** The sonic charge sound is about 1.6 s long; start it so it peaks when the black hole collapses. */
    private static final int CHARGE_SOUND_LEAD = 32;
    private static final double SUCK_MIN = 0.16;
    private static final double SUCK_MAX = 0.5;
    private static final double SWIRL = 0.1;
    /** Upward pull cap: enemies below the core are drawn up gently instead of being launched into the air. */
    private static final double MAX_SUCK_LIFT = 0.2;
    private static final double CORE_HOLD_DISTANCE = 0.7;
    private static final double BLAST_PUSH = 1.3;
    private static final double BLAST_LIFT = 0.6;
    private static final Vector3f CORE_AXIS = new Vector3f(0.35f, 1f, 0.2f).normalize();
    private static final Vector3f SHELL_AXIS = new Vector3f(-0.4f, 1f, 0.3f).normalize();

    /** Enemies this owner has hit or lifted, by entity id. */
    private final Map<UUID, Juggle> juggles = new HashMap<>();

    /** Per-enemy combo state: lift cooldown, the airborne window and the running combo count. */
    private static final class Juggle {
        long nextLift;
        long airborneUntil;
        int combo;
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        juggles.clear();
    }

    @Override
    public void onTick(long tick) {
        if (tick % CLEANUP_PERIOD_TICKS != 0 || juggles.isEmpty()) {
            return;
        }
        long now = ctx().currentTick();
        juggles.values().removeIf(juggle -> juggle.nextLift <= now && juggle.airborneUntil <= now);
    }

    // ------------------------------------------------------------------ passive: weightless strike

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || ctx().isAbilityDamage() || !player().equals(event.getDamager())
                || event.getCause() != DamageCause.ENTITY_ATTACK || !ctx().isEnemy(player(), victim)) {
            return;
        }
        long now = ctx().currentTick();
        Juggle juggle = juggles.computeIfAbsent(victim.getUniqueId(), id -> new Juggle());
        if (now < juggle.airborneUntil && !(Motion.isGrounded(victim) || victim.isInWater())) {
            double bonus = Math.max(0, params().num("air_bonus")) / 100.0;
            event.setDamage(event.getDamage() * (1 + bonus));
            juggle.combo++;
            comboFeedback(victim, juggle.combo);
        } else {
            juggle.combo = 1;
        }
        if (now >= juggle.nextLift) {
            juggle.nextLift = now + params().ticks("lift_cooldown");
            double height = params().num("lift_height");
            // Vanilla knockback is applied after this event; lift on the next tick so it is not overwritten.
            ctx().runLater(player(), 1, () -> lift(victim, height));
        }
    }

    private void lift(LivingEntity victim, double height) {
        if (!Combat.isTargetable(ctx(), player(), victim) || height <= 0) {
            return;
        }
        Vector push = Motion.horizontal(victim.getLocation().toVector().subtract(player().getLocation().toVector()));
        push.multiply(LIFT_PUSH).setY(Motion.launchSpeed(height));
        if (!Motion.push(victim, push, MAX_SPEED)) {
            return;
        }
        markAirborne(victim);

        Location feet = victim.getLocation().add(0, 0.1, 0);
        Fx.ring(feet, 0.6, 10, 0, Particle.DUST, VIOLET_DUST);
        victim.getWorld().spawnParticle(Particle.REVERSE_PORTAL, feet, 8, 0.25, 0.1, 0.25, 0.04);
        Fx.sound(feet, Sound.ENTITY_SHULKER_BULLET_HIT, 0.7f, 1.4f + Fx.jitter(0.15f));
    }

    private void comboFeedback(LivingEntity victim, int combo) {
        Location body = Fx.body(victim);
        victim.getWorld().spawnParticle(Particle.ENCHANTED_HIT, body, 8, 0.3, 0.4, 0.3, 0.15);
        victim.getWorld().spawnParticle(Particle.DUST, body, 4, 0.3, 0.4, 0.3, 0, VIOLET_DUST);
        Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, (float) Math.min(2.0, 0.9 + 0.15 * combo));
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">공중 콤보 <white>×" + combo + "</white></" + COLOR_TAG + ">"));
    }

    /** Opens the juggle window: melee hits get the air bonus while the enemy is still off the ground. */
    private void markAirborne(LivingEntity victim) {
        Juggle juggle = juggles.computeIfAbsent(victim.getUniqueId(), id -> new Juggle());
        juggle.airborneUntil = ctx().currentTick() + JUGGLE_WINDOW_TICKS;
    }

    // ------------------------------------------------------------------ active: gravity pull

    @Override
    public ActivationResult onActive() {
        LivingEntity target = ctx().findTarget(player(), params().num("pull_range"));
        if (target == null) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        long slowTicks = params().ticks("pull_slow");
        Fx.sound(player().getEyeLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(Fx.body(target), Sound.ENTITY_SHULKER_SHOOT, 1.0f, 0.6f + Fx.jitter(0.05f));
        target.getWorld().spawnParticle(Particle.REVERSE_PORTAL, Fx.body(target), 16, 0.3, 0.5, 0.3, 0.05);
        Loop.repeat(ctx(), player(), 0, 1, PULL_MAX_TICKS, i -> pullStep(target, i, slowTicks));
        return ActivationResult.success();
    }

    /** One tick of the pull: steer the target towards the spot in front of the owner. False when finished. */
    private boolean pullStep(LivingEntity target, long tick, long slowTicks) {
        Player owner = player();
        if (!Combat.isTargetable(ctx(), player(), target) || !target.getWorld().equals(owner.getWorld())) {
            return false;
        }
        Vector to = pullDestination(owner).toVector().subtract(target.getLocation().toVector());
        double distance = to.length();
        if (distance <= PULL_ARRIVE_DISTANCE || tick >= PULL_MAX_TICKS - 1) {
            arrive(target, slowTicks);
            return false;
        }
        double speed = Math.min(PULL_SPEED, Math.max(0.3, distance * 0.45));
        Vector velocity = Motion.horizontal(to).multiply(speed);
        // Small hops keep the target off the ground (no ground friction) without launching it.
        boolean grounded = Motion.isGrounded(target) || target.isInWater();
        velocity.setY(Math.clamp(to.getY() * 0.3, -0.6, 0.6) + (grounded ? 0.22 : 0.0));
        target.setVelocity(Motion.clamp(velocity, MAX_SPEED));
        target.setFallDistance(0);

        Location body = Fx.body(target);
        World world = target.getWorld();
        world.spawnParticle(Particle.TRAIL, body, 2, 0.25, 0.4, 0.25, 0, new Particle.Trail(Fx.body(owner), VIOLET, 6));
        if (tick % 2 == 0) {
            world.spawnParticle(Particle.REVERSE_PORTAL, body, 3, 0.25, 0.4, 0.25, 0.02);
        }
        return true;
    }

    /** The spot in front of the owner where pulled enemies are dropped (the owner's spot if a wall is in the way). */
    private static Location pullDestination(Player owner) {
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontal(feet.getDirection());
        if (facing.lengthSquared() < 1.0E-6) {
            return feet;
        }
        RayTraceResult wall = owner.getWorld().rayTraceBlocks(feet.clone().add(0, 0.5, 0), facing,
                PULL_STOP_DISTANCE, FluidCollisionMode.NEVER, true);
        return wall != null ? feet : feet.add(facing.multiply(PULL_STOP_DISTANCE));
    }

    private void arrive(LivingEntity target, long slowTicks) {
        target.setVelocity(new Vector(0, Math.min(0, target.getVelocity().getY()), 0));
        target.setFallDistance(0);
        if (slowTicks > 0) {
            int duration = (int) Math.min(Integer.MAX_VALUE, slowTicks);
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, 1, false, true, true));
        }
        markAirborne(target);

        Location body = Fx.body(target);
        target.getWorld().spawnParticle(Particle.REVERSE_PORTAL, body, 20, 0.3, 0.5, 0.3, 0.08);
        Fx.ring(target.getLocation().add(0, 0.1, 0), 0.8, 12, 0, Particle.DUST, VIOLET_DUST);
        Fx.sound(body, Sound.BLOCK_HEAVY_CORE_PLACE, 1.0f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_SHULKER_BULLET_HIT, 0.8f, 0.8f);
    }

    // ------------------------------------------------------------------ ultimate: black hole

    @Override
    public ActivationResult onUltimate() {
        double radius = params().num("ult_radius");
        BlackHole hole = new BlackHole(aimPoint(params().num("ult_range")), radius);
        if (ctx().enemiesNear(player(), hole.center, radius).isEmpty()) {
            return ActivationResult.fail("블랙홀 범위(" + AbilityInfo.formatNumber(radius) + "블록) 안에 적이 없습니다");
        }
        hole.open();
        return ActivationResult.success();
    }

    /**
     * Where the black hole opens: under the aimed enemy, or where the view ray hits a block (or ends), snapped down to
     * the ground when there is ground close below.
     */
    private Location aimPoint(double range) {
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
                    : hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.4));
        }
        Double ground = groundBelow(point);
        if (ground != null) {
            point.setY(ground);
        }
        point.add(0, CORE_HEIGHT, 0);
        point.setYaw(0);
        point.setPitch(0);
        return point;
    }

    /** The top of the first solid block at most {@link #GROUND_SNAP} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = point.getBlockY();
        int end = Math.max(world.getMinHeight(), start - GROUND_SNAP);
        for (int y = Math.min(start, world.getMaxHeight() - 1); y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /** A black hole: pulls enemies in for its lifetime, then collapses into a blast. */
    private final class BlackHole implements LongPredicate {

        private final Location center;
        private final World world;
        private final double radius;
        private final double floorY;
        private final long life;
        private @Nullable ItemDisplay core;
        private @Nullable ItemDisplay shell;

        BlackHole(Location center, double radius) {
            this.center = center;
            this.world = center.getWorld();
            this.radius = radius;
            Double ground = groundBelow(center);
            this.floorY = (ground != null ? ground : center.getY() - CORE_HEIGHT) + 0.15;
            this.life = Math.max(20, params().ticks("ult_duration"));
        }

        void open() {
            core = spawnOrb(Material.BLACK_CONCRETE, new Display.Brightness(0, 0), true);
            shell = spawnOrb(Material.PURPLE_STAINED_GLASS, new Display.Brightness(15, 15), false);
            Fx.sound(center, Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.5f, 0.6f);
            Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 0.5f);
            Fx.sound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
            world.spawnParticle(Particle.REVERSE_PORTAL, center, 40, 0.2, 0.2, 0.2, 0.6);
            Loop.repeat(ctx(), player(), 0, 1, life + 1, this);
        }

        private ItemDisplay spawnOrb(Material material, Display.Brightness brightness, boolean glowing) {
            ItemStack item = ItemStack.of(material);
            return ctx().track(player(), world.spawn(center, ItemDisplay.class, display -> {
                display.setPersistent(false);
                display.setItemStack(item);
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                display.setBrightness(brightness);
                display.setViewRange(2.0f);
                display.setTransformation(orbPose(0.05f, 0, CORE_AXIS));
                if (glowing) {
                    display.setGlowing(true);
                    display.setGlowColorOverride(VIOLET);
                }
            }));
        }

        @Override
        public boolean test(long tick) {
            if (tick >= life) {
                collapse();
                return false;
            }
            if (tick % POSE_PERIOD == 1) {
                updatePose(tick + POSE_PERIOD);
            }
            if (tick == life - CHARGE_SOUND_LEAD) {
                Fx.sound(center, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, 1.1f);
            }
            suck();
            drawSwirl(tick);
            if (tick % 20 == 0) {
                Fx.sound(center, Sound.BLOCK_PORTAL_AMBIENT, 0.8f, 0.5f + 0.6f * tick / life);
            }
            return true;
        }

        /** Sets the orbs' pose for {@code targetTick}, interpolated over the next {@link #POSE_PERIOD} ticks. */
        private void updatePose(long targetTick) {
            float growth = Math.min(1f, (float) targetTick / GROW_TICKS);
            long untilCollapse = life - targetTick;
            float shrink = untilCollapse >= COLLAPSE_TICKS ? 1f : Math.max(0.03f, (float) untilCollapse / COLLAPSE_TICKS);
            float scale = growth * shrink;
            float angle = targetTick * 0.15f;
            pose(core, orbPose(CORE_SIZE * scale, angle, CORE_AXIS));
            pose(shell, orbPose(SHELL_SIZE * scale, -angle * 0.7f, SHELL_AXIS));
        }

        private void pose(@Nullable ItemDisplay display, Transformation transformation) {
            if (display == null || !display.isValid()) {
                return;
            }
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(POSE_PERIOD);
            display.setTransformation(transformation);
        }

        /** The pull is the black hole's crowd control, not knockback: it ignores knockback resistance by design. */
        private void suck() {
            for (LivingEntity enemy : ctx().enemiesNear(player(), center, radius)) {
                Vector to = center.toVector().subtract(Fx.body(enemy).toVector());
                double distance = to.length();
                Vector velocity;
                if (distance < CORE_HOLD_DISTANCE) {
                    // Held in the core; never pushed upwards, so grounded enemies stay grounded (no hovering).
                    velocity = new Vector(to.getX() * 0.4, Math.min(0, to.getY()) * 0.4, to.getZ() * 0.4);
                } else {
                    double closeness = 1 - Math.min(1, distance / radius);
                    Vector direction = to.multiply(1 / distance);
                    Vector swirl = new Vector(-direction.getZ(), 0, direction.getX()).multiply(SWIRL);
                    velocity = direction.multiply(SUCK_MIN + (SUCK_MAX - SUCK_MIN) * closeness).add(swirl);
                    velocity.setY(Math.clamp(velocity.getY(), -0.35, MAX_SUCK_LIFT));
                }
                enemy.setVelocity(Motion.clamp(velocity, MAX_SPEED));
                enemy.setFallDistance(0);
            }
        }

        private void drawSwirl(long tick) {
            double base = tick * 0.35;
            for (int arm = 0; arm < 2; arm++) {
                for (int k = 0; k < 6; k++) {
                    double r = 0.9 + k * 0.45;
                    double a = base + arm * Math.PI + k * 0.55;
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, center.getX() + Math.cos(a) * r,
                            center.getY() + Math.sin(a) * r * 0.12, center.getZ() + Math.sin(a) * r,
                            1, 0, 0, 0, 0, DISK_DUST);
                }
            }
            if (tick % 2 == 0) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < 4; i++) {
                    Vector offset = new Vector(random.nextGaussian(), random.nextGaussian() * 0.6, random.nextGaussian())
                            .normalize().multiply(2.5 + random.nextDouble() * radius * 0.4);
                    // Portal particles with count 0 fly from (center + offset) into the center.
                    world.spawnParticle(Particle.PORTAL, center, 0, offset.getX(), offset.getY(), offset.getZ(), 1.0);
                }
            }
            if (tick % 5 == 0) {
                Location edge = center.clone();
                edge.setY(floorY);
                Fx.ring(edge, radius, (int) Math.min(48, Math.max(16, radius * 3.5)), 0, Particle.DUST, EDGE_DUST);
            }
        }

        private void collapse() {
            removeOrbs();
            world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
            world.spawnParticle(Particle.FLASH, center, 1, 0, 0, 0, 0, FLASH_COLOR);
            world.spawnParticle(Particle.REVERSE_PORTAL, center, 70, 0.3, 0.3, 0.3, 1.0);
            world.spawnParticle(Particle.SONIC_BOOM, center, 1);
            double blastRadius = params().num("blast_radius");
            Location edge = center.clone();
            edge.setY(floorY);
            int points = (int) Math.min(40, Math.max(14, blastRadius * 5));
            Fx.ring(edge, blastRadius, points, 0, Particle.DUST, VIOLET_DUST);
            Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.6f);
            Fx.sound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 1.3f);

            double damage = params().num("blast_damage");
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(player(), center, blastRadius)) {
                Combat.hit(ctx(), player(), enemy, damage);
                hits++;
                if (!Combat.isTargetable(ctx(), player(), enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                    away = new Vector(Math.cos(angle), 0, Math.sin(angle));
                }
                // The blast is knockback: resistant targets (giant Titan, Earth) are pushed less or not at all.
                if (Motion.push(enemy, away.multiply(BLAST_PUSH).setY(BLAST_LIFT), MAX_SPEED)) {
                    markAirborne(enemy);
                }
            }
            if (hits > 0 && player().isOnline()) {
                player().sendActionBar(Text.mm("<" + COLOR_TAG + ">블랙홀 붕괴 <white>" + hits + "명 적중!</white></"
                        + COLOR_TAG + ">"));
            }
        }

        private void removeOrbs() {
            if (core != null) {
                core.remove();
            }
            if (shell != null) {
                shell.remove();
            }
        }
    }

    /** A cube centered on the display origin (item displays are centered), rotated around {@code axis}. */
    private static Transformation orbPose(float size, float angle, Vector3f axis) {
        Quaternionf rotation = new Quaternionf().rotateAxis(angle, axis);
        return new Transformation(new Vector3f(), rotation, new Vector3f(size, size, size), new Quaternionf());
    }
}
