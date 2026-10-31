package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 오디세우스 — the cunning hero of Ithaca who blinded the one-eyed giant and told it his name was "Nobody", then took
 * Troy with a wooden horse. His name never shows (name tag, kill feed and announcements all say "아무도"), no glowing
 * light gives him away and he hits enemies bigger than himself harder; the active drives a heated stake into the first
 * enemy in front of him and leaves him unseen by that one enemy for a moment; the ultimate sets up a giant gift horse
 * that he and his crouching allies climb into, hidden and untouchable, until somebody touches or hits the gift, he
 * crouches or the time is up, and they all burst out of it.
 *
 * <p>The "아무도" name goes through the match's name hook ({@link Ability#publicName}); the name tag over the head is hidden
 * from enemies on their own scoreboards (never the server's main one) and replaced by a "아무도" tag only they see.
 * Riders of the horse (each in a seat of its own that fits its body) are hidden from enemies per viewer, their damage
 * is cancelled and they are kept inside the hull every tick; an ally gets off alone by crouching again. Other
 * abilities' non-damage effects may still reach them (accepted).
 *
 * <p>Look: weathered wood, the sea of Ithaca and bronze. The stake is a long pale shaft with a glowing ember tip that
 * thrusts out and back in a shower of sparks; the blinded enemy carries an owner-only crossed eye counting down. The
 * horse is a tall wooden horse on four wheels wrapped like a present in a crimson ribbon with a bow on its back; it
 * rises plank by plank, creaks now and then, and bursts apart into splinters when its door flies open.
 */
public final class OdysseusAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("odysseus", "오디세우스", Material.SADDLE)
            .color(TextColor.color(0xB07A3B))
            .difficulty(2)
            .passive("나는 '아무도'다",
                    "이름표와 킬 피드·공지에 내 이름 대신 '아무도'가 나오고, 위치를 드러내는 발광 효과에 걸리지 "
                            + "않습니다. 나보다 {giant_ratio}배 이상 큰 적에게 주는 피해가 {giant_bonus}% 늘어납니다.")
            .active("외눈박이의 말뚝",
                    "달군 말뚝을 앞으로 찔러({stake_range}블록, 좁게) 처음 닿은 적에게 {stake_damage} 피해를 주고 "
                            + "{stake_blind}초간 눈을 멀게 하며, {vanish_duration}초간 그 적에게만 내가 보이지 않습니다(다른 적에게는 "
                            + "보입니다). 그 적을 공격하면 일찍 드러납니다. 빗나가면 쿨타임이 {miss_refund}% 돌아옵니다.",
                    9)
            .ultimate("트로이 목마",
                    "조준한 곳({horse_range}블록 이내)에 거대한 목마를 세우고, 나와 {board_radius}블록 안에서 웅크린 "
                            + "아군이 올라타 숨습니다(처음 {board_window}초는 목마 곁에서 웅크려도 탐). 숨은 사람은 "
                            + "보이지 않고 피해를 받지 않습니다. 적이 목마를 건드리거나, 내가 웅크리거나, "
                            + "{horse_duration}초가 지나면 모두 튀어나와 반경 {burst_radius}블록의 적에게 "
                            + "{burst_damage} 피해를 주고 띄우며, {charge_duration}초간 주는 피해 +{charge_damage}%, "
                            + "이동 속도 +{charge_speed}%를 얻습니다.")
            .param("giant_ratio", 1.15)
            .param("giant_bonus", 25)
            .param("stake_range", 4.5)
            .param("stake_damage", 4)
            .param("stake_blind", 1)
            .param("vanish_duration", 3)
            .param("miss_refund", 50)
            .param("horse_range", 10)
            .param("board_radius", 6)
            .param("board_window", 3)
            .param("horse_duration", 6)
            .param("burst_radius", 4)
            .param("burst_damage", 5)
            .param("burst_lift", 1.5)
            .param("charge_duration", 4)
            .param("charge_damage", 20)
            .param("charge_speed", 20)
            .build();

    private static final String BRONZE = "#D19A55";
    private static final String SEA = "#5FA3D9";
    private static final String CRIMSON = "#C8323C";

    // ------------------------------------------------------------------ look: weathered wood, Ithaca sea, bronze
    /** Pale bronze, weathered wood, the sea of Ithaca. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF3D9A8, 0xB07A3B, 0x2B5F8A);
    private static final Color BRONZE_COLOR = Color.fromRGB(0xB07A3B);
    private static final Color SEA_COLOR = Color.fromRGB(0x2B5F8A);
    private static final Color EMBER_COLOR = Color.fromRGB(0xFF7A2E);
    private static final Particle.DustOptions BRONZE_DUST = PALETTE.dust(0.8f);
    private static final Particle.DustOptions BRONZE_SMALL = PALETTE.dust(0.5f);
    private static final Particle.DustOptions SEA_DUST = new Particle.DustOptions(SEA_COLOR, 0.8f);
    private static final Particle.DustOptions MIST = new Particle.DustOptions(Color.fromRGB(0xBFD8EA), 0.9f);
    private static final Particle.DustOptions WOOD_DUST = new Particle.DustOptions(Color.fromRGB(0x8B6A47), 1.0f);
    private static final Particle.DustOptions EMBER = new Particle.DustOptions(EMBER_COLOR, 0.6f);
    private static final Particle.DustTransition HEAT = new Particle.DustTransition(Color.fromRGB(0xFFD08A),
            EMBER_COLOR, 0.7f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);

    /** Block types of the visuals; block data is created on use (it needs a running server). */
    private static final Material SHAFT = Material.STRIPPED_BIRCH_LOG;
    private static final Material TIP = Material.ORANGE_CONCRETE;
    private static final Material HULL = Material.OAK_PLANKS;
    private static final Material DARK_WOOD = Material.DARK_OAK_PLANKS;
    private static final Material WHEEL = Material.STRIPPED_DARK_OAK_LOG;
    private static final Material LEG = Material.SPRUCE_PLANKS;
    private static final Material RIBBON = Material.RED_WOOL;
    private static final Material DOOR = Material.SPRUCE_PLANKS;

    /** Display entities one odysseus keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    private static final double REST_FALL = -Motion.GRAVITY * Motion.DRAG;
    private static final double MAX_SPEED = 1.4;
    private static final double HEAD_GAP = 0.15;

    // passive
    private static final long NAME_PERIOD = 20;
    private static final long TAG_PERIOD = 5;
    private static final double TAG_ABOVE = 0.5;
    /**
     * Ticks a display following a player glides to each new spot: as long as a client lerps a moving player's body
     * (3 steps), so a follower moved every tick keeps pace with the body instead of trailing or jittering.
     */
    private static final int FOLLOW_LERP = 3;
    private static final long GIANT_MARK_PERIOD = 10;
    private static final double GIANT_MARK_RANGE = 20;
    private static final long GIANT_FX_PERIOD = 10;
    private static final long GLOW_FX_PERIOD = 20;
    private static final String NOBODY = "아무도";

    // active: the stake
    private static final double STAKE_PAD = 0.3;
    private static final float STAKE_WIDTH = 0.11f;
    private static final float STAKE_LENGTH = 1.6f;
    private static final float TIP_SIZE = 0.15f;
    private static final long THRUST_TICKS = 3;
    private static final long RETRACT_TICKS = 4;

    // ultimate: the horse
    private static final double HORSE_HALF_WIDTH = 0.9;
    private static final double HORSE_HALF_LENGTH = 1.55;
    private static final double HORSE_HEIGHT = 4.3;
    private static final double TOUCH_MARGIN = 0.25;
    private static final double BOARD_REACH = 1.6;
    private static final double SEAT_SLIP = 0.3;
    /** Where the riders sit, along the horse (blocks from its middle): each one gets a seat of its own if it fits. */
    private static final double[] SEATS = {0, 0.65, -0.65, 1.2, -1.2};
    private static final double SEAT_GAP = 0.5;
    /** How far in front of an aimed enemy the gift is set down (it must not open the moment it stands). */
    private static final double BEFORE_TARGET = 2.6;
    private static final long RISE_TICKS = 6;
    private static final long CREAK_PERIOD = 30;
    private static final long SNEAK_GRACE = 6;
    private static final long COLLAPSE_TICKS = 12;
    private static final double EXIT_SPEED = 0.35;

    /** Who should not see whom, and for how many reasons (the stake, the horse), by target then viewer. */
    private final Visibility visibility = new Visibility();
    private final Map<UUID, Vanish> vanished = new HashMap<>();
    /** Each enemy board the name tag is hidden on, and the team the entry was on before (null: none). */
    private final Map<UUID, @Nullable String> nameBoards = new HashMap<>();
    private final Map<UUID, Long> giantFx = new HashMap<>();
    private final List<Display> live = new ArrayList<>();
    /** The displays only some players may see, and who may (see {@link #privateText}). */
    private final Map<Display, Predicate<Player>> privateDisplays = new HashMap<>();
    private final LandingGuard landing = new LandingGuard();
    private @Nullable Listener glowGuard;
    private @Nullable TextDisplay nameTag;
    private long lastGlowFx = Long.MIN_VALUE / 2;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    /** "아무도": the name the kill feed, the elimination titles and the announcements show. */
    @Override
    public @Nullable Component publicName() {
        return Component.text(NOBODY);
    }

    @Override
    public void onGrant() {
        Listener guard = new GlowGuard();
        Bukkit.getPluginManager().registerEvents(guard, ctx().plugin());
        glowGuard = guard;
        PotionEffect glow = player().getPotionEffect(PotionEffectType.GLOWING);
        if (glow != null) {
            player().removePotionEffect(PotionEffectType.GLOWING);
        }
    }

    @Override
    public void onRevoke() {
        Listener guard = glowGuard;
        glowGuard = null;
        if (guard != null) {
            HandlerList.unregisterAll(guard);
        }
        restoreNameTags(player());
        visibility.showAll(ctx());
        vanished.clear();
        giantFx.clear();
        live.clear();
        privateDisplays.clear();
        nameTag = null;
    }

    // ================================================================== who sees whom

    /**
     * Per-viewer hiding with reasons: a player is shown again to a viewer only when no reason to hide it is left (the
     * stake's vanish and the horse may overlap; one plain {@code showPlayer} would end both).
     */
    private static final class Visibility {
        private final Map<UUID, Map<UUID, Integer>> hidden = new HashMap<>();

        void hide(AbilityContext ctx, Player viewer, Player target) {
            Map<UUID, Integer> viewers = hidden.computeIfAbsent(target.getUniqueId(), id -> new HashMap<>());
            int count = viewers.getOrDefault(viewer.getUniqueId(), 0);
            if (count == 0) {
                viewer.hidePlayer(ctx.plugin(), target);
            }
            viewers.put(viewer.getUniqueId(), count + 1);
        }

        void show(AbilityContext ctx, UUID viewerId, Player target) {
            Map<UUID, Integer> viewers = hidden.get(target.getUniqueId());
            if (viewers == null) {
                return;
            }
            Integer count = viewers.get(viewerId);
            if (count == null) {
                return;
            }
            if (count <= 1) {
                viewers.remove(viewerId);
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    viewer.showPlayer(ctx.plugin(), target);
                }
            } else {
                viewers.put(viewerId, count - 1);
            }
            if (viewers.isEmpty()) {
                hidden.remove(target.getUniqueId());
            }
        }

        boolean isHidden(Player viewer, Player target) {
            Map<UUID, Integer> viewers = hidden.get(target.getUniqueId());
            return viewers != null && viewers.containsKey(viewer.getUniqueId());
        }

        void showAll(AbilityContext ctx) {
            for (Map.Entry<UUID, Map<UUID, Integer>> entry : hidden.entrySet()) {
                Player target = Bukkit.getPlayer(entry.getKey());
                if (target == null) {
                    continue;
                }
                for (UUID viewerId : entry.getValue().keySet()) {
                    Player viewer = Bukkit.getPlayer(viewerId);
                    if (viewer != null) {
                        viewer.showPlayer(ctx.plugin(), target);
                    }
                }
            }
            hidden.clear();
        }
    }

    // ================================================================== passive: Nobody

    /**
     * The passive's listener: no glowing light on Nobody (the effect is refused the moment it would land), and nobody
     * who joins sees what is not theirs to see.
     */
    private final class GlowGuard implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            hidePrivateFrom(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onEffect(EntityPotionEffectEvent event) {
            if (!event.getEntity().equals(player()) || !PotionEffectType.GLOWING.equals(event.getModifiedType())) {
                return;
            }
            EntityPotionEffectEvent.Action action = event.getAction();
            if (action != EntityPotionEffectEvent.Action.ADDED && action != EntityPotionEffectEvent.Action.CHANGED) {
                return;
            }
            event.setCancelled(true);
            glowShrugged();
        }
    }

    /** A light meant to give Nobody away fizzles into sea mist. */
    private void glowShrugged() {
        Player owner = player();
        long now = ctx().currentTick();
        if (now - lastGlowFx < GLOW_FX_PERIOD) {
            return;
        }
        lastGlowFx = now;
        Location body = Fx.body(owner);
        Vfx.cloud(ctx(), body, Particle.DUST, 10, 0.35, 0, MIST, false);
        Vfx.cloud(ctx(), body, Particle.CLOUD, 4, 0.3, 0.02, null, false);
        Fx.sound(body, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.3f, 1.8f);
        owner.sendActionBar(Text.mm("<" + SEA + ">발광 무효</" + SEA + "> <gray>…빛이 비춘 곳엔 아무도 없었다</gray>"));
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        landing.update(owner, now);
        if (tick % NAME_PERIOD == 0) {
            hideNameTags(owner);
        }
        if (tick % TAG_PERIOD == 0 || nameTag == null) {
            updateNameTag(owner);
        } else if (nameTag != null && nameTag.isValid()) {
            Displays.moveTo(nameTag, tagSpot(owner));
        }
        if (tick % GIANT_MARK_PERIOD == 0) {
            markGiants(owner);
        }
        tickVanished(owner, tick, now);
        if (tick % 200 == 0) {
            giantFx.values().removeIf(at -> now - at > GIANT_FX_PERIOD);
        }
    }

    /**
     * Hides the real name tag from every enemy: on the enemy's own scoreboard the owner's entry moves to a team whose
     * tags are never shown (keeping the color and prefix of its team). The server's main scoreboard is never touched.
     */
    private void hideNameTags(Player owner) {
        String entry = owner.getName();
        String teamName = nameTeam(owner);
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player viewer : ctx().alivePlayers()) {
            if (viewer.equals(owner) || !ctx().isEnemy(owner, viewer)) {
                continue;
            }
            Scoreboard board = viewer.getScoreboard();
            if (board == main) {
                continue;
            }
            Team current = board.getEntryTeam(entry);
            if (current != null && current.getName().equals(teamName)) {
                continue;
            }
            Team mine = board.getTeam(teamName);
            if (mine == null) {
                mine = board.registerNewTeam(teamName);
            }
            mine.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            if (current != null) {
                if (current.hasColor() && current.color() instanceof NamedTextColor named) {
                    mine.color(named);
                }
                mine.prefix(current.prefix());
                mine.setCanSeeFriendlyInvisibles(current.canSeeFriendlyInvisibles());
            }
            if (!nameBoards.containsKey(viewer.getUniqueId())) {
                nameBoards.put(viewer.getUniqueId(), current == null ? null : current.getName());
            }
            if (current != null) {
                current.removeEntry(entry);
            }
            mine.addEntry(entry);
        }
    }

    /** Puts the name tag back on every board it was hidden on (also when the owner is gone). */
    private void restoreNameTags(Player owner) {
        String entry = owner.getName();
        String teamName = nameTeam(owner);
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Map.Entry<UUID, @Nullable String> saved : nameBoards.entrySet()) {
            Player viewer = Bukkit.getPlayer(saved.getKey());
            if (viewer == null) {
                continue;
            }
            Scoreboard board = viewer.getScoreboard();
            if (board == main) {
                continue;
            }
            Team mine = board.getTeam(teamName);
            if (mine != null) {
                mine.removeEntry(entry);
                mine.unregister();
            }
            String original = saved.getValue();
            Team before = original == null ? null : board.getTeam(original);
            if (before != null) {
                before.addEntry(entry);
            }
        }
        nameBoards.clear();
    }

    private static String nameTeam(Player owner) {
        return "mcpl_nb_" + owner.getUniqueId().toString().substring(0, 8);
    }

    /**
     * The "아무도" tag over the head: shown to the enemies who can see the owner, to nobody else (re-checked for every
     * player who joins). It follows every tick and glides as long as a client lerps a moving player ({@link
     * #FOLLOW_LERP}), so it keeps pace with the body instead of trailing and jittering behind it.
     */
    private void updateNameTag(Player owner) {
        TextDisplay tag = nameTag;
        if (tag == null || !tag.isValid()) {
            if (!room(1)) {
                return;
            }
            tag = keep(Displays.spawn(ctx(), owner, tagSpot(owner), TextDisplay.class, 0, d -> {
                d.text(Text.mm("<white>" + NOBODY + "</white>"));
                d.setBillboard(Display.Billboard.CENTER);
                d.setDefaultBackground(true);
                d.setShadowed(false);
                d.setTransformation(Displays.centered(0, 0, 0, 1, 1, 1, 0, 0, 0));
            }));
            tag.setTeleportDuration(FOLLOW_LERP);
            nameTag = tag;
        }
        Displays.moveTo(tag, tagSpot(owner));
        syncNameTag(owner);
    }

    /** Shows the tag to exactly the enemies who can see Nobody right now (call it whenever that changes). */
    private void syncNameTag(Player owner) {
        TextDisplay tag = nameTag;
        if (tag == null || !tag.isValid()) {
            return;
        }
        boolean concealed = owner.hasPotionEffect(PotionEffectType.INVISIBILITY);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean show = !concealed && !viewer.equals(owner) && ctx().isAlive(viewer) && ctx().isEnemy(owner, viewer)
                    && viewer.canSee(owner);
            if (show != viewer.canSee(tag)) {
                if (show) {
                    viewer.showEntity(ctx().plugin(), tag);
                } else {
                    viewer.hideEntity(ctx().plugin(), tag);
                }
            }
        }
    }

    private static Location tagSpot(Player owner) {
        return owner.getLocation().add(0, owner.getHeight() + TAG_ABOVE, 0);
    }

    private boolean isGiant(LivingEntity victim) {
        double ratio = Math.max(1.0, params().num("giant_ratio"));
        return standingHeight(victim) >= standingHeight(player()) * ratio - 1.0E-6;
    }

    /**
     * How tall {@code entity} stands: a player-shaped body by its scale alone (crouching, swimming, gliding or
     * crawling never shrinks it, so a crouching Nobody does not make everyone a giant), any other body by its hitbox.
     */
    private static double standingHeight(LivingEntity entity) {
        return entity instanceof Player || entity instanceof Mannequin ? Body.standing(entity).height()
                : entity.getHeight();
    }

    /** Over every giant within reach, a bronze eye only Nobody sees: the one-eyed giant to bring down. */
    private void markGiants(Player owner) {
        int count = Vfx.n(ctx(), 6);
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), GIANT_MARK_RANGE)) {
            if (!isGiant(enemy) || isHidden(owner, enemy)) {
                continue;
            }
            Location head = head(enemy).add(0, 0.25, 0);
            for (int i = 0; i < count; i++) {
                double angle = i * Math.PI * 2 / count;
                owner.spawnParticle(Particle.DUST, head.getX() + Math.cos(angle) * 0.3, head.getY(),
                        head.getZ() + Math.sin(angle) * 0.3, 1, 0, 0, 0, 0, BRONZE_SMALL);
            }
            owner.spawnParticle(Particle.DUST, head.getX(), head.getY(), head.getZ(), 1, 0, 0, 0, 0, SEA_DUST);
        }
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled()) {
            return;
        }
        double multiplier = 1;
        if (isGiant(victim)) {
            multiplier *= 1 + Math.max(0, params().num("giant_bonus")) / 100.0;
        }
        if (multiplier > 1) {
            event.setDamage(event.getDamage() * multiplier);
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        Vanish vanish = vanished.get(victim.getUniqueId());
        if (vanish != null) {
            vanish.end(true);
        }
        long now = ctx().currentTick();
        if (!isGiant(victim) || isHidden(player(), victim)) {
            return;
        }
        Long last = giantFx.get(victim.getUniqueId());
        if (last != null && now - last < GIANT_FX_PERIOD) {
            return;
        }
        giantFx.put(victim.getUniqueId(), now);
        // The giant-slayer's blow: a bronze flash at the head and a stinging ring.
        Location head = head(victim);
        Vfx.dot(head, Particle.FLASH, BRONZE_COLOR, false);
        Vfx.ring(ctx(), head, 0.4, 10, now * 0.3, Particle.DUST_COLOR_TRANSITION, GLOW, false);
        Vfx.cloud(ctx(), Fx.body(victim), Particle.CRIT, 6, 0.3, 0.15, null, false);
        Fx.sound(head, Sound.ITEM_TRIDENT_HIT, 0.7f, 1.4f + Fx.jitter(0.1f));
        player().sendActionBar(Text.mm("<" + BRONZE + "><b>거인 사냥</b></" + BRONZE + "> <gray>+"
                + AbilityInfo.formatNumber(params().num("giant_bonus")) + "%</gray>"));
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        landing.absorb(event, ctx().currentTick());
    }

    // ================================================================== active: the stake

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!(direction.lengthSquared() > 1.0E-8)) {
            return ActivationResult.fail("<gray>찌를 방향이 없습니다</gray>");
        }
        direction.normalize();
        double range = Math.max(0.5, params().num("stake_range"));
        World world = owner.getWorld();
        RayTraceResult wall = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        double reach = wall == null ? range : wall.getHitPosition().distance(eye.toVector());
        LivingEntity victim = firstOnPath(owner, eye, direction, reach);
        double stop = victim == null ? reach : Math.min(reach, Fx.body(victim).distance(eye));
        thrust(owner, eye, direction, stop);
        if (victim == null) {
            // The cooldown starts once this returns: the refund follows a tick later.
            ctx().runLater(owner, 1, () -> {
                double refund = Math.clamp(params().num("miss_refund"), 0, 100) / 100.0;
                double now = ctx().cooldownRemaining(owner);
                if (now > 0 && refund > 0) {
                    ctx().reduceCooldown(owner, now * refund);
                }
            });
            owner.sendActionBar(Text.mm("<gray>말뚝이 허공을 찔렀습니다</gray> <dark_gray>(쿨타임 "
                    + AbilityInfo.formatNumber(params().num("miss_refund")) + "% 회복)</dark_gray>"));
            return ActivationResult.success();
        }
        ctx().runLater(owner, THRUST_TICKS - 1, () -> pierce(owner, victim));
        return ActivationResult.success();
    }

    /** The first enemy whose (padded) body the stake's line meets within {@code reach}, the hidden ones skipped. */
    private @Nullable LivingEntity firstOnPath(Player owner, Location eye, Vector direction, double reach) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        Vector start = eye.toVector();
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, reach + 1.5)) {
            if (isHidden(owner, enemy)) {
                continue;
            }
            BoundingBox box = enemy.getBoundingBox().expand(STAKE_PAD);
            RayTraceResult hit = box.rayTrace(start, direction, reach);
            if (hit == null) {
                continue;
            }
            double distance = hit.getHitPosition().distance(start);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = enemy;
            }
        }
        return best;
    }

    /** The heated stake thrusts out from the hand along the aim and is pulled back, embers flying. */
    private void thrust(Player owner, Location eye, Vector direction, double reach) {
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.clamp(direction.getY(), -1, 1)));
        Location hand = hand(owner, direction);
        double travel = Math.max(0, reach - STAKE_LENGTH * 0.5 - 0.4);
        Vector out = direction.clone().multiply(travel);
        Vector back = direction.clone().multiply(-0.3);
        if (room(2)) {
            BlockDisplay shaft = keep(Displays.block(ctx(), owner, hand, SHAFT.createBlockData(),
                    stakePose(back, yaw, pitch, STAKE_LENGTH), THRUST_TICKS + RETRACT_TICKS + 4));
            BlockDisplay tip = keep(Displays.block(ctx(), owner, hand, TIP.createBlockData(),
                    tipPose(back, direction, yaw, pitch), THRUST_TICKS + RETRACT_TICKS + 4));
            Displays.outline(tip, EMBER_COLOR);
            Displays.animateNextTick(ctx(), owner, shaft, stakePose(out, yaw, pitch, STAKE_LENGTH), (int) THRUST_TICKS);
            Displays.animateNextTick(ctx(), owner, tip, tipPose(out, direction, yaw, pitch), (int) THRUST_TICKS);
            ctx().runLater(owner, THRUST_TICKS + 1, () -> {
                Displays.animate(shaft, stakePose(back, yaw, pitch, STAKE_LENGTH), (int) RETRACT_TICKS);
                Displays.animate(tip, tipPose(back, direction, yaw, pitch), (int) RETRACT_TICKS);
            });
        }
        Location start = hand.clone();
        Location end = eye.clone().add(direction.clone().multiply(Math.max(0.5, reach)));
        Vfx.line(ctx(), start, end, 3, Particle.DUST_COLOR_TRANSITION, HEAT, false);
        Vfx.cloud(ctx(), end, Particle.SMALL_FLAME, 4, 0.1, 0.02, null, false);
        Vfx.cloud(ctx(), start, Particle.SMOKE, 3, 0.08, 0.01, null, false);
        Fx.sound(hand, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(hand, Sound.ITEM_TRIDENT_THROW, 0.9f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(hand, Sound.BLOCK_FIRE_AMBIENT, 0.8f, 1.4f);
    }

    /** The shaft: a long thin pale log along the aim, centered {@code center} from the hand plus half its length. */
    private static Transformation stakePose(Vector offset, float yaw, float pitch, float length) {
        Vector center = offset.clone().add(directionOf(yaw, pitch).multiply(length * 0.5));
        return Displays.transform((float) center.getX(), (float) center.getY(), (float) center.getZ(), STAKE_WIDTH,
                STAKE_WIDTH, length, yaw, pitch, 0);
    }

    /** The ember tip, at the front end of the shaft. */
    private static Transformation tipPose(Vector offset, Vector direction, float yaw, float pitch) {
        Vector at = offset.clone().add(direction.clone().multiply(STAKE_LENGTH + TIP_SIZE * 0.3));
        return Displays.transform((float) at.getX(), (float) at.getY(), (float) at.getZ(), TIP_SIZE, TIP_SIZE,
                TIP_SIZE * 1.6f, yaw, pitch, 45);
    }

    /** The unit vector a transformation's yaw/pitch turns +Z into (yaw 0 is south, pitch positive is down). */
    private static Vector directionOf(float yaw, float pitch) {
        Vector3f v = new Quaternionf().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch))
                .transform(new Vector3f(0, 0, 1));
        return new Vector(v.x(), v.y(), v.z());
    }

    /** The stake goes in: damage, a moment of darkness, and Nobody fades from that one enemy's eyes. */
    private void pierce(Player owner, LivingEntity victim) {
        if (!Combat.isTargetable(ctx(), owner, victim)) {
            return;
        }
        Location head = head(victim);
        Vfx.burst(ctx(), head, Vfx.Palette.of(0xFFE2A8, 0xFF7A2E, 0x5A2A10), 0.6, Particle.SMALL_FLAME, false);
        Vfx.cloud(ctx(), head, Particle.LAVA, 2, 0.1, 0, null, false);
        Fx.sound(head, Sound.BLOCK_FIRE_EXTINGUISH, 0.9f, 1.1f + Fx.jitter(0.1f));
        Fx.sound(head, Sound.ENTITY_GENERIC_BURN, 0.8f, 1.0f);
        Fx.sound(head, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 0.8f);
        Combat.hit(ctx(), owner, victim, Math.max(0, params().num("stake_damage")));
        if (!Combat.isTargetable(ctx(), owner, victim)) {
            return;
        }
        long blind = params().ticks("stake_blind");
        if (blind > 0) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (int) Math.min(Integer.MAX_VALUE,
                    blind), 0, false, true, true));
        }
        long vanish = params().ticks("vanish_duration");
        if (vanish > 0) {
            Vanish previous = vanished.get(victim.getUniqueId());
            if (previous != null) {
                previous.end(false);
            }
            Vanish fade = new Vanish(owner, victim, vanish);
            vanished.put(victim.getUniqueId(), fade);
            fade.start();
        }
    }

    /** Nobody is unseen by one enemy for a while: per viewer, with an owner-only crossed eye counting down over it. */
    private final class Vanish {
        private final Player owner;
        private final LivingEntity victim;
        private final long until;
        private @Nullable TextDisplay mark;
        boolean done;

        Vanish(Player owner, LivingEntity victim, long ticks) {
            this.owner = owner;
            this.victim = victim;
            this.until = ctx().currentTick() + ticks;
        }

        void start() {
            if (victim instanceof Player viewer) {
                visibility.hide(ctx(), viewer, owner);
                viewer.sendActionBar(Text.mm("<" + SEA + "><b>아무도 없다…?</b></" + SEA + "> <gray>오디세우스가 "
                        + "보이지 않습니다</gray>"));
            } else if (victim instanceof Mob mob && owner.equals(mob.getTarget())) {
                mob.setTarget(null);
            }
            Location body = Fx.body(owner);
            Vfx.cloud(ctx(), body, Particle.DUST, 10, 0.35, 0, MIST, false);
            Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 1.2f);
            if (room(1)) {
                mark = keep(privateText(owner, markSpot(), markText(), 0.6f, until - ctx().currentTick() + 5,
                        viewer -> viewer.equals(owner)));
                mark.setTeleportDuration(FOLLOW_LERP);
            }
            syncNameTag(owner);
        }

        void tick(long tick, long now) {
            if (done) {
                return;
            }
            if (now >= until || !Combat.isTargetable(ctx(), owner, victim)) {
                end(false);
                return;
            }
            TextDisplay label = mark;
            if (label != null && label.isValid()) {
                // A victim hidden from Nobody (stealth) must not be given away by its mark.
                label.setViewRange(isHidden(owner, victim) ? 0f : 1.5f);
                Displays.moveTo(label, markSpot());
                if (tick % 4 == 0) {
                    label.text(markText());
                }
            }
        }

        private Location markSpot() {
            return head(victim).add(0, 0.45, 0);
        }

        private Component markText() {
            long left = Math.max(0, until - ctx().currentTick());
            return Text.mm("<" + SEA + ">⊘</" + SEA + "> <" + BRONZE + ">" + Fx.seconds(left) + "</" + BRONZE + ">");
        }

        /** The enemy sees Nobody again ({@code struck}: because Nobody hit it). */
        void end(boolean struck) {
            if (done) {
                return;
            }
            done = true;
            vanished.remove(victim.getUniqueId(), this);
            if (victim instanceof Player viewer) {
                visibility.show(ctx(), viewer.getUniqueId(), owner);
            }
            remove(mark);
            mark = null;
            syncNameTag(owner);
            if (struck && ctx().isAlive(owner)) {
                owner.sendActionBar(Text.mm("<gray>공격해서 모습이 드러났습니다</gray>"));
            }
        }
    }

    private void tickVanished(Player owner, long tick, long now) {
        if (vanished.isEmpty()) {
            return;
        }
        for (Vanish vanish : new ArrayList<>(vanished.values())) {
            vanish.tick(tick, now);
        }
    }

    // ================================================================== ultimate: the Trojan horse

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location spot = horseSpot(owner);
        if (spot == null) {
            return ActivationResult.fail("<gray>목마를 세울 자리가 없습니다</gray>");
        }
        new Horse(owner, spot, owner.getLocation().getYaw()).start();
        return ActivationResult.success();
    }

    /**
     * Where the gift stands: a little short of the aimed enemy, else where the aim meets the ground within reach, else
     * straight under the end of the reach; on safe ground for a rider, inside the arena. Null if there is none.
     */
    private @Nullable Location horseSpot(Player owner) {
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!(direction.lengthSquared() > 1.0E-8)) {
            direction = Motion.horizontalFacing(eye);
        }
        direction.normalize();
        double range = Math.max(1, params().num("horse_range"));
        Location point;
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null) {
            Vector back = Motion.horizontal(owner.getLocation().toVector().subtract(aimed.getLocation().toVector()));
            double gap = Math.min(BEFORE_TARGET, aimed.getLocation().distance(owner.getLocation()));
            point = aimed.getLocation().add(back.multiply(gap)).add(0, 0.5, 0);
        } else {
            RayTraceResult hit = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            point = hit == null ? eye.clone().add(direction.clone().multiply(range))
                    : hit.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.6));
        }
        Body body = Body.standing(owner);
        for (double back = 0; back <= 3; back += 0.5) {
            Location at = point.clone().subtract(Motion.horizontal(direction).multiply(back));
            Location ground = groundUnder(at);
            if (ground != null && Spots.isSafe(ctx(), ground, body)) {
                return ground;
            }
        }
        return null;
    }

    private static @Nullable Location groundUnder(Location point) {
        World world = point.getWorld();
        Location from = point.clone().add(0, 0.05, 0);
        RayTraceResult ground = world.rayTraceBlocks(from, new Vector(0, -1, 0), 24, FluidCollisionMode.NEVER, true);
        if (ground == null) {
            return null;
        }
        Location at = ground.getHitPosition().toLocation(world);
        at.setYaw(0);
        at.setPitch(0);
        return at;
    }

    /** One part of the horse model: block, size, offset (local: +Z is the horse's front) and tilt. */
    private record Part(Material material, float sx, float sy, float sz, float x, float y, float z, float pitch,
                        float roll) {
    }

    /** The gift horse, front along +Z: wheels, legs, body, neck and head, mane and tail, door, ribbons and a bow. */
    private static final List<Part> MODEL = List.of(
            new Part(DARK_WOOD, 1.6f, 0.22f, 3.0f, 0, 0.55f, 0, 0, 0),
            new Part(WHEEL, 0.12f, 0.7f, 0.7f, 0.86f, 0.35f, 1.05f, 0, 0),
            new Part(WHEEL, 0.12f, 0.7f, 0.7f, -0.86f, 0.35f, 1.05f, 0, 0),
            new Part(WHEEL, 0.12f, 0.7f, 0.7f, 0.86f, 0.35f, -1.05f, 0, 0),
            new Part(WHEEL, 0.12f, 0.7f, 0.7f, -0.86f, 0.35f, -1.05f, 0, 0),
            new Part(LEG, 0.26f, 1.05f, 0.26f, 0.42f, 1.18f, 0.85f, 0, 0),
            new Part(LEG, 0.26f, 1.05f, 0.26f, -0.42f, 1.18f, 0.85f, 0, 0),
            new Part(LEG, 0.26f, 1.05f, 0.26f, 0.42f, 1.18f, -0.85f, 0, 0),
            new Part(LEG, 0.26f, 1.05f, 0.26f, -0.42f, 1.18f, -0.85f, 0, 0),
            new Part(HULL, 1.2f, 1.1f, 2.4f, 0, 2.25f, 0, 0, 0),
            new Part(HULL, 0.6f, 1.35f, 0.62f, 0, 3.15f, 1.1f, 25, 0),
            new Part(HULL, 0.56f, 0.56f, 1.05f, 0, 3.8f, 1.55f, 0, 0),
            new Part(DARK_WOOD, 0.42f, 0.24f, 0.1f, 0, 4.18f, 1.3f, 0, 0),
            new Part(DARK_WOOD, 0.14f, 0.95f, 0.5f, 0, 3.45f, 0.82f, 25, 0),
            new Part(DARK_WOOD, 0.14f, 0.85f, 0.16f, 0, 2.15f, -1.38f, -30, 0),
            new Part(DOOR, 0.06f, 0.8f, 0.9f, 0.62f, 2.2f, 0, 0, 0),
            new Part(RIBBON, 1.24f, 1.14f, 0.2f, 0, 2.25f, 0, 0, 0),
            new Part(RIBBON, 0.2f, 1.14f, 2.44f, 0, 2.25f, 0, 0, 0),
            new Part(RIBBON, 0.42f, 0.28f, 0.14f, 0.22f, 2.95f, 0, 0, 30),
            new Part(RIBBON, 0.42f, 0.28f, 0.14f, -0.22f, 2.95f, 0, 0, -30));
    private static final int DOOR_INDEX = 15;

    /** A rider of the horse: who, its seat, the modifiers holding it there and whether it was crouching. */
    private static final class Rider {
        final Player player;
        final Location seat;
        final long boardedAt;
        final List<NamespacedKey> holds = new ArrayList<>(2);
        final List<UUID> hiddenFrom = new ArrayList<>();
        boolean wasSneaking;

        Rider(Player player, Location seat, long boardedAt) {
            this.player = player;
            this.seat = seat;
            this.boardedAt = boardedAt;
            this.wasSneaking = player.isSneaking();
        }
    }

    /**
     * The Trojan horse: a static gift at its spot with the riders hidden inside, until it opens (touched or hit by an
     * enemy, the owner crouching, the time) and everyone bursts out. Its listener lives with its displays: when the
     * owner's ability is released (death, quitting, the match ending, a gambler's borrow ending), the riders are let
     * out and nothing is left registered.
     */
    private final class Horse implements LongPredicate, Listener {

        private final Player owner;
        private final Location spot;
        private final float yaw;
        private final long ticks;
        private final long window;
        private final Map<UUID, Rider> riders = new LinkedHashMap<>();
        /** Allies who got off on their own (or found no room): they do not climb in again. */
        private final Set<UUID> left = new HashSet<>();
        private final List<BlockDisplay> parts = new ArrayList<>();
        private final List<Interaction> bodies = new ArrayList<>(2);
        private @Nullable TextDisplay countdown;
        private boolean registered;
        private boolean released;
        private boolean opened;
        private boolean pendingOpen;
        private @Nullable String openReason;
        private boolean wasSneaking;
        private long run;

        Horse(Player owner, Location spot, float yaw) {
            this.owner = owner;
            this.spot = spot.clone();
            this.yaw = yaw;
            this.ticks = Math.max(1, params().ticks("horse_duration"));
            this.window = params().ticks("board_window");
        }

        void start() {
            build();
            Bukkit.getPluginManager().registerEvents(this, ctx().plugin());
            registered = true;
            Location center = center();
            Vfx.ring(ctx(), spot.clone().add(0, 0.1, 0), 1.9, 22, 0, Particle.DUST, BRONZE_DUST, true);
            Vfx.cloud(ctx(), center, Particle.BLOCK, 20, 0.8, 0.12, HULL.createBlockData(), true);
            Fx.sound(center, Sound.BLOCK_WOOD_PLACE, 1.2f, 0.6f);
            Fx.sound(center, Sound.ENTITY_HORSE_AMBIENT, 0.8f, 0.6f);
            Fx.sound(center, Sound.BLOCK_BARREL_CLOSE, 1.0f, 0.7f);
            board(owner);
            double radius = Math.max(0, params().num("board_radius"));
            for (Player ally : ctx().alliesNear(owner, owner.getLocation(), radius)) {
                if (ally.isSneaking()) {
                    board(ally);
                }
            }
            wasSneaking = owner.isSneaking();
            Loop.repeat(ctx(), owner, 1, 1, ticks, this, () -> open("<gray>때가 되었다!</gray>"));
        }

        private Location center() {
            return spot.clone().add(0, 2.2, 0);
        }

        /** The horse rises plank by plank over its spot, all parts unrotated and turned by the transformation alone. */
        private void build() {
            Location at = Displays.unrotated(spot);
            long life = ticks + COLLAPSE_TICKS + 20;
            int index = 0;
            for (Part part : MODEL) {
                if (!room(1)) {
                    break;
                }
                BlockDisplay display = keep(Displays.block(ctx(), owner, at, part.material().createBlockData(),
                        pose(part, 0.02f, 0, 0), life));
                parts.add(display);
                long delay = 1 + index / 4;
                ctx().runLater(owner, delay, () -> Displays.animate(display, pose(part, 1, 0, 0),
                        (int) RISE_TICKS));
                index++;
            }
            // Interaction boxes are always square and axis-aligned: two of them, front and back along the turned
            // horse, follow its length far better than one big square.
            Vector forward = forward();
            double along = HORSE_HALF_LENGTH - HORSE_HALF_WIDTH;
            for (int sign = -1; sign <= 1; sign += 2) {
                Location center = spot.clone().add(forward.clone().multiply(sign * along));
                Interaction hitbox = at.getWorld().spawn(Displays.unrotated(center), Interaction.class, interaction -> {
                    interaction.setPersistent(false);
                    interaction.setInteractionWidth((float) (HORSE_HALF_WIDTH * 2 + 0.1));
                    interaction.setInteractionHeight((float) HORSE_HEIGHT);
                    interaction.setResponsive(true);
                });
                ctx().track(owner, hitbox);
                ctx().runLater(owner, life, () -> {
                    if (hitbox.isValid()) {
                        hitbox.remove();
                    }
                });
                bodies.add(hitbox);
            }
            if (room(1)) {
                countdown = keep(privateText(owner, spot.clone().add(0, HORSE_HEIGHT + 0.5, 0), countdownText(ticks),
                        0.7f, life, viewer -> viewer.equals(owner) || ctx().isAlly(owner, viewer)));
            }
        }

        /** The horse's front, horizontally (yaw 0 is south, +Z). */
        private Vector forward() {
            double angle = Math.toRadians(yaw);
            return new Vector(-Math.sin(angle), 0, Math.cos(angle));
        }

        /** {@code part} grown by {@code grow}, turned with the horse, rocked {@code rock} degrees, pushed out by {@code burst}. */
        private Transformation pose(Part part, float grow, float rock, float burst) {
            Vector3f local = new Vector3f(part.x(), part.y(), part.z());
            if (burst > 0) {
                Vector3f out = new Vector3f(part.x(), part.y() - 1.0f, part.z());
                if (out.lengthSquared() > 1.0E-6f) {
                    local.add(out.normalize().mul(burst));
                }
            }
            Vector3f offset = new Quaternionf().rotateY((float) Math.toRadians(-yaw)).rotateZ((float) Math.toRadians(rock))
                    .transform(local);
            return Displays.transform(offset.x(), offset.y(), offset.z(), part.sx() * grow, part.sy() * grow,
                    part.sz() * grow, yaw, part.pitch(), part.roll() + rock + burst * 40);
        }

        private Component countdownText(long left) {
            return Text.mm("<" + BRONZE + "><b>목마</b></" + BRONZE + "> <" + SEA + ">" + Fx.seconds(left)
                    + "초</" + SEA + "> <gray>· 웅크리면 출격</gray>");
        }

        /** {@code player} climbs in: hidden from every enemy, see-through for the team, held in its seat. */
        private void board(Player player) {
            UUID id = player.getUniqueId();
            if (riders.containsKey(id) || left.contains(id) || opened || !ctx().isAlive(player)) {
                return;
            }
            Location seat = seatFor(player);
            if (seat == null) {
                // The spot was checked for Nobody's body only: a bigger ally does not fit under a low ceiling.
                left.add(id);
                player.sendActionBar(Text.mm("<gray>목마 안이 좁아 탈 수 없습니다</gray>"));
                return;
            }
            seat.setYaw(player.getLocation().getYaw());
            seat.setPitch(player.getLocation().getPitch());
            Rider rider = new Rider(player, seat.clone(), ctx().currentTick());
            riders.put(id, rider);
            Location from = Fx.body(player);
            player.teleport(seat);
            player.setVelocity(new Vector(0, REST_FALL, 0));
            player.setFallDistance(0);
            for (Player viewer : ctx().alivePlayers()) {
                if (!viewer.equals(player) && ctx().isEnemy(owner, viewer)) {
                    visibility.hide(ctx(), viewer, player);
                    rider.hiddenFrom.add(viewer.getUniqueId());
                }
            }
            long life = ticks + 20;
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) life, 0, true, false, true));
            rider.holds.add(ctx().addModifier(owner, player, Attribute.MOVEMENT_SPEED, -1.0,
                    Operation.MULTIPLY_SCALAR_1, life));
            rider.holds.add(ctx().addModifier(owner, player, Attribute.JUMP_STRENGTH, -1.0,
                    Operation.MULTIPLY_SCALAR_1, life));
            Vfx.trail(from, center(), SEA_COLOR, 8, false);
            Vfx.cloud(ctx(), from, Particle.DUST, 8, 0.3, 0, MIST, false);
            Fx.sound(seat, Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.9f, 0.8f + Fx.jitter(0.05f));
            player.sendActionBar(Text.mm("<" + BRONZE + "><b>트로이 목마</b></" + BRONZE + "> <gray>안에 숨었습니다"
                    + (player.equals(owner) ? "" : " · 다시 웅크리면 내립니다") + "</gray>"));
            syncNameTag(owner);
        }

        /**
         * A seat of its own along the horse that is safe for {@code player}'s body (riders sharing one spot push each
         * other apart on their clients and would be pulled back again and again); a shared one if there is no other,
         * null if none fits.
         */
        private @Nullable Location seatFor(Player player) {
            Body body = Body.standing(player);
            Vector forward = forward();
            Location shared = null;
            for (double offset : SEATS) {
                Location seat = spot.clone().add(forward.clone().multiply(offset));
                if ((offset != 0 && !onGround(seat)) || !Spots.isSafe(ctx(), seat, body)) {
                    continue;
                }
                if (!seatTaken(seat)) {
                    return seat;
                }
                if (shared == null) {
                    shared = seat;
                }
            }
            return shared;
        }

        private boolean seatTaken(Location seat) {
            for (Rider rider : riders.values()) {
                if (horizontal(rider.seat, seat) < SEAT_GAP) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean test(long step) {
            run = step;
            if (released || opened) {
                return false;
            }
            if (pendingOpen) {
                open(openReason);
                return false;
            }
            holdRiders();
            if (step < window) {
                for (Player ally : ctx().alliesNear(owner, spot, BOARD_REACH + HORSE_HALF_LENGTH)) {
                    if (ally.isSneaking() && !riders.containsKey(ally.getUniqueId())) {
                        board(ally);
                    }
                }
            }
            boolean sneaking = owner.isSneaking();
            if (sneaking && !wasSneaking && step >= SNEAK_GRACE) {
                open("<gray>지금이다!</gray>");
                return false;
            }
            wasSneaking = sneaking;
            if (touched()) {
                open("<gray>누군가 선물에 손을 댔다!</gray>");
                return false;
            }
            if (projectileInside()) {
                open("<gray>선물이 맞았다!</gray>");
                return false;
            }
            tickLook(step);
            return true;
        }

        /**
         * Riders stay inside the horse: one pushed out of its hull is put back in its seat (no stale velocity is ever
         * replayed), a nudge inside it is left alone. An ally crouching anew gets off on its own.
         */
        private void holdRiders() {
            long now = ctx().currentTick();
            for (Iterator<Rider> it = riders.values().iterator(); it.hasNext(); ) {
                Rider rider = it.next();
                Player player = rider.player;
                if (!player.isOnline() || !ctx().isAlive(player)) {
                    letOut(rider, false);
                    it.remove();
                    syncNameTag(owner);
                    continue;
                }
                boolean sneaking = player.isSneaking();
                boolean fresh = sneaking && !rider.wasSneaking;
                rider.wasSneaking = sneaking;
                if (fresh && !player.equals(owner) && now - rider.boardedAt >= SNEAK_GRACE) {
                    it.remove();
                    getOff(rider);
                    continue;
                }
                Location at = player.getLocation();
                if (!at.getWorld().equals(spot.getWorld()) || !inBox(at, 0)
                        || Math.abs(at.getY() - rider.seat.getY()) > SEAT_SLIP) {
                    Location seat = rider.seat.clone();
                    seat.setYaw(at.getYaw());
                    seat.setPitch(at.getPitch());
                    player.teleport(seat);
                    player.setVelocity(new Vector(0, REST_FALL, 0));
                }
                player.setFallDistance(0);
            }
        }

        /** An ally steps out on its own: seen again and free, out the side door, but without the burst or the charge. */
        private void getOff(Rider rider) {
            Player player = rider.player;
            left.add(player.getUniqueId());
            letOut(rider, true);
            Vector side = forward().crossProduct(new Vector(0, 1, 0));
            Vector out = Motion.horizontal(player.getLocation().toVector().subtract(spot.toVector()));
            if (out.lengthSquared() < 1.0E-4) {
                out = side;
            }
            player.setVelocity(Motion.clamp(out.normalize().multiply(EXIT_SPEED).setY(EXIT_SPEED * 0.6), MAX_SPEED));
            Fx.sound(player.getLocation(), Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 0.7f, 1.2f + Fx.jitter(0.05f));
            player.sendActionBar(Text.mm("<gray>목마에서 내렸습니다</gray>"));
            syncNameTag(owner);
        }

        /** An enemy's body within the horse's box (plus a hair): it touched the gift. */
        private boolean touched() {
            for (LivingEntity enemy : ctx().enemiesNear(owner, spot, HORSE_HALF_LENGTH + 2)) {
                if (riders.containsKey(enemy.getUniqueId())) {
                    continue;
                }
                if (inBox(enemy.getLocation(), enemy.getWidth() / 2 + TOUCH_MARGIN)
                        && enemy.getLocation().getY() < spot.getY() + HORSE_HEIGHT
                        && enemy.getLocation().getY() + enemy.getHeight() > spot.getY()) {
                    return true;
                }
            }
            return false;
        }

        /** True if {@code at} lies within the horse's footprint grown by {@code margin}, in the horse's own axes. */
        private boolean inBox(Location at, double margin) {
            double dx = at.getX() - spot.getX();
            double dz = at.getZ() - spot.getZ();
            double angle = Math.toRadians(yaw);
            double forward = -Math.sin(angle) * dx + Math.cos(angle) * dz;
            double side = Math.cos(angle) * dx + Math.sin(angle) * dz;
            return Math.abs(side) <= HORSE_HALF_WIDTH + margin && Math.abs(forward) <= HORSE_HALF_LENGTH + margin;
        }

        /**
         * An enemy's arrow (or any projectile) flying into the horse: it was hit. Arrows lying in the ground or stuck
         * in a wall from earlier stay entities: they never count, or the gift would open the moment it stands.
         */
        private boolean projectileInside() {
            BoundingBox box = BoundingBox.of(spot.clone().add(0, HORSE_HEIGHT / 2, 0), HORSE_HALF_LENGTH,
                    HORSE_HEIGHT / 2, HORSE_HALF_LENGTH);
            for (Entity entity : spot.getWorld().getNearbyEntities(box)) {
                if (entity instanceof Projectile projectile && inFlight(projectile) && isEnemyShot(projectile)
                        && inBox(projectile.getLocation(), 0.1)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isEnemyShot(Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            Player attacker = shooter instanceof Entity entity ? ctx().resolveAttacker(entity) : null;
            if (attacker == null) {
                attacker = ctx().resolveAttacker(projectile);
            }
            return attacker != null && ctx().isEnemy(owner, attacker);
        }

        /** The gift breathes: a soft rock now and then, a creak, the team's countdown and bronze motes. */
        private void tickLook(long step) {
            TextDisplay label = countdown;
            if (label != null && label.isValid() && step % 5 == 0) {
                label.text(countdownText(ticks - step));
            }
            if (step % CREAK_PERIOD == 0 && step > 0) {
                Fx.sound(center(), Sound.BLOCK_WOOD_STEP, 0.6f, 0.6f + Fx.jitter(0.05f));
                float rock = (float) (2.5 * Math.sin(step * 0.7));
                for (int i = 0; i < parts.size(); i++) {
                    BlockDisplay part = parts.get(i);
                    if (part.isValid()) {
                        Displays.animate(part, pose(MODEL.get(i), 1, rock, 0), 10);
                    }
                }
            }
            if (step % 8 == 0) {
                Vfx.dot(spot.clone().add(Math.cos(step * 0.4) * 1.2, 2.9 + Math.sin(step * 0.3) * 0.2,
                        Math.sin(step * 0.4) * 1.2), Particle.DUST, BRONZE_SMALL, false);
            }
            if (ticks - step <= 20 && step % 4 == 0) {
                BlockDisplay door = parts.size() > DOOR_INDEX ? parts.get(DOOR_INDEX) : null;
                if (door != null && door.isValid()) {
                    Displays.animate(door, pose(MODEL.get(DOOR_INDEX), 1, (step % 8 == 0) ? 1.5f : -1.5f, 0), 2);
                }
                Fx.sound(center(), Sound.BLOCK_WOODEN_DOOR_OPEN, 0.3f, 1.8f);
            }
        }

        /** The door flies open: everyone bursts out, the enemies around are thrown, the horse falls apart. */
        void open(@Nullable String reason) {
            if (opened || released) {
                return;
            }
            opened = true;
            Location center = center();
            Location ground = spot.clone().add(0, 0.2, 0);
            World world = spot.getWorld();
            Vfx.dot(center, Particle.FLASH, BRONZE_COLOR, true);
            world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 40), 1.0, 1.2, 1.0, 0.25, HULL.createBlockData(),
                    true);
            world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 14), 0.6, 0.6, 0.6, 0.2,
                    RIBBON.createBlockData(), true);
            world.spawnParticle(Particle.POOF, center, Vfx.n(ctx(), 18), 1.0, 1.0, 1.0, 0.05, null, true);
            double radius = Math.max(0, params().num("burst_radius"));
            Vfx.shockwave(ctx(), owner, ground, 0.6, Math.max(0.6, radius), 7, Particle.DUST_COLOR_TRANSITION, GLOW,
                    true);
            Fx.sound(center, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.2f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.3f);
            Fx.sound(center, Sound.ITEM_GOAT_HORN_SOUND_0, 1.0f, 1.2f);
            collapse();
            double damage = Math.max(0, params().num("burst_damage"));
            double up = Motion.launchSpeed(Math.max(0, params().num("burst_lift")));
            for (LivingEntity enemy : ctx().enemiesNear(owner, ground, radius)) {
                if (isHidden(owner, enemy) || riders.containsKey(enemy.getUniqueId())) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(spot.toVector()));
                    if (away.lengthSquared() < 1.0E-6) {
                        away = Motion.horizontalFacing(owner.getLocation());
                    }
                    Motion.push(enemy, away.multiply(0.5).setY(up), MAX_SPEED);
                }
            }
            List<Rider> out = new ArrayList<>(riders.values());
            riders.clear();
            int index = 0;
            for (Rider rider : out) {
                letOut(rider, true);
                Player player = rider.player;
                if (ctx().isAlive(player)) {
                    double angle = Math.toRadians(yaw) + Math.PI / 2 + index * (Math.PI * 2 / Math.max(1, out.size()));
                    Vector away = new Vector(-Math.sin(angle), 0, Math.cos(angle));
                    player.setVelocity(Motion.clamp(away.multiply(EXIT_SPEED).setY(EXIT_SPEED), MAX_SPEED));
                    if (player.equals(owner)) {
                        landing.arm(ctx().currentTick(), 60);
                    }
                    charge(player);
                }
                index++;
            }
            if (ctx().isAlive(owner)) {
                owner.sendActionBar(Text.mm("<" + BRONZE + "><b>출격!</b></" + BRONZE + "> " + (reason == null ? ""
                        : reason)));
            }
            release();
        }

        /** The planks fly apart, spinning and shrinking. */
        private void collapse() {
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                if (!part.isValid()) {
                    continue;
                }
                Displays.animate(part, pose(MODEL.get(i), 0.4f, 0, 1.6f), (int) COLLAPSE_TICKS);
                ctx().runLater(owner, COLLAPSE_TICKS, () -> {
                    if (part.isValid()) {
                        part.remove();
                    }
                });
            }
            parts.clear();
            remove(countdown);
            countdown = null;
            for (Interaction hitbox : bodies) {
                if (hitbox.isValid()) {
                    hitbox.remove();
                }
            }
            bodies.clear();
        }

        /** A rider comes out: seen again, free to move, no longer see-through. */
        private void letOut(Rider rider, boolean fx) {
            Player player = rider.player;
            for (NamespacedKey key : rider.holds) {
                ctx().removeModifier(player, key);
            }
            rider.holds.clear();
            for (UUID viewer : rider.hiddenFrom) {
                visibility.show(ctx(), viewer, player);
            }
            rider.hiddenFrom.clear();
            PotionEffect invisible = player.getPotionEffect(PotionEffectType.INVISIBILITY);
            if (invisible != null && invisible.isAmbient() && !invisible.hasParticles()) {
                player.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
            if (fx && player.isOnline()) {
                Vfx.cloud(ctx(), Fx.body(player), Particle.DUST, 6, 0.3, 0, WOOD_DUST, false);
            }
            if (player.equals(owner)) {
                syncNameTag(owner);
            }
        }

        /** Fresh out of the horse: harder blows and quicker feet for a while. */
        private void charge(Player player) {
            long ticks = params().ticks("charge_duration");
            if (ticks <= 0) {
                return;
            }
            double speed = Math.max(0, params().num("charge_speed")) / 100.0;
            if (speed > 0) {
                ctx().addModifier(owner, player, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1, ticks);
            }
            charged.put(player.getUniqueId(), ctx().currentTick() + ticks);
            player.sendActionBar(Text.mm("<" + BRONZE + "><b>기습!</b></" + BRONZE + "> <gray>" + Fx.seconds(ticks)
                    + "초간 피해 +" + AbilityInfo.formatNumber(params().num("charge_damage")) + "% · 이동 속도 +"
                    + AbilityInfo.formatNumber(params().num("charge_speed")) + "%</gray>"));
            Loop.repeat(ctx(), owner, 1, 3, Math.max(1, ticks / 3), step -> {
                if (!player.isOnline()) {
                    return false;
                }
                Vfx.dot(player.getLocation().add(0, 0.1, 0), Particle.DUST, step % 2 == 0 ? BRONZE_SMALL : SEA_DUST,
                        false);
                return true;
            });
        }

        /** Riders fresh out of the horse, until when they hit harder. */
        private final Map<UUID, Long> charged = new HashMap<>();
        /** Keeps the listener alive for the charge after the horse is gone; its removal ends it. */
        private @Nullable TextDisplay chargeAnchor;

        // ------------------------------------------------------------------ the horse's listener

        /** Riders take no damage; an enemy's blow at one of them (an area hit, a lucky shot) hits the gift. */
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onRiderHurt(EntityDamageEvent event) {
            if (released || opened || !riders.containsKey(event.getEntity().getUniqueId())) {
                return;
            }
            DamageType type = event.getDamageSource().getDamageType();
            if (type == DamageType.GENERIC_KILL || type == DamageType.OUT_OF_WORLD || type == DamageType.OUTSIDE_BORDER) {
                return;
            }
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Player attacker = ctx().resolveAttacker(byEntity.getDamager());
                if (attacker != null && ctx().isEnemy(owner, attacker)) {
                    requestOpen("<gray>선물이 맞았다!</gray>");
                }
            }
        }

        /** Riders cannot fight from inside; riders fresh out of it hit harder. */
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onRiderAttack(EntityDamageByEntityEvent event) {
            Player attacker = ctx().resolveAttacker(event.getDamager());
            if (attacker == null) {
                return;
            }
            if (!opened && !released && riders.containsKey(attacker.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            Long until = charged.get(attacker.getUniqueId());
            if (until != null && until > ctx().currentTick() && event.getEntity() instanceof LivingEntity victim
                    && ctx().isEnemy(owner, victim)) {
                event.setDamage(event.getDamage() * (1 + Math.max(0, params().num("charge_damage")) / 100.0));
            }
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onRiderKnockback(EntityKnockbackEvent event) {
            if (!released && !opened && riders.containsKey(event.getEntity().getUniqueId())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPunch(PrePlayerAttackEntityEvent event) {
            if (isBody(event.getAttacked()) && ctx().isEnemy(owner, event.getPlayer())) {
                requestOpen("<gray>누군가 선물을 두드렸다!</gray>");
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPoke(PlayerInteractEntityEvent event) {
            if (isBody(event.getRightClicked()) && ctx().isEnemy(owner, event.getPlayer())) {
                requestOpen("<gray>누군가 선물을 열어 보려 했다!</gray>");
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onShot(ProjectileHitEvent event) {
            if (isBody(event.getHitEntity()) && isEnemyShot(event.getEntity())) {
                requestOpen("<gray>선물이 맞았다!</gray>");
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            Rider rider = riders.remove(event.getPlayer().getUniqueId());
            if (rider != null) {
                letOut(rider, false);
            }
        }

        /** The horse's parts go when the owner's ability is released: so do the riders' hiding and this listener. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onRemove(EntityRemoveEvent event) {
            Entity removed = event.getEntity();
            if (removed.equals(chargeAnchor)) {
                chargeAnchor = null;
                unregister();
                return;
            }
            if (released || opened) {
                return;
            }
            if (parts.contains(removed) || isBody(removed) || removed.equals(countdown)) {
                for (Rider rider : new ArrayList<>(riders.values())) {
                    letOut(rider, false);
                }
                riders.clear();
                release();
            }
        }

        private boolean isBody(@Nullable Entity entity) {
            return entity instanceof Interaction hitbox && bodies.contains(hitbox);
        }

        private void requestOpen(String reason) {
            if (!opened && !released && !pendingOpen) {
                pendingOpen = true;
                openReason = reason;
            }
        }

        /**
         * The horse's fight is over. The listener stays for the riders' charge bonus, tied to a tracked anchor: it goes
         * when the charge runs out or with the owner's ability, whichever comes first.
         */
        void release() {
            if (released) {
                return;
            }
            released = true;
            long left = 0;
            for (Long until : charged.values()) {
                left = Math.max(left, until - ctx().currentTick());
            }
            if (left > 0 && ctx().isAlive(owner)) {
                chargeAnchor = Displays.text(ctx(), owner, owner.getLocation(), Component.empty(), 0.01f, left + 1);
                chargeAnchor.setViewRange(0f);
            } else {
                unregister();
            }
        }

        private void unregister() {
            if (registered) {
                HandlerList.unregisterAll(this);
                registered = false;
            }
        }
    }

    private static double horizontal(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ================================================================== helpers

    /** Just in front of the right hand, along the aim. */
    private static Location hand(Player owner, Vector direction) {
        Location eye = owner.getEyeLocation();
        Vector right = direction.getCrossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        }
        right.normalize();
        return Displays.unrotated(eye.add(direction.clone().multiply(0.4)).add(right.multiply(0.3)).subtract(0, 0.3,
                0));
    }

    private static Location head(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() + HEAD_GAP, 0);
    }

    /**
     * A camera-facing text display at {@code at} that only the players {@code viewer} accepts see: hidden from everyone
     * else in the tick it spawns, and from whoever joins later (an enemy rejoining the match is a new player object
     * that would see every entity), see {@link #hidePrivateFrom}.
     */
    private TextDisplay privateText(Player owner, Location at, Component text, float scale, long life,
                                    Predicate<Player> viewer) {
        TextDisplay display = Displays.text(ctx(), owner, at, text, scale, life);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!viewer.test(online)) {
                online.hideEntity(ctx().plugin(), display);
            }
        }
        privateDisplays.put(display, viewer);
        return display;
    }

    /** A player joined (or rejoined) the server: the private displays it may not see are hidden from it too. */
    private void hidePrivateFrom(Player joined) {
        privateDisplays.keySet().removeIf(display -> !display.isValid());
        for (Map.Entry<Display, Predicate<Player>> entry : privateDisplays.entrySet()) {
            if (!entry.getValue().test(joined)) {
                joined.hideEntity(ctx().plugin(), entry.getKey());
            }
        }
        syncNameTag(player());
    }

    /** True if {@code feet} stand right on the ground (not over a step down or a hole). */
    private static boolean onGround(Location feet) {
        RayTraceResult ground = feet.getWorld().rayTraceBlocks(feet.clone().add(0, 0.05, 0), new Vector(0, -1, 0), 0.1,
                FluidCollisionMode.NEVER, true);
        return ground != null;
    }

    /** A projectile still flying: not an arrow lying in the ground or stuck in a wall. */
    private static boolean inFlight(Projectile projectile) {
        return projectile.isValid() && !(projectile instanceof AbstractArrow arrow && arrow.isInBlock());
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
