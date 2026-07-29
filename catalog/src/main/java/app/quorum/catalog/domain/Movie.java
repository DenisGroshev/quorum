package app.quorum.catalog.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "movies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Movie {

    @Id
    @Column(name = "tmdb_id")
    private Long tmdbId;

    @Column(name = "title_ru", length = 512)
    private String titleRu;

    @Column(name = "title_en", length = 512)
    private String titleEn;

    @Column(name = "original_title", nullable = false, length = 512)
    private String originalTitle;

    @Column(name = "release_year")
    private Short releaseYear;

    @Column(name = "runtime_min")
    private Short runtimeMin;

    @Column(name = "poster_path", length = 256)
    private String posterPath;

    @Column(name = "vote_avg", nullable = false)
    private BigDecimal voteAvg;

    @Column(name = "vote_count", nullable = false)
    private int voteCount;

    @Column(name = "original_lang", length = 8)
    private String originalLang;

    @Column(name = "overview_ru", columnDefinition = "text")
    private String overviewRu;

    @Column(name = "overview_en", columnDefinition = "text")
    private String overviewEn;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "movie_genres", joinColumns = @JoinColumn(name = "tmdb_id"))
    private Set<MovieGenreRef> genres = new HashSet<>();

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Movie other)) return false;
        return tmdbId != null && tmdbId.equals(other.tmdbId);
    }

    @Override
    public int hashCode() {
        return Movie.class.hashCode();
    }
}

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
class MovieGenreRef {

    @Column(name = "genre_id", nullable = false)
    private int genreId;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        MovieGenreRef that = (MovieGenreRef) o;
        return genreId == that.genreId && primary == that.primary;
    }

    @Override
    public int hashCode() {
        return Objects.hash(genreId, primary);
    }
}
