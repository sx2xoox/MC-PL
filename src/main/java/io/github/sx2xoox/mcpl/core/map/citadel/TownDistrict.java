package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.citadel.TownHouse.State;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The ruined town district (east-southeast, 0°..60°): a market square with a well and stalls in the middle, a chapel
 * with a climbable bell tower on a terrace south of it, a broken town gate facing the keep, and thirteen houses and a
 * barn along winding lanes, some still roofed, some half collapsed, some only shells.
 *
 * <p>Plan (map-local x east, z south; the district spans x 18..96, z 3..83):
 * <pre>
 *   gate (x 36..40)  -> gate street -> market square (x 52..68, z 19..36) -> east street -> wall lane
 *   north lane (bridged at the upper floors) and tavern lane run from the market to the east road (z 3)
 *   chapel street climbs to the chapel terrace: chapel (x 55..63, z 46..61), bell tower (x 64..70, z 43..49)
 *   the south-west lane runs to the south-east road; the barn and gardens fill the southern tip
 * </pre>
 * Heights for a fight: rooftops (every roof has a ladder or a crumbled wall up to it, +8..+13), the tavern balcony
 * and the lane bridge (+5), the gate walkway (+6), the chapel terrace (+2) and the bell tower platform (+16).
 */
final class TownDistrict implements MapZone {

    @Override
    public String name() {
        return "town";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isDistrict(CitadelLayout.District.TOWN, x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        TownPainter p = new TownPainter(canvas, canvas.random(name()));
        TownStreets.pave(p);
        for (TownHouse house : houses()) {
            house.build(p);
        }
        TownMarket.build(p);
        TownChapel.build(p);
        TownGate.build(p);
        yards(p);
        TownProps.groundCover(p, 15, 0, 97, 85, 0.07);
    }

    /**
     * A balcony on the tavern's east gable (x 80..81, z 9..13), on two posts over the east door, reached through a
     * doorway from the upper floor; players can drop from it to the street.
     */
    private static void tavernBalcony(TownPainter p) {
        int floor = TownPainter.GROUND + 5;
        for (int x = 80; x <= 81; x++) {
            for (int z = 9; z <= 13; z++) {
                p.set(x, floor, z, Material.SPRUCE_PLANKS);
                boolean rail = x == 81 || z == 9 || z == 13;
                if (rail && z != 11) {
                    p.set(x, floor + 1, z, Material.DARK_OAK_FENCE);
                }
            }
        }
        for (int z : new int[] {9, 13}) {
            for (int y = TownPainter.GROUND + 1; y < floor; y++) {
                p.log(81, y, z, Material.DARK_OAK_LOG, Axis.Y);
            }
        }
        for (int z = 10; z <= 12; z++) {
            p.set(81, floor - 1, z, Blocks.stairsTop(Material.SPRUCE_STAIRS, BlockFace.WEST));
        }
        // the doorway from the upper floor, and a lantern hanging on a chain from the roof's overhang
        p.clearBox(79, floor + 1, 11, 79, floor + 2, 11);
        p.fill(80, floor + 4, 10, 80, floor + 7, 10, BlockSpec.of(Material.IRON_CHAIN));
        p.set(80, floor + 3, 10, Blocks.lantern(false, true));
    }

    /**
     * An open timber footbridge over the north lane (x 52..55, z 10..12) between the upper floors of the house by the
     * gate and the weaver's house, through doorways in both walls.
     */
    private static void laneBridge(TownPainter p) {
        int floor = TownPainter.GROUND + 5;
        for (int x = 52; x <= 55; x++) {
            p.set(x, floor, 11, Material.SPRUCE_PLANKS);
            for (int z : new int[] {10, 12}) {
                p.log(x, floor, z, Material.STRIPPED_DARK_OAK_LOG, Axis.X);
                p.set(x, floor + 1, z, Material.DARK_OAK_FENCE);
            }
            p.set(x, floor - 1, 11, Blocks.stairsTop(Material.SPRUCE_STAIRS,
                    x < 54 ? BlockFace.WEST : BlockFace.EAST));
        }
        p.clearBox(51, floor + 1, 11, 51, floor + 2, 11);
        p.clearBox(56, floor + 1, 11, 56, floor + 2, 11);
        p.set(53, floor - 1, 11, Blocks.lantern(false, true));
    }

    /** Gardens, the churchyard, the orchard and the field outside the gate, with carts, hay and wood as cover. */
    private static void yards(TownPainter p) {
        // north: a garden by the gate tower, barrels and wood along the east road
        TownProps.garden(p, 37, 5, 41, 10, BlockFace.EAST);
        TownProps.barrels(p, 72, 4);
        TownProps.barrels(p, 82, 13);
        tavernBalcony(p);
        laneBridge(p);
        TownProps.woodPile(p, 92, 6, Axis.Z);
        TownProps.hayPile(p, 92, 13);
        TownProps.bush(p, 54, 4, Material.OAK_LEAVES);
        // east: fuel for the smithy, hay by the east street, a garden behind the bell tower
        TownProps.woodPile(p, 78, 17, Axis.Z);
        TownProps.hayPile(p, 83, 24);
        TownProps.garden(p, 72, 43, 78, 48, BlockFace.WEST);
        TownProps.wallFragment(p, 74, 50, 4, Axis.X, 3, TownPainter.STONE_BRICK);
        TownProps.bush(p, 88, 34, Material.AZALEA_LEAVES);
        // at the foot of the chapel terrace: an old yew with a ladder (up to the terrace edge) and a low wall
        TownProps.tree(p, 48, 50, Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES, BlockFace.EAST);
        TownProps.fieldWall(p, 45, 45, 48, 45);
        // a birch in the garden behind the bell tower, bushes in the ruin beside the chapel and in the orchard
        TownProps.tree(p, 75, 45, Material.BIRCH_LOG, Material.BIRCH_LEAVES, BlockFace.SOUTH);
        TownProps.bush(p, 70, 54, Material.OAK_LEAVES);
        TownProps.bush(p, 70, 59, Material.AZALEA_LEAVES);
        TownProps.bush(p, 66, 63, Material.FLOWERING_AZALEA_LEAVES);
        // south tip: hay and a cart by the barn
        TownProps.hayPile(p, 47, 72);
        TownProps.cart(p, 52, 75, BlockFace.WEST, Material.HAY_BLOCK);
        TownProps.bush(p, 58, 76, Material.OAK_LEAVES);
        // west: a garden by the south-west lane, hay between the town wall and the west house
        TownProps.garden(p, 34, 48, 39, 53, BlockFace.EAST);
        TownProps.hayPile(p, 39, 33);
        TownProps.barrels(p, 50, 37);
        // outside the gate: a grassy knoll with boulders, broken walls, an abandoned cart and bushes
        TownProps.mound(p, 29, 35, 4.5, 4, 2);
        p.set(29, TownPainter.GROUND + 3, 35, Material.MOSSY_COBBLESTONE);
        p.set(30, TownPainter.GROUND + 3, 35, Blocks.slab(Material.ANDESITE_SLAB));
        TownProps.boulder(p, 28, 39);
        TownProps.wallFragment(p, 28, 29, 6, Axis.X, 3, TownPainter.COBBLE);
        TownProps.wallFragment(p, 30, 42, 4, Axis.X, 2, TownPainter.STONE_BRICK);
        TownProps.fieldWall(p, 24, 33, 21, 29);
        TownProps.cart(p, 31, 23, BlockFace.NORTH, Material.BARREL);
        TownProps.bush(p, 26, 43, Material.AZALEA_LEAVES);
        TownProps.bush(p, 36, 8, Material.OAK_LEAVES);
    }

    /** The town's houses, north row first. */
    static List<TownHouse> houses() {
        return List.of(
                // north row, along the east road
                new TownHouse("gatehouse-north", 43, 7, 51, 15, TownHouse.DARK_OAK_PLASTER).ridgeAlongX(true)
                        .door(BlockFace.SOUTH, 4).door(BlockFace.EAST, 4)
                        .stairs(45, 8, BlockFace.EAST).roofLadder(BlockFace.NORTH, 2),
                new TownHouse("weaver", 56, 6, 63, 13, TownHouse.SPRUCE_MUD).state(State.COLLAPSED)
                        .collapseLowEnd().door(BlockFace.SOUTH, 3).door(BlockFace.WEST, 4)
                        .stairs(62, 8, BlockFace.SOUTH),
                new TownHouse("tavern", 69, 6, 79, 15, TownHouse.CLAY).ridgeAlongX(true)
                        .door(BlockFace.SOUTH, 5).door(BlockFace.WEST, 5).door(BlockFace.EAST, 4)
                        .stairs(71, 7, BlockFace.EAST).roofLadder(BlockFace.SOUTH, 8).chimney()
                        .interior(TownHouse.Interior.TAVERN),
                new TownHouse("cottage", 84, 5, 90, 11, TownHouse.SLATE).stories(1)
                        .door(BlockFace.WEST, 3).door(BlockFace.SOUTH, 3).roofLadder(BlockFace.EAST, 2),
                new TownHouse("east-cottage", 82, 16, 88, 22, TownHouse.SLATE)
                        .door(BlockFace.WEST, 3).door(BlockFace.SOUTH, 2).stairs(87, 17, BlockFace.SOUTH)
                        .roofLadder(BlockFace.EAST, 5),
                // east of the market
                new TownHouse("smithy", 71, 19, 77, 25, TownHouse.STONE).stories(1)
                        .openSide(BlockFace.WEST).door(BlockFace.EAST, 3).roofLadder(BlockFace.EAST, 1)
                        .interior(TownHouse.Interior.SMITHY),
                new TownHouse("east-house", 72, 33, 79, 41, TownHouse.DARK_OAK_PLASTER).state(State.COLLAPSED)
                        .door(BlockFace.NORTH, 3).door(BlockFace.WEST, 4).stairs(78, 35, BlockFace.SOUTH)
                        .roofLadder(BlockFace.WEST, 2),
                new TownHouse("east-ruin", 81, 31, 86, 36, TownHouse.SPRUCE_MUD).state(State.SHELL)
                        .door(BlockFace.WEST, 3).door(BlockFace.NORTH, 2),
                // west, between the gate and the south-west lane
                new TownHouse("west-house", 41, 29, 48, 37, TownHouse.SPRUCE_MUD)
                        .door(BlockFace.NORTH, 3).door(BlockFace.EAST, 5).stairs(42, 31, BlockFace.SOUTH)
                        .roofLadder(BlockFace.WEST, 6).chimney(),
                new TownHouse("west-cottage", 35, 40, 41, 47, TownHouse.SLATE).stories(1).state(State.COLLAPSED)
                        .door(BlockFace.EAST, 3).door(BlockFace.NORTH, 3),
                // south, around the chapel
                new TownHouse("chapel-ruin", 68, 51, 73, 56, TownHouse.CLAY).state(State.SHELL)
                        .door(BlockFace.WEST, 3).door(BlockFace.SOUTH, 3),
                new TownHouse("south-house", 42, 57, 48, 64, TownHouse.CLAY).state(State.COLLAPSED)
                        .door(BlockFace.NORTH, 3).door(BlockFace.EAST, 4).stairs(43, 59, BlockFace.SOUTH),
                new TownHouse("orchard-cottage", 58, 62, 63, 68, TownHouse.DARK_OAK_PLASTER).stories(1)
                        .door(BlockFace.WEST, 3).door(BlockFace.EAST, 4).roofLadder(BlockFace.EAST, 2),
                new TownHouse("barn", 49, 66, 57, 73, TownHouse.BARN).ridgeAlongX(false)
                        .wideDoor(BlockFace.NORTH, 3, 3).wideDoor(BlockFace.SOUTH, 3, 3)
                        .stairs(50, 68, BlockFace.SOUTH).roofLadder(BlockFace.EAST, 2)
                        .interior(TownHouse.Interior.BARN));
    }
}
