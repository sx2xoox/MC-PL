package io.github.sx2xoox.mcpl.core.player;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.jspecify.annotations.Nullable;

/**
 * Everything a match changes on a player, captured before the match and put back afterwards: inventory (including
 * armor and offhand, plus loose cursor/crafting-grid items when taken with {@link #takeLooseItems}), location, game
 * mode, health, absorption, food, saturation, exhaustion, experience, potion effects, fire,
 * freeze, fall distance, air, flight, walk/fly speed, glowing/invisible flags and scoreboard. Restoring also
 * clears the invulnerable flag (modes may set it, e.g. during a preparation phase).
 *
 * <p>Restoring strips every attribute modifier whose key namespace belongs to this plugin, so abilities can never
 * leave a modifier behind. Snapshots are persisted by {@link SnapshotStore}; the scoreboard is kept in memory only
 * (after a restart the main scoreboard is used).
 */
public final class PlayerSnapshot {

    static final int FORMAT_VERSION = 1;

    private final UUID playerId;
    private final String playerName;
    private final String gameId;
    private final long createdAt;
    private final @Nullable String worldName;
    private final @Nullable UUID worldId;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final GameMode gameMode;
    private final double health;
    private final int food;
    private final float saturation;
    private final float exhaustion;
    private final int level;
    private final float exp;
    private final int totalExperience;
    private final List<PotionEffect> effects;
    private final int fireTicks;
    private final int freezeTicks;
    private final float fallDistance;
    private final int remainingAir;
    private final boolean allowFlight;
    private final boolean flying;
    private final float walkSpeed;
    private final float flySpeed;
    private final boolean glowing;
    private final boolean invisible;
    private final ItemStack[] inventory;
    /** Loose items that were not in the inventory: the cursor stack and crafting-grid/menu inputs. */
    private final ItemStack[] extras;
    private final double absorption;
    private final @Nullable Scoreboard scoreboard;

    private PlayerSnapshot(Builder b) {
        this.playerId = b.playerId;
        this.playerName = b.playerName;
        this.gameId = b.gameId;
        this.createdAt = b.createdAt;
        this.worldName = b.worldName;
        this.worldId = b.worldId;
        this.x = b.x;
        this.y = b.y;
        this.z = b.z;
        this.yaw = b.yaw;
        this.pitch = b.pitch;
        this.gameMode = b.gameMode;
        this.health = b.health;
        this.food = b.food;
        this.saturation = b.saturation;
        this.exhaustion = b.exhaustion;
        this.level = b.level;
        this.exp = b.exp;
        this.totalExperience = b.totalExperience;
        this.effects = List.copyOf(b.effects);
        this.fireTicks = b.fireTicks;
        this.freezeTicks = b.freezeTicks;
        this.fallDistance = b.fallDistance;
        this.remainingAir = b.remainingAir;
        this.allowFlight = b.allowFlight;
        this.flying = b.flying;
        this.walkSpeed = b.walkSpeed;
        this.flySpeed = b.flySpeed;
        this.glowing = b.glowing;
        this.invisible = b.invisible;
        this.inventory = cloneItems(b.inventory);
        this.extras = cloneItems(b.extras);
        this.absorption = b.absorption;
        this.scoreboard = b.scoreboard;
    }

    /** Captures the player's current state. {@code gameId} is informational (which match took the snapshot). */
    public static PlayerSnapshot capture(Player player, String gameId) {
        return capture(player, gameId, List.of());
    }

    /**
     * Captures the player's current state plus {@code extras}: loose items taken from the player beforehand (see
     * {@link #takeLooseItems}) that are given back on restore.
     */
    public static PlayerSnapshot capture(Player player, String gameId, List<ItemStack> extras) {
        Builder b = new Builder();
        b.playerId = player.getUniqueId();
        b.playerName = player.getName();
        b.gameId = gameId;
        b.createdAt = System.currentTimeMillis();
        Location location = player.getLocation();
        World world = location.getWorld();
        b.worldName = world == null ? null : world.getName();
        b.worldId = world == null ? null : world.getUID();
        b.x = location.getX();
        b.y = location.getY();
        b.z = location.getZ();
        b.yaw = location.getYaw();
        b.pitch = location.getPitch();
        b.gameMode = player.getGameMode();
        b.health = player.getHealth();
        b.food = player.getFoodLevel();
        b.saturation = player.getSaturation();
        b.exhaustion = player.getExhaustion();
        b.level = player.getLevel();
        b.exp = player.getExp();
        b.totalExperience = player.getTotalExperience();
        b.effects = new ArrayList<>(player.getActivePotionEffects());
        b.fireTicks = player.getFireTicks();
        b.freezeTicks = player.getFreezeTicks();
        b.fallDistance = player.getFallDistance();
        b.remainingAir = player.getRemainingAir();
        b.allowFlight = player.getAllowFlight();
        b.flying = player.isFlying();
        b.walkSpeed = player.getWalkSpeed();
        b.flySpeed = player.getFlySpeed();
        b.glowing = player.isGlowing();
        b.invisible = player.isInvisible();
        b.inventory = player.getInventory().getContents();
        b.extras = extras.toArray(new ItemStack[0]);
        b.absorption = player.getAbsorptionAmount();
        b.scoreboard = player.getScoreboard();
        return new PlayerSnapshot(b);
    }

    /**
     * Takes the items that belong to the player but are not in their inventory: the stack on the cursor and the
     * slots an open menu would hand back on close (2x2 or 3x3 crafting grid; anvil, smithing table, grindstone,
     * enchanting table, loom, stonecutter, cartography table, merchant and beacon inputs; never a result slot and never
     * a block's own inventory such as a chest or crafter). The slots are emptied, so closing the menu afterwards moves
     * nothing; the caller keeps the returned items (in a snapshot) or gives them back ({@link #giveBack}).
     */
    public static List<ItemStack> takeLooseItems(Player player) {
        List<ItemStack> taken = new ArrayList<>();
        ItemStack cursor = player.getItemOnCursor();
        if (!isEmpty(cursor)) {
            taken.add(cursor.clone());
            player.setItemOnCursor(null);
        }
        InventoryView view = player.getOpenInventory();
        Inventory top = view == null ? null : view.getTopInventory();
        if (top instanceof CraftingInventory crafting) {
            ItemStack[] matrix = crafting.getMatrix();
            boolean any = false;
            for (ItemStack item : matrix) {
                if (!isEmpty(item)) {
                    taken.add(item.clone());
                    any = true;
                }
            }
            if (any) {
                crafting.setMatrix(new ItemStack[matrix.length]);
            }
        } else if (top != null && HANDED_BACK_ON_CLOSE.contains(top.getType())) {
            int result = resultSlot(top.getType());
            for (int slot = 0; slot < top.getSize(); slot++) {
                ItemStack item = top.getItem(slot);
                if (slot != result && !isEmpty(item)) {
                    taken.add(item.clone());
                    top.setItem(slot, null);
                }
            }
        }
        return taken;
    }

    /** Menus (besides crafting grids) whose input slots go back to the player when closed. */
    private static final Set<InventoryType> HANDED_BACK_ON_CLOSE = EnumSet.of(InventoryType.ANVIL,
            InventoryType.SMITHING, InventoryType.GRINDSTONE, InventoryType.ENCHANTING, InventoryType.LOOM,
            InventoryType.STONECUTTER, InventoryType.CARTOGRAPHY, InventoryType.MERCHANT, InventoryType.BEACON);

    /** The result slot of a menu in {@link #HANDED_BACK_ON_CLOSE} (as {@code InventoryView#getSlotType}), or -1. */
    private static int resultSlot(InventoryType type) {
        return switch (type) {
            case ANVIL, CARTOGRAPHY, GRINDSTONE, MERCHANT -> 2;
            case STONECUTTER -> 1;
            case LOOM, SMITHING -> 3;
            default -> -1;
        };
    }

    /** Adds items to the player's inventory and drops whatever does not fit at their feet. */
    public static void giveBack(Player player, Collection<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(items.toArray(new ItemStack[0]));
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItem(player.getLocation(), leftover);
        }
    }

    private static boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.isEmpty();
    }

    // ------------------------------------------------------------------ restore

    /**
     * Puts everything back. The player must be alive (a dead player cannot be teleported); callers keep the snapshot
     * pending and retry after respawn otherwise.
     *
     * @param pluginNamespace attribute modifiers whose key has this namespace are removed
     * @return true if the player is back at the saved location; false if the teleport was cancelled (e.g. by another
     * plugin) or the saved world is not loaded (they were sent to the main world spawn instead). Everything else is
     * restored either way.
     */
    public boolean restore(Player player, String pluginNamespace) {
        player.closeInventory();
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        player.eject();
        Location saved = savedLocation();
        boolean moved = player.teleport(saved != null ? saved : location(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.setGameMode(gameMode);

        ItemStack[] contents = player.getInventory().getContents();
        ItemStack[] restored = new ItemStack[contents.length];
        for (int i = 0; i < restored.length && i < inventory.length; i++) {
            restored[i] = inventory[i] == null || inventory[i].isEmpty() ? null : inventory[i].clone();
        }
        player.getInventory().setContents(restored);
        player.setItemOnCursor(null);
        giveBack(player, List.of(cloneItems(extras)));

        stripPluginModifiers(player, pluginNamespace);
        // effects first: a HEALTH_BOOST raises the maximum the saved health is clamped to
        player.clearActivePotionEffects();
        player.addPotionEffects(effects);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth == null ? 20.0 : maxHealth.getValue();
        player.setHealth(Math.max(0.5, Math.min(max, health)));
        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
        player.setLevel(level);
        player.setExp(Math.max(0f, Math.min(1f, exp)));
        player.setTotalExperience(Math.max(0, totalExperience));

        // after the effects: re-adding ABSORPTION would otherwise refill the absorption hearts
        AttributeInstance maxAbsorption = player.getAttribute(Attribute.MAX_ABSORPTION);
        double absorptionCap = maxAbsorption == null ? absorption : maxAbsorption.getValue();
        player.setAbsorptionAmount(Math.max(0, Math.min(absorptionCap, absorption)));
        player.setFireTicks(fireTicks);
        player.setFreezeTicks(freezeTicks);
        player.setFallDistance(fallDistance);
        player.setRemainingAir(remainingAir);

        player.setAllowFlight(allowFlight);
        if (allowFlight) {
            player.setFlying(flying);
        }
        player.setWalkSpeed(clampSpeed(walkSpeed));
        player.setFlySpeed(clampSpeed(flySpeed));
        player.setGlowing(glowing);
        player.setInvisible(invisible);
        player.setInvulnerable(false);

        player.setScoreboard(scoreboard != null ? scoreboard : Bukkit.getScoreboardManager().getMainScoreboard());
        return saved != null && moved;
    }

    /** Teleports the player to the saved location (nothing else). False if the world is missing or it was cancelled. */
    public boolean teleportBack(Player player) {
        Location saved = savedLocation();
        if (saved == null) {
            return false;
        }
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        player.setFallDistance(0f);
        return player.teleport(saved, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    /** "world (uid) x, y, z" of the saved location, for logs. */
    public String describeLocation() {
        return (worldName == null ? "?" : worldName) + " (" + worldId + ") "
                + String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", x, y, z);
    }

    /** Removes every attribute modifier of the player whose key namespace is {@code namespace}. */
    public static void stripPluginModifiers(Player player, String namespace) {
        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : List.copyOf(instance.getModifiers())) {
                if (modifier.getKey().getNamespace().equals(namespace)) {
                    instance.removeModifier(modifier);
                }
            }
        }
    }

    /** Where the player stood; falls back to the main world spawn if that world is gone. */
    public Location location() {
        Location saved = savedLocation();
        return saved != null ? saved : Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    /** Where the player stood, or null if that world is not loaded. */
    public @Nullable Location savedLocation() {
        World world = worldId == null ? null : Bukkit.getWorld(worldId);
        if (world == null && worldName != null) {
            world = Bukkit.getWorld(worldName);
        }
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }

    private static float clampSpeed(float speed) {
        return Math.max(-1f, Math.min(1f, speed));
    }

    // ------------------------------------------------------------------ accessors

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public String gameId() {
        return gameId;
    }

    public long createdAt() {
        return createdAt;
    }

    public GameMode gameMode() {
        return gameMode;
    }

    public double health() {
        return health;
    }

    public int level() {
        return level;
    }

    /** A copy of the saved inventory contents (storage, armor, offhand). */
    public ItemStack[] inventory() {
        return cloneItems(inventory);
    }

    /** A copy of the loose items (cursor, crafting grid, menu inputs) taken at capture; given back on restore. */
    public ItemStack[] extras() {
        return cloneItems(extras);
    }

    public double absorption() {
        return absorption;
    }

    public List<PotionEffect> effects() {
        return effects;
    }

    // ------------------------------------------------------------------ persistence

    /** Writes this snapshot into {@code out} (items as Base64 of {@link ItemStack#serializeItemsAsBytes}). */
    public void save(ConfigurationSection out) {
        out.set("format", FORMAT_VERSION);
        out.set("player", playerId.toString());
        out.set("name", playerName);
        out.set("game", gameId);
        out.set("created", createdAt);
        out.set("location.world", worldName);
        out.set("location.world-uid", worldId == null ? null : worldId.toString());
        out.set("location.x", x);
        out.set("location.y", y);
        out.set("location.z", z);
        out.set("location.yaw", (double) yaw);
        out.set("location.pitch", (double) pitch);
        out.set("game-mode", gameMode.name());
        out.set("health", health);
        out.set("food", food);
        out.set("saturation", (double) saturation);
        out.set("exhaustion", (double) exhaustion);
        out.set("exp.level", level);
        out.set("exp.progress", (double) exp);
        out.set("exp.total", totalExperience);
        List<Map<String, Object>> serializedEffects = new ArrayList<>();
        for (PotionEffect effect : effects) {
            serializedEffects.add(Map.of(
                    "type", effect.getType().getKey().asString(),
                    "duration", effect.getDuration(),
                    "amplifier", effect.getAmplifier(),
                    "ambient", effect.isAmbient(),
                    "particles", effect.hasParticles(),
                    "icon", effect.hasIcon()));
        }
        out.set("effects", serializedEffects);
        out.set("fire-ticks", fireTicks);
        out.set("freeze-ticks", freezeTicks);
        out.set("fall-distance", (double) fallDistance);
        out.set("air", remainingAir);
        out.set("allow-flight", allowFlight);
        out.set("flying", flying);
        out.set("walk-speed", (double) walkSpeed);
        out.set("fly-speed", (double) flySpeed);
        out.set("glowing", glowing);
        out.set("invisible", invisible);
        out.set("inventory", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(inventory)));
        if (extras.length > 0) {
            out.set("extras", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(extras)));
        }
        out.set("absorption", absorption);
    }

    /**
     * Reads a snapshot written by {@link #save}.
     *
     * @throws IllegalArgumentException if the data is unreadable (the caller should keep the file for manual recovery)
     */
    public static PlayerSnapshot load(ConfigurationSection in) {
        Builder b = new Builder();
        String player = in.getString("player");
        if (player == null) {
            throw new IllegalArgumentException("missing player");
        }
        b.playerId = UUID.fromString(player);
        b.playerName = in.getString("name", player);
        b.gameId = in.getString("game", "");
        b.createdAt = in.getLong("created");
        b.worldName = in.getString("location.world");
        String worldUid = in.getString("location.world-uid");
        b.worldId = worldUid == null ? null : UUID.fromString(worldUid);
        b.x = in.getDouble("location.x");
        b.y = in.getDouble("location.y");
        b.z = in.getDouble("location.z");
        b.yaw = (float) in.getDouble("location.yaw");
        b.pitch = (float) in.getDouble("location.pitch");
        b.gameMode = GameMode.valueOf(in.getString("game-mode", GameMode.SURVIVAL.name()));
        b.health = in.getDouble("health", 20);
        b.food = in.getInt("food", 20);
        b.saturation = (float) in.getDouble("saturation", 5);
        b.exhaustion = (float) in.getDouble("exhaustion");
        b.level = in.getInt("exp.level");
        b.exp = (float) in.getDouble("exp.progress");
        b.totalExperience = in.getInt("exp.total");
        b.effects = new ArrayList<>();
        for (Map<?, ?> raw : in.getMapList("effects")) {
            PotionEffect effect = readEffect(raw);
            if (effect != null) {
                b.effects.add(effect);
            }
        }
        b.fireTicks = in.getInt("fire-ticks");
        b.freezeTicks = in.getInt("freeze-ticks");
        b.fallDistance = (float) in.getDouble("fall-distance");
        b.remainingAir = in.getInt("air", 300);
        b.allowFlight = in.getBoolean("allow-flight");
        b.flying = in.getBoolean("flying");
        b.walkSpeed = (float) in.getDouble("walk-speed", 0.2);
        b.flySpeed = (float) in.getDouble("fly-speed", 0.1);
        b.glowing = in.getBoolean("glowing");
        b.invisible = in.getBoolean("invisible");
        String items = in.getString("inventory");
        if (items == null) {
            throw new IllegalArgumentException("missing inventory");
        }
        b.inventory = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(items));
        String extras = in.getString("extras");
        b.extras = extras == null ? new ItemStack[0] : ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(extras));
        b.absorption = in.getDouble("absorption");
        b.scoreboard = null;
        return new PlayerSnapshot(b);
    }

    private static @Nullable PotionEffect readEffect(Map<?, ?> raw) {
        Object typeKey = raw.get("type");
        NamespacedKey key = typeKey == null ? null : NamespacedKey.fromString(typeKey.toString());
        PotionEffectType type = key == null ? null : Registry.MOB_EFFECT.get(key);
        if (type == null) {
            return null;
        }
        return new PotionEffect(type, intValue(raw.get("duration")), intValue(raw.get("amplifier")),
                Boolean.TRUE.equals(raw.get("ambient")), !Boolean.FALSE.equals(raw.get("particles")),
                !Boolean.FALSE.equals(raw.get("icon")));
    }

    private static int intValue(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static ItemStack[] cloneItems(@Nullable ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? null : items[i].clone();
        }
        return copy;
    }

    private static final class Builder {
        UUID playerId;
        String playerName;
        String gameId;
        long createdAt;
        @Nullable String worldName;
        @Nullable UUID worldId;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        GameMode gameMode = GameMode.SURVIVAL;
        double health = 20;
        int food = 20;
        float saturation = 5;
        float exhaustion;
        int level;
        float exp;
        int totalExperience;
        List<PotionEffect> effects = new ArrayList<>();
        int fireTicks;
        int freezeTicks;
        float fallDistance;
        int remainingAir = 300;
        boolean allowFlight;
        boolean flying;
        float walkSpeed = 0.2f;
        float flySpeed = 0.1f;
        boolean glowing;
        boolean invisible;
        ItemStack[] inventory = new ItemStack[0];
        ItemStack[] extras = new ItemStack[0];
        double absorption;
        @Nullable Scoreboard scoreboard;
    }
}
