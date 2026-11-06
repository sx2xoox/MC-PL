package io.github.sx2xoox.mcpl.core.arena;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

/**
 * A square world border shown only to chosen players (per-player virtual borders via {@link Bukkit#createWorldBorder()}
 * and {@link Player#setWorldBorder(WorldBorder)}); the real world border is never touched. The border does no damage
 * by itself: modes check {@link #isOutside(Location)} / {@link #distanceOutside(Location)} and apply their own damage.
 *
 * <p>Create it through {@code GameSession#createBorder(double)} so it is shown to late joiners and spectators and
 * removed automatically when the match stops. Shrinking is tracked on the server ({@link #currentSize()}) using the
 * server tick, independent of the client animation.
 */
public final class VirtualBorder {

    private final World world;
    private final double centerX;
    private final double centerZ;
    private final Map<UUID, WorldBorder> borders = new HashMap<>();
    private double fromSize;
    private double toSize;
    private long lerpStartTick;
    private long lerpTicks;
    private int warningDistance = 4;
    private int warningTimeTicks = 0;

    /**
     * @param center border center (only x/z are used)
     * @param size   initial side length in blocks
     */
    public VirtualBorder(Location center, double size) {
        World centerWorld = center.getWorld();
        if (centerWorld == null) {
            throw new IllegalArgumentException("center has no world");
        }
        this.world = centerWorld;
        this.centerX = center.getX();
        this.centerZ = center.getZ();
        this.fromSize = clampSize(size);
        this.toSize = this.fromSize;
    }

    public World world() {
        return world;
    }

    public Location center() {
        return new Location(world, centerX, 0, centerZ);
    }

    // ------------------------------------------------------------------ viewers

    /** Shows the border to {@code player} in its current (possibly shrinking) state. */
    public void show(Player player) {
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(centerX, centerZ);
        border.setWarningDistance(warningDistance);
        border.setWarningTimeTicks(warningTimeTicks);
        border.setDamageAmount(0);
        border.setDamageBuffer(0);
        border.setSize(currentSize());
        long remaining = remainingTicks();
        if (remaining > 0) {
            border.changeSize(toSize, remaining);
        }
        borders.put(player.getUniqueId(), border);
        player.setWorldBorder(border);
    }

    /** Puts the player's normal world border back. */
    public void hide(Player player) {
        if (borders.remove(player.getUniqueId()) != null) {
            player.setWorldBorder(null);
        }
    }

    public boolean isShownTo(Player player) {
        return borders.containsKey(player.getUniqueId());
    }

    /** Forgets a player who left the server. */
    public void forget(UUID playerId) {
        borders.remove(playerId);
    }

    /** Hides the border from everyone it was shown to. */
    public void hideAll() {
        for (UUID id : Map.copyOf(borders).keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.setWorldBorder(null);
            }
        }
        borders.clear();
    }

    // ------------------------------------------------------------------ size

    /** Sets the size instantly (stops any shrinking). */
    public void setSize(double size) {
        fromSize = clampSize(size);
        toSize = fromSize;
        lerpTicks = 0;
        for (WorldBorder border : borders.values()) {
            border.setSize(fromSize);
        }
    }

    /** Smoothly changes the size from the current size to {@code targetSize} over {@code ticks}. */
    public void shrink(double targetSize, long ticks) {
        if (ticks <= 0) {
            setSize(targetSize);
            return;
        }
        fromSize = currentSize();
        toSize = clampSize(targetSize);
        lerpStartTick = Bukkit.getCurrentTick();
        lerpTicks = ticks;
        for (WorldBorder border : borders.values()) {
            border.setSize(fromSize);
            border.changeSize(toSize, ticks);
        }
    }

    /** The side length right now (interpolated while shrinking). */
    public double currentSize() {
        long remaining = remainingTicks();
        if (remaining <= 0 || lerpTicks <= 0) {
            return toSize;
        }
        double progress = 1.0 - (double) remaining / lerpTicks;
        return fromSize + (toSize - fromSize) * progress;
    }

    /** The size the border is moving to (equal to {@link #currentSize()} when not shrinking). */
    public double targetSize() {
        return toSize;
    }

    public boolean isShrinking() {
        return remainingTicks() > 0;
    }

    /** Ticks until the current shrink finishes, 0 if not shrinking. */
    public long remainingTicks() {
        if (lerpTicks <= 0) {
            return 0;
        }
        long elapsed = Bukkit.getCurrentTick() - lerpStartTick;
        return Math.max(0, lerpTicks - elapsed);
    }

    /** Red screen tint when closer than this many blocks (client effect only). */
    public void setWarningDistance(int blocks) {
        warningDistance = Math.max(0, blocks);
        for (WorldBorder border : borders.values()) {
            border.setWarningDistance(warningDistance);
        }
    }

    // ------------------------------------------------------------------ checks

    /** True if {@code location} is outside the border, or in another world. */
    public boolean isOutside(Location location) {
        return distanceOutside(location) > 0;
    }

    /**
     * How far outside the border {@code location} is, in blocks (0 when inside, {@link Double#MAX_VALUE} in another
     * world).
     */
    public double distanceOutside(Location location) {
        if (!world.equals(location.getWorld())) {
            return Double.MAX_VALUE;
        }
        double half = currentSize() / 2.0;
        double dx = Math.abs(location.getX() - centerX) - half;
        double dz = Math.abs(location.getZ() - centerZ) - half;
        return Math.max(0, Math.max(dx, dz));
    }

    private static double clampSize(double size) {
        return Math.max(1.0, Math.min(5.9999968E7, size));
    }
}
