package ai.strgm.dealer;

import ai.strgm.dealer.DealerProperties.DeckServerConfig;
import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.types.ServerName;

/** A configured DECK server, as the scheduler refers to it. {@link DeckRegistry} makes one per server, at startup. */
public record DeckServerRef(DeckServerConfig config) {
    public boolean external() {
        return config.egress() == ServerType.EXTERNAL;
    }

    public ServerName name() {
        return config.name();
    }
}
