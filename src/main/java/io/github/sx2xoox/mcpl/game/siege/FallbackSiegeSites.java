package io.github.sx2xoox.mcpl.game.siege;

import java.util.List;

import io.github.sx2xoox.mcpl.core.map.siege.SiegeSites;

/**
 * A plain straight layout used when no 공성전 map is registered (and by the tests): attackers at {@code z = -36},
 * the towers on the {@code z} axis (front {@code -5}, middle {@code 12}, core {@code 28}) and the defenders behind
 * each tower, two data nodes beside the front tower and the big objective in front of it. Every spot stands at
 * {@code feetY}; {@link SiegeLayout} places it around the hub's arena center.
 */
public final class FallbackSiegeSites implements SiegeSites {

    private final int feetY;

    public FallbackSiegeSites(int feetY) {
        this.feetY = feetY;
    }

    @Override
    public List<Spot> attackerSpawns() {
        return row(-36, 0f);
    }

    @Override
    public List<List<Spot>> defenderSpawns() {
        return List.of(row(8, 180f), row(22, 180f), row(36, 180f));
    }

    @Override
    public List<Tower> towers() {
        return List.of(new Tower("front", "성문 탑", 0, feetY, -5, 6),
                new Tower("middle", "중앙 탑", 0, feetY, 12, 6),
                new Tower("core", "코어", 0, feetY, 28, 6));
    }

    @Override
    public List<Objective> objectives() {
        return List.of(new Objective("north", "북쪽 데이터 노드", -14, feetY, 4, 3, false),
                new Objective("canal", "수로 데이터 노드", 14, feetY, 4, 3, false),
                new Objective("center", "오버클럭 코어", 0, feetY, -20, 4, true));
    }

    private List<Spot> row(int z, float yaw) {
        return List.of(new Spot(-3, feetY, z, yaw), new Spot(0, feetY, z, yaw), new Spot(3, feetY, z, yaw));
    }
}
