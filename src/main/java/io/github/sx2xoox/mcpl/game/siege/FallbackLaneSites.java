package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;

/**
 * A small flat two-lane layout used when the 장난감 왕국 map is not registered (and by the tests): the BLUE base at
 * {@code x = -40}, the RED base at {@code x = 40}, the TOP lane at {@code z = -14} and the BOTTOM lane at
 * {@code z = 14}, outer towers at {@code |x| = 12}, inner towers at {@code |x| = 24}, cores at {@code |x| = 34}, the
 * jungle between the lanes and the epic pit in the middle. Everything stands at {@code feetY}; {@link LaneLayout} places
 * it around the hub's arena center. Mirror-symmetric in x.
 */
public final class FallbackLaneSites implements LaneSites {

    private final int feetY;

    public FallbackLaneSites(int feetY) {
        this.feetY = feetY;
    }

    /** +1 for RED, −1 for BLUE: every x of the RED side is the BLUE x mirrored. */
    private static int sign(Side side) {
        return side == Side.RED ? 1 : -1;
    }

    @Override
    public List<Spot> spawns(Side side) {
        int s = sign(side);
        float yaw = side == Side.BLUE ? -90f : 90f;
        List<Spot> out = new ArrayList<>();
        for (int column = 0; column < 2; column++) {
            for (int z = -3; z <= 3; z += 2) {
                out.add(new Spot(s * (40 + 2 * column), feetY, z, yaw));
            }
        }
        return out;
    }

    @Override
    public Spot shop(Side side) {
        return new Spot(sign(side) * 39, feetY, 7, side == Side.BLUE ? -90f : 90f);
    }

    @Override
    public List<Structure> towers(Side side, Lane lane) {
        int s = sign(side);
        int z = lane == Lane.TOP ? -14 : 14;
        String prefix = side.name().toLowerCase(java.util.Locale.ROOT) + "_" + lane.name().toLowerCase(java.util.Locale.ROOT);
        String laneName = lane == Lane.TOP ? "위" : "아래";
        return List.of(new Structure(prefix + "_outer", laneName + " 바깥 타워", s * 12, feetY, z, 5),
                new Structure(prefix + "_inner", laneName + " 안쪽 타워", s * 24, feetY, z, 5));
    }

    @Override
    public Structure core(Side side) {
        return new Structure(side.name().toLowerCase(java.util.Locale.ROOT) + "_core", "코어", sign(side) * 34, feetY,
                0, 5);
    }

    @Override
    public List<Point> lanePath(Lane lane) {
        int z = lane == Lane.TOP ? -14 : 14;
        int bend = lane == Lane.TOP ? -16 : 16;
        return List.of(new Point(-38, feetY, z / 2), new Point(-30, feetY, z), new Point(-20, feetY, z),
                new Point(-10, feetY, z), new Point(0, feetY, bend), new Point(10, feetY, z),
                new Point(20, feetY, z), new Point(30, feetY, z), new Point(38, feetY, z / 2));
    }

    @Override
    public List<Objective> objectives(Side side) {
        int s = sign(side);
        String p = side.name().toLowerCase(java.util.Locale.ROOT) + "_";
        return List.of(new Objective(p + "jelly", ObjectiveKind.JELLY, "젤리 무리", s * 16, feetY, -5, 3),
                new Objective(p + "mice", ObjectiveKind.MICE, "태엽 쥐 떼", s * 16, feetY, 5, 3),
                new Objective(p + "teddy", ObjectiveKind.TEDDY, "곰인형 대장", s * 8, feetY, -7, 3),
                new Objective(p + "rabbit", ObjectiveKind.RABBIT, "시계 토끼", s * 8, feetY, 7, 3),
                new Objective(p + "fountain", ObjectiveKind.FOUNTAIN, "사탕 샘물", s * 21, feetY, 0, 3),
                new Objective(p + "telescope", ObjectiveKind.TELESCOPE, "망원경 탑", s * 11, feetY, 0, 2));
    }

    @Override
    public Objective epic() {
        return new Objective("dragon", ObjectiveKind.DRAGON, "장난감 드래곤", 0, feetY, 0, 5);
    }

    @Override
    public List<Region> regions() {
        return List.of(new Region("blue_base", "파랑 성", -48, -30, -10, 10),
                new Region("red_base", "빨강 성", 30, 48, -10, 10),
                new Region("river", "사탕 강", -6, 6, -10, 10));
    }
}
