package ai.strgm.dealer.model;

import ai.strgm.types.LeaseId;

/** A request placed on its session's lease: the profile it runs on, and the lease's id. */
public record RequestPlacement(Session session, ModelProfileRef profileRef, LeaseId leaseId) {}
