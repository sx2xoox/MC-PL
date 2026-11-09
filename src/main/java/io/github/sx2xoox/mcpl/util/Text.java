package io.github.sx2xoox.mcpl.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Small helpers for MiniMessage formatted text. All user-facing strings in this plugin are MiniMessage.
 */
public final class Text {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private Text() {
    }

    /** Parses a MiniMessage string such as {@code "<red>화염구</red> 발사!"}. */
    public static Component mm(String miniMessage, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(miniMessage, resolvers);
    }

    public static MiniMessage miniMessage() {
        return MINI_MESSAGE;
    }
}
