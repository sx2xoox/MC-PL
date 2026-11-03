package io.github.sx2xoox.mcpl.game.siege.jungle;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.ObjectiveKind;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeArena;
import org.bukkit.Location;
import org.bukkit.Sound;

/**
 * The sounds of the jungle (docs/siege-v2.md §4), vanilla picks that fit the toys: squishy jelly, squeaky mice, a
 * teddy's soft thump, a clock rabbit's ticking, the fountain's chime, the telescope's click, the dragon's roar. Played
 * through {@link SiegeArena#sound} (the match may space repeats).
 */
final class JungleSounds {

    private JungleSounds() {
    }

    /** A monster of {@code kind} was hit. */
    static void hurt(SiegeArena arena, ObjectiveKind kind, Location at) {
        switch (kind) {
            case JELLY -> arena.sound(at, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.9f, 1.3f);
            case MICE -> arena.sound(at, Sound.ENTITY_SILVERFISH_HURT, 0.8f, 1.9f);
            case TEDDY -> arena.sound(at, Sound.BLOCK_WOOL_BREAK, 1f, 0.7f);
            case RABBIT -> arena.sound(at, Sound.ENTITY_RABBIT_HURT, 0.9f, 1.2f);
            case DRAGON -> arena.sound(at, Sound.ENTITY_RAVAGER_HURT, 1f, 1.3f);
            default -> {
            }
        }
    }

    /** A monster of {@code kind} hits. */
    static void attack(SiegeArena arena, ObjectiveKind kind, Location at) {
        switch (kind) {
            case JELLY -> arena.sound(at, Sound.ENTITY_SLIME_ATTACK, 0.8f, 1.5f);
            case MICE -> arena.sound(at, Sound.ENTITY_SILVERFISH_AMBIENT, 0.8f, 2f);
            case TEDDY -> arena.sound(at, Sound.ENTITY_RAVAGER_STEP, 1f, 1.6f);
            case DRAGON -> arena.sound(at, Sound.ENTITY_RAVAGER_ATTACK, 1f, 1.2f);
            default -> {
            }
        }
    }

    /** Once a second while the objective is up (only the ones that make a noise: the ticking clock rabbit). */
    static void ambient(SiegeArena arena, ObjectiveKind kind, Location at, long second) {
        if (kind == ObjectiveKind.RABBIT) {
            arena.sound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, second % 2 == 0 ? 1.8f : 1.5f);
        }
    }

    /** The objective appeared. */
    static void appear(SiegeArena arena, ObjectiveKind kind, Location at) {
        switch (kind) {
            case DRAGON -> arena.sound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 1.4f);
            case FOUNTAIN -> arena.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
            case TELESCOPE -> arena.sound(at, Sound.UI_BUTTON_CLICK, 1f, 1.4f);
            default -> arena.sound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.6f);
        }
    }

    /** The monsters noticed a fighter (the dragon roars). */
    static void aggro(SiegeArena arena, ObjectiveKind kind, Location at) {
        if (kind == ObjectiveKind.DRAGON) {
            arena.sound(at, Sound.ENTITY_RAVAGER_ROAR, 2f, 1.2f);
        }
    }

    /** Capture progress ticked (every second while someone captures). */
    static void capturing(SiegeArena arena, ObjectiveKind kind, Location at) {
        if (kind == ObjectiveKind.TELESCOPE) {
            arena.sound(at, Sound.UI_BUTTON_CLICK, 0.5f, 1.8f);
        } else {
            arena.sound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.4f, 1.8f);
        }
    }

    /** Taken: cleared or captured. */
    static void taken(SiegeArena arena, ObjectiveKind kind, Location at) {
        switch (kind) {
            case FOUNTAIN -> arena.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, 0.8f);
            case TELESCOPE -> arena.sound(at, Sound.ITEM_SPYGLASS_USE, 1.5f, 0.9f);
            case DRAGON -> arena.sound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.8f);
            case TEDDY, RABBIT -> arena.sound(at, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
            default -> arena.sound(at, Sound.ENTITY_PLAYER_BURP, 0.6f, 1.6f);
        }
    }
}
