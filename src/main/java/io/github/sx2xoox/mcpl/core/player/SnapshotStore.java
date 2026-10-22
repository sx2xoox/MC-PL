package io.github.sx2xoox.mcpl.core.player;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Pending {@link PlayerSnapshot}s, persisted to {@code plugins/MCPL/snapshots/<uuid>.yml} as soon as they are taken, so
 * a crash, a reload or a player quitting mid-match never loses anything: the snapshot is restored on the next join
 * (or on enable for players already online) when that player is not part of a running match.
 *
 * <p>A snapshot is never overwritten: {@link #capture} returns the existing one if the player already has a pending
 * snapshot, so the state from <em>before</em> the first match always wins.
 */
public final class SnapshotStore {

    /** When to retry a failed teleport home, in ticks after the restore. */
    private static final long[] TELEPORT_RETRY_TICKS = {20, 100};

    private final Plugin plugin;
    private final Path directory;
    private final String namespace;
    private final Map<UUID, PlayerSnapshot> loaded = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();

    public SnapshotStore(Plugin plugin, File directory) {
        this.plugin = plugin;
        this.directory = directory.toPath();
        this.namespace = new NamespacedKey(plugin, "snapshot").getNamespace();
    }

    /** Scans the snapshot folder. Call once on enable before restoring anyone. */
    public void init() {
        pending.clear();
        loaded.clear();
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                files.forEach(file -> {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".yml")) {
                        return;
                    }
                    try {
                        pending.add(UUID.fromString(name.substring(0, name.length() - 4)));
                    } catch (IllegalArgumentException ignored) {
                        // not a snapshot file
                    }
                });
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read snapshot folder " + directory, e);
        }
        if (!pending.isEmpty()) {
            plugin.getLogger().info(pending.size() + " pending player snapshot(s) will be restored when those players join.");
        }
    }

    /** Players with a pending snapshot (online or not). */
    public Set<UUID> pending() {
        return Collections.unmodifiableSet(pending);
    }

    public boolean has(UUID playerId) {
        return pending.contains(playerId);
    }

    /** The pending snapshot, loaded from disk if needed; null if none or unreadable (the file is kept aside). */
    public @Nullable PlayerSnapshot get(UUID playerId) {
        if (!pending.contains(playerId)) {
            return null;
        }
        PlayerSnapshot snapshot = loaded.get(playerId);
        if (snapshot != null) {
            return snapshot;
        }
        Path file = file(playerId);
        if (!Files.exists(file)) {
            pending.remove(playerId);
            return null;
        }
        try {
            snapshot = PlayerSnapshot.load(YamlConfiguration.loadConfiguration(file.toFile()));
            loaded.put(playerId, snapshot);
            return snapshot;
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Snapshot of " + playerId + " is unreadable; moved aside for manual recovery", e);
            quarantine(playerId);
            return null;
        }
    }

    /**
     * Captures and persists the player's state, unless a snapshot is already pending (then that one is returned and
     * nothing is overwritten). The cursor stack and crafting-grid/menu inputs are taken into the snapshot and the open
     * menu is closed first, so a later reset cannot wipe items that were not in the inventory.
     *
     * @throws SnapshotWriteException if the new snapshot could not be written to disk; nothing is kept and the player
     *                                is left as they were (loose items go back into their inventory)
     */
    public PlayerSnapshot capture(Player player, String gameId) {
        UUID id = player.getUniqueId();
        PlayerSnapshot existing = get(id);
        if (existing != null) {
            return existing;
        }
        List<ItemStack> extras = PlayerSnapshot.takeLooseItems(player);
        player.closeInventory();
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player, gameId, extras);
        try {
            write(snapshot);
        } catch (IOException e) {
            PlayerSnapshot.giveBack(player, extras);
            plugin.getLogger().log(Level.SEVERE, "Could not save the snapshot of " + player.getName()
                    + " to disk; they are left out of the match and not reset", e);
            throw new SnapshotWriteException("Could not write the snapshot of " + player.getName(), e);
        }
        loaded.put(id, snapshot);
        pending.add(id);
        return snapshot;
    }

    /**
     * Restores and deletes the pending snapshot of an online player. The player data is saved before the snapshot file
     * is deleted, so a crash right after cannot bring back the match state without a snapshot to undo it. If the
     * teleport home fails (cancelled by another plugin, world not loaded), it is logged and retried a few times; the
     * rest of the state is not applied again.
     *
     * @return true if a snapshot was restored; false if there was none, the player is dead (retry after respawn) or
     * restoring failed (the snapshot is kept)
     */
    public boolean restore(Player player) {
        UUID id = player.getUniqueId();
        PlayerSnapshot snapshot = get(id);
        if (snapshot == null || player.isDead() || !player.isOnline()) {
            return false;
        }
        boolean home;
        try {
            home = snapshot.restore(player, namespace);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not restore " + player.getName() + "; the snapshot is kept", e);
            return false;
        }
        if (!home) {
            plugin.getLogger().warning("Could not send " + player.getName() + " back to " + snapshot.describeLocation()
                    + (snapshot.savedLocation() == null ? " (world not loaded)" : " (teleport cancelled)")
                    + "; their items and stats were restored. Retrying the teleport shortly.");
            retryTeleport(id, snapshot, 0);
        }
        try {
            player.saveData();
        } catch (RuntimeException e) {
            // Paper logs its own I/O errors and never throws here; only a server without the method gets here. The data
            // is still saved on quit or autosave, so this is not worth a warning.
            plugin.getLogger().info("Could not save the player data of " + player.getName() + " right after restoring ("
                    + e + "); it is saved on quit or autosave.");
        }
        discard(id);
        return true;
    }

    private void retryTeleport(UUID playerId, PlayerSnapshot snapshot, int attempt) {
        if (attempt >= TELEPORT_RETRY_TICKS.length || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || pending.contains(playerId)) {
                // offline, or already captured by a new match: leave them where they are
                plugin.getLogger().warning("Gave up sending " + snapshot.playerName() + " back to "
                        + snapshot.describeLocation());
                return;
            }
            if (!online.isDead() && snapshot.teleportBack(online)) {
                plugin.getLogger().info("Sent " + online.getName() + " back to " + snapshot.describeLocation());
                return;
            }
            if (attempt + 1 >= TELEPORT_RETRY_TICKS.length) {
                plugin.getLogger().warning("Gave up sending " + online.getName() + " back to "
                        + snapshot.describeLocation());
                return;
            }
            retryTeleport(playerId, snapshot, attempt + 1);
        }, TELEPORT_RETRY_TICKS[attempt] - (attempt == 0 ? 0 : TELEPORT_RETRY_TICKS[attempt - 1]));
    }

    /** Drops a pending snapshot without restoring it. */
    public void discard(UUID playerId) {
        loaded.remove(playerId);
        pending.remove(playerId);
        try {
            Files.deleteIfExists(file(playerId));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not delete snapshot file of " + playerId, e);
        }
    }

    /** The namespace whose attribute modifiers are stripped on restore. */
    public String namespace() {
        return namespace;
    }

    private void write(PlayerSnapshot snapshot) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        snapshot.save(yaml);
        Path target = file(snapshot.playerId());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(directory);
            yaml.save(temp.toFile());
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
    }

    private void quarantine(UUID playerId) {
        pending.remove(playerId);
        loaded.remove(playerId);
        Path file = file(playerId);
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not move broken snapshot " + file, e);
        }
    }

    private Path file(UUID playerId) {
        return directory.resolve(playerId + ".yml");
    }
}
