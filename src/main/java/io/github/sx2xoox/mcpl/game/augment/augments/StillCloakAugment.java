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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/** 잠복: standing still for a while makes the owner invisible until they move, attack or get hurt. */
public final class StillCloakAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("still_cloak", "잠복", AugmentTier.GOLD,
                    AugmentKind.SPECIAL, Material.PHANTOM_MEMBRANE)
            .description("{seconds}초 동안 가만히 있으면 투명해집니다 (입은 갑옷과 든 물건은 보임). 움직이거나 공격하거나"
                    + " 피해를 받으면 풀립니다.")
            .param("seconds", 3)
            .build();

    /** Movement (blocks, squared) that still counts as standing still. */
    private static final double STILL = 0.01 * 0.01;

    private final AugmentContext ctx;
    private @Nullable Location last;
    private long stillSince;
    private boolean hidden;

    public StillCloakAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    /** True while the cloak made the owner invisible. */
    public boolean hidden() {
        return hidden;
    }

    @Override
    public void onGrant() {
        last = ctx.player().getLocation();
        stillSince = ctx.tick();
    }

    @Override
    public void onTick() {
        Player player = ctx.player();
        Location now = player.getLocation();
        if (last == null || last.getWorld() != now.getWorld() || last.distanceSquared(now) > STILL) {
            reveal();
            last = now;
            return;
        }
        if (!hidden && ctx.tick() - stillSince >= ctx.params().ticks("seconds")) {
            hidden = true;
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0,
                    true, false, true));
            player.getWorld().playSound(now, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.4f, 0.6f);
        }
        if (hidden && ctx.tick() % 40 == 0) {
            ctx.actionBar("augment-fx.hidden");
        }
    }

    @Override
    public void afterOutgoingDamage(LivingEntity victim, double finalDamage) {
        reveal();
    }

    @Override
    public void onIncomingDamage(EntityDamageEvent event) {
        reveal();
    }

    @Override
    public void onRemove() {
        reveal();
    }

    /** Back to visible; the stillness timer starts again. */
    private void reveal() {
        stillSince = ctx.tick();
        if (!hidden) {
            return;
        }
        hidden = false;
        Player player = ctx.player();
        PotionEffect effect = player.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (effect != null && effect.isInfinite()) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }
}
