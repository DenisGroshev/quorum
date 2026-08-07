package app.quorum.watchlist.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.quorum.common.ContentLang;
import app.quorum.common.web.RequestAttributes;
import app.quorum.watchlist.api.MovieFlags;
import app.quorum.watchlist.domain.SeenItem;
import app.quorum.watchlist.domain.WatchlistItem;
import app.quorum.watchlist.domain.WatchlistService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WatchlistControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final long TMDB_ID = 603L;

    @Mock
    private WatchlistService watchlistService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WatchlistController controller = new WatchlistController(watchlistService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static RequestPostProcessor authenticatedAs(UUID userId) {
        return request -> {
            request.setAttribute(RequestAttributes.USER_ID_ATTRIBUTE, userId);
            return request;
        };
    }

    private static RequestPostProcessor lang(ContentLang lang) {
        return request -> {
            request.setAttribute(RequestAttributes.CONTENT_LANG_ATTRIBUTE, lang);
            return request;
        };
    }

    @Test
    void getFlags_postsRawIdArray_returnsServiceResultKeyedByTmdbId() throws Exception {
        when(watchlistService.getFlags(USER_ID, List.of(603L, 604L)))
                .thenReturn(Map.of(603L, new MovieFlags(true, false), 604L, new MovieFlags(false, true)));

        mockMvc.perform(post("/api/v1/movies/flags")
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[603, 604]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['603'].inWatchlist").value(true))
                .andExpect(jsonPath("$['603'].seen").value(false))
                .andExpect(jsonPath("$['604'].inWatchlist").value(false))
                .andExpect(jsonPath("$['604'].seen").value(true));
    }

    @Test
    void getFlags_emptyArray_returns200WithEmptyBody() throws Exception {
        when(watchlistService.getFlags(USER_ID, List.of())).thenReturn(Map.of());

        mockMvc.perform(post("/api/v1/movies/flags")
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getWatchlist_defaultPaging_returnsPageFromService() throws Exception {
        Page<WatchlistItem> page =
                new PageImpl<>(List.of(new WatchlistItem(USER_ID, TMDB_ID, CLOCK)), PageRequest.of(0, 20), 1);
        when(watchlistService.getWatchlist(USER_ID, 0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/v1/watchlist").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tmdbId").value(TMDB_ID))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void getWatchlist_negativePage_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/watchlist").param("page", "-1").with(authenticatedAs(USER_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getWatchlist_sizeTooLarge_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/watchlist").param("size", "101").with(authenticatedAs(USER_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addToWatchlist_callsServiceThenReturnsCurrentState() throws Exception {
        when(watchlistService.getFlag(USER_ID, TMDB_ID)).thenReturn(new MovieFlags(true, false));
        when(watchlistService.watchlistCount(USER_ID)).thenReturn(5L);
        when(watchlistService.seenCount(USER_ID)).thenReturn(2L);

        mockMvc.perform(put("/api/v1/watchlist/{tmdbId}", TMDB_ID)
                        .with(authenticatedAs(USER_ID))
                        .with(lang(ContentLang.RU)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inWatchlist").value(true))
                .andExpect(jsonPath("$.seen").value(false))
                .andExpect(jsonPath("$.watchlistCount").value(5))
                .andExpect(jsonPath("$.seenCount").value(2));

        verify(watchlistService).addToWatchlist(USER_ID, TMDB_ID, ContentLang.RU);
    }

    @Test
    void removeFromWatchlist_returns204NoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/watchlist/{tmdbId}", TMDB_ID).with(authenticatedAs(USER_ID)))
                .andExpect(status().isNoContent());

        verify(watchlistService).removeFromWatchlist(USER_ID, TMDB_ID);
    }

    @Test
    void getSeen_defaultPaging_returnsPageFromService() throws Exception {
        Page<SeenItem> page = new PageImpl<>(List.of(new SeenItem(USER_ID, TMDB_ID, CLOCK)), PageRequest.of(0, 20), 1);
        when(watchlistService.getSeenItems(USER_ID, 0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/v1/seen").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tmdbId").value(TMDB_ID))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void markSeen_callsServiceThenReturnsCurrentState() throws Exception {
        when(watchlistService.getFlag(USER_ID, TMDB_ID)).thenReturn(new MovieFlags(false, true));
        when(watchlistService.watchlistCount(USER_ID)).thenReturn(4L);
        when(watchlistService.seenCount(USER_ID)).thenReturn(3L);

        mockMvc.perform(put("/api/v1/seen/{tmdbId}", TMDB_ID)
                        .with(authenticatedAs(USER_ID))
                        .with(lang(ContentLang.EN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inWatchlist").value(false))
                .andExpect(jsonPath("$.seen").value(true));

        verify(watchlistService).markSeen(eq(USER_ID), eq(TMDB_ID), eq(ContentLang.EN));
    }

    @Test
    void unmarkSeen_returns204NoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/seen/{tmdbId}", TMDB_ID).with(authenticatedAs(USER_ID)))
                .andExpect(status().isNoContent());

        verify(watchlistService).unmarkSeen(USER_ID, TMDB_ID);
    }
}
