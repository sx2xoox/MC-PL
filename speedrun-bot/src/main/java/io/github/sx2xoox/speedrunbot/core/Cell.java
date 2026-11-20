package io.github.sx2xoox.speedrunbot.core;

/** Immutable integer block position, free of Minecraft types so the planners stay unit-testable. */
public final class Cell {
    public final int x, y, z;

    public Cell(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Cell add(int dx, int dy, int dz) {
        return new Cell(x + dx, y + dy, z + dz);
    }

    public Cell add(Cell d) {
        return new Cell(x + d.x, y + d.y, z + d.z);
    }

    public Cell scale(int k) {
        return new Cell(x * k, y * k, z * k);
    }

    public double dist(Cell o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public long pack() {
        return pack(x, y, z);
    }

    /** Same layout as {@code BlockPos#asLong} (26 bits x, 26 bits z, 12 bits y). */
    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public static Cell unpack(long l) {
        int x = (int) (l << 0 >> 38);
        int y = (int) (l << 52 >> 52);
        int z = (int) (l << 26 >> 38);
        return new Cell(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Cell)) {
            return false;
        }
        Cell c = (Cell) o;
        return c.x == x && c.y == y && c.z == z;
    }

    @Override
    public int hashCode() {
        return (y + z * 31) * 31 + x;
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + ")";
    }
}
