package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.core.ui.SettingsEditor;
import io.github.sx2xoox.mcpl.core.ui.SettingsMenu;
import io.github.sx2xoox.mcpl.game.superpower.loot.LootSettings;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The 초능력전쟁 entries of {@code /게임 설정} and the settings menu: every command changes, saves and clamps its
 * value the way the match reads it, the menu steps them, and a running match keeps its settings.
 */
class SuperpowerSettingsTest {

    private static final Logger LOGGER = Logger.getLogger("SuperpowerSettingsTest");

    private MatchHarness h;
    private TestPlayer admin;
    private File file;

    @BeforeEach
    void setUp() {
        h = new MatchHarness();
        admin = h.join("Admin");
        admin.setOp(true);
        file = new File(h.hub.plugin().getDataFolder(), "config.yml");
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private void run(String command) {
        assertTrue(h.server.dispatchCommand(admin, command), command);
    }

    private ConfigurationSection live() {
        return h.settings();
    }

    /** {@code games.superpower} as saved on disk. */
    private ConfigurationSection saved() {
        ConfigurationSection section = YamlConfiguration.loadConfiguration(file)
                .getConfigurationSection("games.superpower");
        assertNotNull(section);
        return section;
    }

    private SuperpowerSettings read() {
        return SuperpowerSettings.read(live(), LOGGER);
    }

    private LootSettings loot() {
        return LootSettings.read(live(), LOGGER);
    }

    private List<String> chat() {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = admin.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return lines;
    }

    @Test
    void matchSettingsAreSetSavedAndClamped() {
        run("게임 설정 선택방식 선택");
        assertEquals(SelectionMode.CHOICE, h.game.selectionMode());
        assertEquals("CHOICE", saved().getString(SuperpowerSettings.MODE));
        run("mcpl settings mode random");
        assertEquals(SelectionMode.RANDOM, read().selectionMode());

        run("게임 설정 리롤 3");
        assertEquals(3, read().rerolls());
        assertEquals(3, saved().getInt(SuperpowerSettings.REROLLS));
        run("mcpl settings rerolls 50");
        assertEquals(10, saved().getInt(SuperpowerSettings.REROLLS), "clamped like the reader");

        run("게임 설정 준비시간 30");
        assertEquals(30 * 20, read().prepTicks());
        run("mcpl settings prep 999");
        assertEquals(300, saved().getInt(SuperpowerSettings.PREP_SECONDS));

        run("게임 설정 제한시간 0");
        assertEquals(0, read().timeLimitTicks(), "0 = unlimited");
        run("mcpl settings time-limit 100000");
        assertEquals(7200, saved().getInt(SuperpowerSettings.TIME_LIMIT_SECONDS));

        run("게임 설정 자기장 시작 90");
        assertEquals(90 * 20, read().borderDelayTicks());
        run("게임 설정 자기장 축소 -5");
        assertEquals(0, saved().getInt(SuperpowerSettings.BORDER_SHRINK));
        run("게임 설정 자기장 최종 30");
        assertEquals(30.0, read().borderFinalSize());
        run("mcpl settings border final 0");
        assertEquals(1, saved().getInt(SuperpowerSettings.BORDER_FINAL), "at least one block");
        run("mcpl settings border delay 45");
        assertEquals(45, saved().getInt(SuperpowerSettings.BORDER_DELAY));
    }

    @Test
    void effectSettingsAreSetAndSaved() {
        run("게임 설정 파티클 0.5");
        assertEquals(0.5, read().particleScale());
        assertEquals(0.5, saved().getDouble(SuperpowerSettings.PARTICLE_SCALE));
        run("mcpl settings particles 9");
        assertEquals(SuperpowerSettings.MAX_PARTICLE_SCALE, read().particleScale());
        run("게임 설정 파티클 0");
        assertEquals(SuperpowerSettings.MIN_PARTICLE_SCALE, saved().getDouble(SuperpowerSettings.PARTICLE_SCALE));

        run("게임 설정 팀원능력 끄기");
        assertFalse(read().showTeamAbilities());
        assertFalse(saved().getBoolean(SuperpowerSettings.TEAM_ABILITIES));
        run("mcpl settings team-abilities on");
        assertTrue(read().showTeamAbilities());
        run("게임 설정 궁극기알림 끄기");
        assertFalse(read().announceUltimates());
        run("mcpl settings ultimate-announce on");
        assertTrue(saved().getBoolean(SuperpowerSettings.ANNOUNCE_ULTIMATES));
        run("게임 설정 킬로그능력 끄기");
        assertFalse(read().showAbilityInKillFeed());
        run("mcpl settings kill-feed on");
        assertTrue(read().showAbilityInKillFeed());
    }

    @Test
    void lootSettingsAreSetSavedAndClamped() {
        run("게임 설정 상자 켜기");
        assertTrue(loot().enabled());
        run("mcpl settings loot off");
        assertFalse(loot().enabled());
        assertFalse(saved().getBoolean(LootSettings.ENABLED));
        run("게임 설정 연습상자 켜기");
        assertTrue(loot().inPractice());
        run("mcpl settings practice-loot off");
        assertFalse(saved().getBoolean(LootSettings.PRACTICE));

        run("게임 설정 상자수 2.5");
        assertEquals(2.5, loot().chestsPerPlayer());
        run("mcpl settings chests 50");
        assertEquals(LootSettings.MAX_CHESTS_PER_PLAYER, saved().getDouble(LootSettings.CHESTS_PER_PLAYER));

        run("게임 설정 상자최대 2");
        assertEquals(2, loot().maxChests());
        assertEquals(2, loot().minChests(), "the minimum (3) comes down with the maximum");
        assertEquals(2, saved().getInt(LootSettings.CHESTS_MIN));
        run("mcpl settings max-chests 500");
        assertEquals(200, loot().maxChests());

        run("게임 설정 보급 0");
        assertEquals(0, loot().supplyCount(8), "0 = no supply drops at all");
        assertEquals(0, saved().getInt(LootSettings.SUPPLY_MIN));
        run("mcpl settings supply 20");
        assertEquals(10, saved().getInt(LootSettings.SUPPLY_MAX));
        run("게임 설정 보급인원 0");
        assertEquals(1, loot().playersPerSupply());
        run("mcpl settings supply-players 3");
        assertEquals(3, saved().getInt(LootSettings.PLAYERS_PER_SUPPLY));

        run("게임 설정 상자표시 끄기");
        assertFalse(loot().nearbyHint());
        run("mcpl settings chest-hint on");
        assertTrue(saved().getBoolean(LootSettings.NEARBY_HINT));
        run("게임 설정 상자표시거리 100");
        assertEquals(LootSettings.MAX_HINT_RADIUS, loot().nearbyHintRadius());
        run("mcpl settings chest-hint-radius 1");
        assertEquals(LootSettings.MIN_HINT_RADIUS, saved().getInt(LootSettings.NEARBY_HINT_RADIUS));
    }

    @Test
    void theNearbyHintIsDeclaredWithItsDefaults() {
        assertTrue(live().getBoolean(LootSettings.NEARBY_HINT), "added to config.yml");
        assertEquals(12, live().getInt(LootSettings.NEARBY_HINT_RADIUS));
        assertFalse(saved().getComments(LootSettings.NEARBY_HINT).isEmpty(), "with a Korean comment");
        live().set(LootSettings.NEARBY_HINT_RADIUS, 2);
        assertEquals(LootSettings.MIN_HINT_RADIUS, loot().nearbyHintRadius(), "the reader clamps too");
    }

    @Test
    void aRunningMatchKeepsItsSettingsAndTheAdminIsTold() {
        h.join(2);
        SuperpowerMatch match = h.startFight();
        int rerolls = match.settings().rerolls();
        chat();
        run("게임 설정 리롤 7");
        List<String> lines = chat();
        assertTrue(lines.stream().anyMatch(line -> line.contains("리롤 횟수") && line.contains("7회")), "" + lines);
        assertTrue(lines.stream().anyMatch(line -> line.contains("다음 경기부터")), "" + lines);
        assertEquals(rerolls, match.settings().rerolls(), "the running match is not changed");
        assertEquals(7, read().rerolls(), "the next match reads it");
    }

    @Test
    void theOldModeCommandStillWorks() {
        run("게임 능력 선택방식 CHOICE");
        assertEquals(SelectionMode.CHOICE, read().selectionMode());
        assertEquals("CHOICE", saved().getString(SuperpowerSettings.MODE));
    }

    // =================================================================== menu

    private SettingsMenu openMenu() {
        run("게임 설정");
        SettingsMenu menu = SettingsEditor.openMenu(admin);
        assertNotNull(menu);
        return menu;
    }

    private void click(SettingsMenu menu, String id, ClickType type) {
        int slot = menu.slotOf(id);
        assertTrue(slot >= 0, id);
        InventoryClickEvent event = new InventoryClickEvent(admin.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, slot, type, InventoryAction.PICKUP_ALL);
        h.server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void menuClicksChangeTheGameSettings() {
        SettingsMenu menu = openMenu();
        click(menu, "superpower.loot", ClickType.LEFT);
        assertTrue(loot().enabled(), "the harness turns loot off; a click turns it on");
        assertTrue(saved().getBoolean(LootSettings.ENABLED), "saved at once");
        click(menu, "superpower.loot", ClickType.RIGHT);
        assertFalse(loot().enabled(), "any click flips a switch");

        click(menu, "superpower.prep", ClickType.LEFT); // the harness uses 1 second
        assertEquals(6, saved().getInt(SuperpowerSettings.PREP_SECONDS));
        click(menu, "superpower.prep", ClickType.SHIFT_LEFT);
        assertEquals(36, saved().getInt(SuperpowerSettings.PREP_SECONDS));
        click(menu, "superpower.prep", ClickType.RIGHT);
        assertEquals(31 * 20, read().prepTicks());
        click(menu, "superpower.prep", ClickType.DROP);
        assertEquals(15, saved().getInt(SuperpowerSettings.PREP_SECONDS), "the declared default");

        click(menu, "superpower.particles", ClickType.RIGHT);
        assertEquals(0.75, read().particleScale());

        click(menu, "superpower.mode", ClickType.LEFT);
        assertEquals(SelectionMode.CHOICE, h.game.selectionMode());
        click(menu, "superpower.mode", ClickType.LEFT);
        assertEquals(SelectionMode.RANDOM, h.game.selectionMode(), "wraps around");

        for (int i = 0; i < 5; i++) {
            click(menu, "superpower.max-chests", ClickType.SHIFT_RIGHT);
        }
        assertEquals(0, loot().maxChests());
        assertEquals(0, loot().chestCount(8, 60), "0 = no chests");
    }
}
