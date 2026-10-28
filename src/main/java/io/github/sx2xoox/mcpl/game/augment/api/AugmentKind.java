package io.github.sx2xoox.mcpl.game.augment.api;

/** What an augment is about (shown on its card and in the docs). */
public enum AugmentKind {
    STAT("능력치"),
    RESOURCE("자원"),
    COMBAT("전투"),
    MOBILITY("기동"),
    TRADEOFF("양날의 검"),
    SPECIAL("특수");

    private final String koreanName;

    AugmentKind(String koreanName) {
        this.koreanName = koreanName;
    }

    public String koreanName() {
        return koreanName;
    }
}
