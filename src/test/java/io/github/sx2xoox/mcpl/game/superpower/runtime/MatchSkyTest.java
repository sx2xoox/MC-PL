package io.github.sx2xoox.mcpl.game.superpower.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.core.map.MapService;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerSettings;
import io.github.sx2xoox.mcpl.game.superpower.support.DummyAbility;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MatchSky}: in a plugin map world the fight starts at noon and ends at midnight as the border closes in, a
 * shower passes now and then, and the world is back at noon in clear weather when the match ends or is stopped.
 * Ordinary worlds and practice keep their sky.
 */
class MatchSkyTest {

    /** A world named like a plugin map world ({@code MapService#isMapWorld} goes by the name). */
    private static final String MAP_WORLD = MapService.WORLD_PREFIX + "test";

    private MatchHarness h;

    @AfterEach
    void tearDown() {
        if (h != null) {
            h.close();
        }
    }

    /**
     * A short border (1 s wait, 4 s shrink) that hurts nobody, no time limit, neutral abilities. The world's own clock
     * and weather stand still (as in a map world, {@code MapService#applyRules}), so only the match moves them.
     */
    private MatchHarness harness(String world) {
        h = MatchHarness.flat(world);
        World flat = world();
        flat.setGameRule(GameRules.ADVANCE_TIME, false);
        flat.setGameRule(GameRules.ADVANCE_WEATHER, false);
        h.registerDummy();
        h.settings().set(SuperpowerSettings.BORDER_DELAY, 1);
        h.settings().set(SuperpowerSettings.BORDER_SHRINK, 4);
        h.settings().set(SuperpowerSettings.BORDER_FINAL, 6);
        h.settings().set(SuperpowerSettings.BORDER_DAMAGE, 0);
        h.settings().set(SuperpowerSettings.TIME_LIMIT_SECONDS, 0);
        h.settings().set(SuperpowerSettings.SKY_RAIN_CHANCE, 0.0);
        return h;
    }

    private List<TestPlayer> players(String firstAbility) {
        TestPlayer first = h.join("First");
        TestPlayer second = h.join("Second");
        h.game.queueAssignment(first.getUniqueId(), firstAbility);
        h.game.queueAssignment(second.getUniqueId(), DummyAbility.INFO.id());
        return List.of(first, second);
    }

    private World world() {
        World world = h.flatWorld();
        assertNotNull(world);
        return world;
    }

    private static List<String> chat(TestPlayer player) {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return lines;
    }

    private static boolean anyContains(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    private static double attack(TestPlayer player) {
        return player.getAttribute(Attribute.ATTACK_DAMAGE).getValue();
    }

    private void untilFinalBorder(SuperpowerMatch match) {
        h.tickUntil(() -> match.border().state(match.fightTicks()) == BorderController.State.FINAL, 400,
                "the final border");
    }

    @Test
    void dayTurnsToNightAsTheBorderClosesAndNoonReturnsAtTheEnd() {
        harness(MAP_WORLD);
        World world = world();
        // A crash left the map world at night in the rain: the match starts at noon in clear weather all the same.
        world.setTime(18_000);
        world.setStorm(true);
        List<TestPlayer> players = players("werewolf");
        TestPlayer wolf = players.getFirst();
        SuperpowerMatch match = h.startFight();
        assertNotNull(match.sky(), "a map world gets a sky");
        assertEquals(MapService.NOON, world.getTime(), "the fight starts at noon");
        assertFalse(world.hasStorm());
        assertTrue(world.isDayTime());
        h.ticks(21);
        double nightDamage = match.params("werewolf").num("night_damage") / 100;
        assertEquals(1 + nightDamage / 2, attack(wolf), 1.0E-6, "the werewolf is half as fierce by day");

        h.tickUntil(() -> match.border().progress(match.fightTicks()) >= 0.25, 400, "a quarter of the shrink");
        h.ticks(MatchSky.TIME_PERIOD);
        long afternoon = world.getTime();
        assertTrue(afternoon > MapService.NOON && afternoon < MatchSky.SUNSET, "the sun goes down: " + afternoon);
        assertTrue(world.isDayTime());

        untilFinalBorder(match);
        h.ticks(21);
        assertEquals(MatchSky.MIDNIGHT, world.getTime(), "midnight at the final border");
        assertFalse(world.isDayTime(), "the final fight is at night");
        assertEquals(1 + nightDamage, attack(wolf), 1.0E-6, "the werewolf feels the night");
        List<String> lines = chat(wolf);
        assertTrue(anyContains(lines, "해가 저뭅니다"), "sunset announced: " + lines);
        assertTrue(anyContains(lines, "밤이 되었습니다"), "night announced: " + lines);
        assertFalse(world.hasStorm(), "no rain with sky.rain-chance 0");

        match.session().eliminate(players.get(1), null);
        assertEquals(GameState.ENDING, match.session().state(), "the werewolf won");
        assertEquals(MapService.NOON, world.getTime(), "noon again once the match is over");
        assertFalse(world.hasStorm());
    }

    @Test
    void aShowerPassesInsideItsWindowAndIsAnnounced() {
        harness(MAP_WORLD);
        h.settings().set(SuperpowerSettings.SKY_DAY_NIGHT, false);
        h.settings().set(SuperpowerSettings.SKY_RAIN_CHANCE, 1.0);
        h.settings().set(SuperpowerSettings.SKY_RAIN_MIN, 5);
        h.settings().set(SuperpowerSettings.SKY_RAIN_MAX, 5);
        World world = world();
        TestPlayer first = players(DummyAbility.INFO.id()).getFirst();
        SuperpowerMatch match = h.startFight();
        MatchSky sky = match.sky();
        assertNotNull(sky);
        assertFalse(sky.dayNight(), "sky.day-night off");
        long start = sky.rainStart();
        long[] window = MatchSky.rainWindow(match.border(), 0, 100);
        assertTrue(start >= window[0] && start <= window[1], start + " inside " + window[0] + ".." + window[1]);
        assertTrue(start >= MatchSky.RAIN_EARLIEST_TICKS, "not in the first seconds of the fight");
        assertEquals(start + 100, sky.rainEnd(), "rain-seconds 5..5");

        h.tickUntil(world::hasStorm, (int) start + 40, "the rain");
        assertEquals(start, match.fightTicks(), "it starts on time");
        assertFalse(world.isThundering(), "no thunder");
        assertTrue(anyContains(chat(first), "비가 내리기 시작합니다"));

        h.tickUntil(() -> !world.hasStorm(), 140, "the rain to stop");
        assertEquals(start + 100, match.fightTicks(), "it stops on time");
        assertTrue(anyContains(chat(first), "비가 그쳤습니다"));
        h.ticks(60);
        assertFalse(world.hasStorm(), "one shower per match");
        assertEquals(MapService.NOON, world.getTime(), "sky.day-night off keeps noon");
    }

    @Test
    void stoppingTheMatchBringsBackNoonAndClearSkies() {
        harness(MAP_WORLD);
        h.settings().set(SuperpowerSettings.SKY_RAIN_CHANCE, 1.0);
        h.settings().set(SuperpowerSettings.SKY_RAIN_MIN, 30);
        h.settings().set(SuperpowerSettings.SKY_RAIN_MAX, 30);
        World world = world();
        players(DummyAbility.INFO.id());
        SuperpowerMatch match = h.startFight();
        assertNotNull(match.sky(), "a map world gets a sky");
        h.tickUntil(world::hasStorm, 800, "the rain");
        assertFalse(world.isDayTime(), "night by now");

        assertTrue(h.hub.games().stop(Component.text("test")));
        assertEquals(MapService.NOON, world.getTime(), "noon after /게임 중지");
        assertFalse(world.hasStorm(), "clear after /게임 중지");
        assertNull(h.match());
    }

    @Test
    void ordinaryWorldsKeepTheirSky() {
        harness("world");
        h.settings().set(SuperpowerSettings.SKY_RAIN_CHANCE, 1.0);
        h.settings().set(SuperpowerSettings.SKY_RAIN_MIN, 5);
        h.settings().set(SuperpowerSettings.SKY_RAIN_MAX, 5);
        World world = world();
        world.setTime(1000);
        players(DummyAbility.INFO.id());
        SuperpowerMatch match = h.startFight();
        assertNull(match.sky(), "no sky outside plugin map worlds");
        untilFinalBorder(match);
        h.ticks((int) MatchSky.RAIN_EARLIEST_TICKS + 120);
        assertEquals(1000, world.getTime(), "the time of an ordinary world is never changed");
        assertFalse(world.hasStorm(), "nor its weather");
    }

    @Test
    void practiceKeepsTheSky() {
        harness(MAP_WORLD);
        h.settings().set(SuperpowerSettings.SKY_RAIN_CHANCE, 1.0);
        h.settings().set(SuperpowerSettings.SKY_RAIN_MIN, 5);
        h.settings().set(SuperpowerSettings.SKY_RAIN_MAX, 5);
        World world = world();
        world.setTime(1000);
        TestPlayer admin = h.join("Admin");
        admin.setOp(true);
        assertTrue(h.server.dispatchCommand(admin, "게임 능력 연습 " + DummyAbility.INFO.id()));
        h.tickUntil(() -> h.match() != null && h.match().phase() == MatchPhase.FIGHT, 400, "the practice");
        SuperpowerMatch practice = h.match();
        assertNotNull(practice);
        assertTrue(practice.isPractice());
        assertNull(practice.sky(), "practice never changes the sky");
        h.ticks((int) MatchSky.RAIN_EARLIEST_TICKS + 120);
        assertEquals(1000, world.getTime());
        assertFalse(world.hasStorm());
    }

    @Test
    void theSkyIsSetFromTheSettingsCommands() {
        harness(MAP_WORLD);
        TestPlayer admin = h.join("Admin");
        admin.setOp(true);
        Logger logger = h.hub.plugin().getLogger();
        assertTrue(SuperpowerSettings.read(h.settings(), logger).dayNight(), "on by default");
        for (String command : List.of("게임 설정 밤낮 끄기", "게임 설정 비확률 50", "게임 설정 비시간 최소 30",
                "mcpl settings rain-seconds max 10")) {
            assertTrue(h.server.dispatchCommand(admin, command), command);
        }
        SuperpowerSettings read = SuperpowerSettings.read(h.settings(), logger);
        assertFalse(read.dayNight());
        assertEquals(0.5, read.rainChance(), 1.0E-9, "typed in percent, stored as 0..1");
        assertEquals(0.5, h.settings().getDouble(SuperpowerSettings.SKY_RAIN_CHANCE), 1.0E-9);
        assertEquals(30 * 20, read.rainMinTicks());
        assertEquals(30 * 20, read.rainMaxTicks(), "never shorter than the minimum");
        h.settings().set(SuperpowerSettings.SKY_RAIN_CHANCE, 35);
        assertEquals(0.35, SuperpowerSettings.read(h.settings(), logger).rainChance(), 1.0E-9, "35 reads as 35%");
        assertFalse(SuperpowerSettings.read(h.settings(), logger).forPractice().dayNight(), "never in practice");
    }
}
