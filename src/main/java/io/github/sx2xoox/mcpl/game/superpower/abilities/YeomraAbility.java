package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 염라대왕 — the king of the underworld, who judges the dead by their past in the karma mirror and sends his black-robed
 * messengers to fetch them. He sees where every enemy around stood a moment ago (an afterimage only he sees); the active
 * raises the karma mirror at the aimed enemy's past spot and drags it back along its own footsteps, undoing a dash, an
 * escape or an engage (an enemy that stood still all along is judged for its karma instead); the ultimate sends one
 * reaper for each of the nearest enemies, walking straight at it through walls, calling its name, never to be hit.
 *
 * <p>Unlike the 시간술사, who rewinds only himself, the mirror rewinds the <em>enemy</em> along the path the server saw it
 * walk, checking every step for a safe spot (it stops where the way is blocked now). Unlike summons that fight and find
 * their way, the reapers are slow, unstoppable timers: walking away outpaces them, standing still does not.
 *
 * <p>Look: underworld crimson, black lacquer and ghost-fire blue. The afterimages are faint blue silhouettes at the end of
 * a dotted trail of footsteps. The karma mirror is a square mirror of ghostly blue glass in a black lacquer frame with a
 * crimson rim on a black stand; it rises from the ground facing the enemy, shines a blue beam on it and pulls it into
 * itself along a trail of ghost fire. The reapers rise out of the ground in black robes with a crimson sash, pale faces
 * with glowing blue eyes and the translucent black horsehair hat of the old messengers, outlined in crimson.
 */
public final class YeomraAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("yeomra", "염라대왕", Material.LECTERN)
            .color(TextColor.color(0x8E1B24))
            .difficulty(2)
            .passive("업경 잔상",
                    "{echo_radius}블록 안의 적마다 {rewind_seconds}초 전에 서 있던 자리에 희미한 푸른 잔상이 나에게만 "
                            + "보이고, 조준한 적은 그 자리까지 지나온 발자취가 점선으로 이어집니다. 업경대가 그 적을 "
                            + "되돌려 놓을 자리와 길입니다.")
            .active("업경대",
                    "조준한 적({mirror_range}블록 이내)의 {rewind_seconds}초 전 자리에 업경대가 솟아 그 적을 비춥니다. "
                            + "{mirror_windup}초 뒤 그 적은 자기가 지나온 발자취를 거슬러 {rewind_time}초 만에 업경대 앞으로 "
                            + "끌려가고(지금 막힌 곳에서는 멈춤) {mirror_damage} 피해를 입습니다. 그동안 거의 움직이지 "
                            + "않은 적({karma_still}블록 미만)은 끌려가는 대신 업보를 심판받아 {karma_damage} 피해를 "
                            + "입습니다.",
                    10)
            .ultimate("저승사자 출두",
                    "{reaper_range}블록 안의 가까운 적 최대 {reaper_count}명에게 저승사자를 한 명씩 보냅니다. 저승사자는 "
                            + "대상에게서 {reaper_spawn}블록 떨어진 땅(내가 더 가까우면 내 곁)에서 차례로 솟아나, "
                            + "{reaper_duration}초 동안 대상의 이름을 부르며 초속 {reaper_speed}블록(대상이 한 명뿐이면 "
                            + "{reaper_solo_speed}블록)으로 벽도 지나 곧장 걸어갑니다. 저승사자는 공격받지 않고, 대상에게 "
                            + "닿으면 {reaper_damage} 피해를 주고 {reaper_bind}초간 명부의 사슬로 묶은 뒤 사라집니다. 걸어서 "
                            + "따돌릴 수는 있어도 떨쳐 낼 수는 없습니다.")
            .param("echo_radius", 20)
            .param("rewind_seconds", 2)
            .param("mirror_range", 18)
            .param("mirror_windup", 0.2)
            .param("rewind_time", 0.4)
            .param("mirror_damage", 3)
            .param("karma_damage", 4)
            .param("karma_still", 1)
            .param("reaper_range", 30)
            .param("reaper_count", 3)
            .param("reaper_spawn", 9)
            .param("reaper_duration", 8)
            .param("reaper_speed", 3.2)
            .param("reaper_solo_speed", 3.6)
            .param("reaper_damage", 6)
            .param("reaper_bind", 1)
            .build();

    private static final String COLOR_TAG = "#8E1B24";
    private static final String GHOST_TAG = "#4FA3FF";
    private static final String PALE_TAG = "#D9E6F2";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: crimson, black lacquer, ghost fire
    /** Pale ghost-fire blue, ghost-fire blue, black lacquer. */
    private static final Vfx.Palette GHOST = Vfx.Palette.of(0xCFE6FF, 0x4FA3FF, 0x121014);
    /** Bright crimson, underworld crimson, lacquer black. */
    private static final Vfx.Palette CRIMSON = Vfx.Palette.of(0xE05A63, 0x8E1B24, 0x121014);
    private static final Color GHOST_BLUE = Color.fromRGB(0x4FA3FF);
    private static final Color CRIMSON_RED = Color.fromRGB(0x8E1B24);
    private static final Particle.DustOptions ECHO_DUST = new Particle.DustOptions(Color.fromRGB(0x6FB6FF), 0.7f);
    private static final Particle.DustOptions ECHO_HEAD = new Particle.DustOptions(Color.fromRGB(0x9FCBFF), 0.6f);
    private static final Particle.DustOptions STEP_DUST = new Particle.DustOptions(Color.fromRGB(0x3E6FA8), 0.45f);
    private static final Particle.DustOptions EYE_DUST = new Particle.DustOptions(GHOST_BLUE, 0.45f);
    private static final Particle.DustOptions CRIMSON_DUST = CRIMSON.dust(1.0f);
    private static final Particle.DustTransition GHOST_GLOW = GHOST.glow(1.0f);
    private static final Particle.DustTransition GHOST_FADE = GHOST.fade(1.1f);
    private static final Particle.DustTransition CRIMSON_FADE = CRIMSON.fade(1.2f);

    /** Block and item types of the visuals (block data and items are created on use: they need a running server). */
    private static final Material LACQUER = Material.BLACK_CONCRETE;
    private static final Material RIM = Material.RED_CONCRETE;
    private static final Material MIRROR_GLASS = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material ROBE = Material.BLACK_WOOL;
    private static final Material SASH = Material.RED_CONCRETE;
    private static final Material FACE = Material.WHITE_CONCRETE;
    private static final Material HAT = Material.BLACK_STAINED_GLASS;
    private static final Material CHAIN_ITEM = Material.IRON_CHAIN;

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    /** Vanilla's vertical speed of a body resting on the ground: one tick of gravity. */
    private static final double REST_FALL = -Motion.GRAVITY * Motion.DRAG;

    // the watch
    /** Enemies farther than this from the king are not watched (beyond every range of the ability). */
    private static final double WATCH_EXTRA = 8;
    /** Ticks of history kept beyond the rewind itself. */
    private static final long HISTORY_EXTRA = 12;
    private static final long ECHO_PERIOD = 4;
    /** An afterimage closer than this to the enemy is not drawn (it is standing on it). */
    private static final double ECHO_MIN = 0.4;
    private static final int MAX_ECHOES = 6;
    /** The mirror stands on ground at most this far below the spot meant for it (else on the past spot itself). */
    private static final double MIRROR_SNAP = 1.25;

    // the mirror
    private static final long MIRROR_STAY = 12;

    // the reapers
    private static final long RISE_TICKS = 10;
    private static final long RISE_STAGGER = 6;
    private static final double RISE_DEPTH = 2.4;
    private static final long SINK_TICKS = 8;
    private static final long CALL_PERIOD = 30;
    /** A reaper touches its target when it comes this close to its hitbox. */
    private static final double TOUCH = 0.35;
    private static final int REAPER_PARTS = 6;
    /** The least of a reaper that may walk: its robe and its face (a reaper is never sent without a body). */
    private static final int MIN_REAPER_PARTS = 3;

    /** Where every watched enemy was on the last ticks, by entity id. */
    private final Map<UUID, Track> tracks = new HashMap<>();
    private final List<Display> displays = new ArrayList<>();

    /** One sampled position. */
    private record Sample(long tick, double x, double y, double z) {
        Location at(World world) {
            return new Location(world, x, y, z);
        }
    }

    /** The last positions of one enemy, newest last. */
    private static final class Track {
        final ArrayDeque<Sample> samples = new ArrayDeque<>();
        @Nullable World world;
        long seen;
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        tracks.clear();
        displays.clear();
    }

    // ------------------------------------------------------------------ passive: the karma mirror's afterimages

    @Override
    public void onTick(long tick) {
        Player owner = player();
        watch(owner);
        if (tick % ECHO_PERIOD == 0) {
            drawEchoes(owner);
        }
    }

    private long rewindTicks() {
        return Math.max(1, params().ticks("rewind_seconds"));
    }

    /** Samples where every enemy around is this tick (once per tick, whoever asks). */
    private void watch(Player owner) {
        long now = ctx().currentTick();
        double range = Math.max(params().num("echo_radius"), params().num("mirror_range")) + WATCH_EXTRA;
        long keep = rewindTicks() + params().ticks("mirror_windup") + HISTORY_EXTRA;
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            Track track = tracks.computeIfAbsent(enemy.getUniqueId(), id -> new Track());
            Location at = enemy.getLocation();
            if (track.world != null && !track.world.equals(at.getWorld())) {
                track.samples.clear();
            }
            track.world = at.getWorld();
            track.seen = now;
            Sample last = track.samples.peekLast();
            if (last != null && last.tick() == now) {
                continue;
            }
            if (last != null && now - last.tick() > 2) {
                // A gap in the watch (it was out of range): its old steps are not a path any more.
                track.samples.clear();
            }
            track.samples.addLast(new Sample(now, at.getX(), at.getY(), at.getZ()));
            while (!track.samples.isEmpty() && now - track.samples.peekFirst().tick() > keep) {
                track.samples.removeFirst();
            }
        }
        if (now % 40 == 0) {
            tracks.values().removeIf(track -> now - track.seen > 40);
        }
    }

    /** The samples of {@code target} from {@code since} (a tick) up to now, oldest first; empty without a watch. */
    private List<Sample> pathSince(LivingEntity target, long since) {
        Track track = tracks.get(target.getUniqueId());
        List<Sample> path = new ArrayList<>();
        if (track == null || !target.getWorld().equals(track.world)) {
            return path;
        }
        for (Sample sample : track.samples) {
            if (sample.tick() >= since) {
                path.add(sample);
            }
        }
        return path;
    }

    private static double length(List<Sample> path) {
        double total = 0;
        for (int i = 1; i < path.size(); i++) {
            Sample a = path.get(i - 1);
            Sample b = path.get(i);
            total += Math.sqrt(sq(b.x() - a.x()) + sq(b.y() - a.y()) + sq(b.z() - a.z()));
        }
        return total;
    }

    /**
     * Every few ticks, for each enemy around (never one hidden from the king), a faint blue silhouette where it stood
     * the rewind time ago and a dotted trail of its steps from there: drawn only for the king.
     */
    private void drawEchoes(Player owner) {
        long now = ctx().currentTick();
        int drawn = 0;
        // The trail of steps is the mirror's preview: only for the enemy in the king's sights.
        LivingEntity aimed = ctx().findTarget(owner, params().num("mirror_range"));
        int bodyPoints = Vfx.n(ctx(), 5);
        int headPoints = Math.max(3, Vfx.n(ctx(), 6));
        int stepPoints = Vfx.n(ctx(), 8);
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), params().num("echo_radius"))) {
            if (drawn >= MAX_ECHOES || isHidden(owner, enemy)) {
                continue;
            }
            List<Sample> path = pathSince(enemy, now - rewindTicks());
            if (path.size() < 2) {
                continue;
            }
            World world = enemy.getWorld();
            Location past = path.getFirst().at(world);
            if (past.distanceSquared(enemy.getLocation()) < ECHO_MIN * ECHO_MIN) {
                continue;
            }
            drawn++;
            double height = enemy.getHeight();
            // The silhouette: a body line and a ring for the head (counts follow the effect scale).
            for (int i = 0; i < bodyPoints; i++) {
                double y = height * (0.1 + 0.6 * i / Math.max(1, bodyPoints - 1));
                owner.spawnParticle(Particle.DUST, past.getX(), past.getY() + y, past.getZ(), 1, 0, 0, 0, 0, ECHO_DUST);
            }
            double headY = past.getY() + height * 0.88;
            for (int i = 0; i < headPoints; i++) {
                double angle = i * TAU / headPoints + now * 0.05;
                owner.spawnParticle(Particle.DUST, past.getX() + Math.cos(angle) * 0.2, headY,
                        past.getZ() + Math.sin(angle) * 0.2, 1, 0, 0, 0, 0, ECHO_HEAD);
            }
            owner.spawnParticle(Particle.DUST, past.getX() - 0.28, past.getY() + height * 0.6, past.getZ(), 1, 0, 0, 0,
                    0, ECHO_DUST);
            owner.spawnParticle(Particle.DUST, past.getX() + 0.28, past.getY() + height * 0.6, past.getZ(), 1, 0, 0, 0,
                    0, ECHO_DUST);
            if (now % (ECHO_PERIOD * 2) == 0) {
                owner.spawnParticle(Particle.SOUL_FIRE_FLAME, past.getX(), headY + 0.3, past.getZ(), 1, 0, 0, 0, 0);
            }
            if (!enemy.equals(aimed)) {
                continue;
            }
            // The trail of footsteps back to it: the way the mirror would drag it.
            int stride = Math.max(1, path.size() / stepPoints);
            for (int i = 0; i < path.size(); i += stride) {
                Sample sample = path.get(i);
                owner.spawnParticle(Particle.DUST, sample.x(), sample.y() + 0.08, sample.z(), 1, 0, 0, 0, 0,
                        STEP_DUST);
            }
        }
    }

    // ------------------------------------------------------------------ active: the karma mirror

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, params().num("mirror_range"));
        if (target == null || isHidden(owner, target)) {
            return ActivationResult.fail("업경대에 비출 적을 조준하세요");
        }
        watch(owner);
        new Mirror(owner, target).start();
        return ActivationResult.success();
    }

    /**
     * One judgement: the mirror rises at the enemy's past spot facing it, then drags it back along the path the server
     * saw it walk, a step every tick, as long as every step is still a safe spot; an enemy that barely moved is judged
     * for its karma where it stands.
     */
    private final class Mirror implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final World world;
        private final long cast;
        private final long windup;
        private final long rewind;
        private final boolean karma;
        private final Location spot;
        private final float yaw;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private List<Sample> path = List.of();
        private int stepsDone;
        private boolean done;

        Mirror(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.world = target.getWorld();
            this.cast = ctx().currentTick();
            this.windup = Math.max(1, params().ticks("mirror_windup"));
            this.rewind = Math.max(1, params().ticks("rewind_time"));
            List<Sample> past = pathSince(target, cast - rewindTicks());
            this.karma = past.size() < 2 || length(past) < Math.max(0, params().num("karma_still"));
            Location origin = past.isEmpty() ? target.getLocation() : past.getFirst().at(world);
            // The enemy may have been in the air back then (knocked up, falling): the mirror stands under it.
            Location ground = settled(world, origin);
            Vector toTarget = target.getLocation().toVector().subtract(ground.toVector());
            // An enemy judged where it stands faces a mirror set before it, between it and the king.
            if (karma || toTarget.setY(0).lengthSquared() < 0.25) {
                Vector toOwner = Motion.horizontal(owner.getLocation().toVector()
                        .subtract(target.getLocation().toVector()));
                if (toOwner.lengthSquared() < 1.0E-6) {
                    toOwner = new Vector(0, 0, 1);
                }
                Location front = target.getLocation().add(toOwner.clone().multiply(1.4));
                this.spot = Displays.unrotated(standOn(world, front, settled(world, target.getLocation())));
                this.yaw = yawOf(toOwner.clone().multiply(-1));
            } else {
                // It stands just behind the past spot, facing the enemy: the enemy is pulled up in front of it.
                Location behind = ground.clone().subtract(Motion.horizontal(toTarget).multiply(1.1));
                this.spot = Displays.unrotated(standOn(world, behind, ground));
                this.yaw = yawOf(toTarget);
            }
        }

        void start() {
            raise();
            Location glass = glassCenter();
            Vfx.ring(ctx(), spot.clone().add(0, 0.05, 0), 0.9, 14, 0, Particle.DUST_COLOR_TRANSITION, CRIMSON_FADE,
                    true);
            world.spawnParticle(Particle.SOUL, spot.clone().add(0, 0.3, 0), Vfx.n(ctx(), 8), 0.4, 0.2, 0.4, 0.02, null,
                    true);
            Fx.sound(glass, Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 0.9f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(glass, Sound.BLOCK_BELL_RESONATE, 0.8f, 1.2f);
            Fx.sound(owner.getEyeLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 0.8f, 0.6f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>業 업경대</b></" + COLOR_TAG + "> <white><target></white>"
                            + (karma ? " <" + PALE_TAG + ">업보를 심판합니다</" + PALE_TAG + ">"
                            : " <gray>" + Fx.seconds(rewindTicks()) + "초 전으로</gray>"),
                    Placeholder.component("target", target.name())));
            if (target instanceof Player judged) {
                judged.playSound(glass, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1.0f, 0.6f);
            }
            Loop.repeat(ctx(), owner, 1, 1, windup + rewind + 1, this, this::end);
        }

        /** The mirror rises: it grows out of the ground facing the enemy. */
        private void raise() {
            int count = reserve(MirrorPart.ALL.length) ? MirrorPart.ALL.length
                    : reserve(MirrorPart.CORE) ? MirrorPart.CORE : 0;
            long life = windup + rewind + MIRROR_STAY + 12;
            for (int i = 0; i < count; i++) {
                MirrorPart part = MirrorPart.ALL[i];
                BlockDisplay display = Displays.block(ctx(), owner, spot, part.material.createBlockData(),
                        part.pose(yaw, 0.05f), life);
                display.setViewRange(2.0f);
                if (part.material == MIRROR_GLASS) {
                    Displays.outline(display, GHOST_BLUE);
                }
                parts.add(display);
                displays.add(display);
                Displays.animateNextTick(ctx(), owner, display, part.pose(yaw, 1f), (int) Math.max(2, windup));
            }
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(world)) {
                fade();
                return false;
            }
            if (tick < windup) {
                beam(tick);
                return true;
            }
            if (tick == windup) {
                begin();
            }
            if (karma) {
                judge();
                return false;
            }
            long step = tick - windup + 1;
            if (!pull(step) || step >= rewind) {
                land();
                judge();
                return false;
            }
            return true;
        }

        /** The mirror shines its blue light on the enemy. */
        private void beam(long tick) {
            if (isHidden(owner, target)) {
                return;
            }
            Location glass = glassCenter();
            Location body = Fx.body(target);
            double density = Math.min(3, 12 / Math.max(1, glass.distance(body)));
            Vfx.line(ctx(), glass, body, density, Particle.DUST_COLOR_TRANSITION, GHOST_GLOW, false);
            Vfx.trail(glass, body, GHOST_BLUE, 6, false);
            if (tick % 2 == 0) {
                Vfx.dot(glass, Particle.END_ROD, null, false);
            }
        }

        /** The rewind begins: the path from the past spot to now, including the steps taken during the wind-up. */
        private void begin() {
            path = pathSince(target, cast - rewindTicks());
            Location body = Fx.body(target);
            Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 0.7f);
            Fx.sound(body, Sound.ITEM_TRIDENT_RETURN, 1.0f, 0.6f + Fx.jitter(0.05f));
            Vfx.dot(body, Particle.FLASH, GHOST_BLUE, false);
        }

        /**
         * One step back: the enemy goes to the sample that much further into its past. A step that is not a safe spot
         * any more (a wall raised since, the border) ends the rewind there.
         */
        private boolean pull(long step) {
            int last = path.size() - 1;
            if (last <= 0) {
                return false;
            }
            int index = (int) Math.max(0, Math.round(last - (double) last * step / rewind));
            Location from = target.getLocation();
            Location to = path.get(index).at(world);
            to.setYaw(from.getYaw());
            to.setPitch(from.getPitch());
            if (!isSafe(to)) {
                return false;
            }
            trail(from, to);
            target.teleport(to);
            target.setFallDistance(0);
            stop();
            stepsDone++;
            return true;
        }

        /**
         * The rewind is over (or blocked on the way): the enemy is put down on the ground straight under it, since
         * the step it ended on may have been in the air (it was knocked up, swinging, falling then). The mirror never
         * leaves it hanging to take fall damage.
         */
        private void land() {
            if (stepsDone == 0 || !target.isValid()) {
                return;
            }
            Location at = target.getLocation();
            Location ground = settled(world, at);
            if (ground.getY() < at.getY() - 1.0E-6 && isSafe(ground)) {
                trail(at, ground);
                target.teleport(ground);
            }
            target.setFallDistance(0);
            stop();
        }

        private boolean isSafe(Location feet) {
            Body body = new Body(target.getWidth(), target.getHeight(), target.getEyeHeight());
            return Spots.isSafe(ctx(), feet, body);
        }

        /** No momentum survives the judgement (a player's server-side velocity is stale, so it is never replayed). */
        private void stop() {
            double fall = target instanceof Player ? REST_FALL : Math.min(0, target.getVelocity().getY());
            target.setVelocity(new Vector(0, fall, 0));
        }

        private void trail(Location from, Location to) {
            Location a = from.clone().add(0, target.getHeight() * 0.5, 0);
            Location b = to.clone().add(0, target.getHeight() * 0.5, 0);
            Vfx.line(ctx(), a, b, 4, Particle.DUST_COLOR_TRANSITION, GHOST_FADE, false);
            Vfx.dot(b, Particle.SOUL_FIRE_FLAME, null, false);
            Vfx.trail(b, glassCenter(), GHOST_BLUE, 5, false);
        }

        /** The verdict: damage, a crimson flash at the mirror and the word of judgement. */
        private void judge() {
            done = true;
            boolean wasKarma = karma || stepsDone == 0 && path.size() < 2;
            double damage = params().num(wasKarma ? "karma_damage" : "mirror_damage");
            Combat.hit(ctx(), owner, target, damage);
            Location body = Fx.body(target);
            Location glass = glassCenter();
            if (!isHidden(owner, target)) {
                Vfx.dot(body, Particle.FLASH, CRIMSON_RED, true);
                Vfx.burst(ctx(), body, CRIMSON, 0.6, Particle.SOUL_FIRE_FLAME, true);
                Vfx.helix(ctx(), target.getLocation().add(0, 0.05, 0), 0.7, target.getHeight() + 0.3, 1.2, 2, 10,
                        ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_COLOR_TRANSITION, GHOST_GLOW, true);
                popWord(body.clone().add(0, target.getHeight() * 0.6 + 0.6, 0), wasKarma
                        ? "<b><gradient:#FFFFFF:" + COLOR_TAG + ">업보!</gradient></b>"
                        : "<b><gradient:" + PALE_TAG + ":" + GHOST_TAG + ">되돌림</gradient></b>");
            }
            world.spawnParticle(Particle.BLOCK, glass, Vfx.n(ctx(), 12), 0.4, 0.4, 0.4, 0.1,
                    MIRROR_GLASS.createBlockData(), true);
            Vfx.shockwave(ctx(), owner, spot.clone().add(0, 0.08, 0), 0.3, 1.8, 5, Particle.DUST, CRIMSON_DUST, true);
            Fx.sound(body, Sound.BLOCK_BELL_USE, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.35f, 0.55f);
            Fx.sound(glass, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.8f, 0.6f);
            ctx().runLater(owner, MIRROR_STAY, this::fade);
        }

        /** The mirror sinks back into the ground. */
        private void fade() {
            done = true;
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 6);
                }
            }
        }

        void end() {
            if (!done) {
                fade();
            }
        }

        private Location glassCenter() {
            Vector front = facing(yaw).multiply(0.12);
            return spot.clone().add(front).add(0, MirrorPart.GLASS_Y, 0);
        }

        private void popWord(Location at, String text) {
            if (!reserve(1)) {
                return;
            }
            TextDisplay word = Displays.text(ctx(), owner, at, Text.mm(text), 0.6f, 18);
            word.setViewRange(2.0f);
            displays.add(word);
            Displays.animateNextTick(ctx(), owner, word, Displays.centered(0, 0.3f, 0, 1.2f, 1.2f, 1.2f, 0, 0, 0), 5);
        }
    }

    /** One block of the karma mirror, in its own frame (+z is the mirror's face, y up from the ground). */
    private record MirrorPart(Material material, float y, float z, float sx, float sy, float sz) {

        static final float GLASS_Y = 1.55f;
        /** The parts in order of importance: the glass, the frame and the stand come first. */
        static final MirrorPart[] ALL = {
                new MirrorPart(MIRROR_GLASS, GLASS_Y, 0.07f, 1.0f, 1.0f, 0.04f),
                new MirrorPart(LACQUER, GLASS_Y, 0f, 1.24f, 1.24f, 0.1f),
                new MirrorPart(LACQUER, 0.5f, -0.02f, 0.14f, 0.92f, 0.14f),
                new MirrorPart(RIM, GLASS_Y, -0.06f, 1.4f, 1.4f, 0.05f),
                new MirrorPart(RIM, 0.06f, -0.02f, 0.72f, 0.12f, 0.46f),
        };
        static final int CORE = 3;

        /** This part turned to {@code yaw} around the mirror's stand, scaled by {@code scale} (from the ground). */
        Transformation pose(float yaw, float scale) {
            Vector offset = facing(yaw).multiply(z * scale);
            return Displays.transform((float) offset.getX(), y * scale, (float) offset.getZ(), sx * scale, sy * scale,
                    sz * scale, yaw, 0, 0);
        }
    }

    // ------------------------------------------------------------------ ultimate: the reapers

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        List<LivingEntity> targets = new ArrayList<>();
        int max = Math.max(1, params().integer("reaper_count"));
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), params().num("reaper_range"))) {
            if (!isHidden(owner, enemy)) {
                targets.add(enemy);
                if (targets.size() >= max) {
                    break;
                }
            }
        }
        if (targets.isEmpty()) {
            return ActivationResult.fail("저승사자가 데려갈 자가 없습니다");
        }
        // Every reaper's body is spawned (still underground) right now, so a later one never rises invisible: the
        // display budget is shared out first, and a reaper that would have no body is not sent at all.
        displays.removeIf(display -> !display.isValid());
        int room = MAX_DISPLAYS - displays.size();
        int size = reaperSize(room, targets.size());
        int sent = Math.min(targets.size(), room / size);
        if (sent <= 0) {
            return ActivationResult.fail("저승사자가 나설 자리가 없습니다");
        }
        targets = new ArrayList<>(targets.subList(0, sent));
        double speed = params().num(targets.size() == 1 ? "reaper_solo_speed" : "reaper_speed");
        Location head = owner.getEyeLocation();
        Vfx.dot(head, Particle.FLASH, CRIMSON_RED, true);
        Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.1, 0), 0.4, 4, 8, Particle.DUST_COLOR_TRANSITION,
                CRIMSON_FADE, true);
        owner.getWorld().spawnParticle(Particle.SOUL, owner.getLocation().add(0, 0.5, 0), Vfx.n(ctx(), 20), 1.2, 0.3,
                1.2, 0.03, null, true);
        Fx.sound(head, Sound.BLOCK_BELL_USE, 2.0f, 0.5f);
        Fx.sound(head, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.2f, 0.6f);
        Fx.sound(head, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.0f, 0.5f);
        ctx().announce(Text.mm("<" + COLOR_TAG + ">☠</" + COLOR_TAG + "> <white><owner></white><gray>이(가) </gray><"
                        + COLOR_TAG + "><b>저승사자</b></" + COLOR_TAG + "><gray>를 보냈습니다!</gray>",
                Placeholder.component("owner", owner.name())));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>☠ 저승사자 출두</b></" + COLOR_TAG + "> <gray>"
                + targets.size() + "명</gray>"));
        for (int i = 0; i < targets.size(); i++) {
            new Reaper(owner, targets.get(i), speed, i * RISE_STAGGER, size).start();
        }
        return ActivationResult.success();
    }

    /**
     * The displays each of {@code count} reapers gets out of {@code room}: all its parts and the name it calls, all
     * its parts, all but the sash, or at least its robe and face.
     */
    private static int reaperSize(int room, int count) {
        for (int size : new int[] {REAPER_PARTS + 1, REAPER_PARTS, REAPER_PARTS - 1}) {
            if (size * count <= room) {
                return size;
            }
        }
        return MIN_REAPER_PARTS;
    }

    private enum ReaperStage { RISING, WALKING, SINKING, DONE }

    /**
     * One messenger of the underworld: it rises out of the ground a little way from its target, then walks straight at
     * it at a steady pace through anything, calling its name, until it touches it (damage and the chains of the
     * register) or its time runs out. It waits, invisible, while its target is hidden from the king.
     */
    private final class Reaper implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final double perTick;
        private final long delay;
        private final long walkTicks;
        /** Displays this reaper has: its parts, plus the name it calls when there is room for all of them. */
        private final int size;
        private final World world;
        private final List<BlockDisplay> body = new ArrayList<>();
        private @Nullable TextDisplay call;
        private Location at;
        private float yaw;
        private ReaperStage stage = ReaperStage.RISING;
        private long stageStart;
        /** Unseen: waiting underground to rise, or waiting while its target is hidden from the king. */
        private boolean veiled = true;

        Reaper(Player owner, LivingEntity target, double speed, long delay, int size) {
            this.owner = owner;
            this.target = target;
            this.perTick = Math.max(0.01, speed) / 20.0;
            this.delay = delay;
            this.size = size;
            this.walkTicks = Math.max(1, params().ticks("reaper_duration"));
            this.world = target.getWorld();
            this.at = riseSpot();
        }

        /** Where it rises: on the ground between the target and the king, a set distance from the target. */
        private Location riseSpot() {
            Location feet = target.getLocation();
            Vector toOwner = owner.getLocation().toVector().subtract(feet.toVector()).setY(0);
            double distance = toOwner.length();
            Vector direction = distance > 1.0E-3 ? toOwner.multiply(1 / distance) : new Vector(0, 0, 1);
            double away = Math.min(Math.max(0, params().num("reaper_spawn")), distance);
            Location spot = feet.clone().add(direction.multiply(away));
            Location probe = spot.clone().add(0, 2, 0);
            Spots.snapToGround(world, probe, 6);
            if (probe.getY() < spot.getY() + 1.99) {
                spot = probe;
            }
            this.yaw = yawOf(feet.toVector().subtract(spot.toVector()));
            return Displays.unrotated(spot);
        }

        void start() {
            summon();
            long total = delay + RISE_TICKS + walkTicks + SINK_TICKS + 4;
            Loop.repeat(ctx(), owner, 1, 1, total, this, this::end);
        }

        /** Its body, deep in the ground under where it will rise and seen by nobody yet (the budget is its own). */
        private void summon() {
            Location deep = at.clone().subtract(0, RISE_DEPTH, 0);
            int count = Math.min(REAPER_PARTS, size);
            long life = delay + RISE_TICKS + walkTicks + SINK_TICKS + 20;
            for (int i = 0; i < count; i++) {
                ReaperPart part = ReaperPart.ALL[i];
                BlockDisplay display = Displays.block(ctx(), owner, deep, part.material.createBlockData(),
                        part.pose(yaw), life);
                display.setViewRange(0f);
                display.setTeleportDuration((int) RISE_TICKS);
                if (part.outlined) {
                    Displays.outline(display, CRIMSON_RED);
                }
                body.add(display);
                displays.add(display);
            }
            if (size > REAPER_PARTS) {
                call = Displays.text(ctx(), owner, deep.clone().add(0, 2.6, 0), callText(), 0.55f, life);
                call.setTeleportDuration((int) RISE_TICKS);
                call.setViewRange(0f);
                displays.add(call);
            }
        }

        @Override
        public boolean test(long tick) {
            if (tick < delay) {
                return true;
            }
            if (tick == delay) {
                rise();
            }
            if (stage != ReaperStage.SINKING && !Combat.isTargetable(ctx(), owner, target)) {
                sink(false);
            }
            return switch (stage) {
                case RISING -> rising(tick - delay);
                case WALKING -> walking(tick);
                case SINKING -> sinking();
                case DONE -> false;
            };
        }

        /** It comes up out of the ground in a puff of souls. */
        private void rise() {
            stageStart = ctx().currentTick();
            veil(isHidden(owner, target));
            ctx().runLater(owner, 1, () -> place(at));
            world.spawnParticle(Particle.SOUL, at.clone().add(0, 0.2, 0), Vfx.n(ctx(), 14), 0.4, 0.1, 0.4, 0.04, null,
                    true);
            world.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 0.2, 0), Vfx.n(ctx(), 8), 0.35, 0.1, 0.35, 0.02,
                    null, true);
            Vfx.ring(ctx(), at.clone().add(0, 0.05, 0), 0.8, 14, 0, Particle.DUST, CRIMSON_DUST, true);
            Fx.sound(at, Sound.BLOCK_SOUL_SAND_BREAK, 1.0f, 0.6f);
            Fx.sound(at, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 0.9f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.PARTICLE_SOUL_ESCAPE, 1.2f, 0.7f);
        }

        private boolean rising(long elapsed) {
            veil(isHidden(owner, target));
            if (elapsed % 2 == 0) {
                Vfx.cloud(ctx(), at.clone().add(0, 0.2, 0), Particle.SOUL_FIRE_FLAME, 2, 0.3, 0.01, null, false);
            }
            if (elapsed + 1 >= RISE_TICKS) {
                stage = ReaperStage.WALKING;
                stageStart = ctx().currentTick();
                for (BlockDisplay part : body) {
                    if (part.isValid()) {
                        part.setTeleportDuration(1);
                    }
                }
                if (call != null && call.isValid()) {
                    call.setTeleportDuration(1);
                }
                callName();
            }
            return true;
        }

        /** It walks straight at its target, through anything, and calls its name. */
        private boolean walking(long tick) {
            long now = ctx().currentTick();
            if (now - stageStart >= walkTicks || body.stream().noneMatch(Display::isValid)) {
                // Its time is up, or its body is gone: a reaper nobody can see never seizes anyone.
                sink(false);
                return true;
            }
            boolean hidden = isHidden(owner, target);
            veil(hidden);
            if (hidden) {
                // It waits while the king cannot see its target: it must not lead anyone to it.
                return true;
            }
            Location feet = target.getLocation();
            Vector to = feet.toVector().subtract(at.toVector());
            double distance = to.length();
            if (touches()) {
                seize();
                return true;
            }
            if (distance > 1.0E-6) {
                Vector move = to.multiply(Math.min(perTick, distance) / distance);
                at = Displays.unrotated(at.clone().add(move));
                float wanted = yawOf(move);
                if (Math.abs(wrap(wanted - yaw)) > 3f) {
                    yaw = wanted;
                    for (int i = 0; i < body.size(); i++) {
                        Displays.animate(body.get(i), ReaperPart.ALL[i].pose(yaw), 3);
                    }
                }
            }
            double bob = Math.sin(tick * 0.18) * 0.05;
            place(at.clone().add(0, bob, 0));
            if (touches()) {
                seize();
                return true;
            }
            if (tick % 2 == 0) {
                eyes();
            }
            if (tick % 3 == 0) {
                Vfx.dot(at.clone().add(0, 0.1, 0), Particle.SOUL, null, false);
                Vfx.dot(at.clone().add(0, 0.05, 0), Particle.DUST, CRIMSON_DUST, false);
            }
            if ((now - stageStart) % CALL_PERIOD == 0) {
                callName();
            }
            if (target instanceof Player hunted && (now - stageStart) % 10 == 0) {
                double gap = Math.max(0, at.distance(feet) - TOUCH);
                hunted.sendActionBar(Text.mm("<" + COLOR_TAG + ">☠ 저승사자가 당신의 이름을 부릅니다</" + COLOR_TAG
                        + "> <gray>" + AbilityInfo.formatNumber(Math.round(gap * 10) / 10.0) + "블록</gray>"));
                // Its heart races as the messenger closes in.
                float pitch = (float) Math.clamp(1.6 - gap * 0.08, 0.6, 1.6);
                hunted.playSound(hunted.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.0f,
                        pitch);
            }
            return true;
        }

        private boolean touches() {
            BoundingBox box = target.getBoundingBox();
            double dx = Math.max(Math.max(box.getMinX() - at.getX(), 0), at.getX() - box.getMaxX());
            double dz = Math.max(Math.max(box.getMinZ() - at.getZ(), 0), at.getZ() - box.getMaxZ());
            return dx * dx + dz * dz <= TOUCH * TOUCH && at.getY() <= box.getMaxY() && at.getY() + 2.0 >= box.getMinY();
        }

        /** It calls the target's name: the words hang over its hat and a whisper carries. */
        private void callName() {
            TextDisplay label = call;
            if (label != null && label.isValid()) {
                label.text(callText());
            }
            Fx.sound(at, Sound.ENTITY_VEX_AMBIENT, 1.0f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.PARTICLE_SOUL_ESCAPE, 1.0f, 0.6f);
        }

        private Component callText() {
            return Text.mm("<" + PALE_TAG + "><i>…<name>…</i></" + PALE_TAG + ">",
                    Placeholder.component("name", target.name()));
        }

        /** Two blue points of ghost fire under the hat. */
        private void eyes() {
            Vector front = facing(yaw).multiply(0.19);
            Vector side = new Vector(-front.getZ(), 0, front.getX()).normalize().multiply(0.08);
            Location eye = at.clone().add(front).add(0, 1.8, 0);
            Vfx.dot(eye.clone().add(side), Particle.DUST, EYE_DUST, false);
            Vfx.dot(eye.clone().subtract(side), Particle.DUST, EYE_DUST, false);
        }

        /** It reaches its target: the register's chains bind it and the messenger goes back down with its due. */
        private void seize() {
            Combat.hit(ctx(), owner, target, params().num("reaper_damage"));
            long bind = params().ticks("reaper_bind");
            if (bind > 0 && Combat.isTargetable(ctx(), owner, target)) {
                ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, bind);
                ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, bind);
                chain(bind);
            }
            Location body = Fx.body(target);
            Vfx.dot(body, Particle.FLASH, CRIMSON_RED, true);
            Vfx.burst(ctx(), body, CRIMSON, 0.7, Particle.SOUL, true);
            Vfx.helix(ctx(), target.getLocation().add(0, 0.05, 0), 0.6, target.getHeight(), 2, 2, 12, 0,
                    Particle.DUST_COLOR_TRANSITION, CRIMSON_FADE, true);
            Fx.sound(body, Sound.BLOCK_CHAIN_PLACE, 1.2f, 0.6f);
            Fx.sound(body, Sound.ENTITY_WITHER_SKELETON_HURT, 0.8f, 0.6f);
            Fx.sound(body, Sound.BLOCK_BELL_USE, 1.0f, 0.5f);
            TextDisplay label = call;
            if (label != null && label.isValid()) {
                label.text(Text.mm("<b><" + COLOR_TAG + ">명부에 올랐다</" + COLOR_TAG + "></b>"));
            }
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">☠ 저승사자가 <white><target></white>을(를) 붙잡았습니다</"
                    + COLOR_TAG + ">", Placeholder.component("target", target.name())));
            sink(true);
        }

        /** The chains of the register: two chains turning round the bound target's legs. */
        private void chain(long ticks) {
            if (!reserve(2)) {
                return;
            }
            List<ItemDisplay> chains = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ItemDisplay link = Displays.item(ctx(), owner, target.getLocation().add(0, 0.5 + i * 0.5, 0),
                        new ItemStack(CHAIN_ITEM), chainPose(i * 90f), ticks + 4);
                link.setTeleportDuration(1);
                chains.add(link);
                displays.add(link);
            }
            Loop.repeat(ctx(), owner, 1, 1, ticks, run -> {
                if (!target.isValid()) {
                    return false;
                }
                for (int i = 0; i < chains.size(); i++) {
                    ItemDisplay link = chains.get(i);
                    if (link.isValid()) {
                        Displays.moveTo(link, target.getLocation().add(0, 0.5 + i * 0.5, 0));
                        Displays.animate(link, chainPose(i * 90f + run * 25f), 1);
                    }
                }
                return true;
            }, () -> {
                for (ItemDisplay link : chains) {
                    if (link.isValid()) {
                        Displays.shrinkAway(ctx(), owner, link, 3);
                    }
                }
            });
        }

        /** It goes back into the ground (with its due, or empty-handed). */
        private void sink(boolean seized) {
            if (stage == ReaperStage.SINKING || stage == ReaperStage.DONE) {
                return;
            }
            stage = ReaperStage.SINKING;
            stageStart = ctx().currentTick();
            veil(false);
            Location deep = at.clone().subtract(0, RISE_DEPTH, 0);
            for (BlockDisplay part : body) {
                if (part.isValid()) {
                    part.setTeleportDuration((int) SINK_TICKS);
                    Displays.moveTo(part, deep);
                }
            }
            TextDisplay label = call;
            if (label != null && label.isValid()) {
                if (!seized) {
                    label.text(Text.mm("<gray><i>…</i></gray>"));
                }
                label.setTeleportDuration((int) SINK_TICKS);
                Displays.moveTo(label, deep.clone().add(0, 2.6, 0));
            }
            world.spawnParticle(Particle.SOUL, at.clone().add(0, 0.3, 0), Vfx.n(ctx(), 10), 0.3, 0.1, 0.3, 0.03, null,
                    false);
            Fx.sound(at, Sound.BLOCK_SOUL_SAND_BREAK, 0.8f, 0.5f);
            if (!seized) {
                Fx.sound(at, Sound.ENTITY_VEX_DEATH, 0.6f, 0.5f);
            }
        }

        private boolean sinking() {
            if (ctx().currentTick() - stageStart >= SINK_TICKS) {
                stage = ReaperStage.DONE;
                removeAll();
                return false;
            }
            return true;
        }

        /** Hides the reaper while its target is hidden from the king, so it gives nobody away. */
        private void veil(boolean hidden) {
            if (hidden == veiled) {
                return;
            }
            veiled = hidden;
            float range = hidden ? 0f : 2.0f;
            for (BlockDisplay part : body) {
                if (part.isValid()) {
                    part.setViewRange(range);
                }
            }
            if (call != null && call.isValid()) {
                call.setViewRange(range);
            }
        }

        private void place(Location spot) {
            for (BlockDisplay part : body) {
                Displays.moveTo(part, spot);
            }
            if (call != null) {
                Displays.moveTo(call, spot.clone().add(0, 2.6, 0));
            }
        }

        private void removeAll() {
            for (BlockDisplay part : body) {
                if (part.isValid()) {
                    part.remove();
                }
            }
            if (call != null && call.isValid()) {
                call.remove();
            }
        }

        void end() {
            if (stage != ReaperStage.DONE) {
                stage = ReaperStage.DONE;
                removeAll();
            }
        }
    }

    /** One block of a reaper, centred on its axis (y up from the ground); {@code outlined} parts glow crimson. */
    private record ReaperPart(Material material, float y, float sx, float sy, float sz, boolean outlined) {

        /** In order of importance: the robe, the face and the hat come first; the sash is the first to go. */
        static final ReaperPart[] ALL = {
                new ReaperPart(ROBE, 0.48f, 0.64f, 0.96f, 0.44f, true),
                new ReaperPart(ROBE, 1.26f, 0.52f, 0.62f, 0.34f, true),
                new ReaperPart(FACE, 1.77f, 0.36f, 0.38f, 0.34f, false),
                new ReaperPart(HAT, 1.98f, 0.98f, 0.035f, 0.98f, false),
                new ReaperPart(HAT, 2.13f, 0.34f, 0.3f, 0.34f, false),
                new ReaperPart(SASH, 0.98f, 0.55f, 0.07f, 0.37f, false),
        };

        Transformation pose(float yaw) {
            return Displays.transform(0, y, 0, sx, sy, sz, yaw, 0, 0);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code at} lowered straight down onto the first floor or water surface under it, however far below (unchanged
     * over the void). The ray starts a little above the feet, never inside the ground (see {@link Spots#snapToGround}).
     */
    private static Location settled(World world, Location at) {
        Location ground = at.clone();
        Location from = at.clone().add(0, 0.05, 0);
        double depth = from.getY() - world.getMinHeight();
        if (depth <= 0) {
            return ground;
        }
        RayTraceResult hit = world.rayTraceBlocks(from, new Vector(0, -1, 0), depth, FluidCollisionMode.ALWAYS, true);
        if (hit != null && hit.getHitPosition().getY() <= from.getY() - 1.0E-4) {
            ground.setY(hit.getHitPosition().getY());
        }
        return ground;
    }

    /**
     * {@code at} put down on the ground at most a step below it, with room for the mirror; {@code fallback} (a spot on
     * the ground) when there is no such ground there (a ledge's edge, a wall).
     */
    private static Location standOn(World world, Location at, Location fallback) {
        Location ground = at.clone();
        Spots.snapToGround(world, ground, MIRROR_SNAP);
        boolean onGround = world.rayTraceBlocks(ground.clone().add(0, 0.05, 0), new Vector(0, -1, 0), 0.1,
                FluidCollisionMode.NEVER, true) != null;
        return onGround && Spots.isFree(world, ground, 0.6, 2.0) ? ground : fallback.clone();
    }

    /** A chain lying across the legs, turned {@code spin} degrees around the vertical. */
    private static Transformation chainPose(float spin) {
        return Displays.centered(0, 0, 0, 0.9f, 0.9f, 0.9f, spin, 90, 0);
    }

    /** The horizontal unit vector a Minecraft yaw faces (0 is +Z, 90 is -X). */
    private static Vector facing(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians));
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}, 0 if it has none. */
    private static float yawOf(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    private static float wrap(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped > 180f) {
            wrapped -= 360f;
        } else if (wrapped < -180f) {
            wrapped += 360f;
        }
        return wrapped;
    }

    private static double sq(double value) {
        return value * value;
    }

    /** Reserves room for {@code count} more displays under {@link #MAX_DISPLAYS}; false when there is none. */
    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }
}
