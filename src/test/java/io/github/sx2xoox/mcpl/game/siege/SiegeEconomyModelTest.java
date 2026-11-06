package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.sx2xoox.mcpl.game.siege.economy.Gold;
import io.github.sx2xoox.mcpl.game.siege.jungle.JungleSettings;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionKind;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionSettings;
import io.github.sx2xoox.mcpl.game.siege.shop.ShopItem;
import io.github.sx2xoox.mcpl.game.siege.shop.ShopSettings;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.ObjectiveKind;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The growth, economy and tempo model of one 20-minute 공성전 round with the default settings (docs/siege-v2.md §11):
 * an average player of a 3v3 earns experience and gold minute by minute from the sources the settings define, under
 * the stated assumptions of how a round goes; the shop items are compared by what they add per 100 gold; the
 * structures by how long a won fight (the defenders' respawn and walk back) leaves to break them.
 *
 * <p>Intent it holds: level 9 at about 13–17 minutes (the last minutes at full strength, not half the round), a full
 * six-item build at about the round's end, every combat item adding about 1.5–3.5 % combat power per 100 gold (no
 * item twice as good as the others, none useless), and an outer tower that one won fight plus a wave breaks while an
 * inner tower and the core need more than one. The tables go to {@code build/reports/siege-economy.md}.
 */
class SiegeEconomyModelTest {

    private static final Path REPORT = Path.of("build", "reports", "siege-economy.md");

    // ------------------------------------------------------------------ assumptions (an average player of a 3v3)
    /** Seconds of fight in a round. */
    static final int ROUND = 1200;
    /** Share of the time a player is near his lane's minions (the rest: dead, walking, recalling, in the jungle). */
    static final double LANE_PRESENCE = 0.6;
    /** Share of one lane's enemy minions a player last-hits (two lanes, three players, the minions kill some). */
    static final double LAST_HIT_SHARE = 0.3;
    /** Kills and assists per player and minute. */
    static final double KILLS_PER_MINUTE = 0.25;
    static final double ASSISTS_PER_MINUTE = 0.3;
    /** Clears per team and round of its own side's jungle (small camps, buff monsters, capture points). */
    static final double SMALL_CLEARS = 10;
    static final double BUFF_CLEARS = 4;
    static final double CAPTURES = 6;
    /** Enemy towers a team breaks in a round (from minute 7 on) and dragons it takes. */
    static final double TOWERS = 2.5;
    static final int TOWERS_FROM = 420;
    static final double DRAGONS = 1.5;
    /** Basic attack damage per second at level 1 (the measured median, BasicAttackBalanceTest), combo included. */
    static final double BASIC_DPS = 7.0 * 1.08;
    /** Ability damage per second at level 1 (siege-balance.md: 10-second damage median 11 incl. one ultimate). */
    static final double SKILL_DPS = 0.8;
    /** Weight of abilities in combat power beyond their damage (crowd control, dashes, heals). */
    static final double SKILL_WEIGHT = 0.35;
    /** A typical basic attack hit for armour (level 5). */
    static final double TYPICAL_HIT = 5.5;
    /** Blocks from the base to an outer / inner tower / the core and the walking speed (blocks a second). */
    static final double WALK_OUTER = 80;
    static final double WALK_INNER = 50;
    static final double WALK_CORE = 25;
    static final double WALK_SPEED = 4.3;

    record Minute(int minute, int xp, int level, int gold) {
    }

    @Test
    void anAverageRoundGrowsAsIntended() throws IOException {
        Model model = new Model(defaults());
        List<Minute> curve = model.curve();
        Map<String, Double> items = model.itemValues();
        List<String[]> structures = model.structureWindows();
        write(model, curve, items, structures);

        int maxLevel = model.progress.maxLevel();
        int reached = curve.stream().filter(m -> m.level() >= maxLevel).mapToInt(Minute::minute).min().orElse(99);
        assertTrue(reached >= 13 && reached <= 17, "level " + maxLevel + " at minute " + reached + " (13–17)");
        int buildCost = model.fullBuild();
        int earned = curve.get(curve.size() - 1).gold();
        double share = (double) earned / buildCost;
        assertTrue(share >= 0.85 && share <= 1.15, "a full build (" + buildCost + ") at the round's end: earned "
                + earned + " = " + f(share));
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Double> item : items.entrySet()) {
            if (item.getValue() < 1.5 || item.getValue() > 3.5) {
                problems.add(item.getKey() + " " + f(item.getValue()) + " %/100g");
            }
        }
        assertEquals(List.of(), problems, "combat items add 1.5–3.5 % combat power per 100 gold");
        double outer = Double.parseDouble(structures.get(0)[3]);
        double inner = Double.parseDouble(structures.get(1)[3]);
        double core = Double.parseDouble(structures.get(2)[3]);
        assertTrue(outer <= 1.1, "an outer tower falls in one won fight and its wave, needs " + outer);
        assertTrue(inner > outer && inner <= 2.5, "an inner tower takes one or two more, needs " + inner);
        assertTrue(core > inner, "the core needs the most, needs " + core);
    }

    private static YamlConfiguration defaults() {
        YamlConfiguration config = new YamlConfiguration();
        MemoryConfiguration scratch = new MemoryConfiguration();
        SiegeSettings.declareDefaults(scratch);
        SiegeProgressSettings.declareDefaults(scratch);
        for (String key : scratch.getKeys(true)) {
            if (!scratch.isConfigurationSection(key)) {
                config.set(key, scratch.get(key));
            }
        }
        return config;
    }

    /** The model over the default settings. */
    static final class Model {
        final SiegeSettings siege;
        final SiegeProgressSettings progress;
        final Gold.Settings gold;
        final MinionSettings minions;
        final JungleSettings jungle;
        final ShopSettings shop;
        final double bonusReference;

        Model(YamlConfiguration config) {
            siege = SiegeSettings.read(config);
            progress = SiegeProgressSettings.read(config);
            gold = Gold.Settings.read(config);
            minions = MinionSettings.read(config);
            jungle = JungleSettings.read(config);
            shop = ShopSettings.read(config);
            bonusReference = BasicAttackSettings.DEFAULT_BONUS_REFERENCE;
        }

        /** Experience and gold earned by the end of every minute. */
        List<Minute> curve() {
            List<Minute> out = new ArrayList<>();
            double xp = 0;
            double money = gold.start();
            for (int t = 1; t <= ROUND; t++) {
                xp += progress.xpPassivePerSecond();
                money += gold.perSecond();
                int first = minions.firstWaveTicks() / 20;
                int every = minions.intervalTicks() / 20;
                if (t >= first && (t - first) % every == 0) {
                    int wave = (t - first) / every + 1;
                    boolean cannon = minions.cannonEvery() > 0 && wave % minions.cannonEvery() == 0;
                    double xpWave = minions.melee() * minions.stats(MinionKind.MELEE).xp()
                            + minions.ranged() * minions.stats(MinionKind.RANGED).xp()
                            + (cannon ? minions.stats(MinionKind.CANNON).xp() : 0);
                    double goldWave = (minions.melee() + minions.ranged()) * gold.minion()
                            + (cannon ? gold.cannon() : 0);
                    xp += LANE_PRESENCE * xpWave;
                    money += LAST_HIT_SHARE * goldWave;
                }
                double perSecond = 1.0 / 60;
                xp += perSecond * (KILLS_PER_MINUTE * progress.xpKill() + ASSISTS_PER_MINUTE * progress.xpAssist());
                money += perSecond * (KILLS_PER_MINUTE * gold.kill() + ASSISTS_PER_MINUTE * gold.assist());
                double jungleShare = 1.0 / 3 / (ROUND - 30);
                if (t > 30) {
                    xp += jungleShare * (SMALL_CLEARS * jungle.kind(ObjectiveKind.JELLY).xp()
                            + BUFF_CLEARS * jungle.kind(ObjectiveKind.TEDDY).xp()
                            + CAPTURES * jungle.kind(ObjectiveKind.FOUNTAIN).xp());
                    money += jungleShare * (SMALL_CLEARS * gold.small() + BUFF_CLEARS * gold.buff()
                            + CAPTURES * gold.capture() * 1.5);
                }
                if (t > TOWERS_FROM) {
                    xp += progress.xpTowerDestroyed() * TOWERS / (ROUND - TOWERS_FROM);
                    money += gold.tower() * TOWERS / (ROUND - TOWERS_FROM);
                }
                int dragonFirst = jungle.kind(ObjectiveKind.DRAGON).firstTicks() / 20;
                if (t >= dragonFirst) {
                    // the dragons the team takes, spread over the time they can be taken
                    double share = DRAGONS / (ROUND - dragonFirst);
                    xp += share * jungle.kind(ObjectiveKind.DRAGON).xp();
                    money += share * gold.dragon();
                }
                if (t % 60 == 0) {
                    out.add(new Minute(t / 60, (int) xp, progress.levelFor((int) xp), (int) money));
                }
            }
            return out;
        }

        /** What one fully bought six-slot build costs (the best tier of every line, the six dearest lines). */
        int fullBuild() {
            List<Integer> prices = new ArrayList<>(List.of(shop.price(ShopItem.ARMOR_3), shop.price(ShopItem.STEEL_SWORD),
                    shop.price(ShopItem.ORB), shop.price(ShopItem.SHOES), shop.price(ShopItem.HOURGLASS),
                    shop.price(ShopItem.HAMMER), shop.price(ShopItem.REVIVE_CLOCK)));
            prices.sort(null);
            prices.remove(0);
            return prices.stream().mapToInt(Integer::intValue).sum();
        }

        /** Combat power each combat item adds per 100 gold, %. */
        Map<String, Double> itemValues() {
            double basic = BASIC_DPS;
            double skill = SKILL_DPS;
            double basicShare = 1 - SKILL_WEIGHT;
            Map<String, Double> out = new LinkedHashMap<>();
            for (int tier = 1; tier <= 2; tier++) {
                double gain = shop.swordDamage()[tier - 1] / bonusReference;
                ShopItem item = tier == 1 ? ShopItem.WOODEN_SWORD : ShopItem.STEEL_SWORD;
                out.put(item.name(), per100(basicShare * gain, shop.price(item)));
            }
            out.put(ShopItem.ORB.name(), per100(SKILL_WEIGHT * shop.orbSkill() + basicShare * shop.orbAttack(),
                    shop.price(ShopItem.ORB)));
            out.put(ShopItem.HOURGLASS.name(), per100(SKILL_WEIGHT * (1 / (1 - shop.hourglass()) - 1),
                    shop.price(ShopItem.HOURGLASS)));
            double skillTaken = skill / (basic + skill);
            ShopItem[] armor = {ShopItem.ARMOR_1, ShopItem.ARMOR_2, ShopItem.ARMOR_3};
            for (int tier = 1; tier <= 3; tier++) {
                double reduction = (1 - skillTaken) * armorReduction(shop.armorPoints()[tier - 1], TYPICAL_HIT)
                        + skillTaken * shop.armorSkill()[tier - 1];
                out.put(armor[tier - 1].name(), per100(1 / (1 - reduction) - 1, shop.price(armor[tier - 1])));
            }
            return out;
        }

        /** Vanilla armour (no toughness) against one hit of {@code damage}. */
        static double armorReduction(double armor, double damage) {
            return Math.min(20, Math.max(armor / 5, armor - damage / 2)) / 25;
        }

        private static double per100(double gain, int price) {
            return price > 0 ? gain * 100 / (price / 100.0) : 0;
        }

        /**
         * How long a won fight leaves to break each structure (the defenders' respawn at level 5 plus the walk back)
         * against how long three attackers of level 5 and a minion wave need: {name, need, window, fights}.
         */
        List<String[]> structureWindows() {
            List<String[]> out = new ArrayList<>();
            double level = 1 + 0.075 * 4;
            // basic attacks and abilities (full damage on buildings: no 0.65)
            double players = 3 * (BASIC_DPS + SKILL_DPS / 0.65) * level;
            double wave = minions.melee() * minions.stats(MinionKind.MELEE).damage()
                    / (minions.stats(MinionKind.MELEE).attackTicks() / 20.0)
                    + minions.ranged() * minions.stats(MinionKind.RANGED).damage()
                    / (minions.stats(MinionKind.RANGED).attackTicks() / 20.0);
            double respawn = siege.respawnDelay(3, 5) / 20.0;
            Object[][] rows = {{"바깥 타워", siege.outerHealth(), WALK_OUTER}, {"안쪽 타워", siege.innerHealth(),
                    WALK_INNER}, {"코어", siege.coreHealth(), WALK_CORE}};
            for (Object[] row : rows) {
                double need = (double) row[1] / (players + wave * 0.6);
                double window = respawn + (double) row[2] / WALK_SPEED;
                out.add(new String[] {(String) row[0], f(need), f(window), f(need / window)});
            }
            return out;
        }
    }

    private static void write(Model model, List<Minute> curve, Map<String, Double> items, List<String[]> structures)
            throws IOException {
        StringBuilder out = new StringBuilder("# 공성전 한 라운드 모델 (3대3 평균 플레이어)\n\n| 분 | 경험치 | 레벨 | 누적 골드 |\n|---|---|---|---|\n");
        for (Minute m : curve) {
            out.append("| ").append(m.minute()).append(" | ").append(m.xp()).append(" | ").append(m.level())
                    .append(" | ").append(m.gold()).append(" |\n");
        }
        out.append("\n풀빌드 ").append(model.fullBuild()).append(" 골드\n\n| 아이템 | 가격 | 100골드당 전투력 % |\n|---|---|---|\n");
        for (Map.Entry<String, Double> item : items.entrySet()) {
            out.append("| ").append(item.getKey()).append(" | ")
                    .append(model.shop.price(ShopItem.valueOf(item.getKey()))).append(" | ")
                    .append(f(item.getValue())).append(" |\n");
        }
        out.append("\n| 건물 | 부수는 데 (초) | 이긴 싸움이 주는 시간 (초) | 필요한 싸움 |\n|---|---|---|---|\n");
        for (String[] row : structures) {
            out.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, out.toString(), StandardCharsets.UTF_8);
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
