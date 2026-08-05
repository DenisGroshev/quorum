package app.quorum.catalog.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbSearchResponse(
        @JsonProperty("page") int page,
        @JsonProperty("results") List<TmdbMovieShort> results,
        @JsonProperty("total_pages") int totalPages,
        @JsonProperty("total_results") int totalResults) {}
