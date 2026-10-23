package io.github.sx2xoox.mcpl.game.superpower.abilities;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import org.bukkit.Material;

/** 오디세우스 — TODO: not implemented yet. */
public final class OdysseusAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("odysseus", "오디세우스", Material.SADDLE)
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
