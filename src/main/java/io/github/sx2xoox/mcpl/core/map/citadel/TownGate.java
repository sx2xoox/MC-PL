package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.citadel.TownPainter.Palette;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The town gate at the district's inner tip, facing the keep: two square towers ({@code x 36..40}) either side of a
 * five-wide passage ({@code z 19..23}) under an arch with a raised portcullis, a walkway over the arch between the
 * tower tops ({@code y = 70}, players stand at 71, +6), and a stretch of broken town wall running south.
 *
 * <p>Ways up: a ladder inside each tower, an outside stair along the north tower, and the town wall, whose top
 * crumbles down to the ground in steps.
 */
final class TownGate {

    private static final int G = TownPainter.GROUND;
    private static final int X0 = 36;
    private static final int X1 = 40;
    private static final int NORTH_Z0 = 14;
    private static final int NORTH_Z1 = 18;
    private static final int SOUTH_Z0 = 24;
    private static final int SOUTH_Z1 = 28;
    private static final int TOP = G + 6;

    private static final Palette STONE = Palette.of(Material.STONE_BRICKS, Material.STONE_BRICKS,
                    Material.CRACKED_STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.COBBLESTONE,
                    Material.MOSSY_COBBLESTONE)
            .withSlabs(Material.STONE_BRICK_SLAB, Material.MOSSY_STONE_BRICK_SLAB, Material.COBBLESTONE_SLAB)
            .withStairs(Material.STONE_BRICK_STAIRS, Material.MOSSY_STONE_BRICK_STAIRS);

    private TownGate() {
    }

    static void build(TownPainter p) {
        tower(p, NORTH_Z0, NORTH_Z1, BlockFace.SOUTH, 16);
        tower(p, SOUTH_Z0, SOUTH_Z1, BlockFace.NORTH, 26);
        arch(p);
        outsideStair(p);
        townWall(p);
        p.rubble(34, 31, G + 1, 1.5, 2, TownPainter.RUBBLE);
        p.rubble(33, 12, G + 1, 1.2, 1, TownPainter.RUBBLE);
        p.debris(33, 21, 4, 10, STONE);
    }

    /** A 5x5 tower: a ground room with two doors and a ladder up through a hatch to the crenellated top. */
    private static void tower(TownPainter p, int z0, int z1, BlockFace passageSide, int ladderZ) {
        Random random = p.random();
        for (int x = X0; x <= X1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == X0 || x == X1 || z == z0 || z == z1;
                boolean corner = (x == X0 || x == X1) && (z == z0 || z == z1);
                p.set(x, G, z, wall ? STONE.next(random) : BlockSpec.of(Material.COBBLESTONE));
                for (int y = G + 1; y < TOP; y++) {
                    if (wall) {
                        p.set(x, y, z, corner ? BlockSpec.of(Material.CHISELED_STONE_BRICKS) : STONE.next(random));
                    } else {
                        p.clear(x, y, z);
                    }
                }
                p.set(x, TOP, z, wall ? STONE.next(random) : BlockSpec.of(Material.SPRUCE_PLANKS));
                // parapet: merlons on every other outer cell, open towards the walkway and the wall
                boolean inner = passageSide == BlockFace.SOUTH ? z == z1 : z == z0;
                boolean wallSide = passageSide == BlockFace.NORTH && z == z1 && x <= X0 + 1;
                boolean stairSide = passageSide == BlockFace.SOUTH && z == z0 && x <= X0 + 1;
                if (wall && !inner && !wallSide && !stairSide && (x + z) % 2 == 0) {
                    p.set(x, TOP + 1, z, STONE.next(random));
                }
            }
        }
        // doors: into the passage and towards the town
        int doorZ = passageSide == BlockFace.SOUTH ? z1 : z0;
        p.door(38, G + 1, doorZ, Material.SPRUCE_DOOR, passageSide, false);
        p.door(X1, G + 1, (z0 + z1) / 2, Material.SPRUCE_DOOR, BlockFace.EAST, true);
        // ladder on the inside of the west wall, through a hatch
        p.ladder(X0 + 1, G + 1, TOP, ladderZ, BlockFace.EAST);
        // an arrow slit facing the keep, and a lantern under the hatch floor
        int slitZ = ladderZ == z0 + 2 ? z0 + 1 : z0 + 2;
        p.clearBox(X0, G + 2, slitZ, X0, G + 3, slitZ);
        p.set(X0 + 2, TOP - 1, (z0 + z1) / 2, Blocks.lantern(false, true));
    }

    /** The arch over the passage with a raised portcullis, and the walkway on top. */
    private static void arch(TownPainter p) {
        Random random = p.random();
        for (int x = X0; x <= X1; x++) {
            for (int z = NORTH_Z1 + 1; z < SOUTH_Z0; z++) {
                p.set(x, TOP - 1, z, STONE.next(random));
                p.set(x, TOP, z, x == X0 || x == X1 ? STONE.next(random) : BlockSpec.of(Material.SPRUCE_PLANKS));
                if (x == X0 && z % 2 == 1) {
                    p.set(x, TOP + 1, z, STONE.next(random));
                }
            }
            p.set(x, TOP - 2, NORTH_Z1 + 1, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.NORTH));
            p.set(x, TOP - 2, SOUTH_Z0 - 1, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.SOUTH));
        }
        for (int z = NORTH_Z1 + 2; z < SOUTH_Z0 - 1; z++) {
            p.set(38, TOP - 2, z, Material.IRON_BARS);
        }
        // lanterns hanging under the arch
        p.set(X0, TOP - 2, 21, Blocks.lantern(false, true));
        p.set(X1, TOP - 2, 21, Blocks.lantern(false, true));
    }

    /** Stone steps up the north face of the north tower, from the east to its top. */
    private static void outsideStair(TownPainter p) {
        int z = NORTH_Z0 - 1;
        for (int i = 0; i < 5; i++) {
            int x = X1 + 1 - i;
            int y = G + 1 + i;
            if (y > G + 1) {
                p.fill(x, G + 1, z, x, y - 1, z, STONE);
            }
            p.set(x, y, z, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.WEST));
        }
        p.set(X1 + 2, G + 1, z, Blocks.slab(Material.STONE_BRICK_SLAB));
    }

    /**
     * The town wall south of the gate: two blocks thick, level with the tower top where it leaves the tower, then
     * crumbling down in steps to a breach, and a low stub beyond.
     */
    private static void townWall(TownPainter p) {
        Random random = p.random();
        int[] heights = {6, 6, 5, 4, 3, 2, 1, 0, 0, 1, 2, 2, 1};
        for (int i = 0; i < heights.length; i++) {
            int z = SOUTH_Z1 + 1 + i;
            int h = heights[i];
            for (int x = X0; x <= X0 + 1; x++) {
                p.set(x, G, z, STONE.next(random));
                for (int y = G + 1; y <= G + h; y++) {
                    p.set(x, y, z, STONE.next(random));
                }
            }
            if (h >= 2 && i % 2 == 0) {
                p.set(X0, G + h + 1, z, STONE.next(random));
            }
        }
        p.rubble(X0 + 3, SOUTH_Z1 + 8, G + 1, 1.3, 1, TownPainter.RUBBLE);
        p.rubble(X0 - 2, SOUTH_Z1 + 9, G + 1, 1.3, 1, TownPainter.RUBBLE);
    }
}
