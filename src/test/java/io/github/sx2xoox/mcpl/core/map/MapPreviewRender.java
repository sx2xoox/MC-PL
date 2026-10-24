package io.github.sx2xoox.mcpl.core.map;

import java.io.File;
import java.io.IOException;

import io.github.sx2xoox.mcpl.core.map.citadel.CitadelLayout;
import io.github.sx2xoox.mcpl.core.map.citadel.CitadelMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Writes preview images of the maps to {@code build/map-previews} when run with {@code -Dmcpl.renderMaps=true}
 * ({@code ./gradlew renderMaps}; add {@code -Pzone=<name>} for a detailed view of one zone). Skipped otherwise.
 */
class MapPreviewRender {

    @Test
    void renderPreviews() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("mcpl.renderMaps"), "only with -Dmcpl.renderMaps=true");
        MockBukkit.mock();
        try {
            CitadelMap map = new CitadelMap();
            MapCanvas canvas = map.render();
            MapPreview preview = new MapPreview(canvas);
            File out = new File("build/map-previews");
            int r = map.buildRadius();
            MapPreview.write(preview.topDown(-r, -r, r, r, 4, (x, z) -> true, map.playRadius()),
                    new File(out, "citadel-top.png"));
            MapPreview.write(preview.isometric(-r, -r, r, r, 6, (x, z) -> true), new File(out, "citadel-iso.png"));
            String zoneName = System.getProperty("mcpl.renderZone", "");
            for (MapZone zone : map.zones()) {
                if (!zoneName.isEmpty() && !zoneName.equals(zone.name())) {
                    continue;
                }
                int[] box = bounds(zone, r);
                MapPreview.write(preview.topDown(box[0], box[1], box[2], box[3], 8, zone::contains, 0),
                        new File(out, "zone-" + zone.name() + "-top.png"));
                MapPreview.write(preview.isometric(box[0], box[1], box[2], box[3], 16, zone::contains),
                        new File(out, "zone-" + zone.name() + "-iso.png"));
            }
            System.out.println("[previews] written to " + out.getAbsolutePath() + " (" + canvas.blockCount()
                    + " blocks, play radius " + CitadelLayout.PLAY_RADIUS + ")");
        } finally {
            MockBukkit.unmock();
        }
    }

    /** {minX, minZ, maxX, maxZ} of the zone's columns, with a 2-block margin. */
    private static int[] bounds(MapZone zone, int r) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
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
}
