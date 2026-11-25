package ai.strgm.deck;

import ai.strgm.deck.api.model.DeckState;
import ai.strgm.deck.api.model.Implementation;
import ai.strgm.deck.api.model.Lease;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@Profile("stub")
public class StubBackend implements DeckBackend {

    record Speed(int contextTokens, double prefillTps, double decodeTps) {}

    record Def(String id, String model, int contextWindow, List<String> workers, long loadMs, long ttftMs,
            List<Speed> speeds, String quantization, double bitsPerWeight) {}

    private static final List<Speed> SMALL_SPEEDS = List.of(
            new Speed(4096, 800, 40), new Speed(16384, 600, 34), new Speed(32768, 450, 28));
    private static final List<Speed> LARGE_SPEEDS = List.of(
            new Speed(4096, 400, 25), new Speed(32768, 280, 20), new Speed(131072, 150, 14));

    private static final List<Def> DEFS = List.of(
            new Def("small-a", "stub/small-a", 32768, List.of("w1"), 3000, 500, SMALL_SPEEDS, "q4_k_m", 4),
            new Def("small-b", "stub/small-b", 32768, List.of("w2"), 3000, 500, SMALL_SPEEDS, "q4_k_m", 4),
            new Def("large", "stub/large", 131072, List.of("w1", "w2"), 6000, 900, LARGE_SPEEDS, "q8_0", 8));

    private static final String[] WORDS = ("Here is a short answer from the simulated DECK stub. Prefer small, "
            + "focused functions, name things for what they do, test the behaviour rather than the implementation, "
            + "and keep side effects at the edges of the system so the core stays easy to reason about.").split(" ");

    private final DeckProperties props;
    private final ObjectMapper mapper;
    private final Map<String, String> loaded = new LinkedHashMap<>(Map.of("w1", "small-a", "w2", "small-b"));
    private final Map<String, LeaseRecord> leases = Collections.synchronizedMap(new LinkedHashMap<>());

    public StubBackend(final DeckProperties props, final ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        loaded.clear();
        loaded.put("w1", "small-a");
        loaded.put("w2", "small-b");
    }

    private long scaled(final long ms) {
        return Math.round(ms * props.timeFactor());
    }

    private Def def(final String id) {
        return DEFS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "Unknown profile: " + id));
    }

    private boolean overlaps(final Def a, final Def b) {
        return a.workers().stream().anyMatch(b.workers()::contains);
    }

    /** The profile's speeds at {@code contextTokens}: linear between the two nearest points, clamped outside them. */
    private static Speed speedAt(final Def d, final long contextTokens) {
        final var points = d.speeds();
        if (contextTokens <= points.getFirst().contextTokens()) {
            return points.getFirst();
        }
        for (int i = 1; i < points.size(); i++) {
            final var hi = points.get(i);
            if (contextTokens <= hi.contextTokens()) {
                final var lo = points.get(i - 1);
                final var t = (double) (contextTokens - lo.contextTokens()) / (hi.contextTokens() - lo.contextTokens());
                return new Speed((int) contextTokens, lo.prefillTps() + t * (hi.prefillTps() - lo.prefillTps()),
                        lo.decodeTps() + t * (hi.decodeTps() - lo.decodeTps()));
            }
        }
        return points.getLast();
    }

    private synchronized ModelProfile toApi(final Def d) {
        final var isLoaded = d.workers().stream().allMatch(w -> d.id().equals(loaded.get(w)));
        // a lease here takes a worker that every overlapping profile needs, and each of those allows one lease
        final var reductions = DEFS.stream().filter(o -> o != d && overlaps(o, d)).map(o -> new LeaseReduction(o.id(), 1)).toList();
        return new ModelProfile(d.id(), d.model(), d.contextWindow(), isLoaded ? Readiness.LOADED : Readiness.UNLOADED)
                .loadingTimeMs((int) scaled(d.loadMs()))
                .quantization(new Quantization(d.quantization()).bitsPerWeight(d.bitsPerWeight()))
                .limitations(new Limitations().leaseReductions(reductions))
                .prices(new Prices().inputPerMTok(0.0).cachedInputPerMTok(0.0).outputPerMTok(0.0))
                .maxLeases(1).activeLeases((int) leases.values().stream().filter(l -> l.active() && l.profileId.equals(d.id())).count())
                .speeds(d.speeds().stream().map(s -> new SpeedPoint(s.contextTokens(), s.prefillTps() / props.timeFactor(),
                        s.decodeTps() / props.timeFactor())).toList());
    }

    @Override
    public ModelProfiles modelProfiles() {
        return new ModelProfiles(props.serverName(), Implementation.STUB, DEFS.stream().map(this::toApi).toList());
    }

    @Override
    public synchronized Lease acquire(final String profileId, final String sessionRef) {
        final var d = def(profileId);
        final var blockers = leases.values().stream().filter(LeaseRecord::active)
                .filter(l -> overlaps(def(l.profileId), d)).map(l -> l.id).toList();
        if (!blockers.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "lease_conflict",
                    "Profile " + profileId + " is blocked by active leases", blockers);
        }
        final var isLoaded = d.workers().stream().allMatch(w -> d.id().equals(loaded.get(w)));
        final var lease = new LeaseRecord(profileId, sessionRef, isLoaded ? LeaseState.READY : LeaseState.LOADING);
        leases.put(lease.id, lease);
        if (!isLoaded) {
            d.workers().forEach(w -> loaded.put(w, "loading " + d.id()));
            Thread.ofVirtual().start(() -> {
                sleep(scaled(d.loadMs()));
                synchronized (this) {
                    d.workers().forEach(w -> loaded.put(w, d.id()));
                    if (lease.state == LeaseState.LOADING) {
                        lease.readyAt = System.currentTimeMillis();
                        lease.state = LeaseState.READY;
                    }
                }
            });
        }
        return lease.toApi();
    }

    private LeaseRecord record(final String id) {
        final var l = leases.get(id);
        if (l == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Unknown lease: " + id);
        }
        return l;
    }

    @Override
    public Lease get(final String leaseId) {
        return record(leaseId).toApi();
    }

    @Override
    public synchronized List<Lease> leases(final String profileId) {
        synchronized (leases) {
            return leases.values().stream().filter(LeaseRecord::active)
                    .filter(l -> profileId == null || l.profileId.equals(profileId)).map(LeaseRecord::toApi).toList();
        }
    }

    @Override
    public synchronized void release(final String leaseId) {
        final var l = record(leaseId);
        if (l.active()) {
            l.state = LeaseState.RELEASED;
        }
    }

    @Override
    public synchronized Lease evict(final String leaseId) {
        final var l = record(leaseId);
        if (l.active()) {
            l.state = LeaseState.EVICTED;
        }
        return l.toApi();
    }

    @Override
    public synchronized DeckState state() {
        final var workers = loaded.entrySet().stream().map(e -> {
            final var busy = leases.values().stream().anyMatch(l -> l.active() && def(l.profileId).workers().contains(e.getKey()));
            return new Worker().id(e.getKey()).loadedProfile(e.getValue())
                    .state(e.getValue().startsWith("loading") ? "loading" : busy ? "leased" : "idle");
        }).toList();
        final List<Lease> recent = new ArrayList<>();
        synchronized (leases) {
            leases.values().forEach(l -> recent.add(l.toApi()));
        }
        return new DeckState(props.serverName(), Implementation.STUB, modelProfiles().getProfiles(),
                recent.subList(Math.max(0, recent.size() - 30), recent.size()))
                .workers(workers).spendUsd(0.0);
    }

    @Override
    public StreamingResponseBody chat(final String leaseId, final String providerAuthorization, final JsonNode request) {
        final var lease = record(leaseId);
        if (!lease.active()) {
            throw new ApiException(HttpStatus.CONFLICT, "lease_inactive", "Lease " + leaseId + " is " + lease.state.getValue());
        }
        final var d = def(lease.profileId);
        final var maxTokens = (int) Math.max(1, Math.min(Json.number(request.path("max_tokens")) == 0 ? 40
                : Json.number(request.path("max_tokens")), WORDS.length));
        final var includeUsage = Json.bool(request.path("stream_options").path("include_usage"));
        final var prompt = Json.promptTokens(request);
        final var speed = speedAt(d, prompt);
        final StreamingResponseBody body = out -> {
            final var deadline = System.currentTimeMillis() + 60_000;
            while (lease.state == LeaseState.LOADING && System.currentTimeMillis() < deadline) {
                sleep(100);
            }
            sleep(scaled(d.ttftMs()) + Math.round(prompt * 1000.0 / speed.prefillTps() * props.timeFactor()));
            final var id = "chatcmpl-" + lease.id;
            final var created = System.currentTimeMillis() / 1000;
            for (int i = 0; i < maxTokens; i++) {
                final var chunk = mapper.createObjectNode();
                chunk.put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", d.model());
                final var choice = chunk.putArray("choices").addObject();
                choice.put("index", 0);
                final var delta = choice.putObject("delta");
                if (i == 0) {
                    delta.put("role", "assistant");
                }
                delta.put("content", WORDS[i] + " ");
                choice.putNull("finish_reason");
                write(out, chunk.toString());
                sleep(Math.round(1000.0 / speed.decodeTps() * props.timeFactor()));
            }
            final var last = mapper.createObjectNode();
            last.put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", d.model());
            final var choice = last.putArray("choices").addObject();
            choice.put("index", 0);
            choice.putObject("delta");
            choice.put("finish_reason", "stop");
            write(out, last.toString());
            if (includeUsage) {
                final var usage = mapper.createObjectNode();
                usage.put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", d.model());
                usage.putArray("choices");
                final var u = usage.putObject("usage");
                u.put("prompt_tokens", prompt).put("completion_tokens", maxTokens).put("total_tokens", prompt + maxTokens);
                u.putObject("prompt_tokens_details").put("cached_tokens", 0);
                write(out, usage.toString());
            }
            lease.addUsage(prompt, 0, maxTokens, false);
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        };
        return body;
    }

    private static void write(final java.io.OutputStream out, final String json) throws java.io.IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
