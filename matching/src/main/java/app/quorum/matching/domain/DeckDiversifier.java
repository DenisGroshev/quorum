package app.quorum.matching.domain;

import app.quorum.matching.config.MatchingProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DeckDiversifier {

    private final MatchingProperties properties;

    public DeckDiversifier(MatchingProperties properties) {
        this.properties = properties;
    }

    public List<ScoredCandidate> diversify(List<ScoredCandidate> sortedByScoreDescending, int deckSize) {
        int limit = (int) Math.floor(deckSize * properties.maxGenreShare());
        Map<Integer, Integer> genreCounts = new HashMap<>();
        List<ScoredCandidate> selected = new ArrayList<>(deckSize);
        List<ScoredCandidate> deferred = new ArrayList<>();

        for (ScoredCandidate sc : sortedByScoreDescending) {
            if (selected.size() >= deckSize) {
                break;
            }
            Integer primaryGenreId = sc.candidate().primaryGenreId();
            if (primaryGenreId == null) {
                selected.add(sc);
                continue;
            }
            int countSoFar = genreCounts.getOrDefault(primaryGenreId, 0);
            if (countSoFar < limit) {
                selected.add(sc);
                genreCounts.merge(primaryGenreId, 1, Integer::sum);
            } else {
                deferred.add(sc);
            }
        }

        for (ScoredCandidate sc : deferred) {
            if (selected.size() >= deckSize) {
                break;
            }
            selected.add(sc);
        }

        return selected;
    }
}
