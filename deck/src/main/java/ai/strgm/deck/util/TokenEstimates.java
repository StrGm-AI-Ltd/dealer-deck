package ai.strgm.deck.util;

import static java.lang.Math.max;

/** Rough token estimates, for a prompt's size and for a completion whose provider reports no usage. */
public final class TokenEstimates {
    /** A rough estimate of the characters in one token. */
    private static final int CHARS_PER_TOKEN = 4;

    private TokenEstimates() {}

    /** A rough estimate of the tokens in {@code chars} characters: {@value #CHARS_PER_TOKEN} characters per token, and at least one. */
    public static long fromChars(final long chars) {
        return max(1, chars / CHARS_PER_TOKEN);
    }
}
