package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import org.bukkit.Input;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * What the players of one match did with their keys and what moved them (see the voluntary-input part of
 * {@link io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext}): every key's recent down intervals (press to
 * release), the last arm swing and attack, and the stretches of time in which input rules must not judge a player
 * (moved by something else, riding, locked by hard crowd control). Fed by {@link MatchListener} and
 * {@link MatchContext}; all ticks are match ticks ({@link MatchContext#currentTick()}).
 *
 * <p>{@link #usedVoluntarily} answers "was this key down at some moment since that tick while nothing exempted the
 * player", so a key used under a stun or while being pushed never counts afterwards, and a key used before a later push
 * still counts.
 */
final class InputTracker {

    /** A velocity at least this fast (blocks per tick) moves a player; stopping or nudging one does not. */
    static final double DISPLACING_SPEED = 0.1;
    /** A voluntary velocity is matched with the velocity event it causes within this many ticks. */
    static final long VOLUNTARY_MATCH_TICKS = 2;
    /**
     * A velocity event matches the announced voluntary velocity within this share of its length (the server may damp
     * a player's motion a little between setting and sending it).
     */
    static final double VOLUNTARY_TOLERANCE = 0.05;
    /** Down intervals kept per key, and exemption windows kept per player; older ones are forgotten. */
    static final int HISTORY = 48;
    private static final double VELOCITY_EPSILON = 1.0E-6;
    /** The end of an interval that has not ended yet. */
    private static final long OPEN = Long.MAX_VALUE;
    /** The start of an interval that began before anything was recorded (a key held since before the match). */
    private static final long BEFORE = Long.MIN_VALUE / 4;

    private final Map<UUID, State> states = new HashMap<>();

    /** A stretch of match ticks, both ends inclusive; {@code to == OPEN} while it lasts. */
    private static final class Span {
        final long from;
        long to;

        Span(long from, long to) {
            this.from = from;
            this.to = to;
        }
    }

    /** A lock of movement or jumping by hard crowd control, and the owner whose ability applied it. */
    private static final class Lock {
        final Object key;
        final UUID by;
        final boolean jump;
        final Span span;

        Lock(Object key, UUID by, boolean jump, long from) {
            this.key = key;
            this.by = by;
            this.jump = jump;
            this.span = new Span(from, OPEN);
        }
    }

    private static final class State {
        final EnumSet<InputKey> held = EnumSet.noneOf(InputKey.class);
        /** False until the first input event: {@link #held} is then seeded from the player's current input. */
        boolean inputSeen;
        final long[] pressed = unset();
        final long[] released = unset();
        /** Per key, its recent down intervals, oldest first; the last one is open while the key is held. */
        final List<ArrayDeque<Span>> downs = new ArrayList<>();
        long swing = -1;
        long attack = -1;
        long displaced = -1;
        /** Windows in which something else moved the player or it rode a vehicle (merged, oldest first). */
        final ArrayDeque<Span> moved = new ArrayDeque<>();
        final ArrayDeque<Lock> locks = new ArrayDeque<>();
        @Nullable Vector voluntary;
        long voluntaryTick;

        State() {
            for (int i = 0; i < InputKey.values().length; i++) {
                downs.add(new ArrayDeque<>());
            }
        }

        private static long[] unset() {
            long[] ticks = new long[InputKey.values().length];
            Arrays.fill(ticks, -1);
            return ticks;
        }

        ArrayDeque<Span> downs(InputKey key) {
            return downs.get(key.ordinal());
        }
    }

    // =================================================================== recording

    /**
     * New input from {@code player}'s client. The first time a player is seen, the keys it held before are taken from
     * {@link Player#getCurrentInput()} (the server updates it only after the input event), so a key held since before
     * the match does not count as a fresh press (but it does count as held).
     */
    void input(Player player, Input input, long now) {
        State state = state(player.getUniqueId());
        if (!state.inputSeen) {
            state.inputSeen = true;
            for (InputKey key : InputKey.held(player.getCurrentInput())) {
                state.held.add(key);
                add(state.downs(key), new Span(BEFORE, OPEN));
            }
        }
        for (InputKey key : InputKey.values()) {
            boolean down = key.isHeld(input);
            if (down && state.held.add(key)) {
                state.pressed[key.ordinal()] = now;
                add(state.downs(key), new Span(now, OPEN));
            } else if (!down && state.held.remove(key)) {
                state.released[key.ordinal()] = now;
                Span last = state.downs(key).peekLast();
                if (last != null && last.to == OPEN) {
                    last.to = now;
                }
            }
        }
    }

    void swing(UUID player, long now) {
        state(player).swing = now;
    }

    void attack(UUID player, long now) {
        state(player).attack = now;
    }

    /** Something else moved {@code player}: input rules leave it alone for {@link #exemptTicks()} ticks. */
    void displaced(UUID player, long now) {
        State state = state(player);
        state.displaced = now;
        exempt(state, now);
    }

    /** {@code player} rides a vehicle this tick (its walking keys steer the vehicle, not itself). */
    void riding(UUID player, long now) {
        exempt(state(player), now);
    }

    private static void exempt(State state, long now) {
        long until = now + exemptTicks();
        Span last = state.moved.peekLast();
        if (last != null && now <= last.to + 1) {
            last.to = Math.max(last.to, until);
            return;
        }
        add(state.moved, new Span(now, until));
    }

    /** The server sends {@code player} a new velocity: displacement unless it is the voluntary one announced. */
    void velocity(UUID player, Vector velocity, long now) {
        State state = state(player);
        Vector expected = state.voluntary;
        if (expected != null && now - state.voluntaryTick <= VOLUNTARY_MATCH_TICKS && matches(expected, velocity)) {
            state.voluntary = null;
            return;
        }
        if (velocity.lengthSquared() >= DISPLACING_SPEED * DISPLACING_SPEED) {
            displaced(player, now);
        }
    }

    /** The next velocity event of {@code player} with (about) this velocity is voluntary movement. */
    void expectVoluntary(UUID player, Vector velocity, long now) {
        State state = state(player);
        state.voluntary = velocity.clone();
        state.voluntaryTick = now;
    }

    /** Hard crowd control locking {@code player}'s movement (or jumping) began; {@code key} names it for the end. */
    void lockStarted(UUID player, Object key, UUID by, boolean jump, long now) {
        add(state(player).locks, new Lock(key, by, jump, now));
    }

    /** The lock {@code key} of {@code player} ended. */
    void lockEnded(UUID player, Object key, long now) {
        State state = states.get(player);
        if (state == null) {
            return;
        }
        for (Lock lock : state.locks) {
            if (lock.key.equals(key) && lock.span.to == OPEN) {
                lock.span.to = Math.max(lock.span.from, now);
            }
        }
    }

    // =================================================================== queries

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

    long lastAttack(UUID player) {
        State state = states.get(player);
        return state == null ? -1 : state.attack;
    }

    long lastDisplaced(UUID player) {
        State state = states.get(player);
        return state == null ? -1 : state.displaced;
    }

    /**
     * True if {@code key} was down at some moment from {@code sinceTick} to {@code now} at which nothing exempted
     * {@code player}: no displacement window or ride (any key but sneak), no lock of its movement (walking keys and
     * sprint) or of its jumping (jump) applied by someone other than {@code ruleOwner}.
     */
    boolean usedVoluntarily(Player player, InputKey key, long sinceTick, long now, @Nullable UUID ruleOwner) {
        if (sinceTick > now) {
            return false;
        }
        State state = states.get(player.getUniqueId());
        List<Span> downs = new ArrayList<>(4);
        if (state == null || !state.inputSeen) {
            // No input event yet: the client's current input is all there is.
            if (key.isHeld(player.getCurrentInput())) {
                downs.add(new Span(sinceTick, now));
            }
        } else {
            for (Span down : state.downs(key)) {
                long from = Math.max(down.from, sinceTick);
                long to = Math.min(down.to, now);
                if (from <= to) {
                    downs.add(new Span(from, to));
                }
            }
        }
        if (downs.isEmpty()) {
            return false;
        }
        List<Span> exempt = state == null || key == InputKey.SNEAK ? List.of() : exemptions(state, key, ruleOwner);
        for (Span down : downs) {
            if (!covered(down.from, down.to, exempt)) {
                return true;
            }
        }
        return false;
    }

    private static List<Span> exemptions(State state, InputKey key, @Nullable UUID ruleOwner) {
        List<Span> spans = new ArrayList<>(state.moved);
        boolean jump = key == InputKey.JUMP;
        for (Lock lock : state.locks) {
            if (lock.jump == jump && !lock.by.equals(ruleOwner)) {
                spans.add(lock.span);
            }
        }
        spans.sort(Comparator.comparingLong(span -> span.from));
        return spans;
    }

    /** True if every tick of {@code from..to} lies in one of {@code spans} (sorted by start). */
    private static boolean covered(long from, long to, List<Span> spans) {
        long cursor = from;
        for (Span span : spans) {
            if (span.to < cursor) {
                continue;
            }
            if (span.from > cursor) {
                return false;
            }
            if (span.to >= to) {
                return true;
            }
            cursor = span.to + 1;
        }
        return cursor > to;
    }

    void clear() {
        states.clear();
    }

    private State state(UUID player) {
        return states.computeIfAbsent(player, id -> new State());
    }

    /** Appends {@code item}, forgetting the oldest entries beyond {@link #HISTORY} (an open lock is never dropped). */
    private static <T> void add(ArrayDeque<T> history, T item) {
        history.addLast(item);
        Iterator<T> oldest = history.iterator();
        while (history.size() > HISTORY && oldest.hasNext()) {
            T candidate = oldest.next();
            if (!(candidate instanceof Lock lock) || lock.span.to != OPEN) {
                oldest.remove();
            }
        }
    }

    private static long exemptTicks() {
        return AbilityContext.INPUT_EXEMPT_TICKS;
    }

    private static boolean matches(Vector expected, Vector actual) {
        double tolerance = Math.max(VELOCITY_EPSILON, expected.length() * VOLUNTARY_TOLERANCE);
        return expected.distanceSquared(actual) <= tolerance * tolerance;
    }
}
