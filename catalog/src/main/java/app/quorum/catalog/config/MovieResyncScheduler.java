package app.quorum.catalog.config;

import app.quorum.catalog.domain.Movie;
import app.quorum.catalog.infra.MovieRepository;
import app.quorum.catalog.service.CatalogService;
import app.quorum.common.ContentLang;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MovieResyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MovieResyncScheduler.class);
    static final int BATCH_SIZE = 100;

    private final MovieRepository movieRepository;
    private final CatalogService catalogService;
    private final TmdbProperties properties;
    private final Clock clock;

    public MovieResyncScheduler(
            MovieRepository movieRepository, CatalogService catalogService, TmdbProperties properties, Clock clock) {
        this.movieRepository = movieRepository;
        this.catalogService = catalogService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT24H")
    public void resyncStaleMovies() {
        Instant threshold = clock.instant().minus(properties.resyncAfter());
        List<Movie> batch = movieRepository.findMoviesSyncedBefore(threshold, PageRequest.of(0, BATCH_SIZE));
        for (Movie movie : batch) {
            // Ошибка одного фильма не должна прерывать пачку — ловим широко
            // (не только RestClientException, который getOrFetch уже гасит сам).
            try {
                catalogService.getOrFetch(movie.getTmdbId(), ContentLang.RU);
            } catch (Exception e) {
                log.warn("Failed to resync movie {}, continuing with the rest of the batch", movie.getTmdbId(), e);
            }
        }
    }
}
