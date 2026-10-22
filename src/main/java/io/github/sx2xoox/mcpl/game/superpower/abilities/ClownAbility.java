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
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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
 * 광대 — a circus act. Kills and ultimate hits make the clown burst out laughing and sprint off in a trail of
 * confetti, the active juggles a fan of bouncy balls that ricochet off the ground, walls and enemies, and the
 * ultimate fires the clown out of a striped circus cannon onto a marked spot, where a giant firework goes off.
 *
 * <p>The palette is a rainbow: every ball has its own two colors (two block displays, an outer cube and an inner
 * cube turned onto its corner, so it reads as a beach ball) and its own note when it bounces. The firework is made of
 * particles only (no firework entity, no explosion): rainbow shock rings, colored star spheres and aerial shells.
 */
public final class ClownAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("clown", "광대", Material.FIREWORK_ROCKET)
            .color(TextColor.color(0xFF5FA2))
            .difficulty(2)
            .passive("웃음",
                    "적을 처치하거나 궁극기로 적을 맞히면 깔깔 웃으며 {laugh_duration}초간 신속 {laugh_level}단계를 "
                            + "얻습니다. 웃는 동안 무지개 색종이를 흩뿌리며 달립니다.")
            .active("저글링",
                    "통통 튀는 공 {balls}개를 부채꼴로 던집니다. 공은 땅과 벽에 튕기며 {ball_duration}초간 굴러다니고, "
                            + "닿은 적에게 {ball_damage} 피해를 주고 튕겨냅니다(공 하나는 같은 적을 한 번만 맞힙니다).",
                    8)
            .ultimate("서커스 대포",
                    "{ult_windup}초간 대포에 들어갔다가 조준한 적이나 지점({ult_range}블록 이내)으로 자신을 쏘아 보냅니다. "
                            + "떨어진 자리에서 거대한 폭죽이 터져 반경 {ult_radius}블록의 적에게 {ult_damage} 피해를 주고 "
                            + "약 {ult_launch}블록 띄웁니다. 이 비행으로는 낙하 피해를 받지 않습니다.")
            .param("laugh_duration", 5)
            .param("laugh_level", 2)
            .param("balls", 3)
            .param("ball_duration", 3)
            .param("ball_damage", 2)
            .param("ult_windup", 0.75)
            .param("ult_range", 22)
            .param("ult_radius", 4.5)
            .param("ult_damage", 7)
            .param("ult_launch", 3)
            .build();

    private static final String RAINBOW = "<gradient:#FF4D4D:#FF9F1C:#FFE14D:#4DDB6B:#4DA6FF:#B45CFF>";
    /** The rainbow: red, orange, yellow, green, blue, violet. */
    private static final Color[] COLORS = {
            Color.fromRGB(0xFF4D4D), Color.fromRGB(0xFF9F1C), Color.fromRGB(0xFFE14D),
            Color.fromRGB(0x4DDB6B), Color.fromRGB(0x4DA6FF), Color.fromRGB(0xB45CFF),
    };
    /** Pink core, hot pink body, deep magenta edge, for the shared impact bursts. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF0F7, 0xFF5FA2, 0x8E1B5A);
    private static final double TAU = Math.PI * 2;
    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;

    // passive: laughter
    private static final int LAUGH_TEXT_TICKS = 22;

    // active: juggling
    /** Outer and inner colors of the balls, in order (the list repeats for more balls). */
    private static final Material[][] BALL_COLORS = {
            {Material.RED_CONCRETE, Material.YELLOW_CONCRETE},
            {Material.LIGHT_BLUE_CONCRETE, Material.WHITE_CONCRETE},
            {Material.LIME_CONCRETE, Material.MAGENTA_CONCRETE},
            {Material.ORANGE_CONCRETE, Material.PURPLE_CONCRETE},
            {Material.PINK_CONCRETE, Material.CYAN_CONCRETE},
            {Material.YELLOW_CONCRETE, Material.BLUE_CONCRETE},
    };
    /** Each ball's note (do, mi, sol, ...) as a note block pitch. */
    private static final float[] BALL_NOTES = {0.7f, 0.89f, 1.06f, 1.41f, 1.78f, 2.0f};
    private static final double BALL_RADIUS = 0.19;
    private static final float BALL_SIZE = 0.38f;
    private static final float BALL_INNER = 0.31f;
    private static final Quaternionf INNER_ORIENTATION = new Quaternionf()
            .rotateY((float) Math.toRadians(45)).rotateX((float) Math.toRadians(35.26));
    private static final double BALL_SPEED = 0.85;
    private static final double BALL_GRAVITY = 0.06;
    private static final double BALL_DRAG = 0.99;
    private static final double GROUND_BOUNCE = 0.8;
    private static final double WALL_BOUNCE = 0.85;
    private static final double ROLL_FRICTION = 0.95;
    private static final double MIN_BOUNCE = 0.1;
    private static final double SUBSTEP = 0.25;
    private static final double BALL_HIT_RADIUS = BALL_RADIUS + 0.2;
    private static final double FAN_DEGREES = 30;
    private static final double LOB_DEGREES = 12;
    private static final long TOSS_GAP = 2;
    private static final double BONK_PUSH = 0.35;
    private static final double BONK_LIFT = 0.3;
    private static final double BONK_BOUNCE = 0.45;
    private static final double MAX_SPIN = 0.9;

    // ultimate: circus cannon
    private static final double FLIGHT_SPEED = 0.9;
    private static final int FLIGHT_MIN_TICKS = 14;
    private static final int FLIGHT_MAX_TICKS = 32;
    private static final int LANDING_WAIT_TICKS = 24;
    private static final double MIN_ARC = 3;
    private static final double MAX_ARC = 8;
    private static final double GROUND_SEARCH = 12;
    private static final int SHELLS = 3;
    private static final double LAUNCH_PUSH = 0.4;
    private static final Material BARREL = Material.RED_CONCRETE;
    private static final Material STRIPE = Material.WHITE_CONCRETE;
    private static final Material RIM = Material.GOLD_BLOCK;
    private static final Material WHEEL = Material.DARK_OAK_PLANKS;

    /** Bumped by every laugh, so only the newest confetti trail keeps running. */
    private int laughGeneration;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: laughter

    @Override
    public void onKill(Player victim) {
        laugh();
    }

    /** The clown cracks up: speed, a cackle, a rainbow "깔깔깔!" and confetti behind the feet while it lasts. */
    private void laugh() {
        Player owner = player();
        long ticks = params().ticks("laugh_duration");
        if (ticks <= 0 || !owner.isOnline()) {
            return;
        }
        int amplifier = Math.max(0, params().integer("laugh_level") - 1);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) Math.min(Integer.MAX_VALUE, ticks),
                amplifier, false, false, true));
        Location head = owner.getLocation().add(0, owner.getHeight() + 0.5, 0);
        popText(head, "<b>" + RAINBOW + "깔깔깔!</gradient></b>", 1.0f, LAUGH_TEXT_TICKS);
        Fx.sound(head, Sound.ENTITY_WITCH_CELEBRATE, 1.0f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
        ctx().runLater(owner, 2, () -> Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.5f));
        ctx().runLater(owner, 4, () -> Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.8f));
        Vfx.cloud(ctx(), head, Particle.NOTE, 4, 0.4, 1, null, false);

        int generation = ++laughGeneration;
        Loop.repeat(ctx(), owner, 1, 2, Math.max(1, ticks / 2), run -> {
            if (generation != laughGeneration) {
                return false;
            }
            Location feet = owner.getLocation().add(0, 0.15, 0);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Location bit = feet.clone().add(random.nextDouble(-0.35, 0.35), random.nextDouble(0, 0.5),
                        random.nextDouble(-0.35, 0.35));
                Vfx.dot(bit, Particle.DUST, new Particle.DustOptions(COLORS[random.nextInt(COLORS.length)], 0.8f),
                        false);
            }
            if (run % 5 == 0) {
                spawnNote(owner.getLocation().add(0, owner.getHeight() + 0.3, 0));
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ active: juggling

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        int count = Math.max(1, params().integer("balls"));
        long life = Math.max(1, params().ticks("ball_duration"));
        Vector facing = Motion.horizontalFacing(eye);
        double lob = Math.toRadians(Math.clamp(-eye.getPitch(), -10, 30) + LOB_DEGREES);
        Location hands = eye.clone().subtract(0, 0.35, 0).add(facing.clone().multiply(0.3));
        if (collides(owner.getWorld(), hands)) {
            hands = eye.clone().subtract(0, 0.35, 0);
        }
        List<Ball> balls = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = count == 1 ? 0 : Math.toRadians(-FAN_DEGREES / 2 + FAN_DEGREES * i / (count - 1));
            Vector direction = facing.clone().rotateAroundY(angle).multiply(Math.cos(lob)).setY(Math.sin(lob));
            balls.add(new Ball(owner, i, hands, direction.multiply(BALL_SPEED), i * TOSS_GAP, life));
        }
        Juggle juggle = new Juggle(owner, balls);
        Loop.repeat(ctx(), owner, 0, 1, life + count * TOSS_GAP + 2, juggle, juggle::finish);
        Vfx.cloud(ctx(), hands, Particle.DUST_COLOR_TRANSITION, 8, 0.2, 0, PALETTE.glow(0.8f), false);
        Fx.sound(hands, Sound.ENTITY_EGG_THROW, 0.8f, 1.3f + Fx.jitter(0.1f));
        return ActivationResult.success();
    }

    /** All balls of one throw, stepped together each tick. */
    private final class Juggle implements LongPredicate {

        private final Player owner;
        private final List<Ball> balls;

        Juggle(Player owner, List<Ball> balls) {
            this.owner = owner;
            this.balls = balls;
        }

        @Override
        public boolean test(long tick) {
            boolean any = false;
            for (Ball ball : balls) {
                if (ball.step(tick)) {
                    any = true;
                }
            }
            return any && owner.isOnline();
        }

        void finish() {
            for (Ball ball : balls) {
                ball.pop();
            }
        }
    }

    /**
     * One juggling ball: tossed after its delay, then flies under gravity and drag, bounces off the ground (keeping
     * most of its height: it is a bouncy ball), walls and enemies, each enemy at most once.
     */
    private final class Ball {

        private final Player owner;
        private final int index;
        private final long launchTick;
        private final long endTick;
        private final World world;
        private final Set<UUID> hit = new HashSet<>();
        private final BlockDisplay outer;
        private final BlockDisplay inner;
        private final Color color;
        private Quaternionf spin = new Quaternionf();
        private Location position;
        private Vector velocity;
        private boolean launched;
        private boolean popped;

        Ball(Player owner, int index, Location hands, Vector velocity, long launchTick, long life) {
            this.owner = owner;
            this.index = index;
            this.launchTick = launchTick;
            this.endTick = launchTick + life;
            this.world = hands.getWorld();
            this.velocity = velocity;
            Material[] colors = BALL_COLORS[index % BALL_COLORS.length];
            this.color = COLORS[(index * 2) % COLORS.length];
            Vector side = new Vector(-velocity.getZ(), 0, velocity.getX());
            side = side.lengthSquared() < 1.0E-6 ? new Vector() : side.normalize();
            // Waiting balls hang in a small arc above the hands, like the moment before a toss.
            this.position = hands.clone().add(side.multiply((index - 1) * 0.3)).add(0, 0.15 * (index % 2), 0);
            if (collides(world, position)) {
                position = hands.clone();
            }
            position.setYaw(0);
            position.setPitch(0);
            long lifetime = endTick + 8;
            outer = Displays.block(ctx(), owner, position, colors[0].createBlockData(), cubePose(0.05f, spin),
                    lifetime);
            inner = Displays.block(ctx(), owner, position, colors[1].createBlockData(),
                    cubePose(0.04f, INNER_ORIENTATION), lifetime);
            outer.setTeleportDuration(1);
            inner.setTeleportDuration(1);
            Displays.animateNextTick(ctx(), owner, outer, cubePose(BALL_SIZE, spin), 2);
            Displays.animateNextTick(ctx(), owner, inner, cubePose(BALL_INNER, INNER_ORIENTATION), 2);
        }

        /** One tick; false once the ball is gone. */
        boolean step(long tick) {
            if (popped) {
                return false;
            }
            if (tick < launchTick) {
                return true;
            }
            if (!launched) {
                launched = true;
                toss();
            }
            if (tick >= endTick || !outer.isValid()) {
                pop();
                return false;
            }
            Location from = position.clone();
            velocity.setY(velocity.getY() - BALL_GRAVITY).multiply(BALL_DRAG);
            move();
            render(from);
            Vfx.dot(position, Particle.DUST, new Particle.DustOptions(color, 0.7f), false);
            if (tick % 3 == index % 3) {
                Vfx.dot(from, Particle.DUST, new Particle.DustOptions(Color.WHITE, 0.5f), false);
            }
            return true;
        }

        private void toss() {
            Fx.sound(position, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, note());
            Vfx.dot(position, Particle.DUST_COLOR_TRANSITION, new Particle.DustTransition(Color.WHITE, color, 1.0f),
                    false);
        }

        private float note() {
            return BALL_NOTES[index % BALL_NOTES.length];
        }

        private void move() {
            boolean rolled = false;
            int steps = Math.max(1, (int) Math.ceil(velocity.length() / SUBSTEP));
            for (int i = 0; i < steps && !popped; i++) {
                Vector step = velocity.clone().multiply(1.0 / steps);
                for (int axis = 0; axis < 3; axis++) {
                    double delta = axis == 0 ? step.getX() : axis == 1 ? step.getY() : step.getZ();
                    if (Math.abs(delta) < 1.0E-9) {
                        continue;
                    }
                    Location next = position.clone().add(axis == 0 ? delta : 0, axis == 1 ? delta : 0,
                            axis == 2 ? delta : 0);
                    if (!collides(world, next)) {
                        position = next;
                        continue;
                    }
                    if (axis == 1) {
                        if (delta < 0) {
                            rolled = true;
                            if (-velocity.getY() > MIN_BOUNCE) {
                                bounce(true, -velocity.getY());
                                velocity.setY(-velocity.getY() * GROUND_BOUNCE);
                            } else {
                                velocity.setY(0);
                            }
                        } else {
                            velocity.setY(-velocity.getY() * 0.5);
                        }
                        step.setY(0);
                    } else {
                        double speed = Math.abs(axis == 0 ? velocity.getX() : velocity.getZ());
                        if (axis == 0) {
                            velocity.setX(-velocity.getX() * WALL_BOUNCE);
                            step.setX(0);
                        } else {
                            velocity.setZ(-velocity.getZ() * WALL_BOUNCE);
                            step.setZ(0);
                        }
                        if (speed > MIN_BOUNCE) {
                            bounce(false, speed);
                        }
                    }
                }
                bonk();
            }
            if (rolled) {
                velocity.setX(velocity.getX() * ROLL_FRICTION).setZ(velocity.getZ() * ROLL_FRICTION);
            }
        }

        /** A bounce: the ball's note, a squish and a small colored splash. */
        private void bounce(boolean ground, double speed) {
            float volume = (float) Math.min(0.9, 0.25 + speed);
            Fx.sound(position, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, volume, note());
            Fx.sound(position, Sound.ENTITY_SLIME_JUMP_SMALL, volume * 0.6f, 1.4f + Fx.jitter(0.1f));
            Location at = ground ? position.clone().add(0, -BALL_RADIUS + 0.05, 0) : position;
            Vfx.ring(ctx(), at, 0.3, 7, 0, Particle.DUST, new Particle.DustOptions(color, 0.6f), false);
            if (ground) {
                spawnNote(at.clone().add(0, 0.4, 0));
            }
        }

        /** Hits the first enemy it touches and bounces off it. */
        private void bonk() {
            for (LivingEntity enemy : ctx().enemiesNear(owner, position, BALL_HIT_RADIUS)) {
                if (!hit.add(enemy.getUniqueId())) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, Math.max(0, params().num("ball_damage")));
                Location body = Fx.body(enemy);
                Vector flight = Motion.horizontal(velocity);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    if (flight.lengthSquared() < 1.0E-6) {
                        flight = Motion.horizontal(enemy.getLocation().toVector()
                                .subtract(owner.getLocation().toVector()));
                    }
                    Motion.push(enemy, flight.clone().multiply(BONK_PUSH).setY(BONK_LIFT), MAX_SPEED);
                }
                Vector normal = Motion.horizontal(position.toVector().subtract(body.toVector()));
                if (normal.lengthSquared() < 1.0E-6) {
                    normal = flight.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : flight.clone().multiply(-1);
                }
                double into = velocity.dot(normal);
                if (into < 0) {
                    velocity.subtract(normal.clone().multiply(2 * into));
                }
                velocity.multiply(0.7).setY(Math.max(velocity.getY(), BONK_BOUNCE));

                Vfx.burst(ctx(), body, PALETTE, 0.5, Particle.CRIT, false);
                Vfx.cloud(ctx(), body, Particle.DUST, 6, 0.25, 0, new Particle.DustOptions(color, 1.0f), false);
                Fx.sound(body, Sound.ENTITY_SLIME_SQUISH, 0.9f, 1.4f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BIT, 0.9f, note());
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.6f, 1.5f + Fx.jitter(0.1f));
                return;
            }
        }

        /** Moves the ball's displays, rolling them by the distance covered. */
        private void render(Location from) {
            if (!outer.isValid() || !inner.isValid()) {
                return;
            }
            Vector delta = position.toVector().subtract(from.toVector());
            Vector axis = new Vector(0, 1, 0).crossProduct(delta);
            if (axis.lengthSquared() > 1.0E-8) {
                double angle = Math.min(MAX_SPIN, delta.length() / BALL_RADIUS);
                axis.normalize();
                spin = new Quaternionf().rotateAxis((float) angle, (float) axis.getX(), (float) axis.getY(),
                        (float) axis.getZ()).mul(spin).normalize();
            }
            Location at = position.clone();
            at.setYaw(0);
            at.setPitch(0);
            Displays.moveTo(outer, at);
            Displays.moveTo(inner, at);
            Displays.animate(outer, cubePose(BALL_SIZE, spin), 1);
            Displays.animate(inner, cubePose(BALL_INNER, new Quaternionf(spin).mul(INNER_ORIENTATION)), 1);
        }

        /** The ball pops into confetti. */
        void pop() {
            if (popped) {
                return;
            }
            popped = true;
            if (launched && outer.isValid()) {
                Vfx.cloud(ctx(), position, Particle.DUST, 8, 0.2, 0, new Particle.DustOptions(color, 0.9f), false);
                Vfx.cloud(ctx(), position, Particle.POOF, 3, 0.05, 0.03, null, false);
                Fx.sound(position, Sound.ENTITY_CHICKEN_EGG, 0.6f, 1.6f + Fx.jitter(0.1f));
            }
            for (BlockDisplay display : new BlockDisplay[] {outer, inner}) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 3);
                }
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: circus cannon

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle() || owner.isGliding()) {
            return ActivationResult.fail("지금은 대포에 들어갈 수 없습니다");
        }
        Location landing = landingSpot(owner, Math.max(1, params().num("ult_range")));
        if (landing == null) {
            return ActivationResult.fail("대포로 날아갈 안전한 착지 지점이 없습니다");
        }
        Cannon cannon = new Cannon(owner, landing);
        cannon.load();
        Loop.repeat(ctx(), owner, 1, 1, cannon.windup + FLIGHT_MAX_TICKS + 2L * LANDING_WAIT_TICKS
                + LandingGuard.SETTLE_TICKS + 2, cannon, cannon::finish);
        return ActivationResult.success();
    }

    /**
     * Where the cannon shot lands: on the aimed enemy, else the aimed block or the end of the aim line, dropped onto
     * the ground below and pulled back towards the owner until it is a safe spot inside the arena. Null if there is
     * none.
     */
    private @Nullable Location landingSpot(Player owner, double range) {
        Location eye = owner.getEyeLocation();
        World world = eye.getWorld();
        Vector look = eye.getDirection();
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location aim;
        if (aimed != null && (!(aimed instanceof Player victim) || owner.canSee(victim))) {
            aim = aimed.getLocation().add(0, 0.5, 0);
        } else {
            RayTraceResult wall = world.rayTraceBlocks(eye, look, range, FluidCollisionMode.NEVER, true);
            aim = wall == null ? eye.clone().add(look.clone().multiply(range))
                    : wall.getHitPosition().toLocation(world).subtract(look.clone().multiply(0.4));
        }
        Location feet = owner.getLocation();
        Vector back = feet.toVector().subtract(aim.toVector()).setY(0);
        double distance = back.length();
        if (distance > 1.0E-6) {
            back.multiply(1 / distance);
        }
        Body body = Body.standing(owner);
        for (double d = 0; d <= distance; d += 1.0) {
            Location probe = aim.clone().add(back.clone().multiply(d));
            Double ground = groundBelow(probe, GROUND_SEARCH);
            if (ground == null) {
                continue;
            }
            Location spot = probe.clone();
            spot.setY(ground);
            if (Spots.isSafe(ctx(), spot, body)) {
                spot.setYaw(feet.getYaw());
                spot.setPitch(0);
                return spot;
            }
        }
        return null;
    }

    /**
     * The circus cannon: the owner is loaded (rooted) into a striped cannon aimed at the landing spot while a drum
     * roll plays and a rainbow bullseye marks where it will land; then it fires the owner along a fixed arc (the
     * velocity is set every tick from the arc itself, not from where the server last saw the client), and a firework
     * goes off where the owner comes down. The fall is forgiven inside this loop, so a borrowed cannon is safe too.
     */
    private final class Cannon implements LongPredicate {

        private final Player owner;
        private final Location landing;
        private final World world;
        private final long windup;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private Location start;
        private int flightTicks;
        private double arc;
        private long launchTick = -1;
        private long landedTick = -1;
        private int groundedTicks;
        private int stuckTicks;
        private @Nullable Location lastSeen;

        Cannon(Player owner, Location landing) {
            this.owner = owner;
            this.landing = landing;
            this.world = landing.getWorld();
            this.windup = Math.max(1, params().ticks("ult_windup"));
            this.start = owner.getLocation();
        }

        void load() {
            ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, windup + 1);
            ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, windup + 1);
            plan(owner.getLocation());
            buildCannon();
            Location feet = owner.getLocation();
            Fx.sound(feet, Sound.ENTITY_TNT_PRIMED, 1.0f, 1.2f);
            Fx.sound(feet, Sound.BLOCK_PISTON_EXTEND, 0.8f, 0.7f);
            owner.sendActionBar(Text.mm("<b>" + RAINBOW + "서커스 대포 장전!</gradient></b>"));
        }

        /** The arc for a launch from {@code from}: its height and duration grow with the distance. */
        private void plan(Location from) {
            start = from.clone();
            double distance = Math.hypot(landing.getX() - from.getX(), landing.getZ() - from.getZ());
            arc = Math.clamp(2.5 + distance * 0.25, MIN_ARC, MAX_ARC);
            flightTicks = Math.clamp(Math.round(distance / FLIGHT_SPEED) + 8, FLIGHT_MIN_TICKS, FLIGHT_MAX_TICKS);
        }

        /** A point of the arc at tick {@code t} of the flight. */
        private Vector arcPoint(double t) {
            double s = Math.clamp(t / flightTicks, 0, 1);
            Vector from = start.toVector();
            Vector to = landing.toVector();
            Vector point = from.clone().add(to.clone().subtract(from).multiply(s));
            point.setY(point.getY() + 4 * arc * s * (1 - s));
            return point;
        }

        @Override
        public boolean test(long tick) {
            if (owner.isInsideVehicle()) {
                dismantle(0);
                return false;
            }
            if (launchTick < 0) {
                if (tick + 1 < windup) {
                    loading(tick);
                    return true;
                }
                fire(tick);
                return true;
            }
            owner.setFallDistance(0);
            if (landedTick >= 0) {
                // The landing guard: forgive the fall until the owner has settled on the ground.
                if (Motion.isGrounded(owner) || owner.isInWater()) {
                    return ++groundedTicks < LandingGuard.SETTLE_TICKS;
                }
                groundedTicks = 0;
                return tick - landedTick < LANDING_WAIT_TICKS;
            }
            long flown = tick - launchTick;
            if (flown < flightTicks) {
                Vector velocity = arcPoint(flown + 1).subtract(arcPoint(flown));
                owner.setVelocity(Motion.clamp(velocity, MAX_SPEED));
            }
            confetti(flown);
            Location now = owner.getLocation();
            boolean grounded = Motion.isGrounded(owner) || owner.isInWater();
            Location seen = lastSeen;
            stuckTicks = seen != null && seen.distanceSquared(now) < 0.01 ? stuckTicks + 1 : 0;
            lastSeen = now;
            if (flown >= 3 && (grounded && flown >= flightTicks / 2 || stuckTicks >= 4)
                    || flown >= flightTicks + LANDING_WAIT_TICKS) {
                landedTick = tick;
                firework(now);
            }
            return true;
        }

        /** Drum roll, fuse sparks at the back of the cannon, and the bullseye at the landing spot. */
        private void loading(long tick) {
            Location feet = owner.getLocation();
            Vector direction = launchDirection();
            Location fuse = feet.clone().add(0, 0.6, 0).subtract(direction.clone().multiply(0.9));
            Vfx.dot(fuse, Particle.FLAME, null, false);
            Vfx.cloud(ctx(), fuse, Particle.SMOKE, 2, 0.05, 0.02, null, false);
            if (tick % 2 == 0) {
                Fx.sound(feet, Sound.BLOCK_NOTE_BLOCK_SNARE, 0.9f, 0.8f + 0.8f * tick / windup);
                bullseye(tick);
            }
        }

        /** Rainbow rings at the landing spot, so the ones standing there can run. */
        private void bullseye(long tick) {
            Location center = landing.clone().add(0, 0.1, 0);
            double radius = Math.max(1, params().num("ult_radius"));
            for (int i = 0; i < 3; i++) {
                Color color = COLORS[(int) ((i * 2 + tick / 2) % COLORS.length)];
                double r = radius * (1 - i * 0.33);
                Vfx.ring(ctx(), center, r, (int) Math.max(10, r * 6), tick * 0.1 * (i % 2 == 0 ? 1 : -1),
                        Particle.DUST, new Particle.DustOptions(color, 1.2f), true);
            }
        }

        /** Boom: the cannon fires the owner along the arc. */
        private void fire(long tick) {
            launchTick = tick;
            plan(owner.getLocation());
            Vector direction = launchDirection();
            Location muzzle = owner.getLocation().add(0, 0.8, 0).add(direction.clone().multiply(1.2));
            Vfx.dot(muzzle, Particle.FLASH, PALETTE.core(), true);
            Vfx.cloud(ctx(), muzzle, Particle.LARGE_SMOKE, 12, 0.3, 0.05, null, true);
            Vfx.cloud(ctx(), muzzle, Particle.FIREWORK, 20, 0.2, 0.3, null, true);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 16); i++) {
                Location bit = muzzle.clone().add(random.nextDouble(-0.4, 0.4), random.nextDouble(-0.2, 0.6),
                        random.nextDouble(-0.4, 0.4));
                Vfx.dot(bit, Particle.DUST, new Particle.DustOptions(COLORS[i % COLORS.length], 1.0f), true);
            }
            Fx.sound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.3f + Fx.jitter(0.05f));
            Fx.sound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.2f, 0.8f);
            Fx.sound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.0f, 0.7f);
            Fx.sound(muzzle, Sound.ENTITY_WITCH_CELEBRATE, 0.7f, 1.6f);
            dismantle(8);
            owner.setFallDistance(0);
            owner.setVelocity(Motion.clamp(arcPoint(1).subtract(arcPoint(0)), MAX_SPEED));
        }

        /** Rainbow confetti and sparkles streaming behind the flying clown. */
        private void confetti(long flown) {
            Location body = Fx.body(owner);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                Location bit = body.clone().add(random.nextDouble(-0.4, 0.4), random.nextDouble(-0.5, 0.5),
                        random.nextDouble(-0.4, 0.4));
                Vfx.dot(bit, Particle.DUST, new Particle.DustOptions(COLORS[(int) ((flown + i) % COLORS.length)], 1.1f),
                        true);
            }
            Vfx.cloud(ctx(), body, Particle.FIREWORK, 2, 0.15, 0.02, null, true);
            if (flown < flightTicks && flown % 2 == 0) {
                bullseye(flown);
            }
        }

        /** The big one: rainbow shock rings, star spheres, the enemies around thrown up, and shells in the sky. */
        private void firework(Location at) {
            Location center = at.clone().add(0, 0.2, 0);
            Location burst = at.clone().add(0, 1.2, 0);
            double radius = Math.max(0, params().num("ult_radius"));
            Vfx.dot(burst, Particle.FLASH, PALETTE.core(), true);
            Vfx.dot(burst, Particle.FLASH, PALETTE.body(), true);
            Vfx.cloud(ctx(), burst, Particle.FIREWORK, 60, 0.3, 0.45, null, true);
            Vfx.sphere(ctx(), burst, 1.6, 36, Particle.DUST, new Particle.DustOptions(COLORS[0], 1.5f), true);
            Vfx.sphere(ctx(), burst, 2.4, 44, Particle.DUST, new Particle.DustOptions(COLORS[4], 1.3f), true);
            Loop.repeat(ctx(), owner, 0, 1, COLORS.length, run -> {
                double r = 0.6 + (radius - 0.6) * (run + 1) / COLORS.length;
                Vfx.ring(ctx(), center, r, (int) Math.max(12, r * 7), run * 0.3, Particle.DUST,
                        new Particle.DustOptions(COLORS[(int) run], 1.6f), true);
                return true;
            });
            Fx.sound(burst, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.5f, 0.9f);
            Fx.sound(burst, Sound.ENTITY_FIREWORK_ROCKET_BLAST_FAR, 1.5f, 0.8f);
            Fx.sound(burst, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.4f + Fx.jitter(0.05f));
            ctx().runLater(owner, 4, () -> Fx.sound(burst, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.2f, 1.0f));
            popText(burst.clone().add(0, 1.0, 0), "<b>" + RAINBOW + "펑!</gradient></b>", 1.6f, 20);

            int hits = 0;
            double damage = Math.max(0, params().num("ult_damage"));
            double lift = Motion.launchSpeed(Math.max(0, params().num("ult_launch")));
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    double angle = ThreadLocalRandom.current().nextDouble(TAU);
                    away = new Vector(Math.cos(angle), 0, Math.sin(angle));
                }
                Motion.push(enemy, away.multiply(LAUNCH_PUSH).setY(lift), MAX_SPEED);
                Vfx.cloud(ctx(), Fx.body(enemy), Particle.DUST, 8, 0.3, 0,
                        new Particle.DustOptions(COLORS[hits % COLORS.length], 1.2f), true);
            }
            if (hits > 0) {
                laugh();
            }
            shells(at);
        }

        /** Aerial shells bursting one after another above the landing spot, then their sparks drifting down. */
        private void shells(Location at) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < SHELLS; i++) {
                Location sky = at.clone().add(random.nextDouble(-2, 2), 5 + i * 1.3 + random.nextDouble(), random
                        .nextDouble(-2, 2));
                Color color = COLORS[(i * 2 + 1) % COLORS.length];
                Color second = COLORS[(i * 2 + 4) % COLORS.length];
                long delay = 5 + i * 6L;
                Location launch = at.clone().add(0, 0.5, 0);
                ctx().runLater(owner, Math.max(1, delay - 4), () -> Vfx.trail(launch, sky, Color.WHITE, 4, true));
                ctx().runLater(owner, delay, () -> {
                    Vfx.sphere(ctx(), sky, 1.8, 40, Particle.DUST, new Particle.DustOptions(color, 1.3f), true);
                    Vfx.sphere(ctx(), sky, 1.0, 18, Particle.DUST, new Particle.DustOptions(second, 1.1f), true);
                    Vfx.cloud(ctx(), sky, Particle.FIREWORK, 16, 0.4, 0.15, null, true);
                    Vfx.dot(sky, Particle.FLASH, color, true);
                    Fx.sound(sky, Sound.ENTITY_FIREWORK_ROCKET_BLAST_FAR, 1.4f, 0.9f + Fx.jitter(0.15f));
                    Fx.sound(sky, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE_FAR, 1.0f, 1.0f + Fx.jitter(0.15f));
                    Loop.repeat(ctx(), owner, 2, 2, 8, run -> {
                        for (int k = 0; k < Vfx.n(ctx(), 4); k++) {
                            Location spark = Vfx.scatter(sky, 1.8).add(0, -0.25 * run - random.nextDouble(), 0);
                            Vfx.dot(spark, Particle.DUST, new Particle.DustOptions(k % 2 == 0 ? color : second, 0.8f),
                                    false);
                        }
                        return true;
                    });
                });
            }
        }

        /** The direction the cannon points: towards the landing spot, tilted up by the launch angle. */
        private Vector launchDirection() {
            Vector first = arcPoint(1).subtract(arcPoint(0));
            return first.lengthSquared() < 1.0E-6 ? new Vector(0, 1, 0) : first.normalize();
        }

        /**
         * The striped cannon: a red barrel with two white bands and a gold muzzle rim, aimed along the launch, on two
         * wooden wheels.
         */
        private void buildCannon() {
            Location feet = owner.getLocation();
            Vector direction = launchDirection();
            Vector flat = Motion.horizontal(direction);
            if (flat.lengthSquared() < 1.0E-6) {
                flat = Motion.horizontalFacing(feet);
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
            float pitch = (float) -Math.toDegrees(Math.asin(Math.clamp(direction.getY(), -1, 1)));
            pitch = Math.clamp(pitch, -75f, -20f);
            Location pivot = feet.clone().add(0, 0.7, 0);
            pivot.setYaw(0);
            pivot.setPitch(0);
            long life = windup + 20;
            barrelPart(pivot, BARREL, 0.8f, 1.8f, 0.2f, yaw, pitch, life);
            barrelPart(pivot, STRIPE, 0.86f, 0.18f, -0.25f, yaw, pitch, life);
            barrelPart(pivot, STRIPE, 0.86f, 0.18f, 0.55f, yaw, pitch, life);
            barrelPart(pivot, RIM, 0.94f, 0.14f, 1.1f, yaw, pitch, life);
            Vector side = new Vector(-flat.getZ(), 0, flat.getX()).multiply(0.5);
            for (int s = -1; s <= 1; s += 2) {
                Location hub = feet.clone().add(side.clone().multiply(s)).add(0, 0.38, 0);
                hub.setYaw(0);
                hub.setPitch(0);
                BlockDisplay wheel = Displays.block(ctx(), owner, hub, WHEEL.createBlockData(),
                        Displays.transform(0, 0, 0, 0.12f, 0.05f, 0.05f, yaw, 45, 0), life);
                // Thin along the axle (the cannon's side axis), the disc standing in the line of fire.
                Displays.animateNextTick(ctx(), owner, wheel,
                        Displays.transform(0, 0, 0, 0.12f, 0.7f, 0.7f, yaw, 45, 0), 4);
                parts.add(wheel);
            }
        }

        /** One piece of the barrel: a square tube section at {@code along} blocks along the barrel's axis. */
        private void barrelPart(Location pivot, Material material, float width, float length, float along, float yaw,
                                float pitch, long life) {
            Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                    .rotateX((float) Math.toRadians(pitch));
            Vector3f offset = rotation.transform(new Vector3f(0, 0, along));
            Transformation full = Displays.transform(offset.x, offset.y, offset.z, width, width, length, yaw, pitch, 0);
            Transformation small = Displays.transform(offset.x, offset.y, offset.z, width * 0.1f, width * 0.1f,
                    length * 0.1f, yaw, pitch, 0);
            BlockDisplay part = Displays.block(ctx(), owner, pivot, material.createBlockData(), small, life);
            Displays.animateNextTick(ctx(), owner, part, full, 4);
            parts.add(part);
        }

        /** The cannon puffs once and shrinks away after {@code delay} ticks (at once for 0). */
        private void dismantle(long delay) {
            for (BlockDisplay part : parts) {
                if (!part.isValid()) {
                    continue;
                }
                if (delay <= 0) {
                    part.remove();
                    continue;
                }
                Transformation current = part.getTransformation();
                Displays.animate(part, new Transformation(current.getTranslation(), current.getLeftRotation(),
                        new Vector3f(current.getScale()).mul(1.12f), current.getRightRotation()), 2);
                ctx().runLater(owner, delay, () -> {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 5);
                    }
                });
            }
        }

        /** The loop ran out: if the owner never got off the ground, the cannon simply goes away. */
        void finish() {
            if (launchTick < 0) {
                dismantle(0);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** True if a ball's box at {@code center} touches a block (or anything else that collides). */
    private static boolean collides(World world, Location center) {
        double r = BALL_RADIUS;
        return world.hasCollisionsIn(new BoundingBox(center.getX() - r, center.getY() - r, center.getZ() - r,
                center.getX() + r, center.getY() + r, center.getZ() + r));
    }

    /** The top of the first solid block at most {@code depth} blocks below {@code at}, null if none. */
    private static @Nullable Double groundBelow(Location at, double depth) {
        World world = at.getWorld();
        int x = at.getBlockX();
        int z = at.getBlockZ();
        int start = Math.min(at.getBlockY(), world.getMaxHeight() - 1);
        int end = (int) Math.max(world.getMinHeight(), start - depth);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /** A colorful note particle (NOTE takes its color from the x offset when spawned with count 0). */
    private static void spawnNote(Location at) {
        double color = ThreadLocalRandom.current().nextInt(25) / 24.0;
        at.getWorld().spawnParticle(Particle.NOTE, at, 0, color, 0, 0, 1);
    }

    /** An onomatopoeia popping up with an overshoot, then shrinking away. */
    private void popText(Location at, String text, float scale, long life) {
        Player owner = player();
        float roll = (float) ThreadLocalRandom.current().nextDouble(-12, 12);
        TextDisplay label = Displays.text(ctx(), owner, at, Text.mm(text), scale * 0.3f, life);
        float pop = scale * 1.3f;
        Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.15f, 0, pop, pop, pop, 0, 0, roll), 2);
        ctx().runLater(owner, 3, () -> Displays.animate(label, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0,
                0, roll), 3));
        ctx().runLater(owner, Math.max(4, life - 5), () -> {
            if (label.isValid()) {
                Displays.shrinkAway(ctx(), owner, label, 4);
            }
        });
    }

    /** A cube of edge {@code size} centered on the block display's origin, rotated around its center. */
    private static Transformation cubePose(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }
}
