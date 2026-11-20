package io.github.sx2xoox.mcpl.game.superpower.ui;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Player-facing renderings of abilities (names, difficulty stars, wrapped skill descriptions, item lore and hover
 * cards), built from {@link AbilityInfo} and the effective {@link AbilityParams}. Labels come from
 * {@code superpower.yml}.
 */
public final class AbilityTexts {

    /** Wrap width in "half-width units": Hangul and other wide glyphs count 2, everything else 1. */
    static final int WRAP_WIDTH = 46;

    private final Messages messages;

    public AbilityTexts(Messages messages) {
        this.messages = messages;
    }

    public Messages messages() {
        return messages;
    }

    /** The ability name in its color. */
    public static Component name(AbilityInfo info) {
        return Component.text(info.name(), info.color());
    }

    /** The ability name in its color, bold. */
    public static Component boldName(AbilityInfo info) {
        return name(info).decorate(TextDecoration.BOLD);
    }

    /** Difficulty as stars, e.g. ★★☆. */
    public static Component stars(int difficulty) {
        int filled = Math.max(0, Math.min(3, difficulty));
        return Component.text("★".repeat(filled), NamedTextColor.GOLD)
                .append(Component.text("☆".repeat(3 - filled), NamedTextColor.DARK_GRAY));
    }

    /** A skill name in white with its rendered description as hover text. */
    public Component skill(AbilityInfo.Skill skill, AbilityParams params) {
        return Component.text(skill.name(), NamedTextColor.WHITE)
                .hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(),
                        description(skill, params))));
    }

    /** The rendered description of a skill, wrapped into gray lines. */
    public List<Component> description(AbilityInfo.Skill skill, AbilityParams params) {
        String rendered = AbilityInfo.render(skill.description(), params);
        List<Component> out = new ArrayList<>();
        for (String line : wrap(rendered, WRAP_WIDTH)) {
            out.add(messages.get("text.line", Messages.parsed("line", line)));
        }
        return out;
    }

    /**
     * Item/menu lore: difficulty, then each skill with its wrapped description.
     *
     * @param controls add the control hints (우클릭 / F)
     */
    public List<Component> lore(AbilityInfo info, AbilityParams params, boolean controls) {
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("text.difficulty", Messages.tag("stars", stars(info.difficulty()))));
        lines.add(Component.empty());
        lines.add(messages.get("text.passive", Messages.tag("name", info.passive().name())));
        lines.addAll(description(info.passive(), params));
        lines.add(Component.empty());
        lines.add(messages.get("text.active", Messages.tag("name", info.active().name()),
                Messages.tag("cooldown", AbilityInfo.formatNumber(params.cooldownSeconds()))));
        lines.addAll(description(info.active(), params));
        if (controls) {
            lines.add(messages.get("text.control-active"));
        }
        lines.add(Component.empty());
        lines.add(messages.get("text.ultimate", Messages.tag("name", info.ultimate().name())));
        lines.addAll(description(info.ultimate(), params));
        if (controls) {
            lines.add(messages.get("text.control-ultimate"));
        }
        List<Component> out = new ArrayList<>(lines.size());
        for (Component line : lines) {
            out.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return out;
    }

    /** A multi-line card (name, stars, all skills) for hover texts. */
    public Component card(AbilityInfo info, AbilityParams params) {
        List<Component> lines = new ArrayList<>();
        lines.add(boldName(info));
        lines.addAll(lore(info, params, false));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /** The ability name in its color with the {@link #card} as hover text. */
    public Component nameWithCard(AbilityInfo info, AbilityParams params) {
        return name(info).hoverEvent(HoverEvent.showText(card(info, params)));
    }

    /**
     * Word-wraps plain or MiniMessage text at {@code width} units (wide glyphs count 2). MiniMessage tags are not
     * counted and lines only break at spaces outside tags; tags are not carried across lines, so descriptions should
     * keep formatting tags short.
     */
    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        for (String word : text.replace('\n', ' ').split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            int wordWidth = visibleWidth(word);
            if (lineWidth > 0 && lineWidth + 1 + wordWidth > width) {
                lines.add(line.toString());
                line.setLength(0);
                lineWidth = 0;
            }
            if (lineWidth > 0) {
                line.append(' ');
                lineWidth++;
            }
            line.append(word);
            lineWidth += wordWidth;
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    static int visibleWidth(String text) {
        int width = 0;
        boolean inTag = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>' && inTag) {
                inTag = false;
            } else if (!inTag) {
                width += isWide(c) ? 2 : 1;
            }
        }
        return width;
    }

    private static boolean isWide(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.HANGUL_SYLLABLES || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
    }

    /** Plain text of a component-free MiniMessage string (for logs and comparisons). */
    public static String plain(String miniMessage) {
        return Text.miniMessage().stripTags(miniMessage);
    }

    /** {@code Component.text(text)} with italics explicitly off (for item names and lore). */
    public static TextComponent upright(String text) {
        return Component.text(text).decoration(TextDecoration.ITALIC, false);
    }
}
