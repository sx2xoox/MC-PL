package io.github.sx2xoox.mcpl.game.superpower;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.core.config.HubConfig;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The {@code games.superpower.*} settings of {@code config.yml}, read once per match (so {@code /게임 리로드} applies
 * to the next match and a running match never sees half-changed values). Durations are converted to ticks.
 *
 * @param selectionMode          how abilities are handed out
 * @param rerolls                rerolls per player during the prep phase (RANDOM only)
 * @param revealTicks            length of the slot-machine reveal
 * @param choiceTicks            time limit of the CHOICE menu
 * @param prepTicks              invulnerable prep phase before the fight
 * @param timeLimitTicks         fight time limit, 0 = unlimited
 * @param kit                    items given at the start (armor is worn)
 * @param gaugePerDamageDealt    ultimate gauge % per health point dealt to enemy players
 * @param gaugePerDamageTaken    ultimate gauge % per health point taken
 * @param gaugePerSecond         ultimate gauge % per second alive in the fight
 * @param announceUltimates      announce every ultimate to all viewers
 * @param ultimateLockTicks      after a successful ultimate, the caster's gauge earns nothing (damage dealt or taken,
 *                               time) for this long
 * @param borderDelayTicks       fight time before the border starts shrinking
 * @param borderShrinkTicks      how long the border takes to reach its final size
 * @param borderFinalSize        final border side length in blocks
 * @param borderDamagePerSecond  damage per second outside the border (health points)
 * @param showAbilityInKillFeed  show {@code Name(능력)} in the kill feed
 * @param showTeamAbilities      in team mode, teammates see each other's abilities (sidebar, chat, command)
 * @param abilityItemMaterial    base item of the ability item (rendered with the ability icon model)
 * @param particleScale          multiplier for ability particle counts ({@code AbilityContext#effectScale()}),
 *                               {@value #MIN_PARTICLE_SCALE}..{@value #MAX_PARTICLE_SCALE}
 * @param dayNight               in a plugin map world the fight starts at noon and night falls while the border shrinks
 * @param rainChance             chance (0..1) that it rains for a while during a fight in a plugin map world
 * @param rainMinTicks           shortest shower
 * @param rainMaxTicks           longest shower (never below {@code rainMinTicks})
 */
public record SuperpowerSettings(
        SelectionMode selectionMode,
        int rerolls,
        int revealTicks,
        int choiceTicks,
        int prepTicks,
        long timeLimitTicks,
        List<ItemStack> kit,
        double gaugePerDamageDealt,
        double gaugePerDamageTaken,
        double gaugePerSecond,
        boolean announceUltimates,
        long ultimateLockTicks,
        long borderDelayTicks,
        long borderShrinkTicks,
        double borderFinalSize,
        double borderDamagePerSecond,
        boolean showAbilityInKillFeed,
        boolean showTeamAbilities,
        Material abilityItemMaterial,
        double particleScale,
        boolean dayNight,
        double rainChance,
        int rainMinTicks,
        int rainMaxTicks
) {

    public static final String MODE = "selection.mode";
    public static final String REROLLS = "selection.rerolls";
    public static final String REVEAL_SECONDS = "selection.reveal-seconds";
    public static final String CHOICE_SECONDS = "selection.choice-seconds";
    public static final String PREP_SECONDS = "prep-seconds";
    public static final String TIME_LIMIT_SECONDS = "time-limit-seconds";
    public static final String KIT = "kit";
    public static final String GAUGE_DEALT = "ultimate.per-damage-dealt";
    public static final String GAUGE_TAKEN = "ultimate.per-damage-taken";
    public static final String GAUGE_SECOND = "ultimate.per-second";
    public static final String ANNOUNCE_ULTIMATES = "ultimate.announce";
    public static final String ULTIMATE_LOCK = "ultimate.recharge-delay-seconds";
    public static final String BORDER_DELAY = "border.start-delay-seconds";
    public static final String BORDER_SHRINK = "border.shrink-seconds";
    public static final String BORDER_FINAL = "border.final-diameter";
    public static final String BORDER_DAMAGE = "border.damage-per-second";
    public static final String KILL_FEED_ABILITY = "show-ability-in-kill-feed";
    public static final String TEAM_ABILITIES = "show-team-abilities";
    public static final String ABILITY_ITEM = "ability-item";
    public static final String PARTICLE_SCALE = "effects.particle-scale";
    public static final String SKY_DAY_NIGHT = "sky.day-night";
    public static final String SKY_RAIN_CHANCE = "sky.rain-chance";
    public static final String SKY_RAIN_MIN = "sky.rain-seconds.min";
    public static final String SKY_RAIN_MAX = "sky.rain-seconds.max";

    public static final double MIN_PARTICLE_SCALE = 0.25;
    public static final double MAX_PARTICLE_SCALE = 2.0;
    public static final int MAX_REROLLS = 10;
    public static final int MAX_PREP_SECONDS = 300;
    public static final int MAX_TIME_LIMIT_SECONDS = 7200;
    public static final int MAX_BORDER_SECONDS = 7200;
    /** Upper bound of {@code border.final-diameter}: the diameter of the largest arena. */
    public static final int MAX_BORDER_FINAL = 2 * HubConfig.MAX_RADIUS;
    public static final double DEFAULT_RAIN_CHANCE = 0.35;
    public static final int MIN_RAIN_SECONDS = 5;
    public static final int MAX_RAIN_SECONDS = 600;

    public static final List<String> DEFAULT_KIT = List.of(
            "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS", "IRON_SWORD",
            "COOKED_BEEF 16", "GOLDEN_APPLE 2");

    public SuperpowerSettings {
        kit = List.copyOf(kit);
    }

    /**
     * These settings for a practice session ({@code /게임 능력 연습}): no reveal, prep phase or rerolls, no time limit,
     * a border that never shrinks and a sky that never changes. Kit, gauge, effects and the ability item stay as
     * configured, so abilities behave and look exactly like in a real match.
     */
    public SuperpowerSettings forPractice() {
        return new SuperpowerSettings(SelectionMode.RANDOM, 0, 0, choiceTicks, 0, 0, kit, gaugePerDamageDealt,
                gaugePerDamageTaken, gaugePerSecond, announceUltimates, ultimateLockTicks, borderDelayTicks,
                borderShrinkTicks, Double.MAX_VALUE, borderDamagePerSecond, showAbilityInKillFeed, showTeamAbilities,
                abilityItemMaterial, particleScale, false, 0.0, rainMinTicks, rainMaxTicks);
    }

    /** The kit as fresh copies, safe to put into an inventory. */
    public List<ItemStack> kitCopy() {
        List<ItemStack> copy = new ArrayList<>(kit.size());
        for (ItemStack stack : kit) {
            copy.add(stack.clone());
        }
        return copy;
    }

    /** Declares the defaults (with Korean comments) merged into {@code config.yml} → {@code games.superpower}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.set(MODE, SelectionMode.RANDOM.name());
        d.setComments("selection", List.of("능력 배정"));
        d.setComments(MODE, List.of(
                "RANDOM = 무작위 (참가자끼리 최대한 겹치지 않게, 준비 시간 중 리롤 가능)",
                "CHOICE = 무작위 후보 3개 중 메뉴에서 직접 선택 (시간 초과 시 무작위)",
                "/게임 능력 선택방식 <RANDOM|CHOICE> 로도 바꿀 수 있습니다."));
        d.set(REROLLS, 1);
        d.setComments(REROLLS, List.of("RANDOM 방식에서 준비 시간 중 능력을 다시 뽑을 수 있는 횟수 (웅크리고 우클릭)"));
        d.set(REVEAL_SECONDS, 3);
        d.setComments(REVEAL_SECONDS, List.of("능력 공개 슬롯머신 연출 시간(초)"));
        d.set(CHOICE_SECONDS, 15);
        d.setComments(CHOICE_SECONDS, List.of("CHOICE 방식의 선택 제한 시간(초)"));

        d.set(PREP_SECONDS, 15);
        d.setComments(PREP_SECONDS, List.of("능력 공개 후 준비 시간(초). 무적이며 능력은 아직 쓸 수 없습니다."));
        d.set(TIME_LIMIT_SECONDS, 900);
        d.setComments(TIME_LIMIT_SECONDS, List.of(
                "전투 제한 시간(초). 시간이 다 되면 킬 수 → 남은 체력 순으로 승자를 정합니다. 0 = 무제한"));

        d.set(KIT, new ArrayList<>(DEFAULT_KIT));
        d.setComments(KIT, List.of(
                "시작할 때 지급하는 아이템. \"재료 [개수]\" 형식 (예: COOKED_BEEF 16)",
                "갑옷은 자동으로 착용합니다. 9번 칸(능력 아이템 자리)은 비워 둡니다."));

        // A section comment only sticks once the section exists, i.e. after its first value was set.
        d.set(GAUGE_DEALT, 4.0);
        d.setComments("ultimate", List.of("궁극기 게이지 (0~100%, 경험치 바에 표시)"));
        d.setComments(GAUGE_DEALT, List.of("적 플레이어에게 준 피해 1(하트 반 칸)당 충전량(%)"));
        d.set(GAUGE_TAKEN, 2.0);
        d.setComments(GAUGE_TAKEN, List.of("받은 피해 1당 충전량(%)"));
        d.set(GAUGE_SECOND, 0.5);
        d.setComments(GAUGE_SECOND, List.of("전투 중 1초마다 충전량(%)"));
        d.set(ANNOUNCE_ULTIMATES, true);
        d.setComments(ANNOUNCE_ULTIMATES, List.of("궁극기를 쓰면 모두에게 알립니다 (채팅 + 효과음)"));
        d.set(ULTIMATE_LOCK, 10);
        d.setComments(ULTIMATE_LOCK, List.of(
                "궁극기를 쓴 뒤 게이지가 다시 차기 시작할 때까지(초). 그동안 피해·시간으로 게이지가 차지 않습니다.",
                "(궁극기 자체의 피해, 소환수, 투사체로는 이 시간이 지나도 게이지가 차지 않습니다)"));

        d.set(BORDER_DELAY, 120);
        d.setComments("border", List.of("자기장 (경기장 크기에서 시작해 점점 줄어드는 가상 월드보더)"));
        d.setComments(BORDER_DELAY, List.of("전투 시작 후 자기장이 줄어들기 시작할 때까지(초)"));
        d.set(BORDER_SHRINK, 180);
        d.setComments(BORDER_SHRINK, List.of("최종 크기까지 줄어드는 데 걸리는 시간(초)"));
        d.set(BORDER_FINAL, 20);
        d.setComments(BORDER_FINAL, List.of("최종 자기장 한 변의 길이(블록)"));
        d.set(BORDER_DAMAGE, 2.0);
        d.setComments(BORDER_DAMAGE, List.of("자기장 밖에서 1초마다 받는 피해 (2 = 하트 1칸)"));

        d.set(KILL_FEED_ABILITY, true);
        d.setComments(KILL_FEED_ABILITY, List.of("킬로그에 능력 이름 표시: ☠ 철수(화염술사) → 영희(빙결술사)"));
        d.set(TEAM_ABILITIES, true);
        d.setComments(TEAM_ABILITIES, List.of(
                "팀전에서 같은 팀끼리 서로의 능력을 봅니다 (사이드바, 전투 시작 채팅, /게임 능력 팀)"));
        d.set(ABILITY_ITEM, Material.IRON_INGOT.name());
        d.setComments(ABILITY_ITEM, List.of(
                "능력 아이템의 기본 재료 (옛 \"능력자\" 플러그인의 철괴). 모양은 능력 아이콘으로 보입니다."));

        d.set(PARTICLE_SCALE, 1.0);
        d.setComments("effects", List.of("연출"));
        d.setComments(PARTICLE_SCALE, List.of("파티클 양 배율 (0.25~2.0, 서버가 버거우면 낮추세요)"));

        d.set(SKY_DAY_NIGHT, true);
        d.setComments("sky", List.of(
                "하늘 (전투 맵 월드 mcpl_map_* 에서만 바뀝니다. 일반 월드와 능력 연습은 그대로이고,",
                "경기가 끝나거나 중지되면 맵 월드는 다시 정오·맑음으로 돌아옵니다)"));
        d.setComments(SKY_DAY_NIGHT, List.of(
                "낮에 시작해 자기장이 줄어드는 동안 해가 지고, 자기장이 다 줄면 밤입니다 (마지막 싸움은 밤에)",
                "늑대인간·천문학자·팬텀·빛의 사도처럼 낮밤을 타는 능력이 제 힘을 냅니다."));
        d.set(SKY_RAIN_CHANCE, DEFAULT_RAIN_CHANCE);
        d.setComments(SKY_RAIN_CHANCE, List.of(
                "경기 중 한동안 비가 내릴 확률 (0~1, 0.35 = 35%. 1보다 크면 퍼센트로 읽습니다: 35 = 35%)",
                "전투 중 무작위 시점에 내리며 천둥·번개는 없습니다. 수류술사·낚시꾼·갓파가 비를 반깁니다."));
        d.set(SKY_RAIN_MIN, 60);
        d.setComments("sky.rain-seconds", List.of("비가 내리는 시간(초): 최소~최대 사이에서 무작위 (5~600)"));
        d.set(SKY_RAIN_MAX, 120);
    }

    /** Reads the live section; invalid values fall back to the defaults (with a console warning). */
    public static SuperpowerSettings read(ConfigurationSection s, Logger logger) {
        String rawMode = s.getString(MODE, SelectionMode.RANDOM.name());
        SelectionMode mode = SelectionMode.parse(rawMode);
        if (mode == null) {
            logger.warning("games.superpower." + MODE + ": unknown mode '" + rawMode + "', using RANDOM");
            mode = SelectionMode.RANDOM;
        }
        Material item = parseMaterial(s.getString(ABILITY_ITEM, "IRON_INGOT"));
        if (item == null || item.isAir() || !item.isItem()) {
            logger.warning("games.superpower." + ABILITY_ITEM + ": not an item, using IRON_INGOT");
            item = Material.IRON_INGOT;
        }
        List<String> kitSpecs = s.isList(KIT) ? s.getStringList(KIT) : DEFAULT_KIT;
        List<ItemStack> kit = new ArrayList<>();
        for (String spec : kitSpecs) {
            List<ItemStack> parsed = parseKitEntry(spec);
            if (parsed.isEmpty()) {
                logger.warning("games.superpower.kit: ignoring invalid entry '" + spec + "'");
            }
            kit.addAll(parsed);
        }
        int rainMin = seconds(s.getDouble(SKY_RAIN_MIN, 60), MIN_RAIN_SECONDS, MAX_RAIN_SECONDS);
        return new SuperpowerSettings(
                mode,
                clamp(s.getInt(REROLLS, 1), 0, MAX_REROLLS),
                seconds(s.getDouble(REVEAL_SECONDS, 3), 0, 15),
                seconds(s.getDouble(CHOICE_SECONDS, 15), 3, 120),
                seconds(s.getDouble(PREP_SECONDS, 15), 0, MAX_PREP_SECONDS),
                seconds(s.getDouble(TIME_LIMIT_SECONDS, 900), 0, MAX_TIME_LIMIT_SECONDS),
                kit,
                nonNegative(s.getDouble(GAUGE_DEALT, 4.0)),
                nonNegative(s.getDouble(GAUGE_TAKEN, 2.0)),
                nonNegative(s.getDouble(GAUGE_SECOND, 0.5)),
                s.getBoolean(ANNOUNCE_ULTIMATES, true),
                seconds(s.getDouble(ULTIMATE_LOCK, 10), 0, 120),
                seconds(s.getDouble(BORDER_DELAY, 120), 0, MAX_BORDER_SECONDS),
                seconds(s.getDouble(BORDER_SHRINK, 180), 0, MAX_BORDER_SECONDS),
                Math.max(1.0, Math.min(MAX_BORDER_FINAL, finite(s.getDouble(BORDER_FINAL, 20), 20))),
                nonNegative(s.getDouble(BORDER_DAMAGE, 2.0)),
                s.getBoolean(KILL_FEED_ABILITY, true),
                s.getBoolean(TEAM_ABILITIES, true),
                item,
                Math.max(MIN_PARTICLE_SCALE,
                        Math.min(MAX_PARTICLE_SCALE, finite(s.getDouble(PARTICLE_SCALE, 1.0), 1.0))),
                s.getBoolean(SKY_DAY_NIGHT, true),
                rainChance(s.getDouble(SKY_RAIN_CHANCE, DEFAULT_RAIN_CHANCE)),
                rainMin,
                Math.max(rainMin, seconds(s.getDouble(SKY_RAIN_MAX, 120), MIN_RAIN_SECONDS, MAX_RAIN_SECONDS)));
    }

    /**
     * The rain chance as 0..1 from the configured value: 0..1 as is, above 1 read as percent (35 = 35%), clamped to
     * 0..1 (the reader and the settings menu agree on it).
     */
    public static double rainChance(double configured) {
        double value = finite(configured, DEFAULT_RAIN_CHANCE);
        if (value > 1.0) {
            value /= 100.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Parses one kit entry: {@code "IRON_SWORD"}, {@code "COOKED_BEEF 16"}, {@code "cooked_beef x16"} or
     * {@code "minecraft:cooked_beef:16"}. Amounts above the stack size are split into several stacks. Returns an
     * empty list for an unknown or non-item material.
     */
    public static List<ItemStack> parseKitEntry(@Nullable String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        String[] parts = spec.trim().split("\\s+");
        String name = parts[0];
        String amountText = parts.length > 1 ? parts[1] : null;
        int colon = name.lastIndexOf(':');
        if (amountText == null && colon > 0 && name.substring(colon + 1).chars().allMatch(Character::isDigit)
                && colon < name.length() - 1) {
            amountText = name.substring(colon + 1);
            name = name.substring(0, colon);
        }
        Material material = parseMaterial(name);
        if (material == null || material.isAir() || !material.isItem()) {
            return List.of();
        }
        int amount = 1;
        if (amountText != null) {
            String digits = amountText.toLowerCase(Locale.ROOT).replace("x", "").replace("*", "");
            try {
                amount = Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        amount = clamp(amount, 1, 36 * 64);
        List<ItemStack> stacks = new ArrayList<>();
        ItemStack template = ItemStack.of(material);
        int max = Math.max(1, template.getMaxStackSize());
        while (amount > 0) {
            int size = Math.min(max, amount);
            ItemStack stack = template.clone();
            stack.setAmount(size);
            stacks.add(stack);
            amount -= size;
        }
        return stacks;
    }

    private static @Nullable Material parseMaterial(@Nullable String name) {
        return name == null ? null : Material.matchMaterial(name.trim());
    }

    private static int seconds(double seconds, double min, double max) {
        double value = Math.max(min, Math.min(max, finite(seconds, min)));
        return (int) Math.round(value * 20.0);
    }

    private static double nonNegative(double value) {
        return Math.max(0.0, finite(value, 0.0));
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
