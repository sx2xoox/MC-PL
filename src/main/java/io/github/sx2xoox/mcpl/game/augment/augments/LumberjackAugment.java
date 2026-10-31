package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

/** 나무꾼: breaking a log fells the logs of the same kind connected above it. */
public final class LumberjackAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("lumberjack", "나무꾼", AugmentTier.SILVER,
                    AugmentKind.RESOURCE, Material.IRON_AXE)
            .description("원목을 캐면 위로 이어진 같은 원목이 한꺼번에 떨어집니다 (최대 {max}개, 웅크리면 하나만).")
            .param("max", 64)
            .build();

    private record Pos(int x, int y, int z) {
        static Pos of(Block block) {
            return new Pos(block.getX(), block.getY(), block.getZ());
        }
    }

    private final AugmentContext ctx;

    public LumberjackAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = ctx.player();
        Block origin = event.getBlock();
        Material type = origin.getType();
        if (!event.isDropItems() || player.isSneaking() || !Tag.LOGS.isTagged(type)) {
            return;
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        for (Block log : connectedAbove(origin, type, ctx.params().integer("max"))) {
            log.breakNaturally(tool, true);
        }
    }

    /**
     * Blocks of {@code type} connected to {@code origin} (also diagonally) on its level or above, nearest first, at
     * most {@code max}; {@code origin} itself is not included.
     */
    static List<Block> connectedAbove(Block origin, Material type, int max) {
        List<Block> found = new ArrayList<>();
        Set<Pos> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        seen.add(Pos.of(origin));
        queue.add(origin);
        while (!queue.isEmpty() && found.size() < max) {
            Block current = queue.poll();
            for (int dy = 0; dy <= 1 && found.size() < max; dy++) {
                for (int dx = -1; dx <= 1 && found.size() < max; dx++) {
                    for (int dz = -1; dz <= 1 && found.size() < max; dz++) {
                        Block next = current.getRelative(dx, dy, dz);
                        if (next.getY() < origin.getY() || next.getType() != type || !seen.add(Pos.of(next))) {
                            continue;
                        }
                        found.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        return found;
    }
}
