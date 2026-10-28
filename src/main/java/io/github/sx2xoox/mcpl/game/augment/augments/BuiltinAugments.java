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
        registerCombat(registry);
    }

    // Group B: combat
    private static void registerCombat(AugmentRegistry registry) {
        // 실버
        registry.register(SharpEdgeAugment.INFO, SharpEdgeAugment::new);
        registry.register(ThickSkinAugment.INFO, ThickSkinAugment::new);
        registry.register(QuiverAugment.INFO, QuiverAugment::new);
        registry.register(ShieldMasterAugment.INFO, ShieldMasterAugment::new);
        registry.register(FireBladeAugment.INFO, FireBladeAugment::new);
        registry.register(VenomAugment.INFO, VenomAugment::new);
        // 골드
        registry.register(ExecutionerAugment.INFO, ExecutionerAugment::new);
        registry.register(ComboAugment.INFO, ComboAugment::new);
        registry.register(SniperAugment.INFO, SniperAugment::new);
        registry.register(ThornsAugment.INFO, ThornsAugment::new);
        registry.register(HeavyHitterAugment.INFO, HeavyHitterAugment::new);
        registry.register(FrostArrowAugment.INFO, FrostArrowAugment::new);
        registry.register(BlastArrowAugment.INFO, BlastArrowAugment::new);
        // 프리즘
        registry.register(GiantAugment.INFO, GiantAugment::new);
        registry.register(ShadowStrikeAugment.INFO, ShadowStrikeAugment::new);
        registry.register(WarGodAugment.INFO, WarGodAugment::new);
        registry.register(FortressAugment.INFO, FortressAugment::new);
        // Group C: 이동 · 정보 · 트릭 · 도박
        registry.register(SwimmerAugment.INFO, SwimmerAugment::new);
        registry.register(HighJumpAugment.INFO, HighJumpAugment::new);
        registry.register(HunterCompassAugment.INFO, HunterCompassAugment::new);
        registry.register(FootstepsAugment.INFO, FootstepsAugment::new);
        registry.register(PearlsAugment.INFO, PearlsAugment::new);
        registry.register(BlinkAugment.INFO, BlinkAugment::new);
        registry.register(GamblerAugment.INFO, GamblerAugment::new);
        registry.register(StillCloakAugment.INFO, StillCloakAugment::new);
        registry.register(LuckyCoinAugment.INFO, LuckyCoinAugment::new);
        registry.register(SprinterAugment.INFO, SprinterAugment::new);
        registry.register(CobwebTrapAugment.INFO, CobwebTrapAugment::new);
        registry.register(RewindAugment.INFO, RewindAugment::new);
        registry.register(ClairvoyanceAugment.INFO, ClairvoyanceAugment::new);
        registry.register(WingsAugment.INFO, WingsAugment::new);
        registry.register(CollectorAugment.INFO, CollectorAugment::new);
        registry.register(VengefulAugment.INFO, VengefulAugment::new);
    }
}
