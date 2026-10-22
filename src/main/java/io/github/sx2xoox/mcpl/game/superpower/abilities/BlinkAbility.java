package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
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
    /** Destinations hovering at most this high above ground are snapped onto it. */
    private static final double GROUND_SNAP = 1.1;

    // Ultimate search
    private static final double[] AROUND_ANGLES = {0, 40, -40, 80, -80, 120, -120, 180};
    private static final double[] AROUND_DISTANCES = {1.2, 2.0};
    private static final double[] AROUND_HEIGHTS = {0, 0.5, 1.0, -0.5, -1.0};

    private static final double STANDING_WIDTH = 0.6;
    private static final double STANDING_HEIGHT = 1.8;
    private static final double STANDING_EYE = 1.62;
    private static final int TRAIL_STREAKS = 12;
    private static final long LANDING_GRACE_TICKS = 100;

    /** Generation counter of the empowered strike, so a stale expiry timer never clears a newer one. */
    private int empowerToken;
    private boolean empowered;
    /** Until this tick the next fall damage is cancelled (-1: none); set when a teleport ends in mid-air. */
    private long landingGraceUntil = -1;

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
        if (landingGraceUntil >= 0 && isFall(event)) {
            // A blink that ended in mid-air should not be punished by the fall that follows.
            if (ctx().currentTick() <= landingGraceUntil) {
                event.setCancelled(true);
            }
            landingGraceUntil = -1;
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
        sound(body, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.7f + jitter(0.15f));

        double refund = params().num("dodge_refund");
        String suffix = "";
        if (refund > 0) {
            ctx().reduceCooldown(owner, refund);
            suffix = " <gray>점멸 -" + AbilityInfo.formatNumber(refund) + "초</gray>";
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

    private static boolean isFall(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.FALL) {
            return true;
        }
        Tag<DamageType> fall = DamageTypeTags.IS_FALL;
        return fall != null && fall.isTagged(event.getDamageSource().getDamageType());
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
        protectLanding(owner);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">✦ 점멸</" + COLOR_TAG + "> <gray>"
                + AbilityInfo.formatNumber(Math.round(from.distance(destination) * 10) / 10.0) + "블록</gray>"));
        return ActivationResult.success();
    }

    /**
     * The farthest safe standing spot along the view ray, before the first wall. Each sample is tried at eye level
     * first and then slightly higher (so looking down at the floor lands on top of it), snapped onto ground right
     * below, and must be reachable in a straight line from the owner's eyes.
     */
    private static @Nullable Location findBlinkSpot(Player owner, double maxDistance) {
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

        double scale = scaleOf(owner);
        double width = STANDING_WIDTH * scale;
        double height = STANDING_HEIGHT * scale;
        double eyeHeight = STANDING_EYE * scale;
        Location start = owner.getLocation();

        for (double d = reach; d >= MIN_BLINK - 1.0E-6; d -= SAMPLE_STEP) {
            Location point = eye.clone().add(direction.clone().multiply(d));
            for (double lift : LIFTS) {
                Location feet = point.clone().subtract(0, eyeHeight - lift, 0);
                snapToGround(world, feet, GROUND_SNAP);
                if (feet.distanceSquared(start) < MIN_BLINK * MIN_BLINK) {
                    continue;
                }
                if (!fits(world, feet, width, height) || !hasGroundBelow(world, feet)) {
                    continue;
                }
                if (!clearPath(world, eye, feet.clone().add(0, eyeHeight, 0))) {
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
            Location destination = spotBehind(owner, target);
            if (destination == null) {
                continue;
            }
            if (!teleport(owner, owner.getLocation(), destination)) {
                return ActivationResult.fail("순간이동이 막혔습니다");
            }
            protectLanding(owner);
            ripple(target);
            empower();
            return ActivationResult.success();
        }
        return ActivationResult.fail("적 주변에 순간이동할 공간이 없습니다");
    }

    /**
     * A safe spot next to {@code target}, preferring right behind its back, facing it. Every spot must have a clear
     * line to the target's eyes, so the owner never ends up on the other side of a wall.
     */
    private static @Nullable Location spotBehind(Player owner, LivingEntity target) {
        World world = target.getWorld();
        Location base = target.getLocation();
        Vector back = base.getDirection().setY(0);
        if (back.lengthSquared() < 1.0E-4) {
            back = new Vector(0, 0, 1);
        }
        back.normalize().multiply(-1);

        double scale = scaleOf(owner);
        double width = STANDING_WIDTH * scale;
        double height = STANDING_HEIGHT * scale;
        double eyeHeight = STANDING_EYE * scale;
        Location targetEye = target.getEyeLocation();
        double clearance = target.getWidth() / 2 + width / 2;

        for (double angle : AROUND_ANGLES) {
            Vector side = back.clone().rotateAroundY(Math.toRadians(angle));
            for (double distance : AROUND_DISTANCES) {
                for (double dy : AROUND_HEIGHTS) {
                    Location feet = base.clone().add(side.clone().multiply(distance + clearance)).add(0, dy, 0);
                    snapToGround(world, feet, GROUND_SNAP);
                    if (!fits(world, feet, width, height) || !hasGroundBelow(world, feet)
                            || !clearPath(world, targetEye, feet.clone().add(0, eyeHeight, 0))) {
                        continue;
                    }
                    return faceTowards(feet, target.getLocation().add(0, target.getHeight() * 0.6, 0), eyeHeight);
                }
            }
        }
        return null;
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
                sound(owner.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.6f);
            }
        });
    }

    @Override
    public void onTick(long tick) {
        if (landingGraceUntil >= 0 && tick % 2 == 0 && (tick > landingGraceUntil || isGrounded(player()))) {
            landingGraceUntil = -1;
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
        sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f + jitter(0.1f));
        sound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.55f + jitter(0.05f));
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
        sound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 1.15f + jitter(0.1f));

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
        sound(to, Sound.ITEM_CHORUS_FRUIT_TELEPORT, 0.9f, 1.3f + jitter(0.1f));
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /** After a teleport into mid-air, the landing that follows deals no fall damage. */
    private void protectLanding(Player owner) {
        if (!isGrounded(owner)) {
            landingGraceUntil = ctx().currentTick() + LANDING_GRACE_TICKS;
        }
    }

    /** True when a solid surface is right below the entity's feet (server-side, not the client's claim). */
    private static boolean isGrounded(LivingEntity entity) {
        BoundingBox box = entity.getBoundingBox();
        BoundingBox below = new BoundingBox(box.getMinX() + 0.05, box.getMinY() - 0.15, box.getMinZ() + 0.05,
                box.getMaxX() - 0.05, box.getMinY() - 0.01, box.getMaxZ() - 0.05);
        return entity.getWorld().hasCollisionsIn(below) || entity.isInWater();
    }

    /** True if a standing body of the given size fits at {@code feet}: no collisions and no harmful blocks. */
    private static boolean fits(World world, Location feet, double width, double height) {
        if (feet.getY() < world.getMinHeight() + 1 || feet.getY() + height > world.getMaxHeight()) {
            return false;
        }
        double half = width / 2 - 0.01;
        BoundingBox box = new BoundingBox(feet.getX() - half, feet.getY() + 0.01, feet.getZ() - half,
                feet.getX() + half, feet.getY() + height, feet.getZ() + half);
        if (world.hasCollisionsIn(box)) {
            return false;
        }
        int x = feet.getBlockX();
        int z = feet.getBlockZ();
        for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++) {
            if (isHazard(world.getBlockAt(x, y, z))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHazard(Block block) {
        Material type = block.getType();
        return type == Material.LAVA || type == Material.FIRE || type == Material.SOUL_FIRE
                || type == Material.POWDER_SNOW || type == Material.COBWEB;
    }

    /** False above the void: something (block or fluid) must exist somewhere below. */
    private static boolean hasGroundBelow(World world, Location feet) {
        double depth = feet.getY() - world.getMinHeight();
        return depth > 0 && world.rayTraceBlocks(feet, new Vector(0, -1, 0), depth, FluidCollisionMode.ALWAYS,
                true) != null;
    }

    /** Lowers {@code feet} onto the ground if it is at most {@code maxDrop} blocks below. */
    private static void snapToGround(World world, Location feet, double maxDrop) {
        RayTraceResult ground = world.rayTraceBlocks(feet, new Vector(0, -1, 0), maxDrop, FluidCollisionMode.NEVER,
                true);
        if (ground != null) {
            feet.setY(ground.getHitPosition().getY());
        }
    }

    private static boolean clearPath(World world, Location from, Location to) {
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length < 1.0E-3) {
            return true;
        }
        return world.rayTraceBlocks(from, delta.multiply(1 / length), length, FluidCollisionMode.NEVER, true) == null;
    }

    /** {@code feet} with yaw/pitch set so that eyes at {@code eyeHeight} look at {@code focus}. */
    private static Location faceTowards(Location feet, Location focus, double eyeHeight) {
        Vector look = focus.toVector().subtract(feet.toVector().add(new Vector(0, eyeHeight, 0)));
        if (look.lengthSquared() > 1.0E-6) {
            feet.setDirection(look);
        }
        return feet;
    }

    private static double scaleOf(LivingEntity entity) {
        AttributeInstance scale = entity.getAttribute(Attribute.SCALE);
        return scale == null ? 1.0 : Math.max(0.1, scale.getValue());
    }

    private static void sound(Location at, Sound sound, float volume, float pitch) {
        at.getWorld().playSound(at, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private static float jitter(float amount) {
        return (ThreadLocalRandom.current().nextFloat() * 2 - 1) * amount;
    }
}
