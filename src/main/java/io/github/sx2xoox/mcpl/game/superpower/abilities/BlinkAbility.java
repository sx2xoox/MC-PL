package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 순간이동자 — short-range teleportation. Phases through ranged attacks, blinks to a safe spot in the view direction
 * (never through walls) and, as the ultimate, tears through space to appear behind an enemy with an empowered next
 * hit.
 *
 * <p>Look: every teleport opens a pair of violet rifts (glass rhombus displays with a bright seam) that close to a
 * line behind the owner, with an implosion of motes at the departure and a ripple at the arrival. The empowered
 * state shows two amethyst shards orbiting the owner.
 */
public final class BlinkAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("blink", "순간이동자", Material.ENDER_PEARL)
            .color(TextColor.color(0xB57BFF))
            .difficulty(2)
            .passive("위상 회피",
                    "투사체와 {dodge_range}블록 이상 떨어진 적의 원거리 공격을 {dodge_chance}% 확률로 흘려보냅니다. "
                            + "회피하면 점멸 쿨타임이 {dodge_refund}초 줄어듭니다.")
            .active("점멸",
                    "바라보는 방향으로 최대 {distance}블록 떨어진 안전한 지점으로 순간이동합니다. 벽은 통과할 수 없습니다.",
                    6)
            .ultimate("차원 습격",
                    "조준한 적(없으면 가장 가까운 적, {ult_range}블록 이내)의 등 뒤로 순간이동합니다. {empower_duration}초 "
                            + "안에 가하는 다음 공격은 피해가 {empower_multiplier}배가 됩니다.")
            .ultimateNotStandalone()
            .param("dodge_chance", 20)
            .param("dodge_refund", 1)
            .param("dodge_range", 5)
            .param("distance", 10)
            .param("ult_range", 30)
            .param("empower_duration", 5)
            .param("empower_multiplier", 2)
            .build();

    private static final String COLOR_TAG = "#B57BFF";
    /** Lavender core, void purple body, deep violet edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE3C7FF, 0xB57BFF, 0x4A1F7A);

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
    private static final long LANDING_GRACE_TICKS = 100;

    // Visuals
    private static final float SQRT2 = (float) Math.sqrt(2);
    /** A unit cube turned 45° around its depth axis: scaled, it becomes a rhombus (the rift shape). */
    private static final Quaternionf DIAMOND = new Quaternionf().rotateZ((float) Math.toRadians(45));
    private static final int SHARD_COUNT = 2;
    private static final double SHARD_ORBIT = 0.8;
    private static final double SHARD_SPIN = 0.32;

    /** Generation counter of the empowered strike, so a stale expiry timer never clears a newer one. */
    private int empowerToken;
    private boolean empowered;
    /** Cancels the fall damage of the landing after a teleport that ended in mid-air. */
    private final LandingGuard landing = new LandingGuard();
    /** The amethyst shards orbiting the owner while the next hit is empowered. */
    private final List<ItemDisplay> shards = new ArrayList<>();

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
        if (event.getDamage() <= 0 || !isRangedHit(event)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() * 100.0 >= params().num("dodge_chance")) {
            return;
        }
        event.setCancelled(true);
        Player owner = player();
        Vector incoming = event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager().getWorld().equals(owner.getWorld())
                ? owner.getLocation().toVector().subtract(byEntity.getDamager().getLocation().toVector())
                : owner.getLocation().getDirection();
        phaseVisual(owner, incoming);

        // Only a running blink cooldown can be shortened, and the feedback shows what was actually taken off.
        double refund = Math.min(params().num("dodge_refund"), ctx().cooldownRemaining(owner));
        String suffix = "";
        if (refund > 0) {
            ctx().reduceCooldown(owner, refund);
            suffix = " <gray>점멸 -" + AbilityInfo.formatNumber(Math.ceil(refund * 10) / 10.0) + "초</gray>";
        }
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">✦ 위상 회피!</" + COLOR_TAG + ">" + suffix));
    }

    /**
     * A vanilla projectile, or a hit whose direct damager is an attacker at least {@code dodge_range} blocks away.
     * Ability "projectiles" (fireballs, ice spears, skill arrows, grenades...) are delivered as ability damage from
     * the caster, so their distance is what tells them apart from melee.
     */
    private boolean isRangedHit(EntityDamageEvent event) {
        if (isProjectileHit(event)) {
            return true;
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return false;
        }
        Player owner = player();
        Entity damager = byEntity.getDamager();
        Player attacker = ctx().resolveAttacker(damager);
        if (attacker == null || attacker.equals(owner) || !damager.getWorld().equals(owner.getWorld())) {
            return false;
        }
        double range = params().num("dodge_range");
        return damager.getLocation().distanceSquared(owner.getLocation()) >= range * range;
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

    /** The body slips out of phase: a thin rift flickers where the hit passed, with two quick rings around it. */
    private void phaseVisual(Player owner, Vector incoming) {
        Location chest = flat(owner.getLocation().add(0, owner.getHeight() * 0.55, 0));
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        rift(chest, incoming, 0.55f, (float) owner.getHeight() * 0.95f, 25, 0, 1, 1, 4, false);
        Vfx.ring(ctx(), chest, 0.6, 14, phase, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), false);
        Vfx.ring(ctx(), chest.clone().add(0, -0.5, 0), 0.75, 12, phase + 0.4, Particle.DUST, PALETTE.dust(0.7f),
                false);
        Vfx.cloud(ctx(), chest, Particle.REVERSE_PORTAL, 10, 0.25, 0.03, null, false);
        Vfx.cloud(ctx(), chest, Particle.END_ROD, 3, 0.2, 0.05, null, false);
        Fx.sound(chest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.55f, 1.75f + Fx.jitter(0.15f));
        Fx.sound(chest, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 0.9f, 1.5f + Fx.jitter(0.2f));
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
        if (!teleport(owner, from, destination, false)) {
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
            if (!teleport(owner, owner.getLocation(), destination, true)) {
                return ActivationResult.fail("순간이동이 막혔습니다");
            }
            landing.armIfAirborne(owner, ctx().currentTick(), LANDING_GRACE_TICKS);
            ripple(target);
            empower();
            return ActivationResult.success();
        }
        return ActivationResult.fail("적 주변에 순간이동할 공간이 없습니다");
    }

    /** Arrival cue for the victim: a creepy stare only they hear, and a rift ripple running out from their feet. */
    private void ripple(LivingEntity target) {
        Location feet = flat(target.getLocation().add(0, 0.1, 0));
        Vfx.shockwave(ctx(), player(), feet, 0.4, 1.8, 6, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.1f), true);
        Vfx.cloud(ctx(), feet.clone().add(0, target.getHeight() * 0.5, 0), Particle.REVERSE_PORTAL, 24, 0.35, 0.06,
                null, true);
        if (target instanceof Player victim) {
            victim.playSound(victim, Sound.ENTITY_ENDERMAN_STARE, SoundCategory.PLAYERS, 0.7f, 1.4f);
        }
    }

    private void empower() {
        Player owner = player();
        int token = ++empowerToken;
        empowered = true;
        long duration = Math.max(1, params().ticks("empower_duration"));
        spawnShards(duration);
        String multiplier = AbilityInfo.formatNumber(params().num("empower_multiplier"));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">✦ 차원 습격</" + COLOR_TAG + "> <white>다음 공격 피해 ×"
                + multiplier + "</white> <gray>(" + AbilityInfo.formatNumber(params().num("empower_duration"))
                + "초)</gray>"));
        ctx().runLater(owner, duration, () -> {
            if (token != empowerToken || !empowered) {
                return;
            }
            empowered = false;
            clearShards();
            if (owner.isOnline() && ctx().isAlive(owner)) {
                owner.sendActionBar(Text.mm("<gray>차원 습격의 힘이 흩어졌습니다</gray>"));
                Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
                Vfx.cloud(ctx(), chest, Particle.REVERSE_PORTAL, 12, 0.3, 0.02, null, false);
                Fx.sound(chest, Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.6f);
                Fx.sound(chest, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.6f, 1.2f + Fx.jitter(0.1f));
            }
        });
    }

    @Override
    public void onTick(long tick) {
        if (tick % 2 == 0) {
            landing.update(player(), tick);
        }
        if (!empowered || shards.isEmpty() || tick % 2 != 0) {
            return;
        }
        // The shards circle the owner's waist; the display's teleport interpolation smooths the orbit.
        Player owner = player();
        for (int i = 0; i < shards.size(); i++) {
            ItemDisplay shard = shards.get(i);
            if (!shard.isValid() || !shard.getWorld().equals(owner.getWorld())) {
                continue;
            }
            Location at = orbitPoint(owner, i, tick);
            shard.teleport(at);
            if (tick % 6 == 0) {
                Vfx.dot(at, Particle.DUST, PALETTE.dust(0.6f), false);
            }
        }
        if (tick % 10 == 0) {
            Vfx.cloud(ctx(), owner.getLocation().add(0, owner.getHeight() * 0.5, 0), Particle.REVERSE_PORTAL, 2,
                    0.25, 0.01, null, false);
        }
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!empowered || event.isCancelled() || ctx().isAbilityDamage()) {
            return;
        }
        empowered = false;
        empowerToken++;
        clearShards();
        double multiplier = Math.max(1, params().num("empower_multiplier"));
        event.setDamage(event.getDamage() * multiplier);

        // Impact: a slanted rift tears open across the victim and snaps shut, with a flash and a burst of shards.
        Location at = flat(victim.getLocation().add(0, victim.getHeight() * 0.55, 0));
        Vector swing = at.toVector().subtract(player().getLocation().toVector());
        rift(at, swing, 0.7f, (float) victim.getHeight() * 1.4f, 35, 0, 1, 2, 5, false);
        Vfx.burst(ctx(), at, PALETTE, 1.0, Particle.REVERSE_PORTAL, false);
        Vfx.sphere(ctx(), at, 0.9, 22, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.9f), false);
        Vfx.cloud(ctx(), at, Particle.ENCHANTED_HIT, 14, 0.3, 0.3, null, false);
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.55f + Fx.jitter(0.05f));
        Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 0.7f + Fx.jitter(0.1f));
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">✦ 차원 일격!</" + COLOR_TAG + "> <white>×"
                + AbilityInfo.formatNumber(multiplier) + "</white>"));
    }

    // ------------------------------------------------------------------ teleport + visuals

    /** Teleports the owner with the rift visuals. False if something cancelled the teleport. */
    private boolean teleport(Player owner, Location from, Location to, boolean ultimate) {
        if (!owner.teleport(to, TeleportCause.PLUGIN)) {
            return false;
        }
        owner.setFallDistance(0);
        double height = owner.getHeight();
        Vector travel = to.toVector().subtract(from.toVector());
        Vector facing = travel.clone().setY(0).lengthSquared() > 1.0E-4 ? travel : to.getDirection();
        Location fromBody = flat(from.clone().add(0, height * 0.5, 0));
        Location toBody = flat(to.clone().add(0, height * 0.5, 0));
        float scale = ultimate ? 1.25f : 1.0f;

        // Departure: the space folds in on the old body and a rift closes behind it.
        rift(fromBody, facing, 0.9f * scale, (float) height * 1.15f * scale, 0, 0, 2, 2, 7, ultimate);
        implode(fromBody, 1.1 * scale, ultimate ? 22 : 14, ultimate);
        Vfx.cloud(ctx(), fromBody, Particle.REVERSE_PORTAL, ultimate ? 20 : 12, 0.3, 0.03, null, ultimate);
        Fx.sound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 1.15f + Fx.jitter(0.1f));
        Fx.sound(from, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 1.5f + Fx.jitter(0.15f));

        // Travel: a fading violet thread and motes racing from the old body to the new one.
        if (from.getWorld().equals(to.getWorld())) {
            Vfx.line(ctx(), fromBody, toBody, 1.4, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.8f), ultimate);
            int duration = Math.clamp(Math.round(travel.length() * 0.7), 4, 14);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int streaks = Vfx.n(ctx(), ultimate ? 16 : 10);
            for (int i = 0; i < streaks; i++) {
                double ox = (random.nextDouble() - 0.5) * 0.6;
                double oy = (random.nextDouble() - 0.5) * height * 0.9;
                double oz = (random.nextDouble() - 0.5) * 0.6;
                Vfx.trail(fromBody.clone().add(ox, oy, oz), toBody.clone().add(ox, oy, oz),
                        i % 3 == 0 ? PALETTE.core() : PALETTE.body(), duration, ultimate);
            }
        }

        // Arrival: the twin rift opens around the new body, with a flash and a ripple over the ground. The
        // ultimate's rift opens at the target's back, facing it, ringed by a portal circle.
        Vector arrival = ultimate ? to.getDirection() : facing;
        rift(toBody, arrival, 0.95f * scale, (float) height * 1.2f * scale, 0, 0, 1, ultimate ? 6 : 3,
                ultimate ? 10 : 7, ultimate);
        if (ultimate) {
            verticalRing(toBody, arrival, 1.25, 26, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), true);
            verticalRing(toBody, arrival, 1.55, 18, 0.2, Particle.DUST, PALETTE.dust(0.8f), true);
        }
        Vfx.burst(ctx(), toBody, PALETTE, 0.7 * scale, Particle.REVERSE_PORTAL, ultimate);
        Vfx.shockwave(ctx(), owner, flat(to.clone().add(0, 0.08, 0)), 0.3, 1.3 * scale, 5, Particle.DUST,
                PALETTE.dust(0.8f), ultimate);
        Fx.sound(to, Sound.ITEM_CHORUS_FRUIT_TELEPORT, 0.9f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(to, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.4f + Fx.jitter(0.2f));
        if (ultimate) {
            Fx.sound(to, Sound.BLOCK_PORTAL_TRIGGER, 0.35f, 1.8f + Fx.jitter(0.1f));
        }
        return true;
    }

    /** Motes drawn in from a sphere around {@code center} to its middle ({@link Particle#TRAIL}). */
    private void implode(Location center, double radius, int points, boolean force) {
        int count = Vfx.n(ctx(), points);
        double golden = Math.PI * (3 - Math.sqrt(5));
        double spin = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (int i = 0; i < count; i++) {
            double y = 1 - (i + 0.5) * 2.0 / count;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double angle = spin + i * golden;
            Location from = center.clone().add(Math.cos(angle) * r * radius, y * radius, Math.sin(angle) * r * radius);
            Vfx.trail(from, center, i % 2 == 0 ? PALETTE.core() : PALETTE.body(), 5, force);
        }
    }

    /** A ring standing upright around {@code center}, facing {@code facing} (horizontal part). */
    private <T> void verticalRing(Location center, Vector facing, double radius, int points, double phase,
                                  Particle particle, @Nullable T data, boolean force) {
        Vector forward = facing.clone().setY(0);
        if (forward.lengthSquared() < 1.0E-6) {
            forward = new Vector(0, 0, 1);
        }
        Vector side = forward.normalize().crossProduct(new Vector(0, 1, 0));
        int count = Vfx.n(ctx(), points);
        for (int i = 0; i < count; i++) {
            double angle = phase + i * (Math.PI * 2 / count);
            Location at = center.clone().add(side.clone().multiply(Math.cos(angle) * radius))
                    .add(0, Math.sin(angle) * radius, 0);
            Vfx.dot(at, particle, data, force);
        }
    }

    /**
     * A tear in space: a tall violet glass rhombus with a bright seam, upright and facing {@code facing}, tilted by
     * {@code rollDegrees}. After {@code delayTicks} it opens over {@code openTicks}, holds, then narrows to a seam and
     * disappears. Two displays; the ultimate's rifts get a violet outline that shows through walls.
     */
    private void rift(Location center, Vector facing, float width, float height, float rollDegrees, int delayTicks,
                      int openTicks, int holdTicks, int closeTicks, boolean outlined) {
        Location at = flat(center);
        Quaternionf face = new Quaternionf().rotateY((float) -yawOf(facing))
                .rotateZ((float) Math.toRadians(rollDegrees));
        Timing timing = new Timing(delayTicks, Math.max(1, openTicks), Math.max(0, holdTicks),
                Math.max(2, closeTicks));
        riftLayer(at, face, Material.PURPLE_STAINED_GLASS, width, height, 0.04f, timing,
                outlined ? PALETTE.body() : null);
        riftLayer(at, face, Material.WHITE_STAINED_GLASS, width * 0.28f, height * 0.9f, 0.07f, timing, null);
    }

    /** When a rift opens and closes, in ticks. */
    private record Timing(int delay, int open, int hold, int close) {
    }

    private void riftLayer(Location at, Quaternionf face, Material material, float width, float height, float depth,
                           Timing timing, @Nullable Color outline) {
        Player owner = player();
        float w = width / SQRT2;
        float h = height / SQRT2;
        long opened = 1L + timing.delay();
        long closing = opened + timing.open() + timing.hold();
        BlockDisplay layer = Displays.block(ctx(), owner, at, material.createBlockData(),
                rhombus(face, 0.001f, h * 0.55f, depth), closing + timing.close() + 1);
        if (outline != null) {
            Displays.outline(layer, outline);
        }
        ctx().runLater(owner, opened, () -> Displays.animate(layer, rhombus(face, w, h, depth), timing.open()));
        ctx().runLater(owner, closing,
                () -> Displays.animate(layer, rhombus(face, 0.001f, h * 1.15f, depth), timing.close()));
    }

    /** A rhombus of {@code width} by {@code height} (block units / √2) centered on the display, facing {@code face}. */
    private static Transformation rhombus(Quaternionf face, float width, float height, float depth) {
        Vector3f scale = new Vector3f(width, height, depth);
        Vector3f center = new Vector3f(0.5f).rotate(DIAMOND).mul(scale).rotate(face);
        return new Transformation(center.negate(), face, scale, DIAMOND);
    }

    // ------------------------------------------------------------------ empowered shards

    private void spawnShards(long duration) {
        clearShards();
        Player owner = player();
        long tick = ctx().currentTick();
        for (int i = 0; i < SHARD_COUNT; i++) {
            ItemDisplay shard = Displays.item(ctx(), owner, orbitPoint(owner, i, tick),
                    ItemStack.of(Material.AMETHYST_SHARD), Displays.centered(0, 0, 0, 0.01f, 0.01f, 0.01f, 0, 0, 0),
                    duration + 5);
            shard.setBillboard(Display.Billboard.CENTER);
            Displays.animateNextTick(ctx(), owner, shard, Displays.centered(0, 0, 0, 0.5f, 0.5f, 0.5f, 0, 0, 0), 4);
            shards.add(shard);
        }
    }

    private void clearShards() {
        for (ItemDisplay shard : shards) {
            if (shard.isValid()) {
                Displays.shrinkAway(ctx(), player(), shard, 4);
            }
        }
        shards.clear();
    }

    private static Location orbitPoint(Player owner, int index, long tick) {
        double angle = tick * SHARD_SPIN + index * (Math.PI * 2 / SHARD_COUNT);
        double bob = Math.sin(tick * 0.15 + index * Math.PI) * 0.15;
        Location at = owner.getLocation().add(Math.cos(angle) * SHARD_ORBIT, owner.getHeight() * 0.55 + bob,
                Math.sin(angle) * SHARD_ORBIT);
        return flat(at);
    }

    // ------------------------------------------------------------------ helpers

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
