package io.github.sx2xoox.mcpl.game.siege;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The experience and in-match level of every 공성전 player (docs/siege.md §12): plain numbers, no Bukkit. Everyone starts
 * each half at level 1 ({@link #reset}); {@link SiegeProgress} turns level-ups into feedback, health and unlocks.
 */
public final class SiegeLevels {

    private final SiegeProgressSettings settings;
    private final Map<UUID, Integer> xp = new HashMap<>();

    public SiegeLevels(SiegeProgressSettings settings) {
        this.settings = settings;
    }

    /** Total experience of {@code player} this half. */
    public int xp(UUID player) {
        return xp.getOrDefault(player, 0);
    }

    /** The level of {@code player} (the highest level while levels are off). */
    public int level(UUID player) {
        return settings.levelsEnabled() ? settings.levelFor(xp(player)) : settings.maxLevel();
    }

    /**
     * Adds experience.
     *
     * @return the levels gained (0 if none)
     */
    public int add(UUID player, int amount) {
        if (!settings.levelsEnabled() || amount <= 0) {
            return 0;
        }
        int before = level(player);
        xp.merge(player, amount, (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
        return level(player) - before;
    }

    /** Experience gathered into the current level. */
    public int xpIntoLevel(UUID player) {
        return xp(player) - settings.xpForLevel(level(player));
    }

    /** Experience the current level needs in all, or 0 at the top level. */
    public int xpThisLevel(UUID player) {
        int level = level(player);
        return level >= settings.maxLevel() ? 0 : settings.xpToNext(level);
    }

    public boolean canUseActive(UUID player) {
        return level(player) >= settings.activeLevel();
    }

    public boolean canUseUltimate(UUID player) {
        return level(player) >= settings.ultimateLevel();
    }

    /** Everyone back to level 1 (a new half). */
    public void reset() {
        xp.clear();
    }

    public SiegeProgressSettings settings() {
        return settings;
    }
}
