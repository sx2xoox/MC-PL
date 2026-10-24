package io.github.sx2xoox.mcpl.core.config;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Where the modes may damage terrain during a match ({@code protection.ability-block-damage} in {@code config.yml}):
 * explosions and heavy impacts of abilities break blocks, which all come back when the match ends.
 */
public enum BlockDamageScope {
    /** Abilities never break blocks. */
    OFF,
    /** Only in the plugin's own battle map worlds ({@code mcpl_map_*}). The default. */
    MAP_WORLDS,
    /** In every arena world, the server's own worlds included. */
    ALL_WORLDS;

    /** The scope named {@code value} (case-insensitive, {@code -} or {@code _}), or null if there is none. */
    public static @Nullable BlockDamageScope parse(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String name = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (BlockDamageScope scope : values()) {
            if (scope.name().equals(name)) {
                return scope;
            }
        }
        return null;
    }
}
