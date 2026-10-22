package io.github.sx2xoox.mcpl.game.superpower.abilities;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import org.bukkit.Material;

/** 스피드스터 — TODO: not implemented yet. */
public final class SpeedsterAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("speedster", "스피드스터", Material.SUGAR)
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
