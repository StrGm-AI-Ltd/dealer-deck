package ai.strgm.deck.model;

import ai.strgm.deck.api.model.Implementation;
import ai.strgm.types.ServerName;
import java.util.List;

/** The profiles a DECK server offers, with the server's name and implementation. */
public record OfferedProfiles(ServerName serverName, Implementation implementation, List<OfferedProfile> profiles) {}
