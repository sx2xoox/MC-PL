package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

/** 저격수: arrows hitting from far away deal more damage. */
public final class SniperAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("sniper", "저격수", AugmentTier.GOLD,
                    AugmentKind.COMBAT, Material.SPYGLASS)
            .description("{distance}블록 이상 떨어진 적을 화살로 맞히면 피해가 {damage}% 늘어납니다.")
            .param("distance", 20)
            .param("damage", 50)
            .build();

    private final AugmentContext ctx;

    public SniperAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onOutgoingDamage(EntityDamageEvent event, LivingEntity victim) {
        if (Hits.arrow(event) == null) {
            return;
        }
        Player player = ctx.player();
        Location from = player.getLocation();
        Location to = victim.getLocation();
        double distance = ctx.params().num("distance");
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) < distance * distance) {
            return;
        }
        event.setDamage(event.getDamage() * (1.0 + Math.max(0.0, ctx.params().fraction("damage"))));
        player.playSound(player, Sound.ENTITY_ARROW_HIT_PLAYER, 1f, 0.6f);
    }
}
