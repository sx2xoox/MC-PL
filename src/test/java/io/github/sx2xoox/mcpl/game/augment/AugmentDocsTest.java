package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentRegistry;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import io.github.sx2xoox.mcpl.game.augment.augments.BuiltinAugments;
import io.github.sx2xoox.mcpl.game.augment.bonus.BonusCondition;
import io.github.sx2xoox.mcpl.util.Text;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/augments.md} is generated from the augment definitions ({@link AugmentInfo}) and the bonus conditions
 * ({@link BonusCondition}) with their default numbers, so it never drifts from the code. This test fails when the file
 * is stale; {@code ./gradlew updateAugmentDocs} rewrites it.
 */
class AugmentDocsTest {

    private static final Path DOCS = Path.of("docs", "augments.md");

    @Test
    void augmentDocsAreUpToDate() throws IOException {
        String generated = render(registry());
        if (Boolean.getBoolean("mcpl.updateDocs")) {
            Files.writeString(DOCS, generated, StandardCharsets.UTF_8);
            return;
        }
        String current = Files.exists(DOCS) ? Files.readString(DOCS, StandardCharsets.UTF_8) : "";
        assertEquals(generated, current, DOCS + " is out of date: run ./gradlew updateAugmentDocs");
    }

    @Test
    void everyDescriptionPlaceholderIsADeclaredParam() {
        for (AugmentRegistry.Entry entry : registry().all()) {
            AugmentInfo info = entry.info();
            String rendered = info.defaults().render(info.description());
            assertTrue(!rendered.contains("{"), info.id() + " has an undeclared placeholder: " + rendered);
        }
        for (BonusCondition condition : BonusCondition.values()) {
            String rendered = condition.defaults().render(condition.description());
            assertTrue(!rendered.contains("{"), condition.id() + " has an undeclared placeholder: " + rendered);
        }
    }

    @Test
    void idsAreUniqueAndEveryTierHasAugments() {
        Set<String> ids = new HashSet<>();
        for (AugmentRegistry.Entry entry : registry().all()) {
            assertTrue(ids.add(entry.info().id()), "duplicate " + entry.info().id());
        }
        for (BonusCondition condition : BonusCondition.values()) {
            assertTrue(ids.add(condition.id()), "bonus id clashes: " + condition.id());
        }
        for (AugmentTier tier : AugmentTier.values()) {
            assertTrue(registry().ofTier(tier).size() >= 2, tier + " needs augments");
        }
    }

    private static AugmentRegistry registry() {
        AugmentRegistry registry = new AugmentRegistry();
        BuiltinAugments.registerAll(registry);
        return registry;
    }

    static String render(AugmentRegistry registry) {
        StringBuilder out = new StringBuilder();
        out.append("# 증강 야생전 증강 목록 (").append(registry.all().size()).append("종)\n\n");
        out.append("<!-- 자동 생성 문서입니다. 직접 고치지 말고 증강 코드(AugmentInfo, BonusCondition)를 고친 뒤 ")
                .append("`./gradlew updateAugmentDocs` 로 다시 만드세요. -->\n\n");
        out.append("수치는 기본값이며 `augments.yml`에서 조정할 수 있습니다. 체력 단위는 체력 포인트(2 = 하트 1칸)입니다. ")
                .append("게임 규칙은 [augment.md](augment.md)를 보세요. 봇 열이 \"가능\"인 증강은 봇전의 봇도 받습니다.\n\n");

        out.append("| 등급 | 증강 |\n|---|---|\n");
        for (AugmentTier tier : AugmentTier.values()) {
            List<String> names = registry.ofTier(tier).stream().map(AugmentInfo::name).toList();
            out.append("| ").append(tier.koreanName()).append(" | ").append(String.join(", ", names)).append(" |\n");
        }

        for (AugmentTier tier : AugmentTier.values()) {
            out.append("\n## ").append(tier.koreanName()).append("\n\n");
            out.append("| 증강 | 종류 | 봇 | 효과 |\n|---|---|---|---|\n");
            for (AugmentInfo info : registry.ofTier(tier)) {
                out.append("| **").append(cell(info.name())).append("** `").append(info.id()).append("` | ")
                        .append(info.kind().koreanName()).append(" | ")
                        .append(info.forBots() ? "가능" : "").append(" | ")
                        .append(cell(plain(info.defaults().render(info.description())))).append(" |\n");
            }
        }

        out.append("\n## 보너스 증강 조건\n\n");
        out.append("조건마다 한 번, 달성하면 그 등급의 증강 카드 3장 중 하나를 더 고릅니다. **선착순** 조건은 경기에서 ")
                .append("가장 먼저 달성한 한 명만 받습니다.\n\n");
        out.append("| 조건 | 등급 | 선착순 | 내용 |\n|---|---|---|---|\n");
        for (BonusCondition condition : BonusCondition.values()) {
            Params params = condition.defaults();
            out.append("| **").append(cell(condition.koreanName())).append("** `").append(condition.id()).append("` | ")
                    .append(condition.defaultTier().koreanName()).append(" | ")
                    .append(condition.firstCome() ? "예" : "").append(" | ")
                    .append(cell(params.render(condition.description()))).append(" |\n");
        }
        return out.toString();
    }

    private static String plain(String miniMessage) {
        return Text.miniMessage().stripTags(miniMessage);
    }

    /** Text safe inside a Markdown table cell. */
    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
