package io.github.guillermodubon.invoward.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ModuleDependencyRulesTest {

    private static final String BASE_PACKAGE = "io.github.guillermodubon.invoward";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    private static final ArchRule SHARED_MUST_NOT_DEPEND_ON_BUSINESS_MODULES = noClasses()
            .that().resideInAPackage("..shared..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "..identity..",
                    "..document..",
                    "..analysis..",
                    "..extraction..",
                    "..reconciliation..",
                    "..reporting..",
                    "..sharing..",
                    "..notification.."
            );

    private static final ArchRule MODULES_MUST_BE_FREE_OF_CYCLES = slices()
            .matching(BASE_PACKAGE + ".(*)..")
            .should().beFreeOfCycles();

    @Test
    void sharedMustNotDependOnBusinessModules() {
        SHARED_MUST_NOT_DEPEND_ON_BUSINESS_MODULES.check(PRODUCTION_CLASSES);
    }

    @Test
    void modulesMustBeFreeOfCycles() {
        MODULES_MUST_BE_FREE_OF_CYCLES.check(PRODUCTION_CLASSES);
    }
}
