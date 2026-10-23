package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/** The hills district (east-northeast): rolling hills, a camp and watchtowers. (Not built yet: plain ground.) */
final class HillsDistrict implements MapZone {

    @Override
    public String name() {
        return "hills";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isDistrict(CitadelLayout.District.HILLS, x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        // TODO: build the hills district.
    }
}
