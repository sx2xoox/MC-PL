package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The curtain wall: nine blocks high and four thick, a three-wide wall-walk on top with a crenellated parapet on the
 * outer side, broken ends stepping down into the breaches (climbable from the rubble), posterns, and the stair flights
 * that lead up from the inner strip. Gatehouses and towers build their own columns.
 *
 * <p>Every top surface can be reached on foot: the parapet course is one block above the walk and the merlons one
 * above the parapet, so no merlon top becomes an unreachable ledge.
 */
final class OuterRingWall {

    private static final int GROUND = OuterRingPlan.GROUND;
    private static final int WALK = OuterRingPlan.WALK;

    private final OuterRingPlan plan;
    private final OuterRingTerrain terrain;
    private final OuterRingKit kit;

    OuterRingWall(OuterRingPlan plan, OuterRingTerrain terrain, OuterRingKit kit) {
        this.plan = plan;
        this.terrain = terrain;
        this.kit = kit;
    }

    void build() {
        int r = (int) Math.ceil(OuterRingPlan.WALL_OUT) + 1;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (curtain(x, z)) {
                    column(x, z);
                }
            }
        }
        niches();
        corbels();
        posterns();
        flights();
    }

    // ------------------------------------------------------------------ curtain

    private void column(int x, int z) {
        int top = plan.wallTop(x, z);
        boolean parapet = OuterRingPlan.inParapet(x, z);
        boolean full = top >= WALK;
        double angle = CitadelLayout.angle(x, z);
        if (parapet) {
            if (full) {
                top = course(x, z) ? WALK + 1 : WALK;
            } else if (kit.hash(x, z, 72) < 0.45) {
                // The broken ends crumble on the outside first.
                top = Math.max(GROUND + 1, top - 1);
            }
        }
        boolean ivy = ivy(x, z, angle);
        int ivyTop = GROUND + 2 + (int) (kit.noise(angle * 1.7, 3.5, 74) * 7);
        for (int y = GROUND + 1; y <= top; y++) {
            BlockSpec block;
            if (y == top && !parapet && full) {
                block = kit.pick(OuterRingKit.WALK, x, y, z);
            } else if (ivy && y <= ivyTop && y < WALK) {
                block = leaves(x, y, z);
            } else if (y == GROUND + 5 && CitadelLayout.radius(x, z) < OuterRingPlan.WALL_IN + 1 && full) {
                // A string course of polished stone runs along the inner face above the niches.
                block = BlockSpec.of(Material.POLISHED_ANDESITE);
            } else if (y == WALK && parapet) {
                // A string course of polished stone marks the level of the wall-walk on the outside.
                block = BlockSpec.of(Material.POLISHED_ANDESITE);
            } else {
                block = kit.wall(x, y, z);
            }
            kit.set(x, y, z, block);
        }
        if (parapet && full && top == WALK + 1) {
            if (merlon(x, z)) {
                kit.set(x, WALK + 2, z, kit.wall(x, WALK + 2, z));
            }
            // Arrow slits: one-block holes in the parapet's face.
            if (slit(angle)) {
                kit.clear(x, GROUND + 6, z);
            }
        }
        if (!parapet && full && kit.hash(x, z, 75) < 0.035) {
            walkDebris(x, z);
        }
    }

    /** A column of the curtain itself (not a tower, gatehouse or fallen part). */
    private boolean curtain(int x, int z) {
        return OuterRingPlan.inWallBand(x, z) && terrain.kind(x, z) == OuterRingTerrain.Kind.STRUCTURE
                && plan.towerAt(x, z) == null && plan.gateAt(x, z) == null;
    }

    /** Weathering along the wall (0..1): above 0.68 the merlons are gone, above 0.8 the parapet too. */
    private double damage(int x, int z) {
        return kit.noise(CitadelLayout.angle(x, z) / 7.0, 0.5, 71);
    }

    /** A full-height parapet column whose course stands one block above the wall-walk. */
    private boolean course(int x, int z) {
        return curtain(x, z) && OuterRingPlan.inParapet(x, z) && plan.wallTop(x, z) >= WALK && damage(x, z) <= 0.8;
    }

    /** Two merlons, one crenel, measured along the outer face; some merlons have fallen. */
    private boolean merlonPattern(int x, int z) {
        int s = (int) Math.floor(Math.toRadians(CitadelLayout.angle(x, z)) * OuterRingPlan.WALL_OUT);
        return course(x, z) && Math.floorMod(s, 3) != 2 && damage(x, z) < 0.68 && kit.hash(x, z, 73) > 0.08;
    }

    /** A merlon stands only next to a crenel, so its top can always be climbed onto. */
    private boolean merlon(int x, int z) {
        if (!merlonPattern(x, z)) {
            return false;
        }
        for (BlockFace face : OuterRingKit.cardinals()) {
            int nx = x + face.getModX();
            int nz = z + face.getModZ();
            if (course(nx, nz) && !merlonPattern(nx, nz)) {
                return true;
            }
        }
        return false;
    }

    private static boolean slit(double angle) {
        int s = (int) Math.floor(Math.toRadians(angle) * OuterRingPlan.WALL_OUT);
        return Math.floorMod(s, 9) == 4;
    }

    /** Patches of ivy (leaves) creeping up both faces. */
    private boolean ivy(int x, int z, double angle) {
        double r = CitadelLayout.radius(x, z);
        boolean face = r < OuterRingPlan.WALL_IN + 1 || r >= OuterRingPlan.PARAPET_IN;
        return face && kit.noise(angle * 0.9, r > 100 ? 7.5 : 1.5, 76) > 0.66;
    }

    private BlockSpec leaves(int x, int y, int z) {
        double pick = kit.hash(x, y, z, 77);
        if (pick < 0.45) {
            return Blocks.leaves(Material.AZALEA_LEAVES);
        }
        if (pick < 0.65) {
            return Blocks.leaves(Material.FLOWERING_AZALEA_LEAVES);
        }
        return pick < 0.85 ? Blocks.leaves(Material.OAK_LEAVES) : kit.wall(x, y, z);
    }

    /** Loose stones and moss on the wall-walk (one block high at most, so they never block it). */
    private void walkDebris(int x, int z) {
        double pick = kit.hash(x, z, 78);
        BlockSpec debris = pick < 0.45 ? Blocks.slab(Material.COBBLESTONE_SLAB)
                : pick < 0.75 ? BlockSpec.of(Material.MOSS_CARPET)
                : Blocks.slab(Material.STONE_BRICK_SLAB);
        kit.setIfEmpty(x, WALK + 1, z, debris);
    }

    // ------------------------------------------------------------------ niches

    /**
     * A blind arcade along the inner face: every ten blocks a niche three wide, three high and one deep at the foot of
     * the wall (cover for anyone on the inner strip), some with a torch or a barrel.
     */
    private void niches() {
        int r = (int) Math.ceil(OuterRingPlan.WALL_IN) + 1;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (!curtain(x, z) || CitadelLayout.radius(x, z) >= OuterRingPlan.WALL_IN + 1) {
                    continue;
                }
                int slot = Math.floorMod(arc(x, z), 10);
                if (slot < 4 || slot > 6 || !clearAround(x, z)) {
                    continue;
                }
                kit.clearColumn(x, z, GROUND + 1, GROUND + 3);
                kit.set(x, GROUND, z, kit.pick(OuterRingKit.PAVING, x, GROUND, z));
                kit.set(x, GROUND + 4, z, slot == 5 ? BlockSpec.of(Material.CHISELED_STONE_BRICKS)
                        : BlockSpec.of(Material.POLISHED_ANDESITE));
                if (slot == 5) {
                    nicheProp(x, z);
                }
            }
        }
    }

    /** Position along the inner face, one per block. */
    private static int arc(int x, int z) {
        return (int) Math.floor(Math.toRadians(CitadelLayout.angle(x, z)) * OuterRingPlan.WALL_IN);
    }

    /** True if the wall stands full height around the column (no tower, gate, postern, flight or breach close by). */
    private boolean clearAround(int x, int z) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int nx = x + dx;
                int nz = z + dz;
                if (plan.towerAt(nx, nz) != null || plan.gateAt(nx, nz) != null || plan.inPostern(nx, nz)
                        || plan.flightTop(nx, nz) >= 0
                        || OuterRingPlan.inWallBand(nx, nz) && plan.wallTop(nx, nz) < WALK) {
                    return false;
                }
            }
        }
        return true;
    }

    private void nicheProp(int x, int z) {
        double pick = kit.hash(x, z, 81);
        BlockFace inward = OuterRingKit.cardinal(-x, -z);
        if (pick < 0.4) {
            BlockSpec back = kit.canvas.get(x - inward.getModX(), GROUND + 2, z - inward.getModZ());
            if (back != null && !back.isAir()) {
                kit.set(x, GROUND + 2, z, Blocks.wallTorch(false, inward));
            }
        } else if (pick < 0.7) {
            kit.set(x, GROUND + 1, z, Material.BARREL);
        }
    }

    // ------------------------------------------------------------------ corbels

    /**
     * The wall-walk overhangs the inner face by one block on a corbel course (top slabs), widening the walk and
     * giving the inner face a shadow line. The strip below keeps eight blocks of headroom.
     */
    private void corbels() {
        int r = (int) Math.ceil(OuterRingPlan.WALL_IN) + 1;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double radius = CitadelLayout.radius(x, z);
                if (radius < OuterRingPlan.WALL_IN - 0.9 || radius >= OuterRingPlan.WALL_IN || !kit.canWrite(x, z)
                        || !clearAround(x, z)) {
                    continue;
                }
                kit.set(x, WALK - 1, z, Blocks.slabTop(kit.hash(x, z, 82) < 0.7 ? Material.STONE_BRICK_SLAB
                        : Material.COBBLESTONE_SLAB));
                kit.set(x, WALK, z, kit.pick(OuterRingKit.WALK, x, WALK, z));
            }
        }
    }

    // ------------------------------------------------------------------ posterns

    /** Small gates through the curtain: three wide, four high, with a keystone above. */
    private void posterns() {
        for (OuterRingPlan.Postern postern : plan.posterns) {
            int[] c = CitadelLayout.polar(100.5, postern.angle());
            for (int x = c[0] - 6; x <= c[0] + 6; x++) {
                for (int z = c[1] - 6; z <= c[1] + 6; z++) {
                    if (!OuterRingPlan.inWallBand(x, z) || !postern.contains(x, z)) {
                        continue;
                    }
                    kit.clearColumn(x, z, GROUND + 1, GROUND + 4);
                    kit.set(x, GROUND, z, kit.pick(OuterRingKit.PAVING, x, GROUND, z));
                    if (Math.abs(OuterRingPlan.v(x, z, postern.angle())) < 0.6) {
                        kit.set(x, GROUND + 5, z, Material.CHISELED_STONE_BRICKS);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ stair flights

    /**
     * Stair flights along the inner face, from the strip up to the wall-walk. The upper half stands on an arch, so the
     * strip stays walkable underneath (three blocks of headroom).
     */
    private void flights() {
        for (OuterRingPlan.Flight flight : plan.flights) {
            double a = Math.toRadians(flight.angle());
            // Walking up the flight goes against dir along v.
            double upX = Math.sin(a) * flight.dir();
            double upZ = -Math.cos(a) * flight.dir();
            BlockFace up = OuterRingKit.cardinal(upX, upZ);
            int[] c = CitadelLayout.polar(97.5, flight.angle());
            int reach = (int) Math.abs(flight.vTop()) + OuterRingPlan.Flight.STEPS + 3;
            for (int x = c[0] - reach; x <= c[0] + reach; x++) {
                for (int z = c[1] - reach; z <= c[1] + reach; z++) {
                    int top = flight.stepTop(x, z);
                    if (top < 0 || !kit.canWrite(x, z)) {
                        continue;
                    }
                    int from = top >= GROUND + 5 ? GROUND + 4 : GROUND + 1;
                    for (int y = from; y < top; y++) {
                        kit.set(x, y, z, kit.wall(x, y, z));
                    }
                    if (top == WALK) {
                        kit.set(x, top, z, kit.pick(OuterRingKit.WALK, x, top, z));
                    } else {
                        Material stair = kit.hash(x, z, 79) < 0.3 ? Material.MOSSY_STONE_BRICK_STAIRS
                                : Material.STONE_BRICK_STAIRS;
                        kit.set(x, top, z, OuterRingKit.stairs(stair, up));
                    }
                    if (top >= GROUND + 5) {
                        // The arch: clear headroom under the upper steps.
                        kit.clearColumn(x, z, GROUND + 1, GROUND + 3);
                    }
                }
            }
        }
    }
}
