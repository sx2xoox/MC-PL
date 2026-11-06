package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptHandle;
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
 * A rule shown through the prompt channel is judged while its prompt is on screen, from the current showing on (a
 * higher-priority prompt may push it off the screen for a while; what the target did meanwhile never counts):
 * <pre>{@code
 * // every tick while the rule lasts:
 * if (rule.isShown() && Inputs.breaksWalking(ctx(), enemy,
 *         Inputs.judgeFrom(ctx(), rule, params().ticks("grace_seconds")), player())) {
 *     punish(enemy);                          // cap punishments per enemy in the ability
 * }
 * }</pre>
 * A rule judged once at its end (a breath held until the exhale) asks the same {@link #breaks} once: input from the
 * judged stretch counts even if the player was pushed afterwards.
 *
 * <h2>Exemptions</h2>
 * A key never counts for a moment in which the player
 * <ul>
 *     <li>was under hard crowd control applied through the context that locks its movement
 *     ({@link AbilityContext#isMovementLocked}: stuns, roots, prisons; walking keys and sprint) or its jumping
 *     ({@link AbilityContext#isJumpLocked}: the jump key),</li>
 *     <li>was moved by something else, from the push until {@link #DISPLACED_TICKS} later (a push, launch, pull,
 *     knockback or teleport, {@link AbilityContext#wasDisplaced}; its own ability's dashes and blinks do not count),
 *     or rode a vehicle;</li>
 * </ul>
 * exactly for those moments ({@link AbilityContext#usedVoluntarily}): a key tapped and released during a stun never
 * counts afterwards, a key still held when the stun ends counts from then on, and a key used before a push still counts
 * after it. The sneak key and attacks are always voluntary and never exempt. Pass the rule owner as
 * {@code ruleOwner} so that the owner's own lock does not exempt the target (a talisman that leaves only input-driven
 * hops); movement an ability makes <em>for</em> the target's input goes through
 * {@link AbilityContext#setVoluntaryVelocity} so it does not exempt the target either.
 *
 * <p>Client movement itself cannot be refused. To forbid a direction, cut {@code MOVEMENT_SPEED} by 100% through
 * {@link AbilityContext#addModifier} while the key is held (about one ping of slip is unavoidable); such a lock of the
 * rule owner's own exempts nothing when {@code ruleOwner} is passed.
 *
 * <p>Tests: {@code TestPlayer#sendInput} (a client's new input, with the event), {@code TestPlayer#swingArm} and
 * {@code TestPlayer#attackAttempt}; a test teleport ({@code Scenario#place}) displaces the player, so let
 * {@link #DISPLACED_TICKS} pass before judging.
 */
public final class Inputs {

    /** A player moved by something else is exempt from input rules for this long (0.5 s). */
    public static final long DISPLACED_TICKS = AbilityContext.INPUT_EXEMPT_TICKS;

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
     * or after that tick (a quick tap between two ticks counts). Raw: no exemptions; rules use {@link #breaks}.
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

    /**
     * True if {@code player} swung an arm at or after {@code sinceTick}: any swing, so also opening a chest, placing a
     * block, using an item or the off hand. An attack rule uses {@link #attackedSince}.
     */
    public static boolean swungSince(AbilityContext ctx, Player player, long sinceTick) {
        return ctx.lastSwingTick(player) >= sinceTick;
    }

    /**
     * True if {@code player} attacked at or after {@code sinceTick}: a melee attack it tried on any entity, or a
     * projectile it shot or threw with an item ({@link AbilityContext#lastAttackTick}).
     */
    public static boolean attackedSince(AbilityContext ctx, Player player, long sinceTick) {
        return ctx.lastAttackTick(player) >= sinceTick;
    }

    /**
     * The first tick a rule may judge {@code player}'s input: {@code fromTick} (when the rule was shown) plus the
     * rule's own {@code graceTicks} plus {@link AbilityContext#reactionSlackTicks} for its ping. Input that ended
     * before it is never judged; a key still held at that tick counts (the grace is the time to let go).
     */
    public static long judgeFrom(AbilityContext ctx, Player player, long fromTick, long graceTicks) {
        return fromTick + Math.max(0, graceTicks) + ctx.reactionSlackTicks(player);
    }

    /**
     * {@link #judgeFrom(AbilityContext, Player, long, long)} for a rule on the prompt channel: from the current showing
     * of {@code rule} ({@link PromptHandle#showingSinceTick()}, so again after every resume) plus the grace and the
     * target's reaction slack. {@link Long#MAX_VALUE} (never) while the rule is not on the target's screen.
     */
    public static long judgeFrom(AbilityContext ctx, PromptHandle rule, long graceTicks) {
        long since = rule.showingSinceTick();
        return since < 0 ? Long.MAX_VALUE : judgeFrom(ctx, rule.target(), since, graceTicks);
    }

    /** True if something else moved {@code player} within {@link #DISPLACED_TICKS}, or it rides a vehicle. */
    public static boolean isDisplaced(AbilityContext ctx, Player player) {
        return player.isInsideVehicle() || ctx.wasDisplaced(player, DISPLACED_TICKS);
    }

    /**
     * True if {@code player} is not judged for {@code key} right now (see the class documentation): locked by hard crowd
     * control (not counting {@code ruleOwner}'s own) or displaced. Never for {@link InputKey#SNEAK}. {@link #breaks}
     * already applies the exemptions for every moment of the judged stretch; this is "right now" only (e.g. to skip a
     * warning).
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
     * {@link #judgeFrom}) and the key was down at some moment since then at which nothing exempted the player
     * ({@link AbilityContext#usedVoluntarily}: keys used under hard crowd control or while being pushed never count,
     * before or after; a key still held after such a moment does).
     */
    public static boolean breaks(AbilityContext ctx, Player player, InputKey key, long judgeFromTick,
                                 @Nullable Player ruleOwner) {
        return ctx.currentTick() >= judgeFromTick && ctx.usedVoluntarily(player, key, judgeFromTick, ruleOwner);
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

    /** True if {@code player} attacked since {@code judgeFromTick}, once the judging started (never exempt). */
    public static boolean breaksAttack(AbilityContext ctx, Player player, long judgeFromTick) {
        return ctx.currentTick() >= judgeFromTick && attackedSince(ctx, player, judgeFromTick);
    }

    /**
     * True if {@code player} swung an arm since {@code judgeFromTick}, once the judging started (never exempt). Any
     * swing counts (chests, blocks, items, the off hand): for "don't attack" use {@link #breaksAttack}.
     */
    public static boolean breaksSwing(AbilityContext ctx, Player player, long judgeFromTick) {
        return ctx.currentTick() >= judgeFromTick && swungSince(ctx, player, judgeFromTick);
    }
}
