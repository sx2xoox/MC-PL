package io.github.sx2xoox.mcpl.core.map.toykingdom;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.ArenaMap;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;

/**
 * 장난감 왕국, the 공성전 v2 map (docs/siege-v2.md §6): a calm pastel toy kingdom of about 200 x 120 blocks, mirror
 * symmetric. The BLUE castle (light blue, −x) and the RED castle (pink, +x) hold the cores; the TOP lane (yellow brick
 * road, −z) and the BOTTOM lane (light blue brick road, +z) bend out from the castles' lane gates along the edge of
 * the kingdom, two towers per side on each; the jungle between the lanes has six objective clearings per side, the
 * river runs along the middle and the toy dragon's sandbox sits in the exact centre. The kingdom is a play mat on a
 * wooden floor under a glass dome with a giant clock on top, like a toy world kept in a snow globe; in the middle of
 * each lane the mat ends in a drop into the void beyond the outer verge, and three toy-piece holes (a triangle, an
 * upside down one, a long strip) on each half of the middle corridor fall into it too. See {@link ToyLayout} for the
 * plan.
 */
public final class ToyKingdomMap implements ArenaMap, LaneSites {

    private static final int G = ToyLayout.GROUND_Y;

    private final List<MapZone> zones = List.of(
            new ToyBorderZone(),
            new ToyCastleZone(Side.BLUE),
            new ToyCastleZone(Side.RED),
            new ToyLaneZone(Lane.TOP),
            new ToyLaneZone(Lane.BOTTOM),
            new ToyJungleZone(),
            new ToyRiverZone(),
            new ToyDomeZone());

    /** The objective sites of the BLUE half: kind, Korean name, x, z, radius (RED mirrors x). */
    private record Site(ObjectiveKind kind, String name, int x, int z, int radius) {
    }

    private static final List<Site> SITES = List.of(
            new Site(ObjectiveKind.JELLY, "젤리 무리", -52, -22, 4),
            new Site(ObjectiveKind.MICE, "태엽 쥐 떼", -52, 22, 4),
            new Site(ObjectiveKind.TEDDY, "곰인형 대장", -34, -28, 5),
            new Site(ObjectiveKind.RABBIT, "시계 토끼", -34, 28, 5),
            new Site(ObjectiveKind.FOUNTAIN, "사탕 샘물", -16, -26, 5),
            new Site(ObjectiveKind.TELESCOPE, "망원경 탑", -16, 26, 5));

    @Override
    public String id() {
        return "toykingdom";
    }

    @Override
    public String displayName() {
        return "장난감 왕국";
    }

    @Override
    public String description() {
        return "파스텔 장난감 왕국: 파랑 성과 빨강 성 사이로 노랑 벽돌길(탑)과 하늘색 벽돌길(바텀)이 휘어 돌고, "
                + "가운데 정글과 강, 장난감 드래곤 모래놀이터가 있는 2라인 공성전 맵";
    }

    @Override
    public int playRadius() {
        return ToyLayout.BUILD_RADIUS - 2;
    }

    @Override
    public int buildRadius() {
        return ToyLayout.BUILD_RADIUS;
    }

    @Override
    public int groundY() {
        return G;
    }

    @Override
    public int minY() {
        return ToyLayout.MIN_Y;
    }

    @Override
    public int maxY() {
        return ToyLayout.MAX_Y;
    }

    @Override
    public long seed() {
        return 0x70E5L;
    }

    @Override
    public int version() {
        return 4;
    }

    /** The zones in build order (exposed for tests and previews). */
    public List<MapZone> zones() {
        return zones;
    }

    @Override
    public void build(MapCanvas canvas) {
        for (MapZone zone : zones) {
            zone.build(canvas.restrictTo(zone::contains, zone.name()));
        }
    }

    // ------------------------------------------------------------------ sites

    @Override
    public List<Spot> spawns(Side side) {
        int sx = ToyLayout.sign(side);
        float yaw = side == Side.BLUE ? -90 : 90;
        int sz = ToyLayout.left(side);
        List<Spot> spots = new ArrayList<>();
        for (int[] spot : ToyLayout.SPAWN_SPOTS) {
            spots.add(new Spot(sx * spot[0], G + 1, sz * spot[1], yaw));
        }
        return spots;
    }

    @Override
    public Spot shop(Side side) {
        // The shopkeeper behind the counter on the team's left, facing the courtyard (the core's stage).
        int x = ToyLayout.sign(side) * ToyLayout.SHOP_AX;
        int z = ToyLayout.left(side) * ToyLayout.SHOP_AZ;
        return new Spot(x, G + 1, z, ToyLayout.yaw(x, z, ToyLayout.sign(side) * ToyLayout.CORE_AX, 0));
    }

    @Override
    public List<Structure> towers(Side side, Lane lane) {
        int sx = ToyLayout.sign(side);
        int sz = ToyLayout.sign(lane);
        String team = side.name().toLowerCase();
        String lanePart = lane == Lane.TOP ? "top" : "bottom";
        String laneName = lane == Lane.TOP ? "탑" : "바텀";
        String[] order = {"outer", "inner"};
        String[] orderName = {"바깥", "안쪽"};
        List<Structure> out = new ArrayList<>();
        for (int i = 0; i < ToyLayout.TOWERS.length; i++) {
            int[] t = ToyLayout.TOWERS[i];
            out.add(new Structure(team + "-" + lanePart + "-" + order[i], side.name() + " " + laneName + " " + orderName[i]
                    + " 타워", sx * t[0], G + 1, -sz * t[1], ToyLayout.TOWER_RADIUS));
        }
        return out;
    }

    @Override
    public Structure core(Side side) {
        return new Structure(side.name().toLowerCase() + "-core", side.name() + " 코어",
                ToyLayout.sign(side) * ToyLayout.CORE_AX, G + 1, 0, ToyLayout.CORE_RADIUS);
    }

    @Override
    public List<Point> lanePath(Lane lane) {
        int sz = ToyLayout.sign(lane);
        List<int[]> half = ToyLayout.halfPath(10);
        List<Point> out = new ArrayList<>();
        for (int[] p : half) {
            out.add(new Point(-p[0], G + 1, -sz * p[1]));
        }
        for (int i = half.size() - 2; i >= 0; i--) {
            int[] p = half.get(i);
            out.add(new Point(p[0], G + 1, -sz * p[1]));
        }
        return out;
    }

    @Override
    public List<Objective> objectives(Side side) {
        int sx = -ToyLayout.sign(side);
        String team = side.name().toLowerCase();
        List<Objective> out = new ArrayList<>();
        for (Site site : SITES) {
            out.add(new Objective(team + "-" + site.kind().name().toLowerCase(), site.kind(), site.name(),
                    sx * site.x(), G + 1, site.z(), site.radius()));
        }
        return out;
    }

    @Override
    public Objective epic() {
        return new Objective("dragon", ObjectiveKind.DRAGON, "장난감 드래곤", 0, G, 0, ToyLayout.EPIC_RADIUS);
    }

    @Override
    public List<Region> regions() {
        int c = ToyLayout.CASTLE_ZONE_FRONT;
        int e = ToyLayout.BUILD_RADIUS;
        int hz = ToyLayout.CASTLE_ZONE_HALF_Z;
        int jz = 40;
        int river = 16;
        return List.of(
                new Region("blue-base", "BLUE 진영", -e, -c, -hz, hz),
                new Region("red-base", "RED 진영", c, e, -hz, hz),
                new Region("river", "강 · 드래곤 둥지", -river, river, -jz, jz),
                new Region("blue-jungle", "정글", -c + 1, -river - 1, -jz, jz),
                new Region("red-jungle", "정글", river + 1, c - 1, -jz, jz),
                new Region("top", "탑 라인", -e, e, -e, -jz - 1),
                new Region("top-blue", "탑 라인", -e, -c, -jz, -hz - 1),
                new Region("top-red", "탑 라인", c, e, -jz, -hz - 1),
                new Region("bottom", "바텀 라인", -e, e, jz + 1, e),
                new Region("bottom-blue", "바텀 라인", -e, -c, hz + 1, jz),
                new Region("bottom-red", "바텀 라인", c, e, hz + 1, jz));
    }
}
