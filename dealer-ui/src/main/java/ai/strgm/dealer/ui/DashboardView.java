package ai.strgm.dealer.ui;

import ai.strgm.dealer.api.model.ClientInfo;
import ai.strgm.dealer.api.model.EvictionEvent;
import ai.strgm.dealer.api.model.QueuedRequest;
import ai.strgm.dealer.api.model.SessionInfo;
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

/** The live view: DEALER's queue, sessions and leases, evictions, tokens and cost, and each DECK server's state. */
@Route("")
@PageTitle("DEALER + DECK")
public class DashboardView extends VerticalLayout {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Clients clients;
    private final Span status = new Span();
    private final Grid<QueuedRequest> queue = new Grid<>();
    private final Grid<SessionInfo> sessions = new Grid<>();
    private final Grid<EvictionEvent> evictions = new Grid<>();
    private final Grid<ClientInfo> clientGrid = new Grid<>();
    private final Map<String, Grid<ModelProfile>> profileGrids = new LinkedHashMap<>();
    private final Map<String, Grid<Worker>> workerGrids = new LinkedHashMap<>();
    private final Map<String, Span> deckStatus = new LinkedHashMap<>();

    public DashboardView(final Clients clients) {
        this.clients = clients;
        setWidthFull();
        getStyle().set("font-size", "1.1rem");
        add(new H1("DEALER + DECK: live view"), status);

        sessions.addColumn(SessionInfo::getClient).setHeader("Client").setAutoWidth(true);
        sessions.addColumn(SessionInfo::getSessionId).setHeader("Session").setAutoWidth(true);
        sessions.addColumn(SessionInfo::getModel).setHeader("Model").setAutoWidth(true);
        sessions.addColumn(s -> s.getDeckServer() == null ? "-" : s.getDeckServer() + " (" + s.getServerType().getValue() + ")").setHeader("DECK server").setAutoWidth(true);
        sessions.addColumn(s -> s.getProfileId() == null ? "-" : s.getProfileId()).setHeader("Profile").setAutoWidth(true);
        sessions.addColumn(SessionInfo::getLeaseState).setHeader("Lease").setAutoWidth(true);
        sessions.addColumn(s -> seconds(s.getLeaseAgeMs())).setHeader("Lease age").setAutoWidth(true);
        sessions.addColumn(s -> seconds(s.getIdleMs())).setHeader("Idle").setAutoWidth(true);
        sessions.addColumn(SessionInfo::getRequests).setHeader("Requests").setAutoWidth(true);
        sessions.addColumn(s -> s.getPromptTokens() + " / " + s.getCachedTokens() + " / " + s.getCompletionTokens())
                .setHeader("Tokens (prompt / cached / completion)").setAutoWidth(true);
        sessions.addColumn(s -> String.format("$%.6f", s.getCostUsd() == null ? 0 : s.getCostUsd())).setHeader("Cost").setAutoWidth(true);
        sessions.setAllRowsVisible(true);

        queue.addColumn(QueuedRequest::getClient).setHeader("Client");
        queue.addColumn(QueuedRequest::getSessionId).setHeader("Session");
        queue.addColumn(QueuedRequest::getModel).setHeader("Model");
        queue.addColumn(QueuedRequest::getClientPriority).setHeader("Client priority");
        queue.addColumn(QueuedRequest::getRequestPriority).setHeader("Request priority");
        queue.addColumn(q -> seconds(q.getWaitingMs())).setHeader("Waiting");
        queue.addColumn(q -> q.getReservedProfile() == null ? "-" : q.getReservedProfile()).setHeader("Reserved (draining)");
        queue.setAllRowsVisible(true);

        evictions.addColumn(e -> TIME.format(Instant.ofEpochSecond(e.getCreatedAt()))).setHeader("Time").setAutoWidth(true);
        evictions.addColumn(e -> e.getVictimClient() + " / " + e.getVictimSession()).setHeader("Evicted").setAutoWidth(true);
        evictions.addColumn(e -> e.getDeckServer() + "/" + e.getProfileId()).setHeader("From").setAutoWidth(true);
        evictions.addColumn(EvictionEvent::getByClient).setHeader("For").setAutoWidth(true);
        evictions.addColumn(EvictionEvent::getReason).setHeader("Why").setAutoWidth(true);
        evictions.setAllRowsVisible(true);

        clientGrid.addColumn(ClientInfo::getName).setHeader("Client");
        clientGrid.addColumn(ClientInfo::getPriority).setHeader("Priority");
        clientGrid.addColumn(c -> Boolean.TRUE.equals(c.getExternalRouting()) ? "allowed" : "local only").setHeader("External routing");
        clientGrid.setAllRowsVisible(true);

        add(new H3("Sessions and leases"), sessions, new H3("Queue"), queue, new H3("Evictions"), evictions);
        final var decks = new HorizontalLayout();
        decks.setWidthFull();
        clients.decks.keySet().forEach(name -> {
            final var profiles = new Grid<ModelProfile>();
            profiles.addColumn(ModelProfile::getId).setHeader("Profile").setAutoWidth(true);
            profiles.addColumn(p -> p.getReadiness().getValue() + (p.getReadiness() != Readiness.UNLOADED || p.getLoadingTimeMs() == null
                    ? "" : " (" + seconds(p.getLoadingTimeMs().longValue()) + " to load)")).setHeader("Readiness").setAutoWidth(true);
            profiles.addColumn(p -> (p.getActiveLeases() == null ? 0 : p.getActiveLeases()) + " / " + (p.getMaxLeases() == null ? "∞" : p.getMaxLeases())).setHeader("Leases / budget").setAutoWidth(true);
            profiles.addColumn(ModelProfile::getContextWindow).setHeader("Window").setAutoWidth(true);
            profiles.addColumn(p -> p.getLimitations() == null || p.getLimitations().getLeaseReductions() == null
                    || p.getLimitations().getLeaseReductions().isEmpty() ? "-"
                    : String.join(", ", p.getLimitations().getLeaseReductions().stream()
                            .map(r -> r.getProfileId() + " \u2212" + r.getLeases()).toList())).setHeader("Lease reductions").setAutoWidth(true);
            profiles.setAllRowsVisible(true);
            final var workers = new Grid<Worker>();
            workers.addColumn(Worker::getId).setHeader("Worker");
            workers.addColumn(Worker::getLoadedProfile).setHeader("Loaded");
            workers.addColumn(Worker::getState).setHeader("State");
            workers.setAllRowsVisible(true);
            final var label = new Span();
            profileGrids.put(name, profiles);
            workerGrids.put(name, workers);
            deckStatus.put(name, label);
            final var column = new VerticalLayout(new H3("DECK: " + name), label, profiles, workers);
            column.setPadding(false);
            decks.add(column);
        });
        add(decks, new H3("Clients"), clientGrid);
    }

    private record Snapshot(ai.strgm.dealer.api.model.DealerState dealer, String dealerError,
            Map<String, ai.strgm.deck.api.model.DeckState> decks) {}

    @Override
    protected void onAttach(final AttachEvent event) {
        apply(fetch());
        // A browser-side timer calls tick() every second; each response carries the grid updates.
        getElement().executeJs("const el = $0; clearInterval(el.__dealerTimer);"
                + " el.__dealerTimer = setInterval(() => el.$server.tick(), 1000);", getElement());
    }

    @Override
    protected void onDetach(final DetachEvent event) {
        getElement().executeJs("clearInterval($0.__dealerTimer)", getElement());
    }

    @ClientCallable
    public void tick() {
        apply(fetch());
    }

    /** Reads the metrics APIs off the UI lock; {@link #apply} then updates the grids under it. */
    private Snapshot fetch() {
        ai.strgm.dealer.api.model.DealerState dealer = null;
        String error = null;
        try {
            dealer = clients.dealer.getDealerState().getBody();
        } catch (Exception e) {
            error = e.getMessage();
        }
        final Map<String, ai.strgm.deck.api.model.DeckState> decks = new LinkedHashMap<>();
        clients.decks.forEach((name, api) -> {
            try {
                decks.put(name, api.getDeckState().getBody());
            } catch (Exception e) {
                decks.put(name, null);
            }
        });
        return new Snapshot(dealer, error, decks);
    }

    private void apply(final Snapshot snap) {
        final var state = snap.dealer();
        if (state != null) {
            sessions.setItems(state.getSessions());
            queue.setItems(state.getQueue());
            evictions.setItems(state.getEvictions().reversed());
            clientGrid.setItems(state.getClients());
            status.setText("DEALER: " + state.getSessions().size() + " sessions, " + state.getQueue().size() + " queued, "
                    + state.getEvictions().size() + " evictions · " + TIME.format(Instant.now()));
        } else {
            status.setText("DEALER unreachable: " + snap.dealerError());
        }
        snap.decks().forEach((name, deck) -> {
            if (deck == null) {
                deckStatus.get(name).setText("unreachable");
                return;
            }
            profileGrids.get(name).setItems(deck.getProfiles());
            final List<Worker> workers = deck.getWorkers() == null ? List.of() : deck.getWorkers();
            workerGrids.get(name).setItems(workers);
            workerGrids.get(name).setVisible(!workers.isEmpty());
            deckStatus.get(name).setText(deck.getImplementation().getValue()
                    + (deck.getProvider() == null ? "" : " · provider " + deck.getProvider())
                    + String.format(" · spend $%.6f", deck.getSpendUsd() == null ? 0 : deck.getSpendUsd()));
        });
    }

    private static String seconds(final Long ms) {
        return ms == null ? "-" : String.format("%.1f s", ms / 1000.0);
    }
}
