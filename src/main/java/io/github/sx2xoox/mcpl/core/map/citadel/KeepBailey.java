package io.github.sx2xoox.mcpl.core.map.citadel;

import static io.github.sx2xoox.mcpl.core.map.citadel.KeepKit.FLOOR;
import static io.github.sx2xoox.mcpl.core.map.citadel.KeepKit.GROUND;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapBlocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The bailey: the ruined curtain wall around the keep's courtyard (r 25.5 to 28.5), with its gates, towers and the
 * gaps where it fell.
 *
 * <p>Going round from the east (angles as in {@link CitadelLayout}): the main gatehouse (0°, with a half-raised
 * portcullis), a wall that slides down into a rubble ramp, the south-east breach (60°), the south lookout tower
 * (85°), the broken south-west gate between two stumps of round towers (120°), a wall whose middle fell to half
 * height, the west gatehouse (180°), the west tower (210°), the north-west breach (240°), the north wall (half height,
 * then up a short ramp to the walk and down a rubble ramp), the north-east breach (300°) and the half-fallen
 * north-east tower (330°). The wall walk is reached by the stairs inside the gates, the towers' spiral stairs and the
 * ramps; nothing up there is a dead end (the half-height stretches are low enough to jump down from).
 */
final class KeepBailey {

    static final double INNER = 25.5;
    static final double OUTER = 28.5;
    /** Top block of the wall; the wall walk is one higher. */
    static final int TOP = 72;
    /** Top of the stretches that fell to half height: low enough to jump down from, too high to climb. */
    static final int FALLEN_TOP = TOP - 4;
    /** Top block of the gatehouse turrets. */
    private static final int TURRET_TOP = 76;

    /**
     * The wall's nominal top along the ring: {angle, top}, interpolated in between. Tops below {@code GROUND + 1}
     * mean no wall. Pairs of close keyframes make sheer broken ends; long slopes make rubble ramps (one block down per
     * 2.4°, gentle enough to walk).
     */
    private static final double[][] PROFILE = {
            {0, TOP}, {29, TOP}, {48, 64.2},          // south-east wall, ramping down into the breach
            {48.01, 60}, {84.99, 60},                  // south-east breach, up to the lookout tower's middle
            {85, TOP}, {105, TOP},                     // lookout tower and the stub to the broken gate
            {105.01, 60}, {134.99, 60},                // broken gate passage between its two tower stumps
            {135, TOP}, {141, TOP}, {151, FALLEN_TOP + 0.5}, // south-west wall: down a ramp to a stretch that fell
            {156, FALLEN_TOP + 0.5}, {156.01, TOP},          // to half height
            {226, TOP}, {229, 64.5},                   // west gate, west tower, ragged end
            {229.01, 60}, {250.99, 60},                // north-west breach
            {251, 64.5}, {253, FALLEN_TOP + 0.5},            // north wall: half height from a ragged end,
            {261, FALLEN_TOP + 0.5}, {270, TOP}, {273, TOP}, // up a short ramp to the walk,
            {292, 64.2},                               // then down a rubble ramp into the north-east breach
            {292.01, 60}, {310.99, 60},                // north-east breach
            {311, 64.5}, {313.5, TOP}, {360, TOP}};    // ragged end, north-east tower, back to the main gate

    /** Angle ranges whose ends crumble (the angle is jittered per column there). */
    private static final double[][] RAGGED = {{154, 158}, {224, 231}, {249, 256}, {309, 316}};

    private final MapCanvas canvas;
    private final Random random;
    private final int salt;
    /** Top of the wall per column ({@code GROUND} where there is none), indexed {@code [x + 30][z + 30]}. */
    private final int[][] tops = new int[61][61];

    KeepBailey(MapCanvas canvas, Random random) {
        this.canvas = canvas;
        this.random = random;
        this.salt = random.nextInt();
    }

    static boolean inBand(int x, int z) {
        double r = KeepKit.radius(x, z);
        return r >= INNER && r < OUTER;
    }

    /** The wall top of a column (after the profile, before gates and towers), or {@code GROUND}. */
    int wallTop(int x, int z) {
        if (Math.abs(x) > 30 || Math.abs(z) > 30) {
            return GROUND;
        }
        return tops[x + 30][z + 30];
    }

    void build() {
        profile();
        placeWall();
        gatehouse(1, false);
        gatehouse(-1, true);
        gateStairs(1);
        gateStairs(-1);
        brokenGate();
        new KeepBaileyTower(canvas, random, this, 85, KeepBaileyTower.Style.LOOKOUT).build();
        new KeepBaileyTower(canvas, random, this, 210, KeepBaileyTower.Style.INTACT).build();
        new KeepBaileyTower(canvas, random, this, 330, KeepBaileyTower.Style.COLLAPSED).build();
        breaches();
        buttresses();
        overgrowth();
        ivy();
    }

    // ------------------------------------------------------------------ the wall ring

    private void profile() {
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                tops[x + 30][z + 30] = GROUND;
                if (!inBand(x, z)) {
                    continue;
                }
                double angle = CitadelLayout.angle(x, z);
                for (double[] ragged : RAGGED) {
                    if (angle >= ragged[0] && angle <= ragged[1]) {
                        angle += (KeepKit.noise(salt, x * 0.8, z * 0.8) - 0.5) * 5.0;
                    }
                }
                int top = (int) Math.floor(profileAt(angle));
                if (top == TOP && KeepKit.noise(salt + 7, x * 0.5, z * 0.5) > 0.74) {
                    top--; // the walk's paving is gone here: a shallow dip
                }
                tops[x + 30][z + 30] = Math.max(GROUND, top);
            }
        }
    }

    private static double profileAt(double angle) {
        double a = ((angle % 360) + 360) % 360;
        for (int i = 0; i < PROFILE.length - 1; i++) {
            double[] from = PROFILE[i];
            double[] to = PROFILE[i + 1];
            if (a >= from[0] && a <= to[0]) {
                double t = to[0] == from[0] ? 0 : (a - from[0]) / (to[0] - from[0]);
                return from[1] + (to[1] - from[1]) * t;
            }
        }
        return TOP;
    }

    private void placeWall() {
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                int top = wallTop(x, z);
                if (top < FLOOR) {
                    continue;
                }
                double r = KeepKit.radius(x, z);
                boolean face = r >= OUTER - 1 || r < INNER + 1;
                for (int y = FLOOR; y <= top; y++) {
                    BlockSpec block = y <= FLOOR + 1 ? KeepKit.footing(random) : KeepKit.masonry(random);
                    if (y == top && top >= TOP - 1 && random.nextInt(3) > 0) {
                        block = KeepKit.paving(random); // the walk's worn paving
                    } else if (y == TOP - 1 && face && top >= TOP - 1) {
                        block = KeepKit.darkMasonry(random); // string course under the walk
                    }
                    canvas.set(x, y, z, block);
                }
                // Arrow slits in the outer face (one block, so nobody gets stuck in them).
                double arc = Math.toRadians(CitadelLayout.angle(x, z)) * OUTER;
                if (r >= OUTER - 1 && top >= TOP - 2 && Math.floorMod((int) Math.floor(arc), 7) == 3) {
                    canvas.set(x, FLOOR + 3, z, BlockSpec.AIR);
                }
            }
        }
        // Steps: a column next to one exactly a block higher gets a stair, so the ramps and dips can be walked.
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                int top = wallTop(x, z);
                if (!inBand(x, z)) {
                    continue;
                }
                BlockFace up = higherSide(x, z, top);
                if (up != null && (top >= FLOOR || isRampFoot(x, z))) {
                    canvas.set(x, top + 1, z, random.nextInt(5) == 0 ? Blocks.slab(KeepKit.slab(random))
                            : Blocks.stairs(KeepKit.stairs(random), up));
                }
            }
        }
        // Merlons on the outer edge, two up and one down, some fallen.
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                int top = wallTop(x, z);
                double r = KeepKit.radius(x, z);
                if (top < TOP - 2 || r < OUTER - 1 || !inBand(x, z) || !KeepKit.isFree(canvas, x, top + 1, z)) {
                    continue;
                }
                double arc = Math.toRadians(CitadelLayout.angle(x, z)) * OUTER;
                if (Math.floorMod((int) Math.floor(arc), 3) == 0) {
                    continue;
                }
                if (KeepKit.noise(salt + 3, x * 0.6, z * 0.6) > 0.72) {
                    continue; // fallen merlon
                }
                canvas.set(x, top + 1, z, random.nextInt(6) == 0 ? KeepKit.footing(random) : KeepKit.masonry(random));
            }
        }
    }

    /** The side of a band column whose neighbour (also in the band) is exactly one block higher. */
    private BlockFace higherSide(int x, int z, int top) {
        for (BlockFace face : KeepKit.sides()) {
            int nx = x + face.getModX();
            int nz = z + face.getModZ();
            if (inBand(nx, nz) && wallTop(nx, nz) == top + 1) {
                return face;
            }
        }
        return null;
    }

    /** Ground columns at the foot of a ramp get the first step too. */
    private boolean isRampFoot(int x, int z) {
        double angle = CitadelLayout.angle(x, z);
        return angle > 40 && angle < 52 || angle > 283 && angle < 295;
    }

    // ------------------------------------------------------------------ gatehouses

    /**
     * A gatehouse astride the wall on the x axis ({@code sign} 1 east, -1 west): a deep vaulted passage five blocks
     * wide, a portcullis, a crenellated roof level with the wall walk and two square turrets on its outer corners.
     */
    private void gatehouse(int sign, boolean damaged) {
        BlockFace out = sign > 0 ? BlockFace.EAST : BlockFace.WEST;
        BlockFace in = out.getOppositeFace();
        // The body.
        for (int a = 21; a <= 28; a++) {
            for (int b = -6; b <= 6; b++) {
                int x = sign * a;
                if (KeepKit.radius(x, b) > 28.7) {
                    continue;
                }
                for (int y = FLOOR; y <= TOP; y++) {
                    canvas.set(x, y, b, y <= FLOOR + 1 ? KeepKit.footing(random) : KeepKit.masonry(random));
                }
                KeepKit.clear(canvas, x, TOP + 1, b, TOP + 6);
                if (a == 21 || a == 28 || Math.abs(b) == 6) {
                    // A string course of dark stone just under the roof.
                    canvas.set(x, TOP - 1, b, KeepKit.darkMasonry(random));
                }
                if (a > 21 && a < 28 && Math.abs(b) < 6 || random.nextInt(3) == 0) {
                    canvas.set(x, TOP, b, KeepKit.paving(random));
                }
            }
        }
        // Turrets on the outer corners, reached by ladders from the roof.
        for (int side = -1; side <= 1; side += 2) {
            boolean broken = damaged && side > 0;
            for (int a = 26; a <= 28; a++) {
                for (int i = 4; i <= 6; i++) {
                    int x = sign * a;
                    int z = side * i;
                    int turretTop = broken ? TOP + 1 + (int) (KeepKit.hash(salt, x, z) * 2.5) : TURRET_TOP;
                    for (int y = TOP + 1; y <= turretTop; y++) {
                        canvas.set(x, y, z, KeepKit.masonry(random));
                    }
                    boolean corner = (a == 26 || a == 28) && (i == 4 || i == 6);
                    if (!broken && corner && !(a == 26 && i == 4)) {
                        canvas.set(x, turretTop + 1, z, KeepKit.masonry(random));
                    }
                }
            }
            if (!broken) {
                for (int y = TOP + 1; y <= TURRET_TOP; y++) {
                    canvas.set(sign * 25, y, side * 5, BlockSpec.of(Material.LADDER).facing(in));
                }
            } else {
                // The fallen turret lies at the gatehouse's foot.
                KeepKit.Heap heap = new KeepKit.Heap();
                heap.mound(salt + 11, sign * 26.0, side * 9.5, 2.8, 2.0);
                heap.place(canvas, random, true);
            }
        }
        // The vaulted passage.
        for (int a = 21; a <= 28; a++) {
            int x = sign * a;
            for (int b = -2; b <= 2; b++) {
                canvas.set(x, GROUND, b, a == 21 || a == 28 ? BlockSpec.of(Material.STONE_BRICKS)
                        : KeepKit.paving(random));
                KeepKit.clear(canvas, x, FLOOR, b, FLOOR + 4);
            }
            canvas.set(x, FLOOR + 4, -2, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.NORTH));
            canvas.set(x, FLOOR + 4, 2, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.SOUTH));
            for (int b = -1; b <= 1; b++) {
                canvas.set(x, FLOOR + 5, b, BlockSpec.of(a == 21 || a == 28 ? Material.STONE_BRICKS
                        : Material.POLISHED_ANDESITE));
            }
        }
        for (int a : new int[] {21, 28}) {
            canvas.set(sign * a, FLOOR + 5, 0, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
            canvas.set(sign * a, FLOOR + 5, -1, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, out));
            canvas.set(sign * a, FLOOR + 5, 1, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, out));
        }
        // Portcullis: half-raised on the main gate, raised to the vault on the west gate.
        for (int b = -1; b <= 1; b++) {
            canvas.set(sign * 26, FLOOR + 4, b, BlockSpec.of(Material.IRON_BARS));
        }
        if (!damaged) {
            canvas.set(sign * 26, FLOOR + 3, -1, BlockSpec.of(Material.IRON_BARS));
            canvas.set(sign * 26, FLOOR + 3, 1, BlockSpec.of(Material.IRON_BARS));
        }
        canvas.set(sign * 23, FLOOR + 4, 0, Blocks.lantern(false, true));
        // Merlons along the outer edge (on the main gate on a gallery corbelled out over the arch) and a low parapet
        // on the courtyard side.
        int front = damaged ? 28 : 29;
        if (!damaged) {
            for (int b = -3; b <= 3; b++) {
                canvas.set(sign * front, TOP - 1, b, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, in));
                canvas.set(sign * front, TOP, b, KeepKit.masonry(random));
            }
        }
        for (int b = -3; b <= 3; b += 2) {
            canvas.set(sign * front, TOP + 1, b, KeepKit.masonry(random));
        }
        for (int b = -4; b <= 3; b++) {
            if (b != -1 && b != 0) {
                canvas.set(sign * 21, TOP + 1, b, KeepKit.coping(random));
            }
        }
        // Torches beside both arches.
        canvas.set(sign * 29, FLOOR + 2, -3, Blocks.wallTorch(damaged, out));
        canvas.set(sign * 29, FLOOR + 2, 3, Blocks.wallTorch(damaged, out));
        canvas.set(sign * 20, FLOOR + 3, -3, Blocks.wallTorch(damaged, in));
        canvas.set(sign * 20, FLOOR + 3, 3, Blocks.wallTorch(damaged, in));
    }

    /**
     * The stair inside a gatehouse, along the inner face of the wall south of the gate: eight steps up from the
     * courtyard to the gatehouse roof.
     */
    private void gateStairs(int sign) {
        for (int z = 7; z <= 14; z++) {
            int step = FLOOR + (14 - z);
            for (int a = 21; a <= 25; a++) {
                int x = sign * a;
                double r = KeepKit.radius(x, z);
                if (a > 22 && r >= INNER) {
                    break;
                }
                for (int y = FLOOR; y < step; y++) {
                    canvas.set(x, y, z, y == FLOOR ? KeepKit.footing(random) : KeepKit.masonry(random));
                }
                canvas.set(x, step, z, Blocks.stairs(random.nextInt(4) == 0 ? Material.MOSSY_STONE_BRICK_STAIRS
                        : Material.STONE_BRICK_STAIRS, BlockFace.NORTH));
                KeepKit.clear(canvas, x, step + 1, z, TOP + 4);
            }
        }
        // A lantern post at the foot of the stair.
        KeepKit.lanternPost(canvas, sign * 20, FLOOR, 15);
    }

    // ------------------------------------------------------------------ the broken south-west gate

    /**
     * The south-west gate (120°) fell long ago: two stumps of round towers flank the road, with the springers of the
     * arch still reaching for each other. The stumps' tops are reached from the wall walks beside them.
     */
    private void brokenGate() {
        int[] east = CitadelLayout.polar(25, 105);
        int[] west = CitadelLayout.polar(25, 135);
        stump(east[0], east[1], TOP + 1, true);
        stump(west[0], west[1], TOP - 1, false);
        // Arch springers reaching over the road.
        springer(east, west, TOP - 2, 2, true);
        springer(west, east, TOP - 3, 1, false);
        // Rubble against the inner side of the western stump.
        KeepKit.Heap heap = new KeepKit.Heap();
        double[] foot = KeepKit.point(21.0, 133);
        heap.mound(salt + 21, foot[0], foot[1], 3.4, 3.0);
        heap.place(canvas, random, true);
        // Fallen voussoirs either side of the road.
        KeepKit.Heap fallen = new KeepKit.Heap();
        double[] a = KeepKit.point(27.5, 111);
        double[] b = KeepKit.point(28.0, 129);
        fallen.mound(salt + 23, a[0], a[1], 2.2, 1.5);
        fallen.mound(salt + 24, b[0], b[1], 2.0, 1.2);
        fallen.place(canvas, random, true);
    }

    /** A solid round tower stump (radius 3) with a ragged top and, if {@code crowned}, a few merlons left. */
    private void stump(int cx, int cz, int top, boolean crowned) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > 9) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                int columnTop = top;
                if (d2 >= 7 && KeepKit.hash(salt + 31, x, z) > 0.6) {
                    columnTop--; // chipped rim
                }
                KeepKit.clear(canvas, x, columnTop + 1, z, TOP + 4);
                for (int y = FLOOR; y <= columnTop; y++) {
                    canvas.set(x, y, z, y <= FLOOR + 1 ? KeepKit.footing(random) : KeepKit.masonry(random));
                }
                if (crowned && outerHalf(cx, cz, dx, dz) && d2 >= 7 && KeepKit.hash(salt + 32, x, z) > 0.45) {
                    canvas.set(x, columnTop + 1, z, KeepKit.masonry(random));
                }
            }
        }
        // Arrow slits.
        BlockFace outward = KeepKit.cardinal(cx, cz);
        int sx = cx + outward.getModX() * 3;
        int sz = cz + outward.getModZ() * 3;
        canvas.set(sx, FLOOR + 3, sz, BlockSpec.AIR);
    }

    /** True for the half of a stump facing away from the keep. */
    private static boolean outerHalf(int cx, int cz, int dx, int dz) {
        return dx * cx + dz * cz > 0;
    }

    /** A stub of the fallen arch: from tower {@code from} towards {@code to}, {@code length} blocks past its rim. */
    private void springer(int[] from, int[] to, int bottom, int length, boolean lantern) {
        double dx = to[0] - from[0];
        double dz = to[1] - from[1];
        double len = Math.sqrt(dx * dx + dz * dz);
        dx /= len;
        dz /= len;
        for (double t = 2.5; t <= 3.0 + length; t += 0.5) {
            for (double s = -1; s <= 1; s += 0.5) {
                int x = (int) Math.round(from[0] + dx * t - dz * s);
                int z = (int) Math.round(from[1] + dz * t + dx * s);
                for (int y = bottom; y <= bottom + 2; y++) {
                    if (KeepKit.isFree(canvas, x, y, z)) {
                        canvas.set(x, y, z, KeepKit.masonry(random));
                    }
                }
            }
        }
        int tipX = (int) Math.round(from[0] + dx * (3.0 + length));
        int tipZ = (int) Math.round(from[1] + dz * (3.0 + length));
        if (lantern) {
            canvas.set(tipX, bottom - 1, tipZ, Blocks.log(Material.IRON_CHAIN, Axis.Y));
            canvas.set(tipX, bottom - 2, tipZ, Blocks.lantern(false, true));
        }
    }

    // ------------------------------------------------------------------ details

    /**
     * Stepped buttresses against the inner face of the standing wall. Their tops are low enough to jump down from,
     * so nobody is stranded on one.
     */
    private void buttresses() {
        for (double angle = 4; angle < 360; angle += 12.5) {
            int[] p = CitadelLayout.polar(24.9, angle);
            int[] q = CitadelLayout.polar(23.9, angle);
            int[] wall = CitadelLayout.polar(26.2, angle);
            if (wallTop(wall[0], wall[1]) < TOP - 1 || !openAround(p[0], p[1]) || !openAround(q[0], q[1])) {
                continue;
            }
            BlockFace out = KeepKit.cardinal(p[0], p[1]);
            for (int y = FLOOR; y <= FLOOR + 2; y++) {
                canvas.set(p[0], y, p[1], y == FLOOR ? KeepKit.footing(random) : KeepKit.masonry(random));
            }
            canvas.set(p[0], FLOOR + 3, p[1], Blocks.stairs(Material.STONE_BRICK_STAIRS, out));
            if (q[0] != p[0] || q[1] != p[1]) {
                canvas.set(q[0], FLOOR, q[1], KeepKit.footing(random));
                canvas.set(q[0], FLOOR + 1, q[1], Blocks.stairs(Material.STONE_BRICK_STAIRS, out));
            }
        }
    }

    /**
     * Ivy hanging down both faces of the standing wall, from the walk's level a few blocks down. Its top is level with
     * the walk, so it never makes a ledge, and it hangs high enough to walk under.
     */
    private void ivy() {
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                double r = KeepKit.radius(x, z);
                boolean inner = r >= INNER - 1 && r < INNER;
                boolean outer = r >= OUTER && r < OUTER + 1;
                if (!inner && !outer || !canvas.canWrite(x, z)) {
                    continue;
                }
                double n = KeepKit.noise(salt + 70, x * 0.45, z * 0.45);
                if (n < 0.6 || !besideWalk(x, z)) {
                    continue;
                }
                int length = 2 + (int) ((n - 0.6) * 10);
                int bottom = Math.max(TOP - length + 1, FLOOR + (outer ? 3 : 4));
                boolean free = true;
                for (int y = FLOOR; y <= TOP + 2 && free; y++) {
                    free = KeepKit.isFree(canvas, x, y, z);
                }
                if (!free) {
                    continue;
                }
                Material leaves = n > 0.85 ? Material.FLOWERING_AZALEA_LEAVES
                        : (x + z & 1) == 0 ? Material.AZALEA_LEAVES : Material.OAK_LEAVES;
                for (int y = bottom; y <= TOP; y++) {
                    canvas.set(x, y, z, Blocks.leaves(y == bottom && random.nextBoolean() ? Material.OAK_LEAVES
                            : leaves));
                }
            }
        }
    }

    /** True if a band neighbour of the column is standing wall at full height. */
    private boolean besideWalk(int x, int z) {
        for (BlockFace face : KeepKit.sides()) {
            int nx = x + face.getModX();
            int nz = z + face.getModZ();
            if (inBand(nx, nz) && wallTop(nx, nz) == TOP && canvas.surfaceY(nx, nz) <= TOP + 1) {
                return true;
            }
        }
        return false;
    }

    /** True if the courtyard side of a column is clear around it (so a buttress makes no dead-end pocket). */
    private boolean openAround(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!inBand(x + dx, z + dz) && !KeepKit.isFree(canvas, x + dx, FLOOR, z + dz)) {
                    return false;
                }
            }
        }
        return KeepKit.isFree(canvas, x, FLOOR + 4, z);
    }

    /** Moss and grass on the wall walk (not on the merlons, where it would make them a step too high). */
    private void overgrowth() {
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                if (!inBand(x, z)) {
                    continue;
                }
                int top = canvas.surfaceY(x, z);
                if (top < TOP - 4 || top > TOP || !KeepKit.isFree(canvas, x, top + 1, z)
                        || !KeepKit.isFree(canvas, x, top + 2, z)) {
                    continue;
                }
                BlockSpec surface = canvas.get(x, top, z);
                if (surface == null || !MapBlocks.isFullCube(surface)) {
                    continue;
                }
                double n = KeepKit.noise(salt + 60, x * 0.35, z * 0.35);
                if (n > 0.62 && random.nextInt(3) > 0) {
                    canvas.set(x, top + 1, z, BlockSpec.of(Material.MOSS_CARPET));
                } else if (n > 0.55 && random.nextInt(9) == 0) {
                    canvas.set(x, top + 1, z, BlockSpec.of(Material.SHORT_GRASS));
                }
            }
        }
    }

    // ------------------------------------------------------------------ breaches

    /** Rubble in the three breaches, spilled inwards and outwards, with the old footings showing through. */
    private void breaches() {
        KeepKit.Heap heap = new KeepKit.Heap();
        double[][] mounds = {
                {27.0, 55, 2.6, 1.8}, {27.5, 66, 2.2, 1.4}, {23.5, 61, 1.8, 1.0}, {26.5, 71, 2.0, 1.6},
                {27.0, 235, 2.5, 1.8}, {26.0, 246, 2.0, 1.2}, {23.0, 239, 1.6, 1.0},
                {27.0, 294, 2.4, 1.6}, {28.0, 306, 2.0, 1.2}, {23.5, 301, 1.8, 1.1}};
        for (int i = 0; i < mounds.length; i++) {
            double[] m = mounds[i];
            double[] p = KeepKit.point(m[0], m[1]);
            heap.mound(salt + 40 + i, p[0], p[1], m[2], m[3]);
        }
        heap.place(canvas, random, true);
        // Footings: single courses of the old wall here and there.
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                if (!inBand(x, z) || wallTop(x, z) >= FLOOR || !KeepKit.isFree(canvas, x, FLOOR, z)) {
                    continue;
                }
                double angle = CitadelLayout.angle(x, z);
                boolean breach = angle > 48 && angle < 74 || angle > 229 && angle < 251 || angle > 292 && angle < 311;
                if (!breach) {
                    continue;
                }
                double n = KeepKit.noise(salt + 50, x * 0.5, z * 0.5);
                if (n > 0.62) {
                    canvas.set(x, FLOOR, z, KeepKit.footing(random));
                } else if (n > 0.5) {
                    canvas.set(x, FLOOR, z, Blocks.slab(KeepKit.slab(random)));
                }
            }
        }
    }
}
