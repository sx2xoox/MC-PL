package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/** The citadel's keep, the heart of the map and the last place to fight when the border closes. (Not built yet: plain ground.) */
final class KeepZone implements MapZone {

    @Override
    public String name() {
        return "keep";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isKeep(x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        // TODO: build the keep (r <= 30).
    }
}
