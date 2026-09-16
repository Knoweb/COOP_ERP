package lk.coopfed.knoweb;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(KnowebApplication.class);

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("lk.coopfed.knoweb");

    @Test
    void modulithStructureIsValid() {
        MODULES.verify();
    }

    @Test
    void documentsTheModuleGraph() {
        new Documenter(MODULES)
                .writeDocumentation();
    }

    @Test
    void kernelImportsNoBusinessModule() {

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
    void commandHandlersCarryPermissions() {

        classes()
                .that()
                .areAnnotatedWith(CommandHandler.class)
                .should(haveNonBlankPermission())
                .allowEmptyShould(true)
                .check(CLASSES);
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