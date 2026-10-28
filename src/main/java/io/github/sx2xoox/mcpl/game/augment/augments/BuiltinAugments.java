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
        // Group A — 자원 · 파밍 · 생존
        registry.register(MinerTouchAugment.INFO, MinerTouchAugment::new);
        registry.register(LumberjackAugment.INFO, LumberjackAugment::new);
        registry.register(AutoSmeltAugment.INFO, AutoSmeltAugment::new);
        registry.register(FeastAugment.INFO, FeastAugment::new);
        registry.register(LuckyPickAugment.INFO, LuckyPickAugment::new);
        registry.register(RationsAugment.INFO, RationsAugment::new);
        registry.register(NightEyesAugment.INFO, NightEyesAugment::new);
        registry.register(TreasureSenseAugment.INFO, TreasureSenseAugment::new);
        registry.register(SmithBlessingAugment.INFO, SmithBlessingAugment::new);
        registry.register(XpSpringAugment.INFO, XpSpringAugment::new);
        registry.register(EnchantKitAugment.INFO, EnchantKitAugment::new);
        registry.register(PotionBagAugment.INFO, PotionBagAugment::new);
        registry.register(BeastFriendAugment.INFO, BeastFriendAugment::new);
        registry.register(NetheriteScrapAugment.INFO, NetheriteScrapAugment::new);
        registry.register(MidasPickAugment.INFO, MidasPickAugment::new);
        registry.register(RegenHeartAugment.INFO, RegenHeartAugment::new);
        registry.register(EarthBlessingAugment.INFO, EarthBlessingAugment::new);
    }
}
