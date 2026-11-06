package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 화살통: arrows now, and a chance that a bow shot gives its arrow back (the shot arrow then cannot be picked up, like
 * Infinity).
 */
public final class QuiverAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("quiver", "화살통", AugmentTier.SILVER,
                    AugmentKind.RESOURCE, Material.ARROW)
            .description("화살 {arrows}개를 바로 받고, 활을 쏠 때 {chance}% 확률로 화살을 쓰지 않습니다.")
            .param("arrows", 64)
            .param("chance", 30)
            .build();

    private final AugmentContext ctx;

    public QuiverAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.give(Items.stacks(Material.ARROW, ctx.params().integer("arrows")));
    }

    @Override
    public void onShootBow(EntityShootBowEvent event) {
        ItemStack consumed = event.getConsumable();
        if (!event.shouldConsumeItem() || consumed == null || consumed.isEmpty()
                || !(event.getProjectile() instanceof AbstractArrow arrow)
                || arrow.getPickupStatus() != AbstractArrow.PickupStatus.ALLOWED
                || ThreadLocalRandom.current().nextDouble() >= ctx.params().fraction("chance")) {
            return;
        }
        // the shot is about to take one arrow: hand it back, and keep the flying one from being picked up twice
        arrow.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
        ctx.give(consumed.asOne());
    }
}
