package io.github.sx2xoox.mcpl.game.siege.creature;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Difficulty;
import org.junit.jupiter.api.Test;

/** A creature's hit on a player lands as its balance number whatever the server difficulty. */
class CreaturesTest {

    /** What the game makes of {@code amount} a mob causes to a player (Player#hurtServer). */
    private static double scaled(double amount, Difficulty difficulty) {
        return switch (difficulty) {
            case PEACEFUL -> 0;
            case EASY -> Math.min(amount / 2 + 1, amount);
            case NORMAL -> amount;
            case HARD -> amount * 1.5;
        };
    }

    @Test
    void damageIsCompensatedForTheDifficulty() {
        for (Difficulty difficulty : new Difficulty[] {Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD}) {
            for (double damage : new double[] {0.5, 1, 2, 2.5, 4, 7.5, 20}) {
                assertEquals(damage, scaled(Creatures.beforeDifficulty(damage, difficulty), difficulty), 1.0E-9,
                        damage + " on " + difficulty);
            }
        }
    }
}
