package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.List;
import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.ArenaMap;
import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * 협곡 요새, the 초능력 공성전 map (docs/siege.md §5): a gorge about 175 x 80 blocks between high cliffs. The attackers
 * start in a walled camp at the west end, cross a siege field and a river gorge, break through the outer wall to the
 * 성문 탑, through the inner wall to the 중앙 탑 and up onto the keep plateau to the 코어. Three lanes (main road,
 * north flank, south flank) meet at every tower and split again; see {@link SiegeLayout} for the plan.
 *
 * <p>The map builds the ground and the cliffs, lets every zone build its own columns on a restricted canvas, then adds
 * what crosses zones (the tunnel of the south flank) and clears the tower platforms last, so nothing ever stands on
 * them.
 */
public final class SiegeMap implements ArenaMap, SiegeSites {

    private final List<MapZone> zones = List.of(
            new SiegeCampZone(),
            new SiegeRiverZone(),
            new SiegeGateZone(),
            new SiegeCourtZone(),
            new SiegeKeepZone());

    private static final List<Tower> TOWERS = List.of(
            new Tower("front", "성문 탑", SiegeLayout.FRONT_X, SiegeLayout.GROUND_Y + 1, 0, SiegeLayout.TOWER_RADIUS),
            new Tower("middle", "중앙 탑", SiegeLayout.MIDDLE_X, SiegeLayout.GROUND_Y + 1, 0,
                    SiegeLayout.TOWER_RADIUS),
            new Tower("core", "코어", SiegeLayout.CORE_X, SiegeLayout.PLATEAU_Y + 1, 0, SiegeLayout.TOWER_RADIUS));

    private static final List<Spot> ATTACKERS = grid(new int[] {-81, -78}, new int[] {-6, -2, 2, 6},
            SiegeLayout.GROUND_Y + 1, SiegeLayout.FRONT_X, 0);

    private static final List<List<Spot>> DEFENDERS = List.of(
            grid(new int[] {16, 18, 20}, new int[] {10, 12, 14}, SiegeLayout.GROUND_Y + 1, SiegeLayout.FRONT_X, 0)
                    .stream().filter(s -> !(s.x() == 18 && s.z() == 12)).toList(),
            grid(new int[] {46, 48, 50, 52}, new int[] {-29, -26}, SiegeLayout.GROUND_Y + 1, SiegeLayout.MIDDLE_X, 0),
            grid(new int[] {82, 84}, new int[] {-3, -1, 1, 3}, SiegeLayout.PLATEAU_Y + 1, SiegeLayout.CORE_X, 0));

    /** Spawn spots on a grid (every combination of {@code xs} and {@code zs}), all looking at one place. */
    private static List<Spot> grid(int[] xs, int[] zs, int y, int lookX, int lookZ) {
        List<Spot> spots = new java.util.ArrayList<>();
        for (int x : xs) {
            for (int z : zs) {
                spots.add(spot(x, y, z, lookX, lookZ));
            }
        }
        return List.copyOf(spots);
    }

    private static Spot spot(int x, int y, int z, int lookX, int lookZ) {
        return new Spot(x, y, z, SiegeLayout.yaw(x + 0.5, z + 0.5, lookX + 0.5, lookZ + 0.5));
    }

    @Override
    public String id() {
        return "siege";
    }

    @Override
    public String displayName() {
        return "협곡 요새";
    }

    @Override
    public String description() {
        return "강이 흐르는 협곡 끝의 요새: 성문 탑, 중앙 탑, 코어를 향해 큰길과 좌우 우회로가 이어지는 공성전 전용 맵";
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
        return SiegeLayout.GROUND_Y;
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
        return 0x51E6E5L;
    }

    @Override
    public int version() {
        return 1;
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

    /** The zones in build order (exposed for tests and previews). */
    public List<MapZone> zones() {
        return zones;
    }

    @Override
    public void build(MapCanvas canvas) {
        ground(canvas);
        for (MapZone zone : zones) {
            zone.build(canvas.restrictTo(zone::contains, zone.name()));
        }
        tunnel(canvas);
        for (Tower tower : TOWERS) {
            clearPlatform(canvas, tower);
        }
        flora(canvas);
    }

    // ------------------------------------------------------------------ ground and cliffs

    private static void ground(MapCanvas canvas) {
        Random random = canvas.random("ground");
        int r = SiegeLayout.BUILD_RADIUS;
        int w = SiegeLayout.HALF_WIDTH;
        int bottom = SiegeLayout.MIN_Y + 4;
        for (int x = -r; x <= r; x++) {
            for (int z = -w; z <= w; z++) {
                if (SiegeLayout.isInterior(x, z)) {
                    int top = SiegeLayout.ground(x, z);
                    for (int y = bottom; y <= top - 4; y++) {
                        canvas.set(x, y, z, SiegeKit.rock(random, y));
                    }
                    canvas.fill(x, top - 3, z, x, top - 1, z, BlockSpec.of(Material.DIRT));
                    canvas.set(x, top, z, random.nextInt(14) == 0 ? BlockSpec.of(Material.COARSE_DIRT)
                            : BlockSpec.of(Material.GRASS_BLOCK));
                } else {
                    int top = SiegeLayout.cliffTop(x, z);
                    for (int y = bottom; y < top; y++) {
                        canvas.set(x, y, z, SiegeKit.rock(random, y));
                    }
                    int roll = random.nextInt(10);
                    canvas.set(x, top, z, BlockSpec.of(roll < 6 ? Material.GRASS_BLOCK : roll < 8 ? Material.PODZOL
                            : Material.COARSE_DIRT));
                }
            }
        }
        // Mossy ledges and trees along the cliff tops, well back from the valley.
        Random trees = canvas.random("cliff-trees");
        for (int x = -r + 1; x < r; x++) {
            for (int z = -w + 1; z < w; z++) {
                if (SiegeLayout.isInterior(x, z) || nearInterior(x, z, 4) || trees.nextInt(30) != 0) {
                    continue;
                }
                int top = SiegeLayout.cliffTop(x, z);
                if (top + 12 > SiegeLayout.MAX_Y) {
                    continue;
                }
                SiegeKit.spruce(canvas, x, top, z, 6 + trees.nextInt(4), trees);
            }
        }
        // Rock texture on the cliff faces: protruding blocks would make steps, so only swap materials.
        for (int x = -r; x <= r; x++) {
            for (int z = -w; z <= w; z++) {
                if (SiegeLayout.isInterior(x, z) || !nearInterior(x, z, 1)) {
                    continue;
                }
                int top = SiegeLayout.cliffTop(x, z);
                for (int y = SiegeLayout.GROUND_Y + 1; y < top; y++) {
                    if (random.nextInt(7) == 0) {
                        canvas.set(x, y, z, BlockSpec.of(random.nextBoolean() ? Material.MOSSY_COBBLESTONE
                                : Material.COBBLESTONE));
                    }
                }
            }
        }
    }

    /** True if an interior column lies within {@code distance} (square) of a cliff column. */
    static boolean nearInterior(int x, int z, int distance) {
        for (int dx = -distance; dx <= distance; dx++) {
            for (int dz = -distance; dz <= distance; dz++) {
                if (SiegeLayout.isInterior(x + dx, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the south tunnel

    /**
     * The smugglers' tunnel of the south flank: from the ford landing on the east bank of the gorge, under the
     * killing ground and the outer wall, up a stairwell into the outer bailey next to the 성문 탑.
     */
    private static void tunnel(MapCanvas canvas) {
        Random random = canvas.random("tunnel");
        int floor = SiegeLayout.TUNNEL_FLOOR;
        int z1 = SiegeLayout.FORD_Z - 1;
        int z2 = SiegeLayout.FORD_Z + 1;
        int start = SiegeRiverZone.eastLandingX2() + 1;
        int stairStart = -9;
        for (int x = start; x < stairStart; x++) {
            for (int z = z1 - 1; z <= z2 + 1; z++) {
                boolean wall = z < z1 || z > z2;
                canvas.set(x, floor, z, wall ? SiegeKit.rubble(random) : SiegeKit.pick(random, Material.COBBLESTONE,
                        Material.GRAVEL, Material.COARSE_DIRT, Material.STONE_BRICKS));
                for (int y = floor + 1; y <= floor + 3; y++) {
                    if (wall) {
                        canvas.set(x, y, z, random.nextInt(4) == 0 ? SiegeKit.rock(random, y)
                                : SiegeKit.masonry(random));
                    } else {
                        canvas.clear(x, y, z);
                    }
                }
                canvas.set(x, floor + 4, z, Math.floorMod(x, 5) == 0 ? BlockSpec.of(Material.SHROOMLIGHT)
                        : BlockSpec.of(Material.SPRUCE_PLANKS));
            }
            // Mine props every four blocks: posts and a beam.
            if (Math.floorMod(x, 4) == 2) {
                for (int y = floor + 1; y <= floor + 3; y++) {
                    canvas.set(x, y, z1 - 1, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
                    canvas.set(x, y, z2 + 1, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
                }
                canvas.fill(x, floor + 4, z1 - 1, x, floor + 4, z2 + 1, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
            }
            if (Math.floorMod(x, 8) == 4) {
                canvas.set(x, floor + 2, z1, Blocks.wallTorch(false, BlockFace.SOUTH));
            }
        }
        // The stairwell: six steps up to the bailey floor, open to the sky, with a low wall around.
        int ground = SiegeLayout.GROUND_Y;
        for (int i = 0; i <= 5; i++) {
            int x = stairStart + i;
            int top = floor + 1 + i;
            for (int z = z1; z <= z2; z++) {
                canvas.fill(x, floor, z, x, top - 1, z, SiegeKit.masonry(random));
                canvas.set(x, top, z, i == 5 ? SiegeKit.masonry(random)
                        : Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
                for (int y = top + 1; y <= ground + 3; y++) {
                    canvas.clear(x, y, z);
                }
            }
            for (int z : new int[] {z1 - 1, z2 + 1}) {
                canvas.fill(x, floor, z, x, ground, z, SiegeKit.masonry(random));
                canvas.set(x, ground + 1, z, i % 2 == 0 ? BlockSpec.of(Material.STONE_BRICK_WALL)
                        : BlockSpec.of(Material.COBBLESTONE_WALL));
                canvas.clear(x, ground + 2, z);
                canvas.clear(x, ground + 3, z);
            }
        }
        // The back of the stairwell, over the tunnel mouth.
        for (int z = z1 - 1; z <= z2 + 1; z++) {
            canvas.fill(stairStart - 1, floor + 4, z, stairStart - 1, ground, z, SiegeKit.masonry(random));
            canvas.set(stairStart - 1, ground + 1, z, BlockSpec.of(Material.STONE_BRICK_WALL));
        }
        canvas.set(stairStart - 1, ground + 2, z1 - 1, Blocks.lantern(false, false));
        canvas.set(stairStart - 1, ground + 2, z2 + 1, Blocks.lantern(false, false));
    }

    // ------------------------------------------------------------------ platforms and plants

    /** The tower platform: nothing but the floor within the radius, for the crystal and the fight around it. */
    private static void clearPlatform(MapCanvas canvas, Tower tower) {
        int r = tower.radius();
        double limit = (r + 0.5) * (r + 0.5);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > limit) {
                    continue;
                }
                for (int y = tower.y(); y <= tower.y() + 14 && y <= SiegeLayout.MAX_Y; y++) {
                    if (canvas.get(tower.x() + dx, y, tower.z() + dz) != null) {
                        canvas.clear(tower.x() + dx, y, tower.z() + dz);
                    }
                }
            }
        }
    }

    private static void flora(MapCanvas canvas) {
        Random random = canvas.random("flora");
        int r = SiegeLayout.BUILD_RADIUS;
        int w = SiegeLayout.HALF_WIDTH;
        // On the valley floor (not on the tower platforms), and some on the cliff tops.
        MapCanvas valley = canvas.restrictTo((x, z) -> {
            for (Tower tower : TOWERS) {
                int dx = x - tower.x();
                int dz = z - tower.z();
                if (dx * dx + dz * dz <= (tower.radius() + 1) * (tower.radius() + 1)) {
                    return false;
                }
            }
            return true;
        }, "flora");
        SiegeKit.meadow(valley, -r, -w, r, w, 0.11, random);
    }
}
