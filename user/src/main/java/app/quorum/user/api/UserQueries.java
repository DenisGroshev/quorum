package app.quorum.user.api;

import app.quorum.common.ContentLang;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface UserQueries {
    Map<UUID, UserBrief> briefs(Collection<UUID> ids);

    Map<UUID, Set<Integer>> favGenres(Collection<UUID> ids);

    Map<UUID, ContentLang> contentLangs(Collection<UUID> ids);
}
