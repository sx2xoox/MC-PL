package io.github.sx2xoox.mcpl.game.superpower.support;

import java.util.EnumSet;
import java.util.Set;

import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import org.bukkit.Input;

/**
 * A client's movement input for tests ({@link TestPlayer#sendInput}, {@link TestPlayer#setInput}):
 * {@code TestInput.of(InputKey.FORWARD, InputKey.JUMP)}, {@link #NONE}, or a copy with one key more or less.
 */
public record TestInput(boolean isForward, boolean isBackward, boolean isLeft, boolean isRight, boolean isJump,
                        boolean isSneak, boolean isSprint) implements Input {

    /** No key held. */
    public static final TestInput NONE = new TestInput(false, false, false, false, false, false, false);

    /** Exactly the given keys held. */
    public static TestInput of(InputKey... keys) {
        Set<InputKey> held = keys.length == 0 ? EnumSet.noneOf(InputKey.class) : EnumSet.of(keys[0], keys);
        return new TestInput(held.contains(InputKey.FORWARD), held.contains(InputKey.BACKWARD),
                held.contains(InputKey.LEFT), held.contains(InputKey.RIGHT), held.contains(InputKey.JUMP),
                held.contains(InputKey.SNEAK), held.contains(InputKey.SPRINT));
    }

    /** The same keys as {@code input} (any implementation). */
    public static TestInput copyOf(Input input) {
        return new TestInput(input.isForward(), input.isBackward(), input.isLeft(), input.isRight(), input.isJump(),
                input.isSneak(), input.isSprint());
    }

    /** These keys plus {@code key}. */
    public TestInput with(InputKey key) {
        EnumSet<InputKey> held = InputKey.held(this);
        held.add(key);
        return of(held.toArray(InputKey[]::new));
    }

    /** These keys without {@code key}. */
    public TestInput without(InputKey key) {
        EnumSet<InputKey> held = InputKey.held(this);
        held.remove(key);
        return of(held.toArray(InputKey[]::new));
    }
}
