package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 화가 — a battlefield painter. Standing on its own paint inspires it (faster movement), the active throws a glob of
 * paint whose colour cycles red (enemies burn), blue (enemies are slowed) and green (the owner's side heals) and leaves
 * a puddle of it, and the ultimate paints a masterpiece on a giant canvas and slams it onto the ground, covering a wide
 * circle in all three colours at once.
 *
 * <p>Look (red, blue and green paint on a white canvas in a gilded frame): an inspired painter leaves rainbow
 * footprints. The paint glob is a spinning blob of coloured concrete trailing drips; it lands with a splat of paint
 * crumbs and spreads into an irregular puddle (overlapping plates, an inner sheen and stray droplets) that glistens
 * until it dries. The masterpiece rises as a framed canvas facing the painter, three brush strokes are swept across it
 * by a floating brush, a rainbow "걸작!" pops above it, then it tips over face-up onto the ground and the paint floods
 * out into a slowly turning red-blue-green bullseye with a rainbow rim, before it dries away in coloured motes.
 */
public final class PainterAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("painter", "화가", Material.PAINTING)
            .color(TextColor.color(0xFF5FA2))
            .difficulty(2)
            .passive("영감",
                    "내 물감 위에 서 있으면 영감이 떠올라 이동 속도가 {inspire_speed}% 빨라집니다(물감을 벗어나도 "
                            + "{inspire_linger}초 유지).")
            .active("물감 투척",
                    "물감 덩어리를 던져({throw_range}블록 이내) 착지 지점 반경 {puddle_radius}블록의 적에게 {splash_damage} "
                            + "피해를 주고 {puddle_duration}초간 웅덩이를 남깁니다. 색은 던질 때마다 빨강(적 {puddle_interval}초마다 "
                            + "{red_damage} 피해) → 파랑(적 {blue_slow}% 둔화) → 초록(나와 아군 {puddle_interval}초마다 "
                            + "{green_heal} 회복) 순서로 바뀝니다.",
                    7)
            .ultimate("걸작",
                    "거대한 캔버스에 걸작을 그려 바닥에 내리치고, 반경 {ult_radius}블록을 {ult_duration}초간 세 가지 물감으로 "
                            + "뒤덮습니다. 내리칠 때 적에게 {ult_splash} 피해를 주고, 그 안의 적은 {ult_interval}초마다 "
                            + "{ult_damage} 피해와 {ult_slow}% 둔화를, 나와 아군은 {ult_heal} 회복을 받습니다.")
            .param("inspire_speed", 25)
            .param("inspire_linger", 1)
            .param("throw_range", 18)
            .param("puddle_radius", 2.5)
            .param("puddle_duration", 5)
            .param("puddle_interval", 0.5)
            .param("splash_damage", 3)
            .param("red_damage", 0.5)
            .param("blue_slow", 40)
            .param("green_heal", 0.5)
            .param("ult_radius", 7)
            .param("ult_duration", 6)
            .param("ult_interval", 0.5)
            .param("ult_splash", 3)
            .param("ult_damage", 0.75)
            .param("ult_slow", 35)
            .param("ult_heal", 0.5)
            .build();

    private static final String COLOR_TAG = "#FF5FA2";
    private static final String RAINBOW = "<gradient:#FF4D4D:#FFD84D:#4DE07A:#4D9BFF:#C46BFF>";
    /** White canvas core, paint pink body, ink edge (for the shared shapes). */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xFF5FA2, 0x3A2A5C);
    private static final Particle.DustOptions WHITE_DUST = new Particle.DustOptions(Color.WHITE, 0.8f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(Color.fromRGB(0xFFD54F), 0.9f);

    /** The three paints, in the order the active cycles through them. */
    private enum Paint {
        RED("빨강", "#FF4D4D", Material.RED_CONCRETE, Material.PINK_CONCRETE, 0xFF4D4D, 0xFFB3B3),
        BLUE("파랑", "#4D8BFF", Material.BLUE_CONCRETE, Material.LIGHT_BLUE_CONCRETE, 0x3D6BFF, 0xA8D4FF),
        GREEN("초록", "#4DE07A", Material.LIME_CONCRETE, Material.GREEN_CONCRETE, 0x5BE06A, 0xC8FFB8);

        final String label;
        final String tag;
        final Material main;
        final Material accent;
        final Color color;
        final Color light;
        final Particle.DustOptions dust;
        final Particle.DustOptions drop;
        final Particle.DustTransition sheen;

        Paint(String label, String tag, Material main, Material accent, int color, int light) {
            this.label = label;
            this.tag = tag;
            this.main = main;
            this.accent = accent;
            this.color = Color.fromRGB(color);
            this.light = Color.fromRGB(light);
            this.dust = new Particle.DustOptions(this.color, 1.2f);
            this.drop = new Particle.DustOptions(this.color, 0.7f);
            this.sheen = new Particle.DustTransition(this.light, this.color, 1.0f);
        }

        String named() {
            return "<" + tag + ">" + label + "</" + tag + ">";
        }

        Paint next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    // Block data needs a running server: only the materials are constants, the data is created when used.
    private static final Material CANVAS = Material.WHITE_WOOL;
    private static final Material FRAME = Material.GOLD_BLOCK;
    private static final Material BRUSH = Material.BRUSH;

    /** Display entities this ability keeps alive at most at once (optional extras fall back to particles). */
    private static final int MAX_DISPLAYS = 24;

    // passive
    private static final long INSPIRE_PERIOD = 4;
    /** How often the next paint glistens at the hand while the active is ready (a multiple of the period). */
    private static final long LOADED_PERIOD = 12;

    // active
    private static final int GROUND_SEARCH = 6;
    private static final double BLOB_HIT_RADIUS = 0.5;
    private static final float BLOB_SIZE = 0.34f;
    private static final long SPREAD_TICKS = 4;
    private static final long DRY_TICKS = 10;
    private static final int DROPLETS = 2;
    private static final long SLOW_CHECK_PERIOD = 2;
    /** Each slow modifier lasts this long and is renewed halfway while the enemy stays in the paint. */
    private static final long SLOW_LEASE = 20;

    // ultimate
    private static final float CANVAS_WIDTH = 2.8f;
    private static final float CANVAS_HEIGHT = 2.0f;
    private static final float CANVAS_DEPTH = 0.06f;
    private static final float FRAME_THICKNESS = 0.12f;
    private static final float CANVAS_LIFT = 0.45f;
    private static final double[] ZONE_DISTANCES = {3.0, 2.0, 1.0, 0};
    private static final long RISE_TICKS = 5;
    private static final long STROKE_TICKS = 3;
    private static final long STROKE_GAP = 1;
    private static final long FLOURISH_TICKS = 2;
    private static final int FALL_STEPS = 3;
    private static final long FALL_STEP_TICKS = 2;
    private static final long FLOOD_TICKS = 8;
    private static final float STROKE_HEIGHT = 0.46f;
    private static final float[] STROKE_ROWS = {0.78f, 0.5f, 0.22f};
    private static final float[] STROKE_SLANT = {-5f, 4f, -3f};
    /** The bullseye: red rim, blue ring, green heart, as a share of the zone radius. */
    private static final float[] RING_SHARES = {1.0f, 0.66f, 0.34f};
    private static final int ZONE_DROPLETS = 6;
    private static final long TURN_PERIOD = 10;

    // passive state
    private final List<PaintArea> areas = new ArrayList<>();
    private @Nullable NamespacedKey inspireKey;
    private long inspiredUntil = Long.MIN_VALUE / 2;
    private long footstep;
    // active state
    private Paint nextPaint = Paint.RED;
    /** Display entities spawned by this instance (pruned when counted). */
    private final List<Display> live = new ArrayList<>();

    /** Ground the owner painted: inspiration works anywhere on it until it dries. */
    private record PaintArea(Location center, double radius, long until) {
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        // The speed modifier (added until revoke) is removed by the context.
        inspireKey = null;
        areas.clear();
        live.clear();
    }

    // ------------------------------------------------------------------ passive: inspiration

    @Override
    public void onTick(long tick) {
        if (tick % INSPIRE_PERIOD != 0) {
            return;
        }
        Player owner = player();
        if (tick % LOADED_PERIOD == 0 && ctx().cooldownRemaining(owner) <= 0) {
            // The brush is loaded: a drop of the next paint glistens at the hand.
            Vfx.dot(hand(owner), Particle.DUST, nextPaint.drop, false);
        }
        long now = ctx().currentTick();
        areas.removeIf(area -> area.until() <= now);
        if (onPaint(owner)) {
            inspiredUntil = now + Math.max(0, params().ticks("inspire_linger"));
        }
        boolean inspired = now < inspiredUntil;
        if (inspired && inspireKey == null) {
            double speed = Math.max(0, params().num("inspire_speed")) / 100.0;
            inspireKey = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, 0);
            inspiredFx(owner);
        } else if (!inspired && inspireKey != null) {
            ctx().removeModifier(owner, inspireKey);
            inspireKey = null;
        }
        if (inspired) {
            footprints(owner);
        }
    }

    private boolean onPaint(Player owner) {
        Location feet = owner.getLocation();
        for (PaintArea area : areas) {
            Location center = area.center();
            if (!center.getWorld().equals(feet.getWorld())) {
                continue;
            }
            double dx = feet.getX() - center.getX();
            double dz = feet.getZ() - center.getZ();
            double dy = feet.getY() - center.getY();
            if (dx * dx + dz * dz <= area.radius() * area.radius() && dy > -1 && dy < 2.5) {
                return true;
            }
        }
        return false;
    }

    /** Inspiration strikes: a rainbow swirl and a bright chime. */
    private void inspiredFx(Player owner) {
        Location chest = Fx.body(owner);
        Paint[] paints = Paint.values();
        for (int i = 0; i < paints.length; i++) {
            Vfx.helix(ctx(), owner.getLocation().add(0, 0.1, 0), 0.55, 1.9, 1.0, 1, 6, i * Math.PI * 2 / 3,
                    Particle.DUST, paints[i].drop, false);
        }
        Vfx.dot(chest.clone().add(0, 1.1, 0), Particle.END_ROD, null, false);
        Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 0.6f, 1.4f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<b>" + RAINBOW + "영감!</gradient></b> <gray>이동 속도 증가</gray>"));
    }

    /** Rainbow footprints while inspired. */
    private void footprints(Player owner) {
        Location feet = owner.getLocation().add(0, 0.06, 0);
        Paint paint = Paint.values()[(int) (footstep++ % Paint.values().length)];
        Vfx.dot(feet.clone().add(Fx.jitter(0.15f), 0, Fx.jitter(0.15f)), Particle.DUST, paint.drop, false);
        if (footstep % 3 == 0) {
            Vfx.dot(feet.clone().add(0, 0.9, 0), Particle.WAX_OFF, null, false);
        }
    }

    // ------------------------------------------------------------------ active: paint throw

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location target = aimPoint(params().num("throw_range"));
        if (target == null) {
            return ActivationResult.fail("물감을 던질 곳이 없습니다");
        }
        Paint paint = nextPaint;
        nextPaint = paint.next();
        Location hand = hand(owner);
        Blob blob = new Blob(hand, target, paint);
        blob.launch();
        Loop.repeat(ctx(), owner, 1, 1, blob.flight, blob, blob::arrive);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">물감 투척</" + COLOR_TAG + "> " + paint.named()
                + " <dark_gray>·</dark_gray> <gray>다음</gray> " + nextPaint.named()));
        return ActivationResult.success();
    }

    /**
     * Where the glob is aimed: the aimed enemy's feet, else where the view ray meets a block (or ends), dropped onto
     * the ground below. Null without ground.
     */
    private @Nullable Location aimPoint(double range) {
        Player owner = player();
        World world = owner.getWorld();
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null && seen(owner, aimed)) {
            Location feet = flat(aimed.getLocation());
            Double ground = groundBelow(feet.clone().add(0, 0.2, 0), GROUND_SEARCH);
            if (ground != null) {
                feet.setY(ground);
                return feet;
            }
        }
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-8) {
            return null;
        }
        direction.normalize();
        RayTraceResult hit = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        Location probe = hit == null
                ? eye.clone().add(direction.clone().multiply(range))
                : hit.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.3));
        Double ground = groundBelow(probe.clone().add(0, 0.2, 0), GROUND_SEARCH);
        if (ground == null) {
            return null;
        }
        Location at = flat(probe);
        at.setY(ground);
        return at;
    }

    /**
     * The paint glob in flight: an arc from the hand to the aimed point. It splats early on the first enemy it
     * touches (the puddle then spreads under that enemy) or on a block in its way.
     */
    private final class Blob implements LongPredicate {

        private final Location from;
        private final Location to;
        private final Paint paint;
        private final World world;
        private final long flight;
        private final double arc;
        private final @Nullable BlockDisplay body;
        private Location position;
        private boolean landed;

        Blob(Location from, Location to, Paint paint) {
            this.from = from;
            this.to = to.clone().add(0, 0.15, 0);
            this.paint = paint;
            this.world = from.getWorld();
            double distance = from.distance(this.to);
            this.flight = Math.clamp(Math.round(distance * 0.85), 5, 16);
            this.arc = 0.4 + distance * 0.07;
            this.position = from.clone();
            this.body = roomFor(1)
                    ? keep(Displays.block(ctx(), player(), flat(from), data(paint.main), blobPose(0, 0, 1f), flight + 6))
                    : null;
            if (body != null) {
                body.setTeleportDuration(1);
            }
        }

        void launch() {
            world.spawnParticle(Particle.DUST, from, Vfx.n(ctx(), 8), 0.15, 0.15, 0.15, 0, paint.dust);
            world.spawnParticle(Particle.BLOCK, from, Vfx.n(ctx(), 4), 0.1, 0.1, 0.1, 0.05, data(paint.main));
            Fx.sound(from, Sound.ENTITY_SNOWBALL_THROW, 0.8f, 0.7f + Fx.jitter(0.1f));
            Fx.sound(from, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(from, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 0.6f, 1.6f + Fx.jitter(0.1f));
        }

        @Override
        public boolean test(long tick) {
            if (landed) {
                return false;
            }
            double t = (double) (tick + 1) / flight;
            Location next = from.clone().add(to.toVector().subtract(from.toVector()).multiply(t))
                    .add(0, 4 * arc * t * (1 - t), 0);
            // A block in the way stops the glob where it hits.
            Vector step = next.toVector().subtract(position.toVector());
            double length = step.length();
            if (length > 1.0E-4) {
                RayTraceResult wall = world.rayTraceBlocks(position, step.clone().multiply(1 / length), length,
                        FluidCollisionMode.NEVER, true);
                if (wall != null) {
                    land(wall.getHitPosition().toLocation(world).subtract(step.clone().multiply(0.3 / length)));
                    return false;
                }
            }
            Location previous = position;
            position = next;
            for (LivingEntity enemy : ctx().enemiesNear(player(), next, BLOB_HIT_RADIUS)) {
                // An enemy the owner cannot see (stealth) is not given away by a glob splatting on thin air.
                if (seen(player(), enemy)) {
                    land(enemy.getLocation());
                    return false;
                }
            }
            drawFlight(previous, next, tick);
            return true;
        }

        private void drawFlight(Location previous, Location next, long tick) {
            if (body != null && body.isValid()) {
                body.teleport(flat(next));
                float squash = 1f + 0.18f * (float) Math.sin(tick * 1.3);
                Displays.animate(body, blobPose(tick * 47f, tick * 31f, squash), 1);
            }
            Vfx.line(ctx(), previous, next, 3, Particle.DUST, paint.drop, false);
            if (tick % 2 == 0) {
                Vfx.dot(next.clone().add(0, -0.15, 0), Particle.FALLING_DUST, data(paint.main), false);
            }
        }

        /** The loop ran its full flight: the glob lands on the aimed point. */
        void arrive() {
            if (!landed) {
                land(to);
            }
        }

        private void land(Location at) {
            landed = true;
            if (body != null && body.isValid()) {
                body.remove();
            }
            Location center = flat(at);
            Double ground = groundBelow(center.clone().add(0, 0.5, 0), 4);
            if (ground != null) {
                center.setY(ground);
            }
            splash(center, paint, ground != null);
        }
    }

    /** The glob splats: enemies around are hit, and a puddle spreads when there is ground. */
    private void splash(Location center, Paint paint, boolean onGround) {
        Player owner = player();
        World world = center.getWorld();
        double radius = Math.max(0.5, params().num("puddle_radius"));
        Location burst = center.clone().add(0, 0.4, 0);
        Vfx.dot(burst, Particle.FLASH, paint.color, false);
        world.spawnParticle(Particle.DUST, burst, Vfx.n(ctx(), 22), radius * 0.35, 0.3, radius * 0.35, 0, paint.dust);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, burst, Vfx.n(ctx(), 12), 0.4, 0.4, 0.4, 0, paint.sheen);
        world.spawnParticle(Particle.BLOCK, burst, Vfx.n(ctx(), 16), 0.3, 0.2, 0.3, 0.2, data(paint.main));
        world.spawnParticle(Particle.SPLASH, burst, Vfx.n(ctx(), 10), radius * 0.3, 0.1, radius * 0.3, 0.1);
        Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.08, 0), 0.3, radius, 4, Particle.DUST, paint.drop, false);
        Fx.sound(burst, Sound.ENTITY_SLIME_SQUISH, 1.1f, 0.9f + Fx.jitter(0.1f));
        Fx.sound(burst, Sound.BLOCK_HONEY_BLOCK_PLACE, 0.9f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(burst, Sound.ENTITY_PLAYER_SPLASH, 0.5f, 1.6f + Fx.jitter(0.1f));

        double damage = params().num("splash_damage");
        int hits = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center.clone().add(0, 0.6, 0), radius)) {
            // The splash does not go through walls.
            if (!Spots.clearPath(burst, Fx.body(enemy))) {
                continue;
            }
            Combat.hit(ctx(), owner, enemy, damage);
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                hits++;
                Location body = Fx.body(enemy);
                world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 8), 0.3, 0.45, 0.3, 0, paint.dust);
                Vfx.trail(burst, body, paint.color, 4, false);
            }
        }
        if (hits > 0 && owner.isOnline()) {
            owner.sendActionBar(Text.mm(paint.named() + " <white>물감 " + hits + "명 적중!</white>"));
        }
        if (onGround) {
            Puddle puddle = new Puddle(center, paint, radius);
            puddle.spread();
            Loop.repeat(ctx(), owner, 1, 1, puddle.life, puddle, puddle::dry);
        }
    }

    /** An enemy slowed by paint: its slow is a short lease renewed while it stays inside. */
    private static final class Slowed {
        final LivingEntity entity;
        NamespacedKey key;
        long renewAt;

        Slowed(LivingEntity entity, NamespacedKey key, long renewAt) {
            this.entity = entity;
            this.key = key;
            this.renewAt = renewAt;
        }
    }

    /**
     * Leased slows for enemies standing in a painted area: entering ones get a short slow, renewed while they stay;
     * leavers are let go at once.
     */
    private final class SlowField {

        private final Map<UUID, Slowed> slowed = new LinkedHashMap<>();
        private final Particle.DustOptions mark;

        SlowField(Particle.DustOptions mark) {
            this.mark = mark;
        }

        void update(long tick, double slow, Location center, double radius, PaintedArea area) {
            Player owner = player();
            Iterator<Slowed> iterator = slowed.values().iterator();
            while (iterator.hasNext()) {
                Slowed entry = iterator.next();
                if (!Combat.isTargetable(ctx(), owner, entry.entity) || !area.covers(entry.entity)) {
                    ctx().removeModifier(entry.entity, entry.key);
                    iterator.remove();
                } else if (tick >= entry.renewAt) {
                    ctx().removeModifier(entry.entity, entry.key);
                    entry.key = ctx().addModifier(owner, entry.entity, Attribute.MOVEMENT_SPEED, -slow,
                            AttributeModifier.Operation.MULTIPLY_SCALAR_1, SLOW_LEASE);
                    entry.renewAt = tick + SLOW_LEASE / 2;
                }
            }
            if (slow <= 0) {
                return;
            }
            for (LivingEntity enemy : ctx().enemiesNear(owner, center.clone().add(0, 1, 0), radius + 1)) {
                if (slowed.containsKey(enemy.getUniqueId()) || !area.covers(enemy)) {
                    continue;
                }
                NamespacedKey key = ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, SLOW_LEASE);
                slowed.put(enemy.getUniqueId(), new Slowed(enemy, key, tick + SLOW_LEASE / 2));
                Location feet = enemy.getLocation().add(0, 0.1, 0);
                Vfx.ring(ctx(), feet, 0.55, 10, 0, Particle.DUST, mark, false);
                Fx.sound(feet, Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.8f, 0.9f + Fx.jitter(0.1f));
            }
        }

        void release() {
            for (Slowed entry : slowed.values()) {
                ctx().removeModifier(entry.entity, entry.key);
            }
            slowed.clear();
        }
    }

    /** Anything painted on the ground: whether an entity stands in it. */
    private interface PaintedArea {
        boolean covers(LivingEntity entity);
    }

    /** True if {@code entity} stands within {@code radius} of {@code center} (horizontally, near its height). */
    private static boolean within(LivingEntity entity, Location center, double radius) {
        Location at = entity.getLocation();
        if (!at.getWorld().equals(center.getWorld())) {
            return false;
        }
        double dx = at.getX() - center.getX();
        double dz = at.getZ() - center.getZ();
        double dy = at.getY() - center.getY();
        return dx * dx + dz * dz <= radius * radius && dy > -1.5 && dy < 3;
    }

    /**
     * A puddle of one paint: red burns enemies standing in it, blue slows them, green heals the owner and allies in
     * it. It glistens until it dries after {@code puddle_duration}; the owner is inspired on it.
     */
    private final class Puddle implements LongPredicate, PaintedArea {

        private final Location center;
        private final Paint paint;
        private final double radius;
        private final World world;
        private final long life;
        private final long interval;
        private final float yaw;
        private final List<BlockDisplay> plates = new ArrayList<>();
        private final SlowField slows;
        private boolean dried;
        private int effects;

        Puddle(Location center, Paint paint, double radius) {
            this.center = center;
            this.paint = paint;
            this.radius = radius;
            this.world = center.getWorld();
            this.life = Math.max(1, params().ticks("puddle_duration"));
            this.interval = Math.max(1, params().ticks("puddle_interval"));
            this.yaw = ThreadLocalRandom.current().nextFloat() * 90f;
            this.slows = new SlowField(paint.drop);
        }

        /** In the puddle and not behind a wall from its middle (paint does not soak through walls). */
        @Override
        public boolean covers(LivingEntity entity) {
            return within(entity, center, radius)
                    && Spots.clearPath(center.clone().add(0, 0.3, 0), entity.getLocation().add(0, 0.3, 0));
        }

        /** The paint spreads into an irregular splat with a sheen and a few stray droplets. */
        void spread() {
            Player owner = player();
            areas.add(new PaintArea(center, radius, ctx().currentTick() + life));
            long lifetime = life + DRY_TICKS + 4;
            float size = (float) (radius * 1.85);
            float[] shares = {1f, 0.93f, 0.86f};
            for (int i = 0; i < shares.length && roomFor(1); i++) {
                float plateYaw = yaw + i * 30f;
                float height = 0.012f + i * 0.004f;
                BlockDisplay plate = plate(data(paint.main), 0, 0, 0.1f, plateYaw, height, lifetime);
                Displays.animateNextTick(ctx(), owner, plate, platePose(0, 0, size * shares[i], plateYaw, height),
                        (int) SPREAD_TICKS);
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (roomFor(1)) {
                float sheenX = (float) (random.nextDouble(-0.3, 0.3) * radius);
                float sheenZ = (float) (random.nextDouble(-0.3, 0.3) * radius);
                BlockDisplay sheen = plate(data(paint.accent), sheenX, sheenZ, 0.05f, yaw + 15f, 0.026f, lifetime);
                Displays.animateNextTick(ctx(), owner, sheen,
                        platePose(sheenX, sheenZ, (float) (radius * 0.55), yaw + 15f, 0.026f), (int) SPREAD_TICKS + 2);
            }
            for (int i = 0; i < DROPLETS && roomFor(1); i++) {
                double angle = random.nextDouble(Math.PI * 2);
                double distance = radius * (1.05 + random.nextDouble(0.3));
                float x = (float) (Math.cos(angle) * distance);
                float z = (float) (Math.sin(angle) * distance);
                float dropSize = 0.3f + random.nextFloat() * 0.3f;
                float dropYaw = random.nextFloat() * 90f;
                BlockDisplay droplet = plate(data(paint.main), x, z, 0.05f, dropYaw, 0.014f, lifetime);
                Displays.animateNextTick(ctx(), owner, droplet, platePose(x, z, dropSize, dropYaw, 0.014f),
                        (int) SPREAD_TICKS + 3);
            }
        }

        private BlockDisplay plate(BlockData data, float x, float z, float size, float plateYaw, float height,
                                   long lifetime) {
            BlockDisplay plate = keep(Displays.block(ctx(), player(), center, data,
                    platePose(x, z, size, plateYaw, height), lifetime));
            plates.add(plate);
            return plate;
        }

        @Override
        public boolean test(long tick) {
            if (dried) {
                return false;
            }
            if (paint == Paint.BLUE && tick % SLOW_CHECK_PERIOD == 0) {
                double slow = Math.clamp(params().num("blue_slow") / 100.0, 0.0, 1.0);
                slows.update(tick, slow, center, radius, this);
            }
            if (tick % interval == interval - 1) {
                pulse();
            }
            glisten(tick);
            return true;
        }

        /** Red burns enemies in the puddle, green heals the owner's side in it (blue works through its slow). */
        private void pulse() {
            Player owner = player();
            if (paint == Paint.RED) {
                double damage = params().num("red_damage");
                for (LivingEntity enemy : ctx().enemiesNear(owner, center.clone().add(0, 1, 0), radius + 1)) {
                    if (!covers(enemy)) {
                        continue;
                    }
                    Combat.hit(ctx(), owner, enemy, damage);
                    if (Combat.isTargetable(ctx(), owner, enemy)) {
                        effects++;
                        Location feet = enemy.getLocation().add(0, 0.2, 0);
                        world.spawnParticle(Particle.DUST, feet, Vfx.n(ctx(), 6), 0.3, 0.2, 0.3, 0, paint.dust);
                        world.spawnParticle(Particle.SMALL_FLAME, feet, Vfx.n(ctx(), 2), 0.2, 0.1, 0.2, 0.01);
                        Fx.sound(feet, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.6f, 1.6f + Fx.jitter(0.1f));
                    }
                }
            } else if (paint == Paint.GREEN) {
                double heal = params().num("green_heal");
                List<LivingEntity> friends = new ArrayList<>(ctx().alliesNear(owner, center.clone().add(0, 1, 0),
                        radius + 1));
                friends.addFirst(owner);
                for (LivingEntity friend : friends) {
                    if (!covers(friend) || heal <= 0) {
                        continue;
                    }
                    if (friend.getHealth() < Combat.maxHealth(friend)) {
                        effects++;
                    }
                    ctx().heal(friend, heal);
                    Location chest = Fx.body(friend);
                    Vfx.trail(friend.getLocation().add(Fx.jitter(0.4f), 0.05, Fx.jitter(0.4f)), chest, paint.light,
                            8, false);
                    world.spawnParticle(Particle.HAPPY_VILLAGER, chest, Vfx.n(ctx(), 2), 0.25, 0.35, 0.25, 0);
                }
            }
        }

        /** The wet paint glistens, with a hint of its effect: rising embers, drips or leaves of light. */
        private void glisten(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (tick % 3 == 0) {
                Location glint = Vfx.scatter(center, radius * 0.9).add(0, 0.06, 0);
                Vfx.dot(glint, Particle.DUST_COLOR_TRANSITION, paint.sheen, false);
            }
            if (tick % 5 == 0) {
                Location above = Vfx.scatter(center, radius * 0.8).add(0, 0.15 + random.nextDouble(0.5), 0);
                switch (paint) {
                    case RED -> Vfx.dot(above, Particle.DUST, paint.drop, false);
                    case BLUE -> Vfx.dot(above, Particle.DRIPPING_WATER, null, false);
                    case GREEN -> Vfx.dot(above, Particle.HAPPY_VILLAGER, null, false);
                }
            }
            if (tick % 10 == 0) {
                Vfx.ring(ctx(), center.clone().add(0, 0.05, 0), radius, (int) Math.max(12, radius * 6), tick * 0.05,
                        Particle.DUST, paint.drop, false);
            }
            if (tick % 12 == 6) {
                Vfx.dot(Vfx.scatter(center, radius * 0.6).add(0, 0.1, 0), Particle.WAX_OFF, null, false);
            }
        }

        /** The paint dries: the plates shrink into the ground, slows end. */
        void dry() {
            if (dried) {
                return;
            }
            dried = true;
            slows.release();
            Player owner = player();
            for (BlockDisplay plate : plates) {
                if (plate.isValid()) {
                    Displays.shrinkAway(ctx(), owner, plate, (int) DRY_TICKS);
                }
            }
            world.spawnParticle(Particle.DUST, center.clone().add(0, 0.2, 0), Vfx.n(ctx(), 8), radius * 0.4, 0.1,
                    radius * 0.4, 0, paint.drop);
            Fx.sound(center, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.4f, 1.4f + Fx.jitter(0.1f));
            if (paint == Paint.GREEN && effects > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm(paint.named() + " <white>물감 " + effects + "번 치유</white>"));
            }
        }

        /** A flat square plate at ({@code x}, {@code z}) of edge {@code size}, turned {@code plateYaw}. */
        private Transformation platePose(float x, float z, float size, float plateYaw, float height) {
            return Displays.transform(x, height, z, size, 0.02f, size, plateYaw, 0, 0);
        }
    }

    private static Transformation blobPose(float yaw, float pitch, float squash) {
        float wide = BLOB_SIZE * (2f - squash) * 0.9f + BLOB_SIZE * 0.1f;
        return Displays.transform(0, 0, 0, wide, BLOB_SIZE * squash, wide, yaw, pitch, 0);
    }

    // ------------------------------------------------------------------ ultimate: masterpiece

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontalFacing(feet);
        Location center = zoneCenter(feet, facing);
        if (center == null) {
            return ActivationResult.fail("그림을 펼칠 땅이 없습니다");
        }
        Masterpiece masterpiece = new Masterpiece(center, facing, feet.getYaw());
        masterpiece.raise();
        Loop.repeat(ctx(), owner, 1, 1, masterpiece.maxRuns(), masterpiece, masterpiece::release);
        return ActivationResult.success();
    }

    /**
     * Where the painting lands: a few blocks ahead of the owner (closer when something is in the way), on the
     * ground. Null when there is no ground at all.
     */
    private @Nullable Location zoneCenter(Location feet, Vector facing) {
        Location chest = feet.clone().add(0, 1.0, 0);
        for (double distance : ZONE_DISTANCES) {
            Location probe = flat(feet).add(facing.clone().multiply(distance));
            if (distance > 0 && (!Spots.clearPath(chest, probe.clone().add(0, 1.0, 0)) || !ctx().isInsideArena(probe))) {
                continue;
            }
            Double ground = groundBelow(probe.clone().add(0, 1.0, 0), GROUND_SEARCH);
            if (ground != null) {
                probe.setY(ground);
                return probe;
            }
        }
        return null;
    }

    private enum Stage { RISING, PAINTING, FALLING, FLOODED, DRY }

    /**
     * The masterpiece: a gilded canvas rises facing the painter, three strokes are brushed onto it, it tips over
     * face-up onto the ground and the paint floods a circle of {@code ult_radius}. The circle then burns and slows
     * enemies and heals the owner and allies every {@code ult_interval} for {@code ult_duration}.
     */
    private final class Masterpiece implements LongPredicate, PaintedArea {

        private final Location center;
        private final Vector facing;
        private final World world;
        private final Location pivot;
        private final float canvasYaw;
        private final double radius;
        private final long life;
        private final long interval;
        private final long paintStart;
        private final long fallStart;
        private final long floodAt;
        private final List<Part> canvasParts = new ArrayList<>();
        private final List<BlockDisplay> strokes = new ArrayList<>();
        private final List<Ring> rings = new ArrayList<>();
        private final List<BlockDisplay> droplets = new ArrayList<>();
        private final SlowField slows = new SlowField(Paint.BLUE.drop);
        private @Nullable ItemDisplay brush;
        private @Nullable TextDisplay title;
        private Stage stage = Stage.RISING;
        private float turn;
        private int hits;
        private int heals;

        /** One block display of the canvas with its pose in the canvas frame (center and size). */
        private record Part(BlockDisplay display, Vector3f center, Vector3f size, float slant) {
        }

        /** One plate of the bullseye: its band (0 = rim), height and resting yaw. */
        private record Ring(BlockDisplay display, int band, float height, float yaw) {
        }

        Masterpiece(Location center, Vector facing, float ownerYaw) {
            this.center = center;
            this.facing = facing;
            this.world = center.getWorld();
            this.radius = Math.max(1, params().num("ult_radius"));
            this.life = Math.max(1, params().ticks("ult_duration"));
            this.interval = Math.max(1, params().ticks("ult_interval"));
            // The canvas stands so that it lands centered on the zone, its painted face turned to the painter.
            this.pivot = flat(center).subtract(facing.clone().multiply(CANVAS_HEIGHT / 2.0)).add(0, CANVAS_LIFT, 0);
            this.canvasYaw = ownerYaw + 180f;
            this.paintStart = RISE_TICKS + 1;
            this.fallStart = paintStart + STROKE_ROWS.length * (STROKE_TICKS + STROKE_GAP) + FLOURISH_TICKS;
            this.floodAt = fallStart + FALL_STEPS * FALL_STEP_TICKS;
        }

        long maxRuns() {
            return floodAt + life + 1;
        }

        @Override
        public boolean covers(LivingEntity entity) {
            return within(entity, center, radius);
        }

        /** The canvas and its gilded frame rise out of the ground. */
        void raise() {
            Player owner = player();
            long lifetime = floodAt + 12;
            float w = CANVAS_WIDTH;
            float h = CANVAS_HEIGHT;
            float t = FRAME_THICKNESS;
            canvasPart(data(CANVAS), new Vector3f(0, h / 2, 0), new Vector3f(w, h, CANVAS_DEPTH), 0, lifetime);
            canvasPart(data(FRAME), new Vector3f(0, h + t / 2, 0), new Vector3f(w + 2 * t, t, t), 0, lifetime);
            canvasPart(data(FRAME), new Vector3f(0, -t / 2, 0), new Vector3f(w + 2 * t, t, t), 0, lifetime);
            canvasPart(data(FRAME), new Vector3f(-w / 2 - t / 2, h / 2, 0), new Vector3f(t, h, t), 0, lifetime);
            canvasPart(data(FRAME), new Vector3f(w / 2 + t / 2, h / 2, 0), new Vector3f(t, h, t), 0, lifetime);
            for (Part part : canvasParts) {
                part.display().setViewRange(2.0f);
                // Grows out of the ground: start flattened at the pivot.
                Displays.animateNextTick(ctx(), owner, part.display(), pose(part, 0, 0), (int) RISE_TICKS);
            }
            Location base = pivot.clone().subtract(0, CANVAS_LIFT - 0.1, 0);
            world.spawnParticle(Particle.DUST, base, Vfx.n(ctx(), 16), 1.2, 0.1, 0.3, 0, GOLD_DUST, true);
            world.spawnParticle(Particle.DUST, canvasPoint(0, CANVAS_HEIGHT / 2, 0), Vfx.n(ctx(), 14), 1.0, 0.7, 0.3,
                    0, WHITE_DUST, true);
            Vfx.helix(ctx(), base, 1.4, 2.4, 1.0, 3, 10, 0, Particle.DUST_COLOR_TRANSITION, Paint.RED.sheen, true);
            Fx.sound(base, Sound.ENTITY_PAINTING_PLACE, 1.4f, 0.7f);
            Fx.sound(base, Sound.BLOCK_WOOL_PLACE, 1.2f, 0.6f);
            Fx.sound(base, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.9f, 1.3f);
        }

        private void canvasPart(BlockData data, Vector3f middle, Vector3f size, float slant, long lifetime) {
            BlockDisplay display = keep(Displays.block(ctx(), player(), pivot, data,
                    partPose(canvasYaw, 0, 0, new Vector3f(middle.x, 0.01f, middle.z),
                            new Vector3f(size.x, 0.02f, size.z), slant), lifetime));
            canvasParts.add(new Part(display, middle, size, slant));
        }

        @Override
        public boolean test(long tick) {
            if (stage == Stage.DRY) {
                return false;
            }
            if (tick < paintStart) {
                return true;
            }
            if (tick < fallStart) {
                stage = Stage.PAINTING;
                paint(tick - paintStart);
                return true;
            }
            if (tick < floodAt) {
                if (stage != Stage.FALLING) {
                    stage = Stage.FALLING;
                    flourish();
                }
                long sinceFall = tick - fallStart;
                if (sinceFall % FALL_STEP_TICKS == 0) {
                    fall((int) (sinceFall / FALL_STEP_TICKS) + 1);
                }
                return true;
            }
            long t = tick - floodAt;
            if (t == 0) {
                flood();
            }
            if (t >= life) {
                dry();
                return false;
            }
            if (t % SLOW_CHECK_PERIOD == 0) {
                double slow = Math.clamp(params().num("ult_slow") / 100.0, 0.0, 1.0);
                slows.update(t, slow, center, radius, this);
            }
            if (t % interval == interval - 1) {
                pulse();
            }
            if (t > FLOOD_TICKS && t % TURN_PERIOD == 0) {
                turn(t);
            }
            ambience(t);
            return true;
        }

        /** One brush stroke after another sweeps across the canvas, the brush gliding along its edge. */
        private void paint(long since) {
            Player owner = player();
            long cycle = STROKE_TICKS + STROKE_GAP;
            int row = (int) (since / cycle);
            long within = since % cycle;
            if (row >= STROKE_ROWS.length || within >= STROKE_TICKS) {
                return;
            }
            Paint paint = Paint.values()[row];
            float progress = (float) (within + 1) / STROKE_TICKS;
            float left = -CANVAS_WIDTH / 2 + 0.12f;
            float full = CANVAS_WIDTH - 0.24f;
            float y = CANVAS_HEIGHT * STROKE_ROWS[row];
            float z = CANVAS_DEPTH / 2 + 0.015f;
            if (within == 0) {
                BlockDisplay stroke = keep(Displays.block(ctx(), owner, pivot, data(paint.main),
                        partPose(canvasYaw, 0, 0, new Vector3f(left + 0.01f, y, z),
                                new Vector3f(0.02f, STROKE_HEIGHT, 0.02f), STROKE_SLANT[row]), floodAt + 12));
                stroke.setViewRange(2.0f);
                strokes.add(stroke);
                canvasParts.add(new Part(stroke, new Vector3f(0, y, z), new Vector3f(full, STROKE_HEIGHT, 0.02f),
                        STROKE_SLANT[row]));
                Fx.sound(pivot, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 1.2f, 0.9f + 0.2f * row);
                Fx.sound(pivot, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.7f, 1.4f + 0.1f * row);
            }
            BlockDisplay stroke = strokes.get(strokes.size() - 1);
            float width = full * progress;
            Displays.animate(stroke, partPose(canvasYaw, 0, 0, new Vector3f(left + width / 2, y, z),
                    new Vector3f(width, STROKE_HEIGHT, 0.02f), STROKE_SLANT[row]), 1);
            // The brush rides the wet edge of the stroke.
            Location tip = canvasPoint(left + width, y, z + 0.3f);
            if (brush == null && roomFor(1)) {
                brush = keep(Displays.item(ctx(), owner, tip, ItemStack.of(BRUSH),
                        Displays.centered(0, 0, 0, 0.8f, 0.8f, 0.8f, canvasYaw, 0, -35f), floodAt + 4));
                brush.setTeleportDuration(1);
            } else if (brush != null && brush.isValid()) {
                brush.teleport(tip);
            }
            world.spawnParticle(Particle.DUST, tip, Vfx.n(ctx(), 3), 0.08, 0.12, 0.08, 0, paint.drop, true);
            if (within % 2 == 0) {
                world.spawnParticle(Particle.FALLING_DUST, tip, 1, 0.05, 0.05, 0.05, 0, data(paint.main), true);
            }
        }

        /** The masterpiece is finished: a flash, a rainbow title and a fanfare, and the brush vanishes. */
        private void flourish() {
            Player owner = player();
            if (brush != null && brush.isValid()) {
                Displays.shrinkAway(ctx(), owner, brush, 2);
            }
            Location top = canvasPoint(0, CANVAS_HEIGHT + 0.7f, 0);
            if (roomFor(1)) {
                title = keep(Displays.text(ctx(), owner, top, Text.mm("<b>" + RAINBOW + "✦ 걸작! ✦</gradient></b>"),
                        0.3f, FALL_STEPS * FALL_STEP_TICKS + 14));
                title.setViewRange(2.0f);
                Displays.animateNextTick(ctx(), owner, title, Displays.centered(0, 0.3f, 0, 1.6f, 1.6f, 1.6f, 0, 0, 0),
                        4);
            }
            Location canvas = canvasPoint(0, CANVAS_HEIGHT / 2, 0.2f);
            Vfx.burst(ctx(), canvas, PALETTE, 1.2, Particle.END_ROD, true);
            world.spawnParticle(Particle.END_ROD, canvas, Vfx.n(ctx(), 10), 1.0, 0.7, 0.2, 0.05, null, true);
            world.spawnParticle(Particle.DUST, top, Vfx.n(ctx(), 12), 0.8, 0.2, 0.2, 0, GOLD_DUST, true);
            Fx.sound(canvas, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.4f);
            Fx.sound(canvas, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.2f);
        }

        /** The canvas tips over (painted face up) towards the zone, in a few rigid steps. */
        private void fall(int step) {
            float progress = Math.min(1f, (float) step / FALL_STEPS);
            float tilt = -90f * progress;
            float drop = (CANVAS_LIFT - 0.05f) * progress;
            for (Part part : canvasParts) {
                if (part.display().isValid()) {
                    Displays.animate(part.display(), pose(part, tilt, drop), (int) FALL_STEP_TICKS);
                }
            }
            if (step == 1) {
                Fx.sound(pivot, Sound.ENTITY_PAINTING_BREAK, 1.0f, 0.6f);
            }
        }

        /** Slam: the canvas melts into the floor and the paint floods the circle; enemies in it are splashed. */
        private void flood() {
            stage = Stage.FLOODED;
            Player owner = player();
            areas.add(new PaintArea(center, radius, ctx().currentTick() + life));
            for (Part part : canvasParts) {
                if (part.display().isValid()) {
                    Displays.shrinkAway(ctx(), owner, part.display(), 4);
                }
            }
            if (title != null && title.isValid()) {
                Displays.shrinkAway(ctx(), owner, title, 6);
            }
            long lifetime = life + DRY_TICKS + 6;
            Paint[] paints = Paint.values();
            for (int i = 0; i < RING_SHARES.length; i++) {
                Paint paint = paints[i];
                float size = (float) (radius * 2 * RING_SHARES[i]) * 0.94f;
                float height = 0.012f + i * 0.005f;
                for (int k = 0; k < 2; k++) {
                    float plateYaw = k * 45f + i * 15f;
                    float plateHeight = height + k * 0.002f;
                    BlockDisplay plate = keep(Displays.block(ctx(), owner, center, data(paint.main),
                            Displays.transform(0, plateHeight, 0, 0.2f, 0.02f, 0.2f, plateYaw, 0, 0), lifetime));
                    plate.setViewRange(2.0f);
                    rings.add(new Ring(plate, i, plateHeight, plateYaw));
                    ctx().runLater(owner, 1 + i * 2L, () -> Displays.animate(plate,
                            Displays.transform(0, plateHeight, 0, size, 0.02f, size, plateYaw, 0, 0),
                            (int) FLOOD_TICKS));
                }
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < ZONE_DROPLETS && roomFor(1); i++) {
                Paint paint = paints[i % paints.length];
                double angle = i * Math.PI * 2 / ZONE_DROPLETS + random.nextDouble(0.6);
                double distance = radius * (1.04 + random.nextDouble(0.18));
                float x = (float) (Math.cos(angle) * distance);
                float z = (float) (Math.sin(angle) * distance);
                float size = 0.35f + random.nextFloat() * 0.4f;
                float dropYaw = random.nextFloat() * 90f;
                BlockDisplay droplet = keep(Displays.block(ctx(), owner, center, data(paint.accent),
                        Displays.transform(x, 0.014f, z, 0.05f, 0.02f, 0.05f, dropYaw, 0, 0), lifetime));
                droplets.add(droplet);
                ctx().runLater(owner, FLOOD_TICKS - 2, () -> Displays.animate(droplet,
                        Displays.transform(x, 0.014f, z, size, 0.02f, size, dropYaw, 0, 0), 3));
            }

            Location burst = center.clone().add(0, 0.5, 0);
            Vfx.dot(burst, Particle.FLASH, Color.WHITE, true);
            for (int i = 0; i < paints.length; i++) {
                Paint paint = paints[i];
                world.spawnParticle(Particle.DUST, burst, Vfx.n(ctx(), 30), radius * 0.4, 0.4, radius * 0.4, 0,
                        paint.dust, true);
                world.spawnParticle(Particle.BLOCK, burst, Vfx.n(ctx(), 14), 1.2, 0.3, 1.2, 0.3, data(paint.main),
                        true);
                Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.1 + 0.15 * i, 0), 0.6,
                        radius * RING_SHARES[RING_SHARES.length - 1 - i] + 0.3, FLOOD_TICKS, Particle.DUST, paint.dust,
                        true);
            }
            world.spawnParticle(Particle.SPLASH, burst, Vfx.n(ctx(), 40), radius * 0.4, 0.2, radius * 0.4, 0.2, null,
                    true);
            Fx.sound(center, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.3f, 0.8f);
            Fx.sound(center, Sound.ENTITY_SLIME_SQUISH, 1.4f, 0.5f);
            Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.2f, 1.0f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_WOOL_BREAK, 1.2f, 0.7f);

            double damage = params().num("ult_splash");
            for (LivingEntity enemy : ctx().enemiesNear(owner, center.clone().add(0, 1, 0), radius + 1)) {
                if (!covers(enemy)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    hits++;
                    Location body = Fx.body(enemy);
                    for (Paint paint : paints) {
                        world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 4), 0.3, 0.45, 0.3, 0, paint.dust, true);
                    }
                }
            }
        }

        /** Every interval: enemies inside burn, the owner and allies inside heal (the slow runs continuously). */
        private void pulse() {
            Player owner = player();
            double damage = params().num("ult_damage");
            for (LivingEntity enemy : ctx().enemiesNear(owner, center.clone().add(0, 1, 0), radius + 1)) {
                if (!covers(enemy)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    hits++;
                    Location feet = enemy.getLocation().add(0, 0.2, 0);
                    world.spawnParticle(Particle.DUST, feet, Vfx.n(ctx(), 4), 0.3, 0.2, 0.3, 0, Paint.RED.dust, true);
                    world.spawnParticle(Particle.DUST, feet, Vfx.n(ctx(), 2), 0.3, 0.2, 0.3, 0, Paint.BLUE.dust, true);
                }
            }
            double heal = params().num("ult_heal");
            List<LivingEntity> friends = new ArrayList<>(ctx().alliesNear(owner, center.clone().add(0, 1, 0),
                    radius + 1));
            friends.addFirst(owner);
            for (LivingEntity friend : friends) {
                if (!covers(friend) || heal <= 0) {
                    continue;
                }
                if (friend.getHealth() < Combat.maxHealth(friend)) {
                    heals++;
                }
                ctx().heal(friend, heal);
                Location chest = Fx.body(friend);
                Vfx.trail(friend.getLocation().add(Fx.jitter(0.4f), 0.05, Fx.jitter(0.4f)), chest, Paint.GREEN.light,
                        8, true);
                world.spawnParticle(Particle.HAPPY_VILLAGER, chest, Vfx.n(ctx(), 2), 0.25, 0.35, 0.25, 0, null, true);
            }
        }

        /** The rings turn slowly against each other, like paint being swirled. */
        private void turn(long t) {
            turn += 9f;
            for (Ring ring : rings) {
                if (!ring.display().isValid()) {
                    continue;
                }
                float direction = ring.band() % 2 == 0 ? 1f : -1f;
                float size = (float) (radius * 2 * RING_SHARES[ring.band()]) * 0.94f;
                Displays.animate(ring.display(), Displays.transform(0, ring.height(), 0, size, 0.02f, size,
                        ring.yaw() + direction * turn, 0, 0), (int) TURN_PERIOD);
            }
            if (t % (TURN_PERIOD * 4) == 0) {
                Fx.sound(center, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 0.8f, 0.7f + Fx.jitter(0.1f));
            }
        }

        /** A rainbow rim, glints of wet paint and motes in all three colours. */
        private void ambience(long t) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Paint[] paints = Paint.values();
            Paint paint = paints[(int) (t % paints.length)];
            Location glint = Vfx.scatter(center, radius * 0.95).add(0, 0.08, 0);
            Vfx.dot(glint, Particle.DUST_COLOR_TRANSITION, paint.sheen, true);
            if (t % 2 == 0) {
                Location mote = Vfx.scatter(center, radius).add(0, 0.3 + random.nextDouble(1.2), 0);
                Vfx.dot(mote, Particle.DUST, paint.drop, true);
            }
            if (t % 4 == 0) {
                rainbowRim(t);
            }
            if (t % 8 == 4) {
                Vfx.dot(Vfx.scatter(center, radius * 0.7).add(0, 0.12, 0), Particle.WAX_OFF, null, true);
            }
        }

        /** The rim of the zone in a turning rainbow. */
        private void rainbowRim(long t) {
            int points = Vfx.n(ctx(), (int) Math.min(42, Math.max(18, radius * 5)));
            double phase = t * 0.03;
            for (int i = 0; i < points; i++) {
                double angle = phase + i * Math.PI * 2 / points;
                Color color = hue((double) i / points + t * 0.01);
                world.spawnParticle(Particle.DUST, center.getX() + Math.cos(angle) * radius, center.getY() + 0.12,
                        center.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0, new Particle.DustOptions(color, 1.1f),
                        true);
            }
        }

        /** The paint dries: the rings shrink into the ground in a last flurry of colour; slows end. */
        private void dry() {
            if (stage == Stage.DRY) {
                return;
            }
            stage = Stage.DRY;
            slows.release();
            Player owner = player();
            for (Ring ring : rings) {
                if (ring.display().isValid()) {
                    Displays.shrinkAway(ctx(), owner, ring.display(), (int) DRY_TICKS);
                }
            }
            for (BlockDisplay droplet : droplets) {
                if (droplet.isValid()) {
                    Displays.shrinkAway(ctx(), owner, droplet, (int) DRY_TICKS);
                }
            }
            for (Paint paint : Paint.values()) {
                world.spawnParticle(Particle.DUST, center.clone().add(0, 0.4, 0), Vfx.n(ctx(), 12), radius * 0.4,
                        0.3, radius * 0.4, 0, paint.drop, true);
            }
            Fx.sound(center, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.8f, 1.2f);
            Fx.sound(center, Sound.ENTITY_PAINTING_BREAK, 0.8f, 1.2f);
            if (owner.isOnline() && (hits > 0 || heals > 0)) {
                owner.sendActionBar(Text.mm("<b>" + RAINBOW + "걸작</gradient></b> <white>물감 " + hits + "회 · 치유 "
                        + heals + "회</white>"));
            }
        }

        /** The loop ended (normally after drying, or early on an error): nothing may stay behind. */
        void release() {
            if (stage != Stage.DRY) {
                for (Part part : canvasParts) {
                    if (part.display().isValid()) {
                        part.display().remove();
                    }
                }
                if (brush != null && brush.isValid()) {
                    brush.remove();
                }
                dry();
            }
            slows.release();
        }

        /** The pose of a canvas part at tilt {@code tilt} (degrees, negative tips it away) lowered by {@code drop}. */
        private Transformation pose(Part part, float tilt, float drop) {
            return partPose(canvasYaw, tilt, drop, part.center(), part.size(), part.slant());
        }

        /** A point in the standing canvas frame (x along the canvas, y up, z out of its painted face), in the world. */
        private Location canvasPoint(float x, float y, float z) {
            Vector3f offset = new Quaternionf().rotateY((float) Math.toRadians(-canvasYaw))
                    .transform(new Vector3f(x, y, z));
            return pivot.clone().add(offset.x, offset.y, offset.z);
        }
    }

    /**
     * A block display box in the canvas frame: centered on {@code middle}, of {@code size}, slanted {@code slant}
     * degrees within the canvas plane, then the whole canvas turned to {@code yaw}, tipped {@code tilt} degrees about
     * its bottom edge (the display origin) and lowered by {@code drop}.
     */
    private static Transformation partPose(float yaw, float tilt, float drop, Vector3f middle, Vector3f size,
                                           float slant) {
        Quaternionf canvas = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(tilt));
        Quaternionf box = new Quaternionf(canvas).rotateZ((float) Math.toRadians(slant));
        Vector3f center = canvas.transform(new Vector3f(middle));
        Vector3f half = box.transform(new Vector3f(size).mul(0.5f));
        Vector3f translation = center.sub(half).add(0, -drop, 0);
        return new Transformation(translation, box, new Vector3f(size), new Quaternionf());
    }

    // ------------------------------------------------------------------ helpers

    /** A fully saturated colour at {@code hue} turns around the colour wheel (fraction, wraps). */
    private static Color hue(double hue) {
        double h = (hue % 1 + 1) % 1 * 6;
        int sector = (int) h;
        double f = h - sector;
        double rise = f;
        double fall = 1 - f;
        double r;
        double g;
        double b;
        switch (sector) {
            case 0 -> { r = 1; g = rise; b = 0; }
            case 1 -> { r = fall; g = 1; b = 0; }
            case 2 -> { r = 0; g = 1; b = rise; }
            case 3 -> { r = 0; g = fall; b = 1; }
            case 4 -> { r = rise; g = 0; b = 1; }
            default -> { r = 1; g = 0; b = fall; }
        }
        // A little white mixed in keeps the colours paint-like rather than neon.
        return Color.fromRGB((int) Math.round(40 + r * 215), (int) Math.round(40 + g * 215),
                (int) Math.round(40 + b * 215));
    }

    /** The top of the first solid block (with room above it) at most {@code depth} blocks below {@code point}. */
    private static @Nullable Double groundBelow(Location point, int depth) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = Math.min(point.getBlockY(), world.getMaxHeight() - 2);
        int end = Math.max(world.getMinHeight(), start - depth);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable() && block.getRelative(BlockFace.UP).isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /** Where thrown things leave the owner: just in front of the casting hand. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(eye.getDirection().multiply(0.55)).subtract(0, 0.3, 0);
    }

    /** False for an enemy player hidden from the owner by stealth. */
    private static boolean seen(Player owner, LivingEntity enemy) {
        return !(enemy instanceof Player hidden) || owner.canSee(hidden);
    }

    /** True if {@code count} more displays fit in {@link #MAX_DISPLAYS}. */
    private boolean roomFor(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    private static BlockData data(Material material) {
        return material.createBlockData();
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
