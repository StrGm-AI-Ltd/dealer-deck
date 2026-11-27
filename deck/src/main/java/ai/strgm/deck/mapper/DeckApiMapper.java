package ai.strgm.deck.mapper;

import static ai.strgm.util.Nulls.mapNullable;

import ai.strgm.deck.api.model.DeckState;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseReduction;
import ai.strgm.deck.api.model.Limitations;
import ai.strgm.deck.api.model.ModelProfile;
import ai.strgm.deck.api.model.ModelProfiles;
import ai.strgm.deck.api.model.Prices;
import ai.strgm.deck.api.model.Quantization;
import ai.strgm.deck.api.model.SpeedPoint;
import ai.strgm.deck.api.model.Usage;
import ai.strgm.deck.api.model.Worker;
import java.time.Instant;
import ai.strgm.deck.model.LeaseSnapshot;
import ai.strgm.deck.model.OfferedProfile;
import ai.strgm.deck.model.OfferedProfiles;
import ai.strgm.deck.model.ServerState;

/** Maps DECK's domain objects to the models generated from the DECK OpenAPI document (D144). */
public final class DeckApiMapper {
    private DeckApiMapper() {
    }

    public static ModelProfiles modelProfiles(final OfferedProfiles offeredProfiles) {
        return new ModelProfiles(offeredProfiles.serverName(), offeredProfiles.implementation(), offeredProfiles.profiles().stream()
                .map(DeckApiMapper::modelProfile)
                .toList());
    }

    public static Lease lease(final LeaseSnapshot lease) {
        return new Lease(lease.id(), lease.profileId(), lease.state()).sessionRef(lease.sessionRef()).createdAtMs(lease.createdAt().toEpochMilli())
                .readyAtMs(mapNullable(lease.readyAt(), Instant::toEpochMilli))
                .usage(new Usage().promptTokens(lease.usage().promptTokens()).cachedTokens(lease.usage().cachedTokens())
                        .completionTokens(lease.usage().completionTokens()).estimated(lease.usage().estimated()));
    }

    public static DeckState deckState(final ServerState state) {
        final var profiles = state.profiles().stream()
                .map(DeckApiMapper::modelProfile)
                .toList();
        final var leases = state.leases().stream()
                .map(DeckApiMapper::lease)
                .toList();
        return new DeckState(state.serverName(), state.implementation(), profiles, leases).provider(state.provider())
                .workers(state.workers().stream()
                        .map(worker -> new Worker().id(worker.id()).loadedProfile(worker.loadedProfile()).state(worker.state()))
                        .toList())
                .spendUsd(state.spendUsd());
    }

    private static ModelProfile modelProfile(final OfferedProfile profile) {
        final var leaseReductions = profile.leaseReductions().stream()
                .map(reduction -> new LeaseReduction(reduction.profileId(), reduction.leases()))
                .toList();
        final var speeds = profile.speeds().stream()
                .map(point -> new SpeedPoint(point.contextTokens(), point.prefillTokensPerSecond(), point.decodeTokensPerSecond()))
                .toList();
        return new ModelProfile(profile.id(), profile.modelName(), profile.contextWindow(), profile.readiness()).provider(profile.provider())
                .quantization(mapNullable(profile.quantization(),
                        quantization -> new Quantization(quantization.format()).bitsPerWeight(quantization.bitsPerWeight())))
                .loadingTimeMs(profile.loadingTimeMs())
                .limitations(new Limitations().leaseReductions(leaseReductions))
                .prices(new Prices().inputPerMTok(profile.prices().inputPerMTok()).cachedInputPerMTok(profile.prices().cachedInputPerMTok())
                        .outputPerMTok(profile.prices().outputPerMTok()))
                .maxLeases(profile.maxLeases()).activeLeases(profile.activeLeases())
                .speeds(speeds);
    }
}
