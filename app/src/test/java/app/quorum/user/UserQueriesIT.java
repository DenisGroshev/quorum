package app.quorum.user;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.user.api.UserBrief;
import app.quorum.user.api.UserQueries;
import app.quorum.user.domain.Genre;
import app.quorum.user.domain.User;
import app.quorum.user.repository.GenreRepository;
import app.quorum.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserQueriesIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        registry.add("TG_BOT_TOKEN", () -> "unused-in-this-test");
        registry.add("JWT_SECRET", () -> "integration-test-jwt-secret-should-be-long-enough");
        registry.add("TG_APP_NAME", () -> "quorum-it-tests");
        registry.add("TMDB_TOKEN", () -> "unused-in-this-test");
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private UserQueries userQueries;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void briefsAndFavGenres_for50Users_executeFixedNumberOfQueriesRegardlessOfCount() {
        List<Genre> genres = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> Genre.of(i, "ru-" + i, "en-" + i))
                .collect(Collectors.toList());
        genreRepository.saveAll(genres);

        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            User user = User.registerFromTelegram(
                    new TelegramProfile((long) (10_000 + i), "User" + i, "user" + i, null, "ru"), Clock.systemUTC());
            user.replaceFavGenres(Set.of(1, 2, 3));
            userRepository.save(user);
            ids.add(user.getId());
        }

        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Map<UUID, UserBrief> briefs = userQueries.briefs(ids);
        Map<UUID, Set<Integer>> favGenres = userQueries.favGenres(ids);

        assertThat(briefs).hasSize(50);
        assertThat(favGenres).hasSize(50);
        favGenres.values().forEach(set -> assertThat(set).containsExactlyInAnyOrder(1, 2, 3));

        long queryCount = statistics.getPrepareStatementCount();
        assertThat(queryCount)
                .as("briefs()+favGenres() for 50 ids must not run one query per id (N+1)")
                .isLessThanOrEqualTo(2);
    }
}
