package app.quorum.catalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Правило G-S0-ARCH-3: соседние модули (watchlist, matching, session, ...) и
 * веб-слой :app видят только {@code app.quorum.catalog.api} (интерфейс
 * {@code CatalogFacade} и {@code MovieDto}). Сущность {@code Movie} и всё
 * остальное во {@code domain}/{@code infra} — деталь реализации модуля
 * catalog и не должно утекать наружу ни прямым импортом, ни транзитивно.
 */
class CatalogModuleArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().importPackages("app.quorum");
    }

    @Test
    void catalogInternals_areNeverUsedOutsideTheCatalogModule() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("app.quorum.catalog..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("app.quorum.catalog.domain..", "app.quorum.catalog.infra..")
                .because("только app.quorum.catalog.api — публичная граница модуля catalog для соседей");

        rule.check(classes);
    }

    @Test
    void movieEntity_isNeverReferencedOutsideCatalog() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("app.quorum.catalog..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("app.quorum.catalog.domain.Movie")
                .because("наружу должен ходить только MovieDto, а не сущность Movie");

        rule.check(classes);
    }
}
