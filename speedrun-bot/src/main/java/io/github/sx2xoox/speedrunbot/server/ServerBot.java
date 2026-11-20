package io.github.sx2xoox.speedrunbot.server;

import io.github.sx2xoox.speedrunbot.brain.BotInputs;
import io.github.sx2xoox.speedrunbot.brain.BotSenses;
import io.github.sx2xoox.speedrunbot.core.InputLimits;
import io.github.sx2xoox.speedrunbot.mixin.SlotAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.screen.AbstractRecipeScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Server implementation of the brain's two interfaces around a {@link FakePlayer}. Inputs go through the same server
 * code a client's packets reach (movement fields + player physics, PlayerActionC2SPacket actions for mining,
 * interactBlock / interactItem for right clicks, screen-handler slot clicks, recipe book fill). Mining follows the
 * vanilla client's progress loop: the block breaks when the client-side sum of per-tick deltas reaches 1, then a
 * 5-tick cooldown.
 */
public final class ServerBot implements BotInputs, BotSenses {
    private final MinecraftServer server;
    private final FakePlayer player;
    private final BlockPos.Mutable mpos = new BlockPos.Mutable();

    private boolean breaking;
    private BlockPos breakPos;
    private Direction breakSide;
    private float breakProgress;
    private int breakCooldown;
    private boolean invOpen;
    private WorldChunk cachedChunk;
    private ServerWorld cachedWorld;

    public ServerBot(MinecraftServer server, FakePlayer player) {
        this.server = server;
        this.player = player;
    }

    public FakePlayer player() {
        return player;
    }

    private ServerWorld world() {
        return player.getServerWorld();
    }

    // ================================================================== BotInputs

    @Override
    public void setMovement(float forward, float strafe, boolean jump, boolean sprint, boolean sneak) {
        float k = sneak ? 0.3f : 1f;
        player.forwardSpeed = forward * k;
        player.sidewaysSpeed = strafe * k;
        player.setJumping(jump);
        player.setSneaking(sneak);
        player.setSprinting(sprint);
        if (sneak && player.isTouchingWater() && !player.hasVehicle()) {
            player.sinkInWater(); // ClientPlayerEntity#tickMovement: shift in water pulls the player down
        }
        if (player.getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity) {
            // ClientPlayerEntity#tickRiding: A = left, D = right, W = forward, S = back
            ((BoatControl) player.getVehicle()).srb$setInputs(strafe > 0.1f, strafe < -0.1f, forward > 0.1f, forward < -0.1f);
        }
    }

    @Override
    public void useEntity() {
        Entity e = targetedEntity();
        if (e != null) {
            ActionResult r = player.interact(e, Hand.MAIN_HAND);
            if (r.isAccepted() && r.shouldSwingHand()) {
                player.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    /** The entity under the crosshair within entity reach (any targetable entity: the client's GameRenderer rule). */
    private Entity targetedEntity() {
        HitResult block = player.rayTrace(InputLimits.BLOCK_REACH, 1f, false);
        Vec3d eye = eyePos();
        Vec3d look = player.getRotationVec(1f);
        double maxD = block.getType() == HitResult.Type.MISS ? InputLimits.ENTITY_REACH
                : Math.min(InputLimits.ENTITY_REACH, block.getPos().distanceTo(eye));
        Vec3d end = eye.add(look.multiply(maxD));
        Box box = player.getBoundingBox().stretch(look.multiply(maxD)).expand(1.0);
        Entity hit = null;
        double hitD = maxD;
        for (Entity x : world().getEntities(player, box, en -> !en.isSpectator() && en.collides() && en != player.getVehicle())) {
            Box eb = x.getBoundingBox().expand(x.getTargetingMargin());
            Optional<Vec3d> o = eb.rayTrace(eye, end);
            if (o.isPresent() && o.get().distanceTo(eye) < hitD) {
                hitD = o.get().distanceTo(eye);
                hit = x;
            }
        }
        return hit;
    }

    /**
     * Warp (ticks without sleeping) can outrun the asynchronous chunk generation when the bot travels fast (a boat at
     * 8 blocks/s): entities in chunks that are not entity-ticking yet freeze. A client at 20 TPS never sees that, so
     * while the chunks two seconds ahead are not ticking the server runs at normal speed (IGT is in ticks either way).
     */
    public boolean worldReadyAhead() {
        if (!(player.getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity)) {
            return true;
        }
        Entity boat = player.getVehicle();
        Vec3d ahead = boat.getPos().add(boat.getVelocity().multiply(40));
        for (Vec3d q : new Vec3d[]{boat.getPos(), ahead}) {
            int cx = net.minecraft.util.math.MathHelper.floor(q.x) >> 4, cz = net.minecraft.util.math.MathHelper.floor(q.z) >> 4;
            if (!world().getChunkManager().shouldTickChunk(new net.minecraft.util.math.ChunkPos(cx, cz))) {
                return false;
            }
        }
        return world().getChunkManager().shouldTickEntity(boat);
    }

    @Override
    public boolean inBoat() {
        return player.getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity;
    }

    @Override
    public float boatYaw() {
        return inBoat() ? player.getVehicle().yaw : Float.NaN;
    }

    @Override
    public void setLook(float yaw, float pitch) {
        player.yaw = yaw;
        player.pitch = Math.max(-90f, Math.min(90f, pitch));
        player.setHeadYaw(yaw);
    }

    @Override
    public void setAttackHeld(boolean held) {
        if (breakCooldown > 0) {
            breakCooldown--;
        }
        HitResult h = crosshair();
        if (!held || !(h instanceof BlockHitResult) || h.getType() != HitResult.Type.BLOCK) {
            abortBreaking();
            return;
        }
        BlockHitResult b = (BlockHitResult) h;
        BlockPos p = b.getBlockPos();
        if (breakCooldown > 0) {
            return;
        }
        int height = server.getWorldHeight();
        if (!breaking || !p.equals(breakPos)) {
            abortBreaking();
            BlockState st = world().getBlockState(p);
            float delta = st.calcBlockBreakingDelta(player, world(), p);
            player.interactionManager.processBlockBreakingAction(p, PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, b.getSide(), height);
            player.swingHand(Hand.MAIN_HAND);
            if (delta < 1f) {
                breaking = true;
                breakPos = p.toImmutable();
                breakSide = b.getSide();
                breakProgress = 0f;
            }
            return;
        }
        BlockState st = world().getBlockState(p);
        if (st.isAir()) {
            breaking = false;
            return;
        }
        breakProgress += st.calcBlockBreakingDelta(player, world(), p);
        player.swingHand(Hand.MAIN_HAND);
        if (breakProgress >= 1f) {
            player.interactionManager.processBlockBreakingAction(p, PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, breakSide, height);
            breaking = false;
            breakProgress = 0f;
            breakCooldown = 5;
        }
    }

    private void abortBreaking() {
        if (breaking) {
            player.interactionManager.processBlockBreakingAction(breakPos, PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, breakSide,
                    server.getWorldHeight());
            breaking = false;
            breakProgress = 0f;
        }
    }

    @Override
    public void attackEntity() {
        HitResult h = crosshair();
        if (h instanceof EntityHitResult) {
            player.attack(((EntityHitResult) h).getEntity());
            player.swingHand(Hand.MAIN_HAND);
        }
        player.resetLastAttackedTicks();
    }

    @Override
    public void use() {
        HitResult h = crosshair();
        for (Hand hand : Hand.values()) {
            ItemStack stack = player.getStackInHand(hand);
            if (h instanceof BlockHitResult && h.getType() == HitResult.Type.BLOCK) {
                int before = stack.getCount();
                ActionResult r = player.interactionManager.interactBlock(player, world(), stack, hand, (BlockHitResult) h);
                if (Boolean.getBoolean("srb.debugUse")) {
                    System.out.println("[srb] useBlock " + stack + " " + hand + " on " + world().getBlockState(((BlockHitResult) h).getBlockPos()) + " -> " + r);
                }
                if (r.isAccepted()) {
                    if (r.shouldSwingHand()) {
                        player.swingHand(hand);
                    }
                    return;
                }
                if (r == ActionResult.FAIL) {
                    return;
                }
                if (stack.isEmpty() || stack.getCount() != before) {
                    return;
                }
            }
            if (!stack.isEmpty()) {
                ActionResult r = player.interactionManager.interactItem(player, world(), stack, hand);
                if (Boolean.getBoolean("srb.debugUse")) {
                    System.out.println("[srb] use " + stack + " " + hand + " -> " + r + " xhair=" + (h instanceof BlockHitResult ? ((BlockHitResult) h).getBlockPos() + "/" + ((BlockHitResult) h).getSide() : h.getType()));
                }
                if (r.isAccepted()) {
                    if (r.shouldSwingHand()) {
                        player.swingHand(hand);
                    }
                    return;
                }
            }
        }
    }

    @Override
    public void selectHotbar(int slot) {
        if (PlayerInventory.isValidHotbarIndex(slot)) {
            if (player.inventory.selectedSlot != slot) {
                abortBreaking();
            }
            player.inventory.selectedSlot = slot;
            player.updateLastActionTime();
        }
    }

    @Override
    public void openInventory() {
        if (player.currentScreenHandler == player.playerScreenHandler) {
            invOpen = true;
        }
    }

    @Override
    public void clickSlot(int slotId, int button, SlotActionType action) {
        ScreenHandler h = player.currentScreenHandler;
        if (slotId < -1 || slotId >= h.slots.size()) {
            return;
        }
        ItemStack r = h.onSlotClick(slotId, button, action, player);
        h.sendContentUpdates();
        if (Boolean.getBoolean("srb.debugGui")) {
            System.out.println("[srb] click " + slotId + " " + button + " " + action + " -> " + r + " screen=" + h.getClass().getSimpleName());
        }
    }

    @Override
    public void craftRecipe(Identifier recipe, boolean craftAll) {
        ScreenHandler h = player.currentScreenHandler;
        if (!(h instanceof AbstractRecipeScreenHandler)) {
            return;
        }
        Optional<? extends Recipe<?>> r = server.getRecipeManager().get(recipe);
        r.ifPresent(value -> ((AbstractRecipeScreenHandler<?>) h).fillInputSlots(craftAll, value, player));
        if (Boolean.getBoolean("srb.debugGui")) {
            System.out.println("[srb] recipe " + recipe + " all=" + craftAll + " present=" + r.isPresent() + " result=" + h.getSlot(0).getStack());
        }
        h.sendContentUpdates();
    }

    @Override
    public void closeScreen() {
        if (Boolean.getBoolean("srb.debugGui")) {
            System.out.println("[srb] close " + player.currentScreenHandler.getClass().getSimpleName());

        }
        invOpen = false;
        player.closeHandledScreen();
    }

    // ================================================================== BotSenses

    @Override
    public long gameTick() {
        return server.getTicks();
    }

    @Override
    public Vec3d pos() {
        return player.getPos();
    }

    @Override
    public Vec3d eyePos() {
        return player.getCameraPosVec(1f);
    }

    @Override
    public Vec3d velocity() {
        return player.getVelocity();
    }

    @Override
    public float yaw() {
        return player.yaw;
    }

    @Override
    public float pitch() {
        return player.pitch;
    }

    @Override
    public boolean onGround() {
        return player.isOnGround();
    }

    @Override
    public boolean inWater() {
        return player.isTouchingWater();
    }

    @Override
    public boolean inLava() {
        return player.isInLava();
    }

    @Override
    public io.github.sx2xoox.speedrunbot.core.TreasureMap.Reading heldMap() {
        ItemStack st = player.getMainHandStack();
        if (st.getItem() != net.minecraft.item.Items.FILLED_MAP) {
            return null;
        }
        net.minecraft.item.map.MapState ms = net.minecraft.item.FilledMapItem.getMapState(st, world());
        if (ms == null) {
            return null;
        }
        io.github.sx2xoox.speedrunbot.core.TreasureMap.Reading r = new io.github.sx2xoox.speedrunbot.core.TreasureMap.Reading(ms.scale);
        for (net.minecraft.item.map.MapIcon icon : ms.icons.values()) {
            r.add(icon.getType().name(), icon.getX(), icon.getZ());
        }
        return r;
    }

    @Override
    public int air() {
        return player.getAir();
    }

    @Override
    public boolean submerged() {
        return player.isSubmergedInWater();
    }

    @Override
    public boolean onFire() {
        return player.isOnFire();
    }

    @Override
    public boolean horizontalCollision() {
        return player.horizontalCollision;
    }

    @Override
    public float health() {
        return player.getHealth();
    }

    @Override
    public int food() {
        return player.getHungerManager().getFoodLevel();
    }

    @Override
    public boolean alive() {
        return player.isAlive() && !player.removed;
    }

    @Override
    public String dimension() {
        return world().getRegistryKey().getValue().getPath();
    }

    @Override
    public PlayerInventory inventory() {
        return player.inventory;
    }

    @Override
    public ScreenHandler screen() {
        return player.currentScreenHandler;
    }

    @Override
    public boolean screenOpen() {
        return invOpen || player.currentScreenHandler != player.playerScreenHandler;
    }

    @Override
    public int playerInvIndexOfScreenSlot(int screenSlot) {
        ScreenHandler h = player.currentScreenHandler;
        if (screenSlot < 0 || screenSlot >= h.slots.size()) {
            return -1;
        }
        Slot s = h.slots.get(screenSlot);
        if (s.inventory != player.inventory) {
            return -1;
        }
        return ((SlotAccessor) s).srb$getIndex();
    }

    @Override
    public Collection<Recipe<?>> knownCraftingRecipes() {
        List<Recipe<?>> out = new ArrayList<>();
        for (Recipe<?> r : server.getRecipeManager().values()) {
            if (r.getType() == RecipeType.CRAFTING && player.getRecipeBook().contains(r)) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public HitResult crosshair() {
        double reach = InputLimits.BLOCK_REACH;
        HitResult block = player.rayTrace(reach, 1f, false);
        Vec3d eye = eyePos();
        Vec3d look = player.getRotationVec(1f);
        double ereach = InputLimits.ENTITY_REACH;
        double maxD = block.getType() == HitResult.Type.MISS ? ereach : Math.min(ereach, block.getPos().distanceTo(eye));
        Vec3d end = eye.add(look.multiply(maxD));
        Box box = player.getBoundingBox().stretch(look.multiply(maxD)).expand(1.0);
        Entity hit = null;
        double hitD = maxD;
        Vec3d hitPos = null;
        for (Entity x : world().getEntities(player, box, en -> !en.isSpectator() && en.isAlive() && en instanceof net.minecraft.entity.LivingEntity)) {
            Box eb = x.getBoundingBox().expand(x.getTargetingMargin());
            Optional<Vec3d> o = eb.rayTrace(eye, end);
            if (eb.contains(eye)) {
                return new EntityHitResult(x, eye);
            }
            if (o.isPresent() && o.get().distanceTo(eye) < hitD) {
                hitD = o.get().distanceTo(eye);
                hit = x;
                hitPos = o.get();
            }
        }
        if (hit != null) {
            return new EntityHitResult(hit, hitPos);
        }
        return block;
    }

    @Override
    public HitResult fluidCrosshair() {
        Vec3d eye = eyePos();
        Vec3d end = eye.add(player.getRotationVec(1f).multiply(InputLimits.BLOCK_REACH));
        return world().rayTrace(new net.minecraft.world.RayTraceContext(eye, end, net.minecraft.world.RayTraceContext.ShapeType.OUTLINE,
                net.minecraft.world.RayTraceContext.FluidHandling.SOURCE_ONLY, player));
    }

    @Override
    public float breakingDelta(BlockState state, BlockPos pos) {
        return state.calcBlockBreakingDelta(player, world(), pos);
    }

    @Override
    public List<Entity> entitiesNear(Box box) {
        return world().getEntities(player, box, x -> true);
    }

    @Override
    public boolean inPortal() {
        return world().getBlockState(player.getBlockPos()).getBlock() == Blocks.NETHER_PORTAL;
    }

    // ================================================================== BlockSource

    @Override
    public BlockState blockAt(int x, int y, int z) {
        if (y < 0 || y > 255) {
            return null;
        }
        ServerWorld w = world();
        int cx = x >> 4, cz = z >> 4;
        WorldChunk c = cachedChunk;
        if (w != cachedWorld || c == null || c.getPos().x != cx || c.getPos().z != cz) {
            c = w.getChunkManager().getWorldChunk(cx, cz);
            cachedWorld = w;
            cachedChunk = c;
            if (c == null) {
                return null;
            }
        }
        mpos.set(x, y, z);
        return c.getBlockState(mpos);
    }

    /** Call at the start of a tick: chunk objects may have been unloaded. */
    public void newTick() {
        cachedChunk = null;
        heard = null;
    }

    private static final boolean HEARING = !"false".equals(System.getProperty("srb.hearing"));
    private final java.util.Random earRandom = new java.util.Random(0x5EED);
    private List<io.github.sx2xoox.speedrunbot.core.Hearing.Heard> heard;

    @Override
    public List<io.github.sx2xoox.speedrunbot.core.Hearing.Heard> heardLava() {
        if (heard != null) {
            return heard;
        }
        heard = new java.util.ArrayList<>();
        if (!HEARING) {
            return heard;
        }
        BlockPos p = player.getBlockPos();
        Vec3d e = eyePos();
        // the sounds the vanilla client would play this tick (its random display ticks), heard from the ears
        for (io.github.sx2xoox.speedrunbot.core.Hearing.Sound snd : io.github.sx2xoox.speedrunbot.core.Hearing.lavaSounds(
                p.getX(), p.getY(), p.getZ(), e.x, e.y, e.z, (x, y, z) -> {
                    BlockState st = blockAt(x, y, z);
                    BlockState up = blockAt(x, y + 1, z);
                    return st != null && up != null && st.getFluidState().isIn(FluidTags.LAVA) && up.isAir();
                }, earRandom)) {
            heard.add(io.github.sx2xoox.speedrunbot.core.Hearing.perceive(snd, e.x, e.y, e.z, earRandom));
        }
        return heard;
    }

    @Override
    public boolean opaque(BlockState state, int x, int y, int z) {
        mpos.set(x, y, z);
        return state.isOpaqueFullCube(world(), mpos);
    }

    public boolean submergedInWater() {
        return player.isSubmergedIn(FluidTags.WATER);
    }

    @Override
    public int pieChests(io.github.sx2xoox.speedrunbot.core.PieChart.View view) {
        // what the client's block entity renderer would draw: loaded chests in the frustum within render distance
        Vec3d e = eyePos();
        int n = 0;
        for (net.minecraft.block.entity.BlockEntity be : player.getServerWorld().blockEntities) {
            if (!(be instanceof net.minecraft.block.entity.ChestBlockEntity) && !(be instanceof net.minecraft.block.entity.EnderChestBlockEntity)) {
                continue;
            }
            BlockPos q = be.getPos();
            if (io.github.sx2xoox.speedrunbot.core.PieChart.blockInFrustum(view, e.x, e.y, e.z, player.yaw, player.pitch, q.getX(), q.getY(), q.getZ())) {
                n++;
            }
        }
        return n;
    }
}
