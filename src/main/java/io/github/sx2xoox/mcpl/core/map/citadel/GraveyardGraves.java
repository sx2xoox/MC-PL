package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.citadel.GraveyardBuilder.Use;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * Everything small and many in the graveyard: the broken cemetery wall and the lychgate facing the ring road, the
 * statue on the plaza, iron-fenced family plots and the rows of tombstones that fill the wings.
 *
 * <p>Tombstones face south (towards the keep). Every one of them can be stepped onto: taller stones have a ledger
 * slab or a curb in front, so nothing on the ground is a place a player could see but never reach.
 */
final class GraveyardGraves {

    private static final int G = GraveyardBuilder.GROUND;

    private static final Material[] STONE_BLOCKS = {Material.CHISELED_STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
            Material.CRACKED_STONE_BRICKS, Material.STONE_BRICKS, Material.POLISHED_ANDESITE, Material.ANDESITE,
            Material.TUFF_BRICKS, Material.POLISHED_TUFF, Material.CHISELED_TUFF, Material.DEEPSLATE_TILES,
            Material.POLISHED_DEEPSLATE, Material.CHISELED_DEEPSLATE, Material.COBBLED_DEEPSLATE,
            Material.MOSSY_COBBLESTONE, Material.CHISELED_STONE_BRICKS, Material.POLISHED_ANDESITE};
    private static final Material[] STONE_STAIRS = {Material.STONE_BRICK_STAIRS, Material.MOSSY_STONE_BRICK_STAIRS,
            Material.ANDESITE_STAIRS, Material.POLISHED_ANDESITE_STAIRS, Material.TUFF_BRICK_STAIRS,
            Material.DEEPSLATE_BRICK_STAIRS, Material.DEEPSLATE_TILE_STAIRS, Material.COBBLED_DEEPSLATE_STAIRS,
            Material.POLISHED_BLACKSTONE_STAIRS};
    /** Wooden grave crosses and their iron-dark kin (fences connect into a cross shape). */
    private static final Material[] CROSS_WOODS = {Material.DARK_OAK_FENCE, Material.SPRUCE_FENCE,
            Material.DARK_OAK_FENCE, Material.NETHER_BRICK_FENCE};
    private static final Material[] STONE_SLABS = {Material.STONE_BRICK_SLAB, Material.MOSSY_STONE_BRICK_SLAB,
            Material.POLISHED_ANDESITE_SLAB, Material.ANDESITE_SLAB, Material.TUFF_BRICK_SLAB,
            Material.DEEPSLATE_TILE_SLAB, Material.POLISHED_DEEPSLATE_SLAB, Material.COBBLED_DEEPSLATE_SLAB};
    private static final Material[] GRAVE_SOIL = {Material.PODZOL, Material.PODZOL, Material.PODZOL,
            Material.COARSE_DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT};

    /** A family plot: an iron or nether brick fence around a few graves and an obelisk. */
    private record Plot(int x1, int z1, int x2, int z2, Material fence) {
    }

    private static final List<Plot> PLOTS = List.of(new Plot(-27, -62, -21, -57, Material.IRON_BARS),
            new Plot(7, -48, 13, -43, Material.IRON_BARS), new Plot(-9, -93, -3, -89, Material.NETHER_BRICK_FENCE));

    private final GraveyardBuilder b;

    GraveyardGraves(GraveyardBuilder builder) {
        this.b = builder;
    }

    void reserve() {
        // Lychgate pillars and wings, the statue, the plots.
        b.mark(-9, -41, -3, -39, Use.STRUCTURE);
        b.mark(3, -41, 9, -39, Use.STRUCTURE);
        b.mark(-1, -47, 1, -45, Use.STRUCTURE);
        for (Plot p : PLOTS) {
            b.mark(p.x1(), p.z1(), p.x2(), p.z2(), Use.STRUCTURE);
        }
    }

    void build() {
        lychgate();
        cemeteryWall();
        statue();
        for (Plot p : PLOTS) {
            plot(p);
        }
        roadFences(240, 1);
        roadFences(300, -1);
        laneArch(-21, -16, -66);
        laneArch(16, 21, -64);
        for (int[] spot : LAMPS) {
            lampPost(spot[0], spot[1]);
        }
        fields();
        boulders();
    }

    /** Where hanging lamp posts stand (each looks for a free spot next to a path near these). */
    private static final int[][] LAMPS = {{-17, -53}, {17, -53}, {3, -85}, {-21, -80}, {20, -78}, {-3, -85}};

    /**
     * A tall wooden lamp post beside a path with a soul lantern hanging over it from an arm. It takes the first free
     * column next to a path within a few blocks of {@code tx,tz}.
     */
    private void lampPost(int tx, int tz) {
        for (int r = 0; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = tx + dx;
                    int z = tz + dz;
                    if (!b.free(x, z, 2) || b.ground(x, z) != G) {
                        continue;
                    }
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int ax = x + d[0];
                        int az = z + d[1];
                        if (b.use(ax, az) == Use.PATH && b.inside(ax, az) && b.isAir(ax, G + 1, az)) {
                            b.mark(x, z, Use.STRUCTURE);
                            for (int y = G + 1; y <= G + 6; y++) {
                                b.set(x, y, z, BlockSpec.of(Material.DARK_OAK_FENCE));
                            }
                            b.set(ax, G + 6, az, BlockSpec.of(Material.DARK_OAK_FENCE));
                            b.hangingLantern(ax, G + 6, az, 1, true);
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * A broken arch over a side lane between pillars at {@code xLeft} and {@code xRight}: the left pillar and half the
     * arch still stand, the right pillar is a stump and the rest lies in the lane. It is built on the first row near
     * {@code z} where both pillars find free ground.
     */
    private void laneArch(int xLeft, int xRight, int z) {
        for (int i = 0; i <= 8; i++) {
            int az = z + (i % 2 == 0 ? i / 2 : -(i + 1) / 2);
            if (!b.free(xLeft, az, 2) || !b.free(xRight, az, 2) || b.ground(xLeft, az) != G
                    || b.ground(xRight, az) != G) {
                continue;
            }
            b.mark(xLeft, az, Use.STRUCTURE);
            b.mark(xRight, az, Use.STRUCTURE);
            for (int y = G + 1; y <= G + 5; y++) {
                b.set(xLeft, y, az, y == G + 1 ? BlockSpec.of(Material.POLISHED_ANDESITE)
                        : GraveyardBuilder.STONE_RUIN.pick(b.random));
            }
            for (int y = G + 1; y <= G + 2; y++) {
                b.set(xRight, y, az, GraveyardBuilder.STONE_RUIN.pick(b.random));
            }
            b.set(xLeft + 1, G + 5, az, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.WEST));
            b.set(xLeft + 2, G + 5, az, Blocks.slabTop(Material.STONE_BRICK_SLAB));
            b.hangingLantern(xLeft + 1, G + 5, az, 0, true);
            b.set(xLeft + 3, G + 1, az + 1, Blocks.slab(Material.STONE_BRICK_SLAB));
            b.set(xLeft + 2, G + 1, az - 1, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
            return;
        }
    }

    // ------------------------------------------------------------------ entrance

    /**
     * The lychgate over the processional way: two 2x2 pillars and an arch with a hanging soul lantern. The cemetery
     * wall climbs up to the pillars on both sides, so the top of the gate is a (small) perch.
     */
    private void lychgate() {
        int z = -41;
        for (int side = -1; side <= 1; side += 2) {
            for (int x = 3; x <= 4; x++) {
                for (int dz = 0; dz <= 1; dz++) {
                    for (int y = G + 1; y <= G + 5; y++) {
                        BlockSpec spec = y == G + 5 ? BlockSpec.of(Material.CHISELED_STONE_BRICKS)
                                : y == G + 1 ? BlockSpec.of(Material.POLISHED_DEEPSLATE)
                                : GraveyardBuilder.STONE_RUIN.pick(b.random);
                        b.set(side * x, y, z + dz, spec);
                    }
                }
            }
            b.set(side * 4, G + 6, z, Blocks.lantern(true, false));
            // Arch.
            b.set(side * 2, G + 5, z, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, side < 0 ? BlockFace.WEST
                    : BlockFace.EAST));
            b.set(side * 2, G + 5, z + 1, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, side < 0 ? BlockFace.WEST
                    : BlockFace.EAST));
            // The wall wing climbing up to the pillar.
            for (int i = 0; i < 5; i++) {
                int x = side * (5 + i);
                for (int y = G + 1; y <= G + 5 - i; y++) {
                    b.set(x, y, z + 1, GraveyardBuilder.STONE_RUIN.pick(b.random));
                }
            }
        }
        for (int x = -1; x <= 1; x++) {
            b.set(x, G + 5, z, Blocks.slabTop(Material.STONE_BRICK_SLAB));
            b.set(x, G + 5, z + 1, Blocks.slabTop(Material.STONE_BRICK_SLAB));
        }
        b.set(0, G + 4, z, Blocks.lantern(true, true));
    }

    /** Broken stretches of the cemetery wall along the ring road, with wide gaps. */
    private void cemeteryWall() {
        arcWall(40.5, 247, 254, 2, 11);
        arcWall(40.5, 286, 293, 2, 12);
    }

    private void arcWall(double radius, double from, double to, int maxHeight, int salt) {
        List<int[]> columns = new ArrayList<>();
        for (double a = from; a <= to; a += 0.25) {
            int[] p = CitadelLayout.polar(radius, a);
            int[] last = columns.isEmpty() ? null : columns.get(columns.size() - 1);
            if (last == null || last[0] != p[0] || last[1] != p[1]) {
                columns.add(p);
            }
        }
        int[] tops = new int[columns.size()];
        for (int i = 0; i < tops.length; i++) {
            int fromEnd = Math.min(i, tops.length - 1 - i) + 1;
            int wanted = 1 + (int) Math.round(maxHeight * b.noise(i, salt * 5.0, 2.5, salt));
            tops[i] = Math.max(1, Math.min(Math.min(maxHeight, fromEnd), wanted));
        }
        GraveyardBuilder.smoothProfile(tops);
        for (int i = 0; i < tops.length; i++) {
            int[] p = columns.get(i);
            if (!b.free(p[0], p[1], 1.5)) {
                continue;
            }
            b.mark(p[0], p[1], Use.STRUCTURE);
            for (int y = G + 1; y <= G + tops[i]; y++) {
                b.set(p[0], y, p[1], GraveyardBuilder.STONE_RUIN.pick(b.random));
            }
            // Iron railings on the lower stretches.
            if (tops[i] == 1 && b.hash(p[0], p[1], salt) < 0.5) {
                b.set(p[0], G + 2, p[1], BlockSpec.of(Material.IRON_BARS));
            }
        }
    }

    /** A hooded figure on a pedestal in the middle of the plaza, with lanterns on the ground around it. */
    private void statue() {
        int cx = 0;
        int cz = -46;
        int y = G + 1;
        b.set(cx, y, cz, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
        b.set(cx, y, cz - 1, Blocks.stairs(Material.POLISHED_ANDESITE_STAIRS, BlockFace.SOUTH));
        b.set(cx, y, cz + 1, Blocks.stairs(Material.POLISHED_ANDESITE_STAIRS, BlockFace.NORTH));
        b.set(cx - 1, y, cz, Blocks.stairs(Material.POLISHED_ANDESITE_STAIRS, BlockFace.EAST));
        b.set(cx + 1, y, cz, Blocks.stairs(Material.POLISHED_ANDESITE_STAIRS, BlockFace.WEST));
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                b.set(cx + dx, y, cz + dz, Blocks.slab(Material.POLISHED_ANDESITE_SLAB));
            }
        }
        b.set(cx, y + 1, cz, BlockSpec.of(Material.POLISHED_ANDESITE));
        b.set(cx, y + 2, cz, BlockSpec.of(Material.POLISHED_ANDESITE));
        b.set(cx, y + 3, cz, BlockSpec.of(Material.POLISHED_DEEPSLATE));
        b.set(cx, y + 4, cz, BlockSpec.of(Material.SKELETON_SKULL));
        // Sleeves and the hood's back, a scythe in its hand.
        b.set(cx - 1, y + 3, cz, Blocks.stairsTop(Material.POLISHED_DEEPSLATE_STAIRS, BlockFace.EAST));
        b.set(cx + 1, y + 3, cz, Blocks.stairsTop(Material.POLISHED_DEEPSLATE_STAIRS, BlockFace.WEST));
        b.set(cx, y + 4, cz - 1, Blocks.stairs(Material.POLISHED_DEEPSLATE_STAIRS, BlockFace.NORTH));
        for (int dy = 1; dy <= 4; dy++) {
            b.set(cx + 1, y + dy, cz + 1, BlockSpec.of(Material.DARK_OAK_FENCE));
        }
        b.set(cx, y + 5, cz + 1, Blocks.trapdoor(Material.IRON_TRAPDOOR, BlockFace.WEST, true, false));
        b.set(cx + 1, y + 5, cz + 1, Blocks.trapdoor(Material.IRON_TRAPDOOR, BlockFace.WEST, true, false));
        for (int[] d : new int[][] {{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            b.set(cx + d[0], y, cz + d[1], Blocks.lantern(true, false));
        }
    }

    /**
     * Stretches of low iron railing with stone posts a couple of blocks inside the district along a radial road, with
     * wide gaps (and gaps wherever a path or anything else is in the way): they frame the cemetery without closing it.
     */
    private void roadFences(int roadAngle, int side) {
        double a = Math.toRadians(roadAngle);
        double nx = -Math.sin(a) * side;
        double nz = Math.cos(a) * side;
        double offset = CitadelLayout.ROAD_HALF_WIDTH + 2.2;
        int index = 0;
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (double r = 44; r <= 91; r += 0.35) {
            int x = (int) Math.round(Math.cos(a) * r + nx * offset);
            int z = (int) Math.round(Math.sin(a) * r + nz * offset);
            if (x == lastX && z == lastZ) {
                continue;
            }
            lastX = x;
            lastZ = z;
            int phase = index++ % 11;
            if (phase >= 6 || !b.inside(x, z) || b.use(x, z) != Use.FREE || b.ground(x, z) != G) {
                continue;
            }
            b.mark(x, z, Use.STRUCTURE);
            BlockSpec spec = phase == 0 || phase == 5 ? BlockSpec.of(b.chance(0.5) ? Material.POLISHED_ANDESITE
                    : Material.MOSSY_STONE_BRICKS) : b.chance(0.85) ? BlockSpec.of(Material.IRON_BARS) : null;
            if (spec != null) {
                b.set(x, G + 1, z, spec);
            }
        }
    }

    // ------------------------------------------------------------------ family plots

    private void plot(Plot p) {
        int y = G + 1;
        for (int x = p.x1(); x <= p.x2(); x++) {
            for (int z = p.z1(); z <= p.z2(); z++) {
                boolean edge = x == p.x1() || x == p.x2() || z == p.z1() || z == p.z2();
                boolean corner = (x == p.x1() || x == p.x2()) && (z == p.z1() || z == p.z2());
                if (!b.inside(x, z)) {
                    continue;
                }
                if (corner) {
                    b.set(x, y, z, BlockSpec.of(Material.POLISHED_ANDESITE));
                    b.set(x, y + 1, z, BlockSpec.of(Material.CANDLE).lit(true));
                } else if (edge) {
                    // Gates in the middle of the long sides, and missing railings.
                    boolean gate = z == p.z2() && Math.abs(x - (p.x1() + p.x2()) / 2) <= 0
                            || z == p.z1() && x == p.x2() - 1;
                    if (!gate && b.hash(x, z, 23) > 0.18) {
                        b.set(x, y, z, BlockSpec.of(p.fence()));
                    }
                    b.set(x, G, z, BlockSpec.of(Material.COBBLESTONE));
                } else {
                    b.set(x, G, z, BlockSpec.of(b.hash(x, z, 24) < 0.5 ? Material.PODZOL : Material.COARSE_DIRT));
                }
            }
        }
        // Inside: an obelisk and a row of graves.
        int ox = (p.x1() + p.x2()) / 2;
        int oz = p.z1() + 1;
        obelisk(ox, oz, y);
        for (int x = p.x1() + 1; x <= p.x2() - 1; x += 2) {
            if (Math.abs(x - ox) <= 1) {
                continue;
            }
            int z = p.z1() + 1;
            b.set(x, y, z, Blocks.stairs(b.pick(STONE_STAIRS), BlockFace.NORTH));
            b.set(x, G, z + 1, BlockSpec.of(b.pick(GRAVE_SOIL)));
            b.set(x, G, z + 2, BlockSpec.of(b.pick(GRAVE_SOIL)));
        }
    }

    /** A slim stone needle on a stepped base (its tip is the only spot that cannot be reached). */
    private void obelisk(int x, int z, int y) {
        b.set(x, y, z, BlockSpec.of(Material.POLISHED_DEEPSLATE));
        b.set(x, y + 1, z, BlockSpec.of(Material.DEEPSLATE_TILES));
        b.set(x, y + 2, z, BlockSpec.of(Material.POLISHED_DEEPSLATE));
        b.set(x, y + 3, z, BlockSpec.of(Material.CHISELED_DEEPSLATE));
        b.set(x - 1, y, z, Blocks.stairs(Material.POLISHED_DEEPSLATE_STAIRS, BlockFace.EAST));
        b.set(x + 1, y, z, Blocks.stairs(Material.POLISHED_DEEPSLATE_STAIRS, BlockFace.WEST));
        b.set(x, y, z + 1, Blocks.stairs(Material.POLISHED_DEEPSLATE_STAIRS, BlockFace.NORTH));
    }

    // ------------------------------------------------------------------ tombstone fields

    /**
     * Tombstones in rows over all the free ground: a headstone on every second column of every third row (the grave in
     * front of it, a walkway behind), thinned by a density map that leaves open clearings for fighting and keeps the
     * outer rim, the grove and the barrow wilder.
     */
    private void fields() {
        for (int z = GraveyardBuilder.MIN_Z; z <= GraveyardBuilder.MAX_Z; z++) {
            if (Math.floorMod(z, 3) != 0) {
                continue;
            }
            for (int x = GraveyardBuilder.MIN_X; x <= GraveyardBuilder.MAX_X; x += 2) {
                if (b.hash(x, z, 100) <= density(x, z)) {
                    grave(x, z + (b.hash(x, z, 101) < 0.08 ? 1 : 0));
                }
            }
        }
    }

    private double density(int x, int z) {
        if (b.noise(x, z, 9, 102) < 0.18) {
            return 0;
        }
        if (Math.hypot((x - GraveyardCrypts.BARROW_X) / 11.5, (z - GraveyardCrypts.BARROW_Z) / 6.5) < 1) {
            return 0.3;
        }
        if (x > 14 && z < -79) {
            return 0.4;
        }
        return CitadelLayout.radius(x, z) > 89 ? 0.6 : 0.9;
    }

    private void grave(int x, int z) {
        if (!b.free(x, z, 1.5) || !b.free(x, z + 1, 1.5)) {
            return;
        }
        int ground = b.ground(x, z);
        if (b.ground(x, z + 1) != ground) {
            return;
        }
        int y = ground + 1;
        if (!b.isAir(x, y, z) || !b.isAir(x, y, z + 1)) {
            return;
        }
        b.mark(x, z, Use.GRAVE);
        b.mark(x, z + 1, Use.GRAVE);
        // Neighbouring graves of a row share a style (a family, a decade), with a few odd ones out.
        int segment = Math.floorDiv(x, 8);
        int row = Math.floorDiv(z, 3);
        boolean matching = b.random.nextDouble() < 0.72;
        double roll = matching ? b.hash(segment, row, 120) : b.random.nextDouble();
        double shade = matching ? b.hash(segment, row, 121) : b.random.nextDouble();
        if (roll < 0.36) {
            b.set(x, y, z, BlockSpec.of(STONE_BLOCKS[(int) (shade * STONE_BLOCKS.length)]));
        } else if (roll < 0.56) {
            b.set(x, y, z, Blocks.stairs(STONE_STAIRS[(int) (shade * STONE_STAIRS.length)], BlockFace.NORTH));
        } else if (roll < 0.66) {
            // A headstone with an overhanging cap.
            b.set(x, y, z, Blocks.stairsTop(STONE_STAIRS[(int) (shade * STONE_STAIRS.length)], BlockFace.NORTH));
        } else if (roll < 0.73) {
            cross(x, y, z);
            return;
        } else if (roll < 0.79) {
            // A tall headstone with a ledger slab over the grave.
            b.set(x, y, z, BlockSpec.of(b.pick(STONE_BLOCKS)));
            b.set(x, y + 1, z, Blocks.stairs(b.pick(STONE_STAIRS), BlockFace.NORTH));
            b.set(x, y, z + 1, Blocks.slab(b.pick(STONE_SLABS)));
            return;
        } else if (roll < 0.86) {
            // A tomb chest over the whole grave.
            Material chest = b.chance(0.5) ? Material.POLISHED_DEEPSLATE : Material.POLISHED_BLACKSTONE_BRICKS;
            b.set(x, y, z, BlockSpec.of(chest));
            b.set(x, y, z + 1, BlockSpec.of(chest));
            if (b.chance(0.5)) {
                b.set(x, y + 1, z, b.chance(0.5) ? BlockSpec.of(Material.SKELETON_SKULL)
                        : BlockSpec.of(Material.CANDLE).lit(true));
            }
            return;
        } else if (roll < 0.92) {
            // Fallen headstone.
            b.set(x, y, z, Blocks.slab(b.pick(STONE_SLABS)));
        } else if (roll < 0.96) {
            // An open grave, one block deep.
            b.set(x, y, z, Blocks.stairs(b.pick(STONE_STAIRS), BlockFace.NORTH));
            b.clear(x, ground, z + 1);
            b.set(x, ground - 1, z + 1, BlockSpec.of(Material.COARSE_DIRT));
            return;
        }
        // The grave itself: dug soil, sometimes a candle, a flower or a skull.
        b.set(x, ground, z + 1, BlockSpec.of(b.pick(GRAVE_SOIL)));
        double deco = b.random.nextDouble();
        if (deco < 0.12) {
            b.set(x, y, z + 1, BlockSpec.of(b.chance(0.7) ? Material.CANDLE : Material.WHITE_CANDLE).lit(true));
        } else if (deco < 0.2) {
            b.set(x, y, z + 1, BlockSpec.of(b.chance(0.5) ? Material.LILY_OF_THE_VALLEY : Material.POPPY));
        } else if (deco < 0.23) {
            b.set(x, y, z + 1, BlockSpec.of(Material.SKELETON_SKULL));
        }
    }

    /** A wooden cross with a stone curb in front of it (the curb makes its arms reachable). */
    private void cross(int x, int y, int z) {
        if (!b.free(x - 1, z, 2.5) || !b.free(x + 1, z, 2.5) || !b.free(x - 1, z + 1, 2.5)
                || !b.free(x + 1, z + 1, 2.5)) {
            b.set(x, y, z, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
            return;
        }
        Material wood = b.pick(CROSS_WOODS);
        b.set(x, y, z, BlockSpec.of(wood));
        b.set(x, y + 1, z, BlockSpec.of(wood));
        b.set(x - 1, y + 1, z, BlockSpec.of(wood));
        b.set(x + 1, y + 1, z, BlockSpec.of(wood));
        Material slab = b.pick(STONE_SLABS);
        for (int dx = -1; dx <= 1; dx++) {
            b.set(x + dx, y, z + 1, Blocks.slab(slab));
            b.mark(x + dx, z, Use.GRAVE);
            b.mark(x + dx, z + 1, Use.GRAVE);
        }
    }

    // ------------------------------------------------------------------ rocks

    /** A few mossy boulders for cover in the open stretches near the roads. */
    private void boulders() {
        int[][] spots = {{29, -59}, {-6, -38}, {13, -38}, {-24, -55}, {31, -64}, {-30, -81}};
        for (int[] s : spots) {
            if (!b.free(s[0], s[1], 2.0)) {
                continue;
            }
            b.rubble(s[0], s[1], b.ground(s[0], s[1]), 2.3, GraveyardBuilder.Palette.of(Material.MOSSY_COBBLESTONE,
                    3, Material.ANDESITE, 2, Material.TUFF, 2, Material.STONE, 2, Material.COBBLESTONE, 1));
            b.mark(s[0], s[1], Use.STRUCTURE);
        }
    }
}
