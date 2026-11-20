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
    public enum Goal { SCAN, WOOD, WOOD_TOOLS, SCOUT, TREASURE, LOOT, COBBLE, STONE_PICK, IRON, FLINT, SMELT, IRON_TOOLS, WATER, LAVA, PORTAL, DONE }

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
        listen();
        goalTicks++;
        if (inv.has(Items.WATER_BUCKET)) {
            split("water_bucket"); // however it was filled (the goal may change before water() runs again)
        }
        float hp = s.health();
        if (hp < lastHealth - 0.01f) {
            Vec3d pos = s.pos();
            trace(String.format("hurt %.1f -> %.1f at %.1f,%.1f,%.1f lava=%s water=%s fire=%s", lastHealth, hp, pos.x, pos.y, pos.z,
                    s.inLava(), s.inWater(), s.onFire()));
        }
        lastHealth = hp;
        if (goalTicks % 200 == 0) {
            Vec3d pos = s.pos();
            trace(String.format("status pos=%.1f,%.1f,%.1f hp=%.0f food=%d act=%s broken=%d target=%s plans=%d inv=%s", pos.x, pos.y, pos.z,
                    s.health(), s.food(), act, a.broken, target, mover.plans(), inv.summary()));
        }
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        watchdog();
        if (!survival.tick() && !unstick() && !(!s.screenOpen() && relocating())) {
            Goal next = choose();
            if (next != goal) {
                trace("goal " + goal + " -> " + next + " after " + goalTicks + "t inv=" + inv.summary());
                if (next == Goal.PORTAL && goal == Goal.LAVA) {
                    BlockPos uw = mem.nearest(WorldMemory.Kind.WATER_SOURCE, mover.feet(), 48, l -> BlockPos.unpackLongY(l) < 40);
                    trace("underground water near the lava: " + (uw == null ? "none" : uw + " at " + Math.round(Math.sqrt(uw.getSquaredDistance(mover.feet())))));
                }
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

    // ------------------------------------------------------------------ hearing

    private final java.util.ArrayDeque<double[]> lavaHeard = new java.util.ArrayDeque<>();
    /** Places where heard lava turned out to be lava we already see but cannot use (flows, springs). */
    private final List<Vec3d> lavaExplained = new ArrayList<>();
    private Vec3d lavaHeardAt;

    /** Lava sounds (rough direction and distance, as a human hears them) averaged into a guess where lava is. */
    private void listen() {
        for (io.github.sx2xoox.speedrunbot.core.Hearing.Heard h : s.heardLava()) {
            boolean old = false;
            for (Vec3d e : lavaExplained) {
                if (e.squaredDistanceTo(h.x, h.y, h.z) < 7 * 7) {
                    old = true; // lava already seen there (a lava fall, a spring): no portal pool
                }
            }
            if (old) {
                continue;
            }
            lavaHeard.addLast(new double[]{h.x, h.y, h.z, now()});
            if (lavaHeard.size() > 12) {
                lavaHeard.removeFirst();
            }
        }
        while (!lavaHeard.isEmpty() && now() - lavaHeard.peekFirst()[3] > 1200) {
            lavaHeard.removeFirst();
        }
        if (lavaHeard.size() < 2) {
            lavaHeardAt = null;
            return;
        }
        double x = 0, y = 0, z = 0;
        for (double[] q : lavaHeard) {
            x += q[0];
            y += q[1];
            z += q[2];
        }
        int n = lavaHeard.size();
        Vec3d est = new Vec3d(x / n, y / n, z / n);
        if (lavaHeardAt == null) {
            trace(String.format("heard lava %d times, around %.0f,%.0f,%.0f", n, est.x, est.y, est.z));
        }
        lavaHeardAt = est;
    }

    private long progressKey;
    private Vec3d progressAnchor;
    private long lastProgressTick;
    private int stalls;

    /**
     * Nothing moved and nothing changed in the inventory for a long time (a step that can never finish): drop the
     * sub-goal state so the goal starts over from what it knows now.
     */
    private void watchdog() {
        BlockPos f = mover.feet();
        // progress = the inventory changed or the bot got 2.5 blocks away from where it was (pacing back and forth
        // between two cells for minutes on a mineshaft's rails counted as moving before)
        long key = inventoryHash() * 31 + a.broken;
        Vec3d here = s.pos();
        if (key != progressKey || progressAnchor == null || here.squaredDistanceTo(progressAnchor) > 2.5 * 2.5 || smelter.smelting() || bench.busy()) {
            progressKey = key;
            progressAnchor = here;
            lastProgressTick = now();
            return;
        }
        if (now() - lastProgressTick > p.getInt("safety.stallTicks")) {
            stalls++;
            trace("stalled " + (now() - lastProgressTick) + "t in " + goal + " (doing " + act + ", target " + target + ", cave " + caveTarget
                    + ", mover " + mover.lastPlanInfo() + "): starting the step over; " + around(f));
            if (target != null) {
                badTargets.add(target.asLong());
            }
            if (caveTarget != null) {
                markVisited(caveTarget);
            }
            if (lastStallPos != null && lastStallPos.getSquaredDistance(f) < 9 && now() - lastStallTick < 1500) {
                if (!portal.active()) {
                    trace("stalled at the same spot again: avoiding " + mover.stalledAgain());
                } else {
                    portal.stalledAgain(); // a site whose next step cannot be done from here: another site
                }
            }
            target = null;
            caveTarget = null;
            placeTarget = null;
            exploreTarget = null;
            mover.reset();
            mover.forgetAvoid();
            if (s.screenOpen()) {
                c.closeScreen();
            }
            if (act.equals("stairs")) {
                forceShaftUntil = now() + 400; // the staircase is stuck here: dig the shaft for a while instead
            }
            turn();
            lastProgressTick = now();
            unstickTicks = 30;
            unstickYaw = rng.nextFloat() * 360f;
            if (p.get("safety.relocate") > 0 && lastStallPos != null && lastStallPos.getSquaredDistance(f) < 36 && now() - lastStallTick < 1500 && !portal.active()) {
                // the same spot again: a fresh start from here is not enough, go somewhere else first
                relocate(f);
            }
            lastStallPos = f;
            lastStallTick = now();
        }
    }

    private BlockPos lastStallPos;
    private long lastStallTick;
    private BlockPos relocateTo;
    private int relocateTicks;
    private int relocations;

    private void relocate(BlockPos f) {
        relocations++;
        double yaw = rng.nextDouble() * Math.PI * 2;
        int dist = 8 + rng.nextInt(8);
        int dy = goal == Goal.WATER || goal == Goal.WOOD ? 2 : (f.getY() > 20 && (goal == Goal.LAVA || goal == Goal.IRON) ? -3 : 0);
        relocateTo = new BlockPos(f.getX() + Math.round(Math.cos(yaw) * dist), f.getY() + dy, f.getZ() + Math.round(Math.sin(yaw) * dist));
        relocateTicks = 400;
        unstickTicks = 0;
        digDir = Direction.fromHorizontal(rng.nextInt(4));
        if (act.equals("shaft")) {
            noStraightUntil = now() + 600;
        }
        trace("stalled at the same spot again: relocating to " + relocateTo + " (" + relocations + ")");
    }

    /** After repeated stalls at one spot: walk (digging as needed) to a spot some blocks away. */
    private boolean relocating() {
        if (relocateTicks <= 0 || relocateTo == null) {
            return false;
        }
        relocateTicks--;
        act = "relocate";
        Mover.Status st = mover.go(Navigator.near(relocateTo, 2));
        if (st != Mover.Status.RUNNING) {
            relocateTicks = 0;
            mover.reset();
            if (st == Mover.Status.FAILED) {
                trace("relocation to " + relocateTo + " failed");
            }
        }
        return relocateTicks > 0;
    }

    /** The remembered blocks around the feet (stall diagnostics). */
    private String around(BlockPos f) {
        StringBuilder sb = new StringBuilder("pos=").append(String.format("%.2f,%.2f,%.2f", s.pos().x, s.pos().y, s.pos().z))
                .append(" ground=").append(s.onGround()).append(" water=").append(s.inWater());
        String[] names = {"below", "feet", "head", "N", "S", "E", "W", "Nh", "Sh", "Eh", "Wh", "Nb", "Sb", "Eb", "Wb"};
        BlockPos[] qs = {f.down(), f, f.up(), f.north(), f.south(), f.east(), f.west(), f.north().up(), f.south().up(), f.east().up(),
                f.west().up(), f.north().down(), f.south().down(), f.east().down(), f.west().down()};
        for (int i = 0; i < qs.length; i++) {
            BlockState st = mem.get(qs[i]);
            String n = st == null ? "?" : net.minecraft.util.registry.Registry.BLOCK.getId(st.getBlock()).getPath();
            sb.append(' ').append(names[i]).append('=').append(n);
        }
        return sb.toString();
    }

    private int unstickTicks;
    String act = "";

    private boolean setAct(String a) {
        act = a;
        return true;
    }
    private float unstickYaw;

    /** After a stall: walk (and jump, and dig what blocks the way) in a random direction for a moment. */
    private boolean unstick() {
        if (unstickTicks <= 0) {
            return false;
        }
        unstickTicks--;
        if (portal.active() || lavaSeenNear(mover.feet(), 8)) {
            unstickTicks = 0; // never walk blindly next to a lava pool (two runs walked into one)
            return false;
        }
        {
            // never walk blindly into lava or over an unseen drop (a stall next to a lava pool killed runs)
            BlockPos f = mover.feet();
            double[] d = PerceptionRules.direction(unstickYaw, 0);
            BlockPos ahead = f.add(Math.round(d[0]), 0, Math.round(d[2]));
            for (BlockPos q : new BlockPos[]{ahead, ahead.down(), ahead.down(2), ahead.up()}) {
                BlockState st = mem.get(q);
                if ((st != null && Blocks2.isLava(st)) || a.lavaNextTo(q)) {
                    unstickYaw += 90f + rng.nextFloat() * 180f;
                    c.lookAngles(unstickYaw, 10f);
                    return true;
                }
            }
            BlockState below = mem.get(ahead.down());
            if (below == null || (!Blocks2.standable(below) && mem.get(ahead.down(2)) == null)) {
                c.lookAngles(unstickYaw, 40f); // look before stepping
                return true;
            }
        }
        c.lookAngles(unstickYaw, 10f);
        c.move(1f, 0f, true, false, false);
        if (s.horizontalCollision()) {
            BlockPos f = mover.feet();
            double[] d = PerceptionRules.direction(unstickYaw, 0);
            BlockPos ahead = f.add(Math.round(d[0]), 1, Math.round(d[2]));
            if (a.solid(ahead) && !a.lavaNextTo(ahead)) {
                a.mine(ahead, false);
            }
        }
        return true;
    }

    private long inventoryHash() {
        long h = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = s.inventory().getStack(i);
            h = h * 17 + (st.isEmpty() ? 0 : st.getCount() * 131 + Item.getRawId(st.getItem()));
        }
        return h;
    }

    final Survival survival = new Survival(this);
    private final Looter looter = new Looter(this);

    Looter looter() {
        return looter;
    }

    /** Old melee-only defence (kept for reference; Survival replaces it). */
    private boolean defendOld() {
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
        if (bench.busy() || smelter.screenBusy() || looter.screenBusy() || a.equipBusy() || (s.screenOpen() && goalTicks < 400)) {
            return goal; // a screen is in use (crafting, furnace): finish first
        }
        if (goal == Goal.SCAN && goalTicks < p.getInt("surface.scanTicks")) {
            return Goal.SCAN;
        }
        if (goal == Goal.SCAN) {
            chooseRoute();
        }
        if (!inv.pickaxe() && inv.cobble() >= 3 && inv.planks() + inv.logs() * 4 >= 5 && goal != Goal.SCAN && goal != Goal.WOOD
                && goal != Goal.WOOD_TOOLS && (goal == Goal.STONE_PICK || goalTicks > 20)) {
            return Goal.STONE_PICK; // the pickaxe wore out (tunnels, the portal room): a new one before anything else
        }
        if (portal.waitingForLighter()) {
            // the frame is done but the flint was left for later: get it now (gravel near the portal)
            if (inv.count(Items.IRON_INGOT) < 1 && !smelter.smelting()) {
                return Goal.SMELT;
            }
            return inv.has(Items.FLINT) ? Goal.IRON_TOOLS : Goal.FLINT;
        }
        if (portal.active()) {
            return Goal.PORTAL;
        }
        watchForShip();
        if (goal == Goal.LOOT && looter.busy()) {
            return Goal.LOOT;
        }
        if (!inv.pickaxe() && inv.cobble() >= 3 && inv.planks() + inv.logs() * 4 >= 6 && inv.has(Items.STONE_PICKAXE) == false
                && (goal != Goal.SCAN && goal != Goal.WOOD && goal != Goal.WOOD_TOOLS)) {
            return Goal.STONE_PICK; // a pickaxe wore out underground: a new stone one from what we carry
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
        if (needWood) {
            if (inv.logs() * 4 + inv.planks() >= 8) {
                needWood = false;
            } else {
                return Goal.WOOD;
            }
        }
        if (route == RouteSelector.Route.TREASURE && !treasure.finished() && ironTotal() < ironNeeded()) {
            return Goal.TREASURE;
        }
        if (route != RouteSelector.Route.TREASURE && !scoutDone && inv.buckets() == 0 && ironTotal() < ironNeeded()
                && (goal == Goal.SCOUT || seaSeen())) {
            return Goal.SCOUT; // an ocean at the spawn: a short look for a shipwreck before digging (the runners' ocean route)
        }
        if (goal != Goal.PORTAL && chestWorthLooting() != null) {
            return Goal.LOOT;
        }
        if (!inv.stonePick()) {
            return inv.cobble() >= 3 * p.getInt("stone.picks") + 2 || (inv.cobble() >= 3 && goal == Goal.COBBLE && goalTicks > 400)
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
            if (!inv.has(Items.FLINT) && (p.get("route.flintLate") <= 0 || inv.count(Items.IRON_INGOT) < 1)) {
                return Goal.FLINT;
            }
            if (inv.has(Items.FLINT) && !lighterDeferred) {
                return Goal.IRON_TOOLS;
            }
            // flint later: gravel is picked up on the way down to the lava (caves have plenty); the lighter is only
            // needed once the frame stands
        }
        if (!inv.has(Items.WATER_BUCKET) && !portal.waterPlaced()) {
            return Goal.WATER;
        }
        BlockPos lavaKnown = portal.chooseLava();
        if (lavaKnown == null) {
            return Goal.LAVA;
        }
        if (Math.sqrt(lavaKnown.getSquaredDistance(mover.feet())) > p.get("portal.approachDist")) {
            return Goal.LAVA; // seen far away (a ravine, a pool): get close first, the site is chosen there
        }
        return Goal.PORTAL;
    }

    final TreasureHunt treasure = new TreasureHunt(this);
    private BlockPos shipPos;
    private long lastShipLook;
    private BlockPos lootChest;

    private boolean scoutDone;
    private int scoutTicks;
    private Boolean seaSeenCache;

    /** Many water sources at sea level around the spawn: an ocean or a big lake shore. */
    private boolean seaSeen() {
        if (seaSeenCache == null && goalTicks > 0) {
            int n = 0;
            it.unimi.dsi.fastutil.longs.LongIterator it = mem.of(WorldMemory.Kind.WATER_SOURCE).iterator();
            while (it.hasNext()) {
                long l = it.nextLong();
                int y = BlockPos.unpackLongY(l);
                if (y >= 58 && y <= 63 && BlockPos.fromLong(l).getSquaredDistance(spawn) < 80 * 80) {
                    n++;
                }
            }
            seaSeenCache = n >= p.getInt("treasure.seaSources");
            trace("sea level water sources near the spawn: " + n + (seaSeenCache ? " (ocean: scouting for a wreck)" : ""));
        }
        return seaSeenCache != null && seaSeenCache;
    }

    /** Swims out over the sea looking down for a wreck for a short while (bounded by treasure.scoutTicks). */
    private void scout() {
        act = "scout";
        if (scoutTicks % 20 == 0) {
            lastShipLook = 0; // look for the wreck more often than on the normal route
            watchForShip();
        }
        if (scoutTicks % 200 == 0) {
            trace("scouting: worked wood seen " + mem.count(WorldMemory.Kind.WOODWORK) + ", chests " + mem.count(WorldMemory.Kind.CHEST));
        }
        if (route == RouteSelector.Route.TREASURE || ++scoutTicks > p.getInt("treasure.scoutTicks")) {
            scoutDone = true;
            trace("scouting done: " + (route == RouteSelector.Route.TREASURE ? "wreck found" : "no wreck seen"));
            return;
        }
        BlockPos f = mover.feet();
        if (exploreTarget == null || exploreTarget.squaredDistanceTo(Vec3d.ofCenter(f)) < 9 || subTicks > 300) {
            // head for the water and then along it: the farthest seen sea-level water within 40 blocks, a new one each leg
            BlockPos w = null;
            double bd = -1;
            it.unimi.dsi.fastutil.longs.LongIterator it = mem.of(WorldMemory.Kind.WATER_SOURCE).iterator();
            int k = 0;
            while (it.hasNext() && k < 4000) {
                long l = it.nextLong();
                k++;
                int y = BlockPos.unpackLongY(l);
                if (y < 58 || y > 63) {
                    continue;
                }
                BlockPos q = BlockPos.fromLong(l);
                double d = q.getSquaredDistance(f);
                if (d < 40 * 40 && d > bd && rng.nextInt(4) == 0) {
                    bd = d;
                    w = q;
                }
            }
            exploreTarget = w == null ? null : Vec3d.ofCenter(w);
            subTicks = 0;
            if (w == null) {
                scoutDone = true;
                return;
            }
        }
        if (diveTicks > 0) {
            // dive: sink while turning and looking down (wrecks lie on the sea floor, deeper than one sees from above)
            diveTicks--;
            c.sink();
            c.lookAngles(s.yaw() + 12f, 55f);
            if (diveTicks == 0 || s.air() < 140) {
                diveTicks = 0;
                surfaceTicks = 60;
            }
            return;
        }
        if (surfaceTicks > 0) {
            surfaceTicks--;
            c.lookAngles(s.yaw() + 8f, -30f);
            c.move(0.3f, 0f, true, false, false);
            return;
        }
        Mover.Status st = mover.go(Navigator.near(new BlockPos(exploreTarget), 2));
        if (s.inWater() && (st != Mover.Status.RUNNING || subTicks % 200 == 199)) {
            diveTicks = 150;
            exploreTarget = null;
            return;
        }
        if (subTicks % 30 < 12) {
            c.lookAngles(s.yaw() + 30f, 45f); // look down into the water around
        }
    }

    private int diveTicks;
    private int surfaceTicks;

    /** Later in the run a wreck may come into view (walking for wood): switch to the treasure route then. */
    private void watchForShip() {
        if (route == RouteSelector.Route.TREASURE || treasure.finished() || now() - lastShipLook < 100 || ironTotal() >= ironNeeded()
                || inv.buckets() > 0) {
            return;
        }
        lastShipLook = now();
        BlockPos sp = TreasureHunt.findShip(mem, mover.feet(), p.get("treasure.shipDist"));
        if (sp != null) {
            shipPos = sp;
            route = RouteSelector.Route.TREASURE;
            routeNote += " -> TREASURE(ship seen at " + sp.getX() + "," + sp.getY() + "," + sp.getZ() + ")";
            trace("shipwreck seen at " + sp + ": treasure route");
        }
    }

    /** A chest seen close by while iron is still missing (village, ruined portal, shipwreck): worth a look. */
    private BlockPos chestWorthLooting() {
        if (inv.buckets() > 0 && inv.lighter()) {
            return null;
        }
        return mem.nearest(WorldMemory.Kind.CHEST, mover.feet(), p.get("loot.chestDist"), l -> !badTargets.contains(l));
    }

    private void chooseRoute() {
        RouteSelector.Observation o = new RouteSelector.Observation();
        BlockPos f = mover.feet();
        BlockPos lava = mem.nearest(WorldMemory.Kind.LAVA_SOURCE, f, 200, l -> BlockPos.unpackLongY(l) >= 50);
        BlockPos water = mem.nearest(WorldMemory.Kind.WATER_SOURCE, f, 200, null);
        BlockPos iron = mem.nearest(WorldMemory.Kind.IRON_ORE, f, 200, null);
        shipPos = TreasureHunt.findShip(mem, f, p.get("treasure.shipDist"));
        if (shipPos != null) {
            o.ship(Math.sqrt(shipPos.getSquaredDistance(f)));
        }
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
        trace("route " + routeNote + " lava=" + o.surfaceLavaDist + " water=" + o.waterDist + " iron=" + o.ironKnown + " ship=" + shipPos);
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
        if (recoveringWater()) {
            return;
        }
        if (looter.screenBusy()) {
            looter.tick(); // and a chest
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
            case SCOUT:
                scout();
                break;
            case TREASURE:
                if (!treasure.active()) {
                    treasure.start(shipPos);
                }
                treasure.tick();
                if (treasure.phase() == TreasureHunt.Phase.FAILED) {
                    trace("treasure route failed: " + treasure.why());
                }
                break;
            case LOOT:
                if (!looter.busy()) {
                    BlockPos ch = chestWorthLooting();
                    if (ch == null) {
                        break;
                    }
                    looter.start(ch);
                }
                if (looter.tick() == Actions.Status.FAILED) {
                    badTargets.add(looter.chest().asLong());
                }
                break;
            case COBBLE:
                cobble(3 * p.getInt("stone.picks") + 2); // + a stone sword (caves: zombies, skeletons)
                break;
            case STONE_PICK:
                // a spare pickaxe now saves a table later (one wears out after 131 blocks)
                craftStonePicks();
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
        if (target != null && woodMining && mem.get(target) != null && Blocks2.pass(mem.get(target)) != Blocks2.Pass.SOLID) {
            logMined(target); // broken (seen gone before the mining call could say so)
            target = null;
        }
        woodMining = false;
        if (target == null && collectWoodDrops(f)) {
            return;
        }
        if (target == null || !(isLog(target) || isPlanks(target)) || badTargets.contains(target.asLong())) {
            target = mem.nearest(WorldMemory.Kind.LOG, f, 160, l -> !badTargets.contains(l) && BlockPos.unpackLongY(l) <= f.getY() + 6);
            if (target == null || target.getSquaredDistance(f) > 24 * 24) {
                // planks of a mineshaft, a wreck or a house are wood too (and closer underground)
                BlockPos pl = mem.nearest(WorldMemory.Kind.WOODWORK, f, 32,
                        l -> !badTargets.contains(l) && isPlanks(BlockPos.fromLong(l)) && exposed(BlockPos.fromLong(l)));
                if (pl != null && (target == null || pl.getSquaredDistance(f) < target.getSquaredDistance(f))) {
                    target = pl;
                }
            }
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
            logMined(target);
            target = null; // next log
        } else {
            woodMining = true;
        }
    }

    private boolean woodMining;

    /** The drop falls to the ground under the log: remember where (it is often out of view later). */
    private void logMined(BlockPos log) {
        BlockPos d = log;
        for (int k = 0; k < 8; k++) {
            BlockState below = mem.get(d.down());
            if (below == null || Blocks2.standable(below) && !isLog(d.down())) {
                break;
            }
            d = d.down();
        }
        woodDrops.put(-(++dropIds), new Vec3d(d.getX() + 0.5, d.getY(), d.getZ() + 0.5));
        woodDropSeen.put(-dropIds, now());
        trace("log mined; its drop expected at " + d + " (" + woodDrops.size() + " waiting)");
    }

    /** Log drops seen lying around (item entities in view), remembered for a while once out of view. */
    private final java.util.Map<Integer, Vec3d> woodDrops = new java.util.HashMap<>();
    private final java.util.Map<Integer, Long> woodDropSeen = new java.util.HashMap<>();
    private int dropWalkTicks;
    private int dropIds;

    /**
     * Picks up the logs mined so far once no more log is in reach from where we stand (the drops fall to the trunk's
     * foot, out of the pickup range of a player mining from 4 blocks away; runs walked off to the next tree without them).
     */
    private boolean collectWoodDrops(BlockPos f) {
        for (net.minecraft.entity.ItemEntity it : perception.visible(net.minecraft.entity.ItemEntity.class, 12,
                e -> e.getStack().getItem().isIn(ItemTags.LOGS))) {
            woodDrops.put(it.getEntityId(), it.getPos());
            woodDropSeen.put(it.getEntityId(), now());
        }
        Vec3d me = s.pos();
        woodDrops.entrySet().removeIf(e -> now() - woodDropSeen.get(e.getKey()) > 400 || e.getValue().squaredDistanceTo(me) < 1.0);
        if (woodDrops.isEmpty()) {
            dropWalkTicks = 0;
            return false;
        }
        BlockPos next = mem.nearest(WorldMemory.Kind.LOG, f, 6, l -> !badTargets.contains(l) && BlockPos.unpackLongY(l) <= f.getY() + 6);
        if (next != null && Navigator.reach(next, 4.2).done(f.getX(), f.getY(), f.getZ()) && woodDrops.size() < 3) {
            return false; // more logs from here first (three drops waiting: get them, they are a few steps away)
        }
        if (++dropWalkTicks > 160) {
            woodDrops.clear(); // could not get there (a drop on a ledge, in leaves)
            dropWalkTicks = 0;
            return false;
        }
        Vec3d best = null;
        for (Vec3d v : woodDrops.values()) {
            if (best == null || v.squaredDistanceTo(me) < best.squaredDistanceTo(me)) {
                best = v;
            }
        }
        BlockPos at = new BlockPos(best);
        if (!act.equals("log drops")) {
            trace("picking up " + woodDrops.size() + " log drops, nearest at " + at);
        }
        act = "log drops";
        if (mover.go(Navigator.near(at, 0.5)) == Mover.Status.FAILED) {
            woodDrops.values().remove(best);
        }
        return true;
    }

    private boolean isPlanks(BlockPos p) {
        BlockState st = mem.get(p);
        return st != null && st.isIn(net.minecraft.tag.BlockTags.PLANKS);
    }

    private boolean isLog(BlockPos p) {
        BlockState st = mem.get(p);
        return st != null && WorldMemory.classify(st) == WorldMemory.Kind.LOG;
    }

    /** Walk somewhere new, looking around, to discover trees / water / lava. */
    private float exploreYaw = Float.NaN;
    private int exploreLegs;

    private void explore() {
        act = "explore";
        BlockPos f = mover.feet();
        if (exploreTarget == null || subTicks > 300 || exploreTarget.squaredDistanceTo(Vec3d.ofCenter(f)) < 9) {
            // hold one heading (a straight line covers new ground; random turns circle the spawn), toward leaves if seen
            BlockPos leaves = goal == Goal.WOOD ? mem.nearest(WorldMemory.Kind.LEAVES, f, 160, l -> !badTargets.contains(l)) : null;
            if (leaves != null) {
                exploreYaw = yawTo(leaves);
            } else if (Float.isNaN(exploreYaw) || subTicks > 300 || ++exploreLegs % 4 == 0) {
                exploreYaw = (Float.isNaN(exploreYaw) ? s.yaw() : exploreYaw) + (rng.nextFloat() * 120 - 60) + (subTicks > 300 ? 90 : 0);
            }
            double[] d = PerceptionRules.direction(exploreYaw + (rng.nextFloat() * 30 - 15), 0);
            exploreTarget = new Vec3d(f.getX() + d[0] * 32, f.getY(), f.getZ() + d[2] * 32);
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
        if (!bench.busy() && !name.equals("stone_pickaxe") && inv.count(Items.STONE_PICKAXE) + inv.count(Items.IRON_PICKAXE) < 2
                && inv.cobble() >= 3 + (name.equals("furnace") ? 8 : 0) && (inv.count(Items.STICK) >= 2 || inv.planks() >= 6)) {
            // a spare pickaxe while the table is open anyway (one wears out after 131 blocks, deep underground)
            needs.add(new Workbench.Need("spare_pickaxe", i -> i == Items.STONE_PICKAXE, inv.count(Items.STONE_PICKAXE) + 1, true));
        }
        if (bench.run(needs) == Actions.Status.FAILED && bench.error() != null && bench.error().startsWith("no_")) {
            craftFailures++;
            if (bench.error().contains("wood") && name.equals("flint_and_steel") && p.get("route.flintLate") > 0 && !portal.waitingForLighter()) {
                // the lighter is only needed once the frame stands: no trip back to the trees for it now (a run went
                // from y 50 back to the surface for wood after its table was lost)
                lighterDeferred = true;
                trace("no wood for a table: the lighter waits for the frame");
                return;
            }
            if (bench.error().contains("wood") && woodRefills < 3) {
                // out of planks for a table: fetch wood (trees, or planks of a mineshaft / wreck seen underground)
                woodRefills++;
                needWood = true;
                trace("out of wood for " + name + ": fetching more");
                return;
            }
            if (craftFailures > 3) {
                fail("craft_" + name + "_" + bench.error());
            }
        }
    }

    /** Stone pickaxes, and a stone sword in the same table session when the cobble is there (2 more blocks). */
    private void craftStonePicks() {
        int picks = inv.cobble() >= 6 ? p.getInt("stone.picks") : 1;
        if (!bench.busy() && !inv.has(Items.STONE_SWORD) && inv.cobble() >= 3 * picks + 2 && !swordTried) {
            java.util.List<Workbench.Need> needs = new java.util.ArrayList<>();
            needs.add(new Workbench.Need("stone_pickaxe", st -> st == Items.STONE_PICKAXE, picks, true));
            needs.add(new Workbench.Need("stone_sword", st -> st == Items.STONE_SWORD, 1, true));
            swordSession = true;
            if (bench.run(needs) == Actions.Status.FAILED) {
                swordTried = true; // no sword then; the pickaxes alone below
            }
            return;
        }
        if (swordSession && bench.busy()) {
            bench.run(null);
            return;
        }
        craftAtTable(st -> st == Items.STONE_PICKAXE, picks, "stone_pickaxe");
    }

    private boolean swordTried;
    private boolean swordSession;

    private boolean needWood;
    private int woodRefills;

    private int craftFailures;
    private boolean lighterDeferred;

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
            if (s.inWater() || mem.get(f.down()) != null && Blocks2.isWater(mem.get(f.down()))) {
                act = "out of the water";
                if (mover.go(Navigator.dry(f, 24, mem)) != Mover.Status.FAILED) {
                    return;
                }
            }
            descend();
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
        act = "stairs";
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
            if (ownBlock(cell)) {
                turn(); // never dig through our own furnace (a smelting furnace broke and dropped the ore)
                return;
            }
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

    private int rimTicks;
    private long noStraightUntil;
    private long forceShaftUntil;
    private int wetTicks;
    private BlockPos lavaDescentStart;
    private int lavaDescentTicks;

    /**
     * Digs the block under the feet (1x1 shaft, the fastest way down) while staying in the middle of the cell; turns to
     * the staircase for a while when fluid is seen below or next to the shaft.
     */
    private void digStraightDown() {
        act = "shaft";
        if (collect() || movingToDryLand()) {
            return;
        }
        BlockPos f = mover.feet();
        BlockPos below = f.down();
        BlockState st = mem.get(below);
        Vec3d pos = s.pos();
        double cx = f.getX() + 0.5 - pos.x, cz = f.getZ() + 0.5 - pos.z;
        if (st != null && (Blocks2.isLava(st) || Blocks2.isWater(st) || a.lavaNextTo(below) || Blocks2.pass(st) == Blocks2.Pass.FORBID)) {
            boolean lava = Blocks2.isLava(st) || a.lavaNextTo(below) || Blocks2.pass(st) == Blocks2.Pass.FORBID;
            if (!lava && f.getY() >= spawn.getY() - 12) {
                // a lake or the sea at the surface: walk away from the water and start the shaft on dry land
                moveAwayFromWater(f);
                return;
            }
            trace((lava ? "lava" : "water") + " below the shaft at " + below + ": staircase for a while");
            noStraightUntil = now() + (lava ? 2400 : 300);
            turn();
            return;
        }
        if (st != null && Blocks2.pass(st) != Blocks2.Pass.SOLID) {
            // open below (falling, or a cave): look down while it happens
            c.lookAngles(s.yaw(), 90f);
            if (s.onGround()) {
                // standing on the hole's rim: how deep is it?
                int depth = 0;
                BlockPos q = below;
                while (depth < 6) {
                    BlockState qs = mem.get(q);
                    if (qs == null) {
                        depth = 99; // not seen yet: keep looking down
                        break;
                    }
                    if (Blocks2.standable(qs)) {
                        break;
                    }
                    if (Blocks2.isLava(qs)) {
                        depth = 100;
                        break;
                    }
                    depth++;
                    q = q.down();
                }
                if (depth >= 4 && depth != 99) {
                    trace("drop of " + depth + " under the shaft at " + below + ": staircase / cave from here");
                    noStraightUntil = now() + 1200;
                    turn();
                } else if (depth < 4) {
                    // a shallow hole: step to the cell centre so we drop into it
                    float yaw = InputLimits.yawTo(cx, cz);
                    c.lookAngles(yaw, 90f);
                    c.move(0.3f, 0f, false, false, true);
                    if (++rimTicks > 100) {
                        rimTicks = 0;
                        noStraightUntil = now() + 400;
                        turn();
                    }
                }
            }
            return;
        }
        rimTicks = 0;
        if (Math.abs(cx) > 0.2 || Math.abs(cz) > 0.2) {
            float yaw = InputLimits.yawTo(cx, cz);
            c.lookAngles(yaw, 60f);
            if (Math.abs(InputLimits.wrap(yaw - s.yaw())) < 30) {
                c.move(0.3f, 0f, false, false, true);
            }
            return;
        }
        if (!s.onGround()) {
            return;
        }
        if (a.mine(below, false) == Actions.Status.FAILED) {
            noStraightUntil = now() + 400;
        }
    }

    /** One step down: the 1x1 shaft unless lava was seen close by or the shaft just failed here (then the staircase). */
    private void descend() {
        BlockPos f = mover.feet();
        if (now() > noStraightUntil && !lavaSeenNear(f, 10)) {
            digStraightDown();
        } else {
            stairStep();
        }
    }

    /** Lava (a source, or flowing lava touching a remembered cell) seen within r blocks. */
    private boolean lavaSeenNear(BlockPos f, double r) {
        if (mem.nearest(WorldMemory.Kind.LAVA_SOURCE, f, r, null) != null) {
            return true;
        }
        for (BlockPos q : new BlockPos[]{f.down(2), f.down(3), f.down().north(), f.down().south(), f.down().east(), f.down().west()}) {
            BlockState st = mem.get(q);
            if (st != null && Blocks2.isLava(st)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos dryTarget;
    private int dryTicks;
    private int dryMoves;

    /** Picks a spot about 12 blocks away from the water seen around f (the opposite side of its centroid). */
    private void moveAwayFromWater(BlockPos f) {
        double wx = 0, wz = 0;
        int n = 0;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockState st = mem.get(f.getX() + dx, f.getY() + dy, f.getZ() + dz);
                    if (st != null && Blocks2.isWater(st)) {
                        wx += dx;
                        wz += dz;
                        n++;
                    }
                }
            }
        }
        double ang = n == 0 ? rng.nextDouble() * Math.PI * 2 : Math.atan2(-wz / n, -wx / n) + (rng.nextDouble() - 0.5) * (dryMoves > 1 ? 2.0 : 0.6);
        int dist = 10 + 4 * Math.min(dryMoves, 4);
        dryTarget = new BlockPos(f.getX() + Math.round(Math.cos(ang) * dist), f.getY(), f.getZ() + Math.round(Math.sin(ang) * dist));
        dryTicks = 300;
        dryMoves++;
        trace("water under the shaft at the surface: moving to dry land at " + dryTarget);
    }

    /** True while walking to the dry spot picked by {@link #moveAwayFromWater}. */
    private boolean movingToDryLand() {
        if (dryTarget == null || dryTicks <= 0) {
            return false;
        }
        dryTicks--;
        act = "to dry land";
        Mover.Status st = mover.go(Navigator.dry(dryTarget, 4, mem));
        if (st != Mover.Status.RUNNING) {
            dryTicks = 0;
            dryTarget = null;
            mover.reset();
        }
        return dryTicks > 0;
    }

    private void lookAhead() {
        // keep the eyes on the work face, slightly down, so new rock and ores are seen
        if (subTicks % 30 < 6) {
            c.lookAngles(s.yaw() + (subTicks % 60 < 30 ? 60f : -60f), 10f);
        }
    }

    /** One step of a 1x2 tunnel in digDir at the current height. */
    private void tunnelStep() {
        act = "tunnel";
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
            if (inv.throwaways() > 0 && Blocks2.pass(floor) == Blocks2.Pass.PASS && ++holeTicks < 60) {
                if (a.place(col.down(), Actions::isThrowaway) == Actions.Status.FAILED) {
                    holeTicks = 60;
                }
                return;
            }
            holeTicks = 0;
            turn();
            return;
        }
        for (BlockPos cell : cells) {
            if (ownBlock(cell)) {
                turn(); // never dig through our own furnace (a smelting furnace broke and dropped the ore)
                return;
            }
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

    /** One step of a staircase up in digDir (clear the head room, the step and the space above it, then step up). */
    private void stairUpStep() {
        act = "stairs up";
        if (collect() || escaping()) {
            return;
        }
        BlockPos f = mover.feet();
        BlockPos col = f.offset(digDir);
        BlockPos[] cells = {f.up(2), col.up(2), col.up()};
        for (BlockPos cell : new BlockPos[]{f.up(2), f.up(3), col.up(), col.up(2), col.up(3)}) {
            BlockState st = mem.get(cell);
            if (st != null && (Blocks2.isLava(st) || Blocks2.isWater(st) || Blocks2.pass(st) == Blocks2.Pass.FORBID || Blocks2.falls(st))) {
                turn();
                return;
            }
        }
        BlockState step = mem.get(col);
        if (step != null && !Blocks2.standable(step)) {
            // nothing to step onto: put a block there (or turn)
            if (inv.throwaways() > 0 && Blocks2.pass(step) == Blocks2.Pass.PASS && ++holeTicks < 60) {
                if (a.place(col, Actions::isThrowaway) == Actions.Status.FAILED) {
                    holeTicks = 60;
                }
                return;
            }
            holeTicks = 0;
            turn();
            return;
        }
        for (BlockPos cell : cells) {
            if (ownBlock(cell)) {
                turn(); // never dig through our own furnace (a smelting furnace broke and dropped the ore)
                return;
            }
            if (a.solid(cell) || mem.get(cell) == null) {
                if (a.lavaNextTo(cell)) {
                    turn(); // opening it would let the lava in
                    return;
                }
                Actions.Status st = a.mine(cell, false);
                if (st == Actions.Status.FAILED) {
                    turn();
                }
                if (st != Actions.Status.DONE) {
                    return;
                }
            }
        }
        mover.go(Navigator.at(col.up()));
        lookAhead();
    }

    private boolean ownBlock(BlockPos q) {
        BlockState st = mem.get(q);
        return st != null && (st.getBlock() == Blocks.FURNACE || (st.getBlock() == Blocks.CRAFTING_TABLE && bench.busy()));
    }

    private int heardMisses;
    private int probeUntilLen;
    private long probeUntilTick;
    private long lastTurnTick;
    private int holeTicks;
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
        if (s.inWater() && ++wetTicks > 10) {
            // swimming: no way to dig from here; get onto dry ground first
            act = "out of the water";
            if (mover.go(Navigator.dry(f, 16, mem)) != Mover.Status.FAILED) {
                return;
            }
        } else if (!s.inWater()) {
            wetTicks = 0;
        }
        int detour = p.getInt("dig.oreDetour");
        if (ironTotal() < ironNeeded()) {
            BlockPos ore = nearestBlock(f, detour, st -> st.getBlock() == Blocks.IRON_ORE);
            if (ore == null) {
                // ore seen further away (a cave wall, a cliff): worth a walk when it is in the open
                ore = mem.nearest(WorldMemory.Kind.IRON_ORE, f, p.get("iron.seenDist"),
                        l -> !badTargets.contains(l) && exposed(BlockPos.fromLong(l)));
            }
            if (ore != null) {
                target = ore;
                subTicks = 0;
                caveTarget = null;
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
        // gravel blobs are spread through the stone at every height: tunnel where we are instead of digging down
        int targetY = why.equals("iron") ? p.getInt("dig.ironY") : why.equals("gravel") ? Math.max(20, Math.min(f.getY(), spawn.getY() - 8)) : p.getInt("dig.targetY");
        if (why.equals("lava") && lavaDescentStart == null && f.getY() >= spawn.getY() - 6) {
            // do not start the way down next to the lake the water came from: a shaft or staircase there floods
            BlockPos wtr = mem.nearest(WorldMemory.Kind.WATER_SOURCE, f, 10, null);
            if (wtr != null) {
                double dx = f.getX() - wtr.getX(), dz = f.getZ() - wtr.getZ();
                double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
                lavaDescentStart = new BlockPos(f.getX() + dx / len * 12, f.getY(), f.getZ() + dz / len * 12);
                lavaDescentTicks = 0;
                trace("moving away from the water before digging down: " + lavaDescentStart);
            } else {
                lavaDescentStart = f;
            }
        }
        if (why.equals("lava") && lavaDescentStart != null && !lavaDescentStart.equals(f) && ++lavaDescentTicks < 300
                && f.getSquaredDistance(lavaDescentStart) > 9) {
            act = "away from water";
            if (mover.go(Navigator.near(lavaDescentStart, 2)) == Mover.Status.RUNNING) {
                return;
            }
        }
        if (why.equals("lava")) {
            BlockPos known = portal.chooseLava();
            if (known != null) {
                // lava seen: head for it (down first through a shaft / staircase toward it, then across)
                if (f.getY() > known.getY() + 3) {
                    targetY = known.getY() + 1;
                    int dx = known.getX() - f.getX(), dz = known.getZ() - f.getZ();
                    Direction toward = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                    if (digDir != toward && now() - lastTurnTick > 100) {
                        digDir = toward;
                    }
                } else if (known.getSquaredDistance(f) > 6 * 6) {
                    act = "approach lava " + known;
                    if (mover.go(Navigator.near(known, 5)) == Mover.Status.FAILED) {
                        badTargets.add(known.asLong());
                    }
                    return;
                }
            }
        }
        boolean probing = why.equals("lava") && tunnelLen < probeUntilLen && now() < probeUntilTick;
        if (probing && f.getY() <= targetY + 8) {
            tunnelStep();
            return;
        }
        if (why.equals("lava") && portal.chooseLava() == null && lavaHeardAt != null && p.get("lava.hearing") > 0) {
            // not seen but heard: turn the tunnel toward the sound (the tunnel's own checks keep it out of lava:
            // digging straight at the sound through unknown rock opened lava onto the bot). Close to the guess
            // without seeing anything, the guess is spent: look around and listen again.
            double dx = lavaHeardAt.x - f.getX() - 0.5, dz = lavaHeardAt.z - f.getZ() - 0.5;
            double dyHeard = lavaHeardAt.y - f.getY();
            BlockPos hb = new BlockPos(lavaHeardAt);
            if (nearestBlock(hb, 4, Blocks2::isLava) != null || (dx * dx + dz * dz < 64 && nearestBlock(f, 5, Blocks2::isLava) != null)) {
                // the sound comes from lava in view that is no scoopable pool (a fall, a spring's flow)
                trace("heard lava around " + hb + " is lava already seen (no pool there): listening elsewhere");
                lavaExplained.add(lavaHeardAt);
                lavaHeard.clear();
                lavaHeardAt = null;
                return;
            }
            if (dyHeard < -2.5 && f.getY() > 11 && dx * dx + dz * dz < 12 * 12) {
                // the lava is below (a cave floor at y 10 under the tunnel): a staircase down toward it; walking on
                // over the spot ("reached") kept a run circling above heard lava for three minutes
                // a cave going down there is quicker (and the staircase refuses drops into one)
                if (exploreCave(f, (int) Math.round(lavaHeardAt.y) + 1, f.getY()) && caveTarget != null && caveTarget.getY() < f.getY()) {
                    return;
                }
                Direction toward = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                // a turn the staircase made itself (fluid, drop ahead) stands for a while: turning straight back
                // toward the sound each time kept a run stepping in place
                if (dx * dx + dz * dz >= 4 && digDir != toward && now() - lastTurnTick > 300) {
                    digDir = toward;
                    lastTurnTick = now();
                    trace("stairs down toward the lava heard around " + new BlockPos(lavaHeardAt));
                }
                stairStep();
                return;
            }
            if (dyHeard > 2.5 && f.getY() < 50) {
                // the lava is above the tunnel (a pool higher up in the rock): climb toward its level, a tunnel at
                // the lava level never sees it (runs heard lava at y 18 for minutes from a tunnel at y 12)
                if (dx * dx + dz * dz >= 4) {
                    Direction toward = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                    if (digDir != toward && now() - lastTurnTick > 60) {
                        digDir = toward;
                        trace("climbing toward the lava heard around " + new BlockPos(lavaHeardAt));
                    }
                }
                if (exploreCave(f, f.getY() + 1, (int) Math.round(lavaHeardAt.y) + 3) && caveTarget != null && caveTarget.getY() > f.getY()) {
                    return;
                }
                stairUpStep();
                return;
            }
            if (dx * dx + dz * dz < 9) {
                // the guess is rough along the line of hearing (distance by loudness): go on through it for a few
                // blocks (each mined face shows what is behind) before the guess is spent
                trace("heard lava spot " + new BlockPos(lavaHeardAt) + " reached without seeing lava: probing on " + digDir);
                if (++heardMisses >= 4) {
                    // the same sound reached again and again without seeing anything: stop following it here
                    trace("heard lava around " + new BlockPos(lavaHeardAt) + " missed " + heardMisses + " times: listening elsewhere");
                    lavaExplained.add(lavaHeardAt);
                    heardMisses = 0;
                }
                lavaHeard.clear();
                lavaHeardAt = null;
                probeUntilLen = tunnelLen + 5;
                probeUntilTick = now() + 300;
            } else {
                Direction toward = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                if (digDir != toward && now() - lastTurnTick > 60) {
                    digDir = toward;
                    trace("tunnel toward the lava heard around " + new BlockPos(lavaHeardAt));
                }
            }
        }
        if (route == RouteSelector.Route.SURFACE_CAST && why.equals("iron")) {
            targetY = Math.max(targetY, 40);
        }
        if (exploreCave(f, targetY, why.equals("lava") ? targetY + p.getInt("cave.lavaAbove") : Integer.MAX_VALUE)) {
            return;
        }
        // with the water bucket in hand (the lava goal) the shaft goes all the way to the lava level: the bucket puts
        // out a fall into lava; without it the shaft stops at dig.straightMinY and a staircase goes on
        int shaftMin = why.equals("lava") && inv.has(Items.WATER_BUCKET) ? p.getInt("lava.shaftMinY") : p.getInt("dig.straightMinY");
        if (f.getY() > targetY && p.get("dig.straightDown") > 0 && now() > noStraightUntil
                && (f.getY() > Math.max(targetY, shaftMin) || now() < forceShaftUntil || !lavaSeenNear(f, 10))) {
            digStraightDown();
        } else if (f.getY() > targetY) {
            stairStep();
        } else {
            tunnelStep();
            if (tunnelLen > p.getInt("dig.tunnelMax")) {
                fail("tunnel_exhausted_" + why);
            }
        }
    }

    // ------------------------------------------------------------------ caves

    private BlockPos caveTarget;
    private int caveTicks;
    private int lookAround;
    private long lastCaveSearch = -1000;
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet caveVisited = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    private io.github.sx2xoox.speedrunbot.core.Frontier frontier() {
        io.github.sx2xoox.speedrunbot.core.Frontier.Weights w = new io.github.sx2xoox.speedrunbot.core.Frontier.Weights();
        w.radius = p.getInt("cave.radius");
        w.perDepth = p.get("cave.perDepth");
        w.perUnknown = p.get("cave.perUnknown");
        return new io.github.sx2xoox.speedrunbot.core.Frontier((x, y, z) -> {
            BlockState st = mem.get(x, y, z);
            if (st == null) {
                return io.github.sx2xoox.speedrunbot.core.Frontier.Seen.UNKNOWN;
            }
            if (st.isAir() && a.dug.contains(io.github.sx2xoox.speedrunbot.core.Cell.pack(x, y, z))) {
                // our own tunnel: no cave (a tunnel next to a flooded or older tunnel looked like a cave and the bot
                // walked its own dead ends three blocks at a time)
                return io.github.sx2xoox.speedrunbot.core.Frontier.Seen.SOLID;
            }
            if (Blocks2.isLava(st)) {
                return io.github.sx2xoox.speedrunbot.core.Frontier.Seen.LAVA;
            }
            if (Blocks2.isWater(st)) {
                return io.github.sx2xoox.speedrunbot.core.Frontier.Seen.WATER;
            }
            if (st.isIn(net.minecraft.tag.BlockTags.LEAVES) || st.isIn(net.minecraft.tag.BlockTags.LOGS)) {
                return io.github.sx2xoox.speedrunbot.core.Frontier.Seen.AIR; // a canopy is no cave roof
            }
            return Blocks2.pass(st) == Blocks2.Pass.PASS ? io.github.sx2xoox.speedrunbot.core.Frontier.Seen.AIR
                    : io.github.sx2xoox.speedrunbot.core.Frontier.Seen.SOLID;
        }, w);
    }

    /**
     * Walks known caves toward their unseen parts (frontier exploration on memory) and looks around at each stop:
     * cave walls show far more ore than a tunnel's two walls. False when no cave frontier is known nearby.
     */
    private boolean exploreCave(BlockPos f, int targetY, int maxY) {
        act = "cave";
        if (lookAround > 0) {
            lookAround--;
            c.lookAngles(s.yaw() + 45f, lookAround % 8 < 4 ? 20f : -10f);
            return true;
        }
        if (caveTarget == null) {
            if (now() - lastCaveSearch < 20) {
                return false;
            }
            lastCaveSearch = now();
            // caves are under the ground: well below the height the run started at (trees and overhangs are no caves)
            io.github.sx2xoox.speedrunbot.core.Cell cell = frontier().best(f.getX(), f.getY(), f.getZ(), targetY,
                    Math.min(Math.min(spawn.getY() - p.getInt("cave.belowSurface"), 60), maxY), caveVisited::contains);
            if (cell == null) {
                return false;
            }
            caveTarget = new BlockPos(cell.x, cell.y, cell.z);
            caveTicks = 0;
            trace("cave frontier " + caveTarget);
        }
        if (++caveTicks > 400) {
            markVisited(caveTarget);
            caveTarget = null;
            return false;
        }
        Mover.Status st = mover.go(Navigator.at(caveTarget));
        if (st == Mover.Status.ARRIVED) {
            markVisited(caveTarget);
            caveTarget = null;
            lookAround = 16;
            return true;
        }
        if (st == Mover.Status.FAILED) {
            markVisited(caveTarget);
            caveTarget = null;
            return false;
        }
        return true;
    }

    private void markVisited(BlockPos q) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    caveVisited.add(io.github.sx2xoox.speedrunbot.core.Cell.pack(q.getX() + dx, q.getY() + dy, q.getZ() + dz));
                }
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
        if (gravelBreaks > p.getInt("gravel.maxBreaks") * 2 && !inv.has(Items.GRAVEL)) {
            // unlucky with this gravel: look for more (gravel is common in caves, rivers and on beaches)
            trace("no flint from " + gravelBreaks + " gravel breaks; looking for more gravel");
            gravelBreaks = 0;
        }
        BlockPos f = mover.feet();
        BlockPos gr = nearestBlock(f, 24, st -> st.getBlock() == Blocks.GRAVEL);
        if (gr == null) {
            // gravel seen further away (a beach, a river bed, a cave floor)
            gr = mem.nearest(WorldMemory.Kind.GRAVEL, f, 64, l -> !badTargets.contains(l) && exposed(BlockPos.fromLong(l)));
        }
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

    /** Crafts at a table are still ahead (bucket, flint and steel) and the wood for a new table is short. */
    boolean tableNeededLater() {
        boolean ahead = inv.buckets() == 0 || !inv.lighter();
        return ahead && inv.planks() + inv.logs() * 4 < 4 + p.getInt("craft.spareWood");
    }

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
            if ("place_furnace".equals(smelter.error())) {
                relocate(mover.feet()); // no spot for the furnace here (a run tried for six minutes): somewhere else
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
        if (inv.has(Items.BUCKET)) {
            // water right here (in it, above the head, beside us): scoop it without walking
            for (BlockPos q : new BlockPos[]{f, f.up(), f.up(2), f.down(), f.north(), f.south(), f.east(), f.west(), f.up().north(),
                    f.up().south(), f.up().east(), f.up().west()}) {
                if (isWaterSource(q)) {
                    if (a.equip(Items.BUCKET) == Actions.Status.DONE) {
                        Vec3d aim = Vec3d.ofCenter(q);
                        c.lookAt(aim);
                        net.minecraft.util.hit.HitResult h = s.fluidCrosshair();
                        if (h instanceof BlockHitResult && h.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                                && isWaterSource(((BlockHitResult) h).getBlockPos())) {
                            c.use();
                        }
                    }
                    return;
                }
            }
        }
        int surface = Math.max(spawn.getY() - 2, 60);
        if (target == null || !isWaterSource(target) || badTargets.contains(target.asLong())) {
            target = mem.nearest(WorldMemory.Kind.WATER_SOURCE, f, p.get("portal.waterSearchDist") * 3,
                    l -> !badTargets.contains(l) && exposedTop(BlockPos.fromLong(l)));
            subTicks = 0;
            if (target != null && target.getY() > f.getY() + 4) {
                climbTo = target.getY();
                target = null; // well above us: straight up first (reliable), then walk across
                climbing = true;
            }
        }
        if (climbing && f.getY() < Math.max(surface, climbTo)) {
            act = "climb to " + Math.max(surface, climbTo);
            if (mover.climb(Math.max(surface, climbTo)) != Mover.Status.RUNNING) {
                climbing = false;
            }
            return;
        }
        climbing = false;
        if (target == null) {
            // go back up to the surface (where water was seen or is likely), looking around

            if (f.getY() < surface) {
                BlockPos seenWater = mem.nearest(WorldMemory.Kind.WATER_SOURCE, f, 400, l -> !badTargets.contains(l));
                if (seenWater != null && seenWater.getSquaredDistance(f) < 160 * 160 && !climbing) {
                    if (mover.go(Navigator.near(seenWater, 4)) == Mover.Status.FAILED) {
                        climbing = true; // no way through what we know: straight up instead
                    }
                } else if (setAct("climb to surface " + surface) && mover.climb(surface) == Mover.Status.FAILED) {
                    climbing = false;
                    explore();
                }
            } else {
                explore();
            }
            return;
        }
        if (subTicks > 600) {
            badTargets.add(target.asLong());
            target = null;
            climbing = f.getY() < surface;
            return;
        }
        if (fillBucket(target, Items.WATER_BUCKET) == Actions.Status.FAILED) {
            badTargets.add(target.asLong());
            target = null;
            climbing = f.getY() < surface;
        }
    }

    boolean isWaterSource(BlockPos q) {
        BlockState st = mem.get(q);
        return st != null && Blocks2.isWaterSource(st);
    }

    /**
     * A water source the bucket can reach: air above it, or (a spring in a cave wall) air beside it while the cells
     * around it hold no more water (a lake's edge source is no spring and is taken from above).
     */
    boolean exposedTop(BlockPos q) {
        BlockState up = mem.get(q.up());
        if (up != null && up.isAir()) {
            return true;
        }
        if (p.get("water.springs") <= 0) {
            return false;
        }
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockState side = mem.get(q.offset(d));
            if (side != null && side.isAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks into reach of a fluid source and scoops it with an empty bucket (aims at the fluid surface; the bucket's
     * own ray picks the source). DONE when the filled bucket is in the inventory.
     */
    Actions.Status fillBucket(BlockPos src, Item filled) {
        act = "fill";
        int have = inv.count(filled);
        if (fillStartCount < 0) {
            fillStartCount = have;
        }
        if (have > fillStartCount) {
            fillStartCount = -1;
            return Actions.Status.DONE;
        }
        BlockPos f = mover.feet();
        Navigator.Goal g = Navigator.see(src, 4.5, mem); // the bucket's own ray reaches 5 blocks
        if (!g.done(f.getX(), f.getY(), f.getZ()) || (!s.onGround() && !s.inWater())) {
            fillAimTicks = 0;
            if (mover.go(g) == Mover.Status.FAILED) {
                trace("cannot get to a spot that sees " + src + " (" + mover.lastPlanInfo() + ")");
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
        // a source's surface is 8/9 high: aim into it, not over it (grazing rays from above missed)
        // when a block corner is in the way of the centre, try other points of the surface in turn
        double[][] pts = {{0.5, 0.6, 0.5}, {0.25, 0.8, 0.25}, {0.75, 0.8, 0.75}, {0.25, 0.8, 0.75}, {0.75, 0.8, 0.25}};
        double[] pt = pts[(fillAimTicks / 8) % pts.length];
        Vec3d aim = new Vec3d(src.getX() + pt[0], src.getY() + pt[1], src.getZ() + pt[2]);
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
            trace("bucket ray never on " + src + " from " + f + " (hit " + h.getType() + " " + (h instanceof BlockHitResult ? ((BlockHitResult) h).getBlockPos() : "") + ")");
            fillAimTicks = 0;
            fillStartCount = -1;
            return Actions.Status.FAILED; // something is in the way
        }
        return Actions.Status.RUNNING;
    }

    private int fillStartCount = -1;
    private int fillAimTicks;

    private BlockPos waterToRecover;
    private boolean climbing;
    private int climbTo;

    /** Survival poured the water bucket somewhere: scoop it back before going on. */
    void recoverWater(BlockPos at) {
        waterToRecover = at;
    }

    /** True while taking back water poured in an emergency. */
    private boolean recoveringWater() {
        if (waterToRecover == null) {
            return false;
        }
        if (inv.has(Items.WATER_BUCKET) || !inv.has(Items.BUCKET)) {
            waterToRecover = null;
            return false;
        }
        BlockPos src = mem.nearest(WorldMemory.Kind.WATER_SOURCE, waterToRecover, 3, l -> true);
        if (src == null || fillBucket(src, Items.WATER_BUCKET) != Actions.Status.RUNNING) {
            waterToRecover = null;
        }
        return true;
    }

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
