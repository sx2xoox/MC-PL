package io.github.sx2xoox.mcpl.game.augment.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

/**
 * One answer of the Claude API to a {@link ClaudeCommander} (the JSON its structured output schema,
 * {@link ClaudePrompt#schema()}, guarantees). Pure (no Bukkit); every field is cleaned up so nothing the model writes
 * can reach the game unchecked: the chat line is plain text of one short line, the notes are bounded.
 *
 * @param goal    the plan
 * @param target  the name of the enemy to go after ("" for none)
 * @param range   melee or bow preference
 * @param augment the card id it takes ("" when no card is offered)
 * @param notes   its notes on rivals' habits, carried into the next request
 * @param chat    a line to say in chat ("" for none)
 * @param reason  a few words on why (log only)
 * @param replyTo the name of the player it answers ("" for none)
 * @param reply   its answer to them ("" for none)
 */
public record ClaudeDecision(ClaudeOrders.Goal goal, String target, ClaudeOrders.Range range, String augment,
                             String notes, String chat, String reason, String replyTo, String reply) {

    /** Longest notes kept between requests. */
    static final int NOTES_MAX = 400;
    /** Longest chat line. */
    static final int CHAT_MAX = 60;

    /** Parses the model's JSON answer; null if it is not the expected object. */
    public static @Nullable ClaudeDecision parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonObject o;
        try {
            JsonElement root = JsonParser.parseString(json.trim());
            if (!root.isJsonObject()) {
                return null;
            }
            o = root.getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        if (!o.has("goal")) {
            return null;
        }
        return new ClaudeDecision(ClaudeOrders.Goal.parse(string(o, "goal")), oneLine(string(o, "target"), 40),
                ClaudeOrders.Range.parse(string(o, "range")), oneLine(string(o, "augment"), 64),
                bounded(string(o, "notes"), NOTES_MAX), oneLine(string(o, "chat"), CHAT_MAX),
                oneLine(string(o, "reason"), 200), oneLine(string(o, "reply_to"), 40),
                oneLine(string(o, "reply"), CHAT_MAX));
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return "";
        }
        try {
            return e.isJsonPrimitive() ? e.getAsString() : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    /** One plain line: control characters and MiniMessage-like tags out, at most {@code max} characters. */
    static String oneLine(String text, int max) {
        String clean = text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").replace('<', ' ').replace('>', ' ')
                .replaceAll("\\s+", " ").trim();
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static String bounded(String text, int max) {
        String clean = text.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").trim();
        return clean.length() > max ? clean.substring(0, max) : clean;
    }
}
