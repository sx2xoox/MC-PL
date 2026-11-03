package io.github.sx2xoox.mcpl.game.siege;

import java.util.Collection;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.jspecify.annotations.Nullable;

/**
 * The 공성전 levels in play (docs/siege.md §12): hands out experience (passive, kills, assists, towers, defence,
 * objectives), shows level-ups (title, sound, particles, unlock lines), adds the per-level maximum health as an
 * attribute modifier and answers the hosted ability match's kit locks ({@link #activeLocked}, {@link #ultimateLocked},
 * {@link #canChargeGauge}).
 */
final class SiegeProgress {

    private final SiegeMatch match;
    private final GameSession session;
    private final Messages messages;
    private final SiegeLevels levels;
    private final NamespacedKey healthKey;

    SiegeProgress(SiegeMatch match, SiegeProgressSettings settings) {
        this.match = match;
        this.session = match.session();
        this.messages = match.messages();
        this.levels = new SiegeLevels(settings);
        this.healthKey = match.hub().key("siege_level_health");
    }

    SiegeLevels levels() {
        return levels;
    }

    SiegeProgressSettings settings() {
        return levels.settings();
    }

    // ------------------------------------------------------------------ flow

    /** A new half: everyone back to level 1 without the level health (call before the players' reset). */
    void newHalf() {
        levels.reset();
        for (Player player : session.participants()) {
            applyHealth(player);
        }
    }

    /** Once a second of the fight: the passive trickle for everyone in play. */
    void second() {
        int amount = settings().xpPassivePerSecond();
        if (amount <= 0) {
            return;
        }
        for (Player player : session.alivePlayers()) {
            if (match.teamIndex(player.getUniqueId()) >= 0 && session.isInPlay(player)) {
                grant(player.getUniqueId(), amount);
            }
        }
    }

    /** {@code killer} killed an enemy with help from {@code assisters}. */
    void kill(UUID killer, Collection<UUID> assisters) {
        grant(killer, settings().xpKill());
        for (UUID assister : assisters) {
            grant(assister, settings().xpAssist());
        }
    }

    /** Experience for every member of a team (in play, waiting to respawn or not). */
    void teamXp(int team, int amount) {
        for (UUID member : match.members(team)) {
            if (session.isAlive(member)) {
                grant(member, amount);
            }
        }
    }

    void grant(UUID player, int amount) {
        int before = levels.level(player);
        if (levels.add(player, amount) > 0) {
            Player online = Bukkit.getPlayer(player);
            if (online != null) {
                levelUp(online, before, levels.level(player));
            }
        }
    }

    /** Back from a death: the level health again (the respawn reset the attributes' health to full). */
    void respawned(Player player) {
        applyHealth(player);
        player.setHealth(maxHealth(player));
    }

    /** The match ends: no level health stays on anybody. */
    void clear() {
        for (Player player : session.participants()) {
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            if (max != null && max.getModifier(healthKey) != null) {
                max.removeModifier(healthKey);
            }
        }
    }

    // ------------------------------------------------------------------ feedback

    private void levelUp(Player player, int from, int to) {
        double before = maxHealth(player);
        applyHealth(player);
        double gained = maxHealth(player) - before;
        if (gained > 0 && !player.isDead() && session.isInPlay(player)) {
            player.setHealth(Math.min(maxHealth(player), player.getHealth() + gained));
        }
        Feedback.title(player, messages.get("level.up-title", Messages.tag("level", to)),
                messages.get("level.up-subtitle", Messages.tag("level", to)), 3, 25, 8);
        match.titleShown(player);
        Feedback.play(player, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
        if (session.isInPlay(player)) {
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 30,
                    0.4, 0.6, 0.4, 0.3);
        }
        SiegeProgressSettings s = settings();
        if (from < s.activeLevel() && to >= s.activeLevel()) {
            messages.send(player, "level.unlock-active");
        }
        if (from < s.ultimateLevel() && to >= s.ultimateLevel()) {
            messages.send(player, "level.unlock-ultimate");
        }
        if (s.bonusHealth(to) > s.bonusHealth(from)) {
            messages.send(player, "level.health", Messages.tag("hearts", formatHearts(s.bonusHealth(to))));
        }
    }

    private static String formatHearts(double health) {
        double hearts = health / 2;
        return hearts == Math.rint(hearts) ? String.valueOf((long) hearts) : String.valueOf(hearts);
    }

    /** Puts the modifier for the player's current level on (or takes it off at level 1 / levels off). */
    void applyHealth(Player player) {
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max == null) {
            return;
        }
        if (max.getModifier(healthKey) != null) {
            max.removeModifier(healthKey);
        }
        double bonus = settings().bonusHealth(levels.level(player.getUniqueId()));
        if (bonus > 0) {
            max.addModifier(new AttributeModifier(healthKey, bonus, AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.ANY));
        }
        if (!player.isDead() && player.getHealth() > max.getValue()) {
            player.setHealth(max.getValue());
        }
    }

    private static double maxHealth(Player player) {
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 20 : max.getValue();
    }

    // ------------------------------------------------------------------ kit locks

    @Nullable Component activeLocked(Player player) {
        UUID id = player.getUniqueId();
        return levels.canUseActive(id) ? null : messages.get("level.active-locked",
                Messages.tag("level", settings().activeLevel()), Messages.tag("now", levels.level(id)));
    }

    @Nullable Component ultimateLocked(Player player) {
        UUID id = player.getUniqueId();
        return levels.canUseUltimate(id) ? null : messages.get("level.ultimate-locked",
                Messages.tag("level", settings().ultimateLevel()), Messages.tag("now", levels.level(id)));
    }

    boolean canChargeGauge(Player player) {
        return levels.canUseUltimate(player.getUniqueId());
    }

    // ------------------------------------------------------------------ HUD

    /** The sidebar line of a participant: level and experience, or null while levels are off. */
    @Nullable Component hudLine(UUID player) {
        if (!settings().levelsEnabled()) {
            return null;
        }
        int need = levels.xpThisLevel(player);
        return need <= 0 ? messages.get("hud.level-max", Messages.tag("level", levels.level(player)))
                : messages.get("hud.level", Messages.tag("level", levels.level(player)),
                        Messages.tag("xp", levels.xpIntoLevel(player)), Messages.tag("next", need));
    }
}
