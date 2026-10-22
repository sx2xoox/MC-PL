package io.github.sx2xoox.mcpl.game.superpower.support.world;

import java.util.Collection;
import java.util.UUID;

import net.kyori.adventure.key.Key;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.jspecify.annotations.Nullable;

/**
 * An attribute instance that, like vanilla, refuses to add a modifier whose key is already applied
 * ({@code AttributeInstance#addPermanentModifier}: "Modifier is already applied on this attribute!"). MockBukkit
 * silently replaces it instead, which hid real-server errors.
 */
@SuppressWarnings("deprecation")
public final class StrictAttributeInstance implements AttributeInstance {

    private final AttributeInstance delegate;

    public StrictAttributeInstance(AttributeInstance delegate) {
        this.delegate = delegate;
    }

    private void checkNew(AttributeModifier modifier) {
        if (modifier != null && delegate.getModifier(modifier.getKey()) != null) {
            throw new IllegalArgumentException("Modifier is already applied on this attribute! " + modifier.getKey());
        }
    }

    @Override
    public Attribute getAttribute() {
        return delegate.getAttribute();
    }

    @Override
    public double getBaseValue() {
        return delegate.getBaseValue();
    }

    @Override
    public void setBaseValue(double value) {
        delegate.setBaseValue(value);
    }

    @Override
    public Collection<AttributeModifier> getModifiers() {
        return delegate.getModifiers();
    }

    @Override
    public @Nullable AttributeModifier getModifier(Key key) {
        return delegate.getModifier(key);
    }

    @Override
    public void removeModifier(Key key) {
        delegate.removeModifier(key);
    }

    @Override
    public @Nullable AttributeModifier getModifier(UUID uuid) {
        return delegate.getModifier(uuid);
    }

    @Override
    public void removeModifier(UUID uuid) {
        delegate.removeModifier(uuid);
    }

    @Override
    public void addModifier(AttributeModifier modifier) {
        checkNew(modifier);
        delegate.addModifier(modifier);
    }

    @Override
    public void addTransientModifier(AttributeModifier modifier) {
        checkNew(modifier);
        delegate.addTransientModifier(modifier);
    }

    @Override
    public void removeModifier(AttributeModifier modifier) {
        delegate.removeModifier(modifier);
    }

    @Override
    public double getValue() {
        return delegate.getValue();
    }

    @Override
    public double getDefaultValue() {
        return delegate.getDefaultValue();
    }
}
