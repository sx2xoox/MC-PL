package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Item;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;

/** 행운의 곡괭이: a chance to double what an ore drops. */
public final class LuckyPickAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("lucky_pick", "행운의 곡괭이", AugmentTier.SILVER,
                    AugmentKind.RESOURCE, Material.EMERALD)
            .description("광석을 캘 때 {chance}% 확률로 떨어지는 아이템이 2배가 됩니다 (섬세한 손길 제외).")
            .param("chance", 25)
            .build();

    private final AugmentContext ctx;

    public LuckyPickAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onBlockDrop(BlockDropItemEvent event) {
        if (event.getItems().isEmpty() || !Ores.isOre(event.getBlockState().getType()) || Ores.silkTouch(ctx.player())
                || ThreadLocalRandom.current().nextDouble() >= ctx.params().fraction("chance")) {
            return;
        }
        for (Item item : event.getItems()) {
            ItemStack stack = item.getItemStack();
            item.setItemStack(stack.asQuantity(Math.min(stack.getMaxStackSize(), stack.getAmount() * 2)));
        }
        Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 8, 0.3, 0.3, 0.3, 0);
    }
}
