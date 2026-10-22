package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
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
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 팬텀 — the sleepless hunter of the night sky. It is hard to hurt by night or high in the air, soars up and dives on
 * whatever it glares at, and as the ultimate calls a swarm of phantoms that circle over its head and swoop down on
 * every enemy around it.
 *
 * <p>Look: night blue and teal with the phantom's pale green eyes. The phantom's eyes glint in the dark; blows it
 * shrugs off ripple through a teal membrane at its back. 급강하 rises on a gust, hangs in the air with a glowing reticle
 * on its prey and a teal sight line (so the prey can see it coming), then falls like a spear on membrane wings and
 * hits the ground in a teal shockwave. 불면의 밤 summons five phantoms (dark blue bodies, translucent teal wings that
 * flap) circling high above; each one that swoops screeches first, so the prey can still dodge.
 */
public final class PhantomAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("phantom", "팬텀", Material.PHANTOM_MEMBRANE)
            .color(TextColor.color(0x3FB8C4))
            .difficulty(3)
            .passive("야행",
                    "밤이거나 땅에서 {air_height}블록보다 높이 떠 있을 때 받는 피해가 {night_reduction}% 줄어듭니다.")
            .active("급강하",
                    "{soar_height}블록 높이로 솟구쳐 {hover_time}초간 먹잇감을 노려본 뒤, 조준해 둔 적(공중에서 다른 "
                            + "적을 조준하면 그 적, 없으면 그 순간 바라보는 지점, {dive_range}블록 이내)으로 급강하합니다. "
                            + "돌진 경로와 내리꽂힌 자리 {dive_radius}블록 안의 적에게 {dive_damage} 피해를 주고 튕겨 내며, "
                            + "이 비행으로는 낙하 피해를 받지 않습니다.",
                    9)
            .ultimate("불면의 밤",
                    "{night_duration}초간 머리 위로 팬텀 떼를 부릅니다. 주변 {night_radius}블록 안의 적에게 "
                            + "{swoop_interval}초마다 팬텀이 날카로운 울음과 함께 내리꽂혀 {swoop_damage} 피해를 주고 "
                            + "{swoop_slow}초간 구속을 겁니다(울음을 듣고 피할 수 있습니다). 그동안은 낮에도 야행이 "
                            + "적용됩니다.")
            .param("night_reduction", 20)
            .param("air_height", 1.5)
            .param("soar_height", 5)
            .param("hover_time", 0.35)
            .param("dive_range", 16)
            .param("dive_radius", 1.8)
            .param("dive_damage", 5)
            .param("night_duration", 6)
            .param("night_radius", 12)
            .param("swoop_interval", 1.2)
            .param("swoop_damage", 2)
            .param("swoop_slow", 1)
            .build();

    private static final String TEAL = "#3FB8C4";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Pale teal glow, teal membrane, the deep blue of the night sky. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xC9FFF6, 0x3FB8C4, 0x1B2A5C);
    private static final Particle.DustTransition MEMBRANE = PALETTE.glow(0.9f);
    private static final Particle.DustTransition DUSK = PALETTE.fade(1.1f);
    private static final Particle.DustOptions EYE = new Particle.DustOptions(Color.fromRGB(0x9DFF8A), 0.6f);
    private static final Particle.DustOptions NIGHT_DUST = new Particle.DustOptions(Color.fromRGB(0x1B2A5C), 1.3f);

    /** Block types of the visuals; their block data is created on first use (it needs a running server). */
    private static final Material BODY_BLOCK = Material.BLUE_CONCRETE;
    private static final Material WING_BLOCK = Material.CYAN_STAINED_GLASS;
    private static final Material RETICLE_BLOCK = Material.CYAN_STAINED_GLASS;

    /** Caps every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;

    /** Membrane wing outline in body space: x = out from the spine, y = height relative to the shoulders. */
    private static final double[][] WING = {
            {0.15, 0.0}, {0.4, 0.12}, {0.7, 0.2}, {1.0, 0.18}, {1.25, 0.05}, {1.0, -0.05}, {0.75, -0.12},
            {0.45, -0.15}, {0.2, -0.1}};
    private static final double SHOULDER_HEIGHT = 1.35;

    // passive
    private static final long FEEDBACK_COOLDOWN = 10;
    private static final long EYE_PERIOD = 30;

    // active: dive
    /** The dive never goes flatter than this many degrees below the horizon. */
    private static final double MIN_DIVE_PITCH = 20;
    private static final double DIVE_SPEED = 1.6;
    private static final double MIN_DIVE = 1.0;
    /** Extra ticks after the last push during which hits are still checked (client latency). */
    private static final int DIVE_LATENCY_TICKS = 4;
    private static final int MIN_DIVE_TICKS_BEFORE_GROUND = 2;
    private static final double DIVE_KNOCK_SIDE = 0.55;
    private static final double DIVE_KNOCK_UP = 0.5;
    private static final long LANDING_GUARD_TICKS = 120;
    private static final float RETICLE_SIZE = 1.4f;

    // ultimate: sleepless night
    private static final int SWARM_SIZE = 5;
    private static final double ORBIT_HEIGHT = 4.5;
    private static final double ORBIT_RADIUS = 2.6;
    private static final double ORBIT_SPEED = 0.09;
    private static final int GATHER_TICKS = 10;
    private static final int SWOOP_TICKS = 12;
    /** From this swoop tick on the phantom no longer follows its prey: it is committed to where the prey was. */
    private static final int SWOOP_LOCK_TICK = 6;
    private static final int RETURN_TICKS = 10;
    /** Small enough that sprinting away from the screech escapes the strike (walking does not). */
    private static final double SWOOP_HIT_RADIUS = 1.1;
    /** Wing beat of a swarm phantom: the wings swing between {@code MID - SWING} and {@code MID + SWING} degrees. */
    private static final int WING_BEAT_TICKS = 8;
    private static final float WING_MID = 10;
    private static final float WING_SWING = 25;
    /** Each swarm move is interpolated over this many ticks (a little longer than the tick, so it never stalls). */
    private static final int POSE_TICKS = 2;
    private static final int DISPERSE_TICKS = 10;

    private final LandingGuard landing = new LandingGuard();
    private boolean diving;
    /** The ultimate makes it night for the passive. */
    private boolean insomnia;
    private boolean wasNight;
    private long lastFeedback = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        wasNight = isNight();
    }

    @Override
    public void onRevoke() {
        diving = false;
        insomnia = false;
        landing.disarm();
    }

    // ------------------------------------------------------------------ passive: night flight

    private boolean isNight() {
        return insomnia || !player().getWorld().isDayTime();
    }

    /** High in the air: nothing solid (or water) within {@code air_height} blocks below the feet. */
    private boolean isAloft(Player owner) {
        if (owner.isInWater() || owner.isClimbing() || Motion.isGrounded(owner)) {
            return false;
        }
        double height = Math.max(0, params().num("air_height"));
        RayTraceResult ground = owner.getWorld().rayTraceBlocks(owner.getLocation().add(0, 0.05, 0),
                new Vector(0, -1, 0), height + 0.05, FluidCollisionMode.ALWAYS, true);
        return ground == null;
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (landing.absorb(event, ctx().currentTick())) {
            return;
        }
        if (diving && Combat.isFall(event)) {
            event.setCancelled(true);
            return;
        }
        Tag<DamageType> bypass = DamageTypeTags.BYPASSES_INVULNERABILITY;
        if (event.getDamage() <= 0 || bypass != null && bypass.isTagged(event.getDamageSource().getDamageType())) {
            return;
        }
        Player owner = player();
        boolean night = isNight();
        if (!night && !isAloft(owner)) {
            return;
        }
        double reduction = Math.clamp(params().num("night_reduction") / 100.0, 0.0, 1.0);
        if (reduction <= 0) {
            return;
        }
        event.setDamage(event.getDamage() * (1 - reduction));
        long now = ctx().currentTick();
        if (now - lastFeedback >= FEEDBACK_COOLDOWN) {
            lastFeedback = now;
            // The blow ripples through a membrane of night at the phantom's back.
            drawWings(owner, 0.5, 0.8, false);
            Vfx.cloud(ctx(), Fx.body(owner), Particle.DUST_COLOR_TRANSITION, 6, 0.3, 0, DUSK, false);
            Fx.sound(owner.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.5f, 1.4f + Fx.jitter(0.1f));
        }
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        landing.update(owner, tick);
        if (diving) {
            owner.setFallDistance(0);
        }
        if (tick % 10 == 0) {
            boolean night = isNight();
            if (night && !wasNight && !insomnia) {
                nightfall(owner);
            }
            wasNight = night;
        }
        if (tick % EYE_PERIOD == 0 && isNight()) {
            // The phantom's pale green eyes glint in the dark.
            Location eye = owner.getEyeLocation();
            Vector look = Motion.horizontalFacing(eye);
            Vector side = look.clone().crossProduct(new Vector(0, 1, 0)).multiply(0.12);
            Location front = eye.clone().add(look.multiply(0.32)).add(0, 0.02, 0);
            Vfx.dot(front.clone().add(side), Particle.DUST, EYE, false);
            Vfx.dot(front.clone().subtract(side), Particle.DUST, EYE, false);
        }
        if (!diving && tick % 4 == 0 && isAloft(owner)) {
            Vfx.dot(owner.getLocation().add(0, -0.1, 0), Particle.DUST_COLOR_TRANSITION, DUSK, false);
        }
    }

    private void nightfall(Player owner) {
        Location body = Fx.body(owner);
        Vfx.helix(ctx(), owner.getLocation(), 0.55, owner.getHeight() + 0.3, 1.25, 2, 10, 0,
                Particle.DUST_COLOR_TRANSITION, DUSK, false);
        Fx.sound(body, Sound.ENTITY_PHANTOM_AMBIENT, 0.6f, 0.8f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + TEAL + ">☾ 밤이 내렸습니다</" + TEAL + "> <gray>야행: 받는 피해 -"
                + AbilityInfo.formatNumber(params().num("night_reduction")) + "%</gray>"));
    }

    // ------------------------------------------------------------------ active: dive

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 날아오를 수 없습니다");
        }
        if (diving) {
            return ActivationResult.fail("이미 급강하 중입니다");
        }
        double up = Motion.launchSpeed(Math.max(0.5, params().num("soar_height")));
        int ascent = ascentTicks(up);
        owner.setVelocity(Motion.clamp(new Vector(0, up, 0), MAX_SPEED));
        owner.setFallDistance(0);
        diving = true;
        long hover = Math.max(1, params().ticks("hover_time"));
        landing.arm(ctx().currentTick(), ascent + hover + LANDING_GUARD_TICKS);
        LivingEntity prey = ctx().findTarget(owner, params().num("dive_range"));
        if (prey instanceof Player hidden && !owner.canSee(hidden)) {
            prey = null;
        }
        takeOff(owner);
        Dive dive = new Dive(owner, prey, ascent, (int) hover);
        Loop.repeat(ctx(), owner, 1, 1, ascent + hover + dive.maxDiveTicks(), dive, dive::end);
        return ActivationResult.success();
    }

    /** Take-off: a gust bursting off the ground, the membrane wings unfold. */
    private void takeOff(Player owner) {
        Location feet = owner.getLocation().add(0, 0.1, 0);
        Vfx.shockwave(ctx(), owner, feet, 0.3, 2.0, 4, Particle.DUST_COLOR_TRANSITION, DUSK, false);
        Vfx.cloud(ctx(), feet, Particle.GUST, 1, 0.1, 0, null, false);
        Vfx.cloud(ctx(), feet, Particle.CLOUD, 6, 0.3, 0.05, null, false);
        drawWings(owner, 0.2, 1.1, false);
        Fx.sound(feet, Sound.ENTITY_PHANTOM_FLAP, 1.0f, 0.8f + Fx.jitter(0.08f));
        Fx.sound(feet, Sound.ENTITY_BREEZE_JUMP, 0.8f, 1.1f + Fx.jitter(0.08f));
    }

    private enum DivePhase { SOAR, HOVER, DIVE }

    /** Soar for the computed ascent, hang in the air glaring, then fall on the point the phantom looks at. */
    private final class Dive implements LongPredicate {

        private final Player owner;
        /** The enemy aimed at when the dive began; the dive locks onto it unless another enemy is aimed at. */
        private final @Nullable LivingEntity prey;
        private final int ascent;
        private final int hover;
        private final double range;
        private final double radius;
        private final Set<UUID> hit = new HashSet<>();
        private DivePhase phase = DivePhase.SOAR;
        private @Nullable BlockDisplay reticle;
        private Vector direction = new Vector(0, -1, 0);
        private Location target;
        private Location previous;
        private int pushes;
        private double speed;
        private int diveTicks;
        private boolean landed;

        Dive(Player owner, @Nullable LivingEntity prey, int ascent, int hover) {
            this.owner = owner;
            this.prey = prey;
            this.ascent = ascent;
            this.hover = hover;
            this.range = Math.max(MIN_DIVE, params().num("dive_range"));
            this.radius = Math.max(0.5, params().num("dive_radius"));
            this.target = owner.getLocation();
            this.previous = owner.getLocation();
        }

        /** The longest dive (every push plus the latency allowance). */
        long maxDiveTicks() {
            return (long) Math.ceil(range / DIVE_SPEED) + DIVE_LATENCY_TICKS + 2;
        }

        @Override
        public boolean test(long run) {
            if (owner.isInsideVehicle()) {
                return false;
            }
            owner.setFallDistance(0);
            if (run < ascent) {
                soar(run);
                return true;
            }
            if (run < ascent + hover) {
                if (phase != DivePhase.HOVER) {
                    phase = DivePhase.HOVER;
                    beginHover();
                }
                hold(run - ascent);
                return true;
            }
            if (phase != DivePhase.DIVE) {
                phase = DivePhase.DIVE;
                if (!launch()) {
                    return false;
                }
            }
            return dive();
        }

        private void soar(long run) {
            Location feet = owner.getLocation();
            Vfx.dot(feet, Particle.DUST_COLOR_TRANSITION, DUSK, false);
            if (run % 2 == 0) {
                drawWings(owner, 0.35 + 0.25 * Math.sin(run * 0.9), 1.0, false);
            }
        }

        private void beginHover() {
            Location eye = owner.getEyeLocation();
            Fx.sound(eye, Sound.ENTITY_PHANTOM_AMBIENT, 1.2f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.ENTITY_PHANTOM_FLAP, 0.8f, 0.6f);
            Location point = divePoint();
            reticle = Displays.block(ctx(), owner, point.clone().add(0, 0.05, 0), RETICLE_BLOCK.createBlockData(),
                    reticlePose(0.2f, 45), hover + 20L);
            BlockDisplay mark = reticle;
            mark.setTeleportDuration(1);
            Displays.outline(mark, PALETTE.body());
        }

        /** The flat reticle square, {@code size} across, turned {@code yaw} degrees in its transformation. */
        private Transformation reticlePose(float size, float yaw) {
            return Displays.transform(0, 0, 0, size, 0.02f, size, yaw, 0, 0);
        }

        /** Hanging in the air: hold still and show where the dive would land (enemies can read it). */
        private void hold(long run) {
            owner.setVelocity(new Vector());
            Location point = divePoint();
            BlockDisplay mark = reticle;
            if (mark != null && mark.isValid()) {
                // The reticle follows the gaze and turns; it is moved without taking any rotation from the point.
                Displays.moveTo(mark, point.clone().add(0, 0.05, 0));
                if (run > 0) {
                    float size = RETICLE_SIZE * (float) Math.min(1.0, (run + 1) / (double) hover);
                    Displays.animate(mark, reticlePose(Math.max(0.2f, size), 45 + run * 12), 1);
                }
            }
            Location eye = owner.getEyeLocation();
            Vfx.line(ctx(), eye, point, 1.5, Particle.DUST_COLOR_TRANSITION, MEMBRANE, true);
            Vfx.ring(ctx(), point.clone().add(0, 0.1, 0), radius, (int) Math.max(10, radius * 7), run * 0.3,
                    Particle.DUST_COLOR_TRANSITION, DUSK, true);
            drawWings(owner, 0.1 + 0.15 * Math.sin(run * 1.2), 1.15, false);
        }

        /** Locks the dive onto the point looked at right now. False if there is nowhere to dive to. */
        private boolean launch() {
            Location from = owner.getLocation();
            target = divePoint();
            BlockDisplay mark = reticle;
            if (mark != null && mark.isValid()) {
                Displays.shrinkAway(ctx(), owner, mark, 6);
            }
            Vector delta = target.toVector().subtract(from.toVector());
            double distance = delta.length();
            if (distance < MIN_DIVE) {
                return false;
            }
            direction = delta.multiply(1 / distance);
            double reach = Spots.arenaReach(ctx(), from, direction, distance);
            if (reach < MIN_DIVE) {
                owner.sendActionBar(Text.mm("<gray>자기장 밖으로는 급강하할 수 없습니다</gray>"));
                return false;
            }
            pushes = Math.max(1, (int) Math.ceil(reach / DIVE_SPEED));
            speed = reach / pushes;
            previous = from;
            Location body = Fx.body(owner);
            Vfx.burst(ctx(), body, PALETTE, 0.7, Particle.SQUID_INK, false);
            Fx.sound(body, Sound.ENTITY_PHANTOM_SWOOP, 1.4f, 0.8f + Fx.jitter(0.06f));
            Fx.sound(body, Sound.ITEM_ELYTRA_FLYING, 0.5f, 1.6f);
            return true;
        }

        private boolean dive() {
            diveTicks++;
            Location now = owner.getLocation();
            if (diveTicks <= pushes) {
                owner.setVelocity(Motion.clamp(direction.clone().multiply(speed), MAX_SPEED));
            } else if (diveTicks == pushes + 1) {
                owner.setVelocity(direction.clone().multiply(0.15));
            }
            sweep(previous, now);
            trail(previous, now);
            previous = now;
            boolean grounded = Motion.isGrounded(owner) || owner.isInWater();
            if (grounded && diveTicks > MIN_DIVE_TICKS_BEFORE_GROUND || diveTicks >= pushes + DIVE_LATENCY_TICKS) {
                impact(now);
                return false;
            }
            return true;
        }

        /** Damages every enemy within the dive radius of the segment travelled since the last tick. */
        private void sweep(Location from, Location to) {
            double half = owner.getHeight() * 0.5;
            Vector a = from.toVector().add(new Vector(0, half, 0));
            Vector b = to.toVector().add(new Vector(0, half, 0));
            Location mid = a.clone().add(b).multiply(0.5).toLocation(to.getWorld());
            for (LivingEntity enemy : ctx().enemiesNear(owner, mid, a.distance(b) / 2 + radius)) {
                Vector center = Fx.body(enemy).toVector();
                if (!hit.contains(enemy.getUniqueId())
                        && distanceToSegment(center, a, b) <= radius + enemy.getWidth() / 2) {
                    strike(enemy, a);
                }
            }
        }

        private void strike(LivingEntity enemy, Vector lineStart) {
            hit.add(enemy.getUniqueId());
            Combat.hit(ctx(), owner, enemy, params().num("dive_damage"));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            Vector flat = Motion.horizontal(direction);
            Vector side = new Vector(-flat.getZ(), 0, flat.getX());
            Vector offset = enemy.getLocation().toVector().subtract(lineStart);
            if (offset.dot(side) < 0) {
                side.multiply(-1);
            }
            if (side.lengthSquared() < 1.0E-6) {
                Vector out = Motion.horizontal(enemy.getLocation().toVector().subtract(target.toVector()));
                double angle = ThreadLocalRandom.current().nextDouble(TAU);
                side = out.lengthSquared() > 1.0E-6 ? out : new Vector(Math.cos(angle), 0, Math.sin(angle));
            }
            Vector knock = side.multiply(DIVE_KNOCK_SIDE).add(flat.multiply(0.3)).setY(DIVE_KNOCK_UP);
            Motion.push(enemy, knock, MAX_SPEED);
            Location at = Fx.body(enemy);
            Vfx.burst(ctx(), at, PALETTE, 0.6, Particle.SQUID_INK, false);
            Fx.sound(at, Sound.ENTITY_PHANTOM_BITE, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.7f, 0.8f + Fx.jitter(0.08f));
        }

        /** A spear of night: a teal streak along the path and the membrane wings swept back. */
        private void trail(Location from, Location feet) {
            Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
            if (diveTicks <= pushes) {
                Vfx.line(ctx(), from.clone().add(0, owner.getHeight() * 0.5, 0), body, 2.5,
                        Particle.DUST_COLOR_TRANSITION, MEMBRANE, false);
                Vfx.cloud(ctx(), body, Particle.DUST, 3, 0.2, 0, NIGHT_DUST, false);
                drawWings(owner, 1.1, 1.2, false);
                if (diveTicks % 2 == 0) {
                    Fx.sound(feet, Sound.ENTITY_PHANTOM_FLAP, 0.6f, 1.3f + Fx.jitter(0.1f));
                }
            }
        }

        /** The dive hits home: a shockwave of night and everyone near the point of impact is struck. */
        private void impact(Location feet) {
            landed = true;
            owner.setFallDistance(0);
            Location ground = feet.clone().add(0, 0.12, 0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, ground.clone().add(0, 0.5, 0), radius)) {
                if (!hit.contains(enemy.getUniqueId())) {
                    strike(enemy, feet.toVector().subtract(direction));
                }
            }
            Vfx.burst(ctx(), ground.clone().add(0, 0.3, 0), PALETTE, 0.9, Particle.SQUID_INK, false);
            Vfx.shockwave(ctx(), owner, ground, 0.3, radius + 0.6, 5, Particle.DUST_COLOR_TRANSITION, MEMBRANE,
                    false);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.1, 0), 0.2, radius, 5, Particle.DUST, NIGHT_DUST,
                    false);
            Vfx.cloud(ctx(), ground, Particle.CLOUD, 8, 0.4, 0.06, null, false);
            Fx.sound(ground, Sound.ITEM_MACE_SMASH_GROUND, 0.9f, 1.1f + Fx.jitter(0.08f));
            Fx.sound(ground, Sound.ENTITY_PHANTOM_BITE, 1.0f, 0.6f + Fx.jitter(0.05f));
            if (!hit.isEmpty()) {
                owner.sendActionBar(Text.mm("<" + TEAL + ">☾ 급강하</" + TEAL + "> <white>" + hit.size()
                        + "명 적중</white>"));
            }
        }

        /** Runs when the loop ends by itself: the flight is over, the landing stays protected until settled. */
        void end() {
            diving = false;
            BlockDisplay mark = reticle;
            if (mark != null && mark.isValid()) {
                mark.remove();
            }
            if (phase == DivePhase.DIVE && !landed) {
                Location feet = owner.getLocation();
                Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 0.8, 10, 0, Particle.DUST_COLOR_TRANSITION, DUSK, false);
            }
            landing.arm(ctx().currentTick(), LANDING_GUARD_TICKS);
            owner.setFallDistance(0);
        }

        /**
         * Where the phantom would dive now: the enemy aimed at from up here, else the enemy aimed at when the dive
         * began (while it is still a visible enemy in reach), else where the view ray (never flatter than
         * {@link #MIN_DIVE_PITCH} below the horizon) meets a block, else the end of that ray.
         */
        private Location divePoint() {
            World world = owner.getWorld();
            Location eye = owner.getEyeLocation();
            LivingEntity aimed = ctx().findTarget(owner, range);
            if (isVisiblePrey(aimed)) {
                return aimed.getLocation();
            }
            if (isVisiblePrey(prey) && prey.getLocation().distance(owner.getLocation()) <= range) {
                return prey.getLocation();
            }
            double pitch = Math.toRadians(Math.max(MIN_DIVE_PITCH, eye.getPitch()));
            double yaw = Math.toRadians(eye.getYaw());
            Vector look = new Vector(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
            RayTraceResult ground = world.rayTraceBlocks(eye, look, range, FluidCollisionMode.NEVER, true);
            if (ground == null) {
                return eye.clone().add(look.multiply(range));
            }
            Location point = ground.getHitPosition().toLocation(world);
            if (ground.getHitBlockFace() != null && ground.getHitBlockFace() != BlockFace.UP) {
                point.subtract(look.clone().multiply(0.5));
            }
            return point;
        }

        private boolean isVisiblePrey(@Nullable LivingEntity enemy) {
            return enemy != null && Combat.isTargetable(ctx(), owner, enemy) && enemy.getWorld().equals(owner.getWorld())
                    && !(enemy instanceof Player hidden && !owner.canSee(hidden));
        }
    }

    // ------------------------------------------------------------------ ultimate: sleepless night

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = params().num("night_radius");
        if (preys(owner, radius).isEmpty()) {
            return ActivationResult.fail("주변 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        new Swarm(owner).summon();
        return ActivationResult.success();
    }

    /** Enemies around the owner it can see (a stealthed enemy is never hunted, which would reveal it). */
    private List<LivingEntity> preys(Player owner, double radius) {
        List<LivingEntity> found = ctx().enemiesNear(owner, owner.getLocation(), radius);
        found.removeIf(enemy -> enemy instanceof Player hidden && !owner.canSee(hidden));
        return found;
    }

    private enum Flight { ORBIT, SWOOP, RETURN }

    /** One phantom of the swarm: a dark blue body with two flapping teal glass wings, all on one anchor. */
    private final class Bat {
        final BlockDisplay body;
        final BlockDisplay leftWing;
        final BlockDisplay rightWing;
        final int slot;
        Flight flight = Flight.ORBIT;
        int age;
        @Nullable LivingEntity prey;
        Location from;
        Location aim;
        int beat;

        /** Spawns the phantom at {@code at}, heading where {@code at} looks (in its transformations only). */
        Bat(Player owner, int slot, Location at, long lifetime) {
            this.slot = slot;
            this.from = at.clone();
            this.aim = at.clone();
            Quaternionf heading = headingOf(at);
            body = Displays.block(ctx(), owner, at, BODY_BLOCK.createBlockData(), oriented(bodyShape(), heading),
                    lifetime);
            BlockData membrane = WING_BLOCK.createBlockData();
            leftWing = Displays.block(ctx(), owner, at, membrane, oriented(wing(true, WING_MID), heading), lifetime);
            rightWing = Displays.block(ctx(), owner, at, membrane, oriented(wing(false, WING_MID), heading), lifetime);
            for (BlockDisplay part : parts()) {
                part.setTeleportDuration(POSE_TICKS);
                part.setViewRange(2.0f);
            }
        }

        List<BlockDisplay> parts() {
            return List.of(body, leftWing, rightWing);
        }

        boolean isValid() {
            return body.isValid() && leftWing.isValid() && rightWing.isValid();
        }

        /**
         * Flies to {@code at}, heading where {@code at} looks, with the wings one step further in their beat. The
         * displays keep yaw and pitch 0: the heading is applied in their transformations.
         */
        void moveTo(Location at) {
            beat++;
            Quaternionf heading = headingOf(at);
            float angle = WING_MID + WING_SWING * (float) Math.sin(beat * TAU / WING_BEAT_TICKS + slot);
            for (BlockDisplay part : parts()) {
                Displays.moveTo(part, at);
            }
            Displays.animate(body, oriented(bodyShape(), heading), POSE_TICKS);
            Displays.animate(leftWing, oriented(wing(true, angle), heading), POSE_TICKS);
            Displays.animate(rightWing, oriented(wing(false, angle), heading), POSE_TICKS);
        }
    }

    /**
     * The swarm: phantoms circle over the owner's head; every interval each visible enemy in reach (up to the swarm
     * size) is assigned a phantom that screeches and swoops on it, committing to its position a few ticks before
     * the strike, then climbs back into the circle.
     */
    private final class Swarm implements LongPredicate {

        private final Player owner;
        private final List<Bat> bats = new ArrayList<>();
        private final long duration;
        private final long interval;
        private final double radius;
        private double phase;
        private int strikes;

        Swarm(Player owner) {
            this.owner = owner;
            this.duration = Math.max(20, params().ticks("night_duration"));
            this.interval = Math.max(SWOOP_TICKS, params().ticks("swoop_interval"));
            this.radius = Math.max(1, params().num("night_radius"));
            this.phase = ThreadLocalRandom.current().nextDouble(TAU);
        }

        void summon() {
            insomnia = true;
            Location center = owner.getLocation();
            long lifetime = duration + SWOOP_TICKS + RETURN_TICKS + DISPERSE_TICKS + 20;
            for (int i = 0; i < SWARM_SIZE; i++) {
                // They rise out of the dark around the owner's feet and gather overhead.
                double angle = phase + i * TAU / SWARM_SIZE;
                Location start = center.clone().add(Math.cos(angle) * 1.2, 0.6, Math.sin(angle) * 1.2);
                start.setDirection(new Vector(-Math.sin(angle), 0.8, Math.cos(angle)));
                bats.add(new Bat(owner, i, start, lifetime));
            }
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.1, 0), 0.5, radius, 8, Particle.DUST_COLOR_TRANSITION,
                    DUSK, true);
            Vfx.cloud(ctx(), center.clone().add(0, 1, 0), Particle.SQUID_INK, 20, 0.8, 0.05, null, true);
            Vfx.helix(ctx(), center, 1.0, ORBIT_HEIGHT, 1.5, 3, 14, phase, Particle.DUST_COLOR_TRANSITION, MEMBRANE,
                    true);
            Fx.sound(center, Sound.ENTITY_PHANTOM_AMBIENT, 1.5f, 0.6f);
            Fx.sound(center, Sound.ENTITY_PHANTOM_FLAP, 1.2f, 0.7f);
            Fx.sound(center, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 1.0f, 0.8f);
            owner.sendActionBar(Text.mm("<" + TEAL + "><b>☾ 불면의 밤</b></" + TEAL + "> <gray>팬텀 떼가 깨어납니다</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, duration + SWOOP_TICKS + 4, this, this::disperse);
        }

        @Override
        public boolean test(long tick) {
            phase += ORBIT_SPEED;
            if (tick >= GATHER_TICKS && tick < duration && (tick - GATHER_TICKS) % interval == 0) {
                wave();
            }
            boolean swooping = false;
            for (Bat bat : bats) {
                if (!bat.isValid()) {
                    continue;
                }
                fly(bat, tick);
                swooping |= bat.flight != Flight.ORBIT;
            }
            ambience(tick);
            return tick < duration || swooping;
        }

        /** Sends a free phantom after each visible enemy in reach, nearest first. */
        private void wave() {
            List<LivingEntity> targets = preys(owner, radius);
            int next = 0;
            for (Bat bat : bats) {
                if (next >= targets.size()) {
                    break;
                }
                if (bat.flight != Flight.ORBIT || !bat.isValid()) {
                    continue;
                }
                LivingEntity prey = targets.get(next++);
                bat.flight = Flight.SWOOP;
                bat.age = 0;
                bat.prey = prey;
                bat.from = bat.body.getLocation();
                bat.aim = prey.getLocation().add(0, prey.getHeight() * 0.5, 0);
                // The tell: a screech from the diving phantom and a teal ring under the prey.
                Fx.sound(bat.from, Sound.ENTITY_PHANTOM_SWOOP, 1.3f, 0.9f + Fx.jitter(0.1f));
                Fx.sound(prey.getLocation(), Sound.ENTITY_PHANTOM_AMBIENT, 0.7f, 1.3f + Fx.jitter(0.1f));
                Vfx.ring(ctx(), prey.getLocation().add(0, 0.1, 0), SWOOP_HIT_RADIUS, 12, 0,
                        Particle.DUST_COLOR_TRANSITION, MEMBRANE, true);
            }
        }

        private void fly(Bat bat, long tick) {
            switch (bat.flight) {
                case ORBIT -> {
                    Location slot = orbitPoint(bat.slot, tick);
                    Vector heading = new Vector(-Math.sin(slotAngle(bat.slot)), 0, Math.cos(slotAngle(bat.slot)));
                    if (tick < GATHER_TICKS) {
                        // Gathering: rise from the ground into the circle.
                        double t = (double) (tick + 1) / GATHER_TICKS;
                        slot = lerp(bat.from, slot, t * t);
                        heading.setY(0.6 * (1 - t));
                    }
                    slot.setDirection(heading);
                    bat.moveTo(slot);
                    if (tick % 6 == bat.slot) {
                        eyes(slot);
                    }
                }
                case SWOOP -> swoop(bat);
                case RETURN -> {
                    bat.age++;
                    double t = Math.min(1.0, (double) bat.age / RETURN_TICKS);
                    Location home = orbitPoint(bat.slot, tick);
                    Location at = lerp(bat.from, home, t);
                    at.add(0, Math.sin(t * Math.PI) * 1.2, 0);
                    Vector heading = home.toVector().subtract(bat.from.toVector());
                    at.setDirection(heading.lengthSquared() > 1.0E-6 ? heading.setY(Math.max(0.2, heading.getY()))
                            : new Vector(0, 0, 1));
                    bat.moveTo(at);
                    if (bat.age >= RETURN_TICKS) {
                        bat.flight = Flight.ORBIT;
                    }
                }
                default -> {
                }
            }
        }

        /** A swoop: an arc from the circle down onto the prey (followed until the lock tick), then the strike. */
        private void swoop(Bat bat) {
            bat.age++;
            LivingEntity prey = bat.prey;
            if (bat.age <= SWOOP_LOCK_TICK && prey != null && Combat.isTargetable(ctx(), owner, prey)
                    && !(prey instanceof Player hidden && !owner.canSee(hidden))) {
                bat.aim = prey.getLocation().add(0, prey.getHeight() * 0.5, 0);
            }
            double t = Math.min(1.0, (double) bat.age / SWOOP_TICKS);
            // Quadratic arc: out and up first, then steeply down onto the aim point.
            Location control = bat.from.clone().add(bat.aim.clone().subtract(bat.from).multiply(0.3)).add(0, 1.5, 0);
            Location at = bezier(bat.from, control, bat.aim, t);
            Location ahead = bezier(bat.from, control, bat.aim, Math.min(1.0, t + 0.1));
            Vector heading = ahead.toVector().subtract(at.toVector());
            if (heading.lengthSquared() > 1.0E-6) {
                at.setDirection(heading);
            }
            bat.moveTo(at);
            Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, MEMBRANE, false);
            if (bat.age >= SWOOP_TICKS) {
                strikeAt(bat.aim);
                bat.flight = Flight.RETURN;
                bat.age = 0;
                bat.from = bat.aim.clone();
                bat.prey = null;
            }
        }

        /** The phantom strikes whoever is still at the point it committed to. */
        private void strikeAt(Location point) {
            double damage = params().num("swoop_damage");
            int slow = (int) Math.min(Integer.MAX_VALUE, params().ticks("swoop_slow"));
            boolean any = false;
            for (LivingEntity enemy : ctx().enemiesNear(owner, point, SWOOP_HIT_RADIUS)) {
                any = true;
                Combat.hit(ctx(), owner, enemy, damage);
                if (Combat.isTargetable(ctx(), owner, enemy) && slow > 0) {
                    enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 0, false, true, true));
                }
                Location body = Fx.body(enemy);
                Vfx.burst(ctx(), body, PALETTE, 0.5, Particle.SQUID_INK, true);
                Fx.sound(body, Sound.ENTITY_PHANTOM_BITE, 1.0f, 0.9f + Fx.jitter(0.1f));
            }
            if (any) {
                strikes++;
            } else {
                // A miss: the phantom snaps at empty air.
                Vfx.cloud(ctx(), point, Particle.DUST, 4, 0.2, 0, NIGHT_DUST, false);
                Fx.sound(point, Sound.ENTITY_PHANTOM_FLAP, 0.6f, 1.6f + Fx.jitter(0.1f));
            }
        }

        /** The night around the owner: a slow dark ring at the edge of the hunt and stars twinkling overhead. */
        private void ambience(long tick) {
            Location center = owner.getLocation();
            if (tick % 8 == 0 && tick < duration) {
                Vfx.ring(ctx(), center.clone().add(0, 0.12, 0), radius, (int) Math.min(48, Math.max(16, radius * 3.5)),
                        tick * 0.02, Particle.DUST_COLOR_TRANSITION, DUSK, true);
            }
            if (tick % 3 == 0) {
                Location star = Vfx.scatter(center.clone().add(0, ORBIT_HEIGHT + 1.5, 0), ORBIT_RADIUS + 1.5);
                Vfx.dot(star, Particle.END_ROD, null, false);
            }
            if (tick % 30 == 15) {
                Fx.sound(center.clone().add(0, ORBIT_HEIGHT, 0), Sound.ENTITY_PHANTOM_AMBIENT, 0.6f,
                        0.9f + Fx.jitter(0.15f));
            }
        }

        private void eyes(Location at) {
            Vector look = at.getDirection();
            Location front = at.clone().add(look.multiply(0.5)).add(0, 0.05, 0);
            Vfx.dot(front, Particle.DUST, EYE, false);
        }

        /** The night ends: the phantoms climb away into the dark. */
        private void disperse() {
            insomnia = false;
            for (Bat bat : bats) {
                if (!bat.isValid()) {
                    continue;
                }
                Location away = bat.body.getLocation().add(ThreadLocalRandom.current().nextDouble(-3, 3), 6,
                        ThreadLocalRandom.current().nextDouble(-3, 3));
                for (BlockDisplay part : bat.parts()) {
                    part.setTeleportDuration(DISPERSE_TICKS);
                    Displays.moveTo(part, away);
                    Displays.shrinkAway(ctx(), owner, part, DISPERSE_TICKS);
                }
            }
            Location center = owner.getLocation();
            Fx.sound(center, Sound.ENTITY_PHANTOM_FLAP, 1.0f, 0.6f);
            Fx.sound(center, Sound.ENTITY_PHANTOM_DEATH, 0.4f, 1.4f);
            if (owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + TEAL + ">☾ 불면의 밤</" + TEAL + "> <gray>이 끝났습니다</gray>"
                        + (strikes > 0 ? " <white>급습 " + strikes + "회</white>" : "")));
            }
        }

        private double slotAngle(int slot) {
            return phase + slot * TAU / SWARM_SIZE;
        }

        private Location orbitPoint(int slot, long tick) {
            double angle = slotAngle(slot);
            double bob = Math.sin(tick * 0.2 + slot) * 0.25;
            return owner.getLocation().add(Math.cos(angle) * ORBIT_RADIUS, ORBIT_HEIGHT + bob,
                    Math.sin(angle) * ORBIT_RADIUS);
        }
    }

    // ------------------------------------------------------------------ visuals

    /**
     * Two teal membrane wings drawn with particles behind the owner's shoulders.
     *
     * @param sweep how far the wings are swept back, in radians (0 = spread flat to the sides)
     * @param size  wing span multiplier on top of the owner's scale
     */
    private static void drawWings(Player owner, double sweep, double size, boolean force) {
        double scale = Body.scaleOf(owner) * size;
        double yaw = Math.toRadians(owner.getBodyYaw());
        Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vector back = new Vector(Math.sin(yaw), 0, -Math.cos(yaw));
        Location shoulder = owner.getLocation().add(0, SHOULDER_HEIGHT * Body.scaleOf(owner), 0)
                .add(back.clone().multiply(0.2));
        for (int side = -1; side <= 1; side += 2) {
            Vector out = right.clone().multiply(side * Math.cos(sweep)).add(back.clone().multiply(Math.sin(sweep)));
            for (int i = 0; i < WING.length; i++) {
                double[] point = WING[i];
                Location at = shoulder.clone().add(out.clone().multiply(point[0] * scale)).add(0, point[1] * scale, 0);
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, i < 5 ? MEMBRANE : DUSK, force);
            }
        }
    }

    /** A box in a display's own frame (+Z forward, +X left) from corner {@code (x, y, z)}. */
    private static Transformation box(float x, float y, float z, float sx, float sy, float sz) {
        return new Transformation(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(sx, sy, sz),
                new Quaternionf());
    }

    /** The body of a swarm phantom in its own frame (+Z forward, +X left): a slim dark blue bar. */
    private static Transformation bodyShape() {
        return box(-0.18f, -0.1f, -0.45f, 0.36f, 0.2f, 0.9f);
    }

    /**
     * The rotation that points a display's +Z where {@code at} looks, in Minecraft's convention (yaw 0 = +Z, 90 = -X;
     * pitch positive = looking down), the same order the client applies an entity's own rotation in.
     */
    static Quaternionf headingOf(Location at) {
        return new Quaternionf().rotateY((float) Math.toRadians(-at.getYaw()))
                .rotateX((float) Math.toRadians(at.getPitch()));
    }

    /** {@code local} (a shape in a frame where +Z is forward) turned by {@code heading} around the display's origin. */
    private static Transformation oriented(Transformation local, Quaternionf heading) {
        return new Transformation(new Quaternionf(heading).transform(new Vector3f(local.getTranslation())),
                new Quaternionf(heading).mul(local.getLeftRotation()), new Vector3f(local.getScale()),
                new Quaternionf(local.getRightRotation()));
    }

    /** A wing of a swarm phantom, hinged at the body's side and raised by {@code angle} degrees. */
    private static Transformation wing(boolean left, float angle) {
        float side = left ? 1 : -1;
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.toRadians(angle) * side);
        Vector3f pivot = new Vector3f(0.15f * side, 0, 0);
        Vector3f corner = new Vector3f(left ? 0 : -0.9f, -0.015f, -0.3f);
        Vector3f translation = rotation.transform(new Vector3f(corner)).add(pivot);
        return new Transformation(translation, rotation, new Vector3f(0.9f, 0.03f, 0.55f), new Quaternionf());
    }

    private static Location lerp(Location a, Location b, double t) {
        return a.clone().add(b.clone().subtract(a).multiply(t));
    }

    private static Location bezier(Location a, Location control, Location b, double t) {
        Location ab = lerp(a, control, t);
        Location cb = lerp(control, b, t);
        return lerp(ab, cb, t);
    }

    private static double distanceToSegment(Vector point, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double lengthSquared = ab.lengthSquared();
        if (lengthSquared < 1.0E-9) {
            return point.distance(a);
        }
        double t = Math.clamp(point.clone().subtract(a).dot(ab) / lengthSquared, 0.0, 1.0);
        return point.distance(a.clone().add(ab.multiply(t)));
    }

    private static int ascentTicks(double velocity) {
        int ticks = 0;
        double v = velocity;
        while (v > 0 && ticks < 200) {
            v = (v - Motion.GRAVITY) * Motion.DRAG;
            ticks++;
        }
        return ticks;
    }
}
