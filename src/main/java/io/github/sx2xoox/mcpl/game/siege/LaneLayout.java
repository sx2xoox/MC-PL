package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import org.bukkit.Location;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * {@link LaneSites} placed into a world: block coordinates relative to a center block on x/z (block {@code 0, 0} of a
 * map world, the arena center for {@link FallbackLaneSites}), absolute on y. Minions, jungle and shop code turn the
 * map's places into locations with this.
 */
public final class LaneLayout {

    private final LaneSites sites;
    private final World world;
    private final int originX;
    private final int originZ;

    public LaneLayout(LaneSites sites, World world, int originX, int originZ) {
        this.sites = sites;
        this.world = world;
        this.originX = originX;
        this.originZ = originZ;
        for (LaneSites.Side side : LaneSites.Side.values()) {
            if (sites.spawns(side).isEmpty()) {
                throw new IllegalArgumentException("A lane map needs spawns for " + side);
            }
        }
    }

    public LaneSites sites() {
        return sites;
    }

    public World world() {
        return world;
    }

    public Location at(LaneSites.Spot spot) {
        return new Location(world, originX + spot.x() + 0.5, spot.y(), originZ + spot.z() + 0.5, spot.yaw(), 0f);
    }

    public Location at(LaneSites.Point point) {
        return new Location(world, originX + point.x() + 0.5, point.y(), originZ + point.z() + 0.5);
    }

    public Location at(LaneSites.Objective objective) {
        return new Location(world, originX + objective.x() + 0.5, objective.y(), originZ + objective.z() + 0.5);
    }

    /** The base of a model prop (its coordinates are not snapped to block middles). */
    public Location at(LaneSites.ModelProp prop) {
        return new Location(world, originX + prop.x(), prop.y(), originZ + prop.z());
    }

    /** The base of a structure of {@code owner}, its front towards the enemies coming at it ({@link #facing}). */
    public Location at(LaneSites.Structure structure, LaneSites.Side owner) {
        return new Location(world, originX + structure.x() + 0.5, structure.y(), originZ + structure.z() + 0.5,
                facing(sites, structure, owner), 0f);
    }

    /** How far along its lane a tower looks for the direction its enemies come from, blocks. */
    static final double TOWER_LOOK_AHEAD = 16;

    /**
     * The yaw a structure's front (door, windows) faces: a lane tower looks down its lane towards the enemy base (where
     * the enemy minions and players come from, {@link #TOWER_LOOK_AHEAD} blocks along the path its side's minions
     * walk), a core at the enemy core. Falls back to straight at the enemy end of the map (BLUE +x, RED −x).
     */
    public static float facing(LaneSites sites, LaneSites.Structure structure, LaneSites.Side owner) {
        LaneSites.Side enemy = owner == LaneSites.Side.BLUE ? LaneSites.Side.RED : LaneSites.Side.BLUE;
        double tx = Double.NaN;
        double tz = Double.NaN;
        for (LaneSites.Lane lane : LaneSites.Lane.values()) {
            if (!sites.towers(owner, lane).contains(structure)) {
                continue;
            }
            List<LaneSites.Point> path = new ArrayList<>(sites.lanePath(lane));
            if (owner == LaneSites.Side.RED) {
                java.util.Collections.reverse(path);
            }
            int nearest = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < path.size(); i++) {
                double d = flatSquared(path.get(i).x(), path.get(i).z(), structure.x(), structure.z());
                if (d < best) {
                    best = d;
                    nearest = i;
                }
            }
            for (int i = Math.max(0, nearest); i < path.size(); i++) {
                LaneSites.Point point = path.get(i);
                tx = point.x();
                tz = point.z();
                if (flatSquared(tx, tz, structure.x(), structure.z()) >= TOWER_LOOK_AHEAD * TOWER_LOOK_AHEAD) {
                    break;
                }
            }
        }
        if (Double.isNaN(tx) || flatSquared(tx, tz, structure.x(), structure.z()) < 1) {
            LaneSites.Structure core = sites.core(enemy);
            tx = core.x();
            tz = core.z();
        }
        if (flatSquared(tx, tz, structure.x(), structure.z()) < 1) {
            return owner == LaneSites.Side.BLUE ? -90f : 90f;
        }
        return (float) Math.toDegrees(Math.atan2(-(tx - structure.x()), tz - structure.z()));
    }

    private static double flatSquared(double x1, double z1, double x2, double z2) {
        return (x1 - x2) * (x1 - x2) + (z1 - z2) * (z1 - z2);
    }

    public List<Location> spawns(LaneSites.Side side) {
        List<Location> out = new ArrayList<>();
        for (LaneSites.Spot spot : sites.spawns(side)) {
            out.add(at(spot));
        }
        return out;
    }

    public Location shop(LaneSites.Side side) {
        return at(sites.shop(side));
    }

    /** A lane's waypoints as walked by {@code side}'s minions (RED walks the path reversed). */
    public List<Location> lanePath(LaneSites.Lane lane, LaneSites.Side side) {
        List<Location> out = new ArrayList<>();
        for (LaneSites.Point point : sites.lanePath(lane)) {
            out.add(at(point));
        }
        if (side == LaneSites.Side.RED) {
            java.util.Collections.reverse(out);
        }
        return out;
    }

    /** The named region {@code location} is in, or null (outside every region, another world). */
    public LaneSites.@Nullable Region regionAt(Location location) {
        if (!world.equals(location.getWorld())) {
            return null;
        }
        int x = location.getBlockX() - originX;
        int z = location.getBlockZ() - originZ;
        for (LaneSites.Region region : sites.regions()) {
            if (x >= region.minX() && x <= region.maxX() && z >= region.minZ() && z <= region.maxZ()) {
                return region;
            }
        }
        return null;
    }
}
