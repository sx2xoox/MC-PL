package io.github.sx2xoox.mcpl.game.siege.balance;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The numbers 공성전 gives its hosted ability match ({@code MatchHost}): the ability damage factor of a hit, the params
 * an owner reads at its level ({@link SiegePerks}) and the cooldown factor. Plain numbers, no Bukkit; the match looks
 * up levels and ability ids and asks this.
 */
public final class SiegeBalance {

    private final SiegeBalanceSettings settings;
    private final SiegeModifiers modifiers;

    public SiegeBalance(SiegeBalanceSettings settings, SiegeModifiers modifiers) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.modifiers = Objects.requireNonNull(modifiers, "modifiers");
    }

    public SiegeBalanceSettings settings() {
        return settings;
    }

    public SiegeModifiers modifiers() {
        return modifiers;
    }

    /** {@code 1 + damage-per-level × (level − 1)}: 1.0 at level 1, 1.6 at level 9 by default. */
    public double levelFactor(int level) {
        return settings.enabled() ? 1 + settings.damagePerLevel() * Math.max(0, level - 1) : 1.0;
    }

    /**
     * The factor on one hit of ability damage.
     *
     * @param abilityId    the attacker's ability (null: no correction)
     * @param level        the attacker's level (1 …)
     * @param attacker     the attacker ({@link SiegeModifiers.Stat#SKILL_DAMAGE})
     * @param targetPlayer the target if it is a player ({@link SiegeModifiers.Stat#SKILL_DAMAGE_TAKEN}), else null
     * @param ultimate     whether the hit belongs to the attacker's ultimate
     */
    public double damageMultiplier(@Nullable String abilityId, int level, UUID attacker, @Nullable UUID targetPlayer,
                                   boolean ultimate) {
        double factor = 1.0;
        if (settings.enabled()) {
            factor = settings.abilityDamage() * levelFactor(level);
            if (abilityId != null) {
                factor *= settings.correction(abilityId).factor(ultimate);
            }
        }
        factor *= modifiers.factor(attacker, SiegeModifiers.Stat.SKILL_DAMAGE);
        if (targetPlayer != null) {
            factor *= modifiers.factor(targetPlayer, SiegeModifiers.Stat.SKILL_DAMAGE_TAKEN);
        }
        return factor;
    }

    /**
     * The factor on one hit of {@code abilityId}'s ability damage on a building, minion or monster (they take no global
     * factor or level scaling, only this correction).
     */
    public double objectiveMultiplier(@Nullable String abilityId) {
        return settings.enabled() && abilityId != null ? settings.correction(abilityId).objective() : 1.0;
    }

    /**
     * The value an owner at {@code level} reads for a param: the 공성전 tuning ({@link SiegeTuning}), then its level
     * perks, when they are on (a perk adds to the tuned value).
     */
    public double param(String abilityId, int level, String key, double value) {
        double base = settings.enabled() && settings.tuning() ? SiegeTuning.apply(abilityId, key, value) : value;
        return settings.perks() ? SiegePerks.apply(abilityId, level, key, base) : base;
    }

    /** The factor on {@code player}'s active cooldowns (items, buffs). */
    public double cooldownMultiplier(UUID player) {
        return modifiers.factor(player, SiegeModifiers.Stat.COOLDOWN);
    }
}
