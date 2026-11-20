package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

/** 풍성한 식탁: eating heals and fills more saturation. */
public final class FeastAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("feast", "풍성한 식탁", AugmentTier.SILVER,
                    AugmentKind.STAT, Material.COOKED_BEEF)
            .description("음식을 먹을 때마다 체력을 {heal} 회복하고 포만도가 {saturation} 더 찹니다.")
            .param("heal", 2)
            .param("saturation", 4)
            .build();

    private final AugmentContext ctx;
    /** Meals whose extra saturation is added on the next tick (after the food itself, which caps saturation). */
    private int meals;

    public FeastAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!event.getItem().getType().isEdible()) {
            return;
        }
        Player player = ctx.player();
        double heal = ctx.params().num("heal");
        if (heal > 0 && !player.isDead()) {
            player.heal(heal, EntityRegainHealthEvent.RegainReason.EATING);
        }
        meals++;
    }

    @Override
    public void onTick() {
        if (meals == 0) {
            return;
        }
        Player player = ctx.player();
        float extra = (float) (ctx.params().num("saturation") * meals);
        meals = 0;
        // like vanilla, saturation never exceeds the food level
        player.setSaturation(Math.max(0f, Math.min(player.getFoodLevel(), player.getSaturation() + extra)));
    }
}
