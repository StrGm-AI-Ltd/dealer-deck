package ai.strgm.dealer;

import ai.strgm.types.ModelName;
import java.util.List;

/** A virtual {@code auto-*} model: its name, and the smallest context window a profile needs to serve it. */
record AutoTier(ModelName name, int contextWindow) {
    /** The tiers DEALER lists in {@code /v1/models}, smallest first. */
    static final List<AutoTier> ALL = List.of(
            new AutoTier(new ModelName("auto-32k"), 32_768), new AutoTier(new ModelName("auto-65k"), 65_536),
            new AutoTier(new ModelName("auto-128k"), 131_072));
}
