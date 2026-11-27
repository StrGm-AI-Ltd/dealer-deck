package ai.strgm.dealer;

import ai.strgm.dealer.api.model.DealerState;
import ai.strgm.dealer.api.model.ModelList;
import ai.strgm.dealer.api.server.MetricsApi;
import ai.strgm.dealer.api.server.ModelsApi;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Implements the interfaces generated from the DEALER OpenAPI document (D99, D102). */
@RestController
public class DealerApiController implements ModelsApi, MetricsApi {
    private final ModelService modelService;
    private final StateService stateService;
    // every model's `created`: DEALER's start time (this controller is created once, at startup), as no model has
    // a creation date of its own
    private final long startedAt = Instant.now().getEpochSecond();

    public DealerApiController(final ModelService modelService, final StateService stateService) {
        this.modelService = modelService;
        this.stateService = stateService;
    }

    @Override
    public ResponseEntity<ModelList> listModels() {
        return ResponseEntity.ok(DealerApiMapper.modelList(modelService.models(), startedAt));
    }

    @Override
    public ResponseEntity<DealerState> getDealerState() {
        return ResponseEntity.ok(DealerApiMapper.dealerState(stateService.state()));
    }
}
