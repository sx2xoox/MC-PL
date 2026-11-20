package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.augment.bot.BotChat.Trigger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** 봇 채팅: chance rolls, rate limits, placeholders, personality pools and the bundled lines file. */
class BotChatTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final Set<String> ALL = Set.copyOf(BotChat.PLACEHOLDERS);

    /** Every trigger always speaks (chance 1). */
    private static BotChat.Settings always() {
        Map<Trigger, Double> chances = new EnumMap<>(Trigger.class);
        for (Trigger trigger : Trigger.values()) {
            chances.put(trigger, 1.0);
        }
        return new BotChat.Settings(true, 1, 1, chances);
    }

    private static BotChat.Lines lines(Trigger trigger, Map<String, List<String>> groups) {
        return new BotChat.Lines(Map.of(trigger, groups));
    }

    static BotChat.Lines bundled() throws Exception {
        try (InputStream stream = BotChatTest.class.getClassLoader().getResourceAsStream("augment-bots-chat.yml")) {
            assertNotNull(stream, "augment-bots-chat.yml is bundled");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            return BotChat.Lines.read(yaml, BotChat.Lines.EMPTY);
        }
    }

    @Test
    void aTriggerSpeaksWhenItsChanceRolls() {
        BotChat chat = new BotChat(always(), lines(Trigger.KILL, Map.of("general", List.of("ㅂㅂ"))), new Random(1));
        BotChat.Line line = chat.consider(A, null, Trigger.KILL, ALL, 0);
        assertNotNull(line);
        assertEquals("ㅂㅂ", line.text());
        assertEquals(Trigger.KILL, line.trigger());
        assertEquals(A, line.bot());
    }

    @Test
    void theChanceRollKeepsChatSparse() {
        BotChat.Settings defaults = BotChat.Settings.defaults();
        assertEquals(0.3, defaults.chance(Trigger.GREETING), 1e-9);
        assertEquals(0.15, defaults.chance(Trigger.SPOT), 1e-9);
        assertEquals(0.15, defaults.chance(Trigger.ENGAGE), 1e-9);
        assertEquals(0.25, defaults.chance(Trigger.LOW), 1e-9);
        assertEquals(0.45, defaults.chance(Trigger.KILL), 1e-9);
        assertEquals(0.5, defaults.chance(Trigger.DEATH), 1e-9);
        assertEquals(1.0, defaults.chance(Trigger.WIN), 1e-9);
        int spoke = 0;
        Random random = new Random(11);
        for (int i = 0; i < 2000; i++) {
            BotChat chat = new BotChat(defaults, lines(Trigger.SPOT, Map.of("general", List.of("찾았다"))),
                    random);
            if (chat.consider(A, null, Trigger.SPOT, ALL, 0) != null) {
                spoke++;
            }
        }
        assertTrue(spoke > 200 && spoke < 420, "about 15 % of spots are said: " + spoke);
        BotChat.Settings doubled = new BotChat.Settings(true, 2, 1, Map.of());
        assertEquals(0.3, doubled.chance(Trigger.SPOT), 1e-9, "the global multiplier");
        assertEquals(1.0, doubled.chance(Trigger.WIN), 1e-9, "capped at 1");
        BotChat.Settings silent = new BotChat.Settings(true, 0, 1, Map.of());
        BotChat chat = new BotChat(silent, lines(Trigger.WIN, Map.of("general", List.of("GG"))), new Random(3));
        assertNull(chat.consider(A, null, Trigger.WIN, ALL, 0), "multiplier 0: never");
    }

    @Test
    void settingsAreReadFromTheConfig() {
        MemoryConfiguration config = new MemoryConfiguration();
        BotChat.Settings.declareDefaults(config);
        assertEquals(BotChat.Settings.defaults(), BotChat.Settings.read(config));
        config.set(BotChat.Settings.CHANCES + ".kill", 0.9);
        config.set(BotChat.Settings.CHANCE, 0.5);
        config.set(BotChat.Settings.ENABLED, false);
        BotChat.Settings read = BotChat.Settings.read(config);
        assertFalse(read.enabled());
        assertEquals(0.45, read.chance(Trigger.KILL), 1e-9);
        assertEquals(0.075, read.chance(Trigger.SPOT), 1e-9);
    }

    @Test
    void disabledChatIsSilent() {
        BotChat.Settings off = new BotChat.Settings(false, 5, 3, Map.of());
        BotChat chat = new BotChat(off, lines(Trigger.WIN, Map.of("general", List.of("GG"))), new Random(1));
        for (Trigger trigger : Trigger.values()) {
            assertNull(chat.consider(A, BotPersonality.BERSERKER, trigger, ALL, 0));
        }
    }

    @Test
    void rateLimitsHoldPerBotAndForEveryone() {
        BotChat chat = new BotChat(always(), lines(Trigger.SPOT, Map.of("general", List.of("저기 누구 있다"))),
                new Random(1));
        assertNotNull(chat.consider(A, null, Trigger.SPOT, ALL, 0));
        assertNull(chat.consider(B, null, Trigger.SPOT, ALL, BotChat.GLOBAL_GAP - 1), "the global gap");
        assertNotNull(chat.consider(B, null, Trigger.SPOT, ALL, BotChat.GLOBAL_GAP), "another bot after it");
        long later = BotChat.GLOBAL_GAP * 3;
        assertNull(chat.consider(A, null, Trigger.SPOT, ALL, later), "the bot's own 20-40 s gap");
        assertNull(chat.consider(A, null, Trigger.SPOT, ALL, BotChat.BOT_GAP_MIN - 1));
        assertNotNull(chat.consider(A, null, Trigger.SPOT, ALL, BotChat.BOT_GAP_MAX + 1), "after its gap");
    }

    @Test
    void urgentLinesSkipTheBotsOwnGapButNotABurst() {
        BotChat.Lines pool = new BotChat.Lines(Map.of(Trigger.SPOT, Map.of("general", List.of("찾았다")),
                Trigger.KILL, Map.of("general", List.of("ㅂㅂ"))));
        BotChat chat = new BotChat(always(), pool, new Random(1));
        assertNotNull(chat.consider(A, null, Trigger.SPOT, ALL, 0));
        assertNull(chat.consider(A, null, Trigger.KILL, ALL, BotChat.URGENT_GAP - 1), "not in the same second");
        assertNotNull(chat.consider(A, null, Trigger.KILL, ALL, BotChat.URGENT_GAP), "a kill line right after");
    }

    @Test
    void linesNeedingAMissingPlaceholderAreSkipped() {
        BotChat chat = new BotChat(always(), lines(Trigger.DEATH,
                Map.of("general", List.of("잘했다 <target>", "GG"))), new Random(1));
        for (int i = 0; i < 20; i++) {
            BotChat.Line line = chat.consider(UUID.randomUUID(), null, Trigger.DEATH, Set.of("me"), i * 100L);
            assertNotNull(line);
            assertEquals("GG", line.text(), "no killer: no line naming one");
        }
        BotChat only = new BotChat(always(), lines(Trigger.SPOT, Map.of("general", List.of("<target> 보인다"))),
                new Random(1));
        assertNull(only.consider(A, null, Trigger.SPOT, Set.of(), 0), "nothing fits: quiet");
    }

    @Test
    void placeholdersResolve() {
        assertEquals(Set.of("target", "kills"), BotChat.placeholders("<target> 컷 <kills>킬"));
        String line = "<kills>킬째, 잘 가 <target>";
        Component out = MiniMessage.miniMessage().deserialize(line,
                Placeholder.component("target", Component.text("영희 봇")),
                Placeholder.component("kills", Component.text(3)));
        assertEquals("3킬째, 잘 가 영희 봇", PlainTextComponentSerializer.plainText().serialize(out));
    }

    @Test
    void personalitiesUseTheirOwnLinesMostOfTheTime() {
        BotChat.Lines pool = lines(Trigger.KILL, Map.of("general", List.of("ㅂㅂ"),
                "berserker", List.of("하나 더!!!!"), "assassin", List.of("하나")));
        BotChat chat = new BotChat(always(), pool, new Random(7));
        int own = 0;
        for (int i = 0; i < 400; i++) {
            BotChat.Line line = chat.consider(UUID.randomUUID(), BotPersonality.BERSERKER, Trigger.KILL, ALL,
                    i * 100L);
            assertNotNull(line);
            assertFalse(line.text().equals("하나"), "never another personality's line");
            if (line.text().equals("하나 더!!!!")) {
                own++;
            }
        }
        assertTrue(own > 200 && own < 290, "about 60 % its own: " + own);
        BotChat neutral = new BotChat(always(), pool, new Random(7));
        assertEquals("ㅂㅂ", neutral.consider(A, null, Trigger.KILL, ALL, 0).text(), "no personality: general");
    }

    @Test
    void anAssassinHardlyTalksAndABerserkerShouts() {
        assertTrue(BotChat.talkativeness(BotPersonality.ASSASSIN) < 0.5);
        assertTrue(BotChat.talkativeness(BotPersonality.BERSERKER) > 1);
    }

    @Test
    void recentLinesAreNotRepeated() {
        BotChat chat = new BotChat(always(), lines(Trigger.KILL, Map.of("general", List.of("a", "b", "c", "d", "e"))),
                new Random(5));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            seen.add(chat.consider(UUID.randomUUID(), null, Trigger.KILL, ALL, i * 100L).text());
        }
        assertEquals(5, seen.size(), "the last few lines of a trigger are not said again");
    }

    @Test
    void theBundledLinesCoverEveryTriggerAndPersonality() throws Exception {
        BotChat.Lines pool = bundled();
        Set<BotPersonality> voiced = new HashSet<>();
        for (Trigger trigger : Trigger.values()) {
            List<String> general = pool.lines(trigger, BotChat.GENERAL);
            assertTrue(general.size() >= 8, trigger + " has at least 8 general lines: " + general.size());
            for (BotPersonality personality : BotPersonality.values()) {
                for (String line : pool.lines(trigger, personality.key())) {
                    voiced.add(personality);
                    assertTrue(Set.copyOf(BotChat.PLACEHOLDERS).containsAll(placeholderNames(line)),
                            "only known placeholders: " + line);
                }
            }
            for (String line : general) {
                MiniMessage.miniMessage().deserialize(line); // parses
            }
        }
        assertEquals(Set.of(BotPersonality.values()), voiced, "every personality has its own voice");
        assertTrue(pool.lines(Trigger.SPOT, BotPersonality.AVENGER.key()).stream()
                .anyMatch(line -> line.contains("<target>")), "an avenger names its grudge");
    }

    /** Every {@code <name>} in a line that is not a color/format tag. */
    private static Set<String> placeholderNames(String line) {
        Set<String> out = new HashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("<([a-z]+)>").matcher(line);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    @Test
    void aTrimmedFileKeepsTheBundledLinesOfMissingTriggers() throws Exception {
        YamlConfiguration user = new YamlConfiguration();
        user.set("kill.general", List.of("내 문구"));
        BotChat.Lines merged = BotChat.Lines.read(user, bundled());
        assertEquals(List.of("내 문구"), merged.lines(Trigger.KILL, BotChat.GENERAL));
        assertTrue(merged.lines(Trigger.KILL, "berserker").isEmpty(), "the edited trigger is taken as written");
        assertFalse(merged.lines(Trigger.DEATH, BotChat.GENERAL).isEmpty(), "other triggers stay");
    }

    @Test
    void teamLinesHaveTheirOwnAttitudePerPersonalityAndTheTeamWinIsAlwaysSaid() throws Exception {
        BotChat.Lines pool = bundled();
        for (Trigger trigger : Trigger.values()) {
            if (!trigger.team()) {
                continue;
            }
            for (BotPersonality personality : BotPersonality.values()) {
                assertFalse(pool.lines(trigger, personality.key()).isEmpty(),
                        trigger + " has lines of its own for " + personality);
            }
        }
        assertTrue(Trigger.TEAM_WIN.win() && Trigger.TEAM_WIN.team());
        assertFalse(Trigger.KILL.team());
        BotChat chat = new BotChat(BotChat.Settings.defaults(), pool, new Random(4));
        UUID bot = UUID.randomUUID();
        assertNotNull(chat.consider(bot, BotPersonality.SURVIVOR, Trigger.TEAM_WIN, Set.of("kills", "me"), 0),
                "the team win is always said");
        BotChat.Line line = chat.consider(UUID.randomUUID(), null, Trigger.TEAM_ALLY_DOWN, Set.of("ally", "me"), 100);
        if (line != null) {
            assertFalse(line.text().contains("<target>"), "no killer known: no line naming one");
        }
    }
}
