package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;

/** 처형자: more damage to enemies with little health left. */
public final class ExecutionerAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("executioner", "처형자", AugmentTier.GOLD,
                    AugmentKind.COMBAT, Material.GOLDEN_AXE)
            .description("체력이 최대 체력의 {threshold}% 이하인 적에게 주는 모든 피해가 {damage}% 늘어납니다.")
            .param("threshold", 30)
            .param("damage", 30)
            .forBots()
            .build();

    private final AugmentContext ctx;

    public ExecutionerAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onOutgoingDamage(EntityDamageEvent event, LivingEntity victim) {
        AttributeInstance max = victim.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? victim.getHealth() : max.getValue();
        if (cap > 0 && victim.getHealth() <= cap * ctx.params().fraction("threshold")) {
            event.setDamage(event.getDamage() * (1.0 + Math.max(0.0, ctx.params().fraction("damage"))));
        }
    }
}
