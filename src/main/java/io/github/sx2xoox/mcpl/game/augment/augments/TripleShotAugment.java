package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/** 삼중 사격: a bow and arrows; every shot splits into three arrows. */
public final class TripleShotAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("triple_shot", "삼중 사격", AugmentTier.GOLD,
                    AugmentKind.COMBAT, Material.SPECTRAL_ARROW)
            .description("활 1개와 화살 {arrows}개를 받습니다. 활이나 쇠뇌를 쏘면 좌우 {angle}도로 화살 2발이 더 나가며,"
                    + " 추가 화살은 피해가 {damage}%이고 주울 수 없습니다.")
            .param("arrows", 32)
            .param("angle", 10)
            .param("damage", 60)
            .build();

    private final AugmentContext ctx;

    public TripleShotAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.give(ItemStack.of(Material.BOW));
        ctx.give(Items.stacks(Material.ARROW, ctx.params().integer("arrows")));
    }

    @Override
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow main)) {
            return;
        }
        Player player = ctx.player();
        Vector velocity = main.getVelocity();
        double angle = Math.toRadians(ctx.params().num("angle"));
        double damage = main.getDamage() * Math.max(0.0, ctx.params().fraction("damage"));
        for (int side = -1; side <= 1; side += 2) {
            Vector direction = velocity.clone().rotateAroundY(angle * side);
            Arrow extra = main.getWorld().spawn(main.getLocation(), Arrow.class, arrow -> {
                arrow.setShooter(player);
                arrow.setVelocity(direction);
                arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                arrow.setDamage(damage);
                arrow.setCritical(main.isCritical());
            });
            ctx.track(extra);
        }
    }
}
