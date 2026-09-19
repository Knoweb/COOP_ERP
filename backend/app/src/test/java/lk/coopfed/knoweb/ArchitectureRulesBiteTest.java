package lk.coopfed.knoweb;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 17A §14, S0-05 done-when: "a deliberate violation fails the build". The rules of
 * {@link ArchitectureTests} pass vacuously on empty module packages, so this test
 * runs each rule against the deliberately wrong classes in
 * {@code lk.coopfed.archfixtures} and asserts that it reports them. The fixtures
 * live outside the application package on purpose: Modulith must not see them.
 */
class ArchitectureRulesBiteTest {

    private static final JavaClasses FIXTURES = new ClassFileImporter()
            .importPackages("lk.coopfed.archfixtures");

    @Test
    void layerRuleCatchesMasterDataReachingIntoTransactions() {
        assertViolation(ArchitectureTests.layersRule(), "MasterReachesTransactions");
    }

    @Test
    void schemaRuleCatchesAnEntityInAnotherModulesSchema() {
        assertViolation(ArchitectureTests.entitiesInOwnSchemaRule(), "WrongSchemaEntity");
    }

    @Test
    void schemaRuleCatchesAnEntityWithoutASchema() {
        assertViolation(ArchitectureTests.entitiesInOwnSchemaRule(), "NoSchemaEntity");
    }

    @Test
    void kernelRuleCatchesTheKernelImportingAModule() {
        assertViolation(ArchitectureTests.kernelImportsNoModuleRule(), "LeakyKernel");
    }

    @Test
    void internalsRuleCatchesAModuleUsingKernelInternals() {
        assertViolation(ArchitectureTests.noKernelInternalsRule(), "UsesKernelInternals");
    }

    @Test
    void permissionRuleCatchesAHandlerWithABlankPermission() {
        assertViolation(ArchitectureTests.handlersCarryPermissionRule(), "BlankPermissionHandler");
    }

    @Test
    void auditRuleCatchesAHandlerThatNeitherAuditsNorPublishes() {
        assertViolation(ArchitectureTests.handlersAuditAndPublishRule(), "SilentHandler");
    }

    @Test
    void writersRuleCatchesAWriteOutsideACommandHandler() {
        assertViolation(ArchitectureTests.onlyHandlersWriteRule(), "WritesOutsideAHandler");
    }

    @Test
    void writersRuleLeavesACommandHandlerAlone() {
        // SilentHandler saves too, but it is a handler: the writers rule must not name it.
        String report = ArchitectureTests.onlyHandlersWriteRule().evaluate(FIXTURES).getFailureReport().toString();
        assertTrue(!report.contains("SilentHandler"), report);
    }

    private static void assertViolation(
            ArchRule rule,
            String offendingClass) {

        EvaluationResult result = rule.evaluate(FIXTURES);

        assertTrue(result.hasViolation(), "rule did not fire: " + rule.getDescription());
        assertTrue(
                result.getFailureReport().toString().contains(offendingClass),
                "rule fired but not for " + offendingClass + ":\n" + result.getFailureReport());
    }
}
