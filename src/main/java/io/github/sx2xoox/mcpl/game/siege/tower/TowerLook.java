package io.github.sx2xoox.mcpl.game.siege.tower;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;

/**
 * How a 공성전 structure looks (see docs/siege.md "타워와 코어"). The game owns the rules (health, hitbox, shield); a
 * look only draws it. Look ids: {@link #TOWER} (a lane tower, outer or inner) and {@link #CORE} (a side's core). A 3D model look shows a vanilla look to viewers who do not have the pack loaded.
 */
public interface TowerLook {

    /** Look id of a lane tower (outer or inner). */
    String TOWER = "tower";
    /** Look id of a side's core (넥서스). */
    String CORE = "core";

    /**
     * Spawns the visual of one structure standing on {@code base} (its feet spot, facing the enemy base).
     *
     * @param towerId   {@link #TOWER} or {@link #CORE}
     * @param teamColor the owning side's colour
     * @return the live visual; the game calls {@link Visual#remove()} when the round or the match ends
     */
    Visual spawn(Location base, String towerId, TextColor teamColor);

    /**
     * The hitbox (Mannequin scale) a structure of this look needs: a look with bigger towers scales the configured
     * {@code games.siege.towers.hitbox-scale} up so the hitbox fills the structure. Default: as configured.
     */
    default double hitboxScale(String towerId, double configured) {
        return configured;
    }

    /** Height above the base (blocks) of the overhead health bar: just over the top of the structure. */
    default double barHeight(String towerId) {
        return 7;
    }

    /** One spawned visual. Every entity it creates is tracked and removed by {@link #remove()}. */
    interface Visual {

        /** Health left, 0..1 (cracks, damage stages). */
        void health(double fraction);

        /** Whether the structure is protected (the towers before it still stand). */
        void shielded(boolean shielded);

        /** Just took a hit (a short flash or shake). */
        void hit();

        /** Destroyed: collapse effect, then the ruin stays until {@link #remove()}. */
        void destroyed();

        /** Removes every entity of this visual. */
        void remove();
    }
}
