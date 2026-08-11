package app.quorum.matching.domain;

import app.quorum.catalog.api.CatalogFacade;
import app.quorum.catalog.api.MovieDto;
import app.quorum.common.ContentLang;
import app.quorum.matching.config.MatchingProperties;
import app.quorum.session.api.SessionHistoryQueries;
import app.quorum.session.domain.DeckRequest;
import app.quorum.watchlist.api.WatchlistAgg;
import app.quorum.watchlist.api.WatchlistQueries;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CandidateCollector {

    private static final ContentLang CATALOG_LOOKUP_LANG = ContentLang.RU;

    private final WatchlistQueries watchlistQueries;
    private final CatalogFacade catalogFacade;
    private final SessionHistoryQueries sessionHistoryQueries;
    private final MatchingProperties properties;

    public CandidateCollector(
            WatchlistQueries watchlistQueries,
            CatalogFacade catalogFacade,
            SessionHistoryQueries sessionHistoryQueries,
            MatchingProperties properties) {
        this.watchlistQueries = watchlistQueries;
        this.catalogFacade = catalogFacade;
        this.sessionHistoryQueries = sessionHistoryQueries;
        this.properties = properties;
    }

    public List<Candidate> collect(DeckRequest request, Set<Integer> unionFavGenres) {
        Map<Long, WatchlistAgg> baseAgg = watchlistQueries.aggregateFor(request.memberIds()); // M-1

        List<MovieDto> fallbackMovies = fetchFallbackIfNeeded(request, baseAgg, unionFavGenres); // M-2/M-3

        Map<Long, MovieDto> catalogData = fetchAllCatalogData(baseAgg.keySet(), fallbackMovies); // M-2: one call

        List<Candidate> candidates = assembleCandidates(baseAgg, fallbackMovies, catalogData);

        return excludePastWinners(candidates, request); // M-4
    }

    private List<MovieDto> fetchFallbackIfNeeded(
            DeckRequest request, Map<Long, WatchlistAgg> baseAgg, Set<Integer> unionFavGenres) {
        if (request.watchlistOnly()) {
            return List.of(); // M-3
        }
        int needed = request.deckSize() * properties.candidateMultiplier() - baseAgg.size();
        if (needed <= 0 || unionFavGenres.isEmpty()) {
            return List.of();
        }

        List<MovieDto> popular = catalogFacade.popularByGenres(unionFavGenres, needed, CATALOG_LOOKUP_LANG);
        return popular.stream().filter(m -> !baseAgg.containsKey(m.tmdbId())).toList();
    }

    private Map<Long, MovieDto> fetchAllCatalogData(Set<Long> baseIds, List<MovieDto> fallbackMovies) {
        Set<Long> allIds = new HashSet<>(baseIds);
        for (MovieDto movie : fallbackMovies) {
            allIds.add(movie.tmdbId());
        }
        if (allIds.isEmpty()) {
            return Map.of();
        }
        return catalogFacade.getCached(allIds, CATALOG_LOOKUP_LANG);
    }

    private List<Candidate> assembleCandidates(
            Map<Long, WatchlistAgg> baseAgg, List<MovieDto> fallbackMovies, Map<Long, MovieDto> catalogData) {
        Map<Long, Candidate> byId = new LinkedHashMap<>();

        for (WatchlistAgg agg : baseAgg.values()) {
            MovieDto movie = catalogData.get(agg.tmdbId());
            if (movie == null) {
                continue;
            }
            byId.put(agg.tmdbId(), toCandidate(agg, movie, false));
        }

        for (MovieDto movie : fallbackMovies) {
            byId.putIfAbsent(movie.tmdbId(), toCandidate(null, movie, true));
        }

        return new ArrayList<>(byId.values());
    }

    private List<Candidate> excludePastWinners(List<Candidate> candidates, DeckRequest request) {
        Set<Long> pastWinners = sessionHistoryQueries.pastWinnersForExactMemberSet(Set.copyOf(request.memberIds()));
        if (pastWinners.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .filter(c -> !pastWinners.contains(c.tmdbId()))
                .toList();
    }

    private static Candidate toCandidate(WatchlistAgg agg, MovieDto movie, boolean fromFallback) {
        return new Candidate(
                movie.tmdbId(),
                agg != null ? agg.ownersCount() : 0,
                agg != null ? agg.seenCount() : 0,
                agg != null ? agg.medianAddedAt() : null,
                movie.genreIds(),
                movie.primaryGenreId(),
                movie.runtimeMin(),
                movie.voteAverage(),
                movie.voteCount(),
                movie.originalLanguage(),
                fromFallback);
    }
}
