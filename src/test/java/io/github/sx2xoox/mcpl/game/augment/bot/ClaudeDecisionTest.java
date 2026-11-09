package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Parsing and cleaning the model's answer, the prompt parts, the settings and the cost estimate. */
class ClaudeDecisionTest {

    @Test
    void parsesTheStructuredAnswer() {
        ClaudeDecision d = ClaudeDecision.parse("""
                {"goal":"ATTACK","target":"영희 봇","range":"BOW","augment":"iron_body",
                 "notes":"영희: 기둥 쌓고 활","chat":"간다~","reason":"weak target"}""");
        assertNotNull(d);
        assertEquals(ClaudeOrders.Goal.ATTACK, d.goal());
        assertEquals("영희 봇", d.target());
        assertEquals(ClaudeOrders.Range.BOW, d.range());
        assertEquals("iron_body", d.augment());
        assertEquals("영희: 기둥 쌓고 활", d.notes());
        assertEquals("간다~", d.chat());
    }

    @Test
    void anythingElseIsNoAnswer() {
        assertNull(ClaudeDecision.parse(""));
        assertNull(ClaudeDecision.parse("I think you should attack"));
        assertNull(ClaudeDecision.parse("[1,2]"));
        assertNull(ClaudeDecision.parse("{\"chat\":\"hi\"}"));
        ClaudeDecision odd = ClaudeDecision.parse("{\"goal\":\"DANCE\",\"range\":5}");
        assertNotNull(odd);
        assertEquals(ClaudeOrders.Goal.AUTO, odd.goal());
        assertEquals(ClaudeOrders.Range.AUTO, odd.range());
    }

    @Test
    void theChatLineIsOnePlainShortLine() {
        ClaudeDecision d = ClaudeDecision.parse("{\"goal\":\"AUTO\",\"chat\":\"<red>hi</red>\\n<click:run_command:/op x>"
                + "다 덤벼라 아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아아\"}");
        assertNotNull(d);
        assertFalse(d.chat().contains("<"));
        assertFalse(d.chat().contains("\n"));
        assertTrue(d.chat().length() <= ClaudeDecision.CHAT_MAX);
    }

    @Test
    void theSchemaRequiresEveryFieldAndNothingElse() {
        Map<String, Object> schema = ClaudePrompt.schema();
        assertEquals("object", schema.get("type"));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(List.of("goal", "target", "range", "augment", "notes", "chat", "reason"), schema.get("required"));
    }

    @Test
    void theSystemPromptIsFixedAndTheStateGoesInTheUserMessage() {
        List<ClaudePrompt.AugmentLine> catalog = List.of(new ClaudePrompt.AugmentLine("iron_body", "강철 체력", "silver",
                "최대 체력 +4"));
        String system = ClaudePrompt.system(catalog);
        assertEquals(system, ClaudePrompt.system(catalog), "same text every time (the cache prefix)");
        assertTrue(system.contains("iron_body | 강철 체력 | silver | 최대 체력 +4"));
        ClaudeSnapshot snap = snapshot();
        String user = ClaudePrompt.user(snap, "영희: 용암", List.of(new ClaudePrompt.Card("iron_body", "강철 체력",
                "silver")), "round 1, silver tier");
        assertTrue(user.contains("영희 봇"));
        assertTrue(user.contains("holds LAVA bucket"));
        assertTrue(user.contains("YOUR NOTES: 영희: 용암"));
        assertTrue(user.contains("- iron_body (강철 체력, silver)"));
        assertFalse(system.contains("영희"), "nothing of the match in the cached part");
        assertEquals(snap.rivals().getFirst(), snap.rival("영희봇"), "names match loosely");
        assertNull(snap.rival("민수 봇"));
    }

    static ClaudeSnapshot snapshot() {
        ClaudeSnapshot.Self self = new ClaudeSnapshot.Self("[Claude] 철수 봇", "hell", "neutral", 18, 20, 0, 3, 16, 120, 15,
                "iron armor, iron sword", true, true, List.of("강철 체력"), "finished, 3 blocks away", 1, false);
        ClaudeSnapshot.Rival rival = new ClaudeSnapshot.Rival(UUID.randomUUID(), "영희 봇", 12, 5, 14, 20, 15,
                "lava_bucket", true, false, true, false, true, true, 0.62, 0);
        return new ClaudeSnapshot(true, 0, 95, 1, 40, true, 150, 15, 60, 6, self, List.of(rival),
                List.of("영희 봇 poured lava on you"), "KITE -> 영희 봇");
    }

    @Test
    void theKeyComesFromTheSettingElseTheEnvironmentAndNeverShows() {
        ClaudeSettings none = ClaudeSettings.DEFAULTS;
        assertEquals("", none.resolveKey(name -> null));
        assertEquals("env-key", none.resolveKey(name -> name.equals(ClaudeSettings.KEY_ENV) ? "env-key" : null));
        ClaudeSettings set = new ClaudeSettings(true, 1, "claude-opus-5-5", "low", 160, 400, 240, 15_000, 2048, false,
                900, "[Claude] ", "sk-secret", "", true);
        assertEquals("sk-secret", set.resolveKey(name -> "env-key"));
        assertFalse(set.toString().contains("sk-secret"));
        assertEquals(1, set.defaultBots());
        assertEquals(0, none.defaultBots());
        assertEquals(1, none.askedBots());
    }

    @Test
    void theCostFollowsTheModelsPrices() {
        ClaudeLedger ledger = new ClaudeLedger();
        ledger.reply(new ClaudeClient.Reply("end_turn", "{}", 1_000_000, 100_000, 2_000_000, 0), 1_000_000);
        assertEquals(4 + 2 + 0.4, ledger.cost("claude-opus-5-5"), 1e-9);
        assertEquals(2 + 1 + 0.4, ledger.cost("claude-sonnet-5-5"), 1e-9);
        assertEquals(0.1 + 0.05 + 0.02, ledger.cost("claude-haiku-5-5"), 1e-9);
    }
}
