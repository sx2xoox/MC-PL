package io.github.sx2xoox.mcpl.game.superpower.abilities;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
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
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/**
 * 흡혈귀 — sustain through aggression. Every hit heals a little, the active tethers an aimed enemy and drains its
 * health while the line of sight holds, and the ultimate turns all damage dealt into healing.
 */
public final class VampireAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("vampire", "흡혈귀", Material.FERMENTED_SPIDER_EYE)
            .color(TextColor.color(0xB0174F))
            .difficulty(2)
            .passive("흡혈", "적에게 입힌 모든 피해의 {lifesteal}%만큼 체력을 회복합니다.")
            .active("생명 흡수",
                    "{drain_range}블록 안의 조준한 적과 {drain_duration}초간 연결되어 {drain_interval}초마다 체력을 "
                            + "{drain_damage}씩 빼앗아 회복합니다. 시야에서 벗어나거나 너무 멀어지면 끊어집니다.",
                    10)
            .ultimate("피의 축제", "{ult_duration}초간 흡혈 효과가 {ult_lifesteal}%로 오르고 신속 I을 얻습니다.")
            .ultimateNotStandalone()
            .param("lifesteal", 20)
            .param("drain_range", 15)
            .param("drain_duration", 2)
            .param("drain_interval", 0.25)
            .param("drain_damage", 1)
            .param("ult_duration", 8)
            .param("ult_lifesteal", 100)
            .build();

    /** Extra blocks a drained enemy may move beyond the aim range before the tether snaps. */
    private static final double DRAIN_LEASH = 3;
    private static final int FEAST_SPEED_AMPLIFIER = 0;
    private static final long FEAST_VISUAL_PERIOD = 2;
    /** Minimum ticks between two lifesteal slurp sounds for the owner. */
    private static final long SLURP_COOLDOWN = 10;

    private static final Color CRIMSON = Color.fromRGB(176, 23, 79);
    private static final Color DARK_BLOOD = Color.fromRGB(110, 0, 20);
    private static final Particle.DustOptions BLOOD_DUST = new Particle.DustOptions(CRIMSON, 1.2f);
    private static final Particle.DustTransition FEAST_DUST =
            new Particle.DustTransition(Color.fromRGB(220, 20, 60), Color.fromRGB(30, 0, 10), 1.3f);
    private static final BlockData BLOOD_SPLASH = Material.REDSTONE_BLOCK.createBlockData();

    /** True while the drain deals its own damage: the drain heals by itself, the passive must not add to it. */
    private boolean draining;
    private long feastUntil = -1;
    private long lastSlurp = Long.MIN_VALUE;
    private @Nullable Drain drain;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        draining = false;
        feastUntil = -1;
        drain = null;
    }

    // ------------------------------------------------------------------ passive: lifesteal

    private boolean isFeasting() {
        return feastUntil >= 0 && ctx().currentTick() < feastUntil;
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || draining || !ctx().isEnemy(player(), victim)) {
            return;
        }
        boolean feast = isFeasting();
        double share = Math.max(0, params().num(feast ? "ult_lifesteal" : "lifesteal")) / 100.0;
        if (share <= 0) {
            return;
        }
        DamageCause cause = event.getCause();
        boolean melee = !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && (cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK);
        double before = victim.getHealth() + victim.getAbsorptionAmount();
        // Heal once the event has fully resolved, so cancelled or reduced hits only heal what actually landed.
        ctx().runLater(player(), 1, () -> {
            if (event.isCancelled() || !ctx().isAlive(player())) {
                return;
            }
            double amount = Math.min(Math.max(0, event.getFinalDamage()), before) * share;
            if (amount < 0.05) {
                return;
            }
            ctx().heal(player(), amount);
            siphonVisual(victim, amount);
            if (feast && melee && victim.isValid()) {
                Location body = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
                victim.getWorld().spawnParticle(Particle.BLOCK, body, 14, 0.25, 0.3, 0.25, 0.15, BLOOD_SPLASH);
                victim.getWorld().spawnParticle(Particle.DUST, body, 6, 0.25, 0.3, 0.25, 0, BLOOD_DUST);
            }
        });
    }

    /** Blood flowing from {@code from} to the owner, scaled with the healed amount, plus a quiet slurp. */
    private void siphonVisual(LivingEntity from, double amount) {
        Player owner = player();
        Location to = chest(owner);
        if (!from.isValid() || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        Location start = chest(from);
        int duration = (int) Math.max(4, Math.min(16, start.distance(to) * 2.5));
        int count = 2 + (int) Math.min(6, Math.round(amount * 1.5));
        start.getWorld().spawnParticle(Particle.TRAIL, start, count, 0.2, 0.25, 0.2, 0,
                new Particle.Trail(to, CRIMSON, duration));
        long now = ctx().currentTick();
        if (now - lastSlurp >= SLURP_COOLDOWN) {
            lastSlurp = now;
            owner.playSound(owner, Sound.ITEM_HONEY_BOTTLE_DRINK, SoundCategory.PLAYERS, 0.4f, 1.5f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ active: life drain

    @Override
    public ActivationResult onActive() {
        double range = Math.max(1, params().num("drain_range"));
        LivingEntity target = ctx().findTarget(player(), range);
        if (target == null) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안의 적을 조준하세요");
        }
        Drain previous = drain;
        if (previous != null) {
            previous.stop();
        }
        Drain created = new Drain(target, range);
        drain = created;
        created.begin();
        return ActivationResult.success();
    }

    /** A tether between the owner and one enemy that siphons health at a fixed interval. */
    private final class Drain {

        private final LivingEntity target;
        private final double range;
        private final long interval = Math.max(1, params().ticks("drain_interval"));
        private final double damage = Math.max(0, params().num("drain_damage"));
        private double total;
        private boolean stopped;
        private boolean snapped;

        Drain(LivingEntity target, double range) {
            this.target = target;
            this.range = range;
        }

        void begin() {
            Player owner = player();
            Fx.sound(owner.getLocation(), Sound.ENTITY_VEX_CHARGE, 1.0f, 0.7f);
            Fx.sound(target.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.8f, 1.6f);
            target.getWorld().spawnParticle(Particle.DUST, chest(target), 12, 0.3, 0.4, 0.3, 0, BLOOD_DUST);
            long duration = Math.max(1, params().ticks("drain_duration"));
            Loop.repeat(ctx(), player(), 1, 1, duration, this::tick, this::finish);
        }

        void stop() {
            stopped = true;
        }

        private boolean tick(long tick) {
            if (stopped) {
                return false;
            }
            if (!holds()) {
                snapped = true;
                return false;
            }
            beam(tick);
            if ((tick + 1) % interval == 0) {
                siphon();
            }
            return !stopped;
        }

        private boolean holds() {
            Player owner = player();
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(owner.getWorld())) {
                return false;
            }
            double leash = range + DRAIN_LEASH;
            return owner.getEyeLocation().distanceSquared(chest(target)) <= leash * leash
                    && owner.hasLineOfSight(target);
        }

        private void beam(long tick) {
            Location from = chest(target);
            Location to = chest(player());
            int duration = (int) Math.max(5, Math.min(20, from.distance(to) * 1.5));
            Color color = tick % 2 == 0 ? CRIMSON : DARK_BLOOD;
            from.getWorld().spawnParticle(Particle.TRAIL, from, 2, 0.15, 0.2, 0.15, 0,
                    new Particle.Trail(to, color, duration));
            if (tick % 4 == 0) {
                from.getWorld().spawnParticle(Particle.DUST, from, 2, 0.25, 0.35, 0.25, 0, BLOOD_DUST);
            }
        }

        private void siphon() {
            Player owner = player();
            double before = target.getHealth() + target.getAbsorptionAmount();
            draining = true;
            try {
                Combat.hit(ctx(), player(), target, damage);
            } finally {
                draining = false;
            }
            boolean alive = target.isValid() && !target.isDead();
            double after = alive ? target.getHealth() + target.getAbsorptionAmount() : 0;
            double drained = Math.max(0, before - after);
            Location at = chest(target);
            Fx.sound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 0.9f, 1.4f + Fx.jitter(0.1f));
            if (drained <= 0) {
                return;
            }
            ctx().heal(owner, drained);
            total += drained;
            owner.playSound(owner, Sound.ITEM_HONEY_BOTTLE_DRINK, SoundCategory.PLAYERS, 0.45f, 1.4f + Fx.jitter(0.1f));
            owner.getWorld().spawnParticle(Particle.DUST, chest(owner), 4, 0.25, 0.3, 0.25, 0, BLOOD_DUST);
            owner.sendActionBar(Text.mm("<#B0174F>생명 흡수</#B0174F> <gray>+" + AbilityInfo.formatNumber(total)
                    + "</gray>"));
        }

        private void finish() {
            if (drain == this) {
                drain = null;
            }
            if (stopped) {
                return;
            }
            Player owner = player();
            if (snapped) {
                Fx.sound(owner.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.7f, 1.6f);
                owner.sendActionBar(Text.mm("<gray>흡수가 끊겼습니다</gray> <#B0174F>+"
                        + AbilityInfo.formatNumber(total) + "</#B0174F>"));
            } else {
                Fx.sound(owner.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.8f, 1.1f);
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: blood feast

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        long duration = Math.max(1, params().ticks("ult_duration"));
        feastUntil = ctx().currentTick() + duration;
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) Math.min(Integer.MAX_VALUE, duration),
                FEAST_SPEED_AMPLIFIER, false, true, true));

        Location at = owner.getLocation();
        World world = at.getWorld();
        Fx.sound(at, Sound.ENTITY_BAT_TAKEOFF, 1.2f, 0.7f);
        Fx.sound(at, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 0.6f);
        Fx.sound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 1.6f, 0.9f);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, chest(owner), 40, 0.6, 0.7, 0.6, 0, FEAST_DUST);
        world.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 0.3, 0), 12, 0.5, 0.2, 0.5, 0.02);
        Fx.ring(at.clone().add(0, 0.15, 0), 2.0, 28, 0, Particle.DUST, BLOOD_DUST);

        long visualRuns = Math.max(1, duration / FEAST_VISUAL_PERIOD);
        Loop.repeat(ctx(), player(), FEAST_VISUAL_PERIOD, FEAST_VISUAL_PERIOD, visualRuns, tick -> {
            if (!isFeasting()) {
                return false;
            }
            Location now = owner.getLocation();
            double height = owner.getHeight();
            for (int strand = 0; strand < 2; strand++) {
                double phase = tick * 0.5 + strand * Math.PI;
                double rise = (tick * 0.12 + strand * 0.5) % 1.0;
                now.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, now.getX() + Math.cos(phase) * 0.7,
                        now.getY() + rise * height, now.getZ() + Math.sin(phase) * 0.7, 1, 0, 0, 0, 0, FEAST_DUST);
            }
            return true;
        }, () -> {
            if (feastUntil >= 0 && ctx().currentTick() + FEAST_VISUAL_PERIOD >= feastUntil) {
                Fx.sound(owner.getLocation(), Sound.ENTITY_BAT_AMBIENT, 0.8f, 0.8f);
                owner.sendActionBar(Text.mm("<gray>피의 축제가 끝났습니다</gray>"));
            }
        });
        return ActivationResult.success();
    }

    // ------------------------------------------------------------------ helpers

    private static Location chest(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.55, 0);
    }
}
