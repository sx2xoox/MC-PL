package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.tag.DamageTypeTags;

/** Dealing ability damage and reading damage events. */
public final class Combat {

    private Combat() {
    }

    /**
     * Deals credited {@link DamageType#MAGIC} ability damage through {@link AbilityContext#damage}, if
     * {@code amount} is positive and {@code target} is still a live enemy of {@code source}. The context already
     * ignores invulnerability frames, so rapid multi-hits all land; it applies no vanilla knockback.
     */
    public static void hit(AbilityContext ctx, Player source, LivingEntity target, double amount) {
        if (amount > 0 && isTargetable(ctx, source, target)) {
            ctx.damage(source, target, amount);
        }
    }

    /** Like {@link #hit(AbilityContext, Player, LivingEntity, double)} with an explicit damage type. */
    public static void hit(AbilityContext ctx, Player source, LivingEntity target, double amount, DamageType type) {
        if (amount > 0 && isTargetable(ctx, source, target)) {
            ctx.damage(source, target, amount, type);
        }
    }

    /** True while {@code entity} exists, is not dead and is an enemy of {@code owner}. */
    public static boolean isTargetable(AbilityContext ctx, Player owner, Entity entity) {
        return entity.isValid() && !entity.isDead() && ctx.isEnemy(owner, entity);
    }

    /** The entity's maximum health (at least 1), 20 if it has no such attribute. */
    public static double maxHealth(LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : Math.max(1.0, attribute.getValue());
    }

    /** True for fall damage: the {@link DamageCause#FALL} cause or a damage type tagged as a fall. */
    public static boolean isFall(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.FALL) {
            return true;
        }
        Tag<DamageType> fall = DamageTypeTags.IS_FALL;
        return fall != null && fall.isTagged(event.getDamageSource().getDamageType());
    }
}
