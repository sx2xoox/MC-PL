package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 마술사 — a stage magician. Enemy attacks sometimes hit nothing but a shower of cards while the magician is a step
 * to the side, the active throws a quick volley of spinning cards that each leave a suit mark (three marks complete
 * the trick and explode), and the ultimate drops a velvet curtain on an enemy, makes it vanish and lets it fall out
 * of thin air somewhere else, while the magician disappears under a giant top hat.
 *
 * <p>Purple and gold. Cards are paper item displays lying flat and spinning; marks are suit symbols (♠ ♥ ♦) floating
 * over the marked head; the curtain is four purple velvet panels under a gold pelmet; the hat is three black and red
 * block displays.
 */
public final class MagicianAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("magician", "마술사", Material.PAPER)
            .color(TextColor.color(0xA66CFF))
            .difficulty(2)
            .passive("손재주",
                    "적의 공격을 받으면 {dodge_chance}% 확률로 카드 뭉치만 남기고 옆으로 약 {dodge_distance}블록 "
                            + "순간 회피해 피해를 받지 않습니다. 회피한 뒤 {dodge_cooldown}초 동안은 다시 회피하지 않습니다.")
            .active("카드 던지기",
                    "바라보는 방향으로 카드 {cards}장을 연달아 던집니다({card_range}블록). 카드마다 {card_damage} 피해를 "
                            + "주고 문양 표식을 남기며({mark_duration}초 유지), 표식이 {trick_marks}개 모이면 트릭이 완성되어 "
                            + "폭발하며 반경 {trick_radius}블록의 적에게 {trick_damage} 피해를 줍니다.",
                    8)
            .ultimate("사라지는 마술",
                    "조준한 적({ult_range}블록 이내)에게 커튼을 내려 {ult_hold}초간 묶어 둡니다. 커튼이 걷히면 적은 "
                            + "사라져 {ult_min_distance}~{ult_max_distance}블록 떨어진 무작위 안전한 곳의 허공에서 "
                            + "떨어지며 {ult_damage} 피해를 입고 {ult_blind}초간 앞이 보이지 않습니다. 나는 커다란 모자 "
                            + "속으로 사라져 {ult_stealth}초간 적의 눈에 보이지 않습니다.")
            // The vanishing needs onRevoke to show the owner again, and the attack hooks to break it.
            .ultimateNotStandalone()
            .param("dodge_chance", 20)
            .param("dodge_distance", 1.5)
            .param("dodge_cooldown", 5)
            .param("cards", 3)
            .param("card_range", 22)
            .param("card_damage", 1.5)
            .param("mark_duration", 6)
            .param("trick_marks", 3)
            .param("trick_damage", 3.5)
            .param("trick_radius", 2.5)
            .param("ult_range", 18)
            .param("ult_hold", 1)
            .param("ult_min_distance", 10)
            .param("ult_max_distance", 18)
            .param("ult_damage", 6)
            .param("ult_blind", 1.5)
            .param("ult_stealth", 1.5)
            .build();

    private static final String COLOR_TAG = "#A66CFF";
    private static final String GOLD_TAG = "#FFC83D";
    /** Pale gold core, stage purple body, deep violet edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF3C4, 0xA66CFF, 0x2A0F45);
    private static final Color GOLD = Color.fromRGB(0xFFC83D);
    private static final Particle.DustTransition GLOW = PALETTE.glow(0.9f);
    private static final Particle.DustTransition FADE = PALETTE.fade(0.9f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.8f);
    private static final Particle.DustTransition GOLD_GLOW = new Particle.DustTransition(Color.fromRGB(0xFFF3C4),
            GOLD, 1.0f);
    private static final double TAU = Math.PI * 2;
    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;
    /** The suits of the marks, in the order they are collected. */
    private static final String[] SUITS = {"<#B784FF>♠</#B784FF>", "<#FF4D6D>♥</#FF4D6D>", "<#FFC83D>♦</#FFC83D>",
            "<white>♣</white>"};

    // passive: sleight of hand
    private static final int DODGE_CARDS = 4;
    private static final int READY_SPARKLE_PERIOD = 20;

    // active: card throw
    private static final Material CARD = Material.PAPER;
    private static final long CARD_GAP = 3;
    private static final double CARD_SPEED = 1.6;
    private static final double CARD_HIT_RADIUS = 0.35;
    private static final double CARD_SWEEP_STEP = 0.4;
    private static final float CARD_SCALE = 0.5f;
    private static final float CARD_SPIN = 55f;
    private static final double MERGE_DISTANCE = 2.5;
    private static final int STUCK_TICKS = 12;
    private static final long TRICK_DELAY = 3;
    private static final int TRICK_CARDS = 5;
    private static final double TRICK_PUSH = 0.5;
    private static final double TRICK_LIFT = 0.3;

    // ultimate: vanishing act
    private static final Material VELVET = Material.PURPLE_WOOL;
    private static final Material TRIM = Material.GOLD_BLOCK;
    private static final Material HAT = Material.BLACK_CONCRETE;
    private static final Material HAT_BAND = Material.RED_CONCRETE;
    private static final float STAGE_HALF = 0.85f;
    private static final float CURTAIN_HEIGHT = 2.5f;
    private static final float CURTAIN_THICKNESS = 0.05f;
    private static final int CURTAIN_DROP_TICKS = 6;
    private static final double DROP_HEIGHT = 2.5;
    private static final int SEARCH_ATTEMPTS = 48;
    private static final double GROUND_SEARCH = 14;
    private static final int HAT_COVER_TICKS = 3;
    private static final int HAT_LIFT_DELAY = 5;
    private static final int DOVES = 2;

    /** Card marks on enemies, by entity id. */
    private final Map<UUID, Mark> marks = new HashMap<>();
    private final Stealth stealth = new Stealth();
    private long dodgeReady = Long.MIN_VALUE / 2;
    private boolean readyShown = true;

    /** The suit marks on one enemy and the floating symbols over its head. */
    private static final class Mark {
        final LivingEntity target;
        int count;
        long expires;
        @Nullable TextDisplay label;

        Mark(LivingEntity target) {
            this.target = target;
        }
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        stealth.end(ctx(), player());
        marks.clear();
    }

    @Override
    public void onTick(long tick) {
        long now = ctx().currentTick();
        if (!readyShown && now >= dodgeReady) {
            readyShown = true;
            Player owner = player();
            owner.playSound(owner, Sound.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.7f, 1.7f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">손재주 <" + GOLD_TAG + ">준비</" + GOLD_TAG + "></"
                    + COLOR_TAG + ">"));
            Vfx.cloud(ctx(), hand(owner), Particle.DUST_COLOR_TRANSITION, 4, 0.12, 0, GOLD_GLOW, false);
        } else if (readyShown && tick % READY_SPARKLE_PERIOD == 0) {
            // While a dodge is ready, gold motes flicker at the fingertips.
            Location at = hand(player());
            Vfx.dot(at, Particle.DUST, GOLD_DUST, false);
            Vfx.dot(at.clone().add(0, 0.12, 0), Particle.ENCHANT, null, false);
        }
        if (stealth.isActive() && tick % 10 == 0) {
            stealth.hideFromEnemies(ctx(), player());
        }
        if (!marks.isEmpty()) {
            updateMarks(now);
        }
    }

    // ------------------------------------------------------------------ passive: sleight of hand

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        long now = ctx().currentTick();
        if (now < dodgeReady) {
            return;
        }
        Player owner = player();
        Entity causing = event.getDamageSource().getCausingEntity();
        Player attacker = ctx().resolveAttacker(causing != null ? causing : byEntity.getDamager());
        if (attacker == null || attacker.equals(owner) || !ctx().isEnemy(owner, attacker)) {
            return;
        }
        double chance = Math.clamp(params().num("dodge_chance") / 100.0, 0, 1);
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        event.setCancelled(true);
        dodgeReady = now + params().ticks("dodge_cooldown");
        readyShown = false;
        Location from = owner.getLocation();
        Location threat = attacker.getWorld().equals(owner.getWorld()) ? attacker.getLocation() : from;
        dodgeFx(from);
        // Step aside on the next tick, outside the damage event of whoever attacked.
        ctx().runLater(owner, 1, () -> sidestep(threat));
    }

    /** The magician reappears a step to the side of the attack (or behind it, or stays put if there is no room). */
    private void sidestep(Location threat) {
        Player owner = player();
        if (!owner.isOnline() || !ctx().isAlive(owner) || owner.isInsideVehicle()) {
            return;
        }
        Location feet = owner.getLocation();
        double distance = Math.max(0, params().num("dodge_distance"));
        Vector away = Motion.horizontal(feet.toVector().subtract(threat.toVector()));
        if (away.lengthSquared() < 1.0E-6) {
            away = Motion.horizontalFacing(feet).multiply(-1);
        }
        Vector side = new Vector(-away.getZ(), 0, away.getX());
        if (ThreadLocalRandom.current().nextBoolean()) {
            side.multiply(-1);
        }
        Body body = Body.standing(owner);
        Location chest = feet.clone().add(0, body.height() * 0.5, 0);
        Vector[] options = {side, side.clone().multiply(-1), away, side.clone().add(away).normalize(),
                side.clone().multiply(-1).add(away).normalize()};
        for (Vector option : options) {
            Location spot = feet.clone().add(option.clone().multiply(distance));
            Spots.snapToGround(feet.getWorld(), spot, 1.5);
            if (distance <= 0 || !Spots.isSafe(ctx(), spot, body)
                    || !Spots.clearPath(chest, spot.clone().add(0, body.height() * 0.5, 0))) {
                continue;
            }
            spot.setYaw(feet.getYaw());
            spot.setPitch(feet.getPitch());
            owner.teleport(spot);
            owner.setFallDistance(0);
            Location arrival = Fx.body(owner);
            Vfx.trail(Fx.body(owner).subtract(option.clone().multiply(distance)), arrival, PALETTE.body(), 4, false);
            Vfx.cloud(ctx(), arrival, Particle.ENCHANT, 10, 0.3, 0.6, null, false);
            Vfx.dot(arrival, Particle.END_ROD, null, false);
            return;
        }
    }

    /** Where the hit should have landed: a puff of purple smoke and a handful of cards scattering. */
    private void dodgeFx(Location from) {
        Player owner = player();
        Location chest = from.clone().add(0, owner.getHeight() * 0.55, 0);
        Vfx.cloud(ctx(), chest, Particle.DUST_COLOR_TRANSITION, 14, 0.3, 0, FADE, false);
        Vfx.cloud(ctx(), chest, Particle.WITCH, 8, 0.3, 0.05, null, false);
        Vfx.cloud(ctx(), chest, Particle.POOF, 4, 0.2, 0.02, null, false);
        scatterCards(chest, DODGE_CARDS, 0.9, 16);
        popText(chest.clone().add(0, 0.9, 0), "<b><gradient:#FFF3C4:#A66CFF>회피!</gradient></b>", 0.9f, 18);
        Fx.sound(chest, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.4f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, 1.3f + Fx.jitter(0.05f));
        Fx.sound(chest, Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 0.8f, 1.2f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">손재주 — <b>회피!</b></" + COLOR_TAG + ">"));
    }

    /** Cards bursting out of {@code at}, spinning outwards and fluttering down before they fade. */
    private void scatterCards(Location at, int count, double reach, long life) {
        Player owner = player();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double angle = i * TAU / count + random.nextDouble(0.5);
            Vector out = new Vector(Math.cos(angle), random.nextDouble(0.2, 0.8), Math.sin(angle)).multiply(reach);
            Location start = at.clone();
            start.setYaw(0);
            start.setPitch(0);
            float yaw = random.nextFloat() * 360f;
            ItemDisplay card = Displays.item(ctx(), owner, start, ItemStack.of(CARD),
                    Displays.centered(0, 0, 0, 0.1f, 0.1f, 0.1f, yaw, 90, 0), life);
            card.setTeleportDuration(6);
            Displays.animateNextTick(ctx(), owner, card, Displays.centered(0, 0, 0, CARD_SCALE * 0.8f,
                    CARD_SCALE * 0.8f, CARD_SCALE * 0.8f, yaw + 160, 70, 25), 6);
            ctx().runLater(owner, 1, () -> {
                if (card.isValid()) {
                    Displays.moveTo(card, start.clone().add(out));
                }
            });
            ctx().runLater(owner, 7, () -> {
                if (card.isValid()) {
                    card.setTeleportDuration(Math.max(1, (int) life - 10));
                    Displays.moveTo(card, start.clone().add(out).add(out.getX() * 0.3, -0.9,
                            out.getZ() * 0.3));
                    Displays.animate(card, Displays.centered(0, 0, 0, CARD_SCALE * 0.8f, CARD_SCALE * 0.8f,
                            CARD_SCALE * 0.8f, yaw + 320, 110, -20), (int) Math.max(1, life - 10));
                }
            });
            ctx().runLater(owner, Math.max(8, life - 4), () -> {
                if (card.isValid()) {
                    Displays.shrinkAway(ctx(), owner, card, 3);
                }
            });
        }
    }

    // ------------------------------------------------------------------ active: card throw

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        int count = Math.max(1, params().integer("cards"));
        double range = Math.max(1, params().num("card_range"));
        long flight = (long) Math.ceil(range / CARD_SPEED);
        List<Card> cards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cards.add(new Card(owner, i * CARD_GAP, range));
        }
        Loop.repeat(ctx(), owner, 0, 1, (count - 1) * CARD_GAP + flight + 2, tick -> {
            boolean any = false;
            for (Card card : cards) {
                if (card.step(tick)) {
                    any = true;
                }
            }
            return any;
        }, () -> {
            for (Card card : cards) {
                card.fade();
            }
        });
        Fx.sound(owner.getEyeLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.8f);
        return ActivationResult.success();
    }

    /**
     * One thrown card. It is thrown on its tick along the owner's aim at that moment (so a volley can be swept
     * across a moving target) and really flies along the eye line; the drawn card leaves from the hand and merges into
     * that line. It sticks where it hits an enemy or a wall.
     */
    private final class Card {

        private final Player owner;
        private final long throwTick;
        private final double range;
        private @Nullable ItemDisplay display;
        private Location position;
        private Vector direction = new Vector(0, 0, 1);
        private Vector handOffset = new Vector();
        private double travelled;
        private float spin;
        private boolean thrown;
        private boolean done;

        Card(Player owner, long throwTick, double range) {
            this.owner = owner;
            this.throwTick = throwTick;
            this.range = range;
            this.position = owner.getEyeLocation();
        }

        boolean step(long tick) {
            if (done) {
                return false;
            }
            if (tick < throwTick) {
                return true;
            }
            if (!thrown) {
                throwCard();
                return true;
            }
            return fly(tick);
        }

        private void throwCard() {
            thrown = true;
            Location eye = owner.getEyeLocation();
            direction = eye.getDirection();
            position = eye.clone();
            Location from = hand(owner);
            handOffset = from.toVector().subtract(eye.toVector());
            spin = ThreadLocalRandom.current().nextFloat() * 360f;
            long life = (long) Math.ceil(range / CARD_SPEED) + STUCK_TICKS + 6;
            ItemDisplay card = Displays.item(ctx(), owner, flat(from), ItemStack.of(CARD),
                    cardPose(CARD_SCALE * 0.6f, spin), life);
            card.setTeleportDuration(1);
            display = card;
            Vfx.dot(from, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
            Vfx.cloud(ctx(), from, Particle.ENCHANT, 4, 0.1, 0.3, null, false);
            Fx.sound(from, Sound.ENTITY_ALLAY_ITEM_THROWN, 0.8f, 1.5f + Fx.jitter(0.1f));
            Fx.sound(from, Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 2.0f);
        }

        private boolean fly(long tick) {
            double step = Math.min(CARD_SPEED, range - travelled);
            if (step <= 1.0E-3) {
                fade();
                return false;
            }
            World world = position.getWorld();
            Location before = drawn();
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double reach = wall == null ? step : wall.getHitPosition().distance(position.toVector());
            for (double d = 0; ; d += CARD_SWEEP_STEP) {
                double along = Math.min(d, reach);
                Location point = position.clone().add(direction.clone().multiply(along));
                List<LivingEntity> found = ctx().enemiesNear(owner, point, CARD_HIT_RADIUS);
                if (!found.isEmpty()) {
                    position = point;
                    travelled += along;
                    move(before, tick);
                    strike(found.getFirst());
                    return false;
                }
                if (along >= reach) {
                    break;
                }
            }
            if (wall != null) {
                position = wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.05));
                travelled += reach;
                move(before, tick);
                stick(wall.getHitBlock());
                return false;
            }
            position = position.clone().add(direction.clone().multiply(step));
            travelled += step;
            move(before, tick);
            return true;
        }

        private Location drawn() {
            double merge = Math.max(0, 1 - travelled / MERGE_DISTANCE);
            return position.clone().add(handOffset.clone().multiply(merge));
        }

        /** Moves the card display and draws its gold-and-purple streak. */
        private void move(Location before, long tick) {
            Location now = drawn();
            ItemDisplay card = display;
            if (card != null && card.isValid()) {
                spin += CARD_SPIN;
                Displays.moveTo(card, flat(now));
                Displays.animate(card, cardPose(CARD_SCALE, spin), 1);
            }
            if (before.distanceSquared(now) > 1.0E-4) {
                Vfx.line(ctx(), before, now, 2, Particle.DUST_COLOR_TRANSITION, tick % 2 == 0 ? GLOW : GOLD_GLOW,
                        false);
            }
            if (tick % 2 == 0) {
                Vfx.dot(now, Particle.WAX_OFF, null, false);
            }
        }

        /** The card hits an enemy: damage, a suit mark, a little flash, and the card sticks for a moment. */
        private void strike(LivingEntity enemy) {
            done = true;
            Combat.hit(ctx(), owner, enemy, Math.max(0, params().num("card_damage")));
            Location at = position.clone();
            Vfx.cloud(ctx(), at, Particle.ENCHANTED_HIT, 6, 0.15, 0.15, null, false);
            Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 6, 0.15, 0, GOLD_GLOW, false);
            Fx.sound(at, Sound.ENTITY_ARROW_HIT_PLAYER, 0.6f, 1.8f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, 1.4f + Fx.jitter(0.1f));
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                addMark(enemy);
            }
            settle(6);
        }

        /** The card sticks into the wall it hit. */
        private void stick(@Nullable Block block) {
            done = true;
            Location at = position.clone();
            if (block != null) {
                at.getWorld().spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 4), 0.05, 0.05, 0.05, 0,
                        block.getBlockData());
            }
            Fx.sound(at, Sound.ENTITY_ARROW_HIT, 0.6f, 1.6f + Fx.jitter(0.1f));
            settle(STUCK_TICKS);
        }

        private void settle(int ticks) {
            ItemDisplay card = display;
            if (card == null || !card.isValid()) {
                return;
            }
            ctx().runLater(owner, ticks, () -> {
                if (card.isValid()) {
                    Displays.shrinkAway(ctx(), owner, card, 3);
                }
            });
        }

        /** The card ran out of range (or the volley ended): it flutters away. */
        void fade() {
            if (done) {
                return;
            }
            done = true;
            ItemDisplay card = display;
            if (card != null && card.isValid()) {
                Vfx.cloud(ctx(), card.getLocation(), Particle.DUST_COLOR_TRANSITION, 3, 0.1, 0, FADE, false);
                Displays.shrinkAway(ctx(), owner, card, 3);
            }
        }
    }

    /** Adds a suit mark to {@code enemy}; the last one completes the trick. */
    private void addMark(LivingEntity enemy) {
        long now = ctx().currentTick();
        Mark mark = marks.get(enemy.getUniqueId());
        if (mark == null || mark.expires < now) {
            if (mark != null) {
                removeLabel(mark);
            }
            mark = new Mark(enemy);
            marks.put(enemy.getUniqueId(), mark);
        }
        mark.count++;
        mark.expires = now + params().ticks("mark_duration");
        int needed = Math.max(1, params().integer("trick_marks"));
        showLabel(mark, mark.count >= needed);
        Location head = enemy.getLocation().add(0, enemy.getHeight() + 0.5, 0);
        Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 0.9f + 0.25f * mark.count);
        if (mark.count >= needed) {
            Mark done = mark;
            marks.remove(enemy.getUniqueId());
            Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.6f);
            ctx().runLater(player(), TRICK_DELAY, () -> trick(done));
        }
    }

    /** The marks over the head: the collected suits, swelling once the trick is complete. */
    private void showLabel(Mark mark, boolean complete) {
        LivingEntity target = mark.target;
        if (!isVisible(target)) {
            removeLabel(mark);
            return;
        }
        StringBuilder text = new StringBuilder("<b>");
        for (int i = 0; i < mark.count; i++) {
            text.append(i > 0 ? " " : "").append(SUITS[i % SUITS.length]);
        }
        text.append("</b>");
        Location above = labelSpot(target);
        TextDisplay label = mark.label;
        if (label == null || !label.isValid()) {
            label = Displays.text(ctx(), player(), above, Text.mm(text.toString()), 0.4f, 0);
            label.setTeleportDuration(1);
            mark.label = label;
        } else {
            label.text(Text.mm(text.toString()));
        }
        float scale = complete ? 1.4f : 0.9f;
        Displays.animateNextTick(ctx(), player(), label, Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, 0), 3);
    }

    /** Expires marks, keeps the symbols over the heads and hides them over enemies the owner cannot see. */
    private void updateMarks(long now) {
        for (Iterator<Mark> it = marks.values().iterator(); it.hasNext(); ) {
            Mark mark = it.next();
            LivingEntity target = mark.target;
            if (mark.expires < now || !Combat.isTargetable(ctx(), player(), target)) {
                removeLabel(mark);
                it.remove();
                continue;
            }
            if (!isVisible(target)) {
                removeLabel(mark);
                continue;
            }
            TextDisplay label = mark.label;
            if (label == null || !label.isValid()) {
                showLabel(mark, false);
            } else {
                Displays.moveTo(label, labelSpot(target));
            }
            if ((now & 7) == 0) {
                Vfx.dot(labelSpot(target).add(0, -0.2, 0), Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
            }
        }
    }

    private void removeLabel(Mark mark) {
        TextDisplay label = mark.label;
        mark.label = null;
        if (label != null && label.isValid()) {
            Displays.shrinkAway(ctx(), player(), label, 3);
        }
    }

    private static Location labelSpot(LivingEntity target) {
        Location at = target.getLocation().add(0, target.getHeight() + 0.45, 0);
        at.setYaw(0);
        at.setPitch(0);
        return at;
    }

    /**
     * 트릭 완성: the suits spin into the marked enemy, which bursts in a flash of gold and a fan of cards, hurting it
     * and every enemy around it.
     */
    private void trick(Mark mark) {
        Player owner = player();
        LivingEntity target = mark.target;
        TextDisplay label = mark.label;
        mark.label = null;
        Location center = target.isValid() ? Fx.body(target) : label != null ? label.getLocation() : null;
        if (label != null && label.isValid()) {
            Displays.shrinkAway(ctx(), owner, label, 3);
        }
        if (center == null || !center.getWorld().equals(owner.getWorld())) {
            return;
        }
        double radius = Math.max(0, params().num("trick_radius"));
        double damage = Math.max(0, params().num("trick_damage"));
        int hits = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            Combat.hit(ctx(), owner, enemy, damage);
            hits++;
            if (!Combat.isTargetable(ctx(), owner, enemy) || enemy.equals(target)) {
                continue;
            }
            Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
            if (away.lengthSquared() > 1.0E-6) {
                Motion.push(enemy, away.multiply(TRICK_PUSH).setY(TRICK_LIFT), MAX_SPEED);
            }
        }
        Vfx.dot(center, Particle.FLASH, GOLD, false);
        Vfx.sphere(ctx(), center, 0.9, 24, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
        Vfx.sphere(ctx(), center, Math.max(1.0, radius * 0.8), 30, Particle.DUST_COLOR_TRANSITION, GLOW, false);
        Vfx.cloud(ctx(), center, Particle.ENCHANT, 24, 0.4, 1.0, null, false);
        Vfx.cloud(ctx(), center, Particle.FIREWORK, 10, 0.2, 0.15, null, false);
        Vfx.shockwave(ctx(), owner, target.getLocation().add(0, 0.1, 0), 0.4, Math.max(0.8, radius), 5,
                Particle.DUST_COLOR_TRANSITION, GLOW, false);
        scatterCards(center, TRICK_CARDS, 1.4, 18);
        popText(center.clone().add(0, 1.2, 0), "<b><gradient:#FFF3C4:#FFC83D:#A66CFF>트릭!</gradient></b>", 1.2f, 22);
        Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.2f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 1.4f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 1.0f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.7f, 1.2f + Fx.jitter(0.05f));
        if (hits > 0) {
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>트릭 완성!</b></" + GOLD_TAG + ">"));
        }
    }

    // ------------------------------------------------------------------ ultimate: the vanishing act

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, Math.max(1, params().num("ult_range")));
        if (target == null || !isVisible(target)) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        VanishingAct act = new VanishingAct(owner, target);
        act.begin();
        Loop.repeat(ctx(), owner, 1, 1, act.hold + HAT_LIFT_DELAY + 2, act, act::finish);
        return ActivationResult.success();
    }

    /**
     * The vanishing act. A velvet curtain unrolls around the enemy, which is held (rooted and silenced) behind it
     * while a top hat spins over the magician's head and a drum roll plays. Then the curtain drops onto an empty
     * stage: the enemy falls out of thin air somewhere else (hurt and blinded) and the magician is swallowed by the
     * giant hat, which lifts off with doves and leaves nobody behind.
     */
    private final class VanishingAct implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final Location stage;
        private final long hold;
        private final List<BlockDisplay> curtain = new ArrayList<>();
        private final List<BlockDisplay> trim = new ArrayList<>();
        private final BlockDisplay[] hat = new BlockDisplay[3];
        private float hatSpin;
        private boolean revealed;

        VanishingAct(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.stage = flat(target.getLocation());
            this.hold = Math.max(1, params().ticks("ult_hold"));
        }

        void begin() {
            // Held behind the curtain: no walking, no jumping, no skills.
            long held = hold + 2;
            ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, held);
            ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, held);
            if (target instanceof Player victim) {
                ctx().silence(victim, held);
            }
            // Stop it in its tracks (a player's server-side velocity is stale, so nothing is read from it).
            target.setVelocity(new Vector());
            raiseCurtain();
            raiseHat();
            Location chest = Fx.body(owner);
            Vfx.cloud(ctx(), chest, Particle.ENCHANT, 20, 0.4, 1.0, null, true);
            Vfx.trail(hand(owner), Fx.body(target), GOLD, 8, true);
            Fx.sound(chest, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.0f, 1.1f);
            Fx.sound(stage, Sound.BLOCK_WOOL_PLACE, 1.0f, 0.6f);
            Fx.sound(stage, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.8f, 1.4f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>사라지는 마술!</b></" + COLOR_TAG + ">"));
        }

        @Override
        public boolean test(long tick) {
            if (!revealed) {
                if (tick + 1 < hold) {
                    holding(tick);
                    return true;
                }
                reveal();
                return true;
            }
            long since = tick - hold + 1;
            if (since == HAT_COVER_TICKS) {
                vanishOwner();
            }
            if (since >= HAT_LIFT_DELAY) {
                liftHat();
                return false;
            }
            return true;
        }

        /** Four velvet panels unrolling from a gold pelmet around the enemy. */
        private void raiseCurtain() {
            long life = hold + CURTAIN_DROP_TICKS + 12;
            float width = STAGE_HALF * 2 + CURTAIN_THICKNESS;
            for (int side = 0; side < 4; side++) {
                double angle = side * Math.PI / 2;
                Vector normal = new Vector(Math.sin(angle), 0, Math.cos(angle));
                float yaw = side * 90f;
                Location panelAt = stage.clone().add(normal.clone().multiply(STAGE_HALF)).add(0, CURTAIN_HEIGHT / 2, 0);
                BlockDisplay panel = Displays.block(ctx(), owner, panelAt, VELVET.createBlockData(),
                        Displays.transform(0, CURTAIN_HEIGHT / 2, 0, width, 0.02f, CURTAIN_THICKNESS, yaw, 0, 0), life);
                Displays.animateNextTick(ctx(), owner, panel, Displays.transform(0, 0, 0, width, CURTAIN_HEIGHT,
                        CURTAIN_THICKNESS, yaw, 0, 0), CURTAIN_DROP_TICKS);
                curtain.add(panel);
                Location trimAt = stage.clone().add(normal.clone().multiply(STAGE_HALF)).add(0, CURTAIN_HEIGHT + 0.05, 0);
                BlockDisplay bar = Displays.block(ctx(), owner, trimAt, TRIM.createBlockData(),
                        Displays.transform(0, 0, 0, 0.05f, 0.05f, 0.05f, yaw, 0, 0), life);
                Displays.outline(bar, GOLD);
                Displays.animateNextTick(ctx(), owner, bar, Displays.transform(0, 0, 0, width + 0.12f, 0.12f, 0.12f,
                        yaw, 0, 0), 3);
                trim.add(bar);
            }
        }

        /** The top hat appears over the magician's head. */
        private void raiseHat() {
            long life = hold + HAT_LIFT_DELAY + 14;
            Location at = hatSpot();
            hat[0] = Displays.block(ctx(), owner, at, HAT.createBlockData(), hatPose(0, 0.1f, false), life);
            hat[1] = Displays.block(ctx(), owner, at, HAT.createBlockData(), hatPose(1, 0.1f, false), life);
            hat[2] = Displays.block(ctx(), owner, at, HAT_BAND.createBlockData(), hatPose(2, 0.1f, false), life);
            for (BlockDisplay part : hat) {
                part.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, part, hatPose(partIndex(part), 1f, false), 4);
            }
            Vfx.cloud(ctx(), at, Particle.POOF, 6, 0.2, 0.02, null, false);
        }

        private int partIndex(BlockDisplay part) {
            return part == hat[0] ? 0 : part == hat[1] ? 1 : 2;
        }

        /** The hold: the drum roll speeds up, the hat spins, the curtain sways and glitters. */
        private void holding(long tick) {
            hatSpin += 22f;
            Location at = hatSpot();
            for (int i = 0; i < hat.length; i++) {
                BlockDisplay part = hat[i];
                if (part != null && part.isValid()) {
                    Displays.moveTo(part, at);
                    Displays.animate(part, hatPose(i, 1f, false), 1);
                }
            }
            Vfx.ring(ctx(), at.clone().add(0, 0.05, 0), 0.5, 8, tick * 0.5, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW,
                    false);
            long period = Math.max(1, 4 - tick * 3 / Math.max(1, hold));
            if (tick % period == 0) {
                Fx.sound(stage, Sound.BLOCK_NOTE_BLOCK_SNARE, 1.0f, 0.9f + 0.6f * tick / hold);
            }
            if (tick % 4 == 0) {
                float sway = (tick / 4 % 2 == 0 ? 1.5f : -1.5f);
                float width = STAGE_HALF * 2 + CURTAIN_THICKNESS;
                for (int side = 0; side < curtain.size(); side++) {
                    BlockDisplay panel = curtain.get(side);
                    if (tick >= CURTAIN_DROP_TICKS && panel.isValid()) {
                        Displays.animate(panel, Displays.transform(0, 0, 0, width, CURTAIN_HEIGHT, CURTAIN_THICKNESS,
                                side * 90f, 0, sway), 4);
                    }
                }
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location glitter = stage.clone().add(random.nextDouble(-STAGE_HALF, STAGE_HALF),
                    random.nextDouble(0.3, CURTAIN_HEIGHT), random.nextDouble(-STAGE_HALF, STAGE_HALF));
            Vfx.dot(glitter, Particle.DUST, GOLD_DUST, true);
            Vfx.dot(stage.clone().add(0, CURTAIN_HEIGHT + 0.3, 0), Particle.WITCH, null, false);
        }

        /** Ta-da: the curtain drops onto an empty stage, the enemy falls out of thin air elsewhere. */
        private void reveal() {
            revealed = true;
            dropCurtain();
            Location middle = stage.clone().add(0, 1.0, 0);
            Vfx.dot(middle, Particle.FLASH, PALETTE.body(), true);
            Vfx.cloud(ctx(), middle, Particle.CLOUD, 20, 0.6, 0.05, null, true);
            Vfx.cloud(ctx(), middle, Particle.WITCH, 16, 0.6, 0.1, null, true);
            Vfx.cloud(ctx(), middle, Particle.DUST_COLOR_TRANSITION, 20, 0.7, 0, FADE, true);
            Fx.sound(middle, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 0.9f);
            Fx.sound(middle, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 1.2f);
            Fx.sound(middle, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
            Fx.sound(middle, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.26f);
            Fx.sound(middle, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.5f);
            coverOwner();
            if (Combat.isTargetable(ctx(), owner, target) && target.getWorld().equals(stage.getWorld())) {
                Location spot = vanishSpot();
                if (spot != null) {
                    arrive(spot);
                } else {
                    // No room anywhere: the trick still lands, right where the enemy stood.
                    hurt(target.getLocation());
                }
            }
        }

        private void dropCurtain() {
            float width = STAGE_HALF * 2 + CURTAIN_THICKNESS;
            for (int side = 0; side < curtain.size(); side++) {
                BlockDisplay panel = curtain.get(side);
                if (!panel.isValid()) {
                    continue;
                }
                Displays.animate(panel, Displays.transform(0, -CURTAIN_HEIGHT / 2, 0, width, 0.02f, CURTAIN_THICKNESS,
                        side * 90f, 0, 0), 5);
                ctx().runLater(owner, 6, () -> {
                    if (panel.isValid()) {
                        panel.remove();
                    }
                });
            }
            for (BlockDisplay bar : trim) {
                if (bar.isValid()) {
                    Displays.shrinkAway(ctx(), owner, bar, 5);
                }
            }
        }

        /** The giant hat drops over the magician. */
        private void coverOwner() {
            Location feet = flat(owner.getLocation());
            for (int i = 0; i < hat.length; i++) {
                BlockDisplay part = hat[i];
                if (part != null && part.isValid()) {
                    part.setTeleportDuration(HAT_COVER_TICKS);
                    Displays.moveTo(part, feet);
                    Displays.animate(part, hatPose(i, 1f, true), HAT_COVER_TICKS);
                }
            }
            Fx.sound(feet, Sound.BLOCK_WOOL_FALL, 1.0f, 0.6f);
        }

        /** Under the hat, the magician is gone. */
        private void vanishOwner() {
            long ticks = params().ticks("ult_stealth");
            if (ticks <= 0) {
                return;
            }
            stealth.begin(ctx(), owner, ticks, () -> reappear(false));
            Stealth.dropAggro(ctx(), owner, 16);
        }

        /** The hat lifts off and vanishes in a puff, doves flying out of it. */
        private void liftHat() {
            Location feet = flat(owner.getLocation());
            Location above = feet.clone().add(0, 2.2, 0);
            for (BlockDisplay part : hat) {
                if (part == null || !part.isValid()) {
                    continue;
                }
                part.setTeleportDuration(6);
                Displays.moveTo(part, above);
                Displays.animate(part, hatPose(partIndex(part), 1f, false), 6);
                ctx().runLater(owner, 6, () -> {
                    if (part.isValid()) {
                        Vfx.cloud(ctx(), above, Particle.POOF, 8, 0.3, 0.03, null, false);
                        Displays.shrinkAway(ctx(), owner, part, 3);
                    }
                });
            }
            doves(feet.clone().add(0, 1.2, 0));
            Fx.sound(feet, Sound.ENTITY_PARROT_FLY, 1.0f, 1.2f);
            Fx.sound(feet, Sound.ENTITY_PHANTOM_FLAP, 0.6f, 1.6f);
        }

        /** Two white feathers flapping up and away. */
        private void doves(Location at) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < DOVES; i++) {
                double angle = random.nextDouble(TAU);
                Location start = flat(at);
                Location end = start.clone().add(Math.cos(angle) * 2.5, 3.0 + random.nextDouble(), Math.sin(angle) * 2.5);
                // Minecraft yaw of the flight, plus 90 so the feather's flat side shows the flight edge-on.
                float yaw = (float) Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle))) + 90f;
                ItemDisplay dove = Displays.item(ctx(), owner, start, ItemStack.of(Material.FEATHER),
                        Displays.centered(0, 0, 0, 0.6f, 0.6f, 0.6f, yaw, 0, 0), 22);
                dove.setTeleportDuration(16);
                ctx().runLater(owner, 1, () -> {
                    if (dove.isValid()) {
                        Displays.moveTo(dove, end);
                    }
                });
                Loop.repeat(ctx(), owner, 1, 3, 6, run -> {
                    if (!dove.isValid()) {
                        return false;
                    }
                    float flap = run % 2 == 0 ? 25f : -25f;
                    Displays.animate(dove, Displays.centered(0, 0, 0, 0.6f, 0.6f, 0.6f, yaw, 0, flap), 3);
                    Vfx.dot(dove.getLocation(), Particle.DUST, new Particle.DustOptions(Color.WHITE, 0.7f), false);
                    return true;
                });
                ctx().runLater(owner, 18, () -> {
                    if (dove.isValid()) {
                        Displays.shrinkAway(ctx(), owner, dove, 3);
                    }
                });
            }
        }

        /**
         * A random safe spot between the minimum and maximum distance from the stage (then closer, if nothing fits),
         * inside the arena and its border. Null if there is none.
         */
        private @Nullable Location vanishSpot() {
            double min = Math.max(0, params().num("ult_min_distance"));
            double max = Math.max(min, params().num("ult_max_distance"));
            Body body = Body.standing(target);
            World world = stage.getWorld();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int attempt = 0; attempt < SEARCH_ATTEMPTS; attempt++) {
                double low = attempt < SEARCH_ATTEMPTS / 2 ? min : min * 0.4;
                double distance = low + random.nextDouble() * Math.max(0, max - low);
                double angle = random.nextDouble(TAU);
                Location probe = stage.clone().add(Math.cos(angle) * distance, 6, Math.sin(angle) * distance);
                Double ground = groundBelow(probe, GROUND_SEARCH);
                if (ground == null) {
                    continue;
                }
                Location feet = probe.clone();
                feet.setY(ground);
                if (Spots.isSafe(ctx(), feet, body)) {
                    return feet;
                }
            }
            return null;
        }

        /** The enemy falls out of thin air at {@code spot}: cards burst, "짜잔!", damage and blindness. */
        private void arrive(Location spot) {
            Body body = Body.standing(target);
            Location drop = spot.clone().add(0, DROP_HEIGHT, 0);
            Location to = Spots.isFree(spot.getWorld(), drop, body.width(), body.height()) ? drop : spot.clone();
            Location current = target.getLocation();
            to.setYaw(current.getYaw());
            to.setPitch(current.getPitch());
            target.teleport(to);
            target.setVelocity(new Vector(0, -0.1, 0));
            target.setFallDistance(0);
            Location chest = Fx.body(target);
            Vfx.dot(chest, Particle.FLASH, GOLD, true);
            Vfx.cloud(ctx(), chest, Particle.CLOUD, 14, 0.4, 0.04, null, true);
            Vfx.cloud(ctx(), chest, Particle.DUST_COLOR_TRANSITION, 16, 0.5, 0, GOLD_GLOW, true);
            Vfx.ring(ctx(), spot.clone().add(0, 0.1, 0), 1.2, 18, 0, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            scatterCards(chest, 4, 1.1, 16);
            popText(chest.clone().add(0, 1.3, 0), "<b><gradient:#FFF3C4:#FFC83D:#A66CFF>짜잔!</gradient></b>", 1.4f,
                    26);
            Fx.sound(chest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f);
            Fx.sound(chest, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.3f);
            Fx.sound(chest, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            hurt(to);
        }

        private void hurt(Location at) {
            Combat.hit(ctx(), owner, target, Math.max(0, params().num("ult_damage")));
            long blind = params().ticks("ult_blind");
            if (blind > 0 && Combat.isTargetable(ctx(), owner, target)) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                        (int) Math.min(Integer.MAX_VALUE, blind), 0, false, false, true));
            }
            // The target may be a custom-named entity (a training dummy): its name goes in as a component.
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>짜잔!</b> <white><name></white>이(가) 사라졌습니다</"
                    + COLOR_TAG + ">", Placeholder.component("name", target.name())));
            Vfx.cloud(ctx(), at.clone().add(0, 1, 0), Particle.ENCHANT, 12, 0.4, 0.8, null, false);
        }

        /** Where the spinning hat hovers: over the magician's head. */
        private Location hatSpot() {
            return flat(owner.getLocation().add(0, owner.getHeight() + 0.45, 0));
        }

        /**
         * One part of the hat (0 crown, 1 brim, 2 band), its origin at the bottom of the brim: the small hat spins
         * over the head, the giant one covers a whole body.
         */
        private Transformation hatPose(int part, float grow, boolean giant) {
            float crownWidth = (giant ? 0.95f : 0.42f) * grow;
            float crownHeight = (giant ? 2.1f : 0.5f) * grow;
            float brimWidth = (giant ? 1.5f : 0.72f) * grow;
            float brimHeight = (giant ? 0.08f : 0.05f) * grow;
            float bandHeight = (giant ? 0.25f : 0.1f) * grow;
            return switch (part) {
                case 0 -> Displays.transform(0, brimHeight + crownHeight / 2, 0, crownWidth, crownHeight, crownWidth,
                        hatSpin, 0, 0);
                case 1 -> Displays.transform(0, brimHeight / 2, 0, brimWidth, brimHeight, brimWidth, hatSpin, 0, 0);
                default -> Displays.transform(0, brimHeight + bandHeight / 2, 0, crownWidth * 1.05f, bandHeight,
                        crownWidth * 1.05f, hatSpin, 0, 0);
            };
        }

        /** The loop ended by itself: anything not revealed yet is taken down. */
        void finish() {
            if (revealed) {
                return;
            }
            for (BlockDisplay part : curtain) {
                part.remove();
            }
            for (BlockDisplay part : trim) {
                part.remove();
            }
            for (BlockDisplay part : hat) {
                if (part != null) {
                    part.remove();
                }
            }
        }
    }

    /** The magician steps out of thin air: the stealth ends early on an attack, or when its time is up. */
    private void reappear(boolean early) {
        Player owner = player();
        if (!stealth.end(ctx(), owner)) {
            return;
        }
        Location chest = Fx.body(owner);
        Vfx.cloud(ctx(), chest, Particle.WITCH, 10, 0.3, 0.05, null, false);
        Vfx.cloud(ctx(), chest, Particle.DUST_COLOR_TRANSITION, 10, 0.35, 0, GLOW, false);
        Fx.sound(chest, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, early ? 1.6f : 1.3f);
    }

    /** An attack reveals the vanished magician. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (stealth.isActive() && player().equals(event.getDamager())) {
            reappear(true);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** False for an enemy player the owner cannot see (hidden by stealth): no aiming at it, no marks over it. */
    private boolean isVisible(LivingEntity target) {
        return !(target instanceof Player victim) || player().canSee(victim);
    }

    /** The magician's card hand: chest height, forward and to the right. */
    private static Location hand(Player owner) {
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontalFacing(feet);
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        return flat(feet.add(0, owner.getHeight() * 0.7, 0).add(facing.multiply(0.35)).add(right.multiply(0.3)));
    }

    /** A copy of {@code at} without rotation (display entities are posed by their transformation). */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }

    /** A card lying flat, turned {@code spin} degrees about the vertical. */
    private static Transformation cardPose(float scale, float spin) {
        return Displays.centered(0, 0, 0, scale, scale, scale, spin, 90, 0);
    }

    /** The top of the first solid block at most {@code depth} blocks below {@code at}, null if none. */
    private static @Nullable Double groundBelow(Location at, double depth) {
        World world = at.getWorld();
        int x = at.getBlockX();
        int z = at.getBlockZ();
        int start = Math.min(at.getBlockY(), world.getMaxHeight() - 1);
        int end = (int) Math.max(world.getMinHeight(), start - depth);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /** An onomatopoeia popping up with an overshoot, then shrinking away. */
    private void popText(Location at, String text, float scale, long life) {
        Player owner = player();
        float roll = (float) ThreadLocalRandom.current().nextDouble(-10, 10);
        TextDisplay label = Displays.text(ctx(), owner, flat(at), Text.mm(text), scale * 0.3f, life);
        float pop = scale * 1.3f;
        Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.15f, 0, pop, pop, pop, 0, 0, roll), 2);
        ctx().runLater(owner, 3, () -> Displays.animate(label, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0,
                0, roll), 3));
        ctx().runLater(owner, Math.max(4, life - 5), () -> {
            if (label.isValid()) {
                Displays.shrinkAway(ctx(), owner, label, 4);
            }
        });
    }
}
