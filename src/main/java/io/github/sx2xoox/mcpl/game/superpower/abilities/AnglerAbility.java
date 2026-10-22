package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 낚시꾼 — an angler. Near water or in the rain its ultimate charges faster, the active casts a bobber that hooks
 * the first enemy it touches and reels it up through the air to flop down in front of the angler, and the ultimate
 * hooks a giant salmon out of the ground that leaps up under the enemies and belly-flops onto them.
 *
 * <p>Unlike the grappler's drag along the ground, the catch is lifted on a high arc (a lag-proof, pre-computed
 * curve), so the enemy is helpless in the air for a moment and lands right at the angler's feet.
 *
 * <p>Look: sea teal and foam white. A white fishing line (a thin glowing beam) runs from the rod tip to a red and
 * white bobber; hooks splash, the catch sprays droplets along its arc. The salmon is built from terracotta blocks
 * (red flanks, olive back, pale belly, V tail) that turn as one body along its leap; water columns burst up where it
 * breaks the ground and a crown of water plates spreads where it lands, before it flops twice and sinks away.
 */
public final class AnglerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("angler", "낚시꾼", Material.FISHING_ROD)
            .color(TextColor.color(0x2EC4B6))
            .difficulty(2)
            .passive("바다의 행운",
                    "물가(물 {luck_radius}블록 이내)나 물속, 비를 맞는 곳에서는 궁극기 게이지가 초당 {luck_gauge}% "
                            + "더 찹니다. 낚아채기로 적을 낚을 때마다 게이지가 {catch_gauge}% 찹니다.")
            .active("낚아채기",
                    "찌를 던집니다({cast_range}블록). 찌에 걸린 적은 {reel_damage} 피해를 입고 {reel_height}블록 높이로 "
                            + "낚아 올려져 내 앞에 내동댕이쳐지며 {slam_damage} 피해와 {reel_slow}초 구속 II를 받습니다. "
                            + "빗나가면 쿨타임이 {miss_refund}% 돌아옵니다.",
                    9)
            .ultimate("대어",
                    "조준한 땅({ult_range}블록 이내)에서 거대한 연어를 낚아 올립니다. {ult_warning}초 뒤 땅을 뚫고 "
                            + "튀어올라 반경 {ult_radius}블록의 적에게 {ult_burst_damage} 피해를 주고 띄운 뒤, 떨어지며 "
                            + "내리찍어 {ult_slam_damage} 피해를 주고 밀쳐내며 {ult_slow}초간 구속 II를 겁니다.")
            .param("luck_radius", 3)
            .param("luck_gauge", 1.5)
            .param("catch_gauge", 8)
            .param("cast_range", 14)
            .param("reel_damage", 3)
            .param("reel_height", 3)
            .param("slam_damage", 2)
            .param("reel_slow", 1.5)
            .param("miss_refund", 50)
            .param("ult_range", 24)
            .param("ult_radius", 4.5)
            .param("ult_warning", 1)
            .param("ult_burst_damage", 3)
            .param("ult_slam_damage", 7)
            .param("ult_slow", 2)
            .build();

    private static final String COLOR_TAG = "#2EC4B6";

    // ------------------------------------------------------------------ look: foam white, sea teal, deep sea
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0x2EC4B6, 0x0B5563);
    private static final Particle.DustTransition SEA_GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition SEA_FADE = PALETTE.fade(1.1f);
    private static final Particle.DustOptions SHADOW = new Particle.DustOptions(Color.fromRGB(0x0B3640), 1.3f);
    private static final Particle.DustOptions FOAM = new Particle.DustOptions(Color.fromRGB(0xE8FBFF), 0.9f);
    private static final Material LINE_BLOCK = Material.WHITE_CONCRETE;
    private static final Material BOBBER_TOP = Material.RED_CONCRETE;
    private static final Material BOBBER_BOTTOM = Material.WHITE_CONCRETE;
    private static final Material WATER_GLASS = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final float LINE_WIDTH = 0.035f;

    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;

    // ------------------------------------------------------------------ passive: luck of the sea
    private static final long LUCK_PERIOD = 20;
    private static final int LUCK_DEPTH = 2;
    private static final double SKY_CHECK = 64;

    // ------------------------------------------------------------------ active: hook and reel
    private static final double CAST_SPEED = 1.5;
    private static final double CAST_LIFT = 0.08;
    private static final double CAST_GRAVITY = 0.035;
    /** Extra reach around an enemy's hitbox for the bobber. */
    private static final double HOOK_RADIUS = 0.35;
    private static final long TUG_TICKS = 3;
    private static final long REEL_TICKS = 12;
    /**
     * Gentle pull back onto the planned arc for server-driven targets (mobs). Players are steered open-loop: their
     * server position trails their client by the ping, and correcting towards it would overshoot.
     */
    private static final double REEL_CORRECTION = 0.3;
    /** Where the catch lands: this far in front of the angler, towards where it came from. */
    private static final double LAND_DISTANCE = 2.2;
    private static final long RETRACT_TICKS = 5;
    /** The longest a catch let go mid-reel is kept from fall damage while it comes down. */
    private static final long CUSHION_MAX_TICKS = 80;
    private static final double FLOP_HOP = 0.28;
    private static final float BOBBER_SIZE = 0.2f;

    // ------------------------------------------------------------------ ultimate: the big one
    private static final int GROUND_SNAP = 8;
    private static final float FISH_SCALE = 1.9f;
    private static final long LEAP_TICKS = 22;
    private static final double LEAP_HEIGHT = 6.0;
    private static final double LEAP_START_BACK = 3.2;
    private static final double LEAP_START_DEPTH = 1.6;
    private static final double LEAP_END_FORWARD = 1.2;
    /** Height of the fish's middle above the ground when it lies flat (half its scaled body height). */
    private static final double FISH_REST_HEIGHT = 0.6 * FISH_SCALE;
    private static final double ERUPT_LIFT = 2.0;
    private static final double SLAM_PUSH = 1.1;
    private static final double SLAM_LIFT = 0.45;
    private static final long FLOP_1 = 5;
    private static final long FLOP_2 = 11;
    private static final long SINK_START = 17;
    private static final long SINK_TICKS = 14;
    private static final int COLUMNS = 6;
    private static final int CROWN_PLATES = 8;
    private static final float CROWN_LEAN = 20f;
    private static final int DROPLETS = 4;
    private static final long DROPLET_TICKS = 14;
    /** The salmon grows out of the ground over its first ticks of the leap. */
    private static final int FISH_GROW_TICKS = 4;

    /** The salmon's blocks in its own frame (nose along +z), at scale 1. */
    private static final FishPart[] FISH = {
            new FishPart(Material.RED_TERRACOTTA, 0, 0, 0, 1.0f, 1.1f, 2.1f, 0),
            new FishPart(Material.RED_TERRACOTTA, 0, -0.03f, 1.3f, 0.82f, 0.92f, 0.62f, 0),
            new FishPart(Material.GREEN_TERRACOTTA, 0, 0.56f, -0.05f, 0.72f, 0.18f, 2.3f, 0),
            new FishPart(Material.WHITE_TERRACOTTA, 0, -0.56f, 0.1f, 0.8f, 0.14f, 2.1f, 0),
            new FishPart(Material.RED_TERRACOTTA, 0, 0, -1.3f, 0.5f, 0.62f, 0.62f, 0),
            new FishPart(Material.TERRACOTTA, 0, 0.32f, -1.8f, 0.08f, 0.78f, 0.46f, -35),
            new FishPart(Material.TERRACOTTA, 0, -0.32f, -1.8f, 0.08f, 0.78f, 0.46f, 35),
            new FishPart(Material.TERRACOTTA, 0, 0.78f, 0.15f, 0.08f, 0.42f, 0.72f, -15),
            new FishPart(Material.BLACK_CONCRETE, 0, 0.14f, 1.42f, 0.86f, 0.16f, 0.16f, 0),
    };

    private record FishPart(Material material, float x, float y, float z, float w, float h, float l, float pitch) {
    }

    private boolean lucky;
    private @Nullable Cast cast;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        cast = null;
        lucky = false;
    }

    // ------------------------------------------------------------------ passive: luck of the sea

    @Override
    public void onTick(long tick) {
        if (tick % LUCK_PERIOD != 0) {
            return;
        }
        Player owner = player();
        boolean now = isLucky(owner);
        if (now != lucky) {
            lucky = now;
            if (now) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚓ 바다의 행운</" + COLOR_TAG
                        + "> <gray>궁극기 게이지가 더 빨리 찹니다</gray>"));
                Fx.sound(owner.getLocation(), Sound.ENTITY_FISH_SWIM, 0.6f, 1.3f + Fx.jitter(0.1f));
            }
        }
        double gain = Math.max(0, params().num("luck_gauge"));
        // Charging gauge over time: it waits out the recharge delay after an ultimate like every other source.
        if (!now || gain <= 0 || ctx().gauge(owner) >= 100 || ctx().isGaugeLocked(owner)) {
            return;
        }
        ctx().addEarnedGauge(owner, gain);
        Location feet = owner.getLocation().add(0, 0.1, 0);
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        Vfx.ring(ctx(), feet, 0.6, 10, tick * 0.05, Particle.FISHING, null, false);
        for (int i = 0; i < Vfx.n(ctx(), 2); i++) {
            Vfx.trail(Vfx.scatter(feet, 0.9), chest, i % 2 == 0 ? PALETTE.body() : PALETTE.core(), 12, false);
        }
        Vfx.cloud(ctx(), chest, Particle.DOLPHIN, 4, 0.3, 0, null, false);
    }

    /** In water, under open rain, or close to water. */
    private boolean isLucky(Player owner) {
        if (owner.isInWater()) {
            return true;
        }
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        if (world.hasStorm() && world.rayTraceBlocks(eye, new Vector(0, 1, 0), SKY_CHECK, FluidCollisionMode.ALWAYS,
                false) == null) {
            return true;
        }
        double radius = Math.max(0, params().num("luck_radius"));
        int r = (int) Math.ceil(radius);
        Location feet = owner.getLocation();
        int fx = feet.getBlockX();
        int fy = feet.getBlockY();
        int fz = feet.getBlockZ();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > (radius + 0.5) * (radius + 0.5)) {
                    continue;
                }
                for (int dy = -LUCK_DEPTH; dy <= 1; dy++) {
                    if (isWater(world.getBlockAt(fx + dx, fy + dy, fz + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isWater(Block block) {
        Material type = block.getType();
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return true;
        }
        return type != Material.AIR && block.getBlockData() instanceof Waterlogged waterlogged
                && waterlogged.isWaterlogged();
    }

    // ------------------------------------------------------------------ active: hook and reel

    @Override
    public ActivationResult onActive() {
        if (cast != null) {
            return ActivationResult.fail("이미 찌를 던졌습니다");
        }
        Cast created = new Cast(player());
        cast = created;
        created.throwLine();
        return ActivationResult.success();
    }

    /** The rod tip: in front of and slightly right of the eyes, where the held rod ends. */
    private static Location rodTip(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        Vector facing = Motion.horizontalFacing(eye);
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        return eye.add(look.multiply(0.9)).add(right.multiply(0.35)).subtract(0, 0.15, 0);
    }

    private enum Stage { FLY, TUG, REEL, RETRACT }

    /**
     * One cast: the bobber flies along a shallow arc; the first enemy it touches is hooked, tugged and reeled up on a
     * pre-computed arc to land in front of the angler; a miss reels the bobber back and refunds part of the cooldown.
     */
    private final class Cast implements LongPredicate {

        private final Player owner;
        private final World world;
        private final double range;
        private final Vector velocity;
        private final Location bobber;
        private final List<BlockDisplay> floats = new ArrayList<>();
        private @Nullable BlockDisplay line;
        private Stage stage = Stage.FLY;
        private double traveled;
        private long stageTick;
        private @Nullable LivingEntity hooked;
        private @Nullable Location retractFrom;
        private boolean landed;
        private Location reelStart;
        private Location reelEnd;
        private double apexY;

        Cast(Player owner) {
            this.owner = owner;
            this.world = owner.getWorld();
            this.range = Math.max(2, params().num("cast_range"));
            this.bobber = rodTip(owner);
            this.velocity = owner.getEyeLocation().getDirection().multiply(CAST_SPEED).add(new Vector(0, CAST_LIFT, 0));
            this.reelStart = bobber.clone();
            this.reelEnd = bobber.clone();
        }

        void throwLine() {
            long lifetime = (long) Math.ceil(range / CAST_SPEED) + 6 + TUG_TICKS + REEL_TICKS + RETRACT_TICKS + 20;
            Location at = bobber.clone();
            at.setYaw(0);
            at.setPitch(0);
            floats.add(Displays.block(ctx(), owner, at, BOBBER_TOP.createBlockData(),
                    Displays.transform(0, BOBBER_SIZE * 0.25f, 0, BOBBER_SIZE, BOBBER_SIZE * 0.5f, BOBBER_SIZE,
                            0, 0, 0), lifetime));
            floats.add(Displays.block(ctx(), owner, at, BOBBER_BOTTOM.createBlockData(),
                    Displays.transform(0, -BOBBER_SIZE * 0.25f, 0, BOBBER_SIZE, BOBBER_SIZE * 0.5f, BOBBER_SIZE,
                            0, 0, 0), lifetime));
            for (BlockDisplay display : floats) {
                display.setTeleportDuration(1);
            }
            line = Displays.block(ctx(), owner, rodTip(owner), LINE_BLOCK.createBlockData(),
                    beamPose(new Vector(0, 0, 0.01), LINE_WIDTH), lifetime);
            line.setTeleportDuration(1);

            Location tip = rodTip(owner);
            Fx.sound(tip, Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 0.8f + Fx.jitter(0.08f));
            Fx.sound(tip, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.6f + Fx.jitter(0.1f));
            Vfx.cloud(ctx(), tip, Particle.DUST_COLOR_TRANSITION, 6, 0.12, 0, SEA_GLOW, false);
            Loop.repeat(ctx(), owner, 1, 1, lifetime, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            stageTick++;
            return switch (stage) {
                case FLY -> fly();
                case TUG -> tug();
                case REEL -> reel();
                case RETRACT -> retract();
            };
        }

        /** One tick of the bobber's flight: hooks the first enemy on its path, plops on blocks or at max range. */
        private boolean fly() {
            if (!owner.getWorld().equals(world)) {
                return false;
            }
            Vector step = velocity.clone();
            double length = step.length();
            Vector direction = step.clone().multiply(1 / length);
            RayTraceResult wall = world.rayTraceBlocks(bobber, direction, length, FluidCollisionMode.NEVER, true);
            double limit = wall != null ? wall.getHitPosition().distance(bobber.toVector()) : length;
            LivingEntity first = null;
            double best = limit;
            Location middle = bobber.clone().add(step.clone().multiply(0.5));
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, length * 0.5 + 2)) {
                if (!visible(owner, enemy)) {
                    // A stealthed enemy is not hooked: the line would give its position away.
                    continue;
                }
                BoundingBox box = enemy.getBoundingBox().expand(HOOK_RADIUS);
                double distance;
                if (box.contains(bobber.toVector())) {
                    distance = 0;
                } else {
                    RayTraceResult hit = box.rayTrace(bobber.toVector(), direction, length);
                    if (hit == null) {
                        continue;
                    }
                    distance = hit.getHitPosition().distance(bobber.toVector());
                }
                if (distance <= best) {
                    best = distance;
                    first = enemy;
                }
            }
            if (first != null) {
                hook(first);
                return true;
            }
            if (wall != null) {
                bobber.add(direction.multiply(Math.max(0, limit - 0.1)));
                miss(true);
                return true;
            }
            bobber.add(step);
            traveled += length;
            velocity.setY(velocity.getY() - CAST_GRAVITY);
            drawLine(bobber);
            placeBobber(bobber);
            if (stageTick % 2 == 0) {
                Vfx.dot(bobber, Particle.DUST_COLOR_TRANSITION, SEA_GLOW, false);
            }
            if (traveled >= range) {
                miss(false);
            }
            return true;
        }

        /** The bobber caught an enemy: a splash, the hook damage and a few ticks of tension on the line. */
        private void hook(LivingEntity target) {
            hooked = target;
            stage = Stage.TUG;
            stageTick = 0;
            Location body = Fx.body(target);
            Combat.hit(ctx(), owner, target, Math.max(0, params().num("reel_damage")));
            double gauge = Math.max(0, params().num("catch_gauge"));
            if (gauge > 0) {
                // A bonus for a landed hit, earned like the hit's own damage: nothing during the recharge delay.
                ctx().addEarnedGauge(owner, gauge);
            }
            Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.SPLASH, false);
            Vfx.cloud(ctx(), body, Particle.SPLASH, 18, 0.35, 0.1, null, false);
            Vfx.ring(ctx(), body, 0.8, 14, 0, Particle.FISHING, null, false);
            Fx.sound(body, Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.0f, 1.0f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 0.8f + Fx.jitter(0.08f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.6f, 1.2f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚓ 낚았다!</" + COLOR_TAG + "> <white><name></white>",
                    Placeholder.component("name", target.name())));
            if (!Combat.isTargetable(ctx(), owner, target)) {
                startRetract(body);
            }
        }

        /**
         * The catch is still on the line: a live enemy in this world that the angler can see. One that slips into
         * stealth is let go, so the line, the bobber and the slam never give its position away.
         */
        private boolean holds(@Nullable LivingEntity target) {
            return target != null && Combat.isTargetable(ctx(), owner, target) && target.getWorld().equals(world)
                    && visible(owner, target);
        }

        /** Where the bobber is drawn right now (it rides on the catch while hooked). */
        private Location drawnBobber() {
            for (BlockDisplay display : floats) {
                if (display.isValid()) {
                    return display.getLocation();
                }
            }
            return bobber;
        }

        /** The line goes taut; then the reel begins from wherever the target stands. */
        private boolean tug() {
            LivingEntity target = hooked;
            if (target == null || !holds(target)) {
                startRetract(drawnBobber());
                return true;
            }
            Location body = Fx.body(target);
            placeBobber(body);
            drawLine(body);
            Location tip = rodTip(owner);
            // Tension: motes racing along the line towards the rod.
            Vfx.trail(body, tip, PALETTE.core(), 4, false);
            Vfx.cloud(ctx(), body, Particle.SPLASH, 4, 0.25, 0.05, null, false);
            if (stageTick >= TUG_TICKS) {
                planReel(target);
                stage = Stage.REEL;
                stageTick = 0;
                Fx.sound(tip, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.2f, 1.2f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.7f, 1.4f + Fx.jitter(0.1f));
                Vfx.cloud(ctx(), target.getLocation(), Particle.CLOUD, 6, 0.3, 0.05, null, false);
            }
            return true;
        }

        /** The arc: from the target's feet up to {@code reel_height} above the higher end, down in front of the angler. */
        private void planReel(LivingEntity target) {
            reelStart = target.getLocation();
            Location feet = owner.getLocation();
            Vector toward = Motion.horizontal(reelStart.toVector().subtract(feet.toVector()));
            if (toward.lengthSquared() < 1.0E-6) {
                toward = Motion.horizontalFacing(feet);
            }
            double gap = Math.min(LAND_DISTANCE, horizontalDistance(feet, reelStart));
            Location end = feet.clone().add(toward.multiply(gap));
            Location chest = feet.clone().add(0, owner.getHeight() * 0.5, 0);
            if (!Spots.clearPath(chest, end.clone().add(0, owner.getHeight() * 0.5, 0))) {
                end = feet.clone();
            }
            reelEnd = end;
            apexY = Math.max(reelStart.getY(), reelEnd.getY()) + Math.max(0.5, params().num("reel_height"));
        }

        /** Point of the reel arc at {@code s} in 0..1 (a parabola through the apex). */
        private Vector arc(double s) {
            double control = 2 * apexY - (reelStart.getY() + reelEnd.getY()) / 2;
            double y = (1 - s) * (1 - s) * reelStart.getY() + 2 * (1 - s) * s * control + s * s * reelEnd.getY();
            return new Vector(reelStart.getX() + (reelEnd.getX() - reelStart.getX()) * s, y,
                    reelStart.getZ() + (reelEnd.getZ() - reelStart.getZ()) * s);
        }

        /**
         * One tick of the reel. The velocity is the planned curve's own next step (see {@link #REEL_CORRECTION}), so a
         * lagging client position never makes it overshoot.
         */
        private boolean reel() {
            LivingEntity target = hooked;
            if (target == null || !holds(target)) {
                if (target != null) {
                    cushion(target);
                }
                startRetract(drawnBobber());
                return true;
            }
            long t = stageTick;
            if (t > REEL_TICKS) {
                flop(target);
                return true;
            }
            // Where the catch should be now, and where it should be after this tick's move.
            Vector planned = arc((double) (t - 1) / REEL_TICKS);
            Vector next = arc((double) t / REEL_TICKS);
            double correction = target instanceof Player ? 0 : REEL_CORRECTION;
            Vector velocity = next.clone().subtract(planned)
                    .add(planned.clone().subtract(target.getLocation().toVector()).multiply(correction));
            target.setVelocity(Motion.clamp(velocity, MAX_SPEED));
            target.setFallDistance(0);
            Location body = Fx.body(target);
            placeBobber(body);
            drawLine(body);
            // Droplets shaken off the catch.
            Vfx.cloud(ctx(), body, Particle.FALLING_WATER, 4, 0.3, 0, null, false);
            if (t % 2 == 0) {
                Vfx.dot(body, Particle.DUST_COLOR_TRANSITION, SEA_FADE, false);
                Vfx.cloud(ctx(), body, Particle.SPLASH, 3, 0.3, 0.05, null, false);
            }
            return true;
        }

        /**
         * A catch let go mid-reel (it slipped into stealth, say) falls the rest of the way without fall damage, as it
         * would have landed on the reel: its fall distance is kept at zero until it has settled on the ground (or
         * swims). Nothing is drawn, so a stealthed catch stays hidden.
         */
        private void cushion(LivingEntity target) {
            if (!target.isValid() || target.isDead()) {
                return;
            }
            target.setFallDistance(0);
            long[] settled = {0};
            Loop.repeat(ctx(), owner, 1, 1, CUSHION_MAX_TICKS, run -> {
                if (!target.isValid() || target.isDead()) {
                    return false;
                }
                target.setFallDistance(0);
                settled[0] = Motion.isGrounded(target) || target.isInWater() ? settled[0] + 1 : 0;
                return settled[0] < LandingGuard.SETTLE_TICKS;
            });
        }

        /** The catch lands on the deck: the slam, a slow and a fish-out-of-water flop. */
        private void flop(LivingEntity target) {
            landed = true;
            target.setFallDistance(0);
            Combat.hit(ctx(), owner, target, Math.max(0, params().num("slam_damage")));
            Location feet = target.getLocation();
            Location body = Fx.body(target);
            if (Combat.isTargetable(ctx(), owner, target)) {
                long slow = params().ticks("reel_slow");
                if (slow > 0) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                            (int) Math.min(Integer.MAX_VALUE, slow), 1, false, true, true));
                }
                ThreadLocalRandom random = ThreadLocalRandom.current();
                Motion.push(target, new Vector(random.nextDouble(-0.08, 0.08), FLOP_HOP,
                        random.nextDouble(-0.08, 0.08)), MAX_SPEED);
            }
            Vfx.burst(ctx(), body, PALETTE, 0.7, Particle.SPLASH, false);
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.3, 1.8, 5, Particle.DUST_COLOR_TRANSITION,
                    SEA_GLOW, false);
            Vfx.cloud(ctx(), feet.clone().add(0, 0.2, 0), Particle.SPLASH, 24, 0.5, 0.15, null, false);
            BlockData ground = groundUnder(feet);
            if (ground != null) {
                world.spawnParticle(Particle.BLOCK, feet.clone().add(0, 0.1, 0), Vfx.n(ctx(), 10), 0.4, 0.05, 0.4,
                        0.1, ground);
            }
            Fx.sound(feet, Sound.ENTITY_SALMON_FLOP, 1.2f, 0.8f + Fx.jitter(0.08f));
            Fx.sound(feet, Sound.ENTITY_GENERIC_SPLASH, 0.9f, 1.1f + Fx.jitter(0.1f));
            Fx.sound(feet, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.7f, 0.8f + Fx.jitter(0.08f));
            ctx().runLater(owner, 6, () -> {
                if (target.isValid() && !target.isDead()) {
                    Fx.sound(target.getLocation(), Sound.ENTITY_COD_FLOP, 0.8f, 1.0f + Fx.jitter(0.1f));
                    Vfx.cloud(ctx(), target.getLocation().add(0, 0.3, 0), Particle.SPLASH, 8, 0.3, 0.1, null, false);
                }
            });
            startRetract(body);
        }

        /** A miss: the bobber plops down, then reels back. Part of the cooldown comes back. */
        private void miss(boolean onBlock) {
            Vfx.cloud(ctx(), bobber, Particle.SPLASH, onBlock ? 10 : 6, 0.2, 0.05, null, false);
            Vfx.ring(ctx(), bobber, 0.4, 10, 0, Particle.DUST, FOAM, false);
            Fx.sound(bobber, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.6f, 1.4f + Fx.jitter(0.1f));
            double refund = Math.clamp(params().num("miss_refund") / 100.0, 0.0, 1.0);
            if (refund > 0) {
                // The cooldown started when the cast succeeded (the bobber flies from the next tick on).
                ctx().reduceCooldown(owner, params().cooldownSeconds() * refund);
            }
            startRetract(bobber);
        }

        private void startRetract(Location from) {
            stage = Stage.RETRACT;
            stageTick = 0;
            retractFrom = from.clone();
            Fx.sound(rodTip(owner), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 1.4f + Fx.jitter(0.1f));
        }

        /** The bobber is wound back to the rod tip, then everything disappears. */
        private boolean retract() {
            Location from = retractFrom;
            if (from == null || !owner.getWorld().equals(world)) {
                return false;
            }
            Location tip = rodTip(owner);
            double t = Math.min(1.0, (double) stageTick / RETRACT_TICKS);
            Location at = from.clone().add(tip.toVector().subtract(from.toVector()).multiply(t * t));
            placeBobber(at);
            drawLine(at);
            return stageTick < RETRACT_TICKS;
        }

        private void placeBobber(Location at) {
            for (BlockDisplay display : floats) {
                Displays.moveTo(display, at);
            }
        }

        private void drawLine(Location end) {
            BlockDisplay beam = line;
            Location tip = rodTip(owner);
            Vector delta = end.toVector().subtract(tip.toVector());
            if (beam == null || !beam.isValid() || delta.lengthSquared() < 1.0E-4) {
                return;
            }
            // The beam's direction lives in its transformation; the display itself stays unrotated.
            Displays.moveTo(beam, tip);
            Displays.animate(beam, beamPose(delta, LINE_WIDTH), 1);
        }

        private void finish() {
            for (BlockDisplay display : floats) {
                if (display.isValid()) {
                    display.remove();
                }
            }
            floats.clear();
            if (line != null && line.isValid()) {
                line.remove();
            }
            line = null;
            if (cast == this) {
                cast = null;
            }
            if (landed && hooked != null) {
                hooked.setFallDistance(0);
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: the big one

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location ground = aimGround(owner, Math.max(1, params().num("ult_range")));
        if (ground == null) {
            return ActivationResult.fail("물고기를 낚을 땅이 보이지 않습니다");
        }
        if (!ctx().isInsideArena(ground)) {
            return ActivationResult.fail("자기장 밖에는 낚싯대를 드리울 수 없습니다");
        }
        new BigCatch(owner, ground).start();
        return ActivationResult.success();
    }

    /**
     * The ground point of the ultimate: under the aimed (visible) enemy, else where the view ray meets a block,
     * snapped onto the ground below. Null without ground in reach.
     */
    private @Nullable Location aimGround(Player owner, double range) {
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location point;
        if (aimed != null && visible(owner, aimed)) {
            point = aimed.getLocation().add(0, 0.2, 0);
        } else {
            Location eye = owner.getEyeLocation();
            Vector direction = eye.getDirection();
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER,
                    true);
            if (hit == null) {
                return null;
            }
            point = hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.2));
        }
        Double top = groundBelow(point);
        if (top == null) {
            return null;
        }
        point.setY(top);
        point.setYaw(0);
        point.setPitch(0);
        return point;
    }

    /** The top of the first solid block at most {@link #GROUND_SNAP} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = point.getBlockY();
        int end = Math.max(world.getMinHeight(), start - GROUND_SNAP);
        for (int y = Math.min(start, world.getMaxHeight() - 1); y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /**
     * The ultimate: a telegraphed fishing spot (a big bobber, ripples and a circling fish shadow), then a giant salmon
     * erupts from the ground, arcs high and belly-flops on the spot, flops twice and sinks away.
     */
    private final class BigCatch implements LongPredicate {

        private final Player owner;
        private final Location ground;
        private final World world;
        private final Vector leap;
        private final double radius;
        private final long warning;
        private final List<BlockDisplay> fish = new ArrayList<>();
        private final List<BlockDisplay> floats = new ArrayList<>();
        private final List<Droplet> droplets = new ArrayList<>();
        private final Location start;
        private final Location apex;
        private final Location end;
        private final Location control;
        private final float fishYaw;
        private @Nullable BlockDisplay line;
        private Location anchor;
        private final double shadowPhase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);

        private record Droplet(BlockDisplay display, Vector from, Vector velocity) {
        }

        BigCatch(Player owner, Location ground) {
            this.owner = owner;
            this.ground = ground;
            this.world = ground.getWorld();
            this.radius = Math.max(1, params().num("ult_radius"));
            this.warning = Math.max(4, params().ticks("ult_warning"));
            Vector toward = Motion.horizontal(ground.toVector().subtract(owner.getLocation().toVector()));
            this.leap = toward.lengthSquared() < 1.0E-6 ? Motion.horizontalFacing(owner.getLocation()) : toward;
            this.start = ground.clone().subtract(leap.clone().multiply(LEAP_START_BACK)).subtract(0, LEAP_START_DEPTH,
                    0);
            this.apex = ground.clone().add(0, LEAP_HEIGHT, 0);
            this.end = ground.clone().add(leap.clone().multiply(LEAP_END_FORWARD)).add(0, FISH_REST_HEIGHT, 0);
            // Control point of the quadratic curve that passes through the apex halfway.
            this.control = apex.clone().multiply(2).subtract(start.clone().add(end).multiply(0.5));
            this.anchor = start.clone();
            this.fishYaw = yawOf(leap);
        }

        void start() {
            long total = warning + LEAP_TICKS + SINK_START + SINK_TICKS + 4;
            Location at = ground.clone().add(0, 0.25, 0);
            float size = BOBBER_SIZE * 2.6f;
            floats.add(Displays.block(ctx(), owner, at, BOBBER_TOP.createBlockData(),
                    Displays.transform(0, size * 0.25f, 0, size, size * 0.5f, size, 0, 0, 0), warning + 4));
            floats.add(Displays.block(ctx(), owner, at, BOBBER_BOTTOM.createBlockData(),
                    Displays.transform(0, -size * 0.25f, 0, size, size * 0.5f, size, 0, 0, 0), warning + 4));
            for (BlockDisplay display : floats) {
                Displays.outline(display, PALETTE.body());
                display.setViewRange(2.0f);
            }
            line = Displays.block(ctx(), owner, rodTip(owner), LINE_BLOCK.createBlockData(),
                    beamPose(new Vector(0, 0, 0.01), LINE_WIDTH * 1.5f), warning + LEAP_TICKS + 2);
            line.setTeleportDuration(1);
            line.setViewRange(2.0f);

            Fx.sound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.2f, 0.6f);
            Fx.sound(at, Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.2f, 0.7f);
            Fx.sound(at, Sound.AMBIENT_UNDERWATER_ENTER, 1.0f, 1.2f);
            Fx.sound(at, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 1.2f, 0.8f);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.1, 0), 0.3, radius, 6, Particle.DUST_COLOR_TRANSITION,
                    SEA_GLOW, true);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⚓ 대어</b></" + COLOR_TAG + "> <gray>뭔가 큰 게 "
                    + "물었다…!</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, total, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            if (tick < warning) {
                telegraph(tick);
                return true;
            }
            long t = tick - warning;
            if (t == 0) {
                erupt();
            }
            if (t <= LEAP_TICKS) {
                fly(t);
                updateDroplets(t);
                if (t == LEAP_TICKS) {
                    slam();
                }
                return true;
            }
            long after = t - LEAP_TICKS;
            updateDroplets(t);
            aftermath(after);
            return after < SINK_START + SINK_TICKS;
        }

        // -------------------------------------------------------------- telegraph

        private void telegraph(long tick) {
            Location floor = ground.clone().add(0, 0.12, 0);
            if (tick % 2 == 0) {
                Vfx.ring(ctx(), floor, radius, (int) Math.min(48, Math.max(18, radius * 6)), tick * 0.03,
                        Particle.DUST_COLOR_TRANSITION, SEA_GLOW, true);
            }
            if (tick % 6 == 0) {
                Vfx.shockwave(ctx(), owner, floor, 0.3, radius * 0.8, 8, Particle.DUST, FOAM, false);
                Fx.sound(floor, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 1.0f, 0.8f + Fx.jitter(0.1f));
            }
            // The fish's shadow circling under the surface.
            double angle = shadowPhase + tick * 0.32;
            double r = radius * 0.5;
            Vector along = new Vector(-Math.sin(angle), 0, Math.cos(angle));
            Location head = floor.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
            for (int i = 0; i < Vfx.n(ctx(), 6); i++) {
                double back = i * 0.35;
                double width = 0.35 * Math.sin(Math.PI * (i + 1) / 7.0);
                Vector side = new Vector(-along.getZ(), 0, along.getX()).multiply(width);
                Location at = head.clone().subtract(along.clone().multiply(back));
                Vfx.dot(at.clone().add(side), Particle.DUST, SHADOW, false);
                Vfx.dot(at.clone().subtract(side), Particle.DUST, SHADOW, false);
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Location at = Vfx.scatter(floor, radius * 0.9);
                Vfx.dot(at, random.nextBoolean() ? Particle.BUBBLE_POP : Particle.SPLASH, null, false);
            }
            Vfx.dot(Vfx.scatter(floor, radius * 0.7), Particle.FISHING, null, true);
            // The big bobber bobs, then is dragged under right before the strike.
            long untilStrike = warning - tick;
            double bob = untilStrike <= 4 ? -0.25 * (5 - untilStrike) : Math.sin(tick * 0.6) * 0.08;
            Location bobberAt = ground.clone().add(0, 0.25 + bob, 0);
            for (BlockDisplay display : floats) {
                if (display.isValid()) {
                    Displays.moveTo(display, bobberAt);
                }
            }
            if (untilStrike == 4) {
                Fx.sound(bobberAt, Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.4f, 0.6f);
                Vfx.cloud(ctx(), bobberAt, Particle.SPLASH, 30, 0.4, 0.2, null, true);
            }
            drawLine(bobberAt);
        }

        // -------------------------------------------------------------- the leap

        private void erupt() {
            for (BlockDisplay display : floats) {
                if (display.isValid()) {
                    display.remove();
                }
            }
            floats.clear();
            anchor = curve(0);
            float pitch = pitchOf(tangent(0));
            for (FishPart part : FISH) {
                BlockDisplay display = Displays.block(ctx(), owner, anchor, part.material().createBlockData(),
                        turned(fishPose(part, 0.05f), fishYaw, pitch), LEAP_TICKS + SINK_START + SINK_TICKS + 10);
                display.setTeleportDuration(1);
                display.setViewRange(2.0f);
                fish.add(display);
            }
            Location center = ground.clone();
            waterColumns(center);
            sprayDroplets(center);
            Vfx.dot(center.clone().add(0, 1, 0), Particle.FLASH, PALETTE.body(), true);
            Vfx.cloud(ctx(), center.clone().add(0, 0.5, 0), Particle.SPLASH, 60, radius * 0.4, 0.4, null, true);
            Vfx.cloud(ctx(), center.clone().add(0, 0.3, 0), Particle.CLOUD, 20, radius * 0.3, 0.08, null, true);
            BlockData under = groundUnder(center.clone().add(0, 0.1, 0));
            if (under != null) {
                world.spawnParticle(Particle.BLOCK, center.clone().add(0, 0.2, 0), Vfx.n(ctx(), 40), radius * 0.3,
                        0.2, radius * 0.3, 0.3, under, true);
            }
            Fx.sound(center, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.6f, 0.6f);
            Fx.sound(center, Sound.ENTITY_DOLPHIN_JUMP, 1.6f, 0.5f);
            Fx.sound(center, Sound.ENTITY_GENERIC_SPLASH, 1.4f, 0.7f);
            Fx.sound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.4f, 0.5f);

            double damage = Math.max(0, params().num("ult_burst_damage"));
            for (LivingEntity enemy : ctx().enemiesNear(owner, center.clone().add(0, 0.5, 0), radius)) {
                Combat.hit(ctx(), owner, enemy, damage);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                // Tossed straight up by the burst, the crowd control of the ultimate: they come down where they were,
                // under the fish. (Thrown outwards they would land outside the belly-flop they are meant to eat.)
                Motion.push(enemy, new Vector(0, Motion.launchSpeed(ERUPT_LIFT), 0), MAX_SPEED);
                enemy.setFallDistance(0);
                if (visible(owner, enemy)) {
                    Vfx.cloud(ctx(), Fx.body(enemy), Particle.SPLASH, 12, 0.3, 0.1, null, false);
                }
            }
        }

        /**
         * The salmon flies along its curve, nose following the motion (it grows out of the ground over the first
         * ticks); the line stays hooked in its mouth.
         */
        private void fly(long t) {
            double s = (double) t / LEAP_TICKS;
            Location at = curve(s);
            float pitch = pitchOf(tangent(s));
            float scale = FISH_SCALE * (float) Math.min(1.0, (t + 1) / (double) FISH_GROW_TICKS);
            anchor = at;
            poseFish(at, 1, scale, pitch, 1);
            Location nose = at.clone();
            nose.setYaw(fishYaw);
            nose.setPitch(pitch);
            Vector heading = nose.getDirection();
            Location mouth = at.clone().add(heading.clone().multiply(1.6 * scale));
            drawLine(mouth);
            Location tail = at.clone().subtract(heading.multiply(1.6 * scale));
            Vfx.cloud(ctx(), tail, Particle.FALLING_WATER, 6, 0.5, 0, null, true);
            Vfx.cloud(ctx(), at, Particle.SPLASH, 6, 0.8, 0.05, null, true);
            Vfx.dot(tail, Particle.DUST_COLOR_TRANSITION, SEA_FADE, true);
            if (t == LEAP_TICKS / 2) {
                Fx.sound(at, Sound.ENTITY_SALMON_FLOP, 1.4f, 0.5f);
            }
            if (t >= LEAP_TICKS - 6) {
                // Impact telegraph: the landing ring tightens as the fish comes down.
                double closing = radius * (0.4 + 0.6 * (LEAP_TICKS - t) / 6.0);
                Vfx.ring(ctx(), ground.clone().add(0, 0.12, 0), closing, (int) Math.max(16, closing * 6), t * 0.2,
                        Particle.DUST_COLOR_TRANSITION, SEA_GLOW, true);
            }
        }

        /** The belly-flop: the big hit, a crown of water and a shockwave. */
        private void slam() {
            if (line != null && line.isValid()) {
                line.remove();
            }
            line = null;
            Location rest = end.clone();
            anchor = rest;
            poseFish(rest, 2, FISH_SCALE, 0, 2);
            Location center = ground.clone();
            crown(center);
            Vfx.dot(center.clone().add(0, 1, 0), Particle.FLASH, PALETTE.core(), true);
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.12, 0), 0.5, radius + 0.5, 6,
                    Particle.DUST_COLOR_TRANSITION, SEA_GLOW, true);
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.3, 0), 0.5, radius, 8, Particle.SPLASH, null, true);
            Vfx.cloud(ctx(), center.clone().add(0, 0.6, 0), Particle.SPLASH, 80, radius * 0.45, 0.5, null, true);
            Vfx.cloud(ctx(), center.clone().add(0, 0.2, 0), Particle.CLOUD, 24, radius * 0.4, 0.1, null, true);
            BlockData under = groundUnder(center.clone().add(0, 0.1, 0));
            if (under != null) {
                world.spawnParticle(Particle.BLOCK, center.clone().add(0, 0.2, 0), Vfx.n(ctx(), 50), radius * 0.4,
                        0.1, radius * 0.4, 0.4, under, true);
                world.spawnParticle(Particle.DUST_PILLAR, center.clone().add(0, 0.2, 0), Vfx.n(ctx(), 20),
                        radius * 0.35, 0.1, radius * 0.35, 0.2, under, true);
            }
            Fx.sound(center, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.8f, 0.5f);
            Fx.sound(center, Sound.ENTITY_GENERIC_SPLASH, 1.6f, 0.5f);
            Fx.sound(center, Sound.ENTITY_SALMON_FLOP, 1.8f, 0.5f);
            Fx.sound(center, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.9f, 0.6f);
            Fx.sound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0f, 0.8f);

            double damage = Math.max(0, params().num("ult_slam_damage"));
            long slow = params().ticks("ult_slow");
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center.clone().add(0, 0.5, 0), radius)) {
                // A stealthed enemy is hit all the same, but neither marked nor counted on the action bar.
                boolean seen = visible(owner, enemy);
                Combat.hit(ctx(), owner, enemy, damage);
                if (seen) {
                    hits++;
                }
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    away = leap.clone();
                }
                Motion.push(enemy, away.multiply(SLAM_PUSH).setY(SLAM_LIFT), MAX_SPEED);
                enemy.setFallDistance(0);
                if (slow > 0) {
                    enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                            (int) Math.min(Integer.MAX_VALUE, slow), 1, false, true, true));
                }
                if (seen) {
                    Vfx.burst(ctx(), Fx.body(enemy), PALETTE, 0.5, Particle.SPLASH, false);
                }
            }
            if (hits > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⚓ 대어!</b></" + COLOR_TAG + "> <white>" + hits
                        + "명 적중</white>"));
            }
        }

        /** After the slam: two flops, then the salmon sinks into the ground leaving ripples. */
        private void aftermath(long after) {
            if (after == FLOP_1 || after == FLOP_2) {
                Location hop = anchor.clone().add(0, after == FLOP_1 ? 0.9 : 0.5, 0);
                poseFish(hop, 3, FISH_SCALE, after == FLOP_1 ? -14 : 10, 3);
                ctx().runLater(owner, 3, () -> poseFish(anchor, 3, FISH_SCALE, 0, 3));
                Fx.sound(hop, Sound.ENTITY_SALMON_FLOP, 1.4f, after == FLOP_1 ? 0.6f : 0.75f);
                Vfx.cloud(ctx(), ground.clone().add(0, 0.3, 0), Particle.SPLASH, 30, radius * 0.3, 0.2, null, true);
            }
            if (after == SINK_START) {
                Location sunk = anchor.clone().subtract(0, FISH_REST_HEIGHT * 2.2, 0);
                poseFish(sunk, (int) SINK_TICKS, FISH_SCALE * 0.6f, 0, (int) SINK_TICKS);
                Fx.sound(ground, Sound.AMBIENT_UNDERWATER_ENTER, 1.0f, 0.7f);
                Fx.sound(ground, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 1.0f, 1.0f);
            }
            if (after >= SINK_START && after % 3 == 0) {
                Location floor = ground.clone().add(0, 0.15, 0);
                double fade = 1 - (double) (after - SINK_START) / SINK_TICKS;
                Vfx.ring(ctx(), floor, 0.8 + (after - SINK_START) * 0.25, 18, after * 0.1, Particle.DUST, FOAM,
                        false);
                Vfx.cloud(ctx(), floor, Particle.BUBBLE_POP, (int) Math.max(1, Math.round(8 * fade)), 1.2, 0.02,
                        null, false);
            }
        }

        /**
         * Moves the salmon to {@code at} over {@code moveTicks} and poses it at {@code scale}, pitched by {@code pitch}
         * (nose up when negative) over {@code poseTicks}. The fish turns as one body through its parts'
         * transformations; the displays themselves stay unrotated.
         */
        private void poseFish(Location at, int moveTicks, float scale, float pitch, int poseTicks) {
            for (int i = 0; i < fish.size(); i++) {
                BlockDisplay display = fish.get(i);
                if (!display.isValid()) {
                    continue;
                }
                display.setTeleportDuration(moveTicks);
                Displays.moveTo(display, at);
                Displays.animate(display, turned(fishPose(FISH[i], scale), fishYaw, pitch), poseTicks);
            }
        }

        /** Water columns bursting out of the ground around the salmon's breach, collapsing back after a moment. */
        private void waterColumns(Location center) {
            BlockData glass = WATER_GLASS.createBlockData();
            double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            for (int i = 0; i < COLUMNS; i++) {
                double angle = phase + i * Math.PI * 2 / COLUMNS;
                double r = radius * 0.45;
                Location at = center.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
                at.setYaw(0);
                at.setPitch(0);
                float tall = 2.6f + (i % 3) * 0.6f;
                BlockDisplay column = Displays.block(ctx(), owner, at, glass,
                        Displays.transform(0, 0.05f, 0, 0.55f, 0.1f, 0.55f, (float) Math.toDegrees(angle), 0, 0), 20);
                column.setViewRange(2.0f);
                Displays.animateNextTick(ctx(), owner, column, Displays.transform(0, tall / 2, 0, 0.6f, tall, 0.6f,
                        (float) Math.toDegrees(angle), 0, 0), 3);
                ctx().runLater(owner, 7, () -> {
                    if (column.isValid()) {
                        Displays.shrinkAway(ctx(), owner, column, 8);
                    }
                });
            }
        }

        /** A few glassy droplets thrown up and out by the breach, falling back on their own arcs. */
        private void sprayDroplets(Location center) {
            BlockData glass = WATER_GLASS.createBlockData();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < DROPLETS; i++) {
                double angle = random.nextDouble(Math.PI * 2);
                Vector velocity = new Vector(Math.cos(angle) * 0.25, 0.75 + random.nextDouble(0.3),
                        Math.sin(angle) * 0.25);
                Location at = center.clone().add(0, 0.5, 0);
                float size = 0.3f + random.nextFloat() * 0.15f;
                BlockDisplay drop = Displays.block(ctx(), owner, at, glass, Displays.scaled(size), DROPLET_TICKS + 4);
                drop.setTeleportDuration(1);
                droplets.add(new Droplet(drop, at.toVector(), velocity));
            }
        }

        private void updateDroplets(long t) {
            if (droplets.isEmpty()) {
                return;
            }
            for (Droplet droplet : droplets) {
                BlockDisplay display = droplet.display();
                if (!display.isValid()) {
                    continue;
                }
                if (t >= DROPLET_TICKS) {
                    display.remove();
                    continue;
                }
                Vector at = droplet.from().clone().add(droplet.velocity().clone().multiply(t))
                        .add(new Vector(0, -0.06 * t * t, 0));
                Location location = at.toLocation(world);
                Displays.moveTo(display, location);
                if (t % 2 == 0) {
                    Vfx.dot(location, Particle.FALLING_WATER, null, false);
                }
            }
            if (t >= DROPLET_TICKS) {
                droplets.clear();
            }
        }

        /** The crown splash: water plates tilted outwards, rising in a ring around the impact and falling away. */
        private void crown(Location center) {
            BlockData glass = WATER_GLASS.createBlockData();
            for (int i = 0; i < CROWN_PLATES; i++) {
                double angle = i * Math.PI * 2 / CROWN_PLATES;
                Location at = center.clone().add(Math.cos(angle) * radius * 0.7, 0, Math.sin(angle) * radius * 0.7);
                at.setYaw(0);
                at.setPitch(0);
                // A plate facing away from the center, its top leaning outwards like the rim of a splash.
                float yaw = (float) Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle)));
                BlockDisplay plate = Displays.block(ctx(), owner, at, glass,
                        Displays.transform(0, 0.05f, 0, 1.1f, 0.1f, 0.06f, yaw, 0, 0), 18);
                plate.setViewRange(2.0f);
                Displays.animateNextTick(ctx(), owner, plate, Displays.transform(0, 0.9f, 0, 1.3f, 1.8f, 0.06f, yaw,
                        CROWN_LEAN, 0), 3);
                ctx().runLater(owner, 6, () -> {
                    if (plate.isValid()) {
                        Displays.shrinkAway(ctx(), owner, plate, 7);
                    }
                });
            }
        }

        private Location curve(double s) {
            Vector p = start.toVector().multiply((1 - s) * (1 - s)).add(control.toVector().multiply(2 * (1 - s) * s))
                    .add(end.toVector().multiply(s * s));
            return p.toLocation(world);
        }

        private Vector tangent(double s) {
            Vector a = control.toVector().subtract(start.toVector()).multiply(2 * (1 - s));
            Vector b = end.toVector().subtract(control.toVector()).multiply(2 * s);
            Vector t = a.add(b);
            return t.lengthSquared() < 1.0E-8 ? leap.clone() : t;
        }

        private void drawLine(Location target) {
            BlockDisplay beam = line;
            if (beam == null || !beam.isValid() || !owner.getWorld().equals(world)) {
                return;
            }
            Location tip = rodTip(owner);
            Vector delta = target.toVector().subtract(tip.toVector());
            if (delta.lengthSquared() < 1.0E-4) {
                return;
            }
            Displays.moveTo(beam, tip);
            Displays.animate(beam, beamPose(delta, LINE_WIDTH * 1.5f), 1);
        }

        private void finish() {
            for (BlockDisplay display : fish) {
                if (display.isValid()) {
                    display.remove();
                }
            }
            fish.clear();
            for (BlockDisplay display : floats) {
                if (display.isValid()) {
                    display.remove();
                }
            }
            floats.clear();
            for (Droplet droplet : droplets) {
                if (droplet.display().isValid()) {
                    droplet.display().remove();
                }
            }
            droplets.clear();
            if (line != null && line.isValid()) {
                line.remove();
            }
        }
    }

    /** The Minecraft pitch (degrees, positive looks down) of a direction. */
    private static float pitchOf(Vector direction) {
        return (float) -Math.toDegrees(Math.atan2(direction.getY(), Math.hypot(direction.getX(), direction.getZ())));
    }

    /**
     * {@code local} (a pose in the rig's own frame, nose along +z) turned as part of a rigid body by {@code yaw} and
     * {@code pitch} (Minecraft degrees: yaw 0 = +z, 90 = -x; positive pitch looks down). The rotation lives in the
     * transformation only, so the display entity keeps yaw/pitch 0 and is moved with {@link Displays#moveTo}.
     */
    private static Transformation turned(Transformation local, float yaw, float pitch) {
        Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch));
        return new Transformation(turn.transform(new Vector3f(local.getTranslation())),
                new Quaternionf(turn).mul(local.getLeftRotation()), new Vector3f(local.getScale()),
                new Quaternionf(local.getRightRotation()));
    }

    /** One block of the salmon at {@code scale}, relative to the fish's anchor. */
    private static Transformation fishPose(FishPart part, float scale) {
        return Displays.transform(part.x() * scale, part.y() * scale, part.z() * scale, part.w() * scale,
                part.h() * scale, part.l() * scale, 0, part.pitch(), 0);
    }

    // ------------------------------------------------------------------ helpers

    /** A thin box of the given width from the block display's origin along {@code delta}. */
    private static Transformation beamPose(Vector delta, float width) {
        Vector3f direction = new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ());
        float length = direction.length();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction.normalize());
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
        return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
    }

    /** The solid block right under {@code at}, null over air, fluids and plants. */
    private static @Nullable BlockData groundUnder(Location at) {
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        return ground.getMaterial().isSolid() ? ground : null;
    }

    /** The Minecraft yaw (degrees) of a horizontal direction. */
    private static float yawOf(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** False for players hidden from the owner (stealth): they are never hooked, followed or marked. */
    private static boolean visible(Player owner, LivingEntity entity) {
        return !(entity instanceof Player player) || owner.canSee(player);
    }
}
