package io.github.sx2xoox.mcpl.core.pack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Sends the MCPL resource pack ({@code resourcepack/MCPL-pack.zip} inside the plugin jar, built from
 * {@code src/main/resourcepack-src} by {@code ./gradlew resourcePack}) to joining players when
 * {@code resource-pack.enabled}, serving it from a built-in HTTP server or an external URL, and remembers per player
 * whether it loaded ({@link PackAudience}). The pack is offered, not required (unless {@code resource-pack.required}):
 * declining only means the vanilla fallbacks.
 *
 * <p>The pack always has the same id, so a changed pack (new hash or URL after {@code /게임 리로드} or an update)
 * replaces the old one on every client instead of stacking, and is resent to everyone online.
 */
public final class ResourcePackService implements Listener, PackAudience {

    /** Where the pack is inside the plugin jar. */
    public static final String BUNDLED = "resourcepack/MCPL-pack.zip";

    /** The one id of the MCPL pack on clients (stable, so a newer pack replaces the older one). */
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("mcpl-pack".getBytes(StandardCharsets.UTF_8));

    /** What a player's client did with the pack. */
    public enum Status {
        /** Not sent (disabled, or not yet). */
        NONE,
        /** Sent; downloading or loading. */
        PENDING,
        LOADED,
        DECLINED,
        /** The download or loading failed (bad URL, unreachable host, wrong hash, ...). */
        FAILED;

        /** The status a client report means. */
        public static Status of(PlayerResourcePackStatusEvent.Status reported) {
            return switch (reported) {
                case SUCCESSFULLY_LOADED -> LOADED;
                case DECLINED -> DECLINED;
                case ACCEPTED, DOWNLOADED -> PENDING;
                case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> FAILED;
            };
        }
    }

    private final JavaPlugin plugin;
    private final Supplier<ResourcePackSettings> settings;
    private final Messages messages;
    private final Map<UUID, Status> statuses = new HashMap<>();
    private final List<Consumer<Player>> listeners = new CopyOnWriteArrayList<>();
    private byte @Nullable [] zip;
    private String sha1 = "";
    private @Nullable PackHttpServer server;
    private @Nullable ResourcePackInfo info;
    private ResourcePackSettings active = ResourcePackSettings.DISABLED;
    private boolean registered;

    public ResourcePackService(JavaPlugin plugin, Supplier<ResourcePackSettings> settings, Messages messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
    }

    /** Loads the bundled pack, copies it to the data folder (owners may host it themselves), applies the settings. */
    public void enable() {
        try (InputStream in = plugin.getResource(BUNDLED)) {
            if (in == null) {
                plugin.getLogger().warning("The plugin jar has no " + BUNDLED + "; the resource pack is unavailable.");
            } else {
                zip = in.readAllBytes();
                sha1 = sha1(zip);
                File copy = new File(plugin.getDataFolder(), "MCPL-pack.zip");
                if (!copy.isFile() || !sha1(Files.readAllBytes(copy.toPath())).equals(sha1)) {
                    Files.createDirectories(copy.toPath().getParent());
                    Files.write(copy.toPath(), zip);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read the bundled resource pack", e);
        }
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
        apply();
    }

    /**
     * {@code /게임 리로드}: applies changed settings. A changed pack (URL or hash) is resent to everyone; otherwise only
     * players who were never sent it get it.
     */
    public void reload() {
        apply();
    }

    public void disable() {
        stopServer();
        if (registered) {
            HandlerList.unregisterAll(this);
            registered = false;
        }
        statuses.clear();
        listeners.clear();
        info = null;
    }

    @Override
    public boolean hasPack(Player player) {
        return status(player.getUniqueId()) == Status.LOADED;
    }

    @Override
    public Subscription subscribe(Consumer<Player> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public Status status(UUID player) {
        return statuses.getOrDefault(player, Status.NONE);
    }

    /** SHA-1 of the bundled pack (hex), or empty without one. */
    public String sha1() {
        return sha1;
    }

    private void apply() {
        ResourcePackSettings wanted = settings.get();
        ResourcePackInfo previous = info;
        boolean serverMatches = server != null && wanted.enabled() && wanted.selfHosted()
                && active.hostPort() == wanted.hostPort();
        if (!serverMatches) {
            stopServer();
        }
        active = wanted;
        info = makeInfo(wanted);
        if (info == null) {
            if (previous != null) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.removeResourcePacks(PACK_ID);
                }
            }
            resetAll();
            return;
        }
        boolean changed = previous == null || !previous.uri().equals(info.uri())
                || !Objects.equals(previous.hash(), info.hash());
        if (changed) {
            resetAll();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (status(player.getUniqueId()) == Status.NONE) {
                send(player);
            }
        }
    }

    private @Nullable ResourcePackInfo makeInfo(ResourcePackSettings wanted) {
        if (!wanted.enabled() || zip == null) {
            return null;
        }
        String path = "MCPL-pack-" + sha1.substring(0, 12) + ".zip"; // a new pack is a new URL: no stale caches
        if (wanted.selfHosted() && server == null) {
            try {
                server = PackHttpServer.start(wanted.hostPort(), path, zip);
                plugin.getLogger().info("Serving the resource pack on port " + wanted.hostPort());
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not start the resource pack server on port "
                        + wanted.hostPort() + " (resource-pack.host-port); the pack is not sent", e);
                return null;
            }
        }
        String url = wanted.downloadUrl(path, Bukkit.getIp());
        if (url == null) {
            plugin.getLogger().warning("resource-pack.public-address is empty (and server-ip is not set): players"
                    + " cannot reach the built-in pack server, so the pack is not sent.");
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("resource-pack.url is not a valid URL: " + url);
            return null;
        }
        String hash = wanted.hashToSend(sha1);
        return ResourcePackInfo.resourcePackInfo(PACK_ID, uri, hash == null ? "" : hash);
    }

    /** Everyone back to {@link Status#NONE} (and told so, so visuals fall back until the new pack loads). */
    private void resetAll() {
        if (statuses.isEmpty()) {
            return;
        }
        statuses.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            notifyListeners(player);
        }
    }

    private void send(Player player) {
        ResourcePackInfo pack = info;
        if (pack == null) {
            return;
        }
        statuses.put(player.getUniqueId(), Status.PENDING);
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(pack)
                .required(active.required())
                .replace(false)
                .prompt(messages.get("resource-pack.prompt"))
                .build());
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private void notifyListeners(Player player) {
        for (Consumer<Player> listener : listeners) {
            try {
                listener.accept(player);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "A resource pack status listener failed", e);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        statuses.remove(player.getUniqueId());
        if (info != null) {
            send(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        statuses.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (info == null || !PACK_ID.equals(event.getID())) {
            return;
        }
        Player player = event.getPlayer();
        Status status = Status.of(event.getStatus());
        Status before = statuses.put(player.getUniqueId(), status);
        if (status == Status.FAILED) {
            plugin.getLogger().warning(player.getName() + " could not load the resource pack (" + event.getStatus()
                    + "); they see the vanilla look instead. Check that resource-pack.public-address / url is"
                    + " reachable from outside.");
        }
        if (before != status) {
            notifyListeners(player);
        }
    }

    static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
