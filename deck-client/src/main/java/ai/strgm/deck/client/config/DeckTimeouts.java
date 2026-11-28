package ai.strgm.deck.client.config;

import java.time.Duration;

/**
 * How long to wait for a DECK server: to connect and to read, for the lease, profile and state calls; and to connect
 * and for a chat completion's response to start, which can take minutes while a model loads.
 */
public record DeckTimeouts(Duration connect, Duration read, Duration chatConnect, Duration chatResponse) {}
