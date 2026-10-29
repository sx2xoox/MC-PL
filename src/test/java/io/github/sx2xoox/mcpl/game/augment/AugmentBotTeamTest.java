package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.game.augment.bot.BotController;
import io.github.sx2xoox.mcpl.game.augment.bot.BotControllerAccess;
import io.github.sx2xoox.mcpl.game.augment.bot.BotDifficulty;
import io.github.sx2xoox.mcpl.game.augment.bot.BotPersonality;
import io.github.sx2xoox.mcpl.game.augment.bot.BotSettings;
import io.github.sx2xoox.mcpl.game.augment.command.BotCommand;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 봇전 팀전: humans spread over the teams, bots fill them, no friendly fire, the last team standing wins. */
class AugmentBotTeamTest {

    private AugmentHarness h;
    private TestPlayer admin;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        admin = h.join("Admin");
        admin.setOp(true);
        for (BotPersonality each : BotPersonality.values()) {
            h.settings().set(BotSettings.PERSONALITIES + "." + each.key(), 0);
        }
        BotControllerAccess.autoRecovery(false);
    }

    @AfterEach
    void tearDown() {
        BotControllerAccess.autoRecovery(true);
        h.close();
    }

    private AugmentMatch start(String command) {
        assertTrue(h.server.dispatchCommand(admin, command));
        h.tickUntil(() -> h.match() != null && h.match().phase() == MatchPhase.FARMING, 3000, "farming");
        return h.match();
    }

    private void hit(LivingEntity victim, LivingEntity attacker, double amount) {
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(attacker)
                .withDirectEntity(attacker).build());
    }

    @Test
    void teamOptionsParse() {
        BotCommand.TeamOptions team = BotCommand.TeamOptions.parse("팀 3 4 보통");
        assertNotNull(team);
        BotSettings settings = h.game.readBotSettings();
        assertEquals(3, team.teamSize(settings));
        assertEquals(4, team.teamCount(settings));
        assertEquals(10, team.lineup(settings, 2).size(), "12 places, 2 humans: 10 bots");
        assertTrue(team.lineup(settings, 2).stream().allMatch(d -> d == BotDifficulty.NORMAL));
        BotCommand.TeamOptions big = BotCommand.TeamOptions.parse("팀 4 5");
        assertEquals(15, big.lineup(settings, 0).size(), "at most bots.max bots");
        BotCommand.TeamOptions mixed = BotCommand.TeamOptions.parse("팀 2 3 쉬움 1 어려움 1");
        List<BotDifficulty> lineup = mixed.lineup(settings, 0);
        assertEquals(6, lineup.size());
        assertEquals(3, lineup.stream().filter(d -> d == BotDifficulty.HARD).count(), "the mix sets the shares");
        assertEquals(2, BotCommand.TeamOptions.parse("팀 2 1").teamCount(settings), "at least two teams");
        assertTrue(BotCommand.TeamOptions.parse("팀 2 3").lineup(settings, 6).isEmpty(), "no room for bots");
        assertNull(BotCommand.TeamOptions.parse("3 보통"), "not a team request");
        assertNotNull(BotCommand.TeamOptions.parse("팀 2 3 세개").difficulty().error());
    }

    @Test
    void humansAreSpreadOverTheTeamsAndBotsFillEveryPlace() {
        TestPlayer other = h.join("Other");
        AugmentMatch match = start("게임 증강 봇전 팀 2 3 쉬움");
        GameSession session = match.session();
        assertTrue(session.isTeamMode());
        assertEquals(3, session.teams().size(), "exactly the asked number of teams");
        assertEquals(4, match.bots().all().size(), "6 places, 2 humans: 4 bots");
        for (MatchTeam team : session.teams()) {
            assertEquals(2, team.size(), "every team is full: " + team);
        }
        assertNotEquals(session.teamOf(admin), session.teamOf(other), "the humans are spread over the teams");
        for (BotController bot : match.bots().all()) {
            MatchTeam team = session.teamOf(bot.id());
            assertNotNull(team);
            assertEquals(team.color(), bot.body().body().customName().color(), "the nametag shows the team colour");
        }
    }

    @Test
    void teammatesNeverHurtEachOther() {
        AugmentMatch match = start("게임 증강 봇전 팀 2 2 쉬움");
        GameSession session = match.session();
        h.tickUntilPhase(MatchPhase.FIGHT);
        BotController mate = match.bots().all().stream()
                .filter(bot -> session.sameTeam(bot.id(), admin.getUniqueId())).findFirst().orElseThrow();
        BotController enemy = match.bots().all().stream()
                .filter(bot -> !session.sameTeam(bot.id(), admin.getUniqueId())).findFirst().orElseThrow();
        LivingEntity mateBody = mate.body().body();
        double before = mateBody.getHealth();
        hit(mateBody, admin, 5);
        assertEquals(before, mateBody.getHealth(), 1.0E-6, "the human cannot hurt its bot teammate");
        double human = admin.getHealth();
        hit(admin, mateBody, 5);
        assertEquals(human, admin.getHealth(), 1.0E-6, "its bot teammate cannot hurt the human");
        LivingEntity enemyBody = enemy.body().body();
        double enemyHealth = enemyBody.getHealth();
        hit(enemyBody, admin, 5);
        assertTrue(enemyBody.getHealth() < enemyHealth, "enemies can be hit");
    }

    @Test
    void theLastTeamStandingWinsEvenAfterEveryHumanIsOut() {
        AugmentMatch match = start("게임 증강 봇전 팀 2 2 쉬움");
        GameSession session = match.session();
        h.tickUntilPhase(MatchPhase.FIGHT);
        UUID me = admin.getUniqueId();
        BotController mate = match.bots().all().stream().filter(bot -> session.sameTeam(bot.id(), me))
                .findFirst().orElseThrow();
        List<BotController> enemies = match.bots().all().stream().filter(bot -> !session.sameTeam(bot.id(), me))
                .toList();
        assertEquals(2, enemies.size());
        hit(admin, enemies.getFirst().body().body(), 1000);
        assertEquals(GameState.RUNNING, session.state(), "the human is out, its bot teammate fights on");
        hit(enemies.getFirst().body().body(), mate.body().body(), 1000);
        assertEquals(GameState.RUNNING, session.state());
        hit(mate.body().body(), enemies.get(1).body().body(), 1000);
        assertEquals(GameState.ENDING, session.state(), "one team left");
        GameResult result = session.result();
        assertNotNull(result);
        assertTrue(result.isWinner(enemies.get(1).id()));
        assertTrue(result.isWinner(enemies.getFirst().id()), "the whole team wins");
        assertTrue(!result.isWinner(me) && !result.isWinner(mate.id()));
    }

    @Test
    void spectateTeamsAreBotsOnly() {
        AugmentMatch match = start("게임 증강 봇전 관전 팀 3 2 보통");
        GameSession session = match.session();
        assertTrue(session.isTeamMode());
        assertEquals(2, session.teams().size());
        assertEquals(6, match.bots().all().size());
        for (MatchTeam team : session.teams()) {
            assertEquals(3, team.size());
            assertTrue(team.members().stream().allMatch(session::isBot));
        }
    }
}
