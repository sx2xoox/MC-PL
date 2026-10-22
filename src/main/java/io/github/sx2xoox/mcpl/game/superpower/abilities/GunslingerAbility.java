package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 총잡이 — a western gunslinger with a six-shooter. The active fires one hitscan revolver shot (short range, bonus
 * damage on the head); the passive is the cylinder: six rounds with only a short gap between shots, then the shells
 * are dumped and the gun is reloaded over the whole cooldown (or quietly topped off after a while without shooting).
 * The ultimate is high noon: for two seconds the gunslinger draws and slowly lets a reticle fill on every enemy in
 * front of it, then shoots all of them at once, each as hard as its reticle had filled.
 *
 * <p>Unlike the 저격수 (one heavy aimed shot from afar, marks that only raise damage), the gunslinger fights at
 * pistol range with many light shots, and its execution is a multi-target lock-on that every victim sees coming and
 * can break by taking cover or leaving its sight.
 *
 * <p>Look: brass, old leather and gun smoke. Every shot flashes a small brass star at the muzzle, draws a white-hot
 * tracer with a brass glow and leaves a wisp of smoke; a head hit rings a bell and pops "헤드샷!". When the cylinder is
 * empty six brass shells tumble to the ground with a tinkle, and while it reloads a ring of six chambers turns at the
 * gunslinger's hip, each one lighting up with a click. High noon raises a blazing sun over the gunslinger and paints
 * a clock face on the ground whose hand sweeps to twelve; each victim gets a reticle over its head that tightens and
 * turns from gold to red, and when the bell strikes noon every locked enemy is shot in the same instant.
 */
public final class GunslingerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("gunslinger", "총잡이", Material.GUNPOWDER)
            .color(TextColor.color(0xD9A441))
            .difficulty(3)
            .passive("6연발",
                    "속사는 리볼버 탄창의 {rounds}발을 씁니다. 한 발을 쏘면 {shot_interval}초 뒤에 다음 발을 쏠 수 있고, "
                            + "탄창을 다 비우면 탄피를 털어 내고 {cooldown}초간 재장전합니다. {idle_reload}초간 쏘지 않으면 "
                            + "빈 약실이 저절로 채워집니다.")
            .active("속사",
                    "허리춤의 리볼버를 뽑아 바라보는 방향으로 한 발을 쏩니다({shot_range}블록). 처음 맞은 적은 "
                            + "{shot_damage} 피해를 입고, 머리에 맞으면 피해가 {headshot_bonus}% 늘어납니다.",
                    5)
            .ultimate("정오의 결투",
                    "{noon_draw}초간 리볼버를 겨누며(이동 속도 -{noon_slow}%) 정면 {noon_cone}° 시야 안, {noon_range}블록 "
                            + "이내의 적에게 조준 표식을 채웁니다. 표식은 시야에 들어와 있는 동안 차오르고, 벽 뒤로 숨거나 시야를 "
                            + "벗어나면 식습니다. 시간이 다 되면 표식이 남은 적 최대 {noon_targets}명을 한꺼번에 쏴 표식이 찬 "
                            + "만큼 {noon_min}~{noon_max} 피해를 줍니다.")
            .param("rounds", 6)
            .param("shot_interval", 0.3)
            .param("idle_reload", 6)
            .param("shot_range", 16)
            .param("shot_damage", 1.5)
            .param("headshot_bonus", 100)
            .param("noon_draw", 2)
            .param("noon_slow", 60)
            .param("noon_cone", 110)
            .param("noon_range", 40)
            .param("noon_targets", 6)
            .param("noon_min", 3)
            .param("noon_max", 9)
            .build();

    private static final String COLOR_TAG = "#D9A441";
    private static final String RED_TAG = "#E5484D";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** White-gold muzzle light, brass, old saddle leather. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF1C9, 0xD9A441, 0x5A3A1E);
    /** The noon reticle: pale gold, blood red, dried blood. */
    private static final Vfx.Palette NOON = Vfx.Palette.of(0xFFE7A0, 0xE5484D, 0x5C0B0E);
    private static final Particle.DustTransition HOT = PALETTE.glow(0.8f);
    private static final Particle.DustTransition EMBER = PALETTE.fade(0.6f);
    private static final Particle.DustOptions BRASS = PALETTE.dust(0.75f);
    private static final Particle.DustOptions EMPTY_CHAMBER = new Particle.DustOptions(Color.fromRGB(0x3A2E22), 0.6f);
    private static final Particle.DustOptions LEATHER = new Particle.DustOptions(PALETTE.edge(), 0.9f);
    private static final Particle.DustOptions SAND = new Particle.DustOptions(Color.fromRGB(0xC8A878), 1.1f);
    private static final Particle.DustOptions NOON_MARK = new Particle.DustOptions(NOON.body(), 1.2f);
    private static final Particle.DustTransition NOON_GLOW = NOON.glow(1.0f);
    private static final Particle.DustTransition NOON_FADE = NOON.fade(0.9f);

    /** Block and item types of the visuals (block data is created on use: it needs a running server). */
    private static final Material TRACER_BLOCK = Material.WHITE_CONCRETE;
    private static final Material FLASH_BLOCK = Material.YELLOW_CONCRETE;
    private static final Material SHELL_ITEM = Material.GOLD_NUGGET;
    private static final Material SUN_CORE = Material.OCHRE_FROGLIGHT;
    private static final Material SUN_HALO = Material.YELLOW_STAINED_GLASS;

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;

    // active
    private static final double SHOT_RAY = 0.12;
    /** The top share of a target's height that counts as its head. */
    private static final double HEAD_SHARE = 0.2;
    private static final float TRACER_WIDTH = 0.06f;
    private static final int TRACER_FADE_TICKS = 3;
    private static final float FLASH_SIZE = 0.34f;
    private static final int HEADSHOT_LABEL_TICKS = 16;
    private static final int MAX_LINE_POINTS = 60;

    // passive: the cylinder
    private static final long DUMP_DELAY = 4;
    private static final double SHELL_GRAVITY = 0.05;
    private static final int SHELL_FALL_TICKS = 18;
    private static final long SHELL_REST_TICKS = 12;
    private static final double CYLINDER_RADIUS = 0.22;

    // ultimate: high noon
    private static final double SUN_HEIGHT = 6.5;
    private static final float SUN_SIZE = 1.3f;
    private static final float SUN_HALO_SIZE = 2.1f;
    private static final double CLOCK_RADIUS = 2.1;
    /** A target in sight for this share of the draw is fully locked. */
    private static final double LOCK_FULL_SHARE = 0.75;
    /** Out of sight, a lock drains this many times faster than it fills. */
    private static final double LOCK_DECAY = 2.0;
    private static final float RETICLE_LOOSE = 1.7f;
    private static final float RETICLE_TIGHT = 0.85f;
    private static final int MAX_RETICLES = 16;
    private static final double RETICLE_ABOVE = 0.75;

    // cylinder state
    private int rounds = -1;
    private boolean reloading;
    private long reloadTicks;
    private int chambersShown;
    private long lastShot = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
    private double nextFactor = 1.0;
    /** The target of the shot being dealt right now, and whether it landed (see {@link #onDamageDealt}). */
    private @Nullable LivingEntity pendingTarget;
    private boolean landed;
    private final List<Display> displays = new ArrayList<>();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        rounds = maxRounds();
    }

    @Override
    public void onRevoke() {
        reloading = false;
        pendingTarget = null;
        displays.clear();
    }

    @Override
    public double cooldownMultiplier() {
        return nextFactor;
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() && victim.equals(pendingTarget)) {
            landed = true;
        }
    }

    private int maxRounds() {
        return Math.max(1, params().integer("rounds"));
    }

    // ------------------------------------------------------------------ passive: six-shooter

    @Override
    public void onTick(long tick) {
        Player owner = player();
        int max = maxRounds();
        if (rounds < 0 || rounds > max) {
            rounds = max;
        }
        if (reloading) {
            double remaining = ctx().cooldownRemaining(owner);
            if (remaining <= 0) {
                finishReload(owner, true);
                return;
            }
            double total = Math.max(1, reloadTicks) / 20.0;
            double progress = Math.clamp(1 - remaining / total, 0.0, 1.0);
            int loaded = Math.min(max - 1, (int) Math.floor(progress * max));
            if (loaded > chambersShown) {
                chambersShown = loaded;
                chamberClick(owner, loaded, max, remaining);
            }
            if (tick % 2 == 0) {
                drawCylinder(owner, loaded, max, tick);
            }
            return;
        }
        long idle = params().ticks("idle_reload");
        if (rounds < max && idle > 0 && ctx().currentTick() - lastShot >= idle) {
            topOff(owner, max);
        }
    }

    /** The cylinder is empty: the shells are dumped and the reload runs for the whole cooldown. */
    private void startReload(Player owner) {
        reloading = true;
        reloadTicks = Math.max(1, params().ticks(AbilityInfo.COOLDOWN));
        chambersShown = 0;
        ctx().runLater(owner, DUMP_DELAY, () -> dumpShells(owner));
    }

    private void finishReload(Player owner, boolean flourish) {
        reloading = false;
        rounds = maxRounds();
        if (!flourish) {
            return;
        }
        Location hand = hip(owner);
        Vfx.ring(ctx(), hand, CYLINDER_RADIUS + 0.1, 12, 0, Particle.DUST_COLOR_TRANSITION, HOT, false);
        Vfx.cloud(ctx(), hand, Particle.WAX_OFF, 4, 0.12, 0.3, null, false);
        Fx.sound(hand, Sound.ITEM_CROSSBOW_LOADING_END, 0.8f, 1.5f + Fx.jitter(0.05f));
        Fx.sound(hand, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.6f, 1.8f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⁍ 장전 완료</" + COLOR_TAG + "> <white>"
                + pips(rounds, maxRounds()) + "</white>"));
    }

    /** No shot for a while: the gunslinger spins the cylinder and fills the empty chambers. */
    private void topOff(Player owner, int max) {
        int filled = max - rounds;
        rounds = max;
        Location hand = hip(owner);
        drawCylinder(owner, max, max, ctx().currentTick());
        Vfx.cloud(ctx(), hand, Particle.WAX_OFF, 3, 0.1, 0.2, null, false);
        for (int i = 0; i < Math.min(3, filled); i++) {
            int index = i;
            ctx().runLater(owner, 2L * i + 1, () -> owner.playSound(owner, Sound.ITEM_CROSSBOW_LOADING_MIDDLE,
                    SoundCategory.PLAYERS, 0.5f, 1.4f + 0.1f * index));
        }
        ctx().runLater(owner, 8, () -> Fx.sound(owner.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5f, 1.9f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⁍ 빈 약실을 채웠습니다</" + COLOR_TAG + "> <white>"
                + pips(rounds, max) + "</white>"));
    }

    /** One more chamber is loaded: a click and the meter in the action bar. */
    private void chamberClick(Player owner, int loaded, int max, double remaining) {
        owner.playSound(owner, Sound.ITEM_CROSSBOW_LOADING_MIDDLE, SoundCategory.PLAYERS, 0.6f,
                1.0f + 0.1f * loaded);
        Fx.sound(owner.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.35f, 1.6f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⁍ 재장전</" + COLOR_TAG + "> <white>" + pips(loaded, max)
                + "</white> <gray>" + AbilityInfo.formatNumber(Math.ceil(remaining * 10) / 10.0) + "초</gray>"));
    }

    /** The cylinder turning at the gunslinger's hip: loaded chambers glow brass, empty ones are dark. */
    private void drawCylinder(Player owner, int loaded, int max, long tick) {
        Location hand = hip(owner);
        double turn = tick * 0.35;
        World world = hand.getWorld();
        for (int i = 0; i < max; i++) {
            double angle = turn + i * TAU / max;
            Location at = hand.clone().add(Math.cos(angle) * CYLINDER_RADIUS, Math.sin(angle) * CYLINDER_RADIUS * 0.6,
                    Math.sin(angle) * CYLINDER_RADIUS * 0.8);
            world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, i < loaded ? BRASS : EMPTY_CHAMBER);
        }
    }

    /** Six brass shells tumble out of the open cylinder and tinkle on the ground. */
    private void dumpShells(Player owner) {
        if (!ctx().isAlive(owner)) {
            return;
        }
        Location hand = hip(owner);
        Fx.sound(hand, Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.8f, 1.6f + Fx.jitter(0.05f));
        Fx.sound(hand, Sound.BLOCK_LEVER_CLICK, 0.6f, 0.8f);
        Vfx.cloud(ctx(), hand, Particle.SMOKE, 4, 0.1, 0.02, null, false);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int shells = Math.min(maxRounds(), 6);
        for (int i = 0; i < shells; i++) {
            if (!reserve(1)) {
                break;
            }
            Location from = hand.clone().add(random.nextDouble(-0.08, 0.08), random.nextDouble(-0.05, 0.05),
                    random.nextDouble(-0.08, 0.08));
            Vector velocity = new Vector(random.nextDouble(-0.07, 0.07), random.nextDouble(0.02, 0.1),
                    random.nextDouble(-0.07, 0.07));
            ItemDisplay shell = Displays.item(ctx(), owner, from, new ItemStack(SHELL_ITEM),
                    Displays.centered(0, 0, 0, 0.32f, 0.32f, 0.32f, random.nextFloat() * 360, 90, 0),
                    SHELL_FALL_TICKS + SHELL_REST_TICKS + 8);
            shell.setTeleportDuration(1);
            displays.add(shell);
            Location floor = from.clone();
            Spots.snapToGround(from.getWorld(), floor, 4);
            // No floor close below (a ledge): the shell drops out of sight and fades there.
            double floorY = floor.getY() < from.getY() - 1.0E-4 ? floor.getY() + 0.03 : from.getY() - 4;
            float spin = 25 + random.nextFloat() * 30;
            long delay = i;
            ctx().runLater(owner, 1 + delay, () -> fall(owner, shell, velocity, floorY, spin));
        }
    }

    /** A shell falls with a little gravity, spinning, until it lies on the floor; then it fades. */
    private void fall(Player owner, ItemDisplay shell, Vector start, double floorY, float spin) {
        Vector velocity = start.clone();
        float[] angle = {0};
        Loop.repeat(ctx(), owner, 0, 1, SHELL_FALL_TICKS, tick -> {
            if (!shell.isValid()) {
                return false;
            }
            Location at = shell.getLocation().add(velocity);
            boolean grounded = at.getY() <= floorY;
            if (grounded) {
                at.setY(floorY);
            }
            Displays.moveTo(shell, at);
            angle[0] += spin;
            Displays.animate(shell, Displays.centered(0, 0, 0, 0.32f, 0.32f, 0.32f, angle[0], grounded ? 90 : angle[0],
                    0), 1);
            if (grounded) {
                Fx.sound(at, Sound.BLOCK_CHAIN_PLACE, 0.35f, 1.9f + Fx.jitter(0.1f));
                Vfx.dot(at.clone().add(0, 0.05, 0), Particle.WAX_OFF, null, false);
                return false;
            }
            velocity.setY(velocity.getY() - SHELL_GRAVITY);
            velocity.multiply(0.96);
            return true;
        }, () -> ctx().runLater(owner, SHELL_REST_TICKS, () -> {
            if (shell.isValid()) {
                Displays.shrinkAway(ctx(), owner, shell, 4);
            }
        }));
    }

    private static String pips(int filled, int max) {
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < max; i++) {
            bar.append(i < filled ? '◉' : '○');
        }
        return bar.toString();
    }

    // ------------------------------------------------------------------ active: quick shot

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        int max = maxRounds();
        if (rounds < 0 || rounds > max) {
            rounds = max;
        }
        if (reloading) {
            // Only reachable with cooldowns switched off: the reload was never waited for.
            finishReload(owner, false);
        }
        long cycle = params().ticks(AbilityInfo.COOLDOWN);
        rounds = Math.max(0, rounds - 1);
        lastShot = ctx().currentTick();
        if (rounds == 0) {
            nextFactor = 1.0;
            startReload(owner);
        } else {
            nextFactor = cycle <= 0 ? 1.0 : Math.clamp(params().ticks("shot_interval") / (double) cycle, 0.0, 1.0);
        }
        String result = fire(owner);
        String meter = rounds == 0 ? "<gray>재장전…</gray>" : "<white>" + pips(rounds, max) + "</white>";
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⁍ 속사</" + COLOR_TAG + "> " + meter + result));
        return ActivationResult.success();
    }

    /** Fires one shot along the gunslinger's view. Returns a short MiniMessage suffix for the action bar. */
    private String fire(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector direction = aim(eye);
        double range = Math.max(1, params().num("shot_range"));
        Shot shot = trace(owner, eye, direction, range);
        Location muzzle = muzzle(owner);
        muzzleFlash(muzzle, direction);
        tracer(muzzle, shot.end(), false);
        float pitch = 1.5f + Fx.jitter(0.12f);
        Fx.sound(eye, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.1f, pitch);
        Fx.sound(eye, Sound.ENTITY_GENERIC_EXPLODE, 0.25f, 1.9f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ITEM_CROSSBOW_SHOOT, 0.7f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(eye, Sound.BLOCK_LEVER_CLICK, 0.5f, 1.8f + Fx.jitter(0.1f));

        LivingEntity target = shot.target();
        if (target != null) {
            double damage = Math.max(0, params().num("shot_damage"));
            if (shot.head()) {
                damage *= 1 + Math.max(0, params().num("headshot_bonus")) / 100.0;
            }
            if (strike(target, damage)) {
                bodyImpact(shot.end(), direction, shot.head());
                if (shot.head()) {
                    headshot(owner, target);
                    return " <" + RED_TAG + "><b>헤드샷!</b></" + RED_TAG + ">";
                }
                owner.playSound(owner, Sound.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.6f, 1.6f);
                return " <gray>명중</gray>";
            }
            // Dodged or blocked: the bullet only sparks past.
            Vfx.cloud(ctx(), shot.end(), Particle.ELECTRIC_SPARK, 6, 0.2, 0.1, null, false);
            return "";
        }
        Block block = shot.block();
        if (block != null) {
            blockImpact(shot.end(), block);
        }
        return "";
    }

    /**
     * Deals a shot's damage and tells whether it landed (see {@link #onDamageDealt}): on-hit effects only follow a
     * hit nothing cancelled. Only the active uses it; the volley of high noon must also work borrowed, without hooks.
     */
    private boolean strike(LivingEntity target, double damage) {
        if (!(damage > 0)) {
            return Combat.isTargetable(ctx(), player(), target);
        }
        pendingTarget = target;
        landed = false;
        try {
            Combat.hit(ctx(), player(), target, damage);
        } finally {
            pendingTarget = null;
        }
        return landed;
    }

    private record Shot(Location end, @Nullable LivingEntity target, boolean head, @Nullable Block block) {
    }

    /** The bullet's path: the first enemy the owner can see, or the first block, or the end of the range. */
    private Shot trace(Player owner, Location eye, Vector direction, double range) {
        World world = eye.getWorld();
        RayTraceResult hit = world.rayTrace(eye, direction, range, FluidCollisionMode.NEVER, true, SHOT_RAY,
                entity -> entity instanceof LivingEntity && ctx().isEnemy(owner, entity)
                        && (!(entity instanceof Player hidden) || owner.canSee(hidden)));
        if (hit == null) {
            return new Shot(eye.clone().add(direction.clone().multiply(range)), null, false, null);
        }
        Location point = hit.getHitPosition().toLocation(world);
        if (hit.getHitEntity() instanceof LivingEntity target) {
            double headFrom = target.getBoundingBox().getMaxY() - target.getHeight() * HEAD_SHARE;
            return new Shot(point, target, point.getY() >= headFrom - 1.0E-6, null);
        }
        return new Shot(point, null, false, hit.getHitBlock());
    }

    /** The muzzle blooms: a brass star across the barrel, sparks and a puff of smoke. */
    private void muzzleFlash(Location muzzle, Vector direction) {
        World world = muzzle.getWorld();
        Vfx.dot(muzzle, Particle.FLASH, PALETTE.core(), false);
        disc(muzzle, direction, 0.18, 8, Particle.DUST_COLOR_TRANSITION, HOT, false);
        int sparks = Vfx.n(ctx(), 5);
        for (int i = 0; i < sparks; i++) {
            Vector spray = direction.clone().add(randomUnit().multiply(0.3)).normalize();
            world.spawnParticle(Particle.CRIT, muzzle.getX(), muzzle.getY(), muzzle.getZ(), 0, spray.getX(),
                    spray.getY(), spray.getZ(), 0.6);
        }
        world.spawnParticle(Particle.WHITE_SMOKE, muzzle.getX(), muzzle.getY(), muzzle.getZ(), 0, direction.getX(),
                direction.getY() + 0.1, direction.getZ(), 0.08);
        if (!reserve(1)) {
            return;
        }
        // A flat brass diamond across the barrel, turned to the shot in its transformation only.
        Quaternionf across = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), toJoml(direction))
                .rotateZ((float) Math.toRadians(45 + ThreadLocalRandom.current().nextInt(-15, 16)));
        BlockDisplay star = Displays.block(ctx(), player(), muzzle, FLASH_BLOCK.createBlockData(),
                box(across, FLASH_SIZE, FLASH_SIZE, 0.02f), 6);
        displays.add(star);
        ctx().runLater(player(), 1, () -> {
            if (star.isValid()) {
                Displays.animate(star, box(across, FLASH_SIZE * 0.05f, FLASH_SIZE * 0.05f, 0.01f), 3);
            }
        });
    }

    /**
     * The bullet's path: a white-hot beam (outlined in brass for high noon) that thins out within a few ticks, a brass
     * glow line and a lingering smoke line.
     */
    private void tracer(Location from, Location to, boolean noon) {
        Vector delta = to.toVector().subtract(from.toVector());
        if (delta.lengthSquared() < 1.0E-4) {
            return;
        }
        if (reserve(1)) {
            BlockDisplay beam = Displays.block(ctx(), player(), from, TRACER_BLOCK.createBlockData(),
                    beamPose(delta, noon ? TRACER_WIDTH * 1.6f : TRACER_WIDTH), TRACER_FADE_TICKS + 4L);
            if (noon) {
                Displays.outline(beam, NOON.body());
            }
            displays.add(beam);
            Displays.animateNextTick(ctx(), player(), beam, beamPose(delta, 0.004f), TRACER_FADE_TICKS);
        }
        line(from, to, 1.0, Particle.DUST_COLOR_TRANSITION, noon ? NOON_GLOW : HOT, noon);
        line(from, to, 0.3, Particle.WHITE_SMOKE, null, false);
        Loop.repeat(ctx(), player(), 2, 2, 2, run -> {
            line(from, to, 0.3 - run * 0.1, Particle.DUST_COLOR_TRANSITION, EMBER, false);
            return true;
        });
    }

    /** A bullet in a body: a brass flash, sparks along the shot and a little red. */
    private void bodyImpact(Location at, Vector direction, boolean head) {
        World world = at.getWorld();
        Vfx.dot(at, Particle.FLASH, head ? NOON.body() : PALETTE.body(), false);
        Vfx.cloud(ctx(), at, Particle.CRIT, 8, 0.15, 0.3, null, false);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, at, Vfx.n(ctx(), 2), 0.1, 0.1, 0.1, 0.1);
        disc(at, direction, 0.3, 10, Particle.DUST, BRASS, false);
        Fx.sound(at, Sound.ENTITY_ARROW_HIT_PLAYER, 0.7f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.3f + Fx.jitter(0.1f));
    }

    /** A head hit: a ring snaps round the head, "헤드샷!" pops up and a bell rings for the shooter. */
    private void headshot(Player owner, LivingEntity target) {
        Location head = target.getEyeLocation();
        Vfx.ring(ctx(), head, 0.45, 14, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_COLOR_TRANSITION,
                NOON_GLOW, false);
        Vfx.cloud(ctx(), head, Particle.WAX_OFF, 4, 0.2, 0.3, null, false);
        if (reserve(1)) {
            TextDisplay label = Displays.text(ctx(), owner, head.clone().add(0, 0.7, 0),
                    Text.mm("<b><gradient:#FFF1C9:" + RED_TAG + ">헤드샷!</gradient></b>"), 0.55f,
                    HEADSHOT_LABEL_TICKS);
            displays.add(label);
            Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.3f, 0, 1.15f, 1.15f, 1.15f, 0, 0, 0),
                    4);
            ctx().runLater(owner, HEADSHOT_LABEL_TICKS - 5, () -> {
                if (label.isValid()) {
                    Displays.shrinkAway(ctx(), owner, label, 4);
                }
            });
        }
        owner.playSound(owner, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 0.9f, 1.9f);
        owner.playSound(owner, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.5f, 2.0f);
    }

    /** A bullet in a block: chips of it, sparks and dust. */
    private void blockImpact(Location at, Block block) {
        World world = at.getWorld();
        if (block.getType().isSolid()) {
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 8), 0.08, 0.08, 0.08, 0.1, block.getBlockData());
        }
        Vfx.cloud(ctx(), at, Particle.ELECTRIC_SPARK, 4, 0.08, 0.15, null, false);
        Vfx.cloud(ctx(), at, Particle.SMOKE, 3, 0.05, 0.02, null, false);
        Fx.sound(at, Sound.BLOCK_IRON_HIT, 0.5f, 1.6f + Fx.jitter(0.15f));
    }

    // ------------------------------------------------------------------ ultimate: high noon

    @Override
    public ActivationResult onUltimate() {
        new Noon(player()).start();
        return ActivationResult.success();
    }

    /** One victim's lock: how far it has filled (0..1) and its reticle. */
    private static final class Lock {
        final LivingEntity target;
        double progress;
        boolean full;
        @Nullable TextDisplay reticle;
        float scale = RETICLE_LOOSE;

        Lock(LivingEntity target) {
            this.target = target;
        }
    }

    /**
     * High noon: the draw (the gunslinger slowed, a sun overhead, a clock face whose hand sweeps to twelve and a reticle
     * filling over every enemy in sight), then one volley at every enemy whose lock has not drained away.
     */
    private final class Noon implements LongPredicate {

        private final Player owner;
        private final long draw;
        private final double range;
        private final double cosHalfCone;
        private final float noonYaw;
        private final Map<UUID, Lock> locks = new LinkedHashMap<>();
        private final BlockDisplay[] sun = new BlockDisplay[2];
        private @Nullable NamespacedKey slow;
        private boolean fired;

        Noon(Player owner) {
            this.owner = owner;
            this.draw = Math.max(10, params().ticks("noon_draw"));
            this.range = Math.max(1, params().num("noon_range"));
            double cone = Math.clamp(params().num("noon_cone"), 1, 360);
            this.cosHalfCone = Math.cos(Math.toRadians(cone / 2));
            this.noonYaw = owner.getLocation().getYaw();
        }

        void start() {
            double slowShare = Math.clamp(params().num("noon_slow") / 100.0, 0.0, 1.0);
            if (slowShare > 0) {
                slow = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -slowShare,
                        Operation.MULTIPLY_SCALAR_1, draw + 2);
            }
            Location above = sunSpot();
            sun[0] = Displays.block(ctx(), owner, above, SUN_CORE.createBlockData(),
                    cubePose(0.1f, new Quaternionf().rotateY(0.785f).rotateX(0.615f)), draw + 20);
            sun[1] = Displays.block(ctx(), owner, above, SUN_HALO.createBlockData(),
                    cubePose(0.1f, new Quaternionf()), draw + 20);
            for (BlockDisplay part : sun) {
                part.setViewRange(2.5f);
                part.setTeleportDuration(2);
                displays.add(part);
            }
            Location feet = owner.getLocation();
            Fx.sound(feet, Sound.BLOCK_BELL_USE, 1.6f, 0.6f);
            Fx.sound(feet, Sound.BLOCK_BELL_RESONATE, 1.0f, 0.55f);
            Fx.sound(feet, Sound.ITEM_ELYTRA_FLYING, 0.25f, 0.6f);
            Fx.sound(feet, Sound.BLOCK_LEVER_CLICK, 0.9f, 0.7f);
            Vfx.cloud(ctx(), feet.clone().add(0, 0.2, 0), Particle.DUST, 14, 1.2, 0, SAND, true);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>☀ 정오의 결투</b></" + COLOR_TAG + "> <gray>조준 중…</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, draw + 1, this, this::cleanup);
        }

        @Override
        public boolean test(long tick) {
            if (tick >= draw) {
                fire();
                return false;
            }
            scan();
            double progress = (tick + 1) / (double) draw;
            if (tick % 2 == 0) {
                drawClock(progress);
                updateSun(tick, progress);
            }
            follow(tick);
            if (tick % 5 == 0) {
                Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, tick % 10 == 0 ? 1.6f : 1.2f);
            }
            if (tick % 6 == 3) {
                // Hot wind drifting across the duel.
                Location gust = Vfx.scatter(owner.getLocation(), 6).add(0, 0.3, 0);
                Vfx.trail(gust, gust.clone().add(randomUnit().setY(0).multiply(2.5)), Color.fromRGB(0xC8A878), 14,
                        false);
            }
            return true;
        }

        /** Fills the lock of every enemy in sight and drains the others. */
        private void scan() {
            Location eye = owner.getEyeLocation();
            Vector look = aim(eye);
            double fill = 1.0 / (draw * LOCK_FULL_SHARE);
            Set<UUID> seen = new HashSet<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
                if (!inSight(eye, look, enemy)) {
                    continue;
                }
                seen.add(enemy.getUniqueId());
                Lock lock = locks.computeIfAbsent(enemy.getUniqueId(), id -> new Lock(enemy));
                lock.progress = Math.min(1.0, lock.progress + fill);
                if (lock.progress >= 1.0 && !lock.full) {
                    lock.full = true;
                    lockedFx(lock);
                }
            }
            locks.values().removeIf(lock -> {
                if (seen.contains(lock.target.getUniqueId())) {
                    return false;
                }
                lock.progress -= fill * LOCK_DECAY;
                lock.full = false;
                boolean gone = lock.progress <= 0 || !Combat.isTargetable(ctx(), owner, lock.target)
                        || isHidden(lock.target);
                if (gone) {
                    removeReticle(lock);
                }
                return gone;
            });
        }

        /** In the gunslinger's cone of view, within range, visible to it and not behind a wall. */
        private boolean inSight(Location eye, Vector look, LivingEntity enemy) {
            if (isHidden(enemy) || !enemy.getWorld().equals(eye.getWorld())) {
                return false;
            }
            Location head = enemy.getEyeLocation();
            Location body = Fx.body(enemy);
            Vector to = body.toVector().subtract(eye.toVector());
            double distance = to.length();
            if (distance > range + enemy.getWidth()) {
                return false;
            }
            if (distance > 0.8 && to.multiply(1 / distance).dot(look) < cosHalfCone) {
                return false;
            }
            return Spots.clearPath(eye, head) || Spots.clearPath(eye, body);
        }

        private boolean isHidden(LivingEntity enemy) {
            return enemy instanceof Player hidden && !owner.canSee(hidden);
        }

        /** Every reticle follows its victim; it tightens and reddens as the lock fills. */
        private void follow(long tick) {
            for (Lock lock : locks.values()) {
                LivingEntity target = lock.target;
                Location over = target.getLocation().add(0, target.getHeight() + RETICLE_ABOVE, 0);
                TextDisplay reticle = lock.reticle;
                if (reticle == null || !reticle.isValid()) {
                    if (countReticles() >= MAX_RETICLES || !reserve(1)) {
                        continue;
                    }
                    reticle = Displays.text(ctx(), owner, over, reticleText(lock), RETICLE_LOOSE, draw + 10);
                    reticle.setViewRange(2.0f);
                    reticle.setTeleportDuration(1);
                    lock.reticle = reticle;
                    displays.add(reticle);
                    continue;
                }
                Displays.moveTo(reticle, over);
                if (tick % 2 == 0) {
                    reticle.text(reticleText(lock));
                    float scale = (float) (RETICLE_LOOSE + (RETICLE_TIGHT - RETICLE_LOOSE) * lock.progress);
                    if (Math.abs(scale - lock.scale) > 0.01f) {
                        lock.scale = scale;
                        Displays.animate(reticle, Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, 0), 2);
                    }
                }
                if (tick % 3 == 0) {
                    Location feet = target.getLocation().add(0, 0.08, 0);
                    double radius = 1.2 - 0.6 * lock.progress;
                    Vfx.ring(ctx(), feet, radius, 10, tick * 0.3, Particle.DUST_COLOR_TRANSITION,
                            lock.full ? NOON_GLOW : NOON_FADE, true);
                }
            }
        }

        private int countReticles() {
            int count = 0;
            for (Lock lock : locks.values()) {
                if (lock.reticle != null && lock.reticle.isValid()) {
                    count++;
                }
            }
            return count;
        }

        private Component reticleText(Lock lock) {
            String color = lock.full ? RED_TAG : lerpColor(0xFFF1C9, 0xE5A13A, lock.progress * 1.25);
            String glyph = lock.full ? "<b>◎</b>" : "◎";
            return Text.mm("<" + color + ">" + glyph + "</" + color + ">");
        }

        /** A victim is fully locked: a sharp ping for everyone near it and a flash on the reticle. */
        private void lockedFx(Lock lock) {
            Location over = lock.target.getLocation().add(0, lock.target.getHeight() + RETICLE_ABOVE, 0);
            Vfx.dot(over, Particle.FLASH, NOON.body(), true);
            Fx.sound(over, Sound.BLOCK_NOTE_BLOCK_BIT, 0.9f, 1.9f);
            owner.playSound(owner, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.6f, 1.8f);
        }

        /** The clock on the ground: twelve marks, a short hand on twelve and a long hand sweeping round to it. */
        private void drawClock(double progress) {
            Location center = owner.getLocation().add(0, 0.06, 0);
            World world = center.getWorld();
            double noon = Math.toRadians(noonYaw);
            int marks = 12;
            for (int i = 0; i < marks; i++) {
                double yaw = noon + i * TAU / marks;
                Location at = center.clone().add(-Math.sin(yaw) * CLOCK_RADIUS, 0, Math.cos(yaw) * CLOCK_RADIUS);
                if (i == 0) {
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0, NOON_GLOW, true);
                } else {
                    world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, i % 3 == 0 ? BRASS : LEATHER, true);
                }
            }
            // The long hand starts at nine and sweeps clockwise (growing yaw) to twelve.
            double hand = noon - Math.toRadians(270) * (1 - progress);
            hand(center, noon, CLOCK_RADIUS * 0.55, Particle.DUST, LEATHER);
            hand(center, hand, CLOCK_RADIUS * 0.9, Particle.DUST_COLOR_TRANSITION, progress > 0.85 ? NOON_GLOW : HOT);
        }

        private <T> void hand(Location center, double yaw, double length, Particle particle, T data) {
            int points = Vfx.n(ctx(), 6);
            for (int i = 1; i <= points; i++) {
                double t = length * i / points;
                Vfx.dot(center.clone().add(-Math.sin(yaw) * t, 0, Math.cos(yaw) * t), particle, data, true);
            }
        }

        private Location sunSpot() {
            return owner.getLocation().add(0, SUN_HEIGHT, 0);
        }

        /** The sun rises over the gunslinger, grows and turns, burning brighter towards noon. */
        private void updateSun(long tick, double progress) {
            Location above = sunSpot().add(0, -0.8 * (1 - progress), 0);
            float grow = (float) Math.min(1.0, (tick + 2) / 8.0);
            float angle = tick * 0.08f;
            if (sun[0] != null && sun[0].isValid()) {
                Displays.moveTo(sun[0], above);
                Displays.animate(sun[0], cubePose(SUN_SIZE * grow,
                        new Quaternionf().rotateY(0.785f + angle).rotateX(0.615f)), 2);
            }
            if (sun[1] != null && sun[1].isValid()) {
                Displays.moveTo(sun[1], above);
                Displays.animate(sun[1], cubePose(SUN_HALO_SIZE * grow * (float) (0.95 + 0.05 * Math.sin(tick * 0.6)),
                        new Quaternionf().rotateY(-angle * 0.7f).rotateZ(0.4f)), 2);
            }
            if (tick % 4 == 0) {
                Vfx.ring(ctx(), above, 1.6 + 0.3 * progress, 14, tick * 0.2, Particle.DUST_COLOR_TRANSITION, HOT,
                        true);
                Vfx.trail(above, owner.getLocation().add(0, owner.getHeight(), 0), PALETTE.core(), 10, true);
            }
        }

        /** Noon: every enemy whose lock still holds is shot at the same instant. */
        private void fire() {
            fired = true;
            if (slow != null) {
                ctx().removeModifier(owner, slow);
                slow = null;
            }
            Location eye = owner.getEyeLocation();
            Location muzzle = muzzle(owner);
            List<Lock> volley = new ArrayList<>(locks.values());
            // The surest locks first; between equal ones, players before summons.
            volley.sort(Comparator.comparingDouble((Lock lock) -> lock.progress).reversed()
                    .thenComparing(lock -> !(lock.target instanceof Player)));
            int max = Math.max(0, params().integer("noon_targets"));
            double minDamage = Math.max(0, params().num("noon_min"));
            double maxDamage = Math.max(minDamage, params().num("noon_max"));
            int shots = 0;
            for (Lock lock : volley) {
                if (shots >= max) {
                    break;
                }
                LivingEntity target = lock.target;
                removeReticle(lock);
                if (lock.progress <= 0 || !Combat.isTargetable(ctx(), owner, target) || isHidden(target)) {
                    continue;
                }
                Location body = Fx.body(target);
                Location head = target.getEyeLocation();
                Location aimed = Spots.clearPath(eye, body) ? body : Spots.clearPath(eye, head) ? head : null;
                if (aimed == null) {
                    // It made it behind cover in time: the bullet sparks off the wall.
                    RayTraceResult wall = eye.getWorld().rayTraceBlocks(eye, body.toVector().subtract(eye.toVector()),
                            eye.distance(body), FluidCollisionMode.NEVER, true);
                    if (wall != null) {
                        Location at = wall.getHitPosition().toLocation(eye.getWorld());
                        tracer(muzzle, at, true);
                        Vfx.cloud(ctx(), at, Particle.ELECTRIC_SPARK, 8, 0.1, 0.2, null, true);
                    }
                    continue;
                }
                shots++;
                double damage = minDamage + (maxDamage - minDamage) * Math.clamp(lock.progress, 0.0, 1.0);
                tracer(muzzle, aimed, true);
                Combat.hit(ctx(), owner, target, damage);
                noonImpact(aimed, lock.full);
            }
            locks.clear();
            volleyFx(eye, shots);
        }

        private void noonImpact(Location at, boolean full) {
            Vfx.dot(at, Particle.FLASH, NOON.body(), true);
            Vfx.burst(ctx(), at, NOON, full ? 0.7 : 0.5, Particle.CRIT, true);
            Fx.sound(at, Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, 0.9f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f + Fx.jitter(0.1f));
        }

        /** The volley's thunder: a bell, the shots blurred into one blast and a ring of gun smoke. */
        private void volleyFx(Location eye, int shots) {
            Location feet = owner.getLocation().add(0, 0.15, 0);
            Fx.sound(eye, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 2.0f, 1.2f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 1.5f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.BLOCK_BELL_USE, 1.6f, 1.0f);
            for (int i = 1; i < Math.min(shots, 6); i++) {
                ctx().runLater(owner, i, () -> Fx.sound(eye, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f,
                        1.4f + Fx.jitter(0.15f)));
            }
            Vfx.shockwave(ctx(), owner, feet, 0.5, 3.2, 6, Particle.DUST, SAND, true);
            Vfx.cloud(ctx(), eye, Particle.WHITE_SMOKE, 16, 0.5, 0.03, null, true);
            Vfx.cloud(ctx(), eye, Particle.LARGE_SMOKE, 6, 0.3, 0.02, null, false);
            for (BlockDisplay part : sun) {
                if (part != null && part.isValid()) {
                    Vfx.dot(part.getLocation(), Particle.FLASH, PALETTE.core(), true);
                    Displays.shrinkAway(ctx(), owner, part, 6);
                }
            }
            Location gunSmoke = eye.clone();
            Loop.repeat(ctx(), owner, 2, 2, 6, run -> {
                Vfx.cloud(ctx(), gunSmoke.clone().add(0, run * 0.15, 0), Particle.WHITE_SMOKE, 2, 0.3, 0.01, null,
                        false);
                return true;
            });
            owner.sendActionBar(Text.mm(shots > 0
                    ? "<" + COLOR_TAG + "><b>☀ 정오</b></" + COLOR_TAG + "> <white>" + shots + "명 명중!</white>"
                    : "<" + COLOR_TAG + "><b>☀ 정오</b></" + COLOR_TAG + "> <gray>겨눌 적이 없었습니다</gray>"));
        }

        /** The draw ended without the volley (the owner fell): nothing lingers. */
        void cleanup() {
            if (slow != null) {
                ctx().removeModifier(owner, slow);
                slow = null;
            }
            for (Lock lock : locks.values()) {
                removeReticle(lock);
            }
            locks.clear();
            if (!fired) {
                for (BlockDisplay part : sun) {
                    if (part != null && part.isValid()) {
                        part.remove();
                    }
                }
            }
        }

        private void removeReticle(Lock lock) {
            TextDisplay reticle = lock.reticle;
            if (reticle != null && reticle.isValid()) {
                reticle.remove();
            }
            lock.reticle = null;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Reserves room for {@code count} more displays under {@link #MAX_DISPLAYS}; false when there is none. */
    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    /** The view direction, never zero. */
    private static Vector aim(Location eye) {
        Vector direction = eye.getDirection();
        if (!(direction.lengthSquared() > 1.0E-8)) {
            return Motion.horizontalFacing(eye);
        }
        return direction.normalize();
    }

    /** The revolver's muzzle: in front of the eyes, to the right and below, like a gun held at the hip. */
    private static Location muzzle(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector direction = aim(eye);
        Vector right = direction.getCrossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            double yaw = Math.toRadians(eye.getYaw());
            right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        }
        right.normalize();
        Vector up = right.getCrossProduct(direction).normalize();
        return eye.add(direction.multiply(0.6)).add(right.multiply(0.28)).subtract(up.multiply(0.32));
    }

    /** The gunslinger's right hip, where the cylinder turns while reloading. */
    private static Location hip(Player owner) {
        Location feet = owner.getLocation();
        Vector forward = Motion.horizontalFacing(feet);
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        return feet.add(0, owner.getHeight() * 0.5, 0).add(right.multiply(0.4)).add(forward.multiply(0.25));
    }

    /** A line of particles with at most {@link #MAX_LINE_POINTS} points, however long it is. */
    private <T> void line(Location from, Location to, double perBlock, Particle particle, @Nullable T data,
                          boolean force) {
        double length = from.distance(to);
        double density = length * perBlock > MAX_LINE_POINTS ? MAX_LINE_POINTS / Math.max(1, length) : perBlock;
        if (density > 0) {
            Vfx.line(ctx(), from, to, density, particle, data, force);
        }
    }

    /** A ring of particles in the plane across {@code axis}. */
    private <T> void disc(Location center, Vector axis, double radius, int points, Particle particle,
                          @Nullable T data, boolean force) {
        Vector normal = axis.clone();
        if (normal.lengthSquared() < 1.0E-8) {
            normal = new Vector(0, 1, 0);
        }
        normal.normalize();
        Vector u = normal.clone().crossProduct(Math.abs(normal.getY()) > 0.95 ? new Vector(1, 0, 0)
                : new Vector(0, 1, 0)).normalize();
        Vector v = normal.clone().crossProduct(u);
        int count = Vfx.n(ctx(), points);
        World world = center.getWorld();
        for (int i = 0; i < count; i++) {
            double angle = i * TAU / count;
            Vector offset = u.clone().multiply(Math.cos(angle) * radius).add(v.clone().multiply(Math.sin(angle) * radius));
            world.spawnParticle(particle, center.getX() + offset.getX(), center.getY() + offset.getY(),
                    center.getZ() + offset.getZ(), 1, 0, 0, 0, 0, data, force);
        }
    }

    /** A MiniMessage hex color between {@code from} and {@code to} ({@code t} clamped to 0..1). */
    private static String lerpColor(int from, int to, double t) {
        double k = Math.clamp(t, 0.0, 1.0);
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private static Vector3f toJoml(Vector vector) {
        Vector3f v = new Vector3f((float) vector.getX(), (float) vector.getY(), (float) vector.getZ());
        return v.lengthSquared() < 1.0E-8f ? new Vector3f(0, 0, 1) : v.normalize();
    }

    /** A box of the given size centered on the block display's origin, turned by {@code rotation}. */
    static Transformation box(Quaternionf rotation, float sx, float sy, float sz) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(sx / 2, sy / 2, sz / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(sx, sy, sz), new Quaternionf());
    }

    /** A cube of edge {@code size} centered on the block display's origin. */
    private static Transformation cubePose(float size, Quaternionf rotation) {
        return box(rotation, size, size, size);
    }

    /** A thin box of the given width from the block display's origin along {@code delta}. */
    static Transformation beamPose(Vector delta, float width) {
        Vector3f direction = new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ());
        float length = direction.length();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction.normalize());
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
        return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
    }

    private static Vector randomUnit() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vector vector = new Vector(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
        return vector.lengthSquared() < 1.0E-6 ? new Vector(1, 0, 0) : vector.normalize();
    }
}
