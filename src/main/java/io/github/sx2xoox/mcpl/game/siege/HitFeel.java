package io.github.sx2xoox.mcpl.game.siege;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureKind;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels;
import io.github.sx2xoox.mcpl.game.siege.creature.HitMaterial;
import io.github.sx2xoox.mcpl.game.siege.creature.HitStrength;
import io.github.sx2xoox.mcpl.game.siege.creature.Creatures;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The 공성전 hit feel (타격감, {@code games.siege.hit-feel.*}): what a landed hit adds on top of the damage number and the
 * creature model's own flinch ({@code ModelCreatureLook}).
 *
 * <ul>
 *   <li>Creatures: sparks of the toy's material where the blow landed (on the side facing the attacker, not inside the
 *       model; plastic glints, cotton tufts, jelly drops, metal sparks), a bigger burst with a flash for a strong hit, a
 *       hit sound in layers (a deep thump as heavy as the toy, the material / the creature's own voice, a short bright
 *       tick) whose pitch varies over a run of hits, and a little pop where a creature falls to its last hit.</li>
 *   <li>Structures: wood splinters or porcelain shards where the blow landed (the model shakes itself).</li>
 *   <li>The attacker hears a short "딱" of their own when a hit lands (only they: vanilla's strong / crit / knockback
 *       attack sounds), a stronger one for a critical hit or a finisher, a ring for a kill; gold won by a last hit
 *       pops up as {@code +N 골드} where it fell, for them only.</li>
 * </ul>
 * Enemy players and bots keep vanilla's own hurt flash and sound (the basic attack adds its hit sparks): only the
 * attacker's confirmation is added. Repeats are spaced per target (the sound) and per attacker (the confirmation),
 * never globally, so one target's sounds never swallow another's. Particles only: nothing to clean up but the damage
 * numbers' gold pops, which the session tracks.
 */
public final class HitFeel {

    /** Hits on one target this close together (ticks) are a run: its sound's pitch walks through {@link #RUN}. */
    static final int RUN_TICKS = 24;
    /** Pitch steps of a run of hits (a little higher, higher, lower, ...), so a fast combo is not one note. */
    private static final float[] RUN = {0, 0.06f, 0.12f, -0.05f, 0.09f, 0.03f};

    private final GameSession session;
    private final SiegeSounds sounds;
    private final DamageNumbers numbers;
    private final HitFeelSettings settings;
    private final Supplier<BasicAttack.Accent> accent;
    private final BooleanSupplier ability;
    private final Map<UUID, Run> runs = new HashMap<>();

    private record Run(int count, long tick) {
    }

    /**
     * @param accent  the accent of the basic attack hit landing now (critical, finisher, none)
     * @param ability true while an ability's damage lands
     */
    public HitFeel(GameSession session, SiegeSounds sounds, DamageNumbers numbers, HitFeelSettings settings,
                   Supplier<BasicAttack.Accent> accent, BooleanSupplier ability) {
        this.session = session;
        this.sounds = sounds;
        this.numbers = numbers;
        this.settings = settings;
        this.accent = accent;
        this.ability = ability;
    }

    public HitFeelSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------------ strength

    /** How hard {@code event} reads, with the hit's accent and whether it is ability damage. */
    public HitStrength strength(EntityDamageEvent event) {
        double max = event.getEntity() instanceof LivingEntity living ? Creatures.maxHealth(living) : 20;
        return strength(event.getCause(), event.getFinalDamage(), max, accent.get(), ability.getAsBoolean(),
                settings.strongFraction());
    }

    /**
     * How hard a hit reads: burns, poisons and tiny ticks are light; a basic attack's critical hit or finisher, and an
     * ability hit of at least {@code strongFraction} of the target's maximum health, strong; the rest normal.
     */
    public static HitStrength strength(EntityDamageEvent.DamageCause cause, double amount, double maxHealth,
                                       BasicAttack.Accent accent, boolean ability, double strongFraction) {
        switch (cause) {
            case FIRE, FIRE_TICK, POISON, WITHER, LAVA, FREEZE, STARVATION, DROWNING -> {
                return HitStrength.LIGHT;
            }
            default -> {
            }
        }
        if (accent != BasicAttack.Accent.NONE) {
            return HitStrength.STRONG;
        }
        if (!(amount >= 1)) {
            return HitStrength.LIGHT;
        }
        if (ability && maxHealth > 0 && amount >= maxHealth * strongFraction) {
            return HitStrength.STRONG;
        }
        return HitStrength.NORMAL;
    }

    /** The strength of a hit landing now without an event (a structure): the basic attack's accent. */
    HitStrength strengthNow() {
        return accent.get() == BasicAttack.Accent.NONE ? HitStrength.NORMAL : HitStrength.STRONG;
    }

    // ------------------------------------------------------------------ creatures

    /**
     * A creature of {@code kind} took a landed hit from {@code from} (null = unknown) by {@code attacker} (null = not a
     * player): sparks, the layered sound, the attacker's confirmation, the pop of a {@code kill}.
     *
     * @param top the top of what is drawn of it above its feet (its model), blocks
     */
    public void creature(LivingEntity victim, @Nullable Location from, @Nullable Player attacker, CreatureKind kind,
                         HitStrength strength, boolean kill, double top) {
        if (!settings.enabled() || victim.getWorld() == null) {
            return;
        }
        Location at = impactPoint(victim, from, top);
        Vector out = outward(victim.getLocation(), from);
        HitMaterial material = HitMaterial.of(kind);
        if (strength != HitStrength.LIGHT) {
            material.sparks(at, out, settings.sparks(), strength == HitStrength.STRONG);
        }
        if (kill || sounds.ready("feel:" + victim.getUniqueId(), settings.soundGapTicks())) {
            layers(at, victim.getUniqueId(), kind, strength);
        }
        if (attacker != null) {
            confirm(attacker, strength, kill);
        }
        if (kill && settings.killPop()) {
            pop(at, material);
        }
    }

    /** The hit sound: a thump as heavy as the toy, its voice, a short bright tick; varied over a run of hits. */
    private void layers(Location at, UUID target, CreatureKind kind, HitStrength strength) {
        float volume = settings.soundVolume();
        if (!(volume > 0)) {
            return;
        }
        float heavy = (float) CreatureModels.heaviness(kind.id());
        float vary = vary(target);
        HitMaterial.Voice voice = HitMaterial.voice(kind);
        if (strength == HitStrength.LIGHT) {
            sounds.at(at, voice.sound(), voice.volume() * 0.5f * volume, pitch(voice.pitch() * (1 + vary)));
            return;
        }
        float hard = strength.amount();
        if (heavy >= 0.6f) {
            sounds.at(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, (0.4f + 0.45f * heavy) * hard * volume,
                    pitch(0.8f - 0.3f * heavy + vary * 0.5f));
        } else {
            sounds.at(at, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, (0.35f + 0.5f * heavy) * hard * volume,
                    pitch(1.05f - 0.45f * heavy + vary * 0.5f));
        }
        sounds.at(at, voice.sound(), voice.volume() * volume, pitch(voice.pitch() * (1 + vary)));
        sounds.at(at, Sound.BLOCK_NOTE_BLOCK_HAT, (strength == HitStrength.STRONG ? 0.55f : 0.3f) * volume,
                pitch(1.55f + 0.35f * (1 - heavy) + vary));
        if (strength == HitStrength.STRONG) {
            sounds.at(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.35f * volume, pitch(1.75f + vary));
        }
    }

    /** The pitch step of this hit in the target's run of hits (plus a little jitter). */
    float vary(UUID target) {
        long now = session.tick();
        Run before = runs.get(target);
        int count = before != null && now - before.tick() <= RUN_TICKS ? before.count() + 1 : 0;
        runs.put(target, new Run(count, now));
        if (runs.size() > 256) {
            runs.values().removeIf(run -> now - run.tick() > RUN_TICKS);
        }
        return RUN[count % RUN.length] + (float) (session.random().nextDouble() * 0.06 - 0.03);
    }

    private static float pitch(float pitch) {
        return Math.max(0.5f, Math.min(2f, pitch));
    }

    /** A creature fell to its last hit: a small bright pop where it stood. */
    private void pop(Location at, HitMaterial material) {
        World world = at.getWorld();
        world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, Color.fromRGB(0xFF, 0xF4, 0xD0));
        world.spawnParticle(Particle.FIREWORK, at, 8, 0.1, 0.1, 0.1, 0.12);
        world.spawnParticle(Particle.POOF, at, 6, 0.25, 0.25, 0.25, 0.04);
        material.sparks(at, new Vector(), settings.sparks() * 1.3, false);
    }

    // ------------------------------------------------------------------ structures

    /**
     * A structure ({@code key}: its id) of {@code material} inside {@code bounds} took a hit, by {@code attacker} (null
     * = a minion's): splinters or shards where it was struck, the attacker's confirmation ({@code destroyed}: the
     * kill's).
     */
    public void structure(String key, World world, BoundingBox bounds, HitMaterial material,
                          @Nullable Player attacker, boolean destroyed) {
        if (!settings.enabled()) {
            return;
        }
        Location at;
        Vector out;
        if (attacker != null && attacker.getWorld().equals(world)) {
            Vector eye = attacker.getEyeLocation().toVector();
            Vector point = new Vector(Math.clamp(eye.getX(), bounds.getMinX(), bounds.getMaxX()),
                    Math.clamp(eye.getY(), bounds.getMinY() + 0.5, Math.max(bounds.getMinY() + 0.5, bounds.getMaxY())),
                    Math.clamp(eye.getZ(), bounds.getMinZ(), bounds.getMaxZ()));
            at = point.toLocation(world);
            out = eye.clone().subtract(point).setY(0);
            out = out.lengthSquared() < 1.0E-6 ? new Vector() : out.normalize();
        } else {
            at = new Vector(bounds.getCenterX(), Math.min(bounds.getMaxY(), bounds.getMinY() + 2.5),
                    bounds.getCenterZ()).toLocation(world);
            out = new Vector();
        }
        HitStrength strength = attacker == null ? HitStrength.NORMAL : strengthNow();
        if (sounds.ready("feel-debris:" + key, settings.soundGapTicks())) {
            material.sparks(at, out, settings.sparks(), strength == HitStrength.STRONG);
        }
        if (attacker != null) {
            confirm(attacker, strength, destroyed);
        }
    }

    // ------------------------------------------------------------------ fighters and the attacker

    /** {@code attacker} landed a hit on an enemy player or bot (vanilla shows the hurt): their confirmation. */
    public void fighter(Player attacker, HitStrength strength) {
        if (settings.enabled()) {
            confirm(attacker, strength, false);
        }
    }

    /**
     * The attacker's own "딱" (only they hear it): vanilla's strong attack sound, the crit for a critical hit, the
     * knockback thud for a finisher or another strong hit, a bright ring for a {@code kill}. At most one per
     * {@code confirm-gap-ticks} (a sweep through many is one), a kill always.
     */
    public void confirm(Player attacker, HitStrength strength, boolean kill) {
        float volume = settings.confirmVolume();
        if (!settings.enabled() || !(volume > 0)) {
            return;
        }
        UUID id = attacker.getUniqueId();
        if (kill && sounds.ready("feel-kill:" + id, 1)) {
            sounds.to(attacker, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f * volume, 2f);
            sounds.to(attacker, Sound.ENTITY_PLAYER_ATTACK_STRONG, volume, 0.9f);
            return;
        }
        if (strength == HitStrength.LIGHT || !sounds.ready("feel-confirm:" + id, settings.confirmGapTicks())) {
            return;
        }
        BasicAttack.Accent now = accent.get();
        if (now == BasicAttack.Accent.CRITICAL) {
            sounds.to(attacker, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.3f * volume, 1.1f);
        } else if (now == BasicAttack.Accent.FINISH || strength == HitStrength.STRONG) {
            sounds.to(attacker, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f * volume, 1.15f);
        } else {
            sounds.to(attacker, Sound.ENTITY_PLAYER_ATTACK_STRONG, volume, 1.3f);
        }
    }

    /** {@code amount} gold won at {@code at}: {@code +N 골드} there for {@code player} only. */
    public void gold(Player player, Location at, int amount) {
        if (settings.enabled() && settings.goldPop() && amount > 0) {
            numbers.gold(player, at, amount);
        }
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Where a blow from {@code from} lands on {@code victim} drawn {@code top} blocks tall: on its side facing the blow
     * (not inside the model), at about the attacker's chest height within the body; its middle when nothing says.
     */
    public static Location impactPoint(LivingEntity victim, @Nullable Location from, double top) {
        Location feet = victim.getLocation();
        double height = Math.max(0.3, Math.max(top, victim.getHeight()));
        Location at = feet.clone().add(0, height * 0.55, 0);
        if (from == null || from.getWorld() == null || !from.getWorld().equals(feet.getWorld())) {
            return at;
        }
        Vector toward = from.toVector().subtract(feet.toVector()).setY(0);
        double distance = toward.length();
        if (distance > 1.0E-6) {
            double reach = Math.min(victim.getWidth() * 0.5, Math.max(0, distance - 0.2));
            at.add(toward.multiply(reach / distance));
        }
        double y = from.getY() + 1.2 - feet.getY();
        at.setY(feet.getY() + Math.max(height * 0.25, Math.min(height * 0.85, y)));
        return at;
    }

    /** The flat unit direction from {@code feet} out towards {@code from} (zero when unknown). */
    static Vector outward(Location feet, @Nullable Location from) {
        if (from == null || from.getWorld() == null || !from.getWorld().equals(feet.getWorld())) {
            return new Vector();
        }
        Vector out = from.toVector().subtract(feet.toVector()).setY(0);
        return out.lengthSquared() < 1.0E-6 ? new Vector() : out.normalize();
    }
}
