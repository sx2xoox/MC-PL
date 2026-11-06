package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The augment cards of one offer floating in front of one player's camera ({@code augments.ui: CARDS}, League of
 * Legends Arena style): the player keeps moving and fighting while picking.
 *
 * <p>Every piece (card frame, icon, name, description, key cap, title ribbon, timer, hints) is a text display that
 * only this player sees, riding the player (so it follows every movement smoothly on the client) with billboard
 * {@code CENTER}: the client turns a display to the camera before applying its transformation, so the transformation's
 * translation is in camera space (x right, y up, -z ahead). The art is glyphs of the pack font {@link CardGlyphs#FONT}.
 * A passenger sits on top of the player's head, not at the eyes; each tick the translation takes that offset out
 * again for the current pitch and pose. Pieces of one card lie a few millimetres apart in depth, so they never fight.
 *
 * <p>Layout in screen heights at a vertical FOV of 70 (the default), the same numbers as
 * {@code tools/assetgen/augment_cards.py} (its previews). Animations (cards popping in one after another, the
 * highlighted card growing and lighting up, a rerolled card flipping out and back, the picked card rising while the
 * others fall away) are computed here each tick and smoothed by display interpolation.
 */
public final class OfferCards {

    // ── layout (same numbers as tools/assetgen/augment_cards.py) ─────────────────────────────────────────
    static final double DEPTH = 0.5;
    static final double SCREEN = 2 * DEPTH * Math.tan(Math.toRadians(35)); // blocks per screen height at DEPTH
    static final double CARD_H = 0.36;
    static final double TX = CARD_H / 144;
    static final double CARD_GAP = 0.035;
    static final double CARD_Y = 0.18;
    static final double MAX_ROW = 1.30;
    static final double NAME_UNIT = 2.3 / 1080;
    static final double DESC_UNIT = 2.1 / 1080;
    static final double HINT_UNIT = 1.9 / 1080;
    static final double ICON_Y = 36;
    static final double NAME_Y = 1.5;
    static final double DESC_TOP = -12;
    static final double DESC_W = 78;
    static final int DESC_LINES = 6;
    static final double HIGHLIGHT_SCALE = 1.10;
    static final double HIGHLIGHT_LIFT = 0.014;
    static final double RIBBON_Y = CARD_Y + CARD_H / 2 + 0.045;
    static final double FOOTER_Y = CARD_Y - CARD_H / 2 - 0.032;
    static final double INFO_Y = -0.075;
    static final double BAR_Y = -0.102;
    static final double HINT_Y = -0.13;
    /** Ticks a leaving set of cards (picked, folded) animates before it is removed. */
    static final int LEAVE_TICKS = 11;
    /** Interpolation of every transformation update (the client eases between the server's ticks). */
    static final int SMOOTH_TICKS = 2;

    /** Scoreboard tag of every card display (left-overs are found and removed by it). */
    public static final String TAG = "mcpl_offer_card";

    private enum Anchor {
        /** A glyph line: its centre is (0.5, 0) units from the display's origin. */
        GLYPH,
        /** One text line, centred. */
        TEXT,
        /** A block of text lines, placed by its top centre. */
        TEXT_TOP
    }

    /** One text display: where it sits (relative to its card's centre, or on the screen) and how big a unit is. */
    private final class Layer {
        final TextDisplay display;
        final int card;
        final double x;
        final double y;
        final double unit;
        final double depth;
        final Anchor anchor;
        int lines = 1;
        @Nullable Transformation sent;

        Layer(int card, double x, double y, double unit, double depth, Anchor anchor, Component text, boolean shadow) {
            this.card = card;
            this.x = x;
            this.y = y;
            this.unit = unit;
            this.depth = depth;
            this.anchor = anchor;
            this.display = spawn(text, shadow);
        }
    }

    private final AugmentMatch match;
    private final Player player;
    private final World world;
    private final Plugin plugin;
    private final Offer offer;
    private final List<Layer> layers = new ArrayList<>();
    /** The cards as shown (a rerolled card keeps its old face until it has flipped out). */
    private final List<AugmentInfo> shown;
    private final long[] rerollAt;
    private final double[] glow;
    private final Layer[] frames;
    private final Layer[] icons;
    private final Layer[] names;
    private final Layer[] descriptions;
    private final Layer[] footers;
    private Layer title;
    private Layer info;
    private Layer bar;
    private Layer seconds;
    private Layer hint;
    private int highlight;
    private long age;
    private long leaveAt = -1;
    private int picked = -1;
    private boolean removed;
    private int shownRerolls = -1;
    private long shownSeconds = -1;

    OfferCards(AugmentMatch match, Player player, Plugin plugin, Offer offer, int highlight) {
        this.match = match;
        this.player = player;
        this.world = player.getWorld();
        this.plugin = plugin;
        this.offer = offer;
        this.shown = new ArrayList<>(offer.cards());
        int n = shown.size();
        this.highlight = highlight >= 0 && highlight < n ? highlight : -1;
        this.rerollAt = new long[n];
        java.util.Arrays.fill(rerollAt, Long.MIN_VALUE / 2);
        this.glow = new double[n];
        this.frames = new Layer[n];
        this.icons = new Layer[n];
        this.names = new Layer[n];
        this.descriptions = new Layer[n];
        this.footers = new Layer[n];
        build();
        tick(); // place everything (hidden: the cards pop in over the next ticks)
    }

    public Offer offer() {
        return offer;
    }

    /** The highlighted card (0-based), or -1. */
    public int highlight() {
        return highlight;
    }

    /** Leaving (picked or folded): only animates out, takes no input. */
    boolean leaving() {
        return leaveAt >= 0;
    }

    /** Removed: every display is gone. */
    boolean done() {
        return removed;
    }

    /** The displays (tests). */
    List<TextDisplay> displays() {
        List<TextDisplay> out = new ArrayList<>(layers.size());
        for (Layer layer : layers) {
            out.add(layer.display);
        }
        return out;
    }

    // ── building ────────────────────────────────────────────────────────────

    private void build() {
        int n = shown.size();
        for (int i = 0; i < n; i++) {
            AugmentInfo card = shown.get(i);
            frames[i] = add(new Layer(i, 0, 0, TX, DEPTH, Anchor.GLYPH, CardGlyphs.frame(offer.tier(), false), false));
            icons[i] = add(new Layer(i, 0, ICON_Y * TX, TX, DEPTH - 0.002, Anchor.GLYPH, icon(card), false));
            names[i] = add(new Layer(i, 0, NAME_Y * TX, NAME_UNIT, DEPTH - 0.003, Anchor.TEXT, name(card), true));
            descriptions[i] = add(new Layer(i, 0, DESC_TOP * TX, DESC_UNIT, DEPTH - 0.003, Anchor.TEXT_TOP,
                    Component.empty(), true));
            describe(i, card);
            footers[i] = add(new Layer(i, 0, FOOTER_Y - CARD_Y, TX, DEPTH - 0.003, Anchor.GLYPH, footer(i, false),
                    false));
        }
        add(new Layer(-1, 0, RIBBON_Y, TX, DEPTH, Anchor.GLYPH, CardGlyphs.ribbon(offer.tier()), false));
        title = add(new Layer(-1, 0, RIBBON_Y, NAME_UNIT, DEPTH - 0.003, Anchor.TEXT, match.messages().get(
                "offer.cards-title", Messages.tag("tier", OfferMenu.tierName(match.messages(), offer.tier()))), true));
        info = add(new Layer(-1, 0, INFO_Y, HINT_UNIT, DEPTH - 0.003, Anchor.TEXT, Component.empty(), true));
        bar = add(new Layer(-1, 0, BAR_Y, TX, DEPTH, Anchor.GLYPH, CardGlyphs.bar(1), false));
        seconds = add(new Layer(-1, (CardGlyphs.BAR_W / 2.0 + 4) * TX + 12 * HINT_UNIT, BAR_Y, HINT_UNIT,
                DEPTH - 0.003, Anchor.TEXT, Component.empty(), true));
        hint = add(new Layer(-1, 0, HINT_Y, HINT_UNIT, DEPTH - 0.003, Anchor.TEXT, Component.empty(), true));
        refreshTexts();
    }

    private Layer add(Layer layer) {
        layers.add(layer);
        return layer;
    }

    private TextDisplay spawn(Component text, boolean shadow) {
        Location at = player.getLocation();
        at.setYaw(0);
        at.setPitch(0);
        TextDisplay display = world.spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.addScoreboardTag(TAG);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setDefaultBackground(false);
            d.setShadowed(shadow);
            d.setSeeThrough(false);
            d.setLineWidth(4096);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setInterpolationDuration(0);
            d.setTeleportDuration(0);
            d.setTransformation(hidden());
            d.text(text);
        });
        player.showEntity(plugin, display);
        player.addPassenger(display);
        return display;
    }

    private static Transformation hidden() {
        return new Transformation(new Vector3f(0, 0, -(float) DEPTH), new Quaternionf(), new Vector3f(0.0001f),
                new Quaternionf());
    }

    // ── content ─────────────────────────────────────────────────────────────

    private static Component icon(AugmentInfo info) {
        Component glyph = CardGlyphs.icon(info.id());
        return glyph != null ? glyph : Component.text("◆", info.tier().color());
    }

    private static Component name(AugmentInfo info) {
        return Component.text(info.name(), NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true);
    }

    /** Sets card {@code i}'s description block: the kind in the tier colour, then the wrapped description. */
    private void describe(int i, AugmentInfo info) {
        String description = match.config().params(info).render(info.description());
        int width = (int) Math.floor(DESC_W * TX / DESC_UNIT);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("◆ " + info.kind().koreanName(), info.tier().color()));
        for (String line : OfferDialog.wrap(description, width, DESC_LINES - 1, false)) {
            lines.add(Component.text(line, TextColor.color(0xEBEBEB)));
        }
        Layer layer = descriptions[i];
        layer.lines = lines.size();
        layer.display.text(Component.join(JoinConfiguration.newlines(), lines));
    }

    private Component footer(int i, boolean lit) {
        Component key = CardGlyphs.key(i + 1, lit);
        if (match.rerollsLeft(player.getUniqueId()) <= 0) {
            return key;
        }
        return Component.empty().append(key).append(CardGlyphs.space(4)).append(CardGlyphs.reroll(lit));
    }

    /** Info, hints and footers (rerolls left, offers waiting); called when they may have changed. */
    void refreshTexts() {
        int rerolls = match.rerollsLeft(player.getUniqueId());
        int queued = Math.max(0, match.pendingOffers(player.getUniqueId()) - 1);
        Component waiting = queued > 0 ? match.messages().get("offer.cards-queued", Messages.tag("count", queued))
                : Component.empty();
        info.display.text(match.messages().get("offer.cards-info", Messages.tag("source", offer.source()),
                Messages.tag("rerolls", rerolls), Messages.tag("queued", waiting)));
        Component keys = Component.empty();
        for (int i = 1; i <= shown.size(); i++) {
            keys = keys.append(CardGlyphs.smallKey((char) ('0' + i)));
        }
        hint.display.text(match.messages().get(rerolls > 0 ? "offer.cards-hint" : "offer.cards-hint-no-reroll",
                Messages.tag("keys", keys), Messages.tag("f", CardGlyphs.smallKey('F')),
                Messages.tag("sneak", CardGlyphs.smallKey('^')), Messages.tag("one", CardGlyphs.smallKey('1')),
                Messages.tag("q", CardGlyphs.smallKey('Q'))));
        if (rerolls != shownRerolls) {
            shownRerolls = rerolls;
            for (int i = 0; i < shown.size(); i++) {
                footers[i].display.text(footer(i, i == highlight));
            }
        }
    }

    private void updateClock() {
        long ticksLeft = Math.max(0, offer.deadline() - match.clock());
        long secs = match.secondsLeft(offer);
        double total = Math.max(1, match.choiceTicks());
        bar.display.text(CardGlyphs.bar(ticksLeft / total));
        if (secs != shownSeconds) {
            shownSeconds = secs;
            seconds.display.text(match.messages().get(secs <= 5 ? "offer.cards-seconds-urgent" : "offer.cards-seconds",
                    Messages.tag("seconds", secs)));
        }
    }

    // ── input ───────────────────────────────────────────────────────────────

    /** Highlights card {@code index}; false if it already was (a second press picks it). */
    boolean highlight(int index) {
        if (index < 0 || index >= shown.size() || index == highlight) {
            return false;
        }
        int before = highlight;
        highlight = index;
        if (before >= 0) {
            light(before, false);
        }
        light(index, true);
        return true;
    }

    /** Moves the highlight by {@code step} (the mouse wheel), wrapping round. */
    void moveHighlight(int step) {
        int n = shown.size();
        int from = highlight < 0 ? (step > 0 ? -1 : n) : highlight;
        highlight(Math.floorMod(from + step, n));
    }

    private void light(int i, boolean lit) {
        frames[i].display.text(CardGlyphs.frame(offer.tier(), lit));
        footers[i].display.text(footer(i, lit));
    }

    /** Card {@code index} was redrawn (the offer holds the new card): it flips out and back with its new face. */
    void rerolled(int index) {
        if (index >= 0 && index < shown.size()) {
            rerollAt[index] = age;
        }
    }

    /** Every card was redrawn: they flip one after another. */
    void rerolledAll() {
        for (int i = 0; i < shown.size(); i++) {
            rerollAt[i] = age + 2L * i;
        }
    }

    /** Starts the exit: the picked card ({@code picked} ≥ 0) rises, the others fall away; -1 = all fold away. */
    void leave(int picked) {
        if (leaveAt < 0) {
            this.picked = picked;
            leaveAt = age;
        }
    }

    // ── tick ────────────────────────────────────────────────────────────────

    /** Advances the animations and keeps the displays on the player; removes everything when gone. */
    void tick() {
        if (removed) {
            return;
        }
        if (!player.isOnline() || !player.isValid() || player.getWorld() != world
                || leaveAt >= 0 && age - leaveAt > LEAVE_TICKS) {
            remove();
            return;
        }
        if (leaveAt < 0) {
            swapRerolledFaces();
            if (age % 4 == 0) {
                updateClock();
            }
        }
        double[] head = headOffset();
        for (Layer layer : layers) {
            if (!layer.display.isValid()) {
                continue;
            }
            if (layer.display.getVehicle() != player) {
                player.addPassenger(layer.display);
            }
            Transformation next = place(layer, head);
            if (layer.sent == null || !same(layer.sent, next)) {
                if (layer.sent != null) {
                    layer.display.setInterpolationDelay(0);
                    layer.display.setInterpolationDuration(SMOOTH_TICKS);
                }
                layer.display.setTransformation(next);
                layer.sent = next;
            }
        }
        for (int i = 0; i < glow.length; i++) {
            double target = i == highlight && leaveAt < 0 ? 1 : 0;
            glow[i] += (target - glow[i]) * 0.5;
        }
        age++;
    }

    /** A rerolled card shows its new face once it has turned edge-on (3 ticks after the reroll). */
    private void swapRerolledFaces() {
        for (int i = 0; i < shown.size(); i++) {
            if (age - rerollAt[i] == 3 && i < offer.cards().size()) {
                AugmentInfo card = offer.cards().get(i);
                shown.set(i, card);
                icons[i].display.text(icon(card));
                names[i].display.text(name(card));
                describe(i, card);
            }
        }
    }

    /**
     * The offset of the displays' origin (on top of the head) from the camera (at the eyes), in the displays'
     * camera-facing frame: subtracted from every translation.
     */
    private double[] headOffset() {
        Location eye = player.getLocation();
        double h = Math.max(0, player.getHeight() - player.getEyeHeight());
        Quaternionf facing = new Quaternionf().rotationYXZ((float) Math.toRadians(-(eye.getYaw() - 180)),
                (float) Math.toRadians(-eye.getPitch()), 0);
        Vector3f local = facing.conjugate().transform(new Vector3f(0, (float) h, 0));
        return new double[] {local.x, local.y, local.z};
    }

    private Transformation place(Layer layer, double[] head) {
        double scale;
        double px;
        double py;
        if (layer.card >= 0) {
            int i = layer.card;
            double[] row = row(shown.size());
            double cardScale = row[1] * cardScale(i);
            if (cardScale <= 0.002) {
                return hidden();
            }
            double lift = cardLift(i);
            px = row[0] + i * row[2] + cardScale * layer.x;
            py = CARD_Y + lift + cardScale * layer.y;
            scale = cardScale;
        } else {
            scale = globalScale();
            if (scale <= 0.002) {
                return hidden();
            }
            px = layer.x * scale;
            py = (layer.y - CARD_Y) * scale + CARD_Y;
        }
        double z = layer.depth;
        double f = z / DEPTH;
        double unit = layer.unit * SCREEN * f * scale;   // blocks per font unit
        double ax;
        double ay;
        switch (layer.anchor) {
            case GLYPH -> {
                ax = 0.5;
                ay = 0;
            }
            case TEXT -> {
                ax = 1;
                ay = 5;
            }
            default -> {
                ax = 1;
                ay = 10.0 * layer.lines - 1;
            }
        }
        Vector3f translation = new Vector3f(
                (float) (px * SCREEN * f - ax * unit - head[0]),
                (float) (py * SCREEN * f - ay * unit - head[1]),
                (float) (-z - head[2]));
        float size = (float) (unit / 0.025);
        return new Transformation(translation, new Quaternionf(), new Vector3f(size, size, size), new Quaternionf());
    }

    /** {first card x, row scale, step} in screen heights for {@code n} cards. */
    static double[] row(int n) {
        double body = 96 * TX;
        double total = n * body + (n - 1) * CARD_GAP;
        double k = Math.min(1.0, MAX_ROW / total);
        double step = (body + CARD_GAP) * k;
        return new double[] {-(n - 1) / 2.0 * step, k, step};
    }

    private double cardScale(int i) {
        double appear = easeOutBack(clamp((age - 2 - 2.0 * i) / 8.0));
        if (age - 2 - 2 * i < 0) {
            appear = 0;
        }
        long r = age - rerollAt[i];
        double flip = 1;
        if (r >= 0 && r < 3) {
            flip = 1 - 0.95 * r / 3.0;
        } else if (r >= 3 && r < 9) {
            flip = 0.05 + 0.95 * easeOutBack((r - 3) / 6.0);
        }
        double s = appear * flip * (1 + (HIGHLIGHT_SCALE - 1) * glow[i]);
        if (leaveAt >= 0) {
            double l = age - leaveAt;
            if (i == picked) {
                s *= 1 + 0.2 * clamp(l / 4.0);
                s *= l > 4 ? 1 - clamp((l - 4) / 6.0) : 1;
            } else {
                s *= 1 - clamp(l / 6.0);
            }
        }
        return Math.max(0, s);
    }

    private double cardLift(int i) {
        double appear = clamp((age - 2 - 2.0 * i) / 8.0);
        double lift = -0.06 * (1 - easeOut(appear)) + HIGHLIGHT_LIFT * glow[i];
        if (leaveAt >= 0) {
            double l = age - leaveAt;
            lift += i == picked ? 0.06 * easeOut(clamp(l / 8.0)) : -0.10 * clamp(l / 6.0);
        }
        return lift;
    }

    private double globalScale() {
        double s = easeOut(clamp(age / 6.0));
        if (leaveAt >= 0) {
            s *= 1 - clamp((age - leaveAt) / 5.0);
        }
        return s;
    }

    private static double clamp(double x) {
        return Math.max(0, Math.min(1, x));
    }

    private static double easeOut(double x) {
        return 1 - (1 - x) * (1 - x);
    }

    private static double easeOutBack(double x) {
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
    }

    private static boolean same(Transformation a, Transformation b) {
        return a.getTranslation().distanceSquared(b.getTranslation()) < 1.0e-10
                && Math.abs(a.getScale().x - b.getScale().x) < 1.0e-6;
    }

    /** Removes every display at once. */
    void remove() {
        if (removed) {
            return;
        }
        removed = true;
        for (Layer layer : layers) {
            if (layer.display.isValid()) {
                player.removePassenger(layer.display);
                layer.display.remove();
            }
        }
    }
}
