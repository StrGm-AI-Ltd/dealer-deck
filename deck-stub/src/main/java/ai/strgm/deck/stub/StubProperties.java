package ai.strgm.deck.stub;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code timeFactor} scales every simulated duration, e.g. 0.5 runs the stub twice as fast. A chat request on a lease
 * that's still loading waits up to {@code loadingWaitSeconds} for it, checking every {@code loadingPollMillis}.
 */
@ConfigurationProperties("deck")
public record StubProperties(double timeFactor, int loadingWaitSeconds, int loadingPollMillis) {}
