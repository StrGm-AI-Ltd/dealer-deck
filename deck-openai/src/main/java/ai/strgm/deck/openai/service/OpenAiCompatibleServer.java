package ai.strgm.deck.openai.service;

import static ai.strgm.util.Nulls.mapNullable;
import static java.nio.charset.StandardCharsets.UTF_8;

import ai.strgm.deck.openai.client.ProviderClient;
import ai.strgm.deck.openai.config.UpstreamProperties;
import ai.strgm.deck.service.AbstractDeckServer;
import ai.strgm.deck.service.ChatStream;
import ai.strgm.deck.config.DeckProperties;
import ai.strgm.deck.util.Json;
import ai.strgm.deck.exception.LeaseConflictException;
import ai.strgm.deck.model.LeaseRecord;
import ai.strgm.deck.model.LeaseSnapshot;
import ai.strgm.deck.model.OfferedProfile;
import ai.strgm.deck.model.OfferedProfile.Prices;
import ai.strgm.deck.model.OfferedProfile.Quantization;
import ai.strgm.deck.model.OfferedProfiles;
import ai.strgm.deck.exception.ProviderCredentialMissingException;
import ai.strgm.deck.model.ServerState;
import ai.strgm.deck.exception.UnknownProfileException;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.deck.api.model.Readiness;
import ai.strgm.deck.openai.config.UpstreamProperties.UpstreamModel;
import ai.strgm.deck.openai.model.ProviderChatEvent;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ModelName;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import ai.strgm.types.TokenPrices;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The DECK server backed by an OpenAI-compatible provider, configured under {@code deck.upstream}: Nebius Token
 * Factory by default (D91, D92), or a server on the operator's own hardware, such as oMLX. The provider manages
 * its own capacity, so profiles are always loaded and leases are granted at once, up to each model's instance
 * budget; a lease carries its session's usage. DECK stores no provider credentials in v0.5: it forwards the one
 * DEALER sends (D104).
 */
@Component
public class OpenAiCompatibleServer extends AbstractDeckServer {

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

    private UpstreamModel upstreamModelFor(final ProfileId profileId) {
        return upstreamProperties.models().stream()
                .filter(upstreamModel -> profileId(upstreamModel).equals(profileId))
                .findFirst()
                .orElseThrow(() -> new UnknownProfileException(profileId));
    }

    /** A model's profile id, which is also the model name DEALER's clients request: the provider's name, prefixed. */
    private ProfileId profileId(final UpstreamModel upstreamModel) {
        return new ProfileId(prefix + upstreamModel.model());
    }

    private List<OfferedProfile> profiles() {
        return upstreamProperties.models().stream()
                .map(upstreamModel -> new OfferedProfile(profileId(upstreamModel), new ModelName(prefix + upstreamModel.model()),
                        upstreamProperties.provider(), upstreamModel.contextWindow(),
                        mapNullable(upstreamModel.quantization(), quantization -> new Quantization(quantization, upstreamModel.bitsPerWeight())),
                        Readiness.LOADED, null, List.of(),
                        new Prices(upstreamModel.inputPerMTok(), upstreamModel.cachedInputPerMTok(), upstreamModel.outputPerMTok()),
                        upstreamModel.maxLeases(), activeLeaseIds(profileId(upstreamModel)).size(), List.of()))
                .toList();
    }

    @Override
    public OfferedProfiles modelProfiles() {
        return new OfferedProfiles(properties.serverName(), upstreamProperties.implementation(), profiles());
    }

    @Override
    protected LeaseRecord grantLease(final ProfileId profileId, final SessionRef sessionRef) {
        final var upstreamModel = upstreamModelFor(profileId);
        final var activeLeaseIds = activeLeaseIds(profileId);
        if (leaseBudgetInUse(upstreamModel, activeLeaseIds.size())) {
            throw new LeaseConflictException("The instance budget of " + profileId + " (" + upstreamModel.maxLeases() + ") is in use",
                    activeLeaseIds);
        }
        return registerLease(profileId, sessionRef, LeaseState.READY);
    }

    /** Whether {@code upstreamModel}'s lease budget, if it has one, is used up by {@code activeLeases}. */
    private static boolean leaseBudgetInUse(final UpstreamModel upstreamModel, final int activeLeases) {
        return upstreamModel.maxLeases() != null && activeLeases >= upstreamModel.maxLeases();
    }

    // TODO(#104): a money amount with its currency, instead of a bare double
    private double cost(final LeaseSnapshot lease) {
        final var upstreamModel = upstreamModelFor(lease.profileId());
        final var usage = lease.usage();
        return new TokenPrices(upstreamModel.inputPerMTok(), upstreamModel.cachedInputPerMTok(), upstreamModel.outputPerMTok())
                .costUsd(usage.promptTokens(), usage.cachedTokens(), usage.completionTokens());
    }

    @Override
    public ServerState state() {
        final var spend = allLeases().stream()
                .mapToDouble(this::cost)
                .sum();
        return new ServerState(properties.serverName(), upstreamProperties.implementation(), upstreamProperties.provider(), profiles(),
                recentLeases(), List.of(), spend);
    }

    @Override
    public ChatStream openChatStream(final LeaseId leaseId, final @Nullable String providerAuthorization, final JsonNode chatRequest) {
        if (providerAuthorization == null || providerAuthorization.isBlank()) {
            throw new ProviderCredentialMissingException();
        }
        final var leaseRecord = activeLeaseRecord(leaseId);
        final var upstreamModel = upstreamModelFor(leaseRecord.profileId());
        final var providerEvents = providerClient.openChatStream(providerAuthorization, upstreamModel.model(), chatRequest);
        final var promptTokens = Json.promptTokens(chatRequest);
        final ChatStream chatStream = out -> {
            boolean sawUsage = false;
            long completionChars = 0;
            try (providerEvents) {
                ProviderChatEvent event;
                while ((event = providerEvents.nextEvent()) != null) {
                    if (event.usage() != null) {
                        sawUsage = true;
                        addUsage(leaseRecord, event.usage().promptTokens(), event.usage().cachedTokens(), event.usage().completionTokens(), false);
                    }
                    completionChars += event.contentChars();
                    out.write(event.text().getBytes(UTF_8));
                    out.flush();
                }
            } finally {
                if (!sawUsage) {
                    addUsage(leaseRecord, promptTokens, 0, Json.estimatedTokens(completionChars), true);
                }
                out.flush();
            }
        };
        return chatStream;
    }
}
