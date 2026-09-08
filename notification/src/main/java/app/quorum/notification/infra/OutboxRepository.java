package app.quorum.notification.infra;

import app.quorum.notification.domain.OutboxMessage;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query(
            """
            select m from OutboxMessage m
            where m.status = app.quorum.notification.domain.OutboxStatus.PENDING
              and m.nextAttemptAt <= :now
            order by m.createdAt asc
            """)
    List<OutboxMessage> findReadyToSend(@Param("now") Instant now, Pageable pageable);

    boolean existsByEventKey(String eventKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select m from OutboxMessage m where m.id = :id")
    Optional<OutboxMessage> findLockedById(@Param("id") UUID id);
}
