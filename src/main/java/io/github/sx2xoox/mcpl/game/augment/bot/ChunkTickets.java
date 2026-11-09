package io.github.sx2xoox.mcpl.game.augment.bot;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Keeps chunks loaded for the bots. On Paper 26 a plugin chunk ticket ({@link World#addPluginChunkTicket}) is a
 * {@code plugin_ticket} at the forced level (31) with the loading and simulation flags: the ticketed chunk is
 * entity-ticking (the bot's legs walk and path there) and its neighbours are loaded around it, wherever the players
 * are. Its own interface so tests can record what is held.
 */
public interface ChunkTickets {

    void add(int chunkX, int chunkZ);

    void remove(int chunkX, int chunkZ);

    /** Lets go of every chunk this holds. */
    void removeAll();

    /** Plugin chunk tickets in {@code world}. */
    static ChunkTickets of(World world, Plugin plugin) {
        return new ChunkTickets() {
            @Override
            public void add(int chunkX, int chunkZ) {
                world.addPluginChunkTicket(chunkX, chunkZ, plugin);
            }

            @Override
            public void remove(int chunkX, int chunkZ) {
                world.removePluginChunkTicket(chunkX, chunkZ, plugin);
            }

            @Override
            public void removeAll() {
                world.removePluginChunkTickets(plugin);
            }
        };
    }
}
