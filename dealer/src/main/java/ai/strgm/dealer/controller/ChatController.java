package ai.strgm.dealer.controller;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;

import ai.strgm.dealer.config.DealerProperties.ClientConfig;
import ai.strgm.dealer.security.AuthFilter;
import ai.strgm.dealer.service.ChatService;
import ai.strgm.types.ModelName;
import ai.strgm.types.Priority;
import ai.strgm.types.SessionId;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * POST /v1/chat/completions. Described in the DEALER OpenAPI document but hand-written, because it streams
 * server-sent events and the generated Spring interfaces return one object (the streaming exception, D100). It
 * validates the request and streams what {@link ChatService} opens.
 */
@RestController
public class ChatController {
    private final ChatService chatService;
    private final ObjectMapper mapper;

    public ChatController(final ChatService chatService, final ObjectMapper mapper) {
        this.chatService = chatService;
        this.mapper = mapper;
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody final String rawBody,
            @RequestHeader(value = "X-Session-Id", required = false) final @Nullable String sessionIdHeader,
            @RequestHeader(value = "X-Request-Priority", required = false) final @Nullable Integer requestPriority,
            final HttpServletRequest servletRequest) {
        final var clientConfig = (ClientConfig) servletRequest.getAttribute(AuthFilter.CLIENT_CONFIG_ATTRIBUTE);
        if (clientConfig == null) {
            throw new ApiException(UNAUTHORIZED, "authentication_error", "A client API key is required");
        }
        final JsonNode body;
        try {
            body = mapper.readTree(rawBody);
        } catch (JacksonException exception) {
            throw new ApiException(BAD_REQUEST, "invalid_request_error", "The request body isn't valid JSON");
        }
        if (!(body.path("stream").isBoolean() && body.path("stream").booleanValue())) {
            throw new ApiException(BAD_REQUEST, "invalid_request_error", "DEALER streams only in v0.5: set \"stream\": true");
        }
        if (sessionIdHeader == null) {
            throw invalidSessionId();
        }
        final SessionId sessionId;
        try {
            sessionId = new SessionId(sessionIdHeader);
        } catch (IllegalArgumentException exception) {
            throw invalidSessionId();
        }
        final Priority priority;
        try {
            priority = requestPriority == null ? Priority.DEFAULT_REQUEST : new Priority(requestPriority);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(BAD_REQUEST, "invalid_request_error",
                    "X-Request-Priority must be between " + Priority.MIN + " and " + Priority.MAX, "X-Request-Priority");
        }
        final var requestedModel = body.path("model").isString() ? body.path("model").asString() : "";
        if (requestedModel.isBlank()) {
            throw new ApiException(BAD_REQUEST, "invalid_request_error", "Missing \"model\"");
        }
        final var chatStream = chatService.open(clientConfig, sessionId, new ModelName(requestedModel), priority, body);
        final StreamingResponseBody stream = chatStream::relayTo;
        return ResponseEntity.ok().contentType(TEXT_EVENT_STREAM)
                .header("X-Dealer-Server", chatStream.serverName().value())
                .header("X-Dealer-Profile", chatStream.profileId().value())
                .body(stream);
    }

    private static ApiException invalidSessionId() {
        return new ApiException(BAD_REQUEST, "invalid_request_error", "Missing or invalid required header X-Session-Id");
    }
}
