package io.github.sx2xoox.mcpl.core.arena;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Temporary blocks: {@link #place} replaces a block and remembers the original, which comes back after the given
 * duration, when the match ends ({@link #restoreAll()}), or when the plugin is disabled. Pending restores are written
 * to {@code tempblocks.yml} (debounced) and restored on the next enable, so even a crash leaves no trace.
 *
 * <p>Only air and replaceable blocks (grass, flowers, snow layers, fluids, ...) without a block entity can be
 * replaced. While temporary, a block cannot be broken, exploded, burned, melted, decayed, flowed into, moved by
 * pistons, toggled by players or changed by entities.
 */
public final class TempBlockService implements Listener {

    private static final int FLUSH_DELAY_TICKS = 20;

    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        String serialize() {
            return world + ";" + x + ";" + y + ";" + z;
        }
    }

    private static final class Entry {
        final BlockKey key;
        final BlockData original;
        final long expireTick;
        /**
         * True for the other half of a double plant that was only emptied (air or water) because its partner was
         * replaced; {@link #place} may still use it for another temporary block.
         */
        final boolean placeholder;
        /** The other half of a double plant (both halves are taken and restored together), or null. */
        @Nullable BlockKey partner;
        boolean done;

        Entry(BlockKey key, BlockData original, long expireTick, boolean placeholder) {
            this.key = key;
            this.original = original;
            this.expireTick = expireTick;
            this.placeholder = placeholder;
        }

        boolean upperHalf() {
            return original instanceof Bisected bisected && bisected.getHalf() == Bisected.Half.TOP;
        }
    }

    private final Plugin plugin;
    private final File file;
    private final Map<BlockKey, Entry> entries = new LinkedHashMap<>();
    private final PriorityQueue<Entry> expiring = new PriorityQueue<>(Comparator.comparingLong(e -> e.expireTick));
    /** Persisted restores whose world is not loaded yet: world UID → "x;y;z;data" lines. */
    private final Map<UUID, List<String>> orphans = new LinkedHashMap<>();
    private final PhysicsGuard physicsGuard = new PhysicsGuard();
    private boolean physicsGuardRegistered;
    private BukkitTask ticker;
    private int flushIn = -1;

    /**
     * Cancels physics updates of temporary blocks (e.g. a temporary snow layer popping off). Registered only while
     * temporary blocks exist: Paper skips firing this very frequent event when nobody listens to it.
     */
    private final class PhysicsGuard implements Listener {
        @EventHandler(ignoreCancelled = true)
        public void onPhysics(BlockPhysicsEvent event) {
            if (isTemporary(event.getBlock())) {
                event.setCancelled(true);
            }
        }
    }

    private void updatePhysicsGuard() {
        boolean needed = ticker != null && !entries.isEmpty();
        if (needed && !physicsGuardRegistered) {
            Bukkit.getPluginManager().registerEvents(physicsGuard, plugin);
            physicsGuardRegistered = true;
        } else if (!needed && physicsGuardRegistered) {
            HandlerList.unregisterAll(physicsGuard);
            physicsGuardRegistered = false;
        }
    }

    public TempBlockService(Plugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    /** Registers the protection listener and the expiry ticker. */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Restores everything, writes the (now empty) state and stops. */
    public void shutdown() {
        restoreAll();
        flush();
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        updatePhysicsGuard();
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------ API

    /**
     * Temporarily replaces {@code block} with {@code data}.
     *
     * <p>Replacing one half of a double plant (tall grass, large fern, tall seagrass) also empties the other half and
     * restores both together, lower half first, so the plant never ends up split.
     *
     * @param durationTicks restore after this many ticks; {@code <= 0} keeps it until {@link #restoreAll()} (match end)
     * @return false if the block is not air/replaceable, has a block entity, is outside the world height or is
     * already temporary
     */
    public boolean place(Block block, BlockData data, long durationTicks) {
        if (outsideHeight(block)) {
            return false;
        }
        BlockKey key = BlockKey.of(block);
        long expire = durationTicks > 0 ? Bukkit.getCurrentTick() + durationTicks : Long.MAX_VALUE;
        Entry existing = entries.get(key);
        if (existing != null) {
            if (!existing.placeholder) {
                return false;
            }
            // the emptied half of a double plant we already hold: it can carry this temporary block as well
            Entry claimed = new Entry(key, existing.original, expire, false);
            claimed.partner = existing.partner;
            existing.done = true;
            track(claimed, durationTicks);
            block.setBlockData(data, false);
            afterChange();
            return true;
        }
        if (!block.isEmpty() && !block.isReplaceable()) {
            return false;
        }
        if (block.getState(false) instanceof TileState) {
            return false;
        }
        BlockData original = block.getBlockData();
        Block other = otherHalf(block, original);
        if (other != null && (outsideHeight(other) || entries.containsKey(BlockKey.of(other)))) {
            return false;
        }
        Entry entry = new Entry(key, original, expire, false);
        track(entry, durationTicks);
        if (other != null) {
            Entry partner = new Entry(BlockKey.of(other), other.getBlockData(), expire, true);
            entry.partner = partner.key;
            partner.partner = key;
            track(partner, durationTicks);
            other.setBlockData(emptied(original), false);
        }
        block.setBlockData(data, false);
        afterChange();
        return true;
    }

    private static boolean outsideHeight(Block block) {
        World world = block.getWorld();
        return block.getY() < world.getMinHeight() || block.getY() >= world.getMaxHeight();
    }

    /** The matching other half if {@code data} is half of a (whole) double plant, else null. */
    private static @Nullable Block otherHalf(Block block, BlockData data) {
        if (!(data instanceof Bisected half)) {
            return null;
        }
        Block other = block.getRelative(half.getHalf() == Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN);
        BlockData otherData = other.getBlockData();
        if (otherData.getMaterial() != data.getMaterial() || !(otherData instanceof Bisected otherHalf)
                || otherHalf.getHalf() == half.getHalf()) {
            return null;
        }
        return other;
    }

    /** What the emptied other half of a double plant holds meanwhile: water under water, else air. */
    private static BlockData emptied(BlockData plant) {
        boolean wet = plant.getMaterial() == Material.TALL_SEAGRASS
                || plant instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
        return (wet ? Material.WATER : Material.AIR).createBlockData();
    }

    private void track(Entry entry, long durationTicks) {
        entries.put(entry.key, entry);
        if (durationTicks > 0) {
            expiring.add(entry);
        }
    }

    private void afterChange() {
        scheduleFlush();
        updatePhysicsGuard();
    }

    /** True if the block is currently temporary. */
    public boolean isTemporary(Block block) {
        return !entries.isEmpty() && entries.containsKey(BlockKey.of(block));
    }

    /** Restores one temporary block early (with the other half of a double plant). False if it was not temporary. */
    public boolean restore(Block block) {
        Entry entry = entries.get(BlockKey.of(block));
        if (entry == null) {
            return false;
        }
        release(entry);
        afterChange();
        return true;
    }

    /** Restores every temporary block (newest first). Returns how many were restored. */
    public int restoreAll() {
        if (entries.isEmpty()) {
            return 0;
        }
        List<Entry> all = new ArrayList<>(entries.values());
        expiring.clear();
        for (int i = all.size() - 1; i >= 0; i--) {
            Entry entry = all.get(i);
            if (entries.get(entry.key) == entry) {
                release(entry); // blocks restored later stay guarded meanwhile
            }
        }
        entries.clear();
        afterChange();
        return all.size();
    }

    /**
     * Unregisters and restores {@code entry}. A double plant comes back whole: the lower half first (the upper one
     * stays registered, so physics cannot touch it), then the upper half, so no shape update sees a split plant.
     */
    private void release(Entry entry) {
        Entry partner = entry.partner == null ? null : entries.get(entry.partner);
        if (partner == null || partner.done) {
            entries.remove(entry.key);
            apply(entry);
            return;
        }
        Entry lower = entry.upperHalf() ? partner : entry;
        Entry upper = lower == entry ? partner : entry;
        entries.remove(lower.key);
        apply(lower);
        entries.remove(upper.key);
        apply(upper);
    }

    /** Number of temporary blocks right now. */
    public int size() {
        return entries.size();
    }

    // ------------------------------------------------------------------ internals

    private void apply(Entry entry) {
        entry.done = true;
        World world = Bukkit.getWorld(entry.key.world());
        if (world == null) {
            orphans.computeIfAbsent(entry.key.world(), id -> new ArrayList<>())
                    .add(entry.key.x() + ";" + entry.key.y() + ";" + entry.key.z() + ";" + entry.original.getAsString());
            return;
        }
        try {
            world.getBlockAt(entry.key.x(), entry.key.y(), entry.key.z()).setBlockData(entry.original, true);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not restore temporary block at " + entry.key, e);
        }
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        boolean changed = false;
        while (!expiring.isEmpty() && expiring.peek().expireTick <= now) {
            Entry entry = expiring.poll();
            if (entry.done || entries.get(entry.key) != entry) {
                continue;
            }
            Entry partner = entry.partner == null ? null : entries.get(entry.partner);
            if (partner != null && !partner.done && partner.expireTick > now) {
                continue; // a double plant comes back whole, when its other half expires too
            }
            release(entry);
            changed = true;
        }
        if (changed) {
            afterChange();
        }
        if (flushIn > 0 && --flushIn == 0) {
            flush();
        }
    }

    private void scheduleFlush() {
        if (flushIn <= 0) {
            flushIn = FLUSH_DELAY_TICKS;
        }
    }

    /** Writes pending restores to disk now (deletes the file when there are none). */
    public void flush() {
        flushIn = -1;
        if (entries.isEmpty() && orphans.isEmpty()) {
            if (file.exists() && !file.delete()) {
                plugin.getLogger().warning("Could not delete " + file);
            }
            return;
        }
        List<String> lines = new ArrayList<>(entries.size());
        Set<BlockKey> written = new HashSet<>();
        for (Entry entry : entries.values()) {
            if (!written.add(entry.key)) {
                continue;
            }
            Entry partner = entry.partner == null ? null : entries.get(entry.partner);
            if (partner != null && written.add(partner.key)) {
                // both halves of a double plant, lower first: recovery restores them in file order
                Entry lower = entry.upperHalf() ? partner : entry;
                Entry upper = lower == entry ? partner : entry;
                lines.add(lower.key.serialize() + ";" + lower.original.getAsString());
                lines.add(upper.key.serialize() + ";" + upper.original.getAsString());
            } else {
                lines.add(entry.key.serialize() + ";" + entry.original.getAsString());
            }
        }
        orphans.forEach((world, rest) -> rest.forEach(line -> lines.add(world + ";" + line)));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("MCPL temporary blocks waiting to be restored. Do not edit."));
        yaml.set("blocks", lines);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + file, e);
        }
    }

    /**
     * Restores blocks left over from a crash (read from disk). Blocks in worlds that are not loaded yet are restored
     * when the world loads.
     */
    public void recover() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int restored = 0;
        for (String line : yaml.getStringList("blocks")) {
            String[] parts = line.split(";", 5);
            if (parts.length != 5) {
                continue;
            }
            try {
                UUID worldId = UUID.fromString(parts[0]);
                String rest = parts[1] + ";" + parts[2] + ";" + parts[3] + ";" + parts[4];
                World world = Bukkit.getWorld(worldId);
                if (world == null) {
                    orphans.computeIfAbsent(worldId, id -> new ArrayList<>()).add(rest);
                } else if (restoreLine(world, rest)) {
                    restored++;
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping malformed temporary block entry: " + line);
            }
        }
        if (restored > 0) {
            plugin.getLogger().info("Restored " + restored + " temporary block(s) left over from an unclean shutdown.");
        }
        flush();
    }

    private boolean restoreLine(World world, String line) {
        String[] parts = line.split(";", 4);
        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            world.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(parts[3]), true);
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not restore temporary block " + line, e);
            return false;
        }
    }

    // ------------------------------------------------------------------ guards

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        List<String> lines = orphans.remove(event.getWorld().getUID());
        if (lines == null) {
            return;
        }
        for (String line : lines) {
            restoreLine(event.getWorld(), line);
        }
        flush();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isTemporary(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!entries.isEmpty()) {
            event.blockList().removeIf(this::isTemporary);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!entries.isEmpty()) {
            event.blockList().removeIf(this::isTemporary);
        }
    }

    /** Also keeps grass, mycelium and nylium under a temporary block from fading to dirt or netherrack. */
    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        Block block = event.getBlock();
        if (isTemporary(block) || isTemporary(block.getRelative(BlockFace.UP))) {
            event.setCancelled(true);
        }
    }

    /** Also covers {@code BlockSpreadEvent} and {@code EntityBlockFormEvent}. */
    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (isTemporary(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (isTemporary(event.getBlock()) || isTemporary(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        if (isTemporary(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isTemporary(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (isTemporary(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (isTemporary(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!entries.isEmpty() && event.getBlocks().stream().anyMatch(this::isTemporary)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!entries.isEmpty() && event.getBlocks().stream().anyMatch(this::isTemporary)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.PHYSICAL) {
            Block clicked = event.getClickedBlock();
            if (clicked != null && isTemporary(clicked)) {
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            }
        }
    }
}
