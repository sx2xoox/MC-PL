package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.sx2xoox.mcpl.core.pack.ModelRef;
import org.bukkit.Particle;
import org.bukkit.Sound;
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

    /**
     * How a part moves by itself (one animation step every {@code moving} / {@code idle} ticks; 0 = still). The body
     * parts' own motions only name the model's character (the Python registry keeps them): how the whole model walks,
     * hops or breathes is its {@link Gait}, played on every part at once.
     */
    public enum Motion {
        /** Body: walks with a side-to-side waddle (soldier, archer, teddy); see {@link Gait}. */
        WADDLE(0, 0),
        /** Body: rolls along with a little rattle (cannon). */
        ROLL(0, 0),
        /** Body: squash and stretch, always (jelly). */
        WOBBLE(0, 0),
        /** Body: a quick patter (mouse). */
        SCURRY(0, 0),
        /** Body: hops while it runs (rabbit). */
        HOP(0, 0),
        /** Body: a heavy rocking stomp (dragon). */
        STOMP(0, 0),
        /** Body: a friendly bob and look-around (merchant). */
        BOB(0, 0),
        /** The dragon's wings flap (narrower and up every other step). */
        FLAP(5, 10),
        /** A wind-up key on the back, turning about the forward axis (a quarter turn a step). */
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
     * How the whole model sits at one moment of an attack, a flinch or a step, relative to its rest pose. Moves and
     * turns are about the creature's feet in its own frame (forward = where it looks), so every part moves together.
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

        static List<Key> breathing(int period) {
            return List.of(rest(0), of(period / 2, 0, 0, 0, 0, 0, 0.99f, 1.03f, 0), rest(period));
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
     * How a creature attacks or casts: keyframes from rest (tick 0) back to rest (the last key). {@link #impact()} is
     * the tick of the motion the blow lands on: a quick hit lands when its motion starts (the game's numbers never
     * wait for a motion), a skill with a warning starts its motion {@code impact} ticks before the blow lands (before
     * that the creature charges: {@code CreatureSkin.Visual#charge}).
     */
    public enum Attack {
        //                tick  forward  up    pitch  yaw  roll  width  height  wings
        /** 태엽 병정 총검 찌르기: winds back, then a long bayonet thrust, leaning into it. */
        THRUST(4, List.of(Key.rest(0),
                Key.of(2, -0.08f, 0, -6, 0, 0, 1, 1, 0),
                Key.of(4, 0.42f, 0, 12, 0, 0, 1, 1, 0),
                Key.of(6, 0.32f, 0, 8, 0, 0, 1, 1, 0),
                Key.rest(10)), List.of()),
        /** 장난감 궁수: draws the bow leaning back, lets go with a snap forward and a little recoil. */
        DRAW(4, List.of(Key.rest(0),
                Key.of(3, -0.12f, 0, -14, 0, 0, 1, 1, 0),
                Key.of(4, 0.08f, 0, 6, 0, 0, 1, 1, 0),
                Key.of(6, -0.04f, 0, -3, 0, 0, 1, 1, 0),
                Key.rest(9)), List.of()),
        /** 장난감 대포: kicks back with the muzzle up, a puff of smoke at the muzzle, rolls back into place. */
        RECOIL(1, List.of(Key.rest(0),
                Key.of(1, -0.32f, 0, -8, 0, 0, 1.06f, 0.92f, 0),
                Key.of(3, -0.36f, 0, -4, 0, 0, 1, 1, 0),
                Key.of(6, -0.1f, 0, 0, 0, 0, 1, 1, 0),
                Key.rest(10)),
                List.of(new Puff(1, Particle.CLOUD, 1.45f, 1.05f, 4, 0.12f, 0.06f),
                        new Puff(2, Particle.SMOKE, 1.35f, 1.1f, 3, 0.1f, 0.02f))),
        /** 젤리 몸통 박치기: crouches, springs up tall and thin, slams down flat ahead. */
        POUNCE(5, List.of(Key.rest(0),
                Key.of(1, 0, 0, 0, 0, 0, 1.15f, 0.8f, 0),
                Key.of(3, 0.2f, 0.5f, 0, 0, 0, 0.82f, 1.3f, 0),
                Key.of(5, 0.35f, 0, 0, 0, 0, 1.3f, 0.65f, 0),
                Key.of(7, 0.2f, 0, 0, 0, 0, 0.95f, 1.1f, 0),
                Key.rest(10)), List.of()),
        /** 젤리 끈적 웅덩이: swells up leaning back, then spits a blob forward with a squishy lurch. */
        GOOP(8, List.of(Key.rest(0),
                Key.of(3, -0.1f, 0, -10, 0, 0, 1.2f, 0.85f, 0),
                Key.of(6, -0.05f, 0.15f, -14, 0, 0, 0.85f, 1.25f, 0),
                Key.of(8, 0.25f, 0, 12, 0, 0, 1.25f, 0.8f, 0),
                Key.of(11, 0.05f, 0, 2, 0, 0, 0.95f, 1.08f, 0),
                Key.rest(14)),
                List.of(new Puff(8, Particle.ITEM_SLIME, 0.8f, 0.5f, 6, 0.2f, 0.12f))),
        /** 태엽 쥐: a short dart forward, head down, two quick nibbles, back. */
        NIBBLE(3, List.of(Key.rest(0),
                Key.of(1, -0.05f, 0, 0, 0, 0, 1.05f, 0.9f, 0),
                Key.of(3, 0.35f, 0, 10, 0, 0, 1, 1, 0),
                Key.of(4, 0.3f, 0, 4, 0, 0, 1, 1, 0),
                Key.of(5, 0.35f, 0, 10, 0, 0, 1, 1, 0),
                Key.rest(8)), List.of()),
        /** 태엽 쥐 흩어지기: rears up on its hind legs, squeaking, and looks left and right. */
        SQUEAK(2, List.of(Key.rest(0),
                Key.of(2, 0, 0.2f, -28, 0, 0, 0.95f, 1.1f, 0),
                Key.of(5, 0, 0.2f, -24, 15, 0, 0.95f, 1.1f, 0),
                Key.of(8, 0, 0.2f, -24, -15, 0, 0.95f, 1.1f, 0),
                Key.rest(11)), List.of()),
        /** 곰인형 앞발 내려치기: rears up leaning back, then brings both paws down in front. */
        SLAM(10, List.of(Key.rest(0),
                Key.of(4, 0, 0.1f, -14, 0, 0, 0.97f, 1.08f, 0),
                Key.of(8, 0, 0.14f, -18, 0, 0, 0.96f, 1.1f, 0),
                Key.of(10, 0.3f, 0, 20, 0, 0, 1.08f, 0.9f, 0),
                Key.of(13, 0.2f, 0, 12, 0, 0, 1.02f, 0.97f, 0),
                Key.rest(18)),
                List.of(new Puff(10, Particle.CLOUD, 1.4f, 0.1f, 4, 0.3f, 0.03f))),
        /** 곰인형 솜뭉치 충격파: crouches, jumps up and lands with a stomp that squashes it flat. */
        SHOCKWAVE(11, List.of(Key.rest(0),
                Key.of(4, 0, 0, 0, 0, 0, 1.12f, 0.82f, 0),
                Key.of(8, 0, 0.7f, -6, 0, 0, 0.92f, 1.12f, 0),
                Key.of(11, 0, 0, 0, 0, 0, 1.28f, 0.72f, 0),
                Key.of(14, 0, 0, 0, 0, 0, 0.97f, 1.06f, 0),
                Key.rest(18)),
                List.of(new Puff(11, Particle.CLOUD, 0, 0.2f, 6, 1.2f, 0.08f))),
        /** 시계 토끼 시곗바늘 던지기: winds back twisting to one side, flings the hand forward, and back. */
        THROW(5, List.of(Key.rest(0),
                Key.of(3, -0.1f, 0, -12, 22, -4, 1, 1, 0),
                Key.of(5, 0.12f, 0, 10, -18, 4, 1, 1, 0),
                Key.of(8, 0.04f, 0, 3, -6, 0, 1, 1, 0),
                Key.rest(11)), List.of()),
        /**
         * 시계 토끼 태엽 감기: spins round, shrinking to nothing (wound up, it leaves), then pops up big at its new place
         * and settles.
         */
        WIND(7, List.of(Key.rest(0),
                Key.of(2, 0, 0, 0, 90, 0, 1, 1.04f, 0),
                Key.of(4, 0, 0.05f, 0, 180, 0, 0.85f, 0.85f, 0),
                Key.of(5, 0, 0.15f, 0, 210, 0, 0.7f, 0.7f, 0),
                Key.of(6, 0, 0.25f, 0, 270, 0, 0.5f, 0.5f, 0),
                Key.of(7, 0, 0.35f, 0, 330, 0, 0.3f, 0.3f, 0),
                Key.of(9, 0, 0.1f, 0, 360, 0, 0.7f, 1.25f, 0),
                Key.of(12, 0, 0, 0, 360, 0, 1.12f, 0.88f, 0),
                Key.of(14, 0, 0, 0, 360, 0, 0.98f, 1.02f, 0),
                Key.rest(15)),
                List.of(new Puff(7, Particle.END_ROD, 0, 1, 6, 0.4f, 0.05f))),
        /** 장난감 드래곤 깨물기: rears its head back, then snaps forward. */
        BITE(6, List.of(Key.rest(0),
                Key.of(3, -0.15f, 0.05f, -10, 0, 0, 1, 1, 0.15f),
                Key.of(6, 0.45f, 0, 14, 0, 0, 1, 0.97f, -0.1f),
                Key.of(9, 0.3f, 0, 8, 0, 0, 1, 1, 0),
                Key.rest(14)), List.of()),
        /**
         * 장난감 드래곤 숨결: rears back with the wings wide open, lunges forward with a big flap and a breath of cloud
         * along the cone.
         */
        BREATH(7, List.of(Key.rest(0),
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
        /** 장난감 드래곤 꼬리 휩쓸기: crouches and spins once right round, the tail sweeping the ground. */
        TAIL(8, List.of(Key.rest(0),
                Key.of(2, 0, 0, 0, -20, 0, 1.04f, 0.94f, 0),
                Key.of(5, 0, 0, 2, 90, 4, 1.04f, 0.94f, -0.2f),
                Key.of(8, 0, 0, 2, 180, 4, 1.04f, 0.94f, -0.2f),
                Key.of(9, 0, 0, 2, 210, 4, 1.04f, 0.94f, -0.2f),
                Key.of(11, 0, 0, 0, 300, 2, 1.02f, 0.97f, 0),
                Key.of(13, 0, 0, 0, 360, 0, 0.98f, 1.04f, 0.1f),
                Key.rest(16)),
                List.of(new Puff(8, Particle.CLOUD, 0, 0.3f, 6, 1.6f, 0.06f))),
        /** 장난감 드래곤 날개 돌풍: rears up and beats its wings hard, twice, blowing clouds out all round. */
        GUST(6, List.of(Key.rest(0),
                Key.of(4, 0, 0.25f, -16, 0, 0, 1, 1, 0.8f),
                Key.of(6, 0, 0.3f, -10, 0, 0, 1, 1, -0.4f),
                Key.of(8, 0, 0.25f, -14, 0, 0, 1, 1, 0.9f),
                Key.of(10, 0, 0.2f, -6, 0, 0, 1, 1, -0.45f),
                Key.of(13, 0, 0.1f, -2, 0, 0, 1, 1, 0.5f),
                Key.rest(17)),
                List.of(new Puff(6, Particle.CLOUD, 0, 1.2f, 6, 1.8f, 0.2f),
                        new Puff(10, Particle.CLOUD, 0, 1.2f, 6, 2.2f, 0.2f))),
        /** 장난감 드래곤 격노: rears up with the wings wide, shaking its head as it roars. */
        ROAR(4, List.of(Key.rest(0),
                Key.of(4, 0, 0.2f, -24, 0, 0, 1, 1.06f, 0.6f),
                Key.of(8, 0, 0.2f, -26, 0, 4, 1, 1.06f, 0.7f),
                Key.of(10, 0, 0.2f, -26, 0, -4, 1, 1.06f, 0.7f),
                Key.of(12, 0, 0.2f, -24, 0, 4, 1, 1.06f, 0.6f),
                Key.rest(16)), List.of()),
        /** 태엽 상인 (never strikes; plays it when a player opens the shop at it): a polite bow. */
        BOW(0, List.of(Key.rest(0),
                Key.of(4, 0.05f, 0, 22, 0, 0, 1, 1, 0),
                Key.of(8, 0.05f, 0, 22, 0, 0, 1, 1, 0),
                Key.rest(14)), List.of());

        private final int impact;
        private final List<Key> keys;
        private final List<Puff> puffs;

        Attack(int impact, List<Key> keys, List<Puff> puffs) {
            this.impact = impact;
            this.keys = keys;
            this.puffs = puffs;
        }

        public List<Key> keys() {
            return keys;
        }

        public List<Puff> puffs() {
            return puffs;
        }

        /** The tick of the motion its blow lands on. */
        public int impact() {
            return impact;
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

    /**
     * The stagger when pushed (the creatures' own small knockback instead of vanilla's): leaning away from the blow, a
     * wobble back past upright, rest. Keys for a blow from straight ahead; {@link #staggerAt} turns it.
     */
    public static final List<Key> STAGGER = List.of(Key.rest(0), Key.of(2, -0.08f, 0.04f, -14, 0, 0, 1, 1, 0),
            Key.of(5, 0.02f, 0, 5, 0, 0, 1, 1, 0), Key.of(7, 0, 0, -2, 0, 0, 1, 1, 0), Key.rest(9));

    /** How long a stagger takes, ticks. */
    public static final int STAGGER_TICKS = STAGGER.getLast().tick();

    /**
     * The stagger at {@code tick} for a blow from {@code ahead} (1 = from straight in front, -1 = from behind) and
     * {@code right} (1 = from its right side), as strong as {@code strength} (0..1).
     */
    public static Offset staggerAt(int tick, float ahead, float right, float strength) {
        Offset base = sample(STAGGER, tick);
        return new Offset(base.forward() * ahead * strength, base.up() * strength, base.pitch() * ahead * strength,
                0, base.pitch() * right * strength, 1, 1, 0);
    }

    /** The shiver of a creature charging a skill (its warning): a quick tremble side to side, a little crouched. */
    public static Offset chargeAt(int tick) {
        float side = (Math.floorMod(tick, 4) < 2 ? 1 : -1) * 3f;
        return new Offset(0, 0, -3, 0, side, 1.05f, 0.94f, 0.15f);
    }

    /**
     * How a creature's whole model moves on its own: the {@link #walk} keys loop while the body walks (smoothly, under
     * it: the toy's steps, hops and bounces are this pose, never the body's), the {@link #idle} keys loop while it
     * stands. A foot lands on the walk ticks of {@link #stepsAt} (a soft step sound; the bodies are silent).
     */
    public enum Gait {
        //                    tick  forward  up    pitch  yaw  roll  width  height  wings
        /** 태엽 병정·장난감 궁수: a stiff wind-up march, rocking side to side, bobbing at each step. */
        MARCH(List.of(Key.of(0, 0, 0, 3, 0, -7, 1, 1, 0),
                Key.of(2, 0, 0.07f, 3, 0, 0, 1, 1, 0),
                Key.of(4, 0, 0, 3, 0, 7, 1, 1, 0),
                Key.of(6, 0, 0.07f, 3, 0, 0, 1, 1, 0),
                Key.of(8, 0, 0, 3, 0, -7, 1, 1, 0)),
                Key.breathing(40), new int[] {0, 4}, Sound.BLOCK_BAMBOO_WOOD_STEP, 0.12f, 1.8f),
        /** 장난감 대포: trundles on its wheels with a little rattle. */
        TRUNDLE(List.of(Key.rest(0),
                Key.of(1, 0, 0.04f, -2, 0, 2, 1, 1, 0),
                Key.of(3, 0, 0, 0, 0, 0, 1, 1, 0),
                Key.of(4, 0, 0.03f, -1, 0, -2, 1, 1, 0),
                Key.rest(6)),
                Key.breathing(60), new int[] {3}, Sound.BLOCK_WOOD_STEP, 0.1f, 1.7f),
        /** 젤리: bounces along, squashing flat as it lands and stretching tall as it springs. */
        BOUNCE(List.of(Key.of(0, 0, 0, 0, 0, 0, 1.18f, 0.78f, 0),
                Key.of(3, 0, 0.1f, -6, 0, 0, 0.9f, 1.2f, 0),
                Key.of(6, 0, 0.55f, 4, 0, 0, 0.88f, 1.18f, 0),
                Key.of(9, 0, 0.45f, 8, 0, 0, 0.95f, 1.08f, 0),
                Key.of(11, 0, 0.05f, 0, 0, 0, 1.08f, 0.9f, 0),
                Key.of(12, 0, 0, 0, 0, 0, 1.22f, 0.74f, 0),
                Key.of(14, 0, 0, 0, 0, 0, 1.18f, 0.78f, 0)),
                List.of(Key.of(0, 0, 0, 0, 0, 0, 1.05f, 0.94f, 0), Key.of(8, 0, 0, 0, 0, 0, 0.96f, 1.05f, 0),
                        Key.of(16, 0, 0, 0, 0, 0, 1.05f, 0.94f, 0)),
                new int[] {12}, Sound.BLOCK_HONEY_BLOCK_STEP, 0.35f, 1.6f),
        /** 태엽 쥐: a quick pitter-patter; standing, it sniffs about. */
        SCAMPER(List.of(Key.of(0, 0, 0, 4, 0, 0, 1, 1, 0),
                Key.of(1, 0, 0.05f, -3, 0, 0, 1, 1, 0),
                Key.of(2, 0, 0, 4, 0, 3, 1, 1, 0),
                Key.of(3, 0, 0.05f, -3, 0, 0, 1, 1, 0),
                Key.of(4, 0, 0, 4, 0, 0, 1, 1, 0)),
                List.of(Key.rest(0), Key.of(10, 0, 0, -8, 12, 0, 1, 1, 0), Key.of(16, 0, 0, -6, -10, 0, 1, 1, 0),
                        Key.rest(22), Key.rest(30)),
                new int[] {}, Sound.BLOCK_NOTE_BLOCK_HAT, 0, 1),
        /** 곰인형 대장: a heavy plush waddle, rocking hard and landing each step with a soft thud. */
        LUMBER(List.of(Key.of(0, 0, 0, 0, 0, -9, 1.04f, 0.96f, 0),
                Key.of(4, 0, 0.12f, 2, 0, -4, 1, 1, 0),
                Key.of(8, 0, 0, 0, 0, 9, 1.04f, 0.96f, 0),
                Key.of(12, 0, 0.12f, 2, 0, 4, 1, 1, 0),
                Key.of(16, 0, 0, 0, 0, -9, 1.04f, 0.96f, 0)),
                Key.breathing(40), new int[] {0, 8}, Sound.BLOCK_WOOL_FALL, 0.8f, 0.6f),
        /** 시계 토끼: short springy hops, crouching before each and squashing as it lands. */
        BUNNY(List.of(Key.of(0, 0, 0, 0, 0, 0, 1.1f, 0.86f, 0),
                Key.of(2, 0, 0.2f, 10, 0, 0, 0.92f, 1.12f, 0),
                Key.of(5, 0, 0.62f, 2, 0, 0, 0.96f, 1.06f, 0),
                Key.of(8, 0, 0.25f, -6, 0, 0, 1, 1, 0),
                Key.of(10, 0, 0, 0, 0, 0, 1.12f, 0.86f, 0),
                Key.of(12, 0, 0, 0, 0, 0, 1.1f, 0.86f, 0)),
                Key.breathing(30), new int[] {10}, Sound.BLOCK_WOOL_STEP, 0.4f, 1.6f),
        /** 장난감 드래곤: a slow, grand stride, rocking and half opening its wings at each step. */
        STRIDE(List.of(Key.of(0, 0, 0, 2, 0, -5, 1.02f, 0.97f, 0.1f),
                Key.of(6, 0, 0.14f, -2, 0, -2, 1, 1, 0.45f),
                Key.of(12, 0, 0, 2, 0, 5, 1.02f, 0.97f, 0.05f),
                Key.of(18, 0, 0.14f, -2, 0, 2, 1, 1, 0.45f),
                Key.of(24, 0, 0, 2, 0, -5, 1.02f, 0.97f, 0.1f)),
                List.of(Key.rest(0), Key.of(20, 0, 0.02f, -2, 0, 0, 1, 1.03f, 0.15f), Key.rest(40)),
                new int[] {0, 12}, Sound.BLOCK_WOOL_FALL, 1f, 0.5f),
        /** 태엽 상인: stands at its stall bobbing and looking about (it never walks). */
        STALL(List.of(Key.rest(0), Key.rest(20)),
                List.of(Key.rest(0), Key.of(10, 0, 0.05f, 0, 6, 0, 1, 1, 0), Key.rest(20),
                        Key.of(30, 0, 0.05f, 0, -6, 0, 1, 1, 0), Key.rest(40)),
                new int[] {}, Sound.BLOCK_NOTE_BLOCK_HAT, 0, 1);

        private final List<Key> walk;
        private final List<Key> idle;
        private final int[] steps;
        private final Sound step;
        private final float volume;
        private final float pitch;

        Gait(List<Key> walk, List<Key> idle, int[] steps, Sound step, float volume, float pitch) {
            this.walk = walk;
            this.idle = idle;
            this.steps = steps;
            this.step = step;
            this.volume = volume;
            this.pitch = pitch;
        }

        public List<Key> walk() {
            return walk;
        }

        public List<Key> idle() {
            return idle;
        }

        /** Ticks of a walk cycle. */
        public int period() {
            return walk.getLast().tick();
        }

        /** Ticks of an idle cycle. */
        public int idlePeriod() {
            return idle.getLast().tick();
        }

        /** The pose {@code tick} ticks into walking (looping). */
        public Offset walkAt(int tick) {
            return loop(walk, tick);
        }

        /** The pose {@code tick} ticks into standing (looping). */
        public Offset idleAt(int tick) {
            return loop(idle, tick);
        }

        /** Whether a foot lands (a step is heard) {@code tick} ticks into walking. */
        public boolean stepsAt(int tick) {
            int at = Math.floorMod(tick, period());
            for (int s : steps) {
                if (s == at) {
                    return volume > 0;
                }
            }
            return false;
        }

        public Sound stepSound() {
            return step;
        }

        public float stepVolume() {
            return volume;
        }

        public float stepPitch() {
            return pitch;
        }

        private static Offset loop(List<Key> keys, int tick) {
            int period = keys.getLast().tick();
            int at = period <= 0 ? 0 : Math.floorMod(tick, period);
            for (int i = 1; i < keys.size(); i++) {
                Key to = keys.get(i);
                if (at <= to.tick()) {
                    Key from = keys.get(i - 1);
                    return from.offset().lerp(to.offset(), (at - from.tick()) / (float) (to.tick() - from.tick()));
                }
            }
            return keys.getFirst().offset();
        }
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

    /** Creature kind → its quick (basic) attack. */
    public static final Map<String, Attack> ATTACKS = Map.of(SOLDIER, Attack.THRUST, ARCHER, Attack.DRAW,
            CANNON, Attack.RECOIL, JELLY, Attack.POUNCE, MOUSE, Attack.NIBBLE, TEDDY, Attack.SLAM,
            RABBIT, Attack.THROW, DRAGON, Attack.BITE, MERCHANT, Attack.BOW);

    /** Creature kind → the motions of its skills (the jungle plays them; the attack previews draw them). */
    public static final Map<String, List<Attack>> SKILLS = Map.of(SOLDIER, List.of(), ARCHER, List.of(),
            CANNON, List.of(), JELLY, List.of(Attack.GOOP), MOUSE, List.of(Attack.SQUEAK),
            TEDDY, List.of(Attack.SHOCKWAVE), RABBIT, List.of(Attack.WIND),
            DRAGON, List.of(Attack.BREATH, Attack.TAIL, Attack.GUST, Attack.ROAR), MERCHANT, List.of());

    /** Creature kind → how it walks and stands. */
    public static final Map<String, Gait> GAITS = Map.of(SOLDIER, Gait.MARCH, ARCHER, Gait.MARCH,
            CANNON, Gait.TRUNDLE, JELLY, Gait.BOUNCE, MOUSE, Gait.SCAMPER, TEDDY, Gait.LUMBER, RABBIT, Gait.BUNNY,
            DRAGON, Gait.STRIDE, MERCHANT, Gait.STALL);

    /**
     * Creature kind → the size ({@code Attribute.SCALE}) of its invisible body at size 1 ({@link CreatureBodies}: the
     * same neutral mob for every kind, 0.9 × 1.3 blocks unscaled), so the body is about as wide as the model and no
     * taller: it is what abilities hit and what the pathfinder walks.
     */
    public static final Map<String, Double> BODY_SCALES = Map.of(SOLDIER, 1.15, ARCHER, 1.05, CANNON, 1.0,
            JELLY, 0.68, MOUSE, 0.55, TEDDY, 1.7, RABBIT, 1.25, DRAGON, 2.6, MERCHANT, 1.2);

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

    /**
     * Creature kind → the top of its model above the feet, blocks (the highest box of {@code creatures.py}, every
     * part included). The vanilla body under it is often much shorter or taller (a rabbit at scale 2 is 1 block
     * tall, its model 2.5), so whatever floats over a creature (health bar, damage numbers) is placed by the model for
     * pack players.
     */
    public static final Map<String, Double> TOPS = Map.of(SOLDIER, 2.09, ARCHER, 1.86, CANNON, 1.36, JELLY, 0.82,
            MOUSE, 0.68, TEDDY, 2.74, RABBIT, 2.51, DRAGON, 4.14, MERCHANT, 2.22);

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
