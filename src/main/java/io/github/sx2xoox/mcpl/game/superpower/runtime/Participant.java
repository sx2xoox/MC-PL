package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.menu.ChoiceMenu;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Per-player state of one superpower match. Kept after elimination (for the kill feed tag and the results).
 */
public final class Participant {

    private final UUID id;
    private final String name;

    /** Assigned ability id; null until assigned. Kept after elimination. */
    @Nullable String abilityId;
    /** Assigned by an admin ({@code /게임 능력 지정}): no rerolls. */
    boolean forced;
    int rerollsLeft;

    /** The bound instance while the owner is alive in the fight. */
    @Nullable Ability ability;
    boolean granted;

    final UltimateGauge gauge = new UltimateGauge();
    final Cooldown cooldown = new Cooldown();
    long silencedUntil;
    boolean silenceShown;

    // CHOICE mode
    List<String> candidates = List.of();
    @Nullable ChoiceMenu menu;
    boolean chosen;

    /** Session tick of the last right-click, to ignore the duplicate block+air interaction of one click. */
    long lastUseTick = -1;
    /** Session tick of the last "not ready" sound, so holding right-click does not spam it. */
    long lastDenySoundTick = -100;

    Participant(Player player) {
        this.id = player.getUniqueId();
        this.name = player.getName();
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** The online player, or null. */
    public @Nullable Player player() {
        return Bukkit.getPlayer(id);
    }

    public @Nullable String abilityId() {
        return abilityId;
    }

    public @Nullable Ability ability() {
        return ability;
    }

    public boolean isForced() {
        return forced;
    }

    public int rerollsLeft() {
        return rerollsLeft;
    }

    public double gauge() {
        return gauge.value();
    }

    public boolean isSilenced(long now) {
        return silencedUntil > now;
    }
}
