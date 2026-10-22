package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth;
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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 그림자 — assassin. Hits harder from behind, vanishes completely from enemies' screens for a few seconds and, as
 * the ultimate, flickers between nearby enemies' backs, cutting each, before slipping back to where it started.
 */
public final class ShadowAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("shadow", "그림자", Material.BLACK_DYE)
            .color(TextColor.color(0x9A8CC2))
            .difficulty(3)
            .passive("암습", "적의 등 뒤에서 근접 공격하면 피해가 {backstab_bonus}% 증가합니다.")
            .active("그림자 은신",
                    "{stealth_duration}초간 적의 눈에서 완전히 사라지고 신속 I을 얻습니다(발밑에 희미한 그림자가 남습니다). "
                            + "공격하거나 공격받으면 풀리며, 은신 중의 첫 근접 공격은 무조건 암습이 됩니다.",
                    14)
            .ultimate("그림자 난무",
                    "반경 {ult_radius}블록의 적들 등 뒤로 차례로 순간이동하며 {ult_strikes}번 베어 각 {ult_damage} 피해를 준 뒤 "
                            + "원래 자리로 돌아옵니다. 대상이 부족하면 같은 적을 여러 번 벱니다.")
            .param("backstab_bonus", 40)
            .param("stealth_duration", 5)
            .param("ult_radius", 12)
            .param("ult_strikes", 3)
            .param("ult_damage", 5)
            .build();

    private static final String COLOR_TAG = "#9A8CC2";
    private static final Color SHADOW_BLACK = Color.fromRGB(0x16, 0x10, 0x22);
    private static final Color SHADOW_VIOLET = Color.fromRGB(0x6A, 0x4C, 0x9C);
    private static final Particle.DustOptions SHADOW_DUST = new Particle.DustOptions(SHADOW_BLACK, 1.4f);
    private static final Particle.DustOptions FOOTPRINT_DUST = new Particle.DustOptions(SHADOW_BLACK, 0.9f);
    private static final Particle.DustTransition SHADOW_FADE =
            new Particle.DustTransition(SHADOW_BLACK, SHADOW_VIOLET, 1.3f);

    /** Cosine of the angle (from the victim's view direction) beyond which the attacker counts as behind (~105°). */
    private static final double BACKSTAB_DOT = -0.26;
    /** Enemy mobs this close that are chasing the owner lose track of them when stealth starts. */
    private static final double AGGRO_DROP_RANGE = 24;

    // Ultimate
    private static final long DANCE_STEP_TICKS = 6;
    /** A target that fled this many radii away from the start point is skipped. */
    private static final double DANCE_LEASH = 2.0;
    /** Repeated strikes on the same target come from a different side each time. */
    private static final double REPEAT_ANGLE_STEP = 120;
    private static final long LANDING_GRACE_TICKS = 100;

    private final Stealth stealth = new Stealth();
    /** Cancels the fall damage of the landing after a dance step that ended in mid-air. */
    private final LandingGuard landing = new LandingGuard();

    private enum Reveal { ATTACK, HIT, EXPIRED, REVOKE }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        reveal(Reveal.REVOKE);
    }

    // ------------------------------------------------------------------ passive: backstab

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled()) {
            return;
        }
        boolean fromStealth = stealth.isActive();
        if (fromStealth) {
            reveal(Reveal.ATTACK);
        }
        if (ctx().isAbilityDamage() || !player().equals(event.getDamager())
                || event.getCause() != DamageCause.ENTITY_ATTACK) {
            return;
        }
        if (!fromStealth && !isBehind(victim)) {
            return;
        }
        double bonus = Math.max(0, params().num("backstab_bonus"));
        event.setDamage(event.getDamage() * (1 + bonus / 100.0));

        Location at = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        World world = at.getWorld();
        world.spawnParticle(Particle.CRIT, at, 12, 0.25, 0.3, 0.25, 0.25);
        world.spawnParticle(Particle.DUST, at, 10, 0.3, 0.35, 0.3, 0, SHADOW_DUST);
        world.spawnParticle(Particle.SQUID_INK, at, 4, 0.15, 0.2, 0.15, 0.02);
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.65f + Fx.jitter(0.08f));
        Fx.sound(at, Sound.ENTITY_PHANTOM_BITE, 0.7f, 0.8f + Fx.jitter(0.1f));
        String label = fromStealth ? "기습 암습!" : "암습!";
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">⚔ " + label + "</" + COLOR_TAG + "> <gray>+"
                + AbilityInfo.formatNumber(bonus) + "%</gray>"));
    }

    /** True if the owner stands outside the victim's field of view, roughly at its back. */
    private boolean isBehind(LivingEntity victim) {
        Location victimLocation = victim.getLocation();
        Vector facing = victimLocation.getDirection().setY(0);
        Vector toAttacker = player().getLocation().toVector().subtract(victimLocation.toVector()).setY(0);
        if (facing.lengthSquared() < 1.0E-6 || toAttacker.lengthSquared() < 1.0E-6) {
            return false;
        }
        return facing.normalize().dot(toAttacker.normalize()) <= BACKSTAB_DOT;
    }

    // ------------------------------------------------------------------ active: shadow stealth

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        long duration = Math.max(1, params().ticks("stealth_duration"));

        // Invisibility hides the owner from viewers that are not enemies too (allies, spectators see the vanilla
        // effect); enemies do not receive the player at all, so armor, held items and the name tag vanish as well.
        int effectTicks = (int) Math.min(Integer.MAX_VALUE, duration);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, effectTicks + 5, 0, false, false, true));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, effectTicks, 0, false, false, true));
        stealth.begin(ctx(), owner, duration, () -> reveal(Reveal.EXPIRED));
        Stealth.dropAggro(ctx(), owner, AGGRO_DROP_RANGE);

        Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
        World world = body.getWorld();
        world.spawnParticle(Particle.LARGE_SMOKE, body, 16, 0.3, 0.6, 0.3, 0.02);
        world.spawnParticle(Particle.SQUID_INK, body, 10, 0.3, 0.5, 0.3, 0.05);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 14, 0.4, 0.7, 0.4, 0, SHADOW_FADE);
        Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.9f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_PHANTOM_FLAP, 0.8f, 0.6f + Fx.jitter(0.1f));
        return ActivationResult.success();
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        // The dance may end next to an airborne enemy; that drop is not the owner's fault.
        if (landing.absorb(event, ctx().currentTick())) {
            return;
        }
        if (stealth.isActive() && event.getDamage() > 0 && event instanceof EntityDamageByEntityEvent) {
            reveal(Reveal.HIT);
        }
    }

    @Override
    public void onTick(long tick) {
        if (tick % 2 == 0) {
            landing.update(player(), tick);
        }
        if (!stealth.isActive()) {
            return;
        }
        Player owner = player();
        if (tick % 4 == 0) {
            // The only tell: a faint shadow pooling at the owner's feet.
            Location feet = owner.getLocation().add(0, 0.05, 0);
            World world = feet.getWorld();
            world.spawnParticle(Particle.DUST, feet, 1, 0.2, 0.01, 0.2, 0, FOOTPRINT_DUST);
            world.spawnParticle(Particle.SMOKE, feet, 1, 0.15, 0.01, 0.15, 0.002);
        }
        if (tick % 5 == 0) {
            owner.sendActionBar(Text.mm("<dark_gray>☾</dark_gray> <" + COLOR_TAG + ">그림자 은신</" + COLOR_TAG
                    + "> <gray>" + Fx.seconds(stealth.ticksLeft(ctx().currentTick())) + "초</gray>"));
        }
        if (tick % 20 == 0) {
            stealth.hideFromEnemies(ctx(), owner);
            Stealth.dropAggro(ctx(), owner, AGGRO_DROP_RANGE);
        }
    }

    /** Ends the stealth (if any), showing the owner to everyone it was hidden from. */
    private void reveal(Reveal reason) {
        Player owner = player();
        if (!stealth.end(ctx(), owner) || !owner.isOnline()) {
            return;
        }
        removeOwnEffect(owner, PotionEffectType.INVISIBILITY);
        removeOwnEffect(owner, PotionEffectType.SPEED);
        if (reason == Reveal.REVOKE) {
            return;
        }

        Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
        World world = body.getWorld();
        world.spawnParticle(Particle.LARGE_SMOKE, body, 10, 0.3, 0.6, 0.3, 0.01);
        world.spawnParticle(Particle.DUST, body, 8, 0.35, 0.6, 0.35, 0, SHADOW_DUST);
        Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7f, 1.2f + Fx.jitter(0.1f));
        switch (reason) {
            case HIT -> owner.sendActionBar(Text.mm("<gray>공격받아 은신이 풀렸습니다</gray>"));
            case EXPIRED -> owner.sendActionBar(Text.mm("<gray>그림자 은신이 끝났습니다</gray>"));
            default -> {
                // ATTACK: the backstab feedback is shown by onDealDamage.
            }
        }
    }

    /** Removes our level-I effect; a stronger effect from another source is left alone. */
    private static void removeOwnEffect(Player owner, PotionEffectType type) {
        PotionEffect effect = owner.getPotionEffect(type);
        if (effect != null && effect.getAmplifier() == 0) {
            owner.removePotionEffect(type);
        }
    }

    // ------------------------------------------------------------------ ultimate: shadow dance

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 사용할 수 없습니다");
        }
        double radius = params().num("ult_radius");
        List<LivingEntity> targets = new ArrayList<>(ctx().enemiesNear(owner, owner.getLocation(), radius));
        if (targets.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        int strikes = Math.max(1, params().integer("ult_strikes"));
        // Visible enemies first; enemiesNear is nearest first and the sort is stable.
        targets.sort(Comparator.comparingInt(enemy -> owner.hasLineOfSight(enemy) ? 0 : 1));
        if (targets.size() > strikes) {
            targets = new ArrayList<>(targets.subList(0, strikes));
        }

        Location origin = owner.getLocation();
        Location body = origin.clone().add(0, owner.getHeight() * 0.5, 0);
        World world = origin.getWorld();
        world.spawnParticle(Particle.LARGE_SMOKE, body, 20, 0.4, 0.7, 0.4, 0.03);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 16, 0.5, 0.8, 0.5, 0, SHADOW_FADE);
        Fx.sound(origin, Sound.ENTITY_PHANTOM_SWOOP, 1.0f, 0.7f);
        Fx.sound(origin, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.0f, 0.8f);

        Loop.repeat(ctx(), player(), 0, DANCE_STEP_TICKS, strikes + 1L, new Dance(origin, targets, strikes, radius));
        return ActivationResult.success();
    }

    /** One step per strike, then a final step that brings the owner home. */
    private final class Dance implements LongPredicate {

        private final Location origin;
        private final List<LivingEntity> targets;
        private final int strikes;
        private final double leash;
        private final double damage;
        private final Map<UUID, Integer> hits = new HashMap<>();
        private int cursor;

        Dance(Location origin, List<LivingEntity> targets, int strikes, double radius) {
            this.origin = origin.clone();
            this.targets = targets;
            this.strikes = strikes;
            this.leash = Math.max(4, radius * DANCE_LEASH);
            this.damage = params().num("ult_damage");
        }

        @Override
        public boolean test(long step) {
            if (step >= strikes) {
                returnHome();
                return false;
            }
            LivingEntity target = nextTarget();
            if (target == null) {
                returnHome();
                return false;
            }
            strike(target);
            return true;
        }

        private @Nullable LivingEntity nextTarget() {
            for (int i = 0; i < targets.size(); i++) {
                int index = (cursor + i) % targets.size();
                LivingEntity candidate = targets.get(index);
                if (isValidTarget(candidate)) {
                    cursor = index + 1;
                    return candidate;
                }
            }
            return null;
        }

        private boolean isValidTarget(LivingEntity target) {
            return Combat.isTargetable(ctx(), player(), target)
                    && target.getWorld().equals(origin.getWorld())
                    && target.getLocation().distanceSquared(origin) <= leash * leash;
        }

        private void strike(LivingEntity target) {
            Player owner = player();
            int repeat = hits.merge(target.getUniqueId(), 1, Integer::sum) - 1;
            Location before = owner.getLocation();
            Location spot = Spots.around(ctx(), owner, target, repeat * REPEAT_ANGLE_STEP);
            if (spot != null && owner.teleport(spot, TeleportCause.PLUGIN)) {
                owner.setFallDistance(0);
                landing.armIfAirborne(owner, ctx().currentTick(), LANDING_GRACE_TICKS);
                shadowStep(owner, before, spot);
            } else {
                // No room next to the target: the shadow lashes out from where the owner stands instead.
                shadowStep(owner, before, target.getLocation());
            }

            Combat.hit(ctx(), owner, target, damage);

            Location at = target.getLocation().add(0, target.getHeight() * 0.55, 0);
            World world = at.getWorld();
            world.spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0.1, 0.1, 0.1, 0);
            world.spawnParticle(Particle.DUST, at, 14, 0.35, 0.45, 0.35, 0, SHADOW_DUST);
            world.spawnParticle(Particle.SQUID_INK, at, 6, 0.2, 0.3, 0.2, 0.04);
            world.spawnParticle(Particle.CRIT, at, 8, 0.3, 0.3, 0.3, 0.3);
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.25f + Fx.jitter(0.15f));
            Fx.sound(at, Sound.ENTITY_PHANTOM_BITE, 0.8f, 1.1f + Fx.jitter(0.15f));
        }

        private void returnHome() {
            Player owner = player();
            if (!owner.isValid() || !owner.getWorld().equals(origin.getWorld())) {
                return;
            }
            if (!Spots.isSafe(ctx(), origin, Body.standing(owner))) {
                return;
            }
            Location before = owner.getLocation();
            if (owner.teleport(origin, TeleportCause.PLUGIN)) {
                owner.setFallDistance(0);
                landing.armIfAirborne(owner, ctx().currentTick(), LANDING_GRACE_TICKS);
                shadowStep(owner, before, origin);
                Fx.sound(origin, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.9f, 0.85f + Fx.jitter(0.05f));
            }
        }
    }

    /** Dark streaks from the old position to the new one plus a puff of smoke where the owner left. */
    private static void shadowStep(Player owner, Location from, Location to) {
        World world = from.getWorld();
        Location fromBody = from.clone().add(0, owner.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.LARGE_SMOKE, fromBody, 6, 0.25, 0.5, 0.25, 0.01);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, fromBody, 8, 0.3, 0.6, 0.3, 0, SHADOW_FADE);
        if (!world.equals(to.getWorld())) {
            return;
        }
        int duration = Math.clamp(Math.round(from.distance(to) * 0.7), 3, 10);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 8; i++) {
            double ox = (random.nextDouble() - 0.5) * 0.5;
            double oy = random.nextDouble() * owner.getHeight();
            double oz = (random.nextDouble() - 0.5) * 0.5;
            Color color = i % 2 == 0 ? SHADOW_BLACK : SHADOW_VIOLET;
            world.spawnParticle(Particle.TRAIL, from.clone().add(ox, oy, oz), 1, 0, 0, 0, 0,
                    new Particle.Trail(to.clone().add(ox, oy, oz), color, duration));
        }
    }
}
