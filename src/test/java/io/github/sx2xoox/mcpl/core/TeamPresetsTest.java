package io.github.sx2xoox.mcpl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.game.TeamPresets;
import io.github.sx2xoox.mcpl.core.ui.TeamMenu;
import io.github.sx2xoox.mcpl.game.superpower.support.TestServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@code /게임 팀 선택|지정|해제|초기화|목록|섞기}, the team menu and how a match uses the picks. */
class TeamPresetsTest {

    private ServerMock server;
    private Hub hub;
    private FakeGame game;
    private PlayerMock admin;
    private PlayerMock user;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new TestServer());
        server.addSimpleWorld("world");
        hub = MockBukkit.load(MCPLPlugin.class).hub();
        game = new FakeGame("teamfake", true);
        hub.games().register(game);
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        user = server.addPlayer("User");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private TeamPresets presets() {
        return hub.teamPresets();
    }

    private static String text(PlayerMock player) {
        StringBuilder out = new StringBuilder();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            out.append(PlainTextComponentSerializer.plainText().serialize(next)).append('\n');
        }
        return out.toString();
    }

    private int countdownTicks() {
        return hub.config().countdownSeconds() * 20 + 5;
    }

    @Test
    void playersPickTheirTeamAndAdminsAssign() {
        assertTrue(server.dispatchCommand(user, "게임 팀 선택 빨강"));
        assertEquals("red", presets().get(user.getUniqueId()));
        assertTrue(text(user).contains("빨강 팀"));

        server.dispatchCommand(user, "게임 팀 선택 초록");
        assertEquals("red", presets().get(user.getUniqueId()), "no match and no team size: 빨강 and 파랑 only");
        assertTrue(text(user).contains("빨강, 파랑"), "the refusal names the teams that can be picked");

        server.dispatchCommand(user, "게임 팀 지정 Admin 파랑");
        assertNull(presets().get(admin.getUniqueId()), "assigning needs admin");

        assertTrue(server.dispatchCommand(admin, "mcpl team assign User blue"));
        assertEquals("blue", presets().get(user.getUniqueId()));
        assertTrue(text(user).contains("관리자 지정"), "the player hears about it");

        server.dispatchCommand(admin, "게임 팀 목록");
        String list = text(admin);
        assertTrue(list.contains("파랑 팀 (1) User"), list);

        hub.config().setTeamChoice(false);
        server.dispatchCommand(user, "게임 팀 선택 빨강");
        assertEquals("blue", presets().get(user.getUniqueId()), "team-choice off: players cannot pick");
        hub.config().setTeamChoice(true);

        server.dispatchCommand(user, "게임 팀 해제");
        assertNull(presets().get(user.getUniqueId()));
        server.dispatchCommand(admin, "게임 팀 지정 User 빨강");
        server.dispatchCommand(admin, "게임 팀 해제 User");
        assertNull(presets().get(user.getUniqueId()));

        server.dispatchCommand(admin, "게임 팀 지정 User 빨강");
        server.dispatchCommand(admin, "게임 팀 초기화");
        assertTrue(presets().all().isEmpty());
    }

    @Test
    void shuffleSplitsTheWaitingPlayers() {
        server.addPlayer("P3");
        server.addPlayer("P4");
        assertTrue(server.dispatchCommand(admin, "게임 팀 섞기"));
        assertEquals(4, presets().all().size());
        assertEquals(2, presets().members("red").size());
        assertEquals(2, presets().members("blue").size());
    }

    @Test
    void theModeDecidesWhichTeamsCanBePicked() throws GameStartException {
        hub.config().setTeamSize(1);
        for (int i = 0; i < 2; i++) {
            server.addPlayer("P" + i);
        }
        assertEquals(4, presets().available().size(), "team size 1 and four waiting players: four teams");

        game.fixedTeams = 2; // like 공성전
        GameSession session = hub.games().start(game, null);
        assertEquals(GameState.COUNTDOWN, session.state());
        server.dispatchCommand(user, "게임 팀 선택 초록");
        assertNull(presets().get(user.getUniqueId()), "a two-team mode only has 빨강 and 파랑");
        server.dispatchCommand(user, "게임 팀 선택 파랑");
        assertEquals("blue", presets().get(user.getUniqueId()));
    }

    @Test
    void aMatchFormsItsTeamsFromThePicks() throws GameStartException {
        game.fixedTeams = 2;
        game.lateJoin = true;
        PlayerMock p3 = server.addPlayer("P3");
        PlayerMock p4 = server.addPlayer("P4");
        presets().set(admin.getUniqueId(), "blue");
        presets().set(user.getUniqueId(), "blue");
        presets().set(p3.getUniqueId(), "green"); // not a team of a two-team match

        GameSession session = hub.games().start(game, null);
        server.getScheduler().performTicks(countdownTicks());
        assertEquals(GameState.RUNNING, session.state());
        MatchTeam red = session.teams().getFirst();
        MatchTeam blue = session.teams().get(1);
        assertEquals("red", red.id());
        assertTrue(blue.contains(admin) && blue.contains(user), "the picks are kept");
        assertTrue(red.contains(p3) && red.contains(p4), "the rest fill the other team");
        assertTrue(text(p3).contains("자동 배정"), "a refused pick is explained");

        // a late joiner goes to the picked team, someone without a pick to the smallest
        PlayerMock late = new PlayerMock(server, "Late");
        presets().set(late.getUniqueId(), "blue");
        server.addPlayer(late);
        assertEquals(blue, session.teamOf(late));
        PlayerMock other = server.addPlayer("Other");
        assertEquals(red, session.teamOf(other));

        hub.games().stop(Component.text("test"));
        assertEquals("blue", presets().get(admin.getUniqueId()), "picks stay for the next match");
    }

    @Test
    void pickersAreMovedSoNoTeamStartsEmpty() throws GameStartException {
        game.fixedTeams = 2;
        PlayerMock p3 = server.addPlayer("P3");
        for (PlayerMock player : List.of(admin, user, p3)) {
            presets().set(player.getUniqueId(), "red");
        }
        GameSession session = hub.games().start(game, null);
        server.getScheduler().performTicks(countdownTicks());
        MatchTeam red = session.teams().getFirst();
        MatchTeam blue = session.teams().get(1);
        assertEquals(2, red.size());
        assertEquals(1, blue.size(), "the match never starts with an empty team");
        List<PlayerMock> moved = new ArrayList<>();
        for (PlayerMock player : List.of(admin, user, p3)) {
            if (blue.contains(player)) {
                moved.add(player);
            }
        }
        assertEquals(1, moved.size());
        assertTrue(text(moved.getFirst()).contains("인원을 맞추느라"));
    }

    @Test
    void theMenuPicksATeam() {
        assertTrue(server.dispatchCommand(user, "게임 팀 선택"));
        TeamMenu menu = user.getOpenInventory().getTopInventory().getHolder(false) instanceof TeamMenu open
                ? open : null;
        assertNotNull(menu, "no team: the menu opens");
        assertNotNull(menu.teamAt(1));
        assertEquals("blue", menu.teamAt(1).id());

        InventoryClickEvent click = new InventoryClickEvent(user.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                1, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(click);
        assertTrue(click.isCancelled(), "nothing moves");
        assertEquals("blue", presets().get(user.getUniqueId()));

        InventoryClickEvent auto = new InventoryClickEvent(user.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                menu.autoSlot(), ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(auto);
        assertNull(presets().get(user.getUniqueId()), "the last slot goes back to automatic placement");
    }
}
