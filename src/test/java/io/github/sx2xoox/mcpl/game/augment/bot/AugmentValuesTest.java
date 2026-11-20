package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentRegistry;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.augments.BuiltinAugments;

/**
 * The augment picks: every augment a bot can own is in the table, and a bot that reads the cards (with what it owns)
 * ends up with augments that beat the random card it took before. Prints the measurement (docs/augment.md).
 */
class AugmentValuesTest {

    static List<AugmentInfo> botAugments() {
        AugmentRegistry registry = new AugmentRegistry();
        BuiltinAugments.registerAll(registry);
        List<AugmentInfo> out = new ArrayList<>();
        for (AugmentRegistry.Entry e : registry.all()) {
            if (e.info().forBots()) {
                out.add(e.info());
            }
        }
        return out;
    }

    @Test
    void everyAugmentBotsCanOwnHasAValue() {
        Set<String> missing = new TreeSet<>();
        for (AugmentInfo info : botAugments()) {
            if (AugmentValues.effect(info.id(), info.params()) == AugmentValues.Effect.NONE) {
                missing.add(info.id());
            }
        }
        assertEquals(Set.of(), missing, "augments bots can own without a value in AugmentValues");
    }

    @Test
    void itReadsTheCardsWithWhatItOwns() {
        CombatModel.Stats me = CombatModel.Stats.plain(20, 20, 15, 0, 6).withSkill(12, 0.9, 0.8);
        Random random = new Random(1);
        List<AugmentValues.Card> cards = List.of(card("feather_fall"), card("berserker_pact"), card("vengeful"));
        assertEquals("berserker_pact", AugmentValues.pick(cards, me, Map.of(), AugmentValues.Effect.NONE, 0, 0, 1,
                0, random));
        // a sniper wants its arrows to hit harder
        List<AugmentValues.Card> bow = List.of(card("sniper"), card("feather_fall"), card("vengeful"));
        assertEquals("sniper", AugmentValues.pick(bow, me, Map.of(), AugmentValues.Effect.NONE, 0.8, 0, 1, 0, random));
    }

    private static AugmentValues.Card card(String id) {
        for (AugmentInfo info : botAugments()) {
            if (info.id().equals(id)) {
                return new AugmentValues.Card(id, info.params());
            }
        }
        throw new IllegalArgumentException(id);
    }

    /** The default rounds' tier weights (silver, gold, prism). */
    static final double[][] ROUNDS = {{70, 25, 5}, {40, 45, 15}, {20, 50, 30}, {10, 45, 45}};

    /**
     * Two bots of the same gear and skill draw the same four rounds of three cards (the rounds' tiers): one takes the
     * card the smart pick names, the other the first card (what bots did before). Then they fight duels.
     */
    @Test
    void readingTheCardsWinsDuels() {
        List<AugmentInfo> pool = botAugments();
        Random random = new Random(5);
        int matches = 1500;
        int runs = 60;
        double smartWins = 0;
        double mirror = 0;
        Map<String, Integer> picked = new LinkedHashMap<>();
        for (int m = 0; m < matches; m++) {
            Gear.Loadout gear = Gear.at(0.5 + random.nextDouble() * 0.8);
            CombatModel.Stats base = new CombatModel.Stats(20, 20, gear.armorPoints(), gear.toughness(),
                    gear.sword().damage(), 12, 0.85, 0.7, 1, 1, 0, 0, 0, 0, 1, 2, 0);
            Map<String, Map<String, Double>> smart = new LinkedHashMap<>();
            Map<String, Map<String, Double>> first = new LinkedHashMap<>();
            for (double[] weights : ROUNDS) {
                AugmentTier tier = tier(weights, random);
                smart.computeIfAbsent(pick(pool, tier, smart.keySet(), random, base, smart), k -> paramsOf(pool, k));
                List<AugmentValues.Card> offer = offer(pool, tier, first.keySet(), random);
                first.put(offer.getFirst().id(), offer.getFirst().params());
            }
            smart.keySet().forEach(id -> picked.merge(id, 1, Integer::sum));
            CombatModel.Stats a = base.with(AugmentValues.total(smart));
            CombatModel.Stats b = base.with(AugmentValues.total(first));
            int wins = 0;
            int even = 0;
            for (int r = 0; r < runs; r++) {
                wins += CombatModel.simulate(a, b, random) ? 1 : 0;
                even += CombatModel.simulate(b, b, random) ? 1 : 0;
            }
            smartWins += (double) wins / runs;
            mirror += (double) even / runs;
        }
        double rate = smartWins / matches;
        List<Map.Entry<String, Integer>> top = new ArrayList<>(picked.entrySet());
        top.sort((x, y) -> y.getValue() - x.getValue());
        System.out.printf(Locale.ROOT, "[augment-picks] matches=%d runs=%d smart vs first-card win=%.1f%% "
                + "(same augments vs themselves: %.1f%%) most picked=%s%n", matches, runs, 100 * rate,
                100 * mirror / matches, top.subList(0, Math.min(8, top.size())));
        assertTrue(rate > 0.6, "reading the cards wins clearly more duels: " + rate);
    }

    private static String pick(List<AugmentInfo> pool, AugmentTier tier, Set<String> owned, Random random,
                               CombatModel.Stats base, Map<String, Map<String, Double>> have) {
        List<AugmentValues.Card> offer = offer(pool, tier, owned, random);
        return AugmentValues.pick(offer, base, have, AugmentValues.Effect.NONE, 0.2, 0.2, 1, 0, random);
    }

    private static List<AugmentValues.Card> offer(List<AugmentInfo> pool, AugmentTier tier, Set<String> owned,
                                                  Random random) {
        List<AugmentInfo> same = new ArrayList<>();
        for (AugmentInfo info : pool) {
            if (info.tier() == tier && !owned.contains(info.id())) {
                same.add(info);
            }
        }
        Collections.shuffle(same, random);
        List<AugmentValues.Card> out = new ArrayList<>();
        for (AugmentInfo info : same.subList(0, Math.min(3, same.size()))) {
            out.add(new AugmentValues.Card(info.id(), info.params()));
        }
        return out;
    }

    private static Map<String, Double> paramsOf(List<AugmentInfo> pool, String id) {
        for (AugmentInfo info : pool) {
            if (info.id().equals(id)) {
                return info.params();
            }
        }
        return Map.of();
    }

    private static AugmentTier tier(double[] weights, Random random) {
        double roll = random.nextDouble() * (weights[0] + weights[1] + weights[2]);
        return roll < weights[0] ? AugmentTier.SILVER : roll < weights[0] + weights[1] ? AugmentTier.GOLD
                : AugmentTier.PRISM;
    }
}
