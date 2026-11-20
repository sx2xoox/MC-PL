package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.List;
import java.util.Locale;

/**
 * The last check on what a Claude bot says ({@code chat} and {@code reply}): its trash talk may tease how someone
 * plays, never more. A line that holds a blocked word (spaces, punctuation and case ignored) is dropped whole, not
 * masked. The system prompt asks for the same ({@link ClaudePrompt}); this is the net under it. Pure.
 */
final class ClaudeFilter {

    /**
     * Blocked words, normalized (lower case, no spaces or punctuation): swearing, slurs and hate, sexual words, family
     * and looks, self-harm and death wishes, and anything about being an AI or its instructions.
     */
    static final List<String> BLOCKED = List.of(
            // swearing
            "씨발", "시발", "씨바", "시바", "ㅅㅂ", "ㅆㅂ", "병신", "븅신", "ㅂㅅ", "ㅄ", "좆", "존나", "졸라", "ㅈㄴ",
            "개새", "새끼", "섀끼", "ㅅㄲ", "씹", "지랄", "ㅈㄹ", "미친놈", "미친년", "닥쳐", "꺼져", "엿먹",
            "fuck", "shit", "bitch", "asshole", "dick", "cunt", "wtf", "stfu",
            // family, looks, real life
            "느금", "니애미", "애미", "애비", "엄마", "아빠", "부모", "가족", "패드립", "못생", "뚱뚱", "얼굴", "외모",
            "키작", "대머리", "학교", "직장", "주소", "신상",
            // hate and slurs
            "장애인", "애자", "틀딱", "한남", "김치녀", "메갈", "일베", "흑형", "짱깨", "쪽바리", "게이", "호모",
            "nigg", "fag", "retard",
            // sexual
            "섹스", "야동", "보지", "자지", "강간", "sex", "porn",
            // self-harm, death wishes
            "자살", "자해", "뒤져", "뒤지", "디져", "죽어라", "죽어버려", "죽여버", "목매", "kys", "killyourself",
            // breaking character
            "프롬프트", "prompt", "시스템", "system", "instruction", "지시사항", "인공지능", "ai봇", "언어모델", "claudeapi");

    private ClaudeFilter() {
    }

    /** True when {@code line} may be said. */
    static boolean allows(String line) {
        String flat = normalize(line);
        for (String word : BLOCKED) {
            if (flat.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** Lower case, letters and digits only (Hangul jamo kept), so "씨 발" and "s.h.i.t" are caught. */
    static String normalize(String text) {
        StringBuilder b = new StringBuilder(text.length());
        text.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(b::appendCodePoint);
        return b.toString();
    }
}
