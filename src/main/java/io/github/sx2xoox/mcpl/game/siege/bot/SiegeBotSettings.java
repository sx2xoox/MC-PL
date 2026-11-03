package io.github.sx2xoox.mcpl.game.siege.bot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The numbers of 공성전 봇전 ({@code games.siege.bots.*}): how many bots a side gets by default, their names and jobs,
 * the jobs' stats ({@link JobStats}, health points, blocks and seconds) and the difficulties ({@link Profile}).
 *
 * @param perSide           bots fill each side up to this many fighters when the command names no number
 * @param difficulty        the difficulty when the command names none
 * @param names             the bots' names ("철수" → "철수 봇")
 * @param jobs              the jobs bots are dealt (each side cycles through them)
 * @param legSpeed          movement speed attribute of a bot's legs (0.32: a walking player at speed 1)
 * @param recallTicks       how long a bot channels its recall to the base (cancelled by a hit)
 * @param baseHeal          share of its maximum health a bot heals per second at its base
 * @param regen             share of its maximum health a bot heals per second after {@code regenDelayTicks} unhurt
 * @param regenDelayTicks   time without damage before the slow healing starts
 * @param upgradeCost       gold one upgrade costs (bots buy them at the base)
 * @param upgradeDamage     extra damage share per upgrade
 * @param upgradeHealth     extra maximum health per upgrade
 * @param maxUpgrades       upgrades a bot may buy in a round
 * @param stats             each job's numbers
 * @param profiles          each difficulty's numbers
 */
public record SiegeBotSettings(int perSide, SiegeBotDifficulty difficulty, List<String> names, List<BotJob> jobs,
                               double legSpeed, int recallTicks, double baseHeal, double regen, int regenDelayTicks,
                               int upgradeCost, double upgradeDamage, double upgradeHealth, int maxUpgrades,
                               Map<BotJob, JobStats> stats, Map<SiegeBotDifficulty, Profile> profiles) {

    public static final String PATH = "bots.";
    /** Most fighters a side may be filled up to. */
    public static final int MAX_PER_SIDE = 10;

    public SiegeBotSettings {
        names = List.copyOf(names);
        jobs = List.copyOf(jobs);
        stats = Map.copyOf(stats);
        profiles = Map.copyOf(profiles);
    }

    /**
     * One job's numbers.
     *
     * @param health        maximum health at level 1 (a player has 20)
     * @param defense       share of every hit it takes away (0.2 = 20 % less)
     * @param speed         walking speed (1 = a walking player)
     * @param damage        basic attack damage at level 1 (healer: its bolt)
     * @param range         basic attack reach in blocks
     * @param attackTicks   time between basic attacks
     * @param skillDamage   the skill's damage (healer: the health it heals)
     * @param skillRange    how far the skill reaches (assassin: the dash)
     * @param skillRadius   the skill's area around its target (0 = the target only)
     * @param skillCount    archer: enemies the volley hits at most
     * @param skillCooldown time between two skills
     * @param skillEffect   knight: how long the bash slows (ticks)
     */
    public record JobStats(double health, double defense, double speed, double damage, double range, int attackTicks,
                           double skillDamage, double skillRange, double skillRadius, int skillCount,
                           int skillCooldown, int skillEffect) {

        /** The defaults, also the values written to {@code config.yml}. */
        public static JobStats preset(BotJob job) {
            return switch (job) {
                case KNIGHT -> new JobStats(30, 0.2, 1.0, 4.5, 2.8, 18, 5, 3.5, 0, 1, 160, 30);
                case ARCHER -> new JobStats(20, 0, 1.05, 3, 12, 18, 3, 14, 3, 3, 180, 0);
                case MAGE -> new JobStats(20, 0, 1.0, 3.2, 10, 22, 6, 12, 3, 1, 200, 0);
                case HEALER -> new JobStats(22, 0.05, 1.0, 2.4, 10, 20, 6, 10, 0, 1, 160, 0);
                case ASSASSIN -> new JobStats(22, 0.05, 1.15, 4, 2.6, 14, 6.5, 8, 0, 1, 200, 0);
            };
        }

        static JobStats read(ConfigurationSection c, String path, JobStats d) {
            return new JobStats(
                    clamp(c.getDouble(path + "health", d.health), 1, 1000, d.health),
                    clamp(c.getDouble(path + "defense", d.defense), 0, 0.9, d.defense),
                    clamp(c.getDouble(path + "speed", d.speed), 0.2, 3, d.speed),
                    clamp(c.getDouble(path + "damage", d.damage), 0, 100, d.damage),
                    clamp(c.getDouble(path + "range", d.range), 1, 32, d.range),
                    ticks(c.getDouble(path + "attack-seconds", d.attackTicks / 20.0), 0.25, d.attackTicks),
                    clamp(c.getDouble(path + "skill-damage", d.skillDamage), 0, 100, d.skillDamage),
                    clamp(c.getDouble(path + "skill-range", d.skillRange), 1, 32, d.skillRange),
                    clamp(c.getDouble(path + "skill-radius", d.skillRadius), 0, 16, d.skillRadius),
                    (int) Math.round(clamp(c.getDouble(path + "skill-count", d.skillCount), 1, 10, d.skillCount)),
                    ticks(c.getDouble(path + "skill-cooldown-seconds", d.skillCooldown / 20.0), 1, d.skillCooldown),
                    ticks(c.getDouble(path + "skill-effect-seconds", d.skillEffect / 20.0), 0, d.skillEffect));
        }

        void write(ConfigurationSection d, String path) {
            d.set(path + "health", health);
            d.set(path + "defense", defense);
            d.set(path + "speed", speed);
            d.set(path + "damage", damage);
            d.set(path + "range", range);
            d.set(path + "attack-seconds", attackTicks / 20.0);
            d.set(path + "skill-damage", skillDamage);
            d.set(path + "skill-range", skillRange);
            d.set(path + "skill-radius", skillRadius);
            d.set(path + "skill-count", skillCount);
            d.set(path + "skill-cooldown-seconds", skillCooldown / 20.0);
            d.set(path + "skill-effect-seconds", skillEffect / 20.0);
        }

        /** Basic attack damage per second at level 1. */
        public double damagePerSecond() {
            return damage * 20.0 / attackTicks;
        }
    }

    /**
     * One difficulty's numbers.
     *
     * @param reactionTicks time between two decisions (and before reacting to something new)
     * @param accuracy      chance a ranged attack or skill hits (melee always hits what is in reach)
     * @param skillChance   chance to use a ready skill when it could
     * @param retreatAt     health share at or below which it backs off and recalls
     * @param aggression    0..1: how readily it takes a fight (1 = even when outnumbered a little)
     * @param focusLowest   chance to pick the enemy with the least health instead of the nearest
     * @param lastHit       chance to aim at a minion its next hit kills (gold) instead of the nearest
     * @param jungleChance  chance (every 5 seconds of a quiet lane) to go take a jungle camp or the dragon
     * @param damageScale   multiplier of everything it deals
     */
    public record Profile(int reactionTicks, double accuracy, double skillChance, double retreatAt, double aggression,
                          double focusLowest, double lastHit, double jungleChance, double damageScale) {

        public static Profile preset(SiegeBotDifficulty difficulty) {
            return switch (difficulty) {
                case EASY -> new Profile(16, 0.7, 0.35, 0.2, 0.35, 0.2, 0.2, 0.04, 0.8);
                case NORMAL -> new Profile(8, 0.85, 0.7, 0.3, 0.55, 0.6, 0.6, 0.08, 1.0);
                case HARD -> new Profile(4, 0.95, 1.0, 0.35, 0.7, 1.0, 0.9, 0.12, 1.1);
            };
        }

        static Profile read(ConfigurationSection c, String path, Profile d) {
            return new Profile(
                    ticks(c.getDouble(path + "reaction-seconds", d.reactionTicks / 20.0), 0.05, d.reactionTicks),
                    clamp(c.getDouble(path + "accuracy", d.accuracy), 0, 1, d.accuracy),
                    clamp(c.getDouble(path + "skill-chance", d.skillChance), 0, 1, d.skillChance),
                    clamp(c.getDouble(path + "retreat-at", d.retreatAt), 0, 0.9, d.retreatAt),
                    clamp(c.getDouble(path + "aggression", d.aggression), 0, 1, d.aggression),
                    clamp(c.getDouble(path + "focus-lowest", d.focusLowest), 0, 1, d.focusLowest),
                    clamp(c.getDouble(path + "last-hit", d.lastHit), 0, 1, d.lastHit),
                    clamp(c.getDouble(path + "jungle-chance", d.jungleChance), 0, 1, d.jungleChance),
                    clamp(c.getDouble(path + "damage-scale", d.damageScale), 0.1, 5, d.damageScale));
        }

        void write(ConfigurationSection d, String path) {
            d.set(path + "reaction-seconds", reactionTicks / 20.0);
            d.set(path + "accuracy", accuracy);
            d.set(path + "skill-chance", skillChance);
            d.set(path + "retreat-at", retreatAt);
            d.set(path + "aggression", aggression);
            d.set(path + "focus-lowest", focusLowest);
            d.set(path + "last-hit", lastHit);
            d.set(path + "jungle-chance", jungleChance);
            d.set(path + "damage-scale", damageScale);
        }
    }

    static final String PER_SIDE = PATH + "per-side";
    static final String DIFFICULTY = PATH + "difficulty";
    static final String NAMES = PATH + "names";
    static final String JOBS = PATH + "jobs";
    static final String SPEED = PATH + "move-speed";
    static final String RECALL = PATH + "recall-seconds";
    static final String BASE_HEAL = PATH + "base-heal-per-second";
    static final String REGEN = PATH + "regen-per-second";
    static final String REGEN_DELAY = PATH + "regen-delay-seconds";
    static final String UPGRADE = PATH + "upgrade.";

    static final List<String> DEFAULT_NAMES = List.of("철수", "영희", "민수", "지민", "서연", "도윤", "하준", "지우",
            "수아", "예준", "시우", "하은", "유진", "현우", "다은", "준서", "서윤", "주원", "지아", "건우");

    public static SiegeBotSettings defaults() {
        return read(new org.bukkit.configuration.MemoryConfiguration());
    }

    /** Reads {@code games.siege} ({@code bots.*}); missing or invalid values fall back to the defaults. */
    public static SiegeBotSettings read(ConfigurationSection c) {
        SiegeBotDifficulty difficulty = SiegeBotDifficulty.parse(c.getString(DIFFICULTY, "normal"));
        List<String> names = new ArrayList<>();
        for (String name : c.getStringList(NAMES)) {
            if (!name.isBlank()) {
                names.add(name.trim());
            }
        }
        List<BotJob> jobs = new ArrayList<>();
        for (String key : c.getStringList(JOBS)) {
            BotJob job = BotJob.parse(key);
            if (job != null && !jobs.contains(job)) {
                jobs.add(job);
            }
        }
        Map<BotJob, JobStats> stats = new EnumMap<>(BotJob.class);
        for (BotJob job : BotJob.values()) {
            stats.put(job, JobStats.read(c, PATH + "job." + job.key() + ".", JobStats.preset(job)));
        }
        Map<SiegeBotDifficulty, Profile> profiles = new EnumMap<>(SiegeBotDifficulty.class);
        for (SiegeBotDifficulty level : SiegeBotDifficulty.values()) {
            profiles.put(level, Profile.read(c, PATH + "profile." + level.key() + ".", Profile.preset(level)));
        }
        return new SiegeBotSettings(
                (int) Math.round(clamp(c.getDouble(PER_SIDE, 3), 1, MAX_PER_SIDE, 3)),
                difficulty == null ? SiegeBotDifficulty.NORMAL : difficulty,
                names.isEmpty() ? DEFAULT_NAMES : names,
                jobs.isEmpty() ? List.of(BotJob.values()) : jobs,
                clamp(c.getDouble(SPEED, 0.32), 0.1, 1, 0.32),
                ticks(c.getDouble(RECALL, 6), 0, 120),
                clamp(c.getDouble(BASE_HEAL, 0.15), 0, 1, 0.15),
                clamp(c.getDouble(REGEN, 0.01), 0, 1, 0.01),
                ticks(c.getDouble(REGEN_DELAY, 6), 0, 120),
                (int) Math.round(clamp(c.getDouble(UPGRADE + "cost", 600), 0, 100_000, 600)),
                clamp(c.getDouble(UPGRADE + "damage", 0.08), 0, 1, 0.08),
                clamp(c.getDouble(UPGRADE + "health", 2), 0, 40, 2),
                (int) Math.round(clamp(c.getDouble(UPGRADE + "max", 4), 0, 20, 4)),
                stats, profiles);
    }

    /** Declares the defaults (with Korean comments) merged into {@code config.yml} → {@code games.siege}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.setComments("bots", List.of(
                "공성전 봇전 (/게임 시작 공성전 봇전 [팀당 인원] [쉬움|보통|어려움]): 봇이 양 팀의 빈자리를 채웁니다.",
                "봇은 초능력 대신 봇 전용 직업(기사·궁수·마법사·치유사·암살자)으로 싸웁니다. 수치는 체력·블록·초."));
        d.set(PER_SIDE, 3);
        d.setComments(PER_SIDE, List.of("명령에 인원이 없을 때 한 팀을 이 인원까지 봇으로 채움 (사람 포함, 최대 "
                + MAX_PER_SIDE + ")"));
        d.set(DIFFICULTY, "normal");
        d.setComments(DIFFICULTY, List.of("명령에 난이도가 없을 때: easy(쉬움) · normal(보통) · hard(어려움)"));
        d.set(NAMES, DEFAULT_NAMES);
        d.setComments(NAMES, List.of("봇 이름 (\"철수\" → \"철수 봇\")"));
        List<String> jobKeys = new ArrayList<>();
        for (BotJob job : BotJob.values()) {
            jobKeys.add(job.key());
        }
        d.set(JOBS, jobKeys);
        d.setComments(JOBS, List.of("봇에게 나눠 줄 직업 (팀마다 차례로 돌아가며): knight 기사, archer 궁수, mage 마법사,",
                "healer 치유사, assassin 암살자"));
        d.set(SPEED, 0.32);
        d.setComments(SPEED, List.of("봇 다리(길찾기 몹)의 이동 속도 속성. 0.32 = 걷는 사람과 비슷"));
        d.set(RECALL, 6);
        d.setComments(RECALL, List.of("체력이 낮을 때 귀환하는 데 드는 시간(초). 맞으면 취소"));
        d.set(BASE_HEAL, 0.15);
        d.setComments(BASE_HEAL, List.of("본진에서 초당 회복 (최대 체력 비율)"));
        d.set(REGEN, 0.01);
        d.set(REGEN_DELAY, 6);
        d.setComments(REGEN, List.of("regen-delay-seconds 초 동안 안 맞으면 초당 이만큼 회복 (최대 체력 비율)"));
        d.set(UPGRADE + "cost", 600);
        d.set(UPGRADE + "damage", 0.08);
        d.set(UPGRADE + "health", 2);
        d.set(UPGRADE + "max", 4);
        d.setComments("bots.upgrade", List.of("봇은 본진에서 골드로 강화를 삽니다: cost 골드마다 피해 +damage(비율), 최대 체력 +health,",
                "한 판에 max 번까지"));
        d.setComments("bots.job", List.of(
                "직업 수치 (1레벨 기준, 레벨에 따라 능력처럼 강해짐): health 체력 · defense 받는 피해 감소 비율 · speed 이동 속도",
                "damage·range·attack-seconds 기본 공격 · skill-* 스킬 (치유사 skill-damage = 회복량,",
                "암살자 skill-range = 돌진 거리, 궁수 skill-count = 맞히는 적 수, 기사 skill-effect-seconds = 둔화 시간)"));
        for (BotJob job : BotJob.values()) {
            JobStats.preset(job).write(d, PATH + "job." + job.key() + ".");
        }
        d.setComments("bots.profile", List.of(
                "난이도별: reaction-seconds 판단 간격 · accuracy 원거리 명중률 · skill-chance 스킬 사용률",
                "retreat-at 이 체력 비율 이하면 후퇴·귀환 · aggression 싸움을 거는 성향 · focus-lowest 체력 낮은 적을 노릴 확률",
                "last-hit 막타 노리기 확률 · jungle-chance 라인이 조용할 때 정글·드래곤에 갈 확률(5초마다) · damage-scale 피해 배율"));
        for (SiegeBotDifficulty level : SiegeBotDifficulty.values()) {
            Profile.preset(level).write(d, PATH + "profile." + level.key() + ".");
        }
    }

    public JobStats stats(BotJob job) {
        return stats.getOrDefault(job, JobStats.preset(job));
    }

    public Profile profile(SiegeBotDifficulty level) {
        return profiles.getOrDefault(level, Profile.preset(level));
    }

    private static int ticks(double seconds, double minSeconds, int fallback) {
        if (!Double.isFinite(seconds)) {
            return fallback;
        }
        return (int) Math.round(Math.max(minSeconds, Math.min(600, seconds)) * 20);
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
