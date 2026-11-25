package ai.strgm.deck.openai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The DECK server backed by an OpenAI-compatible provider, on the web layer the deck module shares with every
 * DECK server.
 */
@SpringBootApplication(scanBasePackages = "ai.strgm.deck")
@ConfigurationPropertiesScan("ai.strgm.deck")
public class DeckOpenAiApplication {
    public static void main(final String[] args) {
        SpringApplication.run(DeckOpenAiApplication.class, args);
    }
}
