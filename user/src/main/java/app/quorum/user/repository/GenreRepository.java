package app.quorum.user.repository;

import app.quorum.user.domain.Genre;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenreRepository extends JpaRepository<Genre, Integer> {
    long countByIdIn(Collection<Integer> ids);
}
