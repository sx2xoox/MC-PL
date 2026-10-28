package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** 날개: an elytra and firework rockets. */
public final class WingsAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("wings", "날개", AugmentTier.PRISM,
                    AugmentKind.MOBILITY, Material.ELYTRA)
            .description("겉날개 1개와 폭죽 로켓 {rockets}개를 바로 받습니다.")
            .param("rockets", 8)
            .build();

    private final AugmentContext ctx;

    public WingsAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.give(ItemStack.of(Material.ELYTRA));
        ctx.give(Items.stacks(Material.FIREWORK_ROCKET, ctx.params().integer("rockets")));
    }
}
