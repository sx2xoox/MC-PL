package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/** The ruined town district (east-southeast): broken houses, alleys and a market square. (Not built yet: plain ground.) */
final class TownDistrict implements MapZone {

    @Override
    public String name() {
        return "town";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isDistrict(CitadelLayout.District.TOWN, x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        // TODO: build the ruined town district.
    }
}
