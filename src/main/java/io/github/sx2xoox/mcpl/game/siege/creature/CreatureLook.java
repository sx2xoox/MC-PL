package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.jspecify.annotations.Nullable;

/**
 * How a 공성전 v2 creature looks: the seam between the lane systems (which drive a {@link Mob} body: its health,
 * hitbox, pathfinding and attacks) and the models.
 *
 * <p>{@link CreatureBodies} spawns the body: one invisible, silent neutral mob for every kind (the hitbox and the legs;
 * its size is the settings' toy size, {@code Attribute.SCALE}). A skin ({@link CreatureSkin}, the pack's
 * {@link ModelCreatureLook}) dresses it as the toy: the model {@link CreatureKind#modelId()} following the body, its gait,
 * attacks and skills ({@link SkinnedCreatureLook} joins the two); it removes its displays in {@link #removed}.
 */
public interface CreatureLook {

    /**
     * Spawns the body of a creature at {@code at}. {@code configure} (health, name, AI) runs inside the spawn, before
     * the body is added to the world; the look's own dressing runs before it.
     *
     * @param side the team of a minion or shopkeeper, null for neutral monsters
     */
    Mob spawn(CreatureKind kind, LaneSites.@Nullable Side side, Location at, Consumer<Mob> configure);

    /** The body is gone (died or removed): the look drops whatever it added. */
    default void removed(Mob body) {
    }

    /**
     * The body attacks now (the systems call it with each basic attack, the shopkeeper when a player opens its shop):
     * the look plays the kind's attack motion ({@link CreatureModels#ATTACKS}). Nothing by default.
     */
    default void attack(Mob body) {
    }

    /** The body plays a skill's {@code motion} now ({@link CreatureModels#SKILLS}); the basic attack by default. */
    default void attack(Mob body, CreatureModels.Attack motion) {
        attack(body);
    }

    /** The body charges a skill (its warning) for {@code ticks}; nothing by default. */
    default void charge(Mob body, int ticks) {
    }

    /** The body was pushed by a blow from {@code from}: it staggers ({@code strength} 0..1); nothing by default. */
    default void stagger(Mob body, Location from, double strength) {
    }

    /** The body is enraged (or calm again); nothing by default. */
    default void enraged(Mob body, boolean enraged) {
    }

    /** The body took a hit (a landed hit): the look flinches. Nothing by default. */
    default void hurt(Mob body) {
    }

    /** Called every tick for each live body (animations); nothing by default. */
    default void tick(Mob body, CreatureKind kind) {
    }
}
