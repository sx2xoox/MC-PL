package io.github.sx2xoox.mcpl.game.superpower.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.destroystokyo.paper.ClientOption;

import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.game.superpower.support.world.ClientMovement;
import io.github.sx2xoox.mcpl.game.superpower.support.world.DamageSimulation;
import io.github.sx2xoox.mcpl.game.superpower.support.world.EntityViewer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.github.sx2xoox.mcpl.game.superpower.support.world.Hurtable;
import io.github.sx2xoox.mcpl.game.superpower.support.world.RayTracing;
import io.github.sx2xoox.mcpl.game.superpower.support.world.TestSkinParts;
import io.github.sx2xoox.mcpl.game.superpower.support.world.VanillaAttributes;
import io.github.sx2xoox.mcpl.game.superpower.support.world.WorldPhysics;
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A {@link PlayerMock} filling the gaps of MockBukkit the superpower runtime and the abilities rely on:
 *
 * <ul>
 *     <li>item cooldown groups, per-player world borders and healing;</li>
 *     <li>{@code damage(double, DamageSource)} through {@link DamageSimulation} (server-like causes, i-frames,
 *     resistance and absorption);</li>
 *     <li>a body whose size follows the {@link Attribute#SCALE} attribute (hitbox, height, eye height), like a real
 *     player's;</li>
 *     <li>line of sight through block ray tracing ({@link RayTracing#hasLineOfSight}), body yaw and
 *     {@link #isInLava()} in a {@link FlatWorld};</li>
 *     <li>the client options a default client reports ({@link #getClientOption}) and a settable ping
 *     ({@link #setPing}, 0 by default);</li>
 *     <li>the particles sent to this player alone ({@link #particles()});</li>
 *     <li>movement input: a settable {@link #getCurrentInput()} ({@link #setInput}), a client sending new input with
 *     its {@link PlayerInputEvent} ({@link #sendInput}) and arm swings ({@link #swingArm});</li>
 *     <li>server-sent velocities fire a {@link PlayerVelocityEvent} ({@link #setVelocity}; the player's own movement
 *     goes through {@link #setClientVelocity});</li>
 *     <li>Paper's per-viewer entity visibility with {@code setVisibleByDefault} ({@link #canSee(Entity)},
 *     {@link EntityViewer});</li>
 *     <li>the Adventure titles and action bars it was sent ({@link #titles()}, {@link #actionBars()}).</li>
 * </ul>
 */
public final class TestPlayer extends PlayerMock implements Hurtable, EntityViewer, ClientMovement {

    /** A particle sent to this player alone ({@code player.spawnParticle}). */
    public record SentParticle(org.bukkit.Particle particle, Location at, int count) {
    }

    private static final double WIDTH = 0.6;
    private static final double HEIGHT = 1.8;
    private static final double SNEAK_HEIGHT = 1.5;
    private static final double EYE_HEIGHT = 1.62;
    private static final double SNEAK_EYE_HEIGHT = 1.27;

    private final Map<Key, Integer> cooldowns = new HashMap<>();
    private @Nullable WorldBorder worldBorder;
    private @Nullable Float bodyYaw;
    private double lastHurt;
    private int skinParts = TestSkinParts.ALL;
    private int ping;
    private final List<SentParticle> particles = new ArrayList<>();
    private TestInput input = TestInput.NONE;
    private final List<Title> titles = new ArrayList<>();
    private int titleClears;
    /** Entities whose visibility this player inverted (hidden although visible by default, or the reverse). */
    private final Map<UUID, Set<Plugin>> invertedEntities = new HashMap<>();

    public TestPlayer(ServerMock server, String name) {
        super(server, name);
        VanillaAttributes.completePlayer(attributes);
    }

    /**
     * Like Paper since 1.21.10 ({@code CraftPlayer#teleport0}): a teleport within the world keeps the passengers riding
     * (mounted displays; MockBukkit still refuses such a teleport unless the deprecated RETAIN_PASSENGERS is passed),
     * and a player carrying passengers cannot change worlds.
     */
    @Override
    @SuppressWarnings("removal")
    public boolean teleport(Location location, PlayerTeleportEvent.TeleportCause cause, TeleportFlag... flags) {
        if (!hasPassengers()) {
            return super.teleport(location, cause, flags);
        }
        if (location.getWorld() != null && !location.getWorld().equals(getWorld())) {
            return false;
        }
        TeleportFlag[] keep = Arrays.copyOf(flags, flags.length + 1);
        keep[flags.length] = TeleportFlag.EntityState.RETAIN_PASSENGERS;
        return super.teleport(location, cause, keep);
    }

    /** Like the server: absorption is clamped to the {@link Attribute#MAX_ABSORPTION} attribute. */
    @Override
    public void setAbsorptionAmount(double amount) {
        if (!(amount >= 0) || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount < 0 or non-finite");
        }
        super.setAbsorptionAmount(Math.min(amount, getAttribute(Attribute.MAX_ABSORPTION).getValue()));
    }

    @Override
    public void setCooldown(Key cooldownGroup, int ticks) {
        if (ticks <= 0) {
            cooldowns.remove(cooldownGroup);
        } else {
            cooldowns.put(cooldownGroup, ticks);
        }
    }

    @Override
    public int getCooldown(Key cooldownGroup) {
        return cooldowns.getOrDefault(cooldownGroup, 0);
    }

    @Override
    public void setWorldBorder(@Nullable WorldBorder border) {
        this.worldBorder = border;
    }

    @Override
    public @Nullable WorldBorder getWorldBorder() {
        return worldBorder;
    }

    @Override
    public double lastHurt() {
        return lastHurt;
    }

    @Override
    public void lastHurt(double amount) {
        this.lastHurt = amount;
    }

    @Override
    public double getLastDamage() {
        return lastHurt;
    }

    @Override
    public void setLastDamage(double damage) {
        this.lastHurt = damage;
    }

    @Override
    public void damage(double amount, DamageSource damageSource) {
        DamageSimulation.damage(this, amount, damageSource);
    }

    @Override
    public void heal(double amount, EntityRegainHealthEvent.RegainReason reason) {
        if (!isDead()) {
            double max = getAttribute(Attribute.MAX_HEALTH).getValue();
            setHealth(Math.min(max, getHealth() + amount));
        }
    }

    // ------------------------------------------------------------------ client

    /** Sets the skin layers the client reports (raw bits, see {@link TestSkinParts}). */
    public void setClientSkinParts(int raw) {
        this.skinParts = raw;
    }

    /**
     * Sets the ping (round trip, in milliseconds) the server reports for this player, as a lagging connection would
     * (0 by default, like a local client).
     */
    public void setPing(int millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("ping < 0");
        }
        this.ping = millis;
    }

    @Override
    public int getPing() {
        return ping;
    }

    /** The options of a default Korean client: right-handed, full chat, the skin layers set here (all by default). */
    @Override
    public <T> T getClientOption(ClientOption<T> option) {
        Object value;
        if (option == ClientOption.SKIN_PARTS) {
            value = new TestSkinParts(skinParts);
        } else if (option == ClientOption.MAIN_HAND) {
            value = getMainHand();
        } else if (option == ClientOption.LOCALE) {
            value = "ko_kr";
        } else if (option == ClientOption.VIEW_DISTANCE) {
            value = 10;
        } else if (option == ClientOption.CHAT_VISIBILITY) {
            value = ClientOption.ChatVisibility.FULL;
        } else if (option == ClientOption.PARTICLE_VISIBILITY) {
            value = ClientOption.ParticleVisibility.ALL;
        } else if (option == ClientOption.TEXT_FILTERING_ENABLED) {
            value = false;
        } else {
            value = true; // chat colours, server listings
        }
        return option.getType().cast(value);
    }

    // ------------------------------------------------------------------ body

    /** The {@link Attribute#SCALE} value, 1 without the attribute. */
    public double scale() {
        AttributeInstance scale = getAttribute(Attribute.SCALE);
        return scale == null ? 1.0 : Math.max(0.0625, scale.getValue());
    }

    @Override
    public double getHeight() {
        return (isSneaking() ? SNEAK_HEIGHT : HEIGHT) * scale();
    }

    @Override
    public double getWidth() {
        return WIDTH * scale();
    }

    @Override
    public double getEyeHeight() {
        return (isSneaking() ? SNEAK_EYE_HEIGHT : EYE_HEIGHT) * scale();
    }

    @Override
    public double getEyeHeight(boolean ignorePose) {
        return ignorePose ? EYE_HEIGHT * scale() : getEyeHeight();
    }

    @Override
    public Location getEyeLocation() {
        return getLocation().add(0, getEyeHeight(), 0);
    }

    @Override
    public BoundingBox getBoundingBox() {
        Location at = getLocation();
        double half = getWidth() / 2;
        return new BoundingBox(at.getX() - half, at.getY(), at.getZ() - half, at.getX() + half,
                at.getY() + getHeight(), at.getZ() + half);
    }

    @Override
    public float getBodyYaw() {
        return bodyYaw != null ? bodyYaw : getLocation().getYaw();
    }

    @Override
    public void setBodyYaw(float bodyYaw) {
        this.bodyYaw = bodyYaw;
    }

    // ------------------------------------------------------------------ world queries

    @Override
    public boolean hasLineOfSight(Entity other) {
        return RayTracing.hasLineOfSight(this, other);
    }

    @Override
    public boolean hasLineOfSight(Location location) {
        if (!getWorld().equals(location.getWorld())) {
            return false;
        }
        Vector from = getEyeLocation().toVector();
        return RayTracing.clearLine(getWorld(), from, location.toVector(), 128.0);
    }

    @Override
    public boolean isInLava() {
        return getWorld() instanceof FlatWorld && FlatWorld.isIn(this, Material.LAVA);
    }

    // Not final: getAttribute runs from the PlayerMock constructor, before field initializers.
    private @Nullable Map<Attribute, AttributeInstance> strictAttributes;

    /** Like vanilla, adding a modifier whose key is already applied throws (MockBukkit silently replaces it). */
    @Override
    public @Nullable AttributeInstance getAttribute(Attribute attribute) {
        AttributeInstance real = super.getAttribute(attribute);
        if (real == null || real instanceof io.github.sx2xoox.mcpl.game.superpower.support.world.StrictAttributeInstance) {
            return real;
        }
        if (strictAttributes == null) {
            strictAttributes = new HashMap<>();
        }
        return strictAttributes.computeIfAbsent(attribute,
                ignored -> new io.github.sx2xoox.mcpl.game.superpower.support.world.StrictAttributeInstance(real));
    }

    /** Like Paper, refuses particles with missing or mistyped data (MockBukkit accepts anything). */
    @Override
    public <T> void spawnParticle(org.bukkit.Particle particle, double x, double y, double z, int count,
                                  double offsetX, double offsetY, double offsetZ, double speedX, double speedY,
                                  double speedZ, @Nullable T data, boolean force,
                                  org.bukkit.Particle.RandomizationType randomizationType) {
        io.github.sx2xoox.mcpl.game.superpower.support.world.PaperRules.checkParticle(particle, data);
        particles.add(new SentParticle(particle, new Location(getWorld(), x, y, z), count));
        super.spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, speedX, speedY, speedZ, data, force,
                randomizationType);
    }

    /** The particles sent to this player alone so far (oldest first). */
    public List<SentParticle> particles() {
        return particles;
    }

    /**
     * Like Paper, a non-finite velocity throws (MockBukkit stores it), and a velocity set by plugin code reaches the
     * client through a {@link PlayerVelocityEvent} (on a server it fires when the velocity is sent, later in the same
     * tick; here right away). A cancelled event leaves the velocity unchanged, a changed one is applied instead. The
     * player's own movement (the {@link WorldPhysics} step, a test walking it) uses {@link #setClientVelocity}.
     */
    @Override
    public void setVelocity(Vector velocity) {
        io.github.sx2xoox.mcpl.game.superpower.support.world.PaperRules.checkVelocity(velocity);
        PlayerVelocityEvent event = new PlayerVelocityEvent(this, velocity.clone());
        if (!event.callEvent()) {
            return;
        }
        super.setVelocity(event.getVelocity());
    }

    /** The player's own movement: sets the velocity without a {@link PlayerVelocityEvent}. */
    @Override
    public void setClientVelocity(Vector velocity) {
        io.github.sx2xoox.mcpl.game.superpower.support.world.PaperRules.checkVelocity(velocity);
        super.setVelocity(velocity);
    }

    // ------------------------------------------------------------------ input

    /** What {@link #getCurrentInput()} returns (MockBukkit throws); no event, see {@link #sendInput}. */
    public void setInput(Input input) {
        this.input = TestInput.copyOf(input);
    }

    @Override
    public Input getCurrentInput() {
        return input;
    }

    /**
     * The client sends new input, as on a server: a {@link PlayerInputEvent} fires while {@link #getCurrentInput()} still
     * returns the old input, then the new one is stored. Nothing happens when the keys did not change (clients only
     * send changes).
     */
    public void sendInput(Input input) {
        TestInput next = TestInput.copyOf(input);
        if (next.equals(this.input)) {
            return;
        }
        getServer().getPluginManager().callEvent(new PlayerInputEvent(this, next));
        this.input = next;
    }

    /** {@link #sendInput} with exactly these keys held (none: everything released). */
    public void sendInput(InputKey... keys) {
        sendInput(TestInput.of(keys));
    }

    /** The client swings the main arm (an attack or a click), as the server reports it. */
    public void swingArm() {
        swingArm(EquipmentSlot.HAND);
    }

    /** The client swings {@code hand} ({@link EquipmentSlot#HAND} or {@link EquipmentSlot#OFF_HAND}). */
    public void swingArm(EquipmentSlot hand) {
        getServer().getPluginManager().callEvent(new PlayerArmSwingEvent(this, hand));
    }

    /**
     * The client attacks {@code target} in melee, as a server reports it before any damage logic: a
     * {@code PrePlayerAttackEntityEvent} and the arm swing (no damage is dealt; use {@code attack(Entity)} of
     * MockBukkit or damage helpers for that).
     */
    public void attackAttempt(Entity target) {
        getServer().getPluginManager().callEvent(new PrePlayerAttackEntityEvent(this, target, true));
        swingArm();
    }

    // ------------------------------------------------------------------ titles

    /** Every title shown to this player (MockBukkit drops Adventure titles), oldest first. */
    public List<Title> titles() {
        return titles;
    }

    /** How often this player's title was cleared. */
    public int titleClears() {
        return titleClears;
    }

    @Override
    public void showTitle(Title title) {
        titles.add(title);
        super.showTitle(title);
    }

    @Override
    public void clearTitle() {
        titleClears++;
        super.clearTitle();
    }

    /** Every action bar sent to this player since the last call, oldest first (drains MockBukkit's queue). */
    public List<Component> actionBars() {
        List<Component> bars = new ArrayList<>();
        Component bar;
        while ((bar = nextActionBar()) != null) {
            bars.add(bar);
        }
        return bars;
    }

    // ------------------------------------------------------------------ per-viewer entity visibility

    /**
     * Like Paper's {@code CraftPlayer}: hiding an entity visible by default hides it from this player, hiding one hidden
     * by default undoes this player's show of it (players themselves keep MockBukkit's {@code hidePlayer} handling).
     */
    @Override
    public void hideEntity(Plugin plugin, Entity entity) {
        if (entity instanceof Player) {
            super.hideEntity(plugin, entity);
            return;
        }
        if (equals(entity)) {
            return;
        }
        if (EntityViewer.visibleByDefault(entity)) {
            invert(plugin, entity);
        } else {
            uninvert(plugin, entity);
        }
    }

    /** Like Paper: showing an entity hidden by default shows it to this player alone. */
    @Override
    public void showEntity(Plugin plugin, Entity entity) {
        if (entity instanceof Player) {
            super.showEntity(plugin, entity);
            return;
        }
        if (equals(entity)) {
            return;
        }
        if (EntityViewer.visibleByDefault(entity)) {
            uninvert(plugin, entity);
        } else {
            invert(plugin, entity);
        }
    }

    /** Like Paper: visible by default, unless this player hid it; hidden by default, unless this player was shown it. */
    @Override
    public boolean canSee(Entity entity) {
        if (entity instanceof Player) {
            return super.canSee(entity);
        }
        return equals(entity)
                || EntityViewer.visibleByDefault(entity) ^ invertedEntities.containsKey(entity.getUniqueId());
    }

    @Override
    public void resetVisibility(Entity entity) {
        invertedEntities.remove(entity.getUniqueId());
    }

    private void invert(Plugin plugin, Entity entity) {
        invertedEntities.computeIfAbsent(entity.getUniqueId(), id -> new HashSet<>()).add(plugin);
    }

    private void uninvert(Plugin plugin, Entity entity) {
        Set<Plugin> plugins = invertedEntities.get(entity.getUniqueId());
        if (plugins != null && plugins.remove(plugin) && plugins.isEmpty()) {
            invertedEntities.remove(entity.getUniqueId());
        }
    }

    /** Like Paper, health outside 0..max (or NaN) throws (MockBukkit clamps it). */
    @Override
    public void setHealth(double health) {
        io.github.sx2xoox.mcpl.game.superpower.support.world.PaperRules.checkHealth(health, getMaxHealth());
        super.setHealth(health);
    }

    /** Like Paper, non-finite yaw or pitch throws. */
    @Override
    public void setRotation(float yaw, float pitch) {
        io.github.sx2xoox.mcpl.game.superpower.support.world.PaperRules.checkRotation(yaw, pitch);
        super.setRotation(yaw, pitch);
    }
}
