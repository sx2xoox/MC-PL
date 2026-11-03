package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.ArenaMap;
import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * 네온 시티, the 초능력 공성전 map (docs/siege.md §6): one district of a neon megacity at night, about 320 x 177 blocks
 * inside a ring of skyscrapers. The attackers start under an elevated highway in the west and push through the neon
 * slums, the corporate firewall (방화벽 게이트), the data hub (데이터 허브) and up onto the podium of the megacorp
 * headquarters (메인프레임). Three lanes (main avenue, north street, sunken canal) meet at every tower and split again;
 * cross streets, alleys, enterable buildings and glass skybridges connect them. See {@link SiegeLayout} for the plan.
 *
 * <p>The map builds the ground, the canal and the skyline, lets every zone build its own columns on a restricted
 * canvas, then adds what spans zones (the highway, the monorail) and clears the tower platforms last.
 */
public final class SiegeMap implements ArenaMap, SiegeSites {

    private static final int G = SiegeLayout.GROUND_Y;

    private final List<MapZone> zones = List.of(
            new SiegeSlumsZone(),
            new SiegeFirewallZone(),
            new SiegeHubZone(),
            new SiegeHqZone());

    private static final List<Tower> TOWERS = List.of(
            new Tower("front", "방화벽 게이트", SiegeLayout.FRONT_X, G + 1, 0, SiegeLayout.TOWER_RADIUS),
            new Tower("middle", "데이터 허브", SiegeLayout.MIDDLE_X, G + 1, 0, SiegeLayout.TOWER_RADIUS),
            new Tower("core", "메인프레임", SiegeLayout.CORE_X, SiegeLayout.PODIUM_Y + 1, 0,
                    SiegeLayout.TOWER_RADIUS));

    private static final List<Spot> ATTACKERS = grid(new int[] {-152, -148}, new int[] {-6, -2, 2, 6}, G + 1,
            SiegeLayout.FRONT_X, 0);

    private static final List<List<Spot>> DEFENDERS = List.of(
            grid(new int[] {-32, -27}, new int[] {17, 21, 25, 29}, G + 1, SiegeLayout.FRONT_X, 0),
            grid(new int[] {55, 59}, new int[] {-35, -31, -27, -23}, G + 1, SiegeLayout.MIDDLE_X, 0),
            grid(new int[] {142, 146}, new int[] {-6, -2, 2, 6}, SiegeLayout.PODIUM_Y + 1, SiegeLayout.CORE_X, 0));

    /**
     * Neutral objectives on the cross street between the firewall and the hub: a data node on the north street and one
     * down in the canal, and the big objective on the avenue crossing between them.
     */
    private static final List<Objective> OBJECTIVES = List.of(
            new Objective("north", "북쪽 데이터 노드", -16, G + 1, -50, 4, false),
            new Objective("canal", "수로 데이터 노드", -16, SiegeLayout.CANAL_Y + 1, 48, 4, false),
            new Objective("center", "오버클럭 코어", -16, G + 1, 0, 5, true));

    /**
     * Monster camps in the side streets and blocks of both halves (docs/siege.md §13): on each half three small camps
     * and one buff camp (레드 서버 on the attackers' half, 블루 서버 on the defenders' half), and the epic
     * 메인프레임 수호자 in the headquarters' north forecourt. Each centre is a free standing place with a flat open
     * area around it ({@code SiegeMapTest#theCampsHaveOpenReachableGround}).
     */
    private static final List<Camp> CAMPS = List.of(
            new Camp("west-drones", "드론 둥지", "drones", -89, G + 1, -34, 4),
            new Camp("west-rats", "변종 쥐떼", "rats", -64, G + 1, 67, 4),
            new Camp("west-scrap", "고철 골렘", "scrap", -89, G + 1, 34, 4),
            new Camp("red", "레드 서버", "red", -65, G + 1, -80, 5),
            new Camp("east-drones", "드론 둥지", "drones", 19, G + 1, -64, 3),
            new Camp("east-rats", "변종 쥐떼", "rats", 55, G + 1, 66, 4),
            new Camp("east-scrap", "고철 골렘", "scrap", 72, G + 1, 34, 4),
            new Camp("blue", "블루 서버", "blue", 94, G + 1, 78, 5),
            new Camp("guardian", "메인프레임 수호자", "guardian", 82, G + 1, -30, 7));

    /** Spawn spots on a grid (every combination of {@code xs} and {@code zs}), all looking at one place. */
    private static List<Spot> grid(int[] xs, int[] zs, int y, int lookX, int lookZ) {
        List<Spot> spots = new ArrayList<>();
        for (int x : xs) {
            for (int z : zs) {
                spots.add(new Spot(x, y, z, SiegeLayout.yaw(x + 0.5, z + 0.5, lookX + 0.5, lookZ + 0.5)));
            }
        }
        return List.copyOf(spots);
    }

    @Override
    public String id() {
        return "siege";
    }

    @Override
    public String displayName() {
        return "네온 시티";
    }

    @Override
    public String description() {
        return "밤의 네온 메가시티: 고가 하부에서 슬럼, 방화벽 게이트, 데이터 허브를 지나 메가코프 본사의 메인프레임까지 "
                + "큰길·북쪽 거리·지하 수로 세 갈래와 건물·구름다리로 이어지는 공성전 전용 맵";
    }

    @Override
    public int playRadius() {
        return SiegeLayout.BUILD_RADIUS;
    }

    @Override
    public int buildRadius() {
        return SiegeLayout.BUILD_RADIUS;
    }

    @Override
    public int groundY() {
        return G;
    }

    @Override
    public int minY() {
        return SiegeLayout.MIN_Y;
    }

    @Override
    public int maxY() {
        return SiegeLayout.MAX_Y;
    }

    @Override
    public long seed() {
        return 0x4E30C17L;
    }

    @Override
    public int version() {
        return 3;
    }

    @Override
    public List<Spot> attackerSpawns() {
        return ATTACKERS;
    }

    @Override
    public List<List<Spot>> defenderSpawns() {
        return DEFENDERS;
    }

    @Override
    public List<Tower> towers() {
        return TOWERS;
    }

    @Override
    public List<Objective> objectives() {
        return OBJECTIVES;
    }

    @Override
    public List<Camp> camps() {
        return CAMPS;
    }

    /** The zones in build order (exposed for tests and previews). */
    public List<MapZone> zones() {
        return zones;
    }

    @Override
    public void build(MapCanvas canvas) {
        ground(canvas);
        canal(canvas);
        skyline(canvas);
        for (MapZone zone : zones) {
            zone.build(canvas.restrictTo(zone::contains, zone.name()));
        }
        highway(canvas);
        monorail(canvas);
        SiegeWayfinding.build(canvas);
        SiegeWayfinding.objectiveRings(canvas, OBJECTIVES);
        for (Tower tower : TOWERS) {
            towerRing(canvas, tower);
        }
        for (Tower tower : TOWERS) {
            clearPlatform(canvas, tower);
        }
    }

    // ------------------------------------------------------------------ ground

    private static void ground(MapCanvas canvas) {
        Random random = canvas.random("ground");
        int r = SiegeLayout.BUILD_RADIUS;
        int w = SiegeLayout.HALF_WIDTH;
        for (int x = -r; x <= r; x++) {
            for (int z = -w; z <= w; z++) {
                for (int y = G - 6; y < G; y++) {
                    canvas.set(x, y, z, SiegeKit.fill(random));
                }
                canvas.set(x, G, z, surface(random, x, z));
            }
        }
    }

    /** The top block of the street at a column. */
    private static BlockSpec surface(Random random, int x, int z) {
        if (!SiegeLayout.isInterior(x, z)) {
            return BlockSpec.of(Material.POLISHED_BLACKSTONE);
        }
        if (SiegeLayout.inPlaza(x, z)) {
            int cx = x < 0 ? SiegeLayout.FRONT_X : SiegeLayout.MIDDLE_X;
            double d = Math.hypot(x - cx, z);
            int ring = (int) Math.round(d);
            if (ring == 11 || ring == 15) {
                return BlockSpec.of(x < 0 ? Material.MAGENTA_CONCRETE : Material.CYAN_CONCRETE);
            }
            if (ring == 13) {
                return BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS);
            }
            if (ring <= 10) {
                return ring % 3 == 0 ? BlockSpec.of(Material.POLISHED_DEEPSLATE)
                        : BlockSpec.of(Material.GRAY_CONCRETE);
            }
            return SiegeKit.pavement(random);
        }
        if (x <= SiegeLayout.START_EAST || x < SiegeLayout.WALL_X1 && !road(x, z)) {
            return random.nextInt(40) == 0 ? BlockSpec.of(Material.LIGHT_BLUE_STAINED_GLASS) : SiegeKit.grime(random);
        }
        if (road(x, z)) {
            // Lane markings.
            if (Math.abs(z) <= SiegeLayout.MAIN_HALF) {
                if (z == 0 && Math.floorMod(x, 8) < 4) {
                    return BlockSpec.of(Material.YELLOW_CONCRETE);
                }
                if (Math.abs(z) == 4 && Math.floorMod(x, 6) < 3) {
                    return BlockSpec.of(Material.WHITE_CONCRETE);
                }
            }
            if (random.nextInt(60) == 0) {
                return BlockSpec.of(Material.LIGHT_BLUE_STAINED_GLASS);
            }
            return SiegeKit.asphalt(random);
        }
        return SiegeKit.pavement(random);
    }

    /** Asphalt (roads) as opposed to sidewalks, plazas and the ground under buildings. */
    private static boolean road(int x, int z) {
        if (Math.abs(z) <= SiegeLayout.MAIN_HALF - 3 && x < SiegeLayout.STAIRS_X1) {
            return true;
        }
        if (z >= SiegeLayout.NORTH_Z1 + 3 && z <= SiegeLayout.NORTH_Z2 - 3) {
            return true;
        }
        for (int[] street : SiegeLayout.CROSS_STREETS) {
            if (x >= street[0] + 2 && x <= street[1] - 2) {
                return true;
            }
        }
        return Math.abs(x - SiegeLayout.FRONT_X) <= 2 || Math.abs(x - SiegeLayout.MIDDLE_X) <= 2;
    }

    // ------------------------------------------------------------------ the canal

    /** Cross streets over the canal: bridges at street level with stairs down next to them (x ranges). */
    static final int[][] CANAL_CROSSINGS = {{-104, -96}, {-54, -46}, {-20, -12}, {26, 34}, {64, 72}};

    /**
     * The sunken canal of the south lane: six blocks below the street, a water channel along it, railings on the
     * ledges, stairs down at the west end, next to every crossing and up at the east end.
     */
    private static void canal(MapCanvas canvas) {
        Random random = canvas.random("canal");
        int floor = SiegeLayout.CANAL_Y;
        for (int x = SiegeLayout.CANAL_X1; x <= SiegeLayout.CANAL_X2; x++) {
            boolean bridge = crossing(x);
            for (int z = SiegeLayout.CANAL_Z1; z <= SiegeLayout.CANAL_Z2; z++) {
                for (int y = floor + 1; y <= G - (bridge ? 1 : 0); y++) {
                    canvas.clear(x, y, z);
                }
                if (bridge) {
                    canvas.set(x, G, z, z == SiegeLayout.CANAL_Z1 || z == SiegeLayout.CANAL_Z2
                            ? BlockSpec.of(Material.POLISHED_BLACKSTONE) : SiegeKit.asphalt(random));
                    canvas.set(x, G - 1, z, Math.floorMod(x, 3) == 0
                            ? BlockSpec.of(Material.SEA_LANTERN) : BlockSpec.of(Material.POLISHED_DEEPSLATE));
                }
                boolean channel = z >= SiegeLayout.CHANNEL_Z1 && z <= SiegeLayout.CHANNEL_Z2
                        && x >= SiegeLayout.CANAL_X1 + 7 && x <= SiegeLayout.CANAL_X2 - 10;
                if (channel) {
                    canvas.set(x, floor, z, BlockSpec.of(Material.WATER));
                    canvas.set(x, floor - 1, z, BlockSpec.of(Material.DARK_PRISMARINE));
                } else {
                    canvas.set(x, floor, z, z == SiegeLayout.CHANNEL_Z1 - 1 || z == SiegeLayout.CHANNEL_Z2 + 1
                            ? BlockSpec.of(Material.WAXED_OXIDIZED_CUT_COPPER) : SiegeKit.grime(random));
                }
                for (int y = floor - 3; y < floor - (channel ? 1 : 0); y++) {
                    canvas.set(x, y, z, SiegeKit.fill(random));
                }
            }
            // The canal walls: dark tiles with a neon strip, railings on the ledges.
            for (int z : new int[] {SiegeLayout.CANAL_Z1 - 1, SiegeLayout.CANAL_Z2 + 1}) {
                for (int y = floor + 1; y < G; y++) {
                    canvas.set(x, y, z, y == floor + 3 && Math.floorMod(x, 4) != 0
                            ? SiegeKit.glow(SiegeWayfinding.CANAL) : BlockSpec.of(Material.DEEPSLATE_TILES));
                }
                if (!bridge && !nextToCrossing(x)) {
                    canvas.set(x, G + 1, z, BlockSpec.of(Material.IRON_BARS));
                }
            }
        }
        // Stairs down from the attackers' start (west end), up at the east end.
        for (int i = 0; i < 6; i++) {
            int x = SiegeLayout.CANAL_X1 - 6 + i;
            for (int z = SiegeLayout.CANAL_Z1; z <= SiegeLayout.CANAL_Z2; z++) {
                for (int y = floor + 1; y <= G; y++) {
                    canvas.clear(x, y, z);
                }
                canvas.fill(x, floor - 2, z, x, G - 2 - i, z, SiegeKit.fill(random));
                canvas.set(x, G - 1 - i, z, Blocks.stairs(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, BlockFace.WEST));
            }
            for (int z : new int[] {SiegeLayout.CANAL_Z1 - 1, SiegeLayout.CANAL_Z2 + 1}) {
                canvas.fill(x, floor + 1, z, x, G, z, BlockSpec.of(Material.DEEPSLATE_TILES));
                canvas.set(x, G + 1, z, BlockSpec.of(Material.IRON_BARS));
            }
        }
        for (int i = 0; i < 5; i++) {
            int z = SiegeLayout.CANAL_Z2 - 4 + i;
            for (int x = SiegeLayout.CANAL_X2 - 4; x <= SiegeLayout.CANAL_X2; x++) {
                canvas.fill(x, floor, z, x, floor + i, z, SiegeKit.fill(random));
                canvas.set(x, floor + 1 + i, z, Blocks.stairs(Material.POLISHED_BLACKSTONE_BRICK_STAIRS,
                        BlockFace.SOUTH));
            }
        }
        for (int x = SiegeLayout.CANAL_X2 - 4; x <= SiegeLayout.CANAL_X2; x++) {
            canvas.clear(x, G + 1, SiegeLayout.CANAL_Z2 + 1);
        }
        // Stairs down next to every crossing (on its east side, from the north ledge).
        for (int[] crossing : CANAL_CROSSINGS) {
            for (int x = crossing[1] + 1; x <= crossing[1] + 3; x++) {
                for (int i = 0; i < 5; i++) {
                    int z = SiegeLayout.CANAL_Z1 + i;
                    canvas.fill(x, floor + 1, z, x, G - 2 - i, z, SiegeKit.fill(random));
                    canvas.set(x, G - 1 - i, z, Blocks.stairs(Material.POLISHED_BLACKSTONE_BRICK_STAIRS,
                            BlockFace.NORTH));
                }
            }
        }
    }

    static boolean crossing(int x) {
        for (int[] crossing : CANAL_CROSSINGS) {
            if (x >= crossing[0] && x <= crossing[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean nextToCrossing(int x) {
        for (int[] crossing : CANAL_CROSSINGS) {
            if (x > crossing[1] && x <= crossing[1] + 3) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the skyline

    /**
     * The ring of skyscrapers around the district: hollow towers 36-58 blocks high with lit windows, neon edges and
     * billboards facing inwards. Nobody can climb them; they are the map's border.
     */
    private static void skyline(MapCanvas canvas) {
        Random random = canvas.random("skyline");
        int r = SiegeLayout.BUILD_RADIUS;
        int w = SiegeLayout.HALF_WIDTH;
        int side = SiegeLayout.SIDE;
        int east = SiegeLayout.EAST_END;
        // North and south rows (along x), then west and east rows (along z).
        for (int sign : new int[] {-1, 1}) {
            int x = -r;
            while (x <= r) {
                int len = Math.min(r - x + 1, 12 + random.nextInt(16));
                int zIn = sign * (side + 1);
                int zOut = sign * w;
                tower(canvas, random, x, Math.min(zIn, zOut), x + len - 1, Math.max(zIn, zOut),
                        sign < 0 ? BlockFace.SOUTH : BlockFace.NORTH);
                x += len;
            }
            int z = -side;
            while (z <= side) {
                int len = Math.min(side - z + 1, 12 + random.nextInt(16));
                int xIn = sign * (east + 1);
                int xOut = sign * r;
                tower(canvas, random, Math.min(xIn, xOut), z, Math.max(xIn, xOut), z + len - 1,
                        sign < 0 ? BlockFace.EAST : BlockFace.WEST);
                z += len;
            }
        }
    }

    private static void tower(MapCanvas canvas, Random random, int x1, int z1, int x2, int z2, BlockFace inward) {
        SiegeKit.Facade look = SiegeKit.facade(random);
        int top = G + 36 + random.nextInt(23);
        top = Math.min(top, SiegeLayout.MAX_Y - 3);
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean shell = x == x1 || x == x2 || z == z1 || z == z2;
                if (!shell) {
                    canvas.set(x, top, z, BlockSpec.of(Material.POLISHED_BLACKSTONE));
                    continue;
                }
                boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                boolean face = inward == BlockFace.SOUTH && z == z2 || inward == BlockFace.NORTH && z == z1
                        || inward == BlockFace.EAST && x == x2 || inward == BlockFace.WEST && x == x1;
                int along = inward.getModZ() != 0 ? x - x1 : z - z1;
                for (int y = G + 1; y <= top; y++) {
                    BlockSpec spec;
                    if (corner) {
                        spec = face && y % 2 == 0 ? SiegeKit.glow(look.neon()) : BlockSpec.of(look.trim());
                    } else if (!face) {
                        spec = BlockSpec.of(look.wall());
                    } else if (y <= G + 4) {
                        spec = y == G + 4 ? SiegeKit.concrete(look.neon()) : BlockSpec.of(look.wall());
                    } else if (Math.floorMod(y - G, 4) == 0) {
                        spec = BlockSpec.of(look.trim());
                    } else if (Math.floorMod(along, 3) == 0) {
                        spec = BlockSpec.of(look.wall());
                    } else {
                        int roll = random.nextInt(14);
                        spec = roll == 0 ? BlockSpec.of(Material.OCHRE_FROGLIGHT) : roll == 1
                                ? BlockSpec.of(Material.SEA_LANTERN) : roll == 2 ? SiegeKit.glass(look.neon())
                                : BlockSpec.of(look.window());
                    }
                    canvas.set(x, y, z, spec);
                }
                canvas.set(x, top + 1, z, Blocks.slab(Material.POLISHED_BLACKSTONE_SLAB));
            }
        }
        // A big billboard on some inner faces.
        int len = inward.getModZ() != 0 ? x2 - x1 + 1 : z2 - z1 + 1;
        if (len >= 14 && random.nextInt(3) == 0) {
            int width = len - 4;
            int height = 7 + random.nextInt(5);
            int y1 = G + 14 + random.nextInt(Math.max(1, top - G - height - 18));
            if (inward.getModZ() != 0) {
                int c = inward == BlockFace.SOUTH ? z2 : z1;
                SiegeKit.board(canvas, random, x1 + 2, y1, c - inward.getModZ(), width, height, true,
                        inward.getModZ(), SiegeKit.neon(random), SiegeKit.neon(random));
            } else {
                int c = inward == BlockFace.EAST ? x2 : x1;
                SiegeKit.board(canvas, random, z1 + 2, y1, c - inward.getModX(), width, height, false,
                        inward.getModX(), SiegeKit.neon(random), SiegeKit.neon(random));
            }
        }
    }

    // ------------------------------------------------------------------ highway and monorail

    /** The elevated highway over the attackers' spawn, on pairs of pillars. */
    private static void highway(MapCanvas canvas) {
        Random random = canvas.random("highway");
        int x1 = -152;
        int x2 = -138;
        int deck = G + 16;
        int side = SiegeLayout.SIDE;
        for (int x = x1; x <= x2; x++) {
            for (int z = -side; z <= side; z++) {
                canvas.set(x, deck - 1, z, Math.floorMod(z, 6) == 0 && (x == x1 + 3 || x == x2 - 3)
                        ? BlockSpec.of(Material.SEA_LANTERN) : BlockSpec.of(Material.POLISHED_DEEPSLATE));
                boolean edge = x == x1 || x == x2;
                canvas.set(x, deck, z, edge ? BlockSpec.of(Material.GRAY_CONCRETE) : x == (x1 + x2) / 2
                        && Math.floorMod(z, 6) < 3 ? BlockSpec.of(Material.YELLOW_CONCRETE) : SiegeKit.asphalt(random));
                if (edge) {
                    canvas.set(x, deck + 1, z, BlockSpec.of(Material.ANDESITE_WALL));
                }
            }
            // Neon fascia along the deck.
            canvas.set(x, deck - 1, -side, SiegeKit.glow("MAGENTA"));
        }
        for (int z = -side; z <= side; z++) {
            canvas.set(x1 - 1, deck - 1, z, Math.floorMod(z, 2) == 0 ? SiegeKit.glow("MAGENTA")
                    : BlockSpec.of(Material.BLACK_CONCRETE));
            canvas.set(x2 + 1, deck - 1, z, Math.floorMod(z, 2) == 0 ? SiegeKit.glow("CYAN")
                    : BlockSpec.of(Material.BLACK_CONCRETE));
        }
        for (int z = -side + 6; z <= side - 6; z += 20) {
            if (Math.abs(z) <= SiegeLayout.MAIN_HALF + 2 || z >= SiegeLayout.CANAL_Z1 - 3
                    && z <= SiegeLayout.CANAL_Z2 + 3) {
                continue;
            }
            for (int px : new int[] {x1 + 2, x2 - 3}) {
                for (int y = G + 1; y < deck - 1; y++) {
                    canvas.fill(px, y, z, px + 1, y, z + 1, y % 4 == 0 ? SiegeKit.glow("CYAN")
                            : BlockSpec.of(Material.GRAY_CONCRETE));
                }
            }
        }
        // Wrecks and a gantry sign on the deck.
        SiegeKit.car(canvas, random, x1 + 3, -30, false);
        SiegeKit.car(canvas, random, x1 + 8, 20, false);
        for (int z = -14; z <= 14; z++) {
            canvas.set(x2 + 1, deck + 1, z, BlockSpec.of(Material.BLACK_CONCRETE));
        }
    }

    /** The monorail over the main avenue: a beam on pillars from the west skyline to a station on the podium. */
    private static void monorail(MapCanvas canvas) {
        int y = G + 26;
        int end = 104;
        for (int x = SiegeLayout.WEST_END; x <= end; x++) {
            for (int z = -1; z <= 0; z++) {
                canvas.set(x, y, z, BlockSpec.of(Material.POLISHED_BLACKSTONE));
                canvas.set(x, y - 1, z, Math.floorMod(x, 4) == 0 ? SiegeKit.glow("CYAN")
                        : BlockSpec.of(Material.BLACK_CONCRETE));
            }
            canvas.set(x, y + 1, -1, BlockSpec.of(Material.IRON_BARS));
            canvas.set(x, y + 1, 0, BlockSpec.of(Material.IRON_BARS));
        }
        for (int px : new int[] {-144, -112, -84, -24, 4, 60}) {
            for (int yy = G + 1; yy < y - 1; yy++) {
                for (int dx = 0; dx <= 1; dx++) {
                    for (int z = -1; z <= 0; z++) {
                        canvas.set(px + dx, yy, z, yy % 5 == 0 ? SiegeKit.glow("PURPLE")
                                : BlockSpec.of(Material.GRAY_CONCRETE));
                    }
                }
            }
        }
        // The station on the podium: legs, a platform roof with a sign.
        int py = SiegeLayout.PODIUM_Y;
        for (int lx : new int[] {98, 104}) {
            for (int lz : new int[] {-4, 3}) {
                for (int yy = py + 1; yy < y - 1; yy++) {
                    canvas.set(lx, yy, lz, BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
                }
            }
        }
        for (int x = 98; x <= 104; x++) {
            for (int z = -4; z <= 3; z++) {
                canvas.set(x, y - 1, z, BlockSpec.of(Material.POLISHED_DEEPSLATE));
                canvas.set(x, y + 4, z, z == -4 || z == 3 ? SiegeKit.concrete("CYAN")
                        : BlockSpec.of(Material.BLACK_STAINED_GLASS));
            }
        }
        // A train parked over the firewall district.
        for (int x = -40; x <= -20; x++) {
            for (int z = -2; z <= 1; z++) {
                for (int yy = y + 1; yy <= y + 3; yy++) {
                    boolean window = yy == y + 2;
                    canvas.set(x, yy, z, window ? BlockSpec.of(Material.CYAN_STAINED_GLASS)
                            : yy == y + 1 ? SiegeKit.glow("MAGENTA") : BlockSpec.of(Material.WHITE_CONCRETE));
                }
            }
        }
    }

    // ------------------------------------------------------------------ platforms

    /** A ring of light in the district's colour just outside the platform: where the tower's defence zone ends. */
    private static void towerRing(MapCanvas canvas, Tower tower) {
        String colour = SiegeWayfinding.accentAt(tower.x(), tower.z());
        int r = tower.radius() + 1;
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dz = -r - 1; dz <= r + 1; dz++) {
                if ((int) Math.round(Math.hypot(dx, dz)) != r) {
                    continue;
                }
                canvas.set(tower.x() + dx, tower.y() - 1, tower.z() + dz, SiegeKit.glass(colour));
                canvas.set(tower.x() + dx, tower.y() - 2, tower.z() + dz, SiegeKit.glow(colour));
            }
        }
    }

    /** The tower platform: nothing but the floor within the radius, high above, for the model and the fight. */
    private static void clearPlatform(MapCanvas canvas, Tower tower) {
        int r = tower.radius();
        double limit = (r + 0.5) * (r + 0.5);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > limit) {
                    continue;
                }
                for (int y = tower.y(); y <= tower.y() + SiegeLayout.TOWER_CLEARANCE; y++) {
                    if (canvas.get(tower.x() + dx, y, tower.z() + dz) != null) {
                        canvas.clear(tower.x() + dx, y, tower.z() + dz);
                    }
                }
            }
        }
    }
}
