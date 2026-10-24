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
 *     <li>owns the target's <b>action bar</b> ({@link #actionBar() its line}, sent again every few ticks so vanilla
 *     never fades it and another ability's action-bar message can only flash over it for a moment);</li>
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
 *     <li>A prompt with a strictly higher priority than the one on screen takes the screen at once; the pushed-back
 *     prompt waits again and later resumes with the on-screen time it had left ({@code onShown} does not run again;
 *     {@link PromptHandle#isShown()} tells whether it is up right now).</li>
 *     <li>A prompt that waited longer than {@link #maxWaitTicks()} is dropped: {@link PromptEndReason#UNSEEN} if it was
 *     never shown, {@link PromptEndReason#INTERRUPTED} if it was pushed back. Neither may have consequences.</li>
 *     <li>{@link Builder#onEnd onEnd} runs exactly once with the {@link PromptEndReason}. Punish or reward a final answer
 *     only on {@link PromptEndReason#EXPIRED}; it also runs for {@code OWNER_REVOKED} / {@code MATCH_END} (possibly after
 *     the owner's {@code onRevoke}), where it must only clean up.</li>
 * </ul>
 * Judge answers by voluntary input ({@code abilities.kit.Inputs}) and add
 * {@link AbilityContext#reactionSlackTicks(org.bukkit.entity.Player)} to every reaction window.
 *
 * <p>Prompts are cleaned up for you: an owner's prompts end when its ability is revoked, a target's when it leaves
 * play, everything at the match end. A prompt is a template: the same instance can be submitted to several targets
 * (each gets its own {@link PromptHandle}).
 *
 * <pre>{@code
 * Prompt riddle = Prompt.builder(Text.mm("<gold>수수께끼!</gold> 같은 그림의 타일을 밟으세요"), params().ticks("answer_seconds"))
 *         .priority(Prompt.NORMAL)
 *         .overhead(Text.mm("<gold><b>☀ ?</b></gold>"))
 *         .maxWait(20)
 *         .onShown(handle -> raiseTiles(handle.target()))           // the answer timer starts now
 *         .onEnd((handle, reason) -> {
 *             if (reason == PromptEndReason.EXPIRED) {
 *                 judge(handle.target());                           // the target saw the whole window
 *             }
 *             removeTiles(handle.target());
 *         })
 *         .build();
 * PromptHandle handle = ctx().prompt(player(), enemy, riddle);
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

    private final Component actionBar;
    private final @Nullable Component overhead;
    private final @Nullable Component title;
    private final @Nullable Component subtitle;
    private final int priority;
    private final long durationTicks;
    private final long maxWaitTicks;
    private final @Nullable Consumer<PromptHandle> onShown;
    private final @Nullable BiConsumer<PromptHandle, PromptEndReason> onEnd;

    private Prompt(Builder builder) {
        this.actionBar = builder.actionBar;
        this.overhead = builder.overhead;
        this.title = builder.title;
        this.subtitle = builder.subtitle;
        this.priority = builder.priority;
        this.durationTicks = builder.durationTicks;
        this.maxWaitTicks = builder.maxWaitTicks;
        this.onShown = builder.onShown;
        this.onEnd = builder.onEnd;
    }

    /**
     * Starts a prompt.
     *
     * @param actionBar     the line on the target's action bar while shown (the primary display)
     * @param durationTicks on-screen ticks before it ends as {@link PromptEndReason#EXPIRED} (the answer window), > 0
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

    /** On-screen ticks before the prompt expires. */
    public long durationTicks() {
        return durationTicks;
    }

    /** How long the prompt may wait in the queue (in total) before it is dropped unseen or interrupted. */
    public long maxWaitTicks() {
        return maxWaitTicks;
    }

    /** Runs once, the first time the prompt is shown; null for none. */
    public @Nullable Consumer<PromptHandle> onShown() {
        return onShown;
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
        private @Nullable Consumer<PromptHandle> onShown;
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
         * How long the prompt may wait for the screen, in total (default {@link Prompt#DEFAULT_MAX_WAIT_TICKS}). 0 means
         * "now or never": a prompt that cannot be shown in the tick it is submitted ends {@link PromptEndReason#UNSEEN}.
         */
        public Builder maxWait(long ticks) {
            if (ticks < 0) {
                throw new IllegalArgumentException("maxWait < 0: " + ticks);
            }
            this.maxWaitTicks = ticks;
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
