package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The canyon district (west-northwest, 180°–240°): a gorge of layered rock eight to ten blocks deep winding as an S
 * between the two roads. It is crossed by a fallen giant tree, an old stone bridge, a natural rock arch and a sagging
 * rope bridge, and reached by its two end ramps, quarry stairs on the keep side, a trail cut into its northern wall
 * and a ladder. Around it: a mesa lookout with two spiral ramps on the keep side, a ridge pierced by a cave in the
 * north-west, hoodoo pillars, boulders, an abandoned quarry camp and a broken watch post.
 *
 * <p>See {@link CanyonPlan} for the geometry; the builders run in order: height field ({@link CanyonTerrain},
 * {@link CanyonRocks#shape}), blocks, carving, crossings, ruins, scattered rock, plants.
 */
final class CanyonDistrict implements MapZone {

    /** Where the quarry stairs climb out of the gorge on the keep side. */
    private static final double QUARRY_S = 0.27;
    /** Where the trail along the northern wall leaves the gorge floor. */
    private static final double TRAIL_S = 0.27;

    @Override
    public String name() {
        return "canyon";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isDistrict(CitadelLayout.District.CANYON, x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        Random random = canvas.random(name());
        CanyonPlan plan = new CanyonPlan(random.nextLong());
        CanyonTerrain terrain = new CanyonTerrain(canvas, plan);
        CanyonRocks rocks = new CanyonRocks(canvas, plan, terrain, new Random(random.nextLong()));

        terrain.carveGorge();
        rocks.shape();
        terrain.wallTrail(TRAIL_S, false, 3.2);
        int[] quarryTop = quarryStairs(plan, terrain);
        terrain.roughSteps();
        terrain.write();

        rocks.carve();
        new CanyonCrossings(canvas, plan, terrain, rocks).build();
        CanyonRuins ruins = new CanyonRuins(canvas, plan, terrain);
        ruins.build();
        markWaysDown(plan, ruins, quarryTop);
        rocks.scatter();
        new CanyonFlora(canvas, plan, terrain, new Random(random.nextLong())).build();
    }

    /**
     * Wide cut-stone stairs from the gorge floor up the keep-side wall to the quarry camp.
     *
     * @return the column just past their top step, {x, z, facing ordinal}
     */
    private static int[] quarryStairs(CanyonPlan plan, CanyonTerrain terrain) {
        double[] n = plan.innerNormal(QUARRY_S);
        BlockFace facing = CanyonPlan.cardinal(n[0], n[1]);
        int[] start = plan.beside(QUARRY_S, plan.halfWidth(QUARRY_S) - 1.5, true);
        int base = terrain.top(start[0], start[1]);
        int[] top = terrain.stairs(start[0], start[1], base, facing, 5, CanyonPlan.GROUND,
                Material.STONE_BRICK_STAIRS, Material.STONE_BRICKS);
        return new int[] {top[0], top[1], facing.ordinal()};
    }

    /** Lantern posts beside the top of the quarry stairs and where the wall trail reaches the plateau. */
    private static void markWaysDown(CanyonPlan plan, CanyonRuins ruins, int[] quarryTop) {
        BlockFace facing = BlockFace.values()[quarryTop[2]];
        BlockFace side = CanyonTerrain.clockwise(facing);
        ruins.lanternPost(quarryTop[0] + side.getModX() * 3, quarryTop[1] + side.getModZ() * 3);
        ruins.lanternPost(quarryTop[0] - side.getModX() * 3, quarryTop[1] - side.getModZ() * 3);
        // The trail climbs about nine blocks at three blocks per four along the wall.
        double trailTop = TRAIL_S + 12.5 / plan.length();
        int[] post = plan.beside(trailTop, plan.halfWidth(trailTop) + 4.5, false);
        ruins.lanternPost(post[0], post[1]);
    }
}
