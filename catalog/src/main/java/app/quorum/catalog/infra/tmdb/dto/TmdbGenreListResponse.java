package app.quorum.catalog.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbGenreListResponse(List<TmdbGenre> genres) {}
