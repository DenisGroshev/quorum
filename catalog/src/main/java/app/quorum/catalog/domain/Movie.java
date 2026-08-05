package app.quorum.catalog.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "movies")
@Getter
@Setter
@NoArgsConstructor
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
