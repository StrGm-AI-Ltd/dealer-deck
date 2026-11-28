package ai.strgm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** How a completion's tokens are priced. */
class TokenPricesTest {
    private static final double INPUT_PER_M_TOK = 1.0;
    private static final double CACHED_INPUT_PER_M_TOK = 0.5;
    private static final double OUTPUT_PER_M_TOK = 2.0;
    private static final long PROMPT_TOKENS = 1_000_000;
    private static final long CACHED_TOKENS = 400_000;
    private static final long COMPLETION_TOKENS = 500_000;
    private static final double ROUNDING = 1e-12;

    @Test
    void uncachedPromptCachedPromptAndCompletionTokensEachHaveTheirPrice() {
        final var prices = new TokenPrices(INPUT_PER_M_TOK, CACHED_INPUT_PER_M_TOK, OUTPUT_PER_M_TOK);
        // 600k uncached prompt tokens at 1, 400k cached ones at 0.5 and 500k completion tokens at 2, per million
        assertEquals(0.6 + 0.2 + 1.0, prices.costUsd(PROMPT_TOKENS, CACHED_TOKENS, COMPLETION_TOKENS), ROUNDING);
    }

    @Test
    void tokensWithoutPricesCostNothing() {
        assertEquals(0.0, TokenPrices.NONE.costUsd(PROMPT_TOKENS, CACHED_TOKENS, COMPLETION_TOKENS));
    }
}
