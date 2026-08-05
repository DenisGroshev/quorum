package app.quorum.catalog.web;

import app.quorum.catalog.api.MovieDto;
import app.quorum.catalog.config.TmdbProperties;
import app.quorum.catalog.domain.Genre;
import app.quorum.catalog.domain.ImageUrlBuilder;
import app.quorum.catalog.infra.CatalogGenreRepository;
import app.quorum.catalog.infra.tmdb.TmdbMovieMapper;
import app.quorum.catalog.infra.tmdb.dto.TmdbMovieShort;
import app.quorum.catalog.infra.tmdb.dto.TmdbSearchResponse;
import app.quorum.catalog.service.CatalogService;
import app.quorum.catalog.web.dto.GenreResponse;
import app.quorum.catalog.web.dto.MovieDetailsResponse;
import app.quorum.catalog.web.dto.MovieSearchItemResponse;
import app.quorum.common.ContentLang;
import app.quorum.common.web.RequestAttributes;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogService catalogService;
    private final CatalogGenreRepository genreRepository;
    private final ImageUrlBuilder imageUrlBuilder;
    private final TmdbProperties properties;

    public CatalogController(
            CatalogService catalogService,
            CatalogGenreRepository genreRepository,
            ImageUrlBuilder imageUrlBuilder,
            TmdbProperties properties) {
        this.catalogService = catalogService;
        this.genreRepository = genreRepository;
        this.imageUrlBuilder = imageUrlBuilder;
        this.properties = properties;
    }

    @GetMapping("/movies/search")
    public List<MovieSearchItemResponse> searchMovies(
            @RequestParam String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestAttribute(RequestAttributes.CONTENT_LANG_ATTRIBUTE) ContentLang lang) {
        validateQuery(query);
        validatePage(page);
        TmdbSearchResponse result = catalogService.search(query, page, lang);
        return toSearchItems(result.results(), lang);
    }

    @GetMapping("/movies/trending")
    public List<MovieSearchItemResponse> trendingMovies(
            @RequestParam(defaultValue = "1") int page,
            @RequestAttribute(RequestAttributes.CONTENT_LANG_ATTRIBUTE) ContentLang lang) {
        validatePage(page);
        TmdbSearchResponse result = catalogService.trending(lang, page);
        return toSearchItems(result.results(), lang);
    }

    @GetMapping("/movies/{tmdbId}")
    public MovieDetailsResponse getMovie(
            @PathVariable long tmdbId, @RequestAttribute(RequestAttributes.CONTENT_LANG_ATTRIBUTE) ContentLang lang) {
        MovieDto movie = catalogService.getOrFetch(tmdbId, lang);
        return new MovieDetailsResponse(
                movie.tmdbId(),
                movie.title(),
                movie.originalTitle(),
                movie.releaseYear(),
                movie.runtimeMin(),
                movie.posterUrl(),
                genreNames(movie.genreIds(), lang),
                movie.voteAverage(),
                movie.overview(),
                false,
                false);
    }

    @GetMapping("/genres")
    public List<GenreResponse> genres(@RequestAttribute(RequestAttributes.CONTENT_LANG_ATTRIBUTE) ContentLang lang) {
        return genreRepository.findAll().stream()
                .map(genre -> new GenreResponse(genre.getId(), localizedName(genre, lang)))
                .toList();
    }

    private List<MovieSearchItemResponse> toSearchItems(List<TmdbMovieShort> items, ContentLang lang) {
        if (items.isEmpty()) {
            return List.of();
        }
        Set<Integer> allGenreIds =
                items.stream().flatMap(item -> item.genreIds().stream()).collect(Collectors.toSet());
        Map<Integer, String> names = genreNameLookup(allGenreIds, lang);
        return items.stream()
                .map(item -> new MovieSearchItemResponse(
                        item.id(),
                        item.title(),
                        yearOf(item.releaseDate()),
                        imageUrlBuilder.posterUrl(item.posterPath(), properties.posterSizeList()),
                        item.genreIds().stream()
                                .map(names::get)
                                .filter(Objects::nonNull)
                                .toList(),
                        item.voteAverage(),
                        false,
                        false))
                .toList();
    }

    private List<String> genreNames(Set<Integer> genreIds, ContentLang lang) {
        Map<Integer, String> names = genreNameLookup(genreIds, lang);
        return genreIds.stream().map(names::get).filter(Objects::nonNull).toList();
    }

    private Map<Integer, String> genreNameLookup(Collection<Integer> ids, ContentLang lang) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return genreRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Genre::getId, genre -> localizedName(genre, lang)));
    }

    private static String localizedName(Genre genre, ContentLang lang) {
        return lang == ContentLang.RU ? genre.getNameRu() : genre.getNameEn();
    }

    private static Integer yearOf(String releaseDate) {
        Short year = TmdbMovieMapper.parseYear(releaseDate);
        return year == null ? null : year.intValue();
    }

    private static void validateQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query must not be blank");
        }
        if (query.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query must be at most 100 characters long");
        }
    }

    private static void validatePage(int page) {
        if (page < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 1");
        }
    }
}
