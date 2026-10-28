package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** 독침: melee hits poison enemies (with a cooldown). */
public final class VenomAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("venom", "독침", AugmentTier.SILVER,
                    AugmentKind.COMBAT, Material.SPIDER_EYE)
            .description("근접 공격이 적에게 {seconds}초 동안 독 {level}을 겁니다 (재사용 {cooldown}초).")
            .param("seconds", 2)
            .param("level", 2)
            .param("cooldown", 4)
            .forBots()
            .build();

    private final AugmentContext ctx;
    private long readyAt;

    public VenomAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onOutgoingDamage(EntityDamageEvent event, LivingEntity victim) {
        if (ctx.tick() < readyAt || !Hits.meleeBy(event, ctx.owner())) {
            return;
        }
        int ticks = (int) ctx.params().ticks("seconds");
        int amplifier = Math.max(0, ctx.params().integer("level") - 1);
        if (ticks <= 0) {
            return;
        }
        victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, ticks, amplifier, false, true, true));
        readyAt = ctx.tick() + ctx.params().ticks("cooldown");
    }
}
