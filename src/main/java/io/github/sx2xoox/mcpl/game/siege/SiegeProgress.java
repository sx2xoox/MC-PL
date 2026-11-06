package io.github.sx2xoox.mcpl.game.siege;

import java.util.Collection;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.ui.TextWidth;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegePerks;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.jspecify.annotations.Nullable;

/**
 * The 공성전 levels in play (docs/siege.md "레벨"): hands out experience (passive, kills, assists, towers, and whatever
 * other features grant through {@link SiegeMatch#addXp}), shows level-ups (title, sound, particles) and adds the
 * per-level maximum health as an attribute modifier. Levels never lock any part of the kit.
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

    /** A new round: everyone back to level 1 without the level health (call before the players' reset). */
    void newRound() {
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
        match.sounds().levelUp(player);
        if (session.isInPlay(player)) {
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 30,
                    0.4, 0.6, 0.4, 0.3);
        }
        SiegeProgressSettings s = settings();
        if (s.bonusHealth(to) > s.bonusHealth(from)) {
            messages.send(player, "level.health", Messages.tag("hearts", formatHearts(s.bonusHealth(to))));
        }
        AbilityInfo info = match.abilityInfo(player.getUniqueId());
        for (int level = from + 1; info != null && level <= to; level++) {
            String perk = SiegePerks.describe(info.id(), level);
            if (perk != null) {
                messages.send(player, "level.perk", Messages.tag("perk", perk));
            }
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

    // ------------------------------------------------------------------ HUD

    /**
     * The sidebar line under the level: the next level that brings the player's ability a perk and what it does
     * ("6레벨: 불바다 범위 +1블록"), at most {@code maxWidth} wide (the perk's name loses words from the front until it
     * fits); null at the top level, when no perk is left, or while levels or perks are off.
     */
    @Nullable Component nextPerkLine(UUID player, int maxWidth) {
        AbilityInfo info = match.abilityInfo(player);
        if (info == null || !settings().levelsEnabled() || !match.balance().settings().perks()) {
            return null;
        }
        SiegePerks.Perk perk = SiegePerks.next(info.id(), levels.level(player), settings().maxLevel());
        if (perk == null) {
            return null;
        }
        String change = perk.text().substring(perk.label().length());
        String label = perk.label();
        Component line = perkLine(perk.level(), label + change);
        while (TextWidth.of(line) > maxWidth && label.indexOf(' ') > 0) {
            label = label.substring(label.indexOf(' ') + 1);
            line = perkLine(perk.level(), label + change);
        }
        return line;
    }

    private Component perkLine(int level, String perk) {
        return messages.get("hud.next-perk", Messages.tag("level", level), Messages.tag("perk", perk));
    }

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
