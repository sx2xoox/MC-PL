package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/abilities.md} is generated from the ability definitions ({@link AbilityInfo}: names, descriptions with
 * their default numbers, cooldowns, difficulty), so it can never drift from the code. This test fails when the file
 * is stale; {@code ./gradlew updateAbilityDocs} rewrites it.
 */
class AbilityDocsTest {

    private static final Path DOCS = Path.of("docs", "abilities.md");

    /** Document sections, in order. Every built-in ability must be in exactly one. */
    private static final Map<String, List<String>> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("원소", List.of("pyro", "thunder", "frost", "aero"));
        CATEGORIES.put("기동 · 암살", List.of("blink", "shadow", "grapple", "chrono"));
        CATEGORIES.put("근접 · 탱커", List.of("berserker", "titan", "guardian", "vampire"));
        CATEGORIES.put("제어", List.of("gravity", "earth", "venom", "mirror"));
        CATEGORIES.put("원거리 · 소환 · 지원", List.of("archer", "bomber", "summoner", "healer"));
        CATEGORIES.put("특수", List.of("pixie", "phoenix", "illusionist", "gambler"));
        CATEGORIES.put("자연", List.of("hydro", "magma", "druid", "sand"));
        CATEGORIES.put("빛 · 소리 · 정신", List.of("light", "sonic", "psychic", "hexer"));
        CATEGORIES.put("무기", List.of("blade", "lancer", "ninja", "duelist"));
        CATEGORIES.put("기술", List.of("sniper", "engineer", "alchemist", "magnet"));
        CATEGORIES.put("변신 · 생물", List.of("werewolf", "spider", "slime", "dragon"));
        CATEGORIES.put("주술 · 트릭", List.of("necromancer", "bard", "thief"));
        CATEGORIES.put("격투 · 공간 · 건축", List.of("monk", "fighter", "dimension", "builder"));
        CATEGORIES.put("직업", List.of("beekeeper", "angler", "farmer", "chef"));
        CATEGORIES.put("무대 · 스포츠", List.of("magician", "clown", "soccer", "batter"));
        CATEGORIES.put("어둠 · 영혼", List.of("demon", "reaper", "ghost", "phantom"));
        CATEGORIES.put("마인크래프트 몹", List.of("creeper", "shulker", "goat", "turtle"));
        CATEGORIES.put("기계 · 우주", List.of("mech", "astronomer", "hacker"));
        CATEGORIES.put("버섯 · 수정 · 예술", List.of("mycologist", "crystal", "painter"));
        CATEGORIES.put("사냥 · 모험", List.of("hunter", "pirate", "speedster"));
        CATEGORIES.put("한국 설화", List.of("dokkaebi", "gumiho", "sansin", "shaman"));
        CATEGORIES.put("신화", List.of("medusa", "cupid", "minotaur", "hydra"));
        CATEGORIES.put("직업 2", List.of("police", "miner", "blacksmith", "gunslinger"));
        CATEGORIES.put("야생 동물", List.of("octopus", "bear", "eagle", "hedgehog"));
        CATEGORIES.put("정신 · 기묘", List.of("hypnotist", "puppeteer", "alien", "zombie"));
        CATEGORIES.put("겨울 · 축제", List.of("snowman", "santa", "dj", "skater"));
        CATEGORIES.put("소년만화", List.of("rubber", "kiwarrior", "boredhero", "chuuni", "oath"));
        CATEGORIES.put("탐식 · 탐욕", List.of("bulgasari", "agwi", "midas", "centipede", "taotie"));
        CATEGORIES.put("마음 · 속임수", List.of("satori", "mimic", "odysseus", "eris", "baku"));
        CATEGORIES.put("죽음 · 예언", List.of("cassandra", "banshee", "cursenote", "charon", "yeomra"));
        CATEGORIES.put("하늘 · 땅", List.of("blockfall", "qilin", "atlas", "poltergeist", "jormungandr"));
    }

    private MatchHarness h;

    @BeforeEach
    void setUp() {
        // Ability classes build block data while they initialize, which needs a (mock) server.
        h = MatchHarness.flat();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void everyAbilityIsInExactlyOneSection() {
        Set<String> listed = new TreeSet<>();
        List<String> duplicates = new ArrayList<>();
        for (List<String> ids : CATEGORIES.values()) {
            for (String id : ids) {
                if (!listed.add(id)) {
                    duplicates.add(id);
                }
            }
        }
        assertEquals(List.of(), duplicates, "abilities listed twice");
        assertEquals(ids(registry()), listed, "every built-in ability has a docs section");
    }

    @Test
    void abilityDocsAreUpToDate() throws IOException {
        String generated = render(registry());
        if (Boolean.getBoolean("mcpl.updateDocs")) {
            Files.writeString(DOCS, generated, StandardCharsets.UTF_8);
            return;
        }
        String current = Files.exists(DOCS) ? Files.readString(DOCS, StandardCharsets.UTF_8) : "";
        assertEquals(generated, current, DOCS + " is out of date: run ./gradlew updateAbilityDocs");
    }

    private static Set<String> ids(AbilityRegistry registry) {
        Set<String> ids = new TreeSet<>();
        for (AbilityRegistry.Entry entry : registry.all()) {
            ids.add(entry.info().id());
        }
        return ids;
    }

    private static AbilityRegistry registry() {
        AbilityRegistry registry = new AbilityRegistry();
        BuiltinAbilities.registerAll(registry);
        return registry;
    }

    static String render(AbilityRegistry registry) {
        StringBuilder out = new StringBuilder();
        int total = registry.all().size();
        out.append("# 초능력전쟁 능력 목록 (").append(total).append("종)\n\n");
        out.append("<!-- 자동 생성 문서입니다. 직접 고치지 말고 능력 코드(AbilityInfo)를 고친 뒤 ")
                .append("`./gradlew updateAbilityDocs` 로 다시 만드세요. -->\n\n");
        out.append("수치는 기본값이며 `abilities.yml`에서 조정할 수 있습니다. 피해 단위는 체력 포인트(20 = 하트 10칸)이고, ")
                .append("능력 피해는 기본적으로 마법 피해(방어구 무시)입니다. 기본 킷은 철 갑옷 + 철검 기준입니다.\n")
                .append("난이도는 ★(쉬움) ~ ★★★(어려움)입니다. 액티브는 능력 아이템 우클릭, 궁극기는 게이지 100%에서 F 키입니다.\n\n");

        out.append("| 분류 | 능력 |\n|---|---|\n");
        for (Map.Entry<String, List<String>> section : CATEGORIES.entrySet()) {
            List<String> names = new ArrayList<>();
            for (String id : section.getValue()) {
                names.add(info(registry, id).name());
            }
            out.append("| ").append(section.getKey()).append(" | ").append(String.join(", ", names)).append(" |\n");
        }

        for (Map.Entry<String, List<String>> section : CATEGORIES.entrySet()) {
            out.append("\n## ").append(section.getKey()).append("\n\n");
            out.append("| 능력 | 패시브 | 액티브 (쿨타임) | 궁극기 |\n|---|---|---|---|\n");
            for (String id : section.getValue()) {
                AbilityInfo info = info(registry, id);
                AbilityParams params = AbilityParams.defaults(info);
                out.append("| **").append(cell(info.name())).append("** `").append(info.id()).append("`<br>")
                        .append(stars(info.difficulty()))
                        .append(" | ").append(skill(info.passive(), params, null))
                        .append(" | ").append(skill(info.active(), params, params.cooldownSeconds()))
                        .append(" | ").append(skill(info.ultimate(), params, null))
                        .append(" |\n");
            }
        }
        return out.toString();
    }

    private static AbilityInfo info(AbilityRegistry registry, String id) {
        AbilityRegistry.Entry entry = registry.get(id);
        if (entry == null) {
            throw new IllegalStateException("unknown ability in the docs sections: " + id);
        }
        return entry.info();
    }

    private static String skill(AbilityInfo.Skill skill, AbilityParams params, Double cooldownSeconds) {
        StringBuilder text = new StringBuilder("**").append(cell(skill.name())).append("**");
        if (cooldownSeconds != null) {
            text.append(" (").append(AbilityInfo.formatNumber(cooldownSeconds)).append("초)");
        }
        return text.append(": ").append(cell(AbilityInfo.render(skill.description(), params))).toString();
    }

    private static String stars(int difficulty) {
        return "★".repeat(difficulty) + "☆".repeat(3 - difficulty);
    }

    /** Text safe inside a Markdown table cell. */
    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
