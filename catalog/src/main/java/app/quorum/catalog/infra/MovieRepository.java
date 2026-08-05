package app.quorum.catalog.infra;

import app.quorum.catalog.domain.Movie;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    @Query("""
            SELECT m
            FROM Movie m
            WHERE m.tmdbId IN :ids
            """)
    List<Movie> findAllMoviesByIds(@Param("ids") Collection<Long> ids);

    @Query(
            """
            SELECT m
            FROM Movie m
            WHERE m.syncedAt < :moment
            ORDER BY m.syncedAt ASC
            """)
    List<Movie> findMoviesSyncedBefore(@Param("moment") Instant moment);

    // Тот же запрос, но с Pageable — для фоновой пересинхронизации (шаг 12),
    // которая обязана брать фильмы пачками, а не всё разом.
    @Query(
            """
            SELECT m
            FROM Movie m
            WHERE m.syncedAt < :moment
            ORDER BY m.syncedAt ASC
            """)
    List<Movie> findMoviesSyncedBefore(@Param("moment") Instant moment, Pageable pageable);

    // Pageable задаёт и сортировку (voteAvg desc — вызывающий код), и лимит.
    @Query(
            """
            SELECT DISTINCT m
            FROM Movie m
            JOIN m.genres g
            WHERE g.genreId IN :genreIds
            """)
    List<Movie> findPopularByGenreIds(@Param("genreIds") Collection<Integer> genreIds, Pageable pageable);
}
