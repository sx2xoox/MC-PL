package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** 보물 탐지기: now and then, nearby diamond, gold and iron ores glow through walls for the owner alone. */
public final class TreasureSenseAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("treasure_sense", "보물 탐지기", AugmentTier.GOLD,
                    AugmentKind.SPECIAL, Material.SPYGLASS)
            .description("{interval}초마다 반경 {radius}블록 안의 다이아몬드·금·철 광석이 {duration}초 동안 벽 너머로 빛나"
                    + " 보입니다 (나에게만, 가까운 {max}개).")
            .param("interval", 10)
            .param("radius", 12)
            .param("duration", 4)
            .param("max", 40)
            .build();

    private record Mark(BlockDisplay display, long until) {
    }

    private final AugmentContext ctx;
    private final List<Mark> marks = new ArrayList<>();
    private long nextScan;

    public TreasureSenseAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        nextScan = ctx.tick();
    }

    @Override
    public void onTick() {
        long now = ctx.tick();
        for (Iterator<Mark> it = marks.iterator(); it.hasNext(); ) {
            Mark mark = it.next();
            if (now >= mark.until() || !mark.display().isValid()) {
                mark.display().remove();
                it.remove();
            }
        }
        if (now >= nextScan) {
            nextScan = now + Math.max(20L, ctx.params().ticks("interval"));
            scan(now);
        }
    }

    /** Marks the nearest valuable ores around the owner. */
    private void scan(long now) {
        Player player = ctx.player();
        Location center = player.getLocation();
        World world = center.getWorld();
        int radius = Math.max(0, ctx.params().integer("radius"));
        int max = ctx.params().integer("max");
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        int minY = Math.max(world.getMinHeight(), cy - radius);
        int maxY = Math.min(world.getMaxHeight() - 1, cy + radius);
        List<Block> found = new ArrayList<>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (glow(block.getType()) != null) {
                        found.add(block);
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(block -> block.getLocation().add(0.5, 0.5, 0.5).distanceSquared(center)));
        long until = now + Math.max(1L, ctx.params().ticks("duration"));
        for (Block block : found.subList(0, Math.max(0, Math.min(max, found.size())))) {
            Color color = glow(block.getType());
            BlockDisplay display = world.spawn(block.getLocation(), BlockDisplay.class, d -> {
                d.setBlock(block.getBlockData());
                d.setTransformation(new Transformation(new Vector3f(0.1f, 0.1f, 0.1f), new AxisAngle4f(),
                        new Vector3f(0.8f, 0.8f, 0.8f), new AxisAngle4f()));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setGlowColorOverride(color);
                d.setGlowing(true);
                d.setPersistent(false);
                ctx.showOnlyToOwner(d);
            });
            marks.add(new Mark(ctx.track(display), until));
        }
    }

    /** The outline color of a valuable ore, or null for anything else. */
    static @Nullable Color glow(Material type) {
        return switch (type) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> Color.AQUA;
            case GOLD_ORE, DEEPSLATE_GOLD_ORE -> Color.YELLOW;
            case IRON_ORE, DEEPSLATE_IRON_ORE -> Color.fromRGB(0xD8AF93);
            default -> null;
        };
    }

    /** Ores marked right now (tests). */
    public int marked() {
        return marks.size();
    }
}
