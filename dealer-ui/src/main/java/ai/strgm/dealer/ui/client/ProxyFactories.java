package ai.strgm.dealer.ui.client;

import ai.strgm.dealer.ui.config.UiProperties;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** Builds the factory of an endpoint's generated HTTP-interface clients: its URL and key, and the configured timeouts. */
final class ProxyFactories {
    private ProxyFactories() {}

    static HttpServiceProxyFactory forEndpoint(final UiProperties.Endpoint endpoint, final UiProperties properties) {
        final var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        requests.setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()));
        final var restClient = RestClient.builder().requestFactory(requests).baseUrl(endpoint.url())
                .defaultHeader("Authorization", "Bearer " + endpoint.key()).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build();
    }
}
