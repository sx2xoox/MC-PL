package io.github.sx2xoox.speedrunbot.mixin;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DataFixerBuilder;
import net.minecraft.datafixer.Schemas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Harness speed only: the data fixer's rule optimisation (tens of CPU seconds on the worker threads at every server
 * start, for upgrading old worlds) is skipped; every run makes a fresh world, so nothing is ever upgraded. Same idea as
 * the LazyDFU mod. No effect on the game itself.
 */
@Mixin(Schemas.class)
public abstract class SchemasMixin {
    @Redirect(method = "create", at = @At(value = "INVOKE",
            target = "Lcom/mojang/datafixers/DataFixerBuilder;build(Ljava/util/concurrent/Executor;)Lcom/mojang/datafixers/DataFixer;"))
    private static DataFixer srb$noOptimisation(DataFixerBuilder builder, java.util.concurrent.Executor executor) {
        return builder.build(task -> { });
    }
}
