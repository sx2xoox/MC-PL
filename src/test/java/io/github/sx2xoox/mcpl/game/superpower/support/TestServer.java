package io.github.sx2xoox.mcpl.game.superpower.support;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.WorldBorder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.RenderType;
import org.jspecify.annotations.Nullable;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.inventory.ChestInventoryMock;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;
import org.mockbukkit.mockbukkit.scoreboard.ObjectiveMock;
import org.mockbukkit.mockbukkit.scoreboard.ScoreMock;
import org.mockbukkit.mockbukkit.scoreboard.ScoreboardManagerMock;
import org.mockbukkit.mockbukkit.scoreboard.ScoreboardMock;
import org.mockbukkit.mockbukkit.world.WorldBorderMock;

/**
 * MockBukkit server that can create per-player world borders (used by the hub's virtual border), whose
 * scoreboards support {@link ObjectiveMock#numberFormat(NumberFormat)} (unimplemented in MockBukkit; the hub's
 * sidebars set it), so the match HUD really runs in tests, and whose custom inventories know their holder.
 */
@SuppressWarnings("unchecked")
public final class TestServer extends ServerMock {

    private @Nullable TestScoreboardManager scoreboards;

    @Override
    public WorldBorder createWorldBorder() {
        return new WorldBorderMock(getWorlds().getFirst());
    }

    @Override
    public InventoryMock createInventory(@Nullable InventoryHolder owner, int size) {
        return createInventory(owner, size, Component.empty());
    }

    /**
     * Chest inventories that also answer {@link Inventory#getHolder(boolean)} like Paper's (MockBukkit throws), so the
     * listeners that find their custom holders without a block snapshot (choice menu, loot chests) run in tests.
     * MockBukkit ignores the title of a chest inventory too.
     */
    @Override
    public InventoryMock createInventory(@Nullable InventoryHolder owner, int size, Component title) {
        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException("Size for custom inventory must be a multiple of 9 between 9 and 54 slots"
                    + " (got " + size + ")");
        }
        return new HolderInventory(owner, size);
    }

    /** A chest inventory whose {@link #getHolder(boolean)} works. */
    static final class HolderInventory extends ChestInventoryMock {

        HolderInventory(@Nullable InventoryHolder owner, int size) {
            super(owner, size);
        }

        @Override
        public @Nullable InventoryHolder getHolder(boolean useSnapshot) {
            return getHolder();
        }
    }

    @Override
    public ScoreboardManagerMock getScoreboardManager() {
        // Lazy: ServerMock's constructor may ask before this subclass's fields are initialised.
        if (scoreboards == null) {
            scoreboards = new TestScoreboardManager();
        }
        return scoreboards;
    }

    /** Hands out {@link TestScoreboard}s. */
    static final class TestScoreboardManager extends ScoreboardManagerMock {
        private final TestScoreboard main = new TestScoreboard();

        @Override
        public ScoreboardMock getMainScoreboard() {
            return main;
        }

        @Override
        public ScoreboardMock getNewScoreboard() {
            return new TestScoreboard();
        }
    }

    /** A scoreboard whose objectives remember their number format. */
    static final class TestScoreboard extends ScoreboardMock {

        @Override
        public ObjectiveMock registerNewObjective(String name, Criteria criteria, Component displayName,
                                                  RenderType renderType) {
            super.registerNewObjective(name, criteria, displayName, renderType);
            // Swap in the subclass (MockBukkit constructs ObjectiveMock itself and keeps it in a private map).
            TestObjective objective = new TestObjective(this, name, displayName, criteria, renderType);
            objectives().put(name, objective);
            return objective;
        }

        private Map<String, ObjectiveMock> objectives() {
            try {
                Field field = ScoreboardMock.class.getDeclaredField("objectives");
                field.setAccessible(true);
                return (Map<String, ObjectiveMock>) field.get(this);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("MockBukkit's ScoreboardMock changed", e);
            }
        }
    }

    /** An objective with a number format whose scores have custom names. */
    static final class TestObjective extends ObjectiveMock {
        private final Map<String, TestScore> scores = new HashMap<>();
        private @Nullable NumberFormat numberFormat;

        TestObjective(ScoreboardMock scoreboard, String name, Component displayName, Criteria criteria,
                      RenderType renderType) {
            super(scoreboard, name, displayName, criteria, renderType);
        }

        @Override
        public @Nullable NumberFormat numberFormat() {
            return numberFormat;
        }

        @Override
        public void numberFormat(@Nullable NumberFormat format) {
            this.numberFormat = format;
        }

        @Override
        public ScoreMock getScore(String entry) {
            return scores.computeIfAbsent(entry, key -> new TestScore(this, key));
        }

        @Override
        @SuppressWarnings("deprecation")
        public ScoreMock getScore(OfflinePlayer player) {
            return getScore(player.getName());
        }
    }

    /** A score with a custom name and number format (both unimplemented in MockBukkit). */
    static final class TestScore extends ScoreMock {
        private @Nullable Component customName;
        private @Nullable NumberFormat numberFormat;

        TestScore(ObjectiveMock objective, String entry) {
            super(objective, entry);
        }

        @Override
        public @Nullable Component customName() {
            return customName;
        }

        @Override
        public void customName(@Nullable Component customName) {
            this.customName = customName;
        }

        @Override
        public @Nullable NumberFormat numberFormat() {
            return numberFormat;
        }

        @Override
        public void numberFormat(@Nullable NumberFormat format) {
            this.numberFormat = format;
        }
    }
}
