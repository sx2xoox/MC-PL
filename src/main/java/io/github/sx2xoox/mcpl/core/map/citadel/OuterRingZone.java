package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/** The outer ring: the broken curtain wall with gates where the roads arrive, and the hills beyond the play area. (Not built yet: plain ground.) */
final class OuterRingZone implements MapZone {

    @Override
    public String name() {
        return "outer";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isOuterRing(x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        // TODO: build the outer ring (r > 96).
    }
}
