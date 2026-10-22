package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 광전사 — a melee bruiser that grows stronger the closer it is to death. Missing health boosts melee damage,
 * the charge bowls enemies out of the way, and the frenzy turns melee hits into healing.
 *
 * <p>Look: blood red. Rage is a heartbeat ring at the feet with embers rising off the body that grows with the
 * missing health; raging hits leave a crimson crescent. The charge drags speed streaks and cleaves struck enemies
 * with a ghostly axe. The frenzy erupts in red glass flame spikes and a blood column, then burns as a flame-like
 * aura around the berserker.
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

    private static final double TAU = Math.PI * 2;

    // Charge
    private static final double CHARGE_SPEED = 1.15;
    /** Small downward pull so the charge hugs the ground instead of floating off ledges. */
    private static final double CHARGE_SINK = -0.08;
    private static final double CHARGE_HIT_RADIUS = 1.2;
    /** Ticks before stall detection starts (the client needs a moment to pick up the new velocity). */
    private static final long CHARGE_GRACE_TICKS = 3;
    private static final double STALL_EPSILON = 0.12;
    /** Ticks in a row without movement that end the charge; three, so one late movement packet does not. */
    private static final int STALL_LIMIT = 3;
    /** Most ticks a slow connection may add to the grace period and the charge length. */
    private static final long MAX_LAG_TICKS = 8;
    private static final double MILLIS_PER_TICK = 50.0;
    private static final double BOWL_FORWARD = 0.9;
    private static final double BOWL_SIDE = 0.75;
    private static final double BOWL_UP = 0.42;
    private static final double MAX_VELOCITY = 2.5;

    // Rage feedback
    private static final long RAGE_TICK_PERIOD = 10;
    private static final long RAGE_AURA_PERIOD = 5;
    /** At full rage the aura beats like a heart, once per this many ticks. */
    private static final long RAGE_BEAT_PERIOD = 20;
    private static final double RAGE_STEP_PERCENT = 10;
    /** Rage below this share shows no aura. */
    private static final double RAGE_VISIBLE = 0.1;
    /** Melee hits with at least this much rage show the crimson crescent. */
    private static final double RAGE_HIT_FEEDBACK = 0.3;

    // Frenzy
    private static final int FRENZY_AMPLIFIER = 1;
    private static final long FRENZY_VISUAL_PERIOD = 2;

    // Visuals
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFF8A80, 0xE0303A, 0x5A0000);
    private static final Particle.DustOptions CORE_DUST = new Particle.DustOptions(PALETTE.core(), 0.8f);
    private static final Particle.DustOptions EDGE_DUST = new Particle.DustOptions(PALETTE.edge(), 1.1f);
    /** Ticks a charge speed streak takes to fly back past the berserker. */
    private static final int STREAK_TICKS = 5;
    private static final double SLASH_WIDTH_DEGREES = 130;
    private static final float AXE_SCALE = 1.6f;
    private static final float AXE_FROM_ROLL = 70;
    private static final float AXE_TO_ROLL = -65;
    private static final int AXE_SWING_TICKS = 3;
    private static final int AXE_FADE_TICKS = 4;
    private static final double FRENZY_WAVE_RADIUS = 3.6;
    private static final long FRENZY_WAVE_TICKS = 6;
    private static final int FLAME_SPIKES = 8;
    private static final int BLOOD_SPIKES = 4;
    private static final long SPIKE_HOLD_TICKS = 7;
    private static final int SPIKE_RISE_TICKS = 3;
    private static final int SPIKE_SINK_TICKS = 5;

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
            if (fraction >= RAGE_HIT_FEEDBACK) {
                rageHit(victim, fraction);
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
        if (tick % RAGE_AURA_PERIOD != 0) {
            return;
        }
        double fraction = rageFraction();
        if (tick % RAGE_TICK_PERIOD == 0) {
            double maxBonus = Math.max(0, params().num("rage_max"));
            int step = (int) Math.floor(maxBonus * fraction / RAGE_STEP_PERCENT + 1.0E-6);
            if (step > lastRageStep) {
                String percent = AbilityInfo.formatNumber(Math.round(maxBonus * fraction));
                player().sendActionBar(fraction >= 0.999
                        ? Text.mm("<#E0303A><b>분노 최대!</b></#E0303A> <gray>근접 피해 +" + percent + "%</gray>")
                        : Text.mm("<#E0303A>분노</#E0303A> <gray>근접 피해 +" + percent + "%</gray>"));
            }
            lastRageStep = step;
        }
        if (fraction >= RAGE_VISIBLE) {
            rageAura(tick, fraction);
        }
    }

    /** A ring at the feet that widens and thickens with rage, embers rising off the body, a heartbeat at full rage. */
    private void rageAura(long tick, double fraction) {
        Player owner = player();
        Location feet = owner.getLocation().add(0, 0.08, 0);
        double radius = 0.45 + 0.35 * fraction;
        int points = 6 + (int) Math.round(10 * fraction);
        Vfx.ring(ctx(), feet, radius, points, tick * 0.21, Particle.DUST, PALETTE.dust((float) (0.6 + 0.6 * fraction)),
                false);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int embers = Vfx.n(ctx(), fraction >= 0.5 ? 2 : 1);
        for (int i = 0; i < embers; i++) {
            Location from = Vfx.scatter(feet, radius);
            Location to = from.clone().add(0, owner.getHeight() * random.nextDouble(0.6, 1.1), 0);
            Vfx.trail(from, to, i % 2 == 0 ? PALETTE.core() : PALETTE.body(), random.nextInt(10, 16), false);
        }
        if (fraction >= 0.999 && tick % RAGE_BEAT_PERIOD == 0) {
            Vfx.ring(ctx(), feet, radius + 0.35, points + 4, -tick * 0.21, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.fade(1.1f), false);
            owner.playSound(owner, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 0.35f,
                    1.3f + Fx.jitter(0.05f));
        }
    }

    /** A raging melee hit: a crimson crescent through the victim, stronger and lower-pitched with more rage. */
    private void rageHit(LivingEntity victim, double fraction) {
        Location body = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        Vector facing = victim.getLocation().toVector().subtract(player().getLocation().toVector());
        double tilt = ThreadLocalRandom.current().nextDouble(-40, 40);
        slash(body, facing, 0.75, tilt, 7 + (int) Math.round(fraction * 5), (float) (0.9 + 0.5 * fraction));
        body.getWorld().spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 4 + (int) Math.round(fraction * 6)), 0.2, 0.3,
                0.2, 0.2);
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6f, 1.15f - (float) fraction * 0.4f);
        if (fraction >= 0.999) {
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.5f, 0.8f + Fx.jitter(0.08f));
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
        // The server sees the owner move only a round trip after the velocity is sent: wait that long for the
        // first movement before calling the charge stalled, and give the charge that much more time.
        long lag = lagTicks(owner);
        long maxTicks = (long) Math.ceil(distance / CHARGE_SPEED) + 6 + lag;
        Set<UUID> struck = new HashSet<>();
        charging = true;

        chargeStart(start);
        owner.setVelocity(direction.clone().multiply(CHARGE_SPEED).setY(CHARGE_SINK));
        owner.setFallDistance(0);

        Location[] last = {start};
        int[] stalls = {0};
        boolean[] moved = {false};
        Loop.repeat(ctx(), player(), 0, 1, maxTicks, tick -> {
            Location now = owner.getLocation();
            if (!now.getWorld().equals(start.getWorld()) || horizontalDistance(start, now) >= distance - 0.2) {
                return false;
            }
            if (!moved[0] && horizontalDistance(start, now) >= STALL_EPSILON) {
                moved[0] = true;
            }
            if (moved[0] || tick >= CHARGE_GRACE_TICKS + lag) {
                stalls[0] = horizontalDistance(last[0], now) < STALL_EPSILON ? stalls[0] + 1 : 0;
                if (stalls[0] >= STALL_LIMIT) {
                    wallBump(now, direction);
                    return false;
                }
            }
            last[0] = now;

            owner.setVelocity(direction.clone().multiply(CHARGE_SPEED).setY(CHARGE_SINK));
            owner.setFallDistance(0);
            chargeTrail(now, direction);

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
            chargeEnd(owner.getLocation());
            if (!struck.isEmpty()) {
                owner.sendActionBar(Text.mm("<#E0303A>돌진!</#E0303A> <gray>" + struck.size() + "명 적중</gray>"));
            }
        });
        return ActivationResult.success();
    }

    /** Ticks the server sees the owner's movement late: one round trip, capped (ping can read 0 after joining). */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / MILLIS_PER_TICK));
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
        bowlImpact(enemy, direction);
    }

    /** The charge sets off: a roar, a crescent of red dust and torn-up ground behind the berserker. */
    private void chargeStart(Location start) {
        World world = start.getWorld();
        Fx.sound(start, Sound.ENTITY_HOGLIN_ANGRY, 1.0f, 0.9f + Fx.jitter(0.1f));
        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(start, Sound.ENTITY_BREEZE_JUMP, 0.6f, 0.7f + Fx.jitter(0.05f));
        Location feet = start.clone().add(0, 0.1, 0);
        Vfx.arc(ctx(), feet, 0.7, start.getYaw() + 180, 150, 12, Particle.DUST, PALETTE.dust(1.3f), false);
        Vfx.arc(ctx(), feet, 1.0, start.getYaw() + 180, 120, 9, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.0f),
                false);
        BlockData ground = groundUnder(start);
        if (ground != null) {
            world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 12), 0.35, 0.05, 0.35, 0.15, ground);
        }
        world.spawnParticle(Particle.POOF, feet, Vfx.n(ctx(), 5), 0.25, 0.05, 0.25, 0.04);
    }

    /** One tick of the charge: red speed streaks flying back past the body, a dust smear and kicked-up ground. */
    private void chargeTrail(Location at, Vector direction) {
        World world = at.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double height = player().getHeight();
        Vector side = new Vector(-direction.getZ(), 0, direction.getX());
        int streaks = Vfx.n(ctx(), 3);
        for (int i = 0; i < streaks; i++) {
            Location from = at.clone().add(side.clone().multiply(random.nextDouble(-0.45, 0.45)))
                    .add(0, random.nextDouble(0.15, height * 0.95), 0).add(direction.clone().multiply(0.4));
            Location to = from.clone().subtract(direction.clone().multiply(random.nextDouble(1.4, 2.4)));
            Vfx.trail(from, to, i == 0 ? PALETTE.core() : PALETTE.body(), STREAK_TICKS, false);
        }
        world.spawnParticle(Particle.DUST, at.clone().add(0, 0.35, 0), Vfx.n(ctx(), 2), 0.2, 0.2, 0.2, 0,
                PALETTE.dust(1.5f));
        BlockData ground = groundUnder(at);
        if (ground != null) {
            world.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.1, 0), Vfx.n(ctx(), 3), 0.25, 0.05, 0.25, 0.1,
                    ground);
        }
    }

    /** A struck enemy: a sweep, a red burst, a crescent and a ghostly axe cleaving through it. */
    private void bowlImpact(LivingEntity enemy, Vector direction) {
        Location body = Fx.body(enemy);
        World world = body.getWorld();
        world.spawnParticle(Particle.SWEEP_ATTACK, body, 1, 0, 0, 0, 0);
        Vfx.burst(ctx(), body, PALETTE, 0.55, Particle.CRIT, false);
        slash(body, direction, 0.95, ThreadLocalRandom.current().nextDouble(-35, 35), 12, 1.4f);
        ghostAxe(body);
        Fx.sound(body, Sound.ENTITY_GOAT_RAM_IMPACT, 1.0f, 0.85f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.7f, 0.9f + Fx.jitter(0.1f));
    }

    /** A big axe facing every viewer that swings once through {@code at}, then shrinks away. */
    private void ghostAxe(Location at) {
        Player owner = player();
        ItemDisplay axe = Displays.item(ctx(), owner, at, new ItemStack(Material.IRON_AXE),
                Displays.centered(0, 0, 0, AXE_SCALE * 0.75f, AXE_SCALE * 0.75f, AXE_SCALE * 0.75f, 0, 0,
                        AXE_FROM_ROLL),
                AXE_SWING_TICKS + AXE_FADE_TICKS + 4);
        axe.setBillboard(Display.Billboard.CENTER);
        Displays.animateNextTick(ctx(), owner, axe,
                Displays.centered(0, 0, 0, AXE_SCALE, AXE_SCALE, AXE_SCALE, 0, 0, AXE_TO_ROLL), AXE_SWING_TICKS);
        ctx().runLater(owner, AXE_SWING_TICKS + 2, () -> {
            if (axe.isValid()) {
                Displays.shrinkAway(ctx(), owner, axe, AXE_FADE_TICKS);
            }
        });
    }

    /** The charge hit a wall: chips of the wall, sparks and a dull thud. */
    private void wallBump(Location at, Vector direction) {
        Location front = at.clone().add(direction.clone().multiply(0.6)).add(0, 1.0, 0);
        BlockData wall = front.getBlock().getBlockData();
        World world = at.getWorld();
        if (!wall.getMaterial().isAir()) {
            world.spawnParticle(Particle.BLOCK, front, Vfx.n(ctx(), 18), 0.3, 0.4, 0.3, 0.15, wall);
        }
        world.spawnParticle(Particle.CRIT, front, Vfx.n(ctx(), 8), 0.2, 0.3, 0.2, 0.2);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, front, Vfx.n(ctx(), 8), 0.25, 0.35, 0.25, 0,
                PALETTE.fade(1.2f));
        Fx.sound(front, Sound.ITEM_MACE_SMASH_GROUND, 0.7f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(front, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.5f, 0.6f + Fx.jitter(0.05f));
    }

    /** Afterglow where the charge ended: a dark ring of settling dust. */
    private void chargeEnd(Location at) {
        Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), 0.6, 10, ThreadLocalRandom.current().nextDouble(TAU),
                Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.9f), false);
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
        frenzyCast(owner);

        long visualRuns = Math.max(1, duration / FRENZY_VISUAL_PERIOD);
        Loop.repeat(ctx(), player(), FRENZY_VISUAL_PERIOD, FRENZY_VISUAL_PERIOD, visualRuns, tick -> {
            if (!isFrenzied()) {
                return false;
            }
            frenzyAura(owner, tick);
            return true;
        }, () -> {
            if (frenzyUntil >= 0 && ctx().currentTick() + FRENZY_VISUAL_PERIOD >= frenzyUntil) {
                Location at = owner.getLocation();
                Fx.sound(at, Sound.ENTITY_PLAYER_BREATH, 1.0f, 0.7f);
                Vfx.ring(ctx(), at.clone().add(0, 0.15, 0), 0.9, 14, 0, Particle.SMOKE, null, false);
                at.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, at.clone().add(0, owner.getHeight() * 0.5, 0),
                        Vfx.n(ctx(), 10), 0.35, 0.5, 0.35, 0, PALETTE.fade(1.0f));
                owner.sendActionBar(Text.mm("<gray>광폭화가 끝났습니다</gray>"));
            }
        });
        return ActivationResult.success();
    }

    /** The frenzy erupts: a roar, a flash, a blood shockwave, a rising crimson column and flame spikes. */
    private void frenzyCast(Player owner) {
        Location at = owner.getLocation();
        World world = at.getWorld();
        Location chest = at.clone().add(0, owner.getHeight() * 0.55, 0);
        Fx.sound(at, Sound.ENTITY_RAVAGER_ROAR, 1.3f, 1.1f + Fx.jitter(0.05f));
        Fx.sound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 1.5f, 1.4f);
        Fx.sound(at, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.5f + Fx.jitter(0.05f));
        world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, PALETTE.core(), true);
        Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.15, 0), 0.6, FRENZY_WAVE_RADIUS, FRENZY_WAVE_TICKS,
                Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.6f), true);
        Vfx.helix(ctx(), at, 0.75, 3.2, 1.75, 3, 16, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.3f), true);
        Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), 1.0, 8, 0, Particle.DUST_PILLAR,
                Material.REDSTONE_BLOCK.createBlockData(), true);
        flameSpikes(at);
    }

    /** Red glass flame tongues bursting from the ground around the berserker, with thin blood spikes inside. */
    private void flameSpikes(Location at) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BlockData glass = Material.RED_STAINED_GLASS.createBlockData();
        BlockData blood = Material.REDSTONE_BLOCK.createBlockData();
        for (int i = 0; i < FLAME_SPIKES; i++) {
            double angle = i * TAU / FLAME_SPIKES + random.nextDouble(-0.2, 0.2);
            spike(at, angle, 0.9, 0.16f, (float) random.nextDouble(1.4, 2.4), (float) random.nextDouble(12, 22), glass);
        }
        for (int i = 0; i < BLOOD_SPIKES; i++) {
            double angle = (i + 0.5) * TAU / BLOOD_SPIKES + random.nextDouble(-0.3, 0.3);
            spike(at, angle, 0.55, 0.08f, (float) random.nextDouble(2.4, 3.0), (float) random.nextDouble(6, 12), blood);
        }
    }

    /**
     * One spike {@code distance} blocks from {@code center} in direction {@code angle}, leaning outwards by
     * {@code lean} degrees: it shoots up out of the ground, holds, then sinks back.
     */
    private void spike(Location center, double angle, double distance, float width, float height, float lean,
                       BlockData data) {
        Player owner = player();
        Location base = center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
        base.setYaw(0);
        base.setPitch(0);
        float yaw = (float) Math.toDegrees(angle) - 90;
        Transformation buried = spikePose(angle, width, 0.02f, lean, yaw);
        BlockDisplay spike = Displays.block(ctx(), owner, base, data, buried,
                SPIKE_HOLD_TICKS + SPIKE_SINK_TICKS + 2);
        Displays.animateNextTick(ctx(), owner, spike, spikePose(angle, width, height, lean, yaw), SPIKE_RISE_TICKS);
        ctx().runLater(owner, SPIKE_HOLD_TICKS, () -> Displays.animate(spike, buried, SPIKE_SINK_TICKS));
    }

    /** A {@code width} x {@code height} column standing on the entity location, leaning outwards. */
    private static Transformation spikePose(double angle, float width, float height, float lean, float yaw) {
        double tilt = Math.toRadians(lean);
        float half = height / 2;
        float out = (float) (Math.sin(tilt) * half);
        return Displays.transform((float) Math.cos(angle) * out, (float) (Math.cos(tilt) * half),
                (float) Math.sin(angle) * out, width, height, width, yaw, lean, 0);
    }

    /** The frenzy burning: two flame tongues spiralling up the body, embers and a ring at the feet. */
    private void frenzyAura(Player owner, long tick) {
        Location now = owner.getLocation();
        World world = now.getWorld();
        double height = owner.getHeight();
        int flames = Vfx.n(ctx(), 3);
        for (int strand = 0; strand < 2; strand++) {
            for (int k = 0; k < flames; k++) {
                double rise = (tick * 0.16 + (double) k / flames + strand * 0.5) % 1.0;
                double angle = tick * 0.5 + strand * Math.PI + rise * 2.4;
                double radius = 0.75 * (1 - 0.5 * rise);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, now.getX() + Math.cos(angle) * radius,
                        now.getY() + rise * height * 1.15, now.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0,
                        new Particle.DustTransition(PALETTE.core(), PALETTE.edge(), (float) (1.4 - 0.7 * rise)));
            }
        }
        if (tick % 2 == 0) {
            Location from = Vfx.scatter(now, 0.7).add(0, 0.1, 0);
            Vfx.trail(from, from.clone().add(0, height * 1.3, 0), PALETTE.core(), 12, false);
        }
        if (tick % 3 == 0) {
            Vfx.ring(ctx(), now.clone().add(0, 0.08, 0), 0.85, 12, -tick * 0.3, Particle.DUST, EDGE_DUST, false);
        }
    }

    /**
     * Heals a share of the damage once the event has fully resolved, so hits cancelled or reduced by the victim's
     * own ability (or anything else) only heal what actually landed, absorption hearts included.
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
            double amount = Math.min(damageWithAbsorption(event), before) * share;
            if (amount < 0.05) {
                return;
            }
            ctx().heal(player(), amount);
            lifestealVisual(victim, amount);
        });
    }

    /**
     * What the hit took off health and absorption hearts together. {@link EntityDamageEvent#getFinalDamage()}
     * leaves out the part absorption soaked up (the negative {@code ABSORPTION} modifier).
     */
    @SuppressWarnings("deprecation") // DamageModifier is the only exact source of the absorbed amount; no replacement
    private static double damageWithAbsorption(EntityDamageEvent event) {
        return Math.max(0, event.getFinalDamage() - event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION));
    }

    /** Blood motes flowing from the victim into the berserker's chest, and a small flare when they arrive. */
    private void lifestealVisual(LivingEntity victim, double amount) {
        Player owner = player();
        Location to = chest(owner);
        Location from = victim.isValid() ? chest(victim)
                : to.clone().add(Motion.horizontalFacing(owner.getLocation()).multiply(1.5));
        if (!from.getWorld().equals(to.getWorld())) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int duration = (int) Math.max(4, Math.min(16, from.distance(to) * 3));
        int motes = Vfx.n(ctx(), 2 + (int) Math.min(5, Math.round(amount * 2)));
        for (int i = 0; i < motes; i++) {
            Location start = from.clone().add(random.nextDouble(-0.2, 0.2), random.nextDouble(-0.25, 0.25),
                    random.nextDouble(-0.2, 0.2));
            Vfx.trail(start, to, i % 2 == 0 ? PALETTE.body() : PALETTE.core(), duration + i % 3, false);
        }
        ctx().runLater(owner, duration, () -> Vfx.ring(ctx(), chest(owner), 0.4, 6, random.nextDouble(TAU),
                Particle.DUST, CORE_DUST, false));
    }

    // ------------------------------------------------------------------ visual helpers

    /**
     * A diagonal crescent through {@code center}, swung along {@code facing}: three thin arcs (bright core, glowing
     * body, dark edge) on a circle whose pivot lies behind the center, tilted {@code tiltDegrees} from horizontal.
     */
    private void slash(Location center, Vector facing, double radius, double tiltDegrees, int points, float size) {
        Vector forward = Motion.horizontal(facing);
        if (forward.lengthSquared() < 1.0E-6) {
            forward = Motion.horizontalFacing(player().getLocation());
        }
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        double tilt = Math.toRadians(tiltDegrees);
        Vector sweep = right.multiply(Math.cos(tilt)).add(new Vector(0, Math.sin(tilt), 0));
        Location pivot = center.clone().subtract(forward.clone().multiply(radius));
        int count = Math.max(2, Vfx.n(ctx(), points));
        double half = Math.toRadians(SLASH_WIDTH_DEGREES / 2);
        Object[] layers = {new Particle.DustOptions(PALETTE.core(), size * 0.7f), PALETTE.glow(size),
                PALETTE.fade(size * 0.8f)};
        for (int layer = 0; layer < layers.length; layer++) {
            double r = radius * (0.88 + layer * 0.12);
            Particle particle = layer == 0 ? Particle.DUST : Particle.DUST_COLOR_TRANSITION;
            for (int i = 0; i < count; i++) {
                double a = -half + 2 * half * i / (count - 1);
                Location at = pivot.clone().add(forward.clone().multiply(Math.cos(a) * r))
                        .add(sweep.clone().multiply(Math.sin(a) * r));
                Vfx.dot(at, particle, layers[layer], false);
            }
        }
    }

    private static @Nullable BlockData groundUnder(Location at) {
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        return ground.getMaterial().isSolid() ? ground : null;
    }

    private static Location chest(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.55, 0);
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }
}
