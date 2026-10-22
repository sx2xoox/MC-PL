package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

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
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 뇌신 — lightning caster. Melee hits may shock, the active calls a telegraphed bolt on the aimed spot that
 * launches enemies, the ultimate smites every enemy in a wide radius several times.
 */
public final class ThunderAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("thunder", "뇌신", Material.LIGHTNING_ROD)
            .color(TextColor.color(0xFFE14D))
            .difficulty(2)
            .passive("정전기",
                    "근접 공격 시 {shock_chance}% 확률로 적을 감전시켜 {shock_damage} 피해를 주고 "
                            + "{shock_slow}초간 구속 II를 겁니다.")
            .active("낙뢰",
                    "{range}블록 안의 조준한 곳에 번개를 내리꽂습니다. {strike_delay}초 뒤 반경 {radius}블록의 적에게 "
                            + "{damage} 피해를 주고 {launch_height}블록 높이로 띄웁니다.",
                    9)
            .ultimate("천벌",
                    "반경 {ult_radius}블록 안의 모든 적에게 {ult_interval}초 간격으로 낙뢰를 {ult_strikes}번 "
                            + "내리칩니다. 낙뢰 한 번에 {ult_damage} 피해를 줍니다.")
            .param("shock_chance", 25)
            .param("shock_damage", 1)
            .param("shock_slow", 1)
            .param("range", 30)
            .param("radius", 2.5)
            .param("damage", 6)
            .param("strike_delay", 0.3)
            .param("launch_height", 2)
            .param("ult_radius", 20)
            .param("ult_strikes", 3)
            .param("ult_interval", 1)
            .param("ult_damage", 4)
            .build();

    private static final double GRAVITY = 0.08;
    private static final double OUTWARD_PUSH = 0.3;
    private static final double MAX_VELOCITY = 2.5;
    /** Ticks between casting the ultimate and the first wave of bolts (the storm gathers). */
    private static final long ULT_WINDUP_TICKS = 10;

    private static final Color FLASH_COLOR = Color.fromRGB(255, 248, 210);
    private static final Particle.DustOptions CHARGE = new Particle.DustOptions(Color.fromRGB(255, 225, 77), 1.2f);
    private static final Particle.DustTransition ARC =
            new Particle.DustTransition(Color.fromRGB(255, 255, 255), Color.fromRGB(120, 200, 255), 1.0f);

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: static shock

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || ctx().isAbilityDamage() || event.getCause() != DamageCause.ENTITY_ATTACK
                || !player().equals(event.getDamager()) || !ctx().isEnemy(player(), victim)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() * 100.0 >= params().num("shock_chance")) {
            return;
        }
        int slowTicks = (int) Math.min(Integer.MAX_VALUE, params().ticks("shock_slow"));
        if (slowTicks > 0) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks, 1, false, true, true));
        }
        Location body = victim.getLocation().add(0, victim.getHeight() * 0.55, 0);
        World world = victim.getWorld();
        world.spawnParticle(Particle.ELECTRIC_SPARK, body, 14, 0.3, 0.5, 0.3, 0.15);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 6, 0.3, 0.5, 0.3, 0, ARC);
        sound(body, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.45f, 1.8f + jitter(0.15f));
        player().sendActionBar(Text.mm("<#FFE14D>⚡ 감전!</#FFE14D>"));

        // Deal the shock right after the melee hit has resolved, instead of nesting it inside this event.
        double damage = params().num("shock_damage");
        ctx().runLater(player(), 1, () -> hit(victim, damage));
    }

    // ------------------------------------------------------------------ active: lightning strike

    @Override
    public ActivationResult onActive() {
        double range = params().num("range");
        Location target = aimPoint(range);
        if (target == null) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안의 땅이나 적을 조준하세요");
        }
        double radius = params().num("radius");
        long delay = params().ticks("strike_delay");

        sound(target, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 1.6f);
        sound(player().getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 0.6f, 1.8f);
        drawWarning(target, radius, 0);
        if (delay <= 0) {
            strike(target);
        } else {
            loop(1, 1, delay, tick -> {
                if (tick == delay - 1) {
                    strike(target);
                    return false;
                }
                drawWarning(target, radius, tick + 1);
                return true;
            });
        }
        return ActivationResult.success();
    }

    /** The aimed enemy's feet, otherwise the aimed block surface, or null if nothing is in range. */
    private @Nullable Location aimPoint(double range) {
        LivingEntity aimed = ctx().findTarget(player(), range);
        if (aimed != null) {
            return aimed.getLocation();
        }
        Location eye = player().getEyeLocation();
        World world = eye.getWorld();
        RayTraceResult result = world.rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.SOURCE_ONLY,
                true);
        if (result == null) {
            return null;
        }
        Vector point = result.getHitPosition();
        BlockFace face = result.getHitBlockFace();
        if (face != null) {
            point.add(face.getDirection().multiply(0.05));
        }
        return point.toLocation(world);
    }

    private void drawWarning(Location center, double radius, long tick) {
        World world = center.getWorld();
        int points = 18;
        double turn = tick * 0.4;
        for (int i = 0; i < points; i++) {
            double angle = turn + i * (Math.PI * 2 / points);
            world.spawnParticle(Particle.ELECTRIC_SPARK, center.getX() + Math.cos(angle) * radius,
                    center.getY() + 0.15, center.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
        }
        world.spawnParticle(Particle.DUST, center.clone().add(0, 0.3, 0), 4, radius * 0.35, 0.05,
                radius * 0.35, 0, CHARGE);
    }

    private void strike(Location point) {
        World world = point.getWorld();
        world.strikeLightningEffect(point);
        world.spawnParticle(Particle.FLASH, point.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0, FLASH_COLOR);
        world.spawnParticle(Particle.ELECTRIC_SPARK, point.clone().add(0, 0.4, 0), 40, 0.6, 0.4, 0.6, 0.6);
        world.spawnParticle(Particle.LARGE_SMOKE, point.clone().add(0, 0.2, 0), 6, 0.4, 0.1, 0.4, 0.02);

        double radius = params().num("radius");
        double damage = params().num("damage");
        double launch = launchVelocity(params().num("launch_height"));
        int hits = 0;
        for (LivingEntity enemy : ctx().enemiesNear(player(), point, radius)) {
            hit(enemy, damage);
            hits++;
            if (!enemy.isValid() || enemy.isDead() || !ctx().isEnemy(player(), enemy)) {
                continue;
            }
            Vector push = enemy.getLocation().toVector().subtract(point.toVector()).setY(0);
            if (push.lengthSquared() > 1.0E-4) {
                push.normalize().multiply(OUTWARD_PUSH);
            } else {
                push.zero();
            }
            enemy.setVelocity(clamp(push.setY(launch), MAX_VELOCITY));
        }
        if (hits > 0) {
            player().sendActionBar(Text.mm("<#FFE14D>⚡ 낙뢰 적중</#FFE14D> <gray>" + hits + "명</gray>"));
        }
    }

    // ------------------------------------------------------------------ ultimate: divine punishment

    @Override
    public ActivationResult onUltimate() {
        double radius = params().num("ult_radius");
        if (ctx().enemiesNear(player(), player().getLocation(), radius).isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        int strikes = Math.max(1, params().integer("ult_strikes"));
        long interval = Math.max(1, params().ticks("ult_interval"));
        long total = ULT_WINDUP_TICKS + (strikes - 1) * interval + 1;

        Location origin = player().getLocation();
        sound(origin, Sound.ITEM_TRIDENT_THUNDER, 1.6f, 0.8f);
        sound(origin, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.8f);
        origin.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, origin.clone().add(0, 1, 0), 40, 0.5, 0.8, 0.5,
                0.4);

        loop(0, 1, total, tick -> {
            Location center = player().getLocation();
            drawStorm(center, tick);
            long sinceWindup = tick - ULT_WINDUP_TICKS;
            if (sinceWindup >= 0 && sinceWindup % interval == 0) {
                int wave = (int) (sinceWindup / interval) + 1;
                if (wave <= strikes) {
                    smite(center, radius, wave, strikes);
                }
            }
            return true;
        });
        return ActivationResult.success();
    }

    private void smite(Location center, double radius, int wave, int strikes) {
        double damage = params().num("ult_damage");
        List<LivingEntity> enemies = ctx().enemiesNear(player(), center, radius);
        for (LivingEntity enemy : enemies) {
            Location at = enemy.getLocation();
            World world = at.getWorld();
            world.strikeLightningEffect(at);
            world.spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, 1, 0), 24, 0.4, 0.8, 0.4, 0.4);
            hit(enemy, damage);
        }
        player().sendActionBar(Text.mm("<#FFE14D>⚡ 천벌 " + wave + "/" + strikes + "</#FFE14D> <gray>"
                + enemies.size() + "명 적중</gray>"));
    }

    private void drawStorm(Location center, long tick) {
        World world = center.getWorld();
        world.spawnParticle(Particle.ELECTRIC_SPARK, center.clone().add(0, 1.0, 0), 2, 0.4, 0.7, 0.4, 0.05);
        if (tick % 2 == 0) {
            Location cloud = center.clone().add(0, 7, 0);
            world.spawnParticle(Particle.LARGE_SMOKE, cloud, 4, 2.5, 0.3, 2.5, 0.01);
            world.spawnParticle(Particle.ELECTRIC_SPARK, cloud, 3, 2.5, 0.3, 2.5, 0.2);
        }
        if (tick % 6 == 0) {
            sound(center, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.5f, 0.6f + jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Initial upward speed that lifts a player roughly {@code height} blocks (vanilla gravity 0.08/tick). */
    private static double launchVelocity(double height) {
        return Math.sqrt(2 * GRAVITY * Math.max(0, height));
    }

    /** Deals credited ability damage. Clears the hurt cooldown first so follow-up hits are not swallowed. */
    private void hit(LivingEntity target, double amount) {
        if (amount <= 0 || !target.isValid() || !ctx().isEnemy(player(), target)) {
            return;
        }
        target.setNoDamageTicks(0);
        ctx().damage(player(), target, amount);
    }

    /**
     * Runs {@code step} with a 0-based run index every {@code period} ticks after {@code delay}, until it returns
     * false, has run {@code maxRuns} times or the owner is no longer alive. Owner-scoped: cancelled on revoke.
     */
    private void loop(long delay, long period, long maxRuns, LongPredicate step) {
        if (maxRuns <= 0) {
            return;
        }
        BukkitTask[] handle = new BukkitTask[1];
        long[] runs = {0};
        handle[0] = ctx().runTimer(player(), delay, period, () -> {
            long index = runs[0]++;
            boolean more = index < maxRuns && ctx().isAlive(player()) && step.test(index);
            if ((!more || index + 1 >= maxRuns) && handle[0] != null) {
                handle[0].cancel();
            }
        });
    }

    private static Vector clamp(Vector velocity, double max) {
        double length = velocity.length();
        if (!Double.isFinite(length)) {
            return new Vector();
        }
        return length > max ? velocity.multiply(max / length) : velocity;
    }

    private static void sound(Location at, Sound sound, float volume, float pitch) {
        at.getWorld().playSound(at, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private static float jitter(float amount) {
        return (ThreadLocalRandom.current().nextFloat() * 2 - 1) * amount;
    }
}
