package io.github.sx2xoox.mcpl.core.pack;

import java.util.Locale;

import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * The {@code resource-pack.*} settings of {@code config.yml}: whether players are sent the MCPL resource pack (3D
 * models such as the 공성전 towers) and where they download it from.
 *
 * @param enabled       send the pack to players when they join
 * @param mode          how the plugin serves the pack itself (when {@code url} is empty)
 * @param url           an external download URL (the owner uploaded {@code MCPL-pack.zip}); empty = served by the plugin
 * @param hostPort      TCP port of the built-in download server ({@link Mode#SEPARATE_PORT})
 * @param publicAddress {@code host} or {@code host:port} players download from; empty = the address each player
 *                      connected with (their virtual host), then {@code server-ip}
 * @param sha1          the pack hash sent to clients: {@link #AUTO} = the bundled pack's, empty = none (no check)
 * @param required      clients must accept the pack (declining disconnects them)
 */
public record ResourcePackSettings(boolean enabled, Mode mode, String url, int hostPort, String publicAddress,
                                   String sha1, boolean required) {

    /** Where the plugin serves the pack. */
    public enum Mode {
        /** On the Minecraft port itself ({@link SamePortHost}): nothing to open or configure. */
        SAME_PORT,
        /** A small HTTP server on {@code host-port} ({@link PackHttpServer}): needed behind Velocity/BungeeCord. */
        SEPARATE_PORT;

        static Mode parse(@Nullable String value) {
            return value != null && value.trim().toLowerCase(Locale.ROOT).replace('_', '-').equals("separate-port")
                    ? SEPARATE_PORT : SAME_PORT;
        }
    }

    public static final String AUTO = "auto";
    public static final int DEFAULT_PORT = 8164;
    /** The port a client reports when the address was typed without one (also an SRV record's domain). */
    private static final int MINECRAFT_DEFAULT_PORT = 25565;
    public static final ResourcePackSettings DISABLED =
            new ResourcePackSettings(false, Mode.SAME_PORT, "", DEFAULT_PORT, "", AUTO, false);

    public ResourcePackSettings {
        url = url.trim();
        publicAddress = publicAddress.trim();
        sha1 = sha1.trim().toLowerCase(Locale.ROOT);
    }

    /** Reads {@code resource-pack}; a missing section means disabled. */
    public static ResourcePackSettings read(@Nullable ConfigurationSection section) {
        if (section == null) {
            return DISABLED;
        }
        int port = section.getInt("host-port", DEFAULT_PORT);
        return new ResourcePackSettings(
                section.getBoolean("enabled", true),
                Mode.parse(section.getString("mode")),
                section.getString("url", ""),
                port < 1 || port > 65535 ? DEFAULT_PORT : port,
                section.getString("public-address", ""),
                section.getString("sha1", AUTO),
                section.getBoolean("required", false));
    }

    /** The plugin serves the pack (no external URL configured). */
    public boolean selfHosted() {
        return url.isEmpty();
    }

    /**
     * The URL a client downloads the pack from: the external {@link #url}; else {@code path} on the plugin's server,
     * at {@link #publicAddress}, else at the host the player connected with ({@code connectedHost}/{@code
     * connectedPort}, their virtual host: what reached the game port reaches the pack too), else at {@code serverIp}.
     * The port is the game port in {@code samePort} mode (the one the player used, unless it is the default 25565 an
     * SRV domain reports, then {@code serverPort}), else {@link #hostPort}. Null if no usable address is known.
     *
     * @param samePort the pack is served on the game port right now ({@link Mode#SAME_PORT} and installed)
     */
    public @Nullable String downloadUrl(String path, boolean samePort, @Nullable String connectedHost,
                                        int connectedPort, String serverIp, int serverPort) {
        if (!selfHosted()) {
            return url;
        }
        int port = samePort ? serverPort : hostPort;
        String host = publicAddress;
        if (!host.isEmpty()) {
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon) { // host:port (not an IPv6 literal)
                try {
                    port = Integer.parseInt(host.substring(colon + 1));
                    host = host.substring(0, colon);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        } else {
            host = usableHost(connectedHost);
            if (!host.isEmpty()) {
                if (samePort && connectedPort > 0 && connectedPort != MINECRAFT_DEFAULT_PORT) {
                    port = connectedPort;
                }
            } else {
                host = usableHost(serverIp);
            }
        }
        if (host.isEmpty()) {
            return null;
        }
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]"; // IPv6 literal
        }
        return "http://" + host + ":" + port + "/" + path;
    }

    /** {@code host} without a NUL-separated proxy/mod suffix or a trailing DNS dot; empty if unusable. */
    private static String usableHost(@Nullable String host) {
        if (host == null) {
            return "";
        }
        int nul = host.indexOf('\0');
        String clean = (nul >= 0 ? host.substring(0, nul) : host).trim();
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return clean.isEmpty() || clean.equals("0.0.0.0") || clean.equals("::") ? "" : clean;
    }

    /** The hash to send: the bundled pack's for {@link #AUTO}, none for empty, else the configured 40-digit SHA-1. */
    public @Nullable String hashToSend(String bundledSha1) {
        if (sha1.equals(AUTO)) {
            return bundledSha1;
        }
        return sha1.matches("[0-9a-f]{40}") ? sha1 : null;
    }
}
