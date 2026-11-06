package io.github.sx2xoox.mcpl.game.superpower.support;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/**
 * A neutral ability for the targets of a scenario: no passive, skills that do nothing, and an ultimate that is not
 * standalone (so the Gambler never borrows it). Giving it to enemies keeps their own abilities from interfering
 * with the ability under test (no dodges, reflections, damage reduction, revives...).
 */
public final class DummyAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("dummy", "허수아비", Material.HAY_BLOCK)
            .color(TextColor.color(0xC8B070))
            .passive("허수아비", "아무 일도 하지 않습니다.")
            .active("허수아비", "아무 일도 하지 않습니다.", 30)
            .ultimate("허수아비", "아무 일도 하지 않습니다.")
            .ultimateNotStandalone()
            .build();

    /**
     * A second, identical neutral ability. A team of two needs two different abilities (one per player); with only
     * {@link #INFO} in a pool the match fills the gap with a random real ability, whose passive then interferes.
     */
    public static final AbilityInfo TWIN_INFO = AbilityInfo.builder("dummy_twin", "허수아비 쌍둥이", Material.HAY_BLOCK)
            .color(TextColor.color(0xC8B070))
            .passive("허수아비", "아무 일도 하지 않습니다.")
            .active("허수아비", "아무 일도 하지 않습니다.", 30)
            .ultimate("허수아비", "아무 일도 하지 않습니다.")
            .ultimateNotStandalone()
            .build();

    private final AbilityInfo info;

    public DummyAbility() {
        this(INFO);
    }

    public DummyAbility(AbilityInfo info) {
        this.info = info;
    }

    @Override
    public AbilityInfo info() {
        return info;
    }

    @Override
    public ActivationResult onActive() {
        return ActivationResult.success();
    }

    @Override
    public ActivationResult onUltimate() {
        return ActivationResult.success();
    }
}
