package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import org.bukkit.Input;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * What the players of one match did with their keys and what moved them (see the voluntary-input part of
 * {@link io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext}): the last press and release of every input
 * key, the last arm swing, and the last time something else displaced them. Fed by {@link MatchListener}; all ticks
 * are match ticks ({@link MatchContext#currentTick()}).
 */
final class InputTracker {

    /** A velocity at least this fast (blocks per tick) moves a player; stopping or nudging one does not. */
    static final double DISPLACING_SPEED = 0.1;
    /** A voluntary velocity is matched with the velocity event it causes within this many ticks. */
    static final long VOLUNTARY_MATCH_TICKS = 2;
    private static final double VELOCITY_EPSILON = 1.0E-6;

    private final Map<UUID, State> states = new HashMap<>();

    private static final class State {
        final EnumSet<InputKey> held = EnumSet.noneOf(InputKey.class);
        /** False until the first input event: {@link #held} is then seeded from the player's current input. */
        boolean inputSeen;
        final long[] pressed = unset();
        final long[] released = unset();
        long swing = -1;
        long displaced = -1;
        @Nullable Vector voluntary;
        long voluntaryTick;

        private static long[] unset() {
            long[] ticks = new long[InputKey.values().length];
            Arrays.fill(ticks, -1);
            return ticks;
        }
    }

    /**
     * New input from {@code player}'s client. The first time a player is seen, the keys it held before are taken from
     * {@link Player#getCurrentInput()} (the server updates it only after the input event), so a key held since before
     * the match does not count as a fresh press.
     */
    void input(Player player, Input input, long now) {
        State state = state(player.getUniqueId());
        if (!state.inputSeen) {
            state.inputSeen = true;
            state.held.addAll(InputKey.held(player.getCurrentInput()));
        }
        for (InputKey key : InputKey.values()) {
            boolean down = key.isHeld(input);
            if (down && state.held.add(key)) {
                state.pressed[key.ordinal()] = now;
            } else if (!down && state.held.remove(key)) {
                state.released[key.ordinal()] = now;
            }
        }
    }

    void swing(UUID player, long now) {
        state(player).swing = now;
    }

    void displaced(UUID player, long now) {
        state(player).displaced = now;
    }

    /** The server sends {@code player} a new velocity: displacement unless it is the voluntary one announced. */
    void velocity(UUID player, Vector velocity, long now) {
        State state = state(player);
        Vector expected = state.voluntary;
        if (expected != null && now - state.voluntaryTick <= VOLUNTARY_MATCH_TICKS && same(expected, velocity)) {
            state.voluntary = null;
            return;
        }
        if (velocity.lengthSquared() >= DISPLACING_SPEED * DISPLACING_SPEED) {
            state.displaced = now;
        }
    }

    /** The next velocity event of {@code player} with exactly this velocity is voluntary movement. */
    void expectVoluntary(UUID player, Vector velocity, long now) {
        State state = state(player);
        state.voluntary = velocity.clone();
        state.voluntaryTick = now;
    }

    long lastPress(UUID player, InputKey key) {
        State state = states.get(player);
        return state == null ? -1 : state.pressed[key.ordinal()];
    }

    long lastRelease(UUID player, InputKey key) {
        State state = states.get(player);
        return state == null ? -1 : state.released[key.ordinal()];
    }

    long lastSwing(UUID player) {
        State state = states.get(player);
        return state == null ? -1 : state.swing;
    }

    long lastDisplaced(UUID player) {
        State state = states.get(player);
        return state == null ? -1 : state.displaced;
    }

    void clear() {
        states.clear();
    }

    private State state(UUID player) {
        return states.computeIfAbsent(player, id -> new State());
    }

    private static boolean same(Vector a, Vector b) {
        return Math.abs(a.getX() - b.getX()) < VELOCITY_EPSILON && Math.abs(a.getY() - b.getY()) < VELOCITY_EPSILON
                && Math.abs(a.getZ() - b.getZ()) < VELOCITY_EPSILON;
    }
}
