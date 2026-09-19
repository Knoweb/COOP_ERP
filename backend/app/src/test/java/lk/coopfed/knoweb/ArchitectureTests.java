package lk.coopfed.knoweb;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Rules R1 to R7 of doc 17 §6.1 as tests (17A §5). They are the only reason a
 * modular monolith stays modular; a violation fails the build. The rules are
 * static methods so that {@link ArchitectureRulesBiteTest} can prove each one
 * reports a violation against deliberately wrong classes.
 */
class ArchitectureTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(CoopErpApplication.class);

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(CoopErpApplication.class.getPackageName());

    private static final String[] BUSINESS_PACKAGES = {
            "..m1party..",
            "..m2catalogue..",
            "..m3pricing..",
            "..m4trading..",
            "..m5inventory..",
            "..m6pos..",
            "..m7customers..",
            "..m8reporting..",
            "..m9integration..",
            "..m10procurement..",
            "..hello.."
    };

    /** Module package name to the database schemas it owns (17A §2, doc 18 Part F). */
    static final Map<String, Set<String>> SCHEMA_OWNERSHIP = Map.ofEntries(
            Map.entry("kernel", Set.of("kernel")),
            Map.entry("hello", Set.of("hello")),
            Map.entry("m1party", Set.of("party", "security")),
            Map.entry("m2catalogue", Set.of("catalogue")),
            Map.entry("m3pricing", Set.of("pricing")),
            Map.entry("m4trading", Set.of("trading")),
            Map.entry("m5inventory", Set.of("inventory")),
            Map.entry("m6pos", Set.of("pos")),
            Map.entry("m7customers", Set.of("customers")),
            Map.entry("m8reporting", Set.of("reporting")),
            Map.entry("m9integration", Set.of("integration")),
            Map.entry("m10procurement", Set.<String>of()));

    @Test
    void modulithStructureIsValid() {           // R1, R5: published packages, allowed dependencies
        MODULES.verify();
    }

    @Test
    void documentsTheModuleGraph() {
        new Documenter(MODULES)
                .writeDocumentation();
    }

    @Test
    void layersAreRespected() {                 // R2, R3
        layersRule().check(CLASSES);
    }

    @Test
    void entitiesStayInTheirModuleSchema() {    // R4
        entitiesInOwnSchemaRule().check(CLASSES);
    }

    @Test
    void kernelImportsNoBusinessModule() {      // R5
        kernelImportsNoModuleRule().check(CLASSES);
    }

    @Test
    void businessModulesDoNotAccessKernelInternals() {
        noKernelInternalsRule().check(CLASSES);
    }

    @Test
    void commandHandlersCarryPermissions() {    // R7
        handlersCarryPermissionRule().check(CLASSES);
    }


    @Test
    void commandHandlersAuditAndPublish() {     // AGENTS.md: guards, mutation, audit, event
        handlersAuditAndPublishRule().check(CLASSES);
    }

    @Test
    void onlyCommandHandlersWriteToTheDatabase() {
        onlyHandlersWriteRule().check(CLASSES);
    }

    /** R2, R3: kernel -> master data (M1-M3) -> transactions (M4-M7, M10) -> read side (M8, M9). */
    static ArchRule layersRule() {
        return layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .layer("kernel").definedBy("..kernel..")
                .layer("master").definedBy(
                        "..m1party..",
                        "..m2catalogue..",
                        "..m3pricing..")
                .layer("transactions").definedBy(
                        "..m4trading..",
                        "..m5inventory..",
                        "..m6pos..",
                        "..m7customers..",
                        "..m10procurement..")
                .layer("read").definedBy(
                        "..m8reporting..",
                        "..m9integration..")
                .whereLayer("master").mayOnlyAccessLayers("kernel")
                .whereLayer("transactions").mayOnlyAccessLayers("kernel", "master")
                .whereLayer("read").mayOnlyAccessLayers("kernel", "master", "transactions")
                .allowEmptyShould(true);
    }

    /** R4: every entity names a schema, and one its own module owns. */
    static ArchRule entitiesInOwnSchemaRule() {
        return classes()
                .that()
                .areAnnotatedWith(Table.class)
                .should(declareTheirOwnModuleSchema())
                .allowEmptyShould(true);
    }

    /** R5: the kernel depends on no module. */
    static ArchRule kernelImportsNoModuleRule() {
        return noClasses()
                .that()
                .resideInAPackage("..kernel..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(BUSINESS_PACKAGES)
                .allowEmptyShould(true);
    }

    /** Modules use the kernel through kernel.api only. */
    static ArchRule noKernelInternalsRule() {
        return noClasses()
                .that()
                .resideInAnyPackage(BUSINESS_PACKAGES)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..kernel.internal..")
                .allowEmptyShould(true);
    }

    /** R7: no handler without a permission. */
    static ArchRule handlersCarryPermissionRule() {
        return classes()
                .that()
                .areAnnotatedWith(CommandHandler.class)
                .should(haveNonBlankPermission())
                .allowEmptyShould(true);
    }

    /**
     * Every command handler records an audit event and publishes a domain event (AGENTS.md:
     * guards, mutation, audit.record, events.publish). A forgotten audit call is otherwise
     * invisible: nothing fails, the trail simply has a hole.
     *
     * <p>What this rule proves: the calls exist somewhere in the handler class. What it cannot
     * prove: that every path reaches them, their order, or their content. The handler's
     * integration test does that, with the KernelRecorder of the test support package.
     */
    static ArchRule handlersAuditAndPublishRule() {
        return classes()
                .that()
                .areAnnotatedWith(CommandHandler.class)
                .should(call(AuditFacade.class, "record"))
                .andShould(call(EventPublisher.class, "publish"))
                .allowEmptyShould(true);
    }

    /**
     * The other half: in a business module, only a command handler may write to the database.
     * A service or controller that saves through a repository changes data that no handler
     * audited and no event announced.
     *
     * <p>Counted as a write: save*, delete*, insert* and update* on a Spring Data repository;
     * persist, merge and remove on an EntityManager; update, batchUpdate and execute on a
     * JdbcTemplate. When 19A adds event consumers and scheduled jobs (projections in M8, for
     * example), their annotations join CommandHandler in the list of allowed writers here.
     */
    static ArchRule onlyHandlersWriteRule() {
        return noClasses()
                .that()
                .resideInAnyPackage(BUSINESS_PACKAGES)
                .and()
                .areNotAnnotatedWith(CommandHandler.class)
                .should(writeToTheDatabase())
                .allowEmptyShould(true);
    }

    private static ArchCondition<JavaClass> call(
            Class<?> owner,
            String methodName) {
        return new ArchCondition<>(
                "call " + owner.getSimpleName() + "." + methodName + "(...)") {
            @Override
            public void check(
                    JavaClass javaClass,
                    ConditionEvents events) {
                boolean calls = javaClass.getMethodCallsFromSelf().stream()
                        .anyMatch(c -> c.getName().equals(methodName)
                                && c.getTargetOwner().isAssignableTo(owner));
                if (!calls) {
                    events.add(
                            SimpleConditionEvent.violated(
                                    javaClass,
                                    javaClass.getName()
                                            + " is a @CommandHandler but never calls "
                                            + owner.getSimpleName() + "." + methodName + "(...)"));
                }
            }
        };
    }

    private static final Pattern REPOSITORY_WRITE = Pattern.compile("(save|delete|insert|update).*");
    private static final Set<String> ENTITY_MANAGER_WRITE = Set.of("persist", "merge", "remove");
    private static final Set<String> JDBC_WRITE = Set.of("update", "batchUpdate", "execute");

    /** Used under noClasses(): every write found is reported as one violation. */
    private static ArchCondition<JavaClass> writeToTheDatabase() {
        return new ArchCondition<>(
                "write to the database (only @CommandHandler classes may)") {
            @Override
            public void check(
                    JavaClass javaClass,
                    ConditionEvents events) {
                for (JavaMethodCall c : javaClass.getMethodCallsFromSelf()) {
                    JavaClass target = c.getTargetOwner();
                    boolean write =
                            (target.isAssignableTo(Repository.class)
                                    && REPOSITORY_WRITE.matcher(c.getName()).matches())
                                    || (target.isAssignableTo(EntityManager.class)
                                    && ENTITY_MANAGER_WRITE.contains(c.getName()))
                                    || (target.isAssignableTo(JdbcOperations.class)
                                    && JDBC_WRITE.contains(c.getName()));
                    if (write) {
                        events.add(
                                SimpleConditionEvent.satisfied(
                                        c,
                                        javaClass.getName() + " writes through "
                                                + target.getSimpleName() + "." + c.getName()
                                                + "(...) but is not a @CommandHandler; "
                                                + c.getSourceCodeLocation()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> declareTheirOwnModuleSchema() {

        return new ArchCondition<>(
                "declare @Table(schema = ...) within their own module's schemas") {
            @Override
            public void check(
                    JavaClass javaClass,
                    ConditionEvents events) {

                String module = moduleOf(javaClass);
                Set<String> allowed = SCHEMA_OWNERSHIP.getOrDefault(module, Set.of());
                String schema = javaClass.getAnnotationOfType(Table.class).schema();

                if (schema == null || schema.isBlank()) {
                    events.add(
                            SimpleConditionEvent.violated(
                                    javaClass,
                                    javaClass.getName()
                                            + " declares no schema on @Table; "
                                            + "every entity names its module schema"));
                } else if (!allowed.contains(schema)) {
                    events.add(
                            SimpleConditionEvent.violated(
                                    javaClass,
                                    javaClass.getName()
                                            + " maps to schema \"" + schema
                                            + "\" but module " + module
                                            + " owns " + allowed));
                }
            }
        };
    }

    /** The module is the first package segment below the application's root package. */
    private static String moduleOf(
            JavaClass javaClass) {

        String prefix = CoopErpApplication.class.getPackageName() + ".";
        String pkg = javaClass.getPackageName();

        if (!pkg.startsWith(prefix)) {
            return "";
        }

        String rest = pkg.substring(prefix.length());
        int dot = rest.indexOf('.');

        return dot < 0 ? rest : rest.substring(0, dot);
    }

    private static ArchCondition<JavaClass> haveNonBlankPermission() {

        return new ArchCondition<>(
                "declare a non-blank @CommandHandler permission") {
            @Override
            public void check(
                    JavaClass javaClass,
                    ConditionEvents events) {

                CommandHandler annotation = javaClass.reflect()
                        .getAnnotation(
                                CommandHandler.class);

                boolean valid = annotation != null
                        && annotation.permission() != null
                        && !annotation.permission()
                                .isBlank();

                if (!valid) {

                    events.add(
                            SimpleConditionEvent.violated(
                                    javaClass,
                                    javaClass.getName()
                                            + " has an empty "
                                            + "@CommandHandler permission"));
                }
            }
        };
    }
}
