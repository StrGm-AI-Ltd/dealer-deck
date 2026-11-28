package ai.strgm.types;

/**
 * Token prices per million tokens; zero where none are stated. The one place that knows how a completion's tokens are
 * priced, for DEALER's accounting and DECK's spend alike.
 */
// TODO(#104): money amounts with their currency, instead of bare doubles in an implied currency
public record TokenPrices(double inputPerMTok, double cachedInputPerMTok, double outputPerMTok) {
    public static final TokenPrices NONE = new TokenPrices(0, 0, 0);
    /** Prices are per million tokens. */
    private static final double TOKENS_PER_MILLION = 1_000_000.0;

    /**
     * What a completion's tokens cost: the prompt tokens not cached at the input price, the cached ones at the cached
     * input price, and the completion tokens at the output price.
     */
    public double costUsd(final long promptTokens, final long cachedTokens, final long completionTokens) {
        return ((promptTokens - cachedTokens) * inputPerMTok + cachedTokens * cachedInputPerMTok + completionTokens * outputPerMTok)
                / TOKENS_PER_MILLION;
    }
}
