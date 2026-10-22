package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 폭탄마 — area denial with fused explosives. Shrugs off blasts (and rides the blast of its own grenade), lobs a
 * ticking grenade and calls a carpet of falling bombs across the aimed spot.
 *
 * <p>No real explosion ever happens: the grenade is a primed TNT entity with zero yield that is removed right before
 * its fuse would run out, the bombs are block displays, and every blast is applied explicitly to enemies only.
 *
 * <p>Visuals: the grenade carries a blinking red light and draws a red countdown ring that closes in before it
 * blows; every blast is a layered fireball of glowing glass cubes with a flash, a fire bloom, debris, an expanding
 * shock ring and smouldering embers. The carpet is flown in by a small bomber whose bombs drift down onto marked,
 * closing target rings.
 */
public final class BomberAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("bomber", "폭탄마", Material.TNT)
            .color(TextColor.color(0xFF7A2E))
            .difficulty(2)
            .passive("방폭복",
                    "폭발 피해를 {blast_resist}% 덜 받습니다. 내 수류탄 폭발에 휘말리면 피해 없이 높이 날아오르며"
                            + "(폭탄 점프) 착지 피해도 받지 않습니다.")
            .active("수류탄",
                    "{grenade_fuse}초 뒤 터지는 수류탄을 던집니다. 반경 {grenade_radius}블록 안의 적에게 거리에 따라 "
                            + "{grenade_min_damage}~{grenade_damage} 피해를 주고 밀쳐냅니다(블록 파괴 없음).",
                    8)
            .ultimate("융단폭격",
                    "조준한 지점({carpet_range}블록 이내)을 가로질러 {carpet_duration}초에 걸쳐 폭탄 {carpet_bombs}개를 "
                            + "떨어뜨립니다. 폭탄마다 반경 {carpet_radius}블록의 적에게 {carpet_damage} 피해를 줍니다"
                            + "(한 적당 최대 {carpet_max_hits}회).")
            .param("blast_resist", 70)
            .param("grenade_fuse", 1.5)
            .param("grenade_radius", 4)
            .param("grenade_damage", 7)
            .param("grenade_min_damage", 3)
            .param("carpet_range", 40)
            .param("carpet_duration", 1.5)
            .param("carpet_bombs", 8)
            .param("carpet_radius", 3)
            .param("carpet_damage", 5)
            .param("carpet_length", 12)
            .param("carpet_max_hits", 3)
            .build();

    private static final double GRENADE_SPEED = 1.0;
    private static final double GRENADE_LIFT = 0.22;
    /** The TNT fuse is this much longer than the grenade timer, so vanilla never detonates it first. */
    private static final int FUSE_MARGIN = 8;
    /** Ticks before detonation in which the blast radius is marked on the ground. */
    private static final long WARNING_TICKS = 12;

    /** Fraction of the blast radius within which the owner gets launched by their own grenade. */
    private static final double BLAST_JUMP_REACH = 0.8;
    private static final double BLAST_JUMP_UP = 1.15;
    private static final double BLAST_JUMP_SIDE = 0.55;
    /** The landing after a blast jump is protected from fall damage for at most this long. */
    private static final long BLAST_JUMP_MAX_AIR = 160;

    private static final double BLAST_PUSH = 0.9;
    private static final double BLAST_LIFT = 0.45;
    private static final double MAX_SPEED = 2.5;
    /** Ground probes start this far above their point, never on a block's face. */
    private static final double GROUND_PROBE_LIFT = 0.05;

    /** Sideways zig-zag of the carpet bombs around the bombing line. */
    private static final double BOMB_LATERAL = 1.4;
    private static final double BOMB_DROP_HEIGHT = 14;
    private static final int BOMB_FALL_TICKS = 10;
    private static final float BOMB_SIZE = 0.8f;
    private static final float BOMB_SPIN_DEGREES = 35f;

    // ------------------------------------------------------------------ visuals

    /** Pale yellow core, alarm red body, burnt brown edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF59D, 0xFF5252, 0x4E342E);
    /** Fire inside a blast: yellow-white to orange. */
    private static final Vfx.Palette FIRE = Vfx.Palette.of(0xFFF59D, 0xFF9F43, 0x4E342E);
    private static final Particle.DustOptions WARNING_DUST = new Particle.DustOptions(PALETTE.body(), 1.2f);
    private static final Particle.DustOptions WARNING_CORE = new Particle.DustOptions(PALETTE.core(), 0.9f);
    /** Glass layers of a fireball, inside out. */
    private static final Material[] FIREBALL = {
            Material.YELLOW_STAINED_GLASS, Material.ORANGE_STAINED_GLASS, Material.RED_STAINED_GLASS};
    private static final int FIREBALL_GROW_TICKS = 3;
    private static final int FIREBALL_FADE_TICKS = 5;
    /** Ticks the ground smoulders after a blast. */
    private static final long EMBER_TICKS = 16;
    private static final float LIGHT_SIZE = 0.24f;

    /** The carpet's bomber flies at most this fast (blocks per tick) and only over carpets of at least this length. */
    private static final double PLANE_MAX_SPEED = 1.2;
    private static final long PLANE_MIN_TICKS = 10;
    /** Ticks the plane keeps flying after the carpet ends before it fades out. */
    private static final int PLANE_FADE_TICKS = 6;
    private static final float PLANE_SPAWN_SCALE = 0.25f;

    /** Cancels the fall damage of the landing after a blast jump. */
    private final LandingGuard blastJump = new LandingGuard();
    private long blastJumpTick;
    /** The target of the blast hit being dealt right now, and whether it landed (see {@link #onDamageDealt}). */
    private @Nullable LivingEntity pendingTarget;
    private boolean landed;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: blast suit

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (blastJump.absorb(event, ctx().currentTick())) {
            // A soft landing: a ring of dust puffs out from the feet.
            Location feet = player().getLocation().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), player(), feet, 0.2, 1.3, 4, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.0f),
                    false);
            Vfx.cloud(ctx(), feet, Particle.POOF, 6, 0.3, 0.03, null, false);
            Fx.sound(feet, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 0.6f, 1.3f + Fx.jitter(0.1f));
            return;
        }
        if (!isExplosive(event)) {
            return;
        }
        double resist = Math.clamp(params().num("blast_resist") / 100.0, 0.0, 1.0);
        event.setDamage(event.getDamage() * (1 - resist));
        // The blast suit soaks it up: a shell of dark plating motes flashes around the body.
        Location body = Fx.body(player());
        Vfx.sphere(ctx(), body, 0.65, 14, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.8f), false);
        Vfx.cloud(ctx(), body, Particle.WHITE_SMOKE, 4, 0.3, 0.02, null, false);
        Fx.sound(body, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.7f, 1.3f + Fx.jitter(0.1f));
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() && victim.equals(pendingTarget)) {
            landed = true;
        }
    }

    @Override
    public void onTick(long tick) {
        if (!blastJump.isArmed() || tick % 2 != 0) {
            return;
        }
        long now = ctx().currentTick();
        blastJump.update(player(), now);
        if (blastJump.isArmed() && now - blastJumpTick < 20) {
            // Rocket trail under the flying bomber.
            Location feet = player().getLocation();
            Vfx.dot(feet, Particle.DUST_COLOR_TRANSITION, FIRE.glow(1.0f), false);
            Vfx.dot(feet.clone().add(0, -0.2, 0), Particle.SMOKE, null, false);
            if (tick % 4 == 0) {
                Vfx.dot(feet, Particle.SMALL_FLAME, null, false);
            }
        }
    }

    /** Vanilla explosions, any explosion-typed damage, and the bombs of another 폭탄마. */
    private boolean isExplosive(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.BLOCK_EXPLOSION || cause == DamageCause.ENTITY_EXPLOSION) {
            return true;
        }
        Tag<DamageType> explosion = DamageTypeTags.IS_EXPLOSION;
        if (explosion != null && explosion.isTagged(event.getDamageSource().getDamageType())) {
            return true;
        }
        if (!ctx().isAbilityDamage()) {
            return false;
        }
        Player attacker = ctx().resolveAttacker(event.getDamageSource().getCausingEntity());
        return attacker != null && !attacker.equals(player()) && ctx().abilityOf(attacker) instanceof BomberAbility;
    }

    // ------------------------------------------------------------------ active: grenade

    @Override
    public ActivationResult onActive() {
        Location eye = player().getEyeLocation();
        World world = eye.getWorld();
        Vector look = eye.getDirection();
        Location spawn = eye.clone().add(look.clone().multiply(0.5)).subtract(0, 0.3, 0);
        if (!spawn.getBlock().isPassable()) {
            spawn = eye.clone().subtract(0, 0.3, 0);
        }
        long fuse = Math.max(1, params().ticks("grenade_fuse"));
        Vector velocity = Motion.clamp(look.clone().multiply(GRENADE_SPEED).add(new Vector(0, GRENADE_LIFT, 0)),
                MAX_SPEED);

        TNTPrimed grenade = ctx().track(player(), world.spawn(spawn, TNTPrimed.class, tnt -> {
            tnt.setPersistent(false);
            tnt.setFuseTicks((int) Math.min(Integer.MAX_VALUE - FUSE_MARGIN, fuse + FUSE_MARGIN));
            tnt.setYield(0f); // safety net: even a vanilla detonation would be harmless
            tnt.setIsIncendiary(false);
            tnt.setSource(player());
            tnt.setVelocity(velocity);
        }));
        if (!grenade.isValid()) {
            return ActivationResult.fail("수류탄을 던질 수 없습니다");
        }
        Fx.sound(eye, Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ENTITY_TNT_PRIMED, 0.9f, 1.3f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ITEM_FLINTANDSTEEL_USE, 0.8f, 1.2f + Fx.jitter(0.1f));
        Vfx.cloud(ctx(), spawn, Particle.SMALL_FLAME, 4, 0.08, 0.02, null, false);

        // A small red light rides on top of the grenade and blinks faster as the fuse burns down.
        BlockDisplay light = Displays.block(ctx(), player(), grenade.getLocation(),
                Material.REDSTONE_BLOCK.createBlockData(),
                Displays.transform(0, 0.16f, 0, LIGHT_SIZE, LIGHT_SIZE, LIGHT_SIZE, 45, 0, 0), fuse + FUSE_MARGIN);
        grenade.addPassenger(light);

        double radius = Math.max(0.5, params().num("grenade_radius"));
        Loop.repeat(ctx(), player(), 1, 1, fuse, tick -> {
            if (!grenade.isValid()) {
                return false;
            }
            long left = fuse - tick - 1;
            Location at = grenade.getLocation().add(0, 0.5, 0);
            fuseSparks(at.clone().add(0, 0.85, 0), tick); // just above the light
            long beepEvery = left > 15 ? 8 : left > 6 ? 4 : 2;
            boolean beep = left % beepEvery == 0;
            if (beep) {
                float progress = 1f - (float) left / fuse;
                Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.0f + progress);
            }
            blink(light, beep || left % beepEvery == 1, left <= WARNING_TICKS);
            if (left <= WARNING_TICKS && left % 2 == 0) {
                Location floor = groundBelow(at, 3);
                countdownRing(floor == null ? at : floor, radius, (double) left / WARNING_TICKS);
            }
            if (left <= 0) {
                grenade.remove();
                light.remove();
                detonate(at, radius, params().num("grenade_damage"), params().num("grenade_min_damage"), true,
                        target -> true);
                blastJump(at, radius);
                return false;
            }
            return true;
        }, () -> {
            if (grenade.isValid()) {
                grenade.remove();
            }
            if (light.isValid()) {
                light.remove();
            }
        });
        return ActivationResult.success();
    }

    /** A burning fuse: alternating sparks and a thin smoke thread. */
    private void fuseSparks(Location top, long tick) {
        Vfx.dot(top, tick % 2 == 0 ? Particle.SMALL_FLAME : Particle.ELECTRIC_SPARK, null, false);
        if (tick % 2 == 0) {
            Vfx.dot(top.clone().add(0, 0.1, 0), Particle.SMOKE, null, false);
        }
    }

    /** The grenade's light: bright while on, dim while off, outlined in red once the blast is imminent. */
    private static void blink(BlockDisplay light, boolean on, boolean urgent) {
        if (!light.isValid()) {
            return;
        }
        light.setBrightness(on ? Displays.FULL_BRIGHT : new Display.Brightness(3, 3));
        if (urgent && on) {
            Displays.outline(light, PALETTE.body());
        } else if (light.isGlowing()) {
            light.setGlowing(false);
        }
    }

    /** The blast radius on the ground, with an inner ring closing in on the grenade as the fuse runs out. */
    private void countdownRing(Location floor, double radius, double remaining) {
        Location at = floor.clone().add(0, 0.15, 0);
        int points = (int) Math.max(12, Math.min(40, radius * 7));
        double phase = ctx().currentTick() * 0.1;
        Vfx.ring(ctx(), at, radius, points, phase, Particle.DUST, WARNING_DUST, true);
        double inner = Math.max(0.2, radius * remaining);
        Vfx.ring(ctx(), at.clone().add(0, 0.05, 0), inner, (int) Math.max(6, points * remaining), -phase,
                Particle.DUST, WARNING_CORE, true);
    }

    /** Launches the owner away from their own grenade, without damage and without the landing damage. */
    private void blastJump(Location center, double radius) {
        Player self = player();
        if (!ctx().isAlive(self) || !self.getWorld().equals(center.getWorld())) {
            return;
        }
        double distance = distanceToBox(center, self.getBoundingBox());
        double reach = radius * BLAST_JUMP_REACH;
        if (distance > reach) {
            return;
        }
        double strength = 1 - distance / reach;
        Vector away = self.getLocation().toVector().subtract(center.toVector()).setY(0);
        if (away.lengthSquared() < 0.04) {
            away = self.getLocation().getDirection().setY(0);
        }
        if (away.lengthSquared() < 1.0E-4) {
            away = new Vector(0, 0, 0);
        } else {
            away.normalize().multiply(BLAST_JUMP_SIDE * (0.6 + 0.4 * strength));
        }
        away.setY(BLAST_JUMP_UP * (0.7 + 0.3 * strength));
        self.setVelocity(Motion.clamp(away, MAX_SPEED));
        self.setFallDistance(0f);
        blastJumpTick = ctx().currentTick();
        blastJump.arm(blastJumpTick, BLAST_JUMP_MAX_AIR);

        // Lift-off: a puff ring at the feet and a jet of flame and smoke pushing down.
        Location feet = self.getLocation();
        World world = feet.getWorld();
        Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 0.7, 14, 0, Particle.CLOUD, null, false);
        int jets = Vfx.n(ctx(), 8);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < jets; i++) {
            world.spawnParticle(i % 2 == 0 ? Particle.FLAME : Particle.LARGE_SMOKE, feet.getX(), feet.getY(),
                    feet.getZ(), 0, random.nextGaussian() * 0.2, -1, random.nextGaussian() * 0.2, 0.25, null, false);
        }
        Fx.sound(feet, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 0.9f + Fx.jitter(0.1f));
        Fx.sound(feet, Sound.ENTITY_BREEZE_WIND_BURST, 0.8f, 1.1f + Fx.jitter(0.1f));
        self.sendActionBar(Text.mm("<#FF7A2E>✸ 폭탄 점프!</#FF7A2E>"));
    }

    // ------------------------------------------------------------------ ultimate: carpet bombing

    @Override
    public ActivationResult onUltimate() {
        Location aim = aimedGround(params().num("carpet_range"));
        if (aim == null) {
            return ActivationResult.fail("조준한 곳이 너무 멀거나 허공입니다");
        }
        Vector forward = player().getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 1.0E-4) {
            double yaw = Math.toRadians(player().getLocation().getYaw());
            forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        forward.normalize();
        Vector heading = forward.clone();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());

        int bombs = Math.max(1, params().integer("carpet_bombs"));
        long duration = params().ticks("carpet_duration");
        double length = Math.max(0, params().num("carpet_length"));
        double radius = Math.max(0.5, params().num("carpet_radius"));
        double damage = params().num("carpet_damage");
        ThreadLocalRandom random = ThreadLocalRandom.current();

        List<Location> targets = new ArrayList<>(bombs);
        for (int i = 0; i < bombs; i++) {
            double along = bombs == 1 ? 0 : -length / 2 + length * i / (bombs - 1);
            double lateral = (i % 2 == 0 ? 1 : -1) * BOMB_LATERAL * (0.6 + 0.4 * random.nextDouble());
            Location point = aim.clone().add(forward.clone().multiply(along)).add(side.clone().multiply(lateral));
            Location landing = groundBelow(point.clone().add(0, 4, 0), 16);
            targets.add(landing == null ? point : landing);
        }
        long[] releaseAt = new long[bombs];
        for (int i = 0; i < bombs; i++) {
            releaseAt[i] = bombs == 1 ? 0 : Math.round((double) duration * i / (bombs - 1));
        }

        // A bomber flies the line and drops each bomb so that it drifts onto its target while falling. Under a
        // ceiling (or for an instant carpet) the bombs simply fall straight down.
        double speed = duration > 0 ? length / duration : 0;
        boolean flyover = duration >= PLANE_MIN_TICKS && speed > 0 && speed <= PLANE_MAX_SPEED && openSky(targets);
        Vector drift = flyover ? forward.clone().multiply(-speed * BOMB_FALL_TICKS) : new Vector();
        long runs = releaseAt[bombs - 1] + BOMB_FALL_TICKS + 2;
        Plane plane = flyover ? new Plane(aim, forward, side, -length / 2 - speed * BOMB_FALL_TICKS, speed,
                runs + PLANE_FADE_TICKS + 4) : null;

        bombingLine(aim, forward, length);
        Fx.sound(player().getEyeLocation(), Sound.ENTITY_TNT_PRIMED, 1.0f, 0.6f);
        Fx.sound(aim, Sound.BLOCK_BEACON_DEACTIVATE, 1.6f, 0.5f);
        Fx.sound(aim, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.6f, 0.5f);
        if (plane != null) {
            Fx.sound(aim, Sound.ENTITY_PHANTOM_SWOOP, 2.0f, 0.5f + Fx.jitter(0.05f));
        }

        // A target standing still (or frozen by an ally) must not eat every bomb of the carpet.
        Map<UUID, Integer> hits = new HashMap<>();
        int maxHits = Math.max(1, params().integer("carpet_max_hits"));
        List<FallingBomb> falling = new ArrayList<>();
        int[] released = {0};
        Loop.repeat(ctx(), player(), 0, 1, runs, tick -> {
            if (plane != null) {
                plane.fly(tick);
            }
            if (tick % 3 == 0 && released[0] < bombs) {
                bombingLine(aim, heading, length);
            }
            while (released[0] < bombs && releaseAt[released[0]] <= tick) {
                Location target = targets.get(released[0]++);
                falling.add(new FallingBomb(target, radius, drift));
            }
            for (Iterator<FallingBomb> it = falling.iterator(); it.hasNext(); ) {
                FallingBomb bomb = it.next();
                if (bomb.fall()) {
                    it.remove();
                    detonate(bomb.ground.clone().add(0, 0.5, 0), radius, damage, damage, false,
                            target -> hits.merge(target.getUniqueId(), 1, Integer::sum) <= maxHits);
                }
            }
            return released[0] < bombs || !falling.isEmpty();
        }, () -> {
            falling.forEach(FallingBomb::discard);
            if (plane != null) {
                plane.leave();
            }
        });
        return ActivationResult.success();
    }

    /** A dashed red line along the carpet: the bombing run's path, visible to everyone in the way. */
    private void bombingLine(Location aim, Vector forward, double length) {
        Location from = aim.clone().add(forward.clone().multiply(-length / 2)).add(0, 0.2, 0);
        Location to = aim.clone().add(forward.clone().multiply(length / 2)).add(0, 0.2, 0);
        Vfx.line(ctx(), from, to, 1.5, Particle.DUST, WARNING_DUST, true);
    }

    /** True if nothing blocks the sky above any of the targets up to the drop height. */
    private static boolean openSky(List<Location> targets) {
        for (Location target : targets) {
            RayTraceResult ceiling = target.getWorld().rayTraceBlocks(target.clone().add(0, 0.6, 0),
                    new Vector(0, 1, 0), BOMB_DROP_HEIGHT + 3, FluidCollisionMode.NEVER, true);
            if (ceiling != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * The carpet's bomber: a small dark plane of block displays flying the bombing line at drop height, trailing
     * contrails from its wingtips. It swoops in small, flies at constant speed and shrinks away after the run. The
     * plane's heading lives in the transformations of its parts; the displays themselves are never rotated.
     */
    private final class Plane {
        private final Location base;
        private final Vector forward;
        private final Vector side;
        private final double start;
        private final double speed;
        /** The flight direction as a Minecraft yaw (degrees), and the rotation that turns the plane's +Z onto it. */
        private final float yaw;
        private final Quaternionf heading;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private final List<Transformation> shapes = new ArrayList<>();
        private long lastTick;

        Plane(Location aim, Vector forward, Vector side, double start, double speed, long lifetime) {
            this.base = Displays.unrotated(aim).add(0, BOMB_DROP_HEIGHT + 0.6, 0);
            this.forward = forward.clone();
            this.side = side.clone();
            this.start = start;
            this.speed = speed;
            this.yaw = (float) Math.toDegrees(Math.atan2(-forward.getX(), forward.getZ()));
            this.heading = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
            Location at = position(0);
            // fuselage, wings, tail plane, tail fin, cockpit stripe (local +Z is the flight direction)
            add(at, Material.GRAY_CONCRETE, 0, 0, 0, 0.55f, 0.5f, 3.2f, lifetime);
            add(at, Material.LIGHT_GRAY_CONCRETE, 0, 0.02f, 0.25f, 4.8f, 0.12f, 1.0f, lifetime);
            add(at, Material.LIGHT_GRAY_CONCRETE, 0, 0.08f, -1.3f, 1.9f, 0.1f, 0.6f, lifetime);
            add(at, Material.RED_CONCRETE, 0, 0.5f, -1.35f, 0.1f, 0.85f, 0.7f, lifetime);
            add(at, Material.RED_CONCRETE, 0, 0.27f, 0.9f, 0.5f, 0.06f, 0.5f, lifetime);
            for (int i = 0; i < parts.size(); i++) {
                Displays.animateNextTick(ctx(), player(), parts.get(i), shapes.get(i), 4);
            }
        }

        private void add(Location at, Material material, float x, float y, float z, float sx, float sy, float sz,
                         long lifetime) {
            Transformation full = part(x, y, z, sx, sy, sz, 1);
            BlockDisplay part = Displays.block(ctx(), player(), at, material.createBlockData(),
                    part(x, y, z, sx, sy, sz, PLANE_SPAWN_SCALE), lifetime);
            part.setTeleportDuration(1);
            parts.add(part);
            shapes.add(full);
        }

        /** One part of the plane, given in the plane's frame (local +Z forward), scaled and turned to the heading. */
        private Transformation part(float x, float y, float z, float sx, float sy, float sz, float scale) {
            Vector3f offset = new Quaternionf(heading).transform(new Vector3f(x, y, z).mul(scale));
            return Displays.transform(offset.x(), offset.y(), offset.z(), sx * scale, sy * scale, sz * scale, yaw, 0,
                    0);
        }

        private Location position(long tick) {
            return base.clone().add(forward.clone().multiply(start + speed * tick));
        }

        void fly(long tick) {
            lastTick = tick;
            Location at = position(tick);
            for (BlockDisplay part : parts) {
                Displays.moveTo(part, at);
            }
            // Contrails from the wingtips and a hot exhaust behind the fuselage.
            Location tail = at.clone().subtract(forward.clone().multiply(1.9));
            Vfx.dot(tail.clone().add(side.clone().multiply(2.3)), Particle.CLOUD, null, true);
            Vfx.dot(tail.clone().add(side.clone().multiply(-2.3)), Particle.CLOUD, null, true);
            Vfx.dot(tail, tick % 2 == 0 ? Particle.SMOKE : Particle.SMALL_FLAME, null, true);
        }

        void leave() {
            long last = lastTick;
            Loop.repeat(ctx(), player(), 1, 1, PLANE_FADE_TICKS, step -> {
                fly(last + step + 1);
                return true;
            });
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), player(), part, PLANE_FADE_TICKS);
                }
            }
        }
    }

    /**
     * A glowing TNT block display falling onto a marked spot, spinning and trailing fuse sparks; dropped from a
     * moving plane it drifts forward onto the spot while it falls. A red ring closes in on the spot meanwhile.
     */
    private final class FallingBomb {
        private final Location ground;
        private final Location top;
        private final double radius;
        private final BlockDisplay display;
        private int age;
        private float yaw;

        FallingBomb(Location ground, double radius, Vector drift) {
            this.ground = ground;
            this.radius = radius;
            World world = ground.getWorld();
            RayTraceResult ceiling = world.rayTraceBlocks(ground.clone().add(0, 0.6, 0), new Vector(0, 1, 0),
                    BOMB_DROP_HEIGHT, FluidCollisionMode.NEVER, true);
            double height = ceiling == null ? BOMB_DROP_HEIGHT
                    : Math.max(0.5, ceiling.getHitPosition().getY() - ground.getY() - 1.0);
            this.top = ground.clone().add(drift).add(0, 0.4 + height, 0);
            this.yaw = ThreadLocalRandom.current().nextFloat() * 360f;
            this.display = Displays.block(ctx(), player(), top, Material.TNT.createBlockData(),
                    Displays.scaled(BOMB_SIZE), BOMB_FALL_TICKS + 4);
            // The bomb spins through the display's own yaw (its transformation has no rotation, so nothing doubles).
            display.setRotation(yaw, 0);
            display.setTeleportDuration(1);
            Displays.outline(display, PALETTE.body());
            targetRing(1);
            Fx.sound(top, Sound.ENTITY_TNT_PRIMED, 1.2f, 0.8f + Fx.jitter(0.1f));
        }

        /** Advances the fall by one tick; true once the bomb has hit the ground. */
        boolean fall() {
            age++;
            if (age >= BOMB_FALL_TICKS) {
                discard();
                return true;
            }
            double progress = (double) age / BOMB_FALL_TICKS;
            yaw += BOMB_SPIN_DEGREES;
            // Constant horizontal drift onto the spot, accelerating fall.
            Location at = new Location(ground.getWorld(),
                    top.getX() + (ground.getX() - top.getX()) * progress,
                    ground.getY() + 0.4 + (top.getY() - ground.getY() - 0.4) * (1 - progress * progress),
                    top.getZ() + (ground.getZ() - top.getZ()) * progress, yaw, 0);
            if (display.isValid()) {
                display.teleport(at);
            }
            Location fuse = at.clone().add(0, 0.5, 0);
            Vfx.dot(fuse, Particle.DUST_COLOR_TRANSITION, FIRE.glow(0.9f), true);
            Vfx.dot(fuse.clone().add(0, 0.15, 0), age % 2 == 0 ? Particle.SMALL_FLAME : Particle.SMOKE, null, false);
            if (age % 2 == 0) {
                targetRing(1 - progress);
            }
            return false;
        }

        /** The landing spot: the blast radius and an inner ring that closes as the bomb comes down. */
        private void targetRing(double remaining) {
            countdownRing(ground, radius, Math.max(0.1, remaining));
        }

        void discard() {
            if (display.isValid()) {
                display.remove();
            }
        }
    }

    // ------------------------------------------------------------------ explosions

    /**
     * An explicit, block-safe blast: visuals, then damage falling off linearly from {@code maxDamage} at the center
     * to {@code minDamage} at the edge for every exposed enemy in range that {@code admit} accepts, plus knockback.
     */
    private void detonate(Location center, double radius, double maxDamage, double minDamage, boolean big,
                          Predicate<LivingEntity> admit) {
        explosion(center, radius, big);
        for (LivingEntity target : ctx().enemiesNear(player(), center, radius)) {
            if (!exposed(center, target) || !admit.test(target)) {
                continue;
            }
            double distance = Math.min(radius, distanceToBox(center, target.getBoundingBox()));
            double proximity = 1 - distance / radius;
            // The blast only throws targets it actually hit (not one that dodged or blocked it).
            if (blastHit(target, minDamage + (maxDamage - minDamage) * proximity) && target.isValid()
                    && !target.isDead()) {
                knockback(center, target, proximity);
            }
        }
    }

    /**
     * The look and sound of a blast: flash, a fireball of nested glowing glass cubes that swells and collapses, a
     * fire bloom thrown outwards, ground debris, two expanding shock rings and embers smouldering afterwards.
     */
    private void explosion(Location center, double radius, boolean big) {
        boolean force = !big; // the small blasts are the carpet, the ultimate: seen from afar
        World world = center.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location floor = groundBelow(center, 2);
        Location ground = (floor == null ? center.clone().subtract(0, 0.5, 0) : floor).add(0, 0.12, 0);

        world.spawnParticle(Particle.FLASH, center, 1, 0, 0, 0, 0, PALETTE.core(), force);
        world.spawnParticle(big ? Particle.EXPLOSION_EMITTER : Particle.EXPLOSION, center, 1, 0, 0, 0, 0, null,
                force);
        fireball(center, radius * (big ? 0.5 : 0.45), big ? 3 : 2);

        // Fire bloom: flames thrown out in every direction, a few hot lava pops.
        int flames = Vfx.n(ctx(), big ? 26 : 14);
        for (int i = 0; i < flames; i++) {
            Vector direction = new Vector(random.nextGaussian(), Math.abs(random.nextGaussian()) * 0.6 + 0.15,
                    random.nextGaussian()).normalize();
            world.spawnParticle(i % 3 == 0 ? Particle.SMALL_FLAME : Particle.FLAME, center.getX(), center.getY(),
                    center.getZ(), 0, direction.getX(), direction.getY(), direction.getZ(),
                    0.12 + radius * 0.035 * random.nextDouble(0.6, 1.2), null, false);
        }
        Vfx.cloud(ctx(), center, Particle.LAVA, big ? 5 : 3, 0.3, 0, null, false);
        Vfx.cloud(ctx(), center, Particle.DUST_COLOR_TRANSITION, big ? 18 : 10, radius * 0.25, 0, FIRE.glow(1.6f),
                false);
        Vfx.cloud(ctx(), center.clone().add(0, 0.4, 0), Particle.LARGE_SMOKE, big ? 12 : 6, 0.45, 0.05, null, false);

        // Debris of the ground it went off on.
        Block below = ground.clone().subtract(0, 0.3, 0).getBlock();
        BlockData debris = below.getType().isSolid() ? below.getBlockData() : Material.DIRT.createBlockData();
        world.spawnParticle(Particle.BLOCK, ground.clone().add(0, 0.3, 0), Vfx.n(ctx(), big ? 24 : 12),
                radius * 0.25, 0.1, radius * 0.25, 0.35, debris, false);

        // Shock rings: a hot one racing to the edge, a sooty one following.
        Vfx.shockwave(ctx(), player(), ground, 0.3, radius, big ? 5 : 4, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(1.4f), force);
        Vfx.shockwave(ctx(), player(), ground.clone().add(0, 0.1, 0), 0.2, radius * 0.85, big ? 9 : 7, Particle.DUST,
                new Particle.DustOptions(PALETTE.edge(), 1.6f), false);
        embers(ground, radius * 0.5, big ? EMBER_TICKS : EMBER_TICKS / 2);

        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, big ? 2.2f : 1.6f, (big ? 0.95f : 1.1f) + Fx.jitter(0.08f));
        Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.2f, 0.6f + Fx.jitter(0.1f));
        Fx.sound(center, big ? Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST : Sound.ENTITY_DRAGON_FIREBALL_EXPLODE,
                big ? 1.4f : 0.7f, 0.7f + Fx.jitter(0.1f));
    }

    /** Nested glass cubes at odd angles that swell to {@code size} in a few ticks, then collapse. */
    private void fireball(Location center, double size, int layers) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location at = center.clone();
        at.setYaw(0);
        at.setPitch(0);
        for (int i = 0; i < layers; i++) {
            float scale = (float) (size * (0.55 + 0.45 * i / Math.max(1, layers - 1)));
            float yaw = random.nextFloat() * 90f;
            float pitch = random.nextFloat() * 90f;
            float roll = random.nextFloat() * 90f;
            BlockDisplay layer = Displays.block(ctx(), player(), at, FIREBALL[i % FIREBALL.length].createBlockData(),
                    Displays.transform(0, 0, 0, 0.1f, 0.1f, 0.1f, yaw, pitch, roll),
                    FIREBALL_GROW_TICKS + FIREBALL_FADE_TICKS + 2);
            Displays.animateNextTick(ctx(), player(), layer,
                    Displays.transform(0, 0, 0, scale, scale, scale, yaw + 30, pitch + 20, roll), FIREBALL_GROW_TICKS);
            ctx().runLater(player(), FIREBALL_GROW_TICKS + 1, () -> {
                if (layer.isValid()) {
                    Displays.shrinkAway(ctx(), player(), layer, FIREBALL_FADE_TICKS);
                }
            });
        }
    }

    /** The scorched ground smoulders: a few embers and smoke threads rising for a moment. */
    private void embers(Location ground, double spread, long ticks) {
        Loop.repeat(ctx(), player(), 2, 2, Math.max(1, ticks / 2), step -> {
            Location spot = Vfx.scatter(ground, spread);
            Vfx.dot(spot, step % 2 == 0 ? Particle.SMALL_FLAME : Particle.SMOKE, null, false);
            Vfx.dot(Vfx.scatter(ground, spread), Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.8f), false);
            return true;
        });
    }

    /**
     * Deals blast damage and tells whether it landed. Without damage there is nothing to dodge, so the blast still
     * throws a valid enemy.
     */
    private boolean blastHit(LivingEntity target, double damage) {
        if (!(damage > 0)) {
            return Combat.isTargetable(ctx(), player(), target);
        }
        pendingTarget = target;
        landed = false;
        try {
            Combat.hit(ctx(), player(), target, damage);
        } finally {
            pendingTarget = null;
        }
        return landed;
    }

    private void knockback(Location center, LivingEntity target, double proximity) {
        Vector away = target.getLocation().toVector().subtract(center.toVector()).setY(0);
        if (away.lengthSquared() < 1.0E-4) {
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            away = new Vector(Math.cos(angle), 0, Math.sin(angle));
        }
        double factor = Motion.knockbackFactor(target);
        away.normalize().multiply(BLAST_PUSH * (0.4 + 0.6 * proximity) * factor);
        away.setY(BLAST_LIFT * (0.5 + 0.5 * proximity) * factor);
        // Replaces the velocity: a player's velocity on the server is stale, never add to it.
        target.setVelocity(Motion.clamp(away, MAX_SPEED));
    }

    /** True if no solid block stands between the blast and the target's eyes or body center. */
    private static boolean exposed(Location center, LivingEntity target) {
        return Spots.clearPath(center, target.getEyeLocation())
                || Spots.clearPath(center, target.getLocation().add(0, target.getHeight() * 0.5, 0));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The ground point the owner aims at within {@code range}: below an aimed enemy's feet (a jumping enemy marks
     * the ground under it, not a spot in mid-air), else below the aimed block.
     */
    private @Nullable Location aimedGround(double range) {
        LivingEntity target = ctx().findTarget(player(), range);
        if (target != null) {
            Location feet = target.getLocation();
            Location ground = groundBelow(feet, 8);
            return ground == null ? feet : ground;
        }
        Location eye = player().getEyeLocation();
        World world = eye.getWorld();
        RayTraceResult ray = world.rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        if (ray == null) {
            return null;
        }
        Location point = ray.getHitPosition().toLocation(world);
        BlockFace face = ray.getHitBlockFace();
        if (face != null) {
            point.add(face.getDirection().multiply(0.3));
        }
        Location ground = groundBelow(point, 8);
        return ground == null ? point : ground;
    }

    /**
     * The first solid surface at most {@code maxDrop} blocks below {@code point}, null if there is none. The ray
     * starts a little above the point: a ray that starts on a block's face (a point on the ground) reports its hit
     * inside the block.
     */
    private static @Nullable Location groundBelow(Location point, double maxDrop) {
        World world = point.getWorld();
        Location from = point.clone().add(0, GROUND_PROBE_LIFT, 0);
        RayTraceResult down = world.rayTraceBlocks(from, new Vector(0, -1, 0), maxDrop + GROUND_PROBE_LIFT,
                FluidCollisionMode.NEVER, true);
        return down == null ? null : down.getHitPosition().toLocation(world);
    }

    /** Distance from a point to the closest point of a box (0 inside), matching how enemies are found. */
    private static double distanceToBox(Location point, BoundingBox box) {
        double dx = Math.max(Math.max(box.getMinX() - point.getX(), 0), point.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - point.getY(), 0), point.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - point.getZ(), 0), point.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
