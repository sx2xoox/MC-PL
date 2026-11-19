package io.github.sx2xoox.speedrunbot.brain;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.function.Predicate;

/**
 * Smelting as one explicit state machine that owns the furnace screen from opening to closing: place (or find) the
 * furnace, open it, put the ore in, put exactly enough fuel in (coal first; planks only above the wood the remaining
 * crafts need), close, wait elsewhere, come back, open, take the ingots (and any fuel / ore left), close. Between
 * open and close nothing else may touch the screen ({@link #screenBusy()}); RunBrain runs only this then.
 */
public final class Smelter {
    public enum Phase { IDLE, PLACE, GO, OPEN, LOAD, CLOSE_LOADED, SMELTING, GO_COLLECT, OPEN_COLLECT, COLLECT, CLOSE_DONE }

    private final RunBrain b;
    private Phase phase = Phase.IDLE;
    private BlockPos furnace;
    private long readyTick = -1;
    private int loaded;
    private int phaseTicks;
    private int openFails;
    private int fuelClicks;
    private int heldFrom = -1;
    private String error;
    private BlockPos standOverride;
    private int collectWaits;
    private double burnedUnits;

    public Smelter(RunBrain b) {
        this.b = b;
    }

    public Phase phase() {
        return phase;
    }

    /** A furnace screen interaction is in progress: nothing else may click or close. */
    public boolean screenBusy() {
        return phase == Phase.OPEN || phase == Phase.LOAD || phase == Phase.CLOSE_LOADED || phase == Phase.OPEN_COLLECT
                || phase == Phase.COLLECT || phase == Phase.CLOSE_DONE;
    }

    /** Ore is in the furnace (the bot may do other things until it is ready). */
    public boolean smelting() {
        return phase == Phase.SMELTING || phase == Phase.GO_COLLECT || phase == Phase.OPEN_COLLECT || phase == Phase.COLLECT;
    }

    public boolean ready() {
        return smelting() && b.now() >= readyTick;
    }

    public int loaded() {
        return smelting() ? loaded : 0;
    }

    public BlockPos furnace() {
        return furnace;
    }

    public String error() {
        return error;
    }

    private void go(Phase p) {
        if (p != phase) {
            b.trace("smelter " + phase + " -> " + p);
        }
        phase = p;
        phaseTicks = 0;
    }

    /** Starts (or continues) a smelt of all carried iron ore. RUNNING while loading, DONE once loaded or collected. */
    public Actions.Status tick() {
        phaseTicks++;
        switch (phase) {
            case IDLE:
                error = null;
                if (b.inv().count(Items.IRON_ORE) == 0) {
                    return Actions.Status.DONE;
                }
                go(furnace != null && isFurnace(furnace) ? Phase.GO : Phase.PLACE);
                return Actions.Status.RUNNING;
            case PLACE:
                return place();
            case GO:
            case GO_COLLECT:
                return walk();
            case OPEN:
            case OPEN_COLLECT:
                return open();
            case LOAD:
                return load();
            case CLOSE_LOADED:
                if (close()) {
                    go(Phase.SMELTING);
                }
                return Actions.Status.RUNNING;
            case SMELTING:
                return Actions.Status.DONE;
            case COLLECT:
                return collect();
            case CLOSE_DONE:
                if (close()) {
                    furnace = furnace; // stays usable for a later smelt
                    go(Phase.IDLE);
                    b.split("iron_ingots");
                    return Actions.Status.DONE;
                }
                return Actions.Status.RUNNING;
            default:
                return Actions.Status.RUNNING;
        }
    }

    /** Goes back to the furnace and takes the ingots (call when {@link #ready()}). */
    public Actions.Status collectTick() {
        if (phase == Phase.SMELTING) {
            go(Phase.GO_COLLECT);
        }
        return tick();
    }

    private boolean isFurnace(BlockPos p) {
        BlockState st = b.mem.get(p);
        return st != null && st.getBlock() == Blocks.FURNACE;
    }

    private Actions.Status place() {
        if (!b.inv().has(Items.FURNACE)) {
            BlockPos known = b.mem.nearest(WorldMemory.Kind.FURNACE, b.feet(), 8, l -> !b.badTargets().contains(l));
            if (known != null) {
                furnace = known;
                go(Phase.GO);
                return Actions.Status.RUNNING;
            }
            error = "no_furnace";
            go(Phase.IDLE);
            return Actions.Status.FAILED;
        }
        if (b.s.screenOpen()) {
            b.c.closeScreen();
            return Actions.Status.RUNNING;
        }
        BlockPos placed = b.placeNear(st -> st.getItem() == Items.FURNACE, Blocks.FURNACE);
        if (placed != null) {
            furnace = placed;
            standOverride = null;
            b.trace("furnace at " + placed);
            go(Phase.GO);
        } else if (phaseTicks > 300) {
            error = "place_furnace";
            go(Phase.IDLE);
            return Actions.Status.FAILED;
        }
        return Actions.Status.RUNNING;
    }

    private Actions.Status walk() {
        if (!isFurnace(furnace) && b.mem.get(furnace) != null) {
            b.trace("furnace at " + furnace + " is gone");
            furnace = null;
            go(b.inv().has(Items.FURNACE) ? Phase.PLACE : Phase.IDLE);
            return Actions.Status.RUNNING;
        }
        BlockPos f = b.feet();
        Navigator.Goal g = standOverride != null ? Navigator.at(standOverride) : Navigator.reach(furnace, 3.6);
        if (!g.done(f.getX(), f.getY(), f.getZ()) || !b.s.onGround()) {
            if (b.mover.go(g) == Mover.Status.FAILED || phaseTicks > 600) {
                if (standOverride != null) {
                    standOverride = null;
                    phaseTicks = 0;
                    return Actions.Status.RUNNING;
                }
                error = "furnace_unreachable";
                b.badTargets().add(furnace.asLong());
                furnace = null;
                go(b.inv().has(Items.FURNACE) ? Phase.PLACE : Phase.IDLE);
            }
            return Actions.Status.RUNNING;
        }
        go(phase == Phase.GO ? Phase.OPEN : Phase.OPEN_COLLECT);
        return Actions.Status.RUNNING;
    }

    private Actions.Status open() {
        if (b.s.screen() instanceof AbstractFurnaceScreenHandler) {
            openFails = 0;
            fuelClicks = 0;
            collectWaits = 0;
            AbstractFurnaceScreenHandler h0 = (AbstractFurnaceScreenHandler) b.s.screen();
            burnedUnits = fuelItems(h0.getSlot(1).getStack());
            go(phase == Phase.OPEN ? Phase.LOAD : Phase.COLLECT);
            return Actions.Status.RUNNING;
        }
        if (b.s.screenOpen()) {
            if (!b.a.equipBusy()) {
                b.c.closeScreen();
            }
            return Actions.Status.RUNNING;
        }
        if (phaseTicks > 40) {
            // the crosshair cannot reach it from here: stand right next to it (same level) and try again
            openFails++;
            b.trace("cannot open the furnace at " + furnace + " from " + b.feet() + " (try " + openFails + ")");
            standOverride = openFails <= 4 ? standCell(openFails) : null;
            if (openFails > 4) {
                b.badTargets().add(furnace.asLong());
                furnace = null;
                error = "furnace_open";
                go(b.inv().has(Items.FURNACE) ? Phase.PLACE : Phase.IDLE);
                return Actions.Status.RUNNING;
            }
            go(phase == Phase.OPEN ? Phase.GO : Phase.GO_COLLECT);
            return Actions.Status.RUNNING;
        }
        if (b.a.equip(st -> !st.isEmpty() && !(st.getItem() instanceof net.minecraft.item.BlockItem)) == Actions.Status.RUNNING) {
            return Actions.Status.RUNNING;
        }
        if (b.a.useBlock(furnace) == Actions.Status.DONE) {
            b.c.screenOpened();
        }
        return Actions.Status.RUNNING;
    }

    /** A free cell next to the furnace (feet at the furnace's level) to stand in for opening it. */
    private BlockPos standCell(int attempt) {
        Direction[] dirs = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        int found = 0;
        for (Direction d : dirs) {
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos q = furnace.offset(d).down(-dy == 0 ? 0 : 1);
                BlockState here = b.mem.get(q), head = b.mem.get(q.up()), floor = b.mem.get(q.down());
                if (here != null && head != null && floor != null && Blocks2.pass(here) == Blocks2.Pass.PASS
                        && Blocks2.pass(head) == Blocks2.Pass.PASS && Blocks2.standable(floor)) {
                    if (++found == attempt) {
                        return q;
                    }
                }
            }
        }
        return null;
    }

    private AbstractFurnaceScreenHandler handler() {
        return b.s.screen() instanceof AbstractFurnaceScreenHandler ? (AbstractFurnaceScreenHandler) b.s.screen() : null;
    }

    private Actions.Status load() {
        AbstractFurnaceScreenHandler h = handler();
        if (h == null) {
            go(Phase.OPEN); // closed under us
            return Actions.Status.RUNNING;
        }
        if (!b.c.guiReady()) {
            return Actions.Status.RUNNING;
        }
        ItemStack cur = b.s.inventory().getCursorStack();
        if (!cur.isEmpty()) {
            // holding a fuel stack: one right click per unit of fuel needed, then put the rest back
            ItemStack inSlot = h.getSlot(1).getStack();
            int want = h.getSlot(0).getStack().getCount();
            if (burnedUnits + 1e-6 < want && (inSlot.isEmpty() || ItemStack.areItemsEqual(inSlot, cur))) {
                double per = fuelItems(new ItemStack(cur.getItem(), 1));
                if (b.c.click(1, 1, SlotActionType.PICKUP)) {
                    burnedUnits += per;
                }
                return Actions.Status.RUNNING;
            }
            b.c.click(heldFrom >= 0 ? heldFrom : b.a.screenSlotOfInv(firstEmptyInv()), 0, SlotActionType.PICKUP);
            return Actions.Status.RUNNING;
        }
        int ore = b.screenSlotOf(st -> st.getItem() == Items.IRON_ORE);
        if (ore >= 0) {
            b.c.click(ore, 0, SlotActionType.QUICK_MOVE);
            return Actions.Status.RUNNING;
        }
        int items = h.getSlot(0).getStack().getCount();
        if (items == 0) {
            b.trace("furnace input empty after loading; inv=" + b.inv().summary());
            go(Phase.CLOSE_LOADED);
            return Actions.Status.RUNNING;
        }
        ItemStack fuel = h.getSlot(1).getStack();
        double burns = burnedUnits;
        if (burns + 1e-6 < items) {
            // one more unit of fuel: coal first, then planks above the reserve, then logs
            int slot = b.screenSlotOf(st -> st.getItem() == Items.COAL || st.getItem() == Items.CHARCOAL);
            if (slot < 0 && b.inv().planks() > b.plankReserve()) {
                slot = b.screenSlotOf(Inv::isPlanks);
            }
            if (slot < 0 && b.inv().logs() > 0 && b.inv().planks() + b.inv().logs() * 4 > b.plankReserve() + 4) {
                slot = b.screenSlotOf(Inv::isLog);
            }
            if (slot < 0) {
                slot = b.screenSlotOf(Inv::isPlanks); // below the reserve: smelting first, wood later
            }
            if (slot < 0) {
                slot = b.screenSlotOf(st -> st.getItem() == Items.STICK);
            }
            if (slot < 0) {
                if (burns > 0) {
                    go(Phase.CLOSE_LOADED);
                    loaded = (int) Math.min(items, Math.floor(burns));
                    readyTick = b.now() + loaded * 200L + 5;
                    return Actions.Status.RUNNING;
                }
                error = "no_fuel";
                go(Phase.CLOSE_LOADED);
                return Actions.Status.RUNNING;
            }
            ItemStack src = h.getSlot(slot).getStack();
            if (!fuel.isEmpty() && !ItemStack.areItemsEqual(fuel, src)) {
                go(Phase.CLOSE_LOADED); // a different fuel already burns; good enough
                loaded = items;
                readyTick = b.now() + items * 200L + 5;
                return Actions.Status.RUNNING;
            }
            b.c.click(slot, 0, SlotActionType.PICKUP); // pick the stack up; right clicks follow
            heldFrom = slot;
            return Actions.Status.RUNNING;
        }
        loaded = items;
        readyTick = b.now() + items * 200L + 5;
        b.trace("smelting " + items + " ore, ready in " + items * 10 + "s, fuel " + fuel);
        go(Phase.CLOSE_LOADED);
        return Actions.Status.RUNNING;
    }

    private int firstEmptyInv() {
        for (int i = 0; i < 36; i++) {
            if (b.s.inventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        return 0;
    }

    /** Items the fuel stack in the slot still smelts (coal 8, planks/logs 1.5, sticks 0.5). */
    static double fuelItems(ItemStack st) {
        if (st.isEmpty()) {
            return 0;
        }
        Item i = st.getItem();
        double per = i == Items.COAL || i == Items.CHARCOAL ? 8 : Inv.isPlanks(st) || Inv.isLog(st) ? 1.5 : i == Items.STICK ? 0.5 : 1;
        return per * st.getCount();
    }

    private Actions.Status collect() {
        AbstractFurnaceScreenHandler h = handler();
        if (h == null) {
            go(Phase.OPEN_COLLECT);
            return Actions.Status.RUNNING;
        }
        if (!b.c.guiReady()) {
            return Actions.Status.RUNNING;
        }
        if (!h.getSlot(2).getStack().isEmpty()) {
            b.c.click(2, 0, SlotActionType.QUICK_MOVE);
            return Actions.Status.RUNNING;
        }
        if (!h.getSlot(0).getStack().isEmpty()) {
            if (++collectWaits <= 4) {
                // not done yet: close and come back a bit later
                readyTick = b.now() + 60;
                go(Phase.CLOSE_LOADED);
                return Actions.Status.RUNNING;
            }
            b.c.click(0, 0, SlotActionType.QUICK_MOVE); // ran out of fuel: take the ore back
            return Actions.Status.RUNNING;
        }
        if (!h.getSlot(1).getStack().isEmpty()) {
            b.c.click(1, 0, SlotActionType.QUICK_MOVE);
            return Actions.Status.RUNNING;
        }
        go(Phase.CLOSE_DONE);
        return Actions.Status.RUNNING;
    }

    private boolean close() {
        if (!b.s.screenOpen()) {
            return true;
        }
        if (!b.s.inventory().getCursorStack().isEmpty() && heldFrom >= 0 && b.s.screen() instanceof AbstractFurnaceScreenHandler) {
            b.c.click(heldFrom, 0, SlotActionType.PICKUP);
            return false;
        }
        b.c.closeScreen();
        return !b.s.screenOpen();
    }

    /** Abandons whatever is in progress on the screen (a fight): the next call starts again from walking. */
    public void interrupt() {
        if (screenBusy()) {
            if (b.s.screenOpen()) {
                b.c.closeScreen();
            }
            go(smelting() ? Phase.SMELTING : (furnace != null ? Phase.GO : Phase.IDLE));
        }
    }

    public static Predicate<ItemStack> nonBlock() {
        return st -> !st.isEmpty() && !(st.getItem() instanceof net.minecraft.item.BlockItem);
    }
}
