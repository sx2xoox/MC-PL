package io.github.sx2xoox.mcpl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class CommandTest {

    private ServerMock server;
    private Hub hub;
    private PlayerMock admin;
    private PlayerMock user;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        hub = MockBukkit.load(MCPLPlugin.class).hub();
        hub.games().register(new FakeGame("fake"));
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        user = server.addPlayer("User");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void koreanAndEnglishTwinsChangeSettings() {
        assertTrue(server.dispatchCommand(admin, "게임 설정 팀 2"));
        assertEquals(2, hub.config().teamSize());
        assertTrue(server.dispatchCommand(admin, "mcpl settings team 0"));
        assertEquals(0, hub.config().teamSize());

        hub.config().setRotationGames(java.util.List.of("unregistered"));
        server.dispatchCommand(admin, "게임 순환 켜기");
        assertFalse(hub.config().rotationEnabled(), "refused: the rotation list has no registered game");
        hub.config().setRotationGames(java.util.List.of("fake"));
        server.dispatchCommand(admin, "게임 순환 켜기");
        assertTrue(hub.config().rotationEnabled());
        assertTrue(hub.games().rotation().isWaiting(), "turning rotation on while idle schedules a match");
        server.dispatchCommand(admin, "mg rotation off");
        assertFalse(hub.config().rotationEnabled());
    }

    @Test
    void playersChooseToSpectateOrJoin() {
        server.dispatchCommand(user, "게임 관전");
        assertTrue(hub.games().prefersSpectating(user.getUniqueId()));
        server.dispatchCommand(user, "mcpl join");
        assertFalse(hub.games().prefersSpectating(user.getUniqueId()));
    }

    @Test
    void adminCommandsNeedPermission() {
        server.dispatchCommand(user, "게임 설정 팀 3");
        assertEquals(0, hub.config().teamSize());
        server.dispatchCommand(user, "게임 시작 fake");
        assertNull(hub.games().session());
    }

    @Test
    void startByKoreanNameAndStop() {
        server.dispatchCommand(admin, "게임 시작 가짜 게임 fake");
        GameSession session = hub.games().session();
        assertNotNull(session);
        assertEquals("fake", session.game().id());

        server.dispatchCommand(admin, "게임 중지");
        assertNull(hub.games().session());
    }

    @Test
    void arenaIsSavedFromThePlayerLocation() {
        server.dispatchCommand(admin, "게임 경기장 설정 40");
        assertTrue(hub.config().hasArena());
        assertEquals(40, hub.config().arenaRadius());
        assertEquals(admin.getLocation().getBlockX(), hub.config().arenaCenter().getBlockX());

        server.dispatchCommand(admin, "mcpl arena reset");
        assertFalse(hub.config().hasArena());
    }
}
