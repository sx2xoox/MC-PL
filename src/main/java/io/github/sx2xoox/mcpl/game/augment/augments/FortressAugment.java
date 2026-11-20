package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;

/** 강철 요새: armor, armor toughness and knockback resistance on top of whatever is worn. */
public final class FortressAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("fortress", "강철 요새", AugmentTier.PRISM,
                    AugmentKind.STAT, Material.NETHERITE_CHESTPLATE)
            .description("입은 갑옷과 별개로 방어력 +{armor}, 방어 강도 +{toughness}, 밀치기 저항 {knockback}%를 얻습니다.")
            .param("armor", 4)
            .param("toughness", 4)
            .param("knockback", 50)
            .forBots()
            .build();

    private final AugmentContext ctx;

    public FortressAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.addModifier(Attribute.ARMOR, ctx.params().num("armor"), AttributeModifier.Operation.ADD_NUMBER);
        ctx.addModifier(Attribute.ARMOR_TOUGHNESS, ctx.params().num("toughness"),
                AttributeModifier.Operation.ADD_NUMBER);
        ctx.addModifier(Attribute.KNOCKBACK_RESISTANCE, ctx.params().fraction("knockback"),
                AttributeModifier.Operation.ADD_NUMBER);
    }
}
