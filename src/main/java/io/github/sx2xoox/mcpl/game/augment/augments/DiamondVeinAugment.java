package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

/** 다이아 광맥: diamonds now, and more from every diamond ore. */
public final class DiamondVeinAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("diamond_vein", "다이아 광맥", AugmentTier.PRISM,
                    AugmentKind.RESOURCE, Material.DIAMOND)
            .description("다이아몬드 {diamonds}개를 바로 받고, 다이아몬드 광석을 캘 때마다(섬세한 손길 제외) 다이아몬드가"
                    + " {extra}개 더 나옵니다.")
            .param("diamonds", 5)
            .param("extra", 1)
            .build();

    private final AugmentContext ctx;

    public DiamondVeinAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.give(Items.stacks(Material.DIAMOND, ctx.params().integer("diamonds")));
    }

    @Override
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        if (type != Material.DIAMOND_ORE && type != Material.DEEPSLATE_DIAMOND_ORE) {
            return;
        }
        ItemStack tool = ctx.player().getInventory().getItemInMainHand();
        if (!event.isDropItems() || tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            return;
        }
        int extra = ctx.params().integer("extra");
        if (extra > 0) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5),
                    ItemStack.of(Material.DIAMOND, extra));
        }
    }
}
