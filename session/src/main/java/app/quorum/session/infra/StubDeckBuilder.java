package app.quorum.session.infra;

import app.quorum.session.domain.DeckBuilder;
import app.quorum.session.domain.DeckRequest;
import app.quorum.session.domain.DeckResult;
import app.quorum.session.domain.ScoredMovie;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class StubDeckBuilder implements DeckBuilder {

    private static final String STUB_CONFIG_VERSION = "stub-v0";

    private final JdbcClient jdbcClient;

    public StubDeckBuilder(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public DeckResult build(DeckRequest request) {
        List<Long> tmdbIds = jdbcClient
                .sql("SELECT tmdb_id FROM movies ORDER BY tmdb_id LIMIT :deckSize")
                .param("deckSize", request.deckSize())
                .query(Long.class)
                .list();

        List<ScoredMovie> movies = tmdbIds.stream()
                .map(id -> new ScoredMovie(id, BigDecimal.ZERO, List.of()))
                .toList();

        return new DeckResult(movies, STUB_CONFIG_VERSION);
    }
}
