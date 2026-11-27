package ai.strgm.types;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;

/** The session a DECK lease is for. Opaque to DECK; DEALER sends {@code <client>/<session id>}. For example, {@code agent-a/a-1}. */
public record SessionRef(String value) implements MicroType<String> {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public SessionRef {
        requireNonNull(value, "value");
    }

    /** The reference DEALER sends for a client's session. */
    public static SessionRef of(final ClientName client, final SessionId session) {
        return new SessionRef(client.value() + "/" + session.value());
    }

    @Override
    public String toString() {
        return value;
    }
}
