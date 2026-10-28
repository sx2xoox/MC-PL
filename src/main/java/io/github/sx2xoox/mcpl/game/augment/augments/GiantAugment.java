package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;

/** 거인화: bigger, tougher and slower. */
public final class GiantAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("giant", "거인화", AugmentTier.PRISM,
                    AugmentKind.TRADEOFF, Material.IRON_GOLEM_SPAWN_EGG)
            .description("몸이 {scale}% 커지고 최대 체력이 {health} 늘어나는 대신(늘어난 만큼 바로 회복), 이동 속도가 {slow}%"
                    + " 느려집니다.")
            .param("scale", 30)
            .param("health", 8)
            .param("slow", 10)
            .forBots()
            .build();

    private final AugmentContext ctx;

    public GiantAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.addModifier(Attribute.SCALE, ctx.params().fraction("scale"), AttributeModifier.Operation.ADD_SCALAR);
        double health = ctx.params().num("health");
        ctx.addModifier(Attribute.MAX_HEALTH, health, AttributeModifier.Operation.ADD_NUMBER);
        if (health > 0) {
            ctx.owner().heal(health);
        }
        ctx.addModifier(Attribute.MOVEMENT_SPEED, -Math.max(0.0, Math.min(1.0, ctx.params().fraction("slow"))),
                AttributeModifier.Operation.ADD_SCALAR);
    }
}
