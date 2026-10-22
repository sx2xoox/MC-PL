package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 수류술사 — water in motion. Burning never sticks to the owner, and while wet (in water, in the rain, or soaked by
 * its own water skills) it runs faster and slowly heals. The active is a channelled high-pressure water jet that
 * follows the aim and keeps shoving the first enemy it hits; the ultimate rolls a huge wave forward that sweeps every
 * enemy in its path along and smashes them down where it breaks.
 *
 * <p>Look (foam white, sea blue, deep ocean): the jet is a wobbling translucent blue tube with a white pressure core,
 * a double helix of spray wound around it, droplets falling off and a splash crown where it hits. The wave is a
 * row of glass water columns (deep blue body, light blue face, white curling crest) rising out of the ground, with
 * foam and spray along the crest and a wet trail behind; it crashes in a burst of spray and a foam ring.
 */
public final class HydroAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("hydro", "수류술사", Material.HEART_OF_THE_SEA)
            .color(TextColor.color(0x2EA8FF))
            .difficulty(2)
            .passive("흐르는 몸",
                    "몸에 붙은 불이 곧바로 꺼져 화상 피해를 받지 않습니다. 물속이나 빗속에 있을 때, 그리고 물 스킬을 쓴 뒤 "
                            + "{soak_duration}초 동안 젖은 몸이 되어 이동 속도가 {wet_speed}% 빨라지고 "
                            + "{wet_interval}초마다 체력을 {wet_heal} 회복합니다.")
            .active("물대포",
                    "{beam_duration}초 동안 시선을 따라 움직이는 고압 물줄기({beam_range}블록)를 뿜습니다. 물줄기에 맞은 "
                            + "첫 적은 {beam_interval}초마다 {beam_damage} 피해를 입고 강하게 밀려납니다.",
                    9)
            .ultimate("해일",
                    "바라보는 방향으로 폭 {wave_width}블록의 거대한 파도를 일으켜 {wave_distance}블록 밀어 보냅니다. "
                            + "휩쓸린 적은 {wave_damage} 피해를 입고 파도에 떠밀려 가다가, 파도가 부서질 때 "
                            + "{crash_damage} 피해를 더 입습니다.")
            .param("soak_duration", 4)
            .param("wet_speed", 20)
            .param("wet_interval", 2)
            .param("wet_heal", 1)
            .param("beam_duration", 1.2)
            .param("beam_range", 14)
            .param("beam_interval", 0.2)
            .param("beam_damage", 0.8)
            .param("beam_push", 0.55)
            .param("wave_width", 9)
            .param("wave_distance", 16)
            .param("wave_speed", 12)
            .param("wave_damage", 4)
            .param("crash_damage", 3)
            .build();

    private static final String COLOR_TAG = "#2EA8FF";
    /** Foam white, sea blue, deep ocean. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE6FBFF, 0x2EA8FF, 0x0B3D91);
    private static final Color AQUA = Color.fromRGB(0x7FE7FF);
    private static final Particle.DustOptions FOAM = new Particle.DustOptions(PALETTE.core(), 1.2f);
    private static final Particle.DustOptions SEA = PALETTE.dust(1.1f);
    private static final Particle.DustOptions AQUA_DUST = new Particle.DustOptions(AQUA, 0.8f);
    private static final Particle.DustTransition SPRAY = PALETTE.glow(0.9f);
    private static final Particle.DustTransition DEEP = PALETTE.fade(1.4f);
    private static final BlockData JET_SHEATH = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
    private static final BlockData JET_CORE = Material.WHITE_STAINED_GLASS.createBlockData();
    private static final BlockData WAVE_BODY = Material.BLUE_STAINED_GLASS.createBlockData();
    private static final BlockData WAVE_FACE = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
    private static final BlockData WAVE_CREST = Material.WHITE_STAINED_GLASS.createBlockData();

    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.0;
    /** Display entities this ability keeps alive at most at once (extras fall back to particles). */
    private static final int MAX_DISPLAYS = 24;

    // passive
    private static final long PASSIVE_PERIOD = 5;
    private static final long STEAM_COOLDOWN = 10;
    private static final long DRIP_PERIOD = 10;

    // active: water cannon
    /** How much the jet's hitbox test inflates enemy hitboxes, for forgiving aim while tracking. */
    private static final double BEAM_HITBOX = 0.35;
    /** Upward part of each push: keeps the target just off the ground so friction does not eat the shove. */
    private static final double BEAM_LIFT = 0.18;
    private static final float SHEATH_WIDTH = 0.34f;
    private static final float CORE_WIDTH = 0.14f;
    private static final float JET_ROLL = 37f;
    private static final int HELIX_MAX_POINTS = 16;
    private static final long JET_FADE_TICKS = 4;
    private static final long JET_AFTERGLOW_TICKS = 12;

    // ultimate: tidal wave
    /** Target spacing of the wave's water columns across its width. */
    private static final double COLUMN_SPACING = 1.8;
    private static final int MIN_COLUMNS = 3;
    private static final int MAX_COLUMNS = 6;
    private static final double WAVE_START = 0.8;
    private static final float WAVE_HEIGHT = 3.2f;
    private static final float WAVE_DEPTH = 2.0f;
    private static final long RISE_TICKS = 6;
    private static final long POSE_PERIOD = 2;
    private static final long CRASH_TICKS = 6;
    /** How far below the owner's feet the wave may start, and how far a column may step up or down per block. */
    private static final int START_DEPTH = 3;
    private static final int MAX_STEP_DOWN = 3;
    /** Where a swept enemy rides: this far ahead of the wave front, this high above the ground. */
    private static final double RIDE_AHEAD = 0.7;
    private static final double RIDE_HEIGHT = 0.5;
    private static final double RIDE_GAIN = 0.35;
    /** A rider that falls this far behind its column (snagged on something) is let go. */
    private static final double RIDE_LOST = 3.0;
    private static final double CRASH_PUSH = 0.4;
    private static final double CRASH_LIFT = 0.3;
    /** Roughly how far the crash shove carries a rider; it is only shoved forward if it lands inside the arena. */
    private static final double CRASH_TRAVEL = 3.0;
    private static final long PUDDLE_TICKS = 20;
    /**
     * The wave stops this far short of the arena border, so its riders (carried ahead of the front, then shoved on
     * the crash) are never washed out of the arena.
     */
    private static final double BORDER_MARGIN = 2.5;

    // passive state
    private long soakedUntil = -1;
    private long nextHeal = -1;
    private long lastSteam = -STEAM_COOLDOWN;
    private @Nullable NamespacedKey speedKey;
    /** Display entities spawned by this instance that are still alive. */
    private int liveDisplays;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: flowing body

    @Override
    public void onRevoke() {
        speedKey = null;
    }

    @Override
    public void onTick(long tick) {
        if (tick % PASSIVE_PERIOD != 0) {
            return;
        }
        Player owner = player();
        long now = ctx().currentTick();
        if (owner.getFireTicks() > 0) {
            // Fire never sticks to flowing water.
            owner.setFireTicks(0);
            steam();
        }
        boolean wet = isWet(owner, now);
        if (wet && speedKey == null) {
            double bonus = params().num("wet_speed") / 100.0;
            if (bonus > 0) {
                speedKey = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, bonus,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, 0);
            }
        } else if (!wet && speedKey != null) {
            ctx().removeModifier(owner, speedKey);
            speedKey = null;
        }
        if (!wet) {
            nextHeal = -1;
            return;
        }
        long interval = Math.max(1, params().ticks("wet_interval"));
        if (nextHeal < 0) {
            nextHeal = now + interval;
        } else if (now >= nextHeal) {
            nextHeal = now + interval;
            if (owner.getHealth() < Combat.maxHealth(owner)) {
                ctx().heal(owner, params().num("wet_heal"));
                healFx(owner);
            }
        }
        if (tick % DRIP_PERIOD == 0) {
            drip(owner);
        }
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getCause() != DamageCause.FIRE_TICK) {
            return;
        }
        event.setCancelled(true);
        player().setFireTicks(0);
        steam();
    }

    /** In water, out in the rain, or still soaked from a water skill. */
    private boolean isWet(Player owner, long now) {
        if (now < soakedUntil || owner.isInWater()) {
            return true;
        }
        // Checked in this order: rain needs a storm first, and only then asks whether the owner stands in it.
        return owner.getWorld().hasStorm() && owner.isInRain();
    }

    /** The owner is drenched by its own water skill. */
    private void soak() {
        soakedUntil = Math.max(soakedUntil, ctx().currentTick() + params().ticks("soak_duration"));
    }

    /** A hiss of steam where fire touched the owner. */
    private void steam() {
        long now = ctx().currentTick();
        if (now - lastSteam < STEAM_COOLDOWN) {
            return;
        }
        lastSteam = now;
        Location body = Fx.body(player());
        World world = body.getWorld();
        world.spawnParticle(Particle.CLOUD, body, Vfx.n(ctx(), 6), 0.25, 0.4, 0.25, 0.03);
        world.spawnParticle(Particle.WHITE_SMOKE, body, Vfx.n(ctx(), 5), 0.25, 0.45, 0.25, 0.02);
        world.spawnParticle(Particle.SPLASH, body, Vfx.n(ctx(), 8), 0.3, 0.4, 0.3, 0.1);
        Fx.sound(body, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.5f + Fx.jitter(0.1f));
    }

    /** Water drips off the soaked owner. */
    private void drip(Player owner) {
        Location at = owner.getLocation().add(0, owner.getHeight() * 0.7, 0);
        World world = at.getWorld();
        world.spawnParticle(Particle.DRIPPING_WATER, at, Vfx.n(ctx(), 3), 0.25, 0.35, 0.25, 0);
        world.spawnParticle(Particle.FALLING_WATER, at, Vfx.n(ctx(), 2), 0.2, 0.2, 0.2, 0);
    }

    /** Healing water: a ring of spray rising up the body. */
    private void healFx(Player owner) {
        Location feet = owner.getLocation().add(0, 0.15, 0);
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        Vfx.ring(ctx(), feet, 0.55, 10, phase, Particle.DUST_COLOR_TRANSITION, SPRAY, false);
        Loop.repeat(ctx(), owner, 1, 1, 5, run -> {
            Location at = player().getLocation().add(0, 0.3 + run * 0.35, 0);
            Vfx.ring(ctx(), at, 0.45 - run * 0.05, 6, phase + run * 0.6, Particle.DUST, AQUA_DUST, false);
            return true;
        });
        owner.getWorld().spawnParticle(Particle.SPLASH, Fx.body(owner), Vfx.n(ctx(), 6), 0.25, 0.4, 0.25, 0.05);
        Fx.sound(feet, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 0.6f, 1.4f + Fx.jitter(0.15f));
    }

    // ------------------------------------------------------------------ active: water cannon

    @Override
    public ActivationResult onActive() {
        long duration = Math.max(1, params().ticks("beam_duration"));
        Cannon cannon = new Cannon(duration);
        cannon.start();
        Loop.repeat(ctx(), player(), 0, 1, duration, cannon, cannon::finish);
        soak();
        return ActivationResult.success();
    }

    /** Where the jet leaves the owner: just in front of the right hand. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        Vector right = new Vector(-direction.getZ(), 0, direction.getX());
        if (right.lengthSquared() > 1.0E-6) {
            right.normalize().multiply(0.22);
        }
        return eye.add(direction.multiply(0.65)).add(right).subtract(0, 0.28, 0);
    }

    /** What the jet hit this tick: where it ends and the enemy it splashes on, if any. */
    private record JetHit(Location end, @Nullable LivingEntity target, boolean blocked) {
    }

    /**
     * The water cannon: every tick the jet is re-aimed along the owner's view, stops at the first enemy or block, and
     * every {@code beam_interval} the enemy it splashes on takes a hit and is shoved along the jet.
     */
    private final class Cannon implements LongPredicate {

        private final long duration;
        private final long interval;
        private final double range;
        private final double damage;
        private final double push;
        private final Set<UUID> struck = new HashSet<>();
        private @Nullable BlockDisplay sheath;
        private @Nullable BlockDisplay core;
        private @Nullable Location lastHand;
        private @Nullable Location lastEnd;

        Cannon(long duration) {
            this.duration = duration;
            this.interval = Math.max(1, params().ticks("beam_interval"));
            this.range = Math.max(1, params().num("beam_range"));
            this.damage = params().num("beam_damage");
            this.push = params().num("beam_push");
        }

        void start() {
            Player owner = player();
            Location hand = hand(owner);
            Vector direction = owner.getEyeLocation().getDirection();
            double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            aimRing(hand, direction, 0.42, 12, phase, Particle.SPLASH, null);
            aimRing(hand, direction, 0.3, 10, phase + 0.3, Particle.DUST_COLOR_TRANSITION, SPRAY);
            hand.getWorld().spawnParticle(Particle.CLOUD, hand, Vfx.n(ctx(), 4), 0.1, 0.1, 0.1, 0.04);
            Fx.sound(hand, Sound.ITEM_BUCKET_EMPTY, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(hand, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.7f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(hand, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.6f, 1.5f + Fx.jitter(0.05f));
            long lifetime = duration + JET_FADE_TICKS + 2;
            if (reserveDisplays(2, lifetime)) {
                Vector stub = direction.clone().multiply(0.3);
                sheath = Displays.block(ctx(), owner, flat(hand), JET_SHEATH, beamPose(stub, SHEATH_WIDTH, 0),
                        lifetime);
                core = Displays.block(ctx(), owner, flat(hand), JET_CORE, beamPose(stub, CORE_WIDTH, 45), lifetime);
                sheath.setTeleportDuration(1);
                core.setTeleportDuration(1);
            }
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            Location eye = owner.getEyeLocation();
            Vector direction = eye.getDirection();
            if (direction.lengthSquared() < 1.0E-8) {
                return true;
            }
            direction.normalize();
            JetHit hit = trace(owner, eye, direction);
            Location hand = hand(owner);
            draw(hand, hit, tick);
            if (tick % interval == 0 && hit.target() != null) {
                strike(hit.target(), direction);
            }
            if (tick % 4 == 0) {
                Fx.sound(hand, Sound.ENTITY_PLAYER_SWIM, 0.5f, 1.5f + Fx.jitter(0.15f));
                Fx.sound(hit.end(), Sound.BLOCK_WATER_AMBIENT, 0.6f, 1.8f + Fx.jitter(0.1f));
            }
            lastHand = hand;
            lastEnd = hit.end();
            return true;
        }

        /** The first enemy (inflated hitbox) or block along the view ray, within range. */
        private JetHit trace(Player owner, Location eye, Vector direction) {
            Vector start = eye.toVector();
            double reach = range;
            boolean blocked = false;
            RayTraceResult wall = eye.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            if (wall != null) {
                reach = wall.getHitPosition().distance(start);
                blocked = true;
            }
            LivingEntity best = null;
            double bestDistance = reach;
            Location middle = eye.clone().add(direction.clone().multiply(reach / 2));
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, reach / 2 + 1.5)) {
                BoundingBox box = enemy.getBoundingBox().expand(BEAM_HITBOX);
                double distance;
                if (box.contains(start)) {
                    distance = 0;
                } else {
                    RayTraceResult result = box.rayTrace(start, direction, bestDistance);
                    if (result == null) {
                        continue;
                    }
                    distance = result.getHitPosition().distance(start);
                }
                if (distance <= bestDistance) {
                    best = enemy;
                    bestDistance = distance;
                }
            }
            Location end = eye.clone().add(direction.clone().multiply(Math.max(0.6, bestDistance)));
            return new JetHit(end, best, best != null || blocked);
        }

        private void strike(LivingEntity target, Vector direction) {
            Player owner = player();
            Combat.hit(ctx(), owner, target, damage);
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return;
            }
            struck.add(target.getUniqueId());
            if (push > 0) {
                Vector shove = Motion.horizontal(direction);
                if (shove.lengthSquared() < 1.0E-6) {
                    shove = direction.clone();
                }
                Motion.push(target, shove.multiply(push).setY(BEAM_LIFT), MAX_SPEED);
            }
            Location body = Fx.body(target);
            World world = body.getWorld();
            world.spawnParticle(Particle.SPLASH, body, Vfx.n(ctx(), 14), 0.3, 0.45, 0.3, 0.2);
            world.spawnParticle(Particle.BUBBLE_POP, body, Vfx.n(ctx(), 5), 0.3, 0.4, 0.3, 0.05);
            Vfx.dot(body, Particle.FLASH, AQUA, false);
            Fx.sound(body, Sound.ENTITY_GENERIC_SPLASH, 0.7f, 1.3f + Fx.jitter(0.15f));
            Fx.sound(body, Sound.ENTITY_PLAYER_HURT_DROWN, 0.4f, 1.2f + Fx.jitter(0.1f));
        }

        /** Re-poses the jet tube and draws the spray, helix, drips and the splash crown at the end. */
        private void draw(Location hand, JetHit hit, long tick) {
            Location end = hit.end();
            Vector delta = end.toVector().subtract(hand.toVector());
            double length = delta.length();
            if (length < 0.2) {
                return;
            }
            float wobble = 0.05f * (float) Math.sin(tick * 1.7);
            float roll = JET_ROLL * tick;
            pose(sheath, hand, beamPose(delta, SHEATH_WIDTH + wobble, roll));
            pose(core, hand, beamPose(delta, CORE_WIDTH - wobble * 0.4f, 45 - roll));

            World world = hand.getWorld();
            Vector axis = delta.clone().multiply(1 / length);
            helix(hand, axis, length, tick * 0.7);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int spray = Vfx.n(ctx(), 3);
            for (int i = 0; i < spray; i++) {
                Location at = hand.clone().add(axis.clone().multiply(random.nextDouble() * length));
                world.spawnParticle(Particle.SPLASH, at, 1, 0.08, 0.08, 0.08, 0.05);
            }
            if (tick % 2 == 0) {
                Location drop = hand.clone().add(axis.clone().multiply(random.nextDouble() * length));
                Vfx.dot(drop, Particle.FALLING_WATER, null, false);
            }
            Vfx.trail(hand, end, tick % 2 == 0 ? AQUA : PALETTE.core(), (int) Math.max(2, Math.min(8, length / 2)),
                    false);
            if (hit.blocked()) {
                world.spawnParticle(Particle.SPLASH, end, Vfx.n(ctx(), 8), 0.2, 0.2, 0.2, 0.25);
                if (tick % 2 == 0) {
                    aimRing(end, axis, 0.45 + 0.1 * Math.sin(tick), 10, tick * 0.5, Particle.DUST, FOAM);
                    world.spawnParticle(Particle.CLOUD, end, 1, 0.1, 0.1, 0.1, 0.02);
                }
            } else {
                world.spawnParticle(Particle.SPLASH, end, Vfx.n(ctx(), 3), 0.15, 0.15, 0.15, 0.1);
            }
        }

        /** Two strands of spray wound around the jet, with blue and white droplets. */
        private void helix(Location from, Vector axis, double length, double phase) {
            Vector u = axis.clone().crossProduct(Math.abs(axis.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0))
                    .normalize();
            Vector v = axis.clone().crossProduct(u);
            int points = Vfx.n(ctx(), (int) Math.min(HELIX_MAX_POINTS, Math.ceil(length * 1.1)));
            World world = from.getWorld();
            for (int strand = 0; strand < 2; strand++) {
                for (int i = 0; i < points; i++) {
                    double t = (i + 0.5) / points;
                    double angle = phase + strand * Math.PI + t * length * 1.3;
                    // The spray flares out towards the target, like a jet losing its pressure.
                    double radius = 0.16 + 0.14 * t;
                    Vector offset = u.clone().multiply(Math.cos(angle) * radius)
                            .add(v.clone().multiply(Math.sin(angle) * radius));
                    Location at = from.clone().add(axis.clone().multiply(t * length)).add(offset);
                    if (strand == 0) {
                        world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, SEA);
                    } else {
                        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0, SPRAY);
                    }
                }
            }
        }

        private void pose(@Nullable BlockDisplay display, Location hand, Transformation transformation) {
            if (display != null && display.isValid()) {
                display.teleport(flat(hand));
                Displays.animate(display, transformation, 1);
            }
        }

        /** The jet runs dry: the tube thins away and the last drops fall where it was. */
        void finish() {
            for (BlockDisplay display : new BlockDisplay[] {sheath, core}) {
                if (display != null && display.isValid()) {
                    Displays.shrinkAway(ctx(), player(), display, (int) JET_FADE_TICKS);
                }
            }
            Location from = lastHand;
            Location to = lastEnd;
            if (from != null && to != null) {
                Vector delta = to.toVector().subtract(from.toVector());
                Loop.repeat(ctx(), player(), 1, 2, JET_AFTERGLOW_TICKS / 2, run -> {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                        Location drop = from.clone().add(delta.clone().multiply(random.nextDouble()));
                        Vfx.dot(drop, Particle.FALLING_WATER, null, false);
                    }
                    if (run == 0) {
                        to.getWorld().spawnParticle(Particle.CLOUD, to, Vfx.n(ctx(), 3), 0.2, 0.2, 0.2, 0.02);
                    }
                    return true;
                });
                Fx.sound(from, Sound.ITEM_BUCKET_FILL, 0.5f, 1.4f + Fx.jitter(0.1f));
            }
            if (!struck.isEmpty() && player().isOnline()) {
                player().sendActionBar(Text.mm("<" + COLOR_TAG + ">물대포 <white>" + struck.size()
                        + "명 적중!</white></" + COLOR_TAG + ">"));
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: tidal wave

    @Override
    public ActivationResult onUltimate() {
        Location feet = player().getLocation();
        Block ground = groundUnder(feet);
        if (ground == null) {
            return ActivationResult.fail("파도를 일으킬 땅이 없습니다");
        }
        Wave wave = new Wave(feet, ground.getY());
        wave.launch();
        soak();
        return ActivationResult.success();
    }

    /** The first solid block at or below {@code feet}, at most {@link #START_DEPTH} blocks down. */
    private static @Nullable Block groundUnder(Location feet) {
        World world = feet.getWorld();
        int top = Math.min(feet.getBlockY(), world.getMaxHeight() - 1);
        int bottom = Math.max(world.getMinHeight(), feet.getBlockY() - START_DEPTH);
        for (int y = top; y >= bottom; y--) {
            Block block = world.getBlockAt(feet.getBlockX(), y, feet.getBlockZ());
            if (!block.isPassable()) {
                return block;
            }
        }
        return null;
    }

    /** One water column of the wave: its offset across the wave, its ground and its three display layers. */
    private static final class Column {
        final double offset;
        final float width;
        final float profile;
        int groundY;
        boolean alive = true;
        @Nullable BlockDisplay body;
        @Nullable BlockDisplay face;
        @Nullable BlockDisplay crest;

        Column(double offset, float width, float profile, int groundY) {
            this.offset = offset;
            this.width = width;
            this.profile = profile;
            this.groundY = groundY;
        }
    }

    /** An enemy swept along by a column. */
    private record Rider(LivingEntity entity, Column column) {
    }

    /**
     * The tidal wave: a row of water columns advancing together along the owner's facing. Each column follows the
     * terrain (one block up, a few down) and breaks on walls and cliffs; enemies its front reaches are hit once and
     * ride along on its face until it breaks.
     */
    private final class Wave implements LongPredicate {

        private final World world;
        private final Location origin;
        private final Vector forward;
        private final float yaw;
        private final Column[] columns;
        private final double distance;
        private final double speed;
        private final double catchRadius;
        private final long totalTicks;
        private final Set<UUID> caught = new HashSet<>();
        private final Map<UUID, Rider> riders = new LinkedHashMap<>();
        private double travelled = WAVE_START;

        Wave(Location feet, int groundY) {
            this.world = feet.getWorld();
            this.forward = Motion.horizontalFacing(feet);
            this.yaw = (float) Math.toDegrees(Math.atan2(-forward.getX(), forward.getZ()));
            this.origin = feet.clone();
            double width = Math.max(1, params().num("wave_width"));
            int count = Math.clamp(Math.round(width / COLUMN_SPACING), MIN_COLUMNS, MAX_COLUMNS);
            double spacing = width / count;
            this.columns = new Column[count];
            for (int i = 0; i < count; i++) {
                double offset = (i - (count - 1) / 2.0) * spacing;
                // The middle of the wave stands taller than its flanks.
                float profile = (float) (0.78 + 0.22 * Math.cos(Math.PI * offset / Math.max(1, width)));
                columns[i] = new Column(offset, (float) spacing + 0.12f, profile, groundY);
            }
            double wanted = Math.max(WAVE_START + 1, params().num("wave_distance"));
            double reach = Spots.arenaReach(ctx(), feet, forward, wanted + BORDER_MARGIN) - BORDER_MARGIN;
            this.distance = Math.max(WAVE_START + 0.5, Math.min(wanted, reach));
            this.speed = Math.max(0.1, params().num("wave_speed") / 20.0);
            this.catchRadius = spacing * 0.5 + 0.45;
            this.totalTicks = (long) Math.ceil((distance - WAVE_START) / speed) + 1;
        }

        void launch() {
            Player owner = player();
            long lifetime = totalTicks + CRASH_TICKS + 6;
            for (Column column : columns) {
                if (!reserveDisplays(3, lifetime)) {
                    break;
                }
                Location at = columnAt(column);
                column.body = spawnLayer(at, WAVE_BODY, bodyPose(column, 0.12f), lifetime);
                column.face = spawnLayer(at, WAVE_FACE, facePose(column, 0.12f, 18), lifetime);
                column.crest = spawnLayer(at, WAVE_CREST, crestPose(column, 0.12f, 50), lifetime);
            }
            telegraph();
            Loop.repeat(ctx(), owner, 0, 1, totalTicks, this, this::crashAll);
        }

        private BlockDisplay spawnLayer(Location at, BlockData data, Transformation start, long lifetime) {
            BlockDisplay display = Displays.block(ctx(), player(), at, data, start, lifetime);
            display.setTeleportDuration(1);
            display.setViewRange(2.0f);
            return display;
        }

        /** The water gathers: spray bursts up behind the owner and foam lines mark where the wave will roll. */
        private void telegraph() {
            Location base = origin.clone().add(0, 0.2, 0);
            Vector right = right();
            double half = columns.length == 0 ? 0 : Math.abs(columns[0].offset) + COLUMN_SPACING * 0.5;
            Location leftStart = base.clone().add(right.clone().multiply(-half));
            Location rightStart = base.clone().add(right.clone().multiply(half));
            Vector run = forward.clone().multiply(distance);
            Vfx.line(ctx(), leftStart, leftStart.clone().add(run), 1.2, Particle.DUST, FOAM, true);
            Vfx.line(ctx(), rightStart, rightStart.clone().add(run), 1.2, Particle.DUST, FOAM, true);
            Location behind = base.clone().subtract(forward.clone().multiply(0.8));
            world.spawnParticle(Particle.SPLASH, behind, Vfx.n(ctx(), 40), half * 0.6, 0.6, 0.4, 0.3, null, true);
            world.spawnParticle(Particle.CLOUD, behind.clone().add(0, 0.8, 0), Vfx.n(ctx(), 12), half * 0.5, 0.5,
                    0.3, 0.05, null, true);
            Vfx.dot(behind.clone().add(0, 1.5, 0), Particle.FLASH, AQUA, true);
            Fx.sound(origin, Sound.ITEM_TRIDENT_RIPTIDE_3, 1.2f, 0.7f);
            Fx.sound(origin, Sound.AMBIENT_UNDERWATER_ENTER, 1.4f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(origin, Sound.BLOCK_CONDUIT_ACTIVATE, 1.0f, 0.8f);
            Fx.sound(origin, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.2f, 0.6f + Fx.jitter(0.05f));
        }

        @Override
        public boolean test(long tick) {
            travelled = Math.min(distance, travelled + speed);
            boolean any = false;
            for (Column column : columns) {
                if (!column.alive) {
                    continue;
                }
                if (!advance(column)) {
                    crash(column, true);
                    continue;
                }
                any = true;
                moveColumn(column, tick);
                sweep(column);
            }
            carry();
            drawWave(tick);
            if (tick % 5 == 0) {
                Location center = frontAt(0, columns[columns.length / 2].groundY);
                Fx.sound(center, Sound.WEATHER_RAIN_ABOVE, 1.4f, 0.6f + Fx.jitter(0.05f));
                Fx.sound(center, Sound.ENTITY_GENERIC_SPLASH, 0.9f, 0.6f + Fx.jitter(0.1f));
            }
            return any && travelled < distance;
        }

        /** Moves a column's ground along the terrain; false when a wall or a drop stops it. */
        private boolean advance(Column column) {
            Location front = frontAt(column.offset, column.groundY);
            int x = front.getBlockX();
            int z = front.getBlockZ();
            int previous = column.groundY;
            if (!passable(x, previous + 1, z) && !passable(x, previous + 2, z)) {
                return false;
            }
            Integer ground = surface(x, z, previous);
            if (ground == null) {
                return false;
            }
            column.groundY = ground;
            return true;
        }

        /** The walkable ground block of column (x, z): at most one step up and {@link #MAX_STEP_DOWN} down. */
        private @Nullable Integer surface(int x, int z, int previous) {
            int top = Math.min(previous + 1, world.getMaxHeight() - 2);
            int bottom = Math.max(world.getMinHeight(), previous - MAX_STEP_DOWN);
            for (int y = top; y >= bottom; y--) {
                Block block = world.getBlockAt(x, y, z);
                if (!block.isPassable() && block.getRelative(BlockFace.UP).isPassable()) {
                    return y;
                }
            }
            return null;
        }

        private boolean passable(int x, int y, int z) {
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                return true;
            }
            return world.getBlockAt(x, y, z).isPassable();
        }

        private void moveColumn(Column column, long tick) {
            Location at = columnAt(column);
            for (BlockDisplay display : new BlockDisplay[] {column.body, column.face, column.crest}) {
                if (display != null && display.isValid()) {
                    display.teleport(at);
                }
            }
            if (tick % POSE_PERIOD != 0) {
                return;
            }
            float rise = Math.min(1f, (float) (tick + POSE_PERIOD) / RISE_TICKS);
            int index = indexOf(column);
            float swell = 0.88f + 0.12f * (float) Math.sin(tick * 0.45 + index * 1.3);
            float height = rise * swell;
            animate(column.body, bodyPose(column, height));
            animate(column.face, facePose(column, height, 18 + 6 * (float) Math.sin(tick * 0.3 + index)));
            animate(column.crest, crestPose(column, height, 48 + 10 * (float) Math.sin(tick * 0.4 + index * 0.7)));
        }

        private void animate(@Nullable BlockDisplay display, Transformation pose) {
            if (display != null && display.isValid()) {
                Displays.animate(display, pose, (int) POSE_PERIOD);
            }
        }

        /** Enemies the front of a column reaches are hit once and start riding the wave. */
        private void sweep(Column column) {
            Location probe = frontAt(column.offset, column.groundY).add(0, 1.0, 0)
                    .subtract(forward.clone().multiply(0.4));
            for (LivingEntity enemy : ctx().enemiesNear(player(), probe, catchRadius)) {
                if (!caught.add(enemy.getUniqueId())) {
                    continue;
                }
                Combat.hit(ctx(), player(), enemy, params().num("wave_damage"));
                if (!Combat.isTargetable(ctx(), player(), enemy)) {
                    continue;
                }
                riders.put(enemy.getUniqueId(), new Rider(enemy, column));
                Location body = Fx.body(enemy);
                world.spawnParticle(Particle.SPLASH, body, Vfx.n(ctx(), 24), 0.4, 0.6, 0.4, 0.3, null, true);
                world.spawnParticle(Particle.BUBBLE_POP, body, Vfx.n(ctx(), 8), 0.4, 0.5, 0.4, 0.05, null, true);
                Vfx.dot(body, Particle.FLASH, AQUA, true);
                Fx.sound(body, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.0f, 1.0f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.ENTITY_PLAYER_HURT_DROWN, 0.8f, 0.9f + Fx.jitter(0.1f));
            }
        }

        /**
         * Steers every rider onto the face of its column. Being swept away is the wave's crowd control, not knockback:
         * it ignores knockback resistance (like a black hole's pull); the final crash is knockback and respects it.
         */
        private void carry() {
            Iterator<Rider> iterator = riders.values().iterator();
            while (iterator.hasNext()) {
                Rider rider = iterator.next();
                LivingEntity entity = rider.entity();
                if (!Combat.isTargetable(ctx(), player(), entity) || !rider.column().alive) {
                    iterator.remove();
                    continue;
                }
                Location target = frontAt(rider.column().offset, rider.column().groundY)
                        .add(forward.clone().multiply(RIDE_AHEAD)).add(0, RIDE_HEIGHT, 0);
                Location at = entity.getLocation();
                Vector to = target.toVector().subtract(at.toVector());
                if (Math.hypot(to.getX(), to.getZ()) > RIDE_LOST) {
                    iterator.remove();
                    continue;
                }
                Vector velocity = forward.clone().multiply(speed)
                        .add(new Vector(to.getX(), 0, to.getZ()).multiply(RIDE_GAIN));
                velocity.setY(Math.clamp(to.getY() * RIDE_GAIN, -0.35, 0.4));
                entity.setVelocity(Motion.clamp(velocity, MAX_SPEED));
                entity.setFallDistance(0);
                if (ThreadLocalRandom.current().nextInt(3) == 0) {
                    Vfx.dot(Fx.body(entity), Particle.SPLASH, null, true);
                }
            }
        }

        /** Foam along the crest, water pouring down the face and a wet trail behind. */
        private void drawWave(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            float rise = Math.min(1f, (float) (tick + 1) / RISE_TICKS);
            for (Column column : columns) {
                if (!column.alive) {
                    continue;
                }
                double height = WAVE_HEIGHT * column.profile * rise;
                Location front = frontAt(column.offset, column.groundY);
                Vector across = right().multiply(column.width * 0.5);
                for (int i = 0; i < Vfx.n(ctx(), 2); i++) {
                    Location crest = front.clone().add(across.clone().multiply(random.nextDouble() * 2 - 1))
                            .add(0, height * (0.85 + random.nextDouble() * 0.15), 0);
                    world.spawnParticle(Particle.CLOUD, crest, 1, 0.05, 0.05, 0.05, 0.02, null, true);
                    world.spawnParticle(Particle.DUST, crest, 1, 0.1, 0.05, 0.1, 0, FOAM, true);
                }
                for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                    Location spray = front.clone().add(across.clone().multiply(random.nextDouble() * 2 - 1))
                            .add(forward.clone().multiply(0.3)).add(0, height * random.nextDouble(), 0);
                    world.spawnParticle(Particle.SPLASH, spray, 1, 0.1, 0.1, 0.1, 0.15, null, true);
                }
                Location face = front.clone().add(across.clone().multiply(random.nextDouble() * 2 - 1))
                        .add(0, height * 0.6, 0);
                world.spawnParticle(Particle.FALLING_WATER, face, 1, 0.1, 0.2, 0.1, 0, null, true);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, face.clone().subtract(0, height * 0.4, 0), 1,
                        0.2, 0.1, 0.2, 0, DEEP, true);
                if (tick % 2 == 0) {
                    Location wake = front.clone().subtract(forward.clone().multiply(WAVE_DEPTH + 0.4))
                            .add(across.clone().multiply(random.nextDouble() * 2 - 1)).add(0, 0.15, 0);
                    world.spawnParticle(Particle.DRIPPING_WATER, wake, 1, 0.2, 0, 0.2, 0, null, true);
                    world.spawnParticle(Particle.DUST, wake, 1, 0.2, 0, 0.2, 0, SEA, true);
                }
            }
        }

        /** The whole wave breaks at the end of its run. */
        private void crashAll() {
            for (Column column : columns) {
                if (column.alive) {
                    crash(column, false);
                }
            }
            for (Rider rider : riders.values()) {
                slam(rider.entity());
            }
            riders.clear();
            int hit = caught.size();
            if (hit > 0 && player().isOnline()) {
                player().sendActionBar(Text.mm("<" + COLOR_TAG + ">해일 <white>" + hit + "명 휩쓸림!</white></"
                        + COLOR_TAG + ">"));
            }
        }

        /** A column breaks: it collapses forward in a burst of spray; its riders are smashed down. */
        private void crash(Column column, boolean early) {
            column.alive = false;
            float height = WAVE_HEIGHT * column.profile;
            animateCrash(column.body, bodyPose(column, 0.08f));
            animateCrash(column.face, facePose(column, 0.25f, 70));
            animateCrash(column.crest, crestPose(column, 0.3f, 110));
            Location front = frontAt(column.offset, column.groundY).add(0, 0.3, 0);
            Vector across = right().multiply(column.width * 0.5);
            world.spawnParticle(Particle.SPLASH, front.clone().add(0, height * 0.4, 0), Vfx.n(ctx(), 30),
                    column.width * 0.4, height * 0.3, 0.5, 0.35, null, true);
            world.spawnParticle(Particle.CLOUD, front.clone().add(0, height * 0.3, 0), Vfx.n(ctx(), 6),
                    column.width * 0.3, 0.3, 0.3, 0.06, null, true);
            world.spawnParticle(Particle.BUBBLE_POP, front, Vfx.n(ctx(), 10), column.width * 0.4, 0.3, 0.4, 0.05,
                    null, true);
            Vfx.line(ctx(), front.clone().add(across), front.clone().subtract(across), 3, Particle.DUST, FOAM, true);
            puddle(front, column.width);
            if (early) {
                Iterator<Rider> iterator = riders.values().iterator();
                while (iterator.hasNext()) {
                    Rider rider = iterator.next();
                    if (rider.column() == column) {
                        slam(rider.entity());
                        iterator.remove();
                    }
                }
                Fx.sound(front, Sound.ENTITY_GENERIC_SPLASH, 1.0f, 0.8f + Fx.jitter(0.1f));
            } else if (column == columns[columns.length / 2]) {
                Vfx.shockwave(ctx(), player(), front.clone().add(forward.clone().multiply(1.0)), 0.5, 3.5, 6,
                        Particle.DUST, FOAM, true);
                Fx.sound(front, Sound.ENTITY_GENERIC_SPLASH, 1.6f, 0.5f);
                Fx.sound(front, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.4f, 0.6f);
                Fx.sound(front, Sound.AMBIENT_UNDERWATER_EXIT, 1.2f, 0.7f + Fx.jitter(0.05f));
                Fx.sound(front, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 1.0f, 0.8f);
            }
        }

        private void animateCrash(@Nullable BlockDisplay display, Transformation pose) {
            if (display == null || !display.isValid()) {
                return;
            }
            Displays.animate(display, pose, (int) CRASH_TICKS - 1);
            ctx().runLater(player(), CRASH_TICKS, () -> {
                if (display.isValid()) {
                    display.remove();
                }
            });
        }

        /** The crash damage and a final shove for a rider. */
        private void slam(LivingEntity entity) {
            Player owner = player();
            Combat.hit(ctx(), owner, entity, params().num("crash_damage"));
            if (!Combat.isTargetable(ctx(), owner, entity)) {
                return;
            }
            boolean room = ctx().isInsideArena(entity.getLocation().add(forward.clone().multiply(CRASH_TRAVEL)));
            Motion.push(entity, forward.clone().multiply(room ? CRASH_PUSH : 0).setY(CRASH_LIFT), MAX_SPEED);
            entity.setFallDistance(0);
            Location body = Fx.body(entity);
            world.spawnParticle(Particle.SPLASH, body, Vfx.n(ctx(), 20), 0.4, 0.5, 0.4, 0.3, null, true);
            Vfx.dot(body, Particle.FLASH, PALETTE.core(), true);
            Fx.sound(body, Sound.ENTITY_GENERIC_SPLASH, 1.0f, 1.1f + Fx.jitter(0.1f));
        }

        /** Afterglow: water still running off the ground where a column broke. */
        private void puddle(Location front, float width) {
            Location center = front.clone();
            Vector across = right().multiply(width * 0.5);
            Loop.repeat(ctx(), player(), 2, 2, PUDDLE_TICKS / 2, run -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                double fade = 1 - (double) run / (PUDDLE_TICKS / 2.0);
                int drops = Math.max(1, (int) Math.round(Vfx.n(ctx(), 3) * fade));
                for (int i = 0; i < drops; i++) {
                    Location at = center.clone().add(across.clone().multiply(random.nextDouble() * 2 - 1))
                            .add(forward.clone().multiply(random.nextDouble() * 1.5 - 0.3));
                    world.spawnParticle(i % 2 == 0 ? Particle.DRIPPING_WATER : Particle.SPLASH, at, 1, 0, 0, 0, 0,
                            null, true);
                }
                if (run % 3 == 0) {
                    Vfx.dot(center.clone().add(0, 0.1, 0), Particle.DUST_COLOR_TRANSITION, DEEP, true);
                }
                return true;
            });
        }

        // -------------------------------------------------------------- geometry

        private Vector right() {
            return new Vector(-forward.getZ(), 0, forward.getX());
        }

        private int indexOf(Column column) {
            for (int i = 0; i < columns.length; i++) {
                if (columns[i] == column) {
                    return i;
                }
            }
            return 0;
        }

        /** The front of the wave at {@code offset} across it, standing on ground block {@code groundY}. */
        private Location frontAt(double offset, int groundY) {
            Location at = origin.clone().add(forward.clone().multiply(travelled)).add(right().multiply(offset));
            at.setY(groundY + 1.0);
            return at;
        }

        /** Where a column's displays stand, turned to face along the wave (their local +z is forward). */
        private Location columnAt(Column column) {
            Location at = frontAt(column.offset, column.groundY);
            at.setYaw(yaw);
            at.setPitch(0);
            return at;
        }
    }

    /** The deep body of a water column: a box from the front edge back, its back sloping into the ground. */
    private static Transformation bodyPose(Column column, float height) {
        float h = Math.max(0.05f, WAVE_HEIGHT * column.profile * height * 0.62f);
        return tiltedBox(-column.width / 2, -0.15f, -WAVE_DEPTH, column.width, h, WAVE_DEPTH, 0f, 0f, -10f);
    }

    /** The light face of a column, leaning forward over the body. */
    private static Transformation facePose(Column column, float height, float lean) {
        float full = WAVE_HEIGHT * column.profile * Math.max(0.05f, height);
        float base = full * 0.35f;
        return tiltedBox(-column.width * 0.49f, base, -0.95f, column.width * 0.98f, Math.max(0.05f, full * 0.5f),
                0.95f, base, 0f, lean);
    }

    /** The white curling crest on top of a column. */
    private static Transformation crestPose(Column column, float height, float curl) {
        float full = WAVE_HEIGHT * column.profile * Math.max(0.05f, height);
        float base = full * 0.78f;
        return tiltedBox(-column.width * 0.48f, base, -0.7f, column.width * 0.96f, Math.max(0.04f, full * 0.22f),
                0.75f, base, 0f, curl);
    }

    /**
     * A box with its corner at {@code (minX, minY, minZ)} and the given size, then rotated by {@code pitchDeg}
     * around the x axis through {@code (0, pivotY, pivotZ)}. Positive angles lean the top towards +z (forward).
     */
    private static Transformation tiltedBox(float minX, float minY, float minZ, float sx, float sy, float sz,
                                            float pivotY, float pivotZ, float pitchDeg) {
        Quaternionf rotation = new Quaternionf().rotateX((float) Math.toRadians(pitchDeg));
        Vector3f pivot = new Vector3f(0, pivotY, pivotZ);
        Vector3f translation = rotation.transform(new Vector3f(minX, minY, minZ).sub(pivot)).add(pivot);
        return new Transformation(translation, rotation, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    /** A square tube of the given width from the display's origin along {@code delta}, rolled {@code roll} degrees. */
    private static Transformation beamPose(Vector delta, float width, float roll) {
        Vector3f direction = new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ());
        float length = direction.length();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction.normalize())
                .rotateZ((float) Math.toRadians(roll % 360f));
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
        return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Claims {@code count} display entities for {@code lifetime} ticks if that stays within {@link #MAX_DISPLAYS}.
     */
    private boolean reserveDisplays(int count, long lifetime) {
        if (count <= 0 || liveDisplays + count > MAX_DISPLAYS) {
            return false;
        }
        liveDisplays += count;
        ctx().runLater(player(), Math.max(1, lifetime), () -> liveDisplays -= count);
        return true;
    }

    /** A ring of {@code particle} around the axis {@code direction}, centred on {@code center}. */
    private <T> void aimRing(Location center, Vector direction, double radius, int points, double phase,
                             Particle particle, @Nullable T data) {
        Vector axis = direction.clone().normalize();
        Vector side = axis.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0E-6) {
            side = new Vector(1, 0, 0);
        }
        side.normalize();
        Vector up = side.clone().crossProduct(axis).normalize();
        int count = Vfx.n(ctx(), points);
        for (int i = 0; i < count; i++) {
            double angle = phase + i * (Math.PI * 2 / count);
            Vector offset = side.clone().multiply(Math.cos(angle) * radius)
                    .add(up.clone().multiply(Math.sin(angle) * radius));
            Vfx.dot(center.clone().add(offset), particle, data, false);
        }
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
