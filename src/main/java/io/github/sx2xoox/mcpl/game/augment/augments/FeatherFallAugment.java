package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.event.entity.EntityDamageEvent;

/** 깃털 낙법: (almost) no fall damage. */
public final class FeatherFallAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("feather_fall", "깃털 낙법", AugmentTier.SILVER,
                    AugmentKind.MOBILITY, Material.FEATHER)
            .description("떨어질 때 받는 피해가 {reduction}% 줄어듭니다.")
            .param("reduction", 100)
            .forBots()
            .build();

    private final AugmentContext ctx;

    public FeatherFallAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onIncomingDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        double kept = 1.0 - Math.max(0.0, Math.min(1.0, ctx.params().fraction("reduction")));
        if (kept <= 0.0) {
            event.setCancelled(true);
        } else {
            event.setDamage(event.getDamage() * kept);
        }
    }
}
