package ai.strgm.deck;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The streaming chat endpoint. It's described in the DECK OpenAPI document, but hand-written: the generated
 * Spring interfaces return one object, and this endpoint streams server-sent events (the streaming exception, D100).
 */
@RestController
public class ChatController {
    private final DeckServer server;
    private final ObjectMapper mapper;

    public ChatController(final DeckServer server, final ObjectMapper mapper) {
        this.server = server;
        this.mapper = mapper;
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<StreamingResponseBody> chat(@RequestHeader(value = "X-Lease-Id", required = false) final @Nullable String leaseId,
            @RequestHeader(value = "X-Provider-Authorization", required = false) final @Nullable String providerAuthorization,
            @RequestBody final String body) {
        if (leaseId == null || leaseId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", "Missing required header X-Lease-Id");
        }
        final JsonNode request;
        try {
            request = mapper.readTree(body);
        } catch (JacksonException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", "The request body isn't valid JSON");
        }
        if (!Json.bool(request.path("stream"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", "Streaming only: set \"stream\": true");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(server.chat(leaseId, providerAuthorization, request));
    }
}
