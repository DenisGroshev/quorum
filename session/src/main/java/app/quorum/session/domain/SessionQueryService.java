package app.quorum.session.domain;

import app.quorum.catalog.api.CatalogFacade;
import app.quorum.catalog.api.MovieDto;
import app.quorum.common.ContentLang;
import app.quorum.common.SessionState;
import app.quorum.session.infra.SessionRepository;
import app.quorum.session.web.dto.SessionHistoryItem;
import app.quorum.session.web.dto.SessionSummary;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SessionQueryService {

    private static final List<SessionState> ACTIVE_STATES =
            List.of(SessionState.LOBBY, SessionState.VOTING, SessionState.DECIDED);
    private static final List<SessionState> HISTORY_MEMBER_STATES =
            List.of(SessionState.WATCHED, SessionState.ARCHIVED);

    private final SessionRepository sessionRepository;
    private final CatalogFacade catalogFacade;

    public SessionQueryService(SessionRepository sessionRepository, CatalogFacade catalogFacade) {
        this.sessionRepository = sessionRepository;
        this.catalogFacade = catalogFacade;
    }

    public List<SessionSummary> listActive(UUID userId) {
        return sessionRepository.findByMembers_Key_UserIdAndStateInOrderByCreatedAtDesc(userId, ACTIVE_STATES).stream()
                .map(s -> new SessionSummary(s.getId(), s.getTitle(), s.getState(), s.getCreatedAt()))
                .toList();
    }

    public List<SessionHistoryItem> listHistory(UUID userId, ContentLang lang) {
        List<Session> sessions = sessionRepository.findHistoryFor(userId, HISTORY_MEMBER_STATES);

        List<Long> winnerIds = sessions.stream()
                .map(Session::getWinnerTmdbId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, MovieDto> winners = winnerIds.isEmpty() ? Map.of() : catalogFacade.getCached(winnerIds, lang);

        return sessions.stream()
                .map(s -> {
                    MovieDto winner = s.getWinnerTmdbId() != null ? winners.get(s.getWinnerTmdbId()) : null;
                    return new SessionHistoryItem(
                            s.getId(),
                            s.getTitle(),
                            s.getState(),
                            s.getCreatedAt(),
                            s.getWinnerTmdbId(),
                            winner != null ? winner.title() : null,
                            winner != null ? winner.posterUrl() : null);
                })
                .toList();
    }

    public long countWatched(UUID userId) {
        return sessionRepository.countByMembers_Key_UserIdAndState(userId, SessionState.WATCHED);
    }
}
