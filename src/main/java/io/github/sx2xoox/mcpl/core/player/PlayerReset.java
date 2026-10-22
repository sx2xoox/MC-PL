package io.github.sx2xoox.mcpl.core.player;

import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

/**
 * Prepares a participant for a match: empty inventory, full health and food, no effects, no experience, adventure
 * mode, no flight, no fire, default speeds and none of this plugin's attribute modifiers. The hub calls it right
 * after taking the player's {@link PlayerSnapshot}; modes may call it again (e.g. before a respawn).
 */
public final class PlayerReset {

    private PlayerReset() {
    }

    /**
     * @param pluginNamespace attribute modifiers with this key namespace are removed
     *                        ({@link SnapshotStore#namespace()})
     */
    public static void reset(Player player, String pluginNamespace) {
        player.closeInventory();
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        player.eject();
        player.getInventory().clear();
        player.setItemOnCursor(null);
        PlayerSnapshot.stripPluginModifiers(player, pluginNamespace);

        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);

        heal(player);
        player.clearActivePotionEffects();
        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0f);
        player.setRemainingAir(player.getMaximumAir());
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
        player.setGlowing(false);
        player.setInvisible(false);
        player.setInvulnerable(false);
    }

    /** Full health (current max health), full food, vanilla saturation, no exhaustion. */
    public static void heal(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
        player.setFoodLevel(20);
        player.setSaturation(5f);
        player.setExhaustion(0f);
    }
}
