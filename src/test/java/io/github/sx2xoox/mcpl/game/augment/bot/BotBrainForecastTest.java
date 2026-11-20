package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Contact;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Decision;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Situation;
import org.junit.jupiter.api.Test;

/** The brain with duel numbers ({@link CombatModel}): what it reads that the gear score could not. */
class BotBrainForecastTest {

    private static final UUID A = new UUID(0, 1);

    private static final CombatModel.Stats IRON = CombatModel.Stats.plain(20, 20, 15, 0, 6).withSkill(11, 0.99, 1);

    private static Situation situation(CombatModel.@org.jspecify.annotations.Nullable Stats self, double health,
                                       Contact contact, Behaviour current, double margin) {
        Gear.Loadout iron = Gear.at(0.6);
        return new Situation(true, 0, health, 0, 20, 3, true, iron.score(), true, 64, margin, true, 50, 12, false,
                true, Double.POSITIVE_INFINITY, false, false, current, current == null ? null : contact.id(),
                List.of(contact), 0, false, 10, 0, self);
    }

    private static Contact contact(double distance, double health, CombatModel.Stats stats, double gearScore) {
        return new Contact(A, distance, health, 20, gearScore, true, false, false, false, false, false, stats);
    }

    @Test
    void itFinishesAnyoneItsNextHitKillsEvenAtTheBorder() {
        // naked, 6 health, 3 blocks away, and the border is closing in: one iron-sword hit kills (6 through no armor)
        CombatModel.Stats naked = CombatModel.Stats.plain(6, 20, 0, 0, 1);
        Contact c = contact(3, 6, naked, Gear.NAKED);
        BotBrain brain = new BotBrain(BotProfile.preset(BotDifficulty.HELL));
        Decision withForecast = brain.decide(situation(IRON.withHealth(20), 20, c, null, 1));
        assertEquals(Behaviour.ENGAGE, withForecast.behaviour(), "a sure kill is worth a moment at the edge");
        Decision before = brain.decide(situation(null, 20, new Contact(A, 3, 6, 20, Gear.NAKED, true, false, false,
                false, false, false), null, 1));
        assertEquals(Behaviour.FLEE_BORDER, before.behaviour(), "the gear score alone only knew 4 health or less");
    }

    @Test
    void itLeavesATradeTheForecastSaysItLoses() {
        // fighting a full-diamond enemy with a diamond sword at full health, itself in iron at 14 health:
        // a lost trade; the earlier brain stayed in it (the committed bonus), the forecast leaves
        BotBrain brain = new BotBrain(BotProfile.preset(BotDifficulty.HELL));
        CombatModel.Stats diamond = CombatModel.Stats.plain(20, 20, 20, 8, 7);
        Contact c = contact(3, 20, diamond, Gear.at(1.4).score());
        Decision withForecast = brain.decide(situation(IRON.withHealth(14), 14, c, Behaviour.ENGAGE, 100));
        assertNotEquals(Behaviour.ENGAGE, withForecast.behaviour(), withForecast.toString());
        assertTrue(CombatModel.winChance(IRON.withHealth(14), diamond) < 0.1);
    }

    /**
     * The cost of a decision with 39 contacts (a 40-bot match), with and without the forecast: printed for the
     * performance notes; the forecast must stay a small part of a tick (50 ms).
     */
    @Test
    void aDecisionWithTheForecastStaysCheap() {
        java.util.Random random = new java.util.Random(3);
        List<Contact> many = new java.util.ArrayList<>();
        List<Contact> plain = new java.util.ArrayList<>();
        for (int i = 0; i < 39; i++) {
            CombatModel.Stats s = CombatModel.Stats.plain(5 + random.nextInt(16), 20, random.nextInt(21), 0, 6);
            UUID id = new UUID(1, i);
            double distance = 5 + random.nextDouble() * 60;
            many.add(new Contact(id, distance, s.health(), 20, 30, true, false, false, false, false, false, s));
            plain.add(new Contact(id, distance, s.health(), 20, 30, true, false, false, false, false, false));
        }
        Gear.Loadout iron = Gear.at(0.6);
        BotBrain brain = new BotBrain(BotProfile.preset(BotDifficulty.HELL));
        double[] micros = new double[2];
        for (int round = 0; round < 2; round++) {
            for (int variant = 0; variant < 2; variant++) {
                List<Contact> contacts = variant == 0 ? many : plain;
                Situation s = new Situation(true, 0, 20, 0, 20, 3, true, iron.score(), true, 64, 100, true, 50, 12,
                        false, true, Double.POSITIVE_INFINITY, false, false, null, null, contacts, 0.3, false, 39, 0,
                        variant == 0 ? IRON : null);
                int n = 2000;
                long start = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    brain.decide(s);
                }
                micros[variant] = (System.nanoTime() - start) / 1000.0 / n;
            }
        }
        System.out.printf(java.util.Locale.ROOT, "[brain-cost] one decision, 39 contacts: forecast %.1f µs, "
                + "gear score %.1f µs%n", micros[0], micros[1]);
        assertTrue(micros[0] < 1000, "a decision takes well under a millisecond: " + micros[0]);
    }

    @Test
    void earlyWithManyRivalsItSkipsAFightItLosesButTheLastFewFightItOut() {
        BotBrain brain = new BotBrain(BotProfile.preset(BotDifficulty.HELL));
        CombatModel.Stats better = CombatModel.Stats.plain(20, 20, 19, 4, 7);
        Contact c = contact(15, 20, better, Gear.at(1.0).score());
        assertTrue(CombatModel.winChance(brain.advantage(situation(IRON, 20, c, null, 100), c, 1)) < 0.4);
        Decision early = brain.decide(situation(IRON, 20, c, null, 100));
        assertEquals(Behaviour.AVOID, early.behaviour(), early.toString());
        Gear.Loadout iron = Gear.at(0.6);
        Situation showdown = new Situation(true, 0, 20, 0, 20, 3, true, iron.score(), true, 64, 100, true, 5, 12, false,
                true, Double.POSITIVE_INFINITY, false, false, null, null, List.of(c), 1, true, 2, 0, IRON);
        assertNotEquals(Behaviour.AVOID, brain.decide(showdown).behaviour(), "the last few fight it out");
    }

    @Test
    void itTakesAFightTheGearScoreCalledEvenWhenItsSwordWins() {
        // the same gear score, but the enemy's is all armor and a fist (no sword in hand): an easy kill
        CombatModel.Stats armoredFist = CombatModel.Stats.plain(20, 20, 20, 0, 1);
        double score = Gear.score(20, 0, 1);
        BotBrain brain = new BotBrain(BotProfile.preset(BotDifficulty.NORMAL));
        Decision d = brain.decide(situation(IRON, 20, contact(10, 20, armoredFist, score), null, 100));
        assertEquals(Behaviour.ENGAGE, d.behaviour(), d.toString());
        assertTrue(CombatModel.winChance(IRON, armoredFist) > 0.9);
    }
}
