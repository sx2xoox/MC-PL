package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/** The forest district (south): old trees, boulders and undergrowth. (Not built yet: plain ground.) */
final class ForestDistrict implements MapZone {

    @Override
    public String name() {
        return "forest";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isDistrict(CitadelLayout.District.FOREST, x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        // TODO: build the forest district.
    }
}
