package io.github.sx2xoox.mcpl.core.map.toykingdom;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/**
 * The edge of the kingdom: a wall of toy blocks (3 x 3 cells in pink, yellow, light blue and white, three high, two at
 * its outer rim) all around the walkable area, and the wooden floorboards beyond it under the clockwork hall's plinth
 * (the hall's pillars stand on the toy blocks, see {@link ToyHallZone}). In the middle of each lane the wall and the
 * floor are cut away (the drops, see {@link ToyLayout#inCliff}).
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
                if (out <= ToyLayout.HEDGE_WIDTH && !ToyLayout.inCliff(x, z)) {
                    ToyKit.brick(canvas, x, z, out <= ToyLayout.HEDGE_WIDTH - 1 ? 3 : 2, colour(x, z));
                }
            }
        }
    }

    /** The colour of the toy block wall at a column: 3 x 3 cells of the toy colours. */
    static BlockSpec colour(int x, int z) {
        int cell = Math.floorDiv(x, 3) + Math.floorDiv(z, 3);
        return ToyKit.TOY_COLOURS[Math.floorMod(cell, ToyKit.TOY_COLOURS.length)];
    }
}
