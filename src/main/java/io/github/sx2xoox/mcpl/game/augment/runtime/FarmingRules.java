package io.github.sx2xoox.mcpl.game.augment.runtime;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * The farming grace of a real match ({@code games.augment.farming.*}): nobody gets hurt and nobody gets hungry until
 * the fight starts. See {@link AugmentMatch#farmingInvulnerable()} and {@link AugmentMatch#farmingNoHunger()}.
 */
final class FarmingRules {

    private FarmingRules() {
    }

    /**
     * Whether the farming grace cancels damage of {@code cause}. Commands ({@code /kill}) still kill and the void
     * still hurts, so nobody falls forever below the world.
     */
    static boolean prevents(EntityDamageEvent.DamageCause cause) {
        return cause != EntityDamageEvent.DamageCause.KILL && cause != EntityDamageEvent.DamageCause.VOID;
    }

    /** Full food and saturation, no exhaustion. */
    static void feed(Player player) {
        if (player.getFoodLevel() < 20) {
            player.setFoodLevel(20);
        }
        if (player.getSaturation() < 20f) {
            player.setSaturation(20f);
        }
        player.setExhaustion(0f);
    }
}
