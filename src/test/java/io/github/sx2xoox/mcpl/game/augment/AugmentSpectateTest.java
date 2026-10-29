package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.game.augment.bot.BotController;
import io.github.sx2xoox.mcpl.game.augment.bot.BotDifficulty;
import io.github.sx2xoox.mcpl.game.augment.bot.BotMotion;
import io.github.sx2xoox.mcpl.game.augment.bot.BotSettings;
import io.github.sx2xoox.mcpl.game.augment.command.BotCommand;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchMode;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 봇 관전전: only bots play, humans watch; mixed difficulties, time scale and the admin phase skip. */
class AugmentSpectateTest {

    private AugmentHarness h;
    private TestPlayer admin;
    private TestPlayer viewer;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        admin = h.join("Admin");
        admin.setOp(true);
        viewer = h.join("Viewer");
        h.settings().set(BotSettings.Spectate.FARMING, 10);
        h.settings().set(BotSettings.Spectate.BORDER_DELAY, 2);
        h.settings().set(BotSettings.Spectate.BORDER_SHRINK, 4);
        for (BotDifficulty difficulty : BotDifficulty.values()) {
            // small bases: the mock world keeps every block a base survey reads
            h.settings().set("bots." + difficulty.key() + ".base-size", 5);
        }
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private AugmentMatch spectate(String options) {
        assertTrue(h.server.dispatchCommand(admin, ("게임 증강 봇전 관전 " + options).trim()));
        h.tickUntil(() -> h.match() != null && h.match().phase() == MatchPhase.FARMING, 3000, "farming");
        return h.match();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** A hit credited to {@code attacker} that no shield blocks (magic: not a melee swing). */
    private static void hit(LivingEntity victim, LivingEntity attacker, double amount) {
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(DamageType.MAGIC).withCausingEntity(attacker)
                .withDirectEntity(attacker).build());
    }

    private List<String> chat(TestPlayer player) {
        List<String> out = new java.util.ArrayList<>();
        Component line;
        while ((line = player.nextComponentMessage()) != null) {
            out.add(plain(line));
        }
        return out;
    }

    // =================================================================== start and participants

    @Test
    void everyHumanWatchesAndOnlyBotsTakePart() {
        AugmentMatch match = spectate("쉬움 2 어려움 1");
        GameSession session = match.session();
        assertEquals(AugmentSpectateGame.ID, session.game().id());
        assertEquals(MatchMode.SPECTATE, match.mode());
        assertEquals(3, session.participantIds().size());
        assertTrue(session.participantIds().stream().allMatch(session::isBot), "only bots take part");
        for (Player human : List.of(admin, viewer)) {
            assertFalse(session.isParticipant(human));
            assertTrue(session.isMember(human), "snapshotted as a spectator");
            assertTrue(session.isSpectator(human));
            assertEquals(GameMode.SPECTATOR, human.getGameMode());
            assertEquals(match.wild().world(), human.getWorld(), "watching in the match world");
        }
        List<BotController> bots = match.bots().all();
        assertEquals(List.of(BotDifficulty.EASY, BotDifficulty.EASY, BotDifficulty.HARD),
                bots.stream().map(BotController::difficulty).toList());
        assertEquals("철수 봇 [쉬움·" + bots.get(0).personality().koreanName() + "]",
                session.plainName(bots.get(0).id()), "the difficulty and the personality next to the name");
        assertEquals("민수 봇 [어려움·" + bots.get(2).personality().koreanName() + "]",
                session.plainName(bots.get(2).id()));
        assertEquals(3, bots.stream().map(BotController::personality).distinct().count(),
                "a small lineup has no personality twice");
        assertEquals("철수 봇", bots.get(0).name(), "the body keeps its plain name");
        assertEquals(10 * 20, match.settings().farmingTicks(), "관전전 timings");
    }

    @Test
    void aSpectateMatchNeedsSomeoneOnlineAndAtLeastTwoBots() {
        AugmentMatch match = spectate("1 보통");
        assertEquals(2, match.bots().all().size(), "one bot cannot fight: a second joins");
    }

    @Test
    void normalMatchesKeepTheirTimings() {
        h.settings().set(BotSettings.Spectate.FARMING, 3);
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 봇전 2 쉬움"));
        h.tickUntilPhase(MatchPhase.FARMING);
        assertEquals(10 * 20, h.match().settings().farmingTicks(), "봇전 keeps games.augment.farming-seconds");
        assertTrue(h.match().session().isParticipant(admin), "humans play in 봇전");
        assertEquals("철수 봇", h.match().session().plainName(h.match().bots().all().getFirst().id()),
                "one difficulty: no tag");
    }

    @Test
    void roundsMoveWithTheShorterFarming() {
        AugmentSettings normal = h.game.readSettings();
        AugmentSettings quick = normal.withTimings(normal.farmingTicks() / 2, 20, 40);
        assertEquals(normal.rounds().getFirst().atTicks() / 2, quick.rounds().getFirst().atTicks());
        assertEquals(20, quick.borderDelayTicks());
        assertEquals(40, quick.borderShrinkTicks());
    }

    @Test
    void mixedDifficultiesParse() {
        BotCommand.Options mix = BotCommand.Options.parse("쉬움 3 어려움 2");
        assertNull(mix.error());
        assertEquals(Map.of(BotDifficulty.EASY, 3, BotDifficulty.HARD, 2), mix.mix());
        assertEquals(5, mix.count());
        BotCommand.Options countFirst = BotCommand.Options.parse("2 지옥 1 보통");
        assertEquals(Map.of(BotDifficulty.HELL, 2, BotDifficulty.NORMAL, 1), countFirst.mix());
        assertEquals(Map.of(BotDifficulty.EASY, 1, BotDifficulty.HARD, 2),
                BotCommand.Options.parse("쉬움 어려움 2").mix(), "a difficulty alone is one bot");
        assertEquals(Map.of(BotDifficulty.EASY, 4), BotCommand.Options.parse("쉬움 3 쉬움 1").mix(), "summed");
        assertEquals("5", BotCommand.Options.parse("쉬움 3 5 어려움").error());
        assertEquals("abc", BotCommand.Options.parse("쉬움 abc").error());
        BotCommand.Options legacy = BotCommand.Options.parse("5 보통");
        assertEquals(5, legacy.count());
        assertEquals(BotDifficulty.NORMAL, legacy.difficulty());
        assertTrue(legacy.mix().isEmpty());
        BotSettings settings = h.game.readBotSettings();
        assertEquals(List.of(BotDifficulty.EASY, BotDifficulty.EASY, BotDifficulty.HARD),
                BotCommand.Options.parse("쉬움 2 어려움 1").lineup(settings));
        h.settings().set(BotSettings.MAX, 3);
        assertEquals(3, BotCommand.Options.parse("쉬움 3 어려움 2").lineup(h.game.readBotSettings()).size(),
                "capped at bots.max");
    }

    // =================================================================== rules

    @Test
    void botsNeverSeeOrTargetTheWatchingHumans() {
        AugmentMatch match = spectate("2 지옥");
        h.tickUntilPhase(MatchPhase.FIGHT);
        BotController bot = match.bots().all().getFirst();
        Location at = bot.body().body().getLocation();
        admin.teleport(at.clone().add(1.5, 0, 0));
        viewer.teleport(at.clone().add(0, 0, 1.5));
        double health = admin.getHealth();
        for (int i = 0; i < 60; i++) {
            h.ticks(1);
            assertTrue(match.rivals(bot.id(), match.wild().world()).stream().noneMatch(e -> e instanceof Player),
                    "no human is a rival");
            assertFalse(match.isEnemy(bot.id(), admin));
            if (bot.decision() != null) {
                assertFalse(admin.getUniqueId().equals(bot.decision().target()));
                assertFalse(viewer.getUniqueId().equals(bot.decision().target()));
            }
        }
        assertEquals(health, admin.getHealth(), 1.0E-9, "never hurt");
    }

    @Test
    void botsFightToTheLastOneWhoWins() {
        AugmentMatch match = spectate("3 보통");
        GameSession session = match.session();
        h.tickUntilPhase(MatchPhase.FIGHT);
        List<BotController> bots = match.bots().all();
        LivingEntity winner = bots.get(0).body().body();
        hit(bots.get(1).body().body(), winner, 1000);
        assertEquals(GameState.RUNNING, session.state(), "two bots still stand: no human-style early end");
        chat(admin);
        hit(bots.get(2).body().body(), winner, 1000);
        assertEquals(GameState.ENDING, session.state());
        GameResult result = session.result();
        assertNotNull(result);
        assertEquals(List.of(bots.get(0).id()), result.winners(), "the last bot standing wins alone");
        assertEquals(2, session.stats().of(bots.get(0).id()).kills());
        assertTrue(chat(admin).stream().anyMatch(line -> line.contains("우승") && line.contains("철수 봇 [보통·")),
                "the winner is announced to the spectators");
    }

    @Test
    void theBotsFightEachOtherToAWinnerOnTheirOwn() {
        h.settings().set(BotSettings.Spectate.BORDER_DELAY, 10);
        h.settings().set(BotSettings.Spectate.BORDER_SHRINK, 60);
        AugmentMatch match = spectate("3 지옥");
        GameSession session = match.session();
        h.tickUntilPhase(MatchPhase.FIGHT);
        // the mock pathfinder does not walk: put them where the real one would bring them, in sight of each other
        Location center = new Location(match.wild().world(), match.wild().arena().centerX(), 0,
                match.wild().arena().centerZ());
        center.setY(match.wild().world().getHighestBlockYAt(center) + 1);
        int i = 0;
        for (BotController bot : match.bots().all()) {
            bot.body().legs().teleport(center.clone().add(i * 1.5, 0, (i % 2) * 1.5));
            i++;
        }
        for (int t = 0; t < 20 * 120 && session.state() == GameState.RUNNING; t++) {
            // the mock does not tick entities: wear off the invulnerability frames like the server does
            for (BotController bot : match.bots().all()) {
                Mannequin body = bot.body().body();
                body.setNoDamageTicks(Math.max(0, body.getNoDamageTicks() - 1));
            }
            h.ticks(1);
            if (t % 100 == 0) {
                chat(admin);
                chat(viewer);
            }
        }
        assertEquals(GameState.ENDING, session.state(), "the bots settle it among themselves");
        GameResult result = session.result();
        assertNotNull(result);
        assertEquals(1, result.winners().size(), "one bot left");
        assertTrue(session.isBot(result.winners().getFirst()));
    }

    /**
     * Moves every bot's legs along its last path like the server would (the mock pathfinder does not walk): straight
     * to the destination at the pace the path's speed gives on flat ground.
     */
    private static void walk(AugmentMatch match) {
        for (BotController bot : match.bots().all()) {
            if (bot.body() == null || bot.body().body().isDead()) {
                continue;
            }
            var legs = bot.body().legs();
            var pathfinder = (io.github.sx2xoox.mcpl.game.superpower.support.world.MockEntities.RecordingPathfinder)
                    legs.getPathfinder();
            if (!pathfinder.hasPath() || pathfinder.destinations().isEmpty()) {
                continue;
            }
            Location to = pathfinder.destinations().getLast();
            Location at = legs.getLocation();
            double step = BotMotion.groundSpeed(0.32, pathfinder.speeds().getLast()) / 20;
            double dx = to.getX() - at.getX();
            double dz = to.getZ() - at.getZ();
            double distance = Math.hypot(dx, dz);
            if (distance < 0.2) {
                pathfinder.stopPathfinding();
                continue;
            }
            double f = Math.min(1, step / distance);
            Location next = at.clone().add(dx * f, 0, dz * f);
            next.setY(at.getWorld().getHighestBlockYAt(next) + 1);
            legs.teleport(next);
        }
    }

    @Test
    void aTypicalBotOnlyMatchIsWonByABotThatFought() {
        h.settings().set(BotSettings.Spectate.BORDER_DELAY, 20);
        h.settings().set(BotSettings.Spectate.BORDER_SHRINK, 90);
        AugmentMatch match = spectate("4 보통");
        GameSession session = match.session();
        h.tickUntilPhase(MatchPhase.FIGHT);
        // scattered over the world as spawned: they have to find each other
        for (int t = 0; t < 20 * 240 && session.state() == GameState.RUNNING; t++) {
            for (BotController bot : match.bots().all()) {
                if (bot.body() != null) {
                    Mannequin body = bot.body().body();
                    body.setNoDamageTicks(Math.max(0, body.getNoDamageTicks() - 1));
                }
            }
            walk(match);
            h.ticks(1);
            if (t % 100 == 0) {
                chat(admin);
                chat(viewer);
            }
        }
        assertEquals(GameState.ENDING, session.state(), "settled among themselves");
        GameResult result = session.result();
        assertNotNull(result);
        assertEquals(1, result.winners().size());
        UUID winner = result.winners().getFirst();
        assertTrue(session.stats().of(winner).kills() >= 1, "the winner fought for it: "
                + session.stats().of(winner).kills() + " kills");
    }

    @Test
    void aFastClockNeverMakesTheBorderOutrunTheBots() {
        h.settings().set(AugmentSettings.RADIUS, 400);
        h.settings().set(AugmentSettings.PREGENERATE, false);
        h.settings().set(BotSettings.Spectate.BORDER_SHRINK, 360);
        AugmentMatch match = spectate("2 보통");
        assertTrue(match.setTimeScale(4));
        assertEquals(4.0, match.clockRate(), 1.0E-9, "the farming runs four times as fast");
        assertEquals(AugmentMatch.PhaseStep.FIGHT, match.advancePhase());
        assertEquals(4.0, match.clockRate(), 1.0E-9, "the border delay too");
        assertEquals(AugmentMatch.PhaseStep.SHRINK, match.advancePhase(), "the border starts closing");
        assertTrue(match.borderShrinking());
        double rate = match.clockRate();
        assertTrue(rate < 4 && rate >= 1, "but not the shrink: " + rate);
        double edge = BotMotion.borderEdgeSpeed(rate, 800, 24, 360 * 20);
        assertTrue(edge <= 0.7 * BotMotion.SPRINT_BPS + 1.0E-6, "the edge moves at most 70 % of a sprint: " + edge);
        double before = match.borderHalfIn(0);
        h.ticks(20);
        double moved = before - match.borderHalfIn(0);
        assertTrue(moved > 0 && moved <= 0.7 * BotMotion.SPRINT_BPS + 0.2, "blocks in a real second: " + moved);
    }

    @Test
    void theWorldBorderHurtsBotsOutsideItToABorderDeath() {
        h.settings().set(BotSettings.Spectate.BORDER_DELAY, 600);
        AugmentMatch match = spectate("2 쉬움");
        GameSession session = match.session();
        h.tickUntilPhase(MatchPhase.FIGHT);
        BotController bot = match.bots().all().getFirst();
        World world = match.wild().world();
        world.getWorldBorder().setDamageAmount(0.2);
        Location outside = new Location(world, match.wild().arena().centerX() + match.settings().radius() + 30, 0,
                match.wild().arena().centerZ());
        outside.setY(world.getHighestBlockYAt(outside) + 1);
        bot.body().legs().teleport(outside);
        double health = bot.body().body().getHealth();
        h.ticks(22);
        assertTrue(bot.body().body().getHealth() < health, "hurt by the border");
        assertTrue(bot.decision() != null
                && bot.decision().behaviour() == io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour.FLEE_BORDER,
                "and heads back in: " + bot.decision());
        h.tickUntil(() -> !session.isAlive(bot.id()), 20 * 30, "a border death");
        List<String> feed = chat(admin);
        assertTrue(feed.stream().anyMatch(line -> line.contains("철수 봇") && line.contains("자기장")),
                () -> "a world border death in the kill feed: " + feed);
    }

    @Test
    void whenEveryHumanLeavesTheMatchStopsAndTheBotsGo() {
        AugmentMatch match = spectate("2 쉬움");
        GameSession session = match.session();
        World world = match.wild().world();
        assertTrue(admin.disconnect());
        h.ticks(40);
        assertEquals(GameState.RUNNING, session.state(), "someone still watches");
        assertTrue(viewer.disconnect());
        h.tickUntil(() -> session.state() == GameState.CLOSED, 200, "stopped");
        assertTrue(world.getEntitiesByClass(Mannequin.class).isEmpty(), "bots removed");
        assertNull(h.match());
    }

    @Test
    void spectatorsAreRestoredAfterTheMatch() {
        Location before = viewer.getLocation();
        AugmentMatch match = spectate("2 쉬움");
        GameSession session = match.session();
        h.tickUntilPhase(MatchPhase.FIGHT);
        List<BotController> bots = match.bots().all();
        hit(bots.get(1).body().body(), bots.get(0).body().body(), 1000);
        h.tickUntil(() -> session.state() == GameState.CLOSED, 20 * 60, "closed");
        assertEquals(GameMode.SURVIVAL, viewer.getGameMode());
        assertEquals(before.getWorld(), viewer.getWorld(), "back where they were");
        assertEquals(before.getX(), viewer.getLocation().getX(), 1.0E-6);
    }

    @Test
    void teamModeSplitsTheBotsIntoTeams() {
        h.hub.config().setTeamSize(2);
        AugmentMatch match = spectate("4 쉬움");
        GameSession session = match.session();
        assertTrue(session.isTeamMode());
        assertEquals(2, session.teams().size());
        for (MatchTeam team : session.teams()) {
            assertEquals(2, team.size());
        }
        h.tickUntilPhase(MatchPhase.FIGHT);
        List<BotController> bots = match.bots().all();
        MatchTeam first = session.teamOf(bots.getFirst().id());
        for (BotController bot : bots) {
            if (!first.equals(session.teamOf(bot.id()))) {
                hit(bot.body().body(), bots.getFirst().body().body(), 1000);
            }
        }
        assertEquals(GameState.ENDING, session.state());
        assertTrue(session.result().isWinner(bots.getFirst().id()), "the last team standing wins");
    }

    @Test
    void theBotPvpGateLiftsAtTheFightStart() {
        AugmentMatch match = spectate("2 쉬움");
        List<BotController> bots = match.bots().all();
        LivingEntity a = bots.get(0).body().body();
        LivingEntity b = bots.get(1).body().body();
        double health = b.getHealth();
        hit(b, a, 3);
        assertEquals(health, b.getHealth(), 1.0E-9, "farming: bots cannot hurt each other");
        h.tickUntilPhase(MatchPhase.FIGHT);
        double before = b.getHealth(); // (a 강철 체력 augment of the farming may have raised it)
        hit(b, a, 3);
        assertTrue(b.getHealth() < before, "fight: they can");
    }

    // =================================================================== spectator tools

    @Test
    void spectatorsFollowABotByNameOrNextAndMoveOnWhenItDies() {
        AugmentMatch match = spectate("3 쉬움");
        h.tickUntilPhase(MatchPhase.FIGHT);
        List<BotController> bots = match.bots().all();
        assertTrue(h.server.dispatchCommand(viewer, "게임 증강 관전 철수"));
        assertEquals(bots.get(0).body().body(), viewer.getSpectatorTarget(), "camera on 철수 봇");
        assertTrue(h.server.dispatchCommand(viewer, "게임 증강 관전 다음"));
        assertEquals(bots.get(1).body().body(), viewer.getSpectatorTarget(), "the next bot");
        hit(bots.get(1).body().body(), bots.get(2).body().body(), 1000);
        assertEquals(bots.get(2).body().body(), viewer.getSpectatorTarget(), "moved on to the killer");
        chat(viewer);
        assertTrue(h.server.dispatchCommand(viewer, "게임 증강 관전"));
        List<String> list = chat(viewer);
        assertTrue(list.stream().anyMatch(line -> line.contains("철수 봇 [쉬움·") && line.contains("❤")), list.toString());
        assertTrue(list.stream().noneMatch(line -> line.contains("영희 봇")), "the dead are not listed");
    }

    @Test
    void theSidebarShowsEveryBotWithWhatItIsDoing() {
        AugmentMatch match = spectate("2 보통");
        h.ticks(40);
        var sidebar = match.session().sidebar(viewer);
        String lines = String.join("\n", sidebar.lines().stream().map(AugmentSpectateTest::plain).toList());
        assertTrue(lines.contains("철수 봇 [보통·"), "difficulty and personality: " + lines);
        assertTrue(lines.contains("파밍") || lines.contains("기지 건설"), "the behaviour in Korean: " + lines);
        assertTrue(lines.contains("◆"), "augment count");
    }

    // =================================================================== time scale and skipping

    @Test
    void theTimeScaleSpeedsUpTheMatchClocksOnly() {
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 봇전 2 쉬움"));
        h.tickUntilPhase(MatchPhase.FARMING);
        AugmentMatch match = h.match();
        assertEquals(1, match.timeScale());
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 배속 4"));
        assertEquals(4, match.timeScale());
        long clock = match.clock();
        long time = match.time();
        h.ticks(10);
        assertEquals(clock + 10, match.clock(), "real ticks stay real");
        assertEquals(time + 40, match.time(), "the match clock runs four times as fast");
        assertTrue(match.ticksToNextRound() < 0 || match.ticksToNextRound() <= (2 * 20 - match.time()) / 4 + 1);
        h.tickUntilPhase(MatchPhase.FIGHT);
        assertTrue(match.clock() <= 10 * 20 / 4 + 12, "10 s of farming in about 2.5 s: " + match.clock());
        assertEquals(1, match.roundsPlayed(), "the round came on the fast clock");
        assertEquals(1.0, match.farmingProgress(), 0.2, "and the bots' gear schedule with it");
        h.tickUntil(match::borderShrinking, 400, "shrink");
        assertTrue(match.clock() <= 10 * 20 / 4 + 2 * 20 / 4 + 15, "the border delay too: " + match.clock());
    }

    @Test
    void theTimeScaleNeedsAnAdminAndABotMatch() {
        AugmentMatch normal = h.startFarming();
        assertFalse(normal.setTimeScale(3), "a normal match has no time scale");
        assertEquals(1, normal.timeScale());
        viewer.setOp(false);
        h.server.dispatchCommand(viewer, "게임 증강 배속 3");
        assertEquals(1, normal.timeScale());
    }

    @Test
    void anAdminSkipsTheFarmingOfARealMatch() {
        AugmentMatch match = h.startFarming();
        GameSession session = match.session();
        assertEquals(0, match.roundsPlayed());
        h.server.dispatchCommand(viewer, "게임 증강 다음단계");
        assertEquals(MatchPhase.FARMING, match.phase(), "players cannot skip");
        chat(viewer);
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 다음단계"));
        assertEquals(MatchPhase.FIGHT, match.phase(), "PvP now");
        assertEquals(1, match.roundsPlayed(), "the round due by the fight start opened");
        assertTrue(chat(viewer).stream().anyMatch(line -> line.contains("관리자가 무적")), "told everyone");
        assertFalse(match.borderShrinking());
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 다음단계"));
        assertTrue(match.borderShrinking(), "the border shrinks now");
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 다음단계"));
        assertTrue(match.borderFinal());
        assertEquals(GameState.RUNNING, session.state());
    }

    @Test
    void skippingTheFarmingOfABotMatchTurnsTheBotsToFighting() {
        AugmentMatch match = spectate("2 지옥");
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 다음단계"));
        assertEquals(MatchPhase.FIGHT, match.phase());
        assertTrue(match.fight());
        h.ticks(30);
        for (BotController bot : match.bots().all()) {
            assertNotNull(bot.decision());
            assertFalse(bot.decision().behaviour() == io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour.FARM
                    || bot.decision().behaviour()
                    == io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour.BUILD_BASE,
                    "no farming after the skip: " + bot.decision());
        }
    }

    // =================================================================== looking around

    @Test
    void aBotTurnsItsHeadTowardsATargetAtItsTurnSpeed() {
        h.settings().set(BotSettings.Spectate.BORDER_DELAY, 600);
        AugmentMatch match = spectate("쉬움 1 지옥 1");
        h.tickUntilPhase(MatchPhase.FIGHT);
        BotController easy = match.bots().all().get(0);
        BotController hell = match.bots().all().get(1);
        Location at = easy.body().legs().getLocation();
        at.setYaw(0);
        easy.body().legs().teleport(at);
        hell.body().legs().teleport(at.clone().add(0, 0, -4)); // straight behind (north)
        float before = easy.headYaw();
        h.ticks(1);
        float turned = Math.abs(io.github.sx2xoox.mcpl.game.augment.bot.BotAim.delta(before, easy.headYaw()));
        assertTrue(turned <= BotDifficulty.EASY.turnSpeed() + 0.01f, "no instant snap: " + turned);
        h.ticks(30);
        assertEquals(180f, Math.abs(easy.headYaw()), 25f, "faces its rival in the end: " + easy.headYaw());
        assertEquals(0f, io.github.sx2xoox.mcpl.game.augment.bot.BotAim.delta(easy.headYaw(),
                easy.body().body().getLocation().getYaw()), 1.0f, "the body entity shows the head's rotation");
    }

    @Test
    void theMatchWorldKeepsTheSpectatorsWhenOneJoinsLater() {
        AugmentMatch match = spectate("2 쉬움");
        TestPlayer late = h.join("Late");
        h.ticks(2);
        assertTrue(match.session().isSpectator(late));
        assertFalse(match.session().isParticipant(late), "joiners never take part");
        assertEquals(GameMode.SPECTATOR, late.getGameMode());
        UUID id = late.getUniqueId();
        assertFalse(match.session().aliveIds().contains(id));
    }

    // =================================================================== 봇 채팅

    private static boolean botLine(String line) {
        return line.matches("^<\\S+ 봇> .+");
    }

    @Test
    void botsTalkInChatLikePlayers() {
        h.settings().set(io.github.sx2xoox.mcpl.game.augment.bot.BotChat.Settings.CHANCE, 5);
        h.settings().set(io.github.sx2xoox.mcpl.game.augment.bot.BotChat.Settings.CHANCES + ".greeting", 1.0);
        spectate("3 쉬움");
        chat(admin);
        h.ticks(20 * 30);
        List<String> lines = chat(admin).stream().filter(AugmentSpectateTest::botLine).toList();
        assertFalse(lines.isEmpty(), "greetings: " + lines);
        assertTrue(lines.size() <= 30 / 4 + 1, "the global gap keeps it sparse: " + lines);
        assertTrue(lines.stream().noneMatch(line -> line.contains("<target>") || line.contains("<augment>")),
                "placeholders resolve: " + lines);
    }

    @Test
    void disabledBotChatIsSilent() {
        h.settings().set(io.github.sx2xoox.mcpl.game.augment.bot.BotChat.Settings.ENABLED, false);
        h.settings().set(io.github.sx2xoox.mcpl.game.augment.bot.BotChat.Settings.CHANCE, 5);
        AugmentMatch match = spectate("3 쉬움");
        h.tickUntilPhase(MatchPhase.FIGHT);
        List<BotController> bots = match.bots().all();
        hit(bots.get(0).body().body(), bots.get(1).body().body(), 1000);
        h.ticks(60);
        assertTrue(chat(admin).stream().noneMatch(AugmentSpectateTest::botLine));
    }

    @Test
    void aKillIsTauntedAndTheVictimSaysLastWords() {
        h.settings().set(BotSettings.Spectate.BORDER_DELAY, 600);
        h.settings().set(io.github.sx2xoox.mcpl.game.augment.bot.BotChat.Settings.CHANCES + ".kill", 1.0);
        h.settings().set(io.github.sx2xoox.mcpl.game.augment.bot.BotChat.Settings.CHANCES + ".death", 1.0);
        h.settings().set(io.github.sx2xoox.mcpl.game.augment.bot.BotChat.Settings.CHANCE, 5);
        for (String trigger : List.of("greeting", "fight-start", "augment", "gear", "spot", "engage", "border",
                "base-done", "low", "escape", "third-party", "showdown")) {
            h.settings().set(io.github.sx2xoox.mcpl.game.augment.bot.BotChat.Settings.CHANCES + "." + trigger, 0.0);
        }
        AugmentMatch match = spectate("3 쉬움");
        h.tickUntilPhase(MatchPhase.FIGHT);
        h.ticks(100);
        chat(admin);
        List<BotController> bots = match.bots().all();
        hit(bots.get(0).body().body(), bots.get(1).body().body(), 1000);
        h.ticks(60);
        List<String> all = chat(admin);
        List<String> lines = all.stream().filter(AugmentSpectateTest::botLine).toList();
        assertFalse(lines.isEmpty(), "chat: " + all);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("<철수 봇> ")), "last words: " + lines);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("<영희 봇> ")), "the taunt: " + lines);
        assertTrue(lines.stream().noneMatch(line -> line.contains("<target>") || line.contains("<kills>")), lines.toString());
    }
}
