package io.github.sx2xoox.mcpl.core.map;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.BiPredicate;

import io.github.sx2xoox.mcpl.core.map.citadel.CitadelMap;
import io.github.sx2xoox.mcpl.core.map.siege.SiegeLayout;
import io.github.sx2xoox.mcpl.core.map.siege.SiegeMap;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKingdomMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Writes preview images of the maps to {@code build/map-previews} when run with {@code -Dmcpl.renderMaps=true}
 * ({@code ./gradlew renderMaps}; add {@code -Pmap=<id>} for one map and {@code -Pzone=<name>} for a detailed view of
 * one zone). Skipped otherwise.
 */
class MapPreviewRender {

    @Test
    void renderPreviews() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("mcpl.renderMaps"), "only with -Dmcpl.renderMaps=true");
        MockBukkit.mock();
        try {
            String only = System.getProperty("mcpl.renderMap", "");
            String zoneName = System.getProperty("mcpl.renderZone", "");
            File out = new File("build/map-previews");
            if (only.isEmpty() || only.equals("citadel")) {
                CitadelMap map = new CitadelMap();
                int r = map.buildRadius();
                render(map, map.zones(), zoneName, out, -r, -r, r, r, map.playRadius(), 6, (x, z) -> true);
            }
            if (only.isEmpty() || only.equals("siege")) {
                SiegeMap map = new SiegeMap();
                int r = map.buildRadius();
                int w = SiegeLayout.HALF_WIDTH;
                // The isometric view leaves out the skyline in front (south and east), so the district can be seen.
                MapPreview.night = true;
                MapCanvas canvas = render(map, map.zones(), zoneName, out, -r, -w, r, w, 0, 6,
                        (x, z) -> z <= SiegeLayout.SIDE && x <= SiegeLayout.EAST_END);
                // Street views looking east (towards the towers) from each district, and the annotated guide.
                MapPreview preview = new MapPreview(canvas);
                int[] views = {-150, -100, -70, -8, 73};
                for (int x : views) {
                    MapPreview.write(preview.elevationEast(x, 70, -64, 64, SiegeLayout.CANAL_Y, 112, 6),
                            new File(out, "siege-view-" + x + ".png"));
                }
                MapPreview.night = false;
                SiegeGuideMap.write(map, canvas, new File(out, "siege-guide.png"));
            }
            if (only.isEmpty() || only.equals("toykingdom")) {
                // The kingdom under its glass dome (the views would show only glass), then the dome itself.
                ToyKingdomMap map = new ToyKingdomMap();
                int r = map.buildRadius();
                ArenaMap open = new WithoutZone(map, map.zones(), "dome");
                List<MapZone> zones = map.zones().stream().filter(z -> !z.name().equals("dome")).toList();
                MapCanvas canvas = render(open, zones, zoneName, out, -r, -68, r, 68, 0, 6, (x, z) -> true);
                ToyKingdomGuideMap.write(map, canvas, new File(out, "toykingdom-guide.png"));
                MapPreview domed = new MapPreview(map.render());
                MapPreview.write(domed.isometric(-r, -r, r, r, 6, (x, z) -> true),
                        new File(out, "toykingdom-dome-iso.png"));
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    private static MapCanvas render(ArenaMap map, List<MapZone> zones, String zoneName, File out, int minX, int minZ,
                               int maxX, int maxZ, int ring, int tile, BiPredicate<Integer, Integer> iso)
            throws IOException {
        MapCanvas canvas = map.render();
        MapPreview preview = new MapPreview(canvas);
        BiPredicate<Integer, Integer> all = (x, z) -> true;
        MapPreview.write(preview.topDown(minX, minZ, maxX, maxZ, 4, all, ring), new File(out, map.id() + "-top.png"));
        MapPreview.write(preview.isometric(minX, minZ, maxX, maxZ, tile, iso), new File(out, map.id() + "-iso.png"));
        for (MapZone zone : zones) {
            if (!zoneName.isEmpty() && !zoneName.equals(zone.name())) {
                continue;
            }
            int[] box = bounds(zone, minX, minZ, maxX, maxZ);
            MapPreview.write(preview.topDown(box[0], box[1], box[2], box[3], 8, zone::contains, 0),
                    new File(out, "zone-" + zone.name() + "-top.png"));
            MapPreview.write(preview.isometric(box[0], box[1], box[2], box[3], 16, zone::contains),
                    new File(out, "zone-" + zone.name() + "-iso.png"));
        }
        System.out.println("[previews] " + map.id() + " written to " + out.getAbsolutePath() + " ("
                + canvas.blockCount() + " blocks)");
        return canvas;
    }

    /** {minX, minZ, maxX, maxZ} of the zone's columns, with a 2-block margin. */
    private static int[] bounds(MapZone zone, int x1, int z1, int x2, int z2) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (zone.contains(x, z)) {
                    minX = Math.min(minX, x);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }
        return new int[] {minX - 2, minZ - 2, maxX + 2, maxZ + 2};
    }

    /** A map built without one of its zones (for previews of what lies under a roof). */
    private record WithoutZone(ArenaMap map, List<MapZone> zones, String left) implements ArenaMap {

        @Override
        public String id() {
            return map.id();
        }

        @Override
        public String displayName() {
            return map.displayName();
        }

        @Override
        public String description() {
            return map.description();
        }

        @Override
        public int playRadius() {
            return map.playRadius();
        }

        @Override
        public int buildRadius() {
            return map.buildRadius();
        }

        @Override
        public int groundY() {
            return map.groundY();
        }

        @Override
        public int minY() {
            return map.minY();
        }

        @Override
        public int maxY() {
            return map.maxY();
        }

        @Override
        public long seed() {
            return map.seed();
        }

        @Override
        public int version() {
            return map.version();
        }

        @Override
        public void build(MapCanvas canvas) {
            for (MapZone zone : zones) {
                if (!zone.name().equals(left)) {
                    zone.build(canvas.restrictTo(zone::contains, zone.name()));
                }
            }
        }
    }
}
