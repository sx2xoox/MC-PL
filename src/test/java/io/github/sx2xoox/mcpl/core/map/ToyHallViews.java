package io.github.sx2xoox.mcpl.core.map;

import java.io.File;
import java.io.IOException;

import io.github.sx2xoox.mcpl.core.map.MapPreview.Camera;

/**
 * Previews of the clockwork hall around 장난감 왕국 ({@code ./gradlew renderMaps -Pmap=toykingdom}): the whole map from
 * outside (isometric and from the air) and, as a player sees it from the kingdom's floor, looking up at the clock, up
 * at the ceiling straight overhead, at a gate wall, at the clock bay behind a castle and into a corner.
 */
final class ToyHallViews {

    private ToyHallViews() {
    }

    /** Eye height of a player standing on the ground (y 65). */
    private static final double EYE = 65 + 1.62;

    static void write(MapCanvas canvas, File out) throws IOException {
        MapPreview preview = new MapPreview(canvas);
        int r = canvas.radius();
        if (System.getenv().getOrDefault("MCPL_HALL_VIEWS", "").isEmpty()) {
            MapPreview.write(preview.isometric(-r, -r, r, r, 6, (x, z) -> true),
                    new File(out, "toykingdom-hall-iso.png"));
        }
        long started = System.nanoTime();
        String only = System.getenv().getOrDefault("MCPL_HALL_VIEWS", "");
        for (View view : VIEWS) {
            if (!only.isEmpty() && !java.util.Arrays.asList(only.split(",")).contains(view.name())) {
                continue;
            }
            MapPreview.write(preview.perspective(view.camera(), view.width(), view.height()),
                    new File(out, "toykingdom-hall-" + view.name() + ".png"));
        }
        System.out.println("[previews] toykingdom hall views in " + (System.nanoTime() - started) / 1_000_000 + " ms");
    }

    /** A named view and its picture size. */
    record View(String name, Camera camera, int width, int height) {
    }

    static final View[] VIEWS = {
        new View("up", look(-10.5, EYE, 48.5, 0.5, 118, -20, 100), 1920, 1080),
        new View("zenith", new Camera(0.5, EYE, 0.5, 0, 89.9, 125), 1600, 1200),
        new View("wall", look(0.5, EYE + 12, 18.5, 0.5, 92, -62, 88), 1920, 1080),
        new View("end", look(36.5, EYE + 6, 0.5, 110, 100, 0.5, 96), 1920, 1080),
        new View("corner", look(-20.5, EYE, 47.5, -100, 95, 62, 100), 1920, 1080),
        new View("aerial", look(190, 225, 175, 0, 90, 0, 60), 1920, 1080),
    };

    /** A camera at one point looking at another. */
    static Camera look(double x, double y, double z, double tx, double ty, double tz, double fov) {
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return new Camera(x, y, z, yaw, pitch, fov);
    }
}
