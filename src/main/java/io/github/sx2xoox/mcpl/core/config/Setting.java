package io.github.sx2xoox.mcpl.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * One value an admin can change in game: an entry of the settings menu ({@code /게임 설정}) and a matching command
 * ({@code /게임 설정 <이름> <값>}, English twin {@code /mcpl settings <name> <value>}). The value lives in
 * {@code config.yml}; the getter reads it and the setter writes it through {@link HubConfig} (which saves at once and
 * keeps the file's comments). Values are clamped to the same ranges the config readers use.
 *
 * <p>Texts come from the owner's message file: {@code <textKey>.name} (short name), {@code <textKey>.lore}
 * (explanation lines), optionally {@code <textKey>.zero} (label shown instead of a number that is 0, e.g. 개인전) and
 * {@code <textKey>.option.<value>} (labels of a {@link Choice}). On/off labels and units come from the hub's
 * {@code messages.yml} ({@code settings.on}, {@code settings.unit.*}).
 *
 * <p>Create entries with {@link #builder(String)} and hand them to the hub in a {@link SettingsSection} (see
 * {@link io.github.sx2xoox.mcpl.core.game.MiniGame#settings()}).
 */
public abstract sealed class Setting permits Setting.Toggle, Setting.Number, Setting.Choice {

    /** How a number is shown (message keys {@code settings.unit.<key>} of {@code messages.yml}). */
    public enum Unit {
        /** Whole seconds; a minute or more is also shown as minutes and seconds. */
        SECONDS("seconds"),
        BLOCKS("blocks"),
        PLAYERS("players"),
        COUNT("count"),
        TIMES("times"),
        MULTIPLIER("multiplier");

        private final String key;

        Unit(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** A requested number was outside the range and clamped to this end of it. */
    public enum Limit {
        MIN,
        MAX
    }

    /**
     * What a change did.
     *
     * @param changed   the stored value changed
     * @param direction +1 for up / on / next, -1 for down / off / previous, 0 when nothing changed
     * @param limit     the request ran into the minimum or maximum (it was clamped), or null
     */
    public record Change(boolean changed, int direction, @Nullable Limit limit) {

        static final Change NONE = new Change(false, 0, null);
    }

    private final String id;
    private final List<String> korean;
    private final List<String> english;
    private final Material icon;
    private final Messages messages;
    private final String textKey;
    private final String path;

    private Setting(Builder builder) {
        this.id = builder.id;
        this.korean = List.copyOf(builder.korean);
        this.english = List.copyOf(builder.english);
        this.icon = builder.icon;
        this.messages = Objects.requireNonNull(builder.messages, "text(messages, key) is required");
        this.textKey = Objects.requireNonNull(builder.textKey, "text(messages, key) is required");
        this.path = builder.path;
        if (korean.isEmpty() || english.isEmpty()) {
            throw new IllegalArgumentException("setting " + id + " needs a Korean and an English command name");
        }
    }

    /** Starts a setting with a stable id (e.g. {@code "superpower.loot"}, used in logs and tests). */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    /** The Korean command words after {@code /게임 설정}, e.g. {@code [자기장, 시작]}. */
    public List<String> korean() {
        return korean;
    }

    /** The English command words after {@code /mcpl settings}, e.g. {@code [border, delay]}. */
    public List<String> english() {
        return english;
    }

    public Material icon() {
        return icon;
    }

    /** Where the value is saved, e.g. {@code games.superpower.loot.enabled} (shown in the menu). */
    public String path() {
        return path;
    }

    /** The short name, e.g. "보급 상자". */
    public Component name() {
        return messages.get(textKey + ".name");
    }

    /** The explanation lines. */
    public List<Component> description() {
        return messages.lines(textKey + ".lore");
    }

    /** The current value as shown in chat and the menu. */
    public abstract Component valueText(Messages hub);

    /** The default value as shown in the menu. */
    public abstract Component defaultText(Messages hub);

    /** The value placeholder of the command usage, e.g. {@code <켜기|끄기>}. */
    public abstract String usageHint(boolean english);

    /** Sets the default value. */
    public abstract Change reset();

    /** The label of the message key {@code <textKey>.<suffix>}, or null if the owner's file has none. */
    @Nullable Component optionalText(String suffix) {
        String key = textKey + "." + suffix;
        return messages.has(key) ? messages.get(key) : null;
    }

    // =================================================================== kinds

    /** An on/off switch: any click flips it; commands take {@code 켜기|끄기} ({@code on|off}). */
    public static final class Toggle extends Setting {

        private final BooleanSupplier getter;
        private final Consumer<Boolean> setter;
        private final boolean defaultValue;

        private Toggle(Builder builder, BooleanSupplier getter, Consumer<Boolean> setter, boolean defaultValue) {
            super(builder);
            this.getter = getter;
            this.setter = setter;
            this.defaultValue = defaultValue;
        }

        public boolean value() {
            return getter.getAsBoolean();
        }

        public boolean defaultValue() {
            return defaultValue;
        }

        public Change set(boolean value) {
            if (value() == value) {
                return Change.NONE;
            }
            setter.accept(value);
            return new Change(true, value ? 1 : -1, null);
        }

        public Change toggle() {
            return set(!value());
        }

        @Override
        public Change reset() {
            return set(defaultValue);
        }

        @Override
        public Component valueText(Messages hub) {
            return hub.get(value() ? "settings.on" : "settings.off");
        }

        @Override
        public Component defaultText(Messages hub) {
            return hub.get(defaultValue ? "settings.on" : "settings.off");
        }

        @Override
        public String usageHint(boolean english) {
            return english ? "<on|off>" : "<켜기|끄기>";
        }
    }

    /**
     * A number in {@code min..max}: left click adds {@code step}, right click subtracts it, with shift
     * {@code bigStep}; commands take the number and clamp it.
     */
    public static final class Number extends Setting {

        private final DoubleSupplier getter;
        private final DoubleConsumer setter;
        private final double defaultValue;
        private final double min;
        private final double max;
        private final double step;
        private final double bigStep;
        private final boolean integer;
        private final Unit unit;

        private Number(Builder builder, DoubleSupplier getter, DoubleConsumer setter, double defaultValue) {
            super(builder);
            if (!(builder.min <= builder.max) || builder.step <= 0 || builder.bigStep <= 0) {
                throw new IllegalArgumentException("setting " + builder.id + " needs range(min, max) and steps");
            }
            this.getter = getter;
            this.setter = setter;
            this.min = builder.min;
            this.max = builder.max;
            this.step = builder.step;
            this.bigStep = builder.bigStep;
            this.integer = builder.integer;
            this.unit = builder.unit;
            this.defaultValue = clamp(defaultValue, builder.min);
        }

        /** The effective value: the stored one clamped to the range (the default if it is not a number). */
        public double value() {
            return clamp(getter.getAsDouble(), defaultValue);
        }

        public double defaultValue() {
            return defaultValue;
        }

        public double min() {
            return min;
        }

        public double max() {
            return max;
        }

        public double step() {
            return step;
        }

        public double bigStep() {
            return bigStep;
        }

        /** True for whole numbers (commands take an integer). */
        public boolean integer() {
            return integer;
        }

        public Unit unit() {
            return unit;
        }

        /** Clamps (and for whole numbers rounds) {@code requested}, then stores it. */
        public Change set(double requested) {
            if (Double.isNaN(requested)) {
                return Change.NONE;
            }
            Limit limit = requested < min ? Limit.MIN : requested > max ? Limit.MAX : null;
            double target = clamp(requested, defaultValue);
            double raw = getter.getAsDouble();
            if (target == raw) {
                return new Change(false, 0, limit);
            }
            double before = value();
            setter.accept(target);
            return new Change(true, Double.compare(target, before), limit);
        }

        /** Adds {@code direction} steps ({@code bigStep} with {@code big}). */
        public Change step(int direction, boolean big) {
            return set(value() + Integer.signum(direction) * (big ? bigStep : step));
        }

        @Override
        public Change reset() {
            return set(defaultValue);
        }

        /** Clamps to the range and rounds (whole numbers, otherwise two decimals); not a number → fallback. */
        private double clamp(double value, double fallback) {
            double finite = Double.isFinite(value) ? value : fallback;
            double clamped = Math.max(min, Math.min(max, finite));
            return integer ? Math.round(clamped) : Math.round(clamped * 100.0) / 100.0;
        }

        /** {@code value} formatted like the value of this setting (unit, or the zero label). */
        public Component format(Messages hub, double value) {
            if (value == 0) {
                Component zero = optionalText("zero");
                if (zero != null) {
                    return zero;
                }
            }
            String number = Format.decimal(value);
            if (unit == Unit.SECONDS && value >= 60) {
                return hub.get("settings.unit.minutes", Messages.tag("value", number),
                        Messages.tag("duration", Format.duration(Math.round(value * 1000))));
            }
            return hub.get("settings.unit." + unit.key(), Messages.tag("value", number));
        }

        @Override
        public Component valueText(Messages hub) {
            return format(hub, value());
        }

        @Override
        public Component defaultText(Messages hub) {
            return format(hub, defaultValue);
        }

        @Override
        public String usageHint(boolean english) {
            return "<" + argumentName(english) + ">";
        }

        /** The name of the command argument, e.g. {@code 초} / {@code seconds}. */
        public String argumentName(boolean english) {
            return switch (unit) {
                case SECONDS -> english ? "seconds" : "초";
                case BLOCKS -> english ? "blocks" : "블록";
                case PLAYERS -> english ? "players" : "인원";
                case COUNT -> english ? "count" : "개수";
                case TIMES -> english ? "times" : "횟수";
                case MULTIPLIER -> english ? "scale" : "배율";
            };
        }
    }

    /** One of a few named values: left click picks the next, right click the previous. */
    public static final class Choice extends Setting {

        /**
         * One value.
         *
         * @param value   the stored name, e.g. {@code MAP_WORLDS} (its label is {@code <textKey>.option.map-worlds})
         * @param korean  Korean command words for it, e.g. {@code 맵에서만}
         * @param english English command words, e.g. {@code maps}
         */
        public record Option(String value, List<String> korean, List<String> english) {

            public Option {
                korean = List.copyOf(korean);
                english = List.copyOf(english);
            }

            /** The message key suffix of the label. */
            public String key() {
                return value.toLowerCase(Locale.ROOT).replace('_', '-');
            }
        }

        private final List<Option> options;
        private final Supplier<@Nullable String> getter;
        private final Consumer<String> setter;
        private final Option defaultOption;

        private Choice(Builder builder, List<Option> options, Supplier<@Nullable String> getter,
                       Consumer<String> setter, String defaultValue) {
            super(builder);
            if (options.isEmpty()) {
                throw new IllegalArgumentException("setting " + builder.id + " has no options");
            }
            this.options = List.copyOf(options);
            this.getter = getter;
            this.setter = setter;
            Option found = find(defaultValue);
            this.defaultOption = found == null ? this.options.getFirst() : found;
        }

        public List<Option> options() {
            return options;
        }

        /** The current option (the default if the stored value is unknown). */
        public Option value() {
            Option found = find(getter.get());
            return found == null ? defaultOption : found;
        }

        public Option defaultOption() {
            return defaultOption;
        }

        private @Nullable Option find(@Nullable String value) {
            if (value == null) {
                return null;
            }
            for (Option option : options) {
                if (option.value().equalsIgnoreCase(value.trim())) {
                    return option;
                }
            }
            return null;
        }

        public Change set(Option option) {
            Option current = value();
            if (current.equals(option)) {
                if (!option.value().equals(getter.get())) {
                    setter.accept(option.value()); // the same option spelled differently: store the usual name
                }
                return Change.NONE;
            }
            setter.accept(option.value());
            return new Change(true, Integer.signum(options.indexOf(option) - options.indexOf(current)), null);
        }

        /** Picks the next ({@code direction > 0}) or previous option, wrapping around. */
        public Change cycle(int direction) {
            int index = options.indexOf(value());
            int next = Math.floorMod(index + (direction >= 0 ? 1 : -1), options.size());
            Change change = set(options.get(next));
            return change.changed() ? new Change(true, direction >= 0 ? 1 : -1, null) : change;
        }

        @Override
        public Change reset() {
            return set(defaultOption);
        }

        /** The label of {@code option}. */
        public Component label(Option option) {
            Component label = optionalText("option." + option.key());
            return label != null ? label : Component.text(option.value());
        }

        @Override
        public Component valueText(Messages hub) {
            return label(value());
        }

        @Override
        public Component defaultText(Messages hub) {
            return label(defaultOption);
        }

        @Override
        public String usageHint(boolean english) {
            List<String> words = new ArrayList<>();
            for (Option option : options) {
                words.add(english ? option.english().getFirst() : option.korean().getFirst());
            }
            return "<" + String.join("|", words) + ">";
        }
    }

    // =================================================================== builder

    /** Collects the shared parts; finish with {@link #toggle}, {@link #number} or {@link #choice}. */
    public static final class Builder {

        private final String id;
        private List<String> korean = List.of();
        private List<String> english = List.of();
        private Material icon = Material.PAPER;
        private @Nullable Messages messages;
        private @Nullable String textKey;
        private String path = "";
        private double min = Double.NaN;
        private double max = Double.NaN;
        private double step = 1;
        private double bigStep = 5;
        private boolean integer;
        private Unit unit = Unit.COUNT;

        private Builder(String id) {
            this.id = id;
        }

        /** One-word command names, e.g. {@code ("상자", "loot")}. */
        public Builder command(String korean, String english) {
            return command(List.of(korean), List.of(english));
        }

        /** Command words, e.g. {@code (List.of("자기장", "시작"), List.of("border", "delay"))}. */
        public Builder command(List<String> korean, List<String> english) {
            this.korean = korean;
            this.english = english;
            return this;
        }

        public Builder icon(Material icon) {
            this.icon = icon;
            return this;
        }

        /** The message file and the key prefix of the texts ({@code .name}, {@code .lore}, ...). */
        public Builder text(Messages messages, String textKey) {
            this.messages = messages;
            this.textKey = textKey;
            return this;
        }

        /** Where the value is saved in {@code config.yml} (shown in the menu). */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /** Numbers: the allowed range (the config reader's clamp). */
        public Builder range(double min, double max) {
            this.min = min;
            this.max = max;
            return this;
        }

        /** Numbers: the click step and the shift-click step. */
        public Builder steps(double step, double bigStep) {
            this.step = step;
            this.bigStep = bigStep;
            return this;
        }

        /** Numbers: whole numbers only. */
        public Builder integer() {
            this.integer = true;
            return this;
        }

        /** Numbers: how the value is shown. */
        public Builder unit(Unit unit) {
            this.unit = unit;
            return this;
        }

        public Toggle toggle(BooleanSupplier getter, Consumer<Boolean> setter, boolean defaultValue) {
            return new Toggle(this, getter, setter, defaultValue);
        }

        public Number number(DoubleSupplier getter, DoubleConsumer setter, double defaultValue) {
            return new Number(this, getter, setter, defaultValue);
        }

        public Choice choice(List<Choice.Option> options, Supplier<@Nullable String> getter, Consumer<String> setter,
                             String defaultValue) {
            return new Choice(this, options, getter, setter, defaultValue);
        }
    }
}
