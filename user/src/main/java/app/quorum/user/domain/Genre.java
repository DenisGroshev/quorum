package app.quorum.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "genres")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Genre {

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
}
