package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.List;
import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.bukkit.block.BlockFace;

/**
 * The lake district (west-southwest, 120°..180°): a lake of three waters with islands, bridges and a ruined mill.
 *
 * <ul>
 *   <li>The west basin holds the ruin island with the broken watchtower ({@link LakeRuin}, the district's high
 *       point), reached by a stone arch bridge from the north shore and a boardwalk from the mill.</li>
 *   <li>A boardwalk runs from the island over the islet with its shrine to the east shore; stepping stones lead on to
 *       the willow island in the south basin, which a third walk joins to the west shore.</li>
 *   <li>The water mill with its wheel stands on the east shore at the end of its race ({@link LakeMill}), with its
 *       yard, granary and garden north of it ({@link LakeYard}).</li>
 *   <li>Around the water: the boathouse and the wooden lookout in the north-west corner, the fishers' cottage on the
 *       west shore, the gazebo knoll by the south-east road and a drowned colonnade in the open water
 *       ({@link LakeShore}); trees, reeds, lily pads and cover everywhere ({@link LakeFlora}).</li>
 * </ul>
 * The ground is planned first ({@link LakeTerrain}, where the buildings level their plots), then written, then built
 * on. All water lies at least seven blocks inside the district, behind sloping banks.
 */
final class LakeDistrict implements MapZone {

    /** Bounding box of the zone's columns (with a margin). */
    static final int MIN_X = -98;
    static final int MAX_X = -14;
    static final int MIN_Z = 0;
    static final int MAX_Z = 86;

    /** The lake: a west basin, a narrow middle and a south basin. */
    static final List<LakeTerrain.Blob> BASINS = List.of(
            new LakeTerrain.Blob(-69, 30, 15.5, 12.5, -10),
            new LakeTerrain.Blob(-56, 42, 9, 8, 0),
            new LakeTerrain.Blob(-58, 58, 12.5, 10.5, 35),
            new LakeTerrain.Blob(-50, 67, 5, 4, 20));

    static final List<LakeTerrain.Blob> ISLANDS = List.of(
            new LakeTerrain.Blob(-70, 32, 8, 7.5, -10),
            new LakeTerrain.Blob(-59, 60, 5, 4, 30),
            new LakeTerrain.Blob(-55, 44, 2.5, 2.2, 0));

    /** The knoll with the ruined gazebo, east of the lake. */
    static final List<LakeTerrain.Blob> HILLS = List.of(
            new LakeTerrain.Blob(LakeShore.GAZEBO_X, LakeShore.GAZEBO_Z, 8, 6.5, 0));

    @Override
    public String name() {
        return "lake";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isDistrict(CitadelLayout.District.LAKE, x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        Random random = canvas.random(name());
        LakeNoise noise = new LakeNoise(random.nextLong());
        LakeTerrain terrain = new LakeTerrain(canvas, noise, MIN_X, MIN_Z, MAX_X, MAX_Z, BASINS, ISLANDS, HILLS);
        terrain.plan();
        LakeRuin.plan(terrain);
        LakeMill.plan(terrain);
        LakeShore.plan(terrain);
        LakeYard.plan(terrain);
        terrain.write();

        LakeYard yard = new LakeYard(canvas, terrain, noise, new Random(random.nextLong()));
        yard.paths();
        new LakeRuin(canvas, new Random(random.nextLong())).build();
        new LakeMill(canvas, terrain, new Random(random.nextLong())).build();
        new LakeShore(canvas, terrain, new Random(random.nextLong())).build();
        yard.build();
        crossings(canvas, terrain, new Random(random.nextLong()));
        new LakeFlora(canvas, terrain, noise, new Random(random.nextLong()), MIN_X, MIN_Z, MAX_X, MAX_Z).build();
    }

    /** The arch bridge to the ruin island, the wooden walks across the lake and the piers. */
    private static void crossings(MapCanvas canvas, LakeTerrain terrain, Random random) {
        LakeBridges bridges = new LakeBridges(canvas, terrain, random);
        bridges.archBridge(LakeRuin.CX, LakeRuin.CZ - (int) LakeRuin.PLOT_RADIUS);
        // Ruin island <-> the mill's quay.
        bridges.boardwalk(new int[][] {{-66, 26}, {-48, 26}}, true);
        // Ruin island -> over the islet -> east shore.
        bridges.boardwalk(new int[][] {{-66, 38}, {-66, 44}, {-45, 44}}, true);
        // Willow island <-> west shore.
        bridges.boardwalk(new int[][] {{-62, 61}, {-71, 61}}, false);
        bridges.pier(LakeShore.BOATHOUSE_X, LakeShore.BOATHOUSE_Z + 3, BlockFace.SOUTH, 4);
        bridges.pier(-52, 73, BlockFace.NORTH, 6);
        // Rowboats left on the water (each only where the water is deep enough).
        bridges.rowboat(-66, 21, BlockFace.EAST);
        bridges.rowboat(-54, 50, BlockFace.SOUTH);
    }
}
