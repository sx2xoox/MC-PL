package io.github.sx2xoox.mcpl.game.augment.runtime;

/** What kind of 증강 야생전 session an {@link AugmentMatch} is. */
public enum MatchMode {
    /** A real match ({@code /게임 시작 증강}). */
    NORMAL,
    /** 증강 연습: phases by command, no timed rounds, dummies, no winner ({@code /게임 증강 연습}). */
    PRACTICE,
    /** 봇전: a real match where bots fill the lobby ({@code /게임 증강 봇전}). */
    BOTS,
    /** 봇 관전전: only bots play, every human watches; quicker timings ({@code /게임 증강 봇전 관전}). */
    SPECTATE
}
