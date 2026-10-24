package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Inputs;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.game.superpower.ability.Prompt;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptEndReason;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptHandle;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 금제술사 — the calligrapher who writes taboos. Its brush draws a ring of ink letters at an enemy's feet that forbids
 * exactly what that enemy was doing when the ring closed (sprinting, jumping, or fighting); breaking it blows the letters
 * up. Its ultimate seals the ground into a barrier whose single rule is chosen by the caster's own stance and binds the
 * caster too. Whoever breaks a taboo is branded a violator and takes more from every stroke that follows.
 *
 * <p>Look (ink black, vermilion seal red, hanji paper white): paper squares lettered in black ink laid flat in a ring,
 * a vermilion "禁" seal stamped under the feet, the rule written over the head; a broken taboo bursts the letters into
 * ink splashes and leaves a red "犯" hanging over the violator. The barrier is a circle of standing paper charms with a
 * hanging scroll of its rule in the middle and pale paper motes rising along its wall.
 *
 * <p>Every rule judges voluntary input only (kit {@link Inputs}): pushed, launched or stunned players are exempt, and
 * nothing is judged before the rule is on the target's screen (prompt channel) plus a grace and the ping slack.
 */
public final class ProhibitorAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("prohibitor", "금제술사", Material.BARRIER)
            .color(TextColor.color(0xC8231E))
            .difficulty(3)
            .passive("금기의 대가",
                    "금기를 어긴 적은 {violator_duration}초간 위반자가 됩니다. 위반자에게 주는 피해가 {violator_bonus}% "
                            + "늘고, 위반자가 또 금기를 어기면 그 위반 피해가 {violator_repeat}배가 됩니다.")
            .active("금제의 글자",
                    "조준한 적({range}블록 이내)에게 금기를 새겨, 그 순간 하던 행동을 {taboo_duration}초간 금지합니다. "
                            + "달리던 중이면 달리기, 공중이면 점프, 그 밖에는 공격(근접·투사체)이 금지됩니다. 어기면 "
                            + "{break_damage} 피해와 {break_stagger}초 경직을 받고 금기가 풀리며, 끝까지 지키면 "
                            + "{keep_damage} 피해만 받습니다.",
                    10)
            .ultimate("금제 결계",
                    "반경 {barrier_radius}블록의 결계를 {barrier_duration}초간 펼치고 시전할 때의 자세로 규칙을 정합니다. "
                            + "웅크리면 멈추기(이동 키를 {stop_grace}초 넘게 떼기), 공중이면 점프, 그 밖에는 능력 사용이 "
                            + "금지됩니다. 결계 안의 적이 규칙을 어기거나 걸어 나가면 {barrier_damage} 피해와 "
                            + "{barrier_stagger}초 경직을 받습니다(적마다 최대 {barrier_max}번). 내가 어기면 결계가 깨집니다. "
                            + "결계 안에서 내가 주는 피해가 {barrier_bonus}% 늘어납니다.")
            .ultimateNotStandalone()
            .param("violator_duration", 6)
            .param("violator_bonus", 20)
            .param("violator_repeat", 1.5)
            .param("range", 18)
            .param("draw_seconds", 0.3)
            .param("taboo_duration", 4)
            .param("judge_grace", 0.6)
            .param("break_damage", 4)
            .param("break_stagger", 0.75)
            .param("keep_damage", 1)
            .param("barrier_radius", 9)
            .param("barrier_duration", 7)
            .param("stop_grace", 1)
            .param("barrier_damage", 3)
            .param("barrier_stagger", 0.5)
            .param("barrier_interval", 0.5)
            .param("barrier_max", 3)
            .param("barrier_bonus", 15)
            .build();

    // ------------------------------------------------------------------ look
    private static final String INK_TAG = "#151515";
    private static final String SEAL_TAG = "#C8231E";
    private static final String PAPER_TAG = "#F3EDDF";
    /** Paper white, vermilion, ink. */
    private static final Vfx.Palette SEAL = Vfx.Palette.of(0xFFD8C8, 0xC8231E, 0x3A0806);
    /** Paper white, wet grey ink, ink black. */
    private static final Vfx.Palette INK = Vfx.Palette.of(0xF3EDDF, 0x4A4642, 0x151515);
    private static final Color PAPER = Color.fromARGB(215, 0xF3, 0xED, 0xDF);
    private static final Color SEAL_PAPER = Color.fromARGB(230, 0xC8, 0x23, 0x1E);
    private static final Color BURNT_PAPER = Color.fromARGB(220, 0x5A, 0x10, 0x0C);
    private static final Particle.DustOptions INK_DUST = new Particle.DustOptions(Color.fromRGB(0x151515), 1.1f);
    private static final Particle.DustOptions SEAL_DUST = new Particle.DustOptions(Color.fromRGB(0xC8231E), 0.9f);
    private static final Particle.DustOptions PAPER_DUST = new Particle.DustOptions(Color.fromRGB(0xF3EDDF), 0.7f);
    private static final Particle.DustTransition SEAL_FADE = SEAL.fade(1.2f);
    private static final Particle.DustTransition INK_FADE = INK.fade(1.0f);

    private static final int MAX_DISPLAYS = 24;
    private static final double TAU = Math.PI * 2;
    private static final int RING_GLYPHS = 8;
    private static final double RING_RADIUS = 1.05;
    private static final float GLYPH_SCALE = 0.75f;
    private static final float SEAL_SCALE = 2.2f;
    private static final double GROUND_LIFT = 0.03;
    private static final String[] RING_TEXT = {"禁", "制", "律", "令"};
    private static final int BARRIER_SLIPS = 12;
    private static final String[] SLIP_TEXT = {"禁", "封", "止", "律"};
    private static final double MARK_ABOVE = 0.55;

    /** Cooldown rises above this from "ready" when a skill was just used. */
    private static final double READY = 0.05 + 1.0E-6;
    private static final double COOLDOWN_SET = 0.04;
    /** A gauge drop this large is an ultimate cast. */
    private static final double GAUGE_DROP = 50;
    /** How long a taboo waits for the target's screen before it is dropped. */
    private static final long PROMPT_WAIT = 20;
    /** A jump taboo on a target that never lands is judged from here on anyway. */
    private static final long LANDING_WAIT = 60;

    // ------------------------------------------------------------------ state
    private final List<Display> live = new ArrayList<>();
    /** Violators: until when, and the red mark over each. */
    private final Map<UUID, Long> violators = new HashMap<>();
    private final Map<UUID, TextDisplay> violatorMarks = new HashMap<>();
    private final Map<UUID, LivingEntity> violatorBodies = new HashMap<>();
    private @Nullable Barrier barrier;
    /** True while the taboo's own punishment is dealt: the violator bonus does not stack on it. */
    private boolean punishing;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        barrier = null;
        violators.clear();
        violatorMarks.clear();
        violatorBodies.clear();
        live.clear();
    }

    // ================================================================== passive: the price of a taboo

    private boolean isViolator(Entity entity) {
        Long until = violators.get(entity.getUniqueId());
        return until != null && until > ctx().currentTick();
    }

    /** Brands {@code target} a violator for {@link #INFO}'s violator duration (a red "犯" over its head). */
    private void brand(LivingEntity target) {
        long ticks = Math.max(1, params().ticks("violator_duration"));
        violators.put(target.getUniqueId(), ctx().currentTick() + ticks);
        violatorBodies.put(target.getUniqueId(), target);
        TextDisplay mark = violatorMarks.get(target.getUniqueId());
        if ((mark == null || !mark.isValid()) && reserve(1)) {
            Player owner = player();
            mark = keep(Displays.spawn(ctx(), owner, markSpot(target), TextDisplay.class, 0,
                    viewer -> !ctx().isConcealedFrom(target, viewer), d -> {
                        d.text(Text.mm("<b><" + SEAL_TAG + ">犯</" + SEAL_TAG + "></b>"));
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                        d.setShadowed(true);
                        d.setTeleportDuration(1);
                        d.setTransformation(Displays.centered(0, 0, 0, 0.2f, 0.2f, 0.2f, 0, 0, 0));
                    }));
            Displays.animateNextTick(ctx(), owner, mark, Displays.centered(0, 0, 0, 1.3f, 1.3f, 1.3f, 0, 0, 0), 4);
            violatorMarks.put(target.getUniqueId(), mark);
        }
    }

    private static Location markSpot(LivingEntity target) {
        return target.getLocation().add(0, target.getHeight() + MARK_ABOVE, 0);
    }

    @Override
    public void onTick(long tick) {
        long now = ctx().currentTick();
        Iterator<Map.Entry<UUID, Long>> it = violators.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            LivingEntity body = violatorBodies.get(entry.getKey());
            TextDisplay mark = violatorMarks.get(entry.getKey());
            boolean gone = body == null || !body.isValid() || body.isDead()
                    || (body instanceof Player p && !ctx().isAlive(p));
            if (entry.getValue() <= now || gone) {
                it.remove();
                violatorBodies.remove(entry.getKey());
                violatorMarks.remove(entry.getKey());
                if (mark != null && mark.isValid()) {
                    Displays.shrinkAway(ctx(), player(), mark, 4);
                }
                continue;
            }
            if (mark != null && mark.isValid()) {
                Displays.moveTo(mark, markSpot(body));
            }
            if (tick % 6 == 0 && !(body instanceof Player p && ctx().isConcealedFrom(p, player()))) {
                Location head = body.getLocation().add(0, body.getHeight() + 0.2, 0);
                Vfx.dot(head.clone().add(Fx.jitter(0.3f), 0, Fx.jitter(0.3f)), Particle.DUST, SEAL_DUST, false);
                Vfx.dot(head.clone().add(Fx.jitter(0.25f), -0.4, Fx.jitter(0.25f)), Particle.SQUID_INK, null, false);
            }
        }
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled()) {
            return;
        }
        double multiplier = 1;
        if (!punishing && isViolator(victim)) {
            multiplier += Math.max(0, params().num("violator_bonus")) / 100.0;
            Location at = Fx.body(victim);
            Vfx.cloud(ctx(), at, Particle.SQUID_INK, 3, 0.25, 0.02, null, false);
            Vfx.dot(at, Particle.DUST, SEAL_DUST, false);
        }
        Barrier current = barrier;
        if (!punishing && current != null && current.contains(player().getLocation())) {
            multiplier += Math.max(0, params().num("barrier_bonus")) / 100.0;
        }
        if (multiplier != 1) {
            event.setDamage(event.getDamage() * multiplier);
        }
    }

    /** The taboo's punishment: damage (more for a repeat violator), a stagger, and the violator's brand. */
    private void punish(Player owner, LivingEntity target, double damage, long stagger) {
        double amount = isViolator(target) ? damage * Math.max(1, params().num("violator_repeat")) : damage;
        punishing = true;
        try {
            Combat.hit(ctx(), owner, target, amount);
        } finally {
            punishing = false;
        }
        if (stagger > 0 && Combat.isTargetable(ctx(), owner, target)) {
            ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, stagger);
            ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, stagger);
        }
        if (target.isValid() && !target.isDead()) {
            brand(target);
        }
    }

    // ================================================================== active: the written taboo

    /** The three taboos the brush can write. */
    private enum Taboo {
        SPRINT("달리지 마라", "走"),
        JUMP("뛰지 마라", "跳"),
        STRIKE("때리지 마라", "打");

        final String words;
        final String glyph;

        Taboo(String words, String glyph) {
            this.words = words;
            this.glyph = glyph;
        }
    }

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, Math.max(1, params().num("range")));
        if (target == null) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        brushStroke(owner, target);
        new Writing(owner, target).start();
        return ActivationResult.success();
    }

    /** The brush swings: an ink arc in front of the caster and a vermilion stroke flying to the target. */
    private void brushStroke(Player owner, LivingEntity target) {
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        Location front = eye.clone().add(facing.clone().multiply(0.9)).add(0, -0.3, 0);
        Vfx.arc(ctx(), front, 0.8, owner.getLocation().getYaw(), 140, 14, Particle.DUST, INK_DUST, false);
        Vfx.arc(ctx(), front.clone().add(0, 0.12, 0), 0.7, owner.getLocation().getYaw(), 110, 10, Particle.DUST,
                SEAL_DUST, false);
        Location feet = target.getLocation().add(0, 0.2, 0);
        for (int i = 0; i < Vfx.n(ctx(), 6); i++) {
            Vfx.trail(front, feet.clone().add(Fx.jitter(0.6f), 0, Fx.jitter(0.6f)), Color.fromRGB(0x151515), 8, false);
        }
        Vfx.trail(front, feet, Color.fromRGB(0xC8231E), 7, true);
        Fx.sound(owner.getLocation(), Sound.ITEM_BRUSH_BRUSHING_GENERIC, 1.0f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(owner.getLocation(), Sound.ITEM_INK_SAC_USE, 0.9f, 0.7f);
        Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.4f);
    }

    /** One ring of ink letters: drawn, closed into a taboo, judged on the target's input until it ends. */
    private final class Writing implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final @Nullable Player enemy;
        private final long drawTicks;
        private final long duration;
        private final List<TextDisplay> glyphs = new ArrayList<>();
        private @Nullable TextDisplay seal;
        private @Nullable Taboo taboo;
        private @Nullable PromptHandle rule;
        private long closedAt = -1;
        private long landedAt = -1;
        private long mobEnd = Long.MAX_VALUE;
        private double phase;
        private boolean over;

        Writing(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.enemy = target instanceof Player p ? p : null;
            this.drawTicks = Math.max(1, params().ticks("draw_seconds"));
            this.duration = Math.max(1, params().ticks("taboo_duration"));
        }

        void start() {
            Loop.repeat(ctx(), owner, 0, 1, drawTicks + PROMPT_WAIT + duration + LANDING_WAIT + 20, this,
                    this::clear);
        }

        private Location ringCenter() {
            return target.getLocation().add(0, GROUND_LIFT, 0);
        }

        private Location glyphSpot(int index) {
            double angle = phase + index * TAU / RING_GLYPHS;
            return ringCenter().add(Math.cos(angle) * RING_RADIUS, 0, Math.sin(angle) * RING_RADIUS);
        }

        @Override
        public boolean test(long run) {
            if (over) {
                return false;
            }
            if (!target.isValid() || target.isDead() || (enemy != null && !ctx().isAlive(enemy))) {
                clear();
                return false;
            }
            if (taboo == null) {
                draw(run);
                if (run + 1 >= drawTicks) {
                    close();
                }
                return !over;
            }
            follow(run);
            long now = ctx().currentTick();
            if (enemy == null) {
                if (now >= mobEnd) {
                    honour();
                    return false;
                }
                return true;
            }
            PromptHandle handle = rule;
            if (handle == null || !handle.isActive()) {
                return !over;
            }
            if (handle.isShown() && broken(enemy, handle, now)) {
                breakTaboo();
                return false;
            }
            return true;
        }

        /** The brush lays the letters one by one around the feet. */
        private void draw(long run) {
            int due = (int) Math.min(RING_GLYPHS, Math.ceil((run + 1) * (double) RING_GLYPHS / drawTicks));
            while (glyphs.size() < due && reserve(1)) {
                int index = glyphs.size();
                Location at = glyphSpot(index);
                String text = RING_TEXT[index % RING_TEXT.length];
                float yaw = (float) Math.toDegrees(phase + index * TAU / RING_GLYPHS) + 90f;
                TextDisplay glyph = keep(Displays.spawn(ctx(), owner, at, TextDisplay.class, 0, d -> {
                    d.text(Text.mm("<b><" + INK_TAG + ">" + text + "</" + INK_TAG + "></b>"));
                    d.setBillboard(Display.Billboard.FIXED);
                    d.setBackgroundColor(PAPER);
                    d.setShadowed(false);
                    d.setTeleportDuration(1);
                    d.setTransformation(Displays.centered(0, 0, 0, 0.1f, 0.1f, 0.1f, yaw, -90, 0));
                }));
                Displays.animateNextTick(ctx(), owner, glyph, Displays.centered(0, 0, 0, GLYPH_SCALE, GLYPH_SCALE,
                        GLYPH_SCALE, yaw, -90, 0), 3);
                glyphs.add(glyph);
                Vfx.cloud(ctx(), at.clone().add(0, 0.1, 0), Particle.SQUID_INK, 2, 0.12, 0.01, null, false);
                Vfx.dot(at.clone().add(0, 0.15, 0), Particle.DUST, INK_DUST, false);
                Fx.sound(at, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 0.5f, 1.3f + index * 0.06f);
            }
        }

        /** The ring closes: the taboo is whatever the target is doing right now. */
        private void close() {
            closedAt = ctx().currentTick();
            Taboo kind;
            if (airborne(target)) {
                kind = Taboo.JUMP;
            } else if (enemy != null && (enemy.isSprinting()
                    || (Inputs.isHeld(enemy, InputKey.SPRINT) && Inputs.isWalking(enemy)))) {
                kind = Taboo.SPRINT;
            } else {
                kind = Taboo.STRIKE;
            }
            taboo = kind;
            Location center = ringCenter();
            if (reserve(1)) {
                TextDisplay stamp = keep(Displays.spawn(ctx(), owner, center, TextDisplay.class, 0, d -> {
                    d.text(Text.mm("<b><" + PAPER_TAG + ">禁</" + PAPER_TAG + "></b>"));
                    d.setBillboard(Display.Billboard.FIXED);
                    d.setBackgroundColor(SEAL_PAPER);
                    d.setShadowed(false);
                    d.setTeleportDuration(1);
                    d.setTransformation(Displays.centered(0, 0.6f, 0, SEAL_SCALE * 1.5f, SEAL_SCALE * 1.5f,
                            SEAL_SCALE * 1.5f, 0, -90, 0));
                }));
                Displays.animateNextTick(ctx(), owner, stamp, Displays.centered(0, 0, 0, SEAL_SCALE, SEAL_SCALE,
                        SEAL_SCALE, 0, -90, 0), 3);
                seal = stamp;
            }
            Vfx.ring(ctx(), center.clone().add(0, 0.05, 0), RING_RADIUS + 0.2, 20, 0, Particle.DUST_COLOR_TRANSITION,
                    SEAL_FADE, true);
            Vfx.cloud(ctx(), center.clone().add(0, 0.2, 0), Particle.SQUID_INK, 6, 0.5, 0.02, null, false);
            Fx.sound(center, Sound.BLOCK_ANVIL_LAND, 0.35f, 1.7f);
            Fx.sound(center, Sound.ENTITY_EVOKER_CAST_SPELL, 0.8f, 1.2f);
            Fx.sound(center, Sound.BLOCK_BELL_USE, 0.5f, 1.8f);
            if (enemy == null) {
                mobEnd = ctx().currentTick() + duration;
                return;
            }
            String line = "<b><" + SEAL_TAG + ">禁 " + kind.words + "</" + SEAL_TAG + "></b>";
            Prompt prompt = Prompt.builder(Text.mm(line + " <gray>— 어기면 글자가 터집니다 ("
                            + Fx.seconds(duration) + "초)</gray>"), duration)
                    .priority(Prompt.NORMAL)
                    .overhead(Text.mm("<b><" + SEAL_TAG + ">禁</" + SEAL_TAG + "> <white>" + kind.words
                            + "</white></b>"))
                    .maxWait(PROMPT_WAIT)
                    .maxPause(PROMPT_WAIT)
                    .onEnd(this::ended)
                    .build();
            rule = ctx().prompt(owner, enemy, prompt);
        }

        /** True once the target's own input broke the taboo (judged from the current showing on). */
        private boolean broken(Player victim, PromptHandle handle, long now) {
            long from = Inputs.judgeFrom(ctx(), handle, params().ticks("judge_grace"));
            Taboo kind = taboo;
            if (kind == Taboo.JUMP) {
                if (landedAt < 0) {
                    if (!airborne(victim) || now - closedAt >= LANDING_WAIT) {
                        landedAt = now;
                    } else {
                        return false;
                    }
                }
                from = Math.max(from, landedAt + ctx().reactionSlackTicks(victim));
                return Inputs.breaks(ctx(), victim, InputKey.JUMP, from, owner);
            }
            if (kind == Taboo.SPRINT) {
                return Inputs.breaks(ctx(), victim, InputKey.SPRINT, from, owner)
                        || (now >= from && victim.isSprinting() && !Inputs.isExempt(ctx(), victim, InputKey.SPRINT,
                        owner));
            }
            return Inputs.breaksAttack(ctx(), victim, from);
        }

        private void ended(PromptHandle handle, PromptEndReason reason) {
            if (over) {
                return;
            }
            if (reason == PromptEndReason.EXPIRED && target.isValid() && !target.isDead()) {
                honour();
            } else {
                clear();
            }
        }

        /** The letters follow the feet and turn slowly; ink drips from them. */
        private void follow(long run) {
            phase += 0.05;
            for (int i = 0; i < glyphs.size(); i++) {
                TextDisplay glyph = glyphs.get(i);
                if (glyph.isValid()) {
                    Displays.moveTo(glyph, glyphSpot(i));
                    if (run % 3 == 0) {
                        float yaw = (float) Math.toDegrees(phase + i * TAU / RING_GLYPHS) + 90f;
                        Displays.animate(glyph, Displays.centered(0, 0, 0, GLYPH_SCALE, GLYPH_SCALE, GLYPH_SCALE,
                                yaw, -90, 0), 3);
                    }
                }
            }
            TextDisplay stamp = seal;
            if (stamp != null && stamp.isValid()) {
                Displays.moveTo(stamp, ringCenter());
            }
            if (run % 4 == 0 && !(enemy != null && ctx().isConcealedFrom(enemy, owner))) {
                Location at = glyphSpot((int) (run / 4) % RING_GLYPHS).add(0, 0.1, 0);
                Vfx.dot(at, Particle.DUST, SEAL_DUST, false);
                Vfx.dot(ringCenter().add(Fx.jitter(0.9f), 0.05, Fx.jitter(0.9f)), Particle.SQUID_INK, null, false);
            }
        }

        /** The taboo is broken: the letters burst. */
        private void breakTaboo() {
            over = true;
            Location center = ringCenter();
            for (TextDisplay glyph : glyphs) {
                if (glyph.isValid()) {
                    glyph.setBackgroundColor(BURNT_PAPER);
                    glyph.text(Text.mm("<b><white>破</white></b>"));
                    Location at = glyph.getLocation();
                    Vfx.cloud(ctx(), at.clone().add(0, 0.2, 0), Particle.SQUID_INK, 3, 0.15, 0.08, null, false);
                    Displays.shrinkAway(ctx(), owner, glyph, 4);
                }
            }
            TextDisplay stamp = seal;
            if (stamp != null && stamp.isValid()) {
                Displays.animate(stamp, Displays.centered(0, 0.4f, 0, SEAL_SCALE * 1.6f, SEAL_SCALE * 1.6f,
                        SEAL_SCALE * 1.6f, 0, -90, 0), 2);
                ctx().runLater(owner, 3, () -> {
                    if (stamp.isValid()) {
                        Displays.shrinkAway(ctx(), owner, stamp, 3);
                    }
                });
            }
            PromptHandle handle = rule;
            if (handle != null) {
                handle.cancel();
            }
            Location body = Fx.body(target);
            Vfx.burst(ctx(), body, SEAL, 0.9, Particle.SQUID_INK, true);
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.1, 0), 0.3, 2.4, 5, Particle.DUST_COLOR_TRANSITION,
                    INK_FADE, false);
            Fx.sound(body, Sound.BLOCK_DECORATED_POT_SHATTER, 1.0f, 0.8f);
            Fx.sound(body, Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.8f, 1.3f);
            Fx.sound(body, Sound.ITEM_INK_SAC_USE, 1.0f, 0.5f);
            pop(owner, target, "<b><" + SEAL_TAG + ">금기 위반!</" + SEAL_TAG + "></b>", 1.4f);
            punish(owner, target, Math.max(0, params().num("break_damage")), params().ticks("break_stagger"));
        }

        /** The taboo was kept: the ink bleeds into the ground for a small price. */
        private void honour() {
            over = true;
            Location center = ringCenter();
            for (TextDisplay glyph : glyphs) {
                if (glyph.isValid()) {
                    Displays.animate(glyph, Displays.centered(0, 0, 0, GLYPH_SCALE * 1.4f, 0.02f, GLYPH_SCALE * 1.4f,
                            0, -90, 0), 6);
                    ctx().runLater(owner, 6, () -> {
                        if (glyph.isValid()) {
                            Displays.shrinkAway(ctx(), owner, glyph, 6);
                        }
                    });
                }
            }
            TextDisplay stamp = seal;
            if (stamp != null && stamp.isValid()) {
                Displays.shrinkAway(ctx(), owner, stamp, 8);
            }
            Vfx.ring(ctx(), center.clone().add(0, 0.05, 0), RING_RADIUS * 1.4, 18, 0, Particle.DUST, INK_DUST, false);
            Vfx.cloud(ctx(), center.clone().add(0, 0.1, 0), Particle.SQUID_INK, 5, 0.6, 0.01, null, false);
            Fx.sound(center, Sound.ITEM_INK_SAC_USE, 0.8f, 1.2f);
            Fx.sound(center, Sound.BLOCK_WOOL_PLACE, 0.6f, 0.7f);
            Combat.hit(ctx(), owner, target, Math.max(0, params().num("keep_damage")));
        }

        /** Gone without a verdict (the target left, the prompt was never seen): the letters simply fade. */
        private void clear() {
            if (over) {
                return;
            }
            over = true;
            for (TextDisplay glyph : glyphs) {
                if (glyph.isValid()) {
                    Displays.shrinkAway(ctx(), owner, glyph, 4);
                }
            }
            glyphs.clear();
            TextDisplay stamp = seal;
            if (stamp != null && stamp.isValid()) {
                Displays.shrinkAway(ctx(), owner, stamp, 4);
            }
            PromptHandle handle = rule;
            if (handle != null) {
                handle.cancel();
            }
        }
    }

    private static boolean airborne(LivingEntity entity) {
        return !Motion.isGrounded(entity) && !entity.isInWater()
                && !(entity instanceof Player p && (p.isFlying() || p.isGliding()));
    }

    // ================================================================== ultimate: the sealed ground

    /** The barrier's three rules, chosen by the caster's stance. */
    private enum Rule {
        KEEP_MOVING("멈추지 마라", "不止"),
        STAY_GROUNDED("땅을 떠나지 마라", "不離地"),
        NO_SKILLS("능력을 쓰지 마라", "禁術");

        final String words;
        final String glyph;

        Rule(String words, String glyph) {
            this.words = words;
            this.glyph = glyph;
        }
    }

    @Override
    public ActivationResult onUltimate() {
        if (barrier != null) {
            return ActivationResult.fail("이미 결계가 펼쳐져 있습니다");
        }
        Player owner = player();
        Rule rule = airborne(owner) ? Rule.STAY_GROUNDED : owner.isSneaking() ? Rule.KEEP_MOVING : Rule.NO_SKILLS;
        Barrier sealed = new Barrier(owner, rule);
        barrier = sealed;
        sealed.raise();
        return ActivationResult.success();
    }

    /** How one judged player (an enemy or the caster) stands with the barrier's rule. */
    private static final class Judged {
        final Player player;
        final @Nullable PromptHandle handle;
        long lastMove;
        long lastPunish = Long.MIN_VALUE / 2;
        int punished;
        double lastCooldown;
        double lastGauge;
        long lastJudgedJump = Long.MIN_VALUE / 2;
        boolean baselined;

        Judged(Player player, @Nullable PromptHandle handle, long now, double cooldown, double gauge) {
            this.player = player;
            this.handle = handle;
            this.lastMove = now;
            this.lastCooldown = cooldown;
            this.lastGauge = gauge;
        }
    }

    /** The barrier: a circle of paper charms, its rule on a hanging scroll and on everyone's screen. */
    private final class Barrier implements LongPredicate {

        private final Player owner;
        private final Rule rule;
        private final Location center;
        private final double radius;
        private final long duration;
        private final List<TextDisplay> slips = new ArrayList<>();
        private @Nullable TextDisplay scroll;
        private final Map<UUID, Judged> judged = new LinkedHashMap<>();
        private @Nullable Judged self;
        private boolean over;

        Barrier(Player owner, Rule rule) {
            this.owner = owner;
            this.rule = rule;
            Location feet = owner.getLocation();
            this.center = new Location(feet.getWorld(), feet.getX(), feet.getY(), feet.getZ());
            if (airborne(owner)) {
                Spots.snapToGround(center.getWorld(), center, 6);
            }
            this.radius = Math.max(2, params().num("barrier_radius"));
            this.duration = Math.max(1, params().ticks("barrier_duration"));
        }

        boolean contains(Location at) {
            if (over || !at.getWorld().equals(center.getWorld())) {
                return false;
            }
            double dx = at.getX() - center.getX();
            double dz = at.getZ() - center.getZ();
            return dx * dx + dz * dz <= radius * radius && Math.abs(at.getY() - center.getY()) <= radius;
        }

        void raise() {
            for (int i = 0; i < BARRIER_SLIPS && reserve(1); i++) {
                Location at = slipSpot(i, 0);
                String text = SLIP_TEXT[i % SLIP_TEXT.length];
                TextDisplay slip = keep(Displays.spawn(ctx(), owner, at.clone().add(0, -1.2, 0), TextDisplay.class, 0,
                        d -> {
                            d.text(Text.mm("<b><" + SEAL_TAG + ">" + text + "</" + SEAL_TAG + "></b>\n<" + INK_TAG
                                    + ">" + rule.glyph + "</" + INK_TAG + ">"));
                            d.setBillboard(Display.Billboard.VERTICAL);
                            d.setBackgroundColor(PAPER);
                            d.setShadowed(false);
                            d.setTeleportDuration(3);
                            d.setTransformation(Displays.centered(0, 0, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0));
                        }));
                slips.add(slip);
                int delay = 1 + i / 2;
                ctx().runLater(owner, delay, () -> Displays.moveTo(slip, at));
            }
            if (reserve(1)) {
                Location top = center.clone().add(0, 3.8, 0);
                TextDisplay hanging = keep(Displays.spawn(ctx(), owner, top, TextDisplay.class, 0, d -> {
                    d.text(Text.mm(scrollText(duration)));
                    d.setBillboard(Display.Billboard.VERTICAL);
                    d.setBackgroundColor(PAPER);
                    d.setShadowed(false);
                    d.setTransformation(Displays.centered(0, 0, 0, 0.2f, 0.2f, 0.2f, 0, 0, 0));
                }));
                Displays.animateNextTick(ctx(), owner, hanging, Displays.centered(0, 0, 0, 2.0f, 2.0f, 2.0f, 0, 0, 0),
                        6);
                scroll = hanging;
            }
            Location ground = center.clone().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, ground, 0.5, radius, 8, Particle.DUST_COLOR_TRANSITION, SEAL_FADE, true);
            Vfx.burst(ctx(), owner.getLocation().add(0, 1, 0), SEAL, 1.2, Particle.SQUID_INK, true);
            Fx.sound(center, Sound.BLOCK_BELL_USE, 1.2f, 0.6f);
            Fx.sound(center, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 0.7f);
            Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
            Fx.sound(center, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 0.6f);
            ctx().announce(Text.mm("<b><" + SEAL_TAG + ">結界</" + SEAL_TAG + "></b> <white>금제 결계의 규칙: "
                    + rule.words + "</white> <gray>(" + Fx.seconds(duration) + "초)</gray>"));
            long now = ctx().currentTick();
            self = new Judged(owner, promptFor(owner, true), now, ctx().cooldownRemaining(owner), ctx().gauge(owner));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, () -> end(false));
        }

        private String scrollText(long left) {
            return "<b><" + SEAL_TAG + ">結界 · " + rule.glyph + "</" + SEAL_TAG + "></b>\n<" + INK_TAG + "><b>"
                    + rule.words + "</b></" + INK_TAG + ">\n<#5A5047>" + Fx.seconds(left) + "초</#5A5047>";
        }

        private Location slipSpot(int index, double bob) {
            double angle = index * TAU / BARRIER_SLIPS;
            return center.clone().add(Math.cos(angle) * radius, 1.3 + bob, Math.sin(angle) * radius);
        }

        private @Nullable PromptHandle promptFor(Player target, boolean caster) {
            long left = Math.max(1, duration - (ctx().currentTick() - startTick()));
            String head = "<b><" + SEAL_TAG + ">結界 — " + rule.words + "</" + SEAL_TAG + "></b>";
            String tail = caster ? " <gray>— 나도 어기면 결계가 깨집니다</gray>"
                    : " <gray>— 어기거나 걸어 나가면 벌을 받습니다</gray>";
            Prompt prompt = Prompt.builder(Text.mm(head + tail), left)
                    .priority(Prompt.HIGH)
                    .overhead(caster ? null : Text.mm("<b><" + SEAL_TAG + ">結界</" + SEAL_TAG + "> <white>"
                            + rule.words + "</white></b>"))
                    .reactionSlack(false)
                    .maxWait(PROMPT_WAIT)
                    .build();
            return ctx().prompt(owner, target, prompt);
        }

        private long started = -1;

        private long startTick() {
            if (started < 0) {
                started = ctx().currentTick();
            }
            return started;
        }

        @Override
        public boolean test(long run) {
            if (over) {
                return false;
            }
            startTick();
            long now = ctx().currentTick();
            visuals(run);
            Judged caster = self;
            if (caster != null && breaks(caster, now)) {
                end(true);
                return false;
            }
            for (LivingEntity entity : ctx().enemiesNear(owner, center, radius + 1)) {
                if (!(entity instanceof Player enemy) || judged.containsKey(enemy.getUniqueId())
                        || !contains(enemy.getLocation()) || ctx().isConcealedFrom(enemy, owner)) {
                    continue;
                }
                judged.put(enemy.getUniqueId(), new Judged(enemy, promptFor(enemy, false), now,
                        ctx().cooldownRemaining(enemy), ctx().gauge(enemy)));
            }
            Iterator<Judged> it = judged.values().iterator();
            while (it.hasNext()) {
                Judged entry = it.next();
                Player enemy = entry.player;
                if (!ctx().isAlive(enemy) || !ctx().isEnemy(owner, enemy)) {
                    cancel(entry);
                    it.remove();
                    continue;
                }
                if (!contains(enemy.getLocation())) {
                    if (!Inputs.isExempt(ctx(), enemy, InputKey.FORWARD, owner) && shown(entry)) {
                        violate(entry, now, "결계 이탈!");
                    }
                    cancel(entry);
                    it.remove();
                    continue;
                }
                if (breaks(entry, now)) {
                    violate(entry, now, "결계 위반!");
                }
            }
            return true;
        }

        private boolean shown(Judged entry) {
            PromptHandle handle = entry.handle;
            return handle != null && handle.isShown();
        }

        /** True when {@code entry} broke the rule this tick (judged from its rule's current showing on). */
        private boolean breaks(Judged entry, long now) {
            PromptHandle handle = entry.handle;
            Player target = entry.player;
            double cooldown = ctx().cooldownRemaining(target);
            double gauge = ctx().gauge(target);
            // The first look only takes the baseline: the caster's own gauge empties right after the cast.
            boolean cast = entry.baselined && ((entry.lastCooldown <= READY
                    && cooldown > entry.lastCooldown + COOLDOWN_SET) || entry.lastGauge - gauge >= GAUGE_DROP);
            entry.baselined = true;
            entry.lastCooldown = cooldown;
            entry.lastGauge = gauge;
            if (handle == null || !handle.isShown()) {
                entry.lastMove = now;
                return false;
            }
            long from = Inputs.judgeFrom(ctx(), handle, 0);
            if (now < from) {
                entry.lastMove = now;
                return false;
            }
            return switch (rule) {
                case KEEP_MOVING -> {
                    if (Inputs.walkedSince(ctx(), target, now)
                            || Inputs.isExempt(ctx(), target, InputKey.FORWARD, target.equals(owner) ? null : owner)) {
                        entry.lastMove = now;
                        yield false;
                    }
                    long allowed = params().ticks("stop_grace") + ctx().reactionSlackTicks(target);
                    if (now - entry.lastMove > allowed) {
                        entry.lastMove = now;
                        yield true;
                    }
                    yield false;
                }
                case STAY_GROUNDED -> {
                    long since = Math.max(from, entry.lastJudgedJump + 1);
                    boolean jumped = Inputs.breaks(ctx(), target, InputKey.JUMP, since,
                            target.equals(owner) ? null : owner);
                    if (jumped) {
                        entry.lastJudgedJump = now;
                    }
                    yield jumped;
                }
                case NO_SKILLS -> cast;
            };
        }

        /** An enemy broke the rule (or walked out): a vermilion bolt from the nearest charm, the punishment. */
        private void violate(Judged entry, long now, String line) {
            if (entry.punished >= Math.max(0, params().integer("barrier_max"))
                    || now - entry.lastPunish < params().ticks("barrier_interval")) {
                return;
            }
            entry.punished++;
            entry.lastPunish = now;
            Player enemy = entry.player;
            Location body = Fx.body(enemy);
            Location from = nearestSlip(body);
            Vfx.line(ctx(), from, body, 4, Particle.DUST_COLOR_TRANSITION, SEAL_FADE, true);
            Vfx.burst(ctx(), body, SEAL, 0.7, Particle.SQUID_INK, false);
            Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.5f, 1.5f);
            Fx.sound(body, Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.8f, 1.1f);
            Fx.sound(from, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.6f);
            pop(owner, enemy, "<b><" + SEAL_TAG + ">" + line + "</" + SEAL_TAG + "></b>", 1.2f);
            punish(owner, enemy, Math.max(0, params().num("barrier_damage")), params().ticks("barrier_stagger"));
        }

        private Location nearestSlip(Location to) {
            Location best = center.clone().add(0, 1.3, 0);
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < BARRIER_SLIPS; i++) {
                Location spot = slipSpot(i, 0);
                double distance = spot.distanceSquared(to);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = spot;
                }
            }
            return best;
        }

        private void visuals(long run) {
            for (int i = 0; i < slips.size(); i++) {
                TextDisplay slip = slips.get(i);
                if (slip.isValid() && run > 2 + i / 2 && run % 4 == i % 4) {
                    Displays.moveTo(slip, slipSpot(i, Math.sin(run * 0.12 + i) * 0.12));
                }
            }
            TextDisplay hanging = scroll;
            if (hanging != null && hanging.isValid() && run % 5 == 0) {
                hanging.text(Text.mm(scrollText(Math.max(0, duration - run))));
            }
            if (run % 4 == 0) {
                Location ground = center.clone().add(0, 0.12, 0);
                Vfx.ring(ctx(), ground, radius, (int) Math.round(radius * 5), run * 0.02, Particle.DUST, INK_DUST,
                        false);
                Vfx.ring(ctx(), ground, radius - 0.35, (int) Math.round(radius * 3), -run * 0.03, Particle.DUST,
                        SEAL_DUST, false);
            }
            for (int i = 0; i < Vfx.n(ctx(), 8); i++) {
                double angle = Math.random() * TAU;
                double height = Math.random() * 3.5;
                Location wall = center.clone().add(Math.cos(angle) * radius, 0.2 + height, Math.sin(angle) * radius);
                Vfx.dot(wall, Particle.DUST, PAPER_DUST, false);
            }
            if (run % 10 == 0) {
                Vfx.cloud(ctx(), center.clone().add(0, 3.0, 0), Particle.WHITE_ASH, 6, 1.0, 0.01, null, false);
            }
        }

        private void cancel(Judged entry) {
            PromptHandle handle = entry.handle;
            if (handle != null) {
                handle.cancel();
            }
        }

        /** The barrier lifts (its time ran out) or shatters (its caster broke its own rule). */
        void end(boolean shattered) {
            if (over) {
                return;
            }
            over = true;
            if (barrier == this) {
                barrier = null;
            }
            for (Judged entry : judged.values()) {
                cancel(entry);
            }
            judged.clear();
            Judged caster = self;
            if (caster != null) {
                cancel(caster);
            }
            for (TextDisplay slip : slips) {
                if (!slip.isValid()) {
                    continue;
                }
                if (shattered) {
                    slip.setBackgroundColor(BURNT_PAPER);
                    slip.text(Text.mm("<b><white>破</white></b>"));
                    Vfx.dot(slip.getLocation(), Particle.SMALL_FLAME, null, false);
                    ctx().runLater(owner, 5, () -> {
                        if (slip.isValid()) {
                            Vfx.dot(slip.getLocation(), Particle.SMOKE, null, false);
                            Displays.shrinkAway(ctx(), owner, slip, 4);
                        }
                    });
                } else {
                    slip.setTeleportDuration(10);
                    Displays.moveTo(slip, slip.getLocation().add(0, 2.5, 0));
                    ctx().runLater(owner, 8, () -> {
                        if (slip.isValid()) {
                            Displays.shrinkAway(ctx(), owner, slip, 5);
                        }
                    });
                }
            }
            TextDisplay hanging = scroll;
            if (hanging != null && hanging.isValid()) {
                if (shattered) {
                    hanging.text(Text.mm("<b><" + SEAL_TAG + ">結界 붕괴!</" + SEAL_TAG + "></b>"));
                }
                ctx().runLater(owner, shattered ? 10 : 2, () -> {
                    if (hanging.isValid()) {
                        Displays.shrinkAway(ctx(), owner, hanging, 5);
                    }
                });
            }
            Location ground = center.clone().add(0, 0.2, 0);
            if (shattered) {
                Vfx.shockwave(ctx(), owner, ground, radius, 0.5, 6, Particle.DUST_COLOR_TRANSITION, SEAL_FADE, true);
                Fx.sound(center, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.6f);
                Fx.sound(center, Sound.ENTITY_GENERIC_BURN, 1.0f, 0.8f);
                owner.sendActionBar(Text.mm("<b><" + SEAL_TAG + ">결계 붕괴!</" + SEAL_TAG + "></b> <gray>스스로 "
                        + "규칙을 어겼습니다</gray>"));
                ctx().announce(Text.mm("<" + SEAL_TAG + ">結界</" + SEAL_TAG + "> <gray>금제술사가 스스로 규칙을 어겨 "
                        + "결계가 깨졌습니다</gray>"));
            } else {
                Vfx.ring(ctx(), ground, radius, 40, 0, Particle.WHITE_ASH, null, false);
                Fx.sound(center, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.2f);
                Fx.sound(center, Sound.BLOCK_BELL_RESONATE, 0.6f, 1.6f);
            }
        }
    }

    // ================================================================== helpers

    /** A word popping up over {@code target}'s head and fading (hidden from those who cannot see it). */
    private void pop(Player owner, LivingEntity target, String mini, float scale) {
        if (!reserve(1)) {
            return;
        }
        Location at = target.getLocation().add(0, target.getHeight() + 1.6, 0);
        TextDisplay text = keep(Displays.spawn(ctx(), owner, at, TextDisplay.class, 24,
                viewer -> !ctx().isConcealedFrom(target, viewer), d -> {
                    d.text(Text.mm(mini));
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    d.setShadowed(true);
                    d.setTransformation(Displays.centered(0, 0, 0, 0.3f, 0.3f, 0.3f, 0, 0, 0));
                }));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, 16, () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 5);
            }
        });
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
