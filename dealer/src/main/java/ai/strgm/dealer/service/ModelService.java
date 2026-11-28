package ai.strgm.dealer.service;

import ai.strgm.dealer.client.DeckRegistry;
import ai.strgm.dealer.model.AutoTier;
import ai.strgm.dealer.model.ListedModel;
import ai.strgm.types.ModelName;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** The models DEALER offers: its virtual {@code auto-*} tiers, then every DECK profile. */
@Service
public class ModelService {
    private final DeckRegistry decks;

    public ModelService(final DeckRegistry decks) {
        this.decks = decks;
    }

    public List<ListedModel> models() {
        final var tierModels = AutoTier.ALL.stream()
                .map(tier -> new ListedModel(tier.modelName(), "dealer", null, null, tier.contextWindow(), true));
        // TODO(#62): list only the profiles the requesting client may use, not every profile for every client
        // a profile is listed by its id, which clients may request as a model name
        final var profileModels = decks.profiles().stream()
                .map(profileRef -> new ListedModel(new ModelName(profileRef.profile().id().value()),
                        profileRef.serverRef().name().value(), profileRef.serverRef().name(), profileRef.serverRef().config().egress(),
                        profileRef.profile().contextWindow(), false));
        return Stream.concat(tierModels, profileModels)
                .toList();
    }
}
