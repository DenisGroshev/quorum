package app.quorum.catalog.infra.tmdb;

import app.quorum.catalog.domain.Movie;
import app.quorum.catalog.domain.MovieGenreRef;
import app.quorum.catalog.infra.tmdb.dto.TmdbGenre;
import app.quorum.catalog.infra.tmdb.dto.TmdbMovieFull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

/**
 * Переводит ответы TMDB (шаг 2) в сущность {@link Movie} (шаг 0).
 *
 * <p>TMDB отдаёт один язык за запрос, а {@link Movie} хранит оба языка сразу —
 * поэтому оба метода принимают полную карточку фильма на русском И на
 * английском (тот самый вызывающий код должен получить обе заранее, один раз,
 * при первом сохранении фильма). Общие для языков поля (id, постер, рейтинг,
 * жанры, год, хронометраж) берутся из русской карточки — обе карточки
 * описывают один и тот же фильм, эти поля от языка не зависят.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TmdbMovieMapper {

    @Mapping(target = "tmdbId", source = "ru.id")
    @Mapping(target = "originalTitle", source = "ru.originalTitle")
    @Mapping(target = "titleRu", source = "ru.title")
    @Mapping(target = "titleEn", source = "en.title")
    @Mapping(target = "overviewRu", source = "ru.overview")
    @Mapping(target = "overviewEn", source = "en.overview")
    @Mapping(target = "posterPath", source = "ru.posterPath")
    @Mapping(target = "voteAvg", source = "ru.voteAverage")
    @Mapping(target = "voteCount", source = "ru.voteCount")
    @Mapping(target = "originalLang", source = "ru.originalLanguage")
    @Mapping(target = "releaseYear", source = "ru.releaseDate", qualifiedByName = "parseYear")
    @Mapping(target = "runtimeMin", source = "ru.runtime", qualifiedByName = "toShort")
    @Mapping(target = "genres", expression = "java(TmdbMovieMapper.toGenreRefs(ru.genres()))")
    @Mapping(target = "syncedAt", source = "syncedAt")
    Movie toMovie(TmdbMovieFull ru, TmdbMovieFull en, Instant syncedAt);

    @Mapping(target = "tmdbId", ignore = true)
    @Mapping(target = "originalTitle", source = "ru.originalTitle")
    @Mapping(target = "titleRu", source = "ru.title")
    @Mapping(target = "titleEn", source = "en.title")
    @Mapping(target = "overviewRu", source = "ru.overview")
    @Mapping(target = "overviewEn", source = "en.overview")
    @Mapping(target = "posterPath", source = "ru.posterPath")
    @Mapping(target = "voteAvg", source = "ru.voteAverage")
    @Mapping(target = "voteCount", source = "ru.voteCount")
    @Mapping(target = "originalLang", source = "ru.originalLanguage")
    @Mapping(target = "releaseYear", source = "ru.releaseDate", qualifiedByName = "parseYear")
    @Mapping(target = "runtimeMin", source = "ru.runtime", qualifiedByName = "toShort")
    @Mapping(target = "genres", expression = "java(TmdbMovieMapper.toGenreRefs(ru.genres()))")
    @Mapping(target = "syncedAt", source = "syncedAt")
    void updateMovie(@MappingTarget Movie movie, TmdbMovieFull ru, TmdbMovieFull en, Instant syncedAt);

    @Named("parseYear")
    static Short parseYear(String releaseDate) {
        if (releaseDate == null || releaseDate.isBlank()) {
            return null;
        }
        return (short) LocalDate.parse(releaseDate).getYear();
    }

    @Named("toShort")
    static Short toShort(Integer value) {
        return value == null ? null : value.shortValue();
    }

    // Основной жанр — первый в списке TMDB (так делает и сам TMDB).
    static Set<MovieGenreRef> toGenreRefs(List<TmdbGenre> genres) {
        Set<MovieGenreRef> refs = new LinkedHashSet<>();
        if (genres == null) {
            return refs;
        }
        for (int i = 0; i < genres.size(); i++) {
            refs.add(new MovieGenreRef(genres.get(i).id(), i == 0));
        }
        return refs;
    }
}
