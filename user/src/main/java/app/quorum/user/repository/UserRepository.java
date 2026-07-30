package app.quorum.user.repository;

import app.quorum.user.domain.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByTgUserId(Long tgUserId);

    @Query("select u.id, g from User u join u.favGenreIds g where u.id in :ids")
    List<Object[]> findFavoriteGenrePairs(@Param("ids") Collection<UUID> ids);
}
