package io.github.sx2xoox.mcpl.game.siege.lanes;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;

/**
 * A flat stand-in for the 장난감 왕국 map: BLUE base at x = −64, RED at +64, the TOP lane straight along z = −20, the
 * BOTTOM lane along z = +20, each side's jungle between them and the dragon in the centre. Everything stands on
 * {@code y} (the flat test world's surface).
 */
public final class TestLaneSites implements LaneSites {

    public static final int TOP_Z = -20;
    public static final int BOTTOM_Z = 20;
    public static final int BASE_X = 64;

    private final int y;

    public TestLaneSites(int y) {
        this.y = y;
    }

    private static int sign(Side side) {
        return side == Side.BLUE ? -1 : 1;
    }

    @Override
    public List<Spot> spawns(Side side) {
        List<Spot> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            out.add(new Spot(sign(side) * (BASE_X + 2), y, i - 4, side == Side.BLUE ? -90 : 90));
        }
        return out;
    }

    @Override
    public Spot shop(Side side) {
        return new Spot(sign(side) * (BASE_X - 2), y, 0, side == Side.BLUE ? -90 : 90);
    }

    @Override
    public List<Structure> towers(Side side, Lane lane) {
        int z = lane == Lane.TOP ? TOP_Z : BOTTOM_Z;
        String prefix = side.name().toLowerCase() + "-" + lane.name().toLowerCase();
        return List.of(new Structure(prefix + "-outer", "바깥 타워", sign(side) * 30, y, z, 3),
                new Structure(prefix + "-inner", "안쪽 타워", sign(side) * 48, y, z, 3));
    }

    @Override
    public Structure core(Side side) {
        return new Structure(side.name().toLowerCase() + "-core", "코어", sign(side) * (BASE_X + 4), y, 0, 4);
    }

    @Override
    public List<Point> lanePath(Lane lane) {
        int z = lane == Lane.TOP ? TOP_Z : BOTTOM_Z;
        List<Point> out = new ArrayList<>();
        for (int x = -60; x <= 60; x += 10) {
            out.add(new Point(x, y, z));
        }
        return out;
    }

    @Override
    public List<Objective> objectives(Side side) {
        int s = sign(side);
        String p = side.name().toLowerCase() + "-";
        return List.of(
                new Objective(p + "jelly", ObjectiveKind.JELLY, "젤리 무리", s * 40, y, 0, 3),
                new Objective(p + "mice", ObjectiveKind.MICE, "태엽 쥐 떼", s * 25, y, -8, 3),
                new Objective(p + "teddy", ObjectiveKind.TEDDY, "곰인형 대장", s * 25, y, 8, 3),
                new Objective(p + "rabbit", ObjectiveKind.RABBIT, "시계 토끼", s * 12, y, 0, 3),
                new Objective(p + "fountain", ObjectiveKind.FOUNTAIN, "사탕 샘물", s * 52, y, 8, 3),
                new Objective(p + "telescope", ObjectiveKind.TELESCOPE, "망원경 탑", s * 52, y, -8, 3));
    }

    @Override
    public Objective epic() {
        return new Objective("dragon", ObjectiveKind.DRAGON, "장난감 드래곤", 0, y, 0, 5);
    }
}
