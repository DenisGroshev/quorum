package app.quorum.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.quorum.catalog.domain.Genre;
import app.quorum.catalog.infra.CatalogGenreRepository;
import app.quorum.catalog.infra.tmdb.TmdbClient;
import app.quorum.catalog.infra.tmdb.dto.TmdbGenre;
import app.quorum.catalog.infra.tmdb.dto.TmdbGenreListResponse;
import app.quorum.common.ContentLang;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class GenreSyncRunnerTest {

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private CatalogGenreRepository genreRepository;

    private GenreSyncRunner runner() {
        return new GenreSyncRunner(tmdbClient, genreRepository);
    }

    @Test
    void run_fetchesBothLanguages_andSavesMergedGenres() {
        when(tmdbClient.getGenres(ContentLang.RU))
                .thenReturn(
                        new TmdbGenreListResponse(List.of(new TmdbGenre(28, "Боевик"), new TmdbGenre(35, "Комедия"))));
        when(tmdbClient.getGenres(ContentLang.EN))
                .thenReturn(
                        new TmdbGenreListResponse(List.of(new TmdbGenre(28, "Action"), new TmdbGenre(35, "Comedy"))));

        runner().run(null);

        ArgumentCaptor<List<Genre>> captor = ArgumentCaptor.forClass(List.class);
        verify(genreRepository).saveAll(captor.capture());
        List<Genre> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        Genre action = saved.stream().filter(g -> g.getId() == 28).findFirst().orElseThrow();
        assertThat(action.getNameRu()).isEqualTo("Боевик");
        assertThat(action.getNameEn()).isEqualTo("Action");
    }

    @Test
    void run_tmdbUnavailable_doesNotThrow_appStartupContinues() {
        when(tmdbClient.getGenres(any())).thenThrow(new RestClientException("TMDB is down"));

        assertThatCode(() -> runner().run(null)).doesNotThrowAnyException();

        verifyNoInteractions(genreRepository);
    }
}
