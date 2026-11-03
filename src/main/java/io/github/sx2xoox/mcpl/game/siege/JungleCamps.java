package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.map.siege.SiegeSites;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Vex;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.Nullable;

/**
 * The neutral monster camps of a 공성전 half (docs/siege.md §13), like jungle camps: small camps, buff camps
 * (레드·블루 서버) and the epic 메인프레임 수호자, each from the map's {@link SiegeSites#camps()} and with its own
 * respawn timer. Monsters stand still until a player hits them, then fight the players in play; pulled beyond their
 * leash (or left alone for a while) they walk home with full health. Either team can kill them: the last hit gives the
 * camp to that team (experience split between its players nearby, a bonus for the last hit, a team buff for the bigger
 * camps). The monsters are enemies of both teams' abilities through the {@code MatchHost} hooks; they and their labels
 * are tracked by the session, their chunks are held loaded, and everything goes at the end of the half.
 */
final class JungleCamps implements Listener {

    private final SiegeMatch match;
    private final GameSession session;
    private final Messages messages;
    private final CampSettings settings;
    private final List<Camp> camps = new ArrayList<>();
    private final List<int[]> heldChunks = new ArrayList<>();
    /** The team buffs: player → half tick the buff ends. */
    private final Map<UUID, Long> red = new HashMap<>();
    private final Map<UUID, Long> blue = new HashMap<>();
    /** Everyone who got a camp buff's potion effects this half. */
    private final Set<UUID> buffed = new HashSet<>();
    private long now;

    JungleCamps(SiegeMatch match, CampSettings settings) {
        this.match = match;
        this.session = match.session();
        this.messages = match.messages();
        this.settings = settings;
        if (!settings.enabled()) {
            return;
        }
        SiegeLayout layout = match.layout();
        for (SiegeSites.Camp site : layout.sites().camps()) {
            CampKind kind = CampKind.byId(site.kind());
            if (kind == null) {
                session.plugin().getLogger().warning("Unknown siege camp kind " + site.kind() + " (" + site.id() + ")");
                continue;
            }
            camps.add(new Camp(site, kind, layout.at(site)));
        }
        holdChunks();
    }

    /** One camp of the map. */
    final class Camp {
        private final SiegeSites.Camp site;
        private final CampKind kind;
        private final Location at;
        /** The live monsters, each with its home place. */
        private final Map<Mob, Location> mobs = new LinkedHashMap<>();
        /** The last player who hit each monster (by monster id). */
        private final Map<UUID, UUID> hitters = new HashMap<>();
        private boolean alive;
        private long spawnAt;
        private boolean warned;
        private boolean aggro;
        private long lastHit;
        private @Nullable UUID lastHitter;
        private @Nullable TextDisplay label;

        private Camp(SiegeSites.Camp site, CampKind kind, Location at) {
            this.site = site;
            this.kind = kind;
            this.at = at;
        }

        SiegeSites.Camp site() {
            return site;
        }

        CampKind kind() {
            return kind;
        }

        Location at() {
            return at.clone();
        }

        boolean isAlive() {
            return alive;
        }

        boolean isFighting() {
            return aggro;
        }

        /** Half tick of the next spawn (while dead). */
        long spawnAt() {
            return spawnAt;
        }

        List<Mob> mobs() {
            return List.copyOf(mobs.keySet());
        }

        @Nullable TextDisplay label() {
            return label;
        }

        CampSettings.TierSettings tier() {
            return settings.tier(kind.tier());
        }
    }

    // ------------------------------------------------------------------ flow

    /** The fight of a half begins: every camp waits for its first spawn. */
    void beginFight() {
        clear();
        for (Camp camp : camps) {
            camp.spawnAt = camp.tier().firstTicks();
            if (camp.spawnAt <= 0) {
                spawn(camp);
            }
            label(camp, true);
        }
    }

    /** The half is over: monsters, labels and buffs are gone. */
    void clear() {
        for (Camp camp : camps) {
            removeMobs(camp);
            camp.alive = false;
            camp.warned = false;
            camp.aggro = false;
            camp.lastHitter = null;
            if (camp.label != null) {
                camp.label.remove();
                session.untrack(camp.label);
                camp.label = null;
            }
        }
        for (UUID id : buffed) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.removePotionEffect(PotionEffectType.STRENGTH);
                player.removePotionEffect(PotionEffectType.HASTE);
                player.removePotionEffect(PotionEffectType.RESISTANCE);
                player.removePotionEffect(PotionEffectType.ABSORPTION);
            }
        }
        buffed.clear();
        red.clear();
        blue.clear();
        now = 0;
    }

    /** The match ends: {@link #clear()} and the chunks are free again. */
    void stop() {
        clear();
        World world = match.layout().world();
        for (int[] chunk : heldChunks) {
            world.removePluginChunkTicket(chunk[0], chunk[1], session.plugin());
        }
        heldChunks.clear();
    }

    /** Once a second of the fight. */
    void second(long tick) {
        now = tick;
        for (Camp camp : camps) {
            if (!camp.alive) {
                waitToSpawn(camp);
            } else {
                guard(camp);
            }
            label(camp, false);
        }
        buffs();
    }

    private void waitToSpawn(Camp camp) {
        long left = camp.spawnAt - now;
        if (left <= 0) {
            spawn(camp);
            return;
        }
        int warning = camp.tier().warningTicks();
        if (camp.kind.tier() != CampKind.Tier.SMALL && !camp.warned && warning > 0 && left <= warning) {
            camp.warned = true;
            session.broadcast(messages.prefixed("camp.soon", Messages.tag("name", campName(camp)),
                    Messages.tag("seconds", (left + 19) / 20)));
            session.playSound(Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, camp.kind.tier() == CampKind.Tier.EPIC ? 0.6f : 1f);
        }
    }

    /** Leash and reset: a monster pulled too far, or a fight nobody kept up, sends the camp home with full health. */
    private void guard(Camp camp) {
        List<Mob> lost = new ArrayList<>();
        boolean pulled = false;
        double leash = camp.tier().leashRadius();
        for (Mob mob : camp.mobs.keySet()) {
            if (!mob.isValid() || mob.isDead()) {
                lost.add(mob);
            } else if (!mob.getWorld().equals(camp.at.getWorld())
                    || mob.getLocation().distanceSquared(camp.at) > leash * leash) {
                pulled = true;
            }
        }
        for (Mob mob : lost) {
            // Gone without a death event (unloaded, removed): no credit.
            died(camp, mob, null);
            if (!camp.alive) {
                return;
            }
        }
        if (pulled || camp.aggro && now - camp.lastHit >= settings.resetTicks()) {
            reset(camp);
            return;
        }
        if (camp.aggro) {
            for (Mob mob : camp.mobs.keySet()) {
                if (!(mob.getTarget() instanceof Player target) || !canFight(target)) {
                    Player next = nearestFighter(camp);
                    if (next != null) {
                        mob.setTarget(next);
                    }
                }
            }
        }
    }

    /** Back home, full health, standing still again. */
    void reset(Camp camp) {
        camp.aggro = false;
        camp.hitters.clear();
        camp.lastHitter = null;
        for (Map.Entry<Mob, Location> entry : camp.mobs.entrySet()) {
            Mob mob = entry.getKey();
            mob.setTarget(null);
            mob.setAware(false);
            mob.setFireTicks(0);
            mob.teleport(entry.getValue());
            AttributeInstance max = mob.getAttribute(Attribute.MAX_HEALTH);
            mob.setHealth(max == null ? mob.getHealth() : max.getValue());
        }
        World world = camp.at.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.HAPPY_VILLAGER, camp.at.clone().add(0, 1, 0), 12, 1, 0.6, 1, 0);
        }
    }

    private @Nullable Player nearestFighter(Camp camp) {
        double leash = camp.tier().leashRadius();
        Player best = null;
        double bestDistance = leash * leash;
        for (Player player : session.alivePlayers()) {
            if (!canFight(player) || !player.getWorld().equals(camp.at.getWorld())) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(camp.at);
            if (distance <= bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ spawn

    private void spawn(Camp camp) {
        removeMobs(camp);
        World world = camp.at.getWorld();
        if (world == null) {
            return;
        }
        CampSettings.MobSettings mob = settings.mob(camp.kind);
        int teamSize = Math.max(match.members(0).size(), match.members(1).size());
        double health = settings.health(camp.kind, teamSize);
        int count = mob.count();
        double spread = count > 1 ? Math.max(0, Math.min(camp.site.radius() - 1, 2.0)) : 0;
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2 * i / count;
            Location home = camp.at.clone().add(Math.cos(angle) * spread, 0, Math.sin(angle) * spread);
            home.setYaw((float) Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle))));
            try {
                Mob spawned = world.spawn(home, camp.kind.type(), m -> configure(m, camp, health, mob.scale()));
                session.track(spawned);
                camp.mobs.put(spawned, home);
            } catch (RuntimeException e) {
                session.plugin().getLogger().log(Level.WARNING, "Could not spawn the camp " + camp.site.id(), e);
            }
        }
        camp.alive = !camp.mobs.isEmpty();
        camp.warned = false;
        camp.aggro = false;
        camp.hitters.clear();
        camp.lastHitter = null;
        if (!camp.alive) {
            camp.spawnAt = now + camp.tier().respawnTicks();
            return;
        }
        world.spawnParticle(Particle.PORTAL, camp.at.clone().add(0, 1, 0), 40, 1, 1, 1, 0.3);
        switch (camp.kind.tier()) {
            case SMALL -> {
            }
            case BUFF -> {
                session.broadcast(messages.prefixed("camp.buff-spawned", Messages.tag("name", campName(camp)),
                        Messages.tag("buff", messages.get("camp.buff." + camp.kind.id()))));
                session.playSound(Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.2f);
            }
            case EPIC -> {
                match.title(messages.get("camp.epic-title", Messages.tag("name", campName(camp))),
                        messages.get("camp.epic-subtitle", Messages.tag("xp", camp.tier().xp())));
                session.broadcast(messages.prefixed("camp.epic-spawned", Messages.tag("name", campName(camp)),
                        Messages.tag("xp", camp.tier().xp())));
                session.playSound(Sound.ENTITY_RAVAGER_ROAR, 0.8f, 0.7f);
            }
        }
    }

    private void configure(Mob mob, Camp camp, double health, double scale) {
        mob.setPersistent(false);
        optional(() -> mob.setRemoveWhenFarAway(false));
        optional(() -> mob.setDespawnInPeacefulOverride(TriState.FALSE));
        optional(() -> mob.setCanPickupItems(false));
        mob.customName(messages.get("camp.mob." + camp.kind.id()));
        mob.setCustomNameVisible(true);
        mob.setAware(false);
        base(mob, Attribute.MAX_HEALTH, health);
        base(mob, Attribute.SCALE, scale);
        base(mob, Attribute.FOLLOW_RANGE, camp.tier().leashRadius() + 4);
        if (camp.kind.tier() != CampKind.Tier.SMALL) {
            base(mob, Attribute.KNOCKBACK_RESISTANCE, camp.kind.tier() == CampKind.Tier.EPIC ? 1 : 0.5);
        }
        AttributeInstance max = mob.getAttribute(Attribute.MAX_HEALTH);
        mob.setHealth(max == null ? Math.min(health, mob.getHealth()) : max.getValue());
        if (mob instanceof Vex vex) {
            optional(() -> vex.setLimitedLifetime(false));
            optional(() -> vex.setBound(camp.at));
        }
        if (mob instanceof PiglinAbstract piglin) {
            optional(() -> piglin.setImmuneToZombification(true));
        }
        EntityEquipment equipment = mob.getEquipment();
        if (camp.kind.gear() != null) {
            equipment.setHelmet(dyed(Material.LEATHER_HELMET, camp), true);
            equipment.setChestplate(dyed(Material.LEATHER_CHESTPLATE, camp), true);
        }
    }

    /** Vanilla behaviour a test server may not implement (the camp works without it). */
    private static void optional(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException testServer) {
            // A test server that does not implement it (MockBukkit's UnimplementedOperationException).
        }
    }

    private static ItemStack dyed(Material material, Camp camp) {
        ItemStack item = ItemStack.of(material);
        if (camp.kind.gear() != null) {
            item.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(camp.kind.gear()));
        }
        return item;
    }

    private static void base(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private void removeMobs(Camp camp) {
        for (Mob mob : camp.mobs.keySet()) {
            mob.remove();
            session.untrack(mob);
        }
        camp.mobs.clear();
        camp.hitters.clear();
    }

    // ------------------------------------------------------------------ kills

    /** A monster of the camp is gone; credit is the player who hit it last (null if none). */
    private void died(Camp camp, Mob mob, @Nullable UUID credit) {
        if (camp.mobs.remove(mob) == null) {
            return;
        }
        session.untrack(mob);
        UUID last = credit != null ? credit : camp.hitters.get(mob.getUniqueId());
        if (last == null) {
            last = camp.lastHitter;
        }
        camp.hitters.remove(mob.getUniqueId());
        if (camp.mobs.isEmpty()) {
            cleared(camp, last);
        }
    }

    private void cleared(Camp camp, @Nullable UUID credit) {
        camp.alive = false;
        camp.aggro = false;
        camp.warned = false;
        camp.hitters.clear();
        camp.lastHitter = null;
        camp.spawnAt = now + camp.tier().respawnTicks();
        int team = credit == null ? -1 : match.teamIndex(credit);
        if (team < 0) {
            return;
        }
        CampSettings.TierSettings tier = camp.tier();
        SiegeProgress progress = match.progress();
        Component teamName = match.teams().get(team).displayName();
        if (camp.kind.tier() == CampKind.Tier.EPIC) {
            progress.teamXp(team, tier.xp());
        } else {
            List<UUID> sharers = sharers(camp, team, credit);
            int each = sharers.isEmpty() ? 0 : (tier.xp() + sharers.size() - 1) / sharers.size();
            for (UUID id : sharers) {
                progress.grant(id, each);
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    player.sendActionBar(messages.get(id.equals(credit) ? "camp.xp-last-hit" : "camp.xp-share",
                            Messages.tag("name", campName(camp)), Messages.tag("xp", each),
                            Messages.tag("bonus", tier.lastHitBonus())));
                }
            }
        }
        progress.grant(credit, tier.lastHitBonus());
        switch (camp.kind) {
            case RED -> buff(team, red, tier.buffTicks(), PotionEffectType.STRENGTH);
            case BLUE -> buff(team, blue, tier.buffTicks(), PotionEffectType.HASTE);
            case GUARDIAN -> buffGuardian(team, tier.buffTicks());
            default -> {
            }
        }
        Component killer = session.publicName(credit);
        switch (camp.kind.tier()) {
            case SMALL -> {
                for (UUID member : match.members(team)) {
                    Player player = Bukkit.getPlayer(member);
                    if (player != null) {
                        messages.send(player, "camp.small-cleared", Messages.tag("player", killer),
                                Messages.tag("name", campName(camp)));
                    }
                }
            }
            case BUFF -> {
                session.broadcast(messages.prefixed("camp.buff-cleared", Messages.tag("team", teamName),
                        Messages.tag("player", killer), Messages.tag("name", campName(camp)),
                        Messages.tag("buff", messages.get("camp.buff." + camp.kind.id()))));
                session.playSound(Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.3f);
            }
            case EPIC -> {
                match.title(messages.get("camp.epic-cleared-title", Messages.tag("team", teamName),
                                Messages.tag("name", campName(camp))),
                        messages.get("camp.epic-cleared-subtitle", Messages.tag("xp", tier.xp())));
                session.broadcast(messages.prefixed("camp.epic-cleared", Messages.tag("team", teamName),
                        Messages.tag("player", killer), Messages.tag("name", campName(camp)),
                        Messages.tag("xp", tier.xp())));
                session.playSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 0.9f);
            }
        }
    }

    /** The killing team's players in play near the camp, the last hitter always among them. */
    private List<UUID> sharers(Camp camp, int team, UUID credit) {
        List<UUID> out = new ArrayList<>();
        double radius = settings.shareRadius();
        for (UUID member : match.members(team)) {
            Player player = Bukkit.getPlayer(member);
            if (member.equals(credit) || player != null && canFight(player)
                    && player.getWorld().equals(camp.at.getWorld())
                    && player.getLocation().distanceSquared(camp.at) <= radius * radius) {
                out.add(member);
            }
        }
        if (!out.contains(credit)) {
            out.add(credit);
        }
        return out;
    }

    // ------------------------------------------------------------------ buffs

    private void buff(int team, Map<UUID, Long> holders, int ticks, PotionEffectType icon) {
        if (ticks <= 0) {
            return;
        }
        for (UUID member : match.members(team)) {
            Player player = Bukkit.getPlayer(member);
            if (player == null || !canFight(player)) {
                continue;
            }
            holders.put(member, now + ticks);
            buffed.add(member);
            player.addPotionEffect(new PotionEffect(icon, ticks, 0, false, true, true));
        }
    }

    private void buffGuardian(int team, int ticks) {
        if (ticks <= 0) {
            return;
        }
        for (UUID member : match.members(team)) {
            Player player = Bukkit.getPlayer(member);
            if (player == null || !canFight(player)) {
                continue;
            }
            buffed.add(member);
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, 0, false, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, ticks, 1, false, true, true));
        }
    }

    /** Ends expired buffs (and those of the dead: a buff does not survive a death) and runs the blue buff. */
    private void buffs() {
        red.entrySet().removeIf(entry -> !holds(entry.getKey(), entry.getValue()));
        blue.entrySet().removeIf(entry -> !holds(entry.getKey(), entry.getValue()));
        if (blue.isEmpty()) {
            return;
        }
        var context = match.abilities().context();
        for (UUID id : blue.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            if (settings.blueCooldownPerSecond() > 0) {
                context.reduceCooldown(player, settings.blueCooldownPerSecond());
            }
            if (settings.blueGaugePerSecond() > 0 && match.progress().canChargeGauge(player)) {
                context.addEarnedGauge(player, settings.blueGaugePerSecond());
            }
        }
    }

    private boolean holds(UUID id, long until) {
        Player player = Bukkit.getPlayer(id);
        return until > now && player != null && canFight(player) && !player.isDead();
    }

    boolean hasRedBuff(UUID player) {
        Long until = red.get(player);
        return until != null && until > now;
    }

    boolean hasBlueBuff(UUID player) {
        Long until = blue.get(player);
        return until != null && until > now;
    }

    // ------------------------------------------------------------------ events

    /** Monsters only ever target players in play, and only while their camp fights. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        Camp camp = campOf(event.getEntity());
        if (camp != null && (!camp.aggro || !(event.getTarget() instanceof Player target) || !canFight(target))) {
            event.setCancelled(true);
        }
    }

    /**
     * Damage on a monster: only from players in play during the fight (ability damage included) and from the burns,
     * poisons and withering they cause; the fall, walls and other monsters do not hurt it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobDamage(EntityDamageEvent event) {
        Camp camp = campOf(event.getEntity());
        if (camp == null) {
            return;
        }
        if (match.phase() != SiegeMatch.Phase.FIGHT) {
            event.setCancelled(true);
            return;
        }
        Player attacker = session.combat().resolveAttacker(event);
        if (attacker == null) {
            switch (event.getCause()) {
                case FIRE_TICK, POISON, WITHER, KILL, VOID -> {
                }
                default -> event.setCancelled(true);
            }
            return;
        }
        if (!canFight(attacker)) {
            event.setCancelled(true);
        }
    }

    /** A landed hit on a monster: the camp fights back, and the hitter is remembered for the kill. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobHit(EntityDamageEvent event) {
        Camp camp = campOf(event.getEntity());
        Player attacker = camp == null ? null : session.combat().resolveAttacker(event);
        if (camp == null || attacker == null || !canFight(attacker)) {
            return;
        }
        camp.aggro = true;
        camp.lastHit = now;
        camp.lastHitter = attacker.getUniqueId();
        camp.hitters.put(event.getEntity().getUniqueId(), attacker.getUniqueId());
        for (Mob mob : camp.mobs.keySet()) {
            mob.setAware(true);
            if (!(mob.getTarget() instanceof Player target) || !canFight(target)) {
                mob.setTarget(attacker);
            }
        }
    }

    /** What a monster does to players: only players in play, the camp's own damage. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobAttack(EntityDamageByEntityEvent event) {
        Camp camp = campOf(source(event.getDamager()));
        if (camp == null) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim) || !canFight(victim) || match.isProtected(victim)
                || match.phase() != SiegeMatch.Phase.FIGHT) {
            event.setCancelled(true);
            return;
        }
        event.setDamage(settings.mob(camp.kind).damage());
    }

    /** The 레드 서버 buff: what its holder hits (an enemy player or a monster) burns. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(EntityDamageEvent event) {
        if (red.isEmpty() || settings.redBurnTicks() <= 0 || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Player attacker = session.combat().resolveAttacker(event);
        if (attacker == null || attacker.equals(victim) || !hasRedBuff(attacker.getUniqueId())
                || match.towerOf(victim) != null || !match.abilities().context().isEnemy(attacker, victim)) {
            return;
        }
        victim.setFireTicks(Math.max(victim.getFireTicks(), settings.redBurnTicks()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMobDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        Camp camp = campOf(mob);
        if (camp == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        died(camp, mob, camp.hitters.get(mob.getUniqueId()));
    }

    private static @Nullable Entity source(Entity damager) {
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Entity entity ? entity : null;
        }
        return damager;
    }

    // ------------------------------------------------------------------ host hooks and queries

    /** A player who may fight camps: a team member in play. */
    private boolean canFight(Player player) {
        return session.isParticipant(player) && match.teamIndex(player.getUniqueId()) >= 0
                && session.isInPlay(player);
    }

    /** The camp a live monster belongs to, or null. */
    @Nullable Camp campOf(@Nullable Entity entity) {
        if (!(entity instanceof Mob mob)) {
            return null;
        }
        for (Camp camp : camps) {
            if (camp.mobs.containsKey(mob)) {
                return camp;
            }
        }
        return null;
    }

    /** The live monsters (enemy candidates for abilities). */
    List<LivingEntity> targets() {
        List<LivingEntity> out = new ArrayList<>();
        for (Camp camp : camps) {
            for (Mob mob : camp.mobs.keySet()) {
                if (mob.isValid() && !mob.isDead()) {
                    out.add(mob);
                }
            }
        }
        return out;
    }

    /** Monsters are enemies of every player in play of both teams during the fight. */
    boolean isEnemy(Player owner, Entity entity) {
        return match.phase() == SiegeMatch.Phase.FIGHT && campOf(entity) != null && entity.isValid()
                && canFight(owner);
    }

    List<Camp> camps() {
        return List.copyOf(camps);
    }

    @Nullable Camp camp(String id) {
        for (Camp camp : camps) {
            if (camp.site.id().equals(id)) {
                return camp;
            }
        }
        return null;
    }

    private Component campName(Camp camp) {
        return messages.get("camp.name-" + camp.kind.tier().key(), Messages.tag("name", camp.site.name()));
    }

    // ------------------------------------------------------------------ looks

    private Component status(Camp camp) {
        if (!camp.alive) {
            return messages.get("camp.status-dead",
                    Messages.tag("time", Format.clock((Math.max(0, camp.spawnAt - now) + 19) / 20)));
        }
        return messages.get(camp.aggro ? "camp.status-fighting" : "camp.status-alive");
    }

    /**
     * Spawns or refreshes the label over the camp (the name and the respawn timer while it is dead): at the start of
     * the fight, and again later if its chunk unloaded it.
     */
    private void label(Camp camp, boolean spawn) {
        World world = camp.at.getWorld();
        if (world == null || !spawn && !world.isChunkLoaded(camp.at.getBlockX() >> 4, camp.at.getBlockZ() >> 4)) {
            return;
        }
        Component text = messages.get("camp.label", Messages.tag("name", campName(camp)),
                Messages.tag("status", status(camp)));
        try {
            TextDisplay label = camp.label;
            if (label == null || !label.isValid()) {
                double height = 1.2 + 2.2 * settings.mob(camp.kind).scale();
                label = world.spawn(camp.at.clone().add(0, height, 0), TextDisplay.class, display -> {
                    display.setPersistent(false);
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setShadowed(true);
                    display.setBrightness(new Display.Brightness(15, 15));
                });
                session.track(label);
                camp.label = label;
            }
            label.text(text);
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.FINE, "Could not show camp " + camp.site.id(), e);
        }
    }

    /**
     * The sidebar line about the big camps (buff camps and the epic): up ones first, then by the time to their
     * respawn ("레드 ✔ · 수호자 3:10 · 블루 4:42"), or null without big camps.
     */
    @Nullable Component hudLine() {
        List<Camp> big = new ArrayList<>();
        for (Camp camp : camps) {
            if (camp.kind.tier() != CampKind.Tier.SMALL) {
                big.add(camp);
            }
        }
        if (big.isEmpty()) {
            return null;
        }
        big.sort(Comparator.comparingLong(camp -> camp.alive ? Long.MIN_VALUE : camp.spawnAt));
        List<Component> parts = new ArrayList<>();
        for (Camp camp : big) {
            Component name = messages.get("camp.short." + camp.kind.id());
            parts.add(camp.alive ? messages.get("camp.hud-up", Messages.tag("name", name))
                    : messages.get("camp.hud-down", Messages.tag("name", name),
                            Messages.tag("time", Format.clock((Math.max(0, camp.spawnAt - now) + 19) / 20))));
        }
        return messages.get("camp.hud", Messages.tag("camps",
                Component.join(JoinConfiguration.separator(messages.get("camp.hud-separator")), parts)));
    }

    // ------------------------------------------------------------------ chunks

    /** Keeps the chunks around every camp loaded for the match (the monsters and labels are not persistent). */
    private void holdChunks() {
        World world = match.layout().world();
        for (Camp camp : camps) {
            int reach = (int) Math.ceil(camp.tier().leashRadius()) + 2;
            for (int cx = (camp.at.getBlockX() - reach) >> 4; cx <= (camp.at.getBlockX() + reach) >> 4; cx++) {
                for (int cz = (camp.at.getBlockZ() - reach) >> 4; cz <= (camp.at.getBlockZ() + reach) >> 4; cz++) {
                    if (world.addPluginChunkTicket(cx, cz, session.plugin())) {
                        heldChunks.add(new int[] {cx, cz});
                    }
                }
            }
        }
    }

    /** Spawns a camp right away (tests and admins). */
    void spawnNow(Camp camp) {
        camp.spawnAt = now;
        spawn(camp);
        label(camp, true);
    }
}
