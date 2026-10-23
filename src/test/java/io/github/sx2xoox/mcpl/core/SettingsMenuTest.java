package io.github.sx2xoox.mcpl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.config.BlockDamageScope;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.core.ui.SettingsEditor;
import io.github.sx2xoox.mcpl.core.ui.SettingsMenu;
import io.github.sx2xoox.mcpl.game.superpower.support.TestServer;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The settings menu ({@code /게임 설정}): clicks change values, nothing can be taken, only admins can use it. */
class SettingsMenuTest {

    private ServerMock server;
    private MCPLPlugin plugin;
    private Hub hub;
    private PlayerMock admin;

    @BeforeEach
    void setUp() {
        // Its custom inventories answer getHolder(false) like Paper's (MockBukkit's throw).
        server = MockBukkit.mock(new TestServer());
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(MCPLPlugin.class);
        hub = plugin.hub();
        hub.games().register(new FakeGame("fake"));
        admin = server.addPlayer("Admin");
        admin.setOp(true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private SettingsMenu open() {
        assertTrue(server.dispatchCommand(admin, "게임 설정"));
        SettingsMenu menu = SettingsEditor.openMenu(admin);
        assertNotNull(menu, "players get the menu");
        return menu;
    }

    private InventoryClickEvent click(int rawSlot, ClickType type, InventoryAction action) {
        InventoryView view = admin.getOpenInventory();
        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, type,
                action);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private InventoryClickEvent click(SettingsMenu menu, String settingId, ClickType type) {
        int slot = menu.slotOf(settingId);
        assertTrue(slot >= 0, settingId + " is on the first page");
        InventoryClickEvent event = click(slot, type, InventoryAction.PICKUP_ALL);
        assertTrue(event.isCancelled(), "a menu click never moves the item");
        return event;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** The newest action bar text (older ones are dropped). */
    private String lastActionBar() {
        String last = "";
        Component next;
        while ((next = admin.nextActionBar()) != null) {
            last = plain(next);
        }
        return last;
    }

    /** The item the menu shows in {@code slot} (with its data components, see {@link TestServer.HolderInventory}). */
    private static ItemStack item(SettingsMenu menu, int slot) {
        ItemStack item = ((TestServer.HolderInventory) menu.getInventory()).placed(slot);
        assertNotNull(item);
        return item;
    }

    private static String name(SettingsMenu menu, String settingId) {
        return name(item(menu, menu.slotOf(settingId)));
    }

    private static String name(ItemStack item) {
        Component name = item.getData(DataComponentTypes.ITEM_NAME);
        assertNotNull(name);
        return plain(name);
    }

    private static List<Component> loreLines(ItemStack item) {
        ItemLore lore = item.getData(DataComponentTypes.LORE);
        assertNotNull(lore);
        return lore.lines();
    }

    private static String lore(SettingsMenu menu, String settingId) {
        return String.join("\n", loreLines(item(menu, menu.slotOf(settingId))).stream()
                .map(SettingsMenuTest::plain).toList());
    }

    /** The match status item in the bottom right corner. */
    private static ItemStack status(SettingsMenu menu) {
        return item(menu, menu.getInventory().getSize() - 1);
    }

    @Test
    void theMenuShowsEverySectionOnOnePage() {
        SettingsMenu menu = open();
        assertEquals(1, menu.pages());
        for (String id : new String[] {"hub.team-size", "hub.countdown", "hub.terrain", "superpower.mode",
                "superpower.prep", "superpower.border-final", "superpower.particles", "superpower.loot",
                "superpower.supply", "superpower.chest-hint", "superpower.chest-hint-radius"}) {
            assertTrue(menu.slotOf(id) >= 0, id + " is in the menu");
        }
        assertEquals("팀 인원 · 개인전", name(menu, "hub.team-size"));
        String lore = lore(menu, "hub.team-size");
        assertTrue(lore.contains("팀당 인원"), "the explanation: " + lore);
        assertTrue(lore.contains("범위 0명 ~ 32명 (0 = 개인전)"), "the range in plain numbers: " + lore);
        assertTrue(lore.contains("현재 값: 개인전") && !lore.contains("최솟값"), "0 has its own meaning: " + lore);
        assertTrue(lore.contains("/게임 설정 팀 <인원>"), "the matching command: " + lore);
        assertTrue(lore.contains("config.yml → match.team-size"), "where it is saved: " + lore);
        assertFalse(lore.contains("다음 경기부터"), "no match is running: " + lore);
        assertEquals("대기 중", name(status(menu)));
    }

    @Test
    void choicesDimTheOptionsThatAreNotSelected() {
        SettingsMenu menu = open();
        Component options = loreLines(item(menu, menu.slotOf("hub.terrain"))).stream()
                .filter(line -> plain(line).startsWith("선택지")).findFirst().orElseThrow();
        assertTrue(plain(options).contains("▶ 전투 맵에서만"), plain(options));
        Map<String, @Nullable TextColor> colours = new HashMap<>();
        colours(options, null, colours);
        assertEquals(NamedTextColor.DARK_GRAY, colourOf(colours, "끄기"), "dimmed: " + colours);
        assertEquals(NamedTextColor.DARK_GRAY, colourOf(colours, "모든 월드"), "dimmed: " + colours);
        assertEquals(NamedTextColor.GREEN, colourOf(colours, "전투 맵에서만"), "the current option keeps its colour");
    }

    private static @Nullable TextColor colourOf(Map<String, @Nullable TextColor> colours, String text) {
        return colours.entrySet().stream().filter(entry -> entry.getKey().contains(text))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
    }

    /** The colour every piece of text is drawn in (its own or the nearest parent's), by text. */
    private static void colours(Component node, @Nullable TextColor inherited, Map<String, @Nullable TextColor> into) {
        TextColor colour = node.color() != null ? node.color() : inherited;
        if (node instanceof TextComponent text && !text.content().isBlank()) {
            into.put(text.content().trim(), colour);
        }
        for (Component child : node.children()) {
            colours(child, colour, into);
        }
    }

    @Test
    void iconsShowOnlyTheirNameAndLore() {
        SettingsMenu menu = open();
        int size = menu.getInventory().getSize();
        int checked = 0;
        for (int slot = 0; slot < size; slot++) {
            ItemStack item = ((TestServer.HolderInventory) menu.getInventory()).placed(slot);
            if (item == null || item.getType() == Material.GRAY_STAINED_GLASS_PANE) {
                continue;
            }
            TooltipDisplay display = item.getData(DataComponentTypes.TOOLTIP_DISPLAY);
            assertNotNull(display, item.getType() + " hides its extra tooltip lines");
            assertTrue(display.hiddenComponents().containsAll(List.of(DataComponentTypes.FIREWORKS,
                    DataComponentTypes.INSTRUMENT, DataComponentTypes.BUNDLE_CONTENTS,
                    DataComponentTypes.ATTRIBUTE_MODIFIERS)), item.getType().toString());
            assertFalse(display.hiddenComponents().contains(DataComponentTypes.LORE), "the lore stays");
            checked++;
        }
        assertTrue(checked > 20, "headers, entries and controls: " + checked);
    }

    @Test
    void clicksStepNumbersAndSaveThem() {
        SettingsMenu menu = open();
        click(menu, "hub.team-size", ClickType.LEFT);
        assertEquals(1, hub.config().teamSize());
        click(menu, "hub.team-size", ClickType.SHIFT_LEFT);
        assertEquals(5, hub.config().teamSize(), "shift = the bigger step");
        click(menu, "hub.team-size", ClickType.RIGHT);
        assertEquals(4, hub.config().teamSize());
        assertEquals("팀 인원 · 4명", name(menu, "hub.team-size"), "the item shows the new value at once");
        assertEquals(4, YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"))
                .getInt("match.team-size"), "saved to config.yml");

        for (int i = 0; i < 20; i++) {
            click(menu, "hub.team-size", ClickType.SHIFT_LEFT);
        }
        assertEquals(32, hub.config().teamSize(), "clamped at the maximum");
        assertTrue(lastActionBar().contains("최댓값"), "the admin is told why nothing changes");
        assertTrue(lore(menu, "hub.team-size").contains("현재 값: 32명 (최댓값)"),
                "also in the tooltip, which the menu never covers");
        click(menu, "hub.team-size", ClickType.DROP);
        assertEquals(0, hub.config().teamSize(), "Q puts the default back");
        click(menu, "hub.team-size", ClickType.RIGHT);
        assertEquals(0, hub.config().teamSize(), "clamped at the minimum");
    }

    @Test
    void clicksCycleChoices() {
        SettingsMenu menu = open();
        click(menu, "hub.terrain", ClickType.LEFT);
        assertEquals(BlockDamageScope.ALL_WORLDS, hub.config().abilityBlockDamage());
        click(menu, "hub.terrain", ClickType.LEFT);
        assertEquals(BlockDamageScope.OFF, hub.config().abilityBlockDamage(), "wraps around");
        click(menu, "hub.terrain", ClickType.RIGHT);
        assertEquals(BlockDamageScope.ALL_WORLDS, hub.config().abilityBlockDamage());
        click(menu, "hub.terrain", ClickType.DOUBLE_CLICK);
        assertEquals(BlockDamageScope.ALL_WORLDS, hub.config().abilityBlockDamage(),
                "a double click is already two clicks");
    }

    @Test
    void nothingCanBeTakenOrPutIn() {
        SettingsMenu menu = open();
        int size = menu.getInventory().getSize();
        int slot = menu.slotOf("hub.countdown");
        int header = menu.slotOf("hub.team-size") - 1;

        assertTrue(click(slot, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP).isCancelled(), "number keys");
        assertTrue(click(slot, ClickType.MIDDLE, InventoryAction.CLONE_STACK).isCancelled());
        assertTrue(click(slot, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR).isCancelled());
        assertTrue(click(header, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled(), "the row header");
        assertTrue(click(size + 3, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled(),
                "shift-clicking an own item into the menu");
        assertTrue(click(size + 3, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled(),
                "own inventory below the menu");
        InventoryDragEvent drag = new InventoryDragEvent(admin.getOpenInventory(), ItemStack.of(Material.STONE),
                ItemStack.of(Material.STONE, 2), false, Map.of(slot, ItemStack.of(Material.STONE)));
        server.getPluginManager().callEvent(drag);
        assertTrue(drag.isCancelled(), "dragging into the menu");
        assertEquals(5, hub.config().countdownSeconds(), "none of these is a setting click");

        assertTrue(click(slot, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled(),
                "shift-clicking an entry never moves it");
        assertEquals(10, hub.config().countdownSeconds(), "it is the bigger step instead");
        assertNotNull(menu.getInventory().getItem(slot), "the entry stays in the menu");
        assertTrue(admin.getItemOnCursor().isEmpty(), "nothing on the cursor");
        assertSame(menu, SettingsEditor.openMenu(admin));
    }

    @Test
    void anAdminWhoLostThePermissionIsRefusedAndTheMenuCloses() {
        SettingsMenu menu = open();
        admin.setOp(false);
        click(menu, "hub.team-size", ClickType.LEFT);
        assertEquals(0, hub.config().teamSize());
        server.getScheduler().performOneTick();
        assertNull(SettingsEditor.openMenu(admin), "closed on the next tick");
    }

    @Test
    void theCloseButtonClosesTheMenu() {
        SettingsMenu menu = open();
        assertTrue(click(menu.closeSlot(), ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled());
        assertSame(menu, SettingsEditor.openMenu(admin), "never closed inside the click event");
        server.getScheduler().performOneTick();
        assertNull(SettingsEditor.openMenu(admin));
    }

    @Test
    void openMenusFollowChangesMadeElsewhere() {
        SettingsMenu menu = open();
        assertTrue(server.dispatchCommand(admin, "게임 설정 팀 3"));
        assertEquals("팀 인원 · 3명", name(menu, "hub.team-size"));
        hub.config().setCountdownSeconds(12);
        hub.settings().refreshMenus();
        assertEquals("카운트다운 · 12초", name(menu, "hub.countdown"));
    }

    @Test
    void duringTheCountdownMostChangesApplyToTheComingMatch() {
        server.addPlayer("User");
        SettingsMenu menu = open();
        assertTrue(server.dispatchCommand(admin, "게임 시작 fake"));
        assertEquals(GameState.COUNTDOWN, hub.games().session().state());
        assertEquals("경기 시작 전 (카운트다운)", name(status(menu)), "open menus follow the match");
        assertFalse(lore(menu, "hub.team-size").contains("다음 경기부터"), "read when the countdown ends");
        assertTrue(lore(menu, "hub.countdown").contains("다음 경기부터"), "the countdown already runs");
        assertTrue(lore(menu, "hub.terrain").contains("다음 경기부터"), "read when the match was started");

        lastActionBar();
        click(menu, "hub.team-size", ClickType.LEFT);
        assertEquals(1, hub.config().teamSize());
        String bar = lastActionBar();
        assertTrue(bar.contains("팀 인원") && bar.contains("1명") && !bar.contains("다음 경기부터"), bar);
        click(menu, "hub.countdown", ClickType.LEFT);
        assertTrue(lastActionBar().contains("다음 경기부터"), "the countdown of this match is not changed");
    }

    @Test
    void whileAMatchRunsChangesWaitForTheNextMatch() {
        server.addPlayer("User");
        assertTrue(server.dispatchCommand(admin, "게임 시작 fake"));
        server.getScheduler().performTicks(hub.config().countdownSeconds() * 20L + 5);
        assertEquals(GameState.RUNNING, hub.games().session().state());
        SettingsMenu menu = open();
        assertEquals("경기 진행 중", name(status(menu)));
        assertTrue(lore(menu, "hub.team-size").contains("다음 경기부터"), "every entry says so in its tooltip");

        lastActionBar();
        click(menu, "hub.team-size", ClickType.LEFT);
        assertEquals(1, hub.config().teamSize(), "saved for the next match");
        String bar = lastActionBar();
        assertTrue(bar.contains("팀 인원") && bar.contains("1명") && bar.contains("다음 경기부터"), bar);
    }

    @Test
    void aClickNeverOverwritesEditsMadeToTheFile() throws IOException {
        SettingsMenu menu = open();
        File file = new File(plugin.getDataFolder(), "config.yml");
        String edited = Files.readString(file.toPath()).replace("countdown-seconds: 5", "countdown-seconds: 9");
        Files.writeString(file.toPath(), edited);
        click(menu, "hub.team-size", ClickType.LEFT);
        assertEquals(0, hub.config().teamSize(), "refused until the edit is loaded");
        assertEquals(edited, Files.readString(file.toPath()), "the owner's edit is kept");
        assertTrue(hub.config().isWritable(), "the edited file is fine, it only needs /게임 리로드");

        assertTrue(server.dispatchCommand(admin, "게임 리로드"));
        click(menu, "hub.team-size", ClickType.LEFT);
        assertEquals(1, hub.config().teamSize());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file);
        assertEquals(1, saved.getInt("match.team-size"));
        assertEquals(9, saved.getInt("match.countdown-seconds"), "the owner's edit survives the save");
    }

    @Test
    void aBrokenConfigRefusesMenuChanges() throws IOException {
        SettingsMenu menu = open();
        File file = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(file.toPath(), "match:\n\tteam-size: 2\n");
        assertThrows(IllegalStateException.class, hub::reload);
        click(menu, "hub.team-size", ClickType.LEFT);
        click(menu, "superpower.loot", ClickType.LEFT);
        assertEquals(0, hub.config().teamSize());
        assertTrue(hub.config().gameSection("superpower").getBoolean("loot.enabled"));
        assertEquals("match:\n\tteam-size: 2\n", Files.readString(file.toPath()));
    }

    @Test
    void theMenuClosesWhenThePluginDisables() {
        open();
        hub.disable();
        assertFalse(SettingsEditor.openMenu(admin) != null, "no menu outlives the plugin");
    }
}
