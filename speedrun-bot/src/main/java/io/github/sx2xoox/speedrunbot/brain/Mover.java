package io.github.sx2xoox.speedrunbot.brain;

import io.github.sx2xoox.speedrunbot.core.InputLimits;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Goal-directed movement: plans with {@link Navigator} on memory, follows the path with player inputs (walk, sprint,
 * jump, mine what blocks the way, pillar), and re-plans when the path becomes invalid, the plan was partial, or
 * progress stalls.
 */
public final class Mover {
    public enum Status { RUNNING, ARRIVED, FAILED }

    private final BotSenses s;
    private final Controls c;
    private final Actions a;
    private final WorldMemory mem;
    private final LongOpenHashSet avoid = new LongOpenHashSet();
    /** Steps that stalled the bot twice at the same spot: kept out of plans even after {@link #forgetAvoid}. */
    private final LongOpenHashSet stickyAvoid = new LongOpenHashSet();
    private long noPillarUntil;

    /**
     * The watchdog saw the bot stall at the same spot again with the same plan: keep the step it was trying out of the
     * next plans (and pillaring off for a while when that was the step), so the next plan differs.
     */
    public String stalledAgain() {
        Navigator.Step step = path != null && index < path.steps.size() ? path.steps.get(index) : null;
        if (step == null) {
            return "no step";
        }
        stickyAvoid.add(step.to.asLong());
        if (step.move == Navigator.Move.PILLAR || step.move == Navigator.Move.SWIM_UP) {
            noPillarUntil = s.gameTick() + 600;
        }
        return step.toString();
    }

    private Navigator.Goal goal;
    private Navigator.Path path;
    private BlockPos pathStart;
    private int index;
    private int sinceReplan;
    private int stuck;
    private double bestDist;
    private int failures;
    private int pillarPhase;
    private int peekTicks;
    private int pillarIndex = -1;
    private static final boolean DEBUG = Boolean.getBoolean("srb.debugMove");
    private long plannedNodes;
    private int plans;

    public java.util.function.Consumer<String> debug = x -> { };
    private String why = "";

    private StepResult replan(String reason) {
        why = reason;
        return StepResult.REPLAN;
    }

    public Mover(BotSenses s, Controls c, Actions a, WorldMemory mem) {
        this.s = s;
        this.c = c;
        this.a = a;
        this.mem = mem;
    }

    public BlockPos feet() {
        Vec3d p = s.pos();
        return new BlockPos(Math.floor(p.x), Math.floor(p.y + 0.01), Math.floor(p.z));
    }

    public void reset() {
        goal = null;
        path = null;
        failures = 0;
    }

    public int plans() {
        return plans;
    }

    public long plannedNodes() {
        return plannedNodes;
    }

    /** Moves toward the goal; call every tick with the same goal object until it reports ARRIVED/FAILED. */
    public Status go(Navigator.Goal g) {
        BlockPos f = feet();
        if (goal == null || !g.key().equals(goal.key())) {
            goal = g;
            path = null;
            failures = 0;
        }
        if (g.done(f.getX(), f.getY(), f.getZ()) && (s.onGround() || s.inWater())) {
            path = null;
            return Status.ARRIVED;
        }
        sinceReplan++;
        if (path == null || index >= path.steps.size() || (!path.complete && sinceReplan > 60)) {
            if (!plan(f)) {
                return ++failures > 6 ? Status.FAILED : Status.RUNNING;
            }
        }
        StepResult r = follow(f);
        if (r == StepResult.REPLAN) {
            if (failures % 5 == 0 || DEBUG) {
                debug.accept("replan " + why + " at " + f + " step=" + (path != null && index < path.steps.size() ? path.steps.get(index) : null) + " failures=" + failures);
            }
            path = null;
            if (++failures > 25) {
                return Status.FAILED;
            }
        }
        return Status.RUNNING;
    }

    private String lastPlan = "";

    public String lastPlanInfo() {
        return lastPlan;
    }

    private boolean plan(BlockPos f) {
        boolean pillar = Actions.count(s.inventory(), Actions::isThrowaway) > 0 && s.gameTick() >= noPillarUntil;
        avoid.addAll(stickyAvoid);
        Navigator nav = new Navigator(mem, s.inventory(), pillar, true, avoid, 1.3);
        Navigator.Path p = nav.find(f, goal, 12000);
        plans++;
        plannedNodes += p.expanded;
        lastPlan = "plan steps=" + p.steps.size() + " complete=" + p.complete + " expanded=" + p.expanded + " first=" + (p.steps.isEmpty() ? null : p.steps.get(0));
        sinceReplan = 0;
        if (p.steps.isEmpty()) {
            path = null;
            return false;
        }
        path = p;
        pathStart = f;
        index = 0;
        stuck = 0;
        bestDist = Double.MAX_VALUE;
        pillarPhase = 0;
        return true;
    }

    private enum StepResult { OK, REPLAN }

    private StepResult follow(BlockPos f) {
        Navigator.Step step = path.steps.get(index);
        BlockPos t = step.to;
        BlockPos from = index == 0 ? pathStart : path.steps.get(index - 1).to;
        // skip ahead if we are already on a later node
        for (int j = index; j < Math.min(path.steps.size(), index + 4); j++) {
            if (path.steps.get(j).to.equals(f) && (s.onGround() || s.inWater())) {
                index = j + 1;
                stuck = 0;
                bestDist = Double.MAX_VALUE;
                if (index >= path.steps.size()) {
                    return StepResult.OK;
                }
                step = path.steps.get(index);
                t = step.to;
                from = path.steps.get(index - 1).to;
            }
        }
        // still valid?
        for (BlockPos cell : bodyCells(step, from)) {
            BlockState st = mem.get(cell);
            if (st != null && Blocks2.pass(st) == Blocks2.Pass.FORBID) {
                return replan("forbidden_cell");
            }
        }
        if (step.move != Navigator.Move.PILLAR && step.move != Navigator.Move.SWIM_UP) {
            BlockState fl = mem.get(t.down());
            BlockState tb = mem.get(t);
            boolean water = tb != null && Blocks2.isWater(tb);
            if (fl != null && !water && !Blocks2.standable(fl) && !Blocks2.isWater(fl) && Blocks2.pass(fl) != Blocks2.Pass.SOLID) {
                // floor turned out to be missing: a drop. Fine if shallow, else re-plan
                int depth = 0;
                BlockPos q = t.down();
                while (depth < 5) {
                    BlockState qs = mem.get(q);
                    if (qs == null || Blocks2.standable(qs) || Blocks2.isWater(qs)) {
                        break;
                    }
                    if (Blocks2.isLava(qs)) {
                        depth = 99;
                        break;
                    }
                    depth++;
                    q = q.down();
                }
                if (depth > 3) {
                    avoid.add(t.asLong());
                    return replan("drop");
                }
            }
        }
        // look before dropping: the landing (and what is under it) must be seen, it may be lava
        if (step.move == Navigator.Move.DOWN && from.getY() - t.getY() >= 1) {
            BlockState land = mem.get(t.down());
            if (land == null && peekTicks < 12) {
                peekTicks++;
                c.lookAt(new Vec3d(t.getX() + 0.5, t.getY(), t.getZ() + 0.5));
                return StepResult.OK;
            }
            if (land != null && Blocks2.isLava(land)) {
                avoid.add(t.asLong());
                return replan("lava_landing");
            }
        }
        peekTicks = 0;
        // clear the way first
        for (BlockPos cell : bodyCells(step, from)) {
            if (a.solid(cell)) {
                Actions.Status ms = a.mine(cell, false);
                if (ms == Actions.Status.FAILED) {
                    avoid.add(cell.asLong());
                    return replan("mine_failed");
                }
                stuckCheck(t, true);
                return StepResult.OK;
            }
        }
        Vec3d pos = s.pos();
        Vec3d target = new Vec3d(t.getX() + 0.5, t.getY(), t.getZ() + 0.5);
        double hd = Math.sqrt(sq(target.x - pos.x) + sq(target.z - pos.z));
        switch (step.move) {
            case PILLAR: {
                if (pillarIndex != index) {
                    pillarIndex = index; // a new pillar step (the skip-ahead may have moved the index)
                    pillarPhase = 0;
                }
                if (DEBUG && s.gameTick() % 10 == 0) {
                    debug.accept("pillar phase=" + pillarPhase + " pos=" + s.pos() + " ground=" + s.onGround() + " pitch=" + s.pitch() + " from=" + from
                            + " t=" + t + " held=" + s.inventory().getMainHandStack() + " above=" + mem.get(from.up(2)));
                }
                if (a.equip(Actions::isThrowaway) != Actions.Status.DONE) {
                    return StepResult.OK;
                }
                c.lookAngles(s.yaw(), 90f);
                if (hd > 0.25) {
                    walkToward(target, false, 0.5f);
                }
                if (s.onGround() && pillarPhase == 0 && s.pitch() > 80f) {
                    c.jump();
                    pillarPhase = 1;
                } else if (pillarPhase == 1 && pos.y >= from.getY() + 1.0) {
                    BlockPos cell = from;
                    if (a.place(cell, Actions::isThrowaway) == Actions.Status.DONE) {
                        pillarPhase = 2;
                    }
                } else if (pillarPhase == 1 && s.onGround() && pos.y < from.getY() + 0.5) {
                    pillarPhase = 0; // missed: retry
                }
                if (f.equals(t) && s.onGround()) {
                    index++;
                    pillarPhase = 0;
                }
                stuckCheck(t, true);
                return stuckFail() ? replan("stuck") : StepResult.OK;
            }
            case DIG_DOWN: {
                // the cell itself was mined above; wait to fall into it
                walkToward(target, false, 0.3f);
                if (f.equals(t)) {
                    index++;
                }
                stuckCheck(t, false);
                return stuckFail() ? replan("stuck") : StepResult.OK;
            }
            default:
                break;
        }
        boolean straight = step.move == Navigator.Move.WALK || step.move == Navigator.Move.DIAG;
        boolean sprint = straight && runAhead() >= 3 && !s.inWater();
        boolean jump = false;
        // bobbing at the surface the feet are briefly out of the water and off the ground: still "in the water" for
        // jumping (holding jump while pushing against a one-high bank lifts the player out, as in vanilla)
        BlockState under = mem.get(f.down());
        boolean wet = s.inWater() || (!s.onGround() && under != null && Blocks2.isWater(under));
        if (step.move == Navigator.Move.UP && hd < 1.6 && (s.onGround() || wet)) {
            jump = true;
        }
        if (step.move == Navigator.Move.SWIM_UP || (wet && t.getY() >= f.getY())) {
            jump = true;
        } else if (s.inWater() && t.getY() < f.getY()) {
            c.sink();
        }
        if (s.horizontalCollision() && s.onGround() && t.getY() >= f.getY()) {
            jump = true;
        }
        walkToward(target, sprint, 1f);
        if (jump) {
            c.jump();
        }
        if (step.move == Navigator.Move.SWIM_UP && s.inWater() && f.getY() == t.getY() - 1 && s.pos().y > t.getY() - 0.75
                && index + 1 < path.steps.size()) {
            // bobbing at the surface: the feet never reach the air cell above the water; the next step (onto the
            // shore, jumping while swimming) takes us out
            BlockState ts = mem.get(t);
            if (ts == null || !Blocks2.isWater(ts)) {
                index++;
                stuck = 0;
                bestDist = Double.MAX_VALUE;
                return StepResult.OK;
            }
        }
        if (f.equals(t) && (hd < 0.35 || (straight && index + 1 < path.steps.size() && hd < 0.7)) && (s.onGround() || s.inWater())) {
            index++;
            stuck = 0;
            bestDist = Double.MAX_VALUE;
        }
        // deviation: fell somewhere else
        if (!f.equals(t) && !f.equals(from) && s.onGround() && f.getManhattanDistance(t) > 2) {
            return replan("deviation");
        }
        stuckCheck(t, false);
        return stuckFail() ? replan("stuck") : StepResult.OK;
    }

    private int runAhead() {
        int n = 0;
        for (int j = index; j < path.steps.size() && n < 4; j++) {
            Navigator.Move m = path.steps.get(j).move;
            if (m != Navigator.Move.WALK && m != Navigator.Move.DIAG) {
                break;
            }
            if (a.solid(path.steps.get(j).to) || a.solid(path.steps.get(j).to.up())) {
                break;
            }
            n++;
        }
        return n;
    }

    private void walkToward(Vec3d target, boolean sprint, float speed) {
        Vec3d p = s.pos();
        float yaw = InputLimits.yawTo(target.x - p.x, target.z - p.z);
        c.lookAngles(yaw, 12f);
        float diff = Math.abs(InputLimits.wrap(yaw - s.yaw()));
        float fwd = diff < 60 ? speed : 0f;
        c.move(fwd, 0f, false, sprint && diff < 20, false);
    }

    private void stuckCheck(BlockPos t, boolean working) {
        Vec3d p = s.pos();
        double d = Math.sqrt(sq(t.getX() + 0.5 - p.x) + sq(t.getY() - p.y) + sq(t.getZ() + 0.5 - p.z));
        if (working) {
            stuck = Math.max(0, stuck - 1);
            return;
        }
        if (d < bestDist - 0.05) {
            bestDist = d;
            stuck = 0;
        } else {
            stuck++;
        }
    }

    private boolean stuckFail() {
        if (stuck > 50) {
            if (index < path.steps.size()) {
                avoid.add(path.steps.get(index).to.asLong());
            }
            stuck = 0;
            return true;
        }
        return false;
    }

    /** Cells that must be passable for the step, top first (so the bot never digs out its own floor first). */
    public static List<BlockPos> bodyCells(Navigator.Step step, BlockPos from) {
        List<BlockPos> out = new ArrayList<>(4);
        BlockPos t = step.to;
        switch (step.move) {
            case WALK:
            case DIAG:
                out.add(t.up());
                out.add(t);
                break;
            case UP:
                out.add(from.up(2));
                out.add(t.up());
                out.add(t);
                break;
            case DOWN: {
                BlockPos col = new BlockPos(t.getX(), from.getY(), t.getZ());
                out.add(col.up());
                out.add(col);
                for (int y = from.getY() - 1; y >= t.getY(); y--) {
                    out.add(new BlockPos(t.getX(), y, t.getZ()));
                }
                break;
            }
            case DIG_DOWN:
                out.add(t);
                break;
            case PILLAR:
                out.add(from.up(2));
                break;
            default:
                break;
        }
        return out;
    }

    private static double sq(double v) {
        return v * v;
    }

    private int climbPhase;
    private BlockPos climbFrom;

    /**
     * Straight up to height y0 the way players get out of a hole: mine what is above the head, jump, place a block
     * under the feet at the top of the jump, repeat. ARRIVED at y0, FAILED without blocks or when lava/water is above.
     */
    public Status climb(int y0) {
        BlockPos f = feet();
        if (f.getY() >= y0 && s.onGround() && canStepOut(f)) {
            climbPhase = 0;
            return Status.ARRIVED;
        }
        if (f.getY() > y0 + 12) {
            return Status.FAILED; // a tall wall; let the caller walk
        }
        BlockPos head = f.up(2);
        BlockState hs = mem.get(head);
        if (hs != null && (Blocks2.isLava(hs) || Blocks2.isWater(hs) || Blocks2.pass(hs) == Blocks2.Pass.FORBID)) {
            return Status.FAILED;
        }
        if (s.onGround() && (hs == null || Blocks2.pass(hs) == Blocks2.Pass.SOLID)) {
            if (a.lavaNextTo(head)) {
                return Status.FAILED;
            }
            Actions.Status st = a.mine(head, false);
            return st == Actions.Status.FAILED ? Status.FAILED : Status.RUNNING;
        }
        if (Actions.count(s.inventory(), Actions::isThrowaway) == 0) {
            return Status.FAILED;
        }
        if (a.equip(Actions::isThrowaway) != Actions.Status.DONE) {
            return Status.RUNNING;
        }
        c.lookAngles(s.yaw(), 90f);
        Vec3d p = s.pos();
        double cx = f.getX() + 0.5 - p.x, cz = f.getZ() + 0.5 - p.z;
        if (s.onGround()) {
            if (Math.abs(cx) > 0.25 || Math.abs(cz) > 0.25) {
                // centre on the cell first (sneak-walk) so the block goes under the feet
                float yaw = InputLimits.yawTo(cx, cz);
                c.lookAngles(yaw, 60f);
                c.move(0.3f, 0f, false, false, true);
                return Status.RUNNING;
            }
            if (s.pitch() > 80f) {
                c.jump();
                climbFrom = f;
                climbPhase = 1;
            }
            return Status.RUNNING;
        }
        if (climbPhase == 1 && climbFrom != null && p.y >= climbFrom.getY() + 1.0) {
            if (a.place(climbFrom, Actions::isThrowaway) == Actions.Status.DONE) {
                climbPhase = 0;
            }
        }
        return Status.RUNNING;
    }

    /** Some side cell is open at feet and head height (out of the climbing shaft). */
    private boolean canStepOut(BlockPos f) {
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockState a1 = mem.get(f.getX() + d[0], f.getY(), f.getZ() + d[1]);
            BlockState a2 = mem.get(f.getX() + d[0], f.getY() + 1, f.getZ() + d[1]);
            if (a1 != null && a2 != null && Blocks2.pass(a1) == Blocks2.Pass.PASS && Blocks2.pass(a2) == Blocks2.Pass.PASS) {
                return true;
            }
        }
        return false;
    }

    /** Known lava touching the feet or head cell (sides, below, above). */
    public boolean lavaNear(BlockPos f) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockState st = mem.get(f.getX() + dx, f.getY() + dy, f.getZ() + dz);
                    if (st != null && Blocks2.isLava(st)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void forgetAvoid() {
        avoid.clear();
    }
}
