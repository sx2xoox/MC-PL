package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchHost;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 공성전 levels, experience and the neutral objectives (docs/siege.md §12–13) on the fallback layout. */
class SiegeProgressTest {

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
        siege.sitesOverride(new FallbackSiegeSites((int) FlatWorld.SURFACE));
        config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy"));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.HALF_PREP_SECONDS, 1);
        config.set(SiegeSettings.HALFTIME_SECONDS, 1);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        // Only what a test grants: no trickle.
        config.set(SiegeProgressSettings.XP_PASSIVE, 0);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private SiegeMatch start() {
        h.join(2);
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

    private static ObjectivePoints.Point point(SiegeMatch match, String id) {
        for (ObjectivePoints.Point point : match.objectives().points()) {
            if (point.site().id().equals(id)) {
                return point;
            }
        }
        throw new AssertionError("no objective " + id);
    }

    private static double maxHealth(Player player) {
        return player.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    // ------------------------------------------------------------------ the curve

    @Test
    void theExperienceCurve() {
        SiegeProgressSettings s = SiegeProgressSettings.read(new MemoryConfiguration());
        assertEquals(9, s.maxLevel());
        assertEquals(60, s.xpToNext(1));
        assertEquals(100, s.xpToNext(2));
        assertEquals(0, s.xpForLevel(1));
        assertEquals(60, s.xpForLevel(2));
        assertEquals(1600, s.xpForLevel(9));
        assertEquals(1, s.levelFor(0));
        assertEquals(1, s.levelFor(59));
        assertEquals(2, s.levelFor(60));
        assertEquals(3, s.levelFor(160));
        assertEquals(8, s.levelFor(1599));
        assertEquals(9, s.levelFor(1600));
        assertEquals(9, s.levelFor(1_000_000), "capped at the top level");
        assertEquals(0, s.bonusHealth(1));
        assertEquals(2, s.bonusHealth(2), 1.0E-9, "a heart every two levels");
        assertEquals(2, s.bonusHealth(3), 1.0E-9);
        assertEquals(8, s.bonusHealth(9), 1.0E-9);

        SiegeLevels levels = new SiegeLevels(s);
        UUID id = UUID.randomUUID();
        assertEquals(1, levels.level(id));
        assertEquals(0, levels.add(id, 59));
        assertEquals(2, levels.add(id, 101), "two levels at once");
        assertEquals(3, levels.level(id));
        assertEquals(0, levels.xpIntoLevel(id));
        assertEquals(140, levels.xpThisLevel(id));
        assertTrue(levels.canUseActive(id) && levels.canUseUltimate(id));
        levels.reset();
        assertEquals(1, levels.level(id));
        assertFalse(levels.canUseActive(id));

        MemoryConfiguration off = new MemoryConfiguration();
        off.set(SiegeProgressSettings.LEVELS_ENABLED, false);
        SiegeLevels free = new SiegeLevels(SiegeProgressSettings.read(off));
        assertEquals(9, free.level(id), "levels off: the whole kit from the start");
        assertEquals(0, free.add(id, 500));
        assertEquals(0, SiegeProgressSettings.read(off).bonusHealth(9));
    }

    // ------------------------------------------------------------------ gates and growth

    @Test
    void levelsUnlockTheKitAndAddHealth() {
        SiegeMatch match = start();
        Player attacker = member(match, 0);
        UUID id = attacker.getUniqueId();
        MatchHost host = match.abilities().host();
        assertNotNull(host);
        assertEquals(1, match.level(id));
        assertEquals(20, maxHealth(attacker), 1.0E-9);

        assertFalse(match.abilities().useActive(attacker).succeeded(), "level 1: passive only");
        match.abilities().setGauge(attacker, 100);
        assertFalse(match.abilities().useUltimate(attacker).succeeded(), "no ultimate at level 1");
        assertFalse(host.canChargeGauge(attacker), "the gauge does not charge yet");

        match.progress().grant(id, 60);
        assertEquals(2, match.level(id));
        assertEquals(22, maxHealth(attacker), 1.0E-9, "level 2: one more heart");
        assertTrue(match.abilities().useActive(attacker).succeeded(), "level 2: the active");
        assertFalse(match.abilities().useUltimate(attacker).succeeded(), "still no ultimate");

        match.progress().grant(id, 100);
        assertEquals(3, match.level(id));
        assertTrue(host.canChargeGauge(attacker));
        match.abilities().setGauge(attacker, 100);
        assertTrue(match.abilities().useUltimate(attacker).succeeded(), "level 3: the ultimate");

        match.expireHalf();
        untilFight(2);
        assertEquals(1, match.level(id), "every half starts at level 1");
        assertEquals(0, match.experience(id));
        assertEquals(20, maxHealth(attacker), 1.0E-9, "the level health is gone");
    }

    @Test
    void killsTowersAndThePassiveTrickleGiveExperience() {
        config.set(SiegeProgressSettings.XP_PASSIVE, 2);
        SiegeMatch match = start();
        Player attacker = member(match, 0);
        Player defender = member(match, 1);
        int before = match.experience(attacker.getUniqueId());
        h.ticks(60);
        assertTrue(match.experience(attacker.getUniqueId()) >= before + 4, "a trickle every second");

        int attackerXp = match.experience(attacker.getUniqueId());
        int defenderXp = match.experience(defender.getUniqueId());
        defender.setNoDamageTicks(0);
        match.abilities().context().damage(attacker, defender, 10_000);
        assertTrue(defender.isDead());
        assertEquals(attackerXp + 30, match.experience(attacker.getUniqueId()), "kill: +30");
        assertEquals(defenderXp, match.experience(defender.getUniqueId()), "nothing for dying");

        Tower front = match.attackableTower();
        assertNotNull(front);
        int xp = match.experience(attacker.getUniqueId());
        match.hitTower(front, attacker, 1_000_000);
        assertTrue(front.isDestroyed());
        assertEquals(xp + 150, match.experience(attacker.getUniqueId()), "tower destroyed: the attackers +150");
    }

    // ------------------------------------------------------------------ objectives

    @Test
    void aDataNodeIsCapturedByOneTeamAndContestedByBoth() {
        config.set(SiegeProgressSettings.NODE_CAPTURE, 3);
        config.set(SiegeProgressSettings.NODE_RESPAWN, 10);
        SiegeMatch match = start();
        Player attacker = member(match, 0);
        Player defender = member(match, 1);
        ObjectivePoints.Point north = point(match, "north");
        ObjectivePoints.Point center = point(match, "center");
        assertTrue(north.isOpen(), "the data nodes are open from the start");
        assertFalse(center.isOpen(), "the 오버클럭 코어 comes later");
        assertTrue(h.flatWorld().getEntities().stream().anyMatch(TextDisplay.class::isInstance), "labels");

        attacker.teleport(north.at());
        defender.teleport(north.at().add(1, 0, 0));
        h.ticks(100);
        assertTrue(north.isContested(), "both teams inside: contested");
        assertEquals(0, north.fraction(), 1.0E-9, "contested points do not move");

        defender.teleport(match.spawnFor(defender.getUniqueId()));
        h.tickUntil(() -> !north.isOpen(), 100, "the capture");
        assertEquals(100, match.experience(attacker.getUniqueId()), "node: +100 for the team");
        assertEquals(0, match.experience(defender.getUniqueId()), "nothing for the other team");
        assertNotNull(attacker.getPotionEffect(PotionEffectType.SPEED), "the node's buff");
        assertNull(defender.getPotionEffect(PotionEffectType.SPEED));

        h.tickUntil(north::isOpen, 20 * 12, "the node to open again");
    }

    @Test
    void theOverclockCoreAppearsOnATimerAndGivesALotOfExperience() {
        config.set(SiegeProgressSettings.MAJOR_FIRST, 3);
        config.set(SiegeProgressSettings.MAJOR_WARNING, 2);
        config.set(SiegeProgressSettings.MAJOR_CAPTURE, 2);
        SiegeMatch match = start();
        Player defender = member(match, 1);
        ObjectivePoints.Point center = point(match, "center");
        assertFalse(center.isOpen());
        defender.teleport(center.at());
        h.ticks(40);
        assertEquals(0, match.experience(defender.getUniqueId()), "nothing before it appears");
        h.tickUntil(center::isOpen, 60, "the core to appear");
        h.tickUntil(() -> !center.isOpen(), 80, "the capture");
        assertEquals(250, match.experience(defender.getUniqueId()), "core: +250");
        assertEquals(3, match.level(defender.getUniqueId()));
        assertNotNull(defender.getPotionEffect(PotionEffectType.RESISTANCE));
        assertNotNull(defender.getPotionEffect(PotionEffectType.REGENERATION));
    }

    @Test
    void anEmptyPointFallsBackAndTheOtherTeamDrainsItFirst() {
        config.set(SiegeProgressSettings.NODE_CAPTURE, 6);
        SiegeMatch match = start();
        Player attacker = member(match, 0);
        Player defender = member(match, 1);
        ObjectivePoints.Point canal = point(match, "canal");
        attacker.teleport(canal.at());
        h.tickUntil(() -> canal.fraction() >= 0.5, 100, "half the capture");
        assertEquals(0, canal.capturer());
        attacker.teleport(match.spawnFor(attacker.getUniqueId()));
        double held = canal.fraction();
        h.ticks(20);
        assertTrue(canal.fraction() < held, "an empty point falls back");
        defender.teleport(canal.at());
        h.tickUntil(() -> canal.capturer() == 1, 100, "the defenders to take it over");
        assertTrue(canal.isOpen());
    }

    @Test
    void theMatchEndLeavesNoObjectiveOrLevelBehind() {
        SiegeMatch match = start();
        Player attacker = member(match, 0);
        match.progress().grant(attacker.getUniqueId(), 1600);
        assertEquals(9, match.level(attacker.getUniqueId()));
        assertEquals(28, maxHealth(attacker), 1.0E-9, "four more hearts at level 9");
        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "게임 중지"));
        h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
        assertEquals(0, h.flatWorld().getEntities().stream().filter(TextDisplay.class::isInstance).count());
        assertEquals(20, maxHealth(attacker), 1.0E-9, "no level health after the match");
    }
}
