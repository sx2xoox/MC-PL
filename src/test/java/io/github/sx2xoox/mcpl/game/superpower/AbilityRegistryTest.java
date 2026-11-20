package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashSet;
import java.util.Set;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class AbilityRegistryTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void everyBuiltinAbilityIsWellFormed() {
        AbilityRegistry registry = new AbilityRegistry();
        BuiltinAbilities.registerAll(registry);
        assertEquals(151, registry.all().size());

        Set<String> names = new HashSet<>();
        for (AbilityRegistry.Entry entry : registry.all()) {
            AbilityInfo info = entry.info();
            Ability ability = registry.create(info.id());
            assertSame(info, ability.info());
            assertFalse(names.contains(info.name()), "duplicate name " + info.name());
            names.add(info.name());
            assertFalse(info.icon().isAir(), info.id() + " icon");
            AbilityParams params = AbilityParams.defaults(info);
            for (AbilityInfo.Skill skill : new AbilityInfo.Skill[] {info.passive(), info.active(), info.ultimate()}) {
                String rendered = AbilityInfo.render(skill.description(), params);
                assertFalse(rendered.matches(".*\\{[a-z0-9_]+}.*"), info.id() + " has unknown placeholder: " + rendered);
            }
        }
    }

    @Test
    void renderFormatsNumbers() {
        AbilityInfo info = AbilityInfo.builder("test", "테스트", org.bukkit.Material.STONE)
                .passive("p", "p").active("a", "{damage} 피해, {radius}블록", 5).ultimate("u", "u")
                .param("damage", 4).param("radius", 2.5).build();
        assertEquals("4 피해, 2.5블록", AbilityInfo.render(info.active().description(), AbilityParams.defaults(info)));
    }
}
