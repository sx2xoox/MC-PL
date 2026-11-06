package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.PropMotion;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.Quarter;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The jungle between the lanes, a toy room on the play mat: a few long walls of toy bricks shape a middle corridor
 * (castle front gate to the dragon's sandbox, starting on the castle's checkered mat) and two rows of clearings, one
 * per objective. Every clearing has its own round rug (a white ring in it) and its own landmark just outside the open
 * area: jelly cubes on a pink rug (젤리 무리), a cheese wedge with a wind-up key on a yellow one (태엽 쥐 떼), a big toy
 * box on a yellow one (곰인형 대장), rabbit ears with a pocket watch between them on a pink one (시계 토끼), a candy
 * fountain on a light blue one (사탕 샘물) and a telescope tower on a light blue one (망원경 탑). Piles of toy boxes and
 * cotton candy trees fill the corners in between (with a box of crayons on the TOP side and a jar of marbles on the
 * BOTTOM side), and giant toys stand in the far corner pockets beyond the lanes' bends (a stack of alphabet blocks by
 * the TOP lane, a spinning top by the BOTTOM lane). Most toys are models ({@link #props}, {@code models/props.py}) on
 * invisible footings where their bulk stands (two blocks high, so nobody walks through or hops onto them); the fountain
 * and the telescope tower stay blocks, shaped with stairs, slabs and other detail blocks in a second tone of their
 * colours ({@link ToyKit}).
 */
final class ToyJungleZone implements MapZone {

    /** Toy brick walls in quarter coordinates (both sides, both halves): {ax1, az1, ax2, az2, radius}. */
    static final double[][] WALLS = {
        {24, 12, 34, 12, 1.5},
        {46, 12, 58, 12, 1.5},
        {43, 21, 43, 35, 1.5},
        {25, 21, 25, 35, 1.5},
    };
    private static final BlockSpec[] WALL_COLOURS = {ToyKit.YELLOW, ToyKit.PINK, ToyKit.SKY, ToyKit.SKY};

    /** Cotton candy trees (models, walked under) in quarter coordinates: {ax, az} of the stick. */
    static final int[][] TREES = {{10, 17}};

    /** Rug radius and its white ring. */
    private static final double RUG = 6.5;

    @Override
    public String name() {
        return "jungle";
    }

    @Override
    public boolean contains(int x, int z) {
        return ToyLayout.partOf(x, z).equals(name());
    }

    @Override
    public void build(MapCanvas canvas) {
        int r = ToyLayout.BUILD_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (contains(x, z)) {
                    ToyKit.ground(canvas, x, z);
                }
            }
        }
        for (Side side : Side.values()) {
            Quarter top = Quarter.of(side, Lane.TOP);
            Quarter bottom = Quarter.of(side, Lane.BOTTOM);
            rug(canvas, top, 52, 22, ToyKit.PINK);
            rug(canvas, top, 34, 28, ToyKit.YELLOW);
            rug(canvas, top, 16, 26, ToyKit.SKY);
            rug(canvas, bottom, 52, 22, ToyKit.YELLOW);
            rug(canvas, bottom, 34, 28, ToyKit.PINK);
            rug(canvas, bottom, 16, 26, ToyKit.SKY);
            for (Lane lane : Lane.values()) {
                Quarter q = Quarter.of(side, lane);
                for (int i = 0; i < WALLS.length; i++) {
                    double[] w = WALLS[i];
                    ToyKit.wall(canvas, q, w[0], w[1], w[2], w[3], w[4], WALL_COLOURS[i]);
                }
                boxPiles(canvas, q);
            }
            blockStack(canvas, top);
            spinningTop(canvas, bottom);
            jellies(canvas, top);
            toyBox(canvas, top);
            fountain(canvas, top);
            cheese(canvas, bottom);
            rabbitEars(canvas, bottom);
            telescope(canvas, bottom);
            crayonBox(canvas, top);
            marbleJar(canvas, bottom);
        }
        ToyKit.scatter(canvas, this);
    }

    /** Where the small toys stand (crayons TOP, marbles BOTTOM): between the corridor's cotton tree and box pile. */
    private static final int SMALL_TOY_AX = 21;
    private static final int SMALL_TOY_AZ = 16;

    /** A camp's round rug in its colour with a white ring, flat in the floor. */
    private void rug(MapCanvas canvas, Quarter q, int cx, int cz, BlockSpec colour) {
        int r = (int) Math.ceil(RUG);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > RUG || !contains(q.x(cx + dx), q.z(cz + dz))) {
                    continue;
                }
                q.set(canvas, cx + dx, G, cz + dz, d > RUG - 2 && d <= RUG - 1 ? ToyKit.WHITE : colour);
            }
        }
        // A dotted white stitch around the rug where it lies on the mat.
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dz = -r - 1; dz <= r + 1; dz++) {
                double d = Math.hypot(dx, dz);
                int x = q.x(cx + dx);
                int z = q.z(cz + dz);
                if (d > RUG && d <= RUG + 1 && contains(x, z) && ToyKit.isMat(canvas.get(x, G, z))
                        && ToyKit.stitch(x, z)) {
                    canvas.set(x, G, z, ToyKit.WHITE);
                }
            }
        }
    }

    /** A footing of {@code sx} by {@code sz} columns, {@code height} high, low corner {@code ax, az}. */
    private static void footing(MapCanvas canvas, Quarter q, int ax, int az, int sx, int sz, int height) {
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                for (int y = G + 1; y <= G + height; y++) {
                    ToyKit.footing(canvas, q, ax + dx, y, az + dz);
                }
            }
        }
    }

    /** A round footing two high: the columns within {@code radius} of the block {@code cx, cz}. */
    private static void roundFooting(MapCanvas canvas, Quarter q, int cx, int cz, double radius) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.hypot(dx, dz) <= radius) {
                    footing(canvas, q, cx + dx, cz + dz, 1, 1, 2);
                }
            }
        }
    }

    /**
     * Piles of toy boxes in the corners between the clearings (paths run between them): one between the corridor and
     * the 곰인형/토끼 clearing, one beyond the 젤리/쥐 clearing. The open chests are models; a little yellow toy block
     * stays a block.
     */
    private static void boxPiles(MapCanvas canvas, Quarter q) {
        footing(canvas, q, PINK_BOX[0], PINK_BOX[1], 4, 3, 2);
        ToyKit.toyBlock(canvas, q, 33, G + 1, 17, 2, ToyKit.YELLOW);
        footing(canvas, q, SKY_BOX[0], SKY_BOX[1], 4, 4, 2);
        footing(canvas, q, YELLOW_BOX[0], YELLOW_BOX[1], 3, 3, 2);
    }

    /** The open toy chests of the piles (low corners in quarter coordinates). */
    private static final int[] PINK_BOX = {28, 16};
    private static final int[] SKY_BOX = {54, 31};
    private static final int[] YELLOW_BOX = {58, 30};

    /** 젤리 무리: three wobbly jelly puddings around the clearing (low corners of their 3 x 3). */
    private static final int[][] JELLIES = {{57, 20}, {55, 27}, {58, 15}};

    private static void jellies(MapCanvas canvas, Quarter q) {
        for (int[] j : JELLIES) {
            roundFooting(canvas, q, j[0] + 1, j[1] + 1, 1);
        }
    }

    /** 태엽 쥐 떼: the cheese wedge (its high end and the wind-up key at {@code CHEESE_AX}), rising towards +ax. */
    private static final int CHEESE_AX = 60;
    private static final int CHEESE_AZ = 22;

    private static void cheese(MapCanvas canvas, Quarter q) {
        footing(canvas, q, CHEESE_AX - 3, CHEESE_AZ - 3, 1, 7, 1);
        footing(canvas, q, CHEESE_AX - 2, CHEESE_AZ - 3, 3, 7, 2);
    }

    /** 곰인형 대장: the teddy's big toy chest (7 x 3) and the two toy blocks stacked beside it (2 x 2). */
    private static final int TEDDY_BOX_AX = 31;
    private static final int TEDDY_BOX_AZ = 34;

    private static void toyBox(MapCanvas canvas, Quarter q) {
        footing(canvas, q, TEDDY_BOX_AX, TEDDY_BOX_AZ, 7, 3, 2);
        footing(canvas, q, TEDDY_BOX_AX + 8, TEDDY_BOX_AZ + 1, 2, 2, 2);
    }

    /** 시계 토끼: the rabbit's ears either side of the watch (the watch hangs high enough to walk under). */
    private static final int RABBIT_AZ = 36;

    private static void rabbitEars(MapCanvas canvas, Quarter q) {
        footing(canvas, q, 29, RABBIT_AZ, 2, 1, 2);
        footing(canvas, q, 38, RABBIT_AZ, 2, 1, 2);
    }

    /**
     * 사탕 샘물: a two-tier candy fountain: a round basin of water with a bevelled white rim, a white bowl on a pink and
     * white candy pillar (water in it too) and a sparkle on top.
     */
    private static void fountain(MapCanvas canvas, Quarter q) {
        int cx = 16;
        int cz = 35;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double d = Math.hypot(dx, dz);
                if (d == 0) {
                    continue;
                }
                if (d <= 2.5) {
                    q.set(canvas, cx + dx, G + 1, cz + dz, ToyKit.WATER);
                } else if (d <= 3.3) {
                    q.place(canvas, cx + dx, G + 1, cz + dz, ToyKit.bevel(ToyKit.WHITE_STAIRS, dx, dz, false));
                }
                // The bowl stands high enough over the basin to wade in under it.
                if (d <= 1.5) {
                    q.set(canvas, cx + dx, G + 4, cz + dz, ToyKit.WHITE);
                    q.set(canvas, cx + dx, G + 5, cz + dz, ToyKit.WATER);
                } else if (d <= 2.3) {
                    q.place(canvas, cx + dx, G + 4, cz + dz, ToyKit.bevel(ToyKit.WHITE_STAIRS, dx, dz, true));
                    q.place(canvas, cx + dx, G + 5, cz + dz, ToyKit.WHITE_SLAB);
                }
            }
        }
        BlockSpec[] pillar = {ToyKit.PINK, ToyKit.WHITE, ToyKit.PINK, ToyKit.WHITE, ToyKit.PINK, ToyKit.WHITE};
        for (int i = 0; i < pillar.length; i++) {
            q.set(canvas, cx, G + 1 + i, cz, pillar[i]);
        }
        q.place(canvas, cx, G + 7, cz, ToyKit.STICK);
    }

    /**
     * 망원경 탑: a small white lookout (a light blue base band and windows) with a railed wooden balcony on top, a yellow
     * telescope on a tripod pointing out over the clearing and a red pennant at the back.
     */
    private static void telescope(MapCanvas canvas, Quarter q) {
        int cx = 16;
        int cz = 35;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 2.3) {
                    continue;
                }
                if (d <= 1.5) {
                    for (int y = G + 1; y <= G + 6; y++) {
                        boolean window = (dx == 0) != (dz == 0) && (y == G + 4 || y == G + 5);
                        q.set(canvas, cx + dx, y, cz + dz, y == G + 1 || window ? ToyKit.SKY : ToyKit.WHITE);
                    }
                }
                q.place(canvas, cx + dx, G + 7, cz + dz, ToyKit.WOOD_SLAB_TOP);
                if (d > 1.5) {
                    q.set(canvas, cx + dx, G + 8, cz + dz, ToyKit.FENCE);
                }
            }
        }
        // The telescope on its tripod: the eyepiece at the back, the lens out over the clearing (towards -az).
        q.set(canvas, cx, G + 8, cz, ToyKit.FENCE);
        q.place(canvas, cx, G + 9, cz + 1, ToyKit.stairs(ToyKit.YELLOW_STAIRS, BlockFace.NORTH, false));
        for (int z = cz - 2; z <= cz; z++) {
            q.set(canvas, cx, G + 9, z, ToyKit.YELLOW);
        }
        q.set(canvas, cx, G + 9, cz - 3, ToyKit.SKY);
        // A red pennant on a pole at the back of the balcony, flying outwards.
        for (int y = G + 9; y <= G + 10; y++) {
            q.set(canvas, cx + 1, y, cz + 2, ToyKit.FENCE);
        }
        q.place(canvas, cx + 2, G + 10, cz + 2, BlockSpec.of(Material.RED_WALL_BANNER).facing(BlockFace.EAST));
    }

    /** The box of crayons (TOP): its box on a 3 x 2 footing. */
    private static void crayonBox(MapCanvas canvas, Quarter q) {
        footing(canvas, q, SMALL_TOY_AX - 1, SMALL_TOY_AZ - 1, 3, 2, 2);
    }

    /** The jar of marbles (BOTTOM): a round footing under the jar. */
    private static void marbleJar(MapCanvas canvas, Quarter q) {
        roundFooting(canvas, q, SMALL_TOY_AX, SMALL_TOY_AZ, 2);
    }

    /** Where a corner toy stands (quarter coordinates): in the corner pocket beyond a lane's bend, on its diagonal. */
    private static final int CORNER_AX = ToyLayout.HALF_X - ToyLayout.CORNER
            + (int) Math.round((ToyLayout.CORNER - 5.0) / Math.sqrt(2));
    private static final int CORNER_AZ = ToyLayout.HALF_Z - ToyLayout.CORNER
            + (int) Math.round((ToyLayout.CORNER - 5.0) / Math.sqrt(2));

    /** The stack of alphabet blocks (TOP): footings under its two big blocks, corner to corner on the diagonal. */
    private static void blockStack(MapCanvas canvas, Quarter q) {
        footing(canvas, q, CORNER_AX - 4, CORNER_AZ, 4, 4, 2);
        footing(canvas, q, CORNER_AX, CORNER_AZ - 4, 4, 4, 2);
    }

    /**
     * A spinning top on its tip is a model ({@link #props}): an invisible footing (radius 1.6, two blocks high) keeps
     * players out of its middle.
     */
    private static void spinningTop(MapCanvas canvas, Quarter q) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.hypot(dx, dz) <= 1.6) {
                    ToyKit.footing(canvas, q, CORNER_AX + dx, G + 1, CORNER_AZ + dz);
                    ToyKit.footing(canvas, q, CORNER_AX + dx, G + 2, CORNER_AZ + dz);
                }
            }
        }
    }

    /** True in a quarter that is a mirror image of the models' own frame (they can only be turned, not mirrored). */
    private static boolean reflected(Quarter q) {
        return q.sx() * q.sz() < 0;
    }

    /**
     * The jungle's model props ({@code models/props.py}): in every quarter the cotton candy tree and the piles' open toy
     * chests; TOP: the jelly puddings, the teddy's toy chest (its stacked blocks towards +ax: the mirrored model in a
     * mirrored quarter), the box of crayons and the stack of alphabet blocks (turned a quarter in a mirrored quarter, so
     * its blocks still stand on their footings); BOTTOM: the cheese with the mice's wind-up key spinning in it (rising
     * towards +ax, its front turned round in a mirrored quarter), the rabbit's ears with the pocket watch (its hands tick
     * round), the jar of marbles and the big spinning top. Fronts face the clearings ({@code -az}).
     */
    static void props(List<LaneSites.ModelProp> out) {
        for (Side side : Side.values()) {
            for (Lane lane : Lane.values()) {
                Quarter q = Quarter.of(side, lane);
                float front = q.yaw(0, -1);
                for (int[] t : TREES) {
                    out.add(q.prop("cotton_tree", t[0] + 0.5, G + 1, t[1] + 0.5, front, PropMotion.STILL));
                }
                out.add(q.prop("toy_box_pink", PINK_BOX[0] + 2, G + 1, PINK_BOX[1] + 1.5, front, PropMotion.STILL));
                out.add(q.prop("toy_box_sky", SKY_BOX[0] + 2, G + 1, SKY_BOX[1] + 2, front, PropMotion.STILL));
                out.add(q.prop("toy_box_yellow", YELLOW_BOX[0] + 1.5, G + 1, YELLOW_BOX[1] + 1.5, front,
                        PropMotion.STILL));
            }
            Quarter top = Quarter.of(side, Lane.TOP);
            float topFront = top.yaw(0, -1);
            for (int[] j : JELLIES) {
                out.add(top.prop("jelly", j[0] + 1.5, G + 1, j[1] + 1.5, topFront, PropMotion.STILL));
            }
            out.add(top.prop(reflected(top) ? "toy_box_mirror" : "toy_box", TEDDY_BOX_AX + 3.5, G + 1,
                    TEDDY_BOX_AZ + 1.5, topFront, PropMotion.STILL));
            out.add(top.prop("crayon_box", SMALL_TOY_AX + 0.5, G + 1, SMALL_TOY_AZ, topFront, PropMotion.STILL));
            out.add(top.prop("block_stack", CORNER_AX, G + 1, CORNER_AZ, reflected(top) ? top.yaw(1, 0) : topFront,
                    PropMotion.STILL));

            Quarter bottom = Quarter.of(side, Lane.BOTTOM);
            float bottomFront = bottom.yaw(0, -1);
            float cheeseFront = reflected(bottom) ? bottom.yaw(0, 1) : bottomFront;
            out.add(bottom.prop("cheese", CHEESE_AX + 0.5, G + 1, CHEESE_AZ + 0.5, cheeseFront, PropMotion.STILL));
            out.add(bottom.prop("cheese_key", CHEESE_AX + 0.5, G + 1, CHEESE_AZ + 0.5, cheeseFront, PropMotion.SPIN));
            out.add(bottom.prop("rabbit_watch", 34.5, G + 1, RABBIT_AZ + 0.5, bottomFront, PropMotion.STILL));
            out.add(bottom.prop("rabbit_watch_hands", 34.5, G + 1, RABBIT_AZ + 0.5, bottomFront, PropMotion.KEY));
            out.add(bottom.prop("marble_jar", SMALL_TOY_AX + 0.5, G + 1, SMALL_TOY_AZ + 0.5, bottomFront,
                    PropMotion.STILL));
            out.add(bottom.prop("spinning_top", CORNER_AX + 0.5, G + 1, CORNER_AZ + 0.5, bottom.yaw(-1, -1),
                    PropMotion.SPIN));
        }
    }
}
