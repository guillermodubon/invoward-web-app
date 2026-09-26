package io.github.guillermodubon.invoward.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class LayerDependencyRulesTest {

    private static final String BASE_PACKAGE = "io.github.guillermodubon.invoward";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    private static final ArchRule DOMAIN_MUST_NOT_DEPEND_ON_FRAMEWORKS = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..");

    private static final ArchRule DOMAIN_MUST_NOT_DEPEND_ON_OTHER_LAYERS = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..api..", "..application..", "..infrastructure..");

    private static final ArchRule APPLICATION_MUST_NOT_DEPEND_ON_API_OR_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..api..", "..infrastructure..");

    private static final ArchRule API_MUST_NOT_DEPEND_ON_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..infrastructure..");

    @Test
    void domainMustRemainIndependentFromFrameworks() {
        DOMAIN_MUST_NOT_DEPEND_ON_FRAMEWORKS.check(PRODUCTION_CLASSES);
    }

    @Test
    void domainMustNotDependOnOtherLayers() {
        DOMAIN_MUST_NOT_DEPEND_ON_OTHER_LAYERS.check(PRODUCTION_CLASSES);
    }

    @Test
    void applicationMustNotDependOnApiOrInfrastructure() {
        APPLICATION_MUST_NOT_DEPEND_ON_API_OR_INFRASTRUCTURE.check(PRODUCTION_CLASSES);
    }

    @Test
    void apiMustNotDependOnInfrastructure() {
        API_MUST_NOT_DEPEND_ON_INFRASTRUCTURE.check(PRODUCTION_CLASSES);
    }
}
