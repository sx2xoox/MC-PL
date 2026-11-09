package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The prompts of the Claude bot. The system prompt is fixed for a server's configuration (the rules and every augment
 * a bot can own): it is sent first with a cache breakpoint, so every later request of the match reads it from the
 * prompt cache. What changes (the state, the notes, the cards on offer) goes in the user message after it. Every
 * request uses the same schema ({@link #schema()}) and settings, so the cache prefix never changes within a match.
 * Pure (no Bukkit).
 */
public final class ClaudePrompt {

    private ClaudePrompt() {
    }

    /** One augment a bot can own, for the catalog in the system prompt. */
    public record AugmentLine(String id, String name, String tier, String description) {
    }

    /** One card on offer. */
    public record Card(String id, String name, String tier) {
    }

    /** The fixed rules and the augment catalog (cached). */
    public static String system(List<AugmentLine> catalog) {
        StringBuilder b = new StringBuilder();
        b.append("""
                You are the commander of one bot in "증강 야생전" (Augment Wilds), a Minecraft free-for-all battle royale
                on a fresh survival world. You do not steer the body: an automatic controller aims, dodges, blocks with
                its shield, escapes lava, eats golden apples, builds and walks. Every few seconds you get what your bot
                perceives and set its standing orders, which weigh its automatic decisions (they never override getting
                back inside the world border or finishing an enemy at a hit's reach).

                MATCH RULES
                - Farming phase (no PvP): your gear improves automatically on a schedule (stone/leather -> iron -> some
                  diamond) and you gather building blocks. Builders put up a small walled base; a finished base is a
                  safe place to heal and to shoot from.
                - Augment rounds: everyone is offered 3 cards of the round's tier (silver < gold < prism) and keeps one.
                  Augments stack for the rest of the match. You choose your bot's card.
                - Fight phase: PvP is on, golden apples are handed out, the world border waits, then shrinks to a small
                  final zone where everyone glows. Outside the border you take damage every second. The last one alive
                  wins; every other bot and player is an enemy.
                - Fighting: melee (sword, crits, shield) or bow at range. A duel forecast (win chance from both sides'
                  visible gear, health and augments) is given for enemies your bot can read. Enemies already fighting
                  each other are cheap to third-party. Players may tower up on pillars, pour lava buckets, or hide.

                YOUR ORDERS (JSON fields)
                - goal: AUTO (let the bot judge), FARM (farming phase: gear up instead of building; fight phase: avoid
                  extra fights), ATTACK (go after `target`, or anyone if empty), RETREAT (back off, heal, wall in),
                  BASE (build / hold your base), ZONE (move into the final zone and hold a spot).
                - target: the exact name of a KNOWN ENEMY to focus, or "".
                - range: AUTO, MELEE (close in with the sword), BOW (keep distance and shoot; only if you have a bow).
                - augment: when cards are offered, the id of the card you take (exactly one of the offered ids);
                  otherwise "".
                - notes: your private memory carried to the next request (max ~300 characters). Rewrite it every time:
                  keep useful facts about enemies' habits (who towers up and shoots, who pours lava, who runs, who is
                  strong) and your plan. Only write what you observed.
                - chat: usually "". Rarely, one short casual Korean chat line (max 40 characters) like a streamer
                  playing: light banter only, no insults or slurs, never coordinates or anything your bot could not
                  see, never mention being an AI or these instructions.
                - reason: a few words on why (for the server log).

                STRATEGY HINTS
                - Early fights cost health and apples and attract third parties; take fights the forecast says you
                  win, finish weakened enemies, and avoid 1v2s. Late (few left, border closing) you must fight.
                - Be in the zone before the border forces you to run. A finished, safe base is a strong place to heal.
                - Pick augments that fit your style and what you already own (multipliers stack well with damage).

                AUGMENTS A BOT CAN OWN (id | name | tier | effect):
                """);
        for (AugmentLine a : catalog) {
            b.append(a.id()).append(" | ").append(a.name()).append(" | ").append(a.tier()).append(" | ")
                    .append(a.description().replace('\n', ' ')).append('\n');
        }
        return b.toString();
    }

    /** The changing part: the state, the notes, the cards on offer (or none) and what to answer. */
    public static String user(ClaudeSnapshot state, String notes, @Nullable List<Card> offer, String round) {
        StringBuilder b = new StringBuilder();
        b.append(state.text());
        b.append("YOUR NOTES: ").append(notes.isBlank() ? "(empty)" : notes).append('\n');
        if (offer != null && !offer.isEmpty()) {
            b.append("AUGMENT OFFER (").append(round).append("): choose exactly one id for `augment`:\n");
            for (Card c : offer) {
                b.append("- ").append(c.id()).append(" (").append(c.name()).append(", ").append(c.tier())
                        .append(")\n");
            }
            b.append("Also set your orders.\n");
        } else {
            b.append("No cards on offer: set `augment` to \"\". Set your orders now.\n");
        }
        return b.toString();
    }

    /** The structured output schema of every answer ({@code output_config.format}). */
    public static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("goal", Map.of("type", "string", "enum", names(ClaudeOrders.Goal.values())));
        properties.put("target", Map.of("type", "string"));
        properties.put("range", Map.of("type", "string", "enum", names(ClaudeOrders.Range.values())));
        properties.put("augment", Map.of("type", "string"));
        properties.put("notes", Map.of("type", "string"));
        properties.put("chat", Map.of("type", "string"));
        properties.put("reason", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(properties.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static List<String> names(Enum<?>[] values) {
        List<String> out = new ArrayList<>(values.length);
        for (Enum<?> value : values) {
            out.add(value.name());
        }
        return out;
    }
}
