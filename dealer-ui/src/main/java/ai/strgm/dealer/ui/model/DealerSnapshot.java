package ai.strgm.dealer.ui.model;

import java.util.List;

/**
 * DEALER's state at one moment: its sessions, the lease requests in its queue, its recent evictions (oldest first), and
 * its clients. The DEALER API marks none of their fields required, so a field it may leave out is nullable here, except
 * counts, which are zero when it does.
 */
public record DealerSnapshot(List<SessionSnapshot> sessions, List<LeaseRequestSnapshot> queue, List<Eviction> evictions,
        List<ConfiguredClient> clients) {}
