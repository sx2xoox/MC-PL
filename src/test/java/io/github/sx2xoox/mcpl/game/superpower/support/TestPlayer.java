package io.github.sx2xoox.mcpl.game.superpower.support;

import java.util.HashMap;
import java.util.Map;

import io.github.sx2xoox.mcpl.game.superpower.support.world.DamageSimulation;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.github.sx2xoox.mcpl.game.superpower.support.world.Hurtable;
import io.github.sx2xoox.mcpl.game.superpower.support.world.RayTracing;
import io.github.sx2xoox.mcpl.game.superpower.support.world.VanillaAttributes;
import net.kyori.adventure.key.Key;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A {@link PlayerMock} filling the gaps of MockBukkit the superpower runtime and the abilities rely on:
 *
 * <ul>
 *     <li>item cooldown groups, per-player world borders and healing;</li>
 *     <li>{@code damage(double, DamageSource)} through {@link DamageSimulation} (server-like causes, i-frames,
 *     resistance and absorption);</li>
 *     <li>a body whose size follows the {@link Attribute#SCALE} attribute (hitbox, height, eye height), like a real
 *     player's;</li>
 *     <li>line of sight through block ray tracing ({@link RayTracing#hasLineOfSight}), body yaw and
 *     {@link #isInLava()} in a {@link FlatWorld}.</li>
 * </ul>
 */
public final class TestPlayer extends PlayerMock implements Hurtable {

    private static final double WIDTH = 0.6;
    private static final double HEIGHT = 1.8;
    private static final double SNEAK_HEIGHT = 1.5;
    private static final double EYE_HEIGHT = 1.62;
    private static final double SNEAK_EYE_HEIGHT = 1.27;

    private final Map<Key, Integer> cooldowns = new HashMap<>();
    private @Nullable WorldBorder worldBorder;
    private @Nullable Float bodyYaw;
    private double lastHurt;

    public TestPlayer(ServerMock server, String name) {
        super(server, name);
        VanillaAttributes.completePlayer(attributes);
    }

    /** Like the server: absorption is clamped to the {@link Attribute#MAX_ABSORPTION} attribute. */
    @Override
    public void setAbsorptionAmount(double amount) {
        if (!(amount >= 0) || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount < 0 or non-finite");
        }
        super.setAbsorptionAmount(Math.min(amount, getAttribute(Attribute.MAX_ABSORPTION).getValue()));
    }

    @Override
    public void setCooldown(Key cooldownGroup, int ticks) {
        if (ticks <= 0) {
            cooldowns.remove(cooldownGroup);
        } else {
            cooldowns.put(cooldownGroup, ticks);
        }
    }

    @Override
    public int getCooldown(Key cooldownGroup) {
        return cooldowns.getOrDefault(cooldownGroup, 0);
    }

    @Override
    public void setWorldBorder(@Nullable WorldBorder border) {
        this.worldBorder = border;
    }

    @Override
    public @Nullable WorldBorder getWorldBorder() {
        return worldBorder;
    }

    @Override
    public double lastHurt() {
        return lastHurt;
    }

    @Override
    public void lastHurt(double amount) {
        this.lastHurt = amount;
    }

    @Override
    public double getLastDamage() {
        return lastHurt;
    }

    @Override
    public void setLastDamage(double damage) {
        this.lastHurt = damage;
    }

    @Override
    public void damage(double amount, DamageSource damageSource) {
        DamageSimulation.damage(this, amount, damageSource);
    }

    @Override
    public void heal(double amount, EntityRegainHealthEvent.RegainReason reason) {
        if (!isDead()) {
            double max = getAttribute(Attribute.MAX_HEALTH).getValue();
            setHealth(Math.min(max, getHealth() + amount));
        }
    }

    // ------------------------------------------------------------------ body

    /** The {@link Attribute#SCALE} value, 1 without the attribute. */
    public double scale() {
        AttributeInstance scale = getAttribute(Attribute.SCALE);
        return scale == null ? 1.0 : Math.max(0.0625, scale.getValue());
    }

    @Override
    public double getHeight() {
        return (isSneaking() ? SNEAK_HEIGHT : HEIGHT) * scale();
    }

    @Override
    public double getWidth() {
        return WIDTH * scale();
    }

    @Override
    public double getEyeHeight() {
        return (isSneaking() ? SNEAK_EYE_HEIGHT : EYE_HEIGHT) * scale();
    }

    @Override
    public double getEyeHeight(boolean ignorePose) {
        return ignorePose ? EYE_HEIGHT * scale() : getEyeHeight();
    }

    @Override
    public Location getEyeLocation() {
        return getLocation().add(0, getEyeHeight(), 0);
    }

    @Override
    public BoundingBox getBoundingBox() {
        Location at = getLocation();
        double half = getWidth() / 2;
        return new BoundingBox(at.getX() - half, at.getY(), at.getZ() - half, at.getX() + half,
                at.getY() + getHeight(), at.getZ() + half);
    }

    @Override
    public float getBodyYaw() {
        return bodyYaw != null ? bodyYaw : getLocation().getYaw();
    }

    @Override
    public void setBodyYaw(float bodyYaw) {
        this.bodyYaw = bodyYaw;
    }

    // ------------------------------------------------------------------ world queries

    @Override
    public boolean hasLineOfSight(Entity other) {
        return RayTracing.hasLineOfSight(this, other);
    }

    @Override
    public boolean hasLineOfSight(Location location) {
        if (!getWorld().equals(location.getWorld())) {
            return false;
        }
        Vector from = getEyeLocation().toVector();
        return RayTracing.clearLine(getWorld(), from, location.toVector(), 128.0);
    }

    @Override
    public boolean isInLava() {
        return getWorld() instanceof FlatWorld && FlatWorld.isIn(this, Material.LAVA);
    }
}
