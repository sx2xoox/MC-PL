package io.github.sx2xoox.speedrunbot.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.BlockState;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * A real {@link ServerPlayerEntity} without a client. A normal server player is moved by its client's packets; this
 * one runs the same player physics on the server ({@code playerTick} -> {@code LivingEntity#tickMovement} -> travel)
 * from the inputs the bot sets each tick (forward/sideways speed, jumping, sprinting, sneaking, yaw/pitch). Mining,
 * using items, damage, hunger, inventory and portals are the vanilla code paths. Idea from Carpet mod's
 * EntityPlayerMPFake (MIT, gnembon); written anew for 1.16.1 Yarn names.
 */
public final class FakePlayer extends ServerPlayerEntity {
    private DamageSource deathSource;

    private FakePlayer(MinecraftServer server, ServerWorld world, GameProfile profile) {
        super(server, world, profile, new ServerPlayerInteractionManager(world));
    }

    public static FakePlayer join(MinecraftServer server, String name) {
        ServerWorld world = server.getOverworld();
        GameProfile profile = new GameProfile(PlayerEntity.getOfflinePlayerUuid(name), name);
        FakePlayer p = new FakePlayer(server, world, profile);
        server.getPlayerManager().onPlayerConnect(new FakeConnection(), p);
        p.stepHeight = 0.6f; // the server sets 1.0 for players because clients send their own positions
        return p;
    }

    @Override
    public void tick() {
        if (getServer().getTicks() % 5 == 0) {
            // what the network handler does for a moving client: keep the chunk view around the player
            networkHandler.syncWithPlayerPosition();
            getServerWorld().getChunkManager().updateCameraPosition(this);
        }
        super.tick();
        playerTick();
    }

    /** Server players skip fall damage because the client reports falls; this player reports its own. */
    @Override
    protected void fall(double heightDifference, boolean onGround, BlockState landedState, BlockPos landedPosition) {
        handleFall(heightDifference, onGround);
    }

    @Override
    public void onDeath(DamageSource source) {
        deathSource = source;
        super.onDeath(source);
    }

    public DamageSource deathSource() {
        return deathSource;
    }
}
