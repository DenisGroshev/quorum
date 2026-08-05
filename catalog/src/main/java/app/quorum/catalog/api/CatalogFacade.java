package app.quorum.catalog.api;

import app.quorum.common.ContentLang;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface CatalogFacade {
    MovieDto getOrFetch(long tmdbId, ContentLang lang);

    List<MovieDto> popularByGenres(Set<Integer> genreIds, int limit, ContentLang lang);

    Map<Long, MovieDto> getCached(Collection<Long> tmdbIds, ContentLang lang);
}
