package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
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
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 건축가 — builds cover in a heartbeat. Stands firm on solid ground, throws up a brick wall in front of itself that
 * stops projectiles and shoves whoever stood in the way, and as the ultimate raises a small fortress with towers,
 * battlements and a partial roof in which it and its allies recover.
 *
 * <p>Look: brick red, plaster and oak, with cyan blueprint lines. Wall bricks fly from the builder's hands in a short
 * arc and turn into the real (temporary) blocks where they land. The fortress is first drawn as a glowing blueprint on
 * the ground, then rises layer by layer clockwise in puffs of mortar, gets red banners on its towers and a floating
 * countdown, and crumbles in a cloud of brick dust when the time is up.
 */
public final class BuilderAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("builder", "건축가", Material.BRICKS)
            .color(TextColor.color(0xC0583E))
            .difficulty(1)
            .passive("견고한 기반",
                    "땅을 딛고 서 있는 동안 받는 피해가 {stand_reduction}% 감소합니다.")
            .active("즉석 방벽",
                    "바라보는 방향 {wall_distance}블록 앞에 가로 {wall_width} × 높이 {wall_height} 벽돌 벽을 {wall_duration}초간 "
                            + "세웁니다(투사체 차단). 벽이 솟는 자리에 있던 적은 {wall_damage} 피해를 입고 밀려납니다.",
                    9)
            .ultimate("요새 건설",
                    "내 주위에 반경 {ult_size}블록의 작은 요새(성벽·망루·지붕 일부, 앞뒤 출입구)를 {ult_duration}초간 짓습니다. "
                            + "요새 안의 나와 아군은 초당 {ult_heal} 체력을 회복하고, 공사장 안의 적은 {ult_shove_damage} 피해를 "
                            + "입고 밖으로 밀려납니다.")
            .param("stand_reduction", 10)
            .param("wall_distance", 2)
            .param("wall_width", 3)
            .param("wall_height", 2)
            .param("wall_duration", 5)
            .param("wall_damage", 2)
            .param("ult_size", 3)
            .param("ult_duration", 8)
            .param("ult_heal", 1)
            .param("ult_shove_damage", 2)
            .build();

    private static final String COLOR_TAG = "#C0583E";
    /** Plaster highlight, brick red, dark oak. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE6C7, 0xC0583E, 0x5D3A1A);
    /** The cyan of blueprint lines. */
    private static final Color BLUEPRINT = Color.fromRGB(0x7FD3FF);
    private static final Particle.DustOptions BLUEPRINT_DUST = new Particle.DustOptions(BLUEPRINT, 0.9f);
    private static final Particle.DustOptions BRICK_DUST = PALETTE.dust(1.1f);
    private static final Particle.DustTransition MORTAR = PALETTE.glow(1.0f);
    private static final Particle.DustTransition SETTLE = PALETTE.fade(1.2f);

    private static final BlockData BRICKS = Material.BRICKS.createBlockData();
    private static final BlockData LOG = Material.OAK_LOG.createBlockData();
    private static final BlockData PLANKS = Material.OAK_PLANKS.createBlockData();
    private static final BlockData SLAB = Material.OAK_SLAB.createBlockData();

    private static final double MAX_SPEED = 2.5;

    // passive: firm footing
    private static final long FOOTING_FX_COOLDOWN = 10;

    // active: instant barricade
    private static final int MAX_WALL_WIDTH = 9;
    private static final int MAX_WALL_HEIGHT = 4;
    /** How far up or down a wall column may follow the terrain. */
    private static final int[] COLUMN_STEPS = {0, 1, -1, -2, -3};
    /** Bricks that fly in as displays (the rest just appear), to cap display entities. */
    private static final int MAX_FLYING_BRICKS = 12;
    private static final int ROW_STAGGER = 1;
    /** The flying brick's two legs: up and out to the arc's top, then down into place. */
    private static final int ARC_UP_TICKS = 2;
    private static final int ARC_DOWN_TICKS = 2;
    private static final double SHOVE_PUSH = 0.9;
    /** Runs (every 2 ticks) of mortar dust settling on a fresh wall. */
    private static final int SETTLE_RUNS = 6;
    private static final double SHOVE_LIFT = 0.35;

    // ultimate: fortress
    private static final int MIN_FORTRESS = 2;
    private static final int MAX_FORTRESS = 6;
    private static final int GROUND_SEARCH = 3;
    private static final long BLUEPRINT_TICKS = 6;
    private static final long STAGE_TICKS = 3;
    private static final int STAGES = 4;
    private static final int BRICKS_PER_STAGE = 4;
    private static final long HEAL_PERIOD = 10;
    private static final double FORTRESS_PUSH = 1.0;
    private static final double FORTRESS_LIFT = 0.5;
    private static final float BANNER_SCALE = 0.75f;
    private static final float BANNER_SWAY = 12f;

    private long nextFootingFx;

    /** One temporary block of a build: where, what, and in which step it goes up. */
    private record Cell(Block block, BlockData data, int stage) {

        Location center() {
            return block.getLocation().add(0.5, 0.5, 0.5);
        }
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: firm footing

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0) {
            return;
        }
        Tag<DamageType> bypass = DamageTypeTags.BYPASSES_INVULNERABILITY;
        if (bypass != null && bypass.isTagged(event.getDamageSource().getDamageType())) {
            return;
        }
        Player owner = player();
        double reduction = Math.clamp(params().num("stand_reduction") / 100.0, 0.0, 1.0);
        if (reduction <= 0 || owner.isInsideVehicle() || !Motion.isGrounded(owner)) {
            return;
        }
        event.setDamage(event.getDamage() * (1 - reduction));
        footing(owner, event.getDamage());
    }

    /** A blocked share of a hit: brick chips spring off the ground at the builder's feet. */
    private void footing(Player owner, double damage) {
        long now = ctx().currentTick();
        if (damage < 1.5 || now < nextFootingFx) {
            return;
        }
        nextFootingFx = now + FOOTING_FX_COOLDOWN;
        Location feet = owner.getLocation().add(0, 0.1, 0);
        feet.getWorld().spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 8), 0.3, 0.05, 0.3, 0.1, BRICKS);
        Vfx.ring(ctx(), feet, 0.55, 10, ThreadLocalRandom.current().nextDouble(Math.PI * 2), Particle.DUST,
                BRICK_DUST, false);
        Fx.sound(feet, Sound.BLOCK_MUD_BRICKS_HIT, 0.7f, 1.1f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ active: instant barricade

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location feet = owner.getLocation();
        World world = feet.getWorld();
        BlockFace facing = cardinal(feet.getYaw());
        int distance = Math.max(1, (int) Math.round(params().num("wall_distance")));
        int width = Math.clamp(params().integer("wall_width"), 1, MAX_WALL_WIDTH);
        int height = Math.clamp(params().integer("wall_height"), 1, MAX_WALL_HEIGHT);
        long duration = Math.max(1, params().ticks("wall_duration"));
        int cx = feet.getBlockX() + facing.getModX() * distance;
        int cz = feet.getBlockZ() + facing.getModZ() * distance;
        int sideX = -facing.getModZ();
        int sideZ = facing.getModX();
        int refY = feet.getBlockY();

        List<Cell> cells = new ArrayList<>();
        int first = -(width - 1) / 2;
        for (int w = first; w < first + width; w++) {
            int x = cx + sideX * w;
            int z = cz + sideZ * w;
            Integer base = columnBase(world, x, refY, z);
            if (base == null) {
                continue;
            }
            for (int h = 0; h < height; h++) {
                Block block = world.getBlockAt(x, base + h, z);
                if (!canPlace(block)) {
                    break;
                }
                cells.add(new Cell(block, BRICKS, h));
            }
        }
        if (cells.isEmpty()) {
            return ActivationResult.fail("벽을 세울 자리가 없습니다");
        }
        Vector forward = new Vector(facing.getModX(), 0, facing.getModZ());
        BoundingBox volume = bounds(cells, 0.15);
        int shoved = shove(owner, volume, forward, SHOVE_PUSH, SHOVE_LIFT, Math.max(0, params().num("wall_damage")));
        blueprintBox(world, bounds(cells, 0.02));
        raiseWall(owner, cells, duration);

        Location hands = hands(owner);
        Vfx.cloud(ctx(), hands, Particle.DUST_COLOR_TRANSITION, 8, 0.15, 0, MORTAR, false);
        Fx.sound(hands, Sound.ENTITY_VILLAGER_WORK_MASON, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(hands, Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 0.8f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">🧱 즉석 방벽</" + COLOR_TAG + ">"
                + (shoved > 0 ? " <white>" + shoved + "명 밀쳐냄</white>" : "")));
        return ActivationResult.success();
    }

    /**
     * Bricks fly in from the hands, bottom row first, and each one becomes a temporary block where it lands (unless
     * somebody is standing there by then: nobody is ever built into a wall).
     */
    private void raiseWall(Player owner, List<Cell> cells, long duration) {
        Location hands = hands(owner);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int flying = 0;
        long lastPlace = 0;
        for (Cell cell : cells) {
            long delay = (long) cell.stage() * ROW_STAGGER;
            long land = delay + ARC_UP_TICKS + ARC_DOWN_TICKS + 1;
            lastPlace = Math.max(lastPlace, land);
            BlockDisplay brick = null;
            if (flying++ < MAX_FLYING_BRICKS) {
                brick = flyingBrick(owner, hands, cell, delay, 1.0f, 0, random);
            }
            BlockDisplay shown = brick;
            ctx().runLater(owner, land, () -> {
                if (shown != null && shown.isValid()) {
                    shown.remove();
                }
                place(cell, duration);
            });
        }
        Location center = cells.get(cells.size() / 2).center();
        ctx().runLater(owner, lastPlace, () -> {
            Fx.sound(center, Sound.BLOCK_MUD_BRICKS_PLACE, 1.0f, 0.8f + Fx.jitter(0.08f));
            Fx.sound(center, Sound.BLOCK_STONE_PLACE, 0.8f, 0.7f + Fx.jitter(0.08f));
            Fx.sound(center, Sound.BLOCK_ANVIL_LAND, 0.25f, 1.6f + Fx.jitter(0.1f));
        });
        // Afterglow: mortar dust settling down the fresh wall.
        Loop.repeat(ctx(), owner, lastPlace + 1, 2, SETTLE_RUNS, run -> {
            for (int i = 0; i < Vfx.n(ctx(), 2); i++) {
                Cell cell = cells.get(random.nextInt(cells.size()));
                Location at = cell.center().add(random.nextDouble(-0.55, 0.55), random.nextDouble(0.1, 0.55),
                        random.nextDouble(-0.55, 0.55));
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, SETTLE, false);
                if (run % 2 == 0) {
                    Vfx.dot(at, Particle.WHITE_ASH, null, false);
                }
            }
            return true;
        });
        ctx().runLater(owner, lastPlace + Math.max(1, duration - 2), () -> crumble(cells));
    }

    /** The plan of a build: a glowing cyan wireframe of {@code box}, drawn for an instant before it goes up. */
    private void blueprintBox(World world, BoundingBox box) {
        double[] xs = {box.getMinX(), box.getMaxX()};
        double[] ys = {box.getMinY(), box.getMaxY()};
        double[] zs = {box.getMinZ(), box.getMaxZ()};
        for (double y : ys) {
            for (double z : zs) {
                blueprintLine(new Location(world, xs[0], y, z), new Location(world, xs[1], y, z));
            }
            for (double x : xs) {
                blueprintLine(new Location(world, x, y, zs[0]), new Location(world, x, y, zs[1]));
            }
        }
        for (double x : xs) {
            for (double z : zs) {
                blueprintLine(new Location(world, x, ys[0], z), new Location(world, x, ys[1], z));
            }
        }
        Fx.sound(box.getCenter().toLocation(world), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.4f + Fx.jitter(0.1f));
    }

    private void blueprintLine(Location from, Location to) {
        Vfx.line(ctx(), from, to, 2.5, Particle.DUST, BLUEPRINT_DUST, false);
    }

    /**
     * A spinning little brick thrown in an arc from the hands to {@code lift} blocks above the cell, ending at
     * {@code landScale} (a full block that turns into the real one at 1).
     */
    private BlockDisplay flyingBrick(Player owner, Location hands, Cell cell, long delay, float landScale, double lift,
                                     ThreadLocalRandom random) {
        Location target = flat(cell.center().add(0, lift, 0));
        Location top = flat(hands.clone().add(target).multiply(0.5).add(0, 1.0 + 0.3 * cell.stage(), 0));
        BlockDisplay brick = Displays.block(ctx(), owner, flat(hands), cell.data(),
                Displays.transform(0, 0, 0, 0.25f, 0.25f, 0.25f, random.nextFloat() * 360, random.nextFloat() * 90, 0),
                delay + ARC_UP_TICKS + ARC_DOWN_TICKS + 4);
        brick.setTeleportDuration(ARC_UP_TICKS);
        ctx().runLater(owner, delay + 1, () -> {
            if (brick.isValid()) {
                brick.teleport(top);
                Displays.animate(brick, Displays.transform(0, 0, 0, 0.55f, 0.55f, 0.55f, random.nextFloat() * 360,
                        random.nextFloat() * 180, 0), ARC_UP_TICKS);
            }
        });
        ctx().runLater(owner, delay + 1 + ARC_UP_TICKS, () -> {
            if (brick.isValid()) {
                brick.setTeleportDuration(ARC_DOWN_TICKS);
                brick.teleport(target);
                Displays.animate(brick, Displays.scaled(landScale), ARC_DOWN_TICKS);
            }
        });
        return brick;
    }

    /** Places one temporary block with a puff of mortar; false if it was refused or someone stands there. */
    private boolean place(Cell cell, long duration) {
        Block block = cell.block();
        if (isOccupied(block) || !canPlace(block) || !ctx().placeTempBlock(block, cell.data(), duration)) {
            Vfx.cloud(ctx(), cell.center(), Particle.BLOCK, 3, 0.25, 0.05, cell.data(), false);
            return false;
        }
        Location center = cell.center();
        World world = center.getWorld();
        world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 5), 0.35, 0.35, 0.35, 0.05, cell.data());
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, center, Vfx.n(ctx(), 3), 0.4, 0.4, 0.4, 0, MORTAR);
        return true;
    }

    /**
     * Enemies whose hitbox overlaps {@code volume} take {@code damage} and are thrown out: along {@code push}, or away
     * from the volume's middle when {@code push} is zero. Returns how many were hit.
     */
    private int shove(Player owner, BoundingBox volume, Vector push, double speed, double lift, double damage) {
        Location center = volume.getCenter().toLocation(owner.getWorld());
        double reach = volume.getWidthX() + volume.getWidthZ() + volume.getHeight();
        int count = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, reach)) {
            if (!enemy.getBoundingBox().overlaps(volume)) {
                continue;
            }
            count++;
            Combat.hit(ctx(), owner, enemy, damage);
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            Vector direction = push.lengthSquared() > 1.0E-6 ? Motion.horizontal(push)
                    : Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
            if (direction.lengthSquared() < 1.0E-6) {
                double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                direction = new Vector(Math.cos(angle), 0, Math.sin(angle));
            }
            Motion.push(enemy, direction.multiply(speed).setY(lift), MAX_SPEED);
            Location body = Fx.body(enemy);
            Vfx.burst(ctx(), body, PALETTE, 0.5, Particle.CRIT, false);
            body.getWorld().spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 8), 0.3, 0.4, 0.3, 0.1, BRICKS);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.9f, 0.9f + Fx.jitter(0.1f));
        }
        return count;
    }

    // ------------------------------------------------------------------ ultimate: fortress

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location feet = owner.getLocation();
        Integer floor = groundLevel(feet);
        if (floor == null) {
            return ActivationResult.fail("공중에서는 요새를 지을 수 없습니다");
        }
        int size = Math.clamp(params().integer("ult_size"), MIN_FORTRESS, MAX_FORTRESS);
        long duration = Math.max(BLUEPRINT_TICKS + STAGES * STAGE_TICKS + 20, params().ticks("ult_duration"));
        Fortress fortress = new Fortress(owner, feet.getBlockX(), floor, feet.getBlockZ(), size,
                cardinal(feet.getYaw()), duration);
        if (fortress.cells.isEmpty()) {
            return ActivationResult.fail("요새를 지을 자리가 없습니다");
        }
        fortress.begin();
        return ActivationResult.success();
    }

    /**
     * The fortress: a ring of brick walls with oak corner towers, battlements on every other block, oak lintels over a
     * front and a back gate and a partial roof of oak slabs in the corners. Everything is temporary and goes down
     * together when the time is up.
     */
    private final class Fortress implements LongPredicate {

        private final Player owner;
        private final int cx;
        private final int floor;
        private final int cz;
        private final int size;
        private final long duration;
        private final World world;
        private final List<Cell> cells = new ArrayList<>();
        private final List<List<Cell>> stages = new ArrayList<>();
        private final List<Cell> corners = new ArrayList<>();
        private final List<ItemDisplay> banners = new ArrayList<>();
        private @Nullable TextDisplay label;

        Fortress(Player owner, int cx, int floor, int cz, int size, BlockFace front, long duration) {
            this.owner = owner;
            this.cx = cx;
            this.floor = floor;
            this.cz = cz;
            this.size = size;
            this.duration = duration;
            this.world = owner.getWorld();
            for (int s = 0; s < STAGES; s++) {
                stages.add(new ArrayList<>());
            }
            plan(front);
        }

        /** Lays out every block of the fortress, sorted clockwise within each step so the walls grow around. */
        private void plan(BlockFace front) {
            int doorX = front.getModX() * size;
            int doorZ = front.getModZ() * size;
            for (int dx = -size; dx <= size; dx++) {
                for (int dz = -size; dz <= size; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != size) {
                        continue;
                    }
                    Integer base = columnBase(world, cx + dx, floor, cz + dz);
                    if (base == null) {
                        continue;
                    }
                    boolean corner = Math.abs(dx) == size && Math.abs(dz) == size;
                    boolean door = dx == doorX && dz == doorZ || dx == -doorX && dz == -doorZ;
                    if (corner) {
                        add(cx + dx, base, cz + dz, LOG, 0);
                        add(cx + dx, base + 1, cz + dz, LOG, 1);
                        add(cx + dx, base + 2, cz + dz, LOG, 2);
                        Cell cap = add(cx + dx, base + 3, cz + dz, SLAB, 3);
                        if (cap != null) {
                            corners.add(cap);
                        }
                    } else if (door) {
                        add(cx + dx, base + 2, cz + dz, PLANKS, 2);
                    } else {
                        add(cx + dx, base, cz + dz, BRICKS, 0);
                        add(cx + dx, base + 1, cz + dz, BRICKS, 1);
                        if (((dx + dz) & 1) == 0) {
                            add(cx + dx, base + 2, cz + dz, BRICKS, 2);
                        }
                    }
                }
            }
            // The partial roof: a canopy of slabs over each inner corner, head height above the floor.
            Set<Long> canopy = new LinkedHashSet<>();
            int inner = size - 1;
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    canopy.add(key(sx * inner, sz * inner));
                    canopy.add(key(sx * inner, sz * (inner - 1)));
                    canopy.add(key(sx * (inner - 1), sz * inner));
                }
            }
            for (long packed : canopy) {
                int dx = (int) (packed >> 32);
                int dz = (int) packed;
                if (dx != 0 || dz != 0) {
                    add(cx + dx, floor + 2, cz + dz, SLAB, 3);
                }
            }
            Location middle = middle();
            for (List<Cell> stage : stages) {
                stage.sort(Comparator.comparingDouble(cell -> angle(cell.center(), middle)));
            }
        }

        private @Nullable Cell add(int x, int y, int z, BlockData data, int stage) {
            Block block = world.getBlockAt(x, y, z);
            if (!canPlace(block)) {
                return null;
            }
            Cell cell = new Cell(block, data, stage);
            cells.add(cell);
            stages.get(stage).add(cell);
            return cell;
        }

        void begin() {
            Location middle = middle();
            Location ground = middle.clone().add(0, 0.05, 0);
            Vfx.dot(ground.clone().add(0, 1, 0), Particle.FLASH, BLUEPRINT, true);
            Fx.sound(middle, Sound.ENTITY_VILLAGER_WORK_MASON, 1.2f, 0.9f);
            Fx.sound(middle, Sound.BLOCK_SCAFFOLDING_PLACE, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(middle, Sound.BLOCK_ANVIL_USE, 0.5f, 1.4f + Fx.jitter(0.05f));
            int shoved = shove(owner, footprint(), new Vector(), FORTRESS_PUSH, FORTRESS_LIFT,
                    Math.max(0, params().num("ult_shove_damage")));
            if (shoved > 0) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">🏰 공사장 정리!</" + COLOR_TAG + "> <white>" + shoved
                        + "명 밀쳐냄</white>"));
            }
            Loop.repeat(ctx(), owner, 0, 1, duration, this);
        }

        @Override
        public boolean test(long tick) {
            if (tick < BLUEPRINT_TICKS) {
                blueprint(tick);
                return true;
            }
            long build = tick - BLUEPRINT_TICKS;
            if (build < STAGES * STAGE_TICKS) {
                construct((int) (build / STAGE_TICKS), build % STAGE_TICKS);
            } else if (build == STAGES * STAGE_TICKS) {
                complete();
            }
            if (tick % HEAL_PERIOD == 0) {
                heal();
            }
            if (tick % 6 == 0) {
                ambient();
            }
            if (label != null && label.isValid() && tick % 20 == 0) {
                label.text(Text.mm(labelText(duration - tick)));
            }
            if (tick % 10 == 0) {
                sway(tick);
            }
            if (tick >= duration - 1) {
                crumbleAll();
                return false;
            }
            return true;
        }

        /** The plan is drawn on the ground first: cyan lines running round the footprint, posts at the corners. */
        private void blueprint(long tick) {
            double y = floor + 0.08;
            double min = -size;
            double max = size + 1.0;
            double side = max - min;
            double perimeter = side * 4;
            double drawn = perimeter * (tick + 1) / BLUEPRINT_TICKS;
            int points = Vfx.n(ctx(), (int) Math.round(perimeter * 2));
            for (int i = 0; i <= points; i++) {
                double s = perimeter * i / points;
                if (s > drawn) {
                    break;
                }
                double[] xz = perimeterPoint(s, side, min);
                Vfx.dot(new Location(world, cx + xz[0], y, cz + xz[1]), Particle.DUST, BLUEPRINT_DUST, true);
            }
            if (tick == BLUEPRINT_TICKS - 1) {
                for (int sx = 0; sx <= 1; sx++) {
                    for (int sz = 0; sz <= 1; sz++) {
                        Location post = new Location(world, cx + (sx == 0 ? min : max), y, cz + (sz == 0 ? min : max));
                        Vfx.line(ctx(), post, post.clone().add(0, 3.2, 0), 3, Particle.END_ROD, null, true);
                    }
                }
                Fx.sound(middle(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.2f);
            }
        }

        /** One tick of a building step: its share of the blocks goes up, with flying bricks and mortar puffs. */
        private void construct(int stage, long step) {
            List<Cell> work = stages.get(stage);
            if (step == 0 && stage < STAGES - 1) {
                throwBricks(work);
            }
            int from = (int) (work.size() * step / STAGE_TICKS);
            int to = (int) (work.size() * (step + 1) / STAGE_TICKS);
            long remaining = duration - BLUEPRINT_TICKS - (long) stage * STAGE_TICKS - step;
            int placed = 0;
            for (int i = from; i < to; i++) {
                Cell cell = work.get(i);
                if (place(cell, Math.max(1, remaining))) {
                    placed++;
                    if (i % 3 == 0) {
                        world.spawnParticle(Particle.DUST_PLUME, cell.center().add(0, -0.4, 0), 1, 0.2, 0, 0.2, 0.01);
                    }
                }
            }
            if (placed > 0) {
                Location at = work.get(from).center();
                Sound sound = work.get(from).data().getMaterial() == Material.BRICKS ? Sound.BLOCK_MUD_BRICKS_PLACE
                        : Sound.BLOCK_WOOD_PLACE;
                Fx.sound(at, sound, 1.0f, 0.8f + 0.1f * stage + Fx.jitter(0.08f));
                Fx.sound(at, Sound.BLOCK_STONE_PLACE, 0.6f, 0.9f + Fx.jitter(0.1f));
            }
        }

        /** A handful of bricks thrown from the builder's hands onto blocks of this step. */
        private void throwBricks(List<Cell> work) {
            if (work.isEmpty() || !owner.isOnline()) {
                return;
            }
            Location hands = hands(owner);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Math.min(BRICKS_PER_STAGE, work.size()); i++) {
                Cell cell = work.get(random.nextInt(work.size()));
                BlockDisplay brick = flyingBrick(owner, hands, cell, 0, 0.45f, 0.9, random);
                ctx().runLater(owner, ARC_UP_TICKS + ARC_DOWN_TICKS + 1, () -> {
                    if (brick.isValid()) {
                        brick.getWorld().spawnParticle(Particle.BLOCK, brick.getLocation(), Vfx.n(ctx(), 4), 0.2,
                                0.2, 0.2, 0.05, cell.data());
                        brick.remove();
                    }
                });
            }
        }

        /** Done: banners unfurl on the towers, a countdown floats over the keep and a bell rings. */
        private void complete() {
            Location middle = middle();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (Cell cap : corners) {
                if (cap.block().getType() != Material.OAK_SLAB) {
                    continue;
                }
                Location at = flat(cap.block().getLocation().add(0.5, 1.15, 0.5));
                float yaw = (float) Math.toDegrees(Math.atan2(-(at.getX() - middle.getX()), at.getZ() - middle.getZ()));
                ItemDisplay banner = Displays.item(ctx(), owner, at, new ItemStack(Material.RED_BANNER),
                        Displays.centered(0, -0.4f, 0, 0.05f, 0.05f, 0.05f, yaw, 0, 0), duration);
                Displays.animateNextTick(ctx(), owner, banner, Displays.centered(0, 0, 0, BANNER_SCALE, BANNER_SCALE,
                        BANNER_SCALE, yaw + random.nextFloat() * 6, 0, 0), 5);
                banners.add(banner);
            }
            Location top = flat(middle.clone().add(0, 3.4, 0));
            label = Displays.text(ctx(), owner, top, Text.mm(labelText(duration - BLUEPRINT_TICKS
                    - STAGES * STAGE_TICKS)), 0.01f, duration);
            Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0), 4);
            Vfx.shockwave(ctx(), owner, middle.clone().add(0, 0.1, 0), 0.5, size + 0.5, 6,
                    Particle.DUST_COLOR_TRANSITION, MORTAR, true);
            Vfx.cloud(ctx(), middle.clone().add(0, 1.5, 0), Particle.WAX_ON, 14, size * 0.5, 0.1, null, true);
            Fx.sound(middle, Sound.BLOCK_BELL_USE, 1.2f, 1.0f);
            Fx.sound(middle, Sound.BLOCK_ANVIL_USE, 0.6f, 1.1f + Fx.jitter(0.05f));
            Fx.sound(middle, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.4f);
            if (owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>🏰 요새 완공!</b></" + COLOR_TAG + ">"));
            }
        }

        private String labelText(long ticksLeft) {
            return "<b><gradient:#FFE6C7:" + COLOR_TAG + ">요새</gradient></b> <white>"
                    + Fx.seconds(Math.max(0, ticksLeft)) + "초</white>";
        }

        /** The builder and allies inside recover; healing motes show who is being restored. */
        private void heal() {
            double amount = Math.max(0, params().num("ult_heal")) * HEAL_PERIOD / 20.0;
            if (amount <= 0) {
                return;
            }
            List<LivingEntity> inside = new ArrayList<>();
            if (isInside(owner)) {
                inside.add(owner);
            }
            for (Player ally : ctx().alliesNear(owner, middle(), size * 1.5)) {
                if (isInside(ally)) {
                    inside.add(ally);
                }
            }
            for (LivingEntity entity : inside) {
                ctx().heal(entity, amount);
                Location body = Fx.body(entity);
                Vfx.cloud(ctx(), body, Particle.HAPPY_VILLAGER, 3, 0.35, 0, null, false);
                Vfx.trail(body.clone().add(0, -0.8, 0), body.clone().add(0, 0.9, 0), PALETTE.core(), 10, false);
            }
        }

        /** Warm motes rising from the courtyard while the fortress stands. */
        private void ambient() {
            Location middle = middle();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Location from = Vfx.scatter(middle, size - 0.8).add(0, 0.1, 0);
                Vfx.trail(from, from.clone().add(0, 1.2 + random.nextDouble(), 0),
                        i % 2 == 0 ? PALETTE.core() : PALETTE.body(), 14 + random.nextInt(8), false);
            }
        }

        /** The banners sway in the wind. */
        private void sway(long tick) {
            float swing = (tick / 10) % 2 == 0 ? BANNER_SWAY : -BANNER_SWAY;
            Location middle = middle();
            for (ItemDisplay banner : banners) {
                if (!banner.isValid()) {
                    continue;
                }
                Location at = banner.getLocation();
                float yaw = (float) Math.toDegrees(Math.atan2(-(at.getX() - middle.getX()), at.getZ() - middle.getZ()));
                Displays.animate(banner, Displays.centered(0, 0, 0, BANNER_SCALE, BANNER_SCALE, BANNER_SCALE,
                        yaw + swing, 0, swing * 0.4f), 10);
            }
        }

        /** Time is up: the walls burst into brick dust (the temporary blocks restore themselves right after). */
        private void crumbleAll() {
            crumble(cells);
            for (ItemDisplay banner : banners) {
                if (banner.isValid()) {
                    Displays.shrinkAway(ctx(), owner, banner, 6);
                }
            }
            TextDisplay text = label;
            if (text != null && text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 4);
            }
            Location middle = middle();
            Fx.sound(middle, Sound.BLOCK_WOOD_BREAK, 1.0f, 0.8f + Fx.jitter(0.05f));
        }

        private boolean isInside(LivingEntity entity) {
            if (!entity.isValid() || entity.isDead() || !entity.getWorld().equals(world)) {
                return false;
            }
            Location at = entity.getLocation();
            double reach = size - 0.3;
            return Math.abs(at.getX() - (cx + 0.5)) <= reach && Math.abs(at.getZ() - (cz + 0.5)) <= reach
                    && at.getY() >= floor - 1 && at.getY() <= floor + 3;
        }

        private BoundingBox footprint() {
            return new BoundingBox(cx - size, floor - 1, cz - size, cx + size + 1, floor + 4, cz + size + 1);
        }

        private Location middle() {
            return new Location(world, cx + 0.5, floor, cz + 0.5);
        }

        /** The point {@code s} blocks along the footprint's outline, starting at its min corner. */
        private static double[] perimeterPoint(double s, double side, double min) {
            int edge = (int) Math.min(3, Math.floor(s / side));
            double t = s - edge * side;
            return switch (edge) {
                case 0 -> new double[]{min + t, min};
                case 1 -> new double[]{min + side, min + t};
                case 2 -> new double[]{min + side - t, min + side};
                default -> new double[]{min, min + side - t};
            };
        }

        private static long key(int dx, int dz) {
            return ((long) dx << 32) | (dz & 0xFFFFFFFFL);
        }
    }

    // ------------------------------------------------------------------ shared

    /** The temporary blocks give way: bursts of their own material and brick dust. */
    private void crumble(List<Cell> built) {
        int shown = 0;
        Location sound = null;
        for (Cell cell : built) {
            if (cell.block().getType() != cell.data().getMaterial()) {
                continue;
            }
            if (shown++ % 2 != 0) {
                continue;
            }
            Location center = cell.center();
            center.getWorld().spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 6), 0.35, 0.35, 0.35, 0.1,
                    cell.data());
            Vfx.dot(center, Particle.DUST_COLOR_TRANSITION, SETTLE, false);
            if (sound == null) {
                sound = center;
            }
        }
        if (sound != null) {
            Fx.sound(sound, Sound.BLOCK_MUD_BRICKS_BREAK, 1.0f, 0.8f + Fx.jitter(0.08f));
            Fx.sound(sound, Sound.BLOCK_STONE_BREAK, 0.8f, 0.7f + Fx.jitter(0.08f));
        }
    }

    /**
     * The lowest free cell of a column near {@code refY} that stands on something solid: the same height first, then
     * one up and up to three down, so walls follow gentle terrain. Null if there is none.
     */
    private static @Nullable Integer columnBase(World world, int x, int refY, int z) {
        for (int step : COLUMN_STEPS) {
            int y = refY + step;
            if (y - 1 < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            Block block = world.getBlockAt(x, y, z);
            Block below = world.getBlockAt(x, y - 1, z);
            if (canPlace(block) && below.getType().isSolid() && !below.isPassable()) {
                return y;
            }
        }
        return null;
    }

    /** The block height the owner stands on (its feet level), searching a little below; null in mid-air. */
    private static @Nullable Integer groundLevel(Location feet) {
        World world = feet.getWorld();
        int x = feet.getBlockX();
        int z = feet.getBlockZ();
        for (int y = feet.getBlockY(); y >= feet.getBlockY() - GROUND_SEARCH && y - 1 >= world.getMinHeight(); y--) {
            if (!world.getBlockAt(x, y - 1, z).isPassable()) {
                return y;
            }
        }
        return null;
    }

    /** Air or a replaceable block (grass, flowers, snow...) inside the world: the temp block service accepts it. */
    private static boolean canPlace(Block block) {
        World world = block.getWorld();
        return block.getY() >= world.getMinHeight() && block.getY() < world.getMaxHeight()
                && (block.isEmpty() || block.isReplaceable());
    }

    private static boolean isOccupied(Block block) {
        BoundingBox cell = BoundingBox.of(block);
        return !block.getWorld().getNearbyEntities(cell, entity -> entity instanceof LivingEntity).isEmpty();
    }

    /** The union of the cells' boxes, grown by {@code margin}. */
    private static BoundingBox bounds(List<Cell> cells, double margin) {
        BoundingBox box = BoundingBox.of(cells.getFirst().block());
        for (Cell cell : cells) {
            box.union(BoundingBox.of(cell.block()));
        }
        return box.expand(margin);
    }

    /** The cardinal direction closest to a yaw. */
    private static BlockFace cardinal(float yaw) {
        int quarter = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (quarter) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    private static Location hands(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(eye.getDirection().multiply(0.5)).subtract(0, 0.45, 0);
    }

    /** Horizontal angle of {@code at} around {@code middle}, for clockwise building order. */
    private static double angle(Location at, Location middle) {
        return Math.atan2(at.getZ() - middle.getZ(), at.getX() - middle.getX());
    }

    /** A copy without yaw and pitch, so display entities are not turned by the entity rotation as well. */
    private static Location flat(Location location) {
        Location copy = location.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
