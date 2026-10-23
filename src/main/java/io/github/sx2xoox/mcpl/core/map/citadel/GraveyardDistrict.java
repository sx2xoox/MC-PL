package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/** The graveyard district (north): tombs, a ruined temple and crypt walls. (Not built yet: plain ground.) */
final class GraveyardDistrict implements MapZone {

    @Override
    public String name() {
        return "graveyard";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isDistrict(CitadelLayout.District.GRAVEYARD, x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        // TODO: build the graveyard district.
    }
}
