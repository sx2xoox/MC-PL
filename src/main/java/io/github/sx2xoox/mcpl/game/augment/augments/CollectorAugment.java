package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;

/** 증강 수집가: one more gold augment to pick now, and more cards in later offers. */
public final class CollectorAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("collector", "증강 수집가", AugmentTier.PRISM,
                    AugmentKind.SPECIAL, Material.BUNDLE)
            .description("골드 증강을 하나 더 바로 고릅니다. 이후 증강 선택에서는 카드가 {cards}장 더 나옵니다(최대 5장).")
            .param("cards", 1)
            .build();

    private final AugmentContext ctx;

    public CollectorAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.offer(AugmentTier.GOLD);
    }

    @Override
    public int extraCards() {
        return Math.max(0, ctx.params().integer("cards"));
    }
}
