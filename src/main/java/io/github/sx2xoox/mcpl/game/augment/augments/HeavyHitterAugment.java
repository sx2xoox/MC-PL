package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;

/** 강타: much stronger melee knockback (the attack knockback attribute, like a Knockback enchantment). */
public final class HeavyHitterAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("heavy_hitter", "강타", AugmentTier.GOLD,
                    AugmentKind.COMBAT, Material.MACE)
            .description("근접 공격의 밀치기가 크게 늘어납니다 (밀치기 마법부여 {knockback}레벨만큼).")
            .param("knockback", 2)
            .build();

    private final AugmentContext ctx;

    public HeavyHitterAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.addModifier(Attribute.ATTACK_KNOCKBACK, ctx.params().num("knockback"),
                AttributeModifier.Operation.ADD_NUMBER);
    }
}
