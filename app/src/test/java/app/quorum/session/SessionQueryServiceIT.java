package app.quorum.session;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.common.ContentLang;
import app.quorum.common.SessionState;
import app.quorum.session.domain.DeckResult;
import app.quorum.session.domain.ScoredMovie;
import app.quorum.session.domain.Session;
import app.quorum.session.domain.SessionParams;
import app.quorum.session.domain.SessionQueryService;
import app.quorum.session.infra.SessionRepository;
import app.quorum.session.web.dto.SessionHistoryItem;
import app.quorum.session.web.dto.SessionSummary;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SessionQueryServiceIT {

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

    // A competing @Primary DataSource @Bean would make Boot's own DataSourceAutoConfiguration
    // skip creating the real one (its @ConditionalOnMissingBean sees ours first) - nothing left
    // to wrap, self-reference. Instead, post-process the already-created bean in place.
    @TestConfiguration
    static class CountingDataSourceConfig {
        static final AtomicInteger QUERY_COUNTER = new AtomicInteger();

        @Bean
        static BeanPostProcessor countingDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource && !(bean instanceof CountingDataSource)) {
                        return new CountingDataSource(dataSource, QUERY_COUNTER);
                    }
                    return bean;
                }
            };
        }
    }

    @Autowired
    private SessionQueryService sessionQueryService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private final AtomicInteger queryCounter = CountingDataSourceConfig.QUERY_COUNTER;

    private void insertMovie(long tmdbId, String titleRu) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO movies (tmdb_id, title_ru, original_title, vote_avg, vote_count, synced_at)
                        VALUES (:tmdbId, :titleRu, :title, :voteAvg, :voteCount, :syncedAt)
                        """)
                .param("tmdbId", tmdbId)
                .param("titleRu", titleRu)
                .param("title", "Movie " + tmdbId)
                .param("voteAvg", new BigDecimal("7.5"))
                .param("voteCount", 100)
                .param("syncedAt", Timestamp.from(Instant.now()))
                .update();
    }

    private static DeckResult deckFor(long tmdbId) {
        return new DeckResult(List.of(new ScoredMovie(tmdbId, new BigDecimal("9.0"), List.of())), "v1");
    }

    private static User user(long tgUserId) {
        return User.registerFromTelegram(
                new TelegramProfile(tgUserId, "User " + tgUserId, "user" + tgUserId, null, "ru"), Clock.systemUTC());
    }

    private static SessionParams params() {
        return new SessionParams("Movie night", 10, 30, false);
    }

    private Session watchedSession(UUID hostId, UUID memberId, String code, long winnerTmdbId, Instant createdAt) {
        Clock clock = Clock.fixed(createdAt, ZoneOffset.UTC);
        Session session = Session.create(hostId, params(), code, clock);
        session.join(memberId, clock);
        session.startVoting(hostId, deckFor(winnerTmdbId), "v1", clock);
        session.decide(winnerTmdbId, false, clock);
        session.markWatched(hostId, clock);
        return session;
    }

    @Test
    void listActive_returnsLobbyVotingDecidedForMember_newestFirst_excludingOthers() {
        User host = userRepository.save(user(2000));
        User member = userRepository.save(user(2001));
        User stranger = userRepository.save(user(2002));
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

        Session lobby = Session.create(host.getId(), params(), "ACT00001", Clock.fixed(t0, ZoneOffset.UTC));
        Session voting =
                Session.create(host.getId(), params(), "ACT00002", Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC));
        voting.join(member.getId(), Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC));
        voting.startVoting(host.getId(), deckFor(810_001), "v1", Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC));
        Session decided =
                Session.create(host.getId(), params(), "ACT00003", Clock.fixed(t0.plusSeconds(120), ZoneOffset.UTC));
        decided.join(member.getId(), Clock.fixed(t0.plusSeconds(120), ZoneOffset.UTC));
        decided.startVoting(host.getId(), deckFor(810_002), "v1", Clock.fixed(t0.plusSeconds(120), ZoneOffset.UTC));
        decided.decide(810_002L, false, Clock.fixed(t0.plusSeconds(120), ZoneOffset.UTC));
        Session cancelled =
                Session.create(host.getId(), params(), "ACT00004", Clock.fixed(t0.plusSeconds(180), ZoneOffset.UTC));
        cancelled.cancel(host.getId());
        Session othersSession = Session.create(stranger.getId(), params(), "ACT00005", Clock.fixed(t0, ZoneOffset.UTC));
        insertMovie(810_001, "Movie A");
        insertMovie(810_002, "Movie B");
        sessionRepository.saveAll(List.of(lobby, voting, decided, cancelled, othersSession));

        List<SessionSummary> active = sessionQueryService.listActive(host.getId());

        assertThat(active)
                .extracting(SessionSummary::id)
                .containsExactly(decided.getId(), voting.getId(), lobby.getId());
    }

    @Test
    void listHistory_cancelled_visibleOnlyToHost() {
        User host = userRepository.save(user(2010));
        User member = userRepository.save(user(2011));
        Session session = Session.create(host.getId(), params(), "HIS00001", Clock.systemUTC());
        session.join(member.getId(), Clock.systemUTC());
        session.cancel(host.getId());
        sessionRepository.saveAndFlush(session);

        List<SessionHistoryItem> hostHistory = sessionQueryService.listHistory(host.getId(), ContentLang.RU);
        List<SessionHistoryItem> memberHistory = sessionQueryService.listHistory(member.getId(), ContentLang.RU);

        assertThat(hostHistory).extracting(SessionHistoryItem::id).contains(session.getId());
        assertThat(memberHistory).extracting(SessionHistoryItem::id).doesNotContain(session.getId());
    }

    @Test
    void listHistory_watchedAndArchived_visibleToAnyMember_withWinnerTitleAndNullSafety() {
        User host = userRepository.save(user(2020));
        User member = userRepository.save(user(2021));
        insertMovie(810_010, "Победитель Вечера");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

        Session watched = watchedSession(host.getId(), member.getId(), "HIS00002", 810_010, t0);

        Session archived =
                Session.create(host.getId(), params(), "HIS00003", Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC));
        archived.join(member.getId(), Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC));
        archived.startVoting(host.getId(), deckFor(810_011), "v1", Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC));
        insertMovie(810_011, "Не собранный победитель");
        archived.decide(null, false, Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC));
        archived.archive();

        sessionRepository.saveAll(List.of(watched, archived));

        List<SessionHistoryItem> history = sessionQueryService.listHistory(member.getId(), ContentLang.RU);

        assertThat(history)
                .filteredOn(item -> item.id().equals(watched.getId()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.state()).isEqualTo(SessionState.WATCHED);
                    assertThat(item.winnerTmdbId()).isEqualTo(810_010L);
                    assertThat(item.winnerTitle()).isEqualTo("Победитель Вечера");
                });
        assertThat(history)
                .filteredOn(item -> item.id().equals(archived.getId()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.state()).isEqualTo(SessionState.ARCHIVED);
                    assertThat(item.winnerTmdbId()).isNull();
                    assertThat(item.winnerTitle()).isNull();
                });
    }

    @Test
    void listHistory_queryCountStaysFlat_regardlessOfSessionCount_forSameWinner() {
        User soloHost = userRepository.save(user(2030));
        User soloMember = userRepository.save(user(2031));
        User crowdedHost = userRepository.save(user(2032));
        User crowdedMember = userRepository.save(user(2033));
        insertMovie(810_020, "Общий победитель");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

        sessionRepository.saveAndFlush(watchedSession(soloHost.getId(), soloMember.getId(), "CNT00001", 810_020, t0));

        // "История из 20 сессий" from the spec's mandatory-test list.
        int crowdedSessionCount = 20;
        for (int i = 0; i < crowdedSessionCount; i++) {
            sessionRepository.saveAndFlush(watchedSession(
                    crowdedHost.getId(),
                    crowdedMember.getId(),
                    "CNTX" + String.format("%04d", i),
                    810_020,
                    t0.plusSeconds(i + 1)));
        }
        assertThat(sessionRepository.findHistoryFor(crowdedHost.getId(), List.of(SessionState.WATCHED)))
                .hasSize(crowdedSessionCount);

        queryCounter.set(0);
        sessionQueryService.listHistory(soloHost.getId(), ContentLang.RU);
        int soloQueries = queryCounter.get();

        queryCounter.set(0);
        sessionQueryService.listHistory(crowdedHost.getId(), ContentLang.RU);
        int crowdedQueries = queryCounter.get();

        assertThat(soloQueries).isEqualTo(crowdedQueries);
    }

    @Test
    void countWatched_countsOnlyWatchedSessions() {
        User host = userRepository.save(user(2040));
        User member = userRepository.save(user(2041));
        insertMovie(810_030, "Watched movie");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

        Session watched = watchedSession(host.getId(), member.getId(), "CW000001", 810_030, t0);
        Session lobby =
                Session.create(host.getId(), params(), "CW000002", Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC));
        Session cancelled =
                Session.create(host.getId(), params(), "CW000003", Clock.fixed(t0.plusSeconds(120), ZoneOffset.UTC));
        cancelled.cancel(host.getId());
        sessionRepository.saveAll(List.of(watched, lobby, cancelled));

        assertThat(sessionQueryService.countWatched(host.getId())).isEqualTo(1);
    }

    private static final class CountingDataSource extends DelegatingDataSource {
        private final AtomicInteger counter;

        CountingDataSource(DataSource delegate, AtomicInteger counter) {
            super(delegate);
            this.counter = counter;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return countingConnection(super.getConnection());
        }

        private Connection countingConnection(Connection target) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")) {
                            counter.incrementAndGet();
                        }
                        try {
                            return method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
