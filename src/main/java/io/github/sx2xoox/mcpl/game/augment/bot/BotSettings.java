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
 * @param moveSpeed   movement speed attribute of a bot's legs (its pathing mob); {@link BotMotion#REFERENCE_ATTRIBUTE}
 *                    moves exactly like a player (walking, sprinting), the speed grows with its square
 * @param profiles    how well each difficulty plays
 * @param spectate    봇 관전전 ({@code bots.spectate.*})
 * @param personalities how often each {@link BotPersonality} is drawn (0 = never)
 * @param team        봇전 팀전 ({@code bots.team.*})
 */
public record BotSettings(int count, BotDifficulty difficulty, int max, List<String> names, List<String> skins,
                          double moveSpeed, Map<BotDifficulty, BotProfile> profiles, Spectate spectate,
                          Map<BotPersonality, Double> personalities, Team team) {

    /**
     * The {@code games.augment.bots.team.*} settings of 봇전 팀전 ({@code /게임 증강 봇전 팀 [팀당 인원] [팀 수]}).
     *
     * @param size            players per team when the command names none
     * @param count           teams when the command names none
     * @param cohesionRadius  blocks a bot keeps within of its team's leader (a human teammate, else the strongest bot)
     * @param sharedBase      the team's base builders build one base together (else each its own)
     * @param teamChat        team lines in bot chat (regroup, help, focus, ally down, team win)
     */
    public record Team(int size, int count, double cohesionRadius, boolean sharedBase, boolean teamChat) {

        public static final String SIZE = "bots.team.size";
        public static final String COUNT = "bots.team.count";
        public static final String COHESION = "bots.team.cohesion-radius";
        public static final String SHARED_BASE = "bots.team.shared-base";
        public static final String TEAM_CHAT = "bots.team.team-chat";
        /** The most teams (the hub's colour palette). */
        public static final int MAX_TEAMS = 8;
        public static final Team DEFAULTS = new Team(3, 2, 16, true, true);

        static void declareDefaults(ConfigurationSection d) {
            d.setComments("bots.team", List.of(
                    "봇전 팀전 (/게임 증강 봇전 팀 [팀당 인원] [팀 수] [난이도]): 사람은 팀마다 고르게 나뉘고 빈자리는 봇이 채웁니다.",
                    "봇끼리만: /게임 증강 봇전 관전 팀 3 4 보통. 마지막까지 남은 팀이 이깁니다."));
            d.set(SIZE, DEFAULTS.size());
            d.setComments(SIZE, List.of("팀당 인원을 쓰지 않았을 때"));
            d.set(COUNT, DEFAULTS.count());
            d.setComments(COUNT, List.of("팀 수를 쓰지 않았을 때 (2~" + MAX_TEAMS + ")"));
            d.set(COHESION, DEFAULTS.cohesionRadius());
            d.setComments(COHESION, List.of("봇이 팀장(사람 팀원, 없으면 가장 강한 봇)에게서 이만큼(블록) 안에 머뭅니다"));
            d.set(SHARED_BASE, DEFAULTS.sharedBase());
            d.setComments(SHARED_BASE, List.of("팀의 기지 짓는 봇들이 함께 기지 하나를 지음 (끄면 봇마다 자기 기지)"));
            d.set(TEAM_CHAT, DEFAULTS.teamChat());
            d.setComments(TEAM_CHAT, List.of("봇 채팅의 팀 대사 (합류, 도움 요청, 집중 공격, 팀원 사망, 팀 우승)"));
        }

        static Team read(ConfigurationSection s) {
            double radius = s.getDouble(COHESION, DEFAULTS.cohesionRadius());
            return new Team(Math.max(1, Math.min(HARD_MAX, s.getInt(SIZE, DEFAULTS.size()))),
                    Math.max(2, Math.min(MAX_TEAMS, s.getInt(COUNT, DEFAULTS.count()))),
                    Double.isFinite(radius) ? Math.max(4, Math.min(64, radius)) : DEFAULTS.cohesionRadius(),
                    s.getBoolean(SHARED_BASE, true), s.getBoolean(TEAM_CHAT, true));
        }
    }

    /**
     * The {@code games.augment.bots.spectate.*} settings of 봇 관전전 ({@code /게임 증강 봇전 관전}): a quicker match
     * than the configured one (normal matches and 봇전 keep their timings).
     *
     * @param farmingTicks      farming length (the augment rounds move by the same ratio)
     * @param borderDelayTicks  fight time before the border shrinks
     * @param borderShrinkTicks how long the border takes to close
     * @param behaviourChat     a short chat line when a bot starts something notable (fight, retreat, shelter)
     * @param timeScale         the match clocks' speed at the start (1-4, {@code /게임 증강 배속})
     */
    public record Spectate(long farmingTicks, long borderDelayTicks, long borderShrinkTicks, boolean behaviourChat,
                           int timeScale) {

        public static final String FARMING = "bots.spectate.farming-seconds";
        public static final String BORDER_DELAY = "bots.spectate.border-start-delay-seconds";
        public static final String BORDER_SHRINK = "bots.spectate.border-shrink-seconds";
        public static final String BEHAVIOUR_CHAT = "bots.spectate.behaviour-chat";
        public static final String TIME_SCALE = "bots.spectate.time-scale";
        /** The fastest {@code /게임 증강 배속}. */
        public static final int MAX_TIME_SCALE = 4;

        static void declareDefaults(ConfigurationSection d) {
            d.setComments("bots.spectate", List.of(
                    "봇 관전전 (/게임 증강 봇전 관전 [봇 수] [난이도 | 난이도별 수]): 사람은 모두 관전하고 봇끼리 끝까지 싸웁니다.",
                    "보통 경기보다 빠르게 진행됩니다 (보통 경기·봇전의 시간은 그대로). 증강 라운드는 파밍 시간 비율대로 당겨집니다."));
            d.set(FARMING, 300);
            d.setComments(FARMING, List.of("관전전의 파밍 시간(초)"));
            d.set(BORDER_DELAY, 30);
            d.setComments(BORDER_DELAY, List.of("관전전: 전투 시작 후 월드보더가 줄어들기 시작할 때까지(초)"));
            d.set(BORDER_SHRINK, 360);
            d.setComments(BORDER_SHRINK, List.of("관전전: 월드보더가 최종 크기까지 줄어드는 시간(초)"));
            d.set(BEHAVIOUR_CHAT, true);
            d.setComments(BEHAVIOUR_CHAT, List.of("관전전: 봇이 교전·후퇴·진지 구축 같은 행동을 시작하면 채팅에 짧게 알림"));
            d.set(TIME_SCALE, 1);
            d.setComments(TIME_SCALE, List.of("관전전 시작 배속 (1~" + MAX_TIME_SCALE
                    + "): 경기 시계(파밍·라운드·보더·장비·기지 짓기)만 빨라집니다. 경기 중에는 /게임 증강 배속"));
        }

        static Spectate read(ConfigurationSection s) {
            return new Spectate(ticks(s.getDouble(FARMING, 300)), ticks(s.getDouble(BORDER_DELAY, 30)),
                    ticks(s.getDouble(BORDER_SHRINK, 360)), s.getBoolean(BEHAVIOUR_CHAT, true),
                    Math.max(1, Math.min(MAX_TIME_SCALE, s.getInt(TIME_SCALE, 1))));
        }

        private static long ticks(double seconds) {
            double value = Double.isFinite(seconds) ? Math.max(0, Math.min(7200, seconds)) : 0;
            return Math.round(value * 20.0);
        }
    }

    public static final String COUNT = "bots.count";
    public static final String DIFFICULTY = "bots.difficulty";
    public static final String MAX = "bots.max";
    public static final String NAMES = "bots.names";
    public static final String SKINS = "bots.skins";
    public static final String SPEED = "bots.move-speed";
    public static final String PERSONALITIES = "bots.personalities";

    /** Hard cap of bots in one match (performance). */
    public static final int HARD_MAX = 15;

    static final List<String> DEFAULT_NAMES = List.of("철수", "영희", "민수", "지민", "서연", "도윤", "하준", "지우",
            "수아", "예준", "시우", "하은", "유진", "현우", "다은");

    public BotSettings {
        names = List.copyOf(names);
        skins = List.copyOf(skins);
        profiles = Map.copyOf(profiles);
        personalities = Map.copyOf(personalities);
    }

    public BotProfile profile(BotDifficulty of) {
        return profiles.getOrDefault(of, BotProfile.preset(of));
    }

    /** {@link #name(int)} with the difficulty: "철수 봇 [어려움]" (mixed lineups and 봇 관전전). */
    public String name(int index, BotDifficulty difficulty) {
        return name(index) + " [" + difficulty.koreanName() + "]";
    }

    /** {@link #name(int)} with the difficulty and personality: "철수 봇 [어려움·사냥꾼]" (봇 관전전, mixed lineups). */
    public String name(int index, BotDifficulty difficulty, @org.jspecify.annotations.Nullable BotPersonality personality) {
        return personality == null ? name(index, difficulty)
                : name(index) + " [" + difficulty.koreanName() + "·" + personality.koreanName() + "]";
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
                "혼자서도 할 수 있고, 사람이 여럿이어도 됩니다. 봇이 이기면 사람은 집니다.",
                "난이도를 섞을 수도 있습니다: /게임 증강 봇전 쉬움 3 어려움 2"));
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
        d.setComments(SPEED, List.of("봇의 이동 속도 (길찾기 몹의 이동 속도 속성). 0.32 = 사람과 같은 속도: 가까이는 걷고(4.3블록/초)",
                "멀리 갈 때·쫓을 때·도망칠 때·보더를 피할 때는 달립니다(5.6블록/초). 속도는 이 값의 제곱에 비례합니다."));
        for (BotDifficulty difficulty : BotDifficulty.values()) {
            String path = "bots." + difficulty.key();
            BotProfile.preset(difficulty).toConfig().forEach((key, value) -> d.set(path + "." + key, value));
        }
        d.setComments("bots." + BotDifficulty.EASY.key(), BotProfile.COMMENTS);
        for (BotPersonality each : BotPersonality.values()) {
            d.set(PERSONALITIES + "." + each.key(), 1.0);
        }
        d.setComments(PERSONALITIES, List.of(
                "봇 성격과 뽑힐 비중 (0 = 쓰지 않음). 성격은 행동 점수에 곱해지는 성향입니다 (난이도는 실력):",
                "hunter 사냥꾼 · fortress 요새꾼 · archer 저격수 · opportunist 어부지리형 · berserker 광전사 · survivor 생존왕",
                "trapper 트래퍼 · assassin 암살자 · tank 탱커 · avenger 복수귀 · gambler 도박사 · nomad 유목민"));
        Spectate.declareDefaults(d);
        Team.declareDefaults(d);
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
        Map<BotPersonality, Double> personalities = new EnumMap<>(BotPersonality.class);
        for (BotPersonality each : BotPersonality.values()) {
            double weight = s.getDouble(PERSONALITIES + "." + each.key(), 1.0);
            personalities.put(each, Double.isFinite(weight) ? Math.max(0, Math.min(100, weight)) : 1.0);
        }
        return new BotSettings(Math.max(1, Math.min(max, s.getInt(COUNT, 5))),
                difficulty != null ? difficulty : BotDifficulty.NORMAL, max, names, skins,
                Double.isFinite(speed) ? Math.max(0.05, Math.min(1.0, speed)) : 0.32, profiles,
                Spectate.read(s), personalities, Team.read(s));
    }
}
