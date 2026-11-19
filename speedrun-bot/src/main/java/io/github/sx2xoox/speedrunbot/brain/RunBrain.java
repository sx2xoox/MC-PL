package io.github.sx2xoox.speedrunbot.brain;

import io.github.sx2xoox.speedrunbot.core.Igt;
import io.github.sx2xoox.speedrunbot.core.InputLimits;
import io.github.sx2xoox.speedrunbot.core.Params;
import io.github.sx2xoox.speedrunbot.core.PerceptionRules;
import io.github.sx2xoox.speedrunbot.core.RouteSelector;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Recipe;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.tag.ItemTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The run: an ordered list of sub-goals re-evaluated every time one finishes (wood -> wooden tools -> cobble ->
 * stone pickaxe -> iron -> flint -> smelt -> bucket + lighter -> water -> lava -> portal -> enter). Each sub-goal is
 * a small state machine that acts only through {@link Controls} and knows the world only through {@link WorldMemory}.
 * Numbers that steer it come from {@link Params} (tuned by tools/tune.py).
 */
public final class RunBrain {
    public enum Goal { SCAN, WOOD, WOOD_TOOLS, COBBLE, STONE_PICK, IRON, FLINT, SMELT, IRON_TOOLS, WATER, LAVA, PORTAL, DONE }

    final BotSenses s;
    final Controls c;
    final WorldMemory mem = new WorldMemory();
    final Perception perception;
    final Actions a;
    final Mover mover;
    final Crafter crafter;
    final Params p;
    final Inv inv;
    final Igt igt;
    final PortalBuilder portal;
    private final List<String> trace = new ArrayList<>();

    Goal goal = Goal.SCAN;
    private int goalTicks;
    private String failure;
    private boolean finished;
    private RouteSelector.Route route = RouteSelector.Route.DIG_CAST;
    private String routeNote = "";
    private BlockPos spawn;
    private BlockPos tablePos;
    private BlockPos furnacePos;
    private long smeltReady = -1;
    private int furnaceTries;
    private boolean furnaceClose;
    private int smeltLoaded;
    private Direction digDir = Direction.NORTH;
    private int tunnelLen;
    private int gravelBreaks;
    private int deaths;
    private final LongOpenHashSet badTargets = new LongOpenHashSet();
    private BlockPos target;
    private int sub;
    private int subTicks;
    private int attackCooldown;
    private Vec3d exploreTarget;
    private int lastLogs;
    private float lastHealth = 20f;

    public RunBrain(BotSenses s, BotInputs in, Params p, PerceptionRules rules, int raysPerTick) {
        this.s = s;
        this.c = new Controls(in, s);
        this.p = p;
        this.perception = new Perception(s, mem, rules, raysPerTick);
        this.a = new Actions(s, c, mem);
        this.mover = new Mover(s, c, a, mem);
        this.crafter = new Crafter(s, c);
        this.inv = new Inv(s.inventory());
        this.igt = new Igt(s.gameTick());
        this.portal = new PortalBuilder(this);
        this.spawn = mover.feet();
        this.rng = new java.util.Random(spawn.asLong());
        a.debug = this::trace;
        crafter.debug = this::trace;
        mover.debug = this::trace;
        trace("start at " + spawn.toString() + " fair=" + rules.fair());
    }

    // ================================================================== bookkeeping

    void trace(String msg) {
        String line = Igt.format(igt.elapsedTicks(s.gameTick())) + " [" + goal + "] " + msg;
        trace.add(line);
        if (trace.size() > 4000) {
            trace.remove(0);
        }
    }

    public List<String> traceLines() {
        return trace;
    }

    public Igt igt() {
        return igt;
    }

    public boolean finished() {
        return finished;
    }

    public String failure() {
        return failure;
    }

    public RouteSelector.Route route() {
        return route;
    }

    public String routeNote() {
        return routeNote;
    }

    public WorldMemory memory() {
        return mem;
    }

    public Goal goal() {
        return goal;
    }

    public long now() {
        return s.gameTick();
    }

    void split(String name) {
        if (igt.split(name, s.gameTick())) {
            trace("split " + name + " " + Igt.format(igt.splitTicks(name)) + " inv=" + inv.summary());
        }
    }

    void dbg(String msg) {
        if (subTicks % 40 == 1) {
            trace(msg);
        }
    }

    void fail(String why) {
        if (!finished) {
            failure = why;
            finished = true;
            trace("FAIL " + why);
        }
    }

    // ================================================================== tick

    public void tick() {
        if (finished) {
            return;
        }
        if (!s.alive()) {
            fail("death");
            return;
        }
        if (s.dimension().equals("the_nether")) {
            split("nether");
            finished = true;
            trace("in the nether");
            return;
        }
        perception.tick();
        goalTicks++;
        float hp = s.health();
        if (hp < lastHealth - 0.01f) {
            Vec3d pos = s.pos();
            trace(String.format("hurt %.1f -> %.1f at %.1f,%.1f,%.1f lava=%s water=%s fire=%s", lastHealth, hp, pos.x, pos.y, pos.z,
                    s.inLava(), s.inWater(), s.onFire()));
        }
        lastHealth = hp;
        if (goalTicks % 200 == 0) {
            Vec3d pos = s.pos();
            trace(String.format("status pos=%.1f,%.1f,%.1f hp=%.0f food=%d target=%s plans=%d inv=%s", pos.x, pos.y, pos.z,
                    s.health(), s.food(), target, mover.plans(), inv.summary()));
        }
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        if (!defend()) {
            Goal next = choose();
            if (next != goal) {
                trace("goal " + goal + " -> " + next + " after " + goalTicks + "t inv=" + inv.summary());
                goal = next;
                goalTicks = 0;
                sub = 0;
                subTicks = 0;
                target = null;
                placeTarget = null;
                mover.reset();
                crafter.reset();
            }
            run();
        }
        c.flush();
    }

    /** Swings at hostile mobs in melee range (the player sees them). Returns true when it used this tick. */
    private boolean defend() {
        List<HostileEntity> near = perception.visible(HostileEntity.class, 3.2, null);
        if (near.isEmpty() || s.screenOpen()) {
            return false;
        }
        HostileEntity e = near.get(0);
        c.lookAt(e.getPos().add(0, e.getHeight() * 0.6, 0));
        if (attackCooldown == 0 && c.aimed(e.getPos().add(0, e.getHeight() * 0.6, 0), 15f)) {
            c.attackEntity();
            attackCooldown = 12;
        }
        return true;
    }

    // ================================================================== goal selection

    /** Iron the bot owns, counting ore that is in the furnace. */
    private int ironTotal() {
        return inv.ironEquivalent() + smelter.loaded();
    }

    private int ironNeeded() {
        return p.getInt("iron.needed");
    }

    private Goal choose() {
        if (bench.busy() || smelter.screenBusy() || a.equipBusy() || (s.screenOpen() && goalTicks < 400)) {
            return goal; // a screen is in use (crafting, furnace): finish first
        }
        if (goal == Goal.SCAN && goalTicks < p.getInt("surface.scanTicks")) {
            return Goal.SCAN;
        }
        if (goal == Goal.SCAN) {
            chooseRoute();
        }
        if (portal.active()) {
            return Goal.PORTAL;
        }
        if (!inv.pickaxe()) {
            int planksNeeded = 9 + 4 + 3; // table + sticks + pickaxe, a second table, smelting fuel
            if (inv.logs() * 4 + inv.planks() < Math.max(planksNeeded, p.getInt("wood.logs") * 4) && goalTicks < 99999) {
                if (goal != Goal.WOOD_TOOLS) {
                    return Goal.WOOD;
                }
            }
            return Goal.WOOD_TOOLS;
        }
        if (!inv.stonePick()) {
            return inv.cobble() >= 3 * p.getInt("stone.picks") || (inv.cobble() >= 3 && goal == Goal.COBBLE && goalTicks > 400)
                    ? Goal.STONE_PICK : Goal.COBBLE;
        }
        boolean needIron = ironTotal() < ironNeeded();
        if (needIron) {
            return Goal.IRON;
        }
        if (inv.buckets() == 0) {
            if (inv.count(Items.IRON_INGOT) < 3) {
                return Goal.SMELT;
            }
            return Goal.IRON_TOOLS;
        }
        if (!inv.lighter()) {
            if (inv.count(Items.IRON_INGOT) < 1) {
                return Goal.SMELT;
            }
            if (!inv.has(Items.FLINT) && smelter.smelting()) {
                return Goal.SMELT; // the bucket is made; flint is looked for next to the furnace meanwhile
            }
            if (!inv.has(Items.FLINT)) {
                return Goal.FLINT;
            }
            return Goal.IRON_TOOLS;
        }
        if (!inv.has(Items.WATER_BUCKET) && !portal.waterPlaced()) {
            return Goal.WATER;
        }
        if (portal.chooseLava() == null) {
            return Goal.LAVA;
        }
        return Goal.PORTAL;
    }

    private void chooseRoute() {
        RouteSelector.Observation o = new RouteSelector.Observation();
        BlockPos f = mover.feet();
        BlockPos lava = mem.nearest(WorldMemory.Kind.LAVA_SOURCE, f, 200, l -> BlockPos.unpackLongY(l) >= 50);
        BlockPos water = mem.nearest(WorldMemory.Kind.WATER_SOURCE, f, 200, null);
        BlockPos iron = mem.nearest(WorldMemory.Kind.IRON_ORE, f, 200, null);
        if (lava != null) {
            o.surfaceLava(Math.sqrt(lava.getSquaredDistance(f)));
            BlockPos w2 = mem.nearest(WorldMemory.Kind.WATER_SOURCE, lava, 200, null);
            if (w2 != null) {
                o.lavaToWater(Math.sqrt(w2.getSquaredDistance(lava)));
            }
        }
        if (water != null) {
            o.water(Math.sqrt(water.getSquaredDistance(f)));
        }
        if (iron != null) {
            o.iron(mem.count(WorldMemory.Kind.IRON_ORE), Math.sqrt(iron.getSquaredDistance(f)));
        }
        RouteSelector.Choice ch = new RouteSelector(p).choose(o);
        route = ch.route;
        routeNote = new RouteSelector(p).rank(o).toString();
        trace("route " + routeNote + " lava=" + o.surfaceLavaDist + " water=" + o.waterDist + " iron=" + o.ironKnown);
    }

    // ================================================================== goals

    private void run() {
        subTicks++;
        if (bench.busy()) {
            bench.run(null); // a crafting session in progress owns the hands and the screen
            return;
        }
        if (smelter.screenBusy()) {
            smelter.tick(); // so does the furnace screen
            return;
        }
        switch (goal) {
            case SCAN:
                scan();
                break;
            case WOOD:
                wood();
                break;
            case WOOD_TOOLS:
                woodTools();
                break;
            case COBBLE:
                cobble(3 * p.getInt("stone.picks"));
                break;
            case STONE_PICK:
                // a spare pickaxe now saves a table later (one wears out after 131 blocks)
                craftAtTable(st -> st == Items.STONE_PICKAXE, inv.cobble() >= 6 ? p.getInt("stone.picks") : 1, "stone_pickaxe");
                break;
            case IRON:
                iron();
                break;
            case FLINT:
                flint();
                break;
            case SMELT:
                smelt();
                break;
            case IRON_TOOLS:
                if (inv.buckets() == 0) {
                    craftAtTable(st -> st == Items.BUCKET, 1, "bucket");
                } else {
                    craftAtTable(st -> st == Items.FLINT_AND_STEEL, 1, "flint_and_steel");
                }
                break;
            case WATER:
                water();
                break;
            case LAVA:
                exploreUnderground("lava");
                break;
            case PORTAL:
                portal.tick();
                break;
            default:
                break;
        }
    }

    private void scan() {
        // look around: one turn level, one up, one down
        float pitch = goalTicks < 10 ? 0f : goalTicks < 20 ? -25f : goalTicks < 30 ? 30f : 0f;
        c.lookAngles(s.yaw() + 40f, pitch);
    }

    // ------------------------------------------------------------------ wood

    private void wood() {
        int logs = inv.logs();
        if (logs > lastLogs) {
            split("first_log");
        }
        lastLogs = logs;
        BlockPos f = mover.feet();
        if (target == null || !isLog(target) || badTargets.contains(target.asLong())) {
            target = mem.nearest(WorldMemory.Kind.LOG, f, 96, l -> !badTargets.contains(l) && BlockPos.unpackLongY(l) <= f.getY() + 6);
            sub = 0;
            subTicks = 0;
        }
        if (target == null) {
            explore();
            return;
        }
        exploreTarget = null;
        if (subTicks > 500) {
            badTargets.add(target.asLong());
            target = null;
            return;
        }
        Navigator.Goal g = Navigator.reach(target, 4.2);
        if (!g.done(f.getX(), f.getY(), f.getZ()) || !s.onGround()) {
            if (mover.go(g) == Mover.Status.FAILED) {
                badTargets.add(target.asLong());
                target = null;
            }
            return;
        }
        Actions.Status st = a.mine(target, true);
        if (st == Actions.Status.FAILED) {
            badTargets.add(target.asLong());
            target = null;
        } else if (st == Actions.Status.DONE) {
            target = null; // next log; drops are collected by walking under the tree
        }
    }

    private boolean isLog(BlockPos p) {
        BlockState st = mem.get(p);
        return st != null && WorldMemory.classify(st) == WorldMemory.Kind.LOG;
    }

    /** Walk somewhere new, looking around, to discover trees / water / lava. */
    private void explore() {
        BlockPos f = mover.feet();
        if (exploreTarget == null || subTicks > 300 || exploreTarget.squaredDistanceTo(Vec3d.ofCenter(f)) < 9) {
            float yaw = s.yaw() + (rng.nextFloat() * 120 - 60);
            double[] d = PerceptionRules.direction(yaw, 0);
            exploreTarget = new Vec3d(f.getX() + d[0] * 24, f.getY(), f.getZ() + d[2] * 24);
            subTicks = 0;
            mover.reset();
        }
        BlockPos t = new BlockPos(exploreTarget);
        if (mover.go(Navigator.near(t, 2)) != Mover.Status.RUNNING) {
            exploreTarget = null;
        }
        if (subTicks % 40 < 8) {
            c.lookAngles(s.yaw() + 45f, 0f); // glance around
        }
    }

    // ------------------------------------------------------------------ crafting

    private Recipe<?> recipe(Predicate<Item> out, boolean table) {
        Recipe<?> cur = crafter.current();
        if (cur != null && out.test(cur.getOutput().getItem())) {
            return cur;
        }
        return crafter.recipeFor(out, table);
    }

    final Workbench bench = new Workbench(this);

    /** Crafts one item kind (at a table when needed) until the inventory holds {@code count}. */
    void craftAtTable(Predicate<Item> out, int count, String name) {
        java.util.List<Workbench.Need> needs = new java.util.ArrayList<>();
        needs.add(new Workbench.Need(name, out, count, true));
        if (bench.run(needs) == Actions.Status.FAILED && bench.error() != null && bench.error().startsWith("no_")) {
            craftFailures++;
            if (craftFailures > 3) {
                fail("craft_" + name + "_" + bench.error());
            }
        }
    }

    private int craftFailures;

    private void woodTools() {
        java.util.List<Workbench.Need> needs = new java.util.ArrayList<>();
        needs.add(new Workbench.Need("planks", i -> ItemTags.PLANKS.contains(i), inv.planks() + inv.logs() * 4, false));
        needs.add(new Workbench.Need("wooden_pickaxe", i -> i == Items.WOODEN_PICKAXE, 1, true));
        if (bench.run(needs) == Actions.Status.FAILED) {
            craftFailures++;
            if (craftFailures > 3) {
                fail("craft_wooden_pickaxe_" + bench.error());
            }
        }
    }

    BlockPos placeTargetOrNull() {
        return placeTarget;
    }

    /** A cell next to the player where a block can stand: air (known) with a solid floor, not the player's cells. */
    private BlockPos placeTarget;
    private int placeTries;

    /**
     * Places a block from the inventory next to the player; tries one spot for a while, then another. Returns the
     * position once the block is there (seen), else null.
     */
    BlockPos placeNear(Predicate<ItemStack> item, net.minecraft.block.Block expected) {
        if (placeTarget == null || badTargets.contains(placeTarget.asLong())) {
            placeTarget = placeSpot();
            placeTries = 0;
            if (placeTarget == null) {
                // no free cell with a floor in reach: dig a niche at feet level next to us
                BlockPos f = mover.feet();
                for (Direction d : new Direction[]{digDir, digDir.rotateYClockwise(), digDir.rotateYCounterclockwise(), digDir.getOpposite()}) {
                    BlockPos q = f.offset(d);
                    BlockState qs = mem.get(q);
                    BlockState below = mem.get(q.down());
                    if (qs != null && a.solid(q) && below != null && Blocks2.standable(below) && !badTargets.contains(q.asLong())
                            && Blocks2.pass(qs) == Blocks2.Pass.SOLID && !lavaAround(q)) {
                        if (a.mine(q, false) == Actions.Status.FAILED) {
                            badTargets.add(q.asLong());
                        }
                        return null;
                    }
                }
                c.lookAngles(s.yaw() + 30, 40);
                return null;
            }
        }
        Actions.Status st = a.place(placeTarget, item);
        if (st == Actions.Status.DONE) {
            BlockPos done = placeTarget;
            placeTarget = null;
            BlockState now = mem.get(done);
            if (now == null || now.getBlock() != expected) {
                badTargets.add(done.asLong());
                return null;
            }
            return done;
        }
        if (st == Actions.Status.FAILED || ++placeTries > 40) {
            badTargets.add(placeTarget.asLong());
            placeTarget = null;
        }
        return null;
    }

    private BlockPos placeSpot() {
        BlockPos f = mover.feet();
        BlockPos best = null;
        double bestD = 99;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos q = f.add(dx, dy, dz);
                    if (Math.abs(dx) <= 0 && Math.abs(dz) <= 0) {
                        continue;
                    }
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy >= 0 && dx * dz == 0 && Math.abs(dx + dz) == 0) {
                        continue;
                    }
                    BlockState here = mem.get(q);
                    BlockState below = mem.get(q.down());
                    if (here == null || below == null || Blocks2.pass(here) != Blocks2.Pass.PASS || !Blocks2.standable(below)) {
                        continue;
                    }
                    if (!here.isAir() && !here.getMaterial().isReplaceable()) {
                        continue;
                    }
                    if (inPlayer(q) || badTargets.contains(q.asLong())) {
                        continue;
                    }
                    double d = Vec3d.ofCenter(q).distanceTo(s.eyePos());
                    if (d < bestD && d < 4.0) {
                        bestD = d;
                        best = q;
                    }
                }
            }
        }
        return best;
    }

    boolean lavaAround(BlockPos q) {
        for (Direction d : Direction.values()) {
            BlockState n = mem.get(q.offset(d));
            if (n != null && (Blocks2.isLava(n) || Blocks2.isWater(n))) {
                return true;
            }
        }
        return false;
    }

    boolean inPlayer(BlockPos q) {
        Vec3d p = s.pos();
        return q.getX() + 1 > p.x - 0.3 && q.getX() < p.x + 0.3 && q.getZ() + 1 > p.z - 0.3 && q.getZ() < p.z + 0.3
                && q.getY() + 1 > p.y && q.getY() < p.y + 1.8;
    }

    // ------------------------------------------------------------------ stone

    private static boolean dropsCobble(BlockState st) {
        return st != null && (st.getBlock() == Blocks.STONE || st.getBlock() == Blocks.COBBLESTONE);
    }

    private void cobble(int want) {
        if (inv.cobble() >= want) {
            return;
        }
        BlockPos f = mover.feet();
        if (target == null || !dropsCobble(mem.get(target)) || badTargets.contains(target.asLong())) {
            target = nearestBlock(f, 10, RunBrain::dropsCobble);
            subTicks = 0;
        }
        if (target == null) {
            stairStep();
            return;
        }
        mineTarget(4.2, true);
    }

    /** Mines {@link #target}: walks into reach (digging as needed), mines it, collects the drop. */
    private void mineTarget(double reach, boolean needDrop) {
        BlockPos f = mover.feet();
        if (subTicks > 600) {
            badTargets.add(target.asLong());
            target = null;
            return;
        }
        Navigator.Goal g = Navigator.reach(target, reach);
        if (!g.done(f.getX(), f.getY(), f.getZ()) || (!s.onGround() && !s.inWater())) {
            if (mover.go(g) == Mover.Status.FAILED) {
                badTargets.add(target.asLong());
                target = null;
            }
            return;
        }
        Actions.Status st = a.mine(target, needDrop);
        if (st == Actions.Status.FAILED) {
            badTargets.add(target.asLong());
            target = null;
        } else if (st == Actions.Status.DONE) {
            pickupAt = target;
            pickupTicks = 0;
            target = null;
        }
    }

    private BlockPos pickupAt;
    private int pickupTicks;

    /** Walks onto a just-mined block's cell to pick up its drop (bounded). Returns true while doing it. */
    private boolean collect() {
        if (pickupAt == null) {
            return false;
        }
        if (++pickupTicks > 40) {
            pickupAt = null;
            return false;
        }
        BlockPos f = mover.feet();
        BlockPos stand = pickupAt;
        BlockState st = mem.get(stand);
        while (st != null && Blocks2.pass(st) == Blocks2.Pass.PASS && stand.getY() > 1) {
            BlockState below = mem.get(stand.down());
            if (below == null || Blocks2.standable(below)) {
                break;
            }
            stand = stand.down();
            st = mem.get(stand);
        }
        if (Math.abs(f.getX() - stand.getX()) + Math.abs(f.getZ() - stand.getZ()) <= 0 && Math.abs(f.getY() - stand.getY()) <= 1) {
            pickupAt = null;
            return false;
        }
        if (mover.go(Navigator.near(stand, 0.5)) != Mover.Status.RUNNING) {
            pickupAt = null;
        }
        return true;
    }

    BlockPos nearestBlock(BlockPos f, int r, Predicate<BlockState> pred) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos q = f.add(dx, dy, dz);
                    if (badTargets.contains(q.asLong())) {
                        continue;
                    }
                    BlockState st = mem.get(q);
                    if (st == null || !pred.test(st) || !exposed(q)) {
                        continue;
                    }
                    double d = dx * dx + dy * dy * 1.5 + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = q;
                    }
                }
            }
        }
        return best;
    }

    /** At least one neighbour is known air (the bot saw a face of it). */
    boolean exposed(BlockPos q) {
        for (Direction d : Direction.values()) {
            BlockState n = mem.get(q.offset(d));
            if (n != null && Blocks2.pass(n) == Blocks2.Pass.PASS) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ underground

    /** One step of a 1x2 staircase down in digDir (mines head, body, step), turning away from fluids. */
    private boolean escaping() {
        if (escapeTicks <= 0) {
            return false;
        }
        escapeTicks--;
        BlockPos f = mover.feet();
        BlockPos back = f.offset(digDir.getOpposite(), 6).up(3);
        mover.go(Navigator.near(back, 2));
        return true;
    }

    private void stairStep() {
        if (collect() || escaping()) {
            return;
        }
        BlockPos f = mover.feet();
        BlockPos col = f.offset(digDir);
        BlockPos[] cells = {col.up(), col, col.down()};
        for (BlockPos cell : cells) {
            BlockState st = mem.get(cell);
            if (st != null && (Blocks2.isLava(st) || Blocks2.isWater(st) || Blocks2.pass(st) == Blocks2.Pass.FORBID)) {
                turn();
                return;
            }
        }
        // the step's floor must hold (known after mining the step, or it is rock)
        BlockState floor = mem.get(col.down(2));
        if (floor != null && (Blocks2.isLava(floor))) {
            turn();
            return;
        }
        for (BlockPos cell : cells) {
            if (a.solid(cell) || mem.get(cell) == null) {
                Actions.Status st = a.mine(cell, false);
                if (st == Actions.Status.FAILED) {
                    turn();
                }
                if (st != Actions.Status.DONE) {
                    return;
                }
            }
        }
        if (floor != null && !Blocks2.standable(floor) && !Blocks2.isWater(floor)) {
            // a cave below the step: only go if it is shallow and dry
            BlockPos q = col.down(2);
            int depth = 0;
            while (depth < 6) {
                BlockState qs = mem.get(q);
                if (qs == null || Blocks2.standable(qs)) {
                    break;
                }
                if (Blocks2.isLava(qs)) {
                    turn();
                    return;
                }
                depth++;
                q = q.down();
            }
            if (depth > 3) {
                turn();
                return;
            }
        }
        mover.go(Navigator.at(col.down()));
        lookAhead();
    }

    private void lookAhead() {
        // keep the eyes on the work face, slightly down, so new rock and ores are seen
        if (subTicks % 30 < 6) {
            c.lookAngles(s.yaw() + (subTicks % 60 < 30 ? 60f : -60f), 10f);
        }
    }

    /** One step of a 1x2 tunnel in digDir at the current height. */
    private void tunnelStep() {
        if (collect() || escaping()) {
            return;
        }
        BlockPos f = mover.feet();
        BlockPos col = f.offset(digDir);
        BlockPos[] cells = {col.up(), col};
        for (BlockPos cell : cells) {
            BlockState st = mem.get(cell);
            if (st != null && (Blocks2.isLava(st) || Blocks2.pass(st) == Blocks2.Pass.FORBID)) {
                turn();
                return;
            }
        }
        BlockState floor = mem.get(col.down());
        if (floor != null && (Blocks2.isLava(floor) || (!Blocks2.standable(floor) && !Blocks2.isWater(floor)))) {
            // a hole: fill it if we can, else turn
            if (Blocks2.isLava(floor)) {
                turn();
                return;
            }
            if (inv.throwaways() > 0 && Blocks2.pass(floor) == Blocks2.Pass.PASS) {
                a.place(col.down(), Actions::isThrowaway);
                return;
            }
            turn();
            return;
        }
        for (BlockPos cell : cells) {
            if (a.solid(cell) || mem.get(cell) == null) {
                Actions.Status st = a.mine(cell, false);
                if (st == Actions.Status.FAILED) {
                    turn();
                }
                if (st != Actions.Status.DONE) {
                    return;
                }
            }
        }
        if (mover.go(Navigator.at(col)) == Mover.Status.ARRIVED) {
            tunnelLen++;
        }
        lookAhead();
    }

    private long lastTurnTick;
    private int turnStreak;

    private void turn() {
        Direction[] order = {digDir.rotateYClockwise(), digDir.rotateYCounterclockwise(), digDir.getOpposite()};
        digDir = order[rng.nextInt(2)];
        turnStreak = now() - lastTurnTick < 10 ? turnStreak + 1 : 0;
        lastTurnTick = now();
        if (turnStreak > 6) {
            // boxed in by fluids / drops: walk back toward where the run started for a while
            turnStreak = 0;
            escapeTicks = 120;
            trace("boxed in; backing off");
        }
        trace("turn to " + digDir);
        mover.reset();
    }

    private int escapeTicks;
    final java.util.Random rng;

    /** Ore/gravel within detour reach, else keep digging toward the lava level and along it. */
    private void exploreUnderground(String why) {
        if (target != null) {
            mineTarget(4.2, true);
            return;
        }
        if (collect()) {
            return;
        }
        BlockPos f = mover.feet();
        int detour = p.getInt("dig.oreDetour");
        if (ironTotal() < ironNeeded()) {
            BlockPos ore = nearestBlock(f, detour, st -> st.getBlock() == Blocks.IRON_ORE);
            if (ore != null) {
                target = ore;
                subTicks = 0;
                return;
            }
        }
        if (!inv.lighter() && !inv.has(Items.FLINT) && inv.count(Items.GRAVEL) == 0) {
            BlockPos gr = nearestBlock(f, p.getInt("dig.gravelDetour"), st -> st.getBlock() == Blocks.GRAVEL);
            if (gr != null) {
                target = gr;
                subTicks = 0;
                return;
            }
        }
        if (inv.count(Items.COAL) < 1) {
            BlockPos coal = nearestBlock(f, 3, st -> st.getBlock() == Blocks.COAL_ORE);
            if (coal != null) {
                target = coal;
                subTicks = 0;
                return;
            }
        }
        int targetY = route == RouteSelector.Route.SURFACE_CAST && why.equals("iron") ? 40 : p.getInt("dig.targetY");
        if (f.getY() > targetY) {
            stairStep();
        } else {
            tunnelStep();
            if (tunnelLen > p.getInt("dig.tunnelMax")) {
                fail("tunnel_exhausted_" + why);
            }
        }
    }

    private void iron() {
        exploreUnderground("iron");
        if (inv.count(Items.IRON_ORE) > 0) {
            split("first_iron");
        }
    }

    // ------------------------------------------------------------------ flint

    private void flint() {
        if (inv.has(Items.FLINT)) {
            split("flint");
            return;
        }
        if (target != null) {
            mineTarget(4.2, false);
            if (target == null) {
                gravelBreaks++;
            }
            return;
        }
        if (collect()) {
            return;
        }
        if (gravelBreaks > p.getInt("gravel.maxBreaks") * 2) {
            fail("no_flint");
            return;
        }
        BlockPos f = mover.feet();
        BlockPos gr = nearestBlock(f, 24, st -> st.getBlock() == Blocks.GRAVEL);
        if (gr != null) {
            target = gr;
            subTicks = 0;
            return;
        }
        if (inv.has(Items.GRAVEL)) {
            // place the gravel next to us and break it again (10% flint per break)
            BlockPos spot = placeSpot();
            if (spot != null) {
                if (a.place(spot, st -> st.getItem() == Items.GRAVEL) == Actions.Status.DONE) {
                    target = spot;
                    subTicks = 0;
                }
                return;
            }
        }
        exploreUnderground("gravel");
    }

    // ------------------------------------------------------------------ smelting

    final Smelter smelter = new Smelter(this);

    /** Planks to keep for crafts still ahead (another table for the flint and steel if it is not made yet). */
    int plankReserve() {
        return inv.has(Items.FLINT_AND_STEEL) ? 0 : 4;
    }

    private void smelt() {
        if (collect()) {
            return;
        }
        if (smelter.smelting()) {
            if (smelter.ready()) {
                smelter.collectTick();
                return;
            }
            whileSmelting();
            return;
        }
        if (smelter.phase() == Smelter.Phase.IDLE && !inv.has(Items.FURNACE)
                && mem.nearest(WorldMemory.Kind.FURNACE, mover.feet(), 8, l -> !badTargets.contains(l)) == null) {
            if (inv.cobble() < 8) {
                cobble(8);
                return;
            }
            craftAtTable(i -> i == Items.FURNACE, 1, "furnace");
            return;
        }
        if (smelter.tick() == Actions.Status.FAILED) {
            trace("smelter failed: " + smelter.error());
            if ("no_fuel".equals(smelter.error())) {
                fail("no_fuel");
            }
        }
    }

    /** Useful work near the furnace while the ore smelts: flint from gravel close by, else look around. */
    private void whileSmelting() {
        BlockPos fp = smelter.furnace();
        if (!inv.lighter() && !inv.has(Items.FLINT)) {
            if (target != null) {
                mineTarget(4.2, false);
                return;
            }
            if (fp != null) {
                BlockPos gr = mem.nearest(WorldMemory.Kind.GRAVEL, fp, 16, l -> !badTargets.contains(l) && exposed(BlockPos.fromLong(l)));
                if (gr != null) {
                    target = gr;
                    subTicks = 0;
                    return;
                }
            }
            if (inv.has(Items.GRAVEL)) {
                BlockPos spot = placeSpot();
                if (spot != null && a.place(spot, st -> st.getItem() == Items.GRAVEL) == Actions.Status.DONE) {
                    target = spot;
                    subTicks = 0;
                }
                return;
            }
        }
        if (fp != null && mover.feet().getSquaredDistance(fp) > 16) {
            mover.go(Navigator.reach(fp, 3.5));
            return;
        }
        c.lookAngles(s.yaw() + 6f, 15f);
    }

    int screenSlotOf(Predicate<ItemStack> pred) {
        int n = s.screen().slots.size();
        for (int i = 0; i < n; i++) {
            if (s.playerInvIndexOfScreenSlot(i) < 0) {
                continue;
            }
            ItemStack st = s.screen().getSlot(i).getStack();
            if (!st.isEmpty() && pred.test(st)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ water

    private void water() {
        if (inv.has(Items.WATER_BUCKET)) {
            split("water_bucket");
            return;
        }
        BlockPos f = mover.feet();
        if (target == null || !isWaterSource(target) || badTargets.contains(target.asLong())) {
            target = mem.nearest(WorldMemory.Kind.WATER_SOURCE, f, p.get("portal.waterSearchDist") * 3,
                    l -> !badTargets.contains(l) && exposedTop(BlockPos.fromLong(l)));
            subTicks = 0;
        }
        if (target == null) {
            // go up toward the surface where we came from, looking around
            if (f.getY() < 60 && spawn != null) {
                if (mover.go(Navigator.near(spawn, 3)) == Mover.Status.FAILED) {
                    explore();
                }
            } else {
                explore();
            }
            return;
        }
        if (subTicks > 900) {
            badTargets.add(target.asLong());
            target = null;
            return;
        }
        if (fillBucket(target, Items.WATER_BUCKET) == Actions.Status.FAILED) {
            badTargets.add(target.asLong());
            target = null;
        }
    }

    boolean isWaterSource(BlockPos q) {
        BlockState st = mem.get(q);
        return st != null && Blocks2.isWaterSource(st);
    }

    boolean exposedTop(BlockPos q) {
        BlockState up = mem.get(q.up());
        return up != null && (up.isAir() || Blocks2.isWater(up) && false);
    }

    /**
     * Walks into reach of a fluid source and scoops it with an empty bucket (aims at the fluid surface; the bucket's
     * own ray picks the source). DONE when the filled bucket is in the inventory.
     */
    Actions.Status fillBucket(BlockPos src, Item filled) {
        int have = inv.count(filled);
        if (fillStartCount < 0) {
            fillStartCount = have;
        }
        if (have > fillStartCount) {
            fillStartCount = -1;
            return Actions.Status.DONE;
        }
        BlockPos f = mover.feet();
        Navigator.Goal g = Navigator.see(src, 3.6, mem);
        if (!g.done(f.getX(), f.getY(), f.getZ()) || (!s.onGround() && !s.inWater())) {
            fillAimTicks = 0;
            if (mover.go(g) == Mover.Status.FAILED) {
                fillStartCount = -1;
                return Actions.Status.FAILED;
            }
            return Actions.Status.RUNNING;
        }
        if (a.equip(Items.BUCKET) != Actions.Status.DONE) {
            if (!inv.has(Items.BUCKET)) {
                fillStartCount = -1;
                return Actions.Status.FAILED;
            }
            return Actions.Status.RUNNING;
        }
        Vec3d aim = new Vec3d(src.getX() + 0.5, src.getY() + 0.9, src.getZ() + 0.5);
        c.lookAt(aim);
        net.minecraft.util.hit.HitResult h = s.fluidCrosshair();
        boolean onSource = false;
        if (h instanceof BlockHitResult && h.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            BlockState hs = mem.get(((BlockHitResult) h).getBlockPos());
            onSource = hs != null && (filled == Items.LAVA_BUCKET ? Blocks2.isLavaSource(hs) : Blocks2.isWaterSource(hs));
        }
        if (onSource && c.aimed(aim, 6f)) {
            c.use();
        }
        if (++fillAimTicks > 60) {
            fillAimTicks = 0;
            fillStartCount = -1;
            return Actions.Status.FAILED; // something is in the way
        }
        return Actions.Status.RUNNING;
    }

    private int fillStartCount = -1;
    private int fillAimTicks;

    // ------------------------------------------------------------------ helpers for the portal builder

    BlockPos feet() {
        return mover.feet();
    }

    Inv inv() {
        return inv;
    }

    LongOpenHashSet badTargets() {
        return badTargets;
    }

    void exploreForLava() {
        exploreUnderground("lava");
    }

    float yawTo(BlockPos q) {
        Vec3d e = s.eyePos();
        return InputLimits.yawTo(q.getX() + 0.5 - e.x, q.getZ() + 0.5 - e.z);
    }

    public int deaths() {
        return deaths;
    }
}
