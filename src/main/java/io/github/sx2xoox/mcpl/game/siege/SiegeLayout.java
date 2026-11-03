package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.siege.SiegeSites;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * {@link SiegeSites} placed into a world: block coordinates relative to a center block on x/z (block {@code 0, 0}
 * of a map world, the arena center for {@link FallbackSiegeSites}), absolute on y.
 */
public final class SiegeLayout {

    private final SiegeSites sites;
    private final World world;
    private final int originX;
    private final int originZ;

    public SiegeLayout(SiegeSites sites, World world, int originX, int originZ) {
        this.sites = sites;
        this.world = world;
        this.originX = originX;
        this.originZ = originZ;
        if (sites.towers().isEmpty() || sites.attackerSpawns().isEmpty() || sites.defenderSpawns().isEmpty()) {
            throw new IllegalArgumentException("A siege layout needs towers and spawns for both sides");
        }
    }

    public SiegeSites sites() {
        return sites;
    }

    public World world() {
        return world;
    }

    public Location at(SiegeSites.Spot spot) {
        return new Location(world, originX + spot.x() + 0.5, spot.y(), originZ + spot.z() + 0.5, spot.yaw(), 0f);
    }

    /**
     * The crystal spot of a tower (the base of its visual and hitbox), facing the attackers: they come from −x on every
     * siege map, and yaw 90 faces −x.
     */
    public Location at(SiegeSites.Tower tower) {
        return new Location(world, originX + tower.x() + 0.5, tower.y(), originZ + tower.z() + 0.5, 90f, 0f);
    }

    public List<Location> attackerSpawns() {
        return all(sites.attackerSpawns());
    }

    /** Defender spawns once {@code towersDown} towers fell (the last list once all but the core fell). */
    public List<Location> defenderSpawns(int towersDown) {
        List<List<SiegeSites.Spot>> lists = sites.defenderSpawns();
        return all(lists.get(Math.max(0, Math.min(lists.size() - 1, towersDown))));
    }

    private List<Location> all(List<SiegeSites.Spot> spots) {
        List<Location> out = new ArrayList<>(spots.size());
        for (SiegeSites.Spot spot : spots) {
            out.add(at(spot));
        }
        return out;
    }
}
