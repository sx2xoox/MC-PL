package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
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
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 야구선수 — a slugger. Melee hits knock enemies far and every third hit in a row is a clean hit that sends them
 * flying, the active bats a line drive that staggers (longer on a head hit), and the ultimate is a giant bat swung
 * through everyone in front, launching them sky-high; their landing hurts and is credited to the batter.
 *
 * <p>Visuals: a light birch bat with a black grip (two block displays) swings at the owner's side; the baseball is
 * a camera-facing snowball display with red seams spiralling around its streak. The home run telegraphs its fan on
 * the ground (white with red stitches), launches with a crack and a white flash, and every launched enemy leaves a
 * white streak with a twinkle at the top of its flight.
 */
public final class BatterAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("batter", "야구선수", Material.STICK)
            .color(TextColor.color(0xE53935))
            .difficulty(2)
            .passive("배트",
                    "근접 공격한 적을 약 {bat_push}블록 쳐냅니다. 연달아 {crit_every}번째로 맞힐 때마다 클린 히트가 "
                            + "되어 피해가 {crit_bonus}% 커지고 약 {crit_push}블록 날려보냅니다"
                            + "({count_reset}초 안에 다시 맞히지 못하면 카운트가 초기화됩니다).")
            .active("강속구",
                    "공을 띄워 배트로 후려쳐 빠른 직선 타구를 날립니다({fastball_range}블록). 처음 맞은 적에게 "
                            + "{fastball_damage} 피해를 주고 {fastball_stun}초간 경직시키며, 머리에 맞으면 "
                            + "{head_bonus} 추가 피해와 함께 {head_stun}초간 경직시킵니다.",
                    8)
            .ultimate("만루 홈런",
                    "{ult_windup}초간 거대한 배트를 크게 당겼다가 휘둘러 앞쪽 {ult_radius}블록 안의 적에게 "
                            + "{ult_damage} 피해를 주고 약 {ult_height}블록 높이, {ult_distance}블록 멀리 날려버립니다. "
                            + "떨어지며 받는 낙하 피해(최대 {ult_fall_max})도 나의 공격으로 기록됩니다.")
            .param("bat_push", 3.5)
            .param("crit_every", 3)
            .param("crit_bonus", 50)
            .param("crit_push", 7)
            .param("count_reset", 4)
            .param("fastball_range", 30)
            .param("fastball_damage", 4)
            .param("fastball_stun", 0.4)
            .param("head_bonus", 3)
            .param("head_stun", 1)
            .param("ult_windup", 0.5)
            .param("ult_radius", 4.5)
            .param("ult_damage", 6)
            .param("ult_height", 8)
            .param("ult_distance", 10)
            .param("ult_fall_max", 6)
            .build();

    private static final String COLOR_TAG = "#E53935";
    /** Chalk white core, stitch red body, deep red edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xE53935, 0x7F0000);
    private static final Particle.DustTransition GLOW = PALETTE.glow(0.9f);
    private static final Particle.DustTransition FADE = PALETTE.fade(0.9f);
    private static final Particle.DustOptions CHALK = new Particle.DustOptions(Color.WHITE, 0.8f);
    private static final Particle.DustOptions STITCH = new Particle.DustOptions(Color.fromRGB(0xE53935), 0.6f);
    private static final Particle.DustOptions WOOD = new Particle.DustOptions(Color.fromRGB(0xD9B98A), 0.9f);
    private static final double TAU = Math.PI * 2;
    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;
    /** Blocks of knockback travel per unit of horizontal launch speed (with the lifts below). */
    private static final double PUSH_PER_SPEED = 8.0;
    private static final double BAT_LIFT = 0.36;
    private static final double CRIT_LIFT = 0.5;
    /** Vanilla horizontal air drag of a flying player. */
    private static final double AIR_DRAG = 0.91;

    // bat displays
    private static final Material BAT_WOOD = Material.BIRCH_PLANKS;
    private static final Material BAT_GRIP = Material.BLACK_CONCRETE;
    private static final float BAT_LENGTH = 1.05f;
    private static final float BAT_WIDTH = 0.12f;
    private static final float GRIP_WIDTH = 0.07f;
    private static final float GRIP_SHARE = 0.32f;

    // passive
    private static final int STREAK_TICKS = 10;

    // active: fastball
    private static final int TOSS_TICKS = 4;
    private static final double FASTBALL_SPEED = 2.6;
    /** The ball's reach around its line when touching an enemy's hitbox. */
    private static final double BALL_PAD = 0.25;
    /** Hits above this share of the hitbox height count as head hits. */
    private static final double HEAD_LINE = 0.72;
    /** Over this distance the drawn ball merges from the hand into the eye line it really flies on. */
    private static final double MERGE_DISTANCE = 3.0;
    private static final float BALL_SCALE = 0.32f;

    // ultimate: home run
    private static final float GIANT = 3.0f;
    /** Half width of the swing fan in degrees (a bit more than the front half). */
    private static final double SWING_HALF_ANGLE = 100;
    private static final int SWING_TICKS = 5;
    private static final double SWING_FROM = 150;
    private static final double SWING_TO = -70;
    private static final long FLIGHT_MAX_TICKS = 100;
    private static final int FLIGHT_MIN_TICKS = 5;
    private static final int HOMERUN_TEXT_TICKS = 40;

    /** Landed melee hits in the current streak and when the last one landed. */
    private int count;
    private long lastHit = Long.MIN_VALUE / 2;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        count = 0;
    }

    // ------------------------------------------------------------------ passive: bat

    @Override
    public void onTick(long tick) {
        if (tick % 8 != 0 || streak() != critEvery() - 1 || critEvery() <= 1) {
            return;
        }
        // The next hit is a clean hit: the bat hand glints red and white.
        Location hand = hand(player());
        Vfx.dot(hand, Particle.DUST_COLOR_TRANSITION, GLOW, false);
        Vfx.dot(hand.clone().add(0, 0.15, 0), Particle.ELECTRIC_SPARK, null, false);
    }

    /** The clean hit's bonus: only changes the damage, the victim may still cancel the hit. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnMelee(event, victim) || streak() + 1 < critEvery()) {
            return;
        }
        double bonus = Math.max(0, params().num("crit_bonus")) / 100.0;
        event.setDamage(event.getDamage() * (1 + bonus));
    }

    /** The hit landed: count it, knock the victim away (far on a clean hit). */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnMelee(event, victim)) {
            return;
        }
        int streak = streak() + 1;
        lastHit = ctx().currentTick();
        boolean clean = streak >= critEvery();
        count = clean ? 0 : streak;
        Player owner = player();
        Vector direction = Motion.horizontalFacing(owner.getLocation());
        double blocks = Math.max(0, params().num(clean ? "crit_push" : "bat_push"));
        // Vanilla knockback is applied after this event; push on the next tick so it is not overwritten.
        ctx().runLater(owner, 1, () -> knock(victim, direction, blocks, clean));
        if (clean) {
            cleanHitFx(victim);
        } else {
            hitFx(victim, streak);
        }
    }

    private boolean isOwnMelee(EntityDamageByEntityEvent event, LivingEntity victim) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && event.getCause() == DamageCause.ENTITY_ATTACK && ctx().isEnemy(player(), victim);
    }

    private int critEvery() {
        return Math.max(1, params().integer("crit_every"));
    }

    /** Hits in the current streak; the streak is over once {@code count_reset} passed without a hit. */
    private int streak() {
        return ctx().currentTick() - lastHit > params().ticks("count_reset") ? 0 : count;
    }

    private void knock(LivingEntity victim, Vector direction, double blocks, boolean clean) {
        if (!Combat.isTargetable(ctx(), player(), victim) || blocks <= 0) {
            return;
        }
        Vector push = direction.clone().multiply(blocks / PUSH_PER_SPEED).setY(clean ? CRIT_LIFT : BAT_LIFT);
        if (Motion.push(victim, push, MAX_SPEED) && clean) {
            streak(victim, STREAK_TICKS);
        }
    }

    /** An ordinary bat hit: a short white swoosh and a wooden knock, and the count in the action bar. */
    private void hitFx(LivingEntity victim, int streak) {
        Player owner = player();
        Location body = Fx.body(victim);
        Vfx.arc(ctx(), body, 0.7, owner.getLocation().getYaw(), 120, 9, Particle.DUST, CHALK, false);
        Vfx.cloud(ctx(), body, Particle.CRIT, 4, 0.2, 0.15, null, false);
        Fx.sound(body, Sound.BLOCK_BAMBOO_WOOD_HIT, 1.0f, 1.0f + 0.15f * streak + Fx.jitter(0.05f));
        countBar(streak, false);
    }

    /** 클린 히트: a crack, a red-white burst and a "딱!" popping up. */
    private void cleanHitFx(LivingEntity victim) {
        Location body = Fx.body(victim);
        Vfx.burst(ctx(), body, PALETTE, 0.8, Particle.CRIT, false);
        Vfx.arc(ctx(), body, 0.9, player().getLocation().getYaw(), 150, 14, Particle.DUST_COLOR_TRANSITION, GLOW,
                false);
        Vfx.cloud(ctx(), body, Particle.SWEEP_ATTACK, 1, 0.1, 0, null, false);
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.9f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.BLOCK_BAMBOO_WOOD_BREAK, 1.0f, 1.3f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.5f, 1.6f + Fx.jitter(0.05f));
        popText(body.clone().add(0, victim.getHeight() * 0.5 + 0.2, 0),
                "<b><gradient:#FFFFFF:#E53935>딱!</gradient></b>", 1.0f, 16);
        countBar(critEvery(), true);
    }

    /** The streak as a baseball count: ● per hit, ○ for the rest, and the clean hit called out. */
    private void countBar(int streak, boolean clean) {
        if (clean) {
            player().sendActionBar(Text.mm("<" + COLOR_TAG + "><b>클린 히트!</b></" + COLOR_TAG + ">"));
            return;
        }
        StringBuilder dots = new StringBuilder();
        for (int i = 1; i <= critEvery(); i++) {
            dots.append(i <= streak ? "<white>●</white> " : "<dark_gray>○</dark_gray> ");
        }
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">배트</" + COLOR_TAG + "> " + dots.toString().trim()));
    }

    /** A fading white streak following a knocked enemy. */
    private void streak(LivingEntity victim, int ticks) {
        Location[] last = {Fx.body(victim)};
        Loop.repeat(ctx(), player(), 1, 1, ticks, run -> {
            if (!victim.isValid() || victim.isDead()) {
                return false;
            }
            Location now = Fx.body(victim);
            if (now.distanceSquared(last[0]) > 1.0E-3) {
                Vfx.line(ctx(), last[0], now, 2, Particle.DUST, CHALK, false);
            }
            last[0] = now;
            return true;
        });
    }

    // ------------------------------------------------------------------ active: fastball

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("fastball_range"));
        Fastball ball = new Fastball(owner, range);
        ball.toss();
        Loop.repeat(ctx(), owner, 1, 1, TOSS_TICKS + (long) Math.ceil(range / FASTBALL_SPEED) + 2, ball, ball::finish);
        return ActivationResult.success();
    }

    /**
     * The fastball: tossed up from the hand, batted on the {@link #TOSS_TICKS}th tick along the owner's aim at that
     * moment, then a straight line at {@link #FASTBALL_SPEED} blocks per tick that stops at walls and on the first
     * enemy whose hitbox it crosses. It really flies along the eye line (so aiming at a head hits the head); the
     * drawn ball starts at the hand and merges into that line.
     */
    private final class Fastball implements LongPredicate {

        private final Player owner;
        private final double range;
        private final World world;
        private final ItemDisplay ball;
        private final Bat bat;
        private Location position;
        private Vector direction = new Vector(0, 0, 1);
        private Vector handOffset = new Vector();
        private double travelled;
        private boolean flying;
        private boolean done;

        Fastball(Player owner, double range) {
            this.owner = owner;
            this.range = range;
            this.world = owner.getWorld();
            this.position = hand(owner);
            long life = TOSS_TICKS + (long) Math.ceil(range / FASTBALL_SPEED) + 8;
            ball = Displays.item(ctx(), owner, position, ItemStack.of(Material.SNOWBALL),
                    Displays.centered(0, 0, 0, BALL_SCALE, BALL_SCALE, BALL_SCALE, 0, 0, 0), life);
            ball.setBillboard(Display.Billboard.CENTER);
            ball.setTeleportDuration(1);
            bat = new Bat(owner, 1f, TOSS_TICKS + 16);
        }

        /** The toss: the ball pops up from the hand while the bat is cocked behind the shoulder. */
        void toss() {
            Location at = hand(owner);
            Displays.moveTo(ball, at.clone().add(0, 0.6, 0));
            bat.pose(owner, SWING_FROM, 0.6, 1);
            Vfx.cloud(ctx(), at, Particle.DUST, 4, 0.1, 0, CHALK, false);
            Fx.sound(at, Sound.ENTITY_SNOWBALL_THROW, 0.7f, 1.4f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.2f + Fx.jitter(0.05f));
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            if (!flying) {
                if (tick + 1 < TOSS_TICKS) {
                    // The ball hangs at the top of the toss; the bat draws back a little more.
                    bat.pose(owner, SWING_FROM + 6 * (tick + 1), 0.6, 1);
                    return true;
                }
                hit();
                return true;
            }
            return fly(tick);
        }

        /** Crack: the bat whips through and the ball leaves along the eye line. */
        private void hit() {
            flying = true;
            Location eye = owner.getEyeLocation();
            direction = eye.getDirection();
            position = eye.clone();
            Location from = hand(owner);
            handOffset = from.toVector().subtract(eye.toVector());
            bat.swing(owner, SWING_FROM + 18, -40, 3, 0.15);
            bat.fadeAfter(5);
            Vfx.dot(from, Particle.FLASH, PALETTE.core(), false);
            verticalRing(from, direction, 0.35, 10, 0, Particle.DUST, CHALK, false);
            Vfx.cloud(ctx(), from, Particle.CRIT, 6, 0.1, 0.25, null, false);
            Fx.sound(from, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.3f + Fx.jitter(0.05f));
            Fx.sound(from, Sound.BLOCK_BAMBOO_WOOD_BREAK, 1.0f, 1.5f + Fx.jitter(0.05f));
            Fx.sound(from, Sound.ENTITY_ARROW_SHOOT, 0.7f, 1.8f + Fx.jitter(0.05f));
        }

        private boolean fly(long tick) {
            double step = Math.min(FASTBALL_SPEED, range - travelled);
            if (step <= 1.0E-3) {
                fizzle();
                return false;
            }
            Vector start = position.toVector();
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double reach = wall == null ? step : wall.getHitPosition().distance(start);
            Hit victim = firstEnemy(start, reach);
            Location before = drawn();
            if (victim != null) {
                position = victim.point().toLocation(world);
                travelled += victim.distance();
                Displays.moveTo(ball, drawn());
                streakFx(before, drawn(), tick);
                strike(victim);
                return false;
            }
            if (wall != null) {
                position = wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.15));
                travelled += reach;
                Displays.moveTo(ball, drawn());
                streakFx(before, drawn(), tick);
                thunk(wall);
                return false;
            }
            position = position.clone().add(direction.clone().multiply(step));
            travelled += step;
            Displays.moveTo(ball, drawn());
            streakFx(before, drawn(), tick);
            return true;
        }

        /** Where the ball is drawn: the real position, offset towards the hand at the start of the flight. */
        private Location drawn() {
            double merge = Math.max(0, 1 - travelled / MERGE_DISTANCE);
            return position.clone().add(handOffset.clone().multiply(merge));
        }

        /** The nearest enemy whose (padded) hitbox the segment of this tick crosses, with where it was crossed. */
        private @Nullable Hit firstEnemy(Vector start, double reach) {
            Location middle = start.clone().add(direction.clone().multiply(reach / 2)).toLocation(world);
            Hit best = null;
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, reach / 2 + 1.5)) {
                BoundingBox box = enemy.getBoundingBox().expand(BALL_PAD);
                double distance;
                Vector point;
                if (box.contains(start)) {
                    distance = 0;
                    point = start.clone();
                } else {
                    RayTraceResult crossing = box.rayTrace(start, direction, reach);
                    if (crossing == null) {
                        continue;
                    }
                    point = crossing.getHitPosition();
                    distance = point.distance(start);
                }
                if (best == null || distance < best.distance()) {
                    best = new Hit(enemy, point, distance);
                }
            }
            return best;
        }

        /** The ball lands: damage and a stagger, more of both on a head hit. */
        private void strike(Hit hit) {
            done = true;
            LivingEntity victim = hit.victim();
            BoundingBox box = victim.getBoundingBox();
            boolean head = hit.point().getY() >= box.getMinY() + box.getHeight() * HEAD_LINE;
            double damage = Math.max(0, params().num("fastball_damage"))
                    + (head ? Math.max(0, params().num("head_bonus")) : 0);
            Combat.hit(ctx(), owner, victim, damage);
            long stun = params().ticks(head ? "head_stun" : "fastball_stun");
            Location at = hit.point().toLocation(world);
            if (Combat.isTargetable(ctx(), owner, victim)) {
                if (stun > 0) {
                    ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
                    ctx().addModifier(owner, victim, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
                    daze(victim, stun, head);
                }
                Vector push = Motion.horizontal(direction).multiply(0.25).setY(0.12);
                Motion.push(victim, push, MAX_SPEED);
            }
            Vfx.burst(ctx(), at, PALETTE, head ? 0.7 : 0.5, Particle.CRIT, false);
            Fx.sound(at, Sound.ENTITY_ARROW_HIT_PLAYER, 0.8f, 1.4f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.2f + Fx.jitter(0.08f));
            if (head) {
                Vfx.dot(at, Particle.FLASH, PALETTE.core(), false);
                Fx.sound(at, Sound.BLOCK_ANVIL_LAND, 0.4f, 2.0f);
                Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 1.8f);
                popText(at.clone().add(0, 0.5, 0), "<b><gradient:#FFFFFF:#E53935>헤드샷!</gradient></b>", 1.0f, 20);
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>헤드샷!</b></" + COLOR_TAG + ">"));
            } else {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">강속구 적중!</" + COLOR_TAG + ">"));
            }
            drop(at, 6);
        }

        /** The ball smacks a wall and drops. */
        private void thunk(RayTraceResult wall) {
            done = true;
            Location at = position.clone();
            Block block = wall.getHitBlock();
            if (block != null) {
                world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 8), 0.12, 0.12, 0.12, 0, block.getBlockData());
            }
            Vfx.cloud(ctx(), at, Particle.DUST, 5, 0.12, 0, CHALK, false);
            Fx.sound(at, Sound.ENTITY_ARROW_HIT, 0.8f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_WOOD_HIT, 0.8f, 1.2f + Fx.jitter(0.05f));
            drop(at, 8);
        }

        private void fizzle() {
            done = true;
            Vfx.cloud(ctx(), drawn(), Particle.DUST, 4, 0.1, 0, CHALK, false);
            drop(drawn(), 4);
        }

        /** The ball falls away and is gone. */
        private void drop(Location at, int ticks) {
            if (!ball.isValid()) {
                return;
            }
            ball.setTeleportDuration(Math.max(1, ticks));
            Displays.moveTo(ball, at.clone().add(0, -0.6, 0));
            ctx().runLater(owner, ticks, () -> {
                if (ball.isValid()) {
                    Displays.shrinkAway(ctx(), owner, ball, 3);
                }
            });
        }

        /** A white streak with two red seams twisting around it. */
        private void streakFx(Location from, Location to, long tick) {
            if (from.distanceSquared(to) < 1.0E-4) {
                return;
            }
            Vfx.line(ctx(), from, to, 3, Particle.DUST, CHALK, false);
            Vector axis = to.toVector().subtract(from.toVector());
            double length = axis.length();
            Vector[] basis = basis(axis.multiply(1 / length));
            int points = Vfx.n(ctx(), (int) Math.ceil(length * 2));
            for (int i = 0; i < points; i++) {
                double t = (i + 0.5) / points;
                for (int seam = 0; seam < 2; seam++) {
                    double angle = (tick + t) * 2.6 + seam * Math.PI;
                    Location at = from.clone().add(to.clone().subtract(from).toVector().multiply(t))
                            .add(basis[0].clone().multiply(Math.cos(angle) * 0.12))
                            .add(basis[1].clone().multiply(Math.sin(angle) * 0.12));
                    Vfx.dot(at, Particle.DUST, STITCH, false);
                }
            }
        }

        /** The loop ended by itself (out of time): make sure the ball is gone. */
        void finish() {
            if (!done) {
                fizzle();
            }
        }
    }

    /** Where the fastball crossed an enemy's hitbox. */
    private record Hit(LivingEntity victim, Vector point, double distance) {
    }

    /** Stars circling a staggered head: white for a body hit, red and white for a head hit. */
    private void daze(LivingEntity victim, long ticks, boolean head) {
        Loop.repeat(ctx(), player(), 1, 2, Math.max(1, ticks / 2), run -> {
            if (!victim.isValid() || victim.isDead()) {
                return false;
            }
            Location top = victim.getLocation().add(0, victim.getHeight() + 0.2, 0);
            int stars = head ? 3 : 2;
            for (int i = 0; i < stars; i++) {
                double angle = run * 0.8 + i * TAU / stars;
                Location at = top.clone().add(Math.cos(angle) * 0.42, 0.05 * Math.sin(run + i), Math.sin(angle) * 0.42);
                Vfx.dot(at, Particle.DUST, i % 2 == 0 ? CHALK : STITCH, false);
                if (head && run % 2 == 0 && i == 0) {
                    Vfx.dot(at, Particle.WAX_ON, null, false);
                }
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ ultimate: grand slam

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("ult_radius"));
        boolean anyone = false;
        for (LivingEntity enemy : ctx().enemiesNear(owner, Fx.body(owner), radius)) {
            if (!(enemy instanceof Player victim) || owner.canSee(victim)) {
                anyone = true;
                break;
            }
        }
        if (!anyone) {
            return ActivationResult.fail("배트가 닿을 거리(" + AbilityInfo.formatNumber(radius) + "블록)에 적이 없습니다");
        }
        HomeRun homeRun = new HomeRun(owner, radius);
        homeRun.windup();
        Loop.repeat(ctx(), owner, 1, 1, homeRun.windupTicks + 1, homeRun, homeRun::finish);
        return ActivationResult.success();
    }

    /**
     * The grand slam: the owner plants (rooted) and draws a giant bat back over the shoulder while the fan it will
     * cover is drawn on the ground, then swings through everyone in front at once and launches them.
     */
    private final class HomeRun implements LongPredicate {

        private final Player owner;
        private final double radius;
        private final long windupTicks;
        private final Bat bat;
        private @Nullable BlockDisplay plate;
        private boolean swung;

        HomeRun(Player owner, double radius) {
            this.owner = owner;
            this.radius = radius;
            this.windupTicks = Math.max(1, params().ticks("ult_windup"));
            this.bat = new Bat(owner, GIANT, windupTicks + SWING_TICKS + 12);
        }

        void windup() {
            ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1,
                    windupTicks + 2);
            ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, windupTicks + 2);
            Location feet = owner.getLocation().add(0, 0.02, 0);
            // The plate is a diamond with a corner pointing where the batter faces (the display itself is unrotated).
            float plateYaw = feet.getYaw() + 45;
            plate = Displays.block(ctx(), owner, feet, Material.WHITE_CONCRETE.createBlockData(),
                    Displays.transform(0, 0, 0, 0.05f, 0.02f, 0.05f, plateYaw, 0, 0), windupTicks + SWING_TICKS + 10);
            Displays.animateNextTick(ctx(), owner, plate, Displays.transform(0, 0, 0, 0.75f, 0.02f, 0.75f, plateYaw, 0,
                    0), 4);
            bat.pose(owner, SWING_FROM, 0.9, 1);
            Fx.sound(feet, Sound.ITEM_CROSSBOW_LOADING_START, 1.0f, 0.6f);
            Fx.sound(feet, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.0f, 0.5f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>예고 홈런!</b></" + COLOR_TAG + ">"));
        }

        @Override
        public boolean test(long tick) {
            if (!swung) {
                if (tick + 1 < windupTicks) {
                    charge(tick);
                    return true;
                }
                swing();
            }
            return false;
        }

        /** The bat creaks back further; the fan on the ground follows the owner's aim. */
        private void charge(long tick) {
            double progress = (double) (tick + 1) / windupTicks;
            bat.pose(owner, SWING_FROM + 15 * progress, 0.9 + 0.2 * progress, 2);
            if (tick % 2 == 0) {
                drawFan(tick);
            }
            Location hands = hand(owner);
            Location tip = hands.clone().add(bat.direction().multiply(BAT_LENGTH * GIANT));
            Vfx.trail(tip, hands, PALETTE.core(), 6, true);
            if (tick % 4 == 0) {
                Fx.sound(hands, Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 0.9f, 0.6f + 0.4f * (float) progress);
                Fx.sound(hands, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, 0.6f + 0.3f * (float) progress);
            }
        }

        /** The swing's reach on the ground: a white fan edge with red stitches, and spokes at its sides. */
        private void drawFan(long tick) {
            Location feet = owner.getLocation().add(0, 0.1, 0);
            float yaw = feet.getYaw();
            double width = SWING_HALF_ANGLE * 2;
            Vfx.arc(ctx(), feet, radius, yaw, width, (int) Math.max(16, radius * 7), Particle.DUST_COLOR_TRANSITION,
                    GLOW, true);
            Vfx.arc(ctx(), feet, radius - 0.35, yaw + (tick % 4 == 0 ? 4 : -4), width - 10,
                    (int) Math.max(8, radius * 3), Particle.DUST, STITCH, true);
            for (int s = -1; s <= 1; s += 2) {
                double angle = Math.toRadians(yaw + s * SWING_HALF_ANGLE) + Math.PI / 2;
                Location edge = feet.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
                Vfx.line(ctx(), feet, edge, 1.5, Particle.DUST, CHALK, true);
            }
        }

        /** Swing: everything in the fan is hit at once and launched; the bat whips through over a few ticks. */
        private void swing() {
            swung = true;
            Location feet = owner.getLocation();
            Vector facing = Motion.horizontalFacing(feet);
            Location chest = Fx.body(owner);
            bat.swing(owner, SWING_FROM + 15, SWING_TO, SWING_TICKS, 0.15);
            bat.fadeAfter(SWING_TICKS + 4);
            if (plate != null && plate.isValid()) {
                Displays.shrinkAway(ctx(), owner, plate, 6);
            }

            List<LivingEntity> launched = new ArrayList<>();
            double cosLimit = Math.cos(Math.toRadians(SWING_HALF_ANGLE));
            for (LivingEntity enemy : ctx().enemiesNear(owner, chest, radius)) {
                Vector to = Motion.horizontal(enemy.getLocation().toVector().subtract(feet.toVector()));
                if (to.lengthSquared() > 1.0E-6 && to.dot(facing) < cosLimit) {
                    continue;
                }
                if (!Spots.clearPath(chest, Fx.body(enemy))) {
                    continue;
                }
                launched.add(enemy);
            }
            double damage = Math.max(0, params().num("ult_damage"));
            int hits = 0;
            for (LivingEntity enemy : launched) {
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    launch(enemy, facing, feet);
                }
            }
            swingFx(chest, feet, facing, hits);
        }

        private void launch(LivingEntity enemy, Vector facing, Location feet) {
            Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(feet.toVector()));
            Vector direction = facing.clone().multiply(0.6).add(away.multiply(0.4));
            direction = direction.lengthSquared() < 1.0E-6 ? facing.clone() : direction.normalize();
            double height = Math.max(0, params().num("ult_height"));
            double up = Motion.launchSpeed(height);
            double forward = horizontalSpeed(Math.max(0, params().num("ult_distance")), airTicks(up));
            if (Motion.push(enemy, direction.multiply(forward).setY(up), MAX_SPEED)) {
                new Flight(owner, enemy).start();
            }
        }

        private void swingFx(Location chest, Location feet, Vector facing, int hits) {
            float yaw = feet.getYaw();
            for (int i = 0; i < 3; i++) {
                Location layer = chest.clone().add(0, -0.3 + 0.3 * i, 0);
                Vfx.arc(ctx(), layer, radius * (0.55 + 0.15 * i), yaw, SWING_HALF_ANGLE * 2, 22,
                        Particle.DUST_COLOR_TRANSITION, i == 1 ? GLOW : FADE, true);
            }
            for (int i = -2; i <= 2; i++) {
                double angle = Math.toRadians(yaw + i * 35) + Math.PI / 2;
                Vfx.dot(chest.clone().add(Math.cos(angle) * radius * 0.6, 0, Math.sin(angle) * radius * 0.6),
                        Particle.SWEEP_ATTACK, null, true);
            }
            Location front = chest.clone().add(facing.clone().multiply(1.2));
            Vfx.dot(front, Particle.FLASH, PALETTE.core(), true);
            Vfx.cloud(ctx(), front, Particle.CRIT, 20, 0.5, 0.5, null, true);
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.5, radius, 5, Particle.DUST, CHALK, true);
            Fx.sound(front, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.6f);
            Fx.sound(front, Sound.ITEM_MACE_SMASH_AIR, 1.0f, 1.2f);
            if (hits == 0) {
                Fx.sound(front, Sound.ENTITY_VILLAGER_NO, 1.0f, 0.8f);
                popText(chest.clone().add(0, 1.4, 0), "<b><gray>헛스윙...</gray></b>", 1.2f, 24);
                owner.sendActionBar(Text.mm("<gray>헛스윙...</gray>"));
                return;
            }
            Fx.sound(front, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 0.7f);
            Fx.sound(front, Sound.BLOCK_BAMBOO_WOOD_BREAK, 1.5f, 0.8f);
            Fx.sound(front, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 1.0f, 1.3f);
            Fx.sound(front, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f + Fx.jitter(0.05f));
            String headline = hits >= 2 ? "만루 홈런!!" : "홈런!";
            homeRunText(chest.clone().add(0, 1.6, 0), headline);
            Fx.sound(chest, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.3f);
            ctx().runLater(owner, 4, () -> Fx.sound(owner.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f,
                    1.2f));
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>" + headline + "</b> <white>" + hits + "명</white></"
                    + COLOR_TAG + ">"));
            // Names go in as components: a name is never MiniMessage.
            ctx().announce(Text.mm("<" + COLOR_TAG + ">⚾ <white><name></white> 선수, <b>" + headline + "</b></"
                    + COLOR_TAG + ">", Placeholder.component("name", owner.name())));
        }

        /** A big rising headline over the batter, with sparkles bursting around it. */
        private void homeRunText(Location at, String headline) {
            TextDisplay text = Displays.text(ctx(), owner, at,
                    Text.mm("<b><gradient:#FFFFFF:#E53935:#FFFFFF>" + headline + "</gradient></b>"), 0.4f,
                    HOMERUN_TEXT_TICKS);
            Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.5f, 0, 2.6f, 2.6f, 2.6f, 0, 0, 0), 4);
            ctx().runLater(owner, 6, () -> Displays.animate(text, Displays.centered(0, 1.2f, 0, 2.2f, 2.2f, 2.2f, 0, 0,
                    0), 20));
            ctx().runLater(owner, HOMERUN_TEXT_TICKS - 6, () -> {
                if (text.isValid()) {
                    Displays.shrinkAway(ctx(), owner, text, 5);
                }
            });
            Vfx.cloud(ctx(), at, Particle.FIREWORK, 24, 0.8, 0.15, null, true);
            Vfx.ring(ctx(), at, 1.4, 20, 0, Particle.DUST_COLOR_TRANSITION, GLOW, true);
        }

        /** Runs when the loop ends by itself: after the swing the bat fades on its own; otherwise it is removed. */
        void finish() {
            if (swung) {
                return;
            }
            bat.remove();
            if (plate != null && plate.isValid()) {
                plate.remove();
            }
        }
    }

    /**
     * One enemy flying off a home run: its fall distance is kept at zero on the way (vanilla fall damage would be
     * nobody's), the peak is recorded, and on landing it takes the fall as credited damage (capped).
     */
    private final class Flight implements LongPredicate {

        private final Player owner;
        private final LivingEntity enemy;
        private double peak;
        private Location last;
        private boolean twinkled;

        Flight(Player owner, LivingEntity enemy) {
            this.owner = owner;
            this.enemy = enemy;
            this.last = Fx.body(enemy);
            this.peak = enemy.getLocation().getY();
        }

        void start() {
            enemy.setFallDistance(0);
            Loop.repeat(ctx(), owner, 1, 1, FLIGHT_MAX_TICKS, this);
        }

        @Override
        public boolean test(long tick) {
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return false;
            }
            enemy.setFallDistance(0);
            double y = enemy.getLocation().getY();
            if (y > peak) {
                peak = y;
            } else if (!twinkled && tick > 2 && y < peak - 0.1) {
                twinkled = true;
                twinkle();
            }
            Location now = Fx.body(enemy);
            if (now.distanceSquared(last) > 1.0E-3) {
                Vfx.line(ctx(), last, now, 2, Particle.DUST, CHALK, true);
                Vfx.dot(now, Particle.DUST, STITCH, false);
            }
            last = now;
            if (tick >= FLIGHT_MIN_TICKS && (Motion.isGrounded(enemy) || enemy.isInWater())) {
                land();
                return false;
            }
            return true;
        }

        /** The top of the flight: a star twinkles like a ball vanishing into the sky. */
        private void twinkle() {
            Location at = enemy.getLocation().add(0, enemy.getHeight() + 0.4, 0);
            Vfx.dot(at, Particle.FLASH, PALETTE.core(), true);
            Vfx.cloud(ctx(), at, Particle.END_ROD, 8, 0.1, 0.12, null, true);
            Fx.sound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE_FAR, 1.0f, 1.5f + Fx.jitter(0.1f));
        }

        private void land() {
            Location feet = enemy.getLocation();
            double fallen = peak - feet.getY();
            double damage = enemy.isInWater() ? 0 : Math.clamp(fallen - 3, 0, Math.max(0, params().num("ult_fall_max")));
            if (damage > 0) {
                Combat.hit(ctx(), owner, enemy, damage, DamageType.FALL);
            }
            Location ground = feet.clone().add(0, 0.1, 0);
            Vfx.ring(ctx(), ground, 0.9, 14, 0, Particle.DUST, CHALK, false);
            Block below = feet.clone().add(0, -0.5, 0).getBlock();
            if (!below.isPassable()) {
                feet.getWorld().spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 14), 0.4, 0.05, 0.4, 0,
                        below.getBlockData());
            }
            Vfx.cloud(ctx(), ground, Particle.CLOUD, 6, 0.3, 0.03, null, false);
            Fx.sound(ground, Sound.ENTITY_PLAYER_BIG_FALL, 1.0f, 0.9f + Fx.jitter(0.05f));
            Fx.sound(ground, Sound.ENTITY_GENERIC_BIG_FALL, 0.8f, 0.7f + Fx.jitter(0.05f));
        }
    }

    // ------------------------------------------------------------------ the bat

    /**
     * A bat at the owner's hands: a light wooden barrel and a black grip, two block displays sharing their origin at
     * the hands. Its direction is an angle around the owner (0 = straight ahead, positive = to the right) and a
     * tilt upwards.
     */
    private final class Bat {

        private final BlockDisplay barrel;
        private final BlockDisplay grip;
        private final float scale;
        private Vector direction = new Vector(0, 0, 1);

        Bat(Player owner, float scale, long lifetime) {
            this.scale = scale;
            Location at = hand(owner);
            barrel = Displays.block(ctx(), owner, at, BAT_WOOD.createBlockData(),
                    stickPose(direction, 0, 0.01f, 0.01f), lifetime);
            grip = Displays.block(ctx(), owner, at, BAT_GRIP.createBlockData(), stickPose(direction, 0, 0.01f, 0.01f),
                    lifetime);
            for (BlockDisplay part : new BlockDisplay[] {barrel, grip}) {
                part.setTeleportDuration(1);
            }
        }

        Vector direction() {
            return direction.clone();
        }

        /** Points the bat at {@code angle} degrees around the owner with the given upward tilt. */
        void pose(Player owner, double angle, double tilt, int ticks) {
            if (!barrel.isValid() || !grip.isValid()) {
                return;
            }
            Location feet = owner.getLocation();
            Vector facing = Motion.horizontalFacing(feet);
            Vector right = new Vector(-facing.getZ(), 0, facing.getX());
            double radians = Math.toRadians(angle);
            direction = facing.clone().multiply(Math.cos(radians)).add(right.multiply(Math.sin(radians)))
                    .setY(tilt).normalize();
            Location at = hand(owner);
            Displays.moveTo(barrel, at);
            Displays.moveTo(grip, at);
            float length = BAT_LENGTH * scale;
            Displays.animate(barrel, stickPose(direction, length * GRIP_SHARE, length * (1 - GRIP_SHARE),
                    BAT_WIDTH * scale), ticks);
            Displays.animate(grip, stickPose(direction, 0, length * GRIP_SHARE + 0.01f, GRIP_WIDTH * scale), ticks);
        }

        /** Swings from {@code from} to {@code to} degrees in steps of at most 60 degrees per tick, leaving an arc. */
        void swing(Player owner, double from, double to, int ticks, double tilt) {
            int steps = Math.max(ticks, (int) Math.ceil(Math.abs(to - from) / 60.0));
            Loop.repeat(ctx(), owner, 0, 1, steps, run -> {
                double angle = from + (to - from) * (run + 1) / steps;
                pose(owner, angle, tilt, 1);
                Location hands = hand(owner);
                Location tip = hands.clone().add(direction.clone().multiply(BAT_LENGTH * scale));
                Vfx.line(ctx(), hands.clone().add(direction.clone().multiply(BAT_LENGTH * scale * 0.4)), tip, 3,
                        Particle.DUST, WOOD, scale > 1);
                Vfx.dot(tip, Particle.DUST_COLOR_TRANSITION, GLOW, scale > 1);
                return true;
            });
        }

        void fadeAfter(long ticks) {
            Player owner = player();
            ctx().runLater(owner, ticks, () -> {
                for (BlockDisplay part : new BlockDisplay[] {barrel, grip}) {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 4);
                    }
                }
            });
        }

        void remove() {
            barrel.remove();
            grip.remove();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The owner's batting hands: chest height, a little forward and to the right. */
    private static Location hand(Player owner) {
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontalFacing(feet);
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        Location at = feet.add(0, owner.getHeight() * 0.62, 0).add(facing.multiply(0.3)).add(right.multiply(0.3));
        at.setYaw(0);
        at.setPitch(0);
        return at;
    }

    /** Ticks an entity launched upwards with {@code speed} stays in the air before it is back at its start height. */
    private static int airTicks(double speed) {
        double y = 0;
        double v = speed;
        for (int tick = 1; tick <= 400; tick++) {
            y += v;
            v = (v - Motion.GRAVITY) * Motion.DRAG;
            if (y <= 0 && tick > 1) {
                return tick;
            }
        }
        return 400;
    }

    /** The horizontal launch speed that covers {@code distance} blocks in {@code ticks} under air drag. */
    private static double horizontalSpeed(double distance, int ticks) {
        double travel = (1 - Math.pow(AIR_DRAG, Math.max(1, ticks))) / (1 - AIR_DRAG);
        return distance / travel;
    }

    /** A box of the given width from the display's origin along {@code direction}, from {@code start} to its end. */
    private static Transformation stickPose(Vector direction, float start, float length, float width) {
        Vector3f axis = new Vector3f((float) direction.getX(), (float) direction.getY(), (float) direction.getZ());
        if (axis.lengthSquared() < 1.0E-6f) {
            axis.set(0, 0, 1);
        }
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), axis.normalize());
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, start));
        return new Transformation(translation, rotation, new Vector3f(width, width, Math.max(0.01f, length)),
                new Quaternionf());
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
}
