package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttacks;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import org.bukkit.Particle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every 공성전 pool ability has its own basic attack (평타), and the table in docs/siege-v2.md §8 is generated from
 * {@link BasicAttacks}: this test fails while it is stale; {@code ./gradlew updateBasicAttackDocs} rewrites it.
 */
class BasicAttackDocsTest {

    private static final Path DOCS = Path.of("docs", "siege-v2.md");
    private static final String START = "<!-- basic-attacks:start (자동 생성: ./gradlew updateBasicAttackDocs, "
            + "BasicAttacks 를 고친 뒤 다시 만드세요) -->";
    private static final String END = "<!-- basic-attacks:end -->";

    private MatchHarness h;

    @BeforeEach
    void setUp() {
        // Sounds and abilities need a (mock) server.
        h = MatchHarness.flat();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void everyPoolAbilityHasItsOwnBasicAttack() {
        AbilityRegistry registry = registry();
        assertEquals(SiegeSettings.DEFAULT_POOL, List.copyOf(BasicAttacks.defaults().keySet()),
                "one basic attack per pool ability, in pool order");
        Set<String> names = new HashSet<>();
        for (BasicAttack attack : BasicAttacks.defaults().values()) {
            assertNotNull(registry.get(attack.id()), attack.id() + " is a built-in ability");
            assertTrue(names.add(attack.name()), "basic attack names are unique: " + attack.name());
            assertTrue(attack.damage() > 0 && attack.range() > 0, attack.id());
            Class<?> data = attack.palette().particle().getDataType();
            assertTrue(data == Void.class || data == Particle.DustOptions.class,
                    attack.id() + ": its particle needs no data");
            assertNotEquals(attack.palette().sound(), null, attack.id());
            assertNotNull(attack.fx(), attack.id() + " has a display look");
            assertTrue(!attack.fx().scene().isBlank(), attack.id() + " describes its look");
        }
    }

    @Test
    void everyJobLooksDifferent() {
        Set<String> looks = new HashSet<>();
        for (BasicAttack attack : BasicAttacks.defaults().values()) {
            BasicAttack.Fx fx = attack.fx();
            String look = attack.shape() + " " + fx.glow() + " " + fx.core() + " " + fx.weapon() + " " + fx.weight()
                    + " " + fx.signature() + " " + fx.size() + " " + attack.palette().item() + " "
                    + attack.palette().particle();
            assertTrue(looks.add(look), attack.id() + " looks like another job: " + look);
        }
    }

    @Test
    void combosAndCriticalHitsKeepTheDamagePerSecondWithinTenPercent() {
        BasicAttackSettings.Feel feel = BasicAttackSettings.Feel.DEFAULT;
        double average = feel.averageMultiplier();
        assertTrue(average >= 1.0 && average <= 1.10,
                "finishers and critical hits add at most 10% to an unbroken combo, was " + average);
        for (BasicAttack attack : BasicAttacks.defaults().values()) {
            assertTrue(attack.damagePerSecond() * average <= 9.6,
                    attack.id() + ": even with combos at most an iron sword's damage per second");
        }
    }

    @Test
    void basicAttacksAreWeakerAtRangeAndSteadyUpClose() {
        for (BasicAttack attack : BasicAttacks.defaults().values()) {
            double dps = attack.damagePerSecond();
            assertTrue(dps <= 9.0, attack.id() + ": at most a vanilla sword's damage per second, was " + dps);
            assertTrue(dps >= 3.0, attack.id() + ": still a steady main damage, was " + dps);
            if (attack.ranged()) {
                assertTrue(dps <= 5.5, attack.id() + ": ranged attacks hit softer, was " + dps);
            }
            assertTrue(attack.cooldown() >= 10, attack.id() + ": not spammy (≥ 0.5 s)");
            if (attack.effect() == BasicAttack.Effect.SLOW) {
                assertTrue(attack.effectPower() <= 0.2 && attack.effectSeconds() <= 1.0, attack.id() + ": small slow");
            }
        }
    }

    @Test
    void basicAttackDocsAreUpToDate() throws IOException {
        String current = Files.readString(DOCS, StandardCharsets.UTF_8);
        int start = current.indexOf(START);
        int end = current.indexOf(END);
        assertTrue(start >= 0 && end > start, DOCS + " has the generated basic attack block");
        String generated = current.substring(0, start + START.length()) + "\n" + render(registry())
                + current.substring(end);
        if (Boolean.getBoolean("mcpl.updateDocs")) {
            Files.writeString(DOCS, generated, StandardCharsets.UTF_8);
            return;
        }
        assertEquals(generated, current, DOCS + " is out of date: run ./gradlew updateBasicAttackDocs");
    }

    private static AbilityRegistry registry() {
        AbilityRegistry registry = new AbilityRegistry();
        BuiltinAbilities.registerAll(registry);
        return registry;
    }

    static String render(AbilityRegistry registry) {
        StringBuilder out = new StringBuilder();
        BasicAttackSettings.Feel feel = BasicAttackSettings.Feel.DEFAULT;
        out.append("\n콤보: 연속 ").append(feel.comboLength()).append("번째 평타가 **피니시**(피해 ×")
                .append(n(feel.finisherDamage())).append(", 밀침 ").append(n(feel.finisherPush()))
                .append(", 더 큰 연출), 준비된 뒤 ").append(n(feel.comboResetSeconds()))
                .append("초 안에 안 치면 처음부터. 치명타: 평타마다 ").append(n(feel.critChance() * 100))
                .append("% 확률로 피해 ×").append(n(feel.critDamage()))
                .append(" (금색 불꽃, 큰 숫자). 쉬지 않고 이어 칠 때 평균 피해 ×")
                .append(n(feel.averageMultiplier())).append(" (아래 '초당'은 기본값 기준).\n");
        out.append("\n| 능력 | 평타 | 형태 | 피해 | 간격 | 사거리 | 초당 | 부가효과 | 연출 | 설명 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (BasicAttack attack : BasicAttacks.defaults().values()) {
            AbilityRegistry.Entry entry = registry.get(attack.id());
            String ability = entry == null ? attack.id() : entry.info().name();
            out.append("| ").append(ability).append(" `").append(attack.id()).append("` | **")
                    .append(attack.name()).append("** | ").append(attack.shape().korean()).append(" | ")
                    .append(damage(attack)).append(" | ").append(n(attack.cooldownSeconds())).append("초 | ")
                    .append(n(attack.range())).append(" | ").append(n(round1(attack.damagePerSecond())))
                    .append(" | ").append(effect(attack)).append(" | ").append(attack.fx().scene()).append(" | ")
                    .append(attack.description()).append(" |\n");
        }
        out.append("\n").append(BasicAttacks.defaults().size()).append("종 한 줄 요약:\n\n");
        List<String> lines = new ArrayList<>();
        for (BasicAttack attack : BasicAttacks.defaults().values()) {
            AbilityRegistry.Entry entry = registry.get(attack.id());
            String ability = entry == null ? attack.id() : entry.info().name();
            lines.add("- " + ability + ": " + attack.name() + " — " + attack.description());
        }
        out.append(String.join("\n", lines)).append("\n\n");
        return out.toString();
    }

    private static String damage(BasicAttack attack) {
        String each = n(attack.damage());
        return switch (attack.shape()) {
            case COMBO, SHOT, CONE -> attack.hits() > 1 ? each + " × " + attack.hits() : each;
            case PROJECTILE -> attack.hits() > 1 ? each + " × " + attack.hits() + "발" : each;
            case CHAIN -> each + " → 튈 때마다 ×" + n(attack.falloff()) + " (최대 " + attack.hits() + "명)";
            case LOB, STRIKE -> each + " (반경 " + n(attack.radius()) + ")";
            default -> each;
        };
    }

    private static String effect(BasicAttack attack) {
        double p = attack.effectPower();
        String s = n(attack.effectSeconds());
        return switch (attack.effect()) {
            case NONE -> "–";
            case SLOW -> "감속 " + n(p * 100) + "% " + s + "초";
            case DOT -> "지속 피해 " + n(p) + " / " + s + "초";
            case KNOCKBACK -> "밀쳐내기";
            case PULL -> "끌어당기기";
            case LIFESTEAL -> "흡혈 " + n(p * 100) + "%";
            case HEAL_ALLIES -> "아군 회복 " + n(p);
            case LUNGE -> "돌진";
            case WEAKEN -> "약화(공격력 −" + n(p) + ") " + s + "초";
        };
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static String n(double value) {
        return new BigDecimal(Double.toString(Math.round(value * 100) / 100.0)).stripTrailingZeros()
                .toPlainString();
    }
}
