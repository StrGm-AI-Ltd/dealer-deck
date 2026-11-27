package ai.strgm.deck.openai;

import static java.lang.Math.max;
import static java.lang.Thread.currentThread;

import ai.strgm.deck.AbstractDeckServer;
import ai.strgm.deck.ApiException;
import ai.strgm.deck.DeckProperties;
import ai.strgm.deck.Json;
import ai.strgm.deck.LeaseRecord;
import ai.strgm.deck.UpstreamException;
import ai.strgm.deck.api.model.DeckState;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.deck.api.model.Limitations;
import ai.strgm.deck.api.model.ModelProfile;
import ai.strgm.deck.api.model.ModelProfiles;
import ai.strgm.deck.api.model.Prices;
import ai.strgm.deck.api.model.Quantization;
import ai.strgm.deck.api.model.Readiness;
import ai.strgm.deck.openai.UpstreamProperties.UpstreamModel;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ModelName;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The DECK server backed by an OpenAI-compatible provider, configured under {@code deck.upstream}: Nebius Token
 * Factory by default (D91, D92), or a server on the operator's own hardware, such as oMLX. The provider manages
 * its own capacity, so profiles are always loaded and leases are granted at once, up to each model's instance
 * budget; a lease carries its session's usage. DECK stores no provider credentials in v0.5: it forwards the one
 * DEALER sends (D104).
 */
@Component
public class OpenAiCompatibleServer extends AbstractDeckServer {
    /** Prices are per million tokens. */
    private static final double TOKENS_PER_MILLION = 1_000_000.0;
    /** The prefix of a server-sent event's data line. */
    private static final String SSE_DATA_PREFIX = "data:";

    private final HttpClient http;
    private final UpstreamProperties upstream;
    private final String prefix;

    public OpenAiCompatibleServer(final DeckProperties properties, final UpstreamProperties upstream,
            final ObjectMapper mapper) {
        super(properties, mapper);
        this.upstream = upstream;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(upstream.connectTimeoutSeconds())).build();
        this.prefix = upstream.idPrefix() == null ? upstream.provider() + "/" : upstream.idPrefix();
    }

    private UpstreamModel model(final ProfileId profileId) {
        return upstream.models().stream()
                .filter(upstreamModel -> profileId(upstreamModel).equals(profileId))
                .findFirst()
                .orElseThrow(() -> unknownProfile(profileId));
    }

    /** A model's profile id, which is also the model name DEALER's clients request: the provider's name, prefixed. */
    private ProfileId profileId(final UpstreamModel upstreamModel) {
        return new ProfileId(prefix + upstreamModel.model());
    }

    private List<ModelProfile> profiles() {
        return upstream.models().stream()
                .map(upstreamModel -> new ModelProfile(profileId(upstreamModel), new ModelName(prefix + upstreamModel.model()),
                        upstreamModel.contextWindow(), Readiness.LOADED).provider(upstream.provider()).limitations(new Limitations())
                        .quantization(upstreamModel.quantization() == null ? null
                                : new Quantization(upstreamModel.quantization()).bitsPerWeight(upstreamModel.bitsPerWeight()))
                        .maxLeases(upstreamModel.maxLeases()).activeLeases(active(profileId(upstreamModel)).size())
                        .prices(new Prices().inputPerMTok(upstreamModel.inputPerMTok())
                                .cachedInputPerMTok(upstreamModel.cachedInputPerMTok()).outputPerMTok(upstreamModel.outputPerMTok())))
                .toList();
    }

    @Override
    public ModelProfiles modelProfiles() {
        return new ModelProfiles(properties.serverName(), upstream.implementation(), profiles());
    }

    @Override
    protected LeaseRecord grant(final ProfileId profileId, final SessionRef sessionRef) {
        final var upstreamModel = model(profileId);
        final var active = active(profileId);
        if (upstreamModel.maxLeases() != null && active.size() >= upstreamModel.maxLeases()) {
            throw new ApiException(HttpStatus.CONFLICT, "lease_conflict",
                    "The instance budget of " + profileId + " (" + upstreamModel.maxLeases() + ") is in use", active);
        }
        return register(profileId, sessionRef, LeaseState.READY);
    }

    // TODO(#104): a money amount with its currency, instead of a bare double
    private double cost(final Lease lease) {
        final var upstreamModel = model(lease.getProfileId());
        final var usage = lease.getUsage();
        if (usage == null) {
            return 0;
        }
        final var cachedTokens = orZero(usage.getCachedTokens());
        return ((orZero(usage.getPromptTokens()) - cachedTokens) * upstreamModel.inputPerMTok()
                + cachedTokens * upstreamModel.cachedInputPerMTok()
                + orZero(usage.getCompletionTokens()) * upstreamModel.outputPerMTok()) / TOKENS_PER_MILLION;
    }

    private static long orZero(final @Nullable Long tokens) {
        return tokens == null ? 0 : tokens;
    }

    @Override
    public DeckState state() {
        final var spend = all().stream()
                .mapToDouble(this::cost)
                .sum();
        return new DeckState(properties.serverName(), upstream.implementation(), profiles(), recent())
                .provider(upstream.provider()).spendUsd(spend);
    }

    @Override
    public StreamingResponseBody chat(final LeaseId leaseId, final @Nullable String providerAuthorization, final JsonNode request) {
        if (providerAuthorization == null || providerAuthorization.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_error",
                    "Missing X-Provider-Authorization: this DECK server forwards the provider credential it receives (D104)");
        }
        final var lease = activeRecord(leaseId);
        final var upstreamModel = model(lease.profileId());
        final var body = (ObjectNode) request.deepCopy();
        body.put("model", upstreamModel.model().value());
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        final var base = upstream.baseUrl().endsWith("/") ? upstream.baseUrl() : upstream.baseUrl() + "/";
        final var upstreamRequest = HttpRequest.newBuilder(URI.create(base + "chat/completions"))
                .header("Authorization", providerAuthorization)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(upstream.requestTimeoutMinutes()))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        final var name = "Provider " + upstream.provider();
        final HttpResponse<java.io.InputStream> response;
        try {
            response = http.send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                currentThread().interrupt();
            }
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "upstream_error", name + " unreachable: " + exception.getMessage());
        }
        if (response.statusCode() != HttpStatus.OK.value()) {
            final String text;
            try (var responseBody = response.body()) {
                text = new String(responseBody.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception exception) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "upstream_error", name + " error " + response.statusCode());
            }
            final var upstreamStatus = HttpStatus.resolve(response.statusCode());
            final var status = upstreamStatus == null ? HttpStatus.SERVICE_UNAVAILABLE : switch (upstreamStatus) {
                case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
                case UNAUTHORIZED, FORBIDDEN -> HttpStatus.UNAUTHORIZED;
                case BAD_REQUEST, NOT_FOUND, UNPROCESSABLE_CONTENT -> HttpStatus.BAD_REQUEST;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new UpstreamException(status, text.isBlank() ? "{\"error\":{\"message\":\"" + name + " error "
                    + response.statusCode() + "\",\"type\":\"upstream_error\"}}" : text,
                    response.headers().firstValue("Retry-After").orElse(null));
        }
        final var prompt = Json.promptTokens(request);
        final StreamingResponseBody stream = out -> {
            boolean sawUsage = false;
            long chars = 0;
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(SSE_DATA_PREFIX)) {
                        final var data = line.substring(SSE_DATA_PREFIX.length()).trim();
                        if (!data.equals("[DONE]") && data.startsWith("{")) {
                            final var chunk = mapper.readTree(data);
                            final var usage = chunk.path("usage");
                            if (usage.isObject()) {
                                sawUsage = true;
                                addUsage(lease, Json.number(usage.path("prompt_tokens")),
                                        Json.number(usage.path("prompt_tokens_details").path("cached_tokens")),
                                        Json.number(usage.path("completion_tokens")), false);
                            }
                            final var choices = chunk.path("choices");
                            for (int choiceIndex = 0; choiceIndex < choices.size(); choiceIndex++) {
                                chars += Json.text(choices.get(choiceIndex).path("delta").path("content")).length();
                            }
                        }
                    }
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    if (line.isEmpty()) {
                        out.flush();
                    }
                }
            } finally {
                if (!sawUsage) {
                    addUsage(lease, prompt, 0, max(1, chars / Json.CHARS_PER_TOKEN), true);
                }
                out.flush();
            }
        };
        return stream;
    }
}
