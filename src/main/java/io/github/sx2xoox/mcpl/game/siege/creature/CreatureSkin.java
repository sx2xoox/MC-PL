package io.github.sx2xoox.mcpl.game.siege.creature;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * How a 공성전 creature is dressed (minions, jungle monsters, the shop keeper; docs/siege-v2.md §2 §4 §7). The game owns
 * the creature's vanilla mob (rules, AI, health, hitbox, name plate); a look only dresses it. {@link #VANILLA} leaves
 * the mob as it is; {@link ModelCreatureLook} shows the pack's 3D toy model to players with the resource pack and the
 * vanilla mob to everyone else.
 */
public interface CreatureSkin {

    /** No dressing: everyone sees the vanilla mob. */
    CreatureSkin VANILLA = (body, kind, team) -> Visual.NONE;

    /**
     * Dresses {@code body} as {@code kind}.
     *
     * @param body the creature's mob (kept as the game made it; the look never damages, moves or removes it)
     * @param kind a {@link CreatureModels} kind ({@code soldier}, {@code archer}, {@code cannon}, {@code jelly},
     *             {@code mouse}, {@code teddy}, {@code rabbit}, {@code dragon}, {@code merchant}) or an alias
     *             ({@link CreatureModels#kindOf}: {@code mice}, {@code melee}, {@code ranged}, ...)
     * @param team the team colour of a minion (tints its jacket, cap, carriage); null for neutral creatures
     * @return the live visual; the game calls {@link Visual#remove()} when the creature dies or the match ends
     */
    Visual attach(LivingEntity body, String kind, @Nullable Color team);

    /** One dressed creature. Every entity it creates is tracked and removed by {@link #remove()}. */
    interface Visual {

        /** Nothing to draw (the vanilla look). */
        Visual NONE = () -> { };

        /** Just took a hit: a short squash and flash. */
        default void hit() {
        }

        /**
         * Just took a hit from {@code from} (the attacker's or projectile's place; null = unknown) as hard as
         * {@code strength}: the toy freezes for a moment (hitstop), leans away from the blow, flashes white then red,
         * squashes and springs back. {@link #hit()} by default.
         */
        default void hit(@Nullable Location from, HitStrength strength) {
            hit();
        }

        /**
         * Attacks now: the kind's attack motion ({@link CreatureModels.Attack}: the soldier thrusts, the cannon recoils,
         * the jelly pounces, ...).
         */
        default void attack() {
        }

        /** Plays {@code motion} now (a skill's motion: the jelly's goop, the teddy's shockwave, the dragon's tail...). */
        default void attack(CreatureModels.Attack motion) {
            attack();
        }

        /** Charges a skill for {@code ticks} (its warning): a shiver, before the skill's motion plays. */
        default void charge(int ticks) {
        }

        /**
         * Pushed by a blow from {@code fromX, fromZ} (world coordinates): staggers away from it, as strong as
         * {@code strength} (0..1).
         */
        default void stagger(double fromX, double fromZ, double strength) {
        }

        /** Enraged (the dragon below its rage health) or calm again: glows angry red while it is. */
        default void enraged(boolean enraged) {
        }

        /** Removes every entity of this visual and gives everyone the vanilla mob back. Safe to call twice. */
        void remove();
    }
}
