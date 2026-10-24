package io.github.sx2xoox.mcpl.core.ui;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * An Adventure {@link BossBar} that remembers who sees it, so it can always be hidden from everyone
 * ({@link #hideAll()}) and never leaks. Session boss bars ({@code GameSession#sharedBossBar} /
 * {@code GameSession#playerBossBar}) are hidden automatically when the match stops.
 */
public final class ManagedBossBar {

    private final BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();

    public ManagedBossBar(Component name, float progress, BossBar.Color color, BossBar.Overlay overlay) {
        this.bar = BossBar.bossBar(name, clamp(progress), color, overlay);
    }

    /** The underlying Adventure boss bar (for flags or listeners). */
    public BossBar bar() {
        return bar;
    }

    public ManagedBossBar name(Component name) {
        if (!bar.name().equals(name)) {
            bar.name(name);
        }
        return this;
    }

    /** Progress in 0..1 (clamped). */
    public ManagedBossBar progress(float progress) {
        float clamped = clamp(progress);
        if (bar.progress() != clamped) {
            bar.progress(clamped);
        }
        return this;
    }

    public ManagedBossBar color(BossBar.Color color) {
        bar.color(color);
        return this;
    }

    public ManagedBossBar overlay(BossBar.Overlay overlay) {
        bar.overlay(overlay);
        return this;
    }

    public void show(Player player) {
        if (viewers.add(player.getUniqueId())) {
            player.showBossBar(bar);
        }
    }

    public void hide(Player player) {
        if (viewers.remove(player.getUniqueId())) {
            player.hideBossBar(bar);
        }
    }

    public boolean isShownTo(Player player) {
        return viewers.contains(player.getUniqueId());
    }

    /** Hides the bar from every player it was shown to (online ones; offline players drop it on their own). */
    public void hideAll() {
        for (UUID id : Set.copyOf(viewers)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.hideBossBar(bar);
            }
        }
        viewers.clear();
    }

    /** Forgets a player that left the server without sending packets. */
    public void forget(UUID playerId) {
        viewers.remove(playerId);
    }

    private static float clamp(float progress) {
        if (Float.isNaN(progress)) {
            return 0f;
        }
        return Math.max(BossBar.MIN_PROGRESS, Math.min(BossBar.MAX_PROGRESS, progress));
    }
}
