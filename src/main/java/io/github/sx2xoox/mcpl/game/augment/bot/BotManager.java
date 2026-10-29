package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.combat.CombatTracker;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The bots of one 봇전 match: registers them as participants of the session ({@link GameSession#addBot}), spawns
 * their bodies when the world is ready, runs their {@link BotController}s (decisions spread over ticks), applies the
 * rules to them (the PvP gate, teams, shields, knockback, kill credit) and removes everything at the end.
 */
public final class BotManager implements Listener {

    private record Hit(UUID other, long tick) {
    }

    /** A hit somewhere: the clash of a fight others can hear. */
    private record Noise(Location at, long tick, UUID attacker, UUID victim) {
    }

    /** How long a fight's noise can be followed. */
    static final int NOISE_TICKS = 200;

    /** How long a hit counts as "fighting" for third-party decisions. */
    static final int FIGHT_TICKS = 100;

    private final BotHost host;
    private final GameSession session;
    private final BotSettings settings;
    private final List<BotDifficulty> lineup;
    private final boolean tagged;
    private boolean keepLoaded;
    private final Random random;
    private final String namespace;
    private final Map<UUID, BotController> bots = new LinkedHashMap<>();
    /** Body and legs entity ids → bot id. */
    private final Map<UUID, UUID> byEntity = new HashMap<>();
    private final Map<UUID, Hit> lastHurtBy = new HashMap<>();
    private final Map<UUID, Hit> lastHit = new HashMap<>();
    private final List<Noise> noises = new ArrayList<>();
    /** Chunks held loaded (and ticking) around the bots, as {@code x, z} keys. */
    private final Set<Long> held = new HashSet<>();
    private @Nullable ChunkTickets tickets;
    /** When each bot last took world border damage (ticks of {@link BotHost#clock()}). */
    private final Map<UUID, Long> borderHurt = new HashMap<>();
    private int forwarding;
    private boolean disposed;
    private final BotTeams teams;
    /** Bots killed by the world border (measurement: should stay 0). */
    private int borderDeaths;
    private final BotStats stats = new BotStats();
    private final MotionTracker motion = new MotionTracker();

    public BotManager(BotHost host, BotSettings settings, BotDifficulty difficulty) {
        this(host, settings, List.of(difficulty), false, false);
    }

    /**
     * @param lineup     the difficulty of each bot, in order (its size is the number of bots)
     * @param tagged     show the difficulty next to every bot's name ("철수 봇 [어려움]")
     * @param keepLoaded hold the chunks around every bot loaded and ticking, so bots far from every player keep
     *                   acting (without it they freeze in unloaded chunks)
     */
    public BotManager(BotHost host, BotSettings settings, List<BotDifficulty> lineup, boolean tagged,
                      boolean keepLoaded) {
        if (lineup.isEmpty()) {
            throw new IllegalArgumentException("no bots");
        }
        this.host = host;
        this.session = host.session();
        this.settings = settings;
        this.lineup = List.copyOf(lineup);
        this.tagged = tagged;
        this.keepLoaded = keepLoaded;
        this.random = session.random();
        this.namespace = session.hub().key("bot").getNamespace();
        this.teams = new BotTeams(this, session, settings.team());
    }

    /** The shared minds of the teams (봇전 팀전; inactive in free-for-all). */
    BotTeams teams() {
        return teams;
    }

    /** 팀전: team lines in bot chat ({@code bots.team.team-chat}) and a team match. */
    public boolean teamChat() {
        return teams.active() && settings.team().teamChat();
    }

    /** 팀전: the enemy {@code bot}'s team is hitting now (focus fire), or null. */
    public @Nullable UUID teamFocus(UUID bot) {
        return teams.active() ? teams.focus(bot, host.clock()) : null;
    }

    /** PvP is on. */
    boolean fightOn() {
        return host.fight();
    }

    /** 팀전 measurement: share of bot hits on an enemy a teammate hit just before (focus fire). */
    public double focusShare() {
        return teams.focusShare();
    }

    /** 팀전 measurement: one summary line (focus fire, cohesion, behaviours, team bases), or null outside 팀전. */
    public @Nullable String teamReport() {
        return teams.active() ? teams.report() + " borderDeaths=" + borderDeaths : null;
    }

    /** 팀전 measurement: bot hits on enemies so far. */
    public long botHits() {
        return teams.botHits();
    }

    /** 팀전 measurement: average distance of a living bot to its nearest teammate in the fight (NaN: none). */
    public double averageMateDistance() {
        return teams.averageMateDistance();
    }

    /** The difficulty of the first bot (all of them unless the lineup is mixed). */
    public BotDifficulty difficulty() {
        return lineup.getFirst();
    }

    /** The difficulty of every bot, in order. */
    public List<BotDifficulty> lineup() {
        return lineup;
    }

    GameSession session() {
        return session;
    }

    // =================================================================== lifecycle

    /** Adds the bots of the lineup (capped) as participants of the running session; their ids in order. */
    public List<UUID> register() {
        int wanted = Math.max(0, Math.min(Math.min(settings.max(), BotSettings.HARD_MAX), lineup.size()));
        List<@Nullable BotPersonality> personalities = BotPersonality.lineup(settings.personalities(), wanted, random);
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < wanted; i++) {
            UUID id = UUID.randomUUID();
            BotDifficulty difficulty = lineup.get(i);
            BotPersonality personality = personalities.get(i);
            String name = settings.name(i);
            if (!session.addBot(id, tagged ? settings.name(i, difficulty, personality) : name)) {
                continue;
            }
            Random own = new Random(random.nextLong());
            // decisions of different bots fall on different ticks, a little irregularly
            bots.put(id, new BotController(id, name, difficulty, this, host, settings.profile(difficulty),
                    personality == null ? BotPersonality.Traits.NEUTRAL : personality.traits(own), own,
                    i * 7 + own.nextInt(5)));
            stats.add(id, name, difficulty.key(), personality == null ? "-" : personality.key());
            out.add(id);
        }
        return out;
    }

    private void track(LivingEntity entity, long clock) {
        Location at = entity.getLocation();
        motion.update(entity.getUniqueId(), at.getX(), at.getY(), at.getZ(), clock);
    }

    /** How {@code entity} really moves (blocks per tick, smoothed from its positions; zero if not tracked). */
    Vector motion(LivingEntity entity) {
        double[] v = motion.velocity(entity.getUniqueId());
        return new Vector(v[0], v[1], v[2]);
    }

    /** The fight numbers of this match (server log). */
    public BotStats stats() {
        return stats;
    }

    /** The fight numbers as server log lines: the bots still standing count as winners. */
    public List<String> statsReport() {
        for (UUID alive : session.aliveIds()) {
            stats.won(alive);
        }
        List<String> out = new ArrayList<>(stats.report());
        out.add("borderDeaths=" + borderDeaths);
        return out;
    }

    /**
     * Spawns the bodies at the given spots (one per bot, reused if short): dry ones first (no water or lava next to
     * them, see {@link #dry}), the others only if there are not enough.
     */
    public void spawnAll(List<Location> spots) {
        spawnAll(spots, Map.of());
    }

    /** {@link #spawnAll(List)}, but each bot in {@code teamSpots} (팀전: by its team's spot) spawns there. */
    public void spawnAll(List<Location> spots, Map<UUID, Location> teamSpots) {
        if (disposed || spots.isEmpty()) {
            return;
        }
        List<Location> ordered = new ArrayList<>(spots.size());
        for (Location spot : spots) {
            if (dry(spot)) {
                ordered.add(spot);
            }
        }
        for (Location spot : spots) {
            if (!ordered.contains(spot)) {
                ordered.add(spot);
            }
        }
        int i = 0;
        for (BotController bot : bots.values()) {
            if (bot.body() != null || !session.isAlive(bot.id())) {
                continue;
            }
            Location spot = ordered.get(Math.min(i++, ordered.size() - 1)).clone();
            spot.setYaw(random.nextFloat() * 360f);
            Location own = teamSpots.get(bot.id());
            if (own != null) {
                spot = own.clone(); // 팀전: next to its team
                spot.setYaw(random.nextFloat() * 360f);
            }
            io.github.sx2xoox.mcpl.core.game.MatchTeam team = session.teamOf(bot.id());
            BotBody body = BotBody.spawn(session, spot, Component.text(bot.name(),
                            team == null ? NamedTextColor.WHITE : team.color()),
                    host.messages().get("bots.description",
                            Messages.tag("difficulty", bot.difficulty().koreanName())),
                    settings.skins().isEmpty() ? null : settings.skins().get(i % settings.skins().size()),
                    settings.moveSpeed());
            if (body == null) {
                continue;
            }
            byEntity.put(body.body().getUniqueId(), bot.id());
            byEntity.put(body.legs().getUniqueId(), bot.id());
            bot.attach(body);
        }
        holdChunks(); // before the first tick: a body far from every player must not be unloaded
    }

    /**
     * A standing spot on solid ground under the open sky with no water, lava or hazard (powder snow, cactus, berry
     * bushes, magma, fire) within two blocks around, below or at head height.
     */
    public static boolean dry(Location spot) {
        World world = spot.getWorld();
        if (world == null) {
            return false;
        }
        int x = spot.getBlockX();
        int y = spot.getBlockY();
        int z = spot.getBlockZ();
        Material floor = world.getBlockAt(x, y - 1, z).getType();
        if (!floor.isSolid() || Hazards.hazard(floor) || Hazards.underground(spot)) {
            return false;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Block block = world.getBlockAt(x + dx, y + dy, z + dz);
                    if (block.isLiquid() || Hazards.hazard(block)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * True if {@code shooter} draws a bow (a bot holds its bow only while drawing) pointed at {@code at} (within about
     * 25°): an arrow is on its way.
     */
    boolean aimingAt(LivingEntity shooter, Entity at) {
        boolean drawing;
        if (shooter instanceof org.bukkit.entity.Player player) {
            org.bukkit.Material active = player.getActiveItem().getType();
            drawing = player.isHandRaised() && (active == org.bukkit.Material.BOW
                    || active == org.bukkit.Material.CROSSBOW);
        } else {
            org.bukkit.inventory.EntityEquipment equipment = shooter.getEquipment();
            drawing = equipment != null && equipment.getItemInMainHand().getType() == org.bukkit.Material.BOW;
        }
        if (!drawing) {
            return false;
        }
        Vector look = shooter.getEyeLocation().getDirection().setY(0);
        Vector to = at.getLocation().toVector().subtract(shooter.getLocation().toVector()).setY(0);
        if (look.lengthSquared() < 1.0E-6 || to.lengthSquared() < 1.0E-6) {
            return false;
        }
        return look.normalize().dot(to.normalize()) > 0.9;
    }

    /** True if {@code who} looks towards {@code at} (within about 70°): it would see someone sneaking up. */
    boolean facing(LivingEntity who, Entity at) {
        Vector look = who.getLocation().getDirection().setY(0);
        Vector to = at.getLocation().toVector().subtract(who.getLocation().toVector()).setY(0);
        if (look.lengthSquared() < 1.0E-6 || to.lengthSquared() < 1.0E-6) {
            return false;
        }
        return look.normalize().dot(to.normalize()) > 0.35;
    }

    public void tick(long clock) {
        if (disposed) {
            return;
        }
        if (host.fight()) {
            for (LivingEntity body : liveBodies()) {
                track(body, clock);
            }
            for (org.bukkit.entity.Player player : session.alivePlayers()) {
                track(player, clock);
            }
        }
        for (BotController bot : List.copyOf(bots.values())) {
            if (session.isAlive(bot.id())) {
                bot.tick(clock);
            }
        }
        if (clock % 20 == 0) {
            holdChunks();
        }
        if (clock % 20 == 10) {
            borderDamage(clock);
        }
        if (clock % 100 == 50 && host.fight()) {
            teams.sample();
            for (BotController bot : bots.values()) {
                if (session.isAlive(bot.id()) && bot.decision() != null) {
                    stats.sample(bot.id(), bot.decision().behaviour());
                }
            }
        }
    }

    /**
     * The world border hurts a bot outside it like a player, once a second: past the border's damage buffer,
     * {@code damage-per-block} per block out (at least 1), as a world border death. Applied here because the
     * teleported body is not ticked like a free-moving entity; skipped if the server already hurt it this second.
     */
    void borderDamage(long clock) {
        World world = host.world();
        WorldBorder border = world.getWorldBorder();
        double half = border.getSize() / 2;
        Location center = border.getCenter();
        for (LivingEntity body : liveBodies()) {
            if (!body.getWorld().equals(world)) {
                continue;
            }
            UUID id = botOf(body);
            Long last = id == null ? null : borderHurt.get(id);
            if (last != null && clock - last < 20) {
                continue;
            }
            double outside = outside(body.getLocation().getX(), body.getLocation().getZ(), center.getX(),
                    center.getZ(), half) - border.getDamageBuffer();
            double amount = borderDamage(outside, border.getDamageAmount());
            if (amount <= 0) {
                continue;
            }
            body.setNoDamageTicks(0);
            body.damage(amount, DamageSource.builder(DamageType.OUTSIDE_BORDER).build());
        }
    }

    /** Blocks outside a square border of half side {@code half} around {@code (cx, cz)} (0 inside). */
    static double outside(double x, double z, double cx, double cz, double half) {
        return Math.max(0, Math.max(Math.abs(x - cx), Math.abs(z - cz)) - half);
    }

    /** Vanilla's border damage for {@code blocksOut} past the buffer: {@code perBlock} each, at least 1; 0 inside. */
    static double borderDamage(double blocksOut, double perBlock) {
        if (!(blocksOut > 0) || !(perBlock > 0)) {
            return 0;
        }
        return Math.max(1, Math.floor(blocksOut * perBlock));
    }

    /** Remembers border damage the server dealt to a bot itself (no second helping this second). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBorderHurt(EntityDamageEvent event) {
        if (event.getDamageSource().getDamageType() == DamageType.OUTSIDE_BORDER && isBody(event.getEntity())) {
            UUID id = botOf(event.getEntity());
            if (id != null) {
                borderHurt.put(id, host.clock());
            }
        }
    }

    /** The most chunks held at once (15 bots × 3×3 around each, plus their bases being built). */
    static final int MAX_HELD = BotSettings.HARD_MAX * 9 + BotSettings.HARD_MAX;

    /** Uses {@code replacement} to hold chunks (tests record what is held). */
    public void useTickets(ChunkTickets replacement) {
        releaseChunks();
        this.tickets = replacement;
        this.keepLoaded = true;
    }

    /**
     * Holds the 3×3 chunks around every live bot's legs (and the chunk of a base it is building) with plugin tickets
     * and lets go of the rest: run every second (a bot walks well under a chunk a second), right after the bodies
     * spawn and after a death.
     */
    void holdChunks() {
        if (!keepLoaded || disposed) {
            return;
        }
        World world = host.world();
        Set<Long> wanted = new java.util.LinkedHashSet<>();
        for (BotController bot : bots.values()) {
            BotBody body = bot.body();
            if (body == null || !session.isAlive(bot.id()) || !body.valid() || !body.legs().getWorld().equals(world)) {
                continue;
            }
            int cx = body.legs().getLocation().getBlockX() >> 4;
            int cz = body.legs().getLocation().getBlockZ() >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    wanted.add(chunkKey(cx + dx, cz + dz));
                }
            }
            BasePlanner.Plan base = bot.base();
            if (base != null && bot.decision() != null
                    && bot.decision().behaviour() == BotBrain.Behaviour.BUILD_BASE) {
                wanted.add(chunkKey(base.x() >> 4, base.z() >> 4));
            }
        }
        try {
            ChunkTickets using = tickets();
            for (Long key : List.copyOf(held)) {
                if (!wanted.contains(key)) {
                    using.remove(chunkX(key), chunkZ(key));
                    held.remove(key);
                }
            }
            for (Long key : wanted) {
                if (held.size() >= MAX_HELD) {
                    break;
                }
                if (held.add(key)) {
                    using.add(chunkX(key), chunkZ(key));
                }
            }
        } catch (RuntimeException unsupported) {
            keepLoaded = false; // a server (or test mock) without chunk tickets: the bots near players still act
            held.clear();
            session.plugin().getLogger().log(Level.FINE, "Chunk tickets unavailable", unsupported);
        }
    }

    private ChunkTickets tickets() {
        if (tickets == null) {
            Plugin plugin = session.plugin();
            tickets = ChunkTickets.of(host.world(), plugin);
        }
        return tickets;
    }

    private void releaseChunks() {
        if (held.isEmpty()) {
            return;
        }
        held.clear();
        try {
            tickets().removeAll();
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.FINE, "Could not remove chunk tickets", e);
        }
    }

    /** Chunks held loaded around the bots right now (for tests). */
    public int heldChunks() {
        return held.size();
    }

    /** True while the chunk at chunk coordinates {@code x, z} is held for a bot (for tests). */
    public boolean holds(int chunkX, int chunkZ) {
        return held.contains(chunkKey(chunkX, chunkZ));
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }

    /** Removes every bot entity (match end, stop, plugin disable). */
    public void dispose() {
        disposed = true;
        releaseChunks();
        for (BotController bot : bots.values()) {
            BotBody body = bot.body();
            if (body != null) {
                body.remove();
            }
        }
        byEntity.clear();
    }

    // =================================================================== queries

    public boolean isBot(UUID id) {
        return bots.containsKey(id);
    }

    public @Nullable BotController bot(UUID id) {
        return bots.get(id);
    }

    public List<BotController> all() {
        return List.copyOf(bots.values());
    }

    /** The bot whose body (or legs) {@code entity} is, or null. */
    public @Nullable UUID botOf(@Nullable Entity entity) {
        return entity == null ? null : byEntity.get(entity.getUniqueId());
    }

    /** True for the body of a bot in play. */
    public boolean isBody(@Nullable Entity entity) {
        UUID id = botOf(entity);
        BotController bot = id == null ? null : bots.get(id);
        return bot != null && bot.body() != null && bot.body().body() == entity;
    }

    public boolean isLegs(@Nullable Entity entity) {
        UUID id = botOf(entity);
        BotController bot = id == null ? null : bots.get(id);
        return bot != null && bot.body() != null && bot.body().legs() == entity;
    }

    /** The body of a bot in play, or null. */
    public @Nullable LivingEntity body(UUID id) {
        BotController bot = bots.get(id);
        BotBody body = bot == null ? null : bot.body();
        return body != null && body.valid() && session.isAlive(id) ? body.body() : null;
    }

    /** Bodies of the bots in play. */
    public List<LivingEntity> liveBodies() {
        List<LivingEntity> out = new ArrayList<>();
        for (UUID id : bots.keySet()) {
            LivingEntity body = body(id);
            if (body != null) {
                out.add(body);
            }
        }
        return out;
    }

    /** The gear score of a combatant as others see it (a bot's gear, a player's armor and weapon). */
    double gearOf(LivingEntity entity) {
        UUID id = botOf(entity);
        BotController bot = id == null ? null : bots.get(id);
        if (bot != null) {
            return bot.gearScore();
        }
        return Gear.score(value(entity, Attribute.ARMOR), value(entity, Attribute.ARMOR_TOUGHNESS),
                Math.max(1, value(entity, Attribute.ATTACK_DAMAGE)));
    }

    private static double value(LivingEntity entity, Attribute attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? 0 : instance.getValue();
    }

    /** True if {@code who} hit or was hit by someone other than {@code me} in the last seconds. */
    boolean fightingOther(UUID who, UUID me, long now) {
        Hit hurt = lastHurtBy.get(who);
        Hit hit = lastHit.get(who);
        // a fight with a teammate of mine is my team's fight, not one to wait out
        return hurt != null && !hurt.other().equals(me) && !session.sameTeam(hurt.other(), me)
                && now - hurt.tick() <= FIGHT_TICKS
                || hit != null && !hit.other().equals(me) && !session.sameTeam(hit.other(), me)
                && now - hit.tick() <= FIGHT_TICKS;
    }

    /**
     * The nearest fight {@code listener} hears from {@code at} (hits within {@code range} blocks in the last
     * {@link #NOISE_TICKS} ticks it was not part of), or null.
     */
    @Nullable Location heardFight(UUID listener, Location at, long now, double range) {
        Location best = null;
        double bestDistance = range * range;
        for (Noise noise : noises) {
            if (now - noise.tick() > NOISE_TICKS || noise.attacker().equals(listener)
                    || noise.victim().equals(listener) || noise.at().getWorld() != at.getWorld()) {
                continue;
            }
            double d = noise.at().distanceSquared(at);
            if (d <= bestDistance) {
                bestDistance = d;
                best = noise.at().clone();
            }
        }
        return best;
    }

    /**
     * A bot's body takes suffocation damage (a block it did not see coming, falling sand): it gets out of the block
     * at once ({@link BotController#unstuck}) and the hit is cancelled if it could.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSuffocate(EntityDamageEvent event) {
        if (event.getDamageSource().getDamageType() != DamageType.IN_WALL || !isBody(event.getEntity())) {
            return;
        }
        UUID id = botOf(event.getEntity());
        BotController bot = id == null ? null : bots.get(id);
        BotBody body = bot == null ? null : bot.body();
        if (body == null) {
            return;
        }
        bot.unstuck(host.clock(), body);
        Location look = body.body().getLocation();
        body.sync(look.getYaw(), look.getPitch(), body.body().getBodyYaw());
        Location feet = body.legs().getLocation();
        if (!feet.getWorld().hasCollisionsIn(BotController.standing(feet.getX(), feet.getY(), feet.getZ()))) {
            event.setCancelled(true);
        }
    }

    /** The cells of the trappers' hidden pits (every bot knows them and keeps out). */
    private final java.util.Set<org.bukkit.block.Block> traps = new java.util.HashSet<>();

    void markTrap(org.bukkit.block.Block cell) {
        traps.add(cell);
    }

    /** True if {@code cell} is part of a trapper's hidden pit. */
    boolean trap(org.bukkit.block.Block cell) {
        return !traps.isEmpty() && traps.contains(cell);
    }

    /** Centers of the other bots' bases. */
    List<int[]> baseCenters(UUID except) {
        List<int[]> out = new ArrayList<>();
        for (BotController bot : bots.values()) {
            BasePlanner.Plan plan = bot.base();
            if (plan != null && !bot.id().equals(except) && !session.sameTeam(bot.id(), except)) {
                out.add(new int[] {plan.x(), plan.z()});
            }
        }
        return out;
    }

    /** True for an attribute modifier of this plugin (an augment's). */
    boolean ours(AttributeModifier modifier) {
        return modifier.getKey().getNamespace().equals(namespace);
    }

    void log(String what, Throwable error) {
        session.plugin().getLogger().log(Level.WARNING, "Error in " + what, error);
    }

    // =================================================================== events

    /** The legs are only a vehicle: hits on them land on the body. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegsHurt(EntityDamageEvent event) {
        if (!isLegs(event.getEntity())) {
            return;
        }
        event.setCancelled(true);
        UUID id = botOf(event.getEntity());
        LivingEntity body = id == null ? null : body(id);
        if (body != null && event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() != body
                && forwarding == 0) {
            forwarding++;
            try {
                body.damage(event.getDamage(), event.getDamageSource());
            } finally {
                forwarding--;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegsBurn(EntityCombustEvent event) {
        if (isLegs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** No trading with the invisible legs. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (isLegs(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    /** The PvP gate and teams for hits where a bot is involved, and the shield. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onGate(EntityDamageEvent event) {
        UUID attacker = host.attackerOf(event);
        UUID victim = host.participantOf(event.getEntity());
        if (attacker == null || victim == null || attacker.equals(victim)
                || !isBot(attacker) && !isBot(victim)) {
            return;
        }
        if (!host.fight() || session.sameTeam(attacker, victim)) {
            event.setCancelled(true);
            return;
        }
        BotController bot = bots.get(victim);
        if (bot != null && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event instanceof EntityDamageByEntityEvent byEntity && bot.block(host.clock(), byEntity.getDamager())) {
            event.setCancelled(true);
        }
    }

    /** The living body that dealt {@code event} (the shooter of a projectile), or null. */
    static @Nullable LivingEntity attackerBody(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter) {
            return shooter;
        }
        return damager instanceof LivingEntity living ? living : null;
    }

    /** Stats, kill credit, fights for third parties and the knockback of a bot's body. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRecord(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        UUID attacker = host.attackerOf(event);
        UUID victimId = host.participantOf(victim);
        if (attacker == null || victimId == null || attacker.equals(victimId)) {
            return;
        }
        long now = host.clock();
        lastHurtBy.put(victimId, new Hit(attacker, now));
        lastHit.put(attacker, new Hit(victimId, now));
        BotController hurt = bots.get(victimId);
        if (hurt != null && session.isParticipant(attacker)) {
            hurt.struck(attacker, attackerBody(event), now); // it remembers who hit it, and where they are
        }
        for (UUID side : new UUID[] {attacker, victimId}) {
            BotController bot = bots.get(side);
            if (bot != null) {
                bot.fought(now);
            }
        }
        noises.removeIf(noise -> now - noise.tick() > NOISE_TICKS);
        if (noises.size() >= 32) {
            noises.removeFirst();
        }
        noises.add(new Noise(victim.getLocation(), now, attacker, victimId));
        if (session.isParticipant(attacker) && session.isParticipant(victimId)) {
            teams.hit(attacker, victimId, attackerBody(event), victim, now);
        }
        if (isBot(attacker) || isBot(victimId)) {
            double dealt = Math.min(event.getFinalDamage(), victim.getHealth() + victim.getAbsorptionAmount());
            session.recordDamage(attacker, victimId, dealt);
            boolean arrow = event instanceof EntityDamageByEntityEvent byEntity
                    && byEntity.getDamager() instanceof org.bukkit.entity.Projectile;
            boolean melee = event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
            stats.damage(attacker, victimId, dealt, arrow, melee && !arrow);
        }
        if (isBody(victim) && event instanceof EntityDamageByEntityEvent byEntity) {
            knockBack(victimId, byEntity.getDamager());
        }
    }

    private void knockBack(UUID bot, Entity damager) {
        BotController controller = bots.get(bot);
        BotBody body = controller == null ? null : controller.body();
        if (body == null) {
            return;
        }
        Vector away = body.legs().getLocation().toVector().subtract(damager.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1.0E-6) {
            return;
        }
        double resist = Math.max(0, Math.min(1, value(body.body(), Attribute.KNOCKBACK_RESISTANCE)));
        Vector push = away.normalize().multiply(0.4 * (1 - resist)).setY(0.36 * (1 - resist));
        body.legs().setVelocity(body.legs().getVelocity().multiply(0.5).add(push));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        UUID id = botOf(dead);
        if (id == null) {
            return;
        }
        BotController bot = bots.get(id);
        event.setDroppedExp(0);
        event.getDrops().clear();
        if (bot == null || !isBody(dead)) {
            return;
        }
        event.getDrops().addAll(bot.loot());
        EntityDamageEvent last = dead.getLastDamageCause();
        if (last != null && last.getDamageSource().getDamageType() == DamageType.OUTSIDE_BORDER) {
            borderDeaths++;
        }
        UUID killer = session.combat().lastAttackerId(id, CombatTracker.DEFAULT_WINDOW_TICKS);
        stats.died(id, session.aliveIds().size(), last == null ? "?" : last.getDamageSource().getDamageType()
                == DamageType.OUTSIDE_BORDER ? "border" : killer != null ? (isBot(killer) ? "bot" : "player")
                + (last.getCause() == EntityDamageEvent.DamageCause.PROJECTILE ? "-arrow" : "")
                : last.getCause().name().toLowerCase(java.util.Locale.ROOT), killer);
        BotBody body = bot.body();
        if (body != null) {
            body.legs().remove();
        }
        for (BotController other : bots.values()) {
            other.forget(id);
            BotBody witness = other.body();
            if (killer != null && witness != null && witness.valid() && !other.id().equals(killer)
                    && witness.body().getWorld().equals(dead.getWorld())
                    && witness.body().getLocation().distanceSquared(dead.getLocation()) < 32 * 32) {
                other.grudge(killer, host.clock()); // a kill nearby is not forgotten
            }
        }

        host.botDied(id, dead, killer, dead.getLastDamageCause());
        holdChunks(); // its chunks go now
    }

    /** A player left the match: the bots forget them. */
    public void forget(UUID participant) {
        for (BotController bot : bots.values()) {
            bot.forget(participant);
        }
    }

    /** The bot a Mannequin body belongs to (for tests). */
    public @Nullable BotController controllerOf(Mannequin body) {
        UUID id = botOf(body);
        return id == null ? null : bots.get(id);
    }
}
