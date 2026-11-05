package io.github.sx2xoox.mcpl.game.siege;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceSettings;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeLabData;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The 공성전 balance lab: every pool ability fights in a real 공성전 match on the test server (MockBukkit, flat world with
 * physics) at levels 1, 6 and 9, and the numbers that come out are written to {@link SiegeLabData#FILE} for
 * {@code SiegeBalanceReport}. Run it with {@code ./gradlew siegeLab} (all abilities, a few minutes) or
 * {@code ./gradlew siegeLab -Pabilities=pyro,frost} (only those rows are replaced).
 *
 * <p>Scenarios (the attacker never moves by itself and aims at its target every tick; the enemies stand still and are
 * healed to full after every tick, so nothing dies):
 * <ol>
 *     <li>S0 range (level 1 only): one active at 3 … 26 blocks; the farthest distance that still lands half of the best
 *     is the effective range, the duels are fought at the best distance.</li>
 *     <li>S1 duel, 30 s: the active whenever it is ready (attacker and target back on their spots before each cast).
 *     Damage on the target per second, crowd control on it, how far it was pushed, how far the attacker moved; the
 *     attacker and an ally behind it are kept at 70% health and take a probe hit of 2 every 0.5 s from the target:
 *     healing, shields (absorption), damage prevented against the probe and immune time are measured on both.</li>
 *     <li>S2 ultimate, 12 s: the ultimate at 0 and the active whenever ready; the ultimate's share is S2 minus S1's
 *     first 12 s.</li>
 *     <li>S3 cluster, 10 s: five enemies within 1.2 blocks of the spot; ultimate + active.</li>
 *     <li>S4 tower, 20 s: 5 blocks before an enemy outer tower (it does not shoot back); ultimate + active.</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "mcpl.siegeLab", matches = "true")
class SiegeBalanceLab {

    static final double PROBE = 2.0;
    /** The ultimate is tried at this distance too when the duel distance is farther. */
    static final double ULT_CLOSE = 4;
    static final int PROBE_TICKS = 10;
    static final double KEEP_HEALTH = 0.7;
    static final double[] DISTANCES = {26, 18, 12, 8, 5, 3};
    static final Map<Integer, Integer> XP = Map.of(1, 0, 6, 700, 9, 1600);

    @Test
    void measure() throws IOException {
        String only = System.getProperty("mcpl.siegeLab.abilities", "");
        List<String> ids = only.isBlank() ? SiegeSettings.DEFAULT_POOL : List.of(only.split(","));
        String out = System.getProperty("mcpl.siegeLab.out", "");
        java.nio.file.Path file = out.isBlank() ? SiegeLabData.FILE : java.nio.file.Path.of(out);
        Map<String, SiegeLabData.Row> rows = new LinkedHashMap<>(SiegeLabData.read(file).rows());
        for (String id : ids) {
            double[] engagement = null;
            for (int level : SiegeLabData.LEVELS) {
                long start = System.nanoTime();
                Run run = new Run(id.trim(), level, engagement);
                SiegeLabData.Row row = run.measure();
                engagement = new double[] {row.get("dist"), row.get("range")};
                rows.put(row.id() + ":" + level, row);
                System.out.printf("[lab] %s L%d %.1fs dmg30=%.1f ult=%.1f aoe5=%.1f tower=%.1f%n", id, level,
                        (System.nanoTime() - start) / 1e9, row.get("dmg30"), row.get("ult12") - row.get("skill12"),
                        row.get("aoe5"), row.get("tower20"));
            }
        }
        List<SiegeLabData.Row> ordered = new ArrayList<>();
        for (String id : SiegeSettings.DEFAULT_POOL) {
            for (int level : SiegeLabData.LEVELS) {
                SiegeLabData.Row row = rows.get(id + ":" + level);
                if (row != null) {
                    ordered.add(row);
                }
            }
        }
        SiegeLabData.write(file, SiegeLabData.fingerprint(SiegeLabData.registry()), ordered);
    }

    /** One ability at one level in a fresh match. */
    static final class Run implements Listener {
        final String id;
        final int level;
        final double[] engagement;
        final Map<String, Double> m = new LinkedHashMap<>();
        MatchHarness h;
        SiegeMatch match;
        SuperpowerMatch abilities;
        MatchContext ctx;
        Player attacker;
        Player ally;
        final List<Player> enemies = new ArrayList<>();
        /** Damage per enemy (by uuid) this tick. */
        final Map<UUID, Double> tickDamage = new HashMap<>();
        Location center;
        double probeBase;

        Run(String id, int level, double[] engagement) {
            this.id = id;
            this.level = level;
            this.engagement = engagement;
        }

        /** Records the damage on an enemy; a hit that would kill leaves it at 1 health (nothing dies in the lab). */
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            Entity victim = event.getEntity();
            if (victim instanceof Player player && enemies.contains(victim)) {
                double dealt = event.getFinalDamage();
                tickDamage.merge(victim.getUniqueId(), dealt, Double::sum);
                if (dealt >= player.getHealth() - 1) {
                    event.setDamage(Math.max(0, player.getHealth() - 1));
                }
            }
        }

        SiegeLabData.Row measure() {
            h = MatchHarness.flat();
            h.hub.plugin().getLogger().setLevel(Level.SEVERE);
            try {
                setUp();
                double dist;
                double range;
                if (engagement == null) {
                    double[] found = rangeProbe();
                    dist = found[0];
                    range = found[1];
                } else {
                    dist = engagement[0];
                    range = engagement[1];
                }
                m.put("dist", dist);
                m.put("range", range);
                m.put("probe", probeBase);
                Duel s1 = duel(dist, false, 600);
                Duel s2 = duel(dist, true, 240);
                double ultDist = dist;
                if (dist > ULT_CLOSE + 1) {
                    // an ultimate may need to be close: the better of the two counts
                    Duel near = duel(ULT_CLOSE, true, 240);
                    Duel nearSkill = duel(ULT_CLOSE, false, 240);
                    double far = s2.damageUpTo(240) - s1.damageUpTo(240) + s2.hard.sum(240) / 20 - s1.hard.sum(240) / 20;
                    double close = near.damageUpTo(240) - nearSkill.damageUpTo(240) + near.hard.sum(240) / 20
                            - nearSkill.hard.sum(240) / 20;
                    if (close > far + 0.5) {
                        s2 = near;
                        ultDist = ULT_CLOSE;
                        put(nearSkill, "12", 240);
                    }
                }
                m.put("ultDist", ultDist);
                put(s1, "30", 600);
                if (ultDist == dist) {
                    put(s1, "12", 240);
                }
                put(s2, "U", 240);
                m.put("casts", (double) s1.casts);
                m.put("dmg3", s1.damageUpTo(60));
                m.put("dmg10", s1.damageUpTo(200));
                m.put("dmg30", s1.damageUpTo(600));
                m.putIfAbsent("skill12", s1.damageUpTo(240));
                m.put("ult12", s2.damageUpTo(240));
                m.put("ultOk", s2.ultimateUsed ? 1.0 : 0.0);
                m.put("bonusHp", Math.max(s1.bonusHp, s2.bonusHp));
                m.put("dashes30", (double) s1.dashes);
                m.put("selfCost30", s1.selfCost);
                double[] cluster = cluster(Math.min(dist, ultDist));
                m.put("aoe1", cluster[0]);
                m.put("aoe3", cluster[0] + cluster[1] + cluster[2]);
                m.put("aoe5", Arrays.stream(cluster).sum());
                m.put("tower20", tower());
                double plain = melee(false, false);
                double boosted = melee(true, false);
                double skilled = melee(false, true);
                m.put("melee12", plain);
                m.put("ultMelee", Math.max(0, boosted - plain));
                m.put("skillMelee", Math.max(0, skilled - plain));
                return new SiegeLabData.Row(id, level, m, s1.perSecond(30), s2.perSecond(12));
            } finally {
                h.close();
            }
        }

        private void put(Duel d, String suffix, int ticks) {
            m.put("hard" + suffix, d.hard.sum(ticks) / 20);
            m.put("silence" + suffix, d.silence.sum(ticks) / 20);
            m.put("soft" + suffix, d.soft.sum(ticks) / 20);
            m.put("path" + suffix, d.path.sum(ticks));
            m.put("heal" + suffix, d.heal.sum(ticks));
            m.put("shield" + suffix, d.shield.sum(ticks));
            m.put("prevented" + suffix, d.prevented.sum(ticks));
            m.put("invuln" + suffix, d.invuln.sum(ticks) / 20);
            m.put("allyHeal" + suffix, d.allyHeal.sum(ticks));
            m.put("allyShield" + suffix, d.allyShield.sum(ticks));
            m.put("allyPrevented" + suffix, d.allyPrevented.sum(ticks));
            m.put("allyBuff" + suffix, d.allyBuff.sum(ticks) / 20);
            if (suffix.equals("12")) {
                m.put("skill12", d.damageUpTo(ticks));
            }
            if (!suffix.equals("12")) {
                m.put("knock" + suffix, d.knock);
            }
        }

        private void setUp() {
            h.registerDummy();
            h.settings().set("ultimate.per-second", 0);
            SiegeGame siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
            siege.sitesOverride(new FallbackLaneSites((int) FlatWorld.SURFACE));
            ConfigurationSection config = siege.config();
            config.set(SiegeSettings.ABILITY_POOL, List.of("dummy"));
            config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
            config.set(SiegeSettings.ROUND_SECONDS, 3600);
            config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
            config.set(SiegeSettings.ATTACK_DAMAGE, 0);
            config.set(SiegeSettings.PROTECTION_SECONDS, 0);
            config.set(SiegeSettings.REGEN_PER_SECOND, 0);
            config.set(SiegeProgressSettings.XP_PASSIVE, 0);
            // -Dmcpl.siegeLab.untuned=true: measure without the 공성전 tuning (to compare, into another file)
            config.set(SiegeBalanceSettings.TUNING, !Boolean.getBoolean("mcpl.siegeLab.untuned"));
            config.set("minions.enabled", false);
            config.set("jungle.enabled", false);
            String path = SiegeBalanceSettings.ABILITIES + "." + id;
            config.set(path + ".skill", 1);
            config.set(path + ".ultimate", 1);
            config.set(path + ".objective", 1);
            h.join(10);
            try {
                h.hub.games().start(siege, null);
            } catch (GameStartException e) {
                throw new AssertionError(e);
            }
            h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT,
                    2000, "the fight");
            match = siege.currentMatch();
            abilities = match.abilities();
            ctx = abilities.context();
            h.server.getPluginManager().registerEvents(this, h.hub.plugin());
            List<Player> team0 = members(0);
            List<Player> team1 = members(1);
            attacker = team0.get(0);
            ally = team0.get(1);
            enemies.addAll(team1);
            // everyone but the attacker fights with the neutral dummy (a pool smaller than a team is filled with
            // random abilities)
            for (int team = 0; team < 2; team++) {
                for (Player player : members(team)) {
                    abilities.replaceAbility(player, "dummy");
                }
            }
            for (Player player : List.of(attacker, ally)) {
                grant(player);
            }
            for (Player enemy : enemies) {
                grant(enemy);
            }
            Location blue = match.towers(LaneSites.Side.BLUE).getFirst().base();
            center = blue.clone();
            center.setX(0);
            center.setZ(0);
            center.setY(FlatWorld.SURFACE);
            // the probe against the attacker before it has its ability
            place(attacker, at(-6, 0), -90);
            place(enemies.getFirst(), at(0, 0), 90);
            h.ticks(2);
            keep(attacker);
            double before = attacker.getHealth() + attacker.getAbsorptionAmount();
            ctx.damage(enemies.getFirst(), attacker, PROBE);
            probeBase = before - attacker.getHealth() - attacker.getAbsorptionAmount();
            keep(attacker);
            if (!abilities.replaceAbility(attacker, id)) {
                throw new AssertionError(id + " could not be bound");
            }
            h.ticks(5);
        }

        private void grant(Player player) {
            int xp = XP.get(level);
            if (xp > 0) {
                match.progress().grant(player.getUniqueId(), xp);
            }
        }

        private List<Player> members(int team) {
            List<Player> out = new ArrayList<>();
            for (UUID member : match.teams().get(team).members()) {
                out.add(h.server.getPlayer(member));
            }
            return out;
        }

        Location at(double dx, double dz) {
            return center.clone().add(dx, 0, dz);
        }

        /** Unbinds and rebinds the ability (its entities and tasks gone), waits, clears effects and cooldowns. */
        private void reset() {
            abilities.replaceAbility(attacker, "dummy");
            h.ticks(60);
            for (Player p : allFighters()) {
                if (!p.isDead()) {
                    for (var effect : List.copyOf(p.getActivePotionEffects())) {
                        p.removePotionEffect(effect.getType());
                    }
                    p.setFireTicks(0);
                    p.setHealth(MatchContextAccess.maxHealth(p));
                }
            }
            if (!abilities.replaceAbility(attacker, id)) {
                throw new AssertionError(id + " could not be rebound");
            }
            ctx.reduceCooldown(attacker, 1000);
            h.ticks(2);
        }

        private List<Player> allFighters() {
            List<Player> out = new ArrayList<>(List.of(attacker, ally));
            out.addAll(enemies);
            return out;
        }

        // ------------------------------------------------------------ S0: range

        private double[] rangeProbe() {
            double[] value = new double[DISTANCES.length];
            for (int i = 0; i < DISTANCES.length; i++) {
                reset();
                Location spot = at(-6, 0);
                place(attacker, spot, -90);
                Player target = enemies.getFirst();
                place(target, at(-6 + DISTANCES[i], 0), 90);
                parkOthers(target);
                h.ticks(2);
                double damage = 0;
                double control = 0;
                boolean cast = false;
                for (int t = 0; t < 70; t++) {
                    tickDamage.clear();
                    aim(attacker, target);
                    aim(target, attacker);
                    if (!cast && t < 10) {
                        cast = abilities.useActive(attacker).succeeded();
                    }
                    h.ticks(1);
                    damage += tickDamage.getOrDefault(target.getUniqueId(), 0.0);
                    control += hard(target) ? 1 / 20.0 : 0;
                    heal(target);
                }
                value[i] = damage + control;
            }
            double best = Arrays.stream(value).max().orElse(0);
            if (best < 0.05) {
                return new double[] {5, 0};
            }
            double dist = 3;
            double range = 3;
            for (int i = DISTANCES.length - 1; i >= 0; i--) {
                if (value[i] >= 0.9 * best) {
                    dist = DISTANCES[i];
                }
                if (value[i] >= 0.5 * best) {
                    range = DISTANCES[i];
                }
            }
            // the farthest of the near-best distances
            for (int i = 0; i < DISTANCES.length; i++) {
                if (value[i] >= 0.9 * best) {
                    dist = DISTANCES[i];
                    break;
                }
            }
            return new double[] {dist, range};
        }

        private void parkOthers(Player keep) {
            int n = 0;
            for (Player enemy : enemies) {
                if (enemy != keep) {
                    place(enemy, at(-40 + 3 * n++, 34), 0);
                }
            }
        }

        // ------------------------------------------------------------ S1 / S2: the duel

        static final class Series {
            final double[] values = new double[700];

            void add(int tick, double value) {
                if (tick < values.length) {
                    values[tick] += value;
                }
            }

            double sum(int ticks) {
                double sum = 0;
                for (int i = 0; i < Math.min(ticks, values.length); i++) {
                    sum += values[i];
                }
                return sum;
            }
        }

        final class Duel {
            final Series damage = new Series();
            final Series hard = new Series();
            final Series silence = new Series();
            final Series soft = new Series();
            final Series path = new Series();
            final Series heal = new Series();
            final Series shield = new Series();
            final Series prevented = new Series();
            final Series invuln = new Series();
            final Series allyHeal = new Series();
            final Series allyShield = new Series();
            final Series allyPrevented = new Series();
            final Series allyBuff = new Series();
            int casts;
            int dashes;
            double knock;
            double bonusHp;
            double selfCost;
            boolean ultimateUsed;

            double damageUpTo(int ticks) {
                return damage.sum(ticks);
            }

            double[] perSecond(int seconds) {
                double[] out = new double[seconds];
                for (int s = 0; s < seconds; s++) {
                    for (int t = 0; t < 20; t++) {
                        out[s] += damage.values[s * 20 + t];
                    }
                }
                return out;
            }
        }

        /** Health keeping of a fighter that takes probes: last set health and absorption. */
        final class Keeper {
            final Player player;
            final double baseMax;
            double setHealth;
            double lastAbsorption;

            Keeper(Player player) {
                this.player = player;
                this.baseMax = MatchContextAccess.maxHealth(player);
                keep();
            }

            void keep() {
                double max = MatchContextAccess.maxHealth(player);
                setHealth = KEEP_HEALTH * max;
                if (!player.isDead()) {
                    player.setHealth(setHealth);
                }
                lastAbsorption = player.getAbsorptionAmount();
            }

            /** {heal, shield gained, self cost} since the last keep. */
            double[] read() {
                double health = player.isDead() ? 0 : player.getHealth();
                double absorption = player.getAbsorptionAmount();
                double[] out = {Math.max(0, health - setHealth), Math.max(0, absorption - lastAbsorption),
                        Math.max(0, setHealth - health)};
                lastAbsorption = absorption;
                return out;
            }

            /** {prevented, immune ticks} of one probe from {@code source}. */
            double[] probe(Player source) {
                if (player.isDead()) {
                    return new double[] {0, 0};
                }
                double before = player.getHealth() + player.getAbsorptionAmount();
                ctx.damage(source, player, PROBE);
                double after = player.isDead() ? 0 : player.getHealth() + player.getAbsorptionAmount();
                lastAbsorption = player.getAbsorptionAmount();
                double loss = before - after;
                return new double[] {Math.max(0, probeBase - loss), loss < 0.05 * probeBase ? PROBE_TICKS : 0};
            }
        }

        private Duel duel(double dist, boolean ultimate, int ticks) {
            reset();
            Duel d = new Duel();
            Location spotA = at(-6, 0);
            Location spotL = at(-8, 0);
            Player target = enemies.getFirst();
            Location spotE = at(-6 + dist, 0);
            place(attacker, spotA, -90);
            place(ally, spotL, -90);
            place(target, spotE, 90);
            parkOthers(target);
            h.ticks(2);
            Keeper self = new Keeper(attacker);
            Keeper mate = new Keeper(ally);
            Location last = attacker.getLocation();
            Location castAt = spotA;
            boolean castMoved = false;
            for (int t = 0; t < ticks; t++) {
                tickDamage.clear();
                if (ultimate && !d.ultimateUsed && t < 40) {
                    aim(attacker, target);
                    abilities.setGauge(attacker, 100);
                    d.ultimateUsed = abilities.useUltimate(attacker).succeeded();
                }
                if (ctx.cooldownRemaining(attacker) <= 0 && !ctx.isSilenced(attacker)
                        && !(ultimate && t < 2 && !d.ultimateUsed)) {
                    if (attacker.getLocation().distance(spotA) > 0.5 || target.getLocation().distance(spotE) > 0.5) {
                        place(attacker, spotA, -90);
                        place(target, spotE, 90);
                    }
                    aim(attacker, target);
                    Location before = attacker.getLocation();
                    last = before;
                    if (abilities.useActive(attacker).succeeded()) {
                        d.casts++;
                        castAt = before;
                        castMoved = false;
                    }
                }
                aim(attacker, target);
                aim(target, attacker);
                h.ticks(1);
                d.damage.add(t, tickDamage.getOrDefault(target.getUniqueId(), 0.0));
                // control on the target
                if (hard(target)) {
                    d.hard.add(t, 1);
                } else if (ctx.isSilenced(target)) {
                    d.silence.add(t, 1);
                } else {
                    d.soft.add(t, softness(target));
                }
                d.knock = Math.max(d.knock, flat(target.getLocation()).distance(flat(spotE)));
                // the attacker's movement
                Location now = attacker.getLocation();
                if (now.getWorld() == last.getWorld()) {
                    double step = now.distance(last);
                    if (step > 0.05 && step < 40) {
                        d.path.add(t, step);
                    }
                }
                if (!castMoved && now.distance(castAt) >= 2.5) {
                    castMoved = true;
                    d.dashes++;
                }
                last = now;
                // survival and support against the probes
                double[] own = self.read();
                d.heal.add(t, own[0]);
                d.shield.add(t, own[1]);
                d.selfCost += own[2];
                double[] other = mate.read();
                d.allyHeal.add(t, other[0]);
                d.allyShield.add(t, other[1]);
                if (buffed(ally)) {
                    d.allyBuff.add(t, 1);
                }
                d.bonusHp = Math.max(d.bonusHp, MatchContextAccess.maxHealth(attacker) - self.baseMax);
                if (t % PROBE_TICKS == 0) {
                    double[] p = self.probe(target);
                    d.prevented.add(t, p[0]);
                    d.invuln.add(t, p[1]);
                    double[] q = mate.probe(target);
                    d.allyPrevented.add(t, q[0]);
                }
                self.keep();
                mate.keep();
                for (Player enemy : enemies) {
                    heal(enemy);
                }
            }
            return d;
        }

        // ------------------------------------------------------------ S3: cluster

        private double[] cluster(double dist) {
            reset();
            Location spotA = at(-6, 0);
            place(attacker, spotA, -90);
            place(ally, at(-30, 30), -90);
            double[][] offsets = {{0, 0}, {1.2, 0}, {-1.2, 0.4}, {0, 1.2}, {0.2, -1.2}};
            for (int i = 0; i < enemies.size(); i++) {
                place(enemies.get(i), at(-6 + dist + offsets[i][0], offsets[i][1]), 90);
            }
            h.ticks(2);
            Player target = enemies.getFirst();
            Map<UUID, Double> total = new HashMap<>();
            boolean ult = false;
            for (int t = 0; t < 200; t++) {
                tickDamage.clear();
                if (!ult && t < 40) {
                    aim(attacker, target);
                    abilities.setGauge(attacker, 100);
                    ult = abilities.useUltimate(attacker).succeeded();
                }
                if (t >= 2 && ctx.cooldownRemaining(attacker) <= 0 && !ctx.isSilenced(attacker)) {
                    if (attacker.getLocation().distance(spotA) > 0.5) {
                        place(attacker, spotA, -90);
                    }
                    aim(attacker, target);
                    abilities.useActive(attacker);
                }
                aim(attacker, target);
                h.ticks(1);
                tickDamage.forEach((uuid, damage) -> total.merge(uuid, damage, Double::sum));
                for (Player enemy : enemies) {
                    heal(enemy);
                }
                keep(attacker);
            }
            double[] out = new double[5];
            List<Double> sorted = new ArrayList<>(total.values());
            sorted.sort((a, b) -> Double.compare(b, a));
            for (int i = 0; i < Math.min(5, sorted.size()); i++) {
                out[i] = sorted.get(i);
            }
            return out;
        }

        // ------------------------------------------------------------ S5: melee (basic attacks) with the ultimate

        /**
         * Damage on one enemy at 2.5 blocks over 12 s of basic attacks: alone, after the ultimate, or with the active
         * on cooldown (what a buff of the next hits or of melee adds, with the casts' own hits).
         */
        private double melee(boolean ultimate, boolean active) {
            reset();
            Location spotA = at(-6, 0);
            Location spotE = at(-3.5, 0);
            Player target = enemies.getFirst();
            place(attacker, spotA, -90);
            place(target, spotE, 90);
            parkOthers(target);
            h.ticks(2);
            double total = 0;
            boolean ult = !ultimate;
            for (int t = 0; t < 240; t++) {
                tickDamage.clear();
                if (!ult && t < 40) {
                    aim(attacker, target);
                    abilities.setGauge(attacker, 100);
                    ult = abilities.useUltimate(attacker).succeeded();
                }
                if (attacker.getLocation().distance(spotA) > 1.5 || target.getLocation().distance(spotE) > 1.5) {
                    place(attacker, spotA, -90);
                    place(target, spotE, 90);
                }
                aim(attacker, target);
                if (active && ctx.cooldownRemaining(attacker) <= 0 && !ctx.isSilenced(attacker)) {
                    abilities.useActive(attacker);
                    aim(attacker, target);
                }
                abilities.basicAttack(attacker);
                h.ticks(1);
                total += tickDamage.getOrDefault(target.getUniqueId(), 0.0);
                for (Player enemy : enemies) {
                    heal(enemy);
                }
                keep(attacker);
            }
            return total;
        }

        // ------------------------------------------------------------ S4: tower

        private double tower() {
            reset();
            LaneSites.Side enemySide = match.side(enemies.getFirst().getUniqueId());
            Tower tower = null;
            for (Tower candidate : match.towers(enemySide)) {
                if (candidate.kind() == Tower.Kind.OUTER && candidate.lane() == LaneSites.Lane.TOP) {
                    tower = candidate;
                }
            }
            if (tower == null || tower.hitbox() == null) {
                return 0;
            }
            Location spot = tower.base().clone().add(enemySide == LaneSites.Side.RED ? -5 : 5, 0, 0);
            spot.setY(FlatWorld.SURFACE);
            place(attacker, spot, enemySide == LaneSites.Side.RED ? -90 : 90);
            for (Player enemy : enemies) {
                place(enemy, at(-40, -34 + enemies.indexOf(enemy) * 3), 0);
            }
            place(ally, at(-34, 34), 0);
            h.ticks(2);
            double before = tower.health();
            boolean ult = false;
            for (int t = 0; t < 400 && !tower.isDestroyed(); t++) {
                Entity hitbox = tower.hitbox();
                if (hitbox == null) {
                    break;
                }
                if (!ult && t < 40) {
                    aim(attacker, hitbox);
                    abilities.setGauge(attacker, 100);
                    ult = abilities.useUltimate(attacker).succeeded();
                }
                if (t >= 2 && ctx.cooldownRemaining(attacker) <= 0 && !ctx.isSilenced(attacker)) {
                    if (attacker.getLocation().distance(spot) > 0.5) {
                        place(attacker, spot, enemySide == LaneSites.Side.RED ? -90 : 90);
                    }
                    aim(attacker, hitbox);
                    abilities.useActive(attacker);
                }
                aim(attacker, hitbox);
                h.ticks(1);
                keep(attacker);
            }
            return before - tower.health();
        }

        // ------------------------------------------------------------ helpers

        private boolean hard(Player target) {
            return ctx.isMovementLocked(target, null) || target.hasPotionEffect(PotionEffectType.LEVITATION);
        }

        /** 0 … 1: how slowed or blinded the target is this tick. */
        private double softness(Player target) {
            double slow = 0;
            AttributeInstance speed = target.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null && speed.getBaseValue() > 0) {
                slow = Math.max(0, 1 - speed.getValue() / speed.getBaseValue());
            }
            var slowness = target.getPotionEffect(PotionEffectType.SLOWNESS);
            if (slowness != null) {
                slow = Math.max(slow, Math.min(0.9, 0.15 * (slowness.getAmplifier() + 1)));
            }
            if (ctx.isJumpLocked(target, null)) {
                slow = Math.max(slow, 0.3);
            }
            double blind = target.hasPotionEffect(PotionEffectType.BLINDNESS)
                    || target.hasPotionEffect(PotionEffectType.DARKNESS) ? 0.5 : 0;
            return Math.min(1, slow + blind);
        }

        private boolean buffed(Player player) {
            for (PotionEffectType type : List.of(PotionEffectType.SPEED, PotionEffectType.STRENGTH,
                    PotionEffectType.RESISTANCE, PotionEffectType.REGENERATION, PotionEffectType.ABSORPTION)) {
                if (player.hasPotionEffect(type)) {
                    return true;
                }
            }
            AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
            return speed != null && speed.getValue() > speed.getBaseValue() * 1.05;
        }

        private void heal(Player enemy) {
            if (!enemy.isDead()) {
                enemy.setHealth(MatchContextAccess.maxHealth(enemy));
            }
        }

        private void keep(Player player) {
            if (!player.isDead()) {
                player.setHealth(KEEP_HEALTH * MatchContextAccess.maxHealth(player));
            }
        }

        private static Location flat(Location location) {
            Location out = location.clone();
            out.setY(0);
            return out;
        }

        private void place(Player player, Location spot, float yaw) {
            if (player.isDead()) {
                return;
            }
            Location to = spot.clone();
            to.setYaw(yaw);
            to.setPitch(0);
            player.teleport(to);
            player.setVelocity(new Vector());
            player.setFallDistance(0);
        }

        private static void aim(Player player, Entity target) {
            Location point = target.getLocation().add(0, target.getHeight() * 0.6, 0);
            Vector direction = point.toVector().subtract(player.getEyeLocation().toVector());
            if (direction.lengthSquared() < 1.0E-6) {
                return;
            }
            Location look = player.getLocation();
            look.setDirection(direction);
            player.setRotation(look.getYaw(), look.getPitch());
        }
    }

    /** {@link MatchContext}'s max health helper (package-private there). */
    static final class MatchContextAccess {
        static double maxHealth(LivingEntity entity) {
            AttributeInstance instance = entity.getAttribute(Attribute.MAX_HEALTH);
            return instance == null ? 20.0 : instance.getValue();
        }
    }
}
