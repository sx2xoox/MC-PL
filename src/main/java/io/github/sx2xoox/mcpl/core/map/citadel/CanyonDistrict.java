package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/** The canyon district (west-northwest): a rocky gorge crossed by bridges. (Not built yet: plain ground.) */
final class CanyonDistrict implements MapZone {

    @Override
    public String name() {
        return "canyon";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isDistrict(CitadelLayout.District.CANYON, x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        // TODO: build the canyon district.
    }
}
