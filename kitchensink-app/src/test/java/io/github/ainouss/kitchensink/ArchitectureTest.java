package io.github.ainouss.kitchensink;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Checks the boundaries between domain, application and adapters (see docs/architecture.md).
 */
@AnalyzeClasses(packages = "io.github.ainouss.kitchensink", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule the_domain_depends_on_nothing_but_the_jdk_and_jakarta_validation = classes()
            .that().resideInAPackage("..member.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "jakarta.validation..", "..member.domain..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule the_application_does_not_depend_on_adapters = noClasses()
            .that().resideInAPackage("..member.application..")
            .should().dependOnClassesThat().resideInAPackage("..member.adapter..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule the_application_does_not_depend_on_spring_web_or_mongodb = noClasses()
            .that().resideInAPackage("..member.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.data.mongodb..", "com.mongodb..", "org.bson..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule adapters_do_not_depend_on_each_other = slices()
            .matching("..member.adapter.(*).(*)..")
            .should().notDependOnEachOther()
            .allowEmptyShould(true);
}
