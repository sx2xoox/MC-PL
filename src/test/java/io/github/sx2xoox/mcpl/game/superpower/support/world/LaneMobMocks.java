package io.github.sx2xoox.mcpl.game.superpower.support.world;

import java.util.Map;
import java.util.UUID;

import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Endermite;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Panda;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.EndermiteMock;
import org.mockbukkit.mockbukkit.entity.PandaMock;
import org.mockbukkit.mockbukkit.entity.RabbitMock;
import org.mockbukkit.mockbukkit.entity.SkeletonMock;
import org.mockbukkit.mockbukkit.entity.SlimeMock;
import org.mockbukkit.mockbukkit.entity.VillagerMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;

/**
 * Doubles of the 공성전 v2 lane creatures (minions, jungle monsters, the shopkeeper; see {@link MockEntities}):
 * MockBukkit's mobs throw for {@code damage(double, DamageSource)} and {@code getLastDamage}, and its slime has no
 * size.
 */
public final class LaneMobMocks {

    /** Requested Bukkit class to the double spawned for it (part of {@link MockEntities#SUBSTITUTES}). */
    static final Map<Class<? extends Entity>, Class<? extends Entity>> SUBSTITUTES = Map.of(
            Zombie.class, TestZombie.class,
            Skeleton.class, TestSkeleton.class,
            Slime.class, TestSlime.class,
            Endermite.class, TestEndermite.class,
            Panda.class, TestPanda.class,
            Rabbit.class, TestRabbit.class,
            Villager.class, TestVillager.class);

    private LaneMobMocks() {
    }

    /** A Zombie that can take {@link DamageSource} damage. */
    public static class TestZombie extends ZombieMock implements Hurtable {

        private double lastHurt;

        public TestZombie(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 3.0);
        }

        /** Like Paper: not valid while its spawn consumer runs (not in the world yet). */
        @Override
        public boolean isValid() {
            return MockEntities.inWorld(this) && super.isValid();
        }

        @Override
        public void setHealth(double health) {
            PaperRules.checkHealth(health, getMaxHealth());
            super.setHealth(health);
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
    }

    /** A Skeleton that can take {@link DamageSource} damage. */
    public static class TestSkeleton extends SkeletonMock implements Hurtable {

        private double lastHurt;

        public TestSkeleton(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 2.0);
        }

        /** Like Paper: not valid while its spawn consumer runs (not in the world yet). */
        @Override
        public boolean isValid() {
            return MockEntities.inWorld(this) && super.isValid();
        }

        @Override
        public void setHealth(double health) {
            PaperRules.checkHealth(health, getMaxHealth());
            super.setHealth(health);
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
    }

    /** A Slime that can take {@link DamageSource} damage. */
    public static class TestSlime extends SlimeMock implements Hurtable {

        private double lastHurt;

        public TestSlime(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 1.0);
        }

        /** Like Paper: not valid while its spawn consumer runs (not in the world yet). */
        @Override
        public boolean isValid() {
            return MockEntities.inWorld(this) && super.isValid();
        }

        @Override
        public void setHealth(double health) {
            PaperRules.checkHealth(health, getMaxHealth());
            super.setHealth(health);
        }

        /** MockBukkit has no slime dimensions: 0.52 blocks a size step, like vanilla. */
        @Override
        public double getWidth() {
            return 0.52 * getSize();
        }

        @Override
        public double getHeight() {
            return 0.52 * getSize();
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
    }

    /** A Endermite that can take {@link DamageSource} damage. */
    public static class TestEndermite extends EndermiteMock implements Hurtable {

        private double lastHurt;

        public TestEndermite(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 2.0);
        }

        /** Like Paper: not valid while its spawn consumer runs (not in the world yet). */
        @Override
        public boolean isValid() {
            return MockEntities.inWorld(this) && super.isValid();
        }

        @Override
        public void setHealth(double health) {
            PaperRules.checkHealth(health, getMaxHealth());
            super.setHealth(health);
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
    }

    /** A Panda that can take {@link DamageSource} damage. */
    public static class TestPanda extends PandaMock implements Hurtable {

        private double lastHurt;

        public TestPanda(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 6.0);
        }

        /** Like Paper: not valid while its spawn consumer runs (not in the world yet). */
        @Override
        public boolean isValid() {
            return MockEntities.inWorld(this) && super.isValid();
        }

        @Override
        public void setHealth(double health) {
            PaperRules.checkHealth(health, getMaxHealth());
            super.setHealth(health);
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
    }

    /** A Rabbit that can take {@link DamageSource} damage. */
    public static class TestRabbit extends RabbitMock implements Hurtable {

        private double lastHurt;

        public TestRabbit(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 1.0);
        }

        /** Like Paper: not valid while its spawn consumer runs (not in the world yet). */
        @Override
        public boolean isValid() {
            return MockEntities.inWorld(this) && super.isValid();
        }

        @Override
        public void setHealth(double health) {
            PaperRules.checkHealth(health, getMaxHealth());
            super.setHealth(health);
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
    }

    /** A Villager that can take {@link DamageSource} damage. */
    public static class TestVillager extends VillagerMock implements Hurtable {

        private double lastHurt;

        public TestVillager(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 1.0);
        }

        /** Like Paper: not valid while its spawn consumer runs (not in the world yet). */
        @Override
        public boolean isValid() {
            return MockEntities.inWorld(this) && super.isValid();
        }

        @Override
        public void setHealth(double health) {
            PaperRules.checkHealth(health, getMaxHealth());
            super.setHealth(health);
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
    }
}
