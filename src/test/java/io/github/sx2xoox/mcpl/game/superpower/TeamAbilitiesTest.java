package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.ui.Sidebar;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** In team mode teammates see each other's abilities: sidebar, chat at the fight start and {@code /게임 능력 팀}. */
class TeamAbilitiesTest {

    private MatchHarness h;

    @BeforeEach
    void setUp() {
        h = new MatchHarness();
        h.hub.config().setTeamSize(2);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<String> drain(TestPlayer player) {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(plain(next));
        }
        return lines;
    }

    private List<String> sidebar(GameSession session, Player viewer) {
        List<String> lines = new ArrayList<>();
        for (Component line : session.sidebar(viewer).lines()) {
            lines.add(plain(line));
        }
        return lines;
    }

    private String abilityName(SuperpowerMatch match, Player player) {
        Participant participant = match.participant(player.getUniqueId());
        assertNotNull(participant);
        AbilityRegistry.Entry entry = h.game.registry().get(participant.abilityId());
        assertNotNull(entry);
        return entry.info().name();
    }

    private static TestPlayer teammate(GameSession session, List<TestPlayer> players, TestPlayer of) {
        MatchTeam team = session.teamOf(of);
        assertNotNull(team);
        for (TestPlayer other : players) {
            if (other != of && team.contains(other)) {
                return other;
            }
        }
        throw new AssertionError("no teammate for " + of.getName());
    }

    private static boolean anyContains(List<String> lines, String... parts) {
        return lines.stream().anyMatch(line -> {
            for (String part : parts) {
                if (!line.contains(part)) {
                    return false;
                }
            }
            return true;
        });
    }

    private SuperpowerMatch fight(List<TestPlayer> players) {
        GameSession session = h.start();
        for (TestPlayer player : players) {
            drain(player);
        }
        h.tickUntilPhase(MatchPhase.FIGHT);
        h.ticks(20);
        SuperpowerMatch match = h.match();
        assertNotNull(match);
        assertTrue(session.isTeamMode());
        return match;
    }

    @Test
    void teammatesSeeEachOthersAbilitiesButNotTheEnemies() {
        List<TestPlayer> players = h.join(4);
        GameSession session = h.start();
        for (TestPlayer player : players) {
            drain(player);
        }
        h.tickUntilPhase(MatchPhase.FIGHT);
        h.ticks(20);
        SuperpowerMatch match = h.match();
        assertNotNull(match);

        for (TestPlayer player : players) {
            TestPlayer mate = teammate(session, players, player);
            String mateAbility = abilityName(match, mate);
            List<String> board = sidebar(session, player);
            assertTrue(anyContains(board, "▸", mate.getName(), mateAbility),
                    player.getName() + " sees " + mate.getName() + "'s ability on the sidebar: " + board);
            assertTrue(board.size() <= Sidebar.MAX_LINES);
            List<String> chat = drain(player);
            assertTrue(anyContains(chat, "팀원의 능력"), "team summary header for " + player.getName());
            assertTrue(anyContains(chat, mate.getName(), "→", mateAbility), "team summary line: " + chat);
            for (TestPlayer enemy : players) {
                if (enemy == player || enemy == mate) {
                    continue;
                }
                assertFalse(anyContains(board, enemy.getName()), "no enemy on the sidebar: " + board);
                assertFalse(anyContains(chat, enemy.getName(), "→"), "no enemy ability in chat: " + chat);
            }
        }
    }

    @Test
    void aTeammatesAbilityStaysHiddenUntilTheirRevealLands() {
        h.settings().set("selection.reveal-seconds", 5);
        List<TestPlayer> players = h.join(4);
        GameSession session = h.start();
        h.tickUntilPhase(MatchPhase.REVEAL);
        h.ticks(20);
        SuperpowerMatch match = h.match();
        assertNotNull(match);
        TestPlayer player = players.getFirst();
        TestPlayer mate = teammate(session, players, player);
        List<String> board = sidebar(session, player);
        assertTrue(anyContains(board, "▸", mate.getName(), "???"), "hidden while revealing: " + board);
        assertFalse(anyContains(board, abilityName(match, mate)), "no spoiler before the reveal: " + board);

        h.tickUntilPhase(MatchPhase.PREP);
        h.ticks(10);
        assertTrue(anyContains(sidebar(session, player), mate.getName(), abilityName(match, mate)),
                "visible once revealed (prep phase, rerolls can still change it)");
    }

    @Test
    void eliminatedTeammatesAreCrossedOutAndKeepTheirTeamOnTheSidebar() {
        List<TestPlayer> players = h.join(4);
        SuperpowerMatch match = fight(players);
        GameSession session = match.session();
        TestPlayer player = players.getFirst();
        TestPlayer mate = teammate(session, players, player);
        String mateAbility = abilityName(match, mate);
        TestPlayer enemy = players.stream().filter(p -> p != player && p != mate).findFirst().orElseThrow();
        session.eliminate(mate, enemy);
        h.ticks(20);

        assertTrue(anyContains(sidebar(session, player), "☠", mate.getName(), mateAbility),
                "eliminated teammate: " + sidebar(session, player));
        List<String> spectating = sidebar(session, mate);
        assertTrue(anyContains(spectating, "▸", player.getName(), abilityName(match, player)),
                "an eliminated player still sees their team: " + spectating);
        assertTrue(spectating.size() <= Sidebar.MAX_LINES);
    }

    @Test
    void bigTeamsFitTheSidebar() {
        h.hub.config().setTeamSize(6);
        List<TestPlayer> players = h.join(12);
        SuperpowerMatch match = fight(players);
        for (TestPlayer player : players) {
            List<String> board = sidebar(match.session(), player);
            assertTrue(board.size() <= Sidebar.MAX_LINES, "at most " + Sidebar.MAX_LINES + " lines: " + board);
            assertTrue(anyContains(board, "외 3명"), "the rest of the team is counted: " + board);
            assertTrue(anyContains(board, "자기장"), "the border line still fits: " + board);
        }
    }

    @Test
    void commandShowsTheTeamOnDemand() {
        List<TestPlayer> players = h.join(4);
        SuperpowerMatch match = fight(players);
        TestPlayer player = players.getFirst();
        TestPlayer mate = teammate(match.session(), players, player);
        drain(player);
        assertTrue(h.server.dispatchCommand(player, "게임 능력 팀"));
        List<String> chat = drain(player);
        assertTrue(anyContains(chat, mate.getName(), "→", abilityName(match, mate)), "command output: " + chat);
        assertEquals(SuperpowerMatch.TeamAbilitiesResult.OK, match.showTeamAbilities(player));
    }

    @Test
    void canBeTurnedOff() {
        h.settings().set("show-team-abilities", false);
        List<TestPlayer> players = h.join(4);
        GameSession session = h.start();
        for (TestPlayer player : players) {
            drain(player);
        }
        h.tickUntilPhase(MatchPhase.FIGHT);
        h.ticks(20);
        SuperpowerMatch match = h.match();
        assertNotNull(match);
        for (TestPlayer player : players) {
            TestPlayer mate = teammate(session, players, player);
            assertFalse(anyContains(sidebar(session, player), "▸"), "no teammate lines");
            assertFalse(anyContains(drain(player), abilityName(match, mate)), "no team summary");
        }
        assertEquals(SuperpowerMatch.TeamAbilitiesResult.DISABLED, match.showTeamAbilities(players.getFirst()));
        h.server.dispatchCommand(players.getFirst(), "게임 능력 팀");
        assertTrue(anyContains(drain(players.getFirst()), "꺼 두었습니다"));
    }

    @Test
    void freeForAllHasNoTeam() {
        h.hub.config().setTeamSize(0);
        List<TestPlayer> players = h.join(3);
        SuperpowerMatch match = fight3(players);
        for (TestPlayer player : players) {
            assertFalse(anyContains(sidebar(match.session(), player), "▸"));
        }
        assertEquals(SuperpowerMatch.TeamAbilitiesResult.NOT_TEAM_MODE, match.showTeamAbilities(players.getFirst()));
    }

    private SuperpowerMatch fight3(List<TestPlayer> players) {
        h.start();
        h.tickUntilPhase(MatchPhase.FIGHT);
        h.ticks(20);
        SuperpowerMatch match = h.match();
        assertNotNull(match);
        assertFalse(match.session().isTeamMode());
        UUID first = players.getFirst().getUniqueId();
        assertNotNull(match.participant(first));
        return match;
    }
}
