package io.github.sx2xoox.mcpl.game.augment.bot;

/**
 * A bot's gear as tiers, the schedule it improves on while "farming" and the gear score both bots and their read of
 * players are compared with. No Bukkit types: {@link BotBody} maps tiers to items.
 */
public final class Gear {

    /** Armor material of one piece. */
    public enum Armor {
        NONE(0, 0, 0, 0, 0), LEATHER(1, 3, 2, 1, 0), CHAINMAIL(2, 5, 4, 1, 0), IRON(2, 6, 5, 2, 0),
        DIAMOND(3, 8, 6, 3, 2);

        private final int[] points;
        private final double toughness;

        Armor(int helmet, int chest, int legs, int boots, double toughness) {
            this.points = new int[] {helmet, chest, legs, boots};
            this.toughness = toughness;
        }

        /** Armor points of the piece in {@code slot} (0 helmet, 1 chestplate, 2 leggings, 3 boots). */
        public int points(int slot) {
            return points[slot];
        }

        public double toughness() {
            return this == NONE ? 0 : toughness;
        }
    }

    /** Sword material. */
    public enum Weapon {
        NONE(1), WOOD(4), STONE(5), IRON(6), DIAMOND(7);

        private final double damage;

        Weapon(double damage) {
            this.damage = damage;
        }

        /** Attack damage with this sword (a fist is 1). */
        public double damage() {
            return damage;
        }
    }

    /**
     * What a bot carries.
     *
     * @param armor  helmet, chestplate, leggings, boots
     * @param sword  its sword
     * @param bow    it has a bow
     * @param arrows arrows for it
     * @param shield a shield in the off hand
     * @param blocks building blocks for its base and walls (a player's farmed cobblestone, logs and dirt: enough for
     *               a closed base and some to spare)
     */
    public record Loadout(Armor[] armor, Weapon sword, boolean bow, int arrows, boolean shield, int blocks) {

        public Loadout {
            armor = armor.clone();
        }

        public Armor armor(int slot) {
            return armor[slot];
        }

        public int armorPoints() {
            int sum = 0;
            for (int slot = 0; slot < 4; slot++) {
                sum += armor[slot].points(slot);
            }
            return sum;
        }

        public double toughness() {
            double sum = 0;
            for (Armor piece : armor) {
                sum += piece.toughness();
            }
            return sum;
        }

        public double score() {
            return Gear.score(armorPoints(), toughness(), sword.damage());
        }
    }

    private Gear() {
    }

    /**
     * One number to compare gear: armor points, half the toughness and the weapon (a diamond sword vs a stone one is
     * worth about five armor points).
     */
    public static double score(double armorPoints, double toughness, double weaponDamage) {
        return armorPoints + 0.5 * toughness + 2.5 * weaponDamage;
    }

    /** The gear score of a fresh player (nothing at all). */
    public static final double NAKED = score(0, 0, 1);
    /** The gear score of a player in full iron with an iron sword (what a human usually has at PvP). */
    public static final double AVERAGE = score(15, 0, 6);

    private static final Armor N = Armor.NONE;
    private static final Armor L = Armor.LEATHER;
    private static final Armor I = Armor.IRON;
    private static final Armor D = Armor.DIAMOND;

    /**
     * The gear of a bot {@code progress} of the way through the farming (0 = start, 1 = PvP starts), at a human's
     * pace: stone and leather first, then iron, then part diamond. Difficulty scales {@code progress} by its gear pace.
     */
    public static Loadout at(double progress) {
        double p = Math.max(0, progress);
        if (p < 0.08) {
            return new Loadout(new Armor[] {N, N, N, N}, Weapon.WOOD, false, 0, false, 64);
        }
        if (p < 0.2) {
            return new Loadout(new Armor[] {L, L, N, L}, Weapon.STONE, false, 0, false, 96);
        }
        if (p < 0.35) {
            return new Loadout(new Armor[] {L, L, L, L}, Weapon.STONE, true, 16, false, 160);
        }
        if (p < 0.5) {
            return new Loadout(new Armor[] {L, I, L, I}, Weapon.IRON, true, 24, true, 224);
        }
        if (p < 0.7) {
            return new Loadout(new Armor[] {I, I, I, I}, Weapon.IRON, true, 32, true, 256);
        }
        if (p < 0.95) {
            return new Loadout(new Armor[] {I, D, I, I}, Weapon.IRON, true, 48, true, 288);
        }
        if (p < 1.3) {
            return new Loadout(new Armor[] {I, D, I, D}, Weapon.DIAMOND, true, 64, true, 320);
        }
        return new Loadout(new Armor[] {D, D, I, D}, Weapon.DIAMOND, true, 64, true, 384);
    }
}
