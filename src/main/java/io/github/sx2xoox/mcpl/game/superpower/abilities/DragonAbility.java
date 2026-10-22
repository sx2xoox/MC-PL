package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 드래곤 — a fire-proof, armored flier. Its scales shrug off fire and blunt arrows, it breathes a cone of violet
 * dragon fire, and as the ultimate it spreads huge wings, takes to the sky and drops breath bombs on whoever is
 * below.
 *
 * <p>Look: magenta and deep purple with a pale pink core. The breath is a roaring stream of dragon-breath particles
 * and violet embers; the bombs are spinning purple crystal orbs whose landing spot glows beneath the dragon, bursting
 * into a ring of violet fire that smoulders for a moment. The wings are two great membranes of stained glass on
 * dark bone, beating slowly with the ender dragon's flap.
 */
public final class DragonAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("dragon", "드래곤", Material.DRAGON_HEAD)
            .color(TextColor.color(0xC13BE0))
            .difficulty(2)
            .passive("용의 비늘",
                    "불과 용암에 피해를 입지 않고, 화살 같은 투사체에 받는 피해가 {scale_reduction}% 줄어듭니다.")
            .active("드래곤 브레스",
                    "{breath_duration}초간 바라보는 방향 {breath_range}블록 앞까지 원뿔 모양으로 보랏빛 브레스를 "
                            + "내뿜어, 그 안의 적에게 {breath_interval}초마다 {breath_damage} 피해를 줍니다. 벽 너머로는 "
                            + "닿지 않습니다.",
                    10)
            .ultimate("용의 비상",
                    "거대한 날개를 펼쳐 {flight_duration}초간 날아오릅니다. 나는 동안 {bomb_interval}초마다 발밑으로 "
                            + "브레스 폭탄을 떨어뜨려 반경 {bomb_radius}블록의 적에게 {bomb_damage} 피해를 주고, 떨어진 "
                            + "자리에 {pool_duration}초간 용의 불꽃이 남아 {pool_interval}초마다 {pool_damage} 피해를 "
                            + "줍니다. 날개가 접히면 천천히 내려옵니다.")
            // Flight must be taken away again by this instance (timer + onRevoke), so it cannot be borrowed.
            .ultimateNotStandalone()
            .param("scale_reduction", 25)
            .param("breath_duration", 2)
            .param("breath_range", 7)
            .param("breath_angle", 30)
            .param("breath_interval", 0.25)
            .param("breath_damage", 0.75)
            .param("flight_duration", 6)
            .param("take_off_height", 4)
            .param("fly_speed", 70)
            .param("bomb_interval", 1)
            .param("bomb_radius", 3)
            .param("bomb_damage", 3)
            .param("pool_duration", 1.5)
            .param("pool_interval", 0.5)
            .param("pool_damage", 0.5)
            .build();

    private static final String VIOLET = "#C13BE0";
    private static final double TAU = Math.PI * 2;

    // Passive
    private static final long SCALE_FX_COOLDOWN = 10;

    // Breath
    /** Distance in front of the eyes where the breath leaves the mouth. */
    private static final double MOUTH_FORWARD = 0.8;
    private static final double BREATH_PARTICLE_SPEED = 0.55;

    // Flight
    private static final double DEFAULT_FLY_SPEED = 0.1;
    private static final double MAX_VELOCITY = 2.5;
    private static final int SLOW_FALL_REFRESH_TICKS = 40;
    private static final long MAX_DESCENT_TICKS = 30 * 20;
    private static final long FALL_GRACE_TICKS = 10;
    /** The ground marker under the dragon brightens this many ticks before a bomb drops. */
    private static final long BOMB_WARNING_TICKS = 8;
    private static final double BOMB_START_SPEED = 0.4;
    private static final double BOMB_GRAVITY = 0.1;
    private static final double BOMB_MAX_SPEED = 1.8;
    private static final double BOMB_MAX_FALL = 40;
    /** Height (above the pool's ground) up to which the smouldering pool burns. */
    private static final double POOL_HEIGHT = 1.5;

    // ------------------------------------------------------------------ look: pale pink, magenta, deep purple
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFD6FF, 0xC13BE0, 0x3D0A5C);
    private static final Particle.DustOptions EMBER = PALETTE.dust(1.1f);
    private static final Particle.DustOptions DEEP = new Particle.DustOptions(PALETTE.edge(), 1.3f);
    private static final Particle.DustOptions SCALE_SPARK = new Particle.DustOptions(Color.fromRGB(0xE9A6FF), 0.7f);
    private static final BlockData ORB_CORE = Material.CRYING_OBSIDIAN.createBlockData();
    private static final BlockData ORB_SHELL = Material.MAGENTA_STAINED_GLASS.createBlockData();
    private static final BlockData POOL_GLASS = Material.PURPLE_STAINED_GLASS.createBlockData();
    private static final BlockData UPPER_MEMBRANE = Material.MAGENTA_STAINED_GLASS.createBlockData();
    private static final BlockData LOWER_MEMBRANE = Material.PURPLE_STAINED_GLASS.createBlockData();
    private static final BlockData BONE = Material.BLACK_CONCRETE.createBlockData();
    private static final int MAX_DISPLAYS = 24;

    // Wings (in the owner's body space, scaled by the owner's size times WING_SIZE)
    private static final float WING_ROOT_HEIGHT = 1.3f;
    private static final float WING_BACK = 0.24f;
    private static final float WING_SIZE = 1.25f;
    private static final float UPPER_LENGTH = 1.5f;
    private static final float UPPER_HEIGHT = 0.9f;
    private static final float LOWER_LENGTH = 1.0f;
    private static final float LOWER_HEIGHT = 0.7f;
    private static final float UPPER_ROLL = -22f;
    private static final float LOWER_ROLL = -95f;
    private static final float MEMBRANE_THICKNESS = 0.02f;
    private static final float BONE_THICKNESS = 0.07f;
    private static final double SWEEP_REST = 40;
    private static final double SWEEP_AMPLITUDE = 28;
    private static final double FLAP_SPEED = 0.32;
    private static final int FLAP_STEP_TICKS = 2;
    private static final int WING_OPEN_TICKS = 6;
    private static final int WING_FOLD_TICKS = 8;

    private final LandingGuard landing = new LandingGuard();
    private long lastScaleFx = Long.MIN_VALUE;
    private boolean breathing;
    private boolean flying;
    private boolean descending;
    private boolean savedAllowFlight;
    private float savedFlySpeed = (float) DEFAULT_FLY_SPEED;
    private long descentEndTick;
    private long fallGraceUntil = -1;
    private int liveDisplays;
    private @Nullable Wings wings;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        breathing = false;
        if (wings != null) {
            wings.remove();
            wings = null;
        }
        Player owner = player();
        if (flying) {
            restoreFlight(owner);
        }
        if ((flying || descending) && owner.isOnline()) {
            removeOwnSlowFalling(owner);
        }
        flying = false;
        descending = false;
        landing.disarm();
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        landing.update(owner, tick);
        if (descending) {
            tickDescent(owner, tick);
        }
    }

    // ------------------------------------------------------------------ passive: dragon scales

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (Combat.isFall(event) && (flying || descending || ctx().currentTick() <= fallGraceUntil
                || landing.absorb(event, ctx().currentTick()))) {
            event.setCancelled(true);
            return;
        }
        if (isFireDamage(event)) {
            event.setCancelled(true);
            if (event.getCause() == DamageCause.FIRE_TICK) {
                player().setFireTicks(0);
            }
            scaleFx(null);
            return;
        }
        double reduction = Math.clamp(params().num("scale_reduction") / 100.0, 0.0, 1.0);
        if (reduction > 0 && isProjectile(event)) {
            event.setDamage(event.getDamage() * (1 - reduction));
            scaleFx(event);
        }
    }

    private static boolean isFireDamage(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.FIRE || cause == DamageCause.FIRE_TICK || cause == DamageCause.LAVA) {
            return true;
        }
        // Covers magma blocks, campfires and fireballs, which report other causes.
        Tag<DamageType> fire = DamageTypeTags.IS_FIRE;
        return fire != null && fire.isTagged(event.getDamageSource().getDamageType());
    }

    private static boolean isProjectile(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.PROJECTILE) {
            return true;
        }
        Tag<DamageType> projectile = DamageTypeTags.IS_PROJECTILE;
        return projectile != null && projectile.isTagged(event.getDamageSource().getDamageType());
    }

    /** The scales flare: violet sparks where the hit landed (or around the body for fire) and a hard clink. */
    private void scaleFx(@Nullable EntityDamageEvent hit) {
        long now = ctx().currentTick();
        if (now - lastScaleFx < SCALE_FX_COOLDOWN) {
            return;
        }
        lastScaleFx = now;
        Player owner = player();
        Location body = Fx.body(owner);
        Location at = body;
        if (hit != null && hit.getDamageSource().getDirectEntity() != null
                && hit.getDamageSource().getDirectEntity().getWorld().equals(owner.getWorld())) {
            Vector toward = hit.getDamageSource().getDirectEntity().getLocation().toVector()
                    .subtract(body.toVector()).setY(0);
            if (toward.lengthSquared() > 1.0E-4) {
                at = body.clone().add(toward.normalize().multiply(owner.getWidth() * 0.6));
            }
        }
        Vfx.cloud(ctx(), at, Particle.DUST, 6, 0.2, 0, SCALE_SPARK, false);
        Vfx.cloud(ctx(), at, Particle.ELECTRIC_SPARK, 3, 0.15, 0.05, null, false);
        if (hit == null) {
            Vfx.ring(ctx(), owner.getLocation().add(0, 0.2, 0), 0.6, 8, now * 0.3, Particle.DUST, EMBER, false);
        }
        Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, 0.7f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ active: dragon breath

    @Override
    public ActivationResult onActive() {
        if (breathing) {
            return ActivationResult.fail("이미 브레스를 내뿜고 있습니다");
        }
        Player owner = player();
        long duration = Math.max(1, params().ticks("breath_duration"));
        long interval = Math.max(1, params().ticks("breath_interval"));
        breathing = true;
        Location eye = owner.getEyeLocation();
        Fx.sound(eye, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.6f);
        Loop.repeat(ctx(), owner, 0, 1, duration, tick -> {
            breathe(owner, tick, tick % interval == 0);
            return true;
        }, () -> {
            breathing = false;
            afterglow(owner);
        });
        return ActivationResult.success();
    }

    /** One tick of breath: the cone of fire in front of the owner, and its damage every interval. */
    private void breathe(Player owner, long tick, boolean scorch) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location mouth = eye.clone().add(direction.clone().multiply(MOUTH_FORWARD)).subtract(0, 0.15, 0);
        double range = Math.max(1, params().num("breath_range"));
        double half = Math.toRadians(Math.clamp(params().num("breath_angle"), 1, 80));
        coneFx(mouth, direction, range, half, tick);
        if (tick % 5 == 0) {
            Fx.sound(mouth, Sound.ENTITY_BLAZE_SHOOT, 0.5f, 0.5f + Fx.jitter(0.08f));
            Fx.sound(mouth, Sound.BLOCK_FIRE_AMBIENT, 1.0f, 0.6f + Fx.jitter(0.1f));
        }
        if (!scorch) {
            return;
        }
        double damage = params().num("breath_damage");
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, range + 0.5)) {
            Location body = Fx.body(enemy);
            Vector to = body.toVector().subtract(eye.toVector());
            double distance = to.length();
            if (distance > range + enemy.getWidth() * 0.5 || !inCone(direction, to, half, enemy, distance)
                    || !Spots.clearPath(eye, body)) {
                continue;
            }
            Combat.hit(ctx(), owner, enemy, damage);
            Vfx.cloud(ctx(), body, Particle.DUST, 4, 0.25, 0, EMBER, false);
            Vfx.dot(body, Particle.DRAGON_BREATH, 1.0f, false);
        }
    }

    /**
     * True if the enemy's body lies inside the cone: the angle to its chest is within {@code half}, widened by the
     * angle its own half-width covers at that distance, so an enemy at the edge still gets singed.
     */
    private static boolean inCone(Vector direction, Vector to, double half, LivingEntity enemy, double distance) {
        if (distance < 0.6) {
            return true;
        }
        double angle = direction.angle(to);
        double slack = Math.atan2(enemy.getWidth() * 0.5 + 0.2, distance);
        return angle <= half + slack;
    }

    /** The breath: dragon fire streaming out along the cone, violet embers inside it and a bright throat. */
    private void coneFx(Location mouth, Vector direction, double range, double half, long tick) {
        World world = mouth.getWorld();
        Vector side = direction.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0E-6) {
            side = new Vector(1, 0, 0);
        }
        side.normalize();
        Vector up = side.clone().crossProduct(direction).normalize();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int streams = Vfx.n(ctx(), 7);
        for (int i = 0; i < streams; i++) {
            Vector ray = coneRay(direction, side, up, half * random.nextDouble(0.2, 1.0), random.nextDouble(TAU));
            world.spawnParticle(Particle.DRAGON_BREATH, mouth.getX(), mouth.getY(), mouth.getZ(), 0, ray.getX(),
                    ray.getY(), ray.getZ(), BREATH_PARTICLE_SPEED * range / 7.0 * random.nextDouble(0.7, 1.1), 1.0f);
        }
        int embers = Vfx.n(ctx(), 6);
        for (int i = 0; i < embers; i++) {
            double t = Math.sqrt(random.nextDouble());
            Vector ray = coneRay(direction, side, up, half * random.nextDouble(), random.nextDouble(TAU));
            Location at = mouth.clone().add(ray.multiply(range * t));
            Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, t < 0.4 ? PALETTE.glow(1.0f) : PALETTE.fade(1.3f), false);
        }
        Vfx.dot(mouth, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.8f), false);
        if (tick % 3 == 0) {
            Location tip = mouth.clone().add(direction.clone().multiply(range));
            Vfx.cloud(ctx(), tip, Particle.REVERSE_PORTAL, 4, 0.5, 0.02, null, false);
            Vfx.dot(Vfx.scatter(tip, 0.6), Particle.SMOKE, null, false);
        }
    }

    /** A unit vector {@code angle} radians off {@code direction}, turned {@code roll} radians around it. */
    private static Vector coneRay(Vector direction, Vector side, Vector up, double angle, double roll) {
        double sin = Math.sin(angle);
        return direction.clone().multiply(Math.cos(angle))
                .add(side.clone().multiply(sin * Math.cos(roll)))
                .add(up.clone().multiply(sin * Math.sin(roll)));
    }

    /** When the breath ends, violet smoke lingers in front of the dragon for a moment. */
    private void afterglow(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double range = Math.max(1, params().num("breath_range"));
        Location mid = eye.clone().add(direction.clone().multiply(range * 0.55));
        Loop.repeat(ctx(), owner, 0, 3, 5, run -> {
            Vfx.cloud(ctx(), mid, Particle.DUST_COLOR_TRANSITION, 4, range * 0.15, 0, PALETTE.fade(1.4f), false);
            Vfx.dot(Vfx.scatter(mid, range * 0.2), Particle.SMOKE, null, false);
            return true;
        });
        Fx.sound(eye, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.2f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ ultimate: dragon's ascent

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (flying) {
            return ActivationResult.fail("이미 날고 있습니다");
        }
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 날 수 없습니다");
        }
        GameMode mode = owner.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return ActivationResult.fail("지금은 날개를 펼칠 수 없습니다");
        }
        savedAllowFlight = owner.getAllowFlight();
        savedFlySpeed = owner.getFlySpeed();
        flying = true;
        descending = false;
        removeOwnSlowFalling(owner);

        float speed = (float) Math.clamp(DEFAULT_FLY_SPEED * params().num("fly_speed") / 100.0, 0.02, 0.2);
        owner.setAllowFlight(true);
        owner.setFlySpeed(speed);
        double lift = Motion.launchSpeed(Math.max(0, params().num("take_off_height")));
        Vector velocity = owner.getVelocity();
        owner.setVelocity(Motion.clamp(new Vector(velocity.getX(), Math.max(velocity.getY(), lift),
                velocity.getZ()), MAX_VELOCITY));
        owner.setFlying(true);
        owner.setFallDistance(0);

        takeOffFx(owner);
        if (wings != null) {
            wings.remove();
        }
        long duration = Math.max(1, params().ticks("flight_duration"));
        wings = reserveDisplays(6, duration + WING_FOLD_TICKS + 20) ? new Wings(owner, duration) : null;
        long interval = Math.max(1, params().ticks("bomb_interval"));
        long[] nextBomb = {interval};
        Loop.repeat(ctx(), owner, 1, 1, duration, tick -> {
            if (!flying || !owner.getAllowFlight()) {
                return false;
            }
            long left = duration - tick;
            tickFlight(owner, tick, left);
            long untilBomb = nextBomb[0] - tick;
            marker(owner, tick, untilBomb <= BOMB_WARNING_TICKS);
            if (untilBomb <= 0) {
                nextBomb[0] += interval;
                dropBomb(owner);
            }
            return true;
        }, () -> endFlight(owner));
        return ActivationResult.success();
    }

    /** Take-off: a downdraft of violet dust, a roar and the first beat of the wings. */
    private void takeOffFx(Player owner) {
        Location feet = owner.getLocation();
        Location body = Fx.body(owner);
        World world = feet.getWorld();
        world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, PALETTE.core(), true);
        Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.4, 3.5, 7, Particle.DUST_COLOR_TRANSITION,
                PALETTE.fade(1.4f), true);
        Vfx.ring(ctx(), feet.clone().add(0, 0.2, 0), 1.2, 14, 0, Particle.CLOUD, null, true);
        world.spawnParticle(Particle.DRAGON_BREATH, feet.clone().add(0, 0.3, 0), Vfx.n(ctx(), 30), 0.8, 0.1, 0.8,
                0.03, 1.0f, true);
        BlockData ground = feet.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        if (ground.getMaterial().isSolid()) {
            Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 1.0, 8, 0, Particle.DUST_PILLAR, ground, true);
        }
        Fx.sound(feet, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.4f, 1.0f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.5f, 0.8f);
        Fx.sound(feet, Sound.ITEM_ARMOR_EQUIP_ELYTRA, 1.0f, 0.6f);
    }

    private void tickFlight(Player owner, long tick, long left) {
        owner.setFallDistance(0);
        Wings current = wings;
        if (current != null && !current.update(owner, tick, left <= 40)) {
            wings = null;
        }
        if (tick % 2 == 0) {
            Location at = owner.getLocation().add(0, owner.getHeight() * 0.3, 0);
            Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.2f), false);
            if (tick % 6 == 0) {
                Vfx.dot(at, Particle.DRAGON_BREATH, 1.0f, false);
            }
        }
        if (tick % 5 == 0) {
            owner.sendActionBar(Text.mm("<" + VIOLET + ">🐉 용의 비상</" + VIOLET + "> <gray>" + Fx.seconds(left)
                    + "초</gray>"));
        }
        if (left <= 60 && left % 20 == 0) {
            owner.playSound(owner, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.8f,
                    0.8f + 0.2f * (3 - left / 20f));
        }
    }

    /**
     * Where the next bomb will land: a violet ring on the ground straight below the dragon, faint while it waits and
     * bright (visible from afar) just before the drop.
     */
    private void marker(Player owner, long tick, boolean imminent) {
        if (tick % 2 != 0) {
            return;
        }
        Location ground = groundBelow(owner.getLocation(), BOMB_MAX_FALL);
        if (ground == null) {
            return;
        }
        double radius = Math.max(0.5, params().num("bomb_radius"));
        Location at = ground.clone().add(0, 0.12, 0);
        if (imminent) {
            Vfx.ring(ctx(), at, radius, (int) Math.max(14, radius * 7), tick * 0.25, Particle.DUST, EMBER, true);
            Vfx.ring(ctx(), at, radius * 0.45, (int) Math.max(8, radius * 3), -tick * 0.25,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), true);
        } else if (tick % 4 == 0) {
            Vfx.ring(ctx(), at, radius, (int) Math.max(10, radius * 3), tick * 0.1, Particle.DUST, DEEP, false);
        }
    }

    /** A breath bomb falls from the dragon's belly and accelerates to the ground. */
    private void dropBomb(Player owner) {
        Location start = owner.getLocation().add(0, -0.2, 0);
        World world = start.getWorld();
        Location position = flat(start);
        long lifetime = 40;
        boolean shown = reserveDisplays(2, lifetime);
        BlockDisplay core = shown ? Displays.block(ctx(), owner, position, ORB_CORE, Displays.scaled(0.38f),
                lifetime) : null;
        BlockDisplay shell = shown ? Displays.block(ctx(), owner, position, ORB_SHELL, Displays.scaled(0.6f),
                lifetime) : null;
        if (core != null) {
            core.setTeleportDuration(1);
        }
        if (shell != null) {
            shell.setTeleportDuration(1);
        }
        Fx.sound(start, Sound.ENTITY_ENDER_DRAGON_SHOOT, 0.9f, 1.3f + Fx.jitter(0.08f));
        Fx.sound(start, Sound.ENTITY_GHAST_SHOOT, 0.4f, 0.6f);
        double[] speed = {BOMB_START_SPEED};
        double[] fallen = {0};
        Loop.repeat(ctx(), owner, 0, 1, (long) (BOMB_MAX_FALL / BOMB_START_SPEED) + 2, tick -> {
            double step = Math.min(speed[0], BOMB_MAX_FALL - fallen[0]);
            RayTraceResult floor = world.rayTraceBlocks(position, new Vector(0, -1, 0), step + 0.05,
                    FluidCollisionMode.ALWAYS, true);
            if (floor != null) {
                Location impact = floor.getHitPosition().toLocation(world);
                removeAll(core, shell);
                explode(owner, impact);
                return false;
            }
            Location from = position.clone();
            position.subtract(0, step, 0);
            fallen[0] += step;
            speed[0] = Math.min(BOMB_MAX_SPEED, speed[0] + BOMB_GRAVITY);
            float spin = tick * 37f;
            if (core != null && core.isValid()) {
                core.teleport(position);
                Displays.animate(core, Displays.transform(0, 0, 0, 0.38f, 0.38f, 0.38f, spin, spin * 0.7f, 0), 1);
            }
            if (shell != null && shell.isValid()) {
                shell.teleport(position);
                Displays.animate(shell, Displays.transform(0, 0, 0, 0.6f, 0.6f, 0.6f, -spin, 0, spin * 0.5f), 1);
            }
            Vfx.line(ctx(), from, position, 2, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), true);
            Vfx.dot(from, Particle.DRAGON_BREATH, 1.0f, true);
            if (fallen[0] >= BOMB_MAX_FALL - 1.0E-3) {
                // Fell into the void: it fizzles out.
                removeAll(core, shell);
                Vfx.cloud(ctx(), position, Particle.REVERSE_PORTAL, 10, 0.3, 0.05, null, false);
                return false;
            }
            return true;
        }, () -> removeAll(core, shell));
    }

    /** The bomb bursts: damage around the impact, a violet blast ring and a smouldering pool of dragon fire. */
    private void explode(Player owner, Location impact) {
        double radius = Math.max(0.5, params().num("bomb_radius"));
        double damage = params().num("bomb_damage");
        Location center = impact.clone().add(0, 0.5, 0);
        int struck = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            Combat.hit(ctx(), owner, enemy, damage);
            struck++;
            Vfx.cloud(ctx(), Fx.body(enemy), Particle.DUST, 6, 0.3, 0, EMBER, true);
        }
        World world = impact.getWorld();
        Location ground = impact.clone().add(0, 0.15, 0);
        world.spawnParticle(Particle.FLASH, ground.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0, PALETTE.core(), true);
        world.spawnParticle(Particle.DRAGON_BREATH, ground.clone().add(0, 0.4, 0), Vfx.n(ctx(), 45), 0.4, 0.3, 0.4,
                0.12, 1.0f, true);
        Vfx.burst(ctx(), ground.clone().add(0, 0.6, 0), PALETTE, 1.0, Particle.REVERSE_PORTAL, true);
        Vfx.shockwave(ctx(), owner, ground, 0.4, radius, 6, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.5f), true);
        Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.1, 0), 0.2, radius * 0.8, 5, Particle.DUST, EMBER, true);
        Fx.sound(impact, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.3f, 0.9f + Fx.jitter(0.08f));
        Fx.sound(impact, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.4f + Fx.jitter(0.08f));
        Fx.sound(impact, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.8f, 0.6f + Fx.jitter(0.05f));
        if (struck > 0) {
            owner.sendActionBar(Text.mm("<" + VIOLET + ">브레스 폭탄!</" + VIOLET + "> <gray>" + struck
                    + "명 적중</gray>"));
        }
        pool(owner, impact, radius);
    }

    /** Dragon fire smoulders where the bomb fell, burning enemies who stay in it. */
    private void pool(Player owner, Location impact, double radius) {
        long duration = params().ticks("pool_duration");
        if (duration <= 0) {
            return;
        }
        long interval = Math.max(1, params().ticks("pool_interval"));
        double damage = params().num("pool_damage");
        double poolRadius = radius * 0.8;
        Location ground = impact.clone().add(0, 0.05, 0);
        BlockDisplay glow = reserveDisplays(1, duration + 8)
                ? Displays.block(ctx(), owner, flat(ground), POOL_GLASS, Displays.transform(0, 0.02f, 0, 0.3f,
                0.04f, 0.3f, 45, 0, 0), duration + 8)
                : null;
        if (glow != null) {
            float size = (float) (poolRadius * 1.4);
            Displays.animateNextTick(ctx(), owner, glow, Displays.transform(0, 0.02f, 0, size, 0.04f, size, 45, 0, 0),
                    4);
        }
        Loop.repeat(ctx(), owner, 1, 1, duration, tick -> {
            if (tick % 2 == 0) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int flames = Vfx.n(ctx(), 4);
                for (int i = 0; i < flames; i++) {
                    Location at = Vfx.scatter(ground, poolRadius).add(0, 0.1, 0);
                    at.getWorld().spawnParticle(Particle.DRAGON_BREATH, at.getX(), at.getY(), at.getZ(), 0, 0, 1, 0,
                            random.nextDouble(0.02, 0.06), 1.0f);
                }
                Vfx.ring(ctx(), ground.clone().add(0, 0.1, 0), poolRadius, (int) Math.max(10, poolRadius * 5),
                        tick * 0.15, Particle.DUST, DEEP, false);
            }
            if (tick > 0 && tick % interval == 0) {
                for (LivingEntity enemy : ctx().enemiesNear(owner, ground, poolRadius + POOL_HEIGHT)) {
                    double dy = enemy.getLocation().getY() - ground.getY();
                    double dx = enemy.getLocation().getX() - ground.getX();
                    double dz = enemy.getLocation().getZ() - ground.getZ();
                    if (dy < -0.5 || dy > POOL_HEIGHT || dx * dx + dz * dz > poolRadius * poolRadius) {
                        continue;
                    }
                    Combat.hit(ctx(), owner, enemy, damage);
                    Vfx.dot(enemy.getLocation().add(0, 0.3, 0), Particle.DRAGON_BREATH, 1.0f, false);
                }
            }
            return true;
        }, () -> {
            if (glow != null && glow.isValid()) {
                Displays.shrinkAway(ctx(), owner, glow, 6);
            }
            Vfx.cloud(ctx(), ground.clone().add(0, 0.3, 0), Particle.SMOKE, 6, poolRadius * 0.4, 0.01, null, false);
        });
    }

    private void endFlight(Player owner) {
        if (!flying) {
            return;
        }
        flying = false;
        restoreFlight(owner);
        long now = ctx().currentTick();
        fallGraceUntil = now + FALL_GRACE_TICKS;
        if (!Motion.isGrounded(owner) && !owner.isInWater()) {
            descending = true;
            descentEndTick = now + MAX_DESCENT_TICKS;
            owner.addPotionEffect(slowFalling());
            landing.arm(now, MAX_DESCENT_TICKS);
        }
        if (wings != null) {
            wings.fold();
            wings = null;
        }
        Location body = Fx.body(owner);
        Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 12, 0.5, 0, PALETTE.fade(1.2f), false);
        Fx.sound(body, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.3f);
        Fx.sound(body, Sound.ITEM_ARMOR_EQUIP_ELYTRA, 0.7f, 1.2f);
        owner.sendActionBar(Text.mm("<" + VIOLET + ">날개를 접었습니다</" + VIOLET + ">"
                + (descending ? " <gray>천천히 내려갑니다</gray>" : "")));
    }

    private void tickDescent(Player owner, long tick) {
        owner.setFallDistance(0);
        if (tick > descentEndTick || owner.isInWater() || Motion.isGrounded(owner)) {
            descending = false;
            fallGraceUntil = tick + FALL_GRACE_TICKS;
            removeOwnSlowFalling(owner);
            return;
        }
        PotionEffect current = owner.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (current == null || current.getDuration() < SLOW_FALL_REFRESH_TICKS / 2) {
            owner.addPotionEffect(slowFalling());
        }
        if (tick % 4 == 0) {
            Location feet = owner.getLocation();
            Vfx.dot(feet.clone().add(0, 0.3, 0), Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.0f), false);
            if (tick % 8 == 0) {
                Vfx.dot(feet.clone().add(0, 0.5, 0), Particle.DRAGON_BREATH, 1.0f, false);
            }
        }
    }

    /** Gives the flight back to how it was before the ultimate (only in modes where the plugin controls it). */
    private void restoreFlight(Player owner) {
        if (!owner.isOnline()) {
            return;
        }
        GameMode mode = owner.getGameMode();
        if (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) {
            owner.setFlying(false);
            owner.setAllowFlight(savedAllowFlight);
        }
        owner.setFlySpeed(savedFlySpeed);
    }

    private static PotionEffect slowFalling() {
        return new PotionEffect(PotionEffectType.SLOW_FALLING, SLOW_FALL_REFRESH_TICKS, 0, false, false, true);
    }

    /** Removes slow falling only if it is the (particle-less) one this ability applied. */
    private static void removeOwnSlowFalling(Player owner) {
        PotionEffect current = owner.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (current != null && !current.hasParticles() && current.getDuration() <= SLOW_FALL_REFRESH_TICKS) {
            owner.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
    }

    // ------------------------------------------------------------------ wings

    /**
     * A pair of dragon wings on the owner's back: per side a large magenta upper membrane, a smaller purple lower
     * membrane and a dark bone along the upper membrane's leading edge (thin block displays), pivoting at the
     * shoulders. They follow the owner every tick and beat slowly by interpolating their sweep angle.
     */
    private final class Wings {
        private final BlockDisplay[] parts = new BlockDisplay[6];
        private final float size;
        private final float bodyScale;
        private double phase;
        private int age;
        private boolean wasDown;

        Wings(Player owner, long duration) {
            this.bodyScale = (float) Body.scaleOf(owner);
            this.size = bodyScale * WING_SIZE;
            long lifetime = duration + WING_FOLD_TICKS + 20;
            Location at = anchor(owner);
            Transformation folded = new Transformation(new Vector3f(0, WING_ROOT_HEIGHT * bodyScale, 0),
                    new Quaternionf(), new Vector3f(0.001f), new Quaternionf());
            int index = 0;
            for (boolean left : new boolean[] {true, false}) {
                for (int part = 0; part < 3; part++) {
                    BlockData data = part == 0 ? UPPER_MEMBRANE : part == 1 ? LOWER_MEMBRANE : BONE;
                    BlockDisplay display = Displays.block(ctx(), owner, at, data, folded, lifetime);
                    if (part == 2) {
                        display.setBrightness(null);
                    }
                    Displays.animateNextTick(ctx(), owner, display, part(left, part, SWEEP_REST), WING_OPEN_TICKS);
                    parts[index++] = display;
                }
            }
        }

        /** Follows the owner and beats; false (after removing itself) if the wings are gone. */
        boolean update(Player owner, long tick, boolean hurried) {
            for (BlockDisplay display : parts) {
                if (!display.isValid() || display.getWorld() != owner.getWorld()) {
                    remove();
                    return false;
                }
            }
            Location at = anchor(owner);
            for (BlockDisplay display : parts) {
                display.teleport(at);
            }
            phase += hurried ? FLAP_SPEED * 1.6 : FLAP_SPEED;
            if (++age > WING_OPEN_TICKS && tick % FLAP_STEP_TICKS == 0) {
                double sweep = SWEEP_REST + SWEEP_AMPLITUDE * Math.sin(phase);
                int index = 0;
                for (boolean left : new boolean[] {true, false}) {
                    for (int part = 0; part < 3; part++) {
                        Displays.animate(parts[index++], part(left, part, sweep), FLAP_STEP_TICKS);
                    }
                }
                boolean down = Math.sin(phase) < 0;
                if (down && !wasDown) {
                    Fx.sound(owner.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.7f, 1.1f + Fx.jitter(0.1f));
                    tipEmbers(owner, at, sweep);
                }
                wasDown = down;
            }
            return true;
        }

        /** Violet embers shed from the wing tips on each down-stroke. */
        private void tipEmbers(Player owner, Location at, double sweep) {
            double yaw = Math.toRadians(at.getYaw());
            for (boolean left : new boolean[] {true, false}) {
                Transformation t = part(left, 0, sweep);
                Vector3f tip = t.getLeftRotation().transform(new Vector3f(t.getScale().x(), t.getScale().y() * 0.5f,
                        0)).add(t.getTranslation());
                double x = tip.x() * Math.cos(yaw) - tip.z() * Math.sin(yaw);
                double z = tip.x() * Math.sin(yaw) + tip.z() * Math.cos(yaw);
                Location point = at.clone().add(x, tip.y(), z);
                Vfx.cloud(ctx(), point, Particle.DUST, 3, 0.1, 0, EMBER, false);
                Vfx.dot(point, Particle.DRAGON_BREATH, 1.0f, false);
            }
            Vfx.dot(owner.getLocation().add(0, 0.2, 0), Particle.CLOUD, null, false);
        }

        void fold() {
            for (BlockDisplay display : parts) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), player(), display, WING_FOLD_TICKS);
                }
            }
        }

        void remove() {
            for (BlockDisplay display : parts) {
                if (display.isValid()) {
                    display.remove();
                }
            }
        }

        private Location anchor(Player owner) {
            Location at = owner.getLocation();
            at.setYaw(owner.getBodyYaw());
            at.setPitch(0);
            return at;
        }

        /**
         * One wing part in body space (local +Z = facing, +X = the owner's left): a thin block with its corner at
         * the wing root, rolled outwards and swept back by {@code sweepDegrees}. Part 0 is the upper membrane, 1 the
         * lower membrane and 2 the bone along the upper membrane's top edge.
         */
        private Transformation part(boolean left, int part, double sweepDegrees) {
            boolean lower = part == 1;
            float length = (lower ? LOWER_LENGTH : UPPER_LENGTH) * size;
            float height = (lower ? LOWER_HEIGHT : UPPER_HEIGHT) * size;
            float roll = (float) Math.toRadians(lower ? LOWER_ROLL : UPPER_ROLL);
            float sweep = (float) Math.toRadians(sweepDegrees);
            Quaternionf rotation = left
                    ? new Quaternionf().rotateY(sweep).rotateZ(roll)
                    : new Quaternionf().rotateY(-sweep).rotateY((float) Math.PI).rotateZ(roll);
            Vector3f root = new Vector3f((left ? 0.06f : -0.06f) * bodyScale, WING_ROOT_HEIGHT * bodyScale,
                    -WING_BACK * bodyScale);
            if (part == 2) {
                float bone = BONE_THICKNESS * size;
                Vector3f edge = rotation.transform(new Vector3f(0, height - bone * 0.5f, 0)).add(root);
                return new Transformation(edge, rotation, new Vector3f(length * 1.06f, bone, bone),
                        new Quaternionf());
            }
            return new Transformation(root, rotation, new Vector3f(length, height, MEMBRANE_THICKNESS),
                    new Quaternionf());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void removeAll(@Nullable BlockDisplay... displays) {
        for (BlockDisplay display : displays) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
    }

    /** The ground straight below {@code at} within {@code depth} blocks, null over the void. */
    private static @Nullable Location groundBelow(Location at, double depth) {
        RayTraceResult down = at.getWorld().rayTraceBlocks(at, new Vector(0, -1, 0), depth,
                FluidCollisionMode.ALWAYS, true);
        return down == null ? null : down.getHitPosition().toLocation(at.getWorld());
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }

    /** Claims {@code count} display entities for {@code lifetime} ticks if that stays within {@link #MAX_DISPLAYS}. */
    private boolean reserveDisplays(int count, long lifetime) {
        if (count <= 0 || liveDisplays + count > MAX_DISPLAYS) {
            return false;
        }
        liveDisplays += count;
        ctx().runLater(player(), Math.max(1, lifetime), () -> liveDisplays -= count);
        return true;
    }
}
