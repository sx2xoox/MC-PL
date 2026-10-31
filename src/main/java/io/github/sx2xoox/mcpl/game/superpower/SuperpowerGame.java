package io.github.sx2xoox.mcpl.game.superpower;

import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.command.GameCommandRegistrar;
import io.github.sx2xoox.mcpl.core.command.Permissions;
import io.github.sx2xoox.mcpl.core.config.Setting;
import io.github.sx2xoox.mcpl.core.config.SettingsSection;
import io.github.sx2xoox.mcpl.core.game.GameInstance;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MiniGame;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.command.AbilityCommand;
import io.github.sx2xoox.mcpl.game.superpower.config.AbilityConfig;
import io.github.sx2xoox.mcpl.game.superpower.loot.LootSettings;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityItems;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityTexts;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * 초능력전쟁 — every player gets one of 24 superpowers (passive, right-click active, F ultimate) and the last player
 * or team standing wins inside a shrinking border. See {@code docs/superpower.md}.
 *
 * <p>Owns what outlives a single match: the ability registry, {@code abilities.yml}, the {@code superpower.yml}
 * messages, pending admin assignments ({@code /게임 능력 지정}) and the {@code /게임 능력} commands.
 */
public final class SuperpowerGame implements MiniGame {

    public static final String ID = "superpower";
    public static final String MESSAGES_FILE = "superpower.yml";

    private final Hub hub;
    private final AbilityRegistry registry = new AbilityRegistry();
    private final AbilityConfig abilities;
    private final Messages messages;
    private final AbilityTexts texts;
    private final AbilityItems items;
    private final Map<UUID, String> pendingAssignments = new LinkedHashMap<>();
    /** Abilities asked for with {@code /게임 능력 연습 <능력>}, used when the player enters a practice session. */
    private final Map<UUID, String> practiceRequests = new LinkedHashMap<>();
    /** Players who asked to join the running practice ({@code /게임 능력 연습}). */
    private final Set<UUID> practiceJoins = new HashSet<>();
    private final SuperpowerPracticeGame practice;
    private @Nullable List<SettingsSection> settings;
    private @Nullable SuperpowerMatch current;

    public SuperpowerGame(Hub hub) {
        this.hub = hub;
        BuiltinAbilities.registerAll(registry);
        this.messages = new Messages(hub.plugin(), MESSAGES_FILE, hub.messages());
        this.messages.load();
        this.abilities = new AbilityConfig(new File(hub.plugin().getDataFolder(), AbilityConfig.FILE_NAME),
                hub.plugin().getLogger());
        this.abilities.load(registry);
        this.texts = new AbilityTexts(messages);
        this.items = new AbilityItems(hub);
        this.practice = new SuperpowerPracticeGame(this);
    }

    // =================================================================== MiniGame

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "초능력전쟁";
    }

    @Override
    public Component displayComponent() {
        return messages.get("game.name");
    }

    @Override
    public List<String> aliases() {
        return List.of("초능력", "능력자", "능력자전쟁", "superpowers");
    }

    @Override
    public Material icon() {
        return Material.NETHER_STAR;
    }

    @Override
    public List<Component> description() {
        return messages.lines("game.description",
                Messages.tag("count", registry.all().size()));
    }

    @Override
    public int minPlayers() {
        return 2;
    }

    @Override
    public boolean supportsTeams() {
        return true;
    }

    @Override
    public void configDefaults(ConfigurationSection defaults) {
        SuperpowerSettings.declareDefaults(defaults);
        LootSettings.declareDefaults(defaults);
    }

    @Override
    public GameInstance createInstance(GameSession session) {
        return createMatch(session, false);
    }

    /** A new match of this mode or a practice session (both are the {@link #currentMatch()} while they run). */
    SuperpowerMatch createMatch(GameSession session, boolean practiceSession) {
        SuperpowerMatch match = new SuperpowerMatch(this, session, practiceSession);
        current = match;
        return match;
    }

    /** Match flow and border, effects and loot chests (see {@link SuperpowerSettingEntries}). */
    @Override
    public List<SettingsSection> settings() {
        if (settings == null) {
            settings = SuperpowerSettingEntries.create(this);
        }
        return settings;
    }

    /** The 능력 배정 방식 entry ({@code /게임 설정 선택방식}), which {@code /게임 능력 선택방식} changes too. */
    public Setting.Choice modeSetting() {
        for (SettingsSection section : settings()) {
            for (Setting setting : section.settings()) {
                if (setting.id().equals(SuperpowerSettingEntries.MODE) && setting instanceof Setting.Choice choice) {
                    return choice;
                }
            }
        }
        throw new IllegalStateException("the selection mode setting is missing");
    }

    @Override
    public void registerCommands(GameCommandRegistrar registrar) {
        new AbilityCommand(this).register(registrar);
    }

    @Override
    public void onReload() {
        messages.load();
        if (!abilities.load(registry)) {
            // The hub's reload message would say "reloaded": tell the admins the ability settings were not.
            Component warning = messages.prefixed("config.abilities-broken");
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(Permissions.ADMIN)) {
                    player.sendMessage(warning);
                }
            }
        }
    }

    @Override
    public void onShutdown() {
        current = null;
        pendingAssignments.clear();
        practiceRequests.clear();
        practiceJoins.clear();
    }

    // =================================================================== shared state

    public Hub hub() {
        return hub;
    }

    /** The ability practice ({@code /게임 능력 연습}), a second mini-game sharing this mode's abilities. */
    public SuperpowerPracticeGame practice() {
        return practice;
    }

    /** Remembers the ability {@code player} wants in practice (null = random). */
    public void requestPractice(UUID player, @Nullable String abilityId) {
        if (abilityId == null) {
            practiceRequests.remove(player);
        } else {
            practiceRequests.put(player, abilityId);
        }
    }

    /** Removes and returns the practice ability requested by {@code player}, or null for a random one. */
    public @Nullable String takePracticeRequest(UUID player) {
        return practiceRequests.remove(player);
    }

    /** {@code player} asked to join the running practice (only such players, and rejoining ones, are let in). */
    public void allowPracticeJoin(UUID player, boolean allowed) {
        if (allowed) {
            practiceJoins.add(player);
        } else {
            practiceJoins.remove(player);
        }
    }

    /** Removes and returns whether {@code player} asked to join the running practice. */
    public boolean takePracticeJoin(UUID player) {
        return practiceJoins.remove(player);
    }

    public AbilityRegistry registry() {
        return registry;
    }

    /** {@code abilities.yml} (enabled flags and params). */
    public AbilityConfig abilities() {
        return abilities;
    }

    public Messages messages() {
        return messages;
    }

    public AbilityTexts texts() {
        return texts;
    }

    public AbilityItems items() {
        return items;
    }

    /** The running match of this mode, or null (also null while another mode runs). */
    public @Nullable SuperpowerMatch currentMatch() {
        SuperpowerMatch match = current;
        GameSession session = hub.games().session();
        if (match == null || session == null || session.instance() != match) {
            return null;
        }
        return match;
    }

    /** Called by the match when it stops. */
    public void matchEnded(SuperpowerMatch match) {
        if (current == match) {
            current = null;
        }
    }

    /** The selection mode the next match will use. */
    public SelectionMode selectionMode() {
        SelectionMode mode = SelectionMode.parse(hub.config().gameSection(ID).getString(SuperpowerSettings.MODE));
        return mode == null ? SelectionMode.RANDOM : mode;
    }

    /** Saves the selection mode to {@code config.yml} (applies to the next match). */
    public void setSelectionMode(SelectionMode mode) {
        hub.config().setGameValue(ID, SuperpowerSettings.MODE, mode.name());
    }

    /** Remembers an admin assignment for the next match (replacing an earlier one for the same player). */
    public void queueAssignment(UUID player, String abilityId) {
        pendingAssignments.put(player, abilityId);
    }

    /** Pending assignments (read-only view). */
    public Map<UUID, String> pendingAssignments() {
        return java.util.Collections.unmodifiableMap(pendingAssignments);
    }

    /** Removes and returns the pending assignments of the given match participants. */
    public Map<UUID, String> takeAssignments(Collection<UUID> participants) {
        Map<UUID, String> taken = new LinkedHashMap<>();
        for (UUID id : participants) {
            String ability = pendingAssignments.remove(id);
            if (ability != null) {
                taken.put(id, ability);
            }
        }
        return taken;
    }

    /**
     * Finds an ability by id or Korean name (case and spaces ignored), also accepting a unique prefix of the name.
     */
    public @Nullable AbilityInfo findAbility(String input) {
        String wanted = normalize(input);
        if (wanted.isEmpty()) {
            return null;
        }
        AbilityInfo prefixMatch = null;
        int prefixMatches = 0;
        for (AbilityRegistry.Entry entry : registry.all()) {
            AbilityInfo info = entry.info();
            if (info.id().equals(wanted) || normalize(info.name()).equals(wanted)) {
                return info;
            }
            if (normalize(info.name()).startsWith(wanted) || info.id().startsWith(wanted)) {
                prefixMatch = info;
                prefixMatches++;
            }
        }
        return prefixMatches == 1 ? prefixMatch : null;
    }

    private static String normalize(String text) {
        return text.replace(" ", "").toLowerCase(Locale.ROOT);
    }
}
