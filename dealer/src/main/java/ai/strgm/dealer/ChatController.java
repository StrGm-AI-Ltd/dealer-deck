package ai.strgm.dealer;

import ai.strgm.dealer.DealerProperties.ClientDef;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ChatController(final Scheduler scheduler, final ObjectMapper mapper) {
        this.scheduler = scheduler;
        this.mapper = mapper;
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody final String raw,
            @RequestHeader(value = "X-Session-Id", required = false) final String sessionId,
            @RequestHeader(value = "X-Request-Priority", required = false) final Integer requestPriority,
            final HttpServletRequest servletRequest) {
        final var client = (ClientDef) servletRequest.getAttribute(AuthFilter.CLIENT);
        if (client == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_error", "A client API key is required");
        }
        final JsonNode body = mapper.readTree(raw);
        if (!(body.path("stream").isBoolean() && body.path("stream").booleanValue())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", "DEALER streams only in v0.5: set \"stream\": true");
        }
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", "Missing or invalid required header X-Session-Id");
        }
        final var model = body.path("model").isString() ? body.path("model").asString() : "";
        if (model.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", "Missing \"model\"");
        }
        if (scheduler.candidates(client, model).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "model_not_found",
                    "No DECK profile serves \"" + model + "\" for client " + client.name()
                            + (client.externalRouting() ? "" : " (external routing is off for this client)"));
        }
        final var priority = requestPriority == null ? 50 : Math.max(1, Math.min(100, requestPriority));
        final var placement = scheduler.acquire(client, sessionId, model, priority);
        final var server = placement.profile().server();
        final var forward = (ObjectNode) body.deepCopy();
        forward.put("model", placement.profile().profile().getModel());
        final var clientWantsUsage = body.path("stream_options").path("include_usage").isBoolean()
                && body.path("stream_options").path("include_usage").booleanValue();
        forward.putObject("stream_options").put("include_usage", true);
        final var request = HttpRequest.newBuilder(URI.create(server.def().url() + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + server.def().key())
                .header("X-Lease-Id", placement.leaseId())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(forward.toString()));
        if (server.providerAuthorization() != null) {
            request.header("X-Provider-Authorization", server.providerAuthorization());
        }
        final HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            scheduler.release(placement, 0, 0, 0);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "DECK server " + server.name() + " unreachable");
        }
        if (response.statusCode() != 200) {
            scheduler.release(placement, 0, 0, 0);
            String text;
            try (var in = response.body()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
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
                    if (line.startsWith("data:")) {
                        final var data = line.substring(5).trim();
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
                .header("X-Dealer-Server", server.name())
                .header("X-Dealer-Profile", placement.profile().profile().getId())
                .body(stream);
    }

    private static long number(final JsonNode n) {
        return n.isNumber() ? n.longValue() : 0;
    }
}
