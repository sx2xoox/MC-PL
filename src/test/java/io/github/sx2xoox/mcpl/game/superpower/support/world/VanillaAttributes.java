package io.github.sx2xoox.mcpl.game.superpower.support.world;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.attribute.Attribute;
import org.mockbukkit.mockbukkit.attribute.AttributeInstanceMock;

/**
 * The attributes every living entity (and every player) has on a real server, with their vanilla defaults.
 * MockBukkit only registers a handful ({@code MAX_HEALTH}, {@code MOVEMENT_SPEED}, ...), so without these
 * {@code getAttribute(SCALE)}, {@code GRAVITY}, {@code SAFE_FALL_DISTANCE}, {@code MAX_ABSORPTION} and friends
 * return null and every ability modifier on them would silently do nothing.
 */
public final class VanillaAttributes {

    /** {@code LivingEntity#createLivingAttributes} (the attributes the abilities and the physics touch). */
    private static final Map<Attribute, Double> LIVING = new LinkedHashMap<>();
    /** Added by {@code Player#createAttributes}. */
    private static final Map<Attribute, Double> PLAYER = new LinkedHashMap<>();

    static {
        LIVING.put(Attribute.MAX_HEALTH, 20.0);
        LIVING.put(Attribute.KNOCKBACK_RESISTANCE, 0.0);
        LIVING.put(Attribute.MOVEMENT_SPEED, 0.7);
        LIVING.put(Attribute.ARMOR, 0.0);
        LIVING.put(Attribute.ARMOR_TOUGHNESS, 0.0);
        LIVING.put(Attribute.MAX_ABSORPTION, 0.0);
        LIVING.put(Attribute.STEP_HEIGHT, 0.6);
        LIVING.put(Attribute.SCALE, 1.0);
        LIVING.put(Attribute.GRAVITY, 0.08);
        LIVING.put(Attribute.SAFE_FALL_DISTANCE, 3.0);
        LIVING.put(Attribute.FALL_DAMAGE_MULTIPLIER, 1.0);
        LIVING.put(Attribute.JUMP_STRENGTH, 0.42);
        LIVING.put(Attribute.OXYGEN_BONUS, 0.0);
        LIVING.put(Attribute.BURNING_TIME, 1.0);
        LIVING.put(Attribute.EXPLOSION_KNOCKBACK_RESISTANCE, 0.0);
        LIVING.put(Attribute.WATER_MOVEMENT_EFFICIENCY, 0.0);
        LIVING.put(Attribute.MOVEMENT_EFFICIENCY, 0.0);
        LIVING.put(Attribute.ATTACK_KNOCKBACK, 0.0);

        PLAYER.put(Attribute.ATTACK_DAMAGE, 1.0);
        PLAYER.put(Attribute.MOVEMENT_SPEED, 0.1);
        PLAYER.put(Attribute.ATTACK_SPEED, 4.0);
        PLAYER.put(Attribute.LUCK, 0.0);
        PLAYER.put(Attribute.BLOCK_INTERACTION_RANGE, 4.5);
        PLAYER.put(Attribute.ENTITY_INTERACTION_RANGE, 3.0);
        PLAYER.put(Attribute.BLOCK_BREAK_SPEED, 1.0);
        PLAYER.put(Attribute.SUBMERGED_MINING_SPEED, 0.2);
        PLAYER.put(Attribute.SNEAKING_SPEED, 0.3);
        PLAYER.put(Attribute.MINING_EFFICIENCY, 0.0);
        PLAYER.put(Attribute.SWEEPING_DAMAGE_RATIO, 0.0);
    }

    private VanillaAttributes() {
    }

    /** Adds the living-entity attributes the mock is missing (existing instances are kept). */
    public static void completeLiving(Map<Attribute, AttributeInstanceMock> attributes) {
        LIVING.forEach((attribute, value) -> attributes.putIfAbsent(attribute,
                new AttributeInstanceMock(attribute, value)));
    }

    /**
     * Adds the attributes of a melee mob ({@code Mob#createMobAttributes} plus its attack damage) the mock is missing
     * (existing instances are kept).
     */
    public static void completeMob(Map<Attribute, AttributeInstanceMock> attributes, double attackDamage) {
        attributes.putIfAbsent(Attribute.FOLLOW_RANGE, new AttributeInstanceMock(Attribute.FOLLOW_RANGE, 16.0));
        attributes.putIfAbsent(Attribute.ATTACK_DAMAGE, new AttributeInstanceMock(Attribute.ATTACK_DAMAGE,
                attackDamage));
        completeLiving(attributes);
    }

    /** Adds the player attributes the mock is missing (existing instances are kept). */
    public static void completePlayer(Map<Attribute, AttributeInstanceMock> attributes) {
        PLAYER.forEach((attribute, value) -> attributes.putIfAbsent(attribute,
                new AttributeInstanceMock(attribute, value)));
        completeLiving(attributes);
    }
}
