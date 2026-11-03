package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.sx2xoox.mcpl.core.pack.ModelRef;
import org.jspecify.annotations.Nullable;

/**
 * The 공성전 v2 toy models of creatures and capture landmarks (made in {@code tools/assetgen/models/creatures.py} and
 * {@code toys.py}; kept in sync with their {@code CREATURES} / landmark constants): which item models a kind shows, at
 * which display scale and height above the mob's feet, and how each part moves.
 */
public final class CreatureModels {

    /** Minions (team-tinted). */
    public static final String SOLDIER = "soldier";
    public static final String ARCHER = "archer";
    public static final String CANNON = "cannon";
    /** Jungle monsters. */
    public static final String JELLY = "jelly";
    public static final String MOUSE = "mouse";
    public static final String TEDDY = "teddy";
    public static final String RABBIT = "rabbit";
    public static final String DRAGON = "dragon";
    /** The base shop keeper (태엽 상인). */
    public static final String MERCHANT = "merchant";
    /** Capture landmarks. */
    public static final String FOUNTAIN = "fountain";
    public static final String TELESCOPE = "telescope";

    /** How a part moves (one animation step every {@code moving} / {@code idle} ticks; 0 = still). */
    public enum Motion {
        /** Walks with a side-to-side waddle, breathes when idle (soldier, archer, teddy). */
        WADDLE(4, 20),
        /** Rolls along with a little rattle (cannon). */
        ROLL(3, 0),
        /** Squash and stretch, always (jelly). */
        WOBBLE(6, 8),
        /** A quick patter (mouse). */
        SCURRY(2, 0),
        /** Hops while it runs (rabbit). */
        HOP(6, 0),
        /** A heavy rocking stomp, breathes when idle (dragon). */
        STOMP(8, 20),
        /** A friendly bob and look-around (merchant). */
        BOB(20, 20),
        /** The dragon's wings flap. */
        FLAP(5, 10),
        /** A wind-up key on the back, turning about the forward axis. */
        KEY_Z(10, 10),
        /** A wind-up key on top, turning about the vertical axis. */
        KEY_Y(10, 10),
        /** Turns slowly about the vertical axis (the telescope sweeping). */
        SPIN(40, 40),
        /** Still. */
        NONE(0, 0);

        final int moving;
        final int idle;

        Motion(int moving, int idle) {
            this.moving = moving;
            this.idle = idle;
        }
    }

    /**
     * One item display of a creature or landmark.
     *
     * @param model  the model id ({@code creature/<kind>[_<part>]}, {@code structure/<id>})
     * @param scale  display scale
     * @param height model centre above the feet / base, blocks
     * @param motion how it moves
     */
    public record Part(String model, float scale, float height, Motion motion) {

        public ModelRef ref() {
            return new ModelRef(model);
        }
    }

    private static Part body(String kind, float scale, float height, Motion motion) {
        return new Part("creature/" + kind, scale, height, motion);
    }

    private static Part part(String kind, String part, float scale, float height, Motion motion) {
        return new Part("creature/" + kind + "_" + part, scale, height, motion);
    }

    /** Creature kind → its displays (the first is the body). */
    public static final Map<String, List<Part>> CREATURES = Map.of(
            SOLDIER, List.of(body(SOLDIER, 1, 1, Motion.WADDLE), part(SOLDIER, "key", 0.5f, 0.72f, Motion.KEY_Z)),
            ARCHER, List.of(body(ARCHER, 1, 1, Motion.WADDLE)),
            CANNON, List.of(body(CANNON, 1, 1, Motion.ROLL)),
            JELLY, List.of(body(JELLY, 1, 1, Motion.WOBBLE)),
            MOUSE, List.of(body(MOUSE, 1, 1, Motion.SCURRY), part(MOUSE, "key", 0.5f, 0.6f, Motion.KEY_Y)),
            TEDDY, List.of(body(TEDDY, 1, 1.4f, Motion.WADDLE)),
            RABBIT, List.of(body(RABBIT, 1, 1.1f, Motion.HOP)),
            DRAGON, List.of(body(DRAGON, 2.5f, 2, Motion.STOMP), part(DRAGON, "wings", 2, 2.3f, Motion.FLAP),
                    part(DRAGON, "key", 1, 3.05f, Motion.KEY_Y)),
            MERCHANT, List.of(body(MERCHANT, 1, 1, Motion.BOB), part(MERCHANT, "key", 0.5f, 0.8f, Motion.KEY_Z)));

    /** Landmark id → its displays (the owner's colour tints the pennants). */
    public static final Map<String, List<Part>> LANDMARKS = Map.of(
            FOUNTAIN, List.of(new Part("structure/fountain", 2, 2.5f, Motion.NONE)),
            TELESCOPE, List.of(new Part("structure/telescope", 3, 4.5f, Motion.NONE),
                    new Part("structure/telescope_scope", 2, 6.8f, Motion.SPIN)));

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("melee", SOLDIER), Map.entry("ranged", ARCHER), Map.entry("minion_melee", SOLDIER),
            Map.entry("minion_ranged", ARCHER), Map.entry("minion_cannon", CANNON), Map.entry("siege", CANNON),
            Map.entry("tank", CANNON), Map.entry("mice", MOUSE), Map.entry("bear", TEDDY), Map.entry("shop", MERCHANT),
            Map.entry("shopkeeper", MERCHANT), Map.entry("jellies", JELLY), Map.entry("slime", JELLY));

    private CreatureModels() {
    }

    /** The kind for an id or alias (any case: {@code MICE} → {@code mouse}, {@code melee} → {@code soldier}). */
    public static @Nullable String kindOf(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        String kind = ALIASES.getOrDefault(key, key);
        return CREATURES.containsKey(kind) || LANDMARKS.containsKey(kind) ? kind : null;
    }

    /** Every model of every creature and landmark. */
    public static Set<ModelRef> models() {
        Set<ModelRef> out = new LinkedHashSet<>();
        for (Map<String, List<Part>> table : List.of(CREATURES, LANDMARKS)) {
            table.keySet().stream().sorted().forEach(kind -> table.get(kind).forEach(p -> out.add(p.ref())));
        }
        return out;
    }
}
