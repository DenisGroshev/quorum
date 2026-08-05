package app.quorum.catalog.config;

import app.quorum.catalog.domain.Genre;
import app.quorum.catalog.infra.CatalogGenreRepository;
import app.quorum.catalog.infra.tmdb.TmdbClient;
import app.quorum.catalog.infra.tmdb.dto.TmdbGenre;
import app.quorum.common.ContentLang;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Наполняет справочник жанров при старте (шаг 11). Пытается сходить в TMDB на
 * обоих языках; если TMDB недоступен — предупреждение в лог и продолжаем
 * запуск со старым справочником (пустым или ранее заполненным), а не падаем.
 * Иначе временный сбой TMDB в момент деплоя не давал бы приложению подняться.
 */
@Component
public class GenreSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GenreSyncRunner.class);

    private final TmdbClient tmdbClient;
    private final CatalogGenreRepository genreRepository;

    public GenreSyncRunner(TmdbClient tmdbClient, CatalogGenreRepository genreRepository) {
        this.tmdbClient = tmdbClient;
        this.genreRepository = genreRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<TmdbGenre> ru = tmdbClient.getGenres(ContentLang.RU).genres();
            List<TmdbGenre> en = tmdbClient.getGenres(ContentLang.EN).genres();
            genreRepository.saveAll(merge(ru, en));
        } catch (RestClientException e) {
            log.warn("TMDB unavailable during genre sync at startup, keeping the existing genre catalog", e);
        }
    }

    // TMDB отдаёт стабильные id жанров одинаково на обоих языках — save() по
    // существующему id это UPDATE, а не INSERT, так что повторный запуск не
    // плодит дубликаты.
    private static List<Genre> merge(List<TmdbGenre> ru, List<TmdbGenre> en) {
        Map<Integer, String> englishNames = en.stream().collect(Collectors.toMap(TmdbGenre::id, TmdbGenre::name));
        return ru.stream()
                .map(genre -> Genre.of(genre.id(), genre.name(), englishNames.getOrDefault(genre.id(), genre.name())))
                .toList();
    }
}
