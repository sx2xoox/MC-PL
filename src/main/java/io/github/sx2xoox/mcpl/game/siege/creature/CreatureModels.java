package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.sx2xoox.mcpl.core.pack.ModelRef;
import org.bukkit.Particle;
import org.jspecify.annotations.Nullable;

/**
 * The 공성전 v2 toy models of creatures and capture landmarks (made in {@code tools/assetgen/models/creatures.py} and
 * {@code toys.py}; kept in sync with their {@code CREATURES} / landmark constants): which item models a kind shows, at
 * which display scale and height above the mob's feet, how each part moves, and how each kind attacks ({@link Attack}:
 * keyframes the whole model plays; the attack preview GIFs of {@code tools/assetgen/attack_preview.py} are drawn from
 * the poses {@code ModelCreatureLook} computes from them, exported to {@code tools/assetgen/models/creature_attacks.json}
 * by {@code ./gradlew updateCreatureAttacks}).
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
     * How the whole model sits at one moment of an attack or a flinch, relative to its rest pose. Moves and turns are
     * about the creature's feet in its own frame (forward = where it looks), so every part moves together.
     *
     * @param forward blocks towards where it looks (negative = back)
     * @param up      blocks up
     * @param pitch   degrees, leaning forward (negative = leaning back)
     * @param yaw     degrees, twisting to its left (negative = to its right)
     * @param roll    degrees, tilting towards its right side
     * @param width   scale across and in depth (a squash keeps its volume by widening)
     * @param height  scale up
     * @param wings   extra wing spread of {@link Motion#FLAP} parts (0.5 = half again as wide, negative = folded)
     */
    public record Offset(float forward, float up, float pitch, float yaw, float roll, float width, float height,
            float wings) {

        public static final Offset REST = new Offset(0, 0, 0, 0, 0, 1, 1, 0);

        /** Both at once (a flinch during an attack): moves and turns add up, scales multiply. */
        public Offset plus(Offset o) {
            return new Offset(forward + o.forward, up + o.up, pitch + o.pitch, yaw + o.yaw, roll + o.roll,
                    width * o.width, height * o.height, wings + o.wings);
        }

        Offset lerp(Offset to, float t) {
            return new Offset(mix(forward, to.forward, t), mix(up, to.up, t), mix(pitch, to.pitch, t),
                    mix(yaw, to.yaw, t), mix(roll, to.roll, t), mix(width, to.width, t), mix(height, to.height, t),
                    mix(wings, to.wings, t));
        }

        private static float mix(float a, float b, float t) {
            return a + (b - a) * t;
        }
    }

    /** The model reaches {@code offset} at {@code tick} (linear in between). */
    public record Key(int tick, Offset offset) {

        // here, not on CreatureModels: the Attack constants use them, and must not initialise CreatureModels first

        static Key of(int tick, float forward, float up, float pitch, float yaw, float roll, float width,
                float height, float wings) {
            return new Key(tick, new Offset(forward, up, pitch, yaw, roll, width, height, wings));
        }

        static Key rest(int tick) {
            return new Key(tick, Offset.REST);
        }
    }

    /**
     * A puff of particles at {@code tick} of an attack (everyone sees it; decoration only): {@code count} particles
     * {@code forward} blocks ahead of the feet and {@code up} blocks up, spread {@code spread}, drifting ahead at
     * {@code speed} blocks per tick.
     */
    public record Puff(int tick, Particle particle, float forward, float up, int count, float spread, float speed) {
    }

    /**
     * How a kind attacks: keyframes from rest (tick 0) back to rest (the last key). The hit lands when the attack
     * starts (the game's numbers never wait for a motion), so the strike keyframe comes within a few ticks.
     */
    public enum Attack {
        //                tick  forward  up    pitch  yaw  roll  width  height  wings
        /** 태엽 병정: winds back, then a long sabre thrust, leaning into it. */
        THRUST(List.of(Key.rest(0),
                Key.of(2, -0.08f, 0, -6, 0, 0, 1, 1, 0),
                Key.of(4, 0.42f, 0, 12, 0, 0, 1, 1, 0),
                Key.of(6, 0.32f, 0, 8, 0, 0, 1, 1, 0),
                Key.rest(10)), List.of()),
        /** 장난감 궁수: draws the bow leaning back, lets go with a snap forward and a little recoil. */
        DRAW(List.of(Key.rest(0),
                Key.of(3, -0.12f, 0, -14, 0, 0, 1, 1, 0),
                Key.of(4, 0.08f, 0, 6, 0, 0, 1, 1, 0),
                Key.of(6, -0.04f, 0, -3, 0, 0, 1, 1, 0),
                Key.rest(9)), List.of()),
        /** 장난감 대포: kicks back with the muzzle up, a puff of smoke at the muzzle, rolls back into place. */
        RECOIL(List.of(Key.rest(0),
                Key.of(1, -0.32f, 0, -8, 0, 0, 1.06f, 0.92f, 0),
                Key.of(3, -0.36f, 0, -4, 0, 0, 1, 1, 0),
                Key.of(6, -0.1f, 0, 0, 0, 0, 1, 1, 0),
                Key.rest(10)),
                List.of(new Puff(1, Particle.CLOUD, 1.45f, 1.05f, 4, 0.12f, 0.06f),
                        new Puff(2, Particle.SMOKE, 1.35f, 1.1f, 3, 0.1f, 0.02f))),
        /** 젤리: crouches, springs up tall and thin, slams down flat ahead. */
        POUNCE(List.of(Key.rest(0),
                Key.of(1, 0, 0, 0, 0, 0, 1.15f, 0.8f, 0),
                Key.of(3, 0.2f, 0.5f, 0, 0, 0, 0.82f, 1.3f, 0),
                Key.of(5, 0.35f, 0, 0, 0, 0, 1.3f, 0.65f, 0),
                Key.of(7, 0.2f, 0, 0, 0, 0, 0.95f, 1.1f, 0),
                Key.rest(10)), List.of()),
        /** 태엽 쥐: a short dart forward, head down, two quick nibbles, back. */
        NIBBLE(List.of(Key.rest(0),
                Key.of(1, -0.05f, 0, 0, 0, 0, 1.05f, 0.9f, 0),
                Key.of(3, 0.35f, 0, 10, 0, 0, 1, 1, 0),
                Key.of(4, 0.3f, 0, 4, 0, 0, 1, 1, 0),
                Key.of(5, 0.35f, 0, 10, 0, 0, 1, 1, 0),
                Key.rest(8)), List.of()),
        /** 곰인형 대장: twists to one side, swings both arms across with the whole body, and back the other way. */
        SWIPE(List.of(Key.rest(0),
                Key.of(2, 0, 0, 0, 20, -4, 1, 1, 0),
                Key.of(5, 0.15f, 0, 4, -25, 5, 1, 1, 0),
                Key.of(8, 0.1f, 0, 2, 18, -3, 1, 1, 0),
                Key.of(11, 0, 0, 0, -6, 0, 1, 1, 0),
                Key.rest(14)), List.of()),
        /**
         * 장난감 드래곤: rears back with the wings wide open, lunges forward with a big flap and a breath of cloud
         * (decoration: the damage is the strike's).
         */
        BREATH(List.of(Key.rest(0),
                Key.of(4, -0.15f, 0.1f, -14, 0, 0, 1, 1, 0.5f),
                Key.of(7, 0.3f, 0, 10, 0, 0, 1, 1, -0.35f),
                Key.of(9, 0.25f, 0, 8, 0, 0, 1, 1, 0.4f),
                Key.of(12, 0.2f, 0, 6, 0, 0, 1, 1, -0.3f),
                Key.of(16, 0.05f, 0, 2, 0, 0, 1, 1, 0),
                Key.rest(20)),
                List.of(new Puff(7, Particle.CLOUD, 3.3f, 2.4f, 5, 0.2f, 0.12f),
                        new Puff(8, Particle.CLOUD, 3.6f, 2.3f, 5, 0.3f, 0.12f),
                        new Puff(9, Particle.CLOUD, 3.9f, 2.2f, 5, 0.35f, 0.12f),
                        new Puff(10, Particle.CLOUD, 4.2f, 2.1f, 4, 0.4f, 0.1f),
                        new Puff(11, Particle.CLOUD, 4.5f, 2.0f, 3, 0.45f, 0.08f))),
        /** 시계 토끼 (never strikes; plays it when hit): a startled hop, leaning back, and a squashy landing. */
        STARTLE(List.of(Key.rest(0),
                Key.of(1, 0, 0, 0, 0, 0, 1.08f, 0.85f, 0),
                Key.of(3, -0.1f, 0.55f, -8, 0, 0, 0.92f, 1.15f, 0),
                Key.of(6, -0.1f, 0, 0, 0, 0, 1.1f, 0.85f, 0),
                Key.of(8, 0, 0, 0, 0, 0, 0.98f, 1.03f, 0),
                Key.rest(10)), List.of()),
        /** 태엽 상인 (never strikes; plays it when a player opens the shop at it): a polite bow. */
        BOW(List.of(Key.rest(0),
                Key.of(4, 0.05f, 0, 22, 0, 0, 1, 1, 0),
                Key.of(8, 0.05f, 0, 22, 0, 0, 1, 1, 0),
                Key.rest(14)), List.of());

        private final List<Key> keys;
        private final List<Puff> puffs;

        Attack(List<Key> keys, List<Puff> puffs) {
            this.keys = keys;
            this.puffs = puffs;
        }

        public List<Key> keys() {
            return keys;
        }

        public List<Puff> puffs() {
            return puffs;
        }

        /** How long it takes, ticks. */
        public int ticks() {
            return keys.getLast().tick();
        }

        /** The offset at {@code tick} (rest before the start and after the end). */
        public Offset at(int tick) {
            return sample(keys, tick);
        }
    }

    /** The flinch when hit (with the white flash): squashed for a tick, a springy rebound, rest. */
    public static final List<Key> HIT = List.of(Key.rest(0), Key.of(1, -0.05f, 0, -3, 0, 0, 1.12f, 0.82f, 0),
            Key.of(3, 0, 0, 0, 0, 0, 0.96f, 1.06f, 0), Key.rest(5));

    /** How long the flinch takes, ticks. */
    public static final int HIT_TICKS = HIT.getLast().tick();

    /** The flinch offset at {@code tick}. */
    public static Offset hitAt(int tick) {
        return sample(HIT, tick);
    }

    private static Offset sample(List<Key> keys, int tick) {
        if (tick <= keys.getFirst().tick() || tick >= keys.getLast().tick()) {
            return Offset.REST;
        }
        for (int i = 1; i < keys.size(); i++) {
            Key to = keys.get(i);
            if (tick <= to.tick()) {
                Key from = keys.get(i - 1);
                return from.offset().lerp(to.offset(), (tick - from.tick()) / (float) (to.tick() - from.tick()));
            }
        }
        return Offset.REST;
    }

    /** Creature kind → how it attacks. */
    public static final Map<String, Attack> ATTACKS = Map.of(SOLDIER, Attack.THRUST, ARCHER, Attack.DRAW,
            CANNON, Attack.RECOIL, JELLY, Attack.POUNCE, MOUSE, Attack.NIBBLE, TEDDY, Attack.SWIPE,
            RABBIT, Attack.STARTLE, DRAGON, Attack.BREATH, MERCHANT, Attack.BOW);

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
