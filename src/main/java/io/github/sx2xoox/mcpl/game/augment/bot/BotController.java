package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Contact;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Decision;
import io.github.sx2xoox.mcpl.game.augment.bot.BotMotion.Gait;
import org.bukkit.FluidCollisionMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Carries out one bot's decisions: it perceives (line of sight, footsteps nearby, memory of the last known position:
 * never through walls), asks its {@link BotBrain} every {@code reaction} ticks and acts every tick: walking with the
 * pathfinder, strafing, swinging (with jump crits), drawing and leading the bow, eating golden apples, building its
 * base during the farming and walling itself in when hunted. The {@link BotProfile} sets how well it does all that.
 */
public final class BotController {

    /**
     * Stuck detection on (the default). Off only in mock-server tests, whose pathfinder never moves the legs (every bot
     * would look stuck there).
     */
    static boolean autoRecovery = true;

    /** How long a bot remembers where it last saw or heard someone. */
    static final int MEMORY_TICKS = 200;
    /** Footsteps are heard this close (sneaking: {@link #SNEAK_HEARING}). */
    static final double HEARING = 10;
    static final double SNEAK_HEARING = 3;
    static final double SIGHT = 64;
    static final double REACH = 3.0;
    static final int DRAW_TICKS = 20;
    static final int APPLE_TICKS = 32;
    /** After walling itself in once, how long before it may do it again. */
    static final int FORTIFY_COOLDOWN = 30 * 20;
    /** The longest it stays walled in: a short pause to heal, never a place to sit out the match. */
    static final int FORTIFY_TICKS = 8 * 20;
    /** A player with food heals about 1 health every 4 s out of combat; so does a bot (it does not eat). */
    static final int REGEN_TICKS = 80;
    static final int REGEN_AFTER_HURT = 100;

    /** Someone perceived; {@code inSight} if in line of sight (else heard, or glowing through a wall). */
    private record Seen(LivingEntity entity, Location at, long tick, double distance, double previous,
                        boolean inSight) {
    }

    /** After this long without a fight (dealt or taken) a bot is fully "idle" and goes looking for one. */
    static final int IDLE_TICKS = 60 * 20;
    /** Where a rival was last seen or heard is a lead worth following this long (beyond the short memory). */
    static final int LEAD_TICKS = 60 * 20;
    /** Fights (hits) are heard this far away, like a player hears swords and bows. */
    static final double FIGHT_HEARING = 48;
    /** A failed site search is tried again after this many ticks (it roams in between). */
    static final int PLAN_AGAIN_TICKS = 5 * 20;
    /** A base site it has walked towards this many ticks without getting any closer is given up for another. */
    static final int SITE_WALK_TICKS = 25 * 20;
    /** Blocks closer to its base site that count as getting there (a slow walk over rough ground is no reason to quit). */
    static final double SITE_PROGRESS = 3;
    /** Inspections (two seconds apart) that may find a hole in its base still open before it is left. */
    static final int MEND_TRIES = 30;
    /** Holes in its walls and roof (beyond those it was built with) that make its base no place to hold. */
    static final int BREACH_HOLES = 6;
    /** How far a trapper throws a cobweb onto an enemy's feet. */
    static final double WEB_THROW_REACH = 6;
    /** Blocks a builder keeps back from its base's extras and traps (to shut its door, bridge, wall itself in). */
    static final int BLOCK_RESERVE = 24;
    /** How far (blocks up or down) the ground on its doorway's lane may be off its floor for it to walk in. */
    static final double LANE_SLACK = 2.5;
    /** Not moving on its own doorway's lane for this long, it clears the cell ahead and steps into it. */
    static final int LANE_STUCK_TICKS = 15;
    /** A sniper in its nest without a line of fire for this long comes down to shoot from the ground. */
    static final int NEST_BLIND_TICKS = 3 * 20;
    /** Ticks between two cobwebs a trapper throws on its enemy's feet. */
    static final int WEB_THROW_TICKS = 6 * 20;
    /** A trapper webs an enemy this close whether or not it is coming at it. */
    static final double WEB_CLOSE = 4.5;
    /** Real ticks before the fight a bot with a finished base heads home. */
    static final int HOME_BEFORE_FIGHT_TICKS = 15 * 20;
    /** Its pace over rough ground on the way to a base site, for planning (sprinting, with detours). */
    static final double WALK_BLOCKS_PER_SECOND = 4.0;
    /** A base must stay clear of the border at least this long ahead to be kept (ticks). */
    static final long BASE_SAFE_TICKS = 30 * 20;
    /** Time to get out of a shut base (gate, airlock, shaft) before the walk in starts (ticks). */
    static final long BASE_EXIT_TICKS = 10 * 20;

    private final UUID id;
    private final String name;
    private final BotDifficulty difficulty;
    private final BotManager manager;
    private final BotHost host;
    private final BotProfile profile;
    private final BotBrain brain;
    private final Random random;
    private final int offset;
    private @Nullable BotBody body;
    private Gear.Loadout loadout = Gear.at(0);
    private int apples;
    private boolean applesGiven;
    private int arrows;
    private int blocksUsed;
    private int arrowsUsed;
    private @Nullable Decision decision;
    private long nextThink;
    private long nextPath;
    private @Nullable Location goal;
    private final Map<UUID, Seen> memory = new HashMap<>();
    /** 팀전: the teammate building its team's base (it shares that base instead of building one), or null. */
    private @Nullable BotController architect;
    /** 팀전: its team's base and what the team puts into it, or null. */
    private TeamTactics.@Nullable SharedBase teamBase;
    private BasePlanner.@Nullable Plan base;
    private BasePlanner.@Nullable Palette palette;
    /** The shaft block it puts under its feet climbing its tower, and when it jumped. */
    private int @Nullable [] shaftCell;
    /** When a trapper may throw its next cobweb, and until when a tank's shield stays up. */
    private long nextThrowAt;
    /** Traps a trapper has laid around its base. */
    private int trapsLaid;
    /** Cobwebs a trapper threw on enemies' feet. */
    private int websThrown;
    private long shieldUpUntil;
    /** When it next digs out whatever blocks its doorway on the way in or out. */
    private long nextPassageClear;
    /** Where it stood on its doorway's lane, and since when (to notice it is wedged there). */
    private @Nullable Location laneLast;
    private long laneStill;
    /** Its tower's nest has had no line of fire to its target since then (-1: it has). */
    private long nestBlindSince = -1;
    /** Until then it shoots from the ground (its nest saw nothing). */
    private long nestBlindUntil;
    /** The nest slit with a line of fire, looked up again at {@link #slitCheckAt}. */
    private long slitCheckAt;
    private int @Nullable [] slitChosen;
    private long shaftSince;
    /** The layout it builds (its personality's; a gambler's drawn once). */
    private BasePlanner.@Nullable Design design;
    private boolean baseFailed;
    private int built;
    private double buildCredit;
    private final List<Block> fortress = new ArrayList<>();
    private long fortifyUntil = -1;
    private long fortifyAgainAt;
    private @Nullable Location shore;
    private long shoreUntil;
    private double lastHealth = -1;
    private long lastHurtAt;
    private long nextSwing;
    private long drawStart = -1;
    private long nextShot;
    private long nextAppleAt;
    private long nextShieldAt;
    private int strafe = 1;
    private long strafeAt;
    /** The head (camera) as the client sees it. */
    private float yaw;
    private float pitch;
    private float bodyYaw;
    /** Where the bot means to look this tick (NaN: nothing in particular), set by {@link #face}. */
    private float wantYaw = Float.NaN;
    private float wantPitch = Float.NaN;
    /** A sound it turned towards (someone heard but not seen). */
    private @Nullable Location glance;
    private long glanceUntil;
    private long eatLookUntil;
    private long idleLookAt;
    private float idleYaw;
    private float idlePitch;
    private @Nullable Location wander;
    private long wanderUntil;
    private @Nullable Location holdSpot;
    private @Nullable Location lastPos;
    private long lastMoveCheck;
    private float lastFall;
    private Gait gait = Gait.WALK;
    private double pathSpeed;
    /** When it last dealt or took PvP damage (-1: never). */
    private long lastFightAt = -1;
    /** When it first noticed PvP was on (-1: not yet). */
    private long fightStartSeen = -1;
    /** The last place a rival was seen or heard (kept longer than the memory, for scouting). */
    private @Nullable Location lead;
    private long leadAt;
    /** Every block it placed (base and shelter) with its material: it may break them again to get out. */
    private final Map<Block, Material> placed = new HashMap<>();
    /** Its personality (score multipliers and habits). */
    private final BotPersonality.Traits traits;
    /** It gave up a base the border was about to take and builds a new one nearer the middle. */
    private boolean relocating;
    /** The base's doorway is shut with its own blocks. */
    private boolean doorClosed;
    /** Ticks the placement in hand has failed (someone stands there): passed over after a second, mended later. */
    private int buildBlockedTicks;
    /** Holes in its finished base to fill ({@link #inspectBase}), and how often each was tried. */
    private final List<BasePlanner.Placement> mending = new ArrayList<>();
    private final Map<Block, Integer> mendTries = new HashMap<>();
    private long nextInspect;
    /** The cells of its base's shell (for {@link #ownShell}), for the plan they were made for. */
    private final java.util.Set<Long> shellCells = new java.util.HashSet<>();
    private BasePlanner.@Nullable Plan shellOf;
    private long nextBreachCheck;
    private boolean wasBreached;
    /** No site found: when it looks again. */
    private long planAgainAt;
    /** Ticks it has been walking to its base site without getting {@link #SITE_PROGRESS} blocks closer. */
    private int siteWalk;
    /** The closest it has got to its base site on the way there. */
    private double siteBest = Double.POSITIVE_INFINITY;
    /** Sites it could not reach ({x, z}). */
    private final List<int[]> badSites = new ArrayList<>();
    /** Whom it holds a grudge against, and since when (ticks). */
    private final Map<UUID, Long> grudges = new HashMap<>();
    /** Where a grudge target was last seen or heard. */
    private @Nullable Location grudgeLead;
    private long grudgeLeadAt;
    /** Cobwebs it laid behind itself this fight (a trapper's), and when it may lay the next. */
    private int websLaid;
    private long nextWebAt;
    // movement: waypoints, progress, recovery
    private @Nullable Location waypoint;
    private @Nullable Location waypointFor;
    private long waypointUntil;
    private @Nullable Location progressAt;
    private @Nullable Location progressGoal;
    private double progressDistance;
    private long progressTick = -1;
    private int pathFailures;
    private int stuckCount;
    private long recoverUntil = -1;
    private int recoverSteps;
    private long nextDig;
    private long nextPathCheck;
    private int turn;
    private int @Nullable [] stepTo;
    private long stepSince;
    private int @Nullable [] pillarCell;
    /** Recovery steps by kind over the match (for tests and the real-server check). */
    private final Map<Recovery.Kind, Integer> recoveries = new java.util.EnumMap<>(Recovery.Kind.class);
    /** Times it got out of powder snow. */
    private int snowEscapes;
    /** The pit cell it fills under itself once it has jumped (climbing out of a trap pit). */
    private int @Nullable [] pitCell;
    private long pitSince;

    BotController(UUID id, String name, BotDifficulty difficulty, BotManager manager, BotHost host, BotProfile profile,
                  Random random, int offset) {
        this(id, name, difficulty, manager, host, profile, BotPersonality.Traits.NEUTRAL, random, offset);
    }

    BotController(UUID id, String name, BotDifficulty difficulty, BotManager manager, BotHost host, BotProfile profile,
                  BotPersonality.Traits traits, Random random, int offset) {
        this.id = id;
        this.name = name;
        this.difficulty = difficulty;
        this.manager = manager;
        this.host = host;
        this.profile = profile;
        this.traits = traits;
        this.brain = new BotBrain(profile, traits, new Random(random.nextLong()));
        this.random = random;
        this.offset = offset;
        this.nextThink = offset % 20; // decisions of different bots fall on different ticks
    }

    /** Its personality's traits. */
    public BotPersonality.Traits traits() {
        return traits;
    }

    /** Its personality, null for the neutral mind. */
    public @Nullable BotPersonality personality() {
        return traits.personality();
    }

    /** True while its base's doorway is shut. */
    public boolean doorClosed() {
        return doorClosed;
    }

    /** How often it used each recovery step (tunnel, stairs, pillar, bridge). */
    public Map<Recovery.Kind, Integer> recoveries() {
        return Map.copyOf(recoveries);
    }

    /** Times it got itself out of powder snow. */
    public int snowEscapes() {
        return snowEscapes;
    }

    /** True while it digs, climbs or bridges its way out of a spot the pathfinder cannot leave. */
    public boolean recovering() {
        return recoverUntil >= 0;
    }

    /** It holds a grudge against {@code who} (who hurt it or killed near it). */
    void grudge(UUID who, long now) {
        if (!who.equals(id)) {
            grudges.put(who, now);
        }
    }

    /**
     * {@code who} hit it ({@code by}: their body, or null): it knows where they are now, even unseen behind its walls
     * (a player turns towards whatever shot them).
     */
    void struck(UUID who, @Nullable LivingEntity by, long now) {
        grudge(who, now);
        BotBody b = body;
        if (by == null || b == null || who.equals(id) || !by.getWorld().equals(b.body().getWorld())) {
            return;
        }
        double distance = by.getLocation().distance(b.body().getLocation());
        Seen old = memory.get(who);
        memory.put(who, new Seen(by, by.getLocation(), now, distance, old == null ? distance : old.distance(),
                old != null && old.tick() == now && old.inSight()));
        glance = by.getLocation();
        glanceUntil = now + 25;
    }

    boolean holdsGrudge(UUID who) {
        return grudges.containsKey(who);
    }

    public UUID id() {
        return id;
    }

    /** The name on its body ("철수 봇"); the match shows {@code session.name(id)} (with the difficulty when mixed). */
    public String name() {
        return name;
    }

    public BotDifficulty difficulty() {
        return difficulty;
    }

    public @Nullable BotBody body() {
        return body;
    }

    void attach(BotBody spawned) {
        this.body = spawned;
        Location at = spawned.legs().getLocation();
        this.yaw = BotAim.wrap(at.getYaw());
        this.bodyYaw = yaw;
        this.idleYaw = yaw;
        spawned.dress(loadout);
    }

    public Gear.Loadout loadout() {
        return loadout;
    }

    public int apples() {
        return apples;
    }

    public int blocks() {
        return Math.max(0, stock(loadout) - blocksUsed);
    }

    public int arrows() {
        return Math.max(0, loadout.arrows() - arrowsUsed);
    }

    public @Nullable Decision decision() {
        return decision;
    }

    public BasePlanner.@Nullable Plan base() {
        return base;
    }

    /** Blocks of the base placed so far. */
    public int built() {
        return built;
    }

    BotProfile profile() {
        return profile;
    }

    /** The gear score others read on this bot. */
    double gearScore() {
        return Gear.score(loadout.armorPoints() + bonus(Attribute.ARMOR), loadout.toughness(),
                loadout.sword().damage() + bonus(Attribute.ATTACK_DAMAGE));
    }

    // =================================================================== tick

    void tick(long now) {
        BotBody b = body;
        if (b == null || !b.valid()) {
            return;
        }
        syncTeamBase(now);
        if (traits.builds() && architect == null) {
            inspectBase(now, b.body());
        }
        if ((now + offset) % 20 == 0) {
            updateGear();
        }
        regenerate(now, b.body());
        if (host.fight() && !applesGiven) {
            applesGiven = true;
            apples = profile.goldenApples();
        }
        if (now >= nextThink) {
            think(now);
            nextThink = now + profile.reactionTicks() + (random.nextInt(3) == 0 ? 1 : 0);
        }
        wantYaw = Float.NaN;
        wantPitch = Float.NaN;
        if (now > shieldUpUntil && b.body().isHandRaised() && drawStart < 0) {
            b.body().clearActiveItem();
        }
        try {
            if (drowning(b.body())) {
                surface(now, b); // nothing else matters while out of air
            } else if (inPowderSnow(b)) {
                escapeSnow(now, b); // it freezes in there: out first
            } else if (inTrapPit(b)) {
                climbOutOfPit(now, b); // a trapper's hidden pit: out of it first
            } else {
                act(now, b);
                progress(now, b);
                if (recovering()) {
                    recover(now, b);
                }
            }
        } catch (RuntimeException e) {
            manager.log("bot " + name, e);
        }
        swim(b);
        unstuck(now, b);
        fall(b);
        look(now, b);
        b.sync(yaw, pitch, bodyYaw);
        breathe(b);
    }

    /** Under water and running out of air. */
    private static boolean drowning(Mannequin self) {
        return self.getRemainingAir() < self.getMaximumAir() * 0.6 && self.getEyeLocation().getBlock().isLiquid();
    }

    /** Swims up and heads for the nearest dry ground. */
    private void surface(long now, BotBody b) {
        WanderingTrader legs = b.legs();
        Vector velocity = legs.getVelocity();
        legs.setVelocity(velocity.setY(Math.max(velocity.getY(), 0.3)));
        Location at = legs.getLocation();
        if (shore == null || now >= shoreUntil) {
            shore = nearestDry(at, 24);
            shoreUntil = now + 60;
        }
        if (shore != null) {
            goTo(now, shore, Gait.RUSH);
        }
    }

    /** The body breathes while the legs' head is above the water (it is put where the legs are, not swimming). */
    private static void breathe(BotBody b) {
        Mannequin self = b.body();
        if (self.getRemainingAir() < self.getMaximumAir() && !self.getEyeLocation().getBlock().isLiquid()
                && !b.legs().getEyeLocation().getBlock().isLiquid()) {
            self.setRemainingAir(self.getMaximumAir());
        }
    }

    /**
     * The nearest standing spot on dry, harmless ground (no water, lava, powder snow, cactus, magma ...) within
     * {@code range} blocks (rings of 4 blocks), or null.
     */
    public static @Nullable Location nearestDry(Location from, int range) {
        World world = from.getWorld();
        for (int r = 4; r <= range; r += 4) {
            for (int i = 0; i < 16; i++) {
                double angle = i * Math.PI / 8;
                int x = from.getBlockX() + (int) Math.round(Math.cos(angle) * r);
                int z = from.getBlockZ() + (int) Math.round(Math.sin(angle) * r);
                Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (top.getType().isSolid() && !top.isLiquid() && !Hazards.hazardousColumn(world, x, z)) {
                    return new Location(world, x + 0.5, top.getY() + 1, z + 0.5);
                }
            }
        }
        return null;
    }

    // =================================================================== powder snow

    /** The legs (and so the body) are sinking in powder snow, or the body has started to freeze in it. */
    private static boolean inPowderSnow(BotBody b) {
        return Hazards.inPowderSnow(b.legs().getLocation())
                || b.body().getFreezeTicks() > 0 && Hazards.inPowderSnow(b.body().getLocation());
    }

    /**
     * Gets out of powder snow at once, like a player with a bucket: it clears the powder snow it is in and the cells
     * on the way to the nearest harmless ground (the match world only), then climbs out there.
     */
    void escapeSnow(long now, BotBody b) {
        WanderingTrader legs = b.legs();
        Location at = legs.getLocation();
        World world = at.getWorld();
        if (!world.equals(host.world())) {
            return;
        }
        snowEscapes++;
        int x = at.getBlockX();
        int y = at.getBlockY();
        int z = at.getBlockZ();
        for (int dy = -1; dy <= 2; dy++) {
            clearHazard(world.getBlockAt(x, y + dy, z));
        }
        Location dry = nearestDry(at, 16);
        if (dry != null) {
            int[] dir = Recovery.direction(at.getX(), at.getZ(), dry.getX(), dry.getZ(), 0);
            for (int dy = 0; dy <= 2; dy++) {
                clearHazard(world.getBlockAt(x + dir[0], y + dy, z + dir[1]));
            }
            if (Hazards.hazard(world.getBlockAt(x + dir[0], y - 1, z + dir[1]))) {
                clearHazard(world.getBlockAt(x + dir[0], y - 1, z + dir[1]));
                placeAny(b.body(), x + dir[0], y - 1, z + dir[1], material(BasePlanner.Part.WALL));
            }
            goal = null;
            goTo(now, dry, Gait.RUSH);
        }
        if (legs.isOnGround() || Hazards.inPowderSnow(at)) {
            legs.setVelocity(legs.getVelocity().setY(0.42));
        }
    }

    /** Its feet are down one of the trappers' hidden pits (a pressure plate is a trap you only step on). */
    private boolean inTrapPit(BotBody b) {
        Block feet = b.legs().getLocation().getBlock();
        return manager.trap(feet) && !Tag.PRESSURE_PLATES.isTagged(feet.getType());
    }

    /**
     * Climbs out of a hidden pit like a player: jumps and puts a block under its feet, again until it stands on top
     * (the pathfinder and its recovery cannot get out of a one-block-wide pit with a bush at the bottom).
     */
    void climbOutOfPit(long now, BotBody b) {
        WanderingTrader legs = b.legs();
        Location at = legs.getLocation();
        int[] cell = pitCell;
        if (cell != null) {
            if (at.getY() >= cell[1] + 1.05 || now - pitSince > 14) {
                World world = at.getWorld();
                clearHazard(world.getBlockAt(cell[0], cell[1], cell[2]));
                placeAny(b.body(), cell[0], cell[1], cell[2], material(BasePlanner.Part.WALL));
                pitCell = null;
            }
            return;
        }
        if (recovering()) {
            endRecovery(now);
        }
        stop();
        if (legs.isOnGround()) {
            pitCell = new int[] {at.getBlockX(), at.getBlockY(), at.getBlockZ()};
            pitSince = now;
            clearHazard(at.getBlock()); // the bush at the bottom
            legs.setVelocity(new Vector(0, 0.42, 0));
        }
    }

    /** Breaks a hazard block (powder snow, cobweb, berry bush, fire) out of its way; never lava or the unbreakable. */
    private void clearHazard(Block block) {
        Material type = block.getType();
        if (Hazards.hazard(type) && type != Material.LAVA && type != Material.MAGMA_BLOCK
                && host.arena().contains(block.getLocation().add(0.5, 0, 0.5))) {
            dig(block);
        }
    }

    /** Breaks a block of the match world (a player mining it): its own placed blocks are forgotten. */
    private void dig(Block block) {
        if (!block.getWorld().equals(host.world()) || Hazards.UNBREAKABLE.contains(block.getType())
                || block.getType().isAir() || recovering() && ownShell(block)) {
            return; // never digs its way out through its own walls: it leaves by the door
        }
        Material was = block.getType();
        block.setType(Material.AIR);
        placed.remove(block);
        fortress.remove(block);
        World world = block.getWorld();
        world.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 10, 0.25, 0.25, 0.25,
                was.createBlockData());
        world.playSound(block.getLocation(), was == Material.POWDER_SNOW ? Sound.BLOCK_POWDER_SNOW_BREAK
                : Sound.BLOCK_STONE_BREAK, 0.7f, 1f);
        BotBody b = body;
        if (b != null) {
            b.body().swingMainHand();
        }
    }


    /**
     * Turns the camera like a player moving the mouse: towards what it deals with ({@link #face}: a target, the bow's
     * aim, a block being placed), else a sound it heard, else where it walks, else an occasional look around; at the
     * difficulty's turn speed, the short way round. The body follows the walking direction (or the head when it
     * fights or stands), never more than {@link BotAim#BODY_LAG} from the head.
     */
    private void look(long now, BotBody b) {
        float speed = difficulty.turnSpeed();
        boolean explicit = !Float.isNaN(wantYaw);
        Location at = b.legs().getLocation();
        float moveYaw = movementYaw(b);
        boolean moving = !Float.isNaN(moveYaw);
        float targetYaw;
        float targetPitch;
        if (explicit) {
            targetYaw = wantYaw;
            targetPitch = wantPitch;
        } else if (glance != null && now < glanceUntil && glance.getWorld() == at.getWorld()) {
            Location eye = at.clone().add(0, 1.62, 0);
            targetYaw = BotAim.yaw(glance.getX() - eye.getX(), glance.getZ() - eye.getZ(), yaw);
            targetPitch = BotAim.pitch(glance.getX() - eye.getX(), glance.getY() + 1.5 - eye.getY(),
                    glance.getZ() - eye.getZ());
        } else if (moving) {
            targetYaw = moveYaw;
            targetPitch = 5f;
        } else {
            if (now >= idleLookAt) {
                idleLookAt = now + 40 + random.nextInt(60);
                idleYaw = BotAim.wrap(bodyYaw + (random.nextFloat() * 2 - 1) * 70f);
                idlePitch = -10f + random.nextFloat() * 25f;
            }
            targetYaw = idleYaw;
            targetPitch = idlePitch;
        }
        if (now < eatLookUntil) {
            targetPitch = Math.max(targetPitch, 30f); // eating: a glance down at the apple
        }
        yaw = BotAim.turn(yaw, targetYaw, speed, explicit && difficulty.flicks());
        pitch = BotAim.turnPitch(pitch, targetPitch, speed * 0.7f);
        float bodyWanted = !explicit && moving ? moveYaw : yaw;
        bodyYaw = BotAim.body(bodyYaw, yaw, bodyWanted, speed * 0.6f);
    }

    /** The walking direction (from the legs' velocity or the next path point), NaN when standing. */
    private float movementYaw(BotBody b) {
        Vector velocity = b.legs().getVelocity();
        if (velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ() > 4.0E-4) {
            return BotAim.yaw(velocity.getX(), velocity.getZ(), yaw);
        }
        if (goal == null) {
            return Float.NaN;
        }
        Location at = b.legs().getLocation();
        Location next = null;
        try {
            var path = b.legs().getPathfinder().getCurrentPath();
            next = path == null ? null : path.getNextPoint();
        } catch (RuntimeException ignored) {
            // no path information: head for the goal
        }
        Location towards = next != null ? next : goal;
        if (towards.getWorld() != at.getWorld() || towards.distanceSquared(at) < 0.25) {
            return Float.NaN;
        }
        return BotAim.yaw(towards.getX() - at.getX(), towards.getZ() - at.getZ(), yaw);
    }

    /** The head's yaw now (for tests). */
    public float headYaw() {
        return yaw;
    }

    /** The head's pitch now (for tests). */
    public float headPitch() {
        return pitch;
    }

    /** Natural regeneration like a fed player's: 1 health every 4 s once not hurt for 5 s. */
    private void regenerate(long now, Mannequin self) {
        double health = self.getHealth();
        if (lastHealth >= 0 && health < lastHealth) {
            lastHurtAt = now;
        }
        if (now - lastHurtAt >= REGEN_AFTER_HURT && (now + offset) % REGEN_TICKS == 0 && !self.isDead()) {
            health = Math.min(maxHealth(self), health + 1);
            self.setHealth(health);
        }
        lastHealth = health;
    }

    /** The gear grows on the schedule (difficulty scales the pace). */
    void updateGear() {
        Gear.Loadout next = Gear.at(host.farmingProgress() * profile.gearPace());
        if (next.score() > loadout.score() || next.blocks() > loadout.blocks()) {
            boolean dress = next.score() != loadout.score() || next.shield() != loadout.shield();
            loadout = next;
            if (dress && body != null) {
                body.dress(loadout);
            }
        }
    }

    // =================================================================== perception and decision

    void think(long now) {
        BotBody b = body;
        if (b == null) {
            return;
        }
        perceive(now, b);
        BotBrain.TeamView team = manager.teams().active()
                ? manager.teams().view(id, b.body().getLocation(), host.fight(), now) : null;
        decision = brain.decide(situation(now, b), team);
    }

    /**
     * 팀전 with a shared base: a bot that is not its team's architect lives in the architect's base (the plan, how far
     * it stands) instead of building its own; the architect builds with the team's rate and blocks. An architect that
     * dies hands the base to the next best builder, who carries on where it stopped.
     */
    private void syncTeamBase(long now) {
        if (!manager.teams().active()) {
            return;
        }
        TeamTactics.SharedBase shared = manager.teams().sharedBase(id, now);
        teamBase = shared;
        BotController arch = shared == null ? null : manager.bot(shared.architect());
        if (arch == null || arch == this) {
            architect = null;
            return;
        }
        architect = arch;
        base = arch.base;
        built = arch.built;
        design = arch.design;
        palette = arch.palette;
        relocating = false;
    }

    /** 팀전: it shares a teammate's base (and does not build one itself). */
    boolean follower() {
        return architect != null;
    }

    private void perceive(long now, BotBody b) {
        Mannequin self = b.body();
        Location at = self.getLocation();
        for (LivingEntity rival : host.rivals(id, self.getWorld())) {
            UUID who = host.participantOf(rival);
            if (who == null) {
                continue;
            }
            double distance = rival.getLocation().distance(at);
            boolean heard = distance <= (rival.isSneaking() ? SNEAK_HEARING : HEARING);
            boolean seen = distance <= SIGHT && self.hasLineOfSight(rival);
            // a glowing rival (everyone glows in the final zone) shows through walls, to players and bots alike
            boolean glowing = !seen && distance <= SIGHT && glows(rival);
            if (heard && !seen) {
                Seen known = memory.get(who);
                if (known == null || now - known.tick() > 40) {
                    glance = rival.getLocation(); // a step behind it: turn towards the sound
                    glanceUntil = now + 25;
                }
            }
            if (seen || heard || glowing) {
                manager.teams().report(id, who, rival, rival.getLocation(), now);
                Seen old = memory.get(who);
                memory.put(who, new Seen(rival, rival.getLocation(), now, distance,
                        old == null ? distance : old.distance(), seen));
                lead = rival.getLocation();
                leadAt = now;
                if (grudges.containsKey(who)) {
                    grudgeLead = rival.getLocation();
                    grudgeLeadAt = now;
                }
            }
        }
        // what its teammates saw or fought (never more than that): known, though not in its own sight
        for (BotTeams.Intel intel : manager.teams().shared(id, now)) {
            UUID who = host.participantOf(intel.entity());
            Seen old = who == null ? null : memory.get(who);
            if (who == null || old != null && old.tick() >= intel.tick() || intel.at().getWorld() != at.getWorld()) {
                continue;
            }
            double distance = intel.at().distance(at);
            memory.put(who, new Seen(intel.entity(), intel.at(), intel.tick(), distance,
                    old == null ? distance : old.distance(), false));
        }
        memory.values().removeIf(seen -> now - seen.tick() > MEMORY_TICKS || seen.entity().isDead()
                || !host.isEnemy(id, seen.entity()));
    }

    BotBrain.Situation situation(long now, BotBody b) {
        Mannequin self = b.body();
        Location at = self.getLocation();
        List<Contact> contacts = new ArrayList<>();
        for (Map.Entry<UUID, Seen> entry : memory.entrySet()) {
            Seen seen = entry.getValue();
            LivingEntity rival = seen.entity();
            boolean visible = seen.tick() == now && seen.inSight();
            double distance = seen.tick() == now ? seen.distance() : seen.at().distance(at);
            contacts.add(new Contact(entry.getKey(), distance, rival.getHealth() + rival.getAbsorptionAmount(),
                    maxHealth(rival), manager.gearOf(rival), visible, manager.fightingOther(entry.getKey(), id, now),
                    seen.distance() < seen.previous() - 0.5, holdsBow(rival), grudges.containsKey(entry.getKey()),
                    seen.tick() == now && webbed(rival)));
        }
        // the nearer of where the border is now and where it will be once it could have walked in (the farther out,
        // the earlier it heads in: terrain makes the way longer than the straight line)
        double off = off(at);
        double margin = aheadHalf(off) - off;
        double zone = Math.hypot(at.getX() - host.borderCenterX(), at.getZ() - host.borderCenterZ());
        BasePlanner.Plan plan = base;
        boolean baseBuilt = relocating || plan != null && built >= plan.placements().size() / 2;
        boolean baseDone = baseFailed || baseFinished() || follower(); // a follower's base is built by its architect
        double baseDistance = plan == null ? Double.POSITIVE_INFINITY
                : Math.hypot(at.getX() - (plan.x() + 0.5), at.getZ() - (plan.z() + 0.5));
        // while it relocates, the brain sees the lost base (built, unsafe): it keeps building the new one
        boolean baseSafe = !relocating && plan != null && baseSafeFor(plan) && !breached(now, plan);
        Decision current = decision;
        return new BotBrain.Situation(host.fight(), Math.max(0, 1 - host.farmingProgress()), self.getHealth(),
                self.getAbsorptionAmount(), maxHealth(self), apples, now >= nextAppleAt, gearScore(),
                loadout.bow() && arrows() > 0, now < fortifyAgainAt ? 0 : blocks(), margin, host.borderShrinking(),
                zone, host.finalHalf(),
                baseBuilt, baseDone, baseDistance, baseSafe, highGround(at),
                current == null ? null : current.behaviour(), current == null ? null : current.target(), contacts,
                host.pressure(), finale(), host.rivals(id, self.getWorld()).size(), idle(now));
    }

    /**
     * How far ahead (ticks) a bot {@code off} blocks from the center looks at the closing border: the time to walk to
     * the final zone at a slow, winding pace (3 blocks a second) plus 10 s, at least 20 s and at most 2 min.
     */
    static long borderLookahead(double off, double finalHalf) {
        double travel = Math.max(0, off - finalHalf);
        return Math.max(400, Math.min(2400, Math.round(travel / 3.0 * 20) + 200));
    }

    /** Blocks from the border's center to {@code at} along the farther axis (the border is a square). */
    private double off(Location at) {
        return Math.max(Math.abs(at.getX() - host.borderCenterX()), Math.abs(at.getZ() - host.borderCenterZ()));
    }

    /**
     * Half side of the border a bot {@code off} blocks out must reckon with: the nearer of where it is now and where it
     * will be once the bot could have walked in ({@link #borderLookahead}). Every border check (the brain's margin,
     * where it heads, where it may hold, which base it may keep) uses this one look ahead, from the moment PvP is on
     * (the wait before the shrink counts too).
     */
    double aheadHalf(double off) {
        return Math.min(host.borderHalfIn(0), host.borderHalfIn(host.fight() ? borderLookahead(off, host.finalHalf())
                : 0));
    }

    /** The border is at or close to its final size: the zone is all there is left. */

    private boolean finale() {
        return host.fight() && host.borderShrinking()
                && host.borderHalfIn(0) <= Math.max(host.finalHalf() * 2, host.finalHalf() + 8);
    }

    /** 0..1: how long it has gone without a fight since PvP came on (1 after {@link #IDLE_TICKS}). */
    double idle(long now) {
        if (!host.fight()) {
            return 0;
        }
        long since = lastFightAt < 0 ? fightTicksSeen(now) : now - lastFightAt;
        return Math.max(0, Math.min(1, (double) since / IDLE_TICKS));
    }

    /** Real ticks since this bot first noticed PvP was on. */
    private long fightTicksSeen(long now) {
        if (fightStartSeen < 0) {
            fightStartSeen = now;
        }
        return now - fightStartSeen;
    }

    /** It dealt or took PvP damage now. */
    void fought(long now) {
        lastFightAt = now;
    }

    static boolean glows(LivingEntity entity) {
        return entity.isGlowing() || entity.hasPotionEffect(PotionEffectType.GLOWING);
    }

    private boolean highGround(Location at) {
        World world = at.getWorld();
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 2;
            sum += world.getHighestBlockYAt(at.getBlockX() + (int) Math.round(Math.cos(angle) * 8),
                    at.getBlockZ() + (int) Math.round(Math.sin(angle) * 8), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        }
        return at.getY() - 1 - sum / 4 >= 3;
    }

    static boolean holdsBow(LivingEntity entity) {
        if (entity.getEquipment() == null) {
            return false;
        }
        Material held = entity.getEquipment().getItemInMainHand().getType();
        return held == Material.BOW || held == Material.CROSSBOW;
    }

    static double maxHealth(LivingEntity entity) {
        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 20.0 : max.getValue();
    }

    // =================================================================== acting

    private void act(long now, BotBody b) {
        Decision d = decision;
        if (d == null) {
            return;
        }
        Mannequin self = b.body();
        LivingEntity target = d.target() == null ? null : known(d.target());
        if (d.behaviour() != Behaviour.FORTIFY && !fortress.isEmpty()) {
            breakOut();
        }
        if (d.behaviour() != Behaviour.KITE && d.behaviour() != Behaviour.ENGAGE && d.behaviour() != Behaviour.AVOID
                && d.behaviour() != Behaviour.DEFEND_BASE && drawStart >= 0) {
            drawStart = -1;
            b.hold(false, loadout.sword());
        }
        Location here = self.getLocation();
        BasePlanner.Plan home = base;
        // behaviours carried out at its base keep the door as it is (backing off to its walls, an ambush from inside)
        boolean atBase = d.behaviour() == Behaviour.DEFEND_BASE || d.behaviour() == Behaviour.BUILD_BASE
                || d.behaviour() == Behaviour.RETREAT_HEAL && baseUsable(here)
                || d.behaviour() == Behaviour.AVOID && target != null && baseUsable(here)
                && traits.weight(Behaviour.DEFEND_BASE) >= 1
                || d.behaviour() == Behaviour.AMBUSH && home != null
                && home.inside(here.getX(), here.getY(), here.getZ());
        if (doorClosed && !atBase) {
            openDoor(); // out it goes: the doorway opens again
        }
        if (!atBase && upInTower(self.getLocation()) && d.behaviour() != Behaviour.AMBUSH) {
            BasePlanner.Plan nest = base;
            if (d.behaviour() == Behaviour.KITE && target != null && nest != null
                    && nestShot(now, b, self, target, nest)) {
                return; // the nest is the best place to shoot from: the slit with a line of fire
            }
            BasePlanner.Plan tower = base;
            if (tower != null) {
                descend(now, b, tower); // down the shaft first, wherever it goes next
            }
            return;
        }
        boolean sneak = false;
        switch (d.behaviour()) {
            case FARM -> {
                farm(now, self);
                defendAgainstMobs(now, self);
            }
            case BUILD_BASE -> {
                if (follower()) {
                    farm(now, self); // its architect builds the team base
                } else {
                    buildBase(now, self);
                }
            }
            case REGROUP -> {
                Location to = manager.teams().rally(id, now);
                if (to == null || to.getWorld() != self.getWorld()) {
                    scout(now, self);
                } else {
                    Location dest = insideSafe(to) ? to : safeFromBorder(to);
                    double distance = dest.distance(self.getLocation());
                    if (distance > 4) {
                        goTo(now, dest, distance > 14 ? Gait.SPRINT : Gait.WALK);
                    } else {
                        stop();
                    }
                }
                eatIfLow(now, self);
                swingAtAnyone(now, self);
            }
            case SCOUT -> {
                Location trail = trail(now, self.getLocation());
                if (trail != null) {
                    goTo(now, trail, Gait.SPRINT);
                } else {
                    scout(now, self);
                }
                swingAtAnyone(now, self);
            }
            case ENGAGE -> {
                if (target == null) {
                    think(now);
                    return;
                }
                sneak = engage(now, b, self, target, where(d.target(), target, now));
            }
            case KITE -> {
                if (target == null) {
                    think(now);
                    return;
                }
                Location at = where(d.target(), target, now);
                kiteMove(now, self, at, target);
                face(self, target.getEyeLocation());
                if (!shoot(now, b, self, target)) {
                    tryMelee(now, self, target);
                }
                eatIfLow(now, self);
            }
            case RETREAT_HEAL -> {
                if (baseUsable(self.getLocation())) {
                    holdBase(now, b, self, target, true); // home to heal behind its walls
                } else {
                    Location threat = nearestThreat();
                    Location mate = manager.teams().active()
                            ? manager.teams().healthyMate(id, self.getLocation()) : null;
                    if (threat != null && mate != null && mate.distance(threat) > 6
                            && mate.distance(self.getLocation()) > 3 && insideSafe(mate)) {
                        goTo(now, mate, Gait.SPRINT); // back to a healthy teammate, not off alone
                        layWeb(now, self, threat);
                    } else if (threat != null) {
                        goTo(now, away(self.getLocation(), threat, 14), Gait.SPRINT);
                        layWeb(now, self, threat);
                    } else {
                        stop();
                    }
                }
                eatIfLow(now, self);
                swingAtAnyone(now, self);
            }
            case FORTIFY -> {
                stop();
                fortify(now, self);
                eatIfLow(now, self);
                swingAtAnyone(now, self);
            }
            case DEFEND_BASE -> {
                if (base == null) {
                    roam(now, self, 20);
                } else {
                    holdBase(now, b, self, target != null ? target : nearestKnown(), false);
                }
                eatIfLow(now, self);
            }
            case HOLD_ZONE -> {
                if (holdSpot == null || !insideSafe(holdSpot)) {
                    double r = host.finalHalf() * 0.5;
                    holdSpot = ground(self.getWorld(), host.borderCenterX() + (random.nextDouble() * 2 - 1) * r,
                            host.borderCenterZ() + (random.nextDouble() * 2 - 1) * r);
                }
                if (holdSpot.distance(self.getLocation()) > 2) {
                    goTo(now, holdSpot, Gait.SPRINT);
                } else {
                    stop();
                }
                eatIfLow(now, self);
                swingAtAnyone(now, self);
            }
            case AMBUSH -> {
                stop();
                if (target != null) {
                    face(self, target.getEyeLocation());
                    if (self.getLocation().distance(target.getLocation()) < 5) {
                        decision = new Decision(Behaviour.ENGAGE, d.target(), d.score());
                    } else if (base != null && base.inside(self.getLocation().getX(), self.getLocation().getY(),
                            self.getLocation().getZ())) {
                        shoot(now, b, self, target); // through the slit while it walks up
                    }
                }
            }
            case THIRD_PARTY -> {
                if (target == null) {
                    think(now);
                    return;
                }
                Location at = where(d.target(), target, now);
                double distance = self.getLocation().distance(at);
                // an assassin stalks close, crouching; an opportunist shadows the fight from 20 to 30 blocks
                boolean stalker = traits.sneaks();
                boolean shadow = traits.personality() == BotPersonality.OPPORTUNIST;
                double near = stalker ? 8 : shadow ? 20 : 14;
                double far = stalker ? 16 : shadow ? 30 : 24;
                if (distance < near) {
                    goTo(now, away(self.getLocation(), at, 6), stalker ? Gait.SNEAK : Gait.WALK);
                    sneak = stalker;
                } else if (distance > far) {
                    boolean creep = stalker && distance < far + 10;
                    goTo(now, at, creep ? Gait.SNEAK : stalker ? Gait.WALK : Gait.SPRINT);
                    sneak = creep;
                } else {
                    stop();
                    face(self, target.getEyeLocation());
                    sneak = traits.sneaks();
                }
                swingAtAnyone(now, self);
            }
            case AVOID -> {
                if (target != null) {
                    Location threat = where(d.target(), target, now);
                    if (baseUsable(self.getLocation()) && traits.weight(Behaviour.DEFEND_BASE) >= 1) {
                        holdBase(now, b, self, target, true); // lure the chaser to its walls
                    } else {
                        goTo(now, away(self.getLocation(), threat, 16), Gait.SPRINT);
                        layWeb(now, self, threat);
                    }
                    if (profile.bowSkill() >= 0.9 || traits.weight(Behaviour.KITE) > 1.2) {
                        shoot(now, b, self, target); // a strong bot or a bowman shoots while backing off
                    }
                }
                eatIfLow(now, self);
                swingAtAnyone(now, self);
            }
            case FLEE_BORDER -> {
                goTo(now, safeFromBorder(self.getLocation()), Gait.RUSH);
                eatIfLow(now, self);
            }
        }
        if (d.behaviour() != Behaviour.FLEE_BORDER) {
            webNearest(now, self);
        }
        if (traits.personality() == BotPersonality.TANK && host.fight()) {
            LivingEntity close = nearestKnown();
            if (close != null && close.getLocation().distance(self.getLocation()) <= 6) {
                raiseShield(now, b, self, close); // a melee enemy close by: the shield goes up
            }
        }
        if (self.isSneaking() != sneak) {
            self.setSneaking(sneak);
        }
        unstick(now, b);
    }

    /** The nearest point well inside where the border is heading (no need to cross the map to the center). */
    private Location safeFromBorder(Location at) {
        double ahead = aheadHalf(off(at));
        double half = Math.max(Math.min(host.finalHalf() * 0.6, ahead), ahead
                - BotBrain.edgeMargin(host.borderShrinking(), host.finalHalf()) - 8);
        double x = clamp(at.getX(), host.borderCenterX() - half, host.borderCenterX() + half);
        double z = clamp(at.getZ(), host.borderCenterZ() - half, host.borderCenterZ() + half);
        return ground(at.getWorld(), x, z);
    }

    /** True if {@code spot} stays inside where the border is heading. */
    private boolean insideSafe(Location spot) {
        BotBody b = body;
        double ahead = aheadHalf(b == null ? off(spot) : Math.max(off(spot), off(b.legs().getLocation())));
        return Math.max(Math.abs(spot.getX() - host.borderCenterX()), Math.abs(spot.getZ() - host.borderCenterZ()))
                < ahead - BotBrain.edgeMargin(host.borderShrinking(), host.finalHalf());
    }

    /** The nearest enemy it knows of. */
    private @Nullable LivingEntity nearestKnown() {
        Seen best = null;
        for (Seen seen : memory.values()) {
            if (best == null || seen.distance() < best.distance()) {
                best = seen;
            }
        }
        return best == null ? null : best.entity();
    }

    // =================================================================== the base in a fight

    /** Its base stands, the border leaves it alone for a while and it is near enough to fall back to. */
    boolean baseUsable(Location at) {
        BasePlanner.Plan plan = base;
        if (plan == null || built < plan.placements().size() * 0.6 || !traits.builds()) {
            return false;
        }
        return baseSafeFor(plan) && Math.hypot(at.getX() - (plan.x() + 0.5), at.getZ() - (plan.z() + 0.5)) < 48;
    }

    /**
     * Falls back to its base and holds it: in through the doorway, the doorway shut behind it while enemies are about
     * (a melee bot, e.g. a tank, holds it open and fights whoever steps in), the bow through the slit facing the
     * enemy, apples and regeneration behind the walls.
     */
    private void holdBase(long now, BotBody b, Mannequin self, @Nullable LivingEntity target, boolean healing) {
        BasePlanner.Plan plan = base;
        if (plan == null) {
            return;
        }
        Location at = self.getLocation();
        World world = self.getWorld();
        int[] inner = plan.inner();
        if (!plan.inside(at.getX(), at.getY(), at.getZ())) {
            int[] door = plan.door().getFirst();
            if (Math.hypot(at.getX() - (door[0] + 0.5), at.getZ() - (door[2] + 0.5)) < 4) {
                clearPassage(plan); // its way in: whatever blocks the doorway goes
            }
            // to the spot outside its way in first (round the walls, off the roof), then in through it
            int[] outer = plan.outer();
            Location front = new Location(world, outer[0] + 0.5, outer[1], outer[2] + 0.5);
            // (the ground outside its doorway may lie a little higher or lower than its floor)
            boolean atFront = Math.hypot(at.getX() - front.getX(), at.getZ() - front.getZ()) < 1.5
                    && Math.abs(at.getY() - front.getY()) < LANE_SLACK;
            boolean onTheWay = Math.abs(at.getY() - (plan.floorY() + 1)) < 1.5 && Math.hypot(at.getX()
                    - (door[0] + 0.5), at.getZ() - (door[2] + 0.5)) < Math.hypot(front.getX() - (door[0] + 0.5),
                    front.getZ() - (door[2] + 0.5)) + 0.5 && !atFront;
            if (walkIn(now, b, plan)) {
                swingAtAnyone(now, self); // on its doorway's lane: straight in
                return;
            }
            if (!atFront && !onTheWay && plan.design() != BasePlanner.Design.BURROW) {
                goTo(now, front, Gait.SPRINT);
            } else {
                goTo(now, new Location(world, inner[0] + 0.5, inner[1], inner[2] + 0.5), Gait.SPRINT);
            }
            swingAtAnyone(now, self);
            return;
        }
        boolean holdDoorway = !loadout.bow() || arrows() <= 0 || traits.weight(Behaviour.ENGAGE) >= 1.1
                && !healing || plan.slits().isEmpty();
        boolean threat = target != null && !target.isDead();
        if (threat) {
            throwWeb(now, self, target); // a trapper webs whoever walks up to its walls
        }
        if (threat && target.getLocation().distance(at) <= REACH + 1.5
                && Math.abs(target.getLocation().getY() - at.getY()) < 2) {
            face(self, target.getEyeLocation());
            tryMelee(now, self, target); // whoever gets in is fought
            goTo(now, target.getLocation(), Gait.WALK);
            raiseShield(now, b, self, target);
            return;
        }
        if (holdDoorway) {
            if (plan.tower() && at.getY() > plan.floorY() + 2.5) {
                descend(now, b, plan);
                return;
            }
            // a survivor hides in its burrow sealed in; the others hold the doorway open and fight whoever comes
            boolean hide = traits.personality() == BotPersonality.SURVIVOR;
            if (doorClosed && !healing && !hide) {
                openDoor();
            }
            if (!doorClosed && (healing && threat || hide)) {
                closeDoor(self);
            }
            Location spot = new Location(world, inner[0] + 0.5, inner[1], inner[2] + 0.5);
            if (spot.distance(at) > 0.8) {
                goTo(now, spot, Gait.WALK);
            } else {
                stop();
            }
            int[] door = plan.door().getFirst();
            face(self, threat ? target.getEyeLocation()
                    : new Location(world, door[0] + 0.5, door[1] + 1.1, door[2] + 0.5));
            if (threat) {
                swingAtAnyone(now, self);
                raiseShield(now, b, self, target);
            }
            return;
        }
        if ((threat || traits.homebody() >= 0.7) && !doorClosed) {
            closeDoor(self); // a homebody keeps its gate shut all fight long
        }
        // the slit facing the enemy (or the zone), and stand right behind it
        int[] slit = null;
        double best = Double.POSITIVE_INFINITY;
        Location look = threat ? target.getLocation()
                : new Location(world, host.borderCenterX(), at.getY(), host.borderCenterZ());
        for (int[] s : plan.slits()) {
            double d = look.distanceSquared(new Location(world, s[0] + 0.5, look.getY(), s[2] + 0.5));
            if (d < best) {
                best = d;
                slit = s;
            }
        }
        if (plan.tower() && threat) {
            int[] clear = clearSlit(now, plan, target); // the nest slit that sees it, if any
            if (clear != null) {
                slit = clear;
            }
        }
        if (slit == null) {
            return;
        }
        if (plan.tower() && at.getY() < slit[4] - 0.5) {
            climb(now, b, plan); // up the shaft to the nest
            return;
        }
        Location spot = new Location(world, slit[3] + 0.5, slit[4], slit[5] + 0.5);
        if (spot.distance(at) > 0.7) {
            goTo(now, spot, Gait.WALK);
        } else {
            stop();
        }
        if (threat) {
            face(self, target.getEyeLocation());
            if (!shoot(now, b, self, target)) {
                swingAtAnyone(now, self);
            }
        } else {
            face(self, new Location(world, slit[0] + 0.5, slit[1] + 0.5, slit[2] + 0.5));
        }
    }

    /**
     * Shoots {@code target} from its tower's nest: walks to the nest slit with a clear line of fire to it (the one
     * nearest the target) and draws. False when no slit has had a line for {@link #NEST_BLIND_TICKS} (the target is
     * under the tower or behind cover): then it comes down to shoot from the ground for a while.
     */
    private boolean nestShot(long now, BotBody b, Mannequin self, LivingEntity target, BasePlanner.Plan plan) {
        if (now < nestBlindUntil || !loadout.bow() || arrows() <= 0) {
            return false;
        }
        int[] slit = clearSlit(now, plan, target);
        if (slit == null) {
            if (nestBlindSince < 0) {
                nestBlindSince = now;
            }
            if (now - nestBlindSince > NEST_BLIND_TICKS) {
                nestBlindUntil = now + 10 * 20;
                nestBlindSince = -1;
                return false;
            }
            stop();
            face(self, target.getEyeLocation());
            return true;
        }
        nestBlindSince = -1;
        Location spot = new Location(self.getWorld(), slit[3] + 0.5, slit[4], slit[5] + 0.5);
        if (spot.distance(self.getLocation()) > 0.7) {
            goTo(now, spot, Gait.WALK);
        } else {
            stop();
        }
        face(self, target.getEyeLocation());
        if (!shoot(now, b, self, target)) {
            swingAtAnyone(now, self);
        }
        return true;
    }

    /**
     * Of its tower's nest slits, the one nearest {@code target} with a clear line from the eyes of whoever stands
     * behind it to the target (looked up twice a second), or null.
     */
    int @Nullable [] clearSlit(long now, BasePlanner.Plan plan, LivingEntity target) {
        if (now < slitCheckAt && slitChosen != null) {
            return slitChosen;
        }
        slitCheckAt = now + 10;
        slitChosen = null;
        World world = host.world();
        Location aim = target.getEyeLocation();
        double best = Double.POSITIVE_INFINITY;
        for (int[] s : plan.slits()) {
            if (s[4] < plan.nestY()) {
                continue;
            }
            Location eye = new Location(world, s[3] + 0.5, s[4] + 1.62, s[5] + 0.5);
            Vector to = aim.toVector().subtract(eye.toVector());
            double length = to.length();
            if (length < 1.0E-3 || length > brain.bowRange()) {
                continue;
            }
            var hit = world.rayTraceBlocks(eye, to.clone().multiply(1 / length), length,
                    FluidCollisionMode.NEVER, true);
            double d = aim.distanceSquared(new Location(world, s[0] + 0.5, aim.getY(), s[2] + 0.5));
            if (hit == null && d < best) {
                best = d;
                slitChosen = s;
            }
        }
        return slitChosen;
    }

    /**
     * Up its tower's shaft like a player: stands in the middle, jumps and puts a block under its feet, again and
     * again until it stands in the nest.
     */
    private void climb(long now, BotBody b, BasePlanner.Plan plan) {
        WanderingTrader legs = b.legs();
        Location at = legs.getLocation();
        int[] cell = shaftCell;
        if (cell != null) {
            if (at.getY() >= cell[1] + 1.05 && b.body().getLocation().getY() >= cell[1] + 1.0
                    || now - shaftSince > 14) {
                placeAny(b.body(), cell[0], cell[1], cell[2], material(BasePlanner.Part.WALL));
                shaftCell = null;
            }
            return;
        }
        Location middle = new Location(at.getWorld(), plan.x() + 0.5, at.getY(), plan.z() + 0.5);
        if (Math.hypot(at.getX() - middle.getX(), at.getZ() - middle.getZ()) > 0.35) {
            goTo(now, middle, Gait.WALK);
            return;
        }
        stop();
        legs.setVelocity(new Vector(0, legs.getVelocity().getY(), 0));
        if (legs.isOnGround() && at.getBlockY() <= plan.nestY()) {
            shaftCell = new int[] {plan.x(), at.getBlockY(), plan.z()};
            shaftSince = now;
            legs.setVelocity(new Vector(0, 0.42, 0));
            face(b.body(), new Location(at.getWorld(), plan.x() + 0.5, at.getBlockY() - 0.5, plan.z() + 0.5));
        }
    }

    /** Down its tower's shaft: from the middle of the nest it digs out the block under its feet, one at a time. */
    private void descend(long now, BotBody b, BasePlanner.Plan plan) {
        WanderingTrader legs = b.legs();
        Location at = legs.getLocation();
        Location middle = new Location(at.getWorld(), plan.x() + 0.5, at.getY(), plan.z() + 0.5);
        if (Math.hypot(at.getX() - middle.getX(), at.getZ() - middle.getZ()) > 0.35) {
            goTo(now, middle, Gait.WALK);
            return;
        }
        stop();
        if (legs.isOnGround() && now >= nextSwing) {
            Block under = at.getWorld().getBlockAt(plan.x(), at.getBlockY() - 1, plan.z());
            if (under.getY() > plan.floorY()) {
                nextSwing = now + digTicks();
                face(b.body(), under.getLocation().add(0.5, 0.5, 0.5));
                dig(under);
            }
        }
    }

    /** It stands up in its tower's nest (or on the shaft): it has to come down before it goes anywhere. */
    private boolean upInTower(Location at) {
        BasePlanner.Plan plan = base;
        return plan != null && plan.tower() && plan.inside(at.getX(), at.getZ()) && at.getY() > plan.floorY() + 2.5
                && at.getY() < plan.roofY();
    }

    /** Shuts the doorway with two of its blocks (never on anyone standing in it). */
    void closeDoor(Mannequin self) {
        BasePlanner.Plan plan = base;
        if (plan == null || doorClosed) {
            return;
        }
        boolean all = true;
        for (int[] cell : plan.door()) {
            Block block = self.getWorld().getBlockAt(cell[0], cell[1], cell[2]);
            if (block.getType().isSolid()) {
                continue;
            }
            if (!place(self, cell[0], cell[1], cell[2], material(BasePlanner.Part.WALL))) {
                all = false;
            }
        }
        doorClosed = all;
        if (all) {
            self.getWorld().playSound(self.getLocation(), Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.8f, 1f);
        }
    }

    /**
     * Digs out whatever stands in its way in, from the spot outside the doorway to the one inside (its own door
     * blocks, a block someone put there); not a burrow's stairs.
     */
    private void clearPassage(BasePlanner.Plan plan) {
        if (plan.design() == BasePlanner.Design.BURROW) {
            openDoor();
            return;
        }
        World world = host.world();
        int ox = Integer.signum(plan.doorX() - plan.x());
        int oz = Integer.signum(plan.doorZ() - plan.z());
        int[] in = plan.inner();
        int x = plan.outer()[0];
        int z = plan.outer()[2];
        for (int step = 0; step < 8 && (x != in[0] || z != in[2]); step++) {
            x -= ox;
            z -= oz;
            for (int y = plan.floorY() + 1; y <= plan.floorY() + 2; y++) {
                Block block = world.getBlockAt(x, y, z);
                if (!block.getType().isAir() && !block.isLiquid()) {
                    dig(block);
                }
            }
        }
        doorClosed = false;
    }

    /** Opens the doorway again (breaks the blocks it shut it with). */
    void openDoor() {
        BasePlanner.Plan plan = base;
        doorClosed = false;
        if (plan == null) {
            return;
        }
        World world = host.world();
        for (int[] cell : plan.door()) {
            Block block = world.getBlockAt(cell[0], cell[1], cell[2]);
            if (placed.containsKey(block) && block.getType() == placed.get(block)) {
                dig(block);
            }
        }
    }

    /** A trapper drops a cobweb behind itself when a chaser is close (a few per fight). */
    private void layWeb(long now, Mannequin self, Location threat) {
        if (!traits.traps() || websLaid >= 16 || now < nextWebAt || threat.distance(self.getLocation()) > 8) {
            return;
        }
        Location at = self.getLocation();
        Vector back = threat.toVector().subtract(at.toVector()).setY(0);
        if (back.lengthSquared() < 1.0E-6) {
            return;
        }
        back.normalize().multiply(1.6);
        Block cell = at.clone().add(back).getBlock();
        if (cell.getRelative(0, -1, 0).getType().isSolid() && place(self, cell.getX(), cell.getY(), cell.getZ(),
                Material.COBWEB)) {
            websLaid++;
            nextWebAt = now + 60;
        }
    }

    /**
     * Where it moves while shooting: a sniper keeps 15 to 30 blocks, backs off up to higher ground and steps side to
     * side between shots; a nomad never stands still, circling its target; anyone else backs off when they come
     * close, closes in when out of range and stands to shoot.
     */
    private void kiteMove(long now, Mannequin self, Location at, LivingEntity target) {
        Location me = self.getLocation();
        double distance = me.distance(at);
        BotPersonality who = traits.personality();
        if (who == BotPersonality.ARCHER && !self.hasLineOfSight(target)) {
            // no line of fire: peek round the cover towards where it was, a little to one side
            Vector to = at.toVector().subtract(me.toVector()).setY(0);
            if (to.lengthSquared() > 1.0E-6) {
                if (now >= strafeAt) {
                    strafeAt = now + 40 + random.nextInt(20);
                    strafe = -strafe;
                }
                Vector peek = to.normalize().rotateAroundY(strafe * Math.PI / 5).multiply(Math.min(8, distance));
                goTo(now, ground(self.getWorld(), me.getX() + peek.getX(), me.getZ() + peek.getZ()), Gait.SPRINT);
                return;
            }
        }
        if (who == BotPersonality.ARCHER) {
            if (distance < 15) {
                goTo(now, higherAway(me, at, 10), distance < 8 ? Gait.SPRINT : Gait.WALK);
            } else if (distance > Math.min(30, brain.bowRange() * 0.9)) {
                goTo(now, at, Gait.SPRINT);
            } else {
                goTo(now, sideStep(now, me, at, 3), Gait.WALK);
            }
            return;
        }
        if (who == BotPersonality.NOMAD) {
            double want = Math.max(12, brain.bowRange() * 0.6);
            Location orbit = sideStep(now, me, at, 6);
            Vector radial = orbit.toVector().subtract(at.toVector()).setY(0);
            if (radial.lengthSquared() > 1.0E-6) {
                orbit = at.clone().add(radial.normalize().multiply(want));
            }
            goTo(now, ground(self.getWorld(), orbit.getX(), orbit.getZ()), Gait.SPRINT); // hit and run, never still
            return;
        }
        if (distance < 9) {
            goTo(now, away(me, at, 12), Gait.SPRINT);
        } else if (distance > brain.bowRange() * 0.85) {
            goTo(now, at, Gait.SPRINT);
        } else {
            stop();
        }
    }

    /** A spot {@code step} blocks to the side of the line to {@code at}, the side switching every second or two. */
    private Location sideStep(long now, Location me, Location at, double step) {
        if (now >= strafeAt) {
            strafeAt = now + 25 + random.nextInt(20);
            strafe = -strafe;
        }
        Vector to = at.toVector().subtract(me.toVector()).setY(0);
        if (to.lengthSquared() < 1.0E-6) {
            return me;
        }
        Vector side = new Vector(-to.getZ(), 0, to.getX()).normalize().multiply(step * strafe);
        return me.clone().add(side);
    }

    /** Of a few spots {@code distance} blocks away from {@code threat}, the highest (a sniper wants the high ground). */
    private Location higherAway(Location from, Location threat, double distance) {
        Vector out = from.toVector().subtract(threat.toVector()).setY(0);
        if (out.lengthSquared() < 1.0E-6) {
            out = new Vector(1, 0, 0);
        }
        out.normalize();
        Location best = from;
        double bestY = Double.NEGATIVE_INFINITY;
        for (int turn = -2; turn <= 2; turn++) {
            Vector dir = out.clone().rotateAroundY(turn * Math.PI / 8).multiply(distance);
            Location spot = ground(from.getWorld(), from.getX() + dir.getX(), from.getZ() + dir.getZ());
            if (spot.getY() > bestY + 0.5) {
                best = spot;
                bestY = spot.getY();
            }
        }
        return best;
    }

    /** True if {@code entity} stands in a cobweb. */
    static boolean webbed(LivingEntity entity) {
        Location at = entity.getLocation();
        return at.getBlock().getType() == Material.COBWEB
                || at.clone().add(0, 1, 0).getBlock().getType() == Material.COBWEB;
    }

    /**
     * A trapper in a fight throws a cobweb on the feet of the nearest enemy it sees when that enemy is close
     * ({@link #WEB_CLOSE}) or coming at it within {@link #WEB_THROW_REACH}, once every {@link #WEB_THROW_TICKS}
     * whatever it is doing (holding its walls, backing off, fighting): stuck, they are easy to hit or to get away from.
     */
    private void webNearest(long now, Mannequin self) {
        if (!traits.traps() || !host.fight() || now < nextThrowAt) {
            return;
        }
        Seen best = null;
        for (Seen seen : memory.values()) {
            if (now - seen.tick() <= 20 && seen.inSight() && (best == null || seen.distance() < best.distance())) {
                best = seen;
            }
        }
        if (best == null || best.entity().isDead()) {
            return;
        }
        double distance = best.entity().getLocation().distance(self.getLocation());
        boolean coming = best.distance() < best.previous() - 0.2;
        if (distance <= WEB_CLOSE || coming && distance <= WEB_THROW_REACH) {
            throwWeb(now, self, best.entity());
        }
    }

    /**
     * Throws a cobweb on {@code target}'s feet (a player places one there): stuck, they are easy to hit. Into air or
     * grass-like plants only, in the match world, never on someone already stuck; within {@link #WEB_THROW_REACH} and
     * in sight.
     */
    private void throwWeb(long now, Mannequin self, LivingEntity target) {
        if (!traits.traps() || now < nextThrowAt || target.getLocation().distance(self.getLocation()) > WEB_THROW_REACH
                || blocks() <= 0 || webbed(target) || !self.hasLineOfSight(target)) {
            return;
        }
        Block feet = target.getLocation().getBlock();
        Material was = feet.getType();
        boolean free = was.isAir() || feet.isReplaceable() && !feet.isLiquid() && !Hazards.hazard(was);
        if (!free || !feet.getWorld().equals(host.world())
                || !host.arena().contains(feet.getLocation().add(0.5, 0, 0.5))) {
            return;
        }
        feet.setType(Material.COBWEB);
        placed.put(feet, Material.COBWEB);
        blocksUsed++;
        face(self, feet.getLocation().add(0.5, 0.5, 0.5));
        self.swingMainHand();
        feet.getWorld().playSound(feet.getLocation(), Sound.BLOCK_WOOL_PLACE, 0.8f, 1f);
        nextThrowAt = now + WEB_THROW_TICKS;
        websThrown++;
    }

    /**
     * A tank holds its shield up while it walks into a fight or holds a doorway (it shows, and frontal hits are
     * blocked); it lowers it to swing.
     */
    private void raiseShield(long now, BotBody b, Mannequin self, @Nullable LivingEntity target) {
        if (!loadout.shield() || traits.personality() != BotPersonality.TANK || target == null) {
            return;
        }
        if (!self.isHandRaised()) {
            self.startUsingItem(EquipmentSlot.OFF_HAND);
        }
        shieldUpUntil = now + 10;
    }

    /**
     * A trapper whose base is closed spends the rest of the farming covering its surroundings with traps, standing
     * at its doorway: cobwebs and hidden pits (two or three deep under a tuft of grass) scattered over every approach
     * out to {@link #trapRadius}, never on the lane to its own door, until it has laid {@link #trapCap}. Other bots
     * know where the pits are and path around them (they see the webs).
     */
    private void layTraps(long now, Mannequin self, BasePlanner.Plan home) {
        int[] door = home.outer();
        Location stand = new Location(self.getWorld(), door[0] + 0.5, door[1], door[2] + 0.5);
        if (stand.distance(self.getLocation()) > 1.5) {
            goTo(now, stand, Gait.WALK);
            return;
        }
        stop();
        buildCredit += buildRate() * Math.max(1, host.timeScale()) / 4 / 20.0;
        if (buildCredit < 1 || trapsLaid >= trapCap() || blocks() <= BLOCK_RESERVE) {
            buildCredit = Math.min(buildCredit, 1);
            return;
        }
        buildCredit--;
        World world = self.getWorld();
        int ox = Integer.signum(home.doorX() - home.x());
        int oz = Integer.signum(home.doorZ() - home.z());
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = home.half() + 2 + random.nextDouble() * (trapRadius() - home.half() - 2);
            int x = home.x() + (int) Math.round(Math.cos(angle) * r);
            int z = home.z() + (int) Math.round(Math.sin(angle) * r);
            int along = (x - home.x()) * ox + (z - home.z()) * oz;
            int across = (x - home.x()) * -oz + (z - home.z()) * ox;
            if (along > home.half() && Math.abs(across) <= 1 || Math.max(Math.abs(x - home.x()),
                    Math.abs(z - home.z())) <= home.half() + 1 || !host.arena().contains(new Location(world, x, 0, z))) {
                continue; // its own way in, its walls
            }
            Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block above = top.getRelative(0, 1, 0);
            if (!top.getType().isSolid() || Hazards.hazard(top) || Hazards.UNBREAKABLE.contains(top.getType())
                    || !(above.getType().isAir() || above.isReplaceable() && !above.isLiquid())) {
                continue;
            }
            face(self, top.getLocation().add(0.5, 1, 0.5));
            if (layTrap(self, top, above)) {
                trapsLaid++;
                return;
            }
        }
    }

    /** The kinds of trap a trapper lays around its base, with how often (weights). */
    enum Trap {
        /** A pit two or three deep under a floating tuft of grass, berry bushes at the bottom (powder snow when cold). */
        PIT(32),
        /** A berry bush: scratches and slows. */
        BUSH(20),
        /** A honey block in the ground: sticks to the feet. */
        HONEY(14),
        /** A pressure plate with a dispenser of arrows beside it, aimed across it. */
        ARROWS(14),
        /** A cobweb. */
        WEB(20);

        final int weight;

        Trap(int weight) {
            this.weight = weight;
        }

        static Trap pick(Random random) {
            int total = 0;
            for (Trap t : values()) {
                total += t.weight;
            }
            int roll = random.nextInt(total);
            for (Trap t : values()) {
                roll -= t.weight;
                if (roll < 0) {
                    return t;
                }
            }
            return WEB;
        }
    }

    /**
     * Lays one trap on the ground block {@code top} (with {@code above} the air over it); false if it could not.
     * Every cell of it is marked for the other bots ({@link BotManager#markTrap}): they path around it.
     */
    private boolean layTrap(Mannequin self, Block top, Block above) {
        World world = self.getWorld();
        Trap trap = Trap.pick(random);
        switch (trap) {
            case WEB -> {
                return place(self, above.getX(), above.getY(), above.getZ(), Material.COBWEB);
            }
            case BUSH -> {
                return place(self, above.getX(), above.getY(), above.getZ(), Material.SWEET_BERRY_BUSH);
            }
            case HONEY -> {
                if (Hazards.UNBREAKABLE.contains(top.getType()) || Tag.LOGS.isTagged(top.getType())) {
                    return false;
                }
                top.setType(Material.HONEY_BLOCK);
                placed.put(top, Material.HONEY_BLOCK);
                blocksUsed++;
                manager.markTrap(top);
                return true;
            }
            case ARROWS -> {
                // the plate on the ground, the dispenser beside it facing across it
                BlockFace[] sides = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
                BlockFace side = sides[random.nextInt(sides.length)];
                Block box = above.getRelative(side);
                if (!above.getType().isAir() || !box.getType().isAir() || !box.getRelative(BlockFace.DOWN).getType()
                        .isSolid() || blocks() < 2 || !host.arena().contains(box.getLocation())) {
                    return false;
                }
                above.setType(Material.STONE_PRESSURE_PLATE);
                placed.put(above, Material.STONE_PRESSURE_PLATE);
                box.setType(Material.DISPENSER);
                placed.put(box, Material.DISPENSER);
                if (box.getBlockData() instanceof Directional facing) {
                    facing.setFacing(side.getOppositeFace());
                    box.setBlockData(facing);
                }
                if (box.getState() instanceof org.bukkit.block.Dispenser dispenser) {
                    dispenser.getInventory().addItem(ItemStack.of(Material.ARROW, 6));
                }
                blocksUsed += 2;
                manager.markTrap(above);
                manager.markTrap(box);
                return true;
            }
            case PIT -> {
                int depth = 2 + random.nextInt(2);
                List<Block> cells = new ArrayList<>();
                for (int d = 0; d < depth; d++) {
                    Block cell = top.getRelative(0, -d, 0);
                    if (Hazards.UNBREAKABLE.contains(cell.getType()) || cell.isLiquid()) {
                        break;
                    }
                    cells.add(cell);
                }
                if (cells.size() < 2) {
                    return false;
                }
                for (Block cell : cells) {
                    dig(cell);
                    manager.markTrap(cell);
                }
                Block bottom = cells.getLast();
                boolean cold = world.getBlockAt(top.getX(), top.getY(), top.getZ()).getTemperature() < 0.15;
                if (cold) {
                    bottom.setType(Material.POWDER_SNOW); // freezes for a moment, never deep enough to kill
                } else if (bottom.getRelative(BlockFace.DOWN).getType().isSolid()) {
                    bottom.setType(Material.SWEET_BERRY_BUSH, false);
                    ripen(bottom);
                }
                if (above.isReplaceable() && !above.getType().isAir()) {
                    dig(above);
                }
                top.setType(Material.SHORT_GRASS, false); // no physics: it floats over the pit
                blocksUsed++;
                return true;
            }
        }
        return false;
    }

    /** A berry bush grown far enough to scratch (a fresh one only slows). */
    private static void ripen(Block bush) {
        if (bush.getBlockData() instanceof Ageable age) {
            age.setAge(Math.min(2, age.getMaximumAge()));
            bush.setBlockData(age, false);
        }
    }

    /** How far out a trapper's field reaches: farther the higher the difficulty (8 easy … 14 hell). */
    int trapRadius() {
        return 8 + Math.max(0, wantedSize(BasePlanner.Design.TRAPS) - 5);
    }

    /** How many traps a trapper lays around its base after closing it (40 easy … 130 hell). */
    int trapCap() {
        return 40 + 15 * Math.max(0, wantedSize(BasePlanner.Design.TRAPS) - 5);
    }

    /** Fights {@code target}; true if it creeps up on them (an assassin crouching in from behind). */
    private boolean engage(long now, BotBody b, Mannequin self, LivingEntity target, Location at) {
        double distance = self.getLocation().distance(at);
        face(self, at.clone().add(0, target.getEyeHeight(), 0));
        if (traits.sneaks() && distance > REACH + 1.5 && distance < 16 && !manager.facing(target, self)) {
            // an assassin comes in from behind, crouching: its steps are heard only from close by
            Vector back = target.getLocation().getDirection().setY(0);
            Location behind = back.lengthSquared() < 1.0E-6 ? at
                    : at.clone().subtract(back.normalize().multiply(Math.min(3, distance * 0.4)));
            goTo(now, behind, distance < 8 ? Gait.SNEAK : Gait.WALK);
            return true;
        }
        BotPersonality who = traits.personality();
        throwWeb(now, self, target);
        if (distance > REACH + 0.6) {
            if (who == BotPersonality.TANK) {
                goTo(now, at, distance > 10 ? Gait.SPRINT : Gait.WALK); // slow and steady, shield up
                raiseShield(now, b, self, target);
                return false;
            }
            goTo(now, at, who == BotPersonality.BERSERKER || who == BotPersonality.HUNTER ? Gait.RUSH : Gait.SPRINT);
            if (who != BotPersonality.BERSERKER && distance > 10 && distance <= brain.bowRange()
                    && shoot(now, b, self, target)) {
                return false;
            }
        } else if (who == BotPersonality.BERSERKER) {
            // no footwork: straight at them, jump-crit after jump-crit
            if (drawStart >= 0) {
                drawStart = -1;
                b.hold(false, loadout.sword());
            }
            goTo(now, target.getLocation(), Gait.RUSH);
            tryMelee(now, self, target);
        } else {
            if (drawStart >= 0) {
                drawStart = -1;
                b.hold(false, loadout.sword());
            }
            if (now >= strafeAt) {
                strafeAt = now + 15 + random.nextInt(20);
                if (random.nextDouble() < profile.strafeChance()) {
                    strafe = -strafe;
                }
            }
            Vector toTarget = target.getLocation().toVector().subtract(self.getLocation().toVector()).setY(0);
            if (toTarget.lengthSquared() > 1.0E-6 && random.nextDouble() < profile.strafeChance()) {
                Vector side = new Vector(-toTarget.getZ(), 0, toTarget.getX()).normalize().multiply(1.5 * strafe);
                goTo(now, target.getLocation().add(side), Gait.WALK);
            } else {
                goTo(now, target.getLocation(), Gait.WALK);
            }
            tryMelee(now, self, target);
            raiseShield(now, b, self, target);
        }
        eatIfLow(now, self);
        return false;
    }

    // =================================================================== fighting

    /** The damage of a swing: the sword plus the plugin's attribute bonuses (augments), times the scale. */
    double swingDamage() {
        BotBody b = body;
        double damage = loadout.sword().damage();
        if (b != null) {
            AttributeInstance attack = b.body().getAttribute(Attribute.ATTACK_DAMAGE);
            if (attack != null) {
                double scalar = 1;
                for (AttributeModifier modifier : attack.getModifiers()) {
                    if (!manager.ours(modifier)) {
                        continue;
                    }
                    switch (modifier.getOperation()) {
                        case ADD_NUMBER -> damage += modifier.getAmount();
                        case ADD_SCALAR, MULTIPLY_SCALAR_1 -> scalar *= 1 + modifier.getAmount();
                    }
                }
                damage *= scalar;
            }
        }
        return Math.max(0.5, damage) * profile.damageScale();
    }

    private double bonus(Attribute attribute) {
        BotBody b = body;
        if (b == null) {
            return 0;
        }
        AttributeInstance instance = b.body().getAttribute(attribute);
        if (instance == null) {
            return 0;
        }
        double sum = 0;
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (manager.ours(modifier) && modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                sum += modifier.getAmount();
            }
        }
        return sum;
    }

    /** Swings at {@code target} when in reach and the sword is charged; true if it swung. */
    boolean tryMelee(long now, Mannequin self, LivingEntity target) {
        double reach = REACH + target.getWidth() / 2;
        if (now < nextSwing || target.isDead() || self.getLocation().distance(target.getLocation()) > reach + 0.3
                || !self.hasLineOfSight(target)) {
            return false;
        }
        face(self, target.getEyeLocation());
        if (!aimedAt(self, target)) {
            return false; // still turning towards them
        }
        nextSwing = now + profile.attackTicks();
        self.swingMainHand();
        double hitChance = Math.max(0.5, Math.min(1, 1 - profile.aimError() / 25.0));
        if (random.nextDouble() >= hitChance) {
            self.getWorld().playSound(self.getLocation(), Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.6f, 1f);
            return true;
        }
        boolean crit = random.nextDouble() < (traits.personality() == BotPersonality.BERSERKER
                ? Math.max(0.95, profile.critChance()) : profile.critChance());
        if (self.isHandRaised()) {
            self.clearActiveItem(); // the shield comes down to swing
        }
        BotBody b = body;
        if (crit && b != null) {
            b.legs().setVelocity(b.legs().getVelocity().setY(0.36));
        }
        double damage = swingDamage() * (crit ? 1.5 : 1.0);
        target.damage(damage, DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(self)
                .withDirectEntity(self).build());
        if (crit) {
            self.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, target.getHeight() * 0.6, 0),
                    12, 0.3, 0.3, 0.3, 0.1);
            self.getWorld().playSound(self.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1f);
        }
        if (profile.strafeChance() >= 0.7 && !target.isDead()) {
            // a sprint (w-tap) hit knocks back further
            Vector push = target.getLocation().toVector().subtract(self.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 1.0E-6) {
                target.setVelocity(target.getVelocity().add(push.normalize().multiply(0.3).setY(0.05)));
            }
        }
        return true;
    }

    /** Draws and looses an arrow at {@code target}; true while busy with the bow. */
    boolean shoot(long now, BotBody b, Mannequin self, LivingEntity target) {
        if (!loadout.bow() || arrows() <= 0 || brain.bowRange() <= 0 || now < nextShot) {
            return false;
        }
        double distance = self.getLocation().distance(target.getLocation());
        if (distance < 6 || distance > brain.bowRange() || !self.hasLineOfSight(target)) {
            if (drawStart >= 0) {
                drawStart = -1;
                b.hold(false, loadout.sword());
            }
            return false;
        }
        if (drawStart < 0) {
            drawStart = now;
            b.hold(true, loadout.sword());
        }
        Location eye = self.getEyeLocation();
        Location aim = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        Vector velocity = target.getVelocity();
        double[] launch = Ballistics.launch(new double[] {eye.getX(), eye.getY(), eye.getZ()},
                new double[] {aim.getX(), aim.getY(), aim.getZ()},
                new double[] {velocity.getX(), velocity.getY(), velocity.getZ()}, profile.bowSkill());
        faceAlong(launch); // the camera follows the lead and the arc while drawing
        if (now - drawStart < DRAW_TICKS) {
            return true;
        }
        launch = Ballistics.scatter(launch, profile.aimError() * 0.6, random);
        Arrow arrow = self.launchProjectile(Arrow.class, new Vector(launch[0], launch[1], launch[2]));
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setShooter(self);
        manager.session().track(arrow);
        self.getWorld().playSound(self.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1f);
        arrowsUsed++;
        drawStart = -1;
        nextShot = now + 4;
        b.hold(false, loadout.sword());
        return true;
    }

    private void eatIfLow(long now, Mannequin self) {
        if (apples <= 0 || now < nextAppleAt || self.getHealth() > brain.healAt()) {
            return;
        }
        apples--;
        nextAppleAt = now + APPLE_TICKS + 40;
        eatLookUntil = now + APPLE_TICKS / 2;
        self.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1, false, true, true));
        self.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0, false, true, true));
        self.getWorld().playSound(self.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.8f, 1f);
        self.getWorld().playSound(self.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.5f, 1f);
    }

    private void swingAtAnyone(long now, Mannequin self) {
        for (Seen seen : memory.values()) {
            if (seen.tick() == now || seen.entity().getLocation().distance(self.getLocation()) <= REACH + 0.5) {
                if (tryMelee(now, self, seen.entity())) {
                    return;
                }
            }
        }
    }

    private void defendAgainstMobs(long now, Mannequin self) {
        for (Entity nearby : self.getNearbyEntities(REACH, 2, REACH)) {
            if (nearby instanceof Enemy && nearby instanceof LivingEntity mob && !(nearby instanceof WanderingTrader)
                    && !manager.isLegs(nearby)) {
                face(self, mob.getEyeLocation());
                tryMelee(now, self, mob);
                return;
            }
        }
    }

    /** Called by the manager before a frontal melee hit lands: true if the shield took it. */
    boolean block(long now, @Nullable Entity attacker) {
        BotBody b = body;
        boolean raised = b != null && b.body().isHandRaised();
        if (b == null || !loadout.shield() || attacker == null || now < nextShieldAt || random.nextDouble()
                >= (raised ? 0.95 : Math.min(0.9, profile.shieldChance() + traits.shieldBonus()))) {
            return false;
        }
        Vector facing = b.body().getLocation().getDirection().setY(0);
        Vector toAttacker = attacker.getLocation().toVector().subtract(b.body().getLocation().toVector()).setY(0);
        if (facing.lengthSquared() < 1.0E-6 || toAttacker.lengthSquared() < 1.0E-6
                || facing.normalize().dot(toAttacker.normalize()) < 0.3) {
            return false;
        }
        nextShieldAt = now + 30;
        b.body().getWorld().playSound(b.body().getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
        return true;
    }

    // =================================================================== building

    /**
     * Builds its base a few blocks a second from the middle of it, in the plan's order (floor and doorway, walls,
     * roof: closed first, then the furniture, the lookout and traps). A block goes on only once it is really there (or
     * the spot is already solid): out of blocks it waits for the next farmed batch instead of leaving a hole, and a
     * spot someone stands in is passed over and mended afterwards ({@link #mend}). With no site found it roams and
     * looks again a few seconds later; a site it cannot reach in {@link #SITE_WALK_TICKS} is given up for another. In
     * the fight, a base the border will swallow is given up and a new one is built nearer the middle.
     */
    private void buildBase(long now, Mannequin self) {
        BasePlanner.Plan plan = base;
        if (plan != null && host.fight() && !baseSafeFor(plan)) {
            openDoor();
            base = null;
            plan = null;
            built = 0;
            mending.clear();
            baseFailed = false;
            relocating = true;
        }
        if (plan == null) {
            if (now < planAgainAt) {
                roam(now, self, 24); // somewhere else may have room
                return;
            }
            plan = planBase(self);
            if (plan == null) {
                if (host.fight()) {
                    baseFailed = true;
                    think(now);
                    return;
                }
                planAgainAt = now + PLAN_AGAIN_TICKS;
                roam(now, self, 24);
                return;
            }
            siteWalk = 0;
            siteBest = Double.POSITIVE_INFINITY;
        }
        // a burrow is dug from its staircase; the rest is built from the middle of the room
        int[] stand = built < plan.fromOutside() ? plan.outer() : new int[] {plan.x(), plan.floorY() + 1, plan.z()};
        Location center = new Location(self.getWorld(), stand[0] + 0.5, stand[1], stand[2] + 0.5);
        Location at = self.getLocation();
        double off = Math.hypot(at.getX() - center.getX(), at.getZ() - center.getZ());
        if (off > 2.5 || Math.abs(at.getY() - center.getY()) > 2.5) {
            if (off < siteBest - SITE_PROGRESS) {
                siteBest = off; // getting closer over rough ground: no giving up yet
                siteWalk = 0;
            }
            if (++siteWalk > SITE_WALK_TICKS && built < plan.closure()) {
                giveUpSite(now, plan); // it cannot get there: another site
                return;
            }
            goTo(now, center, Gait.SPRINT);
            return;
        }
        siteWalk = 0;
        if (off > 0.5) {
            goTo(now, center, Gait.WALK); // the middle: never in the way of its own walls
        } else {
            stop();
        }
        buildCredit += buildRate() * Math.max(1, host.timeScale()) / 20.0;
        while (buildCredit >= 1 && built < plan.placements().size()) {
            if (built >= plan.closure() && blocks() <= BLOCK_RESERVE) {
                break; // closed: the extras wait for more blocks, a few are kept for the door and the way
            }
            BasePlanner.Placement next = plan.placements().get(built);
            Block block = self.getWorld().getBlockAt(next.x(), next.y(), next.z());
            if (next.part() == BasePlanner.Part.CLEAR) {
                built++;
                if (!block.getType().isAir() && host.arena().contains(block.getLocation().add(0.5, 0, 0.5))) {
                    buildCredit--;
                    face(self, block.getLocation().add(0.5, 0.5, 0.5));
                    dig(block); // levelling the floor, opening the doorway
                }
                continue;
            }
            Material want = material(next.part(), next.x(), next.y(), next.z());
            if (closed(block) || block.getType() == want) {
                built++; // already closed (terrain, a tree trunk, its own block)
                continue;
            }
            if (blocks() <= 0) {
                buildCredit = Math.min(buildCredit, 1);
                break; // out of blocks: wait for more instead of leaving a hole
            }
            buildCredit--;
            if (Hazards.hazard(block.getType())) {
                clearHazard(block);
            }
            if (Tag.LEAVES.isTagged(block.getType())) {
                dig(block); // leaves are no wall
            }
            if (place(self, next.x(), next.y(), next.z(), want) || placeAny(self, next.x(), next.y(), next.z(), want)) {
                built++;
                buildBlockedTicks = 0;
                face(self, new Location(self.getWorld(), next.x() + 0.5, next.y() + 0.5, next.z() + 0.5));
            } else if (shoo(next.x(), next.y(), next.z()) && ++buildBlockedTicks > 20) {
                built++; // someone keeps standing there: on with the rest, mended afterwards
                buildBlockedTicks = 0;
            } else {
                break;
            }
        }
        if (built >= plan.placements().size()) {
            relocating = false;
            mend(self, plan);
        } else {
            BasePlanner.Placement next = plan.placements().get(built); // eyes on where the next block goes
            face(self, new Location(self.getWorld(), next.x() + 0.5, next.y() + 0.5, next.z() + 0.5));
        }
    }

    /**
     * Pushes an animal or a monster (not a player, not a bot) out of a cell it wants to build in, away from its base
     * (a player would shove it or swing at it). Always true (for chaining).
     */
    private boolean shoo(int x, int y, int z) {
        BasePlanner.Plan plan = base;
        BoundingBox cell = new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
        for (Entity entity : host.world().getNearbyEntities(cell.clone().expand(0.5))) {
            if (entity instanceof LivingEntity && !(entity instanceof Player) && !manager.isLegs(entity)
                    && !manager.isBody(entity) && host.participantOf(entity) == null) {
                Vector out = plan == null ? new Vector(1, 0, 0)
                        : new Vector(x + 0.5 - (plan.x() + 0.5), 0, z + 0.5 - (plan.z() + 0.5));
                if (out.lengthSquared() < 1.0E-6) {
                    out = new Vector(1, 0, 0);
                }
                entity.setVelocity(out.normalize().multiply(0.6).setY(0.3));
            }
        }
        return true;
    }

    /** True if {@code block} is part of its own base's walls, roof or floor. */
    private boolean ownShell(Block block) {
        BasePlanner.Plan plan = base;
        if (plan == null) {
            return false;
        }
        if (shellOf != plan) {
            shellOf = plan;
            shellCells.clear();
            for (BasePlanner.Placement p : plan.shell()) {
                shellCells.add(BlockKey.of(p.x(), p.y(), p.z()));
            }
        }
        return shellCells.contains(BlockKey.of(block.getX(), block.getY(), block.getZ()));
    }

    /** A block position as one number. */
    private static final class BlockKey {
        static long of(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }
    }

    /** True if {@code block} closes its cell like a wall: solid, harmless, no leaves. */
    private static boolean closed(Block block) {
        Material type = block.getType();
        return type.isSolid() && !Hazards.hazard(type) && !Tag.LEAVES.isTagged(type);
    }

    /**
     * Mends the holes in its finished base's shell (a block passed over because someone stood there, or broken
     * since), a few blocks a second like building; a hole it cannot fill after a few tries is left.
     */
    private void mend(Mannequin self, BasePlanner.Plan plan) {
        Iterator<BasePlanner.Placement> it = mending.iterator();
        while (buildCredit >= 1 && it.hasNext()) {
            BasePlanner.Placement hole = it.next();
            Block block = self.getWorld().getBlockAt(hole.x(), hole.y(), hole.z());
            if (closed(block)) {
                it.remove();
                continue;
            }
            if (blocks() <= 0) {
                break;
            }
            buildCredit--;
            if (Tag.LEAVES.isTagged(block.getType())) {
                dig(block);
            }
            Material want = material(hole.part(), hole.x(), hole.y(), hole.z());
            face(self, new Location(self.getWorld(), hole.x() + 0.5, hole.y() + 0.5, hole.z() + 0.5));
            if (place(self, hole.x(), hole.y(), hole.z(), want) || placeAny(self, hole.x(), hole.y(), hole.z(), want)) {
                it.remove();
            } else {
                shoo(hole.x(), hole.y(), hole.z());
            }
        }
        buildCredit = Math.min(buildCredit, 1);
    }

    /**
     * Looks over its finished base for holes in the walls, roof and floor while farming (every two seconds): any found
     * are queued for {@link #mend}, so the brain sends it back to finish the base; a hole found open
     * {@link #MEND_TRIES} times (someone keeps standing in it) is left.
     */
    private void inspectBase(long now, Mannequin self) {
        BasePlanner.Plan plan = base;
        if (plan == null || host.fight() || built < plan.placements().size() || now < nextInspect) {
            return;
        }
        nextInspect = now + 40;
        for (BasePlanner.Placement p : plan.shell()) {
            Block block = self.getWorld().getBlockAt(p.x(), p.y(), p.z());
            if (closed(block)) {
                continue;
            }
            if (mendTries.merge(block, 1, Integer::sum) > MEND_TRIES) {
                mending.remove(p);
            } else if (!mending.contains(p)) {
                mending.add(p);
            }
        }
    }

    /**
     * True if its base is broken open in the fight (more than {@link #BREACH_HOLES} holes in its walls and roof): it
     * is no place to hold any more. Looked at every two seconds.
     */
    private boolean breached(long now, BasePlanner.Plan plan) {
        if (!host.fight()) {
            return false;
        }
        if (now >= nextBreachCheck) {
            nextBreachCheck = now + 40;
            int holes = 0;
            World world = host.world();
            for (BasePlanner.Placement p : plan.shell()) {
                if (p.part() != BasePlanner.Part.FLOOR && !closed(world.getBlockAt(p.x(), p.y(), p.z()))) {
                    holes++;
                }
            }
            wasBreached = holes > BREACH_HOLES;
        }
        return wasBreached;
    }

    /** Its base is closed and whole (nothing left to place or mend). */
    private boolean baseFinished() {
        BasePlanner.Plan plan = base;
        return plan != null && mending.isEmpty() && (built >= plan.placements().size()
                || built >= plan.closure() && blocks() <= BLOCK_RESERVE);
    }

    /** Gives up a site it could not reach and looks for another one right away (never that one again). */
    private void giveUpSite(long now, BasePlanner.Plan plan) {
        badSites.add(new int[] {plan.x(), plan.z()});
        base = null;
        built = 0;
        mending.clear();
        siteWalk = 0;
        planAgainAt = now;
        if (recovering()) {
            endRecovery(now);
        }
    }

    /** The border leaves {@code plan} alone for the next half minute. */
    private boolean baseSafeFor(BasePlanner.Plan plan) {
        double off = Math.max(Math.abs(plan.x() - host.borderCenterX()), Math.abs(plan.z() - host.borderCenterZ()));
        // the border must leave it alone while it could still get out and walk in (its walls cost time too)
        long ahead = host.fight() ? Math.max(BASE_SAFE_TICKS, borderLookahead(off, host.finalHalf()) + BASE_EXIT_TICKS)
                : BASE_SAFE_TICKS;
        return Math.min(host.borderHalfIn(0), host.borderHalfIn(ahead)) - off > plan.half() + 2
                + (host.fight() ? BotBrain.edgeMargin(host.borderShrinking(), host.finalHalf()) : 0);
    }

    /** The main block of {@code part} (doorway blocks, pillars and bridges it puts up on the way). */
    private Material material(BasePlanner.Part part) {
        return material(part, 0, 0, 0);
    }

    /** The block of {@code part} at {@code (x, y, z)}: its palette's, the mixed-in ones here and there. */
    private Material material(BasePlanner.Part part, int x, int y, int z) {
        BasePlanner.Palette p = palette != null ? palette : BasePlanner.Palette.forBiome(null);
        String options = switch (part) {
            case WALL, CLEAR -> p.wall();
            case FLOOR -> p.floor();
            case TRIM -> p.trim();
            case FENCE -> p.fence();
            case PILLAR -> p.pillar();
            case ROOF -> p.roof();
            case CRENEL -> p.crenel();
            case WINDOW -> p.window();
            case LIGHT -> p.light();
            case CHEST -> "CHEST";
            case CRAFTING_TABLE -> "CRAFTING_TABLE";
            case COBWEB -> "COBWEB";
            case BUSH -> "SWEET_BERRY_BUSH";
        };
        boolean main = x == 0 && y == 0 && z == 0;
        Material material = Material.matchMaterial(main ? options.split("\\|")[0]
                : BasePlanner.Palette.pick(options, x, y, z));
        return material != null && material.isBlock() ? material : Material.COBBLESTONE;
    }

    /** The side of a plain base: the difficulty's, sometimes a size up; at most 9 (the specialists' are grander). */
    int baseSize() {
        int size = profile.baseSize() + traits.baseSizeBonus() + (random.nextInt(4) == 0 ? 2 : 0);
        return Math.max(5, Math.min(9, size | 1));
    }

    /**
     * The size it wants for {@code design}: the personality picks the layout, the difficulty its grandeur (a grade
     * from the difficulty's base size, two more for a quick builder): an easy fortress builder raises a small keep
     * with two towers, a hell one a two-storey castle with four towers, a gatehouse and a palisade; an easy sniper's
     * tower is 7 high, a hell one 13 with a wide nest; trap fields, burrows and bunkers grow the same way.
     */
    int wantedSize(BasePlanner.Design layout) {
        int grade = profile.baseSize() + (profile.buildPerSecond() >= 5 ? 2 : 0);
        return switch (layout) {
            case KEEP -> grade + 4;
            case TOWER, BUNKER -> grade + 2;
            case BURROW, TRAPS -> grade;
            case WATCHPOST -> Math.min(7, grade);
            case BOX -> baseSize();
        };
    }

    /**
     * Picks its base site and size: candidates around itself (rings close by, so a site is found on rough ground too)
     * and towards the middle (a fortress builder or a survivor looks near the final zone), only where the border will
     * still be for a good while, on harmless ground, no farther than it can walk in a fair share of the farming time
     * left, never a site it gave up; its personality sets how much nearness to the middle counts. The layout is its
     * personality's, the size its difficulty's, made smaller until it can close it before the fight with the time
     * and blocks it has ({@link BasePlanner#fit}); without room for that, a smaller one or a plain box.
     */
    private BasePlanner.@Nullable Plan planBase(Mannequin self) {
        World world = self.getWorld();
        Location at = self.getLocation();
        double cx = host.borderCenterX();
        double cz = host.borderCenterZ();
        double pull = traits.centerPull();
        int scale = Math.max(1, host.timeScale());
        double secondsLeft = host.farmingTicksLeft() / 20.0;
        // the farthest site worth the walk: a fifth of the real farming time left (24 blocks at least)
        double reach = host.fight() ? Double.POSITIVE_INFINITY
                : Math.max(24, secondsLeft / scale * 0.2 * WALK_BLOCKS_PER_SECOND);
        if (design == null) {
            design = traits.design(random);
        }
        BasePlanner.Fit fit = BasePlanner.fit(design, wantedSize(design), budget(reach, secondsLeft));
        int wanted = fit.size();
        List<int[]> candidates = new ArrayList<>();
        double nowHalf = host.borderHalfIn(0);
        double finalHalf = host.finalHalf();
        double safeHalf = finalHalf + Math.max(0, nowHalf - finalHalf) * (0.6 - 0.45 * pull) + wanted;
        for (int i = 0; i < 40; i++) {
            double x;
            double z;
            if (i < 8 * pull) {
                // near the middle
                double r = finalHalf + 8 + (nowHalf - finalHalf) * 0.2;
                x = cx + (random.nextDouble() * 2 - 1) * r;
                z = cz + (random.nextDouble() * 2 - 1) * r;
            } else {
                // around itself, drawn towards where the border will end
                double towards = 0.15 + random.nextDouble() * 0.35 + 0.4 * pull;
                x = at.getX() + (cx - at.getX()) * towards + (random.nextDouble() * 2 - 1) * 24;
                z = at.getZ() + (cz - at.getZ()) * towards + (random.nextDouble() * 2 - 1) * 24;
            }
            candidates.add(new int[] {(int) Math.floor(x), (int) Math.floor(z)});
        }
        for (int r = 6; r <= 46; r += 8) {
            for (int i = 0; i < 12; i++) {
                double angle = i * Math.PI / 6 + r;
                candidates.add(new int[] {at.getBlockX() + (int) Math.round(Math.cos(angle) * r),
                        at.getBlockZ() + (int) Math.round(Math.sin(angle) * r)});
            }
        }
        candidates.add(new int[] {at.getBlockX(), at.getBlockZ()});
        candidates.removeIf(c -> !host.arena().contains(new Location(world, c[0] + 0.5, at.getY(), c[1] + 0.5))
                || Math.hypot(c[0] - at.getX(), c[1] - at.getZ()) > reach || nearBadSite(c));
        WorldTerrain terrain = new WorldTerrain(world);
        // a watch post wants the highest ground around, whatever the way to the middle
        double zoneWeight = fit.design() == BasePlanner.Design.WATCHPOST ? 0.01 : 0.04 + 0.25 * pull;
        List<int[]> others = manager.baseCenters(id);
        // its own layout at the size it can close, else smaller ones (rough or wet ground, a dear site), else a plain
        // box, else anywhere the border allows for less long
        List<BasePlanner.Fit> tries = new ArrayList<>();
        for (int tried = wanted; tried >= fit.design().minSize(); tried -= 2) {
            tries.add(new BasePlanner.Fit(fit.design(), tried));
        }
        // then its own layout at the smallest wherever the border allows for less long (relocating is better than
        // losing the look), then a plain box
        List<Boolean> strict = new ArrayList<>(java.util.Collections.nCopies(tries.size(), true));
        tries.add(new BasePlanner.Fit(fit.design(), fit.design().minSize()));
        strict.add(false);
        if (fit.design() != BasePlanner.Design.BOX) {
            for (int tried = Math.min(wanted, 7); tried >= BasePlanner.MIN_SIZE; tried -= 2) {
                tries.add(new BasePlanner.Fit(BasePlanner.Design.BOX, tried));
                strict.add(true);
            }
            tries.add(new BasePlanner.Fit(BasePlanner.Design.BOX, BasePlanner.MIN_SIZE));
            strict.add(false);
        }
        for (int i = 0; i < tries.size(); i++) {
            BasePlanner.Fit t = tries.get(i);
            int[] spot = strict.get(i)
                    ? BasePlanner.choose(terrain, candidates, t.size(), cx, cz, others, zoneWeight, safeHalf)
                    : BasePlanner.choose(terrain, candidates, t.size(), cx, cz, others);
            if (spot == null) {
                continue;
            }
            BasePlanner.Plan plan = BasePlanner.plan(terrain, spot[0], spot[1], t.size(), cx, cz, t.design());
            double walk = Math.hypot(spot[0] - at.getX(), spot[1] - at.getZ());
            double cost = t.design() == BasePlanner.Design.BURROW ? 1.0 / 3 : 1;
            if (t.size() > t.design().minSize() && plan.closureBlocks() * cost > budget(walk, secondsLeft)) {
                continue; // its ground costs more than the time and blocks allow: a smaller one
            }
            base = plan;
            int y = terrain.height(spot[0], spot[1]);
            palette = palette(t.design(), world.getBiome(spot[0], y, spot[1]).getKey().toString(),
                    world.getBlockAt(spot[0], y, spot[1]).getType());
            return plan;
        }
        return null;
    }

    /**
     * Blocks it can close a base with ({@link BasePlanner#budget}): what it places in the farming time left after a
     * walk of {@code walk} blocks, and the blocks it will have by then (its stock grows with the farming; a few kept
     * for bridges). In the fight (no time left) that means the smallest base.
     */
    private double budget(double walk, double secondsLeft) {
        int scale = Math.max(1, host.timeScale());
        double walkSeconds = Double.isInfinite(walk) ? 0 : walk / WALK_BLOCKS_PER_SECOND * scale;
        double progress = Math.min(1, host.farmingProgress());
        int later = stock(Gear.at((progress + 0.9 * (1 - progress)) * profile.gearPace()));
        int blocks = Math.max(blocks(), later - blocksUsed) - 16;
        return BasePlanner.budget(buildRate(), secondsLeft, walkSeconds, blocks);
    }

    /**
     * Placements a second while building: the difficulty's, much more for a base specialist (it spends the whole
     * farming on its base: a fortress builder three times as fast).
     */
    double buildRate() {
        TeamTactics.SharedBase team = teamBase;
        return team != null && team.architect().equals(id) ? Math.max(ownBuildRate(), team.rate()) : ownBuildRate();
    }

    /** Its own building rate (without its team's help). */
    double ownBuildRate() {
        return profile.buildPerSecond() * (1 + 2 * traits.homebody());
    }

    /**
     * Blocks a loadout gives it: the farmed schedule, more for a base specialist (it farmed for its base); a team's
     * architect gets its team's blocks too.
     */
    int stock(Gear.Loadout gear) {
        int own = rawStock(gear);
        TeamTactics.SharedBase team = teamBase;
        if (team == null || !team.architect().equals(id) || host.fight()) {
            return own; // the team's blocks are for its base in the farming, not for rebuilding it in the fight
        }
        return (int) Math.round(own * Math.max(1, team.stock() / (double) Math.max(1, rawStock(loadout))));
    }

    /** Its own blocks now (without its team's). */
    int ownStock() {
        return rawStock(loadout);
    }

    private int rawStock(Gear.Loadout gear) {
        return (int) Math.round(gear.blocks() * (1 + 1.5 * traits.homebody()));
    }

    /**
     * The blocks of a base, by layout and biome, mixed like a player's farmed stock (the first block of each part
     * mostly, the others speckled in): a keep of stone bricks with cobble, mossy and cracked ones, deepslate towers, a
     * log band, plank floors, slab battlements and glass in the upper windows; a tower of logs at the corners and
     * planks with some stone between; a bunker of cobble, cobbled deepslate and blackstone; a burrow patched with the
     * very ground it is dug into; a trapper's box of logs like a palisade. Deserts and badlands build in sandstone,
     * snowy places mix in snow, jungles and swamps moss.
     */
    static BasePlanner.Palette palette(BasePlanner.Design design, String biome, Material surface) {
        BasePlanner.Palette biomes = BasePlanner.Palette.forBiome(biome);
        String b = biome.toLowerCase(java.util.Locale.ROOT);
        boolean red = biomes.wall().startsWith("RED_SANDSTONE");
        boolean sandy = biomes.wall().contains("SANDSTONE");
        boolean snowy = b.contains("snow") || b.contains("frozen") || b.contains("ice") || b.contains("grove");
        boolean mossy = b.contains("jungle") || b.contains("swamp") || b.contains("mangrove") || b.contains("lush");
        String wood = biomes.pillar().endsWith("_LOG") ? biomes.pillar().substring(0, biomes.pillar().length() - 4)
                : sandy ? "ACACIA" : "SPRUCE";
        String planks = wood + "_PLANKS";
        String log = wood + "_LOG";
        String stripped = "STRIPPED_" + wood + "_LOG";
        String slab = wood + "_SLAB";
        String sand = red ? "RED_SANDSTONE" : "SANDSTONE";
        String sandWall = sand + "|CUT_" + sand + "|SMOOTH_" + sand;
        String sandSlab = sand + "_SLAB|CUT_" + sand + "_SLAB";
        return switch (design) {
            case KEEP -> sandy
                    ? new BasePlanner.Palette(sandWall, "CHISELED_" + sand + "|CUT_" + sand, "SMOOTH_" + sand,
                    "STRIPPED_ACACIA_LOG", "ACACIA_PLANKS", sandSlab, "GLASS_PANE", "LANTERN")
                    : new BasePlanner.Palette(mossy ? "MOSSY_STONE_BRICKS|STONE_BRICKS|MOSSY_COBBLESTONE|CRACKED_STONE_BRICKS"
                    : snowy ? "STONE_BRICKS|SNOW_BLOCK|CRACKED_STONE_BRICKS|COBBLESTONE"
                    : "STONE_BRICKS|COBBLESTONE|MOSSY_STONE_BRICKS|CRACKED_STONE_BRICKS|ANDESITE",
                    "DEEPSLATE_BRICKS|COBBLED_DEEPSLATE|POLISHED_DEEPSLATE", planks + "|DARK_OAK_PLANKS", log, planks,
                    "STONE_BRICK_SLAB|COBBLESTONE_SLAB|MOSSY_STONE_BRICK_SLAB", "GLASS_PANE|IRON_BARS", "LANTERN");
            case TOWER -> sandy
                    ? new BasePlanner.Palette(sandWall, "CHISELED_" + sand, "SMOOTH_" + sand, "STRIPPED_ACACIA_LOG",
                    "ACACIA_PLANKS", sandSlab, "GLASS_PANE", "LANTERN")
                    : new BasePlanner.Palette(planks + "|COBBLESTONE|" + stripped + (snowy ? "|SNOW_BLOCK" : ""), log,
                    planks, log, planks, slab + "|COBBLESTONE_SLAB", "GLASS_PANE", "LANTERN");
            case BUNKER -> sandy
                    ? new BasePlanner.Palette(sand + "|COBBLESTONE|SMOOTH_" + sand, "CUT_" + sand, "SMOOTH_" + sand)
                    : new BasePlanner.Palette("COBBLESTONE|COBBLED_DEEPSLATE|BLACKSTONE|STONE|ANDESITE",
                    "POLISHED_BLACKSTONE", "COBBLED_DEEPSLATE|COBBLESTONE|POLISHED_BLACKSTONE");
            case BURROW -> {
                String ground = switch (surface) {
                    case SAND, SANDSTONE -> "SANDSTONE|SMOOTH_SANDSTONE";
                    case RED_SAND -> "RED_SANDSTONE|SMOOTH_RED_SANDSTONE";
                    case STONE, ANDESITE, DIORITE, GRANITE, TUFF, DEEPSLATE, GRAVEL -> "STONE|ANDESITE|COBBLESTONE|TUFF";
                    default -> "DIRT|COARSE_DIRT|ROOTED_DIRT";
                };
                String top = switch (surface) {
                    case GRASS_BLOCK -> "GRASS_BLOCK|MOSS_BLOCK";
                    case SNOW_BLOCK, SNOW, POWDER_SNOW -> "SNOW_BLOCK";
                    case PODZOL, MYCELIUM, MUD, MOSS_BLOCK, COARSE_DIRT -> surface.name();
                    default -> ground;
                };
                yield new BasePlanner.Palette(ground, ground, top, ground, ground, ground, "GLASS_PANE", "LANTERN");
            }
            case TRAPS -> sandy
                    ? new BasePlanner.Palette(sandWall, "STRIPPED_ACACIA_LOG", "SMOOTH_" + sand)
                    : new BasePlanner.Palette(log + "|" + planks + "|" + stripped, log, planks, log, planks, slab,
                    "GLASS_PANE", "LANTERN");
            case WATCHPOST, BOX -> sandy
                    ? new BasePlanner.Palette(sandWall, "CUT_" + sand, sand, "CHISELED_" + sand, sand, sandSlab,
                    "GLASS_PANE", "LANTERN")
                    : biomes.wall().startsWith("COBBLESTONE")
                    ? new BasePlanner.Palette("COBBLESTONE|MOSSY_COBBLESTONE|STONE", "STONE_BRICKS",
                    "COBBLESTONE|STONE", "STONE_BRICKS", "COBBLESTONE", "COBBLESTONE_SLAB", "GLASS_PANE", "LANTERN")
                    : new BasePlanner.Palette(planks + "|" + stripped, log, planks, log, planks, slab, "GLASS_PANE",
                    "LANTERN");
        };
    }

    /** True if {@code spot} is near a site it gave up. */
    private boolean nearBadSite(int[] spot) {
        for (int[] bad : badSites) {
            if (Math.hypot(bad[0] - spot[0], bad[1] - spot[1]) < 8) {
                return true;
            }
        }
        return false;
    }

    /**
     * Places a block of the bot's stock: only in the match world, only into air or replaceable plants (never water,
     * lava or the void), never where a living entity stands or is about to step ({@link #blocksSomeone}).
     */
    boolean place(Mannequin self, int x, int y, int z, Material material) {
        World world = self.getWorld();
        if (!world.equals(host.world()) || blocks() <= 0 || y <= world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        Block block = world.getBlockAt(x, y, z);
        Material type = block.getType();
        if (!(type.isAir() || block.isReplaceable() && !block.isLiquid())) {
            return false;
        }
        if (!host.arena().contains(block.getLocation().add(0.5, 0, 0.5))) {
            return false;
        }
        if (blocksSomeone(world, x, y, z)) {
            return false; // never trap anyone (or itself) inside a block
        }
        block.setType(material);
        if (material == Material.SWEET_BERRY_BUSH) {
            ripen(block);
        }
        placed.put(block, material);
        blocksUsed++;
        self.swingMainHand();
        world.playSound(block.getLocation(), Sound.BLOCK_STONE_PLACE, 0.8f, 1f);
        return true;
    }

    /**
     * Places a block where {@link #place} would not: into water or lava (a bridge, a step over a lake), into a
     * cleared hazard; the same other rules (its stock, the match world and arena, nobody in the way).
     */
    boolean placeAny(Mannequin self, int x, int y, int z, Material material) {
        World world = self.getWorld();
        if (!world.equals(host.world()) || blocks() <= 0 || y <= world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        Block block = world.getBlockAt(x, y, z);
        Material type = block.getType();
        if (type.isSolid() && !Hazards.hazard(type) || Hazards.UNBREAKABLE.contains(type)
                || !host.arena().contains(block.getLocation().add(0.5, 0, 0.5)) || blocksSomeone(world, x, y, z)) {
            return false;
        }
        block.setType(material);
        if (material == Material.SWEET_BERRY_BUSH) {
            ripen(block);
        }
        placed.put(block, material);
        blocksUsed++;
        self.swingMainHand();
        world.playSound(block.getLocation(), Sound.BLOCK_STONE_PLACE, 0.8f, 1f);
        return true;
    }


    /**
     * True if a block at {@code (x, y, z)} would overlap a living entity: every one, the bots' legs included (they are
     * where the body is put next tick), each as a standing body (the legs are a little taller than the body they
     * carry) widened a little and stretched along where it moves in the next ticks (the bodies are a tick behind the
     * legs, and a walker steps into the cell before the next check).
     */
    boolean blocksSomeone(World world, int x, int y, int z) {
        BoundingBox cell = new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
        for (Entity entity : world.getNearbyEntities(cell.clone().expand(2.5))) {
            if (!(entity instanceof LivingEntity living) || living.isDead()) {
                continue;
            }
            Location at = entity.getLocation();
            BoundingBox box = manager.isLegs(entity) || manager.isBody(entity)
                    ? new BoundingBox(at.getX() - 0.3, at.getY(), at.getZ() - 0.3, at.getX() + 0.3, at.getY() + 1.8,
                    at.getZ() + 0.3)
                    : entity.getBoundingBox().clone();
            box.expand(0.1, 0, 0.1);
            Vector motion = entity.getVelocity();
            box.expandDirectional(motion.getX() * 3, Math.max(0, motion.getY()) * 3, motion.getZ() * 3);
            if (box.overlaps(cell)) {
                return true;
            }
        }
        return false;
    }

    /** Walls itself in where it stands: four sides two high and a roof, a few blocks per second. */
    private void fortify(long now, Mannequin self) {
        if (fortifyUntil < 0) {
            fortifyUntil = now + FORTIFY_TICKS;
            center(); // in the middle of its block, so the walls fit around it
        }
        Location feet = self.getLocation();
        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();
        int[][] spots = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
                {0, 2, 0}};
        buildCredit += profile.buildPerSecond() * 2 / 20.0;
        for (int[] spot : spots) {
            if (buildCredit < 1) {
                break;
            }
            Block block = self.getWorld().getBlockAt(x + spot[0], y + spot[1], z + spot[2]);
            if (fortress.contains(block) || !block.getType().isAir()) {
                continue;
            }
            buildCredit--;
            if (place(self, block.getX(), block.getY(), block.getZ(), material(BasePlanner.Part.WALL))) {
                fortress.add(block);
                face(self, block.getLocation().add(0.5, 0.5, 0.5));
            }
        }
        if (self.getHealth() >= Math.min(maxHealth(self), profile.healAt() * 1.7) || now >= fortifyUntil) {
            breakOut();
            decision = null;
            nextThink = now;
        }
    }

    /** Puts the legs in the middle of the block they stand in (if nothing is in the way there). */
    private void center() {
        BotBody b = body;
        if (b == null) {
            return;
        }
        WanderingTrader legs = b.legs();
        Location at = legs.getLocation();
        Location middle = new Location(at.getWorld(), at.getBlockX() + 0.5, at.getY(), at.getBlockZ() + 0.5,
                at.getYaw(), at.getPitch());
        if (!at.getWorld().hasCollisionsIn(standing(middle.getX(), middle.getY(), middle.getZ()))) {
            legs.teleport(middle);
            legs.setVelocity(new Vector());
        }
    }

    /**
     * Takes the shelter down again (a player mines their way out): every block of it that is still its own goes, so
     * no box is left around it that would hide it from everyone for the rest of the match.
     */
    private void breakOut() {
        Iterator<Block> it = fortress.iterator();
        Material wall = material(BasePlanner.Part.WALL);
        while (it.hasNext()) {
            Block block = it.next();
            if (block.getType() == wall) {
                block.setType(Material.AIR);
            }
            placed.remove(block);
            it.remove();
        }
        if (fortifyUntil >= 0) {
            fortifyAgainAt = host.clock() + FORTIFY_COOLDOWN; // no hiding in a box over and over
        }
        fortifyUntil = -1;
    }

    // =================================================================== moving

    /**
     * Walks or sprints to {@code where} (kept inside the border): the pathfinder speed is set so the legs move like a
     * player's walk or sprint ({@link BotMotion}), with the body's speed bonuses. A finished (partial) path is
     * continued at once; otherwise the path is renewed at most every 8 ticks unless the destination moves.
     */
    private void goTo(long now, Location where, Gait how) {
        BotBody b = body;
        if (b == null) {
            return;
        }
        Location dest = insideBorder(where);
        Location at = b.legs().getLocation();
        BasePlanner.Plan home = base;
        if (home != null && inOwnRoom(home, at) && !home.inside(dest.getX(), dest.getY(), dest.getZ())
                && walkOut(now, b, home)) {
            goal = dest; // out by the doorway first, step by step (see walkOut)
            gait = how;
            return;
        }
        if (home != null && !home.inside(at.getX(), at.getY(), at.getZ())
                && home.inside(dest.getX(), dest.getY(), dest.getZ())) {
            if (walkIn(now, b, home)) {
                goal = dest; // in along the doorway's lane, step by step (see walkIn)
                gait = how;
                return;
            }
            int[] out = home.outer();
            Location front = new Location(dest.getWorld(), out[0] + 0.5, out[1], out[2] + 0.5);
            if (front.distanceSquared(at) > 1) {
                dest = front; // to the spot outside its doorway first
            }
        }
        double distance = dest.getWorld() == at.getWorld() ? dest.distance(at) : 0;
        double speed = BotMotion.pathSpeed(BotMotion.blocksPerSecond(how, distance, speedFactor()),
                BotMotion.REFERENCE_ATTRIBUTE);
        Location old = goal;
        boolean same = old != null && old.getWorld() == dest.getWorld() && old.distanceSquared(dest) < 2.25;
        // a path in progress is renewed every 8 ticks; a finished (or failed) one after 3
        boolean fresh = b.legs().getPathfinder().hasPath() ? now < nextPath : now < nextPath - 5;
        if (same && fresh && Math.abs(speed - pathSpeed) < 0.01) {
            return;
        }
        goal = dest;
        gait = how;
        pathSpeed = speed;
        nextPath = now + 8;
        if (recovering()) {
            return; // digging, climbing or bridging its own way for now (see recover)
        }
        dest = throughDoor(at, dest);
        Location target = waypoint(now, at, dest);
        var pathfinder = b.legs().getPathfinder();
        var path = pathfinder.findPath(target);
        if (path == null) {
            pathFailures++;
            if (autoRecovery && pathFailures >= 3 && distance > 3) {
                startRecovery(now); // nowhere the pathfinder can go: its own way
            }
            return;
        }
        pathFailures = 0;
        int safe = safePrefix(path.getPoints(), manager);
        if (safe < path.getPoints().size()) {
            // the path crosses powder snow (or the like): only up to the last safe point; the recovery clears or
            // bridges the rest if that is the only way
            if (safe <= 1) {
                startRecovery(now);
                return;
            }
            Location stop = path.getPoints().get(safe - 1).clone().add(0.5, 0, 0.5);
            path = pathfinder.findPath(stop);
            if (path == null) {
                return;
            }
        }
        pathfinder.moveTo(path, speed);
    }

    /**
     * Where to step next on the way out of {@code home} from {@code (x, z)}: the room cell in front of the doorway
     * while off its lane, then a little further along the lane each time, up to the spot outside the doorway; null
     * once there (or past it).
     */
    static double @Nullable [] exitStep(BasePlanner.Plan home, double x, double z) {
        int ox = Integer.signum(home.doorX() - home.x());
        int oz = Integer.signum(home.doorZ() - home.z());
        double along = (x - (home.x() + 0.5)) * ox + (z - (home.z() + 0.5)) * oz;
        double across = (x - (home.x() + 0.5)) * -oz + (z - (home.z() + 0.5)) * ox;
        int[] in = home.inner();
        int[] out = home.outer();
        double outAlong = (out[0] - home.x()) * ox + (out[2] - home.z()) * oz;
        if (along >= outAlong - 0.3) {
            return null;
        }
        if (Math.abs(across) > 0.35) {
            return new double[] {in[0] + 0.5, in[2] + 0.5}; // onto the doorway's lane
        }
        double ahead = Math.min(outAlong, along + 1.2);
        return new double[] {home.x() + 0.5 + ox * ahead, home.z() + 0.5 + oz * ahead};
    }

    /**
     * True if feet at {@code at} stand in the room of its own walled base (not a burrow, which has its own stairs, nor
     * up a tower's shaft).
     */
    boolean inOwnRoom(BasePlanner.Plan home, Location at) {
        return home.inside(at.getX(), at.getY(), at.getZ()) && (!home.tower() || at.getY() < home.floorY() + 2.5)
                || onOwnStairs(home, at);
    }

    /** True if feet at {@code at} stand on the staircase down to its own burrow. */
    static boolean onOwnStairs(BasePlanner.Plan home, Location at) {
        if (home.design() != BasePlanner.Design.BURROW) {
            return false;
        }
        int ox = Integer.signum(home.doorX() - home.x());
        int oz = Integer.signum(home.doorZ() - home.z());
        double along = (at.getX() - (home.x() + 0.5)) * ox + (at.getZ() - (home.z() + 0.5)) * oz;
        double across = (at.getX() - (home.x() + 0.5)) * -oz + (at.getZ() - (home.z() + 0.5)) * ox;
        return along > home.half() - 0.5 && along < home.half() + 4.5 && Math.abs(across) < 0.7
                && at.getY() > home.floorY() + 0.5 && at.getY() < home.floorY() + 6.5;
    }

    /**
     * The height of the feet {@code ahead} blocks out from the middle along the doorway's lane: the room's floor, a
     * burrow's stairs one block up per block out (to the top of its staircase).
     */
    static double laneFeet(BasePlanner.Plan home, double ahead) {
        if (home.design() != BasePlanner.Design.BURROW || ahead <= home.half()) {
            return home.floorY() + 1;
        }
        return home.floorY() + 1 + Math.min(4, Math.ceil(ahead - home.half() - 0.01));
    }

    /**
     * Walks out of its own base like a player: onto the lane of the doorway (the room cell in front of it), then
     * straight out along it to the spot outside, steering the legs itself (the pathfinder can lose its way in a small
     * room with furniture and its door blocks, and its recovery must never tunnel or pillar inside its own walls).
     * Whatever blocks the doorway is dug (its own door blocks, someone's block), and a block of its own under its feet
     * in the room is taken away. False once it stands outside (the pathfinder takes over).
     */
    private boolean walkOut(long now, BotBody b, BasePlanner.Plan home) {
        WanderingTrader legs = b.legs();
        Location at = legs.getLocation();
        double[] step = exitStep(home, at.getX(), at.getZ());
        if (step == null) {
            return false;
        }
        if (recovering()) {
            endRecovery(now);
        }
        if (legs.getPathfinder().hasPath()) {
            legs.getPathfinder().stopPathfinding();
        }
        if (now >= nextPassageClear) {
            nextPassageClear = now + 20;
            clearPassage(home);
        }
        Block under = at.getBlock().getRelative(BlockFace.DOWN);
        if (at.getY() > home.floorY() + 1.5 && placed.containsKey(under) && !ownShell(under)) {
            dig(under); // a block it stood itself on in the room
        }
        steer(legs, step[0], laneFeet(home, laneAlong(home, step[0], step[1])), step[1]);
        unwedge(now, legs, home, step);
        return true;
    }

    /** How far {@code (x, z)} is from the middle of {@code home} along its doorway's lane. */
    static double laneAlong(BasePlanner.Plan home, double x, double z) {
        int ox = Integer.signum(home.doorX() - home.x());
        int oz = Integer.signum(home.doorZ() - home.z());
        return (x - (home.x() + 0.5)) * ox + (z - (home.z() + 0.5)) * oz;
    }

    /**
     * In through its own doorway like a player: from the spot outside (or anywhere on the doorway's lane) straight
     * along the lane to the room cell inside, steering the legs itself (the pathfinder can fail at a narrow doorway
     * with webs, fences and door blocks about). False when it is not on the lane or already inside.
     */
    private boolean walkIn(long now, BotBody b, BasePlanner.Plan home) {
        WanderingTrader legs = b.legs();
        Location at = legs.getLocation();
        double[] step = enterStep(home, at.getX(), at.getZ());
        if (step == null
                || Math.abs(at.getY() - laneFeet(home, laneAlong(home, at.getX(), at.getZ()))) > LANE_SLACK) {
            return false;
        }
        if (recovering()) {
            endRecovery(now);
        }
        goal = null;
        if (legs.getPathfinder().hasPath()) {
            legs.getPathfinder().stopPathfinding();
        }
        if (now >= nextPassageClear) {
            nextPassageClear = now + 20;
            clearPassage(home);
        }
        steer(legs, step[0], laneFeet(home, laneAlong(home, step[0], step[1])), step[1]);
        unwedge(now, legs, home, step);
        return true;
    }

    /**
     * On its own doorway's lane and not moving for {@link #LANE_STUCK_TICKS} (a step up it cannot jump, a block
     * fallen in): it clears the cell ahead at the lane's height (never its own walls) and steps into it, like a player
     * who mines the block in front of them.
     */
    private void unwedge(long now, WanderingTrader legs, BasePlanner.Plan home, double[] step) {
        Location at = legs.getLocation();
        if (laneLast == null || laneLast.getWorld() != at.getWorld() || laneLast.distanceSquared(at) > 0.0025) {
            laneLast = at;
            laneStill = now;
            return;
        }
        if (now - laneStill < LANE_STUCK_TICKS) {
            return;
        }
        laneStill = now;
        int y = (int) Math.round(laneFeet(home, laneAlong(home, step[0], step[1])));
        int x = (int) Math.floor(step[0]);
        int z = (int) Math.floor(step[1]);
        if (x == at.getBlockX() && z == at.getBlockZ() && y == at.getBlockY()) {
            return;
        }
        World world = at.getWorld();
        for (int dy = 0; dy <= 1; dy++) {
            Block block = world.getBlockAt(x, y + dy, z);
            if (!block.getType().isAir() && !ownShell(block) && host.arena().contains(block.getLocation().add(0.5, 0,
                    0.5))) {
                dig(block);
            }
        }
        nudge(legs, new int[] {x, y, z});
    }

    /** Pushes the legs a walking step towards {@code (x, z)} (jumping onto a step up to {@code y}). */
    private static void steer(WanderingTrader legs, double x, double y, double z) {
        Location at = legs.getLocation();
        Vector push = new Vector(x - at.getX(), 0, z - at.getZ());
        double length = push.length();
        if (length > 1.0E-3) {
            push.multiply(Math.min(0.2, Math.max(0.1, length)) / length);
        }
        double vy = legs.getVelocity().getY();
        if (legs.isOnGround() && y > at.getY() + 0.5) {
            vy = 0.42;
        }
        legs.setVelocity(new Vector(push.getX(), vy, push.getZ()));
    }

    /**
     * Where to step next on the way into {@code home} from {@code (x, z)} on its doorway's lane (between the spot
     * outside and the room cell inside): a little further in each time; null off the lane, beyond the spot outside or
     * once in the room.
     */
    static double @Nullable [] enterStep(BasePlanner.Plan home, double x, double z) {
        int ox = Integer.signum(home.doorX() - home.x());
        int oz = Integer.signum(home.doorZ() - home.z());
        double along = (x - (home.x() + 0.5)) * ox + (z - (home.z() + 0.5)) * oz;
        double across = (x - (home.x() + 0.5)) * -oz + (z - (home.z() + 0.5)) * ox;
        int[] in = home.inner();
        int[] out = home.outer();
        double inAlong = (in[0] - home.x()) * ox + (in[2] - home.z()) * oz;
        double outAlong = (out[0] - home.x()) * ox + (out[2] - home.z()) * oz;
        if (Math.abs(across) > 0.6 || along > outAlong + 0.7 || along <= inAlong + 0.15) {
            return null;
        }
        double ahead = Math.max(inAlong, along - 1.2);
        return new double[] {home.x() + 0.5 + ox * ahead, home.z() + 0.5 + oz * ahead};
    }

    /**
     * The way in and out of its own base, which the pathfinder may not find from inside: out by the doorway first; a
     * burrow's staircase step by step, down to the room or up to the surface.
     */
    private Location throughDoor(Location at, Location dest) {
        BasePlanner.Plan home = base;
        if (home == null || home.tower()) {
            return dest;
        }
        boolean atInside = home.inside(at.getX(), at.getY(), at.getZ());
        boolean destInside = home.inside(dest.getX(), dest.getY(), dest.getZ());
        World world = at.getWorld();
        if (home.design() == BasePlanner.Design.BURROW && atInside != destInside) {
            Location step = burrowStep(home, at, destInside);
            if (step != null) {
                return step;
            }
            if (destInside) {
                int[] top = home.outer(); // to the top of its stairs first
                return new Location(world, top[0] + 0.5, top[1], top[2] + 0.5);
            }
        }
        if (atInside && !destInside) {
            int[] door = home.outer();
            Location out = new Location(world, door[0] + 0.5, door[1], door[2] + 0.5);
            if (out.distanceSquared(at) > 1.5) {
                return out;
            }
        }
        return dest;
    }

    /**
     * The next step on its burrow's staircase, down towards the room or up to the surface, or null if it is not on
     * the stairs (or already at their end).
     */
    static @Nullable Location burrowStep(BasePlanner.Plan plan, Location at, boolean down) {
        int ox = Integer.signum(plan.doorX() - plan.x());
        int oz = Integer.signum(plan.doorZ() - plan.z());
        int along = (at.getBlockX() - plan.x()) * ox + (at.getBlockZ() - plan.z()) * oz - plan.half();
        int across = (at.getBlockX() - plan.x()) * -oz + (at.getBlockZ() - plan.z()) * ox;
        boolean inRoom = plan.inside(at.getX(), at.getY(), at.getZ());
        if (!inRoom) {
            if (along < 0 || along > 4 || Math.abs(across) > 1
                    || along < 4 && at.getY() > plan.floorY() + 1 + along + 1.5) {
                return null; // not on the stairs (on the ground above them: from the top)
            }
            if (across != 0) {
                return new Location(at.getWorld(), plan.doorX() + ox * along + 0.5, plan.floorY() + 1 + along,
                        plan.doorZ() + oz * along + 0.5); // beside them: onto the step first
            }
        }
        int next = (inRoom ? -1 : along) + (down ? -1 : 1);
        if (next < -1 || next > 4) {
            return null;
        }
        if (next == -1) {
            return new Location(at.getWorld(), plan.x() + 0.5, plan.floorY() + 1, plan.z() + 0.5);
        }
        return new Location(at.getWorld(), plan.doorX() + ox * next + 0.5, plan.floorY() + 1 + next,
                plan.doorZ() + oz * next + 0.5);
    }

    /** How far one leg of a long way reaches before the next waypoint (inside the pathfinder's range). */
    static final double WAYPOINT = 22;

    /**
     * The pathfinder only plans about {@link BotBody#FOLLOW_RANGE} blocks: a far destination is reached through
     * waypoints on harmless ground about {@link #WAYPOINT} blocks ahead, straight towards it or turned up to 60° to
     * either side when the straight one is unreachable (a lake, a cliff), kept until reached or stale.
     */
    private Location waypoint(long now, Location at, Location dest) {
        double flat = Math.hypot(dest.getX() - at.getX(), dest.getZ() - at.getZ());
        if (flat <= WAYPOINT + 4 || dest.getWorld() != at.getWorld()) {
            waypoint = null;
            return dest;
        }
        Location kept = waypoint;
        if (kept != null && waypointFor != null && now < waypointUntil && waypointFor.distanceSquared(dest) < 64
                && kept.getWorld() == at.getWorld()
                && Math.hypot(kept.getX() - at.getX(), kept.getZ() - at.getZ()) > 3) {
            return kept;
        }
        BotBody b = body;
        double heading = Math.atan2(dest.getZ() - at.getZ(), dest.getX() - at.getX());
        int[] turns = {0, 30, -30, 60, -60};
        Location fallback = null;
        for (int deg : turns) {
            double angle = heading + Math.toRadians(deg);
            Location spot = ground(at.getWorld(), at.getX() + Math.cos(angle) * WAYPOINT,
                    at.getZ() + Math.sin(angle) * WAYPOINT);
            if (fallback == null) {
                fallback = spot;
            }
            if (b == null) {
                break;
            }
            var path = b.legs().getPathfinder().findPath(spot);
            if (path != null && path.getFinalPoint() != null
                    && path.getFinalPoint().distanceSquared(spot) < 16 && safePrefix(path.getPoints(), manager)
                    == path.getPoints().size()) {
                fallback = spot;
                break;
            }
        }
        waypoint = fallback;
        waypointFor = dest.clone();
        waypointUntil = now + 100;
        return fallback;
    }

    /** How many points of a path from the start are safe to walk (no powder snow, fire, cactus, magma ...). */
    static int safePrefix(List<Location> points) {
        return safePrefix(points, null);
    }

    /** {@link #safePrefix} that also stops before a trapper's hidden pit ({@code manager} knows them). */
    static int safePrefix(List<Location> points, @Nullable BotManager manager) {
        for (int i = 0; i < points.size(); i++) {
            Block feet = points.get(i).getBlock();
            if (Hazards.hazard(feet) || Hazards.hazard(feet.getRelative(0, -1, 0))
                    || Hazards.hazard(feet.getRelative(0, 1, 0))
                    || manager != null && (manager.trap(feet) || manager.trap(feet.getRelative(0, -1, 0)))) {
                return i;
            }
        }
        return points.size();
    }

    // =================================================================== getting unstuck

    /**
     * Watches its progress towards where it goes ({@link Recovery#stuck} over a {@link Recovery#WINDOW}): stuck in a
     * cave, behind a cliff or a wall, it stops relying on the pathfinder and makes its own way ({@link #recover}).
     */
    private void progress(long now, BotBody b) {
        Location at = b.legs().getLocation();
        Location g = goal;
        if (!autoRecovery || g == null || g.getWorld() != at.getWorld()) {
            progressTick = -1;
            return;
        }
        if (progressTick < 0 || progressAt == null || progressGoal == null) {
            progressTick = now;
            progressAt = at;
            progressGoal = g.clone();
            progressDistance = at.distance(g);
            return;
        }
        if (now - progressTick < Recovery.WINDOW) {
            return;
        }
        double distance = at.distance(g);
        boolean stuckNow = Recovery.stuck(at.distance(progressAt), progressDistance - distance,
                g.distance(progressGoal), distance);
        if (stuckNow && !recovering() && decision != null && decision.behaviour() != Behaviour.AMBUSH) {
            stuckCount++;
            startRecovery(now);
        } else if (!stuckNow) {
            stuckCount = 0;
        }
        progressTick = now;
        progressAt = at;
        progressGoal = g.clone();
        progressDistance = distance;
    }

    private void startRecovery(long now) {
        BotBody b = body;
        BasePlanner.Plan home = base;
        if (recovering() || b == null || home != null && inOwnRoom(home, b.legs().getLocation())) {
            return; // in its own room it leaves by the door (walkOut), never through its walls
        }
        recoverUntil = now + 20 * 20;
        recoverSteps = 0;
        turn = 0;
        stepTo = null;
        pillarCell = null;
        waypoint = null;
        pathFailures = 0;
        if (b.legs().getPathfinder().hasPath()) {
            b.legs().getPathfinder().stopPathfinding();
        }
    }

    private void endRecovery(long now) {
        recoverUntil = -1;
        stepTo = null;
        pillarCell = null;
        nextPath = now;
        progressTick = -1;
    }

    /** Blocks it keeps for its base while that is unfinished (bridges and pillars use the rest). */
    private int spareBlocks() {
        BasePlanner.Plan plan = base;
        if (!traits.builds() || host.fight() || baseFailed) {
            return 0;
        }
        return plan == null ? 48 : Math.max(0, Math.min(96, plan.closure() - built));
    }

    /** Ticks between two recovery steps (a player digs a block in a few ticks; the better, the quicker). */
    int digTicks() {
        return Math.max(3, Math.min(12, profile.reactionTicks() + 2));
    }

    /**
     * One recovery step every {@link #digTicks}: towards its goal (the surface first when underground), it walks,
     * tunnels, digs stairs, pillars or bridges ({@link Recovery#next}); a step impossible straight on is tried to the
     * sides. It goes back to the pathfinder after a few steps, when the pathfinder can reach the goal again, or after
     * 20 s.
     */
    void recover(long now, BotBody b) {
        WanderingTrader legs = b.legs();
        Location at = legs.getLocation();
        Location g = goal;
        if (g == null || now >= recoverUntil || recoverSteps >= 16 || g.getWorld() != at.getWorld()) {
            endRecovery(now);
            return;
        }
        int[] pillar = pillarCell;
        if (pillar != null) {
            // jumped: the block goes under the feet once they are above it
            if (at.getY() >= pillar[1] + 1.05 && b.body().getLocation().getY() >= pillar[1] + 1.0
                    || now - stepSince > 14) {
                placeAny(b.body(), pillar[0], pillar[1], pillar[2], material(BasePlanner.Part.WALL));
                pillarCell = null;
            }
            return;
        }
        int[] to = stepTo;
        if (to != null) {
            Vector push = new Vector(to[0] + 0.5 - at.getX(), 0, to[2] + 0.5 - at.getZ());
            boolean there = at.getBlockX() == to[0] && at.getBlockZ() == to[2] && Math.abs(at.getY() - to[1]) < 0.6
                    && push.lengthSquared() < 0.09;
            if (there) {
                // stop in the cell: momentum must not carry it over the next edge
                legs.setVelocity(legs.getVelocity().setX(0).setZ(0));
            } else {
                double length = push.length();
                if (length > 1.0E-2) {
                    push.multiply(Math.min(0.2, length * 0.5) / length); // slows down as it arrives
                }
                double vy = legs.getVelocity().getY();
                if (legs.isOnGround() && to[1] > at.getBlockY()) {
                    vy = 0.42;
                }
                legs.setVelocity(new Vector(push.getX(), vy, push.getZ()));
                if (now - stepSince > 20) {
                    nudge(legs, to); // a step it could not make on its own: put it into the (free, adjacent) cell
                    stepTo = null;
                }
                return;
            }
            stepTo = null;
        }
        if (now < nextDig) {
            return;
        }
        nextDig = now + digTicks();
        if (now >= nextPathCheck && recoverSteps >= 3) {
            nextPathCheck = now + 30;
            // the next stretch (the goal, or a point 16 blocks towards it): if the pathfinder reaches it, it walks on
            double flat = Math.hypot(g.getX() - at.getX(), g.getZ() - at.getZ());
            Location probe = flat <= 16 ? g : ground(at.getWorld(), at.getX() + (g.getX() - at.getX()) * 16 / flat,
                    at.getZ() + (g.getZ() - at.getZ()) * 16 / flat);
            var path = legs.getPathfinder().findPath(probe);
            if (path != null && path.canReachFinalPoint()
                    && safePrefix(path.getPoints(), manager) == path.getPoints().size()) {
                endRecovery(now); // the pathfinder can take it from here
                return;
            }
        }
        World world = at.getWorld();
        Hazards.WorldGrid grid = new Hazards.WorldGrid(world);
        boolean underground = Hazards.underground(at);
        int fx = at.getBlockX();
        int fy = at.getBlockY();
        int fz = at.getBlockZ();
        int rise = (int) Math.round(g.getY() - fy);
        if (underground) {
            rise = Math.max(rise, 2); // out of the cave first
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            int[] dir = Recovery.direction(at.getX(), at.getZ(), g.getX(), g.getZ(), turn + attempt);
            Recovery.Step step = Recovery.next(grid, fx, fy, fz, dir[0], dir[1], rise, underground,
                    blocks() > spareBlocks());
            if (step.kind() == Recovery.Kind.NONE) {
                continue;
            }
            if (attempt > 0) {
                turn += attempt; // that side worked: keep going that way a while
            }
            perform(now, b, step);
            return;
        }
        // boxed in on every side: hop and try again
        if (legs.isOnGround()) {
            legs.setVelocity(legs.getVelocity().setY(0.42));
        }
        turn++;
    }

    private void perform(long now, BotBody b, Recovery.Step step) {
        World world = b.legs().getWorld();
        recoverSteps++;
        recoveries.merge(step.kind(), 1, Integer::sum);
        for (int[] cell : step.breaks()) {
            Block block = world.getBlockAt(cell[0], cell[1], cell[2]);
            if (host.arena().contains(block.getLocation().add(0.5, 0, 0.5))) {
                face(b.body(), block.getLocation().add(0.5, 0.5, 0.5));
                dig(block);
            }
        }
        if (step.kind() == Recovery.Kind.PILLAR) {
            int[] cell = step.places().getFirst();
            pillarCell = cell;
            stepSince = now;
            b.legs().setVelocity(new Vector(0, 0.42, 0));
            face(b.body(), new Location(world, cell[0] + 0.5, cell[1] - 0.5, cell[2] + 0.5));
            return;
        }
        for (int[] cell : step.places()) {
            face(b.body(), new Location(world, cell[0] + 0.5, cell[1] + 0.5, cell[2] + 0.5));
            placeAny(b.body(), cell[0], cell[1], cell[2], material(BasePlanner.Part.WALL));
        }
        stepTo = step.to();
        stepSince = now;
    }

    /** Moves the legs into an adjacent cell it has cleared, if nothing solid is in the way there. */
    private void nudge(WanderingTrader legs, int[] to) {
        Location at = legs.getLocation();
        if (Math.abs(at.getBlockX() - to[0]) > 1 || Math.abs(at.getBlockZ() - to[2]) > 1
                || Math.abs(at.getBlockY() - to[1]) > 1) {
            return;
        }
        World world = at.getWorld();
        if (world.hasCollisionsIn(standing(to[0] + 0.5, to[1], to[2] + 0.5))
                || !world.getBlockAt(to[0], to[1] - 1, to[2]).getType().isSolid()) {
            return;
        }
        legs.teleport(new Location(world, to[0] + 0.5, to[1], to[2] + 0.5, at.getYaw(), at.getPitch()));
        legs.setVelocity(new Vector());
    }

    /** The gait it moves with now (for tests). */
    public Gait gait() {
        return gait;
    }

    /** The pathfinder speed of its last path (for tests). */
    public double pathSpeed() {
        return pathSpeed;
    }

    private void stop() {
        BotBody b = body;
        goal = null;
        if (b != null && b.legs().getPathfinder().hasPath()) {
            b.legs().getPathfinder().stopPathfinding();
        }
    }

    private double speedFactor() {
        BotBody b = body;
        AttributeInstance speed = b == null ? null : b.body().getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null || speed.getBaseValue() <= 0) {
            return traits.speedFactor();
        }
        return Math.max(0.3, Math.min(3.0, speed.getValue() / speed.getBaseValue() * traits.speedFactor()));
    }

    private Location insideBorder(Location where) {
        // where the border is heading (like the brain's margin), less the brain's safety margin and a block: a
        // destination never makes it want to flee the border
        BotBody b = body;
        double ahead = aheadHalf(b == null ? off(where) : off(b.legs().getLocation()));
        double half = Math.max(1.5, ahead - BotBrain.edgeMargin(host.borderShrinking(), host.finalHalf()) - 1);
        double x = clamp(where.getX(), host.borderCenterX() - half, host.borderCenterX() + half);
        double z = clamp(where.getZ(), host.borderCenterZ() - half, host.borderCenterZ() + half);
        Location out = where.clone();
        out.setX(x);
        out.setZ(z);
        if (!host.arena().contains(out)) {
            out = host.arena().clampInside(out);
        }
        return out;
    }

    /**
     * Farming: a bot without a base roams; one with a finished base stays near it (farms around it) and is back
     * inside when the fight starts.
     */
    private void farm(long now, Mannequin self) {
        BasePlanner.Plan home = base;
        boolean follows = follower() && home != null;
        if (home == null || !traits.builds() && !follows || host.fight() || !follows && !baseFinished()) {
            roam(now, self, 40 * traits.roam());
            return;
        }
        Location center = new Location(self.getWorld(), home.x() + 0.5, home.floorY() + 1, home.z() + 0.5);
        if (host.farmingTicksLeft() / (double) Math.max(1, host.timeScale()) > HOME_BEFORE_FIGHT_TICKS) {
            if (traits.traps() && (home.design() == BasePlanner.Design.TRAPS || follows && built >= home.closure())
                    && trapsLaid < trapCap()) {
                layTraps(now, self, home); // a trapper fills its surroundings with traps
                return;
            }
            if (wander == null || now >= wanderUntil || wander.distance(self.getLocation()) < 2) {
                // around the base, never onto its roof
                double angle = random.nextDouble() * Math.PI * 2;
                double r = home.half() + 4 + random.nextDouble() * 16;
                wander = ground(self.getWorld(), center.getX() + Math.cos(angle) * r,
                        center.getZ() + Math.sin(angle) * r);
                wanderUntil = now + 200 + random.nextInt(200);
            }
            goTo(now, wander, Gait.WALK);
        } else if (center.distance(self.getLocation()) > 0.8) {
            goTo(now, center, Gait.SPRINT); // home before the fight starts
        } else {
            stop();
        }
    }

    /** Wanders: legs of {@code range} around where it is, drifting towards the middle. */
    private void roam(long now, Mannequin self, double range) {
        if (wander == null || now >= wanderUntil || wander.distance(self.getLocation()) < 2) {
            Location at = self.getLocation();
            // a roaming bot drifts towards the middle (where the match ends) and away from the edge
            double pull = host.fight() ? 0.2 : 0.1;
            wander = ground(self.getWorld(), at.getX() + (host.borderCenterX() - at.getX()) * pull
                            + (random.nextDouble() * 2 - 1) * range,
                    at.getZ() + (host.borderCenterZ() - at.getZ()) * pull + (random.nextDouble() * 2 - 1) * range);
            wanderUntil = now + 200 + random.nextInt(200);
        }
        goTo(now, wander, Gait.WALK);
    }

    /**
     * Looks for someone: roams, but each leg of the walk is pulled towards the final zone (more as the fight goes
     * on), where everyone ends up; so bots spread over a big world still meet.
     */
    private void scout(long now, Mannequin self) {
        if (wander == null || now >= wanderUntil || wander.distance(self.getLocation()) < 2) {
            Location at = self.getLocation();
            // an idle bot (no fight for a minute) and the last few in the final zone head straight for the middle
            boolean hunt = finale() || idle(now) >= 1;
            double pull = hunt ? 0.9 : scoutPull(host.pressure());
            double spread = hunt ? Math.max(4, Math.min(24, host.borderHalfIn(0) * 0.5)) : 24;
            double x = at.getX() + (host.borderCenterX() - at.getX()) * pull + (random.nextDouble() * 2 - 1) * spread;
            double z = at.getZ() + (host.borderCenterZ() - at.getZ()) * pull + (random.nextDouble() * 2 - 1) * spread;
            wander = ground(self.getWorld(), x, z);
            wanderUntil = now + (hunt ? 160 : 300);
        }
        goTo(now, wander, Gait.SPRINT);
    }

    /** The share of the way to the zone center a scouting leg covers (0.3 at the fight start, 0.8 at the end). */
    static double scoutPull(double pressure) {
        return 0.3 + 0.5 * Math.max(0, Math.min(1, pressure));
    }

    /**
     * The standing spot of column {@code x, z}; over water, lava or a hazard (powder snow, cactus, magma ...) the
     * nearest dry, harmless ground near it if any.
     */
    static Location ground(World world, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        Block top = world.getHighestBlockAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Location spot = new Location(world, x, top.getY() + 1, z);
        if (top.isLiquid() || Hazards.hazardousColumn(world, bx, bz)) {
            Location dry = nearestDry(spot, 16); // walk around lakes and snow pits, not through them
            if (dry != null) {
                return dry;
            }
        }
        return spot;
    }

    private static Location away(Location from, Location threat, double distance) {
        Vector out = from.toVector().subtract(threat.toVector()).setY(0);
        if (out.lengthSquared() < 1.0E-6) {
            out = new Vector(1, 0, 0);
        }
        return from.clone().add(out.normalize().multiply(distance));
    }

    /** Means to look at {@code at} this tick (the head turns there at its turn speed, see {@link #look}). */
    private void face(Mannequin self, Location at) {
        Location eye = self.getEyeLocation();
        double dx = at.getX() - eye.getX();
        double dy = at.getY() - eye.getY();
        double dz = at.getZ() - eye.getZ();
        if (dx * dx + dy * dy + dz * dz < 1.0E-6) {
            return;
        }
        wantYaw = BotAim.yaw(dx, dz, yaw);
        wantPitch = BotAim.pitch(dx, dy, dz);
    }

    /** Means to look along {@code direction} this tick (the bow's launch, arc included). */
    private void faceAlong(double[] direction) {
        wantYaw = BotAim.yaw(direction[0], direction[2], yaw);
        wantPitch = BotAim.pitch(direction[0], direction[1], direction[2]);
    }

    /** True when the head points at {@code target} closely enough to land a swing (within 60°). */
    private boolean aimedAt(Mannequin self, LivingEntity target) {
        Location eye = self.getEyeLocation();
        Location to = target.getLocation();
        float wanted = BotAim.yaw(to.getX() - eye.getX(), to.getZ() - eye.getZ(), yaw);
        return Math.abs(BotAim.delta(yaw, wanted)) <= 60f;
    }

    /**
     * In water the legs swim like a player: they keep their head up (the vanilla float goal is gone with the others)
     * and swim towards the next point of the path at a player's swimming pace (a mob's own water movement is a slow
     * crawl along the bottom).
     */
    private void swim(BotBody b) {
        WanderingTrader legs = b.legs();
        if (!legs.isInWater()) {
            return;
        }
        Vector velocity = legs.getVelocity();
        velocity.setY(Math.max(velocity.getY(), 0.06));
        Location at = legs.getLocation();
        Location towards = null;
        if (goal != null && goal.getWorld() == at.getWorld()) {
            towards = goal;
            try {
                var path = legs.getPathfinder().getCurrentPath();
                Location next = path == null ? null : path.getNextPoint();
                if (next != null && next.getWorld() == at.getWorld()) {
                    towards = next.clone().add(0.5, 0, 0.5); // path points are block corners
                }
            } catch (RuntimeException ignored) {
                // no path information: swim for the goal
            }
        }
        if (towards != null) {
            Vector flat = new Vector(towards.getX() - at.getX(), 0, towards.getZ() - at.getZ());
            if (flat.lengthSquared() > 0.04) {
                double pace = (gait == Gait.WALK || gait == Gait.SNEAK ? BotMotion.SWIM_WALK : BotMotion.SWIM_SPRINT)
                        * Math.max(0.5, Math.min(2, speedFactor()));
                flat.normalize().multiply(pace);
                velocity.setX(flat.getX());
                velocity.setZ(flat.getZ());
            }
        }
        legs.setVelocity(velocity);
    }

    /**
     * Never stays inside a block: if anything solid overlaps where the body stands (a block placed on it, a wall it
     * was pushed into, sand that fell on it), it first breaks its own blocks there (a player mines out of their own
     * shelter), then steps out to the nearest free standing spot. Runs every tick before the body is moved onto the
     * legs, so the body never takes suffocation damage from it.
     *
     * @return true if it had to get out
     */
    boolean unstuck(long now, BotBody b) {
        WanderingTrader legs = b.legs();
        World world = legs.getWorld();
        Location at = legs.getLocation();
        BoundingBox box = standing(at.getX(), at.getY(), at.getZ());
        if (!world.hasCollisionsIn(box)) {
            return false;
        }
        boolean broke = false;
        Iterator<Map.Entry<Block, Material>> it = placed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Block, Material> own = it.next();
            Block block = own.getKey();
            if (block.getWorld().equals(world) && box.overlaps(cell(block))) {
                if (block.getType() == own.getValue()) {
                    block.setType(Material.AIR);
                    broke = true;
                }
                it.remove();
                fortress.remove(block);
            }
        }
        if (broke && !world.hasCollisionsIn(box)) {
            return true;
        }
        int[] spot = BotMotion.freeSpot(new BotMotion.Space() {
            @Override
            public boolean free(int x, int y, int z) {
                return !world.hasCollisionsIn(standing(x + 0.5, y, z + 0.5))
                        && !Hazards.hazard(world.getBlockAt(x, y, z))
                        && !Hazards.hazard(world.getBlockAt(x, y + 1, z));
            }

            @Override
            public boolean floor(int x, int y, int z) {
                Block block = world.getBlockAt(x, y, z);
                return block.getType().isSolid() || block.isLiquid();
            }
        }, at.getBlockX(), at.getBlockY(), at.getBlockZ(), 3);
        if (spot != null) {
            Location out = new Location(world, spot[0] + 0.5, spot[1], spot[2] + 0.5, at.getYaw(), at.getPitch());
            legs.teleport(out);
            legs.setVelocity(new Vector());
            nextPath = now; // walk on from there
        }
        return true;
    }

    /** The box a standing body fills with its feet at {@code (x, y, z)}, a hair smaller than the body. */
    static BoundingBox standing(double x, double y, double z) {
        return new BoundingBox(x - 0.29, y + 0.01, z - 0.29, x + 0.29, y + 1.79, z + 0.29);
    }

    private static BoundingBox cell(Block block) {
        return new BoundingBox(block.getX(), block.getY(), block.getZ(), block.getX() + 1, block.getY() + 1,
                block.getZ() + 1);
    }

    /** Stuck in place while it should walk: hop, then pick another way. */
    private void unstick(long now, BotBody b) {
        if (now - lastMoveCheck < 40 || recovering()) {
            return;
        }
        Location at = b.legs().getLocation();
        if (goal != null && lastPos != null && lastPos.getWorld() == at.getWorld()
                && lastPos.distanceSquared(at) < 0.04 && b.legs().isOnGround()) {
            b.legs().setVelocity(b.legs().getVelocity().setY(0.42));
            nextPath = now;
            wander = null;
        }
        lastPos = at;
        lastMoveCheck = now;
    }

    /** The body has no gravity of its own: falls of the legs hurt the body like a player's fall. */
    private void fall(BotBody b) {
        WanderingTrader legs = b.legs();
        float now = legs.getFallDistance();
        if (legs.isOnGround() && lastFall > 3.0f && !legs.isInWater()) {
            b.body().damage(lastFall - 3.0, DamageSource.builder(DamageType.FALL).build());
        }
        lastFall = legs.isOnGround() ? 0f : now;
    }

    /** Where {@code who} is as far as the bot knows: live while perceived, else where it was last seen or heard. */
    private Location where(UUID who, LivingEntity entity, long now) {
        Seen seen = memory.get(who);
        return seen == null || now - seen.tick() <= profile.reactionTicks() ? entity.getLocation() : seen.at().clone();
    }

    private @Nullable LivingEntity known(UUID who) {
        Seen seen = memory.get(who);
        return seen == null || seen.entity().isDead() ? null : seen.entity();
    }

    private @Nullable Location lastKnown() {
        Seen best = null;
        for (Seen seen : memory.values()) {
            if (best == null || seen.distance() < best.distance()) {
                best = seen;
            }
        }
        return best == null ? null : best.at();
    }

    private @Nullable Location nearestThreat() {
        return lastKnown();
    }

    /**
     * Where to look for someone: the nearest rival it remembers, else where it last saw or heard one (for a minute),
     * else the nearest fight it hears; null if it has no clue (then it drifts to the zone). A lead it has reached and
     * found empty is dropped.
     */
    private @Nullable Location trail(long now, Location at) {
        Location known = lastKnown();
        if (known != null) {
            return known;
        }
        Location grudge = grudgeLead;
        if (traits.grudges() && grudge != null && now - grudgeLeadAt <= 2 * LEAD_TICKS
                && grudge.getWorld() == at.getWorld() && grudge.distanceSquared(at) >= 9) {
            return grudge; // an avenger follows the one it holds a grudge against
        }
        Location last = lead;
        if (last != null && (now - leadAt > LEAD_TICKS || last.getWorld() != at.getWorld()
                || last.distanceSquared(at) < 9)) {
            lead = null;
            last = null;
        }
        if (last != null) {
            return last;
        }
        return manager.heardFight(id, at, now, FIGHT_HEARING);
    }

    /** What it drops when it dies: its gear, apples and arrows left. */
    List<ItemStack> loot() {
        List<ItemStack> out = new ArrayList<>();
        String[] pieces = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
        for (int slot = 0; slot < 4; slot++) {
            ItemStack item = BotBody.item(loadout.armor(slot), pieces[slot]);
            if (item != null) {
                out.add(item);
            }
        }
        ItemStack sword = BotBody.sword(loadout.sword());
        if (sword != null) {
            out.add(sword);
        }
        if (apples > 0) {
            out.add(ItemStack.of(Material.GOLDEN_APPLE, apples));
        }
        if (loadout.bow()) {
            out.add(ItemStack.of(Material.BOW));
            if (arrows() > 0) {
                out.add(ItemStack.of(Material.ARROW, Math.min(64, arrows())));
            }
        }
        return out;
    }

    /** Forgets a combatant (eliminated). */
    void forget(UUID who) {
        memory.remove(who);
    }

    /** For tests: forces the next decision now. */
    void decideNow(long now) {
        think(now);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * {@link BasePlanner.Terrain} over a Bukkit world: the ground under the trees (a trunk in the way is chopped like
     * a bump, one in the wall stands in for a wall block).
     */
    record WorldTerrain(World world) implements BasePlanner.Terrain {
        @Override
        public int height(int x, int z) {
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            int floor = Math.max(world.getMinHeight(), y - 32);
            while (y > floor && tree(world.getBlockAt(x, y, z).getType())) {
                y--;
            }
            return y;
        }

        private static boolean tree(Material type) {
            return Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type) || type == Material.MANGROVE_ROOTS
                    || type == Material.BAMBOO || type == Material.VINE;
        }

        @Override
        public BasePlanner.Surface surface(int x, int z) {
            int y = height(x, z);
            if (y <= world.getMinHeight()) {
                return BasePlanner.Surface.NONE;
            }
            Material type = world.getBlockAt(x, y, z).getType();
            if (type == Material.LAVA) {
                return BasePlanner.Surface.LAVA;
            }
            if (Hazards.hazardousColumn(world, x, z)) {
                return BasePlanner.Surface.HAZARD;
            }

            if (type == Material.WATER || type == Material.BUBBLE_COLUMN || type == Material.ICE
                    || type == Material.KELP || type == Material.SEAGRASS) {
                return BasePlanner.Surface.WATER;
            }
            return BasePlanner.Surface.SOLID;
        }
    }
}
