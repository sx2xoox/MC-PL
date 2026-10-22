package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 흡혈귀 — sustain through aggression. Every hit heals a little, the active tethers an aimed enemy and drains its
 * health while the line of sight holds, and the ultimate turns all damage dealt into healing.
 *
 * <p>Look: blood crimson. Lifesteal sends blood motes from the victim into the vampire's chest. The drain marks the
 * victim with a blood ring and binds the two with a twisting double strand of blood; every gulp sends a glowing blood
 * drop along it. The feast spills a crimson mist and three display bats circle the vampire, flapping, until they fly
 * off when it ends.
 */
public final class VampireAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("vampire", "흡혈귀", Material.FERMENTED_SPIDER_EYE)
            .color(TextColor.color(0xB0174F))
            .difficulty(2)
            .passive("흡혈", "적에게 입힌 모든 피해의 {lifesteal}%만큼 체력을 회복합니다.")
            .active("생명 흡수",
                    "{drain_range}블록 안의 조준한 적과 {drain_duration}초간 연결되어 {drain_interval}초마다 체력을 "
                            + "{drain_damage}씩 빼앗아 회복합니다. 시야에서 벗어나거나 너무 멀어지면 끊어집니다.",
                    10)
            .ultimate("피의 축제", "{ult_duration}초간 흡혈 효과가 {ult_lifesteal}%로 오르고 신속 I을 얻습니다.")
            .ultimateNotStandalone()
            .param("lifesteal", 20)
            .param("drain_range", 15)
            .param("drain_duration", 2)
            .param("drain_interval", 0.25)
            .param("drain_damage", 1)
            .param("ult_duration", 8)
            .param("ult_lifesteal", 100)
            .build();

    private static final double TAU = Math.PI * 2;

    /** Extra blocks a drained enemy may move beyond the aim range before the tether snaps. */
    private static final double DRAIN_LEASH = 3;
    private static final int FEAST_SPEED_AMPLIFIER = 0;
    /** Minimum ticks between two lifesteal slurp sounds for the owner. */
    private static final long SLURP_COOLDOWN = 10;

    // Visuals
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFF4D6D, 0xB0001E, 0x3A0010);
    private static final Particle.DustOptions CORE_DUST = new Particle.DustOptions(PALETTE.core(), 0.6f);
    private static final Particle.DustOptions STRAND_DUST = new Particle.DustOptions(PALETTE.body(), 0.7f);
    /** Radius of the drain's twisting double strand around the beam line. */
    private static final double STRAND_RADIUS = 0.18;
    private static final double STRAND_POINTS_PER_BLOCK = 1.5;
    private static final float DROP_SIZE = 0.22f;
    private static final int HEAL_MOTES = 4;
    // Feast
    private static final int BATS = 3;
    private static final float WING_SPAN = 0.42f;
    private static final float WING_CHORD = 0.26f;
    private static final float WING_THICKNESS = 0.02f;
    private static final float WING_UP = 40;
    private static final float WING_DOWN = -20;
    private static final int FLAP_TICKS = 3;
    private static final double BAT_SPEED = 0.17;
    private static final long FEAST_MIST_PERIOD = 4;
    private static final int BAT_LEAVE_TICKS = 8;

    /** True while the drain deals its own damage: the drain heals by itself, the passive must not add to it. */
    private boolean draining;
    private long feastUntil = -1;
    private long lastSlurp = Long.MIN_VALUE;
    private @Nullable Drain drain;
    private @Nullable Feast feast;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        draining = false;
        feastUntil = -1;
        drain = null;
        feast = null;
    }

    // ------------------------------------------------------------------ passive: lifesteal

    private boolean isFeasting() {
        return feastUntil >= 0 && ctx().currentTick() < feastUntil;
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || draining || !ctx().isEnemy(player(), victim)) {
            return;
        }
        boolean feasting = isFeasting();
        double share = Math.max(0, params().num(feasting ? "ult_lifesteal" : "lifesteal")) / 100.0;
        if (share <= 0) {
            return;
        }
        DamageCause cause = event.getCause();
        boolean melee = !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && (cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK);
        double before = victim.getHealth() + victim.getAbsorptionAmount();
        // Heal once the event has fully resolved, so cancelled or reduced hits only heal what actually landed.
        ctx().runLater(player(), 1, () -> {
            if (event.isCancelled() || !ctx().isAlive(player())) {
                return;
            }
            double amount = Math.min(damageWithAbsorption(event), before) * share;
            if (amount < 0.05) {
                return;
            }
            ctx().heal(player(), amount);
            siphonVisual(victim, amount);
            if (feasting && melee && victim.isValid()) {
                Location body = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
                victim.getWorld().spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 14), 0.25, 0.3, 0.25, 0.15,
                        Material.REDSTONE_BLOCK.createBlockData());
                victim.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 6), 0.25, 0.3,
                        0.25, 0, PALETTE.fade(1.2f));
                victim.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, body, Vfx.n(ctx(), 2), 0.2, 0.2, 0.2,
                        0.1);
            }
        });
    }

    /**
     * What the hit took off health and absorption hearts together. {@link EntityDamageEvent#getFinalDamage()}
     * leaves out the part absorption soaked up (the negative {@code ABSORPTION} modifier).
     */
    @SuppressWarnings("deprecation") // DamageModifier is the only exact source of the absorbed amount; no replacement
    private static double damageWithAbsorption(EntityDamageEvent event) {
        return Math.max(0, event.getFinalDamage() - event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION));
    }

    /** Blood motes flowing from {@code from} into the owner's chest, scaled with the healed amount, and a slurp. */
    private void siphonVisual(LivingEntity from, double amount) {
        Player owner = player();
        Location to = chest(owner);
        if (!from.isValid() || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        Location start = chest(from);
        int duration = (int) Math.max(4, Math.min(16, start.distance(to) * 2.5));
        int motes = Vfx.n(ctx(), 2 + (int) Math.min(6, Math.round(amount * 1.5)));
        for (int i = 0; i < motes; i++) {
            Vfx.trail(Vfx.scatter(start, 0.25).add(0, (i % 3 - 1) * 0.2, 0), to,
                    i % 2 == 0 ? PALETTE.body() : PALETTE.core(), duration + i % 3, false);
        }
        ctx().runLater(owner, duration, () -> healMotes(owner));
        long now = ctx().currentTick();
        if (now - lastSlurp >= SLURP_COOLDOWN) {
            lastSlurp = now;
            owner.playSound(owner, Sound.ITEM_HONEY_BOTTLE_DRINK, SoundCategory.PLAYERS, 0.4f, 1.5f + Fx.jitter(0.1f));
        }
    }

    /** Small motes of blood light sinking into the owner's chest: the heal arriving. */
    private void healMotes(Player owner) {
        Location chest = chest(owner);
        double phase = ThreadLocalRandom.current().nextDouble(TAU);
        int motes = Vfx.n(ctx(), HEAL_MOTES);
        for (int i = 0; i < motes; i++) {
            double angle = phase + i * TAU / motes;
            Location from = chest.clone().add(Math.cos(angle) * 0.55, 0.25, Math.sin(angle) * 0.55);
            Vfx.trail(from, chest, PALETTE.core(), 4, false);
        }
    }

    // ------------------------------------------------------------------ active: life drain

    @Override
    public ActivationResult onActive() {
        double range = Math.max(1, params().num("drain_range"));
        LivingEntity target = ctx().findTarget(player(), range);
        if (target == null) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안의 적을 조준하세요");
        }
        Drain previous = drain;
        if (previous != null) {
            previous.stop();
        }
        Drain created = new Drain(target, range);
        drain = created;
        created.begin();
        return ActivationResult.success();
    }

    /** A tether between the owner and one enemy that siphons health at a fixed interval. */
    private final class Drain {

        private final LivingEntity target;
        private final double range;
        private final long interval = Math.max(1, params().ticks("drain_interval"));
        private final double damage = Math.max(0, params().num("drain_damage"));
        private double total;
        private boolean stopped;
        private boolean snapped;

        Drain(LivingEntity target, double range) {
            this.target = target;
            this.range = range;
        }

        void begin() {
            Player owner = player();
            Fx.sound(owner.getLocation(), Sound.ENTITY_VEX_CHARGE, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(target.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.8f, 1.6f + Fx.jitter(0.1f));
            Fx.sound(target.getLocation(), Sound.ENTITY_PHANTOM_BITE, 0.7f, 0.8f + Fx.jitter(0.1f));
            // The mark: a blood ring under the victim and a burst at its chest.
            Vfx.ring(ctx(), target.getLocation().add(0, 0.1, 0), target.getWidth() * 0.5 + 0.45, 16, 0,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f), true);
            Vfx.burst(ctx(), chest(target), PALETTE, 0.5, Particle.DAMAGE_INDICATOR, false);
            long duration = Math.max(1, params().ticks("drain_duration"));
            Loop.repeat(ctx(), player(), 1, 1, duration, this::tick, this::finish);
        }

        void stop() {
            stopped = true;
        }

        private boolean tick(long tick) {
            if (stopped) {
                return false;
            }
            if (!holds()) {
                snapped = true;
                return false;
            }
            beam(tick);
            if ((tick + 1) % interval == 0) {
                siphon();
            }
            return !stopped;
        }

        private boolean holds() {
            Player owner = player();
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(owner.getWorld())) {
                return false;
            }
            double leash = range + DRAIN_LEASH;
            return owner.getEyeLocation().distanceSquared(chest(target)) <= leash * leash
                    && owner.hasLineOfSight(target);
        }

        /** The tether: motes of blood flowing to the vampire, wrapped in a twisting double strand. */
        private void beam(long tick) {
            Location from = chest(target);
            Location to = chest(player());
            int travel = (int) Math.max(5, Math.min(20, from.distance(to) * 1.5));
            int motes = Vfx.n(ctx(), 2);
            for (int i = 0; i < motes; i++) {
                Vfx.trail(Vfx.scatter(from, 0.15), to, (tick + i) % 2 == 0 ? PALETTE.body() : PALETTE.core(),
                        travel, false);
            }
            if (tick % 2 == 0) {
                strands(from, to, tick * 0.6);
            }
        }

        /** Two blood strands twisting around the line {@code from} - {@code to}. */
        private void strands(Location from, Location to, double phase) {
            Vector axis = to.toVector().subtract(from.toVector());
            double length = axis.length();
            if (length < 0.5) {
                return;
            }
            axis.multiply(1 / length);
            Vector side = axis.clone().crossProduct(new Vector(0, 1, 0));
            if (side.lengthSquared() < 1.0E-6) {
                side = new Vector(1, 0, 0);
            }
            side.normalize();
            Vector up = side.clone().crossProduct(axis).normalize();
            int count = Math.max(4, (int) Math.round(length * STRAND_POINTS_PER_BLOCK * ctx().effectScale()));
            for (int i = 0; i <= count; i++) {
                double t = (double) i / count;
                // Thin at both ends, widest in the middle.
                double radius = STRAND_RADIUS * Math.sin(Math.PI * t);
                for (int strand = 0; strand < 2; strand++) {
                    double angle = phase + t * length * 2.2 + strand * Math.PI;
                    Location at = from.clone().add(axis.clone().multiply(t * length))
                            .add(side.clone().multiply(Math.cos(angle) * radius))
                            .add(up.clone().multiply(Math.sin(angle) * radius));
                    Vfx.dot(at, Particle.DUST, strand == 0 ? STRAND_DUST : CORE_DUST, false);
                }
            }
        }

        private void siphon() {
            Player owner = player();
            double before = target.getHealth() + target.getAbsorptionAmount();
            draining = true;
            try {
                Combat.hit(ctx(), player(), target, damage);
            } finally {
                draining = false;
            }
            boolean alive = target.isValid() && !target.isDead();
            double after = alive ? target.getHealth() + target.getAbsorptionAmount() : 0;
            double drained = Math.max(0, before - after);
            Location at = chest(target);
            Fx.sound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 0.9f, 1.4f + Fx.jitter(0.1f));
            if (drained <= 0) {
                return;
            }
            ctx().heal(owner, drained);
            total += drained;
            at.getWorld().spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 5), 0.2, 0.25, 0.2, 0.1,
                    Material.REDSTONE_BLOCK.createBlockData());
            bloodDrop(at, chest(owner));
            owner.playSound(owner, Sound.ITEM_HONEY_BOTTLE_DRINK, SoundCategory.PLAYERS, 0.45f, 1.4f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<#B0174F>생명 흡수</#B0174F> <gray>+" + AbilityInfo.formatNumber(total)
                    + "</gray>"));
        }

        private void finish() {
            if (drain == this) {
                drain = null;
            }
            if (stopped) {
                return;
            }
            Player owner = player();
            if (snapped) {
                snapVisual(owner);
                Fx.sound(owner.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.7f, 1.6f + Fx.jitter(0.05f));
                owner.sendActionBar(Text.mm("<gray>흡수가 끊겼습니다</gray> <#B0174F>+"
                        + AbilityInfo.formatNumber(total) + "</#B0174F>"));
            } else {
                Fx.sound(owner.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.8f, 1.1f + Fx.jitter(0.05f));
                Vfx.ring(ctx(), chest(owner), 0.55, 12, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f),
                        false);
            }
        }

        /** The tether tore: the strand bursts in the middle into dark blood and smoke. */
        private void snapVisual(Player owner) {
            Location a = chest(owner);
            if (!target.isValid() || !target.getWorld().equals(a.getWorld())) {
                return;
            }
            Location b = chest(target);
            Location middle = a.clone().add(b).multiply(0.5);
            World world = middle.getWorld();
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, middle, Vfx.n(ctx(), 12), 0.3, 0.3, 0.3, 0,
                    PALETTE.fade(1.2f));
            world.spawnParticle(Particle.SMOKE, middle, Vfx.n(ctx(), 6), 0.2, 0.2, 0.2, 0.03);
        }
    }

    /** A glowing blood drop (a turning red cube) flying from {@code from} into the vampire's chest. */
    private void bloodDrop(Location from, Location to) {
        if (!from.getWorld().equals(to.getWorld())) {
            return;
        }
        Player owner = player();
        int travel = (int) Math.max(3, Math.min(10, from.distance(to) * 0.6));
        Location start = from.clone();
        start.setYaw(0);
        start.setPitch(0);
        BlockDisplay drop = Displays.block(ctx(), owner, start, Material.REDSTONE_BLOCK.createBlockData(),
                Displays.transform(0, 0, 0, DROP_SIZE, DROP_SIZE, DROP_SIZE, 45, 35, 0), travel + 6);
        drop.setTeleportDuration(travel);
        ctx().runLater(owner, 1, () -> {
            if (!drop.isValid()) {
                return;
            }
            Location end = chest(owner);
            end.setYaw(0);
            end.setPitch(0);
            if (end.getWorld().equals(drop.getWorld())) {
                drop.teleport(end);
            }
            Displays.animate(drop, Displays.transform(0, 0, 0, DROP_SIZE, DROP_SIZE, DROP_SIZE, 165, 35, 0), travel);
        });
        ctx().runLater(owner, travel + 1, () -> {
            if (drop.isValid()) {
                Displays.shrinkAway(ctx(), owner, drop, 3);
                healMotes(owner);
            }
        });
    }

    // ------------------------------------------------------------------ ultimate: blood feast

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        long duration = Math.max(1, params().ticks("ult_duration"));
        feastUntil = ctx().currentTick() + duration;
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) Math.min(Integer.MAX_VALUE, duration),
                FEAST_SPEED_AMPLIFIER, false, true, true));
        Feast previous = feast;
        if (previous != null) {
            previous.stop();
        }
        Feast created = new Feast(duration);
        feast = created;
        created.begin(owner);
        return ActivationResult.success();
    }

    /** The visuals of one 피의 축제: the burst, the crimson mist and three bats circling the vampire. */
    private final class Feast {

        private record Bat(BlockDisplay left, BlockDisplay right, BlockDisplay body, double phase, double radius,
                           double height, int turn) {
        }

        private final long duration;
        private final List<Bat> bats = new ArrayList<>();
        private boolean stopped;

        Feast(long duration) {
            this.duration = duration;
        }

        void begin(Player owner) {
            cast(owner);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double start = random.nextDouble(TAU);
            for (int i = 0; i < BATS; i++) {
                bats.add(spawnBat(owner, start + i * TAU / BATS, 1.1 + 0.25 * i, 1.45 + 0.3 * i, i % 2 == 0 ? 1 : -1));
            }
            Loop.repeat(ctx(), owner, 1, 1, duration, this::tick, () -> end(owner));
        }

        void stop() {
            stopped = true;
        }

        /** The feast erupts: a flash, a crimson mist rolling outwards and a dark smoke ring. */
        private void cast(Player owner) {
            Location at = owner.getLocation();
            World world = at.getWorld();
            Location body = chest(owner);
            Fx.sound(at, Sound.ENTITY_BAT_TAKEOFF, 1.2f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 0.6f);
            Fx.sound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 1.6f, 0.9f);
            Fx.sound(at, Sound.ENTITY_PHANTOM_FLAP, 1.0f, 0.6f + Fx.jitter(0.05f));
            world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, PALETTE.core(), true);
            Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 30, 0.6, 0, PALETTE.fade(1.5f), true);
            Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.2, 0), 0.5, 3.2, 8, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.fade(1.8f), true);
            Vfx.ring(ctx(), at.clone().add(0, 0.3, 0), 1.2, 12, 0, Particle.LARGE_SMOKE, null, true);
        }

        private boolean tick(long tick) {
            if (stopped || !isFeasting()) {
                return false;
            }
            Player owner = player();
            Location at = owner.getLocation();
            boolean flap = tick % FLAP_TICKS == 0;
            boolean up = (tick / FLAP_TICKS) % 2 == 0;
            for (Bat bat : bats) {
                Location spot = batSpot(at, bat, tick);
                for (BlockDisplay part : List.of(bat.left(), bat.right(), bat.body())) {
                    if (part.isValid()) {
                        part.teleport(spot);
                    }
                }
                if (flap) {
                    float angle = up ? WING_UP : WING_DOWN;
                    Displays.animate(bat.left(), wing(true, angle), FLAP_TICKS);
                    Displays.animate(bat.right(), wing(false, angle), FLAP_TICKS);
                }
                Vfx.dot(spot, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.8f), false);
            }
            if (tick % FEAST_MIST_PERIOD == 0) {
                mist(at);
            }
            return true;
        }

        /** A low crimson mist creeping around the vampire's feet. */
        private void mist(Location at) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int puffs = Vfx.n(ctx(), 4);
            for (int i = 0; i < puffs; i++) {
                Location spot = Vfx.scatter(at, 1.1).add(0, random.nextDouble(0.1, 0.5), 0);
                Vfx.dot(spot, Particle.DUST_COLOR_TRANSITION, new Particle.DustTransition(PALETTE.body(),
                        PALETTE.edge(), (float) random.nextDouble(1.4, 2.0)), false);
            }
        }

        private void end(Player owner) {
            for (Bat bat : bats) {
                batLeaves(owner, bat);
            }
            bats.clear();
            if (feast == this) {
                feast = null;
            }
            if (stopped) {
                return;
            }
            Location at = owner.getLocation();
            Fx.sound(at, Sound.ENTITY_BAT_AMBIENT, 0.8f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ENTITY_BAT_TAKEOFF, 0.8f, 1.0f + Fx.jitter(0.05f));
            Vfx.ring(ctx(), at.clone().add(0, 0.2, 0), 1.0, 12, 0, Particle.SMOKE, null, false);
            owner.sendActionBar(Text.mm("<gray>피의 축제가 끝났습니다</gray>"));
        }

        /** A bat flies off outwards and upwards, shrinking away. */
        private void batLeaves(Player owner, Bat bat) {
            Location center = owner.getLocation();
            Location from = bat.left().isValid() ? bat.left().getLocation() : center;
            Vector out = from.toVector().subtract(center.toVector()).setY(0);
            if (out.lengthSquared() < 1.0E-6) {
                out = new Vector(1, 0, 0);
            }
            Location away = from.clone().add(out.normalize().multiply(3)).add(0, 2.5, 0);
            away.setDirection(away.toVector().subtract(from.toVector()));
            away.setPitch(0);
            for (BlockDisplay part : List.of(bat.left(), bat.right(), bat.body())) {
                if (part.isValid()) {
                    part.setTeleportDuration(BAT_LEAVE_TICKS);
                    part.teleport(away);
                    Displays.shrinkAway(ctx(), owner, part, BAT_LEAVE_TICKS);
                }
            }
        }

        private Location batSpot(Location center, Bat bat, long tick) {
            return orbit(center, bat.phase(), bat.radius(), bat.height(), bat.turn(), tick);
        }

        private Bat spawnBat(Player owner, double phase, double radius, double height, int turn) {
            long lifetime = duration + BAT_LEAVE_TICKS + 10;
            Location spot = orbit(owner.getLocation(), phase, radius, height, turn, 0);
            BlockData membrane = Material.BLACK_CONCRETE.createBlockData();
            BlockDisplay left = Displays.block(ctx(), owner, spot, membrane, wing(true, WING_DOWN), lifetime);
            BlockDisplay right = Displays.block(ctx(), owner, spot, membrane, wing(false, WING_DOWN), lifetime);
            BlockDisplay body = Displays.block(ctx(), owner, spot, Material.REDSTONE_BLOCK.createBlockData(),
                    Displays.transform(0, 0, 0, 0.11f, 0.1f, 0.2f, 0, 0, 0), lifetime);
            return new Bat(left, right, body, phase, radius, height, turn);
        }
    }

    /**
     * Where a bat flies {@code tick} ticks in: circling {@code center} ({@code turn} 1 counter-clockwise, -1
     * clockwise seen from above), bobbing, and facing its flight direction.
     */
    private static Location orbit(Location center, double phase, double radius, double height, int turn, long tick) {
        double angle = phase + tick * BAT_SPEED * turn;
        double bob = Math.sin(tick * 0.25 + phase) * 0.2;
        Location spot = center.clone().add(Math.cos(angle) * radius, height + bob, Math.sin(angle) * radius);
        double dx = -Math.sin(angle) * turn;
        double dz = Math.cos(angle) * turn;
        spot.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        spot.setPitch(0);
        return spot;
    }

    /**
     * One bat wing hinged at the entity origin along its flight axis (local z), spanning sideways (local x) and
     * raised by {@code degrees} (positive lifts the wing tip).
     */
    private static Transformation wing(boolean left, float degrees) {
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.toRadians(left ? -degrees : degrees));
        // The unit block is moved so the hinge edge sits on the z axis, then rotated around it.
        Vector3f corner = new Vector3f(left ? -WING_SPAN : 0, -WING_THICKNESS / 2, -WING_CHORD / 2);
        Vector3f translation = rotation.transform(new Vector3f(corner));
        return new Transformation(translation, rotation, new Vector3f(WING_SPAN, WING_THICKNESS, WING_CHORD),
                new Quaternionf());
    }

    // ------------------------------------------------------------------ helpers

    private static Location chest(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.55, 0);
    }
}
