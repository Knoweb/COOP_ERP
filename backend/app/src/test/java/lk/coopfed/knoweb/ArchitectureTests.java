package lk.coopfed.knoweb;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Table;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import java.util.Map;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Rules R1 to R7 of doc 17 §6.1 as tests (17A §5). They are the only reason a
 * modular monolith stays modular; a violation fails the build.
 */
class ArchitectureTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(CoopErpApplication.class);

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(CoopErpApplication.class.getPackageName());

    /** Module package name to the database schemas it owns (17A §2, doc 18 Part F). */
    private static final Map<String, Set<String>> SCHEMA_OWNERSHIP = Map.ofEntries(
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
    void layersAreRespected() {                 // R2, R3: kernel -> master data -> transactions -> read side

        layeredArchitecture()
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
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void entitiesStayInTheirModuleSchema() {    // R4: a module's tables live in its own schema only

        classes()
                .that()
                .areAnnotatedWith(Table.class)
                .should(declareTheirOwnModuleSchema())
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void kernelImportsNoBusinessModule() {      // R5

        noClasses()
                .that()
                .resideInAPackage("..kernel..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
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
                        "..hello..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void businessModulesDoNotAccessKernelInternals() {

        noClasses()
                .that()
                .resideInAnyPackage(
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
                        "..hello..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..kernel.internal..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void commandHandlersCarryPermissions() {    // R7: no handler without a permission

        classes()
                .that()
                .areAnnotatedWith(CommandHandler.class)
                .should(haveNonBlankPermission())
                .allowEmptyShould(true)
                .check(CLASSES);
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
