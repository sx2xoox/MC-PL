package io.github.sx2xoox.mcpl.game.siege.lanes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.minion.Formation;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionKind;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * Two waves meet in the middle of a lane and fight in formation (docs/siege-v2.md §2, 대열): everyone stays within the
 * lane's width, melee face the enemy they fight from just in front of it and share out the enemy's front, ranged
 * stand behind their own melee; bodies do not push each other and minion hits knock no minion back.
 */
class MinionFormationTest extends LaneTestBase {

    @Override
    void configure(MemoryConfiguration config) {
        config.set("jungle.enabled", false);
        config.set("minions.first-wave-seconds", 0.05);
        config.set("minions.interval-seconds", 600);
        config.set("gold.per-second", 0);
    }

    private static double laneZ(LaneSites.Lane lane) {
        return (lane == LaneSites.Lane.TOP ? TestLaneSites.TOP_Z : TestLaneSites.BOTTOM_Z) + 0.5;
    }

    /** How far along its own walking direction a minion is (BLUE walks +x, RED −x). */
    private static double ahead(MinionWaves.Minion minion) {
        double x = minion.body().getLocation().getX();
        return minion.side() == LaneSites.Side.BLUE ? x : -x;
    }

    @Test
    void wavesMarchAndFightInFormation() {
        start(1, 1);
        // The players watch from far away (no minion fights them).
        for (LaneSites.Side side : LaneSites.Side.values()) {
            player(side, 0).teleport(at(0, 70));
        }
        run(2);
        double edge = Formation.Settings.DEFAULTS.maxLateral() + 1.0;
        double maxLateral = 0;
        int facingChecks = 0;
        int rangedChecks = 0;
        int shared = 0;
        boolean fought = false;
        Map<LivingEntity, Location> last = new HashMap<>();
        Map<LivingEntity, Integer> stillSince = new HashMap<>();
        for (int tick = 0; tick < 700; tick++) {
            run(1);
            List<MinionWaves.Minion> all = systems.minions().minions();
            for (MinionWaves.Minion minion : all) {
                Location now = minion.body().getLocation();
                Location was = last.put(minion.body(), now);
                if (was == null || flat(was, now) > 1.0E-6) {
                    stillSince.put(minion.body(), tick);
                }
            }
            for (MinionWaves.Minion minion : all) {
                Location at = minion.body().getLocation();
                double lateral = Math.abs(at.getZ() - laneZ(minion.lane()));
                maxLateral = Math.max(maxLateral, lateral);
                String who = minion.side() + " " + minion.lane() + " " + minion.kind() + " tick " + tick;
                assertTrue(lateral <= edge, who + " left the lane's width: " + lateral);
                LivingEntity target = minion.target();
                if (target == null) {
                    continue;
                }
                fought = true;
                MinionWaves.Minion enemy = systems.minions().minionOf(target);
                assertTrue(enemy == null || enemy.side() != minion.side(), who + " fights an ally");
                double distance = Math.sqrt(flat(at, target.getLocation()));
                // Both standing a while (every minion thinks every 5 ticks): the fight has settled.
                boolean settled = enemy != null && tick - stillSince.getOrDefault(minion.body(), tick) > 6
                        && tick - stillSince.getOrDefault(target, tick) > 6;
                if (minion.kind() == MinionKind.MELEE && settled && distance <= 2.2) {
                    // In reach: it faces the enemy, from in front of it (the enemy is ahead on its way).
                    Vector looks = at.getDirection().setY(0).normalize();
                    Vector to = target.getLocation().toVector().subtract(at.toVector()).setY(0).normalize();
                    assertTrue(looks.dot(to) > 0.9, who + " looks " + looks + " but fights towards " + to);
                    double gap = minion.side() == LaneSites.Side.BLUE
                            ? target.getLocation().getX() - at.getX() : at.getX() - target.getLocation().getX();
                    assertTrue(gap > 0.3, who + " fights from beside or behind the enemy: " + gap);
                    facingChecks++;
                }
                if (minion.kind() == MinionKind.RANGED && enemy != null) {
                    double front = all.stream().filter(m -> m.side() == minion.side() && m.lane() == minion.lane()
                            && m.kind() == MinionKind.MELEE).mapToDouble(MinionFormationTest::ahead).max()
                            .orElse(Double.NaN);
                    if (!Double.isNaN(front)) {
                        assertTrue(ahead(minion) < front + 0.5, who + " stands in front of its melee");
                        rangedChecks++;
                    }
                }
            }
            for (LaneSites.Side side : LaneSites.Side.values()) {
                for (LaneSites.Lane lane : LaneSites.Lane.values()) {
                    Set<LivingEntity> targets = new HashSet<>();
                    int fighting = 0;
                    for (MinionWaves.Minion minion : all) {
                        if (minion.side() == side && minion.lane() == lane && minion.kind() == MinionKind.MELEE
                                && systems.minions().minionOf(minion.target()) != null) {
                            targets.add(minion.target());
                            fighting++;
                        }
                    }
                    if (fighting >= 3 && targets.size() >= 2) {
                        shared++;
                    }
                }
            }
        }
        assertTrue(fought, "the waves met");
        assertTrue(facingChecks > 100, "melee fought face to face: " + facingChecks);
        assertTrue(rangedChecks > 50, "ranged fought from behind: " + rangedChecks);
        assertTrue(shared > 20, "three melee on more than one enemy: " + shared);
        System.out.println("formation: widest lateral " + maxLateral + ", face-to-face checks " + facingChecks);
    }

    @Test
    void minionBodiesDoNotPushAndTheirHitsKnockNoMinionBack() {
        start(1, 1);
        run(2);
        MinionWaves.Minion blue = systems.minions().minions().stream()
                .filter(m -> m.side() == LaneSites.Side.BLUE && m.kind() == MinionKind.MELEE).findFirst()
                .orElseThrow();
        assertFalse(blue.body().isCollidable(), "no pushing between bodies");
        MinionWaves.Minion red = systems.minions().minions().stream()
                .filter(m -> m.side() == LaneSites.Side.RED && m.kind() == MinionKind.MELEE
                        && m.lane() == blue.lane()).findFirst().orElseThrow();
        red.body().teleport(blue.body().getLocation().add(1.5, 0, 0));
        int[] knocks = {0, 0};
        org.bukkit.event.Listener probe = new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void onKnock(EntityKnockbackEvent event) {
                knocks[event.isCancelled() ? 0 : 1]++;
            }
        };
        Bukkit.getPluginManager().registerEvents(probe, h.hub.plugin());
        // The server knocks the victim back from inside the hit: simulate that.
        org.bukkit.event.Listener knocker = new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR,
                    ignoreCancelled = true)
            public void onDamage(org.bukkit.event.entity.EntityDamageEvent event) {
                if (systems.minions().minionOf(event.getEntity()) != null) {
                    Bukkit.getPluginManager().callEvent(new EntityKnockbackEvent(event.getEntity(),
                            EntityKnockbackEvent.Cause.ENTITY_ATTACK, new Vector(0.4, 0.1, 0)));
                }
            }
        };
        Bukkit.getPluginManager().registerEvents(knocker, h.hub.plugin());
        try {
            run(40);
            assertTrue(knocks[0] > 0, "minion hits knocked minions back");
            assertEquals(0, knocks[1], "a minion hit knocked a minion back");
            // A player's hit still does.
            Player redPlayer = player(LaneSites.Side.RED, 0);
            melee(redPlayer, blue.body(), 1);
            assertEquals(1, knocks[1], "a player's hit pushes as always");
        } finally {
            org.bukkit.event.HandlerList.unregisterAll(probe);
            org.bukkit.event.HandlerList.unregisterAll(knocker);
        }
    }

    private static double flat(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
