package io.github.sx2xoox.mcpl.game.siege.creature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Attack;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Gait;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Key;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Offset;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Part;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Puff;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.ObjectiveKind;
import io.github.sx2xoox.mcpl.game.siege.jungle.JungleSettings;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionSettings;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The attack motions: every kind has its own, each starts and ends at rest, and the poses {@link ModelCreatureLook}
 * shows for them are exported to {@code tools/assetgen/models/creature_attacks.json}, which the attack preview GIFs
 * ({@code tools/assetgen/attack_preview.py}) draw, so the previews are the game's numbers
 * ({@code ./gradlew updateCreatureAttacks} after changing them).
 */
class CreatureAttacksExportTest {

    private static final Path EXPORT = Path.of("tools/assetgen/models/creature_attacks.json");

    @Test
    void everyCreatureHasItsOwnAttackFromRestToRest() {
        assertEquals(CreatureModels.CREATURES.keySet(), CreatureModels.ATTACKS.keySet());
        assertEquals(CreatureModels.CREATURES.keySet(), CreatureModels.SKILLS.keySet());
        assertEquals(CreatureModels.CREATURES.keySet(), CreatureModels.GAITS.keySet());
        assertEquals(CreatureModels.CREATURES.keySet(), CreatureModels.BODY_SCALES.keySet());
        assertEquals(CreatureModels.ATTACKS.size(), new HashSet<>(CreatureModels.ATTACKS.values()).size(),
                "no two kinds share a motion");
        Set<Attack> used = new HashSet<>(CreatureModels.ATTACKS.values());
        CreatureModels.SKILLS.values().forEach(skills -> skills.forEach(skill -> assertTrue(used.add(skill),
                skill + " belongs to one kind, once")));
        assertEquals(Set.of(Attack.values()), used, "every motion is someone's");
        for (Attack attack : Attack.values()) {
            List<Key> keys = attack.keys();
            assertEquals(0, keys.getFirst().tick(), attack + " starts at once");
            assertEquals(Offset.REST, keys.getFirst().offset());
            assertEquals(Offset.REST, keys.getLast().offset(), attack + " ends at rest");
            assertTrue(attack.ticks() <= 20, attack + " is over within a second");
            assertTrue(attack.impact() >= 0 && attack.impact() < attack.ticks(), attack + " lands while it plays");
            for (int i = 1; i < keys.size(); i++) {
                assertTrue(keys.get(i).tick() > keys.get(i - 1).tick(), attack + ": keys in order");
            }
            for (Puff puff : attack.puffs()) {
                assertTrue(puff.tick() >= 1 && puff.tick() <= attack.ticks() && puff.count() <= 6,
                        attack + ": a few particles while it plays");
            }
            assertEquals(Offset.REST, attack.at(attack.ticks()));
            assertEquals(Offset.REST, attack.at(attack.ticks() + 3));
        }
        assertEquals(Offset.REST, CreatureModels.hitAt(0));
        assertTrue(CreatureModels.hitAt(1).height() < 1, "a hit squashes");
        assertEquals(Offset.REST, CreatureModels.hitAt(CreatureModels.HIT_TICKS));
        // the directional, held flinch: leans away from the blow, holds the impact pose through the hitstop
        Offset impact = CreatureModels.hitAt(1, 2, 1, 0, 1);
        assertTrue(impact.pitch() < -10 && impact.forward() < 0, "a blow from ahead knocks it back: " + impact);
        assertEquals(impact, CreatureModels.hitAt(3, 2, 1, 0, 1), "held through the hitstop");
        assertNotEquals(impact, CreatureModels.hitAt(4, 2, 1, 0, 1), "then it springs back");
        assertEquals(Offset.REST, CreatureModels.hitAt(CreatureModels.HIT_TICKS + 2, 2, 1, 0, 1));
        assertTrue(CreatureModels.hitAt(1, 0, -1, 0, 1).pitch() > 0, "from behind it pitches forward");
        assertTrue(CreatureModels.hitAt(1, 0, 0, 1, 1).roll() < 0, "from its right it tilts to its left");
        assertTrue(CreatureModels.hitAmount(CreatureModels.DRAGON) < 0.5f
                && CreatureModels.hitAmount(CreatureModels.TEDDY) < CreatureModels.hitAmount(CreatureModels.SOLDIER)
                && CreatureModels.hitAmount(CreatureModels.MOUSE) > 1.2f, "big toys flinch little, small ones a lot");
        assertTrue(CreatureModels.hitAt(1, 0, 1, 0, 1.4f).height() < impact.height(), "a bigger flinch squashes more");
        assertEquals(Offset.REST, CreatureModels.staggerAt(CreatureModels.STAGGER_TICKS, 1, 0, 1));
        assertTrue(CreatureModels.staggerAt(2, 1, 0, 1).pitch() < 0, "a blow from ahead leans it back");
        assertTrue(CreatureModels.staggerAt(2, -1, 0, 1).pitch() > 0, "from behind, forward");
        assertTrue(CreatureModels.staggerAt(2, 0, 1, 1).roll() < 0, "from its right, it tilts to its left");
    }

    @Test
    void theKindsMoveDifferently() {
        // the strongest moment of each motion
        Offset thrust = peak(Attack.THRUST);
        assertTrue(thrust.forward() > 0.3 && thrust.pitch() > 0, "the soldier lunges, leaning in");
        Offset draw = Attack.DRAW.at(3);
        assertTrue(draw.forward() < 0 && draw.pitch() < 0, "the archer leans back to draw");
        Offset recoil = Attack.RECOIL.at(1);
        assertTrue(recoil.forward() < -0.2 && recoil.pitch() < 0, "the cannon kicks back, muzzle up");
        assertTrue(Attack.POUNCE.at(3).height() > 1.2 && Attack.POUNCE.at(3).up() > 0, "the jelly springs up tall");
        assertTrue(Attack.POUNCE.at(5).height() < 0.7, "and slams flat");
        assertTrue(Attack.SLAM.at(8).pitch() < -10 && Attack.SLAM.at(Attack.SLAM.impact()).pitch() > 10,
                "the teddy rears up, then brings its paws down as the blow lands");
        assertTrue(Attack.SHOCKWAVE.at(8).up() > 0.5 && Attack.SHOCKWAVE.at(Attack.SHOCKWAVE.impact()).height() < 0.8,
                "jumps, and lands flat with the shockwave");
        assertTrue(Attack.WIND.at(Attack.WIND.impact()).height() < 0.4, "the rabbit is wound down to nothing");
        assertTrue(Attack.BREATH.at(4).pitch() < 0 && Attack.BREATH.at(7).pitch() > 0, "the dragon rears, lunges");
        assertTrue(Attack.BREATH.at(4).wings() > 0.3, "wings wide open");
        assertTrue(Attack.TAIL.at(Attack.TAIL.impact()).yaw() >= 150, "the tail sweeps round");
        assertTrue(Attack.GUST.at(8).wings() > 0.8, "a huge wing beat");
        assertTrue(!Attack.RECOIL.puffs().isEmpty() && !Attack.BREATH.puffs().isEmpty(), "smoke and breath");

        // the same moment of the motion puts each kind's body somewhere else
        Set<String> poses = new HashSet<>();
        for (String kind : CreatureModels.CREATURES.keySet()) {
            Part body = CreatureModels.CREATURES.get(kind).getFirst();
            Attack attack = CreatureModels.ATTACKS.get(kind);
            StringBuilder trace = new StringBuilder();
            for (int tick = 1; tick <= 6; tick++) {
                trace.append(json(ModelCreatureLook.pose(body, 0, false, attack.at(tick))));
            }
            assertTrue(poses.add(trace.toString()), kind + " moves like no other");
        }
    }

    /**
     * The toys walk their own way (not their bodies'): each walk cycle loops seamlessly, and the soldier rocks, the jelly
     * squashes and springs, the rabbit hops, the dragon half opens its wings.
     */
    @Test
    void everyKindHasItsOwnGait() {
        for (Gait gait : Gait.values()) {
            assertEquals(gait.walk().getFirst().offset(), gait.walk().getLast().offset(), gait + " loops");
            assertEquals(gait.idle().getFirst().offset(), gait.idle().getLast().offset(), gait + " idles in a loop");
            assertEquals(gait.walkAt(0), gait.walkAt(gait.period()), gait + " walk cycle");
            assertEquals(gait.walkAt(3), gait.walkAt(3 + 5 * gait.period()));
        }
        Gait march = CreatureModels.GAITS.get(CreatureModels.SOLDIER);
        assertTrue(march.walkAt(0).roll() < 0 && march.walkAt(4).roll() > 0, "the soldier rocks side to side");
        Gait bounce = CreatureModels.GAITS.get(CreatureModels.JELLY);
        assertTrue(bounce.walkAt(0).height() < 0.8 && bounce.walkAt(6).up() > 0.5, "the jelly lands flat, springs high");
        assertTrue(bounce.idleAt(0).height() != bounce.idleAt(8).height(), "a jelly wobbles even standing");
        Gait hop = CreatureModels.GAITS.get(CreatureModels.RABBIT);
        assertTrue(hop.walkAt(5).up() > 0.5, "the rabbit hops (its own short hop)");
        Gait stride = CreatureModels.GAITS.get(CreatureModels.DRAGON);
        assertTrue(stride.walkAt(6).wings() > 0.3, "the dragon half opens its wings each stride");
        assertTrue(stride.period() > march.period() * 2, "a grand, slow stride");
        assertTrue(CreatureModels.GAITS.get(CreatureModels.TEDDY).stepsAt(0), "the teddy's thud");
        assertFalse(CreatureModels.GAITS.get(CreatureModels.MOUSE).stepsAt(0), "the mice patter silently");
        Set<String> walks = new HashSet<>();
        for (Gait gait : Gait.values()) {
            StringBuilder trace = new StringBuilder();
            for (int tick = 0; tick < 12; tick++) {
                trace.append(gait.walkAt(tick));
            }
            assertTrue(walks.add(trace.toString()), gait + " walks like no other");
        }
    }

    @Test
    void thePoseMovesTheWholeModelAboutTheFeet() {
        Part body = new Part("creature/x", 1, 1, CreatureModels.Motion.NONE);
        Part wings = new Part("creature/x_wings", 2, 1.5f, CreatureModels.Motion.FLAP);
        Transformation rest = ModelCreatureLook.pose(body, 0, false, Offset.REST);
        assertEquals(new Vector3f(0, 1, 0), rest.getTranslation());
        // ahead is -z (the models face -z)
        Transformation ahead = ModelCreatureLook.pose(body, 0, false, new Offset(0.5f, 0, 0, 0, 0, 1, 1, 0));
        assertEquals(-0.5f, ahead.getTranslation().z, 1e-6);
        // leaning forward swings the centre ahead and down
        Transformation lean = ModelCreatureLook.pose(body, 0, false, new Offset(0, 0, 30, 0, 0, 1, 1, 0));
        assertTrue(lean.getTranslation().z < -0.4 && lean.getTranslation().y < 0.9, lean.getTranslation().toString());
        // a squash lowers the centre with the height
        Transformation flat = ModelCreatureLook.pose(body, 0, false, new Offset(0, 0, 0, 0, 0, 1.2f, 0.5f, 0));
        assertEquals(0.5f, flat.getTranslation().y, 1e-6);
        assertEquals(new Vector3f(1.2f, 0.5f, 1.2f), flat.getScale());
        Transformation spread = ModelCreatureLook.pose(wings, 0, false, new Offset(0, 0, 0, 0, 0, 1, 1, 0.5f));
        assertEquals(3f, spread.getScale().x, 1e-6);
        assertEquals(1f, ModelCreatureLook.pose(body, 0, false, new Offset(0, 0, 0, 0, 0, 1, 1, 0.5f)).getScale().x,
                1e-6, "only wings spread");
        assertNotEquals(rest, lean);
    }

    @Test
    void theExportedPosesAreUpToDate() throws IOException {
        String generated = export();
        if (Boolean.getBoolean("mcpl.updateDocs")) {
            Files.writeString(EXPORT, generated, StandardCharsets.UTF_8);
            return;
        }
        String current = Files.exists(EXPORT) ? Files.readString(EXPORT, StandardCharsets.UTF_8) : "";
        assertEquals(generated, current, EXPORT + " is out of date: run ./gradlew updateCreatureAttacks");
    }

    private static Offset peak(Attack attack) {
        Offset best = Offset.REST;
        for (int tick = 0; tick <= attack.ticks(); tick++) {
            if (attack.at(tick).forward() > best.forward()) {
                best = attack.at(tick);
            }
        }
        return best;
    }

    /**
     * Per kind: its parts; its basic attack (every tick, and the flinch) as before; every motion it plays (basic and
     * skills: ticks, impact, puffs, frames, the area its warning marks with the default settings); its walk and idle
     * cycles. Each frame is every part's display transformation.
     */
    static String export() {
        JungleSettings jungle = JungleSettings.read(new MemoryConfiguration());
        StringBuilder out = new StringBuilder("{\n");
        out.append("  \"generated\": \"CreatureAttacksExportTest (./gradlew updateCreatureAttacks): the poses ")
                .append("ModelCreatureLook shows; forward is -z, feet at y 0\",\n");
        out.append("  \"hitTicks\": ").append(CreatureModels.HIT_TICKS).append(",\n");
        ModelCreatureLook.Reaction reaction = ModelCreatureLook.Reaction.DEFAULT;
        out.append("  \"hitstop\": {\"normal\": ").append(reaction.hold(HitStrength.NORMAL)).append(", \"strong\": ")
                .append(reaction.hold(HitStrength.STRONG)).append("},\n");
        out.append("  \"flash\": {\"white\": ").append(reaction.whiteTicks()).append(", \"red\": ")
                .append(reaction.redTicks()).append("},\n");
        out.append("  \"creatures\": {\n");
        List<String> kinds = new ArrayList<>(new TreeSet<>(CreatureModels.CREATURES.keySet()));
        for (int k = 0; k < kinds.size(); k++) {
            String kind = kinds.get(k);
            List<Part> parts = CreatureModels.CREATURES.get(kind);
            Attack attack = CreatureModels.ATTACKS.get(kind);
            out.append("    \"").append(kind).append("\": {\n");
            out.append("      \"attack\": \"").append(name(attack)).append("\",\n");
            out.append("      \"ticks\": ").append(attack.ticks()).append(",\n");
            out.append("      \"parts\": [");
            for (int i = 0; i < parts.size(); i++) {
                out.append(i == 0 ? "" : ", ").append('"').append(parts.get(i).model()).append('"');
            }
            out.append("],\n");
            puffs(out, attack.puffs(), "      ");
            out.append("      \"frames\": [\n");
            for (int tick = 0; tick <= attack.ticks(); tick++) {
                frame(out, parts, attack.at(tick), 0, false, tick == attack.ticks());
            }
            out.append("      ],\n");
            // the hit reactions ModelCreatureLook plays for a blow from straight ahead: tick 0 = before, 1 = the blow
            // (its impact pose held through the hitstop), then the rebound; normal and strong
            for (HitStrength strength : List.of(HitStrength.NORMAL, HitStrength.STRONG)) {
                int hold = reaction.hold(strength);
                float amount = Math.min(2.2f, CreatureModels.hitAmount(kind) * strength.amount()
                        * reaction.intensity());
                int last = CreatureModels.HIT_TICKS + hold;
                out.append(strength == HitStrength.NORMAL ? "      \"hit\": [\n" : "      \"hitStrong\": [\n");
                for (int tick = 0; tick <= last; tick++) {
                    frame(out, parts, CreatureModels.hitAt(tick, hold, 1, 0, amount), 0, false, tick == last);
                }
                out.append("      ],\n");
            }
            List<Attack> motions = new ArrayList<>();
            motions.add(attack);
            motions.addAll(CreatureModels.SKILLS.get(kind));
            out.append("      \"motions\": {\n");
            for (int m = 0; m < motions.size(); m++) {
                Attack motion = motions.get(m);
                out.append("        \"").append(name(motion)).append("\": {\n");
                out.append("          \"ticks\": ").append(motion.ticks()).append(", \"impact\": ")
                        .append(motion.impact()).append(",\n");
                out.append("          \"area\": ").append(area(motion, jungle)).append(",\n");
                puffs(out, motion.puffs(), "          ");
                out.append("          \"frames\": [\n");
                for (int tick = 0; tick <= motion.ticks(); tick++) {
                    frame(out, parts, motion.at(tick), 0, false, tick == motion.ticks());
                }
                out.append("          ]\n");
                out.append(m == motions.size() - 1 ? "        }\n" : "        },\n");
            }
            out.append("      },\n");
            Gait gait = CreatureModels.GAITS.get(kind);
            out.append("      \"gait\": \"").append(gait.name().toLowerCase(Locale.ROOT)).append("\",\n");
            out.append("      \"walk\": [\n");
            for (int tick = 0; tick < gait.period(); tick++) {
                frame(out, parts, gait.walkAt(tick), tick, true, tick == gait.period() - 1);
            }
            out.append("      ],\n");
            out.append("      \"idle\": [\n");
            for (int tick = 0; tick < gait.idlePeriod(); tick++) {
                frame(out, parts, gait.idleAt(tick), tick, false, tick == gait.idlePeriod() - 1);
            }
            out.append("      ]\n");
            out.append(k == kinds.size() - 1 ? "    }\n" : "    },\n");
        }
        out.append("  }\n}\n");
        return out.toString();
    }

    private static String name(Attack attack) {
        return attack.name().toLowerCase(Locale.ROOT);
    }

    private static void puffs(StringBuilder out, List<Puff> puffs, String indent) {
        out.append(indent).append("\"puffs\": [");
        for (int i = 0; i < puffs.size(); i++) {
            Puff p = puffs.get(i);
            out.append(i == 0 ? "\n" : ",\n").append(indent).append(String.format(Locale.ROOT,
                    "  {\"tick\": %d, \"particle\": \"%s\", \"forward\": %s, \"up\": %s, \"count\": %d, "
                            + "\"spread\": %s, \"speed\": %s}",
                    p.tick(), p.particle().name().toLowerCase(Locale.ROOT), num(p.forward()), num(p.up()),
                    p.count(), num(p.spread()), num(p.speed())));
        }
        out.append(puffs.isEmpty() ? "],\n" : "\n" + indent + "],\n");
    }

    /**
     * The area a motion's warning marks on the ground with the default settings (null for none): circle or cone,
     * centred {@code forward} blocks ahead of the feet.
     */
    private static String area(Attack motion, JungleSettings jungle) {
        return switch (motion) {
            case GOOP -> circle(4, jungle.skill(ObjectiveKind.JELLY, JungleSettings.GOO).radius(), "sticky");
            case SLAM -> {
                double r = jungle.skill(ObjectiveKind.TEDDY, JungleSettings.SLAM).radius();
                yield circle(r * 0.9, r, "danger");
            }
            case SHOCKWAVE -> circle(0, jungle.skill(ObjectiveKind.TEDDY, JungleSettings.SHOCKWAVE).radius(), "danger");
            case TAIL -> circle(0, jungle.skill(ObjectiveKind.DRAGON, JungleSettings.TAIL).radius(), "danger");
            case GUST -> circle(0, jungle.skill(ObjectiveKind.DRAGON, JungleSettings.GUST).radius(), "push");
            case RECOIL -> circle(5, MinionSettings.Shots.DEFAULTS.splashRadius(), "danger");
            case BREATH -> {
                JungleSettings.Skill breath = jungle.skill(ObjectiveKind.DRAGON, JungleSettings.BREATH);
                yield "{\"shape\": \"cone\", \"forward\": 0.0000, \"radius\": " + num((float) breath.radius())
                        + ", \"angle\": " + num((float) breath.angle()) + ", \"colour\": \"danger\"}";
            }
            default -> "null";
        };
    }

    private static String circle(double forward, double radius, String colour) {
        return "{\"shape\": \"circle\", \"forward\": " + num((float) forward) + ", \"radius\": "
                + num((float) radius) + ", \"angle\": 0.0000, \"colour\": \"" + colour + "\"}";
    }

    private static void frame(StringBuilder out, List<Part> parts, Offset offset, int age, boolean moving,
            boolean last) {
        out.append("        [");
        for (int i = 0; i < parts.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(json(ModelCreatureLook.pose(parts.get(i), age, moving, offset)));
        }
        out.append(last ? "]\n" : "],\n");
    }

    private static String json(Transformation t) {
        Vector3f at = t.getTranslation();
        Quaternionf r = t.getLeftRotation();
        Vector3f s = t.getScale();
        return "{\"t\": [" + num(at.x) + ", " + num(at.y) + ", " + num(at.z) + "], \"r\": [" + num(r.x) + ", "
                + num(r.y) + ", " + num(r.z) + ", " + num(r.w) + "], \"s\": [" + num(s.x) + ", " + num(s.y) + ", "
                + num(s.z) + "]}";
    }

    private static String num(float v) {
        String out = String.format(Locale.ROOT, "%.4f", v);
        return out.equals("-0.0000") ? "0.0000" : out;
    }
}
