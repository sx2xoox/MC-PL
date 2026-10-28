package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Contact;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Situation;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

/** Personalities change what a bot prefers in the same situation (multiplied scores), not the rules. */
class BotPersonalityTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final double IRON = Gear.at(0.6).score();
    private static final double DIAMOND = Gear.at(1.4).score();

    /** A random whose doubles are all 0.5: the archetype's numbers without a bot's own variation. */
    private static final class Middle extends Random {
        @Override
        public double nextDouble() {
            return 0.5;
        }
    }

    private static BotBrain brain(BotPersonality personality) {
        return new BotBrain(BotProfile.preset(BotDifficulty.NORMAL), personality.traits(new Middle()), new Random(1));
    }

    private static BotBrain neutral() {
        return new BotBrain(BotProfile.preset(BotDifficulty.NORMAL));
    }

    private static Situation fight(double health, List<Contact> contacts, boolean baseBuilt, double baseDistance,
                                   boolean baseSafe) {
        return new Situation(true, 0, health, 0, 20, 3, true, IRON, true, 100, 100, false, 50, 12, baseBuilt, true,
                baseDistance, baseSafe, false, null, null, contacts);
    }

    private static Contact enemy(UUID id, double distance, double health, double gear, boolean fightingOther) {
        return new Contact(id, distance, health, 20, gear, true, fightingOther, false, false);
    }

    private static Behaviour choice(BotBrain brain, Situation s) {
        return brain.decide(s).behaviour();
    }

    @Test
    void huntedByTwoAFortressHoldsItsBaseWhereAHunterFights() {
        Situation s = fight(20, List.of(enemy(A, 12, 20, IRON, false), enemy(B, 14, 20, IRON, false)), true, 5,
                true);
        assertEquals(Behaviour.AVOID, choice(neutral(), s), "the plain mind backs off");
        assertEquals(Behaviour.DEFEND_BASE, choice(brain(BotPersonality.FORTRESS), s));
        assertEquals(Behaviour.DEFEND_BASE, choice(brain(BotPersonality.TRAPPER), s), "lures them to its traps");
        assertEquals(Behaviour.ENGAGE, choice(brain(BotPersonality.HUNTER), s));
    }

    @Test
    void anEnemyWalkingUpToTheBaseIsFoughtFromItsWalls() {
        Situation s = fight(20, List.of(new Contact(A, 20, 20, 20, IRON, true, false, true, false)), true, 6, true);
        assertEquals(Behaviour.DEFEND_BASE, choice(brain(BotPersonality.FORTRESS), s), "slit and doorway");
        assertEquals(Behaviour.ENGAGE, choice(brain(BotPersonality.BERSERKER), s), "a berserker runs out at them");
        Situation far = fight(20, List.of(new Contact(A, 20, 20, 20, IRON, true, false, true, false)), true, 40,
                true);
        assertNotEquals(Behaviour.DEFEND_BASE, choice(brain(BotPersonality.HUNTER), far));
    }

    @Test
    void hurtABerserkerKeepsFightingWhereASurvivorRetreats() {
        Situation s = fight(8, List.of(enemy(A, 6, 20, IRON, false)), false, Double.POSITIVE_INFINITY, false);
        Behaviour plain = choice(neutral(), s);
        assertTrue(plain == Behaviour.RETREAT_HEAL || plain == Behaviour.FORTIFY, plain.name());
        assertEquals(Behaviour.ENGAGE, choice(brain(BotPersonality.BERSERKER), s));
        assertEquals(Behaviour.RETREAT_HEAL, choice(brain(BotPersonality.SURVIVOR), s));
    }

    @Test
    void anOpportunistWaitsOutAFightABerserkerJoins() {
        Situation s = fight(20, List.of(new Contact(A, 20, 20, 20, IRON, true, true, false, false)), false,
                Double.POSITIVE_INFINITY, false);
        assertEquals(Behaviour.THIRD_PARTY, choice(brain(BotPersonality.OPPORTUNIST), s));
        assertEquals(Behaviour.ENGAGE, choice(brain(BotPersonality.BERSERKER), s));
    }

    @Test
    void anArcherKitesAndANomadAvoidsBeingCornered() {
        Situation duel = fight(20, List.of(enemy(A, 20, 20, IRON, false)), false, Double.POSITIVE_INFINITY, false);
        assertEquals(Behaviour.ENGAGE, choice(neutral(), duel));
        assertEquals(Behaviour.KITE, choice(brain(BotPersonality.ARCHER), duel));
        assertTrue(brain(BotPersonality.ARCHER).bowRange() > neutral().bowRange(), "shoots from further away");
        Situation two = fight(20, List.of(enemy(A, 12, 20, IRON, false), enemy(B, 14, 20, IRON, false)), false,
                Double.POSITIVE_INFINITY, false);
        Behaviour nomad = choice(brain(BotPersonality.NOMAD), two);
        assertTrue(nomad == Behaviour.AVOID || nomad == Behaviour.KITE, nomad.name());
    }

    @Test
    void aNomadNeverBuildsAndAHunterRarelyDoes() {
        Situation farming = new Situation(false, 0.8, 20, 0, 20, 0, false, IRON, false, 100, 100, false, 50, 12,
                false, false, Double.POSITIVE_INFINITY, false, false, null, null, List.of());
        assertEquals(Behaviour.BUILD_BASE, choice(neutral(), farming));
        assertEquals(Behaviour.BUILD_BASE, choice(brain(BotPersonality.FORTRESS), farming));
        assertEquals(Behaviour.FARM, choice(brain(BotPersonality.NOMAD), farming));
        assertTrue(brain(BotPersonality.NOMAD).options(farming).stream()
                .noneMatch(d -> d.behaviour() == Behaviour.BUILD_BASE), "never");
        assertEquals(Behaviour.FARM, choice(brain(BotPersonality.HUNTER), farming), "roams instead");
        assertFalse(BotPersonality.NOMAD.traits(new Random(3)).builds());
    }

    @Test
    void anAvengerHuntsWhoeverWrongedIt() {
        Situation s = fight(20, List.of(enemy(A, 20, 20, IRON, false),
                new Contact(B, 24, 20, 20, DIAMOND, true, false, false, false, true)), false,
                Double.POSITIVE_INFINITY, false);
        assertEquals(A, neutral().decide(s).target(), "the easier one");
        BotBrain avenger = brain(BotPersonality.AVENGER);
        assertEquals(Behaviour.ENGAGE, choice(avenger, s));
        assertEquals(B, avenger.decide(s).target(), "the one it holds a grudge against");
    }

    @Test
    void aGamblerIsUnpredictableAndThePlainMindIsNot() {
        Situation s = fight(20, List.of(enemy(A, 15, 20, IRON, false)), true, 10, true);
        Set<Behaviour> gambler = EnumSet.noneOf(Behaviour.class);
        Set<Behaviour> plain = EnumSet.noneOf(Behaviour.class);
        BotBrain g = new BotBrain(BotProfile.preset(BotDifficulty.NORMAL),
                BotPersonality.GAMBLER.traits(new Random(7)), new Random(7));
        BotBrain n = neutral();
        for (int i = 0; i < 300; i++) {
            gambler.add(choice(g, s));
            plain.add(choice(n, s));
        }
        assertEquals(1, plain.size());
        assertTrue(gambler.size() >= 2, "now a dive, now a bow duel or a retreat: " + gambler);
    }

    @Test
    void aBaseLoverRebuildsItsBaseWhereTheZoneWillBe() {
        Situation lost = new Situation(true, 0, 20, 0, 20, 3, true, IRON, true, 150, 100, true, 5, 12, true, true,
                30, false, false, null, null, List.of(), 0.2);
        assertEquals(Behaviour.BUILD_BASE, choice(brain(BotPersonality.FORTRESS), lost));
        assertNotEquals(Behaviour.BUILD_BASE, choice(brain(BotPersonality.HUNTER), lost));
        assertNotEquals(Behaviour.BUILD_BASE, choice(neutral(), lost));
    }

    @Test
    void personalitiesFadeLateSoMatchesStillEnd() {
        // in the last few's showdown even a survivor fights
        Situation showdown = new Situation(true, 0, 20, 0, 20, 3, true, IRON, true, 100, 100, true, 5, 12, false,
                true, Double.POSITIVE_INFINITY, false, false, null, null, List.of(enemy(A, 12, 20, IRON, false)),
                1, true, 1, 0);
        assertEquals(Behaviour.ENGAGE, choice(brain(BotPersonality.SURVIVOR), showdown));
        assertEquals(Behaviour.ENGAGE, choice(brain(BotPersonality.OPPORTUNIST), showdown));
    }

    @Test
    void aLineupShowsEveryPersonalityBeforeAnyRepeats() {
        Map<BotPersonality, Double> all = new EnumMap<>(BotPersonality.class);
        for (BotPersonality each : BotPersonality.values()) {
            all.put(each, 1.0);
        }
        assertEquals(12, BotPersonality.values().length);
        List<BotPersonality> twelve = BotPersonality.lineup(all, 12, new Random(5));
        assertEquals(12, new HashSet<>(twelve).size(), "twelve bots, twelve personalities");
        List<BotPersonality> fifteen = BotPersonality.lineup(all, 15, new Random(6));
        assertEquals(12, new HashSet<>(fifteen).size());
        Map<BotPersonality, Double> some = new EnumMap<>(all);
        some.put(BotPersonality.BERSERKER, 0.0);
        some.put(BotPersonality.NOMAD, 0.0);
        for (int seed = 0; seed < 20; seed++) {
            List<BotPersonality> drawn = BotPersonality.lineup(some, 15, new Random(seed));
            assertFalse(drawn.contains(BotPersonality.BERSERKER) || drawn.contains(BotPersonality.NOMAD),
                    "weight 0: never");
        }
        Map<BotPersonality, Double> none = new EnumMap<>(BotPersonality.class);
        for (BotPersonality each : BotPersonality.values()) {
            none.put(each, 0.0);
        }
        List<BotPersonality> plain = BotPersonality.lineup(none, 3, new Random(1));
        assertEquals(3, plain.size());
        assertNull(plain.getFirst(), "all 0: no personalities");
    }

    @Test
    void twoBotsOfOnePersonalityStillDiffer() {
        BotPersonality.Traits a = BotPersonality.HUNTER.traits(new Random(1));
        BotPersonality.Traits b = BotPersonality.HUNTER.traits(new Random(2));
        assertNotEquals(a.weights(), b.weights());
        assertNotEquals(a.aggressionShift(), b.aggressionShift());
        List<Double> scouts = new ArrayList<>();
        for (int seed = 0; seed < 10; seed++) {
            double w = BotPersonality.HUNTER.traits(new Random(seed)).weight(Behaviour.SCOUT);
            assertTrue(w > 1.3 && w < 1.7, "around the archetype's 1.5: " + w);
            scouts.add(w);
        }
        assertTrue(new HashSet<>(scouts).size() > 5);
    }

    @Test
    void personalitiesAreConfigurableAndShownInTheName() {
        MemoryConfiguration config = new MemoryConfiguration();
        BotSettings.declareDefaults(config);
        BotSettings defaults = BotSettings.read(config);
        for (BotPersonality each : BotPersonality.values()) {
            assertEquals(1.0, defaults.personalities().get(each), each.key());
            assertEquals(each, BotPersonality.parse(each.koreanName()));
            assertEquals(each, BotPersonality.parse(each.key()));
        }
        config.set(BotSettings.PERSONALITIES + ".gambler", 0);
        config.set(BotSettings.PERSONALITIES + ".tank", 3.5);
        BotSettings read = BotSettings.read(config);
        assertEquals(0.0, read.personalities().get(BotPersonality.GAMBLER));
        assertEquals(3.5, read.personalities().get(BotPersonality.TANK));
        assertEquals("철수 봇 [어려움·사냥꾼]", read.name(0, BotDifficulty.HARD, BotPersonality.HUNTER));
        assertEquals("영희 봇 [보통]", read.name(1, BotDifficulty.NORMAL, null));
    }
}
