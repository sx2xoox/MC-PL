package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 도깨비 — the Korean goblin: a trickster that fights with will-o'-wisps, its magic club and its hat of
 * invisibility. Two blue wisps circle the dokkaebi and dart at nearby enemies on their own; the club ("뚝딱 방망이")
 * smashes the ground in front, and everything it and the wisps hit raises a comic bump ("혹") on the victim's head.
 * Three bumps burst with a "뚝딱!" and leave the victim seeing stars for a moment. The ultimate is the dokkaebi hat
 * ("감투"): pulled down over the head it makes the dokkaebi vanish, and a melee hit from hiding plays a prank that
 * bounces the victim off in a random direction. Every prank (and every swing of the club, and every blow the dokkaebi
 * takes) knocks the hat askew for a moment: the dokkaebi shows and cannot prank until it is pulled down again, so it
 * strikes from hiding, is seen, and vanishes again, and the victims get a window to hit back.
 *
 * <p>The hat lives on this instance's hooks (the prank in the damage hooks, the slipping hat in
 * {@link #onTakeDamage}, the stealth undone in {@link #onRevoke}), so the ultimate is not standalone.
 *
 * <p>Look: will-o'-wisp blue with the dokkaebi's red and the gold its club makes. The wisps are little soul-fire
 * flames orbiting at chest height that wobble through the air on their way to a victim and burst in blue flame and
 * gold sparks. The club is a gnarled dark-wood club with gold studs, raised over the shoulder and brought down in a
 * fast overhead smash that cracks the ground, sprays gold nuggets ("금 나와라 뚝딱!") and blooms blue fire. Bumps
 * are stacked pink lumps that throb on the victim's head; when they burst a "뚝딱!" sign pops up and stars circle the
 * head while the victim is stunned. The hat drops onto the dokkaebi's head from above, the dokkaebi vanishes in a puff
 * of blue fire, and while it is gone only faint blue flames at its feet and its giggle give it away.
 */
public final class DokkaebiAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("dokkaebi", "도깨비", Material.MACE)
            .color(TextColor.color(0x3FA9FF))
            .difficulty(1)
            .passive("도깨비불",
                    "푸른 도깨비불 {wisp_count}개가 주위를 맴돌다 {wisp_range}블록 안의 적에게 날아가 {wisp_damage} 피해를 "
                            + "주고 혹을 붙입니다. 날아간 도깨비불은 {wisp_recharge}초 뒤 다시 피어납니다.")
            .active("뚝딱 방망이",
                    "방망이를 내리쳐 앞쪽 {club_reach}블록 안의 적에게 {club_damage} 피해를 주고 살짝 띄우며 혹을 붙입니다. "
                            + "혹({bump_duration}초 유지)이 {bump_max}개 쌓이면 '뚝딱!' 터져 {bump_pop_damage} 피해와 "
                            + "{bump_stun}초 기절(이동·점프 불가)을 주고, 그 뒤 {bump_immunity}초간은 혹이 붙지 않습니다.",
                    8)
            .ultimate("도깨비 감투",
                    "감투를 눌러써 {hat_duration}초간 적의 눈에서 사라집니다(도깨비불도 숨습니다). 숨은 채 근접 공격이 "
                            + "적중하면 {prank_damage} 추가 피해와 혹을 주고 적을 엉뚱한 방향으로 약 {prank_knock}블록 "
                            + "튕겨 보냅니다. 장난을 치거나 방망이를 휘두르거나 공격받으면 감투가 들썩여 {hat_slip}초간 "
                            + "모습이 드러나고, 그동안은 장난을 칠 수 없습니다.")
            // The prank lives in the damage hooks and the stealth must be undone in onRevoke.
            .ultimateNotStandalone()
            .param("wisp_count", 2)
            .param("wisp_range", 7)
            .param("wisp_damage", 1.5)
            .param("wisp_recharge", 6)
            .param("club_reach", 4)
            .param("club_damage", 4)
            .param("bump_max", 3)
            .param("bump_duration", 8)
            .param("bump_pop_damage", 2)
            .param("bump_stun", 1)
            .param("bump_immunity", 1)
            .param("hat_duration", 6)
            .param("prank_damage", 2)
            .param("prank_knock", 5)
            .param("hat_slip", 1)
            .build();

    private static final String BLUE = "#3FA9FF";
    private static final String GOLD = "#FFC93C";
    private static final String RED = "#FF5A4E";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: wisp blue, dokkaebi red, gold
    /** Pale wisp core, will-o'-wisp blue, deep night indigo. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xD6F4FF, 0x3FA9FF, 0x0D2A6B);
    /** The gold the club makes. */
    private static final Vfx.Palette GOLD_PALETTE = Vfx.Palette.of(0xFFF6C9, 0xFFC93C, 0x8A5A00);
    private static final Particle.DustTransition WISP_GLOW = PALETTE.glow(0.9f);
    private static final Particle.DustTransition WISP_FADE = PALETTE.fade(1.1f);
    private static final Particle.DustTransition WISP_SMALL = PALETTE.glow(0.55f);
    private static final Particle.DustOptions GOLD_DUST = GOLD_PALETTE.dust(0.8f);
    private static final Particle.DustTransition GOLD_GLOW = GOLD_PALETTE.glow(1.0f);
    private static final Particle.DustOptions RED_DUST = new Particle.DustOptions(Color.fromRGB(0xE53935), 0.9f);
    private static final Particle.DustOptions BUMP_DUST = new Particle.DustOptions(Color.fromRGB(0xFF7A8A), 0.6f);
    private static final Particle.DustOptions STAR_DUST = new Particle.DustOptions(Color.fromRGB(0xFFE45C), 0.7f);

    /** Block and item types of the visuals; block data and items are created on use (they need a running server). */
    private static final Material WISP_BLOCK = Material.SOUL_FIRE;
    private static final Material CLUB_HANDLE = Material.STRIPPED_DARK_OAK_LOG;
    private static final Material CLUB_HEAD = Material.DARK_OAK_LOG;
    private static final Material CLUB_STUD = Material.GOLD_BLOCK;
    private static final Material BUMP_BLOCK = Material.PINK_TERRACOTTA;
    private static final Material HAT_BLOCK = Material.BLACK_CONCRETE;
    private static final Material HAT_BAND = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material NUGGET = Material.GOLD_NUGGET;

    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 1.6;
    /** Display entities one dokkaebi keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    /** Kept for the hat (cap, crown, band), so wisps, clubs and bumps never crowd out the ultimate. */
    private static final int HAT_DISPLAYS = 3;
    /** Ticks are counted from here when nothing happened yet ("/ 2": now - this must not overflow). */
    private static final long NEVER = Long.MIN_VALUE / 2;

    // passive: will-o'-wisps
    private static final int MAX_WISPS = 4;
    private static final double WISP_ORBIT = 0.95;
    private static final double WISP_HEIGHT = 1.05;
    private static final double WISP_SPIN = 0.09;
    private static final float WISP_SCALE = 0.34f;
    private static final double WISP_SPEED = 0.55;
    private static final double WISP_WOBBLE = 0.12;
    private static final long WISP_MAX_FLIGHT = 40;
    /** Ticks between two wisps leaving, so a pair never fires as one. */
    private static final long WISP_GAP = 12;
    private static final long WISP_SEEK_PERIOD = 4;
    private static final int WISP_KINDLE_TICKS = 6;

    // active: the club
    private static final float CLUB_SCALE = 1.35f;
    /** The club's grip in body space (local +Z = facing, +X = the owner's left): at the right shoulder. */
    private static final Vector3f GRIP = new Vector3f(-0.34f, 1.38f, 0.12f);
    /** Swing angles (degrees from straight up, positive = forward) per tick: raise, hold up, then the smash. */
    private static final float[] SWING = {-10f, -35f, -55f, -62f, 30f, 125f};
    /** The tick the club strikes the ground (the last swing angle). */
    private static final int STRIKE_TICK = SWING.length - 1;
    private static final long CLUB_HOLD_TICKS = 5;
    private static final int CLUB_FOLD_TICKS = 4;
    /** Enemies within this angle either side of the facing are in the smash. */
    private static final double CLUB_HALF_ARC = Math.toRadians(70);
    /** Enemies this close are smashed whichever side they stand on. */
    private static final double CLUB_POINT_BLANK = 1.0;
    private static final double CLUB_LIFT = 0.42;
    private static final double CLUB_SHOVE = 0.2;
    /** Where the club head lands, in front of the owner. */
    private static final double CLUB_IMPACT = 1.6;

    /** One part of the club in club space: the grip at the origin, the handle along +Y. */
    private record ClubPart(Material block, float x, float y, float z, float sx, float sy, float sz, boolean glow) {
    }

    private static final ClubPart[] CLUB = {
            new ClubPart(CLUB_HANDLE, 0f, 0.32f, 0f, 0.12f, 0.78f, 0.12f, false),
            new ClubPart(CLUB_HEAD, 0f, 1.0f, 0f, 0.34f, 0.72f, 0.34f, false),
            new ClubPart(CLUB_STUD, 0.19f, 0.9f, 0.02f, 0.1f, 0.1f, 0.1f, true),
            new ClubPart(CLUB_STUD, -0.19f, 1.12f, -0.03f, 0.1f, 0.1f, 0.1f, true),
            new ClubPart(CLUB_STUD, 0.02f, 1.24f, 0.19f, 0.1f, 0.1f, 0.1f, true),
    };

    // bumps
    private static final float BUMP_BASE = 0.3f;
    private static final float BUMP_SHRINK = 0.045f;
    private static final double BUMP_STEP = 0.17;
    private static final int BUMP_POP_TICKS = 4;
    private static final long BUMP_THROB_PERIOD = 10;

    // ultimate: the hat
    private static final int HAT_DROP_TICKS = 6;
    private static final double HAT_DROP_HEIGHT = 2.4;
    private static final long FOOTPRINT_PERIOD = 6;
    private static final long GIGGLE_PERIOD = 40;
    private static final double AGGRO_DROP_RANGE = 24;
    private static final double PRANK_LIFT = 0.5;
    /** Roughly how far a launched player slides per unit of horizontal speed (air drag 0.91 over ~22 ticks). */
    private static final double SLIDE_PER_SPEED = 9.5;

    private final Wisp[] wisps = new Wisp[MAX_WISPS];
    private int wispCount;
    private boolean granted;
    private long lastWispShot = NEVER;
    private final Map<UUID, Bumps> bumps = new HashMap<>();
    private final List<Display> displays = new ArrayList<>();
    private final List<Display> hatDisplays = new ArrayList<>();
    private final Stealth stealth = new Stealth();
    private @Nullable Hat hat;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        granted = true;
        wispCount = Math.clamp(params().integer("wisp_count"), 0, MAX_WISPS);
        long now = ctx().currentTick();
        for (int i = 0; i < wispCount; i++) {
            wisps[i] = new Wisp(i);
            // The wisps kindle one after the other as the fight begins.
            wisps[i].readyAt = now + 4L * i;
        }
    }

    @Override
    public void onRevoke() {
        // The context removes the displays; only the stealth must be undone here.
        granted = false;
        Hat worn = hat;
        hat = null;
        if (worn != null) {
            worn.ended = true;
        }
        Player owner = player();
        if (stealth.end(ctx(), owner) && owner.isOnline()) {
            PotionEffect effect = owner.getPotionEffect(PotionEffectType.INVISIBILITY);
            if (effect != null && effect.getAmplifier() == 0) {
                owner.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
        }
        bumps.clear();
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        if (granted) {
            boolean hidden = isHatHidden();
            for (int i = 0; i < wispCount; i++) {
                wisps[i].tick(owner, tick, now, hidden);
            }
            if (!hidden && tick % WISP_SEEK_PERIOD == 0) {
                seek(owner, now);
            }
        }
        if (!bumps.isEmpty()) {
            updateBumps(owner, tick, now);
        }
    }

    // ------------------------------------------------------------------ passive: will-o'-wisps

    private enum WispState { SPENT, READY, FLYING }

    /** One will-o'-wisp: orbits the owner while ready, darts at an enemy, then rekindles after its recharge. */
    private final class Wisp {
        private final int index;
        private WispState state = WispState.SPENT;
        private long readyAt;
        private @Nullable BlockDisplay display;
        private @Nullable LivingEntity target;
        private @Nullable Location position;
        private long flight;
        private boolean shown = true;

        Wisp(int index) {
            this.index = index;
        }

        void tick(Player owner, long tick, long now, boolean hidden) {
            switch (state) {
                case SPENT -> {
                    if (now >= readyAt) {
                        kindle(owner, tick, hidden);
                    }
                }
                case READY -> orbit(owner, tick, hidden);
                case FLYING -> fly(owner, now);
            }
        }

        /** Where this wisp circles now: around the chest, bobbing, the wisps spread evenly. */
        Location orbitPoint(Player owner, long tick) {
            double angle = tick * WISP_SPIN + index * TAU / Math.max(1, wispCount);
            Location at = owner.getLocation().add(Math.cos(angle) * WISP_ORBIT,
                    WISP_HEIGHT + 0.18 * Math.sin(tick * 0.15 + index * 1.7), Math.sin(angle) * WISP_ORBIT);
            return Displays.unrotated(at);
        }

        private void kindle(Player owner, long tick, boolean hidden) {
            state = WispState.READY;
            Location at = orbitPoint(owner, tick);
            if (canSpawn(1)) {
                BlockDisplay flame = Displays.block(ctx(), owner, at, WISP_BLOCK.createBlockData(),
                        Displays.scaled(0.02f), 0);
                flame.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, flame, Displays.scaled(WISP_SCALE), WISP_KINDLE_TICKS);
                remember(flame);
                display = flame;
                shown = true;
                setShown(!hidden);
            }
            if (!hidden) {
                Vfx.cloud(ctx(), at, Particle.SOUL_FIRE_FLAME, 4, 0.08, 0.02, null, false);
                Vfx.ring(ctx(), at, 0.3, 8, tick * 0.3, Particle.DUST_COLOR_TRANSITION, WISP_SMALL, false);
                Fx.sound(at, Sound.BLOCK_FIRE_AMBIENT, 0.35f, 1.6f + Fx.jitter(0.2f));
                Fx.sound(at, Sound.PARTICLE_SOUL_ESCAPE, 0.4f, 1.5f + Fx.jitter(0.2f));
            }
        }

        private void orbit(Player owner, long tick, boolean hidden) {
            Location at = orbitPoint(owner, tick);
            setShown(!hidden);
            BlockDisplay flame = display;
            if (flame != null && flame.isValid()) {
                if (!flame.getWorld().equals(owner.getWorld())) {
                    flame.remove();
                    display = null;
                } else {
                    Displays.moveTo(flame, at);
                }
            }
            if (!hidden && (tick + index) % 3 == 0) {
                Vfx.dot(at.clone().add(0, 0.12, 0), Particle.SOUL_FIRE_FLAME, null, false);
                Vfx.dot(Vfx.scatter(at, 0.12), Particle.DUST_COLOR_TRANSITION, WISP_SMALL, false);
            }
        }

        /** Shows or hides the flame (hidden while the hat is worn, so it never gives the dokkaebi away). */
        private void setShown(boolean visible) {
            BlockDisplay flame = display;
            if (flame == null || !flame.isValid() || visible == shown) {
                return;
            }
            shown = visible;
            flame.setViewRange(visible ? 1.0f : 0f);
        }

        boolean isReady() {
            return state == WispState.READY;
        }

        void launch(Player owner, LivingEntity victim, Location from) {
            state = WispState.FLYING;
            target = victim;
            position = from.clone();
            flight = 0;
            Vfx.dot(from, Particle.FLASH, PALETTE.core(), false);
            Vfx.cloud(ctx(), from, Particle.SOUL_FIRE_FLAME, 3, 0.06, 0.03, null, false);
            Fx.sound(from, Sound.ENTITY_BLAZE_SHOOT, 0.35f, 1.8f + Fx.jitter(0.1f));
            Fx.sound(from, Sound.PARTICLE_SOUL_ESCAPE, 0.7f, 1.3f + Fx.jitter(0.2f));
        }

        /** One tick of flight: wobbling towards the victim's chest, stopped by walls. */
        private void fly(Player owner, long now) {
            LivingEntity victim = target;
            Location at = position;
            if (victim == null || at == null || !Combat.isTargetable(ctx(), owner, victim) || !isVisible(victim)
                    || !victim.getWorld().equals(at.getWorld()) || ++flight > WISP_MAX_FLIGHT) {
                fizzle(now);
                return;
            }
            Location aim = Fx.body(victim);
            Vector to = aim.toVector().subtract(at.toVector());
            double distance = to.length();
            double reach = victim.getWidth() * 0.5 + 0.35;
            if (distance <= reach + WISP_SPEED) {
                strike(owner, victim, now);
                return;
            }
            Vector heading = to.multiply(1 / distance);
            Vector side = heading.clone().crossProduct(new Vector(0, 1, 0));
            if (side.lengthSquared() > 1.0E-6) {
                side.normalize().multiply(Math.sin(flight * 0.7 + index) * WISP_WOBBLE);
            }
            Vector step = heading.clone().multiply(WISP_SPEED).add(side);
            double length = step.length();
            RayTraceResult wall = at.getWorld().rayTraceBlocks(at, step.clone().multiply(1 / length), length,
                    FluidCollisionMode.NEVER, true);
            if (wall != null) {
                at.set(wall.getHitPosition().getX(), wall.getHitPosition().getY(), wall.getHitPosition().getZ());
                fizzle(now);
                return;
            }
            Location from = at.clone();
            at.add(step);
            BlockDisplay flame = display;
            if (flame != null && flame.isValid()) {
                Displays.moveTo(flame, at);
            }
            Vfx.line(ctx(), from, at, 3, Particle.DUST_COLOR_TRANSITION, WISP_GLOW, false);
            if (flight % 2 == 0) {
                Vfx.dot(at, Particle.SOUL_FIRE_FLAME, null, false);
                Vfx.trail(at, from.clone().add(0, 0.25, 0), PALETTE.edge(), 6, false);
            }
        }

        /** The wisp reaches its victim: a small hit, a bump, a burst of blue flame and gold sparks. */
        private void strike(Player owner, LivingEntity victim, long now) {
            Location body = Fx.body(victim);
            Combat.hit(ctx(), owner, victim, params().num("wisp_damage"));
            if (Combat.isTargetable(ctx(), owner, victim)) {
                addBump(victim);
            }
            World world = body.getWorld();
            Vfx.burst(ctx(), body, PALETTE, 0.45, Particle.SOUL_FIRE_FLAME, false);
            world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 4), 0.15, 0.15, 0.15, 0, GOLD_DUST);
            Fx.sound(body, Sound.ENTITY_BLAZE_SHOOT, 0.4f, 1.9f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.BLOCK_FIRE_EXTINGUISH, 0.35f, 1.7f + Fx.jitter(0.15f));
            spend(now);
        }

        private void fizzle(long now) {
            Location at = position;
            if (at != null) {
                Vfx.cloud(ctx(), at, Particle.SMOKE, 3, 0.05, 0.01, null, false);
                Vfx.dot(at, Particle.SOUL_FIRE_FLAME, null, false);
                Fx.sound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.25f, 1.9f);
            }
            spend(now);
        }

        private void spend(long now) {
            state = WispState.SPENT;
            readyAt = now + Math.max(1, params().ticks("wisp_recharge"));
            target = null;
            position = null;
            BlockDisplay flame = display;
            display = null;
            if (flame != null && flame.isValid()) {
                Displays.shrinkAway(ctx(), player(), flame, 3);
            }
        }
    }

    /** Sends a ready wisp at the nearest visible enemy in range (one wisp at a time). */
    private void seek(Player owner, long now) {
        if (now - lastWispShot < WISP_GAP) {
            return;
        }
        Wisp ready = null;
        for (int i = 0; i < wispCount; i++) {
            if (wisps[i].isReady()) {
                ready = wisps[i];
                break;
            }
        }
        if (ready == null) {
            return;
        }
        Location from = ready.orbitPoint(owner, ctx().currentTick());
        double range = Math.max(1, params().num("wisp_range"));
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (!isVisible(enemy) || !Spots.clearPath(from, Fx.body(enemy))) {
                continue;
            }
            lastWispShot = now;
            ready.launch(owner, enemy, from);
            return;
        }
    }

    /** Players hidden from the owner (stealth) are never targeted or marked, so nothing gives them away. */
    private boolean isVisible(LivingEntity target) {
        return !(target instanceof Player hidden) || player().canSee(hidden);
    }

    // ------------------------------------------------------------------ bumps

    /** The bumps on one victim's head. */
    private final class Bumps {
        private final LivingEntity victim;
        private final List<BlockDisplay> lumps = new ArrayList<>();
        private int count;
        private long expires;
        private long immuneUntil = NEVER;
        private boolean hidden;

        Bumps(LivingEntity victim) {
            this.victim = victim;
        }

        Location head() {
            return Displays.unrotated(victim.getLocation().add(0, victim.getHeight(), 0));
        }
    }

    /** Raises one more bump on {@code victim}; the last one bursts with a "뚝딱!". */
    private void addBump(LivingEntity victim) {
        Player owner = player();
        int max = params().integer("bump_max");
        if (max <= 0 || !Combat.isTargetable(ctx(), owner, victim)) {
            return;
        }
        long now = ctx().currentTick();
        Bumps bump = bumps.computeIfAbsent(victim.getUniqueId(), id -> new Bumps(victim));
        if (now < bump.immuneUntil) {
            return;
        }
        bump.count++;
        bump.expires = now + Math.max(1, params().ticks("bump_duration"));
        Location head = bump.head();
        if (bump.count >= max) {
            pop(bump);
            return;
        }
        int index = bump.count - 1;
        if (canSpawn(1)) {
            BlockDisplay lump = Displays.block(ctx(), owner, head, BUMP_BLOCK.createBlockData(),
                    lumpPose(index, 0.05f), 0);
            lump.setTeleportDuration(1);
            remember(lump);
            if (!isVisible(victim)) {
                lump.setViewRange(0);
            }
            Displays.animateNextTick(ctx(), owner, lump, lumpPose(index, 1.35f), 2);
            ctx().runLater(owner, 3, () -> Displays.animate(lump, lumpPose(index, 1f), 3));
            bump.lumps.add(lump);
        }
        if (isVisible(victim)) {
            Location top = head.clone().add(0, 0.15 + index * BUMP_STEP, 0);
            Vfx.cloud(ctx(), top, Particle.DUST, 5, 0.12, 0, BUMP_DUST, false);
            Vfx.dot(top.clone().add(0, 0.2, 0), Particle.CRIT, null, false);
            Fx.sound(top, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 0.7f, 1.0f + 0.25f * index);
            Fx.sound(top, Sound.ENTITY_ITEM_PICKUP, 0.4f, 0.6f + Fx.jitter(0.05f));
        }
    }

    /**
     * A bump in head space: stacked lumps, each a little smaller and higher, turned 45 degrees and a little askew so
     * the stack looks lumpy. The display stays unrotated; all of it lives in the transformation.
     */
    private static Transformation lumpPose(int index, float grow) {
        float size = Math.max(0.05f, BUMP_BASE - index * BUMP_SHRINK) * grow;
        float y = (float) (0.06 + index * BUMP_STEP);
        float tilt = index % 2 == 0 ? 8f : -10f;
        return Displays.transform(index * 0.03f, y, 0, size, size * 0.85f, size, 45f + index * 20f, tilt, 0);
    }

    /** Three bumps: they burst with a "뚝딱!", the victim is stunned and sees stars. */
    private void pop(Bumps bump) {
        Player owner = player();
        LivingEntity victim = bump.victim;
        long now = ctx().currentTick();
        long stun = params().ticks("bump_stun");
        bump.count = 0;
        bump.immuneUntil = now + stun + params().ticks("bump_immunity");
        for (int i = 0; i < bump.lumps.size(); i++) {
            BlockDisplay lump = bump.lumps.get(i);
            if (lump.isValid()) {
                // The lumps swell for a tick, then burst.
                Displays.animate(lump, lumpPose(i, 1.8f), 1);
                ctx().runLater(owner, 1, () -> {
                    if (lump.isValid()) {
                        Displays.shrinkAway(ctx(), owner, lump, BUMP_POP_TICKS);
                    }
                });
            }
        }
        bump.lumps.clear();
        if (stun > 0) {
            ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
            ctx().addModifier(owner, victim, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
        }
        double damage = params().num("bump_pop_damage");
        // The burst may come from a hook watching a landed hit; its damage follows a tick later.
        ctx().runLater(owner, 1, () -> Combat.hit(ctx(), owner, victim, damage));

        boolean visible = isVisible(victim);
        Location head = bump.head();
        if (visible) {
            World world = head.getWorld();
            Vfx.dot(head.clone().add(0, 0.3, 0), Particle.FLASH, GOLD_PALETTE.core(), false);
            world.spawnParticle(Particle.DUST, head.clone().add(0, 0.3, 0), Vfx.n(ctx(), 14), 0.25, 0.2, 0.25, 0,
                    BUMP_DUST);
            world.spawnParticle(Particle.CRIT, head.clone().add(0, 0.3, 0), Vfx.n(ctx(), 12), 0.2, 0.2, 0.2, 0.3);
            Vfx.ring(ctx(), head.clone().add(0, 0.2, 0), 0.7, 14, 0, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
            popSign(owner, head.clone().add(0, 0.85, 0));
            Fx.sound(head, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.7f, 1.5f + Fx.jitter(0.1f));
            Fx.sound(head, Sound.BLOCK_ANVIL_LAND, 0.35f, 1.8f + Fx.jitter(0.1f));
            Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.6f);
            if (stun > 0) {
                seeStars(owner, victim, stun);
            }
        }
        owner.sendActionBar(Text.mm("<" + GOLD + "><b>뚝딱!</b></" + GOLD + "> <gray>혹이 터졌습니다</gray> <white>"
                + AbilityInfo.formatNumber(stun / 20.0) + "초 기절</white>"));
    }

    /** A "뚝딱!" sign that pops up over the burst and floats away. */
    private void popSign(Player owner, Location at) {
        if (!canSpawn(1)) {
            return;
        }
        TextDisplay sign = Displays.text(ctx(), owner, at,
                Text.mm("<b><gradient:" + GOLD + ":" + RED + ">뚝딱!</gradient></b>"), 0.3f, 22);
        remember(sign);
        Displays.animateNextTick(ctx(), owner, sign, Displays.centered(0, 0.35f, 0, 1.4f, 1.4f, 1.4f, 0, 0, 0), 4);
        ctx().runLater(owner, 12, () -> Displays.animate(sign, Displays.centered(0, 0.8f, 0, 0.6f, 0.6f, 0.6f, 0, 0,
                0), 8));
    }

    /** Little stars circling the stunned head while the stun lasts. */
    private void seeStars(Player owner, LivingEntity victim, long stun) {
        Loop.repeat(ctx(), owner, 1, 2, Math.max(1, stun / 2), run -> {
            if (!victim.isValid() || victim.isDead() || !isVisible(victim)) {
                return false;
            }
            Location head = victim.getLocation().add(0, victim.getHeight() + 0.25, 0);
            for (int i = 0; i < 3; i++) {
                double angle = run * 0.6 + i * TAU / 3;
                Location star = head.clone().add(Math.cos(angle) * 0.45, 0.06 * Math.sin(run + i), Math.sin(angle)
                        * 0.45);
                Vfx.dot(star, Particle.DUST, STAR_DUST, false);
                if ((run + i) % 3 == 0) {
                    Vfx.dot(star, Particle.WAX_OFF, null, false);
                }
            }
            return true;
        });
    }

    /** Keeps the lumps on their heads, lets them throb, and lets old bumps go down. */
    private void updateBumps(Player owner, long tick, long now) {
        Iterator<Bumps> it = bumps.values().iterator();
        while (it.hasNext()) {
            Bumps bump = it.next();
            LivingEntity victim = bump.victim;
            boolean gone = !victim.isValid() || victim.isDead() || !victim.getWorld().equals(owner.getWorld());
            if (gone || bump.count > 0 && now > bump.expires) {
                for (BlockDisplay lump : bump.lumps) {
                    if (lump.isValid()) {
                        Displays.shrinkAway(ctx(), owner, lump, 6);
                    }
                }
                bump.lumps.clear();
                bump.count = 0;
                if (gone || now >= bump.immuneUntil) {
                    it.remove();
                }
                continue;
            }
            if (bump.lumps.isEmpty()) {
                if (bump.count == 0 && now >= bump.immuneUntil) {
                    it.remove();
                }
                continue;
            }
            boolean hidden = !isVisible(victim);
            Location head = bump.head();
            for (int i = 0; i < bump.lumps.size(); i++) {
                BlockDisplay lump = bump.lumps.get(i);
                if (!lump.isValid()) {
                    continue;
                }
                Displays.moveTo(lump, head);
                if (hidden != bump.hidden) {
                    lump.setViewRange(hidden ? 0f : 1.0f);
                }
                if (tick % BUMP_THROB_PERIOD == 0 && !hidden) {
                    // The bumps throb.
                    int index = i;
                    Displays.animate(lump, lumpPose(index, 1.15f), 3);
                    ctx().runLater(owner, 3, () -> Displays.animate(lump, lumpPose(index, 1f), 4));
                }
            }
            bump.hidden = hidden;
            if (!hidden && tick % 8 == 0) {
                Vfx.dot(Vfx.scatter(head.clone().add(0, 0.25, 0), 0.25), Particle.DUST, BUMP_DUST, false);
            }
        }
    }

    // ------------------------------------------------------------------ active: the club

    @Override
    public ActivationResult onActive() {
        Hat worn = hat;
        if (worn != null && isPranking()) {
            // A club swung from hiding is no secret: the hat slips.
            worn.slip();
        }
        new Club(player()).start();
        return ActivationResult.success();
    }

    /** One overhead smash: the club is raised over the right shoulder and brought down in front. */
    private final class Club {
        private final Player owner;
        private final List<BlockDisplay> parts = new ArrayList<>();

        Club(Player owner) {
            this.owner = owner;
        }

        void start() {
            float yaw = owner.getLocation().getYaw();
            long life = SWING.length + CLUB_HOLD_TICKS + CLUB_FOLD_TICKS + 4;
            if (canSpawn(CLUB.length)) {
                Location feet = Displays.unrotated(owner.getLocation());
                for (ClubPart part : CLUB) {
                    BlockDisplay display = Displays.block(ctx(), owner, feet, part.block().createBlockData(),
                            clubPose(part, 0.1f, yaw, 0f), life);
                    display.setTeleportDuration(1);
                    if (!part.glow()) {
                        display.setBrightness(null);
                    }
                    remember(display);
                    parts.add(display);
                }
            }
            Location hand = shoulder(owner);
            Vfx.cloud(ctx(), hand, Particle.SOUL_FIRE_FLAME, 4, 0.15, 0.02, null, false);
            Fx.sound(hand, Sound.ENTITY_WITCH_CELEBRATE, 0.5f, 1.6f + Fx.jitter(0.1f));
            Fx.sound(hand, Sound.ITEM_ARMOR_EQUIP_GOLD, 0.7f, 0.7f + Fx.jitter(0.05f));
            Loop.repeat(ctx(), owner, 1, 1, SWING.length + CLUB_HOLD_TICKS, this::tick, this::fold);
        }

        private boolean tick(long t) {
            // The club follows the head's yaw, the same yaw the smash and its ground impact use.
            float yaw = owner.getLocation().getYaw();
            Location feet = Displays.unrotated(owner.getLocation());
            int step = (int) Math.min(t, SWING.length - 1);
            float angle = SWING[step];
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay display = parts.get(i);
                if (!display.isValid() || !display.getWorld().equals(owner.getWorld())) {
                    continue;
                }
                Displays.moveTo(display, feet);
                Displays.animate(display, clubPose(CLUB[i], CLUB_SCALE, yaw, angle), 1);
            }
            if (t < STRIKE_TICK) {
                windup(t);
            } else if (t == STRIKE_TICK) {
                smash();
            }
            return true;
        }

        /** Raising the club: a rising whoosh and blue flame licking along the club head. */
        private void windup(long t) {
            Location top = shoulder(owner).add(0, 0.9, 0);
            Vfx.dot(Vfx.scatter(top, 0.25), Particle.SOUL_FIRE_FLAME, null, false);
            if (t == 1) {
                Fx.sound(top, Sound.ITEM_MACE_SMASH_AIR, 0.6f, 0.8f + Fx.jitter(0.05f));
            }
            if (t == STRIKE_TICK - 1) {
                Fx.sound(top, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.5f + Fx.jitter(0.05f));
            }
        }

        /** The club comes down: everything in front is smashed, the ground cracks and gold sprays. */
        private void smash() {
            Location origin = owner.getLocation();
            Vector facing = Motion.horizontalFacing(origin);
            Location chest = origin.clone().add(0, owner.getHeight() * 0.55, 0);
            double reach = Math.max(1, params().num("club_reach"));
            double damage = params().num("club_damage");
            int struck = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, chest, reach)) {
                Vector to = enemy.getLocation().toVector().subtract(origin.toVector()).setY(0);
                double flat = to.length();
                if (flat > CLUB_POINT_BLANK && facing.angle(to) > CLUB_HALF_ARC) {
                    continue;
                }
                Location body = Fx.body(enemy);
                if (!Spots.clearPath(chest, body)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                struck++;
                bonk(body);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Vector shove = (flat > 1.0E-3 ? to.clone().multiply(1 / flat) : facing.clone())
                            .multiply(CLUB_SHOVE).setY(CLUB_LIFT);
                    Motion.push(enemy, shove, MAX_SPEED);
                    addBump(enemy);
                }
            }
            impact(origin.clone().add(facing.clone().multiply(CLUB_IMPACT)), facing);
            if (struck > 0) {
                owner.sendActionBar(Text.mm("<" + BLUE + "><b>뚝딱!</b></" + BLUE + "> <white>" + struck
                        + "명</white> <gray>혹 +1</gray>"));
            } else {
                owner.sendActionBar(Text.mm("<gray>헛방망이…</gray>"));
            }
        }

        /** A bonk on a victim: blue fire, gold sparks and a comic wooden thud. */
        private void bonk(Location body) {
            Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.SOUL_FIRE_FLAME, false);
            Vfx.cloud(ctx(), body, Particle.CRIT, 8, 0.25, 0.25, null, false);
            Fx.sound(body, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.8f, 1.2f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 0.8f + Fx.jitter(0.08f));
        }

        /** The ground the club strikes: a crack of dust, a blue flame ring, gold nuggets spraying up. */
        private void impact(Location point, Vector facing) {
            World world = point.getWorld();
            Location ground = point.clone().add(0, 1.2, 0);
            Spots.snapToGround(world, ground, 3.5);
            if (ground.getY() > point.getY() + 1.0) {
                ground = point.clone();
            }
            Location surface = ground.clone().add(0, 0.08, 0);
            BlockData floor = ground.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
            if (floor.getMaterial().isSolid()) {
                world.spawnParticle(Particle.BLOCK, surface, Vfx.n(ctx(), 18), 0.5, 0.05, 0.5, 0.15, floor);
                world.spawnParticle(Particle.DUST_PILLAR, surface, Vfx.n(ctx(), 10), 0.4, 0.05, 0.4, 0.1, floor);
            }
            Vfx.shockwave(ctx(), owner, surface, 0.3, 2.2, 5, Particle.DUST_COLOR_TRANSITION, WISP_GLOW, false);
            Vfx.ring(ctx(), surface, 0.8, 14, 0, Particle.SOUL_FIRE_FLAME, null, false);
            Vfx.dot(surface.clone().add(0, 0.4, 0), Particle.FLASH, GOLD_PALETTE.core(), false);
            // 금 나와라 뚝딱: a little spray of gold nuggets.
            ItemStack nugget = new ItemStack(NUGGET);
            world.spawnParticle(Particle.ITEM, surface.clone().add(0, 0.3, 0), Vfx.n(ctx(), 10), 0.2, 0.1, 0.2, 0.18,
                    nugget);
            world.spawnParticle(Particle.DUST, surface.clone().add(0, 0.4, 0), Vfx.n(ctx(), 10), 0.4, 0.3, 0.4, 0,
                    GOLD_DUST);
            Vfx.arc(ctx(), surface.clone().add(0, 0.2, 0), 1.6, Math.toDegrees(Math.atan2(-facing.getX(),
                    facing.getZ())), 120, 12, Particle.DUST, RED_DUST, false);
            Fx.sound(surface, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 0.9f, 0.9f + Fx.jitter(0.08f));
            Fx.sound(surface, Sound.BLOCK_WOOD_BREAK, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(surface, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.3f + Fx.jitter(0.1f));
            Location glow = surface.clone();
            Loop.repeat(ctx(), owner, 2, 2, 6, run -> {
                Location ember = Vfx.scatter(glow, 1.2).add(0, 0.1 + run * 0.08, 0);
                Vfx.dot(ember, run % 2 == 0 ? Particle.SOUL_FIRE_FLAME : Particle.WAX_OFF, null, false);
                Vfx.dot(Vfx.scatter(glow, 1.0).add(0, 0.15, 0), Particle.DUST, GOLD_DUST, false);
                return true;
            });
        }

        private void fold() {
            for (BlockDisplay display : parts) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, CLUB_FOLD_TICKS);
                }
            }
        }
    }

    /** The owner's right shoulder in the world (turned with the head, like the club). */
    private static Location shoulder(Player owner) {
        Location at = owner.getLocation();
        Quaternionf yaw = yawRotation(at.getYaw());
        Vector3f grip = yaw.transform(new Vector3f(GRIP));
        return at.add(grip.x(), grip.y(), grip.z());
    }

    /**
     * A club part posed for a swing: the club turned to the body's {@code yaw} and tipped forward by
     * {@code swingDegrees} about the shoulder (0 = straight up, 90 = level in front), scaled by {@code scale}. The
     * display stays unrotated at the owner's feet; all of this lives in the transformation.
     */
    private static Transformation clubPose(ClubPart part, float scale, float yaw, float swingDegrees) {
        Quaternionf rotation = yawRotation(yaw).rotateX((float) Math.toRadians(swingDegrees));
        Vector3f grip = yawRotation(yaw).transform(new Vector3f(GRIP));
        Vector3f center = rotation.transform(new Vector3f(part.x(), part.y(), part.z()).mul(scale)).add(grip);
        Vector3f size = new Vector3f(part.sx(), part.sy(), part.sz()).mul(scale);
        Vector3f translation = center.sub(rotation.transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    /** The rotation that turns local +Z (forward) to Minecraft's {@code yaw} (0 = +Z south, 90 = -X west). */
    private static Quaternionf yawRotation(float yaw) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
    }

    // ------------------------------------------------------------------ ultimate: the hat of invisibility

    @Override
    public ActivationResult onUltimate() {
        if (hat != null) {
            return ActivationResult.fail("이미 감투를 쓰고 있습니다");
        }
        Hat worn = new Hat(player());
        hat = worn;
        worn.start();
        return ActivationResult.success();
    }

    /** True while the hat is worn (also during a slip, when only the hat is askew): the wisps stay hidden. */
    private boolean isHatHidden() {
        Hat worn = hat;
        return worn != null && worn.worn && !worn.ended;
    }

    /** True while the hat actually hides the dokkaebi (not during a slip): only then does a hit play a prank. */
    private boolean isPranking() {
        return isHatHidden() && stealth.isActive();
    }

    /** The prank: a melee hit from hiding hits harder. Only the damage changes here. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isPranking() || event.isCancelled() || !isOwnMelee(event, victim)) {
            return;
        }
        double bonus = Math.max(0, params().num("prank_damage"));
        if (bonus > 0) {
            event.setDamage(event.getDamage() + bonus);
        }
    }

    /** The prank landed: a bump, a "뚝딱!", the victim bounces off somewhere silly, and the hat slips. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        Hat worn = hat;
        if (worn == null || !isPranking() || !isOwnMelee(event, victim)) {
            return;
        }
        worn.prank(victim);
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        Hat worn = hat;
        if (worn == null || !isHatHidden() || event.isCancelled() || event.getDamage() <= 0
                || !(event instanceof EntityDamageByEntityEvent)) {
            return;
        }
        worn.slip();
    }

    /** The owner's own melee swing at an enemy (not ability damage, not a sweep). */
    private boolean isOwnMelee(EntityDamageByEntityEvent event, LivingEntity victim) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && event.getCause() == DamageCause.ENTITY_ATTACK && ctx().isEnemy(player(), victim);
    }

    /**
     * The dokkaebi hat: it drops onto the head, the dokkaebi vanishes, pranks whoever it hits from hiding, is knocked
     * askew for a moment by every prank, club swing or blow taken, and flies off when the time is up.
     */
    private final class Hat {
        private final Player owner;
        private final long duration;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private boolean worn;
        private boolean ended;
        private long endTick;
        private long slipUntil = NEVER;
        private int pranks;
        private @Nullable Location lastPrint;

        Hat(Player owner) {
            this.owner = owner;
            this.duration = Math.max(1, params().ticks("hat_duration"));
        }

        void start() {
            float yaw = owner.getLocation().getYaw();
            long life = HAT_DROP_TICKS + duration + 30;
            if (canSpawnHat(HAT_DISPLAYS)) {
                Location feet = Displays.unrotated(owner.getLocation());
                for (int part = 0; part < HAT_DISPLAYS; part++) {
                    Material block = part == 2 ? HAT_BAND : HAT_BLOCK;
                    BlockDisplay display = Displays.block(ctx(), owner, feet, block.createBlockData(),
                            hatPose(part, owner, yaw, (float) HAT_DROP_HEIGHT, 0.6f, 0), life);
                    display.setTeleportDuration(1);
                    hatDisplays.add(display);
                    // The hat would block the dokkaebi's view upwards.
                    owner.hideEntity(ctx().plugin(), display);
                    parts.add(display);
                }
            }
            Location head = owner.getEyeLocation().add(0, 1.5, 0);
            Vfx.ring(ctx(), head, 0.6, 14, 0, Particle.DUST_COLOR_TRANSITION, WISP_GLOW, true);
            Vfx.cloud(ctx(), head, Particle.SOUL_FIRE_FLAME, 6, 0.3, 0.02, null, true);
            Fx.sound(head, Sound.ENTITY_WITCH_CELEBRATE, 0.9f, 1.4f);
            Fx.sound(head, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 0.7f, 1.3f);
            Loop.repeat(ctx(), owner, 1, 1, HAT_DROP_TICKS + duration, this::tick, this::end);
        }

        private boolean tick(long t) {
            if (ended) {
                return false;
            }
            float yaw = owner.getLocation().getYaw();
            float drop = t < HAT_DROP_TICKS ? (float) (HAT_DROP_HEIGHT * (1 - (t + 1.0) / HAT_DROP_TICKS)) : 0f;
            float grow = t < HAT_DROP_TICKS ? 0.6f + 0.4f * (t + 1f) / HAT_DROP_TICKS : 1f;
            long now = ctx().currentTick();
            float tilt = worn && now < slipUntil ? 25f : 0f;
            Location feet = Displays.unrotated(owner.getLocation());
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay display = parts.get(i);
                if (display.isValid() && display.getWorld().equals(owner.getWorld())) {
                    Displays.moveTo(display, feet);
                    Displays.animate(display, hatPose(i, owner, yaw, drop, grow, tilt), 1);
                }
            }
            if (t == HAT_DROP_TICKS - 1) {
                vanish();
            } else if (worn) {
                whileHidden(t, now);
            }
            return true;
        }

        /** The hat lands: the dokkaebi is gone in a puff of blue fire. */
        private void vanish() {
            worn = true;
            endTick = ctx().currentTick() + duration;
            hide(duration + 5);
            Location feet = owner.getLocation().add(0, 0.05, 0);
            Location body = Fx.body(owner);
            World world = body.getWorld();
            Vfx.dot(body, Particle.FLASH, PALETTE.core(), true);
            Vfx.helix(ctx(), feet, 0.6, owner.getHeight() * 1.1, 1.5, 2, 12, 0, Particle.DUST_COLOR_TRANSITION,
                    WISP_GLOW, true);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, body, Vfx.n(ctx(), 18), 0.35, 0.6, 0.35, 0.03, null, true);
            world.spawnParticle(Particle.LARGE_SMOKE, body, Vfx.n(ctx(), 8), 0.3, 0.5, 0.3, 0.02, null, true);
            Vfx.shockwave(ctx(), owner, feet, 0.3, 1.8, 5, Particle.DUST_COLOR_TRANSITION, WISP_FADE, true);
            Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.2f);
            Fx.sound(body, Sound.ENTITY_WITCH_CELEBRATE, 1.0f, 1.7f);
            Fx.sound(body, Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 1.4f);
            owner.sendActionBar(Text.mm("<" + BLUE + "><b>도깨비 감투</b></" + BLUE + "> <gray>"
                    + Fx.seconds(duration) + "초간 사라집니다</gray>"));
        }

        /** Hides the dokkaebi from its enemies (and the hat with it) for {@code ticks}. */
        private void hide(long ticks) {
            int effectTicks = (int) Math.min(Integer.MAX_VALUE, ticks);
            owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, effectTicks, 0, false, false,
                    true));
            stealth.begin(ctx(), owner, ticks, () -> { });
            Stealth.dropAggro(ctx(), owner, AGGRO_DROP_RANGE);
            setHatSeenByEnemies(false);
        }

        private void setHatSeenByEnemies(boolean seen) {
            for (Player viewer : ctx().alivePlayers()) {
                if (viewer.equals(owner) || !ctx().isEnemy(owner, viewer)) {
                    continue;
                }
                for (BlockDisplay display : parts) {
                    if (!display.isValid()) {
                        continue;
                    }
                    if (seen) {
                        viewer.showEntity(ctx().plugin(), display);
                    } else {
                        viewer.hideEntity(ctx().plugin(), display);
                    }
                }
            }
        }

        /** While hidden: faint blue flames at the feet, a giggle now and then, the countdown. */
        private void whileHidden(long t, long now) {
            if (!stealth.isActive() && now >= slipUntil) {
                // The slip is over: the hat is pulled down again.
                long left = endTick - now;
                if (left > 0) {
                    hide(left + 5);
                    Location body = Fx.body(owner);
                    Vfx.cloud(ctx(), body, Particle.SOUL_FIRE_FLAME, 8, 0.3, 0.02, null, false);
                    Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 1.5f);
                }
            }
            if (t % FOOTPRINT_PERIOD == 0) {
                footprint();
            }
            if (t % GIGGLE_PERIOD == GIGGLE_PERIOD / 2) {
                // The giggle is the only sound of an invisible dokkaebi: listen for it.
                Fx.sound(owner.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.45f, 1.8f + Fx.jitter(0.1f));
            }
            if (t % 20 == 0 && stealth.isActive()) {
                stealth.hideFromEnemies(ctx(), owner);
                Stealth.dropAggro(ctx(), owner, AGGRO_DROP_RANGE);
                setHatSeenByEnemies(false);
            }
            if (t % 5 == 0) {
                long left = Math.max(0, endTick - now);
                String state = now < slipUntil ? "<" + RED + ">감투가 들썩였습니다!</" + RED + ">"
                        : "<gray>" + Fx.seconds(left) + "초</gray>";
                owner.sendActionBar(Text.mm("<" + BLUE + ">◆ 도깨비 감투</" + BLUE + "> " + state
                        + (pranks > 0 ? " <dark_gray>|</dark_gray> <" + GOLD + ">장난 " + pranks + "회</" + GOLD + ">"
                        : "")));
            }
        }

        /** The only tell: a faint blue flame where the invisible dokkaebi walked. */
        private void footprint() {
            Location feet = owner.getLocation();
            Location previous = lastPrint;
            lastPrint = feet.clone();
            if (previous != null && previous.getWorld().equals(feet.getWorld())
                    && previous.distanceSquared(feet) < 0.25) {
                return;
            }
            Location print = feet.clone().add(Fx.jitter(0.15f), 0.05, Fx.jitter(0.15f));
            Vfx.dot(print, Particle.SOUL_FIRE_FLAME, null, false);
            Vfx.dot(print.clone().add(0, 0.05, 0), Particle.DUST_COLOR_TRANSITION, WISP_SMALL, false);
        }

        /**
         * A melee hit from hiding: a bump, a "뚝딱!" and the victim bounces off somewhere silly; the giggling
         * dokkaebi's hat slips, so it shows for a moment.
         */
        void prank(LivingEntity victim) {
            pranks++;
            slip();
            addBump(victim);
            Location body = Fx.body(victim);
            World world = body.getWorld();
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, body, Vfx.n(ctx(), 10), 0.25, 0.35, 0.25, 0.04);
            world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 6), 0.25, 0.35, 0.25, 0, GOLD_DUST);
            popSign(owner, body.clone().add(0, 1.2, 0));
            Fx.sound(body, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.6f, 1.7f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_WITCH_CELEBRATE, 0.6f, 1.9f + Fx.jitter(0.1f));
            // Vanilla knockback is applied after the hit; the bounce replaces it next tick, built from scratch (a
            // player's server-side velocity is stale and cannot be added to).
            double angle = ThreadLocalRandom.current().nextDouble(TAU);
            double speed = Math.max(0, params().num("prank_knock")) / SLIDE_PER_SPEED;
            Vector bounce = new Vector(Math.cos(angle) * speed, PRANK_LIFT, Math.sin(angle) * speed);
            ctx().runLater(owner, 1, () -> {
                if (!Combat.isTargetable(ctx(), owner, victim)) {
                    return;
                }
                Motion.push(victim, bounce, MAX_SPEED);
                Location from = Fx.body(victim);
                Vfx.trail(from, from.clone().add(bounce.clone().setY(0).multiply(4)), PALETTE.body(), 10, false);
                Vfx.cloud(ctx(), victim.getLocation().add(0, 0.1, 0), Particle.POOF, 4, 0.2, 0.02, null, false);
            });
        }

        /** A prank, a club swing or a blow: the hat is knocked askew and the dokkaebi shows for a moment. */
        void slip() {
            if (!stealth.isActive()) {
                return;
            }
            long now = ctx().currentTick();
            slipUntil = now + Math.max(1, params().ticks("hat_slip"));
            stealth.end(ctx(), owner);
            removeInvisibility();
            setHatSeenByEnemies(true);
            Location body = Fx.body(owner);
            Vfx.cloud(ctx(), body, Particle.SOUL_FIRE_FLAME, 8, 0.3, 0.03, null, false);
            Vfx.cloud(ctx(), body, Particle.SMOKE, 6, 0.3, 0.02, null, false);
            Fx.sound(body, Sound.ENTITY_ALLAY_HURT, 0.8f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.BLOCK_WOOL_BREAK, 0.8f, 0.8f);
        }

        /** Time is up: the hat flies off and the dokkaebi reappears with a laugh. */
        private void end() {
            if (ended) {
                return;
            }
            ended = true;
            if (hat == this) {
                hat = null;
            }
            stealth.end(ctx(), owner);
            removeInvisibility();
            setHatSeenByEnemies(true);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (BlockDisplay display : parts) {
                if (!display.isValid()) {
                    continue;
                }
                Transformation current = display.getTransformation();
                Quaternionf tumble = new Quaternionf(current.getLeftRotation()).rotateXYZ(random.nextFloat(),
                        random.nextFloat() * 3f, random.nextFloat());
                Transformation flung = new Transformation(new Vector3f(current.getTranslation()).add(0, 1.6f, 0),
                        tumble, new Vector3f(current.getScale()), new Quaternionf());
                Displays.animate(display, flung, 8);
                ctx().runLater(owner, 8, () -> {
                    if (display.isValid()) {
                        Displays.shrinkAway(ctx(), owner, display, 5);
                    }
                });
            }
            if (!owner.isOnline() || !ctx().isAlive(owner)) {
                return;
            }
            Location body = Fx.body(owner);
            World world = body.getWorld();
            Vfx.dot(body, Particle.FLASH, PALETTE.core(), false);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, body, Vfx.n(ctx(), 14), 0.3, 0.6, 0.3, 0.04);
            Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.05, 0), 0.3, 1.4, 4,
                    Particle.DUST_COLOR_TRANSITION, WISP_FADE, false);
            Fx.sound(body, Sound.ENTITY_WITCH_CELEBRATE, 0.9f, 1.5f);
            Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7f, 0.9f);
            owner.sendActionBar(Text.mm("<gray>감투가 벗겨졌습니다</gray>" + (pranks > 0
                    ? " <dark_gray>|</dark_gray> <" + GOLD + ">장난 " + pranks + "회</" + GOLD + ">" : "")));
        }

        private void removeInvisibility() {
            PotionEffect effect = owner.getPotionEffect(PotionEffectType.INVISIBILITY);
            if (effect != null && effect.getAmplifier() == 0) {
                owner.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
        }
    }

    /**
     * One part of the hat in head space: 0 the black cap, 1 its raised crown, 2 a glowing blue band. It sits on the
     * owner's head turned to {@code yaw}, lifted by {@code drop} while it falls, and tipped back by {@code tilt}
     * degrees when it slips.
     */
    private static Transformation hatPose(int part, Player owner, float yaw, float drop, float grow, float tilt) {
        float top = (float) owner.getEyeHeight() + 0.2f + drop;
        Vector3f center;
        Vector3f size;
        switch (part) {
            case 0 -> {
                center = new Vector3f(0, top, 0);
                size = new Vector3f(0.52f, 0.2f, 0.52f);
            }
            case 1 -> {
                center = new Vector3f(0, top + 0.17f, -0.03f);
                size = new Vector3f(0.4f, 0.17f, 0.36f);
            }
            default -> {
                center = new Vector3f(0, top - 0.04f, 0);
                size = new Vector3f(0.54f, 0.05f, 0.54f);
            }
        }
        size.mul(grow);
        Quaternionf rotation = yawRotation(yaw).rotateX((float) Math.toRadians(-tilt));
        Vector3f pivot = new Vector3f(0, top - 0.1f, 0);
        Vector3f offset = rotation.transform(new Vector3f(center).sub(pivot)).add(pivot);
        Vector3f translation = offset.sub(rotation.transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    // ------------------------------------------------------------------ display budget

    /** True if {@code count} more wisp, club, bump or sign displays fit (the hat's share stays free). */
    private boolean canSpawn(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS - HAT_DISPLAYS;
    }

    /** True if {@code count} more displays fit in the hat's own share. */
    private boolean canSpawnHat(int count) {
        hatDisplays.removeIf(display -> !display.isValid());
        return hatDisplays.size() + count <= HAT_DISPLAYS;
    }

    private void remember(Display display) {
        displays.add(display);
    }
}
