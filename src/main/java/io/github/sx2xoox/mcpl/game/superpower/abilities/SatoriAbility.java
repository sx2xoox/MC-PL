package io.github.sx2xoox.mcpl.game.superpower.abilities;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import org.bukkit.Material;

/** 사토리 — TODO: not implemented yet. */
public final class SatoriAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("satori", "사토리", Material.OBSERVER)
            .passive("TODO", "TODO")
            .active("TODO", "TODO", 10)
            .ultimate("TODO", "TODO")
            .ultimateNotStandalone()
            .build();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public ActivationResult onActive() {
        return ActivationResult.fail("TODO");
    }

    @Override
    public ActivationResult onUltimate() {
        return ActivationResult.fail("TODO");
    }
}
