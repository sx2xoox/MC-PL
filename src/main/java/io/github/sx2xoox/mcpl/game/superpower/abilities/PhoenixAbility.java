package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.EntityEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 불사조 — rises from its own ashes once per match, soars on wings of flame and, as the ultimate, dives forward as a
 * blazing phoenix that scorches everything in its path.
 */
public final class PhoenixAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("phoenix", "불사조", Material.TOTEM_OF_UNDYING)
            .color(TextColor.color(0xFF7A1A))
            .difficulty(1)
            .passive("환생",
                    "경기당 한 번, 치명상을 입으면 쓰러지지 않고 최대 체력의 {revive_health}%로 되살아나 "
                            + "{revive_grace}초간 피해를 받지 않습니다. 이때 주변 {revive_radius}블록 안의 적에게 "
                            + "{revive_damage} 피해를 주고 {revive_burn}초간 불태웁니다.")
            .active("화염 날개",
                    "약 {leap_height}블록 높이로 날아올라 화염 꼬리를 남기며 {glide_duration}초간 천천히 내려옵니다. "
                            + "웅크리면 급강하하며, 이 비행으로는 낙하 피해를 받지 않습니다.",
                    9)
            .ultimate("불사조 강림",
                    "바라보는 방향으로 최대 {dash_distance}블록 화염 돌진합니다. 경로 위의 적에게 {dash_damage} 피해를 "
                            + "주고 {dash_burn}초간 불태웁니다.")
            .param("revive_health", 50)
            .param("revive_grace", 1)
            .param("revive_radius", 5)
            .param("revive_damage", 4)
            .param("revive_burn", 3)
            .param("leap_height", 8)
            .param("glide_duration", 3)
            .param("dash_distance", 15)
            .param("dash_radius", 2)
            .param("dash_damage", 6)
            .param("dash_burn", 3)
            .build();

    private static final String FLAME = "#FF7A1A";

    // ------------------------------------------------------------------ look: white-gold core, orange, crimson
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF3B0, 0xFF9F1C, 0xE63946);
    private static final Particle.DustTransition HEAT = PALETTE.glow(1.2f);
    private static final Particle.DustTransition EMBER = PALETTE.fade(1.0f);
    private static final Particle.DustTransition WING_CORE = PALETTE.glow(0.8f);
    private static final Particle.DustTransition WING_EDGE = PALETTE.fade(1.1f);
    private static final Particle.DustOptions ASH_DUST = new Particle.DustOptions(Color.fromRGB(0x4A, 0x3C, 0x36),
            0.9f);
    private static final BlockData SCORCH = Material.MAGMA_BLOCK.createBlockData();

    /**
     * Particle wing outline for the dash, in body space: x = distance out from the spine, y = height relative to the
     * shoulders. Points run along the leading edge to the tip and back along the trailing feathers.
     */
    private static final double[][] WING = {
            {0.15, 0.05}, {0.35, 0.25}, {0.6, 0.45}, {0.9, 0.6}, {1.2, 0.62}, {1.45, 0.5},
            {1.25, 0.3}, {1.0, 0.12}, {0.75, -0.05}, {0.5, -0.2}, {0.3, -0.25}};
    private static final double SHOULDER_HEIGHT = 1.35;

    /** Display wings of the leap: per side a fan of three glass feathers (yellow core, orange, crimson tip). */
    private static final BlockData[] FEATHER_BLOCKS = {
            Material.YELLOW_STAINED_GLASS.createBlockData(), Material.ORANGE_STAINED_GLASS.createBlockData(),
            Material.RED_STAINED_GLASS.createBlockData()};
    /** Fan angle of each feather (degrees, up is positive), its length and width on a full-size body. */
    private static final float[] FEATHER_ROLL = {30f, 5f, -22f};
    private static final float[] FEATHER_LENGTH = {1.0f, 1.25f, 0.85f};
    private static final float[] FEATHER_WIDTH = {0.2f, 0.24f, 0.18f};
    private static final float FEATHER_THICKNESS = 0.03f;
    private static final float WING_ROOT_HEIGHT = 1.3f;
    private static final float WING_ROOT_BACK = 0.22f;
    private static final double GLIDE_SWEEP = 30;
    private static final double GLIDE_SWEEP_AMPLITUDE = 18;
    private static final double DIVE_SWEEP = 78;
    private static final int WING_STEP_TICKS = 2;
    private static final int WING_OPEN_TICKS = 4;
    private static final int WING_FOLD_TICKS = 6;

    // Rebirth visuals
    private static final int PILLAR_TICKS = 14;
    private static final double PILLAR_HEIGHT = 5;
    private static final int REBIRTH_FEATHERS = 8;
    private static final int FEATHER_FLIGHT_TICKS = 12;
    private static final int SHOCKWAVE_TICKS = 6;

    // Leap
    private static final double LEAP_FORWARD = 0.45;
    private static final double MAX_LEAP_VELOCITY = 2.5;
    /** Launch speed of a plain vanilla jump, used when no leap height is configured. */
    private static final double PLAIN_JUMP = 0.42;
    /** Caps every velocity this ability applies, so nobody is flung out of the arena. */
    private static final double MAX_VELOCITY = 3.0;
    /** Upper bound on how long a leap may last before its state is dropped (the glide itself is a potion). */
    private static final long LEAP_TIMEOUT_TICKS = 20 * 20;
    private static final long LEAP_MIN_AIR_TICKS = 6;
    /** If the server never sees the owner leave the ground (blocked by a ceiling, extreme lag), give up. */
    private static final long LEAP_NEVER_AIRBORNE_TICKS = 40;
    private static final double DIVE_VELOCITY = -1.2;
    private static final long FALL_GRACE_TICKS = 10;

    // Dash
    private static final double DASH_SPEED = 1.5;
    private static final double MIN_DASH = 2.0;
    private static final double MAX_DASH_PITCH = 25.0;
    private static final double DASH_LIFT = 0.1;
    private static final double WALL_MARGIN = 0.7;
    /** Extra ticks after the last push during which hits are still checked (client latency). */
    private static final int DASH_LATENCY_TICKS = 6;
    /** After the dash, the landing is protected for at most this long (only the height the dash itself added). */
    private static final int DASH_LANDING_TICKS = 60;
    private static final int SCORCH_TICKS = 30;
    /** Scorch marks (glowing magma decals) left on the ground along the path, at most this many. */
    private static final int MAX_SCORCH_MARKS = 8;
    private static final double SCORCH_SPACING = 2.0;
    private static final double SCORCH_GROUND_SEARCH = 1.2;

    private boolean rebirthUsed;
    private long rebirthGraceUntil = -1;
    private boolean leaping;
    /** The server has seen the owner off the ground since the leap began (positions arrive with client lag). */
    private boolean leftGround;
    private boolean dived;
    private long leapStartTick;
    private long fallGraceUntil = -1;
    private @Nullable FlameWings wings;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        if (wings != null) {
            wings.remove();
            wings = null;
        }
    }

    // ------------------------------------------------------------------ passive: rebirth

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        long now = ctx().currentTick();
        if (now <= rebirthGraceUntil && !isInescapable(event)) {
            event.setCancelled(true);
            return;
        }
        if (Combat.isFall(event) && (leaping || now <= fallGraceUntil)) {
            event.setCancelled(true);
            return;
        }
        Player owner = player();
        if (rebirthUsed || event.getFinalDamage() < owner.getHealth() || isInescapable(event)) {
            return;
        }
        event.setCancelled(true);
        rebirth(owner);
    }

    /** Falling out of the world or being killed by command cannot be survived (it would just repeat). */
    private static boolean isInescapable(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.VOID || cause == DamageCause.KILL || cause == DamageCause.SUICIDE) {
            return true;
        }
        DamageType type = event.getDamageSource().getDamageType();
        return type == DamageType.OUT_OF_WORLD || type == DamageType.GENERIC_KILL;
    }

    private void rebirth(Player owner) {
        rebirthUsed = true;
        long now = ctx().currentTick();
        rebirthGraceUntil = now + params().ticks("revive_grace");
        double max = Combat.maxHealth(owner);
        owner.setHealth(Math.clamp(max * params().num("revive_health") / 100.0, 1.0, max));
        owner.setFireTicks(0);
        owner.setFreezeTicks(0);
        owner.removePotionEffect(PotionEffectType.POISON);
        owner.removePotionEffect(PotionEffectType.WITHER);
        owner.setFallDistance(0);
        owner.playEffect(EntityEffect.PROTECTED_FROM_DEATH);

        // Out of the ashes: a white-hot flash, a pillar of fire spiralling up and feathers bursting free.
        Location feet = owner.getLocation();
        Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
        Vfx.burst(ctx(), body, PALETTE, 1.2, Particle.FLAME, false);
        Vfx.cloud(ctx(), feet.clone().add(0, 0.2, 0), Particle.LARGE_SMOKE, 8, 0.5, 0.02, null, false);
        firePillar(feet);
        featherBurst(body);
        Fx.sound(feet, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.6f);
        Fx.sound(feet, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.3f);
        Fx.sound(feet, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 0.8f);
        Fx.sound(feet, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.9f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + FLAME + "><b>✹ 환생!</b></" + FLAME + "> <white>잿더미에서 되살아났습니다</white>"));
        ctx().announce(Text.mm("<" + FLAME + ">✹</" + FLAME + "> <white><player></white><gray>이(가) "
                + "</gray><" + FLAME + ">불사조</" + FLAME + "><gray>의 힘으로 잿더미에서 되살아났습니다!</gray>",
                Placeholder.unparsed("player", owner.getName())));
        // The shockwave is dealt a tick later, outside the damage event that triggered the rebirth.
        ctx().runLater(owner, 1, this::rebirthShockwave);
    }

    /** A double helix of flame that climbs out of the ground and narrows into a column of white heat. */
    private void firePillar(Location feet) {
        Location base = feet.clone();
        Loop.repeat(ctx(), player(), 0, 1, PILLAR_TICKS, run -> {
            double t = (double) (run + 1) / PILLAR_TICKS;
            double height = PILLAR_HEIGHT * Math.min(1, t * 1.6);
            double radius = 0.9 - 0.5 * t;
            Vfx.helix(ctx(), base, radius, height, 1.25, 2, 10, run * 0.45, Particle.FLAME, null, false);
            Vfx.helix(ctx(), base, radius * 0.75, height, 1.25, 2, 6, run * 0.45 + Math.PI / 2,
                    Particle.DUST_COLOR_TRANSITION, HEAT, false);
            if (run % 3 == 0) {
                Vfx.line(ctx(), base.clone().add(0, 0.2, 0), base.clone().add(0, height, 0), 1.5, Particle.END_ROD,
                        null, false);
            }
            return true;
        });
    }

    /** Feathers (item displays) burst out of the flames, spin and fade. */
    private void featherBurst(Location body) {
        Player owner = player();
        Location at = body.clone();
        at.setYaw(0);
        at.setPitch(0);
        ItemStack feather = new ItemStack(Material.FEATHER);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double base = random.nextDouble(Math.PI * 2);
        for (int i = 0; i < REBIRTH_FEATHERS; i++) {
            double angle = base + i * Math.PI * 2 / REBIRTH_FEATHERS;
            float yaw = (float) Math.toDegrees(angle);
            ItemDisplay display = Displays.item(ctx(), owner, at, feather,
                    Displays.centered(0, 0, 0, 0.2f, 0.2f, 0.2f, yaw, 0, 0), FEATHER_FLIGHT_TICKS + 14);
            double reach = 2.0 + random.nextDouble(0.8);
            Transformation out = Displays.centered((float) (Math.cos(angle) * reach), (float) random.nextDouble(0.4,
                    1.4), (float) (Math.sin(angle) * reach), 0.7f, 0.7f, 0.7f, -yaw + 90, random.nextInt(-30, 30),
                    random.nextInt(140, 220));
            Displays.animateNextTick(ctx(), owner, display, out, FEATHER_FLIGHT_TICKS);
            ctx().runLater(owner, FEATHER_FLIGHT_TICKS + 1, () -> Displays.shrinkAway(ctx(), owner, display, 10));
        }
    }

    private void rebirthShockwave() {
        Player owner = player();
        if (!owner.isOnline() || !ctx().isAlive(owner)) {
            return;
        }
        Location center = owner.getLocation();
        double radius = params().num("revive_radius");
        double damage = params().num("revive_damage");
        long burn = params().ticks("revive_burn");
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            Combat.hit(ctx(), owner, enemy, damage);
            ignite(enemy, burn);
            Vector push = enemy.getLocation().toVector().subtract(center.toVector()).setY(0);
            if (push.lengthSquared() < 1.0E-4) {
                push = new Vector(ThreadLocalRandom.current().nextDouble() - 0.5, 0,
                        ThreadLocalRandom.current().nextDouble() - 0.5);
            }
            push.normalize().multiply(0.9).setY(0.4);
            // Replaces the velocity: a player's velocity on the server is stale, never add to it.
            enemy.setVelocity(Motion.clamp(push, MAX_VELOCITY));
            Vfx.burst(ctx(), Fx.body(enemy), PALETTE, 0.6, Particle.FLAME, false);
        }
        Fx.sound(center, Sound.ITEM_FIRECHARGE_USE, 1.0f, 0.7f);
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f);
        // A ring of fire racing out to the radius, a heat ring behind it, then embers rising where it passed.
        Location ground = center.clone().add(0, 0.15, 0);
        Vfx.shockwave(ctx(), owner, ground, 0.4, radius, SHOCKWAVE_TICKS, Particle.FLAME, null, false);
        Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.2, 0), 0.2, radius * 0.85, SHOCKWAVE_TICKS,
                Particle.DUST_COLOR_TRANSITION, EMBER, false);
        Loop.repeat(ctx(), owner, SHOCKWAVE_TICKS, 3, 6, run -> {
            for (int i = 0; i < Vfx.n(ctx(), 5); i++) {
                Location at = Vfx.scatter(ground, radius);
                at.getWorld().spawnParticle(Particle.SMALL_FLAME, at, 0, 0, 0.08, 0, 1);
                if (i % 2 == 0) {
                    Vfx.dot(at.add(0, 0.3, 0), Particle.WHITE_ASH, null, false);
                }
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ active: flame wings

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 날아오를 수 없습니다");
        }
        double height = params().num("leap_height");
        double up = Math.min(MAX_LEAP_VELOCITY, height > 0 ? Motion.launchSpeed(height) : PLAIN_JUMP);
        Vector forward = Motion.horizontal(owner.getLocation().getDirection()).multiply(LEAP_FORWARD);
        owner.setVelocity(Motion.clamp(new Vector(forward.getX(), up, forward.getZ()), MAX_VELOCITY));
        owner.setFallDistance(0);
        int glide = (int) params().ticks("glide_duration");
        // Slow falling has no effect while rising, so the ascent time is simply added on top.
        int ascent = ascentTicks(up);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, ascent + glide, 0, false, false, true));
        leaping = true;
        leftGround = false;
        dived = false;
        leapStartTick = ctx().currentTick();

        // Launch: a burst of flame rushing out over the ground and a fiery updraft.
        Location feet = owner.getLocation();
        Location ground = feet.clone().add(0, 0.1, 0);
        Vfx.shockwave(ctx(), owner, ground, 0.3, 1.8, 4, Particle.FLAME, null, false);
        Vfx.ring(ctx(), ground, 1.0, 14, 0, Particle.DUST_COLOR_TRANSITION, EMBER, false);
        Vfx.helix(ctx(), feet, 0.5, 2.2, 1.0, 2, 8, 0, Particle.FLAME, null, false);
        Vfx.cloud(ctx(), feet, Particle.LARGE_SMOKE, 5, 0.3, 0.02, null, false);
        if (wings != null) {
            wings.remove();
        }
        wings = new FlameWings(owner);
        Fx.sound(feet, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(feet, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.9f + Fx.jitter(0.1f));
        Fx.sound(feet, Sound.ITEM_FIRECHARGE_USE, 0.5f, 1.4f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + FLAME + ">✹ 화염 날개</" + FLAME + "> <gray>웅크리면 급강하</gray>"));
        return ActivationResult.success();
    }

    @Override
    public void onTick(long tick) {
        if (leaping) {
            tickLeap(tick);
        } else if (!rebirthUsed && tick % 20 == 0) {
            // The tell that the rebirth is still available: a single ember drifting off the shoulders.
            Player owner = player();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location at = owner.getLocation().add(random.nextDouble(-0.3, 0.3), owner.getHeight() * 0.8,
                    random.nextDouble(-0.3, 0.3));
            at.getWorld().spawnParticle(Particle.SMALL_FLAME, at, 0, 0, 0.04, 0, 1);
            Vfx.dot(at.add(0, 0.15, 0), Particle.DUST_COLOR_TRANSITION, WING_CORE, false);
        }
    }

    private void tickLeap(long tick) {
        Player owner = player();
        long airborne = tick - leapStartTick;
        owner.setFallDistance(0);
        boolean grounded = Motion.isGrounded(owner) || owner.isInWater();
        if (!grounded) {
            leftGround = true;
        }
        if (airborne > LEAP_TIMEOUT_TICKS
                || (leftGround && airborne >= LEAP_MIN_AIR_TICKS && grounded)
                || (!leftGround && airborne > LEAP_NEVER_AIRBORNE_TICKS)) {
            endLeap(owner, tick);
            return;
        }
        if (!dived && airborne >= LEAP_MIN_AIR_TICKS && owner.isSneaking()) {
            dived = true;
            owner.removePotionEffect(PotionEffectType.SLOW_FALLING);
            // Down and along the view: a player's velocity on the server is stale (its client moves it), so the
            // dive does not try to keep it.
            Vector dive = Motion.horizontal(owner.getLocation().getDirection()).multiply(LEAP_FORWARD)
                    .setY(DIVE_VELOCITY);
            owner.setVelocity(Motion.clamp(dive, MAX_VELOCITY));
            Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
            Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.FLAME, false);
            Fx.sound(owner.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1.0f, 0.7f);
            Fx.sound(owner.getLocation(), Sound.ENTITY_PHANTOM_SWOOP, 0.7f, 1.4f + Fx.jitter(0.1f));
        }
        if (wings != null && !wings.update(owner, tick, dived)) {
            wings = null;
        }
        if (tick % 2 != 0) {
            return;
        }
        // A tail of fire under the flier; a falling comet streak while diving.
        Location feet = owner.getLocation();
        if (dived) {
            Vfx.line(ctx(), feet, feet.clone().add(0, 1.6, 0), 3, Particle.FLAME, null, false);
            Vfx.dot(feet.clone().add(0, 0.8, 0), Particle.DUST_COLOR_TRANSITION, HEAT, false);
        } else {
            Vfx.dot(feet.clone().add(0, 0.2, 0), Particle.FLAME, null, false);
            Vfx.dot(feet.clone().add(0, 0.45, 0), Particle.DUST_COLOR_TRANSITION, EMBER, false);
            if (tick % 6 == 0) {
                Vfx.dot(feet, Particle.SMALL_FLAME, null, false);
            }
        }
        if (tick % 12 == 0 && !dived) {
            Fx.sound(feet, Sound.ENTITY_PHANTOM_FLAP, 0.4f, 1.3f + Fx.jitter(0.1f));
        }
    }

    private void endLeap(Player owner, long tick) {
        leaping = false;
        fallGraceUntil = tick + FALL_GRACE_TICKS;
        owner.setFallDistance(0);
        PotionEffect glide = owner.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (glide != null && !glide.hasParticles() && glide.getAmplifier() == 0) {
            owner.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
        if (wings != null) {
            wings.fold();
            wings = null;
        }
        Location ground = owner.getLocation().add(0, 0.15, 0);
        if (dived) {
            // The dive lands like a meteor: a flash, a ring of fire and a heat ring rushing out.
            Vfx.burst(ctx(), ground.clone().add(0, 0.3, 0), PALETTE, 0.8, Particle.LAVA, false);
            Vfx.shockwave(ctx(), owner, ground, 0.3, 2.2, 5, Particle.FLAME, null, false);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.1, 0), 0.2, 1.8, 5, Particle.DUST_COLOR_TRANSITION,
                    EMBER, false);
            Fx.sound(ground, Sound.ITEM_FIRECHARGE_USE, 0.7f, 1.2f);
            Fx.sound(ground, Sound.ITEM_MACE_SMASH_GROUND, 0.6f, 1.3f + Fx.jitter(0.1f));
        } else {
            Vfx.ring(ctx(), ground, 0.7, 12, 0, Particle.SMALL_FLAME, null, false);
            Vfx.ring(ctx(), ground.clone().add(0, 0.1, 0), 0.5, 8, 0.4, Particle.DUST_COLOR_TRANSITION, EMBER,
                    false);
        }
    }

    // ------------------------------------------------------------------ ultimate: phoenix descent

    /**
     * Velocity based (the client resolves collisions, so the owner can never be pushed through a wall) and
     * self-contained: it only uses owner-scoped tasks and protects its own landing (the fall distance the dash added),
     * so it also works when borrowed by the Gambler.
     */
    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 돌진할 수 없습니다");
        }
        Vector direction = dashDirection(owner);
        double reach = freeDistance(owner, direction, params().num("dash_distance"));
        if (reach < MIN_DASH) {
            return ActivationResult.fail("앞이 막혀 돌진할 수 없습니다");
        }
        // The dash never carries the owner past the arena border.
        reach = Spots.arenaReach(ctx(), owner.getLocation(), direction, reach);
        if (reach < MIN_DASH) {
            return ActivationResult.fail("자기장 밖으로는 돌진할 수 없습니다");
        }
        new Dash(owner, direction, reach).start();
        return ActivationResult.success();
    }

    private final class Dash implements LongPredicate {
        private final Player owner;
        private final Vector direction;
        private final int pushes;
        private final double speed;
        private final double reach;
        private final double radius;
        private final Set<UUID> hit = new HashSet<>();
        private final List<Location> path = new ArrayList<>();
        private final double startY;
        /** Falls are only counted below this height after the dash (the lower of its start and end). */
        private double landingY;
        private Location previous;
        private int age;
        private long groundedTicks;

        Dash(Player owner, Vector direction, double reach) {
            this.owner = owner;
            this.direction = direction;
            this.pushes = Math.max(1, (int) Math.ceil(reach / DASH_SPEED));
            this.speed = reach / pushes;
            this.reach = reach;
            this.radius = Math.max(0.5, params().num("dash_radius"));
            this.previous = owner.getLocation();
            this.startY = previous.getY();
            this.landingY = startY;
        }

        void start() {
            Location feet = owner.getLocation();
            Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
            // Telegraph: the path flares up for an instant, and sparks race ahead along it.
            Location end = body.clone().add(direction.clone().multiply(reach));
            Vfx.line(ctx(), body, end, 2, Particle.DUST_COLOR_TRANSITION, EMBER, true);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                Location from = body.clone().add(random.nextDouble(-0.4, 0.4), random.nextDouble(-0.3, 0.3),
                        random.nextDouble(-0.4, 0.4));
                Vfx.trail(from, end, i % 2 == 0 ? PALETTE.core() : PALETTE.body(), pushes + 2, true);
            }
            // Ignition: a white-hot flash and the phoenix spreading its wings.
            Vfx.burst(ctx(), body, PALETTE, 1.2, Particle.FLAME, true);
            drawWings(owner, 0.15, 1.6, true);
            Fx.sound(feet, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.7f);
            Fx.sound(feet, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.5f);
            Fx.sound(feet, Sound.ITEM_FIRECHARGE_USE, 1.0f, 0.8f);
            Fx.sound(feet, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.45f, 1.8f + Fx.jitter(0.05f));
            Loop.repeat(ctx(), owner, 0, 1, pushes + DASH_LATENCY_TICKS + DASH_LANDING_TICKS, this);
        }

        @Override
        public boolean test(long run) {
            if (owner.isInsideVehicle()) {
                return false;
            }
            age++;
            if (age > pushes + DASH_LATENCY_TICKS) {
                return protectLanding();
            }
            owner.setFallDistance(0);
            if (age <= pushes) {
                Vector velocity = direction.clone().multiply(speed);
                velocity.setY(velocity.getY() + DASH_LIFT);
                owner.setVelocity(Motion.clamp(velocity, MAX_VELOCITY));
            } else if (age == pushes + 1) {
                // Brake, or the air drag would carry the owner much further than the dash.
                owner.setVelocity(direction.clone().multiply(0.3).setY(0.05));
            }
            Location now = owner.getLocation();
            sweep(previous, now);
            trail(previous, now);
            previous = now;
            if (age == pushes + DASH_LATENCY_TICKS) {
                finish(now);
                // An upward dash ends high in the air: forgive the fall back down to where it started (or to where
                // it ended, if that is lower), but not a drop below it, e.g. off a cliff.
                landingY = Math.min(startY, now.getY());
            }
            return true;
        }

        /**
         * Runs every tick after the dash until the owner has settled on the ground: the fall distance only counts
         * what lies below {@link #landingY}. Self-contained, so a Gambler-borrowed dash is protected too.
         */
        private boolean protectLanding() {
            if (owner.isGliding()) {
                return false;
            }
            owner.setFallDistance((float) Math.max(0, landingY - owner.getY()));
            if (Motion.isGrounded(owner) || owner.isInWater()) {
                return ++groundedTicks < LandingGuard.SETTLE_TICKS;
            }
            groundedTicks = 0;
            return true;
        }

        /** Damages every enemy within the dash radius of the segment travelled since the last tick. */
        private void sweep(Location from, Location to) {
            Vector a = from.toVector().add(new Vector(0, owner.getHeight() * 0.5, 0));
            Vector b = to.toVector().add(new Vector(0, owner.getHeight() * 0.5, 0));
            Location mid = a.clone().add(b).multiply(0.5).toLocation(to.getWorld());
            double search = a.distance(b) / 2 + radius;
            double damage = params().num("dash_damage");
            long burn = params().ticks("dash_burn");
            for (LivingEntity enemy : ctx().enemiesNear(owner, mid, search)) {
                if (hit.contains(enemy.getUniqueId())) {
                    continue;
                }
                Vector center = enemy.getLocation().toVector().add(new Vector(0, enemy.getHeight() * 0.5, 0));
                if (distanceToSegment(center, a, b) > radius + enemy.getWidth() / 2) {
                    continue;
                }
                hit.add(enemy.getUniqueId());
                Combat.hit(ctx(), owner, enemy, damage);
                ignite(enemy, burn);
                // Flung aside and up, away from the phoenix's path.
                Vector side = new Vector(-direction.getZ(), 0, direction.getX());
                if (center.clone().subtract(a).dot(side) < 0) {
                    side.multiply(-1);
                }
                if (side.lengthSquared() > 1.0E-6) {
                    side.normalize();
                }
                Vector fling = side.multiply(0.6).add(direction.clone().multiply(0.4)).setY(0.45);
                enemy.setVelocity(Motion.clamp(fling, MAX_VELOCITY));
                Location at = Fx.body(enemy);
                Vfx.burst(ctx(), at, PALETTE, 0.7, Particle.FLAME, true);
                Vfx.cloud(ctx(), at, Particle.LAVA, 3, 0.2, 0, null, false);
                Fx.sound(at, Sound.ENTITY_BLAZE_HURT, 0.8f, 1.2f + Fx.jitter(0.1f));
                Fx.sound(at, Sound.ITEM_FIRECHARGE_USE, 0.6f, 1.5f + Fx.jitter(0.1f));
            }
        }

        /** The blazing body: spread wings, a streak of fire back along the segment and heat motes streaming off. */
        private void trail(Location from, Location feet) {
            Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
            if (age <= pushes) {
                Location back = from.clone().add(0, owner.getHeight() * 0.5, 0);
                Vfx.line(ctx(), back, body, 2.5, Particle.FLAME, null, true);
                Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 5, 0.3, 0, HEAT, true);
                Vfx.cloud(ctx(), body.clone().add(direction.clone().multiply(0.6)), Particle.END_ROD, 2, 0.12, 0,
                        null, true);
                drawWings(owner, 0.2 + 0.1 * Math.sin(age * 1.3), 1.4, true);
                Location wake = body.clone().subtract(direction.clone().multiply(3));
                for (int side = -1; side <= 1; side += 2) {
                    Vector out = new Vector(-direction.getZ(), 0, direction.getX()).multiply(side * 0.9);
                    Vfx.trail(body.clone().add(out), wake.clone().add(out.multiply(1.6)), PALETTE.body(), 6, true);
                }
                path.add(feet.clone());
            } else {
                Vfx.cloud(ctx(), body, Particle.FLAME, 3, 0.25, 0.02, null, false);
            }
            if (age % 3 == 0) {
                Fx.sound(feet, Sound.BLOCK_FIRE_AMBIENT, 0.8f, 1.4f + Fx.jitter(0.2f));
            }
        }

        private void finish(Location feet) {
            Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
            // The phoenix bursts into flame where it lands, then the path smoulders.
            Vfx.burst(ctx(), body, PALETTE, 1.3, Particle.FLAME, true);
            Vfx.dot(body, Particle.EXPLOSION, null, true);
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.15, 0), 0.4, 2.6, 5, Particle.FLAME, null, true);
            Vfx.cloud(ctx(), body, Particle.LAVA, 6, 0.4, 0, null, false);
            Fx.sound(feet, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.6f);
            Fx.sound(feet, Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.6f + Fx.jitter(0.05f));
            if (!hit.isEmpty()) {
                owner.sendActionBar(Text.mm("<" + FLAME + ">✹ 불사조 강림</" + FLAME + "> <white>" + hit.size()
                        + "명 적중</white>"));
            }
            scorch(path);
        }
    }

    /** Glowing scorch marks on the ground along the path, with embers and ash drifting off them (visual only). */
    private void scorch(List<Location> path) {
        if (path.isEmpty()) {
            return;
        }
        Player owner = player();
        List<Location> ground = new ArrayList<>();
        Location last = null;
        for (Location point : path) {
            if (last != null && last.distanceSquared(point) < SCORCH_SPACING * SCORCH_SPACING) {
                continue;
            }
            last = point;
            RayTraceResult floor = point.getWorld().rayTraceBlocks(point.clone().add(0, 0.2, 0),
                    new Vector(0, -1, 0), SCORCH_GROUND_SEARCH, FluidCollisionMode.NEVER, true);
            if (floor == null || ground.size() >= MAX_SCORCH_MARKS) {
                continue;
            }
            Location mark = floor.getHitPosition().toLocation(point.getWorld()).add(0, 0.02, 0);
            ground.add(mark);
            float yaw = ThreadLocalRandom.current().nextFloat() * 90;
            BlockDisplay decal = Displays.block(ctx(), owner, mark, SCORCH, Displays.transform(0, 0, 0, 0.2f, 0.02f,
                    0.2f, yaw, 0, 0), SCORCH_TICKS + 12);
            Displays.animateNextTick(ctx(), owner, decal, Displays.transform(0, 0, 0, 1.0f, 0.02f, 1.0f, yaw, 0, 0),
                    4);
            ctx().runLater(owner, SCORCH_TICKS, () -> Displays.shrinkAway(ctx(), owner, decal, 10));
        }
        Loop.repeat(ctx(), owner, 4, 4, SCORCH_TICKS / 4, run -> {
            boolean ash = run % 2 == 1;
            for (Location point : path) {
                Location at = point.clone().add(0, 0.15, 0);
                at.getWorld().spawnParticle(Particle.SMALL_FLAME, at.getX(), at.getY(), at.getZ(), 0,
                        ThreadLocalRandom.current().nextDouble(-0.01, 0.01), 0.05, 0, 1);
                if (ash) {
                    Vfx.dot(at.add(0, 0.25, 0), Particle.DUST, ASH_DUST, false);
                }
            }
            return true;
        });
    }

    /** The look direction with its pitch limited, so the dash stays roughly level. */
    private static Vector dashDirection(Player owner) {
        Location eye = owner.getEyeLocation();
        double pitch = Math.toRadians(Math.clamp(eye.getPitch(), -MAX_DASH_PITCH, MAX_DASH_PITCH));
        double yaw = Math.toRadians(eye.getYaw());
        return new Vector(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    /**
     * How far the owner can dash along {@code direction} before a wall. Walls are searched horizontally at chest and
     * head height; floors and ceilings are left to the client's collision (the owner just slides along them), so
     * aiming slightly down at an enemy does not cut the dash short.
     */
    private static double freeDistance(Player owner, Vector direction, double max) {
        Vector horizontal = direction.clone().setY(0);
        double cosPitch = horizontal.length();
        if (cosPitch < 1.0E-3) {
            return 0;
        }
        horizontal.multiply(1 / cosPitch);
        World world = owner.getWorld();
        Location feet = owner.getLocation();
        double maxHorizontal = max * cosPitch;
        double reach = maxHorizontal;
        for (double height : new double[] {owner.getHeight() * 0.5, owner.getHeight() * 0.9}) {
            Location start = feet.clone().add(0, height, 0);
            RayTraceResult wall = world.rayTraceBlocks(start, horizontal, maxHorizontal, FluidCollisionMode.NEVER,
                    true);
            if (wall != null) {
                reach = Math.min(reach, wall.getHitPosition().distance(start.toVector()) - WALL_MARGIN);
            }
        }
        return Math.max(0, reach) / cosPitch;
    }

    private static double distanceToSegment(Vector point, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double lengthSquared = ab.lengthSquared();
        if (lengthSquared < 1.0E-9) {
            return point.distance(a);
        }
        double t = Math.clamp(point.clone().subtract(a).dot(ab) / lengthSquared, 0.0, 1.0);
        return point.distance(a.clone().add(ab.multiply(t)));
    }

    // ------------------------------------------------------------------ visuals

    /**
     * Draws a pair of particle flame wings behind the owner's back (the dash moves too fast for displays to keep up):
     * a white-gold leading edge, orange-to-crimson trailing feathers and flame at the tips.
     *
     * @param flap  how far the wings are swept back, in radians (0 = spread flat to the sides)
     * @param size  wing span multiplier on top of the owner's scale
     * @param force visible from far away (the ultimate)
     */
    private static void drawWings(Player owner, double flap, double size, boolean force) {
        double bodyScale = Body.scaleOf(owner);
        double scale = bodyScale * size;
        double yaw = Math.toRadians(owner.getBodyYaw());
        Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vector back = new Vector(Math.sin(yaw), 0, -Math.cos(yaw));
        Location shoulder = owner.getLocation().add(0, SHOULDER_HEIGHT * bodyScale, 0)
                .add(back.clone().multiply(0.2 * bodyScale));
        for (int side = -1; side <= 1; side += 2) {
            Vector out = right.clone().multiply(side * Math.cos(flap)).add(back.clone().multiply(Math.sin(flap)));
            for (int i = 0; i < WING.length; i++) {
                double[] point = WING[i];
                Location at = shoulder.clone().add(out.clone().multiply(point[0] * scale)).add(0, point[1] * scale, 0);
                if (i == 5) {
                    Vfx.dot(at, Particle.FLAME, null, force);
                }
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, i < 6 ? WING_CORE : WING_EDGE, force);
            }
        }
    }

    /**
     * The leap's wings: per side a fan of three thin glass feathers (block displays) rooted between the shoulder
     * blades. They follow the owner, beat slowly while gliding and fold back into a dive. Flames lick off the tips.
     * The body's facing is part of each feather's transformation: the displays themselves are never rotated.
     */
    private final class FlameWings {
        private final BlockDisplay[] feathers = new BlockDisplay[FEATHER_BLOCKS.length * 2];
        private final float bodyScale;
        private double phase;
        private int age;

        FlameWings(Player owner) {
            this.bodyScale = (float) Body.scaleOf(owner);
            Location at = owner.getLocation();
            float bodyYaw = owner.getBodyYaw();
            Transformation folded = new Transformation(new Vector3f(0, WING_ROOT_HEIGHT * bodyScale, 0),
                    new Quaternionf(), new Vector3f(0.001f), new Quaternionf());
            for (int i = 0; i < feathers.length; i++) {
                boolean left = i < FEATHER_BLOCKS.length;
                int index = i % FEATHER_BLOCKS.length;
                BlockDisplay feather = Displays.block(ctx(), owner, at, FEATHER_BLOCKS[index], folded,
                        LEAP_TIMEOUT_TICKS + WING_FOLD_TICKS + 20);
                Displays.animateNextTick(ctx(), owner, feather, feather(left, index, GLIDE_SWEEP, 1.0, bodyYaw),
                        WING_OPEN_TICKS);
                feathers[i] = feather;
            }
        }

        /** Follows the owner and beats; false (after removing itself) if the wings are gone. */
        boolean update(Player owner, long tick, boolean diving) {
            for (BlockDisplay feather : feathers) {
                if (!feather.isValid() || feather.getWorld() != owner.getWorld()) {
                    remove();
                    return false;
                }
            }
            Location at = owner.getLocation();
            for (BlockDisplay feather : feathers) {
                Displays.moveTo(feather, at);
            }
            phase += 0.35;
            age++;
            // The opening animation starts the tick after the wings spawned; until it is over, the wings only turn
            // with the body (finishing on time), then they beat.
            boolean opening = age <= WING_OPEN_TICKS;
            if (tick % WING_STEP_TICKS != 0 || age < 2) {
                return true;
            }
            float bodyYaw = owner.getBodyYaw();
            double sweep = opening ? GLIDE_SWEEP
                    : diving ? DIVE_SWEEP : GLIDE_SWEEP + GLIDE_SWEEP_AMPLITUDE * Math.sin(phase);
            double spread = diving && !opening ? 0.35 : 1.0;
            int ticks = opening ? WING_OPEN_TICKS + 1 - age : WING_STEP_TICKS;
            for (int i = 0; i < feathers.length; i++) {
                boolean left = i < FEATHER_BLOCKS.length;
                Transformation shape = feather(left, i % FEATHER_BLOCKS.length, sweep, spread, bodyYaw);
                Displays.animate(feathers[i], shape, ticks);
                if (!opening && i % FEATHER_BLOCKS.length == 1) {
                    flameAt(at, shape);
                }
            }
            return true;
        }

        /** Flames and heat licking off the tip of the long middle feather. */
        private void flameAt(Location at, Transformation shape) {
            Vector3f tip = shape.getLeftRotation().transform(new Vector3f(shape.getScale().x(),
                    shape.getScale().y() * 0.5f, 0)).add(shape.getTranslation());
            Location point = at.clone().add(tip.x(), tip.y(), tip.z());
            Vfx.dot(point, Particle.FLAME, null, false);
            Vfx.dot(point, Particle.DUST_COLOR_TRANSITION, EMBER, false);
        }

        void fold() {
            for (BlockDisplay feather : feathers) {
                if (feather.isValid()) {
                    Displays.shrinkAway(ctx(), player(), feather, WING_FOLD_TICKS);
                }
            }
        }

        void remove() {
            for (BlockDisplay feather : feathers) {
                if (feather.isValid()) {
                    feather.remove();
                }
            }
        }

        /**
         * One feather: built in body space (local +Z = facing, +X = the owner's left) as a long thin glass block
         * rooted at the spine, fanned by its roll ({@code spread} narrows the fan) and swept back by
         * {@code sweepDegrees}, then turned to the body's yaw (Minecraft's: 0 faces +Z, 90 faces -X).
         */
        private Transformation feather(boolean left, int index, double sweepDegrees, double spread, float bodyYaw) {
            float length = FEATHER_LENGTH[index] * bodyScale;
            float width = FEATHER_WIDTH[index] * bodyScale;
            float roll = (float) Math.toRadians(FEATHER_ROLL[index] * spread);
            float sweep = (float) Math.toRadians(sweepDegrees);
            Quaternionf rotation = left
                    ? new Quaternionf().rotateY(sweep).rotateZ(roll)
                    : new Quaternionf().rotateY(-sweep).rotateY((float) Math.PI).rotateZ(roll);
            // Centre the feather's width on its axis, so it fans around the root instead of hanging off one edge.
            Vector3f root = new Vector3f((left ? 0.08f : -0.08f) * bodyScale, WING_ROOT_HEIGHT * bodyScale,
                    -WING_ROOT_BACK * bodyScale).add(rotation.transform(new Vector3f(0, -width / 2, 0)));
            Quaternionf body = new Quaternionf().rotateY((float) Math.toRadians(-bodyYaw));
            Vector3f turnedRoot = body.transform(root, new Vector3f());
            Quaternionf turned = new Quaternionf(body).mul(rotation);
            return new Transformation(turnedRoot, turned, new Vector3f(length, width, FEATHER_THICKNESS),
                    new Quaternionf());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int ascentTicks(double velocity) {
        int ticks = 0;
        double v = velocity;
        while (v > 0 && ticks < 200) {
            v = (v - 0.08) * 0.98;
            ticks++;
        }
        return ticks;
    }

    private static void ignite(LivingEntity target, long ticks) {
        if (ticks <= 0 || !target.isValid()) {
            return;
        }
        int fireTicks = (int) Math.min(Integer.MAX_VALUE, ticks);
        if (target.getFireTicks() < fireTicks) {
            target.setFireTicks(fireTicks);
        }
    }
}
