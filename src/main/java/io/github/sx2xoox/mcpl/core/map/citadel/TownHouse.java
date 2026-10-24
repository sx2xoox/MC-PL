package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.citadel.TownPainter.Palette;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * One house of the ruined town: a rectangle of outer walls ({@code x0..x1, z0..z1}, inclusive) with a stone ground
 * floor, an optional timber-framed upper floor and a gable roof, in one of three states of decay.
 *
 * <p>Heights (ground top {@code G = 64}): ground floor walls {@code 65..68}, the upper floor's boards at {@code 69}
 * (players stand at 70), upper walls {@code 70..72}, the roof from the wall top. Every enterable room has at least two
 * ways out (doors, windows with a sill to jump through, stairs), and every roof can be reached (an outside ladder or a
 * crumbled wall leading up to it), so rooftops are part of the fight.
 */
final class TownHouse {

    /** How much of the house still stands. */
    enum State {
        /** Whole roof, reached by a ladder outside. */
        INTACT,
        /** One end of the roof and upper floor fell in; a crumbled wall leads up to what is left of the roof. */
        COLLAPSED,
        /** Only broken ground-floor walls are left, overgrown inside. */
        SHELL
    }

    /** What the ground floor is furnished as. */
    enum Interior { HOME, TAVERN, SMITHY, BARN }

    /** Materials of a house. */
    record Style(Palette ground, Material corner, Material post, Material beam, Palette plaster, Material roofStairs,
                 Material roofPatch, Material roofSlab, Material roofBlock, Material floor, Material upperFloor,
                 Material door, Material stairs, Material fence, Material trapdoor) {
    }

    static final Style DARK_OAK_PLASTER = new Style(TownPainter.COBBLE, Material.DARK_OAK_LOG, Material.DARK_OAK_LOG,
            Material.STRIPPED_DARK_OAK_LOG, Palette.of(Material.CALCITE, Material.CALCITE, Material.WHITE_TERRACOTTA,
            Material.CALCITE, Material.MUD_BRICKS), Material.DARK_OAK_STAIRS, Material.SPRUCE_STAIRS,
            Material.DARK_OAK_SLAB, Material.DARK_OAK_PLANKS, Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS,
            Material.SPRUCE_DOOR, Material.SPRUCE_STAIRS, Material.DARK_OAK_FENCE, Material.SPRUCE_TRAPDOOR);

    static final Style SPRUCE_MUD = new Style(TownPainter.STONE_BRICK, Material.SPRUCE_LOG, Material.SPRUCE_LOG,
            Material.STRIPPED_SPRUCE_LOG, Palette.of(Material.PACKED_MUD, Material.PACKED_MUD, Material.MUD_BRICKS,
            Material.PACKED_MUD), Material.SPRUCE_STAIRS, Material.DARK_OAK_STAIRS, Material.SPRUCE_SLAB,
            Material.SPRUCE_PLANKS, Material.OAK_PLANKS, Material.OAK_PLANKS, Material.OAK_DOOR, Material.OAK_STAIRS,
            Material.SPRUCE_FENCE, Material.OAK_TRAPDOOR);

    static final Style SLATE = new Style(TownPainter.COBBLE, Material.OAK_LOG, Material.OAK_LOG,
            Material.STRIPPED_OAK_LOG, Palette.of(Material.WHITE_TERRACOTTA, Material.CALCITE,
            Material.WHITE_TERRACOTTA), Material.DEEPSLATE_TILE_STAIRS, Material.COBBLED_DEEPSLATE_STAIRS,
            Material.DEEPSLATE_TILE_SLAB, Material.DEEPSLATE_TILES, Material.DARK_OAK_PLANKS, Material.SPRUCE_PLANKS,
            Material.DARK_OAK_DOOR, Material.DARK_OAK_STAIRS, Material.SPRUCE_FENCE, Material.DARK_OAK_TRAPDOOR);

    static final Style CLAY = new Style(TownPainter.STONE_BRICK, Material.STRIPPED_SPRUCE_LOG, Material.SPRUCE_LOG,
            Material.SPRUCE_LOG, Palette.of(Material.CALCITE, Material.WHITE_TERRACOTTA, Material.CALCITE),
            Material.MUD_BRICK_STAIRS, Material.BRICK_STAIRS, Material.MUD_BRICK_SLAB, Material.MUD_BRICKS,
            Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS, Material.SPRUCE_DOOR, Material.SPRUCE_STAIRS,
            Material.SPRUCE_FENCE, Material.SPRUCE_TRAPDOOR);

    static final Style BARN = new Style(Palette.of(Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS,
            Material.STRIPPED_SPRUCE_WOOD, Material.SPRUCE_PLANKS, Material.DARK_OAK_PLANKS), Material.SPRUCE_LOG,
            Material.SPRUCE_LOG, Material.STRIPPED_SPRUCE_LOG, Palette.of(Material.SPRUCE_PLANKS,
            Material.DARK_OAK_PLANKS, Material.SPRUCE_PLANKS), Material.DARK_OAK_STAIRS, Material.SPRUCE_STAIRS,
            Material.DARK_OAK_SLAB, Material.DARK_OAK_PLANKS, Material.COARSE_DIRT, Material.SPRUCE_PLANKS,
            Material.SPRUCE_DOOR, Material.SPRUCE_STAIRS, Material.SPRUCE_FENCE, Material.SPRUCE_TRAPDOOR);

    static final Style STONE = new Style(TownPainter.STONE_BRICK, Material.CHISELED_STONE_BRICKS,
            Material.STONE_BRICKS, Material.POLISHED_ANDESITE, TownPainter.STONE_BRICK,
            Material.COBBLED_DEEPSLATE_STAIRS, Material.DEEPSLATE_TILE_STAIRS, Material.COBBLED_DEEPSLATE_SLAB,
            Material.COBBLED_DEEPSLATE, Material.COBBLESTONE, Material.SPRUCE_PLANKS, Material.SPRUCE_DOOR,
            Material.STONE_BRICK_STAIRS, Material.SPRUCE_FENCE, Material.SPRUCE_TRAPDOOR);

    private static final int G = TownPainter.GROUND;
    /** Top row of the ground floor walls. */
    private static final int GROUND_TOP = G + 4;
    /** The upper floor's boards. */
    private static final int UPPER_FLOOR = G + 5;
    /** Top row of the upper floor walls. */
    private static final int UPPER_TOP = G + 8;

    private final String name;
    private final int x0;
    private final int z0;
    private final int x1;
    private final int z1;
    private final Style style;
    private int stories = 2;
    private State state = State.INTACT;
    private boolean ridgeAlongX;
    private final List<int[]> doors = new ArrayList<>();
    private int[] stairs;
    private int[] roofLadder;
    private boolean collapseLow;
    private boolean chimney;
    private Interior interior = Interior.HOME;
    private final List<int[]> wideDoors = new ArrayList<>();
    private BlockFace openSide;

    /** A house with outer walls on {@code x0..x1, z0..z1}. */
    TownHouse(String name, int x0, int z0, int x1, int z1, Style style) {
        this.name = name;
        this.x0 = Math.min(x0, x1);
        this.z0 = Math.min(z0, z1);
        this.x1 = Math.max(x0, x1);
        this.z1 = Math.max(z0, z1);
        this.style = style;
        this.ridgeAlongX = this.x1 - this.x0 > this.z1 - this.z0;
    }

    TownHouse stories(int stories) {
        this.stories = stories;
        return this;
    }

    TownHouse state(State state) {
        this.state = state;
        return this;
    }

    TownHouse ridgeAlongX(boolean alongX) {
        this.ridgeAlongX = alongX;
        return this;
    }

    /** A door in the wall on {@code side}, {@code offset} blocks from the wall's west or north end. */
    TownHouse door(BlockFace side, int offset) {
        doors.add(new int[] {side.ordinal(), offset});
        return this;
    }

    /** The stair to the upper floor: its lowest step at {@code x,z}, climbing towards {@code dir} (4 steps). */
    TownHouse stairs(int x, int z, BlockFace dir) {
        stairs = new int[] {x, z, dir.ordinal()};
        return this;
    }

    /** The outside ladder to the roof, on an eave side ({@code side}), {@code offset} blocks along the wall. */
    TownHouse roofLadder(BlockFace side, int offset) {
        roofLadder = new int[] {side.ordinal(), offset};
        return this;
    }

    /** For {@link State#COLLAPSED}: the low end (west or north) fell in instead of the high end. */
    TownHouse collapseLowEnd() {
        this.collapseLow = true;
        return this;
    }

    TownHouse chimney() {
        this.chimney = true;
        return this;
    }

    TownHouse interior(Interior interior) {
        this.interior = interior;
        return this;
    }

    /** A doorway {@code width} wide and three high (a barn or workshop door), {@code offset} from the wall's end. */
    TownHouse wideDoor(BlockFace side, int offset, int width) {
        wideDoors.add(new int[] {side.ordinal(), offset, width});
        return this;
    }

    /** The ground floor wall on {@code side} is only posts: an open workshop front. */
    TownHouse openSide(BlockFace side) {
        this.openSide = side;
        return this;
    }

    @Override
    public String toString() {
        return name + " (" + x0 + "," + z0 + ".." + x1 + "," + z1 + ")";
    }

    // ------------------------------------------------------------------ build

    void build(TownPainter p) {
        Random random = p.random();
        foundation(p);
        if (state == State.SHELL) {
            shellWalls(p);
            shellInterior(p);
            return;
        }
        groundWalls(p);
        int wallTop = GROUND_TOP;
        if (stories >= 2) {
            upperFloor(p);
            upperWalls(p);
            wallTop = UPPER_TOP;
        }
        roof(p, wallTop);
        openings(p);
        if (state == State.COLLAPSED) {
            collapse(p, wallTop);
        } else {
            roofHoles(p, wallTop);
        }
        if (stairs != null && stories >= 2) {
            interiorStairs(p);
        }
        if (roofLadder != null) {
            roofLadder(p, wallTop);
        }
        if (chimney) {
            chimney(p, wallTop);
        }
        furnish(p, random);
        weather(p, wallTop);
    }

    // ------------------------------------------------------------------ walls

    private void foundation(TownPainter p) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                if (wall) {
                    p.set(x, G, z, TownPainter.COBBLE.next(p.random()));
                } else if (state == State.SHELL) {
                    p.set(x, G, z, p.chance(0.55) ? BlockSpec.of(style.floor())
                            : p.pick(Material.COARSE_DIRT, Material.GRASS_BLOCK, Material.MOSS_BLOCK,
                            Material.COBBLESTONE));
                } else {
                    p.set(x, G, z, p.chance(0.92) ? BlockSpec.of(style.floor()) : TownPainter.COBBLE.next(p.random()));
                }
            }
        }
    }

    private boolean isCorner(int x, int z) {
        return (x == x0 || x == x1) && (z == z0 || z == z1);
    }

    private void groundWalls(TownPainter p) {
        for (int y = G + 1; y <= GROUND_TOP; y++) {
            for (int[] c : perimeter()) {
                p.set(c[0], y, c[1], isCorner(c[0], c[1]) ? BlockSpec.of(style.corner()).axis(Axis.Y)
                        : style.ground().next(p.random()));
            }
        }
    }

    private void upperFloor(TownPainter p) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean wallX = x == x0 || x == x1;
                boolean wallZ = z == z0 || z == z1;
                if (wallX && wallZ) {
                    p.set(x, UPPER_FLOOR, z, BlockSpec.of(style.corner()).axis(Axis.Y));
                } else if (wallX) {
                    p.log(x, UPPER_FLOOR, z, style.beam(), Axis.Z);
                } else if (wallZ) {
                    p.log(x, UPPER_FLOOR, z, style.beam(), Axis.X);
                } else {
                    p.set(x, UPPER_FLOOR, z, style.upperFloor());
                }
            }
        }
        // a lantern under the boards in the middle of the ground floor
        p.set((x0 + x1) / 2, UPPER_FLOOR - 1, (z0 + z1) / 2 - 1, Blocks.lantern(false, true));
    }

    private void upperWalls(TownPainter p) {
        for (int[] c : perimeter()) {
            int x = c[0];
            int z = c[1];
            boolean post = isCorner(x, z) || ((x == x0 || x == x1) ? (z - z0) % 3 == 0 : (x - x0) % 3 == 0);
            for (int y = UPPER_FLOOR + 1; y <= UPPER_TOP; y++) {
                if (post) {
                    p.set(x, y, z, BlockSpec.of(style.post()).axis(Axis.Y));
                } else if (y == UPPER_TOP) {
                    p.log(x, y, z, style.beam(), x == x0 || x == x1 ? Axis.Z : Axis.X);
                } else {
                    p.set(x, y, z, style.plaster().next(p.random()));
                }
            }
        }
    }

    /** Doors and windows. */
    private void openings(TownPainter p) {
        for (int[] wide : wideDoors) {
            BlockFace side = BlockFace.values()[wide[0]];
            for (int i = 0; i < wide[2]; i++) {
                int[] c = wallCell(side, wide[1] + i);
                p.clearBox(c[0], G + 1, c[1], c[0], G + 3, c[1]);
            }
        }
        if (openSide != null) {
            int length = openSide == BlockFace.NORTH || openSide == BlockFace.SOUTH ? x1 - x0 : z1 - z0;
            for (int o = 1; o < length; o++) {
                int[] c = wallCell(openSide, o);
                if (o % 3 == 0) {
                    p.fill(c[0], G + 1, c[1], c[0], GROUND_TOP, c[1], BlockSpec.of(style.corner()).axis(Axis.Y));
                } else {
                    p.clearBox(c[0], G + 1, c[1], c[0], G + 3, c[1]);
                    p.set(c[0], GROUND_TOP, c[1], Blocks.stairsTop(style.stairs(), openSide.getOppositeFace()));
                }
            }
        }
        for (int[] door : doors) {
            BlockFace side = BlockFace.values()[door[0]];
            int[] c = wallCell(side, door[1]);
            p.clear(c[0], G + 2, c[1]);
            if (p.chance(0.2)) {
                // a broken doorway: no door left
                p.clear(c[0], G + 1, c[1]);
            } else {
                p.door(c[0], G + 1, c[1], style.door(), side, p.chance(0.4));
            }
            // a torch on the wall beside the door
            int[] out = step(side);
            int[] along = along(side);
            if (p.chance(0.5) && p.isSolid(c[0] + along[0], G + 3, c[1] + along[1])) {
                p.setIfAir(c[0] + out[0] + along[0], G + 3, c[1] + out[1] + along[1],
                        Blocks.wallTorch(false, side));
            }
        }
        BlockFace[] sides = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};
        int windows = 0;
        for (BlockFace side : sides) {
            int length = side == BlockFace.NORTH || side == BlockFace.SOUTH ? x1 - x0 : z1 - z0;
            for (int o = 2; o <= length - 2; o += 3) {
                int[] c = wallCell(side, o);
                if (nearDoor(side, o) || nearLadder(side, o)) {
                    continue;
                }
                // ground floor window: sill at G+1, opening G+2..G+3
                if (p.isSolid(c[0], G + 2, c[1]) && !isStairWall(c[0], c[1])) {
                    windows++;
                    // most windows are open (to shoot and climb through); every fourth still has glass, some of
                    // it broken
                    boolean glazed = windows % 4 == 3;
                    p.set(c[0], G + 2, c[1], glazed && p.chance(0.5) ? BlockSpec.of(Material.GLASS_PANE)
                            : BlockSpec.AIR);
                    p.set(c[0], G + 3, c[1], glazed ? BlockSpec.of(Material.GLASS_PANE) : BlockSpec.AIR);
                    if (!glazed && p.chance(0.3)) {
                        flowerBox(p, side, o);
                    }
                }
                if (stories >= 2 && p.isSolid(c[0], UPPER_FLOOR + 1, c[1]) && !isStairWall(c[0], c[1])) {
                    windows++;
                    boolean glazed = windows % 4 == 3;
                    p.set(c[0], UPPER_FLOOR + 1, c[1], BlockSpec.AIR);
                    p.set(c[0], UPPER_FLOOR + 2, c[1], glazed ? BlockSpec.of(Material.GLASS_PANE) : BlockSpec.AIR);
                }
            }
        }
    }

    /** A flower box (a trapdoor with flowers) under a ground-floor window. */
    private void flowerBox(TownPainter p, BlockFace side, int offset) {
        int[] out = step(side);
        int[] c = wallCell(side, offset);
        int bx = c[0] + out[0];
        int bz = c[1] + out[1];
        if (!p.isSolid(bx, G + 1, bz) && !p.isSolid(bx, G + 2, bz)) {
            p.set(bx, G + 1, bz, Blocks.trapdoor(style.trapdoor(), side.getOppositeFace(), true, false));
            p.set(bx, G + 2, bz, p.pick(Material.POPPY, Material.DANDELION, Material.CORNFLOWER,
                    Material.AZURE_BLUET, Material.RED_TULIP, Material.ALLIUM));
        }
    }

    private boolean nearDoor(BlockFace side, int offset) {
        for (int[] door : doors) {
            if (door[0] == side.ordinal() && Math.abs(door[1] - offset) <= 1) {
                return true;
            }
        }
        for (int[] wide : wideDoors) {
            if (wide[0] == side.ordinal() && offset >= wide[1] - 1 && offset <= wide[1] + wide[2]) {
                return true;
            }
        }
        return side == openSide;
    }

    private boolean nearLadder(BlockFace side, int offset) {
        return roofLadder != null && roofLadder[0] == side.ordinal() && Math.abs(roofLadder[1] - offset) <= 1;
    }

    /** True for wall cells the interior stair runs along (no windows there). */
    private boolean isStairWall(int x, int z) {
        if (stairs == null) {
            return false;
        }
        BlockFace dir = BlockFace.values()[stairs[2]];
        for (int i = -1; i <= 4; i++) {
            int sx = stairs[0] + dir.getModX() * i;
            int sz = stairs[1] + dir.getModZ() * i;
            if (Math.abs(sx - x) + Math.abs(sz - z) <= 1) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ roof

    /** Roof height above the eave for a column (0 at the overhang), and whether it is the ridge. */
    private int roofRise(int x, int z) {
        return ridgeAlongX ? Math.min(z - (z0 - 1), (z1 + 1) - z) : Math.min(x - (x0 - 1), (x1 + 1) - x);
    }

    private int span() {
        return ridgeAlongX ? (z1 - z0 + 3) : (x1 - x0 + 3);
    }

    private int maxRise() {
        return (span() - 1) / 2;
    }

    /** True if the column is the single middle column of an odd-width roof (capped with a slab). */
    private boolean isRidgeCap(int x, int z) {
        return span() % 2 == 1 && roofRise(x, z) == maxRise();
    }

    /** y of the lowest roof block in a column (walls and gables stop below it). */
    private int roofUnderside(int x, int z, int wallTop) {
        int y = wallTop + roofRise(x, z);
        return isRidgeCap(x, z) ? y - 1 : y;
    }

    private void roof(TownPainter p, int wallTop) {
        Random random = p.random();
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                int y = wallTop + roofRise(x, z);
                Material stairs = random.nextDouble() < 0.12 ? style.roofPatch() : style.roofStairs();
                if (isRidgeCap(x, z)) {
                    p.set(x, y - 1, z, style.roofBlock());
                    p.set(x, y, z, Blocks.slab(style.roofSlab()));
                } else {
                    BlockFace facing;
                    if (ridgeAlongX) {
                        facing = z < (z0 + z1) / 2.0 ? BlockFace.SOUTH : BlockFace.NORTH;
                    } else {
                        facing = x < (x0 + x1) / 2.0 ? BlockFace.EAST : BlockFace.WEST;
                    }
                    p.set(x, y, z, Blocks.stairs(stairs, facing));
                }
            }
        }
        // gables
        int[][] gableRows = ridgeAlongX ? new int[][] {{x0}, {x1}} : new int[][] {{z0}, {z1}};
        for (int[] row : gableRows) {
            for (int i = ridgeAlongX ? z0 : x0; i <= (ridgeAlongX ? z1 : x1); i++) {
                int x = ridgeAlongX ? row[0] : i;
                int z = ridgeAlongX ? i : row[0];
                int top = roofUnderside(x, z, wallTop) - 1;
                for (int y = wallTop + 1; y <= top; y++) {
                    boolean mid = ridgeAlongX ? z == (z0 + z1) / 2 : x == (x0 + x1) / 2;
                    if (mid && y == wallTop + 2 && top >= wallTop + 3) {
                        p.clear(x, y, z); // a small gable window
                    } else {
                        p.set(x, y, z, stories >= 2 ? style.plaster().next(random)
                                : BlockSpec.of(style.roofBlock()));
                    }
                }
            }
        }
    }

    /** One or two small holes where tiles slipped off, away from where the ladder arrives. */
    private void roofHoles(TownPainter p, int wallTop) {
        int holes = 1 + p.random().nextInt(2);
        for (int attempt = 0; attempt < 12 && holes > 0; attempt++) {
            int x = p.between(x0 + 1, x1 - 1);
            int z = p.between(z0 + 1, z1 - 1);
            int rise = roofRise(x, z);
            if (rise < 2 || rise > maxRise() - 1 || isRidgeCap(x, z) || nearRoofLadder(x, z)) {
                continue;
            }
            int y = wallTop + rise;
            p.clear(x, y, z);
            int nx = x + (ridgeAlongX ? 1 : 0);
            int nz = z + (ridgeAlongX ? 0 : 1);
            if (p.chance(0.6) && !nearRoofLadder(nx, nz) && roofRise(nx, nz) == rise) {
                p.clear(nx, y, nz);
            }
            holes--;
        }
    }

    private boolean nearRoofLadder(int x, int z) {
        if (roofLadder == null) {
            return false;
        }
        int[] c = wallCell(BlockFace.values()[roofLadder[0]], roofLadder[1]);
        return Math.abs(c[0] - x) + Math.abs(c[1] - z) <= 3;
    }

    private void roofLadder(TownPainter p, int wallTop) {
        BlockFace side = BlockFace.values()[roofLadder[0]];
        int[] c = wallCell(side, roofLadder[1]);
        int[] out = step(side);
        int lx = c[0] + out[0];
        int lz = c[1] + out[1];
        for (int y = G + 1; y <= wallTop + 1; y++) {
            p.set(lx, y, lz, BlockSpec.of(Material.LADDER).facing(side));
        }
        // no window behind the ladder
        for (int y = G + 1; y <= wallTop; y++) {
            if (!p.isSolid(c[0], y, c[1])) {
                p.set(c[0], y, c[1], y == UPPER_FLOOR && stories >= 2 ? Blocks.log(style.beam(),
                        side == BlockFace.NORTH || side == BlockFace.SOUTH ? Axis.X : Axis.Z)
                        : style.ground().next(p.random()));
            }
        }
    }

    /**
     * A stone chimney stack against the outside of a gable wall, up through the roof's overhang, with a smoking
     * campfire on top.
     */
    private void chimney(TownPainter p, int wallTop) {
        int cx = ridgeAlongX ? x1 + 1 : x0 + 2;
        int cz = ridgeAlongX ? z0 + 2 : z1 + 1;
        int top = wallTop + roofRise(cx, cz) + 2;
        for (int y = G + 1; y <= top; y++) {
            p.set(cx, y, cz, y <= G + 2 ? BlockSpec.of(Material.BRICKS)
                    : p.pick(Material.COBBLESTONE, Material.STONE_BRICKS, Material.MOSSY_COBBLESTONE));
        }
        p.set(cx, top + 1, cz, Blocks.campfire(true));
    }

    // ------------------------------------------------------------------ stairs

    private void interiorStairs(TownPainter p) {
        BlockFace dir = BlockFace.values()[stairs[2]];
        int dx = dir.getModX();
        int dz = dir.getModZ();
        for (int i = 0; i < 4; i++) {
            int x = stairs[0] + dx * i;
            int z = stairs[1] + dz * i;
            int y = G + 1 + i;
            if (y > G + 1) {
                p.fill(x, G + 1, z, x, y - 1, z, BlockSpec.of(style.upperFloor()));
            }
            p.set(x, y, z, Blocks.stairs(style.stairs(), dir));
            // the opening in the upper floor above the steps
            p.clearBox(x, y + 1, z, x, y + 3, z);
        }
        // landing
        int lx = stairs[0] + dx * 4;
        int lz = stairs[1] + dz * 4;
        p.set(lx, UPPER_FLOOR, lz, style.upperFloor());
        p.clearBox(lx, UPPER_FLOOR + 1, lz, lx, UPPER_FLOOR + 2, lz);
        // the rail: on the side of the stair away from the nearer wall
        int[] side = {-dz, dx};
        int sx = stairs[0] + side[0];
        int sz = stairs[1] + side[1];
        if (!inside(sx, sz)) {
            side = new int[] {dz, -dx};
        }
        for (int i = 0; i < 3; i++) {
            int x = stairs[0] + dx * i + side[0];
            int z = stairs[1] + dz * i + side[1];
            if (inside(x, z)) {
                p.set(x, UPPER_FLOOR + 1, z, style.fence());
            }
        }
    }

    /** True for interior columns (inside the walls). */
    private boolean inside(int x, int z) {
        return x > x0 && x < x1 && z > z0 && z < z1;
    }

    // ------------------------------------------------------------------ decay

    /**
     * One end of the roof falls in: no roof, gable or upper walls there, the side walls crumble down in steps (a ramp
     * from the upper floor to the wall top next to the remaining roof), holes in the boards, rubble on the floor.
     */
    private void collapse(TownPainter p, int wallTop) {
        int length = ridgeAlongX ? x1 - x0 : z1 - z0;
        int keep = Math.max(3, length / 2);
        // first collapsed row along the ridge axis (b) and the direction towards the fallen end (sign)
        int sign = collapseLow ? -1 : 1;
        int b = collapseLow ? (ridgeAlongX ? x1 : z1) - keep : (ridgeAlongX ? x0 : z0) + keep;
        int end = collapseLow ? (ridgeAlongX ? x0 : z0) - 1 : (ridgeAlongX ? x1 : z1) + 1;
        int lo = Math.min(b, end);
        int hi = Math.max(b, end);
        for (int a = lo; a <= hi; a++) {
            int stepIndex = (a - b) * sign; // 0 at the boundary, growing towards the fallen end
            for (int c = (ridgeAlongX ? z0 : x0) - 1; c <= (ridgeAlongX ? z1 : x1) + 1; c++) {
                int x = ridgeAlongX ? a : c;
                int z = ridgeAlongX ? c : a;
                // the roof and anything above the wall top
                p.clearBox(x, wallTop + 1, z, x, wallTop + maxRise() + 1, z);
                if (!ridgeAlongX ? (x < x0 || x > x1) : (z < z0 || z > z1)) {
                    p.clear(x, wallTop, z); // eave overhang
                    continue;
                }
                int floorTop = stories >= 2 ? UPPER_FLOOR : G + 1;
                boolean sideWall = ridgeAlongX ? (z == z0 || z == z1) : (x == x0 || x == x1);
                boolean endWall = ridgeAlongX ? (x == (collapseLow ? x0 : x1)) : (z == (collapseLow ? z0 : z1));
                if (sideWall && !endWall) {
                    // crumble in steps: full at the boundary, then one lower per row (a ramp to the roof)
                    int top = Math.max(floorTop, wallTop - stepIndex);
                    for (int y = floorTop + 1; y <= top; y++) {
                        if (!p.isSolid(x, y, z)) {
                            p.set(x, y, z, (stories >= 2 ? style.plaster() : style.ground()).next(p.random()));
                        }
                    }
                    if (top < wallTop) {
                        p.clearBox(x, top + 1, z, x, wallTop, z);
                    }
                } else if (endWall) {
                    int stub = (c == (ridgeAlongX ? z0 : x0) || c == (ridgeAlongX ? z1 : x1)) ? floorTop + 1
                            : floorTop;
                    p.clearBox(x, stub + 1, z, x, wallTop, z);
                    if (stub == floorTop && p.chance(0.3)) {
                        p.set(x, stub + 1, z, Blocks.leaves(Material.AZALEA_LEAVES)); // overgrown wall top
                    }
                } else if (stories >= 2 && stepIndex >= 2 && p.chance(0.22)) {
                    p.clear(x, UPPER_FLOOR, z); // holes in the boards
                }
            }
        }
        // rubble from the fallen roof
        int ca = (b + end) / 2;
        int cc = ridgeAlongX ? (z0 + z1) / 2 : (x0 + x1) / 2;
        int cx = ridgeAlongX ? ca : cc;
        int cz = ridgeAlongX ? cc : ca;
        if (stories >= 2) {
            p.rubble(cx, cz, UPPER_FLOOR + 1, 1.6, 2, TownPainter.RUBBLE);
            // a fallen beam across the boards
            Axis axis = ridgeAlongX ? Axis.Z : Axis.X;
            for (int i = -1; i <= 1; i++) {
                int x = cx + (axis == Axis.X ? i : 0) + (axis == Axis.Z ? 1 : 0);
                int z = cz + (axis == Axis.Z ? i : 0) + (axis == Axis.X ? 1 : 0);
                if (inside(x, z) && !p.isSolid(x, UPPER_FLOOR + 1, z)) {
                    p.log(x, UPPER_FLOOR + 1, z, style.beam(), axis);
                }
            }
        } else {
            p.rubble(cx, cz, G + 1, 2.0, 2, TownPainter.RUBBLE);
        }
    }

    /** Moss, cracks and missing blocks on the walls. */
    private void weather(TownPainter p, int wallTop) {
        Random random = p.random();
        for (int[] c : perimeter()) {
            int x = c[0];
            int z = c[1];
            if (random.nextDouble() < 0.06 && !isCorner(x, z)) {
                int y = p.between(G + 2, GROUND_TOP);
                if (p.isSolid(x, y, z) && p.isSolid(x, y + 1, z) && p.isSolid(x, y - 1, z)) {
                    p.clear(x, y, z);
                }
            }
        }
        // overgrowth at the foot of the walls
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                boolean ring = x == x0 - 1 || x == x1 + 1 || z == z0 - 1 || z == z1 + 1;
                if (!ring || p.isSolid(x, G + 1, z) || !p.owns(x, z) || besideDoor(x, z)) {
                    continue;
                }
                double roll = random.nextDouble();
                if (roll < 0.10) {
                    p.set(x, G + 1, z, Material.SHORT_GRASS);
                } else if (roll < 0.14) {
                    p.set(x, G + 1, z, Material.FERN);
                } else if (roll < 0.17) {
                    p.set(x, G + 1, z, Blocks.leaves(Material.AZALEA_LEAVES));
                } else if (roll < 0.19) {
                    p.set(x, G + 1, z, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
                }
            }
        }
        if (wallTop > GROUND_TOP) {
            // cobwebs high in two inner corners of the ground floor
            p.setIfAir(x0 + 1, GROUND_TOP, z0 + 1, BlockSpec.of(Material.COBWEB));
            p.setIfAir(x1 - 1, GROUND_TOP, z1 - 1, BlockSpec.of(Material.COBWEB));
        }
    }

    // ------------------------------------------------------------------ shells

    /** Broken ground-floor walls whose height changes by at most one block per step (climbable all along). */
    private void shellWalls(TownPainter p) {
        List<int[]> ring = perimeter();
        int n = ring.size();
        int[] heights = new int[n];
        double phase = p.random().nextDouble() * Math.PI * 2;
        for (int i = 0; i < n; i++) {
            double wave = Math.sin(phase + i * 2 * Math.PI / n * 2) * 1.6 + Math.sin(phase * 3 + i * 0.9) * 0.6;
            heights[i] = Math.max(0, Math.min(4, (int) Math.round(2 + wave)));
            for (int[] door : doors) {
                int[] c = wallCell(BlockFace.values()[door[0]], door[1]);
                if (c[0] == ring.get(i)[0] && c[1] == ring.get(i)[1]) {
                    heights[i] = 0;
                }
            }
        }
        // limit steps to one block
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 0; i < n; i++) {
                int prev = heights[(i + n - 1) % n];
                if (heights[i] > prev + 1) {
                    heights[i] = prev + 1;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            int x = ring.get(i)[0];
            int z = ring.get(i)[1];
            int h = heights[i];
            for (int y = G + 1; y <= G + h; y++) {
                p.set(x, y, z, isCorner(x, z) && y <= G + 3 ? BlockSpec.of(style.corner()).axis(Axis.Y)
                        : style.ground().next(p.random()));
            }
            boolean lowest = heights[(i + n - 1) % n] >= h && heights[(i + 1) % n] >= h;
            if (h > 0 && h < 4 && lowest && p.chance(0.4)) {
                p.set(x, G + h + 1, z, style.ground().slab(p.random()));
            }
        }
    }

    private void shellInterior(TownPainter p) {
        int cx = (x0 + x1) / 2;
        int cz = (z0 + z1) / 2;
        p.rubble(cx + p.between(-1, 1), cz + p.between(-1, 1), G + 1, 1.8, 2, TownPainter.RUBBLE);
        // fallen roof beams
        p.log(x0 + 1, G + 1, z1 - 2, style.beam(), Axis.X);
        p.log(x0 + 2, G + 1, z1 - 2, style.beam(), Axis.X);
        for (int x = x0 + 1; x < x1; x++) {
            for (int z = z0 + 1; z < z1; z++) {
                if (p.isSolid(x, G + 1, z)) {
                    continue;
                }
                double roll = p.random().nextDouble();
                if (roll < 0.18) {
                    p.set(x, G + 1, z, Material.SHORT_GRASS);
                } else if (roll < 0.24) {
                    p.set(x, G + 1, z, p.pick(Material.FERN, Material.POPPY, Material.DANDELION,
                            Material.OXEYE_DAISY));
                } else if (roll < 0.27) {
                    p.tallPlant(x, G + 1, z, Material.TALL_GRASS);
                }
            }
        }
    }

    // ------------------------------------------------------------------ furniture

    private void furnish(TownPainter p, Random random) {
        switch (interior) {
            case SMITHY -> furnishSmithy(p);
            case BARN -> furnishBarn(p, random);
            case TAVERN -> furnishTavern(p, random);
            default -> furnishHome(p, random);
        }
        if (stories >= 2 && state != State.SHELL) {
            int ux = x1 - 1;
            int uz = z1 - 1;
            if (!p.isSolid(ux, UPPER_FLOOR + 1, uz) && p.isSolid(ux, UPPER_FLOOR, uz) && !isStairWall(ux, uz)) {
                p.set(ux, UPPER_FLOOR + 1, uz, interior == Interior.BARN ? Blocks.log(Material.HAY_BLOCK, Axis.X)
                        : BlockSpec.of(Material.CHEST));
            }
            if (p.isSolid(x0 + 2, UPPER_FLOOR, z0 + 2) && !p.isSolid(x0 + 2, UPPER_FLOOR + 1, z0 + 2)
                    && !isStairWall(x0 + 2, z0 + 2)) {
                p.set(x0 + 2, UPPER_FLOOR + 1, z0 + 2, interior == Interior.BARN
                        ? Blocks.log(Material.HAY_BLOCK, Axis.Z) : p.pick(Material.RED_CARPET, Material.BROWN_CARPET,
                        Material.GREEN_CARPET, Material.BLUE_CARPET));
            }
        }
    }

    private void furnishHome(TownPainter p, Random random) {
        // barrels in a corner, a table with two chairs in the middle, a crafting table or furnace by a wall
        placeIfFree(p, x1 - 1, z0 + 1, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        if (random.nextBoolean()) {
            placeIfFree(p, x1 - 1, z0 + 2, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        }
        int tx = (x0 + x1) / 2;
        int tz = (z0 + z1) / 2 + 1;
        if (placeIfFree(p, tx, tz, BlockSpec.of(style.fence()))) {
            p.set(tx, G + 2, tz, BlockSpec.of(Material.OAK_PRESSURE_PLATE));
            placeIfFree(p, tx - 1, tz, Blocks.stairs(style.stairs(), BlockFace.WEST));
            placeIfFree(p, tx + 1, tz, Blocks.stairs(style.stairs(), BlockFace.EAST));
        }
        Material[] workshop = {Material.CRAFTING_TABLE, Material.FURNACE, Material.SMOKER, Material.BOOKSHELF,
                Material.LOOM, Material.CARTOGRAPHY_TABLE, Material.COMPOSTER};
        placeIfFree(p, x0 + 1, z1 - 1, BlockSpec.of(workshop[random.nextInt(workshop.length)]));
    }

    /** A forge in the back corner with its chimney, an anvil, a quenching cauldron and tools. */
    private void furnishSmithy(TownPainter p) {
        int fx = x1 - 2;
        int fz = z0 + 1;
        // the forge: a brick hearth two wide with a fire under a hood, the chimney up through the roof
        int top = GROUND_TOP + roofRise(fx + 1, fz) + 2;
        for (int x = fx; x <= fx + 1; x++) {
            p.set(x, G + 1, fz, Material.BRICKS);
            for (int y = G + 3; y <= top - (x == fx ? 1 : 0); y++) {
                p.set(x, y, fz, y % 3 == 0 ? Material.BRICKS : Material.COBBLESTONE);
            }
        }
        p.set(fx, G + 2, fz, Blocks.campfire(true));
        p.set(fx + 1, G + 2, fz, Material.BLAST_FURNACE);
        p.set(fx + 1, top + 1, fz, Blocks.campfire(true));
        placeIfFree(p, fx, fz + 2, BlockSpec.of(Material.ANVIL).facing(BlockFace.EAST));
        placeIfFree(p, x1 - 1, z1 - 1, BlockSpec.of(Material.WATER_CAULDRON));
        placeIfFree(p, x1 - 1, z1 - 2, BlockSpec.of(Material.SMITHING_TABLE));
        placeIfFree(p, x0 + 2, z1 - 1, BlockSpec.of(Material.GRINDSTONE).facing(BlockFace.NORTH));
        placeIfFree(p, x0 + 3, z1 - 1, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        hangLantern(p, (x0 + x1) / 2, (z0 + z1) / 2 + 1);
    }

    /** Hay stacked against the walls, stalls of fences, a cart wheel. */
    private void furnishBarn(TownPainter p, Random random) {
        for (int x = x0 + 1; x < x1; x++) {
            for (int z = z0 + 1; z < z1; z++) {
                boolean byWall = x == x0 + 1 || x == x1 - 1;
                if (!byWall || besideDoor(x, z) || isStairWall(x, z) || p.isSolid(x, G + 1, z)) {
                    continue;
                }
                if (random.nextDouble() < 0.55) {
                    p.set(x, G + 1, z, Blocks.log(Material.HAY_BLOCK, random.nextBoolean() ? Axis.X : Axis.Z));
                    // stacked two high only next to a single bale (a step up)
                    BlockSpec before = p.get(x, G + 1, z - 1);
                    boolean step = before != null && before.material() == Material.HAY_BLOCK
                            && !p.isSolid(x, G + 2, z - 1);
                    if (step && random.nextDouble() < 0.5) {
                        p.set(x, G + 2, z, Blocks.log(Material.HAY_BLOCK, Axis.Y));
                    }
                }
            }
        }
        placeIfFree(p, (x0 + x1) / 2, (z0 + z1) / 2, BlockSpec.of(Material.COMPOSTER));
        hangLantern(p, (x0 + x1) / 2, (z0 + z1) / 2 - 1);
    }

    /** Tables with benches, barrels along a wall, a counter. */
    private void furnishTavern(TownPainter p, Random random) {
        for (int tx = x0 + 3; tx <= x1 - 3; tx += 4) {
            int tz = (z0 + z1) / 2 + 1;
            if (placeIfFree(p, tx, tz, BlockSpec.of(style.fence()))) {
                p.set(tx, G + 2, tz, BlockSpec.of(Material.SPRUCE_PRESSURE_PLATE));
                placeIfFree(p, tx, tz - 1, Blocks.stairs(style.stairs(), BlockFace.NORTH));
                placeIfFree(p, tx, tz + 1, Blocks.stairs(style.stairs(), BlockFace.SOUTH));
            }
        }
        for (int x = x0 + 1; x < x1; x++) {
            if (random.nextDouble() < 0.6) {
                placeIfFree(p, x, z1 - 1, BlockSpec.of(Material.BARREL).facing(BlockFace.WEST));
            }
        }
        hangLantern(p, (x0 + x1) / 2, (z0 + z1) / 2);
    }

    /** A lantern hanging from whatever is above the ground floor at {@code x,z} (boards or roof). */
    private void hangLantern(TownPainter p, int x, int z) {
        for (int y = G + 3; y < G + 16; y++) {
            if (p.isSolid(x, y + 1, z)) {
                if (!p.isSolid(x, y, z)) {
                    p.set(x, y, z, Blocks.lantern(false, true));
                }
                return;
            }
        }
    }

    /** Places a block on the ground floor if the spot is free and not in front of a door. */
    private boolean placeIfFree(TownPainter p, int x, int z, BlockSpec spec) {
        if (!inside(x, z) || p.isSolid(x, G + 1, z) || p.isSolid(x, G + 2, z) || besideDoor(x, z)
                || isStairWall(x, z)) {
            return false;
        }
        p.set(x, G + 1, z, spec);
        return true;
    }

    private boolean besideDoor(int x, int z) {
        for (int[] door : doors) {
            int[] c = wallCell(BlockFace.values()[door[0]], door[1]);
            if (Math.abs(c[0] - x) + Math.abs(c[1] - z) <= 2) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ geometry

    /** The wall cells, going around once (north side west to east, east side, south side east to west, west). */
    private List<int[]> perimeter() {
        List<int[]> out = new ArrayList<>();
        for (int x = x0; x < x1; x++) {
            out.add(new int[] {x, z0});
        }
        for (int z = z0; z < z1; z++) {
            out.add(new int[] {x1, z});
        }
        for (int x = x1; x > x0; x--) {
            out.add(new int[] {x, z1});
        }
        for (int z = z1; z > z0; z--) {
            out.add(new int[] {x0, z});
        }
        return out;
    }

    private int[] wallCell(BlockFace side, int offset) {
        return switch (side) {
            case NORTH -> new int[] {x0 + offset, z0};
            case SOUTH -> new int[] {x0 + offset, z1};
            case WEST -> new int[] {x0, z0 + offset};
            case EAST -> new int[] {x1, z0 + offset};
            default -> throw new IllegalArgumentException(side + " is not a wall side");
        };
    }

    private static int[] step(BlockFace side) {
        return new int[] {side.getModX(), side.getModZ()};
    }

    private static int[] along(BlockFace side) {
        return side == BlockFace.NORTH || side == BlockFace.SOUTH ? new int[] {1, 0} : new int[] {0, 1};
    }
}
