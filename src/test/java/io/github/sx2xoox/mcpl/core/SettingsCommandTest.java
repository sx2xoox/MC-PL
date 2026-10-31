package io.github.sx2xoox.mcpl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.config.BlockDamageScope;
import io.github.sx2xoox.mcpl.core.config.Setting;
import io.github.sx2xoox.mcpl.core.config.SettingsSection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@code /게임 설정}: the hub's own settings as commands, the chat list, the console and a broken config.yml. */
class SettingsCommandTest {

    private ServerMock server;
    private MCPLPlugin plugin;
    private Hub hub;
    private PlayerMock admin;
    private PlayerMock user;
    private File file;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(MCPLPlugin.class);
        hub = plugin.hub();
        hub.games().register(new FakeGame("fake"));
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        user = server.addPlayer("User");
        file = new File(plugin.getDataFolder(), "config.yml");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<Component> drain(PlayerMock player) {
        List<Component> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(next);
        }
        return lines;
    }

    private static List<String> plainLines(List<Component> components) {
        return components.stream().map(SettingsCommandTest::plain).toList();
    }

    private static boolean anyContains(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    private YamlConfiguration saved() {
        return YamlConfiguration.loadConfiguration(file);
    }

    @Test
    void teamSizeIsSetSavedAndClamped() {
        assertTrue(server.dispatchCommand(admin, "게임 설정 팀 2"));
        assertEquals(2, hub.config().teamSize());
        assertEquals(2, saved().getInt("match.team-size"), "saved to config.yml");

        drain(admin);
        assertTrue(server.dispatchCommand(admin, "mcpl settings team 99"));
        assertEquals(32, hub.config().teamSize(), "clamped to the reader's maximum");
        assertEquals(32, saved().getInt("match.team-size"));
        List<String> clamped = plainLines(drain(admin));
        assertTrue(anyContains(clamped, "범위: 0명 ~ 32명"), "told the value was clamped, in plain numbers: " + clamped);

        assertTrue(server.dispatchCommand(admin, "게임 설정 팀 -3"));
        assertEquals(0, hub.config().teamSize());
        List<String> lines = plainLines(drain(admin));
        assertTrue(anyContains(lines, "개인전"), "0 is shown as free-for-all: " + lines);
    }

    @Test
    void countdownAndTerrainDamageAreSetAndSaved() {
        assertTrue(server.dispatchCommand(admin, "게임 설정 카운트다운 10"));
        assertEquals(10, hub.config().countdownSeconds());
        assertEquals(10, saved().getInt("match.countdown-seconds"));
        assertTrue(server.dispatchCommand(admin, "mcpl settings countdown 500"));
        assertEquals(60, hub.config().countdownSeconds());

        assertTrue(server.dispatchCommand(admin, "게임 설정 지형파괴 끄기"));
        assertEquals(BlockDamageScope.OFF, hub.config().abilityBlockDamage());
        assertEquals("OFF", saved().getString("protection.ability-block-damage"));
        assertTrue(server.dispatchCommand(admin, "mcpl settings terrain all"));
        assertEquals(BlockDamageScope.ALL_WORLDS, hub.config().abilityBlockDamage());
        assertTrue(server.dispatchCommand(admin, "게임 설정 지형파괴 맵에서만"));
        assertEquals(BlockDamageScope.MAP_WORLDS, hub.config().abilityBlockDamage());
        assertEquals("MAP_WORLDS", saved().getString("protection.ability-block-damage"));
    }

    @Test
    void savingKeepsTheCommentsOfConfigYml() throws IOException {
        server.dispatchCommand(admin, "게임 설정 팀 3");
        server.dispatchCommand(admin, "게임 설정 상자 끄기");
        String text = Files.readString(file.toPath());
        assertTrue(text.contains("# 팀당 인원. 0 = 개인전"), "hub comments survive");
        assertTrue(text.contains("경기에 상자를 놓을지 여부"), "game comments survive");
        assertTrue(text.contains("team-size: 3"));
    }

    @Test
    void playersCannotChangeSettings() {
        server.dispatchCommand(user, "게임 설정 팀 3");
        server.dispatchCommand(user, "mcpl settings loot off");
        server.dispatchCommand(user, "게임 설정");
        assertEquals(0, hub.config().teamSize());
        assertTrue(hub.config().gameSection("superpower").getBoolean("loot.enabled"));
        assertFalse(user.getOpenInventory().getTopInventory() != null
                && user.getOpenInventory().getTopInventory().getHolder(false)
                instanceof io.github.sx2xoox.mcpl.core.ui.SettingsMenu, "no menu for players");
    }

    @Test
    void theConsoleGetsTheListOfCurrentValues() {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        while (console.nextComponentMessage() != null) {
            // drop the startup messages
        }
        assertTrue(server.dispatchCommand(console, "게임 설정"));
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = console.nextComponentMessage()) != null) {
            lines.add(plain(next));
        }
        assertTrue(anyContains(lines, "현재 설정"), "header: " + lines);
        assertTrue(anyContains(lines, "팀 인원 개인전"), "hub values");
        assertTrue(anyContains(lines, "능력 지형 파괴 전투 맵에서만"));
        assertTrue(anyContains(lines, "상자 켜짐"), "game values");
        assertTrue(anyContains(lines, "자기장 시작 2분 (120초)"), "seconds are shown as minutes too");
        assertTrue(anyContains(lines, "파티클 양 ×1"));
        assertTrue(anyContains(lines, "월드 보호"), "the remaining settings in the footer");
    }

    @Test
    void listLinesTypeTheirCommandWhenClicked() {
        drain(admin);
        assertTrue(server.dispatchCommand(admin, "게임 설정 목록"));
        List<Component> lines = drain(admin);
        Component team = lines.stream().filter(line -> plain(line).contains("팀 인원")).findFirst().orElse(null);
        assertNotNull(team, "a line for the team size");
        assertEquals(ClickEvent.suggestCommand("/게임 설정 팀 "), team.clickEvent());
        assertNotNull(team.hoverEvent(), "hover explains the setting");
        Component border = lines.stream().filter(line -> plain(line).contains("자기장 시작")).findFirst().orElse(null);
        assertNotNull(border);
        assertEquals(ClickEvent.suggestCommand("/게임 설정 자기장 시작 "), border.clickEvent());

        assertTrue(server.dispatchCommand(admin, "mcpl settings list"));
        assertFalse(drain(admin).isEmpty(), "English twin");
    }

    @Test
    void aSettingNameAloneShowsItsValueAndUsage() {
        drain(admin);
        assertTrue(server.dispatchCommand(admin, "게임 설정 지형파괴"));
        List<String> lines = plainLines(drain(admin));
        assertTrue(anyContains(lines, "전투 맵에서만"), "current value: " + lines);
        assertTrue(anyContains(lines, "/게임 설정 지형파괴 <끄기|맵에서만|모든월드>"), "usage: " + lines);

        assertTrue(server.dispatchCommand(admin, "게임 설정 자기장"));
        List<String> border = plainLines(drain(admin));
        assertEquals(3, border.size(), "the group lists its three settings: " + border);
    }

    @Test
    void everySettingHasBothCommandNamesAndTexts() {
        for (SettingsSection section : hub.settings().sections()) {
            assertFalse(plain(section.title()).contains("missing"), section.id());
            for (Setting setting : section.settings()) {
                assertFalse(plain(setting.name()).contains("missing"), setting.id() + " has a name");
                assertFalse(setting.description().isEmpty(), setting.id() + " has an explanation");
                assertFalse(plain(setting.valueText(hub.messages())).contains("missing"), setting.id());
                if (setting instanceof Setting.Choice choice) {
                    for (Setting.Choice.Option option : choice.options()) {
                        assertFalse(plain(choice.label(option)).equals(option.value()),
                                setting.id() + " labels " + option.value());
                    }
                }
            }
        }
    }

    @Test
    void editsOnDiskAreNeverOverwrittenBeforeTheyAreLoaded() throws IOException {
        String edited = Files.readString(file.toPath()).replace("team-size: 0", "team-size: 4");
        Files.writeString(file.toPath(), edited);

        drain(admin);
        assertTrue(server.dispatchCommand(admin, "게임 설정 카운트다운 9"));
        server.dispatchCommand(admin, "게임 설정 상자 끄기");
        server.dispatchCommand(admin, "게임 경기장 초기화");
        assertEquals(5, hub.config().countdownSeconds(), "refused");
        assertTrue(hub.config().gameSection("superpower").getBoolean("loot.enabled"));
        assertEquals(edited, Files.readString(file.toPath()), "the owner's edit is kept");
        List<String> lines = plainLines(drain(admin));
        assertEquals(3, lines.stream().filter(line -> line.contains("/게임 리로드")).count(), "told why: " + lines);
        hub.config().setTeamSize(2); // a direct save is skipped too
        assertEquals(edited, Files.readString(file.toPath()));

        assertTrue(server.dispatchCommand(admin, "게임 리로드"));
        assertEquals(4, hub.config().teamSize(), "the edit is loaded");
        assertTrue(server.dispatchCommand(admin, "게임 설정 카운트다운 9"));
        assertEquals(9, saved().getInt("match.countdown-seconds"));
        assertEquals(4, saved().getInt("match.team-size"), "and kept by the next save");
    }

    @Test
    void anEditThatBreaksTheFileIsKeptAsideAndRefusesChanges() throws IOException {
        Files.writeString(file.toPath(), "match:\n\tteam-size: 2\n"); // no /게임 리로드
        assertTrue(hub.config().isWritable(), "nothing has noticed yet");

        drain(admin);
        server.dispatchCommand(admin, "게임 설정 팀 3");
        assertEquals(0, hub.config().teamSize());
        assertEquals("match:\n\tteam-size: 2\n", Files.readString(file.toPath()), "never written over");
        assertFalse(hub.config().isWritable(), "treated like a broken file on load");
        assertTrue(anyContains(plainLines(drain(admin)), "문법 오류"));
        File[] copies = plugin.getDataFolder().listFiles((dir, name) -> name.startsWith("config.yml.broken-"));
        assertNotNull(copies);
        assertEquals(1, copies.length, "a copy is kept aside");
    }

    @Test
    void aBrokenConfigRefusesEveryChange() throws IOException {
        Files.writeString(file.toPath(), "match:\n\tteam-size: 2\n");
        assertThrows(IllegalStateException.class, hub::reload);
        assertFalse(hub.config().isWritable());
        String broken = Files.readString(file.toPath());

        drain(admin);
        server.dispatchCommand(admin, "게임 설정 팀 4");
        server.dispatchCommand(admin, "게임 설정 지형파괴 끄기");
        server.dispatchCommand(admin, "게임 설정 상자 끄기");
        server.dispatchCommand(admin, "mcpl settings border delay 30");
        assertEquals(0, hub.config().teamSize());
        assertEquals(BlockDamageScope.MAP_WORLDS, hub.config().abilityBlockDamage());
        assertTrue(hub.config().gameSection("superpower").getBoolean("loot.enabled"));
        assertEquals(120, hub.config().gameSection("superpower").getInt("border.start-delay-seconds"));
        assertEquals(broken, Files.readString(file.toPath()), "the owner's file is untouched");
        List<String> lines = plainLines(drain(admin));
        assertEquals(4, lines.stream().filter(line -> line.contains("문법 오류")).count(), "every change is refused");
    }
}
