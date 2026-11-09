package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;

/** 엔더 진주: ender pearls right away. */
public final class PearlsAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("pearls", "엔더 진주", AugmentTier.SILVER,
                    AugmentKind.RESOURCE, Material.ENDER_PEARL)
            .description("엔더 진주 {pearls}개를 바로 받습니다.")
            .param("pearls", 4)
            .build();

    private final AugmentContext ctx;

    public PearlsAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.give(Items.stacks(Material.ENDER_PEARL, ctx.params().integer("pearls")));
    }
}
