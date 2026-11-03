package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.map.ArenaMap;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKingdomMap;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeSystems;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The lane systems (minions, jungle, gold, shop) run inside a 공성전 match, and the 장난감 왕국 is its map. */
class SiegeLanesWiringTest {

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
        config.set("minions.first-wave-seconds", 1);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private SiegeMatch start(int players) {
        h.join(players);
        try {
            h.hub.games().start(siege, null);
        } catch (GameStartException e) {
            throw new AssertionError(e);
        }
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT,
                2000, "the fight");
        return siege.currentMatch();
    }

    private static LaneSystemsModule lanes(SiegeMatch match) {
        for (SiegeModule module : match.modules()) {
            if (module instanceof LaneSystemsModule lanes) {
                return lanes;
            }
        }
        throw new AssertionError("no lane systems");
    }

    @Test
    void theToyKingdomIsTheRegisteredMapAndMustBeBuiltFirst() {
        siege.sitesOverride(null);
        h.join(2);
        assertThrows(GameStartException.class, () -> h.hub.games().start(siege, null),
                "the registered 장난감 왕국 is not built yet");
        assertNull(h.hub.games().session());
        ArenaMap map = h.hub.maps().map(SiegeGame.MAP_ID);
        LaneSites sites = assertInstanceOf(LaneSites.class, map);
        LaneLayout layout = new LaneLayout(sites, h.flatWorld(), 0, 0);
        for (LaneSites.Side side : LaneSites.Side.values()) {
            assertTrue(layout.spawns(side).size() >= 8);
            for (LaneSites.Lane lane : LaneSites.Lane.values()) {
                assertEquals(2, sites.towers(side, lane).size());
            }
        }
    }

    @Test
    void aMatchRunsOnTheToyKingdomSites() {
        siege.sitesOverride(new ToyKingdomMap());
        SiegeMatch match = start(2);
        assertInstanceOf(ToyKingdomMap.class, match.sites());
        assertEquals(10, match.towers().size());
        assertEquals(10, h.flatWorld().getEntitiesByClass(Mannequin.class).size());
        for (Tower tower : match.towers()) {
            assertNotNull(tower.bar(), tower.id());
        }
        Player a = match.session().hub().plugin().getServer().getPlayer(
                match.teams().get(0).members().iterator().next());
        LaneSites.Side side = match.side(a.getUniqueId());
        assertEquals(0, a.getLocation().distance(match.layout().spawns(side).getFirst()), 1.0);
    }

    @Test
    void minionsMarchWithSeparateOverheadBarsAndGoldIsTheMatchGold() {
        SiegeMatch match = start(2);
        SiegeSystems systems = match.systems();
        assertNotNull(systems, "the lane systems run in the match");
        assertTrue(match.economy().enabled(), "gold comes from the lane systems");
        Player a = match.session().hub().plugin().getServer().getPlayer(
                match.teams().get(0).members().iterator().next());
        assertEquals(systems.gold().balance(a.getUniqueId()), match.gold(a.getUniqueId()));
        match.addGold(a.getUniqueId(), 77, "test");
        assertEquals(systems.gold().balance(a.getUniqueId()), match.gold(a.getUniqueId()));

        MinionWaves minions = systems.minions();
        h.tickUntil(() -> !minions.minions().isEmpty(), 400, "the first wave");
        h.ticks(5);
        LaneSystemsModule lanes = lanes(match);
        Mob body = minions.minions().getFirst().body();
        assertTrue(match.abilities().context().isEnemy(a, body)
                == (minions.minions().getFirst().side() != match.side(a.getUniqueId())),
                "minions are enemies of the other side only");
        OverheadBar bar = lanes.bar(body);
        assertNotNull(bar, "a minion has an overhead bar");
        TextDisplay display = bar.display();
        assertNotNull(display);
        assertNull(display.getVehicle(), "a separate display, not a passenger of the mob");
        assertFalse(body.getPassengers().contains(display));
        Location over = body.getLocation();
        h.ticks(2);
        assertTrue(display.getLocation().getY() > over.getY() + body.getHeight(), "above the head");
        assertTrue(Math.abs(display.getLocation().getX() - body.getLocation().getX()) < 0.01, "it follows the mob");

        body.remove();
        h.ticks(2);
        assertNull(lanes.bar(body), "a gone minion's bar goes too");
    }

    @Test
    void towersShootMinionsFirst() {
        config.set(SiegeSettings.ATTACK_DAMAGE, 3);
        SiegeMatch match = start(2);
        SiegeSystems systems = match.systems();
        assertNotNull(systems);
        MinionWaves minions = systems.minions();
        h.tickUntil(() -> !minions.minions().isEmpty(), 400, "the first wave");
        MinionWaves.Minion minion = minions.minions().getFirst();
        Tower enemyOuter = SiegeMatchTest.structure(match, minion.side() == LaneSites.Side.BLUE ? LaneSites.Side.RED
                : LaneSites.Side.BLUE, Tower.Kind.OUTER, LaneSites.Lane.TOP);
        Mob body = minion.body();
        body.teleport(enemyOuter.base().add(3, 0, 0));
        double before = body.getHealth();
        h.ticks(10);
        assertTrue(!body.isValid() || body.getHealth() < before, "the tower shot the minion");
    }

    @Test
    void stoppingRemovesTheLaneCreaturesAndBars() {
        SiegeMatch match = start(2);
        SiegeSystems systems = match.systems();
        assertNotNull(systems);
        h.tickUntil(() -> !systems.minions().minions().isEmpty(), 400, "the first wave");
        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "게임 중지"));
        h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
        assertEquals(0, h.flatWorld().getEntitiesByClass(Mob.class).size());
        assertEquals(0, h.flatWorld().getEntitiesByClass(TextDisplay.class).size());
    }
}
