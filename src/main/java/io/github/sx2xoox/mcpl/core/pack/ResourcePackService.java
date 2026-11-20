package io.github.sx2xoox.mcpl.core.pack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
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
 * {@code resource-pack.enabled}, serving it on the game port ({@link SamePortHost}), a separate built-in HTTP server
 * or an external URL, at the address each player connected with, and remembers per player
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
    /** The served variants of the bundled pack by shader preset id ({@code off} = none), see {@link #variant}. */
    private final Map<String, byte[]> variants = new HashMap<>();
    private String sha1 = "";
    private @Nullable PackHttpServer server;
    private int serverPort;
    private @Nullable SamePortHost samePort;
    /** What the built-in hosts serve (read on netty and HTTP server threads). */
    private volatile SamePortHost.@Nullable Served served;
    /** What is offered (null = nothing); the download URL is per player (see {@link #send}). */
    private @Nullable Offer offer;
    private ResourcePackSettings active = ResourcePackSettings.DISABLED;
    /** The "no address" warning was logged since the last apply (once, not on every join). */
    private boolean warnedNoAddress;
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
                variants.clear();
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
        stopHosting();
        served = null;
        if (registered) {
            HandlerList.unregisterAll(this);
            registered = false;
        }
        statuses.clear();
        listeners.clear();
        offer = null;
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

    /** True while the pack is offered to players (enabled, bundled, and served or an external URL set). */
    public boolean offered() {
        return offer != null;
    }

    /** How the pack is served right now (for {@code /게임 리소스팩}): null when it is not offered. */
    public ResourcePackSettings.@Nullable Mode servedMode() {
        Offer pack = offer;
        if (pack == null || !pack.settings().selfHosted()) {
            return null;
        }
        return pack.samePort() ? ResourcePackSettings.Mode.SAME_PORT : ResourcePackSettings.Mode.SEPARATE_PORT;
    }

    /** The current settings (as last applied). */
    public ResourcePackSettings settings() {
        return active;
    }

    /** The URL {@code player} is sent the pack from, or null when it is not offered (or no address is known). */
    public @Nullable String urlFor(Player player) {
        Offer pack = offer;
        return pack == null ? null : downloadUrl(pack, player);
    }

    /**
     * Sends the pack to {@code player} again ({@code /게임 리소스팩 다시받기}), e.g. after they declined it or the
     * download failed; the client replaces the old copy (same pack id).
     *
     * @return false if the pack is not offered
     */
    public boolean resend(Player player) {
        if (offer == null) {
            return false;
        }
        Status before = statuses.remove(player.getUniqueId());
        if (before != null && before != Status.NONE) {
            notifyListeners(player);
        }
        send(player);
        return true;
    }

    /** SHA-1 of the bundled pack (hex), or empty without one. */
    public String sha1() {
        return sha1;
    }

    private void apply() {
        ResourcePackSettings wanted = settings.get();
        Offer previous = offer;
        active = wanted;
        warnedNoAddress = false;
        offer = makeOffer(wanted);
        if (offer == null) {
            if (previous != null) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.removeResourcePacks(PACK_ID);
                }
            }
            resetAll();
            return;
        }
        boolean changed = !offer.equals(previous);
        if (changed) {
            resetAll();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (status(player.getUniqueId()) == Status.NONE) {
                send(player);
            }
        }
    }

    /**
     * The bundled pack as served with {@code preset} ({@code resource-pack.shaders}, null = no shaders): only that
     * preset's shader files ({@link PackBuilder#withShaders}); cached per preset, the bundle never changes.
     */
    private byte[] variant(byte[] pack, @Nullable ShaderPreset preset) {
        String key = preset == null ? "off" : preset.id();
        byte[] built = variants.get(key);
        if (built == null) {
            try {
                built = PackBuilder.withShaders(pack, preset);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not build the resource pack for the shader preset "
                        + key, e);
                built = pack;
            }
            variants.put(key, built);
        }
        return built;
    }

    /** Starts/stops the hosting {@code wanted} asks for; what is offered then, or null if nothing is. */
    private @Nullable Offer makeOffer(ResourcePackSettings wanted) {
        byte[] pack = zip;
        if (!wanted.enabled() || pack == null) {
            stopHosting();
            return null;
        }
        pack = variant(pack, wanted.shaders());
        String packSha1 = sha1(pack);
        String path = "mcpl-pack/" + packSha1 + ".zip"; // a new pack is a new URL: no stale caches
        String hash = Objects.requireNonNullElse(wanted.hashToSend(packSha1), "");
        if (!wanted.selfHosted()) {
            stopHosting();
            try {
                URI.create(wanted.url());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("resource-pack.url is not a valid URL: " + wanted.url());
                return null;
            }
            return new Offer(wanted, false, path, hash);
        }
        served = new SamePortHost.Served("/" + path, pack);
        if (wanted.mode() == ResourcePackSettings.Mode.SAME_PORT && startSamePort()) {
            stopServer();
            return new Offer(wanted, true, path, hash);
        }
        stopSamePort();
        if (server != null && serverPort != wanted.hostPort()) {
            stopServer();
        }
        if (server == null) {
            try {
                server = PackHttpServer.start(wanted.hostPort(), () -> served);
                serverPort = wanted.hostPort();
                plugin.getLogger().info("Serving the resource pack on port " + wanted.hostPort()
                        + " (resource-pack.host-port)");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not start the resource pack server on port "
                        + wanted.hostPort() + " (resource-pack.host-port); the pack is not sent", e);
                return null;
            }
        }
        return new Offer(wanted, false, path, hash);
    }

    /** Serves the pack on the game port; false if this server cannot (the separate port is used then). */
    private boolean startSamePort() {
        if (samePort != null) {
            return true;
        }
        try {
            SamePortHost host = new SamePortHost(() -> served, plugin.getLogger());
            host.install();
            samePort = host;
            plugin.getLogger().info("Serving the resource pack on the game port (" + Bukkit.getPort() + ")");
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            plugin.getLogger().warning("Cannot serve the resource pack on the game port (" + e
                    + "); using resource-pack.host-port instead.");
            return false;
        }
    }

    private void stopSamePort() {
        if (samePort != null) {
            samePort.uninstall();
            samePort = null;
        }
    }

    private void stopHosting() {
        stopServer();
        stopSamePort();
    }

    /** The URL {@code player} downloads the pack from (see {@link ResourcePackSettings#downloadUrl}), or null. */
    static @Nullable String downloadUrl(Offer offer, Player player) {
        InetSocketAddress virtualHost;
        try {
            virtualHost = player.getVirtualHost();
        } catch (RuntimeException e) {
            virtualHost = null; // a server that does not implement it (MockBukkit): server-ip, then nothing
        }
        return offer.settings().downloadUrl(offer.path(), offer.samePort(),
                virtualHost == null ? null : virtualHost.getHostString(),
                virtualHost == null ? -1 : virtualHost.getPort(), Bukkit.getIp(), Bukkit.getPort());
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
        Offer pack = offer;
        if (pack == null) {
            return;
        }
        String url = downloadUrl(pack, player);
        URI uri;
        try {
            uri = url == null ? null : URI.create(url);
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        if (uri == null) {
            if (!warnedNoAddress) {
                warnedNoAddress = true;
                plugin.getLogger().warning("No address to send " + player.getName() + " the resource pack from: set"
                        + " resource-pack.public-address (or server-ip). They see the vanilla look instead.");
            }
            return;
        }
        statuses.put(player.getUniqueId(), Status.PENDING);
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, uri, pack.hash()))
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
        if (offer != null) {
            send(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        statuses.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (offer == null || !PACK_ID.equals(event.getID())) {
            return;
        }
        Player player = event.getPlayer();
        Status status = Status.of(event.getStatus());
        Status before = statuses.put(player.getUniqueId(), status);
        if (status == Status.FAILED) {
            Offer pack = offer;
            plugin.getLogger().warning(player.getName() + " could not load the resource pack (" + event.getStatus()
                    + ", " + (pack == null ? "-" : downloadUrl(pack, player)) + "); they see the vanilla look instead."
                    + " Behind a proxy (Velocity, BungeeCord, TCPShield) use resource-pack.mode: separate-port; if"
                    + " the address players connect with does not reach this server, set resource-pack.public-address.");
        }
        if (before != status) {
            notifyListeners(player);
        }
    }

    /**
     * The pack on offer: where it is served ({@code settings}, on the game port or not, {@code path}) and its hash
     * (empty = no check).
     */
    record Offer(ResourcePackSettings settings, boolean samePort, String path, String hash) {
    }

    static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
