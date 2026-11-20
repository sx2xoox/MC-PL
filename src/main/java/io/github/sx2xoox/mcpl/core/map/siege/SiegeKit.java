package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * Shared palette, buildings and props of 네온 시티: dark concrete, glass and steel, lit by neon (froglights and sea
 * lanterns behind stained glass), with magenta, cyan, lime and amber accents (never team red or blue).
 */
final class SiegeKit {

    private SiegeKit() {
    }

    static final int G = SiegeLayout.GROUND_Y;
    static final int STOREY = SiegeLayout.STOREY;

    /** Neon accent colours (dye names). */
    static final String[] NEON = {"MAGENTA", "CYAN", "LIME", "YELLOW", "PURPLE", "PINK", "ORANGE", "LIGHT_BLUE"};

    static BlockSpec of(Material material) {
        return BlockSpec.of(material);
    }

    static BlockSpec pick(Random random, Material... materials) {
        return BlockSpec.of(materials[random.nextInt(materials.length)]);
    }

    static String neon(Random random) {
        return NEON[random.nextInt(NEON.length)];
    }

    static BlockSpec concrete(String colour) {
        return of(Material.valueOf(colour + "_CONCRETE"));
    }

    static BlockSpec glass(String colour) {
        return of(Material.valueOf(colour + "_STAINED_GLASS"));
    }

    static BlockSpec pane(String colour) {
        return of(Material.valueOf(colour + "_STAINED_GLASS_PANE"));
    }

    /** A light block whose own colour fits the neon colour. */
    static BlockSpec glow(String colour) {
        return switch (colour) {
            case "MAGENTA", "PURPLE", "PINK" -> of(Material.PEARLESCENT_FROGLIGHT);
            case "LIME", "GREEN" -> of(Material.VERDANT_FROGLIGHT);
            case "YELLOW", "ORANGE" -> of(Material.OCHRE_FROGLIGHT);
            default -> of(Material.SEA_LANTERN);
        };
    }

    // ------------------------------------------------------------------ surfaces

    /** Asphalt of the roads. */
    static BlockSpec asphalt(Random random) {
        int roll = random.nextInt(100);
        if (roll < 70) {
            return of(Material.GRAY_CONCRETE);
        }
        if (roll < 90) {
            return of(Material.POLISHED_DEEPSLATE);
        }
        return of(Material.POLISHED_BLACKSTONE);
    }

    /** Pavement of sidewalks and plazas. */
    static BlockSpec pavement(Random random) {
        int roll = random.nextInt(100);
        if (roll < 55) {
            return of(Material.LIGHT_GRAY_CONCRETE);
        }
        if (roll < 85) {
            return of(Material.POLISHED_ANDESITE);
        }
        return of(Material.SMOOTH_STONE);
    }

    /** Worn ground of the slums and the underpass. */
    static BlockSpec grime(Random random) {
        return pick(random, Material.GRAY_CONCRETE, Material.GRAY_CONCRETE, Material.POLISHED_ANDESITE,
                Material.COBBLED_DEEPSLATE, Material.POLISHED_DEEPSLATE, Material.TUFF, Material.DEEPSLATE_TILES);
    }

    /** Hidden structure under the surface. */
    static BlockSpec fill(Random random) {
        return random.nextInt(5) == 0 ? of(Material.DEEPSLATE) : of(Material.TUFF);
    }

    // ------------------------------------------------------------------ facades

    /** The look of one building: walls, corner trim, window glass, neon accent. */
    record Facade(Material wall, Material trim, Material window, String neon) {
    }

    static Facade facade(Random random) {
        Material[] walls = {Material.GRAY_CONCRETE, Material.BLACK_CONCRETE, Material.LIGHT_GRAY_CONCRETE,
                Material.DEEPSLATE_TILES, Material.POLISHED_BLACKSTONE_BRICKS, Material.POLISHED_DEEPSLATE,
                Material.CYAN_TERRACOTTA, Material.GRAY_TERRACOTTA, Material.WAXED_WEATHERED_CUT_COPPER,
                Material.WAXED_OXIDIZED_COPPER, Material.SMOOTH_BASALT, Material.TUFF_BRICKS};
        Material[] trims = {Material.POLISHED_BLACKSTONE, Material.BLACK_CONCRETE, Material.POLISHED_DEEPSLATE,
                Material.IRON_BLOCK, Material.WAXED_EXPOSED_COPPER, Material.DEEPSLATE_BRICKS};
        Material[] windows = {Material.BLACK_STAINED_GLASS, Material.GRAY_STAINED_GLASS, Material.TINTED_GLASS,
                Material.LIGHT_BLUE_STAINED_GLASS, Material.CYAN_STAINED_GLASS, Material.PURPLE_STAINED_GLASS};
        return new Facade(walls[random.nextInt(walls.length)], trims[random.nextInt(trims.length)],
                windows[random.nextInt(windows.length)], neon(random));
    }

    /** Pane version of a window glass (glass panes for enterable floors, so people can look out). */
    static BlockSpec paneOf(Material window) {
        if (window == Material.TINTED_GLASS) {
            return of(Material.BLACK_STAINED_GLASS_PANE);
        }
        return of(Material.valueOf(window.name() + "_PANE"));
    }

    // ------------------------------------------------------------------ buildings

    /**
     * A city building on a rectangle. Every building has an open ground storey (a shop, a bar, a lobby) with door
     * openings on the {@code doors} sides; an {@code enterable} one also has stairs up through every storey to its
     * roof (so it needs {@code x2 - x1 >= 12}). The upper storeys of the others are sealed.
     *
     * @param doors sides with a door (bitmask of {@link BlockSpec#NORTH} ...)
     */
    record Building(int x1, int z1, int x2, int z2, int storeys, boolean enterable, int doors) {
        int roof() {
            return G + storeys * STOREY;
        }

        int cx() {
            return (x1 + x2) / 2;
        }

        int cz() {
            return (z1 + z2) / 2;
        }
    }

    /** A building whose neon is mostly its district's accent colour ({@link SiegeWayfinding#accent}). */
    static void building(MapCanvas canvas, Random random, Building b) {
        Facade look = facade(random);
        if (random.nextInt(3) != 0) {
            look = new Facade(look.wall(), look.trim(), look.window(), SiegeWayfinding.accentAt(b.cx(), b.cz()));
        }
        building(canvas, random, b, look);
    }

    static void building(MapCanvas canvas, Random random, Building b, Facade look) {
        int top = b.roof();
        // Walls.
        for (int x = b.x1; x <= b.x2; x++) {
            for (int z = b.z1; z <= b.z2; z++) {
                boolean edgeX = x == b.x1 || x == b.x2;
                boolean edgeZ = z == b.z1 || z == b.z2;
                if (!edgeX && !edgeZ) {
                    continue;
                }
                boolean corner = edgeX && edgeZ;
                int along = edgeZ ? x - b.x1 : z - b.z1;
                int length = edgeZ ? b.x2 - b.x1 : b.z2 - b.z1;
                for (int y = G + 1; y <= top; y++) {
                    canvas.set(x, y, z, facadeBlock(b, look, y, along, length, corner, random));
                }
            }
        }
        // Floors and the roof, with ceiling lights.
        for (int k = 1; k <= b.storeys; k++) {
            int y = G + k * STOREY;
            for (int x = b.x1 + 1; x < b.x2; x++) {
                for (int z = b.z1 + 1; z < b.z2; z++) {
                    boolean light = k < b.storeys && Math.floorMod(x - b.x1, 4) == 2
                            && Math.floorMod(z - b.z1, 4) == 2;
                    canvas.set(x, y, z, light ? of(Material.SEA_LANTERN) : k == b.storeys
                            ? of(Material.GRAY_CONCRETE) : of(Material.POLISHED_DEEPSLATE));
                }
            }
        }
        // Parapet.
        for (int x = b.x1; x <= b.x2; x++) {
            for (int z = b.z1; z <= b.z2; z++) {
                if (x == b.x1 || x == b.x2 || z == b.z1 || z == b.z2) {
                    canvas.set(x, top + 1, z, Blocks.slab(Material.POLISHED_BLACKSTONE_SLAB));
                }
            }
        }
        doors(canvas, random, b, look);
        if (b.enterable) {
            stairs(canvas, b);
        }
        groundFloor(canvas, random, b);
        roofTop(canvas, random, b, look);
    }

    private static BlockSpec facadeBlock(Building b, Facade look, int y, int along, int length, boolean corner,
                                         Random random) {
        if (corner) {
            return of(look.trim());
        }
        int rel = y - G - 1;
        int storey = rel / STOREY;
        int row = rel % STOREY;
        if (row == 4) {
            // Floor level: trim, or a neon band on every other storey.
            return storey % 2 == 1 ? glow(look.neon()) : of(look.trim());
        }
        if (row == 3) {
            if (storey == 0 && Math.floorMod(along, 4) == 2 && along > 0 && along < length) {
                // Shop lights over the street.
                return glow(look.neon());
            }
            return storey % 2 == 0 ? concrete(look.neon()) : of(look.wall());
        }
        if (row == 0) {
            return of(look.wall());
        }
        boolean window = storey == 0 ? Math.floorMod(along, 4) != 0 : Math.floorMod(along, 3) != 0;
        if (!window || along <= 0 || along >= length) {
            return of(look.wall());
        }
        if (storey == 0 || b.enterable) {
            return paneOf(look.window());
        }
        // Sealed floors: dark glass, some windows lit from inside.
        if (random.nextInt(9) == 0) {
            return random.nextBoolean() ? of(Material.OCHRE_FROGLIGHT) : glass(look.neon());
        }
        return of(look.window());
    }

    private static void doors(MapCanvas canvas, Random random, Building b, Facade look) {
        int[][] sides = {{BlockSpec.NORTH, 0, -1}, {BlockSpec.EAST, 1, 0}, {BlockSpec.SOUTH, 0, 1},
                {BlockSpec.WEST, -1, 0}};
        for (int[] side : sides) {
            if ((b.doors & side[0]) == 0) {
                continue;
            }
            int dx = side[1];
            int dz = side[2];
            boolean alongX = dz != 0;
            int wallX = dx > 0 ? b.x2 : b.x1;
            int wallZ = dz > 0 ? b.z2 : b.z1;
            int centre = alongX ? b.cx() + (b.enterable ? 1 : 0) : b.cz();
            for (int o = -1; o <= 1; o++) {
                int x = alongX ? centre + o : wallX;
                int z = alongX ? wallZ : centre + o;
                for (int y = G + 1; y <= G + 3; y++) {
                    canvas.clear(x, y, z);
                }
                // An awning over the door, a neon strip under it.
                for (int y = G + 4; y <= G + 4; y++) {
                    int ax = alongX ? x : wallX + dx;
                    int az = alongX ? wallZ + dz : z;
                    if (canvas.canWrite(ax, az) && canvas.get(ax, y, az) == null) {
                        canvas.set(ax, y, az, concrete(look.neon()));
                    }
                }
            }
            for (int o : new int[] {-2, 2}) {
                int ax = alongX ? centre + o : wallX + dx;
                int az = alongX ? wallZ + dz : centre + o;
                if (canvas.canWrite(ax, az) && canvas.get(ax, G + 4, az) == null) {
                    canvas.set(ax, G + 4, az, glow(look.neon()));
                }
            }
        }
        // A blade sign on one side, high up.
        if (b.storeys >= 2 && random.nextInt(3) != 0) {
            int[] side = sides[random.nextInt(4)];
            if ((b.doors & side[0]) != 0) {
                bladeSign(canvas, random, b, side[1], side[2]);
            }
        }
    }

    /** A vertical neon sign standing out from a wall: glowing core, stained glass face, dark caps. */
    private static void bladeSign(MapCanvas canvas, Random random, Building b, int dx, int dz) {
        boolean alongX = dz != 0;
        int pos = alongX ? (random.nextBoolean() ? b.x1 + 2 : b.x2 - 2) : (random.nextBoolean() ? b.z1 + 2 : b.z2 - 2);
        int wallX = dx > 0 ? b.x2 : b.x1;
        int wallZ = dz > 0 ? b.z2 : b.z1;
        int y1 = G + 7;
        int y2 = y1 + 5 + random.nextInt(5);
        if (y2 >= b.roof() - 4) {
            // Up to the parapet, so nobody who drops onto it from the roof is stuck there.
            y2 = b.roof() + 1;
        }
        String colour = neon(random);
        for (int d = 1; d <= 2; d++) {
            int x = alongX ? pos : wallX + dx * d;
            int z = alongX ? wallZ + dz * d : pos;
            if (!canvas.canWrite(x, z)) {
                return;
            }
        }
        for (int y = y1; y <= y2; y++) {
            for (int d = 1; d <= 2; d++) {
                int x = alongX ? pos : wallX + dx * d;
                int z = alongX ? wallZ + dz * d : pos;
                BlockSpec spec = y == y1 || y == y2 ? of(Material.BLACK_CONCRETE)
                        : d == 1 ? glow(colour) : glass(colour);
                canvas.set(x, y, z, spec);
            }
        }
    }

    /** Stairs from the street up through every storey to the roof, switching back along the north wall. */
    private static void stairs(MapCanvas canvas, Building b) {
        int z1 = b.z1 + 1;
        int z2 = b.z1 + 2;
        for (int k = 0; k < b.storeys; k++) {
            int f = G + k * STOREY;
            boolean east = k % 2 == 0;
            for (int i = 0; i < 4; i++) {
                int x = east ? b.x1 + 2 + i : b.x2 - 2 - i;
                for (int z = z1; z <= z2; z++) {
                    canvas.set(x, f + 1 + i, z, Blocks.stairs(Material.POLISHED_BLACKSTONE_BRICK_STAIRS,
                            east ? BlockFace.EAST : BlockFace.WEST));
                    for (int y = f + 2 + i; y <= f + 4 + i; y++) {
                        canvas.clear(x, y, z);
                    }
                }
                // A railing along the hole in the floor above.
                canvas.set(x, f + STOREY + 1, z2 + 1, of(Material.IRON_BARS));
            }
        }
    }

    /** Counters, crates and lights in the open ground storey. */
    private static void groundFloor(MapCanvas canvas, Random random, Building b) {
        int x1 = b.x1 + 2;
        int x2 = b.x2 - 2;
        int z1 = b.z1 + (b.enterable ? 4 : 2);
        int z2 = b.z2 - 2;
        if (x2 - x1 < 3 || z2 - z1 < 3) {
            return;
        }
        // A counter along the middle (cover inside the shop), with a gap to walk around.
        int cz = (z1 + z2) / 2;
        for (int x = x1 + 1; x <= x2 - 1; x++) {
            if (Math.floorMod(x - x1, 6) == 3) {
                continue;
            }
            canvas.set(x, G + 1, cz, random.nextInt(4) == 0 ? of(Material.BARREL)
                    : of(Material.POLISHED_BLACKSTONE_BRICKS));
        }
    }

    private static void roofTop(MapCanvas canvas, Random random, Building b, Facade look) {
        int top = b.roof();
        int w = b.x2 - b.x1;
        int d = b.z2 - b.z1;
        // AC units and vents (one block high, so they are cover on the roofs you can reach).
        int units = (w * d) / 60 + 1;
        for (int i = 0; i < units; i++) {
            int x = b.x1 + 2 + random.nextInt(Math.max(1, w - 4));
            int z = b.z1 + 4 + random.nextInt(Math.max(1, d - 6));
            if (canvas.get(x, top + 1, z) != null || canvas.get(x + 1, top + 1, z) != null) {
                continue;
            }
            if (b.enterable && z <= b.z1 + 3) {
                continue;
            }
            canvas.set(x, top + 1, z, of(Material.LIGHT_GRAY_CONCRETE));
            if (x + 1 < b.x2) {
                canvas.set(x + 1, top + 1, z, of(Material.IRON_BLOCK));
            }
        }
        // An antenna with a light at the top.
        if (!b.enterable || random.nextBoolean()) {
            int ax = b.x2 - 1;
            int az = b.z2 - 1;
            int h = 3 + random.nextInt(5);
            for (int y = top + 1; y <= top + h; y++) {
                canvas.set(ax, y, az, of(Material.LIGHTNING_ROD));
            }
            canvas.set(ax, top + h + 1, az, of(Material.END_ROD));
        }
        // A billboard on tall sealed buildings.
        if (!b.enterable && b.storeys >= 3 && random.nextInt(3) == 0 && w >= 7) {
            billboardOnRoof(canvas, random, b, look);
        }
    }

    private static void billboardOnRoof(MapCanvas canvas, Random random, Building b, Facade look) {
        int top = b.roof();
        int width = Math.min(b.x2 - b.x1 - 2, 9);
        int x1 = b.cx() - width / 2;
        int z = b.cz();
        int y1 = top + 3;
        int height = 4 + random.nextInt(3);
        for (int x : new int[] {x1 + 1, x1 + width - 2}) {
            for (int y = top + 1; y < y1; y++) {
                canvas.set(x, y, z, of(Material.POLISHED_BLACKSTONE_WALL));
            }
        }
        board(canvas, random, x1, y1, z, width, height, true, 1, look.neon(), neon(random));
    }

    /**
     * A neon billboard: lights at {@code z} (or {@code x}), stained glass in front of them on the {@code front} side
     * (+1/-1), showing rows of glyphs in two colours.
     */
    static void board(MapCanvas canvas, Random random, int a1, int y1, int c, int width, int height, boolean alongX,
                      int front, String colourA, String colourB) {
        boolean[][] pattern = glyphs(random, width, height);
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                int a = a1 + i;
                int y = y1 + j;
                boolean frame = i == 0 || i == width - 1 || j == 0 || j == height - 1;
                int x = alongX ? a : c;
                int z = alongX ? c : a;
                int fx = alongX ? a : c + front;
                int fz = alongX ? c + front : a;
                if (frame) {
                    canvas.set(x, y, z, of(Material.BLACK_CONCRETE));
                    continue;
                }
                canvas.set(x, y, z, glow(pattern[i][j] ? colourA : colourB));
                if (canvas.canWrite(fx, fz)) {
                    canvas.set(fx, y, fz, pattern[i][j] ? glass(colourA) : of(Material.BLACK_STAINED_GLASS));
                }
            }
        }
    }

    /** Blocky glyphs (3 wide, 3 tall, one gap) for billboards. */
    private static boolean[][] glyphs(Random random, int width, int height) {
        boolean[][] out = new boolean[width][height];
        for (int gx = 1; gx + 2 < width; gx += 4) {
            for (int gy = 1; gy + 2 < height; gy += 4) {
                int bits = 0x55 + random.nextInt(0x1FF - 0x55);
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        out[gx + i][gy + j] = (bits >> (i * 3 + j) & 1) == 1;
                    }
                }
            }
        }
        if (height <= 4) {
            for (int i = 1; i < width - 1; i++) {
                out[i][1] = out[i][1] || i % 2 == 0;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ props

    /** A parked car: three wide along {@code alongX}, five long; body in {@code colour}, dark glass cabin. */
    static void car(MapCanvas canvas, Random random, int x, int z, boolean alongX) {
        String[] paints = {"WHITE", "BLACK", "GRAY", "YELLOW", "MAGENTA", "CYAN", "LIGHT_GRAY"};
        BlockSpec body = concrete(paints[random.nextInt(paints.length)]);
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 3; j++) {
                int cx = alongX ? x + i : x + j;
                int cz = alongX ? z + j : z + i;
                boolean end = i == 0 || i == 4;
                canvas.set(cx, G + 1, cz, end && j != 1 ? of(i == 0 ? Material.SEA_LANTERN : Material.REDSTONE_BLOCK)
                        : body);
                if (i >= 1 && i <= 3) {
                    canvas.set(cx, G + 2, cz, i == 2 ? body : of(Material.TINTED_GLASS));
                }
            }
        }
    }

    /** A low barrier in hazard stripes, one block high (cover you can jump). */
    static void barrier(MapCanvas canvas, int x, int z, boolean alongX, int length) {
        for (int i = 0; i < length; i++) {
            int bx = alongX ? x + i : x;
            int bz = alongX ? z : z + i;
            canvas.set(bx, G + 1, bz, i % 2 == 0 ? of(Material.YELLOW_CONCRETE) : of(Material.BLACK_CONCRETE));
        }
    }

    /** A stack of crates: barrels and copper boxes, one or two high. */
    static void crates(MapCanvas canvas, Random random, int x, int z, int y) {
        canvas.set(x, y, z, pick(random, Material.BARREL, Material.WAXED_COPPER_BLOCK,
                Material.WAXED_EXPOSED_COPPER, Material.IRON_BLOCK, Material.BARREL));
        canvas.set(x + 1, y, z, pick(random, Material.BARREL, Material.WAXED_COPPER_GRATE,
                Material.WAXED_WEATHERED_COPPER));
        if (random.nextBoolean()) {
            canvas.set(x, y + 1, z, pick(random, Material.BARREL, Material.WAXED_COPPER_BLOCK));
        }
    }

    /** A street lamp: a dark pole, a glowing head, an arm with a lantern hanging from it on the {@code side}. */
    static void lamp(MapCanvas canvas, int x, int z, int armDx, int armDz, String colour) {
        // Low enough that whoever climbs onto it can drop back to the street (5 blocks).
        for (int y = G + 1; y <= G + 4; y++) {
            canvas.set(x, y, z, of(Material.POLISHED_BLACKSTONE_WALL));
        }
        canvas.set(x, G + 5, z, glow(colour));
        canvas.set(x, G + 6, z, of(Material.END_ROD));
        int ax = x + armDx;
        int az = z + armDz;
        if (canvas.canWrite(ax, az) && canvas.get(ax, G + 5, az) == null && canvas.get(ax, G + 4, az) == null) {
            canvas.set(ax, G + 5, az, of(Material.POLISHED_BLACKSTONE));
            canvas.set(ax, G + 4, az, Blocks.lantern(true, true));
        }
    }

    /** A vending machine: two blocks high, glowing front. */
    static void vending(MapCanvas canvas, int x, int z, String colour) {
        canvas.set(x, G + 1, z, concrete(colour));
        canvas.set(x, G + 2, z, glow(colour));
    }

    /** A planter box: a dark rim, moss and azaleas (stylised greenery instead of trees). */
    static void planter(MapCanvas canvas, Random random, int x1, int z1, int x2, int z2, int y) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean rim = x == x1 || x == x2 || z == z1 || z == z2;
                if (rim) {
                    canvas.set(x, y, z, of(Material.POLISHED_DEEPSLATE));
                    continue;
                }
                canvas.set(x, y, z, of(Material.MOSS_BLOCK));
                int roll = random.nextInt(6);
                if (roll < 2) {
                    canvas.set(x, y + 1, z, of(roll == 0 ? Material.FLOWERING_AZALEA : Material.AZALEA));
                } else if (roll < 4) {
                    canvas.set(x, y + 1, z, of(Material.SHORT_GRASS));
                } else if (roll == 4) {
                    canvas.set(x, y + 1, z, of(Material.FERN));
                }
            }
        }
    }

    /** A data pylon: a dark 2x2 column with glowing strips, {@code height} blocks tall from {@code y}. */
    static void pylon(MapCanvas canvas, int x, int z, int y, int height, String colour) {
        for (int i = 0; i < height; i++) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    boolean strip = i % 3 == 1 && i < height - 1;
                    canvas.set(x + dx, y + i, z + dz, strip ? glow(colour)
                            : i == height - 1 ? of(Material.POLISHED_BLACKSTONE) : of(Material.BLACK_CONCRETE));
                }
            }
        }
        canvas.set(x, y + height, z, of(Material.END_ROD));
    }

    /** A horizontal cable (chain) between two blocks; the chain must touch blocks at both ends. */
    static void cable(MapCanvas canvas, int x1, int y, int z1, int x2, int z2) {
        Axis axis = x1 == x2 ? Axis.Z : Axis.X;
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                if (canvas.get(x, y, z) == null) {
                    canvas.set(x, y, z, Blocks.log(Material.IRON_CHAIN, axis));
                }
            }
        }
    }

    /** A dumpster: green body with a dark lid. */
    static void dumpster(MapCanvas canvas, int x, int z, boolean alongX) {
        for (int i = 0; i < 2; i++) {
            int bx = alongX ? x + i : x;
            int bz = alongX ? z : z + i;
            canvas.set(bx, G + 1, bz, of(Material.GREEN_CONCRETE));
            canvas.set(bx, G + 2, bz, Blocks.slab(Material.POLISHED_BLACKSTONE_SLAB));
        }
    }
}
