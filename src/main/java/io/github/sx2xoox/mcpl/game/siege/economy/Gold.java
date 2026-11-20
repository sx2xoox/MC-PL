package io.github.sx2xoox.mcpl.game.siege.economy;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeArena;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The gold of 공성전 v2 (docs/siege-v2.md §3): every member's purse for the round. Gold comes in every second of the
 * fight, for minion last hits, kills and assists, jungle objectives, the dragon and towers; the shop spends it. All
 * amounts are {@link Settings} ({@code games.siege.gold.*}); everything resets with {@link #reset}.
 */
public final class Gold {

    public static final String PATH = "gold.";

    /**
     * Gold amounts.
     *
     * @param start     each member's purse at the start of a round
     * @param perSecond passive income of every player in play during the fight
     * @param minion    a melee or ranged minion's last hit
     * @param cannon    a cannon minion's last hit
     * @param kill      a player kill
     * @param assist    an assist
     * @param small     a small jungle camp (젤리, 쥐), to the last hitter
     * @param buff      a buff monster (곰인형, 토끼), to the last hitter
     * @param capture   a capture point, to each capturer inside
     * @param dragon    the dragon, to every member of the team
     * @param tower     a destroyed enemy tower, to every member of the team
     */
    public record Settings(int start, int perSecond, int minion, int cannon, int kill, int assist, int small, int buff,
            int capture, int dragon, int tower) {

        public static Settings read(ConfigurationSection config) {
            return new Settings(
                    config.getInt(PATH + "start", 500),
                    config.getInt(PATH + "per-second", 1),
                    config.getInt(PATH + "minion", 15),
                    config.getInt(PATH + "cannon", 40),
                    config.getInt(PATH + "kill", 150),
                    config.getInt(PATH + "assist", 50),
                    config.getInt(PATH + "jungle-small", 40),
                    config.getInt(PATH + "jungle-buff", 90),
                    config.getInt(PATH + "capture", 60),
                    config.getInt(PATH + "dragon", 200),
                    config.getInt(PATH + "tower", 120));
        }
    }

    private final SiegeArena arena;
    private final Settings settings;
    private final Map<UUID, Integer> purses = new HashMap<>();

    public Gold(SiegeArena arena, Settings settings) {
        this.arena = arena;
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    /** A player's gold (the start amount before anything happened). */
    public int balance(UUID player) {
        return purses.getOrDefault(player, settings.start());
    }

    /**
     * Adds gold to a member and shows it in the action bar ({@code reason}: a siege.yml key under {@code gold.reason},
     * null = silent).
     */
    public void add(UUID player, int amount, @Nullable String reason) {
        if (amount <= 0 || arena.side(player) == null) {
            return;
        }
        purses.put(player, balance(player) + amount);
        Player online = Bukkit.getPlayer(player);
        if (reason != null && online != null) {
            Messages messages = arena.messages();
            online.sendActionBar(messages.get("gold.gain", Messages.tag("amount", amount),
                    Messages.tag("reason", messages.get("gold.reason." + reason))));
            online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.4f, 1.6f);
        }
    }

    /**
     * {@link #add(UUID, int, String)} won at {@code at} (a last hit, a camp): the amount also pops up there for the
     * player ({@link SiegeArena#goldPop}).
     */
    public void add(UUID player, int amount, @Nullable String reason, @Nullable Location at) {
        if (amount <= 0 || arena.side(player) == null) {
            return;
        }
        add(player, amount, reason);
        if (at != null) {
            arena.goldPop(player, at, amount);
        }
    }

        /** Takes gold if the player has enough. */
    public boolean spend(UUID player, int amount) {
        int have = balance(player);
        if (amount < 0 || have < amount) {
            return false;
        }
        purses.put(player, have - amount);
        return true;
    }

    /** Every member of {@code side} gets {@code amount}. */
    public void team(LaneSites.Side side, int amount, @Nullable String reason) {
        for (UUID member : arena.members(side)) {
            add(member, amount, reason);
        }
    }

    /** Once a second of the fight: the passive income of the players in play. */
    public void second() {
        if (settings.perSecond() <= 0) {
            return;
        }
        for (LaneSites.Side side : LaneSites.Side.values()) {
            for (Player player : arena.fighters(side)) {
                add(player.getUniqueId(), settings.perSecond(), null);
            }
        }
    }

    /** A player kill (the rules call this): the killer's and the assisters' gold. */
    public void kill(@Nullable UUID killer, Collection<UUID> assisters) {
        if (killer != null) {
            add(killer, settings.kill(), "kill");
        }
        for (UUID assister : assisters) {
            if (!assister.equals(killer)) {
                add(assister, settings.assist(), "assist");
            }
        }
    }

    /** {@code side} destroyed an enemy tower (the rules call this). */
    public void towerDestroyed(LaneSites.Side side) {
        team(side, settings.tower(), "tower");
    }

    /** A new round: everyone starts over. */
    public void reset() {
        purses.clear();
    }
}
