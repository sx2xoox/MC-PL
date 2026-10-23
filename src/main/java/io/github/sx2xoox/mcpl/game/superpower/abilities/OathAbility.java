package io.github.sx2xoox.mcpl.game.superpower.abilities;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import org.bukkit.Material;

/** 맹세의 능력자 — TODO: not implemented yet. */
public final class OathAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("oath", "맹세의 능력자", Material.ENCHANTED_BOOK)
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
