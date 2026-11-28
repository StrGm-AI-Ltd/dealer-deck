package ai.strgm.deck.controller;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;

import ai.strgm.deck.service.DeckServer;
import ai.strgm.types.ChatRequest;
import ai.strgm.types.LeaseId;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
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
            @RequestBody final String rawBody) {
        if (leaseId == null || leaseId.isBlank()) {
            throw new ApiException(BAD_REQUEST, "invalid_request_error", "Missing required header X-Lease-Id");
        }
        final ChatRequest chatRequest;
        try {
            chatRequest = ChatRequest.parse(rawBody, mapper);
        } catch (JacksonException exception) {
            throw new ApiException(BAD_REQUEST, "invalid_request_error", "The request body isn't valid JSON");
        }
        if (!chatRequest.stream()) {
            throw new ApiException(BAD_REQUEST, "invalid_request_error", "Streaming only: set \"stream\": true");
        }
        // TODO(#111): reject with 400 a request whose messages isn't an array of objects, or that has no model
        final var chatStream = server.openChatStream(new LeaseId(leaseId), providerAuthorization, chatRequest);
        final StreamingResponseBody responseBody = chatStream::writeTo;
        return ResponseEntity.ok().contentType(TEXT_EVENT_STREAM).body(responseBody);
    }
}
