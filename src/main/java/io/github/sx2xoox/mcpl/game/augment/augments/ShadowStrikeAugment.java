package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 그림자 일격: hold sneak for a while to turn invisible; the next melee hit within the window deals multiplied damage
 * and reveals the owner.
 */
public final class ShadowStrikeAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("shadow_strike", "그림자 일격", AugmentTier.PRISM,
                    AugmentKind.COMBAT, Material.ECHO_SHARD)
            .description("웅크리기(Shift)를 {sneak}초 동안 누르고 있으면 {duration}초 동안 투명해지고, 그동안 다음 근접"
                    + " 공격의 피해가 {multiplier}%가 됩니다 (공격하면 투명 해제, 재사용 {cooldown}초).")
            .param("sneak", 3)
            .param("duration", 5)
            .param("multiplier", 200)
            .param("cooldown", 10)
            .build();

    private final AugmentContext ctx;
    private int sneakTicks;
    /** Tick until which the strike is armed, or -1. */
    private long armedUntil = -1;
    private long readyAt;

    public ShadowStrikeAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    /** True while the next melee hit is empowered. */
    private boolean armed() {
        return armedUntil >= 0;
    }

    @Override
    public void onTick() {
        Player player = ctx.player();
        long now = ctx.tick();
        if (armed()) {
            if (now > armedUntil) {
                disarm(player);
            }
            return;
        }
        if (now < readyAt || !player.isSneaking() || player.getGameMode() == GameMode.SPECTATOR) {
            sneakTicks = 0;
            return;
        }
        if (++sneakTicks < Math.max(1, ctx.params().ticks("sneak"))) {
            return;
        }
        sneakTicks = 0;
        int duration = (int) Math.max(1, ctx.params().ticks("duration"));
        armedUntil = now + duration;
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, duration, 0, false, false, true));
        player.getWorld().spawnParticle(Particle.LARGE_SMOKE, player.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3,
                0.02);
        player.playSound(player, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 0.8f, 1.2f);
        ctx.actionBar("augment-fx.shadow-ready");
    }

    @Override
    public void onOutgoingDamage(EntityDamageEvent event, LivingEntity victim) {
        Player player = ctx.player();
        if (!armed() || !Hits.meleeBy(event, player)) {
            return;
        }
        event.setDamage(event.getDamage() * Math.max(0.0, ctx.params().fraction("multiplier")));
        disarm(player);
        readyAt = ctx.tick() + ctx.params().ticks("cooldown");
        victim.getWorld().spawnParticle(Particle.SWEEP_ATTACK, victim.getLocation().add(0, 1, 0), 1);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.5f);
    }

    @Override
    public void onRemove() {
        if (armed()) {
            disarm(ctx.player());
        }
    }

    /** Ends the strike and the invisibility it gave (a potion of invisibility, which shows particles, stays). */
    private void disarm(Player player) {
        armedUntil = -1;
        sneakTicks = 0;
        PotionEffect effect = player.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (effect != null && !effect.hasParticles()) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }
}
