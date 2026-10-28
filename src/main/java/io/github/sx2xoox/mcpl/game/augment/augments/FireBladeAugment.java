package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;

/** 불꽃 칼날: melee hits set enemies on fire (with a cooldown). */
public final class FireBladeAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("fire_blade", "불꽃 칼날", AugmentTier.SILVER,
                    AugmentKind.COMBAT, Material.BLAZE_ROD)
            .description("근접 공격이 적을 {seconds}초 동안 불태웁니다 (재사용 {cooldown}초).")
            .param("seconds", 2)
            .param("cooldown", 4)
            .build();

    private final AugmentContext ctx;
    private long readyAt;

    public FireBladeAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onOutgoingDamage(EntityDamageEvent event, LivingEntity victim) {
        if (ctx.tick() < readyAt || !Hits.meleeBy(event, ctx.player())) {
            return;
        }
        int ticks = (int) ctx.params().ticks("seconds");
        if (ticks <= 0) {
            return;
        }
        victim.setFireTicks(Math.max(victim.getFireTicks(), ticks));
        readyAt = ctx.tick() + ctx.params().ticks("cooldown");
    }
}
