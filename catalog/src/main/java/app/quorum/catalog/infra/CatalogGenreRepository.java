package app.quorum.catalog.infra;

import app.quorum.catalog.domain.Genre;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogGenreRepository extends JpaRepository<Genre, Integer> {}
