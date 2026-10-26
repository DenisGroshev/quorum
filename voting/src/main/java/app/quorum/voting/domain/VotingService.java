package app.quorum.voting.domain;

import app.quorum.catalog.api.CatalogFacade;
import app.quorum.catalog.api.MovieDto;
import app.quorum.common.ContentLang;
import app.quorum.common.analytics.AnalyticsEvent;
import app.quorum.common.exception.DomainRuleException;
import app.quorum.common.exception.SessionStateConflictException;
import app.quorum.session.api.SessionGuard;
import app.quorum.session.api.VotingContext;
import app.quorum.session.api.VotingFinishedEvent;
import app.quorum.voting.infra.VoteRepository;
import app.quorum.voting.infra.VoteTallyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VotingService {

    private static final ContentLang CATALOG_LOOKUP_LANG = ContentLang.RU;

    private final VoteRepository voteRepository;
    private final VoteTallyRepository voteTallyRepository;
    private final SessionGuard sessionGuard;
    private final CatalogFacade catalogFacade;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final double vetoThreshold;
    private final WinnerCalculator winnerCalculator = new WinnerCalculator();

    public VotingService(
            VoteRepository voteRepository,
            VoteTallyRepository voteTallyRepository,
            SessionGuard sessionGuard,
            CatalogFacade catalogFacade,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            @Value("${quorum.vote.veto-threshold}") double vetoThreshold) {
        this.voteRepository = voteRepository;
        this.voteTallyRepository = voteTallyRepository;
        this.sessionGuard = sessionGuard;
        this.catalogFacade = catalogFacade;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.vetoThreshold = vetoThreshold;
    }

    @Transactional
    public void castVote(UUID sessionId, UUID userId, long tmdbId, VoteValue value) {
        VotingContext context = sessionGuard.requireVotingMember(sessionId, userId);
        if (!context.votingOpen()) {
            throw new SessionStateConflictException("session.illegal-transition");
        }
        if (!context.deckTmdbIds().contains(tmdbId)) {
            throw new DomainRuleException("vote.movie-not-in-deck");
        }

        VoteKey key = new VoteKey(sessionId, userId, tmdbId);
        Vote existing = voteRepository.findById(key).orElse(null);

        boolean alreadySuperlikedThisMovie = existing != null && existing.getValue() == VoteValue.SUPERLIKE;
        if (value == VoteValue.SUPERLIKE
                && !alreadySuperlikedThisMovie
                && voteRepository.existsByKey_SessionIdAndKey_UserIdAndValue(sessionId, userId, VoteValue.SUPERLIKE)) {
            throw new DomainRuleException("vote.superlike-exhausted");
        }

        if (existing != null) {
            existing.change(value, clock);
            voteRepository.save(existing);
        } else {
            voteRepository.save(new Vote(key, value, clock));
        }
        eventPublisher.publishEvent(new AnalyticsEvent("vote_cast", userId, Map.of("voteType", value.name())));

        long votesCast = voteRepository.countByKey_SessionIdAndKey_UserId(sessionId, userId);
        if (votesCast >= context.deckTmdbIds().size()) {
            boolean allFinished = sessionGuard.markMemberFinishedVoting(sessionId, userId, clock);
            if (allFinished) {
                finishVoting(sessionId);
            }
        }
    }

    @Transactional
    public boolean finishVoting(UUID sessionId) {
        WinnerResult result = computeResult(sessionId);

        boolean concluded = sessionGuard.concludeVoting(sessionId, result.winnerTmdbId(), result.contested(), clock);
        if (concluded) {
            eventPublisher.publishEvent(new VotingFinishedEvent(sessionId));
            if (result.winnerTmdbId() != null) {
                int votersCount = (int) voteRepository.countDistinctVotersBySessionId(sessionId);
                eventPublisher.publishEvent(new AnalyticsEvent(
                        "session_decided", null, Map.of("contested", result.contested(), "votedCount", votersCount)));
            }
        }
        return concluded;
    }

    @Transactional(readOnly = true)
    public WinnerResult currentResult(UUID sessionId) {
        return computeResult(sessionId);
    }

    private WinnerResult computeResult(UUID sessionId) {
        List<VoteTally> tallies = voteTallyRepository.tallyFor(sessionId);
        List<WinnerCandidate> candidates = buildCandidates(sessionId, tallies);
        int votersCount = (int) voteRepository.countDistinctVotersBySessionId(sessionId);
        return winnerCalculator.calculate(candidates, votersCount, vetoThreshold);
    }

    private List<WinnerCandidate> buildCandidates(UUID sessionId, List<VoteTally> tallies) {
        Map<Long, BigDecimal> deckScores = sessionGuard.deckScores(sessionId);
        List<Long> tmdbIds = tallies.stream().map(VoteTally::tmdbId).toList();
        Map<Long, MovieDto> movies = catalogFacade.getCached(tmdbIds, CATALOG_LOOKUP_LANG);

        return tallies.stream()
                .map(tally -> {
                    MovieDto movie = movies.get(tally.tmdbId());
                    Integer runtimeMin = movie != null ? movie.runtimeMin() : null;
                    return new WinnerCandidate(tally, deckScores.get(tally.tmdbId()), runtimeMin);
                })
                .toList();
    }
}
