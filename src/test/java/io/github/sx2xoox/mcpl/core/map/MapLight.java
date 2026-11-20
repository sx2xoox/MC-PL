package io.github.sx2xoox.mcpl.core.map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;

/**
 * Block light of a finished map canvas, close to vanilla: every light source emits its level, light spreads to the six
 * neighbours losing one level per block and stops at opaque blocks (full cubes that are not glass). Sky light is left
 * out: this is how the map looks at night.
 */
public final class MapLight {

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final byte[] light;

    public MapLight(MapCanvas canvas) {
        int r = canvas.radius();
        minX = -r;
        minZ = -r;
        minY = canvas.minY();
        sizeX = 2 * r + 1;
        sizeZ = 2 * r + 1;
        sizeY = canvas.maxY() - canvas.minY() + 1;
        light = new byte[sizeX * sizeY * sizeZ];
        boolean[] opaque = new boolean[light.length];
        List<ArrayDeque<Integer>> buckets = buckets();
        canvas.forEach((x, y, z, spec) -> {
            int i = index(x, y, z);
            if (i < 0) {
                return;
            }
            int emit = emission(spec.material());
            if (emit > 0) {
                light[i] = (byte) emit;
                buckets.get(emit).add(i);
            }
            opaque[i] = emit == 0 && blocksLight(spec);
        });
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int level = 15; level > 1; level--) {
            ArrayDeque<Integer> queue = buckets.get(level);
            while (!queue.isEmpty()) {
                int i = queue.poll();
                if (light[i] != level) {
                    continue;
                }
                int x = i / (sizeY * sizeZ);
                int y = (i / sizeZ) % sizeY;
                int z = i % sizeZ;
                for (int[] d : sides) {
                    int nx = x + d[0];
                    int ny = y + d[1];
                    int nz = z + d[2];
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= sizeX || ny >= sizeY || nz >= sizeZ) {
                        continue;
                    }
                    int n = (nx * sizeY + ny) * sizeZ + nz;
                    if (opaque[n] || light[n] >= level - 1) {
                        continue;
                    }
                    light[n] = (byte) (level - 1);
                    buckets.get(level - 1).add(n);
                }
            }
        }
    }

    private static List<ArrayDeque<Integer>> buckets() {
        List<ArrayDeque<Integer>> out = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            out.add(new ArrayDeque<>());
        }
        return out;
    }

    /** Block light at a position (0 outside the canvas). */
    public int at(int x, int y, int z) {
        int i = index(x, y, z);
        return i < 0 ? 0 : light[i];
    }

    private int index(int x, int y, int z) {
        int lx = x - minX;
        int ly = y - minY;
        int lz = z - minZ;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ) {
            return -1;
        }
        return (lx * sizeY + ly) * sizeZ + lz;
    }

    /** Light level a block emits (vanilla values for the blocks the maps use). */
    public static int emission(Material material) {
        String n = material.name();
        if (n.equals("SEA_LANTERN") || n.endsWith("FROGLIGHT") || n.equals("GLOWSTONE") || n.equals("SHROOMLIGHT")
                || n.equals("LANTERN") || n.equals("BEACON") || n.equals("REDSTONE_LAMP") || n.equals("LIGHT")
                || n.equals("JACK_O_LANTERN") || n.equals("CONDUIT") || n.equals("END_GATEWAY")) {
            return 15;
        }
        if (n.equals("END_ROD") || n.equals("TORCH") || n.equals("WALL_TORCH")) {
            return 14;
        }
        if (n.equals("SOUL_LANTERN") || n.contains("SOUL_TORCH") || n.equals("CRYING_OBSIDIAN")) {
            return 10;
        }
        return 0;
    }

    private static boolean blocksLight(BlockSpec spec) {
        String n = spec.material().name();
        if (n.equals("TINTED_GLASS")) {
            return true;
        }
        if (n.contains("GLASS") || n.contains("LEAVES") || n.equals("ICE") || n.equals("BARRIER")) {
            return false;
        }
        return MapBlocks.isFullCube(spec);
    }
}
