package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
 * 사냥꾼 — a woodland trapper. Every enemy it hurts is tracked (it glows, and only the hunter sees the footprints it
 * leaves), the active throws a bear trap that snaps shut on the first enemy to step in, and the ultimate opens the
 * hunt on the weakest enemy around: the hunter runs faster and hits the prey harder while the prey glows and drags
 * its feet, and when the prey falls the hunt moves on to the next one.
 *
 * <p>Unlike the 저격수 (marks everyone from afar) or the 거미 (webs fired at a target), the hunter works on the ground
 * and plans ahead: traps are laid where enemies will walk, the tracks tell where they went.
 *
 * <p>Look: moss green, parchment and dark leather; the hunt is blood orange. A tracked enemy gets a moss ring and a
 * sniff; its boot prints (heel and toe, alternating feet) appear for the hunter only and fade from fresh green to
 * old brown. The bear trap is a real little machine of block displays (a dark round base, a copper trigger plate,
 * two iron jaws on a hinge and a chain): it is thrown spinning and folded, lands with a clank and opens its jaws with
 * ratchet clicks; when it springs the jaws slam shut around the victim's legs with a flash, sparks and a "철컥!" pop,
 * stay clamped to the leg while it is held and creak open again. The hunt is called with a hunting horn and a burst
 * of leaves; a rotating blood-orange reticle and a bobbing marker sit on the prey, a scent trail (hunter only) leads
 * to it, and a kill is celebrated with a golden "사냥 성공!" and healing motes flowing back to the hunter.
 */
public final class HunterAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("hunter", "사냥꾼", Material.CROSSBOW)
            .color(TextColor.color(0x9DB656))
            .difficulty(2)
            .passive("추적",
                    "내가 공격한 적은 {track_duration}초간 발광하며, 그동안 그 적이 남긴 발자국이 나에게만 보입니다.")
            .active("곰덫",
                    "조준한 적의 발밑이나 바닥({trap_range}블록 이내)에 곰덫을 던져 설치합니다(최대 {trap_max}개, "
                            + "{trap_life}초 유지). 덫은 {trap_arm}초 뒤 작동하며, 밟은 적은 {trap_damage} 피해를 입고 "
                            + "{trap_root}초간 묶여 움직일 수 없습니다.",
                    7)
            .ultimate("사냥 개시",
                    "{hunt_range}블록 안에서 체력이 가장 낮은 적을 사냥감으로 지정합니다. {hunt_duration}초간 내 이동 속도가 "
                            + "{hunt_speed}% 빨라지고 사냥감에게 주는 피해가 {hunt_bonus}% 늘며, 사냥감은 발광하고 이동 속도가 "
                            + "{hunt_slow}% 느려집니다. 사냥감이 쓰러지면 남은 시간 동안 다음 사냥감을 쫓고, 내가 쓰러뜨렸다면 "
                            + "체력을 {hunt_heal} 회복합니다.")
            .ultimateNotStandalone()
            .param("track_duration", 5)
            .param("trap_range", 7)
            .param("trap_max", 3)
            .param("trap_life", 45)
            .param("trap_arm", 0.6)
            .param("trap_radius", 0.5)
            .param("trap_damage", 4)
            .param("trap_root", 1.5)
            .param("hunt_range", 40)
            .param("hunt_duration", 8)
            .param("hunt_speed", 25)
            .param("hunt_bonus", 30)
            .param("hunt_slow", 20)
            .param("hunt_heal", 6)
            .build();

    private static final String COLOR_TAG = "#9DB656";
    private static final String HUNT_TAG = "#E2572B";
    private static final String GOLD_TAG = "#FFD45E";
    private static final double TAU = Math.PI * 2;
    /** Parchment core, moss green body, dark leather edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF3E6C0, 0x8DAA4A, 0x4B3621);
    /** The hunt: pale amber core, blood orange body, dried-blood edge. */
    private static final Vfx.Palette HUNT = Vfx.Palette.of(0xFFE3B0, 0xE2572B, 0x5A1E10);
    private static final Particle.DustOptions MOSS = PALETTE.dust(0.9f);
    private static final Particle.DustTransition MOSS_FADE = PALETTE.fade(1.0f);
    private static final Particle.DustOptions LEATHER = new Particle.DustOptions(PALETTE.edge(), 0.8f);
    private static final Particle.DustOptions PRINT_FRESH = new Particle.DustOptions(Color.fromRGB(0x6E8F3A), 0.7f);
    private static final Particle.DustOptions PRINT_OLD = new Particle.DustOptions(Color.fromRGB(0x5A4630), 0.55f);
    private static final Particle.DustOptions RUST = new Particle.DustOptions(Color.fromRGB(0xA0522D), 0.7f);
    private static final Particle.DustTransition HUNT_GLOW = HUNT.glow(1.1f);
    private static final Particle.DustOptions HUNT_DUST = HUNT.dust(0.9f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(Color.fromRGB(0xFFD45E), 1.1f);
    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;
    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;

    // passive: tracking
    private static final long TRACK_PERIOD = 2;
    /** A tracked enemy leaves a print every this many blocks it walks. */
    private static final double STRIDE = 0.85;
    /** Sideways offset of a print from the walking line (left and right foot). */
    private static final double PRINT_SIDE = 0.13;
    /** Distance between the heel and the toe dot of a print. */
    private static final double PRINT_LENGTH = 0.2;
    private static final int MAX_PRINTS = 10;
    private static final long PRINT_LIFE = 60;
    private static final long PRINT_REDRAW = 6;

    // active: bear trap
    private static final Material TRAP_BASE = Material.POLISHED_BLACKSTONE;
    private static final Material TRAP_JAW = Material.IRON_BLOCK;
    private static final Material TRAP_PLATE = Material.COPPER_BLOCK;
    private static final double FLOOR_SEARCH = 6;
    /** Space the trap needs on the floor. */
    private static final double TRAP_FOOTPRINT = 0.7;
    private static final float JAW_LENGTH = 0.64f;
    private static final float JAW_WIDTH = 0.3f;
    private static final float JAW_THICKNESS = 0.045f;
    /** Jaw angles (degrees from flat): open, sprung (a hair short of upright), folded for the throw. */
    private static final float JAW_OPEN = 4f;
    private static final float JAW_SPRUNG = 84f;
    private static final float JAW_FOLDED = 90f;
    private static final float JAW_LOOSE = 55f;
    private static final int OPEN_TICKS = 6;
    private static final int SNAP_TICKS = 2;
    private static final long MIN_FLIGHT = 4;
    private static final long MAX_FLIGHT = 12;
    private static final long GLINT_PERIOD = 50;
    private static final long HINT_PERIOD = 20;
    /** A held enemy is followed by the clamped trap this often. */
    private static final long HOLD_PERIOD = 2;
    private static final long POP_TICKS = 14;

    // ultimate: the hunt
    private static final int RETICLE_BARS = 4;
    private static final double RETICLE_RADIUS = 0.62;
    private static final float RETICLE_LENGTH = 0.62f;
    private static final float RETICLE_WIDTH = 0.07f;
    private static final float RETICLE_SPIN = 7f;
    private static final long HUNT_STEP = 2;
    private static final long SCENT_PERIOD = 4;
    private static final long STATUS_PERIOD = 5;
    private static final int LOCK_TICKS = 6;

    /** Enemies this hunter tracks, by entity id. */
    private final Map<UUID, Track> tracks = new HashMap<>();
    /** Armed or arming traps, oldest first (sprung traps leave this list). */
    private final List<Trap> traps = new ArrayList<>();
    /** Every display this ability currently has alive, for the budget. */
    private final List<Display> displays = new ArrayList<>();
    private @Nullable Hunt hunt;
    private int huntToken;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        Player owner = player();
        for (Track track : tracks.values()) {
            clearGlow(track.target, track.until);
        }
        tracks.clear();
        traps.clear();
        displays.clear();
        Hunt current = hunt;
        hunt = null;
        huntToken++;
        if (current != null) {
            current.release(owner);
        }
    }

    @Override
    public void onTick(long tick) {
        long now = ctx().currentTick();
        if (!traps.isEmpty()) {
            updateTraps(now);
        }
        if (tick % TRACK_PERIOD == 0 && !tracks.isEmpty()) {
            updateTracks(now);
        }
    }

    // ------------------------------------------------------------------ passive: tracking

    /** The hunt's bonus: only the damage changes here, the victim may still cancel the hit. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        Hunt current = hunt;
        if (event.isCancelled() || current == null || !victim.equals(current.prey)) {
            return;
        }
        double bonus = Math.max(0, params().num("hunt_bonus")) / 100.0;
        event.setDamage(event.getDamage() * (1 + bonus));
    }

    /** Every hit that landed on an enemy marks it as tracked. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!ctx().isEnemy(player(), victim)) {
            return;
        }
        track(victim, params().ticks("track_duration"));
        Hunt current = hunt;
        if (current != null && victim.equals(current.prey)) {
            current.bite(victim);
        }
    }

    @Override
    public void onKill(Player victim) {
        Hunt current = hunt;
        if (current != null && victim.equals(current.prey)) {
            current.success(victim);
        }
    }

    /** Tracks {@code target} for at least {@code ticks} more: it glows and leaves prints for the hunter. */
    private void track(LivingEntity target, long ticks) {
        if (ticks <= 0 || !target.isValid() || target.isDead()) {
            return;
        }
        long now = ctx().currentTick();
        Track track = tracks.get(target.getUniqueId());
        boolean fresh = track == null || track.until < now;
        if (track == null) {
            track = new Track(target);
            tracks.put(target.getUniqueId(), track);
        }
        track.until = Math.max(track.until, now + ticks);
        int duration = (int) Math.min(Integer.MAX_VALUE, track.until - now);
        PotionEffect glow = target.getPotionEffect(PotionEffectType.GLOWING);
        if (glow == null || (!glow.isInfinite() && glow.getDuration() < duration)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, duration, 0, false, false, true));
        }
        if (fresh) {
            trackCue(target);
        }
    }

    /** Ends tracking glows this hunter gave: a glow that outlasts the track came from someone else. */
    private void clearGlow(LivingEntity target, long until) {
        if (!target.isValid() || target.isDead()) {
            return;
        }
        PotionEffect glow = target.getPotionEffect(PotionEffectType.GLOWING);
        long left = Math.max(0, until - ctx().currentTick());
        if (glow != null && !glow.isInfinite() && glow.getDuration() <= left + 2) {
            target.removePotionEffect(PotionEffectType.GLOWING);
        }
    }

    /** The scent is picked up: a moss ring at the feet, spores rising, a sniff only the hunter hears. */
    private void trackCue(LivingEntity target) {
        Player owner = player();
        Location feet = target.getLocation().add(0, 0.08, 0);
        double phase = ThreadLocalRandom.current().nextDouble(TAU);
        Vfx.ring(ctx(), feet, 0.75, 14, phase, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), false);
        Vfx.ring(ctx(), feet.clone().add(0, 0.25, 0), 0.5, 8, phase + 0.4, Particle.DUST, LEATHER, false);
        Vfx.cloud(ctx(), Fx.body(target), Particle.COMPOSTER, 6, 0.3, 0, null, false);
        owner.playSound(owner, Sound.ENTITY_FOX_SNIFF, SoundCategory.PLAYERS, 0.8f, 1.2f + Fx.jitter(0.1f));
    }

    private void updateTracks(long now) {
        Player owner = player();
        for (Iterator<Track> it = tracks.values().iterator(); it.hasNext(); ) {
            Track track = it.next();
            LivingEntity target = track.target;
            if (!target.isValid() || target.isDead() || !target.getWorld().equals(owner.getWorld())
                    || (now > track.until && track.prints.isEmpty())) {
                it.remove();
                continue;
            }
            if (!seen(owner, target)) {
                // Stealth hides the trail too: no print may give the enemy away.
                track.prints.clear();
                track.lastStep = null;
                continue;
            }
            if (now <= track.until) {
                step(track, now);
            }
            if (now % PRINT_REDRAW == 0) {
                drawPrints(owner, track, now);
            }
        }
    }

    /** Leaves a print whenever the tracked enemy has walked a stride on the ground. */
    private void step(Track track, long now) {
        Location feet = track.target.getLocation();
        if (track.lastStep == null || !track.lastStep.getWorld().equals(feet.getWorld())) {
            track.lastStep = feet;
            return;
        }
        Vector walked = feet.toVector().subtract(track.lastStep.toVector()).setY(0);
        if (walked.lengthSquared() < STRIDE * STRIDE) {
            return;
        }
        track.lastStep = feet;
        if (!Motion.isGrounded(track.target)) {
            return;
        }
        Vector heading = walked.normalize();
        Vector side = new Vector(-heading.getZ(), 0, heading.getX()).multiply(track.left ? PRINT_SIDE : -PRINT_SIDE);
        track.left = !track.left;
        track.prints.addLast(new Print(feet.getX() + side.getX(), feet.getY() + 0.04, feet.getZ() + side.getZ(),
                heading.getX(), heading.getZ(), now));
        while (track.prints.size() > MAX_PRINTS) {
            track.prints.removeFirst();
        }
    }

    /** The boot prints, for the hunter's eyes only: a heel and a toe, fresh green fading to old brown. */
    private void drawPrints(Player owner, Track track, long now) {
        while (!track.prints.isEmpty() && now - track.prints.peekFirst().tick() > PRINT_LIFE) {
            track.prints.removeFirst();
        }
        for (Print print : track.prints) {
            Particle.DustOptions dust = now - print.tick() < PRINT_LIFE / 2 ? PRINT_FRESH : PRINT_OLD;
            double hx = print.dx() * PRINT_LENGTH / 2;
            double hz = print.dz() * PRINT_LENGTH / 2;
            owner.spawnParticle(Particle.DUST, print.x() - hx, print.y(), print.z() - hz, 1, 0, 0, 0, 0, dust);
            owner.spawnParticle(Particle.DUST, print.x() + hx, print.y(), print.z() + hz, 1, 0, 0, 0, 0, dust);
        }
    }

    /** One tracked enemy. */
    private static final class Track {
        final LivingEntity target;
        long until = Long.MIN_VALUE / 2;
        @Nullable Location lastStep;
        boolean left;
        final Deque<Print> prints = new ArrayDeque<>();

        Track(LivingEntity target) {
            this.target = target;
        }
    }

    /** A boot print: where it is, which way the foot pointed and when it was left. */
    private record Print(double x, double y, double z, double dx, double dz, long tick) {
    }

    // ------------------------------------------------------------------ active: bear trap

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("trap_range"));
        Location landing = trapSpot(owner, range);
        if (landing == null) {
            return ActivationResult.fail("덫을 놓을 바닥이 없습니다");
        }
        int max = Math.max(1, params().integer("trap_max"));
        while (traps.size() >= max) {
            traps.removeFirst().dismantle();
        }
        if (!roomFor(Trap.PARTS)) {
            return ActivationResult.fail("덫을 더 놓을 수 없습니다");
        }
        Trap trap = new Trap(landing);
        traps.add(trap);
        trap.launch(hand(owner));
        int live = traps.size();
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚙ 곰덫 설치 <white>" + live + "/" + max + "</white></"
                + COLOR_TAG + ">"));
        return ActivationResult.success();
    }

    /**
     * Where the trap lands: under the aimed enemy, otherwise where the view ray meets the floor (backing off a wall it
     * hits), always on free, harmless floor inside the arena. Null if there is none.
     */
    private @Nullable Location trapSpot(Player owner, double range) {
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location point;
        double search = FLOOR_SEARCH;
        if (aimed != null && seen(owner, aimed)) {
            point = aimed.getLocation();
        } else {
            RayTraceResult hit = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            if (hit != null) {
                point = hit.getHitPosition().toLocation(world);
                if (hit.getHitBlockFace() != BlockFace.UP) {
                    point.subtract(Motion.horizontal(direction).multiply(0.45));
                }
            } else {
                point = eye.clone().add(direction.clone().multiply(range));
                search += Math.max(0, direction.getY()) * range + eye.getY() - owner.getLocation().getY();
            }
        }
        RayTraceResult floor = world.rayTraceBlocks(point.clone().add(0, 0.3, 0), new Vector(0, -1, 0), search + 0.3,
                FluidCollisionMode.NEVER, true);
        if (floor == null) {
            return null;
        }
        Location spot = floor.getHitPosition().toLocation(world);
        spot.setYaw(owner.getLocation().getYaw());
        spot.setPitch(0);
        if (!Spots.isFree(world, spot, TRAP_FOOTPRINT, 0.5) || !Spots.hasGroundBelow(world, spot.clone().add(0, 0.05, 0))
                || !ctx().isInsideArena(spot)) {
            return null;
        }
        return spot;
    }

    /** Where thrown things leave the hunter: in front of the right hand. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector forward = eye.getDirection();
        Vector right = Motion.horizontal(forward).rotateAroundY(-Math.PI / 2).multiply(0.3);
        return eye.add(forward.multiply(0.5)).add(right).subtract(0, 0.35, 0);
    }

    private void updateTraps(long now) {
        Player owner = player();
        double radius = Math.max(0, params().num("trap_radius"));
        for (Trap trap : List.copyOf(traps)) {
            switch (trap.state) {
                case ARMING -> {
                    if (now >= trap.armAt) {
                        trap.arm();
                    }
                }
                case ARMED -> {
                    if (now >= trap.expireAt) {
                        traps.remove(trap);
                        trap.expire();
                        continue;
                    }
                    List<LivingEntity> caught = ctx().enemiesNear(owner, trap.center.clone().add(0, 0.25, 0), radius);
                    if (!caught.isEmpty()) {
                        traps.remove(trap);
                        trap.spring(caught.getFirst());
                        continue;
                    }
                    trap.idle(owner, now);
                }
                default -> {
                }
            }
        }
    }

    private enum TrapState { FLYING, ARMING, ARMED, SPRUNG, GONE }

    /**
     * One bear trap: a dark round base, a copper trigger plate, two iron jaws hinged on the axis across the trap and a
     * chain lying out behind it. The parts share the entity location; the trap's yaw turns them all.
     */
    private final class Trap {

        static final int PARTS = 5;

        private Location center;
        private final List<Display> parts = new ArrayList<>(PARTS);
        private @Nullable BlockDisplay jawA;
        private @Nullable BlockDisplay jawB;
        private @Nullable BlockDisplay plate;
        private TrapState state = TrapState.FLYING;
        private long armAt;
        private long expireAt;

        Trap(Location landing) {
            this.center = landing.clone();
        }

        /** Spawns the folded trap at the hand and throws it, spinning, onto its spot. */
        void launch(Location from) {
            Player owner = player();
            Location start = from.clone();
            start.setYaw(center.getYaw());
            start.setPitch(0);
            long lifetime = Math.max(1, params().ticks("trap_life")) + MAX_FLIGHT + 20;
            BlockDisplay base = display(start, TRAP_BASE,
                    Displays.transform(0, 0.025f, 0, 0.58f, 0.05f, 0.58f, 45, 0, 0), lifetime);
            plate = display(start, TRAP_PLATE, Displays.transform(0, 0.105f, 0, 0.2f, 0.03f, 0.2f, 0, 0, 0),
                    lifetime);
            jawA = display(start, TRAP_JAW, jawPose(false, JAW_FOLDED), lifetime);
            jawB = display(start, TRAP_JAW, jawPose(true, JAW_FOLDED), lifetime);
            ItemDisplay chain = Displays.item(ctx(), owner, start, ItemStack.of(Material.IRON_CHAIN),
                    Displays.centered(0, 0.03f, -0.62f, 0.45f, 0.45f, 0.45f, 0, 90, 0), lifetime);
            chain.setBrightness(null);
            displays.add(chain);
            parts.add(base);
            parts.add(plate);
            parts.add(jawA);
            parts.add(jawB);
            parts.add(chain);
            for (Display part : parts) {
                part.setTeleportDuration(1);
            }

            Vector delta = center.toVector().subtract(start.toVector());
            double distance = delta.length();
            long flight = Math.clamp(Math.round(3 + distance * 0.8), MIN_FLIGHT, MAX_FLIGHT);
            double apex = 0.5 + distance * 0.12;
            float spinStart = start.getYaw();
            Fx.sound(start, Sound.ENTITY_ALLAY_ITEM_THROWN, 0.8f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(start, Sound.BLOCK_CHAIN_STEP, 0.9f, 1.3f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 1, 1, flight, run -> {
                if (state != TrapState.FLYING) {
                    return false;
                }
                double t = (run + 1.0) / flight;
                Location at = start.clone().add(delta.clone().multiply(t)).add(0, apex * 4 * t * (1 - t), 0);
                at.setYaw(spinStart + (run + 1) * 47f);
                at.setPitch((float) (Math.sin(run * 0.9) * 30));
                move(at);
                Vfx.dot(at, Particle.DUST, LEATHER, false);
                if (run % 2 == 0) {
                    Vfx.dot(at, Particle.CRIT, null, false);
                }
                return true;
            }, this::land);
        }

        /** Touchdown: a clank and a puff of the floor, then the jaws are pried open with ratchet clicks. */
        private void land() {
            if (state != TrapState.FLYING) {
                return;
            }
            Player owner = player();
            move(center);
            state = TrapState.ARMING;
            long now = ctx().currentTick();
            armAt = now + Math.max(OPEN_TICKS, params().ticks("trap_arm"));
            expireAt = now + Math.max(1, params().ticks("trap_life"));
            ctx().runLater(owner, 1, () -> {
                setJaws(JAW_OPEN, OPEN_TICKS);
                for (int i = 0; i < 3; i++) {
                    long delay = 1 + i * 2L;
                    ctx().runLater(owner, delay, () -> {
                        if (state == TrapState.ARMING) {
                            Fx.sound(center, Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 0.6f, 1.5f + Fx.jitter(0.1f));
                        }
                    });
                }
            });
            World world = center.getWorld();
            Block floor = center.clone().subtract(0, 0.1, 0).getBlock();
            BlockData floorData = floor.getType().isAir() ? null : floor.getBlockData();
            if (floorData != null) {
                world.spawnParticle(Particle.BLOCK_CRUMBLE, center.clone().add(0, 0.1, 0), Vfx.n(ctx(), 10), 0.3,
                        0.05, 0.3, 0, floorData);
            }
            Vfx.ring(ctx(), center.clone().add(0, 0.06, 0), 0.55, 12, 0, Particle.DUST, MOSS, false);
            Vfx.cloud(ctx(), center.clone().add(0, 0.15, 0), Particle.SMOKE, 3, 0.15, 0.01, null, false);
            Fx.sound(center, Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(center, Sound.BLOCK_HEAVY_CORE_STEP, 0.8f, 0.9f + Fx.jitter(0.1f));
        }

        /** The trap is set: a click and a glint on the plate. */
        void arm() {
            state = TrapState.ARMED;
            Location pad = center.clone().add(0, 0.12, 0);
            Vfx.dot(pad, Particle.WAX_OFF, null, false);
            Fx.sound(center, Sound.ITEM_CROSSBOW_LOADING_END, 0.7f, 1.4f + Fx.jitter(0.05f));
            Player owner = player();
            hint(owner);
        }

        /**
         * An armed trap lies still. Now and then a glint catches the light (anyone paying attention can spot it); the
         * hunter alone sees a faint moss circle around each of its traps.
         */
        void idle(Player owner, long now) {
            if ((now + center.getBlockX() * 7L) % GLINT_PERIOD == 0) {
                Vfx.dot(center.clone().add(Fx.jitter(0.25f), 0.12, Fx.jitter(0.25f)), Particle.WAX_OFF, null, false);
            }
            if (now % HINT_PERIOD == 0) {
                hint(owner);
            }
        }

        private void hint(Player owner) {
            if (!owner.getWorld().equals(center.getWorld())) {
                return;
            }
            int points = Vfx.n(ctx(), 10);
            for (int i = 0; i < points; i++) {
                double angle = i * TAU / points;
                owner.spawnParticle(Particle.DUST, center.getX() + Math.cos(angle) * 0.5, center.getY() + 0.06,
                        center.getZ() + Math.sin(angle) * 0.5, 1, 0, 0, 0, 0, MOSS);
            }
        }

        /**
         * Snap: the trap jumps to the victim's feet and the jaws slam shut around its legs. The victim takes the
         * damage and is held for the root time, the trap clamped to its leg, then the jaws creak open and the trap
         * falls apart.
         */
        void spring(LivingEntity victim) {
            state = TrapState.SPRUNG;
            Player owner = player();
            Location feet = victim.getLocation();
            Location clamp = center.clone();
            clamp.setX(feet.getX());
            clamp.setZ(feet.getZ());
            if (Math.abs(feet.getY() - center.getY()) > 0.6) {
                clamp.setY(feet.getY());
            }
            center = clamp;
            move(clamp);
            setJaws(JAW_SPRUNG, SNAP_TICKS);
            if (plate != null) {
                Displays.animate(plate, Displays.transform(0, 0.03f, 0, 0.2f, 0.005f, 0.2f, 0, 0, 0), SNAP_TICKS);
            }
            snapFx(victim, clamp);

            Combat.hit(ctx(), owner, victim, params().num("trap_damage"));
            long rootTicks = params().ticks("trap_root");
            if (Combat.isTargetable(ctx(), owner, victim) && rootTicks > 0) {
                ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1,
                        rootTicks);
                ctx().addModifier(owner, victim, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1,
                        rootTicks);
                Vector velocity = victim.getVelocity();
                victim.setVelocity(Motion.clamp(new Vector(0, Math.min(0, velocity.getY()), 0), MAX_SPEED));
            }
            long hold = Math.max(HOLD_PERIOD, rootTicks);
            Loop.repeat(ctx(), owner, HOLD_PERIOD, HOLD_PERIOD, Math.max(1, hold / HOLD_PERIOD), run -> {
                if (!victim.isValid() || victim.isDead() || !victim.getWorld().equals(center.getWorld())) {
                    return false;
                }
                // The trap stays clamped to the leg, wherever a shove takes it.
                Location at = victim.getLocation();
                at.setYaw(center.getYaw());
                at.setPitch(0);
                center = at;
                move(at);
                if (run % 2 == 0) {
                    Vfx.cloud(ctx(), at.clone().add(0, 0.25, 0), Particle.DUST, 2, 0.18, 0, RUST, false);
                }
                if (run % 5 == 0) {
                    Fx.sound(at, Sound.BLOCK_CHAIN_HIT, 0.6f, 0.8f + Fx.jitter(0.1f));
                }
                return true;
            }, this::release);
        }

        private void snapFx(LivingEntity victim, Location at) {
            World world = at.getWorld();
            Location jaws = at.clone().add(0, 0.25, 0);
            Vfx.dot(jaws, Particle.FLASH, PALETTE.core(), false);
            world.spawnParticle(Particle.CRIT, jaws, Vfx.n(ctx(), 14), 0.25, 0.15, 0.25, 0.35);
            world.spawnParticle(Particle.ELECTRIC_SPARK, jaws, Vfx.n(ctx(), 8), 0.2, 0.1, 0.2, 0.15);
            world.spawnParticle(Particle.BLOCK_CRUMBLE, jaws, Vfx.n(ctx(), 10), 0.2, 0.1, 0.2, 0,
                    TRAP_JAW.createBlockData());
            Vfx.ring(ctx(), at.clone().add(0, 0.06, 0), 0.9, 16, 0, Particle.DUST_COLOR_TRANSITION, MOSS_FADE, false);
            Vfx.cloud(ctx(), Fx.body(victim), Particle.DAMAGE_INDICATOR, 4, 0.2, 0.1, null, false);
            Fx.sound(at, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.2f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.5f, 1.5f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.8f + Fx.jitter(0.1f));
            pop(victim.getLocation().add(0, victim.getHeight() + 0.5, 0), "<b><gradient:#F3E6C0:#8DAA4A>철컥!"
                    + "</gradient></b>");
            Player owner = player();
            if (owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚙ 덫에 걸렸다! <white><name></white></" + COLOR_TAG
                        + ">", Placeholder.component("name", victim.name())));
            }
        }

        /** The hold is over: the jaws creak open and the spent trap crumbles away. */
        private void release() {
            if (state == TrapState.GONE) {
                return;
            }
            state = TrapState.GONE;
            displays.removeAll(parts);
            Player owner = player();
            setJaws(JAW_LOOSE, 5);
            Fx.sound(center, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.8f, 0.5f + Fx.jitter(0.05f));
            ctx().runLater(owner, 6, () -> {
                Vfx.cloud(ctx(), center.clone().add(0, 0.15, 0), Particle.DUST, 6, 0.25, 0, RUST, false);
                Vfx.cloud(ctx(), center.clone().add(0, 0.15, 0), Particle.SMOKE, 3, 0.2, 0.01, null, false);
                shrinkAll(5);
            });
        }

        /** The trap outlived its time: it snaps shut on nothing and is gone. */
        void expire() {
            state = TrapState.GONE;
            displays.removeAll(parts);
            setJaws(JAW_SPRUNG, 3);
            Fx.sound(center, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5f, 1.1f + Fx.jitter(0.05f));
            ctx().runLater(player(), 4, () -> shrinkAll(6));
        }

        /** Taken back to make room for a new trap: folded up in a puff of smoke. */
        void dismantle() {
            state = TrapState.GONE;
            // Leaving the budget right away: a new trap may take its place while this one folds up.
            displays.removeAll(parts);
            setJaws(JAW_FOLDED, 3);
            Vfx.cloud(ctx(), center.clone().add(0, 0.2, 0), Particle.SMOKE, 6, 0.2, 0.02, null, false);
            Fx.sound(center, Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.2f + Fx.jitter(0.1f));
            ctx().runLater(player(), 3, () -> shrinkAll(4));
        }

        private void shrinkAll(int ticks) {
            for (Display part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), player(), part, ticks);
                }
            }
        }

        private void move(Location at) {
            for (Display part : parts) {
                if (part.isValid()) {
                    part.teleport(at);
                }
            }
        }

        private void setJaws(float angle, int ticks) {
            if (jawA != null) {
                Displays.animate(jawA, jawPose(false, angle), ticks);
            }
            if (jawB != null) {
                Displays.animate(jawB, jawPose(true, angle), ticks);
            }
        }
    }

    /**
     * A jaw of the trap: a thin iron plate hinged on the trap's local x axis. At angle 0 it lies flat, pointing to +z
     * (the second jaw to -z); at 90 degrees it stands upright. The hinge stays fixed while the angle interpolates.
     */
    private static Transformation jawPose(boolean second, float angleDegrees) {
        Quaternionf rotation = new Quaternionf();
        if (second) {
            rotation.rotateY((float) Math.PI);
        }
        rotation.rotateX((float) Math.toRadians(-angleDegrees));
        Vector3f translation = new Vector3f(second ? JAW_LENGTH / 2 : -JAW_LENGTH / 2, 0.05f, 0);
        return new Transformation(translation, rotation, new Vector3f(JAW_LENGTH, JAW_THICKNESS, JAW_WIDTH),
                new Quaternionf());
    }

    // ------------------------------------------------------------------ ultimate: the hunt

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double range = Math.max(1, params().num("hunt_range"));
        LivingEntity prey = pickPrey(owner, range, null);
        if (prey == null) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안에 사냥할 적이 없습니다");
        }
        Hunt previous = hunt;
        if (previous != null) {
            previous.release(owner);
        }
        long ticks = Math.max(HUNT_STEP, params().ticks("hunt_duration"));
        Hunt next = new Hunt(ctx().currentTick() + ticks, ++huntToken);
        hunt = next;
        next.begin(owner, prey);
        return ActivationResult.success();
    }

    /**
     * The weakest enemy within {@code range}: players first (summons only when there is nobody else), then the lowest
     * health, the nearest on a tie. Enemies hidden from the hunter by stealth are never picked.
     */
    private @Nullable LivingEntity pickPrey(Player owner, double range, @Nullable LivingEntity exclude) {
        LivingEntity best = null;
        int bestRank = Integer.MAX_VALUE;
        double bestHealth = Double.MAX_VALUE;
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (enemy.equals(exclude) || !seen(owner, enemy)) {
                continue;
            }
            int rank = enemy instanceof Player ? 0 : enemy instanceof Mannequin ? 1 : 2;
            double health = enemy.getHealth();
            if (rank < bestRank || (rank == bestRank && health < bestHealth - 1.0E-6)) {
                best = enemy;
                bestRank = rank;
                bestHealth = health;
            }
        }
        return best;
    }

    /**
     * The hunt: the hunter is faster and hits the prey harder; the prey glows, drags its feet and wears a rotating
     * blood-orange reticle with a bobbing marker; a scent trail only the hunter sees leads to it. A fallen prey hands
     * the hunt on to the next weakest enemy.
     */
    private final class Hunt {

        private final long endTick;
        private final int token;
        private @Nullable LivingEntity prey;
        private @Nullable NamespacedKey speedKey;
        private @Nullable NamespacedKey slowKey;
        private final List<BlockDisplay> reticle = new ArrayList<>(RETICLE_BARS);
        private @Nullable TextDisplay marker;
        private boolean veiled;
        private boolean ended;
        private int caught;

        Hunt(long endTick, int token) {
            this.endTick = endTick;
            this.token = token;
        }

        void begin(Player owner, LivingEntity first) {
            long ticks = Math.max(1, endTick - ctx().currentTick());
            double speed = Math.max(0, params().num("hunt_speed")) / 100.0;
            if (speed > 0) {
                speedKey = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1,
                        ticks);
            }
            hornFx(owner);
            mark(owner, first, true);
            Loop.repeat(ctx(), owner, HUNT_STEP, HUNT_STEP, Math.max(1, ticks / HUNT_STEP), this::step, () -> {
                if (token == huntToken) {
                    end(player());
                }
            });
        }

        /** Marks {@code target} as the prey: glow, slow, tracks, reticle and a lock-on. */
        private void mark(Player owner, LivingEntity target, boolean first) {
            dropPrey();
            prey = target;
            long left = Math.max(1, endTick - ctx().currentTick());
            int duration = (int) Math.min(Integer.MAX_VALUE, left);
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, duration, 0, false, false, true));
            double slow = Math.clamp(params().num("hunt_slow") / 100.0, 0.0, 1.0);
            if (slow > 0) {
                slowKey = ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -slow,
                        Operation.MULTIPLY_SCALAR_1, left);
            }
            track(target, left);
            spawnReticle(owner, target);
            lockOn(owner, target);

            Location body = Fx.body(target);
            Fx.sound(body, Sound.ITEM_CROSSBOW_LOADING_END, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f);
            if (target instanceof Player victim) {
                victim.sendActionBar(Text.mm("<" + HUNT_TAG + "><b>⚠ 사냥감으로 지정되었습니다!</b></" + HUNT_TAG + ">"));
            }
            if (first) {
                ctx().announce(Text.mm("<" + HUNT_TAG + ">➶</" + HUNT_TAG + "> <white><owner></white><gray>이(가) "
                        + "</gray><white><prey></white><gray>을(를) </gray><" + HUNT_TAG + ">사냥감</" + HUNT_TAG
                        + "><gray>으로 지정했습니다!</gray>", Placeholder.unparsed("owner", owner.getName()),
                        Placeholder.component("prey", target.name())));
            }
        }

        /** The old prey is let go: its slow ends now (its glow fades with the hunt's time). */
        private void dropPrey() {
            LivingEntity old = prey;
            if (old != null && slowKey != null) {
                ctx().removeModifier(old, slowKey);
            }
            slowKey = null;
            prey = null;
        }

        private boolean step(long run) {
            if (ended || token != huntToken) {
                return false;
            }
            Player owner = player();
            LivingEntity target = prey;
            if (target == null || !Combat.isTargetable(ctx(), owner, target)
                    || !target.getWorld().equals(owner.getWorld())) {
                LivingEntity next = pickPrey(owner, Math.max(1, params().num("hunt_range")), target);
                if (next == null) {
                    end(owner);
                    return false;
                }
                mark(owner, next, false);
                target = next;
            }
            long now = ctx().currentTick();
            boolean hidden = !seen(owner, target);
            if (hidden != veiled) {
                veiled = hidden;
                veil(hidden);
            }
            followReticle(target, run);
            if (!hidden && run % (SCENT_PERIOD / HUNT_STEP) == 0) {
                scent(owner, target);
            }
            if (run % Math.max(1, STATUS_PERIOD / HUNT_STEP) == 0 && owner.isOnline()) {
                status(owner, target, hidden, Math.max(0, endTick - now));
            }
            if (run % 10 == 0) {
                Vfx.cloud(ctx(), owner.getLocation().add(0, 0.1, 0), Particle.DUST, 2, 0.2, 0, HUNT_DUST, false);
            }
            return true;
        }

        /** A hit on the prey: a blood-orange slash of the hunt's bonus. */
        void bite(LivingEntity victim) {
            Location body = Fx.body(victim);
            Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 6, 0.25, 0, HUNT_GLOW, false);
            Vfx.cloud(ctx(), body, Particle.CRIT, 4, 0.2, 0.2, null, false);
            Fx.sound(body, Sound.ENTITY_WOLF_GROWL, 0.35f, 1.4f + Fx.jitter(0.1f));
        }

        /** The hunter brought the prey down: golden "사냥 성공!", healing motes flowing back, the next prey. */
        void success(Player victim) {
            Player owner = player();
            caught++;
            double heal = Math.max(0, params().num("hunt_heal"));
            if (heal > 0) {
                ctx().heal(owner, heal);
            }
            Location at = victim.getLocation().add(0, 1.0, 0);
            World world = at.getWorld();
            Vfx.dot(at, Particle.FLASH, HUNT.core(), true);
            world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 24), 0.5, 0.6, 0.5, 0, GOLD_DUST, true);
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, Vfx.n(ctx(), 18), 0.4, 0.5, 0.4, 0.25, null, true);
            if (owner.getWorld().equals(world)) {
                for (int i = 0; i < Vfx.n(ctx(), 8); i++) {
                    Location from = Vfx.scatter(at, 0.8).add(0, Fx.jitter(0.5f), 0);
                    Vfx.trail(from, Fx.body(owner), PALETTE.body(), 12 + i, true);
                }
                world.spawnParticle(Particle.HAPPY_VILLAGER, Fx.body(owner), Vfx.n(ctx(), 8), 0.3, 0.4, 0.3, 0);
            }
            pop(at.clone().add(0, 1.0, 0), "<b><gradient:#FFD45E:#E2572B>사냥 성공!</gradient></b>");
            Fx.sound(at, Sound.ITEM_GOAT_HORN_SOUND_0, 1.2f, 1.3f);
            Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f + Fx.jitter(0.05f));
            if (owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>➶ 사냥 성공!</b></" + GOLD_TAG + "> <gray>("
                        + caught + ")</gray>"));
            }
            dropPrey();
        }

        /** Ends the hunt by itself (time up, nobody left): the reticle falls apart, the speed and the slow end. */
        private void end(Player owner) {
            if (ended) {
                return;
            }
            ended = true;
            release(owner);
            if (hunt == this) {
                hunt = null;
            }
            Fx.sound(owner.getLocation(), Sound.ITEM_CROSSBOW_LOADING_START, 0.6f, 0.7f);
        }

        /** Undoes everything the hunt changed, also when the hunter is gone. */
        void release(Player owner) {
            ended = true;
            if (speedKey != null) {
                ctx().removeModifier(owner, speedKey);
                speedKey = null;
            }
            LivingEntity target = prey;
            dropPrey();
            if (target != null) {
                clearGlow(target, endTick);
            }
            for (BlockDisplay bar : reticle) {
                if (bar.isValid()) {
                    Displays.shrinkAway(ctx(), owner, bar, 4);
                }
            }
            reticle.clear();
            if (marker != null && marker.isValid()) {
                Displays.shrinkAway(ctx(), owner, marker, 4);
            }
            marker = null;
        }

        private void spawnReticle(Player owner, LivingEntity target) {
            long life = Math.max(1, endTick - ctx().currentTick()) + 10;
            Location feet = reticleSpot(target, 0);
            if (reticle.isEmpty() && roomFor(RETICLE_BARS)) {
                for (int i = 0; i < RETICLE_BARS; i++) {
                    BlockDisplay bar = display(feet, Material.RED_STAINED_GLASS, barPose(i, 0.05f), life);
                    bar.setTeleportDuration((int) HUNT_STEP);
                    reticle.add(bar);
                    Displays.animateNextTick(ctx(), owner, bar, barPose(i, 1f), 5);
                }
            } else {
                for (BlockDisplay bar : reticle) {
                    bar.teleport(feet);
                }
                if (veiled) {
                    veil(false);
                }
            }
            if ((marker == null || !marker.isValid()) && roomFor(1)) {
                marker = Displays.text(ctx(), owner, markerSpot(target, 0),
                        Text.mm("<b><gradient:#FFE3B0:#E2572B>▼</gradient></b>"), 0.1f, life);
                marker.setTeleportDuration((int) HUNT_STEP);
                displays.add(marker);
                Displays.animateNextTick(ctx(), owner, marker, Displays.centered(0, 0, 0, 1.3f, 1.3f, 1.3f, 0, 0, 0),
                        5);
            } else if (marker != null) {
                marker.teleport(markerSpot(target, 0));
            }
            veiled = false;
        }

        private void followReticle(LivingEntity target, long run) {
            Location feet = reticleSpot(target, run);
            for (BlockDisplay bar : reticle) {
                if (bar.isValid()) {
                    bar.teleport(feet);
                }
            }
            if (marker != null && marker.isValid()) {
                marker.teleport(markerSpot(target, run));
            }
        }

        /** Hides (or shows again) the marks while the prey is hidden from the hunter, so they never give it away. */
        private void veil(boolean hidden) {
            for (int i = 0; i < reticle.size(); i++) {
                Displays.animate(reticle.get(i), barPose(i, hidden ? 0.001f : 1f), 3);
            }
            if (marker != null) {
                float scale = hidden ? 0.001f : 1.3f;
                Displays.animate(marker, Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, 0), 3);
            }
        }

        private static Location reticleSpot(LivingEntity target, long run) {
            Location feet = target.getLocation().add(0, 0.08, 0);
            feet.setYaw(run * RETICLE_SPIN * HUNT_STEP % 360);
            feet.setPitch(0);
            return feet;
        }

        private static Location markerSpot(LivingEntity target, long run) {
            double bob = 0.12 * Math.sin(run * 0.5);
            return target.getLocation().add(0, target.getHeight() + 0.95 + bob, 0);
        }

        /** The scent trail: amber motes drifting from the hunter towards the prey, for the hunter only. */
        private void scent(Player owner, LivingEntity target) {
            Location from = Fx.body(owner);
            Location to = Fx.body(target);
            Vector delta = to.toVector().subtract(from.toVector());
            double distance = delta.length();
            if (distance < 2) {
                return;
            }
            Vector direction = delta.multiply(1 / distance);
            Location start = from.clone().add(direction.clone().multiply(1.2));
            Location end = from.clone().add(direction.clone().multiply(Math.min(distance, 7)));
            owner.spawnParticle(Particle.TRAIL, start, 1, 0, 0, 0, 0, new Particle.Trail(end, HUNT.body(), 14));
            owner.spawnParticle(Particle.TRAIL, start.clone().add(0, 0.25, 0), 1, 0, 0, 0, 0,
                    new Particle.Trail(end.clone().add(0, 0.25, 0), HUNT.core(), 18));
        }

        private void status(Player owner, LivingEntity target, boolean hidden, long left) {
            String distance = hidden ? "??" : AbilityInfo.formatNumber(
                    Math.round(owner.getLocation().distance(target.getLocation()) * 10) / 10.0);
            owner.sendActionBar(Text.mm("<" + HUNT_TAG + ">➶ 사냥 중</" + HUNT_TAG + "> <white><prey></white> <gray>"
                    + distance + "m · " + Fx.seconds(left) + "초</gray>", Placeholder.component("prey", target.name())));
        }

        /** The hunt is called: a hunting horn, a burst of leaves and a moss shockwave around the hunter. */
        private void hornFx(Player owner) {
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Location chest = Fx.body(owner);
            World world = feet.getWorld();
            world.spawnParticle(Particle.TINTED_LEAVES, chest, Vfx.n(ctx(), 26), 0.6, 0.5, 0.6, 0.05, PALETTE.body(),
                    true);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, chest, Vfx.n(ctx(), 18), 0.4, 0.5, 0.4, 0,
                    HUNT_GLOW, true);
            Vfx.shockwave(ctx(), owner, feet, 0.4, 3.5, 7, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.2f), true);
            Fx.sound(feet, Sound.ITEM_GOAT_HORN_SOUND_2, 2.0f, 1.0f);
            Fx.sound(feet, Sound.ENTITY_WOLF_GROWL, 0.9f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(feet, Sound.ITEM_CROSSBOW_LOADING_END, 1.0f, 0.6f);
        }

        /** Lock-on: a ring closing in on the prey and motes racing from the hunter to it. */
        private void lockOn(Player owner, LivingEntity target) {
            if (seen(owner, target) && owner.getWorld().equals(target.getWorld())) {
                Location from = Fx.body(owner);
                Location to = Fx.body(target);
                for (int i = 0; i < Vfx.n(ctx(), 5); i++) {
                    Vfx.trail(from.clone().add(Fx.jitter(0.3f), Fx.jitter(0.3f), Fx.jitter(0.3f)), to, HUNT.body(),
                            6 + i, true);
                }
            }
            Loop.repeat(ctx(), owner, 0, 1, LOCK_TICKS, run -> {
                if (!target.isValid() || target.isDead() || !seen(owner, target)) {
                    return false;
                }
                double radius = 1.8 - 1.2 * (run + 1) / LOCK_TICKS;
                Vfx.ring(ctx(), Fx.body(target), radius, 18, run * 0.5, Particle.DUST_COLOR_TRANSITION, HUNT_GLOW,
                        true);
                return true;
            });
        }
    }

    /** One bar of the hunt reticle, {@code index} of four around the feet, scaled for the grow/hide animation. */
    private static Transformation barPose(int index, float scale) {
        double angle = index * TAU / RETICLE_BARS;
        float x = (float) (Math.cos(angle) * RETICLE_RADIUS * scale);
        float z = (float) (Math.sin(angle) * RETICLE_RADIUS * scale);
        // Each bar lies along the tangent of the circle, so the four together read as a square sight.
        return Displays.transform(x, 0, z, RETICLE_WIDTH * scale, 0.04f * scale, RETICLE_LENGTH * scale,
                (float) Math.toDegrees(angle), 0, 0);
    }

    // ------------------------------------------------------------------ shared helpers

    /** A short comic pop-up over a spot: grows in, hangs, shrinks away. */
    private void pop(Location at, String miniMessage) {
        if (!roomFor(1)) {
            return;
        }
        Player owner = player();
        TextDisplay label = Displays.text(ctx(), owner, at, Text.mm(miniMessage), 0.2f, POP_TICKS + 2);
        displays.add(label);
        Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.3f, 0, 1.2f, 1.2f, 1.2f, 0, 0, 0), 4);
        ctx().runLater(owner, POP_TICKS - 5, () -> {
            if (label.isValid()) {
                Displays.shrinkAway(ctx(), owner, label, 4);
            }
        });
    }

    private BlockDisplay display(Location at, Material material, Transformation pose, long lifetime) {
        BlockDisplay display = Displays.block(ctx(), player(), at, material.createBlockData(), pose, lifetime);
        if (material != Material.RED_STAINED_GLASS) {
            // The trap is a plain iron thing: lit by the world, not glowing.
            display.setBrightness(null);
        }
        displays.add(display);
        return display;
    }

    /** True if {@code count} more displays fit in {@link #MAX_DISPLAYS}. */
    private boolean roomFor(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    /** False for an enemy player hidden from the owner by stealth. */
    private static boolean seen(Player owner, LivingEntity entity) {
        return !(entity instanceof Player player) || player.equals(owner) || owner.canSee(player);
    }
}
