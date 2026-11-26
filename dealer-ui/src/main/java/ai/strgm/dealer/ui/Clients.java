package ai.strgm.dealer.ui;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** The generated HTTP-interface clients for DEALER's and each DECK server's metrics API. */
@Component
public class Clients {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    final ai.strgm.dealer.api.client.MetricsApi dealer;
    final Map<String, ai.strgm.deck.api.client.MetricsApi> decks = new LinkedHashMap<>();

    public Clients(final UiProperties properties) {
        dealer = factory(properties.dealer()).createClient(ai.strgm.dealer.api.client.MetricsApi.class);
        properties.decks().forEach(deck -> decks.put(deck.name(), factory(deck).createClient(ai.strgm.deck.api.client.MetricsApi.class)));
    }

    private static HttpServiceProxyFactory factory(final UiProperties.Endpoint endpoint) {
        final var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(CONNECT_TIMEOUT);
        requests.setReadTimeout(READ_TIMEOUT);
        final var restClient = RestClient.builder().requestFactory(requests).baseUrl(endpoint.url())
                .defaultHeader("Authorization", "Bearer " + endpoint.key()).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build();
    }
}
