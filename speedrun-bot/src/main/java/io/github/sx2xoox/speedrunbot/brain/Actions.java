package io.github.sx2xoox.speedrunbot.brain;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

/**
 * Small player actions built from inputs only: equip an item (hotbar key, or an inventory swap through the
 * inventory screen), mine a block (aim + hold attack until the block is gone), place a block against a visible face,
 * use the held item while aiming at a point.
 */
public final class Actions {
    public enum Status { RUNNING, DONE, FAILED }

    private final BotSenses s;
    private final Controls c;
    private final WorldMemory mem;

    // equip state machine
    private int equipPhase;
    private int equipTicks;
    // mining bookkeeping
    private BlockPos mineTarget;
    private int mineTicks;
    private BlockPos lastObstruction;

    public java.util.function.Consumer<String> debug = x -> { };

    public Actions(BotSenses s, Controls c, WorldMemory mem) {
        this.s = s;
        this.c = c;
        this.mem = mem;
    }

    // ------------------------------------------------------------------ inventory

    public static int count(PlayerInventory inv, Predicate<ItemStack> p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && p.test(st)) {
                n += st.getCount();
            }
        }
        ItemStack off = inv.offHand.get(0);
        if (!off.isEmpty() && p.test(off)) {
            n += off.getCount();
        }
        return n;
    }

    public static int count(PlayerInventory inv, Item item) {
        return count(inv, st -> st.getItem() == item);
    }

    public static int findSlot(PlayerInventory inv, Predicate<ItemStack> p) {
        int sel = inv.selectedSlot;
        if (p.test(inv.getStack(sel)) && !inv.getStack(sel).isEmpty()) {
            return sel;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && p.test(st)) {
                return i;
            }
        }
        return -1;
    }

    /** Puts a matching item in the main hand. DONE when held; FAILED when none is carried. */
    public Status equip(Predicate<ItemStack> p) {
        PlayerInventory inv = s.inventory();
        ItemStack held = inv.getMainHandStack();
        if (!held.isEmpty() && p.test(held)) {
            if (equipPhase != 0 && s.screenOpen()) {
                c.closeScreen();
                equipPhase = 0;
                return Status.RUNNING;
            }
            equipPhase = 0;
            return Status.DONE;
        }
        int slot = findSlot(inv, p);
        if (slot < 0) {
            equipPhase = 0;
            return Status.FAILED;
        }
        if (slot < 9) {
            c.selectHotbar(slot);
            return Status.RUNNING;
        }
        // in the main inventory: open the inventory and swap it onto a hotbar key
        if (++equipTicks > 60) {
            equipTicks = 0;
            equipPhase = 0;
            if (s.screenOpen()) {
                c.closeScreen();
            }
            return Status.FAILED;
        }
        int target = swapTargetHotbar(inv);
        if (!s.screenOpen()) {
            if (c.openInventory()) {
                equipPhase = 1;
            }
            return Status.RUNNING;
        }
        int screenSlot = screenSlotOfInv(slot);
        if (screenSlot >= 0 && c.click(screenSlot, target, SlotActionType.SWAP)) {
            equipPhase = 2;
            c.selectHotbar(target);
        }
        return Status.RUNNING;
    }

    public Status equip(Item item) {
        return equip(st -> st.getItem() == item);
    }

    /** Hotbar slot to overwrite: the selected one if it holds nothing useful, else the last slot. */
    private int swapTargetHotbar(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) {
                return i;
            }
        }
        return 8;
    }

    public int screenSlotOfInv(int invIndex) {
        int n = s.screen().slots.size();
        for (int i = 0; i < n; i++) {
            if (s.playerInvIndexOfScreenSlot(i) == invIndex) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ mining

    /** Whether memory says the cell is solid (needs mining to pass). */
    public boolean solid(BlockPos p) {
        BlockState st = mem.get(p);
        return st != null && Blocks2.pass(st) == Blocks2.Pass.SOLID;
    }

    /**
     * Mines the block: selects the best tool, aims, holds attack until the block is gone. When another block is in
     * the way under the crosshair it is mined first.
     */
    public Status mine(BlockPos p, boolean needDrop) {
        BlockState st = mem.get(p);
        if (st == null) {
            // unknown: look at it so perception tells us
            c.lookAt(Vec3d.ofCenter(p));
            return ++mineTicks > 20 ? Status.FAILED : Status.RUNNING;
        }
        if (Blocks2.pass(st) != Blocks2.Pass.SOLID) {
            mineTarget = null;
            mineTicks = 0;
            return Status.DONE;
        }
        if (!p.equals(mineTarget)) {
            mineTarget = p;
            mineTicks = 0;
        }
        if (++mineTicks > 400) {
            mineTarget = null;
            mineTicks = 0;
            return Status.FAILED;
        }
        if (s.eyePos().squaredDistanceTo(Vec3d.ofCenter(p)) > 4.9 * 4.9) {
            return Status.FAILED;
        }
        HitResult h = s.crosshair();
        BlockPos aimPos = p;
        if (h instanceof BlockHitResult && h.getType() == HitResult.Type.BLOCK) {
            BlockPos hp = ((BlockHitResult) h).getBlockPos();
            if (!hp.equals(p) && c.aimed(aimTarget(p), 2f)) {
                BlockState hs = mem.get(hp);
                if (hs != null && !Blocks2.isLava(hs) && !Blocks2.isWater(hs) && (Blocks2.pass(hs) == Blocks2.Pass.SOLID || hs.getHardness(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN) == 0f)) {
                    aimPos = hp; // obstruction: mine it first
                    lastObstruction = hp;
                }
            }
        }
        BlockState target = mem.get(aimPos);
        int tool = target == null ? -1 : Blocks2.bestToolSlot(target, s.inventory(), needDrop && aimPos.equals(p));
        if (tool >= 0) {
            Status e = equip(st2 -> ItemStack.areItemsEqual(st2, s.inventory().getStack(tool)));
            if (e == Status.RUNNING) {
                c.lookAt(aimTarget(p));
                return Status.RUNNING;
            }
        } else if (tool == -1 && !aimPos.equals(p)) {
            // obstruction by hand
        } else if (tool == -1 && !s.inventory().getMainHandStack().isEmpty() && s.inventory().getMainHandStack().isDamageable()) {
            // a tool that does not help: switch to an empty hand or a non-tool if there is one in the hotbar
            for (int i = 0; i < 9; i++) {
                if (s.inventory().getStack(i).isEmpty()) {
                    c.selectHotbar(i);
                    break;
                }
            }
        }
        if (s.screenOpen()) {
            c.closeScreen();
            return Status.RUNNING;
        }
        c.lookAt(aimTarget(p));
        HitResult h2 = s.crosshair();
        if (mineTicks % 40 == 39) {
            debug.accept("mine " + p + " aim=" + aimPos + " hit=" + (h2 instanceof BlockHitResult ? ((BlockHitResult) h2).getBlockPos() + "/" + h2.getType() : h2.getType())
                    + " held=" + s.inventory().getMainHandStack() + " ground=" + s.onGround());
        }
        if (h2 instanceof BlockHitResult && h2.getType() == HitResult.Type.BLOCK && ((BlockHitResult) h2).getBlockPos().equals(aimPos)) {
            c.attack(true);
}
        return Status.RUNNING;
    }

    /** A point on the block to aim at: the centre of the face toward the eyes (so the ray enters that face). */
    public Vec3d aimTarget(BlockPos p) {
        Vec3d e = s.eyePos();
        Vec3d ctr = Vec3d.ofCenter(p);
        Vec3d d = e.subtract(ctr);
        double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
        if (ax >= ay && ax >= az) {
            return ctr.add(Math.signum(d.x) * 0.45, 0, 0);
        } else if (ay >= az) {
            return ctr.add(0, Math.signum(d.y) * 0.45, 0);
        }
        return ctr.add(0, 0, Math.signum(d.z) * 0.45);
    }

    // ------------------------------------------------------------------ placing / using

    /** Face of a solid neighbour that a block placed at target would be clicked on, preferring faces toward the eyes. */
    public BlockHitResult placementClick(BlockPos target) {
        Vec3d e = s.eyePos();
        BlockHitResult best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            BlockPos support = target.offset(dir);
            BlockState st = mem.get(support);
            if (st == null || !Blocks2.standable(st) || Blocks2.isLava(st)) {
                continue;
            }
            Direction face = dir.getOpposite();
            Vec3d point = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            // the face must look toward the eyes
            Vec3d toEye = e.subtract(point);
            if (toEye.x * face.getOffsetX() + toEye.y * face.getOffsetY() + toEye.z * face.getOffsetZ() <= 0.05) {
                continue;
            }
            double d = toEye.lengthSquared();
            if (d < bestD && d <= 4.4 * 4.4) {
                bestD = d;
                best = new BlockHitResult(point, face, support, false);
            }
        }
        return best;
    }

    /** Aims at the click face and uses the held item once when the crosshair is on it. */
    public Status useOn(BlockHitResult click) {
        Vec3d p = click.getPos();
        Direction f = click.getSide();
        // aim slightly inside the face
        Vec3d aim = p.subtract(f.getOffsetX() * 0.05, f.getOffsetY() * 0.05, f.getOffsetZ() * 0.05);
        c.lookAt(aim);
        HitResult h = s.crosshair();
        if (h instanceof BlockHitResult && h.getType() == HitResult.Type.BLOCK) {
            BlockHitResult b = (BlockHitResult) h;
            if (b.getBlockPos().equals(click.getBlockPos()) && b.getSide() == f) {
                return c.use() ? Status.DONE : Status.RUNNING;
            }
        }
        return Status.RUNNING;
    }

    /** Right-clicks a block (opens a table / furnace): aims at it and uses once when the crosshair is on it. */
    public Status useBlock(BlockPos p) {
        c.lookAt(aimTarget(p));
        HitResult h = s.crosshair();
        if (h instanceof BlockHitResult && h.getType() == HitResult.Type.BLOCK && ((BlockHitResult) h).getBlockPos().equals(p)) {
            return c.use() ? Status.DONE : Status.RUNNING;
        }
        return Status.RUNNING;
    }

    /**
     * Places a block item matching p at target: aims at a face that would put the block there and clicks when the
     * crosshair says so (any face that results in the target cell counts). DONE when memory shows a solid block.
     */
    public Status place(BlockPos target, Predicate<ItemStack> p) {
        BlockState st = mem.get(target);
        if (st != null && Blocks2.standable(st)) {
            return Status.DONE;
        }
        Status e = equip(p);
        if (e != Status.DONE) {
            return e;
        }
        BlockHitResult click = placementClick(target);
        if (click == null) {
            return Status.FAILED;
        }
        Direction f = click.getSide();
        c.lookAt(click.getPos().subtract(f.getOffsetX() * 0.05, f.getOffsetY() * 0.05, f.getOffsetZ() * 0.05));
        HitResult h = s.crosshair();
        if (h instanceof BlockHitResult && h.getType() == HitResult.Type.BLOCK) {
            BlockHitResult b = (BlockHitResult) h;
            BlockState hs = mem.get(b.getBlockPos());
            boolean replace = b.getBlockPos().equals(target) && hs != null && hs.getMaterial().isReplaceable();
            if (replace || b.getBlockPos().offset(b.getSide()).equals(target)) {
                c.use();
            }
        }
        return Status.RUNNING;
    }

    public static boolean isThrowaway(ItemStack st) {
        Item i = st.getItem();
        return i instanceof BlockItem && (i == net.minecraft.item.Items.COBBLESTONE || i == net.minecraft.item.Items.DIRT
                || i == net.minecraft.item.Items.NETHERRACK || i == net.minecraft.item.Items.ANDESITE
                || i == net.minecraft.item.Items.DIORITE || i == net.minecraft.item.Items.GRANITE);
    }

    public BlockPos lastObstruction() {
        return lastObstruction;
    }
}
