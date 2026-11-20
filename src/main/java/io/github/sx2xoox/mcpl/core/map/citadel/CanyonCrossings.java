package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The man-made crossings of the gorge: a fallen giant tree (west), a ruined stone arch bridge with gate posts and a
 * ladder down to the floor (middle), and a sagging rope bridge (north). The natural rock arch is in
 * {@link CanyonRocks}.
 *
 * <p>Every crossing runs square to the gorge along a cardinal axis: position {@code p} along {@code dir} from the
 * gorge's center line, row {@code q} across it along {@link CanyonTerrain#clockwise}.
 */
final class CanyonCrossings {

    static final double LOG_S = 0.17;
    static final double STONE_S = 0.475;
    static final double ROPE_S = 0.84;

    private final MapCanvas canvas;
    private final CanyonPlan plan;
    private final CanyonTerrain terrain;
    private final CanyonRocks rocks;

    CanyonCrossings(MapCanvas canvas, CanyonPlan plan, CanyonTerrain terrain, CanyonRocks rocks) {
        this.canvas = canvas;
        this.plan = plan;
        this.terrain = terrain;
        this.rocks = rocks;
    }

    void build() {
        fallenLog();
        stoneBridge();
        ropeBridge();
    }

    /** A crossing's frame: origin on the gorge's center line, {@code dir} across the gorge, {@code side} along it. */
    private record Frame(int x, int z, BlockFace dir, BlockFace side) {

        int x(int p, int q) {
            return x + dir.getModX() * p + side.getModX() * q;
        }

        int z(int p, int q) {
            return z + dir.getModZ() * p + side.getModZ() * q;
        }
    }

    private Frame frame(double s) {
        double[] c = plan.center(s);
        double[] n = plan.innerNormal(s);
        BlockFace dir = CanyonPlan.cardinal(n[0], n[1]);
        return new Frame((int) Math.round(c[0]), (int) Math.round(c[1]), dir, CanyonTerrain.clockwise(dir));
    }

    /**
     * The gorge's extent across the frame for rows {@code qFrom..qTo}: {last p before it, first p after it}, both on
     * the plateau (or the rim) where every row is out of the gorge.
     */
    private int[] span(Frame f, int qFrom, int qTo) {
        int before = 0;
        while (anyInGorge(f, before, qFrom, qTo) && before > -30) {
            before--;
        }
        int after = 0;
        while (anyInGorge(f, after, qFrom, qTo) && after < 30) {
            after++;
        }
        return new int[] {before, after};
    }

    private boolean anyInGorge(Frame f, int p, int qFrom, int qTo) {
        for (int q = qFrom; q <= qTo; q++) {
            int x = f.x(p, q);
            int z = f.z(p, q);
            if (terrain.inGorge(x, z) || terrain.top(x, z) < CanyonPlan.GROUND) {
                return true;
            }
        }
        return false;
    }

    private void put(Frame f, int p, int q, int y, BlockSpec spec) {
        int x = f.x(p, q);
        int z = f.z(p, q);
        if (canvas.canWrite(x, z)) {
            canvas.set(x, y, z, spec);
        }
    }

    private void clearAbove(Frame f, int p, int q, int fromY, int toY) {
        int x = f.x(p, q);
        int z = f.z(p, q);
        for (int y = fromY; y <= toY; y++) {
            if (canvas.canWrite(x, z) && canvas.isSolid(x, y, z)) {
                canvas.clear(x, y, z);
            }
        }
    }

    // ------------------------------------------------------------------ fallen tree

    /**
     * A giant spruce fallen across the gorge: a two-wide trunk (walkable on top), its root plate standing up on the
     * keep side and its broken crown on the far side.
     */
    private void fallenLog() {
        Frame f = frame(LOG_S);
        int[] span = span(f, 0, 1);
        int from = span[0] - 2;
        int to = span[1] + 1;
        BlockSpec log = Blocks.log(Material.SPRUCE_LOG, axis(f.dir()));
        for (int p = from; p <= to; p++) {
            for (int q = 0; q <= 1; q++) {
                int x = f.x(p, q);
                int z = f.z(p, q);
                boolean mossy = plan.roll(x, 65, z, 201) < 14;
                put(f, p, q, 64, log);
                put(f, p, q, 65, mossy ? BlockSpec.of(Material.MOSS_BLOCK) : log);
                clearAbove(f, p, q, 66, 68);
                terrain.reserve(x, z);
            }
            terrain.reserve(f.x(p, -1), f.z(p, -1));
            terrain.reserve(f.x(p, 2), f.z(p, 2));
        }
        // The root plate: a disk of soil and roots standing across the trunk's end.
        int rootP = to + 1;
        for (int q = -2; q <= 3; q++) {
            for (int y = 63; y <= 68; y++) {
                double dq = q - 0.5;
                double dy = y - 65.2;
                if (dq * dq / 7.5 + dy * dy / 8.0 > 1) {
                    continue;
                }
                int x = f.x(rootP, q);
                int z = f.z(rootP, q);
                int roll = plan.roll(x, y, z, 202);
                Material m = roll < 30 ? Material.ROOTED_DIRT : roll < 50 ? Material.DIRT : roll < 68
                        ? Material.COARSE_DIRT : roll < 85 ? Material.MUDDY_MANGROVE_ROOTS : Material.PACKED_MUD;
                put(f, rootP, q, y, BlockSpec.of(m));
            }
            terrain.reserve(f.x(rootP, q), f.z(rootP, q));
            terrain.reserve(f.x(rootP + 1, q), f.z(rootP + 1, q));
        }
        for (int q : new int[] {-1, 2}) {
            int x = f.x(rootP + 1, q);
            int z = f.z(rootP + 1, q);
            if (canvas.canWrite(x, z) && !canvas.isSolid(x, 67, z) && canvas.isSolid(x, 68, z)) {
                canvas.set(x, 67, z, BlockSpec.of(Material.HANGING_ROOTS));
            }
        }
        // The broken crown: a stub of trunk, bare branches and a few clumps of browned needles.
        int crownP = from - 1;
        for (int p = crownP; p >= crownP - 3; p--) {
            put(f, p, 0, 65, log);
        }
        BlockSpec branch = Blocks.log(Material.SPRUCE_LOG, axis(f.side()));
        put(f, crownP - 1, 1, 65, branch);
        put(f, crownP - 1, 2, 65, branch);
        put(f, crownP - 2, -1, 65, branch);
        put(f, crownP - 2, -2, 66, branch);
        put(f, crownP - 3, 1, 66, BlockSpec.of(Material.SPRUCE_LOG).axis(Axis.Y));
        leafClump(f, crownP - 2, 3, 66, 1.6);
        leafClump(f, crownP - 4, -2, 66, 1.9);
        leafClump(f, crownP - 5, 1, 66, 1.5);
        for (int p = crownP - 6; p <= crownP; p++) {
            for (int q = -3; q <= 4; q++) {
                terrain.reserve(f.x(p, q), f.z(p, q));
            }
        }
    }

    private void leafClump(Frame f, int p, int q, int y, double radius) {
        int r = (int) Math.ceil(radius);
        for (int dp = -r; dp <= r; dp++) {
            for (int dq = -r; dq <= r; dq++) {
                for (int dy = -1; dy <= r; dy++) {
                    double d = dp * dp + dq * dq + dy * dy * 1.6;
                    int x = f.x(p + dp, q + dq);
                    int z = f.z(p + dp, q + dq);
                    if (d > radius * radius || !canvas.canWrite(x, z) || canvas.isSolid(x, y + dy, z)
                            || y + dy <= terrain.top(x, z) || plan.roll(x, y + dy, z, 203) < 18) {
                        continue;
                    }
                    Material m = plan.roll(x, y + dy, z, 204) < 70 ? Material.SPRUCE_LEAVES : Material.OAK_LEAVES;
                    canvas.set(x, y + dy, z, Blocks.leaves(m));
                }
            }
        }
    }

    private static Axis axis(BlockFace face) {
        return face.getModX() != 0 ? Axis.X : Axis.Z;
    }

    // ------------------------------------------------------------------ stone bridge

    /**
     * The old stone bridge: four blocks wide with broken parapets, an arch beneath, gate posts with lanterns on both
     * ends and a ladder from the gorge floor up beside its far end.
     */
    private void stoneBridge() {
        Frame f = frame(STONE_S);
        int[] span = span(f, -3, 2);
        int from = span[0] - 1;
        int to = span[1] + 1;
        int floor = plan.floorY(STONE_S);
        double mid = (span[0] + span[1]) / 2.0;
        double half = (span[1] - span[0]) / 2.0;
        for (int p = from; p <= to; p++) {
            for (int q = -3; q <= 2; q++) {
                int x = f.x(p, q);
                int z = f.z(p, q);
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                int under = underside(p, mid, half, floor);
                int ground = terrain.top(x, z);
                if (under - ground < 3) {
                    under = ground;
                }
                for (int y = Math.max(ground + 1, under + 1); y < 64; y++) {
                    canvas.set(x, y, z, BlockSpec.of(masonry(x, y, z)));
                }
                // The arch's soffit steps up towards the middle: soften it with upside-down stairs.
                int next = underside(p + (p < mid ? 1 : -1), mid, half, floor);
                if (under > ground && next > under && under + 1 < 64) {
                    BlockFace away = p < mid ? f.dir().getOppositeFace() : f.dir();
                    canvas.set(x, under + 1, z, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, away));
                }
                boolean rail = q == -3 || q == 2;
                canvas.set(x, 64, z, BlockSpec.of(rail ? masonry(x, 64, z) : deck(x, z, q)));
                clearAbove(f, p, q, 65, 68);
                if (rail && p > from && p < to && plan.roll(x, 65, z, 205) < 78) {
                    // A low parapet of slabs with a few taller, crumbling merlons.
                    int roll = plan.roll(x, 66, z, 206);
                    canvas.set(x, 65, z, roll < 55 ? Blocks.slab(Material.STONE_BRICK_SLAB)
                            : roll < 80 ? Blocks.slab(Material.MOSSY_STONE_BRICK_SLAB)
                            : BlockSpec.of(roll < 90 ? Material.MOSSY_STONE_BRICKS : Material.CRACKED_STONE_BRICKS));
                }
                terrain.reserve(x, z);
            }
        }
        // Gate posts at both ends, one of them broken.
        for (int p : new int[] {from, to}) {
            for (int q : new int[] {-3, 2}) {
                boolean broken = plan.roll(f.x(p, q), 70, f.z(p, q), 207) < 30;
                put(f, p, q, 65, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
                if (broken) {
                    put(f, p, q, 66, Blocks.slab(Material.STONE_BRICK_SLAB));
                    continue;
                }
                put(f, p, q, 66, BlockSpec.of(Material.STONE_BRICKS));
                put(f, p, q, 67, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
                put(f, p, q, 68, Blocks.lantern(false, false));
            }
            for (int q = -2; q <= 1; q++) {
                int beyond = p == from ? p - 1 : p + 1;
                terrain.reserve(f.x(beyond, q), f.z(beyond, q));
                terrain.reserve(f.x(beyond + (p == from ? -1 : 1), q), f.z(beyond + (p == from ? -1 : 1), q));
            }
        }
        // Hanging lanterns under the arch.
        for (int q : new int[] {-2, 1}) {
            int p = (int) Math.round(mid);
            int under = underside(p, mid, half, floor);
            if (under > terrain.top(f.x(p, q), f.z(p, q)) + 3) {
                put(f, p, q, under, Blocks.lantern(false, true));
            }
        }
        // A ladder from the floor up the gorge wall beside the far end of the bridge.
        int q = 3;
        int p = (int) Math.round(mid);
        while (p < to && terrain.top(f.x(p + 1, q), f.z(p + 1, q)) < CanyonPlan.GROUND - 1) {
            p++;
        }
        int lx = f.x(p, q);
        int lz = f.z(p, q);
        int foot = terrain.top(lx, lz);
        if (foot < CanyonPlan.GROUND - 2) {
            rocks.ladder(lx, lz, f.dir().getOppositeFace(), foot + 1, CanyonPlan.GROUND);
        }
    }

    /**
     * The y of the highest air block under the arch at position {@code p}: the arch springs from the walls two blocks
     * above the floor and rises to y 62 in the middle (callers fill it solid where the opening gets too low).
     */
    private static int underside(int p, double mid, double half, int floor) {
        double t = Math.min(1, Math.abs(p - mid) / (half + 0.5));
        return floor + 2 + (int) Math.round((60 - floor) * Math.sqrt(Math.max(0, 1 - t * t)));
    }

    private Material masonry(int x, int y, int z) {
        int roll = plan.roll(x, y, z, 208);
        return roll < 50 ? Material.STONE_BRICKS : roll < 72 ? Material.MOSSY_STONE_BRICKS
                : roll < 90 ? Material.CRACKED_STONE_BRICKS : Material.ANDESITE;
    }

    private Material deck(int x, int z, int q) {
        int roll = plan.roll(x, 64, z, 209);
        if (q == -1 || q == 0) {
            return roll < 55 ? Material.POLISHED_ANDESITE : roll < 80 ? Material.STONE_BRICKS
                    : Material.CRACKED_STONE_BRICKS;
        }
        return roll < 45 ? Material.STONE_BRICKS : roll < 70 ? Material.MOSSY_STONE_BRICKS : roll < 88
                ? Material.CRACKED_STONE_BRICKS : Material.COBBLESTONE;
    }

    // ------------------------------------------------------------------ rope bridge

    /**
     * A long rope bridge: a three-wide deck of spruce slabs that sags one block in the middle, rope-fence rails with
     * posts where it dips, and log posts with lanterns on platforms at both ends.
     */
    private void ropeBridge() {
        Frame f = frame(ROPE_S);
        int[] span = span(f, -2, 2);
        int from = span[0];
        int to = span[1];
        for (int p = from - 1; p <= to + 1; p++) {
            boolean anchor = p <= from || p >= to;
            boolean low = sags(p, from, to);
            boolean prevLow = sags(p - 1, from, to);
            boolean nextLow = sags(p + 1, from, to);
            int deckY = low ? 63 : 64;
            for (int q = -2; q <= 2; q++) {
                int x = f.x(p, q);
                int z = f.z(p, q);
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                int roll = plan.roll(x, deckY, z, 210);
                BlockSpec plank;
                if (anchor) {
                    plank = BlockSpec.of(roll < 75 ? Material.SPRUCE_PLANKS : Material.DARK_OAK_PLANKS);
                    if (terrain.top(x, z) < 64) {
                        // Prop the platform on the rim.
                        for (int y = terrain.top(x, z) + 1; y < 64; y++) {
                            canvas.set(x, y, z, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
                        }
                    }
                } else if (roll < 10 && Math.abs(q) < 2) {
                    plank = Blocks.trapdoor(Material.SPRUCE_TRAPDOOR, f.dir(), true, false);
                } else {
                    plank = Blocks.slabTop(roll < 82 ? Material.SPRUCE_SLAB : Material.DARK_OAK_SLAB);
                }
                canvas.set(x, deckY, z, plank);
                clearAbove(f, p, q, deckY + 1, deckY + 3);
                terrain.reserve(x, z);
                if (Math.abs(q) == 2) {
                    boolean post = anchor ? p == from - 1 || p == to + 1 : low && (!prevLow || !nextLow);
                    if (anchor && post) {
                        for (int y = 65; y <= 67; y++) {
                            canvas.set(x, y, z, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
                        }
                        canvas.set(x, 68, z, Blocks.lantern(false, false));
                    } else if (anchor || plan.roll(x, 70, z, 211) < 88 || post) {
                        canvas.set(x, deckY + 1, z, BlockSpec.of(Material.SPRUCE_FENCE));
                        if (post) {
                            canvas.set(x, deckY + 2, z, BlockSpec.of(Material.SPRUCE_FENCE));
                        }
                    }
                }
            }
        }
        // Keep the bridge heads open.
        for (int q = -1; q <= 1; q++) {
            for (int k = 2; k <= 3; k++) {
                terrain.reserve(f.x(from - k, q), f.z(from - k, q));
                terrain.reserve(f.x(to + k, q), f.z(to + k, q));
            }
        }
    }

    /** True where the rope bridge's deck hangs one block lower (its middle part). */
    private static boolean sags(int p, int from, int to) {
        if (p <= from || p >= to) {
            return false;
        }
        double t = (p - from) / (double) (to - from);
        return 4 * t * (1 - t) > 0.42;
    }
}
