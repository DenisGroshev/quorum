package app.quorum.catalog.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meta")
public class MetaController {

    private static final String ATTRIBUTION_TEXT =
            "This product uses the TMDB API but is not endorsed or certified by TMDB.";
    private static final String TMDB_URL = "https://www.themoviedb.org";

    @GetMapping("/attribution")
    public AttributionResponse attribution() {
        return new AttributionResponse(ATTRIBUTION_TEXT, TMDB_URL);
    }

    public record AttributionResponse(String text, String tmdbUrl) {}
}
