package io.github.sx2xoox.speedrunbot.brain;

import io.github.sx2xoox.speedrunbot.core.Cell;
import io.github.sx2xoox.speedrunbot.core.PortalPlan;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a {@link PortalPlan} next to a known lava source with one bucket: pick a site in solid rock, carve the
 * room, park the water in the station hole, then for each frame cell: mine the pocket, fetch lava, pour it in, grab
 * the parked water, pour it in front of the pocket (lava source -> obsidian), pick it up and park it again. Then mine
 * the interior, light it and walk in.
 */
public final class PortalBuilder {
    private enum State { NONE, RECOVER, CARVE, STATION, CAST, INTERIOR, LIGHT, ENTER }

    private final RunBrain b;
    private State state = State.NONE;
    private PortalPlan plan;
    private BlockPos lava;
    private boolean waterPlaced;
    private int cellIndex;
    private int sub;
    private int subTicks;
    private int clickChoice;
    private int siteAttempts;
    private int obsidian;
    private long castTick;

    public PortalBuilder(RunBrain b) {
        this.b = b;
    }

    public boolean active() {
        return state != State.NONE;
    }

    /** The frame is finished and only the lighter is missing. */
    public boolean waitingForLighter() {
        return state == State.LIGHT && !b.inv().lighter();
    }

    public boolean waterPlaced() {
        return waterPlaced;
    }

    public int obsidian() {
        return obsidian;
    }

    private static BlockPos bp(Cell c) {
        return new BlockPos(c.x, c.y, c.z);
    }

    private static Cell cell(BlockPos p) {
        return new Cell(p.getX(), p.getY(), p.getZ());
    }

    private BlockState mem(Cell c) {
        return b.mem.get(c.x, c.y, c.z);
    }

    /** Air or flowing lava above a source: the bucket's ray (sources only) reaches its top. */
    static boolean openAbove(BlockState up) {
        return up != null && (up.isAir() || (Blocks2.isLava(up) && !up.getFluidState().isStill()));
    }

    /** Nearest known lava source the bot could scoop (top face open). */
    public BlockPos chooseLava() {
        BlockPos f = b.feet();
        return b.mem.nearest(WorldMemory.Kind.LAVA_SOURCE, f, 160, l -> {
            if (b.badTargets().contains(l)) {
                return false;
            }
            BlockState up = b.mem.get(BlockPos.fromLong(l).up());
            return openAbove(up);
        });
    }

    // ================================================================== site

    private PortalPlan findSite(BlockPos lavaPos) {
        double maxLava = b.p.get("portal.maxLavaDist");
        Cell[] dirs = {new Cell(1, 0, 0), new Cell(-1, 0, 0), new Cell(0, 0, 1), new Cell(0, 0, -1)};
        PortalPlan best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos f = b.feet();
        int r = (int) Math.ceil(maxLava) + 2;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    Cell origin = new Cell(lavaPos.getX() + dx, lavaPos.getY() + dy, lavaPos.getZ() + dz);
                    for (Cell n : dirs) {
                        for (int sgn = -1; sgn <= 1; sgn += 2) {
                            Cell u = new Cell(-n.z * sgn, 0, n.x * sgn);
                            PortalPlan pl = new PortalPlan(origin, u, n);
                            Cell stand = pl.at(2, 1, 2);
                            double dl = Math.sqrt(sq(stand.x - lavaPos.getX()) + sq(stand.y - lavaPos.getY()) + sq(stand.z - lavaPos.getZ()));
                            if (dl > maxLava) {
                                continue;
                            }
                            double sc = score(pl);
                            if (Double.isInfinite(sc)) {
                                continue;
                            }
                            sc += dl * 2 + Math.sqrt(f.getSquaredDistance(stand.x, stand.y, stand.z, true)) * 0.3
                                    + Math.abs(origin.y - lavaPos.getY()) * 6; // the room floor at the lava's level: fetching is a short walk
                            if (sc < bestScore) {
                                bestScore = sc;
                                best = pl;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private double score(PortalPlan pl) {
        double unknown = 0;
        for (Cell c : pl.solidBefore()) {
            BlockState s = mem(c);
            if (s == null) {
                unknown++;
                continue;
            }
            if (Blocks2.pass(s) != Blocks2.Pass.SOLID || Blocks2.falls(s)) {
                return Double.POSITIVE_INFINITY;
            }
            if (lavaNear(c)) {
                return Double.POSITIVE_INFINITY;
            }
        }
        List<Cell> room = pl.room();
        for (Cell c : room) {
            BlockState s = mem(c);
            if (s != null && (Blocks2.isLava(s) || Blocks2.isWater(s) || Blocks2.pass(s) == Blocks2.Pass.FORBID)) {
                return Double.POSITIVE_INFINITY;
            }
            if (lavaNear(c)) {
                return Double.POSITIVE_INFINITY;
            }
            BlockState up = mem(c.add(0, 1, 0));
            if (up != null && Blocks2.falls(up) && !room.contains(c.add(0, 1, 0))) {
                return Double.POSITIVE_INFINITY;
            }
        }
        List<Cell> floors = new ArrayList<>();
        for (int a = 0; a <= 3; a++) {
            floors.add(pl.at(a, 0, 1));
            floors.add(pl.at(a, 0, 2));
        }
        floors.add(pl.waterStation().add(0, -1, 0));
        for (Cell c : floors) {
            BlockState s = mem(c);
            if (s == null) {
                unknown += 0.5;
            } else if (!Blocks2.standable(s) || Blocks2.isLava(s)) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return unknown;
    }

    private boolean lavaNear(Cell c) {
        for (Direction d : Direction.values()) {
            BlockState s = b.mem.get(c.x + d.getOffsetX(), c.y + d.getOffsetY(), c.z + d.getOffsetZ());
            if (s != null && Blocks2.isLava(s)) {
                return true;
            }
        }
        return false;
    }

    private static double sq(double v) {
        return v * v;
    }

    private Cell recoverFrom;
    private String lastFailure = "";

    private int softFailures;

    /**
     * A step of a site that already holds obsidian failed in a way that may pass (a path not found this time, a block
     * that would not break): retry from the current state a few times before the cast obsidian is thrown away.
     */
    private boolean softFail(String why) {
        if (obsidian > 0 && ++softFailures <= 8) {
            b.trace("portal step failed (" + why + "), keeping the frame (" + obsidian + "/10): retry " + softFailures);
            b.mover.reset();
            b.mover.forgetAvoid();
            badStands.clear();
            subTicks = Math.min(subTicks, 300);
            return true;
        }
        return false;
    }

    private void restartSite(String why) {
        if ((why.equals("unreachable") || why.equals("mine_failed") || why.equals("stand_unreachable")) && softFail(why)) {
            return;
        }
        b.trace("portal site failed: " + why);
        softFailures = 0;
        obsidian = 0; // the next site starts empty (a stale count let the next site's failures pass as soft)
        noFaceTicks = 0;
        lastFailure = why;
        if (lava != null) {
            b.badTargets().add(lava.asLong());
        }
        if (++siteAttempts > 6) {
            b.fail("portal_" + why);
            return;
        }
        // the water may be parked in this site's station (or poured in front of a pocket): take it along
        recoverFrom = null;
        if (plan != null && !b.inv().has(Items.WATER_BUCKET) && waterPlaced) {
            for (Cell c : new Cell[]{plan.waterStation(), cellIndex < plan.frame().size() ? plan.front(plan.frame().get(cellIndex)) : null}) {
                if (c != null && isWaterSource(c)) {
                    recoverFrom = c;
                }
            }
        }
        state = recoverFrom != null ? State.RECOVER : State.NONE;
        badStands.clear();
        plan = null;
        sub = 0;
        subTicks = 0;
    }

    private void recover() {
        if (b.inv().has(Items.WATER_BUCKET) || subTicks > 400 || !isWaterSource(recoverFrom)) {
            if (!b.inv().has(Items.WATER_BUCKET)) {
                b.trace("water could not be recovered from " + recoverFrom);
            }
            waterPlaced = false;
            state = State.NONE;
            return;
        }
        if (b.fillBucket(bp(recoverFrom), Items.WATER_BUCKET) == Actions.Status.FAILED) {
            subTicks = 999;
        }
    }

    // ================================================================== tick

    /** Scoopable lava sources known within r of p. */
    private int sourcesNear(BlockPos p, double r) {
        int n = 0;
        it.unimi.dsi.fastutil.longs.LongIterator it = b.mem.of(WorldMemory.Kind.LAVA_SOURCE).iterator();
        while (it.hasNext()) {
            long l = it.nextLong();
            if (b.badTargets().contains(l)) {
                continue;
            }
            BlockPos q = BlockPos.fromLong(l);
            if (q.getSquaredDistance(p) <= r * r) {
                BlockState up = b.mem.get(q.up());
                if (openAbove(up)) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * The lava to build next to: ten frame cells need ten scoops, so a pool with enough known sources around it,
     * nearest first. A small pool is accepted only after looking for a bigger one for a while.
     */
    private BlockPos chooseRichLava() {
        BlockPos f = b.feet();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int need = poolScanned ? Math.min(8, b.p.getInt("portal.minSources")) : b.p.getInt("portal.minSources");
        boolean settle = ++lavaLookTicks > b.p.getInt("portal.poolSearchTicks");
        it.unimi.dsi.fastutil.longs.LongIterator it = b.mem.of(WorldMemory.Kind.LAVA_SOURCE).iterator();
        int checked = 0;
        while (it.hasNext() && checked < 600) {
            long l = it.nextLong();
            if (b.badTargets().contains(l)) {
                continue;
            }
            BlockPos q = BlockPos.fromLong(l);
            double d = Math.sqrt(q.getSquaredDistance(f));
            if (d > 160) {
                continue;
            }
            BlockState up = b.mem.get(q.up());
            if (!openAbove(up)) {
                continue;
            }
            checked++;
            int n = sourcesNear(q, 8);
            if (n < need && !settle) {
                continue;
            }
            double sc = d - Math.min(n, 30) * 1.5;
            if (sc < bestScore) {
                bestScore = sc;
                best = q;
            }
        }
        return best;
    }

    private int lavaLookTicks;
    /** Looked around at the pool in view (more of its sources come into view than from the tunnel mouth). */
    private boolean poolScanned;
    private int poolScanTicks;

    public void tick() {
        subTicks++;
        if (state == State.NONE) {
            lava = chooseRichLava();
            if (lava == null) {
                BlockPos near = chooseLava();
                if (!poolScanned && near != null && near.getSquaredDistance(b.feet()) < 7 * 7) {
                    // a pool in view but few of its sources known: look over it before walking off for a bigger one
                    // (standing still next to it waiting cost 45 s in many runs)
                    b.act = "scan pool";
                    poolScanTicks++;
                    float yaw = b.yawTo(near) + (poolScanTicks % 48 - 24) * 5f;
                    b.c.lookAngles(yaw, poolScanTicks % 24 < 12 ? 40f : 15f);
                    if (poolScanTicks > 60) {
                        poolScanned = true;
                        b.trace("pool scanned: " + sourcesNear(near, 8) + " sources known near " + near);
                    }
                    return;
                }
                b.exploreForLava();
                return;
            }
            plan = findSite(lava);
            if (plan == null) {
                b.trace("no portal site near lava " + lava.toString());
                b.badTargets().add(lava.asLong());
                return;
            }
            b.split("portal_site");
            b.trace("portal site origin=" + plan.origin() + " u=" + plan.u() + " n=" + plan.n() + " lava=" + lava.toString());
            state = State.CARVE;
            sub = 0;
            subTicks = 0;
            return;
        }
        switch (state) {
            case RECOVER:
                recover();
                break;
            case CARVE:
                carve();
                break;
            case STATION:
                station();
                break;
            case CAST:
                cast();
                break;
            case INTERIOR:
                interior();
                break;
            case LIGHT:
                light();
                break;
            case ENTER:
                enter();
                break;
            default:
                break;
        }
    }

    private boolean mineCell(Cell c, double reach) {
        BlockPos p = bp(c);
        if (!b.a.solid(p) && b.mem.get(p) != null) {
            return true;
        }
        BlockPos f = b.feet();
        Navigator.Goal g = Navigator.reach(p, reach);
        if (!g.done(f.getX(), f.getY(), f.getZ()) || !b.s.onGround()) {
            if (b.mover.go(g) == Mover.Status.FAILED) {
                restartSite("unreachable");
            }
            return false;
        }
        Actions.Status st = b.a.mine(p, false);
        if (st == Actions.Status.FAILED) {
            restartSite("mine_failed");
        }
        return false;
    }

    private void carve() {
        if (subTicks > 2400) {
            restartSite("carve_timeout");
            return;
        }
        // top cells first in each column, nearest column first
        List<Cell> room = new ArrayList<>(plan.room());
        BlockPos f = b.feet();
        Cell next = null;
        double bestD = Double.MAX_VALUE;
        for (Cell c : room) {
            BlockPos p = bp(c);
            if (!b.a.solid(p) && b.mem.get(p) != null) {
                continue;
            }
            double d = Math.sqrt(f.getSquaredDistance(p)) - c.y * 0.01;
            if (d < bestD) {
                bestD = d;
                next = c;
            }
        }
        if (next == null) {
            state = State.STATION;
            sub = 0;
            subTicks = 0;
            b.split("portal_room");
            return;
        }
        // prefer the top of that column
        Cell top = next;
        while (room.contains(top.add(0, 1, 0)) && (b.a.solid(bp(top.add(0, 1, 0))) || mem(top.add(0, 1, 0)) == null)) {
            top = top.add(0, 1, 0);
        }
        mineCell(top, 4.2);
    }

    private static Vec3d eyeOf(Cell s) {
        return new Vec3d(s.x + 0.5, s.y + 1.62, s.z + 0.5);
    }

    /**
     * Some neighbour of the target (known solid, or unknown = usually rock) shows a face toward it to this eye, in
     * reach, and no known solid block lies on the line between (a stand cell next to the right one may see only a
     * ceiling face hidden behind the room's edge: the top cells' water never got poured).
     */
    private boolean faceSeenFrom(Vec3d eye, Cell target) {
        for (Direction d : Direction.values()) {
            Cell sup = target.add(d.getOffsetX(), d.getOffsetY(), d.getOffsetZ());
            BlockState s = mem(sup);
            if (s != null && (!Blocks2.standable(s) || Blocks2.isLava(s) || Blocks2.isWater(s) || Blocks2.interactive(s))) {
                continue;
            }
            Direction face = d.getOpposite();
            Vec3d pt = new Vec3d(sup.x + 0.5 + face.getOffsetX() * 0.5, sup.y + 0.5 + face.getOffsetY() * 0.5, sup.z + 0.5 + face.getOffsetZ() * 0.5);
            Vec3d toEye = eye.subtract(pt);
            if (toEye.x * face.getOffsetX() + toEye.y * face.getOffsetY() + toEye.z * face.getOffsetZ() <= 0.05 || toEye.length() > 4.4) {
                continue;
            }
            if (clearLine(eye, pt.add(face.getOffsetX() * 0.02, face.getOffsetY() * 0.02, face.getOffsetZ() * 0.02), sup)) {
                return true;
            }
        }
        return false;
    }

    /** No known solid block on the segment (the end block itself excepted). */
    private boolean clearLine(Vec3d from, Vec3d to, Cell end) {
        Vec3d d = to.subtract(from);
        int steps = (int) Math.ceil(d.length() / 0.05);
        double m = 0.08; // a line grazing an edge counts as blocked (the real ray clipped corners the sampling missed)
        for (int i = 1; i < steps; i++) {
            Vec3d q = from.add(d.multiply(i / (double) steps));
            for (int k = 0; k < 8; k++) {
                int x = (int) Math.floor(q.x + ((k & 1) == 0 ? -m : m));
                int y = (int) Math.floor(q.y + ((k & 2) == 0 ? -m : m));
                int z = (int) Math.floor(q.z + ((k & 4) == 0 ? -m : m));
                if (x == end.x && y == end.y && z == end.z) {
                    continue;
                }
                BlockState s = b.mem.get(x, y, z);
                if (s != null && Blocks2.pass(s) == Blocks2.Pass.SOLID) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Where to stand for casting a cell: a stand cell within reach of the pocket, its front and the station. */
    private Cell standFor(Cell c) {
        Cell best = null;
        double bestD = Double.MAX_VALUE;
        Cell front = plan.front(c);
        for (Cell s : plan.standCells()) {
            Vec3d eye = eyeOf(s);
            double d = Math.max(dist(eye, c), Math.max(dist(eye, front), dist(eye, plan.waterStation())));
            if (!faceSeenFrom(eye, c) || !faceSeenFrom(eye, front)) {
                d += 10; // only if no stand cell sees both faces
            }
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    private static double dist(Vec3d e, Cell c) {
        return e.distanceTo(new Vec3d(c.x + 0.5, c.y + 0.5, c.z + 0.5));
    }

    private boolean goStand(Cell stand) {
        return goStand(stand, null);
    }

    /** Goes to the stand cell; a neighbouring stand cell does as well when it sees a face to pour into {@code target}. */
    private boolean goStand(Cell stand, Cell target) {
        BlockPos f = b.feet();
        if (target != null) {
            // a stand cell that sees a face to pour into the target, and where pouring has not failed already
            Cell pick = null;
            for (int pass = 0; pass < 2 && pick == null; pass++) {
                for (Cell o : pass == 0 ? java.util.Collections.singletonList(stand) : plan.standCells()) {
                    if (!blindStands.contains(o) && !badStands.contains(o) && faceSeenFrom(eyeOf(o), target)) {
                        pick = o;
                        break;
                    }
                }
            }
            if (pick == null && !blindStands.isEmpty()) {
                blindStands.clear();
            }
            if (pick != null) {
                stand = pick;
            }
        }
        if (badStands.contains(stand)) {
            // that stand cell failed before: any other stand cell of the plan within reach does as well
            for (Cell o : plan.standCells()) {
                if (!badStands.contains(o)) {
                    stand = o;
                    break;
                }
            }
        }
        if (f.equals(bp(stand)) && (b.s.onGround() || b.s.inWater())) {
            return true;
        }
        for (Cell o : plan.standCells()) {
            if (f.equals(bp(o)) && (b.s.onGround() || b.s.inWater()) && !badStands.contains(o) && f.getSquaredDistance(bp(stand)) <= 4
                    && (target == null || faceSeenFrom(eyeOf(o), target))) {
                return true; // a neighbouring stand cell reaches as well
            }
        }
        if (b.mover.go(Navigator.at(bp(stand))) == Mover.Status.FAILED) {
            badStands.add(stand);
            b.mover.reset();
            if (badStands.size() >= plan.standCells().size()) {
                restartSite("stand_unreachable");
            }
        }
        return false;
    }

    private final java.util.Set<Cell> badStands = new java.util.HashSet<>();
    /** Stand cells from which pouring for the current step failed (the crosshair never got on a face). */
    private final java.util.Set<Cell> blindStands = new java.util.HashSet<>();

    private void station() {
        Cell st = plan.waterStation();
        if (sub == 0) {
            if (!mineCell(st, 4.2)) {
                return;
            }
            sub = 1;
            subTicks = 0;
        }
        if (sub == 1) {
            if (!goStand(plan.standCells().get(0))) {
                return;
            }
            if (isWaterSource(st)) {
                waterPlaced = true;
                state = State.CAST;
                cellIndex = 0;
                sub = 0;
                subTicks = 0;
                return;
            }
            if (b.a.equip(Items.WATER_BUCKET) != Actions.Status.DONE) {
                if (!b.inv().has(Items.WATER_BUCKET)) {
                    restartSite("no_water");
                }
                return;
            }
            pourInto(st);
            if (subTicks > 200) {
                restartSite("station_pour");
            }
        }
    }

    private int noFaceTicks;
    private int cellRetries;
    private int plugTicks;
    private final java.util.Set<Cell> openingsAccepted = new java.util.HashSet<>();
    private int lastSub = -1;
    private int blindCell = -1;
    private int searchTicks;
    private BlockPos fetchSrc;
    private int fetchTicks;

    private boolean isLavaSource(BlockPos p) {
        BlockState s = b.mem.get(p);
        return s != null && Blocks2.isLavaSource(s);
    }

    private boolean isWaterSource(Cell c) {
        BlockState s = mem(c);
        return s != null && Blocks2.isWaterSource(s);
    }

    private boolean isObsidian(Cell c) {
        BlockState s = mem(c);
        return s != null && s.getBlock() == Blocks.OBSIDIAN;
    }

    /**
     * Pours the held bucket into an empty cell by clicking a solid neighbour's face that points into it; tries the
     * candidate faces in turn until the crosshair lands on one.
     */
    private void pourInto(Cell target) {
        List<BlockHitResult> clicks = new ArrayList<>();
        List<Double> clickScore = new ArrayList<>();
        Vec3d eye = b.s.eyePos();
        for (Direction d : Direction.values()) {
            Cell sup = target.add(d.getOffsetX(), d.getOffsetY(), d.getOffsetZ());
            BlockState s = mem(sup);
            // unknown neighbours are usually rock (the crosshair check below confirms the face is really there)
            if (s != null && (!Blocks2.standable(s) || Blocks2.isLava(s) || Blocks2.isWater(s) || Blocks2.interactive(s))) {
                continue;
            }
            Direction face = d.getOpposite();
            Vec3d pt = new Vec3d(sup.x + 0.5 + face.getOffsetX() * 0.5, sup.y + 0.5 + face.getOffsetY() * 0.5, sup.z + 0.5 + face.getOffsetZ() * 0.5);
            Vec3d toEye = eye.subtract(pt);
            if (toEye.x * face.getOffsetX() + toEye.y * face.getOffsetY() + toEye.z * face.getOffsetZ() <= 0.05 || toEye.length() > 4.4) {
                continue;
            }
            // known rock first, then faces seen most head-on
            double score = toEye.normalize().dotProduct(new Vec3d(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ())) + (s == null ? 0 : 1);
            BlockHitResult hr = new BlockHitResult(pt, face, bp(sup), false);
            int at = 0;
            while (at < clicks.size() && clickScore.get(at) >= score) {
                at++;
            }
            clicks.add(at, hr);
            clickScore.add(at, score);
        }
        if (clicks.isEmpty()) {
            noFaceTicks++;
            if (subTicks % 40 == 0) {
                b.trace("no face to pour into " + target);
            }
            return;
        }
        BlockHitResult click = clicks.get((clickChoice / 6) % clicks.size());
        clickChoice++;
        if (clickChoice > clicks.size() * 6 * 3 + 20) {
            // every face tried three times from here: the line of sight is blocked; another stand cell next
            Cell here = cell(b.feet());
            if (plan != null && plan.standCells().contains(here)) {
                blindStands.add(here);
                StringBuilder fs = new StringBuilder();
                for (BlockHitResult hr : clicks) {
                    fs.append(' ').append(hr.getBlockPos().toString()).append('/').append(hr.getSide());
                }
                net.minecraft.util.hit.HitResult h = b.s.crosshair();
                b.trace("no face for " + target + " from " + here + " eye=" + b.s.eyePos() + " faces" + fs + " xhair="
                        + (h instanceof BlockHitResult ? ((BlockHitResult) h).getBlockPos().toString() + "/" + ((BlockHitResult) h).getSide() : h.getType())
                        + ": trying another stand cell");
            }
            clickChoice = 0;
        }
        if (clickChoice % 30 == 29) {
            net.minecraft.util.hit.HitResult h = b.s.crosshair();
            b.trace("pour into " + target + ": " + clicks.size() + " faces, trying " + click.getBlockPos() + "/" + click.getSide() + " xhair="
                    + (h instanceof BlockHitResult ? ((BlockHitResult) h).getBlockPos() + "/" + ((BlockHitResult) h).getSide() : h.getType()));
        }
        if (b.a.useOn(click) == Actions.Status.DONE) {
            b.trace("used " + b.s.inventory().getMainHandStack() + " on " + click.getBlockPos() + "/" + click.getSide() + " for " + target);
            clickChoice = 0;
        }
    }

    /** Some known-solid neighbour of the target shows a face toward the target to the eye of some stand cell. */
    private boolean pourFaceVisible(Cell target) {
        for (Cell st : plan.standCells()) {
            Vec3d eye = new Vec3d(st.x + 0.5, st.y + 1.62, st.z + 0.5);
            for (Direction d : Direction.values()) {
                Cell sup = target.add(d.getOffsetX(), d.getOffsetY(), d.getOffsetZ());
                BlockState s = mem(sup);
                if (s == null || !Blocks2.standable(s) || Blocks2.isLava(s) || Blocks2.isWater(s) || Blocks2.interactive(s)) {
                    continue;
                }
                Direction face = d.getOpposite();
                Vec3d pt = new Vec3d(sup.x + 0.5 + face.getOffsetX() * 0.5, sup.y + 0.5 + face.getOffsetY() * 0.5, sup.z + 0.5 + face.getOffsetZ() * 0.5);
                Vec3d toEye = eye.subtract(pt);
                if (toEye.x * face.getOffsetX() + toEye.y * face.getOffsetY() + toEye.z * face.getOffsetZ() > 0.05 && toEye.length() <= 4.4) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Scoops a fluid source with the held empty bucket (aims at its surface). */
    private void scoop(Cell src) {
        if (b.a.equip(Items.BUCKET) != Actions.Status.DONE) {
            return;
        }
        Vec3d aim = new Vec3d(src.x + 0.5, src.y + 0.75, src.z + 0.5);
        b.c.lookAt(aim);
        if (b.c.aimed(aim, 2.5f)) {
            b.c.use();
        }
    }

    private void cast() {
        List<Cell> frame = plan.frame();
        while (cellIndex < frame.size() && isObsidian(frame.get(cellIndex)) && sub == 0) {
            cellIndex++;
        }
        obsidian = 0;
        for (Cell c : frame) {
            if (isObsidian(c)) {
                obsidian++;
            }
        }
        if (cellIndex >= frame.size()) {
            b.split("obsidian_10");
            state = State.INTERIOR;
            sub = 0;
            subTicks = 0;
            return;
        }
        if (subTicks > 900) {
            if (++cellRetries > 2) {
                restartSite("cast_timeout_" + cellIndex + "_" + sub);
                return;
            }
            b.trace("cast cell " + cellIndex + " stuck in step " + sub + "; retrying the cell");
            subTicks = 0;
            fetchSrc = null;
            b.mover.reset();
            if (sub <= 2) {
                sub = 0;
            }
        }
        Cell c = frame.get(cellIndex);
        Cell front = plan.front(c);
        if (sub != lastSub || cellIndex != blindCell) {
            blindStands.clear();
            blindCell = cellIndex;
        }
        if (sub != lastSub) {
            b.trace("cast " + cellIndex + " step " + lastSub + "->" + sub + " feet=" + b.feet() + " c=" + mem(c) + " front=" + mem(front));
            lastSub = sub;
        }
        boolean bottom = plan.isBottom(c);
        Cell stand = standFor(c);
        switch (sub) {
            case 0: // floor hole for the bottom row
                if (bottom && !mineCell(front, 4.2)) {
                    return;
                }
                sub = 1;
                return;
            case 1: // pocket
                if (!mineCell(c, 4.2)) {
                    return;
                }
                List<Cell> check = new ArrayList<>(plan.mustBeSolid(c));
                boolean supported = false;
                Cell plug = null;
                for (Cell q : plan.frontSupports(c)) {
                    BlockState qs = mem(q);
                    if (qs == null || (Blocks2.pass(qs) == Blocks2.Pass.SOLID && !Blocks2.interactive(qs))) {
                        supported = true;
                    } else if (plug == null && !Blocks2.isLava(qs)) {
                        plug = q;
                    }
                }
                if (!supported && plug != null) {
                    check.add(plug); // no face to pour the water against: make one
                }
                for (Cell n : check) {
                    BlockState s = mem(n);
                    if (s != null && Blocks2.pass(s) != Blocks2.Pass.SOLID && !openingsAccepted.contains(n)) {
                        // An opening around the pocket (cave air, water). A lava source turns to obsidian as soon as
                        // water touches it wherever its flow went, so an opening is only a nuisance: plug it if that
                        // is quick, else go on. Only the face for the water is a must.
                        boolean must = n.equals(plug);
                        boolean give = Blocks2.isLava(s) || ++plugTicks > 120 || b.inv().throwaways() == 0;
                        BlockPos f = b.feet();
                        Navigator.Goal g = Navigator.reach(bp(n), 4.0);
                        if (!give && !g.done(f.getX(), f.getY(), f.getZ())) {
                            give = b.mover.go(g) == Mover.Status.FAILED;
                            if (!give) {
                                return;
                            }
                        }
                        if (!give) {
                            give = b.a.place(bp(n), Actions::isThrowaway) == Actions.Status.FAILED;
                            if (!give) {
                                return;
                            }
                        }
                        if (must) {
                            restartSite("no_water_face_" + cellIndex);
                            return;
                        }
                        b.trace("pocket " + cellIndex + " stays open at " + n + " (" + s + "); casting anyway");
                        openingsAccepted.add(n);
                        plugTicks = 0;
                        return;
                    }
                }
                plugTicks = 0;
                if (!pourFaceVisible(c)) {
                    // a pocket opened into a cave on every side the stand cells see: the lava would have nothing to
                    // be poured against (and would run into the cave); this frame cannot be closed here
                    restartSite("pocket_open_" + cellIndex);
                    return;
                }
                sub = 2;
                return;
            case 2: { // fetch lava
                if (b.inv().has(Items.LAVA_BUCKET)) {
                    fetchSrc = null;
                    sub = 3;
                    return;
                }
                if (fetchSrc == null || !isLavaSource(fetchSrc) || ++fetchTicks > 240) {
                    if (fetchSrc != null) {
                        b.trace("lava source " + fetchSrc + " not scooped; trying another");
                        b.badTargets().add(fetchSrc.asLong());
                    }
                    fetchSrc = chooseLava();
                    fetchTicks = 0;
                }
                BlockPos src = fetchSrc;
                Cell o = plan.origin();
                if (src == null || src.getSquaredDistance(o.x, o.y, o.z, true) > 40 * 40) {
                    // the pool is used up: keep the half-cast frame and look for more lava around it
                    fetchSrc = null;
                    if (obsidian == 0 && src == null) {
                        restartSite("lava_gone");
                        return;
                    }
                    if (searchTicks % 400 == 399) {
                        // sources given up on earlier (a bad angle, a flow in the way) get another chance
                        it.unimi.dsi.fastutil.longs.LongIterator li = b.mem.of(WorldMemory.Kind.LAVA_SOURCE).iterator();
                        while (li.hasNext()) {
                            b.badTargets().remove(li.nextLong());
                        }
                    }
                    if (++searchTicks % 100 == 1) {
                        b.trace("known lava sources: " + b.mem.count(WorldMemory.Kind.LAVA_SOURCE) + ", usable " + sourcesNear(bp(o), 40));
                        b.trace("no lava within reach of the frame; searching (" + obsidian + "/10 cast)");
                    }
                    if (b.feet().getSquaredDistance(o.x, o.y, o.z, true) > 48 * 48) {
                        b.mover.go(Navigator.near(bp(o), 3));
                    } else {
                        b.exploreForLava();
                    }
                    subTicks = Math.min(subTicks, 600); // searching does not count as being stuck
                    return;
                }
                if (b.fillBucket(src, Items.LAVA_BUCKET) == Actions.Status.FAILED) {
                    b.badTargets().add(src.asLong());
                    fetchSrc = null;
                }
                return;
            }
            case 3: // pour lava into the pocket
                if (!goStand(stand, c)) {
                    return;
                }
                if (!b.inv().has(Items.LAVA_BUCKET)) {
                    castTick = b.now();
                    sub = 4;
                    return;
                }
                if (b.a.equip(Items.LAVA_BUCKET) != Actions.Status.DONE) {
                    return;
                }
                pourInto(c);
                return;
            case 4: // grab the parked water
                if (b.inv().has(Items.WATER_BUCKET)) {
                    sub = 5;
                    return;
                }
                scoop(plan.waterStation());
                return;
            case 5: // water in front of the pocket
                if (subTicks % 40 == 0) {
                    b.trace("cast5 c=" + mem(c) + " front=" + mem(front) + " water=" + b.inv().has(Items.WATER_BUCKET) + " held=" + b.s.inventory().getMainHandStack()
                            + " feet=" + b.feet() + " stand=" + stand + " xhair=" + b.s.crosshair().getPos());
                }
                if (isObsidian(c)) {
                    sub = 6;
                    return;
                }
                if (!b.inv().has(Items.WATER_BUCKET)) {
                    // poured: wait for the obsidian to show
                    b.c.lookAt(new Vec3d(c.x + 0.5, c.y + 0.5, c.z + 0.5));
                    return;
                }
                if (!goStand(stand, front)) {
                    return; // the faces to pour against are seen from the stand cell (fetching water moved us)
                }
                if (b.a.equip(Items.WATER_BUCKET) != Actions.Status.DONE) {
                    return;
                }
                pourInto(front);
                return;
            case 6: // pick the water up again
                if (b.inv().has(Items.WATER_BUCKET)) {
                    sub = 7;
                    return;
                }
                if (isWaterSource(front)) {
                    scoop(front);
                } else {
                    // the source is gone (flowed); look around for it
                    b.c.lookAt(new Vec3d(front.x + 0.5, front.y + 0.5, front.z + 0.5));
                    if (subTicks > 600) {
                        restartSite("water_lost");
                    }
                }
                return;
            case 7: // park it in the station
                if (isWaterSource(plan.waterStation()) && !b.inv().has(Items.WATER_BUCKET)) {
                    sub = 8;
                    return;
                }
                if (!goStand(stand, plan.waterStation())) {
                    return;
                }
                if (b.a.equip(Items.WATER_BUCKET) != Actions.Status.DONE) {
                    return;
                }
                pourInto(plan.waterStation());
                return;
            case 8: // refill the floor hole
                if (bottom) {
                    Actions.Status st = b.a.place(bp(front), Actions::isThrowaway);
                    if (st == Actions.Status.RUNNING) {
                        return;
                    }
                }
                b.trace("obsidian " + (cellIndex + 1) + "/10 at " + c);
                cellRetries = 0;
                cellIndex++;
                sub = 0;
                subTicks = 0;
                return;
            default:
                break;
        }
    }

    private void interior() {
        for (int bIdx = 3; bIdx >= 1; bIdx--) {
            for (int a = 1; a <= 2; a++) {
                Cell c = plan.at(a, bIdx);
                if (b.a.solid(bp(c)) || mem(c) == null) {
                    mineCell(c, 4.2);
                    return;
                }
            }
        }
        state = State.LIGHT;
        sub = 0;
        subTicks = 0;
    }

    private void light() {
        BlockState fire = mem(plan.fireCell());
        if (fire != null && fire.getBlock() == Blocks.NETHER_PORTAL) {
            b.split("portal_lit");
            state = State.ENTER;
            subTicks = 0;
            return;
        }
        if (!b.inv().lighter()) {
            subTicks = 0; // the frame waits while the brain gets flint (gravel) and crafts the lighter
            return;
        }
        if (subTicks > 400) {
            restartSite("light_failed");
            return;
        }
        if (!goStand(plan.at(1, 1, 2))) {
            return;
        }
        if (b.a.equip(Items.FLINT_AND_STEEL) != Actions.Status.DONE) {
            return;
        }
        Cell t = plan.lightTarget();
        BlockHitResult click = new BlockHitResult(new Vec3d(t.x + 0.5, t.y + 1.0, t.z + 0.5), Direction.UP, bp(t), false);
        b.a.useOn(click);
    }

    private void enter() {
        Cell e = plan.entryCell();
        if (b.s.inPortal()) {
            b.split("in_portal");
            b.c.lookAngles(b.s.yaw(), 0f);
            return; // stand still until the dimension changes
        }
        if (subTicks > 400) {
            b.fail("enter_failed");
            return;
        }
        Vec3d target = new Vec3d(e.x + 0.5, e.y, e.z + 0.5);
        Vec3d p = b.s.pos();
        float yaw = io.github.sx2xoox.speedrunbot.core.InputLimits.yawTo(target.x - p.x, target.z - p.z);
        b.c.lookAngles(yaw, 10f);
        b.c.move(0.6f, 0f, false, false, false);
    }
}
