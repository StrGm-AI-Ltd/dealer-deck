package ai.strgm.dealer.ui;

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
    final ai.strgm.dealer.api.client.MetricsApi dealer;
    final Map<String, ai.strgm.deck.api.client.MetricsApi> decks = new LinkedHashMap<>();

    public Clients(final UiProperties props) {
        dealer = factory(props.dealer()).createClient(ai.strgm.dealer.api.client.MetricsApi.class);
        props.decks().forEach(d -> decks.put(d.name(), factory(d).createClient(ai.strgm.deck.api.client.MetricsApi.class)));
    }

    private static HttpServiceProxyFactory factory(final UiProperties.Endpoint e) {
        final var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(2000);
        requests.setReadTimeout(5000);
        final var rest = RestClient.builder().requestFactory(requests).baseUrl(e.url())
                .defaultHeader("Authorization", "Bearer " + e.key()).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build();
    }
}
