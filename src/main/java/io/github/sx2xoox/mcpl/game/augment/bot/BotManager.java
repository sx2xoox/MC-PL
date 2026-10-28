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
import org.bukkit.World;
import org.bukkit.WorldBorder;
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
    /** Chunks held loaded (and ticking) around the bots, as {@code x, z} keys. */
    private final Set<Long> held = new HashSet<>();
    private @Nullable ChunkTickets tickets;
    /** When each bot last took world border damage (ticks of {@link BotHost#clock()}). */
    private final Map<UUID, Long> borderHurt = new HashMap<>();
    private int forwarding;
    private boolean disposed;

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
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < wanted; i++) {
            UUID id = UUID.randomUUID();
            BotDifficulty difficulty = lineup.get(i);
            String name = settings.name(i);
            if (!session.addBot(id, tagged ? settings.name(i, difficulty) : name)) {
                continue;
            }
            bots.put(id, new BotController(id, name, difficulty, this, host, settings.profile(difficulty),
                    new Random(random.nextLong()), i * 7));
            out.add(id);
        }
        return out;
    }

    /**
     * Spawns the bodies at the given spots (one per bot, reused if short): dry ones first (no water or lava next to
     * them, see {@link #dry}), the others only if there are not enough.
     */
    public void spawnAll(List<Location> spots) {
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
            BotBody body = BotBody.spawn(session, spot, Component.text(bot.name(), NamedTextColor.WHITE),
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

    /** A standing spot on solid ground with no water or lava within two blocks around or below it. */
    public static boolean dry(Location spot) {
        World world = spot.getWorld();
        if (world == null) {
            return false;
        }
        int x = spot.getBlockX();
        int y = spot.getBlockY();
        int z = spot.getBlockZ();
        if (!world.getBlockAt(x, y - 1, z).getType().isSolid()) {
            return false;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    if (world.getBlockAt(x + dx, y + dy, z + dz).isLiquid()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public void tick(long clock) {
        if (disposed) {
            return;
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
        return hurt != null && !hurt.other().equals(me) && now - hurt.tick() <= FIGHT_TICKS
                || hit != null && !hit.other().equals(me) && now - hit.tick() <= FIGHT_TICKS;
    }

    /** Centers of the other bots' bases. */
    List<int[]> baseCenters(UUID except) {
        List<int[]> out = new ArrayList<>();
        for (BotController bot : bots.values()) {
            BasePlanner.Plan plan = bot.base();
            if (plan != null && !bot.id().equals(except)) {
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
        if (isBot(attacker) || isBot(victimId)) {
            double dealt = Math.min(event.getFinalDamage(), victim.getHealth() + victim.getAbsorptionAmount());
            session.recordDamage(attacker, victimId, dealt);
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
        UUID killer = session.combat().lastAttackerId(id, CombatTracker.DEFAULT_WINDOW_TICKS);
        BotBody body = bot.body();
        if (body != null) {
            body.legs().remove();
        }
        for (BotController other : bots.values()) {
            other.forget(id);
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
