package app.quorum.voting.infra;

import app.quorum.voting.domain.Vote;
import app.quorum.voting.domain.VoteKey;
import app.quorum.voting.domain.VoteValue;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoteRepository extends JpaRepository<Vote, VoteKey> {

    long countByKey_SessionIdAndKey_UserId(UUID sessionId, UUID userId);

    boolean existsByKey_SessionIdAndKey_UserIdAndValue(UUID sessionId, UUID userId, VoteValue value);

    @Query("SELECT COUNT(DISTINCT v.key.userId) FROM Vote v WHERE v.key.sessionId = :sessionId")
    long countDistinctVotersBySessionId(@Param("sessionId") UUID sessionId);

    List<Vote> findByKey_SessionIdAndKey_UserId(UUID sessionId, UUID userId);

    List<Vote> findByKey_SessionIdAndValue(UUID sessionId, VoteValue value);
}
