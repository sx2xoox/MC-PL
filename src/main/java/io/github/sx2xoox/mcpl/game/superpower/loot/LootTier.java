package io.github.sx2xoox.mcpl.game.superpower.loot;

/**
 * The kinds of loot chest. Each has its own pool and item count in {@code games.superpower.loot.<key>} and its own
 * look: normal chests are plain, rare ones sparkle, supply drops fall from the sky with a beam and a glowing marker.
 */
public enum LootTier {

    NORMAL("normal"),
    RARE("rare"),
    SUPPLY("supply");

    private final String key;

    LootTier(String key) {
        this.key = key;
    }

    /** The config section and message key of the tier ({@code normal}, {@code rare}, {@code supply}). */
    public String key() {
        return key;
    }
}
