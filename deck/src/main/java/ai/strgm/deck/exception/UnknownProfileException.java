package ai.strgm.deck.exception;

import ai.strgm.types.ProfileId;

/** This DECK server offers no such profile. */
public class UnknownProfileException extends RuntimeException {
    public UnknownProfileException(final ProfileId profileId) {
        super("Unknown profile: " + profileId);
    }
}
