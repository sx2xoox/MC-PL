package io.github.sx2xoox.mcpl.game.superpower.abilities;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;

/** Registers every built-in superpower. Add new abilities here. */
public final class BuiltinAbilities {

    private BuiltinAbilities() {
    }

    public static void registerAll(AbilityRegistry registry) {
        registry.register(PyroAbility.INFO, PyroAbility::new);
        registry.register(ThunderAbility.INFO, ThunderAbility::new);
        registry.register(FrostAbility.INFO, FrostAbility::new);
        registry.register(AeroAbility.INFO, AeroAbility::new);
        registry.register(BlinkAbility.INFO, BlinkAbility::new);
        registry.register(ShadowAbility.INFO, ShadowAbility::new);
        registry.register(GrappleAbility.INFO, GrappleAbility::new);
        registry.register(ChronoAbility.INFO, ChronoAbility::new);
        registry.register(BerserkerAbility.INFO, BerserkerAbility::new);
        registry.register(TitanAbility.INFO, TitanAbility::new);
        registry.register(GuardianAbility.INFO, GuardianAbility::new);
        registry.register(VampireAbility.INFO, VampireAbility::new);
        registry.register(GravityAbility.INFO, GravityAbility::new);
        registry.register(EarthAbility.INFO, EarthAbility::new);
        registry.register(VenomAbility.INFO, VenomAbility::new);
        registry.register(MirrorAbility.INFO, MirrorAbility::new);
        registry.register(ArcherAbility.INFO, ArcherAbility::new);
        registry.register(BomberAbility.INFO, BomberAbility::new);
        registry.register(SummonerAbility.INFO, SummonerAbility::new);
        registry.register(HealerAbility.INFO, HealerAbility::new);
        registry.register(PixieAbility.INFO, PixieAbility::new);
        registry.register(PhoenixAbility.INFO, PhoenixAbility::new);
        registry.register(IllusionistAbility.INFO, IllusionistAbility::new);
        registry.register(GamblerAbility.INFO, GamblerAbility::new);
    }
}
