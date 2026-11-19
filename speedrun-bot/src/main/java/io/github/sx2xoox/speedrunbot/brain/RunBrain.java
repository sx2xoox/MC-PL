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

    private int ironNeeded() {
        return p.getInt("iron.needed");
    }

    private Goal choose() {
        if (crafter.current() != null || s.screenOpen()) {
            return goal; // ingredients may sit in a crafting grid: finish first
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
            return inv.cobble() >= 3 ? Goal.STONE_PICK : Goal.COBBLE;
        }
        boolean needIron = inv.ironEquivalent() < ironNeeded();
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
                cobble(3);
                break;
            case STONE_PICK:
                craftAtTable(st -> st == Items.STONE_PICKAXE, 1, "stone_pickaxe");
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
            float yaw = s.yaw() + (float) (Math.random() * 120 - 60);
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

    private int woodStep;
    private int badTables;

    private void woodTools() {
        // 1) every log into planks (one recipe-book shift-click), 2) a table in the 2x2 grid, 3) at the table: sticks +
        // wooden pickaxe
        if (woodStep == 0) {
            if (inv.logs() == 0 && !s.screenOpen()) {
                woodStep = 1;
                return;
            }
            Recipe<?> r = recipe(i -> ItemTags.PLANKS.contains(i), false);
            if (r == null) {
                dbg("no planks recipe");
                return;
            }
            if (crafter.craft(r, 1, false, true) == Actions.Status.DONE) {
                woodStep = 1;
            }
            return;
        }
        if (woodStep == 1) {
            if (inv.has(Items.CRAFTING_TABLE) || tablePos != null || mem.nearest(WorldMemory.Kind.CRAFTING_TABLE, mover.feet(), 8, null) != null) {
                woodStep = 2;
                return;
            }
            Recipe<?> r = recipe(i -> i == Items.CRAFTING_TABLE, false);
            if (r == null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < s.screen().slots.size(); i++) {
                    if (!s.screen().getSlot(i).getStack().isEmpty()) {
                        sb.append(i).append('=').append(s.screen().getSlot(i).getStack()).append(' ');
                    }
                }
                dbg("no table recipe " + inv.summary() + " screen=" + s.screen().getClass().getSimpleName() + " slots: " + sb + " cursor=" + s.inventory().getCursorStack());
                return;
            }
            if (crafter.craft(r, 1, false) == Actions.Status.DONE) {
                woodStep = 2;
            }
            return;
        }
        craftAtTable(i -> i == Items.WOODEN_PICKAXE, 1, "wooden_pickaxe");
    }

    /** Crafts at a table: uses a known table within reach, else places one (crafting it if needed). */
    void craftAtTable(Predicate<Item> out, int times, String splitName) {
        if (s.screen() instanceof CraftingScreenHandler) {
            // sticks first when the recipe needs them
            if (out.test(Items.WOODEN_PICKAXE) || out.test(Items.STONE_PICKAXE)) {
                if (inv.count(Items.STICK) < 2) {
                    Recipe<?> sr = recipe(i -> i == Items.STICK, true);
                    if (sr != null) {
                        crafter.craft(sr, 1, true);
                        return;
                    }
                }
            }
            Recipe<?> r = recipe(out, true);
            if (r == null) {
                c.closeScreen();
                trace("cannot craft " + splitName + " inv=" + inv.summary());
                if (subTicks > 200) {
                    fail("craft_" + splitName);
                }
                return;
            }
            Actions.Status st = crafter.craft(r, times, true);
            if (st == Actions.Status.DONE) {
                split(splitName);
            }
            return;
        }
        if (s.screenOpen()) {
            if (crafter.current() != null) {
                crafter.craft(crafter.current(), 1, false); // an inventory craft (planks / table) under way
            } else {
                c.closeScreen();
            }
            return;
        }
        BlockPos f = mover.feet();
        if (tablePos == null && badTables == 0) {
            tablePos = mem.nearest(WorldMemory.Kind.CRAFTING_TABLE, f, 8, null);
        }
        if (tablePos != null && mem.get(tablePos) != null && mem.get(tablePos).getBlock() != Blocks.CRAFTING_TABLE) {
            tablePos = null;
        }
        if (tablePos != null && tablePos.getSquaredDistance(f) > 24 * 24) {
            tablePos = null;
        }
        if (tablePos == null) {
            if (!inv.has(Items.CRAFTING_TABLE)) {
                if (inv.planks() < 4 && inv.logs() > 0) {
                    Recipe<?> pr = recipe(i -> ItemTags.PLANKS.contains(i), false);
                    if (pr != null) {
                        crafter.craft(pr, 1, false);
                    }
                    return;
                }
                Recipe<?> r = recipe(i -> i == Items.CRAFTING_TABLE, false);
                if (r == null) {
                    fail("no_planks_for_table");
                    return;
                }
                crafter.craft(r, 1, false);
                return;
            }
            BlockPos spot = placeSpot();
            if (spot == null) {
                // stand still a moment and look down to find a floor
                c.lookAngles(s.yaw() + 30, 40);
                dbg("no spot for the table");
                return;
            }
            Actions.Status st = a.place(spot, x -> x.getItem() == Items.CRAFTING_TABLE);
            dbg("placing table at " + spot + " -> " + st + " click=" + a.placementClick(spot) + " hit=" + s.crosshair().getPos());
            if (st == Actions.Status.DONE) {
                tablePos = spot;
                trace("table at " + spot.toString());
            }
            return;
        }
        Navigator.Goal g = Navigator.reach(tablePos, 4.0);
        if (!g.done(f.getX(), f.getY(), f.getZ())) {
            Mover.Status ms = mover.go(g);
            dbg("to table " + tablePos + " from " + f + " -> " + ms + " ground=" + s.onGround() + " " + mover.lastPlanInfo());
            if (ms == Mover.Status.FAILED && subTicks > 200) {
                trace("table unreachable; a new one will be placed");
                tablePos = null;
                badTables++;
            }
            return;
        }
        // open: right click the table
        if (a.equip(st -> !st.isEmpty() && !(st.getItem() instanceof net.minecraft.item.BlockItem)) == Actions.Status.RUNNING) {
            return; // hold a non-block item so the click opens the table instead of placing a block
        }
        if (subTicks > 300) {
            trace("cannot open the table at " + tablePos + "; placing a new one");
            tablePos = null;
            subTicks = 0;
            return;
        }
        if (a.useBlock(tablePos) == Actions.Status.DONE) {
            c.screenOpened();
        }
    }

    /** A cell next to the player where a block can stand: air (known) with a solid floor, not the player's cells. */
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
                    if (inPlayer(q)) {
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
    private void stairStep() {
        if (collect()) {
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
        if (collect()) {
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

    private void turn() {
        Direction[] order = {digDir.rotateYClockwise(), digDir.rotateYCounterclockwise(), digDir.getOpposite()};
        digDir = order[(int) (Math.random() * 2)];
        trace("turn to " + digDir);
        mover.reset();
    }

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
        if (inv.ironEquivalent() < ironNeeded()) {
            BlockPos ore = nearestBlock(f, detour, st -> st.getBlock() == Blocks.IRON_ORE);
            if (ore != null) {
                target = ore;
                subTicks = 0;
                return;
            }
        }
        if (!inv.lighter() && !inv.has(Items.FLINT) && inv.count(Items.GRAVEL) == 0) {
            BlockPos gr = nearestBlock(f, 5, st -> st.getBlock() == Blocks.GRAVEL);
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

    private void smelt() {
        if (collect()) {
            return;
        }
        if (furnacePos == null) {
            if (!inv.has(Items.FURNACE)) {
                if (inv.cobble() < 8) {
                    cobble(8);
                    return;
                }
                craftAtTable(i -> i == Items.FURNACE, 1, "furnace");
                return;
            }
            if (s.screenOpen()) {
                c.closeScreen();
                return;
            }
            BlockPos spot = placeSpot();
            if (spot == null) {
                c.lookAngles(s.yaw() + 30, 40);
                return;
            }
            Actions.Status st = a.place(spot, x -> x.getItem() == Items.FURNACE);
            if (st == Actions.Status.DONE) {
                furnacePos = spot;
                trace("furnace at " + spot.toString());
            }
            return;
        }
        BlockPos f = mover.feet();
        Navigator.Goal g = Navigator.reach(furnacePos, 4.0);
        if (!g.done(f.getX(), f.getY(), f.getZ())) {
            mover.go(g);
            return;
        }
        if (smeltReady > 0 && now() < smeltReady) {
            // wait: look around (perception keeps learning)
            c.lookAngles(s.yaw() + 6f, 15f);
            return;
        }
        if (!(s.screen() instanceof AbstractFurnaceScreenHandler)) {
            if (s.screenOpen()) {
                c.closeScreen();
                return;
            }
            if (a.equip(st -> !st.isEmpty() && !(st.getItem() instanceof net.minecraft.item.BlockItem)) == Actions.Status.RUNNING) {
                return;
            }
            if (a.useBlock(furnacePos) == Actions.Status.DONE) {
                c.screenOpened();
            }
            return;
        }
        if (!c.guiReady()) {
            return;
        }
        AbstractFurnaceScreenHandler h = (AbstractFurnaceScreenHandler) s.screen();
        ItemStack out = h.getSlot(2).getStack();
        if (smeltReady > 0) {
            if (!out.isEmpty()) {
                c.click(2, 0, SlotActionType.QUICK_MOVE);
                return;
            }
            if (!h.getSlot(0).getStack().isEmpty()) {
                // not done yet: close and wait a bit more
                smeltReady = now() + 40;
                c.closeScreen();
                return;
            }
            smeltReady = -1;
            split("iron_ingots");
            c.closeScreen();
            return;
        }
        // load: ore into the input, fuel into the fuel slot
        int oreSlot = screenSlotOf(st -> st.getItem() == Items.IRON_ORE);
        if (oreSlot >= 0) {
            c.click(oreSlot, 0, SlotActionType.QUICK_MOVE);
            return;
        }
        int ores = h.getSlot(0).getStack().getCount();
        if (h.getSlot(1).getStack().isEmpty()) {
            int coal = screenSlotOf(st -> st.getItem() == Items.COAL || st.getItem() == Items.CHARCOAL);
            if (coal >= 0) {
                c.click(coal, 0, SlotActionType.QUICK_MOVE);
                return;
            }
        }
        ItemStack fuel = h.getSlot(1).getStack();
        int fuelItems = fuel.isEmpty() ? 0 : (fuel.getItem() == Items.COAL || fuel.getItem() == Items.CHARCOAL ? fuel.getCount() * 8
                : (int) Math.floor(fuel.getCount() * 1.5));
        ItemStack cursor = s.inventory().getCursorStack();
        if (fuelItems < ores) {
            if (cursor.isEmpty()) {
                int planks = screenSlotOf(Inv::isPlanks);
                if (planks < 0) {
                    planks = screenSlotOf(Inv::isLog);
                }
                if (planks < 0) {
                    fail("no_fuel");
                    return;
                }
                c.click(planks, 0, SlotActionType.PICKUP);
            } else {
                c.click(1, 1, SlotActionType.PICKUP); // right click: one item into the fuel slot
            }
            return;
        }
        if (!cursor.isEmpty()) {
            int empty = -1;
            for (int i = 3; i < h.slots.size(); i++) {
                ItemStack st = h.getSlot(i).getStack();
                if (st.isEmpty() || ItemStack.areItemsEqual(st, cursor)) {
                    empty = i;
                    break;
                }
            }
            c.click(empty, 0, SlotActionType.PICKUP);
            return;
        }
        smeltLoaded = ores;
        smeltReady = now() + ores * 200L + 5;
        trace("smelting " + ores + " ore, ready in " + ores * 10 + "s");
        c.closeScreen();
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
        Navigator.Goal g = Navigator.reach(src, 3.8);
        if (!g.done(f.getX(), f.getY(), f.getZ()) || (!s.onGround() && !s.inWater())) {
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
        Vec3d aim = new Vec3d(src.getX() + 0.5, src.getY() + 0.8, src.getZ() + 0.5);
        c.lookAt(aim);
        if (c.aimed(aim, 2f)) {
            c.use();
        }
        return Actions.Status.RUNNING;
    }

    private int fillStartCount = -1;

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
