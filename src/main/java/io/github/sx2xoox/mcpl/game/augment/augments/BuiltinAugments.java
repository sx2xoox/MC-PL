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
