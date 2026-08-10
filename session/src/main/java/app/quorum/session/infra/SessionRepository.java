package app.quorum.session.infra;

import app.quorum.common.SessionState;
import app.quorum.session.domain.Session;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Session s where s.id = :id")
    Optional<Session> lockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Session s where s.inviteCode = :inviteCode")
    Optional<Session> lockByInviteCode(@Param("inviteCode") String inviteCode);

    long countByHostIdAndStateIn(UUID hostId, Collection<SessionState> states);

    List<Session> findByStateAndCreatedAtBefore(SessionState state, Instant cutoff);

    List<Session> findByStateAndVotingDeadlineAtBefore(SessionState state, Instant cutoff);

    List<Session> findByStateAndDecidedAtBefore(SessionState state, Instant cutoff);

    List<Session> findByStateAndCreatedAtBefore(SessionState state, Instant cutoff, Pageable pageable);

    List<Session> findByStateAndVotingDeadlineAtBefore(SessionState state, Instant cutoff, Pageable pageable);

    List<Session> findByStateAndDecidedAtBefore(SessionState state, Instant cutoff, Pageable pageable);

    List<Session> findByMembers_Key_UserIdOrderByCreatedAtDesc(UUID userId);

    List<Session> findByMembers_Key_UserIdAndStateInOrderByCreatedAtDesc(UUID userId, Collection<SessionState> states);

    @Query(
            """
            select s from Session s join s.members m
            where m.key.userId = :userId
              and (s.state in :states or (s.state = app.quorum.common.SessionState.CANCELLED and s.hostId = :userId))
            order by s.createdAt desc
            """)
    List<Session> findHistoryFor(@Param("userId") UUID userId, @Param("states") Collection<SessionState> states);

    long countByMembers_Key_UserIdAndState(UUID userId, SessionState state);
}
