package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 폴터가이스트 — the noisy ghost: nobody is there, yet the plates fly and the furniture rattles. In a fight blocks tear
 * loose from the walls around it (or ghostly pots and frames appear out of thin air where nothing may break) and float
 * around it, each taking a ranged hit in its place; the active hurls them, and the ultimate brings the whole room to
 * life: chunks of the very cover the enemies hide behind rip out and slam into them.
 *
 * <p>Look (dusty purple, ghost cyan, plaster beige): the floating things bob around the ghost wrapped in cyan wisps, a
 * torn block first rattles in place, then flies up into the orbit. Thrown things tumble along a faint trail and burst
 * into their own crumbs; a ghostly object shimmers in. During the uproar the area hums with drifting motes, cover
 * shakes in warning before it tears loose, and every slam ends in plaster dust.
 */
public final class PoltergeistAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("poltergeist", "폴터가이스트", Material.DECORATED_POT)
            .color(TextColor.color(0x7FE3E0))
            .difficulty(2)
            .passive("덜컹덜컹",
                    "{passive_range}블록 안에 적이 있으면 {rattle_interval}초마다 주변 {grab_radius}블록의 벽이나 소품에서"
                            + "(없으면 바닥에서) 블록 하나가 덜컹 뜯겨 내 주위를 떠다닙니다(최대 {orbit_max}개, 누군가 서 "
                            + "있는 바닥은 뜯지 않고 한 목숨에 최대 {grab_life_max}칸). 뜯을 수 없는 곳에서는 허공에서 "
                            + "유령 소품이 나타납니다. 떠다니는 물건은 나를 노린 원거리 공격(투사체, {ranged_distance}블록 "
                            + "밖의 능력 공격) 하나를 대신 맞고 부서집니다.")
            .active("집어던지기",
                    "떠다니는 물건과 바라보는 곳({tear_range}블록 이내)에서 새로 뜯은 블록을 합쳐 {throw_count}개를 "
                            + "{throw_interval}초 간격으로 조준한 곳({throw_range}블록 이내)에 던집니다. 물건마다 반경 "
                            + "{throw_radius}블록의 적에게 {throw_damage} 피해를 줍니다(한 적은 최대 {throw_max_hits}번). "
                            + "전투 맵에서는 벽에서 뜯은 자리가 구멍으로 남아 시야와 길이 열리고, 다른 곳에서는 유령 소품을 "
                            + "던집니다.",
                    7)
            .ultimate("대소동",
                    "{chaos_duration}초간 반경 {chaos_radius}블록의 가구와 벽이 살아납니다. 1초마다 적(최대 "
                            + "{chaos_targets}명)마다 그 적에게서 {cover_range}블록 안의 벽이나 엄폐물이 {chaos_warning}초 "
                            + "덜컹인 뒤 덩어리째 뜯겨 날아와, 맞은 적은 {chaos_damage} 피해를 입고 벽 반대쪽으로 밀려나며 "
                            + "{stagger_duration}초간 휘청입니다(이동·공격 속도 {stagger_slow}% 감소, 적마다 최대 "
                            + "{chaos_max_hits}번). 전투 맵에서는 뜯긴 자리가 구멍으로 남아 엄폐물이 사라지고, 보호된 "
                            + "맵에서는 벽 대신 그 적 곁에 유령 가구가 나타나 똑같이 부딪힙니다. 탁 트인 곳에 선 적은 "
                            + "무사합니다.")
            .param("passive_range", 12)
            .param("rattle_interval", 6)
            .param("grab_radius", 4)
            .param("orbit_max", 3)
            .param("grab_life_max", 20)
            .param("ranged_distance", 6)
            .param("tear_block_radius", 0.5)
            .param("tear_range", 6)
            .param("throw_count", 4)
            .param("throw_interval", 0.2)
            .param("throw_range", 20)
            .param("throw_radius", 1.2)
            .param("throw_damage", 2)
            .param("throw_max_hits", 3)
            .param("chaos_duration", 6)
            .param("chaos_radius", 12)
            .param("chaos_targets", 3)
            .param("cover_range", 3)
            .param("chaos_warning", 0.3)
            .param("chaos_damage", 2)
            .param("chaos_block_radius", 1.2)
            .param("stagger_duration", 0.5)
            .param("stagger_slow", 50)
            .param("chaos_max_hits", 4)
            .build();

    private static final String GHOST_TAG = "#7FE3E0";
    private static final String DUST_TAG = "#7C6A92";

    // ------------------------------------------------------------------ look
    /** Pale ghost light, ghost cyan, dusty purple. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE6FFFE, 0x7FE3E0, 0x7C6A92);
    private static final Color GHOST = Color.fromRGB(0x7FE3E0);
    private static final Color DUSTY = Color.fromRGB(0x7C6A92);
    private static final Particle.DustOptions GHOST_DUST = new Particle.DustOptions(GHOST, 0.8f);
    private static final Particle.DustOptions PLASTER_DUST = new Particle.DustOptions(Color.fromRGB(0xD9CFBF), 1.3f);
    private static final Particle.DustTransition WISP = new Particle.DustTransition(GHOST, DUSTY, 0.7f);

    /** The ghostly odds and ends that appear where nothing may be torn loose. */
    private static final Material[] GHOST_ITEMS = {Material.DECORATED_POT, Material.FLOWER_POT, Material.LANTERN,
            Material.BOOK, Material.CANDLE, Material.CLOCK, Material.PAINTING, Material.BOWL};
    /** The ghostly furniture of the uproar: a bookcase, a chair, a picture, a pot. */
    private static final Material WARDROBE = Material.BOOKSHELF;
    private static final Material CHAIR = Material.OAK_STAIRS;
    private static final Material FRAME = Material.PAINTING;
    private static final Material POT = Material.DECORATED_POT;

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 2.0;

    // passive
    private static final double ORBIT_RADIUS = 1.15;
    private static final float ORBIT_SCALE = 0.5f;
    private static final float ITEM_SCALE = 0.7f;
    private static final long RATTLE_TICKS = 6;
    private static final long RISE_TICKS = 6;
    private static final long ORBIT_LIFETIME = 20L * 60 * 30;

    // active
    private static final double THROW_SPEED = 1.3;
    private static final int THROW_MAX_TICKS = 30;
    private static final double THROW_PUSH = 0.35;

    // ultimate
    private static final long PULSE_PERIOD = 20;
    private static final long CHAOS_FLIGHT = 5;
    private static final int CHUNK_PIECES = 4;
    private static final double SLAM_REACH = 1.4;
    private static final double SLAM_PUSH = 0.9;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final List<Orbiter> orbit = new ArrayList<>();
    private long nextRattle = Long.MIN_VALUE / 2;
    private int torn;
    private boolean fighting;

    /** One floating thing: its display and what it is (a torn block or a ghostly item). */
    private record Orbiter(Display display, @Nullable BlockData block, @Nullable Material item) {
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        orbit.clear();
        live.clear();
    }

    // ------------------------------------------------------------------ passive: rattle

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        orbit.removeIf(orbiter -> !orbiter.display().isValid());
        // In a fight: an enemy around that the ghost can see (a hidden one must not set anything rattling).
        boolean combat = ctx().enemiesNear(owner, owner.getLocation(), params().num("passive_range")).stream()
                .anyMatch(enemy -> seen(owner, enemy));
        if (combat && !fighting) {
            nextRattle = Math.max(nextRattle, now + Math.max(20, params().ticks("rattle_interval")));
        }
        fighting = combat;
        if (combat && now >= nextRattle && orbit.size() < params().integer("orbit_max")) {
            nextRattle = now + Math.max(20, params().ticks("rattle_interval"));
            rattle(owner);
        }
        drift(owner, tick);
    }

    /** The floating things bob around the ghost, turning slowly, wrapped in cyan wisps. */
    private void drift(Player owner, long tick) {
        int count = orbit.size();
        if (count == 0) {
            return;
        }
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        boolean shown = !owner.isInvisible();
        for (int i = 0; i < count; i++) {
            Orbiter orbiter = orbit.get(i);
            Display display = orbiter.display();
            double angle = tick * 0.07 + i * Math.PI * 2 / count;
            double bob = Math.sin(tick * 0.15 + i * 1.7) * 0.18;
            Location at = body.clone().add(Math.cos(angle) * ORBIT_RADIUS, bob, Math.sin(angle) * ORBIT_RADIUS);
            Displays.moveTo(display, at);
            display.setViewRange(shown ? 1.0f : 0f);
            if (tick % 4 == i % 4) {
                Displays.animate(display, pose(orbiter, ORBIT_SCALE, tick * 9f + i * 40f, tick * 5f), 4);
            }
            if (shown && tick % 6 == i % 6) {
                Vfx.dot(at.clone().add(0, -0.25, 0), Particle.DUST_COLOR_TRANSITION, WISP, false);
            }
        }
    }

    /** Something nearby tears loose (or a ghostly thing appears), rattles in place and floats up into the orbit. */
    private void rattle(Player owner) {
        if (!reserve(1)) {
            return;
        }
        Orbiter orbiter = tearNear(owner);
        if (orbiter == null) {
            return;
        }
        Display display = orbiter.display();
        Location start = display.getLocation();
        display.setTeleportDuration(1);
        Loop.repeat(ctx(), owner, 1, 1, RATTLE_TICKS + RISE_TICKS, run -> {
            if (!display.isValid()) {
                return false;
            }
            if (run < RATTLE_TICKS) {
                Displays.moveTo(display, start.clone().add(Fx.jitter(0.08f), Fx.jitter(0.05f), Fx.jitter(0.08f)));
                if (run % 2 == 0) {
                    Fx.sound(start, Sound.BLOCK_CHEST_LOCKED, 0.6f, 1.2f + Fx.jitter(0.2f));
                }
            } else if (run == RATTLE_TICKS) {
                display.setTeleportDuration((int) RISE_TICKS);
                Location goal = owner.getLocation().add(0, owner.getHeight() * 0.55 + 0.3, 0);
                Displays.moveTo(display, goal);
                Vfx.trail(start, goal, GHOST, (int) RISE_TICKS, false);
                Fx.sound(start, Sound.ENTITY_VEX_AMBIENT, 0.7f, 1.4f + Fx.jitter(0.1f));
            }
            return true;
        }, () -> {
            if (display.isValid()) {
                display.setTeleportDuration(2);
                orbit.add(orbiter);
            }
        });
    }

    /**
     * Tears loose a block within {@code grab_radius}: a piece of wall or a prop at body height first, else the floor
     * (never under someone standing on it), through {@link io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext#breakBlocks}
     * — only where blocks may break, at most {@code grab_life_max} per life. Elsewhere a ghostly item appears.
     */
    private @Nullable Orbiter tearNear(Player owner) {
        Location feet = owner.getLocation();
        Block source = wallNear(feet, params().num("grab_radius"));
        if (source == null) {
            source = floorNear(owner, feet, params().num("grab_radius"));
        }
        Orbiter real = source == null ? null : tear(owner, source);
        if (real != null) {
            return real;
        }
        Location spot = source != null ? source.getLocation().add(0.5, 1.2, 0.5)
                : feet.clone().add(Fx.jitter(1.5f), 0.4, Fx.jitter(1.5f));
        return ghost(owner, spot);
    }

    /** Breaks {@code block} (one block) and returns a display of it, null where nothing may break. */
    private @Nullable Orbiter tear(Player owner, Block block) {
        double radius = params().num("tear_block_radius");
        if (radius <= 0 || torn >= params().integer("grab_life_max") || block.isPassable()) {
            return null;
        }
        BlockData data = block.getBlockData();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        if (ctx().breakBlocks(owner, center, Math.min(radius, 0.5)) <= 0 || !block.isPassable()) {
            return null;
        }
        torn++;
        BlockDisplay display = keep(Displays.block(ctx(), owner, center, data, Displays.scaled(0.95f), ORBIT_LIFETIME));
        Displays.animateNextTick(ctx(), owner, display, Displays.scaled(ORBIT_SCALE), (int) RATTLE_TICKS);
        Vfx.cloud(ctx(), center, Particle.BLOCK, 10, 0.35, 0.05, data, false);
        Vfx.cloud(ctx(), center, Particle.DUST, 6, 0.4, 0, PLASTER_DUST, false);
        Fx.sound(center, breakSound(data), 1.0f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(center, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.6f, 1.3f + Fx.jitter(0.1f));
        return new Orbiter(display, data, null);
    }

    /** A ghostly item shimmers into being at {@code spot}. */
    private Orbiter ghost(Player owner, Location spot) {
        Material item = GHOST_ITEMS[ThreadLocalRandom.current().nextInt(GHOST_ITEMS.length)];
        return ghostItem(owner, spot, item, ITEM_SCALE, ORBIT_LIFETIME);
    }

    private Orbiter ghostItem(Player owner, Location spot, Material item, float scale, long lifetime) {
        ItemDisplay display = keep(Displays.item(ctx(), owner, spot, new ItemStack(item),
                Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), lifetime));
        Orbiter orbiter = new Orbiter(display, null, item);
        Displays.animateNextTick(ctx(), owner, display, pose(orbiter, scale, 0, 0), 5);
        Vfx.cloud(ctx(), spot, Particle.DUST_COLOR_TRANSITION, 10, 0.3, 0, WISP, false);
        Vfx.cloud(ctx(), spot, Particle.SOUL, 2, 0.2, 0.02, null, false);
        Fx.sound(spot, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.5f, 0.6f + Fx.jitter(0.1f));
        Fx.sound(spot, Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 0.6f, 0.7f + Fx.jitter(0.1f));
        return orbiter;
    }

    /** The nearest solid block at body height within {@code radius} that faces open air (a wall or a prop). */
    private @Nullable Block wallNear(Location feet, double radius) {
        World world = feet.getWorld();
        int floor = (int) Math.floor(feet.getY() + 1.0E-3);
        int span = (int) Math.ceil(radius);
        List<Block> found = new ArrayList<>();
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    Block block = world.getBlockAt(feet.getBlockX() + dx, floor + dy, feet.getBlockZ() + dz);
                    if (block.isPassable() || block.getState(false) instanceof TileState
                            || block.getLocation().add(0.5, 0.5, 0.5).distance(feet) > radius + 0.5 || !exposed(block)) {
                        continue;
                    }
                    found.add(block);
                }
            }
        }
        return pickNear(found, feet);
    }

    /** A floor block within {@code radius} with room above it and nobody standing over it. */
    private @Nullable Block floorNear(Player owner, Location feet, double radius) {
        World world = feet.getWorld();
        int floor = (int) Math.floor(feet.getY() + 1.0E-3) - 1;
        int span = (int) Math.ceil(radius);
        List<Block> found = new ArrayList<>();
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                if (dx * dx + dz * dz > radius * radius || Math.abs(dx) + Math.abs(dz) < 2) {
                    continue;
                }
                Block block = world.getBlockAt(feet.getBlockX() + dx, floor, feet.getBlockZ() + dz);
                if (block.isPassable() || !block.getRelative(BlockFace.UP).isPassable()
                        || block.getState(false) instanceof TileState || stoodOn(block)) {
                    continue;
                }
                found.add(block);
            }
        }
        return pickNear(found, feet);
    }

    /** True if a living body stands over (or within two blocks above) this floor block. */
    private boolean stoodOn(Block block) {
        BoundingBox above = new BoundingBox(block.getX() - 0.3, block.getY() + 1, block.getZ() - 0.3, block.getX() + 1.3,
                block.getY() + 3.5, block.getZ() + 1.3);
        for (Entity entity : block.getWorld().getNearbyEntities(above)) {
            if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                return true;
            }
        }
        return false;
    }

    private static boolean exposed(Block block) {
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
                BlockFace.UP}) {
            if (block.getRelative(face).isPassable()) {
                return true;
            }
        }
        return false;
    }

    /** One of the few nearest candidates, at random. */
    private static @Nullable Block pickNear(List<Block> found, Location from) {
        if (found.isEmpty()) {
            return null;
        }
        found.sort(Comparator.comparingDouble(block -> block.getLocation().add(0.5, 0.5, 0.5).distanceSquared(from)));
        return found.get(ThreadLocalRandom.current().nextInt(Math.min(4, found.size())));
    }

    /** A floating thing takes a ranged hit meant for the ghost and bursts. */
    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0 || orbit.isEmpty() || !isRanged(event)) {
            return;
        }
        Orbiter shield = orbit.removeFirst();
        if (!shield.display().isValid()) {
            return;
        }
        event.setCancelled(true);
        Player owner = player();
        Location body = Fx.body(owner);
        Location from = body;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            from = byEntity.getDamager().getLocation().add(0, byEntity.getDamager().getHeight() * 0.5, 0);
        }
        Vector toward = from.toVector().subtract(body.toVector());
        Location block = toward.lengthSquared() > 1.0E-4
                ? body.clone().add(toward.normalize().multiply(0.9)) : body.clone().add(0, 0.6, 0);
        Displays.moveTo(shield.display(), block);
        ctx().runLater(owner, 2, () -> shatter(shield, block));
        Fx.sound(block, Sound.ITEM_SHIELD_BLOCK, 0.8f, 1.4f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + GHOST_TAG + ">👻 덜컹! </" + GHOST_TAG + "><gray>떠다니던 물건이 공격을 "
                + "대신 맞았습니다 (남은 " + orbit.size() + "개)</gray>"));
    }

    /** Vanilla projectiles, and ability damage from an enemy {@code ranged_distance} or more away. */
    private boolean isRanged(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return false;
        }
        if (event.getCause() == DamageCause.PROJECTILE || byEntity.getDamager() instanceof Projectile) {
            return true;
        }
        Tag<DamageType> projectile = DamageTypeTags.IS_PROJECTILE;
        if (projectile != null && projectile.isTagged(event.getDamageSource().getDamageType())) {
            return true;
        }
        if (!ctx().isAbilityDamage() || event.getDamageSource().getDamageType() != DamageType.MAGIC) {
            return false;
        }
        Player attacker = ctx().resolveAttacker(byEntity.getDamager());
        double min = Math.max(0, params().num("ranged_distance"));
        return attacker != null && !attacker.equals(player()) && attacker.getWorld().equals(player().getWorld())
                && attacker.getLocation().distanceSquared(player().getLocation()) >= min * min;
    }

    // ------------------------------------------------------------------ active: hurl

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("throw_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        Location aim;
        if (target != null) {
            aim = Fx.body(target);
        } else {
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            aim = hit == null ? eye.clone().add(direction.clone().multiply(range))
                    : hit.getHitPosition().toLocation(owner.getWorld());
        }
        int count = Math.max(1, params().integer("throw_count"));
        List<Orbiter> ammo = new ArrayList<>();
        while (ammo.size() < count && !orbit.isEmpty()) {
            Orbiter next = orbit.removeFirst();
            if (next.display().isValid()) {
                ammo.add(next);
            }
        }
        if (ammo.size() < count) {
            ammo.addAll(tearAhead(owner, count - ammo.size()));
        }
        if (ammo.isEmpty()) {
            return ActivationResult.fail("던질 물건이 없습니다");
        }
        Map<UUID, Integer> hits = new HashMap<>();
        long interval = Math.max(1, params().ticks("throw_interval"));
        for (int i = 0; i < ammo.size(); i++) {
            Orbiter orbiter = ammo.get(i);
            long delay = 2 + i * interval;
            ctx().runLater(owner, delay, () -> hurl(owner, orbiter, target, aim, hits));
        }
        Fx.sound(eye, Sound.ENTITY_VEX_CHARGE, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ENTITY_ALLAY_ITEM_THROWN, 1.0f, 0.7f);
        Vfx.cloud(ctx(), Fx.body(owner), Particle.DUST_COLOR_TRANSITION, 10, 0.4, 0, WISP, false);
        return ActivationResult.success();
    }

    /**
     * Fresh things to throw torn from where the ghost looks (within {@code tear_range}): blocks around the spot the
     * view ray meets, through {@code breakBlocks}; ghostly items for the rest where nothing may break.
     */
    private List<Orbiter> tearAhead(Player owner, int needed) {
        List<Orbiter> fresh = new ArrayList<>();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        double range = Math.max(1, params().num("tear_range"));
        RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        Location spot = eye.clone().add(direction.clone().multiply(Math.min(3, range)));
        if (hit != null && hit.getHitBlock() != null) {
            Block center = hit.getHitBlock();
            spot = hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.4));
            List<Block> around = new ArrayList<>();
            around.add(center);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Block near = center.getRelative(dx, dy, dz);
                        if (!near.equals(center) && exposed(near) && !near.isPassable()) {
                            around.add(near);
                        }
                    }
                }
            }
            for (Block block : around) {
                if (fresh.size() >= needed || !reserve(1)) {
                    break;
                }
                if (stoodOn(block)) {
                    continue;
                }
                Orbiter real = tear(owner, block);
                if (real == null) {
                    break; // nothing breaks here: the rest are ghosts
                }
                fresh.add(real);
            }
        }
        while (fresh.size() < needed && reserve(1)) {
            fresh.add(ghost(owner, spot.clone().add(Fx.jitter(0.5f), Fx.jitter(0.4f), Fx.jitter(0.5f))));
        }
        return fresh;
    }

    /** One thrown thing flies at the aim (homing on an aimed enemy), bursting on walls or the first enemy. */
    private void hurl(Player owner, Orbiter orbiter, @Nullable LivingEntity target, Location aim,
                      Map<UUID, Integer> hits) {
        Display display = orbiter.display();
        if (!display.isValid()) {
            return;
        }
        display.setTeleportDuration(1);
        Location[] position = {display.getLocation()};
        Fx.sound(position[0], Sound.ENTITY_ALLAY_ITEM_THROWN, 0.8f, 1.2f + Fx.jitter(0.15f));
        Loop.repeat(ctx(), owner, 0, 1, THROW_MAX_TICKS, run -> {
            if (!display.isValid()) {
                return false;
            }
            Location goal = target != null && Combat.isTargetable(ctx(), owner, target) ? Fx.body(target) : aim;
            Location at = position[0];
            Vector to = goal.toVector().subtract(at.toVector());
            double distance = to.length();
            if (distance < 0.3) {
                impact(owner, orbiter, at, to, hits);
                return false;
            }
            Vector step = to.multiply(Math.min(THROW_SPEED, distance) / distance);
            RayTraceResult wall = at.getWorld().rayTraceBlocks(at, step, step.length(), FluidCollisionMode.NEVER, true);
            Location next = wall != null ? wall.getHitPosition().toLocation(at.getWorld()).subtract(step.clone()
                    .normalize().multiply(0.2)) : at.clone().add(step);
            position[0] = next;
            Displays.moveTo(display, next);
            Displays.animate(display, pose(orbiter, ORBIT_SCALE, run * 40f, run * 25f), 1);
            Vfx.trail(at, next, GHOST, 6, false);
            if (run % 2 == 0) {
                Vfx.dot(next, Particle.DUST_COLOR_TRANSITION, WISP, false);
            }
            if (wall != null || !ctx().enemiesNear(owner, next, 0.7).isEmpty() || distance <= THROW_SPEED) {
                impact(owner, orbiter, next, step, hits);
                return false;
            }
            return true;
        }, () -> {
            if (display.isValid()) {
                impact(owner, orbiter, position[0], new Vector(), hits);
            }
        });
    }

    private void impact(Player owner, Orbiter orbiter, Location at, Vector heading, Map<UUID, Integer> hits) {
        double damage = params().num("throw_damage");
        int max = Math.max(1, params().integer("throw_max_hits"));
        Vector push = Motion.horizontal(heading).multiply(THROW_PUSH).setY(0.15);
        for (LivingEntity enemy : ctx().enemiesNear(owner, at, Math.max(0.1, params().num("throw_radius")))) {
            int count = hits.getOrDefault(enemy.getUniqueId(), 0);
            if (count >= max) {
                continue;
            }
            hits.put(enemy.getUniqueId(), count + 1);
            Combat.hit(ctx(), owner, enemy, damage);
            if (Combat.isTargetable(ctx(), owner, enemy) && push.lengthSquared() > 0.03) {
                Motion.push(enemy, push, MAX_SPEED);
            }
        }
        shatter(orbiter, at);
    }

    /** A floating or thrown thing bursts into its own crumbs (a block's, or the ghost item's). */
    private void shatter(Orbiter orbiter, Location at) {
        Display display = orbiter.display();
        if (display.isValid()) {
            display.remove();
        }
        BlockData block = orbiter.block();
        if (block != null) {
            Vfx.cloud(ctx(), at, Particle.BLOCK, 14, 0.3, 0.1, block, false);
            Fx.sound(at, breakSound(block), 1.0f, 0.9f + Fx.jitter(0.1f));
        } else if (orbiter.item() != null) {
            Vfx.cloud(ctx(), at, Particle.ITEM, 8, 0.2, 0.08, new ItemStack(orbiter.item()), false);
            Fx.sound(at, itemSound(orbiter.item()), 1.0f, 0.9f + Fx.jitter(0.1f));
        }
        Vfx.cloud(ctx(), at, Particle.DUST, 6, 0.3, 0, PLASTER_DUST, false);
        Vfx.dot(at, Particle.FLASH, GHOST, false);
    }

    // ------------------------------------------------------------------ ultimate: uproar

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("chaos_radius"));
        if (ctx().enemiesNear(owner, owner.getLocation(), radius).isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        Uproar uproar = new Uproar(owner, radius);
        uproar.begin();
        return ActivationResult.success();
    }

    /**
     * The uproar: for {@code chaos_duration}, once a second every enemy around (seen by the ghost, at most
     * {@code chaos_targets}, nearest first) who stands within {@code cover_range} of a wall or a piece of cover gets
     * that cover thrown at it: it shakes for {@code chaos_warning} (step away!), tears out (or a ghostly piece of
     * furniture appears where nothing may break) and flies at where the enemy was.
     */
    private final class Uproar implements LongPredicate {

        private final Player owner;
        private final double radius;
        private final Location center;
        private final long duration = Math.max(20, params().ticks("chaos_duration"));
        private final Map<UUID, Integer> slams = new HashMap<>();
        private int total;

        Uproar(Player owner, double radius) {
            this.owner = owner;
            this.radius = radius;
            this.center = owner.getLocation();
        }

        void begin() {
            Location floor = center.clone().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, floor, 0.5, radius, 8, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f), true);
            Vfx.cloud(ctx(), Fx.body(owner), Particle.SOUL, 16, 0.6, 0.05, null, true);
            Fx.sound(center, Sound.ENTITY_GHAST_SCREAM, 0.6f, 0.6f);
            Fx.sound(center, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0f, 0.6f);
            Fx.sound(center, Sound.AMBIENT_CAVE, 1.0f, 0.8f);
            owner.sendActionBar(Text.mm("<" + GHOST_TAG + "><b>👻 대소동!</b></" + GHOST_TAG + "> <gray>"
                    + Fx.seconds(duration) + "초간 벽 가까이의 적에게 엄폐물이 날아듭니다</gray>"));
            Loop.repeat(ctx(), owner, 0, 1, duration, this, this::end);
        }

        @Override
        public boolean test(long tick) {
            if (tick % PULSE_PERIOD == 0) {
                pulse(tick);
            }
            ambient(tick);
            return true;
        }

        private void pulse(long tick) {
            int max = Math.max(1, params().integer("chaos_max_hits"));
            int targets = Math.max(1, params().integer("chaos_targets"));
            int chosen = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
                if (chosen >= targets) {
                    break;
                }
                if (!seen(owner, enemy) || slams.getOrDefault(enemy.getUniqueId(), 0) >= max) {
                    continue;
                }
                Block cover = coverNear(enemy);
                if (cover == null) {
                    continue; // out in the open: safe
                }
                chosen++;
                strike(enemy, cover);
            }
            if (chosen > 0) {
                Fx.sound(center, Sound.ENTITY_VEX_CHARGE, 0.8f, 0.6f + Fx.jitter(0.05f));
            }
        }

        /** The closest wall or cover block within {@code cover_range} of the enemy, at its body height, facing it. */
        private @Nullable Block coverNear(LivingEntity enemy) {
            Location feet = enemy.getLocation();
            double range = Math.max(0.5, params().num("cover_range"));
            int floor = (int) Math.floor(feet.getY() + 1.0E-3);
            int span = (int) Math.ceil(range + 0.5);
            World world = feet.getWorld();
            Block best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -span; dx <= span; dx++) {
                for (int dz = -span; dz <= span; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        Block block = world.getBlockAt(feet.getBlockX() + dx, floor + dy, feet.getBlockZ() + dz);
                        if (block.isPassable()) {
                            continue;
                        }
                        double distance = horizontalToBlock(feet, block);
                        if (distance > range || distance >= bestDistance || !facesToward(block, feet)) {
                            continue;
                        }
                        best = block;
                        bestDistance = distance;
                    }
                }
            }
            return best;
        }

        /** True if the face of the block towards {@code at} is open air (the cover faces the enemy). */
        private boolean facesToward(Block block, Location at) {
            double dx = at.getX() - (block.getX() + 0.5);
            double dz = at.getZ() - (block.getZ() + 0.5);
            BlockFace face = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? BlockFace.EAST : BlockFace.WEST)
                    : (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
            return block.getRelative(face).isPassable();
        }

        /** The cover rattles, then tears out (or a ghostly piece of furniture appears) and slams into the enemy. */
        private void strike(LivingEntity enemy, Block cover) {
            Location coverCenter = cover.getLocation().add(0.5, 0.5, 0.5);
            Vector toEnemy = Motion.horizontal(enemy.getLocation().toVector().subtract(coverCenter.toVector()));
            if (toEnemy.lengthSquared() < 1.0E-6) {
                toEnemy = new Vector(1, 0, 0);
            }
            List<Orbiter> chunk = new ArrayList<>();
            List<Location> starts = new ArrayList<>();
            double blockRadius = params().num("chaos_block_radius");
            if (blockRadius > 0) {
                List<Block> pieces = new ArrayList<>();
                List<BlockData> datas = new ArrayList<>();
                int span = (int) Math.ceil(blockRadius);
                for (int dx = -span; dx <= span; dx++) {
                    for (int dy = -span; dy <= span; dy++) {
                        for (int dz = -span; dz <= span; dz++) {
                            Block near = cover.getRelative(dx, dy, dz);
                            if (!near.isPassable() && near.getLocation().add(0.5, 0.5, 0.5).distance(coverCenter)
                                    <= blockRadius) {
                                pieces.add(near);
                                datas.add(near.getBlockData());
                            }
                        }
                    }
                }
                if (ctx().breakBlocks(owner, coverCenter, blockRadius) > 0) {
                    for (int i = 0; i < pieces.size() && chunk.size() < CHUNK_PIECES; i++) {
                        Block piece = pieces.get(i);
                        if (piece.isPassable() && reserve(1)) {
                            Location at = piece.getLocation().add(0.5, 0.5, 0.5);
                            BlockDisplay display = keep(Displays.block(ctx(), owner, at, datas.get(i),
                                    Displays.scaled(0.8f), RATTLE_TICKS + CHAOS_FLIGHT + 20));
                            chunk.add(new Orbiter(display, datas.get(i), null));
                            starts.add(at);
                        }
                    }
                }
            }
            if (chunk.isEmpty()) {
                Location spot = coverCenter.clone().add(toEnemy.clone().multiply(0.9)).add(0, 0.2, 0);
                if (!reserve(1)) {
                    return;
                }
                chunk.add(furniture(spot));
                starts.add(spot);
            }
            long warning = Math.max(1, params().ticks("chaos_warning"));
            Location aimAt = Fx.body(enemy);
            Fx.sound(coverCenter, Sound.BLOCK_CHEST_LOCKED, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(coverCenter, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.8f, 0.9f + Fx.jitter(0.1f));
            Vector away = toEnemy.clone();
            Loop.repeat(ctx(), owner, 0, 1, warning + CHAOS_FLIGHT, run -> {
                if (run < warning) {
                    for (int i = 0; i < chunk.size(); i++) {
                        Display display = chunk.get(i).display();
                        Displays.moveTo(display, starts.get(i).clone().add(Fx.jitter(0.1f), Fx.jitter(0.06f),
                                Fx.jitter(0.1f)));
                    }
                    Vfx.cloud(ctx(), coverCenter, Particle.DUST, 3, 0.4, 0, PLASTER_DUST, false);
                    if (run == 0) {
                        Vfx.trail(coverCenter, aimAt, GHOST, (int) warning + 2, false);
                    }
                    return true;
                }
                long flight = run - warning + 1;
                double t = (double) flight / CHAOS_FLIGHT;
                for (int i = 0; i < chunk.size(); i++) {
                    Orbiter piece = chunk.get(i);
                    Location from = starts.get(i);
                    Location at = from.clone().add(aimAt.clone().subtract(from).toVector().multiply(t))
                            .add(0, 0.6 * t * (1 - t), 0);
                    piece.display().setTeleportDuration(1);
                    Displays.moveTo(piece.display(), at);
                    Displays.animate(piece.display(), pose(piece, piece.block() != null ? 0.8f : 1.4f, flight * 50f,
                            flight * 30f), 1);
                    if (i == 0) {
                        Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, WISP, false);
                    }
                }
                if (flight >= CHAOS_FLIGHT) {
                    slam(enemy, aimAt, away, chunk);
                    return false;
                }
                return true;
            });
        }

        /** A ghostly piece of furniture where the wall may not break: a bookcase, a chair, a picture or a pot. */
        private Orbiter furniture(Location spot) {
            int roll = ThreadLocalRandom.current().nextInt(4);
            long lifetime = Math.max(1, params().ticks("chaos_warning")) + CHAOS_FLIGHT + 20;
            if (roll == 0) {
                BlockData shelf = WARDROBE.createBlockData();
                BlockDisplay display = keep(Displays.block(ctx(), owner, spot, shelf,
                        Displays.transform(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), lifetime));
                Orbiter orbiter = new Orbiter(display, shelf, null);
                Displays.animateNextTick(ctx(), owner, display, pose(orbiter, 1.4f, 0, 0), 3);
                Vfx.cloud(ctx(), spot, Particle.DUST_COLOR_TRANSITION, 10, 0.4, 0, WISP, false);
                return orbiter;
            }
            Material item = roll == 1 ? CHAIR : roll == 2 ? FRAME : POT;
            return ghostItem(owner, spot, item, 1.4f, lifetime);
        }

        /** The piece arrives: an enemy still there is struck, pushed away from the wall and staggered. */
        private void slam(LivingEntity enemy, Location aimAt, Vector away, List<Orbiter> chunk) {
            Location at = aimAt;
            boolean struck = Combat.isTargetable(ctx(), owner, enemy)
                    && Fx.body(enemy).distance(aimAt) <= SLAM_REACH + enemy.getWidth() / 2;
            if (struck) {
                int count = slams.getOrDefault(enemy.getUniqueId(), 0);
                slams.put(enemy.getUniqueId(), count + 1);
                total++;
                Combat.hit(ctx(), owner, enemy, params().num("chaos_damage"));
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Motion.push(enemy, away.clone().multiply(SLAM_PUSH).setY(0.3), MAX_SPEED);
                    long stagger = params().ticks("stagger_duration");
                    double slow = Math.clamp(params().num("stagger_slow") / 100.0, 0.0, 1.0);
                    if (stagger > 0 && slow > 0) {
                        ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                                stagger);
                        ctx().addModifier(owner, enemy, Attribute.ATTACK_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                                stagger);
                    }
                }
                at = Fx.body(enemy);
                popText(at.clone().add(0, 1.0, 0), "<gradient:#E6FFFE:#7C6A92><b>쾅!</b></gradient>");
                Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f + Fx.jitter(0.05f));
                Fx.sound(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.7f, 1.2f + Fx.jitter(0.1f));
            }
            for (Orbiter piece : chunk) {
                shatter(piece, at.clone().add(Fx.jitter(0.3f), Fx.jitter(0.3f), Fx.jitter(0.3f)));
            }
            Vfx.burst(ctx(), at, PALETTE, 0.7, Particle.CLOUD, false);
        }

        /** The room hums: ghost motes drift around, and now and then something somewhere rattles. */
        private void ambient(long tick) {
            if (tick % 3 == 0) {
                Location mote = Vfx.scatter(center, radius).add(0, 0.5 + ThreadLocalRandom.current().nextDouble(2), 0);
                Vfx.trail(mote, mote.clone().add(Fx.jitter(1.2f), 0.6, Fx.jitter(1.2f)), GHOST, 14, false);
            }
            if (tick % 2 == 0) {
                Vfx.ring(ctx(), center.clone().add(0, 0.12, 0), radius, (int) Math.max(18, radius * 2.5), tick * 0.02,
                        Particle.DUST, GHOST_DUST, true);
            }
            if (tick % 25 == 12) {
                Fx.sound(Vfx.scatter(center, radius), Sound.BLOCK_WOODEN_DOOR_OPEN, 0.6f, 0.6f + Fx.jitter(0.2f));
            }
        }

        private void end() {
            Vfx.cloud(ctx(), Fx.body(owner), Particle.SOUL, 10, 0.5, 0.03, null, false);
            Fx.sound(center, Sound.ENTITY_VEX_AMBIENT, 0.8f, 0.5f);
            owner.sendActionBar(Text.mm("<" + DUST_TAG + ">👻 대소동이 잦아듭니다</" + DUST_TAG + ">"
                    + (total > 0 ? " <white>" + total + "번 부딪힘</white>" : "")));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A floating thing's pose: a torn block centered and tumbling, or an item standing upright and turning. */
    private static Transformation pose(Orbiter orbiter, float scale, float yaw, float roll) {
        if (orbiter.display() instanceof BlockDisplay) {
            boolean shelf = orbiter.block() != null && orbiter.block().getMaterial() == WARDROBE && scale > 1f;
            if (shelf) {
                return Displays.transform(0, 0, 0, scale * 0.65f, scale * 1.25f, scale * 0.4f, yaw * 0.2f, 0, roll * 0.2f);
            }
            return Displays.transform(0, 0, 0, scale, scale, scale, yaw, roll * 0.5f, roll);
        }
        return Displays.centered(0, 0, 0, scale, scale, scale, yaw, 0, roll * 0.3f);
    }

    private static double horizontalToBlock(Location at, Block block) {
        double dx = Math.max(Math.max(block.getX() - at.getX(), 0), at.getX() - (block.getX() + 1));
        double dz = Math.max(Math.max(block.getZ() - at.getZ(), 0), at.getZ() - (block.getZ() + 1));
        return Math.hypot(dx, dz);
    }

    private static Sound breakSound(BlockData data) {
        Material material = data.getMaterial();
        if (Tag.PLANKS.isTagged(material) || Tag.LOGS.isTagged(material) || material.name().contains("WOOD")) {
            return Sound.BLOCK_WOOD_BREAK;
        }
        if (material.name().contains("GLASS")) {
            return Sound.BLOCK_GLASS_BREAK;
        }
        if (material.name().contains("WOOL")) {
            return Sound.BLOCK_WOOL_BREAK;
        }
        return Sound.BLOCK_STONE_BREAK;
    }

    private static Sound itemSound(Material item) {
        return switch (item) {
            case DECORATED_POT, FLOWER_POT -> Sound.BLOCK_DECORATED_POT_SHATTER;
            case LANTERN -> Sound.BLOCK_GLASS_BREAK;
            case BOOK -> Sound.BLOCK_CHISELED_BOOKSHELF_BREAK;
            case PAINTING -> Sound.ENTITY_PAINTING_BREAK;
            case OAK_STAIRS, BOWL -> Sound.BLOCK_WOOD_BREAK;
            default -> Sound.ENTITY_ITEM_BREAK;
        };
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
