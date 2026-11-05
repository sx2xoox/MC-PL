package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;

/**
 * The 20 abilities that joined the 공성전 pool with the 70 (minotaur … sonic) run in a real 공성전 match: the active,
 * the ultimate and the basic attack (평타) against a player, a minion and an enemy tower, without a single error or
 * warning; buildings keep their place and size, and when the match stops every block it touched is back.
 */
class SiegeNewAbilitiesSmokeTest {

    static final List<String> ADDED = List.of("minotaur", "medusa", "odin", "wukong", "ra", "gumiho", "hydra", "titan",
            "bear", "octopus", "turtle", "monk", "kiwarrior", "magicalgirl", "tengu", "tennis", "druid", "shaman",
            "crystal", "sonic");

    static Stream<String> added() {
        return ADDED.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("added")
    void skillUltimateAndBasicAttackRunInSiege(String id) {
        assertTrue(SiegeSettings.DEFAULT_POOL.contains(id), id + " is in the default pool");
        MatchHarness h = MatchHarness.flat();
        Logger logger = h.hub.plugin().getLogger();
        List<LogRecord> problems = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue() && !mockGap(record.getThrown())) {
                    problems.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            h.registerDummy();
            h.settings().set("ultimate.per-second", 0);
            SiegeGame siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
            siege.sitesOverride(new FallbackLaneSites((int) FlatWorld.SURFACE));
            ConfigurationSection config = siege.config();
            config.set(SiegeSettings.ABILITY_POOL, List.of("dummy"));
            config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
            config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
            config.set(SiegeSettings.ATTACK_DAMAGE, 0);
            config.set(SiegeSettings.PROTECTION_SECONDS, 0);
            config.set("minions.first-wave-seconds", 1);
            // the minions fight on but do not wear the players down: the test watches the skills, not survival
            for (String kind : List.of("melee", "ranged", "cannon")) {
                config.set("minions." + kind + ".damage", 0);
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
            SuperpowerMatch abilities = match.abilities();
            Player attacker = player(match, 0);
            Player enemy = player(match, 1);
            LaneSites.Side enemySide = match.side(enemy.getUniqueId());
            Tower tower = outer(match, enemySide);
            World world = tower.base().getWorld();
            Map<Location, Material> before = blocks(world, tower.base(), 24);

            // ---- against a player, in the open lane before the enemy tower
            Location lane = tower.base().clone().add(enemySide == LaneSites.Side.RED ? -14 : 14, 0, 0);
            assertTrue(abilities.replaceAbility(attacker, id), id + " is bound");
            face(attacker, lane, enemySide == LaneSites.Side.RED ? -90 : 90);
            Location front = lane.clone().add(attacker.getLocation().getDirection().setY(0).normalize().multiply(4));
            enemy.teleport(front);
            h.ticks(2);
            aim(attacker, enemy);
            abilities.useActive(attacker);
            h.ticks(60);
            for (int i = 0; i < 3; i++) {
                aim(attacker, enemy);
                abilities.basicAttack(attacker);
                h.ticks(20);
            }
            heal(attacker, enemy);
            aim(attacker, enemy);
            abilities.setGauge(attacker, 100);
            abilities.useUltimate(attacker);
            h.ticks(240);
            heal(attacker, enemy);

            // ---- against a minion (the first wave on the lanes)
            MinionWaves minions = match.systems().minions();
            h.tickUntil(() -> minions.minions().stream().anyMatch(m -> m.side() == enemySide), 600, "an enemy minion");
            LivingEntity minion = minions.minions().stream().filter(m -> m.side() == enemySide).findFirst()
                    .orElseThrow().body();
            assertTrue(abilities.context().isEnemy(attacker, minion), "the enemy minion is an enemy");
            Vector away = attacker.getLocation().toVector().subtract(minion.getLocation().toVector()).setY(0);
            Location near = minion.getLocation().add(away.lengthSquared() < 1.0E-4 ? new Vector(3, 0, 0)
                    : away.normalize().multiply(3));
            attacker.teleport(near);
            assertTrue(abilities.replaceAbility(attacker, id), () -> "rebound: dead=" + attacker.isDead() + " hp="
                    + attacker.getHealth() + " inPlay=" + match.session().isInPlay(attacker) + " phase=" + match.phase()
                    + " " + abilities.phase());
            aim(attacker, minion);
            abilities.useActive(attacker);
            h.ticks(40);
            aim(attacker, minion);
            abilities.basicAttack(attacker);
            h.ticks(20);
            heal(attacker, enemy);

            // ---- against the enemy tower, from the lane in front of its walls
            Location base = tower.base();
            Location atTower = base.clone().add(enemySide == LaneSites.Side.RED ? -5 : 5, 0, 0);
            attacker.teleport(atTower);
            Mannequin hitbox = tower.hitbox();
            assertNotNull(hitbox);
            double scale = hitbox.getAttribute(Attribute.SCALE).getValue();
            assertTrue(abilities.replaceAbility(attacker, id), () -> "rebound: dead=" + attacker.isDead() + " hp="
                    + attacker.getHealth() + " inPlay=" + match.session().isInPlay(attacker) + " phase=" + match.phase()
                    + " " + abilities.phase());
            aim(attacker, hitbox);
            abilities.useActive(attacker);
            h.ticks(40);
            aim(attacker, hitbox);
            abilities.basicAttack(attacker);
            h.ticks(10);
            abilities.setGauge(attacker, 100);
            aim(attacker, hitbox);
            abilities.useUltimate(attacker);
            h.ticks(240);
            assertEquals(scale, hitbox.getAttribute(Attribute.SCALE).getValue(), 1.0E-9,
                    id + ": a building keeps its size");
            assertTrue(hitbox.getLocation().distance(base) < 0.5, id + ": a building keeps its place");

            StringBuilder report = new StringBuilder();
            for (LogRecord record : problems) {
                report.append("\n  ").append(record.getMessage());
                Throwable thrown = record.getThrown();
                for (int depth = 0; thrown != null && depth < 3; depth++, thrown = thrown.getCause()) {
                    report.append("\n    ").append(depth == 0 ? "<- " : "caused by ").append(thrown);
                    StackTraceElement[] trace = thrown.getStackTrace();
                    for (int i = 0; i < Math.min(8, trace.length); i++) {
                        report.append("\n      at ").append(trace[i]);
                    }
                }
            }
            // every failure is logged as a warning: none may be left but the test server's own gaps
            assertTrue(problems.isEmpty(), id + " failed or logged problems in 공성전:" + report);

            // ---- the match ends: every block is back
            assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "게임 중지"));
            h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
            h.ticks(5);
            Map<Location, Material> after = blocks(world, tower.base(), 24);
            List<String> changed = new ArrayList<>();
            for (Map.Entry<Location, Material> entry : before.entrySet()) {
                Material now = after.get(entry.getKey());
                if (now != entry.getValue()) {
                    changed.add(entry.getKey().toVector() + " " + entry.getValue() + " → " + now);
                }
            }
            assertEquals(List.of(), changed.subList(0, Math.min(10, changed.size())), id + " left blocks changed");
        } finally {
            logger.removeHandler(handler);
            h.close();
        }
    }

    /**
     * True for a failure only the test server has: MockBukkit leaves some mob methods out (a minion's body yaw, its
     * line of sight), which a real server has.
     */
    private static boolean mockGap(@Nullable Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof UnimplementedOperationException) {
                return true;
            }
        }
        return false;
    }

    private static Player player(SiegeMatch match, int team) {
        UUID id = match.teams().get(team).members().iterator().next();
        return match.session().hub().plugin().getServer().getPlayer(id);
    }

    private static Tower outer(SiegeMatch match, LaneSites.Side side) {
        for (Tower tower : match.towers(side)) {
            if (tower.kind() == Tower.Kind.OUTER && tower.lane() == LaneSites.Lane.TOP) {
                return tower;
            }
        }
        throw new AssertionError("no outer tower of " + side);
    }

    /** Every block (barriers read as air) in a box of {@code radius} round {@code center} (−4 … +14 in height). */
    private static Map<Location, Material> blocks(World world, Location center, int radius) {
        Map<Location, Material> out = new HashMap<>();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int x = cx - radius - 16; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int y = cy - 4; y <= cy + 14; y++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    // the buildings' barrier walls come and go with the match itself
                    out.put(new Location(world, x, y, z), type == Material.BARRIER ? Material.AIR : type);
                }
            }
        }
        return out;
    }

    private static void face(Player player, Location at, float yaw) {
        Location spot = at.clone();
        spot.setYaw(yaw);
        spot.setPitch(0);
        player.teleport(spot);
        player.setVelocity(new Vector());
    }

    private static void aim(Player player, Entity target) {
        Location point = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        Location look = player.getLocation();
        look.setDirection(point.toVector().subtract(player.getEyeLocation().toVector()));
        player.teleport(look);
        player.setVelocity(new Vector());
    }

    private static void heal(Player... players) {
        for (Player player : players) {
            if (!player.isDead()) {
                player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
            }
        }
    }
}
