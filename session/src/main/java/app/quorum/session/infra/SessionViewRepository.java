package app.quorum.session.infra;

import app.quorum.common.SessionState;
import app.quorum.common.exception.SessionNotFoundException;
import app.quorum.session.web.dto.MemberView;
import app.quorum.session.web.dto.SessionViewResponse;
import app.quorum.user.api.UserBrief;
import app.quorum.user.api.UserQueries;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SessionViewRepository {

    static final String SESSION_AND_MEMBERS_SQL =
            """
            SELECT s.id, s.title, s.state, s.deck_size, s.voting_deadline_at, s.winner_tmdb_id, s.invite_code,
                   m.user_id, m.is_host, m.completed_voting_at
            FROM sessions s
            JOIN session_members m ON m.session_id = s.id
            WHERE s.id = :sessionId
            """;

    private final JdbcClient jdbcClient;
    private final UserQueries userQueries;
    private final String botUsername;
    private final String miniAppName;

    public SessionViewRepository(
            JdbcClient jdbcClient,
            UserQueries userQueries,
            @Value("${quorum.telegram.bot-username}") String botUsername,
            @Value("${quorum.telegram.miniapp-name}") String miniAppName) {
        this.jdbcClient = jdbcClient;
        this.userQueries = userQueries;
        this.botUsername = botUsername;
        this.miniAppName = miniAppName;
    }

    public SessionViewResponse viewFor(UUID sessionId, UUID viewerId) {
        List<MemberRow> rows = jdbcClient
                .sql(SESSION_AND_MEMBERS_SQL)
                .param("sessionId", sessionId)
                .query((rs, rowNum) -> new MemberRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("state"),
                        rs.getInt("deck_size"),
                        rs.getTimestamp("voting_deadline_at") != null
                                ? rs.getTimestamp("voting_deadline_at").toInstant()
                                : null,
                        rs.getObject("winner_tmdb_id", Long.class),
                        rs.getString("invite_code"),
                        rs.getObject("user_id", UUID.class),
                        rs.getBoolean("is_host"),
                        rs.getTimestamp("completed_voting_at") != null))
                .list();

        if (rows.isEmpty()) {
            throw new SessionNotFoundException("session.not-found");
        }
        boolean viewerIsMember = rows.stream().anyMatch(r -> r.userId().equals(viewerId));
        if (!viewerIsMember) {
            throw new SessionNotFoundException("session.not-found");
        }

        Map<UUID, UserBrief> briefs =
                userQueries.briefs(rows.stream().map(MemberRow::userId).toList());

        List<MemberView> members = rows.stream()
                .map(r -> {
                    UserBrief brief = briefs.get(r.userId());
                    return new MemberView(
                            r.userId(),
                            brief != null ? brief.displayName() : null,
                            brief != null ? brief.avatarUrl() : null,
                            r.isHost(),
                            r.hasFinishedVoting());
                })
                .toList();

        MemberRow first = rows.get(0);
        boolean viewerIsHost =
                rows.stream().anyMatch(r -> r.isHost() && r.userId().equals(viewerId));
        boolean isLobby = SessionState.LOBBY.name().equals(first.state());
        String inviteUrl = viewerIsHost && isLobby ? inviteLink(first.inviteCode()) : null;

        return new SessionViewResponse(
                first.sessionId(),
                first.title(),
                first.state(),
                viewerIsHost,
                members,
                first.deckSize(),
                first.votingDeadlineAt(),
                inviteUrl,
                first.winnerTmdbId());
    }

    private String inviteLink(String inviteCode) {
        return "https://t.me/" + botUsername + "/" + miniAppName + "?startapp=" + inviteCode;
    }

    private record MemberRow(
            UUID sessionId,
            String title,
            String state,
            int deckSize,
            Instant votingDeadlineAt,
            Long winnerTmdbId,
            String inviteCode,
            UUID userId,
            boolean isHost,
            boolean hasFinishedVoting) {}
}
