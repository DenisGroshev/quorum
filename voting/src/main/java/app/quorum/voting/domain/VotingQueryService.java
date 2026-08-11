package app.quorum.voting.domain;

import app.quorum.catalog.api.CatalogFacade;
import app.quorum.catalog.api.MovieDto;
import app.quorum.common.ContentLang;
import app.quorum.session.api.DeckCardInfo;
import app.quorum.session.api.SessionGuard;
import app.quorum.session.api.VotingContext;
import app.quorum.session.api.VotingProgress;
import app.quorum.user.api.UserBrief;
import app.quorum.user.api.UserQueries;
import app.quorum.voting.infra.VoteRepository;
import app.quorum.voting.web.dto.DeckCardResponse;
import app.quorum.voting.web.dto.DeckResponse;
import app.quorum.voting.web.dto.MovieSummary;
import app.quorum.voting.web.dto.ProgressResponse;
import app.quorum.voting.web.dto.ResultResponse;
import app.quorum.voting.web.dto.SuperlikeEntry;
import app.quorum.voting.web.dto.TopEntry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VotingQueryService {

    private static final ContentLang CATALOG_LOOKUP_LANG = ContentLang.RU;

    private final SessionGuard sessionGuard;
    private final VotingService votingService;
    private final VoteRepository voteRepository;
    private final CatalogFacade catalogFacade;
    private final UserQueries userQueries;

    public VotingQueryService(
            SessionGuard sessionGuard,
            VotingService votingService,
            VoteRepository voteRepository,
            CatalogFacade catalogFacade,
            UserQueries userQueries) {
        this.sessionGuard = sessionGuard;
        this.votingService = votingService;
        this.voteRepository = voteRepository;
        this.catalogFacade = catalogFacade;
        this.userQueries = userQueries;
    }

    @Transactional(readOnly = true)
    public DeckResponse deck(UUID sessionId, UUID userId) {
        sessionGuard.requireVotingMember(sessionId, userId);
        List<DeckCardInfo> cards = sessionGuard.deckCards(sessionId);

        Map<Long, VoteValue> myVotes = voteRepository.findByKey_SessionIdAndKey_UserId(sessionId, userId).stream()
                .collect(Collectors.toMap(v -> v.getKey().tmdbId(), Vote::getValue));

        List<Long> tmdbIds = cards.stream().map(DeckCardInfo::tmdbId).toList();
        Map<Long, MovieDto> movies = catalogFacade.getCached(tmdbIds, CATALOG_LOOKUP_LANG);

        List<DeckCardResponse> items = cards.stream()
                .map(card -> {
                    MovieDto movie = movies.get(card.tmdbId());
                    return new DeckCardResponse(
                            card.tmdbId(),
                            movie != null ? movie.title() : null,
                            movie != null ? movie.posterUrl() : null,
                            movie != null ? movie.releaseYear() : null,
                            movie != null ? movie.genreIds() : Set.of(),
                            movie != null ? movie.runtimeMin() : null,
                            movie != null ? movie.voteAverage() : null,
                            card.reasonCode(),
                            card.reasonArgs(),
                            myVotes.get(card.tmdbId()));
                })
                .toList();
        return new DeckResponse(items);
    }

    @Transactional(readOnly = true)
    public ProgressResponse progress(UUID sessionId, UUID userId) {
        VotingContext context = sessionGuard.requireVotingMember(sessionId, userId);
        VotingProgress progress = sessionGuard.votingProgress(sessionId);
        long myVotes = voteRepository.countByKey_SessionIdAndKey_UserId(sessionId, userId);
        int remaining = context.deckTmdbIds().size() - (int) myVotes;
        return new ProgressResponse(progress.totalMembers(), progress.finishedMembers(), remaining);
    }

    @Transactional(readOnly = true)
    public ResultResponse result(UUID sessionId, UUID userId) {
        sessionGuard.requireVotingMember(sessionId, userId);
        WinnerResult result = votingService.currentResult(sessionId);
        List<Vote> superlikeVotes = voteRepository.findByKey_SessionIdAndValue(sessionId, VoteValue.SUPERLIKE);

        Set<Long> tmdbIds = new LinkedHashSet<>();
        if (result.winnerTmdbId() != null) {
            tmdbIds.add(result.winnerTmdbId());
        }
        result.top3().forEach(t -> tmdbIds.add(t.tmdbId()));
        superlikeVotes.forEach(v -> tmdbIds.add(v.getKey().tmdbId()));
        Map<Long, MovieDto> movies = catalogFacade.getCached(tmdbIds, CATALOG_LOOKUP_LANG);

        Set<UUID> voterIds =
                superlikeVotes.stream().map(v -> v.getKey().userId()).collect(Collectors.toSet());
        Map<UUID, UserBrief> voters = userQueries.briefs(voterIds);

        MovieSummary winner = result.winnerTmdbId() == null ? null : movieSummary(result.winnerTmdbId(), movies);
        List<TopEntry> top3 = result.top3().stream()
                .map(t -> {
                    MovieDto movie = movies.get(t.tmdbId());
                    return new TopEntry(t.tmdbId(), movie != null ? movie.title() : null, t.points(), t.superlikes());
                })
                .toList();
        List<SuperlikeEntry> superlikes = superlikeVotes.stream()
                .map(v -> {
                    MovieDto movie = movies.get(v.getKey().tmdbId());
                    UserBrief voter = voters.get(v.getKey().userId());
                    return new SuperlikeEntry(
                            v.getKey().tmdbId(),
                            movie != null ? movie.title() : null,
                            voter != null ? voter.displayName() : null);
                })
                .toList();

        return new ResultResponse(winner, result.contested(), top3, superlikes);
    }

    private static MovieSummary movieSummary(long tmdbId, Map<Long, MovieDto> movies) {
        MovieDto movie = movies.get(tmdbId);
        return new MovieSummary(tmdbId, movie != null ? movie.title() : null, movie != null ? movie.posterUrl() : null);
    }
}
