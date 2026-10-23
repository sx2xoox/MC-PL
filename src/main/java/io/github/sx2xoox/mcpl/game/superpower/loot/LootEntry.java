package io.github.sx2xoox.mcpl.game.superpower.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.sx2xoox.mcpl.game.superpower.SuperpowerSettings;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.PotionContents;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;
import org.jspecify.annotations.Nullable;

/**
 * One entry of a loot pool ({@code games.superpower.loot.<tier>.pool}), parsed from a line such as
 * <pre>
 *   GOLDEN_APPLE 1-2 weight=12
 *   POTION swiftness seconds=30 weight=8
 *   SPLASH_POTION healing level=2
 *   BOW + ARROW 8-12 weight=5
 *   ULTIMATE_CRYSTAL weight=3
 * </pre>
 * Every {@code +}-separated part is one item: the item as in the kit ({@code MATERIAL}, {@code minecraft:material:16},
 * or a superpower consumable {@code ULTIMATE_CRYSTAL} / {@code RECHARGE_CHARM}), then optionally a potion type (for
 * potions and tipped arrows), an amount ({@code 3}, {@code x3} or a range {@code 2-4}) and the options
 * {@code seconds=} (effect length of a potion) and {@code level=} (effect level). {@code weight=} (default
 * {@value #DEFAULT_WEIGHT}) applies to the whole entry: how likely it is picked compared to the pool's other entries.
 *
 * @param parts  the items handed out together
 * @param weight the relative chance of being picked ({@code 0} = never)
 * @param spec   the original line (for messages)
 */
public record LootEntry(List<Part> parts, int weight, String spec) {

    public static final int DEFAULT_WEIGHT = 10;
    private static final int MAX_AMOUNT = 36 * 64;
    private static final Pattern AMOUNT = Pattern.compile("^[x*]?(\\d+)$");
    private static final Pattern RANGE = Pattern.compile("^(\\d+)[-~](\\d+)$");

    /**
     * One item of an entry.
     *
     * @param template   the item (one of it), or null for a consumable
     * @param consumable the superpower consumable, or null for an item
     * @param min        smallest amount
     * @param max        largest amount
     */
    public record Part(@Nullable ItemStack template, LootConsumables.@Nullable Kind consumable, int min, int max) {
    }

    public LootEntry {
        parts = List.copyOf(parts);
    }

    /**
     * Parses one pool line.
     *
     * @throws IllegalArgumentException with a short English reason if the line is invalid
     */
    public static LootEntry parse(@Nullable String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("empty entry");
        }
        Integer weight = null;
        List<Part> parts = new ArrayList<>();
        for (String text : spec.split("\\+", -1)) {
            String[] tokens = text.trim().split("\\s+");
            if (tokens.length == 0 || tokens[0].isEmpty()) {
                throw new IllegalArgumentException("missing item around '+'");
            }
            Integer seconds = null;
            Integer level = null;
            String potion = null;
            int[] amount = null;
            for (int i = 1; i < tokens.length; i++) {
                String token = tokens[i];
                int equals = token.indexOf('=');
                if (equals > 0) {
                    String key = token.substring(0, equals).toLowerCase(Locale.ROOT);
                    int value = number(token.substring(equals + 1), token);
                    switch (key) {
                        case "weight", "w", "가중치" -> {
                            if (value < 0) {
                                throw new IllegalArgumentException("negative weight");
                            }
                            weight = value;
                        }
                        case "seconds", "sec", "초" -> seconds = within(value, 1, 3600, "seconds");
                        case "level", "lv", "레벨" -> level = within(value, 1, 10, "level");
                        default -> throw new IllegalArgumentException("unknown option '" + key + "'");
                    }
                } else {
                    int[] parsed = amount == null ? amount(token) : null;
                    if (parsed != null) {
                        amount = parsed;
                    } else if (potion == null) {
                        potion = token;
                    } else {
                        throw new IllegalArgumentException("unexpected '" + token + "'");
                    }
                }
            }
            parts.add(part(tokens[0], potion, amount, seconds, level));
        }
        return new LootEntry(parts, weight == null ? DEFAULT_WEIGHT : weight, spec.trim());
    }

    private static Part part(String item, @Nullable String potion, int @Nullable [] amount, @Nullable Integer seconds,
                             @Nullable Integer level) {
        LootConsumables.Kind kind = LootConsumables.Kind.parse(item);
        if (kind != null) {
            if (potion != null || seconds != null || level != null) {
                throw new IllegalArgumentException(item + " takes only an amount");
            }
            int min = amount == null ? 1 : amount[0];
            int max = amount == null ? 1 : amount[1];
            return new Part(null, kind, min, max);
        }
        // The kit's "MATERIAL", "minecraft:material" and "material:16" forms.
        List<ItemStack> stacks = SuperpowerSettings.parseKitEntry(item);
        if (stacks.isEmpty()) {
            throw new IllegalArgumentException("unknown item '" + item + "'");
        }
        int kitAmount = 0;
        for (ItemStack stack : stacks) {
            kitAmount += stack.getAmount();
        }
        ItemStack template = stacks.getFirst().asOne();
        if (isPotion(template.getType())) {
            if (potion == null) {
                if (seconds != null || level != null) {
                    throw new IllegalArgumentException("seconds/level need a potion type");
                }
            } else {
                applyPotion(template, potion, seconds, level);
            }
        } else if (potion != null) {
            throw new IllegalArgumentException("unexpected '" + potion + "' (only potions take a type)");
        } else if (seconds != null || level != null) {
            throw new IllegalArgumentException("seconds/level only apply to potions");
        }
        int min = amount == null ? kitAmount : amount[0];
        int max = amount == null ? kitAmount : amount[1];
        return new Part(template, null, min, max);
    }

    private static boolean isPotion(Material material) {
        return material == Material.POTION || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION || material == Material.TIPPED_ARROW;
    }

    /**
     * A vanilla potion ({@code POTION swiftness}), or with {@code seconds}/{@code level} a potion of the same name and
     * colour whose effects last that long / have that level.
     */
    private static void applyPotion(ItemStack item, String name, @Nullable Integer seconds, @Nullable Integer level) {
        PotionType type = potionType(name);
        if (seconds == null && level == null) {
            item.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents().potion(type).build());
            return;
        }
        List<PotionEffect> base = type.getPotionEffects();
        if (base.isEmpty()) {
            throw new IllegalArgumentException("potion '" + name + "' has no effect");
        }
        Color color = base.getFirst().getType().getColor();
        PotionContents.Builder contents = PotionContents.potionContents()
                .customName(baseName(type))
                .customColor(color);
        for (PotionEffect effect : base) {
            int duration = effect.getType().isInstant() ? 1
                    : seconds != null ? seconds * 20 : effect.getDuration();
            int amplifier = level != null ? level - 1 : effect.getAmplifier();
            contents.addCustomEffect(new PotionEffect(effect.getType(), duration, amplifier, false, true, true));
        }
        item.setData(DataComponentTypes.POTION_CONTENTS, contents.build());
    }

    private static PotionType potionType(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        for (PotionType type : PotionType.values()) {
            if (type.getKey().getKey().equals(key)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown potion type '" + name + "'");
    }

    /** {@code strong_healing -> healing}: the name part of the potion's translation key. */
    private static String baseName(PotionType type) {
        String key = type.getKey().getKey();
        if (key.startsWith("long_")) {
            return key.substring("long_".length());
        }
        if (key.startsWith("strong_")) {
            return key.substring("strong_".length());
        }
        return key;
    }

    private static int @Nullable [] amount(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        Matcher single = AMOUNT.matcher(lower);
        if (single.matches()) {
            int value = within(number(single.group(1), token), 1, MAX_AMOUNT, "amount");
            return new int[] {value, value};
        }
        Matcher range = RANGE.matcher(lower);
        if (range.matches()) {
            int min = within(number(range.group(1), token), 1, MAX_AMOUNT, "amount");
            int max = within(number(range.group(2), token), 1, MAX_AMOUNT, "amount");
            if (min > max) {
                throw new IllegalArgumentException("amount range " + token + " runs backwards");
            }
            return new int[] {min, max};
        }
        return null;
    }

    private static int number(String text, String token) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number in '" + token + "'", e);
        }
    }

    private static int within(int value, int min, int max, String what) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(what + " must be " + min + ".." + max);
        }
        return value;
    }

    /**
     * Rolls the stacks of this entry: each part gets an amount in its range, split into stacks of the item's stack
     * size.
     *
     * @param gaugePercent the gauge an ultimate crystal grants (for its lore)
     */
    public List<ItemStack> roll(Random random, LootConsumables consumables, double gaugePercent) {
        List<ItemStack> stacks = new ArrayList<>();
        for (Part part : parts) {
            int amount = part.min() + (part.max() > part.min() ? random.nextInt(part.max() - part.min() + 1) : 0);
            LootConsumables.Kind kind = part.consumable();
            ItemStack template = kind != null ? consumables.create(kind, 1, gaugePercent) : part.template();
            if (template == null) {
                continue;
            }
            int max = Math.max(1, template.getMaxStackSize());
            while (amount > 0) {
                ItemStack stack = template.clone();
                int size = Math.min(max, amount);
                stack.setAmount(size);
                stacks.add(stack);
                amount -= size;
            }
        }
        return stacks;
    }
}
