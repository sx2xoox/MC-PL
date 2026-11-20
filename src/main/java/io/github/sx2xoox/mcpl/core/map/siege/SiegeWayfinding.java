package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapBlocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Material;

/**
 * Wayfinding and street light of 네온 시티, built over the finished districts.
 *
 * <ul>
 *   <li>Every lane keeps one colour along its whole length, from the attackers' camp up to the core: light strips in
 *       the floor (stained glass over a light), floor chevrons pointing east (towards the towers), lamps and a
 *       colour totem at every junction. Main avenue {@link #MAIN} (yellow), north street {@link #NORTH} (lime),
 *       canal {@link #CANAL} (light blue).</li>
 *   <li>Every district has one accent colour ({@link #accent}): its building neon, the floor lights on its streets,
 *       its tower's landmark and a big block-letter sign where the main avenue enters it.</li>
 *   <li>Floor lights on a staggered 8-block grid keep the streets lit at night.</li>
 * </ul>
 */
final class SiegeWayfinding {

    private SiegeWayfinding() {
    }

    private static final int G = SiegeLayout.GROUND_Y;
    private static final int P = SiegeLayout.PODIUM_Y;

    /** Lane colours (dye names). */
    static final String MAIN = "YELLOW";
    static final String NORTH = "LIME";
    static final String CANAL = "LIGHT_BLUE";

    /** Main avenue strips: the edges of the road. */
    static final int MAIN_STRIP = SiegeLayout.MAIN_HALF - 3;
    /** North street strips (both edges of its road). */
    static final int NORTH_STRIP_1 = SiegeLayout.NORTH_Z1 + 3;
    static final int NORTH_STRIP_2 = SiegeLayout.NORTH_Z2 - 3;
    /** Canal strips: both sides of the water channel. */
    static final int CANAL_STRIP_1 = SiegeLayout.CHANNEL_Z1 - 1;
    static final int CANAL_STRIP_2 = SiegeLayout.CHANNEL_Z2 + 1;
    /** Where the lanes leave the attackers' camp. */
    private static final int CAMP_X = -150;
    /** The north lane turns towards the parking ramp here. */
    private static final int RAMP_TURN_X = 90;
    private static final int RAMP_MID_Z = (SiegeLayout.RAMP_Z1 + SiegeLayout.RAMP_Z2) / 2 - 1;
    /** The canal lane's way from the canal's east stairs to the stair tower's door. */
    private static final int CANAL_EXIT_X = 84;
    private static final int TOWER_PATH_Z = 71;
    private static final int TOWER_DOOR_X = 104;

    /** Accent colour of a district ({@link SiegeLayout#partOf}). */
    static String accent(String part) {
        return switch (part) {
            case "slums" -> "ORANGE";
            case "firewall" -> "MAGENTA";
            case "hub" -> "CYAN";
            case "hq" -> "PURPLE";
            default -> "WHITE";
        };
    }

    static String accentAt(int x, int z) {
        return accent(SiegeLayout.partOf(x, z));
    }

    /** The plain floor blocks (street, sidewalk, slum ground, podium roof) the lights may replace. */
    private static final Set<Material> FLOOR = Set.of(Material.BLACK_CONCRETE, Material.GRAY_CONCRETE,
            Material.LIGHT_GRAY_CONCRETE, Material.POLISHED_BLACKSTONE, Material.BLACKSTONE, Material.POLISHED_ANDESITE,
            Material.POLISHED_DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.TUFF, Material.DEEPSLATE_TILES,
            Material.SMOOTH_STONE, Material.WHITE_CONCRETE, Material.YELLOW_CONCRETE,
            Material.WAXED_OXIDIZED_CUT_COPPER, Material.LIGHT_BLUE_STAINED_GLASS);

    static void build(MapCanvas canvas) {
        laneStrips(canvas);
        chevrons(canvas);
        totems(canvas);
        floorLights(canvas);
        signs(canvas);
    }

    // ------------------------------------------------------------------ lane strips

    private static void laneStrips(MapCanvas canvas) {
        // Main avenue: both road edges and the dashed centre line, from the camp to the grand stairs, then on
        // across the podium to the core.
        for (int x = CAMP_X; x < SiegeLayout.STAIRS_X1; x++) {
            if (SiegeLayout.inPlaza(x, 0)) {
                continue;
            }
            strip(canvas, x, G, -MAIN_STRIP, MAIN);
            strip(canvas, x, G, MAIN_STRIP, MAIN);
            if (Math.floorMod(x, 8) < 4) {
                strip(canvas, x, G, 0, MAIN);
            }
        }
        for (int x = SiegeLayout.PODIUM_X1 + 1; x < SiegeLayout.CORE_X - 11; x++) {
            strip(canvas, x, P, -MAIN_STRIP, MAIN);
            strip(canvas, x, P, MAIN_STRIP, MAIN);
        }
        // North street: from the camp to the forecourt, then north to the parking ramp; from the ramp's landing on
        // the podium to the core.
        for (int x = CAMP_X; x <= RAMP_TURN_X; x++) {
            strip(canvas, x, G, NORTH_STRIP_1, NORTH);
            strip(canvas, x, G, NORTH_STRIP_2, NORTH);
        }
        for (int z = RAMP_MID_Z; z < NORTH_STRIP_1; z++) {
            strip(canvas, RAMP_TURN_X, G, z, NORTH);
        }
        for (int x = RAMP_TURN_X; x < SiegeLayout.RAMP_X1; x++) {
            strip(canvas, x, G, RAMP_MID_Z, NORTH);
        }
        for (int z = -SiegeLayout.PODIUM_Z + 1; z <= -14; z++) {
            strip(canvas, 129, P, z, NORTH);
        }
        // Canal: from the camp to the stairs down, along both sides of the channel, then from the east stairs to the
        // stair tower and from its door on the podium to the core.
        for (int x = CAMP_X; x < SiegeLayout.CANAL_X1 - 6; x++) {
            strip(canvas, x, G, (SiegeLayout.CANAL_Z1 + SiegeLayout.CANAL_Z2) / 2, CANAL);
        }
        for (int x = SiegeLayout.CANAL_X1; x <= SiegeLayout.CANAL_X2 - 5; x++) {
            strip(canvas, x, SiegeLayout.CANAL_Y, CANAL_STRIP_1, CANAL);
            strip(canvas, x, SiegeLayout.CANAL_Y, CANAL_STRIP_2, CANAL);
        }
        for (int z = SiegeLayout.CANAL_Z2 + 2; z <= TOWER_PATH_Z; z++) {
            strip(canvas, CANAL_EXIT_X, G, z, CANAL);
        }
        for (int x = CANAL_EXIT_X; x <= TOWER_DOOR_X; x++) {
            strip(canvas, x, G, TOWER_PATH_Z, CANAL);
        }
        for (int z = SiegeLayout.PODIUM_Z - 1; z >= 42; z--) {
            strip(canvas, 103, P, z, CANAL);
        }
        for (int x = 103; x <= 116; x++) {
            strip(canvas, x, P, 42, CANAL);
        }
        for (int z = 42; z >= 17; z--) {
            strip(canvas, 116, P, z, CANAL);
        }
    }

    /** A light strip block: stained glass in the lane colour over a light, if the floor there is plain. */
    private static void strip(MapCanvas canvas, int x, int y, int z, String colour) {
        if (!plainFloor(canvas, x, y, z)) {
            return;
        }
        canvas.set(x, y, z, SiegeKit.glass(colour));
        canvas.set(x, y - 1, z, SiegeKit.glow(colour));
    }

    /** True for a plain floor block that is not buried under a wall. */
    private static boolean plainFloor(MapCanvas canvas, int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        if (spec == null || !FLOOR.contains(spec.material()) || !SiegeLayout.isInterior(x, z)) {
            return false;
        }
        return !buried(canvas, x, y, z);
    }

    /** True if full blocks stand on the floor block three high (it is under a wall or a pillar). */
    private static boolean buried(MapCanvas canvas, int x, int y, int z) {
        for (int h = 1; h <= 3; h++) {
            BlockSpec above = canvas.get(x, y + h, z);
            if (above == null || !MapBlocks.isFullCube(above)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The neutral objectives: a white ring of light in the floor just outside the capture radius (a double ring around
     * the big one) and a light at the centre; nothing stands inside the radius.
     */
    static void objectiveRings(MapCanvas canvas, List<SiegeSites.Objective> objectives) {
        for (SiegeSites.Objective o : objectives) {
            int y = o.y() - 1;
            for (int dx = -o.radius() - 3; dx <= o.radius() + 3; dx++) {
                for (int dz = -o.radius() - 3; dz <= o.radius() + 3; dz++) {
                    int ring = (int) Math.round(Math.hypot(dx, dz));
                    boolean mark = ring == o.radius() + 1 || o.major() && ring == o.radius() + 3 || ring == 0;
                    int x = o.x() + dx;
                    int z = o.z() + dz;
                    BlockSpec spec = canvas.get(x, y, z);
                    if (!mark || spec == null || !(FLOOR.contains(spec.material())
                            || spec.material().name().endsWith("_STAINED_GLASS")) || buried(canvas, x, y, z)) {
                        continue;
                    }
                    canvas.set(x, y, z, ring == 0 ? BlockSpec.of(Material.SEA_LANTERN) : SiegeKit.glass("WHITE"));
                    if (ring != 0) {
                        canvas.set(x, y - 1, z, BlockSpec.of(Material.SEA_LANTERN));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ chevrons and totems

    /** The junctions of the lanes (x ranges of the cross streets and the plaza streets). */
    private static final int[][] JUNCTIONS = {{-104, -96}, {SiegeLayout.FRONT_X - 4, SiegeLayout.FRONT_X + 4},
        {-20, -12}, {SiegeLayout.MIDDLE_X - 4, SiegeLayout.MIDDLE_X + 4}, {64, 72}};

    /** Floor chevrons in the lane colour, every 20 blocks along each lane, pointing east (towards the core). */
    private static void chevrons(MapCanvas canvas) {
        for (int x = -140; x <= 66; x += 20) {
            if (!SiegeLayout.inPlaza(x, 0) && !SiegeLayout.inPlaza(x + 3, 0) && !SiegeLayout.inPlaza(x - 2, 0)) {
                chevron(canvas, x, G, -4, MAIN);
                chevron(canvas, x, G, 4, MAIN);
            }
            chevron(canvas, x, G, (SiegeLayout.NORTH_Z1 + SiegeLayout.NORTH_Z2) / 2, NORTH);
            if (x >= SiegeLayout.CANAL_X1 + 2 && x <= SiegeLayout.CANAL_X2 - 8) {
                chevron(canvas, x, SiegeLayout.CANAL_Y, SiegeLayout.CANAL_Z1 + 4, CANAL);
            }
        }
    }

    /** A chevron ({@code >}) two blocks thick with its tip at {@code x + 2, z}. */
    private static void chevron(MapCanvas canvas, int x, int y, int z, String colour) {
        for (int k = -2; k <= 2; k++) {
            int tip = x + 2 - Math.abs(k);
            strip(canvas, tip, y, z + k, colour);
            strip(canvas, tip - 1, y, z + k, colour);
        }
    }

    /** A colour totem on two corners of every junction of every lane. */
    private static void totems(MapCanvas canvas) {
        int canalLedgeN = SiegeLayout.CANAL_Z1 - 2;
        int canalLedgeS = SiegeLayout.CANAL_Z2 + 2;
        for (int[] j : JUNCTIONS) {
            if (j[0] != SiegeLayout.FRONT_X - 4 && j[0] != SiegeLayout.MIDDLE_X - 4) {
                totem(canvas, j[0], -SiegeLayout.MAIN_HALF, MAIN);
                totem(canvas, j[1], SiegeLayout.MAIN_HALF, MAIN);
            }
            totem(canvas, j[0], SiegeLayout.NORTH_Z2, NORTH);
            totem(canvas, j[1], SiegeLayout.NORTH_Z1, NORTH);
            totem(canvas, j[0] - 1, canalLedgeN, CANAL);
            totem(canvas, j[0] - 1, canalLedgeS, CANAL);
        }
    }

    /** A pillar of lane-colour light, six blocks high (too high to climb), on a free street corner. */
    private static void totem(MapCanvas canvas, int x, int z, String colour) {
        if (!SiegeStreets.free(canvas, x, z) || SiegeZones.keepFree(x, z) && !nearCanal(z)) {
            return;
        }
        canvas.set(x, G + 1, z, BlockSpec.of(Material.BLACK_CONCRETE));
        for (int y = G + 2; y <= G + 5; y++) {
            canvas.set(x, y, z, y % 2 == 0 ? SiegeKit.concrete(colour) : SiegeKit.glow(colour));
        }
        canvas.set(x, G + 6, z, BlockSpec.of(Material.END_ROD));
    }

    private static boolean nearCanal(int z) {
        return z >= SiegeLayout.CANAL_Z1 - 3 && z <= SiegeLayout.CANAL_Z2 + 3;
    }

    // ------------------------------------------------------------------ floor lights

    /**
     * Lights set into the floor on a staggered 8-block grid, in the district's accent colour, everywhere players walk
     * at street level (also inside the open ground storeys, in the canal and on the podium), except the tower
     * plazas, which have their own rings.
     */
    private static void floorLights(MapCanvas canvas) {
        for (int x = SiegeLayout.WEST_END; x <= SiegeLayout.EAST_END; x++) {
            for (int z = -SiegeLayout.SIDE; z <= SiegeLayout.SIDE; z++) {
                int mx = Math.floorMod(x, 8);
                int mz = Math.floorMod(z, 8);
                if (!(mx == 2 && mz == 2 || mx == 6 && mz == 6)) {
                    continue;
                }
                if (SiegeLayout.near(x, z, SiegeLayout.FRONT_X, 0, SiegeLayout.PLAZA_RADIUS)
                        || SiegeLayout.near(x, z, SiegeLayout.MIDDLE_X, 0, SiegeLayout.PLAZA_RADIUS)
                        || SiegeLayout.near(x, z, SiegeLayout.CORE_X, 0, SiegeLayout.PLAZA_RADIUS)) {
                    continue;
                }
                int y = SiegeLayout.ground(x, z);
                if (SiegeLayout.inCanal(x, z) && canvas.get(x, G, z) != null) {
                    y = G;
                }
                BlockSpec spec = canvas.get(x, y, z);
                if (spec == null || !FLOOR.contains(spec.material()) || spec.material() == Material.YELLOW_CONCRETE
                        || canvas.isSolid(x, y + 1, z) || canvas.isSolid(x, y + 2, z)) {
                    continue;
                }
                canvas.set(x, y, z, SiegeKit.glow(accentAt(x, z)));
            }
        }
    }

    // ------------------------------------------------------------------ signs

    /** Big block-letter signs facing the attackers where the main avenue enters each district. */
    private static void signs(MapCanvas canvas) {
        // SLUMS: a gantry over the avenue at the edge of the camp.
        int x = SiegeLayout.START_EAST;
        int y1 = G + 8;
        board(canvas, x, y1, 0, "SLUMS", accent("slums"));
        for (int z : new int[] {-12, 12}) {
            for (int y = G + 1; y < y1 + 7; y++) {
                canvas.set(x, y, z, y % 3 == 0 ? SiegeKit.glow(accent("slums")) : BlockSpec.of(Material.BLACK_CONCRETE));
            }
        }
        // FIREWALL on the face of the wall south of the gate, GATE on the lintel.
        text(canvas, "FIREWALL", SiegeLayout.WALL_X1, G + 7, 30, SiegeKit.glow(accent("firewall")),
                BlockSpec.of(Material.BLACK_CONCRETE));
        text(canvas, "FIREWALL", SiegeLayout.WALL_X1, G + 7, 72, SiegeKit.glow(accent("firewall")),
                BlockSpec.of(Material.BLACK_CONCRETE));
        text(canvas, "GATE", SiegeLayout.WALL_X1, G + 11, 0, SiegeKit.glow(accent("firewall")),
                BlockSpec.of(Material.BLACK_CONCRETE));
        // DATA HUB on the west side of the ring walkway, MEGACORP on the podium's face.
        board(canvas, SiegeLayout.MIDDLE_X - SiegeHubZone.RING_OUT, SiegeLayout.BRIDGE_Y + 1, 0, "DATA HUB",
                accent("hub"));
        board(canvas, SiegeLayout.PODIUM_X1, G + 6, -34, "MEGACORP", accent("hq"));
        board(canvas, SiegeLayout.PODIUM_X1, G + 6, 34, "MEGACORP", accent("hq"));
    }

    /**
     * A sign in a plane along z at {@code x}, read from the west: dark board with a frame in {@code colour}, the
     * letters lit, centred on {@code zc}, its bottom row at {@code y1} (7 rows).
     */
    static void board(MapCanvas canvas, int x, int y1, int zc, String text, String colour) {
        int width = width(text);
        int z1 = zc - width / 2 - 1;
        int z2 = z1 + width + 1;
        for (int z = z1; z <= z2; z++) {
            for (int y = y1; y <= y1 + 6; y++) {
                boolean frame = z == z1 || z == z2 || y == y1 || y == y1 + 6;
                canvas.set(x, y, z, frame ? SiegeKit.concrete(colour) : BlockSpec.of(Material.BLACK_CONCRETE));
            }
        }
        text(canvas, text, x, y1 + 1, z1 + 1 + width / 2, SiegeKit.glow(colour), null);
    }

    /** Writes {@code text} (block font, 5 rows from {@code y1} up) into the plane at {@code x}, centred on zc. */
    static void text(MapCanvas canvas, String text, int x, int y1, int zc, BlockSpec on, BlockSpec off) {
        int z = zc - width(text) / 2;
        for (char c : text.toCharArray()) {
            String[] glyph = FONT.get(c);
            int w = glyph[0].length();
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < w; col++) {
                    boolean lit = glyph[row].charAt(col) == '#';
                    int y = y1 + 4 - row;
                    if (lit) {
                        canvas.set(x, y, z + col, on);
                    } else if (off != null) {
                        canvas.set(x, y, z + col, off);
                    }
                }
            }
            if (off != null) {
                for (int row = 0; row < 5; row++) {
                    canvas.set(x, y1 + row, z + w, off);
                }
            }
            z += w + 1;
        }
    }

    /** Width of a text in the block font (letters one column apart). */
    static int width(String text) {
        int w = -1;
        for (char c : text.toCharArray()) {
            w += FONT.get(c)[0].length() + 1;
        }
        return w;
    }

    /** A 5-row block font (rows from the top; {@code #} is lit). */
    private static final Map<Character, String[]> FONT = Map.ofEntries(
            Map.entry('A', new String[] {".#.", "#.#", "###", "#.#", "#.#"}),
            Map.entry('B', new String[] {"##.", "#.#", "##.", "#.#", "##."}),
            Map.entry('C', new String[] {".##", "#..", "#..", "#..", ".##"}),
            Map.entry('D', new String[] {"##.", "#.#", "#.#", "#.#", "##."}),
            Map.entry('E', new String[] {"###", "#..", "##.", "#..", "###"}),
            Map.entry('F', new String[] {"###", "#..", "##.", "#..", "#.."}),
            Map.entry('G', new String[] {".##", "#..", "#.#", "#.#", ".##"}),
            Map.entry('H', new String[] {"#.#", "#.#", "###", "#.#", "#.#"}),
            Map.entry('I', new String[] {"###", ".#.", ".#.", ".#.", "###"}),
            Map.entry('L', new String[] {"#..", "#..", "#..", "#..", "###"}),
            Map.entry('M', new String[] {"#...#", "##.##", "#.#.#", "#...#", "#...#"}),
            Map.entry('O', new String[] {"###", "#.#", "#.#", "#.#", "###"}),
            Map.entry('P', new String[] {"##.", "#.#", "##.", "#..", "#.."}),
            Map.entry('R', new String[] {"##.", "#.#", "##.", "#.#", "#.#"}),
            Map.entry('S', new String[] {".##", "#..", ".#.", "..#", "##."}),
            Map.entry('T', new String[] {"###", ".#.", ".#.", ".#.", ".#."}),
            Map.entry('U', new String[] {"#.#", "#.#", "#.#", "#.#", "###"}),
            Map.entry('W', new String[] {"#...#", "#...#", "#.#.#", "##.##", "#...#"}),
            Map.entry(' ', new String[] {"..", "..", "..", "..", ".."}));
}
