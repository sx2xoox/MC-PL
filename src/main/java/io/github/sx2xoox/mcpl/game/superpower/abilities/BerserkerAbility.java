package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 광전사 — a melee bruiser that grows stronger the closer it is to death. Missing health boosts melee damage,
 * the charge bowls enemies out of the way, and the frenzy turns melee hits into healing.
 *
 * <p>Look: blood red. Rage is a heartbeat ring at the feet with embers rising off the body that grows with the
 * missing health; raging hits leave a crimson crescent. The charge drags speed streaks and cleaves struck enemies
 * with a ghostly axe. The frenzy erupts in red glass flame spikes and a blood column, then burns as a flame-like
 * aura around the berserker.
 */
public final class BerserkerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("berserker", "광전사", Material.IRON_AXE)
            .color(TextColor.color(0xE0303A))
            .difficulty(1)
            .passive("분노",
                    "잃은 체력에 비례해 근접 피해가 최대 {rage_max}% 증가합니다. 체력이 {rage_health} 이하이면 최대치입니다.")
            .active("돌진",
                    "바라보는 방향으로 {charge_distance}블록 돌진합니다. 부딪힌 적에게 {charge_damage} 피해(분노 적용)를 주고 "
                            + "옆으로 튕겨냅니다.",
                    7)
            .ultimate("광폭화",
                    "{ult_duration}초간 힘 II와 신속 II를 얻고, 근접 공격과 돌진으로 입힌 피해의 {ult_lifesteal}%만큼 "
                            + "체력을 회복합니다.")
            .ultimateNotStandalone()
            .param("rage_max", 50)
            .param("rage_health", 4)
            .param("charge_distance", 8)
            .param("charge_damage", 4)
            .param("ult_duration", 8)
            .param("ult_lifesteal", 30)
            .build();

    private static final double TAU = Math.PI * 2;

    // Charge
    private static final double CHARGE_SPEED = 1.15;
    /** Small downward pull so the charge hugs the ground instead of floating off ledges. */
    private static final double CHARGE_SINK = -0.08;
    /** The little forward glide left after the charge (none at a wall, an edge or the border). */
    private static final double CHARGE_GLIDE = 0.25;
    private static final double CHARGE_HIT_RADIUS = 1.2;
    /** Ticks before stall detection starts (the client needs a moment to pick up the new velocity). */
    private static final long CHARGE_GRACE_TICKS = 3;
    private static final double STALL_EPSILON = 0.12;
    /** Ticks in a row without movement that end the charge; three, so one late movement packet does not. */
    private static final int STALL_LIMIT = 3;
    /** Most ticks a slow connection may add to the grace period and the charge length. */
    private static final long MAX_LAG_TICKS = 8;
    private static final double MILLIS_PER_TICK = 50.0;
    /** Runs a charge may take beyond its length at full speed, for stairs or a cobweb that slow the client down. */
    private static final long CHARGE_SLACK_TICKS = 6;
    /** How far ahead the charge looks for ground; it stops at the edge of the void or a lava pit. */
    private static final double VOID_LOOKAHEAD = CHARGE_SPEED + 0.6;
    /** The client steps up onto anything this high (a slab, a stair) while it runs on the ground. */
    private static final double STEP_HEIGHT = 0.6;
    /**
     * The server seeing the berserker this far from every place its client was reckoned to be means something else
     * moved it (a knockback, a teleport): the reckoning has lost it.
     */
    private static final double STRAY_DISTANCE = 3.0;
    private static final double BOWL_FORWARD = 0.9;
    private static final double BOWL_SIDE = 0.75;
    private static final double BOWL_UP = 0.42;
    private static final double MAX_VELOCITY = 2.5;

    // Rage feedback
    private static final long RAGE_TICK_PERIOD = 10;
    private static final long RAGE_AURA_PERIOD = 5;
    /** At full rage the aura beats like a heart, once per this many ticks. */
    private static final long RAGE_BEAT_PERIOD = 20;
    private static final double RAGE_STEP_PERCENT = 10;
    /** Rage below this share shows no aura. */
    private static final double RAGE_VISIBLE = 0.1;
    /** Melee hits with at least this much rage show the crimson crescent. */
    private static final double RAGE_HIT_FEEDBACK = 0.3;

    // Frenzy
    private static final int FRENZY_AMPLIFIER = 1;
    private static final long FRENZY_VISUAL_PERIOD = 2;

    // Visuals
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFF8A80, 0xE0303A, 0x5A0000);
    private static final Particle.DustOptions CORE_DUST = new Particle.DustOptions(PALETTE.core(), 0.8f);
    private static final Particle.DustOptions EDGE_DUST = new Particle.DustOptions(PALETTE.edge(), 1.1f);
    /** Ticks a charge speed streak takes to fly back past the berserker. */
    private static final int STREAK_TICKS = 5;
    private static final double SLASH_WIDTH_DEGREES = 130;
    private static final float AXE_SCALE = 1.6f;
    private static final float AXE_FROM_ROLL = 70;
    private static final float AXE_TO_ROLL = -65;
    private static final int AXE_SWING_TICKS = 3;
    private static final int AXE_FADE_TICKS = 4;
    private static final double FRENZY_WAVE_RADIUS = 3.6;
    private static final long FRENZY_WAVE_TICKS = 6;
    private static final int FLAME_SPIKES = 8;
    private static final int BLOOD_SPIKES = 4;
    private static final long SPIKE_HOLD_TICKS = 7;
    private static final int SPIKE_RISE_TICKS = 3;
    private static final int SPIKE_SINK_TICKS = 5;

    private boolean charging;
    /** True while the charge deals its damage, so the frenzy lifesteal also counts it. */
    private boolean chargeImpact;
    private long frenzyUntil = -1;
    private int lastRageStep;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        charging = false;
        chargeImpact = false;
        frenzyUntil = -1;
    }

    // ------------------------------------------------------------------ passive: rage

    /** 0..1: how much of the rage bonus is unlocked by missing health. */
    private double rageFraction() {
        Player owner = player();
        double max = Combat.maxHealth(owner);
        double floor = Math.min(Math.max(0, params().num("rage_health")), max - 1);
        double missing = max - owner.getHealth();
        return clamp01(missing / (max - floor));
    }

    /** Damage multiplier from rage, e.g. 1.5 at full rage with the default 50%. */
    private double rageMultiplier() {
        return 1.0 + Math.max(0, params().num("rage_max")) / 100.0 * rageFraction();
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !ctx().isEnemy(player(), victim)) {
            return;
        }
        if (ctx().isAbilityDamage()) {
            // The charge already applied rage to its own damage; it only feeds the frenzy lifesteal.
            if (chargeImpact && isFrenzied()) {
                scheduleLifesteal(event, victim);
            }
            return;
        }
        if (!isOwnMelee(event)) {
            return;
        }
        double fraction = rageFraction();
        if (fraction > 0) {
            event.setDamage(event.getDamage() * rageMultiplier());
            if (fraction >= RAGE_HIT_FEEDBACK) {
                rageHit(victim, fraction);
            }
        }
        if (isFrenzied()) {
            scheduleLifesteal(event, victim);
        }
    }

    private boolean isOwnMelee(EntityDamageByEntityEvent event) {
        DamageCause cause = event.getCause();
        return (cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK)
                && player().equals(event.getDamager());
    }

    @Override
    public void onTick(long tick) {
        if (tick % RAGE_AURA_PERIOD != 0) {
            return;
        }
        double fraction = rageFraction();
        if (tick % RAGE_TICK_PERIOD == 0) {
            double maxBonus = Math.max(0, params().num("rage_max"));
            int step = (int) Math.floor(maxBonus * fraction / RAGE_STEP_PERCENT + 1.0E-6);
            if (step > lastRageStep) {
                String percent = AbilityInfo.formatNumber(Math.round(maxBonus * fraction));
                player().sendActionBar(fraction >= 0.999
                        ? Text.mm("<#E0303A><b>분노 최대!</b></#E0303A> <gray>근접 피해 +" + percent + "%</gray>")
                        : Text.mm("<#E0303A>분노</#E0303A> <gray>근접 피해 +" + percent + "%</gray>"));
            }
            lastRageStep = step;
        }
        if (fraction >= RAGE_VISIBLE) {
            rageAura(tick, fraction);
        }
    }

    /** A ring at the feet that widens and thickens with rage, embers rising off the body, a heartbeat at full rage. */
    private void rageAura(long tick, double fraction) {
        Player owner = player();
        Location feet = owner.getLocation().add(0, 0.08, 0);
        double radius = 0.45 + 0.35 * fraction;
        int points = 6 + (int) Math.round(10 * fraction);
        Vfx.ring(ctx(), feet, radius, points, tick * 0.21, Particle.DUST, PALETTE.dust((float) (0.6 + 0.6 * fraction)),
                false);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int embers = Vfx.n(ctx(), fraction >= 0.5 ? 2 : 1);
        for (int i = 0; i < embers; i++) {
            Location from = Vfx.scatter(feet, radius);
            Location to = from.clone().add(0, owner.getHeight() * random.nextDouble(0.6, 1.1), 0);
            Vfx.trail(from, to, i % 2 == 0 ? PALETTE.core() : PALETTE.body(), random.nextInt(10, 16), false);
        }
        if (fraction >= 0.999 && tick % RAGE_BEAT_PERIOD == 0) {
            Vfx.ring(ctx(), feet, radius + 0.35, points + 4, -tick * 0.21, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.fade(1.1f), false);
            owner.playSound(owner, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 0.35f,
                    1.3f + Fx.jitter(0.05f));
        }
    }

    /** A raging melee hit: a crimson crescent through the victim, stronger and lower-pitched with more rage. */
    private void rageHit(LivingEntity victim, double fraction) {
        Location body = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        Vector facing = victim.getLocation().toVector().subtract(player().getLocation().toVector());
        double tilt = ThreadLocalRandom.current().nextDouble(-40, 40);
        slash(body, facing, 0.75, tilt, 7 + (int) Math.round(fraction * 5), (float) (0.9 + 0.5 * fraction));
        body.getWorld().spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 4 + (int) Math.round(fraction * 6)), 0.2, 0.3,
                0.2, 0.2);
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6f, 1.15f - (float) fraction * 0.4f);
        if (fraction >= 0.999) {
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.5f, 0.8f + Fx.jitter(0.08f));
        }
    }

    // ------------------------------------------------------------------ active: charge

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 돌진할 수 없습니다");
        }
        if (charging) {
            return ActivationResult.fail("이미 돌진 중입니다");
        }
        Vector direction = Motion.horizontalFacing(owner.getLocation());
        Location start = owner.getLocation();
        double length = Math.max(1, params().num("charge_distance"));
        // The charge never carries the owner past the arena border.
        double distance = Spots.arenaReach(ctx(), start, direction, length);
        if (distance < 1) {
            return ActivationResult.fail("자기장 밖으로는 돌진할 수 없습니다");
        }
        if (!Spots.hasGroundBelow(start.getWorld(), start.clone().add(direction.clone().multiply(VOID_LOOKAHEAD)))) {
            return ActivationResult.fail("앞이 낭떠러지입니다");
        }
        // The server sees the owner move only a round trip after the velocity is sent: wait that long for the
        // first movement before calling the charge stalled. The client runs the steps the same whatever the ping, so
        // the time the charge gets does not depend on it.
        long lag = lagTicks(owner);
        long budget = (long) Math.ceil(distance / CHARGE_SPEED) + CHARGE_SLACK_TICKS;
        charging = true;
        chargeStart(start);
        owner.setFallDistance(0);
        // Cut short by the border: the charge ends dead still there, without a glide.
        boolean bordered = distance < length - 1.0E-6;
        Charge charge = new Charge(owner, start, direction, distance, bordered, lag, budget);
        // One run more than steps: the last one only bowls at the end and stops.
        Loop.repeat(ctx(), owner, 0, 1, budget + 1, charge, charge::end);
        return ActivationResult.success();
    }

    /**
     * One charge. The berserker's client runs every velocity step sent to it at once, while the server hears where it
     * is only a round trip later. So everything the charge meets is met where the client really is, reckoned from the
     * start along the steps sent the way the client runs them ({@link Reckoning}: it slides along a wall, steps up
     * stairs, crawls through a cobweb). The charge is measured on that run (the server's view would let it run on past
     * its end), enemies are bowled as the berserker reaches them (not a round trip later, after its client ran through
     * them) and only with no wall between, it pulls up at the edge of the void looking ahead of where the next step
     * carries it, and it ends where the berserker stops, not where the server last heard of it.
     */
    private final class Charge implements LongPredicate {

        private final Location start;
        private final Vector direction;
        private final double distance;
        /** The border cut the charge short: it ends dead still there, without a glide. */
        private final boolean bordered;
        private final long lag;
        /** Runs that may send a step; the run after them ends the charge wherever it got to. */
        private final long budget;
        private final Set<UUID> struck = new HashSet<>();
        /**
         * Where the berserker's client is: it has already run every step sent. The first step goes out with the
         * first run: a velocity set at the cast would only be replaced by it before the server sends it on.
         */
        private final Reckoning client;
        private Location last;
        private int stalls;
        private boolean moved;
        /** The charge pulled up at the edge of the void or a lava pit: it ends dead still there, without a glide. */
        private boolean edge;
        /** The charge ran into a wall: it ends with a bump, without a glide. */
        private boolean wall;
        /** Something else moved the berserker far off its path (a knockback, a teleport): it ends there, still. */
        private boolean lost;
        /** Where the berserker stops, once the charge is over. */
        private @Nullable Location stop;
        private boolean ended;

        Charge(Player owner, Location start, Vector direction, double distance, boolean bordered, long lag,
               long budget) {
            this.start = start;
            this.direction = direction;
            this.distance = distance;
            this.bordered = bordered;
            this.lag = lag;
            this.budget = budget;
            this.client = new Reckoning(owner, start);
            this.last = start;
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            Location now = owner.getLocation();
            if (!now.getWorld().equals(start.getWorld())) {
                return false;
            }
            if (client.lost(now)) {
                lost = true;
                return stopAt(now);
            }
            if (!moved && horizontalDistance(start, now) >= STALL_EPSILON) {
                moved = true;
            }
            // Its client has already run every step sent: the berserker bowls what it meets there, never through a
            // wall (the hit reaches past the body, and a wall it stops at is right in front of it).
            Location reached = client.at();
            Location body = reached.clone().add(0, owner.getHeight() * 0.5, 0).add(direction.clone().multiply(0.4));
            for (LivingEntity enemy : ctx().enemiesNear(owner, body, CHARGE_HIT_RADIUS)) {
                if (!struck.contains(enemy.getUniqueId()) && inSight(owner, reached, enemy)) {
                    struck.add(enemy.getUniqueId());
                    bowl(enemy, reached, direction);
                }
            }
            if (client.travelled() >= distance - 0.2 || tick >= budget) {
                return stopAt(reached);
            }
            if (moved || tick >= CHARGE_GRACE_TICKS + lag) {
                stalls = horizontalDistance(last, now) < STALL_EPSILON ? stalls + 1 : 0;
                if (stalls >= STALL_LIMIT) {
                    // Stopped by something the reckoning cannot see: the server has seen it stand still there.
                    wall = true;
                    return stopAt(now);
                }
            }
            last = now;
            // The last step is only as long as what is left, so the charge ends exactly at its end.
            double step = Math.min(CHARGE_SPEED, distance - client.travelled());
            Vector velocity = direction.clone().multiply(step).setY(CHARGE_SINK);
            Vector move = client.next(velocity);
            double progress = Math.hypot(move.getX(), move.getZ());
            if (progress < STALL_EPSILON) {
                // Flush against a wall (or anything too high to step up onto): the client would not get anywhere.
                wall = true;
                return stopAt(reached);
            }
            // Never into the void (or a lava pit): the charge pulls up at the edge, looking ahead along where this
            // step carries the berserker (the server sees it behind that).
            Location ahead = reached.clone().add(move.clone().setY(0).multiply(VOID_LOOKAHEAD / progress));
            if (!Spots.hasGroundBelow(start.getWorld(), ahead)) {
                edge = true;
                return stopAt(reached);
            }
            owner.setVelocity(velocity);
            owner.setFallDistance(0);
            client.advance(move);
            chargeTrail(now, direction);
            return true;
        }

        /** Ends the charge with the berserker standing {@code at}. */
        private boolean stopAt(Location at) {
            stop = at.clone();
            return false;
        }

        void end() {
            if (ended) {
                return;
            }
            ended = true;
            charging = false;
            Player owner = player();
            Location now = owner.getLocation();
            Location at = stop;
            if (at == null) {
                // Carried off to another world (or a failing run).
                at = now.getWorld().equals(start.getWorld()) ? client.at() : now;
            }
            owner.setVelocity(direction.clone().multiply(bordered || edge || wall || lost ? 0 : CHARGE_GLIDE)
                    .setY(CHARGE_SINK));
            if (wall) {
                wallBump(at, direction);
            }
            chargeEnd(at);
            if (!struck.isEmpty()) {
                owner.sendActionBar(Text.mm("<#E0303A>돌진!</#E0303A> <gray>" + struck.size() + "명 적중</gray>"));
            }
        }
    }

    /** True if no block stands between the berserker at {@code feet} and {@code enemy} (chest to chest, eye to eye). */
    private static boolean inSight(Player owner, Location feet, LivingEntity enemy) {
        return Spots.clearPath(feet.clone().add(0, owner.getHeight() * 0.5, 0), Fx.body(enemy))
                || Spots.clearPath(feet.clone().add(0, owner.getEyeHeight(), 0), enemy.getEyeLocation());
    }

    /**
     * Where a player's client is while it follows the velocities sent to it, one every tick. The server hears where
     * the client is only a round trip late, but the client moves by each velocity as soon as it arrives (with a new
     * one every tick nothing else moves it), so it is reckoned from where it set off, moving the way the client
     * moves: y first, then the longer horizontal axis first, each stopped flush against a block (sliding along it on
     * the other axis), stepping up onto anything up to {@link #STEP_HEIGHT} high while on the ground, held back on
     * ladders and vines, and slowed down on the move after it went through a cobweb, a berry bush or powder snow.
     */
    private static final class Reckoning {
        /** Samples of a sweep lie closer together than the body is wide, so no block between two is missed. */
        private static final double SWEEP_SAMPLE = 0.5;
        private static final int SWEEP_ITERATIONS = 12;
        /** A client on a ladder or a vine moves at most this fast along each horizontal axis. */
        private static final double CLIMB_SPEED = 0.15;
        /** The blocks a body touches are looked up this far inside its box. */
        private static final double TOUCH_INSET = 0.01;

        private final World world;
        private final double width;
        private final double height;
        private final Location at;
        /** Every place the client was reckoned to be, to compare the lagging server position with. */
        private final List<Vector> trail = new ArrayList<>();
        /** How much a block the last move went through slows the next move down (x, y and z), if any. */
        private @Nullable Vector stuck;
        private double travelled;

        Reckoning(LivingEntity body, Location from) {
            this.world = from.getWorld();
            this.width = body.getWidth();
            this.height = body.getHeight();
            this.at = from.clone();
            trail.add(at.toVector());
        }

        /** Where the client is. */
        Location at() {
            return at.clone();
        }

        /** How far the client has run, along the ground. */
        double travelled() {
            return travelled;
        }

        /** The move the client makes with {@code velocity} from where it is; {@link #advance} makes it. */
        Vector next(Vector velocity) {
            Vector v = velocity.clone();
            if (climbable()) {
                v.setX(Math.clamp(v.getX(), -CLIMB_SPEED, CLIMB_SPEED)).setY(Math.max(v.getY(), -CLIMB_SPEED))
                        .setZ(Math.clamp(v.getZ(), -CLIMB_SPEED, CLIMB_SPEED));
            }
            Vector slowdown = stuck;
            if (slowdown != null) {
                v.multiply(slowdown);
            }
            if (!fits(at)) {
                return v; // already inside a block (a shape the box check misreads): only the velocity is known
            }
            Vector moved = collide(at, v.getX(), v.getY(), v.getZ());
            boolean blocked = moved.getX() != v.getX() || moved.getZ() != v.getZ();
            if (blocked && v.getY() < 0 && moved.getY() != v.getY()) {
                // On the ground against a block: like the client, up by as much as a step, across and back down.
                Vector climbed = collide(at, v.getX(), STEP_HEIGHT, v.getZ());
                climbed.setY(climbed.getY() + sweep(at.clone().add(climbed), 1, v.getY() - climbed.getY()));
                if (horizontalSquared(climbed) > horizontalSquared(moved)) {
                    moved = climbed;
                }
            }
            return moved;
        }

        /** Makes a move found by {@link #next}. */
        void advance(Vector move) {
            Location from = at.clone();
            at.add(move);
            stuck = slowdown(from, at);
            travelled += Math.hypot(move.getX(), move.getZ());
            trail.add(at.toVector());
        }

        /** True if the server sees the body farther than {@link #STRAY_DISTANCE} from every place it was reckoned. */
        boolean lost(Location seen) {
            if (!world.equals(seen.getWorld())) {
                return true;
            }
            Vector place = seen.toVector();
            for (Vector reckoned : trail) {
                if (reckoned.distanceSquared(place) <= STRAY_DISTANCE * STRAY_DISTANCE) {
                    return false;
                }
            }
            return true;
        }

        /** The body's move from {@code from} by (x, y, z): y first, then the longer horizontal axis, like vanilla. */
        private Vector collide(Location from, double x, double y, double z) {
            Location to = from.clone();
            double dy = sweep(to, 1, y);
            to.setY(to.getY() + dy);
            double dx;
            double dz;
            if (Math.abs(x) < Math.abs(z)) {
                dz = sweep(to, 2, z);
                to.setZ(to.getZ() + dz);
                dx = sweep(to, 0, x);
            } else {
                dx = sweep(to, 0, x);
                to.setX(to.getX() + dx);
                dz = sweep(to, 2, z);
            }
            return new Vector(dx, dy, dz);
        }

        /** How far the body gets from {@code from} along one axis (0 x, 1 y, 2 z) towards {@code delta}. */
        private double sweep(Location from, int axis, double delta) {
            double length = Math.abs(delta);
            if (length < 1.0E-9) {
                return delta;
            }
            int samples = (int) Math.ceil(length / SWEEP_SAMPLE);
            double free = 0;
            for (int i = 1; i <= samples; i++) {
                double reach = length * i / samples;
                if (fits(shifted(from, axis, Math.copySign(reach, delta)))) {
                    free = reach;
                    continue;
                }
                double blocked = reach;
                for (int k = 0; k < SWEEP_ITERATIONS; k++) {
                    double mid = (free + blocked) / 2;
                    if (fits(shifted(from, axis, Math.copySign(mid, delta)))) {
                        free = mid;
                    } else {
                        blocked = mid;
                    }
                }
                return Math.copySign(free, delta);
            }
            return delta;
        }

        private static Location shifted(Location from, int axis, double by) {
            return from.clone().add(axis == 0 ? by : 0, axis == 1 ? by : 0, axis == 2 ? by : 0);
        }

        private boolean fits(Location feet) {
            return Spots.isFree(world, feet, width, height);
        }

        /** True if the client's feet are in a ladder, a vine or anything else it climbs. */
        private boolean climbable() {
            int y = (int) Math.floor(at.getY() + TOUCH_INSET);
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                return false;
            }
            return tagged(Tag.CLIMBABLE, world.getBlockAt(at.getBlockX(), y, at.getBlockZ()).getType());
        }

        /** The strongest slowdown of the blocks the body went through moving from {@code from} to {@code to}. */
        private @Nullable Vector slowdown(Location from, Location to) {
            double half = width / 2 - TOUCH_INSET;
            int minX = (int) Math.floor(Math.min(from.getX(), to.getX()) - half);
            int maxX = (int) Math.floor(Math.max(from.getX(), to.getX()) + half);
            int minY = Math.max(world.getMinHeight(), (int) Math.floor(Math.min(from.getY(), to.getY()) + TOUCH_INSET));
            int maxY = Math.min(world.getMaxHeight() - 1,
                    (int) Math.floor(Math.max(from.getY(), to.getY()) + height - TOUCH_INSET));
            int minZ = (int) Math.floor(Math.min(from.getZ(), to.getZ()) - half);
            int maxZ = (int) Math.floor(Math.max(from.getZ(), to.getZ()) + half);
            Vector slowest = null;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Vector factor = slowdownOf(world.getBlockAt(x, y, z).getType());
                        if (factor != null && (slowest == null || factor.getX() < slowest.getX())) {
                            slowest = factor;
                        }
                    }
                }
            }
            return slowest;
        }

        /** How much the client's move is scaled after it went through a block of {@code type} (vanilla). */
        private static @Nullable Vector slowdownOf(Material type) {
            return switch (type) {
                case COBWEB -> new Vector(0.25, 0.05, 0.25);
                case SWEET_BERRY_BUSH -> new Vector(0.8, 0.75, 0.8);
                case POWDER_SNOW -> new Vector(0.9, 1.5, 0.9);
                default -> null;
            };
        }

        private static boolean tagged(@Nullable Tag<Material> tag, Material type) {
            return tag != null && tag.isTagged(type);
        }

        private static double horizontalSquared(Vector move) {
            return move.getX() * move.getX() + move.getZ() * move.getZ();
        }
    }

    /** Ticks the server sees the owner's movement late: one round trip, capped (ping can read 0 after joining). */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / MILLIS_PER_TICK));
    }

    /** Hits an enemy in the charge's path and knocks it forward and to whichever side it stood on. */
    private void bowl(LivingEntity enemy, Location chargerAt, Vector direction) {
        double damage = Math.max(0, params().num("charge_damage")) * rageMultiplier();
        chargeImpact = true;
        try {
            Combat.hit(ctx(), player(), enemy, damage);
        } finally {
            chargeImpact = false;
        }
        if (!Combat.isTargetable(ctx(), player(), enemy)) {
            return;
        }
        Vector offset = enemy.getLocation().toVector().subtract(chargerAt.toVector()).setY(0);
        double cross = direction.getX() * offset.getZ() - direction.getZ() * offset.getX();
        Vector side = new Vector(-direction.getZ(), 0, direction.getX());
        if (cross < 0) {
            side.multiply(-1);
        }
        Vector knock = direction.clone().multiply(BOWL_FORWARD).add(side.multiply(BOWL_SIDE)).setY(BOWL_UP);
        Motion.push(enemy, knock, MAX_VELOCITY);
        bowlImpact(enemy, direction);
    }

    /** The charge sets off: a roar, a crescent of red dust and torn-up ground behind the berserker. */
    private void chargeStart(Location start) {
        World world = start.getWorld();
        Fx.sound(start, Sound.ENTITY_HOGLIN_ANGRY, 1.0f, 0.9f + Fx.jitter(0.1f));
        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(start, Sound.ENTITY_BREEZE_JUMP, 0.6f, 0.7f + Fx.jitter(0.05f));
        Location feet = start.clone().add(0, 0.1, 0);
        Vfx.arc(ctx(), feet, 0.7, start.getYaw() + 180, 150, 12, Particle.DUST, PALETTE.dust(1.3f), false);
        Vfx.arc(ctx(), feet, 1.0, start.getYaw() + 180, 120, 9, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.0f),
                false);
        BlockData ground = groundUnder(start);
        if (ground != null) {
            world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 12), 0.35, 0.05, 0.35, 0.15, ground);
        }
        world.spawnParticle(Particle.POOF, feet, Vfx.n(ctx(), 5), 0.25, 0.05, 0.25, 0.04);
    }

    /** One tick of the charge: red speed streaks flying back past the body, a dust smear and kicked-up ground. */
    private void chargeTrail(Location at, Vector direction) {
        World world = at.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double height = player().getHeight();
        Vector side = new Vector(-direction.getZ(), 0, direction.getX());
        int streaks = Vfx.n(ctx(), 3);
        for (int i = 0; i < streaks; i++) {
            Location from = at.clone().add(side.clone().multiply(random.nextDouble(-0.45, 0.45)))
                    .add(0, random.nextDouble(0.15, height * 0.95), 0).add(direction.clone().multiply(0.4));
            Location to = from.clone().subtract(direction.clone().multiply(random.nextDouble(1.4, 2.4)));
            Vfx.trail(from, to, i == 0 ? PALETTE.core() : PALETTE.body(), STREAK_TICKS, false);
        }
        world.spawnParticle(Particle.DUST, at.clone().add(0, 0.35, 0), Vfx.n(ctx(), 2), 0.2, 0.2, 0.2, 0,
                PALETTE.dust(1.5f));
        BlockData ground = groundUnder(at);
        if (ground != null) {
            world.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.1, 0), Vfx.n(ctx(), 3), 0.25, 0.05, 0.25, 0.1,
                    ground);
        }
    }

    /** A struck enemy: a sweep, a red burst, a crescent and a ghostly axe cleaving through it. */
    private void bowlImpact(LivingEntity enemy, Vector direction) {
        Location body = Fx.body(enemy);
        World world = body.getWorld();
        world.spawnParticle(Particle.SWEEP_ATTACK, body, 1, 0, 0, 0, 0);
        Vfx.burst(ctx(), body, PALETTE, 0.55, Particle.CRIT, false);
        slash(body, direction, 0.95, ThreadLocalRandom.current().nextDouble(-35, 35), 12, 1.4f);
        ghostAxe(body);
        Fx.sound(body, Sound.ENTITY_GOAT_RAM_IMPACT, 1.0f, 0.85f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.7f, 0.9f + Fx.jitter(0.1f));
    }

    /** A big axe facing every viewer that swings once through {@code at}, then shrinks away. */
    private void ghostAxe(Location at) {
        Player owner = player();
        ItemDisplay axe = Displays.item(ctx(), owner, at, new ItemStack(Material.IRON_AXE),
                Displays.centered(0, 0, 0, AXE_SCALE * 0.75f, AXE_SCALE * 0.75f, AXE_SCALE * 0.75f, 0, 0,
                        AXE_FROM_ROLL),
                AXE_SWING_TICKS + AXE_FADE_TICKS + 4);
        axe.setBillboard(Display.Billboard.CENTER);
        Displays.animateNextTick(ctx(), owner, axe,
                Displays.centered(0, 0, 0, AXE_SCALE, AXE_SCALE, AXE_SCALE, 0, 0, AXE_TO_ROLL), AXE_SWING_TICKS);
        ctx().runLater(owner, AXE_SWING_TICKS + 2, () -> {
            if (axe.isValid()) {
                Displays.shrinkAway(ctx(), owner, axe, AXE_FADE_TICKS);
            }
        });
    }

    /** The charge hit a wall: chips of the wall, sparks and a dull thud. */
    private void wallBump(Location at, Vector direction) {
        Location front = at.clone().add(direction.clone().multiply(0.6)).add(0, 1.0, 0);
        BlockData wall = front.getBlock().getBlockData();
        World world = at.getWorld();
        if (!wall.getMaterial().isAir()) {
            world.spawnParticle(Particle.BLOCK, front, Vfx.n(ctx(), 18), 0.3, 0.4, 0.3, 0.15, wall);
        }
        world.spawnParticle(Particle.CRIT, front, Vfx.n(ctx(), 8), 0.2, 0.3, 0.2, 0.2);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, front, Vfx.n(ctx(), 8), 0.25, 0.35, 0.25, 0,
                PALETTE.fade(1.2f));
        Fx.sound(front, Sound.ITEM_MACE_SMASH_GROUND, 0.7f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(front, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.5f, 0.6f + Fx.jitter(0.05f));
    }

    /** Where the charge ended: the berserker skids to a halt in a dark ring of settling dust. */
    private void chargeEnd(Location at) {
        Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), 0.6, 10, ThreadLocalRandom.current().nextDouble(TAU),
                Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.9f), false);
        Fx.sound(at, Sound.ENTITY_HOGLIN_STEP, 0.8f, 0.8f + Fx.jitter(0.08f));
    }

    // ------------------------------------------------------------------ ultimate: frenzy

    private boolean isFrenzied() {
        return frenzyUntil >= 0 && ctx().currentTick() < frenzyUntil;
    }

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        long duration = Math.max(1, params().ticks("ult_duration"));
        int effectTicks = (int) Math.min(Integer.MAX_VALUE, duration);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, effectTicks, FRENZY_AMPLIFIER, false, true,
                true));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, effectTicks, FRENZY_AMPLIFIER, false, true,
                true));
        frenzyUntil = ctx().currentTick() + duration;
        frenzyCast(owner);

        long visualRuns = Math.max(1, duration / FRENZY_VISUAL_PERIOD);
        Loop.repeat(ctx(), player(), FRENZY_VISUAL_PERIOD, FRENZY_VISUAL_PERIOD, visualRuns, tick -> {
            if (!isFrenzied()) {
                return false;
            }
            frenzyAura(owner, tick);
            return true;
        }, () -> {
            if (frenzyUntil >= 0 && ctx().currentTick() + FRENZY_VISUAL_PERIOD >= frenzyUntil) {
                Location at = owner.getLocation();
                Fx.sound(at, Sound.ENTITY_PLAYER_BREATH, 1.0f, 0.7f);
                Vfx.ring(ctx(), at.clone().add(0, 0.15, 0), 0.9, 14, 0, Particle.SMOKE, null, false);
                at.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, at.clone().add(0, owner.getHeight() * 0.5, 0),
                        Vfx.n(ctx(), 10), 0.35, 0.5, 0.35, 0, PALETTE.fade(1.0f));
                owner.sendActionBar(Text.mm("<gray>광폭화가 끝났습니다</gray>"));
            }
        });
        return ActivationResult.success();
    }

    /** The frenzy erupts: a roar, a flash, a blood shockwave, a rising crimson column and flame spikes. */
    private void frenzyCast(Player owner) {
        Location at = owner.getLocation();
        World world = at.getWorld();
        Location chest = at.clone().add(0, owner.getHeight() * 0.55, 0);
        Fx.sound(at, Sound.ENTITY_RAVAGER_ROAR, 1.3f, 1.1f + Fx.jitter(0.05f));
        Fx.sound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 1.5f, 1.4f);
        Fx.sound(at, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.5f + Fx.jitter(0.05f));
        world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, PALETTE.core(), true);
        Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.15, 0), 0.6, FRENZY_WAVE_RADIUS, FRENZY_WAVE_TICKS,
                Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.6f), true);
        Vfx.helix(ctx(), at, 0.75, 3.2, 1.75, 3, 16, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.3f), true);
        Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), 1.0, 8, 0, Particle.DUST_PILLAR,
                Material.REDSTONE_BLOCK.createBlockData(), true);
        flameSpikes(at);
    }

    /** Red glass flame tongues bursting from the ground around the berserker, with thin blood spikes inside. */
    private void flameSpikes(Location at) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BlockData glass = Material.RED_STAINED_GLASS.createBlockData();
        BlockData blood = Material.REDSTONE_BLOCK.createBlockData();
        for (int i = 0; i < FLAME_SPIKES; i++) {
            double angle = i * TAU / FLAME_SPIKES + random.nextDouble(-0.2, 0.2);
            spike(at, angle, 0.9, 0.16f, (float) random.nextDouble(1.4, 2.4), (float) random.nextDouble(12, 22), glass);
        }
        for (int i = 0; i < BLOOD_SPIKES; i++) {
            double angle = (i + 0.5) * TAU / BLOOD_SPIKES + random.nextDouble(-0.3, 0.3);
            spike(at, angle, 0.55, 0.08f, (float) random.nextDouble(2.4, 3.0), (float) random.nextDouble(6, 12), blood);
        }
    }

    /**
     * One spike {@code distance} blocks from {@code center} in direction {@code angle}, leaning outwards by
     * {@code lean} degrees: it shoots up out of the ground, holds, then sinks back.
     */
    private void spike(Location center, double angle, double distance, float width, float height, float lean,
                       BlockData data) {
        Player owner = player();
        Location base = center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
        base.setYaw(0);
        base.setPitch(0);
        float yaw = (float) Math.toDegrees(angle) - 90;
        Transformation buried = spikePose(angle, width, 0.02f, lean, yaw);
        BlockDisplay spike = Displays.block(ctx(), owner, base, data, buried,
                SPIKE_HOLD_TICKS + SPIKE_SINK_TICKS + 2);
        Displays.animateNextTick(ctx(), owner, spike, spikePose(angle, width, height, lean, yaw), SPIKE_RISE_TICKS);
        ctx().runLater(owner, SPIKE_HOLD_TICKS, () -> Displays.animate(spike, buried, SPIKE_SINK_TICKS));
    }

    /** A {@code width} x {@code height} column standing on the entity location, leaning outwards. */
    private static Transformation spikePose(double angle, float width, float height, float lean, float yaw) {
        double tilt = Math.toRadians(lean);
        float half = height / 2;
        float out = (float) (Math.sin(tilt) * half);
        return Displays.transform((float) Math.cos(angle) * out, (float) (Math.cos(tilt) * half),
                (float) Math.sin(angle) * out, width, height, width, yaw, lean, 0);
    }

    /** The frenzy burning: two flame tongues spiralling up the body, embers and a ring at the feet. */
    private void frenzyAura(Player owner, long tick) {
        Location now = owner.getLocation();
        World world = now.getWorld();
        double height = owner.getHeight();
        int flames = Vfx.n(ctx(), 3);
        for (int strand = 0; strand < 2; strand++) {
            for (int k = 0; k < flames; k++) {
                double rise = (tick * 0.16 + (double) k / flames + strand * 0.5) % 1.0;
                double angle = tick * 0.5 + strand * Math.PI + rise * 2.4;
                double radius = 0.75 * (1 - 0.5 * rise);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, now.getX() + Math.cos(angle) * radius,
                        now.getY() + rise * height * 1.15, now.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0,
                        new Particle.DustTransition(PALETTE.core(), PALETTE.edge(), (float) (1.4 - 0.7 * rise)));
            }
        }
        if (tick % 2 == 0) {
            Location from = Vfx.scatter(now, 0.7).add(0, 0.1, 0);
            Vfx.trail(from, from.clone().add(0, height * 1.3, 0), PALETTE.core(), 12, false);
        }
        if (tick % 3 == 0) {
            Vfx.ring(ctx(), now.clone().add(0, 0.08, 0), 0.85, 12, -tick * 0.3, Particle.DUST, EDGE_DUST, false);
        }
    }

    /**
     * Heals a share of the damage once the event has fully resolved, so hits cancelled or reduced by the victim's
     * own ability (or anything else) only heal what actually landed, absorption hearts included.
     */
    private void scheduleLifesteal(EntityDamageByEntityEvent event, LivingEntity victim) {
        double share = Math.max(0, params().num("ult_lifesteal")) / 100.0;
        if (share <= 0) {
            return;
        }
        double before = victim.getHealth() + victim.getAbsorptionAmount();
        ctx().runLater(player(), 1, () -> {
            if (event.isCancelled() || !ctx().isAlive(player())) {
                return;
            }
            double amount = Math.min(damageWithAbsorption(event), before) * share;
            if (amount < 0.05) {
                return;
            }
            ctx().heal(player(), amount);
            lifestealVisual(victim, amount);
        });
    }

    /**
     * What the hit took off health and absorption hearts together. {@link EntityDamageEvent#getFinalDamage()}
     * leaves out the part absorption soaked up (the negative {@code ABSORPTION} modifier).
     */
    @SuppressWarnings("deprecation") // DamageModifier is the only exact source of the absorbed amount; no replacement
    private static double damageWithAbsorption(EntityDamageEvent event) {
        return Math.max(0, event.getFinalDamage() - event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION));
    }

    /** Blood motes flowing from the victim into the berserker's chest, and a small flare when they arrive. */
    private void lifestealVisual(LivingEntity victim, double amount) {
        Player owner = player();
        Location to = chest(owner);
        Location from = victim.isValid() ? chest(victim)
                : to.clone().add(Motion.horizontalFacing(owner.getLocation()).multiply(1.5));
        if (!from.getWorld().equals(to.getWorld())) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int duration = (int) Math.max(4, Math.min(16, from.distance(to) * 3));
        int motes = Vfx.n(ctx(), 2 + (int) Math.min(5, Math.round(amount * 2)));
        for (int i = 0; i < motes; i++) {
            Location start = from.clone().add(random.nextDouble(-0.2, 0.2), random.nextDouble(-0.25, 0.25),
                    random.nextDouble(-0.2, 0.2));
            Vfx.trail(start, to, i % 2 == 0 ? PALETTE.body() : PALETTE.core(), duration + i % 3, false);
        }
        ctx().runLater(owner, duration, () -> Vfx.ring(ctx(), chest(owner), 0.4, 6, random.nextDouble(TAU),
                Particle.DUST, CORE_DUST, false));
    }

    // ------------------------------------------------------------------ visual helpers

    /**
     * A diagonal crescent through {@code center}, swung along {@code facing}: three thin arcs (bright core, glowing
     * body, dark edge) on a circle whose pivot lies behind the center, tilted {@code tiltDegrees} from horizontal.
     */
    private void slash(Location center, Vector facing, double radius, double tiltDegrees, int points, float size) {
        Vector forward = Motion.horizontal(facing);
        if (forward.lengthSquared() < 1.0E-6) {
            forward = Motion.horizontalFacing(player().getLocation());
        }
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        double tilt = Math.toRadians(tiltDegrees);
        Vector sweep = right.multiply(Math.cos(tilt)).add(new Vector(0, Math.sin(tilt), 0));
        Location pivot = center.clone().subtract(forward.clone().multiply(radius));
        int count = Math.max(2, Vfx.n(ctx(), points));
        double half = Math.toRadians(SLASH_WIDTH_DEGREES / 2);
        Object[] layers = {new Particle.DustOptions(PALETTE.core(), size * 0.7f), PALETTE.glow(size),
                PALETTE.fade(size * 0.8f)};
        for (int layer = 0; layer < layers.length; layer++) {
            double r = radius * (0.88 + layer * 0.12);
            Particle particle = layer == 0 ? Particle.DUST : Particle.DUST_COLOR_TRANSITION;
            for (int i = 0; i < count; i++) {
                double a = -half + 2 * half * i / (count - 1);
                Location at = pivot.clone().add(forward.clone().multiply(Math.cos(a) * r))
                        .add(sweep.clone().multiply(Math.sin(a) * r));
                Vfx.dot(at, particle, layers[layer], false);
            }
        }
    }

    private static @Nullable BlockData groundUnder(Location at) {
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        return ground.getMaterial().isSolid() ? ground : null;
    }

    private static Location chest(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.55, 0);
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }
}
