package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import org.bukkit.Input;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Voluntary input for rules that judge what a player <em>does</em>: "don't move", "don't jump", "don't walk backward",
 * "don't attack", "stand still when I inhale". Such rules read the keys the client presses
 * ({@link Player#getCurrentInput()} and the press history the match records from every
 * {@link org.bukkit.event.player.PlayerInputEvent}), never position or velocity: abilities push players around all the
 * time, and a pushed player did nothing wrong.
 *
 * <h2>The usual rule</h2>
 * <pre>{@code
 * long from = Inputs.judgeFrom(ctx(), enemy, handle.shownAtTick(), params().ticks("grace_seconds"));
 * // every tick while the rule is up:
 * if (Inputs.breaksWalking(ctx(), enemy, from, player())) {
 *     punish(enemy);                          // cap punishments per enemy in the ability
 * }
 * }</pre>
 *
 * <h2>Exemptions</h2>
 * A player is not judged for movement keys while
 * <ul>
 *     <li>hard crowd control applied through the context locks its movement ({@link AbilityContext#isMovementLocked}:
 *     stuns, roots, prisons; {@link AbilityContext#isJumpLocked} for the jump key),</li>
 *     <li>it was moved by something else within {@link #DISPLACED_TICKS} (a push, launch, pull, knockback or teleport,
 *     {@link AbilityContext#wasDisplaced}), or it rides a vehicle;</li>
 * </ul>
 * and keys used during such a moment do not count afterwards. The sneak key and arm swings are always voluntary and
 * never exempt. Pass the rule owner as {@code ruleOwner} so that the owner's own lock does not exempt the target (a
 * talisman that leaves only input-driven hops); movement an ability makes <em>for</em> the target's input goes through
 * {@link AbilityContext#setVoluntaryVelocity} so it does not exempt the target either.
 *
 * <p>Client movement itself cannot be refused. To forbid a direction, cut {@code MOVEMENT_SPEED} by 100% through
 * {@link AbilityContext#addModifier} while the key is held (about one ping of slip is unavoidable).
 *
 * <p>Tests: {@code TestPlayer#sendInput} (a client's new input, with the event) and {@code TestPlayer#swingArm}; a
 * test teleport ({@code Scenario#place}) displaces the player, so let {@link #DISPLACED_TICKS} pass before judging.
 */
public final class Inputs {

    /** A player moved by something else is exempt from movement rules for this long (0.5 s). */
    public static final long DISPLACED_TICKS = 10;

    private Inputs() {
    }

    /** The keys {@code player}'s client holds right now. */
    public static Input current(Player player) {
        return player.getCurrentInput();
    }

    /** True if {@code player} holds {@code key} right now. */
    public static boolean isHeld(Player player, InputKey key) {
        return key.isHeld(player.getCurrentInput());
    }

    /** True if {@code player} holds any walking key (forward, backward, left, right) right now. */
    public static boolean isWalking(Player player) {
        Input input = player.getCurrentInput();
        return input.isForward() || input.isBackward() || input.isLeft() || input.isRight();
    }

    /**
     * True if {@code key} was down at any moment from {@code sinceTick} until now: held now, or pressed or released at
     * or after that tick (a quick tap between two ticks counts).
     */
    public static boolean usedSince(AbilityContext ctx, Player player, InputKey key, long sinceTick) {
        return key.isHeld(player.getCurrentInput()) || ctx.lastPressTick(player, key) >= sinceTick
                || ctx.lastReleaseTick(player, key) >= sinceTick;
    }

    /** {@link #usedSince} for any walking key. */
    public static boolean walkedSince(AbilityContext ctx, Player player, long sinceTick) {
        for (InputKey key : InputKey.WALKING) {
            if (usedSince(ctx, player, key, sinceTick)) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code player} swung an arm (an attack or a click) at or after {@code sinceTick}. */
    public static boolean swungSince(AbilityContext ctx, Player player, long sinceTick) {
        return ctx.lastSwingTick(player) >= sinceTick;
    }

    /**
     * The first tick a rule may judge {@code player}'s input: {@code fromTick} (when the rule or prompt was shown) plus
     * the rule's own {@code graceTicks} plus {@link AbilityContext#reactionSlackTicks} for its ping. Input that ended
     * before it is never judged; a key still held at that tick counts (the grace is the time to let go).
     */
    public static long judgeFrom(AbilityContext ctx, Player player, long fromTick, long graceTicks) {
        return fromTick + Math.max(0, graceTicks) + ctx.reactionSlackTicks(player);
    }

    /** True if something else moved {@code player} within {@link #DISPLACED_TICKS}, or it rides a vehicle. */
    public static boolean isDisplaced(AbilityContext ctx, Player player) {
        return player.isInsideVehicle() || ctx.wasDisplaced(player, DISPLACED_TICKS);
    }

    /**
     * True if {@code player} is not judged for {@code key} right now (see the class documentation): locked by hard crowd
     * control (not counting {@code ruleOwner}'s own) or displaced. Never for {@link InputKey#SNEAK}.
     */
    public static boolean isExempt(AbilityContext ctx, Player player, InputKey key, @Nullable Player ruleOwner) {
        return switch (key) {
            case SNEAK -> false;
            case JUMP -> ctx.isJumpLocked(player, ruleOwner) || isDisplaced(ctx, player);
            default -> ctx.isMovementLocked(player, ruleOwner) || isDisplaced(ctx, player);
        };
    }

    /**
     * True if {@code player} breaks a "don't use {@code key}" rule: the judging has started ({@code judgeFromTick}, see
     * {@link #judgeFrom}), it is not {@link #isExempt exempt}, and the key was used since the judging started and since
     * its last displacement window ended (keys pressed while being pushed do not count; a key still held after the
     * window does).
     */
    public static boolean breaks(AbilityContext ctx, Player player, InputKey key, long judgeFromTick,
                                 @Nullable Player ruleOwner) {
        if (ctx.currentTick() < judgeFromTick || isExempt(ctx, player, key, ruleOwner)) {
            return false;
        }
        long since = judgeFromTick;
        if (key != InputKey.SNEAK) {
            long displaced = ctx.lastDisplacedTick(player);
            if (displaced >= 0) {
                since = Math.max(since, displaced + DISPLACED_TICKS + 1);
            }
        }
        return usedSince(ctx, player, key, since);
    }

    /** {@link #breaks} for any walking key (a "don't move" rule; add {@link InputKey#JUMP} if jumping counts). */
    public static boolean breaksWalking(AbilityContext ctx, Player player, long judgeFromTick,
                                        @Nullable Player ruleOwner) {
        for (InputKey key : InputKey.WALKING) {
            if (breaks(ctx, player, key, judgeFromTick, ruleOwner)) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code player} swung an arm since {@code judgeFromTick}, once the judging started (never exempt). */
    public static boolean breaksSwing(AbilityContext ctx, Player player, long judgeFromTick) {
        return ctx.currentTick() >= judgeFromTick && swungSince(ctx, player, judgeFromTick);
    }
}
