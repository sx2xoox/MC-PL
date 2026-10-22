package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 스피드스터 — a lightning-fast runner. Running without a stop builds momentum (more speed, step by step, and a
 * stronger dash at top speed), the active is a short lightning dash that can be chained up to three times (charges
 * that come back one by one), and the ultimate is light speed: a burst of extreme speed with afterimages that run the
 * owner's exact path a moment later, striking every enemy they brush past.
 *
 * <p>Unlike the 바람술사's leap or the 검성's single instant cut, the speedster's dashes are real motion in quick
 * succession, and its ultimate deals damage by running, not by standing in an area.
 *
 * <p>Look: white-hot yellow fading into red, with lightning. Momentum shows as orbiting sparks (one per stored dash),
 * a yellow-to-red particle afterimage of the runner that flickers behind it from the third step on, and crackling
 * sparks at the feet at top speed. A dash leaves an afterimage at the start, a zigzag bolt of thin glowing bars along
 * the path, speed streaks and a spark burst on every enemy it passes. Light speed strikes the runner with a bolt from
 * above, then trails a ground lightning line while its afterimages (the owner's own head on a yellow, an orange and a
 * red glass body) chase it along its path, each strike a flash of the echo's colour; at the end they catch up and
 * merge back into the runner.
 */
public final class SpeedsterAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("speedster", "스피드스터", Material.SUGAR)
            .color(TextColor.color(0xFFD23F))
            .difficulty(3)
            .passive("가속",
                    "멈추지 않고 달리면 {momentum_step}초마다 이동 속도가 {momentum_speed}%씩 빨라집니다(최대 "
                            + "{momentum_stacks}단계). {momentum_grace}초 넘게 멈추면 처음부터 다시 쌓이며, 최고 속도에서 쓰는 "
                            + "질주는 피해가 {momentum_dash_bonus}% 증가합니다.")
            .active("연속 질주",
                    "바라보는 방향으로 {dash_distance}블록을 순식간에 질주해 지나간 길의 적에게 {dash_damage} 피해를 줍니다. "
                            + "질주는 최대 {dash_charges}회까지 이어서 쓸 수 있고, {cooldown}초마다 1회씩 다시 충전됩니다.",
                    5)
            .ultimate("광속",
                    "{ult_duration}초간 이동 속도가 {ult_speed}% 빨라지고 낙하 피해를 받지 않습니다. 내가 달린 길을 잔상 "
                            + "{ult_echoes}개가 뒤따르며, 달리는 나와 잔상이 스치는 적은 {ult_damage} 피해를 입습니다(적마다 "
                            + "최대 {ult_max_hits}회).")
            .param("momentum_step", 1)
            .param("momentum_speed", 6)
            .param("momentum_stacks", 5)
            .param("momentum_grace", 0.6)
            .param("momentum_dash_bonus", 50)
            .param("dash_charges", 3)
            .param("dash_gap", 0.3)
            .param("dash_distance", 5)
            .param("dash_damage", 2)
            .param("dash_width", 1.1)
            .param("ult_duration", 6)
            .param("ult_speed", 150)
            .param("ult_echoes", 3)
            .param("ult_damage", 1.5)
            .param("ult_radius", 1.0)
            .param("ult_max_hits", 8)
            .build();

    private static final String COLOR_TAG = "#FFD23F";
    private static final String RED_TAG = "#FF5A3C";
    private static final double TAU = Math.PI * 2;
    /** White-hot core, lightning yellow body, red edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFBE0, 0xFFD23F, 0xE23A2A);
    private static final Particle.DustTransition HOT = PALETTE.glow(0.9f);
    private static final Particle.DustTransition COOLING = PALETTE.fade(0.8f);
    private static final Particle.DustOptions SPARK = PALETTE.dust(0.7f);
    private static final Particle.DustOptions RED_DUST = new Particle.DustOptions(PALETTE.edge(), 0.8f);
    /** The colours of the afterimages, first to last: yellow, orange, red. */
    private static final Color[] ECHO_COLORS = {Color.fromRGB(0xFFD23F), Color.fromRGB(0xFF8A2A),
            Color.fromRGB(0xE23A2A)};
    private static final Material[] ECHO_GLASS = {Material.YELLOW_STAINED_GLASS, Material.ORANGE_STAINED_GLASS,
            Material.RED_STAINED_GLASS};
    private static final Material BOLT_CORE = Material.WHITE_CONCRETE;
    private static final Material BOLT_GLOW = Material.YELLOW_STAINED_GLASS;
    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;
    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;

    // passive: momentum
    /** Horizontal blocks per tick (smoothed) that count as running; walking is about 0.22, sprinting 0.28. */
    private static final double RUN_SPEED = 0.15;
    /** A single tick's movement counts at most this much (teleports are not running). */
    private static final double MAX_STEP = 1.2;
    private static final long AFTERIMAGE_PERIOD = 4;
    private static final int AFTERIMAGE_FROM = 3;
    private static final long ORBIT_PERIOD = 2;

    // active: chained dash
    private static final int DASH_TICKS = 4;
    /** The speed left after a dash, so the runner neither stops dead nor slides on. */
    private static final double DASH_EXIT = 0.15;
    private static final double SAMPLE_STEP = 0.5;
    private static final double SHOVE_SIDE = 0.45;
    private static final double SHOVE_UP = 0.25;
    private static final long LANDING_GUARD_TICKS = 60;
    private static final int BOLT_SEGMENTS = 3;
    private static final long BOLT_LIFE = 8;

    // ultimate: light speed
    /** Ticks each afterimage runs behind the one before it. */
    private static final int ECHO_SPACING = 5;
    private static final int MAX_ECHOES = 4;
    /** A source (the runner or an afterimage) only strikes while it moves at least this fast (blocks per tick). */
    private static final double STRIKE_SPEED = 0.2;
    /** The same enemy is struck at most once in this many ticks (by anyone). */
    private static final long STRIKE_SPACING = 4;
    private static final long FALL_GRACE = 40;
    private static final double STEP_BONUS = 0.6;
    private static final long TRAIL_BOLT_PERIOD = 4;
    private static final int STRIKE_SEGMENTS = 4;
    private static final double STRIKE_HEIGHT = 9;
    private static final int MERGE_TICKS = 6;

    /** Every display this ability currently has alive, for the budget. */
    private final List<Display> displays = new ArrayList<>();
    private final LandingGuard landing = new LandingGuard();

    // momentum state
    private @Nullable Location lastFeet;
    private double smoothedSpeed;
    private int stacks;
    private long runTicks;
    private long idleTicks;
    private @Nullable NamespacedKey momentumKey;

    // dash state
    private int charges = -1;
    private long recharge;
    private double nextCooldownFactor = 1.0;
    private int dashToken;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        charges = maxCharges();
    }

    @Override
    public void onRevoke() {
        momentumKey = null;
        stacks = 0;
        lastFeet = null;
        displays.clear();
        landing.disarm();
        dashToken++;
    }

    @Override
    public double cooldownMultiplier() {
        return nextCooldownFactor;
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        landing.absorb(event, ctx().currentTick());
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        landing.update(owner, now);
        momentum(owner, tick);
        rechargeTick(owner);
        if (tick % ORBIT_PERIOD == 0) {
            orbit(owner, tick);
        }
    }

    // ------------------------------------------------------------------ passive: momentum

    /** Measures the running speed from the owner's movement and builds or drops momentum stacks. */
    private void momentum(Player owner, long tick) {
        Location feet = owner.getLocation();
        double moved = 0;
        if (lastFeet != null && lastFeet.getWorld().equals(feet.getWorld())) {
            moved = Math.min(MAX_STEP, Math.hypot(feet.getX() - lastFeet.getX(), feet.getZ() - lastFeet.getZ()));
        }
        Location previous = lastFeet;
        lastFeet = feet;
        smoothedSpeed = smoothedSpeed * 0.5 + moved * 0.5;
        int max = Math.max(0, params().integer("momentum_stacks"));
        long stepTicks = Math.max(1, params().ticks("momentum_step"));
        long grace = params().ticks("momentum_grace");
        if (smoothedSpeed >= RUN_SPEED) {
            idleTicks = 0;
            if (stacks < max && ++runTicks >= stepTicks) {
                runTicks = 0;
                setStacks(owner, stacks + 1);
            }
            if (stacks >= AFTERIMAGE_FROM && previous != null && tick % AFTERIMAGE_PERIOD == 0) {
                afterimage(previous, owner.getLocation().getYaw(), stacks >= max ? HOT : COOLING);
            }
            if (stacks >= max && max > 0 && tick % 2 == 0) {
                Location heel = feet.clone().add(0, 0.1, 0);
                Vfx.cloud(ctx(), heel, Particle.ELECTRIC_SPARK, 2, 0.2, 0.05, null, false);
                Vfx.dot(heel, Particle.DUST, SPARK, false);
            }
        } else {
            runTicks = 0;
            if (stacks > 0 && ++idleTicks > grace) {
                setStacks(owner, 0);
            }
        }
    }

    private void setStacks(Player owner, int value) {
        int max = Math.max(0, params().integer("momentum_stacks"));
        int previous = stacks;
        stacks = Math.clamp(value, 0, Math.max(0, max));
        if (momentumKey != null) {
            ctx().removeModifier(owner, momentumKey);
            momentumKey = null;
        }
        double bonus = stacks * Math.max(0, params().num("momentum_speed")) / 100.0;
        if (bonus > 0) {
            momentumKey = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, bonus,
                    Operation.MULTIPLY_SCALAR_1, 0);
        }
        Location feet = owner.getLocation().add(0, 0.1, 0);
        if (stacks > previous) {
            Vfx.ring(ctx(), feet, 0.5 + 0.08 * stacks, 10 + 2 * stacks, ThreadLocalRandom.current().nextDouble(TAU),
                    Particle.DUST_COLOR_TRANSITION, HOT, false);
            Fx.sound(feet, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.35f, 0.8f + 0.15f * stacks);
            boolean top = stacks >= max;
            if (top) {
                Vfx.cloud(ctx(), Fx.body(owner), Particle.ELECTRIC_SPARK, 12, 0.35, 0.2, null, false);
                Fx.sound(feet, Sound.BLOCK_BEACON_POWER_SELECT, 0.5f, 1.9f);
            }
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚡ 가속 <white>" + meter(stacks, max) + "</white>"
                    + (top ? " <" + RED_TAG + "><b>최고 속도!</b></" + RED_TAG + ">" : "") + "</" + COLOR_TAG + ">"));
        } else if (previous >= 2 && stacks == 0) {
            Vfx.cloud(ctx(), feet, Particle.POOF, 4, 0.2, 0.01, null, false);
            Fx.sound(feet, Sound.BLOCK_FIRE_EXTINGUISH, 0.3f, 1.8f);
        }
    }

    private static String meter(int filled, int max) {
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < max; i++) {
            bar.append(i < filled ? '▮' : '▯');
        }
        return bar.toString();
    }

    /** Stored dashes orbit the runner's waist as small sparks. */
    private void orbit(Player owner, long tick) {
        if (charges <= 0) {
            return;
        }
        Location waist = owner.getLocation().add(0, 0.85, 0);
        for (int i = 0; i < charges; i++) {
            double angle = tick * 0.25 + i * TAU / Math.max(1, charges);
            Vfx.dot(waist.clone().add(Math.cos(angle) * 0.55, 0.1 * Math.sin(tick * 0.3 + i), Math.sin(angle) * 0.55),
                    Particle.DUST, SPARK, false);
        }
    }

    // ------------------------------------------------------------------ active: chained dash

    private int maxCharges() {
        return Math.max(1, params().integer("dash_charges"));
    }

    /** One stored dash comes back every cooldown while fewer than the maximum are stored. */
    private void rechargeTick(Player owner) {
        int max = maxCharges();
        if (charges < 0) {
            charges = max;
        }
        if (charges >= max) {
            charges = max;
            recharge = 0;
            return;
        }
        long cycle = params().ticks(AbilityInfo.COOLDOWN);
        if (++recharge >= cycle) {
            recharge = 0;
            charges++;
            Location waist = owner.getLocation().add(0, 0.9, 0);
            Vfx.cloud(ctx(), waist, Particle.ELECTRIC_SPARK, 5, 0.3, 0.1, null, false);
            owner.playSound(owner, Sound.BLOCK_COPPER_BULB_TURN_ON, SoundCategory.PLAYERS, 0.5f,
                    1.4f + 0.15f * charges);
        }
    }

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 질주할 수 없습니다");
        }
        Location start = owner.getLocation();
        Vector direction = Motion.horizontalFacing(start);
        double distance = Spots.arenaReach(ctx(), start, direction, Math.max(1, params().num("dash_distance")));
        distance = clearReach(owner, start, direction, distance);
        if (distance < 1) {
            return ActivationResult.fail("앞이 막혀 있습니다");
        }
        if (charges < 0) {
            charges = maxCharges();
        }
        int max = maxCharges();
        boolean topSpeed = stacks >= Math.max(1, params().integer("momentum_stacks"));
        boolean last = charges <= 1;
        charges = Math.max(0, charges - 1);
        long cycle = params().ticks(AbilityInfo.COOLDOWN);
        if (cycle <= 0) {
            nextCooldownFactor = 1.0;
        } else if (charges > 0) {
            nextCooldownFactor = Math.min(1.0, params().ticks("dash_gap") / (double) cycle);
        } else {
            nextCooldownFactor = Math.max(0.0, (cycle - recharge) / (double) cycle);
        }

        double damage = params().num("dash_damage");
        if (topSpeed) {
            damage *= 1 + Math.max(0, params().num("momentum_dash_bonus")) / 100.0;
        }
        new Dash(owner, start, direction, distance, damage, topSpeed, last).run();
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚡ 연속 질주 <white>" + pips(charges, max) + "</white>"
                + (topSpeed ? " <" + RED_TAG + ">최고 속도!</" + RED_TAG + ">" : "") + "</" + COLOR_TAG + ">"));
        return ActivationResult.success();
    }

    private static String pips(int filled, int max) {
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < max; i++) {
            bar.append(i < filled ? '●' : '○');
        }
        return bar.toString();
    }

    /** How far the dash gets before a wall: checked at knee and chest height. */
    private static double clearReach(Player owner, Location start, Vector direction, double distance) {
        World world = start.getWorld();
        double reach = distance;
        for (double height : new double[] {0.35, owner.getHeight() * 0.75}) {
            RayTraceResult wall = world.rayTraceBlocks(start.clone().add(0, height, 0), direction, distance + 0.3,
                    FluidCollisionMode.NEVER, true);
            if (wall != null) {
                reach = Math.min(reach, wall.getHitPosition().distance(start.clone().add(0, height, 0).toVector())
                        - 0.4);
            }
        }
        return Math.max(0, reach);
    }

    /**
     * One dash: the runner is driven along its path for a few ticks (velocity set every tick, so ground friction
     * cannot shorten it), and every enemy along the planned path is struck as the runner passes its part of it.
     */
    private final class Dash {

        private final Player owner;
        private final Location start;
        private final Vector direction;
        private final double distance;
        private final double damage;
        private final boolean topSpeed;
        private final boolean last;
        private final Set<UUID> struck = new HashSet<>();
        private final int token;
        private int hits;

        Dash(Player owner, Location start, Vector direction, double distance, double damage, boolean topSpeed,
             boolean last) {
            this.owner = owner;
            this.start = start.clone();
            this.direction = direction.clone();
            this.distance = distance;
            this.damage = damage;
            this.topSpeed = topSpeed;
            this.last = last;
            this.token = ++dashToken;
        }

        void run() {
            landing.arm(ctx().currentTick(), LANDING_GUARD_TICKS);
            owner.setFallDistance(0);
            double speed = distance / DASH_TICKS;
            launchFx();
            Loop.repeat(ctx(), owner, 0, 1, DASH_TICKS + 1, tick -> {
                if (token != dashToken && tick > 0) {
                    // A newer dash took over the runner's motion.
                    return false;
                }
                if (tick >= DASH_TICKS) {
                    owner.setVelocity(direction.clone().multiply(DASH_EXIT));
                    finishFx();
                    return false;
                }
                // Level: a dash started mid-jump skims straight on instead of rising or dropping.
                owner.setVelocity(Motion.clamp(direction.clone().multiply(speed), MAX_SPEED));
                owner.setFallDistance(0);
                strike(tick);
                streak(tick);
                return true;
            });
        }

        /** Strikes enemies along the part of the planned path the runner covers this tick. */
        private void strike(long tick) {
            double from = distance * tick / DASH_TICKS;
            double to = distance * (tick + 1) / DASH_TICKS;
            double width = Math.max(0, params().num("dash_width"));
            Location waist = start.clone().add(0, owner.getHeight() * 0.5, 0);
            for (double d = from; d <= to + 1.0E-6; d += SAMPLE_STEP) {
                Location sample = waist.clone().add(direction.clone().multiply(d));
                for (LivingEntity enemy : ctx().enemiesNear(owner, sample, width)) {
                    if (struck.add(enemy.getUniqueId())) {
                        hit(enemy);
                    }
                }
            }
        }

        private void hit(LivingEntity enemy) {
            Combat.hit(ctx(), owner, enemy, damage);
            hits++;
            Location body = Fx.body(enemy);
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                // Flung aside as the runner zips past.
                Vector offset = body.toVector().subtract(start.toVector()).setY(0);
                Vector side = new Vector(-direction.getZ(), 0, direction.getX());
                if (offset.dot(side) < 0) {
                    side.multiply(-1);
                }
                Motion.push(enemy, side.multiply(SHOVE_SIDE).add(direction.clone().multiply(0.15)).setY(SHOVE_UP),
                        MAX_SPEED);
            }
            World world = body.getWorld();
            Vfx.dot(body, Particle.FLASH, PALETTE.core(), false);
            world.spawnParticle(Particle.ELECTRIC_SPARK, body, Vfx.n(ctx(), 12), 0.25, 0.35, 0.25, 0.2);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 8), 0.25, 0.35, 0.25, 0, HOT);
            world.spawnParticle(Particle.SWEEP_ATTACK, body, 1, 0, 0, 0, 0);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.6f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.7f, 1.3f + Fx.jitter(0.1f));
            if (topSpeed) {
                world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 8), 0.2, 0.3, 0.2, 0, RED_DUST);
            }
        }

        /** The burst at the start: an afterimage left behind, a ring of sparks across the path and a crack. */
        private void launchFx() {
            Location feet = start.clone();
            afterimage(feet, start.getYaw(), HOT);
            Location chest = feet.clone().add(0, owner.getHeight() * 0.55, 0);
            World world = feet.getWorld();
            Vfx.dot(chest, Particle.FLASH, PALETTE.body(), false);
            world.spawnParticle(Particle.ELECTRIC_SPARK, chest, Vfx.n(ctx(), 10), 0.3, 0.4, 0.3, 0.15);
            crossRing(chest, direction, 0.7, HOT);
            world.spawnParticle(Particle.CLOUD, feet.clone().add(0, 0.1, 0), Vfx.n(ctx(), 4), 0.15, 0.02, 0.15,
                    0.04);
            bolt(feet.clone().add(0, 0.35, 0), direction, distance);
            Fx.sound(feet, Sound.ENTITY_BREEZE_WIND_BURST, 0.8f, 1.7f + Fx.jitter(0.1f));
            Fx.sound(feet, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.5f, 1.9f + Fx.jitter(0.08f));
            Fx.sound(feet, Sound.BLOCK_COPPER_BULB_TURN_ON, 0.7f, 1.6f + Fx.jitter(0.1f));
            if (topSpeed || last) {
                Fx.sound(feet, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.35f, 1.8f + Fx.jitter(0.1f));
            }
        }

        /** Speed streaks racing after the runner while it dashes. */
        private void streak(long tick) {
            Location at = owner.getLocation();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                Location from = at.clone().add(random.nextDouble(-0.3, 0.3), random.nextDouble(0.1, 1.7),
                        random.nextDouble(-0.3, 0.3));
                Vfx.trail(from, from.clone().subtract(direction.clone().multiply(1.6 + random.nextDouble())),
                        i % 2 == 0 ? PALETTE.core() : PALETTE.body(), 4 + random.nextInt(3), false);
            }
            Vfx.dot(at.clone().add(0, 0.15, 0), Particle.ELECTRIC_SPARK, null, false);
            if (tick % 2 == 0) {
                Vfx.dot(at.clone().add(0, 0.05, 0), Particle.DUST, RED_DUST, false);
            }
        }

        /** The stop: a skid of sparks and, for the last stored dash, a ring of lightning. */
        private void finishFx() {
            Location feet = owner.getLocation();
            World world = feet.getWorld();
            world.spawnParticle(Particle.ELECTRIC_SPARK, feet.clone().add(0, 0.1, 0), Vfx.n(ctx(), 8), 0.3, 0.05,
                    0.3, 0.12);
            if (last) {
                Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 1.0, 18, 0, Particle.DUST_COLOR_TRANSITION, HOT, false);
                Vfx.ring(ctx(), feet.clone().add(0, 0.15, 0), 0.6, 12, 0.3, Particle.DUST, RED_DUST, false);
                Fx.sound(feet, Sound.BLOCK_BEACON_DEACTIVATE, 0.4f, 2.0f);
            }
            if (hits > 1 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚡ 질주 <white>" + hits + "명 적중!</white></" + COLOR_TAG
                        + ">"));
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: light speed

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        new LightSpeed(owner).begin();
        return ActivationResult.success();
    }

    /** One recorded step of the runner's path. */
    private record Step(double x, double y, double z, float yaw, boolean moving) {
    }

    /**
     * Light speed: extreme speed, a ground lightning trail and afterimages that replay the runner's path a few ticks
     * later. The runner and each afterimage strike the enemies they brush past while moving; the same enemy is struck at
     * most once every {@link #STRIKE_SPACING} ticks and at most the configured number of times in total.
     */
    private final class LightSpeed {

        private final Player owner;
        private final World world;
        private final long duration;
        private final int echoCount;
        private final List<Step> path = new ArrayList<>();
        private final List<Echo> echoes = new ArrayList<>();
        private final Map<UUID, Integer> hitCount = new HashMap<>();
        private final Map<UUID, Long> lastStruck = new HashMap<>();
        private final double damage;
        private final double radius;
        private final int maxHits;
        private int totalHits;

        LightSpeed(Player owner) {
            this.owner = owner;
            this.world = owner.getWorld();
            this.duration = Math.max(1, params().ticks("ult_duration"));
            this.echoCount = Math.clamp(params().integer("ult_echoes"), 0, MAX_ECHOES);
            this.damage = params().num("ult_damage");
            this.radius = Math.max(0, params().num("ult_radius"));
            this.maxHits = Math.max(0, params().integer("ult_max_hits"));
        }

        void begin() {
            double speed = Math.max(0, params().num("ult_speed")) / 100.0;
            if (speed > 0) {
                ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1,
                        duration);
            }
            ctx().addModifier(owner, owner, Attribute.STEP_HEIGHT, STEP_BONUS, Operation.ADD_NUMBER, duration);
            ctx().addModifier(owner, owner, Attribute.FALL_DAMAGE_MULTIPLIER, -1.0, Operation.MULTIPLY_SCALAR_1,
                    duration + FALL_GRACE);
            Location feet = owner.getLocation();
            record(feet, false);
            ItemStack head = headOf(owner);
            for (int i = 0; i < echoCount; i++) {
                if (!roomFor(2)) {
                    break;
                }
                echoes.add(new Echo(i, feet, head));
            }
            strikeFx(feet);
            Loop.repeat(ctx(), owner, 1, 1, duration + MERGE_TICKS, this::step);
        }

        private boolean step(long tick) {
            if (!owner.getWorld().equals(world)) {
                end();
                return false;
            }
            if (tick >= duration) {
                return merge(tick - duration);
            }
            Location feet = owner.getLocation();
            Step previous = path.getLast();
            double moved = Math.hypot(feet.getX() - previous.x(), feet.getZ() - previous.z());
            boolean moving = moved >= STRIKE_SPEED && moved < MAX_STEP * 4;
            record(feet, moving);
            trail(previous, feet, tick, moving);
            if (moving) {
                strikeAround(feet, PALETTE.body(), tick);
            }
            for (Echo echo : echoes) {
                Step at = stepAt(path.size() - 1 - echo.delay);
                Step before = stepAt(path.size() - 2 - echo.delay);
                echo.follow(at);
                if (at.moving() && Math.hypot(at.x() - before.x(), at.z() - before.z()) >= STRIKE_SPEED) {
                    strikeAround(new Location(world, at.x(), at.y(), at.z()), echo.color, tick);
                }
            }
            if (tick % 10 == 0) {
                Fx.sound(feet, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.25f, 1.9f + Fx.jitter(0.1f));
            }
            if (tick % 5 == 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⚡ 광속</b></" + COLOR_TAG + "> <gray>"
                        + Fx.seconds(duration - tick) + "초</gray>" + (totalHits > 0 ? " <white>" + totalHits
                        + "타</white>" : "")));
            }
            return true;
        }

        private void record(Location feet, boolean moving) {
            path.add(new Step(feet.getX(), feet.getY(), feet.getZ(), feet.getYaw(), moving));
            int keep = (MAX_ECHOES + 1) * ECHO_SPACING + 4;
            if (path.size() > keep * 2) {
                path.subList(0, path.size() - keep).clear();
            }
        }

        private Step stepAt(int index) {
            return path.get(Math.clamp(index, 0, path.size() - 1));
        }

        /** Strikes every enemy around {@code feet} that may be struck again. */
        private void strikeAround(Location feet, Color color, long tick) {
            if (maxHits <= 0) {
                return;
            }
            Location waist = feet.clone().add(0, 0.9, 0);
            long now = ctx().currentTick();
            for (LivingEntity enemy : ctx().enemiesNear(owner, waist, radius)) {
                UUID id = enemy.getUniqueId();
                if (hitCount.getOrDefault(id, 0) >= maxHits || now - lastStruck.getOrDefault(id, Long.MIN_VALUE / 2)
                        < STRIKE_SPACING) {
                    continue;
                }
                hitCount.merge(id, 1, Integer::sum);
                lastStruck.put(id, now);
                Combat.hit(ctx(), owner, enemy, damage);
                totalHits++;
                Location body = Fx.body(enemy);
                World w = body.getWorld();
                Vfx.dot(body, Particle.FLASH, color, false);
                w.spawnParticle(Particle.ELECTRIC_SPARK, body, Vfx.n(ctx(), 8), 0.25, 0.35, 0.25, 0.15);
                w.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 6), 0.25, 0.35, 0.25, 0,
                        new Particle.DustOptions(color, 1.0f));
                w.spawnParticle(Particle.SWEEP_ATTACK, body, 1, 0, 0, 0, 0);
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.7f + Fx.jitter(0.15f));
                Fx.sound(body, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 0.5f, 1.6f + 0.05f * (tick % 6));
            }
        }

        /** A ground lightning line from the last step to this one, sparks, and now and then a bolt display. */
        private void trail(Step previous, Location feet, long tick, boolean moving) {
            Location from = new Location(world, previous.x(), previous.y() + 0.1, previous.z());
            Location to = feet.clone().add(0, 0.1, 0);
            if (moving) {
                Vfx.line(ctx(), from, to, 3, Particle.DUST_COLOR_TRANSITION, HOT, true);
                Vfx.dot(to, Particle.ELECTRIC_SPARK, null, true);
                if (tick % TRAIL_BOLT_PERIOD == 0) {
                    Vector delta = to.toVector().subtract(from.toVector());
                    if (delta.lengthSquared() > 0.04) {
                        bolt(from.clone().add(0, 0.15, 0), delta.clone().normalize(), Math.min(3, delta.length() * 3));
                    }
                }
            } else if (tick % 3 == 0) {
                Vfx.cloud(ctx(), Fx.body(owner), Particle.ELECTRIC_SPARK, 3, 0.3, 0.05, null, true);
            }
        }

        /** The ultimate begins: a bolt strikes the runner, a shock ring spreads and the runner crackles. */
        private void strikeFx(Location feet) {
            Location top = feet.clone().add(Fx.jitter(1.5f), STRIKE_HEIGHT, Fx.jitter(1.5f));
            Location bottom = feet.clone().add(0, 0.2, 0);
            Location current = top;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 1; i <= STRIKE_SEGMENTS && roomFor(2); i++) {
                double t = (double) i / STRIKE_SEGMENTS;
                Location next = top.clone().add(bottom.toVector().subtract(top.toVector()).multiply(t));
                if (i < STRIKE_SEGMENTS) {
                    next.add(random.nextDouble(-0.7, 0.7), 0, random.nextDouble(-0.7, 0.7));
                }
                beam(current, next, 0.12f, 6, true);
                current = next;
            }
            World w = feet.getWorld();
            Vfx.dot(Fx.body(owner), Particle.FLASH, PALETTE.core(), true);
            w.spawnParticle(Particle.ELECTRIC_SPARK, Fx.body(owner), Vfx.n(ctx(), 40), 0.5, 0.8, 0.5, 0.4, null,
                    true);
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.4, 5, 7, Particle.DUST_COLOR_TRANSITION, HOT,
                    true);
            Vfx.helix(ctx(), feet, 0.7, 2.2, 2, 2, 16, 0, Particle.DUST_COLOR_TRANSITION, HOT, true);
            Fx.sound(feet, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.6f);
            Fx.sound(feet, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 1.8f);
            Fx.sound(feet, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 2.0f);
        }

        /** Time is up: the afterimages rush back into the runner and vanish with a flash. */
        private boolean merge(long run) {
            Location feet = owner.getLocation();
            double t = Math.min(1.0, (run + 1.0) / MERGE_TICKS);
            for (Echo echo : echoes) {
                echo.rush(feet, t);
            }
            if (run + 1 >= MERGE_TICKS) {
                end();
                return false;
            }
            return true;
        }

        private void end() {
            for (Echo echo : echoes) {
                echo.vanish();
            }
            echoes.clear();
            Location feet = owner.getLocation();
            Vfx.dot(Fx.body(owner), Particle.FLASH, PALETTE.body(), true);
            Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 1.2, 20, 0, Particle.DUST_COLOR_TRANSITION, COOLING, true);
            Fx.sound(feet, Sound.BLOCK_BEACON_DEACTIVATE, 0.9f, 1.8f);
            Fx.sound(feet, Sound.ENTITY_BREEZE_WIND_BURST, 0.6f, 1.4f);
            if (totalHits > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⚡ 광속</b></" + COLOR_TAG + "> <white>" + totalHits
                        + "타 적중!</white>"));
            }
        }

        /**
         * An afterimage: the owner's head on a translucent glass body in the echo's colour, replaying the path
         * {@link #delay} ticks late, with streaks of its colour where its legs would be.
         */
        private final class Echo {
            private final int delay;
            private final Color color;
            private final float fullSize;
            private final ItemDisplay head;
            private final BlockDisplay torso;
            /** Grows from nothing to full size over its first ticks. */
            private float growth;
            /** The way the afterimage faces (a Minecraft yaw), carried by its transformations, never the entities. */
            private float yaw;

            Echo(int index, Location feet, ItemStack headItem) {
                this.delay = (index + 1) * ECHO_SPACING;
                this.color = ECHO_COLORS[index % ECHO_COLORS.length];
                this.fullSize = 1f - 0.08f * index;
                this.yaw = feet.getYaw();
                long life = duration + MERGE_TICKS + 10;
                head = Displays.item(ctx(), owner, feet, headItem, headPose(), life);
                head.setTeleportDuration(1);
                displays.add(head);
                torso = Displays.block(ctx(), owner, feet, ECHO_GLASS[index % ECHO_GLASS.length].createBlockData(),
                        torsoPose(), life);
                torso.setTeleportDuration(1);
                displays.add(torso);
            }

            /** The head on top, turned to {@link #yaw}. */
            Transformation headPose() {
                float size = 0.55f * fullSize * Math.max(0.01f, growth);
                return Displays.centered(0, 1.55f, 0, size, size, size, yaw, 0, 0);
            }

            /** The glass body under it, turned the same way. */
            Transformation torsoPose() {
                float size = fullSize * Math.max(0.01f, growth);
                return Displays.transform(0, 1.0f, 0, 0.5f * size, 0.72f * size, 0.26f * size, yaw, 0, 0);
            }

            void follow(Step step) {
                Location at = new Location(world, step.x(), step.y(), step.z());
                growth = Math.min(1f, growth + 0.25f);
                yaw = step.yaw();
                place(at);
                if (step.moving()) {
                    Particle.DustOptions dust = new Particle.DustOptions(color, 0.8f);
                    Vfx.dot(at.clone().add(Fx.jitter(0.15f), 0.35, Fx.jitter(0.15f)), Particle.DUST, dust, false);
                    Vfx.dot(at.clone().add(Fx.jitter(0.15f), 0.7, Fx.jitter(0.15f)), Particle.DUST, dust, false);
                }
            }

            void rush(Location feet, double t) {
                Location from = head.isValid() ? head.getLocation() : feet;
                Location at = from.clone().add(feet.toVector().subtract(from.toVector()).multiply(t));
                yaw = feet.getYaw();
                place(at);
                Vfx.trail(at.clone().add(0, 1.0, 0), feet.clone().add(0, 1.0, 0), color, 4, false);
            }

            private void place(Location at) {
                Displays.moveTo(head, at);
                Displays.moveTo(torso, at);
                Displays.animate(head, headPose(), 1);
                Displays.animate(torso, torsoPose(), 1);
            }

            void vanish() {
                for (Display part : new Display[] {head, torso}) {
                    displays.remove(part);
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 3);
                    }
                }
            }
        }
    }

    /** The owner's own head (its skin), for the afterimages. */
    private static ItemStack headOf(Player owner) {
        ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
        head.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(owner.getPlayerProfile()));
        return head;
    }

    // ------------------------------------------------------------------ shared visuals

    /** A particle silhouette of the runner at {@code feet}: legs, body, arms and head, in the given dust. */
    private void afterimage(Location feet, float yaw, Particle.DustTransition dust) {
        double rad = Math.toRadians(yaw);
        Vector forward = new Vector(-Math.sin(rad), 0, Math.cos(rad));
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        World world = feet.getWorld();
        double[][] points = {
                {-0.12, 0.15}, {-0.12, 0.45}, {0.12, 0.15}, {0.12, 0.45}, {0, 0.8}, {0, 1.05}, {0, 1.3},
                {-0.32, 1.0}, {-0.3, 1.25}, {0.32, 1.0}, {0.3, 1.25}, {0, 1.62}, {-0.14, 1.55}, {0.14, 1.55},
                {0, 1.78},
        };
        int count = Math.max(4, (int) Math.round(points.length * ctx().effectScale()));
        for (int i = 0; i < Math.min(count, points.length); i++) {
            double[] p = points[i];
            Location at = feet.clone().add(side.clone().multiply(p[0])).add(0, p[1], 0);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0, dust);
        }
    }

    /** A ring standing across {@code direction} (the plane the runner bursts through). */
    private void crossRing(Location center, Vector direction, double radius, Particle.DustTransition dust) {
        Vector forward = Motion.horizontal(direction);
        if (forward.lengthSquared() < 1.0E-6) {
            return;
        }
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        int points = Vfx.n(ctx(), 16);
        World world = center.getWorld();
        for (int i = 0; i < points; i++) {
            double angle = i * TAU / points;
            Location at = center.clone().add(side.clone().multiply(Math.cos(angle) * radius))
                    .add(0, Math.sin(angle) * radius, 0);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0, dust);
        }
    }

    /** A zigzag lightning bolt along {@code direction} for {@code length} blocks, of thin glowing bars. */
    private void bolt(Location from, Vector direction, double length) {
        if (length < 0.5) {
            return;
        }
        Vector side = new Vector(-direction.getZ(), 0, direction.getX());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location current = from.clone();
        for (int i = 1; i <= BOLT_SEGMENTS; i++) {
            if (!roomFor(2)) {
                return;
            }
            double t = (double) i / BOLT_SEGMENTS;
            Location next = from.clone().add(direction.clone().multiply(length * t));
            if (i < BOLT_SEGMENTS) {
                next.add(side.clone().multiply(random.nextDouble(-0.35, 0.35))).add(0, random.nextDouble(-0.1, 0.25),
                        0);
            }
            beam(current, next, 0.06f, BOLT_LIFE, false);
            current = next;
        }
    }

    /** One bar of a bolt: a white core in a yellow glass sleeve, flaring for a moment then thinning out. */
    private void beam(Location from, Location to, float width, long life, boolean force) {
        Vector delta = to.toVector().subtract(from.toVector());
        if (delta.lengthSquared() < 1.0E-4) {
            return;
        }
        Player owner = player();
        BlockDisplay core = Displays.block(ctx(), owner, from, BOLT_CORE.createBlockData(), beamPose(delta, width),
                life);
        BlockDisplay glow = Displays.block(ctx(), owner, from, BOLT_GLOW.createBlockData(),
                beamPose(delta, width * 2.6f), life);
        displays.add(core);
        displays.add(glow);
        if (force) {
            core.setViewRange(2.5f);
            glow.setViewRange(2.5f);
        }
        long fadeAt = Math.max(1, life / 2);
        ctx().runLater(owner, fadeAt, () -> {
            Displays.animate(core, beamPose(delta, 0.001f), (int) (life - fadeAt));
            Displays.animate(glow, beamPose(delta, 0.001f), (int) (life - fadeAt));
        });
    }

    /** A thin box of the given width from the block display's origin along {@code delta}. */
    private static Transformation beamPose(Vector delta, float width) {
        Vector3f direction = new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ());
        float length = direction.length();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction.normalize());
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
        return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
    }

    /** True if {@code count} more displays fit in {@link #MAX_DISPLAYS}. */
    private boolean roomFor(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }
}
