package io.github.sx2xoox.mcpl.game.superpower.support.world;

import java.util.Map;
import java.util.UUID;

import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.PiglinBrute;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Vex;
import org.bukkit.entity.WitherSkeleton;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PiglinBruteMock;
import org.mockbukkit.mockbukkit.entity.RavagerMock;
import org.mockbukkit.mockbukkit.entity.SilverfishMock;
import org.mockbukkit.mockbukkit.entity.VexMock;
import org.mockbukkit.mockbukkit.entity.WitherSkeletonMock;

/**
 * Doubles of the 공성전 camp monsters (see {@link MockEntities}): MockBukkit's mobs throw for
 * {@code damage(double, DamageSource)} and {@code getLastDamage}, which ability damage needs.
 */
public final class CampMobMocks {

    /** Requested Bukkit class to the double spawned for it (part of {@link MockEntities#SUBSTITUTES}). */
    static final Map<Class<? extends Entity>, Class<? extends Entity>> SUBSTITUTES = Map.of(
            Vex.class, TestVex.class,
            Silverfish.class, TestSilverfish.class,
            PiglinBrute.class, TestPiglinBrute.class,
            WitherSkeleton.class, TestWitherSkeleton.class,
            Ravager.class, TestRavager.class);

    private CampMobMocks() {
    }

    /** A Vex (a 공성전 camp monster) that can take {@link DamageSource} damage. */
    public static class TestVex extends VexMock implements Hurtable {

        private double lastHurt;

        public TestVex(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 4.0);
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

    /** A Silverfish (a 공성전 camp monster) that can take {@link DamageSource} damage. */
    public static class TestSilverfish extends SilverfishMock implements Hurtable {

        private double lastHurt;

        public TestSilverfish(ServerMock server, UUID uuid) {
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

    /** A PiglinBrute (a 공성전 camp monster) that can take {@link DamageSource} damage. */
    public static class TestPiglinBrute extends PiglinBruteMock implements Hurtable {

        private double lastHurt;

        public TestPiglinBrute(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 7.0);
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

    /** A WitherSkeleton (a 공성전 camp monster) that can take {@link DamageSource} damage. */
    public static class TestWitherSkeleton extends WitherSkeletonMock implements Hurtable {

        private double lastHurt;

        public TestWitherSkeleton(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 4.0);
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

    /** A Ravager (a 공성전 camp monster) that can take {@link DamageSource} damage. */
    public static class TestRavager extends RavagerMock implements Hurtable {

        private double lastHurt;

        public TestRavager(ServerMock server, UUID uuid) {
            super(server, uuid);
            VanillaAttributes.completeMob(attributes, 12.0);
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
