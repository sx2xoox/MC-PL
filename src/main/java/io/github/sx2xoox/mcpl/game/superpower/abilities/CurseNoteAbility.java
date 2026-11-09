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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 빨간 글씨 — the Korean school superstition that writing a name in red ink kills, and a notebook a nameless ink ghost
 * lives in. Only names exchanged in a fight can be written: an enemy the owner hurt or was hurt by is known for a while
 * (a few names at most, shown in red over their heads for the owner only). The active writes a known enemy's name in
 * red: the name and a countdown float over its head for all to see, and when the countdown ends red ink runs over it,
 * hurting and felling it for a moment, unless it finds the writer first: hurting the owner during the countdown tears
 * the page and slows the owner instead. When a written name's owner falls, the ink comes back (the cooldown is reset).
 * The ultimate borrows the ink ghost's eyes: every enemy around is known and seen through walls, names are written fast
 * and fall sooner, and afterwards the ink runs dry for a while.
 *
 * <p>Unlike the 사신's mark (a share of the damage taken, later), this is a fixed countdown with a counter-rule: the
 * cursed hunts the writer, the writer hides or holds out. Knowing a name first means fighting first; the ultimate's price
 * is an empty notebook. The ultimate changes the active (cooldown, countdown, unlimited names), so it is not standalone.
 *
 * <p>Look: notebook black, red ink and the ink ghost's grey. A known name hangs over an enemy's head in red, only for the
 * owner. Writing opens a notebook in front of the owner, red ink flies from it to the victim and the name is written
 * over its head letter by letter above a countdown, ink dripping below it while grey wisps circle closer. At zero the
 * ink spreads up the body in a red and black spiral and splashes; a torn page bursts into shreds of paper. Under the
 * ultimate a grey ink ghost with red eyes looms over the owner's shoulder.
 */
public final class CurseNoteAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("cursenote", "빨간 글씨", Material.WRITABLE_BOOK)
            .color(TextColor.color(0x9E0F1F))
            .difficulty(2)
            .passive("통성명",
                    "나와 피해를 주고받은 적의 이름을 {name_duration}초간 압니다(최대 {name_max}명). 이름을 적어 둔 "
                            + "적이 {name_duration}초 안에 쓰러지면 이름 적기 쿨타임이 바로 돌아옵니다.")
            .active("이름 적기",
                    "이름을 아는 적 가운데 조준한 적(없으면 가장 가까운 적, {write_range}블록 이내)의 이름을 적습니다. "
                            + "그 적의 머리 위에 {write_countdown}초 카운트다운이 뜨고, 끝나면 {write_damage} 피해를 "
                            + "입고 {write_down}초간 쓰러져 움직일 수 없습니다. 그 사이 그 적이 나에게 피해를 주면 "
                            + "무효가 되고 나는 {torn_slow}초간 이동 속도가 {torn_slow_amount}% 느려집니다.",
                    11)
            .ultimate("저승의 시력",
                    "{sight_duration}초간 잉크 귀신의 눈을 빌려 {sight_radius}블록 안 모든 적의 이름을 알고(수 제한 "
                            + "없음), 그 이름이 벽 너머로도 보입니다. 그동안 이름 적기 쿨타임은 {sight_cooldown}초, "
                            + "카운트다운은 {sight_countdown}초가 되며, 한 적에게는 한 번에 하나의 이름만 적을 수 있습니다. "
                            + "끝나면 잉크가 바닥나 {dry_duration}초간 이름을 적을 수 없습니다.")
            .ultimateNotStandalone()
            .param("name_duration", 15)
            .param("name_max", 3)
            .param("write_range", 30)
            .param("write_countdown", 4)
            .param("write_damage", 5)
            .param("write_down", 1)
            .param("torn_slow", 1)
            .param("torn_slow_amount", 40)
            .param("sight_duration", 8)
            .param("sight_radius", 40)
            .param("sight_cooldown", 2)
            .param("sight_countdown", 3)
            .param("dry_duration", 8)
            .build();

    private static final String INK_TAG = "#9E0F1F";
    private static final String BRIGHT_TAG = "#FF4A5C";
    private static final String GHOST_TAG = "#6E6A73";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: notebook black, red ink, ink ghost
    /** Bright red ink, red ink, notebook black. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFF4A5C, 0x9E0F1F, 0x121212);
    private static final Color INK = Color.fromRGB(0x9E0F1F);
    private static final Color GHOST = Color.fromRGB(0x6E6A73);
    private static final TextColor INK_TEXT = TextColor.color(0x9E0F1F);
    private static final TextColor BRIGHT_TEXT = TextColor.color(0xFF4A5C);
    private static final TextColor DARK_TEXT = TextColor.color(0x5A0812);
    private static final Particle.DustOptions INK_DUST = PALETTE.dust(1.0f);
    private static final Particle.DustOptions INK_DROP = PALETTE.dust(0.6f);
    private static final Particle.DustOptions BLACK_DUST = new Particle.DustOptions(Color.fromRGB(0x121212), 1.0f);
    private static final Particle.DustOptions GHOST_DUST = new Particle.DustOptions(GHOST, 0.9f);
    private static final Particle.DustOptions GHOST_EYE = new Particle.DustOptions(Color.fromRGB(0xFF2A3C), 0.5f);
    private static final Particle.DustTransition INK_GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition INK_FADE = PALETTE.fade(1.2f);

    /** Block and item types of the visuals (block data and items are created on use: they need a running server). */
    private static final Material BOOK_ITEM = Material.WRITABLE_BOOK;
    private static final Material PAPER_ITEM = Material.PAPER;
    private static final Material GHOST_BLOCK = Material.GRAY_STAINED_GLASS;

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    private static final double NAME_ABOVE = 0.45;
    private static final double CURSE_ABOVE = 0.95;
    private static final long WRITE_TICKS = 10;
    private static final long BOOK_TICKS = 18;
    private static final long STATUS_PERIOD = 40;
    private static final long NAME_REFRESH = 10;
    private static final int MAX_NAME_LENGTH = 16;
    private static final float GHOST_HEAD_Y = 2.05f;

    /** Names the owner knows, the one least lately exchanged blows with first. */
    private final Map<UUID, Known> known = new LinkedHashMap<>();
    /** Countdowns running, by entity id. */
    private final Map<UUID, Curse> curses = new HashMap<>();
    /** Names written lately: when their owner falls, the ink comes back. Entity id to the tick it stops counting. */
    private final Map<UUID, Written> written = new HashMap<>();
    private final List<Display> displays = new ArrayList<>();
    private long sightUntil = Long.MIN_VALUE / 2;
    private long dryUntil = Long.MIN_VALUE / 2;
    private long lastStatus = Long.MIN_VALUE / 2;
    private final List<BlockDisplay> ghost = new ArrayList<>();

    private static final class Known {
        final LivingEntity entity;
        long until;
        @Nullable TextDisplay label;
        final Set<UUID> hiddenFrom = new HashSet<>();

        Known(LivingEntity entity) {
            this.entity = entity;
        }
    }

    private record Written(LivingEntity entity, long until) {
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        for (Known name : known.values()) {
            removeLabel(name);
        }
        known.clear();
        curses.clear();
        written.clear();
        ghost.clear();
        displays.clear();
    }

    private boolean inSight() {
        return ctx().currentTick() < sightUntil;
    }

    private boolean isDry() {
        return ctx().currentTick() < dryUntil;
    }

    /** Under the ink ghost's eyes the notebook is quick: the cooldown that starts now is the ultimate's. */
    @Override
    public double cooldownMultiplier() {
        if (!inSight()) {
            return 1.0;
        }
        double cooldown = params().cooldownSeconds();
        return cooldown > 0 ? Math.clamp(params().num("sight_cooldown") / cooldown, 0.0, 1.0) : 1.0;
    }

    // ------------------------------------------------------------------ passive: exchanging names

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isEnemy(player(), victim)) {
            learn(player(), victim, true);
        }
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity) || event.isCancelled()) {
            return;
        }
        Player owner = player();
        Entity damager = byEntity.getDamager();
        Player attacker = ctx().resolveAttacker(damager);
        LivingEntity source = attacker != null ? attacker : damager instanceof LivingEntity living ? living : null;
        if (source == null || !ctx().isEnemy(owner, source)) {
            return;
        }
        learn(owner, source, true);
        Curse curse = curses.get(source.getUniqueId());
        if (curse != null && event.getFinalDamage() > 0) {
            curse.tear();
        }
    }

    /**
     * {@code enemy}'s name is known for a while. Every {@code exchange} of blows moves it to the back of the line, so
     * beyond the limit the name least lately exchanged blows with is forgotten (never the one of the duel going on);
     * the underworld sight only adds the names it did not know yet.
     */
    private void learn(Player owner, LivingEntity enemy, boolean exchange) {
        long now = ctx().currentTick();
        long duration = Math.max(1, params().ticks("name_duration"));
        Known name = exchange ? known.remove(enemy.getUniqueId()) : known.get(enemy.getUniqueId());
        boolean fresh = name == null;
        if (fresh) {
            name = new Known(enemy);
        }
        known.put(enemy.getUniqueId(), name);
        name.until = Math.max(name.until, now + duration);
        if (!inSight()) {
            trimNames();
        }
        if (fresh && known.containsKey(enemy.getUniqueId()) && !isHidden(owner, enemy)) {
            Location head = enemy.getLocation().add(0, enemy.getHeight() + NAME_ABOVE, 0);
            Vfx.trail(owner.getEyeLocation().subtract(0, 0.4, 0), head, INK, 10, false);
            owner.playSound(owner.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.8f,
                    1.3f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<" + INK_TAG + ">✎ 이름을 알았다:</" + INK_TAG + "> <" + BRIGHT_TAG
                    + "><name></" + BRIGHT_TAG + ">", Placeholder.component("name", enemy.name())));
            lastStatus = now;
        }
    }

    /** Forgets the names least lately exchanged blows with beyond the limit (a name being written is kept). */
    private void trimNames() {
        int max = Math.max(1, params().integer("name_max"));
        Iterator<Known> names = known.values().iterator();
        while (known.size() > max && names.hasNext()) {
            Known oldest = names.next();
            if (curses.containsKey(oldest.entity.getUniqueId())) {
                continue;
            }
            removeLabel(oldest);
            names.remove();
        }
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        boolean sight = inSight();
        if (sight && tick % 10 == 0) {
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), params().num("sight_radius"))) {
                if (!isHidden(owner, enemy)) {
                    learn(owner, enemy, false);
                }
            }
        }
        Iterator<Known> names = known.values().iterator();
        while (names.hasNext()) {
            Known name = names.next();
            LivingEntity enemy = name.entity;
            boolean writing = curses.containsKey(enemy.getUniqueId());
            if (!writing && (name.until <= now || !Combat.isTargetable(ctx(), owner, enemy))) {
                removeLabel(name);
                names.remove();
                continue;
            }
            showLabel(owner, name, sight, tick);
        }
        checkWritten(owner, now);
        updateGhost(owner, tick);
        if (isDry() && tick % 10 == 0) {
            Location hand = owner.getEyeLocation().subtract(0, 0.5, 0);
            Vfx.cloud(ctx(), hand, Particle.WHITE_ASH, 3, 0.2, 0.01, null, false);
            if (tick % 20 == 0) {
                owner.sendActionBar(Text.mm("<gray>✎ 잉크가 바닥났습니다 — " + Fx.seconds(dryUntil - now)
                        + "초</gray>"));
                lastStatus = now;
            }
        } else if (!known.isEmpty() && now - lastStatus >= STATUS_PERIOD) {
            status(owner, now);
        }
    }

    /** The names the owner knows, on the action bar now and then. */
    private void status(Player owner, long now) {
        lastStatus = now;
        TextComponent.Builder bar = Component.text().append(Text.mm("<" + INK_TAG + ">✎</" + INK_TAG + "> "));
        boolean first = true;
        for (Known name : known.values()) {
            if (!first) {
                bar.append(Component.text(" · ", TextColor.color(0x777777)));
            }
            first = false;
            bar.append(name.entity.name().colorIfAbsent(BRIGHT_TEXT));
            if (!inSight()) {
                bar.append(Component.text(" " + Fx.seconds(Math.max(0, name.until - now)) + "초",
                        TextColor.color(0x999999)));
            }
        }
        owner.sendActionBar(bar.build());
    }

    /** A known name over the enemy's head, only for the owner; seen through walls under the ink ghost's eyes. */
    private void showLabel(Player owner, Known name, boolean sight, long tick) {
        LivingEntity enemy = name.entity;
        Location over = enemy.getLocation().add(0, enemy.getHeight() + NAME_ABOVE, 0);
        TextDisplay label = name.label;
        if (label == null || !label.isValid()) {
            if (!reserve(1)) {
                return;
            }
            label = Displays.text(ctx(), owner, over, nameText(enemy, sight), 0.55f, 0);
            label.setTeleportDuration(1);
            label.setViewRange(2.0f);
            name.label = label;
            name.hiddenFrom.clear();
            displays.add(label);
            onlyForOwner(owner, label, name.hiddenFrom);
        }
        if (tick % NAME_REFRESH == 0) {
            onlyForOwner(owner, label, name.hiddenFrom);
            label.text(nameText(enemy, sight));
        }
        boolean hidden = isHidden(owner, enemy);
        float range = hidden ? 0f : 2.0f;
        if (label.getViewRange() != range) {
            label.setViewRange(range);
        }
        if (label.isSeeThrough() != sight) {
            label.setSeeThrough(sight);
        }
        Displays.moveTo(label, over);
    }

    private static Component nameText(LivingEntity enemy, boolean sight) {
        Component name = enemy.name().colorIfAbsent(BRIGHT_TEXT);
        return Component.text().append(Component.text(sight ? "◉ " : "✎ ", INK_TEXT)).append(name).build();
    }

    /** Hides {@code display} from everyone online but the owner (again now and then, for those who came later). */
    private void onlyForOwner(Player owner, Display display, Set<UUID> hiddenFrom) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(owner) && hiddenFrom.add(online.getUniqueId())) {
                online.hideEntity(ctx().plugin(), display);
            }
        }
    }

    private static void removeLabel(Known name) {
        if (name.label != null && name.label.isValid()) {
            name.label.remove();
        }
        name.label = null;
    }

    /** A written name's owner fell: the ink comes back to the notebook. */
    private void checkWritten(Player owner, long now) {
        if (written.isEmpty()) {
            return;
        }
        Iterator<Written> entries = written.values().iterator();
        while (entries.hasNext()) {
            Written entry = entries.next();
            LivingEntity enemy = entry.entity();
            boolean fallen = enemy.isDead() || !enemy.isValid()
                    || enemy instanceof Player player && !ctx().isAlive(player);
            if (fallen) {
                entries.remove();
                inkReturns(owner);
            } else if (entry.until() <= now) {
                entries.remove();
            }
        }
    }

    private void inkReturns(Player owner) {
        if (ctx().cooldownRemaining(owner) <= 0) {
            return;
        }
        ctx().reduceCooldown(owner, Math.max(1, params().cooldownSeconds()) * 10);
        Location hand = owner.getEyeLocation().subtract(0, 0.5, 0);
        Vfx.helix(ctx(), owner.getLocation().add(0, 0.1, 0), 0.5, owner.getHeight(), 1.5, 2, 10, 0,
                Particle.DUST_COLOR_TRANSITION, INK_GLOW, false);
        Vfx.cloud(ctx(), hand, Particle.DUST, 8, 0.25, 0, INK_DUST, false);
        Fx.sound(hand, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 0.8f);
        Fx.sound(hand, Sound.ITEM_INK_SAC_USE, 1.0f, 1.2f);
        owner.sendActionBar(Text.mm("<" + BRIGHT_TAG + ">✎ 잉크가 돌아왔습니다</" + BRIGHT_TAG + "> <gray>이름 적기 준비"
                + "</gray>"));
        lastStatus = ctx().currentTick();
    }

    // ------------------------------------------------------------------ active: writing a name

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        long now = ctx().currentTick();
        if (isDry()) {
            return ActivationResult.fail("잉크가 바닥났습니다 (" + Fx.seconds(dryUntil - now) + "초)");
        }
        if (known.isEmpty()) {
            return ActivationResult.fail("아는 이름이 없습니다 — 먼저 싸워서 이름을 알아내세요");
        }
        double range = params().num("write_range");
        List<LivingEntity> writable = new ArrayList<>();
        for (Known name : known.values()) {
            LivingEntity enemy = name.entity;
            if (Combat.isTargetable(ctx(), owner, enemy) && !isHidden(owner, enemy)
                    && !curses.containsKey(enemy.getUniqueId()) && enemy.getWorld().equals(owner.getWorld())
                    && enemy.getLocation().distanceSquared(owner.getLocation()) <= range * range) {
                writable.add(enemy);
            }
        }
        if (writable.isEmpty()) {
            return ActivationResult.fail("적을 수 있는 이름이 없습니다");
        }
        LivingEntity aimed = ctx().findTarget(owner, range);
        LivingEntity target = aimed != null && writable.contains(aimed) ? aimed : null;
        if (target == null) {
            Location at = owner.getLocation();
            target = writable.stream().min((a, b) -> Double.compare(a.getLocation().distanceSquared(at),
                    b.getLocation().distanceSquared(at))).orElseThrow();
        }
        long countdown = Math.max(1, params().ticks(inSight() ? "sight_countdown" : "write_countdown"));
        Curse curse = new Curse(owner, target, countdown);
        curses.put(target.getUniqueId(), curse);
        written.put(target.getUniqueId(), new Written(target, now + Math.max(1, params().ticks("name_duration"))));
        curse.start();
        return ActivationResult.success();
    }

    private enum CurseStage { COUNTING, TORN, DONE }

    /**
     * One name in red: the name and its countdown over the cursed head, then the ink. Hurting the writer before the end
     * tears the page.
     */
    private final class Curse implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final long countdown;
        private final String plainName;
        private @Nullable TextDisplay label;
        private @Nullable ItemDisplay book;
        private CurseStage stage = CurseStage.COUNTING;

        Curse(Player owner, LivingEntity target, long countdown) {
            this.owner = owner;
            this.target = target;
            this.countdown = countdown;
            this.plainName = plain(target);
        }

        void start() {
            openBook();
            Location over = labelSpot();
            if (reserve(1)) {
                label = Displays.text(ctx(), owner, over, curseText(0, countdown), 0.8f, countdown + 30);
                label.setTeleportDuration(1);
                label.setViewRange(2.5f);
                displays.add(label);
            }
            Vfx.trail(bookSpot(), over, INK, 12, true);
            Vfx.ring(ctx(), target.getLocation().add(0, 0.1, 0), 0.9, 14, 0, Particle.DUST_COLOR_TRANSITION, INK_FADE,
                    true);
            Fx.sound(bookSpot(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 0.7f);
            Fx.sound(bookSpot(), Sound.ITEM_INK_SAC_USE, 1.0f, 0.6f);
            Fx.sound(over, Sound.ENTITY_VEX_AMBIENT, 0.8f, 0.6f + Fx.jitter(0.05f));
            owner.sendActionBar(Text.mm("<" + INK_TAG + "><b>✎ 이름 적기</b></" + INK_TAG + "> <" + BRIGHT_TAG
                            + "><target></" + BRIGHT_TAG + "> <gray>" + Fx.seconds(countdown) + "초</gray>",
                    Placeholder.component("target", target.name())));
            if (target instanceof Player cursed) {
                cursed.sendActionBar(Text.mm("<" + BRIGHT_TAG + "><b>당신의 이름이 빨간 글씨로 적혔습니다</b></"
                        + BRIGHT_TAG + "> <gray>— " + Fx.seconds(countdown) + "초 안에 작가를 찾아 때리세요</gray>"));
                cursed.playSound(cursed.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 1.0f, 0.5f);
            }
            Loop.repeat(ctx(), owner, 1, 1, countdown, this, this::end);
        }

        /** The notebook opens in front of the writer for a moment. */
        private void openBook() {
            if (!reserve(1)) {
                return;
            }
            book = Displays.item(ctx(), owner, bookSpot(), new ItemStack(BOOK_ITEM), bookPose(0.1f), BOOK_TICKS + 6);
            book.setTeleportDuration(1);
            displays.add(book);
            Displays.animateNextTick(ctx(), owner, book, bookPose(0.55f), 4);
            ItemDisplay held = book;
            ctx().runLater(owner, BOOK_TICKS, () -> {
                if (held.isValid()) {
                    Displays.shrinkAway(ctx(), owner, held, 4);
                }
            });
        }

        @Override
        public boolean test(long tick) {
            if (stage != CurseStage.COUNTING) {
                return false;
            }
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(owner.getWorld())) {
                vanish();
                return false;
            }
            long left = countdown - tick - 1;
            ItemDisplay held = book;
            if (held != null && held.isValid()) {
                Displays.moveTo(held, bookSpot());
            }
            boolean hidden = isHidden(owner, target);
            TextDisplay text = label;
            if (text != null && text.isValid()) {
                text.setViewRange(hidden ? 0f : 2.5f);
                Displays.moveTo(text, labelSpot());
                if (tick <= WRITE_TICKS || tick % 2 == 0) {
                    text.text(curseText(tick + 1, left));
                }
            }
            if (!hidden) {
                drip(tick, left);
            }
            if (tick < WRITE_TICKS && tick % 3 == 0) {
                Fx.sound(bookSpot(), Sound.BLOCK_SAND_STEP, 0.4f, 1.8f + Fx.jitter(0.1f));
            }
            if (left > 0 && left % 20 == 0) {
                Fx.sound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 0.5f);
                if (target instanceof Player cursed) {
                    cursed.playSound(cursed.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 0.8f,
                            1.0f + (countdown - left) / 40f);
                }
            }
            if (left <= 0) {
                spill();
                return false;
            }
            return true;
        }

        /** Ink drips from the name and the ink ghost's grey wisps circle closer. */
        private void drip(long tick, long left) {
            Location over = labelSpot();
            if (tick % 2 == 0) {
                Vfx.dot(Vfx.scatter(over.clone().subtract(0, 0.3, 0), 0.35), Particle.DUST, INK_DROP, false);
                Vfx.dot(Vfx.scatter(over.clone().subtract(0, 0.35, 0), 0.3), Particle.DRIPPING_OBSIDIAN_TEAR, null,
                        false);
            }
            if (tick % 3 == 0) {
                double progress = 1 - (double) left / countdown;
                double radius = 1.6 - 1.0 * progress;
                double angle = tick * 0.4;
                Location feet = target.getLocation();
                for (int i = 0; i < 2; i++) {
                    double a = angle + i * Math.PI;
                    Vfx.dot(feet.clone().add(Math.cos(a) * radius, 0.3 + progress * target.getHeight(),
                            Math.sin(a) * radius), Particle.DUST, GHOST_DUST, false);
                }
            }
        }

        /** The countdown ran out: red ink runs over the cursed, hurts it and fells it for a moment. */
        private void spill() {
            stage = CurseStage.DONE;
            curses.remove(target.getUniqueId());
            Combat.hit(ctx(), owner, target, params().num("write_damage"));
            long down = params().ticks("write_down");
            if (down > 0 && Combat.isTargetable(ctx(), owner, target)) {
                ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, down);
                ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, down);
                puddle(down);
            }
            Location body = Fx.body(target);
            if (!isHidden(owner, target)) {
                Vfx.helix(ctx(), target.getLocation().add(0, 0.05, 0), 0.55, target.getHeight(), 2, 2, 14,
                        ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_COLOR_TRANSITION, INK_GLOW, true);
                Vfx.dot(body, Particle.FLASH, INK, true);
                Vfx.cloud(ctx(), body, Particle.SQUID_INK, 14, 0.3, 0.08, null, true);
                Vfx.cloud(ctx(), body, Particle.DUST, 16, 0.4, 0, INK_DUST, true);
            }
            Fx.sound(body, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.5f, 1.3f);
            Fx.sound(body, Sound.ENTITY_SQUID_SQUIRT, 1.2f, 0.6f);
            Fx.sound(body, Sound.BLOCK_BELL_USE, 0.8f, 0.5f);
            TextDisplay text = label;
            if (text != null && text.isValid()) {
                text.text(Component.text().append(Component.text(plainName, DARK_TEXT, TextDecoration.BOLD))
                        .append(Component.newline()).append(Component.text("✎", INK_TEXT)).build());
                Displays.animate(text, Displays.centered(0, -0.3f, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0), 10);
                ctx().runLater(owner, 14, () -> {
                    if (text.isValid()) {
                        text.remove();
                    }
                });
            }
            owner.sendActionBar(Text.mm("<" + BRIGHT_TAG + "><b>✎ <target></b></" + BRIGHT_TAG + "> <gray>— 잉크가 "
                    + "번졌습니다</gray>", Placeholder.component("target", target.name())));
        }

        /** A puddle of red ink round the felled for as long as it lies there. */
        private void puddle(long ticks) {
            Loop.repeat(ctx(), owner, 2, 4, Math.max(1, ticks / 4), run -> {
                if (!target.isValid() || isHidden(owner, target)) {
                    return false;
                }
                Vfx.ring(ctx(), target.getLocation().add(0, 0.05, 0), 0.6, 12, run * 0.4, Particle.DUST,
                        run % 2 == 0 ? INK_DUST : BLACK_DUST, false);
                return true;
            });
        }

        /** The cursed found the writer: the page tears, the curse is void and the writer stumbles. */
        void tear() {
            if (stage != CurseStage.COUNTING) {
                return;
            }
            stage = CurseStage.TORN;
            curses.remove(target.getUniqueId());
            long slow = params().ticks("torn_slow");
            double amount = Math.clamp(params().num("torn_slow_amount") / 100.0, 0.0, 1.0);
            if (slow > 0 && amount > 0) {
                ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -amount, Operation.MULTIPLY_SCALAR_1, slow);
            }
            Location hand = bookSpot();
            hand.getWorld().spawnParticle(Particle.ITEM, hand, Vfx.n(ctx(), 16), 0.25, 0.25, 0.25, 0.15,
                    new ItemStack(PAPER_ITEM), true);
            shreds(hand);
            Fx.sound(hand, Sound.ENTITY_SHEEP_SHEAR, 1.2f, 1.4f);
            Fx.sound(hand, Sound.ITEM_BOOK_PAGE_TURN, 1.2f, 0.5f);
            Fx.sound(hand, Sound.ENTITY_VEX_HURT, 0.8f, 0.6f);
            popOver(owner, "<b><" + BRIGHT_TAG + ">작가를 찾았다!</" + BRIGHT_TAG + "></b>");
            TextDisplay text = label;
            if (text != null && text.isValid()) {
                text.text(Component.text(plainName, TextColor.color(0x888888), TextDecoration.STRIKETHROUGH));
                ctx().runLater(owner, 10, () -> {
                    if (text.isValid()) {
                        Displays.shrinkAway(ctx(), owner, text, 4);
                    }
                });
            }
            ItemDisplay held = book;
            if (held != null && held.isValid()) {
                held.remove();
            }
            owner.sendActionBar(Text.mm("<" + BRIGHT_TAG + "><b>작가를 찾았다!</b></" + BRIGHT_TAG + "> <gray>페이지가 "
                    + "찢어졌습니다</gray>"));
            if (target instanceof Player cursed) {
                cursed.sendActionBar(Text.mm("<" + BRIGHT_TAG + "><b>작가를 찾았다!</b></" + BRIGHT_TAG
                        + "> <gray>저주가 풀렸습니다</gray>"));
            }
        }

        /** Two shreds of the torn page flutter away from the writer's hands. */
        private void shreds(Location hand) {
            for (int i = 0; i < 2 && reserve(1); i++) {
                ItemDisplay shred = Displays.item(ctx(), owner, hand, new ItemStack(PAPER_ITEM),
                        Displays.centered(0, 0, 0, 0.35f, 0.35f, 0.35f, i * 70f, 30, i * 40f), 16);
                shred.setTeleportDuration(10);
                displays.add(shred);
                Vector away = Motion.horizontalFacing(owner.getLocation()).rotateAroundY(i == 0 ? 0.9 : -0.9);
                Location to = hand.clone().add(away.multiply(1.4)).add(0, 0.8, 0);
                ctx().runLater(owner, 1, () -> {
                    Displays.moveTo(shred, to);
                    Displays.animate(shred, Displays.centered(0, 0, 0, 0.2f, 0.2f, 0.2f, 200f, 120, 90), 10);
                });
            }
        }

        /** The cursed vanished from the match: the name has nobody to fall on. */
        private void vanish() {
            stage = CurseStage.DONE;
            curses.remove(target.getUniqueId());
            TextDisplay text = label;
            if (text != null && text.isValid()) {
                text.remove();
            }
        }

        void end() {
            if (stage == CurseStage.COUNTING) {
                vanish();
            }
        }

        private Location labelSpot() {
            return target.getLocation().add(0, target.getHeight() + CURSE_ABOVE, 0);
        }

        private Location bookSpot() {
            Location eye = owner.getEyeLocation();
            return Displays.unrotated(eye.add(Motion.horizontalFacing(eye).multiply(0.65)).subtract(0, 0.45, 0));
        }

        /** The book lying open in the hands, turned to face the way the writer faces. */
        private Transformation bookPose(float scale) {
            float yaw = owner.getLocation().getYaw();
            return Displays.centered(0, 0, 0, scale, scale, scale, yaw, -60, 0);
        }

        /** The name written so far (the first {@code letters}) over the seconds left. */
        private Component curseText(long letters, long ticksLeft) {
            int shown = (int) Math.min(plainName.length(),
                    Math.ceil(plainName.length() * Math.min(1.0, (double) letters / WRITE_TICKS)));
            Component name = Component.text(plainName.substring(0, shown), INK_TEXT, TextDecoration.BOLD);
            Component count = Component.text(Fx.seconds(Math.max(0, ticksLeft)), BRIGHT_TEXT, TextDecoration.BOLD);
            return Component.text().append(name).append(Component.newline()).append(count).build();
        }
    }

    /** A word popping up over a player's head. */
    private void popOver(Player over, String text) {
        if (!reserve(1)) {
            return;
        }
        TextDisplay word = Displays.text(ctx(), player(), over.getLocation().add(0, over.getHeight() + 0.7, 0),
                Text.mm(text), 0.6f, 20);
        word.setViewRange(2.0f);
        displays.add(word);
        Displays.animateNextTick(ctx(), player(), word, Displays.centered(0, 0.3f, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0), 5);
    }

    // ------------------------------------------------------------------ ultimate: the underworld sight

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        long now = ctx().currentTick();
        long duration = Math.max(1, params().ticks("sight_duration"));
        if (ctx().enemiesNear(owner, owner.getLocation(), params().num("sight_radius")).stream()
                .allMatch(enemy -> isHidden(owner, enemy))) {
            // Nobody to see: the gauge is kept and the ink does not run dry for nothing.
            return ActivationResult.fail("저승의 시력으로 볼 적이 없습니다");
        }
        sightUntil = now + duration;
        dryUntil = Long.MIN_VALUE / 2;
        // The ink wells up: the notebook is ready at once.
        ctx().reduceCooldown(owner, Math.max(1, params().cooldownSeconds()) * 10);
        int seen = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), params().num("sight_radius"))) {
            if (!isHidden(owner, enemy)) {
                learn(owner, enemy, false);
                seen++;
            }
        }
        summonGhost(owner, duration);
        Location head = owner.getEyeLocation();
        Vfx.dot(head, Particle.FLASH, INK, true);
        Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.1, 0), 0.5, 6, 8, Particle.DUST_COLOR_TRANSITION,
                INK_FADE, true);
        owner.getWorld().spawnParticle(Particle.SQUID_INK, head, Vfx.n(ctx(), 20), 0.5, 0.5, 0.5, 0.05, null, true);
        Fx.sound(head, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.7f, 0.8f);
        Fx.sound(head, Sound.ENTITY_GLOW_SQUID_SQUIRT, 1.0f, 0.5f);
        Fx.sound(head, Sound.ENTITY_VEX_CHARGE, 1.0f, 0.5f);
        owner.sendActionBar(Text.mm("<" + INK_TAG + "><b>◉ 저승의 시력</b></" + INK_TAG + "> <gray>" + seen
                + "명의 이름이 보입니다</gray>"));
        ctx().runLater(owner, duration, () -> runDry(owner));
        return ActivationResult.success();
    }

    /** The sight ends: the notebook runs dry and the names beyond the usual few are forgotten. */
    private void runDry(Player owner) {
        long now = ctx().currentTick();
        if (now < sightUntil) {
            return;
        }
        dryUntil = now + Math.max(0, params().ticks("dry_duration"));
        trimNames();
        Location hand = owner.getEyeLocation().subtract(0, 0.5, 0);
        Vfx.cloud(ctx(), hand, Particle.WHITE_ASH, 16, 0.3, 0.02, null, false);
        Fx.sound(hand, Sound.ITEM_BOOK_PUT, 1.0f, 0.5f);
        Fx.sound(hand, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.4f);
        owner.sendActionBar(Text.mm("<gray>✎ 잉크가 바닥났습니다 — " + Fx.seconds(dryUntil - now) + "초</gray>"));
        lastStatus = now;
    }

    /** The ink ghost looms over the owner's shoulder: a grey translucent body and head, red eyes. */
    private void summonGhost(Player owner, long duration) {
        for (BlockDisplay part : ghost) {
            if (part.isValid()) {
                part.remove();
            }
        }
        ghost.clear();
        if (!reserve(2)) {
            return;
        }
        float yaw = owner.getLocation().getYaw();
        for (int i = 0; i < 2; i++) {
            BlockDisplay part = Displays.block(ctx(), owner, ghostSpot(owner, 0), GHOST_BLOCK.createBlockData(),
                    ghostPose(i, yaw, 0.05f), duration + 10);
            part.setTeleportDuration(2);
            part.setViewRange(2.0f);
            Displays.outline(part, GHOST);
            ghost.add(part);
            displays.add(part);
            Displays.animateNextTick(ctx(), owner, part, ghostPose(i, yaw, 1f), 8);
        }
    }

    private void updateGhost(Player owner, long tick) {
        if (ghost.isEmpty()) {
            return;
        }
        if (!inSight()) {
            for (BlockDisplay part : ghost) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 8);
                }
            }
            ghost.clear();
            return;
        }
        float yaw = owner.getLocation().getYaw();
        Location spot = ghostSpot(owner, tick);
        for (int i = 0; i < ghost.size(); i++) {
            BlockDisplay part = ghost.get(i);
            if (part.isValid()) {
                Displays.moveTo(part, spot);
                if (tick % 2 == 0) {
                    Displays.animate(part, ghostPose(i, yaw, 1f), 2);
                }
            }
        }
        if (tick % 2 == 0) {
            Vector front = Motion.horizontalFacing(owner.getLocation()).multiply(0.26);
            Vector side = new Vector(-front.getZ(), 0, front.getX()).normalize().multiply(0.1);
            Location eyes = spot.clone().add(0, GHOST_HEAD_Y, 0).add(front);
            Vfx.dot(eyes.clone().add(side), Particle.DUST, GHOST_EYE, false);
            Vfx.dot(eyes.clone().subtract(side), Particle.DUST, GHOST_EYE, false);
        }
        if (tick % 5 == 0) {
            Vfx.dot(Vfx.scatter(spot, 0.4).add(0, 0.4, 0), Particle.SQUID_INK, null, false);
        }
    }

    /** Behind the owner and a little above, swaying. */
    private static Location ghostSpot(Player owner, long tick) {
        Vector back = Motion.horizontalFacing(owner.getLocation()).multiply(-0.75);
        return Displays.unrotated(owner.getLocation().add(back).add(0, 0.6 + Math.sin(tick * 0.15) * 0.08, 0));
    }

    /** The ghost's parts: a tall body, then a head above it, turned with the owner. */
    private static Transformation ghostPose(int part, float yaw, float scale) {
        return part == 0
                ? Displays.transform(0, 1.0f * scale, 0, 0.7f * scale, 1.5f * scale, 0.45f * scale, yaw, 0, 0)
                : Displays.transform(0, GHOST_HEAD_Y * scale, 0, 0.55f * scale, 0.55f * scale, 0.55f * scale, yaw,
                0, 0);
    }

    // ------------------------------------------------------------------ helpers

    /** The entity's name as plain text, without legacy colour codes, at most a few letters. */
    private static String plain(LivingEntity entity) {
        String name = PlainTextComponentSerializer.plainText().serialize(entity.name()).replaceAll("§.", "").strip();
        if (name.isEmpty()) {
            name = "?";
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    /** Reserves room for {@code count} more displays under {@link #MAX_DISPLAYS}; false when there is none. */
    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }
}
