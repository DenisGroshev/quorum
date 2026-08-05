package app.quorum.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.quorum.catalog.infra.tmdb.TmdbClient;
import app.quorum.catalog.infra.tmdb.dto.TmdbConfiguration;
import app.quorum.catalog.infra.tmdb.dto.TmdbImagesConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImageUrlBuilderTest {

    @Mock
    private TmdbClient tmdbClient;

    @Test
    void posterUrl_nullPath_returnsNull_notStringLiteral() {
        ImageUrlBuilder builder = new ImageUrlBuilder(tmdbClient);

        assertThat(builder.posterUrl(null, "w185")).isNull();
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void posterUrl_blankPath_returnsNull() {
        ImageUrlBuilder builder = new ImageUrlBuilder(tmdbClient);

        assertThat(builder.posterUrl("", "w185")).isNull();
    }

    @Test
    void posterUrl_buildsFullHttpsAddress() {
        when(tmdbClient.getConfiguration())
                .thenReturn(new TmdbConfiguration(
                        new TmdbImagesConfig("https://image.tmdb.org/t/p/", List.of("w185", "w500"))));
        ImageUrlBuilder builder = new ImageUrlBuilder(tmdbClient);

        String url = builder.posterUrl("/abc123.jpg", "w185");

        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/w185/abc123.jpg");
        assertThat(url).startsWith("https://");
    }

    @Test
    void configuration_isFetchedOnce_andCachedAcrossCalls() {
        when(tmdbClient.getConfiguration())
                .thenReturn(
                        new TmdbConfiguration(new TmdbImagesConfig("https://image.tmdb.org/t/p/", List.of("w185"))));
        ImageUrlBuilder builder = new ImageUrlBuilder(tmdbClient);

        builder.posterUrl("/a.jpg", "w185");
        builder.posterUrl("/b.jpg", "w185");
        builder.posterUrl("/c.jpg", "w500");

        verify(tmdbClient, times(1)).getConfiguration();
    }
}
