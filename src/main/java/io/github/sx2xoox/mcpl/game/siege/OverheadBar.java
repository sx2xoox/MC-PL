package io.github.sx2xoox.mcpl.game.siege;

import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A health bar floating over something (docs/siege.md "화면 표시"): a camera-facing {@link TextDisplay} with a name line
 * and a bar line ({@code ||||||||||||||||||||} in the owner's colour, the lost part dark grey, then the health). Used
 * for the towers and cores, and meant for minions and jungle monsters too: {@link #at} stands still over a place,
 * {@link #above} rides on an entity as a passenger so it follows it. The display is tracked by the session (removed
 * when the match ends at the latest) and redrawn only when its text changes.
 */
public final class OverheadBar {

    /** Segments of the bar. */
    public static final int SEGMENTS = 20;
    private static final String SEGMENT = "|";
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);

    private final GameSession session;
    private @Nullable TextDisplay display;
    private Component name;
    private TextColor color;
    private double health = 1;
    private double max = 1;
    private boolean shielded;
    private boolean showNumber = true;
    private Component shown = Component.empty();

    private OverheadBar(GameSession session, Component name, TextColor color) {
        this.session = session;
        this.name = name;
        this.color = color;
    }

    /** A bar standing at {@code at} (e.g. the top of a tower), seen from up to {@code viewRange} × 64 blocks. */
    public static OverheadBar at(GameSession session, Location at, Component name, TextColor color, float viewRange) {
        OverheadBar bar = new OverheadBar(session, name, color);
        bar.spawn(at, null, 0, viewRange);
        return bar;
    }

    /** A bar riding on {@code entity}, {@code height} blocks above where passengers sit (a minion, a monster). */
    public static OverheadBar above(GameSession session, Entity entity, double height, Component name,
                                    TextColor color) {
        OverheadBar bar = new OverheadBar(session, name, color);
        bar.spawn(entity.getLocation(), entity, height, 0.5f);
        return bar;
    }

    private void spawn(Location at, @Nullable Entity rider, double height, float viewRange) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        try {
            TextDisplay spawned = world.spawn(at, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setSeeThrough(false);
                d.setBackgroundColor(Color.fromARGB(96, 0, 0, 0));
                d.setBrightness(FULL_BRIGHT);
                d.setViewRange(viewRange);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setTeleportDuration(2);
                if (height != 0) {
                    d.setTransformation(new Transformation(new Vector3f(0, (float) height, 0), new AxisAngle4f(),
                            new Vector3f(1, 1, 1), new AxisAngle4f()));
                }
            });
            display = session.track(spawned);
            if (rider != null) {
                rider.addPassenger(spawned);
            }
            redraw();
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.WARNING, "Could not spawn an overhead bar", e);
        }
    }

    /** Health left and maximum (health points, or any unit). */
    public void health(double health, double max) {
        this.health = Math.max(0, health);
        this.max = Math.max(1.0E-9, max);
        redraw();
    }

    public void name(Component name) {
        this.name = name;
        redraw();
    }

    public void color(TextColor color) {
        this.color = color;
        redraw();
    }

    /** A shield (🛡 in front of the name, the bar in aqua). */
    public void shielded(boolean shielded) {
        this.shielded = shielded;
        redraw();
    }

    /** Whether the health number follows the bar (default true). */
    public void showNumber(boolean showNumber) {
        this.showNumber = showNumber;
        redraw();
    }

    /** What the bar shows now (tests). */
    public Component text() {
        return shown;
    }

    public @Nullable TextDisplay display() {
        return display;
    }

    /** Moves a standing bar (no effect on a bar riding an entity). */
    public void moveTo(Location to) {
        TextDisplay d = display;
        if (d != null && d.isValid() && d.getVehicle() == null) {
            d.teleport(to);
        }
    }

    /** Removes the display. */
    public void remove() {
        TextDisplay d = display;
        display = null;
        if (d != null) {
            d.remove();
            session.untrack(d);
        }
    }

    private void redraw() {
        Component next = render(name, health, max, shielded ? NamedTextColor.AQUA : color, shielded, showNumber);
        if (next.equals(shown)) {
            return;
        }
        shown = next;
        TextDisplay d = display;
        if (d != null && d.isValid()) {
            d.text(next);
        }
    }

    /** The two lines: the name (🛡 when shielded), then the bar and the health. */
    static Component render(Component name, double health, double max, TextColor fill, boolean shielded,
                            boolean number) {
        Component title = shielded ? Component.text("🛡 ", NamedTextColor.AQUA).append(name) : name;
        Component line = bar(health / max, SEGMENTS, fill);
        if (number) {
            line = line.append(Component.text(" " + (int) Math.ceil(health), NamedTextColor.WHITE));
        }
        return Component.text().append(title).append(Component.newline()).append(line).build();
    }

    /** {@code segments} bar segments, {@link #filled} of them in {@code fill}, the rest dark grey. */
    public static Component bar(double fraction, int segments, TextColor fill) {
        int filled = filled(fraction, segments);
        return Component.text(SEGMENT.repeat(filled), fill)
                .append(Component.text(SEGMENT.repeat(segments - filled), NamedTextColor.DARK_GRAY));
    }

    /** Filled segments for {@code fraction} (0..1): at least one while anything is left, all only when full. */
    public static int filled(double fraction, int segments) {
        if (!(fraction > 0)) {
            return 0;
        }
        if (fraction >= 1) {
            return segments;
        }
        return Math.max(1, Math.min(segments - 1, (int) Math.round(fraction * segments)));
    }
}
