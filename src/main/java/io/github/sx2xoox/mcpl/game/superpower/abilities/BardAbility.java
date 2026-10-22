package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.jspecify.annotations.Nullable;

/**
 * 음유시인 — music as magic. A traveler's song quickens the bard and nearby allies, a discordant chord weakens and
 * slows every enemy around, and the ultimate is a grand chorus: for a few seconds every beat sends out a sound wave
 * that hurts enemies and heals and hastens the bard's side. Alone (free-for-all, or no ally in reach) the chorus
 * becomes a solo that empowers the bard itself.
 *
 * <p>Look (rainbow notes): note particles in every color; on the move the bard hums floating note glyphs, an ally
 * joining the song gets one popping over its head and, with allies around, the song's reach is traced on the ground.
 * The discord flashes magenta, two jagged rings of clashing colors race out of step and sharps, flats and naturals
 * (text displays) burst out of the lute, leaving a shaking "♭" over every struck enemy for as long as the debuff
 * lasts. During the chorus a record spins over the bard's head and note glyphs circle it, both bouncing on the beat,
 * while rings of colored dust pulse out (a bright crest on each bar's forte). The chorus plays a real melody (Ode to
 * Joy) on note-block sounds, one note per beat, with a bass line and drums, and ends on a full chord.
 */
public final class BardAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("bard", "음유시인", Material.NOTE_BLOCK)
            .color(TextColor.color(0xFFC857))
            .difficulty(1)
            .passive("여행자의 노래",
                    "나와 반경 {aura_radius}블록 안 아군의 이동 속도가 {aura_speed}% 빨라집니다.")
            .active("불협화음",
                    "귀를 찢는 불협화음으로 반경 {discord_radius}블록의 적에게 {discord_damage} 피해를 주고 "
                            + "{discord_duration}초간 나약함과 구속 II를 겁니다.",
                    11)
            .ultimate("대합창",
                    "{chorus_duration}초간 {chorus_interval}초마다 음파를 퍼뜨려 반경 {chorus_radius}블록의 적에게 "
                            + "{chorus_damage} 피해를 주고, 나와 아군은 {chorus_heal}씩 회복하며 신속을 얻습니다. "
                            + "함께 노래할 아군이 없으면 독주가 되어 나에게 힘과 저항이 깃듭니다.")
            .param("aura_radius", 8)
            .param("aura_speed", 10)
            .param("discord_radius", 8)
            .param("discord_damage", 2)
            .param("discord_duration", 3)
            .param("chorus_duration", 8)
            .param("chorus_interval", 0.5)
            .param("chorus_radius", 7)
            .param("chorus_damage", 1)
            .param("chorus_heal", 0.5)
            .build();

    private static final String COLOR_TAG = "#FFC857";
    /** Bright white core, warm lute gold body, a deep violet edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xFFC857, 0x6A3DB8);
    /** The rainbow every beat cycles through. */
    private static final Color[] RAINBOW = {
            Color.fromRGB(0xFF5C7A), Color.fromRGB(0xFFA94D), Color.fromRGB(0xFFE45C), Color.fromRGB(0x7CE87C),
            Color.fromRGB(0x5CD8FF), Color.fromRGB(0x7A8CFF), Color.fromRGB(0xC77DFF),
    };
    /** The discord's clashing pair: a sour magenta against a sickly green. */
    private static final Particle.DustTransition DISCORD_SOUR =
            new Particle.DustTransition(Color.fromRGB(0xFF3D9A), Color.fromRGB(0x3A0B4F), 1.3f);
    private static final Particle.DustTransition DISCORD_ACID =
            new Particle.DustTransition(Color.fromRGB(0xB6FF3D), Color.fromRGB(0x2B4A0A), 1.1f);
    private static final TextColor FLAT_COLOR = TextColor.color(0xFF3D9A);
    private static final String[] GLYPHS = {"♪", "♫", "♬", "♩", "♪", "♫"};
    /** The discord's broken notation: sharps, flats and naturals flying apart. */
    private static final String[] SOUR_GLYPHS = {"♯", "♭", "♮"};
    private static final TextColor[] SOUR_COLORS = {TextColor.color(0xFF3D9A), TextColor.color(0xB6FF3D),
            TextColor.color(0x9A4DFF)};
    private static final double TAU = Math.PI * 2;

    /**
     * Ode to Joy (Beethoven, public domain) as note-block notes (0 = F#3 ... 24 = F#5): E E F G | G F E D | C C D E |
     * E D D. One note per beat; the chorus loops it.
     */
    private static final int[] MELODY = {10, 10, 11, 13, 13, 11, 10, 8, 6, 6, 8, 10, 10, 8, 8, 6};
    /** The bass line, one note per bar of four beats: C, G, C, G. */
    private static final int[] BASS = {6, 1, 6, 1};

    // passive
    private static final long AURA_PERIOD = 10;
    /** On the move, the bard hums a floating note this often... */
    private static final long HUM_PERIOD = 40;
    /** ...if it travelled at least this far since the last time. */
    private static final double HUM_STRIDE = 2.0;
    private static final long FLOAT_NOTE_TICKS = 24;
    /** With allies around, the song's reach is drawn on the ground this often. */
    private static final long REACH_RING_PERIOD = 40;

    // active
    private static final int DISCORD_WAVE_TICKS = 6;
    private static final int SOUR_BURST = 6;
    private static final int SOUR_FLIGHT_TICKS = 6;
    /** Sour marks over struck enemies, at most this many at a time. */
    private static final int MAX_FLAT_MARKS = 12;

    // ultimate
    /** The record spinning flat over the bard's head during the chorus. */
    private static final double DISC_HEIGHT = 2.55;
    private static final float DISC_SCALE = 0.75f;
    /** The record turns a quarter every this many ticks (interpolated, so it spins smoothly). */
    private static final int DISC_QUARTER_TICKS = 4;
    private static final double ORBIT_RADIUS = 1.3;
    private static final float GLYPH_SCALE = 1.3f;
    private static final float GLYPH_POP = 1.9f;
    /** Buffs are refreshed on every beat and last this much longer than one beat, so they never flicker. */
    private static final int BUFF_OVERLAP = 10;

    /** The speed aura on the bard and each ally in reach, by player id. */
    private final Map<UUID, Aura> auras = new HashMap<>();
    private int flatMarks;
    /** No humming while the chorus is sung (until this tick). */
    private long singingUntil = -1;
    /** Where the bard was at the last hum check; it hums when it has travelled since. */
    private @Nullable Location lastHumSpot;

    private record Aura(Player player, NamespacedKey key) {
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: traveler's song

    @Override
    public void onGrant() {
        refreshAura();
    }

    @Override
    public void onRevoke() {
        // Owner-scoped modifiers are removed with the ability; only forget them here.
        auras.clear();
        lastHumSpot = null;
    }

    @Override
    public void onTick(long tick) {
        if (tick % AURA_PERIOD != 0) {
            return;
        }
        refreshAura();
        Player owner = player();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(TAU);
        note(owner.getLocation().add(Math.cos(angle) * 0.6, 2.0 + random.nextDouble() * 0.3, Math.sin(angle) * 0.6),
                random.nextInt(25), false);
        if (tick % HUM_PERIOD == 0) {
            // A traveler's song: on the move, the bard hums and a note glyph drifts up from its shoulder.
            Location here = owner.getLocation();
            boolean travelled = lastHumSpot != null && lastHumSpot.getWorld().equals(here.getWorld())
                    && lastHumSpot.distanceSquared(here) >= HUM_STRIDE * HUM_STRIDE;
            if (travelled && ctx().currentTick() > singingUntil) {
                Location shoulder = here.clone().add(Math.cos(angle) * 0.45, 1.7, Math.sin(angle) * 0.45);
                floatingNote(shoulder, RAINBOW[random.nextInt(RAINBOW.length)], 0.8f);
                Fx.sound(shoulder, Sound.BLOCK_NOTE_BLOCK_FLUTE, 0.35f, pitch(MELODY[random.nextInt(MELODY.length)]));
            }
            lastHumSpot = here;
        }
        if (tick % (AURA_PERIOD * 2) == 0) {
            for (Aura aura : auras.values()) {
                if (!aura.player().equals(owner) && aura.player().isOnline()) {
                    note(aura.player().getLocation().add(0, aura.player().getHeight() + 0.4, 0),
                            random.nextInt(25), false);
                }
            }
        }
        if (tick % REACH_RING_PERIOD == 0) {
            showReach(owner, tick);
        }
    }

    /**
     * With allies around, the song's reach is drawn now and then as a faint rainbow ring on the ground, so teammates
     * can see where to stand. Alone, nobody needs it and it is not drawn.
     */
    private void showReach(Player owner, long tick) {
        double radius = Math.max(0, params().num("aura_radius"));
        if (radius <= 0 || ctx().alliesNear(owner, owner.getLocation(), radius * 2).isEmpty()) {
            return;
        }
        Location ground = owner.getLocation().add(0, 0.1, 0);
        int points = Vfx.n(ctx(), (int) Math.min(48, Math.max(16, radius * 4)));
        World world = ground.getWorld();
        double phase = tick * 0.01;
        for (int i = 0; i < points; i++) {
            double angle = phase + i * TAU / points;
            Particle.DustOptions dust = new Particle.DustOptions(RAINBOW[i % RAINBOW.length], 0.8f);
            world.spawnParticle(Particle.DUST, ground.getX() + Math.cos(angle) * radius, ground.getY(),
                    ground.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0, dust);
        }
    }

    /**
     * Gives the bard and every ally in reach the speed modifier (once each) and takes it from allies that left the
     * reach or the fight.
     */
    private void refreshAura() {
        Player owner = player();
        double bonus = Math.max(0, params().num("aura_speed")) / 100.0;
        Set<UUID> inReach = new HashSet<>();
        List<Player> members = new ArrayList<>();
        members.add(owner);
        members.addAll(ctx().alliesNear(owner, owner.getLocation(), Math.max(0, params().num("aura_radius"))));
        for (Player member : members) {
            inReach.add(member.getUniqueId());
            if (bonus > 0 && !auras.containsKey(member.getUniqueId())) {
                NamespacedKey key = ctx().addModifier(owner, member, Attribute.MOVEMENT_SPEED, bonus,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, 0);
                auras.put(member.getUniqueId(), new Aura(member, key));
                if (!member.equals(owner)) {
                    // An ally joins the song: a note flies over to it and pops above its head.
                    Color color = RAINBOW[auras.size() % RAINBOW.length];
                    Vfx.trail(Fx.body(owner), Fx.body(member), color, 8, false);
                    floatingNote(member.getLocation().add(0, member.getHeight() + 0.3, 0), color, 1.0f);
                    Fx.sound(member.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f,
                            pitch(13 + auras.size() % 5) + Fx.jitter(0.02f));
                }
            }
        }
        Iterator<Map.Entry<UUID, Aura>> it = auras.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Aura> entry = it.next();
            if (!inReach.contains(entry.getKey())) {
                ctx().removeModifier(entry.getValue().player(), entry.getValue().key());
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ active: discord

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double radius = Math.max(0.5, params().num("discord_radius"));
        List<LivingEntity> enemies = ctx().enemiesNear(owner, owner.getLocation(), radius);
        if (enemies.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        discordWave(owner, radius);
        double damage = params().num("discord_damage");
        long duration = params().ticks("discord_duration");
        int ticks = (int) Math.min(Integer.MAX_VALUE, Math.max(1, duration));
        int struck = 0;
        for (LivingEntity enemy : enemies) {
            Combat.hit(ctx(), owner, enemy, damage);
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            struck++;
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 0, false, true, true));
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 1, false, true, true));
            sourNote(enemy, ticks);
        }
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">♪ 불협화음</" + COLOR_TAG + "> <gray>" + struck
                + "명의 귀를 찢었습니다</gray>"));
        return ActivationResult.success();
    }

    /**
     * The discord: a flash and a spray of notes at the lute, then two jagged rings of clashing colors racing out of
     * step with each other (the wobble of their radius is the dissonance), over a tritone stab.
     */
    private void discordWave(Player owner, double radius) {
        Location chest = owner.getLocation().add(0, 1.1, 0);
        Location feet = owner.getLocation().add(0, 0.15, 0);
        World world = chest.getWorld();
        Vfx.dot(chest, Particle.FLASH, Color.fromRGB(0xFF3D9A), false);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int notes = Vfx.n(ctx(), 14);
        for (int i = 0; i < notes; i++) {
            note(chest.clone().add(random.nextGaussian() * 0.5, random.nextGaussian() * 0.4, random.nextGaussian() * 0.5),
                    random.nextInt(25), false);
        }
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, chest, Vfx.n(ctx(), 12), 0.35, 0.35, 0.35, 0,
                DISCORD_SOUR);
        double phase = random.nextDouble(TAU);
        sourBurst(owner, chest, radius, phase);
        Loop.repeat(ctx(), owner, 0, 1, DISCORD_WAVE_TICKS, run -> {
            double t = (double) (run + 1) / DISCORD_WAVE_TICKS;
            jaggedRing(feet, radius * t, run * 1.3 + phase, DISCORD_SOUR);
            jaggedRing(feet.clone().add(0, 0.5, 0), radius * Math.max(0.1, t - 0.18), -run * 1.7 - phase,
                    DISCORD_ACID);
            return true;
        });
        // A tritone (C against F#): the "devil's interval", plus a growling bass and a glitchy bit note.
        Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_GUITAR, 1.4f, pitch(6));
        Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_GUITAR, 1.4f, pitch(12));
        Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_BASS, 1.2f, pitch(0));
        Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0f, pitch(3) + Fx.jitter(0.03f));
        Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, pitch(19) + Fx.jitter(0.05f));
    }

    /**
     * The broken notation: sharps, flats and naturals burst out of the lute in every direction, tumbling as they fly
     * with the wave, and wink out.
     */
    private void sourBurst(Player owner, Location chest, double radius, double phase) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double reach = Math.min(radius * 0.55, 4.5);
        for (int i = 0; i < SOUR_BURST; i++) {
            int kind = i % SOUR_GLYPHS.length;
            double angle = phase + i * TAU / SOUR_BURST;
            TextDisplay glyph = Displays.text(ctx(), owner, chest,
                    Component.text(SOUR_GLYPHS[kind], SOUR_COLORS[kind]).decorate(TextDecoration.BOLD), 0.3f,
                    SOUR_FLIGHT_TICKS + 8L);
            glyph.setTeleportDuration(SOUR_FLIGHT_TICKS);
            Location out = chest.clone().add(Math.cos(angle) * reach, random.nextDouble(-0.3, 0.6),
                    Math.sin(angle) * reach);
            float roll = (random.nextBoolean() ? 1 : -1) * (120f + random.nextFloat() * 120f);
            ctx().runLater(owner, 1, () -> {
                if (glyph.isValid()) {
                    glyph.teleport(out);
                    Displays.animate(glyph, Displays.centered(0, 0, 0, 1.5f, 1.5f, 1.5f, 0, 0, roll), SOUR_FLIGHT_TICKS);
                }
            });
            ctx().runLater(owner, SOUR_FLIGHT_TICKS + 1L, () -> {
                if (glyph.isValid()) {
                    Displays.shrinkAway(ctx(), owner, glyph, 4);
                }
            });
        }
    }

    /** A horizontal ring whose radius wobbles seven times around, so it reads as a harsh, broken sound wave. */
    private void jaggedRing(Location center, double radius, double phase, Particle.DustTransition dust) {
        int count = Vfx.n(ctx(), (int) Math.max(12, Math.round(radius * 6)));
        World world = center.getWorld();
        for (int i = 0; i < count; i++) {
            double angle = phase * 0.2 + i * TAU / count;
            double r = radius * (1 + 0.09 * Math.sin(angle * 7 + phase));
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, center.getX() + Math.cos(angle) * r, center.getY(),
                    center.getZ() + Math.sin(angle) * r, 1, 0, 0, 0, 0, dust);
        }
    }

    /** A shaking "♭" over a struck enemy for the length of the debuff, and a sour note where it stands. */
    private void sourNote(LivingEntity enemy, int ticks) {
        Location head = enemy.getLocation().add(0, enemy.getHeight() + 0.45, 0);
        Vfx.cloud(ctx(), Fx.body(enemy), Particle.CRIT, 6, 0.25, 0.15, null, false);
        enemy.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, Fx.body(enemy), Vfx.n(ctx(), 6), 0.25, 0.4, 0.25,
                0, DISCORD_SOUR);
        Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.8f, pitch(1) + Fx.jitter(0.04f));
        if (flatMarks >= MAX_FLAT_MARKS) {
            return;
        }
        flatMarks++;
        Player owner = player();
        TextDisplay mark = Displays.text(ctx(), owner, head,
                Component.text("♭", FLAT_COLOR).decorate(TextDecoration.BOLD), 0.2f, ticks + 8L);
        Displays.animateNextTick(ctx(), owner, mark, Displays.centered(0, 0, 0, 1.4f, 1.4f, 1.4f, 0, 0, 0), 3);
        mark.setTeleportDuration(2);
        Loop.repeat(ctx(), owner, 2, 2, Math.max(1, ticks / 2), run -> {
            if (!mark.isValid()) {
                return false;
            }
            // The mark leaves with the debuff: the enemy fell, or the discord was cleansed off it.
            if (!Combat.isTargetable(ctx(), owner, enemy) || !enemy.hasPotionEffect(PotionEffectType.WEAKNESS)
                    && !enemy.hasPotionEffect(PotionEffectType.SLOWNESS)) {
                Displays.shrinkAway(ctx(), owner, mark, 4);
                return false;
            }
            Location above = enemy.getLocation().add(Fx.jitter(0.08f), enemy.getHeight() + 0.45 + Fx.jitter(0.05f),
                    Fx.jitter(0.08f));
            mark.teleport(above);
            if (run % 4 == 0) {
                Displays.animate(mark, Displays.centered(0, 0, 0, 1.4f, 1.4f, 1.4f, 0, 0, Fx.jitter(14)), 2);
            }
            return true;
        });
        ctx().runLater(owner, Math.max(1, ticks), () -> {
            flatMarks = Math.max(0, flatMarks - 1);
            if (mark.isValid()) {
                Displays.shrinkAway(ctx(), owner, mark, 5);
            }
        });
    }

    // ------------------------------------------------------------------ ultimate: grand chorus

    @Override
    public ActivationResult onUltimate() {
        new Chorus().begin();
        return ActivationResult.success();
    }

    /**
     * The chorus: note glyphs circle the bard while every beat plays the next note of the melody and sends a colored
     * wave out to the chorus radius, hurting enemies and healing and hastening the bard's side (empowering the bard
     * itself when it sings alone). It ends on a full chord with a fountain of notes.
     */
    private final class Chorus implements LongPredicate {

        private final long duration;
        private final long interval;
        private final double radius;
        private final TextDisplay[] glyphs = new TextDisplay[GLYPHS.length];
        private @Nullable ItemDisplay disc;
        private float discYaw;
        private long lastBeat = Long.MIN_VALUE / 2;
        private int beat;
        private boolean wasSolo;

        Chorus() {
            this.duration = Math.max(20, params().ticks("chorus_duration"));
            this.interval = Math.max(2, params().ticks("chorus_interval"));
            this.radius = Math.max(1, params().num("chorus_radius"));
        }

        void begin() {
            Player owner = player();
            singingUntil = ctx().currentTick() + duration + 20;
            for (int i = 0; i < glyphs.length; i++) {
                TextColor color = TextColor.color(RAINBOW[i % RAINBOW.length].asRGB());
                glyphs[i] = Displays.text(ctx(), owner, orbitPoint(owner.getLocation(), i, 0),
                        Component.text(GLYPHS[i], color).decorate(TextDecoration.BOLD), 0.1f, duration + 20);
                glyphs[i].setTeleportDuration(2);
                Displays.animateNextTick(ctx(), owner, glyphs[i], glyphScale(GLYPH_SCALE), 6);
            }
            // A record drops onto an invisible turntable over the bard's head and starts to spin.
            ItemDisplay record = Displays.item(ctx(), owner, discPoint(owner.getLocation(), 0.8),
                    ItemStack.of(Material.MUSIC_DISC_OTHERSIDE), discPose(0.05f, 0), duration + 20);
            record.setTeleportDuration(2);
            Displays.animateNextTick(ctx(), owner, record, discPose(DISC_SCALE, 0), 5);
            disc = record;
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, feet, 0.5, radius, 8, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.5f), true);
            Vfx.helix(ctx(), feet, 0.8, 2.4, 1.5, 3, 12, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), true);
            // The opening flourish: a quick rising arpeggio.
            for (int i = 0; i < 3; i++) {
                int step = i;
                ctx().runLater(owner, i * 2L, () -> Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.2f,
                        pitch(6 + step * 4)));
            }
            Fx.sound(feet, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::finale);
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (tick % 2 == 0) {
                Location base = owner.getLocation();
                for (int i = 0; i < glyphs.length; i++) {
                    TextDisplay glyph = glyphs[i];
                    if (glyph != null && glyph.isValid()) {
                        glyph.teleport(orbitPoint(base, i, tick + 2));
                    }
                }
                ItemDisplay record = disc;
                if (record != null && record.isValid()) {
                    // The record hops on every beat and settles back.
                    double hop = 0.3 * Math.max(0, 1 - (tick + 2 - lastBeat) / 6.0);
                    record.teleport(discPoint(base, hop));
                }
            }
            ItemDisplay record = disc;
            if (tick > 4 && tick % DISC_QUARTER_TICKS == 0 && record != null && record.isValid()) {
                discYaw = (discYaw + 90f) % 360f;
                Displays.animate(record, discPose(DISC_SCALE, discYaw), DISC_QUARTER_TICKS);
            }
            if (tick % interval == 0) {
                lastBeat = tick;
                pulse(owner);
            } else if (tick % 4 == 0) {
                // Between beats a few notes drift up around the singer.
                ThreadLocalRandom random = ThreadLocalRandom.current();
                double angle = random.nextDouble(TAU);
                note(owner.getLocation().add(Math.cos(angle) * 0.9, 1.6 + random.nextDouble() * 0.8,
                        Math.sin(angle) * 0.9), random.nextInt(25), true);
            }
            return true;
        }

        /** One beat: the melody note, the wave, the hurt, the healing and the buffs. */
        private void pulse(Player owner) {
            int index = beat++;
            int note = MELODY[index % MELODY.length];
            Color color = RAINBOW[index % RAINBOW.length];
            Location center = owner.getLocation();
            Location chest = center.clone().add(0, 1.2, 0);

            Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_BELL, 1.4f, pitch(note));
            Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_FLUTE, 0.7f, pitch(note));
            if (index % 2 == 0) {
                Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_BASS, 1.1f, pitch(BASS[(index / 4) % BASS.length]));
            }
            if (index % 4 == 0) {
                Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.7f, 0.8f);
            } else if (index % 2 == 0) {
                Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.2f);
            }

            // Forte on the first beat of every bar: the wave carries a bright crest and the glyphs jump higher.
            boolean forte = index % 4 == 0;
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.12, 0), 0.6, radius, 5, Particle.DUST,
                    new Particle.DustOptions(color, forte ? 1.7f : 1.3f), true);
            if (forte) {
                Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.5, 0), 0.4, radius * 0.95, 6, Particle.END_ROD,
                        null, true);
            }
            Vfx.ring(ctx(), chest, 0.9, 10, index * 0.6, Particle.DUST_COLOR_TRANSITION,
                    new Particle.DustTransition(Color.WHITE, color, 0.9f), true);
            int sprays = Vfx.n(ctx(), 4);
            for (int i = 0; i < sprays; i++) {
                double angle = index * 0.9 + i * TAU / sprays;
                note(chest.clone().add(Math.cos(angle) * 0.7, 0.6, Math.sin(angle) * 0.7), (note + i * 3) % 25, true);
            }
            popGlyphs(owner, forte ? GLYPH_POP * 1.25f : GLYPH_POP);

            double damage = params().num("chorus_damage");
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                Combat.hit(ctx(), owner, enemy, damage);
                Location body = Fx.body(enemy);
                Vfx.cloud(ctx(), body, Particle.CRIT, 3, 0.2, 0.12, null, false);
                Vfx.dot(body, Particle.DUST, new Particle.DustOptions(color, 1.2f), false);
            }

            double heal = Math.max(0, params().num("chorus_heal"));
            List<Player> allies = ctx().alliesNear(owner, center, radius);
            int buffTicks = (int) Math.min(Integer.MAX_VALUE, interval + BUFF_OVERLAP);
            for (Player ally : allies) {
                bless(ally, heal, buffTicks, forte);
                Vfx.trail(chest, Fx.body(ally), color, 6, false);
                note(ally.getLocation().add(0, ally.getHeight() + 0.4, 0), note, false);
            }
            bless(owner, heal, buffTicks, forte);
            boolean solo = allies.isEmpty();
            if (solo) {
                // A solo: the bard sings to itself and grows bolder.
                owner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, buffTicks, 0, false, false, true));
                owner.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, buffTicks, 0, false, false, true));
                Vfx.helix(ctx(), center.clone().add(0, 0.1, 0), 0.55, 2.0, 1.0, 2, 8, index * 0.8,
                        Particle.DUST_COLOR_TRANSITION, new Particle.DustTransition(color, PALETTE.body(), 0.8f),
                        false);
            }
            if (solo != wasSolo || index == 0) {
                wasSolo = solo;
                owner.sendActionBar(Text.mm(solo
                        ? "<" + COLOR_TAG + ">♬ 독주</" + COLOR_TAG + "> <gray>힘과 저항이 깃듭니다</gray>"
                        : "<" + COLOR_TAG + ">♬ 대합창</" + COLOR_TAG + "> <gray>아군 " + allies.size()
                        + "명과 함께 노래합니다</gray>"));
            }
        }

        /** Heals and hastens one member of the chorus; on a forte a heart rises over it. */
        private void bless(Player member, double heal, int buffTicks, boolean forte) {
            if (heal > 0) {
                ctx().heal(member, heal);
                if (forte) {
                    Vfx.dot(member.getLocation().add(0, member.getHeight() + 0.25, 0), Particle.HEART, null, false);
                }
            }
            member.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, buffTicks, 0, false, false, true));
        }

        /** Every glyph jumps on the beat, then settles back. */
        private void popGlyphs(Player owner, float pop) {
            for (TextDisplay glyph : glyphs) {
                if (glyph != null && glyph.isValid()) {
                    Displays.animate(glyph, glyphScale(pop), 2);
                }
            }
            ctx().runLater(owner, 2, () -> {
                for (TextDisplay glyph : glyphs) {
                    if (glyph != null && glyph.isValid()) {
                        Displays.animate(glyph, glyphScale(GLYPH_SCALE), (int) Math.max(2, interval - 3));
                    }
                }
            });
        }

        /** The finale: a full C major chord, a fountain of notes and the glyphs soaring up and away. */
        private void finale() {
            Player owner = player();
            Location chest = owner.getLocation().add(0, 1.2, 0);
            for (int note : new int[] {6, 10, 13, 18}) {
                Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_BELL, 1.3f, pitch(note));
                Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, pitch(note));
            }
            Fx.sound(chest, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
            Vfx.dot(chest, Particle.FLASH, PALETTE.body(), true);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int count = Vfx.n(ctx(), 24);
            for (int i = 0; i < count; i++) {
                double angle = i * TAU / count;
                double lift = 0.4 + random.nextDouble() * 1.6;
                note(chest.clone().add(Math.cos(angle) * 1.2, lift, Math.sin(angle) * 1.2), (i * 3) % 25, true);
            }
            Vfx.sphere(ctx(), chest, 1.4, 30, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f), true);
            ItemDisplay record = disc;
            if (record != null && record.isValid()) {
                // The record pops off the turntable: a last flip upwards, then it is gone.
                record.setTeleportDuration(6);
                record.teleport(record.getLocation().add(0, 1.2, 0));
                Displays.animate(record, Displays.centered(0, 0, 0, DISC_SCALE * 1.2f, DISC_SCALE * 1.2f,
                        DISC_SCALE * 1.2f, discYaw + 90f, 0, 0), 6);
                ctx().runLater(owner, 6, () -> {
                    if (record.isValid()) {
                        Displays.shrinkAway(ctx(), owner, record, 4);
                    }
                });
            }
            for (int i = 0; i < glyphs.length; i++) {
                TextDisplay glyph = glyphs[i];
                if (glyph == null || !glyph.isValid()) {
                    continue;
                }
                glyph.setTeleportDuration(8);
                glyph.teleport(glyph.getLocation().add(0, 1.6 + i * 0.15, 0));
                ctx().runLater(owner, 6, () -> {
                    if (glyph.isValid()) {
                        Displays.shrinkAway(ctx(), owner, glyph, 5);
                    }
                });
            }
        }

        /** Over the bard's head, {@code hop} blocks higher while it bounces on a beat. */
        private Location discPoint(Location base, double hop) {
            Location at = base.clone().add(0, DISC_HEIGHT + hop, 0);
            at.setYaw(0);
            at.setPitch(0);
            return at;
        }

        /** The record lying flat, turned {@code yaw} degrees about the vertical. */
        private Transformation discPose(float scale, float yaw) {
            return Displays.centered(0, 0, 0, scale, scale, scale, yaw, -90f, 0);
        }

        /** Where glyph {@code index} circles at {@code tick}: a slowly turning, gently bobbing ring around the head. */
        private Location orbitPoint(Location base, int index, long tick) {
            double angle = tick * 0.09 + index * TAU / glyphs.length;
            double bob = 0.18 * Math.sin(tick * 0.2 + index * 1.7);
            return base.clone().add(Math.cos(angle) * ORBIT_RADIUS, 1.5 + (index % 2) * 0.45 + bob,
                    Math.sin(angle) * ORBIT_RADIUS);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A note glyph popping up at {@code at}, drifting upwards with a slight sway and fading out. */
    private void floatingNote(Location at, Color color, float scale) {
        Player owner = player();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String glyph = GLYPHS[random.nextInt(GLYPHS.length)];
        TextDisplay note = Displays.text(ctx(), owner, at,
                Component.text(glyph, TextColor.color(color.asRGB())).decorate(TextDecoration.BOLD), 0.05f,
                FLOAT_NOTE_TICKS + 2);
        Displays.animateNextTick(ctx(), owner, note, Displays.centered(0, 0.15f, 0, scale, scale, scale, 0, 0, 0), 4);
        float sway = (float) (random.nextDouble() - 0.5) * 0.5f;
        float tilt = (random.nextFloat() - 0.5f) * 30f;
        ctx().runLater(owner, 5, () -> Displays.animate(note,
                Displays.centered(sway, 0.9f, 0, scale * 0.9f, scale * 0.9f, scale * 0.9f, 0, 0, tilt),
                (int) FLOAT_NOTE_TICKS - 10));
        ctx().runLater(owner, FLOAT_NOTE_TICKS - 5, () -> {
            if (note.isValid()) {
                Displays.shrinkAway(ctx(), owner, note, 4);
            }
        });
    }

    /** The pitch of note-block note {@code note} (0 = F#3 at 0.5 ... 12 = F#4 at 1.0 ... 24 = F#5 at 2.0). */
    private static float pitch(int note) {
        return (float) Math.pow(2.0, (Math.clamp(note, 0, 24) - 12) / 12.0);
    }

    /** One note particle, colored like a note block's note {@code note} (0..24). */
    private static void note(Location at, int note, boolean force) {
        // With a count of 0 the x offset picks the note's color.
        at.getWorld().spawnParticle(Particle.NOTE, at.getX(), at.getY(), at.getZ(), 0,
                Math.clamp(note, 0, 24) / 24.0, 0, 0, 1, null, force);
    }

    private static Transformation glyphScale(float scale) {
        return Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, 0);
    }
}
