package com.snippetveil.core

/**
 * **One invocation's mapping, as a file — and one invocation's is all this can be handed.**
 *
 * That scoping is the feature rather than a limitation of the argument this takes. A dump of the
 * whole stored mapping would be the complete plaintext domain glossary of the codebase sitting in
 * `~/Downloads`, outside the location chosen so carefully to keep the mapping out of git and out of
 * cloud sync — and it would be an audit trail, which this product refused as an objective. What the
 * mapping *is* is a reversal key, and a reversal key is worth exporting for exactly one snippet.
 *
 * **Why it exists at all, given that:** the sidecar is bounded, and locals, parameters, type
 * parameters, anonymous-class members and **every string literal** live there and nowhere else — a
 * literal has no qualified key, so it is never written into the durable mapping. Once an invocation
 * falls past the horizon that half of its table is gone permanently, and no later reversal can get
 * it back. This file is the only way to keep a specific conversation decodable past that point.
 *
 * ### The format, and what each part of it is for
 *
 * **CSV**, because the file is opened in a spreadsheet as often as in an editor, and quoting is a
 * solved problem there rather than one this product would be inventing: a redacted literal's text is
 * arbitrary content the user wrote, commas and quotes and — in a text block — line breaks included.
 * See [quoted].
 *
 * **`Placeholder, Original, Kind`, placeholder first**, because the lookup direction is always
 * *reply -> source*: the reader is holding a word out of an AI's answer and wants the name it stood
 * for. Ordered the other way they would be scanning the one column they do not have. [MappedKind] is
 * the third because it is what makes the file reversible by hand — `method2` and `field2` read
 * differently in a sentence, and the kind is what says which one a placeholder was.
 *
 * **Rows in first-occurrence order**, which is the engine's own order and not a sort applied here:
 * it is the order the snippet introduces the names, so the file reads top to bottom against the text
 * it reverses. A sort by placeholder would look tidier and would agree with nothing.
 *
 * **Records end `\r\n`, per RFC 4180.** A text block's content carries raw newlines, and those sit
 * inside a quoted field where a conforming reader knows they are content — but a reader that is not
 * conforming, which includes every `grep` and every eye scanning the file, still gets the rows right
 * when the record separator is the one thing a field's own newline is not.
 *
 * **A preserved unresolved name is not a row.** It has no placeholder: it was emitted under its own
 * name, so a row for it would map a name to itself, and it would be the one line in this file that
 * printed a real name in the placeholder column.
 *
 * @return the file's whole content, header included — an invocation that minted no placeholders
 *   yields the header alone, and the export action is what decides that such a file is not offered
 */
fun AnonymizationResult.mappingCsv(): String = buildString {
    append(row("Placeholder", "Original", "Kind"))
    for (name in names) {
        val placeholder = name.placeholder ?: continue
        append(row(placeholder, name.original, name.kind.label))
    }
}

/** One record, terminated — see above for why the terminator is `\r\n`, and why it is on every row. */
private fun row(placeholder: String, original: String, kind: String): String =
    listOf(placeholder, original, kind).joinToString(",", postfix = "\r\n", transform = ::quoted)

/**
 * A field, **quoted where it has to be and not otherwise** — a comma, a quote or a line break in the
 * value, which between them are every character that could end a field or a record early.
 *
 * Minimal rather than quoting every field unconditionally, which is the simpler rule and the worse
 * one here: this file is read by people at least as often as by a parser, and a column of quotes
 * around `Type1` would cost legibility on every row to say something true of almost none of them.
 * The rule reads the value rather than the column, so nothing about it can drift as columns change.
 */
private fun quoted(field: String): String =
    if (field.any { it in ",\"\r\n" }) "\"" + field.replace("\"", "\"\"") + "\"" else field
