package io.github.sx2xoox.mcpl.game.superpower.ability;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.bukkit.Input;

/**
 * One key of a player's movement input ({@link org.bukkit.entity.Player#getCurrentInput()}, the
 * {@link org.bukkit.event.player.PlayerInputEvent}). What a client <em>presses</em>, not how it moves: being pushed,
 * launched or teleported never changes it.
 */
public enum InputKey {

    FORWARD,
    BACKWARD,
    LEFT,
    RIGHT,
    JUMP,
    SNEAK,
    SPRINT;

    /** The walking keys (forward, backward, left, right). */
    public static final Set<InputKey> WALKING = Collections.unmodifiableSet(EnumSet.of(FORWARD, BACKWARD, LEFT, RIGHT));

    /** True if {@code input} holds this key. */
    public boolean isHeld(Input input) {
        return switch (this) {
            case FORWARD -> input.isForward();
            case BACKWARD -> input.isBackward();
            case LEFT -> input.isLeft();
            case RIGHT -> input.isRight();
            case JUMP -> input.isJump();
            case SNEAK -> input.isSneak();
            case SPRINT -> input.isSprint();
        };
    }

    /** True for the walking keys (forward, backward, left, right). */
    public boolean isWalking() {
        return WALKING.contains(this);
    }

    /** The keys {@code input} holds. */
    public static EnumSet<InputKey> held(Input input) {
        EnumSet<InputKey> held = EnumSet.noneOf(InputKey.class);
        for (InputKey key : values()) {
            if (key.isHeld(input)) {
                held.add(key);
            }
        }
        return held;
    }
}
