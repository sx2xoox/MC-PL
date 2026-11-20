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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 축구선수 — a striker who never stops running. Sprinting builds up speed (a ball rolls at the feet while dribbling),
 * the active kicks that ball as a real bouncing shot that ricochets off the ground, walls and enemies, and the
 * ultimate bends a burning banana kick around the defence into the aimed enemy, with a goal celebration.
 *
 * <p>The ball is two block displays: a white cube and a smaller black cube turned onto its corner, whose tips poke
 * through the white faces like the patches of a football; it rolls with the distance it covers. The shot's flight is
 * simulated here (gravity, drag, axis-by-axis block collisions with bounces), so it is exact and needs no entity.
 */
public final class SoccerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("soccer", "축구선수", Material.CLAY_BALL)
            .color(TextColor.color(0x3DDC84))
            .difficulty(1)
            .passive("드리블",
                    "달리는 동안 이동 속도가 점점 빨라져 {dribble_ramp}초 뒤에는 최대 {dribble_max}%(신속 II 수준)까지 "
                            + "오릅니다. 발밑에서 공이 굴러가며, 달리기를 멈추면 처음부터 다시 쌓입니다.")
            .active("슈팅",
                    "발밑의 공을 바라보는 방향으로 힘껏 찹니다. 공은 땅과 벽에 튕기며 최대 {shot_range}블록 굴러가고, "
                            + "맞은 적에게 {shot_damage} 피해를 주며 약 {shot_push}블록 밀쳐냅니다. "
                            + "벽에 맞고 튕긴 공은 피해가 {bank_bonus}% 커집니다.",
                    7)
            .ultimate("바나나킥",
                    "불붙은 공을 크게 감아 차서 조준한 적(없으면 바라보는 방향, {ult_range}블록 이내)에게 휘어 들어가게 "
                            + "합니다. 공은 적을 모두 꿰뚫으며 {ult_damage} 피해를 주고 {ult_burn}초간 불태우며 약 "
                            + "{ult_push}블록 밀쳐냅니다. 첫 적중에는 골 세리머니가 터집니다.")
            .param("dribble_ramp", 3)
            .param("dribble_max", 40)
            .param("shot_range", 28)
            .param("shot_damage", 5)
            .param("shot_push", 5)
            .param("bank_bonus", 40)
            .param("ult_range", 30)
            .param("ult_damage", 8)
            .param("ult_burn", 3)
            .param("ult_push", 4)
            .build();

    private static final String COLOR_TAG = "#3DDC84";
    /** Chalk white core, pitch green body, deep turf edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0x3DDC84, 0x1B5E20);
    /** The banana kick's fire: white-hot, orange, ember red. */
    private static final Vfx.Palette FIRE = Vfx.Palette.of(0xFFF3B0, 0xFF8A1F, 0xC62828);
    private static final Particle.DustTransition GLOW = PALETTE.glow(0.9f);
    private static final Particle.DustTransition TURF_FADE = PALETTE.fade(0.8f);
    private static final Particle.DustOptions CHALK = new Particle.DustOptions(Color.WHITE, 0.7f);
    private static final Particle.DustTransition EMBER = FIRE.glow(1.1f);
    private static final Particle.DustTransition EMBER_FADE = FIRE.fade(0.9f);
    private static final double TAU = Math.PI * 2;
    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;
    /** Blocks of knockback travel per unit of horizontal launch speed (with {@link #PUSH_LIFT}). */
    private static final double PUSH_PER_SPEED = 8.0;
    private static final double PUSH_LIFT = 0.38;

    // ball
    private static final Material SHELL = Material.WHITE_CONCRETE;
    private static final Material PATCH = Material.BLACK_CONCRETE;
    private static final Material TURF = Material.GRASS_BLOCK;
    private static final double BALL_RADIUS = 0.22;
    private static final float SHELL_SIZE = 0.44f;
    /** Small enough that its faces stay inside the shell, large enough that its corners poke out. */
    private static final float PATCH_SIZE = 0.36f;
    private static final Quaternionf PATCH_ORIENTATION = new Quaternionf()
            .rotateY((float) Math.toRadians(45)).rotateX((float) Math.toRadians(35.26));
    /** At most this much visible spin per tick (radians), so interpolation never turns the wrong way. */
    private static final double MAX_SPIN = 0.9;

    // passive: dribble
    private static final int DRIBBLE_STEPS = 4;
    /** Where the dribbled ball rolls, ahead of the feet. */
    private static final double DRIBBLE_LEAD = 0.75;
    /** Ticks of the owner's recorded motion the dribbled ball runs ahead, so it never trails a fast client. */
    private static final double DRIBBLE_PREDICT = 1.5;

    // active: shot
    private static final double SHOT_SPEED = 1.3;
    private static final double BALL_GRAVITY = 0.04;
    private static final double AIR_DRAG = 0.99;
    private static final double GROUND_BOUNCE = 0.55;
    private static final double WALL_BOUNCE = 0.8;
    private static final double ENEMY_BOUNCE = 0.45;
    private static final double ROLL_FRICTION = 0.93;
    /** Vertical speeds below this do not bounce any more: the ball rolls. */
    private static final double MIN_BOUNCE = 0.12;
    private static final double REST_SPEED = 0.06;
    private static final long SHOT_MAX_TICKS = 80;
    /** Longest movement per collision sub-step, well below the ball's size, so nothing is tunnelled through. */
    private static final double SUBSTEP = 0.3;
    /** Reach of the ball around its center when touching enemies. */
    private static final double SHOT_HIT_RADIUS = BALL_RADIUS + 0.2;
    private static final double MIN_LOFT = -12;
    private static final double MAX_LOFT = 35;
    private static final double LOFT_BONUS = 4;

    // ultimate: banana kick
    private static final int ULT_WINDUP = 7;
    private static final double ULT_SPEED = 1.5;
    private static final double ULT_HIT_RADIUS = 0.9;
    private static final double PATH_STEP = 0.25;
    private static final double CURVE_FACTOR = 0.35;
    private static final double CURVE_MIN = 1.5;
    private static final double CURVE_MAX = 6;
    private static final double CURVE_RISE = 1.2;
    /** Without a target the banana bends back onto the aim line this far along it (fraction of the range). */
    private static final double FREE_BEND = 0.55;
    private static final double ULT_PUSH_LIFT = 0.45;
    private static final int GOAL_TICKS = 40;
    private static final float GOAL_WIDTH = 3.0f;
    private static final float GOAL_HEIGHT = 2.1f;
    private static final float POST = 0.12f;
    private static final Material GOAL_FRAME = Material.WHITE_CONCRETE;
    private static final Material GOAL_NET = Material.COBWEB;

    /** The dribble speed level (0..{@link #DRIBBLE_STEPS}) and its modifier. */
    private int dribbleLevel;
    private @Nullable NamespacedKey dribbleKey;
    private long sprintTicks;
    private @Nullable Ball dribbleBall;
    private @Nullable Location lastFeet;
    private Vector stride = new Vector();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        // The modifier and the displays are owner-scoped: the context removes them.
        dribbleKey = null;
        dribbleLevel = 0;
        dribbleBall = null;
        lastFeet = null;
    }

    // ------------------------------------------------------------------ passive: dribble

    @Override
    public void onTick(long tick) {
        Player owner = player();
        Location feet = owner.getLocation();
        Location last = lastFeet;
        stride = last != null && last.getWorld().equals(feet.getWorld())
                ? Motion.clamp(feet.toVector().subtract(last.toVector()), 1.5) : new Vector();
        lastFeet = feet;

        boolean running = owner.isSprinting() && !owner.isFlying() && !owner.isGliding() && !owner.isSwimming()
                && !owner.isInsideVehicle();
        sprintTicks = running ? sprintTicks + 1 : 0;
        long ramp = Math.max(1, params().ticks("dribble_ramp"));
        int level = running ? (int) Math.min(DRIBBLE_STEPS, sprintTicks * DRIBBLE_STEPS / ramp) : 0;
        if (level != dribbleLevel) {
            setDribble(level);
        }
        if (dribbleLevel > 0) {
            dribble(tick, feet);
        }
    }

    private void setDribble(int level) {
        Player owner = player();
        int previous = dribbleLevel;
        dribbleLevel = level;
        if (dribbleKey != null) {
            ctx().removeModifier(owner, dribbleKey);
            dribbleKey = null;
        }
        if (level > 0) {
            double bonus = Math.max(0, params().num("dribble_max")) / 100.0 * level / DRIBBLE_STEPS;
            dribbleKey = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, bonus, Operation.ADD_SCALAR, 0);
        }
        if (level > previous) {
            dribbleUpFx(level);
        } else if (level == 0) {
            Ball ball = dribbleBall;
            dribbleBall = null;
            if (ball != null) {
                ball.pop(false);
            }
        }
    }

    /** Each level up: a whoosh pitched higher, a gauge in the action bar, and at full speed a chalk ring. */
    private void dribbleUpFx(int level) {
        Player owner = player();
        Location feet = owner.getLocation().add(0, 0.1, 0);
        float pitch = 0.9f + 0.2f * level;
        Fx.sound(feet, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.35f, pitch + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.BLOCK_GRASS_STEP, 0.8f, 1.2f + 0.1f * level);
        StringBuilder gauge = new StringBuilder();
        for (int i = 1; i <= DRIBBLE_STEPS; i++) {
            gauge.append(i <= level ? "<white>▶</white>" : "<dark_gray>▷</dark_gray>");
        }
        int percent = (int) Math.round(Math.max(0, params().num("dribble_max")) * level / DRIBBLE_STEPS);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">드리블</" + COLOR_TAG + "> " + gauge + " <white>+" + percent
                + "%</white>"));
        Vfx.ring(ctx(), feet, 0.6, 10, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_COLOR_TRANSITION,
                GLOW, false);
        if (level == DRIBBLE_STEPS) {
            Vfx.shockwave(ctx(), owner, feet, 0.4, 1.8, 5, Particle.DUST, CHALK, false);
            Vfx.cloud(ctx(), feet, Particle.CLOUD, 6, 0.3, 0.05, null, false);
            Fx.sound(feet, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.4f, 1.6f);
            Fx.sound(feet, Sound.ENTITY_BREEZE_SHOOT, 0.4f, 1.8f + Fx.jitter(0.05f));
        }
    }

    /** The ball rolling at the feet, turf kicked up behind it and, at full speed, chalk speed lines. */
    private void dribble(long tick, Location feet) {
        Location spot = dribbleSpot(feet);
        Ball ball = dribbleBall;
        if (spot == null) {
            // In the air with no ground close below: the ball waits until the feet are back down.
            if (ball != null) {
                dribbleBall = null;
                ball.pop(false);
            }
            return;
        }
        if (ball == null || !ball.isValid()) {
            ball = new Ball(spot, 0, false);
            dribbleBall = ball;
            Fx.sound(spot, Sound.ENTITY_ITEM_PICKUP, 0.4f, 0.7f + Fx.jitter(0.05f));
        } else {
            ball.moveTo(spot);
        }
        World world = feet.getWorld();
        if (tick % 2 == 0) {
            Location behind = spot.clone().subtract(Motion.horizontalFacing(feet).multiply(0.35)).add(0, -0.1, 0);
            world.spawnParticle(Particle.BLOCK, behind, Vfx.n(ctx(), 2), 0.1, 0.05, 0.1, 0, turf());
        }
        if (dribbleLevel >= DRIBBLE_STEPS - 1) {
            Vector back = Motion.horizontalFacing(feet).multiply(-1);
            Vector side = new Vector(-back.getZ(), 0, back.getX()).multiply(0.3);
            for (int s = -1; s <= 1; s += 2) {
                Location from = feet.clone().add(side.clone().multiply(s)).add(0, 0.25 + 0.3 * (tick % 3), 0);
                Vfx.trail(from, from.clone().add(back.clone().multiply(1.6)), s < 0 ? PALETTE.core() : PALETTE.body(),
                        5, false);
            }
        }
        if (dribbleLevel == DRIBBLE_STEPS && tick % 4 == 0) {
            Vfx.dot(feet.clone().add(0, 0.1, 0), Particle.DUST_COLOR_TRANSITION, TURF_FADE, false);
        }
    }

    /** Where the dribbled ball rolls this tick: ahead of the feet on the ground, or null in mid-air. */
    private @Nullable Location dribbleSpot(Location feet) {
        Vector ahead = Motion.horizontalFacing(feet).multiply(DRIBBLE_LEAD)
                .add(stride.clone().setY(0).multiply(DRIBBLE_PREDICT));
        Location spot = feet.clone().add(ahead).add(0, 0.6, 0);
        World world = feet.getWorld();
        if (collides(world, spot)) {
            spot = feet.clone().add(0, 0.6, 0);
        }
        RayTraceResult ground = world.rayTraceBlocks(spot, new Vector(0, -1, 0), 2.2, FluidCollisionMode.NEVER, true);
        if (ground == null) {
            return null;
        }
        spot.setY(ground.getHitPosition().getY() + BALL_RADIUS + 0.01);
        spot.setYaw(0);
        spot.setPitch(0);
        return spot;
    }

    // ------------------------------------------------------------------ active: shot

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontalFacing(feet);
        double loft = Math.toRadians(Math.clamp(-feet.getPitch() + LOFT_BONUS, MIN_LOFT, MAX_LOFT));
        Vector direction = facing.clone().multiply(Math.cos(loft)).setY(Math.sin(loft));

        Ball ball = dribbleBall;
        dribbleBall = null;
        Location start = ball != null && ball.isValid() && !collides(feet.getWorld(), ball.at) ? ball.at.clone() : null;
        if (start == null) {
            start = kickSpot(feet, facing);
        }
        if (ball == null || !ball.isValid()) {
            if (ball != null) {
                ball.remove();
            }
            ball = new Ball(start, SHOT_MAX_TICKS + 20, false);
        }
        Shot shot = new Shot(owner, ball, start, direction.multiply(SHOT_SPEED), Math.max(1, params().num("shot_range")));
        shot.kick();
        Loop.repeat(ctx(), owner, 1, 1, SHOT_MAX_TICKS, shot, shot::finish);
        return ActivationResult.success();
    }

    /** The ball's spot in front of the feet, pulled back towards the owner when a wall is right there. */
    private static Location kickSpot(Location feet, Vector facing) {
        World world = feet.getWorld();
        for (double ahead : new double[] {0.8, 0.45, 0.0}) {
            Location spot = feet.clone().add(facing.clone().multiply(ahead)).add(0, BALL_RADIUS + 0.12, 0);
            if (!collides(world, spot)) {
                spot.setYaw(0);
                spot.setPitch(0);
                return spot;
            }
        }
        return feet.clone().add(0, 0.6, 0);
    }

    /**
     * The kicked ball: flies under gravity and drag, bounces off the ground, walls and ceilings axis by axis, and
     * caroms off the enemies it hits (each one at most once). A ball that came off a wall is a bank shot.
     */
    private final class Shot implements LongPredicate {

        private final Player owner;
        private final Ball ball;
        private final World world;
        private final double range;
        private final Set<UUID> hit = new HashSet<>();
        private Location position;
        private Vector velocity;
        private double travelled;
        private boolean banked;
        private boolean grounded;
        private int resting;
        private boolean done;

        Shot(Player owner, Ball ball, Location start, Vector velocity, double range) {
            this.owner = owner;
            this.ball = ball;
            this.world = start.getWorld();
            this.position = start.clone();
            this.velocity = velocity;
            this.range = range;
        }

        void kick() {
            Location at = position.clone();
            ball.moveTo(at);
            Vfx.dot(at, Particle.FLASH, PALETTE.core(), false);
            Vfx.cloud(ctx(), at, Particle.CLOUD, 6, 0.15, 0.08, null, false);
            Vfx.cloud(ctx(), at.clone().add(0, -0.15, 0), Particle.BLOCK, 8, 0.2, 0.1, turf(), false);
            verticalRing(at, velocity, 0.45, 12, 0, Particle.DUST_COLOR_TRANSITION, GLOW, false);
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ENTITY_BREEZE_SHOOT, 0.5f, 1.6f + Fx.jitter(0.1f));
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            if (travelled >= range || !ball.isValid()) {
                return false;
            }
            Location from = position.clone();
            velocity.setY(velocity.getY() - BALL_GRAVITY).multiply(AIR_DRAG);
            move();
            if (done) {
                return false;
            }
            ball.moveTo(position);
            flight(from, tick);
            if (grounded && velocity.length() < REST_SPEED) {
                return ++resting < 6;
            }
            resting = 0;
            return true;
        }

        /** One tick of movement in sub-steps, each axis on its own, with bounces and enemy hits. */
        private void move() {
            grounded = false;
            boolean rolled = false;
            int steps = Math.max(1, (int) Math.ceil(velocity.length() / SUBSTEP));
            for (int i = 0; i < steps && !done; i++) {
                Location before = position.clone();
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
                            grounded = true;
                            rolled = true;
                            if (-velocity.getY() > MIN_BOUNCE) {
                                bounceFx(position.clone().add(0, -BALL_RADIUS, 0), true, -velocity.getY());
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
                            banked = true;
                            Vector normal = new Vector(axis == 0 ? -Math.signum(delta) : 0, 0,
                                    axis == 2 ? -Math.signum(delta) : 0);
                            bounceFx(position.clone().add(normal.multiply(-BALL_RADIUS)), false, speed);
                        }
                    }
                }
                travelled += position.distance(before);
                touchEnemies();
                if (travelled >= range) {
                    break;
                }
            }
            if (rolled) {
                velocity.setX(velocity.getX() * ROLL_FRICTION).setZ(velocity.getZ() * ROLL_FRICTION);
            }
        }

        private void touchEnemies() {
            for (LivingEntity enemy : ctx().enemiesNear(owner, position, SHOT_HIT_RADIUS)) {
                if (hit.add(enemy.getUniqueId())) {
                    strike(enemy);
                    return;
                }
            }
        }

        /** The ball smacks into an enemy: damage, a push along the ball's flight, and the ball caroms off. */
        private void strike(LivingEntity enemy) {
            double damage = Math.max(0, params().num("shot_damage"));
            if (banked) {
                damage *= 1 + Math.max(0, params().num("bank_bonus")) / 100.0;
            }
            Combat.hit(ctx(), owner, enemy, damage);
            Location body = Fx.body(enemy);
            Vector flight = Motion.horizontal(velocity);
            if (flight.lengthSquared() < 1.0E-6) {
                flight = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
            }
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                double speed = Math.max(0, params().num("shot_push")) / PUSH_PER_SPEED;
                Motion.push(enemy, flight.clone().multiply(speed).setY(PUSH_LIFT), MAX_SPEED);
            }
            // Carom: reflect off the enemy's body and lose most of the pace.
            Vector normal = Motion.horizontal(position.toVector().subtract(body.toVector()));
            if (normal.lengthSquared() < 1.0E-6) {
                normal = flight.clone().multiply(-1);
            }
            double into = velocity.dot(normal);
            if (into < 0) {
                velocity.subtract(normal.clone().multiply(2 * into));
            }
            velocity.multiply(ENEMY_BOUNCE).setY(Math.max(velocity.getY(), 0.25));

            Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.CRIT, false);
            Vfx.cloud(ctx(), body, Particle.CLOUD, 4, 0.2, 0.05, null, false);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.0f + Fx.jitter(0.08f));
            Fx.sound(body, Sound.BLOCK_SLIME_BLOCK_HIT, 0.8f, 0.7f + Fx.jitter(0.08f));
            Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, 0.9f + Fx.jitter(0.08f));
            if (banked) {
                popText(body.clone().add(0, enemy.getHeight() * 0.6, 0), "<b><gradient:#FFFFFF:#3DDC84>반사 슛!"
                        + "</gradient></b>", 0.9f, 18);
                Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
            }
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">" + (banked ? "반사 슛 적중!" : "슈팅 적중!") + "</"
                    + COLOR_TAG + ">"));
        }

        /** A puff where the ball bounced, louder and larger the harder it hit. */
        private void bounceFx(Location at, boolean ground, double speed) {
            float volume = (float) Math.min(1.0, 0.3 + speed);
            if (ground) {
                Vfx.ring(ctx(), at.clone().add(0, 0.05, 0), 0.35, 8, 0, Particle.DUST, CHALK, false);
                world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 4), 0.15, 0.02, 0.15, 0, turf());
            } else {
                Vfx.cloud(ctx(), at, Particle.CLOUD, 4, 0.1, 0.04, null, false);
                Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 6, 0.2, 0, GLOW, false);
            }
            Fx.sound(at, Sound.ENTITY_SLIME_JUMP_SMALL, volume, 1.3f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, volume * 0.7f, ground ? 1.1f : 1.4f);
        }

        /** A chalk streak behind the ball, green motes, and turf when it rolls. */
        private void flight(Location from, long tick) {
            if (from.distanceSquared(position) > 1.0E-4) {
                Vfx.line(ctx(), from, position, 2.5, Particle.DUST, CHALK, false);
            }
            if (tick % 2 == 0) {
                Vfx.dot(position, Particle.DUST_COLOR_TRANSITION, TURF_FADE, false);
            }
            if (grounded && velocity.lengthSquared() > 0.01) {
                world.spawnParticle(Particle.BLOCK, position.clone().add(0, -BALL_RADIUS, 0), 1, 0.1, 0, 0.1, 0,
                        turf());
            }
        }

        /** The loop ended (range, rest or time): the ball pops. */
        void finish() {
            done = true;
            ball.pop(true);
        }
    }

    // ------------------------------------------------------------------ ultimate: banana kick

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double range = Math.max(3, params().num("ult_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        if (target instanceof Player victim && !owner.canSee(victim)) {
            target = null; // never curve towards (and reveal) a hidden enemy
        }
        BananaKick kick = new BananaKick(owner, target, range);
        kick.setUp();
        Loop.repeat(ctx(), owner, 0, 1, ULT_WINDUP + (long) Math.ceil(range * 1.6 / ULT_SPEED) + 2, kick, kick::finish);
        return ActivationResult.success();
    }

    /**
     * The banana kick: a short wind-up with the curve drawn ahead, then a burning ball flying a quadratic curve from
     * the feet to the target (bending out to whichever side is clear) and on along its tangent. It burns through
     * every enemy close to the path; the first one hit triggers the goal.
     */
    private final class BananaKick implements LongPredicate {

        private final Player owner;
        private final @Nullable LivingEntity target;
        private final double range;
        private final World world;
        private final Set<UUID> hit = new HashSet<>();
        private List<Vector> path = new ArrayList<>();
        private int index;
        private @Nullable Ball ball;
        private boolean goal;
        private boolean done;

        BananaKick(Player owner, @Nullable LivingEntity target, double range) {
            this.owner = owner;
            this.target = target;
            this.range = range;
            this.world = owner.getWorld();
        }

        void setUp() {
            Ball dribbled = dribbleBall;
            dribbleBall = null;
            if (dribbled != null) {
                dribbled.remove();
            }
            path = plan();
            Location start = path.getFirst().toLocation(world);
            Ball fireball = new Ball(start, ULT_WINDUP + (long) Math.ceil(range * 1.6 / ULT_SPEED) + 12, true);
            Displays.outline(fireball.shell, FIRE.body());
            ball = fireball;
            Fx.sound(start, Sound.BLOCK_NOTE_BLOCK_FLUTE, 1.0f, 2.0f);
            Fx.sound(start, Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(start, Sound.ENTITY_BLAZE_SHOOT, 0.5f, 1.4f + Fx.jitter(0.05f));
            owner.sendActionBar(Text.mm("<gradient:#FFF3B0:#FF8A1F><b>바나나킥!</b></gradient>"));
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            Ball fireball = ball;
            if (fireball == null || !fireball.isValid()) {
                return false;
            }
            if (tick < ULT_WINDUP) {
                windup(fireball, tick);
                return true;
            }
            if (tick == ULT_WINDUP) {
                path = plan();
                index = 0;
                kickFx(path.getFirst().toLocation(world));
            }
            return fly(fireball, tick);
        }

        /** The ball catches fire at the feet while the curve it will take flickers ahead. */
        private void windup(Ball fireball, long tick) {
            Location at = fireball.at;
            double phase = tick * 0.9;
            for (int i = 0; i < 2; i++) {
                double angle = phase + i * Math.PI;
                Vfx.dot(at.clone().add(Math.cos(angle) * 0.4, 0.1 * i, Math.sin(angle) * 0.4), Particle.FLAME, null,
                        true);
            }
            Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 4, 0.15, 0, EMBER, false);
            if (tick % 2 == 0) {
                path = plan();
                fireball.moveTo(path.getFirst().toLocation(world));
                drawPath(path, tick);
            }
            if (tick == ULT_WINDUP - 3) {
                Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_FLUTE, 1.0f, 2.0f);
            }
        }

        /** The telegraph: the curve drawn as glowing dots, a ring around the aimed enemy. */
        private void drawPath(List<Vector> points, long tick) {
            int stride = Math.max(1, (int) Math.round(0.7 / PATH_STEP));
            for (int i = (int) (tick / 2 % stride); i < points.size(); i += stride) {
                Vfx.dot(points.get(i).toLocation(world), Particle.DUST_COLOR_TRANSITION, EMBER_FADE, true);
            }
            LivingEntity aimed = aimed();
            if (aimed != null) {
                Vfx.ring(ctx(), aimed.getLocation().add(0, 0.1, 0), 0.9, 14, tick * 0.3,
                        Particle.DUST_COLOR_TRANSITION, EMBER, true);
            }
        }

        /**
         * The aimed enemy while the kick may still curve towards it: alive, in this world and not hidden from the
         * owner. An enemy that slips into stealth during the wind-up is let go (no ring, no curve into it), so the
         * kick never gives its hiding place away.
         */
        private @Nullable LivingEntity aimed() {
            LivingEntity aimed = target;
            if (aimed == null || !aimed.isValid() || !aimed.getWorld().equals(world)) {
                return null;
            }
            return aimed instanceof Player victim && !owner.canSee(victim) ? null : aimed;
        }

        private void kickFx(Location at) {
            Vfx.burst(ctx(), at, FIRE, 0.9, Particle.FLAME, true);
            Vfx.cloud(ctx(), at.clone().add(0, -0.2, 0), Particle.BLOCK, 10, 0.25, 0.12, turf(), true);
            Vfx.dot(at, Particle.SWEEP_ATTACK, null, true);
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.2f, 0.6f);
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.2f, 0.5f);
            Fx.sound(at, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ENTITY_BREEZE_SHOOT, 0.8f, 0.7f + Fx.jitter(0.05f));
        }

        /** One tick of flight along the planned curve: walls end it, enemies close to it burn. */
        private boolean fly(Ball fireball, long tick) {
            Vector previous = path.get(index);
            Location from = previous.toLocation(world);
            double budget = ULT_SPEED;
            while (budget > 0 && index < path.size() - 1) {
                Vector next = path.get(index + 1);
                Vector delta = next.clone().subtract(previous);
                double length = delta.length();
                if (length > 1.0E-6) {
                    RayTraceResult wall = world.rayTraceBlocks(previous.toLocation(world), delta.clone().multiply(1 / length),
                            length, FluidCollisionMode.NEVER, true);
                    if (wall != null) {
                        Location stop = wall.getHitPosition().toLocation(world)
                                .subtract(delta.clone().multiply(0.25 / length));
                        fireball.moveTo(stop);
                        trail(from, stop, delta, tick);
                        crash(stop);
                        return false;
                    }
                }
                index++;
                previous = next;
                budget -= length;
                burn(next.toLocation(world), delta);
            }
            Location now = previous.toLocation(world);
            fireball.moveTo(now);
            trail(from, now, now.toVector().subtract(from.toVector()), tick);
            if (index >= path.size() - 1) {
                fizzle(now);
                return false;
            }
            return true;
        }

        private void burn(Location at, Vector direction) {
            for (LivingEntity enemy : ctx().enemiesNear(owner, at, ULT_HIT_RADIUS)) {
                if (!hit.add(enemy.getUniqueId())) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, Math.max(0, params().num("ult_damage")));
                Location body = Fx.body(enemy);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    ignite(enemy, params().ticks("ult_burn"));
                    Vector push = Motion.horizontal(direction);
                    if (push.lengthSquared() < 1.0E-6) {
                        push = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
                    }
                    double speed = Math.max(0, params().num("ult_push")) / PUSH_PER_SPEED;
                    Motion.push(enemy, push.multiply(speed).setY(ULT_PUSH_LIFT), MAX_SPEED);
                }
                Vfx.burst(ctx(), body, FIRE, 0.8, Particle.FLAME, true);
                Vfx.cloud(ctx(), body, Particle.LAVA, 3, 0.2, 0, null, false);
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f + Fx.jitter(0.08f));
                Fx.sound(body, Sound.ENTITY_BLAZE_HURT, 0.6f, 1.3f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.ITEM_FIRECHARGE_USE, 0.7f, 1.2f + Fx.jitter(0.1f));
                if (!goal) {
                    goal = true;
                    celebrate(at, direction);
                }
            }
        }

        /** Fire along the segment flown this tick, a white curl twisting around it and smoke behind. */
        private void trail(Location from, Location to, Vector direction, long tick) {
            if (from.distanceSquared(to) > 1.0E-4) {
                Vfx.line(ctx(), from, to, 3, Particle.DUST_COLOR_TRANSITION, EMBER, true);
            }
            Vfx.cloud(ctx(), to, Particle.FLAME, 4, 0.15, 0.02, null, true);
            Vfx.dot(to, Particle.SMALL_FLAME, null, false);
            Vector axis = direction.lengthSquared() > 1.0E-6 ? direction.clone().normalize() : new Vector(0, 0, 1);
            Vector[] basis = basis(axis);
            for (int strand = 0; strand < 2; strand++) {
                double angle = tick * 1.1 + strand * Math.PI;
                Location curl = to.clone().add(basis[0].clone().multiply(Math.cos(angle) * 0.35))
                        .add(basis[1].clone().multiply(Math.sin(angle) * 0.35));
                Vfx.dot(curl, Particle.DUST, strand == 0 ? CHALK : PALETTE.dust(0.7f), false);
            }
            if (tick % 2 == 0) {
                Vfx.cloud(ctx(), from, Particle.SMOKE, 2, 0.1, 0.01, null, false);
            }
            if (tick % 4 == 0) {
                Fx.sound(to, Sound.ENTITY_BLAZE_SHOOT, 0.4f, 1.6f + Fx.jitter(0.1f));
            }
        }

        /** The ball hits a wall: it bursts into flames and is gone. */
        private void crash(Location at) {
            done = true;
            Vfx.burst(ctx(), at, FIRE, 1.0, Particle.FLAME, true);
            Vfx.cloud(ctx(), at, Particle.LARGE_SMOKE, 6, 0.3, 0.02, null, false);
            Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.0f, 0.6f);
            if (!goal) {
                popText(at.clone().add(0, 0.8, 0), "<b><gradient:#FFF3B0:#FF8A1F>골대 강타!</gradient></b>", 1.0f, 26);
            }
            Ball fireball = ball;
            if (fireball != null) {
                fireball.pop(true);
            }
        }

        private void fizzle(Location at) {
            done = true;
            Vfx.cloud(ctx(), at, Particle.SMOKE, 8, 0.2, 0.03, null, false);
            Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 8, 0.25, 0, EMBER_FADE, false);
            Fx.sound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.4f + Fx.jitter(0.1f));
            Ball fireball = ball;
            if (fireball != null) {
                fireball.pop(true);
            }
        }

        /** Runs when the loop ends by itself (only possible if the flight ran out of time). */
        void finish() {
            if (!done) {
                done = true;
                Ball fireball = ball;
                if (fireball != null) {
                    fireball.pop(true);
                }
            }
        }

        /**
         * The flight path: a quadratic curve from the feet to the target (or to a point on the aim line), bent to the
         * right, else to the left, else not at all when walls are in the way, then straight on along its tangent
         * until the range is used up.
         */
        private List<Vector> plan() {
            Location feet = owner.getLocation();
            Vector facing = Motion.horizontalFacing(feet);
            Location startSpot = kickSpot(feet, facing);
            Vector start = startSpot.toVector();
            Vector end;
            LivingEntity aimed = aimed();
            if (aimed != null) {
                end = Fx.body(aimed).toVector();
            } else {
                Location eye = owner.getEyeLocation();
                Vector look = eye.getDirection();
                double reach = range * FREE_BEND;
                RayTraceResult wall = world.rayTraceBlocks(eye, look, reach, FluidCollisionMode.NEVER, true);
                if (wall != null) {
                    reach = Math.max(1.5, wall.getHitPosition().distance(eye.toVector()) - 0.6);
                }
                end = eye.toVector().add(look.clone().multiply(reach));
                end.setY(Math.max(end.getY(), feet.getY() + 0.6));
            }
            Vector chord = end.clone().subtract(start);
            double distance = chord.length();
            Vector side = new Vector(-chord.getZ(), 0, chord.getX());
            side = side.lengthSquared() < 1.0E-6 ? new Vector(-facing.getZ(), 0, facing.getX()) : side.normalize();
            double bend = Math.clamp(distance * CURVE_FACTOR, CURVE_MIN, CURVE_MAX);
            Vector middle = start.clone().add(end).multiply(0.5).add(new Vector(0, CURVE_RISE, 0));
            Vector control = middle.clone();
            for (double sign : new double[] {-1, 1}) {
                Vector candidate = middle.clone().add(side.clone().multiply(sign * bend));
                if (Spots.clearPath(startSpot, candidate.toLocation(world))
                        && Spots.clearPath(candidate.toLocation(world), end.toLocation(world))) {
                    control = candidate;
                    break;
                }
            }
            List<Vector> points = new ArrayList<>();
            double rough = start.distance(control) + control.distance(end);
            int samples = Math.max(4, (int) Math.ceil(rough / PATH_STEP));
            for (int i = 0; i <= samples; i++) {
                double t = (double) i / samples;
                double a = (1 - t) * (1 - t);
                double b = 2 * (1 - t) * t;
                double c = t * t;
                points.add(start.clone().multiply(a).add(control.clone().multiply(b)).add(end.clone().multiply(c)));
            }
            Vector tangent = end.clone().subtract(control);
            if (tangent.lengthSquared() < 1.0E-6) {
                tangent = chord.lengthSquared() < 1.0E-6 ? facing.clone() : chord.clone();
            }
            tangent.normalize();
            double extra = Math.max(3, range - rough);
            int more = (int) Math.ceil(extra / PATH_STEP);
            for (int i = 1; i <= more; i++) {
                points.add(end.clone().add(tangent.clone().multiply(i * PATH_STEP)));
            }
            return points;
        }

        /**
         * 골! A goal frame (posts, crossbar and a cobweb net) rises right behind the enemy and the net bulges with the
         * ball; a whistle, cheers, confetti and a big headline.
         */
        private void celebrate(Location at, Vector direction) {
            Vector forward = Motion.horizontal(direction);
            if (forward.lengthSquared() < 1.0E-6) {
                forward = Motion.horizontalFacing(owner.getLocation());
            }
            Vector side = new Vector(-forward.getZ(), 0, forward.getX());
            Location base = at.clone().add(forward.clone().multiply(1.6));
            Double ground = groundBelow(base);
            base.setY(ground != null ? ground : at.getY() - 1.0);
            float yaw = (float) Math.toDegrees(Math.atan2(-forward.getX(), forward.getZ()));
            Player owner = this.owner;

            BlockData frame = GOAL_FRAME.createBlockData();
            Location left = base.clone().add(side.clone().multiply(GOAL_WIDTH / 2)).add(0, GOAL_HEIGHT / 2, 0);
            Location right = base.clone().add(side.clone().multiply(-GOAL_WIDTH / 2)).add(0, GOAL_HEIGHT / 2, 0);
            Location top = base.clone().add(0, GOAL_HEIGHT, 0);
            Location netAt = base.clone().add(forward.clone().multiply(0.15)).add(0, GOAL_HEIGHT / 2, 0);
            List<BlockDisplay> parts = new ArrayList<>();
            parts.add(goalPart(left, frame, Displays.transform(0, 0, 0, POST, GOAL_HEIGHT, POST, yaw, 0, 0)));
            parts.add(goalPart(right, frame, Displays.transform(0, 0, 0, POST, GOAL_HEIGHT, POST, yaw, 0, 0)));
            parts.add(goalPart(top, frame, Displays.transform(0, 0, 0, GOAL_WIDTH + POST, POST, POST, yaw, 0, 0)));
            BlockDisplay net = goalPart(netAt, GOAL_NET.createBlockData(),
                    Displays.transform(0, 0, 0, GOAL_WIDTH, GOAL_HEIGHT, 0.05f, yaw, 0, 0));
            parts.add(net);
            // The net bulges backwards as the ball hits it, then settles.
            Vector3f bulge = new Vector3f((float) forward.getX(), 0, (float) forward.getZ()).mul(0.3f);
            ctx().runLater(owner, 3, () -> Displays.animate(net, Displays.transform(bulge.x, bulge.y, bulge.z,
                    GOAL_WIDTH * 0.96f, GOAL_HEIGHT * 0.96f, 0.6f, yaw, 0, 0), 3));
            ctx().runLater(owner, 8, () -> Displays.animate(net, Displays.transform(0, 0, 0, GOAL_WIDTH, GOAL_HEIGHT,
                    0.05f, yaw, 0, 0), 6));
            ctx().runLater(owner, GOAL_TICKS - 8, () -> {
                for (BlockDisplay part : parts) {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 6);
                    }
                }
            });

            Location headline = top.clone().add(0, 1.0, 0);
            TextDisplay text = Displays.text(ctx(), owner, headline,
                    Text.mm("<b><gradient:#FFFFFF:#3DDC84:#FFFFFF>골!!!</gradient></b>"), 0.5f, GOAL_TICKS);
            Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.6f, 0, 3.0f, 3.0f, 3.0f, 0, 0, 0), 4);
            ctx().runLater(owner, 6, () -> Displays.animate(text, Displays.centered(0, 0.8f, 0, 2.4f, 2.4f, 2.4f, 0,
                    0, 0), 6));
            ctx().runLater(owner, GOAL_TICKS - 6, () -> {
                if (text.isValid()) {
                    Displays.shrinkAway(ctx(), owner, text, 5);
                }
            });

            Vfx.dot(top, Particle.FLASH, PALETTE.core(), true);
            Vfx.cloud(ctx(), top, Particle.FIREWORK, 30, 0.6, 0.25, null, true);
            Location center = base.clone().add(0, 1.0, 0);
            Loop.repeat(ctx(), owner, 2, 2, 8, run -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < Vfx.n(ctx(), 6); i++) {
                    Location bit = Vfx.scatter(center, 2.5).add(0, 1.5 + random.nextDouble() * 1.5, 0);
                    Vfx.dot(bit, Particle.DUST, random.nextBoolean() ? CHALK : PALETTE.dust(1.0f), true);
                }
                Vfx.cloud(ctx(), center.clone().add(0, 2, 0), Particle.FIREWORK, 3, 1.2, 0.05, null, true);
                return true;
            });
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_FLUTE, 1.5f, 2.0f);
            ctx().runLater(owner, 3, () -> Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_FLUTE, 1.5f, 1.9f));
            Fx.sound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f, 1.2f);
            Fx.sound(at, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.2f, 1.0f);
            Fx.sound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.1f);
            Fx.sound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            owner.sendActionBar(Text.mm("<b><gradient:#FFFFFF:#3DDC84>골!!!</gradient></b>"));
            // Names go in as components: a name is never MiniMessage (a custom name can even be legacy text).
            ctx().announce(Text.mm("<" + COLOR_TAG + ">⚽ <white><name></white> 선수의 바나나킥, <b>골!!!</b></"
                    + COLOR_TAG + ">", Placeholder.component("name", owner.name())));
        }

        private BlockDisplay goalPart(Location at, BlockData data, Transformation pose) {
            Transformation start = new Transformation(pose.getTranslation(), pose.getLeftRotation(),
                    new Vector3f(pose.getScale()).mul(1f, 0.05f, 1f), pose.getRightRotation());
            BlockDisplay part = Displays.block(ctx(), owner, at, data, start, GOAL_TICKS);
            Displays.animateNextTick(ctx(), owner, part, pose, 3);
            return part;
        }
    }

    // ------------------------------------------------------------------ the ball

    /**
     * The football: a white cube with a smaller black cube turned onto its corner inside, whose tips poke out as
     * patches. It rolls about the axis across its motion by the distance it covered.
     */
    private final class Ball {

        private final BlockDisplay shell;
        private final BlockDisplay patches;
        private Quaternionf spin = new Quaternionf();
        private Location at;

        Ball(Location at, long lifetime, boolean fire) {
            this.at = at.clone();
            Player owner = player();
            shell = Displays.block(ctx(), owner, at, SHELL.createBlockData(), cubePose(SHELL_SIZE * 0.2f, spin),
                    lifetime);
            patches = Displays.block(ctx(), owner, at, (fire ? Material.ORANGE_CONCRETE : PATCH).createBlockData(),
                    cubePose(PATCH_SIZE * 0.2f, PATCH_ORIENTATION), lifetime);
            shell.setTeleportDuration(1);
            patches.setTeleportDuration(1);
            Displays.animateNextTick(ctx(), owner, shell, cubePose(SHELL_SIZE, spin), 3);
            Displays.animateNextTick(ctx(), owner, patches, cubePose(PATCH_SIZE, PATCH_ORIENTATION), 3);
        }

        boolean isValid() {
            return shell.isValid() && patches.isValid();
        }

        void moveTo(Location to) {
            if (!isValid()) {
                return;
            }
            Vector delta = to.toVector().subtract(at.toVector());
            Vector axis = new Vector(0, 1, 0).crossProduct(delta);
            if (axis.lengthSquared() > 1.0E-8) {
                double angle = Math.min(MAX_SPIN, delta.length() / BALL_RADIUS);
                axis.normalize();
                spin = new Quaternionf().rotateAxis((float) angle, (float) axis.getX(), (float) axis.getY(),
                        (float) axis.getZ()).mul(spin).normalize();
            }
            at = to.clone();
            at.setYaw(0);
            at.setPitch(0);
            Displays.moveTo(shell, at);
            Displays.moveTo(patches, at);
            Displays.animate(shell, cubePose(SHELL_SIZE, spin), 1);
            Displays.animate(patches, cubePose(PATCH_SIZE, new Quaternionf(spin).mul(PATCH_ORIENTATION)), 1);
        }

        /** Shrinks the ball away; {@code puff} adds a little pop of dust. */
        void pop(boolean puff) {
            if (puff && isValid()) {
                Vfx.cloud(ctx(), at, Particle.POOF, 5, 0.1, 0.03, null, false);
                Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 6, 0.2, 0, GLOW, false);
                Fx.sound(at, Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.6f + Fx.jitter(0.05f));
            }
            for (BlockDisplay display : new BlockDisplay[] {shell, patches}) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), player(), display, 4);
                }
            }
        }

        void remove() {
            shell.remove();
            patches.remove();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** True if the ball's box at {@code center} touches a block (or anything else that collides). */
    private static boolean collides(World world, Location center) {
        double r = BALL_RADIUS;
        return world.hasCollisionsIn(new BoundingBox(center.getX() - r, center.getY() - r, center.getZ() - r,
                center.getX() + r, center.getY() + r, center.getZ() + r));
    }

    /** The top of the first solid block at most 4 blocks below {@code at}, null if none. */
    private static @Nullable Double groundBelow(Location at) {
        World world = at.getWorld();
        int x = at.getBlockX();
        int z = at.getBlockZ();
        int start = Math.min(at.getBlockY(), world.getMaxHeight() - 1);
        for (int y = start; y >= Math.max(world.getMinHeight(), start - 4); y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    private static BlockData turf() {
        return TURF.createBlockData();
    }

    /** An onomatopoeia popping up with an overshoot, then shrinking away. */
    private void popText(Location at, String text, float scale, long life) {
        Player owner = player();
        float roll = (float) ThreadLocalRandom.current().nextDouble(-10, 10);
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

    /** A ring around the direction of motion (in the plane across it). */
    private <T> void verticalRing(Location center, Vector direction, double radius, int points, double phase,
                                  Particle particle, @Nullable T data, boolean force) {
        Vector[] basis = basis(direction.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : direction.clone().normalize());
        int count = Vfx.n(ctx(), points);
        for (int i = 0; i < count; i++) {
            double angle = phase + i * TAU / count;
            Location at = center.clone().add(basis[0].clone().multiply(Math.cos(angle) * radius))
                    .add(basis[1].clone().multiply(Math.sin(angle) * radius));
            Vfx.dot(at, particle, data, force);
        }
    }

    /** Two unit vectors perpendicular to the unit vector {@code axis} and to each other. */
    private static Vector[] basis(Vector axis) {
        Vector helper = Math.abs(axis.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = axis.clone().crossProduct(helper).normalize();
        Vector v = axis.clone().crossProduct(u).normalize();
        return new Vector[] {u, v};
    }

    private static void ignite(LivingEntity target, long ticks) {
        if (ticks <= 0 || !target.isValid()) {
            return;
        }
        int fireTicks = (int) Math.min(Integer.MAX_VALUE, ticks);
        if (target.getFireTicks() < fireTicks) {
            target.setFireTicks(fireTicks);
        }
    }

    /** A cube of edge {@code size} centered on the block display's origin, rotated around its center. */
    private static Transformation cubePose(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }
}
