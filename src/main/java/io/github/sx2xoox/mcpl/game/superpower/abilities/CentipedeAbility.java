package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 왕지네 — the thousand-year-old giant centipede of the Jinae market tale, that took a maiden in sacrifice every year
 * until a grateful toad fought it off (the Japanese ōmukade legend is just as famous).
 *
 * <p>A body of segments wriggles along the centipede's footsteps and poisons whatever brushes against it. The active
 * lets the body grow long: wherever the centipede runs, a wall of its body rises behind it for a while — a light-cycle
 * game that cuts escape routes and blocks shots. The ultimate makes it the thousand-year-old king: bigger, faster,
 * with taller walls that throw back whoever runs into them; an enemy caught inside a closed ring of its body is
 * squeezed and poisoned, until two seconds later the nearest segment caves in and opens a gap (nobody is caged for
 * good).
 *
 * <p>Look: dark blood-red segments with lacquer-black plates and poison-yellow legs. The trailing body is a chain of
 * displays following the recorded path, swaying from side to side with legs scissoring as it runs. The walls are laid
 * as alternating red and black segments with yellow legs sprouting from their sides and a ripple of poison running along
 * them from tail to head; the head grows two yellow pincers that snap open and shut. A closed ring is announced to its
 * prisoner and the segment that will give way flickers until it crumbles.
 */
public final class CentipedeAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("centipede", "왕지네", Material.NETHER_WART)
            .color(TextColor.color(0xB0302A))
            .difficulty(3)
            .passive("백 개의 다리",
                    "내 발자취를 따라 몸통 마디 {segments}개가 꿈틀거리며 이어집니다. 내 몸이나 마디에 닿은 적은 "
                            + "{touch_damage} 피해를 입고 {touch_poison}초간 독에 걸립니다(같은 적은 {touch_cooldown}초마다).")
            .active("지네 길",
                    "{path_duration}초간 몸이 길게 자라 이동 속도가 {path_speed}% 빨라지고, 지나간 자리마다 높이 "
                            + "{wall_height}블록의 몸통 벽이 {wall_duration}초간 남습니다(최대 {wall_max}칸, 누가 서 있는 "
                            + "칸은 건너뜀). 벽은 이동과 투사체를 막고, 벽에 부딪힌 적은 {bump_damage} 피해를 입고 "
                            + "{bump_poison}초간 독에 걸립니다(같은 적은 {bump_cooldown}초마다).",
                    10)
            .ultimate("천 년 묵은 왕지네",
                    "{ult_duration}초간 몸이 {ult_scale}% 커지고 이동 속도가 {ult_speed}% 빨라지며, 지나간 자리에 높이 "
                            + "{ult_wall_height}블록의 벽이 {ult_wall_duration}초간 남습니다(최대 {ult_wall_max}칸). 벽에 "
                            + "닿은 적은 {ult_bump_damage} 피해를 입고 튕겨 나갑니다(적마다 {ult_bump_max}번). 내 벽에 "
                            + "완전히 둘러싸인 적은 1초마다 {ring_damage} 피해를 입고 {ring_poison}초간 독에 걸리며, "
                            + "고리가 닫히고 {ring_gap}초 뒤 가장 가까운 마디 하나가 무너져 틈이 열립니다. 벽과 고리를 "
                            + "합쳐 적마다 최대 {ult_damage_max} 피해를 줍니다.")
            .param("segments", 5)
            .param("touch_damage", 1)
            .param("touch_poison", 2)
            .param("touch_cooldown", 1)
            .param("path_duration", 3)
            .param("path_speed", 20)
            .param("wall_height", 2)
            .param("wall_duration", 4)
            .param("wall_max", 24)
            .param("bump_damage", 1)
            .param("bump_poison", 1)
            .param("bump_cooldown", 1)
            .param("ult_duration", 7)
            .param("ult_scale", 30)
            .param("ult_speed", 30)
            .param("ult_wall_height", 3)
            .param("ult_wall_duration", 6)
            .param("ult_wall_max", 60)
            .param("ult_bump_damage", 2)
            .param("ult_bump_max", 2)
            .param("ring_damage", 2)
            .param("ring_poison", 2)
            .param("ring_gap", 2)
            .param("ult_damage_max", 8)
            .build();

    private static final String RED_TAG = "#C2302A";
    private static final String YELLOW_TAG = "#E8C21C";

    // ------------------------------------------------------------------ look
    /** Poison yellow core, blood red body, lacquer black edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE8C21C, 0x6E1A1A, 0x1A1414);
    private static final Particle.DustOptions VENOM = new Particle.DustOptions(Color.fromRGB(0xE8C21C), 0.8f);
    private static final Particle.DustOptions VENOM_SMALL = new Particle.DustOptions(Color.fromRGB(0xD4B41A), 0.55f);
    private static final Particle.DustOptions BLOOD = new Particle.DustOptions(Color.fromRGB(0x6E1A1A), 1.0f);
    private static final Particle.DustOptions LACQUER = new Particle.DustOptions(Color.fromRGB(0x1A1414), 1.0f);
    private static final Particle.DustTransition POISON_GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition RIPPLE = new Particle.DustTransition(Color.fromRGB(0xF2D54A),
            Color.fromRGB(0x8A2A1A), 0.9f);

    /** Materials of the visuals and walls; block data is created when used (it needs a running server). */
    private static final Material SEGMENT = Material.NETHER_WART_BLOCK;
    private static final Material PLATE = Material.BLACK_CONCRETE;
    private static final Material LEGS = Material.YELLOW_TERRACOTTA;
    private static final Material PINCER = Material.YELLOW_CONCRETE;
    private static final Material WALL_RED = Material.NETHER_WART_BLOCK;
    private static final Material WALL_BLACK = Material.BLACKSTONE;

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 1.4;

    // passive
    private static final double SEGMENT_SPACING = 0.72;
    private static final double SAMPLE_STEP = 0.12;
    /** A jump in position larger than this (a teleport) lays the body out behind the centipede again. */
    private static final double TELEPORT_JUMP = 4;
    private static final double TOUCH_GROW = 0.12;
    private static final double SEGMENT_HALF = 0.36;
    private static final double SEGMENT_TOP = 0.48;
    private static final double SWAY = 0.09;
    private static final double LEG_SWING = 24;

    // walls
    private static final long PENDING_TICKS = 30;
    private static final double GROUND_PROBE = 3;
    private static final double BODY_MARGIN = 0.05;
    private static final double LAG_MARGIN_PER_TICK = 0.3;
    private static final double MAX_LAG_MARGIN = 1.2;
    private static final long MAX_LAG_TICKS = 8;
    private static final double WALL_TOUCH = 0.12;
    private static final long ULT_BUMP_SPACING = 10;
    private static final double BOUNCE = 0.85;
    private static final double BOUNCE_LIFT = 0.4;
    private static final long RIPPLE_PERIOD = 12;
    private static final long PINCER_SNAP = 6;

    // ring
    private static final long RING_CHECK = 10;
    private static final long RING_SQUEEZE = 20;
    private static final int RING_ESCAPE = 12;
    private static final int RING_MAX_NODES = 900;
    private static final int RING_MAX_DROP = 3;

    /** One trailing body segment: its displays. */
    private static final class Segment {
        @Nullable BlockDisplay body;
        @Nullable BlockDisplay plate;
        @Nullable BlockDisplay legs;
    }

    /** The recorded footsteps, newest first. */
    private final Deque<Vector> path = new ArrayDeque<>();
    private final List<Segment> segments = new ArrayList<>();
    private final Map<UUID, Long> nextTouch = new HashMap<>();
    private final List<Display> displays = new ArrayList<>();
    private @Nullable Trail trail;
    private boolean granted;
    private double sway;
    private float bodyGrow = 1f;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        granted = true;
        layOutBehind(player().getLocation());
    }

    @Override
    public void onRevoke() {
        granted = false;
        Trail running = trail;
        if (running != null) {
            running.stopLaying();
        }
        trail = null;
        path.clear();
        segments.clear();
        nextTouch.clear();
        displays.clear();
    }

    /** Package-private for tests: the cells (block columns) of the running trail's walls that still stand. */
    int wallBlocks() {
        Trail running = trail;
        return running == null ? 0 : running.walls.size();
    }

    // ------------------------------------------------------------------ passive: a hundred legs

    @Override
    public void onTick(long tick) {
        if (!granted) {
            return;
        }
        Player owner = player();
        record(owner.getLocation());
        boolean laying = trail != null && trail.laying;
        // While the walls are being laid, the body is the wall: the trailing segments draw in.
        float target = laying ? 0f : 1f;
        bodyGrow += Math.clamp(target - bodyGrow, -0.25f, 0.25f);
        placeSegments(owner, tick);
        if (tick % 2 == 0) {
            touch(owner);
        }
        if (tick % 40 == 0) {
            long now = ctx().currentTick();
            nextTouch.values().removeIf(next -> next <= now);
        }
    }

    /** A straight body behind the centipede (at the start, and after a teleport). */
    private void layOutBehind(Location at) {
        path.clear();
        Vector back = Motion.horizontalFacing(at).multiply(-1);
        int count = (int) Math.ceil((segmentCount() + 1) * SEGMENT_SPACING / SAMPLE_STEP);
        for (int i = 0; i <= count; i++) {
            path.addLast(at.toVector().add(back.clone().multiply(i * SAMPLE_STEP)));
        }
    }

    private int segmentCount() {
        return Math.clamp(params().integer("segments"), 0, 8);
    }

    private void record(Location at) {
        Vector now = at.toVector();
        Vector newest = path.peekFirst();
        if (newest == null || newest.distance(now) > TELEPORT_JUMP) {
            layOutBehind(at);
            return;
        }
        double moved = newest.distance(now);
        if (moved >= SAMPLE_STEP) {
            path.addFirst(now);
            sway += moved * 2.2;
        }
        double keep = (segmentCount() + 2) * SEGMENT_SPACING;
        double length = 0;
        Vector previous = null;
        int index = 0;
        for (Iterator<Vector> it = path.iterator(); it.hasNext(); index++) {
            Vector point = it.next();
            if (previous != null) {
                length += previous.distance(point);
            }
            previous = point;
            if (length > keep && index > 2) {
                it.remove();
            }
        }
    }

    /** The point {@code distance} blocks back along the path from the head, and the direction towards the head. */
    private @Nullable Vector[] along(Location head, double distance) {
        Vector previous = head.toVector();
        double walked = 0;
        for (Vector point : path) {
            double step = previous.distance(point);
            if (step < 1.0E-6) {
                continue;
            }
            if (walked + step >= distance) {
                double t = (distance - walked) / step;
                Vector at = previous.clone().add(point.clone().subtract(previous).multiply(t));
                Vector forward = previous.clone().subtract(point);
                return new Vector[] {at, forward};
            }
            walked += step;
            previous = point;
        }
        Vector last = path.peekLast();
        if (last == null) {
            return null;
        }
        Vector forward = Motion.horizontalFacing(head);
        return new Vector[] {last.clone(), forward};
    }

    private void placeSegments(Player owner, long tick) {
        int count = segmentCount();
        Location head = owner.getLocation();
        World world = owner.getWorld();
        float scale = (float) Body.scaleOf(owner);
        while (segments.size() < count) {
            segments.add(new Segment());
        }
        for (int i = 0; i < count; i++) {
            Segment segment = segments.get(i);
            Vector[] spot = along(head, (i + 1) * SEGMENT_SPACING * scale);
            if (spot == null) {
                continue;
            }
            Vector forward = spot[1];
            float yaw = yawOf(forward);
            float pitch = (float) Math.toDegrees(-Math.atan2(forward.getY(),
                    Math.max(1.0E-6, Math.hypot(forward.getX(), forward.getZ()))));
            pitch = Math.clamp(pitch, -40f, 40f);
            double phase = sway - i * 0.9;
            Vector side = new Vector(-Math.cos(Math.toRadians(yaw)), 0, -Math.sin(Math.toRadians(yaw)));
            Location at = spot[0].toLocation(world).add(side.multiply(Math.sin(phase) * SWAY * scale));
            float size = scale * (i == count - 1 ? 0.78f : 1f - i * 0.03f) * Math.max(0.001f, bodyGrow);
            float legYaw = (float) (Math.sin(phase * 1.7) * LEG_SWING);
            ensure(owner, segment, at, yaw, pitch, size, legYaw);
            if (segment.body != null) {
                Displays.moveTo(segment.body, at);
                Displays.animate(segment.body, bodyPose(yaw, pitch, size), 1);
            }
            if (segment.plate != null) {
                Displays.moveTo(segment.plate, at);
                Displays.animate(segment.plate, platePose(yaw, pitch, size), 1);
            }
            if (segment.legs != null) {
                Displays.moveTo(segment.legs, at);
                Displays.animate(segment.legs, legPose(yaw + legYaw, pitch, size), 1);
            }
            if (bodyGrow > 0.5f && tick % 8 == i % 8) {
                Vfx.dot(at.clone().add(0, 0.1, 0), Particle.DUST, VENOM_SMALL, false);
            }
        }
    }

    /** Spawns the segment's displays if they are missing (the first tick, or after the context removed them). */
    private void ensure(Player owner, Segment segment, Location at, float yaw, float pitch, float size, float legYaw) {
        if (segment.body == null || !segment.body.isValid()) {
            segment.body = reserve(1) ? keep(Displays.block(ctx(), owner, at, SEGMENT.createBlockData(),
                    bodyPose(yaw, pitch, size), 0)) : null;
            if (segment.body != null) {
                segment.body.setTeleportDuration(2);
            }
        }
        if (segment.plate == null || !segment.plate.isValid()) {
            segment.plate = reserve(1) ? keep(Displays.block(ctx(), owner, at, PLATE.createBlockData(),
                    platePose(yaw, pitch, size), 0)) : null;
            if (segment.plate != null) {
                segment.plate.setTeleportDuration(2);
            }
        }
        if (segment.legs == null || !segment.legs.isValid()) {
            segment.legs = reserve(1) ? keep(Displays.block(ctx(), owner, at, LEGS.createBlockData(),
                    legPose(yaw + legYaw, pitch, size), 0)) : null;
            if (segment.legs != null) {
                segment.legs.setTeleportDuration(2);
            }
        }
    }

    private static Transformation bodyPose(float yaw, float pitch, float size) {
        return part(yaw, pitch, 0, 0.22f * size, 0, 0.6f * size, 0.36f * size, 0.62f * size);
    }

    private static Transformation platePose(float yaw, float pitch, float size) {
        return part(yaw, pitch, 0, 0.42f * size, -0.02f * size, 0.52f * size, 0.06f * size, 0.5f * size);
    }

    private static Transformation legPose(float yaw, float pitch, float size) {
        return part(yaw, pitch, 0, 0.1f * size, 0, 1.15f * size, 0.05f * size, 0.08f * size);
    }

    /** Enemies brushing against the centipede or its segments are bitten by the legs. */
    private void touch(Player owner) {
        long now = ctx().currentTick();
        double scale = Body.scaleOf(owner);
        double reach = (segmentCount() + 1) * SEGMENT_SPACING * scale + 2;
        List<BoundingBox> parts = new ArrayList<>();
        parts.add(owner.getBoundingBox().expand(TOUCH_GROW));
        if (bodyGrow > 0.5f) {
            for (Segment segment : segments) {
                if (segment.body != null && segment.body.isValid()) {
                    Location at = segment.body.getLocation();
                    double half = SEGMENT_HALF * scale;
                    parts.add(new BoundingBox(at.getX() - half, at.getY(), at.getZ() - half, at.getX() + half,
                            at.getY() + SEGMENT_TOP * scale, at.getZ() + half).expand(TOUCH_GROW));
                }
            }
        }
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), reach)) {
            Long next = nextTouch.get(enemy.getUniqueId());
            if (next != null && now < next) {
                continue;
            }
            BoundingBox box = enemy.getBoundingBox();
            BoundingBox touched = null;
            for (BoundingBox partBox : parts) {
                if (partBox.overlaps(box)) {
                    touched = partBox;
                    break;
                }
            }
            if (touched == null) {
                continue;
            }
            nextTouch.put(enemy.getUniqueId(), now + params().ticks("touch_cooldown"));
            Combat.hit(ctx(), owner, enemy, params().num("touch_damage"));
            poison(enemy, params().ticks("touch_poison"));
            if (!isHidden(enemy)) {
                Location at = touched.getCenter().toLocation(owner.getWorld());
                legBite(at);
            }
        }
    }

    /** A flurry of yellow legs: the passive's bite. */
    private void legBite(Location at) {
        World world = at.getWorld();
        world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 6), 0.25, 0.2, 0.25, 0, VENOM);
        world.spawnParticle(Particle.CRIT, at, Vfx.n(ctx(), 4), 0.2, 0.2, 0.2, 0.1);
        world.spawnParticle(Particle.ITEM_SLIME, at, Vfx.n(ctx(), 2), 0.15, 0.1, 0.15, 0);
        Fx.sound(at, Sound.ENTITY_SPIDER_STEP, 0.9f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_SILVERFISH_HURT, 0.6f, 0.6f + Fx.jitter(0.08f));
    }

    private void poison(LivingEntity enemy, long ticks) {
        if (ticks <= 0 || !Combat.isTargetable(ctx(), player(), enemy)) {
            return;
        }
        int duration = (int) Math.min(Integer.MAX_VALUE, ticks);
        PotionEffect current = enemy.getPotionEffect(PotionEffectType.POISON);
        if (current == null || current.getAmplifier() == 0 && current.getDuration() < duration) {
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.POISON, duration, 0, false, true, true));
        }
    }

    // ------------------------------------------------------------------ active: the centipede's road

    @Override
    public ActivationResult onActive() {
        Trail running = trail;
        if (running != null && running.laying) {
            return ActivationResult.fail("이미 몸이 자라나고 있습니다");
        }
        Player owner = player();
        Trail road = new Trail(owner, false);
        trail = road;
        road.start();
        return ActivationResult.success();
    }

    // ------------------------------------------------------------------ ultimate: the thousand-year-old king

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Trail running = trail;
        if (running != null && running.laying) {
            if (running.king) {
                return ActivationResult.fail("이미 왕지네가 되어 있습니다");
            }
            // The road in progress becomes the king's: it stops growing, the king's body takes over.
            running.stopLaying();
        }
        Trail king = new Trail(owner, true);
        trail = king;
        king.start();
        return ActivationResult.success();
    }

    /** One wall block the trail placed: what it replaced (to open a gap early) and its column. */
    private record Placed(Block block, BlockData original, long column, int base) {
    }

    /**
     * The trail of the centipede's body: walls rise in the cells it leaves behind, enemies that run into them are
     * bitten (and, for the king, thrown back), and the king squeezes whoever is ringed in. The walls are temporary
     * blocks with their own lifetime, so they also go away if the centipede falls; the trail keeps checking them until
     * the last one is gone.
     */
    private final class Trail implements LongPredicate {

        private final Player owner;
        private final boolean king;
        private final long layTicks;
        private final long wallTicks;
        private final int height;
        private final int maxCells;
        private final Map<Long, Placed> walls = new LinkedHashMap<>();
        private final Map<Long, List<Placed>> columns = new LinkedHashMap<>();
        private final Map<Long, Long> columnExpiry = new HashMap<>();
        private final Deque<long[]> pending = new ArrayDeque<>();
        private final Map<UUID, Long> nextBump = new HashMap<>();
        private final Map<UUID, Integer> bumps = new HashMap<>();
        private final Map<UUID, Double> dealt = new HashMap<>();
        private final Map<UUID, Ring> rings = new HashMap<>();
        private final List<BlockDisplay> pincers = new ArrayList<>();
        private long lastCell = Long.MIN_VALUE;
        private int lastBase;
        private int cells;
        private long rippleStart = Long.MIN_VALUE / 2;
        private boolean laying = true;
        private boolean done;

        Trail(Player owner, boolean king) {
            this.owner = owner;
            this.king = king;
            this.layTicks = Math.max(1, params().ticks(king ? "ult_duration" : "path_duration"));
            this.wallTicks = Math.max(1, params().ticks(king ? "ult_wall_duration" : "wall_duration"));
            this.height = Math.clamp(params().integer(king ? "ult_wall_height" : "wall_height"), 1, 6);
            this.maxCells = Math.max(0, params().integer(king ? "ult_wall_max" : "wall_max"));
        }

        void start() {
            double speed = Math.max(0, params().num(king ? "ult_speed" : "path_speed")) / 100.0;
            if (speed > 0) {
                ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1,
                        layTicks);
            }
            if (king) {
                double scale = Math.max(0, params().num("ult_scale")) / 100.0;
                if (scale > 0) {
                    ctx().addModifier(owner, owner, Attribute.SCALE, scale, Operation.MULTIPLY_SCALAR_1, layTicks);
                }
            }
            for (int side = -1; side <= 1 && reserve(1); side += 2) {
                BlockDisplay pincer = keep(Displays.block(ctx(), owner, Displays.unrotated(owner.getLocation()),
                        PINCER.createBlockData(), pincerPose(owner.getBodyYaw(), side, 0.6f, 0.05f), layTicks + 8));
                pincer.setTeleportDuration(1);
                pincers.add(pincer);
            }
            Location feet = owner.getLocation();
            Location chest = Fx.body(owner);
            World world = chest.getWorld();
            Vfx.dot(chest, Particle.FLASH, PALETTE.core(), king);
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.4, king ? 3.5 : 2.0, 6,
                    Particle.DUST_COLOR_TRANSITION, POISON_GLOW, king);
            world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), king ? 24 : 12), 0.5, 0.2, 0.5, 0,
                    WALL_RED.createBlockData(), king);
            if (king) {
                Vfx.helix(ctx(), feet, 1.0, owner.getHeight() * 1.3, 2, 2, 16, 0, Particle.DUST, BLOOD, true);
                Fx.sound(chest, Sound.ENTITY_RAVAGER_ROAR, 1.2f, 1.3f);
                Fx.sound(chest, Sound.ENTITY_SILVERFISH_AMBIENT, 1.4f, 0.4f);
                Fx.sound(chest, Sound.ENTITY_SPIDER_AMBIENT, 1.0f, 0.5f);
                owner.sendActionBar(Text.mm("<" + RED_TAG + "><b>천 년 묵은 왕지네</b></" + RED_TAG + "> <gray>"
                        + Fx.seconds(layTicks) + "초 · 달린 길이 벽이 됩니다 · 적을 둘러싸세요</gray>"));
            } else {
                Fx.sound(chest, Sound.ENTITY_SILVERFISH_AMBIENT, 1.0f, 0.7f);
                Fx.sound(chest, Sound.ENTITY_SPIDER_STEP, 1.0f, 0.8f);
                owner.sendActionBar(Text.mm("<" + RED_TAG + "><b>지네 길</b></" + RED_TAG + "> <gray>"
                        + Fx.seconds(layTicks) + "초 · 달린 길이 벽이 됩니다</gray>"));
            }
            Loop.repeat(ctx(), owner, 1, 1, layTicks + wallTicks + PENDING_TICKS + 2, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            long now = ctx().currentTick();
            if (laying && tick >= layTicks) {
                stopLaying();
            }
            if (laying) {
                follow(owner.getLocation());
                pinch(tick);
            }
            placePending(now);
            freeTrapped();
            expire(now);
            bump(now);
            if (king && tick % RING_CHECK == 0) {
                checkRings(now);
            }
            if (king) {
                squeeze(now);
            }
            ripple(tick, now);
            // Done once nothing is laid any more and the last wall is gone.
            return laying || !walls.isEmpty() || !pending.isEmpty();
        }

        void stopLaying() {
            if (!laying) {
                return;
            }
            laying = false;
            if (lastCell != Long.MIN_VALUE) {
                pending.addLast(new long[] {lastCell, lastBase, ctx().currentTick()});
            }
            for (BlockDisplay pincer : pincers) {
                if (pincer.isValid()) {
                    Displays.shrinkAway(ctx(), owner, pincer, 4);
                }
            }
            pincers.clear();
        }

        void finish() {
            done = true;
            rings.clear();
            if (trail == this) {
                trail = null;
            }
        }

        // ---------------------------------------------------------------- laying the body

        /** A new cell under the centipede: the cell it left is queued for a wall. */
        private void follow(Location feet) {
            int x = feet.getBlockX();
            int z = feet.getBlockZ();
            long column = column(x, z);
            if (column == lastCell) {
                return;
            }
            Integer base = groundBase(feet);
            if (lastCell != Long.MIN_VALUE) {
                pending.addLast(new long[] {lastCell, lastBase, ctx().currentTick()});
            }
            lastCell = base == null ? Long.MIN_VALUE : column;
            lastBase = base == null ? 0 : base;
        }

        /** The y of the first free block above the ground under {@code feet} (null over a deep drop). */
        private @Nullable Integer groundBase(Location feet) {
            World world = feet.getWorld();
            int x = feet.getBlockX();
            int z = feet.getBlockZ();
            int y = (int) Math.floor(feet.getY() + 0.01);
            for (int down = 0; down <= GROUND_PROBE; down++) {
                Block below = world.getBlockAt(x, y - down - 1, z);
                if (!below.isPassable()) {
                    return y - down;
                }
            }
            return null;
        }

        /** Raises the queued walls whose cells nobody stands in any more (old ones that never cleared are dropped). */
        private void placePending(long now) {
            if (pending.isEmpty()) {
                return;
            }
            List<BoundingBox> bodies = bodiesNear();
            for (Iterator<long[]> it = pending.iterator(); it.hasNext(); ) {
                long[] cell = it.next();
                if (cells >= maxCells || now - cell[2] > PENDING_TICKS) {
                    it.remove();
                    continue;
                }
                if (columns.containsKey(cell[0])) {
                    it.remove();
                    continue;
                }
                int x = (int) (cell[0] >> 32);
                int z = (int) cell[0];
                int base = (int) cell[1];
                BoundingBox columnBox = new BoundingBox(x, base, z, x + 1, base + height, z + 1);
                boolean occupied = false;
                for (BoundingBox body : bodies) {
                    if (body.overlaps(columnBox)) {
                        occupied = true;
                        break;
                    }
                }
                if (occupied) {
                    continue;
                }
                it.remove();
                raise(x, base, z);
            }
        }

        private void raise(int x, int base, int z) {
            World world = owner.getWorld();
            long column = column(x, z);
            boolean black = (cells & 1) == 1;
            List<Placed> placed = new ArrayList<>();
            for (int dy = 0; dy < height; dy++) {
                Block block = world.getBlockAt(x, base + dy, z);
                if (!ctx().isInsideArena(block.getLocation().add(0.5, 0.5, 0.5))) {
                    break;
                }
                BlockData original = block.getBlockData();
                Material material = black ^ (dy == height - 1 && height > 2) ? WALL_BLACK : WALL_RED;
                if (!ctx().placeTempBlock(block, material.createBlockData(), wallTicks)) {
                    break;
                }
                Placed wall = new Placed(block, original, column, base);
                walls.put(key(x, base + dy, z), wall);
                placed.add(wall);
            }
            if (placed.isEmpty()) {
                return;
            }
            cells++;
            columns.put(column, placed);
            columnExpiry.put(column, ctx().currentTick() + wallTicks);
            Location middle = new Location(world, x + 0.5, base + height * 0.5, z + 0.5);
            world.spawnParticle(Particle.BLOCK, middle, Vfx.n(ctx(), 6), 0.3, height * 0.3, 0.3, 0,
                    WALL_RED.createBlockData());
            // Yellow legs sprout from both sides of the new segment.
            Vector heading = Motion.horizontalFacing(owner.getLocation());
            Vector side = new Vector(-heading.getZ(), 0, heading.getX());
            for (int s = -1; s <= 1; s += 2) {
                Location from = middle.clone().add(side.clone().multiply(0.5 * s)).subtract(0, height * 0.3, 0);
                Vfx.line(ctx(), from, from.clone().add(side.clone().multiply(0.45 * s)).subtract(0, 0.3, 0), 6,
                        Particle.DUST, VENOM_SMALL, false);
            }
            if (cells % 2 == 0) {
                Fx.sound(middle, Sound.BLOCK_WART_BLOCK_BREAK, 0.8f, 0.6f + Fx.jitter(0.1f));
                Fx.sound(middle, Sound.ENTITY_SILVERFISH_STEP, 0.8f, 0.7f + Fx.jitter(0.1f));
            }
        }

        /** Living bodies around (a player's grown by how far its client may be ahead of the server). */
        private List<BoundingBox> bodiesNear() {
            List<BoundingBox> bodies = new ArrayList<>();
            Location at = owner.getLocation();
            double reach = 12;
            for (Entity entity : at.getWorld().getNearbyEntities(at, reach, reach, reach)) {
                if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                    double margin = BODY_MARGIN + (living instanceof Player player
                            ? Math.min(MAX_LAG_MARGIN, LAG_MARGIN_PER_TICK * lagTicks(player)) : 0);
                    bodies.add(living.getBoundingBox().expand(margin, 0, margin));
                }
            }
            return bodies;
        }

        /** Nobody is ever stuck in a wall: a block someone stands in opens at once. */
        private void freeTrapped() {
            if (walls.isEmpty()) {
                return;
            }
            Location at = owner.getLocation();
            double reach = 40;
            for (Entity entity : at.getWorld().getNearbyEntities(at, reach, reach, reach)) {
                if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                    continue;
                }
                BoundingBox box = living.getBoundingBox();
                for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX() - 1.0E-7); x++) {
                    for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY() - 1.0E-7); y++) {
                        for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ() - 1.0E-7);
                             z++) {
                            Placed wall = walls.get(key(x, y, z));
                            if (wall != null) {
                                openColumn(wall.column(), false);
                            }
                        }
                    }
                }
            }
        }

        /** Walls whose time is up crumble (the temporary block service restores them; this keeps the books). */
        private void expire(long now) {
            for (Iterator<Map.Entry<Long, Long>> it = columnExpiry.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, Long> entry = it.next();
                if (entry.getValue() > now) {
                    continue;
                }
                it.remove();
                List<Placed> column = columns.remove(entry.getKey());
                if (column == null) {
                    continue;
                }
                for (Placed wall : column) {
                    walls.remove(key(wall.block().getX(), wall.block().getY(), wall.block().getZ()));
                }
                Placed bottom = column.getFirst();
                Location middle = bottom.block().getLocation().add(0.5, column.size() * 0.5, 0.5);
                middle.getWorld().spawnParticle(Particle.BLOCK_CRUMBLE, middle, Vfx.n(ctx(), 3), 0.3,
                        column.size() * 0.3, 0.3, 0, WALL_RED.createBlockData());
            }
        }

        /** Opens one wall column now (a gap in a ring, or someone stuck in it): the blocks show what they replaced. */
        private void openColumn(long column, boolean caveIn) {
            List<Placed> blocks = columns.remove(column);
            columnExpiry.remove(column);
            if (blocks == null) {
                return;
            }
            Location middle = null;
            for (Placed wall : blocks) {
                Block block = wall.block();
                walls.remove(key(block.getX(), block.getY(), block.getZ()));
                Material shown = block.getType();
                if (shown == WALL_RED || shown == WALL_BLACK) {
                    block.setBlockData(wall.original(), false);
                }
                middle = block.getLocation().add(0.5, 0.5, 0.5);
                block.getWorld().spawnParticle(Particle.BLOCK, middle, Vfx.n(ctx(), caveIn ? 10 : 4), 0.3, 0.3, 0.3,
                        0, WALL_RED.createBlockData());
            }
            if (middle != null && caveIn) {
                Fx.sound(middle, Sound.BLOCK_WART_BLOCK_BREAK, 1.2f, 0.5f);
                Fx.sound(middle, Sound.ENTITY_SILVERFISH_DEATH, 0.9f, 0.6f + Fx.jitter(0.05f));
            }
        }

        // ---------------------------------------------------------------- running into the body

        private void bump(long now) {
            if (walls.isEmpty()) {
                return;
            }
            double reach = 48;
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), reach)) {
                UUID id = enemy.getUniqueId();
                Long next = nextBump.get(id);
                if (next != null && now < next) {
                    continue;
                }
                Placed wall = touching(enemy);
                if (wall == null) {
                    continue;
                }
                if (king) {
                    int count = bumps.getOrDefault(id, 0);
                    if (count >= Math.max(0, params().integer("ult_bump_max"))) {
                        continue;
                    }
                    bumps.put(id, count + 1);
                    nextBump.put(id, now + ULT_BUMP_SPACING);
                    hurt(enemy, params().num("ult_bump_damage"));
                    bounce(enemy, wall);
                } else {
                    nextBump.put(id, now + params().ticks("bump_cooldown"));
                    Combat.hit(ctx(), owner, enemy, params().num("bump_damage"));
                    poison(enemy, params().ticks("bump_poison"));
                }
                if (!isHidden(enemy)) {
                    Location at = enemy.getLocation().add(0, enemy.getHeight() * 0.4, 0);
                    legBite(at);
                }
            }
        }

        /** The wall block an enemy's body touches, null if none. */
        private @Nullable Placed touching(LivingEntity enemy) {
            BoundingBox box = enemy.getBoundingBox().expand(WALL_TOUCH);
            for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
                for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++) {
                    for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                        Placed wall = walls.get(key(x, y, z));
                        if (wall != null && BoundingBox.of(wall.block()).overlaps(box)) {
                            return wall;
                        }
                    }
                }
            }
            return null;
        }

        /** The king's body throws back whoever runs into it. */
        private void bounce(LivingEntity enemy, Placed wall) {
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            Vector away = Motion.horizontal(enemy.getLocation().toVector()
                    .subtract(wall.block().getLocation().add(0.5, 0, 0.5).toVector()));
            if (away.lengthSquared() < 1.0E-6) {
                away = Motion.horizontal(enemy.getLocation().getDirection()).multiply(-1);
            }
            Motion.push(enemy, away.multiply(BOUNCE).setY(BOUNCE_LIFT), MAX_SPEED);
        }

        /** The king's damage on one enemy, capped over the whole ultimate. */
        private void hurt(LivingEntity enemy, double amount) {
            double cap = Math.max(0, params().num("ult_damage_max"));
            double so = dealt.getOrDefault(enemy.getUniqueId(), 0.0);
            double hit = Math.min(amount, cap - so);
            if (hit <= 1.0E-6) {
                return;
            }
            dealt.put(enemy.getUniqueId(), so + hit);
            Combat.hit(ctx(), owner, enemy, hit);
        }

        // ---------------------------------------------------------------- the ring

        /** One enemy ringed in by the king's body. */
        private final class Ring {
            final LivingEntity enemy;
            final long closedAt;
            long nextSqueeze;
            long gapColumn;
            boolean opened;

            Ring(LivingEntity enemy, long closedAt) {
                this.enemy = enemy;
                this.closedAt = closedAt;
                this.nextSqueeze = closedAt + RING_SQUEEZE;
                this.gapColumn = nearestColumn(enemy);
            }
        }

        private void checkRings(long now) {
            if (columns.isEmpty()) {
                rings.clear();
                return;
            }
            Set<UUID> still = new HashSet<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), RING_ESCAPE + 16)) {
                if (!enclosed(enemy)) {
                    continue;
                }
                UUID id = enemy.getUniqueId();
                still.add(id);
                Ring ring = rings.get(id);
                if (ring == null || ring.opened) {
                    ring = new Ring(enemy, now);
                    rings.put(id, ring);
                    closed(ring);
                }
            }
            rings.keySet().retainAll(still);
        }

        /** The ring just closed: the prisoner is told it will open in a moment, everyone hears the coils tighten. */
        private void closed(Ring ring) {
            LivingEntity enemy = ring.enemy;
            long gap = params().ticks("ring_gap");
            if (!isHidden(enemy)) {
                Location at = Fx.body(enemy);
                Vfx.ring(ctx(), enemy.getLocation().add(0, 0.1, 0), 1.2, 16, 0, Particle.DUST_COLOR_TRANSITION,
                        POISON_GLOW, true);
                Fx.sound(at, Sound.ENTITY_SILVERFISH_AMBIENT, 1.2f, 0.5f);
                Fx.sound(at, Sound.BLOCK_WART_BLOCK_PLACE, 1.0f, 0.5f);
            }
            if (enemy instanceof Player victim) {
                victim.sendActionBar(Text.mm("<" + RED_TAG + "><b>왕지네에게 둘러싸였습니다!</b></" + RED_TAG + "> <gray>"
                        + Fx.seconds(gap) + "초 뒤 가장 가까운 마디가 무너집니다</gray>"));
            }
            if (!isHidden(enemy)) {
                owner.sendActionBar(Text.mm("<" + YELLOW_TAG + ">고리가 닫혔습니다</" + YELLOW_TAG
                        + "> <white><name></white>", Placeholder.component("name", enemy.name())));
            }
        }

        /** Ringed enemies are squeezed every second; the nearest segment gives way when their time is up. */
        private void squeeze(long now) {
            long gap = params().ticks("ring_gap");
            for (Ring ring : List.copyOf(rings.values())) {
                LivingEntity enemy = ring.enemy;
                if (ring.opened || !Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                if (now >= ring.nextSqueeze) {
                    ring.nextSqueeze = now + RING_SQUEEZE;
                    hurt(enemy, params().num("ring_damage"));
                    poison(enemy, params().ticks("ring_poison"));
                    if (!isHidden(enemy)) {
                        Location body = Fx.body(enemy);
                        body.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 8), 0.3, 0.5,
                                0.3, 0, POISON_GLOW);
                        Fx.sound(body, Sound.ENTITY_SPIDER_HURT, 0.8f, 0.6f + Fx.jitter(0.08f));
                    }
                }
                if (!columns.containsKey(ring.gapColumn)) {
                    ring.gapColumn = nearestColumn(enemy);
                }
                if (now - ring.closedAt >= gap) {
                    ring.opened = true;
                    openColumn(ring.gapColumn, true);
                    if (enemy instanceof Player victim) {
                        victim.sendActionBar(Text.mm("<" + YELLOW_TAG + ">마디가 무너져 틈이 열렸습니다!</" + YELLOW_TAG
                                + ">"));
                    }
                } else if ((now - ring.closedAt) % 4 == 0 && !isHidden(enemy)) {
                    // The segment that will give way flickers (not around a hidden prisoner: it would give it away).
                    List<Placed> column = columns.get(ring.gapColumn);
                    if (column != null && !column.isEmpty()) {
                        Block bottom = column.getFirst().block();
                        Location at = bottom.getLocation().add(0.5, column.size() * 0.5, 0.5);
                        at.getWorld().spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 6), 0.35, column.size() * 0.3,
                                0.35, 0, VENOM, true);
                        at.getWorld().spawnParticle(Particle.BLOCK_CRUMBLE, at, Vfx.n(ctx(), 2), 0.3, 0.4, 0.3, 0,
                                WALL_RED.createBlockData());
                    }
                }
            }
        }

        private long nearestColumn(LivingEntity enemy) {
            Location at = enemy.getLocation();
            long best = Long.MIN_VALUE;
            double bestDistance = Double.MAX_VALUE;
            for (long column : columns.keySet()) {
                int x = (int) (column >> 32);
                int z = (int) column;
                double distance = Math.hypot(x + 0.5 - at.getX(), z + 0.5 - at.getZ());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = column;
                }
            }
            return best;
        }

        /**
         * True if the enemy cannot walk out: a flood fill over the cells it can stand in (stepping up one block,
         * dropping a few) never gets far from where it stands, and the centipede's own walls are part of what holds
         * it in.
         */
        private boolean enclosed(LivingEntity enemy) {
            World world = enemy.getWorld();
            Location feet = enemy.getLocation();
            int sx = feet.getBlockX();
            int sy = (int) Math.floor(feet.getY() + 0.01);
            int sz = feet.getBlockZ();
            Deque<int[]> queue = new ArrayDeque<>();
            Set<Long> visited = new HashSet<>();
            queue.add(new int[] {sx, sy, sz});
            visited.add(key(sx, sy, sz));
            boolean touchedWall = false;
            int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            while (!queue.isEmpty()) {
                int[] node = queue.poll();
                if (Math.abs(node[0] - sx) > RING_ESCAPE || Math.abs(node[2] - sz) > RING_ESCAPE) {
                    return false;
                }
                if (visited.size() > RING_MAX_NODES) {
                    return false;
                }
                for (int[] direction : directions) {
                    int nx = node[0] + direction[0];
                    int nz = node[2] + direction[1];
                    int ny = node[1];
                    if (walls.containsKey(key(nx, ny, nz)) || walls.containsKey(key(nx, ny + 1, nz))) {
                        touchedWall = true;
                        continue;
                    }
                    int[] next = null;
                    if (free(world, nx, ny, nz) && free(world, nx, ny + 1, nz)) {
                        int gy = ny;
                        int drop = 0;
                        while (free(world, nx, gy - 1, nz) && drop < RING_MAX_DROP) {
                            gy--;
                            drop++;
                        }
                        if (free(world, nx, gy - 1, nz)) {
                            // A deep drop: a way out.
                            return false;
                        }
                        next = new int[] {nx, gy, nz};
                    } else if (!free(world, nx, ny, nz) && free(world, nx, ny + 1, nz) && free(world, nx, ny + 2, nz)
                            && free(world, node[0], ny + 2, node[2])) {
                        if (walls.containsKey(key(nx, ny + 2, nz))) {
                            touchedWall = true;
                            continue;
                        }
                        next = new int[] {nx, ny + 1, nz};
                    }
                    if (next != null && visited.add(key(next[0], next[1], next[2]))) {
                        queue.add(next);
                    }
                }
            }
            return touchedWall;
        }

        private boolean free(World world, int x, int y, int z) {
            if (walls.containsKey(key(x, y, z))) {
                return false;
            }
            return world.getBlockAt(x, y, z).isPassable();
        }

        // ---------------------------------------------------------------- looks

        /** The head's pincers snap open and shut while the body grows. */
        private void pinch(long tick) {
            if (pincers.isEmpty()) {
                return;
            }
            float yaw = owner.getBodyYaw();
            float open = (tick / PINCER_SNAP) % 2 == 0 ? 1f : 0.35f;
            float scale = (float) Body.scaleOf(owner);
            Location feet = Displays.unrotated(owner.getLocation());
            for (int i = 0; i < pincers.size(); i++) {
                BlockDisplay pincer = pincers.get(i);
                Displays.moveTo(pincer, feet);
                if (tick % 2 == 0) {
                    Displays.animate(pincer, pincerPose(yaw, i == 0 ? -1 : 1, open, scale), 2);
                }
            }
            if (tick % PINCER_SNAP == 0 && open < 1f) {
                Fx.sound(owner.getLocation(), Sound.ENTITY_SPIDER_STEP, 0.6f, 1.8f + Fx.jitter(0.1f));
            }
        }

        /** A ripple of poison runs along the walls from the tail to the head. */
        private void ripple(long tick, long now) {
            if (columns.isEmpty()) {
                return;
            }
            if (tick % RIPPLE_PERIOD == 0) {
                rippleStart = now;
            }
            long age = now - rippleStart;
            int index = (int) (age * 3);
            int i = 0;
            for (List<Placed> column : columns.values()) {
                if (i >= index - 2 && i <= index) {
                    Placed top = column.getLast();
                    Location at = top.block().getLocation().add(0.5, 1.08, 0.5);
                    Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, RIPPLE, false);
                }
                i++;
                if (i > index) {
                    break;
                }
            }
        }
    }

    /** One of the two pincers: a yellow hook curving forward from the head, {@code open} spreads it. */
    private static Transformation pincerPose(float yaw, int side, float open, float scale) {
        float spread = 18f + 26f * open;
        return part(yaw + side * spread, -12f, 0.16f * side * scale, 0.55f * scale, 0.42f * scale, 0.07f * scale,
                0.07f * scale, 0.5f * scale);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A block display box of size (sx, sy, sz) centered on the local point (lx, ly, lz), in a frame turned by
     * {@code yaw} (0 = facing +Z, local +X is the left hand side) and tilted by {@code pitch} (positive = nose down).
     * All of it lives in the transformation: the display itself stays unrotated.
     */
    private static Transformation part(float yaw, float pitch, float lx, float ly, float lz, float sx, float sy,
                                       float sz) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch));
        Vector3f translation = rotation.transform(new Vector3f(lx - sx / 2, ly - sy / 2, lz - sz / 2));
        return new Transformation(translation, rotation, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}, 0 if it has none. */
    private static float yawOf(Vector direction) {
        if (Math.abs(direction.getX()) < 1.0E-9 && Math.abs(direction.getZ()) < 1.0E-9) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / 50.0));
    }

    /** True for a player the centipede cannot see (stealth): no visual may give it away. */
    private boolean isHidden(LivingEntity entity) {
        return entity instanceof Player hidden && !player().canSee(hidden);
    }

    private <T extends Display> T keep(T display) {
        displays.add(display);
        return display;
    }

    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }
}
