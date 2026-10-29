package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour;
import io.github.sx2xoox.mcpl.game.augment.bot.BotChat;
import io.github.sx2xoox.mcpl.game.augment.bot.BotChat.Trigger;
import io.github.sx2xoox.mcpl.game.augment.bot.BotController;
import io.github.sx2xoox.mcpl.game.augment.bot.BotManager;
import io.github.sx2xoox.mcpl.game.augment.bot.Gear;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * 봇 채팅 of 봇전 and 봇 관전전: watches the bots (what they decide, their health, gear and base) and the match's
 * events (augments, kills, deaths, the border, the win) and lets {@link BotChat} decide what they say. A line looks
 * like a player's chat line ({@code <철수 봇> 거기 숨은 거 다 보여}). Bots only mention enemies they can see or that
 * fought them; no line carries a position.
 */
final class BotChatFeed {

    /** The lines file in the plugin folder (bundled default copied on first use). */
    static final String FILE = "augment-bots-chat.yml";
    /** A spotted enemy is spotted again only after this long (ticks). */
    static final long RESPOT = 60 * 20;
    /** A spot counts when the enemy is this close (blocks) and in line of sight. */
    static final double SPOT_RANGE = 40;
    /** Health fraction under which a bot is in trouble (an escape later counts). */
    static final double NEAR_DEATH = 0.25;
    /** Health fraction at which it got away. */
    static final double RECOVERED = 0.6;
    /** A bot is out of a fight when nobody hit it for this long (ticks). */
    static final long CALM = 5 * 20;
    /** The third-party window: the fight it joined (ticks). */
    static final long THIRD_PARTY_WINDOW = 8 * 20;

    private static final Set<Behaviour> ENGAGING = EnumSet.of(Behaviour.ENGAGE, Behaviour.KITE, Behaviour.AMBUSH);
    private static final Set<Behaviour> FALLING_BACK = EnumSet.of(Behaviour.RETREAT_HEAL, Behaviour.FORTIFY,
            Behaviour.AVOID, Behaviour.DEFEND_BASE);

    /** A line to consider later (staggered greetings, the kill line after last words). */
    private record Pending(UUID bot, Trigger trigger, Map<String, Component> tags, long at) {
    }

    private final AugmentMatch match;
    private final BotManager bots;
    private final BotChat chat;
    private final FightLog log;
    private final Random random;
    private final Map<UUID, Behaviour> lastBehaviour = new HashMap<>();
    private final Map<UUID, Integer> lastGear = new HashMap<>();
    private final Set<UUID> baseDone = new HashSet<>();
    private final Map<UUID, Map<UUID, Long>> spotted = new HashMap<>();
    private final Map<UUID, Long> nearDeath = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private boolean showdown;
    /** Lines said, for tests. */
    private final List<BotChat.Line> said = new ArrayList<>();

    BotChatFeed(AugmentMatch match, BotManager bots, BotChat chat, FightLog log, Random random) {
        this.match = match;
        this.bots = bots;
        this.chat = chat;
        this.log = log;
        this.random = random;
    }

    /** The bundled lines with the server owner's file on top (missing triggers keep the bundled ones). */
    static BotChat.Lines loadLines(JavaPlugin plugin) {
        BotChat.Lines bundled = BotChat.Lines.EMPTY;
        try (InputStream stream = plugin.getResource(FILE)) {
            if (stream != null) {
                try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    bundled = BotChat.Lines.read(YamlConfiguration.loadConfiguration(reader), BotChat.Lines.EMPTY);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not read bundled " + FILE, e);
        }
        File file = new File(plugin.getDataFolder(), FILE);
        try {
            if (!file.exists() && plugin.getResource(FILE) != null) {
                plugin.saveResource(FILE, false);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not write " + FILE, e);
        }
        if (!file.exists()) {
            return bundled;
        }
        return BotChat.Lines.read(YamlConfiguration.loadConfiguration(file), bundled);
    }

    List<BotChat.Line> said() {
        return Collections.unmodifiableList(said);
    }

    private GameSession session() {
        return match.session();
    }

    // =================================================================== events of the match

    /** The farming started: a few bots say hello, one after another. */
    void farmingStarted() {
        stagger(Trigger.GREETING, Integer.MAX_VALUE, 40);
    }

    void fightStarted() {
        stagger(Trigger.FIGHT_START, Integer.MAX_VALUE, 20);
    }

    void borderShrinking() {
        stagger(Trigger.BORDER, Integer.MAX_VALUE, 30);
    }

    private void stagger(Trigger trigger, int count, long delay) {
        List<BotController> standing = standing();
        Collections.shuffle(standing, random);
        long at = match.clock() + delay;
        for (int i = 0; i < Math.min(count, standing.size()); i++) {
            pending.add(new Pending(standing.get(i).id(), trigger, Map.of(), at));
            at += 90 + random.nextInt(80);
        }
    }

    void augmentGranted(UUID bot, AugmentInfo info) {
        say(bot, Trigger.AUGMENT, Map.of("augment", OfferMenu.name(info)));
    }

    /** {@code killer} (a bot) killed {@code victim}: a taunt, a moment after the victim's last words. */
    void killed(UUID killer, UUID victim) {
        if (!bots.isBot(killer) || bots.body(killer) == null) {
            return;
        }
        Map<String, Component> tags = Map.of("target", name(victim),
                "kills", Component.text(session().stats().of(killer).kills()));
        pending.add(new Pending(killer, Trigger.KILL, tags, match.clock() + 25 + random.nextInt(20)));
    }

    /** A bot died: its last words (naming its killer when there is one). */
    void died(UUID bot, @Nullable UUID killer) {
        say(bot, Trigger.DEATH, killer == null ? Map.of() : Map.of("target", name(killer)));
        lastBehaviour.remove(bot);
        nearDeath.remove(bot);
    }

    void finished(GameResult result) {
        boolean team = bots.teamChat() && result.winners().size() > 1;
        for (UUID winner : result.winners()) {
            if (bots.isBot(winner) && (!team || bots.body(winner) != null)) {
                say(winner, team ? Trigger.TEAM_WIN : Trigger.WIN,
                        Map.of("kills", Component.text(session().stats().of(winner).kills())));
            }
        }
    }

    /**
     * 팀전: {@code dead} (a bot or a player, still counted alive) went down, by {@code killer} if any: one of its
     * teammates mourns it (or, the last one of its team, says so) and one of the killer's teammates cheers.
     */
    void teamDeath(UUID dead, @Nullable UUID killer) {
        if (!bots.teamChat()) {
            return;
        }
        long at = match.clock() + 30 + random.nextInt(30);
        List<UUID> mates = livingBotMates(dead, null);
        int teamsLeft = 0;
        for (io.github.sx2xoox.mcpl.core.game.MatchTeam team : session().aliveTeams()) {
            boolean other = team.members().stream().anyMatch(id -> !id.equals(dead) && session().isAlive(id));
            teamsLeft += other ? 1 : 0;
        }
        if (!mates.isEmpty()) {
            UUID speaker = mates.get(random.nextInt(mates.size()));
            Map<String, Component> tags = new HashMap<>();
            tags.put("ally", name(dead));
            tags.put("alive", Component.text(Math.max(0, session().aliveCount() - 1)));
            if (killer != null && !session().sameTeam(killer, dead)) {
                tags.put("target", name(killer));
            }
            boolean last = mates.size() == 1 && teamMembersAlive(dead) == 1 && teamsLeft >= 2;
            pending.add(new Pending(speaker, last ? Trigger.TEAM_LOSING : Trigger.TEAM_ALLY_DOWN, tags, at));
        }
        if (killer != null && !session().sameTeam(killer, dead)) {
            List<UUID> cheer = livingBotMates(killer, dead);
            if (!cheer.isEmpty()) {
                UUID speaker = cheer.get(random.nextInt(cheer.size()));
                pending.add(new Pending(speaker, Trigger.TEAM_ALLY_KILL,
                        Map.of("ally", name(killer), "target", name(dead)), at + 40 + random.nextInt(40)));
            }
        }
    }

    /** Living bots on {@code of}'s team other than {@code of} (and {@code except}). */
    private List<UUID> livingBotMates(UUID of, @Nullable UUID except) {
        io.github.sx2xoox.mcpl.core.game.MatchTeam team = session().teamOf(of);
        List<UUID> out = new ArrayList<>();
        if (team == null) {
            return out;
        }
        for (UUID member : team.members()) {
            if (!member.equals(of) && !member.equals(except) && bots.isBot(member) && bots.body(member) != null) {
                out.add(member);
            }
        }
        return out;
    }

    /** Members of {@code dead}'s team still in play, not counting {@code dead}. */
    private int teamMembersAlive(UUID dead) {
        io.github.sx2xoox.mcpl.core.game.MatchTeam team = session().teamOf(dead);
        if (team == null) {
            return 0;
        }
        int n = 0;
        for (UUID member : team.members()) {
            if (!member.equals(dead) && session().isAlive(member)) {
                n++;
            }
        }
        return n;
    }

    /** A hit between participants was just recorded: maybe a third party joined a bot's fight. */
    void hit(UUID attacker, UUID victim) {
        if (!match.fight() || !bots.isBot(victim) || bots.body(victim) == null) {
            return;
        }
        UUID newcomer = log.thirdParty(victim, match.clock(), THIRD_PARTY_WINDOW);
        if (newcomer != null && newcomer.equals(attacker)) {
            say(victim, Trigger.THIRD_PARTY, Map.of("target", name(newcomer)));
        }
    }

    // =================================================================== watching the bots

    /** Twice a second. */
    void tick() {
        long now = match.clock();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending next = it.next();
            if (now >= next.at()) {
                it.remove();
                say(next.bot(), next.trigger(), next.tags());
            }
        }
        for (BotController bot : standing()) {
            watch(bot, now);
        }
        if (!showdown && match.fight() && session().aliveCount() <= 3 && session().aliveCount() >= 2
                && session().aliveCount() < bots.all().size()) {
            showdown = true;
            List<UUID> alive = new ArrayList<>(session().aliveIds());
            long at = now + 20;
            for (UUID id : alive) {
                if (!bots.isBot(id)) {
                    continue;
                }
                List<UUID> rivals = alive.stream().filter(other -> !other.equals(id)
                        && !session().sameTeam(id, other)).toList();
                Map<String, Component> tags = new HashMap<>();
                tags.put("alive", Component.text(alive.size()));
                tags.put("kills", Component.text(session().stats().of(id).kills()));
                if (!rivals.isEmpty()) {
                    tags.put("target", name(rivals.get(random.nextInt(rivals.size()))));
                }
                pending.add(new Pending(id, Trigger.SHOWDOWN, tags, at));
                at += 70 + random.nextInt(60);
            }
        }
    }

    private void watch(BotController bot, long now) {
        UUID id = bot.id();
        LivingEntity body = bots.body(id);
        if (body == null) {
            return;
        }
        int gear = gearTier(bot.loadout());
        Integer before = lastGear.put(id, gear);
        if (before != null && gear > before && gear >= 2) {
            say(id, Trigger.GEAR, Map.of("gear", Component.text(gear >= 3 ? "다이아" : "철")));
        }
        if (!match.fight() && bot.base() != null && bot.built() >= bot.base().placements().size()
                && !bot.base().placements().isEmpty() && baseDone.add(id)) {
            say(id, Trigger.BASE_DONE, Map.of());
        }
        double max = body.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null ? 20
                : body.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        double fraction = Math.min(1, body.getHealth() / Math.max(1, max));
        if (match.fight()) {
            if (fraction <= NEAR_DEATH) {
                nearDeath.putIfAbsent(id, now);
            } else {
                Long low = nearDeath.get(id);
                if (low != null && now - low > 90 * 20) {
                    nearDeath.remove(id);
                } else if (low != null && fraction >= RECOVERED && log.taken(id, now, CALM) == 0) {
                    nearDeath.remove(id);
                    say(id, Trigger.ESCAPE, Map.of());
                }
            }
        }
        watchDecision(bot, body, fraction, now);
    }

    /** What it decided: a spotted enemy, a fight it starts, falling back hurt, running from the border. */
    private void watchDecision(BotController bot, LivingEntity body, double fraction, long now) {
        UUID id = bot.id();
        BotBrain.Decision decision = bot.decision();
        if (decision == null) {
            return;
        }
        Behaviour behaviour = decision.behaviour();
        Behaviour previous = lastBehaviour.put(id, behaviour);
        if (!match.fight()) {
            return;
        }
        boolean switched = previous != behaviour;
        UUID target = decision.target();
        LivingEntity targetBody = target == null ? null : match.body(target);
        boolean sees = targetBody != null && targetBody.getWorld().equals(body.getWorld())
                && targetBody.getLocation().distanceSquared(body.getLocation()) < SPOT_RANGE * SPOT_RANGE
                && body.hasLineOfSight(targetBody);
        boolean team = bots.teamChat();
        if (switched && behaviour == Behaviour.FLEE_BORDER) {
            say(id, Trigger.BORDER, Map.of("alive", Component.text(session().aliveCount())));
        } else if (switched && team && behaviour == Behaviour.REGROUP) {
            say(id, Trigger.TEAM_REGROUP, Map.of());
        } else if (switched && FALLING_BACK.contains(behaviour) && fraction < 0.45) {
            UUID attacker = log.lastAttacker(id, now, CALM * 2);
            boolean mates = team && teamMembersAlive(id) > 0;
            say(id, mates ? Trigger.TEAM_HELP : Trigger.LOW,
                    attacker == null ? Map.of() : Map.of("target", name(attacker)));
        } else if (switched && team && ENGAGING.contains(behaviour) && target != null
                && target.equals(bots.teamFocus(id)) && !log.opponents(id, now, CALM).contains(target)) {
            say(id, Trigger.TEAM_FOCUS, Map.of("target", name(target))); // joins the fight its team started
        } else if (sees && freshSpot(id, target, now)) {
            say(id, switched && ENGAGING.contains(behaviour) ? Trigger.ENGAGE : Trigger.SPOT,
                    Map.of("target", name(target)));
        } else if (switched && ENGAGING.contains(behaviour) && target != null
                && (sees || log.opponents(id, now, CALM).contains(target))) {
            say(id, Trigger.ENGAGE, Map.of("target", name(target)));
        }
    }

    /** True the first time (in {@link #RESPOT}) the bot sees {@code target}. */
    private boolean freshSpot(UUID bot, @Nullable UUID target, long now) {
        if (target == null) {
            return false;
        }
        Map<UUID, Long> seen = spotted.computeIfAbsent(bot, any -> new HashMap<>());
        Long last = seen.put(target, now);
        return last == null || now - last > RESPOT;
    }

    /** 0 none, 1 stone/leather, 2 iron, 3 diamond: the best of its chestplate and sword. */
    static int gearTier(Gear.Loadout loadout) {
        int armor = switch (loadout.armor(1)) {
            case NONE -> 0;
            case LEATHER, CHAINMAIL -> 1;
            case IRON -> 2;
            case DIAMOND -> 3;
        };
        int sword = switch (loadout.sword()) {
            case NONE, WOOD, STONE -> 1;
            case IRON -> 2;
            case DIAMOND -> 3;
        };
        return Math.max(armor, sword);
    }

    // =================================================================== saying it

    private List<BotController> standing() {
        List<BotController> out = new ArrayList<>();
        for (BotController bot : bots.all()) {
            if (bots.body(bot.id()) != null) {
                out.add(bot);
            }
        }
        return out;
    }

    /** A participant's short name for a line: a bot's body name ("철수 봇"), a player's name. */
    private Component name(UUID id) {
        BotController bot = bots.bot(id);
        return Component.text(bot != null ? bot.name() : session().plainName(id));
    }

    private void say(UUID id, Trigger trigger, Map<String, Component> tags) {
        BotController bot = bots.bot(id);
        if (bot == null) {
            return;
        }
        Set<String> available = new HashSet<>(tags.keySet());
        available.add("me");
        BotChat.Line line = chat.consider(id, bot.personality(), trigger, available, match.clock());
        if (line == null) {
            return;
        }
        said.add(line);
        List<TagResolver> resolvers = new ArrayList<>();
        tags.forEach((key, value) -> resolvers.add(Messages.tag(key, value)));
        resolvers.add(Messages.tag("me", Component.text(bot.name())));
        Component message = match.messages().parse(line.text(), resolvers.toArray(TagResolver[]::new));
        Component full = session().name(id);
        Component speaker = Component.text(bot.name()).color(full.color()).hoverEvent(HoverEvent.showText(full));
        Component chatLine = match.messages().get("bots.chat-line", Messages.tag("name", speaker),
                Messages.tag("message", message));
        match.announce(chatLine);
        Bukkit.getConsoleSender().sendMessage(chatLine);
    }
}
