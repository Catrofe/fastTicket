package com.fastticket.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class ModuleDependencyTest {

    private val modules = mapOf(
        "catalog" to emptySet(),
        "pricing" to setOf("catalog"),
        "inventory" to setOf("catalog", "pricing"),
        "payments" to emptySet(),
        "checkout" to setOf("inventory", "payments"),
        "queue" to emptySet(),
        "realtime" to setOf("inventory", "checkout"),
        "jobs" to setOf("inventory", "checkout", "payments", "queue"),
    )

    private val production: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.fastticket")

    private val withFixtures: JavaClasses = ClassFileImporter().importPackages("com.fastticket")

    private val moduleRules: List<ArchRule> = modules.map { (module, allowed) ->
        val forbidden = (modules.keys - module - allowed).map { "com.fastticket.$it.." }.toTypedArray()
        noClasses().that().resideInAPackage("com.fastticket.$module..")
            .should().dependOnClassesThat().resideInAnyPackage(*forbidden)
            .allowEmptyShould(true)
            .`as`("module $module only depends on ${allowed.ifEmpty { setOf("shared") }}")
    } + noClasses().that().resideInAPackage("com.fastticket.shared..")
        .should().dependOnClassesThat().resideInAnyPackage(*modules.keys.map { "com.fastticket.$it.." }.toTypedArray())
        .allowEmptyShould(true)
        .`as`("shared depends on no module")

    private val internalPackage = Regex("""com\.fastticket\.([a-z]+)\.(api|application|domain|infra)(\.|$)""")

    private val publicApiRule: ArchRule = classes().should(object : ArchCondition<JavaClass>("not use another module's internals") {
        override fun check(item: JavaClass, events: ConditionEvents) {
            item.directDependenciesFromSelf.forEach { dependency ->
                val target = internalPackage.find(dependency.targetClass.packageName)
                if (target != null && !item.packageName.startsWith("com.fastticket.${target.groupValues[1]}")) {
                    events.add(SimpleConditionEvent.violated(dependency, dependency.description))
                }
            }
        }
    }).allowEmptyShould(true).`as`("only the root of a module is visible to other modules")

    private val applicationRule: ArchRule = noClasses().that().resideInAPackage("com.fastticket.*.application..")
        .should().dependOnClassesThat().resideInAnyPackage("com.fastticket.*.infra..", "com.fastticket.*.api..")
        .allowEmptyShould(true).`as`("application does not depend on infra or api")

    private val jobsRules: List<ArchRule> = listOf(
        noClasses().that().resideInAPackage("com.fastticket.jobs..")
            .should().dependOnClassesThat().resideInAnyPackage("com.fastticket.*.domain..", "com.fastticket.*.infra..")
            .allowEmptyShould(true).`as`("jobs does not depend on domain or infra"),
        classes().that().resideInAPackage("com.fastticket.jobs..")
            .should().resideInAPackage("com.fastticket.jobs")
            .allowEmptyShould(true).`as`("jobs has no subpackages"),
    )

    private val noCycles: ArchRule = slices().matching("com.fastticket.(*)..")
        .should().beFreeOfCycles().allowEmptyShould(true)

    private val layerRules: List<ArchRule> = listOf(
        noClasses().that().resideInAPackage("com.fastticket.*.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.fastticket.*.api..", "com.fastticket.*.infra..", "org.springframework.web..",
            )
            .allowEmptyShould(true).`as`("domain does not depend on api, infra or web"),
        noClasses().that().resideInAPackage("com.fastticket.*.api..")
            .should().dependOnClassesThat().resideInAPackage("com.fastticket.*.infra..")
            .allowEmptyShould(true).`as`("api does not depend on infra"),
    )

    private val javaTimeNow = DescribedPredicate.describe<JavaCall<*>>("call java.time *.now()") {
        it.name == "now" && it.targetOwner.packageName == "java.time"
    }

    private val timeRules: List<ArchRule> = listOf(
        noClasses().that().resideOutsideOfPackage("com.fastticket.shared.time..")
            .should().callMethodWhere(javaTimeNow)
            .allowEmptyShould(true).`as`("only DbClock reads java.time now()"),
        noClasses().that().resideOutsideOfPackage("com.fastticket.shared.time..")
            .should().callMethod(System::class.java, "currentTimeMillis")
            .allowEmptyShould(true).`as`("no System.currentTimeMillis()"),
        noClasses().that().resideOutsideOfPackage("com.fastticket.shared.time..")
            .should().dependOnClassesThat().areAssignableTo(java.time.Clock::class.java)
            .allowEmptyShould(true).`as`("no java.time.Clock outside shared.time"),
        noClasses().that().resideOutsideOfPackage("com.fastticket.shared.time..")
            .should().dependOnClassesThat().areAssignableTo(java.util.Date::class.java)
            .orShould().dependOnClassesThat().areAssignableTo(java.util.Calendar::class.java)
            .allowEmptyShould(true).`as`("no java.util.Date or Calendar outside shared.time"),
    )

    private val redisRule: ArchRule = noClasses()
        .that().resideInAnyPackage("com.fastticket.catalog..", "com.fastticket.pricing..", "com.fastticket.inventory..",
            "com.fastticket.payments..", "com.fastticket.checkout..", "com.fastticket.shared..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.data.redis..")
        .allowEmptyShould(true).`as`("only queue, realtime and jobs know Redis")

    private val knownPackagesOnly: ArchRule = classes().that().resideInAPackage("com.fastticket..")
        .should().resideInAnyPackage(
            "com.fastticket", "com.fastticket.shared..", *modules.keys.map { "com.fastticket.$it.." }.toTypedArray(),
        )
        .allowEmptyShould(true).`as`("production code lives in the root package, shared or a known module")

    private val allRules: List<ArchRule> = moduleRules + publicApiRule + applicationRule + jobsRules + noCycles + layerRules + timeRules + redisRule + knownPackagesOnly

    @Test
    fun `production code respects every architecture rule`() {
        allRules.forEach { it.check(production) }
    }

    @Test
    fun `rules fail on deliberately violating fixtures`() {
        val mustFail = allRules
        mustFail.forEach { rule ->
            assertFailsWith<AssertionError>("rule should fail: ${rule.description}") { rule.check(withFixtures) }
        }
    }
}
