package io.github.sx2xoox.mcpl.game.siege.creature;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Key;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Offset;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Part;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Puff;
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
        assertEquals(CreatureModels.ATTACKS.size(), new HashSet<>(CreatureModels.ATTACKS.values()).size(),
                "no two kinds share a motion");
        for (Attack attack : Attack.values()) {
            List<Key> keys = attack.keys();
            assertEquals(0, keys.getFirst().tick(), attack + " starts at once");
            assertEquals(Offset.REST, keys.getFirst().offset());
            assertEquals(Offset.REST, keys.getLast().offset(), attack + " ends at rest");
            assertTrue(attack.ticks() <= 20, attack + " is over within a second");
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
        assertTrue(Math.abs(Attack.SWIPE.at(2).yaw()) > 10 && Attack.SWIPE.at(2).yaw() * Attack.SWIPE.at(5).yaw() < 0,
                "the teddy twists one way, then the other");
        assertTrue(Attack.BREATH.at(4).pitch() < 0 && Attack.BREATH.at(7).pitch() > 0, "the dragon rears, lunges");
        assertTrue(Attack.BREATH.at(4).wings() > 0.3, "wings wide open");
        assertTrue(Attack.STARTLE.at(3).up() > 0.4, "the rabbit hops");
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

    /** Per kind: its parts, then every tick of its attack and of the flinch, each part's display transformation. */
    static String export() {
        StringBuilder out = new StringBuilder("{\n");
        out.append("  \"generated\": \"CreatureAttacksExportTest (./gradlew updateCreatureAttacks): the poses ")
                .append("ModelCreatureLook shows; forward is -z, feet at y 0\",\n");
        out.append("  \"hitTicks\": ").append(CreatureModels.HIT_TICKS).append(",\n");
        out.append("  \"creatures\": {\n");
        List<String> kinds = new ArrayList<>(new TreeSet<>(CreatureModels.CREATURES.keySet()));
        for (int k = 0; k < kinds.size(); k++) {
            String kind = kinds.get(k);
            List<Part> parts = CreatureModels.CREATURES.get(kind);
            Attack attack = CreatureModels.ATTACKS.get(kind);
            out.append("    \"").append(kind).append("\": {\n");
            out.append("      \"attack\": \"").append(attack.name().toLowerCase(Locale.ROOT)).append("\",\n");
            out.append("      \"ticks\": ").append(attack.ticks()).append(",\n");
            out.append("      \"parts\": [");
            for (int i = 0; i < parts.size(); i++) {
                out.append(i == 0 ? "" : ", ").append('"').append(parts.get(i).model()).append('"');
            }
            out.append("],\n");
            out.append("      \"puffs\": [");
            List<Puff> puffs = attack.puffs();
            for (int i = 0; i < puffs.size(); i++) {
                Puff p = puffs.get(i);
                out.append(i == 0 ? "\n" : ",\n").append(String.format(Locale.ROOT,
                        "        {\"tick\": %d, \"particle\": \"%s\", \"forward\": %s, \"up\": %s, \"count\": %d, "
                                + "\"spread\": %s, \"speed\": %s}",
                        p.tick(), p.particle().name().toLowerCase(Locale.ROOT), num(p.forward()), num(p.up()),
                        p.count(), num(p.spread()), num(p.speed())));
            }
            out.append(puffs.isEmpty() ? "],\n" : "\n      ],\n");
            out.append("      \"frames\": [\n");
            for (int tick = 0; tick <= attack.ticks(); tick++) {
                frame(out, parts, attack.at(tick), tick == attack.ticks());
            }
            out.append("      ],\n");
            out.append("      \"hit\": [\n");
            for (int tick = 0; tick <= CreatureModels.HIT_TICKS; tick++) {
                frame(out, parts, CreatureModels.hitAt(tick), tick == CreatureModels.HIT_TICKS);
            }
            out.append("      ]\n");
            out.append(k == kinds.size() - 1 ? "    }\n" : "    },\n");
        }
        out.append("  }\n}\n");
        return out.toString();
    }

    private static void frame(StringBuilder out, List<Part> parts, Offset offset, boolean last) {
        out.append("        [");
        for (int i = 0; i < parts.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(json(ModelCreatureLook.pose(parts.get(i), 0, false, offset)));
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
