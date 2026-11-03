package io.github.sx2xoox.mcpl.game.siege.tower;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;

/**
 * How a 공성전 tower looks (see docs/siege.md §3). The game owns the tower's rules (health, hitbox, order, shield);
 * a look only draws it. The game falls back to a block-display look; with the MCPL resource pack a 3D model look is
 * used, which shows the vanilla look to viewers who do not have the pack loaded.
 */
public interface TowerLook {

    /**
     * Spawns the visual of one tower standing on {@code base} (the crystal spot of {@code SiegeSites.Tower}).
     *
     * @param towerId    {@code front}, {@code middle} or {@code core}
     * @param teamColor  the defending team's colour
     * @return the live visual; the game calls {@link Visual#remove()} when the half or the match ends
     */
    Visual spawn(Location base, String towerId, TextColor teamColor);

    /** One spawned tower visual. Every entity it creates is tracked and removed by {@link #remove()}. */
    interface Visual {

        /** Health left, 0..1 (cracks, damage stages). */
        void health(double fraction);

        /** Whether the tower is protected because the tower in front of it still stands. */
        void shielded(boolean shielded);

        /** Just took a hit (a short flash or shake). */
        void hit();

        /** Destroyed: collapse effect, then the ruin stays until {@link #remove()}. */
        void destroyed();

        /** Removes every entity of this visual. */
        void remove();
    }
}
