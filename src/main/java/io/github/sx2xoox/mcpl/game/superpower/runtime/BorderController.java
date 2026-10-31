package io.github.sx2xoox.mcpl.game.superpower.runtime;

import io.github.sx2xoox.mcpl.core.arena.VirtualBorder;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerSettings;
import org.bukkit.Location;

/**
 * The shrinking "자기장": a per-viewer virtual world border that starts at the arena size, waits
 * {@code border.start-delay-seconds} into the fight and then shrinks to {@code border.final-diameter} over
 * {@code border.shrink-seconds}. The border itself deals no damage; the match damages players outside every second.
 */
final class BorderController {

    enum State {
        /** Not shrinking yet. */
        WAITING,
        /** Shrinking right now. */
        SHRINKING,
        /** At its final size (or configured never to shrink). */
        FINAL
    }

    private final VirtualBorder border;
    private final double initialSize;
    private final double finalSize;
    private final long delayTicks;
    private final long shrinkTicks;
    private boolean started;

    BorderController(GameSession session, SuperpowerSettings settings) {
        this.initialSize = session.arena().diameter();
        this.finalSize = Math.min(initialSize, settings.borderFinalSize());
        this.delayTicks = settings.borderDelayTicks();
        this.shrinkTicks = settings.borderShrinkTicks();
        this.border = session.createBorder(initialSize);
        border.setWarningDistance(5);
    }

    /** False when the final size is not smaller than the arena (the border never moves). */
    boolean shrinks() {
        return finalSize < initialSize - 0.5;
    }

    /**
     * Advances the schedule.
     *
     * @return true on the tick the border starts shrinking (announce it)
     */
    boolean tick(long fightTicks) {
        if (started || !shrinks() || fightTicks < delayTicks) {
            return false;
        }
        started = true;
        border.shrink(finalSize, shrinkTicks);
        return true;
    }

    State state(long fightTicks) {
        if (!shrinks()) {
            return State.FINAL;
        }
        if (!started && fightTicks < delayTicks) {
            return State.WAITING;
        }
        return border.isShrinking() ? State.SHRINKING : State.FINAL;
    }

    /**
     * How far the border has come: 0 while waiting, 0..1 while shrinking, 1 at its final size (also when it never
     * moves; see {@link #shrinks()}).
     */
    double progress(long fightTicks) {
        return switch (state(fightTicks)) {
            case WAITING -> 0.0;
            case SHRINKING -> shrinkTicks <= 0 ? 1.0
                    : Math.clamp(1.0 - border.remainingTicks() / (double) shrinkTicks, 0.0, 1.0);
            case FINAL -> 1.0;
        };
    }

    long ticksUntilShrink(long fightTicks) {
        return Math.max(0L, delayTicks - fightTicks);
    }

    long delayTicks() {
        return delayTicks;
    }

    long shrinkTicks() {
        return shrinkTicks;
    }

    long shrinkRemainingTicks() {
        return border.remainingTicks();
    }

    double currentSize() {
        return border.currentSize();
    }

    double finalSize() {
        return finalSize;
    }

    boolean isOutside(Location location) {
        return border.isOutside(location);
    }
}
