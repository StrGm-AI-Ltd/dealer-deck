package ai.strgm.dealer.ui.client;

import ai.strgm.dealer.api.client.MetricsApi;
import ai.strgm.dealer.ui.config.UiProperties;
import org.springframework.stereotype.Component;

/** The generated HTTP-interface client for DEALER's metrics API. */
@Component
public class DealerClient {
    private final MetricsApi metricsApi;

    public DealerClient(final UiProperties properties) {
        metricsApi = ProxyFactories.forEndpoint(properties.dealer(), properties).createClient(MetricsApi.class);
    }

    /** DEALER's metrics API. */
    public MetricsApi metricsApi() {
        return metricsApi;
    }
}
