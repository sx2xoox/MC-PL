package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityRegainHealthEvent;

/** 피의 갈증: lifesteal on damage dealt to enemies. */
public final class BloodthirstAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("bloodthirst", "피의 갈증", AugmentTier.GOLD,
                    AugmentKind.COMBAT, Material.REDSTONE)
            .description("적(상대 플레이어, 적대적 몹)에게 준 피해의 {percent}%만큼 체력을 회복합니다.")
            .param("percent", 20)
            .forBots()
            .build();

    private final AugmentContext ctx;

    public BloodthirstAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void afterOutgoingDamage(LivingEntity victim, double finalDamage) {
        double amount = finalDamage * ctx.params().fraction("percent");
        LivingEntity player = ctx.owner();
        if (amount > 0 && !player.isDead()) {
            player.heal(amount, EntityRegainHealthEvent.RegainReason.MAGIC);
        }
    }
}
