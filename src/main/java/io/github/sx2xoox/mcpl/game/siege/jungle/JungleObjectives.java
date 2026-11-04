package io.github.sx2xoox.mcpl.game.siege.jungle;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.ObjectiveKind;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.siege.arena.AttributeBonus;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeArena;
import io.github.sx2xoox.mcpl.game.siege.arena.LaneModifiers;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureKind;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureLook;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureMover;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureSounds;
import io.github.sx2xoox.mcpl.game.siege.creature.Creatures;
import io.github.sx2xoox.mcpl.game.siege.economy.Gold;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The jungle of 공성전 v2 (docs/siege-v2.md §4): each side's six objectives ({@link LaneSites#objectives}) and the
 * centre dragon ({@link LaneSites#epic}). Either team may take any of them.
 *
 * <ul>
 *     <li>Monster camps (젤리, 쥐, 곰인형, 토끼, 드래곤) stand still until a player in play hits them, then fight the
 *     nearest player (the rabbit only flees); pulled beyond their leash, or left alone for a while, they walk home with
 *     full health. The last hit takes the camp for that player's side: score, gold for the last hitter (the dragon: the
 *     whole team), experience split between the side's players nearby (the dragon: each member), and the buff (곰인형:
 *     attack, 토끼: cooldown, to the last hitter and allies nearby; 드래곤: damage for the whole team and stronger
 *     minions).</li>
 *     <li>Capture points (사탕 샘물, 망원경 탑): players of one side inside fill the capture; both sides inside pause it
 *     (contested); the other side's progress drains first; an empty point slowly falls back. Captured: score, gold and
 *     experience for the capturers, and the effect (the team healed at once and regenerating / the enemies on that half
 *     of the map glowing).</li>
 * </ul>
 *
 * A label over every site shows its name, state and respawn timer; monsters show their health. Buffs end on death and
 * at the end of the round ({@link #clear()}); everything spawned is tracked by the match.
 */
public final class JungleObjectives implements Listener {

    /** Blocks a player's feet may be above or below a capture point and still count as inside. */
    static final double HEIGHT_RANGE = 3.5;
    private static final TextColor MONSTER = TextColor.color(0xFFC857);

    private final SiegeArena arena;
    private final JungleSettings settings;
    private final CreatureLook look;
    private final CreatureMover mover;
    private final Gold gold;
    private final LaneModifiers modifiers;
    private final @Nullable MinionWaves minions;
    private final Messages messages;
    private final List<Site> sites = new ArrayList<>();
    /** Player → buff kind → the tick it ends. */
    private final Map<UUID, EnumMap<ObjectiveKind, Long>> buffs = new HashMap<>();
    /** Side → telescope reveal: the end tick and the half of the map (−1 = −x, 1 = +x, 0 = all). */
    private final Map<LaneSites.Side, long[]> reveals = new EnumMap<>(LaneSites.Side.class);
    /** Chunks held loaded around the sites while a round runs, as {@code x, z} pairs. */
    private final List<int[]> heldChunks = new ArrayList<>();
    private boolean striking;
    private long now;

    public JungleObjectives(SiegeArena arena, JungleSettings settings, CreatureLook look, CreatureMover mover,
            Gold gold, LaneModifiers modifiers, @Nullable MinionWaves minions) {
        this.arena = arena;
        this.settings = settings;
        this.look = look;
        this.mover = mover;
        this.gold = gold;
        this.modifiers = modifiers;
        this.minions = minions;
        this.messages = arena.messages();
        LaneSites map = arena.sites();
        for (LaneSites.Side side : LaneSites.Side.values()) {
            for (LaneSites.Objective objective : map.objectives(side)) {
                if (objective.kind() != ObjectiveKind.DRAGON) {
                    sites.add(new Site(objective, side));
                }
            }
        }
        sites.add(new Site(map.epic(), null));
    }

    /** One objective of the map. */
    public final class Site {
        private final LaneSites.Objective objective;
        private final LaneSites.@Nullable Side home;
        private final Location at;
        private final Map<Mob, Location> mobs = new LinkedHashMap<>();
        /** The last player who hit each monster (by monster id). */
        private final Map<UUID, UUID> hitters = new HashMap<>();
        private final Map<Mob, @Nullable LivingEntity> targets = new HashMap<>();
        private final Map<Mob, Long> nextAttack = new HashMap<>();
        private boolean up;
        private long upAt;
        private boolean aggro;
        private long lastHit;
        private @Nullable UUID lastHitter;
        private LaneSites.@Nullable Side capturer;
        private int progress;
        private boolean contested;
        private boolean healthDirty;
        private @Nullable TextDisplay label;

        private Site(LaneSites.Objective objective, LaneSites.@Nullable Side home) {
            this.objective = objective;
            this.home = home;
            this.at = arena.at(objective.x(), objective.y(), objective.z());
        }

        public LaneSites.Objective objective() {
            return objective;
        }

        public ObjectiveKind kind() {
            return objective.kind();
        }

        /** The side whose half the site is in (null for the dragon). */
        public LaneSites.@Nullable Side home() {
            return home;
        }

        public Location at() {
            return at.clone();
        }

        public boolean isUp() {
            return up;
        }

        public boolean isFighting() {
            return aggro;
        }

        /** Tick the site comes (back) while down. */
        public long upAt() {
            return upAt;
        }

        public List<Mob> mobs() {
            return List.copyOf(mobs.keySet());
        }

        public boolean isCapturePoint() {
            return CreatureKind.of(kind()) == null;
        }

        /** The side whose progress fills a capture point, or null. */
        public LaneSites.@Nullable Side capturer() {
            return capturer;
        }

        /** Capture progress 0..1. */
        public double fraction() {
            return Math.min(1.0, (double) progress / kindSettings().captureTicks());
        }

        public boolean isContested() {
            return contested;
        }

        public @Nullable TextDisplay label() {
            return label;
        }

        JungleSettings.Kind kindSettings() {
            return settings.kind(kind());
        }

        boolean contains(Location location) {
            if (!location.getWorld().equals(at.getWorld()) || Math.abs(location.getY() - at.getY()) > HEIGHT_RANGE) {
                return false;
            }
            double radius = Math.max(1.5, objective.radius());
            return Creatures.flatDistanceSquared(location, at) <= radius * radius;
        }
    }

    // ------------------------------------------------------------------ flow

    /** The fight of a round begins: every site waits for its first appearance. */
    public void begin() {
        clear();
        if (!settings.enabled()) {
            return;
        }
        holdChunks();
        for (Site site : sites) {
            site.upAt = site.kindSettings().firstTicks();
            label(site);
        }
    }

    /** Keeps the chunks around every site (its leash) loaded: monsters and labels are not persistent. */
    private void holdChunks() {
        Set<Long> seen = new HashSet<>();
        for (Site site : sites) {
            int reach = (int) Math.ceil(Math.max(site.objective.radius(), site.kindSettings().leash())) + 2;
            int x = site.at.getBlockX();
            int z = site.at.getBlockZ();
            for (int cx = (x - reach) >> 4; cx <= (x + reach) >> 4; cx++) {
                for (int cz = (z - reach) >> 4; cz <= (z + reach) >> 4; cz++) {
                    if (seen.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL))
                            && arena.world().addPluginChunkTicket(cx, cz, arena.plugin())) {
                        heldChunks.add(new int[] {cx, cz});
                    }
                }
            }
        }
    }

    /** Every tick of the fight. */
    public void tick() {
        now++;
        if (!settings.enabled()) {
            return;
        }
        for (Site site : sites) {
            if (!site.up) {
                if (now >= site.upAt) {
                    appear(site);
                }
            } else if (site.isCapturePoint()) {
                capture(site);
            } else {
                for (Mob mob : site.mobs.keySet()) {
                    mover.tick(mob);
                    look.tick(mob, CreatureKind.of(site.kind()));
                }
                if (now % 5 == 0) {
                    guard(site);
                }
                if (site.healthDirty) {
                    site.healthDirty = false;
                    for (Mob mob : site.mobs.keySet()) {
                        Creatures.showHealth(arena, mob, monsterName(site), MONSTER);
                    }
                }
            }
            if (now % 10 == 0) {
                label(site);
            }
            if (site.up && now % 20 == 0) {
                JungleSounds.ambient(arena, site.kind(), site.at, now / 20);
            }
        }
        if (now % 20 == 0) {
            second();
        }
    }

    /** The round is over: monsters, labels, buffs and reveals are gone. */
    public void clear() {
        for (Site site : sites) {
            removeMobs(site);
            site.up = false;
            site.aggro = false;
            site.capturer = null;
            site.progress = 0;
            site.contested = false;
            if (site.label != null) {
                site.label.remove();
                arena.untrack(site.label);
                site.label = null;
            }
        }
        for (UUID player : new ArrayList<>(buffs.keySet())) {
            endBuffs(player);
        }
        buffs.clear();
        reveals.clear();
        for (int[] chunk : heldChunks) {
            arena.world().removePluginChunkTicket(chunk[0], chunk[1], arena.plugin());
        }
        heldChunks.clear();
        now = 0;
    }

    private void appear(Site site) {
        site.up = true;
        site.aggro = false;
        site.capturer = null;
        site.progress = 0;
        site.contested = false;
        CreatureKind creature = CreatureKind.of(site.kind());
        if (creature != null) {
            spawnMobs(site, creature);
            if (site.mobs.isEmpty()) {
                site.up = false;
                site.upAt = now + site.kindSettings().respawnTicks();
                return;
            }
        }
        site.at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, site.at.clone().add(0, 1, 0), 20, 1, 0.6, 1, 0);
        JungleSounds.appear(arena, site.kind(), site.at);
        if (site.kind() == ObjectiveKind.DRAGON) {
            arena.broadcast(messages.prefixed("jungle.dragon-spawned", Messages.tag("name", siteName(site))));
        }
        label(site);
    }

    private void spawnMobs(Site site, CreatureKind creature) {
        removeMobs(site);
        JungleSettings.Kind kind = site.kindSettings();
        int teamSize = Math.max(arena.members(LaneSites.Side.BLUE).size(), arena.members(LaneSites.Side.RED).size());
        double health = kind.health() * (1 + settings.healthPerExtraPlayer() * Math.max(0, teamSize - 1));
        int count = kind.count();
        double spread = count > 1 ? Math.clamp(site.objective.radius() - 1, 0, 2.0) : 0;
        Component name = monsterName(site);
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2 * i / count;
            Location home = site.at.clone().add(Math.cos(angle) * spread, 0, Math.sin(angle) * spread);
            home.setYaw((float) Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle))));
            try {
                Mob mob = look.spawn(creature, null, home, body -> Creatures.prepare(body, name, health,
                        kind.scale(), kind.leash() + 4));
                arena.track(mob);
                site.mobs.put(mob, home);
            } catch (RuntimeException e) {
                arena.plugin().getLogger().log(Level.WARNING, "Could not spawn the jungle site "
                        + site.objective.id(), e);
            }
        }
        site.healthDirty = true;
    }

    // ------------------------------------------------------------------ monsters

    /** Leash, reset and the fight of a camp's monsters. */
    private void guard(Site site) {
        if (!site.aggro) {
            return;
        }
        JungleSettings.Kind kind = site.kindSettings();
        double leash = kind.leash();
        for (Mob mob : site.mobs.keySet()) {
            if (!mob.getWorld().equals(site.at.getWorld())
                    || Creatures.flatDistanceSquared(mob.getLocation(), site.at) > leash * leash) {
                reset(site);
                return;
            }
        }
        if (now - site.lastHit >= settings.resetTicks()) {
            reset(site);
            return;
        }
        for (Mob mob : site.mobs.keySet()) {
            LivingEntity target = site.targets.get(mob);
            if (target == null || !fighter(site, target)) {
                target = nearestFighter(site, mob.getLocation());
                site.targets.put(mob, target);
            }
            if (target == null) {
                mover.stop(mob);
                continue;
            }
            if (kind.range() <= 0) {
                flee(site, mob, target);
                continue;
            }
            if (Creatures.flatDistanceSquared(mob.getLocation(), target.getLocation()) <= kind.range() * kind.range()) {
                mover.stop(mob);
                Creatures.face(mob, target.getLocation());
                if (now >= site.nextAttack.getOrDefault(mob, 0L)) {
                    site.nextAttack.put(mob, now + kind.attackTicks());
                    strike(site, mob, target, kind.damage());
                }
            } else {
                mover.moveTo(mob, target.getLocation(), kind.speed());
            }
        }
    }

    /** The clock rabbit runs away from its attacker, staying inside its leash. */
    private void flee(Site site, Mob mob, LivingEntity from) {
        Vector away = mob.getLocation().toVector().subtract(from.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1.0E-4) {
            away = new Vector(1, 0, 0);
        }
        Location goal = mob.getLocation().add(away.normalize().multiply(4));
        double room = Math.max(1, site.kindSettings().leash() - 3);
        Vector fromHome = goal.toVector().subtract(site.at.toVector()).setY(0);
        if (fromHome.lengthSquared() > room * room) {
            // Cornered at the edge: dash across home to the other side instead.
            goal = site.at.clone().subtract(fromHome.normalize().multiply(room * 0.6));
        }
        goal.setY(site.at.getY());
        mover.moveTo(mob, goal, site.kindSettings().speed() * 1.2);
    }

    private void strike(Site site, Mob mob, LivingEntity target, double damage) {
        striking = true;
        try {
            Creatures.strike(arena, mob, target, damage, null, false);
        } finally {
            striking = false;
        }
        attackMotion(site, mob);
    }

    /** The monster's attack motion (pack players) and sound (everyone). */
    private void attackMotion(Site site, Mob mob) {
        look.attack(mob);
        CreatureKind creature = CreatureKind.of(site.kind());
        if (creature != null) {
            CreatureSounds.attack(arena, creature, mob.getLocation());
        }
    }

    /** Back home, full health, standing still again. */
    public void reset(Site site) {
        site.aggro = false;
        site.hitters.clear();
        site.targets.clear();
        site.lastHitter = null;
        for (Map.Entry<Mob, Location> entry : site.mobs.entrySet()) {
            Mob mob = entry.getKey();
            mover.stop(mob);
            mob.setFireTicks(0);
            mob.teleport(entry.getValue());
            Creatures.heal(mob);
        }
        site.healthDirty = true;
        site.at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, site.at.clone().add(0, 1, 0), 12, 1, 0.6, 1, 0);
    }

    /** A player in play or a 봇전 bot in play, inside the camp's leash. */
    private boolean fighter(Site site, LivingEntity entity) {
        boolean inPlay;
        if (entity instanceof Player player) {
            inPlay = arena.canFight(player);
        } else {
            UUID bot = arena.fighterOf(entity);
            LaneSites.Side side = bot == null ? null : arena.side(bot);
            inPlay = side != null && arena.extraFighters(side).contains(entity);
        }
        return inPlay && entity.getWorld().equals(site.at.getWorld())
                && Creatures.flatDistanceSquared(entity.getLocation(), site.at)
                <= site.kindSettings().leash() * site.kindSettings().leash();
    }

    private @Nullable LivingEntity nearestFighter(Site site, Location from) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LaneSites.Side side : LaneSites.Side.values()) {
            List<LivingEntity> fighters = new ArrayList<>(arena.fighters(side));
            fighters.addAll(arena.extraFighters(side));
            for (LivingEntity fighter : fighters) {
                if (!fighter(site, fighter)) {
                    continue;
                }
                double distance = fighter.getLocation().distanceSquared(from);
                if (distance < bestDistance) {
                    best = fighter;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private void removeMobs(Site site) {
        for (Mob mob : site.mobs.keySet()) {
            mob.remove();
            forget(mob);
        }
        site.mobs.clear();
        site.hitters.clear();
        site.targets.clear();
        site.nextAttack.clear();
    }

    private void forget(Mob mob) {
        arena.untrack(mob);
        arena.removeOverheadBar(mob);
        look.removed(mob);
    }

    /** A monster is gone; the camp is cleared when it was the last. */
    private void died(Site site, Mob mob, @Nullable UUID credit) {
        if (site.mobs.remove(mob) == null) {
            return;
        }
        forget(mob);
        site.targets.remove(mob);
        site.nextAttack.remove(mob);
        UUID last = credit != null ? credit : site.hitters.get(mob.getUniqueId());
        if (last == null) {
            last = site.lastHitter;
        }
        site.hitters.remove(mob.getUniqueId());
        if (site.mobs.isEmpty()) {
            taken(site, last, last == null ? List.of() : sharers(site, last));
        }
    }

    // ------------------------------------------------------------------ capture points

    private void capture(Site site) {
        boolean blue = false;
        boolean red = false;
        List<UUID> inside = new ArrayList<>();
        for (LaneSites.Side side : LaneSites.Side.values()) {
            for (Player player : arena.fighters(side)) {
                if (site.contains(player.getLocation())) {
                    inside.add(player.getUniqueId());
                    if (side == LaneSites.Side.BLUE) {
                        blue = true;
                    } else {
                        red = true;
                    }
                }
            }
        }
        site.contested = blue && red;
        if (site.contested) {
            return;
        }
        if (!blue && !red) {
            // Nobody there: the progress slowly falls back.
            if (site.progress > 0 && --site.progress == 0) {
                site.capturer = null;
            }
            return;
        }
        LaneSites.Side side = blue ? LaneSites.Side.BLUE : LaneSites.Side.RED;
        if (site.capturer != null && site.capturer != side) {
            // The other side's progress drains first, twice as fast.
            site.progress -= 2;
            if (site.progress <= 0) {
                site.progress = 0;
                site.capturer = null;
            }
            return;
        }
        site.capturer = side;
        site.progress++;
        if (site.progress % 20 == 0) {
            JungleSounds.capturing(arena, site.kind(), site.at);
        }
        if (site.progress >= site.kindSettings().captureTicks()) {
            List<UUID> capturers = new ArrayList<>();
            for (UUID id : inside) {
                if (arena.side(id) == side) {
                    capturers.add(id);
                }
            }
            taken(site, capturers.getFirst(), capturers);
        }
    }

    // ------------------------------------------------------------------ rewards

    /**
     * The site was taken by {@code credit}'s side (null = nobody, e.g. a monster that fell out of the world): score,
     * gold, experience, the buff or effect, and the respawn timer.
     */
    private void taken(Site site, @Nullable UUID credit, List<UUID> sharers) {
        site.up = false;
        site.aggro = false;
        site.hitters.clear();
        site.lastHitter = null;
        site.capturer = null;
        site.progress = 0;
        site.contested = false;
        JungleSettings.Kind kind = site.kindSettings();
        site.upAt = now + kind.respawnTicks();
        LaneSites.Side side = credit == null ? null : arena.side(credit);
        label(site);
        if (side == null) {
            return;
        }
        JungleSounds.taken(arena, site.kind(), site.at);
        arena.addScore(side, kind.score());
        Gold.Settings money = gold.settings();
        switch (site.kind()) {
            case JELLY, MICE -> gold.add(credit, money.small(), "jungle");
            case TEDDY, RABBIT -> gold.add(credit, money.buff(), "jungle-buff");
            case FOUNTAIN, TELESCOPE -> {
                for (UUID id : sharers) {
                    gold.add(id, money.capture(), "capture");
                }
            }
            case DRAGON -> gold.team(side, money.dragon(), "dragon");
        }
        if (site.kind() == ObjectiveKind.DRAGON) {
            for (UUID member : arena.members(side)) {
                arena.addXp(member, kind.xp());
            }
        } else if (!sharers.isEmpty()) {
            int each = (kind.xp() + sharers.size() - 1) / sharers.size();
            for (UUID id : sharers) {
                arena.addXp(id, each);
            }
        }
        switch (site.kind()) {
            case TEDDY, RABBIT -> {
                for (UUID id : sharers) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null && arena.canFight(player)) {
                        buff(player, site.kind(), kind.buffTicks());
                    }
                }
            }
            case DRAGON -> {
                for (Player player : arena.fighters(side)) {
                    buff(player, ObjectiveKind.DRAGON, kind.buffTicks());
                }
                if (minions != null) {
                    minions.empower(side, kind.buffTicks());
                }
            }
            case FOUNTAIN -> fountain(side);
            case TELESCOPE -> reveals.put(side, new long[] {now + settings.telescopeRevealTicks(), half(site)});
            default -> {
            }
        }
        Component team = messages.get("jungle.side." + side.name().toLowerCase(Locale.ROOT));
        Component who = credit == null ? Component.empty() : Component.text(nameOf(credit));
        switch (site.kind()) {
            case JELLY, MICE -> {
                for (UUID id : sharers) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null) {
                        messages.send(player, "jungle.small-cleared", Messages.tag("name", siteName(site)));
                    }
                }
            }
            default -> arena.broadcast(messages.prefixed("jungle.taken." + key(site.kind()),
                    Messages.tag("team", team), Messages.tag("player", who), Messages.tag("name", siteName(site))));
        }
    }

    /** The killing side's players in play near the site, the credited player always among them. */
    private List<UUID> sharers(Site site, UUID credit) {
        List<UUID> out = new ArrayList<>();
        out.add(credit);
        LaneSites.Side side = arena.side(credit);
        if (side == null) {
            return out;
        }
        double radius = settings.shareRadius();
        for (Player player : arena.fighters(side)) {
            if (!player.getUniqueId().equals(credit) && player.getWorld().equals(site.at.getWorld())
                    && player.getLocation().distanceSquared(site.at) <= radius * radius) {
                out.add(player.getUniqueId());
            }
        }
        return out;
    }

    private void fountain(LaneSites.Side side) {
        for (Player player : arena.fighters(side)) {
            player.setHealth(Math.min(Creatures.maxHealth(player), player.getHealth() + settings.fountainHeal()));
            if (settings.fountainRegenTicks() > 0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, settings.fountainRegenTicks(),
                        0, false, true, true));
            }
            player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 2, 0), 4, 0.4, 0.3, 0.4, 0);
        }
    }

    /** −1 if the site is on the −x half of the map, 1 on the +x half, 0 in the middle. */
    private int half(Site site) {
        return Integer.signum(site.objective.x());
    }

    // ------------------------------------------------------------------ buffs and reveals

    private void buff(Player player, ObjectiveKind kind, int ticks) {
        if (ticks <= 0) {
            return;
        }
        UUID id = player.getUniqueId();
        buffs.computeIfAbsent(id, key -> new EnumMap<>(ObjectiveKind.class)).put(kind, now + ticks);
        switch (kind) {
            case TEDDY -> {
                modifiers.set(id, source(kind), LaneModifiers.Stat.SKILL_DAMAGE, settings.teddySkill());
                AttributeBonus.set(player, Attribute.ATTACK_DAMAGE, buffKey(kind), settings.teddyAttack(),
                        AttributeModifier.Operation.ADD_NUMBER);
            }
            case RABBIT -> modifiers.set(id, source(kind), LaneModifiers.Stat.COOLDOWN, settings.rabbitCooldown());
            case DRAGON -> {
                modifiers.set(id, source(kind), LaneModifiers.Stat.SKILL_DAMAGE, settings.dragonSkill());
                AttributeBonus.set(player, Attribute.ATTACK_DAMAGE, buffKey(kind), settings.dragonAttack(),
                        AttributeModifier.Operation.ADD_SCALAR);
            }
            default -> {
            }
        }
        player.sendActionBar(messages.get("jungle.buff." + key(kind), Messages.tag("seconds", ticks / 20)));
    }

    private void endBuff(UUID id, ObjectiveKind kind) {
        modifiers.remove(id, source(kind));
        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            AttributeBonus.remove(player, Attribute.ATTACK_DAMAGE, buffKey(kind));
        }
    }

    /** Ends every buff of a player (death, round end). */
    public void endBuffs(UUID player) {
        EnumMap<ObjectiveKind, Long> held = buffs.remove(player);
        if (held != null) {
            for (ObjectiveKind kind : held.keySet()) {
                endBuff(player, kind);
            }
        }
    }

    /** Whether a player holds the buff of {@code kind} (TEDDY, RABBIT, DRAGON). */
    public boolean hasBuff(UUID player, ObjectiveKind kind) {
        EnumMap<ObjectiveKind, Long> held = buffs.get(player);
        Long until = held == null ? null : held.get(kind);
        return until != null && until > now;
    }

    /** Once a second: expired buffs end, telescopes reveal. */
    private void second() {
        for (Map.Entry<UUID, EnumMap<ObjectiveKind, Long>> entry : new ArrayList<>(buffs.entrySet())) {
            UUID id = entry.getKey();
            Player player = Bukkit.getPlayer(id);
            boolean gone = player == null || !arena.canFight(player);
            for (Map.Entry<ObjectiveKind, Long> buff : new ArrayList<>(entry.getValue().entrySet())) {
                if (gone || buff.getValue() <= now) {
                    entry.getValue().remove(buff.getKey());
                    endBuff(id, buff.getKey());
                }
            }
            if (entry.getValue().isEmpty()) {
                buffs.remove(id);
            }
        }
        reveals.entrySet().removeIf(entry -> entry.getValue()[0] <= now);
        for (Map.Entry<LaneSites.Side, long[]> entry : reveals.entrySet()) {
            int half = (int) entry.getValue()[1];
            double centre = arena.at(0, 0, 0).getX();
            for (Player enemy : arena.fighters(MinionWaves.enemy(entry.getKey()))) {
                double x = enemy.getLocation().getX() - centre;
                if (half == 0 || Math.signum(x) == half) {
                    enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 30, 0, false, false, false));
                }
            }
        }
    }

    /** Whether {@code side} has a telescope reveal running. */
    public boolean isRevealing(LaneSites.Side side) {
        long[] reveal = reveals.get(side);
        return reveal != null && reveal[0] > now;
    }

    private static String source(ObjectiveKind kind) {
        return "buff:" + kind.name().toLowerCase(Locale.ROOT);
    }

    private NamespacedKey buffKey(ObjectiveKind kind) {
        return new NamespacedKey(arena.plugin(), "siege_buff_" + kind.name().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (siteOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplit(SlimeSplitEvent event) {
        if (siteOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Monsters hurt only through their own hits; they are hurt only by players in play (abilities included) and the
     * burns or poisons those cause, never by falls, walls or minions.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (siteOf(event.getDamageSource().getCausingEntity()) != null && !striking) {
            event.setCancelled(true);
            return;
        }
        Site site = siteOf(event.getEntity());
        if (site == null) {
            return;
        }
        if (!arena.isFighting()) {
            event.setCancelled(true);
            return;
        }
        Player attacker = arena.attacker(event);
        UUID bot = attacker == null ? arena.fighterAttacker(event) : null;
        if (bot != null) {
            // A 봇전 bot in play may fight any camp, like a player.
            if (arena.side(bot) == null) {
                event.setCancelled(true);
            }
            return;
        }
        if (attacker == null) {
            switch (event.getCause()) {
                case FIRE_TICK, POISON, WITHER, KILL, VOID -> {
                }
                default -> event.setCancelled(true);
            }
            return;
        }
        if (!arena.canFight(attacker)) {
            event.setCancelled(true);
        }
    }

    /** A landed hit: the camp fights back and remembers the hitter for the kill. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        Site site = siteOf(event.getEntity());
        if (site == null) {
            return;
        }
        site.healthDirty = true;
        Mob hurt = (Mob) event.getEntity();
        arena.damageNumber(hurt, event.getFinalDamage());
        JungleSounds.hurt(arena, site.kind(), hurt.getLocation());
        look.hurt(hurt);
        if (site.kindSettings().range() <= 0) {
            // the clock rabbit never strikes: its "attack" is a startled hop when hit
            attackMotion(site, hurt);
        }
        Player attacker = arena.attacker(event);
        LivingEntity hitter;
        UUID hitterId;
        if (attacker != null && arena.canFight(attacker)) {
            hitter = attacker;
            hitterId = attacker.getUniqueId();
        } else {
            UUID bot = attacker == null ? arena.fighterAttacker(event) : null;
            Entity body = event.getDamageSource().getCausingEntity();
            if (bot == null || !(body instanceof LivingEntity living) || arena.side(bot) == null) {
                return;
            }
            hitter = living;
            hitterId = bot;
        }
        if (!site.aggro) {
            JungleSounds.aggro(arena, site.kind(), site.at);
        }
        site.aggro = true;
        site.lastHit = now;
        site.lastHitter = hitterId;
        site.hitters.put(event.getEntity().getUniqueId(), hitterId);
        for (Mob mob : site.mobs.keySet()) {
            LivingEntity current = site.targets.get(mob);
            if (current == null || !current.isValid() || current.isDead()) {
                site.targets.put(mob, hitter);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        Site site = siteOf(mob);
        if (site == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        died(site, mob, site.hitters.get(mob.getUniqueId()));
    }

    /** A buff does not survive its holder's death. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        endBuffs(event.getEntity().getUniqueId());
    }

    // ------------------------------------------------------------------ queries

    /** The site a live monster belongs to, or null. */
    public @Nullable Site siteOf(@Nullable Entity entity) {
        if (!(entity instanceof Mob mob)) {
            return null;
        }
        for (Site site : sites) {
            if (site.mobs.containsKey(mob)) {
                return site;
            }
        }
        return null;
    }

    public List<Site> sites() {
        return List.copyOf(sites);
    }

    /** The site of a kind in a side's half (null side: the dragon), or null. */
    public @Nullable Site site(LaneSites.@Nullable Side home, ObjectiveKind kind) {
        for (Site site : sites) {
            if (site.home == home && site.kind() == kind) {
                return site;
            }
        }
        return null;
    }

    /** Live monsters (ability targets). */
    public List<LivingEntity> targets() {
        List<LivingEntity> out = new ArrayList<>();
        for (Site site : sites) {
            for (Mob mob : site.mobs.keySet()) {
                if (mob.isValid() && !mob.isDead()) {
                    out.add(mob);
                }
            }
        }
        return out;
    }

    /** Monsters are enemies of every player in play during the fight. */
    public boolean isEnemy(Player owner, Entity entity) {
        return arena.isFighting() && siteOf(entity) != null && entity.isValid() && arena.canFight(owner);
    }

    /** Makes a site appear now (tests and admins). */
    public void spawnNow(Site site) {
        site.upAt = now;
        appear(site);
    }

    public long now() {
        return now;
    }

    // ------------------------------------------------------------------ looks

    private Component siteName(Site site) {
        return messages.get("jungle.name." + key(site.kind()));
    }

    private Component monsterName(Site site) {
        return messages.get("jungle.mob." + key(site.kind()));
    }

    private static String key(ObjectiveKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private String nameOf(UUID player) {
        Player online = Bukkit.getPlayer(player);
        return online == null ? "?" : online.getName();
    }

    /** The label's second line, or null when there is nothing worth saying (an idle camp, an empty point). */
    private @Nullable Component status(Site site) {
        if (!site.up) {
            return messages.get("jungle.status-down",
                    Messages.tag("time", Format.clock((Math.max(0, site.upAt - now) + 19) / 20)));
        }
        if (site.isCapturePoint()) {
            if (site.contested) {
                return messages.get("jungle.status-contested");
            }
            if (site.capturer == null) {
                return null;
            }
            int filled = (int) Math.round(site.fraction() * 10);
            TextColor colour = MinionWaves.colour(site.capturer);
            return Component.text("|".repeat(filled), colour)
                    .append(Component.text("|".repeat(10 - filled), NamedTextColor.DARK_GRAY));
        }
        return site.aggro ? messages.get("jungle.status-fighting") : null;
    }

    /** Spawns or refreshes the label over a site: its name and its state or respawn timer. */
    private void label(Site site) {
        Component status = status(site);
        Component text = status == null ? siteName(site)
                : Component.join(JoinConfiguration.newlines(), siteName(site), status);
        try {
            TextDisplay label = site.label;
            if (label == null || !label.isValid()) {
                double height = site.isCapturePoint() ? 2.5 : 1.6 + 2.0 * site.kindSettings().scale();
                label = site.at.getWorld().spawn(site.at.clone().add(0, height, 0), TextDisplay.class, display -> {
                    display.setPersistent(false);
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setShadowed(true);
                    display.setBrightness(new Display.Brightness(15, 15));
                });
                arena.track(label);
                site.label = label;
            }
            label.text(text);
        } catch (RuntimeException e) {
            arena.plugin().getLogger().log(Level.FINE, "Could not label jungle site " + site.objective.id(), e);
        }
    }

    /**
     * The sidebar line about the dragon and the buff monsters: up ones marked, the rest with their respawn time, or
     * null before the fight.
     */
    public @Nullable Component hudLine() {
        if (!settings.enabled() || sites.isEmpty()) {
            return null;
        }
        List<Component> parts = new ArrayList<>();
        for (ObjectiveKind kind : List.of(ObjectiveKind.DRAGON, ObjectiveKind.TEDDY, ObjectiveKind.RABBIT)) {
            long soonest = Long.MAX_VALUE;
            boolean up = false;
            for (Site site : sites) {
                if (site.kind() == kind) {
                    up |= site.up;
                    soonest = Math.min(soonest, site.upAt);
                }
            }
            if (soonest == Long.MAX_VALUE) {
                continue;
            }
            Component name = messages.get("jungle.short." + key(kind));
            parts.add(up ? messages.get("jungle.hud-up", Messages.tag("name", name))
                    : messages.get("jungle.hud-down", Messages.tag("name", name),
                            Messages.tag("time", Format.clock((Math.max(0, soonest - now) + 19) / 20))));
        }
        return parts.isEmpty() ? null : Component.join(JoinConfiguration.separator(Component.text(" · ",
                NamedTextColor.DARK_GRAY)), parts);
    }
}
