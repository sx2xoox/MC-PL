package io.github.sx2xoox.mcpl.game.siege.shop;

import java.util.EnumMap;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The 공성전 v2 shop ({@code games.siege.shop.*}; docs/siege-v2.md §3). Times in ticks, distances in blocks, health in
 * health points, percentages as fractions (0.06 = 6 %).
 *
 * @param range           how close to the own shop terminal a player must be to buy
 * @param slots           item slots per player
 * @param prices          each item's price
 * @param candyHeal       체력 사탕: health healed by one candy
 * @param candyTicks      체력 사탕: over this long
 * @param candyCount      체력 사탕: candies per purchase
 * @param candyStack      체력 사탕: most candies in the slot
 * @param armorPoints     장난감 갑옷 1·2·3단계: armour points
 * @param armorSkill      장난감 갑옷 1·2·3단계: ability damage taken reduction
 * @param swordDamage     나무검 / 강철검: melee damage
 * @param orbSkill        마법 구슬: ability damage
 * @param shoesSpeed      태엽 신발: movement speed
 * @param hourglass       모래시계 장신구: cooldown reduction
 * @param hammer          망치 장갑: tower and core damage
 * @param wardRadius      망원경 와드: reveal radius
 * @param wardTicks       망원경 와드: how long a ward stands
 * @param wardCount       망원경 와드: wards per purchase
 * @param wardStack       망원경 와드: most wards in the slot
 * @param wardsPlaced     망원경 와드: most standing wards per player (the oldest goes)
 * @param reviveSeconds   부활 시계: respawn change (negative = sooner)
 * @param recallTicks     귀환 깃발: channel time
 */
public record ShopSettings(
        double range,
        int slots,
        Map<ShopItem, Integer> prices,
        double candyHeal,
        int candyTicks,
        int candyCount,
        int candyStack,
        double[] armorPoints,
        double[] armorSkill,
        double[] swordDamage,
        double orbSkill,
        double shoesSpeed,
        double hourglass,
        double hammer,
        double wardRadius,
        int wardTicks,
        int wardCount,
        int wardStack,
        int wardsPlaced,
        double reviveSeconds,
        int recallTicks) {

    public static final String PATH = "shop.";

    public static ShopSettings read(ConfigurationSection config) {
        Map<ShopItem, Integer> prices = new EnumMap<>(ShopItem.class);
        for (ShopItem item : ShopItem.values()) {
            prices.put(item, Math.max(0, config.getInt(PATH + "price." + item.key(), item.defaultPrice())));
        }
        return new ShopSettings(
                config.getDouble(PATH + "range", 8),
                Math.max(1, config.getInt(PATH + "slots", 6)),
                prices,
                config.getDouble(PATH + "candy.heal", 8),
                ticks(config.getDouble(PATH + "candy.seconds", 10)),
                Math.max(1, config.getInt(PATH + "candy.count", 3)),
                Math.max(1, config.getInt(PATH + "candy.stack", 9)),
                new double[] {
                    config.getDouble(PATH + "armor.points-1", 2),
                    config.getDouble(PATH + "armor.points-2", 4),
                    config.getDouble(PATH + "armor.points-3", 6)},
                new double[] {
                    config.getDouble(PATH + "armor.skill-reduction-1", 0.06),
                    config.getDouble(PATH + "armor.skill-reduction-2", 0.12),
                    config.getDouble(PATH + "armor.skill-reduction-3", 0.18)},
                new double[] {
                    config.getDouble(PATH + "sword.damage-1", 1.5),
                    config.getDouble(PATH + "sword.damage-2", 3)},
                config.getDouble(PATH + "orb.skill-damage", 0.10),
                config.getDouble(PATH + "shoes.speed", 0.08),
                config.getDouble(PATH + "hourglass.cooldown", 0.10),
                config.getDouble(PATH + "hammer.structure-damage", 0.20),
                config.getDouble(PATH + "ward.radius", 12),
                ticks(config.getDouble(PATH + "ward.seconds", 90)),
                Math.max(1, config.getInt(PATH + "ward.count", 2)),
                Math.max(1, config.getInt(PATH + "ward.stack", 4)),
                Math.max(1, config.getInt(PATH + "ward.placed", 3)),
                config.getDouble(PATH + "revive-clock.seconds", -2),
                Math.max(1, ticks(config.getDouble(PATH + "recall.seconds", 8))));
    }

    public int price(ShopItem item) {
        return prices.get(item);
    }

    static int ticks(double seconds) {
        return (int) Math.round(seconds * 20);
    }
}
