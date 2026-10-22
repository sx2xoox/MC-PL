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
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 해적 — a buccaneer. Kills (and kills it helped with) are plundered for ultimate gauge, and a kill reloads the cannon
 * at once. The active wheels out a deck cannon and fires a heavy iron ball that rolls along the ground, follows the
 * terrain, drops off ledges and bowls through every enemy in its way. The ultimate raises a ghost ship behind the
 * pirate that follows it around and shells wherever the pirate aims.
 *
 * <p>Unlike the 폭탄마's lobbed grenades and fixed bombing strip, the cannonball is a solid shot that stays on the
 * ground, and the ghost ship's barrage is steered live by the pirate's aim.
 *
 * <p>Look: navy and gold for the pirate, spectral teal and soul fire for the ghost ship. A plunder bursts a fountain of
 * gold coins out of the fallen enemy that then streams into the pirate, with a "약탈!" sign. The cannon (oak carriage,
 * blackened barrel, gold muzzle band) materializes with a lit fuse, fires with a flash, a smoke cloud and a recoil;
 * the ball is three dark cubes turned against each other that tumble as it rolls, kicking up the floor, and bursts
 * through enemies with sparks and a gold flash. The ghost ship is translucent cyan glass and warped wood with two pale
 * sails, three cannons and soul lanterns; it rises out of sea mist with a ship's bell, rocks as it follows, fires with
 * ghast howls and soul fire, and every shell is announced by a closing teal ring before it lands in a burst of
 * spectral water and soul flame.
 */
public final class PirateAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("pirate", "해적", Material.NAUTILUS_SHELL)
            .color(TextColor.color(0xE0B040))
            .difficulty(2)
            .passive("약탈",
                    "적을 처치하면 금화를 약탈해 궁극기 게이지가 {plunder_gauge}% 차고 대포알이 즉시 재장전됩니다. "
                            + "내가 {assist_window}초 안에 공격한 적이 다른 이유로 쓰러져도 게이지가 {assist_gauge}% 찹니다.")
            .active("대포알",
                    "갑판 대포를 꺼내 쇠 대포알을 쏩니다. 대포알은 땅을 따라 최대 {ball_range}블록 굴러가며 지나가는 "
                            + "적마다 {ball_damage} 피해를 주고 강하게 밀쳐냅니다(적을 뚫고 지나갈수록 느려집니다).",
                    8)
            .ultimate("유령선 포격",
                    "{ult_duration}초간 등 뒤에 유령선을 불러냅니다. 유령선은 나를 따라다니며 {ult_interval}초마다 내가 "
                            + "조준한 곳({ult_range}블록 이내)에 포탄을 쏘아, 떨어진 자리 반경 {ult_radius}블록의 적에게 "
                            + "{ult_damage} 피해를 줍니다(적마다 최대 {ult_max_hits}회).")
            .param("plunder_gauge", 30)
            .param("assist_gauge", 12)
            .param("assist_window", 6)
            .param("ball_range", 18)
            .param("ball_damage", 5)
            .param("ball_knockback", 1.2)
            .param("ult_duration", 5)
            .param("ult_interval", 0.4)
            .param("ult_range", 32)
            .param("ult_radius", 2.5)
            .param("ult_damage", 3)
            .param("ult_max_hits", 4)
            .build();

    private static final String COLOR_TAG = "#E0B040";
    private static final String GHOST_TAG = "#5FE3C0";
    private static final double TAU = Math.PI * 2;
    /** Pale gold core, doubloon gold body, deep navy edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE9A8, 0xE0B040, 0x1B2A4A);
    /** The ghost ship: pale foam core, spectral teal body, deep sea edge. */
    private static final Vfx.Palette GHOST = Vfx.Palette.of(0xE0FFF8, 0x5FE3C0, 0x0E2A3A);
    private static final Particle.DustOptions GOLD_DUST = PALETTE.dust(1.0f);
    private static final Particle.DustTransition GOLD_GLOW = PALETTE.glow(1.1f);
    private static final Particle.DustOptions NAVY_DUST = new Particle.DustOptions(PALETTE.edge(), 1.0f);
    private static final Particle.DustOptions IRON_DUST = new Particle.DustOptions(Color.fromRGB(0x3A3A40), 0.9f);
    private static final Particle.DustTransition GHOST_GLOW = GHOST.glow(1.2f);
    private static final Particle.DustTransition GHOST_FADE = GHOST.fade(1.1f);
    private static final Particle.DustOptions GHOST_DUST = GHOST.dust(0.9f);
    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;
    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;

    // passive: plunder
    private static final long ASSIST_PERIOD = 5;
    private static final int COINS = 6;
    private static final long COIN_RISE_TICKS = 9;
    private static final long COIN_FLY_TICKS = 8;
    private static final long SIGN_TICKS = 24;

    // active: cannonball
    private static final Material CARRIAGE = Material.DARK_OAK_PLANKS;
    private static final Material BARREL = Material.POLISHED_BLACKSTONE;
    private static final Material BAND = Material.GOLD_BLOCK;
    private static final Material[] BALL_CUBES = {Material.BLACK_CONCRETE, Material.COAL_BLOCK,
            Material.POLISHED_BLACKSTONE};
    /** The ball is three cubes in different orientations, which together read as a rough sphere. */
    private static final Quaternionf[] BALL_ORIENTATIONS = {
            new Quaternionf(),
            new Quaternionf().rotateY((float) Math.toRadians(45)).rotateX((float) Math.toRadians(35.26)),
            new Quaternionf().rotateZ((float) Math.toRadians(45)).rotateY((float) Math.toRadians(30)),
    };
    private static final double BALL_RADIUS = 0.3;
    private static final float BALL_CUBE = 0.46f;
    /** How far beyond the ball an enemy's hitbox may be and still be struck. */
    private static final double BALL_REACH = 0.25;
    private static final double BALL_SPEED = 0.85;
    private static final double BALL_MIN_SPEED = 0.22;
    /** Speed kept after bowling through an enemy, and per tick of rolling. */
    private static final double HIT_SLOWDOWN = 0.75;
    private static final double ROLL_FRICTION = 0.992;
    private static final double BALL_GRAVITY = 0.08;
    private static final double BALL_DRAG = 0.98;
    /** Steps (slabs, stairs) at most this high are rolled over; anything higher stops the ball. */
    private static final double STEP_UP = 0.55;
    private static final double BOUNCE_MIN = 0.18;
    private static final double BOUNCE = 0.35;
    private static final double BALL_LIFT = 0.38;
    /** Visual spin per tick at most, so the client's interpolation never takes the short way round. */
    private static final double MAX_SPIN = 0.7;
    private static final long FUSE_TICKS = 3;
    private static final long CANNON_TICKS = 16;
    private static final long MAX_ROLL_TICKS = 80;
    private static final double CANNON_FORWARD = 1.0;
    /** How far in front of the cannon's base the ball leaves the barrel. */
    private static final double MUZZLE_REACH = 0.75;
    private static final double MUZZLE_HEIGHT = 0.45;
    /** Displays one shot needs (the cannon's three parts and the ball's cubes). */
    private static final int SHOT_DISPLAYS = 3 + BALL_CUBES.length;

    // ultimate: ghost ship
    private static final double SHIP_BEHIND = 4.5;
    private static final double SHIP_HEIGHT = 3.6;
    private static final double SHIP_FOLLOW = 0.35;
    private static final long SHIP_STEP = 2;
    private static final int SHIP_RISE_TICKS = 10;
    private static final int SHIP_SINK_TICKS = 10;
    private static final float SHIP_SPAWN_SCALE = 0.15f;
    /** The ship turns at most this many degrees per step, so its parts never cut corners while interpolating. */
    private static final float MAX_SHIP_TURN = 15f;
    private static final double SHELL_APEX = 2.5;
    private static final long MIN_SHELL_TICKS = 10;
    private static final long MAX_SHELL_TICKS = 20;
    private static final double SHELL_PUSH = 0.55;
    private static final double SHELL_LIFT = 0.45;
    private static final double AIR_BURST_SEARCH = 40;
    /** Where the shells of a salvo land around the aimed point, as fractions of the spread (cycled). */
    private static final double[][] SPREAD_PATTERN = {{0, 0}, {0.8, 0.5}, {-0.7, 0.6}, {0.3, -0.9}, {-0.6, -0.5},
            {0.9, -0.2}, {-0.2, 0.95}, {-0.95, 0.05}};
    /** Local z of the three cannons along the ship's side. */
    private static final float[] CANNON_Z = {-1.1f, 0.15f, 1.4f};

    /** Ghost ship parts in the ship's frame (+z the bow, +x the firing side): material, offset, size, pitch. */
    private record Part(Material material, float x, float y, float z, float sx, float sy, float sz, float pitch) {
    }

    private static final Part[] SHIP = {
            new Part(Material.CYAN_STAINED_GLASS, 0, 0, 0, 1.3f, 0.8f, 4.2f, 0),
            new Part(Material.WARPED_PLANKS, 0, 0.42f, 0, 1.18f, 0.06f, 3.9f, 0),
            new Part(Material.CYAN_STAINED_GLASS, 0, 0.45f, 2.25f, 0.85f, 0.5f, 1.0f, -18),
            new Part(Material.LIGHT_BLUE_STAINED_GLASS, 0, 0.8f, -1.75f, 1.3f, 0.75f, 1.0f, 0),
            new Part(Material.WARPED_HYPHAE, 0, 2.05f, 0.3f, 0.12f, 3.2f, 0.12f, 0),
            new Part(Material.WHITE_STAINED_GLASS, 0, 2.25f, 0.3f, 2.3f, 1.8f, 0.06f, 0),
            new Part(Material.WHITE_STAINED_GLASS, 0, 1.7f, 1.55f, 1.6f, 1.2f, 0.05f, 0),
            new Part(Material.POLISHED_BLACKSTONE, 0.75f, 0.12f, CANNON_Z[0], 0.7f, 0.24f, 0.24f, 0),
            new Part(Material.POLISHED_BLACKSTONE, 0.75f, 0.12f, CANNON_Z[1], 0.7f, 0.24f, 0.24f, 0),
            new Part(Material.POLISHED_BLACKSTONE, 0.75f, 0.12f, CANNON_Z[2], 0.7f, 0.24f, 0.24f, 0),
    };
    /** Index of the first cannon in {@link #SHIP}. */
    private static final int FIRST_CANNON = 7;
    private static final int SAIL = 5;
    private static final int FORE_SAIL = 6;
    /** Soul lanterns hanging at the stern and the bow. */
    private static final float[][] LANTERNS = {{0, 1.45f, -2.2f}, {0, 0.95f, 2.75f}};

    /** Enemy players the pirate hit, with the tick of the last hit (for assists). */
    private final Map<UUID, Long> lastHits = new HashMap<>();
    /** Every display this ability currently has alive, for the budget. */
    private final List<Display> displays = new ArrayList<>();
    private int shipToken;
    /** The ghost ship sailing now, if any. */
    private @Nullable GhostShip ship;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        lastHits.clear();
        displays.clear();
        shipToken++;
        ship = null;
    }

    // ------------------------------------------------------------------ passive: plunder

    @Override
    public void onTick(long tick) {
        if (tick % ASSIST_PERIOD != 0 || lastHits.isEmpty()) {
            return;
        }
        long now = ctx().currentTick();
        long window = params().ticks("assist_window");
        for (Iterator<Map.Entry<UUID, Long>> it = lastHits.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> entry = it.next();
            Player victim = player().getServer().getPlayer(entry.getKey());
            if (victim == null || !victim.isOnline()) {
                it.remove();
                continue;
            }
            if (!ctx().isAlive(victim)) {
                // Fell to someone (or something) else while still marked by the pirate.
                it.remove();
                plunder(victim, false);
            } else if (now - entry.getValue() > window) {
                it.remove();
            }
        }
    }

    /** A hit on an enemy player marks it for a share of the plunder; the first mark glints gold. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!(victim instanceof Player target) || !ctx().isEnemy(player(), target)) {
            return;
        }
        Long previous = lastHits.put(target.getUniqueId(), ctx().currentTick());
        // A stealthed enemy caught by the ball or a shell is marked all the same, but nothing glints over it.
        if (previous == null && seen(player(), target)) {
            Location head = target.getLocation().add(0, target.getHeight() + 0.2, 0);
            Vfx.dot(head, Particle.WAX_ON, null, false);
            Vfx.cloud(ctx(), head, Particle.DUST, 3, 0.15, 0, GOLD_DUST, false);
        }
    }

    @Override
    public void onKill(Player victim) {
        lastHits.remove(victim.getUniqueId());
        plunder(victim, true);
    }

    /** Gauge for the plunder; a kill of its own also reloads the cannon. */
    private void plunder(Player victim, boolean kill) {
        Player owner = player();
        double gauge = Math.max(0, params().num(kill ? "plunder_gauge" : "assist_gauge"));
        if (gauge > 0) {
            ctx().addGauge(owner, gauge);
        }
        if (kill) {
            ctx().reduceCooldown(owner, Math.max(0, ctx().cooldownRemaining(owner)) + 1);
        }
        String sign = kill ? "<b><gradient:#FFE9A8:#E0B040>☠ 약탈! +" + AbilityInfo.formatNumber(gauge)
                + "%</gradient></b>" : "<gradient:#FFE9A8:#E0B040>몫 +" + AbilityInfo.formatNumber(gauge) + "%</gradient>";
        // The sign first: when displays are short, a few coins less is the smaller loss.
        sign(owner, sign, kill);
        coinFountain(owner, victim.getLocation(), kill);
        if (owner.isOnline()) {
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">" + (kill ? "☠ 약탈! 대포 재장전" : "⚓ 약탈 몫") + " <white>+"
                    + AbilityInfo.formatNumber(gauge) + "%</white></" + COLOR_TAG + ">"));
        }
    }

    /**
     * The fallen enemy's purse bursts: gold coins spring up in a fountain, hang a moment and then stream into the
     * pirate with a jingle.
     */
    private void coinFountain(Player owner, Location from, boolean kill) {
        Location origin = Displays.unrotated(from.clone().add(0, 0.9, 0));
        World world = origin.getWorld();
        Vfx.dot(origin, Particle.FLASH, PALETTE.core(), false);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, origin, Vfx.n(ctx(), kill ? 22 : 10), 0.35, 0.4, 0.35,
                0, GOLD_GLOW);
        world.spawnParticle(Particle.WAX_ON, origin, Vfx.n(ctx(), kill ? 14 : 6), 0.4, 0.4, 0.4, 0.3);
        Fx.sound(origin, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(origin, Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.6f + Fx.jitter(0.1f));
        if (!owner.getWorld().equals(world)) {
            return;
        }
        int coins = kill ? COINS : COINS / 2;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // The coins are an extra: they always leave room for the shot the kill has just reloaded.
        for (int i = 0; i < coins && roomFor(1 + SHOT_DISPLAYS); i++) {
            double angle = i * TAU / coins + random.nextDouble(0.4);
            double reach = 0.6 + random.nextDouble(0.5);
            double height = 1.2 + random.nextDouble(0.8);
            Location peak = origin.clone().add(Math.cos(angle) * reach, height, Math.sin(angle) * reach);
            ItemStack item = ItemStack.of(i % 3 == 0 ? Material.GOLD_INGOT : Material.GOLD_NUGGET);
            ItemDisplay coin = Displays.item(ctx(), owner, origin, item, Displays.centered(0, 0, 0, 0.4f, 0.4f,
                    0.4f, 0, 0, 0), COIN_RISE_TICKS + COIN_FLY_TICKS + 6);
            coin.setTeleportDuration(1);
            displays.add(coin);
            long delay = i;
            Loop.repeat(ctx(), owner, delay, 1, COIN_RISE_TICKS + COIN_FLY_TICKS, run -> {
                if (!coin.isValid()) {
                    return false;
                }
                Location at;
                if (run < COIN_RISE_TICKS) {
                    double t = (run + 1.0) / COIN_RISE_TICKS;
                    double ease = 1 - (1 - t) * (1 - t);
                    at = origin.clone().add(peak.toVector().subtract(origin.toVector()).multiply(ease));
                } else {
                    double t = (run - COIN_RISE_TICKS + 1.0) / COIN_FLY_TICKS;
                    Location chest = Fx.body(owner);
                    at = peak.clone().add(chest.toVector().subtract(peak.toVector()).multiply(t * t));
                    if (run % 2 == 0) {
                        Vfx.dot(at, Particle.WAX_ON, null, false);
                    }
                }
                Displays.moveTo(coin, at);
                Displays.animate(coin, Displays.centered(0, 0, 0, 0.4f, 0.4f, 0.4f, run * 37f, 0, run * 11f), 1);
                if (run == COIN_RISE_TICKS + COIN_FLY_TICKS - 1) {
                    Fx.sound(owner.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f,
                            1.2f + 0.1f * (float) delay + Fx.jitter(0.05f));
                }
                return true;
            }, coin::remove);
        }
    }

    /** A gold sign over the pirate's head. */
    private void sign(Player owner, String miniMessage, boolean big) {
        if (!roomFor(1 + SHOT_DISPLAYS)) {
            return;
        }
        Location above = owner.getLocation().add(0, owner.getHeight() + 0.7, 0);
        TextDisplay label = Displays.text(ctx(), owner, above, Text.mm(miniMessage), 0.2f, SIGN_TICKS + 2);
        displays.add(label);
        float scale = big ? 1.4f : 1.0f;
        Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.35f, 0, scale, scale, scale, 0, 0, 0), 5);
        ctx().runLater(owner, SIGN_TICKS - 6, () -> {
            if (label.isValid()) {
                Displays.shrinkAway(ctx(), owner, label, 5);
            }
        });
    }

    // ------------------------------------------------------------------ active: cannonball

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location feet = owner.getLocation();
        Vector forward = Motion.horizontalFacing(feet);
        Location base = Displays.unrotated(feet.clone().add(forward.clone().multiply(CANNON_FORWARD - 0.25)));
        // The ball leaves the barrel in front of the cannon: the whole way there (and the ball's own front) must be
        // open, or the ball would start inside a wall, or behind a door or a trapdoor the pirate stands against.
        Location ballStart = muzzle(base, forward);
        if (!ballStart.getBlock().isPassable()
                || !Spots.clearPath(Fx.body(owner), ballStart.clone().add(forward.clone().multiply(BALL_RADIUS)))) {
            return ActivationResult.fail("앞이 막혀 대포를 놓을 수 없습니다");
        }
        if (!roomFor(SHOT_DISPLAYS)) {
            return ActivationResult.fail("대포를 준비하는 중입니다");
        }
        // The cannon is aimed through its transformations; the display entities themselves never rotate.
        Quaternionf heading = headingFrame(feet.getYaw(), 0);
        List<BlockDisplay> cannon = cannon(owner, base, heading);
        // The fuse burns down, then the cannon fires.
        Loop.repeat(ctx(), owner, 0, 1, FUSE_TICKS, run -> {
            Location fuse = base.clone().add(forward.clone().multiply(-0.45)).add(0, 0.62, 0);
            Vfx.dot(fuse, Particle.SMALL_FLAME, null, false);
            Vfx.dot(fuse, Particle.SMOKE, null, false);
            if (run == 0) {
                Fx.sound(fuse, Sound.ENTITY_TNT_PRIMED, 0.7f, 1.6f + Fx.jitter(0.05f));
            }
            return true;
        }, () -> fire(owner, cannon, base, forward, heading));
        return ActivationResult.success();
    }

    /** The deck cannon: an oak carriage, a blackened barrel with a gold muzzle band, popping in at the pirate's feet. */
    private List<BlockDisplay> cannon(Player owner, Location base, Quaternionf heading) {
        List<BlockDisplay> parts = new ArrayList<>(3);
        long life = FUSE_TICKS + CANNON_TICKS;
        parts.add(display(base, CARRIAGE, framed(heading, Displays.transform(0, 0.13f, 0, 0.5f, 0.26f, 0.7f, 0, 0, 0)),
                life, false));
        parts.add(display(base, BARREL, framed(heading, Displays.transform(0, 0.45f, 0.05f, 0.34f, 0.34f, 1.0f, 0, -6,
                0)), life, false));
        parts.add(display(base, BAND, framed(heading, Displays.transform(0, 0.5f, 0.52f, 0.4f, 0.4f, 0.09f, 0, -6, 0)),
                life, false));
        Vfx.cloud(ctx(), base.clone().add(0, 0.4, 0), Particle.POOF, 4, 0.2, 0.02, null, false);
        Vfx.ring(ctx(), base.clone().add(0, 0.08, 0), 0.6, 12, 0, Particle.DUST, NAVY_DUST, false);
        Fx.sound(base, Sound.BLOCK_WOOD_PLACE, 0.8f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(base, Sound.BLOCK_CHAIN_PLACE, 0.7f, 0.8f + Fx.jitter(0.05f));
        return parts;
    }

    /** Where the ball leaves the cannon standing at {@code base}: in front of the barrel's mouth. */
    private static Location muzzle(Location base, Vector forward) {
        return base.clone().add(forward.clone().multiply(MUZZLE_REACH)).add(0, MUZZLE_HEIGHT + 0.05, 0);
    }

    /** Fire: a flash, smoke and a boom at the muzzle, the barrel kicks back and the ball rolls out. */
    private void fire(Player owner, List<BlockDisplay> cannon, Location base, Vector forward, Quaternionf heading) {
        Location muzzle = muzzle(base, forward);
        World world = muzzle.getWorld();
        Vfx.dot(muzzle, Particle.FLASH, PALETTE.core(), false);
        world.spawnParticle(Particle.EXPLOSION, muzzle, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.LARGE_SMOKE, muzzle, Vfx.n(ctx(), 10), 0.2, 0.2, 0.2, 0.04);
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, muzzle, Vfx.n(ctx(), 3), 0.15, 0.1, 0.15, 0.02);
        world.spawnParticle(Particle.FLAME, muzzle, Vfx.n(ctx(), 8), 0.1, 0.1, 0.1, 0.08);
        Vfx.arc(ctx(), muzzle, 0.6, yawOf(forward), 160, 12, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
        Fx.sound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 1.5f + Fx.jitter(0.05f));
        Fx.sound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 0.5f + Fx.jitter(0.05f));
        Fx.sound(muzzle, Sound.ITEM_MACE_SMASH_GROUND, 0.7f, 0.6f + Fx.jitter(0.05f));
        // Recoil: the barrel slides back on its carriage, then the cannon dissolves.
        if (cannon.size() >= 3) {
            Displays.animate(cannon.get(1), framed(heading, Displays.transform(0, 0.45f, -0.18f, 0.34f, 0.34f, 1.0f, 0,
                    -10, 0)), 2);
            Displays.animate(cannon.get(2), framed(heading, Displays.transform(0, 0.5f, 0.29f, 0.4f, 0.4f, 0.09f, 0,
                    -10, 0)), 2);
        }
        ctx().runLater(owner, CANNON_TICKS - 7, () -> {
            for (BlockDisplay part : cannon) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 5);
                }
            }
            Vfx.cloud(ctx(), base.clone().add(0, 0.4, 0), Particle.SMOKE, 5, 0.2, 0.02, null, false);
        });
        Cannonball ball = new Cannonball(muzzle, forward);
        Loop.repeat(ctx(), owner, 0, 1, MAX_ROLL_TICKS, ball, ball::rest);
    }

    /**
     * A rolling iron ball: on the ground it follows the floor (rolling over low steps, down slopes and off ledges, with a
     * bounce when it lands), a wall stops it dead, water swallows it. Every enemy in its way is struck once, hard, and
     * slows it down.
     */
    private final class Cannonball implements LongPredicate {

        private final Location center;
        private final World world;
        private final Vector direction;
        private final Vector3f axis;
        private final BlockDisplay[] cubes = new BlockDisplay[BALL_CUBES.length];
        private final Set<UUID> struck = new HashSet<>();
        private final double range;
        private Quaternionf spin = new Quaternionf();
        private double speed = BALL_SPEED;
        private double vy = 0.1;
        private double travelled;
        private boolean grounded;
        private boolean finished;
        private int hits;

        Cannonball(Location muzzle, Vector direction) {
            this.center = Displays.unrotated(muzzle);
            this.world = muzzle.getWorld();
            this.direction = direction.clone();
            // Rolling forward turns the ball about the horizontal axis across its path.
            Vector across = new Vector(0, 1, 0).crossProduct(direction);
            this.axis = new Vector3f((float) across.getX(), (float) across.getY(), (float) across.getZ()).normalize();
            this.range = Math.max(1, params().num("ball_range"));
            for (int i = 0; i < cubes.length; i++) {
                cubes[i] = display(center, BALL_CUBES[i], cubePose(BALL_CUBE * 0.6f, BALL_ORIENTATIONS[i]),
                        MAX_ROLL_TICKS + 12, false);
                cubes[i].setTeleportDuration(1);
                Displays.animateNextTick(ctx(), player(), cubes[i], cubePose(BALL_CUBE, BALL_ORIENTATIONS[i]), 2);
            }
        }

        @Override
        public boolean test(long tick) {
            if (finished) {
                return false;
            }
            Player owner = player();
            if (!ctx().isInsideArena(center) || center.getY() < world.getMinHeight()) {
                finish(End.FADE);
                return false;
            }
            // Horizontal: a wall stops the ball, a low step is rolled over.
            double step = speed;
            RayTraceResult wall = world.rayTraceBlocks(center, direction, step + BALL_RADIUS, FluidCollisionMode.NEVER,
                    true);
            if (wall != null) {
                Block block = wall.getHitBlock();
                double top = block == null ? Double.MAX_VALUE : block.getBoundingBox().getMaxY();
                double bottom = center.getY() - BALL_RADIUS;
                Location onTop = center.clone();
                onTop.setY(top + BALL_RADIUS + 0.01);
                if (block != null && top - bottom <= STEP_UP && world.rayTraceBlocks(onTop, direction,
                        step + BALL_RADIUS, FluidCollisionMode.NEVER, true) == null) {
                    center.setY(onTop.getY());
                } else {
                    center.add(direction.clone().multiply(Math.max(0, wall.getHitPosition().distance(center.toVector())
                            - BALL_RADIUS)));
                    crash(wall);
                    return false;
                }
            }
            center.add(direction.clone().multiply(step));
            travelled += step;

            // Vertical: follow the floor, fall off ledges, bounce on a hard landing.
            double drop = BALL_RADIUS + Math.max(0, -vy) + (grounded ? STEP_UP : 0.05);
            RayTraceResult floor = world.rayTraceBlocks(center, new Vector(0, -1, 0), drop, FluidCollisionMode.NEVER,
                    true);
            boolean wasGrounded = grounded;
            if (floor != null && vy <= 0) {
                double ground = floor.getHitPosition().getY();
                if (vy < -BOUNCE_MIN) {
                    vy = -vy * BOUNCE;
                    landFx(ground);
                    grounded = false;
                } else {
                    vy = 0;
                    grounded = true;
                }
                center.setY(ground + BALL_RADIUS + Math.max(0, vy));
            } else {
                center.setY(center.getY() + vy);
                vy = (vy - BALL_GRAVITY) * BALL_DRAG;
                grounded = false;
            }
            if (center.getBlock().isLiquid()) {
                finish(End.SINK);
                return false;
            }

            pose(step);
            strike(owner);
            trail(tick, wasGrounded);
            speed *= ROLL_FRICTION;
            if (travelled >= range || speed < BALL_MIN_SPEED) {
                return false;
            }
            return true;
        }

        private void pose(double step) {
            double angle = Math.min(MAX_SPIN, step / BALL_RADIUS);
            spin = new Quaternionf().rotateAxis((float) angle, axis).mul(spin).normalize();
            for (int i = 0; i < cubes.length; i++) {
                BlockDisplay cube = cubes[i];
                if (cube != null && cube.isValid()) {
                    Displays.moveTo(cube, center);
                    Displays.animate(cube, cubePose(BALL_CUBE, new Quaternionf(spin).mul(BALL_ORIENTATIONS[i])), 1);
                }
            }
        }

        /** Bowls through every enemy it touches: damage, a hard shove along its path and a slowdown. */
        private void strike(Player owner) {
            double damage = params().num("ball_damage");
            double knockback = Math.max(0, params().num("ball_knockback"));
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, BALL_RADIUS + BALL_REACH)) {
                if (!struck.add(enemy.getUniqueId())) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                speed *= HIT_SLOWDOWN;
                if (Combat.isTargetable(ctx(), owner, enemy) && knockback > 0) {
                    Motion.push(enemy, direction.clone().multiply(knockback).setY(BALL_LIFT), MAX_SPEED);
                }
                if (!seen(owner, enemy)) {
                    // Bowled over in stealth: hurt and shoved, but no flash or clang gives its spot away.
                    continue;
                }
                Location body = Fx.body(enemy);
                World w = body.getWorld();
                Vfx.dot(body, Particle.FLASH, PALETTE.core(), false);
                w.spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 14), 0.25, 0.3, 0.25, 0.4);
                w.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 10), 0.3, 0.35, 0.3, 0, IRON_DUST);
                Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, GOLD_GLOW, false);
                Fx.sound(body, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.9f, 0.7f + Fx.jitter(0.05f));
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.35f, 1.4f + Fx.jitter(0.1f));
            }
        }

        /** Floor dust kicked up while rolling, iron grit, a smoke wisp and a rumble. */
        private void trail(long tick, boolean wasGrounded) {
            Location low = center.clone().subtract(0, BALL_RADIUS - 0.05, 0);
            if (grounded && wasGrounded) {
                Block floor = low.clone().subtract(0, 0.15, 0).getBlock();
                if (!floor.getType().isAir() && tick % 2 == 0) {
                    world.spawnParticle(Particle.BLOCK_CRUMBLE, low, Vfx.n(ctx(), 3), 0.15, 0.02, 0.15, 0,
                            floor.getBlockData());
                }
                if (tick % 4 == 0) {
                    Fx.sound(low, Sound.BLOCK_HEAVY_CORE_STEP, 0.8f, 0.6f + Fx.jitter(0.08f));
                    Fx.sound(low, Sound.BLOCK_GRAVEL_STEP, 0.6f, 0.5f + Fx.jitter(0.08f));
                }
            }
            Location behind = center.clone().subtract(direction.clone().multiply(0.35));
            Vfx.dot(behind, Particle.DUST, IRON_DUST, false);
            if (tick % 3 == 0) {
                Vfx.dot(behind.clone().add(0, 0.15, 0), Particle.SMOKE, null, false);
            }
        }

        private void landFx(double ground) {
            Location at = center.clone();
            at.setY(ground + 0.05);
            Block floor = at.clone().subtract(0, 0.2, 0).getBlock();
            if (!floor.getType().isAir()) {
                world.spawnParticle(Particle.DUST_PILLAR, at, Vfx.n(ctx(), 8), 0.25, 0.05, 0.25, 0.1,
                        floor.getBlockData());
            }
            Fx.sound(at, Sound.BLOCK_HEAVY_CORE_PLACE, 1.0f, 0.7f + Fx.jitter(0.05f));
        }

        /** Stopped dead by a wall: the wall cracks, sparks fly, the ball shatters. */
        private void crash(RayTraceResult wall) {
            Location at = wall.getHitPosition().toLocation(world);
            Block block = wall.getHitBlock();
            if (block != null && !block.getType().isAir()) {
                world.spawnParticle(Particle.BLOCK_CRUMBLE, at, Vfx.n(ctx(), 16), 0.3, 0.3, 0.3, 0,
                        block.getBlockData());
            }
            world.spawnParticle(Particle.CRIT, at, Vfx.n(ctx(), 10), 0.2, 0.2, 0.2, 0.3);
            Vfx.dot(at, Particle.POOF, null, false);
            Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.8f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_ANVIL_LAND, 0.6f, 0.6f + Fx.jitter(0.05f));
            finish(End.SHATTER);
        }

        /** The loop ended by itself: the ball has rolled out (or never hit anything) and comes to rest. */
        void rest() {
            finish(End.FADE);
        }

        private void finish(End end) {
            if (finished) {
                return;
            }
            finished = true;
            Player owner = player();
            switch (end) {
                case SINK -> {
                    world.spawnParticle(Particle.SPLASH, center, Vfx.n(ctx(), 20), 0.3, 0.1, 0.3, 0.1);
                    world.spawnParticle(Particle.BUBBLE_COLUMN_UP, center, Vfx.n(ctx(), 8), 0.2, 0.2, 0.2, 0.05);
                    Fx.sound(center, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.8f, 0.7f + Fx.jitter(0.05f));
                }
                case SHATTER -> world.spawnParticle(Particle.DUST, center, Vfx.n(ctx(), 14), 0.25, 0.25, 0.25, 0,
                        IRON_DUST);
                case FADE -> {
                    Vfx.cloud(ctx(), center, Particle.SMOKE, 4, 0.15, 0.01, null, false);
                    Fx.sound(center, Sound.BLOCK_HEAVY_CORE_STEP, 0.6f, 0.5f);
                }
            }
            for (BlockDisplay cube : cubes) {
                if (cube != null && cube.isValid()) {
                    displays.remove(cube);
                    Displays.shrinkAway(ctx(), owner, cube, end == End.SHATTER ? 2 : 5);
                }
            }
            if (hits > 1 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚓ 대포알 <white>" + hits + "명 관통!</white></"
                        + COLOR_TAG + ">"));
            }
        }
    }

    private enum End { FADE, SHATTER, SINK }

    // ------------------------------------------------------------------ ultimate: ghost ship

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        GhostShip previous = ship;
        int freed = previous == null ? 0 : previous.budgeted();
        if (!roomFor(SHIP.length + LANTERNS.length - freed)) {
            return ActivationResult.fail("유령선을 부를 수 없습니다");
        }
        if (previous != null) {
            // A refilled gauge calls a new ship: the old one sinks (and leaves the display budget) right away.
            previous.depart();
        }
        GhostShip next = new GhostShip(owner, ++shipToken);
        ship = next;
        next.sail();
        return ActivationResult.success();
    }

    /**
     * The ghost ship: it rises out of sea mist behind the pirate, follows it rocking gently, and fires a shell every
     * interval at whatever the pirate is aiming at. Each shell arcs from one of its three cannons, its landing spot
     * marked by a closing teal ring, and bursts in spectral water and soul flame.
     */
    private final class GhostShip {

        private final Player owner;
        private final int token;
        private final World world;
        private final List<Display> parts = new ArrayList<>();
        private final List<Shell> shells = new ArrayList<>();
        private final Map<UUID, Integer> hitsTaken = new HashMap<>();
        private final long duration;
        private final long interval;
        private final int salvo;
        private Location position;
        /** The ship's heading as a Minecraft yaw (its bow), its rocking pitch, size and billowing sails. */
        private float yaw;
        private float rock;
        private float scale = SHIP_SPAWN_SCALE;
        private float billow = 1f;
        /** Ticks each cannon is still drawn recoiled. */
        private final int[] recoil = new int[CANNON_Z.length];
        private int fired;
        private int nextCannon;
        private int totalHits;
        private boolean departed;

        GhostShip(Player owner, int token) {
            this.owner = owner;
            this.token = token;
            this.world = owner.getWorld();
            this.duration = Math.max(SHIP_STEP, params().ticks("ult_duration"));
            this.interval = Math.max(1, params().ticks("ult_interval"));
            this.salvo = (int) Math.max(1, duration / interval);
            this.yaw = owner.getLocation().getYaw() + 90;
            this.position = berth(owner).subtract(0, 1.6, 0);
        }

        void sail() {
            Location at = Displays.unrotated(position);
            long lifetime = duration + MAX_SHELL_TICKS + SHIP_SINK_TICKS + 20;
            for (int i = 0; i < SHIP.length; i++) {
                BlockDisplay piece = display(at, SHIP[i].material(), partPose(i), lifetime, true);
                piece.setTeleportDuration((int) SHIP_STEP);
                piece.setViewRange(2.0f);
                parts.add(piece);
            }
            for (int i = 0; i < LANTERNS.length; i++) {
                ItemDisplay light = Displays.item(ctx(), owner, at, ItemStack.of(Material.SOUL_LANTERN),
                        partPose(SHIP.length + i), lifetime);
                light.setTeleportDuration((int) SHIP_STEP);
                displays.add(light);
                parts.add(light);
            }

            // Telegraph: sea mist swirls up behind the pirate and the ship's bell tolls.
            Location mist = berth(owner).subtract(0, 1.0, 0);
            Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.1, 0), 0.5, 4.5, 8,
                    Particle.DUST_COLOR_TRANSITION, GHOST_GLOW, true);
            world.spawnParticle(Particle.CLOUD, mist, Vfx.n(ctx(), 40), 1.8, 0.6, 1.8, 0.02, null, true);
            world.spawnParticle(Particle.SOUL, mist, Vfx.n(ctx(), 16), 1.5, 0.6, 1.5, 0.03, null, true);
            Vfx.helix(ctx(), mist.clone().subtract(0, 1.0, 0), 2.2, 3.5, 1.5, 2, 18, 0, Particle.DUST_COLOR_TRANSITION,
                    GHOST_FADE, true);
            Fx.sound(mist, Sound.BLOCK_BELL_USE, 1.6f, 0.6f);
            Fx.sound(mist, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 1.2f, 0.6f);
            Fx.sound(mist, Sound.AMBIENT_UNDERWATER_ENTER, 1.0f, 0.6f);
            ctx().runLater(owner, 8, () -> Fx.sound(owner.getLocation(), Sound.BLOCK_BELL_USE, 1.4f, 0.75f));

            long runs = duration + MAX_SHELL_TICKS;
            Loop.repeat(ctx(), owner, 1, 1, runs, this::step, this::depart);
        }

        private boolean step(long tick) {
            if (departed || token != shipToken) {
                return false;
            }
            if (tick % SHIP_STEP == 0) {
                follow(tick);
            }
            if (tick < duration && tick >= SHIP_RISE_TICKS / 2 && (tick - SHIP_RISE_TICKS / 2) % interval == 0
                    && fired < salvo) {
                fire();
            }
            for (Iterator<Shell> it = shells.iterator(); it.hasNext(); ) {
                Shell shell = it.next();
                if (shell.fly()) {
                    it.remove();
                    impact(shell.target);
                }
            }
            ambience(tick);
            return tick < duration || !shells.isEmpty();
        }

        /** Where the ship wants to be: behind the pirate and above it, lower under a ceiling. */
        private Location berth(Player pirate) {
            Location feet = pirate.getLocation();
            Vector back = Motion.horizontalFacing(feet).multiply(-SHIP_BEHIND);
            Location spot = Displays.unrotated(feet.clone().add(back));
            double height = SHIP_HEIGHT;
            RayTraceResult ceiling = world.rayTraceBlocks(spot.clone().add(0, 1, 0), new Vector(0, 1, 0),
                    SHIP_HEIGHT + 2.5, FluidCollisionMode.NEVER, true);
            if (ceiling != null) {
                height = Math.max(1.2, ceiling.getHitPosition().getY() - spot.getY() - 2.6);
            }
            return spot.add(0, height, 0);
        }

        /**
         * Glides after the pirate, turning its broadside to the pirate's aim, rising to full size and rocking on
         * invisible waves. The displays only move; heading, rocking and size are all in their transformations.
         */
        private void follow(long tick) {
            if (!owner.getWorld().equals(world)) {
                return;
            }
            Location target = berth(owner);
            position.add(target.toVector().subtract(position.toVector()).multiply(SHIP_FOLLOW));
            float wanted = owner.getLocation().getYaw() + 90;
            float turn = Math.clamp(wrap(wanted - yaw) * (float) SHIP_FOLLOW, -MAX_SHIP_TURN, MAX_SHIP_TURN);
            yaw = wrap(yaw + turn);
            rock = (float) (3 * Math.sin(tick * 0.09));
            scale = Math.min(1f, SHIP_SPAWN_SCALE + (1f - SHIP_SPAWN_SCALE) * (tick + SHIP_STEP) / SHIP_RISE_TICKS);
            billow = 1f + 0.25f * (float) Math.sin(tick * 0.4);
            for (int i = 0; i < recoil.length; i++) {
                recoil[i] = Math.max(0, recoil[i] - (int) SHIP_STEP);
            }
            Location at = Displays.unrotated(position.clone().add(0, 0.15 * Math.sin(tick * 0.12), 0));
            for (Display part : parts) {
                Displays.moveTo(part, at);
            }
            pose((int) SHIP_STEP);
        }

        /** Animates every part to the ship's current heading, rocking, size, sails and cannon recoil. */
        private void pose(int ticks) {
            for (int i = 0; i < parts.size(); i++) {
                Display part = parts.get(i);
                if (part.isValid()) {
                    Displays.animate(part, partPose(i), ticks);
                }
            }
        }

        /** Part {@code index} (ship parts first, then the lanterns), turned to the ship's heading. */
        private Transformation partPose(int index) {
            Quaternionf frame = headingFrame(yaw, rock);
            if (index >= SHIP.length) {
                float[] lantern = LANTERNS[index - SHIP.length];
                float size = 0.55f * scale;
                return framed(frame, Displays.centered(lantern[0] * scale, lantern[1] * scale, lantern[2] * scale,
                        size, size, size, 0, 0, 0));
            }
            Part part = SHIP[index];
            float x = part.x();
            float depth = part.sz();
            if (index == SAIL) {
                depth *= billow;
            } else if (index == FORE_SAIL) {
                depth *= 1f + (billow - 1f) * 0.9f;
            }
            int cannon = index - FIRST_CANNON;
            if (cannon >= 0 && cannon < recoil.length && recoil[cannon] > 0) {
                x -= 0.2f;
            }
            return framed(frame, Displays.transform(x * scale, part.y() * scale, part.z() * scale, part.sx() * scale,
                    part.sy() * scale, depth * scale, 0, part.pitch(), 0));
        }

        /** One shell from the next cannon, aimed at the pirate's crosshair (spread around it after the first). */
        private void fire() {
            int index = fired++;
            Location aim = aimPoint();
            if (aim == null) {
                return;
            }
            double spread = Math.max(0, params().num("ult_radius")) * 0.5;
            double[] offset = SPREAD_PATTERN[index % SPREAD_PATTERN.length];
            Location target = aim.clone().add(offset[0] * spread, 0, offset[1] * spread);
            Location landed = settle(target);
            int cannon = nextCannon++ % CANNON_Z.length;
            Location muzzle = muzzle(cannon);
            // The barrel kicks back inboard for a moment.
            recoil[cannon] = 4;
            Display barrel = parts.size() > FIRST_CANNON + cannon ? parts.get(FIRST_CANNON + cannon) : null;
            if (barrel != null && barrel.isValid()) {
                Displays.animate(barrel, partPose(FIRST_CANNON + cannon), 2);
            }
            Vfx.dot(muzzle, Particle.FLASH, GHOST.core(), true);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, muzzle, Vfx.n(ctx(), 10), 0.15, 0.15, 0.15, 0.08, null,
                    true);
            world.spawnParticle(Particle.CLOUD, muzzle, Vfx.n(ctx(), 6), 0.2, 0.2, 0.2, 0.03, null, true);
            Fx.sound(muzzle, Sound.ENTITY_GHAST_SHOOT, 1.2f, 0.6f + Fx.jitter(0.08f));
            Fx.sound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f + Fx.jitter(0.08f));
            shells.add(new Shell(muzzle, landed));
        }

        /** The pirate's crosshair: the aimed enemy, else the floor (or wall) the view ray hits, else far ahead. */
        private @Nullable Location aimPoint() {
            if (!owner.isOnline() || !owner.getWorld().equals(world)) {
                return null;
            }
            double range = Math.max(1, params().num("ult_range"));
            LivingEntity aimed = ctx().findTarget(owner, range);
            if (aimed != null && seen(owner, aimed)) {
                return aimed.getLocation();
            }
            Location eye = owner.getEyeLocation();
            Vector direction = eye.getDirection();
            RayTraceResult hit = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            if (hit != null) {
                return hit.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.3));
            }
            return eye.clone().add(direction.multiply(range));
        }

        /** Drops a target point onto the floor below it, if there is one within reach (else it bursts in the air). */
        private Location settle(Location target) {
            RayTraceResult floor = world.rayTraceBlocks(target.clone().add(0, 1.0, 0), new Vector(0, -1, 0),
                    AIR_BURST_SEARCH, FluidCollisionMode.ALWAYS, true);
            if (floor == null) {
                return target;
            }
            return floor.getHitPosition().toLocation(world);
        }

        private Location muzzle(int cannon) {
            Vector local = new Vector(1.15, 0.12, CANNON_Z[cannon]);
            return position.clone().add(rotate(local, yaw));
        }

        /** Ghostly ambience: soul fire in the lanterns, mist dripping off the keel, motes off the sails. */
        private void ambience(long tick) {
            if (tick >= duration) {
                return;
            }
            Location keel = position.clone().add(Fx.jitter(0.6f), -0.5, Fx.jitter(0.6f)).add(rotate(
                    new Vector(0, 0, Fx.jitter(1.8f)), yaw));
            Vfx.dot(keel, Particle.DUST_COLOR_TRANSITION, GHOST_FADE, true);
            if (tick % 2 == 0) {
                world.spawnParticle(Particle.CLOUD, keel, 1, 0.1, 0.05, 0.1, 0.005, null, true);
            }
            if (tick % 3 == 0) {
                for (float[] lantern : LANTERNS) {
                    Location light = position.clone().add(rotate(new Vector(lantern[0], lantern[1] + 0.15,
                            lantern[2]), yaw));
                    Vfx.dot(light, Particle.SOUL_FIRE_FLAME, null, true);
                }
            }
            if (tick % 5 == 0) {
                Location sail = position.clone().add(rotate(new Vector(Fx.jitter(1.0f), 2.2 + Fx.jitter(0.8f), 0.3),
                        yaw));
                Vfx.dot(sail, Particle.SOUL, null, true);
            }
            if (tick % 30 == 15) {
                Fx.sound(position, Sound.BLOCK_WOOD_STEP, 0.7f, 0.5f);
                Fx.sound(position, Sound.ENTITY_BOAT_PADDLE_WATER, 0.8f, 0.6f + Fx.jitter(0.1f));
            }
        }

        /** A shell bursts: spectral water and soul flame, a shock ring, damage and a shove (capped per enemy). */
        private void impact(Location at) {
            double radius = Math.max(0.5, params().num("ult_radius"));
            double damage = params().num("ult_damage");
            int maxHits = Math.max(1, params().integer("ult_max_hits"));
            Location burst = at.clone().add(0, 0.3, 0);
            Vfx.dot(burst, Particle.FLASH, GHOST.body(), true);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, burst, Vfx.n(ctx(), 22), 0.4, 0.3, 0.4, 0.12, null, true);
            world.spawnParticle(Particle.SPLASH, burst, Vfx.n(ctx(), 30), radius * 0.4, 0.3, radius * 0.4, 0.4, null,
                    true);
            world.spawnParticle(Particle.BUBBLE_POP, burst, Vfx.n(ctx(), 12), radius * 0.4, 0.4, radius * 0.4, 0.05,
                    null, true);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, burst.clone().add(0, 0.6, 0), Vfx.n(ctx(), 18), 0.3,
                    0.9, 0.3, 0, GHOST_GLOW, true);
            Block floor = at.clone().subtract(0, 0.2, 0).getBlock();
            if (!floor.getType().isAir() && !floor.isLiquid()) {
                BlockData data = floor.getBlockData();
                world.spawnParticle(Particle.BLOCK_CRUMBLE, burst, Vfx.n(ctx(), 14), radius * 0.3, 0.1, radius * 0.3,
                        0, data, true);
            }
            Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.1, 0), 0.3, radius, 5, Particle.DUST_COLOR_TRANSITION,
                    GHOST_FADE, true);
            Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.1f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.8f, 0.7f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.PARTICLE_SOUL_ESCAPE, 1.0f, 0.8f + Fx.jitter(0.1f));

            for (LivingEntity enemy : ctx().enemiesNear(owner, burst, radius)) {
                int taken = hitsTaken.merge(enemy.getUniqueId(), 1, Integer::sum);
                if (taken > maxHits) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                totalHits++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(at.toVector()));
                Motion.push(enemy, away.multiply(SHELL_PUSH).setY(SHELL_LIFT), MAX_SPEED);
                if (seen(owner, enemy)) {
                    Vfx.cloud(ctx(), Fx.body(enemy), Particle.SOUL, 3, 0.25, 0.02, null, false);
                }
            }
        }

        /** How many of the display budget's entries are this ship's (its parts and the shells in flight). */
        int budgeted() {
            int count = 0;
            for (Display display : displays) {
                if (parts.contains(display) || shells.stream().anyMatch(shell -> display.equals(shell.body))) {
                    count++;
                }
            }
            return count;
        }

        /**
         * Time is up (or a new ship was called): the ship sinks back into the mist with a last toll of the bell. Its
         * parts leave the display budget at once, while they sink.
         */
        void depart() {
            if (departed) {
                return;
            }
            departed = true;
            if (ship == this) {
                ship = null;
            }
            displays.removeAll(parts);
            for (Shell shell : shells) {
                shell.discard();
            }
            shells.clear();
            Location at = position.clone();
            world.spawnParticle(Particle.CLOUD, at, Vfx.n(ctx(), 30), 1.8, 0.5, 1.8, 0.02, null, true);
            world.spawnParticle(Particle.SOUL, at, Vfx.n(ctx(), 12), 1.5, 0.6, 1.5, 0.02, null, true);
            Fx.sound(at, Sound.BLOCK_BELL_USE, 1.2f, 0.5f);
            Fx.sound(at, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 1.0f, 0.5f);
            scale = 0.001f;
            pose(SHIP_SINK_TICKS);
            for (Display part : parts) {
                if (part.isValid()) {
                    part.setTeleportDuration(SHIP_SINK_TICKS);
                    Displays.moveTo(part, part.getLocation().subtract(0, 1.5, 0));
                }
            }
            ctx().runLater(owner, SHIP_SINK_TICKS + 1, () -> {
                for (Display part : parts) {
                    displays.remove(part);
                    if (part.isValid()) {
                        part.remove();
                    }
                }
                parts.clear();
            });
            if (totalHits > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + GHOST_TAG + ">☠ 유령선 포격 <white>" + totalHits + "발 명중!</white></"
                        + GHOST_TAG + ">"));
            }
        }

        /** A shell in flight: a dark iron ball on an arc, trailing soul fire, its landing ring closing in. */
        private final class Shell {
            private final Location from;
            private final Location target;
            private final long flight;
            private final double apex;
            private final @Nullable BlockDisplay body;
            private long age;

            Shell(Location from, Location target) {
                this.from = from.clone();
                this.target = target.clone();
                double distance = from.distance(target);
                this.flight = Math.clamp(Math.round(8 + distance * 0.35), MIN_SHELL_TICKS, MAX_SHELL_TICKS);
                this.apex = SHELL_APEX + distance * 0.08;
                this.body = roomFor(1) ? display(from, Material.BLACK_CONCRETE, cubePose(0.36f, new Quaternionf()),
                        flight + 4, false) : null;
                if (body != null) {
                    body.setTeleportDuration(1);
                    Displays.outline(body, GHOST.body());
                }
            }

            /** One tick of flight; true when the shell has landed. */
            boolean fly() {
                age++;
                double t = Math.min(1.0, (double) age / flight);
                Location at = from.clone().add(target.toVector().subtract(from.toVector()).multiply(t))
                        .add(0, apex * 4 * t * (1 - t), 0);
                if (body != null && body.isValid()) {
                    Displays.moveTo(body, at);
                    if (age > 1) {
                        // Tumbles from the tick after it was fired (the client needs a tick to start interpolating).
                        Displays.animate(body, cubePose(0.36f, new Quaternionf().rotateXYZ(age * 0.5f, age * 0.3f, 0)),
                                1);
                    }
                }
                Vfx.dot(at, Particle.SOUL_FIRE_FLAME, null, true);
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, GHOST_FADE, true);
                if (age % 2 == 0) {
                    double radius = Math.max(0.5, params().num("ult_radius"));
                    double remaining = 1 - t;
                    Location ring = target.clone().add(0, 0.12, 0);
                    Vfx.ring(ctx(), ring, radius, 16, age * 0.1, Particle.DUST, GHOST_DUST, true);
                    Vfx.ring(ctx(), ring, 0.3 + (radius - 0.3) * remaining, 10, -age * 0.2,
                            Particle.DUST_COLOR_TRANSITION, GHOST_GLOW, true);
                }
                if (age >= flight) {
                    discard();
                    return true;
                }
                return false;
            }

            void discard() {
                if (body != null && body.isValid()) {
                    displays.remove(body);
                    body.remove();
                }
            }
        }
    }

    /** A vector of the ship's frame turned to world space by its heading (the yaw part of {@link #headingFrame}). */
    private static Vector rotate(Vector local, float yaw) {
        return local.clone().rotateAroundY(Math.toRadians(-yaw));
    }

    private static float wrap(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) {
            wrapped -= 360f;
        } else if (wrapped < -180f) {
            wrapped += 360f;
        }
        return wrapped;
    }

    // ------------------------------------------------------------------ shared helpers

    /**
     * The rotation a display entity with this yaw and pitch would give its model (Minecraft's convention: yaw 0 faces
     * +z, 90 faces -x; positive pitch tips the front down). The displays here stay unrotated and carry it in their
     * transformations instead, so nothing can add an entity's rotation on top.
     */
    private static Quaternionf headingFrame(float yawDegrees, float pitchDegrees) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yawDegrees))
                .rotateX((float) Math.toRadians(pitchDegrees));
    }

    /** {@code local}, a pose in a frame of its own, turned by {@code frame} about the display's origin. */
    private static Transformation framed(Quaternionf frame, Transformation local) {
        Vector3f translation = new Quaternionf(frame).transform(new Vector3f(local.getTranslation()));
        Quaternionf rotation = new Quaternionf(frame).mul(local.getLeftRotation());
        return new Transformation(translation, rotation, new Vector3f(local.getScale()),
                new Quaternionf(local.getRightRotation()));
    }

    /** A cube of edge {@code size} centered on the block display's origin, rotated around its center. */
    private static Transformation cubePose(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private BlockDisplay display(Location at, Material material, Transformation pose, long lifetime, boolean glow) {
        BlockDisplay display = Displays.block(ctx(), player(), at, material.createBlockData(), pose, lifetime);
        if (!glow) {
            // Iron and wood are lit by the world; only the ghost ship glows.
            display.setBrightness(null);
        }
        displays.add(display);
        return display;
    }

    /** True if {@code count} more displays fit in {@link #MAX_DISPLAYS}. */
    private boolean roomFor(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    /** False for an enemy player hidden from the owner by stealth. */
    private static boolean seen(Player owner, LivingEntity entity) {
        return !(entity instanceof Player player) || player.equals(owner) || owner.canSee(player);
    }

    /** The Minecraft yaw (degrees) of a horizontal direction. */
    private static float yawOf(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }
}
