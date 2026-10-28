package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.combat.CombatTracker;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
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
    private final BotDifficulty difficulty;
    private final Random random;
    private final String namespace;
    private final Map<UUID, BotController> bots = new LinkedHashMap<>();
    /** Body and legs entity ids → bot id. */
    private final Map<UUID, UUID> byEntity = new HashMap<>();
    private final Map<UUID, Hit> lastHurtBy = new HashMap<>();
    private final Map<UUID, Hit> lastHit = new HashMap<>();
    private int forwarding;
    private boolean disposed;

    public BotManager(BotHost host, BotSettings settings, BotDifficulty difficulty) {
        this.host = host;
        this.session = host.session();
        this.settings = settings;
        this.difficulty = difficulty;
        this.random = session.random();
        this.namespace = session.hub().key("bot").getNamespace();
    }

    public BotDifficulty difficulty() {
        return difficulty;
    }

    GameSession session() {
        return session;
    }

    // =================================================================== lifecycle

    /** Adds {@code count} bots (capped) as participants of the running session; their ids in order. */
    public List<UUID> register(int count) {
        int wanted = Math.max(0, Math.min(Math.min(settings.max(), BotSettings.HARD_MAX), count));
        List<UUID> out = new ArrayList<>();
        BotProfile profile = settings.profile(difficulty);
        for (int i = 0; i < wanted; i++) {
            UUID id = UUID.randomUUID();
            String name = settings.name(i);
            if (!session.addBot(id, name)) {
                continue;
            }
            bots.put(id, new BotController(id, name, this, host, profile, new Random(random.nextLong()), i * 7));
            out.add(id);
        }
        return out;
    }

    /** Spawns the bodies at the given spots (one per bot, reused if short). */
    public void spawnAll(List<Location> spots) {
        if (disposed || spots.isEmpty()) {
            return;
        }
        int i = 0;
        for (BotController bot : bots.values()) {
            if (bot.body() != null || !session.isAlive(bot.id())) {
                continue;
            }
            Location spot = spots.get(Math.min(i++, spots.size() - 1)).clone();
            spot.setYaw(random.nextFloat() * 360f);
            BotBody body = BotBody.spawn(session, spot, Component.text(bot.name(), NamedTextColor.WHITE),
                    host.messages().get("bots.description", Messages.tag("difficulty", difficulty.koreanName())),
                    settings.skins().isEmpty() ? null : settings.skins().get(i % settings.skins().size()),
                    settings.moveSpeed());
            if (body == null) {
                continue;
            }
            byEntity.put(body.body().getUniqueId(), bot.id());
            byEntity.put(body.legs().getUniqueId(), bot.id());
            bot.attach(body);
        }
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
    }

    /** Removes every bot entity (match end, stop, plugin disable). */
    public void dispose() {
        disposed = true;
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
