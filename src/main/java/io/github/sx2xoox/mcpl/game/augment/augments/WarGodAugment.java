package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityRegainHealthEvent;

/** 전쟁의 신: every enemy player killed adds attack damage (up to a cap) and heals. */
public final class WarGodAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("war_god", "전쟁의 신", AugmentTier.PRISM,
                    AugmentKind.COMBAT, Material.NETHERITE_SWORD)
            .description("적 플레이어를 처치할 때마다 공격력이 {damage}씩(최대 {max_stacks}번) 늘어나고 체력을 {heal}"
                    + " 회복합니다.")
            .param("damage", 1)
            .param("max_stacks", 5)
            .param("heal", 4)
            .build();

    private final AugmentContext ctx;
    private int stacks;

    public WarGodAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onKill(LivingEntity victim) {
        LivingEntity player = ctx.owner();
        if (stacks < ctx.params().integer("max_stacks")) {
            stacks++;
            ctx.addModifier(Attribute.ATTACK_DAMAGE, stacks * ctx.params().num("damage"),
                    AttributeModifier.Operation.ADD_NUMBER);
        }
        double heal = ctx.params().num("heal");
        if (heal > 0 && !player.isDead()) {
            player.heal(heal, EntityRegainHealthEvent.RegainReason.MAGIC);
        }
        player.getWorld().playSound(player, Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.2f);
        ctx.actionBar("augment-fx.war-god", Messages.tag("stacks", stacks));
    }
}
