package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.Random;
import java.util.function.BiPredicate;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.bukkit.Material;

/**
 * The corporate firewall and the district behind it. The wall (16 high, magenta neon) is open on the avenue under a
 * billboard lintel, broken on the north street (the rubble steps up onto the wall-walk) and spans the canal as a
 * culvert. Behind it, the round 방화벽 게이트 plaza with its gate pylons, the security HQ (enterable) and the open
 * checkpoint where the defenders spawn while the front tower stands.
 */
final class SiegeFirewallZone implements MapZone {

    private static final int G = SiegeLayout.GROUND_Y;
    private static final int N = BlockSpec.NORTH;
    private static final int S = BlockSpec.SOUTH;
    private static final int X1 = SiegeLayout.WALL_X1;
    private static final int X2 = SiegeLayout.WALL_X2;
    private static final int TOP = SiegeLayout.WALL_TOP;
    /** The breach on the north street: open between these z, the broken wall steps up north of it. */
    private static final int BREACH_Z1 = -54;
    private static final int BREACH_Z2 = -44;

    @Override
    public String name() {
        return "firewall";
    }

    @Override
    public boolean contains(int x, int z) {
        return SiegeLayout.isInterior(x, z) && x >= SiegeLayout.FIREWALL_WEST && x < SiegeLayout.HUB_WEST;
    }

    @Override
    public void build(MapCanvas canvas) {
        Random random = canvas.random(name());
        wall(canvas, random);
        plaza(canvas, random);
        checkpoint(canvas, random);
        // Security HQ (enterable) north of the avenue, facing the plaza.
        SiegeKit.building(canvas, random, new SiegeKit.Building(-45, -43, -31, -18, 4, true, N | S));
        SiegeStreets.block(canvas, random, -29, -43, -21, -18, N | S, 2, 3, 0);
        SiegeStreets.block(canvas, random, -73, -43, -57, -20, N | S, 2, 4, 0.5);
        SiegeStreets.block(canvas, random, -73, 20, -57, 41, N | S, 2, 4, 0.5);
        SiegeStreets.block(canvas, random, -45, 33, -21, 41, N | S, 2, 3, 0);
        SiegeStreets.block(canvas, random, -73, -88, -57, -57, S, 3, 6, 0.5);
        SiegeStreets.block(canvas, random, -44, -88, -21, -57, S, 3, 6, 0.5);
        SiegeStreets.block(canvas, random, -73, 59, -57, 88, N, 3, 6, 0.5);
        SiegeStreets.block(canvas, random, -44, 59, -21, 88, N, 3, 6, 0.5);
        BiPredicate<Integer, Integer> keep = SiegeZones::keepFree;
        SiegeStreets.streetAlongX(canvas, random, -73, -11, -SiegeLayout.MAIN_HALF, SiegeLayout.MAIN_HALF, keep);
        SiegeStreets.streetAlongX(canvas, random, -73, -11, SiegeLayout.NORTH_Z1, SiegeLayout.NORTH_Z2, keep);
        SiegeStreets.streetAlongZ(canvas, random, -20, -12, -88, 88, keep);
        SiegeStreets.streetAlongZ(canvas, random, -54, -46, -88, -20, keep);
        SiegeStreets.streetAlongZ(canvas, random, -54, -46, 20, 88, keep);
    }

    // ------------------------------------------------------------------ the wall

    private void wall(MapCanvas canvas, Random random) {
        int side = SiegeLayout.SIDE;
        for (int z = -side; z <= side; z++) {
            int height = wallHeight(z);
            boolean gate = Math.abs(z) <= SiegeLayout.MAIN_HALF;
            boolean culvert = z >= SiegeLayout.CANAL_Z1 && z <= SiegeLayout.CANAL_Z2;
            for (int x = X1; x <= X2; x++) {
                if (culvert) {
                    canvas.set(x, G, z, BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
                }
                for (int y = G + 1; y <= height; y++) {
                    if (gate && y <= G + 10) {
                        continue;
                    }
                    canvas.set(x, y, z, wallBlock(x, y, z, random));
                }
                if (height == TOP && (x == X1 || x == X2)) {
                    canvas.set(x, TOP + 1, z, BlockSpec.of(Material.POLISHED_BLACKSTONE_WALL));
                }
            }
            if (culvert && Math.abs(z - 50) > 2) {
                canvas.set(X1, G - 1, z, BlockSpec.of(Material.IRON_BARS));
            }
            // Laser fence on top: iron bars between glowing posts.
            if (height == TOP && Math.floorMod(z, 8) == 0) {
                canvas.set(X1, TOP + 2, z, SiegeKit.glow("MAGENTA"));
            }
        }
        // The rubble of the breach.
        for (int z = BREACH_Z1; z <= BREACH_Z2; z++) {
            for (int x = X1 - 2; x <= X2 + 2; x++) {
                if (random.nextInt(4) == 0 && canvas.get(x, G + 1, z) == null) {
                    canvas.set(x, G + 1, z, SiegeKit.pick(random, Material.CRACKED_POLISHED_BLACKSTONE_BRICKS,
                            Material.COBBLED_DEEPSLATE, Material.BLACKSTONE));
                }
            }
        }
        // The lintel over the gate: a billboard facing the attackers.
        SiegeKit.board(canvas, random, -SiegeLayout.MAIN_HALF, G + 11, X1, 2 * SiegeLayout.MAIN_HALF + 1, 6, false,
                -1, "MAGENTA", "PURPLE");
        // Gate pylons.
        for (int sign : new int[] {-1, 1}) {
            for (int z = 11; z <= 13; z++) {
                for (int x = X1 - 2; x <= X2 + 2; x++) {
                    for (int y = G + 1; y <= TOP + 6; y++) {
                        boolean edge = x == X1 - 2 || x == X2 + 2;
                        canvas.set(x, y, sign * z, edge && y % 3 == 0 ? SiegeKit.glow("MAGENTA")
                                : BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
                    }
                }
            }
        }
    }

    /** Top block of the wall at {@code z}: full height, broken around the breach (steps up north of it). */
    private static int wallHeight(int z) {
        if (z >= BREACH_Z1 && z <= BREACH_Z2) {
            return G;
        }
        if (z < BREACH_Z1 && z >= BREACH_Z1 - (TOP - G)) {
            // Steps: one block up per block north, from G+1 next to the breach to the wall-walk.
            return G + (BREACH_Z1 - z);
        }
        if (z > BREACH_Z2 && z <= BREACH_Z2 + 4) {
            return G + 3 * (z - BREACH_Z2) + 1;
        }
        return TOP;
    }

    private static BlockSpec wallBlock(int x, int y, int z, Random random) {
        if (x == X1 && (y == G + 6 || y == G + 12) && Math.floorMod(z, 3) != 0) {
            return SiegeKit.glow("MAGENTA");
        }
        if (x == X1 && Math.floorMod(z, 12) == 0) {
            return BlockSpec.of(Material.BLACK_CONCRETE);
        }
        if (y == TOP) {
            return BlockSpec.of(Material.POLISHED_DEEPSLATE);
        }
        int roll = random.nextInt(10);
        return roll < 6 ? BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS) : roll < 8
                ? BlockSpec.of(Material.DEEPSLATE_TILES) : BlockSpec.of(Material.CRACKED_POLISHED_BLACKSTONE_BRICKS);
    }

    // ------------------------------------------------------------------ the plaza

    /**
     * The 방화벽 게이트 plaza: the platform stays clear; around it planters and barriers at radius 12-14 (cover that
     * leaves the avenue and the cross street open), lamps, and two gate pylons north and south carrying a lintel high
     * above the tower.
     */
    private void plaza(MapCanvas canvas, Random random) {
        int cx = SiegeLayout.FRONT_X;
        for (int a = 0; a < 4; a++) {
            double angle = Math.PI / 4 + a * Math.PI / 2;
            int px = cx + (int) Math.round(13 * Math.cos(angle));
            int pz = (int) Math.round(13 * Math.sin(angle));
            SiegeKit.planter(canvas, random, px - 1, pz - 1, px + 1, pz + 1, G + 1);
            int lx = cx + (int) Math.round(16 * Math.cos(angle));
            int lz = (int) Math.round(16 * Math.sin(angle));
            SiegeKit.lamp(canvas, lx, lz, 0, lz > 0 ? -1 : 1, "MAGENTA");
        }
        for (int a = 0; a < 8; a++) {
            double angle = Math.PI / 8 + a * Math.PI / 4;
            int bx = cx + (int) Math.round(11.5 * Math.cos(angle));
            int bz = (int) Math.round(11.5 * Math.sin(angle));
            canvas.set(bx, G + 1, bz, BlockSpec.of(a % 2 == 0 ? Material.YELLOW_CONCRETE : Material.BLACK_CONCRETE));
        }
        // Gate pylons with the lintel above the tower's clearance.
        int lintel = G + 1 + SiegeLayout.TOWER_CLEARANCE + 2;
        for (int sign : new int[] {-1, 1}) {
            for (int x = cx - 2; x <= cx + 1; x++) {
                for (int z = 17; z <= 20; z++) {
                    for (int y = G + 1; y <= lintel + 4; y++) {
                        boolean edge = x == cx - 2 || x == cx + 1 || z == 17 || z == 20;
                        canvas.set(x, y, sign * z, edge && y % 4 == 1 ? SiegeKit.glow("MAGENTA")
                                : edge ? BlockSpec.of(Material.BLACK_CONCRETE)
                                : BlockSpec.of(Material.POLISHED_BLACKSTONE));
                    }
                }
            }
        }
        for (int z = -16; z <= 16; z++) {
            for (int x = cx - 2; x <= cx + 1; x++) {
                canvas.set(x, lintel, z, BlockSpec.of(Material.POLISHED_BLACKSTONE));
                canvas.set(x, lintel + 1, z, x == cx - 2 || x == cx + 1 ? SiegeKit.glow("MAGENTA")
                        : BlockSpec.of(Material.BLACK_CONCRETE));
                canvas.set(x, lintel + 2, z, BlockSpec.of(Material.POLISHED_BLACKSTONE));
            }
        }
    }

    // ------------------------------------------------------------------ the checkpoint

    /** An open checkpoint pavilion (defender spawn 0): a roof on columns, barriers with gaps, scanner arches. */
    private void checkpoint(MapCanvas canvas, Random random) {
        int x1 = SiegeLayout.CHECKPOINT_X1;
        int x2 = SiegeLayout.CHECKPOINT_X2;
        int z1 = SiegeLayout.CHECKPOINT_Z1;
        int z2 = SiegeLayout.CHECKPOINT_Z2;
        int roof = G + 6;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                canvas.set(x, roof, z, edge ? (Math.floorMod(x + z, 2) == 0 ? SiegeKit.glow("YELLOW")
                        : BlockSpec.of(Material.BLACK_CONCRETE)) : BlockSpec.of(Material.GRAY_CONCRETE));
                if (!edge && Math.floorMod(x - x1, 4) == 2 && Math.floorMod(z - z1, 4) == 2) {
                    canvas.set(x, roof, z, BlockSpec.of(Material.SEA_LANTERN));
                }
                canvas.set(x, G, z, Math.floorMod(x + z, 2) == 0 ? BlockSpec.of(Material.POLISHED_DEEPSLATE)
                        : BlockSpec.of(Material.YELLOW_CONCRETE));
            }
        }
        for (int x : new int[] {x1, (x1 + x2) / 2, x2}) {
            for (int z : new int[] {z1, (z1 + z2) / 2, z2}) {
                for (int y = G + 1; y < roof; y++) {
                    canvas.set(x, y, z, BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
                }
            }
        }
        // Barriers on the west side (towards the attackers) with two scanner arches as gaps.
        for (int z = z1 + 1; z < z2; z++) {
            int gapA = z1 + 4;
            int gapB = z2 - 4;
            if (Math.abs(z - gapA) <= 1 || Math.abs(z - gapB) <= 1) {
                if (z == gapA || z == gapB) {
                    canvas.set(x1 - 1, roof - 1, z, SiegeKit.glow("LIME"));
                }
                continue;
            }
            canvas.set(x1 - 1, G + 1, z, Blocks.slab(Material.POLISHED_BLACKSTONE_SLAB));
        }
        for (int z : new int[] {z1 + 3, z1 + 5, z2 - 5, z2 - 3}) {
            for (int y = G + 1; y < roof - 1; y++) {
                canvas.set(x1 - 1, y, z, BlockSpec.of(Material.IRON_BARS));
            }
            canvas.set(x1 - 1, roof - 1, z, BlockSpec.of(Material.POLISHED_BLACKSTONE));
        }
        // A billboard on the roof facing the plaza.
        SiegeKit.board(canvas, random, z1 + 3, roof + 1, x1 + 2, 10, 5, false, -1, "YELLOW", "ORANGE");
    }
}
