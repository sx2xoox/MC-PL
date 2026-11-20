package io.github.sx2xoox.mcpl.game.augment.bot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code plugins/MCPL/stats/augment-claude/<time>.csv}: one row per bot of a match that had Claude bots, the plain
 * bots included, so a server can compare Claude bots with plain bots of the same difficulty over many matches (place,
 * kills, survival, damage) next to what the Claude bots cost (calls, latency, failures, tokens, estimated USD).
 */
final class ClaudeStatsFile {

    static final String HEADER = "time,game,model,effort,bot,difficulty,personality,claude,place,kills,survived_s,"
            + "damage_dealt,damage_taken,augments,calls,answered,failed,stale,refusals,avg_latency_ms,input_tokens,"
            + "output_tokens,cache_read_tokens,cache_write_tokens,cost_usd,augment_picks,augment_fallbacks,goals,"
            + "heard,replies,filtered,quick_calls";

    /** One bot's row. */
    record Row(String bot, String difficulty, String personality, String claude, int place, int kills,
               long survivedSeconds, double dealt, double taken, List<String> augments,
               @org.jspecify.annotations.Nullable ClaudeLedger ledger) {
    }

    private ClaudeStatsFile() {
    }

    static List<String> lines(String time, String game, String model, String effort, List<Row> rows) {
        List<String> out = new ArrayList<>(rows.size() + 1);
        out.add(HEADER);
        for (Row r : rows) {
            ClaudeLedger l = r.ledger();
            out.add(String.join(",", time, csv(game), csv(model), csv(effort), csv(r.bot()), csv(r.difficulty()),
                    csv(r.personality()), csv(r.claude()), Integer.toString(r.place()), Integer.toString(r.kills()),
                    Long.toString(r.survivedSeconds()), String.format(Locale.ROOT, "%.1f", r.dealt()),
                    String.format(Locale.ROOT, "%.1f", r.taken()), csv(String.join(";", r.augments())),
                    l == null ? "" : Integer.toString(l.calls()), l == null ? "" : Integer.toString(l.answered()),
                    l == null ? "" : Integer.toString(l.failures()), l == null ? "" : Integer.toString(l.stale()),
                    l == null ? "" : Integer.toString(l.refusals()),
                    l == null ? "" : String.format(Locale.ROOT, "%.0f", l.averageLatencyMillis()),
                    l == null ? "" : Long.toString(l.inputTokens()), l == null ? "" : Long.toString(l.outputTokens()),
                    l == null ? "" : Long.toString(l.cacheReadTokens()),
                    l == null ? "" : Long.toString(l.cacheWriteTokens()),
                    l == null ? "" : String.format(Locale.ROOT, "%.5f", l.cost(model)),
                    l == null ? "" : Integer.toString(l.augmentPicks()),
                    l == null ? "" : Integer.toString(l.augmentFallbacks()), l == null ? "" : csv(l.goals.toString()),
                    l == null ? "" : Integer.toString(l.heard()), l == null ? "" : Integer.toString(l.replies()),
                    l == null ? "" : Integer.toString(l.filtered()),
                    l == null ? "" : Integer.toString(l.quickCalls())));
        }
        return out;
    }

    /** Writes the file into {@code dir}; its path. */
    static Path write(Path dir, String game, String model, String effort, List<Row> rows) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String time = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 19);
        Files.createDirectories(dir);
        Path file = dir.resolve(now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + game + ".csv");
        for (int n = 2; Files.exists(file); n++) {
            file = dir.resolve(now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + game + "-" + n
                    + ".csv");
        }
        Files.write(file, lines(time, game, model, effort, rows), StandardCharsets.UTF_8);
        return file;
    }

    static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
