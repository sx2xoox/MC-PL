package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;

/** 거미줄 함정: cobwebs to place (the match world is thrown away, so nothing needs cleaning up). */
public final class CobwebTrapAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("cobweb_trap", "거미줄 함정", AugmentTier.GOLD,
                    AugmentKind.COMBAT, Material.COBWEB)
            .description("거미줄 {cobwebs}개를 바로 받습니다. 쫓아오는 적의 발을 묶으세요.")
            .param("cobwebs", 12)
            .build();

    private final AugmentContext ctx;

    public CobwebTrapAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.give(Items.stacks(Material.COBWEB, ctx.params().integer("cobwebs")));
    }
}
