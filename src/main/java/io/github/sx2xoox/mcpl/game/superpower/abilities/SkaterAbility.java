package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 스케이터 — a figure skater who brings the rink along. On ice (natural ice or the skater's own) it skates faster,
 * and enemies moving over its ice lose their footing: their feet slip out and they slide on helplessly for a moment.
 * The active is a glide that lays a lane of ice behind the skater and can be steered by looking; the ultimate is a
 * triple axel: a spinning leap that lands in three shockwaves and freezes a whole rink around the landing.
 *
 * <p>Unlike the 빙결술사 (ice as a prison) and the 스피드스터 (speed by running), the skater controls ground: its ice
 * stays for a while, makes enemies on it slide out of control and speeds the skater itself up.
 *
 * <p>Look: ice blue, silver and white. The skater's blades scratch frost sparks into the ground as it moves, glinting
 * brighter on ice. Its ice is a glassy sheet of packed ice laid on the ground that glitters, cracks before it melts
 * and shrinks away. A slip throws up a spray of ice and a "미끄덩!" pop. The glide leaves silver speed lines and a
 * fan of snow at the stop. The axel winds a double ribbon of ice-blue light around the spinning skater with three ice
 * shards orbiting it, counts the turns over its head, marks the landing on the ground, and lands in three rings of
 * ice light, shattering ice shards, a frozen rink spreading out and the judges' "10.0".
 */
public final class SkaterAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("skater", "스케이터", Material.PACKED_ICE)
            .color(TextColor.color(0x7AB8FF))
            .difficulty(2)
            .passive("활주",
                    "얼음 위(내 빙판 포함)에서는 이동 속도가 {ice_speed}% 빨라집니다. 내 빙판 위에서 움직이는 적은 "
                            + "{slip_cooldown}초마다 발이 미끄러져 가던 방향으로 쭉 미끄러지고, {slip_stumble}초간 휘청여 "
                            + "이동 속도가 {slip_slow}% 느려지고 점프할 수 없습니다.")
            .active("빙판 질주",
                    "얼음을 깔며 바라보는 방향으로 {glide_distance}블록을 미끄러지듯 활주합니다(시선을 돌리면 조금씩 "
                            + "꺾입니다). 지나간 자리에는 {ice_duration}초간 폭 {ice_width}블록의 빙판이 남고, 부딪힌 적은 "
                            + "{glide_damage} 피해를 입고 옆으로 튕겨 나갑니다.",
                    9)
            .ultimate("트리플 악셀",
                    "세 바퀴 회전하며 뛰어올라 바라보는 방향으로 최대 {axel_distance}블록 날아갑니다. 착지하면 충격파가 세 번 "
                            + "퍼져(반경 {ring_first}·{ring_second}·{ring_third}블록) 닿을 때마다 {ring_damage} 피해를 주고 "
                            + "띄우며, 반경 {rink_radius}블록에 {rink_duration}초간 빙판을 깝니다. 이 점프로는 낙하 피해를 "
                            + "받지 않습니다.")
            .param("ice_speed", 35)
            .param("slip_cooldown", 1.5)
            .param("slip_stumble", 0.6)
            .param("slip_slow", 50)
            .param("glide_distance", 12)
            .param("glide_damage", 3)
            .param("ice_duration", 6)
            .param("ice_width", 2)
            .param("axel_distance", 8)
            .param("axel_height", 4)
            .param("ring_first", 2)
            .param("ring_second", 3.5)
            .param("ring_third", 5)
            .param("ring_damage", 2)
            .param("rink_radius", 6)
            .param("rink_duration", 6)
            .build();

    private static final String COLOR_TAG = "#7AB8FF";
    private static final String SILVER_TAG = "#DDE8F0";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Glinting white, ice blue, a deep rink blue. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0x9EDCFF, 0x2A6FDB);
    private static final Color SILVER = Color.fromRGB(0xDDE8F0);
    private static final Particle.DustTransition ICE_GLOW = PALETTE.glow(1.1f);
    private static final Particle.DustTransition ICE_FADE = PALETTE.fade(1.0f);
    private static final Particle.DustOptions SILVER_DUST = new Particle.DustOptions(SILVER, 0.7f);
    private static final Particle.DustOptions FROST_DUST = PALETTE.dust(0.9f);
    /** The three shockwaves of the landing, inside out. */
    private static final Particle.DustTransition[] WAVE_COLORS = {
            PALETTE.glow(1.5f), new Particle.DustTransition(Color.fromRGB(0x9EDCFF), SILVER, 1.4f), PALETTE.fade(1.3f)};

    /** Block types of the visuals; block data is created on use (it needs a running server). */
    private static final Material SHEET = Material.PACKED_ICE;
    private static final Material SHARD = Material.ICE;
    private static final Material BLUE_SHARD = Material.BLUE_ICE;

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 2.0;
    private static final long MAX_LAG_TICKS = 8;

    // passive
    private static final long SPEED_CHECK_PERIOD = 2;
    private static final long SCRATCH_PERIOD = 4;
    /** Horizontal blocks per tick (smoothed) that count as moving on the ice (walking is about 0.22). */
    private static final double SLIP_MIN_SPEED = 0.06;
    /** A single tick's movement counts at most this much (teleports are not walking). */
    private static final double MAX_STEP = 1.0;
    private static final double SLIP_SPEED = 0.85;
    private static final double SLIP_LIFT = 0.06;
    /** Ice is solid ground for this far above and below its sheet. */
    private static final double ICE_REACH_UP = 0.7;
    private static final double ICE_REACH_DOWN = 0.35;
    private static final long ZONE_CRACK_TICKS = 20;
    private static final long SLIP_FORGET_TICKS = 40;
    private static final int SLIP_POP_TICKS = 14;

    // active: ice glide
    private static final double GLIDE_SPEED = 0.9;
    /** Degrees the glide may turn towards the skater's view per tick. */
    private static final double TURN_RATE = 7;
    private static final double GLIDE_SINK = -0.08;
    private static final double GLIDE_EXIT = 0.2;
    private static final double GLIDE_HIT_REACH = 1.15;
    private static final double SHOVE_SIDE = 0.55;
    private static final double SHOVE_UP = 0.25;
    private static final double VOID_LOOKAHEAD = GLIDE_SPEED + 0.6;
    private static final double TILE_SPACING = 1.8;
    private static final float TILE_LENGTH = 1.95f;
    private static final float SHEET_THICKNESS = 0.05f;
    private static final long CHARGE_GRACE_TICKS = 3;
    private static final double STALL_EPSILON = 0.1;
    private static final int STALL_LIMIT = 3;
    private static final long LANDING_GUARD_TICKS = 60;

    // ultimate: triple axel
    private static final int TURNS = 3;
    private static final long MAX_AIR_TICKS = 60;
    /** The axel counts as landed after this many ticks on the ground without ever leaving it (plus the ping). */
    private static final long STUCK_TICKS = 6;
    private static final long FALL_GRACE_TICKS = 20;
    private static final double AIR_DRAG = 0.91;
    private static final int ORBIT_SHARDS = 3;
    private static final long WAVE_GAP = 4;
    private static final double WAVE_LIFT = 0.42;
    private static final double WAVE_PUSH = 0.3;
    /** The rink is three square sheets turned 30° apart (a twelve-pointed, nearly round rink). */
    private static final int RINK_SHEETS = 3;
    /**
     * The union of three squares 30° apart reaches from 1.155 to 1.414 times their half side from the middle; this
     * half side puts that outline within about 10% of the rink's radius on either side.
     */
    private static final double RINK_HALF_SIDE = 1 / 1.28;
    private static final int RINK_GROW_TICKS = 6;

    /** Every display this ability currently has alive, for the budget. */
    private final List<Display> displays = new ArrayList<>();
    private final List<IceZone> zones = new ArrayList<>();
    private final Map<UUID, Slip> slips = new HashMap<>();
    private final LandingGuard landing = new LandingGuard();
    private boolean iceTicking;
    private @Nullable NamespacedKey speedKey;
    private @Nullable Location lastFeet;
    private boolean gliding;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        // Displays and modifiers are owner-scoped: the framework removes them. Only forget them here.
        displays.clear();
        zones.clear();
        slips.clear();
        speedKey = null;
        lastFeet = null;
        landing.disarm();
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        landing.absorb(event, ctx().currentTick());
    }

    // ------------------------------------------------------------------ passive: skating

    @Override
    public void onTick(long tick) {
        Player owner = player();
        landing.update(owner, ctx().currentTick());
        if (tick % SPEED_CHECK_PERIOD != 0) {
            return;
        }
        Location feet = owner.getLocation();
        boolean onIce = Motion.isGrounded(owner) && (isNaturalIce(feet) || onOwnIce(feet));
        if (onIce && speedKey == null) {
            double bonus = Math.max(0, params().num("ice_speed")) / 100.0;
            if (bonus > 0) {
                speedKey = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, bonus,
                        Operation.MULTIPLY_SCALAR_1, 0);
                Vfx.ring(ctx(), feet.clone().add(0, 0.08, 0), 0.6, 12, 0, Particle.DUST_COLOR_TRANSITION, ICE_GLOW,
                        false);
                Fx.sound(feet, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.8f + Fx.jitter(0.1f));
            }
        } else if (!onIce && speedKey != null) {
            ctx().removeModifier(owner, speedKey);
            speedKey = null;
        }
        Location previous = lastFeet;
        lastFeet = feet;
        if (tick % SCRATCH_PERIOD == 0 && previous != null && previous.getWorld().equals(feet.getWorld())
                && horizontal(previous, feet) > 0.25 && Motion.isGrounded(owner)) {
            scratch(owner, previous, feet, onIce);
        }
    }

    /** The blades scratch frost into the ground as the skater moves: brighter, glinting lines on ice. */
    private void scratch(Player owner, Location from, Location to, boolean onIce) {
        Vector dir = Motion.horizontal(to.toVector().subtract(from.toVector()));
        Vector side = new Vector(-dir.getZ(), 0, dir.getX()).multiply(0.14);
        Location a = to.clone().add(0, 0.04, 0);
        Location back = a.clone().subtract(dir.clone().multiply(onIce ? 1.2 : 0.7));
        for (int s = -1; s <= 1; s += 2) {
            Vfx.line(ctx(), back.clone().add(side.clone().multiply(s)), a.clone().add(side.clone().multiply(s)),
                    onIce ? 4 : 2.5, Particle.DUST, onIce ? FROST_DUST : SILVER_DUST, false);
        }
        if (onIce) {
            Vfx.dot(a.clone().add(0, 0.1, 0), Particle.WAX_OFF, null, false);
            Fx.sound(a, Sound.ITEM_AXE_SCRAPE, 0.15f, 1.8f + Fx.jitter(0.1f));
        } else {
            Vfx.dot(a, Particle.SNOWFLAKE, null, false);
        }
    }

    private static boolean isNaturalIce(Location feet) {
        Block below = feet.clone().subtract(0, 0.05, 0).getBlock();
        return switch (below.getType()) {
            case ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE -> true;
            default -> false;
        };
    }

    private boolean onOwnIce(Location feet) {
        for (IceZone zone : zones) {
            if (zone.contains(feet)) {
                return true;
            }
        }
        return false;
    }

    private static double horizontal(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    // ------------------------------------------------------------------ the skater's ice

    /**
     * A sheet of the skater's ice: a lane (a polyline with a half width) or a round rink. It is a shape on the ground,
     * drawn with thin packed ice displays: the real ground is never changed.
     */
    private final class IceZone {
        final World world;
        /** Lane points (with their ground height); one point, the center, for a rink. */
        final List<Vector> points = new ArrayList<>();
        final double halfWidth;
        final boolean rink;
        final List<BlockDisplay> sheets = new ArrayList<>();
        long until;
        boolean cracking;

        IceZone(World world, double halfWidth, boolean rink, long until) {
            this.world = world;
            this.halfWidth = halfWidth;
            this.rink = rink;
            this.until = until;
        }

        boolean contains(Location feet) {
            if (!feet.getWorld().equals(world) || points.isEmpty()) {
                return false;
            }
            Vector at = feet.toVector();
            if (rink) {
                Vector center = points.getFirst();
                return onSheetHeight(at.getY(), center.getY())
                        && Math.hypot(at.getX() - center.getX(), at.getZ() - center.getZ()) <= halfWidth;
            }
            for (int i = 0; i < points.size(); i++) {
                Vector a = points.get(i);
                Vector b = i + 1 < points.size() ? points.get(i + 1) : a;
                if (segmentDistance(at, a, b) <= halfWidth && onSheetHeight(at.getY(), Math.min(a.getY(), b.getY()))) {
                    return true;
                }
            }
            return false;
        }

        /** The middle of the zone and how far from it the zone reaches, for finding enemies near it. */
        Vector center() {
            if (rink) {
                return points.getFirst().clone();
            }
            Vector sum = new Vector();
            for (Vector point : points) {
                sum.add(point);
            }
            return sum.multiply(1.0 / points.size());
        }

        double extent() {
            if (rink) {
                return halfWidth;
            }
            Vector center = center();
            double far = 0;
            for (Vector point : points) {
                far = Math.max(far, point.distance(center));
            }
            return far + halfWidth;
        }
    }

    private static boolean onSheetHeight(double feetY, double sheetY) {
        return feetY >= sheetY - ICE_REACH_DOWN && feetY <= sheetY + ICE_REACH_UP;
    }

    /** Horizontal distance from {@code p} to the segment {@code a}-{@code b}. */
    private static double segmentDistance(Vector p, Vector a, Vector b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double length = dx * dx + dz * dz;
        double t = length < 1.0E-9 ? 0 : Math.clamp(((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / length,
                0, 1);
        double x = a.getX() + dx * t - p.getX();
        double z = a.getZ() + dz * t - p.getZ();
        return Math.hypot(x, z);
    }

    /** How one enemy moves over the ice: where it was last tick, how fast it goes and when it may slip again. */
    private static final class Slip {
        @Nullable Location last;
        long lastSeen;
        double speed;
        Vector heading = new Vector();
        long nextSlip = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
    }

    private void addZone(IceZone zone) {
        zones.add(zone);
        if (iceTicking) {
            return;
        }
        iceTicking = true;
        Loop.repeat(ctx(), player(), 1, 1, Long.MAX_VALUE, this::tickIce, () -> iceTicking = false);
    }

    /** Every tick: the ice glitters and melts away in time, and enemies moving on it slip. */
    private boolean tickIce(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        Iterator<IceZone> it = zones.iterator();
        while (it.hasNext()) {
            IceZone zone = it.next();
            if (now >= zone.until) {
                melt(zone);
                it.remove();
                continue;
            }
            if (!zone.cracking && zone.until - now <= ZONE_CRACK_TICKS) {
                zone.cracking = true;
                Location at = zone.center().toLocation(zone.world);
                Fx.sound(at, Sound.BLOCK_GLASS_HIT, 0.8f, 0.6f);
            }
            glitter(zone, tick);
        }
        if (zones.isEmpty()) {
            slips.clear();
            return false;
        }
        Set<LivingEntity> skaters = new HashSet<>();
        for (IceZone zone : zones) {
            Location center = zone.center().toLocation(zone.world);
            skaters.addAll(ctx().enemiesNear(owner, center, zone.extent() + 1));
        }
        for (LivingEntity enemy : skaters) {
            track(owner, enemy, now);
        }
        slips.values().removeIf(slip -> now - slip.lastSeen > SLIP_FORGET_TICKS);
        return true;
    }

    /** Follows an enemy near the ice and makes it slip when it moves on the ice and its footing is due to go. */
    private void track(Player owner, LivingEntity enemy, long now) {
        Slip slip = slips.computeIfAbsent(enemy.getUniqueId(), id -> new Slip());
        Location feet = enemy.getLocation();
        Location last = slip.last;
        double moved = 0;
        if (last != null && last.getWorld().equals(feet.getWorld()) && now - slip.lastSeen == 1) {
            moved = horizontal(last, feet);
            if (moved > MAX_STEP) {
                moved = 0;
            } else if (moved > 1.0E-4) {
                slip.heading = Motion.horizontal(feet.toVector().subtract(last.toVector()));
            }
        }
        slip.speed = slip.speed * 0.5 + moved * 0.5;
        slip.last = feet;
        slip.lastSeen = now;
        if (now < slip.nextSlip || slip.speed < SLIP_MIN_SPEED || slip.heading.lengthSquared() < 1.0E-6
                || !Motion.isGrounded(enemy) || !onOwnIce(feet)) {
            return;
        }
        slip.nextSlip = now + Math.max(1, params().ticks("slip_cooldown"));
        slipOut(owner, enemy, slip.heading.clone());
    }

    /** The enemy's feet go: it slides on the way it was going and staggers for a moment. */
    private void slipOut(Player owner, LivingEntity enemy, Vector heading) {
        Motion.push(enemy, heading.clone().multiply(SLIP_SPEED).setY(SLIP_LIFT), MAX_SPEED);
        long stumble = params().ticks("slip_stumble");
        double slow = Math.clamp(params().num("slip_slow") / 100.0, 0.0, 1.0);
        if (stumble > 0) {
            if (slow > 0) {
                ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, stumble);
            }
            ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, stumble);
        }
        Location feet = enemy.getLocation().add(0, 0.1, 0);
        Fx.sound(feet, Sound.BLOCK_GLASS_STEP, 1.0f, 0.6f + Fx.jitter(0.1f));
        Fx.sound(feet, Sound.ENTITY_PLAYER_SMALL_FALL, 0.8f, 1.2f);
        Fx.sound(feet, Sound.ITEM_AXE_SCRAPE, 0.6f, 1.5f + Fx.jitter(0.1f));
        if (hiddenFromOwner(enemy)) {
            return;
        }
        World world = feet.getWorld();
        world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 10), 0.3, 0.05, 0.3, 0.1, SHARD.createBlockData());
        world.spawnParticle(Particle.SNOWFLAKE, feet, Vfx.n(ctx(), 6), 0.25, 0.05, 0.25, 0.08);
        Vfx.trail(feet, feet.clone().add(heading.clone().multiply(2.2)), PALETTE.body(), 8, false);
        if (roomFor(1)) {
            TextDisplay pop = remember(Displays.text(ctx(), owner, enemy.getLocation().add(0, enemy.getHeight() + 0.4, 0),
                    Text.mm("<b><" + COLOR_TAG + ">미끄덩!</" + COLOR_TAG + "></b>"), 0.4f, SLIP_POP_TICKS));
            Displays.animateNextTick(ctx(), owner, pop, Displays.centered(0, 0.3f, 0, 1.0f, 1.0f, 1.0f, 0, 0, -12), 4);
            ctx().runLater(owner, SLIP_POP_TICKS - 4, () -> {
                if (pop.isValid()) {
                    Displays.shrinkAway(ctx(), owner, pop, 3);
                }
            });
        }
    }

    /** Glints and frost drifting over the sheet, and a few cracks once it is about to melt. */
    private void glitter(IceZone zone, long tick) {
        if (tick % 4 != 0 || zone.points.isEmpty()) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < Vfx.n(ctx(), zone.rink ? 3 : 2); i++) {
            Location at;
            if (zone.rink) {
                at = Vfx.scatter(zone.points.getFirst().toLocation(zone.world), zone.halfWidth);
            } else {
                Vector point = zone.points.get(random.nextInt(zone.points.size()));
                at = Vfx.scatter(point.toLocation(zone.world), zone.halfWidth);
            }
            at.add(0, 0.12, 0);
            Vfx.dot(at, zone.cracking ? Particle.DUST : Particle.WAX_OFF, zone.cracking ? FROST_DUST : null, false);
        }
        if (zone.cracking && tick % 8 == 0) {
            for (BlockDisplay sheet : zone.sheets) {
                if (sheet.isValid()) {
                    Location at = sheet.getLocation().add(0, 0.1, 0);
                    zone.world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 2), 0.4, 0.02, 0.4, 0,
                            SHARD.createBlockData());
                }
            }
        }
    }

    private void melt(IceZone zone) {
        for (BlockDisplay sheet : zone.sheets) {
            if (sheet.isValid()) {
                Displays.shrinkAway(ctx(), player(), sheet, 8);
            }
        }
        if (!zone.points.isEmpty()) {
            Location at = zone.center().toLocation(zone.world);
            Fx.sound(at, Sound.BLOCK_GLASS_BREAK, 0.6f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.3f, 1.8f);
        }
    }

    // ------------------------------------------------------------------ active: ice glide

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 활주할 수 없습니다");
        }
        if (gliding) {
            return ActivationResult.fail("이미 활주 중입니다");
        }
        Location start = owner.getLocation();
        Vector heading = Motion.horizontalFacing(start);
        if (heading.lengthSquared() < 1.0E-6) {
            return ActivationResult.fail("활주할 방향이 없습니다");
        }
        if (clearAhead(owner, start, heading, 1.2) < 1.0) {
            return ActivationResult.fail("앞이 막혀 있습니다");
        }
        if (!Spots.hasGroundBelow(start.getWorld(), start.clone().add(heading.clone().multiply(VOID_LOOKAHEAD))
                .add(0, 0.5, 0))) {
            return ActivationResult.fail("앞이 낭떠러지입니다");
        }
        gliding = true;
        Glide glide = new Glide(owner, start, heading);
        long maxTicks = (long) Math.ceil(glide.distance / GLIDE_SPEED) + CHARGE_GRACE_TICKS + lagTicks(owner) + 4;
        Loop.repeat(ctx(), owner, 0, 1, maxTicks, glide, glide::end);
        return ActivationResult.success();
    }

    /** How far the way along {@code heading} is clear of walls, checked at knee and chest height. */
    private static double clearAhead(Player owner, Location from, Vector heading, double distance) {
        World world = from.getWorld();
        double reach = distance;
        for (double height : new double[] {0.35, owner.getHeight() * 0.75}) {
            Location eye = from.clone().add(0, height, 0);
            RayTraceResult wall = world.rayTraceBlocks(eye, heading, distance + 0.4, FluidCollisionMode.NEVER, true);
            if (wall != null) {
                reach = Math.min(reach, wall.getHitPosition().distance(eye.toVector()) - 0.4);
            }
        }
        return Math.max(0, reach);
    }

    /**
     * The glide: the skater is sent along a lane that bends towards where it looks, laying ice sheets on the way
     * (where its client goes: the sum of the steps sent), bumping enemies aside, until the distance is used up or a
     * wall, a drop or the arena border stops it.
     */
    private final class Glide implements LongPredicate {

        private final Player owner;
        private final World world;
        private final double distance;
        private final IceZone zone;
        private final Set<UUID> struck = new HashSet<>();
        private final long lag;
        private Vector heading;
        /** Where the skater's client is: the start plus every step sent. */
        private final Location planned;
        private final Location begin;
        private Location last;
        private double sent;
        private double sinceTile;
        private int stalls;
        private boolean moved;
        private boolean ended;
        private int hits;

        Glide(Player owner, Location start, Vector heading) {
            this.owner = owner;
            this.world = start.getWorld();
            this.heading = heading.clone();
            this.distance = Spots.arenaReach(ctx(), start, heading, Math.max(1, params().num("glide_distance")));
            this.planned = Displays.unrotated(start);
            this.begin = start.clone();
            this.last = start.clone();
            this.lag = lagTicks(owner);
            double half = Math.max(0.3, params().num("ice_width") / 2);
            long until = ctx().currentTick() + (long) Math.ceil(distance / GLIDE_SPEED) + Math.max(1,
                    params().ticks("ice_duration"));
            this.zone = new IceZone(world, half, false, until);
            zone.points.add(planned.toVector());
            addZone(zone);
            landing.arm(ctx().currentTick(), LANDING_GUARD_TICKS);
            launchFx(start);
            layTile(planned.clone(), yawOf(heading));
        }

        @Override
        public boolean test(long tick) {
            Location now = owner.getLocation();
            if (!now.getWorld().equals(world) || sent >= distance - 0.05) {
                return false;
            }
            if (!moved && horizontal(begin, now) >= STALL_EPSILON) {
                moved = true;
            }
            if (moved || tick >= CHARGE_GRACE_TICKS + lag) {
                stalls = horizontal(last, now) < STALL_EPSILON ? stalls + 1 : 0;
                if (stalls >= STALL_LIMIT) {
                    return false;
                }
            }
            last = now;
            steer();
            double step = Math.min(GLIDE_SPEED, distance - sent);
            if (clearAhead(owner, planned, heading, step) < step - 1.0E-3) {
                return false;
            }
            Location ahead = planned.clone().add(heading.clone().multiply(step + VOID_LOOKAHEAD - GLIDE_SPEED))
                    .add(0, 0.5, 0);
            if (!Spots.hasGroundBelow(world, ahead)) {
                return false;
            }
            Location next = planned.clone().add(heading.clone().multiply(step));
            if (!ctx().isInsideArena(next)) {
                return false;
            }
            sent += step;
            sinceTile += step;
            planned.setX(next.getX());
            planned.setZ(next.getZ());
            Location ground = planned.clone().add(0, 0.6, 0);
            Spots.snapToGround(world, ground, 1.8);
            planned.setY(ground.getY());
            owner.setVelocity(Motion.clamp(heading.clone().multiply(step).setY(GLIDE_SINK), MAX_SPEED));
            owner.setFallDistance(0);
            zone.points.add(planned.toVector());
            zone.until = Math.max(zone.until, ctx().currentTick() + Math.max(1, params().ticks("ice_duration")));
            if (sinceTile >= TILE_SPACING) {
                sinceTile -= TILE_SPACING;
                layTile(planned.clone().subtract(heading.clone().multiply(TILE_SPACING / 2)), yawOf(heading));
            }
            strike(now);
            streak(now, tick);
            return true;
        }

        /** The lane bends towards where the skater looks, a few degrees per tick. */
        private void steer() {
            Vector want = Motion.horizontalFacing(owner.getLocation());
            if (want.lengthSquared() < 1.0E-6) {
                return;
            }
            double current = Math.atan2(heading.getZ(), heading.getX());
            double target = Math.atan2(want.getZ(), want.getX());
            double delta = Math.atan2(Math.sin(target - current), Math.cos(target - current));
            double limit = Math.toRadians(TURN_RATE);
            double turned = current + Math.clamp(delta, -limit, limit);
            heading = new Vector(Math.cos(turned), 0, Math.sin(turned));
        }

        /** Enemies in front of the skater are bumped aside, once each. */
        private void strike(Location now) {
            Location front = now.clone().add(0, owner.getHeight() * 0.5, 0).add(heading.clone().multiply(0.6));
            for (LivingEntity enemy : ctx().enemiesNear(owner, front, GLIDE_HIT_REACH)) {
                if (!struck.add(enemy.getUniqueId())) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, params().num("glide_damage"));
                hits++;
                Location body = Fx.body(enemy);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Vector offset = body.toVector().subtract(now.toVector()).setY(0);
                    Vector side = new Vector(-heading.getZ(), 0, heading.getX());
                    if (offset.dot(side) < 0) {
                        side.multiply(-1);
                    }
                    Motion.push(enemy, side.multiply(SHOVE_SIDE).add(heading.clone().multiply(0.25)).setY(SHOVE_UP),
                            MAX_SPEED);
                }
                if (!hiddenFromOwner(enemy)) {
                    Vfx.dot(body, Particle.FLASH, PALETTE.core(), false);
                    world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 12), 0.3, 0.4, 0.3, 0.1,
                            SHARD.createBlockData());
                    world.spawnParticle(Particle.SNOWFLAKE, body, Vfx.n(ctx(), 8), 0.3, 0.4, 0.3, 0.1);
                }
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.5f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.BLOCK_GLASS_BREAK, 0.7f, 1.6f + Fx.jitter(0.1f));
            }
        }

        /** Silver speed lines and blowing snow behind the gliding skater. */
        private void streak(Location now, long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Location from = now.clone().add(random.nextDouble(-0.3, 0.3), random.nextDouble(0.1, 1.6),
                        random.nextDouble(-0.3, 0.3));
                Vfx.trail(from, from.clone().subtract(heading.clone().multiply(1.8 + random.nextDouble())),
                        i % 2 == 0 ? SILVER : PALETTE.body(), 5 + random.nextInt(3), false);
            }
            Location heel = now.clone().add(0, 0.08, 0);
            world.spawnParticle(Particle.SNOWFLAKE, heel, Vfx.n(ctx(), 2), 0.2, 0.02, 0.2, 0.06);
            if (tick % 3 == 0) {
                Fx.sound(heel, Sound.ITEM_AXE_SCRAPE, 0.4f, 1.6f + Fx.jitter(0.1f));
                Fx.sound(heel, Sound.BLOCK_GLASS_STEP, 0.5f, 1.4f + Fx.jitter(0.1f));
            }
        }

        /** The stop: a fan of snow thrown up by the blades, a last tile under the skater. */
        void end() {
            if (ended) {
                return;
            }
            ended = true;
            gliding = false;
            if (!owner.isValid() || owner.isDead()) {
                return;
            }
            owner.setVelocity(heading.clone().multiply(GLIDE_EXIT).setY(GLIDE_SINK));
            if (sinceTile > 0.6) {
                layTile(planned.clone().subtract(heading.clone().multiply(sinceTile / 2)), yawOf(heading));
            }
            Location feet = owner.getLocation();
            float yaw = yawOf(heading);
            Vfx.arc(ctx(), feet.clone().add(0, 0.2, 0), 1.0, yaw, 150, 14, Particle.SNOWFLAKE, null, false);
            Vfx.arc(ctx(), feet.clone().add(0, 0.1, 0), 1.3, yaw, 130, 12, Particle.DUST_COLOR_TRANSITION, ICE_FADE,
                    false);
            world.spawnParticle(Particle.BLOCK, feet.clone().add(heading.clone().multiply(0.6)), Vfx.n(ctx(), 10), 0.4,
                    0.1, 0.4, 0.15, SHEET.createBlockData());
            Fx.sound(feet, Sound.ITEM_AXE_SCRAPE, 0.9f, 1.0f);
            Fx.sound(feet, Sound.BLOCK_POWDER_SNOW_STEP, 1.0f, 1.2f);
            if (hits > 0) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⛸ 빙판 질주</" + COLOR_TAG + "> <white>" + hits
                        + "명 적중</white>"));
            }
        }

        /** One sheet of ice along the lane, laid flat on the ground and turned along the glide. */
        private void layTile(Location at, float yaw) {
            if (!roomFor(1)) {
                return;
            }
            Location spot = at.clone().add(0, 0.6, 0);
            Spots.snapToGround(world, spot, 1.8);
            float width = (float) (zone.halfWidth * 2);
            // Generous: the sheet melts with its lane (see melt), the lifetime is only a safety net.
            long life = Math.max(1, zone.until - ctx().currentTick()) + 40;
            BlockDisplay sheet = remember(Displays.block(ctx(), owner, spot, SHEET.createBlockData(),
                    sheetPose(width * 0.2f, TILE_LENGTH * 0.4f, yaw), life));
            Displays.animateNextTick(ctx(), owner, sheet, sheetPose(width, TILE_LENGTH, yaw), 3);
            zone.sheets.add(sheet);
            world.spawnParticle(Particle.SNOWFLAKE, spot.clone().add(0, 0.1, 0), Vfx.n(ctx(), 4), 0.5, 0.02, 0.5,
                    0.03);
            Vfx.dot(spot.clone().add(0, 0.12, 0), Particle.DUST_COLOR_TRANSITION, ICE_GLOW, false);
        }
    }

    /**
     * A thin sheet of ice lying on the ground, {@code width} across and {@code length} long along {@code yaw}
     * (Minecraft's yaw: 0 = +Z, 90 = -X): its long side is the block's Z axis, turned by the transformation.
     */
    static Transformation sheetPose(float width, float length, float yaw) {
        return Displays.transform(0, SHEET_THICKNESS / 2 + 0.006f, 0, width, SHEET_THICKNESS, length, yaw, 0, 0);
    }

    private void launchFx(Location start) {
        Location feet = start.clone().add(0, 0.1, 0);
        World world = feet.getWorld();
        Vfx.ring(ctx(), feet, 0.8, 14, 0, Particle.DUST_COLOR_TRANSITION, ICE_GLOW, false);
        world.spawnParticle(Particle.SNOWFLAKE, feet, Vfx.n(ctx(), 10), 0.4, 0.05, 0.4, 0.08);
        Vfx.dot(feet.clone().add(0, 0.9, 0), Particle.FLASH, PALETTE.body(), false);
        Fx.sound(feet, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.6f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(feet, Sound.ITEM_AXE_SCRAPE, 1.0f, 0.8f);
        Fx.sound(feet, Sound.BLOCK_GLASS_PLACE, 0.8f, 1.4f);
    }

    // ------------------------------------------------------------------ ultimate: triple axel

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 뛸 수 없습니다");
        }
        new Axel(owner).leap();
        return ActivationResult.success();
    }

    /**
     * The triple axel: a spinning leap along the skater's facing (shortened by walls and the arena border), three turns
     * in the air, and the landing: three shockwaves rolling out one after another and a rink frozen around the spot.
     */
    private final class Axel implements LongPredicate {

        private final Player owner;
        private final World world;
        private final Vector forward;
        private final long airTicks;
        private final List<BlockDisplay> orbit = new ArrayList<>();
        private final Location start;
        private final Location predicted;
        private final long lag;
        private boolean airborne;
        private int turnsShown;
        private boolean landed;

        Axel(Player owner) {
            this.owner = owner;
            this.world = owner.getWorld();
            this.start = owner.getLocation();
            this.lag = lagTicks(owner);
            Vector facing = Motion.horizontalFacing(start);
            double height = Math.max(0.5, params().num("axel_height"));
            double up = Motion.launchSpeed(height);
            this.airTicks = flightTicks(up);
            double distance = Math.max(0, params().num("axel_distance"));
            if (facing.lengthSquared() > 1.0E-6 && distance > 0) {
                distance = Spots.arenaReach(ctx(), start, facing, distance);
                distance = Math.min(distance, clearAhead(owner, start.clone().add(0, Math.min(height, 2.5), 0),
                        facing, distance));
            } else {
                distance = 0;
            }
            double reach = (1 - Math.pow(AIR_DRAG, airTicks)) / (1 - AIR_DRAG);
            double speed = distance <= 0 ? 0 : distance / reach;
            this.forward = facing.lengthSquared() > 1.0E-6 ? facing.clone().multiply(speed) : new Vector();
            this.predicted = start.clone().add(facing.clone().multiply(distance));
            owner.setVelocity(Motion.clamp(forward.clone().setY(up), MAX_SPEED + 0.5));
            owner.setFallDistance(0);
        }

        void leap() {
            long guard = MAX_AIR_TICKS + FALL_GRACE_TICKS;
            // No fall damage from this jump, also for a borrowed axel that gets no damage hooks.
            ctx().addModifier(owner, owner, Attribute.FALL_DAMAGE_MULTIPLIER, -1.0, Operation.MULTIPLY_SCALAR_1,
                    guard);
            landing.arm(ctx().currentTick(), guard);
            for (int i = 0; i < ORBIT_SHARDS && roomFor(1); i++) {
                BlockDisplay shard = remember(Displays.block(ctx(), owner, Displays.unrotated(start),
                        (i % 2 == 0 ? SHARD : BLUE_SHARD).createBlockData(), Displays.scaled(0.05f),
                        MAX_AIR_TICKS + 10));
                shard.setTeleportDuration(1);
                orbit.add(shard);
            }
            Location feet = start.clone().add(0, 0.1, 0);
            Vfx.ring(ctx(), feet, 1.0, 16, 0, Particle.DUST_COLOR_TRANSITION, ICE_GLOW, true);
            world.spawnParticle(Particle.SNOWFLAKE, feet, Vfx.n(ctx(), 16), 0.5, 0.05, 0.5, 0.12, null, true);
            Fx.sound(feet, Sound.ITEM_AXE_SCRAPE, 1.2f, 0.7f);
            Fx.sound(feet, Sound.ENTITY_BREEZE_JUMP, 0.9f, 1.2f);
            Fx.sound(feet, Sound.ITEM_TRIDENT_RIPTIDE_2, 0.7f, 1.4f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⛸ 트리플 악셀!</b></" + COLOR_TAG + ">"));
            Loop.repeat(ctx(), owner, 1, 1, MAX_AIR_TICKS + lag, this, this::land);
        }

        @Override
        public boolean test(long tick) {
            Location now = owner.getLocation();
            if (!now.getWorld().equals(world)) {
                return false;
            }
            owner.setFallDistance(0);
            boolean grounded = Motion.isGrounded(owner) || owner.isInWater() || owner.isClimbing();
            if (!grounded) {
                airborne = true;
            } else if (airborne || tick >= STUCK_TICKS + lag) {
                // Landed; or never left the ground (a low ceiling), once its client had time to take off.
                return false;
            }
            spin(now, tick);
            telegraph(tick);
            return true;
        }

        /** The spin: a double ribbon of light around the skater, three ice shards orbiting it, the turns counted. */
        private void spin(Location now, long tick) {
            double progress = Math.min(1.0, (tick + 1) / (double) Math.max(1, airTicks));
            double angle = progress * TURNS * TAU;
            Location waist = now.clone().add(0, owner.getHeight() * 0.5, 0);
            for (int strand = 0; strand < 2; strand++) {
                double a = angle + strand * Math.PI;
                for (int k = 0; k < 3; k++) {
                    double lagged = a - k * 0.35;
                    Location at = waist.clone().add(Math.cos(lagged) * 0.75, -0.5 + strand + k * 0.12,
                            Math.sin(lagged) * 0.75);
                    Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, k == 0 ? ICE_GLOW : ICE_FADE, true);
                }
            }
            for (int i = 0; i < orbit.size(); i++) {
                BlockDisplay shard = orbit.get(i);
                double a = angle * 1.5 + i * TAU / orbit.size();
                Displays.moveTo(shard, waist.clone().add(Math.cos(a) * 0.95, Math.sin(angle + i) * 0.4,
                        Math.sin(a) * 0.95));
                Displays.animate(shard, Displays.transform(0, 0, 0, 0.16f, 0.16f, 0.5f,
                        (float) Math.toDegrees(-a), 20, (float) (tick * 30)), 1);
            }
            int turns = Math.min(TURNS, (int) Math.floor(progress * TURNS + 1.0E-6));
            while (turnsShown < turns) {
                turnsShown++;
                turnFx(now, turnsShown);
            }
        }

        /** A completed turn: a whoosh rising in pitch and the count popping over the skater. */
        private void turnFx(Location now, int turn) {
            Location over = now.clone().add(0, owner.getHeight() + 0.6, 0);
            Fx.sound(over, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.1f + 0.25f * turn);
            Fx.sound(over, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f + 0.2f * turn);
            Vfx.ring(ctx(), now.clone().add(0, owner.getHeight() * 0.5, 0), 1.1, 18, turn, Particle.DUST,
                    SILVER_DUST, true);
            if (roomFor(1)) {
                String text = turn == TURNS ? "<b><" + COLOR_TAG + ">" + turn + "회전!</" + COLOR_TAG + "></b>"
                        : "<" + SILVER_TAG + ">" + turn + "회전</" + SILVER_TAG + ">";
                TextDisplay count = remember(Displays.text(ctx(), owner, over, Text.mm(text), 0.5f, 14));
                Displays.animateNextTick(ctx(), owner, count, Displays.centered(0, 0.35f, 0, 1.1f, 1.1f, 1.1f, 0, 0,
                        0), 4);
                ctx().runLater(owner, 10, () -> {
                    if (count.isValid()) {
                        Displays.shrinkAway(ctx(), owner, count, 3);
                    }
                });
            }
        }

        /** The landing spot is marked on the ground: where the axel is headed, then right under the falling skater. */
        private void telegraph(long tick) {
            double radius = Math.max(0.5, params().num("ring_third"));
            Location mark;
            if (tick < airTicks / 2) {
                mark = predicted.clone().add(0, 0.6, 0);
                Spots.snapToGround(world, mark, 6);
            } else {
                RayTraceResult ground = world.rayTraceBlocks(owner.getLocation(), new Vector(0, -1, 0), 24,
                        FluidCollisionMode.ALWAYS, true);
                if (ground == null) {
                    return;
                }
                mark = ground.getHitPosition().toLocation(world);
            }
            if (tick % 2 == 0) {
                Vfx.ring(ctx(), mark.add(0, 0.12, 0), radius, (int) Math.max(16, radius * 5), tick * 0.15,
                        Particle.DUST_COLOR_TRANSITION, ICE_FADE, true);
                Vfx.ring(ctx(), mark, Math.max(0.4, params().num("ring_first")), 12, -tick * 0.2, Particle.DUST,
                        FROST_DUST, true);
            }
        }

        /** The landing: three waves, the rink and the judges' scores. */
        void land() {
            if (landed) {
                return;
            }
            landed = true;
            for (BlockDisplay shard : orbit) {
                if (shard.isValid()) {
                    Displays.shrinkAway(ctx(), owner, shard, 3);
                }
            }
            if (!owner.isValid() || owner.isDead()) {
                return;
            }
            owner.setFallDistance(0);
            Location center = Displays.unrotated(owner.getLocation());
            Location ground = center.clone().add(0, 0.6, 0);
            Spots.snapToGround(world, ground, 2.5);
            landingFx(ground);
            rink(ground);
            double[] radii = {params().num("ring_first"), params().num("ring_second"), params().num("ring_third")};
            double damage = params().num("ring_damage");
            int[] hits = {0};
            for (int wave = 0; wave < radii.length; wave++) {
                int index = wave;
                double radius = Math.max(0.5, radii[wave]);
                ctx().runLater(owner, 1 + wave * WAVE_GAP, () -> wave(ground, radius, damage, index, hits));
            }
            ctx().runLater(owner, 2 + 2 * WAVE_GAP, () -> {
                if (hits[0] > 0 && owner.isOnline()) {
                    owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⛸ 트리플 악셀</b></" + COLOR_TAG + "> <white>"
                            + hits[0] + "회 적중</white>"));
                }
            });
        }

        private void wave(Location ground, double radius, double damage, int index, int[] hits) {
            Location heart = ground.clone().add(0, 1.0, 0);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.15 + 0.1 * index, 0), 0.4, radius, 4,
                    Particle.DUST_COLOR_TRANSITION, WAVE_COLORS[index % WAVE_COLORS.length], true);
            Vfx.ring(ctx(), ground.clone().add(0, 0.3, 0), radius, (int) Math.max(14, radius * 5), index,
                    Particle.SNOWFLAKE, null, true);
            Fx.sound(ground, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f + 0.25f * index);
            Fx.sound(ground, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.8f, 1.0f + 0.2f * index);
            for (LivingEntity enemy : ctx().enemiesNear(owner, ground, radius)) {
                if (!Spots.clearPath(heart, Fx.body(enemy))) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                hits[0]++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(ground.toVector()));
                Motion.push(enemy, away.multiply(WAVE_PUSH).setY(WAVE_LIFT), MAX_SPEED);
                if (hiddenFromOwner(enemy)) {
                    continue;
                }
                Location body = Fx.body(enemy);
                world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 8), 0.3, 0.4, 0.3, 0.1,
                        SHARD.createBlockData());
                Vfx.dot(body, Particle.FLASH, PALETTE.core(), false);
            }
        }

        private void landingFx(Location ground) {
            Location at = ground.clone().add(0, 0.5, 0);
            Vfx.dot(at, Particle.FLASH, PALETTE.core(), true);
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 40), 1.2, 0.3, 1.2, 0.25, SHARD.createBlockData(),
                    true);
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 20), 1.0, 0.3, 1.0, 0.2, SHEET.createBlockData(),
                    true);
            Vfx.sphere(ctx(), at, 1.2, 30, Particle.SNOWFLAKE, null, true);
            Fx.sound(ground, Sound.ITEM_MACE_SMASH_GROUND, 1.2f, 1.1f);
            Fx.sound(ground, Sound.BLOCK_GLASS_BREAK, 1.3f, 0.6f);
            Fx.sound(ground, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
            if (roomFor(1)) {
                TextDisplay score = remember(Displays.text(ctx(), owner, ground.clone().add(0, 3.0, 0),
                        Text.mm("<b><" + SILVER_TAG + ">10.0</" + SILVER_TAG + "> <" + COLOR_TAG + ">10.0</" + COLOR_TAG
                                + "> <" + SILVER_TAG + ">10.0</" + SILVER_TAG + "></b>"), 0.5f, 34));
                score.setViewRange(2.0f);
                Displays.animateNextTick(ctx(), owner, score, Displays.centered(0, 0.4f, 0, 1.3f, 1.3f, 1.3f, 0, 0, 0),
                        5);
                ctx().runLater(owner, 28, () -> {
                    if (score.isValid()) {
                        Displays.shrinkAway(ctx(), owner, score, 4);
                    }
                });
            }
        }

        /** The rink: three square sheets turned 30° apart, together a nearly round rink around the landing. */
        private void rink(Location ground) {
            double radius = Math.max(0.5, params().num("rink_radius"));
            long duration = params().ticks("rink_duration");
            if (duration <= 0) {
                return;
            }
            IceZone zone = new IceZone(world, radius, true, ctx().currentTick() + duration);
            zone.points.add(ground.toVector());
            float side = (float) (radius * 2 * RINK_HALF_SIDE);
            for (int i = 0; i < RINK_SHEETS && roomFor(1); i++) {
                float yaw = i * 30f;
                float lift = 0.006f + 0.003f * i;
                BlockDisplay sheet = remember(Displays.block(ctx(), owner, ground, SHEET.createBlockData(),
                        rinkPose(side * 0.1f, yaw, lift), duration + 12));
                sheet.setViewRange(2.0f);
                Displays.animateNextTick(ctx(), owner, sheet, rinkPose(side, yaw, lift), RINK_GROW_TICKS);
                zone.sheets.add(sheet);
            }
            addZone(zone);
        }
    }

    /** A flat square sheet of {@code side} lying on the ground, turned {@code yaw} degrees, {@code lift} above it. */
    static Transformation rinkPose(float side, float yaw, float lift) {
        return Displays.transform(0, SHEET_THICKNESS / 2 + lift, 0, side, SHEET_THICKNESS, side, yaw, 0, 0);
    }

    /**
     * Ticks an entity launched upwards at {@code up} blocks per tick stays in the air before it is back at its start
     * height (vanilla gravity and drag).
     */
    private static long flightTicks(double up) {
        double y = 0;
        double v = up;
        for (int i = 1; i <= MAX_AIR_TICKS; i++) {
            y += v;
            v = (v - Motion.GRAVITY) * Motion.DRAG;
            if (y <= 0 && i > 1) {
                return i;
            }
        }
        return MAX_AIR_TICKS;
    }

    // ------------------------------------------------------------------ helpers

    /** An enemy hidden from the skater (stealth) must not be given away by effects drawn on it. */
    private boolean hiddenFromOwner(LivingEntity enemy) {
        return enemy instanceof Player victim && !player().canSee(victim);
    }

    /** Ticks the server sees a player's movement late: one round trip, capped. */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / 50.0));
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}, 0 if it has none. */
    static float yawOf(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    private boolean roomFor(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T remember(T display) {
        displays.add(display);
        return display;
    }
}
