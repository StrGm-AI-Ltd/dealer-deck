package ai.strgm.deck.openai;

import static ai.strgm.util.Nulls.mapNullable;
import static java.lang.Math.max;
import static java.nio.charset.StandardCharsets.UTF_8;

import ai.strgm.deck.AbstractDeckServer;
import ai.strgm.deck.ChatStream;
import ai.strgm.deck.DeckProperties;
import ai.strgm.deck.Json;
import ai.strgm.deck.LeaseConflictException;
import ai.strgm.deck.LeaseRecord;
import ai.strgm.deck.ProviderCredentialMissingException;
import ai.strgm.deck.UnknownProfileException;
import ai.strgm.deck.api.model.DeckState;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.deck.api.model.Limitations;
import ai.strgm.deck.api.model.ModelProfile;
import ai.strgm.deck.api.model.ModelProfiles;
import ai.strgm.deck.api.model.Prices;
import ai.strgm.deck.api.model.Quantization;
import ai.strgm.deck.api.model.Readiness;
import ai.strgm.deck.openai.UpstreamProperties.UpstreamModel;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ModelName;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The DECK server backed by an OpenAI-compatible provider, configured under {@code deck.upstream}: Nebius Token
 * Factory by default (D91, D92), or a server on the operator's own hardware, such as oMLX. The provider manages
 * its own capacity, so profiles are always loaded and leases are granted at once, up to each model's instance
 * budget; a lease carries its session's usage. DECK stores no provider credentials in v0.5: it forwards the one
 * DEALER sends (D104).
 */
@Component
public class OpenAiCompatibleServer extends AbstractDeckServer {
    /** Prices are per million tokens. */
    private static final double TOKENS_PER_MILLION = 1_000_000.0;
    /** The prefix of a server-sent event's data line. */
    private static final String SSE_DATA_PREFIX = "data:";

    private final ProviderClient providerClient;
    private final UpstreamProperties upstreamProperties;
    private final String prefix;

    public OpenAiCompatibleServer(final DeckProperties properties, final UpstreamProperties upstreamProperties,
            final ProviderClient providerClient, final ObjectMapper mapper) {
        super(properties, mapper);
        this.upstreamProperties = upstreamProperties;
        this.providerClient = providerClient;
        this.prefix = upstreamProperties.idPrefix() == null ? upstreamProperties.provider() + "/" : upstreamProperties.idPrefix();
    }

    private UpstreamModel model(final ProfileId profileId) {
        return upstreamProperties.models().stream()
                .filter(upstreamModel -> profileId(upstreamModel).equals(profileId))
                .findFirst()
                .orElseThrow(() -> new UnknownProfileException(profileId));
    }

    /** A model's profile id, which is also the model name DEALER's clients request: the provider's name, prefixed. */
    private ProfileId profileId(final UpstreamModel upstreamModel) {
        return new ProfileId(prefix + upstreamModel.model());
    }

    private List<ModelProfile> profiles() {
        return upstreamProperties.models().stream()
                .map(upstreamModel -> new ModelProfile(profileId(upstreamModel), new ModelName(prefix + upstreamModel.model()),
                        upstreamModel.contextWindow(), Readiness.LOADED).provider(upstreamProperties.provider()).limitations(new Limitations())
                        .quantization(mapNullable(upstreamModel.quantization(),
                                quantization -> new Quantization(quantization).bitsPerWeight(upstreamModel.bitsPerWeight())))
                        .maxLeases(upstreamModel.maxLeases()).activeLeases(activeLeaseIds(profileId(upstreamModel)).size())
                        .prices(new Prices().inputPerMTok(upstreamModel.inputPerMTok())
                                .cachedInputPerMTok(upstreamModel.cachedInputPerMTok()).outputPerMTok(upstreamModel.outputPerMTok())))
                .toList();
    }

    @Override
    public ModelProfiles modelProfiles() {
        return new ModelProfiles(properties.serverName(), upstreamProperties.implementation(), profiles());
    }

    @Override
    protected LeaseRecord grant(final ProfileId profileId, final SessionRef sessionRef) {
        final var upstreamModel = model(profileId);
        final var activeLeaseIds = activeLeaseIds(profileId);
        if (upstreamModel.maxLeases() != null && activeLeaseIds.size() >= upstreamModel.maxLeases()) {
            throw new LeaseConflictException("The instance budget of " + profileId + " (" + upstreamModel.maxLeases() + ") is in use",
                    activeLeaseIds);
        }
        return register(profileId, sessionRef, LeaseState.READY);
    }

    // TODO(#104): a money amount with its currency, instead of a bare double
    private double cost(final Lease lease) {
        final var upstreamModel = model(lease.getProfileId());
        final var usage = lease.getUsage();
        if (usage == null) {
            return 0;
        }
        final var cachedTokens = orZero(usage.getCachedTokens());
        return ((orZero(usage.getPromptTokens()) - cachedTokens) * upstreamModel.inputPerMTok()
                + cachedTokens * upstreamModel.cachedInputPerMTok()
                + orZero(usage.getCompletionTokens()) * upstreamModel.outputPerMTok()) / TOKENS_PER_MILLION;
    }

    private static long orZero(final @Nullable Long tokens) {
        return tokens == null ? 0 : tokens;
    }

    @Override
    public DeckState state() {
        final var spend = allLeases().stream()
                .mapToDouble(this::cost)
                .sum();
        return new DeckState(properties.serverName(), upstreamProperties.implementation(), profiles(), recentLeases())
                .provider(upstreamProperties.provider()).spendUsd(spend);
    }

    @Override
    public ChatStream chat(final LeaseId leaseId, final @Nullable String providerAuthorization, final JsonNode request) {
        if (providerAuthorization == null || providerAuthorization.isBlank()) {
            throw new ProviderCredentialMissingException();
        }
        final var leaseRecord = activeRecord(leaseId);
        final var upstreamModel = model(leaseRecord.profileId());
        final var upstreamBody = (ObjectNode) request.deepCopy();
        upstreamBody.put("model", upstreamModel.model().value());
        upstreamBody.put("stream", true);
        upstreamBody.putObject("stream_options").put("include_usage", true);
        final var providerEvents = providerClient.openChat(providerAuthorization, upstreamBody.toString());
        final var promptTokens = Json.promptTokens(request);
        final ChatStream stream = out -> {
            boolean sawUsage = false;
            long completionChars = 0;
            try (var reader = new BufferedReader(new InputStreamReader(providerEvents, UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(SSE_DATA_PREFIX)) {
                        final var eventData = line.substring(SSE_DATA_PREFIX.length()).trim();
                        if (!eventData.equals("[DONE]") && eventData.startsWith("{")) {
                            final var chunk = mapper.readTree(eventData);
                            final var usage = chunk.path("usage");
                            if (usage.isObject()) {
                                sawUsage = true;
                                addUsage(leaseRecord, Json.number(usage.path("prompt_tokens")),
                                        Json.number(usage.path("prompt_tokens_details").path("cached_tokens")),
                                        Json.number(usage.path("completion_tokens")), false);
                            }
                            final var choices = chunk.path("choices");
                            for (int choiceIndex = 0; choiceIndex < choices.size(); choiceIndex++) {
                                completionChars += Json.text(choices.get(choiceIndex).path("delta").path("content")).length();
                            }
                        }
                    }
                    out.write((line + "\n").getBytes(UTF_8));
                    if (line.isEmpty()) {
                        out.flush();
                    }
                }
            } finally {
                if (!sawUsage) {
                    addUsage(leaseRecord, promptTokens, 0, max(1, completionChars / Json.CHARS_PER_TOKEN), true);
                }
                out.flush();
            }
        };
        return stream;
    }
}
