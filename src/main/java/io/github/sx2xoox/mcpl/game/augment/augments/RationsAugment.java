package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;

/** 비상식량: golden apples and steaks right away. */
public final class RationsAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("rations", "비상식량", AugmentTier.SILVER,
                    AugmentKind.RESOURCE, Material.GOLDEN_APPLE)
            .description("황금 사과 {apples}개와 스테이크 {steaks}개를 바로 받습니다.")
            .param("apples", 2)
            .param("steaks", 16)
            .build();

    private final AugmentContext ctx;

    public RationsAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.give(Items.stacks(Material.GOLDEN_APPLE, ctx.params().integer("apples")));
        ctx.give(Items.stacks(Material.COOKED_BEEF, ctx.params().integer("steaks")));
    }
}
