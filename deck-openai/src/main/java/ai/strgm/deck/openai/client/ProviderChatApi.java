package ai.strgm.deck.openai.client;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE;

import java.io.InputStream;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** An OpenAI-compatible provider's streamed chat completion: its server-sent events come back as they arrive. */
@HttpExchange
interface ProviderChatApi {
    @PostExchange(url = "chat/completions", contentType = APPLICATION_JSON_VALUE, accept = TEXT_EVENT_STREAM_VALUE)
    InputStream openChatStream(@RequestHeader("Authorization") String providerAuthorization, @RequestBody String chatRequest);
}
