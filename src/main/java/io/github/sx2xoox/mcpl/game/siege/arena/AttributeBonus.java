package io.github.sx2xoox.mcpl.game.siege.arena;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlotGroup;

/** Keyed attribute modifiers the 공성전 systems give players (shop items, buffs): set replaces, 0 removes. */
public final class AttributeBonus {

    private AttributeBonus() {
    }

    /** Sets the modifier {@code key} of {@code attribute} to {@code amount} (replacing it; 0 removes it). */
    public static void set(LivingEntity entity, Attribute attribute, NamespacedKey key, double amount,
            AttributeModifier.Operation operation) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier current = instance.getModifier(key);
        if (current == null ? amount == 0 : current.getAmount() == amount && current.getOperation() == operation) {
            return;
        }
        instance.removeModifier(key);
        if (amount != 0) {
            instance.addModifier(new AttributeModifier(key, amount, operation, EquipmentSlotGroup.ANY));
        }
    }

    public static void remove(LivingEntity entity, Attribute attribute, NamespacedKey key) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(key);
        }
    }

    /** The amount of the modifier {@code key}, or 0. */
    public static double amount(LivingEntity entity, Attribute attribute, NamespacedKey key) {
        AttributeInstance instance = entity.getAttribute(attribute);
        AttributeModifier modifier = instance == null ? null : instance.getModifier(key);
        return modifier == null ? 0 : modifier.getAmount();
    }
}
