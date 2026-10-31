package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.game.superpower.config.AbilityConfig;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AbilityCommandTest {

    private MatchHarness h;
    private TestPlayer admin;
    private TestPlayer user;

    @BeforeEach
    void setUp() {
        h = new MatchHarness();
        admin = h.join("Admin");
        admin.setOp(true);
        user = h.join("User");
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private static List<String> drain(TestPlayer player) {
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

    @Test
    void listShowsEveryAbility() {
        drain(user);
        assertTrue(h.server.dispatchCommand(user, "게임 능력 목록"));
        List<String> lines = drain(user);
        assertTrue(anyContains(lines, "초능력 목록"));
        assertTrue(anyContains(lines, "화염술사"));
        assertTrue(anyContains(lines, "도박사"));
        assertTrue(h.server.dispatchCommand(user, "mcpl ability list"));
        assertTrue(anyContains(drain(user), "빙결술사"));
    }

    @Test
    void infoAcceptsKoreanNamesIdsAndPrefixes() {
        drain(user);
        h.server.dispatchCommand(user, "게임 능력 정보 화염술사");
        List<String> korean = drain(user);
        assertTrue(anyContains(korean, "화염술사"));
        assertTrue(anyContains(korean, "화염구"), "skills are listed");
        assertFalse(anyContains(korean, "{"), "params are rendered");

        h.server.dispatchCommand(user, "mcpl ability info frost");
        assertTrue(anyContains(drain(user), "빙결술사"));
        h.server.dispatchCommand(user, "게임 능력 정보 환영");
        assertTrue(anyContains(drain(user), "환영술사"), "unique prefixes work");
        h.server.dispatchCommand(user, "게임 능력 정보 없는능력");
        assertTrue(anyContains(drain(user), "찾을 수 없습니다"));
    }

    @Test
    void selectionModeIsAdminOnlyAndSaved() {
        h.server.dispatchCommand(user, "게임 능력 선택방식 CHOICE");
        assertEquals(SelectionMode.RANDOM, h.game.selectionMode(), "players cannot change the mode");

        assertTrue(h.server.dispatchCommand(admin, "게임 능력 선택방식 CHOICE"));
        assertEquals(SelectionMode.CHOICE, h.game.selectionMode());
        assertEquals("CHOICE", h.hub.plugin().getConfig().getString("games.superpower.selection.mode"));
        File configFile = new File(h.hub.plugin().getDataFolder(), "config.yml");
        assertEquals("CHOICE", YamlConfiguration.loadConfiguration(configFile)
                .getString("games.superpower.selection.mode"), "saved to config.yml");

        assertTrue(h.server.dispatchCommand(admin, "mcpl ability mode random"));
        assertEquals(SelectionMode.RANDOM, h.game.selectionMode());
    }

    @Test
    void assignQueuesForTheNextMatch() {
        h.server.dispatchCommand(user, "게임 능력 지정 User 화염술사");
        assertTrue(h.game.pendingAssignments().isEmpty(), "players cannot assign");

        assertTrue(h.server.dispatchCommand(admin, "게임 능력 지정 User 빙결술사"));
        assertEquals("frost", h.game.pendingAssignments().get(user.getUniqueId()));
        assertTrue(h.server.dispatchCommand(admin, "mcpl ability assign User pyro"));
        assertEquals("pyro", h.game.pendingAssignments().get(user.getUniqueId()), "a newer assignment replaces");
        drain(admin);
        h.server.dispatchCommand(admin, "게임 능력 지정 Nobody 화염술사");
        assertTrue(anyContains(drain(admin), "찾을 수 없습니다"));
    }

    @Test
    void rerollOutsideAMatchExplainsWhy() {
        drain(user);
        h.server.dispatchCommand(user, "게임 능력 리롤");
        assertTrue(anyContains(drain(user), "진행 중인 초능력전쟁 경기가 없습니다"));
    }

    @Test
    void reloadRereadsAbilitiesYml() throws Exception {
        File file = new File(h.hub.plugin().getDataFolder(), AbilityConfig.FILE_NAME);
        assertTrue(file.exists(), "abilities.yml is generated on enable");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("abilities.pyro.enabled", false);
        yaml.set("abilities.frost.params.cooldown", 2.5);
        yaml.save(file);

        assertTrue(h.server.dispatchCommand(admin, "게임 리로드"));
        AbilityConfig.Snapshot snapshot = h.game.abilities().snapshot();
        assertFalse(snapshot.isEnabled("pyro"));
        assertEquals(2.5, snapshot.params(h.game.registry().get("frost").info()).cooldownSeconds(), 1.0E-9);

        drain(admin);
        h.server.dispatchCommand(admin, "게임 능력 지정 User 화염술사");
        assertTrue(anyContains(drain(admin), "꺼져 있어"), "disabled abilities cannot be assigned");
        assertNull(h.game.pendingAssignments().get(user.getUniqueId()));
        assertNotNull(h.game.findAbility("pyro"), "disabled abilities can still be looked up");
    }
}
