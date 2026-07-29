package app.quorum.user.api;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface UserQueries {
    Map<UUID, UserBrief> briefs(Collection<UUID> ids);

    Map<UUID, Set<Integer>> favGenres(Collection<UUID> ids);
}
