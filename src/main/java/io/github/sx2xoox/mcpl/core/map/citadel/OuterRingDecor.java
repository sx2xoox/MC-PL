package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * Everything that makes the outer ring feel lived-in and gives cover: the roads running on beyond the gates up into
 * the hills, the wrecked trebuchets that broke the wall, woods of spruce, oak and birch with a few dead trees,
 * boulders, bushes, fallen masonry along both feet of the wall, abandoned camps, milestones at the road ends and grass
 * and flowers everywhere.
 *
 * <p>Inside the arena border (r 105) decorations stay low (one block, or a step up to two) so their tops never become
 * unreachable ledges.
 */
final class OuterRingDecor {

    private static final int GROUND = OuterRingPlan.GROUND;
    private static final int R = CitadelLayout.BUILD_RADIUS;

    private static final Material[] FLOWERS = {Material.DANDELION, Material.POPPY, Material.CORNFLOWER,
            Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.ALLIUM, Material.LILY_OF_THE_VALLEY};

    private final OuterRingPlan plan;
    private final OuterRingTerrain terrain;
    private final OuterRingKit kit;
    private final List<int[]> trees = new ArrayList<>();

    OuterRingDecor(OuterRingPlan plan, OuterRingTerrain terrain, OuterRingKit kit) {
        this.plan = plan;
        this.terrain = terrain;
        this.kit = kit;
    }

    void build() {
        roads();
        siegeEngines();
        camps();
        woods();
        boulders();
        fallenMasonry();
        rubbleDetails();
        bushes();
        plants();
    }

    // ------------------------------------------------------------------ roads

    /** The roads continue beyond the gates as worn tracks fading out on the hillside, each ending at a milestone. */
    private void roads() {
        for (OuterRingPlan.Gate gate : plan.gates) {
            for (int x = -R; x <= R; x++) {
                for (int z = -R; z <= R; z++) {
                    double u = OuterRingPlan.u(x, z, gate.angle());
                    if (u < OuterRingPlan.GATE_BODY_OUT || u > 120 || !kit.canWrite(x, z)) {
                        continue;
                    }
                    double wobble = Math.sin(u / 4.0 + gate.angle()) * 0.8;
                    double across = Math.abs(OuterRingPlan.v(x, z, gate.angle()) - wobble);
                    double fade = 1 - OuterRingKit.smooth((u - 110) / 8);
                    if (across >= 2.5 || kit.hash(x, z, 201) > fade + 0.1
                            || terrain.kind(x, z) != OuterRingTerrain.Kind.GROUND) {
                        continue;
                    }
                    int h = terrain.height(x, z);
                    kit.set(x, h, z, kit.pick(OuterRingKit.ROAD, x, h, z));
                }
            }
            int[] stone = CitadelLayout.polar(116, gate.angle() + Math.toDegrees(3.5 / 116));
            milestone(stone[0], stone[1]);
        }
    }

    private void milestone(int x, int z) {
        if (!kit.canWrite(x, z) || terrain.kind(x, z) != OuterRingTerrain.Kind.GROUND) {
            return;
        }
        int y = terrain.height(x, z) + 1;
        kit.set(x, y, z, Material.MOSSY_STONE_BRICKS);
        kit.set(x, y + 1, z, Material.CHISELED_STONE_BRICKS);
        kit.set(x, y + 2, z, Blocks.lantern(false, false));
    }

    // ------------------------------------------------------------------ camps

    /** The besiegers' abandoned camps on the hills: a campfire, log benches, barrels and hay. */
    private void camps() {
        camp(41, 112);
        camp(141, 112);
        camp(320, 112);
    }

    // ------------------------------------------------------------------ siege engines

    /**
     * The wrecks of the trebuchets that broke the wall, on the hillside in front of the three big breaches: a log
     * frame with two uprights and an axle, the snapped throwing arm lying towards the wall, the counterweight box and a
     * few stone shots.
     */
    private void siegeEngines() {
        for (OuterRingPlan.Breach breach : plan.breaches) {
            if (breach.low()) {
                continue;
            }
            double side = kit.hash((int) breach.angle(), 0, 291) < 0.5 ? -1 : 1;
            trebuchet(breach.angle(), 114, side * (breach.halfWidth() + 3));
        }
    }

    private void trebuchet(double angle, double u0, double v0) {
        int[] c = local(angle, u0, v0);
        if (!kit.canWrite(c[0], c[1]) || terrain.kind(c[0], c[1]) != OuterRingTerrain.Kind.GROUND) {
            return;
        }
        int y = terrain.height(c[0], c[1]) + 1;
        double a = Math.toRadians(angle);
        Axis along = Math.abs(Math.cos(a)) >= Math.abs(Math.sin(a)) ? Axis.X : Axis.Z;
        Axis across = along == Axis.X ? Axis.Z : Axis.X;
        BlockSpec beamAlong = Blocks.log(Material.DARK_OAK_LOG, along);
        BlockSpec beamAcross = Blocks.log(Material.DARK_OAK_LOG, across);
        BlockSpec post = Blocks.log(Material.DARK_OAK_LOG, Axis.Y);
        // The frame: two beams along the throw, joined by cross beams at both ends.
        for (double u = -2.5; u <= 2.5; u += 0.5) {
            for (int s = -1; s <= 1; s += 2) {
                placeLocal(angle, u0 + u, v0 + s * 1.5, y, beamAlong);
            }
        }
        for (double v = -1.5; v <= 1.5; v += 0.5) {
            placeLocal(angle, u0 - 2.5, v0 + v, y, beamAcross);
            placeLocal(angle, u0 + 2.5, v0 + v, y, beamAcross);
        }
        // Uprights and the axle.
        for (int s = -1; s <= 1; s += 2) {
            for (int dy = 1; dy <= 4; dy++) {
                placeLocal(angle, u0, v0 + s * 1.5, y + dy, post);
            }
        }
        for (double v = -1.5; v <= 1.5; v += 0.5) {
            placeLocal(angle, u0, v0 + v, y + 5, beamAcross);
        }
        // The snapped arm: one half still hangs from the axle, the other lies in the grass towards the wall.
        for (double t = 0; t <= 1; t += 0.1) {
            placeLocal(angle, u0 - 0.5 - t * 3, v0, y + 4 - (int) Math.round(t * 3), beamAlong);
        }
        for (double u = 0; u <= 4; u += 0.5) {
            placeLocal(angle, u0 - 6 - u, v0 + 1 + u * 0.3, y, beamAlong);
        }
        // The counterweight box, fallen off its pivot.
        for (double u = 1; u <= 2.5; u += 0.5) {
            for (double v = -1; v <= 1; v += 0.5) {
                placeLocal(angle, u0 + u + 1.5, v0 + v, y, BlockSpec.of(Material.SPRUCE_PLANKS));
                placeLocal(angle, u0 + u + 1.5, v0 + v, y + 1, kit.hash((int) u, (int) v, 293) < 0.5
                        ? BlockSpec.of(Material.COBBLESTONE) : BlockSpec.of(Material.SPRUCE_PLANKS));
            }
        }
        // Stone shots that were never thrown.
        for (int i = 0; i < 3; i++) {
            int[] shot = local(angle, u0 - 1 + i, v0 - 3.5 - i % 2);
            if (kit.canWrite(shot[0], shot[1]) && terrain.kind(shot[0], shot[1]) == OuterRingTerrain.Kind.GROUND) {
                kit.setIfEmpty(shot[0], terrain.height(shot[0], shot[1]) + 1, shot[1],
                        BlockSpec.of(i == 1 ? Material.SMOOTH_STONE : Material.STONE));
            }
        }
        for (int du = -8; du <= 5; du += 4) {
            for (int dv = -4; dv <= 4; dv += 4) {
                int[] p = local(angle, u0 + du, v0 + dv);
                trees.add(p);
            }
        }
    }

    /** A column at local coordinates around a radial line. */
    private static int[] local(double angle, double u, double v) {
        double a = Math.toRadians(angle);
        return new int[] {(int) Math.round(Math.cos(a) * u - Math.sin(a) * v),
                (int) Math.round(Math.sin(a) * u + Math.cos(a) * v)};
    }

    /** Places a block at local coordinates and height {@code y} (never below the ground there), if the spot is free. */
    private void placeLocal(double angle, double u, double v, int y, BlockSpec block) {
        int[] p = local(angle, u, v);
        if (!kit.canWrite(p[0], p[1]) || terrain.kind(p[0], p[1]) == OuterRingTerrain.Kind.STRUCTURE
                || terrain.kind(p[0], p[1]) == OuterRingTerrain.Kind.RIM) {
            return;
        }
        int ground = terrain.height(p[0], p[1]);
        kit.setIfEmpty(p[0], Math.max(y, ground + 1), p[1], block);
    }

    private void camp(double angle, double radius) {
        int[] c = CitadelLayout.polar(radius, angle);
        if (!flat(c[0], c[1], 3)) {
            return;
        }
        int y = terrain.height(c[0], c[1]) + 1;
        kit.set(c[0], y, c[1], Blocks.campfire(kit.hash(c[0], c[1], 211) < 0.5));
        kit.set(c[0] + 2, y, c[1], Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Z));
        kit.set(c[0] + 2, y, c[1] + 1, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Z));
        kit.set(c[0] - 1, y, c[1] - 2, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
        kit.set(c[0], y, c[1] - 2, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
        kit.set(c[0] - 2, y, c[1] + 2, Material.BARREL);
        kit.set(c[0] - 2, y, c[1] + 1, Material.HAY_BLOCK);
        kit.set(c[0] - 3, y, c[1] + 2, Material.HAY_BLOCK);
    }

    /** True if the ground around a column is level and free (plain ground, no paths). */
    private boolean flat(int x, int z, int radius) {
        int h = terrain.height(x, z);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!kit.canWrite(x + dx, z + dz) || terrain.kind(x + dx, z + dz) != OuterRingTerrain.Kind.GROUND
                        || Math.abs(terrain.height(x + dx, z + dz) - h) > 1
                        || !kit.isEmpty(x + dx, terrain.height(x + dx, z + dz) + 1, z + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ trees

    /** Woods on the hills: mostly spruce, oak and birch in the lower slopes, a few dead trees. */
    private void woods() {
        int attempts = 0;
        while (trees.size() < 70 && attempts++ < 4000) {
            double angle = kit.random.nextDouble() * 360;
            double radius = 107 + kit.random.nextDouble() * 12.5;
            int[] p = CitadelLayout.polar(radius, angle);
            int x = p[0];
            int z = p[1];
            if (!treeSpot(x, z)) {
                continue;
            }
            // Clustered woods: denser where the noise says so.
            if (kit.fbm(x / 18.0, z / 18.0, 221) < 0.42 && kit.random.nextDouble() < 0.75) {
                continue;
            }
            trees.add(new int[] {x, z});
            int y = terrain.height(x, z) + 1;
            double kind = kit.random.nextDouble();
            if (kind < 0.5) {
                spruce(x, y, z);
            } else if (kind < 0.75) {
                oak(x, y, z);
            } else if (kind < 0.9) {
                birch(x, y, z);
            } else {
                deadTree(x, y, z);
            }
        }
    }

    private boolean treeSpot(int x, int z) {
        if (!kit.canWrite(x, z) || terrain.kind(x, z) != OuterRingTerrain.Kind.GROUND || plan.onPath(x, z, 5)) {
            return false;
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                OuterRingTerrain.Kind k = terrain.kind(x + dx, z + dz);
                if (k == OuterRingTerrain.Kind.STRUCTURE || k == OuterRingTerrain.Kind.RUBBLE) {
                    return false;
                }
            }
        }
        for (int[] t : trees) {
            int dx = t[0] - x;
            int dz = t[1] - z;
            if (dx * dx + dz * dz < 25) {
                return false;
            }
        }
        return true;
    }

    private void spruce(int x, int y, int z) {
        int height = 7 + kit.random.nextInt(4);
        BlockSpec log = Blocks.log(Material.SPRUCE_LOG, Axis.Y);
        BlockSpec leaves = Blocks.leaves(Material.SPRUCE_LEAVES);
        for (int i = 0; i < height; i++) {
            kit.set(x, y + i, z, log);
        }
        kit.setIfEmpty(x, y + height, z, leaves);
        kit.setIfEmpty(x, y + height + 1, z, leaves);
        int[] radii = {1, 1, 2, 1, 2, 3, 2, 3};
        for (int i = 0; i < radii.length && height - 1 - i >= 2; i++) {
            leafDisk(x, y + height - 1 - i, z, radii[i], leaves);
        }
    }

    private void oak(int x, int y, int z) {
        int height = 4 + kit.random.nextInt(3);
        BlockSpec log = Blocks.log(Material.OAK_LOG, Axis.Y);
        BlockSpec leaves = Blocks.leaves(Material.OAK_LEAVES);
        for (int i = 0; i < height; i++) {
            kit.set(x, y + i, z, log);
        }
        int top = y + height;
        for (int dy = -2; dy <= 1; dy++) {
            double radius = dy == 1 ? 1.2 : dy == -2 ? 2.2 : 2.7;
            leafDisk(x, top + dy, z, radius, leaves);
        }
        kit.setIfEmpty(x, top + 2, z, leaves);
    }

    private void birch(int x, int y, int z) {
        int height = 5 + kit.random.nextInt(3);
        BlockSpec log = Blocks.log(Material.BIRCH_LOG, Axis.Y);
        BlockSpec leaves = Blocks.leaves(Material.BIRCH_LEAVES);
        for (int i = 0; i < height; i++) {
            kit.set(x, y + i, z, log);
        }
        int top = y + height;
        for (int dy = -3; dy <= 0; dy++) {
            leafDisk(x, top + dy, z, dy == 0 ? 1 : 1.8, leaves);
        }
        kit.setIfEmpty(x, top + 1, z, leaves);
    }

    private void deadTree(int x, int y, int z) {
        int height = 4 + kit.random.nextInt(3);
        Material wood = kit.random.nextBoolean() ? Material.SPRUCE_LOG : Material.STRIPPED_OAK_LOG;
        for (int i = 0; i < height; i++) {
            kit.set(x, y + i, z, Blocks.log(wood, Axis.Y));
        }
        BlockFace face = OuterRingKit.cardinals()[kit.random.nextInt(4)];
        Axis axis = face.getModX() != 0 ? Axis.X : Axis.Z;
        int branchY = y + height - 2;
        for (int i = 1; i <= 2; i++) {
            kit.setIfEmpty(x + face.getModX() * i, branchY + (i == 2 ? 1 : 0), z + face.getModZ() * i,
                    Blocks.log(wood, axis));
        }
        BlockFace other = face.getOppositeFace();
        kit.setIfEmpty(x + other.getModX(), branchY - 1, z + other.getModZ(), Blocks.log(wood, axis));
    }

    /** A disk of leaves with ragged corners, only where nothing stands yet. */
    private void leafDisk(int cx, int y, int cz, double radius, BlockSpec leaves) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius + 0.3 || d > radius - 0.4 && kit.hash(cx + dx, y, cz + dz, 231) < 0.45) {
                    continue;
                }
                kit.setIfEmpty(cx + dx, y, cz + dz, leaves);
            }
        }
    }

    // ------------------------------------------------------------------ rocks

    /** Mossy boulders scattered over the hills (cover for anyone fighting on the slopes). */
    private void boulders() {
        int placed = 0;
        int attempts = 0;
        while (placed < 38 && attempts++ < 3000) {
            double angle = kit.random.nextDouble() * 360;
            double radius = 107 + kit.random.nextDouble() * 12;
            int[] p = CitadelLayout.polar(radius, angle);
            int x = p[0];
            int z = p[1];
            if (!kit.canWrite(x, z) || terrain.kind(x, z) != OuterRingTerrain.Kind.GROUND || plan.onPath(x, z, 3)
                    || nearTree(x, z, 3)) {
                continue;
            }
            double rx = 1.2 + kit.random.nextDouble() * 1.4;
            double rz = 1.2 + kit.random.nextDouble() * 1.4;
            double ry = 1 + kit.random.nextDouble() * 1.2;
            boulder(x, terrain.height(x, z), z, rx, ry, rz);
            placed++;
        }
    }

    private void boulder(int cx, int cy, int cz, double rx, double ry, double rz) {
        int ix = (int) Math.ceil(rx);
        int iy = (int) Math.ceil(ry);
        int iz = (int) Math.ceil(rz);
        for (int dx = -ix; dx <= ix; dx++) {
            for (int dz = -iz; dz <= iz; dz++) {
                for (int dy = -1; dy <= iy; dy++) {
                    double n = sq(dx / (rx + 0.5)) + sq(dy / (ry + 0.5)) + sq(dz / (rz + 0.5));
                    if (n > 1) {
                        continue;
                    }
                    int x = cx + dx;
                    int z = cz + dz;
                    int y = cy + dy;
                    boolean top = sq(dx / (rx + 0.5)) + sq((dy + 1) / (ry + 0.5)) + sq(dz / (rz + 0.5)) > 1;
                    BlockSpec block = top && kit.hash(x, y, z, 241) < 0.5 ? BlockSpec.of(Material.MOSSY_COBBLESTONE)
                            : kit.pick(OuterRingKit.ROCK, x, y, z);
                    if (y > terrain.height(x, z)) {
                        kit.setIfEmpty(x, y, z, block);
                    }
                }
            }
        }
    }

    private boolean nearTree(int x, int z, int distance) {
        for (int[] t : trees) {
            if (Math.abs(t[0] - x) <= distance && Math.abs(t[1] - z) <= distance) {
                return true;
            }
        }
        return false;
    }

    private static double sq(double v) {
        return v * v;
    }

    // ------------------------------------------------------------------ masonry and bushes

    /**
     * Fallen masonry along both feet of the wall: loose blocks, slabs and stairs, now and then a chunk of two blocks
     * with a step, so they give low cover without leaving ledges no one can reach.
     */
    private void fallenMasonry() {
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                if (!kit.canWrite(x, z) || terrain.kind(x, z) != OuterRingTerrain.Kind.GROUND
                        || terrain.height(x, z) != GROUND || plan.inPassage(x, z) || plan.flightTop(x, z) >= 0
                        || plan.inPostern(x, z) || nearDoor(x, z)) {
                    continue;
                }
                double r = CitadelLayout.radius(x, z);
                boolean inside = r < OuterRingPlan.WALL_IN && r > OuterRingPlan.WALL_IN - 1;
                boolean outside = r >= OuterRingPlan.WALL_OUT && r < OuterRingPlan.WALL_OUT + 4;
                if (!inside && !outside) {
                    continue;
                }
                if (CitadelLayout.nearestRoadDistance(x, z) <= CitadelLayout.ROAD_HALF_WIDTH + 1) {
                    continue;
                }
                double chance = kit.hash(x, z, 251);
                double cluster = kit.noise(CitadelLayout.angle(x, z) * 1.3, r / 3.0, 252);
                if (chance > (inside ? 0.18 : 0.3) * cluster) {
                    continue;
                }
                double pick = kit.hash(x, z, 253);
                BlockSpec block = pick < 0.45 ? kit.pick(OuterRingKit.RUBBLE, x, GROUND + 1, z)
                        : pick < 0.75 ? Blocks.slab(kit.hash(x, z, 254) < 0.5 ? Material.COBBLESTONE_SLAB
                        : Material.STONE_BRICK_SLAB)
                        : Blocks.stairs(Material.STONE_BRICK_STAIRS, OuterRingKit.cardinals()[(int) (kit.hash(x, z,
                        255) * 4)]);
                kit.setIfEmpty(x, GROUND + 1, z, block);
            }
        }
    }

    /** Moss on the breaches' rubble and a few fallen beams from the wall-walk's hoardings. */
    private void rubbleDetails() {
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                if (!kit.canWrite(x, z) || terrain.kind(x, z) != OuterRingTerrain.Kind.RUBBLE) {
                    continue;
                }
                int h = terrain.height(x, z);
                BlockSpec top = kit.canvas.get(x, h, z);
                if (top == null || top.slab() != null || !kit.isEmpty(x, h + 1, z)) {
                    continue;
                }
                double moss = kit.noise(x / 3.0, z / 3.0, 281);
                if (moss > 0.55 && kit.hash(x, z, 282) < 0.6) {
                    kit.set(x, h, z, Material.MOSSY_COBBLESTONE);
                    if (kit.hash(x, z, 283) < 0.35) {
                        kit.set(x, h + 1, z, Material.MOSS_CARPET);
                    }
                }
            }
        }
        for (OuterRingPlan.Breach breach : plan.breaches) {
            for (int i = 0; i < 3; i++) {
                double u = 97 + kit.random.nextDouble() * 9;
                double v = (kit.random.nextDouble() - 0.5) * breach.halfWidth() * 1.6;
                double a = Math.toRadians(breach.angle());
                int x = (int) Math.round(Math.cos(a) * u - Math.sin(a) * v);
                int z = (int) Math.round(Math.sin(a) * u + Math.cos(a) * v);
                beam(x, z, kit.random.nextBoolean() ? Axis.X : Axis.Z);
            }
        }
    }

    /** A dark oak beam three blocks long lying on level rubble. */
    private void beam(int x, int z, Axis axis) {
        int dx = axis == Axis.X ? 1 : 0;
        int dz = axis == Axis.Z ? 1 : 0;
        int h = terrain.height(x, z);
        for (int i = -1; i <= 1; i++) {
            int bx = x + dx * i;
            int bz = z + dz * i;
            if (!kit.canWrite(bx, bz) || terrain.height(bx, bz) != h || !kit.isEmpty(bx, h + 1, bz)
                    || terrain.kind(bx, bz) == OuterRingTerrain.Kind.STRUCTURE) {
                return;
            }
        }
        for (int i = -1; i <= 1; i++) {
            kit.set(x + dx * i, h + 1, z + dz * i, Blocks.log(Material.DARK_OAK_LOG, axis));
        }
    }

    /** True next to a tower door or postern (keep the way in free). */
    private boolean nearDoor(int x, int z) {
        for (OuterRingPlan.Tower tower : plan.towers) {
            if (Math.abs(OuterRingPlan.v(x, z, tower.angle())) < 3 && Math.hypot(x - tower.cx(), z - tower.cz())
                    < OuterRingPlan.Tower.SIZE + 7) {
                return true;
            }
        }
        for (OuterRingPlan.Postern postern : plan.posterns) {
            if (OuterRingPlan.u(x, z, postern.angle()) > 0 && Math.abs(OuterRingPlan.v(x, z, postern.angle())) < 3) {
                return true;
            }
        }
        for (OuterRingPlan.Gate gate : plan.gates) {
            for (int side = -1; side <= 1; side += 2) {
                int[] c = gate.flank(side);
                if (Math.hypot(x - c[0], z - c[1]) < OuterRingPlan.GATE_FLANK_SIZE + 3) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Low bushes on the hills and against the wall's outer foot. */
    private void bushes() {
        int placed = 0;
        int attempts = 0;
        while (placed < 70 && attempts++ < 3000) {
            double angle = kit.random.nextDouble() * 360;
            double radius = 103.5 + kit.random.nextDouble() * 16;
            int[] p = CitadelLayout.polar(radius, angle);
            int x = p[0];
            int z = p[1];
            if (!kit.canWrite(x, z) || terrain.kind(x, z) != OuterRingTerrain.Kind.GROUND || plan.onPath(x, z, 2)
                    || nearDoor(x, z)) {
                continue;
            }
            Material leaves = kit.random.nextDouble() < 0.5 ? Material.AZALEA_LEAVES
                    : kit.random.nextBoolean() ? Material.OAK_LEAVES : Material.FLOWERING_AZALEA_LEAVES;
            int size = 1 + kit.random.nextInt(3);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > size || kit.hash(x + dx, z + dz, 261) < 0.3) {
                        continue;
                    }
                    int nx = x + dx;
                    int nz = z + dz;
                    if (!kit.canWrite(nx, nz) || terrain.kind(nx, nz) != OuterRingTerrain.Kind.GROUND) {
                        continue;
                    }
                    kit.setIfEmpty(nx, terrain.height(nx, nz) + 1, nz, Blocks.leaves(leaves));
                }
            }
            placed++;
        }
    }

    // ------------------------------------------------------------------ plants

    /** Grass, ferns and flowers on every free grass block. */
    private void plants() {
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                if (!kit.canWrite(x, z)) {
                    continue;
                }
                OuterRingTerrain.Kind k = terrain.kind(x, z);
                if (k != OuterRingTerrain.Kind.GROUND && k != OuterRingTerrain.Kind.CRAG) {
                    continue;
                }
                int h = terrain.height(x, z);
                BlockSpec top = kit.canvas.get(x, h, z);
                if (top == null || top.material() != Material.GRASS_BLOCK || !kit.isEmpty(x, h + 1, z)
                        || !kit.isEmpty(x, h + 2, z)) {
                    continue;
                }
                double r = CitadelLayout.radius(x, z);
                double meadow = kit.noise(x / 9.0, z / 9.0, 271);
                double pick = kit.hash(x, z, 272);
                if (pick < 0.22) {
                    kit.set(x, h + 1, z, Material.SHORT_GRASS);
                } else if (pick < 0.27) {
                    kit.set(x, h + 1, z, Material.FERN);
                } else if (pick < 0.27 + 0.08 * meadow) {
                    Material flower = FLOWERS[(int) (kit.noise(x / 5.0, z / 5.0, 273) * FLOWERS.length * 0.999)];
                    kit.set(x, h + 1, z, flower);
                } else if (pick < 0.37 && r > OuterRingPlan.WALL_OUT + 2) {
                    Blocks.tallPlant(kit.canvas, x, h + 1, z, kit.hash(x, z, 274) < 0.6 ? Material.TALL_GRASS
                            : Material.LARGE_FERN);
                }
            }
        }
    }
}
