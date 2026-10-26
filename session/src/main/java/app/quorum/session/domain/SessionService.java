package app.quorum.session.domain;

import app.quorum.common.SessionState;
import app.quorum.common.analytics.AnalyticsEvent;
import app.quorum.common.exception.DomainRuleException;
import app.quorum.common.exception.SessionNotFoundException;
import app.quorum.session.api.SessionWatchedEvent;
import app.quorum.session.infra.SessionRepository;
import app.quorum.session.web.dto.CreateSessionRequest;
import app.quorum.session.web.dto.UpdateSessionParamsRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SessionService {

    private static final Set<SessionState> ACTIVE_HOST_STATES = Set.of(SessionState.LOBBY, SessionState.VOTING);
    private static final Set<Integer> ALLOWED_DECK_SIZES = Set.of(10, 20, 30);
    private static final Set<Integer> ALLOWED_VOTE_DEADLINE_MINUTES = Set.of(30, 120, 1440);
    private static final int MAX_TITLE_LENGTH = 128;
    private static final int MAX_INVITE_CODE_ATTEMPTS = 3;

    private final SessionRepository sessionRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final DeckBuilder deckBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final int hostLimit;
    private final String botUsername;
    private final String miniAppName;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public SessionService(
            SessionRepository sessionRepository,
            InviteCodeGenerator inviteCodeGenerator,
            DeckBuilder deckBuilder,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            @Value("${quorum.limits.active-hosted-sessions}") int hostLimit,
            @Value("${quorum.telegram.bot-username}") String botUsername,
            @Value("${quorum.telegram.miniapp-name}") String miniAppName,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.deckBuilder = deckBuilder;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.hostLimit = hostLimit;
        this.botUsername = botUsername;
        this.miniAppName = miniAppName;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public SessionCreationResult create(UUID hostId, CreateSessionRequest request) {
        SessionParams params = resolveParams(request);

        if (sessionRepository.countByHostIdAndStateIn(hostId, ACTIVE_HOST_STATES) >= hostLimit) {
            throw new DomainRuleException("session.host-limit");
        }

        DataIntegrityViolationException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_INVITE_CODE_ATTEMPTS; attempt++) {
            try {
                Session saved = requiresNewTransactionTemplate.execute(status -> {
                    Session session = Session.create(hostId, params, inviteCodeGenerator.generate(), clock);
                    Session result = sessionRepository.saveAndFlush(session);
                    eventPublisher.publishEvent(
                            new AnalyticsEvent("session_created", hostId, Map.of("deckSize", params.deckSize())));
                    return result;
                });
                return new SessionCreationResult(saved, inviteLink(saved.getInviteCode()));
            } catch (DataIntegrityViolationException collision) {
                lastFailure = collision;
            }
        }
        throw lastFailure;
    }

    public UUID join(String inviteCode, UUID userId) {
        return requiresNewTransactionTemplate.execute(status -> {
            Session session = sessionRepository
                    .lockByInviteCode(inviteCode)
                    .orElseThrow(() -> new SessionNotFoundException("session.not-found"));
            boolean newMember = !session.isMember(userId);
            session.join(userId, clock);
            sessionRepository.saveAndFlush(session);
            if (newMember) {
                eventPublisher.publishEvent(new AnalyticsEvent("session_joined", userId));
            }
            return session.getId();
        });
    }

    public void startVoting(UUID sessionId, UUID actorId) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            Session session = requireMember(sessionId, actorId);
            List<UUID> memberIds =
                    session.getMembers().stream().map(SessionMember::getUserId).toList();
            DeckResult deckResult = deckBuilder.build(
                    new DeckRequest(session.getId(), memberIds, session.getDeckSize(), session.isWatchlistOnly()));
            session.startVoting(actorId, deckResult, deckResult.configVersion(), clock);
            sessionRepository.saveAndFlush(session);
            eventPublisher.publishEvent(new AnalyticsEvent(
                    "voting_started",
                    actorId,
                    Map.of(
                            "participantCount", memberIds.size(),
                            "deckSize", deckResult.movies().size())));
        });
    }

    public void cancelSession(UUID sessionId, UUID actorId) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            Session session = requireMember(sessionId, actorId);
            session.cancel(actorId);
            sessionRepository.saveAndFlush(session);
        });
    }

    public void kick(UUID sessionId, UUID actorId, UUID targetId) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            Session session = requireMember(sessionId, actorId);
            session.kick(actorId, targetId);
            sessionRepository.saveAndFlush(session);
        });
    }

    public void updateParams(UUID sessionId, UUID actorId, UpdateSessionParamsRequest request) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            Session session = requireMember(sessionId, actorId);
            SessionParams params = resolveUpdateParams(session, request);
            session.updateParams(actorId, params);
            sessionRepository.saveAndFlush(session);
        });
    }

    public void markWatched(UUID sessionId, UUID actorId) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            Session session = requireMember(sessionId, actorId);
            session.markWatched(actorId, clock);
            sessionRepository.saveAndFlush(session);

            List<UUID> memberIds =
                    session.getMembers().stream().map(SessionMember::getUserId).toList();
            eventPublisher.publishEvent(new SessionWatchedEvent(memberIds, session.getWinnerTmdbId()));
            eventPublisher.publishEvent(new AnalyticsEvent("session_watched", actorId));
        });
    }

    public void rate(UUID sessionId, UUID actorId, short value) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            Session session = requireMember(sessionId, actorId);
            session.rateBy(actorId, value, clock);
            sessionRepository.saveAndFlush(session);
            eventPublisher.publishEvent(new AnalyticsEvent("rating_set", actorId, Map.of("value", (int) value)));
        });
    }

    private Session requireMember(UUID sessionId, UUID actorId) {
        Session session = sessionRepository
                .lockById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("session.not-found"));
        if (!session.isMember(actorId)) {
            throw new SessionNotFoundException("session.not-found");
        }
        return session;
    }

    private SessionParams resolveParams(CreateSessionRequest request) {
        String title = request.title() != null && !request.title().isBlank() ? request.title() : defaultTitle();
        int deckSize = request.deckSize() != null ? request.deckSize() : 20;
        int voteDeadlineMinutes = request.voteDeadlineMinutes() != null ? request.voteDeadlineMinutes() : 120;

        SessionParams params = new SessionParams(title, deckSize, voteDeadlineMinutes, request.watchlistOnly());
        validateParams(params);
        return params;
    }

    private SessionParams resolveUpdateParams(Session current, UpdateSessionParamsRequest request) {
        String title = request.title() != null && !request.title().isBlank() ? request.title() : current.getTitle();
        int deckSize = request.deckSize() != null ? request.deckSize() : current.getDeckSize();
        int voteDeadlineMinutes = request.voteDeadlineMinutes() != null
                ? request.voteDeadlineMinutes()
                : current.getVoteDeadlineMinutes();
        boolean watchlistOnly = request.watchlistOnly() != null ? request.watchlistOnly() : current.isWatchlistOnly();

        SessionParams params = new SessionParams(title, deckSize, voteDeadlineMinutes, watchlistOnly);
        validateParams(params);
        return params;
    }

    private void validateParams(SessionParams params) {
        if (params.title().length() > MAX_TITLE_LENGTH) {
            throw new DomainRuleException("validation.failed");
        }
        if (!ALLOWED_DECK_SIZES.contains(params.deckSize())) {
            throw new DomainRuleException("validation.failed");
        }
        if (!ALLOWED_VOTE_DEADLINE_MINUTES.contains(params.voteDeadlineMinutes())) {
            throw new DomainRuleException("validation.failed");
        }
    }

    private String defaultTitle() {
        return "Киновечер " + LocalDate.now(clock);
    }

    private String inviteLink(String inviteCode) {
        return "https://t.me/" + botUsername + "/" + miniAppName + "?startapp=" + inviteCode;
    }
}
