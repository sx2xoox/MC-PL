package io.github.sx2xoox.mcpl.core.map;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKingdomMap;
import org.jspecify.annotations.Nullable;

/**
 * An annotated top-down guide of 장난감 왕국 for players: the lanes with their minion paths, towers, cores, spawns, shops,
 * the jungle objectives and the dragon (in Korean when a Hangul font is installed, else in English).
 */
final class ToyKingdomGuideMap {

    private ToyKingdomGuideMap() {
    }

    private static final int SCALE = 6;
    private static final int HALF_Z = 66;
    private static final int MARGIN_TOP = 70;
    private static final int MARGIN_BOTTOM = 110;

    private static final Color BLUE = new Color(70, 120, 235);
    private static final Color RED = new Color(230, 70, 80);
    private static final Color TOP = new Color(255, 205, 40);
    private static final Color BOTTOM = new Color(110, 200, 250);

    static void write(ToyKingdomMap map, MapCanvas canvas, File file) throws IOException {
        int r = map.buildRadius();
        BufferedImage top = new MapPreview(canvas).topDown(-r, -HALF_Z, r, HALF_Z, SCALE, (x, z) -> true, 0);
        int width = top.getWidth();
        int height = top.getHeight() + MARGIN_TOP + MARGIN_BOTTOM;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(250, 246, 238));
        g.fillRect(0, 0, width, height);
        g.drawImage(top, 0, MARGIN_TOP, null);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
        g.setColor(Color.WHITE);
        g.fillRect(0, MARGIN_TOP, width, top.getHeight());
        g.setComposite(AlphaComposite.SrcOver);
        String hangul = hangulFont();
        boolean ko = hangul != null;
        String family = ko ? hangul : Font.SANS_SERIF;
        Projection p = new Projection(-r, -HALF_Z);

        label(g, family, 26, ko ? "장난감 왕국 — 공성전 2라인 맵" : "Toy Kingdom - two-lane siege map", width / 2, 34,
                Color.BLACK);
        label(g, family, 15, ko ? "BLUE 진영 (서쪽, 파랑 성)  ·  RED 진영 (동쪽, 분홍 성)  ·  대칭 맵"
                : "BLUE base (west)  -  RED base (east)  -  mirror symmetric", width / 2, 58, new Color(60, 60, 60));

        // Lane paths with arrows from BLUE to RED.
        lane(g, p, map.lanePath(Lane.TOP), TOP);
        lane(g, p, map.lanePath(Lane.BOTTOM), BOTTOM);
        label(g, family, 20, ko ? "탑 라인 (노랑 벽돌길)" : "TOP lane (yellow road)", p.x(0), p.z(-58) + 6,
                new Color(150, 110, 0));
        label(g, family, 20, ko ? "바텀 라인 (하늘색 벽돌길)" : "BOTTOM lane (light blue road)", p.x(0),
                p.z(58) + 10, new Color(20, 110, 170));

        // Towers and cores.
        for (Side side : Side.values()) {
            Color colour = side == Side.BLUE ? BLUE : RED;
            for (Lane lane : Lane.values()) {
                List<LaneSites.Structure> towers = map.towers(side, lane);
                for (int i = 0; i < towers.size(); i++) {
                    LaneSites.Structure t = towers.get(i);
                    ring(g, p, t.x(), t.z(), t.radius(), colour, 4f);
                    g.setColor(colour);
                    g.fillOval(p.x(t.x() + 0.5) - 9, p.z(t.z() + 0.5) - 9, 18, 18);
                    label(g, family, 13, (i == 0 ? (ko ? "바깥 타워" : "outer") : (ko ? "안쪽 타워" : "inner")),
                            p.x(t.x() + 0.5), p.z(t.z() + 0.5) + (lane == Lane.TOP ? 62 : -50), colour.darker());
                }
            }
            LaneSites.Structure core = map.core(side);
            ring(g, p, core.x(), core.z(), core.radius(), colour, 5f);
            label(g, family, 20, ko ? "코어" : "core", p.x(core.x() + 0.5), p.z(core.z() + 0.5) + 7, colour.darker());
            // Spawns and shop.
            g.setColor(colour);
            for (LaneSites.Spot s : map.spawns(side)) {
                g.fillRect(p.x(s.x()) + 1, p.z(s.z()) + 1, SCALE - 2, SCALE - 2);
            }
            LaneSites.Spot first = map.spawns(side).getFirst();
            label(g, family, 14, ko ? "부활" : "spawn", p.x(first.x() + 0.5), p.z(9), Color.BLACK);
            LaneSites.Spot shop = map.shop(side);
            g.setColor(new Color(255, 170, 0));
            g.fillOval(p.x(shop.x() + 0.5) - 8, p.z(shop.z() + 0.5) - 8, 16, 16);
            label(g, family, 14, ko ? "상점" : "shop", p.x(shop.x() + 0.5), p.z(shop.z()) - 12, Color.BLACK);
            label(g, family, 22, side == Side.BLUE ? (ko ? "BLUE 성" : "BLUE castle") : (ko ? "RED 성" : "RED castle"),
                    p.x(core.x() + 0.5), p.z(-25), colour.darker());

            // Objectives.
            for (LaneSites.Objective o : map.objectives(side)) {
                ring(g, p, o.x(), o.z(), o.radius(), new Color(200, 60, 160), 3f);
                label(g, family, 14, ko ? o.name() : o.kind().name().toLowerCase(), p.x(o.x() + 0.5),
                        p.z(o.z() + 0.5) + 5, new Color(120, 20, 100));
            }
        }
        LaneSites.Objective dragon = map.epic();
        ring(g, p, dragon.x(), dragon.z(), dragon.radius(), new Color(150, 60, 200), 4f);
        label(g, family, 18, ko ? "장난감 드래곤" : "toy dragon", p.x(0.5), p.z(0.5) + 6, new Color(90, 30, 140));
        label(g, family, 15, ko ? "강" : "river", p.x(0.5) + 26, p.z(-30), new Color(20, 70, 160));
        label(g, family, 15, ko ? "강" : "river", p.x(0.5) + 26, p.z(30), new Color(20, 70, 160));
        label(g, family, 18, ko ? "BLUE 정글" : "BLUE jungle", p.x(-40), p.z(0.5) + 6, new Color(30, 80, 30));
        label(g, family, 18, ko ? "RED 정글" : "RED jungle", p.x(41), p.z(0.5) + 6, new Color(30, 80, 30));

        int ly = MARGIN_TOP + top.getHeight() + 30;
        label(g, family, 15, ko ? "미니언은 각 성의 라인 문에서 출발해 길을 따라 걷습니다 (화살표: BLUE → RED). 타워는 바깥 → 안쪽 → 코어 순서."
                : "Minions leave each castle's lane gate and follow the road (arrows: BLUE to RED). Towers fall outer, "
                + "inner, then the core.", width / 2, ly, new Color(40, 40, 40));
        label(g, family, 15, ko ? "정글: 진영마다 젤리·쥐(소형), 곰인형·토끼(버프), 사탕 샘물·망원경 탑(점령) · 가운데 장난감 드래곤"
                : "Jungle per side: jelly, mice (small), teddy, rabbit (buffs), fountain, telescope (capture); dragon in "
                + "the centre", width / 2, ly + 28, new Color(40, 40, 40));
        label(g, family, 15, ko ? "울타리(짙은 초록)는 넘을 수 없는 벽 · 타워 둘레의 팀 색 고리 = 타워 구역"
                : "Hedges (dark green) are walls; the team coloured ring around a tower marks its zone", width / 2,
                ly + 56, new Color(40, 40, 40));
        g.dispose();
        MapPreview.write(image, file);
    }

    private record Projection(int minX, int minZ) {
        int x(double x) {
            return (int) Math.round((x - minX) * SCALE);
        }

        int z(double z) {
            return (int) Math.round((z - minZ) * SCALE) + MARGIN_TOP;
        }
    }

    private static void ring(Graphics2D g, Projection p, int x, int z, int radius, Color colour, float stroke) {
        int rr = radius * SCALE;
        g.setStroke(new BasicStroke(stroke));
        g.setColor(colour);
        g.drawOval(p.x(x + 0.5) - rr, p.z(z + 0.5) - rr, 2 * rr, 2 * rr);
    }

    private static void lane(Graphics2D g, Projection p, List<LaneSites.Point> path, Color colour) {
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(colour.darker());
        for (int i = 1; i < path.size(); i++) {
            LaneSites.Point a = path.get(i - 1);
            LaneSites.Point b = path.get(i);
            g.drawLine(p.x(a.x() + 0.5), p.z(a.z() + 0.5), p.x(b.x() + 0.5), p.z(b.z() + 0.5));
        }
        for (int i = 0; i < path.size(); i++) {
            LaneSites.Point a = path.get(i);
            g.fillOval(p.x(a.x() + 0.5) - 3, p.z(a.z() + 0.5) - 3, 6, 6);
        }
        // Arrow heads at every third segment.
        for (int i = 2; i < path.size(); i += 3) {
            LaneSites.Point a = path.get(i - 1);
            LaneSites.Point b = path.get(i);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double len = Math.hypot(dx, dz);
            if (len == 0) {
                continue;
            }
            double ux = dx / len;
            double uz = dz / len;
            double mx = (a.x() + b.x()) / 2.0 + 0.5;
            double mz = (a.z() + b.z()) / 2.0 + 0.5;
            int tx = p.x(mx + ux * 1.5);
            int tz = p.z(mz + uz * 1.5);
            g.drawLine(tx, tz, p.x(mx - ux * 1.5 - uz * 1.5), p.z(mz - uz * 1.5 + ux * 1.5));
            g.drawLine(tx, tz, p.x(mx - ux * 1.5 + uz * 1.5), p.z(mz - uz * 1.5 - ux * 1.5));
        }
    }

    /** Centred text with a light outline. */
    private static void label(Graphics2D g, String family, int size, String text, int cx, int baseline, Color colour) {
        g.setFont(new Font(family, Font.BOLD, size));
        FontMetrics metrics = g.getFontMetrics();
        int x = cx - metrics.stringWidth(text) / 2;
        g.setColor(new Color(255, 255, 255, 230));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                g.drawString(text, x + dx, baseline + dy);
            }
        }
        g.setColor(colour);
        g.drawString(text, x, baseline);
    }

    /** An installed font family that can write Hangul (NanumGothic preferred), or null. */
    private static @Nullable String hangulFont() {
        Set<String> families = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames());
        for (String family : List.of("NanumGothic", "Nanum Gothic", "Noto Sans CJK KR", "WenQuanYi Zen Hei")) {
            if (families.contains(family) && new Font(family, Font.PLAIN, 12).canDisplayUpTo("장난감 왕국") == -1) {
                return family;
            }
        }
        return null;
    }
}
