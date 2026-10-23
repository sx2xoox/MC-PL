package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/** The lake district (west-southwest): a lake with islands, bridges and a ruined mill. (Not built yet: plain ground.) */
final class LakeDistrict implements MapZone {

    @Override
    public String name() {
        return "lake";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isDistrict(CitadelLayout.District.LAKE, x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        // TODO: build the lake district.
    }
}
