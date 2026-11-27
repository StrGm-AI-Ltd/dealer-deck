package ai.strgm.deck.openai.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpStatus.OK;

import ai.strgm.deck.exception.ProviderErrorException;
import ai.strgm.deck.exception.ProviderErrorException.Reason;
import ai.strgm.deck.exception.ProviderUnreachableException;
import ai.strgm.deck.openai.config.UpstreamProperties;
import ai.strgm.types.ModelName;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client of the OpenAI-compatible provider configured under {@code deck.upstream}, with its streamed chat completion
 * as a Java method. It hides the HTTP underneath:
 * <ul>
 * <li>the request body, and the credential DEALER forwarded;</li>
 * <li>the provider's error statuses, which become {@link ProviderErrorException}s, and connection failures, which
 * become {@link ProviderUnreachableException}s;</li>
 * <li>the server-sent events, which a {@link ProviderChatStream} reads.</li>
 * </ul>
 */
@Component
public class ProviderClient {
    private final String providerLabel;
    private final ObjectMapper mapper;
    private final ProviderChatApi providerChatApi;

    public ProviderClient(final UpstreamProperties upstreamProperties, final ObjectMapper mapper) {
        this.providerLabel = "Provider " + upstreamProperties.provider();
        this.mapper = mapper;
        final var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(upstreamProperties.connectTimeoutSeconds())).build());
        requests.setReadTimeout(Duration.ofMinutes(upstreamProperties.requestTimeoutMinutes()));
        // the chat path is relative to the base URL, which may or may not end in a slash
        final var baseUrl = upstreamProperties.baseUrl().endsWith("/") ? upstreamProperties.baseUrl() : upstreamProperties.baseUrl() + "/";
        final var restClient = RestClient.builder().requestFactory(requests).baseUrl(baseUrl)
                .defaultStatusHandler(status -> !status.isSameCodeAs(OK), (providerRequest, providerResponse) -> {
                    throw providerError(providerResponse);
                })
                .build();
        this.providerChatApi = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build().createClient(ProviderChatApi.class);
    }

    /**
     * Opens a streamed chat completion of {@code upstreamModel} with the credential DEALER forwarded. It asks the
     * provider to report the tokens used, which the events carry. Close the stream once it's read.
     */
    public ProviderChatStream openChatStream(final String providerAuthorization, final ModelName upstreamModel, final JsonNode chatRequest) {
        final var providerRequest = (ObjectNode) chatRequest.deepCopy();
        providerRequest.put("model", upstreamModel.value());
        providerRequest.put("stream", true);
        providerRequest.putObject("stream_options").put("include_usage", true);
        try {
            return new ProviderChatStream(providerChatApi.openChatStream(providerAuthorization, providerRequest.toString()), mapper);
        } catch (ResourceAccessException exception) {
            // the connection failure's own message, not the wrapper's
            final var cause = exception.getCause();
            throw new ProviderUnreachableException(providerLabel + " unreachable: " + (cause == null ? exception.getMessage() : cause.getMessage()));
        }
    }

    /** The provider's error response as an exception: what its status means, and its body and Retry-After, as it sent them. */
    private ProviderErrorException providerError(final ClientHttpResponse providerResponse) throws IOException {
        final var providerStatus = providerResponse.getStatusCode().value();
        final var message = providerLabel + " error " + providerStatus;
        final String providerErrorBody;
        try (var responseBody = providerResponse.getBody()) {
            providerErrorBody = new String(responseBody.readAllBytes(), UTF_8);
        } catch (IOException exception) {
            return new ProviderErrorException(Reason.UNREADABLE, message, "", null);
        }
        return new ProviderErrorException(reason(providerStatus), message, providerErrorBody, providerResponse.getHeaders().getFirst("Retry-After"));
    }

    /** What the provider's error status means: retry later, a rejected credential, a rejected request, or anything else. */
    private static Reason reason(final int providerStatus) {
        final var status = HttpStatus.resolve(providerStatus);
        return status == null ? Reason.UNAVAILABLE : switch (status) {
            case TOO_MANY_REQUESTS -> Reason.RATE_LIMITED;
            case UNAUTHORIZED, FORBIDDEN -> Reason.CREDENTIAL_REJECTED;
            case BAD_REQUEST, NOT_FOUND, UNPROCESSABLE_CONTENT -> Reason.REQUEST_REJECTED;
            default -> Reason.UNAVAILABLE;
        };
    }
}
