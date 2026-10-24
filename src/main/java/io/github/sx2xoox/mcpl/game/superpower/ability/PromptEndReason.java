package io.github.sx2xoox.mcpl.game.superpower.ability;

/**
 * Why a {@link Prompt} ended, handed to its {@link Prompt.Builder#onEnd onEnd} callback. Only {@link #EXPIRED} means
 * the target saw the whole prompt; judge a final answer there (or, for a rule judged continuously, only while
 * {@link PromptHandle#isShown()}). Never punish a target for a prompt that ended for any other reason.
 */
public enum PromptEndReason {

    /** It was on the target's screen for its whole duration: the answer window ran out normally. */
    EXPIRED,

    /**
     * It waited in the target's queue longer than its {@link Prompt#maxWaitTicks()} and was <b>never shown</b>: the
     * target never saw it, so it must have no consequence at all.
     */
    UNSEEN,

    /**
     * It was shown, then pushed back by a higher-priority prompt and could not come back within its
     * {@link Prompt#maxWaitTicks()}: the target saw only part of it.
     */
    INTERRUPTED,

    /** The ability ended it with {@link PromptHandle#cancel()} (answered early, the skill stopped, ...). */
    CANCELLED,

    /** The target left play: eliminated, died, quit or became a spectator. */
    TARGET_LEFT,

    /** The owner's ability was revoked (the owner was eliminated, quit, or its ability was replaced). */
    OWNER_REVOKED,

    /** The match ended. */
    MATCH_END;

    /** True only for {@link #EXPIRED}: the target saw the prompt for its full duration. */
    public boolean fullySeen() {
        return this == EXPIRED;
    }
}
