package io.github.sx2xoox.mcpl.game.siege;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * The 공성전 sounds (docs/siege.md "소리"): hits that sound like the toy they hit ({@link Surface}), the 75/50/25%
 * warnings, the destroy fanfare, the rate-limited "our tower is under attack" alert, level-ups, round start and end,
 * and the coin/purchase sounds the gold and shop code use. Place sounds are played to each viewer within
 * {@value #HEAR_RANGE} blocks (so they mix by distance); repeats go through {@link #ready} so nothing spams.
 */
public final class SiegeSounds {

    /** What a structure or monster is made of: picks its hit sound. */
    public enum Surface {
        /** Wooden toy (towers, teddy, soldiers): a dry knock. */
        WOOD(Sound.BLOCK_BAMBOO_WOOD_HIT, 1.25f),
        /** Plastic toy (minions, mice): a hollow click. */
        PLASTIC(Sound.ENTITY_ARMOR_STAND_HIT, 1.4f),
        /** Porcelain (the music-box core): a bright clink. */
        PORCELAIN(Sound.BLOCK_DECORATED_POT_HIT, 1.35f),
        /** Plush and jelly: a soft thud. */
        SOFT(Sound.BLOCK_WOOL_HIT, 0.9f),
        /** Tin and clockwork: a light metal tick. */
        METAL(Sound.BLOCK_CHAIN_HIT, 1.3f);

        private final Sound sound;
        private final float pitch;

        Surface(Sound sound, float pitch) {
            this.sound = sound;
            this.pitch = pitch;
        }

        public Sound sound() {
            return sound;
        }

        public float pitch() {
            return pitch;
        }
    }

    /** Players hear place sounds within this many blocks. */
    public static final double HEAR_RANGE = 32;
    /** Ticks between two hit sounds on the same thing. */
    public static final int HIT_GAP_TICKS = 3;

    private final GameSession session;
    private final Map<String, Long> last = new HashMap<>();

    public SiegeSounds(GameSession session) {
        this.session = session;
    }

    /**
     * True (and remembered) if {@code key} did not play during the last {@code gapTicks} ticks: a rate limit any caller
     * can use for its own repeats.
     */
    public boolean ready(String key, long gapTicks) {
        long now = session.tick();
        Long before = last.get(key);
        if (before != null && now - before < gapTicks) {
            return false;
        }
        last.put(key, now);
        return true;
    }

    // ------------------------------------------------------------------ plumbing

    /** {@code sound} at {@code at} for every viewer within {@link #HEAR_RANGE}. */
    public void at(Location at, Sound sound, float volume, float pitch) {
        if (at.getWorld() == null) {
            return;
        }
        double max = HEAR_RANGE * HEAR_RANGE;
        for (Player viewer : session.viewers()) {
            if (viewer.getWorld().equals(at.getWorld()) && viewer.getLocation().distanceSquared(at) <= max) {
                viewer.playSound(at, sound, volume, pitch);
            }
        }
    }

    /** {@code sound} for one player, at their own position. */
    public void to(Player player, Sound sound, float volume, float pitch) {
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /** {@code sound} for each of {@code players}, at their own positions. */
    public void to(Collection<? extends Player> players, Sound sound, float volume, float pitch) {
        for (Player player : players) {
            to(player, sound, volume, pitch);
        }
    }

    // ------------------------------------------------------------------ structures and monsters

    /** A hit on something made of {@code surface} (rate-limited per {@code key}: the structure or monster id). */
    public void hit(Location at, Surface surface, String key) {
        if (ready("hit:" + key, HIT_GAP_TICKS)) {
            at(at, surface.sound(), 1f, surface.pitch() + (float) (session.random().nextDouble() * 0.1 - 0.05));
        }
    }

    /** A structure fell to {@code percent}% (75, 50 or 25): a chime at the structure, lower the lower it gets. */
    public void healthWarning(Location at, int percent) {
        float pitch = percent >= 75 ? 1.4f : percent >= 50 ? 1.1f : 0.8f;
        at(at, Sound.BLOCK_NOTE_BLOCK_BELL, 1f, pitch);
        at(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, pitch);
    }

    /**
     * "Our structure is under attack" for one defender, at most once per {@code gapTicks} for this
     * {@code key}.
     *
     * @return true if it played
     */
    public boolean underAttack(Player owner, String key, long gapTicks) {
        if (!ready("alert:" + owner.getUniqueId() + ":" + key, gapTicks)) {
            return false;
        }
        to(owner, Sound.BLOCK_BELL_USE, 0.8f, 1.4f);
        to(owner, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 0.7f);
        return true;
    }

    /** A structure was destroyed: the crash where it stood, a fanfare for the winners, a low tone for the losers. */
    public void destroyed(Location at, Surface surface, Collection<? extends Player> winners,
                          Collection<? extends Player> losers) {
        at(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.1f);
        at(at, surface == Surface.PORCELAIN ? Sound.BLOCK_DECORATED_POT_SHATTER : Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
        to(winners, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        to(winners, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1f);
        to(losers, Sound.BLOCK_BEACON_DEACTIVATE, 0.9f, 0.8f);
    }

    /** A monster or minion died at {@code at}. */
    public void defeated(Location at, Surface surface) {
        at(at, surface == Surface.SOFT ? Sound.ENTITY_SLIME_SQUISH : Sound.ENTITY_ITEM_BREAK, 0.9f, 1.2f);
    }

    // ------------------------------------------------------------------ players

    public void levelUp(Player player) {
        to(player, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
        to(player, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 0.6f, 1.6f);
    }

    /** The killer's ding. */
    public void kill(Player killer) {
        to(killer, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.8f);
    }

    /** Gold received (rate-limited per player so a stream of small amounts is one coin sound). */
    public void coin(Player player) {
        if (ready("coin:" + player.getUniqueId(), 4)) {
            to(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.7f);
        }
    }

    /** Something bought. */
    public void purchase(Player player) {
        to(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
        to(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
    }

    /** Refused (no gold, full slots, away from the shop). */
    public void denied(Player player) {
        to(player, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.1f);
    }

    /** The shield turned a hit away. */
    public void shieldBlocked(Player player) {
        if (ready("shield:" + player.getUniqueId(), 10)) {
            to(player, Sound.ITEM_SHIELD_BLOCK, 0.6f, 1.2f);
        }
    }

    // ------------------------------------------------------------------ rounds

    /** The countdown tick (3, 2, 1). */
    public void countdown(Collection<? extends Player> players, int seconds) {
        to(players, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, seconds == 1 ? 1.6f : 1.2f);
    }

    public void roundStart(Collection<? extends Player> players) {
        to(players, Sound.EVENT_RAID_HORN, 0.6f, 1.2f);
    }

    /** The fight begins. */
    public void fightStart(Collection<? extends Player> players) {
        to(players, Sound.ENTITY_EVOKER_CAST_SPELL, 0.8f, 1.2f);
        to(players, Sound.BLOCK_NOTE_BLOCK_COW_BELL, 0.8f, 1f);
    }

    public void roundEnd(Collection<? extends Player> winners, Collection<? extends Player> losers) {
        to(winners, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        to(losers, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 0.7f);
    }

    /** A warning everyone hears (the last minutes, overtime). */
    public void alarm(Collection<? extends Player> players) {
        to(players, Sound.BLOCK_BELL_USE, 0.8f, 0.9f);
        to(players, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, 0.6f);
    }
}
