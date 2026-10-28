package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

/** 재생의 심장: out of combat for a while, health regenerates every second. */
public final class RegenHeartAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("regen_heart", "재생의 심장", AugmentTier.PRISM,
                    AugmentKind.STAT, Material.HEART_OF_THE_SEA)
            .description("{delay}초 동안 피해를 받지 않으면 1초마다 체력을 {heal}씩 회복합니다.")
            .param("delay", 5)
            .param("heal", 1)
            .build();

    private final AugmentContext ctx;
    private long lastHurt;

    public RegenHeartAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        lastHurt = ctx.tick();
    }

    @Override
    public void onIncomingDamage(EntityDamageEvent event) {
        lastHurt = ctx.tick();
    }

    @Override
    public void onTick() {
        long calm = ctx.tick() - lastHurt - ctx.params().ticks("delay");
        if (calm < 0 || calm % 20 != 0) {
            return;
        }
        Player player = ctx.player();
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double heal = ctx.params().num("heal");
        if (heal > 0 && !player.isDead() && max != null && player.getHealth() < max.getValue()) {
            player.heal(heal, EntityRegainHealthEvent.RegainReason.MAGIC_REGEN);
        }
    }
}
