package ai.strgm.deck.exception;

/** A chat request for a provider-backed server came without the provider credential to forward (D104). */
public class ProviderCredentialMissingException extends RuntimeException {
    public ProviderCredentialMissingException() {
        super("No provider credential to forward");
    }
}
