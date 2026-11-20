package io.github.sx2xoox.speedrunbot.brain;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

/**
 * Loots a chest the bot has seen: walk into reach, open it (right click), shift-click the useful items (iron, gold,
 * TNT, food, coal, the treasure map...), close. Owns the screen from open to close like the other screen sessions.
 */
public final class Looter {
    private enum Phase { IDLE, GO, OPEN, TAKE, CLOSE }

    private final RunBrain b;
    private Phase phase = Phase.IDLE;
    private BlockPos chest;
    private int phaseTicks;
    private int openFails;
    private String report = "";

    public Looter(RunBrain b) {
        this.b = b;
    }

    public boolean screenBusy() {
        return phase == Phase.OPEN || phase == Phase.TAKE || phase == Phase.CLOSE;
    }

    public boolean busy() {
        return phase != Phase.IDLE;
    }

    public BlockPos chest() {
        return chest;
    }

    public String report() {
        return report;
    }

    public static boolean wanted(ItemStack st) {
        Item i = st.getItem();
        return i == Items.IRON_INGOT || i == Items.IRON_NUGGET || i == Items.GOLD_INGOT || i == Items.GOLD_NUGGET || i == Items.TNT
                || i == Items.FILLED_MAP || i == Items.COAL || i == Items.DIAMOND || i == Items.FLINT_AND_STEEL || i == Items.FLINT
                || i == Items.OBSIDIAN || i == Items.FIRE_CHARGE || i == Items.IRON_PICKAXE || i == Items.IRON_SWORD || i == Items.IRON_AXE
                || i == Items.BUCKET || i == Items.WATER_BUCKET || i == Items.LAVA_BUCKET || i == Items.GOLDEN_APPLE
                || i == Items.ENCHANTED_GOLDEN_APPLE || i == Items.EMERALD
                || (i.isFood() && i != Items.ROTTEN_FLESH && i != Items.SUSPICIOUS_STEW && i != Items.POISONOUS_POTATO && i != Items.SPIDER_EYE);
    }

    /** Taking order: iron (tools, bucket), food, the map, flint, obsidian, then gold (bartering later) and the rest. */
    static int rank(ItemStack st) {
        Item i = st.getItem();
        if (i == Items.IRON_INGOT || i == Items.FILLED_MAP) {
            return 0;
        }
        if (i == Items.IRON_NUGGET || i == Items.IRON_PICKAXE || i == Items.BUCKET || i == Items.WATER_BUCKET || i == Items.LAVA_BUCKET
                || i == Items.FLINT_AND_STEEL) {
            return 1;
        }
        if (i.isFood()) {
            return 2;
        }
        if (i == Items.FLINT || i == Items.OBSIDIAN || i == Items.FIRE_CHARGE) {
            return 3;
        }
        if (i == Items.GOLD_INGOT || i == Items.GOLD_NUGGET) {
            return 4;
        }
        return 5;
    }

    private void go(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    /** Starts looting a seen chest; call {@link #tick()} until it reports DONE/FAILED. */
    public void start(BlockPos chestPos) {
        chest = chestPos;
        openFails = 0;
        report = "";
        go(Phase.GO);
    }

    public Actions.Status tick() {
        phaseTicks++;
        switch (phase) {
            case IDLE:
                return Actions.Status.DONE;
            case GO: {
                BlockState st = b.mem.get(chest);
                if (st != null && st.getBlock() != Blocks.CHEST && st.getBlock() != Blocks.TRAPPED_CHEST) {
                    go(Phase.IDLE);
                    return Actions.Status.FAILED;
                }
                BlockPos f = b.feet();
                if (b.s.inWater() && b.s.eyePos().squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(chest)) < 4.0 * 4.0) {
                    go(Phase.OPEN); // under water within reach: open from here (swimming to a "standing" cell drifts)
                    return Actions.Status.RUNNING;
                }
                net.minecraft.util.math.Vec3d p = b.s.pos();
                double hx = chest.getX() + 0.5 - p.x, hz = chest.getZ() + 0.5 - p.z;
                if (b.s.inWater() && Math.hypot(hx, hz) < 3 && chest.getY() < p.y) {
                    // straight above it in the water (back from a breath): sink onto it
                    b.c.lookAt(net.minecraft.util.math.Vec3d.ofCenter(chest));
                    b.c.move(Math.hypot(hx, hz) > 0.8 ? 0.5f : 0f, 0f, false, false, false);
                    b.c.sink();
                    return phaseTicks > 900 ? fail() : Actions.Status.RUNNING;
                }
                Navigator.Goal g = Navigator.reach(chest, 3.6);
                if (!g.done(f.getX(), f.getY(), f.getZ()) || (!b.s.onGround() && !b.s.inWater())) {
                    if (b.mover.go(g) == Mover.Status.FAILED || phaseTicks > 900) {
                        go(Phase.IDLE);
                        return Actions.Status.FAILED;
                    }
                    return Actions.Status.RUNNING;
                }
                go(Phase.OPEN);
                return Actions.Status.RUNNING;
            }
            case OPEN:
                if (b.s.inWater()) {
                    b.c.noFloat(); // do not hold space (it lifts us away), nor shift (shift + item in hand skips the chest)
                }
                if (b.s.screen() instanceof GenericContainerScreenHandler) {
                    go(Phase.TAKE);
                    return Actions.Status.RUNNING;
                }
                if (b.s.screenOpen()) {
                    if (!b.a.equipBusy()) {
                        b.c.closeScreen();
                    }
                    return Actions.Status.RUNNING;
                }
                if (phaseTicks > 60) {
                    if (++openFails > 2) {
                        go(Phase.IDLE);
                        return Actions.Status.FAILED;
                    }
                    // the crosshair cannot reach it: mine what is in the way (above first) by walking closer
                    go(Phase.GO);
                    return Actions.Status.RUNNING;
                }
                if (b.a.equip(Smelter.nonBlock()) == Actions.Status.RUNNING) {
                    return Actions.Status.RUNNING;
                }
                if (b.a.useBlock(chest) == Actions.Status.DONE) {
                    b.c.screenOpened();
                }
                return Actions.Status.RUNNING;
            case TAKE: {
                if (!(b.s.screen() instanceof GenericContainerScreenHandler)) {
                    go(Phase.OPEN);
                    return Actions.Status.RUNNING;
                }
                if (!b.c.guiReady()) {
                    return Actions.Status.RUNNING;
                }
                GenericContainerScreenHandler h = (GenericContainerScreenHandler) b.s.screen();
                int n = h.getInventory().size();
                // the most useful first (the screen may have to close early: air under water, a mob)
                int best = -1, bestRank = Integer.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    ItemStack st = h.getSlot(i).getStack();
                    if (!st.isEmpty() && wanted(st) && rank(st) < bestRank) {
                        bestRank = rank(st);
                        best = i;
                    }
                }
                if (best >= 0) {
                    ItemStack st = h.getSlot(best).getStack();
                    String what = st.getCount() + " " + net.minecraft.util.registry.Registry.ITEM.getId(st.getItem()).getPath() + ", ";
                    if (b.c.click(best, 0, SlotActionType.QUICK_MOVE)) {
                        report += what;
                    }
                    return Actions.Status.RUNNING;
                }
                go(Phase.CLOSE);
                return Actions.Status.RUNNING;
            }
            case CLOSE:
                if (b.s.screenOpen()) {
                    b.c.closeScreen();
                    return Actions.Status.RUNNING;
                }
                b.trace("looted chest at " + chest + ": " + report);
                b.badTargets().add(chest.asLong());
                go(Phase.IDLE);
                return Actions.Status.DONE;
            default:
                return Actions.Status.RUNNING;
        }
    }

    private Actions.Status fail() {
        go(Phase.IDLE);
        return Actions.Status.FAILED;
    }

    public void interrupt() {
        if (screenBusy()) {
            if (b.s.screenOpen()) {
                b.c.closeScreen();
            }
            go(Phase.GO);
        }
    }
}
