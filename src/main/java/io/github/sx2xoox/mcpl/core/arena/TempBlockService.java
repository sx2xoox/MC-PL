package io.github.sx2xoox.mcpl.core.arena;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
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
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Temporary blocks: {@link #place} replaces a block and remembers the original, which comes back after the given
 * duration, when the match ends ({@link #restoreAll()}), or when the plugin is disabled. Pending restores are written
 * to {@code tempblocks.yml} (debounced) and restored on the next enable, so even a crash leaves no trace.
 *
 * <p>Only air and replaceable blocks (grass, flowers, snow layers, fluids, ...) without a block entity can be
 * replaced. While temporary, a block cannot be broken, exploded, burned, melted, decayed, flowed into, moved by
 * pistons, toggled by players or changed by entities.
 *
 * <p>Blocks can also be <b>removed</b> temporarily ({@link #remove}, the terrain damage of abilities): they become air
 * without physics, so nothing next to them pops off or falls, and come back exactly as they were (again without
 * physics, so their neighbours keep their shapes) with {@link #restoreRemovals()} when the match closes. A removed
 * spot can carry a temporary block meanwhile; that one is restored first. Removals are written to the same file and
 * restored after a crash too. While removed, nothing flows, forms, grows or spreads into the spot, and the ground
 * exposed under it does not turn into grass.
 *
 * <p>While a world has removed blocks, its temporary blocks come back <b>without physics</b> as well (they were placed
 * without it): a shape update next to a crater would reshape the blocks around it (a stair turning straight, a fence or
 * wall losing its connection or post, leaves forgetting their log) and pop anything left without support, and the
 * removed blocks themselves come back without physics, so nothing would ever reshape them back.
 */
public final class TempBlockService implements Listener {

    private static final int FLUSH_DELAY_TICKS = 20;
    /** Living entities are lifted out of restored blocks by at most this many blocks. */
    private static final int MAX_RESCUE_LIFT = 16;

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

    /** A removed block: what it was, and its line in the file (serialized once, files can hold thousands). */
    private record Removal(BlockData original, String line) {
    }

    private final Plugin plugin;
    private final File file;
    private final Map<BlockKey, Entry> entries = new LinkedHashMap<>();
    private final PriorityQueue<Entry> expiring = new PriorityQueue<>(Comparator.comparingLong(e -> e.expireTick));
    /** Removed blocks in removal order (a group's supporting block before the blocks attached to it). */
    private final Map<BlockKey, Removal> removed = new LinkedHashMap<>();
    /** Persisted restores whose world is not loaded yet: world UID → "x;y;z;data" lines. */
    private final Map<UUID, List<String>> orphans = new LinkedHashMap<>();
    /** Like {@link #orphans}, for removed blocks (restored after them, without physics). */
    private final Map<UUID, List<String>> orphanRemovals = new LinkedHashMap<>();
    /** Worlds with removed blocks right now (their temporary blocks are restored without physics). */
    private final Set<UUID> scarred = new HashSet<>();
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
     * restores both together, lower half first, so the plant never ends up split. A {@link #remove removed} spot (air)
     * can be used too; it is empty again when the temporary block goes.
     *
     * @param durationTicks restore after this many ticks; {@code <= 0} keeps it until {@link #restorePlacements()}
     *                      (match end)
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

    /** True if the block is currently temporary (placed; see {@link #isRemoved} for removed blocks). */
    public boolean isTemporary(Block block) {
        return !entries.isEmpty() && entries.containsKey(BlockKey.of(block));
    }

    /**
     * Temporarily removes a group of blocks: each one becomes air without physics and comes back with
     * {@link #restoreRemovals()}. List a block before the blocks attached to it (they are restored in this order).
     * All or nothing: refused (false, nothing changed) if any block is air, already removed or temporary, has a block
     * entity, is outside the world height or is listed twice.
     */
    public boolean remove(List<Block> group) {
        if (group.isEmpty()) {
            return false;
        }
        Set<BlockKey> keys = new HashSet<>();
        for (Block block : group) {
            BlockKey key = BlockKey.of(block);
            if (outsideHeight(block) || !keys.add(key) || removed.containsKey(key) || entries.containsKey(key)
                    || block.isEmpty() || block.getState(false) instanceof TileState) {
                return false;
            }
        }
        BlockData air = Material.AIR.createBlockData();
        for (Block block : group) {
            BlockData original = block.getBlockData();
            BlockKey key = BlockKey.of(block);
            removed.put(key, new Removal(original, key.serialize() + ";" + original.getAsString()));
        }
        scarred.add(group.getFirst().getWorld().getUID());
        // Attached blocks first, so not even a client sees one hang in the air for a moment.
        for (int i = group.size() - 1; i >= 0; i--) {
            group.get(i).setBlockData(air, false);
        }
        scheduleFlush();
        return true;
    }

    /** True if the block is removed for now (see {@link #remove}). */
    public boolean isRemoved(Block block) {
        return !removed.isEmpty() && removed.containsKey(BlockKey.of(block));
    }

    /**
     * What the block will be once everything is restored: the original of a removed or temporary block, else its
     * current data.
     */
    public BlockData originalData(Block block) {
        if (!removed.isEmpty() || !entries.isEmpty()) {
            BlockKey key = BlockKey.of(block);
            Removal removal = removed.get(key);
            if (removal != null) {
                return removal.original();
            }
            Entry entry = entries.get(key);
            if (entry != null) {
                return entry.original;
            }
        }
        return block.getBlockData();
    }

    /** Number of removed blocks right now. */
    public int removedCount() {
        return removed.size();
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

    /**
     * Restores everything: the temporary blocks ({@link #restorePlacements()}), then the removed blocks
     * ({@link #restoreRemovals()}). Returns how many blocks were restored.
     */
    public int restoreAll() {
        return restorePlacements() + restoreRemovals();
    }

    /**
     * Restores every temporary block (newest first), leaving removed blocks removed. Returns how many were restored.
     */
    public int restorePlacements() {
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

    /**
     * Puts every removed block back at once, in removal order and without physics, so they come back exactly as they
     * were and nothing around them changes. Living entities that end up inside a restored block (a mob in a crater)
     * are lifted onto the first free spot above it. Returns how many blocks were restored.
     */
    public int restoreRemovals() {
        if (removed.isEmpty()) {
            return 0;
        }
        List<Map.Entry<BlockKey, Removal>> all = new ArrayList<>(removed.entrySet());
        if (!entries.isEmpty()) {
            // A temporary block standing in a removed spot goes first (it would bring back the air it replaced).
            for (Map.Entry<BlockKey, Removal> entry : all) {
                Entry placed = entries.get(entry.getKey());
                if (placed != null) {
                    release(placed);
                }
            }
            updatePhysicsGuard();
        }
        removed.clear();
        scarred.clear();
        Map<UUID, Set<BlockKey>> solids = new HashMap<>();
        for (Map.Entry<BlockKey, Removal> entry : all) {
            BlockKey key = entry.getKey();
            BlockData original = entry.getValue().original();
            World world = Bukkit.getWorld(key.world());
            if (world == null) {
                orphanRemovals.computeIfAbsent(key.world(), id -> new ArrayList<>())
                        .add(key.x() + ";" + key.y() + ";" + key.z() + ";" + original.getAsString());
                continue;
            }
            try {
                world.getBlockAt(key.x(), key.y(), key.z()).setBlockData(original, false);
                if (original.getMaterial().isSolid()) {
                    solids.computeIfAbsent(key.world(), id -> new HashSet<>()).add(key);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not restore removed block at " + key, e);
            }
        }
        solids.forEach(this::rescueEntities);
        scheduleFlush();
        return all.size();
    }

    /** Lifts living entities whose body overlaps one of the {@code restored} solid blocks onto free space above. */
    private void rescueEntities(UUID worldId, Set<BlockKey> restored) {
        World world = Bukkit.getWorld(worldId);
        if (world == null || restored.isEmpty()) {
            return;
        }
        for (LivingEntity entity : world.getLivingEntities()) {
            if (!entity.isValid() || entity.isDead()) {
                continue;
            }
            BoundingBox box = entity.getBoundingBox();
            if (!overlapsAny(worldId, box, restored)) {
                continue;
            }
            for (int lift = 1; lift <= MAX_RESCUE_LIFT; lift++) {
                BoundingBox moved = box.clone().shift(0, lift, 0);
                if (!collides(world, moved)) {
                    Location to = entity.getLocation().add(0, lift, 0);
                    entity.setFallDistance(0f);
                    entity.teleport(to);
                    break;
                }
            }
        }
    }

    private static boolean overlapsAny(UUID worldId, BoundingBox box, Set<BlockKey> blocks) {
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX() - 1.0E-6); x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY() - 1.0E-6); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ() - 1.0E-6); z++) {
                    if (blocks.contains(new BlockKey(worldId, x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** True if a solid block of {@code world} overlaps {@code box} (or the box leaves the world's height). */
    private static boolean collides(World world, BoundingBox box) {
        if (box.getMinY() < world.getMinHeight() || box.getMaxY() > world.getMaxHeight()) {
            return true;
        }
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX() - 1.0E-6); x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY() - 1.0E-6); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ() - 1.0E-6); z++) {
                    if (world.getBlockAt(x, y, z).getType().isSolid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Number of temporary (placed) blocks right now; removed blocks are counted by {@link #removedCount()}. */
    public int size() {
        return entries.size();
    }

    // ------------------------------------------------------------------ internals

    /**
     * Puts the original back: with physics (the neighbours fit themselves to it again), except in a world with removed
     * blocks (see the class comment), where nothing may reshape around the craters.
     */
    private void apply(Entry entry) {
        entry.done = true;
        World world = Bukkit.getWorld(entry.key.world());
        if (world == null) {
            orphans.computeIfAbsent(entry.key.world(), id -> new ArrayList<>())
                    .add(entry.key.x() + ";" + entry.key.y() + ";" + entry.key.z() + ";" + entry.original.getAsString());
            return;
        }
        try {
            world.getBlockAt(entry.key.x(), entry.key.y(), entry.key.z())
                    .setBlockData(entry.original, !scarred.contains(entry.key.world()));
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
        if (entries.isEmpty() && orphans.isEmpty() && removed.isEmpty() && orphanRemovals.isEmpty()) {
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
        List<String> removals = new ArrayList<>(removed.size());
        for (Removal removal : removed.values()) {
            removals.add(removal.line());
        }
        orphanRemovals.forEach((world, rest) -> rest.forEach(line -> removals.add(world + ";" + line)));
        try {
            write(lines, removals);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + file, e);
        }
    }

    /**
     * Writes the YAML file by hand (a match can remove thousands of blocks, and this runs every second while it
     * does): to a temporary file first, then moved over the old one, so a crash mid-write never loses it.
     */
    private void write(List<String> placed, List<String> removals) throws IOException {
        StringBuilder out = new StringBuilder(64 + 96 * (placed.size() + removals.size()));
        out.append("# MCPL temporary blocks waiting to be restored. Do not edit.\n");
        appendList(out, "blocks", placed);
        appendList(out, "removed", removals);
        Path target = file.toPath();
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, out, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void appendList(StringBuilder out, String key, List<String> lines) {
        out.append(key).append(':');
        if (lines.isEmpty()) {
            out.append(" []\n");
            return;
        }
        out.append('\n');
        for (String line : lines) {
            out.append("- '").append(line.replace("'", "''")).append("'\n");
        }
    }

    /**
     * Restores blocks left over from a crash (read from disk): temporary blocks first, then removed blocks. Blocks in
     * worlds that are not loaded yet are restored when the world loads. Like during the match, temporary blocks of a
     * world with removed blocks come back without physics.
     */
    public void recover() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> removals = yaml.getStringList("removed");
        Set<UUID> withRemovals = new HashSet<>();
        for (String line : removals) {
            try {
                withRemovals.add(UUID.fromString(line.split(";", 2)[0]));
            } catch (IllegalArgumentException e) {
                // malformed, reported below
            }
        }
        int restored = recoverLines(yaml.getStringList("blocks"), orphans, world -> !withRemovals.contains(world));
        restored += recoverLines(removals, orphanRemovals, world -> false);
        if (restored > 0) {
            plugin.getLogger().info("Restored " + restored + " temporary block(s) left over from an unclean shutdown.");
        }
        flush();
    }

    private int recoverLines(List<String> lines, Map<UUID, List<String>> waiting, Predicate<UUID> physics) {
        int restored = 0;
        for (String line : lines) {
            String[] parts = line.split(";", 5);
            if (parts.length != 5) {
                continue;
            }
            try {
                UUID worldId = UUID.fromString(parts[0]);
                String rest = parts[1] + ";" + parts[2] + ";" + parts[3] + ";" + parts[4];
                World world = Bukkit.getWorld(worldId);
                if (world == null) {
                    waiting.computeIfAbsent(worldId, id -> new ArrayList<>()).add(rest);
                } else if (restoreLine(world, rest, physics.test(worldId))) {
                    restored++;
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping malformed temporary block entry: " + line);
            }
        }
        return restored;
    }

    private boolean restoreLine(World world, String line, boolean physics) {
        String[] parts = line.split(";", 4);
        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            world.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(parts[3]), physics);
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
        List<String> removals = orphanRemovals.remove(event.getWorld().getUID());
        if (lines == null && removals == null) {
            return;
        }
        boolean physics = removals == null || removals.isEmpty();
        for (String line : lines == null ? List.<String>of() : lines) {
            restoreLine(event.getWorld(), line, physics);
        }
        for (String line : removals == null ? List.<String>of() : removals) {
            restoreLine(event.getWorld(), line, false);
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

    /** Also covers {@code EntityBlockFormEvent} (snow golem trails, frost walker). */
    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (isTemporary(event.getBlock()) || changesRemovedSpot(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Grass, mycelium, vines, ... never spread over a temporary block or into a crater. */
    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (isTemporary(event.getBlock()) || changesRemovedSpot(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Crops, cane, bamboo, ... never grow into a removed spot. */
    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (isTemporary(event.getBlock()) || changesRemovedSpot(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * True if a change of {@code block} would alter a crater: the block is removed, or it is the ground exposed right
     * under a removed block (dirt there must not turn into grass, nor get a snow layer).
     */
    private boolean changesRemovedSpot(Block block) {
        return !removed.isEmpty() && (isRemoved(block) || isRemoved(block.getRelative(BlockFace.UP)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (isTemporary(event.getBlock()) || isTemporary(event.getToBlock()) || isRemoved(event.getToBlock())) {
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
