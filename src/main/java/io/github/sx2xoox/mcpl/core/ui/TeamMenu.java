package io.github.sx2xoox.mcpl.core.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.game.TeamAssigner;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The team menu ({@code /게임 팀 선택} without a team): one wool per team that can be picked now (its pickers in the
 * lore), clicking one picks it, the last slot goes back to automatic placement. Clicks and drags are cancelled by
 * {@link TeamMenuListener}; every open team menu is redrawn after a pick ({@link #refreshAll}).
 */
public final class TeamMenu implements InventoryHolder {

    private static final int ROW = 9;
    private static final int MAX_NAMES = 8;

    private final Hub hub;
    private final UUID viewer;
    private final Inventory inventory;
    private final Map<Integer, TeamAssigner.Entry> slots = new HashMap<>();
    private final int autoSlot;

    private TeamMenu(Hub hub, UUID viewer, int teams) {
        this.hub = hub;
        this.viewer = viewer;
        int rows = Math.max(1, (teams + 1 + ROW - 1) / ROW);
        this.inventory = Bukkit.createInventory(this, rows * ROW, hub.messages().get("team.menu.title"));
        this.autoSlot = rows * ROW - 1;
    }

    /** Opens the menu for {@code player}; false if no team can be picked now. */
    public static boolean open(Hub hub, Player player) {
        List<TeamAssigner.Entry> teams = hub.teamPresets().available();
        if (teams.isEmpty()) {
            return false;
        }
        TeamMenu menu = new TeamMenu(hub, player.getUniqueId(), teams.size());
        menu.render();
        player.openInventory(menu.inventory);
        return true;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** The team in this slot, or null (tests). */
    public TeamAssigner.@Nullable Entry teamAt(int slot) {
        return slots.get(slot);
    }

    /** The slot that removes the pick (자동 배정). */
    public int autoSlot() {
        return autoSlot;
    }

    /** Redraws the menu from the current picks and teams. */
    public void render() {
        inventory.clear();
        slots.clear();
        List<TeamAssigner.Entry> teams = hub.teamPresets().available();
        String mine = hub.teamPresets().get(viewer);
        for (int i = 0; i < teams.size() && i < autoSlot; i++) {
            TeamAssigner.Entry team = teams.get(i);
            slots.put(i, team);
            inventory.setItem(i, teamItem(team, team.id().equals(mine)));
        }
        ItemStack auto = ItemStack.of(Material.LIGHT_GRAY_STAINED_GLASS);
        auto.setData(DataComponentTypes.ITEM_NAME, hub.messages().get("team.menu.auto-name"));
        auto.setData(DataComponentTypes.LORE, ItemLore.lore(plain(List.of(hub.messages().get(
                mine == null ? "team.menu.current" : "team.menu.auto-lore")))));
        inventory.setItem(autoSlot, auto);
    }

    private ItemStack teamItem(TeamAssigner.Entry team, boolean mine) {
        ItemStack item = ItemStack.of(wool(team));
        List<UUID> members = hub.teamPresets().members(team.id());
        item.setData(DataComponentTypes.ITEM_NAME, hub.messages().get("team.menu.team-name",
                Messages.tag("team", team.displayName()), Messages.tag("count", members.size())));
        List<Component> lore = new ArrayList<>();
        for (int i = 0; i < members.size() && i < MAX_NAMES; i++) {
            lore.add(hub.messages().get("team.menu.member", Messages.tag("player", nameOf(members.get(i)))));
        }
        if (members.size() > MAX_NAMES) {
            lore.add(hub.messages().get("team.menu.more", Messages.tag("count", members.size() - MAX_NAMES)));
        }
        lore.add(hub.messages().get(mine ? "team.menu.current" : "team.menu.join"));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(plain(lore)));
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, mine);
        return item;
    }

    /** Handles a click on the menu's own slot. */
    void click(Player player, int slot) {
        TeamAssigner.Entry team = slots.get(slot);
        if (slot == autoSlot) {
            hub.teamPresets().remove(player.getUniqueId());
        } else if (team != null && hub.teamPresets().available().contains(team)) {
            hub.teamPresets().set(player.getUniqueId(), team.id());
        } else {
            return;
        }
        Feedback.play(player, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        refreshAll();
    }

    /** Redraws every open team menu (after a pick, from the menu or a command). */
    public static void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            InventoryView view = player.getOpenInventory();
            Inventory top = view == null ? null : view.getTopInventory();
            if (top != null && top.getHolder(false) instanceof TeamMenu menu) {
                menu.render();
            }
        }
    }

    static String nameOf(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
        String name = offline.getName();
        return name == null ? id.toString().substring(0, 8) : name;
    }

    static Material wool(TeamAssigner.Entry team) {
        Material wool = Material.matchMaterial(team.dye().name() + "_WOOL");
        return wool == null ? Material.WHITE_WOOL : wool;
    }

    private static List<Component> plain(List<Component> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (Component line : lines) {
            out.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return out;
    }
}
