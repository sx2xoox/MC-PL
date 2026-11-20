package io.github.sx2xoox.mcpl.core.ui;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Player-facing text loaded from a MiniMessage YAML file in the data folder ({@code messages.yml} for the hub).
 * Keys missing from the server owner's file fall back to the copy bundled in the jar, so new keys added by an update
 * work without editing the file. Reloadable with {@link #load()}.
 *
 * <p>Every message can use {@code <prefix>} and the placeholders passed as {@link TagResolver}s, e.g.
 * <pre>{@code
 * messages.send(player, "match.eliminated", Messages.tag("player", session.name(victim)), Messages.tag("alive", 3));
 * }</pre>
 *
 * <p>A mini-game may keep its own file ({@code new Messages(plugin, "superpower.yml", hub.messages())}); it inherits
 * the hub prefix unless it defines its own {@code prefix} key.
 */
public final class Messages {

    private final JavaPlugin plugin;
    private final String fileName;
    private final @Nullable Messages parent;
    private YamlConfiguration user = new YamlConfiguration();
    private YamlConfiguration defaults = new YamlConfiguration();
    private Component prefix = Component.empty();

    public Messages(JavaPlugin plugin, String fileName) {
        this(plugin, fileName, null);
    }

    /**
     * @param parent messages whose prefix is used when this file defines none (may be null)
     */
    public Messages(JavaPlugin plugin, String fileName, @Nullable Messages parent) {
        this.plugin = plugin;
        this.fileName = fileName;
        this.parent = parent;
    }

    /**
     * (Re)loads the file, writing the bundled default copy to the data folder on first run.
     *
     * <p>Plugin updates change the bundled text, but a file written by an older version would keep showing the old
     * lines forever. So the bundled copy each file was written from is kept in {@code .bundled/}: a line the owner
     * never changed (still equal to that copy) follows the new bundled text, and a file nobody edited is replaced by
     * the new one. A file from before this bookkeeping is backed up to {@code <name>.old} and replaced.
     */
    public void load() {
        File file = new File(plugin.getDataFolder(), fileName);
        File snapshot = new File(new File(plugin.getDataFolder(), ".bundled"), fileName);
        try (InputStream stream = plugin.getResource(fileName)) {
            if (stream != null) {
                byte[] bundled = stream.readAllBytes();
                defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(new java.io.ByteArrayInputStream(bundled), StandardCharsets.UTF_8));
                upgrade(file, snapshot, bundled);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not read bundled " + fileName, e);
        }
        user = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        String rawPrefix = raw("prefix");
        if (rawPrefix != null) {
            prefix = Text.mm(rawPrefix);
        } else if (parent != null) {
            prefix = parent.prefix();
        } else {
            prefix = Component.empty();
        }
    }

    /**
     * Brings the owner's file up to the bundled text (see {@link #load()}).
     */
    private void upgrade(File file, File snapshot, byte[] bundled) throws java.io.IOException {
        if (!file.exists()) {
            write(file, bundled);
        } else if (!snapshot.exists()) {
            if (!java.util.Arrays.equals(java.nio.file.Files.readAllBytes(file.toPath()), bundled)) {
                File backup = new File(file.getParentFile(), fileName + ".old");
                java.nio.file.Files.copy(file.toPath(), backup.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                write(file, bundled);
                plugin.getLogger().info(fileName + " was replaced by this version's text (the old file is kept as "
                        + backup.getName() + ")");
            }
        } else {
            byte[] previous = java.nio.file.Files.readAllBytes(snapshot.toPath());
            if (!java.util.Arrays.equals(previous, bundled)) {
                YamlConfiguration owner = YamlConfiguration.loadConfiguration(file);
                YamlConfiguration before = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(new java.io.ByteArrayInputStream(previous), StandardCharsets.UTF_8));
                YamlConfiguration changed = new YamlConfiguration();
                for (String key : owner.getKeys(true)) {
                    if (!owner.isConfigurationSection(key)
                            && !java.util.Objects.equals(owner.get(key), before.get(key))) {
                        changed.set(key, owner.get(key));
                    }
                }
                if (changed.getKeys(true).isEmpty()) {
                    write(file, bundled);
                } else {
                    YamlConfiguration merged = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(new java.io.ByteArrayInputStream(bundled), StandardCharsets.UTF_8));
                    for (String key : changed.getKeys(true)) {
                        if (!changed.isConfigurationSection(key)) {
                            merged.set(key, changed.get(key));
                        }
                    }
                    merged.save(file);
                    plugin.getLogger().info(fileName + ": updated to this version's text, keeping the lines edited "
                            + "on this server");
                }
            }
        }
        snapshot.getParentFile().mkdirs();
        write(snapshot, bundled);
    }

    private static void write(File file, byte[] content) throws java.io.IOException {
        file.getParentFile().mkdirs();
        java.nio.file.Files.write(file.toPath(), content);
    }

    /** The colored prefix put in front of chat messages. */
    public Component prefix() {
        return parent != null && raw("prefix") == null ? parent.prefix() : prefix;
    }

    /** True if the key exists in the server owner's file or the bundled defaults. */
    public boolean has(String key) {
        return user.contains(key) || defaults.contains(key);
    }

    /** The raw MiniMessage string, or null if the key is unknown. Lists are joined with new lines. */
    public @Nullable String raw(String key) {
        Object value = user.contains(key) ? user.get(key) : defaults.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder();
            for (Object line : list) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(line);
            }
            return out.toString();
        }
        return value.toString();
    }

    /** The message without prefix. Unknown keys render as a red {@code <missing:key>} marker. */
    public Component get(String key, TagResolver... resolvers) {
        String raw = raw(key);
        if (raw == null) {
            return Component.text("<missing:" + key + ">", NamedTextColor.RED);
        }
        return parse(raw, resolvers);
    }

    /** The message with the prefix in front. */
    public Component prefixed(String key, TagResolver... resolvers) {
        return prefix().append(get(key, resolvers));
    }

    /**
     * Every line of a list message (or the single line of a string message) parsed separately, for lore, sidebar
     * or help pages.
     */
    public List<Component> lines(String key, TagResolver... resolvers) {
        Object value = user.contains(key) ? user.get(key) : defaults.get(key);
        List<Component> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object line : list) {
                out.add(parse(String.valueOf(line), resolvers));
            }
        } else if (value != null) {
            out.add(parse(value.toString(), resolvers));
        }
        return out;
    }

    /** Parses any MiniMessage string with {@code <prefix>} available. */
    public Component parse(String miniMessage, TagResolver... resolvers) {
        TagResolver[] all = new TagResolver[resolvers.length + 1];
        System.arraycopy(resolvers, 0, all, 0, resolvers.length);
        all[resolvers.length] = Placeholder.component("prefix", prefix());
        return Text.mm(miniMessage, all);
    }

    /** Sends the prefixed message. */
    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(prefixed(key, resolvers));
    }

    /** Sends the message without prefix (multi-line messages keep their line breaks). */
    public void sendPlain(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(get(key, resolvers));
    }

    /** Sends the message in the action bar. */
    public void actionBar(Audience audience, String key, TagResolver... resolvers) {
        audience.sendActionBar(get(key, resolvers));
    }

    // ------------------------------------------------------------------ placeholder helpers

    /** {@code <name>} replaced by a component (kept as is, not parsed). */
    public static TagResolver tag(String name, ComponentLike value) {
        return Placeholder.component(name, value);
    }

    /**
     * {@code <name>} replaced by plain text ({@code String.valueOf(value)}, not parsed as MiniMessage, so player input
     * is safe). Doubles are formatted with {@link Format#number(double)}.
     */
    public static TagResolver tag(String name, @Nullable Object value) {
        String text = value instanceof Double d ? Format.number(d)
                : value instanceof Float f ? Format.number(f)
                : String.valueOf(value);
        return Placeholder.unparsed(name, text);
    }

    /** {@code <name>} replaced by a MiniMessage snippet that is parsed (only for trusted text). */
    public static TagResolver parsed(String name, String miniMessage) {
        return Placeholder.parsed(name, miniMessage);
    }
}
