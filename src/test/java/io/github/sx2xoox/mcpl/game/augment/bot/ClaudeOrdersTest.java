package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Contact;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Decision;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Situation;
import org.junit.jupiter.api.Test;

/** The Claude bot's orders weigh the brain's options; they never beat the border or a sure kill. */
class ClaudeOrdersTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private static Situation fight(double health, double borderMargin, List<Contact> contacts) {
        return new Situation(true, 0, health, 0, 20, 2, true, Gear.AVERAGE, true, 64, borderMargin, false, 50, 30,
                false, true, Double.POSITIVE_INFINITY, false, false, null, null, contacts, 0.2);
    }

    private static Contact rival(UUID id, double distance, double health) {
        return new Contact(id, distance, health, 20, Gear.AVERAGE, true, false, false, false);
    }

    private static BotBrain brain() {
        return new BotBrain(BotProfile.preset(BotDifficulty.HELL));
    }

    @Test
    void anAttackOrderPicksTheNamedTargetOverTheBrainsChoice() {
        BotBrain brain = brain();
        Situation s = fight(20, 100, List.of(rival(A, 8, 20), rival(B, 20, 20)));
        Decision plain = brain.decide(s);
        assertEquals(A, plain.target(), "the brain goes for the nearer one: " + brain.options(s));
        brain.orders(new ClaudeOrders(ClaudeOrders.Goal.ATTACK, B, ClaudeOrders.Range.MELEE));
        Decision ordered = brain.decide(s);
        assertEquals(B, ordered.target(), "told to hunt B: " + ordered);
        assertTrue(ordered.behaviour() == Behaviour.ENGAGE || ordered.behaviour() == Behaviour.KITE);
    }

    @Test
    void aRetreatOrderBacksOffAHealthyBotFromAnEvenFight() {
        BotBrain brain = brain();
        Situation s = fight(14, 100, List.of(rival(A, 6, 20)));
        assertEquals(Behaviour.ENGAGE, brain.decide(s).behaviour());
        brain.orders(new ClaudeOrders(ClaudeOrders.Goal.RETREAT, null, ClaudeOrders.Range.AUTO));
        Behaviour ordered = brain.decide(s).behaviour();
        assertTrue(ordered == Behaviour.RETREAT_HEAL || ordered == Behaviour.AVOID || ordered == Behaviour.FORTIFY,
                "backs off: " + ordered);
    }

    @Test
    void noOrderBeatsGettingBackInsideTheBorder() {
        BotBrain brain = brain();
        brain.orders(new ClaudeOrders(ClaudeOrders.Goal.ATTACK, A, ClaudeOrders.Range.MELEE));
        Situation outside = fight(20, -4, List.of(rival(A, 10, 20)));
        assertEquals(Behaviour.FLEE_BORDER, brain.decide(outside).behaviour());
        brain.orders(new ClaudeOrders(ClaudeOrders.Goal.BASE, null, ClaudeOrders.Range.AUTO));
        assertEquals(Behaviour.FLEE_BORDER, brain.decide(outside).behaviour());
    }

    @Test
    void aRetreatOrderNeverDropsASureKill() {
        BotBrain brain = brain();
        brain.orders(new ClaudeOrders(ClaudeOrders.Goal.RETREAT, null, ClaudeOrders.Range.AUTO));
        Decision d = brain.decide(fight(20, 100, List.of(rival(A, 2, 2))));
        assertEquals(Behaviour.ENGAGE, d.behaviour());
        assertEquals(A, d.target());
    }

    @Test
    void aFarmOrderKeepsABuilderGearingUpInsteadOfBuilding() {
        BotBrain brain = new BotBrain(BotProfile.preset(BotDifficulty.HELL),
                BotPersonality.FORTRESS.traits(new java.util.Random(1)), new java.util.Random(1));
        Situation farming = new Situation(false, 0.8, 20, 0, 20, 0, true, Gear.AVERAGE, false, 64, 100, false, 50,
                30, false, false, Double.POSITIVE_INFINITY, true, false, null, null, List.of());
        assertEquals(Behaviour.BUILD_BASE, brain.decide(farming).behaviour());
        brain.orders(new ClaudeOrders(ClaudeOrders.Goal.FARM, null, ClaudeOrders.Range.AUTO));
        assertEquals(Behaviour.FARM, brain.decide(farming).behaviour());
    }

    @Test
    void aBowPreferenceShootsFromRange() {
        BotBrain brain = brain();
        Situation s = fight(20, 100, List.of(rival(A, 14, 20)));
        brain.orders(new ClaudeOrders(ClaudeOrders.Goal.AUTO, null, ClaudeOrders.Range.BOW));
        assertEquals(Behaviour.KITE, brain.decide(s).behaviour(), String.valueOf(brain.options(s)));
    }

    @Test
    void noOrdersOrAutoOrdersChangeNothing() {
        BotBrain brain = brain();
        Situation s = fight(20, 100, List.of(rival(A, 8, 20), rival(B, 20, 15)));
        Decision plain = brain.decide(s);
        brain.orders(ClaudeOrders.NONE);
        assertTrue(ClaudeOrders.NONE.none());
        assertEquals(plain, brain.decide(s));
        brain.orders(null);
        assertNull(brain.orders());
        assertFalse(new ClaudeOrders(ClaudeOrders.Goal.ZONE, null, ClaudeOrders.Range.AUTO).none());
    }
}
