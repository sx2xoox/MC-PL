package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.List;

import io.github.sx2xoox.mcpl.core.game.DeathOutcome;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Another mode running the 초능력전쟁 abilities with rules of its own (e.g. 공성전): a {@link SuperpowerMatch} created
 * with a host keeps everything about abilities (binding, input, cooldown, gauge, silence, the ability context and its
 * cleanup) but leaves the flow to the host. A hosted match:
 *
 * <ul>
 *     <li>has no selection, reveal, prep countdown, border shrink, time limit, loot, sky, HUD or win check of its own;
 *     it reads {@code games.superpower} like practice does ({@link io.github.sx2xoox.mcpl.game.superpower.SuperpowerSettings#forPractice()});</li>
 *     <li>waits in {@link MatchPhase#PREP} (everyone invulnerable, abilities not usable) until the host assigns
 *     abilities ({@link SuperpowerMatch#hostAssign}) and starts a fight ({@link SuperpowerMatch#hostBeginFight});
 *     {@link SuperpowerMatch#hostEndFight} revokes them again (half time, between rounds);</li>
 *     <li>asks the host what a death means ({@link #onDeath}) and rebinds the ability on respawn (gauge kept);</li>
 *     <li>lets the host lock parts of the kit (e.g. 공성전 levels: {@link #activeLocked}, {@link #ultimateLocked},
 *     {@link #canChargeGauge}); passives always run;</li>
 *     <li>treats the host's {@link #extraTargets() extra targets} as enemies where the host says so (e.g. the towers of
 *     the defending team are enemies of the attackers only), so {@code AbilityContext#damage}, {@code enemiesNear}
 *     and {@code findTarget} reach them.</li>
 * </ul>
 *
 * The host forwards the hub's {@link io.github.sx2xoox.mcpl.core.game.GameInstance} callbacks to the match.
 */
public interface MatchHost {

    /** What happens to a participant who died (the match already revoked its ability and ran {@code onKill}). */
    DeathOutcome onDeath(Player victim, @Nullable Player killer);

    /** A participant came back after a {@link DeathOutcome.Respawn}; its ability is bound again if a fight runs. */
    default void onRespawn(Player player) {
    }

    /** A participant was eliminated (quit). The hosted match does not check for a winner. */
    default void onEliminated(Player player, @Nullable Player killer) {
    }

    /**
     * The ability item was used (right-click) while no fight runs, e.g. a reroll with sneak + right-click.
     *
     * @return true if the host handled it (otherwise the player hears "not yet")
     */
    default boolean onUseOutsideFight(Player player) {
        return false;
    }

    /**
     * True if the ability item is the player's weapon (공성전): the kit's first sword becomes the ability item in hotbar
     * slot 1 ({@link io.github.sx2xoox.mcpl.game.superpower.ui.AbilityItems#createWeapon}); it hits like that sword,
     * right-click casts the active skill and F the ultimate. False: the classic item in slot 9.
     */
    default boolean abilityWeapon() {
        return false;
    }

    /**
     * The basic attacks (평타) of the {@link #abilityWeapon() ability weapon}, or null (the default): a left-click
     * with the weapon then fires the ability's own basic attack ({@link BasicAttack}: a swing, combo, shot, projectile,
     * ...) instead of a vanilla sword hit, whose damage is cancelled. Read once when the match starts; ignored unless
     * {@link #abilityWeapon()} is true and the table is {@link BasicAttackSettings#enabled() enabled}.
     */
    default @Nullable BasicAttackSettings basicAttacks() {
        return null;
    }

    /**
     * Factor on every basic attack hit {@code attacker} deals to {@code target} (e.g. 공성전 levels); 1.0 by default.
     * Attack damage bonuses (items, buffs) are added by the match on top ({@link BasicAttackSettings#bonusReference()}).
     */
    default double basicAttackMultiplier(Player attacker, LivingEntity target) {
        return 1.0;
    }

    /** Live non-player targets abilities may hit (enemy candidates for targeting), e.g. tower hitboxes. */
    default List<LivingEntity> extraTargets() {
        return List.of();
    }

    /**
     * Whether {@code entity} is one of the host's targets (then {@link #isExtraEnemy} alone decides whether it is an
     * enemy of an ability owner).
     */
    default boolean isExtraTarget(Entity entity) {
        return false;
    }

    /** Whether the host's target {@code entity} is an enemy of {@code owner}. */
    default boolean isExtraEnemy(Player owner, Entity entity) {
        return false;
    }

    /**
     * Why {@code player} may not use the active skill right now (shown in the action bar), or null if it may. Checked
     * after the fight and before silence and cooldown.
     */
    default @Nullable Component activeLocked(Player player) {
        return null;
    }

    /** Why {@code player} may not use the ultimate right now, or null if it may. Checked before the gauge. */
    default @Nullable Component ultimateLocked(Player player) {
        return null;
    }

    /**
     * Whether the ultimate gauge of {@code player} fills by playing (time, damage). Explicit grants (items, commands)
     * are not asked.
     */
    default boolean canChargeGauge(Player player) {
        return true;
    }

    // ------------------------------------------------------------------ balance (공성전 levels, items, buffs)

    /**
     * The in-match level of {@code player} that its ability may read ({@code AbilityContext#level}): 1 and up in a
     * mode with levels (공성전 1…9), 0 (the default) when the host has none.
     */
    default int abilityLevel(Player player) {
        return 0;
    }

    /**
     * Factor on every hit of ability damage {@code attacker} deals to {@code target} through the ability context
     * ({@code AbilityContext#damage}, kit {@code Combat}) and by its summons' melee hits. 1.0 (the default) leaves the
     * numbers of {@code abilities.yml} as they are. Read once per hit, so it may change at any time (levels, items).
     */
    default double abilityDamageMultiplier(Player attacker, LivingEntity target) {
        return 1.0;
    }

    /**
     * Like {@link #abilityDamageMultiplier(Player, LivingEntity)}, told whether the hit belongs to the attacker's
     * ultimate (dealt by its ultimate code or an entity the ultimate created), so a host can weigh the active and
     * passive skills and the ultimate apart. The default ignores {@code ultimate}.
     */
    default double abilityDamageMultiplier(Player attacker, LivingEntity target, boolean ultimate) {
        return abilityDamageMultiplier(attacker, target);
    }

    /**
     * The value {@code owner}'s ability reads for its declared param {@code key} (configured: {@code value}), e.g. a
     * level perk adding to a radius. Asked on every read; the default returns {@code value}. The active cooldown that
     * the match starts is read through it too (key {@code cooldown}).
     */
    default double abilityParam(Player owner, String abilityId, String key, double value) {
        return value;
    }

    /** Factor on the active cooldown {@code player} gets after a successful cast (items, buffs); 1.0 by default. */
    default double cooldownMultiplier(Player player) {
        return 1.0;
    }
}
