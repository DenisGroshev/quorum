package app.quorum.matching.domain;

import app.quorum.common.ContentLang;
import app.quorum.matching.config.MatchingProperties;
import app.quorum.matching.infra.GenreNameRepository;
import app.quorum.session.domain.DeckBuilder;
import app.quorum.session.domain.DeckReason;
import app.quorum.session.domain.DeckRequest;
import app.quorum.session.domain.DeckResult;
import app.quorum.session.domain.ScoredMovie;
import app.quorum.user.api.UserQueries;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HeuristicDeckBuilder implements DeckBuilder {

    private static final Logger log = LoggerFactory.getLogger(HeuristicDeckBuilder.class);

    private final CandidateCollector candidateCollector;
    private final MovieScorer movieScorer;
    private final DeckDiversifier deckDiversifier;
    private final ReasonBuilder reasonBuilder;
    private final GenreNameRepository genreNameRepository;
    private final UserQueries userQueries;
    private final MatchingProperties properties;
    private final Clock clock;

    public HeuristicDeckBuilder(
            CandidateCollector candidateCollector,
            MovieScorer movieScorer,
            DeckDiversifier deckDiversifier,
            ReasonBuilder reasonBuilder,
            GenreNameRepository genreNameRepository,
            UserQueries userQueries,
            MatchingProperties properties,
            Clock clock) {
        this.candidateCollector = candidateCollector;
        this.movieScorer = movieScorer;
        this.deckDiversifier = deckDiversifier;
        this.reasonBuilder = reasonBuilder;
        this.genreNameRepository = genreNameRepository;
        this.userQueries = userQueries;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public DeckResult build(DeckRequest request) {
        ScoringContext context = buildScoringContext(request);

        List<Candidate> candidates = candidateCollector.collect(request, unionOf(context.memberFavGenres()));
        if (candidates.isEmpty()) {
            log.warn("No candidates available for session {}, returning an empty deck", request.sessionId());
            return new DeckResult(List.of(), properties.version());
        }

        List<ScoredCandidate> sorted = candidates.stream()
                .map(c -> new ScoredCandidate(c, movieScorer.score(c, context)))
                .sorted(Comparator.comparing(ScoredCandidate::score).reversed())
                .toList();

        List<ScoredCandidate> diversified = deckDiversifier.diversify(sorted, request.deckSize());

        List<ScoredCandidate> shuffled = new ArrayList<>(diversified);
        Collections.shuffle(shuffled, new Random(seedFor(request.sessionId())));

        List<ScoredMovie> movies = assembleWithReasons(shuffled, context);

        return new DeckResult(movies, properties.version());
    }

    private ScoringContext buildScoringContext(DeckRequest request) {
        if (request.memberIds().isEmpty()) {
            return new ScoringContext(0, List.of(), Set.of(), clock.instant());
        }

        Map<UUID, Set<Integer>> favGenresByMember = userQueries.favGenres(request.memberIds());
        Map<UUID, ContentLang> langsByMember = userQueries.contentLangs(request.memberIds());

        List<Set<Integer>> memberFavGenres = request.memberIds().stream()
                .map(id -> favGenresByMember.getOrDefault(id, Set.of()))
                .toList();
        Set<String> memberLanguageCodes = langsByMember.values().stream()
                .map(lang -> lang.name().toLowerCase())
                .collect(Collectors.toSet());

        return new ScoringContext(request.memberIds().size(), memberFavGenres, memberLanguageCodes, clock.instant());
    }

    private List<ScoredMovie> assembleWithReasons(List<ScoredCandidate> deck, ScoringContext context) {
        Set<Integer> neededGenreIds = new HashSet<>();
        for (ScoredCandidate sc : deck) {
            neededGenreIds.addAll(sc.candidate().genreIds());
        }
        Map<Integer, String> genreNames = genreNameRepository.namesRu(neededGenreIds);

        List<ScoredMovie> movies = new ArrayList<>(deck.size());
        for (ScoredCandidate sc : deck) {
            List<DeckReason> reasons = reasonBuilder.buildReasons(sc.candidate(), context, genreNames);
            movies.add(new ScoredMovie(sc.candidate().tmdbId(), sc.score(), reasons));
        }
        return movies;
    }

    private static Set<Integer> unionOf(List<Set<Integer>> memberFavGenres) {
        Set<Integer> union = new HashSet<>();
        for (Set<Integer> genres : memberFavGenres) {
            union.addAll(genres);
        }
        return union;
    }

    private static long seedFor(UUID sessionId) {
        return sessionId.getMostSignificantBits() ^ sessionId.getLeastSignificantBits();
    }
}
