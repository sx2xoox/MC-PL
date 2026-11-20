package io.github.sx2xoox.speedrunbot.brain;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Recipe;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.tag.ItemTags;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * One crafting session owned from start to end: (if a table is needed) use a table right here or place one (crafting
 * it from planks in the 2x2 grid first), open the screen, craft every requested item with the recipe book
 * (missing planks / sticks are crafted first), close. While a session runs nothing else touches the screen.
 */
public final class Workbench {
    public static final class Need {
        final String name;
        final Predicate<Item> output;
        final int count;
        final boolean table;

        public Need(String name, Predicate<Item> output, int count, boolean table) {
            this.name = name;
            this.output = output;
            this.count = count;
            this.table = table;
        }
    }

    private enum Phase { IDLE, PREP_TABLE_ITEM, PLACE_TABLE, GO_TABLE, OPEN, CRAFT, WAIT_RESULT, CLOSE, PICKUP }

    private final RunBrain b;
    private Phase phase = Phase.IDLE;
    private List<Need> needs;
    private int needIndex;
    private int haveBefore;
    private BlockPos table;
    private int ticks;
    private int phaseTicks;
    private String error;
    private boolean subCraft;

    public Workbench(RunBrain b) {
        this.b = b;
    }

    public boolean busy() {
        return phase != Phase.IDLE;
    }

    /** Drops the session (a fight): the screen is closed, the next call starts over. */
    public void interrupt() {
        if (b.s.screenOpen()) {
            b.c.closeScreen();
        }
        phase = Phase.IDLE;
    }

    public String error() {
        return error;
    }

    public BlockPos table() {
        return table;
    }

    private void go(Phase p) {
        if (Boolean.getBoolean("srb.debugGui")) {
            b.trace("bench " + phase + " -> " + p + " table=" + table + " screen=" + b.s.screen().getClass().getSimpleName() + "/" + b.s.screenOpen()
                    + " result=" + b.s.screen().getSlot(0).getStack() + " inv=" + b.inv().summary());
        }
        phase = p;
        phaseTicks = 0;
    }

    private Actions.Status fail(String why) {
        error = why;
        b.trace("workbench failed: " + why + " inv=" + b.inv().summary());
        if (b.s.screenOpen()) {
            b.c.closeScreen();
        }
        phase = Phase.IDLE;
        return Actions.Status.FAILED;
    }

    private int count(Predicate<Item> out) {
        return Actions.count(b.s.inventory(), st -> out.test(st.getItem()));
    }

    /** Runs the session; call every tick with the same needs until DONE / FAILED. */
    public Actions.Status run(List<Need> requested) {
        ticks++;
        phaseTicks++;
        if (phase == Phase.IDLE) {
            needs = new ArrayList<>(requested);
            needIndex = 0;
            error = null;
            ticks = 0;
            boolean anyTable = false;
            for (Need n : needs) {
                anyTable |= n.table;
            }
            if (anyTable) {
                table = knownTable();
                go(table != null ? Phase.GO_TABLE : (b.inv().has(Items.CRAFTING_TABLE) ? Phase.PLACE_TABLE : Phase.PREP_TABLE_ITEM));
            } else {
                table = null;
                go(Phase.OPEN);
            }
        }
        if (ticks > 900) {
            return fail("timeout_" + phase);
        }
        switch (phase) {
            case PREP_TABLE_ITEM:
                return prepTableItem();
            case PLACE_TABLE:
                return placeTable();
            case GO_TABLE:
                return goTable();
            case OPEN:
                return open();
            case CRAFT:
                return craft();
            case WAIT_RESULT:
                return waitResult();
            case CLOSE:
                if (!b.s.screenOpen() || b.c.closeScreen()) {
                    if (table != null && b.tableNeededLater()) {
                        // too little wood for another table and crafts still ahead (underground): take this one along
                        b.trace("picking the table up again (" + b.inv().planks() + " planks)");
                        go(Phase.PICKUP);
                        return Actions.Status.RUNNING;
                    }
                    phase = Phase.IDLE;
                    return Actions.Status.DONE;
                }
                return Actions.Status.RUNNING;
            case PICKUP: {
                BlockState st = b.mem.get(table);
                if (st != null && st.getBlock() == Blocks.CRAFTING_TABLE && phaseTicks < 200) {
                    if (b.a.mine(table, true) == Actions.Status.FAILED) {
                        phase = Phase.IDLE;
                        return Actions.Status.DONE;
                    }
                    return Actions.Status.RUNNING;
                }
                // walk over the drop
                BlockPos f = b.feet();
                if (b.inv().has(Items.CRAFTING_TABLE) || phaseTicks > 260 || (f.getX() == table.getX() && f.getZ() == table.getZ())) {
                    phase = Phase.IDLE;
                    return Actions.Status.DONE;
                }
                if (b.mover.go(Navigator.near(table, 0.5)) != Mover.Status.RUNNING) {
                    phase = Phase.IDLE;
                    return Actions.Status.DONE;
                }
                return Actions.Status.RUNNING;
            }
            default:
                return Actions.Status.RUNNING;
        }
    }

    private BlockPos knownTable() {
        if (b.inv().has(Items.CRAFTING_TABLE)) {
            return null; // carried along: place it here
        }
        double reuse = b.p.get("craft.tableReuseDist");
        if (b.inv().planks() + b.inv().logs() * 4 < 4) {
            reuse = Math.max(reuse, 64); // no wood for a new one: a walk to an old table beats a trip to the trees
        }
        return b.mem.nearest(WorldMemory.Kind.CRAFTING_TABLE, b.feet(), reuse, l -> !b.badTargets().contains(l));
    }

    // ------------------------------------------------------------------ table

    /** A table item: crafted in the 2x2 grid (planks first if needed). */
    private Actions.Status prepTableItem() {
        if (b.inv().has(Items.CRAFTING_TABLE)) {
            if (b.s.screenOpen()) {
                b.c.closeScreen();
                return Actions.Status.RUNNING;
            }
            go(Phase.PLACE_TABLE);
            return Actions.Status.RUNNING;
        }
        if (!b.s.screenOpen()) {
            b.c.openInventory();
            return Actions.Status.RUNNING;
        }
        if (!(b.s.screen() instanceof PlayerScreenHandler)) {
            b.c.closeScreen();
            return Actions.Status.RUNNING;
        }
        if (b.s.screen().getSlot(0).getStack().getItem() != Items.AIR) {
            b.c.click(0, 0, SlotActionType.QUICK_MOVE);
            return Actions.Status.RUNNING;
        }
        if (b.inv().planks() < 4) {
            if (b.inv().logs() == 0) {
                return fail("no_wood_for_table");
            }
            clickRecipe(i -> ItemTags.PLANKS.contains(i), false);
            return Actions.Status.RUNNING;
        }
        clickRecipe(i -> i == Items.CRAFTING_TABLE, false);
        return Actions.Status.RUNNING;
    }

    private Actions.Status placeTable() {
        if (b.s.screenOpen() && !b.a.equipBusy()) {
            b.c.closeScreen();
            return Actions.Status.RUNNING;
        }
        BlockPos placed = b.placeNear(st -> st.getItem() == Items.CRAFTING_TABLE, Blocks.CRAFTING_TABLE);
        if (placed != null) {
            table = placed;
            b.trace("table at " + placed);
            go(Phase.GO_TABLE);
        } else if (phaseTicks > 300) {
            return fail("place_table");
        }
        return Actions.Status.RUNNING;
    }

    private Actions.Status goTable() {
        BlockState st = b.mem.get(table);
        if (st != null && st.getBlock() != Blocks.CRAFTING_TABLE) {
            table = null;
            go(b.inv().has(Items.CRAFTING_TABLE) ? Phase.PLACE_TABLE : Phase.PREP_TABLE_ITEM);
            return Actions.Status.RUNNING;
        }
        BlockPos f = b.feet();
        Navigator.Goal g = Navigator.reach(table, 4.0);
        if (!g.done(f.getX(), f.getY(), f.getZ())) {
            if (b.mover.go(g) == Mover.Status.FAILED || phaseTicks > 200) {
                b.badTargets().add(table.asLong());
                table = null;
                go(b.inv().has(Items.CRAFTING_TABLE) ? Phase.PLACE_TABLE : Phase.PREP_TABLE_ITEM);
            }
            return Actions.Status.RUNNING;
        }
        go(Phase.OPEN);
        return Actions.Status.RUNNING;
    }

    // ------------------------------------------------------------------ screen

    private Actions.Status open() {
        if (table == null) {
            if (b.s.screen() instanceof PlayerScreenHandler && b.s.screenOpen()) {
                go(Phase.CRAFT);
                return Actions.Status.RUNNING;
            }
            if (b.s.screenOpen()) {
                b.c.closeScreen();
                return Actions.Status.RUNNING;
            }
            b.c.openInventory();
            return Actions.Status.RUNNING;
        }
        if (b.s.screen() instanceof CraftingScreenHandler) {
            go(Phase.CRAFT);
            return Actions.Status.RUNNING;
        }
        if (b.s.screenOpen()) {
            if (!b.a.equipBusy()) {
                b.c.closeScreen();
            }
            return Actions.Status.RUNNING;
        }
        if (phaseTicks > 80) {
            b.badTargets().add(table.asLong());
            table = null;
            go(b.inv().has(Items.CRAFTING_TABLE) ? Phase.PLACE_TABLE : Phase.PREP_TABLE_ITEM);
            return Actions.Status.RUNNING;
        }
        // hold a non-block item so the right click opens the table instead of placing a block
        if (b.a.equip(st -> !st.isEmpty() && !(st.getItem() instanceof net.minecraft.item.BlockItem)) == Actions.Status.RUNNING) {
            return Actions.Status.RUNNING;
        }
        if (b.a.useBlock(table) == Actions.Status.DONE) {
            b.c.screenOpened();
        }
        return Actions.Status.RUNNING;
    }

    private boolean clickRecipe(Predicate<Item> out, boolean tableGrid) {
        Recipe<?> r = b.crafter.recipeFor(out, tableGrid);
        if (r == null) {
            return false;
        }
        return b.c.craft(r.getId(), false);
    }

    private Actions.Status craft() {
        if (!b.s.screenOpen()) {
            go(Phase.OPEN); // closed under us: open again
            return Actions.Status.RUNNING;
        }
        boolean tableGrid = b.s.screen() instanceof CraftingScreenHandler;
        if (!b.s.screen().getSlot(0).getStack().isEmpty()) {
            // something left in the result slot (an earlier fill): take it
            b.c.click(0, 0, SlotActionType.QUICK_MOVE);
            return Actions.Status.RUNNING;
        }
        if (needIndex >= needs.size()) {
            go(Phase.CLOSE);
            return Actions.Status.RUNNING;
        }
        Need n = needs.get(needIndex);
        if (n.table && !tableGrid) {
            go(Phase.CLOSE); // should not happen: needs are ordered table-last by the caller
            return Actions.Status.RUNNING;
        }
        if (count(n.output) >= n.count) {
            b.split(n.name);
            needIndex++;
            return Actions.Status.RUNNING;
        }
        if (!b.c.guiReady()) {
            return Actions.Status.RUNNING;
        }
        Recipe<?> r = b.crafter.recipeFor(n.output, tableGrid);
        if (r == null) {
            // ingredients: sticks / planks first
            boolean needsSticks = n.output.test(Items.WOODEN_PICKAXE) || n.output.test(Items.STONE_PICKAXE) || n.output.test(Items.IRON_PICKAXE)
                    || n.output.test(Items.WOODEN_SHOVEL) || n.output.test(Items.IRON_SHOVEL);
            if (needsSticks && b.inv().count(Items.STICK) < 2 && b.inv().planks() >= 2) {
                subCraft = clickRecipe(i -> i == Items.STICK, tableGrid);
            } else if (b.inv().logs() > 0 && b.inv().planks() < 8) {
                subCraft = clickRecipe(i -> ItemTags.PLANKS.contains(i), tableGrid);
            } else if (needsSticks && b.inv().count(Items.STICK) < 2 && b.inv().logs() > 0) {
                subCraft = clickRecipe(i -> ItemTags.PLANKS.contains(i), tableGrid);
            } else {
                if (n.name.startsWith("spare_")) {
                    needIndex++; // optional extra: skip it
                    return Actions.Status.RUNNING;
                }
                int known = 0;
                for (Recipe<?> kr : b.s.knownCraftingRecipes()) {
                    if (n.output.test(kr.getOutput().getItem())) {
                        known++;
                        b.trace("known recipe for " + n.name + ": " + kr.getId() + " affordable=" + Crafter.affordable(kr, b.s.inventory()));
                    }
                }
                b.trace("no recipe for " + n.name + " (" + known + " known)");
                return fail("no_recipe_" + n.name);
            }
            if (subCraft) {
                go(Phase.WAIT_RESULT);
            }
            return Actions.Status.RUNNING;
        }
        haveBefore = count(n.output);
        if (b.c.craft(r.getId(), false)) {
            subCraft = false;
            go(Phase.WAIT_RESULT);
        }
        return Actions.Status.RUNNING;
    }

    private Actions.Status waitResult() {
        if (!b.s.screenOpen()) {
            go(Phase.OPEN);
            return Actions.Status.RUNNING;
        }
        ItemStack res = b.s.screen().getSlot(0).getStack();
        if (res.isEmpty()) {
            if (phaseTicks > 10) {
                go(Phase.CRAFT); // the fill did not happen: re-evaluate
            }
            return Actions.Status.RUNNING;
        }
        if (b.c.click(0, 0, SlotActionType.QUICK_MOVE)) {
            go(Phase.CRAFT);
        }
        return Actions.Status.RUNNING;
    }
}
