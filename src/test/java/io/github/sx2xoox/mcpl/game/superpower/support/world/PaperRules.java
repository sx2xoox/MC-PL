package io.github.sx2xoox.mcpl.game.superpower.support.world;

import org.bukkit.Particle;
import org.jspecify.annotations.Nullable;

/**
 * Checks Paper performs on a real server that MockBukkit skips, so code that would throw on a live server also
 * throws in tests.
 */
public final class PaperRules {

    private PaperRules() {
    }

    /** {@code CraftParticle#createParticleParam}: required data must be present and of the particle's data type. */
    public static void checkParticle(Particle particle, @Nullable Object data) {
        if (particle == null) {
            throw new IllegalArgumentException("particle cannot be null");
        }
        Class<?> type = particle.getDataType();
        if (type != Void.class && data == null) {
            throw new IllegalArgumentException("missing required data " + type + " for " + particle);
        }
        if (data != null && !type.isInstance(data)) {
            throw new IllegalArgumentException("data (" + data.getClass() + ") should be " + type + " for " + particle);
        }
    }

    /** {@code CraftEntity#setVelocity}: the velocity must be finite (a normalized zero vector is NaN). */
    public static void checkVelocity(org.bukkit.util.Vector velocity) {
        if (velocity == null) {
            throw new IllegalArgumentException("velocity");
        }
        velocity.checkFinite();
    }

    /** {@code CraftLivingEntity#setHealth}: health must be within 0..max (NaN fails too). */
    public static void checkHealth(double health, double maxHealth) {
        if (!(health >= 0 && health <= maxHealth)) {
            throw new IllegalArgumentException("Health value (" + health + ") must be between 0 and " + maxHealth
                    + ". (attribute base value: " + maxHealth + ")");
        }
    }

    /** {@code CraftEntity#setRotation}: yaw and pitch must be finite. */
    public static void checkRotation(float yaw, float pitch) {
        org.bukkit.util.NumberConversions.checkFinite(pitch, "pitch not finite");
        org.bukkit.util.NumberConversions.checkFinite(yaw, "yaw not finite");
    }

    /** A deep copy (the server hands out fresh vectors and quaternions on every get). */
    public static org.bukkit.util.Transformation copy(org.bukkit.util.Transformation t) {
        return new org.bukkit.util.Transformation(new org.joml.Vector3f(t.getTranslation()),
                new org.joml.Quaternionf(t.getLeftRotation()), new org.joml.Vector3f(t.getScale()),
                new org.joml.Quaternionf(t.getRightRotation()));
    }

    /**
     * {@code CraftEntity#getName}: the custom name, if any, serialized with legacy section codes ("§e..."). Putting
     * that into a MiniMessage string throws, so abilities must use {@code Entity#name()} placeholders instead.
     */
    public static String legacyName(net.kyori.adventure.text.@Nullable Component customName, String fallback) {
        return customName == null ? fallback
                : net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                        .serialize(customName);
    }

    /**
     * {@code CraftEntity#teleport0} (Paper 26): a teleport keeps the entities riding the teleported one (a display
     * mounted over a head moves along), only a player carrying passengers cannot change worlds. MockBukkit still
     * refuses any teleport of an entity with passengers unless {@code RETAIN_PASSENGERS} is passed (the flag Paper
     * deprecated when keeping them became the default), so the doubles add it for teleports within the world.
     */
    @SuppressWarnings("removal")
    public static io.papermc.paper.entity.TeleportFlag[] keepPassengers(org.bukkit.entity.Entity entity,
            org.bukkit.Location to, io.papermc.paper.entity.TeleportFlag... flags) {
        if (entity.getPassengers().isEmpty() || to.getWorld() == null || !to.getWorld().equals(entity.getWorld())
                || java.util.Arrays.asList(flags).contains(
                        io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS)) {
            return flags;
        }
        io.papermc.paper.entity.TeleportFlag[] kept = java.util.Arrays.copyOf(flags, flags.length + 1);
        kept[flags.length] = io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS;
        return kept;
    }
}
