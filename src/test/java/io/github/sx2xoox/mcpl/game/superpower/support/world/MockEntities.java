package io.github.sx2xoox.mcpl.game.superpower.support.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.destroystokyo.paper.SkinParts;
import com.destroystokyo.paper.entity.Pathfinder;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.LookAnchor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Wolf;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ArrowMock;
import org.mockbukkit.mockbukkit.entity.BlockDisplayMock;
import org.mockbukkit.mockbukkit.entity.IronGolemMock;
import org.mockbukkit.mockbukkit.entity.ItemDisplayMock;
import org.mockbukkit.mockbukkit.entity.MannequinMock;
import org.mockbukkit.mockbukkit.entity.TextDisplayMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.inventory.EntityEquipmentMock;

/**
 * Entity doubles that fill the MockBukkit gaps the abilities hit. {@link FlatWorld} spawns these instead of the
 * plain mocks (MockBukkit instantiates a requested class directly when it has a {@code (ServerMock, UUID)}
 * constructor), so production code calling {@code world.spawn(location, Wolf.class, ...)} gets a {@link TestWolf}.
 *
 * <p>What they add: {@code damage(double, DamageSource)} through {@link DamageSimulation} (MockBukkit throws for
 * non-players), body yaw, {@code lookAt}, line of sight, display billboards, arrow colours, the mob flags summons
 * set while spawning, a recording {@link Pathfinder}, and {@code setVisibleByDefault}/{@code isVisibleByDefault}
 * with Paper's reset of every viewer (see {@link EntityViewer}; {@code TestPlayer} keeps the per-viewer state, so
 * {@code viewer.canSee(entity)} answers who sees what).
 */
public final class MockEntities {

    /** Requested Bukkit class to the double spawned for it. */
    public static final Map<Class<? extends Entity>, Class<? extends Entity>> SUBSTITUTES = Map.of(
            Wolf.class, TestWolf.class,
            IronGolem.class, TestIronGolem.class,
            Mannequin.class, TestMannequin.class,
            ItemDisplay.class, TestItemDisplay.class,
            BlockDisplay.class, TestBlockDisplay.class,
            TextDisplay.class, TestTextDisplay.class,
            Arrow.class, TestArrow.class);

    private MockEntities() {
    }

    /** Rotation for a look from {@code from} towards {@code (x, y, z)}: {yaw, pitch} in degrees. */
    static float[] lookRotation(Location from, double x, double y, double z) {
        Location look = from.clone();
        Vector direction = new Vector(x - from.getX(), y - from.getY(), z - from.getZ());
        if (direction.lengthSquared() < 1.0E-9) {
            return new float[] {from.getYaw(), from.getPitch()};
        }
        look.setDirection(direction);
        return new float[] {look.getYaw(), look.getPitch()};
    }

    // ------------------------------------------------------------------ mobs

    /** A wolf that can take {@link DamageSource} damage and has the mob flags the summoner sets. */
    public static class TestWolf extends WolfMock implements Hurtable {

        /** Like the server: the custom name when set ({@code getName} in legacy section format). */
        @Override
        public String getName() {
            return PaperRules.legacyName(customName(), super.getName());
        }

        @Override
        public net.kyori.adventure.text.Component name() {
            net.kyori.adventure.text.Component custom = customName();
            return custom == null ? super.name() : custom;
        }

        @Override
        public void setRotation(float yaw, float pitch) {
            PaperRules.checkRotation(yaw, pitch);
            super.setRotation(yaw, pitch);
        }

        @Override
        public void setVelocity(org.bukkit.util.Vector velocity) {
            PaperRules.checkVelocity(velocity);
            super.setVelocity(velocity);
        }

        @Override
        public void setHealth(double health) {
            PaperRules.checkHealth(health, getMaxHealth());
            super.setHealth(health);
        }

        private double lastHurt;
        private final RecordingPathfinder pathfinder = new RecordingPathfinder(this);
        private boolean removeWhenFarAway = true;
        private boolean canPickupItems;
        private float bodyYaw;

        public TestWolf(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 4.0);
        }

        private boolean visibleByDefault = true;

        @Override
        public boolean isVisibleByDefault() {
            return visibleByDefault;
        }

        @Override
        public void setVisibleByDefault(boolean visible) {
            if (visible != visibleByDefault) {
                EntityViewer.defaultChanging(this);
                visibleByDefault = visible;
            }
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
        public void damage(double amount, DamageSource source) {
            DamageSimulation.damage(this, amount, source);
        }

        @Override
        public boolean hasLineOfSight(Entity other) {
            return RayTracing.hasLineOfSight(this, other);
        }

        @Override
        public boolean getRemoveWhenFarAway() {
            return removeWhenFarAway;
        }

        @Override
        public void setRemoveWhenFarAway(boolean remove) {
            this.removeWhenFarAway = remove;
        }

        @Override
        public boolean getCanPickupItems() {
            return canPickupItems;
        }

        @Override
        public void setCanPickupItems(boolean pickup) {
            this.canPickupItems = pickup;
        }

        @Override
        public float getBodyYaw() {
            return bodyYaw;
        }

        @Override
        public void setBodyYaw(float bodyYaw) {
            this.bodyYaw = bodyYaw;
        }

        @Override
        public Pathfinder getPathfinder() {
            return pathfinder;
        }

        @Override
        public boolean isInLava() {
            return FlatWorld.isIn(this, Material.LAVA);
        }
    }

    /** An iron golem that can take {@link DamageSource} damage and has the mob flags the summoner sets. */
    public static class TestIronGolem extends IronGolemMock implements Hurtable {

        /** Like the server: the custom name when set ({@code getName} in legacy section format). */
        @Override
        public String getName() {
            return PaperRules.legacyName(customName(), super.getName());
        }

        @Override
        public net.kyori.adventure.text.Component name() {
            net.kyori.adventure.text.Component custom = customName();
            return custom == null ? super.name() : custom;
        }

        @Override
        public void setRotation(float yaw, float pitch) {
            PaperRules.checkRotation(yaw, pitch);
            super.setRotation(yaw, pitch);
        }

        @Override
        public void setVelocity(org.bukkit.util.Vector velocity) {
            PaperRules.checkVelocity(velocity);
            super.setVelocity(velocity);
        }

        @Override
        public void setHealth(double health) {
            PaperRules.checkHealth(health, getMaxHealth());
            super.setHealth(health);
        }

        private double lastHurt;
        private final RecordingPathfinder pathfinder = new RecordingPathfinder(this);
        private boolean removeWhenFarAway = true;
        private boolean canPickupItems;
        private float bodyYaw;

        public TestIronGolem(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 15.0);
        }

        private boolean visibleByDefault = true;

        @Override
        public boolean isVisibleByDefault() {
            return visibleByDefault;
        }

        @Override
        public void setVisibleByDefault(boolean visible) {
            if (visible != visibleByDefault) {
                EntityViewer.defaultChanging(this);
                visibleByDefault = visible;
            }
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
        public void damage(double amount, DamageSource source) {
            DamageSimulation.damage(this, amount, source);
        }

        @Override
        public boolean hasLineOfSight(Entity other) {
            return RayTracing.hasLineOfSight(this, other);
        }

        @Override
        public boolean getRemoveWhenFarAway() {
            return removeWhenFarAway;
        }

        @Override
        public void setRemoveWhenFarAway(boolean remove) {
            this.removeWhenFarAway = remove;
        }

        @Override
        public boolean getCanPickupItems() {
            return canPickupItems;
        }

        @Override
        public void setCanPickupItems(boolean pickup) {
            this.canPickupItems = pickup;
        }

        @Override
        public float getBodyYaw() {
            return bodyYaw;
        }

        @Override
        public void setBodyYaw(float bodyYaw) {
            this.bodyYaw = bodyYaw;
        }

        @Override
        public Pathfinder getPathfinder() {
            return pathfinder;
        }

        @Override
        public boolean isInLava() {
            return FlatWorld.isIn(this, Material.LAVA);
        }
    }

    /**
     * A mannequin (the illusionist's copy) that can take damage, turn its body, look at things, wear a profile and
     * skin parts (MockBukkit's setters throw). Its equipment rejects drop chances exactly like Paper's
     * {@code CraftEntityEquipment} does for every entity that is not a {@code Mob}, unless {@link #ignoreDropChances}
     * is set.
     */
    public static class TestMannequin extends MannequinMock implements Hurtable {

        /** Like the server: the custom name when set ({@code getName} in legacy section format). */
        @Override
        public String getName() {
            return PaperRules.legacyName(customName(), super.getName());
        }

        @Override
        public net.kyori.adventure.text.Component name() {
            net.kyori.adventure.text.Component custom = customName();
            return custom == null ? super.name() : custom;
        }

        @Override
        public void setRotation(float yaw, float pitch) {
            PaperRules.checkRotation(yaw, pitch);
            super.setRotation(yaw, pitch);
        }

        @Override
        public void setVelocity(org.bukkit.util.Vector velocity) {
            PaperRules.checkVelocity(velocity);
            super.setVelocity(velocity);
        }

        @Override
        public void setHealth(double health) {
            PaperRules.checkHealth(health, getMaxHealth());
            super.setHealth(health);
        }

        /**
         * When true, a drop chance is silently ignored instead of failing like on Paper. Only for tests that exercise
         * the illusionist's copies as if its drop-chance bug were fixed; every harness resets it.
         */
        public static boolean ignoreDropChances;

        private final EntityEquipmentMock equipment = new MannequinEquipment(this);
        private @Nullable ResolvableProfile profile;
        private SkinParts skinParts = new TestSkinParts(TestSkinParts.ALL);
        private double lastHurt;
        private float bodyYaw;

        public TestMannequin(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeLiving(attributes);
        }

        private boolean visibleByDefault = true;

        @Override
        public boolean isVisibleByDefault() {
            return visibleByDefault;
        }

        @Override
        public void setVisibleByDefault(boolean visible) {
            if (visible != visibleByDefault) {
                EntityViewer.defaultChanging(this);
                visibleByDefault = visible;
            }
        }

        @Override
        public EntityEquipment getEquipment() {
            return equipment;
        }

        // A mannequin has a player's body (MockBukkit defines no dimensions for it).

        private double scale() {
            return Math.max(0.0625, getAttribute(org.bukkit.attribute.Attribute.SCALE).getValue());
        }

        @Override
        public double getHeight() {
            return 1.8 * scale();
        }

        @Override
        public double getWidth() {
            return 0.6 * scale();
        }

        @Override
        public double getEyeHeight() {
            return 1.62 * scale();
        }

        @Override
        public double getEyeHeight(boolean ignorePose) {
            return getEyeHeight();
        }

        @Override
        public Location getEyeLocation() {
            return getLocation().add(0, getEyeHeight(), 0);
        }

        @Override
        public org.bukkit.util.BoundingBox getBoundingBox() {
            Location at = getLocation();
            double half = getWidth() / 2;
            return new org.bukkit.util.BoundingBox(at.getX() - half, at.getY(), at.getZ() - half, at.getX() + half,
                    at.getY() + getHeight(), at.getZ() + half);
        }

        /** Like the server, a mannequin always has a profile (an empty one until set; MockBukkit throws). */
        @Override
        public ResolvableProfile getProfile() {
            if (profile == null) {
                profile = ResolvableProfile.resolvableProfile().build();
            }
            return profile;
        }

        @Override
        public SkinParts.Mutable getSkinParts() {
            return skinParts.mutableCopy();
        }

        @Override
        public void setSkinParts(SkinParts parts) {
            skinParts = new TestSkinParts(parts.getRaw());
        }

        @Override
        public void setProfile(ResolvableProfile profile) {
            this.profile = profile;
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
        public void damage(double amount, DamageSource source) {
            DamageSimulation.damage(this, amount, source);
        }

        @Override
        public boolean hasLineOfSight(Entity other) {
            return RayTracing.hasLineOfSight(this, other);
        }

        @Override
        public float getBodyYaw() {
            return bodyYaw;
        }

        @Override
        public void setBodyYaw(float bodyYaw) {
            this.bodyYaw = bodyYaw;
        }

        @Override
        public void lookAt(double x, double y, double z, LookAnchor anchor) {
            Location from = anchor == LookAnchor.EYES ? getEyeLocation() : getLocation();
            float[] rotation = lookRotation(from, x, y, z);
            setRotation(rotation[0], rotation[1]);
        }

        @Override
        public boolean isInLava() {
            return FlatWorld.isIn(this, Material.LAVA);
        }
    }

    /** Equipment of a {@link TestMannequin}: Paper refuses drop chances for entities that are not mobs. */
    private static final class MannequinEquipment extends EntityEquipmentMock {
        MannequinEquipment(TestMannequin mannequin) {
            super(mannequin);
        }

        @Override
        public void setDropChance(EquipmentSlot slot, float chance) {
            if (!TestMannequin.ignoreDropChances) {
                throw new IllegalArgumentException("Cannot set drop chance for non-Mob entity");
            }
        }
    }

    // ------------------------------------------------------------------ displays and projectiles

    /** An item display with a billboard. */
    public static class TestItemDisplay extends ItemDisplayMock {

        private org.bukkit.util.@Nullable Transformation transformation;

        /** Kept as set, like the server (MockBukkit round-trips it through a matrix and conjugates the rotation). */
        @Override
        public org.bukkit.util.Transformation getTransformation() {
            return transformation == null ? super.getTransformation() : PaperRules.copy(transformation);
        }

        @Override
        public void setTransformation(org.bukkit.util.Transformation transformation) {
            super.setTransformation(transformation);
            this.transformation = PaperRules.copy(transformation);
        }

        @Override
        public void setTransformationMatrix(org.joml.Matrix4f transformationMatrix) {
            super.setTransformationMatrix(transformationMatrix);
            this.transformation = null;
        }

        @Override
        public void setRotation(float yaw, float pitch) {
            PaperRules.checkRotation(yaw, pitch);
            super.setRotation(yaw, pitch);
        }

        @Override
        public void setVelocity(org.bukkit.util.Vector velocity) {
            PaperRules.checkVelocity(velocity);
            super.setVelocity(velocity);
        }

        private Display.Billboard billboard = Display.Billboard.FIXED;

        public TestItemDisplay(ServerMock server, UUID uuid) {
            super(server, uuid);
        }

        private boolean visibleByDefault = true;

        @Override
        public boolean isVisibleByDefault() {
            return visibleByDefault;
        }

        @Override
        public void setVisibleByDefault(boolean visible) {
            if (visible != visibleByDefault) {
                EntityViewer.defaultChanging(this);
                visibleByDefault = visible;
            }
        }

        @Override
        public Display.Billboard getBillboard() {
            return billboard;
        }

        @Override
        public void setBillboard(Display.Billboard billboard) {
            this.billboard = billboard;
        }
    }

    /** A block display with a billboard. */
    public static class TestBlockDisplay extends BlockDisplayMock {

        private org.bukkit.util.@Nullable Transformation transformation;

        /** Kept as set, like the server (MockBukkit round-trips it through a matrix and conjugates the rotation). */
        @Override
        public org.bukkit.util.Transformation getTransformation() {
            return transformation == null ? super.getTransformation() : PaperRules.copy(transformation);
        }

        @Override
        public void setTransformation(org.bukkit.util.Transformation transformation) {
            super.setTransformation(transformation);
            this.transformation = PaperRules.copy(transformation);
        }

        @Override
        public void setTransformationMatrix(org.joml.Matrix4f transformationMatrix) {
            super.setTransformationMatrix(transformationMatrix);
            this.transformation = null;
        }

        @Override
        public void setRotation(float yaw, float pitch) {
            PaperRules.checkRotation(yaw, pitch);
            super.setRotation(yaw, pitch);
        }

        @Override
        public void setVelocity(org.bukkit.util.Vector velocity) {
            PaperRules.checkVelocity(velocity);
            super.setVelocity(velocity);
        }

        private Display.Billboard billboard = Display.Billboard.FIXED;

        public TestBlockDisplay(ServerMock server, UUID uuid) {
            super(server, uuid);
        }

        private boolean visibleByDefault = true;

        @Override
        public boolean isVisibleByDefault() {
            return visibleByDefault;
        }

        @Override
        public void setVisibleByDefault(boolean visible) {
            if (visible != visibleByDefault) {
                EntityViewer.defaultChanging(this);
                visibleByDefault = visible;
            }
        }

        @Override
        public Display.Billboard getBillboard() {
            return billboard;
        }

        @Override
        public void setBillboard(Display.Billboard billboard) {
            this.billboard = billboard;
        }
    }

    /** A text display with a billboard. */
    public static class TestTextDisplay extends TextDisplayMock {

        private org.bukkit.util.@Nullable Transformation transformation;

        /** Kept as set, like the server (MockBukkit round-trips it through a matrix and conjugates the rotation). */
        @Override
        public org.bukkit.util.Transformation getTransformation() {
            return transformation == null ? super.getTransformation() : PaperRules.copy(transformation);
        }

        @Override
        public void setTransformation(org.bukkit.util.Transformation transformation) {
            super.setTransformation(transformation);
            this.transformation = PaperRules.copy(transformation);
        }

        @Override
        public void setTransformationMatrix(org.joml.Matrix4f transformationMatrix) {
            super.setTransformationMatrix(transformationMatrix);
            this.transformation = null;
        }

        @Override
        public void setRotation(float yaw, float pitch) {
            PaperRules.checkRotation(yaw, pitch);
            super.setRotation(yaw, pitch);
        }

        @Override
        public void setVelocity(org.bukkit.util.Vector velocity) {
            PaperRules.checkVelocity(velocity);
            super.setVelocity(velocity);
        }

        private Display.Billboard billboard = Display.Billboard.FIXED;

        public TestTextDisplay(ServerMock server, UUID uuid) {
            super(server, uuid);
        }

        private boolean visibleByDefault = true;

        @Override
        public boolean isVisibleByDefault() {
            return visibleByDefault;
        }

        @Override
        public void setVisibleByDefault(boolean visible) {
            if (visible != visibleByDefault) {
                EntityViewer.defaultChanging(this);
                visibleByDefault = visible;
            }
        }

        @Override
        public Display.Billboard getBillboard() {
            return billboard;
        }

        @Override
        public void setBillboard(Display.Billboard billboard) {
            this.billboard = billboard;
        }
    }

    /** An arrow with a colour and the in-ground state {@link WorldPhysics} maintains. */
    public static class TestArrow extends ArrowMock {

        @Override
        public void setRotation(float yaw, float pitch) {
            PaperRules.checkRotation(yaw, pitch);
            super.setRotation(yaw, pitch);
        }

        @Override
        public void setVelocity(org.bukkit.util.Vector velocity) {
            PaperRules.checkVelocity(velocity);
            super.setVelocity(velocity);
        }

        private @Nullable Color color;
        private @Nullable Block attachedBlock;
        boolean leftShooter;

        public TestArrow(ServerMock server, UUID uuid) {
            super(server, uuid);
        }

        private boolean visibleByDefault = true;

        @Override
        public boolean isVisibleByDefault() {
            return visibleByDefault;
        }

        @Override
        public void setVisibleByDefault(boolean visible) {
            if (visible != visibleByDefault) {
                EntityViewer.defaultChanging(this);
                visibleByDefault = visible;
            }
        }

        @Override
        public @Nullable Color getColor() {
            return color;
        }

        @Override
        public void setColor(@Nullable Color color) {
            this.color = color;
        }

        @Override
        public boolean isInBlock() {
            return attachedBlock != null;
        }

        @Override
        public @Nullable Block getAttachedBlock() {
            return attachedBlock;
        }

        void stick(Block block) {
            this.attachedBlock = block;
        }

        /** {@link WorldPhysics} moves the arrow; MockBukkit's own (collision-free) movement is switched off. */
        @Override
        public void tick() {
            Vector velocity = getVelocity();
            setVelocity(new Vector());
            super.tick();
            setVelocity(velocity);
        }
    }

    // ------------------------------------------------------------------ pathfinding

    /**
     * A pathfinder without navigation: it records every destination it was sent to and reports a straight path
     * that can always be reached.
     */
    public static final class RecordingPathfinder implements Pathfinder {
        private final Mob mob;
        private final List<Location> destinations = new ArrayList<>();
        private @Nullable PathResult current;

        RecordingPathfinder(Mob mob) {
            this.mob = mob;
        }

        /** Every destination a {@code moveTo} was issued for, oldest first. */
        public List<Location> destinations() {
            return destinations;
        }

        @Override
        public Mob getEntity() {
            return mob;
        }

        @Override
        public void stopPathfinding() {
            current = null;
        }

        @Override
        public boolean hasPath() {
            return current != null;
        }

        @Override
        public @Nullable PathResult getCurrentPath() {
            return current;
        }

        @Override
        public @Nullable PathResult findPath(Location location) {
            return new StraightPath(location.clone());
        }

        @Override
        public @Nullable PathResult findPath(LivingEntity target) {
            return new StraightPath(target.getLocation());
        }

        @Override
        public @Nullable PathResult findPath(Entity target) {
            return new StraightPath(target.getLocation());
        }

        @Override
        public @Nullable PathResult findPath(Location location, int reachRange) {
            return new StraightPath(location.clone());
        }

        @Override
        public @Nullable PathResult findPath(Entity target, int reachRange) {
            return new StraightPath(target.getLocation());
        }

        @Override
        public boolean moveTo(PathResult path, double speed) {
            current = path;
            Location end = path.getFinalPoint();
            if (end != null) {
                destinations.add(end);
            }
            return true;
        }

        @Override
        public boolean canOpenDoors() {
            return false;
        }

        @Override
        public void setCanOpenDoors(boolean canOpenDoors) {
        }

        @Override
        public boolean canPassDoors() {
            return false;
        }

        @Override
        public void setCanPassDoors(boolean canPassDoors) {
        }

        @Override
        public boolean canFloat() {
            return true;
        }

        @Override
        public void setCanFloat(boolean canFloat) {
        }
    }

    private record StraightPath(Location end) implements Pathfinder.PathResult {
        @Override
        public List<Location> getPoints() {
            return List.of(end);
        }

        @Override
        public int getNextPointIndex() {
            return 0;
        }

        @Override
        public @Nullable Location getNextPoint() {
            return end;
        }

        @Override
        public @Nullable Location getFinalPoint() {
            return end;
        }

        @Override
        public boolean canReachFinalPoint() {
            return true;
        }
    }
}
