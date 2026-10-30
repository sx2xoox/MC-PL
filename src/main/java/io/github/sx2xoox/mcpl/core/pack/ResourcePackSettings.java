package io.github.sx2xoox.mcpl.core.pack;

import java.util.Locale;

import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * The {@code resource-pack.*} settings of {@code config.yml}: whether players are sent the MCPL resource pack (the
 * card art of the augment pick screen) and where they download it from.
 *
 * @param enabled       send the pack to players when they join
 * @param url           an external download URL (the owner uploaded {@code MCPL-pack.zip}); empty = the built-in server
 * @param hostPort      TCP port of the built-in download server (used when {@code url} is empty)
 * @param publicAddress host name or IP players reach the built-in server at; empty = {@code server-ip}
 * @param sha1          the pack hash sent to clients: {@link #AUTO} = the bundled pack's, empty = none (no check)
 */
public record ResourcePackSettings(boolean enabled, String url, int hostPort, String publicAddress, String sha1) {

    public static final String AUTO = "auto";
    public static final int DEFAULT_PORT = 8164;
    public static final ResourcePackSettings DISABLED = new ResourcePackSettings(false, "", DEFAULT_PORT, "", AUTO);

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
                section.getBoolean("enabled", false),
                section.getString("url", ""),
                port < 1 || port > 65535 ? DEFAULT_PORT : port,
                section.getString("public-address", ""),
                section.getString("sha1", AUTO));
    }

    /** The built-in server serves the pack (no external URL configured). */
    public boolean selfHosted() {
        return url.isEmpty();
    }

    /**
     * The URL clients download the pack from: the external {@link #url}, or the built-in server at {@code path} on
     * {@link #publicAddress} (falling back to {@code serverIp}); null if no address is known.
     */
    public @Nullable String downloadUrl(String path, String serverIp) {
        if (!selfHosted()) {
            return url;
        }
        String host = !publicAddress.isEmpty() ? publicAddress : serverIp.trim();
        if (host.isEmpty() || host.equals("0.0.0.0")) {
            return null;
        }
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]"; // IPv6 literal
        }
        return "http://" + host + ":" + hostPort + "/" + path;
    }

    /** The hash to send: the bundled pack's for {@link #AUTO}, none for empty, else the configured 40-digit SHA-1. */
    public @Nullable String hashToSend(String bundledSha1) {
        if (sha1.equals(AUTO)) {
            return bundledSha1;
        }
        return sha1.matches("[0-9a-f]{40}") ? sha1 : null;
    }
}
