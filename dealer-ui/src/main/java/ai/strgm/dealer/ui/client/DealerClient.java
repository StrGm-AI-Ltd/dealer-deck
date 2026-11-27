package ai.strgm.dealer.ui.client;

import static java.util.Objects.requireNonNull;

import ai.strgm.dealer.api.client.MetricsApi;
import ai.strgm.dealer.api.model.DealerState;
import ai.strgm.dealer.ui.config.UiProperties;
import ai.strgm.util.AuthorizationHeaders;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** A client of DEALER's metrics API, built on the HTTP interface generated from the DEALER OpenAPI document. */
@Component
public class DealerClient {
    private final MetricsApi metricsApi;

    public DealerClient(final UiProperties properties) {
        final var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        requests.setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()));
        final var restClient = RestClient.builder().requestFactory(requests).baseUrl(properties.dealer().url())
                .defaultHeader("Authorization", AuthorizationHeaders.forApiKey(properties.dealer().key())).build();
        metricsApi = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build().createClient(MetricsApi.class);
    }

    /** DEALER's state: its servers, queue, sessions, evictions and clients. */
    public DealerState state() {
        return requireNonNull(metricsApi.getDealerState().getBody(), "no state in the response");
    }
}
