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
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 창술사 — a spear fighter. Sprinting hits strike harder, the active throws a spear that pierces everything on its way
 * out and strikes again (dragging enemies along) on its way back to the hand, and the ultimate is a long straight
 * charge that skewers every enemy in its path, carries them on the spear point and hurls them away at the end.
 *
 * <p>Look: sea green, silver and white. The spear is built from display blocks (a silver shaft, a prismarine head,
 * a white point) and spins as it flies, trailing sea-green light and spray. The charge is read before it starts: a
 * glowing lane on the ground, the spear drawn back; then the lancer becomes a riptide of light with the giant spear
 * in front, and the end is a burst of spray and a shockwave.
 */
public final class LancerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("lancer", "창술사", Material.TRIDENT)
            .color(TextColor.color(0x4FD1A5))
            .difficulty(2)
            .passive("돌격 자세", "달리면서 근접 공격하면 피해가 {sprint_bonus}% 증가합니다.")
            .active("창 투척",
                    "창을 던져 {throw_range}블록까지 꿰뚫으며 날립니다. 맞은 적은 {throw_damage} 피해를 입고, 손으로 "
                            + "되돌아오는 창에 다시 맞으면 {return_damage} 피해를 입고 창술사 쪽으로 끌려옵니다.",
                    8)
            .ultimate("일섬 돌격",
                    "잠시 창을 겨눈 뒤 {ult_distance}블록을 일직선으로 돌격합니다. 길목의 적은 {ult_damage} 피해를 입고 "
                            + "창끝에 꿰여 끌려가다가, 돌격이 끝나면 {ult_fling_damage} 피해와 함께 내동댕이쳐집니다.")
            .param("sprint_bonus", 30)
            .param("throw_range", 16)
            .param("throw_damage", 4)
            .param("return_damage", 3)
            .param("ult_distance", 14)
            .param("ult_damage", 4)
            .param("ult_fling_damage", 4)
            .build();

    private static final String COLOR_TAG = "#4FD1A5";
    private static final double TAU = Math.PI * 2;

    // Spear throw
    private static final double THROW_SPEED = 1.5;
    private static final double RETURN_SPEED = 1.7;
    /** How far from the spear's point (to the hitbox) an enemy is struck. */
    private static final double SPEAR_REACH = 0.55;
    private static final double SAMPLE_STEP = 0.4;
    /** Ticks the spear trembles at the end of its flight (or stuck in a wall) before it flies back. */
    private static final long TURN_TICKS = 4;
    private static final long MAX_RETURN_TICKS = 40;
    /** The spear is caught once its point comes this close to the lancer's chest. */
    private static final double CATCH_DISTANCE = 1.0;
    private static final double RETURN_PULL = 0.65;
    private static final double RETURN_LIFT = 0.25;
    private static final float SPIN_PER_TICK = 28f;
    /** How deep the spear point sinks into a wall it hits. */
    private static final double WALL_BITE = 0.3;
    /** How far the turning spear trembles from its spot. */
    private static final double TREMBLE = 0.04;

    // Piercing charge
    private static final long WINDUP_TICKS = 7;
    private static final double CHARGE_SPEED = 1.25;
    private static final double CHARGE_SINK = -0.08;
    /** Ticks before stall detection starts (the client needs a moment to pick up the new velocity). */
    private static final long CHARGE_GRACE_TICKS = 3;
    private static final double STALL_EPSILON = 0.12;
    private static final int STALL_LIMIT = 3;
    private static final long MAX_LAG_TICKS = 8;
    private static final double MILLIS_PER_TICK = 50.0;
    /** Where the spear point sweeps for new victims, ahead of the lancer's chest. */
    private static final double SKEWER_AHEAD = 1.4;
    private static final double SKEWER_REACH = 1.2;
    /** Skewered enemies ride this far ahead of the lancer, a little further for each one already on the spear. */
    private static final double CARRY_AHEAD = 1.9;
    private static final double CARRY_SPACING = 0.55;
    private static final double CARRY_PULL = 0.5;
    /** A skewered enemy left this far behind its spot on the spear (stopped by a wall) slips off. */
    private static final double CARRY_LEASH = 3.5;
    private static final double FLING_FORWARD = 1.4;
    private static final double FLING_UP = 0.6;
    private static final double MAX_VELOCITY = 2.5;
    private static final long LANDING_GRACE_TICKS = 100;
    private static final float CHARGE_SPEAR_SCALE = 1.6f;
    /** How far ahead the charge looks for ground; it stops at the edge of the void or a lava pit. */
    private static final double VOID_LOOKAHEAD = CHARGE_SPEED + 0.6;

    // Visuals
    /** White core, sea green body, deep sea edge; silver for the shaft and streaks. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xEFFFFA, 0x4FD1A5, 0x1E6E5C);
    private static final Color SILVER = Color.fromRGB(0xC9D3DC);
    private static final Particle.DustOptions SILVER_DUST = new Particle.DustOptions(SILVER, 0.8f);
    private static final long STANCE_PERIOD = 4;

    /** Spear parts in the spear's frame (+z is the point). */
    private static final Part SHAFT = new Part(Material.LIGHT_GRAY_CONCRETE, 0.07f, 0.07f, 2.0f, -0.55f, 0);
    private static final Part HEAD = new Part(Material.PRISMARINE_BRICKS, 0.2f, 0.2f, 0.5f, 0.68f, 45);
    private static final Part TIP = new Part(Material.WHITE_CONCRETE, 0.08f, 0.08f, 0.26f, 1.0f, 45);
    private static final Part[] SPEAR_PARTS = {SHAFT, HEAD, TIP};
    /** Distance from the spear's center to its point. */
    private static final double POINT = 1.13;

    private record Part(Material material, float width, float thickness, float length, float offset, float twist) {
    }

    private boolean charging;
    /** Generation of the running landing protection; a newer one retires the older loop. */
    private int fallGuardToken;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        charging = false;
    }

    // ------------------------------------------------------------------ passive: charge stance

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isSprintHit(event)) {
            return;
        }
        double bonus = Math.max(0, params().num("sprint_bonus")) / 100.0;
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isSprintHit(event) || params().num("sprint_bonus") <= 0) {
            return;
        }
        // A thrust: a streak of light through the victim along the attack, spray and a spear's ring.
        Player owner = player();
        Location body = Fx.body(victim);
        Vector thrust = Motion.horizontal(body.toVector().subtract(owner.getLocation().toVector()));
        if (thrust.lengthSquared() < 1.0E-6) {
            thrust = Motion.horizontalFacing(owner.getLocation());
        }
        Location from = body.clone().subtract(thrust.clone().multiply(1.1));
        Location to = body.clone().add(thrust.clone().multiply(1.3));
        Vfx.line(ctx(), from, to, 6, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.7f), false);
        Vfx.trail(from, to.clone().add(thrust), PALETTE.core(), 4, false);
        Vfx.cloud(ctx(), body, Particle.SPLASH, 8, 0.25, 0.1, null, false);
        Vfx.cloud(ctx(), body, Particle.CRIT, 6, 0.2, 0.25, null, false);
        Fx.sound(body, Sound.ITEM_SPEAR_HIT, 0.8f, 1.1f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">➶ 돌격 자세</" + COLOR_TAG + "> <gray>+"
                + AbilityInfo.formatNumber(params().num("sprint_bonus")) + "%</gray>"));
    }

    /** The owner's own sword (or fist) swing while sprinting; ability damage never counts. */
    private boolean isSprintHit(EntityDamageByEntityEvent event) {
        return !ctx().isAbilityDamage() && event.getCause() == DamageCause.ENTITY_ATTACK
                && player().equals(event.getDamager()) && player().isSprinting();
    }

    @Override
    public void onTick(long tick) {
        if (tick % STANCE_PERIOD != 0 || charging) {
            return;
        }
        Player owner = player();
        if (!owner.isSprinting()) {
            return;
        }
        // The stance shows: silver wind streaming back along the lancer's side and a glint at the spear point.
        Location at = owner.getLocation();
        Vector forward = Motion.horizontalFacing(at);
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location from = at.clone().add(side.clone().multiply(random.nextDouble(-0.5, 0.5)))
                .add(0, random.nextDouble(0.3, owner.getHeight() * 0.9), 0).add(forward.clone().multiply(0.4));
        Vfx.trail(from, from.clone().subtract(forward.clone().multiply(1.6)), random.nextBoolean() ? SILVER
                : PALETTE.body(), 5, false);
        Location tip = at.clone().add(0, owner.getHeight() * 0.6, 0).add(forward.multiply(1.1));
        Vfx.dot(tip, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.5f), false);
    }

    // ------------------------------------------------------------------ active: spear throw

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!(direction.lengthSquared() > 1.0E-6)) {
            return ActivationResult.fail("던질 방향이 없습니다");
        }
        direction.normalize();
        Spear spear = new Spear(eye, direction, Math.max(1, params().num("throw_range")));
        long maxTicks = (long) Math.ceil(spear.range / THROW_SPEED) + TURN_TICKS + MAX_RETURN_TICKS + 2;
        Loop.repeat(ctx(), owner, 1, 1, maxTicks, spear, spear::finish);

        Location hand = eye.clone().subtract(0, 0.3, 0).add(direction.clone().multiply(0.6));
        World world = eye.getWorld();
        world.spawnParticle(Particle.FLASH, hand, 1, 0, 0, 0, 0, PALETTE.core());
        Vfx.cloud(ctx(), hand, Particle.SPLASH, 10, 0.2, 0.15, null, false);
        Vfx.arc(ctx(), eye.clone().subtract(0, 0.35, 0), 1.0, eye.getYaw(), 70, 9, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(0.9f), false);
        Fx.sound(eye, Sound.ITEM_TRIDENT_THROW, 1.0f, 0.9f + Fx.jitter(0.08f));
        Fx.sound(eye, Sound.ITEM_SPEAR_LUNGE_1, 0.8f, 1.1f + Fx.jitter(0.1f));
        Fx.sound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.5f + Fx.jitter(0.1f));
        return ActivationResult.success();
    }

    private enum Flight { OUT, TURN, BACK, DONE }

    /** A thrown spear: three block displays flying point first; out, a short turn, then back to the hand. */
    private final class Spear implements LongPredicate {

        private final double range;
        private final World world;
        private final double throwDamage = params().num("throw_damage");
        private final double returnDamage = params().num("return_damage");
        private final BlockDisplay[] parts = new BlockDisplay[SPEAR_PARTS.length];
        private final Set<UUID> outHits = new HashSet<>();
        private final Set<UUID> backHits = new HashSet<>();
        private Flight flight = Flight.OUT;
        private Vector point;
        private Vector heading;
        private double travelled;
        private long phaseTicks;
        private float spin;
        private boolean stuck;

        Spear(Location eye, Vector direction, double range) {
            this.range = range;
            this.world = eye.getWorld();
            this.heading = direction.clone();
            // Thrown from inside the lancer like a real throw, so a hugging enemy is not skipped.
            this.point = eye.toVector().subtract(new Vector(0, 0.2, 0));
            Location at = placement();
            long lifetime = (long) Math.ceil(range / THROW_SPEED) + TURN_TICKS + MAX_RETURN_TICKS + 10;
            for (int i = 0; i < SPEAR_PARTS.length; i++) {
                parts[i] = Displays.block(ctx(), player(), at, SPEAR_PARTS[i].material().createBlockData(),
                        pose(SPEAR_PARTS[i], 1f, 0), lifetime);
                parts[i].setTeleportDuration(1);
            }
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (!owner.getWorld().equals(world)) {
                return false;
            }
            phaseTicks++;
            spin = (spin + SPIN_PER_TICK) % 360f;
            return switch (flight) {
                case OUT -> flyOut(owner);
                case TURN -> turn(owner);
                case BACK -> flyBack(owner);
                case DONE -> false;
            };
        }

        private boolean flyOut(Player owner) {
            double step = Math.min(THROW_SPEED, range - travelled);
            Location from = point.toLocation(world);
            RayTraceResult wall = world.rayTraceBlocks(from, heading, step, FluidCollisionMode.NEVER, true);
            double advance = wall == null ? step : Math.max(0, wall.getHitPosition().distance(point) - 0.05);
            strike(owner, from, heading, advance, outHits, throwDamage, false);
            point.add(heading.clone().multiply(advance));
            travelled += advance;
            move();
            flightTrail(from, point.toLocation(world));
            if (wall != null) {
                stuck = true;
                wallHit(point.toLocation(world), wall);
                // The point bites into the wall (only the displays; the return flies through blocks anyway).
                point.add(heading.clone().multiply(WALL_BITE));
                move();
                startTurn();
            } else if (travelled >= range - 1.0E-6) {
                startTurn();
            }
            return true;
        }

        private void startTurn() {
            flight = Flight.TURN;
            phaseTicks = 0;
        }

        /** The spear trembles in place (or in the wall), then rips free and turns towards the hand. */
        private boolean turn(Player owner) {
            Location at = point.toLocation(world);
            Location shaken = at.clone();
            if (phaseTicks < TURN_TICKS) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                shaken.add(random.nextDouble(-TREMBLE, TREMBLE), random.nextDouble(-TREMBLE, TREMBLE),
                        random.nextDouble(-TREMBLE, TREMBLE));
            }
            Location place = shaken.clone().subtract(heading.clone().multiply(POINT));
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    part.teleport(oriented(place, heading));
                }
            }
            if (phaseTicks % 2 == 0) {
                Vfx.ring(ctx(), at, 0.35, 8, phaseTicks * 0.5, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.7f),
                        false);
            }
            if (phaseTicks >= TURN_TICKS) {
                flight = Flight.BACK;
                phaseTicks = 0;
                Fx.sound(at, Sound.ITEM_TRIDENT_RETURN, 1.0f, 1.0f + Fx.jitter(0.1f));
                if (stuck) {
                    Vfx.cloud(ctx(), at, Particle.CRIT, 6, 0.15, 0.2, null, false);
                }
                Vfx.cloud(ctx(), at, Particle.SPLASH, 10, 0.2, 0.2, null, false);
            }
            return true;
        }

        /** Homing back to the lancer's chest through anything in the way, striking enemies it passes again. */
        private boolean flyBack(Player owner) {
            Vector chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0).toVector();
            Vector toChest = chest.clone().subtract(point);
            double distance = toChest.length();
            if (distance <= CATCH_DISTANCE + RETURN_SPEED * 0.5 || phaseTicks > MAX_RETURN_TICKS) {
                catchSpear(owner, phaseTicks <= MAX_RETURN_TICKS);
                return false;
            }
            Vector direction = toChest.multiply(1 / distance);
            double step = Math.min(RETURN_SPEED, distance - CATCH_DISTANCE * 0.5);
            Location from = point.toLocation(world);
            // The point leads: it turns to face the hand.
            heading = direction;
            strike(owner, from, direction, step, backHits, returnDamage, true);
            point.add(direction.clone().multiply(step));
            move();
            flightTrail(from, point.toLocation(world));
            return true;
        }

        /** Hits every new enemy along the next {@code length} blocks of flight. */
        private void strike(Player owner, Location from, Vector direction, double length, Set<UUID> hit,
                            double damage, boolean pull) {
            for (double d = 0; d <= length + 1.0E-6; d += SAMPLE_STEP) {
                Location sample = from.clone().add(direction.clone().multiply(Math.min(d, length)));
                for (LivingEntity enemy : ctx().enemiesNear(owner, sample, SPEAR_REACH)) {
                    if (!hit.add(enemy.getUniqueId())) {
                        continue;
                    }
                    Combat.hit(ctx(), owner, enemy, damage);
                    pierce(enemy, direction, pull);
                    if (pull && Combat.isTargetable(ctx(), owner, enemy)) {
                        Vector toOwner = Motion.horizontal(owner.getLocation().toVector()
                                .subtract(enemy.getLocation().toVector()));
                        Motion.push(enemy, toOwner.multiply(RETURN_PULL).setY(RETURN_LIFT), MAX_VELOCITY);
                    }
                }
            }
        }

        private void move() {
            Location place = point.toLocation(world).subtract(heading.clone().multiply(POINT));
            Location at = oriented(place, heading);
            for (int i = 0; i < parts.length; i++) {
                BlockDisplay part = parts[i];
                if (part.isValid()) {
                    part.teleport(at);
                    Displays.animate(part, pose(SPEAR_PARTS[i], 1f, spin), 1);
                }
            }
        }

        /** Where the displays stand (the spear's center) for the current point and heading. */
        private Location placement() {
            return oriented(point.toLocation(world).subtract(heading.clone().multiply(POINT)), heading);
        }

        private void catchSpear(Player owner, boolean caught) {
            flight = Flight.DONE;
            Location at = point.toLocation(world);
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 3);
                }
            }
            if (caught) {
                Location hand = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
                Vfx.ring(ctx(), hand, 0.5, 10, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), false);
                Vfx.cloud(ctx(), hand, Particle.SPLASH, 8, 0.2, 0.1, null, false);
                Fx.sound(hand, Sound.ITEM_TRIDENT_RETURN, 0.8f, 1.4f + Fx.jitter(0.1f));
                Fx.sound(hand, Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.6f, 1.3f + Fx.jitter(0.1f));
                int hits = outHits.size() + backHits.size();
                if (hits > 0) {
                    owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">➶ 창 투척</" + COLOR_TAG + "> <gray>" + hits
                            + "회 적중</gray>"));
                }
            } else {
                Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, PALETTE.fade(0.8f), false);
            }
        }

        void finish() {
            if (flight != Flight.DONE) {
                catchSpear(player(), false);
            }
        }

        /** Sea-green light along the flight, silver motes and a little spray. */
        private void flightTrail(Location from, Location to) {
            Vfx.line(ctx(), from, to, 4, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.7f), false);
            Location back = to.clone().subtract(heading.clone().multiply(1.6));
            Vfx.dot(back, Particle.DUST, SILVER_DUST, false);
            if (phaseTicks % 2 == 0) {
                Vfx.cloud(ctx(), to, Particle.SPLASH, 3, 0.1, 0.05, null, false);
            }
            Vfx.dot(to, Particle.END_ROD, null, false);
        }

        private void wallHit(Location at, RayTraceResult wall) {
            if (wall.getHitBlock() != null) {
                BlockData data = wall.getHitBlock().getBlockData();
                at.getWorld().spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 12), 0.15, 0.15, 0.15, 0.1, data);
            }
            Vfx.cloud(ctx(), at, Particle.CRIT, 8, 0.15, 0.25, null, false);
            Fx.sound(at, Sound.ITEM_TRIDENT_HIT_GROUND, 1.0f, 1.0f + Fx.jitter(0.1f));
        }
    }

    /** A pierced enemy: a spray of sea-green light out of its back, sparks and the spear's hit. */
    private void pierce(LivingEntity enemy, Vector direction, boolean returning) {
        Location body = Fx.body(enemy);
        Vfx.burst(ctx(), body, PALETTE, 0.5, Particle.CRIT, false);
        Location exit = body.clone().add(direction.clone().multiply(0.4));
        int motes = Vfx.n(ctx(), 5);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < motes; i++) {
            Vector spray = direction.clone().multiply(random.nextDouble(0.8, 1.6))
                    .add(new Vector(random.nextGaussian() * 0.35, random.nextGaussian() * 0.35,
                            random.nextGaussian() * 0.35));
            Vfx.trail(exit, exit.clone().add(spray), i % 2 == 0 ? PALETTE.core() : PALETTE.body(), 5, false);
        }
        Vfx.cloud(ctx(), body, Particle.SPLASH, 6, 0.2, 0.1, null, false);
        Fx.sound(body, Sound.ITEM_TRIDENT_HIT, 1.0f, (returning ? 1.3f : 1.0f) + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ITEM_SPEAR_HIT, 0.6f, 1.2f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ ultimate: piercing charge

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 돌격할 수 없습니다");
        }
        if (charging) {
            return ActivationResult.fail("이미 돌격 중입니다");
        }
        Location start = owner.getLocation();
        Vector direction = Motion.horizontalFacing(start);
        double distance = Spots.arenaReach(ctx(), start, direction, Math.max(1, params().num("ult_distance")));
        if (distance < 2) {
            return ActivationResult.fail("자기장 밖으로는 돌격할 수 없습니다");
        }
        if (!Spots.hasGroundBelow(start.getWorld(), start.clone().add(direction.clone().multiply(VOID_LOOKAHEAD)))) {
            return ActivationResult.fail("앞이 낭떠러지입니다");
        }
        charging = true;
        Charge charge = new Charge(direction, distance);
        long maxTicks = WINDUP_TICKS + (long) Math.ceil(distance / CHARGE_SPEED) + 8 + lagTicks(owner);
        Loop.repeat(ctx(), owner, 0, 1, maxTicks, charge, charge::end);
        return ActivationResult.success();
    }

    /** Ticks the server sees the owner's movement late: one round trip, capped (ping can read 0 after joining). */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / MILLIS_PER_TICK));
    }

    /** One 일섬 돌격: the wind-up with its lane marker, the charge carrying skewered enemies, and the hurl. */
    private final class Charge implements LongPredicate {

        private final Vector direction;
        private final double distance;
        private final double damage = params().num("ult_damage");
        private final double flingDamage = params().num("ult_fling_damage");
        private final Location laneStart;
        private final List<LivingEntity> skewered = new ArrayList<>();
        private final Set<UUID> struck = new HashSet<>();
        private final BlockDisplay[] spear = new BlockDisplay[SPEAR_PARTS.length];
        private final long lag = lagTicks(player());
        private @Nullable Location start;
        private Location last;
        private int stalls;
        private boolean moved;
        private boolean ended;
        /** The charge pulled up at the edge of the void. */
        private boolean edge;

        Charge(Vector direction, double distance) {
            this.direction = direction;
            this.distance = distance;
            Player owner = player();
            this.laneStart = owner.getLocation();
            this.last = laneStart;
            long lifetime = WINDUP_TICKS + (long) Math.ceil(distance / CHARGE_SPEED) + MAX_LAG_TICKS + 30;
            Location at = oriented(spearSpot(owner, -0.6), direction);
            for (int i = 0; i < SPEAR_PARTS.length; i++) {
                spear[i] = Displays.block(ctx(), owner, at, SPEAR_PARTS[i].material().createBlockData(),
                        pose(SPEAR_PARTS[i], 0.3f, 0), lifetime);
                spear[i].setTeleportDuration(1);
            }
            windupCast(owner);
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (tick < WINDUP_TICKS) {
                windup(owner, tick);
                return true;
            }
            if (start == null) {
                launch(owner);
                return true;
            }
            return charge(owner, tick - WINDUP_TICKS);
        }

        /** The lane lights up from the lancer's feet to the end of the charge; the spear is drawn back. */
        private void windup(Player owner, long tick) {
            double progress = Math.min(1.0, (tick + 1) / (double) (WINDUP_TICKS - 2));
            Location from = laneStart.clone().add(0, 0.1, 0);
            Location to = from.clone().add(direction.clone().multiply(distance * progress));
            Vfx.line(ctx(), from, to, 2.5, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), true);
            Vector side = new Vector(-direction.getZ(), 0, direction.getX()).multiply(0.6);
            if (tick % 2 == 0) {
                Vfx.line(ctx(), from.clone().add(side), to.clone().add(side), 1.2, Particle.DUST, SILVER_DUST, true);
                Vfx.line(ctx(), from.clone().subtract(side), to.clone().subtract(side), 1.2, Particle.DUST,
                        SILVER_DUST, true);
            }
            if (progress >= 1.0) {
                Vfx.ring(ctx(), to, 1.0, 14, tick * 0.3, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.0f), true);
            }
            double drawn = Math.min(1.0, (tick + 1) / (double) WINDUP_TICKS);
            double pull = -0.6 - 0.35 * drawn;
            float scale = 0.3f + (CHARGE_SPEAR_SCALE - 0.3f) * (float) Math.min(1.0, (tick + 1) / 4.0);
            placeSpear(owner, pull, scale, tick * 4f);
            Location hand = spearSpot(owner, pull);
            Vfx.trail(hand.clone().add(direction.clone().multiply(2.5)), hand, PALETTE.core(), 4, false);
        }

        private void launch(Player owner) {
            start = owner.getLocation();
            last = start;
            owner.setVelocity(direction.clone().multiply(CHARGE_SPEED).setY(CHARGE_SINK));
            owner.setFallDistance(0);
            Location chest = start.clone().add(0, owner.getHeight() * 0.55, 0);
            World world = chest.getWorld();
            world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, PALETTE.core(), true);
            world.spawnParticle(Particle.GUST_EMITTER_SMALL, start.clone().add(0, 0.2, 0), 1, 0, 0, 0, 0, null, true);
            Vfx.cloud(ctx(), chest, Particle.SPLASH, 24, 0.5, 0.3, null, true);
            Fx.sound(start, Sound.ITEM_TRIDENT_RIPTIDE_3, 1.2f, 1.0f + Fx.jitter(0.05f));
            Fx.sound(start, Sound.ITEM_SPEAR_LUNGE_3, 1.0f, 0.9f + Fx.jitter(0.05f));
            Fx.sound(start, Sound.ENTITY_BREEZE_WIND_BURST, 0.8f, 0.8f + Fx.jitter(0.05f));
        }

        private boolean charge(Player owner, long tick) {
            Location begin = start;
            Location now = owner.getLocation();
            if (begin == null || !now.getWorld().equals(begin.getWorld())
                    || horizontalDistance(begin, now) >= distance - 0.2) {
                return false;
            }
            if (!moved && horizontalDistance(begin, now) >= STALL_EPSILON) {
                moved = true;
            }
            if (moved || tick >= CHARGE_GRACE_TICKS + lag) {
                stalls = horizontalDistance(last, now) < STALL_EPSILON ? stalls + 1 : 0;
                if (stalls >= STALL_LIMIT) {
                    return false;
                }
            }
            last = now;
            // Never into the void (or a lava pit): the charge pulls up at the edge.
            if (!Spots.hasGroundBelow(now.getWorld(), now.clone().add(direction.clone().multiply(VOID_LOOKAHEAD)))) {
                edge = true;
                return false;
            }
            owner.setVelocity(direction.clone().multiply(CHARGE_SPEED).setY(CHARGE_SINK));
            owner.setFallDistance(0);

            double height = owner.getHeight();
            Location point = now.clone().add(0, height * 0.55, 0).add(direction.clone().multiply(SKEWER_AHEAD));
            for (LivingEntity enemy : ctx().enemiesNear(owner, point, SKEWER_REACH)) {
                if (struck.add(enemy.getUniqueId())) {
                    skewer(owner, enemy);
                }
            }
            carry(owner, now);
            placeSpear(owner, 0.9 + CHARGE_SPEED, CHARGE_SPEAR_SCALE, WINDUP_TICKS * 4f + tick * 20f);
            riptide(owner, now, tick);
            return true;
        }

        private void skewer(Player owner, LivingEntity enemy) {
            Combat.hit(ctx(), owner, enemy, damage);
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            skewered.add(enemy);
            pierce(enemy, direction, false);
            Fx.sound(Fx.body(enemy), Sound.ENTITY_GOAT_RAM_IMPACT, 0.8f, 1.2f + Fx.jitter(0.1f));
        }

        /** Keeps every skewered enemy riding on the spear ahead of the lancer; one stopped by a wall slips off. */
        private void carry(Player owner, Location now) {
            for (int i = skewered.size() - 1; i >= 0; i--) {
                LivingEntity enemy = skewered.get(i);
                if (!Combat.isTargetable(ctx(), owner, enemy) || !enemy.getWorld().equals(now.getWorld())) {
                    skewered.remove(i);
                    continue;
                }
                Location spot = now.clone().add(direction.clone().multiply(CARRY_AHEAD + i * CARRY_SPACING));
                Vector offset = spot.toVector().subtract(enemy.getLocation().toVector());
                if (offset.length() > CARRY_LEASH) {
                    skewered.remove(i);
                    continue;
                }
                Vector velocity = direction.clone().multiply(CHARGE_SPEED).add(offset.multiply(CARRY_PULL));
                velocity.setY(Math.max(0.02, Math.min(0.3, velocity.getY())));
                Motion.push(enemy, velocity, MAX_VELOCITY);
                Vfx.dot(Fx.body(enemy), Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.0f), false);
            }
        }

        /** The end of the charge: everyone on the spear is hurled forward and up. */
        void end() {
            if (ended) {
                return;
            }
            ended = true;
            charging = false;
            Player owner = player();
            Location at = owner.getLocation();
            owner.setVelocity(edge ? new Vector(0, CHARGE_SINK, 0) : direction.clone().multiply(0.2).setY(CHARGE_SINK));
            guardLanding(owner);
            wake(owner, at);
            int flung = 0;
            for (LivingEntity enemy : skewered) {
                Combat.hit(ctx(), owner, enemy, flingDamage);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                flung++;
                Motion.push(enemy, direction.clone().multiply(FLING_FORWARD).setY(FLING_UP), MAX_VELOCITY);
                Vfx.burst(ctx(), Fx.body(enemy), PALETTE, 0.7, Particle.CRIT, true);
            }
            skewered.clear();
            hurl(owner, at, flung);
            if (!struck.isEmpty()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>➶ 일섬 돌격</b></" + COLOR_TAG + "> <gray>"
                        + struck.size() + "명 관통</gray>"));
            }
        }

        private void hurl(Player owner, Location at, int flung) {
            World world = at.getWorld();
            Location front = at.clone().add(0, owner.getHeight() * 0.55, 0).add(direction.clone().multiply(1.6));
            world.spawnParticle(Particle.FLASH, front, 1, 0, 0, 0, 0, PALETTE.core(), true);
            Vfx.cloud(ctx(), front, Particle.SPLASH, 30, 0.6, 0.4, null, true);
            float yaw = yawOf(direction);
            Loop.repeat(ctx(), owner, 0, 1, 5, tick -> {
                double r = 1.0 + tick * 0.8;
                Vfx.arc(ctx(), front, r, yaw, 120 - tick * 10, 10 + (int) tick * 3, Particle.DUST_COLOR_TRANSITION,
                        PALETTE.glow(1.3f), true);
                Vfx.arc(ctx(), at.clone().add(0, 0.15, 0), r, yaw, 110, 8 + (int) tick * 2, Particle.CLOUD, null,
                        false);
                return true;
            });
            Fx.sound(front, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 0.8f, 1.2f + Fx.jitter(0.1f));
            Fx.sound(front, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f + Fx.jitter(0.05f));
            if (flung > 0) {
                Fx.sound(front, Sound.ITEM_TRIDENT_THUNDER, 0.5f, 1.8f);
                Fx.sound(front, Sound.ENTITY_GOAT_RAM_IMPACT, 1.0f, 0.8f + Fx.jitter(0.1f));
            }
            // The spear thrusts once more and dissolves into spray.
            for (int i = 0; i < spear.length; i++) {
                BlockDisplay part = spear[i];
                if (!part.isValid()) {
                    continue;
                }
                part.teleport(oriented(spearSpot(owner, 1.6), direction));
                Displays.animate(part, pose(SPEAR_PARTS[i], CHARGE_SPEAR_SCALE * 1.2f, 0), 3);
                ctx().runLater(owner, 4, () -> {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 5);
                    }
                });
            }
        }

        /** Afterglow: the charged lane fades out in sea-green motes and rising spray. */
        private void wake(Player owner, Location end) {
            Location from = start;
            if (from == null || !from.getWorld().equals(end.getWorld())) {
                return;
            }
            Location a = from.clone().add(0, 0.15, 0);
            Location b = end.clone().add(0, 0.15, 0);
            Vfx.line(ctx(), a, b, 2, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.9f), false);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Vector path = b.toVector().subtract(a.toVector());
            Loop.repeat(ctx(), owner, 2, 3, 5, run -> {
                for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                    Location mote = a.clone().add(path.clone().multiply(random.nextDouble()));
                    Vfx.trail(mote, mote.clone().add(0, random.nextDouble(0.6, 1.4), 0),
                            i % 2 == 0 ? PALETTE.body() : SILVER, 12, false);
                }
                return true;
            });
        }

        private void windupCast(Player owner) {
            Location at = owner.getLocation();
            Location chest = at.clone().add(0, owner.getHeight() * 0.55, 0);
            Fx.sound(at, Sound.ITEM_CROSSBOW_QUICK_CHARGE_3, 1.0f, 0.7f);
            Fx.sound(at, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.0f, 0.8f);
            Vfx.helix(ctx(), at, 0.7, owner.getHeight() + 0.4, 1.5, 2, 12, 0, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.0f), true);
            Vfx.cloud(ctx(), chest, Particle.SPLASH, 16, 0.4, 0.2, null, true);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>➶ 일섬 돌격</b></" + COLOR_TAG + ">"));
        }

        /** A spiral of sea-green light and spray around the lancer, the riptide that carries it. */
        private void riptide(Player owner, Location now, long tick) {
            World world = now.getWorld();
            double height = owner.getHeight();
            Location axis = now.clone().add(0, height * 0.55, 0);
            Vector side = new Vector(-direction.getZ(), 0, direction.getX());
            Vector up = new Vector(0, 1, 0);
            int points = Vfx.n(ctx(), 5);
            for (int strand = 0; strand < 3; strand++) {
                for (int k = 0; k < points; k++) {
                    double along = 1.2 - k * 0.45;
                    double angle = tick * 0.9 + k * 0.6 + strand * TAU / 3;
                    double radius = 0.75 + k * 0.05;
                    Location at = axis.clone().add(direction.clone().multiply(along))
                            .add(side.clone().multiply(Math.cos(angle) * radius))
                            .add(up.clone().multiply(Math.sin(angle) * radius));
                    Vfx.dot(at, Particle.DUST_COLOR_TRANSITION,
                            strand == 0 ? PALETTE.glow(1.0f) : PALETTE.fade(0.9f), false);
                }
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int streaks = Vfx.n(ctx(), 2);
            for (int i = 0; i < streaks; i++) {
                Location from = now.clone().add(side.clone().multiply(random.nextDouble(-0.5, 0.5)))
                        .add(0, random.nextDouble(0.2, height), 0).add(direction.clone().multiply(0.5));
                Vfx.trail(from, from.clone().subtract(direction.clone().multiply(2.2)), i == 0 ? SILVER
                        : PALETTE.core(), 5, false);
            }
            world.spawnParticle(Particle.SPLASH, now.clone().add(0, 0.3, 0), Vfx.n(ctx(), 4), 0.3, 0.1, 0.3, 0.1);
        }

        /** Moves the charge spear {@code lead} blocks ahead of the chest, at {@code scale}, turned {@code roll}. */
        private void placeSpear(Player owner, double lead, float scale, float roll) {
            Location at = oriented(spearSpot(owner, lead), direction);
            for (int i = 0; i < spear.length; i++) {
                BlockDisplay part = spear[i];
                if (part.isValid()) {
                    part.teleport(at);
                    Displays.animate(part, pose(SPEAR_PARTS[i], scale, roll % 360f), 1);
                }
            }
        }

        /** The charge spear's center: at the lancer's side, {@code lead} blocks ahead of its chest. */
        private Location spearSpot(Player owner, double lead) {
            Vector side = new Vector(-direction.getZ(), 0, direction.getX()).multiply(-0.35);
            return owner.getLocation().add(0, owner.getHeight() * 0.5, 0).add(side)
                    .add(direction.clone().multiply(lead));
        }
    }

    /**
     * Keeps the owner's fall distance at zero until it stands on the ground or swims (at most
     * {@link #LANDING_GRACE_TICKS}), so a charge that ran off a ledge never turns into fall damage. A loop of its
     * own (no hooks), so a borrowed charge is protected too.
     */
    private void guardLanding(Player owner) {
        int token = ++fallGuardToken;
        Loop.repeat(ctx(), owner, 0, 1, LANDING_GRACE_TICKS, run -> {
            if (token != fallGuardToken) {
                return false;
            }
            owner.setFallDistance(0);
            return run < 2 || (!Motion.isGrounded(owner) && !owner.isInWater());
        });
    }

    // ------------------------------------------------------------------ helpers

    /** One spear part at {@code scale}, rolled {@code roll} degrees about the spear's length. */
    private static Transformation pose(Part part, float scale, float roll) {
        return Displays.transform(0, 0, part.offset() * scale, part.width() * scale, part.thickness() * scale,
                part.length() * scale, 0, 0, (roll + part.twist()) % 360f);
    }

    /** A copy of {@code at} turned to face {@code direction} (display entities rotate with their location). */
    private static Location oriented(Location at, Vector direction) {
        Location copy = at.clone();
        if (direction.lengthSquared() > 1.0E-8 && Double.isFinite(direction.lengthSquared())) {
            copy.setDirection(direction);
        } else {
            copy.setYaw(0);
            copy.setPitch(0);
        }
        return copy;
    }

    private static float yawOf(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }
}
