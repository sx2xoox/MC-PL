package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/**
 * The graveyard district (north, 240°..300°): a haunted cemetery around a ruined temple.
 *
 * <p>From the ring road a broken cemetery wall and a lychgate open onto a paved processional path, past a statue
 * plaza, up the temple's grand stairs. The temple stands on a podium in the middle of the district (see
 * {@link GraveyardTemple}); rows of tombstones, fenced family plots and dead trees fill the wings, with a mausoleum
 * and a barrow mound in the west, a sunken catacomb under a ruined chapel and a grove of dead trees on a low knoll in
 * the east (see {@link GraveyardCrypts}, {@link GraveyardChapel}). The edges along the roads stay at ground level and
 * open.
 */
final class GraveyardDistrict implements MapZone {

    @Override
    public String name() {
        return "graveyard";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isDistrict(CitadelLayout.District.GRAVEYARD, x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        GraveyardBuilder b = new GraveyardBuilder(canvas, name());
        GraveyardTemple temple = new GraveyardTemple(b);
        GraveyardCrypts crypts = new GraveyardCrypts(b);
        GraveyardGraves graves = new GraveyardGraves(b);
        GraveyardGround ground = new GraveyardGround(b);
        GraveyardChapel chapel = new GraveyardChapel(b);

        // Plan first: structures claim their columns, then the paths, then the terrain is shaped around them.
        temple.reserve();
        crypts.reserve();
        chapel.reserve();
        graves.reserve();
        ground.paths();
        crypts.terrain();
        ground.rolls();
        ground.texture();

        // Build.
        temple.build();
        crypts.build();
        chapel.build();
        new GraveyardTrees(b).build();
        graves.build();
        ground.cover();
    }
}
