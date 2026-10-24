package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/**
 * The citadel's keep (r &lt;= 30), the heart of the map and the last place to fight when the border closes: a ruined
 * square keep tower in a courtyard, inside a broken bailey wall with gatehouses, towers and breaches.
 *
 * <p>Built in layers: the courtyard ground ({@link KeepGrounds}), the keep tower ({@link KeepTower}), the bailey wall
 * with its gates and towers ({@link KeepBailey}, {@link KeepBaileyTower}), then the courtyard's furniture, cover and
 * plants ({@link KeepCourtyard}).
 */
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
        Random random = canvas.random(name());
        new KeepGrounds(canvas, random).build();
        new KeepTower(canvas, random).build();
        new KeepBailey(canvas, random).build();
        new KeepCourtyard(canvas, random).build();
    }
}
