package io.github.sx2xoox.mcpl.game.siege.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.FallbackLaneSites;
import io.github.sx2xoox.mcpl.game.siege.SiegeGame;
import io.github.sx2xoox.mcpl.game.siege.SiegeMatch;
import io.github.sx2xoox.mcpl.game.siege.SiegeModule;
import io.github.sx2xoox.mcpl.game.siege.SiegeSettings;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeSystems;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import io.github.sx2xoox.mcpl.game.siege.tower.Footprint;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.WanderingTrader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 공성전 봇전 in a real match on the fallback lanes: bots fill the teams, fight by the rules, score and are cleaned up. */
class SiegeBotMatchTest {

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
        siege.sitesOverride(new FallbackLaneSites((int) FlatWorld.SURFACE));
        config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy"));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        config.set(SiegeSettings.ATTACK_DAMAGE, 0);
        config.set(SiegeSettings.RESPAWN_SECONDS, 2);
        config.set("minions.first-wave-seconds", 120);
        // Knights only: melee hits always land, so the tests do not depend on the ranged aim roll.
        config.set("bots.jobs", List.of("knight"));
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private SiegeMatch start(int players, int perSide) {
        h.join(players);
        siege.requestBots(new SiegeBots.Setup(siege.botSettings(), perSide, SiegeBotDifficulty.NORMAL));
        try {
            h.hub.games().start(siege, null);
        } catch (GameStartException e) {
            throw new AssertionError(e);
        }
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT,
                2000, "the fight");
        return siege.currentMatch();
    }

    private static SiegeBots bots(SiegeMatch match) {
        for (SiegeModule module : match.modules()) {
            if (module instanceof SiegeBots bots) {
                return bots;
            }
        }
        throw new AssertionError("no bots module");
    }

    private static Player player(SiegeMatch match) {
        for (Player player : match.session().participants()) {
            return player;
        }
        throw new AssertionError("no player");
    }

    /** A bot of {@code side}. */
    private static UUID bot(SiegeMatch match, LaneSites.Side side) {
        for (UUID id : bots(match).ids()) {
            if (match.side(id) == side) {
                return id;
            }
        }
        throw new AssertionError("no bot on " + side);
    }

    private static LaneSites.Side enemy(LaneSites.Side side) {
        return side == LaneSites.Side.BLUE ? LaneSites.Side.RED : LaneSites.Side.BLUE;
    }

    private static Tower structure(SiegeMatch match, LaneSites.Side owner, Tower.Kind kind, LaneSites.Lane lane) {
        for (Tower tower : match.towers(owner)) {
            if (tower.kind() == kind && (tower.lane() == lane || kind == Tower.Kind.CORE)) {
                return tower;
            }
        }
        throw new AssertionError("no " + kind);
    }

    @Test
    void botsFillBothTeamsAndWalkTheirLanes() {
        SiegeMatch match = start(1, 2);
        GameSession session = match.session();
        SiegeBots bots = bots(match);
        assertEquals(3, bots.ids().size(), "one player + three bots make two against two");
        assertEquals(2, match.teams().get(0).size());
        assertEquals(2, match.teams().get(1).size());
        assertFalse(match.isSolo(), "bots fill the empty team");
        for (UUID id : bots.ids()) {
            assertTrue(session.isBot(id));
            assertNull(match.abilities().participant(id), "bots get no superpower");
            assertNotNull(bots.body(id), "a body at the start");
            assertTrue(bots.body(id).getLocation().distance(match.spawnFor(id)) < 2, "at its base");
            String plate = PlainTextComponentSerializer.plainText().serialize(bots.plateText(id));
            assertTrue(plate.contains(session.plainName(id)) && plate.contains("기사"), plate);
            assertTrue(plate.contains("❤"), plate);
        }
        assertEquals(3, h.flatWorld().getEntitiesByClass(WanderingTrader.class).size(), "one pair of legs each");
        UUID walker = bots.ids().getFirst();
        Location start = bots.body(walker).getLocation();
        h.ticks(200);
        Mannequin body = bots.body(walker);
        assertNotNull(body);
        assertTrue(body.getLocation().distance(start) > 4, "it left its base for its lane");
        assertTrue(body.getLocation().distance(bots.legs(walker).getLocation()) < 0.01, "the body follows the legs");
    }

    @Test
    void botsAreTheEnemiesOfTheOtherSideOnly() {
        SiegeMatch match = start(1, 2);
        Player player = player(match);
        LaneSites.Side side = match.side(player.getUniqueId());
        MatchContext ctx = match.abilities().context();
        Mannequin foe = bots(match).body(bot(match, enemy(side)));
        Mannequin mate = bots(match).body(bot(match, side));
        assertTrue(ctx.isEnemy(player, foe));
        assertFalse(ctx.isEnemy(player, mate));
        double before = mate.getHealth();
        ctx.damage(player, mate, 5);
        assertEquals(before, mate.getHealth(), 1.0E-9, "a teammate bot takes no damage");
    }

    @Test
    void aPlayerKillingABotGetsTheCreditAndTheBotRespawns() {
        SiegeMatch match = start(1, 2);
        Player player = player(match);
        int team = match.teamIndex(player.getUniqueId());
        LaneSites.Side side = match.side(player.getUniqueId());
        SiegeBots bots = bots(match);
        UUID foe = bot(match, enemy(side));
        int gold = match.gold(player.getUniqueId());
        match.abilities().context().damage(player, bots.body(foe), 1000);
        assertTrue(bots.isDead(foe), "the bot died");
        assertEquals(2, match.score().points(team), "a kill: +2");
        assertEquals(1, match.score().kills(team));
        assertTrue(match.gold(player.getUniqueId()) >= gold + 150, "kill gold");
        assertEquals(1, match.session().stats().of(player.getUniqueId()).kills());
        assertEquals(1, match.session().stats().of(foe).deaths());
        assertEquals(1, match.score().roundDeaths(foe));
        h.tickUntil(() -> !bots.isDead(foe), 200, "the respawn");
        assertTrue(bots.body(foe).getLocation().distance(match.spawnFor(foe)) < 2, "back at its base");
        assertTrue(bots.isProtected(foe), "spawn protection like a player");
        assertFalse(match.abilities().context().isEnemy(player, bots.body(foe)), "not hittable while protected");
    }

    @Test
    void aBotKillingAPlayerScoresForItsTeam() {
        SiegeMatch match = start(1, 2);
        Player player = player(match);
        LaneSites.Side side = match.side(player.getUniqueId());
        SiegeBots bots = bots(match);
        UUID foe = bot(match, enemy(side));
        int foeTeam = match.teamIndex(foe);
        UUID mate = bot(match, side);
        player.teleport(bots.body(foe).getLocation().add(1, 0, 0));
        player.setHealth(1);
        player.setNoDamageTicks(0);
        bots.attack(foe, player);
        assertTrue(player.isDead() || player.getHealth() <= 0, "the knight's hit killed the player");
        assertEquals(2, match.score().points(foeTeam), "kill points for the bot's team");
        assertEquals(1, match.session().stats().of(foe).kills());
        assertEquals(1, match.score().roundDeaths(player.getUniqueId()));

        // A bot killing a bot scores too.
        Mannequin victim = bots.body(mate);
        victim.setHealth(1);
        victim.setNoDamageTicks(0);
        bots.attack(foe, victim);
        assertTrue(bots.isDead(mate));
        assertEquals(4, match.score().points(foeTeam));
        assertEquals(2, match.session().stats().of(foe).kills());
    }

    @Test
    void aBotsLaneGoesRoundTheBuildingsItRunsThrough() {
        SiegeMatch match = start(1, 2);
        SiegeBots bots = bots(match);
        h.ticks(2);
        for (UUID id : bots.ids()) {
            List<Location> lane = bots.lanePoints(id);
            assertTrue(lane.size() >= 2);
            for (Tower tower : match.towers()) {
                // the map's lane runs through the towers; the bot's keeps a body and some room off their walls
                Footprint.Box walls = tower.keepOut(1.0);
                if (walls == null) {
                    continue;
                }
                for (int i = 1; i < lane.size(); i++) {
                    Location a = lane.get(i - 1);
                    Location b = lane.get(i);
                    assertFalse(walls.crosses(a.getX(), a.getZ(), b.getX(), b.getZ()),
                            "the lane of " + match.side(id) + " runs through " + tower.id() + " at " + a.toVector());
                }
            }
        }
    }

    @Test
    void botsWalkRoundTheBuildingsAndSiegeThemFromOutside() {
        SiegeMatch match = start(1, 2);
        SiegeBots bots = bots(match);
        double nearest = Double.MAX_VALUE;
        for (int tick = 0; tick < 1200; tick++) {
            h.ticks(1);
            for (UUID id : bots.ids()) {
                Mannequin body = bots.body(id);
                if (body == null || !body.isValid()) {
                    continue;
                }
                Location at = body.getLocation();
                for (Tower tower : match.towers()) {
                    Footprint.Box walls = tower.keepOut(0);
                    if (walls == null) {
                        continue;
                    }
                    assertFalse(walls.contains(at.getX(), at.getZ()),
                            "bot at " + at.toVector() + " inside " + tower.id() + " (tick " + tick + ")");
                    if (tower.owner() != match.side(id)) {
                        nearest = Math.min(nearest, walls.distance(at.getX(), at.getZ()));
                    }
                }
            }
        }
        boolean sieged = match.towers().stream().anyMatch(t -> t.health() < t.maxHealth());
        assertTrue(!sieged || nearest < 3, "a bot that hit a building stood by its walls: " + nearest);
    }

    @Test
    void botsHitStructuresOnlyThroughTheRulesAndNotWhileShielded() {
        SiegeMatch match = start(1, 2);
        SiegeBots bots = bots(match);
        UUID id = bots.ids().getFirst();
        LaneSites.Side enemySide = enemy(match.side(id));
        Tower inner = structure(match, enemySide, Tower.Kind.INNER, LaneSites.Lane.TOP);
        Tower outer = structure(match, enemySide, Tower.Kind.OUTER, LaneSites.Lane.TOP);
        assertTrue(inner.isShielded());
        bots.attack(id, inner.hitbox());
        assertEquals(inner.maxHealth(), inner.health(), 1.0E-9, "a shielded tower takes nothing");
        bots.attack(id, outer.hitbox());
        assertTrue(outer.health() < outer.maxHealth(), "an outer tower takes the hit");
        assertNotNull(outer.topDamager());
        assertEquals(id, outer.topDamager(), "credited to the bot");
        Tower own = structure(match, match.side(id), Tower.Kind.OUTER, LaneSites.Lane.TOP);
        bots.attack(id, own.hitbox());
        assertEquals(own.maxHealth(), own.health(), 1.0E-9, "never its own");
    }

    @Test
    void towersAndMinionsFightBots() {
        config.set(SiegeSettings.ATTACK_DAMAGE, 3);
        SiegeMatch match = start(1, 2);
        SiegeBots bots = bots(match);
        UUID id = bot(match, LaneSites.Side.BLUE);
        Tower redOuter = structure(match, LaneSites.Side.RED, Tower.Kind.OUTER, LaneSites.Lane.TOP);
        bots.legs(id).teleport(redOuter.base().add(4, 0, 0));
        h.ticks(1);
        double before = bots.body(id).getHealth();
        h.ticks(12);
        assertTrue(bots.isDead(id) || bots.body(id).getHealth() < before, "the enemy tower shot the bot");

        SiegeSystems systems = match.systems();
        assertNotNull(systems);
        systems.minions().spawnWave();
        h.ticks(1);
        MinionWaves.Minion red = null;
        for (MinionWaves.Minion minion : systems.minions().minions()) {
            if (minion.side() == LaneSites.Side.RED) {
                red = minion;
                break;
            }
        }
        assertNotNull(red);
        UUID other = null;
        for (UUID candidate : bots.ids()) {
            if (match.side(candidate) == LaneSites.Side.BLUE && !bots.isDead(candidate)) {
                other = candidate;
            }
        }
        assertNotNull(other, "a live blue bot");
        UUID blue = other;
        Mob minionBody = red.body();
        bots.legs(blue).teleport(minionBody.getLocation().add(2, 0, 0));
        h.ticks(1);
        Mannequin target = bots.body(blue);
        MinionWaves.Minion watched = red;
        h.tickUntil(() -> watched.target() == target || bots.isDead(blue) || !minionBody.isValid(), 40,
                "the minion to pick the bot");
        assertTrue(watched.target() == target || bots.isDead(blue) || !minionBody.isValid());
    }

    @Test
    void botsLeaveBetweenRoundsAndComeBackFreshAtTheirBase() {
        config.set(SiegeSettings.ROUND_BREAK_SECONDS, 1);
        SiegeMatch match = start(1, 2);
        SiegeBots bots = bots(match);
        UUID id = bots.ids().getFirst();
        h.ticks(100);
        match.score().addPoints(0, 1, null);
        match.expireRound();
        assertEquals(SiegeMatch.Phase.BREAK, match.phase());
        assertNull(bots.body(id), "no bot stays on the field between rounds");
        assertEquals(0, h.flatWorld().getEntitiesByClass(WanderingTrader.class).size());
        h.tickUntil(() -> match.round() == 2 && match.phase() == SiegeMatch.Phase.FIGHT, 2000, "round 2");
        assertNotNull(bots.body(id));
        assertEquals(1, match.level(id), "levels start over");
        assertEquals(bots.settings().stats(BotJob.KNIGHT).health(), bots.body(id).getHealth(), 1.0E-9);
        assertEquals(3, h.flatWorld().getEntitiesByClass(WanderingTrader.class).size());
    }

    @Test
    void stoppingRemovesEveryBotEntity() {
        SiegeMatch match = start(1, 3);
        SiegeBots bots = bots(match);
        h.ticks(60);
        assertTrue(bots.heldChunks() >= 0);
        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "게임 중지"));
        h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
        h.ticks(40);
        assertEquals(0, h.flatWorld().getEntitiesByClass(Mannequin.class).size());
        assertEquals(0, h.flatWorld().getEntitiesByClass(WanderingTrader.class).size());
        assertEquals(0, h.flatWorld().getEntitiesByClass(TextDisplay.class).size());
        assertEquals(0, bots.heldChunks());
    }

    @Test
    void theCommandStartsABotMatchAndAPlainStartHasNoBots() {
        h.join(1);
        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "게임 시작 공성전 봇전 2 쉬움"));
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT,
                2000, "the fight");
        SiegeMatch match = siege.currentMatch();
        assertEquals(3, match.session().botIds().size());
        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "게임 중지"));
        h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
        try {
            h.hub.games().start(siege, null);
        } catch (GameStartException e) {
            throw new AssertionError(e);
        }
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT,
                2000, "the second fight");
        assertTrue(siege.currentMatch().session().botIds().isEmpty(), "an ordinary start has no bots");
    }

    @Test
    void botOptionsParse() {
        SiegeBotOptions options = SiegeBotOptions.parse("어려움 4");
        assertEquals(4, options.perSide());
        assertEquals(SiegeBotDifficulty.HARD, options.difficulty());
        assertNull(options.error());
        assertEquals("abc", SiegeBotOptions.parse("3 abc").error());
        assertEquals("5", SiegeBotOptions.parse("3 5").error(), "one size only");
        SiegeBots.Setup setup = SiegeBotOptions.parse("").setup(SiegeBotSettings.defaults());
        assertEquals(3, setup.perSide());
        assertEquals(SiegeBotDifficulty.NORMAL, setup.difficulty());
        assertEquals(SiegeBotSettings.MAX_PER_SIDE, SiegeBotOptions.parse("99").setup(SiegeBotSettings.defaults())
                .perSide());
    }
}
