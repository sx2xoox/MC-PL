package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The two entities of one bot: the <b>body</b>, a {@link Mannequin} with a player's look (skin, name "철수 봇", armor
 * and sword) that is hit, takes damage, wears the augments' attribute modifiers and dies; and the <b>legs</b>, an
 * invisible, silent {@link WanderingTrader} without goals that walks, jumps and swims with Paper's
 * {@link com.destroystokyo.paper.entity.Pathfinder} (which never paths into lava or over cliffs). Every tick the body
 * is put where the legs are. The legs never take damage: hits on them are passed on to the body ({@link BotManager}). Both are tracked by the
 * session and never saved.
 */
public final class BotBody {

    private final Mannequin body;
    private final WanderingTrader legs;

    private BotBody(Mannequin body, WanderingTrader legs) {
        this.body = body;
        this.legs = legs;
    }

    /** Spawns a bot at {@code at}; null if the world refused one of its entities. */
    static @Nullable BotBody spawn(GameSession session, Location at, Component name, @Nullable Component description,
                                   @Nullable String skin, double moveSpeed) {
        World world = at.getWorld();
        if (world == null) {
            return null;
        }
        WanderingTrader legs;
        Mannequin body;
        try {
            legs = world.spawn(at.clone(), WanderingTrader.class, trader -> {
                trader.setPersistent(false);
                trader.setInvisible(true);
                trader.setSilent(true);
                trader.setCollidable(false);
                trader.setRemoveWhenFarAway(false);
                trader.setCanPickupItems(false);
                trader.setDespawnDelay(0);
                AttributeInstance speed = trader.getAttribute(Attribute.MOVEMENT_SPEED);
                if (speed != null) {
                    speed.setBaseValue(moveSpeed);
                }
                AttributeInstance step = trader.getAttribute(Attribute.STEP_HEIGHT);
                if (step != null) {
                    step.setBaseValue(Math.max(step.getBaseValue(), 0.6));
                }
            });
            body = world.spawn(at.clone(), Mannequin.class, mannequin -> {
                mannequin.setPersistent(false);
                mannequin.customName(name);
                mannequin.setCustomNameVisible(true);
                mannequin.setDescription(description);
                mannequin.setImmovable(true);
                mannequin.setGravity(false);
                if (skin != null) {
                    mannequin.setProfile(ResolvableProfile.resolvableProfile().name(skin).build());
                }
            });
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.WARNING, "Could not spawn a bot", e);
            return null;
        }
        session.track(legs);
        session.track(body);
        if (!legs.isValid() || !body.isValid()) {
            legs.remove();
            body.remove();
            return null;
        }
        try {
            // no wandering, trading, drinking potions or fleeing zombies: the controller moves it
            Bukkit.getMobGoals().removeAllGoals(legs);
        } catch (RuntimeException | LinkageError e) {
            session.plugin().getLogger().log(Level.FINE, "Could not clear the goals of a bot", e);
        }
        legs.getPathfinder().setCanFloat(true);
        return new BotBody(body, legs);
    }

    public Mannequin body() {
        return body;
    }

    public WanderingTrader legs() {
        return legs;
    }

    boolean valid() {
        return body.isValid() && !body.isDead() && legs.isValid();
    }

    /** Wears {@code loadout} (armor, sword, shield). */
    void dress(Gear.Loadout loadout) {
        EntityEquipment equipment = body.getEquipment();
        equipment.setHelmet(item(loadout.armor(0), "HELMET"));
        equipment.setChestplate(item(loadout.armor(1), "CHESTPLATE"));
        equipment.setLeggings(item(loadout.armor(2), "LEGGINGS"));
        equipment.setBoots(item(loadout.armor(3), "BOOTS"));
        equipment.setItemInMainHand(sword(loadout.sword()));
        equipment.setItemInOffHand(loadout.shield() ? ItemStack.of(Material.SHIELD) : null);
    }

    /** Holds a bow (while shooting) or the sword. */
    void hold(boolean bow, Gear.Weapon sword) {
        body.getEquipment().setItemInMainHand(bow ? ItemStack.of(Material.BOW) : sword(sword));
    }

    static @Nullable ItemStack item(Gear.Armor armor, String piece) {
        if (armor == Gear.Armor.NONE) {
            return null;
        }
        Material material = Material.matchMaterial(armor.name() + "_" + piece);
        return material == null ? null : ItemStack.of(material);
    }

    static @Nullable ItemStack sword(Gear.Weapon weapon) {
        if (weapon == Gear.Weapon.NONE) {
            return null;
        }
        Material material = Material.matchMaterial((weapon == Gear.Weapon.WOOD ? "WOODEN" : weapon.name()) + "_SWORD");
        return material == null ? null : ItemStack.of(material);
    }

    /** Puts the body where the legs are, facing {@code yaw}/{@code pitch}. */
    void sync(float yaw, float pitch) {
        Location at = legs.getLocation();
        at.setYaw(yaw);
        at.setPitch(pitch);
        body.teleport(at);
        body.setBodyYaw(yaw);
    }

    void remove() {
        legs.remove();
        body.remove();
    }
}
