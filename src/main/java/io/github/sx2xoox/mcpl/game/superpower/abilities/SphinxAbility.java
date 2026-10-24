package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.ability.Prompt;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptEndReason;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptHandle;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 스핑크스 — the guardian of Thebes who asks riddles and answers them with claws. The riddle is a place test: a tablet
 * over the enemy's head shows a picture, sandstone tiles with pictures rise around it, and only the tile with the same
 * picture is safe to stand on when time runs out. Its ultimate spreads a giant board of picture tiles and asks three
 * riddles in a row (the third is always the famous one), an "O/X quiz" whose answer squares herd the enemies together.
 * Whoever solves all three humbles the sphinx.
 *
 * <p>Look (sandstone beige, lapis blue, pharaoh gold): chiselled sandstone tiles rising out of the ground with golden
 * glyphs (☀ ☾ ★ 웃 蛇 …), a little chiselled-sandstone tablet spinning over the riddled head, sand spiralling up from the
 * right tile when it is revealed and sandstone claws closing on whoever guessed wrong.
 *
 * <p>Answers are judged by where the feet stand (with ping slack), at the end of a window that starts when the riddle
 * is on the target's screen (prompt channel); a riddle the target never saw has no consequence.
 */
public final class SphinxAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("sphinx", "스핑크스", Material.CHISELED_SANDSTONE)
            .color(TextColor.color(0xE2B33C))
            .difficulty(2)
            .passive("수수께끼의 문지기",
                    "내 수수께끼를 풀고 있는 적은 정신이 팔려 나에게 주는 피해가 {riddle_guard}% 줄어듭니다. 적이 내 "
                            + "수수께끼를 틀릴 때마다 궁극기 게이지를 {wrong_gauge}% 얻습니다.")
            .active("수수께끼",
                    "조준한 적({range}블록)의 머리 위에 그림 하나를 띄우고 주위에 그림 타일 3개를 솟게 합니다. "
                            + "{answer_seconds}초 뒤 같은 그림의 타일을 밟고 있으면 {right_damage} 피해만 받고, 틀리거나 아무것도 "
                            + "밟지 않으면 {claw_damage} 피해와 {claw_silence}초 침묵을 받습니다. 나와 아군은 정답 타일을 막아 "
                            + "방해할 수 있습니다.",
                    10)
            .ultimate("세 번째 수수께끼",
                    "발밑에 거대한 그림 타일판을 펼치고 반경 {board_radius}블록의 적에게 수수께끼 3개를 연달아 냅니다(각 "
                            + "{riddle_seconds}초). 매번 정답 칸 위에 없는 적은 {miss_damage} 피해와 {miss_seconds}초 둔화를 "
                            + "받고, 셋 다 틀리면 {all_wrong_damage} 피해와 {all_wrong_silence}초 침묵을 더 받습니다. 셋 다 "
                            + "맞힌 적마다 나는 {oedipus_damage} 피해를 입고 비틀거립니다.")
            .param("riddle_guard", 30)
            .param("wrong_gauge", 8)
            .param("range", 18)
            .param("tile_distance", 3)
            .param("answer_seconds", 2)
            .param("right_damage", 1)
            .param("claw_damage", 5)
            .param("claw_silence", 1.5)
            .param("board_radius", 12)
            .param("riddle_seconds", 1.6)
            .param("riddle_gap", 0.5)
            .param("miss_damage", 2.5)
            .param("miss_slow", 20)
            .param("miss_seconds", 4)
            .param("all_wrong_damage", 2)
            .param("all_wrong_silence", 2)
            .param("oedipus_damage", 3)
            .build();

    // ------------------------------------------------------------------ look
    private static final String SAND_TAG = "#D8C08A";
    private static final String LAPIS_TAG = "#2849A0";
    private static final String GOLD_TAG = "#E2B33C";
    /** Pale gold, pharaoh gold, lapis. */
    private static final Vfx.Palette ROYAL = Vfx.Palette.of(0xFFF1B8, 0xE2B33C, 0x2849A0);
    /** Pale sand, sandstone, burnt umber. */
    private static final Vfx.Palette SAND = Vfx.Palette.of(0xF6E7BE, 0xD8C08A, 0x6E5126);
    private static final Particle.DustOptions SAND_DUST = new Particle.DustOptions(Color.fromRGB(0xD8C08A), 1.1f);
    private static final Particle.DustOptions LAPIS_DUST = new Particle.DustOptions(Color.fromRGB(0x2849A0), 0.9f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(Color.fromRGB(0xE2B33C), 0.9f);
    private static final Particle.DustTransition GOLD_GLOW = ROYAL.glow(1.2f);
    private static final Particle.DustTransition SAND_FADE = SAND.fade(1.3f);
    private static final Color NO_BACKGROUND = Color.fromARGB(0, 0, 0, 0);
    private static final Material TILE = Material.SMOOTH_SANDSTONE;
    private static final Material TILE_ALT = Material.CUT_SANDSTONE;
    private static final Material TABLET = Material.CHISELED_SANDSTONE;
    private static final Material CLAW = Material.SANDSTONE;

    /**
     * The cap for optional displays (claws, pops). Tiles, tablets and the board title are the riddle itself: they are
     * always spawned (one riddle and one board at a time, at most 26) and count against this cap, so the optional ones
     * step aside while the board is up.
     */
    private static final int MAX_DISPLAYS = 24;
    private static final double TAU = Math.PI * 2;
    /** The riddle's tiles: width (blocks) and how far around a tile the feet still count. */
    private static final double SMALL_TILE = 1.3;
    private static final double BOARD_TILE = 2.9;
    private static final double BOARD_STEP = 3.0;
    private static final double FEET_SLACK = 0.3;
    private static final double TILE_HEIGHT = 0.14;
    /** Glyph scales: a tile's two text lines must fit on it (a line is 10/40 of a block per unit scale). */
    private static final float SMALL_GLYPH = 2.3f;
    private static final float BOARD_GLYPH = 5.2f;
    private static final int GLYPH_LINES = 2;
    /** The tablet spins this high over the head (from the seat it rides). */
    private static final float TABLET_ABOVE = 1.75f;
    private static final int TABLET_STEP = 4;
    /** Claws rise this far from the feet and lean in by this much when they close. */
    private static final double CLAW_RADIUS = 0.75;
    private static final float CLAW_CLOSE = 30f;
    private static final long PROMPT_WAIT = 20;
    private static final long CLAW_TICKS = 10;
    private static final long BOARD_RISE = 12;
    private static final long REVEAL_TICKS = 8;
    /** The largest reaction slack the match gives (ping clamped at 350 ms). */
    private static final long MAX_SLACK = 8;

    // ------------------------------------------------------------------ state
    private final List<Display> live = new ArrayList<>();
    /** Enemies busy with one of my riddles (how many), for the passive. */
    private final Map<UUID, Integer> riddled = new HashMap<>();
    private final Map<UUID, Long> lastFlinch = new HashMap<>();
    private @Nullable Board board;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        riddled.clear();
        lastFlinch.clear();
        board = null;
        live.clear();
    }

    // ================================================================== pictures

    /** The pictures of tiles and tablets; the first five are the active's, all nine make up the board. */
    private enum Picture {
        SUN("해", "☀", "#F2C230", "낮에만 떴다가 밤이면 숨는 것은?"),
        MOON("달", "☾", "#BFD4FF", "밤마다 모양을 바꾸는 것은?"),
        STAR("별", "★", "#FFF2A8", "밤하늘에 셀 수 없이 반짝이는 것은?"),
        HUMAN("사람", "웃", "#C9783A", "아침엔 네 발, 점심엔 두 발, 저녁엔 세 발인 것은?"),
        SNAKE("뱀", "蛇", "#5FA05A", "다리 없이 기어 다니는 것은?"),
        WATER("물", "水", "#4F8FE8", "가두면 고이고 풀어 주면 흐르는 것은?"),
        FIRE("불", "火", "#FF6A2A", "먹을수록 커지고 물을 마시면 죽는 것은?"),
        MOUNTAIN("산", "山", "#8A6A3A", "움직이지 않으면서 구름을 머리에 이는 것은?"),
        BIRD("새", "鳥", "#E8E8F0", "알에서 나와 하늘을 나는 것은?");

        final String word;
        final String glyph;
        final String color;
        final String riddle;

        Picture(String word, String glyph, String color, String riddle) {
            this.word = word;
            this.glyph = glyph;
            this.color = color;
            this.riddle = riddle;
        }

        String tag() {
            return "<b><" + color + ">" + glyph + "</" + color + "></b>";
        }

        static List<Picture> small() {
            return List.of(SUN, MOON, STAR, HUMAN, SNAKE);
        }
    }

    // ================================================================== passive: the gatekeeper

    private void riddle(LivingEntity target, int delta) {
        riddled.merge(target.getUniqueId(), delta, Integer::sum);
        if (riddled.getOrDefault(target.getUniqueId(), 0) <= 0) {
            riddled.remove(target.getUniqueId());
        }
    }

    private boolean isRiddled(@Nullable LivingEntity entity) {
        return entity != null && riddled.getOrDefault(entity.getUniqueId(), 0) > 0;
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Player attacker = ctx().resolveAttacker(byEntity.getDamager());
        if (attacker == null || !isRiddled(attacker)) {
            return;
        }
        double guard = Math.clamp(params().num("riddle_guard") / 100.0, 0.0, 1.0);
        event.setDamage(event.getDamage() * (1 - guard));
        long now = ctx().currentTick();
        if (now - lastFlinch.getOrDefault(attacker.getUniqueId(), Long.MIN_VALUE / 2) >= 20) {
            lastFlinch.put(attacker.getUniqueId(), now);
            Location at = Fx.body(player());
            Vfx.cloud(ctx(), at, Particle.FALLING_DUST, 8, 0.4, 0, sandData(), false);
            Vfx.dot(at.clone().add(0, 0.8, 0), Particle.DUST, GOLD_DUST, false);
            if (!ctx().isConcealedFrom(attacker, player())) {
                popOver(player(), attacker, "<b><" + LAPIS_TAG + ">?</" + LAPIS_TAG + "></b> <gray>정신이 팔렸다</gray>",
                        0.9f);
            }
            Fx.sound(at, Sound.BLOCK_SAND_BREAK, 0.6f, 1.4f);
        }
    }

    private void rewardWrongAnswer() {
        double gauge = Math.max(0, params().num("wrong_gauge"));
        if (gauge > 0) {
            ctx().addEarnedGauge(player(), gauge);
        }
    }

    // ================================================================== active: the riddle

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, Math.max(1, params().num("range")));
        if (target == null) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        if (tileSpots(target, 3).isEmpty()) {
            return ActivationResult.fail("그 적 주위에 타일을 놓을 자리가 없습니다");
        }
        new Riddle(owner, target).start();
        return ActivationResult.success();
    }

    /** Up to {@code count} standable spots for small tiles around {@code target}, spread around it. */
    private List<Location> tileSpots(LivingEntity target, int count) {
        double reach = Math.max(1.6, params().num("tile_distance"));
        double[] distances = {reach * 0.85, reach * 0.65, reach};
        Location base = target.getLocation();
        World world = base.getWorld();
        double start = ThreadLocalRandom.current().nextDouble(TAU);
        List<Location> spots = new ArrayList<>();
        for (int k = 0; k < 12 && spots.size() < count; k++) {
            double angle = start + k * TAU / 3 + (k / 3) * 0.5;
            for (double distance : distances) {
                Location spot = base.clone().add(Math.cos(angle) * distance, 1.2, Math.sin(angle) * distance);
                Spots.snapToGround(world, spot, 3.5);
                if (!Spots.isFree(world, spot, 0.6, 1.8) || !Spots.hasGroundBelow(world, spot)
                        || !ctx().isInsideArena(spot) || Math.abs(spot.getY() - base.getY()) > 1.6
                        || !Spots.clearPath(target.getEyeLocation(), spot.clone().add(0, 0.6, 0))) {
                    continue;
                }
                boolean apart = true;
                for (Location other : spots) {
                    if (other.distanceSquared(spot) < (SMALL_TILE + 0.4) * (SMALL_TILE + 0.4)) {
                        apart = false;
                        break;
                    }
                }
                if (apart) {
                    spots.add(spot);
                    break;
                }
            }
        }
        return spots;
    }

    /** One riddle on one enemy: the tablet, the tiles, the verdict. */
    private final class Riddle implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final @Nullable Player enemy;
        private final Picture answer;
        private final List<Picture> pictures = new ArrayList<>();
        private final List<Tile> tiles = new ArrayList<>();
        private final long window;
        private @Nullable BlockDisplay tablet;
        private @Nullable PromptHandle handle;
        private long lastOnAnswer = Long.MIN_VALUE / 2;
        private long mobEnd = Long.MAX_VALUE;
        private boolean raised;
        private boolean over;

        Riddle(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.enemy = target instanceof Player p ? p : null;
            List<Picture> pool = new ArrayList<>(Picture.small());
            Collections.shuffle(pool);
            this.answer = pool.getFirst();
            pictures.add(pool.get(0));
            pictures.add(pool.get(1));
            pictures.add(pool.get(2));
            Collections.shuffle(pictures);
            this.window = Math.max(1, params().ticks("answer_seconds"));
        }

        void start() {
            castVisuals();
            riddle(target, 1);
            if (enemy == null) {
                raise();
                mobEnd = ctx().currentTick() + window;
            } else {
                Prompt prompt = Prompt.builder(Text.mm("<b><" + GOLD_TAG + ">수수께끼!</" + GOLD_TAG + "></b> <white>석판과 "
                                + "같은 그림의 타일을 밟아라:</white> " + answer.tag() + " <gray>" + answer.word + "</gray>"),
                                window)
                        .priority(Prompt.NORMAL)
                        .overhead(Text.mm("<" + SAND_TAG + ">[</" + SAND_TAG + "> " + answer.tag() + " <" + SAND_TAG
                                + ">]</" + SAND_TAG + "> <" + LAPIS_TAG + "><b>?</b></" + LAPIS_TAG + ">"))
                        .maxWait(PROMPT_WAIT)
                        .maxPause(PROMPT_WAIT)
                        .onShown(shown -> raise())
                        .onEnd(this::ended)
                        .build();
                handle = ctx().prompt(owner, enemy, prompt);
            }
            if (!over) {
                Loop.repeat(ctx(), owner, 1, 1, PROMPT_WAIT * 2 + window + 40, this, () -> finish(false));
            }
        }

        private void castVisuals() {
            Location hand = owner.getEyeLocation().add(owner.getLocation().getDirection().multiply(0.6)).add(0, -0.3,
                    0);
            Location head = target.getLocation().add(0, target.getHeight() + 0.6, 0);
            Vfx.ring(ctx(), hand, 0.35, 10, 0, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
            for (int i = 0; i < Vfx.n(ctx(), 5); i++) {
                Vfx.trail(hand, head.clone().add(Fx.jitter(0.5f), Fx.jitter(0.3f), Fx.jitter(0.5f)),
                        Color.fromRGB(0xD8C08A), 10, false);
            }
            Vfx.trail(hand, head, Color.fromRGB(0x2849A0), 9, true);
            Fx.sound(owner.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.9f, 0.8f);
            Fx.sound(owner.getLocation(), Sound.BLOCK_SUSPICIOUS_SAND_BREAK, 1.0f, 0.7f);
            Fx.sound(head, Sound.BLOCK_BELL_RESONATE, 0.5f, 1.5f);
        }

        /** The tiles rise around the target and the tablet appears over its head (the answer timer runs now). */
        private void raise() {
            if (raised || over) {
                return;
            }
            raised = true;
            List<Location> spots = tileSpots(target, 3);
            if (spots.isEmpty()) {
                spots = List.of(target.getLocation());
            }
            // The answer must always be on the field: it goes on the first spot found.
            List<Picture> order = new ArrayList<>(pictures);
            order.remove(answer);
            order.addFirst(answer);
            for (int i = 0; i < spots.size(); i++) {
                tiles.add(new Tile(owner, spots.get(i), order.get(i), SMALL_TILE, SMALL_GLYPH,
                        i % 2 == 0 ? TILE : TILE_ALT, i));
            }
            // The tablet rides the head (no lag behind a target running to a tile) and spins in TABLET_STEP steps.
            BlockDisplay cube = keep(Displays.block(ctx(), owner, seat(target), TABLET.createBlockData(),
                    Displays.transform(0, TABLET_ABOVE, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), 0,
                    viewer -> !ctx().isConcealedFrom(target, viewer)));
            ride(target, cube);
            Displays.animateNextTick(ctx(), owner, cube, Displays.transform(0, TABLET_ABOVE, 0, 0.45f, 0.45f, 0.12f, 0,
                    0, 0), TABLET_STEP);
            tablet = cube;
            Location feet = target.getLocation().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, feet, 0.4, Math.max(1.6, params().num("tile_distance")) + 0.6, 6,
                    Particle.DUST_COLOR_TRANSITION, SAND_FADE, false);
            Fx.sound(feet, Sound.BLOCK_SAND_BREAK, 1.0f, 0.6f);
            Fx.sound(feet, Sound.BLOCK_VAULT_OPEN_SHUTTER, 0.7f, 0.8f);
        }

        private Location tabletSpot() {
            return seat(target).add(0, TABLET_ABOVE, 0);
        }

        @Override
        public boolean test(long run) {
            if (over) {
                return false;
            }
            if (!target.isValid() || target.isDead() || (enemy != null && !ctx().isAlive(enemy))) {
                finish(false);
                return false;
            }
            long now = ctx().currentTick();
            if (!raised) {
                return true;
            }
            if (tileOf(target) == answer) {
                lastOnAnswer = now;
            }
            BlockDisplay cube = tablet;
            if (cube != null && cube.isValid()) {
                ride(target, cube);
                if (run % TABLET_STEP == 0) {
                    Displays.animate(cube, Displays.transform(0, TABLET_ABOVE, 0, 0.45f, 0.45f, 0.12f, run * 9f, 0, 0),
                            TABLET_STEP);
                }
            }
            if (run % 3 == 0) {
                for (Tile tile : tiles) {
                    tile.shimmer(run);
                }
            }
            if (enemy == null && now >= mobEnd) {
                verdict();
                return false;
            }
            return true;
        }

        private @Nullable Picture tileOf(LivingEntity body) {
            for (Tile tile : tiles) {
                if (tile.holds(body)) {
                    return tile.picture;
                }
            }
            return null;
        }

        private void ended(PromptHandle shown, PromptEndReason reason) {
            if (over) {
                return;
            }
            if (reason == PromptEndReason.EXPIRED && target.isValid() && !target.isDead()) {
                verdict();
            } else {
                if (reason == PromptEndReason.UNSEEN) {
                    // Never shown: half the cooldown comes back.
                    ctx().reduceCooldown(owner, ctx().cooldownRemaining(owner) * 0.5);
                }
                finish(false);
            }
        }

        /** Time is up: right where the feet stand (or stood a ping ago), or the claws. */
        private void verdict() {
            long now = ctx().currentTick();
            long slack = enemy == null ? 1 : ctx().reactionSlackTicks(enemy);
            Picture standing = tileOf(target);
            boolean right = standing == answer || (standing == null && now - lastOnAnswer <= slack);
            finish(right);
            Location head = target.getLocation().add(0, target.getHeight() + 0.5, 0);
            if (right) {
                Vfx.cloud(ctx(), tabletSpot(), Particle.BLOCK, 18, 0.25, 0.1, TABLET.createBlockData(), false);
                Vfx.cloud(ctx(), head, Particle.END_ROD, 6, 0.3, 0.04, null, false);
                Fx.sound(head, Sound.BLOCK_DECORATED_POT_SHATTER, 0.9f, 1.2f);
                Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.6f);
                popOver(owner, target, "<b><" + GOLD_TAG + ">정답!</" + GOLD_TAG + "></b>", 1.2f);
                Combat.hit(ctx(), owner, target, Math.max(0, params().num("right_damage")));
            } else {
                rewardWrongAnswer();
                claws(owner, target);
                popOver(owner, target, "<b><#C0392B>오답!</#C0392B></b> <gray>정답은</gray> " + answer.tag(), 1.2f);
                ctx().runLater(owner, CLAW_TICKS / 2, () -> {
                    if (Combat.isTargetable(ctx(), owner, target)) {
                        Combat.hit(ctx(), owner, target, Math.max(0, params().num("claw_damage")));
                        if (target instanceof Player victim) {
                            long silence = params().ticks("claw_silence");
                            if (silence > 0) {
                                ctx().silence(victim, silence);
                            }
                        }
                    }
                });
            }
        }

        /** The riddle is over: tiles sink (or crumble), the tablet goes. */
        private void finish(boolean solved) {
            if (over) {
                return;
            }
            over = true;
            riddle(target, -1);
            for (Tile tile : tiles) {
                tile.sink(tile.picture == answer && solved);
            }
            BlockDisplay cube = tablet;
            if (cube != null && cube.isValid()) {
                if (solved) {
                    cube.remove();
                } else {
                    Displays.shrinkAway(ctx(), owner, cube, 4);
                }
            }
            PromptHandle prompt = handle;
            if (prompt != null) {
                prompt.cancel();
            }
        }
    }

    /** Sandstone claws burst out of the ground around {@code target} and close in on it. */
    private void claws(Player owner, LivingEntity target) {
        Location feet = target.getLocation();
        for (int i = 0; i < 3 && reserve(1); i++) {
            double angle = i * TAU / 3;
            // Faces the feet (like yawToward(at, feet)): its tip leans in toward the victim when it closes.
            float yaw = (float) Math.toDegrees(Math.atan2(Math.cos(angle), -Math.sin(angle)));
            Location at = feet.clone().add(Math.cos(angle) * CLAW_RADIUS, 0, Math.sin(angle) * CLAW_RADIUS);
            BlockDisplay claw = keep(Displays.block(ctx(), owner, at, CLAW.createBlockData(),
                    rooted(-1.35f, 0.18f, 1.3f, 0.18f, yaw, 0), CLAW_TICKS + 8));
            Displays.animateNextTick(ctx(), owner, claw, rooted(-0.05f, 0.18f, 1.3f, 0.18f, yaw, 0), 3);
            ctx().runLater(owner, 4, () -> Displays.animate(claw, rooted(-0.05f, 0.18f, 1.3f, 0.18f, yaw, CLAW_CLOSE),
                    3));
            ctx().runLater(owner, CLAW_TICKS, () -> {
                if (claw.isValid()) {
                    Displays.shrinkAway(ctx(), owner, claw, 6);
                }
            });
        }
        Vfx.cloud(ctx(), feet.clone().add(0, 0.3, 0), Particle.BLOCK, 24, 0.5, 0.15, sandData(), false);
        Vfx.cloud(ctx(), feet.clone().add(0, 0.6, 0), Particle.FALLING_DUST, 14, 0.5, 0, sandData(), false);
        Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 0.9, 14, 0, Particle.DUST_COLOR_TRANSITION, SAND_FADE, false);
        Fx.sound(feet, Sound.BLOCK_SUSPICIOUS_SAND_BREAK, 1.2f, 0.5f);
        Fx.sound(feet, Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.9f, 0.7f);
        Fx.sound(feet, Sound.ENTITY_RAVAGER_ATTACK, 0.6f, 1.3f);
    }

    private static BlockData sandData() {
        return Material.SAND.createBlockData();
    }

    /**
     * A block display standing on its base: the base's middle at {@code baseY} over the entity, pitched (toward the
     * yaw's facing) around that base, so it bends over instead of seesawing around its middle.
     */
    private static Transformation rooted(float baseY, float sx, float sy, float sz, float yaw, float pitch) {
        Quaternionf rotation = new Quaternionf()
                .rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch));
        Vector3f foot = rotation.transform(new Vector3f(sx * 0.5f, 0, sz * 0.5f));
        return new Transformation(new Vector3f(-foot.x, baseY - foot.y, -foot.z), rotation, new Vector3f(sx, sy, sz),
                new Quaternionf());
    }

    /**
     * Flat text (pitch -90) centered on the entity. Text displays are bottom-anchored: laid flat, their lines run from
     * the origin toward -Z by {@code (lines * 10 + 1) / 40} blocks per unit scale, so they move half of that toward +Z.
     */
    private static Transformation flatText(float scale, int lines) {
        float half = (float) ((lines * 10 + 1) / 80.0 * scale);
        return Displays.centered(0, 0, half, scale, scale, scale, 0, -90, 0);
    }

    /** Where a display riding {@code body} sits before its transformation: the top of the body's box. */
    private static Location seat(LivingEntity body) {
        return body.getLocation().add(0, body.getHeight(), 0);
    }

    /**
     * Seats {@code part} on {@code body} as a passenger (again, after anything dismounted it), so every client moves it
     * with the body's own model. A mounted part is never teleported (that would dismount it); its transformation
     * holds its offset from the seat.
     */
    private static void ride(LivingEntity body, Display part) {
        if (!part.isValid() || !body.isValid() || body.isDead() || body.equals(part.getVehicle())) {
            return;
        }
        if (part.isInsideVehicle()) {
            part.leaveVehicle();
        }
        Displays.moveTo(part, seat(body));
        body.addPassenger(part);
    }

    // ================================================================== tiles

    /** A picture tile lying on the ground: a sandstone plate with its glyph and word on top. */
    private final class Tile {

        final Picture picture;
        final Location center;
        final double size;
        private final Player owner;
        private @Nullable BlockDisplay plate;
        private @Nullable TextDisplay glyph;

        Tile(Player owner, Location ground, Picture picture, double size, float glyphScale, Material stone,
             int order) {
            this.owner = owner;
            this.picture = picture;
            this.center = ground.clone();
            this.size = size;
            float s = (float) size;
            // Plate and glyph are the riddle itself: always spawned (the answer is never an invisible square).
            Location at = center.clone().add(0, TILE_HEIGHT / 2, 0);
            BlockDisplay block = keep(Displays.block(ctx(), owner, at, stone.createBlockData(),
                    Displays.transform(0, -0.4f, 0, s, (float) TILE_HEIGHT, s, 0, 0, 0), 0));
            ctx().runLater(owner, 1 + order, () -> Displays.animate(block, Displays.transform(0, 0, 0, s,
                    (float) TILE_HEIGHT, s, 0, 0, 0), 5));
            plate = block;
            Location top = center.clone().add(0, TILE_HEIGHT + 0.02, 0);
            TextDisplay text = keep(Displays.spawn(ctx(), owner, top, TextDisplay.class, 0, d -> {
                d.text(Text.mm(glyphText(false)));
                d.setBillboard(Display.Billboard.FIXED);
                d.setBackgroundColor(NO_BACKGROUND);
                d.setShadowed(false);
                d.setTransformation(flatText(0.05f, GLYPH_LINES));
            }));
            ctx().runLater(owner, 3 + order, () -> Displays.animate(text, flatText(glyphScale, GLYPH_LINES), 4));
            glyph = text;
            Vfx.cloud(ctx(), center.clone().add(0, 0.3, 0), Particle.BLOCK, 10, size * 0.4, 0.08, sandData(), false);
        }

        private String glyphText(boolean lit) {
            String color = lit ? GOLD_TAG : picture.color;
            return "<b><" + color + ">" + picture.glyph + "</" + color + "></b>\n<" + (lit ? GOLD_TAG : "#3A2A12") + ">"
                    + picture.word + "</" + (lit ? GOLD_TAG : "#3A2A12") + ">";
        }

        /** True if {@code body}'s feet stand on this tile. */
        boolean holds(LivingEntity body) {
            Location feet = body.getLocation();
            if (!feet.getWorld().equals(center.getWorld())) {
                return false;
            }
            double half = size / 2 + FEET_SLACK;
            return Math.abs(feet.getX() - center.getX()) <= half && Math.abs(feet.getZ() - center.getZ()) <= half
                    && feet.getY() >= center.getY() - 0.6 && feet.getY() <= center.getY() + 1.5;
        }

        void shimmer(long run) {
            double half = size / 2;
            double t = (run * 0.07) % 1.0;
            double edge = -half + t * size;
            Location base = center.clone().add(0, TILE_HEIGHT + 0.05, 0);
            Vfx.dot(base.clone().add(edge, 0, -half), Particle.DUST, LAPIS_DUST, false);
            Vfx.dot(base.clone().add(-edge, 0, half), Particle.DUST, LAPIS_DUST, false);
            if (size > 2) {
                Vfx.dot(base.clone().add(half, 0, edge), Particle.DUST, SAND_DUST, false);
                Vfx.dot(base.clone().add(-half, 0, -edge), Particle.DUST, SAND_DUST, false);
            }
        }

        /** Shows the right answer: gold glyph, outline, sand spiralling up. */
        void reveal() {
            TextDisplay text = glyph;
            if (text != null && text.isValid()) {
                text.text(Text.mm(glyphText(true)));
            }
            BlockDisplay block = plate;
            if (block != null && block.isValid()) {
                Displays.outline(block, Color.fromRGB(0xE2B33C));
            }
            Vfx.helix(ctx(), center.clone().add(0, 0.2, 0), size * 0.4, 2.4, 1.5, 2, 14, 0,
                    Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, true);
            Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f, 1.4f);
        }

        void dim() {
            TextDisplay text = glyph;
            if (text != null && text.isValid()) {
                text.text(Text.mm(glyphText(false)));
            }
            BlockDisplay block = plate;
            if (block != null && block.isValid()) {
                block.setGlowing(false);
            }
        }

        /** The tile goes back into the sand (the solved one with a golden flash). */
        void sink(boolean golden) {
            if (golden) {
                Vfx.cloud(ctx(), center.clone().add(0, 0.4, 0), Particle.END_ROD, 6, size * 0.3, 0.03, null, false);
            }
            Vfx.cloud(ctx(), center.clone().add(0, 0.25, 0), Particle.BLOCK, 8, size * 0.35, 0.05, sandData(), false);
            float s = (float) size;
            BlockDisplay block = plate;
            if (block != null && block.isValid()) {
                Displays.animate(block, Displays.transform(0, -0.4f, 0, s, (float) TILE_HEIGHT, s, 0, 0, 0), 6);
                ctx().runLater(owner, 6, () -> {
                    if (block.isValid()) {
                        block.remove();
                    }
                });
            }
            TextDisplay text = glyph;
            if (text != null && text.isValid()) {
                // Shrinks into the tile's middle (its centering offset shrinks with it).
                Displays.animate(text, flatText(0.001f, GLYPH_LINES), 4);
                ctx().runLater(owner, 4, () -> {
                    if (text.isValid()) {
                        text.remove();
                    }
                });
            }
        }
    }

    // ================================================================== ultimate: the third riddle

    @Override
    public ActivationResult onUltimate() {
        if (board != null) {
            return ActivationResult.fail("이미 수수께끼판이 펼쳐져 있습니다");
        }
        Player owner = player();
        Board spread = new Board(owner);
        board = spread;
        spread.open();
        return ActivationResult.success();
    }

    /** How one enemy fared on the board. */
    private static final class Score {
        final LivingEntity body;
        int asked;
        int right;
        int wrong;
        @Nullable PromptHandle handle;
        long lastOnAnswer = Long.MIN_VALUE / 2;

        Score(LivingEntity body) {
            this.body = body;
        }
    }

    /** One riddle on the board: the prompts still open, and whether its answer is out. */
    private static final class Round {
        final int index;
        final Picture answer;
        final List<PromptHandle> handles = new ArrayList<>();
        int open;
        boolean timeUp;
        boolean revealed;

        Round(int index, Picture answer) {
            this.index = index;
            this.answer = answer;
        }
    }

    /** The giant board: nine picture tiles, three riddles, the verdicts. */
    private final class Board {

        private final Player owner;
        private final Location center;
        private final double radius;
        private final long riddleTicks;
        private final long gapTicks;
        private final List<Tile> tiles = new ArrayList<>();
        private final List<Picture> riddles = new ArrayList<>();
        private final Map<UUID, Score> scores = new LinkedHashMap<>();
        private @Nullable TextDisplay title;
        private int round = -1;
        private boolean over;

        Board(Player owner) {
            this.owner = owner;
            Location feet = owner.getLocation();
            this.center = new Location(feet.getWorld(), feet.getX(), feet.getY(), feet.getZ());
            Spots.snapToGround(center.getWorld(), center, 6);
            this.radius = Math.max(4, params().num("board_radius"));
            this.riddleTicks = Math.max(1, params().ticks("riddle_seconds"));
            this.gapTicks = Math.max(1, params().ticks("riddle_gap"));
            List<Picture> others = new ArrayList<>(List.of(Picture.values()));
            others.remove(Picture.HUMAN);
            Collections.shuffle(others);
            riddles.add(others.get(0));
            riddles.add(others.get(1));
            riddles.add(Picture.HUMAN);
        }

        void open() {
            List<Picture> layout = new ArrayList<>(List.of(Picture.values()));
            Collections.shuffle(layout);
            int index = 0;
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    Location spot = center.clone().add(i * BOARD_STEP, 1.5, j * BOARD_STEP);
                    Spots.snapToGround(spot.getWorld(), spot, 4);
                    if (spot.getY() > center.getY() + 1.4) {
                        spot.setY(center.getY());
                    }
                    int ring = Math.abs(i) + Math.abs(j);
                    Material stone = (i + j) % 2 == 0 ? TILE : TILE_ALT;
                    tiles.add(new Tile(owner, spot, layout.get(index++), BOARD_TILE, BOARD_GLYPH, stone, ring * 2));
                }
            }
            TextDisplay text = keep(Displays.spawn(ctx(), owner, center.clone().add(0, 5.0, 0), TextDisplay.class, 0,
                    d -> {
                        d.text(Text.mm("<b><" + GOLD_TAG + ">세 번째 수수께끼</" + GOLD_TAG + "></b>\n<" + SAND_TAG
                                + ">스핑크스가 묻는다</" + SAND_TAG + ">"));
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setBackgroundColor(Color.fromARGB(150, 0x17, 0x14, 0x1F));
                        d.setLineWidth(220);
                        d.setShadowed(true);
                        d.setTransformation(Displays.centered(0, 0, 0, 0.2f, 0.2f, 0.2f, 0, 0, 0));
                    }));
            Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0, 0, 1.8f, 1.8f, 1.8f, 0, 0, 0), 6);
            title = text;
            Location chest = owner.getLocation().add(0, 1.2, 0);
            Fx.sound(chest, Sound.ENTITY_RAVAGER_ROAR, 1.4f, 0.8f);
            Fx.sound(chest, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
            Fx.sound(chest, Sound.BLOCK_SUSPICIOUS_SAND_BREAK, 1.2f, 0.5f);
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.2, 0), 0.6, BOARD_STEP * 1.6 + 1, 8,
                    Particle.DUST_COLOR_TRANSITION, SAND_FADE, true);
            Vfx.burst(ctx(), chest, ROYAL, 1.3, Particle.END_ROD, true);
            Vfx.cloud(ctx(), center.clone().add(0, 0.5, 0), Particle.BLOCK, 40, 3.5, 0.15, sandData(), true);
            ctx().announce(Text.mm("<b><" + GOLD_TAG + ">스핑크스</" + GOLD_TAG + "></b> <white>세 번째 수수께끼가 "
                    + "시작됩니다 — 정답 그림 칸 위에 서라!</white>"));
            ctx().runLater(owner, BOARD_RISE, () -> ask(0));
            // Runs until the board closes: a round lasts until its last prompt ended (at most its fallback).
            long roundTicks = 3 * riddleTicks + MAX_SLACK + 4 + REVEAL_TICKS + gapTicks;
            Loop.repeat(ctx(), owner, 1, 2, (BOARD_RISE + 3 * roundTicks + 40) / 2,
                    run -> {
                        if (over) {
                            return false;
                        }
                        for (int t = 0; t < tiles.size(); t++) {
                            if (run % 2 == t % 2) {
                                tiles.get(t).shimmer(run);
                            }
                        }
                        Picture asked = round >= 0 && round < riddles.size() ? riddles.get(round) : null;
                        for (Score score : scores.values()) {
                            if (asked != null && onTile(score.body) == asked) {
                                score.lastOnAnswer = ctx().currentTick();
                            }
                        }
                        if (run % 3 == 0) {
                            Vfx.cloud(ctx(), center.clone().add(0, 0.4, 0), Particle.FALLING_DUST, 5,
                                    BOARD_STEP * 1.5, 0, sandData(), false);
                        }
                        return true;
                    });
        }

        private @Nullable Picture onTile(LivingEntity body) {
            for (Tile tile : tiles) {
                if (tile.holds(body)) {
                    return tile.picture;
                }
            }
            return null;
        }

        private String ordinal(int index) {
            return switch (index) {
                case 0 -> "첫 번째";
                case 1 -> "두 번째";
                default -> "세 번째";
            };
        }

        /** Riddle {@code index}: the question goes up on the board and on every enemy's screen. */
        private void ask(int index) {
            if (over) {
                return;
            }
            round = index;
            Picture answer = riddles.get(index);
            for (Tile tile : tiles) {
                tile.dim();
            }
            TextDisplay text = title;
            if (text != null && text.isValid()) {
                text.text(Text.mm("<b><" + GOLD_TAG + ">" + ordinal(index) + " 수수께끼</" + GOLD_TAG + "></b>\n<white>"
                        + answer.riddle + "</white>"));
            }
            Fx.sound(center, Sound.BLOCK_BELL_USE, 1.0f, 0.7f + index * 0.15f);
            Fx.sound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.7f, 0.6f);
            Round asked = new Round(index, answer);
            List<LivingEntity> mobs = new ArrayList<>();
            long longest = riddleTicks;
            for (LivingEntity entity : ctx().enemiesNear(owner, center, radius)) {
                if (entity instanceof Player enemy && ctx().isConcealedFrom(enemy, owner)) {
                    continue;
                }
                Score score = scores.computeIfAbsent(entity.getUniqueId(), id -> new Score(entity));
                score.lastOnAnswer = Long.MIN_VALUE / 2;
                riddle(entity, 1);
                if (entity instanceof Player enemy) {
                    boolean[] closed = {false};
                    asked.open++;
                    Prompt prompt = Prompt.builder(Text.mm("<b><" + GOLD_TAG + ">" + (index + 1) + "/3</" + GOLD_TAG
                                    + "></b> <white>" + answer.riddle + "</white> <gray>— 정답 그림 칸 위에 서라!</gray>"),
                                    riddleTicks)
                            .priority(Prompt.HIGH)
                            .overhead(Text.mm("<b><" + GOLD_TAG + ">" + ordinal(index) + " 수수께끼</" + GOLD_TAG
                                    + "></b> <" + LAPIS_TAG + "><b>?</b></" + LAPIS_TAG + ">"))
                            .maxWait(riddleTicks / 2)
                            .maxPause(riddleTicks / 2)
                            .onEnd((handle, reason) -> {
                                answered(score, answer, index, reason);
                                if (!closed[0]) {
                                    closed[0] = true;
                                    asked.open--;
                                    settle(asked);
                                }
                            })
                            .build();
                    PromptHandle handle = ctx().prompt(owner, enemy, prompt);
                    score.handle = handle;
                    asked.handles.add(handle);
                    if (!handle.isActive() && !closed[0]) {
                        closed[0] = true; // refused at once
                        asked.open--;
                    }
                    longest = Math.max(longest, riddleTicks + ctx().reactionSlackTicks(enemy));
                } else {
                    mobs.add(entity);
                }
            }
            ctx().runLater(owner, riddleTicks, () -> {
                for (LivingEntity mob : mobs) {
                    Score score = scores.get(mob.getUniqueId());
                    if (score != null) {
                        judge(score, answer, true);
                    }
                }
                asked.timeUp = true;
                settle(asked);
            });
            // A prompt waits at most riddleTicks / 2 for the screen and may be pushed back about as long again: one
            // still open after that is dropped (no verdict), so the answer comes out at last.
            ctx().runLater(owner, longest + riddleTicks + 2, () -> {
                if (asked.revealed || over) {
                    return;
                }
                for (PromptHandle handle : asked.handles) {
                    if (handle.isActive()) {
                        handle.cancel();
                    }
                }
                asked.timeUp = true;
                asked.open = 0;
                settle(asked);
            });
        }

        /**
         * The answer comes out (gold square, title) only once every asked enemy's prompt has ended and the riddle's
         * time is up: an enemy whose question came late never sees the solution before its own verdict. The next riddle
         * (or the board's end) follows the reveal.
         */
        private void settle(Round asked) {
            if (over || asked.revealed || !asked.timeUp || asked.open > 0) {
                return;
            }
            asked.revealed = true;
            reveal(asked.answer);
            int next = asked.index + 1;
            if (next < riddles.size()) {
                ctx().runLater(owner, REVEAL_TICKS + gapTicks, () -> ask(next));
            } else {
                ctx().runLater(owner, REVEAL_TICKS + gapTicks, this::close);
            }
        }

        private void answered(Score score, Picture answer, int index, PromptEndReason reason) {
            if (score.handle != null && !score.handle.isActive()) {
                score.handle = null;
            }
            riddle(score.body, -1);
            if (over || reason != PromptEndReason.EXPIRED || !score.body.isValid() || score.body.isDead()) {
                return;
            }
            judge(score, answer, false);
            if (index == riddles.size() - 1) {
                verdict(score);
            }
        }

        /** Right on the answer square (or a ping ago), or the sand bites. */
        private void judge(Score score, Picture answer, boolean mob) {
            LivingEntity body = score.body;
            if (mob) {
                riddle(body, -1);
            }
            if (!Combat.isTargetable(ctx(), owner, body)) {
                return;
            }
            score.asked++;
            long slack = body instanceof Player p ? ctx().reactionSlackTicks(p) : 1;
            Picture standing = onTile(body);
            boolean right = standing == answer
                    || (standing == null && ctx().currentTick() - score.lastOnAnswer <= slack);
            if (right) {
                score.right++;
                Vfx.cloud(ctx(), body.getLocation().add(0, 1.0, 0), Particle.END_ROD, 4, 0.3, 0.03, null, false);
                Fx.sound(body.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.8f);
                return;
            }
            score.wrong++;
            rewardWrongAnswer();
            Location feet = body.getLocation();
            Vfx.cloud(ctx(), feet.clone().add(0, 0.4, 0), Particle.BLOCK, 16, 0.5, 0.12, sandData(), false);
            Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 0.8, 12, 0, Particle.DUST_COLOR_TRANSITION, SAND_FADE,
                    false);
            Fx.sound(feet, Sound.BLOCK_SUSPICIOUS_SAND_BREAK, 0.9f, 0.6f);
            Fx.sound(feet, Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.6f, 0.9f);
            Combat.hit(ctx(), owner, body, Math.max(0, params().num("miss_damage")));
            double slow = Math.clamp(params().num("miss_slow") / 100.0, 0.0, 0.9);
            long ticks = params().ticks("miss_seconds");
            if (slow > 0 && ticks > 0 && Combat.isTargetable(ctx(), owner, body)) {
                ctx().addModifier(owner, body, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, ticks);
            }
        }

        private void reveal(Picture answer) {
            if (over) {
                return;
            }
            for (Tile tile : tiles) {
                if (tile.picture == answer) {
                    tile.reveal();
                }
            }
            TextDisplay text = title;
            if (text != null && text.isValid()) {
                text.text(Text.mm("<b><" + GOLD_TAG + ">정답:</" + GOLD_TAG + "></b> " + answer.tag() + " <white>"
                        + answer.word + "</white>"));
            }
        }

        /** After the third riddle: all wrong, or the sphinx is humbled. */
        private void verdict(Score score) {
            LivingEntity body = score.body;
            if (score.asked < riddles.size() || !body.isValid() || body.isDead()) {
                return;
            }
            if (score.wrong >= riddles.size() && Combat.isTargetable(ctx(), owner, body)) {
                popOver(owner, body, "<b><" + GOLD_TAG + ">정답은 인간이었다</" + GOLD_TAG + "></b>", 1.1f);
                Fx.sound(body.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.8f, 1.3f);
                Vfx.burst(ctx(), Fx.body(body), SAND, 0.9, Particle.CRIT, false);
                Combat.hit(ctx(), owner, body, Math.max(0, params().num("all_wrong_damage")));
                if (body instanceof Player victim) {
                    long silence = params().ticks("all_wrong_silence");
                    if (silence > 0) {
                        ctx().silence(victim, silence);
                    }
                }
            } else if (score.right >= riddles.size() && body instanceof Player solver && ctx().isAlive(owner)) {
                oedipus(solver);
            }
        }

        /** Someone solved all three: the sphinx reels in fury and pain (the damage is the solver's). */
        private void oedipus(Player solver) {
            Location chest = owner.getLocation().add(0, 1.2, 0);
            popOver(owner, owner, "<b><" + LAPIS_TAG + ">오이디푸스!</" + LAPIS_TAG + "></b>", 1.3f);
            Fx.sound(chest, Sound.ENTITY_RAVAGER_STUNNED, 1.0f, 0.9f);
            Fx.sound(chest, Sound.BLOCK_DECORATED_POT_SHATTER, 1.0f, 0.6f);
            Vfx.burst(ctx(), chest, ROYAL, 0.9, Particle.CRIT, true);
            Combat.hit(ctx(), solver, owner, Math.max(0, params().num("oedipus_damage")));
            long reel = 10;
            if (ctx().isAlive(owner)) {
                ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -0.5, Operation.MULTIPLY_SCALAR_1, reel);
            }
            ctx().announce(Text.mm("<b><" + GOLD_TAG + ">스핑크스</" + GOLD_TAG + "></b> <white>누군가 세 수수께끼를 모두 "
                    + "풀었습니다!</white>"));
        }

        /** The board sinks back into the sand. */
        private void close() {
            if (over) {
                return;
            }
            for (Score score : scores.values()) {
                if (!(score.body instanceof Player)) {
                    verdict(score);
                }
            }
            end();
        }

        void end() {
            if (over) {
                return;
            }
            over = true;
            if (board == this) {
                board = null;
            }
            for (Score score : scores.values()) {
                PromptHandle handle = score.handle;
                if (handle != null && handle.isActive()) {
                    handle.cancel();
                }
            }
            for (Tile tile : tiles) {
                tile.sink(false);
            }
            TextDisplay text = title;
            if (text != null && text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 6);
            }
            Fx.sound(center, Sound.BLOCK_SAND_BREAK, 1.2f, 0.5f);
            Vfx.cloud(ctx(), center.clone().add(0, 0.4, 0), Particle.BLOCK, 30, 3.5, 0.08, sandData(), false);
        }
    }

    // ================================================================== helpers

    /** A short line popping up over {@code target}'s head (hidden from those who cannot see it). */
    private void popOver(Player owner, LivingEntity target, String mini, float scale) {
        if (!reserve(1)) {
            return;
        }
        Location at = target.getLocation().add(0, target.getHeight() + 2.3, 0);
        TextDisplay text = keep(Displays.spawn(ctx(), owner, at, TextDisplay.class, 26,
                viewer -> !ctx().isConcealedFrom(target, viewer), d -> {
                    d.text(Text.mm(mini));
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setBackgroundColor(NO_BACKGROUND);
                    d.setShadowed(true);
                    d.setTransformation(Displays.centered(0, 0, 0, 0.3f, 0.3f, 0.3f, 0, 0, 0));
                }));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, 18, () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 5);
            }
        });
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
