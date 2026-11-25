package ai.strgm.deck.openai;

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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final UpstreamProperties upstream;
    private final String prefix;

    public OpenAiCompatibleServer(final DeckProperties props, final UpstreamProperties upstream,
            final ObjectMapper mapper) {
        super(props, mapper);
        this.upstream = upstream;
        this.prefix = upstream.idPrefix() == null ? upstream.provider() + "/" : upstream.idPrefix();
    }

    private UpstreamProperties.Model model(final String profileId) {
        return upstream.models().stream().filter(m -> (prefix + m.model()).equals(profileId)).findFirst()
                .orElseThrow(() -> unknownProfile(profileId));
    }

    private List<ModelProfile> profiles() {
        return upstream.models().stream()
                .map(m -> new ModelProfile(prefix + m.model(), prefix + m.model(), m.contextWindow(),
                        Readiness.LOADED).provider(upstream.provider()).limitations(new Limitations())
                        .quantization(m.quantization() == null ? null : new Quantization(m.quantization()).bitsPerWeight(m.bitsPerWeight()))
                        .maxLeases(m.maxLeases()).activeLeases(active(prefix + m.model()).size())
                        .prices(new Prices().inputPerMTok(m.inputPerMTok()).cachedInputPerMTok(m.cachedInputPerMTok())
                                .outputPerMTok(m.outputPerMTok())))
                .toList();
    }

    @Override
    public ModelProfiles modelProfiles() {
        return new ModelProfiles(props.serverName(), upstream.implementation(), profiles());
    }

    @Override
    public synchronized Lease acquire(final String profileId, final String sessionRef) {
        final var m = model(profileId);
        final var active = active(profileId);
        if (m.maxLeases() != null && active.size() >= m.maxLeases()) {
            throw new ApiException(HttpStatus.CONFLICT, "lease_conflict",
                    "The instance budget of " + profileId + " (" + m.maxLeases() + ") is in use", active);
        }
        final var lease = new LeaseRecord(profileId, sessionRef, LeaseState.READY);
        leases.put(lease.id, lease);
        return lease.toApi();
    }

    private double cost(final LeaseRecord l) {
        final var m = model(l.profileId);
        synchronized (l) {
            return ((l.promptTokens - l.cachedTokens) * m.inputPerMTok() + l.cachedTokens * m.cachedInputPerMTok()
                    + l.completionTokens * m.outputPerMTok()) / 1_000_000.0;
        }
    }

    @Override
    public DeckState state() {
        final var spend = all().stream().mapToDouble(this::cost).sum();
        return new DeckState(props.serverName(), upstream.implementation(), profiles(), recent())
                .provider(upstream.provider()).spendUsd(spend);
    }

    @Override
    public StreamingResponseBody chat(final String leaseId, final String providerAuthorization, final JsonNode request) {
        if (providerAuthorization == null || providerAuthorization.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_error",
                    "Missing X-Provider-Authorization: this DECK server forwards the provider credential it receives (D104)");
        }
        final var lease = activeRecord(leaseId);
        final var m = model(lease.profileId);
        final var body = (ObjectNode) request.deepCopy();
        body.put("model", m.model());
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        final var base = upstream.baseUrl().endsWith("/") ? upstream.baseUrl() : upstream.baseUrl() + "/";
        final var upstreamRequest = HttpRequest.newBuilder(URI.create(base + "chat/completions"))
                .header("Authorization", providerAuthorization)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        final var name = "Provider " + upstream.provider();
        final HttpResponse<java.io.InputStream> response;
        try {
            response = http.send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "upstream_error", name + " unreachable: " + e.getMessage());
        }
        if (response.statusCode() != 200) {
            final String text;
            try (var in = response.body()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "upstream_error", name + " error " + response.statusCode());
            }
            final var status = switch (response.statusCode()) {
                case 429 -> HttpStatus.TOO_MANY_REQUESTS;
                case 401, 403 -> HttpStatus.UNAUTHORIZED;
                case 400, 404, 422 -> HttpStatus.BAD_REQUEST;
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
                    if (line.startsWith("data:")) {
                        final var data = line.substring(5).trim();
                        if (!data.equals("[DONE]") && data.startsWith("{")) {
                            final var chunk = mapper.readTree(data);
                            final var usage = chunk.path("usage");
                            if (usage.isObject()) {
                                sawUsage = true;
                                lease.addUsage(Json.number(usage.path("prompt_tokens")),
                                        Json.number(usage.path("prompt_tokens_details").path("cached_tokens")),
                                        Json.number(usage.path("completion_tokens")), false);
                            }
                            final var choices = chunk.path("choices");
                            for (int i = 0; i < choices.size(); i++) {
                                chars += Json.text(choices.get(i).path("delta").path("content")).length();
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
                    lease.addUsage(prompt, 0, Math.max(1, chars / 4), true);
                }
                out.flush();
            }
        };
        return stream;
    }
}
