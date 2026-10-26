package app.quorum.catalog.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MetaControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MetaController()).build();
    }

    @Test
    void attribution_returnsTextTmdbUrlAndLogoUrl() throws Exception {
        mockMvc.perform(get("/api/v1/meta/attribution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text")
                        .value("This product uses the TMDB API but is not endorsed or certified by TMDB."))
                .andExpect(jsonPath("$.tmdbUrl").value("https://www.themoviedb.org"))
                .andExpect(jsonPath("$.logoUrl").value("https://www.themoviedb.org/about/logos-attribution"));
    }
}
