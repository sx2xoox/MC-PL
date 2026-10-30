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
import java.util.Map;
import java.util.UUID;
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
 * Sends the MCPL resource pack ({@code resourcepack/MCPL-pack.zip} inside the plugin jar, built by
 * {@code ./gradlew resourcePack}) to joining players when {@code resource-pack.enabled}, serving it from a built-in
 * HTTP server or an external URL, and remembers per player whether it loaded. Modes ask {@link #hasPack(Player)} to
 * pick a screen that needs the pack (the augment card screen) or its vanilla fallback. The pack is offered, never
 * required: declining only means the fallback screens.
 */
public final class ResourcePackService implements Listener {

    /** Where the pack is inside the plugin jar. */
    public static final String BUNDLED = "resourcepack/MCPL-pack.zip";

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
                return;
            }
            zip = in.readAllBytes();
            sha1 = sha1(zip);
            File copy = new File(plugin.getDataFolder(), "MCPL-pack.zip");
            if (!copy.isFile() || !sha1(Files.readAllBytes(copy.toPath())).equals(sha1)) {
                Files.write(copy.toPath(), zip);
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

    /** {@code /게임 리로드}: applies changed settings and sends the pack to players who do not have it yet. */
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
        info = null;
    }

    /** The player's client loaded the current pack. */
    public boolean hasPack(Player player) {
        return status(player.getUniqueId()) == Status.LOADED;
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
        info = null;
        if (!wanted.enabled() || zip == null) {
            statuses.clear();
            return;
        }
        String path = "MCPL-pack-" + sha1.substring(0, 12) + ".zip";
        if (wanted.selfHosted() && server == null) {
            try {
                server = PackHttpServer.start(wanted.hostPort(), path, zip);
                plugin.getLogger().info("Serving the resource pack on port " + wanted.hostPort());
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not start the resource pack server on port "
                        + wanted.hostPort() + " (resource-pack.host-port); the pack is not sent", e);
                return;
            }
        }
        String url = wanted.downloadUrl(path, Bukkit.getIp());
        if (url == null) {
            plugin.getLogger().warning("resource-pack.public-address is empty (and server-ip is not set): players"
                    + " cannot reach the built-in pack server, so the pack is not sent.");
            return;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("resource-pack.url is not a valid URL: " + url);
            return;
        }
        String hash = wanted.hashToSend(sha1);
        UUID id = UUID.nameUUIDFromBytes(("mcpl-pack:" + url + ":" + hash).getBytes(StandardCharsets.UTF_8));
        info = ResourcePackInfo.resourcePackInfo(id, uri, hash == null ? "" : hash);
        if (previous == null || !previous.id().equals(id)) {
            statuses.clear();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (status(player.getUniqueId()) == Status.NONE) {
                send(player);
            }
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
                .required(false)
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
        ResourcePackInfo pack = info;
        if (pack == null || !pack.id().equals(event.getID())) {
            return;
        }
        Status status = Status.of(event.getStatus());
        statuses.put(event.getPlayer().getUniqueId(), status);
        if (status == Status.FAILED) {
            plugin.getLogger().warning(event.getPlayer().getName() + " could not load the resource pack ("
                    + event.getStatus() + "); they see the dialog instead. Check resource-pack.* in config.yml.");
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
