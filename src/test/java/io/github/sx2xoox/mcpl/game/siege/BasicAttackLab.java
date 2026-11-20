package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttacks;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.DummyAbility;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The basic attack lab (평타 실측, docs/siege-v2.md §8): every 공성전 basic attack measured in a real test match, run by
 * {@code ./gradlew basicAttackLab [-Pattacks=ninja,archer]} (too slow for every build: about ten minutes for all 70).
 * For each attack: damage per second to one enemy when the player clicks exactly when the attack is ready ("풀
 * 차징", the crosshair indicator full), as fast as possible ("연타") and with pauses that break the combo; the total
 * on three packed enemies; whether it reaches its listed range. The attack runs on a neutral stand-in ability with the
 * attack's numbers ({@code lab_<id>}): the basic attack itself, without the ability's passives (those are the ability
 * balance's, docs/siege-balance.md). Everything the attack does counts (combo finishers, damage over time); critical
 * hits are off so the numbers are exact (+2.5 % on average).
 *
 * <p>Rules it holds: clicking faster never beats clicking when ready (the cadence is the attack speed; early clicks
 * are dropped, the last three ticks are kept); melee attacks deal 5.5–8.3 per second to one enemy, ranged ones 3–5.5
 * (they pay for the range); an attack that hits a pack pays for it on one enemy (melee arcs of 100° or more, cones and
 * bursts at most 7.2, ranged splashes at most 4.8); supports (healing allies) may go down to 3. The table is written to
 * {@code docs/basic-attack-lab.md} (all attacks) or {@code build/reports/basic-attack-lab.md} (a filtered run).
 */
class BasicAttackLab {

    private static final String FEEL = SiegeMatch.BASIC_ATTACKS + ".";
    private static final Path DOCS = Path.of("docs", "basic-attack-lab.md");
    private static final Path PARTIAL = Path.of("build", "reports", "basic-attack-lab.md");
    /** Ticks of one measurement (6 seconds) and the quiet ticks after it that still count (flight, burns). */
    private static final int WINDOW = 120;
    private static final int TAIL = 35;
    private static final int SETTLE = 35;

    static final double MELEE_MIN = 5.5;
    static final double MELEE_MAX = 8.3;
    static final double RANGED_MIN = 3.0;
    static final double RANGED_MAX = 5.5;
    static final double WIDE_MELEE_MAX = 7.2;
    static final double SPLASH_RANGED_MAX = 4.8;
    static final double SUPPORT_MIN = 3.0;
    /** Three packed enemies taking this much more than one count as a pack hit. */
    static final double PACK = 1.5;
    static final String LAB = "lab_";

    private MatchHarness h;
    private SiegeGame siege;

    @BeforeEach
    void setUp() {
        assumeTrue(Boolean.getBoolean("mcpl.basicAttackLab"), "run with ./gradlew basicAttackLab");
        h = MatchHarness.flat();
        h.registerDummies();
        h.settings().set("ultimate.per-second", 0);
        siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
        siege.sitesOverride(new FallbackLaneSites((int) FlatWorld.SURFACE));
        ConfigurationSection config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of(DummyAbility.INFO.id(), DummyAbility.TWIN_INFO.id()));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.ROUND_SECONDS, 3600);
        config.set(SiegeSettings.ATTACK_DAMAGE, 0);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        config.set(SiegeSettings.PROTECTION_SECONDS, 0);
        config.set(SiegeProgressSettings.XP_PASSIVE, 0);
        config.set("minions.enabled", false);
        config.set("jungle.enabled", false);
        config.set(SiegeHudSettings.NAME_PLATES, false);
        config.set(FEEL + BasicAttackSettings.CRITICAL + "." + BasicAttackSettings.CRIT_CHANCE, 0);
        config.set(FEEL + BasicAttackSettings.EFFECTS + "." + BasicAttackSettings.DISPLAYS, false);
        // a neutral stand-in per attack with that attack's numbers: the basic attack without the ability's passives
        String prefix = SiegeMatch.BASIC_ATTACKS + "." + BasicAttackSettings.ATTACKS + "." + LAB;
        for (BasicAttack a : BasicAttacks.defaults().values()) {
            AbilityInfo info = AbilityInfo.builder(LAB + a.id(), "평타 " + a.id(), Material.HAY_BLOCK)
                    .passive("평타", "아무 일도 하지 않습니다.")
                    .active("평타", "아무 일도 하지 않습니다.", 30)
                    .ultimate("평타", "아무 일도 하지 않습니다.")
                    .ultimateNotStandalone()
                    .build();
            h.game.registry().register(info, () -> new DummyAbility(info));
            String k = prefix + a.id() + ".";
            config.set(k + BasicAttackSettings.SHAPE, a.shape().key());
            config.set(k + BasicAttackSettings.DAMAGE, a.damage());
            config.set(k + BasicAttackSettings.COOLDOWN, a.cooldownSeconds());
            config.set(k + BasicAttackSettings.RANGE, a.range());
            config.set(k + BasicAttackSettings.HITS, a.hits());
            config.set(k + BasicAttackSettings.INTERVAL, a.interval());
            config.set(k + BasicAttackSettings.RADIUS, a.radius());
            config.set(k + BasicAttackSettings.ANGLE, a.angle());
            config.set(k + BasicAttackSettings.SPEED, a.speed());
            config.set(k + BasicAttackSettings.PIERCE, a.pierce());
            config.set(k + BasicAttackSettings.FALLOFF, a.falloff());
            config.set(k + BasicAttackSettings.EFFECT, a.effect().key());
            config.set(k + BasicAttackSettings.EFFECT_POWER, a.effectPower());
            config.set(k + BasicAttackSettings.EFFECT_SECONDS, a.effectSeconds());
        }
        h.game.abilities().load(h.game.registry());
    }

    @AfterEach
    void tearDown() {
        if (h != null) {
            h.close();
        }
    }

    /** One attack's measured numbers (damage per second; {@code three} = the sum over three packed enemies). */
    record Measured(BasicAttack attack, double distance, double timed, double spam, double paused, double three,
                    boolean reaches) {

        double spread() {
            return timed > 0 ? three / timed : 0;
        }
    }

    /** Attacks measured in one match (a long mock match slows down, so a fresh one every few attacks). */
    private static final int SHARD = 10;
    private static final List<Measured> RESULTS = new ArrayList<>();
    private static final List<String> PROBLEMS = new ArrayList<>();

    @ParameterizedTest(name = "attacks {0}0–{0}9")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void everyBasicAttackStaysInItsRole(int shard) {
        Set<String> only = only();
        List<BasicAttack> attacks = new ArrayList<>(BasicAttacks.defaults().values());
        List<BasicAttack> mine = new ArrayList<>();
        for (int i = shard * SHARD; i < Math.min(attacks.size(), (shard + 1) * SHARD); i++) {
            if (only.isEmpty() || only.contains(attacks.get(i).id())) {
                mine.add(attacks.get(i));
            }
        }
        assumeTrue(!mine.isEmpty(), "nothing to measure in this shard");
        List<Measured> results = measureAll(mine);
        List<String> problems = new ArrayList<>();
        for (Measured m : results) {
            problems.addAll(problems(m));
        }
        RESULTS.addAll(results);
        PROBLEMS.addAll(problems);
        assertEquals(List.of(), problems, "basic attacks out of their role");
    }

    @AfterAll
    static void report() throws IOException {
        if (!RESULTS.isEmpty()) {
            List<Measured> ordered = new ArrayList<>();
            for (BasicAttack attack : BasicAttacks.defaults().values()) {
                RESULTS.stream().filter(m -> m.attack().id().equals(attack.id())).findFirst().ifPresent(ordered::add);
            }
            write(ordered, only().isEmpty() ? DOCS : PARTIAL);
        }
    }

    private static Set<String> only() {
        return Arrays.stream(System.getProperty("mcpl.basicAttackIds", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    static List<String> problems(Measured m) {
        List<String> out = new ArrayList<>();
        BasicAttack attack = m.attack();
        String id = attack.id();
        if (m.spam() > m.timed() + 0.05) {
            out.add(id + ": clicking faster beats clicking when ready (" + f(m.spam()) + " > " + f(m.timed()) + ")");
        }
        if (m.paused() > m.timed() + 0.05) {
            out.add(id + ": pausing beats attacking in a row");
        }
        if (!m.reaches()) {
            out.add(id + ": misses an enemy just inside its range " + f(attack.range()));
        }
        boolean support = attack.effect() == BasicAttack.Effect.HEAL_ALLIES;
        double min = support ? SUPPORT_MIN : reach(attack) ? RANGED_MIN : MELEE_MIN;
        double max = reach(attack) ? (m.spread() >= PACK ? SPLASH_RANGED_MAX : RANGED_MAX)
                : wide(attack) && m.spread() >= PACK ? WIDE_MELEE_MAX : MELEE_MAX;
        if (m.timed() > max + 1.0E-9 || m.timed() < min - 1.0E-9) {
            out.add(id + ": single-target " + f(m.timed()) + " outside " + f(min) + "–" + f(max));
        }
        return out;
    }

    /** An attack that hits from afar: every ranged shape, and thrusts of 6 blocks or more (a hook, a sound wave). */
    static boolean reach(BasicAttack attack) {
        return attack.ranged() || attack.range() >= 6;
    }

    /** A melee attack meant to hit a pack: a wide arc, a cone or a burst. */
    static boolean wide(BasicAttack attack) {
        return switch (attack.shape()) {
            case CONE, NOVA -> true;
            case SWING, COMBO -> attack.angle() >= 100;
            default -> false;
        };
    }

    // =================================================================== measuring

    private List<Measured> measureAll(List<BasicAttack> attacks) {
        h.join(6);
        try {
            h.hub.games().start(siege, null);
        } catch (GameStartException e) {
            throw new AssertionError(e);
        }
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT,
                2000, "the fight");
        SiegeMatch match = siege.currentMatch();
        SuperpowerMatch abilities = match.abilities();
        List<Player> one = members(match, 0);
        List<Player> enemies = members(match, 1);
        assertEquals(3, one.size());
        assertEquals(3, enemies.size());
        Player attacker = one.get(0);
        for (Player player : enemies) {
            assertTrue(abilities.replaceAbility(player, DummyAbility.TWIN_INFO.id()));
        }
        for (Player ally : one.subList(1, one.size())) {
            assertTrue(abilities.replaceAbility(ally, DummyAbility.TWIN_INFO.id()));
        }
        List<Measured> out = new ArrayList<>();
        for (BasicAttack attack : attacks) {
            assertTrue(abilities.replaceAbility(attacker, LAB + attack.id()), attack.id());
            double distance = reach(attack) ? Math.min(8.0, attack.range() - 1.5) : 2.5;
            int cooldown = attack.cooldown();
            int pause = cooldown + (int) BasicAttackSettings.Feel.DEFAULT.comboResetTicks() + 4;
            // a whole number of attacks (about 6 s), so the count of attacks in the window is exact
            int window = cooldown * Math.max(6, (int) Math.round((double) WINDOW / cooldown));
            int paces = pause * Math.max(3, (int) Math.round((double) WINDOW / pause));
            double timed = run(match, attacker, one, enemies, 1, distance, window, t -> t % cooldown == 0);
            // every tick, but not in the last ticks a kept click would carry past the window
            double spam = run(match, attacker, one, enemies, 1, distance, window, t -> t < window - 3);
            double paused = run(match, attacker, one, enemies, 1, distance, paces, t -> t % pause == 0);
            double three = run(match, attacker, one, enemies, 3, distance, window, t -> t % cooldown == 0);
            boolean reaches = reaches(match, attacker, one, enemies, attack);
            Measured measured = new Measured(attack, distance, timed, spam, paused, three, reaches);
            System.out.println("[basic-attack-lab] " + row(measured));
            out.add(measured);
        }
        return out;
    }

    /** Damage per second to the first {@code targets} enemies while {@code click} says when to click. */
    private double run(SiegeMatch match, Player attacker, List<Player> team, List<Player> enemies, int targets,
                       double distance, int window, IntPredicate click) {
        SuperpowerMatch abilities = match.abilities();
        settle(match, attacker, team, enemies, targets, distance);
        double dealt = 0;
        for (int t = 0; t < window + TAIL; t++) {
            if (t < window && click.test(t)) {
                abilities.basicAttack(attacker);
            }
            h.ticks(1);
            dealt += lost(enemies, targets);
            arrange(match, attacker, team, enemies, targets, distance);
        }
        return dealt / (window / 20.0);
    }

    /** Whether one attack hits an enemy standing just inside its range. */
    private boolean reaches(SiegeMatch match, Player attacker, List<Player> team, List<Player> enemies,
                            BasicAttack attack) {
        double distance = Math.max(1.5, attack.range() - 0.4);
        // a lob is aimed up to throw far, as a player would; anything else straight at the target
        int highest = attack.shape() == BasicAttack.Shape.LOB ? 45 : 0;
        try {
            for (int up = 0; up <= highest; up += 5) {
                pitch = -up;
                settle(match, attacker, team, enemies, 1, distance);
                match.abilities().basicAttack(attacker);
                double dealt = 0;
                for (int t = 0; t < 70; t++) {
                    h.ticks(1);
                    dealt += lost(enemies, 1);
                    arrange(match, attacker, team, enemies, 1, distance);
                }
                if (dealt > 0) {
                    return true;
                }
            }
            return false;
        } finally {
            pitch = 0;
        }
    }

    /** The attacker's aim up or down (degrees, negative = up). */
    private float pitch;

    /** Quiet ticks so nothing of the previous measurement is still flying or burning, then the positions. */
    private void settle(SiegeMatch match, Player attacker, List<Player> team, List<Player> enemies, int targets,
                        double distance) {
        for (int t = 0; t < SETTLE; t++) {
            arrange(match, attacker, team, enemies, 0, distance);
            h.ticks(1);
        }
        arrange(match, attacker, team, enemies, targets, distance);
    }

    private double lost(List<Player> enemies, int targets) {
        double lost = 0;
        for (int i = 0; i < targets; i++) {
            Player enemy = enemies.get(i);
            lost += max(enemy) - enemy.getHealth();
        }
        return lost;
    }

    /**
     * The attacker at the spot looking east, the first {@code targets} enemies packed {@code distance} in front of it
     * (the others and the attacker's allies far away), everyone at full health and standing still.
     */
    private void arrange(SiegeMatch match, Player attacker, List<Player> team, List<Player> enemies, int targets,
                         double distance) {
        place(match, attacker, 0, 0);
        double[][] pack = {{0, 0}, {0.5, -0.9}, {0.5, 0.9}};
        for (int i = 0; i < enemies.size(); i++) {
            if (i < targets) {
                place(match, enemies.get(i), distance + pack[i][0], pack[i][1]);
            } else {
                place(match, enemies.get(i), 30 + i * 4, 30);
            }
        }
        for (int i = 0; i < team.size(); i++) {
            if (!team.get(i).equals(attacker)) {
                place(match, team.get(i), -30, -30 - i * 4);
            }
        }
    }

    private void place(SiegeMatch match, Player player, double dx, double dz) {
        place(match, player, dx, dz, dx > 0 ? 90f : -90f);
    }

    /** {@code player} at the spot, the attacker looking east and its enemies looking back at it (no backstabs). */
    private void place(SiegeMatch match, Player player, double dx, double dz, float yaw) {
        if (player.isDead()) {
            player.spigot().respawn();
        }
        Location center = match.session().arena().center();
        Location at = new Location(center.getWorld(), center.getX() + dx, FlatWorld.SURFACE, center.getZ() + 40 + dz,
                yaw, dx == 0 && dz == 0 ? pitch : 0f);
        Location now = player.getLocation();
        // teleport only when it moved (pushes, lunges): a teleport every tick slows the mock server down
        if (!now.getWorld().equals(at.getWorld()) || now.distanceSquared(at) > 1.0E-6 || now.getYaw() != yaw
                || now.getPitch() != at.getPitch()) {
            player.teleport(at);
        }
        player.setVelocity(new Vector());
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.setHealth(max(player));
        player.getInventory().setHeldItemSlot(0);
    }

    private List<Player> members(SiegeMatch match, int team) {
        List<Player> out = new ArrayList<>();
        for (UUID id : match.teams().get(team).members()) {
            out.add(h.server.getPlayer(id));
        }
        return out;
    }

    private static double max(Player player) {
        return player.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    // =================================================================== report

    private static String row(Measured m) {
        BasicAttack a = m.attack();
        return "| " + a.id() + " | " + a.shape().korean() + (reach(a) ? " (원거리)" : "") + " | " + f(m.distance())
                + " | " + f(a.damagePerSecond()) + " | " + f(m.timed()) + " | " + f(m.spam()) + " | "
                + f(m.paused()) + " | " + f(m.three()) + " | " + f(m.spread()) + " | " + (m.reaches() ? "O" : "X")
                + " | " + (problems(m).isEmpty() ? "✓" : "✗") + " |";
    }

    private static void write(List<Measured> results, Path path) throws IOException {
        StringBuilder out = new StringBuilder("# 평타 실측 (공성전, 자동 생성)\n\n");
        out.append("<!-- ./gradlew basicAttackLab 이 테스트 경기에서 재서 씁니다(BasicAttackLab). 평타 수치를 바꾼 뒤 다시"
                + " 돌리세요. -->\n\n");
        out.append("1레벨, 방어구·치명타 없음, 콤보 피니시·지속 피해·근접 패시브 포함, 6초 동안 초당 피해. 거리 = 근접 2.5칸, ")
                .append("원거리 8칸(사거리가 짧으면 사거리 −1.5). **준비될 때** = 조준점 게이지가 찰 때마다 클릭(풀 차징), ")
                .append("**연타** = 매 틱 클릭, **끊어 치기** = 콤보가 끊기게 쉬며 클릭, **3명** = 붙어 선 적 셋의 합계.\n\n")
                .append("규칙: 연타 ≤ 준비될 때(일찍 누른 클릭은 버려지고 3틱 안만 기억), 근접 ").append(MELEE_MIN).append("~")
                .append(MELEE_MAX).append(", 원거리 ").append(RANGED_MIN).append("~").append(RANGED_MAX)
                .append(" (한 명 기준). 여럿을 맞히는 평타(3명 배율 ≥ ").append(PACK).append(")는 한 명에게 덜: 넓은 근접")
                .append("(100° 이상·부채꼴·주변 파동) ≤ ").append(WIDE_MELEE_MAX).append(", 원거리 광역 ≤ ")
                .append(SPLASH_RANGED_MAX).append(". 아군 회복 평타는 ").append(SUPPORT_MIN)
                .append("부터. 사거리 끝의 적에게 닿아야 함. 능력 패시브는 빼고 잼(평타만).\n\n");
        out.append("| 능력 | 형태 | 거리 | 표기 초당 | 준비될 때 | 연타 | 끊어 치기 | 3명 합계 | 3명 배율 | 사거리 끝 | 규칙 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Measured m : results) {
            out.append(row(m)).append('\n');
        }
        double melee = median(results.stream().filter(m -> !reach(m.attack())).mapToDouble(Measured::timed)
                .toArray());
        double ranged = median(results.stream().filter(m -> reach(m.attack())).mapToDouble(Measured::timed)
                .toArray());
        out.append("\n중앙값: 근접 ").append(f(melee)).append(", 원거리 ").append(f(ranged)).append("\n");
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
    }

    private static double median(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
