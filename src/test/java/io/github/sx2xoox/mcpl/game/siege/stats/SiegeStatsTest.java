package io.github.sx2xoox.mcpl.game.siege.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.FallbackLaneSites;
import io.github.sx2xoox.mcpl.game.siege.SiegeGame;
import io.github.sx2xoox.mcpl.game.siege.SiegeMatch;
import io.github.sx2xoox.mcpl.game.siege.SiegeModule;
import io.github.sx2xoox.mcpl.game.siege.SiegeSettings;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBotDifficulty;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBots;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The balance records: what a short match adds per ability (skills and basic attacks apart, overkill capped,
 * structures and creatures apart, bots apart), the files, the totals and the admin command.
 */
class SiegeStatsTest {

    private MatchHarness h;
    private SiegeGame siege;
    private ConfigurationSection config;
    private SiegeStatsStore store;
    private final AtomicReference<LivingEntity> creature = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
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
        config.set(SiegeSettings.RESPAWN_SECONDS, 2);
        config.set("minions.first-wave-seconds", 600);
        config.set("bots.jobs", List.of("knight"));
        store = siege.statsStore();
        clear();
        // a stand-in for a minion: a module target every player may hit
        siege.addModule(match -> new SiegeModule() {
            @Override
            public boolean isTarget(Entity entity) {
                return entity.equals(creature.get());
            }

            @Override
            public boolean isEnemy(Player player, Entity entity) {
                return entity.equals(creature.get());
            }

            @Override
            public List<LivingEntity> targets() {
                LivingEntity target = creature.get();
                return target == null ? List.of() : List.of(target);
            }
        });
    }

    @AfterEach
    void tearDown() throws IOException {
        clear();
        h.close();
    }

    private void clear() throws IOException {
        if (Files.isDirectory(store.dir())) {
            try (Stream<Path> files = Files.list(store.dir())) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private SiegeMatch start(int players, int botsPerSide) {
        h.join(players);
        if (botsPerSide > 0) {
            siege.requestBots(new SiegeBots.Setup(siege.botSettings(), botsPerSide, SiegeBotDifficulty.EASY));
        }
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
        for (UUID id : match.teams().get(team).members()) {
            Player player = match.session().hub().plugin().getServer().getPlayer(id);
            if (player != null) {
                return player;
            }
        }
        throw new AssertionError("no player in team " + team);
    }

    private static SiegeStats stats(SiegeMatch match) {
        for (SiegeModule module : match.modules()) {
            if (module instanceof SiegeStats stats) {
                return stats;
            }
        }
        throw new AssertionError("no stats module");
    }

    private static StatLine line(SiegeMatch match, UUID id, int round) {
        for (StatLine line : stats(match).lines()) {
            if (line.participant().equals(id) && line.round() == round) {
                return line;
            }
        }
        throw new AssertionError("no line of " + id + " in round " + round);
    }

    private static LaneSites.Side enemy(LaneSites.Side side) {
        return side == LaneSites.Side.BLUE ? LaneSites.Side.RED : LaneSites.Side.BLUE;
    }

    /** Destroys every structure of {@code side} with {@code attacker}'s ability damage, the core last. */
    private static double raze(SiegeMatch match, LaneSites.Side side, Player attacker) {
        double total = 0;
        for (Tower.Kind kind : List.of(Tower.Kind.OUTER, Tower.Kind.INNER, Tower.Kind.CORE)) {
            for (Tower tower : List.copyOf(match.towers(side))) {
                if (tower.kind() == kind && !tower.isDestroyed()) {
                    total += tower.health();
                    Mannequin hitbox = tower.hitbox();
                    assertNotNull(hitbox);
                    match.abilities().context().damage(attacker, hitbox, 1_000_000);
                    assertTrue(tower.isDestroyed(), tower.id());
                }
            }
        }
        return total;
    }

    private static double basicHit(MatchContext ctx, Player source, LivingEntity target, double amount)
            throws ReflectiveOperationException {
        Method hit = MatchContext.class.getDeclaredMethod("basicHit", Player.class, LivingEntity.class, double.class,
                DamageType.class);
        hit.setAccessible(true);
        return (double) hit.invoke(ctx, source, target, amount, DamageType.PLAYER_ATTACK);
    }

    private static void respawn(Player player) {
        ((TestPlayer) player).respawn();
    }

    private static List<String> drain(Player player) {
        List<String> out = new ArrayList<>();
        Component next;
        while ((next = ((TestPlayer) player).nextComponentMessage()) != null) {
            out.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return out;
    }

    // ------------------------------------------------------------------ recording

    @Test
    void aMatchRecordsSkillsBasicsStructuresCreaturesKillsAndDeathsPerAbility() throws Exception {
        SiegeMatch match = start(2, 0);
        Player a = member(match, 0);
        Player b = member(match, 1);
        MatchContext ctx = match.abilities().context();
        b.teleport(new Location(h.flatWorld(), 0.5, FlatWorld.SURFACE, 0.5));
        a.teleport(new Location(h.flatWorld(), 2.5, FlatWorld.SURFACE, 0.5));
        h.ticks(20);

        double before = b.getHealth();
        b.setNoDamageTicks(0);
        ctx.damage(a, b, 3);
        double skill = before - b.getHealth();
        assertTrue(skill > 0);
        before = b.getHealth();
        double basic = basicHit(ctx, a, b, 2);
        assertEquals(before - b.getHealth(), basic, 1.0E-6);
        assertTrue(basic > 0);

        StatLine lineA = line(match, a.getUniqueId(), 1);
        assertEquals("dummy", lineA.id());
        assertFalse(lineA.bot());
        assertEquals(skill, lineA.enemySkill(), 1.0E-6, "ability damage is skill damage");
        assertEquals(basic, lineA.enemyBasic(), 1.0E-6, "a basic attack hit is basic");
        assertEquals(skill + basic, line(match, b.getUniqueId(), 1).taken(), 1.0E-6);

        Zombie zombie = h.flatWorld().spawn(new Location(h.flatWorld(), 4.5, FlatWorld.SURFACE, 0.5), Zombie.class);
        creature.set(zombie);
        double zombieHealth = zombie.getHealth();
        ctx.damage(a, zombie, 5);
        assertEquals(zombieHealth - zombie.getHealth(), lineA.creature(), 1.0E-6, "minions and monsters apart");
        assertEquals(skill, lineA.enemySkill(), 1.0E-6, "not enemy player damage");

        double left = b.getHealth() + b.getAbsorptionAmount();
        b.setNoDamageTicks(0);
        ctx.damage(a, b, 10_000);
        assertTrue(b.isDead());
        assertEquals(skill + left, lineA.enemySkill(), 1.0E-6, "overkill counts the health that was left");
        assertEquals(1, lineA.kills());
        assertEquals(1, line(match, b.getUniqueId(), 1).deaths());
        respawn(b);

        LaneSites.Side enemySide = enemy(match.side(a.getUniqueId()));
        double structures = raze(match, enemySide, a);
        assertEquals(structures, lineA.structure(), 1.0E-6, "structure damage up to their health");
        assertEquals(skill + left, lineA.enemySkill(), 1.0E-6, "structures are not enemy player damage");
        assertEquals(1.0, lineA.seconds(), 0.06, "fight time counted");
        assertEquals(1, match.score().roundWins(0));

        untilFight(2);
        h.ticks(10);
        h.hub.games().stop(Component.text("test"));
        assertNull(siege.currentMatch());

        assertEquals(StatLine.Result.WIN, lineA.result());
        assertEquals(StatLine.Result.LOSS, line(match, b.getUniqueId(), 1).result());
        assertEquals(StatLine.Result.UNFINISHED, line(match, a.getUniqueId(), 2).result(), "stopped mid-round");

        Path csv = stats(match).lastFile();
        assertNotNull(csv, "a CSV per match");
        assertTrue(csv.getFileName().toString().endsWith("_fallback.csv"), csv.toString());
        byte[] bytes = Files.readAllBytes(csv);
        assertEquals((byte) 0xEF, bytes[0], "UTF-8 BOM for spreadsheets");
        List<String> rows = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertTrue(rows.getFirst().contains("적 피해(스킬)"), rows.getFirst());
        assertEquals(1 + 4, rows.size(), "two players, two rounds: " + rows);
        assertTrue(rows.stream().anyMatch(row -> row.startsWith("1,") && row.contains(",승,")), rows.toString());
        assertTrue(rows.stream().anyMatch(row -> row.startsWith("2,") && row.contains(",중단,")), rows.toString());

        StatTotals dummy = store.load().abilities().get("dummy");
        assertNotNull(dummy);
        assertEquals(2, dummy.rounds(), "the decided round only, both players");
        assertEquals(1, dummy.wins());
        assertEquals(1, dummy.kills());
        assertEquals(1, dummy.deaths());
        assertEquals(skill + left + basic, dummy.enemyDamage(), 0.02);
        assertTrue(store.load().bots().isEmpty());
    }

    @Test
    void theTotalsAddUpOverMatches() throws IOException {
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            StatLine line = new StatLine(1, player, "P", false, "dummy", "더미");
            line.fightTicks = 1200;
            line.enemySkill = 30;
            line.enemyBasic = 10;
            line.kills = 1;
            line.result = i == 0 ? StatLine.Result.WIN : StatLine.Result.LOSS;
            store.save(List.of(line), "toykingdom", LocalDateTime.of(2026, 1, 1, 12, 0));
        }
        StatTotals totals = store.load().abilities().get("dummy");
        assertEquals(2, totals.rounds());
        assertEquals(120, totals.seconds(), 1.0E-9);
        assertEquals(40, totals.enemyPerMinute(), 1.0E-9);
        assertEquals(0.5, totals.winRate(), 1.0E-9);
        assertEquals(2.0, totals.kda(), 1.0E-9);
        try (Stream<Path> files = Files.list(store.dir())) {
            assertEquals(3, files.count(), "two CSVs (same second: a suffix) and the totals");
        }
    }

    @Test
    void botsAreRecordedApartByTheirJob() throws Exception {
        SiegeMatch match = start(1, 1);
        Player player = match.session().participants().getFirst();
        SiegeBots bots = null;
        for (SiegeModule module : match.modules()) {
            if (module instanceof SiegeBots found) {
                bots = found;
            }
        }
        assertNotNull(bots);
        UUID bot = bots.ids().getFirst();
        Mannequin body = bots.body(bot);
        assertNotNull(body);
        MatchContext ctx = match.abilities().context();
        double before = body.getHealth();
        body.setNoDamageTicks(0);
        ctx.damage(player, body, 4);
        double dealt = before - body.getHealth();
        assertTrue(dealt > 0);
        StatLine mine = line(match, player.getUniqueId(), 1);
        assertTrue(mine.enemySkill() >= dealt - 1.0E-6, "hitting a bot is enemy damage");
        StatLine botLine = line(match, bot, 1);
        assertTrue(botLine.bot());
        assertEquals("knight", botLine.id());
        assertEquals("기사", botLine.displayName());
        assertTrue(botLine.taken() >= dealt - 1.0E-6);

        double basicBefore = botLine.enemyBasic();
        player.setNoDamageTicks(0);
        double health = player.getHealth();
        player.damage(3, DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(body).withDirectEntity(body)
                .build());
        assertEquals(basicBefore + (health - player.getHealth()), botLine.enemyBasic(), 1.0E-6,
                "a bot's plain hit is its basic attack");

        raze(match, enemy(match.side(player.getUniqueId())), player);
        h.hub.games().stop(Component.text("test"));
        SiegeStatsStore.Totals totals = store.load();
        assertEquals(1, totals.bots().get("knight").rounds(), "bot jobs apart");
        assertEquals(0, totals.bots().get("knight").wins());
        assertEquals(1, totals.abilities().get("dummy").rounds());
        assertNull(totals.abilities().get("knight"), "never mixed with the abilities");
    }

    @Test
    void disabledRecordsNothing() throws IOException {
        config.set(SiegeStatsSettings.ENABLED, false);
        SiegeMatch match = start(2, 0);
        Player a = member(match, 0);
        raze(match, enemy(match.side(a.getUniqueId())), a);
        h.ticks(5);
        h.hub.games().stop(Component.text("test"));
        assertTrue(stats(match).lines().isEmpty());
        assertFalse(Files.exists(store.totalsFile()));
    }

    // ------------------------------------------------------------------ the command

    private void seed(Map<String, double[]> entries) throws IOException {
        List<StatLine> lines = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : entries.entrySet()) {
            double[] v = entry.getValue(); // rounds, damage per round (60 s each), wins, kills
            for (int i = 0; i < (int) v[0]; i++) {
                StatLine line = new StatLine(1, UUID.randomUUID(), "P", false, entry.getKey(), entry.getKey());
                line.fightTicks = 1200;
                line.enemySkill = v[1];
                line.structure = 10;
                line.kills = i < v[3] ? 1 : 0;
                line.result = i < v[2] ? StatLine.Result.WIN : StatLine.Result.LOSS;
                lines.add(line);
            }
        }
        store.save(lines, "toykingdom", LocalDateTime.now());
    }

    @Test
    void theCommandRanksAbilitiesForAdminsOnly() throws IOException {
        Map<String, double[]> entries = new java.util.LinkedHashMap<>();
        entries.put("alpha", new double[] {4, 100, 1, 0});
        entries.put("bravo", new double[] {4, 60, 3, 4});
        entries.put("charlie", new double[] {4, 50, 2, 1});
        entries.put("delta", new double[] {4, 20, 0, 0});
        entries.put("echo", new double[] {1, 500, 1, 1});
        seed(entries);
        TestPlayer admin = h.join("Admin");
        admin.setOp(true);
        TestPlayer user = h.join("User");
        drain(admin);
        h.server.dispatchCommand(admin, "게임 공성전 통계");
        List<String> out = drain(admin);
        assertTrue(out.getFirst().contains("분당 적 피해순"), out.toString());
        assertEquals(6, out.size(), "header + one line per ability: " + out);
        assertTrue(out.get(1).startsWith("alpha") && out.get(1).contains("100/분") && out.get(1).endsWith("▲"),
                out.get(1));
        assertTrue(out.get(2).startsWith("bravo") && !out.get(2).contains("▲"), out.get(2));
        assertTrue(out.get(4).startsWith("delta") && out.get(4).endsWith("▼"), "median 55: 20 is low " + out);
        assertTrue(out.get(5).startsWith("echo") && !out.get(5).contains("▲"), "too few rounds: last, unmarked");

        h.server.dispatchCommand(admin, "게임 공성전 통계 승률");
        out = drain(admin);
        assertTrue(out.get(1).startsWith("bravo") && out.get(1).contains("75%"), out.toString());
        h.server.dispatchCommand(admin, "게임 공성전 통계 KDA");
        assertTrue(drain(admin).get(1).startsWith("bravo"));

        drain(user);
        h.server.dispatchCommand(user, "게임 공성전 통계");
        assertTrue(drain(user).stream().noneMatch(line -> line.contains("alpha")), "players never see them");

        h.server.dispatchCommand(admin, "게임 공성전 통계 초기화");
        assertTrue(Files.exists(store.totalsFile()), "asks first");
        drain(admin);
        h.server.dispatchCommand(admin, "게임 공성전 통계 초기화 확인");
        assertFalse(Files.exists(store.totalsFile()));
        drain(admin);
        h.server.dispatchCommand(admin, "게임 공성전 통계");
        assertTrue(drain(admin).getFirst().contains("기록이 없습니다"));
    }

    @Test
    void longListsArePaged() throws IOException {
        Map<String, double[]> entries = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 11; i++) {
            entries.put("ability" + (char) ('a' + i), new double[] {3, 10 + i, 1, 0});
        }
        seed(entries);
        TestPlayer admin = h.join("Admin");
        admin.setOp(true);
        drain(admin);
        h.server.dispatchCommand(admin, "게임 공성전 통계");
        List<String> out = drain(admin);
        assertEquals(1 + SiegeStatsCommand.PAGE_SIZE + 1, out.size(), "a chat screen: " + out);
        assertTrue(out.getFirst().contains("1/2"));
        assertTrue(out.getLast().contains("다음"));
        h.server.dispatchCommand(admin, "게임 공성전 통계 피해 2");
        out = drain(admin);
        assertEquals(1 + 3, out.size(), out.toString());
        assertTrue(out.getFirst().contains("2/2"));
    }

    @Test
    void theMedianMarksOnlyWellSampledOutliers() {
        Map<String, StatTotals> totals = new java.util.TreeMap<>();
        double[] dpm = {10, 50, 60, 70, 200};
        for (int i = 0; i < dpm.length; i++) {
            StatTotals entry = new StatTotals();
            entry.rounds = 5;
            entry.seconds = 60;
            entry.enemySkill = dpm[i];
            totals.put("a" + i, entry);
        }
        List<SiegeStatsReport.Row> rows = SiegeStatsReport.rows(totals, id -> id, SiegeStatsReport.Sort.DAMAGE,
                SiegeStatsSettings.DEFAULTS);
        assertEquals("a4", rows.getFirst().id());
        assertEquals(SiegeStatsReport.Mark.HIGH, rows.getFirst().mark());
        assertEquals(SiegeStatsReport.Mark.NONE, rows.get(1).mark(), "70 is within 40% of 60");
        assertEquals(SiegeStatsReport.Mark.LOW, rows.getLast().mark());
        assertEquals(SiegeStatsReport.Mark.NONE, SiegeStatsReport.rows(totals, id -> id, SiegeStatsReport.Sort.DAMAGE,
                new SiegeStatsSettings(true, 6, 40)).getFirst().mark(), "too few rounds: no marks");
    }
}
