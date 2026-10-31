package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;

/** 철광 보따리: iron ingots and bread right away. */
public final class IronBundleAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("iron_bundle", "철광 보따리", AugmentTier.SILVER,
                    AugmentKind.RESOURCE, Material.RAW_IRON)
            .description("철 주괴 {iron}개와 빵 {bread}개를 바로 받습니다.")
            .param("iron", 12)
            .param("bread", 6)
            .build();

    private final AugmentContext ctx;

    public IronBundleAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.give(Items.stacks(Material.IRON_INGOT, ctx.params().integer("iron")));
        ctx.give(Items.stacks(Material.BREAD, ctx.params().integer("bread")));
    }
}
