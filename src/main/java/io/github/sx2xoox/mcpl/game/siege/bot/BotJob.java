package io.github.sx2xoox.mcpl.game.siege.bot;

import java.util.Locale;

import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * The jobs only 공성전 봇전 bots play (docs/siege-v2.md §9): a basic attack and one skill each, much simpler than the
 * 초능력전쟁 abilities. Their numbers are {@link SiegeBotSettings.JobStats} ({@code games.siege.bots.job.<key>.*}).
 */
public enum BotJob {
    /** 기사: melee tank, 방패 강타 (a hit that slows and pushes back). */
    KNIGHT("knight", "기사", "검 베기", "방패 강타", SiegeBotBrain.Role.MELEE, Material.IRON_SWORD, Material.SHIELD),
    /** 궁수: ranged, 연발 사격 (arrows at the target and the enemies around it). */
    ARCHER("archer", "궁수", "화살", "연발 사격", SiegeBotBrain.Role.RANGED, Material.BOW, null),
    /** 마법사: ranged, 화염구 (an explosion around the target). */
    MAGE("mage", "마법사", "마력 탄", "화염구", SiegeBotBrain.Role.RANGED, Material.BLAZE_ROD, null),
    /** 치유사: support, weak bolts, 치유의 빛 (heals the most hurt ally nearby). */
    HEALER("healer", "치유사", "빛 구슬", "치유의 빛", SiegeBotBrain.Role.SUPPORT, Material.AMETHYST_SHARD, null),
    /** 암살자: fast melee, 그림자 일격 (a dash to the target and a heavy hit). */
    ASSASSIN("assassin", "암살자", "단검 베기", "그림자 일격", SiegeBotBrain.Role.MELEE, Material.STONE_SWORD,
            Material.STONE_SWORD);

    private final String key;
    private final String koreanName;
    private final String attackName;
    private final String skillName;
    private final SiegeBotBrain.Role role;
    private final Material weapon;
    private final @Nullable Material offhand;

    BotJob(String key, String koreanName, String attackName, String skillName, SiegeBotBrain.Role role,
           Material weapon, @Nullable Material offhand) {
        this.key = key;
        this.koreanName = koreanName;
        this.attackName = attackName;
        this.skillName = skillName;
        this.role = role;
        this.weapon = weapon;
        this.offhand = offhand;
    }

    /** Config key ({@code knight}, {@code archer}, ...). */
    public String key() {
        return key;
    }

    public String koreanName() {
        return koreanName;
    }

    public String attackName() {
        return attackName;
    }

    public String skillName() {
        return skillName;
    }

    public SiegeBotBrain.Role role() {
        return role;
    }

    /** True for the jobs that attack from afar (their basic attack and skill fly). */
    public boolean ranged() {
        return role != SiegeBotBrain.Role.MELEE;
    }

    public Material weapon() {
        return weapon;
    }

    public @Nullable Material offhand() {
        return offhand;
    }

    /** Parses a key or a Korean name; null if unknown. */
    public static @Nullable BotJob parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        for (BotJob job : values()) {
            if (job.key.equals(wanted) || job.koreanName.equals(wanted) || job.name().equalsIgnoreCase(wanted)) {
                return job;
            }
        }
        return null;
    }
}
