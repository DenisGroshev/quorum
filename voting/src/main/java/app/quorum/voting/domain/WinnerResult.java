package app.quorum.voting.domain;

import jakarta.annotation.Nullable;
import java.util.List;

public record WinnerResult(@Nullable Long winnerTmdbId, boolean contested, List<VoteTally> top3) {

    public static WinnerResult empty() {
        return new WinnerResult(null, false, List.of());
    }
}
