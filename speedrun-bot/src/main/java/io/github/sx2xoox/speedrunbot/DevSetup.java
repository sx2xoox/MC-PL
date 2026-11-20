package io.github.sx2xoox.speedrunbot;

import io.github.sx2xoox.speedrunbot.server.FakePlayer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.Heightmap;

/**
 * Development-only setups for testing one stage of the run in isolation (never used by measurement runs; a result
 * written with one carries a "dev" field). {@code srb.kit=item:count,...} puts items in the bot's inventory at the
 * start; {@code srb.devStart=lava} teleports the bot next to a lava pool the harness finds by reading the world
 * (a test fixture, not bot knowledge), {@code srb.devStart=x,y,z} to a given spot.
 */
public final class DevSetup {
    private static String applied = "";

    private DevSetup() {
    }

    public static boolean active() {
        return !applied.isEmpty();
    }

    public static String describe() {
        return applied;
    }

    /**
     * Seed classification for the harness (never bot input): distances from the spawn to the nearest shipwreck,
     * ruined portal and village by the structure locator, so eval seeds can be grouped (e.g. "ocean spawns").
     */
    public static String probe(MinecraftServer server, BlockPos spawn) {
        ServerWorld w = server.getOverworld();
        StringBuilder sb = new StringBuilder("{");
        net.minecraft.world.gen.feature.StructureFeature<?>[] fs = {net.minecraft.world.gen.feature.StructureFeature.SHIPWRECK,
                net.minecraft.world.gen.feature.StructureFeature.RUINED_PORTAL, net.minecraft.world.gen.feature.StructureFeature.VILLAGE,
                net.minecraft.world.gen.feature.StructureFeature.BURIED_TREASURE};
        String[] names = {"shipwreck", "ruined_portal", "village", "buried_treasure"};
        for (int i = 0; i < fs.length; i++) {
            BlockPos q = w.locateStructure(fs[i], spawn, 8, false);
            double d = q == null ? -1 : Math.sqrt(Math.pow(q.getX() - spawn.getX(), 2) + Math.pow(q.getZ() - spawn.getZ(), 2));
            sb.append('"').append(names[i]).append("\":").append(Math.round(d)).append(',');
            if (q != null) {
                sb.append('"').append(names[i]).append("_at\":\"").append(q.getX()).append(',').append(q.getZ()).append("\",");
            }
        }
        BlockPos rav = findMagmaRavine(w, spawn, 10);
        if (rav != null) {
            sb.append("\"magma_ravine\":").append(Math.round(Math.sqrt(Math.pow(rav.getX() - spawn.getX(), 2) + Math.pow(rav.getZ() - spawn.getZ(), 2))))
                    .append(",\"magma_ravine_at\":\"").append(rav.getX()).append(',').append(rav.getZ()).append("\",");
        } else {
            sb.append("\"magma_ravine\":-1,");
        }
        sb.append("\"biome\":\"").append(net.minecraft.util.registry.Registry.BIOME.getId(w.getBiome(spawn))).append("\"}");
        return sb.toString();
    }

    public static void apply(MinecraftServer server, FakePlayer p) {
        String kit = System.getProperty("srb.kit", "");
        if (!kit.isEmpty()) {
            for (String part : kit.split(",")) {
                String[] kv = part.trim().split(":");
                Item item = Registry.ITEM.get(new Identifier(kv[0]));
                int n = kv.length > 1 ? Integer.parseInt(kv[1]) : 1;
                while (n > 0) {
                    int k = Math.min(n, item.getMaxCount());
                    p.inventory.insertStack(new ItemStack(item, k));
                    n -= k;
                }
            }
            applied += "kit=" + kit + ";";
        }
        String start = System.getProperty("srb.devStart", "");
        if (start.isEmpty()) {
            return;
        }
        ServerWorld w = server.getOverworld();
        BlockPos target = null;
        if (start.equals("lava") || start.equals("lavalow")) {
            target = findLava(w, p.getBlockPos(), start.equals("lavalow"));
        } else if (start.equals("ravine") || start.equals("ravinefloor")) {
            // test fixture: above (or at the floor of) the nearest underwater magma ravine (harness reads the world)
            BlockPos rav = findMagmaRavine(w, p.getBlockPos(), 12);
            if (rav != null) {
                target = start.equals("ravine") ? new BlockPos(rav.getX(), 63, rav.getZ()) : rav.up();
                applied += "ravine=" + rav.getX() + "," + rav.getZ() + ";";
                start = "shipx"; // no pocket clearing
            }
        } else if (start.equals("ship")) {
            // test fixture: next to the nearest shipwreck (the structure locator is harness-only)
            BlockPos q = w.locateStructure(net.minecraft.world.gen.feature.StructureFeature.SHIPWRECK, p.getBlockPos(), 8, false);
            if (q != null) {
                w.getChunk(q.getX() >> 4, q.getZ() >> 4);
                int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, q.getX() + 8, q.getZ() + 8);
                target = new BlockPos(q.getX() + 8, top, q.getZ() + 8);
                applied += "ship=" + q.getX() + "," + q.getZ() + ";";
                // fixture report: where the wreck's planks really are (to check what the bot can see)
                int found = 0;
                BlockPos any = null;
                for (BlockPos r : BlockPos.iterate(q.add(-8, 30, -8), q.add(40, 75, 40))) {
                    if (w.getBlockState(r).isIn(net.minecraft.tag.BlockTags.PLANKS) || w.getBlockState(r).isIn(net.minecraft.tag.BlockTags.WOODEN_STAIRS)) {
                        found++;
                        if (any == null) {
                            any = r.toImmutable();
                        }
                    }
                }
                applied += "wreckwood=" + found + "@" + any + ";";
            }
            start = "shipx";
        } else {
            String[] xyz = start.split(",");
            target = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
        }
        if (target != null && !start.startsWith("lava") && !start.equals("shipx")) {
            // a fixed test spot may be inside rock: clear a pocket (test fixture only)
            w.getChunk(target.getX() >> 4, target.getZ() >> 4);
            w.setBlockState(target, Blocks.AIR.getDefaultState());
            w.setBlockState(target.up(), Blocks.AIR.getDefaultState());
        }
        if (target != null) {
            p.teleport(w, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, p.yaw, p.pitch);
            applied += "start=" + target.getX() + "," + target.getY() + "," + target.getZ() + ";";
        } else {
            applied += "start=none;";
        }
    }

    /**
     * Nearest magma block at y 10 (an underwater ravine/cave floor, see UnderwaterCaveCarver) with open water above it up
     * to the sea (a magma ravine: bubble columns reach the surface), within r chunks; harness-only (reads the world).
     */
    public static BlockPos findMagmaRavine(ServerWorld w, BlockPos from, int r) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        int cx0 = from.getX() >> 4, cz0 = from.getZ() >> 4;
        for (int cx = cx0 - r; cx <= cx0 + r; cx++) {
            for (int cz = cz0 - r; cz <= cz0 + r; cz++) {
                net.minecraft.world.chunk.Chunk ch = w.getChunk(cx, cz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        m.set((cx << 4) + x, 10, (cz << 4) + z);
                        if (ch.getBlockState(m).getBlock() != Blocks.MAGMA_BLOCK) {
                            continue;
                        }
                        // a clear water column to the sea (kelp or seagrass stops a bubble column) and lava under the floor
                        boolean open = true;
                        for (int y = 11; y <= 62 && open; y++) {
                            m.setY(y);
                            net.minecraft.block.Block bl = ch.getBlockState(m).getBlock();
                            open = bl == Blocks.WATER || bl == Blocks.BUBBLE_COLUMN;
                        }
                        m.setY(9);
                        open &= ch.getBlockState(m).getBlock() == Blocks.LAVA;
                        if (!open) {
                            continue;
                        }
                        double d = Math.pow(m.getX() - from.getX(), 2) + Math.pow(m.getZ() - from.getZ(), 2);
                        if (d < bd) {
                            bd = d;
                            best = new BlockPos(m.getX(), 10, m.getZ());
                        }
                    }
                }
            }
        }
        return best;
    }

    /** A dry standable cell 4-8 blocks from a lava source with at least 10 sources around it. */
    private static BlockPos findLava(ServerWorld w, BlockPos from, boolean low) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int r = 0; r <= 160; r += 8) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) < r - 7) {
                        continue;
                    }
                    int x = from.getX() + dx, z = from.getZ() + dz;
                    w.getChunk(x >> 4, z >> 4);
                    int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                    int y0 = low ? 5 : Math.max(1, top - 2);
                    int y1 = low ? 14 : top;
                    for (int y = y0; y <= y1; y++) {
                        m.set(x, y, z);
                        BlockState st = w.getBlockState(m);
                        if (st.getBlock() == Blocks.LAVA && st.getFluidState().isStill() && w.getBlockState(m.up()).isAir()
                                && sources(w, m) >= 10) {
                            BlockPos stand = standNear(w, m.toImmutable());
                            if (stand != null) {
                                return stand;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static int sources(ServerWorld w, BlockPos c) {
        int n = 0;
        for (BlockPos q : BlockPos.iterate(c.add(-4, -1, -4), c.add(4, 1, 4))) {
            BlockState st = w.getBlockState(q);
            if (st.getBlock() == Blocks.LAVA && st.getFluidState().isStill()) {
                n++;
            }
        }
        return n;
    }

    private static BlockPos standNear(ServerWorld w, BlockPos lava) {
        for (int d = 5; d <= 9; d++) {
            for (int[] o : new int[][]{{d, 0}, {-d, 0}, {0, d}, {0, -d}, {d, d}, {-d, -d}}) {
                for (int dy = 3; dy >= -2; dy--) {
                    BlockPos q = lava.add(o[0], dy, o[1]);
                    if (w.getBlockState(q).isAir() && w.getBlockState(q.up()).isAir() && w.getBlockState(q.down()).isSolidBlock(w, q.down())
                            && !w.getBlockState(q.down()).getFluidState().isEmpty() == false) {
                        return q;
                    }
                }
            }
        }
        return null;
    }
}
