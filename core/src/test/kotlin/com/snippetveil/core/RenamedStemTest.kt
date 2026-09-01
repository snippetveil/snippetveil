package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * **A renamed stem, and the number that never goes away.**
 *
 * The rule under test is one sentence — a renamed placeholder renders as `stem + number` and there
 * is no way to remove the number — and everything else here is about which keys the engine will
 * take a stem for at all. That half is enforced in `:core` rather than in the dialog on purpose:
 * a dialog that offered a row it should not have has to change no character of the output, which is
 * a property only a test on this side can assert.
 */
class RenamedStemTest {

    /**
     * **The whole feature in one assertion**: the stem is the user's, the number is the counter's,
     * and the two are written together.
     */
    @Test
    fun `a custom stem renders as the stem followed by the number`() {
        val plan = planOf(
            "Payment payment = repository.load();",
            symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment"),
            symbol("payment", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:payment"),
            symbol("repository", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:repository"),
        )

        val result = anonymize(plan, renaming("class:com.acme.Payment" to "FilterType"), LedgerSnapshot.EMPTY)

        assertEquals("FilterType1 local2 = field3.load();", result.text)
        assertEquals("FilterType1", result.placeholdersFor("Payment").single())
    }

    /**
     * **There is no way to drop the number**, and this is the assertion that says so: a stem that is
     * already a whole placeholder-looking word still gets one of its own, because the number is not
     * something the stem can supply.
     */
    @Test
    fun `a stem does not replace the number, whatever it is spelled like`() {
        val plan = planOf(
            "Payment p;",
            symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment"),
        )

        val result = anonymize(plan, renaming("class:com.acme.Payment" to "Filter"), LedgerSnapshot.EMPTY)

        assertEquals("Filter1 p;", result.text)
    }

    /**
     * **Stability comes from the existing ledger row and from nothing else.** The rename is an input
     * to one invocation; what makes `FilterType1` come back next week is that the qualified key was
     * written down with the placeholder it was given, exactly like a default-stemmed one.
     */
    @Test
    fun `a renamed qualified placeholder comes back verbatim on the next invocation`() {
        val payment = symbol(
            "Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT,
            key = "class:com.acme.Payment", keyIsQualified = true,
        )

        val first = anonymize(
            planOf("Payment p;", payment),
            renaming("class:com.acme.Payment" to "FilterType"),
            LedgerSnapshot.EMPTY,
        )
        val committed = LedgerSnapshot.EMPTY + first.delta

        // The next invocation states no rename at all, which is the point: the stem was never
        // persisted as a setting, and the row is what carries it.
        val second = anonymize(planOf("Payment q;", payment), AnonymizationSettings.DEFAULTS, committed)

        assertEquals("FilterType1 p;", first.text)
        assertEquals(mapOf("class:com.acme.Payment" to MintedName("FilterType1", "Payment")), first.delta.placeholders)
        assertEquals("FilterType1 q;", second.text)
    }

    /**
     * **A key already in the ledger keeps the name it was sent under.** The ledger is append-only —
     * a record of what was actually sent — and a rewritten entry would make an old reply decode
     * wrongly or not at all. The stem is ignored here rather than refused, so a dialog that offered
     * the editor on the wrong row would still change nothing.
     */
    @Test
    fun `a stem for a key the ledger already holds is ignored`() {
        val payment = symbol(
            "Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT,
            key = "class:com.acme.Payment", keyIsQualified = true,
        )
        val ledger = LedgerSnapshot(
            mapOf("class:com.acme.Payment" to MintedName("Type1", "Payment")),
            nextNumber = 2,
        )

        val result = anonymize(
            planOf("Payment p;", payment),
            renaming("class:com.acme.Payment" to "FilterType"),
            ledger,
        )

        assertEquals("Type1 p;", result.text)
        assertEquals(Renaming.ESTABLISHED, result.names.single().renaming)
    }

    /**
     * **The `Unknown` namespace is load-bearing and a rename may not move it.** `Unknown1` tells the
     * model the IDE could not resolve this, which localizes the breakage; a stem the user chose
     * would invite confident reasoning about a symbol nothing resolved.
     */
    @Test
    fun `a stem for an unresolved name is ignored`() {
        val plan = planOf(
            "MissingType m;",
            symbol("MissingType", SymbolRole.TYPE, SymbolOrigin.UNRESOLVED, key = "unresolved:MissingType"),
        )

        val result = anonymize(plan, renaming("unresolved:MissingType" to "FilterType"), LedgerSnapshot.EMPTY)

        assertEquals("Unknown1 m;", result.text)
        assertEquals(Renaming.NONE, result.names.single().renaming)
    }

    /**
     * **A literal cannot be renamed because there is nothing to address it by.** It has no key, so
     * no entry in the map can reach it — which is the same structural reason it cannot be preserved,
     * and it is the right one for the most directly sensitive content the product handles.
     */
    @Test
    fun `a literal keeps the str namespace whatever is renamed`() {
        val plan = planOf(
            """String s = "acme-live";""",
        ).withLiteral(""""acme-live"""")

        val result = anonymize(plan, renaming("acme-live" to "Token", "str1" to "Token"), LedgerSnapshot.EMPTY)

        assertEquals("""String s = "str1";""", result.text)
        assertEquals(Renaming.NONE, result.names.single().renaming)
    }

    /**
     * **An invalid stem falls back to the default namespace rather than throwing or refusing.** The
     * invariant may not depend on the dialog having validated anything, which is the same reasoning
     * that put the preserve rule in the engine.
     *
     * The trailing digit is the case that is about this product rather than about Java: stem
     * `Filter2` with number 1 and stem `Filter` with number 21 would both render `Filter21`.
     */
    @Test
    fun `an invalid stem falls back to the default namespace`() {
        val payment = symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment")

        for (stem in listOf("Filter2", "9Filter", "my filter", "filter-type", "  ", "")) {
            val result = anonymize(
                planOf("Payment p;", payment),
                renaming("class:com.acme.Payment" to stem),
                LedgerSnapshot.EMPTY,
            )

            assertEquals("Type1 p;", result.text, "the stem `$stem` reached the output")
        }
    }

    /**
     * **A stem may not spell one of this engine's own namespaces.** Injectivity is untouched — the
     * counter is shared — but the *signal* is: `Unknown1` tells the model the IDE could not resolve
     * a name, and a resolved symbol renaming itself into that namespace would say something untrue.
     * `str` would let a symbol pass for a redacted literal, and the rest read as a claim about what
     * kind of thing the placeholder stands for.
     */
    @Test
    fun `a stem spelling one of the engine's own namespaces is refused and ignored`() {
        val payment = symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment")

        for (stem in listOf("Unknown", "str", "Type", "field", "T", "pkg", "getField")) {
            assertEquals(StemRejection.RESERVED_NAMESPACE, stemRejection(stem), "`$stem` was accepted as a stem")

            val result = anonymize(
                planOf("Payment p;", payment),
                renaming("class:com.acme.Payment" to stem),
                LedgerSnapshot.EMPTY,
            )

            assertEquals("Type1 p;", result.text, "the stem `$stem` reached the output")
        }
    }

    /**
     * **An unqualified key's rename lasts this invocation and no longer**, which is the ticket's
     * accepted cost stated as a test rather than as a caveat: a local is never ledgered, so there is
     * no row to carry the stem, and the next snippet re-mints it under the default namespace.
     *
     * The `filter` local still carried its context in the snippet where the question was, which is
     * what the cost was accepted for.
     */
    @Test
    fun `a rename of an unqualified key does not survive into the next invocation`() {
        val filter = symbol("filter", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:filter")
        val plan = planOf("int filter = 0;", filter)

        val first = anonymize(plan, renaming(filter.key to "theFilter"), LedgerSnapshot.EMPTY)
        val second = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY + first.delta)

        assertEquals("int theFilter1 = 0;", first.text)
        assertEquals(emptyMap<String, MintedName>(), first.delta.placeholders, "an unqualified key was ledgered")
        assertEquals("int local2 = 0;", second.text)
    }

    /**
     * **A collision burns the number and retries under the same stem.** Nothing about allocation
     * changes: the candidate is checked against the names surviving into the output exactly as a
     * default-stemmed one is, so a renamed placeholder can never be a word the snippet already says.
     */
    @Test
    fun `a stem colliding with a surviving name burns the number and keeps the stem`() {
        val plan = planOf(
            "Payment p = Filter1.of(Filter2.NONE);",
            symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment"),
            symbol("Filter1", SymbolRole.TYPE, SymbolOrigin.LIBRARY, key = "class:org.vendor.Filter1"),
            symbol("Filter2", SymbolRole.TYPE, SymbolOrigin.LIBRARY, key = "class:org.vendor.Filter2"),
        )

        val result = anonymize(plan, renaming("class:com.acme.Payment" to "Filter"), LedgerSnapshot.EMPTY)

        assertEquals("Filter3 p = Filter1.of(Filter2.NONE);", result.text)
        assertEquals(4, result.delta.nextNumber, "the burnt numbers did not move the counter")
    }

    /**
     * **Renaming a field's stem propagates to the accessors derived from it**, because that
     * derivation is what makes the two names agree the way the source's two names did.
     */
    @Test
    fun `an accessor derives its placeholder over the field's custom stem`() {
        val field = symbol(
            "merchantId", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT,
            key = "field:class:com.acme.Payment#merchantId", keyIsQualified = true,
        )
        val plan = planOf(
            "String merchantId; String getMerchantId() { return merchantId; }",
            field,
            symbol(
                "getMerchantId", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT,
                key = "method:class:com.acme.Payment#getMerchantId",
                accessor = AccessorEvidence(field.key, field.declaredName, "get", fieldKeyIsQualified = true),
                keyIsQualified = true,
            ),
        )

        val result = anonymize(plan, renaming(field.key to "merchantField"), LedgerSnapshot.EMPTY)

        assertEquals(
            "String merchantField1; String getMerchantField1() { return merchantField1; }",
            result.text,
        )
    }

    /**
     * **The accessor's own row says its name is derived**, so the dialog has something to say rather
     * than an editor that would do nothing: a stem typed there has nowhere to land, because the name
     * follows the field's.
     */
    @Test
    fun `an accessor row reports a derived name and its field's row is the one on offer`() {
        val field = symbol(
            "merchantId", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT,
            key = "field:class:com.acme.Payment#merchantId", keyIsQualified = true,
        )
        val plan = planOf(
            "String merchantId; String getMerchantId() { return merchantId; }",
            field,
            symbol(
                "getMerchantId", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT,
                key = "method:class:com.acme.Payment#getMerchantId",
                accessor = AccessorEvidence(field.key, field.declaredName, "get", fieldKeyIsQualified = true),
                keyIsQualified = true,
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            mapOf("merchantId" to Renaming.OFFERED, "getMerchantId" to Renaming.DERIVED),
            result.names.associate { it.original to it.renaming },
        )
    }

    /**
     * **A preserved row has no placeholder, so there is nothing on it to rename** — and the stem is
     * kept by whoever holds it, so unticking `Preserve` brings the renamed placeholder back rather
     * than a default-stemmed one.
     */
    @Test
    fun `a preserved row offers no rename and the stem survives being unpreserved`() {
        val payment = symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment")
        val plan = planOf("Payment p;", payment)
        val stems = mapOf(payment.key to "FilterType")

        val preserved = anonymize(
            plan,
            AnonymizationSettings(preservedSymbols = setOf(payment.key), renamedStems = stems),
            LedgerSnapshot.EMPTY,
        )
        val released = anonymize(plan, AnonymizationSettings(renamedStems = stems), LedgerSnapshot.EMPTY)

        assertEquals("Payment p;", preserved.text)
        assertNull(preserved.names.single().placeholder)
        assertEquals(Renaming.NONE, preserved.names.single().renaming)
        assertEquals("FilterType1 p;", released.text)
        assertEquals(Renaming.OFFERED, released.names.single().renaming)
    }

    /**
     * **A reply mentioning a custom stem decodes**, derived accessors included — the tables are
     * exact, so what a reversal recognises is what was minted rather than what a shape recogniser
     * would guess at.
     */
    @Test
    fun `a reply naming a renamed placeholder and its derived accessor round-trips`() {
        val field = symbol(
            "merchantId", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT,
            key = "field:class:com.acme.Payment#merchantId", keyIsQualified = true,
        )
        val plan = planOf(
            "class Payment { String merchantId; String getMerchantId() { return merchantId; } }",
            symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment", keyIsQualified = true),
            field,
            symbol(
                "getMerchantId", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT,
                key = "method:class:com.acme.Payment#getMerchantId",
                accessor = AccessorEvidence(field.key, field.declaredName, "get", fieldKeyIsQualified = true),
                keyIsQualified = true,
            ),
        )

        val result = anonymize(
            plan,
            renaming("class:com.acme.Payment" to "FilterType", field.key to "merchantField"),
            LedgerSnapshot.EMPTY,
        )
        val back = deanonymize(
            "FilterType1 caches merchantField2, so getMerchantField2 never reloads it.",
            Sidecar.EMPTY.recording(RecordedInvocation(Instant.now(), result.mapping)),
            LedgerSnapshot.EMPTY + result.delta,
        )

        assertEquals("Payment caches merchantId, so getMerchantId never reloads it.", back.text)
        assertEquals(emptyList<Unrestored>(), back.unrestored)
    }

    /**
     * **A renamed placeholder past the horizon is counted rather than passed over**, which is the
     * whole of what recording the stems buys: the tables have forgotten what `theFilter1` stood for,
     * and the reversal still knows it was ours and says so.
     *
     * The local is the case the gap was made of. Its key is unqualified, so no row carries it and
     * the sidecar is the only thing that ever knew it — and a reply older than the window meets
     * neither table. `EVICTED` rather than `FOREIGN` because the number is below the counter, which
     * is the same reading a default-stemmed placeholder gets.
     */
    @Test
    fun `a renamed placeholder the tables have forgotten is reported unrestored`() {
        val filter = symbol("filter", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:filter")

        val result = anonymize(planOf("int filter = 0;", filter), renaming(filter.key to "theFilter"), LedgerSnapshot.EMPTY)
        val committed = LedgerSnapshot.EMPTY + result.delta

        // Nothing in either table: the sidecar is empty, which is what past the horizon means, and
        // an unqualified key was never written into the mapping in the first place.
        val back = deanonymize("`theFilter1` is null before you validate.", Sidecar.EMPTY, committed)

        assertEquals("int theFilter1 = 0;", result.text)
        assertEquals(listOf(Unrestored("theFilter1", UnrestoredReason.EVICTED)), back.unrestored)
    }

    /**
     * **The stem is recorded even though the key is not**, and that asymmetry is the point rather
     * than an accident: an unqualified key has nothing stable to be filed under, so what goes into
     * the mapping is the *word*, which is all recognising it later needs.
     */
    @Test
    fun `an unqualified key's stem is recorded without the key being ledgered`() {
        val filter = symbol("filter", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:filter")

        val result = anonymize(planOf("int filter = 0;", filter), renaming(filter.key to "theFilter"), LedgerSnapshot.EMPTY)

        assertEquals(emptyMap<String, MintedName>(), result.delta.placeholders, "an unqualified key was ledgered")
        assertEquals(setOf("theFilter"), result.delta.mintedStems)
    }

    /**
     * **A renamed field's accessor is recognised too, and by the same route** — the derived name is a
     * word of its own, so it is written down as one.
     *
     * `merchantField1` under `get` renders `getMerchantField1`, whose stem is `getMerchantField` and
     * is in no set built from what the user typed. The default form needs no help — `getField1` is
     * read off the roles — so this is the half that only recording can reach, and the fixture keys
     * both symbols unqualified so that nothing is ledgered and the shape recogniser is the only
     * thing left answering.
     */
    @Test
    fun `a renamed field's derived accessor past the horizon is reported unrestored`() {
        val field = symbol("merchantId", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:merchantId")
        val plan = planOf(
            "String merchantId; String getMerchantId() { return merchantId; }",
            field,
            symbol(
                "getMerchantId", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT,
                key = "method:getMerchantId",
                accessor = AccessorEvidence(field.key, field.declaredName, "get"),
            ),
        )

        val result = anonymize(plan, renaming(field.key to "merchantField"), LedgerSnapshot.EMPTY)
        val committed = LedgerSnapshot.EMPTY + result.delta
        val back = deanonymize("getMerchantField1 never reloads it.", Sidecar.EMPTY, committed)

        assertEquals("String merchantField1; String getMerchantField1() { return merchantField1; }", result.text)
        assertEquals(setOf("merchantField", "getMerchantField"), committed.mintedStems)
        assertEquals(listOf(Unrestored("getMerchantField1", UnrestoredReason.EVICTED)), back.unrestored)
    }

    /**
     * **A field named by an earlier snippet still carries its accessor's word**, which is the case
     * the obvious guard gets wrong.
     *
     * The field is renamed and ledgered in the first invocation, where no accessor is in the snippet
     * at all — so `getMerchantField` is minted by nothing and recorded by nothing. The second
     * invocation reads the field's placeholder back out of the mapping rather than minting it, and
     * is nonetheless the first thing ever to write `getMerchantField1`. A check that asked only what
     * *this* invocation renamed would answer no and leave the word unrecognised for good.
     */
    @Test
    fun `an accessor over a field named by an earlier snippet records the derived stem`() {
        val field = symbol(
            "merchantId", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT,
            key = "field:class:com.acme.Payment#merchantId", keyIsQualified = true,
        )
        val accessor = symbol(
            "getMerchantId", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT,
            key = "method:getMerchantId",
            accessor = AccessorEvidence(field.key, field.declaredName, "get", fieldKeyIsQualified = true),
        )

        val first = anonymize(planOf("String merchantId;", field), renaming(field.key to "merchantField"), LedgerSnapshot.EMPTY)
        val second = anonymize(
            planOf("String getMerchantId() { return null; }", accessor),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY + first.delta,
        )
        val committed = LedgerSnapshot.EMPTY + first.delta + second.delta

        assertEquals("String merchantField1;", first.text)
        assertEquals("String getMerchantField1() { return null; }", second.text)
        assertEquals(setOf("merchantField", "getMerchantField"), committed.mintedStems)
        assertEquals(
            listOf(Unrestored("getMerchantField1", UnrestoredReason.EVICTED)),
            deanonymize("getMerchantField1 never reloads it.", Sidecar.EMPTY, committed).unrestored,
        )
    }

    /**
     * **A default-stemmed accessor records no stem**, because the roles already recognise it: the
     * derived form of a namespace this engine mints from is in the shape recogniser by construction,
     * and writing `getField` into the mapping as well would put a word there that names nothing the
     * user chose.
     */
    @Test
    fun `a default-stemmed accessor records no stem`() {
        val field = symbol("merchantId", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:merchantId")
        val plan = planOf(
            "String merchantId; String getMerchantId() { return merchantId; }",
            field,
            symbol(
                "getMerchantId", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT,
                key = "method:getMerchantId",
                accessor = AccessorEvidence(field.key, field.declaredName, "get"),
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val back = deanonymize("getField1 never reloads it.", Sidecar.EMPTY, LedgerSnapshot.EMPTY + result.delta)

        assertEquals("String field1; String getField1() { return field1; }", result.text)
        assertEquals(emptySet<String>(), result.delta.mintedStems)
        assertEquals(listOf(Unrestored("getField1", UnrestoredReason.EVICTED)), back.unrestored)
    }

    /**
     * **A stem nobody minted is not a placeholder**, which is the half of this that keeps the action
     * usable: `sha256`, `utf8` and `count2` are how a model writes ordinary prose, and a recogniser
     * wide enough to claim them would refuse most replies outright.
     *
     * Asserted against a mapping that *has* recorded a stem, so that what is being tested is the
     * recorded set rather than an empty one — a project that had renamed nothing would pass this
     * however wide the rule was.
     */
    @Test
    fun `a word the project never minted a stem for is left alone`() {
        val mapping = LedgerSnapshot(emptyMap(), nextNumber = 300, mintedStems = setOf("theFilter"))

        val back = deanonymize("Hash it with sha256, in utf8, and count2 is the loop bound.", Sidecar.EMPTY, mapping)

        assertEquals(emptyList<Unrestored>(), back.unrestored)
        assertFalse(back.found)
    }

    /**
     * **A recorded stem is a namespace like any other**, so a number this project never handed out
     * reads as [UnrestoredReason.FOREIGN] there exactly as it does under `Type`. The counter is what
     * answers *was it ours*, and recording the stem is only what let the question be asked at all.
     */
    @Test
    fun `a recorded stem above the counter is foreign rather than evicted`() {
        val mapping = LedgerSnapshot(emptyMap(), nextNumber = 5, mintedStems = setOf("theFilter"))

        val back = deanonymize("theFilter2 and theFilter9 and Type9", Sidecar.EMPTY, mapping)

        assertEquals(
            listOf(
                Unrestored("theFilter2", UnrestoredReason.EVICTED),
                Unrestored("theFilter9", UnrestoredReason.FOREIGN),
                Unrestored("Type9", UnrestoredReason.FOREIGN),
            ),
            back.unrestored,
        )
    }

    /**
     * **A stem the engine refused is never recorded**, because it was never minted with: an invalid
     * stem falls back to the default namespace, and recording the word the user typed would make the
     * reversal claim a namespace nothing in the output uses.
     */
    @Test
    fun `a stem the engine fell back from is not recorded`() {
        val payment = symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment")

        for (stem in listOf("Filter2", "9Filter", "my filter", "", "Unknown", "str")) {
            val result = anonymize(planOf("Payment p;", payment), renaming(payment.key to stem), LedgerSnapshot.EMPTY)

            assertEquals(emptySet<String>(), result.delta.mintedStems, "the stem `$stem` was recorded")
        }
    }

    /**
     * **The stems accumulate across invocations**, like the counter and unlike the rows: a project
     * that renamed something in March still recognises it in September, which is the span the gap
     * this closes was measured over.
     */
    @Test
    fun `stems from earlier invocations survive into the snapshot`() {
        val filter = symbol("filter", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:filter")
        val payment = symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment")

        val first = anonymize(planOf("int filter = 0;", filter), renaming(filter.key to "theFilter"), LedgerSnapshot.EMPTY)
        val second = anonymize(
            planOf("Payment p;", payment),
            renaming(payment.key to "FilterType"),
            LedgerSnapshot.EMPTY + first.delta,
        )

        assertEquals(setOf("theFilter", "FilterType"), (LedgerSnapshot.EMPTY + first.delta + second.delta).mintedStems)
    }

    /**
     * **A recorded stem is read by the reversal and never by the anonymiser**, which is what keeps
     * renaming a per-invocation input rather than a setting that was quietly persisted.
     *
     * The failure it forbids is a rename that outlives the dialog it was typed in: a later snippet
     * naming the same local under `theFilter` because the word was in the mapping would be a
     * reduction nobody re-confirmed, and every argument for the number staying is an argument for
     * this staying too.
     */
    @Test
    fun `a recorded stem does not rename anything on the next invocation`() {
        val filter = symbol("filter", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:filter")
        val plan = planOf("int filter = 0;", filter)

        val first = anonymize(plan, renaming(filter.key to "theFilter"), LedgerSnapshot.EMPTY)
        val committed = LedgerSnapshot.EMPTY + first.delta
        val second = anonymize(plan, AnonymizationSettings.DEFAULTS, committed)

        assertEquals(setOf("theFilter"), committed.mintedStems, "the stem was not recorded at all")
        assertEquals("int local2 = 0;", second.text)
        assertEquals(emptySet<String>(), second.delta.mintedStems, "a stem was re-recorded by an invocation that renamed nothing")
    }

    /**
     * The rule the dialog mirrors, stated once here: what a stem may be, and the two reasons it may
     * not be. Empty is not a rejection — it is the way back to the default stem.
     */
    @Test
    fun `a stem has to be a Java identifier that does not end in a digit`() {
        assertNull(stemRejection(""))
        assertNull(stemRejection("FilterType"))
        assertNull(stemRejection("_filter\$"))
        // Surrounding whitespace is not part of a stem, so it is not a reason to refuse one.
        assertNull(stemRejection("  FilterType  "))
        // The language's definition of an identifier rather than ASCII's — including the half of it
        // that does not fit in a `Char`, which is why this reads code points and not characters.
        assertNull(stemRejection("zahlungsprüfer"))
        assertNull(stemRejection("\uD835\uDC53ilter"))

        assertEquals(StemRejection.ENDS_WITH_A_DIGIT, stemRejection("Filter2"))
        assertEquals(StemRejection.NOT_AN_IDENTIFIER, stemRejection("9Filter"))
        assertEquals(StemRejection.NOT_AN_IDENTIFIER, stemRejection("my filter"))
        assertEquals(StemRejection.NOT_AN_IDENTIFIER, stemRejection("filter-type"))
    }

    /** The settings one invocation runs under when the only thing it does is rename. */
    private fun renaming(vararg stems: Pair<String, String>): AnonymizationSettings =
        AnonymizationSettings(renamedStems = stems.toMap())
}
