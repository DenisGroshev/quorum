package app.quorum.notification;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NotificationModuleArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("app.quorum");
    }

    @Test
    void notificationModule_onlyDependsOnSessionApi() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("app.quorum.notification..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("app.quorum.session.domain..", "app.quorum.session.infra..")
                .because("notification must reach session only through session.api (SessionGuard and its "
                        + "published events), never session.domain/session.infra directly");

        rule.check(classes);
    }
}
