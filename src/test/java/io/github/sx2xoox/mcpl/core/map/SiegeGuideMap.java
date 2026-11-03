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

import io.github.sx2xoox.mcpl.core.map.siege.SiegeLayout;
import io.github.sx2xoox.mcpl.core.map.siege.SiegeMap;
import io.github.sx2xoox.mcpl.core.map.siege.SiegeSites;
import org.jspecify.annotations.Nullable;

/**
 * An annotated top-down guide of 네온 시티 for players: the map dimmed, the three lanes drawn in their colours, the
 * districts, towers and spawns labelled (in Korean when a Hangul font is installed, else in English).
 */
final class SiegeGuideMap {

    private SiegeGuideMap() {
    }

    private static final int SCALE = 4;
    private static final int MARGIN_TOP = 70;
    private static final int MARGIN_BOTTOM = 90;

    private static final Color MAIN = new Color(250, 210, 40);
    private static final Color NORTH = new Color(120, 220, 40);
    private static final Color CANAL = new Color(90, 190, 255);

    static void write(SiegeMap map, MapCanvas canvas, File file) throws IOException {
        int minX = -SiegeLayout.BUILD_RADIUS;
        int maxX = SiegeLayout.BUILD_RADIUS;
        int minZ = -SiegeLayout.HALF_WIDTH;
        int maxZ = SiegeLayout.HALF_WIDTH;
        boolean night = MapPreview.night;
        MapPreview.night = false;
        BufferedImage top = new MapPreview(canvas).topDown(minX, minZ, maxX, maxZ, SCALE, (x, z) -> true, 0);
        MapPreview.night = night;
        int width = top.getWidth();
        int height = top.getHeight() + MARGIN_TOP + MARGIN_BOTTOM;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(16, 16, 24));
        g.fillRect(0, 0, width, height);
        g.drawImage(top, 0, MARGIN_TOP, null);
        // Dim the map so the annotations stand out.
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        g.setColor(Color.BLACK);
        g.fillRect(0, MARGIN_TOP, width, top.getHeight());
        g.setComposite(AlphaComposite.SrcOver);
        String hangul = hangulFont();
        boolean korean = hangul != null;
        String family = korean ? hangul : Font.SANS_SERIF;
        Projection p = new Projection(minX, minZ);

        // Districts: borders and names on top.
        int[][] bounds = {{SiegeLayout.WEST_END, SiegeLayout.START_EAST}, {SiegeLayout.START_EAST + 1,
            SiegeLayout.FIREWALL_WEST - 1}, {SiegeLayout.FIREWALL_WEST, SiegeLayout.HUB_WEST - 1},
            {SiegeLayout.HUB_WEST, SiegeLayout.HQ_WEST - 1}, {SiegeLayout.HQ_WEST, SiegeLayout.EAST_END}};
        String[] names = korean ? new String[] {"고가 하부", "네온 슬럼", "방화벽 구역", "데이터 허브", "메가코프 본사"}
                : new String[] {"Underpass", "Neon slums", "Firewall", "Data hub", "Megacorp HQ"};
        String[] notes = korean ? new String[] {"공격 진영", "주황", "자홍", "청록", "보라"}
                : new String[] {"attackers", "orange", "magenta", "cyan", "purple"};
        Color[] accents = {new Color(200, 200, 200), new Color(255, 140, 30), new Color(230, 70, 210),
            new Color(30, 200, 210), new Color(160, 90, 230)};
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] {8f, 6f}, 0f));
        for (int i = 0; i < bounds.length; i++) {
            int x1 = p.x(bounds[i][0]);
            int x2 = p.x(bounds[i][1] + 1);
            g.setColor(accents[i]);
            if (i > 0) {
                g.drawLine(x1, MARGIN_TOP, x1, MARGIN_TOP + top.getHeight());
            }
            g.fillRect(x1 + 2, MARGIN_TOP - 10, x2 - x1 - 4, 6);
            label(g, family, 20, names[i], (x1 + x2) / 2, 30, Color.WHITE);
            label(g, family, 13, notes[i], (x1 + x2) / 2, 50, accents[i]);
        }
        // The firewall itself.
        g.setStroke(new BasicStroke(SCALE * 5f));
        g.setColor(new Color(230, 70, 210, 200));
        g.drawLine(p.x(SiegeLayout.WALL_X1 + 2.5), p.z(-SiegeLayout.SIDE), p.x(SiegeLayout.WALL_X1 + 2.5),
                p.z(SiegeLayout.SIDE));
        int wallX = p.x(SiegeLayout.WALL_X1 + 2.5);
        label(g, family, 13, korean ? "게이트" : "gate", wallX, p.z(-12), Color.WHITE);
        label(g, family, 13, korean ? "무너진 틈" : "breach", wallX, p.z(-56), Color.WHITE);
        label(g, family, 13, korean ? "배수로" : "culvert", wallX, p.z(42), Color.WHITE);

        // Lanes.
        lane(g, p, MAIN, new double[][] {{-150, 0}, {120, 0}});
        lane(g, p, NORTH, new double[][] {{-150, -50}, {90, -50}, {90, -65}, {129, -65}, {129, -12}});
        lane(g, p, CANAL, new double[][] {{-150, 50}, {84, 50}, {84, 71}, {104, 71}, {103, 42}, {116, 42},
            {116, 14}});
        label(g, family, 18, korean ? "큰길 (노랑)" : "Main avenue (yellow)", p.x(-112), p.z(-1) - 4, MAIN);
        label(g, family, 18, korean ? "북쪽 거리 (연두)" : "North street (lime)", p.x(-112), p.z(-50) - 12, NORTH);
        label(g, family, 18, korean ? "지하 수로 (하늘)" : "Sunken canal (light blue)", p.x(-112), p.z(50) + 26,
                CANAL);
        label(g, family, 14, korean ? "주차 램프" : "parking ramp", p.x(112), p.z(-65) - 10, NORTH);
        label(g, family, 14, korean ? "유리 계단탑" : "stair tower", p.x(102), p.z(71) + 22, CANAL);
        label(g, family, 14, korean ? "대계단" : "grand stairs", p.x(84), p.z(0) + 26, MAIN);

        // Towers.
        String[] towerNames = korean ? new String[] {"① 방화벽 게이트", "② 데이터 허브", "③ 메인프레임"}
                : new String[] {"1 Firewall gate", "2 Data hub", "3 Mainframe"};
        Color[] towerColours = {accents[2], accents[3], accents[4]};
        List<SiegeSites.Tower> towers = map.towers();
        for (int i = 0; i < towers.size(); i++) {
            SiegeSites.Tower t = towers.get(i);
            int r = t.radius() * SCALE;
            g.setStroke(new BasicStroke(4f));
            g.setColor(towerColours[i]);
            g.drawOval(p.x(t.x() + 0.5) - r, p.z(t.z() + 0.5) - r, 2 * r, 2 * r);
            g.fillOval(p.x(t.x() + 0.5) - 7, p.z(t.z() + 0.5) - 7, 14, 14);
            label(g, family, 18, towerNames[i], p.x(t.x() + 0.5), p.z(t.z()) - r - 30, Color.WHITE);
        }
        // Neutral objectives.
        for (SiegeSites.Objective o : map.objectives()) {
            int r = o.radius() * SCALE;
            g.setStroke(new BasicStroke(3f));
            g.setColor(Color.WHITE);
            g.drawOval(p.x(o.x() + 0.5) - r, p.z(o.z() + 0.5) - r, 2 * r, 2 * r);
            label(g, family, 13, korean ? o.name() : o.id() + " objective", p.x(o.x() + 0.5),
                    p.z(o.z()) + r + 16, Color.WHITE);
        }
        // Spawns.
        spawns(g, p, map.attackerSpawns(), new Color(255, 90, 90), family, korean ? "공격 부활" : "attackers");
        String[] stages = korean ? new String[] {"수비 부활 (처음)", "수비 부활 (① 파괴 후)", "수비 부활 (② 파괴 후)"}
                : new String[] {"defenders (start)", "defenders (after 1)", "defenders (after 2)"};
        for (int i = 0; i < map.defenderSpawns().size(); i++) {
            spawns(g, p, map.defenderSpawns().get(i), new Color(90, 150, 255), family, stages[i]);
        }

        // Legend.
        int ly = MARGIN_TOP + top.getHeight() + 28;
        String legend = korean
                ? "바닥의 색 띠·화살표(>)·교차로 기둥이 길의 색입니다. 화살표는 타워(동쪽) 방향. 구역마다 네온 색과 큰 간판(SLUMS · FIREWALL · DATA HUB · MEGACORP)이 다릅니다."
                : "Floor strips, arrows (>) and junction totems show the lane colour; arrows point east to the towers. "
                + "Each district has its own neon colour and a big sign (SLUMS, FIREWALL, DATA HUB, MEGACORP).";
        label(g, family, 15, legend, width / 2, ly, new Color(230, 230, 230));
        label(g, family, 15, korean ? "공격: 서쪽(왼쪽) → 동쪽 · 타워를 ①②③ 순서로 파괴" : "Attackers push west to east "
                + "and destroy the towers 1, 2, 3 in order", width / 2, ly + 26, new Color(200, 200, 200));
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

    private static void lane(Graphics2D g, Projection p, Color colour, double[][] points) {
        g.setStroke(new BasicStroke(SCALE * 2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 200));
        for (int i = 1; i < points.length; i++) {
            g.drawLine(p.x(points[i - 1][0]), p.z(points[i - 1][1]), p.x(points[i][0]), p.z(points[i][1]));
        }
        // Arrow heads every 40 blocks along straight runs, pointing the way to the core.
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.BLACK);
        for (int i = 1; i < points.length; i++) {
            double dx = points[i][0] - points[i - 1][0];
            double dz = points[i][1] - points[i - 1][1];
            double length = Math.hypot(dx, dz);
            for (double d = 20; d < length - 10; d += 40) {
                double x = points[i - 1][0] + dx * d / length;
                double z = points[i - 1][1] + dz * d / length;
                double ux = dx / length;
                double uz = dz / length;
                int tx = p.x(x + ux * 2);
                int tz = p.z(z + uz * 2);
                g.drawLine(tx, tz, p.x(x - ux * 1.5 - uz * 1.8), p.z(z - uz * 1.5 + ux * 1.8));
                g.drawLine(tx, tz, p.x(x - ux * 1.5 + uz * 1.8), p.z(z - uz * 1.5 - ux * 1.8));
            }
        }
    }

    private static void spawns(Graphics2D g, Projection p, List<SiegeSites.Spot> spots, Color colour, String family,
                               String name) {
        int sx = 0;
        int sz = 0;
        g.setColor(colour);
        for (SiegeSites.Spot spot : spots) {
            g.fillRect(p.x(spot.x()), p.z(spot.z()), SCALE, SCALE);
            sx += spot.x();
            sz += spot.z();
        }
        int cx = p.x((double) sx / spots.size());
        int cz = p.z((double) sz / spots.size());
        g.setStroke(new BasicStroke(2f));
        g.drawRect(cx - 14, cz - 18, 28, 36);
        label(g, family, 14, name, cx, cz + 36, colour);
    }

    /** Centred text with a dark outline. */
    private static void label(Graphics2D g, String family, int size, String text, int cx, int baseline, Color colour) {
        g.setFont(new Font(family, Font.BOLD, size));
        FontMetrics metrics = g.getFontMetrics();
        int x = cx - metrics.stringWidth(text) / 2;
        g.setColor(new Color(0, 0, 0, 220));
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
            if (families.contains(family) && new Font(family, Font.PLAIN, 12).canDisplayUpTo("네온 시티") == -1) {
                return family;
            }
        }
        return null;
    }
}
