package ai.strgm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** What DEALER and DECK read from a chat request, and that they keep its JSON as the client sent it. */
class ChatRequestTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String FULL_REQUEST = """
            {"model":"small-a","stream":true,"stream_options":{"include_usage":true},"max_tokens":60,"temperature":0.2,
             "messages":[{"role":"system","content":"Be brief."},{"role":"user","content":"hi"}]}""";

    @Test
    void itReadsTheFieldsDealerAndDeckActOnAndKeepsTheJson() {
        final var chatRequest = ChatRequest.parse(FULL_REQUEST, MAPPER);
        assertEquals(new ModelName("small-a"), chatRequest.model());
        assertTrue(chatRequest.stream());
        assertTrue(chatRequest.includeUsage());
        assertEquals(60, chatRequest.maxTokens());
        assertEquals("Be brief.".length() + "hi".length(), chatRequest.promptChars());
        assertEquals(FULL_REQUEST, chatRequest.json());
    }

    @Test
    void aMissingBlankOrNonTextModelIsNone() {
        assertNull(ChatRequest.parse("{\"stream\":true}", MAPPER).model());
        assertNull(ChatRequest.parse("{\"model\":\" \"}", MAPPER).model());
        assertNull(ChatRequest.parse("{\"model\":5}", MAPPER).model());
    }

    @Test
    void fieldsThatAreMissingOrOfAnotherTypeReadAsFalseOrZero() {
        for (final var json : new String[] {"{}", "[1]", "{\"stream\":\"yes\",\"max_tokens\":\"60\",\"stream_options\":true}"}) {
            final var chatRequest = ChatRequest.parse(json, MAPPER);
            assertFalse(chatRequest.stream(), json);
            assertFalse(chatRequest.includeUsage(), json);
            assertEquals(0, chatRequest.maxTokens(), json);
            assertEquals(0, chatRequest.promptChars(), json);
        }
    }

    @Test
    void invalidJsonThrows() {
        assertThrows(JacksonException.class, () -> ChatRequest.parse("{\"model\":", MAPPER));
    }
}
