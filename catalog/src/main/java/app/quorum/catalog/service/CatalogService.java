package app.quorum.catalog.service;

import app.quorum.catalog.api.CatalogFacade;
import app.quorum.catalog.api.MovieDto;
import app.quorum.catalog.config.CatalogCacheConfig;
import app.quorum.catalog.config.TmdbProperties;
import app.quorum.catalog.domain.ImageUrlBuilder;
import app.quorum.catalog.domain.Movie;
import app.quorum.catalog.domain.MovieGenreRef;
import app.quorum.catalog.infra.MovieRepository;
import app.quorum.catalog.infra.tmdb.TmdbClient;
import app.quorum.catalog.infra.tmdb.TmdbMovieMapper;
import app.quorum.catalog.infra.tmdb.dto.TmdbMovieFull;
import app.quorum.catalog.infra.tmdb.dto.TmdbSearchResponse;
import app.quorum.common.ContentLang;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
public class CatalogService implements CatalogFacade {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final TmdbClient tmdbClient;
    private final MovieRepository movieRepository;
    private final TmdbMovieMapper mapper;
    private final TmdbProperties properties;
    private final ImageUrlBuilder imageUrlBuilder;
    private final Clock clock;

    public CatalogService(
            TmdbClient tmdbClient,
            MovieRepository movieRepository,
            TmdbMovieMapper mapper,
            TmdbProperties properties,
            ImageUrlBuilder imageUrlBuilder,
            Clock clock) {
        this.tmdbClient = tmdbClient;
        this.movieRepository = movieRepository;
        this.mapper = mapper;
        this.properties = properties;
        this.imageUrlBuilder = imageUrlBuilder;
        this.clock = clock;
    }

    @Cacheable(
            cacheNames = CatalogCacheConfig.MOVIE_SEARCH_CACHE,
            key = "T(app.quorum.catalog.config.SearchCacheKey).of(#query, #page, #lang)")
    public TmdbSearchResponse search(String query, int page, ContentLang lang) {
        return tmdbClient.searchMovies(query, page, lang);
    }

    public TmdbSearchResponse trending(ContentLang lang, int page) {
        return tmdbClient.getTrending(lang, page);
    }

    @Override
    public MovieDto getOrFetch(long tmdbId, ContentLang lang) {
        Movie movie = movieRepository.findById(tmdbId).orElse(null);

        if (movie == null) {
            movie = fetchAndSave(tmdbId);
        } else if (isStale(movie)) {
            movie = refreshOrKeepStale(movie);
        }

        return toDto(movie, lang, properties.posterSizeDetails());
    }

    @Override
    public Map<Long, MovieDto> getCached(Collection<Long> tmdbIds, ContentLang lang) {
        return movieRepository.findAllMoviesByIds(tmdbIds).stream()
                .collect(Collectors.toMap(Movie::getTmdbId, m -> toDto(m, lang, properties.posterSizeList())));
    }

    @Override
    public List<MovieDto> popularByGenres(Set<Integer> genreIds, int limit, ContentLang lang) {
        Pageable page = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "voteAvg", "voteCount"));
        return movieRepository.findPopularByGenreIds(genreIds, page).stream()
                .map(m -> toDto(m, lang, properties.posterSizeList()))
                .toList();
    }

    private boolean isStale(Movie movie) {
        return movie.getSyncedAt().isBefore(clock.instant().minus(properties.resyncAfter()));
    }

    private Movie fetchAndSave(long tmdbId) {
        TmdbMovieFull ru = tmdbClient.getMovie(tmdbId, ContentLang.RU);
        TmdbMovieFull en = tmdbClient.getMovie(tmdbId, ContentLang.EN);
        return movieRepository.save(mapper.toMovie(ru, en, clock.instant()));
    }

    private Movie refreshOrKeepStale(Movie movie) {
        try {
            TmdbMovieFull ru = tmdbClient.getMovie(movie.getTmdbId(), ContentLang.RU);
            TmdbMovieFull en = tmdbClient.getMovie(movie.getTmdbId(), ContentLang.EN);
            mapper.updateMovie(movie, ru, en, clock.instant());
            return movieRepository.save(movie);
        } catch (RestClientException e) {
            log.warn("TMDB unavailable while refreshing movie {}, serving stale data", movie.getTmdbId(), e);
            return movie;
        }
    }

    private MovieDto toDto(Movie movie, ContentLang lang, String posterSize) {
        String title = lang == ContentLang.RU ? movie.getTitleRu() : movie.getTitleEn();
        String overview = lang == ContentLang.RU ? movie.getOverviewRu() : movie.getOverviewEn();
        Set<Integer> genreIds =
                movie.getGenres().stream().map(MovieGenreRef::getGenreId).collect(Collectors.toSet());
        Integer primaryGenreId = movie.getGenres().stream()
                .filter(MovieGenreRef::isPrimary)
                .map(MovieGenreRef::getGenreId)
                .findFirst()
                .orElse(null);
        return new MovieDto(
                movie.getTmdbId(),
                title,
                movie.getOriginalTitle(),
                movie.getReleaseYear() == null ? null : movie.getReleaseYear().intValue(),
                movie.getRuntimeMin() == null ? null : movie.getRuntimeMin().intValue(),
                imageUrlBuilder.posterUrl(movie.getPosterPath(), posterSize),
                genreIds,
                primaryGenreId,
                movie.getVoteAvg(),
                movie.getVoteCount(),
                movie.getOriginalLang(),
                overview);
    }
}
