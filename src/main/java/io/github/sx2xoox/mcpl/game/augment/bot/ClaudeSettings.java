package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code games.augment.bots.claude.*} settings of the Claude bot: a 봇전 bot whose reflexes are the usual brain's
 * and whose big decisions (augment picks, the plan every few seconds, notes on rivals, a chat line now and then) come
 * from the Claude API ({@link ClaudeCommander}).
 *
 * @param enabled          every 봇전 / 봇 관전전 mixes in {@code count} Claude bots (else only when the command asks)
 * @param count            Claude bots per match (the first bots of the lineup)
 * @param model            the model id
 * @param effort           {@code output_config.effort} (low, medium, high, xhigh, max)
 * @param intervalTicks    the fight's decision interval
 * @param farmingTicks     the decision interval while farming (calmer, cheaper)
 * @param augmentWaitTicks how long a card offer waits for its answer before the bot picks by itself
 * @param timeoutMillis    HTTP timeout of one request
 * @param maxTokens        {@code max_tokens} (thinking + the answer)
 * @param chat             it may say a short line in chat
 * @param chatCooldownTicks the least time between two of its lines
 * @param namePrefix       put before its name ("[Claude] 철수 봇")
 * @param apiKey           the API key (empty: environment variable {@code ANTHROPIC_API_KEY}); never logged
 * @param baseUrl          the API base URL (empty: the SDK's default; a local stub for tests)
 * @param stats            write {@code stats/augment-claude/*.csv} after every match with Claude bots
 * @param listen           it hears players' chat (an {@code @클로드} call, its name, or a line said near it) and answers
 *                         ({@link ClaudeEars}); answers are said even when {@code chat} is off
 * @param hearRange        blocks around its body in which it hears a line not addressed to it
 * @param replyCooldownTicks the least time between two of its answers to the same player
 * @param quickGapTicks    an {@code @클로드} call brings its next request forward, but never closer than this to its
 *                         last one
 * @param quickPerMinute   at most this many requests brought forward per minute (per bot)
 * @param quickMax         at most this many requests brought forward per match (per bot)
 */
public record ClaudeSettings(boolean enabled, int count, String model, String effort, long intervalTicks,
                             long farmingTicks, long augmentWaitTicks, long timeoutMillis, int maxTokens, boolean chat,
                             long chatCooldownTicks, String namePrefix, String apiKey, String baseUrl, boolean stats,
                             boolean listen, double hearRange, long replyCooldownTicks, long quickGapTicks,
                             int quickPerMinute, int quickMax) {

    public static final String ENABLED = "bots.claude.enabled";
    public static final String COUNT = "bots.claude.count";
    public static final String MODEL = "bots.claude.model";
    public static final String EFFORT = "bots.claude.effort";
    public static final String INTERVAL = "bots.claude.interval-seconds";
    public static final String FARMING_INTERVAL = "bots.claude.farming-interval-seconds";
    public static final String AUGMENT_WAIT = "bots.claude.augment-wait-seconds";
    public static final String TIMEOUT = "bots.claude.timeout-seconds";
    public static final String MAX_TOKENS = "bots.claude.max-tokens";
    public static final String CHAT = "bots.claude.chat";
    public static final String CHAT_COOLDOWN = "bots.claude.chat-cooldown-seconds";
    public static final String NAME_PREFIX = "bots.claude.name-prefix";
    public static final String API_KEY = "bots.claude.api-key";
    public static final String BASE_URL = "bots.claude.base-url";
    public static final String STATS = "bots.claude.stats";
    public static final String LISTEN = "bots.claude.listen";
    public static final String HEAR_RANGE = "bots.claude.hear-range";
    public static final String REPLY_COOLDOWN = "bots.claude.reply-cooldown-seconds";
    public static final String QUICK_GAP = "bots.claude.quick-reply-gap-seconds";
    public static final String QUICK_PER_MINUTE = "bots.claude.quick-replies-per-minute";
    public static final String QUICK_MAX = "bots.claude.quick-replies-per-match";

    /** The environment variable read when {@code api-key} is empty. */
    public static final String KEY_ENV = "ANTHROPIC_API_KEY";
    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

    public static final ClaudeSettings DEFAULTS = new ClaudeSettings(false, 1, DEFAULT_MODEL, "low", 8 * 20, 20 * 20,
            12 * 20, 15_000, 2048, false, 45 * 20, "[Claude] ", "", "", true, true, 24, 10 * 20, 3 * 20, 6, 60);

    /** Never shows the key (logs, debug output). */
    @Override
    public String toString() {
        return "ClaudeSettings[enabled=" + enabled + ", count=" + count + ", model=" + model + ", effort=" + effort
                + ", interval=" + intervalTicks + ", key=" + (apiKey.isBlank() ? "(env)" : "(set)") + ", baseUrl="
                + baseUrl + ", listen=" + listen + "]";
    }

    /** Claude bots in a match started without asking for them ({@code enabled}: {@code count}, else none). */
    public int defaultBots() {
        return enabled ? count : 0;
    }

    /** Claude bots in a match whose command asked for them ("클로드"): {@code count}, at least one. */
    public int askedBots() {
        return Math.max(1, count);
    }

    /** The key to use: {@code api-key}, else the environment's; blank when there is none. */
    public String resolveKey(Function<String, String> env) {
        if (!apiKey.isBlank()) {
            return apiKey.trim();
        }
        String fromEnv = env.apply(KEY_ENV);
        return fromEnv == null ? "" : fromEnv.trim();
    }

    /** Declares the defaults (with Korean comments) in {@code games.augment}. */
    static void declareDefaults(ConfigurationSection d) {
        d.setComments("bots.claude", List.of(
                "Claude 봇 (지휘관): 조준·회피·이동 같은 반사 행동은 보통 봇과 같고, 증강 선택과 몇 초마다의 큰 작전",
                "(파밍·공격·후퇴·기지·구역, 노릴 적, 근접/활)을 Claude API가 정합니다. 상대 버릇을 메모해 다음 판단에 씁니다.",
                "API 크레딧을 씁니다. 키가 없으면 보통 봇으로 뜁니다. 명령에 '클로드'를 쓰면 이 경기에만 섞습니다:",
                "/게임 시작 증강 봇전 지옥 5 클로드 · /게임 시작 증강 관전 지옥 8 클로드"));
        d.set(ENABLED, DEFAULTS.enabled());
        d.setComments(ENABLED, List.of("켜면 모든 봇전·관전전에 Claude 봇이 count명 섞입니다 (끄면 명령에 '클로드'를 쓸 때만)"));
        d.set(COUNT, DEFAULTS.count());
        d.setComments(COUNT, List.of("한 경기의 Claude 봇 수 (봇 목록의 앞쪽 봇이 Claude 봇이 됨)"));
        d.set(MODEL, DEFAULTS.model());
        d.setComments(MODEL, List.of("모델 ID (예: claude-opus-5-5, claude-sonnet-5-5, claude-haiku-5-5)"));
        d.set(EFFORT, DEFAULTS.effort());
        d.setComments(EFFORT, List.of("생각의 깊이: low, medium, high, xhigh, max (낮을수록 빠르고 쌈)"));
        d.set(INTERVAL, 8);
        d.setComments(INTERVAL, List.of("전투 중 작전을 다시 정하는 간격(초, 3~60)"));
        d.set(FARMING_INTERVAL, 20);
        d.setComments(FARMING_INTERVAL, List.of("파밍 중 작전을 다시 정하는 간격(초, 3~120)"));
        d.set(AUGMENT_WAIT, 12);
        d.setComments(AUGMENT_WAIT, List.of("증강 카드 답을 기다리는 시간(초); 넘으면 보통 봇처럼 고름"));
        d.set(TIMEOUT, 15);
        d.setComments(TIMEOUT, List.of("요청 하나의 제한 시간(초)"));
        d.set(MAX_TOKENS, DEFAULTS.maxTokens());
        d.setComments(MAX_TOKENS, List.of("응답 최대 토큰 (생각 + 답)"));
        d.set(CHAT, DEFAULTS.chat());
        d.setComments(CHAT, List.of("Claude 봇이 가끔 짧은 한국어 채팅을 함"));
        d.set(CHAT_COOLDOWN, 45);
        d.setComments(CHAT_COOLDOWN, List.of("Claude 봇 채팅 사이의 최소 간격(초)"));
        d.set(NAME_PREFIX, DEFAULTS.namePrefix());
        d.setComments(NAME_PREFIX, List.of("Claude 봇 이름 앞에 붙는 표시"));
        d.set(API_KEY, "");
        d.setComments(API_KEY, List.of("Anthropic API 키 (비우면 환경 변수 ANTHROPIC_API_KEY). 로그·채팅에 나오지 않습니다."));
        d.set(BASE_URL, "");
        d.setComments(BASE_URL, List.of("API 주소 (비우면 기본값; 테스트용 로컬 서버 주소)"));
        d.set(STATS, DEFAULTS.stats());
        d.setComments(STATS, List.of("경기마다 plugins/MCPL/stats/augment-claude/*.csv 에 기록 (호출·지연·토큰·추정 비용·순위)"));
        d.set(LISTEN, DEFAULTS.listen());
        d.setComments(LISTEN, List.of("플레이어 채팅을 듣고 대답함: '@클로드 ...'(또는 @봇이름)로 부르면 다음 작전을 앞당겨 바로 대답,",
                "이름을 말하거나 근처(hear-range)에서 한 말은 다음 작전 때 참고. 켜 두면 chat 이 꺼져 있어도 대답은 합니다"));
        d.set(HEAR_RANGE, 24);
        d.setComments(HEAR_RANGE, List.of("부르지 않은 말을 듣는 거리(블록, 0~64)"));
        d.set(REPLY_COOLDOWN, 10);
        d.setComments(REPLY_COOLDOWN, List.of("같은 플레이어에게 다시 대답하기까지의 최소 간격(초)"));
        d.set(QUICK_GAP, 3);
        d.setComments(QUICK_GAP, List.of("'@클로드'로 불렀을 때 앞당긴 작전 호출과 직전 호출 사이 최소 간격(초, 1~30)"));
        d.set(QUICK_PER_MINUTE, DEFAULTS.quickPerMinute());
        d.setComments(QUICK_PER_MINUTE, List.of("봇 하나가 1분에 앞당길 수 있는 호출 수 (0: 앞당기지 않음)"));
        d.set(QUICK_MAX, DEFAULTS.quickMax());
        d.setComments(QUICK_MAX, List.of("봇 하나가 한 경기에 앞당길 수 있는 호출 수"));
    }

    /** Reads the live {@code games.augment} section; invalid values fall back to the defaults. */
    static ClaudeSettings read(ConfigurationSection s) {
        String model = s.getString(MODEL, DEFAULT_MODEL);
        model = model == null || !model.trim().matches("[A-Za-z0-9._:@/-]{1,100}") ? DEFAULT_MODEL : model.trim();
        String effort = s.getString(EFFORT, "low");
        effort = effort == null ? "low" : effort.trim().toLowerCase(Locale.ROOT);
        if (!EFFORTS.contains(effort)) {
            effort = "low";
        }
        String prefix = s.getString(NAME_PREFIX, DEFAULTS.namePrefix());
        String key = s.getString(API_KEY, "");
        String url = s.getString(BASE_URL, "");
        return new ClaudeSettings(s.getBoolean(ENABLED, false),
                Math.max(1, Math.min(BotSettings.HARD_MAX, s.getInt(COUNT, 1))), model, effort,
                ticks(s.getDouble(INTERVAL, 8), 3, 60), ticks(s.getDouble(FARMING_INTERVAL, 20), 3, 120),
                ticks(s.getDouble(AUGMENT_WAIT, 12), 2, 60),
                Math.round(clamp(s.getDouble(TIMEOUT, 15), 2, 120) * 1000),
                Math.max(256, Math.min(32_000, s.getInt(MAX_TOKENS, DEFAULTS.maxTokens()))),
                s.getBoolean(CHAT, false), ticks(s.getDouble(CHAT_COOLDOWN, 45), 5, 3600),
                prefix == null ? "" : prefix.length() > 24 ? prefix.substring(0, 24) : prefix,
                key == null ? "" : key.trim(), url == null ? "" : url.trim(), s.getBoolean(STATS, true),
                s.getBoolean(LISTEN, true), clamp(s.getDouble(HEAR_RANGE, 24), 0, 64),
                ticks(s.getDouble(REPLY_COOLDOWN, 10), 0, 3600), ticks(s.getDouble(QUICK_GAP, 3), 1, 30),
                Math.max(0, Math.min(60, s.getInt(QUICK_PER_MINUTE, DEFAULTS.quickPerMinute()))),
                Math.max(0, Math.min(10_000, s.getInt(QUICK_MAX, DEFAULTS.quickMax()))));
    }

    private static long ticks(double seconds, double min, double max) {
        return Math.round(clamp(seconds, min, max) * 20);
    }

    private static double clamp(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }
}
