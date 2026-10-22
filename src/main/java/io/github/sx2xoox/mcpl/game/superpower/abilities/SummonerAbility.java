package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.DyeColor;
import org.bukkit.EntityEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 소환사 — fights through a pack. Kills raise a spirit wolf, the active calls a pair of wolves and the ultimate drops
 * an iron golem that slams the ground on arrival.
 *
 * <p>Every summon is driven by its own owner-scoped controller task (targeting, following, lifetime, name tag), so
 * the ultimate also works when borrowed. The owner's own hits mark a focus target that every summon switches to.
 * Summons never pick a player the owner cannot see (stealth), so hiding abilities keep working against the pack.
 * A summon's melee hit deals its attack damage attribute, set from the params (the match applies it exactly); on
 * Peaceful, where vanilla mob attacks never hurt players, the controller strikes for the summon instead.
 *
 * <p>Visuals: every summon rises from a turning circle of glowing runes (text displays in the enchanting-table
 * alphabet) with arcane motes flowing in; summons are circled by soul wisps; a fresh focus target is marked with a
 * rune ring and the pack's attention flies to it.
 */
public final class SummonerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("summoner", "소환사", Material.BONE)
            .color(TextColor.color(0xB48CFF))
            .difficulty(1)
            .passive("사냥의 부름",
                    "적을 처치하면 그 자리에서 영혼 늑대가 깨어나 {kill_wolf_duration}초간 함께 싸웁니다. "
                            + "소환수는 내가 마지막으로 공격한 적을 우선 노립니다.")
            .active("늑대 무리",
                    "늑대 {wolf_count}마리(체력 {wolf_health}, 공격력 {wolf_damage})를 {wolf_duration}초간 소환합니다. "
                            + "늑대는 최대 {max_wolves}마리까지 유지됩니다.",
                    15)
            .ultimate("철골렘",
                    "철골렘(체력 {golem_health}, 공격력 {golem_damage})을 {golem_duration}초간 소환합니다. "
                            + "착지 충격으로 반경 {golem_slam_radius}블록의 적에게 {golem_slam_damage} 피해를 주고 띄웁니다.")
            .param("kill_wolf_duration", 30)
            .param("wolf_count", 2)
            .param("wolf_duration", 20)
            .param("wolf_health", 16)
            .param("wolf_damage", 3)
            .param("max_wolves", 6)
            .param("golem_duration", 25)
            .param("golem_health", 80)
            .param("golem_damage", 7)
            .param("golem_slam_radius", 3)
            .param("golem_slam_damage", 3)
            .param("aggro_range", 16)
            .build();

    /** Ticks between two controller updates of a summon. */
    private static final long CONTROL_PERIOD = 5;
    /** How long the owner's last hit stays the pack's focus target. */
    private static final long FOCUS_TICKS = 100;
    /** A summon keeps chasing its target up to this multiple of the aggro range. */
    private static final double LEASH_FACTOR = 1.5;
    /** The golem walks back to the owner beyond this distance and is brought back beyond the second one. */
    private static final double GOLEM_FOLLOW_DISTANCE = 6;
    private static final double GOLEM_RECALL_DISTANCE = 28;

    /**
     * On Peaceful vanilla mob attacks on players never land, so the controller strikes by itself there: within this
     * gap between the hitboxes (vanilla melee reach) and at most once per vanilla attack interval.
     */
    private static final double WOLF_REACH = 0.8;
    private static final double GOLEM_REACH = 1.2;
    private static final long WOLF_STRIKE_TICKS = 20;
    private static final long GOLEM_STRIKE_TICKS = 25;
    /** Upward toss of a golem strike (vanilla golems throw their target up; ability damage applies no knockback). */
    private static final double GOLEM_TOSS = 0.45;

    private static final double WOLF_WIDTH = 0.6;
    private static final double WOLF_HEIGHT = 0.85;
    private static final double GOLEM_SCALE = 1.15;
    private static final double GOLEM_WIDTH = 1.4 * GOLEM_SCALE;
    private static final double GOLEM_HEIGHT = 2.7 * GOLEM_SCALE;
    /** Vanilla iron golems walk at 0.25; slightly faster so it can keep up with a fight. */
    private static final double GOLEM_SPEED = 0.3;
    private static final double MAX_SPEED = 2.5;

    private static final TextColor NAME_COLOR = TextColor.color(0xB48CFF);
    private static final TextColor SPIRIT_COLOR = TextColor.color(0x8FE3FF);

    // ------------------------------------------------------------------ visuals

    /** Lilac core, violet body, deep purple edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE1BEE7, 0xB388FF, 0x4A148C);
    /** Spirit wolves are the cold, ghostly variant. */
    private static final Vfx.Palette SPIRIT = Vfx.Palette.of(0xE8FBFF, 0x8FE3FF, 0x1F4E6B);
    private static final Color IRON = Color.fromRGB(0xD8D8D8);
    private static final double TAU = Math.PI * 2;
    /** The enchanting-table alphabet: plain letters render as runes. */
    private static final Key RUNE_FONT = Key.key(Key.MINECRAFT_NAMESPACE, "alt");
    private static final String RUNE_LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final TextColor RUNE_COLOR = TextColor.color(0xC9A6FF);
    private static final TextColor SPIRIT_RUNE_COLOR = TextColor.color(0x9FEAFF);
    /** A focus mark is drawn again for the same target at most this often. */
    private static final long FOCUS_MARK_TICKS = 30;

    /** Alive summons, oldest first. */
    private final List<Summon> summons = new ArrayList<>();
    private @Nullable LivingEntity focus;
    private long focusUntil;
    private long focusMarkedAt = -FOCUS_MARK_TICKS;
    /** True while a summon's own (Peaceful) strike is dealt, so it does not count as the owner's hit. */
    private boolean summonStrike;
    /** The target of the ability hit being dealt right now, and whether it landed (see {@link #onDamageDealt}). */
    private @Nullable LivingEntity pendingTarget;
    private boolean landed;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: call of the hunt

    @Override
    public void onKill(Player victim) {
        Location death = victim.getLocation();
        Location spot = death.getWorld().equals(player().getWorld()) ? findSpot(death, 0, WOLF_WIDTH, WOLF_HEIGHT, 0)
                : null;
        if (spot == null) {
            spot = findSpot(player().getLocation(), 1.5, WOLF_WIDTH, WOLF_HEIGHT, 180);
        }
        if (spot == null) {
            spot = player().getLocation();
        }
        Wolf wolf = summonWolf(spot, params().ticks("kill_wolf_duration"), true);
        if (wolf == null) {
            return;
        }
        new RuneCircle(spot, 1.1, 6, 2, 1.9f, SPIRIT_RUNE_COLOR, 26).open();
        rise(spot, SPIRIT, Particle.SOUL, 1.0);
        Vfx.cloud(ctx(), spot.clone().add(0, 0.4, 0), Particle.SCULK_SOUL, 6, 0.3, 0.03, null, false);
        Fx.sound(spot, Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.8f + Fx.jitter(0.08f));
        Fx.sound(spot, Sound.ENTITY_WOLF_GROWL, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(spot, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.4f + Fx.jitter(0.1f));
        player().sendActionBar(Text.mm("<#8FE3FF>영혼 늑대가 깨어났습니다</#8FE3FF>"));
    }

    @Override
    public void onRevoke() {
        // The framework removes the summoned mobs and the rune displays; only forget them here.
        for (Summon summon : summons) {
            summon.dismissed = true;
        }
        summons.clear();
        focus = null;
    }

    /**
     * A hit of the owner landed: its victim becomes the pack's focus. Summon hits deal their attack damage attribute
     * (set from the params) by themselves and never move the focus.
     */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() && victim.equals(pendingTarget)) {
            landed = true;
        }
        Entity damager = event.getDamager();
        if (summonStrike || find(damager) != null) {
            return; // a summon's own attack is not the owner's: it must not turn the whole pack
        }
        if (damager.equals(player()) || (damager instanceof Projectile projectile
                && player().equals(projectile.getShooter()))) {
            long now = ctx().currentTick();
            boolean fresh = !victim.equals(focus) || now > focusUntil || now - focusMarkedAt >= FOCUS_MARK_TICKS;
            focus = victim;
            focusUntil = now + FOCUS_TICKS;
            if (fresh && !summons.isEmpty()) {
                focusMarkedAt = now;
                markFocus(victim);
            }
        }
    }

    /** A rune ring at the focus target's feet and the pack's attention flying to it. */
    private void markFocus(LivingEntity target) {
        Location feet = target.getLocation().add(0, 0.1, 0);
        Vfx.ring(ctx(), feet, 0.8, 14, ctx().currentTick() * 0.3, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(0.9f), false);
        Vfx.ring(ctx(), feet.clone().add(0, 0.05, 0), 0.5, 8, 0, Particle.WITCH, null, false);
        Location body = Fx.body(target);
        for (Summon summon : summons) {
            if (summon.mob.isValid() && summon.mob.getWorld().equals(body.getWorld())) {
                Vfx.trail(Fx.body(summon.mob), body, summon.spirit ? SPIRIT.body() : PALETTE.body(), 8, false);
            }
        }
    }

    // ------------------------------------------------------------------ active: wolf pack

    @Override
    public ActivationResult onActive() {
        int count = Math.max(1, params().integer("wolf_count"));
        long lifetime = params().ticks("wolf_duration");
        Location origin = player().getLocation();
        int summoned = 0;
        for (int i = 0; i < count; i++) {
            // Flank the owner: left, right, then further back on each side.
            double angle = (i % 2 == 0 ? 1 : -1) * (70 + (i / 2) * 25.0);
            Location spot = findSpot(origin, 1.8, WOLF_WIDTH, WOLF_HEIGHT, angle);
            if (spot != null && summonWolf(spot, lifetime, false) != null) {
                summoned++;
                rise(spot, PALETTE, Particle.WITCH, 0.8);
                Vfx.trail(origin.clone().add(0, 1.0, 0), spot.clone().add(0, 0.4, 0), PALETTE.core(), 6, false);
            }
        }
        if (summoned == 0) {
            return ActivationResult.fail("늑대를 부를 공간이 없습니다");
        }
        new RuneCircle(origin, 1.7, 8, 3, 2.1f, RUNE_COLOR, 28).open();
        Vfx.burst(ctx(), origin.clone().add(0, 1.1, 0), PALETTE, 0.5, Particle.ENCHANT, false);
        Fx.sound(origin, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 1.2f + Fx.jitter(0.05f));
        Fx.sound(origin, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(origin, Sound.ENTITY_WOLF_ANGRY_GROWL, 1.0f, 0.9f + Fx.jitter(0.1f));
        player().sendActionBar(Text.mm("<#B48CFF>늑대 " + summoned + "마리 소환 · 현재 " + wolfCount() + "마리</#B48CFF>"));
        return ActivationResult.success();
    }

    private @Nullable Wolf summonWolf(Location at, long lifetime, boolean spirit) {
        double health = Math.max(1, params().num("wolf_health"));
        double damage = Math.max(0, params().num("wolf_damage"));
        int limit = Math.max(1, params().integer("max_wolves"));
        while (wolfCount() >= limit) {
            Summon oldest = summons.stream().filter(s -> !s.golem).findFirst().orElse(null);
            if (oldest == null) {
                break;
            }
            dismiss(oldest, true);
        }

        Wolf wolf = at.getWorld().spawn(at, Wolf.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setRemoveWhenFarAway(false);
            spawned.setCanPickupItems(false);
            spawned.setAdult();
            // Taming resets max health to the vanilla value, so the stats are applied afterwards.
            spawned.setTamed(true);
            spawned.setOwner(player());
            spawned.setSitting(false);
            spawned.setVariant(spirit ? Wolf.Variant.PALE : Wolf.Variant.BLACK);
            spawned.setCollarColor(spirit ? DyeColor.LIGHT_BLUE : DyeColor.PURPLE);
            setBase(spawned, Attribute.MAX_HEALTH, health);
            setBase(spawned, Attribute.ATTACK_DAMAGE, damage);
            spawned.setHealth(Math.min(health, Combat.maxHealth(spawned)));
            spawned.setCustomNameVisible(true);
        });
        if (!wolf.isValid()) {
            return null;
        }
        ctx().summon(player(), wolf);
        Summon summon = new Summon(wolf, spirit ? "영혼 늑대" : "늑대", damage, lifetime, false, spirit);
        summons.add(summon);
        summon.start();
        return wolf;
    }

    private int wolfCount() {
        int count = 0;
        for (Summon summon : summons) {
            if (!summon.golem) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ ultimate: iron golem

    @Override
    public ActivationResult onUltimate() {
        Location spot = findSpot(player().getLocation(), 2.5, GOLEM_WIDTH, GOLEM_HEIGHT, 0);
        if (spot == null) {
            return ActivationResult.fail("골렘을 부를 공간이 없습니다");
        }
        double health = Math.max(1, params().num("golem_health"));
        double damage = Math.max(0, params().num("golem_damage"));
        IronGolem golem = spot.getWorld().spawn(spot, IronGolem.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setRemoveWhenFarAway(false);
            spawned.setPlayerCreated(false); // player-made golems refuse to attack players
            setBase(spawned, Attribute.MAX_HEALTH, health);
            setBase(spawned, Attribute.ATTACK_DAMAGE, damage);
            setBase(spawned, Attribute.SCALE, GOLEM_SCALE);
            setBase(spawned, Attribute.MOVEMENT_SPEED, GOLEM_SPEED);
            spawned.setHealth(Math.min(health, Combat.maxHealth(spawned)));
            spawned.setCustomNameVisible(true);
        });
        if (!golem.isValid()) {
            return ActivationResult.fail("골렘을 부를 수 없습니다");
        }
        ctx().summon(player(), golem);
        Summon summon = new Summon(golem, "철골렘", damage, params().ticks("golem_duration"), true, false);
        summons.add(summon);
        summon.start();
        slam(spot);
        return ActivationResult.success();
    }

    /** The golem's arrival: a ground shockwave that hurts and lifts nearby enemies. */
    private void slam(Location spot) {
        World world = spot.getWorld();
        double radius = Math.max(0.5, params().num("golem_slam_radius"));
        double damage = params().num("golem_slam_damage");

        // A big rune circle under the golem, light falling into it from above, then the shockwave.
        new RuneCircle(spot, Math.max(2.2, radius * 0.85), 12, 3, 2.6f, RUNE_COLOR, 46).open();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int beams = Vfx.n(ctx(), 10);
        for (int i = 0; i < beams; i++) {
            double angle = i * TAU / beams + random.nextDouble(0.3);
            Location sky = spot.clone().add(Math.cos(angle) * 2.2, 9 + random.nextDouble(2), Math.sin(angle) * 2.2);
            Vfx.trail(sky, spot.clone().add(0, 1.2, 0), i % 2 == 0 ? PALETTE.core() : PALETTE.body(), 6, true);
        }
        Location ground = spot.clone().add(0, 0.15, 0);
        Vfx.shockwave(ctx(), player(), ground, 0.4, radius + 0.6, 7, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(1.5f), true);
        Vfx.shockwave(ctx(), player(), ground.clone().add(0, 0.1, 0), 0.2, radius, 5, Particle.DUST,
                new Particle.DustOptions(IRON, 1.2f), true);
        Vfx.burst(ctx(), spot.clone().add(0, 1.3, 0), PALETTE, 1.3, Particle.WITCH, true);
        world.spawnParticle(Particle.DUST_PILLAR, spot.clone().add(0, 0.1, 0), Vfx.n(ctx(), 30), radius * 0.5, 0,
                radius * 0.5, 0.2, Material.IRON_BLOCK.createBlockData(), true);
        world.spawnParticle(Particle.BLOCK, spot.clone().add(0, 0.2, 0), Vfx.n(ctx(), 40), radius * 0.4, 0.1,
                radius * 0.4, 0.15, Material.IRON_BLOCK.createBlockData(), true);
        Fx.sound(spot, Sound.BLOCK_ANVIL_LAND, 1.3f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(spot, Sound.ENTITY_IRON_GOLEM_REPAIR, 1.3f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(spot, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.2f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(spot, Sound.ENTITY_EVOKER_CAST_SPELL, 1.2f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(spot, Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.0f, 0.8f + Fx.jitter(0.05f));

        for (LivingEntity target : ctx().enemiesNear(player(), spot, radius)) {
            if (!hit(target, damage, DamageType.MAGIC) || !target.isValid() || target.isDead()) {
                continue;
            }
            Vector away = target.getLocation().toVector().subtract(spot.toVector()).setY(0);
            if (away.lengthSquared() > 1.0E-4) {
                away.normalize().multiply(0.4);
            }
            away.setY(0.6);
            away.multiply(Motion.knockbackFactor(target));
            target.setVelocity(Motion.clamp(target.getVelocity().add(away), MAX_SPEED));
            Vfx.cloud(ctx(), Fx.body(target), Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, PALETTE.fade(1.1f), false);
        }
    }

    // ------------------------------------------------------------------ summons

    /** One summoned mob and the controller task that keeps it fighting until its time runs out. */
    private final class Summon {
        private final Mob mob;
        private final String label;
        private final double damage;
        private final long expiresAt;
        private final boolean golem;
        private final boolean spirit;
        private boolean dismissed;
        private long nextStrike;

        Summon(Mob mob, String label, double damage, long lifetime, boolean golem, boolean spirit) {
            this.mob = mob;
            this.label = label;
            this.damage = damage;
            this.expiresAt = ctx().currentTick() + Math.max(1, lifetime);
            this.golem = golem;
            this.spirit = spirit;
        }

        void start() {
            updateName(expiresAt - ctx().currentTick());
            long runs = (expiresAt - ctx().currentTick()) / CONTROL_PERIOD + 2;
            Loop.repeat(ctx(), player(), CONTROL_PERIOD, CONTROL_PERIOD, runs, this::control,
                    () -> dismiss(this, true));
        }

        private boolean control(long run) {
            if (dismissed) {
                return false;
            }
            if (!mob.isValid() || mob.isDead()) {
                summons.remove(this);
                dismissed = true;
                return false;
            }
            long left = expiresAt - ctx().currentTick();
            if (left <= 0) {
                return false;
            }
            if (run % (20 / CONTROL_PERIOD) == 0) {
                updateName(left);
            }
            if (mob instanceof Sittable sittable && sittable.isSitting()) {
                sittable.setSitting(false);
            }
            LivingEntity target = chooseTarget();
            if (target != mob.getTarget()) {
                mob.setTarget(target);
            }
            if (target instanceof Player victim && isPeaceful()) {
                strike(victim);
            }
            if (target == null && golem) {
                followOwner();
            }
            wisps();
            return true;
        }

        private @Nullable LivingEntity chooseTarget() {
            double range = Math.max(1, params().num("aggro_range"));
            double leash = range * LEASH_FACTOR;
            LivingEntity focused = focus;
            if (focused != null && ctx().currentTick() <= focusUntil && isValidTarget(focused, leash)) {
                return focused;
            }
            LivingEntity current = mob.getTarget();
            if (current != null && isValidTarget(current, leash)) {
                return current;
            }
            for (LivingEntity candidate : ctx().enemiesNear(player(), mob.getLocation(), range)) {
                if (isVisible(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private boolean isValidTarget(LivingEntity target, double leash) {
            return Combat.isTargetable(ctx(), player(), target) && isVisible(target)
                    && target.getWorld().equals(mob.getWorld())
                    && target.getLocation().distanceSquared(mob.getLocation()) <= leash * leash;
        }

        /**
         * Players hidden from the owner (stealth hides them from every enemy) are off-limits until they show
         * themselves again, so a stealthed enemy is not chased or bitten out of hiding.
         */
        private boolean isVisible(LivingEntity target) {
            return !(target instanceof Player hidden) || player().canSee(hidden);
        }

        private boolean isPeaceful() {
            return mob.getWorld().getDifficulty() == Difficulty.PEACEFUL;
        }

        /**
         * Peaceful only: vanilla mob attacks on players are dropped there, so the summon walks up and strikes through
         * the context (credited, enemy-only, never scaled by difficulty) at the vanilla attack pace.
         */
        private void strike(Player target) {
            double reach = golem ? GOLEM_REACH : WOLF_REACH;
            if (!mob.getBoundingBox().expand(reach).overlaps(target.getBoundingBox()) || !mob.hasLineOfSight(target)) {
                mob.getPathfinder().moveTo(target, 1.2);
                return;
            }
            long now = ctx().currentTick();
            if (now < nextStrike) {
                return;
            }
            nextStrike = now + (golem ? GOLEM_STRIKE_TICKS : WOLF_STRIKE_TICKS);
            if (golem) {
                mob.playEffect(EntityEffect.ENTITY_ATTACK);
            }
            summonStrike = true;
            boolean hurt;
            try {
                hurt = hit(target, damage, DamageType.MOB_ATTACK);
            } finally {
                summonStrike = false;
            }
            if (!hurt) {
                return;
            }
            Location body = Fx.body(target);
            Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 5, 0.25, 0, (spirit ? SPIRIT : PALETTE).glow(0.8f),
                    false);
            if (golem) {
                Fx.sound(body, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 1.0f + Fx.jitter(0.1f));
                if (target.isValid() && !target.isDead()) {
                    Motion.push(target, target.getVelocity().setY(GOLEM_TOSS), MAX_SPEED);
                }
            }
        }

        private void followOwner() {
            Player owner = player();
            if (!owner.getWorld().equals(mob.getWorld())
                    || owner.getLocation().distanceSquared(mob.getLocation()) > GOLEM_RECALL_DISTANCE * GOLEM_RECALL_DISTANCE) {
                Location spot = findSpot(owner.getLocation(), 2.5, GOLEM_WIDTH, GOLEM_HEIGHT, 180);
                if (spot != null) {
                    vanish(mob.getLocation(), true);
                    mob.teleport(spot);
                    rise(spot, PALETTE, Particle.WITCH, 1.4);
                }
                return;
            }
            if (owner.getLocation().distanceSquared(mob.getLocation()) > GOLEM_FOLLOW_DISTANCE * GOLEM_FOLLOW_DISTANCE) {
                mob.getPathfinder().moveTo(owner, 1.0);
            }
        }

        /**
         * Soul wisps circling the summon: each mote is a trail particle that flies along the orbit until the next
         * controller run, so the orbit reads as continuous motion at a few particles per second.
         */
        private void wisps() {
            Location base = mob.getLocation();
            double height = mob.getHeight();
            double radius = golem ? 1.15 : 0.6;
            int count = Vfx.n(ctx(), golem ? 3 : 2);
            double speed = golem ? 0.12 : 0.2;
            double phase = ctx().currentTick() * speed;
            Color color = spirit ? SPIRIT.body() : golem ? PALETTE.core() : PALETTE.body();
            for (int i = 0; i < count; i++) {
                double from = phase + i * TAU / count;
                double to = from + speed * CONTROL_PERIOD;
                Location a = base.clone().add(Math.cos(from) * radius, height * (0.35 + 0.2 * Math.sin(from * 1.7 + i)),
                        Math.sin(from) * radius);
                Location b = base.clone().add(Math.cos(to) * radius, height * (0.35 + 0.2 * Math.sin(to * 1.7 + i)),
                        Math.sin(to) * radius);
                Vfx.trail(a, b, color, (int) CONTROL_PERIOD, false);
            }
            if (spirit && ThreadLocalRandom.current().nextInt(3) == 0) {
                Vfx.dot(base.clone().add(0, height * 0.6, 0), Particle.SOUL, null, false);
            } else if (golem && ThreadLocalRandom.current().nextInt(4) == 0) {
                Vfx.dot(base.clone().add(0, height * 0.75, 0), Particle.WITCH, null, false);
            }
        }

        private void updateName(long ticksLeft) {
            long seconds = Math.max(0, (ticksLeft + 19) / 20);
            mob.customName(Component.text(player().getName() + "의 " + label, spirit ? SPIRIT_COLOR : NAME_COLOR)
                    .append(Component.text(" " + seconds + "초", NamedTextColor.GRAY)));
        }
    }

    /**
     * Deals ability damage and tells whether it landed: on-hit effects only follow a hit nothing cancelled. Without
     * damage there is nothing to dodge, so the effects follow whenever the target is still a valid enemy.
     */
    private boolean hit(LivingEntity target, double damage, DamageType type) {
        if (!(damage > 0)) {
            return Combat.isTargetable(ctx(), player(), target);
        }
        pendingTarget = target;
        landed = false;
        try {
            Combat.hit(ctx(), player(), target, damage, type);
        } finally {
            pendingTarget = null;
        }
        return landed;
    }

    private @Nullable Summon find(Entity entity) {
        for (Summon summon : summons) {
            if (summon.mob.equals(entity)) {
                return summon;
            }
        }
        return null;
    }

    /** Removes a summon, dissolving it into motes. Safe to call more than once. */
    private void dismiss(Summon summon, boolean effects) {
        summons.remove(summon);
        if (summon.dismissed) {
            return;
        }
        summon.dismissed = true;
        Mob mob = summon.mob;
        if (!mob.isValid()) {
            return;
        }
        if (effects) {
            Location at = mob.getLocation();
            vanish(at, summon.golem);
            Fx.sound(at, summon.golem ? Sound.ENTITY_IRON_GOLEM_DEATH : Sound.ENTITY_WOLF_WHINE, 0.8f,
                    1.0f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.PARTICLE_SOUL_ESCAPE, 0.9f, 1.2f + Fx.jitter(0.1f));
        }
        mob.remove();
    }

    // ------------------------------------------------------------------ visuals

    /** A summon taking shape: a burst of palette motes and a short double helix rising from the ground. */
    private void rise(Location at, Vfx.Palette palette, Particle accent, double size) {
        Vfx.burst(ctx(), at.clone().add(0, 0.5 * size, 0), palette, 0.6 * size, accent, false);
        Vfx.helix(ctx(), at.clone().add(0, 0.05, 0), 0.45 * size, 1.3 * size, 1.25, 2, 9,
                ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_COLOR_TRANSITION, palette.glow(0.8f), false);
    }

    /** A summon dissolving: a sphere of fading motes that falls in on itself, and souls leaving upwards. */
    private void vanish(Location at, boolean big) {
        double size = big ? 1.4 : 0.7;
        Location center = at.clone().add(0, 0.5 * size, 0);
        Vfx.sphere(ctx(), center, 0.5 * size, big ? 22 : 14, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.0f),
                false);
        Vfx.cloud(ctx(), center, Particle.SOUL, big ? 6 : 3, 0.3 * size, 0.03, null, false);
        Vfx.cloud(ctx(), center, Particle.REVERSE_PORTAL, big ? 12 : 6, 0.25 * size, 0.02, null, false);
    }

    /**
     * A turning ring of glowing runes lying flat on the ground (text displays in the enchanting-table alphabet). It
     * unfolds outwards, turns while arcane motes stream into its center, then collapses inwards and vanishes.
     */
    private final class RuneCircle {
        private static final int UNFOLD_TICKS = 7;
        private final Location center;
        private final double radius;
        private final int count;
        private final int letters;
        private final float scale;
        private final TextColor color;
        private final long lifetime;
        private final List<TextDisplay> runes = new ArrayList<>();
        private final double startAngle = ThreadLocalRandom.current().nextDouble(TAU);

        RuneCircle(Location at, double radius, int count, int letters, float scale, TextColor color, long lifetime) {
            this.center = at.clone().add(0, 0.04, 0);
            this.center.setYaw(0);
            this.center.setPitch(0);
            this.radius = radius;
            this.count = count;
            this.letters = Math.max(1, letters);
            this.scale = scale;
            this.color = color;
            this.lifetime = Math.max(12, lifetime);
        }

        void open() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < count; i++) {
                double angle = startAngle + i * TAU / count;
                StringBuilder word = new StringBuilder(letters);
                for (int l = 0; l < letters; l++) {
                    word.append(RUNE_LETTERS.charAt(random.nextInt(RUNE_LETTERS.length())));
                }
                String text = word.toString();
                Transformation folded = place(angle, radius * 0.25, 0.01f);
                runes.add(Displays.spawn(ctx(), player(), center, TextDisplay.class, lifetime + 2, rune -> {
                    rune.text(Component.text(text, color).font(RUNE_FONT));
                    rune.setBillboard(Display.Billboard.FIXED);
                    rune.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    rune.setShadowed(true);
                    rune.setTransformation(folded);
                }));
            }
            long closeAt = lifetime - 8;
            Loop.repeat(ctx(), player(), 1, 1, closeAt, tick -> {
                if (tick == 0) {
                    turn(0, UNFOLD_TICKS); // unfold
                } else if (tick >= UNFOLD_TICKS && (tick - UNFOLD_TICKS) % 10 == 0) {
                    // keep turning: aim where the ring should be at the next update
                    turn(tick - UNFOLD_TICKS + 10, 10);
                }
                if (tick % 3 == 0) {
                    inflow(tick);
                }
                if (tick % 6 == 0) {
                    Vfx.ring(ctx(), center.clone().add(0, 0.05, 0), radius * 0.62, (int) Math.round(radius * 9),
                            -tick * 0.05, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.7f), false);
                }
                return true;
            }, this::close);
        }

        /** Turns the ring to its angle {@code tick} ticks after it unfolded, over {@code ticks} ticks. */
        private void turn(long tick, int ticks) {
            double spin = tick * 0.035;
            for (int i = 0; i < runes.size(); i++) {
                Displays.animate(runes.get(i), place(startAngle + spin + i * TAU / count, radius, scale), ticks);
            }
        }

        /** Arcane motes flying from the ring into the center (enchant particles fly towards their origin). */
        private void inflow(long tick) {
            World world = center.getWorld();
            int motes = Vfx.n(ctx(), 3);
            for (int i = 0; i < motes; i++) {
                double angle = startAngle + tick * 0.4 + i * TAU / motes;
                world.spawnParticle(Particle.ENCHANT, center.getX(), center.getY() + 0.3, center.getZ(), 0,
                        Math.cos(angle) * radius, 0.9, Math.sin(angle) * radius, 1.0, null, false);
            }
        }

        private void close() {
            for (int i = 0; i < runes.size(); i++) {
                TextDisplay rune = runes.get(i);
                Displays.animate(rune, place(startAngle + i * TAU / count, radius * 0.2, 0.01f), 7);
            }
            ctx().runLater(player(), 8, () -> runes.forEach(rune -> {
                if (rune.isValid()) {
                    rune.remove();
                }
            }));
            Vfx.cloud(ctx(), center.clone().add(0, 0.4, 0), Particle.DUST_COLOR_TRANSITION, 8, radius * 0.3, 0,
                    PALETTE.fade(0.8f), false);
        }

        /**
         * A rune lying face-up at {@code distance} from the center, its top pointing outwards (text displays grow
         * upwards from their origin, so the glyph sits just inside the ring line).
         */
        private Transformation place(double angle, double distance, float size) {
            double inset = Math.max(0, distance - 0.12 * size);
            return Displays.centered((float) (Math.cos(angle) * inset), 0, (float) (Math.sin(angle) * inset),
                    size, size, size, (float) Math.toDegrees(angle) + 90f, -90f, 0f);
        }
    }

    // ------------------------------------------------------------------ placement

    /**
     * A free spot for a {@code width} x {@code height} mob around {@code center}: first {@code distance} blocks in
     * the facing direction turned by {@code firstAngle} degrees, then the other directions, then the center itself.
     * Each candidate must stand on the ground, be free of blocks, be inside the arena and be visible from the center
     * (never through a wall). Null if nothing fits.
     */
    private @Nullable Location findSpot(Location center, double distance, double width, double height,
                                        double firstAngle) {
        World world = center.getWorld();
        Vector forward = center.getDirection().setY(0);
        if (forward.lengthSquared() < 1.0E-4) {
            forward = new Vector(0, 0, 1);
        }
        forward.normalize();
        Location eye = center.clone().add(0, 1.0, 0);
        int directions = distance <= 0 ? 0 : 8;
        for (int i = 0; i <= directions; i++) {
            Location candidate;
            if (i == directions) {
                candidate = center.clone();
            } else {
                double turn = firstAngle + (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * 45.0;
                candidate = center.clone().add(forward.clone().rotateAroundY(Math.toRadians(-turn)).multiply(distance));
            }
            Location ground = groundBelow(world, candidate, 3);
            if (ground == null || !Spots.isFree(world, ground, width, height) || !ctx().isInsideArena(ground)) {
                continue;
            }
            if (i != directions && !Spots.clearPath(eye, ground.clone().add(0, Math.min(1.0, height * 0.5), 0))) {
                continue;
            }
            ground.setYaw(center.getYaw());
            ground.setPitch(0);
            return ground;
        }
        return null;
    }

    private static @Nullable Location groundBelow(World world, Location point, double maxDrop) {
        Location start = point.clone().add(0, 1.0, 0);
        RayTraceResult down = world.rayTraceBlocks(start, new Vector(0, -1, 0), maxDrop + 1.0,
                FluidCollisionMode.NEVER, true);
        return down == null ? null : down.getHitPosition().toLocation(world).add(0, 0.01, 0);
    }

    // ------------------------------------------------------------------ helpers

    private static void setBase(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }
}
