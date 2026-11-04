package io.github.sx2xoox.mcpl.game.siege.stats;

import java.util.UUID;

/**
 * What one participant did in one round with one ability (or, for a 봇전 bot, one bot job): one row of a match's CSV.
 * Damage is the health actually lost (overkill capped at the health left).
 */
public final class StatLine {

    /** How the round ended for the line's team. */
    public enum Result {
        WIN("승"),
        LOSS("패"),
        /** The match stopped (or a team left) before the round was decided: kept out of the totals. */
        UNFINISHED("중단");

        private final String label;

        Result(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final int round;
    private final UUID participant;
    private final String name;
    private final boolean bot;
    private final String id;
    private final String displayName;

    long fightTicks;
    double enemySkill;
    double enemyBasic;
    double structure;
    double creature;
    double taken;
    int kills;
    int deaths;
    int assists;
    int level = 1;
    Result result = Result.UNFINISHED;

    public StatLine(int round, UUID participant, String name, boolean bot, String id, String displayName) {
        this.round = round;
        this.participant = participant;
        this.name = name;
        this.bot = bot;
        this.id = id;
        this.displayName = displayName;
    }

    public int round() {
        return round;
    }

    public UUID participant() {
        return participant;
    }

    /** The player's (or bot's) name. */
    public String name() {
        return name;
    }

    /** True for a 봇전 bot: {@link #id} is a bot job key, not an ability id. */
    public boolean bot() {
        return bot;
    }

    /** The ability id, or the bot job key for a bot. */
    public String id() {
        return id;
    }

    /** The ability's (or bot job's) Korean name. */
    public String displayName() {
        return displayName;
    }

    /** Fight time of the round the participant was in the match for, in seconds. */
    public double seconds() {
        return fightTicks / 20.0;
    }

    /** Damage to enemy players and bots from skills (actives, passives, ultimates, summons). */
    public double enemySkill() {
        return enemySkill;
    }

    /** Damage to enemy players and bots from basic attacks (평타). */
    public double enemyBasic() {
        return enemyBasic;
    }

    public double enemyDamage() {
        return enemySkill + enemyBasic;
    }

    /** Damage to towers and cores. */
    public double structure() {
        return structure;
    }

    /** Damage to minions and jungle monsters. */
    public double creature() {
        return creature;
    }

    /** Damage taken from anything. */
    public double taken() {
        return taken;
    }

    public int kills() {
        return kills;
    }

    public int deaths() {
        return deaths;
    }

    public int assists() {
        return assists;
    }

    /** The level at the end of the round. */
    public int level() {
        return level;
    }

    public Result result() {
        return result;
    }

    /** True if anything at all was recorded (an empty line is not written). */
    boolean hasData() {
        return fightTicks > 0 || enemySkill > 0 || enemyBasic > 0 || structure > 0 || creature > 0 || taken > 0
                || kills > 0 || deaths > 0 || assists > 0;
    }
}
