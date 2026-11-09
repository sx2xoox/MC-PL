package io.github.sx2xoox.mcpl.game.superpower.loot;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Lidded;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * One loot chest of a match: a temporary chest block (placed with the temp-block service) and a <em>virtual</em>
 * inventory that every participant opening it shares. The items never live in the block entity: removing a
 * container block spills whatever its block entity holds, so the block's own inventory always stays empty and the
 * chest can be restored (or recovered after a crash) without leaving items behind.
 */
public final class LootChest implements InventoryHolder {

    /** Size of the virtual inventory. */
    public static final int SIZE = 27;

    private final Block block;
    private final LootTier tier;
    private final Inventory inventory;
    private @Nullable ItemDisplay marker;
    private boolean opened;
    private boolean lidOpen;
    private boolean removed;

    LootChest(Block block, LootTier tier, Component title) {
        this.block = block;
        this.tier = tier;
        this.inventory = Bukkit.createInventory(this, SIZE, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** The chest block. */
    public Block block() {
        return block;
    }

    public LootTier tier() {
        return tier;
    }

    /** True once somebody opened it (supply drops lose their beam and marker then). */
    public boolean wasOpened() {
        return opened;
    }

    /** True once the chest is gone (emptied, the match ended, or its block was changed). */
    public boolean isRemoved() {
        return removed;
    }

    /** The glowing marker above an unopened supply drop, or null. */
    public @Nullable ItemDisplay marker() {
        return marker;
    }

    /** The center of the chest block. */
    public Location center() {
        return block.getLocation().add(0.5, 0.5, 0.5);
    }

    /** True while the block is still the chest (loads its chunk: check {@link #isLoaded()} first). */
    boolean isIntact() {
        return block.getType() == Material.CHEST;
    }

    /**
     * True while the chest's chunk is loaded. An unloaded chest has nobody near it and its block cannot change, so the
     * upkeep skips it instead of loading the chunk again.
     */
    boolean isLoaded() {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    void markOpened() {
        opened = true;
    }

    void marker(@Nullable ItemDisplay display) {
        this.marker = display;
    }

    void markRemoved() {
        removed = true;
    }

    /** Opens or closes the lid (with the vanilla sound) while someone looks inside. */
    void lid(boolean open) {
        if (lidOpen == open || !isLoaded() || !isIntact()) {
            return;
        }
        lidOpen = open;
        if (block.getState(false) instanceof Lidded lidded) {
            if (open) {
                lidded.open();
            } else {
                lidded.close();
            }
        }
    }
}
