package ai.strgm.dealer.ui.view;

import static ai.strgm.util.Nulls.mapNullable;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import ai.strgm.dealer.api.model.ClientInfo;
import ai.strgm.dealer.api.model.DealerState;
import ai.strgm.dealer.api.model.EvictionEvent;
import ai.strgm.dealer.api.model.QueuedRequest;
import ai.strgm.dealer.api.model.SessionInfo;
import ai.strgm.dealer.ui.client.DealerClient;
import ai.strgm.dealer.ui.client.DeckClients;
import ai.strgm.dealer.ui.config.UiProperties;
import ai.strgm.deck.api.model.DeckState;
import ai.strgm.deck.api.model.Limitations;
import ai.strgm.deck.api.model.ModelProfile;
import ai.strgm.deck.api.model.Readiness;
import ai.strgm.deck.api.model.Worker;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The live view: DEALER's queue, sessions and leases, evictions, tokens and cost, and each DECK server's state. */
@Route("")
@PageTitle("DEALER + DECK")
public class DashboardView extends VerticalLayout {
    private static final double MILLIS_PER_SECOND = 1000.0;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DealerClient dealerClient;
    private final DeckClients deckClients;
    /** How often the browser asks for fresh metrics. */
    private final int refreshMillis;
    private final Span dealerStatus = new Span();
    private final Grid<QueuedRequest> queue = new Grid<>();
    private final Grid<SessionInfo> sessions = new Grid<>();
    private final Grid<EvictionEvent> evictions = new Grid<>();
    private final Grid<ClientInfo> clientGrid = new Grid<>();
    private final Map<String, Grid<ModelProfile>> profileGrids = new LinkedHashMap<>();
    private final Map<String, Grid<Worker>> workerGrids = new LinkedHashMap<>();
    private final Map<String, Span> deckStatus = new LinkedHashMap<>();

    public DashboardView(final DealerClient dealerClient, final DeckClients deckClients, final UiProperties properties) {
        this.dealerClient = dealerClient;
        this.deckClients = deckClients;
        this.refreshMillis = properties.refreshMillis();
        setWidthFull();
        getStyle().set("font-size", "1.1rem");
        add(new H1("DEALER + DECK: live view"), dealerStatus);

        sessions.addColumn(SessionInfo::getClient).setHeader("Client").setAutoWidth(true);
        sessions.addColumn(SessionInfo::getSessionId).setHeader("Session").setAutoWidth(true);
        sessions.addColumn(SessionInfo::getModel).setHeader("Model").setAutoWidth(true);
        sessions.addColumn(session -> session.getDeckServer() == null ? "-"
                : session.getDeckServer() + (session.getServerType() == null ? "" : " (" + session.getServerType().getValue() + ")"))
                .setHeader("DECK server").setAutoWidth(true);
        sessions.addColumn(session -> session.getProfileId() == null ? "-" : session.getProfileId()).setHeader("Profile").setAutoWidth(true);
        sessions.addColumn(SessionInfo::getLeaseState).setHeader("Lease").setAutoWidth(true);
        sessions.addColumn(session -> seconds(session.getLeaseAgeMs())).setHeader("Lease age").setAutoWidth(true);
        sessions.addColumn(session -> seconds(session.getIdleMs())).setHeader("Idle").setAutoWidth(true);
        sessions.addColumn(SessionInfo::getRequests).setHeader("Requests").setAutoWidth(true);
        sessions.addColumn(session -> session.getPromptTokens() + " / " + session.getCachedTokens() + " / " + session.getCompletionTokens())
                .setHeader("Tokens (prompt / cached / completion)").setAutoWidth(true);
        // TODO(#104): format money in its own currency, which the API doesn't carry yet; "$" is assumed
        sessions.addColumn(session -> String.format("$%.6f", session.getCostUsd() == null ? 0 : session.getCostUsd())).setHeader("Cost").setAutoWidth(true);
        sessions.setAllRowsVisible(true);

        queue.addColumn(QueuedRequest::getClient).setHeader("Client");
        queue.addColumn(QueuedRequest::getSessionId).setHeader("Session");
        queue.addColumn(QueuedRequest::getModel).setHeader("Model");
        queue.addColumn(QueuedRequest::getClientPriority).setHeader("Client priority");
        queue.addColumn(QueuedRequest::getRequestPriority).setHeader("Request priority");
        queue.addColumn(request -> seconds(request.getWaitingMs())).setHeader("Waiting");
        queue.addColumn(request -> request.getReservedProfile() == null ? "-" : request.getReservedProfile()).setHeader("Reserved (draining)");
        queue.setAllRowsVisible(true);

        evictions.addColumn(eviction -> eviction.getCreatedAt() == null ? "-" : TIME.format(Instant.ofEpochSecond(eviction.getCreatedAt())))
                .setHeader("Time").setAutoWidth(true);
        evictions.addColumn(eviction -> eviction.getVictimClient() + " / " + eviction.getVictimSession()).setHeader("Evicted").setAutoWidth(true);
        evictions.addColumn(eviction -> eviction.getDeckServer() + "/" + eviction.getProfileId()).setHeader("From").setAutoWidth(true);
        evictions.addColumn(EvictionEvent::getByClient).setHeader("For").setAutoWidth(true);
        evictions.addColumn(EvictionEvent::getReason).setHeader("Why").setAutoWidth(true);
        evictions.setAllRowsVisible(true);

        clientGrid.addColumn(ClientInfo::getName).setHeader("Client");
        clientGrid.addColumn(ClientInfo::getPriority).setHeader("Priority");
        clientGrid.addColumn(client -> Boolean.TRUE.equals(client.getExternalRouting()) ? "allowed" : "local only").setHeader("External routing");
        clientGrid.setAllRowsVisible(true);

        add(new H3("Sessions and leases"), sessions, new H3("Queue"), queue, new H3("Evictions"), evictions);
        final var decks = new HorizontalLayout();
        decks.setWidthFull();
        deckClients.clientsByName().keySet().forEach(deckName -> {
            final var profiles = new Grid<ModelProfile>();
            profiles.addColumn(ModelProfile::getId).setHeader("Profile").setAutoWidth(true);
            profiles.addColumn(profile -> profile.getReadiness().getValue() + (profile.getReadiness() != Readiness.UNLOADED || profile.getLoadingTimeMs() == null
                    ? "" : " (" + seconds(profile.getLoadingTimeMs().longValue()) + " to load)")).setHeader("Readiness").setAutoWidth(true);
            profiles.addColumn(profile -> (profile.getActiveLeases() == null ? 0 : profile.getActiveLeases()) + " / " + (profile.getMaxLeases() == null ? "∞" : profile.getMaxLeases())).setHeader("Leases / budget").setAutoWidth(true);
            profiles.addColumn(ModelProfile::getContextWindow).setHeader("Window").setAutoWidth(true);
            profiles.addColumn(DashboardView::leaseReductions).setHeader("Lease reductions").setAutoWidth(true);
            profiles.setAllRowsVisible(true);
            final var workers = new Grid<Worker>();
            workers.addColumn(Worker::getId).setHeader("Worker");
            workers.addColumn(Worker::getLoadedProfile).setHeader("Loaded");
            workers.addColumn(Worker::getState).setHeader("State");
            workers.setAllRowsVisible(true);
            final var statusLabel = new Span();
            profileGrids.put(deckName, profiles);
            workerGrids.put(deckName, workers);
            deckStatus.put(deckName, statusLabel);
            final var column = new VerticalLayout(new H3("DECK: " + deckName), statusLabel, profiles, workers);
            column.setPadding(false);
            decks.add(column);
        });
        add(decks, new H3("Clients"), clientGrid);
    }

    /** What one refresh read: DEALER's state, or why it couldn't be read, and each DECK server's state, or null if unreachable. */
    private record Snapshot(@Nullable DealerState dealer, @Nullable String dealerError, Map<String, @Nullable DeckState> decks) {}

    @Override
    protected void onAttach(final AttachEvent event) {
        apply(fetchSnapshot());
        // A browser-side timer calls tick() every second; each response carries the grid updates.
        getElement().executeJs("const el = $0; clearInterval(el.__dealerTimer);"
                + " el.__dealerTimer = setInterval(() => el.$server.tick(), " + refreshMillis + ");", getElement());
    }

    @Override
    protected void onDetach(final DetachEvent event) {
        getElement().executeJs("clearInterval($0.__dealerTimer)", getElement());
    }

    @ClientCallable
    public void tick() {
        apply(fetchSnapshot());
    }

    /** Reads the metrics APIs off the UI lock; {@link #apply} then updates the grids under it. */
    private Snapshot fetchSnapshot() {
        DealerState dealer = null;
        String error = null;
        try {
            dealer = dealerClient.state();
        } catch (Exception exception) {
            error = exception.getMessage();
        }
        final Map<String, @Nullable DeckState> decks = new LinkedHashMap<>();
        deckClients.clientsByName().forEach((deckName, deckClient) -> {
            try {
                decks.put(deckName, deckClient.state());
            } catch (Exception exception) {
                decks.put(deckName, null);
            }
        });
        return new Snapshot(dealer, error, decks);
    }

    private void apply(final Snapshot snapshot) {
        final var dealerState = snapshot.dealer();
        if (dealerState != null) {
            final var sessionInfos = orEmpty(dealerState.getSessions());
            final var queuedRequests = orEmpty(dealerState.getQueue());
            final var evictionEvents = orEmpty(dealerState.getEvictions());
            sessions.setItems(sessionInfos);
            queue.setItems(queuedRequests);
            evictions.setItems(evictionEvents.reversed());
            clientGrid.setItems(orEmpty(dealerState.getClients()));
            dealerStatus.setText("DEALER: " + sessionInfos.size() + " sessions, " + queuedRequests.size() + " queued, "
                    + evictionEvents.size() + " evictions · " + TIME.format(Instant.now()));
        } else {
            dealerStatus.setText("DEALER unreachable: " + snapshot.dealerError());
        }
        snapshot.decks().forEach((deckName, deck) -> {
            final var statusLabel = requireNonNull(deckStatus.get(deckName), "every configured DECK server has a status label");
            if (deck == null) {
                statusLabel.setText("unreachable");
                return;
            }
            requireNonNull(profileGrids.get(deckName), "every configured DECK server has a profile grid").setItems(deck.getProfiles());
            final var workers = orEmpty(deck.getWorkers());
            final var workerGrid = requireNonNull(workerGrids.get(deckName), "every configured DECK server has a worker grid");
            workerGrid.setItems(workers);
            workerGrid.setVisible(!workers.isEmpty());
            statusLabel.setText(deck.getImplementation().getValue()
                    + (deck.getProvider() == null ? "" : " · provider " + deck.getProvider())
                    // TODO(#104): the spend's own currency, as for the session cost
                    + String.format(" · spend $%.6f", deck.getSpendUsd() == null ? 0 : deck.getSpendUsd()));
        });
    }

    /** A profile's lease reductions, e.g. {@code large −1}, or {@code -} if it has none. */
    private static String leaseReductions(final ModelProfile profile) {
        final var reductions = mapNullable(profile.getLimitations(), Limitations::getLeaseReductions);
        if (reductions == null || reductions.isEmpty()) {
            return "-";
        }
        return reductions.stream()
                .map(reduction -> reduction.getProfileId() + " \u2212" + reduction.getLeases())
                .collect(joining(", "));
    }

    /** A list the API may leave out, as an empty list when it does. */
    private static <T> List<T> orEmpty(final @Nullable List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String seconds(final @Nullable Long millis) {
        return millis == null ? "-" : String.format("%.1f s", millis / MILLIS_PER_SECOND);
    }
}
