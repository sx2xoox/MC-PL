package io.github.sx2xoox.mcpl.core.map.siege;

/** Places on the streets that props must leave free (spawns, plazas, the canal, pillars, stairs). */
final class SiegeZones {

    private SiegeZones() {
    }

    static final int[] MONORAIL_PILLARS = {-144, -112, -84, -24, 4, 60};

    static boolean keepFree(int x, int z) {
        if (x <= -143 && Math.abs(z) <= 12) {
            return true;
        }
        if (SiegeLayout.near(x, z, SiegeLayout.FRONT_X, 0, SiegeLayout.PLAZA_RADIUS + 3)
                || SiegeLayout.near(x, z, SiegeLayout.MIDDLE_X, 0, SiegeLayout.PLAZA_RADIUS + 5)) {
            return true;
        }
        if (z >= SiegeLayout.CANAL_Z1 - 3 && z <= SiegeLayout.CANAL_Z2 + 3) {
            return true;
        }
        if (Math.abs(z) <= 3) {
            for (int px : MONORAIL_PILLARS) {
                if (x >= px - 2 && x <= px + 3) {
                    return true;
                }
            }
        }
        // The checkpoint and the server farm keep their spawn floors free; the forecourt in front of the stairs.
        if (x >= SiegeLayout.CHECKPOINT_X1 - 2 && x <= SiegeLayout.CHECKPOINT_X2 + 2
                && z >= SiegeLayout.CHECKPOINT_Z1 - 2 && z <= SiegeLayout.CHECKPOINT_Z2 + 2) {
            return true;
        }
        if (x >= SiegeLayout.STAIRS_X1 - 4 && Math.abs(z) <= SiegeLayout.MAIN_HALF + 1) {
            return true;
        }
        // The cross streets' crossings over the canal and the gate openings in the firewall.
        return x >= SiegeLayout.WALL_X1 - 3 && x <= SiegeLayout.WALL_X2 + 3;
    }
}
