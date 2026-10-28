package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.event.entity.EntityDamageEvent;

/** 단단한 피부: less melee damage taken. */
public final class ThickSkinAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("thick_skin", "단단한 피부", AugmentTier.SILVER,
                    AugmentKind.STAT, Material.LEATHER)
            .description("근접 공격으로 받는 피해가 {reduction}% 줄어듭니다.")
            .param("reduction", 10)
            .forBots()
            .build();

    private final AugmentContext ctx;

    public ThickSkinAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onIncomingDamage(EntityDamageEvent event) {
        if (Hits.melee(event)) {
            double kept = 1.0 - Math.max(0.0, Math.min(1.0, ctx.params().fraction("reduction")));
            event.setDamage(event.getDamage() * kept);
        }
    }
}
