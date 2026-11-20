package io.github.sx2xoox.mcpl.game.superpower.ability;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * Something one player has to read and react to: a riddle, a question, a rule, a "hold your breath" warning. Submit it
 * with {@link AbilityContext#prompt(org.bukkit.entity.Player, org.bukkit.entity.Player, Prompt)}; never send another
 * player such a line with a raw title or action bar, which another ability would overwrite unseen.
 *
 * <h2>The prompt channel</h2>
 * Minecraft shows one title and one action bar at a time, so the match keeps one channel per player: <b>one prompt is
 * on that player's screen at a time</b>, the others wait in a queue ordered by {@link #priority()} (higher first), then
 * by submission. While shown, a prompt
 * <ul>
 *     <li>owns the target's <b>action bar</b> ({@link #actionBar() its line}, sent again every tick so vanilla never
 *     fades it and another ability's action-bar message can only flash over it for a tick);</li>
 *     <li>floats its {@link Builder#overhead overhead text} about a block over the target's head, readable by everyone
 *     else (enemies, allies, spectators, the stream) except viewers the target is hidden from (stealth, invisibility
 *     towards enemies; see {@link AbilityContext#isConcealedFrom});</li>
 *     <li>shows its {@link Builder#title title} on the target's screen, if it asks for one. Titles are secondary: only
 *     the prompt on screen has one, so keep them for lines that matter (an ultimate's question), and use plain
 *     {@code showTitle} only for the caster's own big lines on the caster's own screen.</li>
 * </ul>
 *
 * <h2>Timing</h2>
 * <ul>
 *     <li>{@link #durationTicks()} is <b>on-screen</b> time. The answer window starts when the prompt is actually shown
 *     ({@link Builder#onShown onShown}, {@link PromptHandle#shownAtTick()}), never at submission: a free channel shows a
 *     new prompt immediately (in the same call), a busy one makes it wait.</li>
 *     <li>The target's reaction slack ({@link AbilityContext#reactionSlackTicks}, taken when it is first shown) is
 *     added to that window by default ({@link Builder#reactionSlack}), so an answer given in the window's last moment
 *     reaches the server before {@link PromptEndReason#EXPIRED}. {@link PromptHandle#windowTicks()} is the whole
 *     window for that target.</li>
 *     <li>A prompt with a strictly higher priority than the one on screen takes the screen at once; the pushed-back
 *     prompt waits again ({@link Builder#onPushedBack onPushedBack}) and later resumes with the on-screen time it had
 *     left ({@link Builder#onResumed onResumed}; {@code onShown} does not run again; {@link PromptHandle#isShown()}
 *     tells whether it is up right now). It comes back first among its priority, however long the other prompt stays
 *     up, as long as the pause is shorter than {@link #maxPauseTicks()}.</li>
 *     <li>A prompt that never got the screen within {@link #maxWaitTicks()} is dropped as
 *     {@link PromptEndReason#UNSEEN}; a pushed-back prompt that could not come back within {@link #maxPauseTicks()}
 *     ends {@link PromptEndReason#INTERRUPTED}. Neither may have consequences.</li>
 *     <li>{@link Builder#onEnd onEnd} runs exactly once with the {@link PromptEndReason}. Punish or reward a final answer
 *     only on {@link PromptEndReason#EXPIRED}; it also runs for {@code OWNER_REVOKED} / {@code MATCH_END} (possibly after
 *     the owner's {@code onRevoke}), where it must only clean up. A prompt submitted from {@code onEnd} (the next step)
 *     queues behind the prompts already waiting for that target.</li>
 * </ul>
 *
 * <h2>Judging</h2>
 * A final answer (where the target stands, what it chose) is judged in {@code onEnd} on {@code EXPIRED}. A rule judged
 * continuously ("don't move") is judged only while {@link PromptHandle#isShown()}, and only from the current showing
 * on: {@code abilities.kit.Inputs.judgeFrom(ctx, handle, grace)} gives the first tick (the showing's start plus the
 * grace plus the reaction slack, again after every resume), so input the target made while the prompt was off its
 * screen never counts. Judge answers by voluntary input ({@code abilities.kit.Inputs}), never position or velocity.
 * World state a prompt raises (tiles, a talisman) can follow the screen through {@code onPushedBack} /
 * {@code onResumed}.
 *
 * <p>Prompts are cleaned up for you: an owner's prompts end when its ability is revoked, a target's when it leaves
 * play, everything at the match end. A prompt is a template: the same instance can be submitted to several targets
 * (each gets its own {@link PromptHandle}, window and slack).
 *
 * <pre>{@code
 * // A riddle: judged once, at the end of the window the target saw.
 * Prompt riddle = Prompt.builder(Text.mm("<gold>수수께끼!</gold> 같은 그림의 타일을 밟으세요"), params().ticks("answer_seconds"))
 *         .priority(Prompt.NORMAL)
 *         .overhead(Text.mm("<gold><b>☀ ?</b></gold>"))
 *         .maxWait(20)
 *         .onShown(handle -> raiseTiles(handle.target()))           // the answer timer starts now
 *         .onEnd((handle, reason) -> {
 *             if (reason == PromptEndReason.EXPIRED) {
 *                 judge(handle.target());                           // the whole window (+ slack) was on screen
 *             }
 *             removeTiles(handle.target());
 *         })
 *         .build();
 * PromptHandle handle = ctx().prompt(player(), enemy, riddle);
 *
 * // A rule: judged every tick while it is on screen, from the current showing on.
 * if (rule.isShown() && Inputs.breaksWalking(ctx(), enemy,
 *         Inputs.judgeFrom(ctx(), rule, params().ticks("grace_seconds")), player())) {
 *     punish(enemy);                                                // cap punishments per enemy
 * }
 * }</pre>
 */
public final class Prompt {

    /** Background prompts: status lines, reminders. */
    public static final int LOW = 0;
    /** Prompts of active skills. */
    public static final int NORMAL = 50;
    /** Prompts of ultimates: they push an active's prompt off the screen for their duration. */
    public static final int HIGH = 100;
    /** Default {@link #maxWaitTicks()}: 2 seconds. */
    public static final long DEFAULT_MAX_WAIT_TICKS = 40;
    /** Default {@link #maxPauseTicks()}: 10 seconds, longer than any ultimate's prompt should be. */
    public static final long DEFAULT_MAX_PAUSE_TICKS = 200;

    private final Component actionBar;
    private final @Nullable Component overhead;
    private final @Nullable Component title;
    private final @Nullable Component subtitle;
    private final int priority;
    private final long durationTicks;
    private final long maxWaitTicks;
    private final long maxPauseTicks;
    private final boolean reactionSlack;
    private final @Nullable Consumer<PromptHandle> onShown;
    private final @Nullable Consumer<PromptHandle> onPushedBack;
    private final @Nullable Consumer<PromptHandle> onResumed;
    private final @Nullable BiConsumer<PromptHandle, PromptEndReason> onEnd;

    private Prompt(Builder builder) {
        this.actionBar = builder.actionBar;
        this.overhead = builder.overhead;
        this.title = builder.title;
        this.subtitle = builder.subtitle;
        this.priority = builder.priority;
        this.durationTicks = builder.durationTicks;
        this.maxWaitTicks = builder.maxWaitTicks;
        this.maxPauseTicks = builder.maxPauseTicks;
        this.reactionSlack = builder.reactionSlack;
        this.onShown = builder.onShown;
        this.onPushedBack = builder.onPushedBack;
        this.onResumed = builder.onResumed;
        this.onEnd = builder.onEnd;
    }

    /**
     * Starts a prompt.
     *
     * @param actionBar     the line on the target's action bar while shown (the primary display)
     * @param durationTicks on-screen ticks before it ends as {@link PromptEndReason#EXPIRED} (the answer window,
     *                      plus the target's reaction slack unless {@link Builder#reactionSlack} turns it off), > 0
     */
    public static Builder builder(Component actionBar, long durationTicks) {
        return new Builder(actionBar, durationTicks);
    }

    /** The action-bar line (the primary display). */
    public Component actionBar() {
        return actionBar;
    }

    /** The text over the target's head everyone else can read, or null for none. */
    public @Nullable Component overhead() {
        return overhead;
    }

    /** The title on the target's screen while shown, or null. */
    public @Nullable Component title() {
        return title;
    }

    /** The subtitle on the target's screen while shown, or null. */
    public @Nullable Component subtitle() {
        return subtitle;
    }

    /** Queue order, higher first ({@link #LOW}, {@link #NORMAL}, {@link #HIGH} or anything between). */
    public int priority() {
        return priority;
    }

    /** On-screen ticks before the prompt expires, without the reaction slack ({@link PromptHandle#windowTicks()}). */
    public long durationTicks() {
        return durationTicks;
    }

    /** How long the prompt may wait for its first showing before it is dropped as {@link PromptEndReason#UNSEEN}. */
    public long maxWaitTicks() {
        return maxWaitTicks;
    }

    /**
     * How long a pushed-back prompt may wait to come back (each pause) before it ends
     * {@link PromptEndReason#INTERRUPTED}.
     */
    public long maxPauseTicks() {
        return maxPauseTicks;
    }

    /** True if the target's reaction slack is added to the on-screen window (the default). */
    public boolean addsReactionSlack() {
        return reactionSlack;
    }

    /** Runs once, the first time the prompt is shown; null for none. */
    public @Nullable Consumer<PromptHandle> onShown() {
        return onShown;
    }

    /** Runs when a higher-priority prompt takes the screen from this one; null for none. */
    public @Nullable Consumer<PromptHandle> onPushedBack() {
        return onPushedBack;
    }

    /** Runs when the prompt comes back on screen after a push-back; null for none. */
    public @Nullable Consumer<PromptHandle> onResumed() {
        return onResumed;
    }

    /** Runs once when the prompt ends; null for none. */
    public @Nullable BiConsumer<PromptHandle, PromptEndReason> onEnd() {
        return onEnd;
    }

    /** Builder of a {@link Prompt}; see the class documentation for what each part does. */
    public static final class Builder {

        private final Component actionBar;
        private final long durationTicks;
        private @Nullable Component overhead;
        private @Nullable Component title;
        private @Nullable Component subtitle;
        private int priority = NORMAL;
        private long maxWaitTicks = DEFAULT_MAX_WAIT_TICKS;
        private long maxPauseTicks = DEFAULT_MAX_PAUSE_TICKS;
        private boolean reactionSlack = true;
        private @Nullable Consumer<PromptHandle> onShown;
        private @Nullable Consumer<PromptHandle> onPushedBack;
        private @Nullable Consumer<PromptHandle> onResumed;
        private @Nullable BiConsumer<PromptHandle, PromptEndReason> onEnd;

        private Builder(Component actionBar, long durationTicks) {
            this.actionBar = Objects.requireNonNull(actionBar, "actionBar");
            if (durationTicks <= 0) {
                throw new IllegalArgumentException("A prompt must be shown for at least one tick: " + durationTicks);
            }
            this.durationTicks = durationTicks;
        }

        /** Queue priority (default {@link Prompt#NORMAL}); a strictly higher one takes the screen at once. */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /**
         * Public text floating over the target's head while shown (a riddle's picture, the rule, "나 예뻐?"). Keep it to
         * one or two short lines.
         */
        public Builder overhead(@Nullable Component overhead) {
            this.overhead = overhead;
            return this;
        }

        /** A title (and subtitle, may be null) on the target's screen while shown. Use sparingly; see {@link Prompt}. */
        public Builder title(@Nullable Component title, @Nullable Component subtitle) {
            this.title = title;
            this.subtitle = subtitle;
            return this;
        }

        /**
         * How long the prompt may wait for its first showing (default {@link Prompt#DEFAULT_MAX_WAIT_TICKS}); after
         * that it ends {@link PromptEndReason#UNSEEN}. 0 means "now or never": a prompt that cannot be shown at once
         * ends {@code UNSEEN} before {@code ctx.prompt(...)} returns.
         */
        public Builder maxWait(long ticks) {
            if (ticks < 0) {
                throw new IllegalArgumentException("maxWait < 0: " + ticks);
            }
            this.maxWaitTicks = ticks;
            return this;
        }

        /**
         * How long the prompt may stay pushed back by a higher-priority one, per pause (default
         * {@link Prompt#DEFAULT_MAX_PAUSE_TICKS}); after that it ends {@link PromptEndReason#INTERRUPTED}. Lower it for
         * a prompt whose situation goes stale (a riddle about where everyone stands).
         */
        public Builder maxPause(long ticks) {
            if (ticks < 0) {
                throw new IllegalArgumentException("maxPause < 0: " + ticks);
            }
            this.maxPauseTicks = ticks;
            return this;
        }

        /**
         * Whether the target's reaction slack ({@link AbilityContext#reactionSlackTicks}, taken when the prompt is
         * first shown) is added to the on-screen window (default true), so an answer the target gives in the window's
         * last moment still arrives before {@link PromptEndReason#EXPIRED}. Turn it off only for prompts whose length
         * is pure display (a countdown that ends on a fixed beat).
         */
        public Builder reactionSlack(boolean add) {
            this.reactionSlack = add;
            return this;
        }

        /**
         * Runs once, the first time the prompt is on the target's screen (possibly inside {@code ctx.prompt(...)} itself
         * when the channel is free). Start the answer timer and anything the target must react to here.
         */
        public Builder onShown(@Nullable Consumer<PromptHandle> onShown) {
            this.onShown = onShown;
            return this;
        }

        /**
         * Runs each time a higher-priority prompt takes the screen from this one (after that prompt is shown): lower
         * what the target can no longer read about (tiles, a talisman), or {@link PromptHandle#cancel()}.
         */
        public Builder onPushedBack(@Nullable Consumer<PromptHandle> onPushedBack) {
            this.onPushedBack = onPushedBack;
            return this;
        }

        /**
         * Runs each time the prompt comes back on screen after a push-back ({@link PromptHandle#showingSinceTick()} is
         * now): a rule judged continuously starts its judging again from here ({@code Inputs.judgeFrom(ctx, handle,
         * grace)} does that).
         */
        public Builder onResumed(@Nullable Consumer<PromptHandle> onResumed) {
            this.onResumed = onResumed;
            return this;
        }

        /** Runs exactly once when the prompt ends, with the reason (also when it never was shown). */
        public Builder onEnd(@Nullable BiConsumer<PromptHandle, PromptEndReason> onEnd) {
            this.onEnd = onEnd;
            return this;
        }

        public Prompt build() {
            return new Prompt(this);
        }
    }
}
