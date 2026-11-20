package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;

/** 광전사의 계약: less max health, more damage to enemies. */
public final class BerserkerPactAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("berserker_pact", "광전사의 계약", AugmentTier.GOLD,
                    AugmentKind.TRADEOFF, Material.NETHERITE_AXE)
            .description("최대 체력이 {health}만큼 줄어드는 대신, 적에게 주는 모든 피해가 {damage}% 늘어납니다.")
            .param("health", 4)
            .param("damage", 25)
            .forBots()
            .build();

    private final AugmentContext ctx;

    public BerserkerPactAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.addModifier(Attribute.MAX_HEALTH, -ctx.params().num("health"), AttributeModifier.Operation.ADD_NUMBER);
    }

    @Override
    public void onOutgoingDamage(EntityDamageEvent event, LivingEntity victim) {
        event.setDamage(event.getDamage() * (1.0 + Math.max(-1.0, ctx.params().fraction("damage"))));
    }
}
