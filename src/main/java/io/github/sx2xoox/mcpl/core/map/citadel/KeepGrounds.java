package io.github.sx2xoox.mcpl.core.map.citadel;

import static io.github.sx2xoox.mcpl.core.map.citadel.KeepKit.GROUND;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Material;

/**
 * The keep's ground: a worn paved plaza around the keep tower, paved roads from the gates, trodden trails from the
 * breaches, and a courtyard of grass broken by moss, dirt and gravel. Only the top ground block changes; the ground
 * stays at {@link KeepKit#GROUND} everywhere so the whole courtyard is walkable.
 */
final class KeepGrounds {

    /** Half size of the paved square (with rounded corners) around the keep tower, ragged by noise. */
    static final double PLAZA_HALF = 11.5;

    private final MapCanvas canvas;
    private final Random random;
    private final int salt;

    KeepGrounds(MapCanvas canvas, Random random) {
        this.canvas = canvas;
        this.random = random;
        this.salt = random.nextInt();
    }

    void build() {
        int r = CitadelLayout.KEEP_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (canvas.canWrite(x, z)) {
                    canvas.set(x, GROUND, z, surface(x, z));
                }
            }
        }
    }

    /** The ground block of one column. */
    private BlockSpec surface(int x, int z) {
        double r = KeepKit.radius(x, z);
        double wobble = (KeepKit.noise(salt, x * 0.3, z * 0.3) - 0.5) * 3.0;
        double patch = KeepKit.noise(salt + 1, x * 0.18, z * 0.18);
        double fine = KeepKit.noise(salt + 2, x * 0.7, z * 0.7);
        double square = Math.pow(Math.pow(Math.abs(x), 4) + Math.pow(Math.abs(z), 4), 0.25);
        double edge = PLAZA_HALF + wobble * 0.5;
        if (square < edge) {
            // The plaza: paving, grass and moss win back the cracks towards its edge.
            double wild = (square - (PLAZA_HALF - 3)) / 3.0 + (patch - 0.5);
            if (wild > 0.8 && fine > 0.45) {
                return BlockSpec.of(fine > 0.8 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK);
            }
            if (square >= edge - 1.0) {
                return BlockSpec.of(fine > 0.7 ? Material.ANDESITE : Material.POLISHED_ANDESITE); // kerb
            }
            return plaza(patch, fine);
        }
        if (isRoad(x, z, wobble)) {
            return road(x, z, fine);
        }
        if (isTrail(x, z, wobble)) {
            return trail(fine);
        }
        return courtyard(r, patch, fine);
    }

    /** Paved roads from the east and west gates to the keep's doors and from the south-west gate to the forebuilding. */
    private static boolean isRoad(int x, int z, double wobble) {
        double edge = 2.4 + wobble * 0.25;
        if (Math.abs(z) <= edge && Math.abs(x) >= 7) {
            return true;
        }
        // From the broken south-west gate (120 degrees) towards the forebuilding stair.
        return KeepKit.distanceToSegment(x, z, -12.5, 22.0, -6.0, 9.5) <= 1.9 + wobble * 0.2;
    }

    /** Trodden trails from the three breaches (60, 240 and 300 degrees) towards the plaza. */
    private static boolean isTrail(int x, int z, double wobble) {
        for (int angle : new int[] {60, 240, 300}) {
            double[] a = KeepKit.point(29, angle);
            double[] b = KeepKit.point(PLAZA_HALF - 1, angle + 6);
            if (KeepKit.distanceToSegment(x, z, a[0], a[1], b[0], b[1]) <= 1.6 + wobble * 0.3) {
                return true;
            }
        }
        return false;
    }

    /** Flagstones of the plaza in worn patches rather than speckles. */
    private BlockSpec plaza(double patch, double fine) {
        if (patch < 0.3 && fine > 0.4) {
            return BlockSpec.of(Material.MOSSY_STONE_BRICKS);
        }
        if (fine > 0.72) {
            return BlockSpec.of(Material.CRACKED_STONE_BRICKS);
        }
        int roll = random.nextInt(100);
        if (roll < 8) {
            return BlockSpec.of(Material.CRACKED_STONE_BRICKS);
        }
        if (roll < 12) {
            return BlockSpec.of(Material.ANDESITE);
        }
        return BlockSpec.of(Material.STONE_BRICKS);
    }

    /** Cobbled roads: set stones down the middle, worn gravel and dirt along the edges. */
    private BlockSpec road(int x, int z, double fine) {
        double off = Math.abs(z) <= 3 && Math.abs(x) >= 7 ? Math.abs(z) : 1.0;
        int roll = random.nextInt(100);
        if (off >= 2 && roll < 45) {
            return BlockSpec.of(roll < 20 ? Material.GRAVEL : roll < 35 ? Material.COARSE_DIRT : Material.DIRT_PATH);
        }
        if (fine > 0.75 && roll < 55) {
            return BlockSpec.of(Material.MOSSY_COBBLESTONE);
        }
        if (roll < 45) {
            return BlockSpec.of(Material.COBBLESTONE);
        }
        if (roll < 70) {
            return BlockSpec.of(Material.STONE_BRICKS);
        }
        if (roll < 84) {
            return BlockSpec.of(Material.CRACKED_STONE_BRICKS);
        }
        return BlockSpec.of(Material.ANDESITE);
    }

    private BlockSpec trail(double fine) {
        int roll = random.nextInt(100);
        if (roll < 35) {
            return BlockSpec.of(Material.DIRT_PATH);
        }
        if (roll < 60) {
            return BlockSpec.of(Material.COARSE_DIRT);
        }
        if (roll < 75) {
            return BlockSpec.of(Material.GRAVEL);
        }
        if (roll < 85) {
            return BlockSpec.of(Material.COBBLESTONE);
        }
        return BlockSpec.of(fine > 0.5 ? Material.GRASS_BLOCK : Material.ROOTED_DIRT);
    }

    /** Grass with patches of moss (near the walls), dirt, podzol and gravel. */
    private BlockSpec courtyard(double r, double patch, double fine) {
        boolean nearWall = r > 22;
        if (nearWall && patch > 0.55 && fine > 0.35) {
            return BlockSpec.of(Material.MOSS_BLOCK);
        }
        if (patch < 0.22) {
            return BlockSpec.of(fine > 0.5 ? Material.COARSE_DIRT : Material.ROOTED_DIRT);
        }
        if (patch > 0.8 && fine > 0.55) {
            return BlockSpec.of(Material.PODZOL);
        }
        if (fine > 0.9) {
            return BlockSpec.of(Material.GRAVEL);
        }
        if (patch > 0.68 && fine < 0.3) {
            return BlockSpec.of(Material.MOSS_BLOCK);
        }
        return BlockSpec.of(Material.GRASS_BLOCK);
    }
}
