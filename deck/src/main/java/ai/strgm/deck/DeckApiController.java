package ai.strgm.deck;

import ai.strgm.deck.api.model.DeckState;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseRequest;
import ai.strgm.deck.api.model.Leases;
import ai.strgm.deck.api.model.ModelProfiles;
import ai.strgm.deck.api.server.LeasesApi;
import ai.strgm.deck.api.server.MetricsApi;
import ai.strgm.deck.api.server.ModelProfilesApi;
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
        return ResponseEntity.ok(server.modelProfiles());
    }

    @Override
    public ResponseEntity<Lease> acquireLease(final LeaseRequest leaseRequest) {
        final var acquired = server.acquire(leaseRequest.getProfileId(), leaseRequest.getSessionRef());
        final var lease = acquired.lease();
        return acquired.created() ? ResponseEntity.created(URI.create("/leases/" + lease.getId())).body(lease) : ResponseEntity.ok(lease);
    }

    @Override
    public ResponseEntity<Leases> listLeases(final @Nullable ProfileId profileId, final @Nullable SessionRef sessionRef) {
        return ResponseEntity.ok(new Leases(server.leases(profileId, sessionRef)));
    }

    @Override
    public ResponseEntity<Lease> getLease(final LeaseId leaseId) {
        return ResponseEntity.ok(server.get(leaseId));
    }

    @Override
    public ResponseEntity<Void> releaseLease(final LeaseId leaseId) {
        server.release(leaseId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Lease> evictLease(final LeaseId leaseId) {
        return ResponseEntity.ok(server.evict(leaseId));
    }

    @Override
    public ResponseEntity<DeckState> getDeckState() {
        return ResponseEntity.ok(server.state());
    }
}
