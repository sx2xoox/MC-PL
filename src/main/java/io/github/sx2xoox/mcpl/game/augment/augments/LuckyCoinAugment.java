package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;

/** 행운의 동전: more cards in later offers and extra rerolls. */
public final class LuckyCoinAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("lucky_coin", "행운의 동전", AugmentTier.GOLD,
                    AugmentKind.SPECIAL, Material.SUNFLOWER)
            .description("이후 증강 선택에서 카드가 {cards}장 더 나오고(최대 5장), 리롤을 {rerolls}번 더 받습니다.")
            .param("cards", 1)
            .param("rerolls", 1)
            .build();

    private final AugmentContext ctx;

    public LuckyCoinAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.addRerolls(ctx.params().integer("rerolls"));
    }

    @Override
    public int extraCards() {
        return Math.max(0, ctx.params().integer("cards"));
    }
}
