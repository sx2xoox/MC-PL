package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKingdomMap;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureMover;
import io.github.sx2xoox.mcpl.game.siege.creature.GridPathfinders;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionKind;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import io.github.sx2xoox.mcpl.game.siege.tower.Footprint;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Minion waves march past the towers on their lanes (the lanes run through them) without getting caught on their
 * barrier walls: both sides, both lanes, the cannon included, past standing towers and past ruins, on the fallback lanes
 * and on the 장난감 왕국's, walked by the stepping mover of a test server and by a grid pathfinder like vanilla's
 * ({@link GridPathfinders}: two-block nodes for the wide bodies, bodies that rub at corners).
 */
class MinionTowerPassageTest {

    /** A marching minion that has not moved half a block for this long is caught (the stuck rescue is 60). */
    private static final int STALL_TICKS = 40;

    private MatchHarness h;
    private SiegeGame siege;
    private final GridPathfinders grid = new GridPathfinders();

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
        h.registerDummy();
        h.registerTestAbilities();
        h.settings().set("ultimate.per-second", 0);
        siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
        ConfigurationSection config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy"));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        config.set(SiegeSettings.ATTACK_DAMAGE, 0);
        config.set("minions.first-wave-seconds", 1);
        config.set("minions.interval-seconds", 600);
        config.set("minions.cannon-every", 1);
        // pure marching: nothing to fight on the way (the waves pass through each other)
        config.set("minions.aggro-radius", 0);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("fallback", false, false),
                Arguments.of("fallback", true, false),
                Arguments.of("fallback", false, true),
                Arguments.of("fallback", true, true),
                Arguments.of("toy", false, false),
                Arguments.of("toy", true, false),
                Arguments.of("toy", true, true));
    }

    @ParameterizedTest(name = "{0} map, grid pathfinder {1}, ruins {2}")
    @MethodSource("cases")
    void wavesMarchPastEveryTowerWithoutGettingCaught(String map, boolean pathfinder, boolean ruins) {
        siege.sitesOverride(map.equals("toy") ? new ToyKingdomMap() : new FallbackLaneSites((int) FlatWorld.SURFACE));
        if (pathfinder) {
            siege.moverOverride(walls -> CreatureMover.pathfinder(walls, grid::of));
        }
        h.join(2);
        try {
            h.hub.games().start(siege, null);
        } catch (GameStartException e) {
            throw new AssertionError(e);
        }
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT,
                2000, "the fight");
        SiegeMatch match = siege.currentMatch();
        for (Player player : h.server.getOnlinePlayers()) {
            player.teleport(new Location(player.getWorld(), 0, FlatWorld.SURFACE, 400));
        }
        if (ruins) {
            for (Tower.Kind kind : List.of(Tower.Kind.OUTER, Tower.Kind.INNER)) {
                for (Tower tower : match.towers()) {
                    if (tower.kind() == kind) {
                        LaneSites.Side by = tower.owner() == LaneSites.Side.BLUE ? LaneSites.Side.RED
                                : LaneSites.Side.BLUE;
                        match.damageStructure(tower, by, tower.maxHealth() * 10, null);
                        assertTrue(tower.isDestroyed(), tower.id());
                    }
                }
            }
        }
        MinionWaves minions = match.systems().minions();
        h.tickUntil(() -> !minions.minions().isEmpty(), 400, "the first wave");

        List<MinionWaves.Minion> wave = new ArrayList<>(minions.minions());
        Set<MinionKind> kinds = EnumSet.noneOf(MinionKind.class);
        Set<String> lanes = new java.util.HashSet<>();
        for (MinionWaves.Minion minion : wave) {
            kinds.add(minion.kind());
            lanes.add(minion.side() + " " + minion.lane());
        }
        assertEquals(EnumSet.allOf(MinionKind.class), kinds, "the cannon marches too");
        assertEquals(4, lanes.size(), "both sides on both lanes");
        Map<MinionWaves.Minion, Location> anchor = new HashMap<>();
        Map<MinionWaves.Minion, Integer> since = new HashMap<>();
        Set<MinionWaves.Minion> arrived = new java.util.HashSet<>();
        for (int tick = 0; tick < 2400 && arrived.size() < wave.size(); tick++) {
            h.ticks(1);
            if (pathfinder) {
                grid.tick();
            }
            for (MinionWaves.Minion minion : wave) {
                assertTrue(minion.body().isValid(), minion.side() + " " + minion.kind() + " died on the march");
                Location at = minion.body().getLocation();
                String who = minion.side() + " " + minion.lane() + " " + minion.kind() + " at "
                        + String.format("%.2f %.2f", at.getX(), at.getZ()) + " (tick " + tick + ")";
                double half = minion.body().getWidth() / 2;
                for (Tower tower : match.towers()) {
                    Footprint.Box walls = tower.keepOut(Math.max(0, half - 0.05));
                    assertFalse(walls != null && walls.contains(at.getX(), at.getZ()),
                            who + " in the walls of " + tower.id());
                }
                Location end = minion.path().getLast();
                if (Math.hypot(at.getX() - end.getX(), at.getZ() - end.getZ()) < 6) {
                    arrived.add(minion);
                }
                if (arrived.contains(minion)) {
                    continue;
                }
                Location from = anchor.get(minion);
                if (from == null || Math.hypot(at.getX() - from.getX(), at.getZ() - from.getZ()) >= 0.5) {
                    anchor.put(minion, at);
                    since.put(minion, tick);
                } else if (tick - since.get(minion) > STALL_TICKS) {
                    fail(who + " has stood still since tick " + since.get(minion) + nearest(match, at) + " path "
                            + minion.path().stream().map(l -> String.format("(%.1f %.1f)", l.getX(), l.getZ()))
                            .toList() + " next " + minion.nextWaypoint());
                }
            }
        }
        assertEquals(wave.size(), arrived.size(), "every minion marched to the end of its lane");
    }

    private static String nearest(SiegeMatch match, Location at) {
        String out = "";
        double best = Double.MAX_VALUE;
        for (Tower tower : match.towers()) {
            Footprint.Box walls = tower.keepOut(0);
            if (walls != null && walls.distance(at.getX(), at.getZ()) < best) {
                best = walls.distance(at.getX(), at.getZ());
                out = ", " + String.format("%.2f", best) + " from the walls of " + tower.id();
            }
        }
        return out;
    }
}
