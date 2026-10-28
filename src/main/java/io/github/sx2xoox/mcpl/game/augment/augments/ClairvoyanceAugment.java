package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 천리안: during the fight, every few seconds every enemy player is outlined for a moment, through walls, for the
 * owner alone. The outline is a glowing display box that follows the enemy and is shown only to the owner (a real
 * glowing effect would be seen by everyone).
 */
public final class ClairvoyanceAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("clairvoyance", "천리안", AugmentTier.PRISM,
                    AugmentKind.SPECIAL, Material.SPYGLASS)
            .description("PvP가 켜진 뒤 {interval}초마다 모든 적 플레이어가 {glow}초 동안 벽 너머로 빛나 보입니다 (나에게만).")
            .param("interval", 20)
            .param("glow", 3)
            .build();

    private static final Transformation BOX = new Transformation(new Vector3f(-0.35f, 0f, -0.35f),
            new AxisAngle4f(), new Vector3f(0.7f, 1.9f, 0.7f), new AxisAngle4f());

    private record Mark(Player target, BlockDisplay box, long until) {
    }

    private final AugmentContext ctx;
    private final List<Mark> marks = new ArrayList<>();
    private long nextAt = -1;

    public ClairvoyanceAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    /** The outlines shown right now (tests). */
    public List<BlockDisplay> outlines() {
        List<BlockDisplay> out = new ArrayList<>();
        for (Mark mark : marks) {
            out.add(mark.box());
        }
        return out;
    }

    @Override
    public void onTick() {
        follow();
        if (!ctx.pvp()) {
            return;
        }
        long now = ctx.tick();
        if (nextAt < 0 || now >= nextAt) {
            nextAt = now + Math.max(1, ctx.params().ticks("interval"));
            reveal();
        }
    }

    private void reveal() {
        long until = ctx.tick() + Math.max(1, ctx.params().ticks("glow"));
        List<Player> enemies = Nearby.enemyPlayers(ctx);
        for (Player enemy : enemies) {
            BlockDisplay box = enemy.getWorld().spawn(enemy.getLocation(), BlockDisplay.class, display -> {
                display.setVisibleByDefault(false);
                display.setBlock(Material.RED_STAINED_GLASS.createBlockData());
                display.setTransformation(BOX);
                display.setGlowing(true);
                display.setGlowColorOverride(Color.fromRGB(0xFF, 0x40, 0x40));
                display.setBrightness(new Display.Brightness(15, 15));
                display.setTeleportDuration(1);
                display.setPersistent(false);
            });
            marks.add(new Mark(enemy, ctx.trackForOwner(box), until));
        }
        if (!enemies.isEmpty()) {
            Player owner = ctx.player();
            owner.playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.4f);
        }
    }

    /** Moves every outline onto its enemy; removes the ones that ran out or lost their enemy. */
    private void follow() {
        long now = ctx.tick();
        marks.removeIf(mark -> {
            Player target = mark.target();
            boolean over = now >= mark.until() || !mark.box().isValid() || !ctx.isEnemy(target)
                    || target.getWorld() != mark.box().getWorld();
            if (over) {
                mark.box().remove();
                return true;
            }
            Location at = target.getLocation();
            at.setYaw(0f);
            at.setPitch(0f);
            mark.box().teleport(at);
            return false;
        });
    }

    @Override
    public void onRemove() {
        for (Mark mark : marks) {
            mark.box().remove();
        }
        marks.clear();
    }
}
