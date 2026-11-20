package io.github.sx2xoox.speedrunbot.brain;

import io.github.sx2xoox.speedrunbot.core.MiningTime;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

/** Static block classification used by planning (from the block state alone, no world access). */
public final class Blocks2 {
    public enum Pass { PASS, WATER, SOLID, FORBID }

    private static Pass[] passCache = new Pass[0];

    private Blocks2() {
    }

    public static boolean isLava(BlockState s) {
        Fluid f = s.getFluidState().getFluid();
        return f == Fluids.LAVA || f == Fluids.FLOWING_LAVA;
    }

    public static boolean isWater(BlockState s) {
        Fluid f = s.getFluidState().getFluid();
        return f == Fluids.WATER || f == Fluids.FLOWING_WATER;
    }

    public static boolean isWaterSource(BlockState s) {
        return s.getBlock() == Blocks.WATER && s.getFluidState().isStill();
    }

    public static boolean isLavaSource(BlockState s) {
        return s.getBlock() == Blocks.LAVA && s.getFluidState().isStill();
    }

    public static boolean falls(BlockState s) {
        return s.getBlock() instanceof FallingBlock;
    }

    /** How a body cell behaves for walking through it. */
    public static Pass pass(BlockState s) {
        int id = Block.getRawIdFromState(s);
        if (id < passCache.length && passCache[id] != null) {
            return passCache[id];
        }
        Pass p = computePass(s);
        if (id >= passCache.length) {
            Pass[] n = new Pass[Math.max(id + 1, passCache.length * 2)];
            System.arraycopy(passCache, 0, n, 0, passCache.length);
            passCache = n;
        }
        passCache[id] = p;
        return p;
    }

    private static Pass computePass(BlockState s) {
        Block b = s.getBlock();
        if (s.isAir()) {
            return Pass.PASS;
        }
        if (isLava(s) || b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CACTUS || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.COBWEB || b == Blocks.MAGMA_BLOCK || b == Blocks.CAMPFIRE || b == Blocks.NETHER_PORTAL
                || b == Blocks.END_PORTAL) {
            return Pass.FORBID;
        }
        if (isWater(s) && s.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).isEmpty()) {
            return Pass.WATER;
        }
        if (s.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).isEmpty()) {
            return Pass.PASS;
        }
        if (s.getHardness(EmptyBlockView.INSTANCE, BlockPos.ORIGIN) < 0) {
            return Pass.FORBID;
        }
        return Pass.SOLID;
    }

    /** Right-clicking it does something (opens a screen...) instead of placing against it. */
    public static boolean interactive(BlockState s) {
        Block b = s.getBlock();
        return b instanceof net.minecraft.block.BlockWithEntity || b == Blocks.CRAFTING_TABLE || b instanceof net.minecraft.block.DoorBlock
                || b instanceof net.minecraft.block.TrapdoorBlock || b instanceof net.minecraft.block.FenceGateBlock
                || b instanceof net.minecraft.block.AbstractButtonBlock || b == Blocks.LEVER || b instanceof net.minecraft.block.BedBlock
                // right-clicking these is accepted by the block itself (redstone ore lights up), so the held item is not used
                || b == Blocks.REDSTONE_ORE || b == Blocks.NOTE_BLOCK || b == Blocks.CAKE || b == Blocks.REPEATER || b == Blocks.COMPARATOR
                || b == Blocks.DRAGON_EGG || b instanceof net.minecraft.block.AnvilBlock;
    }

    /** Whether the block can be stood on. */
    public static boolean standable(BlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.MAGMA_BLOCK || b == Blocks.CACTUS || isLava(s) || b == Blocks.CAMPFIRE) {
            return false;
        }
        return !s.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).isEmpty();
    }

    /** Best breaking delta with any item in the inventory (or the hand), on the ground and dry. */
    public static float bestDelta(BlockState s, PlayerInventory inv) {
        float hardness = s.getHardness(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
        float best = MiningTime.delta(hardness, !s.isToolRequired(), 1f, 0, false, true);
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty()) {
                continue;
            }
            float speed = st.getMiningSpeedMultiplier(s);
            boolean harvest = !s.isToolRequired() || st.isEffectiveOn(s);
            float d = MiningTime.delta(hardness, harvest, speed, 0, false, true);
            if (d > best) {
                best = d;
            }
        }
        return best;
    }

    /** Inventory slot (0-35) of the item that breaks the block fastest while still dropping it if possible; -1 = hand. */
    public static int bestToolSlot(BlockState s, PlayerInventory inv, boolean needDrop) {
        float hardness = s.getHardness(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
        int bestSlot = -1;
        float best = -1;
        boolean bestHarvest = !s.isToolRequired();
        if (!needDrop || bestHarvest) {
            best = MiningTime.delta(hardness, bestHarvest, 1f, 0, false, true);
        }
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty()) {
                continue;
            }
            boolean harvest = !s.isToolRequired() || st.isEffectiveOn(s);
            if (needDrop && !harvest) {
                continue;
            }
            float d = MiningTime.delta(hardness, harvest, st.getMiningSpeedMultiplier(s), 0, false, true);
            // prefer a real tool only when it is faster (keeps tools from wearing on dirt)
            if (d > best * 1.001f) {
                best = d;
                bestSlot = i;
            }
        }
        return best < 0 ? -2 : bestSlot;
    }
}
