package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 해커 — breaks into enemies' abilities. Every hit plants a backdoor that exposes the victim's cooldown and makes its
 * next skill come back late, the active glitches the aimed enemy (it stutters around like a lagging connection, its
 * view scrambled) and exploits a planted backdoor for an instant silence, and the ultimate uploads malware to every
 * enemy nearby and brings their systems down: silence, screen noise and a drained ultimate gauge.
 *
 * <p>The ultimate needs no grant, ticks or hooks (backdoors only make it drain more), so the gambler may borrow it.
 *
 * <p>Look (neon green on black, with magenta and cyan glitch fringes): a backdoored enemy carries a small terminal
 * readout over its head showing its cooldown, and when the backdoor fires the readout flashes LAG and a loading
 * spinner turns around its head. The glitch types its command into a floating terminal, shoots a green data line with
 * colour fringes, leaves a torn, pixel-sliced afterimage (green, black and magenta glass slices) at every spot the
 * victim stutters away from and splashes cyan-magenta fringes where it reappears. The system down raises a ring of
 * falling code columns around the hacker over circuit traces running out across the floor, streams data into each
 * victim under an upload progress bar, then crashes them: SYSTEM DOWN over their heads, a burst of dead pixels, a
 * descending error melody and a noisy title across their screens while a reboot countdown runs.
 */
public final class HackerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("hacker", "해커", Material.REDSTONE)
            .color(TextColor.color(0x39FF14))
            .difficulty(3)
            .passive("백도어",
                    "공격한 적에게 {backdoor_duration}초간 백도어를 심습니다. 백도어가 심어진 적은 머리 위에 액티브 "
                            + "쿨타임이 드러나고, 쿨타임이 끝나는 순간 {backdoor_lag}초간 먹통(침묵)이 되어 다음 스킬이 늦어집니다. "
                            + "같은 적에게는 발동 후 {backdoor_cooldown}초 뒤부터 다시 심을 수 있습니다.")
            .active("글리치",
                    "조준한 적({glitch_range}블록 이내)을 해킹해 {glitch_damage} 피해를 주고, {glitch_hops}번 무작위 "
                            + "방향으로 짧게 튕겨 보내며(최대 {glitch_distance}블록) 시야를 뒤틀고 {glitch_nausea}초간 멀미를 "
                            + "겁니다. 백도어가 심어진 적이면 백도어를 터뜨려 즉시 {exploit_silence}초간 침묵시킵니다.",
                    10)
            .ultimate("시스템 다운",
                    "반경 {ult_radius}블록의 적에게 {ult_upload}초간 악성 코드를 업로드합니다. 업로드가 끝날 때 내 반경 "
                            + "{ult_radius}블록 안에 남은 적은 {ult_damage} 피해를 받고 {ult_silence}초간 침묵하며 "
                            + "{ult_nausea}초간 화면 노이즈(멀미)에 시달리고, 궁극기 게이지가 {ult_drain}% 줄어듭니다"
                            + "(백도어가 심어진 적은 {exploit_drain}%).")
            .param("backdoor_duration", 12)
            .param("backdoor_cooldown", 8)
            .param("backdoor_lag", 1.5)
            .param("glitch_range", 16)
            .param("glitch_damage", 4)
            .param("glitch_hops", 3)
            .param("glitch_distance", 3.5)
            .param("glitch_nausea", 4)
            .param("exploit_silence", 1.5)
            .param("ult_radius", 10)
            .param("ult_upload", 1)
            .param("ult_damage", 3)
            .param("ult_silence", 3)
            .param("ult_nausea", 4)
            .param("ult_drain", 25)
            .param("exploit_drain", 40)
            .build();

    private static final String GREEN = "#39FF14";
    private static final String PALE = "#D9FFD0";
    private static final String RED = "#FF3B3B";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: neon green on black, glitch fringes
    /** Pale green core, neon green body, near-black edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xD9FFD0, 0x39FF14, 0x031A03);
    private static final Color CYAN = Color.fromRGB(0x2BE7FF);
    private static final Color MAGENTA = Color.fromRGB(0xFF2BD6);
    private static final Particle.DustOptions PIXEL = new Particle.DustOptions(PALETTE.body(), 1.2f);
    private static final Particle.DustOptions PIXEL_SMALL = new Particle.DustOptions(PALETTE.body(), 0.6f);
    private static final Particle.DustOptions DEAD_PIXEL = new Particle.DustOptions(Color.fromRGB(0x050505), 1.4f);
    private static final Particle.DustOptions CYAN_FRINGE = new Particle.DustOptions(CYAN, 0.8f);
    private static final Particle.DustOptions MAGENTA_FRINGE = new Particle.DustOptions(MAGENTA, 0.8f);
    private static final Particle.DustOptions SPINNER = new Particle.DustOptions(Color.fromRGB(0x9AFF8A), 0.6f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition GLOW_SMALL = PALETTE.glow(0.6f);
    private static final Color TERMINAL_BACKGROUND = Color.fromARGB(170, 2, 14, 2);
    private static final String GLYPHS = "01010110ｱｲｳｴｵｶｷｸｹｺｻｼｽ";

    /** Display entities this ability keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;

    // passive
    private static final long BACKDOOR_PERIOD = 2;
    private static final long LABEL_PERIOD = 4;
    private static final int MAX_LABELS = 6;
    private static final float LABEL_SCALE = 0.55f;
    private static final double LABEL_HEIGHT = 0.55;
    private static final long CLEANUP_PERIOD = 200;
    private static final long WRIST_PERIOD = 8;

    // active
    private static final long HOP_START = 3;
    private static final long HOP_INTERVAL = 4;
    private static final int HOP_ATTEMPTS = 12;
    private static final double HOP_MIN = 1.2;
    private static final double HOP_SNAP = 2.5;
    private static final int TERMINAL_TICKS = 16;
    private static final int FRAME_TICKS = 8;
    private static final Material[] SLICE_BLOCKS = {Material.LIME_STAINED_GLASS, Material.BLACK_STAINED_GLASS,
            Material.MAGENTA_STAINED_GLASS};

    // ultimate
    private static final int RAIN_COLUMNS = 8;
    private static final double RAIN_RADIUS = 1.9;
    private static final long RAIN_PERIOD = 6;
    private static final int TRACES = 8;
    private static final int BAR_CELLS = 10;

    /** One planted backdoor. */
    private static final class Backdoor {
        private final Player target;
        /** Until when an idle backdoor (the target's skill is ready) waits for the target to use its skill. */
        private long expires;
        /** The target's cooldown was seen running: the lag fires the moment it ends. */
        private boolean cooling;
        private boolean armed;
        /** Nothing can be planted on this target again before this tick (after a backdoor fired). */
        private long lockedUntil = Long.MIN_VALUE / 2;
        private @Nullable TextDisplay label;

        Backdoor(Player target) {
            this.target = target;
        }
    }

    private final Map<UUID, Backdoor> backdoors = new HashMap<>();
    private int liveDisplays;
    /** True while the system down deals its damage: a crash plants no new backdoor (it already silences). */
    private boolean crashing;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        // The context removes the labels; only forget the backdoors here.
        backdoors.clear();
    }

    // ------------------------------------------------------------------ passive: backdoor

    /** Any landed hit of the hacker (melee, projectile or ability) plants a backdoor on an enemy player. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (crashing || !(victim instanceof Player target) || !ctx().isAlive(target)
                || !ctx().isEnemy(player(), target)) {
            return;
        }
        long now = ctx().currentTick();
        Backdoor backdoor = backdoors.computeIfAbsent(target.getUniqueId(), id -> new Backdoor(target));
        if (now < backdoor.lockedUntil) {
            return;
        }
        long duration = params().ticks("backdoor_duration");
        if (duration <= 0) {
            return;
        }
        boolean fresh = !backdoor.armed;
        backdoor.armed = true;
        backdoor.expires = now + duration;
        if (fresh) {
            backdoor.cooling = ctx().cooldownRemaining(target) > 0;
            plantFx(target);
            showLabel(backdoor);
        }
    }

    /** Lines of green code flowing into the victim's head, with two quick digital chirps. */
    private void plantFx(Player target) {
        Location head = target.getEyeLocation().add(0, 0.3, 0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int motes = Vfx.n(ctx(), 5);
        for (int i = 0; i < motes; i++) {
            Location from = head.clone().add(random.nextDouble(-0.9, 0.9), random.nextDouble(0.2, 0.9),
                    random.nextDouble(-0.9, 0.9));
            Vfx.trail(from, head, i % 3 == 0 ? CYAN : PALETTE.body(), 6 + i, false);
        }
        Vfx.ring(ctx(), head, 0.35, 8, random.nextDouble(TAU), Particle.DUST, PIXEL_SMALL, false);
        Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, 1.6f);
        player().playSound(player(), Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.PLAYERS, 0.5f, 2.0f);
        player().sendActionBar(Text.mm("<" + GREEN + ">⟨/⟩ 백도어 설치</" + GREEN + "> <white>" + target.getName()
                + "</white>"));
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (tick % WRIST_PERIOD == 0) {
            // A faint holographic wristband of green pixels around the hacker's hand.
            Location eye = owner.getEyeLocation();
            Vector look = eye.getDirection();
            Vector right = new Vector(-look.getZ(), 0, look.getX());
            if (right.lengthSquared() > 1.0E-6) {
                Location hand = eye.clone().add(right.normalize().multiply(0.38)).add(look.multiply(0.3))
                        .subtract(0, 0.55, 0);
                Vfx.ring(ctx(), hand, 0.14, 4, tick * 0.4, Particle.DUST, PIXEL_SMALL, false);
            }
        }
        if (backdoors.isEmpty() || tick % BACKDOOR_PERIOD != 0) {
            return;
        }
        long now = ctx().currentTick();
        if (tick % CLEANUP_PERIOD == 0) {
            backdoors.values().removeIf(b -> !b.armed && now >= b.lockedUntil);
        }
        List<Backdoor> fired = new ArrayList<>();
        Iterator<Backdoor> it = backdoors.values().iterator();
        while (it.hasNext()) {
            Backdoor backdoor = it.next();
            if (!backdoor.armed) {
                continue;
            }
            Player target = backdoor.target;
            if (!target.isValid() || !ctx().isAlive(target) || !ctx().isEnemy(owner, target)) {
                backdoor.armed = false;
                dropLabel(backdoor, false);
                it.remove();
                continue;
            }
            double remaining = ctx().cooldownRemaining(target);
            if (remaining > 0) {
                backdoor.cooling = true;
            } else if (backdoor.cooling) {
                fired.add(backdoor);
                continue;
            } else if (now >= backdoor.expires) {
                backdoor.armed = false;
                dropLabel(backdoor, false);
                continue;
            }
            if (tick % LABEL_PERIOD == 0) {
                updateLabel(backdoor, remaining);
            } else {
                followLabel(backdoor);
            }
        }
        // Fired after the walk: the lag is a silence, which runs other code.
        for (Backdoor backdoor : fired) {
            lag(backdoor);
        }
    }

    /** The backdoor fires as the target's cooldown ends: it lags (is silenced) and its readout flashes. */
    private void lag(Backdoor backdoor) {
        Player target = backdoor.target;
        backdoor.armed = false;
        backdoor.lockedUntil = ctx().currentTick() + params().ticks("backdoor_cooldown");
        long lag = params().ticks("backdoor_lag");
        if (lag > 0) {
            ctx().silence(target, lag);
            spinner(target, lag);
        }
        TextDisplay label = backdoor.label;
        if (label != null && label.isValid()) {
            label.text(Text.mm("<b><" + RED + ">LAG</" + RED + "></b> <" + GREEN + ">⟳</" + GREEN + ">"));
        }
        dropLabel(backdoor, true);
        Location head = target.getEyeLocation().add(0, 0.3, 0);
        glitchBurst(Fx.body(target), 0.5, false);
        Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 0.9f);
        Fx.sound(head, Sound.BLOCK_REDSTONE_TORCH_BURNOUT, 0.6f, 1.4f + Fx.jitter(0.1f));
        Player owner = player();
        if (owner.isOnline()) {
            owner.sendActionBar(Text.mm("<" + GREEN + ">⟨/⟩ 백도어 발동</" + GREEN + "> <white>" + target.getName()
                    + "</white> <gray>" + AbilityInfo.formatNumber(lag / 20.0) + "초 먹통</gray>"));
        }
    }

    /** A loading spinner turning around the lagging target's head while it is silenced. */
    private void spinner(Player target, long ticks) {
        Loop.repeat(ctx(), player(), 0, 2, Math.max(1, ticks / 2), run -> {
            if (!target.isValid() || target.isDead()) {
                return false;
            }
            Location head = target.getEyeLocation().add(0, 0.55, 0);
            int dots = 8;
            for (int i = 0; i < dots; i++) {
                double angle = i * TAU / dots;
                Location at = head.clone().add(Math.cos(angle) * 0.32, 0, Math.sin(angle) * 0.32);
                boolean lit = i == (int) (run % dots);
                Vfx.dot(at, Particle.DUST, lit ? PIXEL : SPINNER, false);
            }
            return true;
        });
    }

    private boolean isVisible(LivingEntity target) {
        return !(target instanceof Player hidden) || player().canSee(hidden);
    }

    private static Location labelPoint(Player target) {
        Location at = target.getLocation().add(0, target.getHeight() + LABEL_HEIGHT, 0);
        at.setYaw(0);
        at.setPitch(0);
        return at;
    }

    private int shownLabels() {
        int shown = 0;
        for (Backdoor backdoor : backdoors.values()) {
            if (backdoor.label != null && backdoor.label.isValid()) {
                shown++;
            }
        }
        return shown;
    }

    /** The terminal readout over a backdoored enemy (never over one the hacker cannot see). */
    private void showLabel(Backdoor backdoor) {
        Player target = backdoor.target;
        if (backdoor.label != null && backdoor.label.isValid()) {
            return;
        }
        if (!isVisible(target) || shownLabels() >= MAX_LABELS || !claim(1)) {
            return;
        }
        Player owner = player();
        TextDisplay label = Displays.text(ctx(), owner, labelPoint(target), Text.mm(readout(target, 0)), 0.1f, 0);
        label.setBackgroundColor(TERMINAL_BACKGROUND);
        label.setTeleportDuration((int) BACKDOOR_PERIOD);
        Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0, 0, LABEL_SCALE, LABEL_SCALE,
                LABEL_SCALE, 0, 0, 0), 3);
        backdoor.label = label;
    }

    private void updateLabel(Backdoor backdoor, double remaining) {
        Player target = backdoor.target;
        if (!isVisible(target)) {
            // The target slipped into stealth: its readout must not give it away.
            dropLabel(backdoor, false);
            return;
        }
        TextDisplay label = backdoor.label;
        if (label == null || !label.isValid()) {
            showLabel(backdoor);
            return;
        }
        label.text(Text.mm(readout(target, remaining)));
        followLabel(backdoor);
    }

    private void followLabel(Backdoor backdoor) {
        TextDisplay label = backdoor.label;
        if (label != null && label.isValid() && label.getWorld().equals(backdoor.target.getWorld())) {
            label.teleport(labelPoint(backdoor.target));
        }
    }

    /** "⟨/⟩ 3.2" while the target's skill recharges, "⟨/⟩ 대기" while it is ready (the backdoor waits for its use). */
    private static String readout(Player target, double remaining) {
        String value = remaining > 0
                ? "<white>" + AbilityInfo.formatNumber(Math.ceil(remaining * 10) / 10) + "s</white>"
                : "<gray>대기</gray>";
        return "<" + GREEN + ">⟨/⟩</" + GREEN + "> " + value;
    }

    /** Removes a readout: it pops (the backdoor fired) or blinks out. */
    private void dropLabel(Backdoor backdoor, boolean pop) {
        TextDisplay label = backdoor.label;
        backdoor.label = null;
        if (label == null) {
            return;
        }
        Player owner = player();
        ctx().runLater(owner, pop ? 12 : 4, () -> release(1));
        if (!label.isValid()) {
            return;
        }
        if (pop) {
            Displays.animate(label, Displays.centered(0, 0.25f, 0, LABEL_SCALE * 1.5f, LABEL_SCALE * 1.5f,
                    LABEL_SCALE * 1.5f, 0, 0, 0), 3);
            ctx().runLater(owner, 8, () -> {
                if (label.isValid()) {
                    Displays.shrinkAway(ctx(), owner, label, 3);
                }
            });
        } else {
            // A glitchy blink out: squashed flat, then gone.
            Displays.animate(label, Displays.centered(0, 0, 0, LABEL_SCALE * 1.4f, 0.02f, LABEL_SCALE, 0, 0, 0), 2);
            ctx().runLater(owner, 3, () -> {
                if (label.isValid()) {
                    label.remove();
                }
            });
        }
    }

    /** Takes the backdoor of {@code target} for an exploit: true if one was armed (it is then used up). */
    private boolean exploit(LivingEntity target) {
        Backdoor backdoor = backdoors.get(target.getUniqueId());
        if (backdoor == null || !backdoor.armed || !backdoor.target.equals(target)) {
            return false;
        }
        backdoor.armed = false;
        backdoor.lockedUntil = ctx().currentTick() + params().ticks("backdoor_cooldown");
        TextDisplay label = backdoor.label;
        if (label != null && label.isValid()) {
            label.text(Text.mm("<b><" + RED + ">EXPLOIT</" + RED + "></b>"));
        }
        dropLabel(backdoor, true);
        return true;
    }

    // ------------------------------------------------------------------ active: glitch

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("glitch_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        if (target == null || !isVisible(target)) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        boolean exploited = exploit(target);
        terminal(owner, exploited ? "exploit.exe" : "glitch.exe");
        dataLine(owner, target);
        new Glitch(target, exploited).start();
        return ActivationResult.success();
    }

    /** A small floating terminal in front of the hacker that types the command. */
    private void terminal(Player owner, String command) {
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        Location at = eye.clone().add(look.clone().multiply(1.3)).add(0, 0.25, 0);
        at.setYaw(0);
        at.setPitch(0);
        Fx.sound(eye, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.8f);
        Fx.sound(eye, Sound.UI_BUTTON_CLICK, 0.4f, 1.9f);
        if (!claim(1)) {
            return;
        }
        ctx().runLater(owner, TERMINAL_TICKS + 2, () -> release(1));
        TextDisplay screen = Displays.text(ctx(), owner, at, Text.mm("<" + GREEN + ">> _</" + GREEN + ">"), 0.45f,
                TERMINAL_TICKS);
        screen.setBackgroundColor(TERMINAL_BACKGROUND);
        String full = "> " + command;
        int steps = 6;
        Loop.repeat(ctx(), owner, 1, 1, steps, run -> {
            if (!screen.isValid()) {
                return false;
            }
            int shown = (int) Math.min(full.length(), Math.round((run + 1) * full.length() / (double) steps));
            String typed = full.substring(0, shown);
            screen.text(Text.mm("<" + GREEN + ">" + typed + (run + 1 < steps ? "_" : " <b>✔</b>") + "</" + GREEN
                    + ">"));
            Fx.sound(owner.getEyeLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.25f, 1.6f + Fx.jitter(0.2f));
            return true;
        });
        ctx().runLater(owner, TERMINAL_TICKS - 4, () -> {
            if (screen.isValid()) {
                Displays.animate(screen, Displays.centered(0, 0, 0, 0.6f, 0.01f, 0.45f, 0, 0, 0), 3);
            }
        });
    }

    /** The hack connecting: a jittering green data line with cyan and magenta fringes to the victim. */
    private void dataLine(Player owner, LivingEntity target) {
        Location eye = owner.getEyeLocation();
        Location hand = eye.clone().add(eye.getDirection().multiply(0.6)).subtract(0, 0.3, 0);
        Location body = Fx.body(target);
        Vector delta = body.toVector().subtract(hand.toVector());
        Vector side = new Vector(-delta.getZ(), 0, delta.getX());
        side = side.lengthSquared() < 1.0E-6 ? new Vector(0.06, 0, 0) : side.normalize().multiply(0.06);
        Vfx.line(ctx(), hand, body, 3, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
        Vfx.line(ctx(), hand.clone().add(side), body.clone().add(side), 1.2, Particle.DUST, CYAN_FRINGE, false);
        Vfx.line(ctx(), hand.clone().subtract(side), body.clone().subtract(side), 1.2, Particle.DUST,
                MAGENTA_FRINGE, false);
        Vfx.trail(hand, body, PALETTE.core(), 5, false);
        Fx.sound(hand, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 2.0f);
        Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 0.6f + Fx.jitter(0.05f));
    }

    /**
     * One glitch: the hit lands at once (with the nausea), then the victim stutters to {@code glitch_hops} random safe
     * spots around where it stood, its view scrambled at every hop.
     */
    private final class Glitch implements LongPredicate {
        private final LivingEntity target;
        private final boolean exploited;
        private final Location anchor;
        private final int hops;
        private int done;

        Glitch(LivingEntity target, boolean exploited) {
            this.target = target;
            this.exploited = exploited;
            this.anchor = target.getLocation();
            this.hops = Math.max(0, params().integer("glitch_hops"));
        }

        void start() {
            Player owner = player();
            if (exploited) {
                long silence = params().ticks("exploit_silence");
                if (silence > 0 && target instanceof Player victim) {
                    ctx().silence(victim, silence);
                }
                exploitFx();
            }
            Combat.hit(ctx(), owner, target, params().num("glitch_damage"));
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return;
            }
            int nausea = (int) Math.min(Integer.MAX_VALUE, params().ticks("glitch_nausea"));
            if (nausea > 0) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, nausea, 0, false, true, true));
            }
            glitchBurst(Fx.body(target), 0.7, true);
            Fx.sound(Fx.body(target), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.9f);
            if (hops > 0) {
                Loop.repeat(ctx(), owner, HOP_START, HOP_INTERVAL, hops, this);
            }
        }

        @Override
        public boolean test(long run) {
            if (!Combat.isTargetable(ctx(), player(), target)) {
                return false;
            }
            hop();
            done++;
            if (done >= hops) {
                Player owner = player();
                if (owner.isOnline()) {
                    owner.sendActionBar(Text.mm("<" + GREEN + ">글리치</" + GREEN + "> <white>"
                            + (target instanceof Player p ? p.getName() : "대상") + "</white>"
                            + (exploited ? " <" + RED + ">익스플로잇!</" + RED + ">" : "")));
                }
            }
            return true;
        }

        /** One stutter: a torn afterimage stays behind, the victim reappears nearby looking somewhere else. */
        private void hop() {
            Location from = target.getLocation();
            Location to = target.isInsideVehicle() ? null : hopSpot(target, anchor);
            ghostFrame(target, from);
            if (to == null) {
                glitchBurst(Fx.body(target), 0.5, false);
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            to.setYaw(random.nextFloat() * 360f - 180f);
            to.setPitch(Math.clamp(from.getPitch() + random.nextFloat() * 50f - 25f, -60f, 60f));
            if (!target.teleport(to, TeleportCause.PLUGIN)) {
                return;
            }
            target.setVelocity(new Vector());
            target.setFallDistance(0);
            glitchBurst(Fx.body(target), 0.6, false);
            Location body = Fx.body(target);
            Fx.sound(body, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.7f + Fx.jitter(0.2f));
            Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BIT, 0.7f, 0.5f + random.nextFloat() * 1.5f);
            Fx.sound(body, Sound.BLOCK_COMPARATOR_CLICK, 0.6f, 1.8f);
        }

        private void exploitFx() {
            Location head = target.getEyeLocation().add(0, 0.5, 0);
            Vfx.dot(head, Particle.FLASH, PALETTE.body(), false);
            Vfx.ring(ctx(), head, 0.6, 14, 0, Particle.DUST, PIXEL, false);
            Vfx.cloud(ctx(), head, Particle.ELECTRIC_SPARK, 10, 0.3, 0.2, null, false);
            Fx.sound(head, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.6f);
            Fx.sound(head, Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 2.0f);
        }
    }

    /**
     * A random safe spot for a stutter: within {@code glitch_distance} of where the victim stood, at least
     * {@link #HOP_MIN} from where it is now, standing on ground inside the arena and in plain line from the
     * starting spot (never through a wall). Null if none was found.
     */
    private @Nullable Location hopSpot(LivingEntity target, Location anchor) {
        double reach = Math.max(HOP_MIN + 0.1, params().num("glitch_distance"));
        Body body = Body.standing(target);
        World world = anchor.getWorld();
        Location current = target.getLocation();
        Location anchorChest = anchor.clone().add(0, body.height() * 0.5, 0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < HOP_ATTEMPTS; attempt++) {
            double angle = random.nextDouble(TAU);
            double distance = random.nextDouble(HOP_MIN, reach);
            Location feet = anchor.clone().add(Math.cos(angle) * distance, 0.6, Math.sin(angle) * distance);
            Spots.snapToGround(world, feet, HOP_SNAP);
            if (feet.distanceSquared(current) < HOP_MIN * HOP_MIN || !Spots.isSafe(ctx(), feet, body)
                    || !Spots.clearPath(anchorChest, feet.clone().add(0, body.height() * 0.5, 0))) {
                continue;
            }
            return feet;
        }
        return null;
    }

    /**
     * The afterimage a stutter leaves behind: the body cut into three slices of green, black and magenta glass, torn
     * sideways, jittering once and collapsing into thin lines. Falls back to pixel particles without display budget.
     */
    private void ghostFrame(LivingEntity target, Location from) {
        Player owner = player();
        double height = target.getHeight();
        double width = target.getWidth();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location at = from.clone();
        at.setYaw(0);
        at.setPitch(0);
        if (!claim(SLICE_BLOCKS.length)) {
            for (int i = 0; i < 8; i++) {
                Vfx.dot(at.clone().add(random.nextDouble(-width, width) * 0.5, random.nextDouble(height),
                        random.nextDouble(-width, width) * 0.5), Particle.DUST, i % 2 == 0 ? PIXEL : MAGENTA_FRINGE,
                        false);
            }
            return;
        }
        ctx().runLater(owner, FRAME_TICKS + 2, () -> release(SLICE_BLOCKS.length));
        float sliceHeight = (float) (height / SLICE_BLOCKS.length);
        float w = (float) width;
        for (int i = 0; i < SLICE_BLOCKS.length; i++) {
            float y = sliceHeight * (i + 0.5f);
            float tear = (float) random.nextDouble(-0.18, 0.18);
            BlockDisplay slice = Displays.block(ctx(), owner, at, SLICE_BLOCKS[i].createBlockData(),
                    Displays.transform(tear, y, 0, w, sliceHeight * 0.92f, w * 0.6f, 0, 0, 0), FRAME_TICKS);
            float jitter = (float) random.nextDouble(-0.25, 0.25);
            Displays.animateNextTick(ctx(), owner, slice,
                    Displays.transform(jitter, y, 0, w * 1.1f, sliceHeight * 0.92f, w * 0.6f, 0, 0, 0), 1);
            ctx().runLater(owner, 3, () -> Displays.animate(slice,
                    Displays.transform(-jitter * 0.5f, y, 0, w * 1.3f, 0.02f, w * 0.05f, 0, 0, 0), FRAME_TICKS - 4));
        }
    }

    /**
     * Dead and live pixels bursting around {@code at}, with cyan and magenta fringes offset to either side, like a
     * corrupted frame.
     */
    private void glitchBurst(Location at, double size, boolean flash) {
        World world = at.getWorld();
        if (flash) {
            Vfx.dot(at, Particle.FLASH, PALETTE.body(), false);
        }
        world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 8), size * 0.5, size * 0.8, size * 0.5, 0, PIXEL);
        world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 5), size * 0.5, size * 0.8, size * 0.5, 0, DEAD_PIXEL);
        world.spawnParticle(Particle.DUST, at.clone().add(0.12, 0, 0), Vfx.n(ctx(), 4), size * 0.4, size * 0.7,
                size * 0.4, 0, CYAN_FRINGE);
        world.spawnParticle(Particle.DUST, at.clone().add(-0.12, 0, 0), Vfx.n(ctx(), 4), size * 0.4, size * 0.7,
                size * 0.4, 0, MAGENTA_FRINGE);
        Vfx.cloud(ctx(), at, Particle.ELECTRIC_SPARK, 4, size * 0.4, 0.1, null, false);
    }

    // ------------------------------------------------------------------ ultimate: system down

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("ult_radius"));
        List<LivingEntity> targets = ctx().enemiesNear(owner, Fx.body(owner), radius);
        if (targets.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        new SystemDown(targets, radius).begin();
        return ActivationResult.success();
    }

    /** The upload, then the crash, for every enemy that was in range when it began. */
    private final class SystemDown implements LongPredicate {
        private final List<LivingEntity> targets;
        private final double radius;
        private final long upload;
        private final Map<UUID, TextDisplay> bars = new HashMap<>();
        private final TextDisplay[] rain = new TextDisplay[RAIN_COLUMNS];

        SystemDown(List<LivingEntity> targets, double radius) {
            this.targets = new ArrayList<>(targets);
            this.radius = radius;
            this.upload = Math.max(1, params().ticks("ult_upload"));
        }

        void begin() {
            Player owner = player();
            Location at = owner.getLocation();
            World world = at.getWorld();
            world.spawnParticle(Particle.FLASH, Fx.body(owner), 1, 0, 0, 0, 0, PALETTE.body(), true);
            Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.1, 0), 0.5, radius, 6, Particle.DUST_COLOR_TRANSITION,
                    GLOW, true);
            Fx.sound(at, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.8f);
            Fx.sound(at, Sound.BLOCK_CONDUIT_ACTIVATE, 1.0f, 1.6f);
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BIT, 1.0f, 1.0f);
            long life = upload + 14;
            for (int i = 0; i < rain.length; i++) {
                if (!claim(1)) {
                    break;
                }
                ctx().runLater(owner, life + 2, () -> release(1));
                TextDisplay column = Displays.text(ctx(), owner, rainPoint(owner, i, 0), Text.mm(codeColumn()), 0.5f,
                        life);
                column.setTeleportDuration(1);
                column.setLineWidth(20);
                rain[i] = column;
            }
            // Each bar turns into the reboot countdown after the crash, so it lives until the silence is over.
            long barLife = upload + 10 + Math.max(10, params().ticks("ult_silence")) + 10;
            for (LivingEntity target : targets) {
                if (!isVisible(target) || bars.size() >= MAX_LABELS || !claim(1)) {
                    continue;
                }
                ctx().runLater(owner, barLife + 2, () -> release(1));
                TextDisplay bar = Displays.text(ctx(), owner, barPoint(target), Text.mm(progress(0)), 0.5f, barLife);
                bar.setBackgroundColor(TERMINAL_BACKGROUND);
                bar.setTeleportDuration(1);
                bars.put(target.getUniqueId(), bar);
            }
            Loop.repeat(ctx(), owner, 0, 1, upload + 1, this);
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (tick >= upload) {
                crash(owner);
                return false;
            }
            double t = (double) tick / upload;
            drawRain(owner, tick);
            if (tick % 4 == 0) {
                circuit(owner, tick);
            }
            for (LivingEntity target : targets) {
                if (!Combat.isTargetable(ctx(), owner, target)) {
                    continue;
                }
                TextDisplay bar = bars.get(target.getUniqueId());
                if (bar != null && bar.isValid()) {
                    bar.teleport(barPoint(target));
                    if (tick % 2 == 0) {
                        bar.text(Text.mm(progress(t)));
                    }
                }
                if (isVisible(target) && target.getWorld().equals(owner.getWorld())) {
                    Location from = Fx.body(owner).add(Fx.jitter(0.3f), Fx.jitter(0.3f), Fx.jitter(0.3f));
                    Vfx.trail(from, Fx.body(target), tick % 3 == 0 ? CYAN : PALETTE.body(), 8, true);
                }
            }
            if (tick % 4 == 0) {
                Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.5f, 0.8f + (float) t);
            }
            return true;
        }

        /** The code columns circle the hacker, scrolling down and changing their characters. */
        private void drawRain(Player owner, long tick) {
            for (int i = 0; i < rain.length; i++) {
                TextDisplay column = rain[i];
                if (column == null || !column.isValid() || !column.getWorld().equals(owner.getWorld())) {
                    continue;
                }
                column.teleport(rainPoint(owner, i, tick));
                long phase = (tick + i * 2) % RAIN_PERIOD;
                if (phase == 0) {
                    column.text(Text.mm(codeColumn()));
                    Displays.animate(column, Displays.centered(0, 0.5f, 0, 0.5f, 0.5f, 0.5f, 0, 0, 0), 0);
                } else if (phase == 1) {
                    Displays.animate(column, Displays.centered(0, -0.5f, 0, 0.5f, 0.5f, 0.5f, 0, 0, 0),
                            (int) RAIN_PERIOD - 1);
                }
            }
        }

        /** Circuit traces running out across the floor with a pulse travelling along each, and the range ring. */
        private void circuit(Player owner, long tick) {
            Location floor = owner.getLocation().add(0, 0.1, 0);
            Vfx.ring(ctx(), floor, radius, (int) Math.min(48, Math.max(16, radius * 4)), tick * 0.02, Particle.DUST,
                    PIXEL_SMALL, true);
            double pulse = (tick % 12) / 12.0;
            for (int k = 0; k < TRACES; k++) {
                double angle = k * TAU / TRACES + 0.2;
                double bend = angle + Math.PI / 4;
                Location knee = floor.clone().add(Math.cos(angle) * radius * 0.45, 0, Math.sin(angle) * radius * 0.45);
                Location start = floor.clone().add(Math.cos(angle) * 1.0, 0, Math.sin(angle) * 1.0);
                Location end = knee.clone().add(Math.cos(bend) * radius * 0.4, 0, Math.sin(bend) * radius * 0.4);
                Vfx.line(ctx(), start, knee, 1.0, Particle.DUST, PIXEL_SMALL, true);
                Vfx.line(ctx(), knee, end, 1.0, Particle.DUST, PIXEL_SMALL, true);
                Location head = pulse < 0.5
                        ? start.clone().add(knee.clone().subtract(start).toVector().multiply(pulse * 2))
                        : knee.clone().add(end.clone().subtract(knee).toVector().multiply(pulse * 2 - 1));
                Vfx.dot(head, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            }
        }

        /** The upload completes: every enemy still in range crashes, the others drop the connection. */
        private void crash(Player owner) {
            for (TextDisplay column : rain) {
                if (column != null && column.isValid()) {
                    Displays.animate(column, Displays.centered(0, -1.2f, 0, 0.5f, 0.02f, 0.5f, 0, 0, 0), 4);
                    ctx().runLater(owner, 5, () -> {
                        if (column.isValid()) {
                            column.remove();
                        }
                    });
                }
            }
            Location center = Fx.body(owner);
            List<LivingEntity> inRange = ctx().enemiesNear(owner, center, radius);
            double damage = params().num("ult_damage");
            long silence = params().ticks("ult_silence");
            int nausea = (int) Math.min(Integer.MAX_VALUE, params().ticks("ult_nausea"));
            int crashed = 0;
            for (LivingEntity target : targets) {
                TextDisplay bar = bars.remove(target.getUniqueId());
                if (!Combat.isTargetable(ctx(), owner, target) || !inRange.contains(target)) {
                    disconnect(bar);
                    continue;
                }
                crashed++;
                boolean backdoored = exploit(target);
                double drain = Math.max(0, params().num(backdoored ? "exploit_drain" : "ult_drain"));
                crashing = true;
                try {
                    Combat.hit(ctx(), owner, target, damage);
                } finally {
                    crashing = false;
                }
                if (!Combat.isTargetable(ctx(), owner, target)) {
                    disconnect(bar);
                    continue;
                }
                if (target instanceof Player victim) {
                    if (drain > 0) {
                        ctx().addGauge(victim, -drain);
                    }
                    if (silence > 0) {
                        ctx().silence(victim, silence);
                    }
                    screenNoise(victim, silence);
                }
                if (nausea > 0) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, nausea, 0, false, true, true));
                }
                crashFx(target, bar, silence, drain);
            }
            Location at = owner.getLocation();
            Fx.sound(at, Sound.BLOCK_BEACON_DEACTIVATE, 1.4f, 0.6f);
            Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.8f);
            Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.1, 0), 0.5, radius, 5, Particle.DUST, DEAD_PIXEL, true);
            if (owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + GREEN + "><b>SYSTEM DOWN</b></" + GREEN + "> <white>" + crashed
                        + "명 해킹</white>"));
            }
        }

        /** An enemy that got away: its progress bar reads a lost connection and blinks out. */
        private void disconnect(@Nullable TextDisplay bar) {
            if (bar == null || !bar.isValid()) {
                return;
            }
            bar.text(Text.mm("<gray>연결 끊김</gray>"));
            ctx().runLater(player(), 8, () -> {
                if (bar.isValid()) {
                    Displays.shrinkAway(ctx(), player(), bar, 3);
                }
            });
        }

        /**
         * A crashed enemy: SYSTEM DOWN over its head that turns into a reboot countdown, a burst of dead pixels and a
         * falling error melody.
         */
        private void crashFx(LivingEntity target, @Nullable TextDisplay bar, long silence, double drain) {
            Player owner = player();
            Location body = Fx.body(target);
            glitchBurst(body, 0.9, true);
            Vfx.helix(ctx(), target.getLocation(), 0.6, target.getHeight(), 1.5, 2, 10, 0, Particle.DUST, DEAD_PIXEL,
                    true);
            Fx.sound(body, Sound.BLOCK_REDSTONE_TORCH_BURNOUT, 0.8f, 0.8f);
            for (int i = 0; i < 3; i++) {
                float pitch = 1.2f - i * 0.25f;
                ctx().runLater(owner, 1 + i * 3L, () -> Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BIT, 0.9f, pitch));
            }
            if (bar == null || !bar.isValid()) {
                return;
            }
            bar.text(Text.mm("<b><" + RED + ">SYSTEM DOWN</" + RED + "></b>" + (drain > 0 ? " <gray>-"
                    + AbilityInfo.formatNumber(drain) + "%</gray>" : "")));
            Displays.animate(bar, Displays.centered(0, 0.1f, 0, 0.7f, 0.7f, 0.7f, 0, 0, 0), 3);
            long reboot = Math.max(10, silence);
            Loop.repeat(ctx(), owner, 10, 2, Math.max(1, (reboot - 10) / 2), run -> {
                if (!bar.isValid() || !target.isValid() || target.isDead()) {
                    return false;
                }
                bar.teleport(barPoint(target));
                if (run % 3 == 0) {
                    long left = Math.max(0, reboot - 10 - run * 2);
                    int filled = (int) Math.round(BAR_CELLS * (1 - (double) left / reboot));
                    bar.text(Text.mm("<" + PALE + ">재부팅</" + PALE + "> " + cells(filled) + " <white>"
                            + Fx.seconds(left) + "</white>"));
                }
                return true;
            }, () -> {
                if (bar.isValid()) {
                    Displays.shrinkAway(ctx(), owner, bar, 3);
                }
            });
        }

        private Location rainPoint(Player owner, int index, long tick) {
            double angle = index * TAU / RAIN_COLUMNS + tick * 0.03;
            Location at = owner.getLocation().add(Math.cos(angle) * RAIN_RADIUS, owner.getHeight() * 0.9,
                    Math.sin(angle) * RAIN_RADIUS);
            at.setYaw(0);
            at.setPitch(0);
            return at;
        }

        private Location barPoint(LivingEntity target) {
            Location at = target.getLocation().add(0, target.getHeight() + LABEL_HEIGHT + 0.3, 0);
            at.setYaw(0);
            at.setPitch(0);
            return at;
        }
    }

    /** The noise across a crashed victim's screen: a corrupted title and the guardian's curse in its ears. */
    private void screenNoise(Player victim, long silence) {
        Title title = Title.title(
                Text.mm("<" + GREEN + "><obf>▮▮▮</obf> <b>SYSTEM DOWN</b> <obf>▮▮▮</obf></" + GREEN + ">"),
                Text.mm("<gray>재부팅 중... " + AbilityInfo.formatNumber(silence / 20.0) + "초</gray>"),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(400)));
        victim.showTitle(title);
        victim.playSound(victim, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.PLAYERS, 0.35f, 1.6f);
    }

    /** "[▮▮▮▯▯▯▯▯▯▯] 30%" for an upload at {@code t} (0..1). */
    private static String progress(double t) {
        int filled = (int) Math.round(BAR_CELLS * Math.clamp(t, 0.0, 1.0));
        return "<" + GREEN + ">업로드</" + GREEN + "> " + cells(filled) + " <white>"
                + (int) Math.round(Math.clamp(t, 0.0, 1.0) * 100) + "%</white>";
    }

    private static String cells(int filled) {
        int lit = Math.clamp(filled, 0, BAR_CELLS);
        return "<" + GREEN + ">" + "▮".repeat(lit) + "</" + GREEN + "><dark_gray>" + "▯".repeat(BAR_CELLS - lit)
                + "</dark_gray>";
    }

    /** A column of random code characters, bright at the top and fading to dark green. */
    private static String codeColumn() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder column = new StringBuilder("<gradient:#FFFFFF:#39FF14:#0B5D0B>");
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                column.append("<newline>");
            }
            column.append(GLYPHS.charAt(random.nextInt(GLYPHS.length())));
        }
        return column.append("</gradient>").toString();
    }

    /** Claims {@code count} display entities if that stays within {@link #MAX_DISPLAYS}; release them later. */
    private boolean claim(int count) {
        if (count <= 0 || liveDisplays + count > MAX_DISPLAYS) {
            return false;
        }
        liveDisplays += count;
        return true;
    }

    private void release(int count) {
        liveDisplays = Math.max(0, liveDisplays - count);
    }
}
