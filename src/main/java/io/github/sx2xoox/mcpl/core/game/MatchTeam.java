package io.github.sx2xoox.mcpl.core.game;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.entity.Player;

/**
 * One team of a team-mode match. Teams are created by the hub from the configured team size (see
 * {@link TeamAssigner}); a free-for-all match has no teams at all ({@link GameSession#teams()} is empty).
 *
 * <p>Members are participant UUIDs, including eliminated and offline ones. Only the hub adds members (late joiners
 * go to the smallest team).
 */
public final class MatchTeam {

    private final int index;
    private final String id;
    private final String shortName;
    private final NamedTextColor color;
    private final DyeColor dyeColor;
    private final Set<UUID> members = new LinkedHashSet<>();

    MatchTeam(int index, String id, String shortName, NamedTextColor color, DyeColor dyeColor) {
        this.index = index;
        this.id = id;
        this.shortName = shortName;
        this.color = color;
        this.dyeColor = dyeColor;
    }

    /** 0-based position in {@link GameSession#teams()}. */
    public int index() {
        return index;
    }

    /** Stable lowercase id such as {@code "red"} (or {@code "team17"} beyond the palette). */
    public String id() {
        return id;
    }

    /** Short Korean color name such as {@code "빨강"}. */
    public String shortName() {
        return shortName;
    }

    /** Korean team name such as {@code "빨강 팀"}. */
    public String name() {
        return shortName + " 팀";
    }

    /** The team name in the team color. */
    public Component displayName() {
        return Component.text(name(), color);
    }

    public NamedTextColor color() {
        return color;
    }

    /** Matching dye color, for wool, banners, leather armor or fireworks. */
    public DyeColor dyeColor() {
        return dyeColor;
    }

    /** RGB color for leather armor and potion/firework colors. */
    public Color rgb() {
        return dyeColor.getColor();
    }

    /** Participant UUIDs on this team (unmodifiable live view). */
    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public boolean contains(UUID playerId) {
        return members.contains(playerId);
    }

    public boolean contains(Player player) {
        return members.contains(player.getUniqueId());
    }

    public int size() {
        return members.size();
    }

    void add(UUID playerId) {
        members.add(playerId);
    }

    @Override
    public String toString() {
        return "MatchTeam[" + id + ", " + members.size() + " members]";
    }
}
