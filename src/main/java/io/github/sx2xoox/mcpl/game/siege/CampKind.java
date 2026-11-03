package io.github.sx2xoox.mcpl.game.siege;

import java.util.Locale;

import org.bukkit.Color;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PiglinBrute;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Vex;
import org.bukkit.entity.WitherSkeleton;
import org.jspecify.annotations.Nullable;

/**
 * The monsters of a 공성전 camp (docs/siege.md §13), by the map's {@code SiegeSites.Camp#kind()}: which vanilla mob,
 * its tier (small camp, buff camp, epic) and the colour of its gear. Numbers (count, health, damage, size) live in
 * {@link CampSettings}.
 */
public enum CampKind {
    /** 드론 둥지: a few small flying drones. */
    DRONES("drones", Tier.SMALL, Vex.class, null),
    /** 변종 쥐떼: a pack of mutant rats. */
    RATS("rats", Tier.SMALL, Silverfish.class, null),
    /** 고철 골렘: two scrap golems. */
    SCRAP("scrap", Tier.SMALL, IronGolem.class, null),
    /** 레드 서버: an elite brute in red; the killing team burns what it hits. */
    RED("red", Tier.BUFF, PiglinBrute.class, Color.fromRGB(0xE0, 0x24, 0x3B)),
    /** 블루 서버: an elite in blue; the killing team's cooldowns and gauge run faster. */
    BLUE("blue", Tier.BUFF, WitherSkeleton.class, Color.fromRGB(0x25, 0x6B, 0xF0)),
    /** 메인프레임 수호자: the epic boss the whole team fights. */
    GUARDIAN("guardian", Tier.EPIC, Ravager.class, null);

    /** How big a camp is: how often it comes back, what it gives and how it is announced. */
    public enum Tier {
        SMALL("small"), BUFF("buff"), EPIC("epic");

        private final String key;

        Tier(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    private final String id;
    private final Tier tier;
    private final Class<? extends Mob> type;
    private final @Nullable Color gear;

    CampKind(String id, Tier tier, Class<? extends Mob> type, @Nullable Color gear) {
        this.id = id;
        this.tier = tier;
        this.type = type;
        this.gear = gear;
    }

    String id() {
        return id;
    }

    Tier tier() {
        return tier;
    }

    Class<? extends Mob> type() {
        return type;
    }

    /** Dyed leather armour colour, or null for a mob without gear. */
    @Nullable Color gear() {
        return gear;
    }

    /** The kind with this map id, or null for an unknown one (the camp is skipped). */
    static @Nullable CampKind byId(String id) {
        String wanted = id.toLowerCase(Locale.ROOT);
        for (CampKind kind : values()) {
            if (kind.id.equals(wanted)) {
                return kind;
            }
        }
        return null;
    }
}
