package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 공학자 — builds sentries. Standing near one of its turrets hardens the engineer, the active throws down an automatic
 * turret that peppers the nearest visible enemy, and the ultimate deploys a laser turret whose piercing beam sweeps
 * after enemies and burns everything it touches.
 *
 * <p>Turrets are display entities only (no blocks, nothing to break): they cannot be destroyed, so their counterplay
 * is range, line of sight and time. Like summons they ignore enemies hidden from the engineer (stealth).
 *
 * <p>Look: a deploy kit tumbles to the spot and unfolds into an anvil-legged dispenser turret with a black barrel,
 * an orange muzzle tip and a status light; every shot kicks the barrel back, flashes the light and draws an orange
 * tracer. The laser turret stands on an iron plate with a copper pylon, two capacitor coils spinning around it and an
 * observer head with an orange lens; its beam is a white-hot core in an orange sheath with pulses running along it.
 * A countdown floats over every turret, and the guard zone is traced on the ground.
 */
public final class EngineerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("engineer", "공학자", Material.DISPENSER)
            .color(TextColor.color(0xFF8C1A))
            .difficulty(2)
            .passive("방어 설비",
                    "내 포탑에서 {guard_radius}블록 안에 있으면 받는 피해가 {guard_reduction}% 줄어듭니다.")
            .active("포탑 설치",
                    "조준한 바닥({deploy_range}블록 이내)에 {turret_duration}초간 자동 포탑을 설치합니다. 포탑은 "
                            + "{turret_range}블록 안의 가장 가까운 보이는 적에게 {turret_interval}초마다 {turret_damage} 피해를 "
                            + "주는 탄을 쏩니다. 포탑은 하나만 유지됩니다.",
                    15)
            .ultimate("레이저 포탑",
                    "조준한 바닥에 {laser_duration}초간 레이저 포탑을 설치합니다. {laser_charge}초 충전 후 {laser_range}블록 "
                            + "안의 가장 가까운 적을 따라 움직이는 관통 레이저를 쏴, 광선에 닿은 모든 적에게 "
                            + "{laser_interval}초마다 {laser_damage} 피해를 줍니다.")
            .param("guard_radius", 6)
            .param("guard_reduction", 20)
            .param("deploy_range", 6)
            .param("turret_duration", 8)
            .param("turret_range", 12)
            .param("turret_interval", 0.8)
            .param("turret_damage", 1)
            .param("laser_duration", 8)
            .param("laser_charge", 0.75)
            .param("laser_range", 16)
            .param("laser_interval", 0.25)
            .param("laser_damage", 0.5)
            .build();

    private static final String COLOR_TAG = "#FF8C1A";
    /** Warm light core, safety orange body, gunmetal edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE0B2, 0xFF8C1A, 0x4A5560);
    /** The laser: white-hot core, molten orange, scorched red. */
    private static final Vfx.Palette LASER = Vfx.Palette.of(0xFFFFFF, 0xFFA040, 0xB33A00);
    private static final Particle.DustOptions STEEL = new Particle.DustOptions(Color.fromRGB(0x9AA5B1), 0.9f);
    private static final Particle.DustOptions ORANGE = PALETTE.dust(0.8f);
    private static final Particle.DustOptions GUARD_DASH = new Particle.DustOptions(PALETTE.body(), 0.7f);
    private static final Particle.DustTransition SPARK = PALETTE.glow(0.8f);
    private static final Particle.DustTransition HEAT = LASER.glow(1.0f);
    private static final Particle.DustTransition SCORCH = LASER.fade(0.8f);

    private static final BlockData STAND = Material.ANVIL.createBlockData();
    private static final BlockData GUN_HEAD = Material.DISPENSER.createBlockData();
    private static final BlockData BARREL = Material.BLACK_CONCRETE.createBlockData();
    private static final BlockData MUZZLE_TIP = Material.ORANGE_CONCRETE.createBlockData();
    private static final BlockData STATUS_LIGHT = Material.SHROOMLIGHT.createBlockData();
    private static final BlockData PLATE = Material.IRON_BLOCK.createBlockData();
    private static final BlockData PYLON = Material.WAXED_CUT_COPPER.createBlockData();
    private static final BlockData LASER_HEAD = Material.OBSERVER.createBlockData();
    private static final BlockData LENS = Material.ORANGE_STAINED_GLASS.createBlockData();
    private static final BlockData COIL = Material.WAXED_COPPER_BLOCK.createBlockData();
    private static final BlockData BEAM_CORE = Material.WHITE_CONCRETE.createBlockData();
    private static final BlockData BEAM_SHEATH = Material.ORANGE_STAINED_GLASS.createBlockData();
    private static final BlockData GUARD_PANEL = Material.ORANGE_STAINED_GLASS.createBlockData();
    private static final long GUARD_PANEL_TICKS = 4;

    /** A dispenser's front faces north (local -z): its head part is turned around to face the target. */
    private static final Quaternionf FACE_FORWARD_FROM_NORTH = new Quaternionf().rotateY((float) Math.PI);

    // placement
    private static final int GROUND_SNAP = 6;
    private static final double TURRET_WIDTH = 0.9;
    private static final double TURRET_HEIGHT = 1.2;
    private static final int THROW_MIN_TICKS = 3;
    private static final int THROW_MAX_TICKS = 8;
    private static final double THROW_SPEED = 1.1;

    // gun turret
    private static final double GUN_STAND = 0.6;
    private static final float GUN_HEAD_SIZE = 0.5f;
    private static final int UNFOLD_TICKS = 8;
    private static final long GUN_WARMUP = 10;
    private static final double SHOT_PUSH = 0.12;
    /** Head turning: degrees per tick while tracking, and the idle scan sweep. */
    private static final double GUN_TURN = 24;
    private static final double SCAN_SWEEP = 50;

    // laser turret
    private static final double LASER_PLATE = 0.2;
    private static final double LASER_PYLON = 0.75;
    private static final float LASER_HEAD_SIZE = 0.6f;
    /**
     * Radians per tick the beam can swing towards its target (about 140° a second): a sprint outruns it within two
     * blocks of the turret, and a new target is reached in a visible sweep that burns whoever it crosses.
     */
    private static final double BEAM_TURN = 0.12;
    private static final float BEAM_CORE_WIDTH = 0.06f;
    private static final float BEAM_SHEATH_WIDTH = 0.17f;
    /** Enemies within this distance of the beam's axis are burned. */
    private static final double BEAM_REACH = 0.2;
    private static final double COIL_ORBIT = 0.42;
    /** Radians per tick the idle laser head pans while looking for a target. */
    private static final double IDLE_PAN = 0.04;
    private static final int POSE_PERIOD = 2;

    private static final int AFTERGLOW_TICKS = 14;

    /** The engineer's turrets standing right now (at most one of each kind). */
    private @Nullable GunTurret gun;
    private @Nullable LaserTurret laser;
    private long lastGuardFx = -100;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        // The displays are tracked and removed with the ability; only the bookkeeping is dropped here.
        gun = null;
        laser = null;
    }

    // ------------------------------------------------------------------ passive: fortified position

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0 || isUnblockable(event)) {
            return;
        }
        Turret guard = guardingTurret();
        if (guard == null) {
            return;
        }
        double reduction = Math.clamp(params().num("guard_reduction") / 100.0, 0.0, 1.0);
        if (reduction <= 0) {
            return;
        }
        event.setDamage(event.getDamage() * (1 - reduction));
        long now = ctx().currentTick();
        if (now - lastGuardFx >= 8) {
            lastGuardFx = now;
            guardFx(guard, event);
        }
    }

    @Override
    public void onTick(long tick) {
        if (tick % 20 != 0) {
            return;
        }
        // A faint orange ring at the engineer's feet while a turret covers it.
        if (guardingTurret() != null) {
            Vfx.ring(ctx(), player().getLocation().add(0, 0.08, 0), 0.6, 10, tick * 0.05, Particle.DUST, ORANGE,
                    false);
        }
    }

    /** The closest standing turret within {@code guard_radius} of the engineer, null if none. */
    private @Nullable Turret guardingTurret() {
        double radius = params().num("guard_radius");
        Location at = player().getLocation();
        Turret best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Turret turret : new Turret[] {gun, laser}) {
            if (turret == null || !turret.standing() || !turret.base.getWorld().equals(at.getWorld())) {
                continue;
            }
            double distance = turret.base.distance(at);
            if (distance <= radius && distance < bestDistance) {
                best = turret;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * The turret shields the engineer: a spark line jumps from the turret, and an orange energy panel in a steel
     * frame flashes up between the engineer and the hit.
     */
    private void guardFx(Turret turret, EntityDamageEvent event) {
        Player owner = player();
        Location body = Fx.body(owner);
        Vfx.trail(turret.pivot, body, PALETTE.body(), 5, false);
        Vfx.line(ctx(), turret.pivot, body, 1.5, Particle.DUST_COLOR_TRANSITION, SPARK, false);
        Vector toward = Motion.horizontalFacing(owner.getLocation());
        if (event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager().getWorld().equals(owner.getWorld())) {
            Vector incoming = Motion.horizontal(byEntity.getDamager().getLocation().toVector()
                    .subtract(body.toVector()));
            if (incoming.lengthSquared() > 1.0E-6) {
                toward = incoming;
            }
        }
        Location at = body.clone().add(toward.clone().multiply(owner.getWidth() * 0.5 + 0.4));
        at.setYaw(0);
        at.setPitch(0);
        float yaw = (float) yawOf(toward);
        BlockDisplay panel = Displays.block(ctx(), owner, at, GUARD_PANEL,
                Displays.transform(0, 0, 0, 0.5f, 0.6f, 0.03f, yaw, 0, 0), GUARD_PANEL_TICKS + 6);
        Displays.animateNextTick(ctx(), owner, panel, Displays.transform(0, 0, 0, 0.95f, 1.15f, 0.03f, yaw, 0, 0), 2);
        Displays.outline(panel, PALETTE.body());
        ctx().runLater(owner, GUARD_PANEL_TICKS, () -> {
            if (panel.isValid()) {
                Displays.shrinkAway(ctx(), owner, panel, 3);
            }
        });
        Vfx.cloud(ctx(), at, Particle.WAX_ON, 6, 0.3, 0.05, null, false);
        Vfx.cloud(ctx(), at, Particle.ELECTRIC_SPARK, 4, 0.25, 0.08, null, false);
        Fx.sound(body, Sound.BLOCK_METAL_HIT, 0.7f, 1.4f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.4f, 1.8f + Fx.jitter(0.1f));
    }

    private static boolean isUnblockable(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        return cause == DamageCause.VOID || cause == DamageCause.KILL || cause == DamageCause.WORLD_BORDER
                || cause == DamageCause.SUICIDE;
    }

    // ------------------------------------------------------------------ active: deploy turret

    @Override
    public ActivationResult onActive() {
        Location spot = deploySpot();
        if (spot == null) {
            return ActivationResult.fail("포탑을 설치할 바닥이 없습니다");
        }
        if (gun != null) {
            gun.dismantle(); // one turret at a time, even one still in the air
        }
        GunTurret turret = new GunTurret(spot, Math.max(1, params().ticks("turret_duration")));
        gun = turret;
        throwKit(spot, Material.DISPENSER, turret::assemble);
        return ActivationResult.success();
    }

    /**
     * Where a turret goes: the floor the engineer aims at within {@code deploy_range} (the floor below the end of the
     * aim when it ends in the air, or in front of a wall it hits), else the engineer's own feet. The spot must be on
     * ground, inside the arena and free for the turret's body. Null if none fits.
     */
    private @Nullable Location deploySpot() {
        Player owner = player();
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        double range = Math.max(1, params().num("deploy_range"));
        RayTraceResult hit = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        Location aimed = hit == null
                ? eye.clone().add(direction.clone().multiply(range))
                : hit.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.45)).add(0, 0.05, 0);
        for (Location candidate : new Location[] {aimed, owner.getLocation()}) {
            Location spot = onGround(candidate);
            if (spot != null) {
                return spot;
            }
        }
        return null;
    }

    private @Nullable Location onGround(Location point) {
        World world = point.getWorld();
        Double ground = groundBelow(point);
        if (ground == null) {
            return null;
        }
        Location spot = new Location(world, point.getX(), ground, point.getZ());
        if (!ctx().isInsideArena(spot) || !Spots.isFree(world, spot, TURRET_WIDTH, TURRET_HEIGHT)) {
            return null;
        }
        return spot;
    }

    /** The top of the first solid block at most {@link #GROUND_SNAP} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point) {
        World world = point.getWorld();
        int start = Math.min(point.getBlockY(), world.getMaxHeight() - 1);
        int end = Math.max(world.getMinHeight(), point.getBlockY() - GROUND_SNAP);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(point.getBlockX(), y, point.getBlockZ());
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /**
     * The engineer tosses a tumbling kit to the spot in a short arc; {@code landed} runs when it touches down (only if
     * the engineer is still alive, as everything owner-scoped).
     */
    private void throwKit(Location spot, Material kit, Runnable landed) {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Location start = eye.clone().add(eye.getDirection().multiply(0.6)).subtract(0, 0.35, 0);
        Location end = spot.clone().add(0, 0.3, 0);
        Vector path = end.toVector().subtract(start.toVector());
        double distance = path.length();
        int flight = Math.clamp((long) Math.ceil(distance / THROW_SPEED), THROW_MIN_TICKS, THROW_MAX_TICKS);
        double arc = Math.min(1.6, 0.4 + distance * 0.15);
        ItemDisplay item = Displays.item(ctx(), owner, start, ItemStack.of(kit),
                Displays.centered(0, 0, 0, 0.45f, 0.45f, 0.45f, 0, 0, 0), flight + 2L);
        item.setTeleportDuration(1);
        Fx.sound(eye, Sound.ENTITY_WITCH_THROW, 0.8f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ITEM_ARMOR_EQUIP_IRON, 0.7f, 1.2f + Fx.jitter(0.1f));
        Location marker = spot.clone().add(0, 0.06, 0);
        Loop.repeat(ctx(), owner, 1, 1, flight, run -> {
            double t = (double) (run + 1) / flight;
            Location at = start.clone().add(path.clone().multiply(t)).add(0, 4 * arc * t * (1 - t), 0);
            if (item.isValid()) {
                // Not the engineer's rotation (the arc starts at its eyes): the kit tumbles by its pose alone.
                Displays.moveTo(item, at);
                Displays.animate(item, Displays.centered(0, 0, 0, 0.45f, 0.45f, 0.45f, run * 30f, run * 45f, 0), 1);
            }
            Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, SPARK, false);
            // Telegraph: the footprint of the turret, closing in as the kit comes down.
            Vfx.ring(ctx(), marker, 0.9 - 0.4 * t, 10, run * 0.4, Particle.DUST, ORANGE, false);
            return true;
        }, () -> {
            if (item.isValid()) {
                item.remove();
            }
            landed.run();
        });
    }

    // ------------------------------------------------------------------ turrets

    /** What every turret shares: its spot, its parts, its countdown label and the target choice. */
    private abstract class Turret implements LongPredicate {

        final Location base;
        final Location pivot;
        final long life;
        final List<Display> parts = new ArrayList<>();
        @Nullable TextDisplay timer;
        @Nullable LivingEntity target;
        boolean built;
        boolean gone;

        Turret(Location base, double pivotHeight, long life) {
            this.base = base.clone();
            this.base.setYaw(0);
            this.base.setPitch(0);
            this.pivot = this.base.clone().add(0, pivotHeight, 0);
            this.life = life;
        }

        /** Standing: assembled and not yet dismantled (its parts still exist). */
        boolean standing() {
            return built && !gone && !parts.isEmpty() && parts.getFirst().isValid();
        }

        <T extends Display> T keep(T display) {
            parts.add(display);
            return display;
        }

        BlockDisplay part(Location at, BlockData data, Transformation pose) {
            return keep(Displays.block(ctx(), player(), at, data, pose, life + 20));
        }

        void spawnTimer(double height) {
            timer = Displays.text(ctx(), player(), base.clone().add(0, height, 0), label(life), 0.5f, life + 20);
            parts.add(timer);
        }

        void updateTimer(long left) {
            if (timer != null && timer.isValid() && left % 20 == 0) {
                timer.text(label(left));
            }
        }

        private Component label(long ticksLeft) {
            return Text.mm("<" + COLOR_TAG + ">⚙</" + COLOR_TAG + "> <white>" + Fx.seconds(ticksLeft) + "</white>");
        }

        /**
         * The enemy to shoot from {@code from}: the current one while it stays valid, in range, visible to the
         * engineer and in the clear, else the nearest such enemy. Null if none.
         */
        @Nullable LivingEntity pickTarget(Location from, double range) {
            if (target != null && canShoot(target, from, range)) {
                return target;
            }
            for (LivingEntity enemy : ctx().enemiesNear(player(), from, range)) {
                if (canShoot(enemy, from, range)) {
                    return enemy;
                }
            }
            return null;
        }

        private boolean canShoot(LivingEntity enemy, Location from, double range) {
            if (!Combat.isTargetable(ctx(), player(), enemy) || !enemy.getWorld().equals(from.getWorld())) {
                return false;
            }
            if (enemy instanceof Player hidden && !player().canSee(hidden)) {
                return false; // stealth hides it from the engineer's turrets as well
            }
            Location body = Fx.body(enemy);
            return body.distance(from) <= range + enemy.getWidth() && Spots.clearPath(from, body);
        }

        /** The guard zone, traced as a slow dashed ring around the turret. */
        void drawGuard(long tick) {
            if (tick % 20 != 5) {
                return;
            }
            double radius = params().num("guard_radius");
            Vfx.ring(ctx(), base.clone().add(0, 0.1, 0), radius, (int) Math.max(12, Math.min(36, radius * 4)),
                    tick * 0.01, Particle.DUST, GUARD_DASH, false);
        }

        /** Takes the turret down: the parts sink and shrink away in a puff of smoke and sparks. */
        void dismantle() {
            if (gone) {
                return;
            }
            gone = true;
            if (!built) {
                return; // superseded while the kit was still in the air
            }
            Player owner = player();
            for (Display display : parts) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 6);
                }
            }
            Vfx.cloud(ctx(), pivot, Particle.LARGE_SMOKE, 6, 0.25, 0.02, null, false);
            Vfx.cloud(ctx(), pivot, Particle.ELECTRIC_SPARK, 10, 0.3, 0.15, null, false);
            Vfx.cloud(ctx(), pivot, Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, SPARK, false);
            Vfx.cloud(ctx(), pivot, Particle.DUST, 8, 0.4, 0, STEEL, false); // loose bolts and plates
            Fx.sound(pivot, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(pivot, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(pivot, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.4f, 1.6f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 2, 2, AFTERGLOW_TICKS / 2, run -> {
                Vfx.dot(Vfx.scatter(base, 0.5).add(0, 0.2 + run * 0.12, 0), Particle.DUST_COLOR_TRANSITION, SCORCH,
                        false);
                return true;
            });
        }

        /** The turret's lifetime ran out (its loop ended by itself). */
        void expire() {
            dismantle();
            if (gun == this) {
                gun = null;
            }
            if (laser == this) {
                laser = null;
            }
        }
    }

    /**
     * The automatic turret: an anvil stand with a dispenser head that swivels to the nearest visible enemy and fires
     * a tracer every {@code turret_interval}, scanning left and right while it has nothing to shoot.
     */
    private final class GunTurret extends Turret {

        private final long interval;
        private final double range;
        private final double damage;
        private double yaw;
        private double pitch;
        private double restYaw;
        private @Nullable BlockDisplay head;
        private @Nullable BlockDisplay barrel;
        private @Nullable BlockDisplay tip;
        private @Nullable BlockDisplay light;

        GunTurret(Location base, long life) {
            super(base, GUN_STAND + GUN_HEAD_SIZE / 2.0, life);
            this.interval = Math.max(1, params().ticks("turret_interval"));
            this.range = Math.max(1, params().num("turret_range"));
            this.damage = params().num("turret_damage");
            this.restYaw = player().getLocation().getYaw();
            this.yaw = restYaw;
        }

        /** The kit landed: the turret unfolds out of the ground and comes online. */
        void assemble() {
            if (gone) {
                return;
            }
            Player owner = player();
            Location stand = base.clone().add(0, GUN_STAND / 2, 0);
            BlockDisplay legs = part(stand, STAND, Displays.transform(0, 0, 0, 0.6f, 0.02f, 0.6f, 0, 0, 0));
            Displays.animateNextTick(ctx(), owner, legs, Displays.transform(0, 0, 0, 0.6f, (float) GUN_STAND, 0.6f,
                    0, 0, 0), UNFOLD_TICKS / 2);
            head = part(pivot, GUN_HEAD, headPart(FACE_FORWARD_FROM_NORTH, 0, 0, 0, 0.02f, 0.02f, 0.02f));
            barrel = part(pivot, BARREL, headPart(null, 0, 0, 0.2f, 0.02f, 0.02f, 0.02f));
            tip = part(pivot, MUZZLE_TIP, headPart(null, 0, 0, 0.2f, 0.02f, 0.02f, 0.02f));
            light = part(pivot, STATUS_LIGHT, headPart(null, 0, 0, 0, 0.02f, 0.02f, 0.02f));
            spawnTimer(GUN_STAND + GUN_HEAD_SIZE + 0.45);
            ctx().runLater(owner, UNFOLD_TICKS / 2, () -> pose(UNFOLD_TICKS / 2, 0));
            built = true;

            Location ground = base.clone().add(0, 0.05, 0);
            Vfx.shockwave(ctx(), owner, ground, 0.3, 1.4, 5, Particle.DUST_COLOR_TRANSITION, SPARK, false);
            Vfx.ring(ctx(), ground, range, (int) Math.max(16, Math.min(48, range * 3)), 0, Particle.DUST, GUARD_DASH,
                    false);
            Vfx.cloud(ctx(), stand, Particle.ELECTRIC_SPARK, 12, 0.3, 0.2, null, false);
            Vfx.cloud(ctx(), ground, Particle.WAX_ON, 8, 0.4, 0.1, null, false);
            Fx.sound(stand, Sound.BLOCK_ANVIL_PLACE, 0.6f, 1.5f + Fx.jitter(0.05f));
            Fx.sound(stand, Sound.BLOCK_PISTON_EXTEND, 0.8f, 1.2f + Fx.jitter(0.1f));
            Fx.sound(stand, Sound.ENTITY_IRON_GOLEM_REPAIR, 0.8f, 1.4f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 1, 1, life, this, this::expire);
        }

        @Override
        public boolean test(long tick) {
            if (gone || !standing()) {
                return false;
            }
            long left = life - tick;
            updateTimer(left);
            drawGuard(tick);
            if (tick < GUN_WARMUP) {
                return true;
            }
            Location muzzle = muzzle();
            LivingEntity next = pickTarget(muzzle, range);
            if (next != target) {
                target = next;
                if (next != null) {
                    lockOn(next);
                }
            }
            if (target == null) {
                // Idle scan: the head sweeps left and right around where it was set down.
                turnTowards(restYaw + Math.sin(tick * 0.07) * SCAN_SWEEP, 0, 3);
                return true;
            }
            Vector aim = Fx.body(target).toVector().subtract(pivot.toVector());
            turnTowards(yawOf(aim), pitchOf(aim), 2);
            restYaw = yaw;
            if ((tick - GUN_WARMUP) % interval == 0) {
                fire(target);
            }
            return true;
        }

        private void lockOn(LivingEntity enemy) {
            Fx.sound(pivot, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 1.6f);
            Vfx.ring(ctx(), Fx.body(enemy), 0.7, 12, 0, Particle.DUST_COLOR_TRANSITION, SPARK, false);
        }

        /** Turns the head towards a direction, at most {@link #GUN_TURN} degrees per tick. */
        private void turnTowards(double targetYaw, double targetPitch, int ticks) {
            double deltaYaw = wrap(targetYaw - yaw);
            double step = GUN_TURN * ticks;
            double newYaw = yaw + Math.clamp(deltaYaw, -step, step);
            double newPitch = pitch + Math.clamp(targetPitch - pitch, -step, step);
            if (Math.abs(newYaw - yaw) < 0.5 && Math.abs(newPitch - pitch) < 0.5) {
                return;
            }
            yaw = newYaw;
            pitch = newPitch;
            pose(ticks, 0);
        }

        /** Poses the head parts at the current facing; {@code kick} pulls the barrel back (recoil). */
        private void pose(int ticks, float kick) {
            Quaternionf facing = facing(yaw, pitch);
            float h = GUN_HEAD_SIZE;
            animateHead(head, facing, FACE_FORWARD_FROM_NORTH, 0, 0, 0, h, h, h, ticks);
            animateHead(barrel, facing, null, 0, -0.02f, 0.42f - kick, 0.13f, 0.13f, 0.42f, ticks);
            animateHead(tip, facing, null, 0, -0.02f, 0.64f - kick, 0.17f, 0.17f, 0.05f, ticks);
            animateHead(light, facing, null, 0, h / 2 + 0.05f, -0.1f, 0.13f, 0.1f, 0.13f, ticks);
        }

        private void animateHead(@Nullable BlockDisplay display, Quaternionf facing, @Nullable Quaternionf local,
                                 float cx, float cy, float cz, float sx, float sy, float sz, int ticks) {
            if (display != null && display.isValid()) {
                Displays.animate(display, framed(facing, local, cx, cy, cz, sx, sy, sz), ticks);
            }
        }

        private Location muzzle() {
            Vector forward = facingVector(yaw, pitch);
            return pivot.clone().add(forward.multiply(0.68)).subtract(0, 0.02, 0);
        }

        private void fire(LivingEntity enemy) {
            Location muzzle = muzzle();
            Location body = Fx.body(enemy);
            // Recoil: the barrel kicks back and slides forward again.
            pose(1, 0.12f);
            ctx().runLater(player(), 2, () -> {
                if (standing()) {
                    pose(3, 0);
                }
            });
            if (light != null && light.isValid()) {
                light.setGlowColorOverride(PALETTE.body());
                light.setGlowing(true);
                BlockDisplay lamp = light;
                ctx().runLater(player(), 3, () -> {
                    if (lamp.isValid()) {
                        lamp.setGlowing(false);
                    }
                });
            }
            World world = muzzle.getWorld();
            Vfx.cloud(ctx(), muzzle, Particle.SMALL_FLAME, 3, 0.04, 0.02, null, false);
            Vfx.cloud(ctx(), muzzle, Particle.WAX_ON, 3, 0.05, 0.1, null, false);
            Vfx.cloud(ctx(), muzzle, Particle.SMOKE, 2, 0.04, 0.01, null, false);
            Vfx.trail(muzzle, body, PALETTE.body(), 3, false);
            Vfx.trail(muzzle, body, PALETTE.core(), 2, false);
            Fx.sound(muzzle, Sound.BLOCK_DISPENSER_DISPENSE, 0.8f, 1.6f + Fx.jitter(0.1f));
            Fx.sound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.35f, 1.9f + Fx.jitter(0.05f));

            Combat.hit(ctx(), player(), enemy, damage);
            Vfx.cloud(ctx(), body, Particle.CRIT, 5, 0.15, 0.2, null, false);
            Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 4, 0.15, 0, SPARK, false);
            world.spawnParticle(Particle.ELECTRIC_SPARK, body, Vfx.n(ctx(), 2), 0.1, 0.1, 0.1, 0.05);
            Fx.sound(body, Sound.ENTITY_ARROW_HIT, 0.5f, 1.6f + Fx.jitter(0.15f));
            if (Combat.isTargetable(ctx(), player(), enemy)) {
                Vector push = Motion.horizontal(body.toVector().subtract(muzzle.toVector())).multiply(SHOT_PUSH);
                if (!(enemy instanceof Player)) {
                    Motion.push(enemy, enemy.getVelocity().add(push), 1.0);
                } else if (Motion.isGrounded(enemy)) {
                    // A player's server-side velocity is only the last one sent to it: adding to it would pile every
                    // shot's nudge onto the previous ones. A player in mid-air is left alone (its fall is unknown).
                    Motion.push(enemy, push, 1.0);
                }
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: laser turret

    @Override
    public ActivationResult onUltimate() {
        Location spot = deploySpot();
        if (spot == null) {
            return ActivationResult.fail("포탑을 설치할 바닥이 없습니다");
        }
        if (laser != null) {
            laser.dismantle();
        }
        LaserTurret turret = new LaserTurret(spot, Math.max(1, params().ticks("laser_duration")));
        laser = turret;
        throwKit(spot, Material.OBSERVER, turret::assemble);
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">⚡ 레이저 포탑 전개!</" + COLOR_TAG + ">"));
        return ActivationResult.success();
    }

    /**
     * The laser turret: charges for {@code laser_charge}, then keeps a piercing beam on the nearest visible enemy.
     * The beam swings at a limited speed, burns every enemy along it every {@code laser_interval} and powers down
     * while there is nothing to shoot.
     */
    private final class LaserTurret extends Turret {

        private final long charge;
        private final long interval;
        private final double range;
        private final double damage;
        private Vector beamDirection;
        private @Nullable BlockDisplay head;
        private @Nullable BlockDisplay lens;
        private final BlockDisplay[] coils = new BlockDisplay[2];
        private @Nullable BlockDisplay core;
        private @Nullable BlockDisplay sheath;
        private boolean beamOn;
        private long lastHum = -100;

        LaserTurret(Location base, long life) {
            super(base, LASER_PLATE + LASER_PYLON + LASER_HEAD_SIZE / 2.0, life);
            this.charge = params().ticks("laser_charge");
            this.interval = Math.max(1, params().ticks("laser_interval"));
            this.range = Math.max(1, params().num("laser_range"));
            this.damage = params().num("laser_damage");
            this.beamDirection = Motion.horizontalFacing(player().getLocation());
            if (beamDirection.lengthSquared() < 1.0E-6) {
                beamDirection = new Vector(0, 0, 1);
            }
        }

        void assemble() {
            if (gone) {
                return;
            }
            Player owner = player();
            Location plate = base.clone().add(0, LASER_PLATE / 2, 0);
            BlockDisplay plateDisplay = part(plate, PLATE, Displays.transform(0, 0, 0, 0.1f, 0.02f, 0.1f, 0, 0, 0));
            Displays.animateNextTick(ctx(), owner, plateDisplay,
                    Displays.transform(0, 0, 0, 0.9f, (float) LASER_PLATE, 0.9f, 45, 0, 0), 4);
            Location pylonAt = base.clone().add(0, LASER_PLATE + LASER_PYLON / 2, 0);
            BlockDisplay pylon = part(pylonAt, PYLON, Displays.transform(0, 0, 0, 0.3f, 0.02f, 0.3f, 0, 0, 0));
            Displays.animateNextTick(ctx(), owner, pylon,
                    Displays.transform(0, 0, 0, 0.3f, (float) LASER_PYLON, 0.3f, 0, 0, 0), 6);
            Quaternionf facing = facing(yawOf(beamDirection), 0);
            head = part(pivot, LASER_HEAD, framed(facing, null, 0, 0, 0, 0.02f, 0.02f, 0.02f));
            lens = part(pivot, LENS, framed(facing, null, 0, 0, 0.3f, 0.02f, 0.02f, 0.02f));
            Displays.outline(lens, PALETTE.body());
            for (int i = 0; i < coils.length; i++) {
                coils[i] = part(pylonAt, COIL, coilPose(i, 0, 0.02f));
            }
            spawnTimer(LASER_PLATE + LASER_PYLON + LASER_HEAD_SIZE + 0.5);
            ctx().runLater(owner, 4, () -> poseHead(facing, 4));
            built = true;

            Location ground = base.clone().add(0, 0.05, 0);
            Vfx.shockwave(ctx(), owner, ground, 0.4, 2.2, 6, Particle.DUST_COLOR_TRANSITION, HEAT, true);
            Vfx.ring(ctx(), ground, range, (int) Math.max(24, Math.min(64, range * 3)), 0, Particle.DUST, GUARD_DASH,
                    true);
            Vfx.cloud(ctx(), pylonAt, Particle.ELECTRIC_SPARK, 16, 0.4, 0.25, null, true);
            Vfx.dot(pivot, Particle.FLASH, LASER.body(), true);
            Fx.sound(pylonAt, Sound.BLOCK_ANVIL_PLACE, 0.7f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(pylonAt, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.4f);
            Fx.sound(pylonAt, Sound.BLOCK_PISTON_EXTEND, 1.0f, 0.8f + Fx.jitter(0.05f));
            Loop.repeat(ctx(), owner, 1, 1, life, this, this::expire);
        }

        @Override
        public boolean test(long tick) {
            if (gone || !standing()) {
                return false;
            }
            updateTimer(life - tick);
            drawGuard(tick);
            if (tick % POSE_PERIOD == 0) {
                spinCoils(tick + POSE_PERIOD);
            }
            Location lensAt = lensPoint();
            LivingEntity next = pickTarget(lensAt, range);
            if (next != target && next != null) {
                Fx.sound(pivot, Sound.BLOCK_NOTE_BLOCK_BIT, 0.9f, 1.3f);
            }
            target = next;
            if (target != null) {
                Vector desired = Fx.body(target).toVector().subtract(lensAt.toVector());
                if (desired.lengthSquared() > 1.0E-6) {
                    beamDirection = turn(beamDirection, desired, BEAM_TURN);
                }
            } else {
                // Nothing to shoot: the head pans slowly around, looking for a target.
                beamDirection = Motion.horizontal(beamDirection).rotateAroundY(IDLE_PAN);
                if (beamDirection.lengthSquared() < 1.0E-6) {
                    beamDirection = new Vector(0, 0, 1);
                }
            }
            if (tick % POSE_PERIOD == 0) {
                poseHead(facing(yawOf(beamDirection), pitchOf(beamDirection)), POSE_PERIOD);
            }
            if (tick < charge) {
                chargeUp(tick, lensAt);
                return true;
            }
            if (target == null) {
                powerDown();
                return true;
            }
            fireBeam(tick - charge, lensAt);
            return true;
        }

        /** Spin-up: the lens pulses, sparks gather into it and a dotted guide line shows where the beam will go. */
        private void chargeUp(long tick, Location lensAt) {
            if (tick == 0) {
                Fx.sound(pivot, Sound.ENTITY_GUARDIAN_ATTACK, 1.2f, 1.4f);
                Fx.sound(pivot, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.6f);
            }
            double progress = (double) (tick + 1) / Math.max(1, charge);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 2); i++) {
                Vector offset = new Vector(random.nextGaussian(), random.nextGaussian(), random.nextGaussian())
                        .normalize().multiply(0.9 - 0.4 * progress);
                Vfx.trail(lensAt.clone().add(offset), lensAt, random.nextBoolean() ? LASER.core() : LASER.body(), 5,
                        true);
            }
            if (tick % 2 == 0) {
                Location guideEnd = lensAt.clone().add(beamDirection.clone().multiply(beamLength(lensAt)));
                Vfx.line(ctx(), lensAt, guideEnd, 0.5, Particle.DUST, ORANGE, true);
            }
        }

        private void powerDown() {
            if (!beamOn) {
                return;
            }
            beamOn = false;
            for (BlockDisplay beam : new BlockDisplay[] {core, sheath}) {
                if (beam != null && beam.isValid()) {
                    Displays.animate(beam, beamPose(beamDirection.clone().multiply(0.05), 0.001f), 3);
                }
            }
            Fx.sound(pivot, Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.8f);
        }

        private void fireBeam(long firing, Location lensAt) {
            double length = beamLength(lensAt);
            Vector delta = beamDirection.clone().multiply(length);
            Location end = lensAt.clone().add(delta);
            if (core == null || !core.isValid()) {
                core = keep(Displays.block(ctx(), player(), lensAt, BEAM_CORE, beamPose(delta, 0.001f), life + 20));
                core.setTeleportDuration(1);
                sheath = keep(Displays.block(ctx(), player(), lensAt, BEAM_SHEATH, beamPose(delta, 0.001f),
                        life + 20));
                sheath.setTeleportDuration(1);
                Displays.outline(sheath, LASER.body());
            }
            if (!beamOn) {
                beamOn = true;
                Fx.sound(lensAt, Sound.ENTITY_WARDEN_SONIC_CHARGE, 0.6f, 2.0f);
                Fx.sound(lensAt, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 2.0f);
                Vfx.dot(lensAt, Particle.FLASH, LASER.core(), true);
            }
            // A slight flicker of the core width keeps the beam alive.
            float flicker = 1f + 0.18f * (float) Math.sin(firing * 1.7);
            Displays.moveTo(core, lensAt);
            Displays.moveTo(sheath, lensAt);
            Displays.animate(core, beamPose(delta, BEAM_CORE_WIDTH * flicker), 1);
            Displays.animate(sheath, beamPose(delta, BEAM_SHEATH_WIDTH), 1);
            drawBeam(firing, lensAt, end, length);
            if (firing % interval == 0) {
                burn(lensAt, end, length);
            }
            if (firing - lastHum >= 30) {
                lastHum = firing;
                Fx.sound(pivot, Sound.BLOCK_BEACON_AMBIENT, 1.0f, 1.7f);
                Fx.sound(pivot, Sound.ENTITY_GUARDIAN_ATTACK, 0.5f, 1.8f);
            }
        }

        /** Pulses of light running down the beam, sparks where it ends and heat shimmer at the lens. */
        private void drawBeam(long firing, Location from, Location end, double length) {
            World world = from.getWorld();
            int pulses = Vfx.n(ctx(), 3);
            for (int i = 0; i < pulses; i++) {
                double t = ((firing * 0.13) + (double) i / pulses) % 1.0;
                Location at = from.clone().add(beamDirection.clone().multiply(t * length));
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0.02, 0.02, 0.02, 0, HEAT, true);
            }
            Vfx.cloud(ctx(), end, Particle.ELECTRIC_SPARK, 2, 0.08, 0.08, null, false);
            if (firing % 2 == 0) {
                Vfx.cloud(ctx(), end, Particle.SMALL_FLAME, 1, 0.05, 0.01, null, false);
                Vfx.dot(end, Particle.DUST_COLOR_TRANSITION, SCORCH, false);
                Vfx.dot(from, Particle.WAX_ON, null, false);
            }
            if (firing % 6 == 0) {
                Vfx.cloud(ctx(), end, Particle.SMOKE, 1, 0.05, 0.01, null, false);
            }
        }

        /** Every enemy whose body touches the beam takes the burn. */
        private void burn(Location from, Location end, double length) {
            Vector start = from.toVector();
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(player(), from, length + 1)) {
                BoundingBox box = enemy.getBoundingBox().expand(BEAM_REACH);
                if (!box.contains(start) && box.rayTrace(start, beamDirection, length) == null) {
                    continue;
                }
                Combat.hit(ctx(), player(), enemy, damage);
                hits++;
                Location body = Fx.body(enemy);
                Vfx.dot(body, Particle.FLASH, LASER.body(), false);
                Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 4, 0.2, 0, HEAT, false);
                Vfx.cloud(ctx(), body, Particle.SMALL_FLAME, 2, 0.15, 0.02, null, false);
            }
            if (hits > 0) {
                Fx.sound(end, Sound.BLOCK_FIRE_EXTINGUISH, 0.35f, 1.8f + Fx.jitter(0.1f));
                Fx.sound(end, Sound.BLOCK_CONDUIT_ATTACK_TARGET, 0.4f, 1.6f + Fx.jitter(0.1f));
            }
        }

        /** How far the beam reaches from the lens along its direction before a block stops it. */
        private double beamLength(Location lensAt) {
            RayTraceResult wall = lensAt.getWorld().rayTraceBlocks(lensAt, beamDirection, range,
                    FluidCollisionMode.NEVER, true);
            return wall == null ? range : Math.max(0.1, wall.getHitPosition().distance(lensAt.toVector()));
        }

        private Location lensPoint() {
            return pivot.clone().add(beamDirection.clone().multiply(0.36));
        }

        private void poseHead(Quaternionf facing, int ticks) {
            float h = LASER_HEAD_SIZE;
            if (head != null && head.isValid()) {
                Displays.animate(head, framed(facing, null, 0, 0, 0, h, h, h), ticks);
            }
            if (lens != null && lens.isValid()) {
                Displays.animate(lens, framed(facing, null, 0, 0, h / 2 + 0.03f, 0.32f, 0.32f, 0.08f), ticks);
            }
        }

        /** The capacitor coils orbit the pylon, faster once the beam is live. */
        private void spinCoils(long targetTick) {
            double speed = beamOn ? 0.55 : 0.25;
            for (int i = 0; i < coils.length; i++) {
                BlockDisplay coil = coils[i];
                if (coil != null && coil.isValid()) {
                    Displays.animate(coil, coilPose(i, targetTick * speed, 0.17f), POSE_PERIOD);
                }
            }
        }

        private Transformation coilPose(int index, double angle, float size) {
            double a = angle + index * Math.PI;
            float y = (float) (0.12 * Math.sin(angle * 0.5 + index * Math.PI));
            Quaternionf spin = new Quaternionf().rotateY((float) -a).rotateX(0.6f);
            Vector3f center = new Vector3f((float) (Math.cos(a) * COIL_ORBIT), y, (float) (Math.sin(a) * COIL_ORBIT));
            Vector3f translation = center.sub(new Quaternionf(spin).transform(new Vector3f(size / 2)));
            return new Transformation(translation, spin, new Vector3f(size), new Quaternionf());
        }

        @Override
        void expire() {
            if (core != null && core.isValid() && beamOn) {
                // The beam snaps off with a last flash at the lens.
                Vfx.dot(lensPoint(), Particle.FLASH, LASER.core(), true);
                Fx.sound(pivot, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.4f);
            }
            super.expire();
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    /** The rotation that turns a display's local +z towards the given yaw and pitch (degrees, Minecraft style). */
    private static Quaternionf facing(double yaw, double pitch) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch));
    }

    private static Vector facingVector(double yaw, double pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vector(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    private static double yawOf(Vector direction) {
        return Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    private static double pitchOf(Vector direction) {
        double length = direction.length();
        return length < 1.0E-9 ? 0 : -Math.toDegrees(Math.asin(Math.clamp(direction.getY() / length, -1.0, 1.0)));
    }

    private static double wrap(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped > 180) {
            wrapped -= 360;
        } else if (wrapped < -180) {
            wrapped += 360;
        }
        return wrapped;
    }

    /**
     * Turns a unit direction towards {@code desired} like a turret head: yaw and pitch each by at most
     * {@code maxAngle} radians, so a target behind it is reached by swinging round, never by pitching over the top or
     * down through the floor.
     */
    private static Vector turn(Vector current, Vector desired, double maxAngle) {
        double step = Math.toDegrees(maxAngle);
        double yaw = yawOf(current);
        double pitch = pitchOf(current);
        double newYaw = yaw + Math.clamp(wrap(yawOf(desired) - yaw), -step, step);
        double newPitch = pitch + Math.clamp(pitchOf(desired) - pitch, -step, step);
        return facingVector(newYaw, newPitch);
    }

    /**
     * A block display part of a turret head: a box of the given size centered at {@code (cx, cy, cz)} in the head's
     * frame (local +z = where the head faces), turned with the head and optionally by its own {@code local} rotation.
     */
    private static Transformation framed(Quaternionf facing, @Nullable Quaternionf local, float cx, float cy, float cz,
                                         float sx, float sy, float sz) {
        Quaternionf rotation = new Quaternionf(facing);
        if (local != null) {
            rotation.mul(local);
        }
        Vector3f size = new Vector3f(sx, sy, sz);
        Vector3f translation = new Quaternionf(facing).transform(new Vector3f(cx, cy, cz))
                .sub(new Quaternionf(rotation).transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    /** The initial (tiny) pose of a gun turret head part, before it unfolds. */
    private static Transformation headPart(@Nullable Quaternionf local, float cx, float cy, float cz, float sx,
                                           float sy, float sz) {
        return framed(new Quaternionf(), local, cx, cy, cz, sx, sy, sz);
    }

    /** A thin box of the given width from the block display's origin along {@code delta}. */
    private static Transformation beamPose(Vector delta, float width) {
        Vector3f direction = new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ());
        float length = direction.length();
        if (length < 1.0E-4f) {
            direction.set(0, 0, 1);
            length = 0.01f;
        }
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction.normalize());
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
        return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
    }
}
