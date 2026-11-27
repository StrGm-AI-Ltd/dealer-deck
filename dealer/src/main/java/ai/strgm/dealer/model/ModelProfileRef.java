package ai.strgm.dealer.model;

/** A profile on a DECK server: what a request is placed on. */
public record ModelProfileRef(DeckServerRef serverRef, DeckProfile profile) {
    public String key() {
        return serverRef.name() + "/" + profile.id();
    }

    public boolean external() {
        return serverRef.external();
    }
}
