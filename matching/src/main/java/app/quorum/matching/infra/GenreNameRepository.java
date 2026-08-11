package app.quorum.matching.infra;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GenreNameRepository {

    private final JdbcClient jdbcClient;

    public GenreNameRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Map<Integer, String> namesRu(Set<Integer> genreIds) {
        if (genreIds.isEmpty()) {
            return Map.of();
        }

        return jdbcClient
                .sql("SELECT id, name_ru FROM genres WHERE id IN (:ids)")
                .param("ids", genreIds)
                .query((rs, rowNum) -> Map.entry(rs.getInt("id"), rs.getString("name_ru")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
