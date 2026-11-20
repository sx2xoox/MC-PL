package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.event.entity.EntityDamageEvent;

/** 높이뛰기: higher jumps and softer landings. */
public final class HighJumpAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("high_jump", "높이뛰기", AugmentTier.SILVER,
                    AugmentKind.MOBILITY, Material.SLIME_BALL)
            .description("점프하는 힘이 {jump}% 세지고, 떨어질 때 받는 피해가 {reduction}% 줄어듭니다.")
            .param("jump", 25)
            .param("reduction", 50)
            .build();

    private final AugmentContext ctx;

    public HighJumpAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.addModifier(Attribute.JUMP_STRENGTH, ctx.params().fraction("jump"), AttributeModifier.Operation.ADD_SCALAR);
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
