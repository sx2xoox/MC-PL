package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The forest's landmark: a giant hollow tree, thirteen blocks across. Three arched doorways lead into its hollow,
 * where a two-wide staircase winds up the inside of the trunk to a wooden deck in its crown, fifteen blocks above the
 * forest floor. The crown is a great bowl of leaves around the deck: from the deck its inner slope climbs to the
 * crown's rim, so the deck is screened from below but open to the sky. Two ladders up the outside of the trunk are
 * other ways up and down, so the deck is a strong lookout but never a fortress.
 *
 * <p>Plan (offsets from the trunk center, north up): the hollow is the square {@code |dx|,|dz| <= 3}; the stairs run
 * clockwise along its walls (two wide, a landing in every corner) around an open three-by-three well, one and a
 * quarter turns from the north-west corner up to the deck.
 */
final class ForestHollowTree {

    /** Height of the deck's walking surface above the forest floor's surface block. */
    static final int DECK = 15;
    static final double TRUNK_RADIUS = 6.0;
    static final double DECK_RADIUS = 8.2;
    /** Where the crown's bowl starts on the deck, and where its rim is highest. */
    private static final double BOWL_INNER = 6.4;
    private static final double BOWL_RIM = 9.4;

    private static final Material BARK = Material.DARK_OAK_WOOD;
    private static final Material INNER = Material.STRIPPED_DARK_OAK_WOOD;
    private static final Material PLANKS = Material.SPRUCE_PLANKS;
    private static final Material STAIRS = Material.SPRUCE_STAIRS;

    /** Window slits: {band cell dx, dz, outward dx, dz, level of the stairs beside it}. */
    private static final int[][] WINDOWS = {
            {3, 0, 1, 0, 5}, {3, 3, 1, 0, 6}, {0, 3, 0, 1, 8}, {-3, 3, 0, 1, 9}, {-3, 0, -1, 0, 11},
            {-3, -3, -1, 0, 12}, {3, -3, 0, -1, 3},
    };
    /** The outside ladders' columns (offsets from the trunk center) and the way each faces, away from the trunk. */
    private static final int[][] LADDERS = {{0, -7}, {5, 5}};
    private static final BlockFace[] LADDER_FACINGS = {BlockFace.NORTH, BlockFace.EAST};

    private final ForestSite site;
    private final ForestCanopy canopy;
    private final int cx;
    private final int cz;
    private final int y0;

    ForestHollowTree(ForestSite site, int cx, int cz) {
        this.site = site;
        this.canopy = site.canopy();
        this.cx = cx;
        this.cz = cz;
        this.y0 = site.ground(cx, cz);
    }

    /** Builds the trunk, the stairs, the deck and the crown (the outside ladders come with {@link #finish}). */
    void build() {
        site.markDisk(cx, cz, TRUNK_RADIUS + 0.45, ForestSite.BUILT, true);
        // The flared foot of the trunk counts as roots: the earth mound around them climbs onto it.
        site.markDisk(cx, cz, TRUNK_RADIUS + 1.2, ForestSite.TRUNK, false);
        for (int[] ladder : LADDERS) {
            site.mark(cx + ladder[0], cz + ladder[1], ForestSite.BUILT);
            canopy.exclude(cx + ladder[0], cz + ladder[1]);
        }
        trunk();
        doorways();
        windows();
        stairs();
        deck();
        roots();
        site.markDisk(cx, cz, TRUNK_RADIUS + 7, ForestSite.CLEARING, false);
        branches();
        crown();
        interior();
    }

    // ------------------------------------------------------------------ trunk

    private double radiusAt(int y) {
        int h = y - y0;
        if (h <= 1) {
            return TRUNK_RADIUS + 0.7;
        }
        if (h == 2) {
            return TRUNK_RADIUS + 0.3;
        }
        return TRUNK_RADIUS;
    }

    private void trunk() {
        int r = (int) Math.ceil(DECK_RADIUS + 2);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                boolean hollow = Math.max(Math.abs(dx), Math.abs(dz)) <= 3;
                int x = cx + dx;
                int z = cz + dz;
                if (d <= DECK_RADIUS + 1.5) {
                    // Nothing of any crown grows under the deck or inside the trunk.
                    canopy.floor(x, z, y0 + DECK + 1);
                }
                if (d <= TRUNK_RADIUS + 0.7) {
                    site.set(x, y0, z, hollow ? floor(x, z) : BlockSpec.of(Material.ROOTED_DIRT));
                }
                for (int y = y0 + 1; y < y0 + DECK; y++) {
                    if (d > radiusAt(y) + 0.45) {
                        continue;
                    }
                    if (hollow) {
                        site.clear(x, y, z);
                        continue;
                    }
                    boolean outer = d > radiusAt(y) - 0.8;
                    site.set(x, y, z, outer ? bark(x, y, z) : BlockSpec.of(INNER));
                }
            }
        }
    }

    private BlockSpec bark(int x, int y, int z) {
        double moss = site.noise(x * 0.8 + z * 0.3, y * 1.4, 3.0, 61);
        if (moss > 0.7) {
            return BlockSpec.of(Material.MOSS_BLOCK);
        }
        if (moss < 0.12) {
            return BlockSpec.of(Material.DARK_OAK_LOG).axis(Axis.Y);
        }
        return BlockSpec.of(BARK);
    }

    private BlockSpec floor(int x, int z) {
        double n = site.hash(x, z, 62);
        if (n < 0.45) {
            return BlockSpec.of(Material.ROOTED_DIRT);
        }
        if (n < 0.75) {
            return BlockSpec.of(Material.PODZOL);
        }
        return BlockSpec.of(Material.MOSS_BLOCK);
    }

    /** Three arched doorways (west, south, east), three wide and three high, opening onto paths through the roots. */
    private void doorways() {
        int[][] doors = {{-1, 0}, {0, 1}, {1, 0}};
        for (int[] dir : doors) {
            for (int s = 4; s <= 12; s++) {
                for (int w = -1; w <= 1; w++) {
                    int x = cx + dir[0] * s + (dir[0] == 0 ? w : 0);
                    int z = cz + dir[1] * s + (dir[1] == 0 ? w : 0);
                    if (s <= 8) {
                        int height = w == 0 ? 4 : 3;
                        for (int y = y0 + 1; y <= y0 + height; y++) {
                            site.clear(x, y, z);
                        }
                        site.set(x, y0, z, BlockSpec.of(s <= 7 ? Material.PODZOL : Material.COARSE_DIRT));
                    }
                    site.mark(x, z, ForestSite.BUILT);
                }
            }
        }
    }

    /** Window slits through the trunk, each level with the stairs or landing beside it (players can climb out). */
    private void windows() {
        for (int[] w : WINDOWS) {
            int y = y0 + w[4] + 1;
            for (int s = 1; s <= 4; s++) {
                int x = cx + w[0] + w[2] * s;
                int z = cz + w[1] + w[3] * s;
                for (int h = 0; h < 3 && y + h < y0 + DECK; h++) {
                    site.clear(x, y + h, z);
                }
            }
        }
    }

    // ------------------------------------------------------------------ stairs

    /**
     * The climb: from the north-west corner clockwise along the walls, three steps per side and a landing in each
     * corner, one and a quarter turns up to the deck. A level is the height of the walking surface above the floor.
     */
    private void stairs() {
        int level = 0;
        // Five sides: north, east, south, west and north again.
        BlockFace[] faces = {BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST};
        for (int side = 0; side < faces.length; side++) {
            BlockFace face = faces[side];
            for (int i = -1; i <= 1; i++) {
                level++;
                for (int[] cell : sideCells(face, i)) {
                    step(cx + cell[0], cz + cell[1], level, face);
                }
            }
            if (side < faces.length - 1) {
                for (int[] cell : cornerCells(face)) {
                    landing(cx + cell[0], cz + cell[1], level);
                }
            }
        }
    }

    /** The two cells (inner and outer) of step {@code i} (-1..1) on the side walked towards {@code face}. */
    private static int[][] sideCells(BlockFace face, int i) {
        return switch (face) {
            case EAST -> new int[][] {{i, -3}, {i, -2}};
            case SOUTH -> new int[][] {{2, i}, {3, i}};
            case WEST -> new int[][] {{-i, 2}, {-i, 3}};
            default -> new int[][] {{-3, -i}, {-2, -i}};
        };
    }

    /** The 2x2 landing at the end of the side walked towards {@code face}. */
    private static int[][] cornerCells(BlockFace face) {
        int[] c = switch (face) {
            case EAST -> new int[] {2, -3};
            case SOUTH -> new int[] {2, 2};
            case WEST -> new int[] {-3, 2};
            default -> new int[] {-3, -3};
        };
        return new int[][] {{c[0], c[1]}, {c[0] + 1, c[1]}, {c[0], c[1] + 1}, {c[0] + 1, c[1] + 1}};
    }

    private void step(int x, int z, int level, BlockFace face) {
        site.set(x, y0 + level, z, level >= DECK ? BlockSpec.of(PLANKS) : Blocks.stairs(STAIRS, face));
        fillBelow(x, z, level);
    }

    private void landing(int x, int z, int level) {
        site.set(x, y0 + level, z, BlockSpec.of(PLANKS));
        fillBelow(x, z, level);
    }

    /** The lowest flight sits on solid wood; higher ones float, so players walk under them. */
    private void fillBelow(int x, int z, int level) {
        if (level <= 3) {
            for (int y = y0 + 1; y < y0 + level; y++) {
                site.set(x, y, z, BlockSpec.of(INNER));
            }
        }
    }

    // ------------------------------------------------------------------ deck and crown

    /** True for the deck columns left open above the last flight of stairs. */
    private static boolean stairwell(int dx, int dz) {
        boolean northWest = dx >= -3 && dx <= -2 && dz >= -3 && dz <= -2;
        boolean northFlight = dx >= -1 && dx <= 0 && dz >= -3 && dz <= -2;
        boolean westTop = dx >= -3 && dx <= -2 && dz == -1;
        return northWest || northFlight || westTop;
    }

    private void deck() {
        int y = y0 + DECK;
        int r = (int) Math.ceil(DECK_RADIUS);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > DECK_RADIUS || stairwell(dx, dz)) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                boolean rim = d > DECK_RADIUS - 1;
                site.set(x, y, z, rim ? BlockSpec.of(Material.DARK_OAK_PLANKS)
                        : BlockSpec.of(site.hash(x, z, 63) < 0.2 ? Material.STRIPPED_SPRUCE_WOOD : PLANKS));
                // Joists under the overhang, so the deck reads as built out over the trunk.
                if (d > TRUNK_RADIUS + 0.5 && (dx == 0 || dz == 0 || Math.abs(dx) == Math.abs(dz))) {
                    site.set(x, y - 1, z, Blocks.stairsTop(Material.SPRUCE_STAIRS, outward(dx, dz)));
                }
            }
        }
        // A few things on the deck: barrels, lanterns, a lookout bench.
        site.set(cx + 2, y + 1, cz + 3, Material.BARREL);
        site.set(cx + 3, y + 1, cz + 3, Material.BARREL);
        site.set(cx + 3, y + 1, cz + 2, Blocks.lantern(false, false));
        site.set(cx - 4, y + 1, cz + 3, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.WEST));
        site.set(cx - 4, y + 1, cz + 2, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.WEST));
        site.set(cx + 1, y + 1, cz - 4, Blocks.lantern(true, false));
        site.set(cx - 1, y + 1, cz + 5, Blocks.lantern(false, false));
        // Hanging lanterns under the deck, over the well.
        site.set(cx, y - 1, cz, Blocks.lantern(false, true));
        site.set(cx - 1, y - 1, cz + 1, Blocks.lantern(true, true));
    }

    private static BlockFace outward(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? BlockFace.WEST : BlockFace.EAST;
        }
        return dz > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    /**
     * Huge root flares running out from the trunk, and the earth heaped between them into a mound every column of
     * which can be climbed from the next. The doorways' paths and the ladders' feet stay clear.
     */
    private void roots() {
        double[] angles = {125, 147, 215, 238, 300, 323};
        for (double angle : angles) {
            double a = Math.toRadians(angle + site.range(-5.0, 5.0));
            double length = site.range(4.0, 6.0);
            for (double s = TRUNK_RADIUS; s <= TRUNK_RADIUS + length; s += 0.4) {
                double t = (s - TRUNK_RADIUS) / length;
                int height = t < 0.3 ? 3 : t < 0.65 ? 2 : 1;
                double width = t < 0.4 ? 1.0 : 0.5;
                for (double w = -width; w <= width; w += 0.5) {
                    int x = (int) Math.round(cx + Math.cos(a) * s - Math.sin(a) * w);
                    int z = (int) Math.round(cz + Math.sin(a) * s + Math.cos(a) * w);
                    if (!site.inZone(x, z) || site.use(x, z) != ForestSite.FREE && site.use(x, z) != ForestSite.TRUNK
                            || !site.openAround(x, z)) {
                        continue;
                    }
                    int g = site.ground(x, z);
                    for (int y = g + 1; y <= g + height; y++) {
                        if (site.isAir(x, y, z)) {
                            site.set(x, y, z, y == g + height && site.hash(x, z, 64) < 0.3
                                    ? BlockSpec.of(Material.MOSS_BLOCK) : BlockSpec.of(BARK));
                        }
                    }
                    site.mark(x, z, ForestSite.TRUNK);
                }
            }
        }
        boolean[] region = new boolean[ForestSite.columns()];
        double reach = TRUNK_RADIUS + 9;
        int r = (int) Math.ceil(reach);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (Math.sqrt(dx * dx + dz * dz) <= reach && site.isMoundable(x, z)) {
                    region[ForestSite.index(x, z)] = true;
                }
            }
        }
        site.raiseToSlope(region, null, site::stackTop, (x, y, z, top) -> {
            if (!top) {
                return BlockSpec.of(Material.DIRT);
            }
            double n = site.hash(x, z, 65);
            return BlockSpec.of(n < 0.35 ? Material.PODZOL : n < 0.55 ? Material.ROOTED_DIRT
                    : n < 0.75 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK);
        });
        for (int i = 0; i < region.length; i++) {
            if (region[i]) {
                int x = ForestSite.columnX(i);
                int z = ForestSite.columnZ(i);
                BlockSpec top = site.get(x, site.stackTop(x, z), z);
                if (top != null && top.material() != BARK) {
                    site.setGround(x, z, site.stackTop(x, z));
                }
            }
        }
    }

    /** Great branches spreading from the top of the trunk under the deck and up into the crown. */
    private void branches() {
        int count = 8;
        for (int i = 0; i < count; i++) {
            double a = (i + 0.5) * Math.PI * 2 / count + site.range(-0.15, 0.15);
            Axis axis = Math.abs(Math.cos(a)) >= Math.abs(Math.sin(a)) ? Axis.X : Axis.Z;
            for (double s = TRUNK_RADIUS - 0.5; s <= BOWL_RIM; s += 0.4) {
                int x = (int) Math.round(cx + Math.cos(a) * s);
                int z = (int) Math.round(cz + Math.sin(a) * s);
                double t = (s - TRUNK_RADIUS) / (BOWL_RIM - TRUNK_RADIUS);
                int y = y0 + DECK - 3 + (int) Math.round(Math.max(0, t) * 4);
                if (site.isAir(x, y, z) || site.get(x, y, z).material().name().endsWith("_LEAVES")) {
                    site.set(x, y, z, BlockSpec.of(Material.DARK_OAK_LOG).axis(axis));
                }
            }
        }
    }

    /** The bowl of the crown, as a height field: from the deck up its inner slope to the rim, then down outside. */
    private void crown() {
        int r = 16;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                double outer = 12.0 + 1.6 * site.noise(cx + dx * 0.6, cz + dz * 0.6, 4, 67);
                if (d < BOWL_INNER || d > outer) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                double rise = d < BOWL_RIM ? 1 + (d - BOWL_INNER)
                        : 3.5 + Math.round(site.noise(x, z, 3, 68)) - Math.max(0, d - BOWL_RIM - 1.5);
                int top = y0 + DECK + (int) Math.floor(rise);
                int bottom = d <= DECK_RADIUS + 0.3 ? y0 + DECK + 1
                        : y0 + DECK - 2 + (int) Math.round((d - DECK_RADIUS) * 0.7 + site.noise(x, z, 2, 69));
                for (int y = Math.min(bottom, top); y <= top; y++) {
                    canopy.leaves(x, y, z, leaf(x, y, z));
                }
                if (d < BOWL_INNER + 1) {
                    canopy.fix(x, z);
                }
            }
        }
        // Tufts on the rim break up its line.
        double[] tufts = {20, 100, 165, 250, 320};
        for (double angle : tufts) {
            double a = Math.toRadians(angle + site.range(-12.0, 12.0));
            double d = BOWL_RIM + site.range(0.5, 1.5);
            double tx = cx + Math.cos(a) * d;
            double tz = cz + Math.sin(a) * d;
            canopy.blob(tx, y0 + DECK + 5, tz, site.range(1.8, 2.4), 1.2, site.range(1.8, 2.4),
                    leaf((int) tx, y0 + DECK + 5, (int) tz), 0.2);
        }
    }

    private Material leaf(int x, int y, int z) {
        double n = site.noise(x + y * 0.5, z - y * 0.5, 4, 70);
        if (n > 0.72) {
            return Material.FLOWERING_AZALEA_LEAVES;
        }
        if (n > 0.6) {
            return Material.AZALEA_LEAVES;
        }
        return n < 0.25 ? Material.OAK_LEAVES : Material.DARK_OAK_LEAVES;
    }

    /** Mushrooms, leaf litter and hanging roots in the hollow. */
    private void interior() {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                double n = site.hash(x, z, 66);
                if (n < 0.25) {
                    site.set(x, y0 + 1, z, n < 0.12 ? Material.BROWN_MUSHROOM : Material.RED_MUSHROOM);
                } else if (n < 0.5) {
                    site.set(x, y0 + 1, z, Material.LEAF_LITTER);
                }
            }
        }
        site.set(cx + 1, y0 + DECK - 1, cz - 1, Material.HANGING_ROOTS);
        site.set(cx - 1, y0 + DECK - 1, cz - 1, Material.HANGING_ROOTS);
        site.set(cx + 1, y0 + 1, cz + 1, Blocks.campfire(false));
    }

    /** The ladders up the outside of the trunk, through the deck to the crown. Call after the canopy finish. */
    void finish() {
        for (int i = 0; i < LADDERS.length; i++) {
            int x = cx + LADDERS[i][0];
            int z = cz + LADDERS[i][1];
            BlockFace facing = LADDER_FACINGS[i];
            int tx = x - facing.getModX();
            int tz = z - facing.getModZ();
            int landing = Integer.MAX_VALUE;
            for (int[] side : ForestSite.SIDES) {
                int nx = x + side[0];
                int nz = z + side[1];
                if (nx != tx || nz != tz) {
                    int top = site.top(nx, nz);
                    if (top >= y0 + DECK) {
                        landing = Math.min(landing, top);
                    }
                }
            }
            int g = site.ground(x, z);
            for (int y = g + 1; y <= landing + 2; y++) {
                site.clear(x, y, z);
            }
            BlockSpec ladder = BlockSpec.of(Material.LADDER).facing(facing);
            for (int y = g + 1; y <= landing; y++) {
                site.set(x, y, z, ladder);
                // Bark behind the ladder where a window or doorway opens the trunk (never on the deck itself).
                if (y < y0 + DECK && site.isAir(tx, y, tz)) {
                    site.set(tx, y, tz, BlockSpec.of(BARK));
                }
            }
        }
    }
}
