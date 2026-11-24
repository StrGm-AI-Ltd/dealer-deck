package ai.strgm.deck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** A DECK server. The Spring profile picks the implementation: {@code stub} or {@code external}. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DeckApplication {
    public static void main(final String[] args) {
        SpringApplication.run(DeckApplication.class, args);
    }
}
