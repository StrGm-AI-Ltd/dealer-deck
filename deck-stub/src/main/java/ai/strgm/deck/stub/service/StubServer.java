package ai.strgm.deck.stub.service;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.round;
import static java.lang.System.currentTimeMillis;
import static java.lang.Thread.currentThread;
import static java.nio.charset.StandardCharsets.UTF_8;

import ai.strgm.deck.service.AbstractDeckServer;
import ai.strgm.deck.service.ChatStream;
import ai.strgm.deck.config.DeckProperties;
import ai.strgm.deck.stub.config.StubProperties;
import ai.strgm.deck.util.Json;
import ai.strgm.deck.exception.LeaseConflictException;
import ai.strgm.deck.model.LeaseRecord;
import ai.strgm.deck.model.OfferedProfile;
import ai.strgm.deck.model.OfferedProfile.LeaseReduction;
import ai.strgm.deck.model.OfferedProfile.Prices;
import ai.strgm.deck.model.OfferedProfile.Quantization;
import ai.strgm.deck.model.OfferedProfile.SpeedPoint;
import ai.strgm.deck.model.OfferedProfiles;
import ai.strgm.deck.model.ServerState;
import ai.strgm.deck.model.ServerState.Worker;
import ai.strgm.deck.exception.UnknownProfileException;
import ai.strgm.deck.api.model.Implementation;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.deck.api.model.Readiness;
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
import org.springframework.stereotype.Component;
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

    /** The simulated hardware is the operator's own: its tokens cost nothing. */
    private static final Prices FREE = new Prices(0, 0, 0);
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

    private final StubProperties stubProperties;
    private final Map<String, String> loaded = new LinkedHashMap<>(Map.of("w1", "small-a", "w2", "small-b"));

    public StubServer(final DeckProperties properties, final StubProperties stubProperties, final ObjectMapper mapper) {
        super(properties, mapper);
        this.stubProperties = stubProperties;
        loaded.clear();
        loaded.put("w1", "small-a");
        loaded.put("w2", "small-b");
    }

    private long scaled(final long millis) {
        return round(millis * stubProperties.timeFactor());
    }

    private ProfileDef profileDefFor(final ProfileId id) {
        return PROFILE_DEFS.stream()
                .filter(definition -> definition.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new UnknownProfileException(id));
    }

    private boolean overlaps(final ProfileDef first, final ProfileDef second) {
        return first.workers().stream()
                .anyMatch(second.workers()::contains);
    }

    /** The profile's speeds at {@code contextTokens}: linear between the two nearest points, clamped outside them. */
    private static Speed speedAt(final ProfileDef profileDef, final long contextTokens) {
        final var speedPoints = profileDef.speeds();
        if (contextTokens <= speedPoints.getFirst().contextTokens()) {
            return speedPoints.getFirst();
        }
        for (int pointIndex = 1; pointIndex < speedPoints.size(); pointIndex++) {
            final var upperPoint = speedPoints.get(pointIndex);
            if (contextTokens <= upperPoint.contextTokens()) {
                final var lowerPoint = speedPoints.get(pointIndex - 1);
                final var fractionBetweenPoints = (double) (contextTokens - lowerPoint.contextTokens())
                        / (upperPoint.contextTokens() - lowerPoint.contextTokens());
                return new Speed((int) contextTokens,
                        lowerPoint.prefillTps() + fractionBetweenPoints * (upperPoint.prefillTps() - lowerPoint.prefillTps()),
                        lowerPoint.decodeTps() + fractionBetweenPoints * (upperPoint.decodeTps() - lowerPoint.decodeTps()));
            }
        }
        return speedPoints.getLast();
    }

    private OfferedProfile offeredProfile(final ProfileDef profileDef) {
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
        final var speeds = profileDef.speeds().stream()
                .map(point -> new SpeedPoint(point.contextTokens(),
                        point.prefillTps() / stubProperties.timeFactor(), point.decodeTps() / stubProperties.timeFactor()))
                .toList();
        return new OfferedProfile(profileDef.id(), profileDef.model(), null, profileDef.contextWindow(),
                new Quantization(profileDef.quantization(), profileDef.bitsPerWeight()), isLoaded ? Readiness.LOADED : Readiness.UNLOADED,
                (int) scaled(profileDef.loadMs()), reductions, FREE, LEASES_PER_PROFILE, activeLeaseIds(profileDef.id()).size(), speeds);
    }

    @Override
    public OfferedProfiles modelProfiles() {
        final var profiles = PROFILE_DEFS.stream()
                .map(this::offeredProfile)
                .toList();
        return new OfferedProfiles(properties.serverName(), Implementation.STUB, profiles);
    }

    @Override
    protected LeaseRecord grant(final ProfileId profileId, final SessionRef sessionRef) {
        final var profileDef = profileDefFor(profileId);
        final var blockers = activeRecords().stream()
                .filter(other -> overlaps(profileDefFor(other.profileId()), profileDef))
                .map(LeaseRecord::id)
                .toList();
        if (!blockers.isEmpty()) {
            throw new LeaseConflictException("Profile " + profileId + " is blocked by active leases", blockers);
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
    public ServerState state() {
        final List<Worker> workers;
        synchronized (lock) {
            final var activeLeaseRecords = activeRecords();
            workers = loaded.entrySet().stream()
                    .map(worker -> {
                        final var isLeased = activeLeaseRecords.stream()
                                .anyMatch(leaseRecord -> profileDefFor(leaseRecord.profileId()).workers().contains(worker.getKey()));
                        return new Worker(worker.getKey(), worker.getValue(), worker.getValue().startsWith(LOADING_PREFIX) ? WorkerState.LOADING
                                : isLeased ? WorkerState.LEASED : WorkerState.IDLE);
                    })
                    .toList();
        }
        return new ServerState(properties.serverName(), Implementation.STUB, null, modelProfiles().profiles(), recentLeases(), workers, 0.0);
    }

    @Override
    public ChatStream chat(final LeaseId leaseId, final @Nullable String providerAuthorization, final JsonNode request) {
        final var leaseRecord = activeRecord(leaseId);
        final var profileDef = profileDefFor(leaseRecord.profileId());
        final var maxTokens = (int) max(1, min(Json.number(request.path("max_tokens")) == 0 ? DEFAULT_MAX_TOKENS
                : Json.number(request.path("max_tokens")), WORDS.length));
        final var includeUsage = Json.bool(request.path("stream_options").path("include_usage"));
        final var promptTokens = Json.promptTokens(request);
        final var speed = speedAt(profileDef, promptTokens);
        final ChatStream body = out -> {
            final var deadline = currentTimeMillis() + Duration.ofSeconds(stubProperties.loadingWaitSeconds()).toMillis();
            while (isLoading(leaseRecord) && currentTimeMillis() < deadline) {
                sleep(stubProperties.loadingPollMillis());
            }
            sleep(scaled(profileDef.ttftMs()) + round(promptTokens * MILLIS_PER_SECOND / speed.prefillTps() * stubProperties.timeFactor()));
            final var id = "chatcmpl-" + leaseRecord.id();
            final var createdAt = Instant.now().getEpochSecond();
            for (int tokenIndex = 0; tokenIndex < maxTokens; tokenIndex++) {
                final var contentChunk = mapper.createObjectNode();
                contentChunk.put("id", id).put("object", "chat.completion.chunk").put("created", createdAt).put("model", profileDef.model().value());
                final var choice = contentChunk.putArray("choices").addObject();
                choice.put("index", 0);
                final var delta = choice.putObject("delta");
                if (tokenIndex == 0) {
                    delta.put("role", "assistant");
                }
                delta.put("content", WORDS[tokenIndex] + " ");
                choice.putNull("finish_reason");
                write(out, contentChunk.toString());
                sleep(round(MILLIS_PER_SECOND / speed.decodeTps() * stubProperties.timeFactor()));
            }
            final var finishChunk = mapper.createObjectNode();
            finishChunk.put("id", id).put("object", "chat.completion.chunk").put("created", createdAt).put("model", profileDef.model().value());
            final var choice = finishChunk.putArray("choices").addObject();
            choice.put("index", 0);
            choice.putObject("delta");
            choice.put("finish_reason", "stop");
            write(out, finishChunk.toString());
            if (includeUsage) {
                final var usageChunk = mapper.createObjectNode();
                usageChunk.put("id", id).put("object", "chat.completion.chunk").put("created", createdAt).put("model", profileDef.model().value());
                usageChunk.putArray("choices");
                final var usage = usageChunk.putObject("usage");
                usage.put("prompt_tokens", promptTokens).put("completion_tokens", maxTokens).put("total_tokens", promptTokens + maxTokens);
                usage.putObject("prompt_tokens_details").put("cached_tokens", 0);
                write(out, usageChunk.toString());
            }
            addUsage(leaseRecord, promptTokens, 0, maxTokens, false);
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
