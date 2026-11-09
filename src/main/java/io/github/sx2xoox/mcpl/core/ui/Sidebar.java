package io.github.sx2xoox.mcpl.core.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

/**
 * A flicker-free sidebar on one scoreboard: each line is a score whose {@link Score#customName(Component) custom name}
 * is the displayed text, with the score number hidden ({@link NumberFormat#blank()}). Only changed lines are sent.
 *
 * <p>During a match every viewer has their own scoreboard, so get the sidebar through
 * {@link io.github.sx2xoox.mcpl.core.game.GameSession#sidebar(org.bukkit.entity.Player)} and update it from your
 * tick (throttled, e.g. every 10 ticks). At most {@value #MAX_LINES} lines are shown.
 */
public final class Sidebar {

    public static final int MAX_LINES = 15;

    private static final String OBJECTIVE_NAME = "mcpl_sidebar";

    /** One unique, invisible score holder per line. */
    private static final String[] ENTRIES = new String[MAX_LINES];

    static {
        for (int i = 0; i < MAX_LINES; i++) {
            ENTRIES[i] = "§" + Integer.toHexString(i) + "§r";
        }
    }

    private final Objective objective;
    private final List<Component> lines = new ArrayList<>();
    private Component title;
    private boolean removed;

    /** Creates (or takes over) the sidebar objective of {@code board} and shows it in the sidebar slot. */
    public Sidebar(Scoreboard board, Component title) {
        Objective existing = board.getObjective(OBJECTIVE_NAME);
        if (existing != null) {
            existing.unregister();
        }
        this.title = title;
        this.objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, title);
        this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        this.objective.numberFormat(NumberFormat.blank());
    }

    public Component title() {
        return title;
    }

    /** Changes the title (no packet if unchanged). */
    public void title(Component title) {
        if (removed || title.equals(this.title)) {
            return;
        }
        this.title = title;
        objective.displayName(title);
    }

    /** The lines currently shown. */
    public List<Component> lines() {
        return List.copyOf(lines);
    }

    /** Replaces all lines; unchanged lines are not re-sent. Extra lines beyond {@value #MAX_LINES} are dropped. */
    public void lines(List<? extends Component> newLines) {
        if (removed) {
            return;
        }
        int count = Math.min(newLines.size(), MAX_LINES);
        for (int i = 0; i < count; i++) {
            Component line = Objects.requireNonNull(newLines.get(i), "line");
            if (i < lines.size()) {
                if (!lines.get(i).equals(line)) {
                    lines.set(i, line);
                    score(i).customName(line);
                }
            } else {
                lines.add(line);
                Score score = score(i);
                score.setScore(MAX_LINES - i);
                score.customName(line);
            }
        }
        for (int i = lines.size() - 1; i >= count; i--) {
            lines.remove(i);
            score(i).resetScore();
        }
    }

    /** Convenience for {@link #lines(List)}. */
    public void lines(Component... newLines) {
        lines(List.of(newLines));
    }

    /** Sets a single line, padding with empty lines if needed. */
    public void line(int index, Component line) {
        if (index < 0 || index >= MAX_LINES) {
            throw new IndexOutOfBoundsException(index);
        }
        List<Component> copy = new ArrayList<>(lines);
        while (copy.size() <= index) {
            copy.add(Component.empty());
        }
        copy.set(index, line);
        lines(copy);
    }

    /** Removes every line (the empty title stays visible). */
    public void clear() {
        lines(List.of());
    }

    /** Unregisters the objective; the sidebar disappears. Further calls are ignored. */
    public void remove() {
        if (removed) {
            return;
        }
        removed = true;
        lines.clear();
        try {
            objective.unregister();
        } catch (IllegalStateException ignored) {
            // already unregistered together with its scoreboard
        }
    }

    public boolean isRemoved() {
        return removed;
    }

    private Score score(int index) {
        // Entry names are never displayed (the custom name is); they only need to be unique per line and must not
        // start with '#' (vanilla hides such entries from the sidebar). '§' can never appear in a player name.
        return objective.getScore(ENTRIES[index]);
    }
}
