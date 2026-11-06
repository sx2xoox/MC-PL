package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;

/**
 * The space a standing body needs at a destination, in blocks.
 *
 * @param width     hitbox width
 * @param height    hitbox height
 * @param eyeHeight eye height above the feet
 */
public record Body(double width, double height, double eyeHeight) {

    /** A standing player at scale 1. */
    public static final Body PLAYER = new Body(0.6, 1.8, 1.62);

    /**
     * The standing player-shaped body of {@code entity}, scaled by its {@link Attribute#SCALE}. Sneaking or
     * swimming does not shrink it: the entity stands up right after a teleport.
     */
    public static Body standing(LivingEntity entity) {
        double scale = scaleOf(entity);
        return new Body(PLAYER.width * scale, PLAYER.height * scale, PLAYER.eyeHeight * scale);
    }

    /** The entity's {@link Attribute#SCALE} (at least 0.1), 1 if it has none. */
    public static double scaleOf(LivingEntity entity) {
        AttributeInstance scale = entity.getAttribute(Attribute.SCALE);
        return scale == null ? 1.0 : Math.max(0.1, scale.getValue());
    }
}
