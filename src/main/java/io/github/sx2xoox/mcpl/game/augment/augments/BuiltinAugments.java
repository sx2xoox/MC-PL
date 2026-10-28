package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentRegistry;

/** Registers the built-in augments. Add new ones here (and a docs entry, see {@code AugmentDocsTest}). */
public final class BuiltinAugments {

    private BuiltinAugments() {
    }

    public static void registerAll(AugmentRegistry registry) {
        // 실버
        registry.register(IronBodyAugment.INFO, IronBodyAugment::new);
        registry.register(IronBundleAugment.INFO, IronBundleAugment::new);
        registry.register(FeatherFallAugment.INFO, FeatherFallAugment::new);
        registry.register(GaleAugment.INFO, GaleAugment::new);
        // 골드
        registry.register(BloodthirstAugment.INFO, BloodthirstAugment::new);
        registry.register(TripleShotAugment.INFO, TripleShotAugment::new);
        registry.register(DoubleJumpAugment.INFO, DoubleJumpAugment::new);
        registry.register(BerserkerPactAugment.INFO, BerserkerPactAugment::new);
        // 프리즘
        registry.register(PhoenixFeatherAugment.INFO, PhoenixFeatherAugment::new);
        registry.register(DiamondVeinAugment.INFO, DiamondVeinAugment::new);
    }
}
