package ai.strgm.dealer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** DEALER: one OpenAI-compatible endpoint that routes agent sessions across a DECK of LLM servers. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DealerApplication {
    public static void main(final String[] args) {
        SpringApplication.run(DealerApplication.class, args);
    }
}
