package ai.strgm.deck.openai;

import static java.lang.Math.max;
import static java.lang.Thread.currentThread;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

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
import ai.strgm.util.Nulls;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
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
    private final UpstreamProperties upstreamProperties;
    private final String prefix;

    public OpenAiCompatibleServer(final DeckProperties properties, final UpstreamProperties upstreamProperties,
            final ObjectMapper mapper) {
        super(properties, mapper);
        this.upstreamProperties = upstreamProperties;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(upstreamProperties.connectTimeoutSeconds())).build();
        this.prefix = upstreamProperties.idPrefix() == null ? upstreamProperties.provider() + "/" : upstreamProperties.idPrefix();
    }

    private UpstreamModel model(final ProfileId profileId) {
        return upstreamProperties.models().stream()
                .filter(upstreamModel -> profileId(upstreamModel).equals(profileId))
                .findFirst()
                .orElseThrow(() -> unknownProfile(profileId));
    }

    /** A model's profile id, which is also the model name DEALER's clients request: the provider's name, prefixed. */
    private ProfileId profileId(final UpstreamModel upstreamModel) {
        return new ProfileId(prefix + upstreamModel.model());
    }

    private List<ModelProfile> profiles() {
        return upstreamProperties.models().stream()
                .map(upstreamModel -> new ModelProfile(profileId(upstreamModel), new ModelName(prefix + upstreamModel.model()),
                        upstreamModel.contextWindow(), Readiness.LOADED).provider(upstreamProperties.provider()).limitations(new Limitations())
                        .quantization(Nulls.map(upstreamModel.quantization(), quantization -> new Quantization(quantization).bitsPerWeight(upstreamModel.bitsPerWeight())))
                        .maxLeases(upstreamModel.maxLeases()).activeLeases(activeLeaseIds(profileId(upstreamModel)).size())
                        .prices(new Prices().inputPerMTok(upstreamModel.inputPerMTok())
                                .cachedInputPerMTok(upstreamModel.cachedInputPerMTok()).outputPerMTok(upstreamModel.outputPerMTok())))
                .toList();
    }

    @Override
    public ModelProfiles modelProfiles() {
        return new ModelProfiles(properties.serverName(), upstreamProperties.implementation(), profiles());
    }

    @Override
    protected LeaseRecord grant(final ProfileId profileId, final SessionRef sessionRef) {
        final var upstreamModel = model(profileId);
        final var activeLeaseIds = activeLeaseIds(profileId);
        if (upstreamModel.maxLeases() != null && activeLeaseIds.size() >= upstreamModel.maxLeases()) {
            throw new ApiException(CONFLICT, "lease_conflict",
                    "The instance budget of " + profileId + " (" + upstreamModel.maxLeases() + ") is in use", activeLeaseIds);
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
        final var spend = allLeases().stream()
                .mapToDouble(this::cost)
                .sum();
        return new DeckState(properties.serverName(), upstreamProperties.implementation(), profiles(), recentLeases())
                .provider(upstreamProperties.provider()).spendUsd(spend);
    }

    @Override
    public StreamingResponseBody chat(final LeaseId leaseId, final @Nullable String providerAuthorization, final JsonNode request) {
        if (providerAuthorization == null || providerAuthorization.isBlank()) {
            throw new ApiException(UNAUTHORIZED, "authentication_error",
                    "Missing X-Provider-Authorization: this DECK server forwards the provider credential it receives (D104)");
        }
        final var leaseRecord = activeRecord(leaseId);
        final var upstreamModel = model(leaseRecord.profileId());
        final var upstreamBody = (ObjectNode) request.deepCopy();
        upstreamBody.put("model", upstreamModel.model().value());
        upstreamBody.put("stream", true);
        upstreamBody.putObject("stream_options").put("include_usage", true);
        final var baseUrl = upstreamProperties.baseUrl().endsWith("/") ? upstreamProperties.baseUrl() : upstreamProperties.baseUrl() + "/";
        final var upstreamRequest = HttpRequest.newBuilder(URI.create(baseUrl + "chat/completions"))
                .header("Authorization", providerAuthorization)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(upstreamProperties.requestTimeoutMinutes()))
                .POST(HttpRequest.BodyPublishers.ofString(upstreamBody.toString()))
                .build();
        final var providerLabel = "Provider " + upstreamProperties.provider();
        final HttpResponse<java.io.InputStream> response;
        try {
            response = http.send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                currentThread().interrupt();
            }
            throw new ApiException(GATEWAY_TIMEOUT, "upstream_error", providerLabel + " unreachable: " + exception.getMessage());
        }
        if (response.statusCode() != OK.value()) {
            final String text;
            try (var responseBody = response.body()) {
                text = new String(responseBody.readAllBytes(), UTF_8);
            } catch (Exception exception) {
                throw new ApiException(BAD_GATEWAY, "upstream_error", providerLabel + " error " + response.statusCode());
            }
            final var upstreamStatus = HttpStatus.resolve(response.statusCode());
            final var responseStatus = upstreamStatus == null ? SERVICE_UNAVAILABLE : switch (upstreamStatus) {
                case TOO_MANY_REQUESTS -> TOO_MANY_REQUESTS;
                case UNAUTHORIZED, FORBIDDEN -> UNAUTHORIZED;
                case BAD_REQUEST, NOT_FOUND, UNPROCESSABLE_CONTENT -> BAD_REQUEST;
                default -> SERVICE_UNAVAILABLE;
            };
            throw new UpstreamException(responseStatus, text.isBlank() ? "{\"error\":{\"message\":\"" + providerLabel + " error "
                    + response.statusCode() + "\",\"type\":\"upstream_error\"}}" : text,
                    response.headers().firstValue("Retry-After").orElse(null));
        }
        final var promptTokens = Json.promptTokens(request);
        final StreamingResponseBody stream = out -> {
            boolean sawUsage = false;
            long completionChars = 0;
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(SSE_DATA_PREFIX)) {
                        final var eventData = line.substring(SSE_DATA_PREFIX.length()).trim();
                        if (!eventData.equals("[DONE]") && eventData.startsWith("{")) {
                            final var chunk = mapper.readTree(eventData);
                            final var usage = chunk.path("usage");
                            if (usage.isObject()) {
                                sawUsage = true;
                                addUsage(leaseRecord, Json.number(usage.path("prompt_tokens")),
                                        Json.number(usage.path("prompt_tokens_details").path("cached_tokens")),
                                        Json.number(usage.path("completion_tokens")), false);
                            }
                            final var choices = chunk.path("choices");
                            for (int choiceIndex = 0; choiceIndex < choices.size(); choiceIndex++) {
                                completionChars += Json.text(choices.get(choiceIndex).path("delta").path("content")).length();
                            }
                        }
                    }
                    out.write((line + "\n").getBytes(UTF_8));
                    if (line.isEmpty()) {
                        out.flush();
                    }
                }
            } finally {
                if (!sawUsage) {
                    addUsage(leaseRecord, promptTokens, 0, max(1, completionChars / Json.CHARS_PER_TOKEN), true);
                }
                out.flush();
            }
        };
        return stream;
    }
}
