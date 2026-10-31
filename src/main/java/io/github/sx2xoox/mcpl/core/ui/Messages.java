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

    /** (Re)loads the file, writing the bundled default copy to the data folder on first run. */
    public void load() {
        File file = new File(plugin.getDataFolder(), fileName);
        try (InputStream stream = plugin.getResource(fileName)) {
            if (stream != null) {
                try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    defaults = YamlConfiguration.loadConfiguration(reader);
                }
                if (!file.exists()) {
                    plugin.saveResource(fileName, false);
                }
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
