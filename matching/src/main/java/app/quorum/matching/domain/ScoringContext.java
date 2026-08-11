package app.quorum.matching.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record ScoringContext(
        int participantCount, List<Set<Integer>> memberFavGenres, Set<String> memberLanguageCodes, Instant now) {}
