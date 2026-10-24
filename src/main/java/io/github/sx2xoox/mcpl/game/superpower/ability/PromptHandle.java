package io.github.sx2xoox.mcpl.game.superpower.ability;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * One {@link Prompt} submitted to one target through {@link AbilityContext#prompt}. It is <i>waiting</i> (queued
 * behind another prompt of the same target), <i>shown</i> (on the target's screen) or <i>ended</i>
 * ({@link #endReason()} is set). A shown prompt can be pushed back to waiting by a higher-priority one and resumes
 * later with the on-screen time it had left; {@link Prompt.Builder#onShown onShown} runs only the first time.
 *
 * <p>All ticks are {@link AbilityContext#currentTick()} ticks. Every method is safe to call after the prompt ended
 * (setters and {@link #cancel()} then do nothing).
 */
public interface PromptHandle {

    /** The player whose ability submitted the prompt. */
    Player owner();

    /** The player the prompt is for. */
    Player target();

    /** The prompt as submitted (its texts may have changed since through the setters below). */
    Prompt prompt();

    /** True until the prompt ended (waiting or shown). */
    boolean isActive();

    /** True while the prompt is on the target's screen right now. */
    boolean isShown();

    /** True if the prompt has been on the target's screen at least once. */
    boolean wasShown();

    /** The tick the prompt was first shown, or -1 if it never was. The answer window starts here, not at submission. */
    long shownAtTick();

    /** The tick the current showing began (the first show or the resume after a push-back), or -1 when not shown. */
    long showingSinceTick();

    /**
     * True if the prompt is shown now and has been on screen without a break for at least {@code ticks}. Rules judged
     * while a prompt is up use it with their grace plus the reaction slack:
     * {@code handle.isShownFor(grace + ctx.reactionSlackTicks(handle.target()))}.
     */
    boolean isShownFor(long ticks);

    /** Ticks the prompt has been on screen so far, all showings together. */
    long shownTicks();

    /** On-screen ticks left before it ends as {@link PromptEndReason#EXPIRED} (0 once ended). */
    long remainingTicks();

    /** Why the prompt ended, or null while it is active. */
    @Nullable PromptEndReason endReason();

    /** Replaces the action-bar line (sent right away when shown), e.g. for a countdown. */
    void setActionBar(Component actionBar);

    /** Replaces the overhead text; null removes it. */
    void setOverhead(@Nullable Component overhead);

    /** Replaces the title and subtitle (both null: no title); shown right away when the prompt is shown. */
    void setTitle(@Nullable Component title, @Nullable Component subtitle);

    /**
     * Ends the prompt now with {@link PromptEndReason#CANCELLED} (answered early, the skill stopped): its displays go
     * away, {@code onEnd} runs, and the next waiting prompt of the target is shown in the same tick.
     */
    void cancel();
}
