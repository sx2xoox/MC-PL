package io.github.sx2xoox.mcpl.game.superpower.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The weighted entries one kind of chest draws from. A chest picks several <em>different</em> entries (weighted, no
 * entry twice) and gets all of their items.
 */
public record LootPool(List<LootEntry> entries) {

    public LootPool {
        entries = List.copyOf(entries);
    }

    /**
     * Parses the lines of a pool. Invalid lines are skipped with a console warning naming {@code path}, so a typo
     * never breaks a match.
     */
    public static LootPool parse(@Nullable List<?> lines, String path, Logger logger) {
        List<LootEntry> entries = new ArrayList<>();
        if (lines != null) {
            for (Object line : lines) {
                String spec = line == null ? null : line.toString();
                try {
                    entries.add(LootEntry.parse(spec));
                } catch (IllegalArgumentException e) {
                    logger.warning(path + ": ignoring invalid entry '" + spec + "' (" + e.getMessage() + ")");
                }
            }
        }
        LootPool pool = new LootPool(entries);
        if (pool.isEmpty()) {
            logger.warning(path + ": no usable entries, these chests stay empty and are not placed");
        }
        return pool;
    }

    /** True if no entry can ever be picked. */
    public boolean isEmpty() {
        for (LootEntry entry : entries) {
            if (entry.weight() > 0) {
                return false;
            }
        }
        return true;
    }

    /** Up to {@code count} different entries, each picked with a chance proportional to its weight. */
    public List<LootEntry> pick(Random random, int count) {
        List<LootEntry> left = new ArrayList<>();
        for (LootEntry entry : entries) {
            if (entry.weight() > 0) {
                left.add(entry);
            }
        }
        List<LootEntry> picked = new ArrayList<>();
        while (picked.size() < count && !left.isEmpty()) {
            long total = 0;
            for (LootEntry entry : left) {
                total += entry.weight();
            }
            long roll = (long) (random.nextDouble() * total);
            for (int i = 0; i < left.size(); i++) {
                roll -= left.get(i).weight();
                if (roll < 0 || i == left.size() - 1) {
                    picked.add(left.remove(i));
                    break;
                }
            }
        }
        return picked;
    }

    /** The stacks of {@code count} picked entries. */
    public List<ItemStack> roll(Random random, int count, LootConsumables consumables, double gaugePercent) {
        List<ItemStack> stacks = new ArrayList<>();
        for (LootEntry entry : pick(random, count)) {
            stacks.addAll(entry.roll(random, consumables, gaugePercent));
        }
        return stacks;
    }
}
