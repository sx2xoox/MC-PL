package io.github.sx2xoox.mcpl.core.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.command.SettingsCommand;
import io.github.sx2xoox.mcpl.core.config.Setting;
import io.github.sx2xoox.mcpl.core.config.SettingsSection;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The settings menu of one admin ({@code /게임 설정}): a chest with one row per {@link SettingsSection} (a header item
 * on the left, up to eight entries next to it; longer sections continue on the next row) and a control row at the
 * bottom (help, close, pages, match status). Every entry shows its value, range and default in the lore, and whether a
 * change would wait for the next match (the hovered item's tooltip is the feedback the admin always sees; the action
 * bar can be hidden behind the menu).
 *
 * <ul>
 *     <li>on/off: any click flips it;</li>
 *     <li>numbers: left click +step, right click -step, with shift the bigger step;</li>
 *     <li>choices: left click the next value, right click the previous one;</li>
 *     <li>the drop key (Q) puts the default back.</li>
 * </ul>
 * Clicks are handled by {@link SettingsMenuListener}, which cancels every click and drag while the menu is open, so
 * nothing can be taken out or put in. Values are read again on every {@link #render()}.
 */
public final class SettingsMenu implements InventoryHolder {

    /** Section rows per page; the row below them holds the controls. */
    static final int CONTENT_ROWS = 5;
    private static final int ROW = 9;
    private static final int ENTRIES_PER_ROW = ROW - 1;
    private static final int HELP = 0;
    private static final int PREVIOUS = 3;
    private static final int CLOSE = 4;
    private static final int NEXT = 5;
    private static final int STATUS = 8;

    /** One row of the menu: a section header and up to eight of its entries. */
    private record Row(SettingsSection section, List<Setting> settings) {
    }

    private final SettingsEditor editor;
    private final UUID viewer;
    private final List<Row> rows;
    private final int contentRows;
    private final Inventory inventory;
    private final Map<Integer, Setting> slots = new HashMap<>();
    private int page;

    SettingsMenu(SettingsEditor editor, UUID viewer) {
        this.editor = editor;
        this.viewer = viewer;
        this.rows = layout(editor.sections());
        this.contentRows = Math.max(1, Math.min(CONTENT_ROWS, rows.size()));
        this.inventory = Bukkit.createInventory(this, (contentRows + 1) * ROW,
                editor.hub().messages().get("settings.menu.title"));
        render();
    }

    private static List<Row> layout(List<SettingsSection> sections) {
        List<Row> rows = new ArrayList<>();
        for (SettingsSection section : sections) {
            List<Setting> settings = section.settings();
            for (int from = 0; from < settings.size(); from += ENTRIES_PER_ROW) {
                rows.add(new Row(section, settings.subList(from, Math.min(settings.size(), from + ENTRIES_PER_ROW))));
            }
        }
        return rows;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** The admin this menu was opened for. */
    public UUID viewer() {
        return viewer;
    }

    /** The current page (0-based). */
    public int page() {
        return page;
    }

    public int pages() {
        return Math.max(1, (rows.size() + contentRows - 1) / contentRows);
    }

    /** The menu slot of a setting on the current page, or -1 (tests and tooling). */
    public int slotOf(String settingId) {
        for (Map.Entry<Integer, Setting> entry : slots.entrySet()) {
            if (entry.getValue().id().equals(settingId)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /** The setting shown in {@code rawSlot}, or null. */
    public @Nullable Setting settingAt(int rawSlot) {
        return slots.get(rawSlot);
    }

    /** The slot of the close button. */
    public int closeSlot() {
        return contentRows * ROW + CLOSE;
    }

    private Messages messages() {
        return editor.hub().messages();
    }

    // =================================================================== drawing

    /** Draws the current page with the current values. */
    public void render() {
        slots.clear();
        ItemStack filler = filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        int first = page * contentRows;
        for (int r = 0; r < contentRows && first + r < rows.size(); r++) {
            Row row = rows.get(first + r);
            int base = r * ROW;
            inventory.setItem(base, header(row.section()));
            for (int i = 0; i < row.settings().size(); i++) {
                Setting setting = row.settings().get(i);
                inventory.setItem(base + 1 + i, entry(setting));
                slots.put(base + 1 + i, setting);
            }
        }
        int controls = contentRows * ROW;
        inventory.setItem(controls + HELP, simple(Material.BOOK, "settings.menu.help"));
        inventory.setItem(controls + CLOSE, simple(Material.BARRIER, "settings.menu.close"));
        if (page > 0) {
            inventory.setItem(controls + PREVIOUS, simple(Material.ARROW, "settings.menu.previous"));
        }
        if (page < pages() - 1) {
            inventory.setItem(controls + NEXT, simple(Material.ARROW, "settings.menu.next"));
        }
        boolean session = editor.hub().games().session() != null;
        boolean countdown = editor.countingDown();
        inventory.setItem(controls + STATUS, simple(
                !session ? Material.LIME_DYE : countdown ? Material.YELLOW_DYE : Material.CLOCK,
                !session ? "settings.menu.status-idle"
                        : countdown ? "settings.menu.status-countdown" : "settings.menu.status-running",
                Messages.tag("page", page + 1), Messages.tag("pages", pages())));
    }

    private static ItemStack filler() {
        ItemStack filler = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
        filler.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return filler;
    }

    private ItemStack header(SettingsSection section) {
        ItemStack item = ItemStack.of(section.icon());
        item.setData(DataComponentTypes.ITEM_NAME, section.title());
        item.setData(DataComponentTypes.LORE, ItemLore.lore(plain(section.description())));
        hideExtras(item);
        return item;
    }

    private ItemStack simple(Material material, String key, TagResolver... tags) {
        ItemStack item = ItemStack.of(material);
        item.setData(DataComponentTypes.ITEM_NAME, messages().get(key + ".name", tags));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(plain(messages().lines(key + ".lore", tags))));
        hideExtras(item);
        return item;
    }

    private ItemStack entry(Setting setting) {
        Messages hub = messages();
        ItemStack item = ItemStack.of(setting.icon());
        item.setData(DataComponentTypes.ITEM_NAME, hub.get("settings.menu.entry-name",
                Messages.tag("name", setting.name()), Messages.tag("value", setting.valueText(hub))));
        List<Component> lore = new ArrayList<>(setting.description());
        lore.add(Component.empty());
        String valueKey = "settings.menu.value";
        // At an end of the range the tooltip says so (a click there changes nothing); a 0 with a meaning of its own
        // (개인전, 무제한) is not called the minimum.
        if (setting instanceof Setting.Number number && number.min() < number.max()
                && !(number.value() == 0 && number.zeroLabel() != null)) {
            valueKey = number.value() >= number.max() ? "settings.menu.value-max"
                    : number.value() <= number.min() ? "settings.menu.value-min" : valueKey;
        }
        lore.add(hub.get(valueKey, Messages.tag("value", setting.valueText(hub))));
        switch (setting) {
            case Setting.Toggle toggle -> {
                lore.add(hub.get("settings.menu.default", Messages.tag("default", toggle.defaultText(hub))));
                lore.add(Component.empty());
                lore.add(hub.get("settings.menu.hint-toggle"));
                item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, toggle.value());
            }
            case Setting.Number number -> {
                Component zero = number.zeroLabel();
                lore.add(hub.get("settings.menu.range",
                        Messages.tag("min", number.formatPlain(hub, number.min())),
                        Messages.tag("max", number.formatPlain(hub, number.max())),
                        Messages.tag("zero", zero == null || number.min() > 0 ? Component.empty()
                                : hub.get("settings.menu.zero-means", Messages.tag("zero", zero))),
                        Messages.tag("default", number.defaultText(hub))));
                lore.add(Component.empty());
                lore.add(hub.get("settings.menu.hint-number", Messages.tag("step", Format.decimal(number.step())),
                        Messages.tag("big", Format.decimal(number.bigStep()))));
                item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);
            }
            case Setting.Choice choice -> {
                List<Component> labels = new ArrayList<>();
                for (Setting.Choice.Option option : choice.options()) {
                    boolean current = option.equals(choice.value());
                    // The other options are dimmed: their own colours would win over the line's dark gray.
                    Component label = current ? choice.label(option)
                            : Component.text(PlainTextComponentSerializer.plainText().serialize(choice.label(option)));
                    labels.add(hub.get(current ? "settings.menu.option-current" : "settings.menu.option",
                            Messages.tag("option", label)));
                }
                lore.add(hub.get("settings.menu.options", Messages.tag("options",
                        Component.join(JoinConfiguration.separator(hub.get("settings.menu.option-separator")),
                                labels))));
                lore.add(hub.get("settings.menu.default", Messages.tag("default", choice.defaultText(hub))));
                lore.add(Component.empty());
                lore.add(hub.get("settings.menu.hint-choice"));
                item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);
            }
        }
        lore.add(hub.get("settings.menu.hint-reset"));
        if (editor.waitsForNextMatch(setting)) {
            lore.add(hub.get("settings.menu.waits"));
        }
        lore.add(hub.get("settings.menu.command", Messages.tag("command", SettingsCommand.usage(setting, false))));
        lore.add(hub.get("settings.menu.path", Messages.tag("path", setting.path())));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(plain(lore)));
        hideExtras(item);
        return item;
    }

    /** Tooltip lines an icon may bring along (rocket flight time, horn sound, bundle contents, ...). */
    private static final List<DataComponentType> TOOLTIP_EXTRAS = List.of(DataComponentTypes.ATTRIBUTE_MODIFIERS,
            DataComponentTypes.ENCHANTMENTS, DataComponentTypes.STORED_ENCHANTMENTS, DataComponentTypes.FIREWORKS,
            DataComponentTypes.FIREWORK_EXPLOSION, DataComponentTypes.INSTRUMENT, DataComponentTypes.BUNDLE_CONTENTS,
            DataComponentTypes.CONTAINER, DataComponentTypes.POTION_CONTENTS, DataComponentTypes.JUKEBOX_PLAYABLE,
            DataComponentTypes.BANNER_PATTERNS, DataComponentTypes.DYED_COLOR, DataComponentTypes.TRIM);

    /** Only the name and the lore are shown: every other component of the icon is hidden from the tooltip. */
    private static void hideExtras(ItemStack item) {
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        Set<DataComponentType> hidden = new LinkedHashSet<>(TOOLTIP_EXTRAS);
        hidden.addAll(item.getDataTypes());
        hidden.remove(DataComponentTypes.ITEM_NAME);
        hidden.remove(DataComponentTypes.LORE);
        hidden.remove(DataComponentTypes.TOOLTIP_DISPLAY);
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .hiddenComponents(hidden)
                .build());
    }

    /** Lore lines are italic unless said otherwise. */
    private static List<Component> plain(List<Component> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (Component line : lines) {
            out.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return out;
    }

    // =================================================================== clicks

    /** {@code player} clicked {@code rawSlot} of this menu (the click itself is already cancelled). */
    void click(Player player, int rawSlot, ClickType click) {
        Setting setting = slots.get(rawSlot);
        if (setting != null) {
            boolean big = click.isShiftClick();
            boolean reset = click == ClickType.DROP || click == ClickType.CONTROL_DROP;
            int direction = switch (click) {
                case LEFT, SHIFT_LEFT -> 1;
                case RIGHT, SHIFT_RIGHT -> -1;
                // A double click arrives as two left clicks plus this one: it must not count a third time.
                default -> 0;
            };
            if (!reset && direction == 0) {
                return; // number keys, middle click, double click, ...
            }
            editor.change(player, setting, () -> apply(setting, direction, big, reset), SettingsEditor.Origin.MENU);
            render();
            return;
        }
        int controls = contentRows * ROW;
        if (rawSlot == controls + CLOSE) {
            Feedback.play(player, Sound.UI_BUTTON_CLICK, 0.5f, 0.8f);
            editor.hub().plugin().getServer().getScheduler().runTask(editor.hub().plugin(), () -> {
                if (SettingsEditor.openMenu(player) == this) {
                    player.closeInventory();
                }
            });
        } else if (rawSlot == controls + PREVIOUS && page > 0) {
            page--;
            Feedback.play(player, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.0f);
            render();
        } else if (rawSlot == controls + NEXT && page < pages() - 1) {
            page++;
            Feedback.play(player, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.0f);
            render();
        }
    }

    private static Setting.Change apply(Setting setting, int direction, boolean big, boolean reset) {
        if (reset) {
            return setting.reset();
        }
        return switch (setting) {
            case Setting.Toggle toggle -> toggle.toggle();
            case Setting.Number number -> number.step(direction, big);
            case Setting.Choice choice -> choice.cycle(direction);
        };
    }
}
