package app.quorum.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MovieGenreRef {

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
