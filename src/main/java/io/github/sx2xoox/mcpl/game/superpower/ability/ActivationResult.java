package io.github.sx2xoox.mcpl.game.superpower.ability;

import org.jspecify.annotations.Nullable;

/**
 * Outcome of an active or ultimate activation.
 *
 * <p>On {@link #success()} the framework starts the active cooldown (or consumes the ultimate gauge) and plays
 * the activation feedback. On {@link #fail(String)} nothing is consumed and the reason is shown to the player in
 * the action bar, e.g. {@code fail("시야에 대상이 없습니다")}.
 *
 * @param succeeded whether the skill was used
 * @param reason  MiniMessage text explaining a failure, null on success
 */
public record ActivationResult(boolean succeeded, @Nullable String reason) {

    private static final ActivationResult SUCCESS = new ActivationResult(true, null);

    public static ActivationResult success() {
        return SUCCESS;
    }

    public static ActivationResult fail(String reason) {
        return new ActivationResult(false, reason);
    }
}
