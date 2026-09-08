package com.snippetveil.plugin

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.snippetveil.plugin.kotlin.KotlinSymbolKeys
import com.snippetveil.trust.SHIPPED_CLASSES
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **One key rule for two languages, held over shipped bytecode.**
 *
 * `SymbolKeyingTest` and `KotlinSymbolKeyingTest` assert what the keys *are*. Two of the decisions
 * behind them are not statements about a key at all — they are statements about **which call is
 * made** — and a test that compared answers could not tell either of them from a coincidence:
 *
 *  - A property's key is the backing field its light class exposes, and both languages must reach
 *    that field through **one call**. Two calls that agree today are two calls, and the day the
 *    platform's answer changes only one of them moves.
 *  - A classifier's locality is `ClassId == null`, never `ClassId.isLocal` — which carries
 *    `@ClassIdBasedLocality` and is documented as discouraged under K2 for unclear semantics.
 *
 * So they are checked the way the process rules are: over the compiled classes, by call site, in
 * every platform cell — including the floor, where no Kotlin fixture runs and these classes are
 * compiled anyway. Both are demonstrated red below, because a rule nobody has seen fail is a comment.
 *
 * The same [SHIPPED_CLASSES] the trust rules read, deliberately: a second importer would be a second
 * definition of *what ships*, and the two would drift the day one of them is updated.
 */
class OneKeyRuleTest {

    /**
     * A rule that matches nothing passes, so the coverage is asserted before the rules are: the one
     * class allowed to make the call has to be in the import, or `the backing field is found in
     * exactly one place` is green over an empty set.
     */
    @Test
    fun `the rules below are applied to the classes that make these calls`() {
        assertTrue(SHIPPED_CLASSES.any { it.name == SymbolKeys::class.java.name }) {
            "SymbolKeys is missing from the import, so the rules below pass vacuously."
        }
        assertTrue(SHIPPED_CLASSES.any { it.name.startsWith("com.snippetveil.plugin.kotlin.") }) {
            "No Kotlin-half class is shipped, so the ClassId rule below has nothing to be about."
        }
    }

    /**
     * **The property key's light-class call, asserted as one call rather than as two equal answers.**
     *
     * `SymbolKeys.backingFieldOf` is where it lives; nothing else shipped may ask a `PsiClass` for a
     * field by name. That is what makes the open question about private backing fields harmless: if
     * the platform stopped exposing them, the Java walk's accessor lookup and the Kotlin property's
     * key would **both** fall back to the getter, because they are the same call. An unverified
     * platform fact can change which key is produced; it cannot make the two languages disagree.
     */
    @Test
    fun `the backing field is found in exactly one place in shipped code`() {
        ONE_LIGHT_CLASS_CALL.check(SHIPPED_CLASSES)
    }

    /**
     * **The classifier locality test is `ClassId == null`, and never the flag on the id.**
     *
     * `ClassId.isLocal` carries `@ClassIdBasedLocality` and is documented as discouraged under K2 for
     * unclear semantics, so reading it would put a classifier's persistability — the decision that
     * says what may be written to a durable file — on a fact the platform declines to define.
     *
     * `CallableId.isLocal` is a different flag on a different type and stays legal, which is why the
     * rule names the owner. `KotlinSymbolKeys.namesAStablePosition` reads it deliberately, and
     * `StablePositionTest` is where that is asserted.
     */
    @Test
    fun `nothing shipped reads ClassId isLocal`() {
        NO_CLASSID_LOCALITY.check(SHIPPED_CLASSES)
    }

    /**
     * **Both rules, pointed at code written to violate them.**
     *
     * They earn the demonstration for the reason the isolation rule does: neither failure can be
     * produced by running anything here. A second `findFieldByName` would leave every keying test
     * green — the two answers agree on this platform, which is the whole point — and reading
     * `ClassId.isLocal` would give the same verdict as `ClassId == null` for every fixture that can
     * be written, right up to the K2 release where it does not.
     *
     * Both directions, because a rule that flagged the legal call too is one the next implementer has
     * to suppress, and a suppressed rule is where a violation eventually hides. [KeysTheOtherWay]
     * must be flagged; [SymbolKeys] and [ReadsTheCallableIdFlag] must not.
     *
     * The *same* [ArchRule] objects the two tests above check, for the reason the process rules are
     * hoisted: a rebuilt copy would prove that a copy can fail.
     */
    @Test
    fun `both rules flag the call they ban and leave the legal one alone`() {
        val violations = ONE_LIGHT_CLASS_CALL.violationsIn(
            classesOf(KeysTheOtherWay::class.java, SymbolKeys::class.java)
        )
        assertTrue(KeysTheOtherWay::class.java.name in violations) {
            "The one-call rule did not flag a second findFieldByName: $violations"
        }
        assertTrue(SymbolKeys::class.java.name !in violations) {
            "The one-call rule flagged the one place the call belongs, which an implementer would " +
                "have to suppress: $violations"
        }

        val local = NO_CLASSID_LOCALITY.violationsIn(classesOf(KeysTheOtherWay::class.java))
        assertTrue("isLocal" in local) {
            "The locality rule did not flag a read of ClassId.isLocal: $local"
        }

        // CallableId.isLocal is a different flag on a different type, and the key rule reads it.
        // `check` throws if the rule fires, and the message it throws with is the report.
        NO_CLASSID_LOCALITY.check(classesOf(ReadsTheCallableIdFlag::class.java))
    }

    private fun classesOf(vararg types: Class<*>): JavaClasses = ClassFileImporter().importClasses(*types)

    /** The violations [this] reports against [classes]. Fails the test if it reports none. */
    private fun ArchRule.violationsIn(classes: JavaClasses): String =
        assertThrows(AssertionError::class.java) { check(classes) }.message.orEmpty()
}

/**
 * **The locality half of the persistence rule, exercised where only a hand-built id can reach it.**
 *
 * A plain test rather than a fixture-based one: nothing here needs an IDE, and the failure it pins
 * is one no fixture on this platform produces — the PSI-derived id of a local is `null`, so the
 * `!isLocal` half would never fire and would sit there looking like a line nobody needed.
 *
 * Here rather than beside the Kotlin keying fixtures for the reason the rules above are here: the
 * floor cell excludes `com.snippetveil.plugin.kotlin` because K1 answers light-class questions
 * differently, and this asks none. A predicate that holds in every cell should be checked in every
 * cell.
 *
 * It is needed because **the sources disagree about what a local's id is**. `CallableId`'s own
 * documentation gives `<local>/loc` — non-null, and not stable — while a survey of the API recorded
 * `callableId` as `null`. Persisting a local on a non-null-but-local id writes a key that re-points
 * on the next edit, which is precisely the failure structural path keys were rejected over.
 */
internal class StablePositionTest {

    @Test
    fun `a local callable id does not name a stable position, and neither does a missing one`() {
        assertFalse(
            KotlinSymbolKeys.namesAStablePosition(
                CallableId(FqName("<local>"), FqName("loc"), Name.identifier("settle"))
            ),
            "a `<local>` id is non-null and still names nothing that survives an edit",
        )
        assertFalse(
            KotlinSymbolKeys.namesAStablePosition(null),
            "a missing id names no position at all",
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            KotlinSymbolKeys.namesAStablePosition(
                CallableId(FqName("com.acme"), FqName("Clock"), Name.identifier("now"))
            ),
            "an ordinary member's id was rejected, which would keep every Kotlin callable out of the ledger",
        )
    }
}

/**
 * The red path of both rules, baked in rather than observed once.
 *
 * Test scope, so [SHIPPED_CLASSES] excludes it and the rules never see it in anger; neither method is
 * ever called. It is imported by name, one class at a time, which is the only way a rule gets pointed
 * at code that is meant to violate it.
 */
internal class KeysTheOtherWay {

    /** The second `findFieldByName` — two calls that agree today, and one of them moves tomorrow. */
    fun theOtherFieldLookup(owner: PsiClass, name: String): PsiField? = owner.findFieldByName(name, false)

    /** The locality test the K2 documentation discourages. */
    fun theDiscouragedLocalityTest(id: ClassId): Boolean = id.isLocal
}

/**
 * `CallableId.isLocal` is a different flag on a different type and is read on purpose, so the rule
 * above is asserted legal over it — a rule that banned both would be the kind of noise that teaches
 * people to suppress a check.
 */
internal class ReadsTheCallableIdFlag {

    fun stable(id: CallableId): Boolean = !id.isLocal

    fun anId(): CallableId = CallableId(FqName("com.acme"), FqName("Clock"), Name.identifier("now"))
}

/**
 * Everything except the one function the light-class call belongs in.
 *
 * Expressed as *not that class* rather than as an exception list, for the reason the Kotlin isolation
 * rule is: an exception list is where a violation eventually hides, and this one has exactly one
 * member by design.
 */
private val EVERYTHING_BUT_SYMBOL_KEYS: DescribedPredicate<JavaClass> =
    object : DescribedPredicate<JavaClass>("are not SymbolKeys") {
        override fun test(javaClass: JavaClass): Boolean = javaClass.name != SymbolKeys::class.java.name
    }

/**
 * Every `findFieldByName` call, matched by the method's name so that no descriptor and no receiver
 * type has to be enumerated — a light class, a `PsiClassImpl` and a `PsiClass` reference all reach
 * the same method, and a rule that named one of them would miss the other two.
 */
private val ONE_LIGHT_CLASS_CALL: ArchRule =
    noClasses().that(EVERYTHING_BUT_SYMBOL_KEYS)
        .should(
            object : ArchCondition<JavaClass>("call PsiClass.findFieldByName") {
                override fun check(item: JavaClass, events: ConditionEvents) {
                    item.accessesFromSelf
                        .filter { it.isFindFieldByName() }
                        .forEach { events.add(SimpleConditionEvent.satisfied(it, it.description)) }
                }
            }
        )
        .because(
            "a property's key is the backing field its light class exposes, and two languages " +
                "reaching that field through two calls is two answers that can part company"
        )

private val NO_CLASSID_LOCALITY: ArchRule =
    noClasses().should(
        object : ArchCondition<JavaClass>("read ClassId.isLocal") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                item.accessesFromSelf
                    .filter { it.targetOwner.name == "org.jetbrains.kotlin.name.ClassId" && it.target.name == "isLocal" }
                    .forEach { events.add(SimpleConditionEvent.satisfied(it, it.description)) }
            }
        }
    ).because(
        "ClassId.isLocal carries @ClassIdBasedLocality and is documented as discouraged under K2 " +
            "for unclear semantics; a classifier is local iff its ClassId is null"
    )

private fun JavaAccess<*>.isFindFieldByName(): Boolean = target.name == "findFieldByName"
