package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * 광전사 — a melee bruiser that grows stronger the closer it is to death. Missing health boosts melee damage,
 * the charge bowls enemies out of the way, and the frenzy turns melee hits into healing.
 */
public final class BerserkerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("berserker", "광전사", Material.IRON_AXE)
            .color(TextColor.color(0xE0303A))
            .difficulty(1)
            .passive("분노",
                    "잃은 체력에 비례해 근접 피해가 최대 {rage_max}% 증가합니다. 체력이 {rage_health} 이하이면 최대치입니다.")
            .active("돌진",
                    "바라보는 방향으로 {charge_distance}블록 돌진합니다. 부딪힌 적에게 {charge_damage} 피해(분노 적용)를 주고 "
                            + "옆으로 튕겨냅니다.",
                    7)
            .ultimate("광폭화",
                    "{ult_duration}초간 힘 II와 신속 II를 얻고, 근접 공격과 돌진으로 입힌 피해의 {ult_lifesteal}%만큼 "
                            + "체력을 회복합니다.")
            .ultimateNotStandalone()
            .param("rage_max", 50)
            .param("rage_health", 4)
            .param("charge_distance", 8)
            .param("charge_damage", 4)
            .param("ult_duration", 8)
            .param("ult_lifesteal", 30)
            .build();

    // Charge
    private static final double CHARGE_SPEED = 1.15;
    /** Small downward pull so the charge hugs the ground instead of floating off ledges. */
    private static final double CHARGE_SINK = -0.08;
    private static final double CHARGE_HIT_RADIUS = 1.2;
    /** Ticks before stall detection starts (the client needs a moment to pick up the new velocity). */
    private static final long CHARGE_GRACE_TICKS = 3;
    private static final double STALL_EPSILON = 0.12;
    private static final int STALL_LIMIT = 2;
    private static final double BOWL_FORWARD = 0.9;
    private static final double BOWL_SIDE = 0.75;
    private static final double BOWL_UP = 0.42;
    private static final double MAX_VELOCITY = 2.5;

    // Rage feedback
    private static final long RAGE_TICK_PERIOD = 10;
    private static final double RAGE_STEP_PERCENT = 10;

    // Frenzy
    private static final int FRENZY_AMPLIFIER = 1;
    private static final long FRENZY_VISUAL_PERIOD = 2;

    private static final Color BLOOD = Color.fromRGB(224, 48, 58);
    private static final Particle.DustOptions RAGE_DUST = new Particle.DustOptions(BLOOD, 1.1f);
    private static final Particle.DustOptions CHARGE_DUST = new Particle.DustOptions(BLOOD, 1.6f);
    private static final Particle.DustTransition FRENZY_DUST =
            new Particle.DustTransition(Color.fromRGB(255, 60, 40), Color.fromRGB(40, 0, 0), 1.3f);

    private boolean charging;
    /** True while the charge deals its damage, so the frenzy lifesteal also counts it. */
    private boolean chargeImpact;
    private long frenzyUntil = -1;
    private int lastRageStep;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        charging = false;
        chargeImpact = false;
        frenzyUntil = -1;
    }

    // ------------------------------------------------------------------ passive: rage

    /** 0..1: how much of the rage bonus is unlocked by missing health. */
    private double rageFraction() {
        Player owner = player();
        double max = Combat.maxHealth(owner);
        double floor = Math.min(Math.max(0, params().num("rage_health")), max - 1);
        double missing = max - owner.getHealth();
        return clamp01(missing / (max - floor));
    }

    /** Damage multiplier from rage, e.g. 1.5 at full rage with the default 50%. */
    private double rageMultiplier() {
        return 1.0 + Math.max(0, params().num("rage_max")) / 100.0 * rageFraction();
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !ctx().isEnemy(player(), victim)) {
            return;
        }
        if (ctx().isAbilityDamage()) {
            // The charge already applied rage to its own damage; it only feeds the frenzy lifesteal.
            if (chargeImpact && isFrenzied()) {
                scheduleLifesteal(event, victim);
            }
            return;
        }
        if (!isOwnMelee(event)) {
            return;
        }
        double fraction = rageFraction();
        if (fraction > 0) {
            event.setDamage(event.getDamage() * rageMultiplier());
            if (fraction >= 0.3) {
                Location body = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
                World world = body.getWorld();
                world.spawnParticle(Particle.DUST, body, 3 + (int) Math.round(fraction * 7), 0.25, 0.35, 0.25, 0,
                        RAGE_DUST);
                world.spawnParticle(Particle.CRIT, body, 4 + (int) Math.round(fraction * 6), 0.2, 0.3, 0.2, 0.2);
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6f, 1.15f - (float) fraction * 0.4f);
            }
        }
        if (isFrenzied()) {
            scheduleLifesteal(event, victim);
        }
    }

    private boolean isOwnMelee(EntityDamageByEntityEvent event) {
        DamageCause cause = event.getCause();
        return (cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK)
                && player().equals(event.getDamager());
    }

    @Override
    public void onTick(long tick) {
        if (tick % RAGE_TICK_PERIOD != 0) {
            return;
        }
        double fraction = rageFraction();
        double maxBonus = Math.max(0, params().num("rage_max"));
        int step = (int) Math.floor(maxBonus * fraction / RAGE_STEP_PERCENT + 1.0E-6);
        if (step > lastRageStep) {
            String percent = AbilityInfo.formatNumber(Math.round(maxBonus * fraction));
            player().sendActionBar(fraction >= 0.999
                    ? Text.mm("<#E0303A><b>분노 최대!</b></#E0303A> <gray>근접 피해 +" + percent + "%</gray>")
                    : Text.mm("<#E0303A>분노</#E0303A> <gray>근접 피해 +" + percent + "%</gray>"));
        }
        lastRageStep = step;
        if (fraction < 0.1) {
            return;
        }
        Player owner = player();
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        owner.getWorld().spawnParticle(Particle.DUST, body, 1 + (int) Math.round(fraction * 3), 0.3, 0.45, 0.3, 0,
                RAGE_DUST);
        if (fraction >= 0.999 && tick % (RAGE_TICK_PERIOD * 2) == 0) {
            owner.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, body.add(0, owner.getHeight() * 0.6, 0), 1,
                    0.2, 0.1, 0.2, 0);
        }
    }

    // ------------------------------------------------------------------ active: charge

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 돌진할 수 없습니다");
        }
        if (charging) {
            return ActivationResult.fail("이미 돌진 중입니다");
        }
        Vector direction = Motion.horizontalFacing(owner.getLocation());
        Location start = owner.getLocation();
        // The charge never carries the owner past the arena border.
        double distance = Spots.arenaReach(ctx(), start, direction, Math.max(1, params().num("charge_distance")));
        if (distance < 1) {
            return ActivationResult.fail("자기장 밖으로는 돌진할 수 없습니다");
        }
        long maxTicks = (long) Math.ceil(distance / CHARGE_SPEED) + 6;
        Set<UUID> struck = new HashSet<>();
        charging = true;

        Fx.sound(start, Sound.ENTITY_HOGLIN_ANGRY, 1.0f, 0.9f + Fx.jitter(0.1f));
        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f);
        start.getWorld().spawnParticle(Particle.CLOUD, start.clone().add(0, 0.2, 0), 8, 0.3, 0.05, 0.3, 0.05);
        owner.setVelocity(direction.clone().multiply(CHARGE_SPEED).setY(CHARGE_SINK));
        owner.setFallDistance(0);

        Location[] last = {start};
        int[] stalls = {0};
        Loop.repeat(ctx(), player(), 0, 1, maxTicks, tick -> {
            Location now = owner.getLocation();
            if (!now.getWorld().equals(start.getWorld()) || horizontalDistance(start, now) >= distance - 0.2) {
                return false;
            }
            if (tick >= CHARGE_GRACE_TICKS) {
                stalls[0] = horizontalDistance(last[0], now) < STALL_EPSILON ? stalls[0] + 1 : 0;
                if (stalls[0] >= STALL_LIMIT) {
                    wallBump(now, direction);
                    return false;
                }
            }
            last[0] = now;

            owner.setVelocity(direction.clone().multiply(CHARGE_SPEED).setY(CHARGE_SINK));
            owner.setFallDistance(0);
            chargeTrail(now);

            Location body = now.clone().add(0, owner.getHeight() * 0.5, 0).add(direction.clone().multiply(0.4));
            for (LivingEntity enemy : ctx().enemiesNear(owner, body, CHARGE_HIT_RADIUS)) {
                if (struck.add(enemy.getUniqueId())) {
                    bowl(enemy, now, direction);
                }
            }
            return true;
        }, () -> {
            charging = false;
            owner.setVelocity(direction.clone().multiply(0.25).setY(CHARGE_SINK));
            if (!struck.isEmpty()) {
                owner.sendActionBar(Text.mm("<#E0303A>돌진!</#E0303A> <gray>" + struck.size() + "명 적중</gray>"));
            }
        });
        return ActivationResult.success();
    }

    /** Hits an enemy in the charge's path and knocks it forward and to whichever side it stood on. */
    private void bowl(LivingEntity enemy, Location chargerAt, Vector direction) {
        double damage = Math.max(0, params().num("charge_damage")) * rageMultiplier();
        chargeImpact = true;
        try {
            Combat.hit(ctx(), player(), enemy, damage);
        } finally {
            chargeImpact = false;
        }
        if (!Combat.isTargetable(ctx(), player(), enemy)) {
            return;
        }
        Vector offset = enemy.getLocation().toVector().subtract(chargerAt.toVector()).setY(0);
        double cross = direction.getX() * offset.getZ() - direction.getZ() * offset.getX();
        Vector side = new Vector(-direction.getZ(), 0, direction.getX());
        if (cross < 0) {
            side.multiply(-1);
        }
        Vector knock = direction.clone().multiply(BOWL_FORWARD).add(side.multiply(BOWL_SIDE)).setY(BOWL_UP);
        Motion.push(enemy, knock, MAX_VELOCITY);

        Location body = enemy.getLocation().add(0, enemy.getHeight() * 0.5, 0);
        World world = body.getWorld();
        world.spawnParticle(Particle.SWEEP_ATTACK, body, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.CRIT, body, 14, 0.3, 0.4, 0.3, 0.35);
        world.spawnParticle(Particle.DUST, body, 8, 0.3, 0.4, 0.3, 0, CHARGE_DUST);
        Fx.sound(body, Sound.ENTITY_GOAT_RAM_IMPACT, 1.0f, 0.85f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f);
    }

    private void chargeTrail(Location at) {
        World world = at.getWorld();
        world.spawnParticle(Particle.DUST, at.clone().add(0, 0.3, 0), 3, 0.25, 0.2, 0.25, 0, CHARGE_DUST);
        world.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.1, 0), 1, 0.15, 0.02, 0.15, 0.01);
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        if (!ground.getMaterial().isAir()) {
            world.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.1, 0), 4, 0.3, 0.05, 0.3, 0.1, ground);
        }
    }

    private void wallBump(Location at, Vector direction) {
        Location front = at.clone().add(direction.clone().multiply(0.6)).add(0, 1.0, 0);
        BlockData wall = front.getBlock().getBlockData();
        World world = at.getWorld();
        if (!wall.getMaterial().isAir()) {
            world.spawnParticle(Particle.BLOCK, front, 18, 0.3, 0.4, 0.3, 0.15, wall);
        }
        world.spawnParticle(Particle.CRIT, front, 8, 0.2, 0.3, 0.2, 0.2);
        Fx.sound(front, Sound.ITEM_MACE_SMASH_GROUND, 0.7f, 1.3f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ ultimate: frenzy

    private boolean isFrenzied() {
        return frenzyUntil >= 0 && ctx().currentTick() < frenzyUntil;
    }

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        long duration = Math.max(1, params().ticks("ult_duration"));
        int effectTicks = (int) Math.min(Integer.MAX_VALUE, duration);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, effectTicks, FRENZY_AMPLIFIER, false, true,
                true));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, effectTicks, FRENZY_AMPLIFIER, false, true,
                true));
        frenzyUntil = ctx().currentTick() + duration;

        Location at = owner.getLocation();
        World world = at.getWorld();
        Fx.sound(at, Sound.ENTITY_RAVAGER_ROAR, 1.3f, 1.1f);
        Fx.sound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 1.5f, 1.4f);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at.clone().add(0, owner.getHeight() * 0.5, 0), 40,
                0.6, 0.7, 0.6, 0, FRENZY_DUST);
        world.spawnParticle(Particle.ANGRY_VILLAGER, at.clone().add(0, owner.getHeight() + 0.3, 0), 3, 0.4, 0.1,
                0.4, 0);
        Fx.ring(at.clone().add(0, 0.15, 0), 2.2, 28, 0, Particle.DUST, CHARGE_DUST);

        long visualRuns = Math.max(1, duration / FRENZY_VISUAL_PERIOD);
        Loop.repeat(ctx(), player(), FRENZY_VISUAL_PERIOD, FRENZY_VISUAL_PERIOD, visualRuns, tick -> {
            if (!isFrenzied()) {
                return false;
            }
            Location now = owner.getLocation();
            double spin = tick * 0.55;
            double y = now.getY() + owner.getHeight() * (0.25 + 0.15 * Math.sin(tick * 0.3));
            for (int side = 0; side < 2; side++) {
                double angle = spin + side * Math.PI;
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, now.getX() + Math.cos(angle) * 0.75, y,
                        now.getZ() + Math.sin(angle) * 0.75, 1, 0, 0, 0, 0, FRENZY_DUST);
            }
            return true;
        }, () -> {
            if (frenzyUntil >= 0 && ctx().currentTick() + FRENZY_VISUAL_PERIOD >= frenzyUntil) {
                Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_BREATH, 1.0f, 0.7f);
                owner.sendActionBar(Text.mm("<gray>광폭화가 끝났습니다</gray>"));
            }
        });
        return ActivationResult.success();
    }

    /**
     * Heals a share of the damage once the event has fully resolved, so hits cancelled or reduced by the victim's
     * own ability (or anything else) only heal what actually landed.
     */
    private void scheduleLifesteal(EntityDamageByEntityEvent event, LivingEntity victim) {
        double share = Math.max(0, params().num("ult_lifesteal")) / 100.0;
        if (share <= 0) {
            return;
        }
        double before = victim.getHealth() + victim.getAbsorptionAmount();
        ctx().runLater(player(), 1, () -> {
            if (event.isCancelled() || !ctx().isAlive(player())) {
                return;
            }
            double amount = Math.min(Math.max(0, event.getFinalDamage()), before) * share;
            if (amount < 0.05) {
                return;
            }
            ctx().heal(player(), amount);
            Player owner = player();
            Location to = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
            Location from = victim.isValid() ? victim.getLocation().add(0, victim.getHeight() * 0.55, 0)
                    : to.clone().add(Motion.horizontalFacing(owner.getLocation()).multiply(1.5));
            if (!from.getWorld().equals(to.getWorld())) {
                return;
            }
            int duration = (int) Math.max(4, Math.min(16, from.distance(to) * 3));
            from.getWorld().spawnParticle(Particle.TRAIL, from, 3 + (int) Math.min(5, Math.round(amount * 2)),
                    0.2, 0.25, 0.2, 0, new Particle.Trail(to, BLOOD, duration));
        });
    }

    // ------------------------------------------------------------------ helpers

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }
}
