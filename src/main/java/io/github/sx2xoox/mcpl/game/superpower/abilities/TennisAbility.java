package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import net.kyori.adventure.title.Title;
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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 테니스 천재 — the sports-manga tennis prodigy whose ball knocks people flat. The serve is a fast straight shot that
 * may skip once off the ground or a wall; a ball that hits comes back and hangs in the air in front of the prodigy, and a
 * melee swing at it keeps the rally going, faster and harder every time. Enemies can swing back at an incoming ball:
 * returned, it comes home harmless, the rally breaks and they score on the 15-30-40 board over the prodigy's head. First
 * to four is "게임!" and charges the ultimate. The ultimate is a jump smash onto a marked court circle, the ball then
 * bouncing on to the nearest enemies, each of whom can return it too.
 *
 * <p>Look (ball lime, court green, line white): a fuzzy lime ball of two turned cubes with a white seam, spinning and
 * leaving a lime trail with white sparks at high rally; the serve is a white racket arc. The score board is a white and
 * lime text over the head. The smash marks a court circle with white lines, the prodigy rises and drives a glowing ball
 * down, the court flashes and shatters into rings, and the ball skips from enemy to enemy.
 */
public final class TennisAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("tennis", "테니스 천재", Material.LIME_DYE)
            .color(TextColor.color(0xCDEB3A))
            .difficulty(2)
            .passive("랠리",
                    "내 공이 적을 맞히면 내 쪽으로 튕겨 돌아와 {rally_window}초간 눈앞에 떠 있습니다. 그 공을 근접 "
                            + "공격으로 치면({rally_reach}블록 안) 쿨타임 없이 바라보는 방향으로 다시 날아가고, 랠리마다 "
                            + "공의 속도와 피해가 {rally_bonus}%씩 늘어납니다(최대 {rally_max}랠리). 적도 날아오는 공을 "
                            + "근접 공격으로 받아칠 수 있습니다(공이 {return_reach}블록 안에 왔을 때, 또는 맞기 직전 "
                            + "{return_window}초 안에 휘둘렀을 때) — 받아친 공은 해 없이 나에게 돌아오고 랠리가 끊기며 "
                            + "상대가 점수를 얻습니다. 내 공이 맞히면 내가 점수를 얻고, 머리 위 15-30-40 점수판에서 먼저 "
                            + "{game_points}점을 따면 \"게임!\" — 궁극기 게이지가 {game_gauge}% 찹니다.")
            .active("서브",
                    "{serve_toss}초간 공을 띄웠다가 라켓으로 강하게 쳐서 바라보는 방향으로 빠른 직선 공을 날립니다"
                            + "({serve_range}블록, 초속 {serve_speed}블록). 공은 땅이나 벽에 한 번 튕길 수 있고, 처음 "
                            + "맞은 적에게 {serve_damage} 피해를 주고 약 {serve_push}블록 밀쳐냅니다.",
                    7)
            .ultimate("점프 스매시",
                    "조준한 곳({smash_range}블록)에 코트 원을 그리고 {smash_height}블록 뛰어올라, 정점에서 공을 "
                            + "내리꽂습니다. 반경 {smash_radius}블록의 적에게 {smash_damage} 피해를 주고 약 {smash_lift}"
                            + "블록 띄웁니다(전투 맵에서는 지형도 부숩니다). 이어 공이 크게 튕겨 {bounce_range}블록 안의 "
                            + "가까운 적 최대 {bounce_count}명에게 차례로 날아가 각각 {bounce_damage} 피해를 줍니다(날아오는 "
                            + "공은 근접 공격으로 받아칠 수 있고, 받아치면 튕기기가 끝납니다). 낙하 피해를 받지 않습니다.")
            .param("rally_window", 1.2)
            .param("rally_reach", 4.5)
            .param("rally_bonus", 20)
            .param("rally_max", 5)
            .param("return_reach", 3.5)
            .param("return_window", 0.3)
            .param("game_points", 4)
            .param("game_gauge", 20)
            .param("serve_toss", 0.3)
            .param("serve_range", 30)
            .param("serve_speed", 30)
            .param("serve_damage", 4)
            .param("serve_push", 3)
            .param("smash_range", 24)
            .param("smash_height", 6)
            .param("smash_radius", 3.5)
            .param("smash_damage", 6)
            .param("smash_lift", 1.5)
            .param("smash_terrain", 1.5)
            .param("bounce_range", 10)
            .param("bounce_count", 3)
            .param("bounce_damage", 3)
            .build();

    private static final String LIME_TAG = "#CDEB3A";
    private static final String COURT_TAG = "#2F7D4A";

    // ------------------------------------------------------------------ look
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFAFAFA, 0xCDEB3A, 0x2F7D4A);
    private static final Color LIME = Color.fromRGB(0xCDEB3A);
    private static final Color WHITE = Color.fromRGB(0xFAFAFA);
    private static final Particle.DustOptions LIME_DUST = new Particle.DustOptions(LIME, 0.9f);
    private static final Particle.DustOptions WHITE_DUST = new Particle.DustOptions(WHITE, 0.8f);
    private static final Particle.DustOptions COURT_DUST = new Particle.DustOptions(Color.fromRGB(0x2F7D4A), 1.2f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);

    /** Block types (block data is created on use: it needs a running server). */
    private static final Material FELT = Material.LIME_WOOL;
    private static final Material FELT_LIGHT = Material.LIME_CONCRETE_POWDER;
    private static final Material SEAM = Material.WHITE_CONCRETE;

    private static final float BALL_SIZE = 0.28f;
    private static final float SMASH_BALL_SIZE = 0.5f;
    private static final double BALL_HITBOX = 0.3;
    private static final double SUB_STEP = 0.6;
    private static final double BACK_SPEED = 0.9;
    private static final double RETURN_SPEED = 1.2;
    /** A ball that hangs in front of the prodigy: this far ahead of the eyes, a little below them. */
    private static final double FLOAT_AHEAD = 1.6;
    private static final double FLOAT_BELOW = 0.25;
    private static final double BOUNCE_DAMP = 0.8;
    private static final double FACING_DOT = 0.2;
    private static final double PUSH_LIFT = 0.3;
    /** Horizontal blocks an entity flies per block/tick of initial speed, for a lift of {@link #PUSH_LIFT}. */
    private static final double PUSH_REACH = 6.0;
    private static final double MAX_SPEED = 2.0;
    private static final long DROP_TICKS = 20;
    private static final long MAX_BALL_TICKS = 20 * 30;
    private static final int FOLLOW_LERP = 3;
    private static final String[] CALLS = {"0", "15", "30", "40"};

    // ultimate
    private static final long SMASH_SAFE_TICKS = 100;
    private static final long SETTLE_TICKS = 10;
    private static final int SMASH_FLIGHT = 4;
    private static final double SMASH_FIND_GROUND = 40;
    /** Smashed enemies drift a little outwards as they fly up. */
    private static final double SMASH_SPREAD = 0.15;

    private enum Mode { TOSS, FLY, BACK, FLOAT, DROP, DONE }

    // ------------------------------------------------------------------ state

    /** The ball in play (one at a time). */
    private final class Ball {
        final List<BlockDisplay> parts = new ArrayList<>();
        final Set<UUID> struck = new HashSet<>();
        Location pos;
        Vector velocity = new Vector();
        Mode mode = Mode.TOSS;
        int rally;
        boolean bounced;
        boolean broken;
        double travelled;
        long modeSince;
        long floatUntil;
        long spin;

        Ball(Location pos) {
            this.pos = pos;
        }

        void setMode(Mode next, long now) {
            mode = next;
            modeSince = now;
        }

        void place() {
            spin++;
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                Displays.moveTo(part, pos);
                if (spin % 2 == 0) {
                    Displays.animate(part, ballShape(i, spin * 25f, BALL_SIZE), 2);
                }
            }
        }

        void remove() {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    part.remove();
                }
            }
            parts.clear();
        }
    }

    private final List<Display> live = new ArrayList<>();
    private @Nullable Ball ball;
    private int myPoints;
    private int rivalPoints;
    private @Nullable TextDisplay board;
    private String boardText = "";

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        Ball current = ball;
        if (current != null) {
            current.remove();
        }
        ball = null;
        board = null;
        live.clear();
    }

    // ================================================================== test hooks

    /** The ball's state ("TOSS", "FLY", "BACK", "FLOAT", "DROP"), or "NONE" without a ball in play. */
    public String ballState() {
        Ball current = ball;
        return current == null || current.mode == Mode.DONE ? "NONE" : current.mode.name();
    }

    /** The rallies of the ball in play (0 for a serve). */
    public int rally() {
        Ball current = ball;
        return current == null ? 0 : current.rally;
    }

    /** The prodigy's points in the current game. */
    public int myPoints() {
        return myPoints;
    }

    /** The opponents' points in the current game. */
    public int rivalPoints() {
        return rivalPoints;
    }

    // ================================================================== passive: the score board

    @Override
    public void onTick(long tick) {
        Player owner = player();
        Ball current = ball;
        String text = boardLine(current);
        if (text.isEmpty()) {
            TextDisplay shown = board;
            if (shown != null && shown.isValid()) {
                shown.remove();
            }
            board = null;
            boardText = "";
            return;
        }
        TextDisplay shown = board;
        Location over = owner.getLocation().add(0, owner.getHeight() + 0.55, 0);
        if (shown == null || !shown.isValid()) {
            shown = Displays.text(ctx(), owner, over, Text.mm(text), 0.8f, 0,
                    viewer -> !ctx().isConcealedFrom(owner, viewer));
            shown.setTeleportDuration(FOLLOW_LERP);
            board = shown;
            boardText = text;
        } else {
            Displays.moveTo(shown, over);
            if (!text.equals(boardText)) {
                shown.text(Text.mm(text));
                boardText = text;
            }
        }
    }

    private String boardLine(@Nullable Ball current) {
        boolean rallying = current != null && current.mode != Mode.DONE && current.rally > 0;
        if (myPoints == 0 && rivalPoints == 0 && !rallying) {
            return "";
        }
        String line = "<b><" + LIME_TAG + ">" + call(myPoints) + "</" + LIME_TAG + "></b> <gray>-</gray> <b><white>"
                + call(rivalPoints) + "</white></b>";
        if (rallying) {
            line += " <" + COURT_TAG + ">랠리 ×" + current.rally + "</" + COURT_TAG + ">";
        }
        return line;
    }

    private static String call(int points) {
        return CALLS[Math.clamp(points, 0, CALLS.length - 1)];
    }

    /** One point: the prodigy's (its ball hit) or the opponents' (they returned it). */
    private void score(Player owner, boolean mine) {
        if (mine) {
            myPoints++;
        } else {
            rivalPoints++;
        }
        int game = Math.max(1, params().integer("game_points"));
        Location head = owner.getLocation().add(0, owner.getHeight() + 0.6, 0);
        if (myPoints >= game) {
            myPoints = 0;
            rivalPoints = 0;
            ctx().addEarnedGauge(owner, Math.max(0, params().num("game_gauge")));
            gameFanfare(owner);
        } else if (rivalPoints >= game) {
            myPoints = 0;
            rivalPoints = 0;
            Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.8f, 0.7f);
            if (room(1)) {
                keep(Displays.text(ctx(), owner, head.clone().add(0, 0.4, 0),
                        Text.mm("<gray>게임 — 상대</gray>"), 0.8f, 30, viewer -> !ctx().isConcealedFrom(owner, viewer)));
            }
        } else {
            Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, mine ? 1.6f : 0.9f);
        }
    }

    /** "게임!": the court lines flash white around the prodigy and the board bursts. */
    private void gameFanfare(Player owner) {
        Location feet = owner.getLocation().add(0, 0.08, 0);
        Vector forward = Motion.horizontalFacing(owner.getLocation());
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        double halfLength = 4;
        double halfWidth = 2.5;
        Location[] corners = new Location[4];
        for (int i = 0; i < 4; i++) {
            double l = (i < 2 ? 1 : -1) * halfLength;
            double w = (i == 0 || i == 3 ? 1 : -1) * halfWidth;
            corners[i] = feet.clone().add(forward.clone().multiply(l)).add(right.clone().multiply(w));
        }
        for (int i = 0; i < 4; i++) {
            Vfx.line(ctx(), corners[i], corners[(i + 1) % 4], 2, Particle.DUST, WHITE_DUST, true);
        }
        Vfx.line(ctx(), feet.clone().add(right.clone().multiply(halfWidth)),
                feet.clone().add(right.clone().multiply(-halfWidth)), 2, Particle.DUST, WHITE_DUST, true);
        Location head = owner.getLocation().add(0, owner.getHeight() + 0.6, 0);
        Vfx.burst(ctx(), head, PALETTE, 0.8, Particle.END_ROD, true);
        Fx.sound(head, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
        Fx.sound(head, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
        if (room(1)) {
            TextDisplay call = keep(Displays.text(ctx(), owner, head.clone().add(0, 0.4, 0),
                    Text.mm("<b><gradient:#FAFAFA:#CDEB3A>게임!</gradient></b>"), 1.3f, 30,
                    viewer -> !ctx().isConcealedFrom(owner, viewer)));
            Displays.animateNextTick(ctx(), owner, call, Displays.centered(0, 0.4f, 0, 1.7f, 1.7f, 1.7f, 0, 0, 0), 8);
        }
        owner.sendActionBar(Text.mm("<b><" + LIME_TAG + ">게임!</" + LIME_TAG + "></b> <gray>궁극기 게이지 +"
                + AbilityInfo.formatNumber(params().num("game_gauge")) + "%</gray>"));
    }

    // ================================================================== active: serve

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Ball old = ball;
        if (old != null) {
            old.remove();
            old.mode = Mode.DONE;
        }
        Location start = handSpot(owner);
        Ball created = new Ball(start);
        for (int i = 0; i < 3; i++) {
            BlockDisplay part = Displays.block(ctx(), owner, start, partBlock(i), ballShape(i, 0, BALL_SIZE),
                    MAX_BALL_TICKS + DROP_TICKS);
            part.setTeleportDuration(1);
            created.parts.add(part);
        }
        created.setMode(Mode.TOSS, ctx().currentTick());
        ball = created;
        Fx.sound(start, Sound.ENTITY_SNOWBALL_THROW, 0.8f, 1.4f);
        long toss = Math.max(1, params().ticks("serve_toss"));
        Loop.repeat(ctx(), owner, 1, 1, MAX_BALL_TICKS, i -> step(owner, created, toss), created::remove);
        return ActivationResult.success();
    }

    private static Transformation ballShape(int part, float spin, float size) {
        return switch (part) {
            case 0 -> Displays.transform(0, 0, 0, size, size, size, spin, 0, 0);
            case 1 -> Displays.transform(0, 0, 0, size * 0.92f, size * 0.92f, size * 0.92f, spin + 45, 45, 0);
            default -> Displays.transform(0, 0, 0, size * 1.02f, size * 0.12f, size * 1.02f, spin * 0.5f, 0, 30);
        };
    }

    private static org.bukkit.block.data.BlockData partBlock(int part) {
        return switch (part) {
            case 0 -> FELT.createBlockData();
            case 1 -> FELT_LIGHT.createBlockData();
            default -> SEAM.createBlockData();
        };
    }

    private static Location handSpot(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector forward = Motion.horizontalFacing(owner.getLocation());
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        return eye.add(forward.multiply(0.5)).add(right.multiply(0.3)).add(0, -0.4, 0);
    }

    /** One tick of the ball. False once it is out of play. */
    private boolean step(Player owner, Ball b, long toss) {
        if (ball != b || b.mode == Mode.DONE) {
            return false;
        }
        long now = ctx().currentTick();
        switch (b.mode) {
            case TOSS -> {
                long age = now - b.modeSince;
                double t = Math.min(1, (double) age / toss);
                b.pos = owner.getEyeLocation().add(Motion.horizontalFacing(owner.getLocation()).multiply(0.45))
                        .add(0, 0.2 + 0.7 * Math.sin(t * Math.PI * 0.5), 0);
                b.place();
                if (age >= toss) {
                    serve(owner, b, now);
                }
            }
            case FLY -> fly(owner, b, now);
            case BACK -> back(owner, b, now);
            case FLOAT -> hover(owner, b, now);
            case DROP -> {
                b.velocity.setY(b.velocity.getY() - Motion.GRAVITY);
                Location next = b.pos.clone().add(b.velocity);
                RayTraceResult floor = b.velocity.lengthSquared() < 1.0E-8 ? null : b.pos.getWorld().rayTraceBlocks(
                        b.pos, b.velocity.clone().normalize(), b.velocity.length() + 0.05, FluidCollisionMode.NEVER,
                        true);
                if (floor != null) {
                    b.pos = floor.getHitPosition().toLocation(b.pos.getWorld()).add(0, 0.12, 0);
                    b.velocity.multiply(0.4).setY(Math.abs(b.velocity.getY()) * 0.35);
                } else {
                    b.pos = next;
                }
                b.place();
                if (now - b.modeSince >= DROP_TICKS) {
                    Vfx.cloud(ctx(), b.pos, Particle.DUST, 4, 0.1, 0, LIME_DUST, false);
                    b.mode = Mode.DONE;
                    return false;
                }
            }
            default -> {
                return false;
            }
        }
        return b.mode != Mode.DONE;
    }

    private double speedOf(int rally) {
        double base = Math.max(0.1, params().num("serve_speed")) / 20.0;
        return base * bonus(rally);
    }

    private double bonus(int rally) {
        int counted = Math.clamp(rally, 0, Math.max(0, params().integer("rally_max")));
        return 1 + Math.max(0, params().num("rally_bonus")) / 100.0 * counted;
    }

    /** The racket meets the ball: off it goes where the prodigy looks. */
    private void serve(Player owner, Ball b, long now) {
        Vector look = owner.getEyeLocation().getDirection();
        if (look.lengthSquared() < 1.0E-6) {
            look = Motion.horizontalFacing(owner.getLocation());
        }
        b.velocity = look.normalize().multiply(speedOf(b.rally));
        b.bounced = false;
        b.travelled = 0;
        b.struck.clear();
        b.setMode(Mode.FLY, now);
        Location at = b.pos;
        Vfx.arc(ctx(), owner.getLocation().add(0, 1.2, 0), 1.0, owner.getLocation().getYaw(), 140, 14,
                Particle.DUST, WHITE_DUST, false);
        Vfx.dot(at, Particle.SWEEP_ATTACK, null, false);
        Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 8, 0.15, 0, GLOW, false);
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 1.5f + b.rally * 0.06f + Fx.jitter(0.05f));
        Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.2f + b.rally * 0.1f);
    }

    private void fly(Player owner, Ball b, long now) {
        World world = b.pos.getWorld();
        double left = b.velocity.length();
        double range = Math.max(1, params().num("serve_range"));
        while (left > 1.0E-3) {
            double len = Math.min(SUB_STEP, left);
            Vector dir = b.velocity.clone().normalize();
            if (enemyReturns(owner, b, now)) {
                return;
            }
            RayTraceResult wall = world.rayTraceBlocks(b.pos, dir, len, FluidCollisionMode.NEVER, true);
            double reach = wall == null ? len : Math.max(0, wall.getHitPosition().distance(b.pos.toVector()) - 0.02);
            LivingEntity victim = firstEnemy(owner, b, dir, reach);
            if (victim != null) {
                if (returnsBall(victim, b, now)) {
                    returned(owner, b, victim, now);
                } else {
                    strike(owner, b, victim, now);
                }
                b.place();
                return;
            }
            Location next = b.pos.clone().add(dir.clone().multiply(reach));
            trail(b.pos, next, b.rally);
            b.pos = next;
            b.travelled += reach;
            left -= len;
            if (wall != null) {
                if (b.bounced || wall.getHitBlockFace() == null) {
                    b.setMode(Mode.DROP, now);
                    b.velocity = new Vector(0, -0.1, 0);
                    b.place();
                    return;
                }
                Vector normal = wall.getHitBlockFace().getDirection();
                Vector v = b.velocity;
                b.velocity = v.subtract(normal.clone().multiply(2 * v.dot(normal))).multiply(BOUNCE_DAMP);
                b.bounced = true;
                b.pos.add(normal.multiply(0.05));
                Vfx.cloud(ctx(), b.pos, Particle.DUST, 6, 0.1, 0, LIME_DUST, false);
                Fx.sound(b.pos, Sound.BLOCK_WOOL_HIT, 1.0f, 1.5f);
                Fx.sound(b.pos, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 1.4f);
                left = Math.min(left, b.velocity.length());
            }
            if (b.travelled >= range) {
                b.setMode(Mode.DROP, now);
                b.velocity = dir.multiply(0.3);
                break;
            }
        }
        b.place();
    }

    private void trail(Location from, Location to, int rally) {
        Vfx.line(ctx(), from, to, 2.5, Particle.DUST, LIME_DUST, false);
        if (rally >= 2) {
            Vfx.dot(to, Particle.END_ROD, null, false);
        }
        if (rally >= 4) {
            Vfx.dot(to, Particle.ELECTRIC_SPARK, null, false);
        }
    }

    private @Nullable LivingEntity firstEnemy(Player owner, Ball b, Vector dir, double length) {
        Location middle = b.pos.clone().add(dir.clone().multiply(length / 2));
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity enemy : ctx().enemiesNear(owner, middle, length / 2 + 2)) {
            if (b.struck.contains(enemy.getUniqueId())) {
                continue;
            }
            BoundingBox box = enemy.getBoundingBox().expand(BALL_HITBOX);
            RayTraceResult hit = box.contains(b.pos.toVector()) ? new RayTraceResult(b.pos.toVector())
                    : box.rayTrace(b.pos.toVector(), dir, Math.max(0.01, length));
            if (hit != null) {
                double distance = hit.getHitPosition().distance(b.pos.toVector());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = enemy;
                }
            }
        }
        return best;
    }

    /** An enemy player swung at the ball within its reach, facing it: the ball goes back. */
    private boolean enemyReturns(Player owner, Ball b, long now) {
        double reach = Math.max(0, params().num("return_reach"));
        for (LivingEntity enemy : ctx().enemiesNear(owner, b.pos, reach)) {
            if (!b.struck.contains(enemy.getUniqueId()) && enemy.getEyeLocation().distance(b.pos) <= reach
                    && returnsBall(enemy, b, now)) {
                returned(owner, b, enemy, now);
                b.place();
                return true;
            }
        }
        return false;
    }

    /** True if {@code enemy} (a player) swung within the return window (plus its ping) facing the ball. */
    private boolean returnsBall(LivingEntity enemy, Ball b, long now) {
        if (!(enemy instanceof Player player)) {
            return false;
        }
        long window = params().ticks("return_window") + ctx().reactionSlackTicks(player);
        long swing = ctx().lastSwingTick(player);
        if (swing < 0 || now - swing > window || swing < b.modeSince - window) {
            return false;
        }
        Vector toBall = b.pos.toVector().subtract(player.getEyeLocation().toVector());
        if (toBall.lengthSquared() < 1.0E-4) {
            return true;
        }
        return player.getEyeLocation().getDirection().dot(toBall.normalize()) >= FACING_DOT;
    }

    private void strike(Player owner, Ball b, LivingEntity victim, long now) {
        b.struck.add(victim.getUniqueId());
        Location at = Fx.body(victim);
        double damage = Math.max(0, params().num("serve_damage")) * bonus(b.rally);
        Vector push = Motion.horizontal(b.velocity);
        Combat.hit(ctx(), owner, victim, damage);
        if (Combat.isTargetable(ctx(), owner, victim) && push.lengthSquared() > 0) {
            double speed = Math.max(0, params().num("serve_push")) * bonus(b.rally) / PUSH_REACH;
            Motion.push(victim, push.multiply(speed).setY(PUSH_LIFT), MAX_SPEED);
        }
        Vfx.burst(ctx(), at, PALETTE, 0.6 + b.rally * 0.08, Particle.CRIT, false);
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.BLOCK_SLIME_BLOCK_HIT, 0.8f, 1.4f);
        score(owner, true);
        b.pos = at;
        b.setMode(Mode.BACK, now);
        b.broken = false;
    }

    private void returned(Player owner, Ball b, LivingEntity by, long now) {
        b.struck.add(by.getUniqueId());
        Location at = b.pos.clone();
        Vfx.burst(ctx(), at, PALETTE, 0.5, Particle.SWEEP_ATTACK, false);
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 1.1f);
        Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_SNARE, 0.9f, 1.0f);
        if (room(1)) {
            keep(Displays.text(ctx(), owner, at.clone().add(0, 0.5, 0), Text.mm("<b><white>받아쳤다!</white></b>"),
                    0.7f, 20));
        }
        b.rally = 0;
        b.broken = true;
        b.setMode(Mode.BACK, now);
        score(owner, false);
    }

    /** Where a ball waits for the next stroke: in front of the eyes, a little low. */
    private static Location floatSpot(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector forward = Motion.horizontalFacing(owner.getLocation());
        return eye.add(forward.multiply(FLOAT_AHEAD)).add(0, -FLOAT_BELOW, 0);
    }

    private void back(Player owner, Ball b, long now) {
        Location target = b.broken ? Fx.body(owner) : floatSpot(owner);
        Vector to = target.toVector().subtract(b.pos.toVector());
        double distance = to.length();
        double speed = b.broken ? RETURN_SPEED : BACK_SPEED;
        if (distance <= speed || !b.pos.getWorld().equals(target.getWorld())) {
            b.pos = target;
            if (b.broken) {
                Fx.sound(target, Sound.BLOCK_WOOL_HIT, 0.8f, 1.8f);
                b.setMode(Mode.DROP, now);
                b.velocity = new Vector(0, 0.15, 0);
            } else {
                b.setMode(Mode.FLOAT, now);
                b.floatUntil = now + Math.max(1, params().ticks("rally_window"));
                Fx.sound(target, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.6f + b.rally * 0.05f);
            }
            b.place();
            return;
        }
        Location next = b.pos.clone().add(to.multiply(speed / distance)).add(0, Math.min(0.15, distance * 0.03), 0);
        Vfx.line(ctx(), b.pos, next, 1.5, Particle.DUST, b.broken ? WHITE_DUST : LIME_DUST, false);
        b.pos = next;
        b.place();
    }

    /** The ball hangs in front of the prodigy: a swing at it is the next rally. */
    private void hover(Player owner, Ball b, long now) {
        Location spot = floatSpot(owner).add(0, Math.sin((now - b.modeSince) * 0.4) * 0.06, 0);
        b.pos = spot;
        b.place();
        if (now % 3 == 0) {
            Vfx.dot(spot.clone().add(Fx.jitter(0.2f), Fx.jitter(0.2f), Fx.jitter(0.2f)), Particle.DUST, LIME_DUST,
                    false);
        }
        long swing = ctx().lastSwingTick(owner);
        if (swing >= b.modeSince && owner.getEyeLocation().distance(spot) <= Math.max(0.5,
                params().num("rally_reach"))) {
            b.rally++;
            serve(owner, b, now);
            owner.sendActionBar(Text.mm("<b><" + LIME_TAG + ">랠리 ×" + b.rally + "</" + LIME_TAG + "></b> <gray>피해 ×"
                    + AbilityInfo.formatNumber(bonus(b.rally)) + "</gray>"));
            return;
        }
        if (now - b.modeSince == 1) {
            owner.sendActionBar(Text.mm("<" + LIME_TAG + ">공이 떠 있습니다</" + LIME_TAG + "> <gray>· 근접 공격으로 "
                    + "다시 치세요</gray>"));
        }
        if (now >= b.floatUntil) {
            b.setMode(Mode.DROP, now);
            b.velocity = new Vector(0, 0.05, 0);
            b.rally = 0;
        }
    }

    // ================================================================== ultimate: jump smash

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double range = Math.max(1, params().num("smash_range"));
        Location court = aimPoint(owner, range);
        double height = Math.max(0, params().num("smash_height"));
        double up = Motion.launchSpeed(height);
        long apex = Math.max(4, apexTicks(up));
        owner.setVelocity(new Vector(0, up, 0));
        NamespacedKey safe = ctx().addModifier(owner, owner, Attribute.FALL_DAMAGE_MULTIPLIER, -1,
                Operation.ADD_NUMBER, SMASH_SAFE_TICKS);
        double radius = Math.max(0.5, params().num("smash_radius"));
        Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f);
        Fx.sound(owner.getLocation(), Sound.ENTITY_BREEZE_JUMP, 1.0f, 1.0f);
        Vfx.cloud(ctx(), owner.getLocation().add(0, 0.1, 0), Particle.CLOUD, 10, 0.3, 0.03, null, false);
        owner.showTitle(Title.title(Text.mm("<b><gradient:#FAFAFA:#CDEB3A>점프 스매시!</gradient></b>"),
                Text.mm("<gray>코트 원 안으로 공을 내리꽂습니다</gray>")));
        BlockDisplay[] smashBall = new BlockDisplay[3];
        for (int i = 0; i < 3; i++) {
            if (room(1)) {
                smashBall[i] = keep(Displays.block(ctx(), owner, handSpot(owner), partBlock(i),
                        ballShape(i, 0, SMASH_BALL_SIZE), apex + SMASH_FLIGHT + 4));
                smashBall[i].setTeleportDuration(1);
                Displays.outline(smashBall[i], LIME);
            }
        }
        long total = apex + SMASH_FLIGHT;
        long[] grounded = {-1};
        Loop.repeat(ctx(), owner, 0, 1, total, i -> {
            courtMark(court, radius, i);
            if (i < apex) {
                for (BlockDisplay part : smashBall) {
                    if (part != null) {
                        Displays.moveTo(part, owner.getEyeLocation().add(0, 0.6, 0));
                    }
                }
                Vfx.dot(owner.getLocation().add(0, 0.2, 0), Particle.DUST, LIME_DUST, false);
                return true;
            }
            long k = i - apex + 1;
            if (k == 1) {
                Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.2f, 0.7f);
                Vfx.arc(ctx(), owner.getEyeLocation(), 1.2, owner.getLocation().getYaw(), 160, 16, Particle.DUST,
                        WHITE_DUST, true);
            }
            Location from = owner.getEyeLocation().add(0, 0.6, 0);
            double t = (double) k / SMASH_FLIGHT;
            Location at = from.clone().add(court.toVector().subtract(from.toVector()).multiply(t));
            Vfx.line(ctx(), from, at, 2, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            for (BlockDisplay part : smashBall) {
                if (part != null) {
                    Displays.moveTo(part, at);
                }
            }
            return true;
        }, () -> {
            for (BlockDisplay part : smashBall) {
                if (part != null && part.isValid()) {
                    part.remove();
                }
            }
            impact(owner, court, radius);
        });
        // The prodigy's own landing: the fall guard ends once it has stood on the ground for a while.
        Loop.repeat(ctx(), owner, apex, 2, SMASH_SAFE_TICKS / 2, i -> {
            if (Motion.isGrounded(owner) || owner.isInWater()) {
                if (grounded[0] < 0) {
                    grounded[0] = ctx().currentTick();
                    Vfx.cloud(ctx(), owner.getLocation().add(0, 0.1, 0), Particle.CLOUD, 6, 0.3, 0.02, null, false);
                } else if (ctx().currentTick() - grounded[0] >= SETTLE_TICKS) {
                    ctx().removeModifier(owner, safe);
                    return false;
                }
            } else {
                grounded[0] = -1;
            }
            return true;
        });
        return ActivationResult.success();
    }

    private static long apexTicks(double speed) {
        double v = speed;
        long ticks = 0;
        while (v > 0 && ticks < 200) {
            v = (v - Motion.GRAVITY) * Motion.DRAG;
            ticks++;
        }
        return ticks;
    }

    private Location aimPoint(Player owner, double range) {
        World world = owner.getWorld();
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location point;
        if (aimed != null) {
            point = aimed.getLocation();
        } else {
            Location eye = owner.getEyeLocation();
            Vector dir = eye.getDirection();
            RayTraceResult hit = world.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
            point = hit != null ? hit.getHitPosition().toLocation(world) : eye.clone().add(dir.multiply(range));
        }
        point.add(0, 0.05, 0);
        Spots.snapToGround(world, point, SMASH_FIND_GROUND);
        point.setYaw(0);
        point.setPitch(0);
        return point;
    }

    /** The court circle: a white rim, lime service lines and a centre mark. */
    private void courtMark(Location court, double radius, long i) {
        Location ring = court.clone().add(0, 0.08, 0);
        Vfx.ring(ctx(), ring, radius, 26, i * 0.05, Particle.DUST, WHITE_DUST, true);
        if (i % 2 == 0) {
            Vfx.line(ctx(), ring.clone().add(-radius, 0, 0), ring.clone().add(radius, 0, 0), 1.5, Particle.DUST,
                    LIME_DUST, true);
            Vfx.line(ctx(), ring.clone().add(0, 0, -radius), ring.clone().add(0, 0, radius), 1.5, Particle.DUST,
                    LIME_DUST, true);
        }
        Vfx.ring(ctx(), ring, 0.4, 6, -i * 0.2, Particle.DUST, COURT_DUST, true);
    }

    private void impact(Player owner, Location court, double radius) {
        World world = court.getWorld();
        Location at = court.clone().add(0, 0.3, 0);
        world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, WHITE, true);
        Vfx.burst(ctx(), at, PALETTE, radius * 0.45, Particle.END_ROD, true);
        Vfx.shockwave(ctx(), owner, court.clone().add(0, 0.12, 0), 0.4, radius + 0.4, 7, Particle.DUST, LIME_DUST,
                true);
        Block ground = court.clone().add(0, -0.1, 0).getBlock();
        if (!ground.isPassable()) {
            Vfx.cloud(ctx(), at, Particle.BLOCK_CRUMBLE, 24, radius * 0.4, 0, ground.getBlockData(), true);
        }
        Fx.sound(court, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.3f);
        Fx.sound(court, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.6f);
        Fx.sound(court, Sound.BLOCK_WOOL_HIT, 1.2f, 0.6f);
        double lift = Motion.launchSpeed(Math.max(0, params().num("smash_lift")));
        List<LivingEntity> struck = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, court, radius)) {
            Combat.hit(ctx(), owner, enemy, params().num("smash_damage"));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            struck.add(enemy);
            if (lift > 0) {
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(court.toVector()))
                        .multiply(SMASH_SPREAD);
                Motion.push(enemy, away.setY(lift), MAX_SPEED);
            }
        }
        double terrain = params().num("smash_terrain");
        if (terrain > 0) {
            ctx().breakBlocks(owner, court, terrain);
        }
        bounce(owner, at, new HashSet<>(), Math.max(0, params().integer("bounce_count")));
    }

    /** The ball skips on to the nearest enemy not bounced at yet; each can swing it back, ending the bounces. */
    private void bounce(Player owner, Location from, Set<UUID> done, int left) {
        if (left <= 0) {
            fade(from);
            return;
        }
        double range = Math.max(0.5, params().num("bounce_range"));
        LivingEntity next = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity enemy : ctx().enemiesNear(owner, from, range)) {
            if (done.contains(enemy.getUniqueId()) || ctx().isConcealedFrom(enemy, owner)) {
                continue;
            }
            double distance = Fx.body(enemy).distance(from);
            if (distance < best) {
                best = distance;
                next = enemy;
            }
        }
        if (next == null) {
            fade(from);
            return;
        }
        LivingEntity target = next;
        double arc = Math.min(3, best * 0.35);
        done.add(target.getUniqueId());
        long flight = Math.clamp(Math.round(best / BACK_SPEED), 5, 14);
        BlockDisplay[] parts = new BlockDisplay[2];
        for (int i = 0; i < 2; i++) {
            if (room(1)) {
                parts[i] = keep(Displays.block(ctx(), owner, from, partBlock(i), ballShape(i, 0, BALL_SIZE * 1.3f),
                        flight + 4));
                parts[i].setTeleportDuration(1);
            }
        }
        Fx.sound(from, Sound.BLOCK_WOOL_HIT, 1.0f, 1.2f + done.size() * 0.1f);
        Location start = from.clone();
        long since = ctx().currentTick();
        Loop.repeat(ctx(), owner, 1, 1, flight, i -> {
            double t = (double) (i + 1) / flight;
            Location end = Fx.body(target);
            Location at = start.clone().add(end.toVector().subtract(start.toVector()).multiply(t))
                    .add(0, Math.sin(t * Math.PI) * arc, 0);
            Vfx.dot(at, Particle.DUST, LIME_DUST, false);
            if (i % 2 == 0) {
                Vfx.dot(at, Particle.END_ROD, null, false);
            }
            for (int s = 0; s < parts.length; s++) {
                BlockDisplay part = parts[s];
                if (part != null) {
                    Displays.moveTo(part, at);
                    Displays.animate(part, ballShape(s, (i + 1) * 40f, BALL_SIZE * 1.3f), 1);
                }
            }
            return target.isValid() && !target.isDead();
        }, () -> {
            for (BlockDisplay part : parts) {
                if (part != null && part.isValid()) {
                    part.remove();
                }
            }
            if (!Combat.isTargetable(ctx(), owner, target)) {
                bounce(owner, start, done, left - 1);
                return;
            }
            Location hit = Fx.body(target);
            if (swungBack(target, since)) {
                Vfx.burst(ctx(), hit, PALETTE, 0.5, Particle.SWEEP_ATTACK, false);
                Fx.sound(hit, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 1.1f);
                if (room(1)) {
                    keep(Displays.text(ctx(), owner, hit.clone().add(0, 1.0, 0),
                            Text.mm("<b><white>받아쳤다!</white></b>"), 0.7f, 20));
                }
                Vfx.trail(hit, Fx.body(owner), WHITE, 10, false);
                return;
            }
            Combat.hit(ctx(), owner, target, params().num("bounce_damage"));
            Vfx.burst(ctx(), hit, PALETTE, 0.5, Particle.CRIT, false);
            Fx.sound(hit, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.3f + Fx.jitter(0.1f));
            bounce(owner, hit, done, left - 1);
        });
    }

    /** A player who swung during the flight (or within the return window before it), as the ball arrives. */
    private boolean swungBack(LivingEntity target, long since) {
        if (!(target instanceof Player player)) {
            return false;
        }
        long window = params().ticks("return_window") + ctx().reactionSlackTicks(player);
        long swing = ctx().lastSwingTick(player);
        return swing >= 0 && swing >= since - window && ctx().currentTick() - swing <= window;
    }

    private void fade(Location at) {
        Vfx.cloud(ctx(), at, Particle.DUST, 8, 0.2, 0, LIME_DUST, false);
        Vfx.cloud(ctx(), at, Particle.POOF, 3, 0.1, 0.01, null, false);
    }

    // ================================================================== display budget

    private static final int MAX_DISPLAYS = 24;

    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        Ball current = ball;
        int ballParts = current == null ? 0 : current.parts.size();
        return live.size() + ballParts + 1 + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
