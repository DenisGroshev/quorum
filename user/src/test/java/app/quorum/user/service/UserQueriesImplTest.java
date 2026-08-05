package app.quorum.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.user.api.UserBrief;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserQueriesImplTest {

    @Mock
    private UserRepository userRepository;

    private UserQueriesImpl queries;

    @BeforeEach
    void setUp() {
        queries = new UserQueriesImpl(userRepository);
    }

    @Test
    void briefs_returnsOneEntryPerFoundUser() {
        User a = User.registerFromTelegram(new TelegramProfile(1L, "Alice", "alice", "a.jpg", "ru"), Clock.systemUTC());
        User b = User.registerFromTelegram(new TelegramProfile(2L, "Bob", "bob", "b.jpg", "en"), Clock.systemUTC());
        when(userRepository.findAllById(List.of(a.getId(), b.getId()))).thenReturn(List.of(a, b));

        Map<UUID, UserBrief> result = queries.briefs(List.of(a.getId(), b.getId()));

        assertThat(result).hasSize(2);
        assertThat(result.get(a.getId())).isEqualTo(new UserBrief(a.getId(), "Alice", "a.jpg"));
        assertThat(result.get(b.getId())).isEqualTo(new UserBrief(b.getId(), "Bob", "b.jpg"));
    }

    @Test
    void favGenres_groupsGenreIdsByUserAndIncludesUsersWithNoGenres() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        when(userRepository.findFavoriteGenrePairs(List.of(user1, user2)))
                .thenReturn(List.of(new Object[] {user1, 5}, new Object[] {user1, 7}, new Object[] {user2, 5}));

        Map<UUID, Set<Integer>> result = queries.favGenres(List.of(user1, user2));

        assertThat(result.get(user1)).containsExactlyInAnyOrder(5, 7);
        assertThat(result.get(user2)).containsExactlyInAnyOrder(5);
    }

    @Test
    void favGenres_userWithNoRowsStillGetsEmptySet() {
        UUID userWithNoGenres = UUID.randomUUID();
        when(userRepository.findFavoriteGenrePairs(List.of(userWithNoGenres))).thenReturn(List.of());

        Map<UUID, Set<Integer>> result = queries.favGenres(List.of(userWithNoGenres));

        assertThat(result).containsKey(userWithNoGenres);
        assertThat(result.get(userWithNoGenres)).isEmpty();
    }
}
