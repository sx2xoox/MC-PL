package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 블록 퍼즐러 — the falling block puzzle as a fighting style. Pieces of three or five blocks drift down where the
 * puzzler aims and stay as cover; lines of them flash and blow up; and the ultimate fills a whole board row by row before
 * clearing it all at once.
 *
 * <p>Look (map materials: stone grey, brick red, oak, copper, moss green): a falling piece is a cluster of full-size
 * block displays drifting down over its own dark shadow, sliding a cell when the puzzler turns, slammed down with a
 * shockwave when it sneaks. Landed pieces glint now and then. A cleared line flashes white and gold before it bursts
 * into its own crumbs. The board is outlined in glowing copper, every coming row is shaded before its bar drops, and
 * the full board bursts under a big "올 클리어!".
 */
public final class BlockFallAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("blockfall", "블록 퍼즐러", Material.COPPER_BLOCK)
            .color(TextColor.color(0xC0703F))
            .difficulty(3)
            .passive("줄 폭파",
                    "내 조각이 같은 높이로 가로 {line_length}칸 이상 이어지거나 위로 {stack_height}칸 이상 쌓이면 그 줄이 "
                            + "터져, {line_radius}블록 안의 적에게 {line_damage} 피해를 주고 띄웁니다. 새 조각은 "
                            + "{snap_range}블록 안의 내 조각 옆에 달라붙습니다.")
            .active("조각 떨구기",
                    "조준한 바닥({drop_range}블록 이내) 위에 무작위 조각이 나타나 {drop_time}초에 걸쳐 내려옵니다. 시선을 "
                            + "좌우로 돌리면 옮겨지고 웅크리면 바로 떨어집니다. 깔린 적은 {crush_damage} 피해를 입고 "
                            + "밀려나며, 바로 떨어뜨리면 대신 {slam_damage} 피해와 {slam_stun}초 기절을 줍니다. 조각은 "
                            + "{piece_duration}초간 블록으로 남습니다.",
                    7)
            .ultimate("올 클리어",
                    "조준한 곳({board_range}블록 이내)에 {board_size}×{board_size} 판을 그리고 먼 쪽 줄부터 "
                            + "{bar_interval}초 간격으로 막대를 떨어뜨려 채웁니다. 깔린 적은 {bar_damage} 피해를 입고 막대 "
                            + "위로 올려집니다. 판이 다 차면 판 위의 적에게 {clear_damage} 피해를 주고 {clear_launch}블록 "
                            + "띄웁니다. 판 밖으로 걸어 나가면 피할 수 있습니다.")
            .param("line_length", 6)
            .param("stack_height", 2)
            .param("line_radius", 2)
            .param("line_damage", 4)
            .param("line_hit_cooldown", 1)
            .param("snap_range", 3)
            .param("drop_range", 20)
            .param("drop_height", 4)
            .param("drop_time", 1)
            .param("crush_damage", 3)
            .param("slam_damage", 2)
            .param("slam_stun", 0.5)
            .param("piece_duration", 10)
            .param("board_range", 24)
            .param("board_size", 7)
            .param("bar_interval", 0.3)
            .param("bar_damage", 2)
            .param("clear_damage", 5)
            .param("clear_launch", 2.8)
            .build();

    private static final String COPPER_TAG = "#C0703F";
    private static final String STONE_TAG = "#8A8D91";

    // ------------------------------------------------------------------ look
    /** Warm white, copper, dark umber. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF1D6, 0xC0703F, 0x4A2A1A);
    private static final Color FLASH_WHITE = Color.fromRGB(0xFFFFFF);
    private static final Color GOLD = Color.fromRGB(0xFFD27F);
    private static final Particle.DustOptions SHADOW = new Particle.DustOptions(Color.fromRGB(0x2B2B30), 1.1f);
    private static final Particle.DustOptions ROW_SHADE = new Particle.DustOptions(Color.fromRGB(0x9C4A3A), 1.2f);
    private static final Particle.DustTransition LINE_FLASH = new Particle.DustTransition(FLASH_WHITE, GOLD, 1.4f);

    /** The map materials pieces are made of; block data is created on use (it needs a running server). */
    private static final Material[] MATERIALS = {Material.STONE_BRICKS, Material.BRICKS, Material.OAK_PLANKS,
            Material.WAXED_CUT_COPPER, Material.MOSSY_STONE_BRICKS};
    private static final Material FRAME = Material.WAXED_COPPER_BLOCK;
    private static final Material GLOW = Material.WHITE_CONCRETE;

    /** The pieces: three-block and five-block shapes in the horizontal plane (never a full set of four-block ones). */
    private static final int[][][] SHAPES = {
            {{0, 0}, {1, 0}, {2, 0}},
            {{0, 0}, {1, 0}, {0, 1}},
            {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {4, 0}},
            {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {0, 1}},
            {{0, 0}, {1, 0}, {2, 0}, {1, 1}, {1, 2}},
            {{0, 0}, {2, 0}, {0, 1}, {1, 1}, {2, 1}},
            {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {0, 2}},
            {{1, 0}, {0, 1}, {1, 1}, {2, 1}, {1, 2}},
            {{0, 0}, {1, 0}, {1, 1}, {1, 2}, {2, 2}},
    };
    /** Straight pieces come up more often, so lines stay within reach. */
    private static final int[] WEIGHTS = {3, 1, 3, 1, 1, 1, 1, 1, 1};

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 2.0;

    // active: the falling piece
    /** Degrees of turning that slide the falling piece one cell. */
    private static final float SHIFT_DEGREES = 14f;
    private static final int MAX_SHIFT = 4;
    private static final double SLAM_SPEED = 1.6;
    private static final double CRUSH_PUSH = 0.75;
    /** A crushed enemy the piece surrounds on every side is popped up this high, onto the piece. */
    private static final double CRUSH_POP = 1.4;
    private static final int GROUND_SCAN = 12;
    private static final int RETRY_RUNS = 6;
    private static final long RETRY_PERIOD = 2;
    private static final long FLASH_TICKS = 6;
    /** How high over the ground left under it a line clear throws an enemy (a raised piece adds no fall damage). */
    private static final double LINE_LIFT = 2.2;
    /** The smallest pop of a line clear or an all clear, even over a deep drop. */
    private static final double MIN_POP = 0.5;
    private static final double GROUND_PROBE = 24;

    // ultimate: the board
    private static final long TRACE_TICKS = 10;
    private static final long BAR_FALL_TICKS = 4;
    private static final double BAR_HEIGHT = 4.5;
    private static final long CLEAR_DELAY = 8;
    /** High enough that the bar's block goes in under an enemy even when the server sees it late (its ping). */
    private static final double BAR_LIFT = 1.8;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    /** The blocks of this puzzler's landed pieces, by position. */
    private final Map<Long, Cell> cells = new HashMap<>();
    private final Map<UUID, Long> lineHitUntil = new HashMap<>();
    private @Nullable Drop drop;

    /** A landed block of a piece. */
    private record Cell(Block block, Material material, long expires) {
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        drop = null;
        cells.clear();
        lineHitUntil.clear();
        live.clear();
    }

    // ------------------------------------------------------------------ passive: line clears

    @Override
    public void onTick(long tick) {
        if (tick % 20 != 0 || cells.isEmpty()) {
            return;
        }
        prune();
        // Landed pieces glint now and then.
        List<Cell> all = new ArrayList<>(cells.values());
        if (!all.isEmpty()) {
            Block block = all.get(ThreadLocalRandom.current().nextInt(all.size())).block();
            Vfx.dot(block.getLocation().add(0.5, 1.05, 0.5), Particle.WAX_OFF, null, false);
        }
        if (tick % 200 == 0) {
            lineHitUntil.values().removeIf(until -> until < ctx().currentTick());
        }
    }

    /** Forgets cells that expired or are no longer the piece's block. */
    private void prune() {
        long now = ctx().currentTick();
        cells.values().removeIf(cell -> cell.expires() <= now || cell.block().getType() != cell.material());
    }

    private Cell cellAt(int x, int y, int z) {
        return cells.get(key(x, y, z));
    }

    /**
     * Checks the lines through {@code fresh} blocks: a horizontal run of at least {@code line_length} of the puzzler's
     * blocks at one height (along either axis) or a stack of at least {@code stack_height} clears.
     */
    private void checkLines(Player owner, List<Block> fresh) {
        prune();
        int lineLength = Math.max(2, params().integer("line_length"));
        int stackHeight = Math.max(2, params().integer("stack_height"));
        Map<Long, Cell> clear = new LinkedHashMap<>();
        int longest = 0;
        int tallest = 0;
        for (Block block : fresh) {
            if (cellAt(block.getX(), block.getY(), block.getZ()) == null) {
                continue;
            }
            List<Block> alongX = run(block, 1, 0, 0);
            List<Block> alongZ = run(block, 0, 0, 1);
            List<Block> up = run(block, 0, 1, 0);
            longest = Math.max(longest, Math.max(alongX.size(), alongZ.size()));
            tallest = Math.max(tallest, up.size());
            clearIfLong(clear, alongX, lineLength);
            clearIfLong(clear, alongZ, lineLength);
            clearIfLong(clear, up, stackHeight);
        }
        if (!clear.isEmpty()) {
            for (long at : clear.keySet()) {
                cells.remove(at);
            }
            lineClear(owner, new ArrayList<>(clear.values()));
        } else if (!fresh.isEmpty()) {
            owner.sendActionBar(Text.mm("<" + COPPER_TAG + ">▦ 줄 " + Math.min(longest, lineLength) + "/" + lineLength
                    + "</" + COPPER_TAG + "> <" + STONE_TAG + ">· 쌓기 " + Math.min(tallest, stackHeight) + "/"
                    + stackHeight + "</" + STONE_TAG + ">"));
        }
    }

    /** Adds the cells of {@code run} to {@code clear} if it is at least {@code needed} long. */
    private void clearIfLong(Map<Long, Cell> clear, List<Block> run, int needed) {
        if (run.size() < needed) {
            return;
        }
        for (Block member : run) {
            long at = key(member.getX(), member.getY(), member.getZ());
            Cell cell = cells.get(at);
            if (cell != null) {
                clear.put(at, cell);
            }
        }
    }

    /** The contiguous run of the puzzler's blocks through {@code block} along (dx, dy, dz). */
    private List<Block> run(Block block, int dx, int dy, int dz) {
        List<Block> found = new ArrayList<>();
        found.add(block);
        for (int sign = -1; sign <= 1; sign += 2) {
            for (int i = 1; i < 64; i++) {
                Cell cell = cellAt(block.getX() + sign * dx * i, block.getY() + sign * dy * i, block.getZ() + sign * dz * i);
                if (cell == null) {
                    break;
                }
                found.add(cell.block());
            }
        }
        return found;
    }

    /** A completed line flashes white and gold for a moment, then bursts and throws the enemies around it up. */
    private void lineClear(Player owner, List<Cell> cleared) {
        List<Block> line = new ArrayList<>(cleared.size());
        for (Cell cell : cleared) {
            line.add(cell.block());
        }
        List<BlockDisplay> flashes = new ArrayList<>();
        for (Block block : line) {
            if (!reserve(1)) {
                break;
            }
            BlockDisplay glow = keep(Displays.block(ctx(), owner, block.getLocation().add(0.5, 0.5, 0.5),
                    GLOW.createBlockData(), Displays.scaled(1.02f), FLASH_TICKS + 4));
            Displays.outline(glow, GOLD);
            flashes.add(glow);
        }
        Location middle = centerOf(line);
        Fx.sound(middle, Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f, 1.2f);
        Fx.sound(middle, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.5f);
        Loop.repeat(ctx(), owner, 0, 1, FLASH_TICKS, run -> {
            if (run % 2 == 0) {
                for (Block block : line) {
                    Vfx.dot(block.getLocation().add(0.5, 1.05, 0.5), Particle.DUST_COLOR_TRANSITION, LINE_FLASH, true);
                }
                Fx.sound(middle, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f + 0.15f * run);
            }
            return true;
        }, () -> burst(owner, cleared, line, flashes));
    }

    /**
     * The line bursts: its blocks go (only those still this piece's own: one may have run out during the flash and
     * the spot been taken since), then the enemies around it are hit and thrown up.
     */
    private void burst(Player owner, List<Cell> cleared, List<Block> line, List<BlockDisplay> flashes) {
        for (BlockDisplay glow : flashes) {
            if (glow.isValid()) {
                glow.remove();
            }
        }
        World world = line.getFirst().getWorld();
        long tick = ctx().currentTick();
        for (Cell cell : cleared) {
            Block block = cell.block();
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            if (cell.expires() > tick && block.getType() == cell.material()) {
                world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 8), 0.3, 0.3, 0.3, 0, block.getBlockData(),
                        true);
                ctx().restoreTempBlock(block);
            }
            Vfx.dot(center, Particle.FLASH, FLASH_WHITE, true);
        }
        Location middle = centerOf(line);
        Fx.sound(middle, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.4f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(middle, Sound.BLOCK_STONE_BREAK, 1.2f, 0.7f);
        Fx.sound(middle, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.3f + Fx.jitter(0.05f));
        double radius = Math.max(0, params().num("line_radius"));
        double damage = params().num("line_damage");
        long cooldown = params().ticks("line_hit_cooldown");
        long now = ctx().currentTick();
        Set<UUID> done = new HashSet<>();
        int hits = 0;
        for (Block block : line) {
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius + 0.5)) {
                if (!done.add(enemy.getUniqueId()) || lineHitUntil.getOrDefault(enemy.getUniqueId(),
                        Long.MIN_VALUE / 2) > now) {
                    continue;
                }
                lineHitUntil.put(enemy.getUniqueId(), now + cooldown);
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    pop(enemy, LINE_LIFT);
                }
            }
        }
        popText(middle.clone().add(0, 1.3, 0), "<b><gradient:#FFFFFF:#FFD27F:#C0703F>"
                + (line.size() >= params().integer("line_length") ? "줄 폭파!" : "쌓기 폭파!") + "</gradient></b>");
        if (hits > 0) {
            owner.sendActionBar(Text.mm("<" + COPPER_TAG + "><b>▦ 줄 폭파!</b></" + COPPER_TAG + "> <white>" + hits
                    + "명 적중</white>"));
        }
    }

    /**
     * Throws {@code enemy} up to {@code apex} blocks over the ground under it (with the burst blocks already gone): a
     * pop from a raised piece adds no extra fall.
     */
    private void pop(LivingEntity enemy, double apex) {
        double rise = Math.max(MIN_POP, apex - heightAboveGround(enemy));
        Motion.push(enemy, new Vector(0, Motion.launchSpeed(rise), 0), MAX_SPEED);
    }

    /** How far the entity's feet are above the first block or fluid below them (very far over the void). */
    private static double heightAboveGround(Entity entity) {
        Location feet = entity.getLocation();
        RayTraceResult ground = entity.getWorld().rayTraceBlocks(feet.clone().add(0, 0.05, 0), new Vector(0, -1, 0),
                GROUND_PROBE, FluidCollisionMode.ALWAYS, true);
        return ground == null ? GROUND_PROBE : Math.max(0, feet.getY() - ground.getHitPosition().getY());
    }

    private static Location centerOf(List<Block> blocks) {
        double x = 0;
        double y = 0;
        double z = 0;
        for (Block block : blocks) {
            x += block.getX() + 0.5;
            y += block.getY() + 0.5;
            z += block.getZ() + 0.5;
        }
        int n = Math.max(1, blocks.size());
        return new Location(blocks.getFirst().getWorld(), x / n, y / n, z / n);
    }

    // ------------------------------------------------------------------ active: drop a piece

    @Override
    public ActivationResult onActive() {
        if (drop != null) {
            return ActivationResult.fail("조각이 아직 내려오는 중입니다");
        }
        Player owner = player();
        Location ground = aimGround(owner, Math.max(1, params().num("drop_range")));
        if (ground == null) {
            return ActivationResult.fail("조각을 떨굴 바닥이 보이지 않습니다");
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int[][] shape = rotate(pickShape(random), random.nextInt(4));
        Material material = MATERIALS[random.nextInt(MATERIALS.length)];
        Drop piece = new Drop(owner, ground, shape, material);
        if (!piece.ready()) {
            return ActivationResult.fail("조각이 내려올 자리가 없습니다");
        }
        drop = piece;
        piece.begin();
        return ActivationResult.success();
    }

    private static int[][] pickShape(ThreadLocalRandom random) {
        int total = 0;
        for (int weight : WEIGHTS) {
            total += weight;
        }
        int roll = random.nextInt(total);
        for (int i = 0; i < SHAPES.length; i++) {
            roll -= WEIGHTS[i];
            if (roll < 0) {
                return SHAPES[i];
            }
        }
        return SHAPES[0];
    }

    /** The shape turned by {@code quarter} quarter turns and centered on its middle cell. */
    private static int[][] rotate(int[][] shape, int quarter) {
        int[][] turned = new int[shape.length][2];
        double cx = 0;
        double cz = 0;
        for (int i = 0; i < shape.length; i++) {
            int a = shape[i][0];
            int b = shape[i][1];
            for (int q = 0; q < quarter; q++) {
                int t = a;
                a = -b;
                b = t;
            }
            turned[i][0] = a;
            turned[i][1] = b;
            cx += a;
            cz += b;
        }
        int ox = (int) Math.round(cx / shape.length);
        int oz = (int) Math.round(cz / shape.length);
        for (int[] cell : turned) {
            cell[0] -= ox;
            cell[1] -= oz;
        }
        return turned;
    }

    /**
     * The falling piece: a cluster of block displays that drifts down from {@code drop_height} over its shadow. The
     * puzzler slides it a cell for every {@link #SHIFT_DEGREES} it turns (sideways to the way it faced when dropping),
     * and slams it down by sneaking. On landing it crushes the enemies under it and becomes real (temporary) blocks;
     * near the puzzler's other pieces it first snaps to their side at their height.
     */
    private final class Drop implements LongPredicate {

        private final Player owner;
        private final World world;
        private final int[][] shape;
        private final Material material;
        private final int forwardX;
        private final int forwardZ;
        private final int rightX;
        private final int rightZ;
        private final float castYaw;
        private final double speed;
        private final List<BlockDisplay> blocks = new ArrayList<>();
        private final int aimLayer;
        private final int scanTop;
        private int baseX;
        private int baseZ;
        private int shift;
        private int land;
        private double y;
        private @Nullable Integer snapped;
        private boolean slammed;
        private boolean sneaking;

        Drop(Player owner, Location ground, int[][] shape, Material material) {
            this.owner = owner;
            this.world = ground.getWorld();
            this.shape = shape;
            this.material = material;
            Vector facing = Motion.horizontalFacing(owner.getLocation());
            if (Math.abs(facing.getX()) > Math.abs(facing.getZ())) {
                forwardX = (int) Math.signum(facing.getX());
                forwardZ = 0;
            } else {
                forwardX = 0;
                forwardZ = facing.getZ() >= 0 ? 1 : -1;
            }
            // Facing +Z, the right hand points to -X.
            rightX = -forwardZ;
            rightZ = forwardX;
            castYaw = owner.getLocation().getYaw();
            sneaking = owner.isSneaking();
            double height = Math.max(1, params().num("drop_height"));
            speed = height / Math.max(1, params().ticks("drop_time"));
            aimLayer = (int) Math.floor(ground.getY() + 1.0E-3);
            scanTop = aimLayer + (int) Math.ceil(height) + 1;
            baseX = ground.getBlockX();
            baseZ = ground.getBlockZ();
            int natural = landing(columns(0));
            snapped = snap(natural);
            land = snapped != null ? snapped : natural;
            y = top(columns(0), land, height);
        }

        boolean ready() {
            return land > world.getMinHeight() && y >= land;
        }

        /** The piece's block columns slid {@code by} cells to the right. */
        private List<int[]> columns(int by) {
            List<int[]> out = new ArrayList<>(shape.length);
            for (int[] cell : shape) {
                int a = cell[0] + by;
                int b = cell[1];
                out.add(new int[] {baseX + a * rightX + b * forwardX, baseZ + a * rightZ + b * forwardZ});
            }
            return out;
        }

        /**
         * The layer the piece comes to rest in: on the highest support under any of its columns (from just over where
         * it appears down to {@link #GROUND_SCAN} under the aimed ground), the aimed ground if none has any.
         */
        private int landing(List<int[]> columns) {
            int best = Integer.MIN_VALUE;
            int bottom = Math.max(world.getMinHeight() + 1, aimLayer - GROUND_SCAN);
            int top = Math.min(world.getMaxHeight() - 2, scanTop);
            for (int[] column : columns) {
                for (int yy = top; yy > bottom; yy--) {
                    if (!world.getBlockAt(column[0], yy - 1, column[1]).isPassable()
                            && world.getBlockAt(column[0], yy, column[1]).isPassable()) {
                        best = Math.max(best, yy);
                        break;
                    }
                }
            }
            return best == Integer.MIN_VALUE ? aimLayer : best;
        }

        /** Where the piece appears: {@code height} over its landing, lower under a ceiling. */
        private double top(List<int[]> columns, int layer, double height) {
            int highest = layer + (int) Math.round(height);
            for (int[] column : columns) {
                for (int yy = layer; yy <= layer + (int) Math.round(height); yy++) {
                    if (!world.getBlockAt(column[0], yy, column[1]).isPassable()) {
                        highest = Math.min(highest, yy - 1);
                        break;
                    }
                }
            }
            return Math.max(layer, highest);
        }

        /**
         * Snaps the piece next to the puzzler's blocks within {@code snap_range} of the aim, at their height: the
         * placement that makes the longest line wins, then the smallest move. Not when aiming right onto a piece (that
         * stacks). Returns the layer it snapped to, null if it did not.
         */
        private @Nullable Integer snap(int natural) {
            prune();
            int range = Math.max(0, params().integer("snap_range"));
            if (range <= 0 || cells.isEmpty()) {
                return null;
            }
            List<int[]> aimed = columns(0);
            Set<Integer> layers = new HashSet<>();
            for (Cell cell : cells.values()) {
                Block block = cell.block();
                for (int[] column : aimed) {
                    if (block.getX() == column[0] && block.getZ() == column[1] && block.getY() >= natural - 3
                            && block.getY() <= natural + 3) {
                        return null; // aimed onto a piece: stack it
                    }
                    if (Math.abs(block.getX() - column[0]) <= range && Math.abs(block.getZ() - column[1]) <= range
                            && Math.abs(block.getY() - natural) <= 3) {
                        layers.add(block.getY());
                    }
                }
            }
            int lineLength = Math.max(2, params().integer("line_length"));
            int bestScore = Integer.MIN_VALUE;
            int[] best = null;
            for (int layer : layers) {
                for (int dx = -range; dx <= range; dx++) {
                    for (int dz = -range; dz <= range; dz++) {
                        int score = placement(aimed, dx, dz, layer, lineLength);
                        if (score == Integer.MIN_VALUE) {
                            continue;
                        }
                        score = score * 100 - (Math.abs(dx) + Math.abs(dz)) * 3 - Math.abs(layer - natural);
                        if (score > bestScore) {
                            bestScore = score;
                            best = new int[] {dx, dz, layer};
                        }
                    }
                }
            }
            if (best == null) {
                return null;
            }
            baseX += best[0];
            baseZ += best[1];
            return best[2];
        }

        /**
         * How good placing the piece moved by (dx, dz) at {@code layer} is: MIN_VALUE when it overlaps a block or
         * touches none of the puzzler's blocks, else the longest straight run it makes (a full line counts double).
         */
        private int placement(List<int[]> aimed, int dx, int dz, int layer, int lineLength) {
            Set<Long> mine = new HashSet<>();
            boolean touches = false;
            for (int[] column : aimed) {
                int x = column[0] + dx;
                int z = column[1] + dz;
                if (cellAt(x, layer, z) != null || !world.getBlockAt(x, layer, z).isPassable()
                        || !ctx().isInsideArena(new Location(world, x + 0.5, layer, z + 0.5))) {
                    return Integer.MIN_VALUE;
                }
                mine.add(key(x, layer, z));
                touches |= cellAt(x + 1, layer, z) != null || cellAt(x - 1, layer, z) != null
                        || cellAt(x, layer, z + 1) != null || cellAt(x, layer, z - 1) != null;
            }
            if (!touches) {
                return Integer.MIN_VALUE;
            }
            int longest = 0;
            for (int[] column : aimed) {
                int x = column[0] + dx;
                int z = column[1] + dz;
                longest = Math.max(longest, span(mine, x, layer, z, 1, 0));
                longest = Math.max(longest, span(mine, x, layer, z, 0, 1));
            }
            return longest >= lineLength ? longest * 2 : longest;
        }

        /** The run through (x, z) along (dx, dz) of the puzzler's blocks and the {@code extra} positions. */
        private int span(Set<Long> extra, int x, int layer, int z, int dx, int dz) {
            int count = 1;
            for (int sign = -1; sign <= 1; sign += 2) {
                for (int i = 1; i < 64; i++) {
                    int px = x + sign * dx * i;
                    int pz = z + sign * dz * i;
                    if (cellAt(px, layer, pz) == null && !extra.contains(key(px, layer, pz))) {
                        break;
                    }
                    count++;
                }
            }
            return count;
        }

        void begin() {
            BlockData data = material.createBlockData();
            for (int[] column : columns(shift)) {
                Location at = new Location(world, column[0] + 0.5, y + 0.5, column[1] + 0.5);
                BlockDisplay block = keep(Displays.block(ctx(), owner, at, data, Displays.scaled(0.05f),
                        Math.max(40, params().ticks("drop_time") * 4 + 40)));
                block.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, block, Displays.scaled(0.98f), 3);
                blocks.add(block);
                Vfx.cloud(ctx(), at, Particle.BLOCK, 4, 0.3, 0.02, data, false);
            }
            if (snapped != null) {
                Location at = new Location(world, baseX + 0.5, land + 0.5, baseZ + 0.5);
                Vfx.trail(owner.getEyeLocation(), at, PALETTE.body(), 8, false);
                Fx.sound(at, Sound.BLOCK_COPPER_PLACE, 0.8f, 1.6f);
            }
            Location at = new Location(world, baseX + 0.5, y + 0.5, baseZ + 0.5);
            Fx.sound(at, Sound.BLOCK_PISTON_EXTEND, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 1.2f);
            Loop.repeat(ctx(), owner, 0, 1, 20L * 15, this, this::end);
        }

        @Override
        public boolean test(long tick) {
            boolean nowSneaking = owner.isSneaking();
            if (!slammed && nowSneaking && !sneaking) {
                slammed = true;
                Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.0f, 1.4f);
            }
            sneaking = nowSneaking;
            if (!slammed) {
                int wanted = Math.clamp(Math.round(wrapDegrees(owner.getLocation().getYaw() - castYaw) / SHIFT_DEGREES),
                        -MAX_SHIFT, MAX_SHIFT);
                if (wanted != shift) {
                    slide(wanted);
                }
            }
            y = Math.max(land, y - (slammed ? SLAM_SPEED : speed));
            List<int[]> columns = columns(shift);
            for (int i = 0; i < blocks.size() && i < columns.size(); i++) {
                int[] column = columns.get(i);
                Displays.moveTo(blocks.get(i), new Location(world, column[0] + 0.5, y + 0.5, column[1] + 0.5));
            }
            if (tick % 2 == 0) {
                shadow(columns, land, y - land);
            }
            if (y <= land + 1.0E-6) {
                touchDown(columns);
                return false;
            }
            return true;
        }

        /** Slides the piece towards {@code wanted} (one cell at a time) unless a wall is in the way. */
        private void slide(int wanted) {
            int next = shift + Integer.signum(wanted - shift);
            List<int[]> columns = columns(next);
            int natural = landing(columns);
            int layer = next == 0 && snapped != null ? snapped : natural;
            int bottom = (int) Math.floor(y + 1.0E-6);
            for (int[] column : columns) {
                if (!ctx().isInsideArena(new Location(world, column[0] + 0.5, y, column[1] + 0.5))
                        || !world.getBlockAt(column[0], bottom, column[1]).isPassable()
                        || !world.getBlockAt(column[0], bottom + 1, column[1]).isPassable()) {
                    return; // a wall beside the piece
                }
            }
            if (layer > bottom) {
                return;
            }
            shift = next;
            land = layer;
            Location at = new Location(world, columns.getFirst()[0] + 0.5, y + 0.5, columns.getFirst()[1] + 0.5);
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.6f + Fx.jitter(0.1f));
        }

        /**
         * The impact marker of the falling piece: a soft round shadow on the ground under its middle (never the cells it
         * will fill) that tightens and darkens as the piece comes down.
         */
        private void shadow(List<int[]> columns, int layer, double height) {
            double cx = 0;
            double cz = 0;
            for (int[] column : columns) {
                cx += column[0] + 0.5;
                cz += column[1] + 0.5;
            }
            cx /= columns.size();
            cz /= columns.size();
            double spread = 0;
            for (int[] column : columns) {
                spread = Math.max(spread, Math.hypot(column[0] + 0.5 - cx, column[1] + 0.5 - cz));
            }
            double radius = spread + 0.4 + Math.min(1.0, height * 0.25);
            Location middle = new Location(world, cx, layer + 0.05, cz);
            Vfx.ring(ctx(), middle, radius, Math.clamp(Math.round(radius * 5), 8, 18), height * 0.4,
                    Particle.DUST, SHADOW, false);
            if (slammed || height < 1.5) {
                Vfx.dot(middle, Particle.DUST, SHADOW, false);
                for (int i = 0; i < 3; i++) {
                    Vfx.dot(Vfx.scatter(middle, radius * 0.7), Particle.DUST, SHADOW, false);
                }
            }
        }

        /** The piece lands: enemies under it are crushed aside, the blocks become real, lines are checked. */
        private void touchDown(List<int[]> columns) {
            for (BlockDisplay block : blocks) {
                if (block.isValid()) {
                    block.remove();
                }
            }
            blocks.clear();
            double sumX = 0;
            double sumZ = 0;
            for (int[] column : columns) {
                sumX += column[0] + 0.5;
                sumZ += column[1] + 0.5;
            }
            Location middle = new Location(world, sumX / columns.size(), land + 0.5, sumZ / columns.size());
            Set<UUID> crushed = new HashSet<>();
            for (int[] column : columns) {
                BoundingBox cell = new BoundingBox(column[0], land, column[1], column[0] + 1, land + 1, column[1] + 1);
                for (LivingEntity enemy : ctx().enemiesNear(owner, cell.getCenter().toLocation(world), 1.6)) {
                    if (enemy.getBoundingBox().overlaps(cell) && crushed.add(enemy.getUniqueId())) {
                        crush(enemy, middle, columns);
                    }
                }
            }
            long duration = Math.max(20, params().ticks("piece_duration"));
            BlockData data = material.createBlockData();
            List<Block> placed = new ArrayList<>();
            List<Block> waiting = new ArrayList<>();
            for (int[] column : columns) {
                Block block = world.getBlockAt(column[0], land, column[1]);
                if (!ctx().isInsideArena(block.getLocation().add(0.5, 0.5, 0.5))) {
                    continue;
                }
                if (occupied(block)) {
                    waiting.add(block);
                } else if (ctx().placeTempBlock(block, data, duration)) {
                    cells.put(key(block.getX(), block.getY(), block.getZ()),
                            new Cell(block, material, ctx().currentTick() + duration));
                    placed.add(block);
                }
                world.spawnParticle(Particle.BLOCK, column[0] + 0.5, land + 0.1, column[1] + 0.5, Vfx.n(ctx(), 5), 0.4,
                        0.05, 0.4, 0, data);
            }
            if (slammed) {
                Vfx.shockwave(ctx(), owner, middle.clone().subtract(0, 0.4, 0), 0.5, 2.5, 4,
                        Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.2f), false);
                Fx.sound(middle, Sound.ITEM_MACE_SMASH_GROUND, 1.2f, 0.8f + Fx.jitter(0.05f));
                Fx.sound(middle, Sound.BLOCK_ANVIL_LAND, 0.7f, 0.9f + Fx.jitter(0.05f));
            }
            Fx.sound(middle, placeSound(material), 1.2f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(middle, Sound.BLOCK_PISTON_CONTRACT, 0.8f, 0.6f);
            checkLines(owner, placed);
            if (!waiting.isEmpty()) {
                retry(waiting, data, duration);
            }
        }

        /** Crushed: hurt and shoved out sideways; a slammed piece also stuns. */
        private void crush(LivingEntity enemy, Location middle, List<int[]> columns) {
            Combat.hit(ctx(), owner, enemy, params().num(slammed ? "slam_damage" : "crush_damage"));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(middle.toVector()));
            Vector out = escape(enemy, columns, away);
            if (out != null) {
                Motion.push(enemy, out.multiply(CRUSH_PUSH).setY(0.3), MAX_SPEED);
            } else {
                // Boxed in by the piece on every side: popped up on top of it instead.
                Motion.push(enemy, away.multiply(0.2).setY(Motion.launchSpeed(CRUSH_POP)), MAX_SPEED);
            }
            if (slammed) {
                long stun = params().ticks("slam_stun");
                if (stun > 0) {
                    ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
                    ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
                }
            }
            Location body = Fx.body(enemy);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f + Fx.jitter(0.05f));
            if (seen(owner, enemy)) {
                Vfx.burst(ctx(), body, PALETTE, 0.5, Particle.CRIT, false);
                popText(body.clone().add(0, 1.0, 0), slammed ? "<b><gradient:#FFF1D6:#C0703F>쾅!</gradient></b>"
                        : "<gradient:#FFF1D6:#C0703F>깔림!</gradient>");
            }
        }

        /**
         * The way out from under the piece for an enemy standing in it: towards a neighbouring column the piece leaves
         * open (and that is free to stand in), preferring the one pointing away from the piece's middle. Null if the
         * piece surrounds it.
         */
        private @Nullable Vector escape(LivingEntity enemy, List<int[]> columns, Vector away) {
            Set<Long> piece = new HashSet<>();
            for (int[] column : columns) {
                piece.add(key(column[0], 0, column[1]));
            }
            Location at = enemy.getLocation();
            int ex = at.getBlockX();
            int ez = at.getBlockZ();
            Vector best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 || piece.contains(key(ex + dx, 0, ez + dz))
                            || !world.getBlockAt(ex + dx, land, ez + dz).isPassable()
                            || !world.getBlockAt(ex + dx, land + 1, ez + dz).isPassable()) {
                        continue;
                    }
                    Vector way = new Vector(ex + dx + 0.5 - at.getX(), 0, ez + dz + 0.5 - at.getZ());
                    if (way.lengthSquared() < 1.0E-6) {
                        continue;
                    }
                    way.normalize();
                    double score = way.dot(away) - (dx != 0 && dz != 0 ? 0.3 : 0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = way;
                    }
                }
            }
            return best;
        }

        /** Blocks that had someone in their way go in as soon as the space is free (for a short while). */
        private void retry(List<Block> waiting, BlockData data, long duration) {
            long until = ctx().currentTick() + duration;
            Loop.repeat(ctx(), owner, RETRY_PERIOD, RETRY_PERIOD, RETRY_RUNS, run -> {
                List<Block> placed = new ArrayList<>();
                waiting.removeIf(block -> {
                    if (occupied(block)) {
                        return false;
                    }
                    long left = until - ctx().currentTick();
                    if (left > 10 && ctx().placeTempBlock(block, data, left)) {
                        cells.put(key(block.getX(), block.getY(), block.getZ()), new Cell(block, material, until));
                        placed.add(block);
                    }
                    return true;
                });
                if (!placed.isEmpty()) {
                    checkLines(owner, placed);
                }
                return !waiting.isEmpty();
            });
        }

        void end() {
            for (BlockDisplay block : blocks) {
                if (block.isValid()) {
                    block.remove();
                }
            }
            if (drop == this) {
                drop = null;
            }
        }
    }

    /** True if a living body is in the block's space. */
    private static boolean occupied(Block block) {
        BoundingBox cell = BoundingBox.of(block);
        for (Entity entity : block.getWorld().getNearbyEntities(cell.clone().expand(1.0))) {
            if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()
                    && living.getBoundingBox().overlaps(cell)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ ultimate: all clear

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location ground = aimGround(owner, Math.max(1, params().num("board_range")));
        if (ground == null) {
            return ActivationResult.fail("판을 깔 바닥이 보이지 않습니다");
        }
        Board board = new Board(owner, ground);
        board.begin();
        return ActivationResult.success();
    }

    /**
     * The board: a square outlined in glowing copper on the ground; bars as wide as the board fall row by row from the
     * far side (each row shaded just before), crushing and lifting the enemies under them; once full it bursts.
     */
    private final class Board implements LongPredicate {

        private final Player owner;
        private final World world;
        private final int size;
        private final int floor;
        private final int minX;
        private final int minZ;
        /** Rows from the far side: the bar's axis and its order. */
        private final boolean rowsAlongX;
        private final int farSign;
        private final long interval = Math.max(2, params().ticks("bar_interval"));
        private final long clearAt;
        /** The board's blocks and what each one is (a bar's material). */
        private final Map<Block, Material> placed = new LinkedHashMap<>();
        private final Map<Block, BlockData> waiting = new HashMap<>();
        private final Set<UUID> crushed = new HashSet<>();
        private final List<BlockDisplay> frame = new ArrayList<>();
        private final List<BlockDisplay> falling = new ArrayList<>();
        private @Nullable BlockData barData;
        private int dropped;
        private long startedAt;

        Board(Player owner, Location ground) {
            this.owner = owner;
            this.world = ground.getWorld();
            this.size = Math.max(3, params().integer("board_size"));
            this.floor = (int) Math.floor(ground.getY() + 1.0E-3);
            this.minX = ground.getBlockX() - size / 2;
            this.minZ = ground.getBlockZ() - size / 2;
            Vector facing = Motion.horizontalFacing(owner.getLocation());
            boolean alongZ = Math.abs(facing.getZ()) >= Math.abs(facing.getX());
            // Facing along z, the rows run along x and the far row is the one furthest along the facing.
            this.rowsAlongX = alongZ;
            this.farSign = alongZ ? (facing.getZ() >= 0 ? 1 : -1) : (facing.getX() >= 0 ? 1 : -1);
            this.clearAt = TRACE_TICKS + (size - 1) * interval + BAR_FALL_TICKS + CLEAR_DELAY;
        }

        void begin() {
            startedAt = ctx().currentTick();
            Location center = new Location(world, minX + size / 2.0, floor + 0.02, minZ + size / 2.0);
            if (reserve(4)) {
                float length = size + 0.1f;
                float thick = 0.1f;
                float half = size / 2f;
                float[][] bars = {{0, -half, length, thick}, {0, half, length, thick}, {-half, 0, thick, length},
                        {half, 0, thick, length}};
                BlockData copper = FRAME.createBlockData();
                for (float[] bar : bars) {
                    BlockDisplay edge = keep(Displays.block(ctx(), owner, center, copper,
                            Displays.transform(bar[0], 0.05f, bar[1], bar[2], 0.05f, bar[3], 0, 0, 0), clearAt + 20));
                    Displays.outline(edge, PALETTE.body());
                    edge.setViewRange(2.0f);
                    Displays.animateNextTick(ctx(), owner, edge, Displays.transform(bar[0], 0.05f, bar[1], bar[2], 0.12f,
                            bar[3], 0, 0, 0), 4);
                    frame.add(edge);
                }
            }
            Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BIT, 1.2f, 0.8f);
            Fx.sound(center, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.4f);
            Fx.sound(center, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.4f, 1.6f);
            owner.sendActionBar(Text.mm("<" + COPPER_TAG + "><b>▦ 올 클리어</b></" + COPPER_TAG + "> <gray>판을 채우는 "
                    + "중...</gray>"));
            Loop.repeat(ctx(), owner, 0, 1, clearAt + 1, this, () -> {
                for (BlockDisplay edge : frame) {
                    if (edge.isValid()) {
                        Displays.shrinkAway(ctx(), owner, edge, 6);
                    }
                }
            });
        }

        @Override
        public boolean test(long tick) {
            if (tick < TRACE_TICKS) {
                if (tick % 2 == 0) {
                    trace();
                }
                return true;
            }
            if (tick >= clearAt) {
                allClear();
                return false;
            }
            long sinceTrace = tick - TRACE_TICKS;
            int bar = (int) (sinceTrace / interval);
            long within = sinceTrace % interval;
            if (bar < size && within == 0) {
                launchBar(bar);
            }
            if (barData != null) {
                fall(dropped - 1, Math.min(within, BAR_FALL_TICKS));
            }
            if (!waiting.isEmpty()) {
                long left = Math.max(10, startedAt + clearAt + 20 - ctx().currentTick());
                waiting.entrySet().removeIf(entry -> {
                    if (occupied(entry.getKey())) {
                        return false;
                    }
                    if (ctx().placeTempBlock(entry.getKey(), entry.getValue(), left)) {
                        placed.put(entry.getKey(), entry.getValue().getMaterial());
                    }
                    return true;
                });
            }
            int next = within >= interval - 3 ? bar + 1 : -1;
            if (next >= 0 && next < size && tick % 2 == 0) {
                shade(next);
            }
            return true;
        }

        /** The board's squares, faintly traced before the first bar. */
        private void trace() {
            for (int i = 0; i <= size; i++) {
                Location a = new Location(world, minX + i, floor + 0.05, minZ);
                Location b = new Location(world, minX + i, floor + 0.05, minZ + size);
                Location c = new Location(world, minX, floor + 0.05, minZ + i);
                Location d = new Location(world, minX + size, floor + 0.05, minZ + i);
                Vfx.line(ctx(), a, b, 0.6, Particle.DUST, ROW_SHADE, true);
                Vfx.line(ctx(), c, d, 0.6, Particle.DUST, ROW_SHADE, true);
            }
        }

        /** The cells of bar {@code index} (0 = the far row). */
        private List<Block> row(int index) {
            int line = farSign > 0 ? size - 1 - index : index;
            List<Block> out = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int x = rowsAlongX ? minX + i : minX + line;
                int z = rowsAlongX ? minZ + line : minZ + i;
                out.add(world.getBlockAt(x, floor, z));
            }
            return out;
        }

        /** The next row is shaded red just before its bar drops. */
        private void shade(int index) {
            for (Block block : row(index)) {
                Vfx.dot(block.getLocation().add(0.5, 0.08, 0.5), Particle.DUST, ROW_SHADE, true);
            }
        }

        private void launchBar(int index) {
            dropped++;
            falling.clear();
            Material material = MATERIALS[ThreadLocalRandom.current().nextInt(MATERIALS.length)];
            BlockData data = material.createBlockData();
            barData = data;
            for (Block block : row(index)) {
                if (!reserve(1)) {
                    break;
                }
                BlockDisplay piece = keep(Displays.block(ctx(), owner, block.getLocation().add(0.5, BAR_HEIGHT, 0.5),
                        data, Displays.scaled(0.98f), BAR_FALL_TICKS + 6));
                piece.setTeleportDuration(1);
                falling.add(piece);
            }
            Fx.sound(row(index).get(size / 2).getLocation(), Sound.BLOCK_PISTON_EXTEND, 0.8f, 1.2f + 0.05f * index);
        }

        /** The falling bar accelerates down and becomes blocks when it lands. */
        private void fall(int index, long step) {
            double t = (double) step / BAR_FALL_TICKS;
            double height = BAR_HEIGHT - (BAR_HEIGHT - 0.5) * t * t;
            List<Block> cells = row(index);
            for (int i = 0; i < falling.size() && i < cells.size(); i++) {
                Displays.moveTo(falling.get(i), cells.get(i).getLocation().add(0.5, height, 0.5));
            }
            if (step < BAR_FALL_TICKS) {
                return;
            }
            BlockData data = barData != null ? barData : MATERIALS[0].createBlockData();
            barData = null;
            for (BlockDisplay piece : falling) {
                if (piece.isValid()) {
                    piece.remove();
                }
            }
            falling.clear();
            land(cells, data);
        }

        private void land(List<Block> cells, BlockData data) {
            long left = Math.max(10, startedAt + clearAt + 20 - ctx().currentTick());
            double damage = params().num("bar_damage");
            for (Block block : cells) {
                BoundingBox box = BoundingBox.of(block);
                for (LivingEntity enemy : ctx().enemiesNear(owner, block.getLocation().add(0.5, 0.5, 0.5), 1.5)) {
                    if (!enemy.getBoundingBox().overlaps(box) || !crushed.add(enemy.getUniqueId())) {
                        continue;
                    }
                    Combat.hit(ctx(), owner, enemy, damage);
                    if (Combat.isTargetable(ctx(), owner, enemy)) {
                        Motion.push(enemy, new Vector(0, Motion.launchSpeed(BAR_LIFT), 0), MAX_SPEED);
                    }
                    if (seen(owner, enemy)) {
                        Vfx.cloud(ctx(), Fx.body(enemy), Particle.CRIT, 6, 0.3, 0.1, null, false);
                    }
                }
            }
            for (Block block : cells) {
                if (!ctx().isInsideArena(block.getLocation().add(0.5, 0.5, 0.5))) {
                    continue;
                }
                if (occupied(block)) {
                    waiting.put(block, data); // goes in once whoever stands there is lifted out
                } else if (ctx().placeTempBlock(block, data, left)) {
                    placed.put(block, data.getMaterial());
                }
                world.spawnParticle(Particle.BLOCK, block.getX() + 0.5, floor + 0.1, block.getZ() + 0.5,
                        Vfx.n(ctx(), 3), 0.4, 0.05, 0.4, 0, data);
            }
            Location middle = cells.get(cells.size() / 2).getLocation().add(0.5, 0.5, 0.5);
            Fx.sound(middle, placeSound(data.getMaterial()), 1.3f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(middle, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 0.8f + 0.1f * dropped);
        }

        /** The board is full: all clear! Everything on it is thrown high and the board bursts. */
        private void allClear() {
            Location center = new Location(world, minX + size / 2.0, floor + 0.5, minZ + size / 2.0);
            if (reserve(1)) {
                TextDisplay banner = keep(Displays.text(ctx(), owner, center.clone().add(0, 3.2, 0),
                        Text.mm("<b><gradient:#FFFFFF:#FFD27F:#C0703F:#9C4A3A>올 클리어!</gradient></b>"), 0.4f, 40));
                banner.setViewRange(2.5f);
                Displays.animateNextTick(ctx(), owner, banner, Displays.centered(0, 0.4f, 0, 3.2f, 3.2f, 3.2f, 0, 0, 0),
                        5);
                ctx().runLater(owner, 30, () -> {
                    if (banner.isValid()) {
                        Displays.shrinkAway(ctx(), owner, banner, 8);
                    }
                });
            }
            BoundingBox area = new BoundingBox(minX - 0.3, floor - 0.5, minZ - 0.3, minX + size + 0.3, floor + 4,
                    minZ + size + 0.3);
            // The board goes first, then everyone on it is thrown up to clear_launch over the floor left under them
            // (thrown from the top of a bar, they would fall a block further).
            for (Map.Entry<Block, Material> entry : placed.entrySet()) {
                Block block = entry.getKey();
                if (block.getType() != entry.getValue()) {
                    continue;
                }
                BlockData data = block.getBlockData();
                if (ctx().restoreTempBlock(block) && ThreadLocalRandom.current().nextInt(2) == 0) {
                    world.spawnParticle(Particle.BLOCK, block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5,
                            Vfx.n(ctx(), 4), 0.3, 0.3, 0.3, 0, data, true);
                }
            }
            placed.clear();
            waiting.clear();
            double damage = params().num("clear_damage");
            double apex = Math.max(0, params().num("clear_launch"));
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, size)) {
                if (!enemy.getBoundingBox().overlaps(area)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (Combat.isTargetable(ctx(), owner, enemy) && apex > 0) {
                    pop(enemy, apex);
                }
            }
            Vfx.dot(center.clone().add(0, 1, 0), Particle.FLASH, FLASH_WHITE, true);
            Vfx.shockwave(ctx(), owner, center.clone().subtract(0, 0.4, 0), 0.5, size * 0.75, 6,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.6f), true);
            Vfx.cloud(ctx(), center.clone().add(0, 1, 0), Particle.FIREWORK, 40, size * 0.3, 0.15, null, true);
            Vfx.cloud(ctx(), center, Particle.DUST_COLOR_TRANSITION, 30, size * 0.35, 0, LINE_FLASH, true);
            Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.6f, 0.7f);
            Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.4f, 1.0f);
            Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f);
            Fx.sound(center, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            owner.sendActionBar(Text.mm("<" + COPPER_TAG + "><b>▦ 올 클리어!</b></" + COPPER_TAG + "> <white>" + hits
                    + "명 적중</white>"));
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The ground layer under the aimed enemy, else under the first block the view ray meets (on its top, or beside the
     * face it hit) or where the ray ends; null without ground there.
     */
    private @Nullable Location aimGround(Player owner, double range) {
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location point;
        if (aimed != null) {
            point = aimed.getLocation();
        } else {
            Location eye = owner.getEyeLocation();
            Vector direction = eye.getDirection();
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            if (hit == null) {
                point = eye.clone().add(direction.clone().multiply(range));
            } else if (hit.getHitBlockFace() == BlockFace.UP && hit.getHitBlock() != null) {
                point = hit.getHitBlock().getLocation().add(0.5, 1.0, 0.5);
            } else {
                point = hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.3));
            }
        }
        Location ground = point.clone().add(0, 0.5, 0);
        double before = ground.getY();
        Spots.snapToGround(ground.getWorld(), ground, GROUND_SCAN);
        if (ground.getY() >= before - 1.0E-6) {
            if (aimed == null || !Spots.hasGroundBelow(point.getWorld(), point)) {
                return null; // no ground near where the view ends
            }
            ground = point.clone(); // an aimed enemy stuck in (or hovering over) something: right where it is
        }
        if (!ctx().isInsideArena(ground)) {
            return null;
        }
        return Displays.unrotated(ground);
    }

    private static Sound placeSound(Material material) {
        return switch (material) {
            case OAK_PLANKS -> Sound.BLOCK_WOOD_PLACE;
            case WAXED_CUT_COPPER, WAXED_COPPER_BLOCK -> Sound.BLOCK_COPPER_PLACE;
            default -> Sound.BLOCK_STONE_PLACE;
        };
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) {
            wrapped -= 360f;
        } else if (wrapped < -180f) {
            wrapped += 360f;
        }
        return wrapped;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    private void popText(Location at, String mini) {
        if (!reserve(1)) {
            return;
        }
        Player owner = player();
        TextDisplay text = keep(Displays.text(ctx(), owner, at, Text.mm(mini), 0.5f, 18));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.35f, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0), 4);
        ctx().runLater(owner, 12, () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 5);
            }
        });
    }

    /** True unless {@code entity} is a player hidden from {@code owner} (stealth): nothing may reveal it. */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player hidden) || hidden.equals(owner) || owner.canSee(hidden);
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
