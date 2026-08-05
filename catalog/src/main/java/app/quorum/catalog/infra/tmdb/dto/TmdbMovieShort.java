package app.quorum.catalog.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMovieShort(
        long id,
        String title,
        @JsonProperty("original_title") String originalTitle,
        String overview,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("release_date") String releaseDate,
        @JsonProperty("genre_ids") List<Integer> genreIds,
        @JsonProperty("original_language") String originalLanguage,
        @JsonProperty("vote_average") BigDecimal voteAverage,
        @JsonProperty("vote_count") int voteCount) {}
