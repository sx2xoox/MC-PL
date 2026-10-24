package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Inputs;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
 * 카드 배틀러 — the card-game duelist ("my turn, draw!"): a hand of cards drawn from a deck, played one at a time.
 * Monsters are thrown and bite, spells heal the team and draw again, traps are set face down and spring on enemies who
 * run or jump next to them, and the rare legendary card brings down a huge golden beast. The ultimate is the
 * duelist's own turn: a full hand, nearly no cooldown, a redraw after every card and a better shot at the legend.
 *
 * <p>Look (card gold, trap purple, spell green, monster orange): the hand floats in a fan behind the back (patterned
 * card backs for everyone, coloured faces for the owner; a legendary card has a gold back that everyone sees glint).
 * A thrown monster card spins out and a pair of orange jaws with red eyes snaps shut on the target's head. A spell
 * lights a green circle and sends healing motes to allies. A trap lies flat on the ground and flips up in a purple
 * flash ("함정 카드 발동!"), chains of purple light binding the runner. The legendary beast falls out of the sky onto a
 * gold telegraph ring and stomps a shockwave.
 */
public final class CardGamerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("cardgamer", "카드 배틀러", Material.MAP)
            .color(TextColor.color(0xD9A93A))
            .difficulty(3)
            .passive("드로우",
                    "{draw_interval}초마다 카드를 한 장 뽑습니다(최대 {hand_max}장, 시작할 때 {start_hand}장). "
                            + "몬스터·마법·함정이 {monster_chance} : {spell_chance} : {trap_chance} 비율로 나오고, "
                            + "{legend_chance}% 확률로 전설의 카드가 나옵니다. 손패 앞면은 나에게만 보입니다.")
            .active("카드 발동",
                    "손패 맨 앞 카드를 씁니다(웅크리면 맨 뒤로 넘김). 몬스터: 던져 맞은 적에게 {monster_damage} 피해. "
                            + "마법: 나와 주변 아군 체력 {spell_heal} 회복, 한 장 더 뽑기. 함정: 발밑에 깔아 "
                            + "{trap_radius}블록 안에서 달리거나 점프한 적에게 {trap_damage} 피해, {trap_root}초 속박, "
                            + "{trap_silence}초 침묵. "
                            + "전설: 조준한 곳 반경 {legend_radius}블록의 적에게 {legend_damage} 피해를 주고 날려 보냅니다.",
                    2)
            .ultimate("내 턴!",
                    "손패를 {turn_hand}장까지 채우고 {turn_duration}초간 내 턴이 됩니다. 그동안 카드 발동 쿨타임이 "
                            + "{turn_cooldown}초로 줄고, 쓸 때마다 한 장씩 다시 뽑으며 전설의 카드 확률이 "
                            + "{turn_legend_chance}%로 오릅니다. 턴이 끝나면 쓴 카드 한 장마다 반경 {turn_end_radius}블록의 "
                            + "적에게 {turn_end_damage} 피해를 줍니다(최대 {turn_end_max}).")
            .ultimateNotStandalone()
            .param("draw_interval", 6)
            .param("hand_max", 3)
            .param("start_hand", 2)
            .param("monster_chance", 50)
            .param("spell_chance", 30)
            .param("trap_chance", 20)
            .param("legend_chance", 3)
            .param("shuffle_cooldown", 0.5)
            .param("monster_range", 20)
            .param("monster_damage", 4)
            .param("spell_radius", 6)
            .param("spell_heal", 3)
            .param("trap_duration", 20)
            .param("trap_max", 2)
            .param("trap_arm", 0.5)
            .param("trap_radius", 3)
            .param("trap_damage", 3)
            .param("trap_silence", 1)
            .param("trap_root", 1)
            .param("legend_range", 24)
            .param("legend_delay", 0.75)
            .param("legend_radius", 5)
            .param("legend_damage", 6)
            .param("legend_knockback", 6)
            .param("legend_terrain", 2)
            .param("turn_duration", 4)
            .param("turn_hand", 6)
            .param("turn_cooldown", 0.4)
            .param("turn_legend_chance", 15)
            .param("turn_end_radius", 5)
            .param("turn_end_damage", 0.5)
            .param("turn_end_max", 4)
            .build();

    /** The kinds of cards in the deck. */
    public enum Card {
        MONSTER("몬스터", "#E58A2E"),
        SPELL("마법", "#2FA37A"),
        TRAP("함정", "#8A3FB5"),
        LEGEND("전설", "#FFD24A");

        private final String label;
        private final String tag;

        Card(String label, String tag) {
            this.label = label;
            this.tag = tag;
        }

        /** The Korean card name. */
        public String label() {
            return label;
        }

        String colored() {
            return "<" + tag + ">" + label + "</" + tag + ">";
        }
    }

    private static final String GOLD_TAG = "#D9A93A";
    private static final String TRAP_TAG = "#8A3FB5";
    private static final String SPELL_TAG = "#2FA37A";
    private static final String MONSTER_TAG = "#E58A2E";

    // ------------------------------------------------------------------ look
    private static final Vfx.Palette GOLD = Vfx.Palette.of(0xFFF1B8, 0xD9A93A, 0x7A5A14);
    private static final Vfx.Palette TRAP_FX = Vfx.Palette.of(0xE9D2FF, 0x8A3FB5, 0x2E0F45);
    private static final Vfx.Palette SPELL_FX = Vfx.Palette.of(0xC8FFE6, 0x2FA37A, 0x0F4A35);
    private static final Vfx.Palette MONSTER_FX = Vfx.Palette.of(0xFFE0B0, 0xE58A2E, 0x6B2E08);
    private static final Color GOLD_COLOR = Color.fromRGB(0xD9A93A);
    private static final Color TRAP_COLOR = Color.fromRGB(0x8A3FB5);
    private static final Color SPELL_COLOR = Color.fromRGB(0x2FA37A);
    private static final Color EYE_COLOR = Color.fromRGB(0xFF3030);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD_COLOR, 1.0f);
    private static final Particle.DustOptions TRAP_DUST = new Particle.DustOptions(TRAP_COLOR, 0.9f);
    private static final Particle.DustOptions SPELL_DUST = new Particle.DustOptions(SPELL_COLOR, 1.0f);
    private static final Particle.DustOptions MONSTER_DUST = new Particle.DustOptions(Color.fromRGB(0xE58A2E), 1.0f);
    private static final Particle.DustOptions SHADOW_DUST = new Particle.DustOptions(Color.fromRGB(0x2A2210), 1.6f);

    /** Block types (block data is created on use: it needs a running server). */
    private static final Material BACK = Material.BLUE_GLAZED_TERRACOTTA;
    private static final Material LEGEND_BACK = Material.GOLD_BLOCK;
    private static final Material MONSTER_FACE = Material.ORANGE_CONCRETE;
    private static final Material SPELL_FACE = Material.EMERALD_BLOCK;
    private static final Material TRAP_FACE = Material.PURPLE_CONCRETE;
    private static final Material LEGEND_FACE = Material.RAW_GOLD_BLOCK;
    private static final Material JAW = Material.ORANGE_TERRACOTTA;
    private static final Material EYE = Material.REDSTONE_BLOCK;
    private static final Material BEAST = Material.GOLD_BLOCK;
    private static final Material HORN = Material.QUARTZ_BLOCK;

    private static final int MAX_DISPLAYS = 24;
    private static final float CARD_WIDTH = 0.32f;
    private static final float CARD_HEIGHT = 0.46f;
    private static final float CARD_DEPTH = 0.015f;
    /** The two faces of a card in the hand sit this far apart, the owner's face nearer the back. */
    private static final double FACE_GAP = 0.012;
    private static final double FAN_BEHIND = 0.42;
    private static final double FAN_HEIGHT = 1.15;
    private static final double FAN_SPACING = 0.2;
    private static final float FAN_TILT = 14;
    private static final float RELAYOUT_YAW = 1.5f;
    private static final int FOLLOW_LERP = 3;
    private static final long HUD_PERIOD = 60;
    private static final long TURN_HUD_PERIOD = 10;

    // active
    private static final double CARD_STEP = 1.5;
    private static final double CARD_HITBOX = 0.35;
    private static final int JAW_SNAP_TICKS = 3;
    private static final double TRAP_FIND_GROUND = 6;
    private static final double TRAP_HEIGHT = 2.5;
    /** A mob counts as running or jumping above these speeds (blocks per tick): mobs have no input. */
    private static final double MOB_RUN = 0.18;
    private static final double MOB_JUMP = 0.25;
    private static final double LEGEND_FIND_GROUND = 40;
    private static final double LEGEND_DROP = 12;
    private static final int LEGEND_FALL_TICKS = 6;
    private static final double KNOCK_LIFT = 0.5;
    /** Horizontal blocks an entity flies per block/tick of initial speed, for a lift of {@link #KNOCK_LIFT}. */
    private static final double KNOCK_REACH = 7.5;
    private static final double MAX_SPEED = 2.0;

    // ------------------------------------------------------------------ state

    /** One card in the hand: its public back and the face only the owner sees. */
    private static final class HandCard {
        final Card kind;
        final BlockDisplay back;
        final BlockDisplay face;

        HandCard(Card kind, BlockDisplay back, BlockDisplay face) {
            this.kind = kind;
            this.back = back;
            this.face = face;
        }

        void remove() {
            if (back.isValid()) {
                back.remove();
            }
            if (face.isValid()) {
                face.remove();
            }
        }
    }

    /** A face-down trap card on the ground. */
    private static final class Trap {
        final Location center;
        final BlockDisplay card;
        final long armedAt;
        final long expireAt;
        final float yaw;
        final Map<UUID, Long> insideSince = new HashMap<>();
        boolean armedShown;

        Trap(Location center, BlockDisplay card, long armedAt, long expireAt, float yaw) {
            this.center = center;
            this.card = card;
            this.armedAt = armedAt;
            this.expireAt = expireAt;
            this.yaw = yaw;
        }
    }

    private final List<HandCard> hand = new ArrayList<>();
    private final Deque<Card> stacked = new ArrayDeque<>();
    private final List<Trap> traps = new ArrayList<>();
    private final List<Display> live = new ArrayList<>();
    private long nextDraw = Long.MIN_VALUE / 2;
    private double nextCooldownFactor = 1;
    private boolean turn;
    private long turnUntil = Long.MIN_VALUE / 2;
    private int turnUsed;
    private @Nullable TextDisplay turnLabel;
    private float laidYaw = Float.NaN;
    private int laidCount = -1;
    private long lastHud = Long.MIN_VALUE / 2;
    private boolean hudDirty = true;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        long now = ctx().currentTick();
        nextDraw = now + params().ticks("draw_interval");
        int start = Math.clamp(params().integer("start_hand"), 0, handMax());
        for (int i = 0; i < start; i++) {
            addCard(rollCard(), false);
        }
    }

    @Override
    public void onRevoke() {
        for (HandCard card : hand) {
            card.remove();
        }
        hand.clear();
        traps.clear();
        live.clear();
        turn = false;
        turnLabel = null;
    }

    @Override
    public double cooldownMultiplier() {
        return nextCooldownFactor;
    }

    // ================================================================== test & HUD hooks

    /** The kinds of the cards in the hand, front first. */
    public List<Card> hand() {
        List<Card> kinds = new ArrayList<>();
        for (HandCard card : hand) {
            kinds.add(card.kind);
        }
        return kinds;
    }

    /** Puts {@code cards} on top of the deck: the next draws take them in order. */
    public void stackDeck(Card... cards) {
        for (int i = cards.length - 1; i >= 0; i--) {
            stacked.addFirst(cards[i]);
        }
    }

    /** Replaces the hand with {@code cards}, front first (practice and tests). */
    public void rigHand(Card... cards) {
        for (HandCard card : hand) {
            card.remove();
        }
        hand.clear();
        for (Card card : cards) {
            addCard(card, false);
        }
    }

    /** Face-down traps on the ground. */
    public int trapCount() {
        return traps.size();
    }

    /** True during the ultimate's turn. */
    public boolean isTurn() {
        return turn;
    }

    // ================================================================== passive: the draw

    private int handMax() {
        return Math.max(1, params().integer(turn ? "turn_hand" : "hand_max"));
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        if (hand.size() >= handMax()) {
            nextDraw = now + params().ticks("draw_interval");
        } else if (now >= nextDraw) {
            draw(true);
            nextDraw = now + params().ticks("draw_interval");
        }
        layoutHand(owner);
        tickTraps(owner, now);
        TextDisplay label = turnLabel;
        if (label != null && label.isValid()) {
            Displays.moveTo(label, owner.getLocation().add(0, owner.getHeight() + 0.75, 0));
        }
        if (turn && tick % 4 == 0) {
            Vfx.ring(ctx(), owner.getLocation().add(0, 0.1, 0), 1.1, 10, tick * 0.2, Particle.DUST, GOLD_DUST,
                    false);
        }
        if (hudDirty || now - lastHud >= (turn ? TURN_HUD_PERIOD : HUD_PERIOD)) {
            hud(owner, now);
        }
    }

    /** Draws one card from the deck (the stacked cards first). */
    private void draw(boolean announce) {
        if (hand.size() >= handMax()) {
            return;
        }
        Card card = stacked.isEmpty() ? rollCard() : stacked.removeFirst();
        addCard(card, announce);
    }

    private Card rollCard() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double legend = Math.max(0, params().num(turn ? "turn_legend_chance" : "legend_chance")) / 100.0;
        if (random.nextDouble() < legend) {
            return Card.LEGEND;
        }
        double monster = Math.max(0, params().num("monster_chance"));
        double spell = Math.max(0, params().num("spell_chance"));
        double trap = Math.max(0, params().num("trap_chance"));
        double total = monster + spell + trap;
        if (total <= 0) {
            return Card.MONSTER;
        }
        double roll = random.nextDouble() * total;
        if (roll < monster) {
            return Card.MONSTER;
        }
        return roll < monster + spell ? Card.SPELL : Card.TRAP;
    }

    /** Adds a card at the back of the hand with its two displays (the drawn card grows into the fan). */
    private void addCard(Card kind, boolean announce) {
        Player owner = player();
        Location spot = owner.getLocation().add(0, owner.getHeight() * 0.95, 0);
        Transformation tiny = Displays.scaled(0.01f);
        BlockDisplay back = Displays.block(ctx(), owner, spot, (kind == Card.LEGEND ? LEGEND_BACK : BACK)
                .createBlockData(), tiny, 0);
        back.setTeleportDuration(FOLLOW_LERP);
        BlockDisplay face = Displays.block(ctx(), owner, spot, faceOf(kind).createBlockData(), tiny, 0,
                Displays.owner(owner));
        face.setTeleportDuration(FOLLOW_LERP);
        if (kind == Card.LEGEND) {
            Displays.outline(back, GOLD_COLOR);
        }
        hand.add(new HandCard(kind, back, face));
        laidCount = -1;
        hudDirty = true;
        if (!announce) {
            return;
        }
        Location at = owner.getLocation().add(0, owner.getHeight() * 0.8, 0);
        Fx.sound(at, Sound.ITEM_BOOK_PAGE_TURN, 0.9f, 1.4f + Fx.jitter(0.15f));
        Vfx.cloud(ctx(), at, Particle.DUST, 6, 0.25, 0, kind == Card.LEGEND ? GOLD_DUST : dustOf(kind), false);
        if (kind == Card.LEGEND) {
            // Everyone sees the legend arrive: a gold flash and chime around the duelist.
            Vfx.burst(ctx(), at, GOLD, 1.2, Particle.END_ROD, true);
            Vfx.helix(ctx(), owner.getLocation(), 0.7, 2.2, 1.5, 2, 12, 0, Particle.DUST, GOLD_DUST, true);
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 0.8f);
            Fx.sound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
            owner.showTitle(Title.title(Text.mm("<b><gradient:#FFF1B8:#D9A93A>전설의 카드!</gradient></b>"),
                    Text.mm("<gray>손패에 금빛 카드가 들어왔습니다</gray>")));
        }
    }

    private static Material faceOf(Card kind) {
        return switch (kind) {
            case MONSTER -> MONSTER_FACE;
            case SPELL -> SPELL_FACE;
            case TRAP -> TRAP_FACE;
            case LEGEND -> LEGEND_FACE;
        };
    }

    private static Particle.DustOptions dustOf(Card kind) {
        return switch (kind) {
            case MONSTER -> MONSTER_DUST;
            case SPELL -> SPELL_DUST;
            case TRAP -> TRAP_DUST;
            case LEGEND -> GOLD_DUST;
        };
    }

    /**
     * Lays the hand out in a fan behind the owner's back: each card stands upright facing the way the body faces,
     * tilted around its own normal like a fan. Positions follow every tick; the rotation is re-sent only when the body
     * turned or the hand changed.
     */
    private void layoutHand(Player owner) {
        int n = hand.size();
        if (n == 0) {
            return;
        }
        float yaw = owner.getBodyYaw();
        double scale = Body.scaleOf(owner);
        Vector forward = Motion.horizontalFacing(rotated(owner.getLocation(), yaw));
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        boolean relayout = n != laidCount || Float.isNaN(laidYaw) || Math.abs(wrap(yaw - laidYaw)) > RELAYOUT_YAW;
        for (int i = 0; i < n; i++) {
            HandCard card = hand.get(i);
            double t = i - (n - 1) / 2.0;
            Location spot = owner.getLocation()
                    .add(0, (FAN_HEIGHT - Math.abs(t) * 0.04) * scale, 0)
                    .subtract(forward.clone().multiply(FAN_BEHIND * scale))
                    .add(right.clone().multiply(t * FAN_SPACING * scale));
            Displays.moveTo(card.back, spot.clone().subtract(forward.clone().multiply(FACE_GAP)));
            Displays.moveTo(card.face, spot.clone().add(forward.clone().multiply(FACE_GAP)));
            if (relayout) {
                Transformation shape = Displays.transform(0, 0, 0, CARD_WIDTH * (float) scale,
                        CARD_HEIGHT * (float) scale, CARD_DEPTH, yaw, 0, (float) (-t * FAN_TILT));
                Displays.animate(card.back, shape, 3);
                Displays.animate(card.face, shape, 3);
            }
        }
        if (relayout) {
            laidYaw = yaw;
            laidCount = n;
        }
    }

    private static Location rotated(Location at, float yaw) {
        Location copy = at.clone();
        copy.setYaw(yaw);
        return copy;
    }

    private static float wrap(float degrees) {
        float d = degrees % 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    /** The owner's own hand on its action bar (front first), with the turn's time left. */
    private void hud(Player owner, long now) {
        lastHud = now;
        hudDirty = false;
        if (ctx().hasShownPrompt(owner)) {
            return;
        }
        StringBuilder line = new StringBuilder("<" + GOLD_TAG + ">손패</" + GOLD_TAG + "> ");
        if (hand.isEmpty()) {
            line.append("<dark_gray>비어 있음</dark_gray>");
        }
        for (int i = 0; i < hand.size(); i++) {
            if (i > 0) {
                line.append(" <dark_gray>·</dark_gray> ");
            }
            line.append(i == 0 ? "<b>" : "").append(hand.get(i).kind.colored()).append(i == 0 ? "</b>" : "");
        }
        if (turn) {
            line.append("  <" + GOLD_TAG + "><b>내 턴</b></" + GOLD_TAG + "> <white>")
                    .append(Fx.seconds(turnUntil - now)).append("초</white>");
        }
        owner.sendActionBar(Text.mm(line.toString()));
    }

    // ================================================================== active: play a card

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double cooldown = Math.max(0.05, params().cooldownSeconds());
        nextCooldownFactor = turn ? Math.clamp(params().num("turn_cooldown") / cooldown, 0.0, 1.0) : 1.0;
        if (hand.isEmpty()) {
            return ActivationResult.fail("<gray>손패가 없습니다 · " + AbilityInfo.formatNumber(
                    params().num("draw_interval")) + "초마다 한 장씩 뽑습니다</gray>");
        }
        if (owner.isSneaking()) {
            if (hand.size() < 2) {
                return ActivationResult.fail("<gray>넘길 카드가 없습니다</gray>");
            }
            shuffle(owner);
            nextCooldownFactor = Math.min(nextCooldownFactor,
                    Math.clamp(params().num("shuffle_cooldown") / cooldown, 0.0, 1.0));
            return ActivationResult.success();
        }
        HandCard first = hand.getFirst();
        ActivationResult result = switch (first.kind) {
            case MONSTER -> throwMonster(owner, first);
            case SPELL -> castSpell(owner);
            case TRAP -> setTrap(owner);
            case LEGEND -> callLegend(owner);
        };
        if (!result.succeeded()) {
            return result;
        }
        hand.removeFirst();
        playedAway(owner, first);
        laidCount = -1;
        hudDirty = true;
        if (turn) {
            turnUsed++;
            draw(true);
        }
        if (first.kind == Card.SPELL) {
            draw(true);
        }
        return result;
    }

    /** The front card goes to the back of the fan. */
    private void shuffle(Player owner) {
        HandCard first = hand.removeFirst();
        hand.add(first);
        laidCount = -1;
        hudDirty = true;
        Location at = owner.getLocation().add(0, owner.getHeight() * 0.7, 0);
        Fx.sound(at, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.8f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.6f);
        Vfx.cloud(ctx(), at, Particle.DUST, 5, 0.2, 0, GOLD_DUST, false);
        owner.sendActionBar(Text.mm("<gray>맨 앞 카드를 넘겼습니다 · 다음:</gray> " + hand.getFirst().kind.colored()));
        lastHud = ctx().currentTick();
    }

    /** The played card leaves the fan with a flick and a few sparks of its colour. */
    private void playedAway(Player owner, HandCard card) {
        Location at = card.back.isValid() ? card.back.getLocation() : owner.getLocation().add(0, 1.2, 0);
        Vfx.cloud(ctx(), at, Particle.DUST, 6, 0.15, 0, dustOf(card.kind), false);
        Vfx.dot(at, Particle.END_ROD, null, false);
        card.remove();
    }

    // ------------------------------------------------------------------ monster

    private ActivationResult throwMonster(Player owner, HandCard card) {
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        Vector dir = eye.getDirection();
        if (dir.lengthSquared() < 1.0E-6) {
            return ActivationResult.fail("<gray>던질 방향이 없습니다</gray>");
        }
        dir.normalize();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        if (right.lengthSquared() > 1.0E-6) {
            right.normalize();
        }
        Location start = eye.clone().add(0, -0.25, 0).add(right.clone().multiply(0.25));
        float yaw = owner.getLocation().getYaw();
        BlockDisplay thrown = room(1) ? keep(Displays.block(ctx(), owner, start, MONSTER_FACE.createBlockData(),
                Displays.transform(0, 0, 0, CARD_WIDTH, CARD_DEPTH, CARD_HEIGHT, yaw, 0, 0), 40)) : null;
        if (thrown != null) {
            thrown.setTeleportDuration(1);
            Displays.outline(thrown, Color.fromRGB(0xE58A2E));
        }
        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.7f + Fx.jitter(0.1f));
        Fx.sound(start, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 0.8f);
        double range = Math.max(1, params().num("monster_range"));
        long steps = (long) Math.ceil(range / CARD_STEP);
        Location pos = start.clone();
        Loop.repeat(ctx(), owner, 0, 1, steps, i -> {
            RayTraceResult wall = world.rayTraceBlocks(pos, dir, CARD_STEP, FluidCollisionMode.NEVER, true);
            double reach = wall == null ? CARD_STEP : wall.getHitPosition().distance(pos.toVector());
            LivingEntity victim = firstEnemyOnSegment(owner, pos, dir, reach);
            if (victim != null) {
                Location hit = Fx.body(victim);
                if (thrown != null) {
                    Displays.moveTo(thrown, hit);
                    Displays.shrinkAway(ctx(), owner, thrown, 3);
                }
                pounce(owner, victim, yaw);
                return false;
            }
            Location next = pos.clone().add(dir.clone().multiply(reach));
            Vfx.line(ctx(), pos, next, 2, Particle.DUST, MONSTER_DUST, false);
            if (i % 2 == 0) {
                Vfx.dot(next, Particle.CRIT, null, false);
            }
            pos.setX(next.getX());
            pos.setY(next.getY());
            pos.setZ(next.getZ());
            if (thrown != null) {
                Displays.moveTo(thrown, pos);
                Displays.animate(thrown, Displays.transform(0, 0, 0, CARD_WIDTH, CARD_DEPTH, CARD_HEIGHT,
                        yaw + (i + 1) * 70, 0, 0), 1);
            }
            if (wall != null) {
                fizzle(pos, MONSTER_DUST);
                if (thrown != null) {
                    Displays.shrinkAway(ctx(), owner, thrown, 4);
                }
                return false;
            }
            if (i + 1 >= steps) {
                fizzle(pos, MONSTER_DUST);
                if (thrown != null) {
                    Displays.shrinkAway(ctx(), owner, thrown, 4);
                }
            }
            return true;
        });
        return ActivationResult.success();
    }

    /** The first enemy whose (slightly enlarged) box the segment crosses, nearest first. */
    private @Nullable LivingEntity firstEnemyOnSegment(Player owner, Location from, Vector dir, double length) {
        Location middle = from.clone().add(dir.clone().multiply(length / 2));
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity enemy : ctx().enemiesNear(owner, middle, length / 2 + 2)) {
            BoundingBox box = enemy.getBoundingBox().expand(CARD_HITBOX);
            RayTraceResult hit = box.rayTrace(from.toVector(), dir, Math.max(0.01, length));
            if (hit != null) {
                double distance = hit.getHitPosition().distance(from.toVector());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = enemy;
                }
            }
        }
        return best;
    }

    private void fizzle(Location at, Particle.DustOptions dust) {
        Vfx.cloud(ctx(), at, Particle.DUST, 8, 0.2, 0, dust, false);
        Vfx.cloud(ctx(), at, Particle.SMOKE, 3, 0.1, 0.01, null, false);
        Fx.sound(at, Sound.ITEM_BOOK_PUT, 0.6f, 1.4f);
    }

    /**
     * The monster's jaws close on the victim's head: an orange upper and lower jaw opened wide snap shut (with red eyes
     * glaring over them), and the bite lands with the snap.
     */
    private void pounce(Player owner, LivingEntity victim, float yaw) {
        Location head = victim.getEyeLocation().add(0, 0.1, 0);
        Vector forward = Motion.horizontalFacing(rotated(head, yaw));
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        List<BlockDisplay> parts = new ArrayList<>();
        if (room(4)) {
            BlockDisplay upper = keep(Displays.block(ctx(), owner, head, JAW.createBlockData(),
                    Displays.transform(0, 0.45f, 0, 1.0f, 0.22f, 0.9f, yaw, -55, 0), 16));
            BlockDisplay lower = keep(Displays.block(ctx(), owner, head, JAW.createBlockData(),
                    Displays.transform(0, -0.45f, 0, 1.0f, 0.22f, 0.9f, yaw, 55, 0), 16));
            parts.add(upper);
            parts.add(lower);
            Displays.animateNextTick(ctx(), owner, upper,
                    Displays.transform(0, 0.13f, 0, 1.0f, 0.22f, 0.9f, yaw, -6, 0), JAW_SNAP_TICKS - 1);
            Displays.animateNextTick(ctx(), owner, lower,
                    Displays.transform(0, -0.13f, 0, 1.0f, 0.22f, 0.9f, yaw, 6, 0), JAW_SNAP_TICKS - 1);
            for (int side = -1; side <= 1; side += 2) {
                Vector off = right.clone().multiply(side * 0.25).add(forward.clone().multiply(0.2));
                BlockDisplay eye = keep(Displays.block(ctx(), owner, head, EYE.createBlockData(),
                        Displays.transform((float) off.getX(), 0.62f, (float) off.getZ(), 0.16f, 0.1f, 0.06f, yaw,
                                0, 0), 16));
                Displays.outline(eye, EYE_COLOR);
                parts.add(eye);
            }
        }
        Fx.sound(head, Sound.ENTITY_RAVAGER_ROAR, 0.5f, 1.6f + Fx.jitter(0.1f));
        Vfx.cloud(ctx(), head, Particle.DUST, 10, 0.4, 0, MONSTER_DUST, false);
        ctx().runLater(owner, JAW_SNAP_TICKS, () -> {
            Location at = victim.isValid() ? victim.getEyeLocation() : head;
            Fx.sound(at, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.0f, 0.9f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.7f);
            Vfx.burst(ctx(), at, MONSTER_FX, 0.7, Particle.CRIT, false);
            Combat.hit(ctx(), owner, victim, params().num("monster_damage"));
            for (BlockDisplay part : parts) {
                ctx().runLater(owner, 6, () -> {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 5);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ spell

    private ActivationResult castSpell(Player owner) {
        Location feet = owner.getLocation();
        double radius = Math.max(0, params().num("spell_radius"));
        double heal = Math.max(0, params().num("spell_heal"));
        Location chest = Fx.body(owner);
        List<LivingEntity> healed = new ArrayList<>();
        healed.add(owner);
        healed.addAll(ctx().alliesNear(owner, feet, radius));
        for (LivingEntity target : healed) {
            ctx().heal(target, heal);
            Location to = Fx.body(target);
            if (!target.equals(owner)) {
                Vfx.trail(chest.clone().add(0, 0.8, 0), to, SPELL_COLOR, 10, false);
            }
            Vfx.cloud(ctx(), to, Particle.HAPPY_VILLAGER, 5, 0.35, 0, null, false);
            Vfx.dot(to.clone().add(0, 0.9, 0), Particle.HEART, null, false);
        }
        Location ground = feet.clone().add(0, 0.08, 0);
        Vfx.ring(ctx(), ground, 1.5, 24, 0, Particle.DUST, SPELL_DUST, false);
        Vfx.ring(ctx(), ground, 0.9, 14, 0.3, Particle.END_ROD, null, false);
        Vfx.helix(ctx(), feet, 0.8, 2.0, 1.0, 2, 10, 0, Particle.DUST_COLOR_TRANSITION, SPELL_FX.glow(0.9f), false);
        if (room(1)) {
            BlockDisplay card = keep(Displays.block(ctx(), owner, chest.clone().add(0, 0.9, 0),
                    SPELL_FACE.createBlockData(), Displays.transform(0, 0, 0, CARD_WIDTH, CARD_HEIGHT, CARD_DEPTH,
                            owner.getLocation().getYaw(), 0, 0), 14));
            Displays.animateNextTick(ctx(), owner, card, Displays.transform(0, 0.6f, 0, CARD_WIDTH * 1.4f,
                    CARD_HEIGHT * 1.4f, CARD_DEPTH, owner.getLocation().getYaw() + 180, 0, 0), 10);
            ctx().runLater(owner, 11, () -> {
                if (card.isValid()) {
                    Vfx.burst(ctx(), card.getLocation().add(0, 0.6, 0), SPELL_FX, 0.6, Particle.END_ROD, false);
                    card.remove();
                }
            });
        }
        Fx.sound(chest, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.3f);
        Fx.sound(chest, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.6f, 1.5f);
        Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.6f + Fx.jitter(0.1f));
        return ActivationResult.success();
    }

    // ------------------------------------------------------------------ trap

    private ActivationResult setTrap(Player owner) {
        World world = owner.getWorld();
        Location ground = owner.getLocation();
        Spots.snapToGround(world, ground, TRAP_FIND_GROUND);
        Block below = ground.clone().add(0, -0.05, 0).getBlock();
        if (below.isPassable()) {
            return ActivationResult.fail("<gray>함정을 덮어 둘 땅이 없습니다</gray>");
        }
        int max = Math.max(1, params().integer("trap_max"));
        while (traps.size() >= max) {
            Trap oldest = traps.removeFirst();
            removeTrap(oldest, true);
        }
        float yaw = owner.getLocation().getYaw();
        Location center = ground.clone();
        center.setYaw(0);
        center.setPitch(0);
        long now = ctx().currentTick();
        long duration = Math.max(1, params().ticks("trap_duration"));
        BlockDisplay card = Displays.block(ctx(), owner, center.clone().add(0, 0.02, 0), BACK.createBlockData(),
                Displays.transform(0, 0.7f, 0, 0.42f, CARD_DEPTH, 0.6f, yaw, -60, 0), duration + 30);
        Displays.animateNextTick(ctx(), owner, card,
                Displays.transform(0, 0.01f, 0, 0.42f, CARD_DEPTH, 0.6f, yaw, 0, 0), 3);
        traps.add(new Trap(center, card, now + params().ticks("trap_arm"), now + duration, yaw));
        Fx.sound(center, Sound.ITEM_BOOK_PUT, 1.0f, 0.8f);
        Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
        Vfx.ring(ctx(), center.clone().add(0, 0.05, 0), 0.6, 12, 0, Particle.DUST, TRAP_DUST, false);
        owner.sendActionBar(Text.mm("<" + TRAP_TAG + ">카드를 한 장 덮어 두었습니다</" + TRAP_TAG + "> <gray>· 근처에서 "
                + "달리거나 뛰는 적에게 발동</gray>"));
        lastHud = now;
        return ActivationResult.success();
    }

    private void tickTraps(Player owner, long now) {
        if (traps.isEmpty()) {
            return;
        }
        double radius = Math.max(0.5, params().num("trap_radius"));
        Iterator<Trap> it = traps.iterator();
        while (it.hasNext()) {
            Trap trap = it.next();
            if (now >= trap.expireAt || !trap.card.isValid()) {
                it.remove();
                removeTrap(trap, true);
                continue;
            }
            if (now < trap.armedAt) {
                continue;
            }
            if (!trap.armedShown) {
                trap.armedShown = true;
                Vfx.cloud(ctx(), trap.center.clone().add(0, 0.1, 0), Particle.DUST, 4, 0.2, 0, TRAP_DUST, false);
            }
            if (now % 30 == 0) {
                Vfx.dot(trap.center.clone().add(Fx.jitter(0.2f), 0.06, Fx.jitter(0.25f)), Particle.WITCH, null, false);
            }
            Set<UUID> inside = new HashSet<>();
            LivingEntity tripped = null;
            for (LivingEntity enemy : ctx().enemiesNear(owner, trap.center, radius)) {
                if (Math.abs(enemy.getLocation().getY() - trap.center.getY()) > TRAP_HEIGHT) {
                    continue;
                }
                inside.add(enemy.getUniqueId());
                long since = trap.insideSince.computeIfAbsent(enemy.getUniqueId(), id -> now);
                if (tripped == null && trips(owner, enemy, since)) {
                    tripped = enemy;
                }
            }
            trap.insideSince.keySet().retainAll(inside);
            if (tripped != null) {
                it.remove();
                spring(owner, trap, tripped);
            }
        }
    }

    /** Running or jumping by input (players) or by speed (mobs, which have no input). */
    private boolean trips(Player owner, LivingEntity enemy, long since) {
        if (enemy instanceof Player player) {
            return Inputs.breaks(ctx(), player, InputKey.SPRINT, since, owner)
                    || Inputs.breaks(ctx(), player, InputKey.JUMP, since, owner)
                    || (player.isSprinting() && !Inputs.isExempt(ctx(), player, InputKey.SPRINT, owner));
        }
        Vector v = enemy.getVelocity();
        return Math.hypot(v.getX(), v.getZ()) > MOB_RUN || v.getY() > MOB_JUMP;
    }

    /** "함정 카드 발동!": the card flips up in a purple flash and binds the runner. */
    private void spring(Player owner, Trap trap, LivingEntity victim) {
        Location card = trap.center.clone().add(0, 0.05, 0);
        Displays.animate(trap.card, Displays.transform(0, 0.55f, 0, 0.5f, 0.72f, CARD_DEPTH * 2, trap.yaw, 0, 0), 3);
        trap.card.setBlock(TRAP_FACE.createBlockData());
        Displays.outline(trap.card, TRAP_COLOR);
        ctx().runLater(owner, 18, () -> {
            if (trap.card.isValid()) {
                Displays.shrinkAway(ctx(), owner, trap.card, 6);
            }
        });
        if (room(1)) {
            TextDisplay shout = keep(Displays.text(ctx(), owner, card.clone().add(0, 1.5, 0),
                    Text.mm("<b><" + TRAP_TAG + ">함정 카드 발동!</" + TRAP_TAG + "></b>"), 1.1f, 30));
            Displays.animateNextTick(ctx(), owner, shout, Displays.centered(0, 0.4f, 0, 1.3f, 1.3f, 1.3f, 0, 0, 0), 8);
        }
        Vfx.burst(ctx(), card.clone().add(0, 0.6, 0), TRAP_FX, 0.9, Particle.WITCH, true);
        Vfx.ring(ctx(), card, params().num("trap_radius"), 28, 0, Particle.DUST, TRAP_DUST, true);
        Fx.sound(card, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 0.7f);
        Fx.sound(card, Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.6f + Fx.jitter(0.1f));
        Fx.sound(card, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 0.5f);
        Combat.hit(ctx(), owner, victim, params().num("trap_damage"));
        if (!Combat.isTargetable(ctx(), owner, victim)) {
            return;
        }
        long root = Math.max(0, params().ticks("trap_root"));
        if (victim instanceof Player player) {
            ctx().silence(player, params().ticks("trap_silence"));
        }
        if (root > 0) {
            ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -1, Operation.MULTIPLY_SCALAR_1, root);
            ctx().addModifier(owner, victim, Attribute.JUMP_STRENGTH, -1, Operation.MULTIPLY_SCALAR_1, root);
        }
        Loop.repeat(ctx(), owner, 0, 2, Math.max(1, root / 2), i -> {
            if (!victim.isValid() || victim.isDead()) {
                return false;
            }
            Location feet = victim.getLocation();
            for (int k = 0; k < 2; k++) {
                Vfx.ring(ctx(), feet.clone().add(0, 0.4 + k * 0.7, 0), 0.55, 10, i * 0.5 + k, Particle.DUST,
                        TRAP_DUST, false);
            }
            if (i % 3 == 0) {
                Vfx.trail(card.clone().add(0, 0.6, 0), Fx.body(victim), TRAP_COLOR, 6, false);
            }
            return true;
        });
        owner.sendActionBar(Text.mm("<b><" + TRAP_TAG + ">함정 카드 발동!</" + TRAP_TAG + "></b> <white><victim></white>",
                Placeholder.component("victim", victim.name())));
        lastHud = ctx().currentTick();
    }

    private void removeTrap(Trap trap, boolean fade) {
        if (!trap.card.isValid()) {
            return;
        }
        if (fade) {
            Vfx.cloud(ctx(), trap.center.clone().add(0, 0.1, 0), Particle.DUST, 5, 0.2, 0, TRAP_DUST, false);
            Displays.shrinkAway(ctx(), player(), trap.card, 5);
        } else {
            trap.card.remove();
        }
    }

    // ------------------------------------------------------------------ legend

    private ActivationResult callLegend(Player owner) {
        World world = owner.getWorld();
        double range = Math.max(1, params().num("legend_range"));
        Location eye = owner.getEyeLocation();
        Vector dir = eye.getDirection();
        Location center;
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null) {
            center = aimed.getLocation();
        } else {
            RayTraceResult ground = world.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
            center = ground != null ? ground.getHitPosition().toLocation(world)
                    : eye.clone().add(dir.clone().multiply(range));
        }
        center.add(0, 0.05, 0);
        Spots.snapToGround(world, center, LEGEND_FIND_GROUND);
        center.setYaw(0);
        center.setPitch(0);
        float yaw = owner.getLocation().getYaw();
        long delay = Math.max(LEGEND_FALL_TICKS + 2, params().ticks("legend_delay"));
        double radius = Math.max(0.5, params().num("legend_radius"));
        Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.2f);
        Fx.sound(center, Sound.BLOCK_BELL_RESONATE, 1.0f, 0.7f);
        owner.showTitle(Title.title(Text.mm("<b><gradient:#FFF1B8:#D9A93A>전설의 카드 발동!</gradient></b>"),
                Text.mm("<gray>황금 마수가 내려옵니다</gray>")));
        Loop.repeat(ctx(), owner, 0, 1, delay, i -> {
            double t = (double) (i + 1) / delay;
            Location ring = center.clone().add(0, 0.1, 0);
            Vfx.ring(ctx(), ring, radius, 26, i * 0.08, Particle.DUST, GOLD_DUST, true);
            Vfx.ring(ctx(), ring, radius * t, 12, -i * 0.12, Particle.DUST, SHADOW_DUST, true);
            if (i % 2 == 0) {
                Vfx.line(ctx(), center.clone().add(0, LEGEND_DROP, 0), ring, 1.2, Particle.END_ROD, null, true);
            }
            if (i == delay - LEGEND_FALL_TICKS - 1) {
                dropBeast(owner, center, yaw);
            }
            return true;
        }, () -> slam(owner, center, radius));
        return ActivationResult.success();
    }

    /** The golden beast, built in the sky and falling onto the ring (its parts interpolate down). */
    private void dropBeast(Player owner, Location center, float yaw) {
        Vector forward = Motion.horizontalFacing(rotated(center, yaw));
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        // x right, y up, z forward, size, pitch, roll, block
        double[][] parts = {
                {0, 1.4, 0, 2.2, 2.0, 1.6, 0, 0},
                {0, 2.95, 0.45, 1.3, 1.1, 1.2, 0, 0},
                {-1.45, 1.6, 0.4, 0.6, 1.6, 0.6, -35, 0},
                {1.45, 1.6, 0.4, 0.6, 1.6, 0.6, -35, 0},
                {-0.45, 3.75, 0.45, 0.18, 0.7, 0.18, 0, 20},
                {0.45, 3.75, 0.45, 0.18, 0.7, 0.18, 0, -20},
                {-0.3, 3.05, 1.06, 0.22, 0.14, 0.06, 0, 0},
                {0.3, 3.05, 1.06, 0.22, 0.14, 0.06, 0, 0},
        };
        Material[] blocks = {BEAST, BEAST, BEAST, BEAST, HORN, HORN, EYE, EYE};
        List<BlockDisplay> beast = new ArrayList<>();
        for (int p = 0; p < parts.length; p++) {
            if (!room(1)) {
                break;
            }
            double[] part = parts[p];
            Vector off = right.clone().multiply(part[0]).add(forward.clone().multiply(part[2]));
            float x = (float) off.getX();
            float z = (float) off.getZ();
            float y = (float) part[1];
            Transformation high = Displays.transform(x, (float) (y + LEGEND_DROP), z, (float) part[3],
                    (float) part[4], (float) part[5], yaw, (float) part[6], (float) part[7]);
            Transformation landed = Displays.transform(x, y, z, (float) part[3], (float) part[4], (float) part[5],
                    yaw, p == 2 || p == 3 ? 0 : (float) part[6], (float) part[7]);
            BlockDisplay display = keep(Displays.block(ctx(), owner, center, blocks[p].createBlockData(), high, 50));
            display.setViewRange(2.5f);
            if (blocks[p] == EYE) {
                Displays.outline(display, EYE_COLOR);
            }
            Displays.animateNextTick(ctx(), owner, display, landed, LEGEND_FALL_TICKS - 1);
            beast.add(display);
        }
        ctx().runLater(owner, LEGEND_FALL_TICKS + 22, () -> {
            for (BlockDisplay display : beast) {
                if (display.isValid()) {
                    Vfx.cloud(ctx(), display.getLocation().add(0, 1.5, 0), Particle.DUST, 3, 0.6, 0, GOLD_DUST,
                            false);
                    Displays.shrinkAway(ctx(), owner, display, 10);
                }
            }
        });
    }

    private void slam(Player owner, Location center, double radius) {
        World world = center.getWorld();
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.7f);
        Fx.sound(center, Sound.BLOCK_ANVIL_LAND, 1.0f, 0.5f);
        Fx.sound(center, Sound.ENTITY_RAVAGER_ROAR, 1.2f, 0.6f);
        world.spawnParticle(Particle.FLASH, center.clone().add(0, 1, 0), 1, 0, 0, 0, 0, GOLD_COLOR, true);
        Vfx.burst(ctx(), center.clone().add(0, 0.8, 0), GOLD, radius * 0.5, Particle.END_ROD, true);
        Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.15, 0), 0.5, radius + 0.5, 8, Particle.DUST,
                GOLD_DUST, true);
        Block ground = center.clone().add(0, -0.1, 0).getBlock();
        if (!ground.isPassable()) {
            Vfx.cloud(ctx(), center.clone().add(0, 0.3, 0), Particle.BLOCK_CRUMBLE, 30, radius * 0.4, 0,
                    ground.getBlockData(), true);
        }
        double damage = params().num("legend_damage");
        double speed = Math.max(0, params().num("legend_knockback")) / KNOCK_REACH;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            Combat.hit(ctx(), owner, enemy, damage);
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
            if (away.lengthSquared() < 1.0E-6) {
                away = Motion.horizontalFacing(owner.getLocation());
            }
            Motion.push(enemy, away.multiply(speed).setY(KNOCK_LIFT), MAX_SPEED);
        }
        double terrain = params().num("legend_terrain");
        if (terrain > 0) {
            ctx().breakBlocks(owner, center, terrain);
        }
    }

    // ================================================================== ultimate: my turn

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (turn) {
            return ActivationResult.fail("<gray>이미 내 턴입니다</gray>");
        }
        long now = ctx().currentTick();
        long duration = Math.max(1, params().ticks("turn_duration"));
        turn = true;
        turnUsed = 0;
        turnUntil = now + duration;
        hudDirty = true;
        ctx().reduceCooldown(owner, Math.max(0, ctx().cooldownRemaining(owner)) + 1);
        Location at = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        owner.showTitle(Title.title(Text.mm("<b><gradient:#FFF1B8:#D9A93A>내 턴! 드로우!</gradient></b>"),
                Text.mm("<gray>카드를 마음껏 쓰세요 · 쓸 때마다 다시 뽑습니다</gray>")));
        Fx.sound(at, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        Fx.sound(at, Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 1.4f);
        Vfx.burst(ctx(), at, GOLD, 1.4, Particle.END_ROD, true);
        Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.1, 0), 0.5, 4, 8, Particle.DUST, GOLD_DUST, true);
        int missing = Math.max(0, handMax() - hand.size());
        for (int i = 0; i < missing; i++) {
            int pitch = i;
            ctx().runLater(owner, 2L * (i + 1), () -> {
                if (turn) {
                    draw(true);
                    Fx.sound(player().getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.0f + pitch * 0.12f);
                }
            });
        }
        if (room(1)) {
            TextDisplay label = keep(Displays.text(ctx(), owner, owner.getLocation().add(0, owner.getHeight() + 0.75, 0),
                    Text.mm("<b><gradient:#FFF1B8:#D9A93A>내 턴!</gradient></b>"), 1.2f, duration + 2,
                    viewer -> !ctx().isConcealedFrom(owner, viewer)));
            label.setTeleportDuration(FOLLOW_LERP);
            turnLabel = label;
        }
        ctx().runLater(owner, duration, () -> endTurn(owner));
        return ActivationResult.success();
    }

    /** "턴 종료": every card played this turn adds to a gold pulse around the duelist. */
    private void endTurn(Player owner) {
        if (!turn) {
            return;
        }
        turn = false;
        hudDirty = true;
        laidCount = -1;
        TextDisplay label = turnLabel;
        turnLabel = null;
        if (label != null && label.isValid()) {
            label.remove();
        }
        Location feet = owner.getLocation();
        double radius = Math.max(0.5, params().num("turn_end_radius"));
        double damage = Math.min(Math.max(0, params().num("turn_end_max")),
                turnUsed * Math.max(0, params().num("turn_end_damage")));
        if (room(1)) {
            keep(Displays.text(ctx(), owner, feet.clone().add(0, owner.getHeight() + 0.8, 0),
                    Text.mm("<b><" + GOLD_TAG + ">턴 종료</" + GOLD_TAG + "></b> <gray>×" + turnUsed + "</gray>"),
                    1.0f, 30, viewer -> !ctx().isConcealedFrom(owner, viewer)));
        }
        Fx.sound(feet, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0.8f);
        Fx.sound(feet, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 0.6f);
        if (damage <= 0) {
            Vfx.cloud(ctx(), feet.clone().add(0, 1, 0), Particle.DUST, 8, 0.4, 0, GOLD_DUST, false);
            return;
        }
        Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.2, 0), 0.5, radius, 7, Particle.DUST, GOLD_DUST, true);
        Fx.sound(feet, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 0.8f);
        for (LivingEntity enemy : ctx().enemiesNear(owner, feet, radius)) {
            Combat.hit(ctx(), owner, enemy, damage);
            Vfx.burst(ctx(), Fx.body(enemy), GOLD, 0.5, Particle.CRIT, false);
        }
    }

    // ================================================================== display budget

    /** Room for {@code count} more effect displays next to the hand and the traps. */
    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + hand.size() * 2 + traps.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
