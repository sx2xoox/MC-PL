package io.github.sx2xoox.mcpl.game.superpower.support;

import java.util.HashMap;
import java.util.Map;

import net.kyori.adventure.key.Key;
import org.bukkit.WorldBorder;
import org.bukkit.damage.DamageSource;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.jspecify.annotations.Nullable;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A {@link PlayerMock} filling the few gaps of MockBukkit the superpower runtime relies on: item cooldown groups,
 * per-player world borders, damage with a {@link DamageSource} (simulated with events like the real server) and
 * healing.
 */
public final class TestPlayer extends PlayerMock {

    private final Map<Key, Integer> cooldowns = new HashMap<>();
    private @Nullable WorldBorder worldBorder;

    public TestPlayer(ServerMock server, String name) {
        super(server, name);
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
    public void damage(double amount, DamageSource damageSource) {
        simulateDamage(amount, damageSource);
    }

    @Override
    public void heal(double amount, EntityRegainHealthEvent.RegainReason reason) {
        if (!isDead()) {
            double max = getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
            setHealth(Math.min(max, getHealth() + amount));
        }
    }
}
