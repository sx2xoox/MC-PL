package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * What a Claude bot knows at one request: plain data taken on the main thread ({@link BotController#snapshot}), so the
 * request can be built and sent on another thread. Only what the bot itself perceives (no enemy behind a wall it has
 * not seen or heard). Pure.
 *
 * @param fight            PvP is on
 * @param farmingLeft      seconds of farming left (0 in the fight)
 * @param fightSeconds     seconds since PvP started
 * @param roundsLeft       augment rounds still to come
 * @param nextRound        seconds to the next augment round (-1: none)
 * @param borderShrinking  the border is closing
 * @param borderHalf       half side of the border now (blocks)
 * @param finalHalf        half side of the final zone
 * @param zoneDistance     blocks from the bot to the center
 * @param alive            participants still in play (it included)
 * @param self             the bot itself
 * @param rivals           enemies it knows about, nearest first
 * @param events           what happened to it since the last request
 * @param doing            what its brain is doing now ("교전 → 영희 봇")
 */
public record ClaudeSnapshot(boolean fight, long farmingLeft, long fightSeconds, int roundsLeft, long nextRound,
                             boolean borderShrinking, double borderHalf, double finalHalf, double zoneDistance,
                             int alive, Self self, List<Rival> rivals, List<String> events, String doing) {

    public ClaudeSnapshot {
        rivals = List.copyOf(rivals);
        events = List.copyOf(events);
    }

    /**
     * The bot itself.
     *
     * @param armor    armor points (0-20)
     * @param gear     its armor and sword ("철 갑옷, 다이아 칼")
     * @param augments the names of the augments it owns
     * @param base     its base ("없음", "짓는 중 40%", "완성, 12블록 거리", ...)
     */
    public record Self(String name, String difficulty, String personality, double health, double maxHealth,
                       double absorption, int apples, int arrows, int blocks, int armor, String gear, boolean bow,
                       boolean shield, List<String> augments, String base, int kills, boolean highGround) {

        public Self {
            augments = List.copyOf(augments);
        }
    }

    /**
     * An enemy it knows about.
     *
     * @param id       their participant id (never shown to the model)
     * @param seconds  seconds since it last saw or heard them (0: now)
     * @param height   blocks they stand above it (negative: below)
     * @param win      its forecast chance of winning a duel against them (-1: unknown)
     */
    public record Rival(java.util.UUID id, String name, double distance, double height, double health, double maxHealth, int armor,
                        String weapon, boolean visible, boolean bow, boolean lava, boolean fightingOther,
                        boolean approaching, boolean grudge, double win, long seconds) {
    }

    /** The snapshot as compact lines for the prompt. */
    public String text() {
        StringBuilder b = new StringBuilder();
        if (fight) {
            b.append(String.format(Locale.ROOT, "PHASE: fight (PvP on) for %ds. border half=%.0f%s, final half=%.0f, "
                            + "you are %.0f blocks from the center. alive=%d.%n", fightSeconds, borderHalf,
                    borderShrinking ? " (shrinking)" : "", finalHalf, zoneDistance, alive));
        } else {
            b.append(String.format(Locale.ROOT, "PHASE: farming, %ds left before PvP. alive=%d.%n", farmingLeft, alive));
        }
        b.append(nextRound >= 0 ? String.format(Locale.ROOT, "Augment rounds left: %d (next in %ds).%n", roundsLeft,
                nextRound) : "Augment rounds left: 0.\n");
        b.append(String.format(Locale.ROOT, "YOU: %s [%s, %s] hp %.0f/%.0f%s, golden apples %d, arrows %d, blocks %d, "
                        + "armor %d (%s)%s%s, kills %d%s.%n", self.name(), self.difficulty(), self.personality(),
                self.health(), self.maxHealth(), self.absorption() > 0 ? String.format(Locale.ROOT, " +%.0f absorption",
                        self.absorption()) : "", self.apples(), self.arrows(), self.blocks(), self.armor(), self.gear(),
                self.bow() ? ", bow" : "", self.shield() ? ", shield" : "", self.kills(),
                self.highGround() ? ", on high ground" : ""));
        b.append("Your augments: ").append(self.augments().isEmpty() ? "none" : String.join(", ", self.augments()))
                .append(".\nYour base: ").append(self.base()).append(".\nYour body is now doing: ").append(doing)
                .append(".\n");
        if (rivals.isEmpty()) {
            b.append("KNOWN ENEMIES: none in sight or earshot.\n");
        } else {
            b.append("KNOWN ENEMIES (only what you perceive):\n");
            for (Rival r : rivals) {
                b.append(String.format(Locale.ROOT, "- %s: %.0f blocks%s, hp %.0f/%.0f, armor %d, %s%s%s%s%s%s%s%s%s%n",
                        r.name(), r.distance(), Math.abs(r.height()) >= 2 ? String.format(Locale.ROOT,
                                " (%+.0f high)", r.height()) : "", r.health(), r.maxHealth(), r.armor(), r.weapon(),
                        r.visible() ? ", visible" : r.seconds() > 0 ? ", last seen " + r.seconds() + "s ago"
                                : ", heard", r.bow() ? ", holds bow" : "", r.lava() ? ", holds LAVA bucket" : "",
                        r.fightingOther() ? ", fighting someone else" : "", r.approaching() ? ", approaching" : "",
                        r.grudge() ? ", hurt you" : "", r.win() >= 0 ? String.format(Locale.ROOT,
                                ", duel forecast %.0f%%", r.win() * 100) : "", ""));
            }
        }
        if (!events.isEmpty()) {
            b.append("SINCE LAST TIME:\n");
            for (String e : events) {
                b.append("- ").append(e).append('\n');
            }
        }
        return b.toString();
    }

    /** The rival called {@code name} (exact, else ignoring case and spaces), or null. */
    public @Nullable Rival rival(String name) {
        if (name.isBlank()) {
            return null;
        }
        for (Rival r : rivals) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        String loose = squash(name);
        for (Rival r : rivals) {
            if (squash(r.name()).equals(loose)) {
                return r;
            }
        }
        return null;
    }

    private static String squash(String name) {
        return name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
