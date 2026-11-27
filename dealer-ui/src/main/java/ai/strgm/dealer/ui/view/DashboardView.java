package ai.strgm.dealer.ui.view;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import ai.strgm.dealer.ui.client.DealerClient;
import ai.strgm.dealer.ui.client.DeckClients;
import ai.strgm.dealer.ui.config.UiProperties;
import ai.strgm.dealer.ui.model.ConfiguredClient;
import ai.strgm.dealer.ui.model.DealerSnapshot;
import ai.strgm.dealer.ui.model.Eviction;
import ai.strgm.dealer.ui.model.LeaseRequestSnapshot;
import ai.strgm.dealer.ui.model.SessionSnapshot;
import ai.strgm.deck.api.model.Readiness;
import ai.strgm.deck.client.model.DeckProfileState;
import ai.strgm.deck.client.model.DeckServerState;
import ai.strgm.deck.client.model.DeckWorker;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
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
    private final Grid<LeaseRequestSnapshot> queue = new Grid<>();
    private final Grid<SessionSnapshot> sessions = new Grid<>();
    private final Grid<Eviction> evictions = new Grid<>();
    private final Grid<ConfiguredClient> clientGrid = new Grid<>();
    private final Map<String, Grid<DeckProfileState>> profileGrids = new LinkedHashMap<>();
    private final Map<String, Grid<DeckWorker>> workerGrids = new LinkedHashMap<>();
    private final Map<String, Span> deckStatus = new LinkedHashMap<>();

    public DashboardView(final DealerClient dealerClient, final DeckClients deckClients, final UiProperties properties) {
        this.dealerClient = dealerClient;
        this.deckClients = deckClients;
        this.refreshMillis = properties.refreshMillis();
        setWidthFull();
        getStyle().set("font-size", "1.1rem");
        add(new H1("DEALER + DECK: live view"), dealerStatus);

        sessions.addColumn(SessionSnapshot::client).setHeader("Client").setAutoWidth(true);
        sessions.addColumn(SessionSnapshot::sessionId).setHeader("Session").setAutoWidth(true);
        sessions.addColumn(SessionSnapshot::model).setHeader("Model").setAutoWidth(true);
        sessions.addColumn(session -> session.deckServer() == null ? "-"
                : session.deckServer() + (session.serverType() == null ? "" : " (" + session.serverType().getValue() + ")"))
                .setHeader("DECK server").setAutoWidth(true);
        sessions.addColumn(session -> session.profileId() == null ? "-" : session.profileId()).setHeader("Profile").setAutoWidth(true);
        sessions.addColumn(SessionSnapshot::leaseState).setHeader("Lease").setAutoWidth(true);
        sessions.addColumn(session -> seconds(session.leaseAge())).setHeader("Lease age").setAutoWidth(true);
        sessions.addColumn(session -> seconds(session.idle())).setHeader("Idle").setAutoWidth(true);
        sessions.addColumn(SessionSnapshot::requests).setHeader("Requests").setAutoWidth(true);
        sessions.addColumn(session -> session.promptTokens() + " / " + session.cachedTokens() + " / " + session.completionTokens())
                .setHeader("Tokens (prompt / cached / completion)").setAutoWidth(true);
        // TODO(#104): format money in its own currency, which the API doesn't carry yet; "$" is assumed
        sessions.addColumn(session -> String.format("$%.6f", session.costUsd())).setHeader("Cost").setAutoWidth(true);
        sessions.setAllRowsVisible(true);

        queue.addColumn(LeaseRequestSnapshot::client).setHeader("Client");
        queue.addColumn(LeaseRequestSnapshot::sessionId).setHeader("Session");
        queue.addColumn(LeaseRequestSnapshot::model).setHeader("Model");
        queue.addColumn(LeaseRequestSnapshot::clientPriority).setHeader("Client priority");
        queue.addColumn(LeaseRequestSnapshot::requestPriority).setHeader("Request priority");
        queue.addColumn(leaseRequest -> seconds(leaseRequest.waiting())).setHeader("Waiting");
        queue.addColumn(leaseRequest -> leaseRequest.reservedProfile() == null ? "-" : leaseRequest.reservedProfile()).setHeader("Reserved (draining)");
        queue.setAllRowsVisible(true);

        evictions.addColumn(eviction -> eviction.createdAt() == null ? "-" : TIME.format(eviction.createdAt()))
                .setHeader("Time").setAutoWidth(true);
        evictions.addColumn(eviction -> eviction.victimClient() + " / " + eviction.victimSession()).setHeader("Evicted").setAutoWidth(true);
        evictions.addColumn(eviction -> eviction.deckServer() + "/" + eviction.profileId()).setHeader("From").setAutoWidth(true);
        evictions.addColumn(Eviction::byClient).setHeader("For").setAutoWidth(true);
        evictions.addColumn(Eviction::reason).setHeader("Why").setAutoWidth(true);
        evictions.setAllRowsVisible(true);

        clientGrid.addColumn(ConfiguredClient::name).setHeader("Client");
        clientGrid.addColumn(ConfiguredClient::priority).setHeader("Priority");
        clientGrid.addColumn(client -> client.externalRouting() ? "allowed" : "local only").setHeader("External routing");
        clientGrid.setAllRowsVisible(true);

        add(new H3("Sessions and leases"), sessions, new H3("Queue"), queue, new H3("Evictions"), evictions);
        final var decks = new HorizontalLayout();
        decks.setWidthFull();
        deckClients.clientsByName().keySet().forEach(deckName -> {
            final var profiles = new Grid<DeckProfileState>();
            profiles.addColumn(DeckProfileState::id).setHeader("Profile").setAutoWidth(true);
            profiles.addColumn(profile -> profile.readiness().getValue() + (profile.readiness() != Readiness.UNLOADED || profile.loadingTime() == null
                    ? "" : " (" + seconds(profile.loadingTime()) + " to load)")).setHeader("Readiness").setAutoWidth(true);
            profiles.addColumn(profile -> profile.activeLeases() + " / " + (profile.maxLeases() == null ? "∞" : profile.maxLeases())).setHeader("Leases / budget").setAutoWidth(true);
            profiles.addColumn(DeckProfileState::contextWindow).setHeader("Window").setAutoWidth(true);
            profiles.addColumn(DashboardView::leaseReductions).setHeader("Lease reductions").setAutoWidth(true);
            profiles.setAllRowsVisible(true);
            final var workers = new Grid<DeckWorker>();
            workers.addColumn(DeckWorker::id).setHeader("Worker");
            workers.addColumn(DeckWorker::loadedProfile).setHeader("Loaded");
            workers.addColumn(DeckWorker::state).setHeader("State");
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
    private record Snapshot(@Nullable DealerSnapshot dealer, @Nullable String dealerError, Map<String, @Nullable DeckServerState> decks) {}

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
        DealerSnapshot dealer = null;
        String error = null;
        try {
            dealer = dealerClient.state();
        } catch (Exception exception) {
            error = exception.getMessage();
        }
        final Map<String, @Nullable DeckServerState> decks = new LinkedHashMap<>();
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
        final var dealerSnapshot = snapshot.dealer();
        if (dealerSnapshot != null) {
            sessions.setItems(dealerSnapshot.sessions());
            queue.setItems(dealerSnapshot.queue());
            evictions.setItems(dealerSnapshot.evictions().reversed());
            clientGrid.setItems(dealerSnapshot.clients());
            dealerStatus.setText("DEALER: " + dealerSnapshot.sessions().size() + " sessions, " + dealerSnapshot.queue().size() + " queued, "
                    + dealerSnapshot.evictions().size() + " evictions · " + TIME.format(Instant.now()));
        } else {
            dealerStatus.setText("DEALER unreachable: " + snapshot.dealerError());
        }
        snapshot.decks().forEach((deckName, deck) -> {
            final var statusLabel = requireNonNull(deckStatus.get(deckName), "every configured DECK server has a status label");
            if (deck == null) {
                statusLabel.setText("unreachable");
                return;
            }
            requireNonNull(profileGrids.get(deckName), "every configured DECK server has a profile grid").setItems(deck.profiles());
            final var workerGrid = requireNonNull(workerGrids.get(deckName), "every configured DECK server has a worker grid");
            workerGrid.setItems(deck.workers());
            workerGrid.setVisible(!deck.workers().isEmpty());
            statusLabel.setText(deck.implementation().getValue()
                    + (deck.provider() == null ? "" : " · provider " + deck.provider())
                    // TODO(#104): the spend's own currency, as for the session cost
                    + String.format(" · spend $%.6f", deck.spendUsd()));
        });
    }

    /** A profile's lease reductions, e.g. {@code large −1}, or {@code -} if it has none. */
    private static String leaseReductions(final DeckProfileState profile) {
        if (profile.reductions().isEmpty()) {
            return "-";
        }
        return profile.reductions().stream()
                .map(reduction -> reduction.profileId() + " \u2212" + reduction.leases())
                .collect(joining(", "));
    }

    private static String seconds(final @Nullable Duration duration) {
        return duration == null ? "-" : String.format("%.1f s", duration.toMillis() / MILLIS_PER_SECOND);
    }
}
