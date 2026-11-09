package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The crossings of the lake: a humpbacked stone arch bridge from the north shore to the ruin island, and wooden
 * boardwalks on posts that hop from island to islet to shore, plus the piers along the banks. Decks are at the
 * meadow level (players stand at 65), so the crossings join the banks without steps.
 */
final class LakeBridges {

    /** Deck of the wooden walks: level with the meadow. */
    static final int DECK_Y = LakeTerrain.GROUND_Y;

    private final MapCanvas canvas;
    private final LakeTerrain terrain;
    private final Random random;

    LakeBridges(MapCanvas canvas, LakeTerrain terrain, Random random) {
        this.canvas = canvas;
        this.terrain = terrain;
        this.random = random;
    }

    // ------------------------------------------------------------------ stone arch bridge

    /**
     * A stone bridge along the z axis at {@code x0} (5 wide: a 3-wide deck between parapets), spanning the water
     * north of the island plot: from the last meadow column of the island to the first of the north shore.
     */
    void archBridge(int x0, int islandZ) {
        int south = islandZ;
        while (terrain.isMeadow(x0, south - 1)) {
            south--;
        }
        int north = south - 1;
        while (!terrain.isMeadow(x0, north) || !terrain.isMeadow(x0 - 2, north)
                || !terrain.isMeadow(x0 + 2, north)) {
            north--;
        }
        north -= 1;
        int length = south - north;
        int rise = Math.max(2, Math.min(4, (int) Math.floor(length / 3.3)));
        double mid = (north + south) / 2.0;
        int[] deck = new int[length + 1];
        int[] soffit = new int[length + 1];
        for (int i = 0; i <= length; i++) {
            double t = (double) i / length;
            deck[i] = DECK_Y + (int) Math.round(rise * Math.sin(Math.PI * t));
            double u = (north + i - mid) / (length / 2.0 - 1);
            soffit[i] = DECK_Y + (int) Math.round((rise - 1) * Math.sqrt(Math.max(0, 1 - u * u)));
        }
        for (int i = 0; i <= length; i++) {
            int z = north + i;
            BlockFace towardCenter = z < mid ? BlockFace.SOUTH : BlockFace.NORTH;
            int outer = z < mid ? i - 1 : i + 1;
            int outerDeck = outer < 0 || outer > length ? DECK_Y : deck[outer];
            int outerSoffit = outer < 0 || outer > length ? DECK_Y : soffit[outer];
            for (int x = x0 - 2; x <= x0 + 2; x++) {
                int ground = terrain.top(x, z);
                // The body of the bridge: from the soffit (the arch's underside) up to the deck.
                int bottom = soffit[i] <= DECK_Y ? Math.min(ground + 1, DECK_Y) : soffit[i];
                for (int y = bottom; y < deck[i]; y++) {
                    canvas.set(x, y, z, LakeKit.brick(random));
                }
                if (soffit[i] > DECK_Y && soffit[i] > outerSoffit && soffit[i] < deck[i]) {
                    canvas.set(x, soffit[i], z, LakeKit.brickStairsTop(random, LakeKit.opposite(towardCenter)));
                }
                if (bottom <= LakeTerrain.WATER_Y && soffit[i] <= DECK_Y) {
                    // Abutments reach down into the bed.
                    for (int y = ground; y < bottom; y++) {
                        canvas.set(x, y, z, LakeKit.brick(random));
                    }
                }
                boolean step = deck[i] > outerDeck;
                canvas.set(x, deck[i], z, step ? LakeKit.brickStairs(random, towardCenter)
                        : random.nextInt(6) == 0 ? BlockSpec.of(Material.CRACKED_STONE_BRICKS)
                        : LakeKit.brick(random));
                boolean edge = x == x0 - 2 || x == x0 + 2;
                if (edge && i > 0 && i < length && random.nextInt(7) > 0) {
                    canvas.set(x, deck[i] + 1, z, LakeKit.brickRail(random));
                }
            }
        }
        // Lanterns on the parapet at the crown, and gate pillars where the bridge meets the island.
        int crown = north + length / 2;
        for (int x : new int[] {x0 - 2, x0 + 2}) {
            canvas.set(x, deck[length / 2] + 1, crown, LakeKit.brick(random));
            canvas.set(x, deck[length / 2] + 2, crown, Blocks.lantern(false, false));
        }
        gatePillars(x0, south + 1);
        gatePillars(x0, north - 1);
        bridgeFoot(x0, north);
        terrain.reserve(x0 - 3, north - 2, x0 + 3, south + 2);
    }

    /**
     * The landing where the arch bridge leaves the north shore: worn paving on both sides of the abutment, a broken
     * stone balustrade along the bank with gaps, lamp posts, and stone steps down into the water.
     */
    private void bridgeFoot(int x0, int north) {
        for (int x = x0 - 7; x <= x0 + 7; x++) {
            if (Math.abs(x - x0) <= 2) {
                continue;
            }
            for (int z = north - 4; z <= north + 5; z++) {
                if (!terrain.isFree(x, z) || Math.abs(x - x0) == 7 && random.nextBoolean()) {
                    continue;
                }
                int top = terrain.top(x, z);
                if (top == DECK_Y && terrain.isMeadow(x, z)) {
                    canvas.set(x, DECK_Y, z, paving());
                    boolean bankAhead = terrain.top(x, z + 1) < DECK_Y;
                    if (bankAhead && Math.abs(x - x0) != 5 && random.nextInt(4) > 0) {
                        canvas.set(x, DECK_Y + 1, z, LakeKit.brickRail(random));
                    }
                } else if (top == LakeTerrain.WATER_Y) {
                    boolean waterAhead = terrain.isWater(x, z + 1);
                    canvas.set(x, top, z, waterAhead ? LakeKit.brickStairs(random, BlockFace.NORTH) : paving());
                    if (waterAhead && terrain.top(x, z + 1) < LakeTerrain.WATER_Y - 1) {
                        // A lower step under the water line.
                        canvas.set(x, LakeTerrain.WATER_Y - 1, z + 1, LakeKit.brick(random));
                        for (int y = terrain.top(x, z + 1) + 1; y < LakeTerrain.WATER_Y - 1; y++) {
                            canvas.set(x, y, z + 1, LakeKit.brick(random));
                        }
                    }
                }
                terrain.reserve(x, z, x, z);
            }
        }
        for (int x : new int[] {x0 - 5, x0 + 5}) {
            int z = north - 2;
            if (canvas.canWrite(x, z) && terrain.isMeadow(x, z)) {
                canvas.set(x, DECK_Y + 1, z, LakeKit.brick(random));
                canvas.set(x, DECK_Y + 2, z, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
                canvas.set(x, DECK_Y + 3, z, Blocks.lantern(false, false));
            }
        }
    }

    private BlockSpec paving() {
        int roll = random.nextInt(10);
        return roll < 4 ? LakeKit.brick(random) : roll < 6 ? BlockSpec.of(Material.COBBLESTONE)
                : roll < 8 ? BlockSpec.of(Material.MOSSY_COBBLESTONE) : roll < 9 ? BlockSpec.of(Material.ANDESITE)
                : BlockSpec.of(Material.COARSE_DIRT);
    }

    /** Two weathered pillars flanking a 3-wide way along z, the north one with a broken lintel. */
    private void gatePillars(int x0, int z) {
        for (int x : new int[] {x0 - 2, x0 + 2}) {
            int ground = canvas.surfaceY(x, z);
            int height = x < x0 ? 4 : 3;
            for (int y = ground + 1; y <= ground + height; y++) {
                canvas.set(x, y, z, LakeKit.brick(random));
            }
            canvas.set(x, ground + height + 1, z, x < x0 ? LakeKit.brickSlab(random)
                    : Blocks.lantern(false, false));
        }
        int ground = canvas.surfaceY(x0 - 1, z);
        canvas.set(x0 - 1, ground + 4, z, LakeKit.brickStairsTop(random, BlockFace.WEST));
    }

    // ------------------------------------------------------------------ wooden walks

    /**
     * A wooden boardwalk 3 wide through the axis-aligned corner points {@code points} ({x, z} pairs), on posts where
     * it crosses water. {@code rails} adds broken fence railings on the sides.
     */
    void boardwalk(int[][] points, boolean rails) {
        for (int p = 0; p + 1 < points.length; p++) {
            int[] a = points[p];
            int[] b = points[p + 1];
            int dx = Integer.signum(b[0] - a[0]);
            int dz = Integer.signum(b[1] - a[1]);
            int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
            Axis axis = dx != 0 ? Axis.X : Axis.Z;
            for (int s = 0; s <= steps; s++) {
                int cx = a[0] + dx * s;
                int cz = a[1] + dz * s;
                for (int o = -1; o <= 1; o++) {
                    int x = cx + (dx == 0 ? o : 0);
                    int z = cz + (dz == 0 ? o : 0);
                    plank(x, z, o == 0 ? null : axis);
                    boolean postHere = o != 0 && (s % 4 == 0 || s == steps);
                    if (postHere) {
                        postDown(x, z);
                        if (rails && random.nextInt(5) > 0) {
                            railPost(x, z, s % 8 == 4 && random.nextBoolean());
                        }
                    } else if (o != 0 && rails && random.nextInt(4) > 0 && isWaterBelow(x, z)) {
                        canvas.set(x, DECK_Y + 1, z, BlockSpec.of(Material.SPRUCE_FENCE));
                    }
                }
                terrain.reserve(cx - 1, cz - 1, cx + 1, cz + 1);
            }
            // Square off the corner where the walk turns.
            terrain.reserve(b[0] - 1, b[1] - 1, b[0] + 1, b[1] + 1);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    BlockSpec there = canvas.get(b[0] + ox, DECK_Y, b[1] + oz);
                    if (there == null || there.isAir()) {
                        plank(b[0] + ox, b[1] + oz, null);
                    }
                }
            }
        }
    }

    /**
     * A pier from the shore at {@code x, z} running {@code length} blocks towards {@code facing}, ending in a wider
     * landing with mooring posts, a lantern and a few barrels.
     */
    void pier(int x, int z, BlockFace facing, int length) {
        int dx = facing.getModX();
        int dz = facing.getModZ();
        int[][] walk = {{x, z}, {x + dx * length, z + dz * length}};
        boardwalk(walk, false);
        int ex = x + dx * length;
        int ez = z + dz * length;
        // The landing: two blocks wider on each side at the end.
        for (int s = -1; s <= 1; s++) {
            for (int o = -2; o <= 2; o++) {
                int px = ex + dx * s + (dx == 0 ? o : 0);
                int pz = ez + dz * s + (dz == 0 ? o : 0);
                plank(px, pz, null);
                if (Math.abs(o) == 2 && s != 0) {
                    postDown(px, pz);
                    canvas.set(px, DECK_Y + 1, pz, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
                    canvas.set(px, DECK_Y + 2, pz, BlockSpec.of(Material.SPRUCE_FENCE));
                }
            }
        }
        int sideX = dx == 0 ? 1 : 0;
        int sideZ = dz == 0 ? 1 : 0;
        canvas.set(ex - sideX * 2, DECK_Y + 1, ez - sideZ * 2, BlockSpec.of(Material.BARREL));
        canvas.set(ex + sideX * 2, DECK_Y + 1, ez + sideZ * 2, BlockSpec.of(Material.BARREL));
        canvas.set(ex + sideX * 2, DECK_Y + 2, ez + sideZ * 2, Blocks.lantern(false, false));
        terrain.reserve(ex - 3, ez - 3, ex + 3, ez + 3);
    }

    /**
     * A little rowboat (4 long, 2 wide) floating at the surface, its stern at {@code x, z}, pointing {@code forward};
     * only placed where the water under all of it is at least two deep.
     */
    void rowboat(int x, int z, BlockFace forward) {
        int fx = forward.getModX();
        int fz = forward.getModZ();
        // The second row lies to the right of the boat's heading.
        int rx = -fz;
        int rz = fx;
        int water = LakeTerrain.WATER_Y;
        for (int s = 0; s < 4; s++) {
            for (int o = 0; o < 2; o++) {
                int bx = x + fx * s + rx * o;
                int bz = z + fz * s + rz * o;
                BlockSpec there = canvas.get(bx, water, bz);
                if (!terrain.isFree(bx, bz) || !terrain.isWater(bx, bz) || terrain.top(bx, bz) > water - 2
                        || there == null || there.material() != Material.WATER) {
                    return;
                }
            }
        }
        for (int s = 0; s < 4; s++) {
            for (int o = 0; o < 2; o++) {
                int bx = x + fx * s + rx * o;
                int bz = z + fz * s + rz * o;
                BlockSpec hull = s == 3 ? Blocks.stairs(Material.SPRUCE_STAIRS, forward)
                        : s == 0 ? Blocks.stairs(Material.SPRUCE_STAIRS, forward.getOppositeFace())
                        : Blocks.slab(Material.SPRUCE_SLAB);
                canvas.set(bx, water, bz, hull);
                terrain.reserve(bx, bz, bx, bz);
            }
        }
    }

    private void plank(int x, int z, Axis stringer) {
        if (!canvas.canWrite(x, z)) {
            return;
        }
        if (canvas.surfaceY(x, z) > DECK_Y) {
            return;
        }
        BlockSpec deck;
        if (stringer != null) {
            deck = Blocks.log(random.nextInt(4) == 0 ? Material.STRIPPED_SPRUCE_LOG : Material.SPRUCE_LOG, stringer);
        } else {
            int roll = random.nextInt(12);
            deck = roll < 7 ? BlockSpec.of(Material.SPRUCE_PLANKS) : roll < 10 ? BlockSpec.of(Material.OAK_PLANKS)
                    : roll < 11 ? Blocks.slab(Material.SPRUCE_SLAB) : BlockSpec.of(Material.STRIPPED_SPRUCE_WOOD);
        }
        canvas.set(x, DECK_Y, z, deck);
    }

    /** A post from the bed up to just under the deck (only over water or bank). */
    private void postDown(int x, int z) {
        if (!canvas.canWrite(x, z)) {
            return;
        }
        int ground = terrain.top(x, z);
        for (int y = ground + 1; y < DECK_Y; y++) {
            canvas.set(x, y, z, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
        }
    }

    private void railPost(int x, int z, boolean lantern) {
        canvas.set(x, DECK_Y + 1, z, BlockSpec.of(Material.SPRUCE_FENCE));
        if (lantern) {
            canvas.set(x, DECK_Y + 2, z, Blocks.lantern(false, false));
        }
    }

    private boolean isWaterBelow(int x, int z) {
        return terrain.top(x, z) < DECK_Y;
    }
}
