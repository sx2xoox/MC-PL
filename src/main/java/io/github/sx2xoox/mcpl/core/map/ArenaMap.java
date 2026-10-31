package io.github.sx2xoox.mcpl.core.map;

/**
 * A ready-made battle map the plugin can build into a world of its own (see {@link MapWorlds}). Maps are generated
 * in code, so the same map is built the same way on every server and version.
 *
 * <p>The map is centered on block {@code 0, groundY, 0} of its world. Players stand on the ground at
 * {@code groundY + 1}; the arena of a match played on the map is a circle of {@link #playRadius()} around the center.
 * The map's terrain reaches a bit further ({@link #buildRadius()}) so nobody falls into the void at the border.
 */
public interface ArenaMap {

    /** Stable lowercase id, used in commands and the world name ({@code mcpl_map_<id>}). */
    String id();

    /** Korean display name. */
    String displayName();

    /** One Korean sentence about the map. */
    String description();

    /** Radius of the arena (the play area and the starting border). */
    int playRadius();

    /** Radius of everything the map builds ({@code >= playRadius}). */
    int buildRadius();

    /** Height of the top of the ground at the center; players stand at {@code groundY + 1}. */
    int groundY();

    /** Lowest block the map may place. */
    int minY();

    /** Highest block the map may place. */
    int maxY();

    /** Seed of the map's randomness (rocks, trees, ruins); keep it fixed so the map is reproducible. */
    long seed();

    /**
     * Version of the map's design. A world built with an older version is rebuilt when the map is set up again, so
     * bump it whenever the map changes.
     */
    int version();

    /** Builds the whole map into {@code canvas} (sized by {@link #buildRadius()}, {@link #minY()}, {@link #maxY()}). */
    void build(MapCanvas canvas);

    /** A new, finished canvas with the whole map. */
    default MapCanvas render() {
        MapCanvas canvas = new MapCanvas(buildRadius(), minY(), maxY(), seed());
        build(canvas);
        canvas.finish();
        return canvas;
    }
}
