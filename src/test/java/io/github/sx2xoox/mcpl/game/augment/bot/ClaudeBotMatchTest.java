package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.sx2xoox.mcpl.game.augment.command.BotCommand;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Claude bot in a mock 봇전 against a local stub of the Messages API (never the real API): its requests, its
 * orders reaching the brain, its card picks, and that nothing (errors, rate limits, refusals, slow answers, no key)
 * ever stops the match or leaves threads behind.
 */
class ClaudeBotMatchTest {

    private static final Pattern OFFER = Pattern.compile("^- ([a-z0-9_]+) \\(", Pattern.MULTILINE);

    private AugmentHarness h;
    private TestPlayer admin;
    private ClaudeStub stub;
    /** The card the stub took last. */
    private final AtomicReference<String> picked = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        h = new AugmentHarness();
        admin = h.join("Admin");
        admin.setOp(true);
        for (BotPersonality each : BotPersonality.values()) {
            h.settings().set(BotSettings.PERSONALITIES + "." + each.key(), 0);
        }
        BotControllerAccess.autoRecovery(false);
        stub = new ClaudeStub();
        h.settings().set(ClaudeSettings.API_KEY, "test-key");
        h.settings().set(ClaudeSettings.BASE_URL, stub.url());
        h.settings().set(ClaudeSettings.INTERVAL, 3);
        h.settings().set(ClaudeSettings.FARMING_INTERVAL, 3);
        h.settings().set(ClaudeSettings.AUGMENT_WAIT, 5);
        h.settings().set(ClaudeSettings.TIMEOUT, 15);
        h.settings().set(ClaudeSettings.CHAT, true);
        h.settings().set(ClaudeSettings.CHAT_COOLDOWN, 5);
        BotManager.environment(name -> null);
        ClaudeService.resetWarnings();
        // answers: the last card of an offer; else hunt the other bot, with a chat line
        stub.responder = seen -> {
            String user = seen.user();
            if (user.contains("AUGMENT OFFER")) {
                Matcher m = OFFER.matcher(user.substring(user.indexOf("AUGMENT OFFER")));
                String last = "";
                while (m.find()) {
                    last = m.group(1);
                }
                picked.set(last);
                return ClaudeStub.ok(ClaudeStub.decision("FARM", "", "AUTO", last, "카드 받음", ""));
            }
            return ClaudeStub.ok(ClaudeStub.decision("ATTACK", "영희 봇", "MELEE", "", "영희: 아직 모름", "ㅎㅇ"));
        };
    }

    @AfterEach
    void tearDown() {
        BotManager.environment(null);
        BotControllerAccess.autoRecovery(true);
        h.close();
        stub.close();
    }

    private AugmentMatch botMatch(String options) {
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 봇전 " + options));
        h.tickUntil(() -> h.match() != null && h.match().phase() == MatchPhase.FARMING, 3000, "farming");
        return h.match();
    }

    /** Ticks with a little real time in between (the answers come from another thread). */
    private void tickUntil(BooleanSupplier condition, long maxMillis, String what) {
        long end = System.currentTimeMillis() + maxMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                fail("timed out waiting for " + what);
            }
            h.ticks(1);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(e);
            }
        }
    }

    private static BotController claudeBot(AugmentMatch match) {
        for (BotController bot : match.bots().all()) {
            if (bot.claude()) {
                return bot;
            }
        }
        throw new AssertionError("no Claude bot");
    }

    @Test
    void theCommandWordMixesInAClaudeBot() {
        BotCommand.ClaudeAsk ask = BotCommand.ClaudeAsk.parse("지옥 5 클로드");
        assertTrue(ask.asked());
        assertEquals("지옥 5", ask.rest());
        assertFalse(BotCommand.ClaudeAsk.parse("팀 3 4 보통").asked());
        assertTrue(BotCommand.ClaudeAsk.parse("Claude 3").asked());
        assertTrue(BotCommand.suggestions("클").contains("클로드"));
    }

    @Test
    void aClaudeBotCommandsItsBodyAndPicksItsCards() {
        AugmentMatch match = botMatch("2 지옥 클로드");
        BotController claude = claudeBot(match);
        BotController plain = match.bots().all().get(1);
        assertTrue(claude.name().startsWith("[Claude] "), claude.name());
        assertFalse(plain.claude());
        assertEquals(ClaudeService.Status.ON, match.bots().claudeStatus());
        ClaudeCommander commander = claude.commander();
        // 영희 봇 next to it (the mock pathfinder does not walk): it hears her
        org.bukkit.Location near = claude.body().body().getLocation().add(3, 0, 0);
        plain.body().legs().teleport(near);
        plain.body().body().teleport(near);
        // its plan: hunt 영희 봇, close in
        tickUntil(() -> commander.orders(match.clock()) != null
                && commander.orders(match.clock()).target() != null, 15_000, "orders");
        ClaudeOrders orders = commander.orders(match.clock());
        assertEquals(ClaudeOrders.Goal.ATTACK, orders.goal());
        assertEquals(plain.id(), orders.target(), "the name resolved to the rival it knows");
        assertEquals("영희: 아직 모름", commander.notes());
        String first = stub.requests.getFirst().user();
        assertTrue(first.contains("PHASE: farming"), first);
        assertTrue(first.contains("YOUR NOTES: (empty)"));
        assertTrue(stub.requests.size() >= 1);
        h.ticks(5);
        assertEquals(orders, claude.brain().orders(), "the brain thinks with the orders");
        // the round at 2 s: the API's card, not the bot's own pick
        tickUntil(() -> !claude.augments().isEmpty(), 15_000, "a card");
        assertEquals(picked.get(), claude.augments().keySet().iterator().next());
        assertEquals(1, commander.ledger().augmentPicks());
        assertEquals(0, commander.ledger().augmentFallbacks());
        assertTrue(stub.requests.stream().anyMatch(r -> r.user().contains("AUGMENT OFFER")));
        assertFalse(plain.augments().isEmpty(), "the plain bot picked at once");
        // the chat line (rate limited)
        assertTrue(commander.ledger().chats >= 1);
        // the later requests carry its notes
        tickUntil(() -> stub.requests.stream().anyMatch(r -> r.user().contains("YOUR NOTES: 카드 받음")
                || r.user().contains("YOUR NOTES: 영희: 아직 모름")), 15_000, "notes carried");
        // every request has the same system prompt (the cache prefix)
        String system = stub.requests.getFirst().body().getAsJsonArray("system").get(0).getAsJsonObject()
                .get("text").getAsString();
        for (ClaudeStub.Seen seen : stub.requests) {
            assertEquals(system, seen.body().getAsJsonArray("system").get(0).getAsJsonObject().get("text")
                    .getAsString());
        }
        assertTrue(commander.ledger().calls() >= 2);
        assertTrue(commander.ledger().cost("claude-opus-5-5") > 0);
    }

    @Test
    void errorsRateLimitsAndRefusalsLeaveAPlainBot() {
        stub.responder = seen -> ClaudeStub.error(429, "rate_limit_error");
        AugmentMatch match = botMatch("2 지옥 클로드");
        BotController claude = claudeBot(match);
        ClaudeCommander commander = claude.commander();
        tickUntil(() -> commander.ledger().failures() >= 1, 10_000, "a failed call");
        assertNull(commander.orders(match.clock()));
        // the card offer gets its own pick in time
        tickUntil(() -> !claude.augments().isEmpty(), 15_000, "its own card");
        assertTrue(commander.ledger().augmentFallbacks() >= 1);
        stub.responder = seen -> ClaudeStub.message("refusal", "", 0);
        tickUntil(() -> commander.ledger().refusals() >= 1, 20_000, "a refusal");
        // (no fast-forward here: the bots die at the border soon after PvP, and the answer needs real time to arrive)
        stub.responder = seen -> ClaudeStub.ok("not json");
        tickUntil(() -> commander.ledger().badAnswers >= 1, 20_000, "an unreadable answer");
        assertNull(commander.orders(match.clock()));
        assertNotNull(claude.decision(), "it keeps thinking on its own");
        assertTrue(match.phase() == MatchPhase.FARMING || match.phase() == MatchPhase.FIGHT, "the match goes on");
    }

    @Test
    void aSlowAnswerIsDroppedAndTheMatchEndsCleanly() {
        stub.responder = seen -> ClaudeStub.message("end_turn", ClaudeStub.decision("RETREAT", "", "AUTO", "", "",
                ""), 10_000);
        AugmentMatch match = botMatch("2 지옥 클로드");
        BotController claude = claudeBot(match);
        ClaudeCommander commander = claude.commander();
        ClaudeService service = match.bots().claude();
        assertNotNull(service);
        tickUntil(() -> !claude.augments().isEmpty(), 10_000, "its own card while the answer is late");
        assertTrue(commander.ledger().stale() >= 1 || commander.ledger().augmentFallbacks() >= 1);
        tickUntil(commander::busy, 5_000, "a call in flight");
        long started = System.nanoTime();
        h.hub.games().stop(Component.text("test"));
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000, "stopping never waits for the API");
        assertTrue(service.closed());
        assertTrue(commander.stopped());
        long end = System.currentTimeMillis() + 5_000;
        while (!service.terminated() && System.currentTimeMillis() < end) {
            Thread.onSpinWait();
        }
        assertTrue(service.terminated(), "its threads ended, the call in flight included");
    }

    @Test
    void withoutAKeyTheClaudeBotIsAPlainBot() {
        h.settings().set(ClaudeSettings.API_KEY, "");
        AugmentMatch match = botMatch("2 지옥 클로드");
        assertEquals(ClaudeService.Status.NO_KEY, match.bots().claudeStatus());
        for (BotController bot : match.bots().all()) {
            assertFalse(bot.claude());
            assertFalse(bot.name().startsWith("[Claude]"));
        }
        h.ticks(20 * 4);
        assertEquals(0, stub.requests.size());
        assertTrue(match.bots().all().stream().allMatch(bot -> !bot.augments().isEmpty()), "cards as usual");
    }

    @Test
    void theEnabledSettingMixesThemInWithoutTheWordAndTheStatsFileIsWritten() throws IOException {
        h.settings().set(ClaudeSettings.ENABLED, true);
        AugmentMatch match = botMatch("3 어려움");
        assertEquals(1, match.bots().all().stream().filter(BotController::claude).count());
        BotController claude = claudeBot(match);
        tickUntil(() -> claude.commander().ledger().answered() >= 1, 10_000, "an answer");
        List<String> report = match.bots().statsReport();
        assertTrue(report.stream().anyMatch(line -> line.startsWith("claude [Claude] ") && line.contains("calls=")),
                String.valueOf(report));
        assertTrue(report.stream().anyMatch(line -> line.startsWith("claude total: model=claude-opus-5-5")));
        String stats = report.stream().filter(line -> line.startsWith("claude stats: ")).findFirst().orElseThrow();
        Path file = Path.of(stats.substring("claude stats: ".length()));
        List<String> lines = Files.readAllLines(file);
        assertEquals(ClaudeStatsFile.HEADER, lines.getFirst());
        assertEquals(4, lines.size(), "every bot, the plain ones too");
        assertTrue(lines.stream().anyMatch(line -> line.contains(",yes,")));
        assertTrue(lines.stream().anyMatch(line -> line.contains(",no,")));
        assertFalse(String.join("\n", lines).contains("test-key"));
        assertFalse(String.join("\n", report).contains("test-key"));
    }
}
