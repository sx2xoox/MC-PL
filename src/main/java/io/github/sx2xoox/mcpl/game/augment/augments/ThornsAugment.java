package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;

/** 가시 갑옷: part of the melee damage taken is dealt back to the attacker. */
public final class ThornsAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("thorns", "가시 갑옷", AugmentTier.GOLD,
                    AugmentKind.COMBAT, Material.CACTUS)
            .description("적의 근접 공격으로 받은 피해의 {percent}%를 공격한 적에게 돌려줍니다.")
            .param("percent", 20)
            .forBots()
            .build();

    private final AugmentContext ctx;

    public ThornsAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onIncomingDamage(EntityDamageEvent event) {
        LivingEntity attacker = Hits.attacker(event);
        if (!Hits.melee(event) || attacker == null || !ctx.isEnemy(attacker)) {
            return;
        }
        double amount = event.getFinalDamage() * ctx.params().fraction("percent");
        if (amount > 0) {
            ctx.damage(attacker, amount);
        }
    }
}
