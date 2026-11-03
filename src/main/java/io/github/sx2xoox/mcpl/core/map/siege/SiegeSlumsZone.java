package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.Random;
import java.util.function.BiPredicate;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.bukkit.Material;

/**
 * The attackers' side: the underpass under the elevated highway where they spawn (container yards on both sides),
 * then the neon slums: a market avenue with stalls, capsule blocks with alleys, two enterable towers joined by a
 * glass skybridge over the avenue, and the start of the sunken canal.
 */
final class SiegeSlumsZone implements MapZone {

    private static final int G = SiegeLayout.GROUND_Y;
    private static final int N = BlockSpec.NORTH;
    private static final int S = BlockSpec.SOUTH;

    @Override
    public String name() {
        return "slums";
    }

    @Override
    public boolean contains(int x, int z) {
        return SiegeLayout.isInterior(x, z) && x < SiegeLayout.FIREWALL_WEST;
    }

    @Override
    public void build(MapCanvas canvas) {
        Random random = canvas.random(name());
        underpass(canvas, random);
        // The landmark pair: a capsule hotel and a club tower, joined over the avenue.
        SiegeKit.building(canvas, random, new SiegeKit.Building(-128, -43, -114, -11, 4, true, N | S));
        SiegeKit.building(canvas, random, new SiegeKit.Building(-128, 11, -114, 41, 4, true, N | S));
        SiegeStreets.skybridge(canvas, -121, -11, 11, "MAGENTA");
        // The rest of the blocks.
        SiegeStreets.block(canvas, random, -95, -43, -83, -11, N | S, 2, 4, 0.6);
        SiegeStreets.block(canvas, random, -95, 11, -83, 41, N | S, 2, 4, 0.6);
        SiegeStreets.block(canvas, random, -128, -88, -105, -57, S, 3, 6, 0.5);
        SiegeStreets.block(canvas, random, -95, -88, -83, -57, S, 3, 6, 0.5);
        SiegeStreets.block(canvas, random, -128, 59, -105, 88, N, 3, 6, 0.5);
        SiegeStreets.block(canvas, random, -95, 59, -83, 88, N, 3, 6, 0.5);
        // A small night market on the lot between the hotel and the cross street.
        for (int z : new int[] {-40, -32, -24, 16, 24, 32}) {
            SiegeStreets.stall(canvas, random, -110, z, 1);
        }
        // Market stalls along the avenue, then the street cover.
        for (int x = -94; x <= -88; x += 6) {
            SiegeStreets.stall(canvas, random, x, -SiegeLayout.MAIN_HALF, 1);
            SiegeStreets.stall(canvas, random, x + 2, SiegeLayout.MAIN_HALF, -1);
        }
        BiPredicate<Integer, Integer> keep = SiegeZones::keepFree;
        SiegeStreets.streetAlongX(canvas, random, -128, -83, -SiegeLayout.MAIN_HALF, SiegeLayout.MAIN_HALF, keep);
        SiegeStreets.streetAlongX(canvas, random, -128, -83, SiegeLayout.NORTH_Z1, SiegeLayout.NORTH_Z2, keep);
        SiegeStreets.streetAlongZ(canvas, random, -104, -96, -88, 88, keep);
    }

    /** The spawn under the highway: open ground, container stacks north and south, cover towards the slums. */
    private void underpass(MapCanvas canvas, Random random) {
        for (int z : new int[] {-80, -66, -30, -20, 22, 32, 68, 80}) {
            container(canvas, random, -158, z, random.nextInt(2) + 1);
            container(canvas, random, -136, z + 2, 1);
        }
        // Barricades in front of the spawn, staggered so the way out stays open.
        for (int z = -40; z <= 40; z += 10) {
            if (Math.abs(z) <= 4) {
                continue;
            }
            SiegeKit.barrier(canvas, -132, z - 1, false, 3);
        }
        SiegeKit.car(canvas, random, -140, -16, true);
        SiegeKit.car(canvas, random, -138, 14, true);
        SiegeKit.crates(canvas, random, -132, -6, G + 1);
        SiegeKit.crates(canvas, random, -132, 6, G + 1);
        // Neon arrows on the ground pointing east.
        for (int x = -146; x <= -134; x += 4) {
            canvas.set(x, G, 0, SiegeKit.glow("CYAN"));
            canvas.set(x - 1, G, -1, SiegeKit.glow("CYAN"));
            canvas.set(x - 1, G, 1, SiegeKit.glow("CYAN"));
        }
        // A wall board for the attackers at the west skyline.
        SiegeKit.lamp(canvas, -142, -12, 0, 1, "CYAN");
        SiegeKit.lamp(canvas, -142, 12, 0, -1, "CYAN");
    }

    /** A shipping container (3 wide, 7 long, 3 high), {@code stack} high, along z. */
    private static void container(MapCanvas canvas, Random random, int x, int z, int stack) {
        String[] colours = {"ORANGE", "CYAN", "LIGHT_GRAY", "GREEN", "PURPLE", "YELLOW"};
        for (int s = 0; s < stack; s++) {
            String colour = colours[random.nextInt(colours.length)];
            int y0 = G + 1 + s * 3;
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 7; dz++) {
                    for (int y = y0; y < y0 + 3; y++) {
                        boolean rib = dz % 2 == 0;
                        canvas.set(x + dx, y, z + dz, rib ? SiegeKit.concrete(colour)
                                : BlockSpec.of(Material.valueOf(colour + "_TERRACOTTA")));
                    }
                }
            }
        }
    }
}
