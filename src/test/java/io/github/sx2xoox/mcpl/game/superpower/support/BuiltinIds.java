package io.github.sx2xoox.mcpl.game.superpower.support;

import java.util.List;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;

/** The ids of the built-in abilities, for tests that sweep every ability. */
public final class BuiltinIds {

    private static List<String> implemented;

    private BuiltinIds() {
    }

    /**
     * Every built-in ability that is implemented (sorted). Abilities still being written are stubs whose skills are
     * named "TODO"; they join the sweeps as soon as they are implemented.
     */
    public static synchronized List<String> implemented() {
        if (implemented == null) {
            // Ability classes build block data while they initialize, which needs a (mock) server.
            MatchHarness h = MatchHarness.flat();
            try {
                AbilityRegistry registry = new AbilityRegistry();
                BuiltinAbilities.registerAll(registry);
                implemented = registry.all().stream().map(AbilityRegistry.Entry::info)
                        .filter(info -> !isStub(info)).map(AbilityInfo::id).sorted().toList();
            } finally {
                h.close();
            }
        }
        return implemented;
    }

    public static boolean isStub(AbilityInfo info) {
        return info.passive().name().equals("TODO");
    }
}
