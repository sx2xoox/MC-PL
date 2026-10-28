package io.github.sx2xoox.mcpl.game.augment.api;

import java.util.Locale;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * How strong an augment is. In one augment round everyone draws from the same tier (TFT style); higher tiers are
 * rarer and come later in the match.
 */
public enum AugmentTier {
    SILVER("실버", TextColor.color(0xC9D6DF), Material.IRON_INGOT),
    GOLD("골드", TextColor.color(0xFFC83D), Material.GOLD_INGOT),
    PRISM("프리즘", TextColor.color(0xD58CFF), Material.AMETHYST_SHARD);

    private final String koreanName;
    private final TextColor color;
    private final Material icon;

    AugmentTier(String koreanName, TextColor color, Material icon) {
        this.koreanName = koreanName;
        this.color = color;
        this.icon = icon;
    }

    /** "실버", "골드", "프리즘" (plain text; the colored name lives in the messages file). */
    public String koreanName() {
        return koreanName;
    }

    public TextColor color() {
        return color;
    }

    /** Item of the tier (the reroll button of the menu). */
    public Material icon() {
        return icon;
    }

    /** Lowercase key for messages ({@code silver}, {@code gold}, {@code prism}). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses {@code SILVER}/{@code silver}/{@code 실버}; null if unknown. */
    public static @Nullable AugmentTier parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String wanted = text.trim();
        for (AugmentTier tier : values()) {
            if (tier.name().equalsIgnoreCase(wanted) || tier.koreanName.equals(wanted)) {
                return tier;
            }
        }
        return null;
    }
}
