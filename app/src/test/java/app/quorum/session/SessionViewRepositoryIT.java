package app.quorum.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.common.ContentLang;
import app.quorum.common.exception.SessionNotFoundException;
import app.quorum.session.domain.Session;
import app.quorum.session.domain.SessionParams;
import app.quorum.session.infra.SessionRepository;
import app.quorum.session.infra.SessionViewRepository;
import app.quorum.session.web.dto.MemberView;
import app.quorum.session.web.dto.SessionViewResponse;
import app.quorum.user.api.UserBrief;
import app.quorum.user.api.UserQueries;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SessionViewRepositoryIT {

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
    private SessionViewRepository sessionViewRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataSource dataSource;

    private static User user(long tgUserId) {
        return User.registerFromTelegram(
                new TelegramProfile(tgUserId, "User " + tgUserId, "user" + tgUserId, null, "ru"), Clock.systemUTC());
    }

    private static User userWithProfile(long tgUserId, String displayName, String avatarUrl) {
        return User.registerFromTelegram(
                new TelegramProfile(tgUserId, displayName, "user" + tgUserId, avatarUrl, "ru"), Clock.systemUTC());
    }

    private static SessionParams params() {
        return new SessionParams("Movie night", 10, 30, false);
    }

    @Test
    void viewFor_returnsSessionWithMemberNamesAndAvatars() {
        User host = userRepository.save(userWithProfile(1001, "Alice Host", "https://cdn.example/alice.png"));
        User member = userRepository.save(userWithProfile(1002, "Bob Member", "https://cdn.example/bob.png"));
        Session session = Session.create(host.getId(), params(), "VIEW00001", Clock.systemUTC());
        session.join(member.getId(), Clock.systemUTC());
        sessionRepository.saveAndFlush(session);

        SessionViewResponse response = sessionViewRepository.viewFor(session.getId(), host.getId());

        assertThat(response.id()).isEqualTo(session.getId());
        assertThat(response.title()).isEqualTo("Movie night");
        assertThat(response.state()).isEqualTo("LOBBY");
        assertThat(response.deckSize()).isEqualTo(10);
        assertThat(response.votingDeadlineAt()).isNull();
        assertThat(response.winnerTmdbId()).isNull();
        assertThat(response.members())
                .extracting(
                        MemberView::userId,
                        MemberView::displayName,
                        MemberView::avatarUrl,
                        MemberView::isHost,
                        MemberView::hasFinishedVoting)
                .containsExactlyInAnyOrder(
                        tuple(host.getId(), "Alice Host", "https://cdn.example/alice.png", true, false),
                        tuple(member.getId(), "Bob Member", "https://cdn.example/bob.png", false, false));
    }

    @Test
    void viewFor_hostViewer_getsInviteUrlAndTopLevelHostFlag() {
        User host = userRepository.save(user(1010));
        Session session = Session.create(host.getId(), params(), "VIEW00002", Clock.systemUTC());
        sessionRepository.saveAndFlush(session);

        SessionViewResponse response = sessionViewRepository.viewFor(session.getId(), host.getId());

        assertThat(response.isHost()).isTrue();
        assertThat(response.inviteUrl()).startsWith("https://t.me/").contains(session.getInviteCode());
    }

    @Test
    void viewFor_nonHostViewer_hidesInviteUrlAndTopLevelHostFlag() {
        User host = userRepository.save(user(1020));
        User member = userRepository.save(user(1021));
        Session session = Session.create(host.getId(), params(), "VIEW00003", Clock.systemUTC());
        session.join(member.getId(), Clock.systemUTC());
        sessionRepository.saveAndFlush(session);

        SessionViewResponse response = sessionViewRepository.viewFor(session.getId(), member.getId());

        assertThat(response.isHost()).isFalse();
        assertThat(response.inviteUrl()).isNull();
    }

    @Test
    void viewFor_unknownSession_throwsSessionNotFound() {
        User someone = userRepository.save(user(1030));

        assertThatThrownBy(() -> sessionViewRepository.viewFor(UUID.randomUUID(), someone.getId()))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("session.not-found");
    }

    @Test
    void viewFor_viewerNotAMember_throwsSessionNotFound() {
        User host = userRepository.save(user(1040));
        User stranger = userRepository.save(user(1041));
        Session session = Session.create(host.getId(), params(), "VIEW00004", Clock.systemUTC());
        sessionRepository.saveAndFlush(session);

        assertThatThrownBy(() -> sessionViewRepository.viewFor(session.getId(), stranger.getId()))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("session.not-found");
    }

    @Test
    void viewFor_neverExceedsThreeQueries_regardlessOfMemberCount() {
        User host = userRepository.save(user(2000));
        Session soloSession = Session.create(host.getId(), params(), "CNT000001", Clock.systemUTC());
        sessionRepository.saveAndFlush(soloSession);

        Session crowdedSession = Session.create(host.getId(), params(), "CNT000002", Clock.systemUTC());
        for (int i = 0; i < 9; i++) {
            User member = userRepository.save(user(2001 + i));
            crowdedSession.join(member.getId(), Clock.systemUTC());
        }
        sessionRepository.saveAndFlush(crowdedSession);

        AtomicInteger soloQueries = new AtomicInteger();
        countingRepository(soloQueries).viewFor(soloSession.getId(), host.getId());

        AtomicInteger crowdedQueries = new AtomicInteger();
        countingRepository(crowdedQueries).viewFor(crowdedSession.getId(), host.getId());

        assertThat(soloQueries.get()).isLessThanOrEqualTo(3);
        assertThat(crowdedQueries.get()).isLessThanOrEqualTo(3);
        assertThat(crowdedQueries.get()).isEqualTo(soloQueries.get());
    }

    /**
     * Builds a SessionViewRepository wired to a query-counting DataSource, with a UserQueries
     * stand-in that goes through the same counted connection (rather than the real, JPA-backed
     * bean) so the count reflects exactly what viewFor() itself issues.
     */
    private SessionViewRepository countingRepository(AtomicInteger counter) {
        JdbcClient countingJdbcClient = JdbcClient.create(new CountingDataSource(dataSource, counter));
        UserQueries countingUserQueries = new UserQueries() {
            @Override
            public Map<UUID, UserBrief> briefs(Collection<UUID> ids) {
                return countingJdbcClient
                        .sql("SELECT id, display_name, avatar_url FROM users WHERE id IN (:ids)")
                        .param("ids", ids)
                        .query((rs, rowNum) -> new UserBrief(
                                rs.getObject("id", UUID.class),
                                rs.getString("display_name"),
                                rs.getString("avatar_url")))
                        .list()
                        .stream()
                        .collect(Collectors.toMap(UserBrief::id, b -> b));
            }

            @Override
            public Map<UUID, Set<Integer>> favGenres(Collection<UUID> ids) {
                throw new UnsupportedOperationException("not needed for this test");
            }

            @Override
            public Map<UUID, ContentLang> contentLangs(Collection<UUID> ids) {
                throw new UnsupportedOperationException("not needed for this test");
            }
        };
        return new SessionViewRepository(countingJdbcClient, countingUserQueries, "quorum_bot", "quorum-app");
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
