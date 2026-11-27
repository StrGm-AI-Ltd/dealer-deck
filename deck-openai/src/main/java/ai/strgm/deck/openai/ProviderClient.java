package ai.strgm.deck.openai;

import static java.lang.Thread.currentThread;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpStatus.OK;

import ai.strgm.deck.ProviderErrorException;
import ai.strgm.deck.ProviderErrorException.Reason;
import ai.strgm.deck.ProviderUnreachableException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The chat endpoint of the OpenAI-compatible provider configured under {@code deck.upstream}. Throws
 * {@link ProviderUnreachableException} when the provider can't be reached, and {@link ProviderErrorException} when it
 * refuses a request.
 */
@Component
public class ProviderClient {
    private final HttpClient http;
    private final UpstreamProperties upstreamProperties;

    public ProviderClient(final UpstreamProperties upstreamProperties) {
        this.upstreamProperties = upstreamProperties;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(upstreamProperties.connectTimeoutSeconds())).build();
    }

    /** Sends a streamed chat completion with the credential DEALER forwarded, and returns its server-sent events. */
    public InputStream openChat(final String providerAuthorization, final String requestBody) {
        final var baseUrl = upstreamProperties.baseUrl().endsWith("/") ? upstreamProperties.baseUrl() : upstreamProperties.baseUrl() + "/";
        final var request = HttpRequest.newBuilder(URI.create(baseUrl + "chat/completions"))
                .header("Authorization", providerAuthorization)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(upstreamProperties.requestTimeoutMinutes()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        final var providerLabel = "Provider " + upstreamProperties.provider();
        final HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                currentThread().interrupt();
            }
            throw new ProviderUnreachableException(providerLabel + " unreachable: " + exception.getMessage());
        }
        if (response.statusCode() != OK.value()) {
            final var message = providerLabel + " error " + response.statusCode();
            final String text;
            try (var responseBody = response.body()) {
                text = new String(responseBody.readAllBytes(), UTF_8);
            } catch (Exception exception) {
                throw new ProviderErrorException(Reason.UNREADABLE, message, "", null);
            }
            throw new ProviderErrorException(reason(response.statusCode()), message, text, response.headers().firstValue("Retry-After").orElse(null));
        }
        return response.body();
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
