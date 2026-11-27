package ai.strgm.dealer;

import static java.lang.Thread.currentThread;

import ai.strgm.dealer.DealerProperties.ClientConfig;
import ai.strgm.types.ModelName;
import ai.strgm.types.SessionId;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * POST /v1/chat/completions. Described in the DEALER OpenAPI document but hand-written, because it streams
 * server-sent events and the generated Spring interfaces return one object (the streaming exception, D100).
 */
@RestController
public class ChatController {
    private final Scheduler scheduler;
    private final ObjectMapper mapper;
    /** {@code X-Session-Id}'s maximum length: part of the API, as the {@code SessionId} schema documents it. */
    private static final int MAX_SESSION_ID_LENGTH = 200;
    /** The prefix of a server-sent event's data line. */
    private static final String SSE_DATA_PREFIX = "data:";

    private final HttpClient http;
    /** How long one completion may take, streaming included. */
    private final Duration requestTimeout;

    public ChatController(final Scheduler scheduler, final ObjectMapper mapper, final DealerProperties properties) {
        this.scheduler = scheduler;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.timeouts().chatConnectSeconds())).build();
        this.requestTimeout = Duration.ofMinutes(properties.timeouts().chatRequestMinutes());
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody final String raw,
            @RequestHeader(value = "X-Session-Id", required = false) final @Nullable String sessionId,
            @RequestHeader(value = "X-Request-Priority", required = false) final @Nullable Integer requestPriority,
            final HttpServletRequest servletRequest) {
        final var client = (ClientConfig) servletRequest.getAttribute(AuthFilter.CLIENT);
        if (client == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_error", "A client API key is required");
        }
        final JsonNode body;
        try {
            body = mapper.readTree(raw);
        } catch (JacksonException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", "The request body isn't valid JSON");
        }
        if (!(body.path("stream").isBoolean() && body.path("stream").booleanValue())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", "DEALER streams only in v0.5: set \"stream\": true");
        }
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > MAX_SESSION_ID_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", "Missing or invalid required header X-Session-Id");
        }
        if (requestPriority != null && !Priorities.RANGE.contains(requestPriority)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error",
                    "X-Request-Priority must be between " + Priorities.MIN + " and " + Priorities.MAX, "X-Request-Priority");
        }
        final var requestedModel = body.path("model").isString() ? body.path("model").asString() : "";
        if (requestedModel.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", "Missing \"model\"");
        }
        final var model = new ModelName(requestedModel);
        // TODO(#62): say which DECK servers and model profiles the client may use, instead of its external-routing flag
        if (scheduler.candidates(client, model).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "model_not_found",
                    "No DECK profile serves \"" + model + "\" for client " + client.name()
                            + (client.externalRouting() ? "" : " (external routing is off for this client)"));
        }
        final var priority = requestPriority == null ? Priorities.DEFAULT_REQUEST : requestPriority;
        final var placement = scheduler.acquire(client, new SessionId(sessionId), model, priority);
        final var server = placement.profile().server();
        final var forward = (ObjectNode) body.deepCopy();
        forward.put("model", placement.profile().profile().getModel().value());
        final var clientWantsUsage = body.path("stream_options").path("include_usage").isBoolean()
                && body.path("stream_options").path("include_usage").booleanValue();
        forward.putObject("stream_options").put("include_usage", true);
        final var request = HttpRequest.newBuilder(URI.create(server.config().url() + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + server.config().key())
                .header("X-Lease-Id", placement.leaseId().value())
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(forward.toString()));
        if (server.providerAuthorization() != null) {
            request.header("X-Provider-Authorization", server.providerAuthorization());
        }
        final HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception exception) {
            scheduler.release(placement, 0, 0, 0);
            if (exception instanceof InterruptedException) {
                currentThread().interrupt();
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "DECK server " + server.name() + " unreachable");
        }
        if (response.statusCode() != HttpStatus.OK.value()) {
            scheduler.release(placement, 0, 0, 0);
            String text;
            try (var responseBody = response.body()) {
                text = new String(responseBody.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception exception) {
                text = "";
            }
            throw new ApiException(HttpStatus.valueOf(response.statusCode()), text.isBlank()
                    ? "{\"error\":{\"message\":\"DECK error " + response.statusCode() + "\",\"type\":\"server_error\"}}" : text);
        }
        final StreamingResponseBody stream = out -> {
            long prompt = 0;
            long cached = 0;
            long completion = 0;
            boolean skipBlank = false;
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(SSE_DATA_PREFIX)) {
                        final var data = line.substring(SSE_DATA_PREFIX.length()).trim();
                        if (data.startsWith("{")) {
                            final var chunk = mapper.readTree(data);
                            final var usage = chunk.path("usage");
                            if (usage.isObject()) {
                                prompt = number(usage.path("prompt_tokens"));
                                cached = number(usage.path("prompt_tokens_details").path("cached_tokens"));
                                completion = number(usage.path("completion_tokens"));
                                // Nebius attaches usage to every chunk: only a usage-only chunk (no choices) is the
                                // one the client didn't ask for, so a chunk that carries text is always forwarded
                                if (!clientWantsUsage && chunk.path("choices").isEmpty()) {
                                    skipBlank = true;
                                    continue;
                                }
                            }
                        }
                    }
                    if (line.isEmpty() && skipBlank) {
                        skipBlank = false;
                        continue;
                    }
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    if (line.isEmpty()) {
                        out.flush();
                    }
                }
            } finally {
                scheduler.release(placement, prompt, cached, completion);
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header("X-Dealer-Server", server.name().value())
                .header("X-Dealer-Profile", placement.profile().profile().getId().value())
                .body(stream);
    }

    private static long number(final JsonNode node) {
        return node.isNumber() ? node.longValue() : 0;
    }
}
