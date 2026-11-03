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
 */
public record BasicAttack(String id, String name, String description, Shape shape, double damage, int cooldown,
                          double range, int hits, int interval, double radius, double angle, double speed, int pierce,
                          double falloff, Effect effect, double effectPower, double effectSeconds, Palette palette) {

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
     * volley, two of a fan (the usual share at fighting range), the first target of a chain.
     */
    public double damagePerAttack() {
        return switch (shape) {
            case COMBO, SHOT, CONE -> damage * hits;
            case PROJECTILE -> interval > 0 || angle <= 0 ? damage * hits : damage * Math.min(hits, 2);
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
                speed, pierce, falloff, effect, effectPower, effectSeconds, palette);
    }

    /** The same attack for another ability id (the fallback for abilities without their own). */
    public BasicAttack forId(String abilityId) {
        return new BasicAttack(abilityId, name, description, shape, damage, cooldown, range, hits, interval, radius,
                angle, speed, pierce, falloff, effect, effectPower, effectSeconds, palette);
    }
}
