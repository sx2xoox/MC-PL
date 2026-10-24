package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Lidded;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 미믹 — the treasure chest of fantasy dungeons that bites the adventurer who opens it, in a game where loot chests lie
 * all over the arena. Standing still, the mimic turns into a chest exactly like a loot chest; the active throws bait
 * chests that snap at whoever lands next to, opens or kicks them; the ultimate scatters a whole pile of chests and
 * hides among them — some hold real treasure, some bite, and one of them is the mimic.
 *
 * <p>The chests are real (temporary) chest blocks, so they look, sparkle and collide exactly like the loot chests;
 * opening or punching them is handled by this ability's own listener, after the arena protection has denied the
 * vanilla use (the same pattern as the loot chests). Like a loot chest, a mimic chest never opens for a click with the
 * ability item (that click is the clicker's own skill) or while sneaking with something in hand.
 *
 * <p>Unlike the stealth of the 그림자 or the traps of the 사냥꾼, the mimic hides in plain sight as an object everybody
 * can see, and only bites the one who decides to open it: the counterplay is suspicion (punch or shoot a chest from
 * farther away, it bites back less).
 *
 * <p>Look: oak brown, gold trim and a pink tongue. A bite throws the lid open, flashes a row of white fangs along the rim
 * and lashes a pink tongue out at the victim, which stays wrapped round its waist while it is held. Thrown chests spin in
 * a lob trailing wood dust and gold glints; the ultimate bursts chests out in every direction under a cloud of smoke and
 * gold, with little markers only the mimic's team can read.
 */
public final class MimicAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("mimic", "미믹", Material.CHEST)
            .color(TextColor.color(0xE3B23C))
            .difficulty(1)
            .passive("보물 상자 흉내",
                    "{disguise_delay}초 동안 가만히 서 있으면 전리품 상자와 똑같은 보물 상자로 변신합니다. 적의 눈에는 내 몸과 "
                            + "이름이 사라지고 상자만 보이며, 움직이거나 공격하거나 피해를 받으면 풀립니다. 그 상자를 연(우클릭한) "
                            + "적은 덥석 물려 {disguise_bite} 피해를 받고 {disguise_grab}초간 붙잡힙니다(때리면 물지 않고 정체만 "
                            + "드러납니다). 상자에서 튀어나온 뒤 "
                            + "{ambush_window}초 안의 첫 근접 공격은 {ambush_bonus} 추가 피해를 줍니다.")
            .active("미끼 상자",
                    "조준한 곳({bait_range}블록 이내)에 보물 상자를 던집니다. 떨어진 자리 반경 {land_radius}블록의 적은 뚜껑에 "
                            + "물려 {land_damage} 피해를 받고 {land_grab}초간 붙잡힙니다. 상자는 {bait_duration}초간 전리품 "
                            + "상자처럼 반짝이며 미끼로 남습니다(최대 {max_baits}개). 적이 열면 덥석 {bait_bite} 피해와 "
                            + "{bait_grab}초 붙잡기를, 적이 때리거나 쏘면 되물어 {bait_snap} 피해를 주고 사라집니다.",
                    9)
            .ultimate("보물 무더기",
                    "주위 {pile_radius}블록에 보물 상자를 뿌리고(진짜 보물 {pile_treasures}개, 가짜 {pile_fakes}개) 연기 속에서 "
                            + "나도 그중 하나로 숨습니다(최대 {pile_duration}초). 상자는 열거나 때려야 정체가 드러납니다: 진짜 "
                            + "보물은 연 사람을 {treasure_heal} 회복시키고 금화를 쏟고, 가짜는 덥석 {fake_bite} 피해와 "
                            + "{fake_grab}초 붙잡기, 나는 덥석 {mimic_bite} 피해와 {mimic_grab}초 붙잡기를 주며 튀어나와 "
                            + "{dash_duration}초간 이동 속도가 {dash_speed}% 빨라집니다. 나와 아군 눈에는 상자마다 작은 표시가 "
                            + "보입니다. 움직이면 언제든 튀어나오고, 남은 상자는 {leftover}초 뒤 사라집니다.")
            .ultimateNotStandalone()
            .param("disguise_delay", 1.5)
            .param("disguise_bite", 6)
            .param("disguise_grab", 1.5)
            .param("ambush_window", 2)
            .param("ambush_bonus", 3)
            .param("bait_range", 12)
            .param("land_radius", 1.5)
            .param("land_damage", 3)
            .param("land_grab", 0.75)
            .param("bait_duration", 15)
            .param("max_baits", 2)
            .param("bait_bite", 5)
            .param("bait_grab", 1.5)
            .param("bait_snap", 2)
            .param("pile_radius", 6)
            .param("pile_treasures", 2)
            .param("pile_fakes", 2)
            .param("pile_duration", 8)
            .param("treasure_heal", 2)
            .param("fake_bite", 3)
            .param("fake_grab", 0.75)
            .param("mimic_bite", 7)
            .param("mimic_grab", 1.5)
            .param("dash_duration", 3)
            .param("dash_speed", 20)
            .param("leftover", 3)
            .build();

    private static final String GOLD = "#E3B23C";
    private static final String OAK = "#B07A44";
    private static final String PINK = "#E5778C";
    private static final String RED = "#E5484D";

    // ------------------------------------------------------------------ look: oak, gold trim, pink tongue
    /** Gold trim, oak brown, dark wood. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE7A0, 0xE3B23C, 0x5A3A1C);
    private static final Color GOLD_COLOR = Color.fromRGB(0xE3B23C);
    private static final Color PINK_COLOR = Color.fromRGB(0xE5778C);
    private static final Particle.DustOptions GOLD_DUST = PALETTE.dust(0.8f);
    private static final Particle.DustOptions GOLD_SMALL = PALETTE.dust(0.5f);
    private static final Particle.DustOptions OAK_DUST = new Particle.DustOptions(Color.fromRGB(0x8A5A2B), 1.0f);
    private static final Particle.DustOptions PINK_DUST = new Particle.DustOptions(PINK_COLOR, 0.8f);
    private static final Particle.DustOptions DROOL = new Particle.DustOptions(Color.fromRGB(0xF4B6C2), 0.5f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);

    /** Block and item types of the visuals; block data and items are created on use (they need a running server). */
    private static final Material CHEST = Material.CHEST;
    private static final Material PLANKS = Material.OAK_PLANKS;
    private static final Material FANG = Material.QUARTZ_BLOCK;
    private static final Material TONGUE = Material.PINK_WOOL;
    private static final Material COIN = Material.GOLD_NUGGET;

    /** Display entities one mimic keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    private static final double REST_FALL = -Motion.GRAVITY * Motion.DRAG;

    // passive: the disguise
    /** Moving farther than this (blocks, horizontally) between two ticks breaks the stillness. */
    private static final double STILL_MOVE = 0.03;
    private static final double STILL_FALL = 0.06;
    private static final long DISGUISE_RETRY = 10;
    /** The longest a disguise lasts before the mimic stretches its legs (its temporary block expires). */
    private static final long DISGUISE_MAX = 20 * 45;
    private static final long CREAK_PERIOD = 100;
    private static final long SPARKLE_PERIOD = 20;
    private static final double SPARKLE_RADIUS = 6;
    private static final long PROMPT_PERIOD = 20;

    // the bite
    private static final long LID_SHUT = 10;
    private static final int FANGS = 5;
    private static final float FANG_SIZE = 0.09f;
    private static final float TONGUE_WIDTH = 0.16f;
    private static final float TONGUE_THICK = 0.05f;

    // active: the bait
    private static final double THROW_SPEED = 1.0;
    private static final long MIN_FLIGHT = 6;
    private static final long MAX_FLIGHT = 18;
    private static final double MAX_DROP = 20;
    private static final float THROWN_SCALE = 0.9f;

    // ultimate: the pile
    private static final int SPOT_TRIES = 40;
    private static final double SPOT_GAP = 1.6;
    private static final long PILE_FLIGHT = 10;
    private static final double MARKER_ABOVE = 1.25;

    private enum Kind {
        /** The passive disguise: the mimic itself. */
        DISGUISE,
        /** A bait the active threw. */
        BAIT,
        /** The ultimate's real treasure. */
        TREASURE,
        /** The ultimate's fake that bites. */
        FAKE,
        /** The ultimate's chest the mimic hides in. */
        SELF
    }

    /** One chest block of the mimic. */
    private static final class FakeChest {
        final Block block;
        final Kind kind;
        final long expires;
        @Nullable TextDisplay marker;
        boolean gone;

        FakeChest(Block block, Kind kind, long expires) {
            this.block = block;
            this.kind = kind;
            this.expires = expires;
        }

        Location center() {
            return block.getLocation().add(0.5, 0.45, 0.5);
        }

        boolean isMimic() {
            return kind == Kind.DISGUISE || kind == Kind.SELF;
        }
    }

    private final Map<Block, FakeChest> chests = new HashMap<>();
    private final Deque<FakeChest> baits = new ArrayDeque<>();
    private final Map<UUID, Long> lastPunch = new HashMap<>();
    private final List<Display> live = new ArrayList<>();
    private final Stealth stealth = new Stealth();
    private @Nullable Hooks hooks;
    private @Nullable NamespacedKey abilityItemKey;
    private @Nullable FakeChest hideout;
    private @Nullable Pile pile;
    private @Nullable Location lastSpot;
    private long stillSince;
    private long nextTry = Long.MIN_VALUE / 2;
    private long ambushUntil = Long.MIN_VALUE / 2;
    private @Nullable NamespacedKey dash;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        abilityItemKey = new NamespacedKey(ctx().plugin(), "ability_item");
        Hooks listener = new Hooks();
        Bukkit.getPluginManager().registerEvents(listener, ctx().plugin());
        hooks = listener;
        stillSince = ctx().currentTick();
    }

    @Override
    public void onRevoke() {
        Hooks listener = hooks;
        hooks = null;
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        for (FakeChest chest : new ArrayList<>(chests.values())) {
            chest.gone = true;
            ctx().restoreTempBlock(chest.block);
        }
        chests.clear();
        baits.clear();
        stealth.end(ctx(), player());
        clearInvisibility(player());
        hideout = null;
        pile = null;
        lastPunch.clear();
        live.clear();
    }

    // ================================================================== chests

    /**
     * Places a chest of {@code kind} in {@code block} facing {@code facing} for {@code ticks}: only into air or a
     * replaceable block on solid ground, inside the arena, with nobody standing in it (but the mimic itself, for its own
     * chest). Null if it could not be placed.
     */
    private @Nullable FakeChest place(Block block, BlockFace facing, Kind kind, long ticks) {
        if (chests.containsKey(block) || !(block.isEmpty() || block.isReplaceable())
                || !block.getRelative(BlockFace.DOWN).isCollidable() || !ctx().isInsideArena(block.getLocation()
                .add(0.5, 0, 0.5)) || isOccupied(block, kind == Kind.DISGUISE || kind == Kind.SELF)) {
            return null;
        }
        BlockData data = CHEST.createBlockData();
        if (data instanceof Directional directional && directional.getFaces().contains(facing)) {
            directional.setFacing(facing);
        }
        if (!ctx().placeTempBlock(block, data, ticks)) {
            return null;
        }
        FakeChest chest = new FakeChest(block, kind, ctx().currentTick() + ticks);
        chests.put(block, chest);
        return chest;
    }

    /** True if a living entity (other than the mimic, if {@code selfAllowed}) stands in the block. */
    private boolean isOccupied(Block block, boolean selfAllowed) {
        BoundingBox box = BoundingBox.of(block);
        for (Entity entity : block.getWorld().getNearbyEntities(box)) {
            if (entity instanceof LivingEntity && !(selfAllowed && entity.equals(player()))) {
                return true;
            }
        }
        return false;
    }

    /** Takes a chest away (its block comes back at once) with a puff of wood dust. */
    private void removeChest(FakeChest chest, boolean puff) {
        if (chest.gone) {
            return;
        }
        chest.gone = true;
        chests.remove(chest.block);
        baits.remove(chest);
        remove(chest.marker);
        chest.marker = null;
        ctx().restoreTempBlock(chest.block);
        if (puff) {
            Location center = chest.center();
            World world = center.getWorld();
            world.spawnParticle(Particle.POOF, center, Vfx.n(ctx(), 8), 0.3, 0.25, 0.3, 0.03);
            world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 10), 0.3, 0.25, 0.3, 0.1,
                    PLANKS.createBlockData());
            Fx.sound(center, Sound.BLOCK_WOOD_BREAK, 0.8f, 1.1f + Fx.jitter(0.1f));
        }
    }

    private @Nullable FakeChest chestAt(@Nullable Block block) {
        return block == null ? null : chests.get(block);
    }

    /** The lid of a chest flips open (and shuts again {@link #LID_SHUT} ticks later). */
    private void flipLid(FakeChest chest) {
        if (chest.gone || !(chest.block.getState(false) instanceof Lidded lidded)) {
            return;
        }
        lidded.open();
        ctx().runLater(player(), LID_SHUT, () -> {
            if (!chest.gone && chest.block.getState(false) instanceof Lidded later) {
                later.close();
            }
        });
    }

    // ================================================================== the listener

    /**
     * Clicks, punches and shots on the mimic's chests, and the mimic's own moves while it is disguised. Runs after the
     * arena protection (which denies opening containers) like the loot chests' listener.
     */
    private final class Hooks implements Listener {

        @EventHandler(priority = EventPriority.HIGH)
        public void onInteract(PlayerInteractEvent event) {
            Block clicked = event.getClickedBlock();
            FakeChest chest = chestAt(clicked);
            if (chest == null || event.getHand() != EquipmentSlot.HAND) {
                if (chest != null) {
                    event.setUseInteractedBlock(Event.Result.DENY);
                }
                return;
            }
            Player player = event.getPlayer();
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                event.setUseInteractedBlock(Event.Result.DENY);
                if (opensOnClick(player)) {
                    event.setUseItemInHand(Event.Result.DENY);
                    opened(player, chest);
                }
            } else if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                punched(player, chest);
            }
        }

        /** A punch at a chest in adventure mode only shows as an arm swing: find what the swing was aimed at. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onSwing(PlayerArmSwingEvent event) {
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }
            Player player = event.getPlayer();
            if (player.equals(player())) {
                if (hideout != null) {
                    popOut("<gray>공격하려다 정체가 드러났습니다</gray>", false);
                }
                return;
            }
            if (chests.isEmpty()) {
                return;
            }
            FakeChest chest = aimedChest(player);
            if (chest != null) {
                punched(player, chest);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onProjectileHit(ProjectileHitEvent event) {
            FakeChest chest = chestAt(event.getHitBlock());
            ProjectileSource shooter = event.getEntity().getShooter();
            if (chest != null && shooter instanceof Player player) {
                punched(player, chest);
            }
        }

        /** The mimic pressing any movement key breaks the disguise at once, before its body even moves. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onInput(PlayerInputEvent event) {
            if (!event.getPlayer().equals(player()) || hideout == null) {
                return;
            }
            var input = event.getInput();
            if (input.isForward() || input.isBackward() || input.isLeft() || input.isRight() || input.isJump()) {
                popOut(null, true);
            }
        }
    }

    /** Like a loot chest: never with the ability item in hand (that is the skill) nor sneaking with something held. */
    private boolean opensOnClick(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        NamespacedKey key = abilityItemKey;
        if (key != null && !main.isEmpty() && main.getPersistentDataContainer().has(key, PersistentDataType.STRING)) {
            return false;
        }
        return !player.isSneaking() || (main.isEmpty() && player.getInventory().getItemInOffHand().isEmpty());
    }

    /** The chest the player's arm swing was aimed at, within its block reach and without anything in between. */
    private @Nullable FakeChest aimedChest(Player player) {
        AttributeInstance reach = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        double range = reach == null ? 4.5 : Math.max(1, reach.getValue());
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!(direction.lengthSquared() > 1.0E-8)) {
            return null;
        }
        RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        return hit == null ? null : chestAt(hit.getHitBlock());
    }

    /** Somebody opened a chest: what is inside depends on whose chest it is and who opened it. */
    private void opened(Player opener, FakeChest chest) {
        Player owner = player();
        if (chest.gone || opener.equals(owner) || !ctx().isAlive(opener)) {
            return;
        }
        boolean enemy = ctx().isEnemy(owner, opener);
        switch (chest.kind) {
            case TREASURE -> treasure(opener, chest);
            case DISGUISE -> {
                if (enemy) {
                    bite(chest, opener, params().num("disguise_bite"), params().ticks("disguise_grab"));
                    popOut(null, false);
                }
            }
            case SELF -> {
                if (enemy) {
                    bite(chest, opener, params().num("mimic_bite"), params().ticks("mimic_grab"));
                    popOut(null, true);
                }
            }
            case BAIT -> {
                if (enemy) {
                    bite(chest, opener, params().num("bait_bite"), params().ticks("bait_grab"));
                    ctx().runLater(owner, LID_SHUT + 2, () -> removeChest(chest, true));
                }
            }
            case FAKE -> {
                if (enemy) {
                    bite(chest, opener, params().num("fake_bite"), params().ticks("fake_grab"));
                    ctx().runLater(owner, LID_SHUT + 2, () -> removeChest(chest, true));
                }
            }
        }
    }

    /** Somebody punched or shot a chest: a bait snaps back and goes, the pile's chests show what they are. */
    private void punched(Player puncher, FakeChest chest) {
        Player owner = player();
        if (chest.gone || puncher.equals(owner) || !ctx().isAlive(puncher) || !ctx().isEnemy(owner, puncher)) {
            return;
        }
        long now = ctx().currentTick();
        Long last = lastPunch.get(puncher.getUniqueId());
        if (last != null && now - last < 2) {
            return; // the click and the swing of one punch
        }
        lastPunch.put(puncher.getUniqueId(), now);
        switch (chest.kind) {
            case BAIT -> {
                snap(chest, puncher, params().num("bait_snap"));
                ctx().runLater(owner, 4, () -> removeChest(chest, true));
            }
            case DISGUISE -> popOut("<gray>들켰다! 누군가 상자를 걷어찼습니다</gray>", false);
            default -> opened(puncher, chest);
        }
    }

    // ================================================================== bites

    /** The lid flies open, fangs flash, the tongue lashes out: damage and a hold. */
    private void bite(FakeChest chest, LivingEntity victim, double damage, long grabTicks) {
        Player owner = player();
        if (!Combat.isTargetable(ctx(), owner, victim)) {
            return;
        }
        flipLid(chest);
        biteFx(chest, victim, true);
        Combat.hit(ctx(), owner, victim, Math.max(0, damage));
        if (grabTicks > 0 && Combat.isTargetable(ctx(), owner, victim)) {
            grab(victim, grabTicks);
        }
    }

    /** A smaller snap at whoever hit a bait: damage, no hold. */
    private void snap(FakeChest chest, LivingEntity victim, double damage) {
        Player owner = player();
        flipLid(chest);
        biteFx(chest, victim, false);
        if (Combat.isTargetable(ctx(), owner, victim)) {
            Combat.hit(ctx(), owner, victim, Math.max(0, damage));
        }
    }

    private void biteFx(FakeChest chest, LivingEntity victim, boolean big) {
        Player owner = player();
        Location mouth = chest.center().add(0, 0.25, 0);
        Location target = Fx.body(victim);
        Vector toward = target.toVector().subtract(mouth.toVector());
        double length = toward.length();
        Vector facing = length > 1.0E-3 ? toward.clone().multiply(1 / length) : new Vector(0, 0, 1);
        fangs(owner, mouth, Motion.horizontal(facing).lengthSquared() > 0 ? Motion.horizontal(facing)
                : new Vector(0, 0, 1));
        tongue(owner, mouth, facing, Math.max(0.3, length));
        World world = mouth.getWorld();
        world.spawnParticle(Particle.DUST, target, Vfx.n(ctx(), 8), 0.25, 0.3, 0.25, 0, PINK_DUST);
        world.spawnParticle(Particle.CRIT, target, Vfx.n(ctx(), 8), 0.3, 0.3, 0.3, 0.2);
        world.spawnParticle(Particle.BLOCK, mouth, Vfx.n(ctx(), 6), 0.25, 0.1, 0.25, 0.1, PLANKS.createBlockData());
        Fx.sound(mouth, Sound.BLOCK_CHEST_OPEN, 1.0f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(mouth, Sound.ENTITY_EVOKER_FANGS_ATTACK, big ? 1.0f : 0.7f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(target, Sound.ENTITY_FOX_BITE, 1.0f, big ? 0.7f : 1.1f);
        if (big) {
            Fx.sound(target, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 0.9f + Fx.jitter(0.08f));
        }
        ctx().runLater(owner, LID_SHUT, () -> Fx.sound(mouth, Sound.BLOCK_CHEST_CLOSE, 0.9f, 1.4f));
        popText(mouth.clone().add(0, 0.5, 0), big ? "<b><" + PINK + ">덥석!</" + PINK + "></b>" : "<b><" + PINK
                + ">콱!</" + PINK + "></b>", big ? 1.2f : 0.9f, 16);
    }

    /** A row of white fangs along the front rim, pointing down, for a moment. */
    private void fangs(Player owner, Location mouth, Vector facing) {
        if (!room(FANGS)) {
            return;
        }
        Vector side = new Vector(-facing.getZ(), 0, facing.getX());
        float yaw = (float) Math.toDegrees(Math.atan2(-facing.getX(), facing.getZ()));
        Location rim = mouth.clone().add(facing.clone().multiply(0.42));
        for (int i = 0; i < FANGS; i++) {
            double offset = (i - (FANGS - 1) / 2.0) * 0.18;
            Location at = rim.clone().add(side.clone().multiply(offset));
            BlockDisplay fang = keep(Displays.block(ctx(), owner, at, FANG.createBlockData(),
                    Displays.transform(0, 0, 0, 0.01f, 0.01f, 0.01f, yaw, 0, 45), LID_SHUT));
            Displays.animateNextTick(ctx(), owner, fang, Displays.transform(0, -0.04f, 0, FANG_SIZE, FANG_SIZE,
                    FANG_SIZE * 0.6f, yaw, 0, 45), 2);
        }
    }

    /** The pink tongue lashing from the mouth to the victim and back. */
    private void tongue(Player owner, Location mouth, Vector facing, double length) {
        if (!room(1)) {
            Vfx.line(ctx(), mouth, mouth.clone().add(facing.clone().multiply(length)), 4, Particle.DUST, PINK_DUST,
                    false);
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-facing.getX(), facing.getZ()));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.clamp(facing.getY(), -1, 1)));
        float full = (float) length;
        Vector half = facing.clone().multiply(full / 2);
        BlockDisplay lash = keep(Displays.block(ctx(), owner, mouth, TONGUE.createBlockData(),
                tonguePose(facing.clone().multiply(0.03), 0.06f, yaw, pitch), 12));
        Displays.animateNextTick(ctx(), owner, lash, tonguePose(half, full, yaw, pitch), 2);
        ctx().runLater(owner, 6, () -> {
            if (lash.isValid()) {
                Displays.animate(lash, tonguePose(facing.clone().multiply(0.03), 0.06f, yaw, pitch), 4);
            }
        });
    }

    /** The tongue as a long thin block along {@code yaw}/{@code pitch}, centered {@code center} from the mouth. */
    private static Transformation tonguePose(Vector center, float length, float yaw, float pitch) {
        return Displays.transform((float) center.getX(), (float) center.getY(), (float) center.getZ(), TONGUE_WIDTH,
                TONGUE_THICK, length, yaw, pitch, 0);
    }

    /** Held by the tongue: no walking, no jumping, its momentum gone; a pink ring stays wrapped round its waist. */
    private void grab(LivingEntity victim, long ticks) {
        Player owner = player();
        List<NamespacedKey> keys = new ArrayList<>(2);
        keys.add(ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, ticks));
        keys.add(ctx().addModifier(owner, victim, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, ticks));
        if (victim instanceof Player) {
            victim.setVelocity(new Vector(0, REST_FALL, 0));
        } else {
            victim.setVelocity(new Vector(0, Math.min(0, victim.getVelocity().getY()), 0));
        }
        if (victim instanceof Player held) {
            held.sendActionBar(Text.mm("<" + PINK + "><b>덥석!</b></" + PINK + "> <gray>" + Fx.seconds(ticks)
                    + "초간 붙잡혔습니다</gray>"));
        }
        Loop.repeat(ctx(), owner, 1, 2, Math.max(1, ticks / 2), run -> {
            if (!Combat.isTargetable(ctx(), owner, victim) || isHidden(owner, victim)) {
                return victim.isValid();
            }
            Location waist = victim.getLocation().add(0, victim.getHeight() * 0.45, 0);
            Vfx.ring(ctx(), waist, victim.getWidth() * 0.75, 9, run * 0.6, Particle.DUST, PINK_DUST, false);
            if (run % 3 == 0) {
                Vfx.dot(waist.clone().add(0, -0.2, 0), Particle.DUST, DROOL, false);
            }
            return true;
        });
    }

    // ================================================================== passive: the disguise

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        tickStillness(owner, now);
        if (hideout != null) {
            tickHideout(owner, tick, now);
        }
        if (tick % SPARKLE_PERIOD == 0 && !chests.isEmpty()) {
            sparkle();
        }
        if (tick % 5 == 0) {
            expireChests(now);
        }
        if (tick % 200 == 0) {
            lastPunch.values().removeIf(at -> now - at > 40);
        }
    }

    /** Counts how long the mimic has stood still and turns it into a chest once it is long enough. */
    private void tickStillness(Player owner, long now) {
        Location at = owner.getLocation();
        Location before = lastSpot;
        lastSpot = at;
        boolean moved = before == null || !before.getWorld().equals(at.getWorld())
                || horizontalDistance(before, at) > STILL_MOVE || Math.abs(before.getY() - at.getY()) > STILL_FALL;
        if (hideout != null) {
            if (moved) {
                popOut(null, hideout.kind == Kind.SELF);
            }
            return;
        }
        Pile current = pile;
        if (current != null && current.sneaking) {
            if (moved) {
                current.comeOut("<gray>상자에 숨기 전에 움직여 모습이 드러났습니다</gray>");
            }
            return;
        }
        if (moved || owner.isInWater() || !Motion.isGrounded(owner) || owner.isInsideVehicle() || owner.isGliding()) {
            stillSince = now;
            return;
        }
        long delay = Math.max(1, params().ticks("disguise_delay"));
        if (now - stillSince < delay || now < nextTry || pile != null) {
            return;
        }
        nextTry = now + DISGUISE_RETRY;
        Block feet = at.getBlock();
        FakeChest chest = place(feet, facing(owner), Kind.DISGUISE, DISGUISE_MAX);
        if (chest != null) {
            disguise(owner, chest, "<" + GOLD + "><b>보물 상자 흉내</b></" + GOLD + "> <gray>움직이면 풀립니다</gray>");
        }
    }

    private static double horizontalDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The mimic becomes {@code chest}: hidden from enemies, see-through for its team, its spot a chest. */
    private void disguise(Player owner, FakeChest chest, String message) {
        hideout = chest;
        long ticks = Math.max(1, chest.expires - ctx().currentTick());
        hide(owner, ticks, () -> {
            if (hideout == chest) {
                popOut(null, false);
            }
        });
        Location center = chest.center();
        World world = center.getWorld();
        world.spawnParticle(Particle.POOF, center, Vfx.n(ctx(), 12), 0.35, 0.4, 0.35, 0.03);
        world.spawnParticle(Particle.DUST, center, Vfx.n(ctx(), 10), 0.4, 0.4, 0.4, 0, OAK_DUST);
        world.spawnParticle(Particle.WAX_ON, center, Vfx.n(ctx(), 5), 0.3, 0.3, 0.3, 0);
        Fx.sound(center, Sound.BLOCK_CHEST_CLOSE, 0.8f, 0.9f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.ENTITY_ITEM_FRAME_ADD_ITEM, 0.5f, 0.7f);
        owner.sendActionBar(Text.mm(message));
    }

    /** Hides the mimic from its enemies (and makes it see-through for its team) for at most {@code ticks}. */
    private void hide(Player owner, long ticks, Runnable onExpire) {
        stealth.begin(ctx(), owner, ticks, onExpire);
        Stealth.dropAggro(ctx(), owner, 16);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) Math.min(Integer.MAX_VALUE,
                ticks), 0, true, false, true));
    }

    /** While disguised: the prompt, the hiding refreshed for newcomers and, now and then, a tell-tale creak. */
    private void tickHideout(Player owner, long tick, long now) {
        FakeChest chest = hideout;
        if (chest == null) {
            return;
        }
        if (chest.gone || chest.block.getType() != CHEST) {
            popOut(null, chest.kind == Kind.SELF);
            return;
        }
        stealth.hideFromEnemies(ctx(), owner);
        if (tick % PROMPT_PERIOD == 0) {
            String what = chest.kind == Kind.SELF ? "보물 무더기 속" : "보물 상자 흉내";
            owner.sendActionBar(Text.mm("<" + GOLD + ">" + what + "</" + GOLD + "> <gray>움직이면 튀어나옵니다</gray>"));
        }
        if (tick % CREAK_PERIOD == 0) {
            // The mimic breathes: a soft creak a careful ear can catch.
            Fx.sound(chest.center(), Sound.BLOCK_CHEST_LOCKED, 0.25f, 0.6f + Fx.jitter(0.05f));
        }
    }

    /**
     * The mimic jumps out of its chest: the chest goes, the mimic shows again, and its first melee blow within the
     * ambush window hits harder. {@code dash}: the ultimate's burst of speed.
     */
    private void popOut(@Nullable String message, boolean dashOut) {
        FakeChest chest = hideout;
        if (chest == null) {
            return;
        }
        hideout = null;
        Player owner = player();
        stealth.end(ctx(), owner);
        clearInvisibility(owner);
        long now = ctx().currentTick();
        ambushUntil = now + params().ticks("ambush_window");
        stillSince = now;
        nextTry = now + DISGUISE_RETRY * 2;
        Location center = chest.center();
        flipLid(chest);
        World world = center.getWorld();
        world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 16), 0.35, 0.3, 0.35, 0.15, PLANKS.createBlockData());
        world.spawnParticle(Particle.ITEM, center.clone().add(0, 0.4, 0), Vfx.n(ctx(), 8), 0.2, 0.2, 0.2, 0.15,
                new ItemStack(COIN));
        Vfx.burst(ctx(), center, PALETTE, 0.7, Particle.WAX_ON, false);
        Fx.sound(center, Sound.BLOCK_CHEST_OPEN, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.7f, 1.4f + Fx.jitter(0.1f));
        Fx.sound(center, Sound.ENTITY_FOX_SCREECH, 0.4f, 1.6f + Fx.jitter(0.1f));
        popText(center.clone().add(0, 1.4, 0), "<b><" + GOLD + ">짠!</" + GOLD + "></b>", 1.1f, 14);
        ctx().runLater(owner, 3, () -> removeChest(chest, false));
        if (dashOut) {
            dash(owner);
        }
        Pile current = pile;
        if (current != null && chest.kind == Kind.SELF) {
            current.over();
        }
        owner.sendActionBar(Text.mm(message != null ? message : "<" + GOLD + "><b>튀어나왔다!</b></" + GOLD
                + "> <gray>" + Fx.seconds(params().ticks("ambush_window")) + "초 안의 첫 근접 공격 +"
                + AbilityInfo.formatNumber(params().num("ambush_bonus")) + "</gray>"));
    }

    private void dash(Player owner) {
        long ticks = params().ticks("dash_duration");
        double speed = Math.max(0, params().num("dash_speed")) / 100.0;
        if (ticks <= 0 || speed <= 0) {
            return;
        }
        if (dash != null) {
            ctx().removeModifier(owner, dash);
        }
        dash = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1, ticks);
        Loop.repeat(ctx(), owner, 1, 2, Math.max(1, ticks / 2), run -> {
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Vfx.dot(feet, Particle.DUST, run % 2 == 0 ? GOLD_SMALL : OAK_DUST, false);
            if (run % 4 == 0) {
                Vfx.dot(feet.clone().add(0, 0.3, 0), Particle.WAX_ON, null, false);
            }
            return true;
        });
    }

    private static void clearInvisibility(Player owner) {
        PotionEffect effect = owner.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (effect != null && effect.isAmbient() && !effect.hasParticles()) {
            owner.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    /** Taking damage breaks the disguise. */
    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (hideout != null && !event.isCancelled() && event.getFinalDamage() > 0) {
            popOut("<gray>맞아서 정체가 드러났습니다</gray>", hideout.kind == Kind.SELF);
        }
    }

    /** The ambush: the first melee blow right after jumping out hits harder. Attacking from inside breaks it. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        Player owner = player();
        if (event.isCancelled() || ctx().isAbilityDamage() || !owner.equals(event.getDamager())) {
            return;
        }
        if (hideout != null) {
            popOut(null, false);
        }
        long now = ctx().currentTick();
        if (now <= ambushUntil) {
            ambushUntil = Long.MIN_VALUE / 2;
            double bonus = Math.max(0, params().num("ambush_bonus"));
            if (bonus > 0) {
                event.setDamage(event.getDamage() + bonus);
                Location body = Fx.body(victim);
                Vfx.cloud(ctx(), body, Particle.DUST, 8, 0.3, 0, PINK_DUST, false);
                Vfx.cloud(ctx(), body, Particle.CRIT, 8, 0.3, 0.2, null, false);
                Fx.sound(body, Sound.ENTITY_FOX_BITE, 0.9f, 0.8f);
                owner.sendActionBar(Text.mm("<" + PINK + "><b>기습!</b></" + PINK + "> <gray>+"
                        + AbilityInfo.formatNumber(bonus) + "</gray>"));
            }
        }
    }

    /** Every mimic chest twinkles like an unopened loot chest for the viewers close to it. */
    private void sparkle() {
        int count = Vfx.n(ctx(), 2);
        List<Player> viewers = new ArrayList<>(ctx().alivePlayers());
        for (FakeChest chest : chests.values()) {
            Location center = chest.center();
            for (Player viewer : viewers) {
                if (viewer.getWorld().equals(center.getWorld())
                        && viewer.getLocation().distanceSquared(center) <= SPARKLE_RADIUS * SPARKLE_RADIUS) {
                    viewer.spawnParticle(Particle.WAX_OFF, center.getX(), center.getY() - 0.1, center.getZ(), count,
                            0.3, 0.25, 0.3, 0.0);
                }
            }
        }
    }

    private void expireChests(long now) {
        for (FakeChest chest : new ArrayList<>(chests.values())) {
            if (chest.expires <= now + 1 && chest != hideout) {
                removeChest(chest, chest.kind == Kind.BAIT);
            }
        }
    }

    // ================================================================== active: the bait chest

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (hideout != null) {
            popOut(null, false);
        }
        double range = Math.max(1, params().num("bait_range"));
        Location landing = landing(owner, range);
        if (landing == null) {
            return ActivationResult.fail("<gray>상자를 던질 곳이 없습니다</gray>");
        }
        new Throw(owner, hand(owner), landing).launch();
        return ActivationResult.success();
    }

    /** Where a thrown chest lands: at the aimed enemy, where the aim meets the ground or a wall, or under the throw's end. */
    private @Nullable Location landing(Player owner, double range) {
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!(direction.lengthSquared() > 1.0E-8)) {
            direction = Motion.horizontalFacing(eye);
        }
        direction.normalize();
        Location point;
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null) {
            point = aimed.getLocation().add(0, 0.3, 0);
        } else {
            RayTraceResult hit = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            point = hit == null ? eye.clone().add(direction.clone().multiply(range))
                    : hit.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.3));
        }
        Location ground = groundUnder(point);
        if (ground == null || !ctx().isInsideArena(ground)) {
            return null;
        }
        return ground;
    }

    private static @Nullable Location groundUnder(Location point) {
        World world = point.getWorld();
        Location from = point.clone().add(0, 0.05, 0);
        RayTraceResult ground = world.rayTraceBlocks(from, new Vector(0, -1, 0), MAX_DROP, FluidCollisionMode.NEVER,
                true);
        if (ground == null) {
            return null;
        }
        Location at = ground.getHitPosition().toLocation(world);
        at.setYaw(0);
        at.setPitch(0);
        return at;
    }

    /** A chest spinning through the air in a lob; on landing it snaps at whoever is next to it and stays as a bait. */
    private final class Throw implements LongPredicate {

        private final Player owner;
        private final Location from;
        private final Location to;
        private final long flight;
        private final double arc;
        private final @Nullable Kind kind;
        private @Nullable ItemDisplay body;
        private @Nullable Runnable landed;

        Throw(Player owner, Location from, Location to) {
            this(owner, from, to, Kind.BAIT);
        }

        Throw(Player owner, Location from, Location to, @Nullable Kind kind) {
            this.owner = owner;
            this.from = from;
            this.to = to.clone().add(0, 0.45, 0);
            double distance = from.distance(this.to);
            this.flight = kind == Kind.BAIT ? Math.clamp(Math.round(distance / THROW_SPEED), MIN_FLIGHT, MAX_FLIGHT)
                    : PILE_FLIGHT;
            this.arc = 0.8 + distance * 0.15;
            this.kind = kind;
        }

        Throw onLanded(Runnable then) {
            landed = then;
            return this;
        }

        void launch() {
            if (room(1)) {
                body = keep(Displays.item(ctx(), owner, from, new ItemStack(CHEST),
                        Displays.centered(0, 0, 0, 0.3f, 0.3f, 0.3f, 0, 0, 0), flight + 4));
                body.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, body, spin(0), 3);
            }
            if (kind == Kind.BAIT) {
                Fx.sound(from, Sound.ENTITY_SPLASH_POTION_THROW, 0.8f, 0.7f + Fx.jitter(0.05f));
                Fx.sound(from, Sound.BLOCK_CHEST_CLOSE, 0.6f, 1.6f);
                Vfx.ring(ctx(), to.clone().subtract(0, 0.4, 0), Math.max(0.5, params().num("land_radius")), 14, 0,
                        Particle.DUST, GOLD_DUST, true);
            }
            Loop.repeat(ctx(), owner, 1, 1, flight, this, this::land);
        }

        @Override
        public boolean test(long run) {
            long t = run + 1;
            double progress = (double) t / flight;
            Location at = from.clone().add(to.toVector().subtract(from.toVector()).multiply(progress))
                    .add(0, arc * 4 * progress * (1 - progress), 0);
            ItemDisplay chest = body;
            if (chest != null && chest.isValid()) {
                Displays.moveTo(chest, at);
                if (t % 2 == 0) {
                    Displays.animate(chest, spin(t * 36f), 2);
                }
            }
            Vfx.dot(at, Particle.DUST, OAK_DUST, false);
            if (t % 2 == 0) {
                Vfx.dot(at, Particle.WAX_ON, null, false);
            }
            return true;
        }

        private void land() {
            ItemDisplay chest = body;
            body = null;
            if (chest != null && chest.isValid()) {
                chest.remove();
            }
            Location ground = to.clone().subtract(0, 0.45, 0);
            World world = ground.getWorld();
            world.spawnParticle(Particle.BLOCK, ground.clone().add(0, 0.2, 0), Vfx.n(ctx(), 10), 0.3, 0.1, 0.3, 0.1,
                    PLANKS.createBlockData());
            world.spawnParticle(Particle.POOF, ground.clone().add(0, 0.3, 0), Vfx.n(ctx(), 6), 0.3, 0.2, 0.3, 0.02);
            Fx.sound(ground, Sound.BLOCK_WOOD_HIT, 1.0f, 0.7f + Fx.jitter(0.05f));
            Runnable then = landed;
            if (then != null) {
                then.run();
                return;
            }
            landBait(ground);
        }
    }

    private static Transformation spin(float yaw) {
        return Displays.centered(0, 0, 0, THROWN_SCALE, THROWN_SCALE, THROWN_SCALE, yaw, 15, 10);
    }

    /** The bait lands: everyone next to it is snapped at, and it stays as a chest (the oldest bait goes if too many). */
    private void landBait(Location ground) {
        Player owner = player();
        double radius = Math.max(0, params().num("land_radius"));
        FakeChest chest = placeNear(ground, facing(owner).getOppositeFace(), Kind.BAIT,
                Math.max(1, params().ticks("bait_duration")));
        Location mouth = chest != null ? chest.center() : ground.clone().add(0, 0.45, 0);
        int bitten = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, mouth, radius)) {
            if (isHidden(owner, enemy)) {
                continue;
            }
            bitten++;
            if (chest != null) {
                bite(chest, enemy, params().num("land_damage"), params().ticks("land_grab"));
            } else {
                Combat.hit(ctx(), owner, enemy, Math.max(0, params().num("land_damage")));
                grab(enemy, params().ticks("land_grab"));
            }
        }
        if (chest == null) {
            owner.sendActionBar(Text.mm("<gray>상자를 내려놓을 자리가 없어 그대로 부서졌습니다</gray>"));
            return;
        }
        baits.addLast(chest);
        int max = Math.max(1, params().integer("max_baits"));
        while (baits.size() > max) {
            FakeChest oldest = baits.pollFirst();
            if (oldest != null) {
                removeChest(oldest, true);
            }
        }
        owner.sendActionBar(Text.mm("<" + GOLD + "><b>미끼 상자</b></" + GOLD + "> <gray>"
                + (bitten > 0 ? bitten + "명을 물었습니다 · " : "") + "미끼 " + baits.size() + "/" + max + "</gray>"));
    }

    /** {@link #place} at the ground's block, or the nearest free block around it if that is taken. */
    private @Nullable FakeChest placeNear(Location ground, BlockFace facing, Kind kind, long ticks) {
        Block base = ground.clone().add(0, 0.01, 0).getBlock();
        FakeChest chest = place(base, facing, kind, ticks);
        if (chest != null) {
            return chest;
        }
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int[] offset : around) {
            chest = place(base.getRelative(offset[0], 0, offset[1]), facing, kind, ticks);
            if (chest != null) {
                return chest;
            }
        }
        return null;
    }

    /** A real treasure: whoever opens it gets a bite of health and a shower of gold. */
    private void treasure(Player opener, FakeChest chest) {
        flipLid(chest);
        ctx().heal(opener, Math.max(0, params().num("treasure_heal")));
        Location center = chest.center().add(0, 0.4, 0);
        World world = center.getWorld();
        world.spawnParticle(Particle.ITEM, center, Vfx.n(ctx(), 18), 0.2, 0.3, 0.2, 0.22, new ItemStack(COIN));
        world.spawnParticle(Particle.WAX_ON, center, Vfx.n(ctx(), 10), 0.4, 0.4, 0.4, 0);
        world.spawnParticle(Particle.HEART, Fx.body(opener), 2, 0.3, 0.3, 0.3, 0);
        Fx.sound(center, Sound.BLOCK_CHEST_OPEN, 1.0f, 1.2f);
        Fx.sound(center, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.8f);
        Fx.sound(center, Sound.ITEM_BUNDLE_DROP_CONTENTS, 1.0f, 1.2f);
        popText(center.clone().add(0, 0.6, 0), "<b><" + GOLD + ">보물!</" + GOLD + "></b>", 1.0f, 16);
        opener.sendActionBar(Text.mm("<" + GOLD + ">진짜 보물!</" + GOLD + "> <gray>+"
                + AbilityInfo.formatNumber(params().num("treasure_heal")) + " 회복</gray>"));
        ctx().runLater(player(), LID_SHUT + 2, () -> removeChest(chest, true));
    }

    // ================================================================== ultimate: the treasure pile

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (pile != null) {
            return ActivationResult.fail("<gray>이미 보물 무더기 속에 있습니다</gray>");
        }
        FakeChest disguise = hideout;
        if (disguise != null) {
            popOut(null, false);
            // Its spot is one of the pile's: the chest goes right away, not after the lid flips.
            removeChest(disguise, false);
        }
        int extra = Math.max(0, params().integer("pile_treasures")) + Math.max(0, params().integer("pile_fakes"));
        List<Location> spots = pileSpots(owner, extra);
        if (spots.isEmpty()) {
            return ActivationResult.fail("<gray>상자를 숨길 자리가 없습니다</gray>");
        }
        new Pile(owner, spots).start();
        return ActivationResult.success();
    }

    /**
     * The mimic's own spot first, then up to {@code count} free spots on solid ground within the radius, apart from each
     * other: each must fit the mimic (it may end up hiding in any of them).
     */
    private List<Location> pileSpots(Player owner, int count) {
        World world = owner.getWorld();
        Location center = owner.getLocation();
        double radius = Math.max(1.5, params().num("pile_radius"));
        Body body = Body.standing(owner);
        List<Location> spots = new ArrayList<>();
        Location own = center.getBlock().getLocation().add(0.5, 0, 0.5);
        Spots.snapToGround(world, own, 1.1);
        if (Spots.isSafe(ctx(), own, body) && freeForChest(own.getBlock(), true)) {
            spots.add(own);
        } else {
            return spots;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int tries = 0; tries < SPOT_TRIES && spots.size() < count + 1; tries++) {
            double angle = random.nextDouble(Math.PI * 2);
            double distance = 1.8 + random.nextDouble() * (radius - 1.8);
            Location at = center.clone().add(Math.cos(angle) * distance, 1.0, Math.sin(angle) * distance);
            at = at.getBlock().getLocation().add(0.5, 0, 0.5);
            Spots.snapToGround(world, at, 3.0);
            if (!Spots.isSafe(ctx(), at, body) || !freeForChest(at.getBlock(), false)
                    || !Spots.clearPath(center.clone().add(0, 1.0, 0), at.clone().add(0, 1.0, 0))) {
                continue;
            }
            boolean apart = true;
            for (Location other : spots) {
                if (other.distanceSquared(at) < SPOT_GAP * SPOT_GAP) {
                    apart = false;
                    break;
                }
            }
            if (apart) {
                spots.add(at);
            }
        }
        return spots;
    }

    private boolean freeForChest(Block block, boolean self) {
        return !chests.containsKey(block) && (block.isEmpty() || block.isReplaceable())
                && block.getRelative(BlockFace.DOWN).isCollidable() && !isOccupied(block, self);
    }

    /**
     * The treasure pile: chests burst out of the mimic in a cloud of smoke and gold, and the mimic slips into one of the
     * spots. All the chests (the mimic's own too) land at the same moment, so nobody can tell which one is the mimic. It
     * lasts until the mimic is found or moves (or the time is up); the other chests go a little later.
     */
    private final class Pile implements LongPredicate {

        private final Player owner;
        private final List<Location> spots;
        private final long ticks;
        private final List<FakeChest> pileChests = new ArrayList<>();
        /** Hidden, but the chests are still in the air. */
        boolean sneaking;
        private boolean finished;

        Pile(Player owner, List<Location> spots) {
            this.owner = owner;
            this.spots = spots;
            this.ticks = Math.max(1, params().ticks("pile_duration"));
        }

        void start() {
            pile = this;
            Location origin = owner.getLocation();
            List<Kind> roles = new ArrayList<>();
            int treasures = Math.max(0, params().integer("pile_treasures"));
            for (int i = 0; i < spots.size() - 1; i++) {
                roles.add(i < treasures ? Kind.TREASURE : Kind.FAKE);
            }
            Collections.shuffle(roles);
            int mine = ThreadLocalRandom.current().nextInt(spots.size());
            roles.add(mine, Kind.SELF);
            // The smoke screen: every spot puffs at once, so nobody sees where the mimic went.
            World world = origin.getWorld();
            for (Location spot : spots) {
                Location puff = spot.clone().add(0, 0.6, 0);
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, puff, Vfx.n(ctx(), 6), 0.4, 0.5, 0.4, 0.02, null,
                        true);
                world.spawnParticle(Particle.POOF, puff, Vfx.n(ctx(), 14), 0.5, 0.6, 0.5, 0.04, null, true);
            }
            Vfx.burst(ctx(), origin.clone().add(0, 1, 0), PALETTE, 1.4, Particle.WAX_ON, true);
            world.spawnParticle(Particle.ITEM, origin.clone().add(0, 1.2, 0), Vfx.n(ctx(), 20), 0.4, 0.4, 0.4, 0.3,
                    new ItemStack(COIN), true);
            Fx.sound(origin, Sound.BLOCK_CHEST_OPEN, 1.2f, 0.6f);
            Fx.sound(origin, Sound.ITEM_BUNDLE_DROP_CONTENTS, 1.2f, 0.8f);
            Fx.sound(origin, Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.8f, 0.6f);
            Fx.sound(origin, Sound.ENTITY_WITCH_CELEBRATE, 0.7f, 1.4f);
            // The mimic vanishes in the smoke and slips into its spot.
            sneaking = true;
            hide(owner, ticks + PILE_FLIGHT + 2, () -> comeOut(null));
            Location hideSpot = spots.get(mine).clone();
            hideSpot.setYaw(origin.getYaw());
            hideSpot.setPitch(origin.getPitch());
            if (mine != 0) {
                owner.teleport(hideSpot);
                owner.setVelocity(new Vector());
                owner.setFallDistance(0);
            }
            lastSpot = owner.getLocation();
            long life = ticks + PILE_FLIGHT + 2;
            Location from = origin.clone().add(0, 1.0, 0);
            for (int i = 0; i < spots.size(); i++) {
                Location spot = spots.get(i);
                Kind kind = roles.get(i);
                new Throw(owner, from, spot, kind).onLanded(() -> land(spot, kind, life)).launch();
            }
            owner.sendActionBar(Text.mm("<" + GOLD + "><b>보물 무더기</b></" + GOLD + "> <gray>어느 것이 나인지 아무도 "
                    + "모릅니다</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, ticks + PILE_FLIGHT, this, () -> {
                if (!finished) {
                    if (hideout != null) {
                        popOut("<gray>보물 무더기에서 튀어나왔습니다</gray>", true);
                    } else {
                        comeOut(null);
                    }
                    over();
                }
            });
        }

        /** A chest of the pile lands; the mimic's own one takes it in. */
        private void land(Location spot, Kind kind, long life) {
            if (finished) {
                return;
            }
            if (kind != Kind.SELF) {
                FakeChest chest = place(spot.getBlock(), randomFacing(), kind, life);
                if (chest != null) {
                    pileChests.add(chest);
                    mark(chest);
                }
                return;
            }
            if (!sneaking) {
                return;
            }
            sneaking = false;
            FakeChest self = place(owner.getLocation().getBlock(), facing(owner), Kind.SELF, life);
            if (self == null) {
                comeOut("<gray>숨을 자리가 없어 모습이 드러났습니다</gray>");
                return;
            }
            hideout = self;
            pileChests.add(self);
            mark(self);
        }

        @Override
        public boolean test(long run) {
            return !finished;
        }

        /** The mimic shows itself before its chest landed (it moved, or there was no room). */
        void comeOut(@Nullable String message) {
            if (!sneaking && hideout != null) {
                return;
            }
            boolean wasHidden = sneaking || stealth.isActive();
            sneaking = false;
            if (wasHidden) {
                stealth.end(ctx(), owner);
                clearInvisibility(owner);
                ambushUntil = ctx().currentTick() + params().ticks("ambush_window");
                dash(owner);
            }
            if (message != null) {
                owner.sendActionBar(Text.mm(message));
            }
            over();
        }

        /** The mimic is out: the rest of the pile goes after the leftover time. */
        void over() {
            if (finished) {
                return;
            }
            finished = true;
            if (pile == this) {
                pile = null;
            }
            long leftover = params().ticks("leftover");
            ctx().runLater(owner, Math.max(1, leftover), () -> {
                for (FakeChest chest : pileChests) {
                    if (chest != hideout) {
                        removeChest(chest, true);
                    }
                }
            });
        }
    }

    /** The side the owner faces (yaw 0 is south, 90 west). */
    private static BlockFace facing(Player owner) {
        BlockFace[] faces = {BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST};
        return faces[Math.floorMod(Math.round(owner.getLocation().getYaw() / 90f), 4)];
    }

    private static BlockFace randomFacing() {
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        return faces[ThreadLocalRandom.current().nextInt(faces.length)];
    }

    /** A small marker over a pile chest that only the mimic and its allies see. */
    private void mark(FakeChest chest) {
        if (!room(1)) {
            return;
        }
        Player owner = player();
        String text = switch (chest.kind) {
            case TREASURE -> "<" + GOLD + ">◆ 보물</" + GOLD + ">";
            case FAKE -> "<" + RED + ">✖ 가짜</" + RED + ">";
            default -> "<" + PINK + ">★ 나</" + PINK + ">";
        };
        Location at = chest.block.getLocation().add(0.5, MARKER_ABOVE, 0.5);
        TextDisplay marker = keep(Displays.text(ctx(), owner, at, Text.mm("<b>" + text + "</b>"), 0.5f,
                Math.max(1, chest.expires - ctx().currentTick())));
        marker.setSeeThrough(true);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(owner) && !ctx().isAlly(owner, online)) {
                online.hideEntity(ctx().plugin(), marker);
            }
        }
        chest.marker = marker;
    }

    // ================================================================== helpers

    /** In front of the chest, where a thrown chest leaves the hands. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontalFacing(eye);
        return Displays.unrotated(eye.add(facing.multiply(0.6)).subtract(0, 0.4, 0));
    }

    /** A word popping up and shrinking away. */
    private void popText(Location at, String miniMessage, float scale, long life) {
        if (!room(1)) {
            return;
        }
        Player owner = player();
        Component text = Text.mm(miniMessage);
        TextDisplay display = keep(Displays.text(ctx(), owner, at, text, scale * 0.5f, life));
        Displays.animateNextTick(ctx(), owner, display, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0),
                4);
        ctx().runLater(owner, Math.max(2, life - 5), () -> {
            if (display.isValid()) {
                Displays.shrinkAway(ctx(), owner, display, 4);
            }
        });
    }

    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }

    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    private static void remove(@Nullable Display display) {
        if (display != null && display.isValid()) {
            display.remove();
        }
    }
}
