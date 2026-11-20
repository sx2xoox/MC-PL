package io.github.sx2xoox.mcpl.game.siege.bot;

import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * One 공성전 봇전 bot: who it is (id, name, job, difficulty, side, lane), its brain and state, and while it lives its
 * three entities (like the 증강 봇전 bots): the <b>body</b>, a {@link Mannequin} with a player's look in team-coloured
 * clothes holding its job's weapon, which is hit, takes damage and dies; the <b>legs</b>, an invisible
 * {@link WanderingTrader} without goals that nobody is sent, walking with Paper's pathfinder; and its <b>plate</b>, a
 * {@link TextDisplay} with the same {@code Lv.3 이름 / ■■■□ ❤} as the players' plates. Every tick the body is put where
 * the legs are and the plate above it. All three are tracked by the session and never saved.
 */
final class SiegeBot {

    /** Blocks between the top of the body and the bottom of the plate (as players' plates). */
    static final double PLATE_GAP = 0.35;
    /** Legs path at most this far (blocks). */
    static final double FOLLOW_RANGE = 64;

    final UUID id;
    final String name;
    final BotJob job;
    final SiegeBotDifficulty difficulty;
    final LaneSites.Side side;
    final LaneSites.Lane lane;
    final SiegeBotBrain brain;

    @Nullable Mannequin body;
    @Nullable WanderingTrader legs;
    @Nullable TextDisplay plate;
    Component plateText = Component.empty();

    /** Tick it comes back (while dead), -1 while alive. */
    long respawnAt = -1;
    long protectedUntil;
    long nextThink;
    long nextAttack;
    long nextSkill;
    long lastHurt = Long.MIN_VALUE / 2;
    /** Tick the recall ends, -1 when not recalling. */
    long recallUntil = -1;
    int upgrades;
    SiegeBotBrain.Plan plan;
    /** Where the legs were last sent, and when. */
    @Nullable Location movingTo;
    long movedAt;
    /** Stuck detection. */
    @Nullable Location lastCheck;
    long checkedAt;
    int stuck;
    boolean plateDirty = true;

    SiegeBot(UUID id, String name, BotJob job, SiegeBotDifficulty difficulty, LaneSites.Side side,
             LaneSites.Lane lane, SiegeBotBrain brain) {
        this.id = id;
        this.name = name;
        this.job = job;
        this.difficulty = difficulty;
        this.side = side;
        this.lane = lane;
        this.brain = brain;
        this.plan = SiegeBotBrain.Plan.go(SiegeBotBrain.Action.WAIT, new SiegeBotBrain.Vec(0, 0));
    }

    boolean alive() {
        Mannequin b = body;
        WanderingTrader l = legs;
        return respawnAt < 0 && b != null && b.isValid() && !b.isDead() && l != null && l.isValid();
    }

    double health() {
        Mannequin b = body;
        return b == null ? 0 : b.getHealth();
    }

    double maxHealth() {
        Mannequin b = body;
        AttributeInstance max = b == null ? null : b.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 20 : max.getValue();
    }

    /** Spawns the three entities at {@code at}; false if the world refused one. */
    boolean spawn(GameSession session, Location at, double health, double legSpeed, Color colour) {
        World world = at.getWorld();
        if (world == null) {
            return false;
        }
        remove(session);
        try {
            legs = world.spawn(at.clone(), WanderingTrader.class, trader -> {
                trader.setPersistent(false);
                try {
                    // nobody is sent the legs: nothing to click, nothing to see
                    trader.setVisibleByDefault(false);
                } catch (RuntimeException testServer) {
                    // MockBukkit: invisible is enough
                }
                trader.setInvisible(true);
                trader.setSilent(true);
                trader.setCollidable(false);
                trader.setRemoveWhenFarAway(false);
                trader.setCanPickupItems(false);
                trader.setDespawnDelay(0);
                base(trader, Attribute.MOVEMENT_SPEED, legSpeed);
                AttributeInstance follow = trader.getAttribute(Attribute.FOLLOW_RANGE);
                if (follow != null) {
                    follow.setBaseValue(Math.max(follow.getBaseValue(), FOLLOW_RANGE));
                }
                AttributeInstance step = trader.getAttribute(Attribute.STEP_HEIGHT);
                if (step != null) {
                    step.setBaseValue(Math.max(step.getBaseValue(), 0.6));
                }
            });
            session.track(legs);
            body = world.spawn(at.clone(), Mannequin.class, mannequin -> {
                mannequin.setPersistent(false);
                mannequin.setImmovable(true);
                mannequin.setGravity(false);
                mannequin.setDescription(null);
                base(mannequin, Attribute.MAX_HEALTH, health);
                mannequin.setHealth(health);
                dress(mannequin, colour);
            });
            session.track(body);
            plate = world.spawn(at.clone().add(0, 2.2, 0), TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setShadowed(true);
                d.setSeeThrough(false);
                d.setBackgroundColor(Color.fromARGB(64, 0, 0, 0));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(0.75f);
                d.setTeleportDuration(2);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(0.9f, 0.9f, 0.9f), new AxisAngle4f()));
            });
            session.track(plate);
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.WARNING, "Could not spawn the siege bot " + name, e);
            remove(session);
            return false;
        }
        WanderingTrader walker = legs;
        try {
            // no wandering, trading, drinking potions or fleeing: the manager moves it
            Bukkit.getMobGoals().removeAllGoals(walker);
        } catch (RuntimeException | LinkageError e) {
            session.plugin().getLogger().log(Level.FINE, "Could not clear the goals of a siege bot", e);
        }
        try {
            walker.getPathfinder().setCanFloat(true);
        } catch (RuntimeException e) {
            // A test server without a pathfinder: the mover steps instead.
        }
        plateText = Component.empty();
        plateDirty = true;
        movingTo = null;
        lastCheck = at.clone();
        stuck = 0;
        return true;
    }

    private void dress(Mannequin mannequin, Color colour) {
        EntityEquipment equipment = mannequin.getEquipment();
        Material helmet = job == BotJob.KNIGHT ? Material.IRON_HELMET : Material.LEATHER_HELMET;
        equipment.setHelmet(cosmetic(helmet, colour));
        equipment.setChestplate(cosmetic(Material.LEATHER_CHESTPLATE, colour));
        equipment.setLeggings(cosmetic(Material.LEATHER_LEGGINGS, job == BotJob.ASSASSIN ? Color.fromRGB(0x2B2B33)
                : colour));
        equipment.setBoots(cosmetic(Material.LEATHER_BOOTS, colour));
        equipment.setItemInMainHand(ItemStack.of(job.weapon()));
        Material off = job.offhand();
        equipment.setItemInOffHand(off == null ? null : ItemStack.of(off));
    }

    /** Clothes only: dyed in the team colour, without armor points (a job's defense is its own number). */
    private static ItemStack cosmetic(Material material, Color colour) {
        ItemStack item = ItemStack.of(material);
        try {
            if (material.name().startsWith("LEATHER_")) {
                item.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(colour));
            }
            item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes().build());
        } catch (RuntimeException testServer) {
            // MockBukkit may not know a component: cosmetic only.
        }
        return item;
    }

    static void base(org.bukkit.entity.LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /** Puts the body where the legs are, turned to {@code yaw}, and the plate above it. */
    void sync(float yaw) {
        Mannequin b = body;
        WanderingTrader l = legs;
        if (b == null || l == null) {
            return;
        }
        Location at = l.getLocation();
        at.setYaw(yaw);
        at.setPitch(0);
        b.teleport(at);
        try {
            b.setRotation(yaw, 0);
            b.setBodyYaw(yaw);
        } catch (RuntimeException testServer) {
            // the teleport turned it already
        }
        TextDisplay p = plate;
        if (p != null && p.isValid()) {
            p.teleport(at.clone().add(0, b.getHeight() + PLATE_GAP, 0));
        }
    }

    /** Removes the entities (a death, the end of a round or the match). */
    void remove(GameSession session) {
        for (org.bukkit.entity.Entity entity : new org.bukkit.entity.Entity[] {legs, body, plate}) {
            if (entity != null) {
                entity.remove();
                session.untrack(entity);
            }
        }
        legs = null;
        body = null;
        plate = null;
        recallUntil = -1;
    }
}
