package app.quorum.user.service;

import app.quorum.user.api.UserBrief;
import app.quorum.user.api.UserQueries;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class UserQueriesImpl implements UserQueries {

    private final UserRepository userRepository;

    public UserQueriesImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Map<UUID, UserBrief> briefs(Collection<UUID> ids) {
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(
                        User::getId, u -> new UserBrief(u.getId(), u.getDisplayName(), u.getAvatarUrl())));
    }

    @Override
    public Map<UUID, Set<Integer>> favGenres(Collection<UUID> ids) {
        Map<UUID, Set<Integer>> result = new HashMap<>();
        for (UUID id : ids) {
            result.put(id, new HashSet<>());
        }

        for (Object[] row : userRepository.findFavoriteGenrePairs(ids)) {
            UUID userId = (UUID) row[0];
            Integer genreId = (Integer) row[1];
            result.get(userId).add(genreId);
        }

        return result;
    }
}
