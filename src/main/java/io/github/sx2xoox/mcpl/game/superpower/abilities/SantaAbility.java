package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 산타 — a support that hands out presents. A gift appears in the sack now and then and flies to whoever on Santa's
 * side is hurt the worst; enemies who hit Santa land on the naughty list. The active throws a present that opens on
 * landing: a gift for Santa and the allies there, a burst of coal for the enemies (worse for the naughty). The
 * ultimate raises a Christmas tree that heals and hastens Santa's side, wraps every enemy around it in string lights
 * and ends with its star bursting into fireworks. The tree reaches the ground around its foot (not a roof or a ledge
 * high over it), and its lights and its star only reach enemies who can see it.
 *
 * <p>The gifts come from Santa's own sack (the passive): a tree borrowed by the gambler works like Santa's, but the
 * gambler gets no gifts.
 *
 * <p>Look: Christmas red, snow white, pine green and gold. Presents are red, green or white boxes with gold ribbons
 * and a bow; a ready gift bobs over Santa's head. Naughty enemies carry a lump of coal over their heads. The coal
 * burst throws coal lumps and black smoke, the gift sheds confetti, hearts and golden sparkles. The tree grows layer
 * by layer from the ground (trunk, four tiers of spruce, baubles and a glowing star), strings of colored lights wind
 * around it and lash out to the enemies, snow falls around it, sleigh bells play Jingle Bells, and the star finally
 * bursts into colored fireworks.
 */
public final class SantaAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("santa", "산타", Material.RED_BUNDLE)
            .color(TextColor.color(0xE8384F))
            .difficulty(1)
            .passive("선물 보따리",
                    "{gift_interval}초마다 보따리에 선물이 하나 생깁니다. 반경 {gift_radius}블록 안에 다친 사람이 있으면 체력 "
                            + "비율이 가장 낮은 나 또는 아군에게 선물이 날아가 {gift_heal} 회복과 {gift_buff}초간 무작위 축복"
                            + "(신속 I·재생 I·저항 I)을 줍니다. 나를 공격한 적은 {naughty_duration}초간 나쁜 아이 명단에 올라 "
                            + "내 석탄에 {naughty_bonus}% 더 큰 피해를 받습니다.")
            .active("선물 상자",
                    "조준한 곳({throw_range}블록 이내)으로 선물 상자를 던집니다. 상자가 떨어지면 반경 {throw_radius}블록 "
                            + "안의 나와 아군은 선물을 받아 {throw_heal} 회복하고, 적은 석탄 폭발에 휘말려 {coal_damage} 피해를 "
                            + "입고 밀려나며 {coal_blind}초간 앞이 보이지 않습니다.",
                    9)
            .ultimate("크리스마스의 기적",
                    "앞에 거대한 크리스마스 트리가 {ult_duration}초간 솟아납니다. 트리 둘레 반경 {ult_radius}블록 안의 나와 "
                            + "아군은 {ult_heal_interval}초마다 {ult_heal} 회복하고 신속 I을 얻습니다. 트리가 다 자라는 "
                            + "{ult_bind_delay}초 뒤 반경 안에서 트리가 보이는 적은 전구 줄에 칭칭 감겨 {ult_bind_damage} "
                            + "피해를 입고 {ult_bind}초간 움직이지 못합니다. 트리가 사라질 때 꼭대기의 별이 터져 반경 안에서 "
                            + "트리가 보이는 적에게 {ult_finale_damage} 피해를 줍니다.")
            .param("gift_interval", 15)
            .param("gift_radius", 10)
            .param("gift_heal", 3)
            .param("gift_buff", 5)
            .param("naughty_duration", 8)
            .param("naughty_bonus", 50)
            .param("throw_range", 20)
            .param("throw_radius", 3)
            .param("throw_heal", 3)
            .param("coal_damage", 4)
            .param("coal_blind", 1)
            .param("ult_duration", 7)
            .param("ult_radius", 8)
            .param("ult_heal_interval", 1)
            .param("ult_heal", 1)
            .param("ult_bind_delay", 0.75)
            .param("ult_bind", 1.75)
            .param("ult_bind_damage", 2)
            .param("ult_finale_damage", 4)
            .build();

    private static final String RED_TAG = "#E8384F";
    private static final String GREEN_TAG = "#2FA866";
    private static final String GOLD_TAG = "#FFD166";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Warm white, Christmas red, deep pine. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF6E0, 0xE8384F, 0x1E6B45);
    private static final Color RED = Color.fromRGB(0xE8384F);
    private static final Color GREEN = Color.fromRGB(0x2FA866);
    private static final Color GOLD = Color.fromRGB(0xFFD166);
    private static final Color WHITE = Color.fromRGB(0xFFFFFF);
    private static final Color BLUE = Color.fromRGB(0x5AB4FF);
    /** The colors of the string lights and the confetti. */
    private static final Color[] FESTIVE = {RED, GREEN, GOLD, WHITE, BLUE};
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 1.0f);
    private static final Particle.DustOptions SOOT = new Particle.DustOptions(Color.fromRGB(0x1C1C1C), 1.4f);
    private static final Particle.DustTransition EMBER = new Particle.DustTransition(Color.fromRGB(0xFF7A2E),
            Color.fromRGB(0x2B2B2B), 1.1f);

    /** Block and item types of the visuals; block data and items are created on use (they need a running server). */
    private static final Material[] BOX_COLORS = {Material.RED_CONCRETE, Material.GREEN_CONCRETE,
            Material.WHITE_CONCRETE};
    private static final Material RIBBON = Material.YELLOW_CONCRETE;
    private static final Material BOW = Material.GOLD_BLOCK;
    private static final Material COAL_ITEM = Material.COAL;
    private static final Material TRUNK = Material.SPRUCE_LOG;
    private static final Material LEAVES = Material.SPRUCE_LEAVES;
    private static final Material STAR = Material.GOLD_BLOCK;
    private static final Material[] BAUBLES = {Material.RED_CONCRETE, Material.GOLD_BLOCK,
            Material.LIGHT_BLUE_CONCRETE, Material.WHITE_CONCRETE, Material.MAGENTA_CONCRETE, Material.ORANGE_CONCRETE};

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 1.5;

    // passive: the sack
    private static final long GIFT_CHECK_PERIOD = 5;
    private static final double HOVER_HEIGHT = 0.75;
    private static final float HOVER_SIZE = 0.36f;
    private static final float HOVER_SPIN = 5f;
    private static final int GIFT_FLIGHT_TICKS = 10;
    private static final long JINGLE_PERIOD = 40;
    /** Naughty marks over enemies, at most this many at a time. */
    private static final int MAX_NAUGHTY_MARKS = 4;
    private static final long NAUGHTY_MARK_PERIOD = 2;

    // active: the present
    private static final float THROWN_SIZE = 0.6f;
    private static final double COAL_PUSH = 0.8;
    private static final double COAL_LIFT = 0.35;
    private static final int COAL_LUMPS = 4;
    /** Ground under the aim is searched this far below the aimed point. */
    private static final double AIM_GROUND_SNAP = 6;
    /** A lob is lowered by these shares of its height until nothing (a ceiling, a wall) is in the way of the box. */
    private static final double[] ARC_SHARES = {1.0, 0.75, 0.5, 0.25, 0.0};
    /** The thrown present's extent around its middle: half its box, and up to the top of its bow. */
    private static final double PRESENT_HALF = 0.25;
    private static final double PRESENT_TOP = 0.45;

    // ultimate: the tree
    private static final double TREE_AHEAD = 2.0;
    /** The tree's tiers: width, height and the height of their bottom, from the ground. */
    private static final float[][] TIERS = {{3.2f, 1.1f, 0.85f}, {2.5f, 1.0f, 1.7f}, {1.8f, 0.9f, 2.5f},
            {1.1f, 0.8f, 3.25f}};
    private static final float STAR_HEIGHT = 4.4f;
    /** Display entities of a whole tree: trunk, star, two boxes per tier and the baubles. */
    private static final int TREE_DISPLAYS = 2 + 4 * 2 + 6;
    /** The tree reaches this far above and below its foot: a ledge or a roof over it is out of its reach. */
    private static final double TREE_ABOVE = 2.5;
    private static final double TREE_BELOW = 2.0;
    /** The heights on the tree its lights and its star are seen from (trunk and crown). */
    private static final double[] TREE_EYES = {1.5, 3.0};
    private static final float STAR_SIZE = 0.48f;
    private static final int TIER_GROW_TICKS = 5;
    private static final long FINALE_TICKS = 6;
    /** Jingle Bells (public domain) as note-block notes (10 = E4) and their lengths in ticks. */
    private static final int[] JINGLE = {10, 10, 10, 10, 10, 10, 10, 13, 6, 8, 10};
    private static final int[] JINGLE_TICKS = {4, 4, 8, 4, 4, 8, 4, 4, 6, 2, 12};

    /** Every display this ability currently has alive, for the budget. */
    private final List<Display> displays = new ArrayList<>();
    /**
     * The displays that make way for the tree when the budget is tight: naughty marks (they come back), coal lumps and
     * opened presents (both about to vanish).
     */
    private final List<Display> optional = new ArrayList<>();
    /** Enemies on the naughty list, by entity id. */
    private final Map<UUID, Naughty> naughty = new HashMap<>();
    private long nextGiftAt = Long.MAX_VALUE;
    private boolean giftReady;
    private boolean giftFlying;
    private @Nullable GiftBox hovering;
    private float hoverSpin;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        nextGiftAt = ctx().currentTick() + Math.max(1, params().ticks("gift_interval"));
    }

    @Override
    public void onRevoke() {
        // Displays are owner-scoped: the framework removes them. Only forget them here.
        displays.clear();
        optional.clear();
        naughty.clear();
        hovering = null;
        giftReady = false;
        giftFlying = false;
    }

    // ------------------------------------------------------------------ passive: the sack

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        if (!giftReady && !giftFlying && now >= nextGiftAt) {
            giftReady = true;
            readyFx(owner);
        }
        if (giftReady) {
            hover(owner, tick);
            if (tick % GIFT_CHECK_PERIOD == 0) {
                LivingEntity recipient = neediest(owner);
                if (recipient != null) {
                    sendGift(owner, recipient);
                }
            }
        } else if (tick % JINGLE_PERIOD == 0) {
            // A faint jingle while the next gift is being wrapped.
            Location hip = owner.getLocation().add(0, owner.getHeight() * 0.45, 0);
            Vfx.dot(Vfx.scatter(hip, 0.4), Particle.DUST, GOLD_DUST, false);
            owner.playSound(owner, Sound.BLOCK_NOTE_BLOCK_BELL, 0.25f, pitch(22));
        }
        if (tick % NAUGHTY_MARK_PERIOD == 0 && !naughty.isEmpty()) {
            tickNaughty(owner, now, tick);
        }
    }

    /** The one on Santa's side within reach (Santa included) with the lowest share of health, if anyone is hurt. */
    private @Nullable LivingEntity neediest(Player owner) {
        double radius = Math.max(0, params().num("gift_radius"));
        List<LivingEntity> candidates = new ArrayList<>();
        candidates.add(owner);
        candidates.addAll(ctx().alliesNear(owner, owner.getLocation(), radius));
        LivingEntity best = null;
        double lowest = 1.0;
        for (LivingEntity candidate : candidates) {
            if (!candidate.isValid() || candidate.isDead()) {
                continue;
            }
            double max = Combat.maxHealth(candidate);
            if (candidate.getHealth() >= max - 0.25) {
                continue;
            }
            double ratio = candidate.getHealth() / max;
            if (ratio < lowest) {
                lowest = ratio;
                best = candidate;
            }
        }
        return best;
    }

    private void readyFx(Player owner) {
        Location over = hoverSpot(owner, 0);
        Vfx.cloud(ctx(), over, Particle.END_ROD, 6, 0.25, 0.03, null, false);
        Vfx.ring(ctx(), over, 0.45, 12, 0, Particle.DUST, GOLD_DUST, false);
        Fx.sound(over, Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, pitch(13));
        ctx().runLater(owner, 3, () -> Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, pitch(17)));
        Fx.sound(over, Sound.ITEM_BUNDLE_INSERT, 0.8f, 1.2f);
        owner.sendActionBar(Text.mm("<" + RED_TAG + ">🎁 선물 준비 완료</" + RED_TAG + "> <gray>다친 사람에게 날아갑니다</gray>"));
    }

    private Location hoverSpot(Player owner, long tick) {
        double bob = 0.07 * Math.sin(tick * 0.2);
        return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() + HOVER_HEIGHT + bob, 0));
    }

    /** The ready gift bobs and turns over Santa's head. */
    private void hover(Player owner, long tick) {
        GiftBox box = hovering;
        if (box == null) {
            // Unwrapped from the sack this tick; it grows into shape from the next one (interpolation needs a tick).
            hovering = new GiftBox(hoverSpot(owner, tick), HOVER_SIZE, randomBox(), 0);
            return;
        }
        hoverSpin += HOVER_SPIN;
        box.place(hoverSpot(owner, tick), HOVER_SIZE, hoverSpin, 1);
        if (tick % 10 == 0) {
            Vfx.dot(Vfx.scatter(hoverSpot(owner, tick).add(0, 0.3, 0), 0.3), Particle.END_ROD, null, false);
        }
    }

    /** The ready gift flies from over Santa's head to the one who needs it, homing in, and opens on arrival. */
    private void sendGift(Player owner, LivingEntity recipient) {
        giftReady = false;
        giftFlying = true;
        GiftBox box = hovering;
        hovering = null;
        Location start = hoverSpot(owner, 0);
        GiftBox flying = box != null ? box : new GiftBox(start, HOVER_SIZE, randomBox(), GIFT_FLIGHT_TICKS + 12);
        boolean self = recipient.equals(owner);
        Fx.sound(start, Sound.ITEM_BUNDLE_DROP_CONTENTS, 0.8f, 1.3f);
        Fx.sound(start, Sound.ENTITY_EXPERIENCE_BOTTLE_THROW, 0.6f, 1.2f + Fx.jitter(0.1f));
        Loop.repeat(ctx(), owner, 1, 1, GIFT_FLIGHT_TICKS, run -> {
            if (!stillThere(recipient, start.getWorld())) {
                return false;
            }
            double t = (run + 1) / (double) GIFT_FLIGHT_TICKS;
            Location end = Fx.body(recipient).add(0, self ? 0.1 : 0.2, 0);
            Location at = start.clone().add(end.toVector().subtract(start.toVector()).multiply(t));
            at.add(0, self ? 0 : Math.sin(t * Math.PI) * 1.2, 0);
            hoverSpin += 18;
            flying.place(at, HOVER_SIZE, hoverSpin, 1);
            Vfx.dot(at, Particle.DUST, new Particle.DustOptions(FESTIVE[(int) run % FESTIVE.length], 0.8f), false);
            if (run % 2 == 0) {
                Vfx.dot(at, Particle.END_ROD, null, false);
            }
            return true;
        }, () -> {
            giftFlying = false;
            if (stillThere(recipient, start.getWorld())) {
                nextGiftAt = ctx().currentTick() + Math.max(1, params().ticks("gift_interval"));
                openGift(owner, recipient, flying);
            } else {
                // The one it was meant for is gone: the gift flies back into the sack, still ready.
                giftReady = true;
                hovering = flying;
            }
        });
    }

    /** True while {@code recipient} can still take a gift: valid, alive, in the fight and in {@code world}. */
    private boolean stillThere(LivingEntity recipient, World world) {
        return recipient.isValid() && !recipient.isDead() && recipient.getWorld().equals(world)
                && !(recipient instanceof Player person && !ctx().isAlive(person));
    }

    /** The gift opens: health and a random blessing, confetti, hearts and a golden ring. */
    private void openGift(Player owner, LivingEntity recipient, GiftBox box) {
        Location body = Fx.body(recipient);
        box.burst(body);
        double heal = Math.max(0, params().num("gift_heal"));
        if (heal > 0) {
            ctx().heal(recipient, heal);
        }
        PotionEffectType[] blessings = {PotionEffectType.SPEED, PotionEffectType.REGENERATION,
                PotionEffectType.RESISTANCE};
        String[] names = {"신속 I", "재생 I", "저항 I"};
        int pick = ThreadLocalRandom.current().nextInt(blessings.length);
        int ticks = (int) Math.min(Integer.MAX_VALUE, params().ticks("gift_buff"));
        if (ticks > 0) {
            recipient.addPotionEffect(new PotionEffect(blessings[pick], ticks, 0, false, true, true));
        }
        giftFx(body);
        String text = "<" + RED_TAG + ">🎁 산타의 선물</" + RED_TAG + "> <gray>체력</gray> <white>+"
                + AbilityInfo.formatNumber(heal) + "</white><dark_gray> · </dark_gray><" + GOLD_TAG + ">" + names[pick]
                + "</" + GOLD_TAG + ">";
        if (recipient instanceof Player receiver) {
            receiver.sendActionBar(Text.mm(text));
        }
        if (!recipient.equals(owner)) {
            Vfx.trail(Fx.body(owner), body, GOLD, 10, false);
        }
    }

    /** A gift opens on someone: confetti in every festive color, hearts and a golden ring at the feet. */
    private void giftFx(Location body) {
        World world = body.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < Vfx.n(ctx(), 14); i++) {
            Location at = body.clone().add(random.nextDouble(-0.5, 0.5), random.nextDouble(-0.2, 0.8),
                    random.nextDouble(-0.5, 0.5));
            Vfx.dot(at, Particle.DUST, new Particle.DustOptions(FESTIVE[i % FESTIVE.length], 1.0f), false);
        }
        world.spawnParticle(Particle.HEART, body.clone().add(0, 0.6, 0), Vfx.n(ctx(), 3), 0.35, 0.2, 0.35, 0);
        world.spawnParticle(Particle.HAPPY_VILLAGER, body, Vfx.n(ctx(), 6), 0.35, 0.45, 0.35, 0);
        Vfx.ring(ctx(), body.clone().subtract(0, 0.8, 0), 0.6, 14, random.nextDouble(TAU), Particle.DUST, GOLD_DUST,
                false);
        Fx.sound(body, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, pitch(18));
        Fx.sound(body, Sound.ITEM_BUNDLE_REMOVE_ONE, 0.8f, 1.1f);
    }

    // ------------------------------------------------------------------ passive: the naughty list

    /** Whoever hits Santa goes on the naughty list (or stays on it longer). */
    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0 || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Entity damager = byEntity.getDamager();
        LivingEntity culprit = ctx().resolveAttacker(damager);
        if (culprit == null && damager instanceof LivingEntity living) {
            culprit = living;
        }
        if (culprit == null || !ctx().isEnemy(player(), culprit)) {
            return;
        }
        long until = ctx().currentTick() + Math.max(1, params().ticks("naughty_duration"));
        Naughty entry = naughty.get(culprit.getUniqueId());
        if (entry == null) {
            entry = new Naughty(culprit);
            naughty.put(culprit.getUniqueId(), entry);
            listFx(culprit);
        }
        entry.until = until;
    }

    /** One enemy on the naughty list: until when, and the lump of coal hovering over its head. */
    private static final class Naughty {
        final LivingEntity enemy;
        long until;
        @Nullable ItemDisplay coal;

        Naughty(LivingEntity enemy) {
            this.enemy = enemy;
        }
    }

    private boolean isNaughty(LivingEntity enemy) {
        Naughty entry = naughty.get(enemy.getUniqueId());
        return entry != null && entry.until > ctx().currentTick();
    }

    /** Santa writes the name down: a scratch of the quill and a lump of coal popping up over the culprit. */
    private void listFx(LivingEntity culprit) {
        Player owner = player();
        owner.playSound(owner, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
        owner.playSound(owner, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, pitch(3));
        owner.sendActionBar(Text.mm("<" + RED_TAG + ">📜 나쁜 아이 명단</" + RED_TAG + "> <gray>석탄 피해 +"
                + AbilityInfo.formatNumber(params().num("naughty_bonus")) + "%</gray>"));
    }

    private void tickNaughty(Player owner, long now, long tick) {
        int marks = 0;
        Iterator<Naughty> it = naughty.values().iterator();
        while (it.hasNext()) {
            Naughty entry = it.next();
            LivingEntity enemy = entry.enemy;
            boolean gone = !Combat.isTargetable(ctx(), owner, enemy) || !enemy.getWorld().equals(owner.getWorld());
            if (gone || entry.until <= now) {
                ItemDisplay coal = entry.coal;
                if (coal != null && coal.isValid()) {
                    if (gone) {
                        coal.remove();
                    } else {
                        Displays.shrinkAway(ctx(), owner, coal, 4);
                    }
                }
                it.remove();
                continue;
            }
            Location over = Displays.unrotated(enemy.getLocation().add(0,
                    enemy.getHeight() + 0.45 + 0.06 * Math.sin(tick * 0.25), 0));
            boolean hidden = enemy instanceof Player victim && !owner.canSee(victim);
            ItemDisplay coal = entry.coal;
            if ((coal == null || !coal.isValid()) && !hidden && marks < MAX_NAUGHTY_MARKS && roomFor(1)) {
                coal = rememberOptional(Displays.item(ctx(), owner, over, new ItemStack(COAL_ITEM),
                        Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), 0));
                coal.setBillboard(Display.Billboard.CENTER);
                coal.setTeleportDuration(2);
                Displays.animateNextTick(ctx(), owner, coal, Displays.centered(0, 0, 0, 0.4f, 0.4f, 0.4f, 0, 0, 0), 4);
                entry.coal = coal;
            }
            if (coal != null && coal.isValid()) {
                marks++;
                coal.setViewRange(hidden ? 0f : 1.0f);
                Displays.moveTo(coal, over);
                if (!hidden && tick % 10 == 0) {
                    Vfx.dot(over.clone().add(0, 0.2, 0), Particle.SMOKE, null, false);
                }
            }
        }
    }

    // ------------------------------------------------------------------ active: the present

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location target = aimPoint(owner, Math.max(1, params().num("throw_range")));
        if (target == null) {
            return ActivationResult.fail("선물을 떨어뜨릴 곳이 없습니다");
        }
        new Present(owner, target).fly();
        return ActivationResult.success();
    }

    /**
     * Where the present lands: at the aimed enemy's feet, or on the ground where the view ray hits (or ends), snapped
     * down to the ground below; null when there is no ground in reach.
     */
    private @Nullable Location aimPoint(Player owner, double range) {
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null) {
            Location feet = Displays.unrotated(aimed.getLocation());
            Spots.snapToGround(feet.getWorld(), feet, AIM_GROUND_SNAP);
            return feet;
        }
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        Location point = hit == null ? eye.clone().add(direction.clone().multiply(range))
                : hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.3));
        point = Displays.unrotated(point);
        double before = point.getY();
        Spots.snapToGround(point.getWorld(), point, AIM_GROUND_SNAP);
        if (point.getY() >= before - 1.0E-6 && hit == null) {
            return null;
        }
        return point;
    }

    /** The thrown present: a spinning box on a lob, its landing spot marked; it opens where it lands. */
    private final class Present implements LongPredicate {

        private final Player owner;
        private final Location start;
        private final Location target;
        private final long flight;
        private final double arc;
        private final GiftBox box;
        private float spin;

        Present(Player owner, Location target) {
            this.owner = owner;
            Location eye = owner.getEyeLocation();
            this.start = Displays.unrotated(eye.add(eye.getDirection().multiply(0.6)).subtract(0, 0.3, 0));
            this.target = target.clone().add(0, THROWN_SIZE / 2, 0);
            double distance = start.distance(this.target);
            this.flight = Math.clamp(Math.round(6 + distance * 0.55), 8, 18);
            this.arc = clearArc(start, this.target, 1.0 + distance * 0.22);
            this.box = new GiftBox(start, THROWN_SIZE * 0.4f, randomBox(), flight + 14);
        }

        void fly() {
            Fx.sound(start, Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.6f);
            Fx.sound(start, Sound.ITEM_BUNDLE_DROP_CONTENTS, 0.7f, 0.9f);
            Fx.sound(start, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, pitch(15));
            Loop.repeat(ctx(), owner, 1, 1, flight, this, this::land);
        }

        @Override
        public boolean test(long tick) {
            double t = (tick + 1) / (double) flight;
            Location at = start.clone().add(target.toVector().subtract(start.toVector()).multiply(t));
            at.add(0, Math.sin(t * Math.PI) * arc, 0);
            spin += 24;
            float size = THROWN_SIZE * (float) Math.min(1.0, 0.4 + t * 1.5);
            box.place(at, size, spin, 1);
            Vfx.dot(at, Particle.DUST, new Particle.DustOptions(FESTIVE[(int) tick % FESTIVE.length], 0.9f), true);
            if (tick % 2 == 0) {
                Vfx.dot(at, Particle.END_ROD, null, false);
            }
            // Telegraph: a red and white ring closing in on the landing spot.
            double radius = Math.max(0.5, params().num("throw_radius"));
            Location ground = target.clone().subtract(0, THROWN_SIZE / 2 - 0.1, 0);
            Vfx.ring(ctx(), ground, radius * (1.0 - 0.15 * t), (int) Math.max(12, radius * 5), tick * 0.2,
                    Particle.DUST, new Particle.DustOptions(tick % 4 < 2 ? RED : WHITE, 1.0f), true);
            return true;
        }

        /** The present lands and opens: gifts for Santa's side, coal for the enemies. */
        void land() {
            Location center = target.clone();
            double radius = Math.max(0, params().num("throw_radius"));
            box.burst(center);
            int gifts = 0;
            List<LivingEntity> friends = new ArrayList<>();
            if (owner.getLocation().distance(center) <= radius + owner.getWidth()) {
                friends.add(owner);
            }
            friends.addAll(ctx().alliesNear(owner, center, radius));
            double heal = Math.max(0, params().num("throw_heal"));
            for (LivingEntity friend : friends) {
                if (heal > 0) {
                    ctx().heal(friend, heal);
                }
                giftFx(Fx.body(friend));
                gifts++;
            }
            int coals = 0;
            long blind = params().ticks("coal_blind");
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                if (!Spots.clearPath(center, Fx.body(enemy))) {
                    continue;
                }
                boolean bad = isNaughty(enemy);
                double damage = params().num("coal_damage");
                if (bad) {
                    damage *= 1 + Math.max(0, params().num("naughty_bonus")) / 100.0;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                coals++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    away = Motion.horizontalFacing(owner.getLocation());
                }
                Motion.push(enemy, away.multiply(COAL_PUSH).setY(COAL_LIFT), MAX_SPEED);
                if (blind > 0) {
                    enemy.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                            (int) Math.min(Integer.MAX_VALUE, blind), 0, false, true, true));
                }
                if (bad && !(enemy instanceof Player victim && !owner.canSee(victim))) {
                    Vfx.cloud(ctx(), Fx.body(enemy), Particle.ANGRY_VILLAGER, 2, 0.3, 0, null, false);
                }
            }
            if (coals > 0) {
                coalFx(center);
            }
            openFx(center, coals == 0);
            if (gifts + coals > 0) {
                owner.sendActionBar(Text.mm("<" + RED_TAG + ">🎁 선물 상자</" + RED_TAG + ">"
                        + (gifts > 0 ? " <" + GREEN_TAG + ">선물 " + gifts + "</" + GREEN_TAG + ">" : "")
                        + (coals > 0 ? " <gray>석탄 " + coals + "</gray>" : "")));
            }
        }
    }

    /**
     * The highest lob from {@code from} to {@code to}, {@code arc} high at most, along which nothing (a ceiling
     * indoors, an overhang) is in the way of the present; a straight throw if every lob is blocked.
     */
    private static double clearArc(Location from, Location to, double arc) {
        for (double share : ARC_SHARES) {
            if (arcIsClear(from, to, arc * share)) {
                return arc * share;
            }
        }
        return 0;
    }

    private static boolean arcIsClear(Location from, Location to, double height) {
        World world = from.getWorld();
        Vector delta = to.toVector().subtract(from.toVector());
        int samples = Math.max(6, (int) Math.ceil((delta.length() + 2 * height) / 0.4));
        for (int i = 1; i < samples; i++) {
            double t = (double) i / samples;
            Vector at = from.toVector().add(delta.clone().multiply(t)).add(new Vector(0, Math.sin(t * Math.PI) * height,
                    0));
            if (world.hasCollisionsIn(new BoundingBox(at.getX() - PRESENT_HALF, at.getY() - PRESENT_HALF,
                    at.getZ() - PRESENT_HALF, at.getX() + PRESENT_HALF, at.getY() + PRESENT_TOP,
                    at.getZ() + PRESENT_HALF))) {
                return false;
            }
        }
        return true;
    }

    /** The box bursts open: ribbons fly, confetti everywhere and a jingle. */
    private void openFx(Location center, boolean onlyGift) {
        World world = center.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vfx.dot(center, Particle.FLASH, onlyGift ? GOLD : WHITE, false);
        for (int i = 0; i < Vfx.n(ctx(), 24); i++) {
            Location from = center.clone();
            Location to = center.clone().add(random.nextDouble(-1.6, 1.6), random.nextDouble(0.4, 2.2),
                    random.nextDouble(-1.6, 1.6));
            Vfx.trail(from, to, FESTIVE[i % FESTIVE.length], 8 + random.nextInt(8), false);
        }
        world.spawnParticle(Particle.FIREWORK, center, Vfx.n(ctx(), 10), 0.2, 0.2, 0.2, 0.12);
        Vfx.ring(ctx(), center.clone().subtract(0, THROWN_SIZE / 2 - 0.1, 0),
                Math.max(0.5, params().num("throw_radius")), 24, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f),
                false);
        Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.9f, 1.4f + Fx.jitter(0.1f));
        Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, pitch(18));
        ctx().runLater(player(), 2, () -> Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, pitch(22)));
    }

    /** Coal for the naughty: black smoke, soot and coal lumps flying out of the box. */
    private void coalFx(Location center) {
        World world = center.getWorld();
        world.spawnParticle(Particle.LARGE_SMOKE, center, Vfx.n(ctx(), 14), 0.5, 0.3, 0.5, 0.06);
        world.spawnParticle(Particle.DUST, center, Vfx.n(ctx(), 18), 0.9, 0.5, 0.9, 0, SOOT);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, center, Vfx.n(ctx(), 10), 0.6, 0.4, 0.6, 0, EMBER);
        Vfx.shockwave(ctx(), player(), center.clone().subtract(0, THROWN_SIZE / 2 - 0.15, 0), 0.4,
                Math.max(0.8, params().num("throw_radius")), 5, Particle.DUST, SOOT, false);
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(center, Sound.BLOCK_STONE_BREAK, 1.0f, 0.6f);
        Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 0.6f);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Player owner = player();
        for (int i = 0; i < COAL_LUMPS && roomFor(1); i++) {
            double angle = i * TAU / COAL_LUMPS + random.nextDouble(-0.4, 0.4);
            ItemDisplay lump = rememberOptional(Displays.item(ctx(), owner, center, new ItemStack(COAL_ITEM),
                    Displays.centered(0, 0, 0, 0.35f, 0.35f, 0.35f, 0, 0, 0), 16));
            lump.setBillboard(Display.Billboard.CENTER);
            lump.setTeleportDuration(8);
            Location end = center.clone().add(Math.cos(angle) * 1.8, 0.7 + random.nextDouble(0.6),
                    Math.sin(angle) * 1.8);
            ctx().runLater(owner, 1, () -> Displays.moveTo(lump, end));
            ctx().runLater(owner, 9, () -> {
                if (lump.isValid()) {
                    Displays.shrinkAway(ctx(), owner, lump, 5);
                }
            });
        }
    }

    // ------------------------------------------------------------------ the present (display)

    private static Material randomBox() {
        return BOX_COLORS[ThreadLocalRandom.current().nextInt(BOX_COLORS.length)];
    }

    /**
     * A present built from block displays: a colored box wrapped in two gold ribbons and a gold bow on top, all
     * centered on one point and turned together about the vertical.
     */
    private final class GiftBox {

        private final List<BlockDisplay> parts = new ArrayList<>();

        GiftBox(Location at, float size, Material color, long lifetime) {
            Material[] materials = {color, RIBBON, RIBBON, BOW};
            Location spot = Displays.unrotated(at);
            for (int i = 0; i < materials.length && roomFor(1); i++) {
                BlockDisplay part = remember(Displays.block(ctx(), player(), spot, materials[i].createBlockData(),
                        partPose(i, size * 0.2f, 0), lifetime));
                part.setTeleportDuration(1);
                parts.add(part);
            }
        }

        /** Moves the present to {@code center} at {@code size}, turned {@code spin} degrees, over {@code ticks}. */
        void place(Location center, float size, float spin, int ticks) {
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                if (part.isValid()) {
                    Displays.moveTo(part, center);
                    Displays.animate(part, partPose(i, size, spin), ticks);
                }
            }
        }

        /** The ribbons spring off upwards, the box and bow shrink away. */
        void burst(Location center) {
            Player owner = player();
            optional.addAll(parts);
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                if (!part.isValid()) {
                    continue;
                }
                if (i == 1 || i == 2) {
                    part.setTeleportDuration(5);
                    Displays.moveTo(part, center.clone().add(i == 1 ? 0.6 : -0.6, 1.2, i == 1 ? -0.3 : 0.3));
                    Transformation current = part.getTransformation();
                    Vector3f scale = current.getScale();
                    Displays.animate(part, Displays.transform(0, 0, 0, scale.x(), scale.y(), scale.z(),
                            i == 1 ? 120 : -120, 60, 30), 5);
                    ctx().runLater(owner, 5, () -> {
                        if (part.isValid()) {
                            Displays.shrinkAway(ctx(), owner, part, 4);
                        }
                    });
                } else {
                    Displays.shrinkAway(ctx(), owner, part, 4);
                }
            }
            parts.clear();
        }
    }

    /** The pose of one part of a present of edge {@code size} turned {@code spin} degrees: box, ribbon, ribbon, bow. */
    static Transformation partPose(int part, float size, float spin) {
        float s = size;
        return switch (part) {
            case 0 -> Displays.transform(0, 0, 0, s, s, s, spin, 0, 0);
            case 1 -> Displays.transform(0, 0, 0, s * 1.06f, s * 1.06f, s * 0.2f, spin, 0, 0);
            case 2 -> Displays.transform(0, 0, 0, s * 0.2f, s * 1.06f, s * 1.06f, spin, 0, 0);
            default -> Displays.transform(0, s * 0.62f, 0, s * 0.3f, s * 0.24f, s * 0.3f, spin + 45, 0, 0);
        };
    }

    // ------------------------------------------------------------------ ultimate: the Christmas tree

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location base = treeSpot(owner);
        if (base == null) {
            return ActivationResult.fail("트리를 세울 땅이 없습니다");
        }
        new Tree(owner, base).raise();
        return ActivationResult.success();
    }

    /** In front of Santa if there is room and ground there, otherwise where Santa stands. */
    private @Nullable Location treeSpot(Player owner) {
        Location feet = Displays.unrotated(owner.getLocation());
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        World world = owner.getWorld();
        if (facing.lengthSquared() > 1.0E-6) {
            Location ahead = feet.clone().add(facing.clone().multiply(TREE_AHEAD));
            Location probe = ahead.clone().add(0, 1.0, 0);
            if (Spots.clearPath(feet.clone().add(0, 1.0, 0), probe) && Spots.hasGroundBelow(world, probe)) {
                Spots.snapToGround(world, probe, 3.0);
                if (probe.getY() < ahead.getY() + 1.0 - 1.0E-6 && Spots.isFree(world, probe, 0.6, 1.0)) {
                    return probe;
                }
            }
        }
        Location here = feet.clone().add(0, 0.5, 0);
        if (!Spots.hasGroundBelow(world, here)) {
            return null;
        }
        Spots.snapToGround(world, here, 3.0);
        return here;
    }

    /**
     * The tree: grows tier by tier, heals and hastens Santa's side in its reach, wraps the enemies there in string
     * lights once grown, and bursts its star into fireworks at the end.
     */
    private final class Tree implements LongPredicate {

        private final Player owner;
        private final Location base;
        private final World world;
        private final double radius;
        private final long duration;
        private final long bindAt;
        private final long healEvery;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private @Nullable BlockDisplay star;
        private final List<LivingEntity> bound = new ArrayList<>();
        private int note;
        private long nextNoteAt;
        private int boundCount;

        Tree(Player owner, Location base) {
            this.owner = owner;
            this.base = Displays.unrotated(base);
            this.world = base.getWorld();
            this.radius = Math.max(1, params().num("ult_radius"));
            this.duration = Math.max(FINALE_TICKS + 1, params().ticks("ult_duration"));
            this.bindAt = Math.min(duration - 1, Math.max(1, params().ticks("ult_bind_delay")));
            this.healEvery = Math.max(1, params().ticks("ult_heal_interval"));
        }

        void raise() {
            long lifetime = duration + 20;
            // The tree is the centrepiece: naughty marks, coal lumps and opened presents make way for it.
            makeRoom(TREE_DISPLAYS);
            if (roomFor(1)) {
                BlockDisplay trunk = remember(Displays.block(ctx(), owner, base, TRUNK.createBlockData(),
                        standing(0.42f, 0.01f, 0.42f, 0, 0), lifetime));
                parts.add(trunk);
                Displays.animateNextTick(ctx(), owner, trunk, standing(0.42f, 1.0f, 0.42f, 0, 0), TIER_GROW_TICKS);
            }
            if (roomFor(1)) {
                BlockDisplay top = remember(Displays.block(ctx(), owner, base, STAR.createBlockData(),
                        starPose(0.02f, 0), lifetime));
                top.setViewRange(2.5f);
                Displays.outline(top, GOLD);
                star = top;
                ctx().runLater(owner, Math.max(2, bindAt - 3), () -> Displays.animate(top, starPose(STAR_SIZE, 45),
                        3));
            }
            for (int i = 0; i < TIERS.length; i++) {
                float[] tier = TIERS[i];
                for (int turn = 0; turn < 2 && roomFor(1); turn++) {
                    float yaw = turn * 45f + i * 22.5f;
                    BlockDisplay leaves = remember(Displays.block(ctx(), owner, base, LEAVES.createBlockData(),
                            standing(0.05f, 0.05f, 0.05f, tier[2], yaw), lifetime));
                    parts.add(leaves);
                    int delay = 1 + i * 3;
                    ctx().runLater(owner, delay, () -> Displays.animate(leaves, standing(tier[0], tier[1], tier[0],
                            tier[2], yaw), TIER_GROW_TICKS));
                }
            }
            hangBaubles(lifetime);

            Location center = base.clone().add(0, 1.2, 0);
            Vfx.shockwave(ctx(), owner, base.clone().add(0, 0.1, 0), 0.5, radius, 8, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.3f), true);
            Vfx.cloud(ctx(), center, Particle.SNOWFLAKE, 30, 1.2, 0.08, null, true);
            Fx.sound(base, Sound.BLOCK_GRASS_BREAK, 1.2f, 0.6f);
            Fx.sound(base, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.6f);
            Fx.sound(base, Sound.BLOCK_BELL_USE, 1.0f, 1.4f);
            Fx.sound(base, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.5f);
            owner.sendActionBar(Text.mm("<" + RED_TAG + "><b>🎄 크리스마스의 기적</b></" + RED_TAG + ">"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::finale);
        }

        /** Baubles in festive colors hung on the tiers. */
        private void hangBaubles(long lifetime) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < BAUBLES.length && roomFor(1); i++) {
                float[] tier = TIERS[i % (TIERS.length - 1)];
                double angle = i * 2.4 + random.nextDouble(-0.3, 0.3);
                double reach = tier[0] * 0.5 + 0.05;
                float x = (float) (Math.cos(angle) * reach);
                float z = (float) (Math.sin(angle) * reach);
                float y = tier[2] + 0.15f;
                BlockDisplay bauble = remember(Displays.block(ctx(), owner, base, BAUBLES[i].createBlockData(),
                        Displays.transform(x, y, z, 0.01f, 0.01f, 0.01f, 30, 30, 0), lifetime));
                parts.add(bauble);
                int delay = 4 + i * 2;
                ctx().runLater(owner, delay, () -> Displays.animate(bauble, Displays.transform(x, y, z, 0.2f, 0.2f,
                        0.2f, 30, 30, 0), 3));
            }
        }

        @Override
        public boolean test(long tick) {
            if (!owner.getWorld().equals(world)) {
                return false;
            }
            lights(tick);
            snow(tick);
            jingle(tick);
            if (tick % 10 == 0) {
                Vfx.ring(ctx(), base.clone().add(0, 0.12, 0), radius, (int) Math.min(48, Math.max(16, radius * 4)),
                        tick * 0.02, Particle.DUST, new Particle.DustOptions(tick % 20 == 0 ? RED : GREEN, 1.2f), true);
            }
            if (tick % healEvery == 0) {
                cheer();
            }
            if (tick == bindAt) {
                bind();
            }
            wrap(tick);
            BlockDisplay top = star;
            if (top != null && top.isValid() && tick > bindAt && tick % 2 == 0) {
                Displays.animate(top, starPose(STAR_SIZE * (1 + 0.08f * (float) Math.sin(tick * 0.4)), 45 + tick * 6f),
                        2);
            }
            return true;
        }

        /** Strings of colored lights winding up the tree, twinkling. */
        private void lights(long tick) {
            if (tick % 2 != 0) {
                return;
            }
            int points = Vfx.n(ctx(), 14);
            for (int i = 0; i < points; i++) {
                double t = (double) i / points;
                double height = 0.9 + t * 3.3;
                double width = 1.6 * (1 - t) + 0.35;
                double angle = t * 3.5 * TAU + tick * 0.08;
                Location at = base.clone().add(Math.cos(angle) * width, height, Math.sin(angle) * width);
                Color color = FESTIVE[(i + (int) (tick / 4)) % FESTIVE.length];
                Vfx.dot(at, Particle.DUST, new Particle.DustOptions(color, 0.9f), true);
            }
        }

        private void snow(long tick) {
            if (tick % 3 != 0) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Location at = Vfx.scatter(base, radius).add(0, 4 + random.nextDouble(1.5), 0);
                world.spawnParticle(Particle.SNOWFLAKE, at, 0, 0, -1, 0, 0.05, null, true);
            }
        }

        /** Sleigh bells: Jingle Bells, one note at a time. */
        private void jingle(long tick) {
            if (tick < nextNoteAt) {
                return;
            }
            int index = note % JINGLE.length;
            Fx.sound(base, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, pitch(JINGLE[index]));
            Fx.sound(base, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.45f, pitch(JINGLE[index]));
            if (index % 4 == 0) {
                Fx.sound(base, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, pitch(JINGLE[index] - 4));
            }
            nextNoteAt = tick + JINGLE_TICKS[index];
            note++;
        }

        /** Santa and the allies in reach are healed and hastened, a golden thread from the star to each. */
        private void cheer() {
            double heal = Math.max(0, params().num("ult_heal"));
            List<LivingEntity> friends = new ArrayList<>(ctx().alliesNear(owner, base, radius + TREE_ABOVE));
            friends.add(owner);
            friends.removeIf(friend -> !aroundTree(friend));
            Location top = base.clone().add(0, STAR_HEIGHT, 0);
            for (LivingEntity friend : friends) {
                if (heal > 0) {
                    ctx().heal(friend, heal);
                }
                friend.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                        (int) Math.min(Integer.MAX_VALUE, healEvery + 10), 0, false, true, true));
                Location body = Fx.body(friend);
                Vfx.trail(top, body, GOLD, 10, false);
                world.spawnParticle(Particle.HEART, body.clone().add(0, 0.7, 0), 1, 0.2, 0.1, 0.2, 0);
            }
        }

        /** The grown tree lashes out with string lights: every enemy in reach is wrapped up and held in place. */
        private void bind() {
            long hold = params().ticks("ult_bind");
            double damage = params().num("ult_bind_damage");
            Location trunk = base.clone().add(0, 1.5, 0);
            for (LivingEntity enemy : reachedEnemies()) {
                Combat.hit(ctx(), owner, enemy, damage);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                if (hold > 0) {
                    enemy.setVelocity(new Vector());
                    ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, hold);
                    ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, hold);
                }
                bound.add(enemy);
                boundCount++;
                if (enemy instanceof Player victim && !owner.canSee(victim)) {
                    continue;
                }
                Location body = Fx.body(enemy);
                for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                    Vfx.trail(trunk, body.clone().add(0, -0.4 + 0.3 * i, 0), FESTIVE[i % FESTIVE.length], 6, true);
                }
                Vfx.line(ctx(), trunk, body, 2.5, Particle.DUST, GOLD_DUST, true);
                Fx.sound(body, Sound.BLOCK_CHAIN_PLACE, 1.0f, 1.4f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.6f + Fx.jitter(0.1f));
            }
            if (boundCount > 0) {
                owner.sendActionBar(Text.mm("<" + RED_TAG + ">🎄 전구 줄</" + RED_TAG + "> <white>" + boundCount
                        + "명 결박!</white>"));
            }
        }

        /** The string lights spiral around each bound enemy for as long as the hold lasts. */
        private void wrap(long tick) {
            long hold = params().ticks("ult_bind");
            if (bound.isEmpty() || tick <= bindAt || tick > bindAt + hold || tick % 2 != 0) {
                return;
            }
            for (LivingEntity enemy : bound) {
                if (!Combat.isTargetable(ctx(), owner, enemy) || enemy instanceof Player victim && !owner.canSee(victim)) {
                    continue;
                }
                Location feet = enemy.getLocation();
                double height = enemy.getHeight();
                int points = Vfx.n(ctx(), 8);
                for (int i = 0; i < points; i++) {
                    double t = (double) i / points;
                    double angle = t * 2.5 * TAU + tick * 0.35;
                    double r = enemy.getWidth() * 0.5 + 0.2;
                    Location at = feet.clone().add(Math.cos(angle) * r, 0.15 + t * height, Math.sin(angle) * r);
                    Color color = FESTIVE[(i + (int) (tick / 2)) % FESTIVE.length];
                    Vfx.dot(at, Particle.DUST, new Particle.DustOptions(color, 0.8f), false);
                }
            }
        }

        /** The star bursts into fireworks over the tree; the enemies in reach are hurt, and the tree fades away. */
        void finale() {
            Location top = base.clone().add(0, STAR_HEIGHT, 0);
            double damage = params().num("ult_finale_damage");
            int hits = 0;
            for (LivingEntity enemy : reachedEnemies()) {
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (enemy instanceof Player victim && !owner.canSee(victim)) {
                    continue;
                }
                Vfx.trail(top, Fx.body(enemy), GOLD, 6, true);
                Vfx.dot(Fx.body(enemy), Particle.FLASH, GOLD, false);
            }
            Vfx.dot(top, Particle.FLASH, GOLD, true);
            world.spawnParticle(Particle.FIREWORK, top, Vfx.n(ctx(), 50), 0.3, 0.3, 0.3, 0.3, null, true);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int burst = 0; burst < 3; burst++) {
                Color color = FESTIVE[random.nextInt(FESTIVE.length)];
                Location sky = top.clone().add(random.nextDouble(-2, 2), 1.5 + random.nextDouble(1.5),
                        random.nextDouble(-2, 2));
                long delay = 2L + burst * 4L;
                ctx().runLater(owner, delay, () -> {
                    Vfx.sphere(ctx(), sky, 1.4, 36, Particle.DUST, new Particle.DustOptions(color, 1.2f), true);
                    world.spawnParticle(Particle.FIREWORK, sky, Vfx.n(ctx(), 12), 0.2, 0.2, 0.2, 0.15, null, true);
                    Fx.sound(sky, Sound.ENTITY_FIREWORK_ROCKET_BLAST_FAR, 1.4f, 0.9f + Fx.jitter(0.15f));
                    Fx.sound(sky, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE_FAR, 1.0f, 1.0f + Fx.jitter(0.15f));
                });
            }
            Vfx.shockwave(ctx(), owner, base.clone().add(0, 0.12, 0), 0.5, radius, 7, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.3f), true);
            Fx.sound(top, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.5f, 0.9f);
            Fx.sound(top, Sound.BLOCK_BELL_USE, 1.2f, 1.2f);
            Fx.sound(top, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, (int) FINALE_TICKS);
                }
            }
            BlockDisplay shining = star;
            if (shining != null && shining.isValid()) {
                Displays.animate(shining, starPose(STAR_SIZE * 2.2f, 200), 3);
                ctx().runLater(owner, 3, () -> {
                    if (shining.isValid()) {
                        Displays.shrinkAway(ctx(), owner, shining, 3);
                    }
                });
            }
            if (hits > 0 && owner.isOnline()) {
                String title = shining != null ? "★ 별이 터졌습니다" : "🎄 크리스마스의 기적";
                owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>" + title + "</b></" + GOLD_TAG + "> <white>" + hits
                        + "명 적중!</white>"));
            }
        }

        /** True if {@code entity} is around the tree: within its radius and about at the height of its foot. */
        private boolean aroundTree(LivingEntity entity) {
            Location feet = entity.getLocation();
            double dy = feet.getY() - base.getY();
            return feet.getWorld().equals(world) && dy >= -TREE_BELOW && dy <= TREE_ABOVE
                    && Math.hypot(feet.getX() - base.getX(), feet.getZ() - base.getZ()) <= radius;
        }

        /** The enemies the tree's lights and star reach: around the tree, and able to see it. */
        private List<LivingEntity> reachedEnemies() {
            List<LivingEntity> reached = new ArrayList<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, base, radius + TREE_ABOVE)) {
                if (aroundTree(enemy) && seesTree(enemy)) {
                    reached.add(enemy);
                }
            }
            return reached;
        }

        private boolean seesTree(LivingEntity enemy) {
            Location body = Fx.body(enemy);
            for (double height : TREE_EYES) {
                if (Spots.clearPath(base.clone().add(0, height, 0), body)) {
                    return true;
                }
            }
            return false;
        }

        /** A box standing on the tree's base (its bottom {@code bottom} blocks up), turned {@code yaw} degrees. */
        private Transformation standing(float width, float height, float depth, float bottom, float yaw) {
            return Displays.transform(0, bottom + height / 2, 0, width, height, depth, yaw, 0, 0);
        }

        private Transformation starPose(float size, float spin) {
            return Displays.transform(0, STAR_HEIGHT, 0, size, size, size * 0.35f, spin, 0, 45);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Note-block pitch for a note number (0 = F#3 ... 24 = F#5). */
    private static float pitch(int note) {
        return (float) Math.pow(2.0, (Math.clamp(note, 0, 24) - 12) / 12.0);
    }

    private boolean roomFor(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T remember(T display) {
        displays.add(display);
        return display;
    }

    /** {@link #remember}s a display that makes way for the tree when the budget is tight. */
    private <T extends Display> T rememberOptional(T display) {
        optional.add(display);
        return remember(display);
    }

    /** Removes optional displays (oldest first) until {@code count} more fit in the budget. */
    private void makeRoom(int count) {
        displays.removeIf(display -> !display.isValid());
        optional.removeIf(display -> !display.isValid());
        Iterator<Display> it = optional.iterator();
        while (displays.size() + count > MAX_DISPLAYS && it.hasNext()) {
            Display display = it.next();
            it.remove();
            displays.remove(display);
            display.remove();
        }
    }
}
