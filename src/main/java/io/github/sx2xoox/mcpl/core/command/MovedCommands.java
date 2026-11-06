package io.github.sx2xoox.mcpl.core.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The old command forms that still work ({@link GameCommandRegistrar#moved}): maps the words typed after the root to
 * the words of the new form. Longer old forms win over their prefixes ("증강 봇전 관전" before "증강 봇전").
 */
public final class MovedCommands {

    private record Rule(List<Set<String>> words, List<String> target) {
    }

    private final List<Rule> rules = new ArrayList<>();

    /** See {@link GameCommandRegistrar#moved}. */
    public void add(String oldForm, String newForm) {
        List<Set<String>> words = new ArrayList<>();
        for (String word : split(oldForm)) {
            words.add(Set.of(word.toLowerCase(Locale.ROOT).split("\\|")));
        }
        if (words.isEmpty()) {
            throw new IllegalArgumentException("empty old form");
        }
        rules.add(new Rule(List.copyOf(words), split(newForm)));
        rules.sort(Comparator.comparingInt((Rule rule) -> rule.words().size()).reversed());
    }

    /** The new form for {@code typed} (the words after the root), or null if it is no known old form. */
    public @Nullable List<String> rewrite(List<String> typed) {
        for (Rule rule : rules) {
            if (matches(rule, typed)) {
                List<String> out = new ArrayList<>(rule.target());
                for (String word : typed.subList(rule.words().size(), typed.size())) {
                    // the new form is Korean: an English switch after an English old form becomes Korean too
                    out.add(switch (word.toLowerCase(Locale.ROOT)) {
                        case "on" -> "켜기";
                        case "off" -> "끄기";
                        default -> word;
                    });
                }
                return out;
            }
        }
        return null;
    }

    private static boolean matches(Rule rule, List<String> typed) {
        if (typed.size() < rule.words().size()) {
            return false;
        }
        for (int i = 0; i < rule.words().size(); i++) {
            if (!rule.words().get(i).contains(typed.get(i).toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /** {@code text} split on whitespace, without empty words. */
    public static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        for (String word : text.trim().split("\\s+")) {
            if (!word.isEmpty()) {
                out.add(word);
            }
        }
        return out;
    }
}
