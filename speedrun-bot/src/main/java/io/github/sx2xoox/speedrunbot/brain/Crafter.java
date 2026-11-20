package io.github.sx2xoox.speedrunbot.brain;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Crafts like a player with the recipe book: open the inventory (2x2) or the crafting table screen, click the recipe
 * (fills the grid), shift-click the result. Every click is spaced by {@link Controls}' GUI limit.
 */
public final class Crafter {
    private final BotSenses s;
    private final Controls c;

    private int phase;
    private int done;
    private int waits;
    private int goalTimes;
    private Recipe<?> current;
    private boolean currentTable;

    public boolean usingTable() {
        return currentTable;
    }

    /** The recipe being crafted while a craft is under way (its ingredients may sit in the grid). */
    public Recipe<?> current() {
        return current;
    }

    public Crafter(BotSenses s, Controls c) {
        this.s = s;
        this.c = c;
    }

    public void reset() {
        current = null;
        phase = 0;
        done = 0;
        waits = 0;
    }

    /** An unlocked recipe for an output matching the predicate that fits the grid and the inventory can pay for. */
    public Recipe<?> recipeFor(Predicate<Item> output, boolean table) {
        for (Recipe<?> r : s.knownCraftingRecipes()) {
            if (!output.test(r.getOutput().getItem())) {
                continue;
            }
            if (!table && !fits2x2(r)) {
                continue;
            }
            if (affordable(r, s.inventory())) {
                return r;
            }
        }
        return null;
    }

    private static boolean fits2x2(Recipe<?> r) {
        if (r instanceof net.minecraft.recipe.ShapedRecipe) {
            net.minecraft.recipe.ShapedRecipe sr = (net.minecraft.recipe.ShapedRecipe) r;
            return sr.getWidth() <= 2 && sr.getHeight() <= 2;
        }
        int n = 0;
        for (Ingredient i : r.getPreviewInputs()) {
            if (!i.isEmpty()) {
                n++;
            }
        }
        return n <= 4;
    }

    /** Greedy check that the inventory holds every ingredient (enough for the simple recipes the bot uses). */
    public static boolean affordable(Recipe<?> r, PlayerInventory inv) {
        int[] used = new int[36];
        for (Ingredient ing : r.getPreviewInputs()) {
            if (ing.isEmpty()) {
                continue;
            }
            boolean found = false;
            for (int i = 0; i < 36; i++) {
                ItemStack st = inv.getStack(i);
                if (!st.isEmpty() && st.getCount() - used[i] > 0 && ing.test(st)) {
                    used[i]++;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * Crafts the recipe {@code times} times in the open screen (the inventory is opened if needed when no table
     * screen is open). DONE when finished and the screen is closed; FAILED when the recipe cannot be paid.
     */
    public Actions.Status craft(Recipe<?> r, int times, boolean useTable) {
        return craft(r, times, useTable, false);
    }

    /** craftAll = the recipe book's shift-click: fills as many sets as the inventory allows (one shift-click crafts them all). */
    public java.util.function.Consumer<String> debug = x -> { };

    public Actions.Status craft(Recipe<?> r, int times, boolean useTable, boolean craftAll) {
        if (waits % 40 == 0 || Boolean.getBoolean("srb.debugGui")) {
            debug.accept("craft " + (r == null ? null : r.getId()) + " phase=" + phase + " done=" + done + "/" + times + " screen=" + s.screenOpen()
                    + " result=" + s.screen().getSlot(0).getStack() + " grid1=" + s.screen().getSlot(1).getStack() + " waits=" + waits + " inv=" + new Inv(s.inventory()).summary());
        }
        if (++waits > 400) {
            reset();
            if (s.screenOpen()) {
                c.closeScreen();
            }
            return Actions.Status.FAILED;
        }
        if (current != null && r != null && !current.getId().equals(r.getId())) {
            reset(); // a different job: start over
        }
        boolean tableOpen = s.screen() instanceof CraftingScreenHandler;
        current = r;
        currentTable = useTable;
        if (phase == 0) {
            if (useTable && !tableOpen) {
                return Actions.Status.FAILED; // the caller opens the table
            }
            if (!useTable && !s.screenOpen()) {
                c.openInventory();
                return Actions.Status.RUNNING;
            }
            phase = 1;
            goalTimes = times;
            current = r;
        }
        if (phase == 1) {
            if (done >= goalTimes) {
                phase = 3;
            } else if (!affordable(r, s.inventory())) {
                phase = 3;
                if (done == 0) {
                    c.closeScreen();
                    reset();
                    return Actions.Status.FAILED;
                }
            } else if (c.craft(r.getId(), craftAll)) {
                phase = 2;
            }
            return Actions.Status.RUNNING;
        }
        if (phase == 2) {
            boolean ok = s.screen() instanceof CraftingScreenHandler || s.screen() instanceof PlayerScreenHandler;
            if (!ok) {
                reset();
                return Actions.Status.FAILED;
            }
            ItemStack result = s.screen().getSlot(0).getStack();
            if (result.isEmpty()) {
                phase = 1; // recipe click did not fill; try again
                return Actions.Status.RUNNING;
            }
            if (c.click(0, 0, SlotActionType.QUICK_MOVE)) {
                done++;
                phase = 1;
            }
            return Actions.Status.RUNNING;
        }
        if (phase == 3) {
            if (c.closeScreen()) {
                reset();
                return Actions.Status.DONE;
            }
        }
        return Actions.Status.RUNNING;
    }

    public static List<Item> items(Item... items) {
        List<Item> l = new ArrayList<>();
        for (Item i : items) {
            l.add(i);
        }
        return l;
    }
}
