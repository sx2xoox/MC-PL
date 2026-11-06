package io.github.sx2xoox.mcpl.game.superpower.basic;

import java.util.Locale;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.jspecify.annotations.Nullable;

/**
 * One ability's basic attack (평타, MapleStory style): what a left-click with the ability weapon does instead of a
 * plain sword swing. A {@link Shape} plus numbers, a small side {@link Effect} and a look ({@link Palette}); the
 * runtime's {@code BasicAttackRunner} executes every shape. The numbers are defaults that
 * {@link BasicAttackSettings} lets a server override per ability.
 *
 * @param id           the ability id
 * @param name         Korean name shown to players ("다지기 2연타")
 * @param description  Korean one-liner without numbers
 * @param shape        how it hits
 * @param damage       damage per hit (health points, before armor; the host scales it with levels and items)
 * @param cooldown     ticks from one attack to the next
 * @param range        reach in blocks (melee reach, ray length, projectile travel, cone length, nova radius)
 * @param hits         hits per attack: combo swings, shots, projectiles, chain targets, cone pulses
 * @param interval     ticks between those hits (STRIKE: the delay before it lands)
 * @param radius       width: thrust and projectile hit radius, splash radius, chain jump distance
 * @param angle        degrees: swing and cone width, projectile fan spread
 * @param speed        blocks per tick of a projectile or a lob
 * @param pierce       targets one swing, thrust or projectile may hit (0 = all)
 * @param falloff      damage factor per chain jump
 * @param effect       the side effect on each hit
 * @param effectPower  the effect's strength (slow/weaken amount, damage over time total, push speed, heal, share)
 * @param effectSeconds how long a timed effect lasts
 * @param palette      particles, color, sounds and the projectile item
 * @param fx           the display look: slash and beam blocks, the weapon that flashes, the hit weight, the signature
 */
public record BasicAttack(String id, String name, String description, Shape shape, double damage, int cooldown,
                          double range, int hits, int interval, double radius, double angle, double speed, int pierce,
                          double falloff, Effect effect, double effectPower, double effectSeconds, Palette palette,
                          Fx fx) {

    /** How an attack finds what it hits. */
    public enum Shape {
        /** A horizontal arc in front (every enemy inside, up to {@code pierce}). */
        SWING("휘두르기"),
        /** {@code hits} quick swings, {@code interval} ticks apart. */
        COMBO("연타"),
        /** A straight poke {@code range} long and {@code radius} wide, through enemies. */
        THRUST("찌르기"),
        /** An instant shot along the aim (the first enemy within {@code range}), {@code hits} shots per attack. */
        SHOT("사격"),
        /** {@code hits} flying projectiles fanned over {@code angle}, the first enemies they touch. */
        PROJECTILE("투사체"),
        /** A thrown object that falls in an arc and bursts with a {@code radius} splash. */
        LOB("투척"),
        /** A zap on the aimed enemy that jumps to {@code hits − 1} more within {@code radius}. */
        CHAIN("연쇄"),
        /** A short cone in front ({@code range} long, {@code angle} wide), {@code hits} pulses. */
        CONE("부채꼴"),
        /** A burst around the attacker ({@code range} radius). */
        NOVA("주변 파동"),
        /** A blow that lands on the aimed spot after {@code interval} ticks ({@code radius} splash). */
        STRIKE("지점 타격");

        private final String korean;

        Shape(String korean) {
            this.korean = korean;
        }

        public String korean() {
            return korean;
        }

        /** The config spelling, e.g. {@code swing}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The small extra on each hit. */
    public enum Effect {
        NONE("없음"),
        /** Movement speed −{@code effectPower} (fraction) for {@code effectSeconds}. */
        SLOW("감속"),
        /** {@code effectPower} more damage spread over {@code effectSeconds} (burn, poison, bleeding). */
        DOT("지속 피해"),
        /** Pushed away at {@code effectPower} blocks per tick. */
        KNOCKBACK("밀쳐내기"),
        /** Pulled toward the attacker at {@code effectPower} blocks per tick. */
        PULL("끌어당기기"),
        /** The attacker heals {@code effectPower} (fraction) of the damage dealt. */
        LIFESTEAL("흡혈"),
        /** Allies the attack passes or bursts on heal {@code effectPower}. */
        HEAL_ALLIES("아군 회복"),
        /** The attacker steps forward at {@code effectPower} blocks per tick as it strikes. */
        LUNGE("돌진"),
        /** The target's attack damage −{@code effectPower} for {@code effectSeconds}. */
        WEAKEN("약화");

        private final String korean;

        Effect(String korean) {
            this.korean = korean;
        }

        public String korean() {
            return korean;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        /** True if {@code effectSeconds} matters. */
        public boolean timed() {
            return this == SLOW || this == DOT || this == WEAKEN;
        }
    }

    /**
     * The look of an attack: {@code particle} (shown in {@code color} when it takes a color, else as is), the sound
     * of the attack and of a hit, and the item a flying projectile shows (null: particles only).
     */
    public record Palette(Particle particle, Color color, Sound sound, float pitch, Sound impact, float impactPitch,
                          @Nullable Material item) {

        public static Palette of(Particle particle, int rgb, Sound sound, float pitch, Sound impact,
                                 float impactPitch, @Nullable Material item) {
            return new Palette(particle, Color.fromRGB(rgb), sound, pitch, impact, impactPitch, item);
        }
    }

    /** What a hit stands for beyond its damage (the host shows it, e.g. as a bigger damage number). */
    public enum Accent {
        /** An ordinary hit. */
        NONE,
        /** A hit of a combo's finisher. */
        FINISH,
        /** A critical hit. */
        CRITICAL
    }

    /** How a hit feels: its spark shape and the sound layered under the ability's own impact sound. */
    public enum Weight {
        /** Quick and small (punches, darts): short sparks, a light tap. */
        LIGHT,
        /** Blades and points: long thin sparks, a sharp cut. */
        SHARP,
        /** Hammers, picks, bats: short thick sparks and a chunk, a deep thud. */
        HEAVY,
        /** Spells and energy: sparks with a glowing orb, a chime. */
        MAGIC
    }

    /** How a flying item turns (projectiles and lobs). */
    public enum Spin {
        /** Faces the flight, slowly rolling (orbs, cubes). */
        ROLL,
        /** Lies flat and spins like a disc (shuriken). */
        FLAT,
        /** Points along the flight (arrows, knives, clock hands). */
        POINT,
        /** Tumbles end over end (bombs, flasks). */
        TUMBLE
    }

    /** A small per-job flourish on top of its shape's look. */
    public enum Signature {
        NONE,
        /** Ghost copies of the knife trail the swing (요리사). */
        CLEAVER,
        /** Combo hits are straight jabs with a shock ring instead of arcs (격투가). */
        PUNCH,
        /** Three parallel claw marks instead of one arc (늑대인간, 악마). */
        CLAW,
        /** "딱!" over every hit, "홈런!" on the finisher (야구선수). */
        HOMERUN,
        /** Music notes fly out of the burst (음유시인). */
        NOTES,
        /** Ones and zeros trail the packet (해커). */
        BINARY,
        /** Bubbles trail the shot (물). */
        BUBBLE,
        /** A soul rises from whoever is hit (사신, 강령술사). */
        SOUL,
        /** Drops of blood fall from whoever is hit (흡혈귀, 사냥꾼). */
        BLOOD,
        /** Embers fly from every hit (대장장이). */
        EMBER,
        /** A cobweb clings to whoever is hit for a moment (거미). */
        WEB,
        /** A red and a blue beam side by side (자석). */
        MAGNET,
        /** A thin line and a hook instead of a beam (낚시꾼). */
        HOOK,
        /** The shield bashes forward with the swing (수호자). */
        SHIELD,
        /** Lightning branches flicker around every jump (뇌신). */
        BOLT,
        /** Two bull horns gore forward with the thrust and kick up dust (미노타우로스). */
        HORNS,
        /** Twin snake-eye glares, and a crust of stone cracks off whoever is hit (메두사). */
        STONE,
        /** A golden rune flares over whoever is hit (오딘). */
        RUNE,
        /** A long golden staff sweeps the arc instead of a blade (제천대성). */
        STAFF,
        /** A pillar of sunlight crashes down on the spot (태양신 라). */
        SUNBEAM,
        /** The ground cracks and rubble jumps up around the slam (거인). */
        QUAKE,
        /** The projectile is a flying palm print of wind (무도가). */
        PALM,
        /** Fox-fire wisps float up behind the flame (구미호). */
        FOXFIRE,
        /** A huge paw print slams down at the end of the swing (곰). */
        PAW,
        /** Ink splats on whoever is hit and drips off (문어). */
        INK,
        /** A green serpent writhes from target to target and bares its fangs (히드라). */
        FANGS,
        /** Ki rings ripple out behind the blast (기공 전사). */
        KI,
        /** Hearts trail the shot and burst out of every hit (마법소녀). */
        HEARTS,
        /** Thorny vines coil up out of the ground and burst into leaves (식물술사). */
        VINES,
        /** Feathers whirl out with the gust (텐구). */
        FEATHERS,
        /** A talisman sticks to whoever is hit with a red seal (무당). */
        TALISMAN,
        /** Scutes chip off the shell on every hit (거북이). */
        SHELL,
        /** Amethyst shards burst out of the spikes and every hit (크리스탈술사). */
        SHARDS,
        /** Sonic rings race down the line, growing as they go (음파술사). */
        SONAR,
        /** "퐁!" over every hit, "에이스!" on the finisher (테니스 천재). */
        TENNIS
    }

    /**
     * The display look of an attack (no resource pack needed: vanilla blocks and items on display entities).
     *
     * @param glow      translucent colored block of slash arcs, beams, rings (stained glass)
     * @param core      the bright core block (concrete, lanterns, froglights)
     * @param weapon    the item that flashes along a melee swing or thrust (null: none)
     * @param weight    the hit's spark and sound layer
     * @param spin      how a flying item turns
     * @param signature the job's flourish
     * @param size      overall size factor (1 = usual)
     * @param scene     Korean one-liner of the look (docs)
     */
    public record Fx(Material glow, Material core, @Nullable Material weapon, Weight weight, Spin spin,
                     Signature signature, float size, String scene) {

        public Fx {
            size = Math.max(0.3f, Math.min(3f, size));
        }
    }

    /** A projectile fan this narrow (degrees) still lands every projectile on one enemy at about 8 blocks. */
    public static final double NARROW_FAN = 8;

    public BasicAttack {
        cooldown = Math.max(1, cooldown);
        hits = Math.max(1, hits);
        interval = Math.max(0, interval);
        pierce = Math.max(0, pierce);
    }

    /** The cooldown in seconds. */
    public double cooldownSeconds() {
        return cooldown / 20.0;
    }

    /**
     * Damage of one attack to one target when it lands: every combo hit, shot and cone pulse, every projectile of a
     * volley and of a narrow fan (all of it lands at fighting range), two of a wide fan (the usual share), the first
     * target of a chain.
     */
    public double damagePerAttack() {
        return switch (shape) {
            case COMBO, SHOT, CONE -> damage * hits;
            case PROJECTILE -> interval > 0 || angle <= NARROW_FAN ? damage * hits : damage * Math.min(hits, 2);
            default -> damage;
        };
    }

    /** {@link #damagePerAttack()} per second. */
    public double damagePerSecond() {
        return damagePerAttack() / cooldownSeconds();
    }

    /** Whether the attack reaches beyond melee (players see it as a ranged attack). */
    public boolean ranged() {
        return switch (shape) {
            case SHOT, PROJECTILE, LOB, CHAIN, STRIKE -> true;
            default -> false;
        };
    }

    /** The same attack with other numbers (config overrides). */
    public BasicAttack with(Shape shape, double damage, int cooldown, double range, int hits, int interval,
                            double radius, double angle, double speed, int pierce, double falloff, Effect effect,
                            double effectPower, double effectSeconds) {
        return new BasicAttack(id, name, description, shape, damage, cooldown, range, hits, interval, radius, angle,
                speed, pierce, falloff, effect, effectPower, effectSeconds, palette, fx);
    }

    /** The same attack for another ability id (the fallback for abilities without their own). */
    public BasicAttack forId(String abilityId) {
        return new BasicAttack(abilityId, name, description, shape, damage, cooldown, range, hits, interval, radius,
                angle, speed, pierce, falloff, effect, effectPower, effectSeconds, palette, fx);
    }
}
