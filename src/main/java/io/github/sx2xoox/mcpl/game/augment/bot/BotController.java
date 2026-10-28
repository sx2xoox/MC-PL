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
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
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
    private BasePlanner.@Nullable Plan base;
    private BasePlanner.@Nullable Palette palette;
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
    /** Placements it could not place yet (someone stood there): tried again once. */
    private int buildBlockedTicks;
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
        return Math.max(0, loadout.blocks() - blocksUsed);
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
        try {
            if (drowning(b.body())) {
                surface(now, b); // nothing else matters while out of air
            } else if (inPowderSnow(b)) {
                escapeSnow(now, b); // it freezes in there: out first
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
                || block.getType().isAir()) {
            return;
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
        decision = brain.decide(situation(now, b));
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
                    seen.distance() < seen.previous() - 0.5, holdsBow(rival), grudges.containsKey(entry.getKey())));
        }
        // the nearer of where the border is now and where it will be once it could have walked in (the farther out,
        // the earlier it heads in: terrain makes the way longer than the straight line)
        double off = Math.max(Math.abs(at.getX() - host.borderCenterX()), Math.abs(at.getZ() - host.borderCenterZ()));
        double margin = Math.min(host.borderHalfIn(0), host.borderHalfIn(host.borderShrinking()
                ? borderLookahead(off, host.finalHalf()) : 0)) - off;
        double zone = Math.hypot(at.getX() - host.borderCenterX(), at.getZ() - host.borderCenterZ());
        BasePlanner.Plan plan = base;
        boolean baseBuilt = relocating || plan != null && built >= plan.placements().size() / 2;
        boolean baseDone = baseFailed || plan != null && built >= plan.placements().size();
        double baseDistance = plan == null ? Double.POSITIVE_INFINITY
                : Math.hypot(at.getX() - (plan.x() + 0.5), at.getZ() - (plan.z() + 0.5));
        // while it relocates, the brain sees the lost base (built, unsafe): it keeps building the new one
        boolean baseSafe = !relocating && plan != null && baseSafeFor(plan);
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
        boolean atBase = d.behaviour() == Behaviour.DEFEND_BASE || d.behaviour() == Behaviour.BUILD_BASE
                || d.behaviour() == Behaviour.RETREAT_HEAL && baseUsable(self.getLocation());
        if (doorClosed && !atBase) {
            openDoor(); // out it goes: the doorway opens again
        }
        boolean sneak = false;
        switch (d.behaviour()) {
            case FARM -> {
                roam(now, self, 40 * traits.roam());
                defendAgainstMobs(now, self);
            }
            case BUILD_BASE -> buildBase(now, self);
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
                double distance = self.getLocation().distance(at);
                if (distance < 9) {
                    goTo(now, away(self.getLocation(), at, 12), Gait.SPRINT);
                } else if (distance > brain.bowRange() * 0.85) {
                    goTo(now, at, Gait.SPRINT);
                } else {
                    stop();
                }
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
                    if (threat != null) {
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
                    } else if (base != null && base.inside(self.getLocation().getX(), self.getLocation().getZ())) {
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
                if (distance < 14) {
                    goTo(now, away(self.getLocation(), at, 8), traits.sneaks() ? Gait.SNEAK : Gait.WALK);
                    sneak = traits.sneaks();
                } else if (distance > 24) {
                    goTo(now, at, Gait.SPRINT);
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
        if (self.isSneaking() != sneak) {
            self.setSneaking(sneak);
        }
        unstick(now, b);
    }

    /** The nearest point well inside where the border is heading (no need to cross the map to the center). */
    private Location safeFromBorder(Location at) {
        double ahead = Math.min(host.borderHalfIn(0), host.borderHalfIn(host.borderShrinking()
                ? borderLookahead(Math.max(Math.abs(at.getX() - host.borderCenterX()),
                Math.abs(at.getZ() - host.borderCenterZ())), host.finalHalf()) : 0));
        double half = Math.max(Math.min(host.finalHalf() * 0.6, ahead), ahead
                - BotBrain.edgeMargin(host.borderShrinking(), host.finalHalf()) - 8);
        double x = clamp(at.getX(), host.borderCenterX() - half, host.borderCenterX() + half);
        double z = clamp(at.getZ(), host.borderCenterZ() - half, host.borderCenterZ() + half);
        return ground(at.getWorld(), x, z);
    }

    /** True if {@code spot} stays inside where the border is heading. */
    private boolean insideSafe(Location spot) {
        double ahead = Math.min(host.borderHalfIn(0), host.borderHalfIn(host.borderShrinking() ? 400 : 0));
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
        if (!plan.inside(at.getX(), at.getZ())) {
            int[] door = plan.door().getFirst();
            if (doorClosed && Math.hypot(at.getX() - (door[0] + 0.5), at.getZ() - (door[2] + 0.5)) < 4) {
                openDoor();
            }
            goTo(now, new Location(world, inner[0] + 0.5, inner[1], inner[2] + 0.5), Gait.SPRINT);
            swingAtAnyone(now, self);
            return;
        }
        boolean holdDoorway = !loadout.bow() || arrows() <= 0 || traits.weight(Behaviour.ENGAGE) >= 1.1
                && !healing;
        boolean threat = target != null && !target.isDead();
        if (threat && target.getLocation().distance(at) <= REACH + 1.5) {
            face(self, target.getEyeLocation());
            tryMelee(now, self, target); // whoever gets in is fought
            goTo(now, target.getLocation(), Gait.WALK);
            return;
        }
        if (holdDoorway) {
            if (doorClosed && !healing) {
                openDoor();
            }
            if (!doorClosed && healing && threat) {
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
            }
            return;
        }
        if (threat && !doorClosed) {
            closeDoor(self);
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
        if (slit != null) {
            int sx = slit[0] - Integer.signum(slit[0] - plan.x());
            int sz = slit[2] - Integer.signum(slit[2] - plan.z());
            Location spot = new Location(world, sx + 0.5, plan.floorY() + 1, sz + 0.5);
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
        if (!traits.traps() || websLaid >= 8 || now < nextWebAt || threat.distance(self.getLocation()) > 8) {
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
        if (distance > REACH + 0.6) {
            goTo(now, at, Gait.SPRINT);
            if (distance > 10 && distance <= brain.bowRange() && shoot(now, b, self, target)) {
                return false;
            }
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
        boolean crit = random.nextDouble() < profile.critChance();
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
        if (b == null || !loadout.shield() || attacker == null || now < nextShieldAt
                || random.nextDouble() >= Math.min(0.9, profile.shieldChance() + traits.shieldBonus())) {
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
     * Builds its base a few blocks a second from inside it. A block goes on only once it is really there (or the spot
     * is already solid): out of blocks it waits for the next farmed batch instead of leaving a hole, and a spot
     * someone stands in is tried again a moment later. In the fight, a base the border will swallow is given up and a
     * new one is built nearer the middle.
     */
    private void buildBase(long now, Mannequin self) {
        BasePlanner.Plan plan = base;
        if (plan != null && host.fight() && !baseSafeFor(plan)) {
            openDoor();
            base = null;
            plan = null;
            built = 0;
            baseFailed = false;
            relocating = true;
        }
        if (plan == null) {
            plan = planBase(self);
            if (plan == null) {
                baseFailed = true;
                think(now);
                return;
            }
        }
        Location center = new Location(self.getWorld(), plan.x() + 0.5, plan.floorY() + 1, plan.z() + 0.5);
        if (center.distance(self.getLocation()) > 3) {
            goTo(now, center, Gait.SPRINT);
            return;
        }
        stop();
        buildCredit += profile.buildPerSecond() * Math.max(1, host.timeScale()) / 20.0;
        while (buildCredit >= 1 && built < plan.placements().size()) {
            BasePlanner.Placement next = plan.placements().get(built);
            Block block = self.getWorld().getBlockAt(next.x(), next.y(), next.z());
            if (next.part() == BasePlanner.Part.CLEAR) {
                buildCredit--;
                built++;
                if (!block.getType().isAir() && host.arena().contains(block.getLocation().add(0.5, 0, 0.5))) {
                    face(self, block.getLocation().add(0.5, 0.5, 0.5));
                    dig(block); // levelling the floor
                }
                continue;
            }
            Material want = material(next.part());
            if (block.getType().isSolid() && !Hazards.hazard(block.getType()) || block.getType() == want) {
                built++; // already closed (terrain, a tree, its own block)
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
            if (place(self, next.x(), next.y(), next.z(), want) || placeAny(self, next.x(), next.y(), next.z(), want)) {
                built++;
                buildBlockedTicks = 0;
                face(self, new Location(self.getWorld(), next.x() + 0.5, next.y() + 0.5, next.z() + 0.5));
            } else if (++buildBlockedTicks > 60) {
                built++; // someone keeps standing there: on with the rest
                buildBlockedTicks = 0;
            } else {
                break;
            }
        }
        if (built >= plan.placements().size()) {
            buildCredit = 0;
            relocating = false;
        } else {
            BasePlanner.Placement next = plan.placements().get(built); // eyes on where the next block goes
            face(self, new Location(self.getWorld(), next.x() + 0.5, next.y() + 0.5, next.z() + 0.5));
        }
    }

    /** The border leaves {@code plan} alone for the next half minute. */
    private boolean baseSafeFor(BasePlanner.Plan plan) {
        return host.borderHalfIn(600) - Math.max(Math.abs(plan.x() - host.borderCenterX()),
                Math.abs(plan.z() - host.borderCenterZ())) > plan.half() + 2;
    }

    private Material material(BasePlanner.Part part) {
        BasePlanner.Palette p = palette != null ? palette : BasePlanner.Palette.forBiome(null);
        Material material = switch (part) {
            case WALL, FLOOR, CLEAR -> Material.matchMaterial(p.wall());
            case PILLAR -> Material.matchMaterial(p.pillar());
            case ROOF -> Material.matchMaterial(p.roof());
            case CHEST -> Material.CHEST;
            case CRAFTING_TABLE -> Material.CRAFTING_TABLE;
            case COBWEB -> Material.COBWEB;
        };
        return material != null && material.isBlock() ? material : Material.COBBLESTONE;
    }

    /** The side of its base: the difficulty's, bigger for a fortress builder, sometimes a size up or down. */
    int baseSize() {
        int size = profile.baseSize() + traits.baseSizeBonus() + (random.nextInt(4) == 0 ? 2 : 0);
        return Math.max(5, Math.min(11, size | 1));
    }

    /**
     * Picks its base site: candidates around itself and towards the middle (a fortress builder or a survivor looks
     * near the final zone), only where the border will still be for a good while, on harmless ground; its personality
     * sets how much nearness to the middle counts.
     */
    private BasePlanner.@Nullable Plan planBase(Mannequin self) {
        World world = self.getWorld();
        Location at = self.getLocation();
        double cx = host.borderCenterX();
        double cz = host.borderCenterZ();
        double pull = traits.centerPull();
        int size = baseSize();
        List<int[]> candidates = new ArrayList<>();
        double nowHalf = host.borderHalfIn(0);
        double finalHalf = host.finalHalf();
        double safeHalf = finalHalf + Math.max(0, nowHalf - finalHalf) * (0.6 - 0.45 * pull) + size;
        for (int i = 0; i < 28; i++) {
            Location spot;
            if (i < 8 * pull) {
                // near the middle
                double r = finalHalf + 8 + (nowHalf - finalHalf) * 0.2;
                spot = new Location(world, cx + (random.nextDouble() * 2 - 1) * r, at.getY(),
                        cz + (random.nextDouble() * 2 - 1) * r);
            } else {
                // around itself, drawn towards where the border will end
                double towards = 0.15 + random.nextDouble() * 0.35 + 0.4 * pull;
                spot = new Location(world, at.getX() + (cx - at.getX()) * towards + (random.nextDouble() * 2 - 1) * 24,
                        at.getY(), at.getZ() + (cz - at.getZ()) * towards + (random.nextDouble() * 2 - 1) * 24);
            }
            if (host.arena().contains(spot)) {
                candidates.add(new int[] {spot.getBlockX(), spot.getBlockZ()});
            }
        }
        candidates.add(new int[] {at.getBlockX(), at.getBlockZ()});
        WorldTerrain terrain = new WorldTerrain(world);
        double zoneWeight = 0.04 + 0.25 * pull;
        int[] spot = null;
        // the size it wants, else smaller ones (rough or wet ground), else anywhere the border allows less long
        for (int tried = size; spot == null && tried >= 5; tried -= 2) {
            spot = BasePlanner.choose(terrain, candidates, tried, cx, cz, manager.baseCenters(id), zoneWeight,
                    safeHalf);
            if (spot != null) {
                size = tried;
            }
        }
        if (spot == null) {
            spot = BasePlanner.choose(terrain, candidates, 5, cx, cz, manager.baseCenters(id));
            size = 5;
        }
        if (spot == null) {
            return null;
        }
        BasePlanner.Plan plan = BasePlanner.plan(terrain, spot[0], spot[1], size, cx, cz, traits.traps());
        base = plan;
        int y = terrain.height(spot[0], spot[1]);
        palette = BasePlanner.Palette.forBiome(world.getBiome(spot[0], y, spot[1]).getKey().toString());
        return plan;
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
        int safe = safePrefix(path.getPoints());
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
                    && path.getFinalPoint().distanceSquared(spot) < 16 && safePrefix(path.getPoints())
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
        for (int i = 0; i < points.size(); i++) {
            Block feet = points.get(i).getBlock();
            if (Hazards.hazard(feet) || Hazards.hazard(feet.getRelative(0, -1, 0))
                    || Hazards.hazard(feet.getRelative(0, 1, 0))) {
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
        if (recovering() || b == null) {
            return;
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
        return plan == null ? 48 : Math.max(0, Math.min(64, plan.placements().size() - built));
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
            if (path != null && path.canReachFinalPoint() && safePrefix(path.getPoints()) == path.getPoints().size()) {
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
        double ahead = Math.min(host.borderHalfIn(0), host.borderHalfIn(host.borderShrinking() ? 400 : 0));
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

    /** {@link BasePlanner.Terrain} over a Bukkit world. */
    record WorldTerrain(World world) implements BasePlanner.Terrain {
        @Override
        public int height(int x, int z) {
            return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
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
