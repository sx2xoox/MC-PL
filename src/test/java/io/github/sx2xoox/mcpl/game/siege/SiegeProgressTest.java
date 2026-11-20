package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchHost;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.sound.AudioExperience;

/** 공성전 levels and experience (docs/siege.md "레벨") on the fallback layout: levels lock nothing. */
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
        siege.sitesOverride(new FallbackLaneSites((int) FlatWorld.SURFACE));
        config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy"));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.ROUND_BREAK_SECONDS, 1);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        config.set(SiegeSettings.ATTACK_DAMAGE, 0);
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

    private SiegeMatch untilFight(int round) {
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT
                && siege.currentMatch().round() == round, 2000, "round " + round + " to start");
        return siege.currentMatch();
    }

    private static Player member(SiegeMatch match, int team) {
        UUID id = match.teams().get(team).members().iterator().next();
        return match.session().hub().plugin().getServer().getPlayer(id);
    }

    private static double maxHealth(Player player) {
        return player.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    private static Tower enemyOuter(SiegeMatch match, Player player) {
        LaneSites.Side side = match.side(player.getUniqueId());
        for (Tower tower : match.towers()) {
            if (tower.owner() != side && tower.kind() == Tower.Kind.OUTER) {
                return tower;
            }
        }
        throw new AssertionError("no enemy outer tower");
    }

    @Test
    void theExperienceCurve() {
        SiegeProgressSettings s = SiegeProgressSettings.read(new MemoryConfiguration());
        assertEquals(9, s.maxLevel());
        assertEquals(70, s.xpToNext(1));
        assertEquals(135, s.xpToNext(2));
        assertEquals(0, s.xpForLevel(1));
        assertEquals(70, s.xpForLevel(2));
        assertEquals(2380, s.xpForLevel(9));
        assertEquals(1, s.levelFor(69));
        assertEquals(2, s.levelFor(70));
        assertEquals(9, s.levelFor(1_000_000), "capped at the top level");
        assertEquals(0, s.bonusHealth(1));
        assertEquals(2, s.bonusHealth(2), 1.0E-9, "a heart every two levels");
        assertEquals(8, s.bonusHealth(9), 1.0E-9);

        SiegeLevels levels = new SiegeLevels(s);
        UUID id = UUID.randomUUID();
        assertEquals(1, levels.level(id));
        assertEquals(0, levels.add(id, 69));
        assertEquals(2, levels.add(id, 136), "two levels at once");
        assertEquals(3, levels.level(id));
        assertEquals(200, levels.xpThisLevel(id));
        levels.reset();
        assertEquals(1, levels.level(id));

        MemoryConfiguration off = new MemoryConfiguration();
        off.set(SiegeProgressSettings.LEVELS_ENABLED, false);
        SiegeLevels free = new SiegeLevels(SiegeProgressSettings.read(off));
        assertEquals(9, free.level(id), "levels off: the top level");
        assertEquals(0, free.add(id, 500));
    }

    @Test
    void levelsLockNothingAndOnlyAddHealth() {
        SiegeMatch match = start();
        Player player = member(match, 0);
        UUID id = player.getUniqueId();
        MatchHost host = match.abilities().host();
        assertNotNull(host);
        assertEquals(1, match.level(id));
        assertTrue(host.canChargeGauge(player), "the gauge charges from level 1");
        assertTrue(match.abilities().useActive(player).succeeded(), "level 1: the active works");
        match.abilities().setGauge(player, 100);
        assertTrue(match.abilities().useUltimate(player).succeeded(), "level 1: the ultimate works");
        assertEquals(1, host.abilityLevel(player), "abilities read the siege level");

        ((PlayerMock) player).getHeardSounds().clear();
        match.addXp(id, 70);
        assertEquals(2, match.level(id));
        assertEquals(2, host.abilityLevel(player));
        assertEquals(22, maxHealth(player), 1.0E-9, "level 2: one more heart");
        assertTrue(((PlayerMock) player).getHeardSounds().stream().map(AudioExperience::getSound)
                .anyMatch(name -> name.endsWith("entity.player.levelup")), "the level-up sound");

        match.addScore(match.side(id), 1, "test");
        match.expireRound();
        untilFight(2);
        assertEquals(1, match.level(id), "every round starts at level 1");
        assertEquals(0, match.experience(id));
        assertEquals(20, maxHealth(player), 1.0E-9, "the level health is gone");
    }

    @Test
    void levelUpsAnnounceTheAbilityPerks() {
        String pyro6 = io.github.sx2xoox.mcpl.game.siege.balance.SiegePerks.describe("pyro", 6);
        assertNotNull(pyro6);
        assertTrue(pyro6.contains("+1"), pyro6);
        assertEquals(null, io.github.sx2xoox.mcpl.game.siege.balance.SiegePerks.describe("pyro", 5));
        SiegeMatch match = start();
        Player player = member(match, 0);
        String ability = match.abilities().participant(player.getUniqueId()).abilityId();
        String perk = io.github.sx2xoox.mcpl.game.siege.balance.SiegePerks.describe(ability, 3);
        while (((PlayerMock) player).nextComponentMessage() != null) {
            // drain
        }
        match.addXp(player.getUniqueId(), 205);
        assertEquals(3, match.level(player.getUniqueId()));
        if (perk != null) {
            boolean seen = false;
            net.kyori.adventure.text.Component next;
            while ((next = ((PlayerMock) player).nextComponentMessage()) != null) {
                seen |= SiegeMatchTest.plain(next).contains(perk);
            }
            assertTrue(seen, "the level 3 perk is announced: " + perk);
        }
    }

    @Test
    void killsTowersAndThePassiveTrickleGiveExperience() {
        config.set(SiegeProgressSettings.XP_PASSIVE, 2);
        SiegeMatch match = start();
        Player a = member(match, 0);
        Player b = member(match, 1);
        int before = match.experience(a.getUniqueId());
        h.ticks(60);
        assertTrue(match.experience(a.getUniqueId()) >= before + 4, "a trickle every second");

        int aXp = match.experience(a.getUniqueId());
        int bXp = match.experience(b.getUniqueId());
        b.setNoDamageTicks(0);
        match.abilities().context().damage(a, b, 10_000);
        assertTrue(b.isDead());
        assertEquals(aXp + 30, match.experience(a.getUniqueId()), "kill: +30");
        assertEquals(bXp, match.experience(b.getUniqueId()), "nothing for dying");

        Tower outer = enemyOuter(match, a);
        int xp = match.experience(a.getUniqueId());
        match.hitTower(outer, a, 1_000_000);
        assertTrue(outer.isDestroyed());
        assertEquals(xp + 150, match.experience(a.getUniqueId()), "tower destroyed: the side +150");
    }
}
