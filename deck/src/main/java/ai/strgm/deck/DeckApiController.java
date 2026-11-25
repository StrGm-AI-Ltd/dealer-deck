package ai.strgm.deck;

import ai.strgm.deck.api.model.DeckState;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseRequest;
import ai.strgm.deck.api.model.ModelProfiles;
import ai.strgm.deck.api.server.LeasesApi;
import ai.strgm.deck.api.server.MetricsApi;
import ai.strgm.deck.api.server.ModelProfilesApi;
import java.util.List;
import org.springframework.http.HttpStatus;
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
        return ResponseEntity.status(HttpStatus.CREATED).body(server.acquire(leaseRequest.getProfileId(), leaseRequest.getSessionRef()));
    }

    @Override
    public ResponseEntity<List<Lease>> listLeases(final String profileId) {
        return ResponseEntity.ok(server.leases(profileId));
    }

    @Override
    public ResponseEntity<Lease> getLease(final String leaseId) {
        return ResponseEntity.ok(server.get(leaseId));
    }

    @Override
    public ResponseEntity<Void> releaseLease(final String leaseId) {
        server.release(leaseId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Lease> evictLease(final String leaseId) {
        return ResponseEntity.ok(server.evict(leaseId));
    }

    @Override
    public ResponseEntity<DeckState> getDeckState() {
        return ResponseEntity.ok(server.state());
    }
}
