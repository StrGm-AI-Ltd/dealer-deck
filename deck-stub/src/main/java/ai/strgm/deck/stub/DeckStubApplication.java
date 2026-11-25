package ai.strgm.deck.stub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** The simulated DECK server (v0.5), on the web layer the deck module shares with every DECK server. */
@SpringBootApplication(scanBasePackages = "ai.strgm.deck")
@ConfigurationPropertiesScan("ai.strgm.deck")
public class DeckStubApplication {
    public static void main(final String[] args) {
        SpringApplication.run(DeckStubApplication.class, args);
    }
}
