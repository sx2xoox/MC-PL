package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 화염술사 — a straightforward burner. Immune to fire and sets melee victims ablaze; the active fires a quick
 * three-shot fireball volley, the ultimate wraps the owner in a fire vortex that follows them around.
 */
public final class PyroAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("pyro", "화염술사", Material.BLAZE_POWDER)
            .color(TextColor.color(0xFF7A2E))
            .difficulty(1)
            .passive("불꽃의 몸",
                    "불과 용암 같은 화염 피해를 받지 않습니다. 근접 공격한 적을 {melee_burn}초간 불태웁니다.")
            .active("화염구",
                    "화염구 {shots}발을 빠르게 연사합니다. 화염구가 터지면 반경 {fireball_radius}블록의 적에게 "
                            + "{fireball_damage} 피해를 주고 {fireball_burn}초간 불태우며, 마지막 화염구는 적을 밀쳐냅니다.",
                    8)
            .ultimate("불바다",
                    "{ult_duration}초간 나를 따라다니는 반경 {ult_radius}블록의 화염 소용돌이를 일으킵니다. "
                            + "안에 있는 적은 {ult_interval}초마다 {ult_damage} 피해를 입고 불탑니다.")
            .param("melee_burn", 2)
            .param("shots", 3)
            .param("fireball_damage", 3)
            .param("fireball_radius", 1.5)
            .param("fireball_burn", 3)
            .param("fireball_range", 32)
            .param("fireball_push", 0.4)
            .param("ult_duration", 6)
            .param("ult_radius", 6)
            .param("ult_interval", 0.5)
            .param("ult_damage", 1)
            .param("ult_burn", 2)
            .build();

    /** Blocks travelled per tick by a fireball. */
    private static final double FIREBALL_SPEED = 1.5;
    /** How close the flight path must pass an enemy's hitbox to count as a direct hit. */
    private static final double FIREBALL_HITBOX = 0.35;
    /** Collision sampling distance along the flight path, so fast fireballs never skip a target. */
    private static final double SAMPLE_STEP = 0.4;
    private static final long SHOT_INTERVAL_TICKS = 4;
    private static final int VORTEX_ARMS = 3;
    private static final int VORTEX_POINTS_PER_ARM = 7;
    /** Vanilla deals burn damage whenever the remaining fire ticks are a multiple of this. */
    private static final int BURN_PERIOD = 20;
    /** Upward part of the fireball push, relative to its horizontal speed. */
    private static final double PUSH_LIFT = 0.6;
    private static final double MAX_PUSH = 1.5;

    private static final Particle.DustOptions EMBER = new Particle.DustOptions(Color.fromRGB(255, 96, 24), 1.5f);
    private static final Particle.DustTransition HEAT =
            new Particle.DustTransition(Color.fromRGB(255, 214, 92), Color.fromRGB(200, 40, 10), 1.1f);

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !isFireDamage(event)) {
            return;
        }
        event.setCancelled(true);
        if (event.getCause() == DamageCause.FIRE_TICK) {
            // Immune anyway: drop the burning overlay so it does not block the owner's view.
            player().setFireTicks(0);
        }
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || ctx().isAbilityDamage() || !player().equals(event.getDamager())
                || !ctx().isEnemy(player(), victim)) {
            return;
        }
        DamageCause cause = event.getCause();
        if (cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        ignite(victim, params().ticks("melee_burn"));
        Location body = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
        victim.getWorld().spawnParticle(Particle.FLAME, body, 6, 0.25, 0.4, 0.25, 0.02);
    }

    private static boolean isFireDamage(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.FIRE || cause == DamageCause.FIRE_TICK || cause == DamageCause.LAVA) {
            return true;
        }
        // Covers magma blocks, campfires and fireballs, which now report CONTACT/PROJECTILE causes.
        Tag<DamageType> fire = DamageTypeTags.IS_FIRE;
        return fire != null && fire.isTagged(event.getDamageSource().getDamageType());
    }

    // ------------------------------------------------------------------ active: fireball volley

    @Override
    public ActivationResult onActive() {
        int shots = Math.max(1, params().integer("shots"));
        launchFireball(0, shots == 1);
        if (shots > 1) {
            Loop.repeat(ctx(), player(), SHOT_INTERVAL_TICKS, SHOT_INTERVAL_TICKS, shots - 1, shot -> {
                launchFireball((int) shot + 1, shot + 2 == shots);
                return true;
            });
        }
        return ActivationResult.success();
    }

    private void launchFireball(int index, boolean last) {
        Location eye = player().getEyeLocation();
        Vector direction = eye.getDirection();
        Fireball fireball = new Fireball(eye, direction, last);
        long maxTicks = (long) Math.ceil(params().num("fireball_range") / FIREBALL_SPEED) + 2;
        Loop.repeat(ctx(), player(), 0, 1, maxTicks, fireball);

        World world = eye.getWorld();
        Location muzzle = eye.clone().add(direction.clone().multiply(0.8));
        world.spawnParticle(Particle.FLAME, muzzle, 5, 0.08, 0.08, 0.08, 0.03);
        world.spawnParticle(Particle.SMOKE, muzzle, 3, 0.05, 0.05, 0.05, 0.01);
        Fx.sound(eye, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.9f + index * 0.15f + Fx.jitter(0.05f));
    }

    /** A simulated fireball: flies straight and bursts on the first enemy, block or fluid it meets. */
    private final class Fireball implements LongPredicate {

        private final World world;
        private final Location position;
        private final Vector direction;
        private final ItemDisplay visual;
        private final boolean last;
        private double remaining;

        Fireball(Location eye, Vector direction, boolean last) {
            this.last = last;
            this.world = eye.getWorld();
            this.position = eye.clone();
            this.direction = direction.clone().normalize();
            this.remaining = params().num("fireball_range");
            Location spawn = eye.clone().add(this.direction.clone().multiply(1.0)).subtract(0, 0.2, 0);
            this.visual = ctx().track(player(), world.spawn(spawn, ItemDisplay.class, display -> {
                display.setPersistent(false);
                display.setItemStack(ItemStack.of(Material.FIRE_CHARGE));
                display.setBillboard(Display.Billboard.CENTER);
                display.setBrightness(new Display.Brightness(15, 15));
                display.setTeleportDuration(1);
                display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(0.75f), new Quaternionf()));
            }));
        }

        @Override
        public boolean test(long tick) {
            double step = Math.min(FIREBALL_SPEED, remaining);
            if (step <= 1.0E-3) {
                burst(position);
                return false;
            }
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.ALWAYS, true);
            double travel = wall == null ? step : wall.getHitPosition().distance(position.toVector());

            int samples = Math.max(1, (int) Math.ceil(travel / SAMPLE_STEP));
            for (int i = 1; i <= samples; i++) {
                Location probe = position.clone().add(direction.clone().multiply(travel * i / samples));
                if (!ctx().enemiesNear(player(), probe, FIREBALL_HITBOX).isEmpty()) {
                    burst(probe);
                    return false;
                }
            }
            if (wall != null) {
                Location impact = wall.getHitPosition().toLocation(world);
                Block block = wall.getHitBlock();
                if (block != null && block.getType() == Material.WATER) {
                    fizzle(impact);
                } else {
                    burst(impact.subtract(direction.clone().multiply(0.2)));
                }
                return false;
            }

            position.add(direction.clone().multiply(step));
            remaining -= step;
            if (visual.isValid()) {
                visual.teleport(position);
            }
            world.spawnParticle(Particle.FLAME, position, 2, 0.08, 0.08, 0.08, 0.01);
            world.spawnParticle(Particle.SMOKE, position, 1, 0.05, 0.05, 0.05, 0.0);
            if (tick % 3 == 0) {
                world.spawnParticle(Particle.LAVA, position, 1, 0.05, 0.05, 0.05, 0.0);
            }
            if (remaining <= 1.0E-3) {
                burst(position);
                return false;
            }
            return true;
        }

        private void burst(Location at) {
            visual.remove();
            double radius = params().num("fireball_radius");
            double damage = params().num("fireball_damage");
            long burn = params().ticks("fireball_burn");
            double push = params().num("fireball_push");
            Vector shove = Motion.horizontal(direction).multiply(push).setY(push * PUSH_LIFT);
            for (LivingEntity enemy : ctx().enemiesNear(player(), at, radius)) {
                Combat.hit(ctx(), player(), enemy, damage);
                if (!Combat.isTargetable(ctx(), player(), enemy)) {
                    continue;
                }
                ignite(enemy, burn);
                if (last && push > 0) {
                    Motion.push(enemy, shove, MAX_PUSH);
                }
            }
            world.spawnParticle(Particle.FLAME, at, 22, 0.2, 0.2, 0.2, 0.12);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 12, radius * 0.4, radius * 0.3, radius * 0.4, 0, HEAT);
            world.spawnParticle(Particle.LAVA, at, 4, 0.2, 0.2, 0.2, 0);
            world.spawnParticle(Particle.LARGE_SMOKE, at, 4, 0.2, 0.2, 0.2, 0.03);
            Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.55f, 1.6f + Fx.jitter(0.15f));
            Fx.sound(at, Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.8f + Fx.jitter(0.1f));
        }

        private void fizzle(Location at) {
            visual.remove();
            world.spawnParticle(Particle.CLOUD, at, 8, 0.15, 0.1, 0.15, 0.03);
            world.spawnParticle(Particle.SMOKE, at, 6, 0.15, 0.1, 0.15, 0.02);
            Fx.sound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 1.3f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ ultimate: fire vortex

    @Override
    public ActivationResult onUltimate() {
        long duration = Math.max(1, params().ticks("ult_duration"));
        long interval = Math.max(1, params().ticks("ult_interval"));
        double radius = params().num("ult_radius");

        Location start = player().getLocation();
        ignitionBlast(start, radius);
        Loop.repeat(ctx(), player(), 0, 1, duration, tick -> {
            Location center = player().getLocation();
            drawVortex(center, radius, tick);
            if (tick % interval == 0) {
                scorch(center, radius);
            }
            if (tick == duration - 1) {
                dieDown(center, radius);
            }
            return true;
        });
        return ActivationResult.success();
    }

    private void scorch(Location center, double radius) {
        double damage = params().num("ult_damage");
        long burn = params().ticks("ult_burn");
        World world = center.getWorld();
        for (LivingEntity enemy : ctx().enemiesNear(player(), center, radius)) {
            Combat.hit(ctx(), player(), enemy, damage);
            ignite(enemy, burn);
            Location body = enemy.getLocation().add(0, enemy.getHeight() * 0.5, 0);
            world.spawnParticle(Particle.FLAME, body, 6, 0.25, 0.45, 0.25, 0.03);
        }
        Fx.sound(center, Sound.ENTITY_BLAZE_BURN, 1.2f, 0.7f + Fx.jitter(0.15f));
    }

    private void drawVortex(Location center, double radius, long tick) {
        World world = center.getWorld();
        double cx = center.getX();
        double cy = center.getY();
        double cz = center.getZ();
        double spin = tick * 0.32;

        // Spiral arms sweeping outwards and upwards.
        for (int arm = 0; arm < VORTEX_ARMS; arm++) {
            double base = spin + arm * (Math.PI * 2 / VORTEX_ARMS);
            for (int i = 1; i <= VORTEX_POINTS_PER_ARM; i++) {
                double f = i / (double) VORTEX_POINTS_PER_ARM;
                double r = radius * f;
                double angle = base + f * 2.4;
                double y = 0.15 + f * 1.6;
                Particle particle = i % 3 == 0 ? Particle.SMALL_FLAME : Particle.FLAME;
                world.spawnParticle(particle, cx + Math.cos(angle) * r, cy + y, cz + Math.sin(angle) * r,
                        1, 0, 0, 0, 0);
            }
        }
        // The owner is engulfed in flames.
        world.spawnParticle(Particle.FLAME, cx, cy + 1.0, cz, 2, 0.3, 0.6, 0.3, 0.01);
        // The edge of the inferno, so everyone can see where it ends.
        if (tick % 4 == 0) {
            int points = 30;
            for (int i = 0; i < points; i++) {
                double angle = i * (Math.PI * 2 / points) - spin * 0.5;
                world.spawnParticle(Particle.DUST, cx + Math.cos(angle) * radius, cy + 0.2,
                        cz + Math.sin(angle) * radius, 1, 0, 0.1, 0, 0, EMBER);
            }
        }
        if (tick % 10 == 5) {
            world.spawnParticle(Particle.LAVA, center.clone().add(0, 0.5, 0), 3, radius * 0.4, 0.2, radius * 0.4, 0);
            Fx.sound(center, Sound.BLOCK_FIRE_AMBIENT, 1.4f, 0.8f + Fx.jitter(0.2f));
        }
    }

    private void ignitionBlast(Location center, double radius) {
        World world = center.getWorld();
        int rays = 36;
        double speed = Math.max(0.2, radius * 0.06);
        for (int i = 0; i < rays; i++) {
            double angle = i * (Math.PI * 2 / rays);
            // count 0: the offsets become the particle's direction and "extra" its speed.
            world.spawnParticle(Particle.FLAME, center.getX(), center.getY() + 0.4, center.getZ(), 0,
                    Math.cos(angle), 0.05, Math.sin(angle), speed);
        }
        world.spawnParticle(Particle.LAVA, center.clone().add(0, 0.5, 0), 8, 0.6, 0.3, 0.6, 0);
        Fx.sound(center, Sound.ENTITY_BLAZE_SHOOT, 1.4f, 0.55f);
        Fx.sound(center, Sound.ITEM_FIRECHARGE_USE, 1.2f, 0.6f);
        Fx.sound(center, Sound.ENTITY_GHAST_SHOOT, 0.8f, 0.7f);
    }

    private void dieDown(Location center, double radius) {
        World world = center.getWorld();
        world.spawnParticle(Particle.LARGE_SMOKE, center.clone().add(0, 0.6, 0), 20, radius * 0.35, 0.4,
                radius * 0.35, 0.02);
        Fx.sound(center, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.7f);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Sets {@code target} on fire for at least {@code ticks}. A target that is already burning keeps its burn phase
     * (vanilla burns every 20 fire ticks), so frequent refreshes extend the fire without speeding up its damage.
     */
    private static void ignite(LivingEntity target, long ticks) {
        if (!target.isValid() || target.isDead() || ticks <= 0) {
            return;
        }
        int fireTicks = (int) Math.min(Integer.MAX_VALUE - BURN_PERIOD, ticks);
        int current = target.getFireTicks();
        if (current <= 0) {
            target.setFireTicks(fireTicks);
        } else if (current < fireTicks) {
            int periods = (fireTicks - current + BURN_PERIOD - 1) / BURN_PERIOD;
            target.setFireTicks(current + periods * BURN_PERIOD);
        }
    }
}
