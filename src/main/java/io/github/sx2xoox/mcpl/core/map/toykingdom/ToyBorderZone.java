package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/**
 * The edge of the kingdom: a three block hedge all around the walkable area (nobody hops over it), and a strip of
 * lawn behind it so whoever is thrown over lands softly and is not at the void's edge.
 */
final class ToyBorderZone implements MapZone {

    @Override
    public String name() {
        return "border";
    }

    @Override
    public boolean contains(int x, int z) {
        return ToyLayout.partOf(x, z).equals(name());
    }

    @Override
    public void build(MapCanvas canvas) {
        int r = ToyLayout.BUILD_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (!contains(x, z)) {
                    continue;
                }
                ToyKit.ground(canvas, x, z);
                double out = ToyLayout.outline(x, z);
                if (out <= ToyLayout.HEDGE_WIDTH) {
                    int height = out <= ToyLayout.HEDGE_WIDTH - 1 ? 3 : 2;
                    for (int y = G + 1; y <= G + height; y++) {
                        canvas.set(x, y, z, ToyKit.HEDGE);
                    }
                }
            }
        }
    }
}
