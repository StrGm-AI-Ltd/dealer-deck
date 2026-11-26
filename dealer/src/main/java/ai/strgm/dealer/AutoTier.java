package ai.strgm.dealer;

import java.util.List;

/** A virtual {@code auto-*} model: its name, and the smallest context window a profile needs to serve it. */
record AutoTier(String name, int contextWindow) {
    /** The tiers DEALER lists in {@code /v1/models}, smallest first. */
    static final List<AutoTier> ALL = List.of(
            new AutoTier("auto-32k", 32_768), new AutoTier("auto-65k", 65_536), new AutoTier("auto-128k", 131_072));
}
