package io.github.sx2xoox.speedrunbot.mixin;

import io.github.sx2xoox.speedrunbot.BotRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/**
 * Two hooks: the bot runs once per server tick (after the worlds ticked), and in "warp" mode the server does not
 * sleep between ticks. Warp only removes idle wall-clock time; every tick still runs in full, so game behaviour and
 * the tick-based IGT are unchanged (like Carpet's /tick warp).
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Unique
    private long srb$warpDeadline;

    @Inject(method = "tick", at = @At("TAIL"))
    private void srb$afterTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        BotRuntime.onServerTick((MinecraftServer) (Object) this);
        srb$warpDeadline = Util.getMeasuringTimeMs() + BotRuntime.warpTaskBudgetMs();
    }

    @Inject(method = "shouldKeepTicking", at = @At("HEAD"), cancellable = true)
    private void srb$warp(CallbackInfoReturnable<Boolean> cir) {
        if (BotRuntime.warp()) {
            cir.setReturnValue(Util.getMeasuringTimeMs() < srb$warpDeadline);
        }
    }
}
