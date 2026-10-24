package io.github.sx2xoox.mcpl.core.map.citadel;

/**
 * Smooth, deterministic 2D value noise for the lake district's shores, beds and meadows (no {@code Math.random}: the
 * same seed always gives the same map).
 */
final class LakeNoise {

    private final long seed;

    LakeNoise(long seed) {
        this.seed = seed;
    }

    /** Noise in about {@code -1..1}, varying over {@code scale} blocks, with {@code octaves} finer layers added. */
    double at(double x, double z, double scale, int octaves) {
        double sum = 0;
        double weight = 1;
        double total = 0;
        double frequency = 1 / scale;
        for (int i = 0; i < octaves; i++) {
            sum += weight * value(x * frequency + i * 31.7, z * frequency - i * 17.3);
            total += weight;
            weight *= 0.5;
            frequency *= 2.03;
        }
        return sum / total;
    }

    /** Noise in {@code 0..1} (for picking materials by threshold). */
    double unit(double x, double z, double scale) {
        return (at(x, z, scale, 2) + 1) / 2;
    }

    /** A per-column hash in {@code 0..1}, not smooth (for scattering single blocks). */
    double hash(int x, int z, int salt) {
        long h = mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L));
        return (h >>> 11) * 0x1.0p-53;
    }

    private double value(double x, double z) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        double fx = smooth(x - ix);
        double fz = smooth(z - iz);
        double a = lattice(ix, iz);
        double b = lattice(ix + 1, iz);
        double c = lattice(ix, iz + 1);
        double d = lattice(ix + 1, iz + 1);
        double top = a + (b - a) * fx;
        double bottom = c + (d - c) * fx;
        return top + (bottom - top) * fz;
    }

    private double lattice(int x, int z) {
        return hash(x, z, 7) * 2 - 1;
    }

    private static double smooth(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
