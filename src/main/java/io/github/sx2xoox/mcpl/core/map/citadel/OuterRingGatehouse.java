package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * A gatehouse where a road crosses the wall: a vaulted passage (seven wide, five to seven high, the road runs through
 * at ground level, a raised portcullis hangs in the vault) under a block of masonry with a gate hall at wall-walk
 * level (the walk runs through it, behind an arcade on the inner face) and a crenellated roof above, and two round
 * flanking towers. Each flanking tower has a guard room at ground level (doors to the passage, to the hills and onto
 * the ground outside the wall) and a hall at wall-walk level with a ladder to its crenellated rooftop.
 *
 * <p>The gatehouse is the tallest part of the wall: its roofs are at y 78 (players stand at 79, fifteen blocks above
 * the ground), reached by ladders inside the arena border.
 */
final class OuterRingGatehouse {

    private static final int GROUND = OuterRingPlan.GROUND;
    private static final int WALK = OuterRingPlan.WALK;
    private static final int ROOF = OuterRingPlan.ROOF;
    private static final int SPAN = 14;

    private final OuterRingKit kit;

    OuterRingGatehouse(OuterRingKit kit) {
        this.kit = kit;
    }

    void build(OuterRingPlan.Gate gate) {
        int[] c = CitadelLayout.polar(103, gate.angle());
        for (int x = c[0] - SPAN; x <= c[0] + SPAN; x++) {
            for (int z = c[1] - SPAN; z <= c[1] + SPAN; z++) {
                if (!kit.canWrite(x, z)) {
                    continue;
                }
                for (int side = -1; side <= 1; side += 2) {
                    if (gate.flankDist(x, z, side) < OuterRingPlan.GATE_FLANK_SIZE) {
                        flankColumn(gate, x, z, side);
                    }
                }
                if (gate.body(x, z) && !gate.flankFootprint(x, z)) {
                    bodyColumn(gate, x, z);
                }
            }
        }
        for (int x = c[0] - SPAN; x <= c[0] + SPAN; x++) {
            for (int z = c[1] - SPAN; z <= c[1] + SPAN; z++) {
                if (kit.canWrite(x, z) && gate.passage(x, z)) {
                    passageColumn(gate, x, z);
                }
            }
        }
        // The guard rooms open onto the passage (through the ring and any masonry between).
        for (int x = c[0] - SPAN; x <= c[0] + SPAN; x++) {
            for (int z = c[1] - SPAN; z <= c[1] + SPAN; z++) {
                double across = Math.abs(OuterRingPlan.v(x, z, gate.angle()));
                double u = OuterRingPlan.u(x, z, gate.angle());
                boolean door = across >= OuterRingPlan.GATE_HALF - 0.1 && across < OuterRingPlan.GATE_HALF + 1.4
                        && Math.abs(u - OuterRingPlan.GATE_FLANK_U) < 1.5;
                if (door && kit.canWrite(x, z)) {
                    kit.clearColumn(x, z, GROUND + 1, GROUND + 3);
                    kit.set(x, GROUND, z, kit.pick(OuterRingKit.PAVING, x, GROUND, z));
                }
            }
        }
        for (int side = -1; side <= 1; side += 2) {
            flankLadder(gate, side);
        }
        hallLadder(gate, c);
        brazier(gate);
    }

    // ------------------------------------------------------------------ body and passage

    /**
     * The gatehouse block over the passage: below the wall-walk it is solid masonry around the vault; at wall-walk
     * level a gate hall (the walk runs through it, behind an arcade on the inner face) under a crenellated roof.
     */
    private void bodyColumn(OuterRingPlan.Gate gate, int x, int z) {
        double u = OuterRingPlan.u(x, z, gate.angle());
        double v = OuterRingPlan.v(x, z, gate.angle());
        double across = Math.abs(v);
        boolean face = u < OuterRingPlan.WALL_IN + 1 || u >= OuterRingPlan.GATE_BODY_OUT - 1;
        for (int y = GROUND + 1; y < WALK; y++) {
            kit.set(x, y, z, face && archStone(y, across) ? BlockSpec.of(Material.POLISHED_ANDESITE)
                    : kit.wall(x, y, z));
        }
        kit.set(x, WALK, z, kit.pick(OuterRingKit.WALK, x, WALK, z));
        if (hall(gate, x, z)) {
            kit.clearColumn(x, z, WALK + 1, WALK + 3);
            kit.set(x, WALK + 4, z, kit.wall(x, WALK + 4, z));
        } else {
            for (int y = WALK + 1; y < ROOF; y++) {
                kit.set(x, y, z, kit.wall(x, y, z));
            }
            if (u >= OuterRingPlan.GATE_BODY_OUT - 1 && Math.floorMod((int) Math.round(v), 3) == 1) {
                kit.clear(x, WALK + 2, z);
            }
        }
        kit.set(x, ROOF, z, kit.pick(OuterRingKit.WALK, x, ROOF, z));
        if (bodyEdge(gate, x, z)) {
            kit.set(x, ROOF + 1, z, kit.pick(OuterRingKit.WALL_TOP, x, ROOF + 1, z));
            if (bodyMerlon(gate, x, z)) {
                kit.set(x, ROOF + 2, z, kit.pick(OuterRingKit.WALL_TOP, x, ROOF + 2, z));
            }
        }
    }

    /** Jambs and voussoirs framing the gate arch on both faces. */
    private static boolean archStone(int y, double across) {
        return y <= GROUND + 5 && across >= 3.5 && across < 4.5
                || y == GROUND + 6 && across >= 3.5 && across < 4.5
                || y == GROUND + 7 && across >= 2.5 && across < 3.5
                || y == GROUND + 8 && across >= 1.2 && across < 2.5;
    }

    /** Open columns of the gate hall (the inner part of the gatehouse block, between the arcade's pillars). */
    private static boolean hall(OuterRingPlan.Gate gate, int x, int z) {
        double u = OuterRingPlan.u(x, z, gate.angle());
        if (u >= OuterRingPlan.PARAPET_IN) {
            return false;
        }
        double v = OuterRingPlan.v(x, z, gate.angle());
        boolean pillar = u < OuterRingPlan.WALL_IN + 1 && Math.abs(v) < 4 && Math.floorMod((int) Math.round(v), 3) == 0;
        return !pillar;
    }

    private static boolean bodyOnly(OuterRingPlan.Gate gate, int x, int z) {
        return gate.body(x, z) && !gate.flankFootprint(x, z);
    }

    /** A column on the edge of the gatehouse roof (it carries the parapet). */
    private static boolean bodyEdge(OuterRingPlan.Gate gate, int x, int z) {
        if (!bodyOnly(gate, x, z)) {
            return false;
        }
        for (BlockFace face : OuterRingKit.cardinals()) {
            int nx = x + face.getModX();
            int nz = z + face.getModZ();
            if (!gate.body(nx, nz) && !gate.flankFootprint(nx, nz)) {
                return true;
            }
        }
        return false;
    }

    private static boolean bodyMerlonPattern(OuterRingPlan.Gate gate, int x, int z) {
        int u = (int) Math.round(OuterRingPlan.u(x, z, gate.angle()));
        int v = (int) Math.round(OuterRingPlan.v(x, z, gate.angle()));
        return bodyEdge(gate, x, z) && Math.floorMod(u + v, 3) != 0;
    }

    /** Merlons on the gatehouse roof, each next to a crenel so it can be climbed onto. */
    private static boolean bodyMerlon(OuterRingPlan.Gate gate, int x, int z) {
        if (!bodyMerlonPattern(gate, x, z)) {
            return false;
        }
        for (BlockFace face : OuterRingKit.cardinals()) {
            int nx = x + face.getModX();
            int nz = z + face.getModZ();
            if (bodyEdge(gate, nx, nz) && !bodyMerlonPattern(gate, nx, nz)) {
                return true;
            }
        }
        return false;
    }

    /** A ladder in the gate hall up through a hatch to the gatehouse roof, against its solid outer part. */
    private void hallLadder(OuterRingPlan.Gate gate, int[] c) {
        int bestX = 0;
        int bestZ = 0;
        BlockFace bestFace = null;
        double bestScore = -Double.MAX_VALUE;
        for (int x = c[0] - SPAN; x <= c[0] + SPAN; x++) {
            for (int z = c[1] - SPAN; z <= c[1] + SPAN; z++) {
                if (!bodyOnly(gate, x, z) || !hall(gate, x, z) || CitadelLayout.radius(x, z) > 104.6) {
                    continue;
                }
                for (BlockFace face : OuterRingKit.cardinals()) {
                    int nx = x + face.getModX();
                    int nz = z + face.getModZ();
                    if (!bodyOnly(gate, nx, nz) || hall(gate, nx, nz)) {
                        continue;
                    }
                    double v = Math.abs(OuterRingPlan.v(x, z, gate.angle()));
                    double score = -Math.abs(v - 2) - Math.abs(OuterRingPlan.u(nx, nz, gate.angle()) - 102);
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = x;
                        bestZ = z;
                        bestFace = face.getOppositeFace();
                    }
                }
            }
        }
        if (bestFace == null) {
            return;
        }
        for (int y = WALK + 1; y <= ROOF; y++) {
            kit.set(bestX, y, bestZ, BlockSpec.of(Material.LADDER).facing(bestFace));
        }
    }

    /** The vault: open to y 69 across the passage, then narrowing in half-block steps (top slabs) to y 71. */
    private void passageColumn(OuterRingPlan.Gate gate, int x, int z) {
        double u = OuterRingPlan.u(x, z, gate.angle());
        double across = Math.abs(OuterRingPlan.v(x, z, gate.angle()));
        boolean roofed = gate.body(x, z);
        if (u >= OuterRingPlan.WALL_IN - 1 && u < OuterRingPlan.GATE_BODY_OUT + 1) {
            BlockSpec floor = across < 1 && kit.hash(x, z, 101) < 0.5 ? BlockSpec.of(Material.DIRT_PATH)
                    : kit.pick(OuterRingKit.PAVING, x, GROUND, z);
            kit.set(x, GROUND, z, floor);
        }
        if (!roofed) {
            return;
        }
        kit.clearColumn(x, z, GROUND + 1, GROUND + 5);
        boolean portcullis = u >= 101.5 && u < 102.5;
        BlockSpec slab = Blocks.slabTop(Material.STONE_BRICK_SLAB);
        BlockSpec bars = BlockSpec.of(Material.IRON_BARS);
        if (across < 2.5) {
            kit.set(x, GROUND + 6, z, portcullis ? bars : BlockSpec.AIR);
        } else {
            kit.set(x, GROUND + 6, z, slab);
        }
        if (across < 1.2) {
            kit.set(x, GROUND + 7, z, portcullis ? bars : BlockSpec.AIR);
        } else if (across < 2.5) {
            kit.set(x, GROUND + 7, z, slab);
        }
        boolean face = u < OuterRingPlan.WALL_IN + 1 || u >= OuterRingPlan.GATE_BODY_OUT - 1;
        if (face && across < 0.75) {
            kit.set(x, GROUND + 8, z, Material.CHISELED_STONE_BRICKS);
            kit.set(x, GROUND + 7, z, Blocks.lantern(false, true));
        }
    }

    // ------------------------------------------------------------------ flanking towers

    private void flankColumn(OuterRingPlan.Gate gate, int x, int z, int side) {
        double d = gate.flankDist(x, z, side);
        double u = OuterRingPlan.u(x, z, gate.angle());
        double across = Math.abs(OuterRingPlan.v(x, z, gate.angle()));
        boolean ring = d >= OuterRingPlan.GATE_FLANK_SIZE - 1;
        // Ground floor: a guard room.
        if (ring) {
            for (int y = GROUND + 1; y < WALK; y++) {
                kit.set(x, y, z, kit.wall(x, y, z));
            }
            // Doors to the hills and onto the ground outside the wall, away from the passage.
            boolean toHills = u > OuterRingPlan.GATE_FLANK_U + 2
                    && Math.abs(across - OuterRingPlan.GATE_FLANK_V) < 1.5;
            boolean toApron = across > OuterRingPlan.GATE_FLANK_V + 2 && u < OuterRingPlan.GATE_FLANK_U;
            if (toHills || toApron) {
                kit.clearColumn(x, z, GROUND + 1, GROUND + 3);
                kit.set(x, GROUND, z, kit.pick(OuterRingKit.PAVING, x, GROUND, z));
            }
        } else {
            kit.set(x, GROUND, z, kit.pick(OuterRingKit.PAVING, x, GROUND, z));
            kit.clearColumn(x, z, GROUND + 1, GROUND + 6);
            for (int y = GROUND + 7; y < WALK; y++) {
                kit.set(x, y, z, kit.wall(x, y, z));
            }
        }
        kit.set(x, WALK, z, kit.pick(OuterRingKit.WALK, x, WALK, z));
        // Wall-walk level: a hall open towards the walk and the gate roof, a rooftop above.
        if (!ring) {
            kit.clearColumn(x, z, WALK + 1, ROOF - 1);
            kit.set(x, ROOF, z, kit.pick(OuterRingKit.WALK, x, ROOF, z));
            return;
        }
        boolean open = CitadelLayout.radius(x, z) < OuterRingPlan.WALL_OUT || across < OuterRingPlan.GATE_HALF + 1.5;
        if (open) {
            kit.clearColumn(x, z, WALK + 1, WALK + 3);
        }
        for (int y = open ? WALK + 4 : WALK + 1; y <= ROOF; y++) {
            kit.set(x, y, z, kit.wall(x, y, z));
        }
        kit.set(x, ROOF + 1, z, kit.pick(OuterRingKit.WALL_TOP, x, ROOF + 1, z));
        if (flankMerlon(gate, x, z, side)) {
            kit.set(x, ROOF + 2, z, kit.pick(OuterRingKit.WALL_TOP, x, ROOF + 2, z));
        }
    }

    private static boolean flankRing(OuterRingPlan.Gate gate, int x, int z, int side) {
        double d = gate.flankDist(x, z, side);
        return d >= OuterRingPlan.GATE_FLANK_SIZE - 1 && d < OuterRingPlan.GATE_FLANK_SIZE;
    }

    private static boolean flankMerlonPattern(OuterRingPlan.Gate gate, int x, int z, int side) {
        int[] center = gate.flank(side);
        double a = Math.atan2(z - center[1], x - center[0]);
        return flankRing(gate, x, z, side)
                && Math.floorMod((int) Math.floor((a + Math.PI) * OuterRingPlan.GATE_FLANK_SIZE), 3) != 2;
    }

    /** Merlons around a flanking tower's rooftop, each next to a crenel so it can be climbed onto. */
    private static boolean flankMerlon(OuterRingPlan.Gate gate, int x, int z, int side) {
        if (!flankMerlonPattern(gate, x, z, side)) {
            return false;
        }
        for (BlockFace face : OuterRingKit.cardinals()) {
            int nx = x + face.getModX();
            int nz = z + face.getModZ();
            if (flankRing(gate, nx, nz, side) && !flankMerlonPattern(gate, nx, nz, side)) {
                return true;
            }
        }
        return false;
    }

    /** A ladder in each flanking tower's hall, against its outer side, up through a hatch to the rooftop. */
    private void flankLadder(OuterRingPlan.Gate gate, int side) {
        int[] center = gate.flank(side);
        int bestX = 0;
        int bestZ = 0;
        BlockFace bestFace = null;
        double bestScore = -Double.MAX_VALUE;
        double size = OuterRingPlan.GATE_FLANK_SIZE;
        for (int x = center[0] - 4; x <= center[0] + 4; x++) {
            for (int z = center[1] - 4; z <= center[1] + 4; z++) {
                if (gate.flankDist(x, z, side) >= size - 1) {
                    continue;
                }
                if (CitadelLayout.radius(x, z) > 104.6) {
                    continue;
                }
                for (BlockFace face : OuterRingKit.cardinals()) {
                    int nx = x + face.getModX();
                    int nz = z + face.getModZ();
                    double nd = gate.flankDist(nx, nz, side);
                    if (nd < size - 1 || nd >= size) {
                        continue;
                    }
                    double nAcross = Math.abs(OuterRingPlan.v(nx, nz, gate.angle()));
                    if (CitadelLayout.radius(nx, nz) < OuterRingPlan.WALL_OUT
                            || nAcross < OuterRingPlan.GATE_HALF + 1.5) {
                        continue;
                    }
                    // Against the far side of the tower, inside the arena border.
                    double score = nAcross - CitadelLayout.radius(x, z) * 0.5;
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = x;
                        bestZ = z;
                        bestFace = face.getOppositeFace();
                    }
                }
            }
        }
        if (bestFace == null) {
            return;
        }
        for (int y = WALK + 1; y <= ROOF; y++) {
            kit.set(bestX, y, bestZ, BlockSpec.of(Material.LADDER).facing(bestFace));
        }
    }

    /** A lit campfire on the gatehouse roof, where the guards kept watch. */
    private void brazier(OuterRingPlan.Gate gate) {
        int[] at = CitadelLayout.polar(103.5, gate.angle());
        kit.set(at[0], ROOF + 1, at[1], Blocks.campfire(true));
    }
}
