package io.github.sx2xoox.mcpl.game.siege.creature;

import io.github.sx2xoox.mcpl.game.siege.arena.SiegeArena;
import org.bukkit.Location;
import org.bukkit.Sound;

/**
 * The attack sounds of the 공성전 v2 creatures (docs/siege-v2.md §7.1), vanilla picks that fit the toys, heard by everyone
 * (with or without the pack): the soldier's wind-up click, the archer's bowstring, the cannon's pop, the jelly's boing,
 * the mouse's nibble, the teddy's soft whump, the dragon's roar and wing beat, the rabbit's startled hop. Played through
 * {@link SiegeArena#sound} (the match may space repeats).
 */
public final class CreatureSounds {

    private CreatureSounds() {
    }

    /** A creature of {@code kind} attacks (or, for the rabbit and the shopkeeper, plays its attack motion). */
    public static void attack(SiegeArena arena, CreatureKind kind, Location at) {
        switch (kind) {
            case MINION_MELEE -> {
                arena.sound(at, Sound.BLOCK_TRIPWIRE_CLICK_ON, 0.6f, 1.7f);
                arena.sound(at, Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.5f, 1.6f);
            }
            case MINION_RANGED -> arena.sound(at, Sound.ENTITY_ARROW_SHOOT, 0.6f, 1.7f);
            case MINION_CANNON -> arena.sound(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9f, 1.3f);
            case JELLY -> arena.sound(at, Sound.ENTITY_SLIME_JUMP_SMALL, 0.8f, 1.5f);
            case MICE -> arena.sound(at, Sound.ENTITY_FOX_BITE, 0.6f, 2f);
            case TEDDY -> {
                arena.sound(at, Sound.BLOCK_WOOL_PLACE, 1f, 0.6f);
                arena.sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.4f);
            }
            case DRAGON -> {
                arena.sound(at, Sound.ENTITY_RAVAGER_ATTACK, 1f, 1.2f);
                arena.sound(at, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.4f);
            }
            case RABBIT -> arena.sound(at, Sound.ENTITY_RABBIT_JUMP, 0.8f, 1.4f);
            case SHOPKEEPER -> arena.sound(at, Sound.ENTITY_WANDERING_TRADER_YES, 0.5f, 1.4f);
        }
    }
}
