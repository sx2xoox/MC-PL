package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * 추적 나침반: a compass that points at the nearest enemy player once PvP is on. Moves the owner's compass target
 * (every plain compass follows it; no item is rewritten, so a held compass does not bob) and puts the old one back
 * when the augment is removed.
 */
public final class HunterCompassAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("hunter_compass", "추적 나침반", AugmentTier.SILVER,
                    AugmentKind.SPECIAL, Material.COMPASS)
            .description("가장 가까운 적 플레이어를 가리키는 나침반을 받습니다. PvP가 켜진 뒤에 작동하며 {interval}초마다"
                    + " 방향을 고칩니다.")
            .param("interval", 1)
            .build();

    private final AugmentContext ctx;
    private @Nullable Location original;

    public HunterCompassAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        original = ctx.player().getCompassTarget();
        ItemStack compass = ItemStack.of(Material.COMPASS);
        compass.setData(DataComponentTypes.ITEM_NAME, Component.text(INFO.name(), INFO.tier().color()));
        ctx.give(compass);
    }

    @Override
    public void onTick() {
        long every = Math.max(1, ctx.params().ticks("interval"));
        if (ctx.tick() % every != 0) {
            return;
        }
        Player owner = ctx.player();
        LivingEntity target = ctx.pvp() ? Nearby.nearestEnemy(ctx) : null;
        if (target != null) {
            owner.setCompassTarget(target.getLocation());
        }
        if (!holdsCompass(owner)) {
            return;
        }
        if (!ctx.pvp()) {
            ctx.actionBar("augment-fx.compass-idle");
        } else if (target != null) {
            ctx.actionBar("augment-fx.compass-target", Placeholder.unparsed("distance",
                    Long.toString(Math.round(target.getLocation().distance(owner.getLocation())))));
        }
    }

    private static boolean holdsCompass(Player player) {
        return isPlainCompass(player.getInventory().getItemInMainHand())
                || isPlainCompass(player.getInventory().getItemInOffHand());
    }

    private static boolean isPlainCompass(ItemStack item) {
        return item.getType() == Material.COMPASS && !item.hasData(DataComponentTypes.LODESTONE_TRACKER);
    }

    @Override
    public void onRemove() {
        if (original != null) {
            ctx.player().setCompassTarget(original);
        }
    }
}
