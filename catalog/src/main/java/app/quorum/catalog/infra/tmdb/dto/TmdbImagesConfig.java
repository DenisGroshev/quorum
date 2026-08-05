package app.quorum.catalog.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbImagesConfig(
        @JsonProperty("secure_base_url") String secureBaseUrl,
        @JsonProperty("poster_sizes") List<String> posterSizes) {}
