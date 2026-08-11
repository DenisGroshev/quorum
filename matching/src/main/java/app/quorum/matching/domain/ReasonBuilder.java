package app.quorum.matching.domain;

import app.quorum.matching.config.MatchingProperties;
import app.quorum.session.domain.DeckReason;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ReasonBuilder {

    private static final BigDecimal HIGH_RATING_THRESHOLD = new BigDecimal("7.5");

    private final MatchingProperties properties;

    public ReasonBuilder(MatchingProperties properties) {
        this.properties = properties;
    }

    public List<DeckReason> buildReasons(Candidate candidate, ScoringContext context, Map<Integer, String> genreNames) {
        List<DeckReason> reasons = new ArrayList<>(4);

        if (candidate.ownersCount() > 0) {
            reasons.add(new DeckReason(
                    "IN_WATCHLIST_OF_N",
                    Map.of("count", candidate.ownersCount(), "total", context.participantCount())));
        }

        Set<Integer> matchedGenres = matchedGenres(candidate, context);
        if (!matchedGenres.isEmpty()) {
            List<String> names = matchedGenres.stream()
                    .map(genreNames::get)
                    .filter(Objects::nonNull)
                    .toList();
            if (!names.isEmpty()) {
                reasons.add(new DeckReason("GENRE_MATCH", Map.of("genres", names)));
            }
        }

        if (candidate.voteAverage().compareTo(HIGH_RATING_THRESHOLD) > 0
                && candidate.voteCount() >= properties.minVoteCountForRating()) {
            reasons.add(new DeckReason("HIGH_RATING", Map.of("value", candidate.voteAverage())));
        }

        if (candidate.fromFallback()) {
            reasons.add(new DeckReason("POPULAR_FALLBACK", Map.of()));
        }

        return reasons;
    }

    private static Set<Integer> matchedGenres(Candidate candidate, ScoringContext context) {
        Set<Integer> unionFavGenres = new HashSet<>();
        for (Set<Integer> favGenres : context.memberFavGenres()) {
            unionFavGenres.addAll(favGenres);
        }
        Set<Integer> matched = new HashSet<>(candidate.genreIds());
        matched.retainAll(unionFavGenres);
        return matched;
    }
}
