package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import java.util.function.LongPredicate;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * An owner-scoped repeating task that hands its step a 0-based run index.
 *
 * <p>The loop runs {@code step} every {@code period} ticks (after {@code delay}) until the step returns false or has
 * run {@code maxRuns} times; then {@code onEnd} runs once. A step that throws is logged and ends the loop the same
 * way, instead of failing every tick. When the owner is no longer alive (or offline) the loop stops silently,
 * without {@code onEnd}: whatever it would clean up is released with the owner's ability anyway. The task is created
 * with {@link AbilityContext#runTimer}, so it is also cancelled when the owner's ability is revoked.
 */
public final class Loop implements Runnable {

    private final AbilityContext ctx;
    private final Player owner;
    private final long maxRuns;
    private final LongPredicate step;
    private final @Nullable Runnable onEnd;
    private @Nullable BukkitTask task;
    private long runs;
    private boolean done;

    private Loop(AbilityContext ctx, Player owner, long maxRuns, LongPredicate step, @Nullable Runnable onEnd) {
        this.ctx = ctx;
        this.owner = owner;
        this.maxRuns = maxRuns;
        this.step = step;
        this.onEnd = onEnd;
    }

    /**
     * Starts a loop without an end callback; see
     * {@link #repeat(AbilityContext, Player, long, long, long, LongPredicate, Runnable)}.
     */
    public static void repeat(AbilityContext ctx, Player owner, long delay, long period, long maxRuns,
                              LongPredicate step) {
        repeat(ctx, owner, delay, period, maxRuns, step, null);
    }

    /**
     * Starts a loop.
     *
     * @param delay   ticks before the first run
     * @param period  ticks between runs
     * @param maxRuns the most runs; {@code <= 0} runs nothing and calls {@code onEnd} right away
     * @param step    receives the run index (0, 1, 2, ...); return false to stop
     * @param onEnd   runs once when the loop stops by itself (not when the owner is gone), may be null
     */
    public static void repeat(AbilityContext ctx, Player owner, long delay, long period, long maxRuns,
                              LongPredicate step, @Nullable Runnable onEnd) {
        Loop loop = new Loop(ctx, owner, maxRuns, step, onEnd);
        if (maxRuns <= 0) {
            loop.finish(true);
            return;
        }
        loop.task = ctx.runTimer(owner, delay, period, loop);
    }

    @Override
    public void run() {
        if (done) {
            return;
        }
        if (!ctx.isAlive(owner) || !owner.isOnline()) {
            finish(false);
            return;
        }
        long index = runs++;
        boolean more;
        try {
            more = step.test(index) && index + 1 < maxRuns;
        } catch (RuntimeException e) {
            ctx.plugin().getLogger().log(Level.WARNING, "Ability task of " + owner.getName() + " failed", e);
            more = false;
        }
        if (!more) {
            finish(true);
        }
    }

    private void finish(boolean runOnEnd) {
        done = true;
        if (task != null) {
            task.cancel();
        }
        if (runOnEnd && onEnd != null) {
            onEnd.run();
        }
    }
}
