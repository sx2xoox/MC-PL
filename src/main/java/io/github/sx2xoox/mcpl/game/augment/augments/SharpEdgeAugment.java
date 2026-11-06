package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;

/** 날카로운 칼날: flat bonus melee damage. */
public final class SharpEdgeAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("sharp_edge", "날카로운 칼날", AugmentTier.SILVER,
                    AugmentKind.COMBAT, Material.IRON_SWORD)
            .description("적에게 주는 근접 공격 피해가 {damage} 늘어납니다.")
            .param("damage", 1.5)
            .forBots()
            .build();

    private final AugmentContext ctx;

    public SharpEdgeAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onOutgoingDamage(EntityDamageEvent event, LivingEntity victim) {
        if (Hits.meleeBy(event, ctx.owner())) {
            event.setDamage(Math.max(0.0, event.getDamage() + ctx.params().num("damage")));
        }
    }
}
