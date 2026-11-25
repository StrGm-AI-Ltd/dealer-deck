package ai.strgm.deck.stub;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code timeFactor} scales every simulated duration, e.g. 0.5 runs the stub twice as fast. */
@ConfigurationProperties("deck")
public record StubProperties(double timeFactor) {}
