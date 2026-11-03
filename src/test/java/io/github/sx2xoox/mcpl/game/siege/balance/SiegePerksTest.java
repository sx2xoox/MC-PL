package io.github.sx2xoox.mcpl.game.siege.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.siege.SiegeSettings;
import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The level perks (caps for all 50 pool abilities), the balance numbers, the modifiers and the settings. */
class SiegePerksTest {

    private MatchHarness h;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void everyPoolAbilityHasThreePerksWithinTheCaps() {
        AbilityRegistry registry = new AbilityRegistry();
        BuiltinAbilities.registerAll(registry);
        List<String> problems = new ArrayList<>();
        for (String id : SiegeSettings.DEFAULT_POOL) {
            AbilityInfo info = registry.get(id).info();
            problems.addAll(SiegePerks.violations(info));
            for (SiegePerks.Perk perk : SiegePerks.perks(id)) {
                if (perk.kind() == SiegePerks.Kind.CONTROL || SiegePerks.isControlKey(id, perk.key())) {
                    assertTrue(perk.delta() <= SiegePerks.MAX_CONTROL_SECONDS, perk + ": crowd control cap");
                }
                // Nothing becomes permanent: durations stay bounded, cooldowns only get shorter.
                if (perk.kind() == SiegePerks.Kind.DURATION) {
                    assertTrue(perk.delta() <= 2, perk + ": duration cap");
                }
                if (perk.key().equals(AbilityInfo.COOLDOWN)) {
                    assertTrue(perk.delta() < 0, perk + ": cooldown perks only shorten");
                }
            }
        }
        assertEquals(List.of(), problems);
        assertEquals(SiegeSettings.DEFAULT_POOL.size(), SiegePerks.abilities().size(), "one table row per pool ability");
    }

    @Test
    void perksAddUpByLevelAndAreCappedInCode() {
        assertEquals(6, SiegePerks.apply("pyro", 5, "ult_radius", 6), 1.0E-9);
        assertEquals(7, SiegePerks.apply("pyro", 6, "ult_radius", 6), 1.0E-9);
        assertEquals(4, SiegePerks.apply("pyro", 9, "shots", 3), 1.0E-9);
        assertEquals(3, SiegePerks.apply("pyro", 9, "fireball_damage", 3), 1.0E-9, "other params untouched");
        assertEquals(1.0, SiegePerks.apply("unknown", 9, "x", 1.0), 1.0E-9);
        // A table edit beyond the caps is held to them.
        SiegePerks.Perk stun = new SiegePerks.Perk("frost", 6, "freeze_duration", 3, SiegePerks.Kind.DURATION, "x");
        assertEquals(SiegePerks.MAX_CONTROL_SECONDS, SiegePerks.capped(stun, 2), 1.0E-9);
        SiegePerks.Perk damage = new SiegePerks.Perk("frost", 9, "spear_damage", 10, SiegePerks.Kind.DAMAGE, "x");
        assertEquals(1, SiegePerks.capped(damage, 4), 1.0E-9, "damage +25% at most");
        SiegePerks.Perk execute = new SiegePerks.Perk("reaper", 9, "execute_threshold", 10, SiegePerks.Kind.PERCENT,
                "x");
        assertEquals(0, SiegePerks.capped(execute, 30), 1.0E-9, "kill thresholds never move");
    }

    @Test
    void levelAwareParamsReadThroughTheAdjuster() {
        AbilityRegistry registry = new AbilityRegistry();
        BuiltinAbilities.registerAll(registry);
        AbilityParams base = AbilityParams.defaults(registry.get("pyro").info());
        int[] level = {1};
        AbilityParams owned = base.withAdjuster((key, value) -> SiegePerks.apply("pyro", level[0], key, value));
        assertEquals(6, owned.num("ult_radius"), 1.0E-9);
        level[0] = 6;
        assertEquals(7, owned.num("ult_radius"), 1.0E-9, "a level-up shows at the next read");
        assertEquals(6, owned.values().get("ult_radius"), 1.0E-9, "the configured values stay");
        assertEquals(6, base.num("ult_radius"), 1.0E-9, "the shared params are untouched");
    }

    @Test
    void describeNamesThePerkOfALevel() {
        assertEquals("6레벨: 불바다 범위 +1블록", SiegePerks.describe("pyro", 6));
        assertNull(SiegePerks.describe("pyro", 5));
        AbilityRegistry registry = new AbilityRegistry();
        BuiltinAbilities.registerAll(registry);
        assertEquals("화염술사 3레벨: 화염구 범위 +0.4블록", SiegePerks.describe(registry.get("pyro").info(), 3));
        assertEquals("9레벨: 테이저 쿨타임 −1초", SiegePerks.describe("police", 9));
    }

    @Test
    void damageMultiplierCombinesGlobalCorrectionLevelAndModifiers() {
        SiegeModifiers modifiers = new SiegeModifiers();
        SiegeBalance balance = new SiegeBalance(SiegeBalanceSettings.defaults(), modifiers);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        double g = SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE;
        assertEquals(g, balance.damageMultiplier("dummy", 1, a, b, false), 1.0E-9);
        assertEquals(g * 1.6, balance.damageMultiplier("dummy", 9, a, b, true), 1.0E-9);
        SiegeBalanceSettings.Correction archer = balance.settings().correction("archer");
        assertTrue(archer.ultimate() < 1, "the bundled corrections are loaded");
        assertEquals(g * archer.ultimate(), balance.damageMultiplier("archer", 1, a, b, true), 1.0E-9);
        assertEquals(g * archer.skill(), balance.damageMultiplier("archer", 1, a, b, false), 1.0E-9);

        modifiers.addPercent(a, "shop:orb", SiegeModifiers.Stat.SKILL_DAMAGE, 10);
        modifiers.addPercent(a, "shop:orb", SiegeModifiers.Stat.SKILL_DAMAGE, 10);
        modifiers.set(a, "buff:dragon", SiegeModifiers.Stat.SKILL_DAMAGE, SiegeModifiers.Op.MULTIPLY, 1.1);
        modifiers.addPercent(b, "shop:armor", SiegeModifiers.Stat.SKILL_DAMAGE_TAKEN, -18);
        assertEquals(g * 1.1 * 1.1 * 0.82, balance.damageMultiplier("dummy", 1, a, b, false), 1.0E-9,
                "a key never stacks with itself");
        assertEquals(g * 1.1 * 1.1, balance.damageMultiplier("dummy", 1, a, null, false), 1.0E-9,
                "damage taken only for players");
        modifiers.removeAll("buff:");
        assertEquals(1.1, modifiers.factor(a, SiegeModifiers.Stat.SKILL_DAMAGE), 1.0E-9);
        modifiers.addPercent(a, "a", SiegeModifiers.Stat.COOLDOWN, -50);
        modifiers.addPercent(a, "b", SiegeModifiers.Stat.COOLDOWN, -50);
        assertEquals(SiegeModifiers.Stat.COOLDOWN.floor(), balance.cooldownMultiplier(a), 1.0E-9, "floored");
    }

    @Test
    void settingsReadOverridesAndCanTurnTheBalanceOff() {
        MemoryConfiguration config = new MemoryConfiguration();
        SiegeBalanceSettings.declareDefaults(config);
        assertEquals(SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE, config.getDouble(SiegeBalanceSettings.ABILITY_DAMAGE));
        assertNotNull(config.getConfigurationSection(SiegeBalanceSettings.ABILITIES + ".archer"),
                "the generated corrections are declared");
        config.set(SiegeBalanceSettings.ABILITY_DAMAGE, 0.5);
        config.set(SiegeBalanceSettings.ABILITIES + ".pyro.ultimate", 0.4);
        SiegeBalanceSettings read = SiegeBalanceSettings.read(config);
        assertEquals(0.5, read.abilityDamage(), 1.0E-9);
        assertEquals(0.4, read.correction("pyro").ultimate(), 1.0E-9);
        assertEquals(SiegeBalanceSettings.defaults().correction("pyro").skill(), read.correction("pyro").skill(),
                1.0E-9);

        config.set(SiegeBalanceSettings.ENABLED, false);
        config.set(SiegeBalanceSettings.PERKS, false);
        SiegeBalance off = new SiegeBalance(SiegeBalanceSettings.read(config), new SiegeModifiers());
        assertEquals(1, off.damageMultiplier("pyro", 9, UUID.randomUUID(), null, true), 1.0E-9);
        assertEquals(6, off.param("pyro", 9, "ult_radius", 6), 1.0E-9);
    }
}
