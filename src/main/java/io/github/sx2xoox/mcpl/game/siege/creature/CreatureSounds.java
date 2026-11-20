package io.github.sx2xoox.mcpl.game.siege.creature;

import io.github.sx2xoox.mcpl.game.siege.arena.SiegeArena;
import org.bukkit.Location;
import org.bukkit.Sound;

/**
 * The attack sounds of the 공성전 v2 creatures (docs/siege-v2.md §7.1), vanilla picks that fit the toys, heard by everyone
 * (with or without the pack): the soldier's wind-up click, the archer's bowstring, the cannon's pop, the jelly's boing,
 * the mouse's nibble, the teddy's soft whump, the rabbit's throw, the dragon's bite; each skill's warning and blow. Played through
 * {@link SiegeArena#sound} (the match may space repeats).
 */
public final class CreatureSounds {

    private CreatureSounds() {
    }

    /** A creature of {@code kind} makes its basic attack (the shopkeeper: bows). */
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
                arena.sound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 0.6f);
                arena.sound(at, Sound.BLOCK_WOOL_HIT, 1f, 0.6f);
            }
            case RABBIT -> {
                arena.sound(at, Sound.ENTITY_SNOWBALL_THROW, 0.8f, 1.3f);
                arena.sound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.9f);
            }
            case SHOPKEEPER -> arena.sound(at, Sound.ENTITY_WANDERING_TRADER_YES, 0.5f, 1.4f);
        }
    }

    /** A skill is being charged (its warning shows): the creature's tell, heard by everyone nearby. */
    public static void warn(SiegeArena arena, CreatureModels.Attack motion, Location at) {
        switch (motion) {
            case GOOP -> arena.sound(at, Sound.BLOCK_HONEY_BLOCK_SLIDE, 1f, 1.2f);
            case SQUEAK -> arena.sound(at, Sound.ENTITY_BAT_AMBIENT, 0.6f, 2f);
            case SLAM -> arena.sound(at, Sound.BLOCK_WOOL_HIT, 0.8f, 0.7f);
            case SHOCKWAVE -> {
                arena.sound(at, Sound.BLOCK_WOOL_PLACE, 1f, 0.5f);
                arena.sound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
            }
            case WIND -> arena.sound(at, Sound.ITEM_CROSSBOW_LOADING_START, 1f, 1.5f);
            case BREATH -> arena.sound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.9f, 1.7f);
            case TAIL -> arena.sound(at, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.7f, 0.8f);
            case GUST -> arena.sound(at, Sound.ITEM_ELYTRA_FLYING, 0.4f, 1.6f);
            default -> {
            }
        }
    }

    /** A skill lands ({@code motion} reaching its {@link CreatureModels.Attack#impact()}). */
    public static void skill(SiegeArena arena, CreatureModels.Attack motion, Location at) {
        switch (motion) {
            case GOOP -> arena.sound(at, Sound.BLOCK_HONEY_BLOCK_PLACE, 1f, 1.3f);
            case SQUEAK -> arena.sound(at, Sound.ENTITY_BAT_AMBIENT, 0.8f, 1.8f);
            case SLAM -> {
                arena.sound(at, Sound.BLOCK_WOOL_FALL, 1.2f, 0.5f);
                arena.sound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 0.8f);
            }
            case SHOCKWAVE -> {
                arena.sound(at, Sound.BLOCK_WOOL_BREAK, 1.5f, 0.5f);
                arena.sound(at, Sound.ENTITY_PLAYER_BIG_FALL, 1.2f, 0.6f);
            }
            case WIND -> {
                arena.sound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.7f);
                arena.sound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 2f);
            }
            case BREATH -> {
                arena.sound(at, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1.2f, 1.4f);
                arena.sound(at, Sound.ITEM_FIRECHARGE_USE, 0.8f, 1.5f);
            }
            case TAIL -> arena.sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.6f);
            case GUST -> {
                arena.sound(at, Sound.ENTITY_BREEZE_WIND_BURST, 1.2f, 0.9f);
                arena.sound(at, Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.2f);
            }
            case ROAR -> {
                arena.sound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 1.1f);
                arena.sound(at, Sound.ENTITY_ENDER_DRAGON_AMBIENT, 1.5f, 1.3f);
            }
            default -> {
            }
        }
    }

    /** A cannon shell bursts where it lands (a toy pop, not an explosion). */
    public static void shellBurst(SiegeArena arena, Location at) {
        arena.sound(at, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.9f, 1.5f);
        arena.sound(at, Sound.BLOCK_WOOD_BREAK, 0.6f, 1.4f);
    }

    /** A toy arrow lands. */
    public static void arrowHit(SiegeArena arena, Location at) {
        arena.sound(at, Sound.ENTITY_ARROW_HIT, 0.5f, 1.6f);
    }

    /** The clock hand hits: time slows (a low, stretched bell). */
    public static void slowed(SiegeArena arena, Location at) {
        arena.sound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 0.6f);
        arena.sound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.7f);
    }

    /** A creature breaks: a little toy pop instead of the vanilla death cry. */
    public static void death(SiegeArena arena, Location at) {
        arena.sound(at, Sound.ENTITY_ITEM_BREAK, 0.6f, 1.3f);
        arena.sound(at, Sound.ENTITY_CHICKEN_EGG, 0.6f, 1.6f);
    }
}
