package ai.strgm.deck.controller;

import ai.strgm.deck.api.model.DeckState;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseRequest;
import ai.strgm.deck.api.model.Leases;
import ai.strgm.deck.api.model.ModelProfiles;
import ai.strgm.deck.api.server.LeasesApi;
import ai.strgm.deck.api.server.MetricsApi;
import ai.strgm.deck.api.server.ModelProfilesApi;
import ai.strgm.deck.mapper.DeckApiMapper;
import ai.strgm.deck.service.DeckServer;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Implements the interfaces generated from the DECK OpenAPI document (D99, D102). */
@RestController
public class DeckApiController implements ModelProfilesApi, LeasesApi, MetricsApi {
    private final DeckServer server;

    public DeckApiController(final DeckServer server) {
        this.server = server;
    }

    @Override
    public ResponseEntity<ModelProfiles> getModelProfiles() {
        return ResponseEntity.ok(DeckApiMapper.modelProfiles(server.modelProfiles()));
    }

    @Override
    public ResponseEntity<Lease> acquireLease(final LeaseRequest leaseRequest) {
        final var acquisition = server.acquire(leaseRequest.getProfileId(), leaseRequest.getSessionRef());
        final var lease = DeckApiMapper.lease(acquisition.lease());
        return acquisition.created() ? ResponseEntity.created(URI.create("/leases/" + lease.getId())).body(lease) : ResponseEntity.ok(lease);
    }

    @Override
    public ResponseEntity<Leases> listLeases(final @Nullable ProfileId profileId, final @Nullable SessionRef sessionRef) {
        final var leases = server.leases(profileId, sessionRef).stream()
                .map(DeckApiMapper::lease)
                .toList();
        return ResponseEntity.ok(new Leases(leases));
    }

    @Override
    public ResponseEntity<Lease> getLease(final LeaseId leaseId) {
        return ResponseEntity.ok(DeckApiMapper.lease(server.get(leaseId)));
    }

    @Override
    public ResponseEntity<Void> releaseLease(final LeaseId leaseId) {
        server.release(leaseId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Lease> evictLease(final LeaseId leaseId) {
        return ResponseEntity.ok(DeckApiMapper.lease(server.evict(leaseId)));
    }

    @Override
    public ResponseEntity<DeckState> getDeckState() {
        return ResponseEntity.ok(DeckApiMapper.deckState(server.state()));
    }
}
