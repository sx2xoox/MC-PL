package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerExpChangeEvent;

/** 경험의 샘: levels right away, and more experience from then on. */
public final class XpSpringAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("xp_spring", "경험의 샘", AugmentTier.GOLD,
                    AugmentKind.RESOURCE, Material.EXPERIENCE_BOTTLE)
            .description("레벨을 {levels} 바로 얻고, 이후 얻는 경험치가 {multiplier}배가 됩니다.")
            .param("levels", 10)
            .param("multiplier", 2)
            .build();

    private final AugmentContext ctx;

    public XpSpringAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        int levels = ctx.params().integer("levels");
        if (levels > 0) {
            ctx.player().giveExpLevels(levels);
        }
    }

    @Override
    public void onExpChange(PlayerExpChangeEvent event) {
        if (event.getAmount() > 0) {
            event.setAmount((int) Math.round(event.getAmount() * Math.max(0.0, ctx.params().num("multiplier"))));
        }
    }
}
