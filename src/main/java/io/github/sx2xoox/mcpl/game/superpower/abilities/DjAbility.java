package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 디제이 — a DJ who fights to the beat. A beat runs all match long (only the DJ hears the metronome); melee hits on
 * the beat hit harder and skills used on the beat are boosted (a perfect drop also comes back sooner), while a skill
 * that misses the beat waits for the next one and goes off plain. The active drops the bass in a neon shockwave; the
 * ultimate throws a party: a dance floor under a disco ball where enemies are made to dance on every beat (and hurt on
 * the drop) while the DJ's side gets a burst of speed, and every skill of the DJ is a perfect.
 *
 * <p>Unlike the 음유시인, whose songs are buffs and debuffs, the DJ is about timing: the same skill is weaker, stronger
 * or late depending on when it is pressed, and the judgement allows for the DJ's ping.
 *
 * <p>Look: neon magenta, cyan and purple on black. Every beat sends a colored pulse ring from the DJ's feet. A bass drop
 * slams two speaker boxes down beside the DJ, springs a ring of equalizer bars up around it and rolls out a double
 * neon shockwave (a sonic boom on a perfect), with a "PERFECT!" / "GREAT!" / "MISS" pop over the DJ. The party has a
 * spinning mirror ball with colored light beams sweeping the floor, four speakers pumping at the edge, floor tiles
 * flashing in new colors on every beat and an EDM loop of kicks, snares, hats, a bass line and a big drop every bar.
 */
public final class DjAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("dj", "디제이", Material.JUKEBOX)
            .color(TextColor.color(0xFF2BD6))
            .difficulty(3)
            .passive("비트",
                    "{beat_interval}초마다 박자가 흐릅니다(발밑에서 박자가 퍼지고, 메트로놈은 나에게만 들립니다). 박자에 맞춰 "
                            + "근접 공격하면 피해가 {beat_melee}% 늘어납니다. 스킬도 박자에 맞춰 쓰면 강화됩니다: 퍼펙트(±"
                            + "{perfect_window}초) {perfect_bonus}%, 굿(±{good_window}초) {good_bonus}%. 판정은 내 핑만큼 "
                            + "보정됩니다.")
            .active("베이스 드롭",
                    "베이스를 떨어뜨려 반경 {drop_radius}블록으로 충격파를 퍼뜨립니다. 닿은 적은 {drop_damage} 피해를 입고 "
                            + "밀려나며 튕겨 오릅니다. 박자에 맞추면 그 자리에서 터지며 피해와 미는 힘이 강화되고, 퍼펙트면 "
                            + "쿨타임이 {perfect_refund}초 줄어듭니다. 박자를 놓치면 다음 박자에 강화 없이 터집니다.",
                    8)
            .ultimate("파티 타임",
                    "{party_duration}초간 내 자리에 반경 {party_radius}블록의 댄스 플로어를 깔고 디스코볼을 띄웁니다. "
                            + "플로어 위의 적은 박자마다 억지로 춤추며 휘청이고 {party_damage} 피해를, 4박마다 오는 드롭에는 "
                            + "{party_drop_damage} 피해를 입습니다. 플로어 위의 나와 아군은 신속 II를 얻고, 파티 동안 내 "
                            + "스킬과 근접 공격은 언제나 퍼펙트 판정입니다.")
            .param("beat_interval", 0.5)
            .param("perfect_window", 0.05)
            .param("good_window", 0.1)
            .param("perfect_bonus", 50)
            .param("good_bonus", 25)
            .param("beat_melee", 20)
            .param("drop_radius", 6)
            .param("drop_damage", 4)
            .param("perfect_refund", 3)
            .param("party_duration", 6)
            .param("party_radius", 8)
            .param("party_damage", 0.5)
            .param("party_drop_damage", 1.5)
            .build();

    private static final String MAGENTA_TAG = "#FF2BD6";
    private static final String CYAN_TAG = "#2BF5FF";
    private static final String PURPLE_TAG = "#9A4DFF";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** White-hot core, neon magenta body, deep purple edge; neon cyan as the second color. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xFF2BD6, 0x3A0D6B);
    private static final Color MAGENTA = Color.fromRGB(0xFF2BD6);
    private static final Color CYAN = Color.fromRGB(0x2BF5FF);
    private static final Color PURPLE = Color.fromRGB(0x9A4DFF);
    private static final Color LIME = Color.fromRGB(0xB6FF3D);
    private static final Color[] NEON = {MAGENTA, CYAN, PURPLE, LIME};
    private static final Particle.DustTransition NEON_GLOW = PALETTE.glow(1.2f);
    private static final Particle.DustTransition CYAN_FADE = new Particle.DustTransition(CYAN, PURPLE, 1.2f);

    /** Block types of the visuals; block data is created on use (it needs a running server). */
    private static final Material SPEAKER = Material.JUKEBOX;
    private static final Material SPEAKER_CONE = Material.BLACK_CONCRETE;
    private static final Material[] BAR_COLORS = {Material.MAGENTA_STAINED_GLASS, Material.CYAN_STAINED_GLASS,
            Material.PURPLE_STAINED_GLASS, Material.LIME_STAINED_GLASS};
    private static final Material[] TILE_COLORS = {Material.MAGENTA_STAINED_GLASS, Material.CYAN_STAINED_GLASS,
            Material.PURPLE_STAINED_GLASS, Material.LIME_STAINED_GLASS, Material.YELLOW_STAINED_GLASS,
            Material.LIGHT_BLUE_STAINED_GLASS};
    private static final Material MIRROR = Material.LIGHT_GRAY_STAINED_GLASS;
    private static final Material MIRROR_CORE = Material.SEA_LANTERN;

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 1.8;
    private static final long MAX_LAG_TICKS = 6;
    private static final double MILLIS_PER_TICK = 50.0;

    // passive
    private static final long STATUS_TICKS = 24;
    /** The metronome shows in the action bar from this long (seconds) before the active is ready. */
    private static final double METRONOME_LEAD = 1.0;
    private static final double PULSE_RADIUS = 0.75;

    // active: bass drop
    private static final int WAVE_TICKS = 5;
    private static final double DROP_PUSH = 0.85;
    private static final double DROP_LIFT = 0.45;
    private static final int EQ_BARS = 8;
    private static final double EQ_RADIUS = 1.7;
    private static final float SPEAKER_SIZE = 0.7f;
    private static final int JUDGE_POP_TICKS = 16;

    // ultimate: party
    private static final double BALL_HEIGHT = 3.8;
    private static final float BALL_SIZE = 0.8f;
    private static final int FLOOR_TILES = 8;
    private static final float TILE_SIZE = 1.4f;
    private static final int BEAMS = 4;
    private static final double STEP_PUSH = 0.38;
    private static final double HOP_LIFT = 0.42;
    /** Bass line of the party loop, one note per beat (note-block notes, 0 = F#3). */
    private static final int[] BASS_LINE = {6, 6, 9, 4, 6, 6, 11, 9};
    /** Synth arpeggio over it, one note per half beat. */
    private static final int[] ARPEGGIO = {18, 22, 24, 22, 18, 21, 24, 21};

    /** The timing of a skill or a hit against the beat. */
    enum Judgement {
        PERFECT, GOOD, MISS
    }

    /** Every display this ability currently has alive, for the budget. */
    private final List<Display> displays = new ArrayList<>();
    private double nextCooldownFactor = 1.0;
    /** Every skill and hit is a perfect until this tick (the party). */
    private long partyUntil = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
    private @Nullable String status;
    private long statusUntil = Long.MIN_VALUE / 2;
    /** The owner's last melee hit was on the beat (the tick of that hit). */
    private long beatHitTick = Long.MIN_VALUE / 2;
    private @Nullable UUID beatHitVictim;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        displays.clear();
        status = null;
    }

    @Override
    public double cooldownMultiplier() {
        return nextCooldownFactor;
    }

    // ------------------------------------------------------------------ the beat

    /** Ticks per beat (at least 2). */
    private long beatTicks() {
        return Math.max(2, params().ticks("beat_interval"));
    }

    /** True on the first tick of every beat. */
    private boolean onBeat(long now) {
        return Math.floorMod(now, beatTicks()) == 0;
    }

    /** Judges a press made now: allows for the owner's ping (the beat reaches its ears and the press comes back). */
    Judgement judge() {
        long now = ctx().currentTick();
        if (now < partyUntil) {
            return Judgement.PERFECT;
        }
        long beat = beatTicks();
        long judged = now - lagTicks(player());
        long phase = Math.floorMod(judged, beat);
        long off = Math.min(phase, beat - phase);
        if (off <= params().ticks("perfect_window")) {
            return Judgement.PERFECT;
        }
        if (off <= params().ticks("good_window")) {
            return Judgement.GOOD;
        }
        return Judgement.MISS;
    }

    private double bonus(Judgement judgement) {
        return switch (judgement) {
            case PERFECT -> Math.max(0, params().num("perfect_bonus")) / 100.0;
            case GOOD -> Math.max(0, params().num("good_bonus")) / 100.0;
            case MISS -> 0;
        };
    }

    /** Ticks a player's input reaches the server late after a sound it reacted to: one round trip, capped. */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, Math.round(Math.max(0, player.getPing()) / MILLIS_PER_TICK));
    }

    /** Every beat: a pulse ring at the DJ's feet for everyone, the metronome for the DJ alone. */
    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        if (!onBeat(now)) {
            return;
        }
        long index = Math.floorDiv(now, beatTicks());
        Color color = NEON[Math.floorMod(index, NEON.length)];
        boolean downbeat = Math.floorMod(index, 4) == 0;
        Location feet = owner.getLocation().add(0, 0.08, 0);
        Vfx.ring(ctx(), feet, downbeat ? PULSE_RADIUS * 1.3 : PULSE_RADIUS, downbeat ? 16 : 10, index * 0.4,
                Particle.DUST, new Particle.DustOptions(color, downbeat ? 1.1f : 0.8f), false);
        owner.playSound(owner, downbeat ? Sound.BLOCK_NOTE_BLOCK_BASEDRUM : Sound.BLOCK_NOTE_BLOCK_HAT,
                SoundCategory.PLAYERS, downbeat ? 0.6f : 0.35f, downbeat ? 0.7f : 1.4f);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            bar.append(i == Math.floorMod(index, 4) ? "<" + MAGENTA_TAG + ">◆</" + MAGENTA_TAG + ">"
                    : "<dark_gray>◇</dark_gray>");
            if (i < 3) {
                bar.append(' ');
            }
        }
        boolean talking = status != null && now < statusUntil;
        boolean partying = now < partyUntil;
        // Only when it matters (a skill about to be ready, the party, a fresh judgement), so the action bar stays
        // free for everything else the rest of the time.
        if (!talking && !partying && ctx().cooldownRemaining(owner) > METRONOME_LEAD) {
            return;
        }
        String extra = talking ? " " + status : "";
        String party = partying ? " <" + CYAN_TAG + ">🪩 PARTY</" + CYAN_TAG + ">" : "";
        owner.sendActionBar(Text.mm("<" + PURPLE_TAG + ">♪</" + PURPLE_TAG + "> " + bar + party + extra));
    }

    private void showStatus(String text) {
        status = text;
        statusUntil = ctx().currentTick() + STATUS_TICKS;
        player().sendActionBar(Text.mm("<" + PURPLE_TAG + ">♪</" + PURPLE_TAG + "> " + text));
    }

    private static String label(Judgement judgement) {
        return switch (judgement) {
            case PERFECT -> "<b><gradient:" + MAGENTA_TAG + ":" + CYAN_TAG + ">PERFECT!</gradient></b>";
            case GOOD -> "<b><" + CYAN_TAG + ">GREAT!</" + CYAN_TAG + "></b>";
            case MISS -> "<gray>MISS</gray>";
        };
    }

    // ------------------------------------------------------------------ passive: hits on the beat

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnMelee(event, victim)) {
            return;
        }
        if (judge() == Judgement.MISS) {
            return;
        }
        double bonus = Math.max(0, params().num("beat_melee")) / 100.0;
        event.setDamage(event.getDamage() * (1 + bonus));
        beatHitTick = ctx().currentTick();
        beatHitVictim = victim.getUniqueId();
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnMelee(event, victim) || beatHitTick != ctx().currentTick()
                || !victim.getUniqueId().equals(beatHitVictim)) {
            return;
        }
        Location body = Fx.body(victim);
        Color color = NEON[ThreadLocalRandom.current().nextInt(NEON.length)];
        Vfx.ring(ctx(), body, 0.6, 12, 0, Particle.DUST, new Particle.DustOptions(color, 1.0f), false);
        note(body.clone().add(0, 0.9, 0), ThreadLocalRandom.current().nextInt(25));
        Vfx.dot(body, Particle.FLASH, color, false);
        Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_SNARE, 0.9f, 1.0f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, pitch(18));
        showStatus("<" + MAGENTA_TAG + ">BEAT HIT</" + MAGENTA_TAG + "> <gray>+"
                + AbilityInfo.formatNumber(params().num("beat_melee")) + "%</gray>");
    }

    /** The owner's own melee swing at an enemy (not ability damage, projectiles or summon bites). */
    private boolean isOwnMelee(EntityDamageByEntityEvent event, LivingEntity victim) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && event.getCause() == DamageCause.ENTITY_ATTACK && ctx().isEnemy(player(), victim);
    }

    // ------------------------------------------------------------------ active: bass drop

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Judgement judgement = judge();
        long cooldown = params().ticks(AbilityInfo.COOLDOWN);
        if (judgement == Judgement.PERFECT && cooldown > 0) {
            nextCooldownFactor = Math.max(0, cooldown - params().ticks("perfect_refund")) / (double) cooldown;
        } else {
            nextCooldownFactor = 1.0;
        }
        if (judgement != Judgement.MISS) {
            drop(owner, judgement);
            return ActivationResult.success();
        }
        // Off the beat: the bass waits for the next one and drops plain.
        long delay = Math.floorMod(-ctx().currentTick(), beatTicks());
        judgePop(owner, Judgement.MISS);
        showStatus(label(Judgement.MISS) + " <gray>다음 박자에 드롭</gray>");
        riser(owner, delay);
        if (delay <= 0) {
            drop(owner, Judgement.MISS);
        } else {
            ctx().runLater(owner, delay, () -> drop(owner, Judgement.MISS));
        }
        return ActivationResult.success();
    }

    /** The build-up before a late drop: a rising sweep to the next beat. */
    private void riser(Player owner, long delay) {
        Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, pitch(8));
        Loop.repeat(ctx(), owner, 1, 1, Math.max(0, delay - 1), run -> {
            Location chest = owner.getLocation().add(0, 1.1, 0);
            double radius = 1.2 - 0.15 * run;
            Vfx.ring(ctx(), chest, Math.max(0.3, radius), 8, run * 0.6, Particle.DUST,
                    new Particle.DustOptions(NEON[(int) run % NEON.length], 0.8f), false);
            owner.playSound(owner, Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.PLAYERS, 0.4f, pitch(10 + 2 * (int) run));
            return true;
        });
    }

    /** The drop: speakers slam down, equalizer bars spring up and a neon shockwave rolls out. */
    private void drop(Player owner, Judgement judgement) {
        if (!owner.isValid() || owner.isDead()) {
            return;
        }
        Location center = Displays.unrotated(owner.getLocation());
        double radius = Math.max(0.5, params().num("drop_radius"));
        double power = 1 + bonus(judgement);
        double damage = params().num("drop_damage") * power;
        if (judgement != Judgement.MISS) {
            judgePop(owner, judgement);
            showStatus(label(judgement) + " <gray>베이스 드롭 ×" + AbilityInfo.formatNumber(power) + "</gray>");
        }
        speakers(owner, center, judgement);
        equalizer(owner, center, judgement);
        dropFx(center, judgement);
        Location chest = center.clone().add(0, owner.getHeight() * 0.55, 0);
        Set<UUID> struck = new HashSet<>();
        int[] hits = {0};
        Loop.repeat(ctx(), owner, 0, 1, WAVE_TICKS, run -> {
            double reach = radius * (run + 1) / WAVE_TICKS;
            Location ground = center.clone().add(0, 0.12, 0);
            Vfx.ring(ctx(), ground, reach, (int) Math.max(12, reach * 6), run * 0.3, Particle.DUST_COLOR_TRANSITION,
                    NEON_GLOW, true);
            Vfx.ring(ctx(), ground.clone().add(0, 0.45, 0), Math.max(0.3, reach - 0.35), (int) Math.max(10, reach * 5),
                    run * 0.3 + 0.2, Particle.DUST_COLOR_TRANSITION, CYAN_FADE, true);
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, reach)) {
                if (struck.contains(enemy.getUniqueId()) || !Spots.clearPath(chest, Fx.body(enemy))) {
                    continue;
                }
                struck.add(enemy.getUniqueId());
                Combat.hit(ctx(), owner, enemy, damage);
                hits[0]++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    away = Motion.horizontalFacing(owner.getLocation());
                }
                Motion.push(enemy, away.multiply(DROP_PUSH * power).setY(DROP_LIFT * Math.sqrt(power)), MAX_SPEED);
                if (enemy instanceof Player victim && !owner.canSee(victim)) {
                    continue;
                }
                Location body = Fx.body(enemy);
                Vfx.dot(body, Particle.FLASH, NEON[hits[0] % NEON.length], false);
                note(body.clone().add(0, 0.9, 0), ThreadLocalRandom.current().nextInt(25));
                Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, pitch(2 + hits[0]));
            }
            return true;
        }, () -> {
            if (hits[0] > 0 && owner.isOnline()) {
                showStatus((judgement == Judgement.MISS ? "" : label(judgement) + " ") + "<white>" + hits[0]
                        + "명 적중</white>");
            }
        });
    }

    /** Two speaker boxes slam down beside the DJ, pump once and fade. */
    private void speakers(Player owner, Location center, Judgement judgement) {
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        if (facing.lengthSquared() < 1.0E-6) {
            facing = new Vector(0, 0, 1);
        }
        float yaw = yawOf(facing);
        Vector side = new Vector(-facing.getZ(), 0, facing.getX()).multiply(1.25);
        for (int s = -1; s <= 1; s += 2) {
            if (!roomFor(2)) {
                return;
            }
            Location spot = center.clone().add(side.clone().multiply(s));
            Location high = spot.clone().add(0, 2.2, 0);
            float size = judgement == Judgement.PERFECT ? SPEAKER_SIZE * 1.2f : SPEAKER_SIZE;
            BlockDisplay box = remember(Displays.block(ctx(), owner, high, SPEAKER.createBlockData(),
                    Displays.transform(0, size / 2, 0, size, size, size, yaw, 0, 0), 26));
            // The woofer: a dark cone on the face that points the way the DJ looks.
            Vector3f front = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                    .transform(new Vector3f(0, 0, size / 2 + 0.01f));
            BlockDisplay cone = remember(Displays.block(ctx(), owner, high, SPEAKER_CONE.createBlockData(),
                    Displays.transform(front.x(), size / 2, front.z(), size * 0.6f, size * 0.6f, 0.04f, yaw, 0, 0), 26));
            for (BlockDisplay part : List.of(box, cone)) {
                part.setTeleportDuration(3);
                ctx().runLater(owner, 1, () -> Displays.moveTo(part, spot));
                ctx().runLater(owner, 18, () -> {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 6);
                    }
                });
            }
            ctx().runLater(owner, 5, () -> {
                Transformation pump = Displays.transform(0, size * 0.6f, 0, size * 1.25f, size * 1.25f, size * 1.25f,
                        yaw, 0, 0);
                Displays.animate(box, pump, 2);
                Displays.animate(cone, Displays.transform(front.x() * 1.25f, size * 0.6f, front.z() * 1.25f,
                        size * 0.75f, size * 0.75f, 0.04f, yaw, 0, 0), 2);
                Vfx.cloud(ctx(), spot.clone().add(0, size / 2, 0), Particle.NOTE, 2, 0.3, 1, null, false);
            });
            ctx().runLater(owner, 8, () -> {
                Displays.animate(box, Displays.transform(0, size / 2, 0, size, size, size, yaw, 0, 0), 3);
                Displays.animate(cone, Displays.transform(front.x(), size / 2, front.z(), size * 0.6f, size * 0.6f,
                        0.04f, yaw, 0, 0), 3);
            });
        }
    }

    /** A ring of equalizer bars springs up around the DJ to random heights, then sinks. */
    private void equalizer(Player owner, Location center, Judgement judgement) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        float boost = judgement == Judgement.PERFECT ? 1.4f : judgement == Judgement.GOOD ? 1.2f : 1f;
        for (int i = 0; i < EQ_BARS && roomFor(1); i++) {
            double angle = i * TAU / EQ_BARS;
            float yaw = (float) Math.toDegrees(angle);
            Location base = center.clone().add(Math.cos(angle) * EQ_RADIUS, 0.02, Math.sin(angle) * EQ_RADIUS);
            BlockDisplay bar = remember(Displays.block(ctx(), owner, base,
                    BAR_COLORS[i % BAR_COLORS.length].createBlockData(), barPose(0.01f, yaw), 22));
            float height = (0.6f + random.nextFloat() * 1.6f) * boost;
            Displays.animateNextTick(ctx(), owner, bar, barPose(height, yaw), 3);
            ctx().runLater(owner, 5, () -> Displays.animate(bar, barPose(height * 0.45f, yaw), 4));
            ctx().runLater(owner, 10, () -> Displays.animate(bar, barPose(height * 0.8f, yaw), 3));
            ctx().runLater(owner, 15, () -> {
                if (bar.isValid()) {
                    Displays.shrinkAway(ctx(), owner, bar, 5);
                }
            });
        }
    }

    /** A thin bar standing on the ground, {@code height} tall, turned to face out of the ring. */
    private static Transformation barPose(float height, float yaw) {
        return Displays.transform(0, height / 2, 0, 0.22f, height, 0.22f, yaw, 0, 0);
    }

    private void dropFx(Location center, Judgement judgement) {
        World world = center.getWorld();
        Location chest = center.clone().add(0, 1.0, 0);
        Vfx.dot(chest, Particle.FLASH, judgement == Judgement.MISS ? PURPLE : MAGENTA, true);
        if (judgement == Judgement.PERFECT) {
            Vfx.dot(chest, Particle.SONIC_BOOM, null, true);
            Fx.sound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 1.6f);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < Vfx.n(ctx(), 10); i++) {
            note(chest.clone().add(random.nextDouble(-1, 1), random.nextDouble(0, 1.2), random.nextDouble(-1, 1)),
                    random.nextInt(25));
        }
        world.spawnParticle(Particle.END_ROD, chest, Vfx.n(ctx(), 10), 0.4, 0.4, 0.4, 0.12, null, true);
        Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.6f, 0.5f);
        Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BASS, 1.4f, 0.5f);
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.8f);
        Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 0.6f + Fx.jitter(0.05f));
        if (judgement != Judgement.MISS) {
            Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, pitch(judgement == Judgement.PERFECT ? 24 : 18));
        }
    }

    /** "PERFECT!" / "GREAT!" / "MISS" popping up over the DJ's head. */
    private void judgePop(Player owner, Judgement judgement) {
        if (!roomFor(1)) {
            return;
        }
        Location over = owner.getLocation().add(0, owner.getHeight() + 0.55, 0);
        TextDisplay pop = remember(Displays.text(ctx(), owner, over, Text.mm(label(judgement)), 0.4f,
                JUDGE_POP_TICKS));
        float scale = judgement == Judgement.PERFECT ? 1.5f : judgement == Judgement.GOOD ? 1.2f : 0.9f;
        Displays.animateNextTick(ctx(), owner, pop, Displays.centered(0, 0.35f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, JUDGE_POP_TICKS - 5, () -> {
            if (pop.isValid()) {
                Displays.shrinkAway(ctx(), owner, pop, 4);
            }
        });
    }

    // ------------------------------------------------------------------ ultimate: party time

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location center = Displays.unrotated(owner.getLocation());
        Location probe = center.clone().add(0, 0.5, 0);
        if (!Spots.hasGroundBelow(owner.getWorld(), probe)) {
            return ActivationResult.fail("파티를 열 바닥이 없습니다");
        }
        Spots.snapToGround(owner.getWorld(), probe, 4);
        long duration = Math.max(1, params().ticks("party_duration"));
        partyUntil = ctx().currentTick() + duration;
        new Party(owner, probe, duration).start();
        return ActivationResult.success();
    }

    /**
     * The party: a dance floor around where the DJ stood, a mirror ball over it, four speakers at its edge. Every beat
     * the enemies on the floor are made to dance (a step this way, that way, in and a hop on the drop) and hurt, and
     * the DJ's side on it is hastened.
     */
    private final class Party implements LongPredicate {

        private final Player owner;
        private final Location center;
        private final World world;
        private final long duration;
        private final double radius;
        private final List<BlockDisplay> ball = new ArrayList<>();
        private final List<BlockDisplay> speakers = new ArrayList<>();
        private final List<BlockDisplay> tiles = new ArrayList<>();
        private final List<Float> speakerYaws = new ArrayList<>();
        private int beats;
        private int hits;

        Party(Player owner, Location center, long duration) {
            this.owner = owner;
            this.center = Displays.unrotated(center);
            this.world = center.getWorld();
            this.duration = duration;
            this.radius = Math.max(1, params().num("party_radius"));
        }

        void start() {
            long lifetime = duration + 20;
            Location top = center.clone().add(0, BALL_HEIGHT, 0);
            Material[] ballParts = {MIRROR_CORE, MIRROR};
            for (int i = 0; i < ballParts.length; i++) {
                float size = i == 0 ? BALL_SIZE * 0.7f : BALL_SIZE;
                BlockDisplay piece = remember(Displays.block(ctx(), owner, top, ballParts[i].createBlockData(),
                        cubePose(0.05f, new Quaternionf()), lifetime));
                piece.setViewRange(2.5f);
                ball.add(piece);
                Displays.animateNextTick(ctx(), owner, piece, cubePose(size, ballTurn(0, i)), 6);
            }
            if (!ball.isEmpty()) {
                Displays.outline(ball.getLast(), CYAN);
            }
            for (int i = 0; i < 4 && roomFor(1); i++) {
                double angle = i * TAU / 4 + Math.PI / 4;
                Vector out = new Vector(Math.cos(angle), 0, Math.sin(angle));
                Location spot = center.clone().add(out.clone().multiply(radius - 0.8));
                Spots.snapToGround(world, spot.add(0, 1.0, 0), 3);
                // Each speaker faces the middle of the floor.
                float yaw = yawOf(out.clone().multiply(-1));
                BlockDisplay speaker = remember(Displays.block(ctx(), owner, spot, SPEAKER.createBlockData(),
                        speakerPose(0.05f, yaw), lifetime));
                speakers.add(speaker);
                speakerYaws.add(yaw);
                Displays.animateNextTick(ctx(), owner, speaker, speakerPose(1.0f, yaw), 6);
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < FLOOR_TILES && roomFor(1); i++) {
                BlockDisplay tile = remember(Displays.block(ctx(), owner, tileSpot(random),
                        TILE_COLORS[i % TILE_COLORS.length].createBlockData(), tilePose(TILE_SIZE), lifetime));
                tile.setTeleportDuration(0);
                tiles.add(tile);
            }
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.1, 0), 0.5, radius, 8, Particle.DUST_COLOR_TRANSITION,
                    NEON_GLOW, true);
            Vfx.cloud(ctx(), top, Particle.END_ROD, 30, 0.6, 0.15, null, true);
            Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.6f);
            Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.2f, 0.7f);
            Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f, pitch(18));
            owner.sendActionBar(Text.mm("<b><gradient:" + MAGENTA_TAG + ":" + CYAN_TAG + ">🪩 PARTY TIME!</gradient></b>"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::end);
        }

        private Location tileSpot(ThreadLocalRandom random) {
            Location spot = Vfx.scatter(center, Math.max(0.5, radius - 1.2));
            spot.setX(Math.floor(spot.getX()) + 0.5);
            spot.setZ(Math.floor(spot.getZ()) + 0.5);
            spot.add(0, 1.0, 0);
            Spots.snapToGround(world, spot, 3);
            return spot.add(0, 0.015, 0);
        }

        @Override
        public boolean test(long tick) {
            if (!owner.getWorld().equals(world)) {
                return false;
            }
            long now = ctx().currentTick();
            spinBall(tick);
            beams(tick);
            long half = beatTicks() / 2;
            if (half > 0 && Math.floorMod(now, beatTicks()) == half) {
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.6f);
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BIT, 0.5f, pitch(ARPEGGIO[(beats * 2 + 1) % ARPEGGIO.length]));
            }
            if (onBeat(now)) {
                beat();
            }
            return true;
        }

        private void spinBall(long tick) {
            if (tick % 2 != 0) {
                return;
            }
            for (int i = 0; i < ball.size(); i++) {
                BlockDisplay piece = ball.get(i);
                float size = i == 0 ? BALL_SIZE * 0.7f : BALL_SIZE;
                Displays.animate(piece, cubePose(size, ballTurn(tick + 2, i)), 2);
            }
        }

        private Quaternionf ballTurn(long tick, int part) {
            float spin = (float) Math.toRadians(tick * 9.0 * (part == 0 ? -1 : 1));
            return new Quaternionf().rotateY(spin).rotateX((float) Math.toRadians(35.26))
                    .rotateZ((float) Math.toRadians(45));
        }

        /** Colored light beams from the mirror ball sweeping the floor. */
        private void beams(long tick) {
            if (tick % 2 != 0) {
                return;
            }
            Location top = center.clone().add(0, BALL_HEIGHT, 0);
            for (int i = 0; i < BEAMS; i++) {
                double angle = tick * 0.09 + i * TAU / BEAMS;
                double reach = radius * (0.45 + 0.35 * Math.sin(tick * 0.05 + i));
                Location spot = center.clone().add(Math.cos(angle) * reach, 0.1, Math.sin(angle) * reach);
                Particle.DustOptions dust = new Particle.DustOptions(NEON[i % NEON.length], 0.7f);
                int points = Vfx.n(ctx(), 6);
                Vector delta = spot.toVector().subtract(top.toVector());
                for (int k = 1; k <= points; k++) {
                    Vfx.dot(top.clone().add(delta.clone().multiply(k / (double) points)), Particle.DUST, dust, true);
                }
                Vfx.ring(ctx(), spot, 0.45, 6, tick * 0.3, Particle.DUST, dust, true);
            }
        }

        /** One beat of the party: music, flashing tiles, pumping speakers, the dance and the drop. */
        private void beat() {
            int index = beats++;
            boolean drop = index % 4 == 3;
            music(index, drop);
            flashTiles();
            pumpSpeakers(drop);
            Vfx.ring(ctx(), center.clone().add(0, 0.1, 0), radius, (int) Math.min(48, Math.max(16, radius * 4)),
                    index * 0.2, Particle.DUST, new Particle.DustOptions(NEON[index % NEON.length], 1.3f), true);
            double damage = params().num(drop ? "party_drop_damage" : "party_damage");
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                dance(enemy, index);
            }
            List<LivingEntity> crew = new ArrayList<>(ctx().alliesNear(owner, center, radius));
            if (owner.getLocation().distance(center) <= radius) {
                crew.add(owner);
            }
            for (LivingEntity member : crew) {
                member.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                        (int) Math.min(Integer.MAX_VALUE, beatTicks() + 10), 1, false, true, true));
                if (drop) {
                    note(member.getLocation().add(0, member.getHeight() + 0.4, 0), 18);
                }
            }
            if (drop) {
                Location top = center.clone().add(0, BALL_HEIGHT, 0);
                Vfx.dot(top, Particle.FLASH, NEON[(index / 4) % NEON.length], true);
                world.spawnParticle(Particle.FIREWORK, top, Vfx.n(ctx(), 20), 0.3, 0.3, 0.3, 0.2, null, true);
                Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.15, 0), 0.5, radius, 5,
                        Particle.DUST_COLOR_TRANSITION, NEON_GLOW, true);
            }
        }

        /**
         * One dance step, the same for everyone on that beat: a step to the left, to the right, a step towards the
         * middle of the floor, and a hop on the drop.
         */
        private void dance(LivingEntity enemy, int index) {
            Vector in = Motion.horizontal(center.toVector().subtract(enemy.getLocation().toVector()));
            if (in.lengthSquared() < 1.0E-6) {
                in = new Vector(1, 0, 0);
            }
            Vector side = new Vector(-in.getZ(), 0, in.getX());
            Vector step = switch (index % 4) {
                case 0 -> side.multiply(STEP_PUSH).setY(0.12);
                case 1 -> side.multiply(-STEP_PUSH).setY(0.12);
                case 2 -> in.multiply(STEP_PUSH * 0.8).setY(0.12);
                default -> in.multiply(0.1).setY(HOP_LIFT);
            };
            Motion.push(enemy, step, MAX_SPEED);
            if (enemy instanceof Player victim && !owner.canSee(victim)) {
                return;
            }
            Location feet = enemy.getLocation().add(0, 0.1, 0);
            Color color = NEON[index % NEON.length];
            Vfx.ring(ctx(), feet, 0.55, 10, index * 0.5, Particle.DUST, new Particle.DustOptions(color, 1.0f), false);
            note(enemy.getLocation().add(0, enemy.getHeight() + 0.4, 0), (index * 5) % 25);
            if (index % 4 == 3) {
                Vfx.cloud(ctx(), Fx.body(enemy), Particle.END_ROD, 3, 0.3, 0.05, null, false);
            }
        }

        private void music(int index, boolean drop) {
            Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.4f, 0.6f);
            if (index % 2 == 1) {
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_SNARE, 1.0f, 1.0f);
            }
            int bass = BASS_LINE[index % BASS_LINE.length];
            Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BASS, 1.3f, pitch(bass));
            Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, pitch(ARPEGGIO[(index * 2) % ARPEGGIO.length]));
            if (drop) {
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, pitch(bass + 12));
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, pitch(bass + 16));
                Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 0.8f);
            }
        }

        /** Every beat the tiles jump to new spots in new colors. */
        private void flashTiles() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (BlockDisplay tile : tiles) {
                if (!tile.isValid()) {
                    continue;
                }
                Displays.moveTo(tile, tileSpot(random));
                tile.setBlock(TILE_COLORS[random.nextInt(TILE_COLORS.length)].createBlockData());
            }
        }

        private void pumpSpeakers(boolean drop) {
            for (int i = 0; i < speakers.size(); i++) {
                BlockDisplay speaker = speakers.get(i);
                float yaw = speakerYaws.get(i);
                float size = drop ? 1.35f : 1.15f;
                Displays.animate(speaker, speakerPose(size, yaw), 1);
                ctx().runLater(owner, 3, () -> Displays.animate(speaker, speakerPose(1.0f, yaw), 4));
                Location top = speaker.getLocation().add(0, size + 0.2, 0);
                Vfx.cloud(ctx(), top, Particle.NOTE, drop ? 3 : 1, 0.25, 1, null, false);
            }
        }

        /** The party is over: the ball and the speakers fade, a last burst of confetti. */
        void end() {
            partyUntil = Math.min(partyUntil, ctx().currentTick());
            for (List<BlockDisplay> group : List.of(ball, speakers, tiles)) {
                for (BlockDisplay piece : group) {
                    if (piece.isValid()) {
                        Displays.shrinkAway(ctx(), owner, piece, 6);
                    }
                }
            }
            Location top = center.clone().add(0, BALL_HEIGHT, 0);
            world.spawnParticle(Particle.FIREWORK, top, Vfx.n(ctx(), 40), 0.4, 0.4, 0.4, 0.25, null, true);
            Vfx.sphere(ctx(), top, 1.4, 30, Particle.DUST_COLOR_TRANSITION, NEON_GLOW, true);
            Fx.sound(top, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.4f, 1.0f);
            Fx.sound(top, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.2f, 1.2f);
            Fx.sound(top, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.6f);
            if (owner.isOnline()) {
                showStatus("<" + CYAN_TAG + ">🪩 파티 끝</" + CYAN_TAG + "> <gray>" + hits + "번 춤추게 했습니다</gray>");
            }
        }

        private Transformation speakerPose(float size, float yaw) {
            return Displays.transform(0, size / 2, 0, size, size, size, yaw, 0, 0);
        }

        private Transformation tilePose(float size) {
            return Displays.transform(0, 0.02f, 0, size, 0.04f, size, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A colored note particle (count 0 makes the offset the note color, 0..24). */
    private static void note(Location at, int color) {
        at.getWorld().spawnParticle(Particle.NOTE, at.getX(), at.getY(), at.getZ(), 0, Math.clamp(color, 0, 24) / 24.0,
                0, 0, 1);
    }

    /** Note-block pitch for a note number (0 = F#3 ... 24 = F#5). */
    private static float pitch(int note) {
        return (float) Math.pow(2.0, (Math.clamp(note, 0, 24) - 12) / 12.0);
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}, 0 if it has none. */
    static float yawOf(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    /** A cube of edge {@code size} centered on the block display's origin, rotated around its center. */
    private static Transformation cubePose(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private boolean roomFor(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T remember(T display) {
        displays.add(display);
        return display;
    }
}
