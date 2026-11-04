package io.github.sx2xoox.mcpl.game.siege.shop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.siege.arena.AttributeBonus;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeArena;
import io.github.sx2xoox.mcpl.game.siege.arena.LaneModifiers;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureKind;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureLook;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureSounds;
import io.github.sx2xoox.mcpl.game.siege.creature.Creatures;
import io.github.sx2xoox.mcpl.game.siege.economy.Gold;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/**
 * The 공성전 v2 shop (docs/siege-v2.md §3). Each side's base has a terminal at {@link LaneSites#shop} (an interaction
 * entity, a label and the 태엽 상인); a player within {@link ShopSettings#range()} of the own terminal opens a chest
 * menu by clicking it ({@link #open}). Purchases take {@link Gold}, fill one of the {@link ShopSettings#slots()} item
 * slots and take effect at once:
 *
 * <ul>
 *     <li>passives become attribute modifiers on the buyer (armour, melee damage, speed) and {@link LaneModifiers}
 *     (ability damage dealt/taken, cooldown, structure damage, respawn time) the systems apply;</li>
 *     <li>consumables (체력 사탕, 망원경 와드) and the default 귀환 깃발 are tagged items in the hotbar, used with a
 *     right click; the inventory is kept in step with what the player owns every second.</li>
 * </ul>
 *
 * Everything (items, gold via {@link Gold#reset}, wards, terminals) is reset at the end of a round ({@link #clear()}).
 */
public final class SiegeShop implements Listener {

    /** What a purchase did. */
    public enum Result {
        BOUGHT, UPGRADED, NOT_PLAYING, TOO_FAR, OWNED, STACK_FULL, SLOTS_FULL, NO_GOLD;

        public boolean ok() {
            return this == BOUGHT || this == UPGRADED;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** One filled item slot: an item and (for consumables) how many are left. */
    public static final class Owned {
        private ShopItem item;
        private int count;

        Owned(ShopItem item, int count) {
            this.item = item;
            this.count = count;
        }

        public ShopItem item() {
            return item;
        }

        public int count() {
            return count;
        }
    }

    /** A placed ward. */
    private record Ward(UUID owner, LaneSites.Side side, Location at, long until, @Nullable ItemDisplay display) {
    }

    /** A running candy: health per tick and ticks left. */
    private static final class Heal {
        private final double perTick;
        private int left;

        Heal(double perTick, int left) {
            this.perTick = perTick;
            this.left = left;
        }
    }

    /** A recall channel: when it started. */
    private record Recall(long started) {
    }

    private static final String FLAG = "recall_flag";

    private final SiegeArena arena;
    private final ShopSettings settings;
    private final Gold gold;
    private final LaneModifiers modifiers;
    private final CreatureLook look;
    private final Messages messages;
    private final NamespacedKey itemKey;
    private final Map<UUID, List<Owned>> owned = new HashMap<>();
    private final Map<UUID, ShopMenu> menus = new HashMap<>();
    private final Map<LaneSites.Side, Location> terminals = new EnumMap<>(LaneSites.Side.class);
    private final List<Entity> terminalParts = new ArrayList<>();
    private final Map<UUID, Heal> heals = new HashMap<>();
    private final Map<UUID, Recall> recalls = new LinkedHashMap<>();
    private final Map<UUID, Deque<Ward>> wards = new HashMap<>();
    private long now;

    public SiegeShop(SiegeArena arena, ShopSettings settings, Gold gold, LaneModifiers modifiers,
            CreatureLook look) {
        this.arena = arena;
        this.settings = settings;
        this.gold = gold;
        this.modifiers = modifiers;
        this.look = look;
        this.messages = arena.messages();
        this.itemKey = new NamespacedKey(arena.plugin(), "siege_shop_item");
        for (LaneSites.Side side : LaneSites.Side.values()) {
            LaneSites.Spot spot = arena.sites().shop(side);
            Location at = arena.at(spot.x(), spot.y(), spot.z());
            at.setYaw(spot.yaw());
            terminals.put(side, at);
        }
    }

    // ------------------------------------------------------------------ flow

    /** A round begins: the terminals stand, everyone has a 귀환 깃발 and empty slots. */
    public void begin() {
        clear();
        for (Map.Entry<LaneSites.Side, Location> entry : terminals.entrySet()) {
            spawnTerminal(entry.getKey(), entry.getValue());
        }
        for (LaneSites.Side side : LaneSites.Side.values()) {
            for (Player player : arena.fighters(side)) {
                sync(player);
            }
        }
    }

    /** Every tick of the round. */
    public void tick() {
        now++;
        recalls();
        heals();
        if (now % 20 == 0) {
            second();
        }
    }

    /** The round is over: items, effects, wards, menus and terminals are gone. */
    public void clear() {
        for (ShopMenu menu : new ArrayList<>(menus.values())) {
            Player player = Bukkit.getPlayer(menu.owner());
            if (player != null && player.getOpenInventory().getTopInventory().getHolder(false) == menu) {
                player.closeInventory();
            }
        }
        menus.clear();
        for (LaneSites.Side side : LaneSites.Side.values()) {
            for (UUID member : arena.members(side)) {
                Player player = Bukkit.getPlayer(member);
                if (player != null) {
                    removeItems(player);
                    removeEffects(player);
                }
            }
        }
        for (UUID id : owned.keySet()) {
            modifiers.removePrefix(id, "shop:");
        }
        owned.clear();
        heals.clear();
        recalls.clear();
        for (Deque<Ward> placed : wards.values()) {
            for (Ward ward : placed) {
                removeWard(ward);
            }
        }
        wards.clear();
        for (Entity part : terminalParts) {
            part.remove();
            arena.untrack(part);
            if (part instanceof Mob mob) {
                look.removed(mob);
            }
        }
        terminalParts.clear();
        now = 0;
    }

    private void second() {
        for (LaneSites.Side side : LaneSites.Side.values()) {
            for (Player player : arena.fighters(side)) {
                sync(player);
            }
        }
        for (Deque<Ward> placed : wards.values()) {
            placed.removeIf(ward -> {
                if (ward.until() <= now) {
                    removeWard(ward);
                    return true;
                }
                reveal(ward);
                return false;
            });
        }
    }

    // ------------------------------------------------------------------ terminal

    private void spawnTerminal(LaneSites.Side side, Location at) {
        try {
            Interaction interaction = at.getWorld().spawn(at, Interaction.class, entity -> {
                entity.setPersistent(false);
                entity.setInteractionWidth(1.6f);
                entity.setInteractionHeight(2.6f);
                entity.setResponsive(true);
            });
            arena.track(interaction);
            terminalParts.add(interaction);
            TextDisplay label = at.getWorld().spawn(at.clone().add(0, 3.0, 0), TextDisplay.class, display -> {
                display.setPersistent(false);
                display.setBillboard(Display.Billboard.CENTER);
                display.setShadowed(true);
                display.setBrightness(new Display.Brightness(15, 15));
                display.text(messages.get("shop.terminal"));
            });
            arena.track(label);
            terminalParts.add(label);
        } catch (RuntimeException e) {
            arena.plugin().getLogger().log(Level.WARNING, "Could not build the " + side + " shop terminal", e);
        }
        try {
            Mob keeper = look.spawn(CreatureKind.SHOPKEEPER, side, at, mob -> {
                Creatures.prepare(mob, messages.get("shop.keeper").color(MinionWaves.colour(side)), 20, 1, 1);
                // the terminal's "상점" label names it: a second name tag right under it would only crowd it
                mob.setCustomNameVisible(false);
                mob.setAI(false);
                mob.setInvulnerable(true);
                mob.setSilent(true);
            });
            // the shop spot's yaw is where the keeper looks (LaneSites#shop: out at the players coming to it)
            Creatures.turn(keeper, at.getYaw());
            arena.track(keeper);
            terminalParts.add(keeper);
        } catch (RuntimeException e) {
            // Cosmetic: the interaction entity alone is the terminal.
            arena.plugin().getLogger().log(Level.FINE, "Could not spawn the " + side + " shopkeeper", e);
        }
    }

    /** Whether an entity belongs to a shop terminal. */
    public boolean isTerminal(@Nullable Entity entity) {
        return entity != null && terminalParts.contains(entity);
    }

    /** The terminal location of a side. */
    public Location terminal(LaneSites.Side side) {
        return terminals.get(side).clone();
    }

    /** Whether the player stands close enough to the own terminal to shop. */
    public boolean inRange(Player player) {
        LaneSites.Side side = arena.side(player.getUniqueId());
        if (side == null) {
            return false;
        }
        Location terminal = terminals.get(side);
        return player.getWorld().equals(terminal.getWorld())
                && player.getLocation().distanceSquared(terminal) <= settings.range() * settings.range();
    }

    // ------------------------------------------------------------------ menu

    /** Opens the shop menu, or tells the player why not. */
    public boolean open(Player player) {
        Result refusal = arena.side(player.getUniqueId()) == null ? Result.NOT_PLAYING
                : inRange(player) ? null : Result.TOO_FAR;
        if (refusal != null) {
            refuse(player, refusal);
            return false;
        }
        ShopMenu menu = new ShopMenu(player.getUniqueId(), messages.get("shop.title"),
                arena.side(player.getUniqueId()));
        menus.put(player.getUniqueId(), menu);
        render(player, menu);
        player.openInventory(menu.getInventory());
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.4f);
        return true;
    }

    private void render(Player player, ShopMenu menu) {
        menu.render(messages, settings, gold.balance(player.getUniqueId()), owned(player.getUniqueId()));
    }

    /** The open menu of a player, or null. */
    public @Nullable ShopMenu menu(Player player) {
        return menus.get(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ShopMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= ShopMenu.SIZE) {
            return;
        }
        ShopItem.Category tab = menu.tabAt(event.getRawSlot());
        if (tab != null) {
            menu.category(tab);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            render(player, menu);
            return;
        }
        ShopItem item = menu.itemAt(event.getRawSlot());
        if (item != null) {
            buy(player, item);
            render(player, menu);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ShopMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof ShopMenu menu) {
            menus.remove(menu.owner(), menu);
        }
    }

    /** Right-clicking the terminal (interaction entity or the shopkeeper) opens the menu. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onTerminal(PlayerInteractEntityEvent event) {
        if (isTerminal(event.getRightClicked())) {
            event.setCancelled(true);
            greet(event.getRightClicked());
            open(event.getPlayer());
        }
    }

    /** The shopkeeper at a clicked terminal bows (its attack motion) with a friendly word. */
    private void greet(Entity clicked) {
        for (Entity part : terminalParts) {
            if (part instanceof Mob keeper && keeper.getWorld().equals(clicked.getWorld())
                    && keeper.getLocation().distanceSquared(clicked.getLocation()) <= 9) {
                look.attack(keeper);
                CreatureSounds.attack(arena, CreatureKind.SHOPKEEPER, keeper.getLocation());
                return;
            }
        }
    }

    /** Left-clicking it too; the terminal and the shopkeeper take no damage. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onTerminalHit(EntityDamageEvent event) {
        if (!isTerminal(event.getEntity())) {
            return;
        }
        event.setCancelled(true);
        if (event.getDamageSource().getCausingEntity() instanceof Player player
                && event.getDamageSource().getDirectEntity() == player) {
            greet(event.getEntity());
            open(player);
        }
    }

    // ------------------------------------------------------------------ buying

    /** The filled slots of a player. */
    public List<Owned> owned(UUID player) {
        return owned.getOrDefault(player, List.of());
    }

    /** How many of an item a player has (consumables: the count; others: 1 or 0). */
    public int count(UUID player, ShopItem item) {
        for (Owned slot : owned(player)) {
            if (slot.item == item) {
                return slot.count;
            }
        }
        return 0;
    }

    /** Buys an item for a player (from the menu or a command), telling the player the outcome. */
    public Result buy(Player player, ShopItem item) {
        Result result = tryBuy(player, item);
        if (result.ok()) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.3f);
            player.sendActionBar(messages.get("shop.bought", Messages.tag("item",
                    messages.get("shop.item." + item.key() + ".name")),
                    Messages.tag("balance", gold.balance(player.getUniqueId()))));
            apply(player);
            sync(player);
        } else {
            refuse(player, result);
        }
        return result;
    }

    private Result tryBuy(Player player, ShopItem item) {
        UUID id = player.getUniqueId();
        if (arena.side(id) == null) {
            return Result.NOT_PLAYING;
        }
        if (!inRange(player)) {
            return Result.TOO_FAR;
        }
        List<Owned> slots = owned.computeIfAbsent(id, key -> new ArrayList<>());
        int price = settings.price(item);
        Owned replace = null;
        Owned stack = null;
        for (Owned slot : slots) {
            if (slot.item == item && !item.consumable()) {
                return Result.OWNED;
            }
            if (slot.item == item) {
                stack = slot;
            } else if (item.sameLine(slot.item)) {
                if (slot.item.tier() >= item.tier()) {
                    return Result.OWNED;
                }
                replace = slot;
            }
        }
        int perBuy = item == ShopItem.CANDY ? settings.candyCount() : item == ShopItem.WARD ? settings.wardCount() : 1;
        int stackMax = item == ShopItem.CANDY ? settings.candyStack() : settings.wardStack();
        if (stack != null && stack.count + perBuy > stackMax) {
            return Result.STACK_FULL;
        }
        if (stack == null && replace == null && slots.size() >= settings.slots()) {
            return Result.SLOTS_FULL;
        }
        if (replace != null) {
            price = Math.max(0, price - settings.price(replace.item));
        }
        if (!gold.spend(id, price)) {
            return Result.NO_GOLD;
        }
        if (stack != null) {
            stack.count += perBuy;
            return Result.BOUGHT;
        }
        if (replace != null) {
            replace.item = item;
            replace.count = 1;
            return Result.UPGRADED;
        }
        slots.add(new Owned(item, perBuy));
        return Result.BOUGHT;
    }

    private void refuse(Player player, Result result) {
        player.sendActionBar(messages.get("shop.refused." + result.key(),
                Messages.tag("range", (int) settings.range()), Messages.tag("slots", settings.slots())));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.2f);
    }

    /** One fewer of a consumable; the slot frees when none are left. */
    private void consume(UUID player, ShopItem item) {
        List<Owned> slots = owned.get(player);
        if (slots == null) {
            return;
        }
        for (Owned slot : slots) {
            if (slot.item == item) {
                slot.count--;
                if (slot.count <= 0) {
                    slots.remove(slot);
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ effects

    /** Sets the buyer's passive effects from the owned items. */
    public void apply(Player player) {
        UUID id = player.getUniqueId();
        int armor = 0;
        int sword = 0;
        for (Owned slot : owned(id)) {
            if ("armor".equals(slot.item.line())) {
                armor = slot.item.tier();
            } else if ("sword".equals(slot.item.line())) {
                sword = slot.item.tier();
            }
        }
        AttributeBonus.set(player, Attribute.ARMOR, key("armor"), armor > 0 ? settings.armorPoints()[armor - 1] : 0,
                AttributeModifier.Operation.ADD_NUMBER);
        modifiers.set(id, "shop:armor", LaneModifiers.Stat.SKILL_DAMAGE_TAKEN,
                armor > 0 ? -settings.armorSkill()[armor - 1] : 0);
        AttributeBonus.set(player, Attribute.ATTACK_DAMAGE, key("sword"),
                sword > 0 ? settings.swordDamage()[sword - 1] : 0, AttributeModifier.Operation.ADD_NUMBER);
        boolean shoes = count(id, ShopItem.SHOES) > 0;
        AttributeBonus.set(player, Attribute.MOVEMENT_SPEED, key("shoes"), shoes ? settings.shoesSpeed() : 0,
                AttributeModifier.Operation.ADD_SCALAR);
        modifiers.set(id, "shop:orb", LaneModifiers.Stat.SKILL_DAMAGE,
                count(id, ShopItem.ORB) > 0 ? settings.orbSkill() : 0);
        modifiers.set(id, "shop:hourglass", LaneModifiers.Stat.COOLDOWN,
                count(id, ShopItem.HOURGLASS) > 0 ? settings.hourglass() : 0);
        modifiers.set(id, "shop:hammer", LaneModifiers.Stat.STRUCTURE_DAMAGE,
                count(id, ShopItem.HAMMER) > 0 ? settings.hammer() : 0);
        modifiers.set(id, "shop:revive", LaneModifiers.Stat.RESPAWN_SECONDS,
                count(id, ShopItem.REVIVE_CLOCK) > 0 ? settings.reviveSeconds() : 0);
    }

    /** Roman numerals of the 장난감 갑옷 tiers in item names (tier 0 = the kit's armour: none). */
    private static final String[] TIER_NAMES = {"", " I", " II", " III"};

    /** The 장난감 갑옷 tier {@code player} owns (0 = none): the look of the armour they wear ({@link ToyArmor}). */
    public int armorTier(UUID player) {
        int tier = 0;
        for (Owned slot : owned(player)) {
            if ("armor".equals(slot.item.line())) {
                tier = Math.max(tier, slot.item.tier());
            }
        }
        return Math.min(tier, ToyArmor.MAX_TIER);
    }

    private void removeEffects(Player player) {
        AttributeBonus.remove(player, Attribute.ARMOR, key("armor"));
        AttributeBonus.remove(player, Attribute.ATTACK_DAMAGE, key("sword"));
        AttributeBonus.remove(player, Attribute.MOVEMENT_SPEED, key("shoes"));
        modifiers.removePrefix(player.getUniqueId(), "shop:");
    }

    private NamespacedKey key(String what) {
        return new NamespacedKey(arena.plugin(), "siege_shop_" + what);
    }

    // ------------------------------------------------------------------ hotbar items

    /**
     * Keeps the player's inventory in step with what they own: the 귀환 깃발 and their candies and wards (tagged items);
     * the passive effects are set again too (a respawn may have reset them).
     */
    public void sync(Player player) {
        UUID id = player.getUniqueId();
        LaneSites.Side side = arena.side(id);
        if (side == null) {
            return;
        }
        apply(player);
        int tier = armorTier(id);
        ToyArmor.dress(player, side, tier, piece -> messages.has("shop.toy-armor." + piece)
                ? messages.get("shop.toy-armor." + piece, Messages.tag("tier", TIER_NAMES[tier])) : null);
        Map<String, Integer> wanted = new LinkedHashMap<>();
        wanted.put(FLAG, 1);
        for (Owned slot : owned(id)) {
            if (slot.item.consumable()) {
                wanted.put(slot.item.key(), slot.count);
            }
        }
        PlayerInventory inventory = player.getInventory();
        Map<String, Integer> have = new HashMap<>();
        for (ItemStack stack : inventory.getContents()) {
            String tag = tag(stack);
            if (tag != null) {
                have.merge(tag, stack.getAmount(), Integer::sum);
            }
        }
        if (have.equals(wanted)) {
            return;
        }
        removeItems(player);
        place(inventory, flag(side), 8);
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            if (entry.getKey().equals(FLAG)) {
                continue;
            }
            ShopItem item = ShopItem.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
            place(inventory, consumable(item, entry.getValue()), item == ShopItem.CANDY ? 6 : 7);
        }
    }

    private static void place(PlayerInventory inventory, ItemStack item, int preferred) {
        ItemStack there = inventory.getItem(preferred);
        if (there == null || there.isEmpty()) {
            inventory.setItem(preferred, item);
            return;
        }
        int empty = inventory.firstEmpty();
        if (empty >= 0) {
            inventory.setItem(empty, item);
        }
    }

    private void removeItems(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (tag(contents[slot]) != null) {
                inventory.setItem(slot, null);
            }
        }
    }

    /** The shop tag of an item ({@code recall_flag}, {@code candy}, {@code ward}), or null. */
    public @Nullable String tag(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return item.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    private ItemStack flag(LaneSites.Side side) {
        ItemStack item = ShopMenu.named(ItemStack.of(side == LaneSites.Side.BLUE ? Material.BLUE_BANNER
                : Material.RED_BANNER), messages.get("shop.flag.name"), messages.lines("shop.flag.lore",
                Messages.tag("seconds", settings.recallTicks() / 20)));
        item.editPersistentDataContainer(pdc -> pdc.set(itemKey, PersistentDataType.STRING, FLAG));
        return item;
    }

    private ItemStack consumable(ShopItem shopItem, int count) {
        ItemStack item = ShopMenu.named(ItemStack.of(shopItem.icon(), Math.max(1, Math.min(64, count))),
                messages.get("shop.item." + shopItem.key() + ".name"),
                messages.lines("shop.item." + shopItem.key() + ".lore"));
        item.editPersistentDataContainer(pdc -> pdc.set(itemKey, PersistentDataType.STRING, shopItem.key()));
        return item;
    }

    /** Right-click with a shop item: recall, eat a candy, place a ward. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        String tag = tag(event.getItem());
        if (tag == null) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        Player player = event.getPlayer();
        if (!arena.canFight(player)) {
            return;
        }
        switch (tag) {
            case FLAG -> recall(player);
            case "candy" -> eatCandy(player);
            case "ward" -> placeWard(player, event.getInteractionPoint());
            default -> {
            }
        }
    }

    /** Shop items stay with their owner. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent event) {
        if (tag(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(item -> tag(item) != null);
        recalls.remove(event.getEntity().getUniqueId());
        heals.remove(event.getEntity().getUniqueId());
    }

    // ------------------------------------------------------------------ candy

    /** Eats one candy: health over time. */
    public boolean eatCandy(Player player) {
        UUID id = player.getUniqueId();
        if (count(id, ShopItem.CANDY) <= 0) {
            return false;
        }
        consume(id, ShopItem.CANDY);
        Heal running = heals.get(id);
        int ticks = Math.max(1, settings.candyTicks());
        double perTick = settings.candyHeal() / ticks;
        if (running != null) {
            // A second candy while one works: the rest of the first is added to the new one.
            perTick = (running.perTick * running.left + settings.candyHeal()) / ticks;
        }
        heals.put(id, new Heal(perTick, ticks));
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.8f, 1.4f);
        player.sendActionBar(messages.get("shop.candy-used", Messages.tag("heal", (int) settings.candyHeal()),
                Messages.tag("seconds", ticks / 20)));
        sync(player);
        return true;
    }

    private void heals() {
        heals.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            Heal heal = entry.getValue();
            if (player == null || !arena.canFight(player) || heal.left-- <= 0) {
                return true;
            }
            player.setHealth(Math.min(Creatures.maxHealth(player), player.getHealth() + heal.perTick));
            return heal.left <= 0;
        });
    }

    // ------------------------------------------------------------------ wards

    /** Places a ward where the player looks (or stands): it reveals enemies nearby for a while. */
    public boolean placeWard(Player player, @Nullable Location where) {
        UUID id = player.getUniqueId();
        LaneSites.Side side = arena.side(id);
        if (side == null || count(id, ShopItem.WARD) <= 0) {
            return false;
        }
        consume(id, ShopItem.WARD);
        Location at = where != null && where.getWorld() != null && where.getWorld().equals(player.getWorld())
                && where.distanceSquared(player.getLocation()) <= 36 ? where.clone() : player.getLocation();
        ItemDisplay display = null;
        try {
            display = at.getWorld().spawn(at.clone().add(0, 0.6, 0), ItemDisplay.class, entity -> {
                entity.setPersistent(false);
                entity.setItemStack(ItemStack.of(Material.SPYGLASS));
                entity.setBillboard(Display.Billboard.VERTICAL);
                entity.setBrightness(new Display.Brightness(15, 15));
            });
            arena.track(display);
        } catch (RuntimeException e) {
            arena.plugin().getLogger().log(Level.FINE, "Could not show a ward", e);
        }
        Deque<Ward> placed = wards.computeIfAbsent(id, key -> new ArrayDeque<>());
        Ward ward = new Ward(id, side, at, now + settings.wardTicks(), display);
        placed.addLast(ward);
        while (placed.size() > settings.wardsPlaced()) {
            removeWard(placed.removeFirst());
        }
        reveal(ward);
        player.playSound(at, Sound.ITEM_SPYGLASS_USE, 0.8f, 1.2f);
        player.sendActionBar(messages.get("shop.ward-placed", Messages.tag("seconds", settings.wardTicks() / 20)));
        sync(player);
        return true;
    }

    /** Enemies of the ward's side within its radius glow. */
    private void reveal(Ward ward) {
        double radius = settings.wardRadius();
        for (Player enemy : arena.fighters(MinionWaves.enemy(ward.side()))) {
            if (enemy.getWorld().equals(ward.at().getWorld())
                    && enemy.getLocation().distanceSquared(ward.at()) <= radius * radius) {
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 30, 0, false, false, false));
            }
        }
    }

    private void removeWard(Ward ward) {
        if (ward.display() != null) {
            ward.display().remove();
            arena.untrack(ward.display());
        }
    }

    /** The standing wards of a player. */
    public int wards(UUID player) {
        Deque<Ward> placed = wards.get(player);
        return placed == null ? 0 : placed.size();
    }

    // ------------------------------------------------------------------ recall

    /** Starts (or, clicked again, stops) the 귀환 깃발 channel. */
    public void recall(Player player) {
        UUID id = player.getUniqueId();
        if (recalls.remove(id) != null) {
            player.sendActionBar(messages.get("shop.recall-stopped"));
            return;
        }
        recalls.put(id, new Recall(now));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.6f);
    }

    public boolean isRecalling(UUID player) {
        return recalls.containsKey(player);
    }

    private void recalls() {
        recalls.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            LaneSites.Side side = arena.side(entry.getKey());
            if (player == null || side == null || !arena.canFight(player)) {
                return true;
            }
            long done = now - entry.getValue().started();
            if (done >= settings.recallTicks()) {
                List<LaneSites.Spot> spawns = arena.sites().spawns(side);
                LaneSites.Spot spot = spawns.isEmpty() ? arena.sites().shop(side) : spawns.getFirst();
                Location home = arena.at(spot.x(), spot.y(), spot.z());
                home.setYaw(spot.yaw());
                player.teleport(home);
                player.playSound(home, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
                player.sendActionBar(messages.get("shop.recall-done"));
                return true;
            }
            if (done % 5 == 0) {
                player.sendActionBar(messages.get("shop.recall-channel", Messages.tag("seconds",
                        String.format(Locale.ROOT, "%.1f", (settings.recallTicks() - done) / 20.0))));
                player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 0.2, 0), 3, 0.4, 0.1,
                        0.4, 0.01);
            }
            return false;
        });
    }

    /** A hit stops the recall. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && recalls.remove(player.getUniqueId()) != null) {
            player.sendActionBar(messages.get("shop.recall-interrupted"));
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.4f);
        }
    }

    public ShopSettings settings() {
        return settings;
    }
}
