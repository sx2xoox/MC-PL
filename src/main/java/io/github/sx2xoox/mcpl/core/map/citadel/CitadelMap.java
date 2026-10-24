package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.List;
import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.ArenaMap;
import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.bukkit.Material;

/**
 * 폐허의 성채: a ruined fantasy citadel about 210 blocks across. The keep stands in the middle, six districts
 * (a ruined town, a forest, a lake, a canyon, a graveyard with a temple, hills with watchtowers) surround it between
 * radial roads, and a broken curtain wall closes the play area. See {@link CitadelLayout} for the plan.
 *
 * <p>The map builds the shared ground and the roads, then lets every zone build its own columns on a restricted
 * canvas, so zones can never overwrite each other or the roads.
 */
public final class CitadelMap implements ArenaMap {

    private static final BlockSpec GRASS = BlockSpec.of(Material.GRASS_BLOCK);
    private static final BlockSpec DIRT = BlockSpec.of(Material.DIRT);
    private static final BlockSpec STONE = BlockSpec.of(Material.STONE);

    private final List<MapZone> zones = List.of(
            new KeepZone(),
            new TownDistrict(),
            new ForestDistrict(),
            new LakeDistrict(),
            new CanyonDistrict(),
            new GraveyardDistrict(),
            new HillsDistrict(),
            new OuterRingZone());

    @Override
    public String id() {
        return "citadel";
    }

    @Override
    public String displayName() {
        return "폐허의 성채";
    }

    @Override
    public String description() {
        return "무너진 성채를 중심으로 마을, 숲, 호수, 협곡, 묘지, 언덕이 둘러싼 지름 약 210블록의 전장";
    }

    @Override
    public int playRadius() {
        return CitadelLayout.PLAY_RADIUS;
    }

    @Override
    public int buildRadius() {
        return CitadelLayout.BUILD_RADIUS;
    }

    @Override
    public int groundY() {
        return CitadelLayout.GROUND_Y;
    }

    @Override
    public int minY() {
        return CitadelLayout.MIN_Y;
    }

    @Override
    public int maxY() {
        return CitadelLayout.MAX_Y;
    }

    @Override
    public long seed() {
        return 0x5EEDC17ADE1L;
    }

    @Override
    public int version() {
        return 2;
    }

    /** The zones in build order (exposed for tests and previews). */
    public List<MapZone> zones() {
        return zones;
    }

    @Override
    public void build(MapCanvas canvas) {
        ground(canvas);
        roads(canvas);
        for (MapZone zone : zones) {
            zone.build(canvas.restrictTo(zone::contains, zone.name()));
        }
    }

    /** The island: grass on dirt on stone, with a ragged underside. */
    private static void ground(MapCanvas canvas) {
        Random random = canvas.random("ground");
        int top = CitadelLayout.GROUND_Y;
        int r = CitadelLayout.BUILD_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (CitadelLayout.radius(x, z) > r) {
                    continue;
                }
                int bottom = top - 8 - random.nextInt(3);
                for (int y = bottom; y <= top - 4; y++) {
                    canvas.set(x, y, z, STONE);
                }
                canvas.fill(x, top - 3, z, x, top - 1, z, DIRT);
                canvas.set(x, top, z, GRASS);
            }
        }
    }

    /** The cobbled ring road around the keep and the six dirt roads out to the wall. */
    private static void roads(MapCanvas canvas) {
        Random random = canvas.random("roads");
        int top = CitadelLayout.GROUND_Y;
        int r = CitadelLayout.WALL_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (CitadelLayout.isRingRoad(x, z)) {
                    canvas.set(x, top, z, pick(random, RING_ROAD));
                } else if (CitadelLayout.radius(x, z) > CitadelLayout.RING_ROAD_OUTER
                        && CitadelLayout.radius(x, z) <= r
                        && CitadelLayout.nearestRoadDistance(x, z) <= CitadelLayout.ROAD_HALF_WIDTH) {
                    canvas.set(x, top, z, pick(random, DIRT_ROAD));
                }
            }
        }
    }

    private static final Material[] RING_ROAD = {Material.COBBLESTONE, Material.COBBLESTONE, Material.COBBLESTONE,
            Material.MOSSY_COBBLESTONE, Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.ANDESITE,
            Material.GRAVEL};

    private static final Material[] DIRT_ROAD = {Material.DIRT_PATH, Material.DIRT_PATH, Material.DIRT_PATH,
            Material.COARSE_DIRT, Material.COARSE_DIRT, Material.GRAVEL, Material.COBBLESTONE};

    private static BlockSpec pick(Random random, Material[] materials) {
        return BlockSpec.of(materials[random.nextInt(materials.length)]);
    }
}
