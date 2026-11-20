package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The duel forecast against a hit-by-hit simulation of many random duels (gear from the schedule, hurt or healthy,
 * skill, apples, augments), next to the earlier "power" (health × gear factor²) the brain compared before. Prints the
 * accuracy table (docs/augment.md).
 */
class CombatModelTest {

    @Test
    void armorFollowsVanilla() {
        // a diamond sword's 7 on full diamond (20 armor, 8 toughness): 20 − 28/16 = 18.25 points, 73 % off
        assertEquals(7 * (1 - 18.25 / 25), CombatModel.afterArmor(7, 20, 8), 1.0E-9);
        // nothing on: all of it
        assertEquals(6, CombatModel.afterArmor(6, 0, 0), 1.0E-9);
        // a huge hit breaks through: only armor/5 points stay (30 armor: 6 points, 24 % off)
        assertEquals(76, CombatModel.afterArmor(100, 30, 0), 1.0E-9);
        // never more than 80 % off
        assertEquals(1, CombatModel.afterArmor(5, 60, 20), 1.0E-9);
    }

    @Test
    void betterGearAndHealthWin() {
        CombatModel.Stats iron = CombatModel.Stats.plain(20, 20, 15, 0, 6);
        CombatModel.Stats diamond = CombatModel.Stats.plain(20, 20, 20, 8, 7);
        assertEquals(1, CombatModel.advantage(iron, iron), 1.0E-9);
        assertTrue(CombatModel.winChance(diamond, iron) > 0.75);
        assertTrue(CombatModel.winChance(iron.withHealth(6), iron) < 0.2, "hurt, it loses the even duel");
        // a berserker's pact with a sharp edge beats the plain mirror
        CombatModel.Stats pact = iron.with(AugmentValues.effect("berserker_pact", Map.of()))
                .with(AugmentValues.effect("sharp_edge", Map.of()));
        assertTrue(CombatModel.winChance(pact, iron) > 0.6);
    }

    /** One random fighter of a duel, and how the earlier brain saw it. */
    record Fighter(CombatModel.Stats stats, double gearScore) {
    }

    static Fighter randomFighter(Random random) {
        Gear.Loadout gear = Gear.at(0.25 + random.nextDouble() * 1.2);
        double maxHealth = 20;
        CombatModel.Stats s = new CombatModel.Stats(20, maxHealth, gear.armorPoints(), gear.toughness(),
                gear.sword().damage(), 11 + random.nextInt(3), 0.6 + random.nextDouble() * 0.4,
                0.3 + random.nextDouble() * 0.7, 1, 1, 0, 0, 0, 0, 1, random.nextInt(3), 0);
        List<String> pool = new ArrayList<>(List.of("iron_body", "sharp_edge", "thick_skin", "fire_blade", "venom",
                "bloodthirst", "berserker_pact", "executioner", "combo", "thorns", "heavy_hitter", "phoenix_feather",
                "giant", "fortress"));
        java.util.Collections.shuffle(pool, random);
        int count = random.nextInt(4);
        for (int i = 0; i < count; i++) {
            s = s.with(AugmentValues.effect(pool.get(i), Map.of()));
        }
        s = s.withHealth(Math.max(2, s.maxHealth() * (0.25 + random.nextDouble() * 0.75)));
        return new Fighter(s, gear.score());
    }

    /** The earlier forecast: its power over theirs (its apples counted as 4 each, at most 2). */
    static double oldAdvantage(Fighter me, Fighter them) {
        double mine = BotBrain.power(me.stats().health() + Math.min(me.stats().apples(), 2) * 4.0, me.gearScore());
        double theirs = BotBrain.power(them.stats().health(), them.gearScore());
        return mine / Math.max(1, theirs);
    }

    @Test
    void theForecastPredictsDuelsBetterThanTheEarlierPower() {
        Random random = new Random(11);
        int duels = 1500;
        int runs = 120;
        double[] truth = new double[duels];
        double[] mine = new double[duels];
        double[] old = new double[duels];
        for (int i = 0; i < duels; i++) {
            Fighter a = randomFighter(random);
            Fighter b = randomFighter(random);
            int wins = 0;
            for (int r = 0; r < runs; r++) {
                wins += CombatModel.simulate(a.stats(), b.stats(), random) ? 1 : 0;
            }
            truth[i] = (double) wins / runs;
            mine[i] = CombatModel.advantage(a.stats(), b.stats());
            old[i] = oldAdvantage(a, b);
        }
        // the earlier power gets its best steepness (a fair comparison of what it can tell)
        double oldK = bestSteepness(old, truth);
        double newBrier = brier(mine, truth, CombatModel.STEEPNESS);
        double oldBrier = brier(old, truth, oldK);
        double newRight = rightSide(mine, truth);
        double oldRight = rightSide(old, truth);
        // the fights it would take (forecast > 50 %): how many of them it really wins, and how many sure wins it skips
        double[] newTaken = taken(mine, truth);
        double[] oldTaken = taken(old, truth);
        System.out.printf(Locale.ROOT, "[combat-model] duels=%d runs=%d%n"
                        + "| 예측 | 이기는 쪽 맞힘 | Brier(낮을수록 좋음) | 건 싸움의 실제 승률 | 놓친 이길 싸움 |%n|---|---|---|---|---|%n"
                        + "| 예전 power | %.1f%% | %.3f (k=%.1f) | %.1f%% | %.1f%% |%n"
                        + "| 새 CombatModel | %.1f%% | %.3f | %.1f%% | %.1f%% |%n", duels, runs,
                100 * oldRight, oldBrier, oldK, 100 * oldTaken[0], 100 * oldTaken[1],
                100 * newRight, newBrier, 100 * newTaken[0], 100 * newTaken[1]);
        assertTrue(newRight > oldRight + 0.05, "it calls the winner far more often");
        assertTrue(newBrier < oldBrier, "and its chances are closer to the truth");
        assertTrue(bestSteepness(mine, truth) > CombatModel.STEEPNESS - 1.5
                && bestSteepness(mine, truth) < CombatModel.STEEPNESS + 1.5, "the steepness fits the simulation");
    }

    private static double brier(double[] advantage, double[] truth, double k) {
        double sum = 0;
        for (int i = 0; i < truth.length; i++) {
            double a = Math.pow(Math.max(1.0E-6, advantage[i]), k);
            double p = a / (1 + a);
            sum += (p - truth[i]) * (p - truth[i]);
        }
        return sum / truth.length;
    }

    private static double bestSteepness(double[] advantage, double[] truth) {
        double best = 1;
        double bestScore = Double.POSITIVE_INFINITY;
        for (double k = 0.25; k <= 10; k += 0.25) {
            double score = brier(advantage, truth, k);
            if (score < bestScore) {
                bestScore = score;
                best = k;
            }
        }
        return best;
    }

    private static double rightSide(double[] advantage, double[] truth) {
        int right = 0;
        int counted = 0;
        for (int i = 0; i < truth.length; i++) {
            if (Math.abs(truth[i] - 0.5) < 0.05) {
                continue; // a coin flip has no right side
            }
            counted++;
            right += (advantage[i] > 1) == (truth[i] > 0.5) ? 1 : 0;
        }
        return (double) right / counted;
    }

    /** {real win rate of the fights it takes, share of the fights it would win (≥ 70 %) that it skips}. */
    private static double[] taken(double[] advantage, double[] truth) {
        double won = 0;
        int took = 0;
        int sure = 0;
        int skipped = 0;
        for (int i = 0; i < truth.length; i++) {
            if (advantage[i] > 1) {
                took++;
                won += truth[i];
            }
            if (truth[i] >= 0.7) {
                sure++;
                skipped += advantage[i] > 1 ? 0 : 1;
            }
        }
        return new double[] {took == 0 ? 0 : won / took, sure == 0 ? 0 : (double) skipped / sure};
    }
}
