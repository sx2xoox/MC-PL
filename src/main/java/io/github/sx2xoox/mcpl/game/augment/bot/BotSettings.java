package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * The {@code games.augment.bots.*} settings of {@code config.yml} for 봇전 ({@code /게임 증강 봇전}).
 *
 * @param count       bots added when the command names no count
 * @param difficulty  difficulty when the command names none
 * @param max         the most bots one match may have
 * @param names       bot names ("철수" → "철수 봇"), used in order
 * @param skins       Minecraft account names whose skins the bots wear (empty: default skins)
 * @param moveSpeed   walking speed of a bot's legs (movement speed attribute of its pathing mob)
 * @param profiles    how well each difficulty plays
 */
public record BotSettings(int count, BotDifficulty difficulty, int max, List<String> names, List<String> skins,
                          double moveSpeed, Map<BotDifficulty, BotProfile> profiles) {

    public static final String COUNT = "bots.count";
    public static final String DIFFICULTY = "bots.difficulty";
    public static final String MAX = "bots.max";
    public static final String NAMES = "bots.names";
    public static final String SKINS = "bots.skins";
    public static final String SPEED = "bots.move-speed";

    /** Hard cap of bots in one match (performance). */
    public static final int HARD_MAX = 15;

    static final List<String> DEFAULT_NAMES = List.of("철수", "영희", "민수", "지민", "서연", "도윤", "하준", "지우",
            "수아", "예준", "시우", "하은", "유진", "현우", "다은");

    public BotSettings {
        names = List.copyOf(names);
        skins = List.copyOf(skins);
        profiles = Map.copyOf(profiles);
    }

    public BotProfile profile(BotDifficulty of) {
        return profiles.getOrDefault(of, BotProfile.preset(of));
    }

    /** The display name of the {@code index}-th bot (0-based): "철수 봇", ... ("봇 16" past the list). */
    public String name(int index) {
        String base = index < names.size() ? names.get(index) : Integer.toString(index + 1);
        return base + " 봇";
    }

    /** Declares the defaults (with Korean comments) in {@code games.augment}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.setComments("bots", List.of(
                "봇전 (/게임 증강 봇전 [봇 수] [쉬움|보통|어려움|지옥]): 봇이 빈자리를 채우는 진짜 증강 야생전.",
                "혼자서도 할 수 있고, 사람이 여럿이어도 됩니다. 봇이 이기면 사람은 집니다."));
        d.set(COUNT, 5);
        d.setComments(COUNT, List.of("봇 수를 쓰지 않았을 때 넣는 봇 수"));
        d.set(DIFFICULTY, BotDifficulty.NORMAL.key());
        d.setComments(DIFFICULTY, List.of("난이도를 쓰지 않았을 때: easy(쉬움), normal(보통), hard(어려움), hell(지옥)"));
        d.set(MAX, 15);
        d.setComments(MAX, List.of("한 경기의 최대 봇 수 (1~" + HARD_MAX + ", 서버 성능)"));
        d.set(NAMES, DEFAULT_NAMES);
        d.setComments(NAMES, List.of("봇 이름 (\"철수\" → \"철수 봇\")"));
        d.set(SKINS, List.of());
        d.setComments(SKINS, List.of("봇이 입을 스킨의 마인크래프트 계정 이름 (비우면 기본 스킨)"));
        d.set(SPEED, 0.32);
        d.setComments(SPEED, List.of("봇의 걷는 속도 (길찾기 몹의 이동 속도 속성; 사람 달리기와 비슷하게 맞춘 값)"));
        for (BotDifficulty difficulty : BotDifficulty.values()) {
            String path = "bots." + difficulty.key();
            BotProfile.preset(difficulty).toConfig().forEach((key, value) -> d.set(path + "." + key, value));
        }
        d.setComments("bots." + BotDifficulty.EASY.key(), BotProfile.COMMENTS);
    }

    /** Reads the live {@code games.augment} section; invalid values fall back to the defaults. */
    public static BotSettings read(ConfigurationSection s) {
        BotDifficulty difficulty = BotDifficulty.parse(s.getString(DIFFICULTY));
        List<String> names = new ArrayList<>();
        for (String name : s.getStringList(NAMES)) {
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }
        if (names.isEmpty()) {
            names.addAll(DEFAULT_NAMES);
        }
        List<String> skins = new ArrayList<>();
        for (String skin : s.getStringList(SKINS)) {
            if (skin != null && skin.matches("[A-Za-z0-9_]{1,16}")) {
                skins.add(skin);
            }
        }
        Map<BotDifficulty, BotProfile> profiles = new EnumMap<>(BotDifficulty.class);
        for (BotDifficulty each : BotDifficulty.values()) {
            ConfigurationSection section = s.getConfigurationSection("bots." + each.key());
            profiles.put(each, BotProfile.read(section != null ? section : new MemoryConfiguration(), each));
        }
        int max = Math.max(1, Math.min(HARD_MAX, s.getInt(MAX, 15)));
        double speed = s.getDouble(SPEED, 0.32);
        return new BotSettings(Math.max(1, Math.min(max, s.getInt(COUNT, 5))),
                difficulty != null ? difficulty : BotDifficulty.NORMAL, max, names, skins,
                Double.isFinite(speed) ? Math.max(0.05, Math.min(1.0, speed)) : 0.32, profiles);
    }
}
