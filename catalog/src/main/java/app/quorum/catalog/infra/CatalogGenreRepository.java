package app.quorum.catalog.infra;

import app.quorum.catalog.domain.Genre;
import org.springframework.data.jpa.repository.JpaRepository;

// Имя намеренно не GenreRepository: в модуле user уже есть JpaRepository с
// таким же простым именем (app.quorum.user.repository.GenreRepository) для
// своей сущности Genre. Spring Data выводит имя бина из простого имени
// интерфейса, а не из полного — при совпадении оба бина получают одно и то
// же имя "genreRepository", и общий контекст :app не поднимается
// (BeanDefinitionOverrideException).
public interface CatalogGenreRepository extends JpaRepository<Genre, Integer> {}
