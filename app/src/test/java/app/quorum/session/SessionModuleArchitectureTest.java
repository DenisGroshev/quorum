package app.quorum.session;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * session must not depend on watchlist (see dependency graph, spec sec 6.4): the "movie leaves
 * everyone's watchlist" effect is reached by publishing SessionWatchedEvent, which watchlist
 * listens for — not by session calling into watchlist directly.
 */
class SessionModuleArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().importPackages("app.quorum");
    }

    @Test
    void sessionModule_neverDependsOnWatchlist() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("app.quorum.session..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("app.quorum.watchlist..")
                .because("session publishes SessionWatchedEvent; watchlist reacts via a listener, not a direct call");

        rule.check(classes);
    }

    @Test
    void sessionModule_neverDependsOnVoting() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("app.quorum.session..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("app.quorum.voting..")
                .because("voting depends on session (allowed), not the other way around; the scheduler that watches "
                        + "voting deadlines lives in voting.domain.VotingDeadlineJob and reads overdue sessions "
                        + "through SessionGuard, session never reaches into voting directly");

        rule.check(classes);
    }
}
