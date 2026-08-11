package app.quorum.matching;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.matching.config.MatchingProperties;
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
class MatchingPropertiesConfigBindingIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("TG_BOT_TOKEN", () -> "unused-in-this-test");
        registry.add("JWT_SECRET", () -> "integration-test-jwt-secret-should-be-long-enough");
        registry.add("TG_APP_NAME", () -> "quorum-it-tests");
        registry.add("TMDB_TOKEN", () -> "unused-in-this-test");
    }

    @Autowired
    private MatchingProperties properties;

    @Test
    void matchingProperties_areConfiguredFromApplicationYml() {
        assertThat(properties.version()).isEqualTo("w1");

        assertThat(properties.weights().overlap()).isEqualTo(45);
        assertThat(properties.weights().genreFit()).isEqualTo(20);
        assertThat(properties.weights().rating()).isEqualTo(15);
        assertThat(properties.weights().recency()).isEqualTo(10);
        assertThat(properties.weights().availability()).isEqualTo(10);

        assertThat(properties.penalties().seenShare()).isEqualTo(25);
        assertThat(properties.penalties().longRuntime()).isEqualTo(15);
        assertThat(properties.penalties().foreignLowRated()).isEqualTo(10);

        assertThat(properties.runtimePenaltyThresholdMin()).isEqualTo(150);
        assertThat(properties.recencyHorizonDays()).isEqualTo(180);
        assertThat(properties.minVoteCountForRating()).isEqualTo(100);
        assertThat(properties.defaultRatingWhenFewVotes()).isEqualTo(0.3);
        assertThat(properties.maxGenreShare()).isEqualTo(0.4);
        assertThat(properties.candidateMultiplier()).isEqualTo(3);
        assertThat(properties.availabilityStub()).isEqualTo(0.5);
    }
}
