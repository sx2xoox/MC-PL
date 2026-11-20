package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The natural rock of the canyon district: the mesa lookout and the ridge (shaped into the height field before it is
 * written), then the cave through the ridge, the natural rock arch over the gorge, hoodoo pillars, boulders and the
 * small pools on the gorge floor.
 */
final class CanyonRocks {

    /** The mesa: a flat-topped rock on the keep side with two spiral ramps and two ladders. */
    static final int MESA_X = -46;
    static final int MESA_Z = -15;
    static final int MESA_TOP = 74;
    private static final double MESA_CORE = 4.4;
    private static final double MESA_RAMP = 3.3;
    /** Degrees of the two notches between the ramps (around east and west, where the ladders are). */
    private static final double MESA_GAP = 9;

    /** The ridge in the north-west: its high end (A) looks over the northern reach, its low end (B) is a ramp. */
    static final double[] RIDGE_A = {-64, -57};
    static final double[] RIDGE_B = {-76, -45};
    static final int RIDGE_CREST = 72;
    /** Where the cave crosses the ridge, in blocks from B along it. */
    private static final double TUNNEL_AT = 10.5;

    /** Position along the gorge of the natural rock arch. */
    static final double ARCH_S = 0.68;

    private final MapCanvas canvas;
    private final CanyonPlan plan;
    private final CanyonTerrain terrain;
    private final Random random;

    CanyonRocks(MapCanvas canvas, CanyonPlan plan, CanyonTerrain terrain, Random random) {
        this.canvas = canvas;
        this.plan = plan;
        this.terrain = terrain;
        this.random = random;
    }

    // ------------------------------------------------------------------ height field

    /** Shapes the mesa and the ridge into the height field. */
    void shape() {
        mesa();
        ridge();
    }

    private void mesa() {
        int r = (int) Math.ceil(MESA_CORE + MESA_RAMP + 1.5);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = MESA_X + dx;
                int z = MESA_Z + dz;
                double dist = Math.hypot(dx, dz);
                double theta = Math.toDegrees(Math.atan2(dz, dx));
                if (theta < 0) {
                    theta += 360;
                }
                double rad = Math.toRadians(theta);
                double core = MESA_CORE + 0.7 * plan.noise(Math.cos(rad) * 1.7 + 40, Math.sin(rad) * 1.7 + 40, 91);
                if (dist <= core) {
                    terrain.raise(x, z, MESA_TOP, CanyonTerrain.Kind.ROCK_TOP);
                    continue;
                }
                if (dist > core + MESA_RAMP) {
                    continue;
                }
                double u = mesaRamp(theta);
                if (u < 0) {
                    continue;
                }
                int h = 65 + (int) Math.round(u * (MESA_TOP - 65));
                terrain.raise(x, z, h, h >= MESA_TOP ? CanyonTerrain.Kind.ROCK_TOP : CanyonTerrain.Kind.RAMP);
                terrain.reserve(x, z);
            }
        }
        // The feet of the ramps and the ladder notches stay clear.
        terrain.reserve(MESA_X + 9, MESA_Z + 1, 2.5);
        terrain.reserve(MESA_X - 9, MESA_Z - 1, 2.5);
    }

    /** How far up a mesa ramp a direction is (0 at its foot, 1 at the top), -1 in the notches. */
    private static double mesaRamp(double theta) {
        double span = 180 - 2 * MESA_GAP;
        if (theta >= MESA_GAP && theta <= 180 - MESA_GAP) {
            return (theta - MESA_GAP) / span;
        }
        if (theta >= 180 + MESA_GAP && theta <= 360 - MESA_GAP) {
            return (theta - 180 - MESA_GAP) / span;
        }
        return -1;
    }

    private void ridge() {
        double ax = RIDGE_A[0];
        double az = RIDGE_A[1];
        double bx = RIDGE_B[0];
        double bz = RIDGE_B[1];
        double length = Math.hypot(ax - bx, az - bz);
        double ux = (ax - bx) / length;
        double uz = (az - bz) / length;
        int minX = (int) Math.floor(Math.min(ax, bx)) - 8;
        int maxX = (int) Math.ceil(Math.max(ax, bx)) + 8;
        int minZ = (int) Math.floor(Math.min(az, bz)) - 8;
        int maxZ = (int) Math.ceil(Math.max(az, bz)) + 8;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                CanyonTerrain.Kind current = terrain.kind(x, z);
                if (current != CanyonTerrain.Kind.PLATEAU && current != CanyonTerrain.Kind.RIM) {
                    continue;
                }
                double along = (x - bx) * ux + (z - bz) * uz;
                double across = (x - bx) * -uz + (z - bz) * ux;
                if (along < -1 || along > length + 4) {
                    continue;
                }
                double q = Math.abs(across);
                if (along > length) {
                    q = Math.hypot(across, (along - length) * 1.3);
                }
                double crest = 64 + Math.min(RIDGE_CREST - 64, Math.max(0, along) * 0.95);
                double halfTop = 1.6 + 0.4 * plan.noise(along / 3.0, 3.3, 92);
                double halfBase = (4.4 + 0.9 * plan.noise(along / 2.6, across > 0 ? 7.7 : 1.1, 93))
                        * Math.min(1, 0.45 + Math.max(0, along) / 9);
                if (q > halfBase) {
                    continue;
                }
                if (q <= halfTop) {
                    int h = (int) Math.round(crest);
                    if (h > 64) {
                        terrain.raise(x, z, h, h >= RIDGE_CREST ? CanyonTerrain.Kind.ROCK_TOP
                                : CanyonTerrain.Kind.RAMP);
                    }
                    continue;
                }
                double side = (q - halfTop) / Math.max(0.5, halfBase - halfTop);
                int h;
                if (across < 0) {
                    // North-east flank: a sheer cliff (the ladder side).
                    h = (int) Math.round(crest);
                } else if (along > 3.5 && along < 9.5) {
                    // South-west flank: a natural staircase of one-block ledges.
                    h = (int) Math.round(crest - (crest - 64) * side);
                } else {
                    // Elsewhere two-block ledges.
                    double drop = (crest - 64) * side;
                    h = (int) Math.round(crest - 2 * Math.ceil(drop / 2));
                }
                if (h > 64) {
                    terrain.raise(x, z, h, CanyonTerrain.Kind.ROCK);
                }
            }
        }
        // The low end where the spine starts, and both cave mouths, stay clear.
        terrain.reserve((int) Math.round(bx - ux * 2), (int) Math.round(bz - uz * 2), 2.5);
    }

    // ------------------------------------------------------------------ carving (after the terrain is written)

    /** Carves the cave through the ridge and builds the natural arch over the gorge. */
    void carve() {
        alcoves();
        tunnel();
        naturalArch(ARCH_S);
        mesaLadders();
        ridgeLadder();
    }

    /**
     * Undercuts at the foot of the gorge walls: the rock is hollowed three blocks high and one or two deep under an
     * overhang, shelter from shots from the rim.
     */
    private void alcoves() {
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int x = CanyonPlan.MIN_X; x <= CanyonPlan.MAX_X; x++) {
            for (int z = CanyonPlan.MIN_Z; z <= CanyonPlan.MAX_Z; z++) {
                CanyonTerrain.Kind kind = terrain.kind(x, z);
                if (!canvas.canWrite(x, z) || terrain.reserved(x, z) || terrain.top(x, z) < CanyonPlan.GROUND
                        || kind != CanyonTerrain.Kind.PLATEAU && kind != CanyonTerrain.Kind.RIM
                        || plan.noise(x / 4.0, z / 4.0, 120) < 0.42) {
                    continue;
                }
                for (int[] side : sides) {
                    int fx = x + side[0];
                    int fz = z + side[1];
                    if (terrain.kind(fx, fz) != CanyonTerrain.Kind.FLOOR || terrain.reserved(fx, fz)) {
                        continue;
                    }
                    int floor = terrain.top(fx, fz);
                    if (floor + 5 > CanyonPlan.GROUND - 1) {
                        continue;
                    }
                    hollow(x, z, floor);
                    // Where the noise is strongest the hollow goes two blocks deep.
                    int bx = x - side[0];
                    int bz = z - side[1];
                    if (plan.noise(x / 4.0, z / 4.0, 120) > 0.62 && canvas.canWrite(bx, bz)
                            && terrain.top(bx, bz) == CanyonPlan.GROUND && !terrain.reserved(bx, bz)) {
                        hollow(bx, bz, floor);
                    }
                    break;
                }
            }
        }
    }

    private void hollow(int x, int z, int floor) {
        for (int y = floor - 4; y < floor; y++) {
            if (!canvas.isSolid(x, y, z)) {
                canvas.set(x, y, z, BlockSpec.of(plan.stratum(x, y, z)));
            }
        }
        canvas.set(x, floor, z, BlockSpec.of(plan.roll(x, floor, z, 121) < 50 ? Material.GRAVEL
                : Material.COARSE_DIRT));
        for (int y = floor + 1; y <= floor + 3; y++) {
            canvas.clear(x, y, z);
        }
        if (plan.roll(x, floor, z, 122) < 30) {
            canvas.set(x, floor + 3, z, BlockSpec.of(Material.HANGING_ROOTS));
        }
    }

    /** A cave about four blocks high through the ridge, square to it. */
    private void tunnel() {
        double length = Math.hypot(RIDGE_A[0] - RIDGE_B[0], RIDGE_A[1] - RIDGE_B[1]);
        double ux = (RIDGE_A[0] - RIDGE_B[0]) / length;
        double uz = (RIDGE_A[1] - RIDGE_B[1]) / length;
        double px = RIDGE_B[0] + ux * TUNNEL_AT;
        double pz = RIDGE_B[1] + uz * TUNNEL_AT;
        double nx = -uz;
        double nz = ux;
        for (double k = -8; k <= 8; k += 0.5) {
            double cx = px + nx * k;
            double cz = pz + nz * k;
            double radius = 2.25 + 0.35 * plan.noise(k / 2.0, 5.5, 94);
            int r = (int) Math.ceil(radius);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = (int) Math.round(cx) + dx;
                    int z = (int) Math.round(cz) + dz;
                    if (!canvas.canWrite(x, z)) {
                        continue;
                    }
                    for (int y = 65; y <= 70; y++) {
                        double dy = (y + 0.5 - 66.6) * 1.05;
                        double hx = x - cx;
                        double hz = z - cz;
                        if (hx * hx + hz * hz + dy * dy <= radius * radius && canvas.isSolid(x, y, z)) {
                            canvas.clear(x, y, z);
                            BlockSpec below = canvas.get(x, 64, z);
                            if (below != null && !below.isAir()) {
                                canvas.set(x, 64, z, BlockSpec.of(caveFloor(x, z)));
                            }
                        }
                    }
                    terrain.reserve(x, z);
                }
            }
        }
        // A lantern hangs in the middle of the cave, vines from its ceiling further in.
        hangFromCeiling((int) Math.round(px), (int) Math.round(pz), Blocks.lantern(false, true));
        for (double k : new double[] {-3.5, 2.5, 4.0}) {
            int vx = (int) Math.round(px + nx * k + uz);
            int vz = (int) Math.round(pz + nz * k - ux);
            hangFromCeiling(vx, vz, BlockSpec.of(Material.CAVE_VINES));
        }
    }

    private void hangFromCeiling(int x, int z, BlockSpec spec) {
        if (!canvas.canWrite(x, z)) {
            return;
        }
        for (int y = 70; y >= 68; y--) {
            if (canvas.isSolid(x, y + 1, z) && !canvas.isSolid(x, y, z) && !canvas.isSolid(x, y - 1, z)) {
                canvas.set(x, y, z, spec);
                return;
            }
        }
    }

    private Material caveFloor(int x, int z) {
        int roll = plan.roll(x, 64, z, 95);
        return roll < 35 ? Material.COARSE_DIRT : roll < 60 ? Material.GRAVEL : roll < 80 ? Material.STONE
                : Material.MOSS_BLOCK;
    }

    /** A thick natural arch of layered rock spanning the gorge at {@code s}, walkable on top. */
    private void naturalArch(double s) {
        double[] c = plan.center(s);
        double[] n = plan.innerNormal(s);
        int floor = plan.floorY(s);
        double half = plan.halfWidth(s) + 2.5;
        int r = (int) Math.ceil(half + 3);
        for (int x = (int) c[0] - r; x <= (int) c[0] + r; x++) {
            for (int z = (int) c[1] - r; z <= (int) c[1] + r; z++) {
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                double along = (x - c[0]) * n[0] + (z - c[1]) * n[1];
                double side = (x - c[0]) * -n[1] + (z - c[1]) * n[0];
                double width = 2.0 + 0.55 * plan.noise(along / 2.3, 9.1, 96);
                if (Math.abs(along) > half || Math.abs(side) > width) {
                    continue;
                }
                double t = along / half;
                int topY = 64 + (Math.abs(t) < 0.45 ? 1 : 0);
                int under = floor + (int) Math.round((61 - floor) * Math.sqrt(Math.max(0, 1 - t * t * t * t)));
                if (Math.abs(side) > width - 0.8) {
                    under += 1;
                }
                int ground = terrain.top(x, z);
                if (under - ground < 3) {
                    under = ground;
                }
                for (int y = Math.max(ground + 1, under + 1); y < topY; y++) {
                    canvas.set(x, y, z, BlockSpec.of(plan.stratum(x, y, z)));
                }
                if (topY > ground) {
                    int roll = plan.roll(x, topY, z, 97);
                    Material surface = roll < 30 ? Material.COARSE_DIRT : roll < 55 ? Material.MOSS_BLOCK
                            : roll < 72 ? Material.GRASS_BLOCK : roll < 88 ? Material.ANDESITE : Material.STONE;
                    canvas.set(x, topY, z, BlockSpec.of(surface));
                }
                terrain.reserve(x, z);
            }
        }
    }

    /** Ladders up the mesa in the two notches between its ramps. */
    private void mesaLadders() {
        for (BlockFace facing : new BlockFace[] {BlockFace.EAST, BlockFace.WEST}) {
            int x = MESA_X;
            int z = MESA_Z;
            while (terrain.top(x + facing.getModX(), z) >= MESA_TOP) {
                x += facing.getModX();
            }
            int lx = x + facing.getModX();
            ladder(lx, z, facing, terrain.top(lx, z) + 1, MESA_TOP);
        }
    }

    /** A ladder up the sheer flank of the ridge (the side away from the gorge). */
    private void ridgeLadder() {
        double length = Math.hypot(RIDGE_A[0] - RIDGE_B[0], RIDGE_A[1] - RIDGE_B[1]);
        double ux = (RIDGE_A[0] - RIDGE_B[0]) / length;
        double uz = (RIDGE_A[1] - RIDGE_B[1]) / length;
        int x = (int) Math.round(RIDGE_B[0] + ux * 13.5);
        int z = (int) Math.round(RIDGE_B[1] + uz * 13.5);
        // Walk from the spine across the cliff side (negative "across") to the cliff's foot.
        BlockFace away = CanyonPlan.cardinal(uz, -ux);
        for (int i = 0; i < 10 && terrain.top(x + away.getModX(), z + away.getModZ()) > CanyonPlan.GROUND; i++) {
            x += away.getModX();
            z += away.getModZ();
        }
        int lx = x + away.getModX();
        int lz = z + away.getModZ();
        if (terrain.top(lx, lz) == CanyonPlan.GROUND && terrain.top(x, z) > CanyonPlan.GROUND + 2) {
            ladder(lx, lz, away, CanyonPlan.GROUND + 1, terrain.top(x, z));
        }
    }

    /**
     * A ladder in column {@code x, z} from {@code fromY} up to {@code toY} (the top of the block it leans on), fixed
     * to the solid column behind it ({@code facing} points away from that column).
     */
    void ladder(int x, int z, BlockFace facing, int fromY, int toY) {
        int bx = x - facing.getModX();
        int bz = z - facing.getModZ();
        for (int y = fromY; y <= toY; y++) {
            if (!canvas.isSolid(bx, y, bz) && canvas.canWrite(bx, bz)) {
                canvas.set(bx, y, bz, BlockSpec.of(plan.stratum(bx, y, bz)));
            }
            if (canvas.canWrite(x, z)) {
                canvas.set(x, y, z, BlockSpec.of(Material.LADDER).facing(facing));
            }
        }
        terrain.reserve(x, z, 1.5);
    }

    // ------------------------------------------------------------------ scattered rock

    /** Hoodoos, boulders and pools (after the crossings, which reserve their columns). */
    void scatter() {
        hoodoos();
        boulders();
        pools();
    }

    private void hoodoos() {
        // In the gorge: one in the first bend, one in the wide northern reach.
        int[] a = plan.beside(0.255, 2.4, false);
        hoodoo(a[0], a[1], 13, 1.7);
        int[] b = plan.beside(0.75, 2.6, true);
        hoodoo(b[0], b[1], 12, 1.6);
        // On the plateau: a group in the west, single ones elsewhere.
        hoodoo(-86, -29, 11, 1.8);
        hoodoo(-80, -40, 8, 1.7);
        hoodoo(-39, -33, 6, 1.5);
    }

    /**
     * A leaning pillar of layered rock: a flared foot, a narrow waist and a broad cap of harder rock, with rubble
     * fallen around it.
     */
    private void hoodoo(int x, int z, int height, double radius) {
        int base = terrain.top(x, z);
        double leanX = (plan.roll(x, 1, z, 110) - 50) / 60.0;
        double leanZ = (plan.roll(x, 2, z, 111) - 50) / 60.0;
        int r = (int) Math.ceil(radius * 1.35 + 2);
        for (int i = 0; i < height; i++) {
            int y = base + 1 + i;
            double f = (double) i / Math.max(1, height - 1);
            double rr;
            if (i >= height - 2) {
                rr = radius * 1.08;
            } else if (f < 0.2) {
                rr = radius * (1.35 - f);
            } else {
                rr = radius * (1.15 - 0.42 * Math.sin(Math.PI * Math.min(1, (f - 0.2) / 0.62)));
            }
            double ox = leanX * f;
            double oz = leanZ * f;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int cx = x + dx;
                    int cz = z + dz;
                    if (!canvas.canWrite(cx, cz)) {
                        continue;
                    }
                    double d = Math.hypot(dx - ox, dz - oz) + 0.4 * plan.noise(cx / 1.2, (cz + y) / 1.2, 98);
                    if (d > rr) {
                        continue;
                    }
                    int groundY = terrain.top(cx, cz);
                    int from = i == 0 ? groundY + 1 : y;
                    for (int fill = Math.max(from, groundY + 1); fill <= y; fill++) {
                        Material m = i >= height - 2 ? cap(cx, fill, cz) : plan.stratum(cx, fill, cz);
                        canvas.set(cx, fill, cz, BlockSpec.of(m));
                    }
                    terrain.reserve(cx, cz);
                }
            }
        }
        // Fallen pieces around the foot.
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = x + dx;
                int cz = z + dz;
                double d = Math.hypot(dx, dz);
                if (canvas.canWrite(cx, cz) && d > radius * 1.35 && d <= radius * 1.35 + 1.3
                        && plan.roll(cx, base, cz, 99) < 35 && terrain.top(cx, cz) == base
                        && !canvas.isSolid(cx, base + 1, cz)) {
                    canvas.set(cx, base + 1, cz, BlockSpec.of(rubble(cx, base + 1, cz)));
                    terrain.reserve(cx, cz);
                }
            }
        }
    }

    private Material cap(int x, int y, int z) {
        int roll = plan.roll(x, y, z, 100);
        return roll < 45 ? Material.GRANITE : roll < 75 ? Material.TERRACOTTA : Material.ORANGE_TERRACOTTA;
    }

    private Material rubble(int x, int y, int z) {
        int roll = plan.roll(x, y, z, 101);
        return roll < 30 ? Material.COBBLESTONE : roll < 55 ? Material.ANDESITE : roll < 75 ? Material.MOSSY_COBBLESTONE
                : roll < 90 ? Material.TUFF : Material.GRANITE;
    }

    /** Boulders: about a dozen on the gorge floor for cover down there, the rest over the plateau. */
    private void boulders() {
        List<int[]> placed = new ArrayList<>();
        placeBoulders(placed, true, 12);
        placeBoulders(placed, false, 36);
    }

    private void placeBoulders(List<int[]> placed, boolean inGorge, int target) {
        int attempts = 0;
        int start = placed.size();
        while (placed.size() - start < target && attempts++ < 4000) {
            int x = CanyonPlan.MIN_X + random.nextInt(CanyonPlan.SIZE_X);
            int z = CanyonPlan.MIN_Z + random.nextInt(CanyonPlan.SIZE_Z);
            if (!canvas.canWrite(x, z) || CanyonPlan.edgeDistance(x, z) < 4.5 || terrain.reserved(x, z)) {
                continue;
            }
            CanyonTerrain.Kind kind = terrain.kind(x, z);
            boolean floor = kind == CanyonTerrain.Kind.FLOOR || kind == CanyonTerrain.Kind.SCREE;
            if (floor != inGorge || !floor && kind != CanyonTerrain.Kind.PLATEAU) {
                continue;
            }
            if (floor && plan.distance(x, z) < 1.5) {
                // Keep the middle of the floor free to run along.
                continue;
            }
            if (!floor && plan.rimDistance(x, z) < 3.5) {
                continue;
            }
            double size = floor ? 1.3 + random.nextDouble() * 1.1 : 1.2 + random.nextDouble() * 1.6;
            boolean near = false;
            for (int[] p : placed) {
                if (Math.hypot(p[0] - x, p[1] - z) < 8.5) {
                    near = true;
                    break;
                }
            }
            if (near || !clearAround(x, z, size + 1.5)) {
                continue;
            }
            placed.add(new int[] {x, z});
            boulder(x, z, size, floor);
        }
    }

    private boolean clearAround(int x, int z, double radius) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= radius * radius && terrain.reserved(x + dx, z + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A rounded boulder of mixed rock, half sunk into the ground, mossy on top in the damp gorge. */
    private void boulder(int x, int z, double size, boolean damp) {
        int base = terrain.top(x, z);
        double rx = size * (0.9 + random.nextDouble() * 0.4);
        double rz = size * (0.9 + random.nextDouble() * 0.4);
        double ry = Math.min(2.2, size * 0.8);
        int r = (int) Math.ceil(Math.max(rx, rz)) + 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = x + dx;
                int cz = z + dz;
                if (!canvas.canWrite(cx, cz)) {
                    continue;
                }
                double horizontal = (dx * dx) / (rx * rx) + (dz * dz) / (rz * rz);
                if (horizontal > 1) {
                    continue;
                }
                int ground = terrain.top(cx, cz);
                double bump = 0.25 * plan.noise(cx / 1.5, cz / 1.5, 102);
                int height = (int) Math.round(ry * Math.sqrt(1 - horizontal) + bump);
                int topY = base + height;
                for (int y = ground + 1; y <= topY; y++) {
                    Material m = y == topY && damp && plan.roll(cx, y, cz, 103) < 45 ? Material.MOSS_BLOCK
                            : y == topY && plan.roll(cx, y, cz, 104) < 18 ? Material.MOSSY_COBBLESTONE
                            : rubble(cx, y, cz);
                    canvas.set(cx, y, cz, BlockSpec.of(m));
                }
                terrain.reserve(cx, cz);
            }
        }
    }

    /** Two small pools on the gorge floor, held in by the floor around them. */
    private void pools() {
        pool(plan.beside(0.76, 1.5, false), 2.6, 1.8);
        pool(plan.beside(0.21, 2.0, true), 2.0, 1.6);
    }

    private void pool(int[] center, double rx, double rz) {
        int cx = center[0];
        int cz = center[1];
        int level = terrain.top(cx, cz);
        List<int[]> cells = new ArrayList<>();
        int r = (int) Math.ceil(Math.max(rx, rz));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                double n = (dx * dx) / (rx * rx) + (dz * dz) / (rz * rz) + 0.25 * plan.noise(x / 1.4, z / 1.4, 105);
                if (n <= 1 && canvas.canWrite(x, z) && terrain.top(x, z) == level && !terrain.reserved(x, z)
                        && terrain.kind(x, z) == CanyonTerrain.Kind.FLOOR && !canvas.isSolid(x, level + 1, z)) {
                    cells.add(new int[] {x, z});
                }
            }
        }
        // Water only where every side is water or solid ground at the water level.
        for (int[] c : cells) {
            int x = c[0];
            int z = c[1];
            boolean held = true;
            for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
                int nx = x + face.getModX();
                int nz = z + face.getModZ();
                if (terrain.top(nx, nz) < level || !canvas.canWrite(nx, nz)) {
                    held = false;
                }
            }
            if (held) {
                canvas.set(x, level, z, BlockSpec.of(Material.WATER));
                canvas.set(x, level - 1, z, BlockSpec.of(Material.CLAY));
                terrain.reserve(x, z);
            }
        }
    }
}
