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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth;
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
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 그림자 — assassin. Hits harder from behind, vanishes completely from enemies' screens for a few seconds and, as
 * the ultimate, flickers between nearby enemies' backs, cutting each, before slipping back to where it started.
 *
 * <p>Look: ink-dark slash displays (a black blade line with a violet glow) on every backstab and dance cut, a pool of
 * shadow the owner sinks into when vanishing and returns to after the dance, faint footprints while hidden.
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
    /** Dusky lavender core, bruised violet body, near-black edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0x9A8CC2, 0x4B3B6B, 0x1B1426);
    private static final Particle.DustOptions FOOTPRINT_DUST = new Particle.DustOptions(PALETTE.edge(), 0.9f);

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
    /** The fall after a dance step or the return home is harmless for at most this long. */
    private static final long LANDING_GRACE_TICKS = 100;

    // Visuals
    /** Tilt of a cut from the horizontal, degrees. */
    private static final float SLASH_ROLL = 38;
    /** Footprints are left at most this often while hidden, and only after moving this far. */
    private static final long FOOTPRINT_TICKS = 4;
    private static final double FOOTPRINT_STRIDE = 0.45;

    private final Stealth stealth = new Stealth();
    /** Generation of the running landing protection; a newer one retires the older loop. */
    private int fallGuardToken;
    /** Where the last footprint was left while hidden, and on which side. */
    private @Nullable Location lastFootprint;
    private boolean leftFoot;

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

        // A dark cut across the back; an ambush from stealth crosses it into an X.
        Location at = flat(victim.getLocation().add(0, victim.getHeight() * 0.6, 0));
        Vector cut = at.toVector().subtract(player().getLocation().toVector());
        float tilt = SLASH_ROLL + Fx.jitter(10);
        slash(at, cut, fromStealth ? 1.7f : 1.4f, tilt, 0, false);
        if (fromStealth) {
            slash(at, cut, 1.7f, -tilt, 2, false);
        }
        World world = at.getWorld();
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, Vfx.n(ctx(), 10), 0.3, 0.35, 0.3, 0,
                PALETTE.fade(1.2f));
        Vfx.cloud(ctx(), at, Particle.CRIT, 10, 0.25, 0.25, null, false);
        Vfx.cloud(ctx(), at, Particle.SQUID_INK, 4, 0.15, 0.02, null, false);
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.65f + Fx.jitter(0.08f));
        Fx.sound(at, Sound.ENTITY_PHANTOM_BITE, 0.7f, 0.8f + Fx.jitter(0.1f));
        if (fromStealth) {
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.6f + Fx.jitter(0.06f));
        }
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
        lastFootprint = owner.getLocation();

        vanishVisual(owner);
        return ActivationResult.success();
    }

    /** The owner sinks into a pool of shadow: wisps drawn down into it and a dark helix closing around the body. */
    private void vanishVisual(Player owner) {
        Location feet = flat(owner.getLocation().add(0, 0.03, 0));
        Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
        shadowPool(feet, 1.5f, 3, 12);
        Vfx.helix(ctx(), feet, 0.55, owner.getHeight() * 1.05, 1.25, 2, 10,
                ThreadLocalRandom.current().nextDouble(Math.PI * 2), Particle.DUST_COLOR_TRANSITION,
                PALETTE.fade(1.0f), false);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int wisps = Vfx.n(ctx(), 12);
        for (int i = 0; i < wisps; i++) {
            Location from = body.clone().add((random.nextDouble() - 0.5) * 1.1, (random.nextDouble() - 0.5) * 1.6,
                    (random.nextDouble() - 0.5) * 1.1);
            Vfx.trail(from, feet.clone().add((random.nextDouble() - 0.5) * 0.6, 0, (random.nextDouble() - 0.5) * 0.6),
                    i % 3 == 0 ? PALETTE.core() : PALETTE.edge(), 6 + random.nextInt(4), false);
        }
        Vfx.cloud(ctx(), body, Particle.LARGE_SMOKE, 10, 0.3, 0.02, null, false);
        Vfx.cloud(ctx(), body, Particle.SQUID_INK, 6, 0.25, 0.03, null, false);
        Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.9f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_PHANTOM_FLAP, 0.8f, 0.6f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.BLOCK_SCULK_SPREAD, 0.7f, 0.7f + Fx.jitter(0.1f));
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (stealth.isActive() && event.getDamage() > 0 && event instanceof EntityDamageByEntityEvent) {
            reveal(Reveal.HIT);
        }
    }

    @Override
    public void onTick(long tick) {
        if (!stealth.isActive()) {
            return;
        }
        Player owner = player();
        if (tick % FOOTPRINT_TICKS == 0) {
            footprint(owner, tick);
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

    /** The only tell while hidden: faint dark footprints, alternating sides, and now and then a wisp of smoke. */
    private void footprint(Player owner, long tick) {
        Location feet = owner.getLocation();
        if (lastFootprint != null && lastFootprint.getWorld().equals(feet.getWorld())
                && lastFootprint.distanceSquared(feet) < FOOTPRINT_STRIDE * FOOTPRINT_STRIDE) {
            if (tick % (FOOTPRINT_TICKS * 4) == 0) {
                // Standing still: a shadow pooling at the feet.
                Vfx.ring(ctx(), feet.clone().add(0, 0.05, 0), 0.3, 5, tick * 0.2, Particle.DUST, FOOTPRINT_DUST,
                        false);
            }
            return;
        }
        Vector walk = lastFootprint == null || !lastFootprint.getWorld().equals(feet.getWorld())
                ? Motion.horizontalFacing(feet) : Motion.horizontal(feet.toVector().subtract(lastFootprint.toVector()));
        if (walk.lengthSquared() < 1.0E-6) {
            walk = Motion.horizontalFacing(feet);
        }
        lastFootprint = feet.clone();
        leftFoot = !leftFoot;
        Vector side = walk.clone().crossProduct(new Vector(0, 1, 0)).multiply(leftFoot ? 0.14 : -0.14);
        Location print = feet.clone().add(side).add(0, 0.04, 0);
        Vfx.dot(print, Particle.DUST, FOOTPRINT_DUST, false);
        Vfx.dot(print.clone().add(walk.clone().multiply(0.12)), Particle.DUST, FOOTPRINT_DUST, false);
        if (leftFoot) {
            Vfx.cloud(ctx(), print.clone().add(0, 0.1, 0), Particle.SMOKE, 1, 0.05, 0.005, null, false);
        }
    }

    /** Ends the stealth (if any), showing the owner to everyone it was hidden from. */
    private void reveal(Reveal reason) {
        Player owner = player();
        if (!stealth.end(ctx(), owner) || !owner.isOnline()) {
            return;
        }
        lastFootprint = null;
        removeOwnEffect(owner, PotionEffectType.INVISIBILITY);
        removeOwnEffect(owner, PotionEffectType.SPEED);
        if (reason == Reveal.REVOKE) {
            return;
        }

        // The shadow bursts off the body as a dark ripple.
        Location feet = flat(owner.getLocation().add(0, 0.08, 0));
        Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
        Vfx.shockwave(ctx(), owner, feet, 0.25, 1.5, 4, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.1f), false);
        Vfx.cloud(ctx(), body, Particle.LARGE_SMOKE, 8, 0.3, 0.015, null, false);
        body.getWorld().spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 8), 0.35, 0.6, 0.35, 0, PALETTE.dust(1.3f));
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
        long danceTicks = (strikes + 1L) * DANCE_STEP_TICKS;
        danceTelegraph(owner, origin, targets, danceTicks);
        Loop.repeat(ctx(), player(), 0, DANCE_STEP_TICKS, strikes + 1L, new Dance(origin, targets, strikes, radius));
        return ActivationResult.success();
    }

    /**
     * The dance begins: the owner's shadow stays behind as a pool (it returns into it), the marked enemies get a dark
     * ring at their feet and ink bursts off the owner.
     */
    private void danceTelegraph(Player owner, Location origin, List<LivingEntity> targets, long danceTicks) {
        Location feet = flat(origin.clone().add(0, 0.03, 0));
        Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
        shadowPool(feet, 1.7f, 4, (int) danceTicks + 6);
        Vfx.ring(ctx(), feet.clone().add(0, 0.05, 0), 1.6, 28, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.2f),
                true);
        for (LivingEntity target : targets) {
            Location mark = flat(target.getLocation().add(0, 0.08, 0));
            Vfx.ring(ctx(), mark, 0.75, 16, 0, Particle.DUST, PALETTE.dust(1.1f), true);
            Vfx.trail(body, mark.clone().add(0, target.getHeight() * 0.5, 0), PALETTE.edge(), 5, true);
        }
        Vfx.cloud(ctx(), body, Particle.LARGE_SMOKE, 16, 0.35, 0.03, null, true);
        body.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 16), 0.5, 0.8, 0.5, 0,
                PALETTE.fade(1.3f), true);
        Fx.sound(origin, Sound.ENTITY_PHANTOM_SWOOP, 1.0f, 0.7f);
        Fx.sound(origin, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.0f, 0.8f);
        Fx.sound(origin, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.7f, 0.5f + Fx.jitter(0.05f));
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
        private int struck;

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
                shadowStep(owner, before, spot);
            } else {
                // No room next to the target: the shadow lashes out from where the owner stands instead.
                shadowStep(owner, before, target.getLocation());
            }
            // The step may end next to an airborne enemy; that drop is not the owner's fault.
            guardLanding(owner);

            Combat.hit(ctx(), owner, target, damage);
            cutVisual(owner, target, struck++);
        }

        private void returnHome() {
            Player owner = player();
            if (!owner.isValid() || !owner.getWorld().equals(origin.getWorld())) {
                return;
            }
            // Even when home is blocked, the owner may be left in mid-air next to the last target.
            guardLanding(owner);
            if (!Spots.isSafe(ctx(), origin, Body.standing(owner))) {
                return;
            }
            Location before = owner.getLocation();
            if (owner.teleport(origin, TeleportCause.PLUGIN)) {
                owner.setFallDistance(0);
                shadowStep(owner, before, origin);
                // Rising back out of the pool left behind.
                Location feet = flat(origin.clone().add(0, 0.05, 0));
                Vfx.helix(ctx(), feet, 0.6, owner.getHeight(), 1.0, 2, 10, 0, Particle.DUST_COLOR_TRANSITION,
                        PALETTE.glow(1.0f), true);
                Fx.sound(origin, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.9f, 0.85f + Fx.jitter(0.05f));
                Fx.sound(origin, Sound.ENTITY_PHANTOM_FLAP, 0.7f, 0.9f + Fx.jitter(0.1f));
            }
        }
    }

    /** A dance cut: a crossing pair of dark blades through the target, ink and sparks, a sweep and a bite. */
    private void cutVisual(Player owner, LivingEntity target, int index) {
        Location at = flat(target.getLocation().add(0, target.getHeight() * 0.55, 0));
        Vector cut = at.toVector().subtract(owner.getLocation().toVector());
        float tilt = (index % 2 == 0 ? SLASH_ROLL : -SLASH_ROLL) + Fx.jitter(12);
        slash(at, cut, 2.1f, tilt, 0, true);
        slash(at, cut, 2.1f, tilt - Math.copySign(76, tilt), 2, true);
        World world = at.getWorld();
        world.spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0.1, 0.1, 0.1, 0, null, true);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, Vfx.n(ctx(), 16), 0.35, 0.45, 0.35, 0,
                PALETTE.fade(1.3f), true);
        Vfx.cloud(ctx(), at, Particle.SQUID_INK, 6, 0.2, 0.04, null, true);
        Vfx.cloud(ctx(), at, Particle.CRIT, 8, 0.3, 0.3, null, true);
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.25f + Fx.jitter(0.15f));
        Fx.sound(at, Sound.ENTITY_PHANTOM_BITE, 0.8f, 1.1f + Fx.jitter(0.15f));
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6f, 0.75f + Fx.jitter(0.1f));
    }

    /**
     * Keeps the owner's fall distance at zero until it stands on the ground or swims (at most
     * {@link #LANDING_GRACE_TICKS}), so a dance step that ends in mid-air never turns into fall damage. It is a loop
     * of its own rather than a {@code LandingGuard}: it needs no tick or damage hooks, so it also protects a Gambler
     * that borrowed the dance.
     */
    private void guardLanding(Player owner) {
        int token = ++fallGuardToken;
        Loop.repeat(ctx(), owner, 0, 1, LANDING_GRACE_TICKS, run -> {
            if (token != fallGuardToken) {
                return false;
            }
            owner.setFallDistance(0);
            return !Motion.isGrounded(owner) && !owner.isInWater();
        });
    }

    // ------------------------------------------------------------------ visuals

    /** Dark streaks from the old position to the new one, plus an inky afterimage where the owner left. */
    private void shadowStep(Player owner, Location from, Location to) {
        World world = from.getWorld();
        double height = owner.getHeight();
        Location fromBody = from.clone().add(0, height * 0.5, 0);
        Vfx.cloud(ctx(), fromBody, Particle.LARGE_SMOKE, 6, 0.25, 0.01, null, true);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, fromBody, Vfx.n(ctx(), 10), 0.25, 0.6, 0.25, 0,
                PALETTE.fade(1.4f), true);
        if (!world.equals(to.getWorld())) {
            return;
        }
        int duration = Math.clamp(Math.round(from.distance(to) * 0.7), 3, 10);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int streaks = Vfx.n(ctx(), 10);
        for (int i = 0; i < streaks; i++) {
            double ox = (random.nextDouble() - 0.5) * 0.5;
            double oy = random.nextDouble() * height;
            double oz = (random.nextDouble() - 0.5) * 0.5;
            Vfx.trail(from.clone().add(ox, oy, oz), to.clone().add(ox, oy, oz),
                    i % 3 == 0 ? PALETTE.core() : PALETTE.edge(), duration, true);
        }
    }

    /**
     * A blade line through {@code center}, across {@code direction} and tilted by {@code rollDegrees}: a black core
     * with a violet glow, that whips out to full length, then thins away. Two displays, after {@code delayTicks}.
     */
    private void slash(Location center, Vector direction, float length, float rollDegrees, int delayTicks,
                       boolean force) {
        Player owner = player();
        Location at = flat(center);
        Quaternionf rotation = new Quaternionf().rotateY((float) -yawOf(direction))
                .rotateZ((float) Math.toRadians(rollDegrees));
        if (delayTicks <= 0) {
            slashLayers(owner, at, rotation, length);
        } else {
            ctx().runLater(owner, delayTicks, () -> slashLayers(owner, at, rotation, length));
        }
        Location tip = at.clone().add(new Vector(Math.cos(Math.toRadians(rollDegrees)) * length * 0.5,
                Math.sin(Math.toRadians(rollDegrees)) * length * 0.5, 0)
                .rotateAroundY(-yawOf(direction)));
        Vfx.trail(at.clone().subtract(tip.toVector().subtract(at.toVector())), tip, PALETTE.core(), 3, force);
    }

    private void slashLayers(Player owner, Location at, Quaternionf rotation, float length) {
        slashLayer(owner, at, rotation, Material.PURPLE_STAINED_GLASS, length * 1.08f, 0.16f);
        slashLayer(owner, at, rotation, Material.BLACK_CONCRETE, length, 0.05f);
    }

    private void slashLayer(Player owner, Location at, Quaternionf rotation, Material material, float length,
                            float thickness) {
        BlockDisplay blade = Displays.block(ctx(), owner, at, material.createBlockData(),
                bar(rotation, length * 0.12f, thickness * 0.6f), 9);
        ctx().runLater(owner, 1, () -> Displays.animate(blade, bar(rotation, length, thickness), 2));
        ctx().runLater(owner, 4, () -> Displays.animate(blade, bar(rotation, length * 1.2f, 0.001f), 4));
    }

    /** A thin bar along the local x axis, centered, rotated by {@code rotation}. */
    private static Transformation bar(Quaternionf rotation, float length, float thickness) {
        Vector3f scale = new Vector3f(length, thickness, thickness);
        Vector3f center = new Vector3f(0.5f).mul(scale).rotate(rotation);
        return new Transformation(center.negate(), rotation, scale, new Quaternionf());
    }

    /**
     * A pool of shadow on the ground: a black eight-pointed star (two squares) on a violet rim that spreads over
     * {@code growTicks}, stays and shrinks away so it is gone after {@code lifeTicks}. Three displays.
     */
    private void shadowPool(Location feet, float size, int growTicks, int lifeTicks) {
        Player owner = player();
        Location at = flat(feet);
        poolLayer(owner, at, Material.PURPLE_STAINED_GLASS, size * 1.12f, 0.012f, 22.5f, growTicks, lifeTicks);
        poolLayer(owner, at, Material.BLACK_CONCRETE, size, 0.02f, 0, growTicks, lifeTicks);
        poolLayer(owner, at, Material.BLACK_CONCRETE, size, 0.02f, 45, growTicks, lifeTicks);
    }

    private void poolLayer(Player owner, Location at, Material material, float size, float thickness, float yaw,
                           int growTicks, int lifeTicks) {
        int shrink = 6;
        BlockDisplay layer = Displays.block(ctx(), owner, at, material.createBlockData(),
                Displays.transform(0, thickness / 2, 0, 0.05f, thickness, 0.05f, yaw, 0, 0), lifeTicks + 2L);
        ctx().runLater(owner, 1, () -> Displays.animate(layer,
                Displays.transform(0, thickness / 2, 0, size, thickness, size, yaw, 0, 0), Math.max(1, growTicks)));
        ctx().runLater(owner, Math.max(2, lifeTicks - shrink), () -> {
            if (layer.isValid()) {
                Displays.shrinkAway(ctx(), owner, layer, shrink);
            }
        });
    }

    /** A copy without yaw and pitch, so display entities are not turned by the entity rotation as well. */
    private static Location flat(Location location) {
        Location copy = location.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }

    /** The yaw (radians, Minecraft convention) of the horizontal part of {@code direction}. */
    private static double yawOf(Vector direction) {
        if (direction.getX() * direction.getX() + direction.getZ() * direction.getZ() < 1.0E-8) {
            return 0;
        }
        return Math.atan2(-direction.getX(), direction.getZ());
    }
}
