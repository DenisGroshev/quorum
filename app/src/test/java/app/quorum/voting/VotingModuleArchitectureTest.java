package app.quorum.voting;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * voting is allowed to depend on session (spec sec 6.4), but only through the public contract in
 * session.api — SessionGuard for checks and transitions. Reaching into session.domain or
 * session.infra directly (as the first finishVoting draft did with SessionRepository) bypasses
 * that contract and the invariants it protects.
 */
class VotingModuleArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("app.quorum");
    }

    @Test
    void votingModule_onlyDependsOnSessionApi() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("app.quorum.voting..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("app.quorum.session.domain..", "app.quorum.session.infra..")
                .because("voting must reach session only through session.api (SessionGuard, VotingContext, "
                        + "VotingFinishedEvent), never session.domain/session.infra directly");

        rule.check(classes);
    }
}
