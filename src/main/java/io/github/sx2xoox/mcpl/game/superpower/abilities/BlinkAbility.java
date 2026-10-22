package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
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
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 순간이동자 — short-range teleportation. Phases through some incoming projectiles, blinks to a safe spot in the
 * view direction (never through walls) and, as the ultimate, tears through space to appear behind an enemy with an
 * empowered next hit.
 */
public final class BlinkAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("blink", "순간이동자", Material.ENDER_PEARL)
            .color(TextColor.color(0xB57BFF))
            .difficulty(2)
            .passive("위상 회피",
                    "받는 투사체 공격을 {dodge_chance}% 확률로 흘려보냅니다. 회피하면 점멸 쿨타임이 {dodge_refund}초 "
                            + "줄어듭니다.")
            .active("점멸",
                    "바라보는 방향으로 최대 {distance}블록 떨어진 안전한 지점으로 순간이동합니다. 벽은 통과할 수 없습니다.",
                    6)
            .ultimate("차원 습격",
                    "조준한 적(없으면 가장 가까운 적, {ult_range}블록 이내)의 등 뒤로 순간이동합니다. {empower_duration}초 "
                            + "안에 가하는 다음 공격은 피해가 {empower_multiplier}배가 됩니다.")
            .ultimateNotStandalone()
            .param("dodge_chance", 20)
            .param("dodge_refund", 1)
            .param("distance", 10)
            .param("ult_range", 30)
            .param("empower_duration", 5)
            .param("empower_multiplier", 2)
            .build();

    private static final String COLOR_TAG = "#B57BFF";
    private static final Color VOID_PURPLE = Color.fromRGB(0xB5, 0x7B, 0xFF);
    private static final Color RIFT_CYAN = Color.fromRGB(0x7F, 0xE8, 0xFF);
    private static final Particle.DustTransition RIFT_DUST = new Particle.DustTransition(VOID_PURPLE, RIFT_CYAN, 1.2f);
    private static final Particle.DustOptions EMPOWER_DUST = new Particle.DustOptions(VOID_PURPLE, 0.8f);

    // Blink search
    /** A blink that would move less than this (blocks) is refused instead of wasting the cooldown. */
    private static final double MIN_BLINK = 1.5;
    private static final double SAMPLE_STEP = 0.5;
    /** Distance kept from the wall the view ray hit, so the body never clips into it. */
    private static final double WALL_MARGIN = 0.5;
    /** Upward adjustments tried for each sample (looking down at the floor puts the eye-level point underground). */
    private static final double[] LIFTS = {0, 0.5, 1.0, 1.5, 2.0};
    /** Blink destinations hovering at most this high above ground are snapped onto it. */
    private static final double GROUND_SNAP = 1.1;
    private static final int TRAIL_STREAKS = 12;
    private static final long LANDING_GRACE_TICKS = 100;

    /** Generation counter of the empowered strike, so a stale expiry timer never clears a newer one. */
    private int empowerToken;
    private boolean empowered;
    /** Cancels the fall damage of the landing after a teleport that ended in mid-air. */
    private final LandingGuard landing = new LandingGuard();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: phase dodge

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        // A blink that ended in mid-air should not be punished by the fall that follows.
        if (landing.absorb(event, ctx().currentTick())) {
            return;
        }
        if (event.getDamage() <= 0 || !isProjectileHit(event)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() * 100.0 >= params().num("dodge_chance")) {
            return;
        }
        event.setCancelled(true);
        Player owner = player();
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        World world = body.getWorld();
        world.spawnParticle(Particle.REVERSE_PORTAL, body, 24, 0.3, 0.5, 0.3, 0.04);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 8, 0.35, 0.55, 0.35, 0, RIFT_DUST);
        Fx.sound(body, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.7f + Fx.jitter(0.15f));

        // Only a running blink cooldown can be shortened, and the feedback shows what was actually taken off.
        double refund = Math.min(params().num("dodge_refund"), ctx().cooldownRemaining(owner));
        String suffix = "";
        if (refund > 0) {
            ctx().reduceCooldown(owner, refund);
            suffix = " <gray>점멸 -" + AbilityInfo.formatNumber(Math.ceil(refund * 10) / 10.0) + "초</gray>";
        }
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">✦ 위상 회피!</" + COLOR_TAG + ">" + suffix));
    }

    private static boolean isProjectileHit(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Projectile) {
            return true;
        }
        if (event.getCause() == DamageCause.PROJECTILE) {
            return true;
        }
        Tag<DamageType> projectile = DamageTypeTags.IS_PROJECTILE;
        return projectile != null && projectile.isTagged(event.getDamageSource().getDamageType());
    }

    // ------------------------------------------------------------------ active: blink

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 점멸할 수 없습니다");
        }
        Location from = owner.getLocation();
        Location destination = findBlinkSpot(owner, params().num("distance"));
        if (destination == null) {
            return ActivationResult.fail("순간이동할 공간이 없습니다");
        }
        if (!teleport(owner, from, destination)) {
            return ActivationResult.fail("순간이동이 막혔습니다");
        }
        landing.armIfAirborne(owner, ctx().currentTick(), LANDING_GRACE_TICKS);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">✦ 점멸</" + COLOR_TAG + "> <gray>"
                + AbilityInfo.formatNumber(Math.round(from.distance(destination) * 10) / 10.0) + "블록</gray>"));
        return ActivationResult.success();
    }

    /**
     * The farthest safe standing spot along the view ray, before the first wall. Each sample is tried at eye level
     * first and then slightly higher (so looking down at the floor lands on top of it), snapped onto ground right
     * below, must be reachable in a straight line from the owner's eyes and must pass {@link Spots#isSafe} (no
     * hazards, no void, inside the arena).
     */
    private @Nullable Location findBlinkSpot(Player owner, double maxDistance) {
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!Double.isFinite(direction.lengthSquared()) || direction.lengthSquared() < 1.0E-6) {
            return null;
        }
        direction.normalize();
        double reach = Math.max(0, maxDistance);
        RayTraceResult wall = world.rayTraceBlocks(eye, direction, reach, FluidCollisionMode.NEVER, true);
        if (wall != null) {
            reach = wall.getHitPosition().distance(eye.toVector()) - WALL_MARGIN;
        }

        Body body = Body.standing(owner);
        Location start = owner.getLocation();

        for (double d = reach; d >= MIN_BLINK - 1.0E-6; d -= SAMPLE_STEP) {
            Location point = eye.clone().add(direction.clone().multiply(d));
            for (double lift : LIFTS) {
                Location feet = point.clone().subtract(0, body.eyeHeight() - lift, 0);
                Spots.snapToGround(world, feet, GROUND_SNAP);
                if (feet.distanceSquared(start) < MIN_BLINK * MIN_BLINK) {
                    continue;
                }
                if (!Spots.isSafe(ctx(), feet, body)
                        || !Spots.clearPath(eye, feet.clone().add(0, body.eyeHeight(), 0))) {
                    continue;
                }
                feet.setYaw(start.getYaw());
                feet.setPitch(start.getPitch());
                return feet;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ ultimate: dimension raid

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 사용할 수 없습니다");
        }
        double range = params().num("ult_range");
        List<LivingEntity> candidates = new ArrayList<>();
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null) {
            candidates.add(aimed);
        }
        List<LivingEntity> nearby = new ArrayList<>(ctx().enemiesNear(owner, owner.getLocation(), range));
        // Visible enemies first, then by distance (enemiesNear is already nearest first; the sort is stable).
        nearby.sort(Comparator.comparingInt(enemy -> owner.hasLineOfSight(enemy) ? 0 : 1));
        for (LivingEntity enemy : nearby) {
            if (!candidates.contains(enemy)) {
                candidates.add(enemy);
            }
        }
        if (candidates.isEmpty()) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안에 적이 없습니다");
        }

        for (LivingEntity target : candidates) {
            Location destination = Spots.around(ctx(), owner, target, 0);
            if (destination == null) {
                continue;
            }
            if (!teleport(owner, owner.getLocation(), destination)) {
                return ActivationResult.fail("순간이동이 막혔습니다");
            }
            landing.armIfAirborne(owner, ctx().currentTick(), LANDING_GRACE_TICKS);
            ripple(target);
            empower();
            return ActivationResult.success();
        }
        return ActivationResult.fail("적 주변에 순간이동할 공간이 없습니다");
    }

    /** Arrival cue for the victim: a creepy stare only they hear, and a rift flash around them. */
    private void ripple(LivingEntity target) {
        Location at = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        at.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at, 30, 0.4, 0.6, 0.4, 0.08);
        if (target instanceof Player victim) {
            victim.playSound(victim, Sound.ENTITY_ENDERMAN_STARE, SoundCategory.PLAYERS, 0.7f, 1.4f);
        }
    }

    private void empower() {
        Player owner = player();
        int token = ++empowerToken;
        empowered = true;
        long duration = Math.max(1, params().ticks("empower_duration"));
        String multiplier = AbilityInfo.formatNumber(params().num("empower_multiplier"));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">✦ 차원 습격</" + COLOR_TAG + "> <white>다음 공격 피해 ×"
                + multiplier + "</white> <gray>(" + AbilityInfo.formatNumber(params().num("empower_duration"))
                + "초)</gray>"));
        ctx().runLater(owner, duration, () -> {
            if (token != empowerToken || !empowered) {
                return;
            }
            empowered = false;
            if (owner.isOnline() && ctx().isAlive(owner)) {
                owner.sendActionBar(Text.mm("<gray>차원 습격의 힘이 흩어졌습니다</gray>"));
                Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.6f);
            }
        });
    }

    @Override
    public void onTick(long tick) {
        if (tick % 2 == 0) {
            landing.update(player(), tick);
        }
        if (!empowered || tick % 3 != 0) {
            return;
        }
        Player owner = player();
        Location hand = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        owner.getWorld().spawnParticle(Particle.DUST, hand, 2, 0.3, 0.4, 0.3, 0, EMPOWER_DUST);
        if (tick % 9 == 0) {
            owner.getWorld().spawnParticle(Particle.REVERSE_PORTAL, hand, 2, 0.25, 0.4, 0.25, 0.01);
        }
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!empowered || event.isCancelled() || ctx().isAbilityDamage()) {
            return;
        }
        empowered = false;
        empowerToken++;
        double multiplier = Math.max(1, params().num("empower_multiplier"));
        event.setDamage(event.getDamage() * multiplier);

        Location at = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
        World world = at.getWorld();
        world.spawnParticle(Particle.ENCHANTED_HIT, at, 18, 0.3, 0.4, 0.3, 0.3);
        world.spawnParticle(Particle.REVERSE_PORTAL, at, 30, 0.3, 0.4, 0.3, 0.15);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 12, 0.4, 0.5, 0.4, 0, RIFT_DUST);
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.55f + Fx.jitter(0.05f));
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">✦ 차원 일격!</" + COLOR_TAG + "> <white>×"
                + AbilityInfo.formatNumber(multiplier) + "</white>"));
    }

    // ------------------------------------------------------------------ teleport + visuals

    /** Teleports the owner with departure/arrival effects. False if something cancelled the teleport. */
    private static boolean teleport(Player owner, Location from, Location to) {
        if (!owner.teleport(to, TeleportCause.PLUGIN)) {
            return false;
        }
        owner.setFallDistance(0);
        World world = from.getWorld();
        Location fromBody = from.clone().add(0, owner.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.PORTAL, fromBody, 40, 0.3, 0.6, 0.3, 0.6);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, fromBody, 10, 0.3, 0.6, 0.3, 0, RIFT_DUST);
        Fx.sound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 1.15f + Fx.jitter(0.1f));

        // Streaks flying from the old body to the new one.
        Vector shift = to.toVector().subtract(from.toVector());
        int duration = Math.clamp(Math.round(shift.length() * 0.8), 4, 14);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < TRAIL_STREAKS; i++) {
            double ox = (random.nextDouble() - 0.5) * 0.6;
            double oy = random.nextDouble() * owner.getHeight();
            double oz = (random.nextDouble() - 0.5) * 0.6;
            Location start = from.clone().add(ox, oy, oz);
            Location end = to.clone().add(ox, oy, oz);
            Color color = i % 2 == 0 ? VOID_PURPLE : RIFT_CYAN;
            world.spawnParticle(Particle.TRAIL, start, 1, 0, 0, 0, 0, new Particle.Trail(end, color, duration));
        }

        Location toBody = to.clone().add(0, owner.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.REVERSE_PORTAL, toBody, 30, 0.3, 0.6, 0.3, 0.06);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, toBody, 10, 0.3, 0.6, 0.3, 0, RIFT_DUST);
        Fx.sound(to, Sound.ITEM_CHORUS_FRUIT_TELEPORT, 0.9f, 1.3f + Fx.jitter(0.1f));
        return true;
    }
}
