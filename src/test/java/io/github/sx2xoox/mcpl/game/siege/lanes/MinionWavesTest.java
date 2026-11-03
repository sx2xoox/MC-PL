package io.github.sx2xoox.mcpl.game.siege.lanes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureMover;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionKind;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

/** 공성전 v2 minion waves (docs/siege-v2.md §2). */
class MinionWavesTest extends LaneTestBase {

    @Override
    void configure(MemoryConfiguration config) {
        config.set("jungle.enabled", false);
        config.set("minions.first-wave-seconds", 0.05);
        config.set("gold.per-second", 0);
    }

    private MinionWaves minions() {
        return systems.minions();
    }

    private List<MinionWaves.Minion> of(LaneSites.Side side, MinionKind kind) {
        return minions().minions().stream().filter(m -> m.side() == side && m.kind() == kind).toList();
    }

    @Test
    void wavesSpawnOnBothLanesAndLeaveNothingBehind() {
        config.set("minions.interval-seconds", 1);
        start(1, 1);
        run(2);
        assertEquals(1, minions().wave());
        // 3 melee + 2 ranged, each side, each lane.
        assertEquals(12, count(Zombie.class));
        assertEquals(8, count(Skeleton.class));
        assertEquals(0, count(IronGolem.class));
        for (LaneSites.Lane lane : LaneSites.Lane.values()) {
            for (MinionWaves.Minion minion : minions().minions()) {
                if (minion.lane() == lane) {
                    double laneZ = (lane == LaneSites.Lane.TOP ? TestLaneSites.TOP_Z : TestLaneSites.BOTTOM_Z) + 0.5;
                    assertTrue(Math.abs(minion.body().getLocation().getZ() - laneZ) < 2.5, "spawned on its lane");
                }
            }
        }
        Mob melee = of(LaneSites.Side.BLUE, MinionKind.MELEE).getFirst().body();
        assertEquals(22, melee.getHealth(), 1.0E-6);
        assertTrue(minions().heldChunks() > 0, "lane chunks held");
        assertTrue(arena.tracked.containsAll(minions().bodies()), "minions tracked");

        // Waves 2 and 3: the third brings a cannon on every lane, health grows a little each wave.
        run(40);
        assertEquals(3, minions().wave());
        assertEquals(4, count(IronGolem.class));
        assertTrue(of(LaneSites.Side.RED, MinionKind.CANNON).getFirst().body().getHealth() > 60);

        systems.endRound();
        assertEquals(0, count(Zombie.class) + count(Skeleton.class) + count(IronGolem.class));
        assertTrue(minions().minions().isEmpty());
        assertEquals(0, minions().heldChunks(), "chunk tickets released");
        assertTrue(arena.tracked.isEmpty(), "nothing tracked is left");
    }

    @Test
    void biggerTeamsSendMoreMelee() {
        start(4, 2);
        run(2);
        // (4 − 1) / 3 = one extra melee per lane and side.
        assertEquals(4 * 4, count(Zombie.class));
    }

    @Test
    void minionsWalkTheirLaneTowardsTheEnemyBase() {
        start(1, 1);
        run(2);
        MinionWaves.Minion blue = of(LaneSites.Side.BLUE, MinionKind.MELEE).getFirst();
        MinionWaves.Minion red = of(LaneSites.Side.RED, MinionKind.MELEE).getFirst();
        double blueStart = blue.body().getLocation().getX();
        double redStart = red.body().getLocation().getX();
        run(120);
        assertTrue(blue.body().getLocation().getX() > blueStart + 10, "BLUE walks +x");
        assertTrue(red.body().getLocation().getX() < redStart - 10, "RED walks −x");
        assertTrue(blue.nextWaypoint() > 1, "waypoints reached");
        double laneZ = (blue.lane() == LaneSites.Lane.TOP ? TestLaneSites.TOP_Z : TestLaneSites.BOTTOM_Z) + 0.5;
        assertTrue(Math.abs(blue.body().getLocation().getZ() - laneZ) < 3, "stays on the lane");
    }

    @Test
    void aStuckMinionRepathsThenIsPutBackOnItsPath() {
        int[] stops = {0};
        mover = new CreatureMover() {
            @Override
            public void moveTo(Mob mob, Location to, double speed) {
                // Never gets anywhere.
            }

            @Override
            public void stop(Mob mob) {
                stops[0]++;
            }
        };
        config.set("minions.stuck-seconds", 1);
        start(1, 1);
        run(2);
        MinionWaves.Minion minion = of(LaneSites.Side.BLUE, MinionKind.MELEE).getFirst();
        Location start = minion.body().getLocation();
        run(30);
        assertTrue(stops[0] > 0, "re-pathed");
        run(30);
        Location goal = minion.path().get(1);
        assertTrue(minion.body().getLocation().distance(start) > 5, "put on its path");
        assertTrue(minion.body().getLocation().distance(goal) < 3, "at its next waypoint");
    }

    @Test
    void minionsFightEnemyMinionsBeforePlayersAndLeaveAlliesAlone() {
        start(1, 1);
        run(2);
        MinionWaves.Minion blue = of(LaneSites.Side.BLUE, MinionKind.MELEE).getFirst();
        Player redPlayer = player(LaneSites.Side.RED, 0);
        Player bluePlayer = player(LaneSites.Side.BLUE, 0);
        MinionWaves.Minion red = of(LaneSites.Side.RED, MinionKind.MELEE).stream()
                .filter(m -> m.lane() == blue.lane()).findFirst().orElseThrow();
        Location spot = blue.body().getLocation();
        red.body().teleport(spot.clone().add(1.5, 0, 0));
        redPlayer.teleport(spot.clone().add(-1.5, 0, 1));
        bluePlayer.teleport(spot.clone().add(0, 0, -1));
        run(6);
        assertSame(red.body(), blue.target(), "an enemy minion comes first");
        run(40);
        assertTrue(red.body().getHealth() < 22, "the enemy minion took hits");
        assertEquals(20, bluePlayer.getHealth(), 1.0E-6, "allies are never hit");
        // the skins play it: the attackers' attack motion, the victims' flinch; everyone hears the wind-up click
        assertTrue(skinEvents.contains("minion_melee attack"), skinEvents.toString());
        assertTrue(skinEvents.contains("minion_melee hit"), skinEvents.toString());
        assertTrue(arena.sounds.contains(Sound.BLOCK_TRIPWIRE_CLICK_ON), arena.sounds.toString());

        // Without enemy minions around, the enemy player is the target.
        for (MinionWaves.Minion other : of(LaneSites.Side.RED, MinionKind.MELEE)) {
            other.body().remove();
        }
        for (MinionWaves.Minion other : of(LaneSites.Side.RED, MinionKind.RANGED)) {
            other.body().remove();
        }
        run(40);
        assertTrue(redPlayer.getHealth() < 20, "the enemy player is hit");
    }

    @Test
    void minionsHitEnemyStructuresAndTowersShootMinionsFirst() {
        start(1, 1);
        run(2);
        MinionWaves.Minion blue = of(LaneSites.Side.BLUE, MinionKind.MELEE).getFirst();
        // Clear the way: no enemy minions or players.
        for (MinionWaves.Minion other : minions().minions()) {
            if (other.side() == LaneSites.Side.RED) {
                other.body().remove();
            }
        }
        player(LaneSites.Side.RED, 0).teleport(at(0, 80));
        Location front = blue.body().getLocation().add(2.5, 0, 0);
        Zombie tower = h.flatWorld().spawn(front, Zombie.class);
        tower.setAI(false);
        arena.structures.get(LaneSites.Side.RED).add(tower);
        run(60);
        assertTrue(arena.structureDamage.getOrDefault(tower, 0.0) > 0, "the minions hit the tower");
        assertEquals(tower.getAttribute(Attribute.MAX_HEALTH).getValue(), tower.getHealth(), 1.0E-6,
                "through the rules, not raw damage");

        Mob target = systems.towerTarget(LaneSites.Side.RED, front, 10);
        assertNotNull(target);
        assertSame(LaneSites.Side.BLUE, minions().minionOf(target).side());
        assertNull(systems.towerTarget(LaneSites.Side.BLUE, front, 10), "no RED minions left");
    }

    @Test
    void theLastHitEarnsGoldAndNearbyEnemiesTheExperience() {
        config.set("minions.interval-seconds", 1);
        start(2, 1);
        run(2);
        Player hitter = player(LaneSites.Side.BLUE, 0);
        Player farAway = player(LaneSites.Side.BLUE, 1);
        Player redPlayer = player(LaneSites.Side.RED, 0);
        MinionWaves.Minion red = of(LaneSites.Side.RED, MinionKind.MELEE).getFirst();
        hitter.teleport(red.body().getLocation().add(1, 0, 0));
        farAway.teleport(at(0, 80));
        int before = systems.gold().balance(hitter.getUniqueId());

        // Allies cannot hurt their own minions.
        MinionWaves.Minion own = of(LaneSites.Side.BLUE, MinionKind.MELEE).getFirst();
        melee(hitter, own.body(), 5);
        assertEquals(22, own.body().getHealth(), 1.0E-6);
        melee(redPlayer, own.body(), 5);
        assertEquals(17, own.body().getHealth(), 1.0E-6, "enemies can");

        melee(hitter, red.body(), 10);
        assertEquals(before, systems.gold().balance(hitter.getUniqueId()), "no gold before the kill");
        melee(hitter, red.body(), 100);
        assertTrue(red.body().isDead());
        assertEquals(before + 15, systems.gold().balance(hitter.getUniqueId()), "last-hit gold");
        assertEquals(6, arena.xp(hitter));
        assertEquals(0, arena.xp(farAway), "too far for the experience");

        // A minion that dies to another minion gives no gold.
        MinionWaves.Minion other = of(LaneSites.Side.RED, MinionKind.RANGED).getFirst();
        melee(hitter, other.body(), 5);
        int now = systems.gold().balance(hitter.getUniqueId());
        other.body().setHealth(0.5);
        other.body().teleport(own.body().getLocation().add(1, 0, 0));
        for (MinionWaves.Minion minion : minions().minions()) {
            if (minion != own && minion != other) {
                minion.body().remove();
            }
        }
        run(30);
        assertTrue(other.body().isDead(), "killed by the BLUE minion");
        assertEquals(now, systems.gold().balance(hitter.getUniqueId()));

        // A cannon pays more (the hitter waits out of the minions' reach meanwhile).
        hitter.teleport(at(0, 80));
        run(60);
        MinionWaves.Minion cannon = of(LaneSites.Side.RED, MinionKind.CANNON).getFirst();
        hitter.teleport(cannon.body().getLocation().add(1, 0, 0));
        int beforeCannon = systems.gold().balance(hitter.getUniqueId());
        melee(hitter, cannon.body(), 1000);
        assertEquals(beforeCannon + 40, systems.gold().balance(hitter.getUniqueId()));
    }

    @Test
    void theDragonBuffEmpowersTheNextWaves() {
        start(1, 1);
        run(2);
        double normal = of(LaneSites.Side.BLUE, MinionKind.MELEE).getFirst().body().getHealth();
        systems.endRound();
        systems.beginRound();
        minions().empower(LaneSites.Side.BLUE, 2000);
        run(2);
        assertTrue(minions().empowered(LaneSites.Side.BLUE));
        assertFalse(minions().empowered(LaneSites.Side.RED));
        assertEquals(normal * 1.25, of(LaneSites.Side.BLUE, MinionKind.MELEE).getFirst().body().getHealth(), 1.0E-6);
        assertEquals(normal, of(LaneSites.Side.RED, MinionKind.MELEE).getFirst().body().getHealth(), 1.0E-6);
        assertEquals(2.5 * 1.25, of(LaneSites.Side.BLUE, MinionKind.MELEE).getFirst().damage(), 1.0E-6);
    }
}
