package app.quorum.catalog.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.quorum.catalog.api.MovieDto;
import app.quorum.catalog.config.TmdbProperties;
import app.quorum.catalog.domain.Genre;
import app.quorum.catalog.domain.ImageUrlBuilder;
import app.quorum.catalog.infra.CatalogGenreRepository;
import app.quorum.catalog.infra.tmdb.dto.TmdbMovieShort;
import app.quorum.catalog.infra.tmdb.dto.TmdbSearchResponse;
import app.quorum.catalog.service.CatalogService;
import app.quorum.common.ContentLang;
import app.quorum.common.web.RequestAttributes;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CatalogControllerTest {

    private static final TmdbProperties PROPERTIES = new TmdbProperties(
            "https://api.themoviedb.org/3",
            "test-token",
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            25,
            Duration.ofDays(30),
            Duration.ofMinutes(15),
            10_000,
            "w185",
            "w500");

    @Mock
    private CatalogService catalogService;

    @Mock
    private CatalogGenreRepository genreRepository;

    @Mock
    private ImageUrlBuilder imageUrlBuilder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CatalogController controller =
                new CatalogController(catalogService, genreRepository, imageUrlBuilder, PROPERTIES);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static RequestPostProcessor lang(ContentLang lang) {
        return request -> {
            request.setAttribute(RequestAttributes.CONTENT_LANG_ATTRIBUTE, lang);
            return request;
        };
    }

    @Test
    void search_blankQuery_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/movies/search").param("query", "   ").with(lang(ContentLang.RU)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_queryLongerThan100Chars_returns400() throws Exception {
        String tooLong = "a".repeat(101);
        mockMvc.perform(get("/api/v1/movies/search").param("query", tooLong).with(lang(ContentLang.RU)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_pageLessThanOne_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/movies/search")
                        .param("query", "dune")
                        .param("page", "0")
                        .with(lang(ContentLang.RU)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_defaultsPageToOne_andReturnsMappedItems() throws Exception {
        TmdbMovieShort item = new TmdbMovieShort(
                603,
                "Матрица",
                "The Matrix",
                "overview",
                "/poster.jpg",
                "1999-03-31",
                List.of(28),
                "en",
                new BigDecimal("8.2"),
                25000);
        when(catalogService.search("dune", 1, ContentLang.RU))
                .thenReturn(new TmdbSearchResponse(1, List.of(item), 1, 1));
        when(genreRepository.findAllById(Set.of(28))).thenReturn(List.of(Genre.of(28, "Боевик", "Action")));
        when(imageUrlBuilder.posterUrl("/poster.jpg", "w185")).thenReturn("https://img/w185/poster.jpg");

        mockMvc.perform(get("/api/v1/movies/search").param("query", "dune").with(lang(ContentLang.RU)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tmdbId").value(603))
                .andExpect(jsonPath("$[0].title").value("Матрица"))
                .andExpect(jsonPath("$[0].year").value(1999))
                .andExpect(jsonPath("$[0].posterUrl").value("https://img/w185/poster.jpg"))
                .andExpect(jsonPath("$[0].genres[0]").value("Боевик"));
    }

    @Test
    void search_englishLanguageAttribute_resolvesEnglishGenreNames() throws Exception {
        TmdbMovieShort item = new TmdbMovieShort(
                603,
                "The Matrix",
                "The Matrix",
                "overview",
                "/poster.jpg",
                "1999-03-31",
                List.of(28),
                "en",
                new BigDecimal("8.2"),
                25000);
        when(catalogService.search("matrix", 1, ContentLang.EN))
                .thenReturn(new TmdbSearchResponse(1, List.of(item), 1, 1));
        when(genreRepository.findAllById(Set.of(28))).thenReturn(List.of(Genre.of(28, "Боевик", "Action")));

        mockMvc.perform(get("/api/v1/movies/search").param("query", "matrix").with(lang(ContentLang.EN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].genres[0]").value("Action"));
    }

    @Test
    void trending_pageLessThanOne_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/movies/trending").param("page", "0").with(lang(ContentLang.RU)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void trending_defaultsPageToOne() throws Exception {
        when(catalogService.trending(ContentLang.RU, 1)).thenReturn(new TmdbSearchResponse(1, List.of(), 1, 0));

        mockMvc.perform(get("/api/v1/movies/trending").with(lang(ContentLang.RU)))
                .andExpect(status().isOk());
    }

    @Test
    void getMovie_returnsDetailsWithResolvedGenreNames() throws Exception {
        MovieDto dto = new MovieDto(
                603L,
                "Матрица",
                "The Matrix",
                1999,
                136,
                "https://img/w500/poster.jpg",
                Set.of(28),
                28,
                new BigDecimal("8.2"),
                25000,
                "en",
                "overview ru");
        when(catalogService.getOrFetch(603L, ContentLang.RU)).thenReturn(dto);
        when(genreRepository.findAllById(Set.of(28))).thenReturn(List.of(Genre.of(28, "Боевик", "Action")));

        mockMvc.perform(get("/api/v1/movies/603").with(lang(ContentLang.RU)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tmdbId").value(603))
                .andExpect(jsonPath("$.originalTitle").value("The Matrix"))
                .andExpect(jsonPath("$.runtimeMin").value(136))
                .andExpect(jsonPath("$.genres[0]").value("Боевик"));
    }

    @Test
    void genres_returnsFullCatalogLocalized() throws Exception {
        when(genreRepository.findAll())
                .thenReturn(List.of(Genre.of(28, "Боевик", "Action"), Genre.of(35, "Комедия", "Comedy")));

        mockMvc.perform(get("/api/v1/genres").with(lang(ContentLang.RU)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(28))
                .andExpect(jsonPath("$[0].name").value("Боевик"));
    }
}
