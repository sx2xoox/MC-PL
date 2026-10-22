package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 중력술사 — bends gravity. Melee hits pop enemies into the air for juggle combos, the active drags an aimed enemy
 * right in front of the owner, and the ultimate opens a black hole that swallows nearby enemies before it collapses.
 */
public final class GravityAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("gravity", "중력술사", Material.HEAVY_CORE)
            .color(TextColor.color(0x7D6BFF))
            .difficulty(2)
            .passive("무중력 타격",
                    "근접 공격한 적을 {lift_height}블록 띄웁니다(같은 적은 {lift_cooldown}초마다). "
                            + "띄운 적이 공중에 있는 동안 근접 피해가 {air_bonus}% 증가합니다.")
            .active("끌어당기기",
                    "조준한 적({pull_range}블록 이내)을 중력으로 붙잡아 내 앞으로 끌어오고 {pull_slow}초간 구속 II를 겁니다.",
                    8)
            .ultimate("블랙홀",
                    "조준한 지점({ult_range}블록 이내)에 {ult_duration}초간 블랙홀을 열어 반경 {ult_radius}블록의 적을 "
                            + "빨아들입니다. 블랙홀이 붕괴하면 반경 {blast_radius}블록의 적에게 {blast_damage} 피해를 주고 "
                            + "밀쳐냅니다.")
            .param("lift_height", 1.5)
            .param("lift_cooldown", 1)
            .param("air_bonus", 20)
            .param("pull_range", 25)
            .param("pull_slow", 1)
            .param("ult_range", 30)
            .param("ult_duration", 3)
            .param("ult_radius", 8)
            .param("blast_radius", 4.5)
            .param("blast_damage", 8)
            .build();

    private static final String COLOR_TAG = "#7D6BFF";
    /** Pale lilac glow, indigo body, the near-black violet of the void. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xCBB8FF, 0x7D6BFF, 0x12002B);
    private static final Particle.DustTransition STRAND_GLOW = PALETTE.glow(0.7f);
    private static final Particle.DustOptions STRAND_DUST = PALETTE.dust(0.6f);
    private static final Particle.DustTransition DISK_INNER = PALETTE.glow(1.1f);
    private static final Particle.DustTransition DISK_OUTER = PALETTE.fade(1.0f);
    private static final Particle.DustTransition MOTE = PALETTE.fade(0.9f);

    /** Hard cap for every velocity this ability applies, so nobody is flung out of the arena. */
    private static final double MAX_SPEED = 2.5;

    // passive
    /** Horizontal push that replaces vanilla knockback on a lifted enemy, keeping it in combo range. */
    private static final double LIFT_PUSH = 0.25;
    /** Upper bound for how long a lifted enemy counts as juggled (it ends earlier once it lands). */
    private static final long JUGGLE_WINDOW_TICKS = 40;
    private static final long CLEANUP_PERIOD_TICKS = 200;
    /** The levitation halo under a lifted enemy: runs every 2 ticks, at most this often. */
    private static final int HALO_RUNS = 10;
    private static final int COMBO_LABEL_TICKS = 14;

    // active
    private static final int PULL_MAX_TICKS = 24;
    private static final double PULL_SPEED = 1.6;
    private static final double PULL_ARRIVE_DISTANCE = 1.4;
    /** Where in front of the owner the pulled enemy is dropped. */
    private static final double PULL_STOP_DISTANCE = 1.8;
    private static final BlockData TETHER_BLOCK = Material.AMETHYST_BLOCK.createBlockData();
    private static final float TETHER_WIDTH = 0.07f;
    private static final int TETHER_POINTS = 14;
    private static final int LOCK_ON_TICKS = 4;

    // ultimate
    /** Core height above the ground: about chest height, so swallowed enemies are held standing on the ground. */
    private static final double CORE_HEIGHT = 1.1;
    private static final int GROUND_SNAP = 8;
    private static final float CORE_SIZE = 0.95f;
    private static final float SHELL_SIZE = 1.75f;
    private static final float SHELL_BURST = 3.2f;
    private static final int POSE_PERIOD = 2;
    private static final int GROW_TICKS = 9;
    private static final int COLLAPSE_TICKS = 6;
    /** The sonic charge sound is about 1.6 s long; start it so it peaks when the black hole collapses. */
    private static final int CHARGE_SOUND_LEAD = 32;
    private static final double SUCK_MIN = 0.16;
    private static final double SUCK_MAX = 0.5;
    private static final double SWIRL = 0.1;
    /** Upward pull cap: enemies below the core are drawn up gently instead of being launched into the air. */
    private static final double MAX_SUCK_LIFT = 0.2;
    private static final double CORE_HOLD_DISTANCE = 0.7;
    private static final double BLAST_PUSH = 1.3;
    private static final double BLAST_LIFT = 0.6;
    private static final BlockData CORE_BLOCK = Material.BLACK_CONCRETE.createBlockData();
    private static final BlockData SHELL_BLOCK = Material.PURPLE_STAINED_GLASS.createBlockData();
    private static final BlockData[] DEBRIS_BLOCKS = {
            Material.CRYING_OBSIDIAN.createBlockData(),
            Material.OBSIDIAN.createBlockData(),
            Material.AMETHYST_BLOCK.createBlockData(),
    };
    private static final int DEBRIS_COUNT = 6;
    /** The core is three black cubes in different orientations, which together read as a rough sphere. */
    private static final Quaternionf[] CORE_ORIENTATIONS = {
            new Quaternionf(),
            new Quaternionf().rotateY((float) Math.toRadians(45)).rotateX((float) Math.toRadians(35.26)),
            new Quaternionf().rotateZ((float) Math.toRadians(45)).rotateY((float) Math.toRadians(30)),
    };
    private static final Vector3f CORE_AXIS = new Vector3f(0.35f, 1f, 0.2f).normalize();
    private static final Vector3f SHELL_AXIS = new Vector3f(-0.4f, 1f, 0.3f).normalize();
    private static final Vector3f DEBRIS_AXIS = new Vector3f(0.3f, 1f, 0.2f).normalize();
    /** Normal of the tilted accretion disk. */
    private static final Vector DISK_NORMAL = new Vector(0.18, 1, -0.12).normalize();
    private static final int AFTERGLOW_TICKS = 16;

    /** Enemies this owner has hit or lifted, by entity id. */
    private final Map<UUID, Juggle> juggles = new HashMap<>();

    /** Per-enemy combo state: lift cooldown, the airborne window, the running combo count and its floating label. */
    private static final class Juggle {
        long nextLift;
        long airborneUntil;
        int combo;
        @Nullable TextDisplay label;
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        juggles.clear();
    }

    @Override
    public void onTick(long tick) {
        if (tick % CLEANUP_PERIOD_TICKS != 0 || juggles.isEmpty()) {
            return;
        }
        long now = ctx().currentTick();
        juggles.values().removeIf(juggle -> juggle.nextLift <= now && juggle.airborneUntil <= now);
    }

    // ------------------------------------------------------------------ passive: weightless strike

    /** The air bonus: only changes the damage, since the victim may still cancel the hit. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnMelee(event, victim)) {
            return;
        }
        Juggle juggle = juggles.get(victim.getUniqueId());
        if (juggle != null && isJuggled(juggle, victim)) {
            double bonus = Math.max(0, params().num("air_bonus")) / 100.0;
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    /** The hit landed: count the combo and lift the victim. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnMelee(event, victim)) {
            return;
        }
        long now = ctx().currentTick();
        Juggle juggle = juggles.computeIfAbsent(victim.getUniqueId(), id -> new Juggle());
        if (isJuggled(juggle, victim)) {
            juggle.combo++;
            comboFeedback(victim, juggle);
        } else {
            juggle.combo = 1;
        }
        if (now >= juggle.nextLift) {
            juggle.nextLift = now + params().ticks("lift_cooldown");
            double height = params().num("lift_height");
            // Vanilla knockback is applied after this event; lift on the next tick so it is not overwritten.
            ctx().runLater(player(), 1, () -> lift(victim, height));
        }
    }

    /** The owner's own melee swing at an enemy (not ability damage, projectiles or summon bites). */
    private boolean isOwnMelee(EntityDamageByEntityEvent event, LivingEntity victim) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && event.getCause() == DamageCause.ENTITY_ATTACK && ctx().isEnemy(player(), victim);
    }

    /** True while a lifted enemy is still in the air within its juggle window. */
    private boolean isJuggled(Juggle juggle, LivingEntity victim) {
        return ctx().currentTick() < juggle.airborneUntil && !(Motion.isGrounded(victim) || victim.isInWater());
    }

    private void lift(LivingEntity victim, double height) {
        if (!Combat.isTargetable(ctx(), player(), victim) || height <= 0) {
            return;
        }
        Vector push = Motion.horizontal(victim.getLocation().toVector().subtract(player().getLocation().toVector()));
        push.multiply(LIFT_PUSH).setY(Motion.launchSpeed(height));
        if (!Motion.push(victim, push, MAX_SPEED)) {
            return;
        }
        markAirborne(victim);
        liftFx(victim);
    }

    /** An upside-down gravity ripple under the lifted enemy, then a small halo while it floats. */
    private void liftFx(LivingEntity victim) {
        Location feet = victim.getLocation().add(0, 0.1, 0);
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        Vfx.ring(ctx(), feet, 0.8, 14, phase, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f), false);
        Vfx.ring(ctx(), feet.clone().add(0, 0.4, 0), 0.55, 10, phase + 0.3, Particle.DUST, PALETTE.dust(0.8f), false);
        Vfx.cloud(ctx(), feet, Particle.REVERSE_PORTAL, 6, 0.25, 0.03, null, false);
        Fx.sound(feet, Sound.ENTITY_SHULKER_BULLET_HIT, 0.7f, 1.4f + Fx.jitter(0.15f));
        Fx.sound(feet, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 0.7f + Fx.jitter(0.1f));
        Loop.repeat(ctx(), player(), 2, 2, HALO_RUNS, run -> {
            if (!Combat.isTargetable(ctx(), player(), victim)
                    || run > 1 && (Motion.isGrounded(victim) || victim.isInWater())) {
                return false;
            }
            Vfx.ring(ctx(), victim.getLocation().add(0, 0.05, 0), 0.42, 6, run * 0.6, Particle.DUST,
                    PALETTE.dust(0.6f), false);
            return true;
        });
    }

    private void comboFeedback(LivingEntity victim, Juggle juggle) {
        int combo = juggle.combo;
        Location body = Fx.body(victim);
        Vfx.cloud(ctx(), body, Particle.ENCHANTED_HIT, 6, 0.3, 0.15, null, false);
        Vfx.cloud(ctx(), body, Particle.END_ROD, 2, 0.15, 0.04, null, false);
        Vfx.arc(ctx(), body, 0.75, player().getLocation().getYaw(), 140, 9, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(0.9f), false);
        Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, (float) Math.min(2.0, 0.9 + 0.15 * combo));
        Fx.sound(body, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 0.5f, (float) Math.min(2.0, 1.2 + 0.12 * combo));
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">공중 콤보 <white>×" + combo + "</white></" + COLOR_TAG + ">"));

        // A floating combo counter that pops up over the juggled enemy; only the newest one is kept.
        if (juggle.label != null && juggle.label.isValid()) {
            juggle.label.remove();
        }
        Location above = victim.getLocation().add(0, victim.getHeight() + 0.35, 0);
        TextDisplay label = Displays.text(ctx(), player(), above,
                Text.mm("<b><gradient:#CBB8FF:#7D6BFF>×" + combo + "</gradient></b>"), 0.7f, COMBO_LABEL_TICKS);
        juggle.label = label;
        float pop = (float) Math.min(1.6, 1.05 + 0.08 * combo);
        Displays.animateNextTick(ctx(), player(), label, Displays.centered(0, 0.35f, 0, pop, pop, pop, 0, 0, 0), 4);
        ctx().runLater(player(), COMBO_LABEL_TICKS - 5, () -> {
            if (label.isValid()) {
                Displays.shrinkAway(ctx(), player(), label, 4);
            }
        });
    }

    /** Opens the juggle window: melee hits get the air bonus while the enemy is still off the ground. */
    private void markAirborne(LivingEntity victim) {
        Juggle juggle = juggles.computeIfAbsent(victim.getUniqueId(), id -> new Juggle());
        juggle.airborneUntil = ctx().currentTick() + JUGGLE_WINDOW_TICKS;
    }

    // ------------------------------------------------------------------ active: gravity pull

    @Override
    public ActivationResult onActive() {
        LivingEntity target = ctx().findTarget(player(), params().num("pull_range"));
        if (target == null) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        long slowTicks = params().ticks("pull_slow");
        Pull pull = new Pull(target, slowTicks);
        pull.cast();
        Loop.repeat(ctx(), player(), 0, 1, PULL_MAX_TICKS, pull, pull::release);
        return ActivationResult.success();
    }

    /** The spot in front of the owner where pulled enemies are dropped (the owner's spot if a wall is in the way). */
    private static Location pullDestination(Player owner) {
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontal(feet.getDirection());
        if (facing.lengthSquared() < 1.0E-6) {
            return feet;
        }
        RayTraceResult wall = owner.getWorld().rayTraceBlocks(feet.clone().add(0, 0.5, 0), facing,
                PULL_STOP_DISTANCE, FluidCollisionMode.NEVER, true);
        return wall != null ? feet : feet.add(facing.multiply(PULL_STOP_DISTANCE));
    }

    /** Where the gravity tether leaves the owner: just in front of the casting hand. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(eye.getDirection().multiply(0.55)).subtract(0, 0.3, 0);
    }

    /**
     * The gravity pull: a thin amethyst tether wound with a violet double helix drags the target to the owner, who
     * then lands with a small shockwave and a sinking pressure ring for the duration of the slow.
     */
    private final class Pull implements LongPredicate {

        private final LivingEntity target;
        private final long slowTicks;
        private @Nullable BlockDisplay tether;
        private boolean arrived;
        /** The velocity the pull last gave the target. */
        private Vector lastVelocity = new Vector();

        Pull(LivingEntity target, long slowTicks) {
            this.target = target;
            this.slowTicks = slowTicks;
        }

        void cast() {
            Player owner = player();
            Location hand = hand(owner);
            Location body = Fx.body(target);
            Vfx.cloud(ctx(), hand, Particle.DUST_COLOR_TRANSITION, 8, 0.12, 0, PALETTE.glow(0.9f), false);
            Vfx.cloud(ctx(), body, Particle.REVERSE_PORTAL, 14, 0.3, 0.05, null, false);
            Fx.sound(owner.getEyeLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 1.6f + Fx.jitter(0.1f));
            Fx.sound(owner.getEyeLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.6f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.ENTITY_SHULKER_SHOOT, 1.0f, 0.6f + Fx.jitter(0.05f));
            // Lock-on: a ring closing in around the target.
            Loop.repeat(ctx(), owner, 0, 1, LOCK_ON_TICKS, run -> {
                if (!target.isValid()) {
                    return false;
                }
                double radius = 1.4 - 0.9 * (run + 1) / LOCK_ON_TICKS;
                Vfx.ring(ctx(), Fx.body(target), radius, 16, run * 0.4, Particle.DUST_COLOR_TRANSITION,
                        PALETTE.glow(1.0f), false);
                return true;
            });
            Vector delta = body.toVector().subtract(hand.toVector());
            if (delta.lengthSquared() > 1.0E-4) {
                tether = Displays.block(ctx(), owner, hand, TETHER_BLOCK, beamPose(delta, TETHER_WIDTH),
                        PULL_MAX_TICKS + 6L);
                tether.setTeleportDuration(1);
            }
        }

        /** One tick of the pull: steer the target towards the spot in front of the owner. False when finished. */
        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(owner.getWorld())) {
                return false;
            }
            Vector to = pullDestination(owner).toVector().subtract(target.getLocation().toVector());
            double distance = to.length();
            if (distance <= PULL_ARRIVE_DISTANCE || tick >= PULL_MAX_TICKS - 1) {
                arrive();
                return false;
            }
            double speed = Math.min(PULL_SPEED, Math.max(0.3, distance * 0.45));
            Vector velocity = Motion.horizontal(to).multiply(speed);
            // Small hops keep the target off the ground (no ground friction) without launching it.
            boolean grounded = Motion.isGrounded(target) || target.isInWater();
            velocity.setY(Math.clamp(to.getY() * 0.3, -0.6, 0.6) + (grounded ? 0.22 : 0.0));
            lastVelocity = Motion.clamp(velocity, MAX_SPEED);
            target.setVelocity(lastVelocity);
            target.setFallDistance(0);
            drawTether(tick);
            return true;
        }

        private void drawTether(long tick) {
            Location hand = hand(player());
            Location body = Fx.body(target);
            Vector delta = body.toVector().subtract(hand.toVector());
            if (tether != null && tether.isValid() && delta.lengthSquared() > 1.0E-4) {
                // The hand carries the owner's yaw and pitch: a teleport there would turn the beam a second time.
                Displays.moveTo(tether, hand);
                Displays.animate(tether, beamPose(delta, TETHER_WIDTH), 1);
            }
            helix(body, hand, tick * 0.55);
            Vfx.trail(body, hand, PALETTE.core(), 6, false);
            if (tick % 2 == 0) {
                Vfx.cloud(ctx(), body, Particle.REVERSE_PORTAL, 2, 0.25, 0.02, null, false);
            }
        }

        /** Two violet strands wound around the line from {@code from} to {@code to}. */
        private void helix(Location from, Location to, double phase) {
            Vector axis = to.toVector().subtract(from.toVector());
            double length = axis.length();
            if (length < 0.3) {
                return;
            }
            axis.multiply(1 / length);
            Vector u = axis.clone().crossProduct(Math.abs(axis.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0))
                    .normalize();
            Vector v = axis.clone().crossProduct(u);
            int points = Vfx.n(ctx(), (int) Math.min(TETHER_POINTS, Math.ceil(length * 1.2)));
            World world = from.getWorld();
            for (int strand = 0; strand < 2; strand++) {
                for (int i = 0; i < points; i++) {
                    double t = (i + 0.5) / points;
                    double angle = phase + strand * Math.PI + t * length * 1.6;
                    // The coil tightens towards the owner, like matter falling into a well.
                    double radius = 0.12 + 0.2 * (1 - t);
                    Vector offset = u.clone().multiply(Math.cos(angle) * radius).add(v.clone().multiply(Math.sin(angle) * radius));
                    Location at = from.clone().add(axis.clone().multiply(t * length)).add(offset);
                    if (strand == 0) {
                        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0, STRAND_GLOW);
                    } else {
                        world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, STRAND_DUST);
                    }
                }
            }
        }

        private void arrive() {
            arrived = true;
            // Stop the drag but keep a fall going. A player's server-side velocity is stale (only its client knows
            // the real one), so the pull's own last command stands in for it.
            target.setVelocity(new Vector(0, Math.min(0, lastVelocity.getY()), 0));
            target.setFallDistance(0);
            if (slowTicks > 0) {
                int duration = (int) Math.min(Integer.MAX_VALUE, slowTicks);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, 1, false, true, true));
            }
            markAirborne(target);

            Location body = Fx.body(target);
            Location feet = target.getLocation().add(0, 0.1, 0);
            Vfx.dot(body, Particle.FLASH, PALETTE.core(), false);
            Vfx.sphere(ctx(), body, 0.7, 18, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), false);
            Vfx.cloud(ctx(), body, Particle.REVERSE_PORTAL, 12, 0.3, 0.08, null, false);
            Vfx.shockwave(ctx(), player(), feet, 0.3, 1.6, 5, Particle.DUST, PALETTE.dust(0.9f), false);
            Fx.sound(body, Sound.BLOCK_HEAVY_CORE_PLACE, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.ENTITY_SHULKER_BULLET_HIT, 0.8f, 0.8f + Fx.jitter(0.08f));
            Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 0.6f + Fx.jitter(0.05f));
            pressure();
        }

        /** Afterglow while the target is slowed: a ring sliding down its body, as if gravity pressed it down. */
        private void pressure() {
            long runs = Math.max(1, slowTicks / 3);
            Loop.repeat(ctx(), player(), 3, 3, runs, run -> {
                if (!Combat.isTargetable(ctx(), player(), target)) {
                    return false;
                }
                double cycle = (run % 4) / 3.0;
                Location at = target.getLocation().add(0, target.getHeight() * (1 - cycle) + 0.05, 0);
                Vfx.ring(ctx(), at, 0.5 - 0.1 * cycle, 8, run * 0.5, Particle.DUST_COLOR_TRANSITION,
                        PALETTE.fade(0.8f), false);
                return true;
            });
        }

        /** Runs when the pull loop ends by itself: the tether snaps back into the owner's hand. */
        void release() {
            if (tether == null || !tether.isValid()) {
                return;
            }
            if (!arrived) {
                tether.remove();
                return;
            }
            Displays.shrinkAway(ctx(), player(), tether, 3);
        }
    }

    // ------------------------------------------------------------------ ultimate: black hole

    @Override
    public ActivationResult onUltimate() {
        double radius = params().num("ult_radius");
        BlackHole hole = new BlackHole(aimPoint(params().num("ult_range")), radius);
        if (ctx().enemiesNear(player(), hole.center, radius).isEmpty()) {
            return ActivationResult.fail("블랙홀 범위(" + AbilityInfo.formatNumber(radius) + "블록) 안에 적이 없습니다");
        }
        hole.open();
        return ActivationResult.success();
    }

    /**
     * Where the black hole opens: under the aimed enemy, or where the view ray hits a block (or ends), snapped down to
     * the ground when there is ground close below.
     */
    private Location aimPoint(double range) {
        Player owner = player();
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        Location point;
        if (aimed != null) {
            point = aimed.getLocation().add(0, 0.5, 0);
        } else {
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            point = hit == null
                    ? eye.clone().add(direction.clone().multiply(range))
                    : hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.4));
        }
        Double ground = groundBelow(point);
        if (ground != null) {
            point.setY(ground);
        }
        point.add(0, CORE_HEIGHT, 0);
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
     * A black hole: pulls enemies in for its lifetime, then collapses into a blast.
     *
     * <p>Visuals: a spinning core of three black cubes outlined in violet inside a purple glass shell, debris
     * (obsidian, crying obsidian, amethyst) spiralling in on a tilted accretion disk, violet motes streaming in from
     * all around, and the event horizon drawn on the floor. Before the collapse a ring closes in to mark the blast
     * radius; the collapse is a flash, an expanding glass shell, a violet shockwave and the debris thrown outwards.
     */
    private final class BlackHole implements LongPredicate {

        private final Location center;
        private final World world;
        private final double radius;
        private final double floorY;
        private final long life;
        private final BlockDisplay[] core = new BlockDisplay[CORE_ORIENTATIONS.length];
        private final BlockDisplay[] debris = new BlockDisplay[DEBRIS_COUNT];
        private final double[] debrisRadius = new double[DEBRIS_COUNT];
        private final Vector diskU;
        private final Vector diskV;
        private @Nullable BlockDisplay shell;

        BlackHole(Location center, double radius) {
            this.center = center;
            this.world = center.getWorld();
            this.radius = radius;
            Double ground = groundBelow(center);
            this.floorY = (ground != null ? ground : center.getY() - CORE_HEIGHT) + 0.15;
            this.life = Math.max(20, params().ticks("ult_duration"));
            this.diskU = DISK_NORMAL.clone().crossProduct(new Vector(0, 0, 1)).normalize();
            this.diskV = DISK_NORMAL.clone().crossProduct(diskU).normalize();
        }

        void open() {
            Player owner = player();
            long lifetime = life + 4;
            for (int i = 0; i < core.length; i++) {
                core[i] = Displays.block(ctx(), owner, center, CORE_BLOCK, cubePose(0.05f, CORE_ORIENTATIONS[i]),
                        lifetime);
                Displays.outline(core[i], PALETTE.body());
                core[i].setViewRange(2.0f);
            }
            shell = Displays.block(ctx(), owner, center, SHELL_BLOCK, cubePose(0.05f, new Quaternionf()), lifetime + 6);
            shell.setViewRange(2.0f);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < debris.length; i++) {
                debrisRadius[i] = 1.7 + random.nextDouble() * 0.9;
                Location at = diskPoint(debrisRadius[i], i * Math.PI * 2 / debris.length, 0);
                float size = 0.18f + random.nextFloat() * 0.12f;
                debris[i] = Displays.block(ctx(), owner, at, DEBRIS_BLOCKS[i % DEBRIS_BLOCKS.length],
                        cubePose(size, randomRotation(random)), lifetime + 10);
                debris[i].setTeleportDuration(POSE_PERIOD);
            }

            // Telegraph: the event horizon spreads over the floor while the void tears open.
            Vfx.shockwave(ctx(), owner, floorPoint(), 0.5, radius, 6, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.3f), true);
            Vfx.cloud(ctx(), center, Particle.REVERSE_PORTAL, 40, 0.2, 0.6, null, true);
            Vfx.dot(center, Particle.FLASH, PALETTE.body(), true);
            Fx.sound(center, Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.5f, 0.6f + Fx.jitter(0.04f));
            Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 0.5f);
            Fx.sound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f + Fx.jitter(0.05f));
            Loop.repeat(ctx(), owner, 0, 1, life + 1, this);
        }

        @Override
        public boolean test(long tick) {
            if (tick >= life) {
                collapse();
                return false;
            }
            if (tick % POSE_PERIOD == 1) {
                updatePose(tick + POSE_PERIOD);
            }
            if (tick == life - CHARGE_SOUND_LEAD) {
                Fx.sound(center, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, 1.1f);
            }
            suck(tick);
            drawDisk(tick);
            drawInflow(tick);
            long untilCollapse = life - tick;
            if (untilCollapse <= COLLAPSE_TICKS) {
                // Telegraph of the blast: a ring closing in from the blast radius.
                double blastRadius = params().num("blast_radius");
                double closing = blastRadius * (untilCollapse - 1) / COLLAPSE_TICKS + 0.4;
                Vfx.ring(ctx(), floorPoint(), closing, (int) Math.max(12, closing * 6), tick * 0.3,
                        Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.4f), true);
            } else if (tick % 20 == 0) {
                Fx.sound(center, Sound.BLOCK_PORTAL_AMBIENT, 0.8f, 0.5f + 0.6f * tick / life);
                Fx.sound(center, Sound.BLOCK_BEACON_AMBIENT, 1.0f, 0.5f + 0.3f * tick / life);
            }
            return true;
        }

        /** Sets the displays' pose for {@code targetTick}, interpolated over the next {@link #POSE_PERIOD} ticks. */
        private void updatePose(long targetTick) {
            float growth = Math.min(1f, (float) targetTick / GROW_TICKS);
            long untilCollapse = life - targetTick;
            float shrink = untilCollapse >= COLLAPSE_TICKS ? 1f : Math.max(0.03f, (float) untilCollapse / COLLAPSE_TICKS);
            float scale = growth * shrink;
            // Breathing: the core pulses slightly so it never looks static.
            float pulse = 1f + 0.06f * (float) Math.sin(targetTick * 0.5);
            float angle = targetTick * 0.15f;
            for (int i = 0; i < core.length; i++) {
                Quaternionf rotation = new Quaternionf().rotateAxis(angle * (1 + 0.25f * i), CORE_AXIS)
                        .mul(CORE_ORIENTATIONS[i]);
                pose(core[i], cubePose(CORE_SIZE * scale * pulse, rotation));
            }
            pose(shell, cubePose(SHELL_SIZE * scale, new Quaternionf().rotateAxis(-angle * 0.7f, SHELL_AXIS)));

            // Debris spirals in faster and tighter as the collapse nears.
            double progress = Math.min(1.0, (double) targetTick / life);
            double orbit = targetTick * (0.18 + 0.25 * progress);
            for (int i = 0; i < debris.length; i++) {
                BlockDisplay piece = debris[i];
                if (piece == null || !piece.isValid()) {
                    continue;
                }
                double r = debrisRadius[i] * (1 - 0.55 * progress * progress) * (shrink < 1 ? shrink : 1);
                piece.teleport(diskPoint(Math.max(0.3, r), orbit + i * Math.PI * 2 / debris.length,
                        0.12 * Math.sin(targetTick * 0.3 + i)));
                Transformation current = piece.getTransformation();
                float size = current.getScale().x();
                Quaternionf spin = new Quaternionf(current.getLeftRotation()).rotateAxis(0.5f, DEBRIS_AXIS);
                Displays.animate(piece, cubePose(size, spin.normalize()), POSE_PERIOD);
            }
        }

        private void pose(@Nullable BlockDisplay display, Transformation transformation) {
            if (display != null) {
                Displays.animate(display, transformation, POSE_PERIOD);
            }
        }

        /** The pull is the black hole's crowd control, not knockback: it ignores knockback resistance by design. */
        private void suck(long tick) {
            for (LivingEntity enemy : ctx().enemiesNear(player(), center, radius)) {
                Vector to = center.toVector().subtract(Fx.body(enemy).toVector());
                double distance = to.length();
                Vector velocity;
                if (distance < CORE_HOLD_DISTANCE) {
                    // Held in the core; never pushed upwards, so grounded enemies stay grounded (no hovering).
                    velocity = new Vector(to.getX() * 0.4, Math.min(0, to.getY()) * 0.4, to.getZ() * 0.4);
                } else {
                    double closeness = 1 - Math.min(1, distance / radius);
                    Vector direction = to.multiply(1 / distance);
                    Vector swirl = new Vector(-direction.getZ(), 0, direction.getX()).multiply(SWIRL);
                    velocity = direction.multiply(SUCK_MIN + (SUCK_MAX - SUCK_MIN) * closeness).add(swirl);
                    velocity.setY(Math.clamp(velocity.getY(), -0.35, MAX_SUCK_LIFT));
                }
                enemy.setVelocity(Motion.clamp(velocity, MAX_SPEED));
                enemy.setFallDistance(0);
                if (tick % 2 == 0 && distance >= CORE_HOLD_DISTANCE) {
                    // Every caught enemy is visibly being drained into the hole.
                    Vfx.trail(Fx.body(enemy), center, PALETTE.body(), 8, true);
                }
            }
        }

        /** The accretion disk: two spiral arms of glowing dust on the tilted disk plane, plus the floor horizon. */
        private void drawDisk(long tick) {
            double base = tick * 0.35;
            int perArm = Vfx.n(ctx(), 7);
            for (int arm = 0; arm < 2; arm++) {
                for (int k = 0; k < perArm; k++) {
                    double r = 0.9 + k * 2.7 / perArm;
                    double a = base + arm * Math.PI + k * 0.55;
                    Location at = diskPoint(r, a, 0);
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0,
                            k < perArm / 2 ? DISK_INNER : DISK_OUTER, true);
                }
            }
            if (tick % 4 == 0) {
                Location floor = floorPoint();
                Vfx.ring(ctx(), floor, radius, (int) Math.min(48, Math.max(16, radius * 3.5)), tick * 0.02,
                        Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.3f), true);
                Vfx.ring(ctx(), floor, radius * 0.5, (int) Math.max(10, radius * 1.5), -tick * 0.08, Particle.DUST,
                        PALETTE.dust(0.9f), true);
            }
        }

        /** Matter streaming in from every direction. */
        private void drawInflow(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int motes = Vfx.n(ctx(), 2);
            for (int i = 0; i < motes; i++) {
                Vector offset = randomUnit(random).multiply(2.5 + random.nextDouble() * radius * 0.5);
                Vfx.trail(center.clone().add(offset), center, random.nextBoolean() ? PALETTE.core() : PALETTE.body(),
                        10 + random.nextInt(6), true);
            }
            if (tick % 2 == 0) {
                for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                    Vector offset = randomUnit(random).multiply(2.5 + random.nextDouble() * radius * 0.4);
                    // Portal particles with count 0 fly from (center + offset) into the center.
                    world.spawnParticle(Particle.PORTAL, center, 0, offset.getX(), offset.getY(), offset.getZ(), 1.0,
                            null, true);
                }
            }
        }

        private void collapse() {
            Player owner = player();
            for (BlockDisplay piece : core) {
                if (piece != null) {
                    piece.remove();
                }
            }
            double blastRadius = params().num("blast_radius");
            BlockDisplay bubble = shell;
            if (bubble != null && bubble.isValid()) {
                // The glass shell bursts outwards like a bubble of light, then pops.
                Displays.animate(bubble, cubePose(SHELL_BURST, new Quaternionf().rotateAxis(0.6f, SHELL_AXIS)), 3);
                ctx().runLater(owner, 3, () -> {
                    if (bubble.isValid()) {
                        Displays.shrinkAway(ctx(), owner, bubble, 3);
                    }
                });
            }
            flingDebris(blastRadius);

            Location floor = floorPoint();
            Vfx.dot(center, Particle.FLASH, PALETTE.core(), true);
            Vfx.dot(center, Particle.SONIC_BOOM, null, true);
            Vfx.sphere(ctx(), center, 0.8, 24, Particle.END_ROD, null, true);
            Vfx.cloud(ctx(), center, Particle.REVERSE_PORTAL, 60, 0.3, 1.0, null, true);
            Vfx.shockwave(ctx(), owner, floor, 0.5, blastRadius, 6, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.5f),
                    true);
            Loop.repeat(ctx(), owner, 0, 1, 5, run -> {
                double r = 0.8 + (blastRadius * 0.75 - 0.8) * (run + 1) / 5.0;
                Vfx.sphere(ctx(), center, r, 36, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.3f), true);
                return true;
            });
            afterglow();
            Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 1.3f);
            Fx.sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.2f, 0.5f);

            double damage = params().num("blast_damage");
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, blastRadius)) {
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                    away = new Vector(Math.cos(angle), 0, Math.sin(angle));
                }
                // The blast is knockback: resistant targets (giant Titan, Earth) are pushed less or not at all.
                if (Motion.push(enemy, away.multiply(BLAST_PUSH).setY(BLAST_LIFT), MAX_SPEED)) {
                    markAirborne(enemy);
                }
                Vfx.dot(Fx.body(enemy), Particle.FLASH, PALETTE.body(), false);
            }
            if (hits > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">블랙홀 붕괴 <white>" + hits + "명 적중!</white></"
                        + COLOR_TAG + ">"));
            }
        }

        /** The debris is thrown out with the blast, tumbling and shrinking away. */
        private void flingDebris(double blastRadius) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (BlockDisplay piece : debris) {
                if (piece == null || !piece.isValid()) {
                    continue;
                }
                Vector out = piece.getLocation().toVector().subtract(center.toVector());
                if (out.lengthSquared() < 1.0E-4) {
                    out = randomUnit(random);
                }
                out.normalize().multiply(blastRadius * (0.8 + random.nextDouble() * 0.4)).setY(0.6 + random.nextDouble());
                piece.setTeleportDuration(6);
                piece.teleport(piece.getLocation().add(out));
                Transformation current = piece.getTransformation();
                Displays.animate(piece, cubePose(current.getScale().x(), randomRotation(random)), 6);
                ctx().runLater(player(), 6, () -> {
                    if (piece.isValid()) {
                        Displays.shrinkAway(ctx(), player(), piece, 4);
                    }
                });
            }
        }

        /** Fading violet motes where the black hole was. */
        private void afterglow() {
            Loop.repeat(ctx(), player(), 2, 1, AFTERGLOW_TICKS, run -> {
                double fade = 1 - (double) run / AFTERGLOW_TICKS;
                int count = (int) Math.round(4 * fade);
                for (int i = 0; i < count; i++) {
                    Location at = Vfx.scatter(center, 1.6).add(0, (ThreadLocalRandom.current().nextDouble() - 0.5) * 1.2, 0);
                    Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, MOTE, false);
                }
                if (run % 3 == 0) {
                    Vfx.cloud(ctx(), center, Particle.REVERSE_PORTAL, 2, 0.4, 0.02, null, false);
                }
                return true;
            });
        }

        /** A point on the tilted accretion disk around the center. */
        private Location diskPoint(double r, double angle, double lift) {
            Vector offset = diskU.clone().multiply(Math.cos(angle) * r).add(diskV.clone().multiply(Math.sin(angle) * r))
                    .add(DISK_NORMAL.clone().multiply(lift));
            return center.clone().add(offset);
        }

        private Location floorPoint() {
            Location floor = center.clone();
            floor.setY(floorY);
            return floor;
        }
    }

    // ------------------------------------------------------------------ display helpers

    /** A cube of edge {@code size} centered on the block display's origin, rotated around its center. */
    private static Transformation cubePose(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    /** A thin box of the given width from the block display's origin along {@code delta}. */
    private static Transformation beamPose(Vector delta, float width) {
        Vector3f direction = new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ());
        float length = direction.length();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction.normalize());
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
        return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
    }

    private static Quaternionf randomRotation(ThreadLocalRandom random) {
        return new Quaternionf().rotateXYZ(random.nextFloat() * 6.28f, random.nextFloat() * 6.28f,
                random.nextFloat() * 6.28f);
    }

    private static Vector randomUnit(ThreadLocalRandom random) {
        Vector vector = new Vector(random.nextGaussian(), random.nextGaussian() * 0.6, random.nextGaussian());
        return vector.lengthSquared() < 1.0E-6 ? new Vector(1, 0, 0) : vector.normalize();
    }
}
