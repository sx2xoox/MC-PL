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

/** 강철 체력: more max health. */
public final class IronBodyAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("iron_body", "강철 체력", AugmentTier.SILVER,
                    AugmentKind.STAT, Material.IRON_BLOCK)
            .description("최대 체력이 {health} 늘어나고(체력 2 = 하트 1칸) 늘어난 만큼 바로 회복합니다.")
            .param("health", 4)
            .forBots()
            .build();

    private final AugmentContext ctx;

    public IronBodyAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        double health = ctx.params().num("health");
        ctx.addModifier(Attribute.MAX_HEALTH, health, AttributeModifier.Operation.ADD_NUMBER);
        LivingEntity player = ctx.owner();
        if (health > 0) {
            player.heal(health);
        }
    }
}
