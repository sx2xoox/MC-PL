package io.github.sx2xoox.mcpl.core.map.citadel;

import static io.github.sx2xoox.mcpl.core.map.citadel.KeepKit.FLOOR;
import static io.github.sx2xoox.mcpl.core.map.citadel.KeepKit.GROUND;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * A round tower on the bailey wall (nine blocks across, centred just inside the wall line): a door to the courtyard
 * and one to the outside, a spiral stair round a stone newel up to a floor level with the wall walk (with doorways
 * onto it) and on to a crenellated top. {@link Style} says how ruined it is.
 */
final class KeepBaileyTower {

    enum Style {
        /** The top broke open on the outer side: a lookout with a view over the districts. */
        LOOKOUT,
        /** Still whole, with its battlements. */
        INTACT,
        /** The outer half fell: the spiral stands open to the sky, the top floor is gone. */
        COLLAPSED
    }

    /** The floor at the wall walk's level. */
    private static final int MID = KeepBailey.TOP;
    /** The top floor. */
    private static final int ROOF = MID + 6;
    /** The spiral climbs one block per cell round the newel, clockwise seen from above. */
    private static final int[][] RING = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
    /** Steps of the spiral: 8 to the middle floor, 6 more to the top. */
    private static final int STEPS = 14;

    private final MapCanvas canvas;
    private final Random random;
    private final KeepBailey bailey;
    private final Style style;
    private final int cx;
    private final int cz;
    private final int salt;
    private final BlockFace inward;
    private final int start;

    KeepBaileyTower(MapCanvas canvas, Random random, KeepBailey bailey, double angle, Style style) {
        this.canvas = canvas;
        this.random = random;
        this.bailey = bailey;
        this.style = style;
        int[] center = CitadelLayout.polar(24, angle);
        this.cx = center[0];
        this.cz = center[1];
        this.salt = random.nextInt();
        this.inward = KeepKit.cardinal(-cx, -cz);
        this.start = switch (inward) {
            case NORTH -> 1;
            case EAST -> 3;
            case SOUTH -> 5;
            default -> 7;
        };
    }

    static boolean isInside(int dx, int dz) {
        return dx * dx + dz * dz <= 12;
    }

    static boolean isShell(int dx, int dz) {
        int d2 = dx * dx + dz * dz;
        return d2 > 12 && d2 <= 20;
    }

    void build() {
        shell();
        floors();
        spiral();
        door(inward);
        if (style != Style.COLLAPSED) {
            door(inward.getOppositeFace());
        }
        walkDoorways();
        switch (style) {
            case LOOKOUT -> breakTop();
            case COLLAPSED -> collapse();
            default -> crown();
        }
        furnish();
    }

    // ------------------------------------------------------------------ body

    private void shell() {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (isInside(dx, dz)) {
                    canvas.set(x, GROUND, z, KeepKit.paving(random));
                    KeepKit.clear(canvas, x, FLOOR, z, ROOF + 4);
                } else if (isShell(dx, dz)) {
                    for (int y = FLOOR; y <= ROOF + 1; y++) {
                        BlockSpec block = y <= FLOOR + 1 ? KeepKit.footing(random) : KeepKit.masonry(random);
                        if (y == MID) {
                            block = KeepKit.darkMasonry(random); // string course at the walk's level
                        }
                        canvas.set(x, y, z, block);
                    }
                    KeepKit.clear(canvas, x, ROOF + 2, z, ROOF + 4);
                }
            }
        }
    }

    /** The plank middle floor (at the wall walk's level), the stone top floor and the newel between them. */
    private void floors() {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (!isInside(dx, dz) || Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    continue;
                }
                BlockSpec planks = BlockSpec.of(random.nextInt(5) == 0 ? Material.DARK_OAK_PLANKS
                        : Material.SPRUCE_PLANKS);
                canvas.set(cx + dx, MID, cz + dz, planks);
                canvas.set(cx + dx, ROOF, cz + dz, random.nextInt(4) == 0 ? KeepKit.masonry(random)
                        : BlockSpec.of(Material.STONE_BRICKS));
            }
        }
        // The newel.
        for (int y = FLOOR; y < ROOF; y++) {
            canvas.set(cx, y, cz, BlockSpec.of(y == FLOOR ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS));
        }
        canvas.set(cx, ROOF, cz, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
    }

    /**
     * The spiral: step {@code k} sits on ring cell {@code start + k} at {@code FLOOR + k}. The ring cells under the
     * upper turn are filled down to where the lower turn needs head room (three blocks), so it reads as a solid
     * newel stair; the floors are left open over the steps that need head room.
     */
    private void spiral() {
        for (int k = 0; k < STEPS; k++) {
            int[] cell = RING[(start + k) % RING.length];
            int[] next = RING[(start + k + 1) % RING.length];
            BlockFace facing = KeepKit.cardinal(next[0] - cell[0], next[1] - cell[1]);
            int x = cx + cell[0];
            int z = cz + cell[1];
            int y = FLOOR + k;
            int below = k < 8 ? FLOOR : Math.max(MID, FLOOR + (k - 8) + 4);
            for (int fill = below; fill < y; fill++) {
                canvas.set(x, fill, z, KeepKit.masonry(random));
            }
            canvas.set(x, y, z, Blocks.stairs(random.nextInt(4) == 0 ? Material.MOSSY_STONE_BRICK_STAIRS
                    : Material.STONE_BRICK_STAIRS, facing));
        }
        // Ring cells not carrying a step at the floors' levels: floor them where nothing needs head room.
        for (int i = 0; i < RING.length; i++) {
            int k = Math.floorMod(i - start, RING.length);
            int x = cx + RING[i][0];
            int z = cz + RING[i][1];
            if (k <= 3 && KeepKit.isFree(canvas, x, MID, z)) {
                canvas.set(x, MID, z, BlockSpec.of(Material.SPRUCE_PLANKS));
            }
            boolean upperNeedsRoom = k >= 2 && k <= 4;
            if (!upperNeedsRoom && k != 5 && KeepKit.isFree(canvas, x, ROOF, z)) {
                canvas.set(x, ROOF, z, BlockSpec.of(Material.STONE_BRICKS));
            }
        }
    }

    /** A three-wide arched door through the shell on {@code face}. */
    private void door(BlockFace face) {
        for (int along = 3; along <= 4; along++) {
            for (int across = -1; across <= 1; across++) {
                int dx = face.getModX() * along + (face.getModZ() != 0 ? across : 0);
                int dz = face.getModZ() * along + (face.getModX() != 0 ? across : 0);
                if (!isShell(dx, dz)) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                canvas.set(x, GROUND, z, BlockSpec.of(Material.STONE_BRICKS));
                KeepKit.clear(canvas, x, FLOOR, z, FLOOR + 2);
                if (across == 0) {
                    canvas.set(x, FLOOR + 3, z, BlockSpec.AIR);
                } else {
                    BlockFace side = across < 0 ? (face.getModX() != 0 ? BlockFace.NORTH : BlockFace.WEST)
                            : (face.getModX() != 0 ? BlockFace.SOUTH : BlockFace.EAST);
                    canvas.set(x, FLOOR + 3, z, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, side));
                }
            }
        }
    }

    /** Doorways at the middle floor wherever the wall walk meets the tower. */
    private void walkDoorways() {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (!isShell(dx, dz) || !meetsWalk(dx, dz)) {
                    continue;
                }
                openWalk(dx, dz);
                // Two-thick parts of the shell: open the inner layer too.
                for (BlockFace face : KeepKit.sides()) {
                    int ix = dx + face.getModX();
                    int iz = dz + face.getModZ();
                    if (isShell(ix, iz) && ix * ix + iz * iz < dx * dx + dz * dz) {
                        openWalk(ix, iz);
                    }
                }
            }
        }
    }

    private boolean meetsWalk(int dx, int dz) {
        for (BlockFace face : KeepKit.sides()) {
            int ox = dx + face.getModX();
            int oz = dz + face.getModZ();
            if (isShell(ox, oz) || isInside(ox, oz)) {
                continue;
            }
            int x = cx + ox;
            int z = cz + oz;
            if (KeepBailey.inBand(x, z) && bailey.wallTop(x, z) >= MID - 1) {
                return true;
            }
        }
        return false;
    }

    private void openWalk(int dx, int dz) {
        int x = cx + dx;
        int z = cz + dz;
        canvas.set(x, MID, z, KeepKit.paving(random));
        KeepKit.clear(canvas, x, MID + 1, z, MID + 3);
    }

    // ------------------------------------------------------------------ tops

    /** Merlons round the top, two up and one down. */
    private void crown() {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (!isShell(dx, dz)) {
                    continue;
                }
                double arc = (Math.atan2(dz, dx) + Math.PI) * 4.5;
                if (Math.floorMod((int) Math.floor(arc), 3) != 0) {
                    canvas.set(cx + dx, ROOF + 2, cz + dz, KeepKit.masonry(random));
                }
            }
        }
        canvas.set(cx + 2, ROOF + 1, cz + 2, Blocks.lantern(false, false));
        canvas.set(cx - 2, ROOF + 1, cz - 2, Blocks.lantern(false, false));
    }

    /** The lookout: the outer side of the top broke away in a ragged notch, the rest keeps a few merlons. */
    private void breakTop() {
        BlockFace out = inward.getOppositeFace();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                boolean shell = isShell(dx, dz);
                if (!shell && !isInside(dx, dz)) {
                    continue;
                }
                double toward = (dx * out.getModX() + dz * out.getModZ()) / Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
                double n = KeepKit.noise(salt, (cx + dx) * 0.7, (cz + dz) * 0.7);
                int x = cx + dx;
                int z = cz + dz;
                if (shell) {
                    int top = ROOF + 1;
                    if (toward > 0.35) {
                        top = ROOF - 1 - (int) Math.round(n * 3 + (toward - 0.35) * 3);
                    } else if (n > 0.55) {
                        top = ROOF + 2; // a merlon survived
                    }
                    KeepKit.clear(canvas, x, top + 1, z, ROOF + 3);
                    if (top == ROOF + 2) {
                        canvas.set(x, top, z, KeepKit.masonry(random));
                    }
                } else if (toward > 0.55 && Math.abs(dx) + Math.abs(dz) > 1 && n > 0.35) {
                    canvas.set(x, ROOF, z, BlockSpec.AIR); // the floor's edge fell with the wall
                }
            }
        }
    }

    /** The outer half fell: low ragged stumps of shell, no top floor, part of the middle floor gone, rubble. */
    private void collapse() {
        BlockFace out = inward.getOppositeFace();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                boolean shell = isShell(dx, dz);
                boolean inside = isInside(dx, dz);
                if (!shell && !inside) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                double toward = (dx * out.getModX() + dz * out.getModZ()) / Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
                double n = KeepKit.noise(salt, x * 0.6, z * 0.6);
                if (inside && !(Math.abs(dx) <= 1 && Math.abs(dz) <= 1)) {
                    canvas.set(x, ROOF, z, BlockSpec.AIR);
                    if (toward > 0.2) {
                        canvas.set(x, MID, z, BlockSpec.AIR);
                    }
                }
                if (shell) {
                    int top = toward > -0.15 ? FLOOR + (int) Math.round(n * 2.6) : ROOF - (int) Math.round(n * 3);
                    KeepKit.clear(canvas, x, top + 1, z, ROOF + 3);
                }
            }
        }
        // The top of the stair went with the roof.
        for (int k = 11; k < STEPS; k++) {
            int[] cell = RING[(start + k) % RING.length];
            KeepKit.clear(canvas, cx + cell[0], FLOOR + k - 1, cz + cell[1], ROOF);
        }
        for (int i = 0; i < RING.length; i++) {
            canvas.set(cx + RING[i][0], ROOF, cz + RING[i][1], BlockSpec.AIR);
        }
        canvas.set(cx, ROOF, cz, BlockSpec.AIR);
        canvas.set(cx, ROOF - 1, cz, BlockSpec.AIR);
        // Rubble spilled out over the ground outside and onto the ground floor.
        KeepKit.Heap heap = new KeepKit.Heap();
        heap.mound(salt + 1, cx + out.getModX() * 4.5, cz + out.getModZ() * 4.5, 3.0, 1.6);
        heap.mound(salt + 2, cx + out.getModX() * 2.0 - out.getModZ() * 1.5,
                cz + out.getModZ() * 2.0 + out.getModX() * 1.5, 1.6, 1.0);
        heap.place(canvas, random, true);
    }

    // ------------------------------------------------------------------ details

    private void furnish() {
        // A lantern hanging under the middle floor and barrels by the inner door.
        int lx = cx + 2 * (inward.getModZ() != 0 ? 1 : 0) - inward.getModX();
        int lz = cz + 2 * (inward.getModX() != 0 ? 1 : 0) - inward.getModZ();
        if (KeepKit.isFree(canvas, lx, MID - 1, lz) && !KeepKit.isFree(canvas, lx, MID, lz)) {
            canvas.set(lx, MID - 1, lz, Blocks.lantern(false, true));
        }
        BlockFace side = inward.getModX() != 0 ? BlockFace.SOUTH : BlockFace.EAST;
        int bx = cx + inward.getModX() * 2 + side.getModX() * 2;
        int bz = cz + inward.getModZ() * 2 + side.getModZ() * 2;
        if (isInside(bx - cx, bz - cz) && KeepKit.isFree(canvas, bx, FLOOR, bz)) {
            canvas.set(bx, FLOOR, bz, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        }
        // Torches beside the courtyard door.
        int tx = cx + inward.getModX() * 5;
        int tz = cz + inward.getModZ() * 5;
        BlockFace across = inward.getModX() != 0 ? BlockFace.SOUTH : BlockFace.EAST;
        for (int s = -2; s <= 2; s += 4) {
            int x = tx + across.getModX() * s;
            int z = tz + across.getModZ() * s;
            int bxs = x - inward.getModX();
            int bzs = z - inward.getModZ();
            if (KeepKit.isFree(canvas, x, FLOOR + 2, z) && !KeepKit.isFree(canvas, bxs, FLOOR + 2, bzs)) {
                KeepKit.put(canvas, x, FLOOR + 2, z, Blocks.wallTorch(false, inward));
            }
        }
    }
}
