package app.quorum.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity(name = "CatalogGenre")
@Table(name = "genres")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Genre {

    // Id приходит из TMDB (genre_id), не генерируется базой — поэтому нет @GeneratedValue.
    @Id
    private Integer id;

    @Column(name = "name_ru", nullable = false, length = 64)
    private String nameRu;

    @Column(name = "name_en", nullable = false, length = 64)
    private String nameEn;

    public static Genre of(int id, String nameRu, String nameEn) {
        Genre genre = new Genre();
        genre.id = id;
        genre.nameRu = nameRu;
        genre.nameEn = nameEn;
        return genre;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Genre other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Genre.class.hashCode();
    }
}
