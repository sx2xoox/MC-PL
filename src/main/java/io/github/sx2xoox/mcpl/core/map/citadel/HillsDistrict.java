package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/**
 * The hills district (east-northeast, 300°..360°): rolling grassy hills on the frontier of the citadel. A wood and
 * stone watchtower inside a broken hillfort ring crowns the tallest hill, a ruined windmill stands on the next one
 * along the ridge, a palisaded army camp sits in the hollow below them, a ruined barn with a hayloft holds the meadow
 * by the east road, and a signal beacon ruin watches that road from a knoll. Rocky outcrops, log breastworks, dry
 * stone walls, haystacks and bushes give cover on every slope, so the crests are worth taking but never safe.
 *
 * <p>The build is split into helpers sharing one {@link HillsSite}: {@link HillsTerrain} shapes the ground,
 * {@link HillsPaths} lays the footpaths, {@link HillsWatchtower}, {@link HillsWindmill}, {@link HillsCamp} and
 * {@link HillsBarn} build the landmarks and {@link HillsDecor} adds the cover and the greenery.
 */
final class HillsDistrict implements MapZone {

    /** The watchtower on the tallest hill. */
    static final int[] TOWER = CitadelLayout.polar(74, 335);
    /** The ruined windmill on the second hill, north of the tower along the ridge. */
    static final int[] WINDMILL = CitadelLayout.polar(82, 316);
    /** The army camp in the hollow between the hills and the north-east road. */
    static final int[] CAMP = CitadelLayout.polar(58, 315);
    /** The signal beacon ruin on a knoll above the east road. */
    static final int[] BEACON = CitadelLayout.polar(83, 350);
    /** A low grassy knoll facing the keep. */
    static final int[] KNOLL = CitadelLayout.polar(48, 338);
    /** A shoulder of the knoll towards the camp. */
    static final int[] SPUR = CitadelLayout.polar(50, 328);
    /** The ruined barn in the meadow by the east road. */
    static final int[] BARN = {59, -12};
    /** Rocky crags on the outer flank between the tower hill and the windmill hill. */
    static final int[] CRAGS = CitadelLayout.polar(86, 326);

    /** Height of the watchtower hill's flat top above the base ground. */
    static final int TOWER_HILL = 8;
    /** Height of the windmill hill's flat top above the base ground. */
    static final int WINDMILL_HILL = 7;
    /** Radius of the camp's palisade. */
    static final double CAMP_RADIUS = 10;

    @Override
    public String name() {
        return "hills";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isDistrict(CitadelLayout.District.HILLS, x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        HillsSite site = new HillsSite(canvas, canvas.random(name()));
        new HillsTerrain(site).build();
        // Keep the landmarks' ground free of paths and cover.
        site.markDisk(TOWER[0], TOWER[1], 8.5, HillsSite.Use.STRUCTURE);
        site.markDisk(WINDMILL[0], WINDMILL[1], 7.5, HillsSite.Use.STRUCTURE);
        site.markDisk(CAMP[0], CAMP[1], CAMP_RADIUS + 1.5, HillsSite.Use.STRUCTURE);
        site.markDisk(BEACON[0], BEACON[1], 4.5, HillsSite.Use.STRUCTURE);
        HillsBarn barn = new HillsBarn(site, BARN);
        barn.reserve();
        new HillsPaths(site).build();
        // The landmarks.
        new HillsWatchtower(site, TOWER).build();
        new HillsWindmill(site, WINDMILL).build();
        new HillsCamp(site, CAMP).build();
        barn.build();
        // Cover, then make every lump climbable, then the greenery.
        HillsDecor decor = new HillsDecor(site);
        decor.beacon(BEACON);
        decor.archeryRange();
        decor.hillfort();
        decor.haystacks();
        decor.fieldWalls();
        decor.breastworks();
        decor.rocks();
        decor.bushes();
        site.settleLumps();
        decor.trees();
        decor.plants();
    }
}
