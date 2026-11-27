package ai.strgm.dealer;

import ai.strgm.types.ModelName;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** The models DEALER offers: its virtual {@code auto-*} tiers, then every DECK profile. */
@Service
public class ModelService {
    private final DeckRegistry decks;

    public ModelService(final DeckRegistry decks) {
        this.decks = decks;
    }

    public List<ListedModel> models() {
        final List<ListedModel> models = new ArrayList<>();
        for (final var tier : AutoTier.ALL) {
            models.add(new ListedModel(tier.modelName(), "dealer", null, null, tier.contextWindow(), true));
        }
        // TODO(#62): list only the profiles the requesting client may use, not every profile for every client
        // a profile is listed by its id, which clients may request as a model name
        decks.profiles().forEach(profileRef -> models.add(new ListedModel(new ModelName(profileRef.profile().id().value()),
                profileRef.serverRef().name().value(), profileRef.serverRef().name(), profileRef.serverRef().config().egress(),
                profileRef.profile().contextWindow(), false)));
        return models;
    }
}
