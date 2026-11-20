package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** 자동 제련: raw iron, gold and copper from ores drop as ingots. */
public final class AutoSmeltAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("auto_smelt", "자동 제련", AugmentTier.SILVER,
                    AugmentKind.RESOURCE, Material.FURNACE)
            .description("철·금·구리 광석을 캐면 원석 대신 주괴가 떨어집니다.")
            .build();

    public AutoSmeltAugment(AugmentContext ctx) {
    }

    @Override
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!Ores.isOre(event.getBlockState().getType())) {
            return;
        }
        for (Item item : event.getItems()) {
            ItemStack stack = item.getItemStack();
            Material smelted = smelted(stack.getType());
            if (smelted != null) {
                item.setItemStack(ItemStack.of(smelted, stack.getAmount()));
            }
        }
    }

    /** The ingot of a raw ore item, or null. */
    static @Nullable Material smelted(Material raw) {
        return switch (raw) {
            case RAW_IRON -> Material.IRON_INGOT;
            case RAW_GOLD -> Material.GOLD_INGOT;
            case RAW_COPPER -> Material.COPPER_INGOT;
            default -> null;
        };
    }
}
