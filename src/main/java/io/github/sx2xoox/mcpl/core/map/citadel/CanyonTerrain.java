package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.jspecify.annotations.Nullable;

/**
 * The ground of the canyon district as a height field: the plateau, the gorge with its floor, scree and end ramps,
 * the trails and stairs that lead down into it, and the raised rock (ridge, mesa) other builders add. {@link #write}
 * turns it into blocks: soil on the plateau, layered rock ({@link CanyonPlan#stratum}) in every cliff.
 */
final class CanyonTerrain {

    /** What a column's top is, which decides its surface blocks. */
    enum Kind {
        /** Grass plateau at ground level. */
        PLATEAU,
        /** Rocky lip along the gorge. */
        RIM,
        /** Gorge floor. */
        FLOOR,
        /** Rubble heaped at the foot of the gorge walls. */
        SCREE,
        /** A rough shelf in a cliff. */
        SHELF,
        /** A walkable rock ramp (end ramps, wall trails, ridge spine): gets rough steps. */
        RAMP,
        /** Raised natural rock (ridge, mesa). */
        ROCK,
        /** Grassy top of raised rock. */
        ROCK_TOP,
        /** Cut stone stairs (quarry stairs). */
        STEPS
    }

    private static final int SIZE = CanyonPlan.SIZE_X * CanyonPlan.SIZE_Z;
    /** Blocks up per block along a wall trail. */
    private static final double TRAIL_SLOPE = 0.75;

    private final MapCanvas canvas;
    private final CanyonPlan plan;
    private final int[] top = new int[SIZE];
    private final Kind[] kind = new Kind[SIZE];
    private final @Nullable BlockFace[] steps = new BlockFace[SIZE];
    private final Material[] stepMaterial = new Material[SIZE];
    private final boolean[] reserved = new boolean[SIZE];

    CanyonTerrain(MapCanvas canvas, CanyonPlan plan) {
        this.canvas = canvas;
        this.plan = plan;
        java.util.Arrays.fill(top, CanyonPlan.GROUND);
        java.util.Arrays.fill(kind, Kind.PLATEAU);
    }

    // ------------------------------------------------------------------ queries

    /** The y of the column's top block (ground level outside the district). */
    int top(int x, int z) {
        return CanyonPlan.inBox(x, z) ? top[CanyonPlan.index(x, z)] : CanyonPlan.GROUND;
    }

    Kind kind(int x, int z) {
        return CanyonPlan.inBox(x, z) ? kind[CanyonPlan.index(x, z)] : Kind.PLATEAU;
    }

    /** True for columns of the gorge below the plateau (floor, scree, end ramps). */
    boolean inGorge(int x, int z) {
        Kind k = kind(x, z);
        return (k == Kind.FLOOR || k == Kind.SCREE || k == Kind.RAMP) && top(x, z) < CanyonPlan.GROUND;
    }

    boolean owns(int x, int z) {
        return CanyonPlan.inBox(x, z) && canvas.canWrite(x, z);
    }

    // ------------------------------------------------------------------ shaping

    /** Sets a column's top and kind (no effect outside the district). */
    void set(int x, int z, int y, Kind k) {
        if (!owns(x, z)) {
            return;
        }
        int i = CanyonPlan.index(x, z);
        top[i] = y;
        kind[i] = k;
        steps[i] = null;
        stepMaterial[i] = null;
    }

    /** Raises a column to {@code y} if it is lower. */
    void raise(int x, int z, int y, Kind k) {
        if (owns(x, z) && top(x, z) < y) {
            set(x, z, y, k);
        }
    }

    /** Makes the column's top block a stair of {@code material} climbed towards {@code facing}. */
    void step(int x, int z, int y, BlockFace facing, Material material) {
        if (!owns(x, z)) {
            return;
        }
        set(x, z, y, Kind.STEPS);
        int i = CanyonPlan.index(x, z);
        steps[i] = facing;
        stepMaterial[i] = material;
    }

    /** Carves the gorge: floor, scree at the walls, jagged rims and the rising end ramps. */
    void carveGorge() {
        for (int x = CanyonPlan.MIN_X; x <= CanyonPlan.MAX_X; x++) {
            for (int z = CanyonPlan.MIN_Z; z <= CanyonPlan.MAX_Z; z++) {
                if (!owns(x, z)) {
                    continue;
                }
                double s = plan.s(x, z);
                double e = plan.rimDistance(x, z);
                int floor = plan.floorY(s);
                if (floor >= CanyonPlan.GROUND - 1) {
                    // The shallow tip of an end ramp: a rocky dip.
                    if (e < 0) {
                        set(x, z, floor, Kind.RAMP);
                    } else if (e < 1.4) {
                        set(x, z, CanyonPlan.GROUND, Kind.RIM);
                    }
                    continue;
                }
                boolean ramp = plan.distanceFromEnd(s) < (CanyonPlan.GROUND - floor) / CanyonPlan.END_RAMP_SLOPE + 1.5;
                if (e < 0) {
                    boolean scree = e > -1.4 && plan.noise(x / 2.5, z / 2.5, 31) > -0.15 && !ramp;
                    set(x, z, scree ? floor + 1 : floor, ramp ? Kind.RAMP : scree ? Kind.SCREE : Kind.FLOOR);
                } else if (e < 0.9 && !ramp && plan.noise(x / 3.0, z / 3.0, 32) > 0.4) {
                    // A shelf low enough to drop from to the floor (at most five blocks, even where the floor
                    // beside it is a block lower), never a trap.
                    int shelf = Math.min(CanyonPlan.GROUND - 2 - plan.hash(x, 5, z) % 2, floor + 4);
                    if (shelf >= floor + 2) {
                        set(x, z, shelf, Kind.SHELF);
                    }
                } else if (e < 1.3 && plan.noise(x / 2.2, z / 2.2, 33) > -0.1) {
                    set(x, z, CanyonPlan.GROUND, Kind.RIM);
                }
            }
        }
    }

    /**
     * A trail cut into one wall of the gorge (three or so blocks deep): it leaves the floor at {@code from} and climbs
     * along the wall towards growing {@code s} until it reaches the plateau.
     */
    void wallTrail(double from, boolean inner, double width) {
        for (int x = CanyonPlan.MIN_X; x <= CanyonPlan.MAX_X; x++) {
            for (int z = CanyonPlan.MIN_Z; z <= CanyonPlan.MAX_Z; z++) {
                double s = plan.s(x, z);
                if (!owns(x, z) || s < from || plan.inner(x, z) != inner) {
                    continue;
                }
                double e = plan.rimDistance(x, z);
                if (e < -0.6 || e >= width) {
                    continue;
                }
                int y = plan.floorY(from) + 1 + (int) Math.floor((s - from) * plan.length() * TRAIL_SLOPE);
                if (y < CanyonPlan.GROUND && top(x, z) > y) {
                    set(x, z, y, Kind.RAMP);
                    reserve(x, z);
                }
            }
        }
    }

    /**
     * Straight cut stairs {@code width} wide: the first step is one above {@code baseY} at {@code x, z}, every next
     * step one further towards {@code facing} and one higher, up to {@code topY}. Columns in the way are cut down or
     * built up; low side columns get a cheek one block above the steps. A flat landing breaks the flight halfway.
     *
     * @return the column just past the top step, in the middle of the flight: {x, z}
     */
    int[] stairs(int x, int z, int baseY, BlockFace facing, int width, int topY, Material stair, Material landing) {
        BlockFace side = clockwise(facing);
        int left = -(width / 2);
        int right = width - width / 2 - 1;
        int y = baseY + 1;
        int i = 0;
        int landingAt = (topY - baseY) / 2;
        boolean landed = false;
        while (y <= topY) {
            boolean flat = !landed && y - baseY - 1 == landingAt && topY - baseY > 5;
            for (int j = left - 1; j <= right + 1; j++) {
                int cx = x + facing.getModX() * i + side.getModX() * j;
                int cz = z + facing.getModZ() * i + side.getModZ() * j;
                if (j < left || j > right) {
                    if (top(cx, cz) < y + 1 && kind(cx, cz) != Kind.STEPS) {
                        set(cx, cz, y + 1, Kind.STEPS);
                    }
                    reserve(cx, cz);
                    continue;
                }
                if (flat) {
                    set(cx, cz, y, Kind.STEPS);
                    stepMaterial[CanyonPlan.index(cx, cz)] = landing;
                } else {
                    step(cx, cz, y, facing, stair);
                }
                reserve(cx, cz);
            }
            if (flat) {
                landed = true;
            } else {
                y++;
            }
            i++;
        }
        // Keep the arrival free.
        for (int k = 0; k < 2; k++) {
            for (int j = left; j <= right; j++) {
                reserve(x + facing.getModX() * (i + k) + side.getModX() * j,
                        z + facing.getModZ() * (i + k) + side.getModZ() * j);
            }
        }
        return new int[] {x + facing.getModX() * i, z + facing.getModZ() * i};
    }

    static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    // ------------------------------------------------------------------ reserved columns

    /** Marks a column as part of a path (stairs, bridge heads, trails) that decoration must keep free. */
    void reserve(int x, int z) {
        if (CanyonPlan.inBox(x, z)) {
            reserved[CanyonPlan.index(x, z)] = true;
        }
    }

    /** Reserves every column within {@code radius} of {@code x, z}. */
    void reserve(int x, int z, double radius) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    reserve(x + dx, z + dz);
                }
            }
        }
    }

    boolean reserved(int x, int z) {
        return !CanyonPlan.inBox(x, z) || reserved[CanyonPlan.index(x, z)];
    }

    /**
     * Gives walkable ramps rough steps: where a ramp column is one block above its neighbour and the column behind it
     * is not lower, its top becomes a stair climbed away from that neighbour.
     */
    void roughSteps() {
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        for (int x = CanyonPlan.MIN_X; x <= CanyonPlan.MAX_X; x++) {
            for (int z = CanyonPlan.MIN_Z; z <= CanyonPlan.MAX_Z; z++) {
                if (!owns(x, z) || kind(x, z) != Kind.RAMP) {
                    continue;
                }
                int h = top(x, z);
                BlockFace found = null;
                int count = 0;
                for (BlockFace face : faces) {
                    int bx = x - face.getModX();
                    int bz = z - face.getModZ();
                    int ax = x + face.getModX();
                    int az = z + face.getModZ();
                    if (top(bx, bz) == h - 1 && top(ax, az) >= h && isWalkSurface(bx, bz)) {
                        found = face;
                        count++;
                    }
                }
                if (count == 1) {
                    int roll = plan.roll(x, h, z, 41);
                    Material material = roll < 40 ? Material.COBBLESTONE_STAIRS : roll < 70 ? Material.ANDESITE_STAIRS
                            : roll < 88 ? Material.STONE_STAIRS : Material.MOSSY_COBBLESTONE_STAIRS;
                    int i = CanyonPlan.index(x, z);
                    steps[i] = found;
                    stepMaterial[i] = material;
                }
            }
        }
    }

    private boolean isWalkSurface(int x, int z) {
        Kind k = kind(x, z);
        return k == Kind.RAMP || k == Kind.FLOOR || k == Kind.SCREE || k == Kind.PLATEAU || k == Kind.RIM
                || k == Kind.STEPS;
    }

    // ------------------------------------------------------------------ blocks

    /** Writes every column of the district. */
    void write() {
        for (int x = CanyonPlan.MIN_X; x <= CanyonPlan.MAX_X; x++) {
            for (int z = CanyonPlan.MIN_Z; z <= CanyonPlan.MAX_Z; z++) {
                if (owns(x, z)) {
                    writeColumn(x, z);
                }
            }
        }
    }

    private void writeColumn(int x, int z) {
        int i = CanyonPlan.index(x, z);
        int h = top[i];
        Kind k = kind[i];
        int bottom = CanyonPlan.GROUND;
        while (bottom > canvas.minY() && canvas.get(x, bottom - 1, z) != null) {
            bottom--;
        }
        bottom = Math.min(bottom, h - 5);
        boolean soil = k == Kind.PLATEAU || k == Kind.RIM || k == Kind.ROCK_TOP;
        int soilDepth = k == Kind.PLATEAU ? 2 : 1;
        for (int y = bottom; y < h; y++) {
            Material material = soil && y >= h - soilDepth ? soilUnder(x, y, z) : plan.stratum(x, y, z);
            canvas.set(x, y, z, BlockSpec.of(material));
        }
        BlockFace facing = steps[i];
        if (facing != null) {
            canvas.set(x, h, z, Blocks.stairs(stepMaterial[i], facing));
            canvas.set(x, h - 1, z, BlockSpec.of(plan.stratum(x, h - 1, z)));
        } else if (stepMaterial[i] != null) {
            canvas.set(x, h, z, BlockSpec.of(stepMaterial[i]));
        } else {
            canvas.set(x, h, z, BlockSpec.of(surface(x, h, z, k)));
        }
        for (int y = h + 1; y <= CanyonPlan.GROUND; y++) {
            if (canvas.get(x, y, z) != null) {
                canvas.clear(x, y, z);
            }
        }
    }

    private Material soilUnder(int x, int y, int z) {
        int roll = plan.roll(x, y, z, 51);
        return roll < 75 ? Material.DIRT : roll < 90 ? Material.COARSE_DIRT : Material.ROOTED_DIRT;
    }

    private Material surface(int x, int y, int z, Kind k) {
        int roll = plan.roll(x, y, z, 61);
        return switch (k) {
            case PLATEAU -> plateau(x, z, roll);
            case RIM -> roll < 30 ? Material.COARSE_DIRT : roll < 48 ? Material.STONE : roll < 64 ? Material.ANDESITE
                    : roll < 74 ? Material.GRAVEL : roll < 82 ? Material.TUFF : Material.GRASS_BLOCK;
            case FLOOR -> floor(x, z, roll);
            case SCREE -> roll < 30 ? Material.COBBLESTONE : roll < 50 ? Material.GRAVEL : roll < 68 ? Material.ANDESITE
                    : roll < 84 ? Material.MOSSY_COBBLESTONE : Material.TUFF;
            case SHELF, ROCK -> plan.stratum(x, y, z);
            case RAMP -> roll < 35 ? Material.COARSE_DIRT : roll < 55 ? Material.GRAVEL : roll < 75 ? Material.ANDESITE
                    : roll < 90 ? Material.COBBLESTONE : Material.STONE;
            case ROCK_TOP -> roll < 45 ? Material.GRASS_BLOCK : roll < 70 ? Material.COARSE_DIRT
                    : roll < 85 ? Material.MOSS_BLOCK : Material.ANDESITE;
            case STEPS -> roll < 55 ? Material.STONE_BRICKS : roll < 78 ? Material.MOSSY_STONE_BRICKS
                    : Material.CRACKED_STONE_BRICKS;
        };
    }

    private Material plateau(int x, int z, int roll) {
        double dry = plan.noise(x / 10.0, z / 10.0, 71) + 0.25 * plan.noise(x / 3.0, z / 3.0, 72);
        if (dry > 0.45) {
            return roll < 50 ? Material.COARSE_DIRT : roll < 72 ? Material.GRASS_BLOCK : roll < 86 ? Material.GRAVEL
                    : Material.ROOTED_DIRT;
        }
        if (dry > 0.3 && roll < 30) {
            return Material.COARSE_DIRT;
        }
        return roll < 2 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
    }

    private Material floor(int x, int z, int roll) {
        // A dry stream bed of pebbles winds down the middle of the floor.
        if (plan.distance(x, z) < 1.3 + 0.6 * plan.noise(x / 3.0, z / 3.0, 82)) {
            return roll < 38 ? Material.GRAVEL : roll < 58 ? Material.COBBLESTONE : roll < 72 ? Material.ANDESITE
                    : roll < 84 ? Material.STONE : roll < 94 ? Material.MOSSY_COBBLESTONE : Material.CLAY;
        }
        double wet = plan.noise(x / 5.0, z / 5.0, 81);
        if (wet > 0.3) {
            return roll < 40 ? Material.MOSS_BLOCK : roll < 72 ? Material.GRASS_BLOCK : roll < 86
                    ? Material.MOSSY_COBBLESTONE : Material.ROOTED_DIRT;
        }
        return roll < 32 ? Material.COARSE_DIRT : roll < 50 ? Material.GRASS_BLOCK : roll < 62 ? Material.PODZOL
                : roll < 72 ? Material.PACKED_MUD : roll < 82 ? Material.GRAVEL : roll < 92 ? Material.ROOTED_DIRT
                : Material.TUFF;
    }
}
