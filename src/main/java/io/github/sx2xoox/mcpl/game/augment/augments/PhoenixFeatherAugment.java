package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.core.arena.Spread;
import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** 불사조의 깃털: survive one deadly blow. */
public final class PhoenixFeatherAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("phoenix_feather", "불사조의 깃털", AugmentTier.PRISM,
                    AugmentKind.SPECIAL, Material.BLAZE_POWDER)
            .description("한 번, 죽을 만큼의 피해를 받으면 대신 체력 {health}로 되살아나 {shield}초 동안 거의 무적이 되고"
                    + " {regen}초 동안 재생합니다. (공허에 떨어졌다면 땅 위로 돌아옵니다)")
            .param("health", 10)
            .param("shield", 3)
            .param("regen", 6)
            .build();

    private final AugmentContext ctx;
    private boolean used;

    public PhoenixFeatherAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    /** True once the revive was spent. */
    public boolean used() {
        return used;
    }

    @Override
    public boolean onLethalDamage(EntityDamageEvent event) {
        if (used) {
            return false;
        }
        used = true;
        event.setCancelled(true);
        Player player = ctx.player();
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? 20.0 : max.getValue();
        player.setHealth(Math.max(1.0, Math.min(cap, ctx.params().num("health"))));
        player.setFireTicks(0);
        player.setFallDistance(0f);
        int shield = (int) ctx.params().ticks("shield");
        int regen = (int) ctx.params().ticks("regen");
        if (shield > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, shield, 4, false, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, shield, 0, false, true, true));
        }
        if (regen > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, regen, 1, false, true, true));
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID
                || player.getLocation().getY() < player.getWorld().getMinHeight()) {
            Location loc = player.getLocation();
            Location ground = Spread.surface(player.getWorld(), loc.getBlockX(), loc.getBlockZ());
            Location target = ground != null ? ground : player.getWorld().getSpawnLocation();
            target.setYaw(loc.getYaw());
            target.setPitch(loc.getPitch());
            player.teleport(target);
        }
        player.getWorld().spawnParticle(Particle.FLAME, player.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1.2f);
        ctx.message("augment-fx.phoenix");
        return true;
    }
}
