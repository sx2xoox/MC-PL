package io.github.sx2xoox.mcpl.core.map.toykingdom;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/**
 * The middle: a shallow river along {@code x = 0} from lane to lane (one block of water on sand, so anyone can wade
 * through and step out) and, where it would cross the corridor, the toy dragon's round sandbox: sand one block down in
 * a birch frame, open to the sky.
 */
final class ToyRiverZone implements MapZone {

    @Override
    public String name() {
        return "river";
    }

    @Override
    public boolean contains(int x, int z) {
        return ToyLayout.partOf(x, z).equals(name());
    }

    @Override
    public void build(MapCanvas canvas) {
        int hz = ToyLayout.RIVER_ZONE_HALF_Z;
        int r = (int) Math.ceil(ToyLayout.RIVER_ZONE_RADIUS);
        for (int x = -r; x <= r; x++) {
            for (int z = -hz; z <= hz; z++) {
                if (contains(x, z)) {
                    ToyKit.ground(canvas, x, z);
                }
            }
        }
    }
}
