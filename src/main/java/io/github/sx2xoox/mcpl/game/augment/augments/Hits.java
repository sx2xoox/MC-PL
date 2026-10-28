package io.github.sx2xoox.mcpl.game.augment.augments;

import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jspecify.annotations.Nullable;

/** What kind of hit a damage event is, shared by the combat augments. */
final class Hits {

    private Hits() {
    }

    /** A melee hit dealt by {@code attacker} itself (not a sweep, projectile or effect). */
    static boolean meleeBy(EntityDamageEvent event, LivingEntity attacker) {
        return event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getDamageSource().getDirectEntity() == attacker;
    }

    /** A melee hit taken from a living attacker (player or mob). */
    static boolean melee(EntityDamageEvent event) {
        return event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getDamageSource().getDirectEntity() instanceof LivingEntity;
    }

    /** The arrow (or trident) of a projectile hit, null for any other damage. */
    static @Nullable AbstractArrow arrow(EntityDamageEvent event) {
        Entity direct = event.getDamageSource().getDirectEntity();
        return event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE && direct instanceof AbstractArrow arrow
                ? arrow : null;
    }

    /** The living entity responsible for a hit (the shooter of a projectile, the attacker of a melee hit). */
    static @Nullable LivingEntity attacker(EntityDamageEvent event) {
        Entity causing = event.getDamageSource().getCausingEntity();
        return causing instanceof LivingEntity living ? living : null;
    }
}
