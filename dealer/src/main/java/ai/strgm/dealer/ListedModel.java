package ai.strgm.dealer;

import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.types.ModelName;
import ai.strgm.types.ServerName;
import org.jspecify.annotations.Nullable;

/**
 * A model DEALER offers: a virtual {@code auto-*} tier, owned by DEALER, or a DECK profile, listed by its id and owned
 * by its DECK server.
 */
public record ListedModel(ModelName modelName, String ownedBy, @Nullable ServerName deckServer, @Nullable ServerType serverType,
        int contextWindow, boolean virtual) {}
