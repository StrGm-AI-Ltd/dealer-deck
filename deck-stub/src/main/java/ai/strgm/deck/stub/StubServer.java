package ai.strgm.deck.stub;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.round;
import static java.lang.System.currentTimeMillis;
import static java.lang.Thread.currentThread;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpStatus.CONFLICT;

import ai.strgm.deck.AbstractDeckServer;
import ai.strgm.deck.ApiException;
import ai.strgm.deck.DeckProperties;
import ai.strgm.deck.Json;
import ai.strgm.deck.LeaseRecord;
import ai.strgm.deck.api.model.DeckState;
import ai.strgm.deck.api.model.Implementation;
import ai.strgm.deck.api.model.LeaseReduction;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.deck.api.model.Limitations;
import ai.strgm.deck.api.model.ModelProfile;
import ai.strgm.deck.api.model.ModelProfiles;
import ai.strgm.deck.api.model.Prices;
import ai.strgm.deck.api.model.Quantization;
import ai.strgm.deck.api.model.Readiness;
import ai.strgm.deck.api.model.SpeedPoint;
import ai.strgm.deck.api.model.Worker;
import ai.strgm.deck.api.model.WorkerState;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ModelName;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The local DECK server, simulated (v0.5): three profiles over two workers. small-a and small-b use one
 * worker each; large needs both, so leasing it is blocked by both small leases. Loading, time to first token
 * and the prefill and decode speeds, which fall as the context grows, are simulated and scaled by
 * {@code deck.time-factor}. A request streams at the speeds the profile advertises for its prompt's size.
 */
@Component
public class StubServer extends AbstractDeckServer {

    record Speed(int contextTokens, double prefillTps, double decodeTps) {}

    record ProfileDef(ProfileId id, ModelName model, int contextWindow, List<String> workers, long loadMs, long ttftMs,
            List<Speed> speeds, String quantization, double bitsPerWeight) {}

    /** Each profile allows one lease, so a lease on one profile takes one lease from every profile it overlaps. */
    private static final int LEASES_PER_PROFILE = 1;
    /** The completion length when a request doesn't set {@code max_tokens}. */
    private static final int DEFAULT_MAX_TOKENS = 40;
    private static final double MILLIS_PER_SECOND = 1000.0;
    /** A worker's loaded profile, while a profile is loading onto it, is {@code loading <profile id>}. */
    private static final String LOADING_PREFIX = "loading ";

    private static final List<Speed> SMALL_SPEEDS = List.of(
            new Speed(4096, 800, 40), new Speed(16384, 600, 34), new Speed(32768, 450, 28));
    private static final List<Speed> LARGE_SPEEDS = List.of(
            new Speed(4096, 400, 25), new Speed(32768, 280, 20), new Speed(131072, 150, 14));

    private static final List<ProfileDef> PROFILE_DEFS = List.of(
            new ProfileDef(new ProfileId("small-a"), new ModelName("stub/small-a"), 32768, List.of("w1"), 3000, 500, SMALL_SPEEDS, "q4_k_m", 4),
            new ProfileDef(new ProfileId("small-b"), new ModelName("stub/small-b"), 32768, List.of("w2"), 3000, 500, SMALL_SPEEDS, "q4_k_m", 4),
            new ProfileDef(new ProfileId("large"), new ModelName("stub/large"), 131072, List.of("w1", "w2"), 6000, 900, LARGE_SPEEDS, "q8_0", 8));

    private static final String[] WORDS = ("Here is a short answer from the simulated DECK stub. Prefer small, "
            + "focused functions, name things for what they do, test the behaviour rather than the implementation, "
            + "and keep side effects at the edges of the system so the core stays easy to reason about.").split(" ");

    private final StubProperties stub;
    private final Map<String, String> loaded = new LinkedHashMap<>(Map.of("w1", "small-a", "w2", "small-b"));

    public StubServer(final DeckProperties properties, final StubProperties stub, final ObjectMapper mapper) {
        super(properties, mapper);
        this.stub = stub;
        loaded.clear();
        loaded.put("w1", "small-a");
        loaded.put("w2", "small-b");
    }

    private long scaled(final long millis) {
        return round(millis * stub.timeFactor());
    }

    private ProfileDef profileDefFor(final ProfileId id) {
        return PROFILE_DEFS.stream()
                .filter(definition -> definition.id().equals(id))
                .findFirst()
                .orElseThrow(() -> unknownProfile(id));
    }

    private boolean overlaps(final ProfileDef first, final ProfileDef second) {
        return first.workers().stream()
                .anyMatch(second.workers()::contains);
    }

    /** The profile's speeds at {@code contextTokens}: linear between the two nearest points, clamped outside them. */
    private static Speed speedAt(final ProfileDef profileDef, final long contextTokens) {
        final var points = profileDef.speeds();
        if (contextTokens <= points.getFirst().contextTokens()) {
            return points.getFirst();
        }
        for (int pointIndex = 1; pointIndex < points.size(); pointIndex++) {
            final var upper = points.get(pointIndex);
            if (contextTokens <= upper.contextTokens()) {
                final var lower = points.get(pointIndex - 1);
                final var fraction = (double) (contextTokens - lower.contextTokens()) / (upper.contextTokens() - lower.contextTokens());
                return new Speed((int) contextTokens, lower.prefillTps() + fraction * (upper.prefillTps() - lower.prefillTps()),
                        lower.decodeTps() + fraction * (upper.decodeTps() - lower.decodeTps()));
            }
        }
        return points.getLast();
    }

    private ModelProfile toApi(final ProfileDef profileDef) {
        final boolean isLoaded;
        synchronized (lock) {
            isLoaded = profileDef.workers().stream()
                    .allMatch(worker -> profileDef.id().value().equals(loaded.get(worker)));
        }
        // a lease here takes a worker that every overlapping profile needs
        final var reductions = PROFILE_DEFS.stream()
                .filter(other -> other != profileDef && overlaps(other, profileDef))
                .map(other -> new LeaseReduction(other.id(), LEASES_PER_PROFILE))
                .toList();
        return new ModelProfile(profileDef.id(), profileDef.model(), profileDef.contextWindow(), isLoaded ? Readiness.LOADED : Readiness.UNLOADED)
                .loadingTimeMs((int) scaled(profileDef.loadMs()))
                .quantization(new Quantization(profileDef.quantization()).bitsPerWeight(profileDef.bitsPerWeight()))
                .limitations(new Limitations().leaseReductions(reductions))
                .prices(new Prices().inputPerMTok(0.0).cachedInputPerMTok(0.0).outputPerMTok(0.0))
                .maxLeases(LEASES_PER_PROFILE).activeLeases(active(profileDef.id()).size())
                .speeds(profileDef.speeds().stream()
                        .map(point -> new SpeedPoint(point.contextTokens(),
                                point.prefillTps() / stub.timeFactor(), point.decodeTps() / stub.timeFactor()))
                        .toList());
    }

    @Override
    public ModelProfiles modelProfiles() {
        final var profiles = PROFILE_DEFS.stream()
                .map(this::toApi)
                .toList();
        return new ModelProfiles(properties.serverName(), Implementation.STUB, profiles);
    }

    @Override
    protected LeaseRecord grant(final ProfileId profileId, final SessionRef sessionRef) {
        final var profileDef = profileDefFor(profileId);
        final var blockers = activeRecords().stream()
                .filter(other -> overlaps(profileDefFor(other.profileId()), profileDef))
                .map(LeaseRecord::id)
                .toList();
        if (!blockers.isEmpty()) {
            throw new ApiException(CONFLICT, "lease_conflict",
                    "Profile " + profileId + " is blocked by active leases", blockers);
        }
        final var isLoaded = profileDef.workers().stream()
                .allMatch(worker -> profileDef.id().value().equals(loaded.get(worker)));
        final var leaseRecord = register(profileId, sessionRef, isLoaded ? LeaseState.READY : LeaseState.LOADING);
        if (!isLoaded) {
            profileDef.workers().forEach(worker -> loaded.put(worker, LOADING_PREFIX + profileDef.id()));
            Thread.ofVirtual().start(() -> {
                sleep(scaled(profileDef.loadMs()));
                synchronized (lock) {
                    profileDef.workers().forEach(worker -> loaded.put(worker, profileDef.id().value()));
                    markReady(leaseRecord);
                }
            });
        }
        return leaseRecord;
    }

    @Override
    public DeckState state() {
        final List<Worker> workers;
        synchronized (lock) {
            final var active = activeRecords();
            workers = loaded.entrySet().stream()
                    .map(worker -> {
                        final var busy = active.stream()
                                .anyMatch(leaseRecord -> profileDefFor(leaseRecord.profileId()).workers().contains(worker.getKey()));
                        return new Worker().id(worker.getKey()).loadedProfile(worker.getValue())
                                .state(worker.getValue().startsWith(LOADING_PREFIX) ? WorkerState.LOADING
                                        : busy ? WorkerState.LEASED : WorkerState.IDLE);
                    })
                    .toList();
        }
        return new DeckState(properties.serverName(), Implementation.STUB, modelProfiles().getProfiles(), recent())
                .workers(workers).spendUsd(0.0);
    }

    @Override
    public StreamingResponseBody chat(final LeaseId leaseId, final @Nullable String providerAuthorization, final JsonNode request) {
        final var leaseRecord = activeRecord(leaseId);
        final var profileDef = profileDefFor(leaseRecord.profileId());
        final var maxTokens = (int) max(1, min(Json.number(request.path("max_tokens")) == 0 ? DEFAULT_MAX_TOKENS
                : Json.number(request.path("max_tokens")), WORDS.length));
        final var includeUsage = Json.bool(request.path("stream_options").path("include_usage"));
        final var prompt = Json.promptTokens(request);
        final var speed = speedAt(profileDef, prompt);
        final StreamingResponseBody body = out -> {
            final var deadline = currentTimeMillis() + Duration.ofSeconds(stub.loadingWaitSeconds()).toMillis();
            while (isLoading(leaseRecord) && currentTimeMillis() < deadline) {
                sleep(stub.loadingPollMillis());
            }
            sleep(scaled(profileDef.ttftMs()) + round(prompt * MILLIS_PER_SECOND / speed.prefillTps() * stub.timeFactor()));
            final var id = "chatcmpl-" + leaseRecord.id();
            final var created = Instant.now().getEpochSecond();
            for (int tokenIndex = 0; tokenIndex < maxTokens; tokenIndex++) {
                final var chunk = mapper.createObjectNode();
                chunk.put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", profileDef.model().value());
                final var choice = chunk.putArray("choices").addObject();
                choice.put("index", 0);
                final var delta = choice.putObject("delta");
                if (tokenIndex == 0) {
                    delta.put("role", "assistant");
                }
                delta.put("content", WORDS[tokenIndex] + " ");
                choice.putNull("finish_reason");
                write(out, chunk.toString());
                sleep(round(MILLIS_PER_SECOND / speed.decodeTps() * stub.timeFactor()));
            }
            final var last = mapper.createObjectNode();
            last.put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", profileDef.model().value());
            final var choice = last.putArray("choices").addObject();
            choice.put("index", 0);
            choice.putObject("delta");
            choice.put("finish_reason", "stop");
            write(out, last.toString());
            if (includeUsage) {
                final var usage = mapper.createObjectNode();
                usage.put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", profileDef.model().value());
                usage.putArray("choices");
                final var counts = usage.putObject("usage");
                counts.put("prompt_tokens", prompt).put("completion_tokens", maxTokens).put("total_tokens", prompt + maxTokens);
                counts.putObject("prompt_tokens_details").put("cached_tokens", 0);
                write(out, usage.toString());
            }
            addUsage(leaseRecord, prompt, 0, maxTokens, false);
            out.write("data: [DONE]\n\n".getBytes(UTF_8));
            out.flush();
        };
        return body;
    }

    private static void write(final java.io.OutputStream out, final String json) throws java.io.IOException {
        out.write(("data: " + json + "\n\n").getBytes(UTF_8));
        out.flush();
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(max(0, millis));
        } catch (InterruptedException exception) {
            currentThread().interrupt();
        }
    }
}
