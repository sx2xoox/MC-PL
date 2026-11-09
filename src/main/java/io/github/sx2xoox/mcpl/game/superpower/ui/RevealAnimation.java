package io.github.sx2xoox.mcpl.game.superpower.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The per-player slot machine: titles cycle through ability names (fast at first, slowing down, with a tick sound per
 * frame), then the result lands as a bold colored title with the active and ultimate names as subtitle and a
 * level-up sound. Driven by {@link #tick} once per server tick.
 */
public final class RevealAnimation {

    private static final double FIRST_INTERVAL = 2.0;
    private static final double GROWTH = 1.17;

    private final AbilityInfo result;
    private final List<AbilityInfo> reel;
    private final int totalTicks;
    private final int[] frames;
    private final Random random;
    private int age = -1;
    private int nextFrame;
    private @Nullable AbilityInfo shown;
    private boolean done;

    private RevealAnimation(AbilityInfo result, List<AbilityInfo> reel, int totalTicks, Random random) {
        this.result = result;
        this.reel = List.copyOf(reel);
        this.totalTicks = Math.max(0, totalTicks);
        this.random = random;
        this.frames = schedule(this.totalTicks);
    }

    /** A slot machine lasting {@code totalTicks} that cycles through {@code reel} and lands on {@code result}. */
    public static RevealAnimation spin(AbilityInfo result, List<AbilityInfo> reel, int totalTicks, Random random) {
        return new RevealAnimation(result, reel, totalTicks, random);
    }

    /** Shows the result on the first tick (no spinning). */
    public static RevealAnimation instant(AbilityInfo result) {
        return new RevealAnimation(result, List.of(), 0, new Random());
    }

    /** Frame start ticks: intervals grow from 2 ticks by 17% per frame, so the reel visibly slows down. */
    static int[] schedule(int totalTicks) {
        List<Integer> out = new ArrayList<>();
        double interval = FIRST_INTERVAL;
        int at = 0;
        while (at < totalTicks) {
            out.add(at);
            at += Math.max(1, (int) Math.round(interval));
            interval *= GROWTH;
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    public AbilityInfo result() {
        return result;
    }

    public boolean isDone() {
        return done;
    }

    /**
     * Advances one tick and shows the frame due now.
     *
     * @return true on the tick the final result is shown
     */
    public boolean tick(Player player, Messages messages) {
        if (done) {
            return false;
        }
        age++;
        if (age >= totalTicks) {
            showResult(player, messages);
            return true;
        }
        if (nextFrame < frames.length && age >= frames[nextFrame]) {
            int stay = nextFrame + 1 < frames.length ? frames[nextFrame + 1] - frames[nextFrame] : totalTicks - age;
            showFrame(player, messages, (double) age / Math.max(1, totalTicks), stay);
            nextFrame++;
        }
        return false;
    }

    /** Jumps to the result right away (e.g. the phase ended early). */
    public void complete(Player player, Messages messages) {
        if (!done) {
            showResult(player, messages);
        }
    }

    private void showFrame(Player player, Messages messages, double progress, int stayTicks) {
        AbilityInfo next = pickFrame();
        shown = next;
        Feedback.title(player, messages.get("reveal.spin-title", Messages.tag("ability", AbilityTexts.boldName(next))),
                messages.get("reveal.spin-subtitle"), 0, stayTicks + 4, 0);
        Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, (float) (0.8 + progress * 1.0));
    }

    private AbilityInfo pickFrame() {
        if (reel.isEmpty()) {
            return result;
        }
        if (reel.size() == 1) {
            return reel.getFirst();
        }
        AbilityInfo pick;
        do {
            pick = reel.get(random.nextInt(reel.size()));
        } while (pick == shown);
        return pick;
    }

    private void showResult(Player player, Messages messages) {
        done = true;
        Component title = messages.get("reveal.final-title", Messages.tag("ability", AbilityTexts.boldName(result)));
        Component subtitle = messages.get("reveal.final-subtitle",
                Messages.tag("active", Component.text(result.active().name(), result.color())),
                Messages.tag("ultimate", Component.text(result.ultimate().name(), result.color())));
        Feedback.title(player, title, subtitle, 0, 50, 15);
        Feedback.play(player, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
    }
}
