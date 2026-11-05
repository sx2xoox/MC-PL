package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A 공성전 match on the fallback two-lane layout: sides, structures and shields, the core win, scoring, overtime,
 * rounds, respawns, team sizes, modules and cleanup.
 */
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
        siege.sitesOverride(new FallbackLaneSites((int) FlatWorld.SURFACE));
        config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy"));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.ROUND_BREAK_SECONDS, 1);
        // No spawn camping damage nor tower shots unless a test wants them.
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        config.set(SiegeSettings.ATTACK_DAMAGE, 0);
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

    private SiegeMatch untilFight(int round) {
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT
                && siege.currentMatch().round() == round, 2000, "round " + round + " to start");
        return siege.currentMatch();
    }

    static Player member(SiegeMatch match, int team) {
        UUID id = match.teams().get(team).members().iterator().next();
        return match.session().hub().plugin().getServer().getPlayer(id);
    }

    private static MatchContext ctx(SiegeMatch match) {
        return match.abilities().context();
    }

    static Tower structure(SiegeMatch match, LaneSites.Side owner, Tower.Kind kind,
                           LaneSites.@org.jspecify.annotations.Nullable Lane lane) {
        for (Tower tower : match.towers(owner)) {
            if (tower.kind() == kind && (lane == null || tower.lane() == lane)) {
                return tower;
            }
        }
        throw new AssertionError("no " + kind + " of " + owner + " on " + lane);
    }

    private static Mannequin hitbox(Tower tower) {
        Mannequin body = tower.hitbox();
        assertNotNull(body, tower.id() + " has a hitbox");
        return body;
    }

    private static LaneSites.Side enemy(LaneSites.Side side) {
        return side == LaneSites.Side.BLUE ? LaneSites.Side.RED : LaneSites.Side.BLUE;
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

    /** Destroys every tower of {@code side} (outer first), as {@code attacker}, leaving the core. */
    private static void razeTowers(SiegeMatch match, LaneSites.Side side, Player attacker) {
        for (Tower.Kind kind : List.of(Tower.Kind.OUTER, Tower.Kind.INNER)) {
            for (LaneSites.Lane lane : LaneSites.Lane.values()) {
                match.hitTower(structure(match, side, kind, lane), attacker, 1_000_000);
            }
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void sidesStructuresAndShields() {
        SiegeMatch match = start(2);
        Player a = member(match, 0);
        Player b = member(match, 1);
        LaneSites.Side sideA = match.side(a.getUniqueId());
        LaneSites.Side sideB = match.side(b.getUniqueId());
        assertNotNull(sideA);
        assertNotNull(sideB);
        assertNotEquals(sideA, sideB, "one side each");
        assertSame(match.team(sideA), match.teams().get(0));
        assertEquals(10, match.towers().size(), "2 towers per lane and a core, per side");
        assertEquals(10, count(Mannequin.class), "one hitbox each");
        assertEquals(0, a.getLocation().distance(match.spawnFor(a.getUniqueId())), 1.0E-6, "at the own base");
        Participant participant = match.abilities().participant(a.getUniqueId());
        assertNotNull(participant);
        assertEquals("dummy", participant.abilityId());
        assertNotNull(participant.ability(), "bound in the fight");

        Tower outerTop = structure(match, sideB, Tower.Kind.OUTER, LaneSites.Lane.TOP);
        Tower innerTop = structure(match, sideB, Tower.Kind.INNER, LaneSites.Lane.TOP);
        Tower innerBottom = structure(match, sideB, Tower.Kind.INNER, LaneSites.Lane.BOTTOM);
        Tower core = structure(match, sideB, Tower.Kind.CORE, null);
        assertFalse(outerTop.isShielded());
        assertTrue(innerTop.isShielded(), "the inner tower waits for its lane's outer tower");
        assertTrue(core.isShielded());
        assertEquals(600, outerTop.maxHealth(), 1.0E-9, "one enemy: half the configured health");

        MatchContext ctx = ctx(match);
        assertTrue(ctx.isEnemy(a, hitbox(outerTop)), "the enemy's tower is a target");
        assertFalse(ctx.isEnemy(b, hitbox(outerTop)), "never the owner's");
        assertFalse(ctx.isEnemy(a, hitbox(innerTop)), "a shielded tower is no target");
        ctx.damage(a, hitbox(outerTop), 50);
        assertEquals(550, outerTop.health(), 1.0E-6, "ability damage hits the tower");
        assertTrue(hitbox(outerTop).getHealth() > 0, "the hitbox itself is never hurt");
        ctx.damage(b, hitbox(outerTop), 50);
        assertEquals(550, outerTop.health(), 1.0E-6, "the owner cannot hurt its own tower");
        match.hitTower(innerTop, a, 100);
        assertEquals(innerTop.maxHealth(), innerTop.health(), 1.0E-6, "the shield holds");

        match.hitTower(outerTop, a, 10_000);
        assertTrue(outerTop.isDestroyed());
        assertNull(outerTop.hitbox());
        assertEquals(8, match.score().points(0), "a tower: +8");
        assertFalse(innerTop.isShielded(), "the lane's inner tower opens");
        assertTrue(innerBottom.isShielded(), "the other lane is still closed");
        assertTrue(core.isShielded());
        match.hitTower(innerTop, a, 10_000);
        assertTrue(core.isShielded(), "one inner tower still stands: the core keeps its shield");
        match.hitTower(structure(match, sideB, Tower.Kind.OUTER, LaneSites.Lane.BOTTOM), a, 10_000);
        match.hitTower(innerBottom, a, 10_000);
        assertFalse(core.isShielded(), "both inner towers down: the core opens");
        assertEquals(32, match.score().points(0));
        assertEquals(4, match.score().towers(0));
        assertTrue(match.session().isRunning());
        assertEquals(SiegeMatch.Phase.FIGHT, match.phase(), "towers alone do not end the round");
    }

    @Test
    void destroyingTheCoreWinsTheRoundAtOnce() {
        SiegeMatch match = start(2);
        Player a = member(match, 0);
        LaneSites.Side enemySide = enemy(match.side(a.getUniqueId()));
        razeTowers(match, enemySide, a);
        Tower core = structure(match, enemySide, Tower.Kind.CORE, null);
        assertFalse(core.isShielded());
        match.hitTower(core, a, 10_000);
        assertEquals(1, match.score().roundWins(0), "the core: the round at once, whatever the time");
        assertEquals(SiegeMatch.Phase.BREAK, match.phase());
        assertTrue(match.towers().isEmpty(), "the structures are gone between rounds");
        untilFight(2);
        assertEquals(10, match.towers().size(), "fresh structures for the next round");
        assertEquals(0, match.score().points(0), "points start over");
    }

    @Test
    void killsScoreAndPlayersRespawnAtTheirBaseWithProtection() {
        config.set(SiegeSettings.RESPAWN_SECONDS, 2);
        SiegeMatch match = start(2);
        Player a = member(match, 0);
        Player b = member(match, 1);
        GameSession session = match.session();
        b.teleport(new Location(h.flatWorld(), 0.5, FlatWorld.SURFACE, 0.5));
        b.setNoDamageTicks(0);
        ctx(match).damage(a, b, 10_000);
        assertTrue(b.isDead());
        assertEquals(2, match.score().points(0), "kill: +2");
        respawn(b);
        assertTrue(session.isRespawning(b.getUniqueId()));
        assertEquals(GameMode.SPECTATOR, b.getGameMode());
        h.tickUntil(() -> session.isInPlay(b), 100, "the respawn");
        assertEquals(GameMode.ADVENTURE, b.getGameMode());
        assertEquals(0, b.getLocation().distance(match.spawnFor(b.getUniqueId())), 1.0E-6, "back at the base");
        assertEquals(match.layout().spawns(match.side(b.getUniqueId())).getFirst().getX(), b.getLocation().getX(),
                1.0E-6);
        assertNotNull(match.abilities().participant(b.getUniqueId()).ability(), "ability bound again");
        assertTrue(match.isProtected(b), "spawn protection");
        double health = b.getHealth();
        ctx(match).damage(a, b, 4);
        assertEquals(health, b.getHealth(), 1.0E-9, "protected players take no damage");
        ctx(match).damage(b, a, 1);
        assertFalse(match.isProtected(b), "attacking ends the protection");
    }

    @Test
    void theDeathTitleCountsTheSecondsDownToTheRespawn() {
        config.set(SiegeSettings.RESPAWN_SECONDS, 3);
        SiegeMatch match = start(2);
        Player a = member(match, 0);
        TestPlayer b = (TestPlayer) member(match, 1);
        GameSession session = match.session();
        b.teleport(new Location(h.flatWorld(), 0.5, FlatWorld.SURFACE, 0.5));
        b.setNoDamageTicks(0);
        ctx(match).damage(a, b, 10_000);
        int seconds = (match.respawnTicks(b.getUniqueId()) + 19) / 20;
        b.titles().clear();
        respawn(b);
        List<String> shown = new ArrayList<>();
        int waited = 0;
        while (session.isRespawning(b.getUniqueId()) && waited++ < 200) {
            for (net.kyori.adventure.title.Title title : b.titles()) {
                shown.add(plain(title.title()) + " / " + plain(title.subtitle()));
            }
            b.titles().clear();
            h.ticks(1);
        }
        assertTrue(session.isInPlay(b), "back in play");
        List<String> expected = new ArrayList<>();
        for (int left = seconds; left >= 1; left--) {
            expected.add("사망! / " + left + "초 후 부활");
        }
        assertEquals(expected, shown, "one title per second, counting down");
        assertTrue(waited >= (seconds - 1) * 20 && waited <= seconds * 20 + 2, "respawned at 0 (" + waited + " ticks)");
    }

    @Test
    void onlyPlayersWithThePackGetThePanelBehindTheSidebar() {
        SiegeMatch match = start(2);
        Player a = member(match, 0);
        Player b = member(match, 1);
        match.hud().packs(new io.github.sx2xoox.mcpl.core.pack.PackAudience() {
            @Override
            public boolean hasPack(Player player) {
                return player.equals(a);
            }

            @Override
            public Subscription subscribe(java.util.function.Consumer<Player> listener) {
                return () -> { };
            }
        });
        h.ticks(21);
        io.github.sx2xoox.mcpl.core.ui.Sidebar withPack = match.session().sidebar(a);
        io.github.sx2xoox.mcpl.core.ui.Sidebar without = match.session().sidebar(b);
        assertTrue(hasPanelGlyph(withPack.title()), "the ribbon behind the title");
        assertTrue(withPack.lines().stream().allMatch(SiegeMatchTest::hasPanelGlyph), "a piece behind every line");
        assertFalse(hasPanelGlyph(without.title()), "no pack: no glyph (it would be a box)");
        assertTrue(without.lines().stream().noneMatch(SiegeMatchTest::hasPanelGlyph), "no pack: vanilla lines");
        assertEquals(without.lines().size(), withPack.lines().size());
        assertEquals(plain(match.hud().lines(a).getFirst()),
                plain(withPack.lines().getFirst()).codePoints()
                        .filter(cp -> !io.github.sx2xoox.mcpl.core.ui.SidebarPanel.isPanelGlyph(cp))
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString(),
                "the same text on the panel");
    }

    private static boolean hasPanelGlyph(net.kyori.adventure.text.Component component) {
        return plain(component).codePoints().anyMatch(io.github.sx2xoox.mcpl.core.ui.SidebarPanel::isPanelGlyph);
    }

    @Test
    void theSidebarShowsTheNextLevelPerkUntilNoneIsLeft() {
        config.set(SiegeSettings.ABILITY_POOL, List.of("pyro"));
        config.set(SiegeProgressSettings.XP_PASSIVE, 0);
        SiegeMatch match = start(2);
        Player a = member(match, 0);
        assertEquals("pyro", match.abilityInfo(a.getUniqueId()).id());
        String third = io.github.sx2xoox.mcpl.game.siege.balance.SiegePerks.describe("pyro", 3);
        assertTrue(lines(match, a).contains(third), "level 1: the level 3 perk " + lines(match, a));
        match.progress().grant(a.getUniqueId(), match.progress().settings().xpForLevel(3));
        assertEquals(3, match.level(a.getUniqueId()));
        String sixth = io.github.sx2xoox.mcpl.game.siege.balance.SiegePerks.describe("pyro", 6);
        assertTrue(lines(match, a).contains(sixth), "level 3: the level 6 perk " + lines(match, a));
        assertFalse(lines(match, a).contains(third));
        match.progress().grant(a.getUniqueId(), 100_000);
        assertTrue(lines(match, a).stream().noneMatch(line -> line.contains("레벨:")), "no perk left at the top");
        for (String abilityId : io.github.sx2xoox.mcpl.game.siege.balance.SiegePerks.abilities()) {
            for (io.github.sx2xoox.mcpl.game.siege.balance.SiegePerks.Perk perk
                    : io.github.sx2xoox.mcpl.game.siege.balance.SiegePerks.perks(abilityId)) {
                assertTrue(io.github.sx2xoox.mcpl.core.ui.TextWidth.of(net.kyori.adventure.text.Component.text(
                        perk.level() + "레벨: " + perk.text())) <= 170, perk.text());
            }
        }
    }

    private static List<String> lines(SiegeMatch match, Player player) {
        return match.hud().lines(player).stream().map(SiegeMatchTest::plain).toList();
    }

    @Test
    void enemiesInABaseAreHurt() {
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 4);
        SiegeMatch match = start(2);
        Player a = member(match, 0);
        Location enemyBase = match.layout().spawns(enemy(match.side(a.getUniqueId()))).getFirst();
        a.teleport(enemyBase);
        double before = a.getHealth();
        h.ticks(25);
        assertTrue(a.getHealth() < before, "spawn safety: the enemy base hurts");
    }

    @Test
    void towersShootEnemiesInRange() {
        config.set(SiegeSettings.ATTACK_DAMAGE, 3);
        SiegeMatch match = start(2);
        Player a = member(match, 0);
        Tower outer = structure(match, enemy(match.side(a.getUniqueId())), Tower.Kind.OUTER, LaneSites.Lane.TOP);
        a.teleport(outer.base().add(4, 0, 0));
        double before = a.getHealth();
        h.ticks(10);
        assertTrue(a.getHealth() < before, "the tower shot");
        a.teleport(match.spawnFor(a.getUniqueId()));
        a.setHealth(20);
        h.ticks(40);
        assertEquals(20, a.getHealth(), 1.0E-9, "out of range: no shots");
    }

    @Test
    void timeUpGoesToTheScoreThenToOvertime() {
        SiegeMatch match = start(2);
        Player a = member(match, 0);
        LaneSites.Side sideA = match.side(a.getUniqueId());
        match.expireRound();
        assertTrue(match.isOvertime(), "all tied: overtime");
        assertEquals(SiegeMatch.Phase.FIGHT, match.phase());
        assertEquals(120 * 20, match.ticksLeft(), "2 minutes");
        match.addScore(enemy(sideA), 1, "test");
        assertEquals(1, match.score().roundWins(1), "the first point of the overtime wins");

        untilFight(2);
        match.addScore(sideA, 3, "test");
        match.expireRound();
        assertEquals(1, match.score().roundWins(0), "time up: more points win");
        assertTrue(match.session().isRunning());
    }

    @Test
    void aSilentOvertimeGoesToTheSturdierStructures() {
        SiegeMatch match = start(2);
        Player a = member(match, 0);
        LaneSites.Side enemySide = enemy(match.side(a.getUniqueId()));
        match.expireRound();
        assertTrue(match.isOvertime());
        // Damage without a kill or a destroyed tower: no points.
        match.hitTower(structure(match, enemySide, Tower.Kind.OUTER, LaneSites.Lane.TOP), a, 100);
        assertEquals(0, match.sturdierTeam(), "team A kept more structure health");
        match.expireRound();
        assertEquals(1, match.score().roundWins(0));
    }

    @Test
    void bestOfThreeEndsWhenASideWonTwoRounds() {
        SiegeMatch match = start(2);
        Player a = member(match, 0);
        LaneSites.Side sideA = match.side(a.getUniqueId());
        match.addScore(sideA, 5, "test");
        match.expireRound();
        assertEquals(1, match.score().roundWins(0));
        untilFight(2);
        assertEquals(2, match.round());
        match.addScore(sideA, 5, "test");
        match.expireRound();
        assertEquals(2, match.score().roundWins(0));
        assertEquals(SiegeMatch.Phase.OVER, match.phase());
        h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
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
        LaneSites.Side sideB = match.sideOf(1);
        assertEquals(1200 * (0.25 + 0.25 * sizeA),
                structure(match, sideB, Tower.Kind.OUTER, LaneSites.Lane.TOP).maxHealth(), 1.0E-6,
                "structure health follows the enemy team's size");
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
            assertEquals(5, spots.size(), "five players, five different spawn blocks");
        }
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

    /** One player may start it alone to try it: the other team stays empty and only time or the core end rounds. */
    @Test
    void aPlayerCanPlayItAlone() {
        SiegeMatch match = start(1);
        assertTrue(match.isSolo());
        assertEquals(1, match.teams().get(0).size());
        assertEquals(0, match.teams().get(1).size(), "no bots: the other team stays empty");
        Player solo = member(match, 0);
        LaneSites.Side enemySide = enemy(match.side(solo.getUniqueId()));
        assertEquals(600, structure(match, enemySide, Tower.Kind.OUTER, LaneSites.Lane.TOP).maxHealth(), 1.0E-9);
        h.ticks(100);
        assertTrue(match.session().isRunning(), "an empty team does not lose at once");
        match.hitTower(structure(match, enemySide, Tower.Kind.OUTER, LaneSites.Lane.TOP), solo, 10_000);
        assertEquals(8, match.score().points(0));
        match.expireRound();
        assertEquals(1, match.score().roundWins(0), "the round ends on score and time");
        untilFight(2);
        razeTowers(match, enemySide, solo);
        match.hitTower(structure(match, enemySide, Tower.Kind.CORE, null), solo, 10_000);
        assertEquals(SiegeMatch.Phase.OVER, match.phase(), "two rounds won alone");
    }

    @Test
    void modulesPlugInThroughTheHooks() {
        List<String> calls = new ArrayList<>();
        Map<UUID, Integer> purse = new HashMap<>();
        siege.addModule(match -> {
            match.economy(new SiegeEconomy() {
                @Override
                public int gold(UUID player) {
                    return purse.getOrDefault(player, 0);
                }

                @Override
                public void addGold(UUID player, int amount, String reason) {
                    purse.merge(player, amount, Integer::sum);
                }
            });
            return new SiegeModule() {
                @Override
                public void roundStarted(int round) {
                    calls.add("round " + round);
                }

                @Override
                public void fightStarted() {
                    calls.add("fight");
                }

                @Override
                public void playerKilled(UUID victim, @org.jspecify.annotations.Nullable UUID killer,
                                         List<UUID> assisters) {
                    calls.add("kill");
                    if (killer != null) {
                        match.addGold(killer, 150, "kill");
                    }
                }

                @Override
                public void structureDestroyed(Tower tower, LaneSites.Side by,
                                               @org.jspecify.annotations.Nullable UUID destroyer) {
                    calls.add("tower " + tower.kind());
                }

                @Override
                public void stop() {
                    calls.add("stop");
                }

                @Override
                public net.kyori.adventure.text.Component sidebarLine(Player viewer) {
                    return net.kyori.adventure.text.Component.text("드래곤 2:00");
                }
            };
        });
        SiegeMatch match = start(2);
        assertEquals(List.of("round 1", "fight"), calls);
        Player a = member(match, 0);
        Player b = member(match, 1);
        b.setNoDamageTicks(0);
        ctx(match).damage(a, b, 10_000);
        assertTrue(calls.contains("kill"));
        assertEquals(150, match.gold(a.getUniqueId()), "the installed economy holds the gold");
        match.hitTower(structure(match, match.side(b.getUniqueId()), Tower.Kind.OUTER, LaneSites.Lane.TOP), a,
                10_000);
        assertTrue(calls.contains("tower OUTER"));
        match.addScore(match.side(a.getUniqueId()), 3, "jungle", a.getUniqueId());
        assertEquals(2 + 8 + 3, match.score().points(0));
        match.addXp(a.getUniqueId(), 10);
        assertTrue(match.experience(a.getUniqueId()) >= 10);
        h.ticks(11);
        List<String> lines = match.hud().lines(a).stream().map(SiegeMatchTest::plain).toList();
        assertTrue(lines.size() <= 6, "at most six sidebar lines: " + lines);
        assertTrue(lines.contains("드래곤 2:00"), "the module's objective line: " + lines);
        assertTrue(lines.stream().anyMatch(line -> line.contains("150")), "the gold: " + lines);
        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "게임 중지"));
        h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
        assertTrue(calls.contains("stop"));
    }

    static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void stoppingMidRoundLeavesNothingBehind() {
        SiegeMatch match = start(2);
        assertTrue(count(Mannequin.class) > 0);
        assertTrue(count(TextDisplay.class) >= 10, "an overhead bar per structure");
        // The structure entities are not persistent: their chunks are held loaded (else they vanish when nobody is near).
        for (Tower tower : match.towers()) {
            Location at = hitbox(tower).getLocation();
            assertTrue(h.flatWorld().hasChunkTicket(at.getBlockX() >> 4, at.getBlockZ() >> 4,
                    match.session().hub().plugin()), tower.id() + "'s chunk is held loaded");
        }
        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "게임 중지"));
        h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
        assertFalse(match.session().isRunning());
        assertEquals(0, count(Mannequin.class));
        assertEquals(0, count(TextDisplay.class));
        assertEquals(0, h.flatWorld().chunkTickets(match.session().hub().plugin()), "the chunks are let go");
    }
}
