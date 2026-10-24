package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.game.superpower.ability.Prompt;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 격겜 고수 — the master of 2D fighting games: chained hits count up a combo that hits ever harder, the keys pressed
 * just before the skill pick the special move (a rising uppercut, a whirlwind kick, a low sweep or a dash jab), and the
 * ultimate calls "ROUND 1, FIGHT!": for a few seconds the master and the enemies around are pinned to one vertical
 * plane, a 2D stage where the only ways to dodge are back, forth and up, the combo counts double and the specials cool
 * down twice as fast, and whoever took the most hits is K.O.'d at the bell.
 *
 * <p>Look (neon blue, combo yellow, K.O. red): a combo counter over the master's head that pops at every hit, the input
 * history on the action bar, neon-blue arcs and yellow sparks for every special (a vertical crescent, a spinning ring,
 * a low fan), "DOWN" stars over a swept enemy; the stage is a neon floor line between two yellow posts, with ROUND 1,
 * FIGHT!, a countdown and a red K.O. in big letters.
 */
public final class ArcadeAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("arcade", "격겜 고수", Material.LEVER)
            .color(TextColor.color(0x2F7BFF))
            .difficulty(3)
            .passive("콤보 카운터",
                    "근접 공격이나 능력 적중을 {combo_window}초 안에 이어 가면 콤보가 쌓입니다. {combo_start}히트부터 "
                            + "히트마다 피해가 {combo_step}%씩 늘어나고(최대 {combo_max}%), 끊기면 초기화됩니다. 한 "
                            + "콤보가 {excellent_hits}히트에 이르면 궁극기 게이지를 {excellent_gauge}% 얻습니다.")
            .active("커맨드 기술",
                    "우클릭 직전 {input_window}초 안에 누른 키 순서로 기술이 바뀝니다. 웅크리기→점프: "
                            + "{upper_height}블록 솟구치며 반경 {upper_radius}블록의 적에게 {upper_damage} 피해(솟는 "
                            + "{upper_guard}초간 무적). 뒤→앞: 앞으로 {spin_distance}블록 회전하며 {spin_damage} 피해 "
                            + "두 번. 웅크리기→앞: 앞 {sweep_range}블록·{sweep_angle}°의 적에게 {sweep_damage} 피해와 "
                            + "{sweep_down}초 넘어뜨림. 입력 없음: {jab_distance}블록 돌진해 {jab_damage} 피해.",
                    7)
            .ultimate("라운드 원, 파이트!",
                    "{round_duration}초간 나와 반경 {round_radius}블록의 적이 내가 바라보는 방향의 세로면 하나에 "
                            + "고정되어, 옆으로 비킬 수 없는 2D 격투가 됩니다. 그동안 내 콤보는 {round_combo}배로 "
                            + "쌓이고 커맨드 기술의 재사용 대기시간이 {round_cooldown}% 줄며, 끝날 때 가장 많이 맞힌 "
                            + "적에게 {ko_damage} 피해를 줍니다.")
            .ultimateNotStandalone()
            .param("combo_window", 1)
            .param("combo_start", 3)
            .param("combo_step", 5)
            .param("combo_max", 25)
            .param("excellent_hits", 10)
            .param("excellent_gauge", 10)
            .param("input_window", 0.6)
            .param("upper_height", 4)
            .param("upper_radius", 2)
            .param("upper_damage", 5)
            .param("upper_lift", 3)
            .param("upper_guard", 0.3)
            .param("spin_distance", 5)
            .param("spin_damage", 2)
            .param("sweep_range", 4)
            .param("sweep_angle", 90)
            .param("sweep_damage", 3)
            .param("sweep_down", 0.75)
            .param("jab_distance", 3)
            .param("jab_damage", 3)
            .param("round_duration", 6)
            .param("round_radius", 10)
            .param("round_combo", 2)
            .param("round_cooldown", 50)
            .param("ko_damage", 3)
            .build();

    private static final String BLUE_TAG = "#2F7BFF";
    private static final String YELLOW_TAG = "#FFD400";
    private static final String RED_TAG = "#FF2D3A";

    // ------------------------------------------------------------------ look
    /** Combo yellow core, neon blue body, K.O. red edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFD400, 0x2F7BFF, 0xFF2D3A);
    private static final Color BLUE = Color.fromRGB(0x2F7BFF);
    private static final Color YELLOW = Color.fromRGB(0xFFD400);
    private static final Color RED = Color.fromRGB(0xFF2D3A);
    private static final Particle.DustOptions BLUE_DUST = new Particle.DustOptions(BLUE, 1.0f);
    private static final Particle.DustOptions YELLOW_DUST = new Particle.DustOptions(YELLOW, 0.8f);
    private static final Particle.DustOptions RED_DUST = new Particle.DustOptions(RED, 1.1f);
    private static final Particle.DustTransition NEON = new Particle.DustTransition(Color.fromRGB(0xBFD8FF), BLUE,
            1.1f);
    private static final Particle.DustTransition SPARK = new Particle.DustTransition(YELLOW, BLUE, 0.9f);

    private static final Material STAGE_LINE = Material.LIGHT_BLUE_CONCRETE;
    private static final Material STAGE_POST = Material.YELLOW_CONCRETE;

    /** The special moves, picked by the keys pressed before the cast. */
    public enum Move {
        UPPERCUT("솟구치는 어퍼", "↓ ↑"),
        WHIRLWIND("회오리 발차기", "← →"),
        SWEEP("발 쓸기", "↓ →"),
        JAB("대시 잽", "");

        private final String korean;
        private final String notation;

        Move(String korean, String notation) {
            this.korean = korean;
            this.notation = notation;
        }

        public String korean() {
            return korean;
        }
    }

    /** The keys the input history shows, with their arcade notation (forward is toward the opponent: →). */
    private static final Map<InputKey, String> GLYPHS = new EnumMap<>(Map.of(InputKey.FORWARD, "→",
            InputKey.BACKWARD, "←", InputKey.JUMP, "↑", InputKey.SNEAK, "↓"));

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 2.0;
    private static final double REST_FALL = -0.0784;
    private static final long KNOCKDOWN_STARS = 3;
    private static final double SPIN_SPEED = 0.65;
    private static final double JAB_SPEED = 1.0;
    private static final double SPIN_REACH = 1.4;
    private static final double JAB_REACH = 1.1;
    private static final long SPIN_SECOND = 4;
    private static final double WALL_MARGIN = 0.35;
    private static final long LANDING_GUARD = 80;
    private static final long BAR_PERIOD = 2;
    private static final double PLANE_TOLERANCE = 0.25;
    private static final double PULL_GROUND = 0.35;
    private static final double PULL_AIR = 0.06;
    private static final double PULL_MAX = 0.6;
    private static final long ROUND_TITLE = 20;
    /** The combo counter's height over the master's head (it rides the master). */
    private static final float COUNTER_ABOVE = 0.7f;
    /** How far down the stage looks for the floor under a master who calls the round in the air. */
    private static final double STAGE_DROP = 8;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final LandingGuard landing = new LandingGuard();
    private final Map<InputKey, Long> seenPress = new EnumMap<>(InputKey.class);
    private final List<long[]> history = new ArrayList<>();
    private int combo;
    private long lastHit = Long.MIN_VALUE / 2;
    private boolean excellent;
    private long guardUntil = Long.MIN_VALUE / 2;
    private long nextBar;
    private String lastBar = "";
    private @Nullable TextDisplay counter;
    private @Nullable Round round;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    /** Hits in the current combo. */
    public int combo() {
        return combo;
    }

    /** True while the round runs. */
    public boolean inRound() {
        Round current = round;
        return current != null && !current.ended;
    }

    @Override
    public double cooldownMultiplier() {
        return inRound() ? 1 - Math.clamp(params().num("round_cooldown") / 100.0, 0.0, 0.95) : 1.0;
    }

    @Override
    public void onRevoke() {
        Round current = round;
        if (current != null) {
            current.end(false);
        }
        round = null;
        counter = null;
        live.clear();
    }

    // ================================================================== passive: combo counter

    private long comboWindow() {
        return Math.max(1, params().ticks("combo_window"));
    }

    /** How much a hit that brings the combo to {@code hits} gains. */
    private double comboBonus(int hits) {
        int start = Math.max(1, (int) Math.round(params().num("combo_start")));
        if (hits < start) {
            return 0;
        }
        double step = Math.max(0, params().num("combo_step")) / 100.0;
        double max = Math.max(0, params().num("combo_max")) / 100.0;
        return Math.min(max, step * (hits - start + 1));
    }

    private int comboGain() {
        return inRound() ? Math.max(1, (int) Math.round(params().num("round_combo"))) : 1;
    }

    /** The master's own hits: its melee and its ability damage. */
    private boolean isComboHit(EntityDamageByEntityEvent event) {
        if (!player().equals(event.getDamager())) {
            return false;
        }
        return ctx().isAbilityDamage() || event.getCause() == DamageCause.ENTITY_ATTACK
                || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK;
    }

    /** A hit in a running combo hits harder; only changes the damage. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isComboHit(event) || !ctx().isEnemy(player(), victim)) {
            return;
        }
        int hits = (ctx().currentTick() - lastHit <= comboWindow() ? combo : 0) + comboGain();
        double bonus = comboBonus(hits);
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    /** The hit landed: the combo counts up. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isComboHit(event) || !ctx().isEnemy(player(), victim)) {
            return;
        }
        long now = ctx().currentTick();
        if (now - lastHit > comboWindow()) {
            combo = 0;
            excellent = false;
        }
        combo += comboGain();
        lastHit = now;
        Round current = round;
        if (current != null && !current.ended) {
            current.counted(victim);
        }
        showCounter(victim);
        int excellentHits = Math.max(1, (int) Math.round(params().num("excellent_hits")));
        if (!excellent && combo >= excellentHits) {
            excellent = true;
            excellent();
        }
    }

    /** The counter over the master's head pops at every hit. */
    private void showCounter(LivingEntity victim) {
        Player owner = player();
        double bonus = comboBonus(combo);
        Component line = Text.mm("<b><" + YELLOW_TAG + ">" + combo + "</" + YELLOW_TAG + "> <white>HIT!</white></b>"
                + (bonus > 0 ? " <" + BLUE_TAG + ">+" + Math.round(bonus * 100) + "%</" + BLUE_TAG + ">" : ""));
        if (combo >= 2) {
            TextDisplay shown = counter;
            if (shown == null || !shown.isValid()) {
                if (reserve(1)) {
                    // It rides the master: every client moves it with the body, dashes and all.
                    Location seat = Displays.unrotated(owner.getLocation().add(0, owner.getHeight(), 0));
                    shown = keep(Displays.spawn(ctx(), owner, seat, TextDisplay.class, 0,
                            viewer -> !ctx().isConcealedFrom(owner, viewer), display -> {
                                display.text(line);
                                display.setBillboard(Display.Billboard.CENTER);
                                display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                                display.setShadowed(true);
                                display.setTransformation(counterPose(0.8f));
                            }));
                    ride(owner, shown);
                    counter = shown;
                }
            } else {
                shown.text(line);
                Displays.animate(shown, counterPose(1.15f), 1);
                TextDisplay bumped = shown;
                ctx().runLater(owner, 2, () -> Displays.animate(bumped, counterPose(0.8f), 3));
            }
        }
        if (!ctx().isConcealedFrom(victim, owner)) {
            Location body = Fx.body(victim);
            Vfx.cloud(ctx(), body, Particle.DUST, Math.min(8, 2 + combo / 2), 0.3, 0, combo >= 3 ? YELLOW_DUST
                    : BLUE_DUST, false);
            if (combo >= 3) {
                Vfx.dot(body, Particle.CRIT, null, false);
            }
        }
        Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, Math.min(2.0f, 0.9f + 0.08f * combo));
        nextBar = 0;
    }

    /** The counter at {@code scale}, {@link #COUNTER_ABOVE} over the head of the master it rides. */
    private static Transformation counterPose(float scale) {
        return Displays.centered(0, COUNTER_ABOVE, 0, scale, scale, scale, 0, 0, 0);
    }

    /**
     * Keeps {@code display} riding {@code carrier}: a passenger moves with its carrier's body on every client, frame by
     * frame, where a display teleported after it every tick trails and wobbles. Mounted again if it was thrown off.
     */
    private static void ride(Entity carrier, Display display) {
        if (!display.isValid() || !carrier.isValid() || !display.getWorld().equals(carrier.getWorld())) {
            return;
        }
        Entity vehicle = display.getVehicle();
        if (vehicle != null && vehicle.getUniqueId().equals(carrier.getUniqueId())) {
            return;
        }
        if (vehicle != null) {
            vehicle.removePassenger(display);
        }
        carrier.addPassenger(display);
    }

    /** "EXCELLENT!" — the combo reached its mark. */
    private void excellent() {
        Player owner = player();
        ctx().addEarnedGauge(owner, Math.max(0, params().num("excellent_gauge")));
        Location over = owner.getLocation().add(0, owner.getHeight() + 1.4, 0);
        if (reserve(1)) {
            TextDisplay sign = keep(Displays.text(ctx(), owner, Displays.unrotated(over), Text.mm("<b><" + YELLOW_TAG
                    + ">EXCELLENT!</" + YELLOW_TAG + "></b>"), 0.4f, 30, viewer -> !ctx().isConcealedFrom(owner,
                    viewer)));
            Displays.animateNextTick(ctx(), owner, sign, Displays.centered(0, 0.3f, 0, 1.4f, 1.4f, 1.4f, 0, 0, 0), 5);
        }
        if (!owner.isInvisible()) {
            Vfx.ring(ctx(), owner.getLocation().add(0, 0.1, 0), 1.2, 18, 0, Particle.DUST_COLOR_TRANSITION, SPARK,
                    false);
            Vfx.cloud(ctx(), Fx.body(owner), Particle.FIREWORK, 10, 0.4, 0.15, null, false);
        }
        Fx.sound(over, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.6f);
        Fx.sound(over, Sound.BLOCK_NOTE_BLOCK_BIT, 1.0f, 2.0f);
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        landing.update(owner, now);
        recordInputs(owner, now);
        if (combo > 0 && now - lastHit > comboWindow()) {
            endCombo(owner);
        }
        TextDisplay shown = counter;
        if (shown != null && shown.isValid()) {
            ride(owner, shown);
        }
        if (now >= nextBar) {
            showBar(owner, now);
        }
        if (combo >= 3 && tick % 3 == 0 && !owner.isInvisible()) {
            // Fists crackle with the combo.
            Location hand = owner.getEyeLocation().add(owner.getEyeLocation().getDirection().multiply(0.5))
                    .subtract(0, 0.5, 0);
            Vfx.dot(hand, Particle.DUST_COLOR_TRANSITION, SPARK, false);
        }
    }

    private void endCombo(Player owner) {
        int finished = combo;
        combo = 0;
        excellent = false;
        TextDisplay shown = counter;
        counter = null;
        if (shown != null && shown.isValid()) {
            if (finished >= 3) {
                shown.text(Text.mm("<b><" + YELLOW_TAG + ">" + finished + " HIT</" + YELLOW_TAG + "></b> <white>COMBO!"
                        + "</white>"));
            }
            Displays.shrinkAway(ctx(), owner, shown, 8);
        }
        nextBar = 0;
    }

    /** New presses of the arcade keys join the input history. */
    private void recordInputs(Player owner, long now) {
        long window = inputWindow();
        for (InputKey key : GLYPHS.keySet()) {
            long pressed = ctx().lastPressTick(owner, key);
            Long seen = seenPress.get(key);
            if (pressed >= 0 && (seen == null || pressed > seen)) {
                seenPress.put(key, pressed);
                if (now - pressed <= window) {
                    history.add(new long[] {key.ordinal(), pressed});
                    history.sort((a, b) -> Long.compare(a[1], b[1]));
                    nextBar = 0;
                }
            }
        }
        if (history.removeIf(entry -> now - entry[1] > window)) {
            nextBar = 0;
        }
    }

    private long inputWindow() {
        return Math.max(1, params().ticks("input_window"));
    }

    /** The combo and the input history (with the move it would make) on the master's action bar. */
    private void showBar(Player owner, long now) {
        nextBar = now + BAR_PERIOD;
        if (ctx().hasShownPrompt(owner)) {
            return;
        }
        StringBuilder bar = new StringBuilder();
        if (combo > 0) {
            bar.append("<b><").append(YELLOW_TAG).append(">").append(combo).append(" HIT</").append(YELLOW_TAG)
                    .append("></b>");
            double bonus = comboBonus(combo);
            if (bonus > 0) {
                bar.append(" <").append(BLUE_TAG).append(">+").append(Math.round(bonus * 100)).append("%</")
                        .append(BLUE_TAG).append(">");
            }
        }
        if (!history.isEmpty()) {
            if (!bar.isEmpty()) {
                bar.append(" <dark_gray>|</dark_gray> ");
            }
            bar.append("<gray>입력</gray> <white>");
            for (long[] entry : history) {
                bar.append(GLYPHS.get(InputKey.values()[(int) entry[0]])).append(' ');
            }
            bar.append("</white>");
            Move move = readMove(owner, now);
            if (move != Move.JAB) {
                bar.append("<").append(YELLOW_TAG).append(">⇒ ").append(move.korean()).append("</").append(YELLOW_TAG)
                        .append(">");
            }
        }
        String text = bar.toString();
        if (text.isEmpty()) {
            if (!lastBar.isEmpty()) {
                owner.sendActionBar(Component.empty());
            }
            lastBar = text;
            return;
        }
        if (!text.equals(lastBar) || now % 20 == 0) {
            owner.sendActionBar(Text.mm(text));
        }
        lastBar = text;
    }

    // ================================================================== active: command moves

    /** The move the keys pressed in the last input window spell (the latest finished command wins). */
    public Move readMove(Player owner, long now) {
        long window = inputWindow();
        Move best = Move.JAB;
        long bestSecond = Long.MIN_VALUE;
        long bestFirst = Long.MIN_VALUE;
        for (Move move : new Move[] {Move.UPPERCUT, Move.WHIRLWIND, Move.SWEEP}) {
            InputKey first = switch (move) {
                case UPPERCUT, SWEEP -> InputKey.SNEAK;
                default -> InputKey.BACKWARD;
            };
            InputKey second = move == Move.UPPERCUT ? InputKey.JUMP : InputKey.FORWARD;
            long a = ctx().lastPressTick(owner, first);
            long b = ctx().lastPressTick(owner, second);
            if (a < 0 || b < 0 || now - a > window || now - b > window || b < a) {
                continue;
            }
            if (b > bestSecond || b == bestSecond && a > bestFirst) {
                best = move;
                bestSecond = b;
                bestFirst = a;
            }
        }
        return best;
    }

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Move move = readMove(owner, ctx().currentTick());
        history.clear();
        nextBar = 0;
        switch (move) {
            case UPPERCUT -> uppercut(owner);
            case WHIRLWIND -> whirlwind(owner);
            case SWEEP -> sweep(owner);
            case JAB -> jab(owner);
        }
        return ActivationResult.success();
    }

    /**
     * The move's name pops up over the master, for the others (the master's camera would rise through it in an
     * uppercut; its own action bar already shows the input history).
     */
    private void callout(Player owner, Move move) {
        if (owner.isInvisible() || !reserve(1)) {
            return;
        }
        Location over = Displays.unrotated(owner.getLocation().add(0, owner.getHeight() + 1.2, 0));
        TextDisplay sign = keep(Displays.text(ctx(), owner, over, Text.mm("<b><" + BLUE_TAG + ">" + move.korean()
                + "!</" + BLUE_TAG + "></b>" + (move.notation.isEmpty() ? "" : " <" + YELLOW_TAG + ">" + move.notation
                + "</" + YELLOW_TAG + ">")), 0.3f, 22,
                viewer -> !viewer.equals(owner) && !ctx().isConcealedFrom(owner, viewer)));
        Displays.animateNextTick(ctx(), owner, sign, Displays.centered(0, 0.2f, 0, 0.9f, 0.9f, 0.9f, 0, 0, 0), 4);
    }

    // ------------------------------------------------------------------ 솟구치는 어퍼

    private void uppercut(Player owner) {
        Location feet = owner.getLocation();
        double wanted = Math.max(0, params().num("upper_height"));
        double rise = headroom(owner, wanted);
        guardUntil = ctx().currentTick() + Math.max(0, params().ticks("upper_guard"));
        Vector facing = Motion.horizontalFacing(feet);
        owner.setVelocity(Motion.clamp(facing.clone().multiply(0.08).setY(Motion.launchSpeed(rise)), MAX_SPEED));
        owner.setFallDistance(0);
        landing.arm(ctx().currentTick(), LANDING_GUARD);
        double radius = Math.max(0, params().num("upper_radius"));
        Location chest = feet.clone().add(0, 1.0, 0);
        for (LivingEntity enemy : ctx().enemiesNear(owner, chest, radius)) {
            if (!Spots.clearPath(owner.getEyeLocation(), Fx.body(enemy))) {
                continue;
            }
            Combat.hit(ctx(), owner, enemy, params().num("upper_damage"));
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                Motion.push(enemy, facing.clone().multiply(0.15)
                        .setY(Motion.launchSpeed(Math.max(0, params().num("upper_lift")))), MAX_SPEED);
            }
            if (!ctx().isConcealedFrom(enemy, owner)) {
                Vfx.burst(ctx(), Fx.body(enemy), PALETTE, 0.6, Particle.CRIT, false);
            }
        }
        callout(owner, Move.UPPERCUT);
        Fx.sound(feet, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.ENTITY_BREEZE_JUMP, 0.9f, 1.2f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 1.5f);
        // A neon crescent rises along the master's path, yellow sparks at its tip.
        Vector side = new Vector(-facing.getZ(), 0, facing.getX());
        Loop.repeat(ctx(), owner, 0, 1, 6, run -> {
            Location at = owner.getLocation();
            double t = (run + 1) / 6.0;
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * (i / 7.0) * t;
                Location point = at.clone().add(facing.clone().multiply(Math.sin(a) * 0.9))
                        .add(0, 0.3 + (1 - Math.cos(a)) * 1.1, 0);
                Vfx.dot(point, Particle.DUST_COLOR_TRANSITION, NEON, false);
            }
            Vfx.dot(at.clone().add(facing.clone().multiply(0.6)).add(0, 2.2, 0), Particle.DUST, YELLOW_DUST, false);
            Vfx.dot(at.clone().add(side.clone().multiply(0.4)).add(0, 0.2, 0), Particle.CLOUD, null, false);
            return true;
        });
        Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), Math.max(0.5, radius), 16, 0, Particle.DUST, BLUE_DUST, false);
        Vfx.dot(chest, Particle.FLASH, YELLOW, false);
    }

    /** How far the master can rise (up to {@code wanted}) before a ceiling. */
    private static double headroom(Player owner, double wanted) {
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        RayTraceResult ceiling = world.rayTraceBlocks(eye, new Vector(0, 1, 0), wanted + 0.5, FluidCollisionMode.NEVER,
                true);
        if (ceiling == null) {
            return wanted;
        }
        return Math.max(0, Math.min(wanted, ceiling.getHitPosition().getY() - eye.getY() - 0.4));
    }

    // ------------------------------------------------------------------ dashes (회오리 발차기, 대시 잽)

    /**
     * A dash along the master's facing, steered by where its client is: every step sent is added to the reckoning, the
     * dash ends exactly at its length (or at a wall, the void or the arena border) and the hits are judged there.
     */
    private final class Dash {
        private final Player owner;
        private final Vector direction;
        private final double distance;
        private final double speed;
        private Location reckoned;
        private double travelled;

        Dash(Player owner, double distance, double speed) {
            this.owner = owner;
            Location feet = owner.getLocation();
            this.direction = Motion.horizontalFacing(feet);
            this.distance = Spots.arenaReach(ctx(), feet, direction, Math.max(0, distance));
            this.speed = speed;
            this.reckoned = feet.clone();
        }

        /** One step; false once the dash is over (the master is stopped then). */
        boolean step() {
            double left = distance - travelled;
            if (left <= 0.05) {
                halt();
                return false;
            }
            double step = Math.min(speed, left);
            World world = reckoned.getWorld();
            for (double height : new double[] {0.4, 1.5}) {
                RayTraceResult wall = world.rayTraceBlocks(reckoned.clone().add(0, height, 0), direction,
                        step + WALL_MARGIN, FluidCollisionMode.NEVER, true);
                if (wall != null) {
                    step = Math.min(step, wall.getHitPosition().distance(reckoned.clone().add(0, height, 0)
                            .toVector()) - WALL_MARGIN);
                }
            }
            Location ahead = reckoned.clone().add(direction.clone().multiply(Math.max(0, step) + 0.4)).add(0, 0.2, 0);
            if (step < 0.05 || !Spots.hasGroundBelow(world, ahead)) {
                halt();
                return false;
            }
            boolean grounded = Motion.isGrounded(owner);
            owner.setVelocity(direction.clone().multiply(step).setY(grounded ? REST_FALL : 0));
            owner.setFallDistance(0);
            reckoned = reckoned.add(direction.clone().multiply(step));
            travelled += step;
            return true;
        }

        void halt() {
            owner.setVelocity(new Vector(0, Motion.isGrounded(owner) ? REST_FALL : 0, 0));
        }

        Location body() {
            return reckoned.clone().add(0, 1.0, 0);
        }
    }

    private void whirlwind(Player owner) {
        double distance = Math.max(0, params().num("spin_distance"));
        Dash dash = new Dash(owner, distance, SPIN_SPEED);
        Set<UUID> struck = new HashSet<>();
        double damage = params().num("spin_damage");
        callout(owner, Move.WHIRLWIND);
        Fx.sound(owner.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.9f, 1.2f + Fx.jitter(0.05f));
        Fx.sound(owner.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8f, 1.5f);
        long runs = (long) Math.ceil(distance / SPIN_SPEED) + 2;
        Loop.repeat(ctx(), owner, 0, 1, runs, run -> {
            boolean more = dash.step();
            Location body = dash.body();
            for (LivingEntity enemy : ctx().enemiesNear(owner, body, SPIN_REACH)) {
                if (struck.add(enemy.getUniqueId())) {
                    kick(owner, enemy, damage);
                    ctx().runLater(owner, SPIN_SECOND, () -> {
                        if (Combat.isTargetable(ctx(), owner, enemy)) {
                            kick(owner, enemy, damage);
                        }
                    });
                }
            }
            if (!owner.isInvisible()) {
                double spin = run * 1.6;
                for (int i = 0; i < 3; i++) {
                    double a = spin + i * Math.PI * 2 / 3;
                    Vfx.dot(body.clone().add(Math.cos(a) * 1.0, -0.2 + 0.2 * i, Math.sin(a) * 1.0),
                            Particle.DUST_COLOR_TRANSITION, NEON, false);
                    Vfx.dot(body.clone().add(Math.cos(a + 0.4) * 0.8, 0.1 * i, Math.sin(a + 0.4) * 0.8),
                            Particle.DUST, YELLOW_DUST, false);
                }
                Vfx.dot(body.clone().subtract(0, 0.9, 0), Particle.CLOUD, null, false);
            }
            if (run % 2 == 0) {
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.4f + 0.05f * run);
            }
            return more;
        });
    }

    private void kick(Player owner, LivingEntity enemy, double damage) {
        Combat.hit(ctx(), owner, enemy, damage);
        Location body = Fx.body(enemy);
        if (!ctx().isConcealedFrom(enemy, owner)) {
            Vfx.dot(body, Particle.SWEEP_ATTACK, null, false);
            Vfx.cloud(ctx(), body, Particle.DUST, 4, 0.3, 0, YELLOW_DUST, false);
        }
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.3f + Fx.jitter(0.1f));
    }

    private void jab(Player owner) {
        double distance = Math.max(0, params().num("jab_distance"));
        Dash dash = new Dash(owner, distance, JAB_SPEED);
        double damage = params().num("jab_damage");
        Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 1.4f);
        Fx.sound(owner.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.6f, 1.6f);
        callout(owner, Move.JAB);
        long runs = (long) Math.ceil(distance / JAB_SPEED) + 2;
        Loop.repeat(ctx(), owner, 0, 1, runs, run -> {
            Location fist = dash.body().add(dash.direction.clone().multiply(0.7)).add(0, 0.3, 0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, fist, JAB_REACH)) {
                Combat.hit(ctx(), owner, enemy, damage);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Motion.push(enemy, dash.direction.clone().multiply(0.5).setY(0.2), MAX_SPEED);
                }
                if (!ctx().isConcealedFrom(enemy, owner)) {
                    Vfx.burst(ctx(), Fx.body(enemy), PALETTE, 0.5, Particle.CRIT, false);
                }
                Fx.sound(fist, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, 1.3f + Fx.jitter(0.1f));
                dash.halt();
                return false;
            }
            Location from = dash.body();
            boolean more = dash.step();
            if (!owner.isInvisible()) {
                Vfx.line(ctx(), from, dash.body().add(dash.direction.clone().multiply(0.8)), 4,
                        Particle.DUST_COLOR_TRANSITION, NEON, false);
                Vfx.dot(fist, Particle.DUST, YELLOW_DUST, false);
            }
            return more;
        });
    }

    // ------------------------------------------------------------------ 발 쓸기

    private void sweep(Player owner) {
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontalFacing(feet);
        double range = Math.max(0.5, params().num("sweep_range"));
        double angle = Math.clamp(params().num("sweep_angle"), 10, 360);
        long down = Math.max(0, params().ticks("sweep_down"));
        callout(owner, Move.SWEEP);
        Fx.sound(feet, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.7f);
        Fx.sound(feet, Sound.BLOCK_GRAVEL_BREAK, 0.8f, 0.9f);
        for (LivingEntity enemy : ctx().enemiesNear(owner, feet, range + 1)) {
            Location at = enemy.getLocation();
            Vector to = new Vector(at.getX() - feet.getX(), 0, at.getZ() - feet.getZ());
            double distance = to.length();
            double half = enemy.getWidth() / 2;
            if (distance - half > range || Math.abs(at.getY() - feet.getY()) > 1.5) {
                continue;
            }
            if (distance > 0.4 && Math.toDegrees(facing.angle(to.multiply(1 / distance)))
                    > angle / 2 + Math.toDegrees(Math.atan2(half, distance))) {
                continue;
            }
            if (!Spots.clearPath(feet.clone().add(0, 0.5, 0), at.clone().add(0, 0.5, 0))) {
                continue;
            }
            Combat.hit(ctx(), owner, enemy, params().num("sweep_damage"));
            if (Combat.isTargetable(ctx(), owner, enemy) && down > 0) {
                knockDown(owner, enemy, down);
            }
        }
        // A low fan of neon dust at ankle height, swept from one side to the other.
        float yaw = feet.getYaw();
        Location ankle = feet.clone().add(0, 0.25, 0);
        Loop.repeat(ctx(), owner, 0, 1, 4, run -> {
            double width = angle / 4;
            double center = yaw - angle / 2 + width * (run + 0.5);
            for (double r : new double[] {range * 0.5, range}) {
                Vfx.arc(ctx(), ankle, r, center, width, (int) Math.max(4, r * 3), Particle.DUST_COLOR_TRANSITION,
                        r >= range ? SPARK : NEON, false);
            }
            Vfx.dot(ankle.clone().add(Math.cos(Math.toRadians(center) + Math.PI / 2) * range, 0,
                    Math.sin(Math.toRadians(center) + Math.PI / 2) * range), Particle.CLOUD, null, false);
            return true;
        });
    }

    /** Knocked down: no walking, no jumping for a moment, stars over the head. */
    private void knockDown(Player owner, LivingEntity enemy, long ticks) {
        ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, ticks);
        ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, ticks);
        enemy.setVelocity(new Vector(0, enemy instanceof Player ? REST_FALL : Math.min(0, enemy.getVelocity().getY()),
                0));
        Location body = Fx.body(enemy);
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f);
        Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, 0.6f);
        if (ctx().isConcealedFrom(enemy, owner)) {
            return;
        }
        Vfx.cloud(ctx(), enemy.getLocation().add(0, 0.1, 0), Particle.CLOUD, 6, 0.4, 0.03, null, false);
        if (reserve(1)) {
            TextDisplay sign = keep(Displays.text(ctx(), owner, Displays.unrotated(enemy.getLocation().add(0,
                    enemy.getHeight() + 0.5, 0)), Text.mm("<b><" + RED_TAG + ">DOWN!</" + RED_TAG + "></b>"), 0.7f,
                    ticks + 4, viewer -> !ctx().isConcealedFrom(enemy, viewer)));
            sign.setTeleportDuration(2);
        }
        Loop.repeat(ctx(), owner, 0, KNOCKDOWN_STARS, Math.max(1, ticks / KNOCKDOWN_STARS), run -> {
            if (!enemy.isValid() || enemy.isDead() || ctx().isConcealedFrom(enemy, owner)) {
                return false;
            }
            Location head = enemy.getLocation().add(0, enemy.getHeight() + 0.15, 0);
            double a = run * 1.3;
            Vfx.dot(head.clone().add(Math.cos(a) * 0.4, 0, Math.sin(a) * 0.4), Particle.WAX_ON, null, false);
            Vfx.dot(head.clone().add(-Math.cos(a) * 0.4, 0, -Math.sin(a) * 0.4), Particle.DUST, YELLOW_DUST, false);
            return true;
        });
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        long now = ctx().currentTick();
        if (now <= guardUntil) {
            DamageType type = event.getDamageSource().getDamageType();
            if (type != DamageType.OUT_OF_WORLD && type != DamageType.OUTSIDE_BORDER && type != DamageType.GENERIC_KILL) {
                // Rising through the uppercut: untouchable.
                event.setCancelled(true);
                return;
            }
        }
        landing.absorb(event, now);
    }

    // ================================================================== ultimate: ROUND 1, FIGHT!

    @Override
    public ActivationResult onUltimate() {
        if (inRound()) {
            return ActivationResult.fail("이미 라운드가 진행 중입니다");
        }
        Player owner = player();
        Round started = new Round(owner);
        round = started;
        started.begin();
        return ActivationResult.success();
    }

    /** One fighter on the stage: where it stood last tick (for its own momentum) and when it was last corrected. */
    private static final class Fighter {
        final LivingEntity body;
        double lastX;
        double lastY;
        double lastZ;
        long nextFix;
        int hits;

        Fighter(LivingEntity body) {
            this.body = body;
            Location at = body.getLocation();
            lastX = at.getX();
            lastY = at.getY();
            lastZ = at.getZ();
        }
    }

    /**
     * The round: the master and the enemies around pinned to the vertical plane along the master's view. Sidesteps are
     * undone with fresh velocities back towards the plane (each fighter's own forward, backward and vertical motion is
     * kept), never with teleports, and a player is corrected again only after its ping has had time to apply the last
     * correction, so nobody is flung past the plane.
     */
    private final class Round {
        private final Player owner;
        private final Location origin;
        private final Vector along;
        private final Vector normal;
        private final long duration = Math.max(20, params().ticks("round_duration"));
        private final long endTick;
        private final double radius = Math.max(1, params().num("round_radius"));
        private final Map<UUID, Fighter> fighters = new LinkedHashMap<>();
        private final List<Display> stage = new ArrayList<>();
        private final float yaw;
        /** The origin on the floor under it (a round called in mid-air still draws its stage on the ground). */
        private final Location floor;
        private @Nullable TextDisplay banner;
        private @Nullable TextDisplay clock;
        private boolean ended;

        Round(Player owner) {
            this.owner = owner;
            Location feet = owner.getLocation();
            this.origin = Displays.unrotated(feet);
            this.floor = origin.clone();
            Spots.snapToGround(floor.getWorld(), floor, STAGE_DROP);
            this.along = Motion.horizontalFacing(feet);
            this.normal = new Vector(-along.getZ(), 0, along.getX());
            this.yaw = (float) Math.toDegrees(Math.atan2(-along.getX(), along.getZ()));
            this.endTick = ctx().currentTick() + duration;
        }

        void begin() {
            fighters.put(owner.getUniqueId(), new Fighter(owner));
            for (LivingEntity enemy : ctx().enemiesNear(owner, origin, radius)) {
                if (!ctx().isConcealedFrom(enemy, owner)) {
                    fighters.put(enemy.getUniqueId(), new Fighter(enemy));
                }
            }
            double remaining = ctx().cooldownRemaining(owner);
            if (remaining > 0) {
                ctx().reduceCooldown(owner, remaining * Math.clamp(params().num("round_cooldown") / 100.0, 0.0, 1.0));
            }
            buildStage();
            owner.showTitle(Title.title(Text.mm("<b><" + YELLOW_TAG + ">ROUND 1</" + YELLOW_TAG + "></b>"),
                    Text.mm("<b><" + RED_TAG + ">FIGHT!</" + RED_TAG + "></b>")));
            Prompt notice = Prompt.builder(Text.mm("<b><" + YELLOW_TAG + ">ROUND 1 — FIGHT!</" + YELLOW_TAG
                            + "></b> <white>앞뒤와 점프로만 움직일 수 있습니다</white>"), 40)
                    .priority(Prompt.HIGH)
                    .title(Text.mm("<b><" + YELLOW_TAG + ">ROUND 1</" + YELLOW_TAG + "></b>"),
                            Text.mm("<b><" + RED_TAG + ">FIGHT!</" + RED_TAG + "></b>"))
                    .maxWait(10)
                    .reactionSlack(false)
                    .build();
            for (Fighter fighter : fighters.values()) {
                if (fighter.body instanceof Player enemy && !enemy.equals(owner)) {
                    ctx().prompt(owner, enemy, notice);
                }
            }
            Fx.sound(origin, Sound.BLOCK_BELL_USE, 1.4f, 1.2f);
            Fx.sound(origin, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4f, 1.8f);
            Fx.sound(origin, Sound.BLOCK_NOTE_BLOCK_BIT, 1.0f, 1.0f);
            ctx().runLater(owner, ROUND_TITLE, () -> {
                Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1.0f, 2.0f);
                Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.6f);
            });
            Loop.repeat(ctx(), owner, 1, 1, duration, this::tick, () -> end(true));
        }

        /** A neon floor line along the plane between two yellow posts, the banner and the clock over its middle. */
        private void buildStage() {
            if (!reserve(5)) {
                return;
            }
            float length = (float) (radius * 2);
            Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
            BlockDisplay line = keep(Displays.block(ctx(), owner, floor, STAGE_LINE.createBlockData(),
                    box(rotation, 0, 0.02f, 0, 0.1f, 0.03f, 0.5f), 0));
            Displays.outline(line, BLUE);
            Displays.animateNextTick(ctx(), owner, line, box(rotation, 0, 0.02f, 0, 0.1f, 0.03f, length), 6);
            stage.add(line);
            for (int side : new int[] {-1, 1}) {
                BlockDisplay post = keep(Displays.block(ctx(), owner, floor, STAGE_POST.createBlockData(),
                        box(rotation, 0, 0.05f, side * length / 2, 0.12f, 0.1f, 0.12f), 0));
                Displays.outline(post, YELLOW);
                Displays.animateNextTick(ctx(), owner, post, box(rotation, 0, 1.25f, side * length / 2, 0.12f, 2.5f,
                        0.12f), 6);
                stage.add(post);
            }
            Location over = floor.clone().add(0, 3.4, 0);
            banner = keep(Displays.text(ctx(), owner, over, Text.mm("<b><" + YELLOW_TAG + ">ROUND 1</" + YELLOW_TAG
                    + "></b>"), 2.0f, 0));
            stage.add(banner);
            clock = keep(Displays.text(ctx(), owner, floor.clone().add(0, 4.6, 0), clockText(duration), 1.2f, 0));
            stage.add(clock);
        }

        private Component clockText(long left) {
            return Text.mm("<b><white>" + (int) Math.ceil(left / 20.0) + "</white></b>");
        }

        /** A box in stage space (x across the plane, z along it) centered at the offset. */
        private Transformation box(Quaternionf rotation, float x, float y, float z, float sx, float sy, float sz) {
            Vector3f size = new Vector3f(sx, sy, sz);
            Vector3f corner = new Vector3f(x, y, z).sub(new Vector3f(size).mul(0.5f));
            return new Transformation(new Quaternionf(rotation).transform(corner), new Quaternionf(rotation), size,
                    new Quaternionf());
        }

        void counted(LivingEntity victim) {
            Fighter fighter = fighters.get(victim.getUniqueId());
            if (fighter != null && !victim.equals(owner)) {
                fighter.hits++;
            }
        }

        private boolean tick(long run) {
            if (ended) {
                return false;
            }
            long now = ctx().currentTick();
            long left = endTick - now;
            TextDisplay shown = banner;
            if (run == ROUND_TITLE && shown != null && shown.isValid()) {
                shown.text(Text.mm("<b><" + RED_TAG + ">FIGHT!</" + RED_TAG + "></b>"));
            } else if (run == ROUND_TITLE * 2 && shown != null && shown.isValid()) {
                Displays.shrinkAway(ctx(), owner, shown, 6);
                banner = null;
            }
            TextDisplay time = clock;
            if (time != null && time.isValid() && run % 20 == 0) {
                time.text(clockText(left));
                if (left <= 60) {
                    Fx.sound(origin, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.6f);
                }
            }
            for (var it = fighters.values().iterator(); it.hasNext(); ) {
                Fighter fighter = it.next();
                LivingEntity body = fighter.body;
                boolean gone = !body.isValid() || body.isDead() || !body.getWorld().equals(origin.getWorld())
                        || body instanceof Player player && !ctx().isAlive(player);
                if (gone) {
                    it.remove();
                    continue;
                }
                pin(fighter, now);
            }
            if (run % 4 == 0) {
                drawStage(run);
            }
            return true;
        }

        /** Back towards the plane if the fighter stepped off it; its own motion along the plane and up or down is kept. */
        private void pin(Fighter fighter, long now) {
            LivingEntity body = fighter.body;
            Location at = body.getLocation();
            double vx = at.getX() - fighter.lastX;
            double vy = at.getY() - fighter.lastY;
            double vz = at.getZ() - fighter.lastZ;
            fighter.lastX = at.getX();
            fighter.lastY = at.getY();
            fighter.lastZ = at.getZ();
            double offset = at.toVector().subtract(origin.toVector()).dot(normal);
            if (Math.abs(offset) <= PLANE_TOLERANCE || now < fighter.nextFix) {
                return;
            }
            boolean grounded = Motion.isGrounded(body) || body.isInWater();
            double pull = -Math.clamp(offset * (grounded ? PULL_GROUND : PULL_AIR), -PULL_MAX, PULL_MAX);
            Vector motion = body instanceof Player ? new Vector(vx, vy, vz) : body.getVelocity();
            double forward = motion.dot(along);
            double vertical = grounded ? (body instanceof Player ? REST_FALL : Math.min(0, motion.getY()))
                    : body instanceof Player ? (vy - Motion.GRAVITY) * Motion.DRAG : motion.getY();
            Vector velocity = along.clone().multiply(Math.clamp(forward, -0.6, 0.6)).add(normal.clone().multiply(pull))
                    .setY(Math.clamp(vertical, -1.5, 1.5));
            // The stage holds everyone alike, knockback resistance or not.
            body.setVelocity(Motion.clamp(velocity, MAX_SPEED));
            // A player hears the correction a round trip later: wait for it before judging the offset again.
            fighter.nextFix = now + (body instanceof Player player ? Math.max(2, ctx().reactionSlackTicks(player)) : 1);
        }

        private void drawStage(long run) {
            double length = radius;
            for (double d = -length; d <= length; d += 1.0) {
                if (((int) Math.round(d) + run / 4) % 3 != 0) {
                    continue;
                }
                Vfx.dot(floor.clone().add(along.clone().multiply(d)).add(0, 0.08, 0), Particle.DUST, BLUE_DUST,
                        true);
            }
            if (run % 8 == 0) {
                for (Fighter fighter : fighters.values()) {
                    if (!ctx().isConcealedFrom(fighter.body, owner)) {
                        Location feet = fighter.body.getLocation().add(0, 0.08, 0);
                        Vfx.line(ctx(), feet.clone().add(normal.clone().multiply(0.6)),
                                feet.clone().subtract(normal.clone().multiply(0.6)), 4, Particle.DUST,
                                fighter.body.equals(owner) ? YELLOW_DUST : RED_DUST, false);
                    }
                }
            }
        }

        /** The bell: the enemy that took the most hits is K.O.'d. */
        void end(boolean visible) {
            if (ended) {
                return;
            }
            ended = true;
            Fighter loser = null;
            for (Fighter fighter : fighters.values()) {
                if (!fighter.body.equals(owner) && fighter.hits > 0 && (loser == null || fighter.hits > loser.hits)) {
                    loser = fighter;
                }
            }
            if (visible && ctx().isAlive(owner)) {
                Fx.sound(origin, Sound.BLOCK_BELL_USE, 1.4f, 0.9f);
                if (loser != null && Combat.isTargetable(ctx(), owner, loser.body)) {
                    knockOut(loser.body);
                } else {
                    owner.showTitle(Title.title(Text.mm("<b><white>TIME OVER</white></b>"), Component.empty()));
                }
            }
            for (Display display : stage) {
                if (display.isValid()) {
                    if (visible && ctx().isAlive(owner)) {
                        Displays.shrinkAway(ctx(), owner, display, 6);
                    } else {
                        display.remove();
                    }
                }
            }
            stage.clear();
            banner = null;
            clock = null;
            fighters.clear();
            if (round == this) {
                round = null;
            }
        }

        private void knockOut(LivingEntity loser) {
            Combat.hit(ctx(), owner, loser, params().num("ko_damage"));
            Location body = Fx.body(loser);
            owner.showTitle(Title.title(Text.mm("<b><" + RED_TAG + ">K.O.</" + RED_TAG + "></b>"), Component.empty()));
            Fx.sound(body, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.5f);
            Fx.sound(body, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 0.8f);
            if (ctx().isConcealedFrom(loser, owner)) {
                return;
            }
            Vfx.burst(ctx(), body, PALETTE, 1.2, Particle.CRIT, true);
            Vfx.dot(body, Particle.FLASH, RED, true);
            if (reserve(1)) {
                TextDisplay ko = keep(Displays.text(ctx(), owner, Displays.unrotated(loser.getLocation().add(0,
                        loser.getHeight() + 1.0, 0)), Text.mm("<b><" + RED_TAG + ">K.O.</" + RED_TAG + "></b>"), 0.5f,
                        36, viewer -> !ctx().isConcealedFrom(loser, viewer)));
                Displays.animateNextTick(ctx(), owner, ko, Displays.centered(0, 0.4f, 0, 2.4f, 2.4f, 2.4f, 0, 0, 0),
                        5);
            }
        }
    }

    // ================================================================== helpers

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
