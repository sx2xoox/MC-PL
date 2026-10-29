package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * 봇 채팅: what a bot says in chat and when (pure, no Bukkit calls). The match's chat feed reports what happened
 * ({@link Trigger}); this decides whether the bot speaks (the trigger's chance roll, its personality's talkativeness
 * and rate limits per bot and for all bots) and picks a line from the editable pool ({@code augment-bots-chat.yml}),
 * preferring its personality's own lines. Placeholders ({@code <target>}, {@code <augment>}, ...) are filled by the
 * caller; a line needing one the event has not got is skipped.
 */
public final class BotChat {

    /** What happened. The key is the section of the lines file and of {@code bots.chat.chances}. */
    public enum Trigger {
        /** The farming starts: hello. */
        GREETING("greeting", 0.3, false),
        /** Its base is finished. */
        BASE_DONE("base-done", 0.2, false),
        /** Its gear got a tier better. */
        GEAR("gear", 0.2, false),
        /** It got an augment. */
        AUGMENT("augment", 0.2, false),
        /** PvP comes on. */
        FIGHT_START("fight-start", 0.2, false),
        /** It sees an enemy it had not seen for a while. */
        SPOT("spot", 0.15, false),
        /** It starts a fight. */
        ENGAGE("engage", 0.15, false),
        /** It killed someone. */
        KILL("kill", 0.45, true),
        /** Low health: it falls back. */
        LOW("low", 0.25, false),
        /** It was nearly dead and got away. */
        ESCAPE("escape", 0.2, false),
        /** Someone else joined its fight. */
        THIRD_PARTY("third-party", 0.2, false),
        /** The border closes in. */
        BORDER("border", 0.2, false),
        /** The last few are left. */
        SHOWDOWN("showdown", 0.3, false),
        /** Last words. */
        DEATH("death", 0.5, true),
        /** It won. */
        WIN("win", 1.0, true);

        private final String key;
        private final double chance;
        private final boolean urgent;

        Trigger(String key, double chance, boolean urgent) {
            this.key = key;
            this.chance = chance;
            this.urgent = urgent;
        }

        /** Section of the lines file ({@code kill}, {@code base-done}, ...). */
        public String key() {
            return key;
        }

        /** Default chance that a bot says something when this happens ({@code bots.chat.chances.<key>}). */
        public double chance() {
            return chance;
        }

        /** Said right away: a kill, last words, the win skip the bot's own gap and wait less for others. */
        public boolean urgent() {
            return urgent;
        }
    }

    /** The section of the lines file used by every personality. */
    public static final String GENERAL = "general";

    /** Gap between two lines of one bot (ticks, at frequency 1): a random value in this range. */
    static final long BOT_GAP_MIN = 20 * 20;
    static final long BOT_GAP_MAX = 40 * 20;
    /** Gap between any two lines (ticks, at frequency 1 or more). */
    static final long GLOBAL_GAP = 4 * 20;
    /** Gap before an urgent line (kill, death, win) after any other line (ticks). */
    static final long URGENT_GAP = 20;
    /** How often a personality's own line is chosen when it has some. */
    static final double PERSONAL_SHARE = 0.6;
    /** Lines said recently are not repeated (per trigger). */
    static final int MEMORY = 4;

    /**
     * {@code games.augment.bots.chat.*}.
     *
     * @param enabled   bots talk in chat (봇전 and 봇 관전전)
     * @param chance    times every trigger's chance (0-5)
     * @param frequency divides the gaps between lines (0.1-3): higher talks more often
     * @param chances   each trigger's own chance (0-1) of a line when it happens
     */
    public record Settings(boolean enabled, double chance, double frequency, Map<Trigger, Double> chances) {

        public static final String ENABLED = "bots.chat.enabled";
        public static final String CHANCE = "bots.chat.chance";
        public static final String FREQUENCY = "bots.chat.frequency";
        public static final String CHANCES = "bots.chat.chances";
        public static final double MIN_FREQUENCY = 0.1;
        public static final double MAX_FREQUENCY = 3;
        public static final double MAX_CHANCE = 5;

        public Settings {
            chance = Double.isFinite(chance) ? Math.max(0, Math.min(MAX_CHANCE, chance)) : 1;
            frequency = Double.isFinite(frequency)
                    ? Math.max(MIN_FREQUENCY, Math.min(MAX_FREQUENCY, frequency)) : 1;
            Map<Trigger, Double> all = new EnumMap<>(Trigger.class);
            for (Trigger trigger : Trigger.values()) {
                Double value = chances.get(trigger);
                all.put(trigger, value == null || !Double.isFinite(value) ? trigger.chance()
                        : Math.max(0, Math.min(1, value)));
            }
            chances = Map.copyOf(all);
        }

        /** Every default: chat on, the built-in chances. */
        public static Settings defaults() {
            return new Settings(true, 1, 1, Map.of());
        }

        /** The chance that a bot speaks when {@code trigger} happens (before its personality's talkativeness). */
        public double chance(Trigger trigger) {
            return Math.min(1, chances.getOrDefault(trigger, trigger.chance()) * chance);
        }

        public static void declareDefaults(ConfigurationSection d) {
            d.set(ENABLED, true);
            d.setComments("bots.chat", List.of(
                    "봇 채팅: 봇전·봇 관전전에서 봇이 플레이어처럼 채팅합니다 (<철수 봇> 거기 숨은 거 다 보여).",
                    "문구는 plugins/MCPL/augment-bots-chat.yml 에서 고칩니다. 상황마다 확률로만 말하고,",
                    "봇마다 20~40초, 전체로 4초에 한 줄까지만 말합니다 (처치·마지막 말·우승은 바로)."));
            d.setComments(ENABLED, List.of("봇 채팅 켜기"));
            d.set(CHANCE, 1.0);
            d.setComments(CHANCE, List.of("모든 상황의 말할 확률에 곱하는 배율 (0~" + (int) MAX_CHANCE
                    + ", 0 = 말 안 함)"));
            d.set(FREQUENCY, 1.0);
            d.setComments(FREQUENCY, List.of("말하는 간격 배율 (" + MIN_FREQUENCY + "~" + (int) MAX_FREQUENCY
                    + "): 2 = 간격이 절반 (봇마다 10~20초)"));
            for (Trigger trigger : Trigger.values()) {
                d.set(CHANCES + "." + trigger.key(), trigger.chance());
            }
            d.setComments(CHANCES, List.of(
                    "상황마다 말할 확률 (0~1): greeting 시작 인사 · base-done 기지 완성 · gear 장비 · augment 증강",
                    "fight-start 전투 시작 · spot 적 발견 · engage 교전 · kill 처치 · low 후퇴 · escape 아슬아슬한 생존",
                    "third-party 끼어듦 · border 보더 · showdown 최후의 결전 · death 마지막 말 · win 우승"));
        }

        public static Settings read(ConfigurationSection s) {
            Map<Trigger, Double> chances = new EnumMap<>(Trigger.class);
            for (Trigger trigger : Trigger.values()) {
                chances.put(trigger, s.getDouble(CHANCES + "." + trigger.key(), trigger.chance()));
            }
            return new Settings(s.getBoolean(ENABLED, true), s.getDouble(CHANCE, 1.0), s.getDouble(FREQUENCY, 1.0),
                    chances);
        }
    }

    /**
     * The line pool: trigger → {@link #GENERAL} or a personality key → lines (MiniMessage).
     */
    public record Lines(Map<Trigger, Map<String, List<String>>> pool) {

        public Lines {
            Map<Trigger, Map<String, List<String>>> copy = new EnumMap<>(Trigger.class);
            pool.forEach((trigger, groups) -> {
                Map<String, List<String>> inner = new HashMap<>();
                groups.forEach((group, lines) -> inner.put(group, List.copyOf(lines)));
                copy.put(trigger, Map.copyOf(inner));
            });
            pool = Map.copyOf(copy);
        }

        public static final Lines EMPTY = new Lines(Map.of());

        public List<String> lines(Trigger trigger, String group) {
            return pool.getOrDefault(trigger, Map.of()).getOrDefault(group, List.of());
        }

        /**
         * Reads a lines file: one section per trigger key, each with {@code general} and personality keys
         * ({@code hunter}, {@code fortress}, ...) holding string lists. Missing sections fall back to
         * {@code fallback}'s (so a trimmed file still talks).
         */
        public static Lines read(@Nullable ConfigurationSection file, Lines fallback) {
            Map<Trigger, Map<String, List<String>>> out = new EnumMap<>(Trigger.class);
            for (Trigger trigger : Trigger.values()) {
                ConfigurationSection section = file == null ? null : file.getConfigurationSection(trigger.key());
                if (section == null) {
                    Map<String, List<String>> old = fallback.pool().get(trigger);
                    if (old != null) {
                        out.put(trigger, old);
                    }
                    continue;
                }
                Map<String, List<String>> groups = new HashMap<>();
                for (String group : section.getKeys(false)) {
                    List<String> lines = new ArrayList<>();
                    for (String line : section.getStringList(group)) {
                        if (line != null && !line.isBlank()) {
                            lines.add(line.strip());
                        }
                    }
                    if (!lines.isEmpty()) {
                        groups.put(group.toLowerCase(Locale.ROOT), lines);
                    }
                }
                out.put(trigger, groups);
            }
            return new Lines(out);
        }
    }

    /** A line to say: the bot, the trigger and the raw MiniMessage line (placeholders still in). */
    public record Line(UUID bot, Trigger trigger, String text) {
    }

    /** Every placeholder a line may use. */
    public static final List<String> PLACEHOLDERS = List.of("target", "augment", "gear", "kills", "alive", "me");

    private final Settings settings;
    private final Lines lines;
    private final Random random;
    private final Map<UUID, Long> nextAllowed = new HashMap<>();
    private final Map<Trigger, Deque<String>> recent = new EnumMap<>(Trigger.class);
    private long lastAny = Long.MIN_VALUE / 2;

    public BotChat(Settings settings, Lines lines, Random random) {
        this.settings = settings;
        this.lines = lines;
        this.random = random;
    }

    public Settings settings() {
        return settings;
    }

    /** How chatty a personality is (an assassin hardly talks, a berserker shouts a lot). */
    public static double talkativeness(@Nullable BotPersonality personality) {
        if (personality == null) {
            return 1;
        }
        return switch (personality) {
            case ASSASSIN -> 0.3;
            case BERSERKER -> 1.35;
            case GAMBLER -> 1.15;
            case SURVIVOR, NOMAD -> 0.85;
            default -> 1;
        };
    }

    /**
     * Something happened to {@code bot}: maybe it says something.
     *
     * @param available the placeholders this event can fill ({@code target}, {@code augment}, ...): lines that need
     *                  another one are skipped
     * @param now       the match clock (ticks)
     * @return the line, or null if it stays quiet (disabled, rate limits, the chance roll, nothing fitting)
     */
    public @Nullable Line consider(UUID bot, @Nullable BotPersonality personality, Trigger trigger,
                                   Set<String> available, long now) {
        if (!settings.enabled()) {
            return null;
        }
        long globalGap = trigger == Trigger.WIN ? 0 : trigger.urgent() ? URGENT_GAP : GLOBAL_GAP;
        if (now - lastAny < globalGap) {
            return null;
        }
        Long allowed = nextAllowed.get(bot);
        if (!trigger.urgent() && allowed != null && now < allowed) {
            return null;
        }
        double chance = trigger == Trigger.WIN ? settings.chance(trigger)
                : Math.min(1, settings.chances().get(trigger) * settings.chance() * talkativeness(personality));
        if (random.nextDouble() >= chance) {
            return null;
        }
        String text = pick(personality, trigger, available);
        if (text == null) {
            return null;
        }
        lastAny = now;
        long gap = BOT_GAP_MIN + (long) (random.nextDouble() * (BOT_GAP_MAX - BOT_GAP_MIN));
        nextAllowed.put(bot, now + Math.round(gap / settings.frequency()));
        Deque<String> said = recent.computeIfAbsent(trigger, any -> new ArrayDeque<>());
        said.addLast(text);
        while (said.size() > MEMORY) {
            said.removeFirst();
        }
        return new Line(bot, trigger, text);
    }

    private @Nullable String pick(@Nullable BotPersonality personality, Trigger trigger, Set<String> available) {
        List<String> personal = personality == null ? List.of()
                : usable(lines.lines(trigger, personality.key()), available);
        List<String> general = usable(lines.lines(trigger, GENERAL), available);
        List<String> from;
        if (personal.isEmpty()) {
            from = general;
        } else if (general.isEmpty() || random.nextDouble() < PERSONAL_SHARE) {
            from = personal;
        } else {
            from = general;
        }
        if (from.isEmpty()) {
            return null;
        }
        Deque<String> said = recent.getOrDefault(trigger, new ArrayDeque<>());
        List<String> fresh = from.stream().filter(line -> !said.contains(line)).toList();
        List<String> choice = fresh.isEmpty() ? from : fresh;
        return choice.get(random.nextInt(choice.size()));
    }

    /** The placeholders a line uses ({@code <target>} → {@code target}). */
    public static Set<String> placeholders(String line) {
        Set<String> out = new HashSet<>();
        for (String name : PLACEHOLDERS) {
            if (line.contains("<" + name + ">")) {
                out.add(name);
            }
        }
        return out;
    }

    private static List<String> usable(List<String> lines, Set<String> available) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (available.containsAll(placeholders(line))) {
                out.add(line);
            }
        }
        return out;
    }
}
