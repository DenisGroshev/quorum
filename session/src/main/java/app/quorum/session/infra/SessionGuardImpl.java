package app.quorum.session.infra;

import app.quorum.common.SessionState;
import app.quorum.common.exception.SessionForbiddenException;
import app.quorum.session.api.DeckCardInfo;
import app.quorum.session.api.NotificationSessionView;
import app.quorum.session.api.SessionGuard;
import app.quorum.session.api.VotingContext;
import app.quorum.session.api.VotingProgress;
import app.quorum.session.domain.DeckItem;
import app.quorum.session.domain.DeckReason;
import app.quorum.session.domain.Session;
import app.quorum.session.domain.SessionMember;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SessionGuardImpl implements SessionGuard {

    private final SessionRepository sessionRepository;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public SessionGuardImpl(SessionRepository sessionRepository, PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional(readOnly = true)
    public VotingContext requireVotingMember(UUID sessionId, UUID userId) {
        Session session = sessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new SessionForbiddenException("session.forbidden"));
        if (!session.isMember(userId)) {
            throw new SessionForbiddenException("session.forbidden");
        }
        List<Long> deckTmdbIds =
                session.getDeck().stream().map(item -> item.getKey().tmdbId()).toList();
        return new VotingContext(sessionId, deckTmdbIds, session.getState() == SessionState.VOTING);
    }

    @Override
    public boolean markMemberFinishedVoting(UUID sessionId, UUID userId, Clock clock) {
        return requiresNewTransactionTemplate.execute(status -> {
            Session session = sessionRepository
                    .lockById(sessionId)
                    .orElseThrow(() -> new SessionForbiddenException("session.forbidden"));
            boolean allFinished = session.markMemberFinishedVoting(userId, clock);
            sessionRepository.saveAndFlush(session);
            return allFinished;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> deckScores(UUID sessionId) {
        Session session = sessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new SessionForbiddenException("session.forbidden"));
        return session.getDeck().stream()
                .collect(Collectors.toMap(item -> item.getKey().tmdbId(), DeckItem::getScore));
    }

    @Override
    public boolean concludeVoting(UUID sessionId, Long winnerTmdbId, boolean contested, Clock clock) {
        return requiresNewTransactionTemplate.execute(status -> {
            Session session = sessionRepository
                    .lockById(sessionId)
                    .orElseThrow(() -> new SessionForbiddenException("session.forbidden"));
            if (session.getState() != SessionState.VOTING) {
                return false;
            }
            if (winnerTmdbId == null) {
                session.expire();
            } else {
                session.decide(winnerTmdbId, contested, clock);
            }
            sessionRepository.saveAndFlush(session);
            return true;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> votingSessionsPastDeadline(Instant cutoff, int limit) {
        return sessionRepository
                .findByStateAndVotingDeadlineAtBefore(SessionState.VOTING, cutoff, PageRequest.of(0, limit))
                .stream()
                .map(Session::getId)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeckCardInfo> deckCards(UUID sessionId) {
        Session session = sessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new SessionForbiddenException("session.forbidden"));
        return session.getDeck().stream()
                .map(item -> {
                    List<DeckReason> reasons = item.getReasons();
                    DeckReason primary = reasons.isEmpty() ? null : reasons.get(0);
                    return new DeckCardInfo(
                            item.getKey().tmdbId(),
                            primary != null ? primary.code() : null,
                            primary != null ? primary.args() : Map.of());
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public VotingProgress votingProgress(UUID sessionId) {
        Session session = sessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new SessionForbiddenException("session.forbidden"));
        int total = session.getMembers().size();
        int finished = (int) session.getMembers().stream()
                .filter(m -> m.getCompletedVotingAt() != null)
                .count();
        return new VotingProgress(total, finished);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireHost(UUID sessionId, UUID userId) {
        Session session = sessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new SessionForbiddenException("session.forbidden"));
        if (!session.getHostId().equals(userId)) {
            throw new SessionForbiddenException("session.forbidden");
        }
    }

    @Override
    public NotificationSessionView notificationView(UUID sessionId) {
        return requiresNewTransactionTemplate.execute(status -> {
            Session session = sessionRepository
                    .findById(sessionId)
                    .orElseThrow(() -> new SessionForbiddenException("session.forbidden"));
            List<UUID> memberIds =
                    session.getMembers().stream().map(SessionMember::getUserId).toList();
            return new NotificationSessionView(
                    session.getHostId(),
                    session.getTitle(),
                    session.getInviteCode(),
                    memberIds,
                    session.getWinnerTmdbId(),
                    session.getVotingDeadlineAt());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> votingSessionsNearingDeadline(Instant from, Instant to, int limit) {
        return sessionRepository
                .findByStateAndVotingDeadlineAtBetween(SessionState.VOTING, from, to, PageRequest.of(0, limit))
                .stream()
                .map(Session::getId)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> membersNotFinishedVoting(UUID sessionId) {
        Session session = sessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new SessionForbiddenException("session.forbidden"));
        return session.getMembers().stream()
                .filter(m -> m.getCompletedVotingAt() == null)
                .map(SessionMember::getUserId)
                .toList();
    }
}
