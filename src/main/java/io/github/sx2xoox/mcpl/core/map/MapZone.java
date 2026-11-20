package io.github.sx2xoox.mcpl.core.map;

/**
 * One part of a map built by its own class (a district, a lake, a castle), so large maps stay manageable. The
 * canvas a zone gets is {@linkplain MapCanvas#restrictTo restricted} to the zone's columns.
 */
public interface MapZone {

    /** Short English name, used for the zone's randomness and in errors. */
    String name();

    /** True for the columns the zone owns. */
    boolean contains(int x, int z);

    /** Builds the zone into {@code canvas}, which only accepts writes inside {@link #contains}. */
    void build(MapCanvas canvas);
}
