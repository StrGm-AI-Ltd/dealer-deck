package ai.strgm.deck.client;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE;

import ai.strgm.types.LeaseId;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * The DECK API's chat completion as a stream. The generated {@code ChatApi} reads the response as one chunk; this one
 * returns the server-sent events as they arrive, which a {@code RestClient}-backed HTTP interface supports.
 */
@HttpExchange
interface ChatStreamApi {
    @PostExchange(url = "/v1/chat/completions", contentType = APPLICATION_JSON_VALUE, accept = {APPLICATION_JSON_VALUE, TEXT_EVENT_STREAM_VALUE})
    InputStream openChatStream(@RequestHeader("X-Lease-Id") LeaseId leaseId,
            @RequestHeader(value = "X-Provider-Authorization", required = false) @Nullable String providerAuthorization,
            @RequestBody String chatRequest);
}
