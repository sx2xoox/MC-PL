package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/**
 * The outer ring (r 96..124): the broken curtain wall of the citadel and the land outside it.
 *
 * <p>A crenellated curtain wall (r 98.5..102.5, nine blocks high, wall-walk at y 73) closes the districts in, with a
 * gatehouse where each of the six roads arrives (the road runs on through a vaulted passage), twelve towers (round
 * bastions, tall round and square towers, collapsed stumps), two posterns and four breaches whose rubble slopes can
 * be walked over. Stair flights, the broken ends of the breaches and ladders in every tower lead up to the wall-walk.
 * Outside, a flat apron gives way to hills with crags, woods, boulders, the wrecked trebuchets that made the breaches
 * and the besiegers' camps, rising to a jagged rock rim at the island's edge, so a player knocked past the arena border
 * lands on ground and can walk back in through a gate, tower door, postern or breach.
 *
 * <p>See {@link OuterRingPlan} for where everything stands.
 */
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
        OuterRingKit kit = new OuterRingKit(canvas, name());
        OuterRingPlan plan = new OuterRingPlan();
        OuterRingTerrain terrain = new OuterRingTerrain(plan, kit);
        terrain.shape();
        terrain.build();
        new OuterRingWall(plan, terrain, kit).build();
        OuterRingTower towers = new OuterRingTower(kit);
        for (OuterRingPlan.Tower tower : plan.towers) {
            towers.build(tower);
        }
        OuterRingGatehouse gatehouses = new OuterRingGatehouse(kit);
        for (OuterRingPlan.Gate gate : plan.gates) {
            gatehouses.build(gate);
        }
        new OuterRingDecor(plan, terrain, kit).build();
    }
}
