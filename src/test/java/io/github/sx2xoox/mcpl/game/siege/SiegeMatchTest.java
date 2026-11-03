package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.map.ArenaMap;
import io.github.sx2xoox.mcpl.core.map.siege.SiegeSites;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A 공성전 match on the fallback layout: towers, scoring, halves, rounds, respawns, team sizes and cleanup. */
class SiegeMatchTest {

    private MatchHarness h;
    private SiegeGame siege;
    private ConfigurationSection config;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
        h.registerDummy();
        h.registerTestAbilities();
        h.settings().set("ultimate.per-second", 0);
        siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
        assertNotNull(siege, "공성전 is registered");
        // The 협곡 요새 map is registered but not built in tests: play on the straight fallback layout.
        siege.sitesOverride(new FallbackSiegeSites((int) FlatWorld.SURFACE));
        config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy"));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.HALF_PREP_SECONDS, 1);
        config.set(SiegeSettings.HALFTIME_SECONDS, 1);
        config.set(SiegeSettings.ROUND_BREAK_SECONDS, 1);
        // No spawn camping damage unless a test wants it (the fallback spawns are close to the towers).
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    // ------------------------------------------------------------------ helpers

    private SiegeMatch start(int players) {
        h.join(players);
        try {
            h.hub.games().start(siege, null);
        } catch (GameStartException e) {
            throw new AssertionError(e);
        }
        return untilFight(1);
    }

    private SiegeMatch untilFight(int half) {
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT
                && siege.currentMatch().half() == half, 2000, "half " + half + " to start");
        return siege.currentMatch();
    }

    private static Player member(SiegeMatch match, int team) {
        UUID id = match.teams().get(team).members().iterator().next();
        return match.session().hub().plugin().getServer().getPlayer(id);
    }

    private static MatchContext ctx(SiegeMatch match) {
        return match.abilities().context();
    }

    private static Tower tower(SiegeMatch match, String id) {
        for (Tower tower : match.towers()) {
            if (tower.id().equals(id)) {
                return tower;
            }
        }
        throw new AssertionError("no tower " + id);
    }

    private static Mannequin hitbox(Tower tower) {
        Mannequin body = tower.hitbox();
        assertNotNull(body, tower.id() + " has a hitbox");
        return body;
    }

    private void respawn(Player player) {
        TestPlayer test = (TestPlayer) player;
        test.respawn();
        h.server.getPluginManager().callEvent(new PlayerPostRespawnEvent(player, player.getLocation(), false, false,
                false, PlayerRespawnEvent.RespawnReason.DEATH));
    }

    private long count(Class<? extends Entity> type) {
        return h.flatWorld().getEntities().stream().filter(type::isInstance).count();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void oneVersusOneTowersOrderShieldAndDamage() {
        SiegeMatch match = start(2);
        assertEquals(2, match.teams().size());
        assertEquals(1, match.teams().get(0).size());
        assertEquals(1, match.teams().get(1).size());
        assertEquals(0, match.attackingTeam(), "A attacks in the first half");
        Player attacker = member(match, 0);
        Player defender = member(match, 1);
        Participant participant = match.abilities().participant(attacker.getUniqueId());
        assertNotNull(participant);
        assertEquals("dummy", participant.abilityId());
        assertNotNull(participant.ability(), "bound in the fight");

        Tower front = tower(match, "front");
        Tower middle = tower(match, "middle");
        assertFalse(front.isShielded());
        assertTrue(middle.isShielded(), "only the front tower can be hit");
        assertEquals(600, front.maxHealth(), 1.0E-9, "one attacker: half the configured health");
        assertEquals(3, count(Mannequin.class), "one hitbox per tower");
        assertTrue(count(BlockDisplay.class) >= 6, "pillars and crystals");

        MatchContext ctx = ctx(match);
        assertTrue(ctx.isEnemy(attacker, hitbox(front)), "the front tower is the attackers' enemy");
        assertFalse(ctx.isEnemy(defender, hitbox(front)), "never the defenders'");
        assertFalse(ctx.isEnemy(attacker, hitbox(middle)), "a shielded tower is no target");
        assertTrue(ctx.enemiesNear(attacker, front.base(), 3).contains(hitbox(front)));

        ctx.damage(attacker, hitbox(front), 50);
        assertEquals(550, front.health(), 1.0E-6, "ability damage hits the tower");
        assertTrue(hitbox(front).getHealth() > 0, "the hitbox itself is never hurt");
        ctx.damage(defender, hitbox(front), 50);
        assertEquals(550, front.health(), 1.0E-6, "defenders cannot hurt their own tower");
        match.hitTower(middle, attacker, 100);
        assertEquals(middle.maxHealth(), middle.health(), 1.0E-6, "the shield holds");

        defender.teleport(front.base().add(2, 0, 0));
        assertEquals(0.2, match.defenderReduction(front), 1.0E-9);
        ctx.damage(attacker, hitbox(front), 50);
        assertEquals(510, front.health(), 1.0E-6, "a defender nearby: -20%");

        Location oldSpawn = match.spawnFor(defender.getUniqueId());
        ctx.damage(attacker, hitbox(front), 10_000);
        assertTrue(front.isDestroyed());
        assertNull(front.hitbox());
        assertEquals(15, match.score().points(0), "front tower: +15");
        assertFalse(middle.isShielded(), "the next tower opens");
        assertTrue(ctx.isEnemy(attacker, hitbox(middle)));
        Location newSpawn = match.spawnFor(defender.getUniqueId());
        assertTrue(newSpawn.getZ() > oldSpawn.getZ(), "the defenders' spawn moves back");
        assertEquals(match.layout().defenderSpawns(1).getFirst().getZ(), newSpawn.getZ(), 1.0E-9);
    }

    @Test
    void killsScoreAndPlayersRespawnWithProtection() {
        config.set(SiegeSettings.RESPAWN_SECONDS, 2);
        SiegeMatch match = start(2);
        Player attacker = member(match, 0);
        Player defender = member(match, 1);
        GameSession session = match.session();
        defender.setNoDamageTicks(0);
        ctx(match).damage(attacker, defender, 10_000);
        assertTrue(defender.isDead());
        assertEquals(2, match.score().points(0), "kill: +2");
        respawn(defender);
        assertTrue(session.isRespawning(defender.getUniqueId()));
        assertEquals(GameMode.SPECTATOR, defender.getGameMode());
        h.tickUntil(() -> session.isInPlay(defender), 100, "the respawn");
        assertEquals(GameMode.ADVENTURE, defender.getGameMode());
        Location spawn = match.spawnFor(defender.getUniqueId());
        assertEquals(0, defender.getLocation().distance(spawn), 1.0E-6, "back at the team's spawn");
        assertNotNull(match.abilities().participant(defender.getUniqueId()).ability(), "ability bound again");
        assertTrue(match.isProtected(defender), "spawn protection");
        double health = defender.getHealth();
        ctx(match).damage(attacker, defender, 4);
        assertEquals(health, defender.getHealth(), 1.0E-9, "protected players take no damage");
        ctx(match).damage(defender, attacker, 1);
        assertFalse(match.isProtected(defender), "attacking ends the protection");
    }

    @Test
    void aPlayerWhoDiedBeforeHalfTimeComesBackOnTheNewSide() {
        config.set(SiegeSettings.RESPAWN_SECONDS, 5);
        SiegeMatch match = start(2);
        Player attacker = member(match, 0);
        Player defender = member(match, 1);
        GameSession session = match.session();
        defender.setNoDamageTicks(0);
        ctx(match).damage(attacker, defender, 10_000);
        respawn(defender);
        defender.getInventory().clear();
        match.expireHalf();
        untilFight(2);
        h.tickUntil(() -> session.isInPlay(defender), 200, "the respawn");
        assertEquals(0, defender.getLocation().distance(match.spawnFor(defender.getUniqueId())), 1.0E-6);
        assertEquals(match.layout().attackerSpawns().getFirst().getZ(), defender.getLocation().getZ(), 1.0E-6,
                "B attacks now: back at the attackers' spawn");
        assertTrue(defender.getInventory().contains(org.bukkit.Material.IRON_SWORD), "the fresh kit of the half");
        assertNotNull(match.abilities().participant(defender.getUniqueId()).ability());
    }

    @Test
    void halfTimeSwapsSidesAndKeepsTheAbilities() {
        SiegeMatch match = start(2);
        Player a = member(match, 0);
        Player b = member(match, 1);
        String abilityA = match.abilities().participant(a.getUniqueId()).abilityId();
        ctx(match).damage(a, hitbox(tower(match, "front")), 100);
        match.abilities().setGauge(a, 50);
        match.expireHalf();
        assertEquals(SiegeMatch.Phase.HALFTIME, match.phase());
        assertTrue(match.towers().isEmpty(), "towers are gone during half time");
        assertNull(match.abilities().participant(a.getUniqueId()).ability(), "abilities revoked at half time");

        untilFight(2);
        assertEquals(1, match.attackingTeam(), "B attacks in the second half");
        assertEquals(abilityA, match.abilities().participant(a.getUniqueId()).abilityId(), "same ability");
        assertEquals(0, match.abilities().participant(a.getUniqueId()).gauge(), 1.0E-9, "gauge reset");
        Tower front = tower(match, "front");
        assertEquals(front.maxHealth(), front.health(), 1.0E-9, "towers restored");
        assertTrue(ctx(match).isEnemy(b, hitbox(front)));
        assertFalse(ctx(match).isEnemy(a, hitbox(front)));
    }

    @Test
    void bestOfThreeEndsWithTheTeamThatWonTwoRounds() {
        config.set(SiegeSettings.WIN_SCORE, 10);
        SiegeMatch match = start(2);
        GameSession session = match.session();
        MatchTeam teamA = match.teams().get(0);
        Player a = member(match, 0);
        ctx(match).damage(a, hitbox(tower(match, "front")), 10_000);
        assertEquals(SiegeMatch.Phase.BREAK, match.phase(), "15 points >= 10: round won at once");
        assertEquals(1, match.score().roundWins(0));
        assertTrue(match.towers().isEmpty());

        untilFight(1);
        assertEquals(2, match.round());
        assertEquals(0, match.score().points(0), "a new round starts at 0 points");
        ctx(match).damage(a, hitbox(tower(match, "front")), 10_000);
        assertFalse(session.isRunning(), "two rounds won: the match is over");
        GameResult result = session.result();
        assertNotNull(result);
        assertSame(teamA, result.winningTeam());
        assertEquals(a.getUniqueId(), result.mvp());

        h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
        assertEquals(0, count(Mannequin.class), "no hitbox left");
        assertEquals(0, count(BlockDisplay.class), "no tower display left");
    }

    @Test
    void aTowerThatHoldsIsADefenceSuccess() {
        config.set(SiegeSettings.DEFENCE_SECONDS, 10);
        SiegeMatch match = start(2);
        Tower front = tower(match, "front");
        ctx(match).damage(member(match, 0), hitbox(front), 300);
        double damaged = front.health();
        h.ticks(10 * 20 + 20);
        assertEquals(5, match.score().points(1), "defence success: +5 for the defenders");
        assertTrue(front.health() > damaged, "the tower heals");
    }

    @Test
    void theClimaxStartsInTheLastMinutes() {
        config.set(SiegeSettings.HALF_SECONDS, 60);
        config.set(SiegeSettings.CLIMAX_SECONDS, 30);
        SiegeMatch match = start(2);
        assertFalse(match.isClimax());
        h.tickUntil(match::isClimax, 40 * 20, "the climax");
        assertTrue(match.halfTicks() >= 30 * 20);
        ctx(match).damage(member(match, 0), hitbox(tower(match, "front")), 10_000);
        assertEquals(23, match.score().points(0), "15 x 1.5, rounded");
    }

    @Test
    void aTieAfterTheSecondHalfGoesToSuddenDeath() {
        SiegeMatch match = start(2);
        match.expireHalf();
        untilFight(2);
        match.expireHalf();
        assertTrue(match.isSuddenDeath(), "0 : 0, no towers, no kills");
        assertEquals(SiegeMatch.Phase.FIGHT, match.phase());
        ctx(match).damage(member(match, 1), hitbox(tower(match, "front")), 10_000);
        assertEquals(SiegeMatch.Phase.BREAK, match.phase(), "the first score wins the round");
        assertEquals(1, match.score().roundWins(1));
    }

    @Test
    void rerollDuringTheRoundPrep() {
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy", "probe"));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 30);
        h.join(2);
        try {
            h.hub.games().start(siege, null);
        } catch (GameStartException e) {
            throw new AssertionError(e);
        }
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().round() == 1, 400, "the round");
        SiegeMatch match = siege.currentMatch();
        assertEquals(SiegeMatch.Phase.PREP, match.phase());
        Player player = member(match, 0);
        String before = match.abilities().participant(player.getUniqueId()).abilityId();
        assertEquals(1, match.rerollsLeft(player));
        assertTrue(h.server.dispatchCommand(player, "게임 공성전 리롤"));
        String after = match.abilities().participant(player.getUniqueId()).abilityId();
        assertNotEquals(before, after);
        assertEquals(SiegeMatch.RerollResult.NONE_LEFT, match.reroll(player));
    }

    @Test
    void unevenAndBigTeamsScale() {
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy", "probe"));
        SiegeMatch match = start(5);
        int sizeA = match.teams().get(0).size();
        int sizeB = match.teams().get(1).size();
        assertEquals(5, sizeA + sizeB);
        assertEquals(1, Math.abs(sizeA - sizeB), "2 against 3");
        assertEquals(1200 * (0.25 + 0.25 * sizeA), tower(match, "front").maxHealth(), 1.0E-6,
                "tower health follows the attacker count");
        assertTeamsHaveUniqueAbilities(match);
    }

    @Test
    void fiveAgainstFiveSpawnsDoNotStack() {
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy", "probe"));
        SiegeMatch match = start(10);
        assertEquals(5, match.teams().get(0).size());
        assertEquals(5, match.teams().get(1).size());
        assertTeamsHaveUniqueAbilities(match);
        for (int team = 0; team < 2; team++) {
            Set<String> spots = new HashSet<>();
            for (UUID id : match.teams().get(team).members()) {
                Location at = match.spawnFor(id);
                spots.add(at.getBlockX() + "," + at.getBlockZ());
            }
            assertEquals(5, spots.size(), "five players, five different spawn blocks (three map spots)");
        }
        assertEquals(1200 * 1.5, tower(match, "front").maxHealth(), 1.0E-6);
    }

    private static void assertTeamsHaveUniqueAbilities(SiegeMatch match) {
        for (MatchTeam team : match.teams()) {
            Map<String, UUID> seen = new HashMap<>();
            for (UUID id : team.members()) {
                String ability = match.abilities().participant(id).abilityId();
                assertNotNull(ability);
                assertNull(seen.put(ability, id), "no ability twice in " + team.name());
            }
        }
    }

    @Test
    void theSiegeMapIsTheDefaultAndMustBeBuiltFirst() {
        siege.sitesOverride(null);
        h.join(2);
        assertThrows(GameStartException.class, () -> h.hub.games().start(siege, null),
                "the registered 협곡 요새 is not built yet");
        assertNull(h.hub.games().session());
        ArenaMap map = h.hub.maps().map(SiegeGame.MAP_ID);
        SiegeSites sites = assertInstanceOf(SiegeSites.class, map);
        SiegeLayout layout = new SiegeLayout(sites, h.flatWorld(), 0, 0);
        assertEquals(List.of("front", "middle", "core"),
                sites.towers().stream().map(SiegeSites.Tower::id).toList());
        assertTrue(layout.attackerSpawns().size() >= 3);
        assertEquals(3, sites.defenderSpawns().size());
    }

    @Test
    void abilityPoolCommand() {
        TestPlayer admin = h.join("Admin");
        admin.setOp(true);
        assertTrue(h.server.dispatchCommand(admin, "게임 공성전 능력풀 추가 화염술사"));
        assertTrue(siege.abilityPool().contains("pyro"));
        assertTrue(h.server.dispatchCommand(admin, "게임 공성전 능력풀 제거 pyro"));
        assertFalse(siege.abilityPool().contains("pyro"));
        h.server.dispatchCommand(admin, "게임 공성전 능력풀 추가 없는능력");
        assertEquals(List.of("dummy"), siege.abilityPool(), "unknown abilities are refused");
        assertTrue(h.server.dispatchCommand(admin, "게임 공성전 능력풀 초기화"));
        assertEquals(SiegeSettings.DEFAULT_POOL, siege.abilityPool());
        assertTrue(h.server.dispatchCommand(admin, "mcpl siege pool list"));
    }

    @Test
    void stoppingMidHalfLeavesNothingBehind() {
        SiegeMatch match = start(2);
        assertTrue(count(Mannequin.class) > 0);
        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "게임 중지"));
        h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
        assertFalse(match.session().isRunning());
        assertEquals(0, count(Mannequin.class));
        assertEquals(0, count(BlockDisplay.class));
    }
}
