package com.snippetveil.core

/**
 * **One structured plan, read out of three spellings into one shape** — the document the field
 * inventory walks, and the seam that keeps *what a field is* from being asked three times.
 *
 * PostgreSQL's JSON, YAML and XML outputs carry **the same tree**. What differs is the punctuation,
 * so the punctuation is what these readers absorb: each produces the sequence of query mappings its
 * own spelling encodes, and everything downstream — the inventory, the closure, the treatments — is
 * written once.
 *
 * **Three readers and not one, because they are three vocabularies.** They are not layered on a
 * shared tokenizer and none of them falls back to another: a YAML document is not a JSON document,
 * and a reader that tried both in turn would be a reader that picks. See [PlanFormat] for the
 * predicates that decide which one runs, and for what happens when two of them say yes.
 *
 * **Every offset here is the plan's own.** A node carries slots into the text it was read from, so an
 * occurrence built from one needs no arithmetic — the same promise [PlanSlot] makes, kept all the way
 * up.
 */
internal sealed class PlanNode {

    /** The node's whole extent, punctuation included. */
    abstract val slot: PlanSlot
}

/**
 * **A mapping: labelled entries, in the order the engine printed them.**
 *
 * A list rather than a map, because **a label may repeat** — XML writes a list of plans as repeated
 * `<Plan>` elements under one `<Plans>` — and because a reader that deduplicated would decide which
 * of two printings to report, which is a judgment nothing here is allowed to make.
 */
internal class PlanMapping(val entries: List<PlanEntry>, override val slot: PlanSlot) : PlanNode()

/** **A sequence**: a JSON array, a YAML block sequence, or an XML element whose children are `Item`. */
internal class PlanSequence(val items: List<PlanNode>, override val slot: PlanSlot) : PlanNode()

/**
 * **A scalar**: a string, a number, a boolean.
 *
 * @param content where the value's own text is — inside the delimiters for a quoted scalar, and the
 *   whole token for a bare one. It is where a placeholder is written, for the reason
 *   [PlanOccurrence.nameStart] gives.
 */
internal class PlanScalar(override val slot: PlanSlot, val content: PlanSlot) : PlanNode()

/**
 * One entry of a [PlanMapping]: the label, where that label is **written**, and the value.
 *
 * @param names every range the label occupies in the text — one for JSON and YAML, and **two for
 *   XML**, which writes the name again in the closing tag. A rule that masks a key has to reach all
 *   of them, and a document that told it about only the opening tag would leave the name in the
 *   output while reporting that it had been replaced.
 */
internal class PlanEntry(val label: String, val names: List<PlanSlot>, val value: PlanNode)

/**
 * **PostgreSQL's `EXPLAIN (FORMAT JSON)`, read strictly** — and `null` for anything that is not
 * exactly that.
 *
 * Strict rather than tolerant everywhere: a document that does not parse whole is not a plan, and
 * guessing past a malformed region is how a reader comes to walk text it never understood. The
 * top level must be an **array of objects**, which is what the printer emits and the whole of what
 * this accepts.
 */
internal fun jsonQueriesIn(text: String): List<PlanMapping>? = JsonReader(text).document()

/**
 * **A JSON document whose top level is one object**, read by the same strict reader — and `null` for
 * anything that is not exactly that.
 *
 * PostgreSQL prints an **array** of queries because one `EXPLAIN` can describe several; MySQL prints
 * **one object**, because one `EXPLAIN` describes one statement. That is the whole of the difference
 * between the two entry points, and it is a difference in the printer rather than in the reader: the
 * strictness, the offsets and the refusal on anything that does not parse whole are the same.
 */
internal fun jsonObjectIn(text: String): PlanMapping? = JsonReader(text).objectDocument()

/**
 * **PostgreSQL's `EXPLAIN (FORMAT YAML)`, read strictly** — and `null` for anything else.
 *
 * It reads **the printer's YAML and not YAML**, which is stated rather than hidden: the accepted
 * grammar is a block sequence of block mappings, two-space indentation, one entry per line, and
 * scalars that are either double-quoted or bare. A general YAML parser would accept flow mappings,
 * anchors, tags and multi-document streams — none of which the printer emits, all of which would be
 * surface this container then has to defend.
 */
internal fun yamlQueriesIn(text: String): List<PlanMapping>? = YamlReader(text).document()

/**
 * **PostgreSQL's `EXPLAIN (FORMAT XML)`, read strictly** — and `null` for anything else.
 *
 * The root is `<explain>` and its children are `<Query>` elements, whose contents are the mappings
 * every other format prints at top level. An element whose children are all `<Item>` is a **list**,
 * which is the printer's own spelling for one.
 */
internal fun xmlQueriesIn(text: String): List<PlanMapping>? = XmlReader(text).document()

/**
 * **An XML document whose content is written in its *attributes*, read by the same strict reader** —
 * and `null` for anything that is not exactly one element named [root].
 *
 * PostgreSQL writes one element per field and one attribute in the whole document; SQL Server writes
 * the opposite — a `<ColumnReference Table="[Visits]" Column="OwnerId"/>` carries everything it has
 * to say in its attributes. **An attribute is therefore an entry like any other**, with the same
 * label, the same name range and the same scalar content an element would have had, so the field
 * inventory, the closure and every treatment are the ones already written rather than a second set
 * for a second spelling.
 *
 * Reading them is not a relaxation of the rule PostgreSQL's reader keeps. **Nothing is skipped
 * either way**: there, an attribute that is not the printer's one namespace declaration refuses;
 * here, every attribute becomes an entry and an entry the inventory does not hold refuses. The two
 * are the same fail-closed answer to *this document said something I have no row for*.
 */
internal fun attributedXmlDocumentIn(text: String, root: String): PlanMapping? =
    XmlReader(text, XmlSpelling.ATTRIBUTES).attributedDocument(root)

/**
 * **An attributed XML document whose elements may also carry character data**, read by the same
 * strict reader — and `null` for anything that is not exactly one element named [root].
 *
 * Oracle's monitoring report writes a bind's description in attributes and the **value it bound** in
 * the element's own content: `<bind name=":city" dty="1">Portland</bind>`. The two spellings on one
 * element is the whole of the difference from [attributedXmlDocumentIn], and it is absorbed the way
 * every other spelling here is — the character data becomes **an entry like any other**, under the
 * reserved label [XML_CHARACTER_DATA], so the inventory, the closure and the treatments are the ones
 * already written.
 *
 * **A value written in CDATA sections is one entry too.** A value carrying the sequence that would
 * close a section early is split by the writer across two of them, and the entry's content runs from
 * the opener of the first to the closer of the last — the split included, inside one range. A mask
 * over that range therefore emits one well-formed section rather than a document this product
 * assembled.
 */
internal fun contentXmlDocumentIn(text: String, root: String): PlanMapping? =
    XmlReader(text, XmlSpelling.ATTRIBUTES_AND_DATA).attributedDocument(root)

/**
 * **How a label is spelled as an XML element name** — the printer's own transform, applied here so
 * that one inventory serves all three formats.
 *
 * `ExplainXMLTag` writes every character of a property name that is not `A-Za-z0-9-_.` as `-`, so
 * `Node Type` is `<Node-Type>` and `I/O Read Time` is `<I-O-Read-Time>`. The mapping is **not**
 * invertible — `Full-sort Groups` and a hypothetical `Full sort Groups` collide — so the inventory is
 * carried the other way: every known label is put through this, and a tag that is no label's image
 * refuses like any other unknown field.
 */
internal fun xmlTagOf(label: String): String =
    label.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }.joinToString("")

/** The JSON document, read a character at a time. See [jsonQueriesIn]. */
private class JsonReader(private val source: String) {

    private var at = 0

    fun document(): List<PlanMapping>? {
        val root = value() as? PlanSequence ?: return null
        skipSpace()
        if (at != source.length) return null
        return root.items.map { it as? PlanMapping ?: return null }
    }

    /** The document whose top level is one object, read to the last character. See [jsonObjectIn]. */
    fun objectDocument(): PlanMapping? {
        val root = value() as? PlanMapping ?: return null
        skipSpace()
        if (at != source.length) return null
        return root
    }

    private fun value(): PlanNode? {
        skipSpace()
        return when (peek()) {
            '{' -> mapping()
            '[' -> sequence()
            '"' -> string()
            null -> null
            else -> bare()
        }
    }

    private fun mapping(): PlanNode? {
        val start = at
        at++
        val entries = mutableListOf<PlanEntry>()
        skipSpace()
        if (peek() == '}') {
            at++
            return PlanMapping(entries, slot(start, at))
        }
        while (true) {
            skipSpace()
            val key = string() ?: return null
            skipSpace()
            if (peek() != ':') return null
            at++
            val value = value() ?: return null
            entries += PlanEntry(key.content.written, listOf(key.content), value)
            skipSpace()
            when (peek()) {
                ',' -> at++
                '}' -> {
                    at++
                    return PlanMapping(entries, slot(start, at))
                }

                else -> return null
            }
        }
    }

    private fun sequence(): PlanNode? {
        val start = at
        at++
        val items = mutableListOf<PlanNode>()
        skipSpace()
        if (peek() == ']') {
            at++
            return PlanSequence(items, slot(start, at))
        }
        while (true) {
            items += value() ?: return null
            skipSpace()
            when (peek()) {
                ',' -> at++
                ']' -> {
                    at++
                    return PlanSequence(items, slot(start, at))
                }

                else -> return null
            }
        }
    }

    /** A quoted string, whose content is what lies between the delimiters — escapes and all. */
    private fun string(): PlanScalar? {
        if (peek() != '"') return null
        val start = at
        at++
        val contentStart = at
        while (at < source.length) {
            when (source[at]) {
                '\\' -> at += 2
                '"' -> {
                    val contentEnd = at
                    at++
                    return PlanScalar(slot(start, at), slot(contentStart, contentEnd))
                }

                else -> at++
            }
        }
        return null
    }

    /** A number, `true`, `false` or `null` — everything the printer writes without delimiters. */
    private fun bare(): PlanScalar? {
        val start = at
        while (at < source.length && (source[at].isLetterOrDigit() || source[at] in "+-.")) at++
        if (at == start) return null
        return PlanScalar(slot(start, at), slot(start, at))
    }

    private fun peek(): Char? = source.getOrNull(at)

    private fun skipSpace() {
        while (at < source.length && source[at].isWhitespace()) at++
    }

    private fun slot(start: Int, end: Int) = PlanSlot(source, start, end)
}

/** The YAML document, read a line at a time. See [yamlQueriesIn]. */
private class YamlReader(private val source: String) {

    /**
     * The document's lines, blank ones dropped.
     *
     * [linesIn] rather than `lineSequence()`, and the difference is not stylistic: `lineSequence()`
     * hides which terminator it split on, so **a line's length stops saying where the next line
     * begins** the moment the input arrives with two-character endings. Every offset below is the
     * input's own, and that is what keeps them so.
     */
    private val lines: List<YamlLine> = linesIn(source)
        .map { YamlLine(it.text, it.start) }
        .filterNot { it.isBlank }

    fun document(): List<PlanMapping>? {
        if (lines.isEmpty()) return null
        if (lines.first().indent != 0 || !lines.first().opensAnItem) return null

        val read = sequenceFrom(0, 0) ?: return null
        if (read.next != lines.size) return null
        return read.node.items.map { it as? PlanMapping ?: return null }
    }

    /** The block sequence at [indent], every item of which is a mapping opened by its own `- `. */
    private fun sequenceFrom(from: Int, indent: Int): Read<PlanSequence>? {
        val items = mutableListOf<PlanNode>()
        var at = from
        while (at < lines.size && lines[at].indent == indent && lines[at].opensAnItem) {
            val item = mappingFrom(at, indent + DASH.length, opensAnItem = true) ?: return null
            items += item.node
            at = item.next
        }
        if (items.isEmpty()) return null
        return Read(PlanSequence(items, spanOf(from, at)), at)
    }

    /**
     * The block mapping at [indent] — and, where [opensAnItem], the one whose first entry is written
     * on the `- ` line rather than under it.
     */
    private fun mappingFrom(from: Int, indent: Int, opensAnItem: Boolean): Read<PlanMapping>? {
        val entries = mutableListOf<PlanEntry>()
        var at = from
        var first = opensAnItem
        while (at < lines.size) {
            val line = lines[at]
            if (!first && (line.indent != indent || line.opensAnItem)) break
            if (first && line.indent + DASH.length != indent) break

            val entry = entryOn(line, at, indent) ?: return null
            entries += entry.node
            at = entry.next
            first = false
        }
        if (entries.isEmpty()) return null
        return Read(PlanMapping(entries, spanOf(from, at)), at)
    }

    /** One `Label: value` entry, whose value is on the line or in the block indented under it. */
    private fun entryOn(line: YamlLine, at: Int, indent: Int): Read<PlanEntry>? {
        val body = line.text.substring(line.bodyAt)
        val colon = body.indexOf(':').takeIf { it > 0 } ?: return null
        val label = body.substring(0, colon)
        val names = listOf(PlanSlot(source, line.start + line.bodyAt, line.start + line.bodyAt + colon))

        var valueAt = line.bodyAt + colon + 1
        while (valueAt < line.text.length && line.text[valueAt] == ' ') valueAt++

        if (valueAt < line.text.length) {
            val scalar = scalarIn(line, valueAt) ?: return null
            return Read(PlanEntry(label, names, scalar), at + 1)
        }

        // Nothing after the colon: the value is the block indented under this line — or, where no
        // line is indented under it, the empty list the printer writes for `Triggers: ` with no
        // trigger. **An empty block is a sequence and never a mapping**, because a mapping with no
        // entries would be a container the closure never looked inside.
        val next = lines.getOrNull(at + 1)
        if (next == null || next.indent <= indent) {
            return Read(PlanEntry(label, names, PlanSequence(emptyList(), spanOf(at + 1, at + 1))), at + 1)
        }

        val block = if (next.opensAnItem) {
            sequenceFrom(at + 1, next.indent)
        } else {
            mappingFrom(at + 1, next.indent, opensAnItem = false)
        } ?: return null
        return Read(PlanEntry(label, names, block.node), block.next)
    }

    /** The scalar written from [valueAt] to the end of [line] — quoted, or bare. */
    private fun scalarIn(line: YamlLine, valueAt: Int): PlanScalar? {
        val start = line.start + valueAt
        val end = line.start + line.text.length
        if (line.text[valueAt] != '"') return PlanScalar(PlanSlot(source, start, end), PlanSlot(source, start, end))

        var at = valueAt + 1
        while (at < line.text.length) {
            when (line.text[at]) {
                '\\' -> at += 2
                '"' -> {
                    // The printer writes nothing after a quoted scalar, so a trailing character is a
                    // document this reader does not understand rather than one to read past.
                    if (at != line.text.length - 1) return null
                    return PlanScalar(
                        PlanSlot(source, start, line.start + at + 1),
                        PlanSlot(source, start + 1, line.start + at),
                    )
                }

                else -> at++
            }
        }
        return null
    }

    private fun spanOf(from: Int, to: Int): PlanSlot {
        val start = lines.getOrNull(from)?.start ?: source.length
        val last = lines.getOrNull(to - 1)
        val end = if (last == null) start else last.start + last.text.length
        return PlanSlot(source, minOf(start, end), end)
    }

    private class Read<out T>(val node: T, val next: Int)

    private class YamlLine(val text: String, val start: Int) {

        val indent: Int = text.indexOfFirst { it != ' ' }.takeIf { it >= 0 } ?: text.length

        val isBlank: Boolean = text.isBlank()

        /** Whether this line opens a sequence item — `- `, at its own indentation. */
        val opensAnItem: Boolean = text.startsWith(DASH, indent)

        /** Where the entry's label begins: past the indentation, and past the `- ` of an item. */
        val bodyAt: Int = if (opensAnItem) indent + DASH.length else indent
    }
}

/** What opens a YAML sequence item, spaces included — the printer's own two-character indent step. */
private const val DASH = "- "

/** The other half of a Windows line ending, which every reader here treats as a terminator. */
internal const val RETURN = "\r"

/**
 * **How one plan writer spells its content in XML** — the whole of what this reader has to be told
 * about the document in front of it.
 *
 * One enumeration rather than a flag per difference, because the differences are not independent:
 * there is no writer here that puts its content in character data *and* keeps PostgreSQL's
 * one-element-per-field spelling, and a pair of booleans would have said there was. Each row is one
 * writer's spelling, and the reader asks which it is rather than asking two questions whose
 * combinations it would then have to rule out.
 */
private enum class XmlSpelling {

    /** **One element per field**, and the one namespace declaration PostgreSQL's printer writes. */
    ELEMENTS,

    /** **The content is in the attributes** — SQL Server's showplan writer. */
    ATTRIBUTES,

    /**
     * **The content is in the attributes and in the element's own character data** — Oracle's
     * monitoring writer, which describes a bind in attributes and puts the value it bound inside the
     * element. See [contentXmlDocumentIn].
     */
    ATTRIBUTES_AND_DATA,
}

/**
 * The XML document, read a character at a time. See [xmlQueriesIn] and [attributedXmlDocumentIn].
 *
 * @param spelling how this document's writer spells its content. See [XmlSpelling].
 */
private class XmlReader(
    private val source: String,
    private val spelling: XmlSpelling = XmlSpelling.ELEMENTS,
) {

    private var at = 0

    fun document(): List<PlanMapping>? {
        val root = rootElement() ?: return null
        if (root.label != EXPLAIN) return null

        val queries = root.value as? PlanMapping ?: return null
        if (queries.entries.any { it.label != QUERY }) return null
        return queries.entries.map { it.value as? PlanMapping ?: return null }
    }

    /** The document whose whole content is one element named [root]. See [attributedXmlDocumentIn]. */
    fun attributedDocument(root: String): PlanMapping? {
        val element = rootElement() ?: return null
        if (element.label != root) return null
        return element.value as? PlanMapping
    }

    /** The one element the document is, read to the last character of the input. */
    private fun rootElement(): PlanEntry? {
        skipSpace()
        val root = element() ?: return null
        skipSpace()
        if (at != source.length) return null
        return root
    }

    /** One element, opened at [at], with its children read as a mapping, a sequence or a scalar. */
    private fun element(): PlanEntry? {
        if (peek() != '<') return null
        val start = at
        at++
        val nameStart = at
        while (at < source.length && isNameCharacter(source[at], prefixed = false)) at++
        val label = source.substring(nameStart, at)
        if (label.isEmpty()) return null
        val opening = PlanSlot(source, nameStart, at)

        val written = attributes() ?: return null
        if (source.startsWith("/>", at)) {
            at += 2
            val empty = PlanSlot(source, at, at)
            val closed = if (written.isEmpty()) {
                PlanScalar(PlanSlot(source, start, at), empty)
            } else {
                PlanMapping(written, PlanSlot(source, start, at))
            }
            return PlanEntry(label, listOf(opening), closed)
        }
        if (peek() != '>') return null
        at++

        val contentStart = at

        // **Character data, where this writer writes it beside attributes.** It is asked first and
        // asked once: an element whose content is elements answers `null` here, with the scan left
        // exactly where it was, and is read below as any other element is.
        if (spelling == XmlSpelling.ATTRIBUTES_AND_DATA) {
            val content = characterDataFrom(contentStart)
            if (content != null) return dataElement(start, label, opening, contentStart, written, content)
        }

        val children = mutableListOf<PlanEntry>()
        while (true) {
            skipSpace()
            if (source.startsWith("</", at)) break
            if (peek() != '<') {
                // **An element carrying both attributes and character data refuses.** Its content
                // would be one scalar and its attributes entries of a mapping, and a node is one or
                // the other — so a document this reader can only half-place is not placed at all.
                if (children.isNotEmpty() || written.isNotEmpty()) return null
                return textElement(start, label, opening, contentStart)
            }
            children += element() ?: return null
        }

        val contentEnd = at
        at += 2
        val closingStart = at
        while (at < source.length && source[at] != '>') at++
        if (source.substring(closingStart, at) != label) return null
        at++

        val closing = PlanSlot(source, closingStart, closingStart + label.length)
        val span = PlanSlot(source, start, at)
        val entries = written + children
        val value = if (written.isEmpty() && children.isNotEmpty() && children.all { it.label == ITEM }) {
            PlanSequence(children.map { it.value }, PlanSlot(source, contentStart, contentEnd))
        } else if (entries.isNotEmpty()) {
            PlanMapping(entries, PlanSlot(source, contentStart, contentEnd))
        } else {
            PlanScalar(span, PlanSlot(source, contentStart, contentEnd))
        }
        return PlanEntry(label, listOf(opening, closing), value)
    }

    /**
     * **The character data of the element whose content begins at [from]** — the range its value
     * occupies — or `null` where the content is elements rather than data, with the scan put back
     * where it started so the element can be read the other way.
     *
     * Two spellings and no third:
     *
     *  - **Plain content** runs to the closing tag, and the range is the whole of it.
     *  - **CDATA sections** run from the opener of the first to the closer of the last. A writer
     *    that had to split a value — because it carried the sequence that would have closed a
     *    section early — therefore hands back **one range with the split inside it**, so a mask over
     *    it replaces the whole value and leaves one well-formed section behind.
     *
     * A section that never closes is a document this reader does not understand, which is the answer
     * everything here gives one.
     */
    private fun characterDataFrom(from: Int): PlanSlot? {
        val mark = at
        if (!source.startsWith(CDATA_OPEN, at)) {
            while (at < source.length && source[at] != '<') at++
            if (source.startsWith("</", at)) return PlanSlot(source, from, at)
            at = mark
            return null
        }

        var contentStart = -1
        var contentEnd = -1
        while (source.startsWith(CDATA_OPEN, at)) {
            val open = at + CDATA_OPEN.length
            val close = source.indexOf(CDATA_CLOSE, open).takeIf { it >= 0 } ?: break
            if (contentStart < 0) contentStart = open
            contentEnd = close
            at = close + CDATA_CLOSE.length
        }
        if (contentStart < 0 || !source.startsWith("</", at)) {
            at = mark
            return null
        }
        return PlanSlot(source, contentStart, contentEnd)
    }

    /**
     * One element whose content is character data, with the closing tag read and the entry built —
     * a **scalar** where the element carries nothing else, and a **mapping** where it also carries
     * attributes, the data entering it as one entry like any other.
     */
    private fun dataElement(
        start: Int,
        label: String,
        opening: PlanSlot,
        contentStart: Int,
        written: List<PlanEntry>,
        content: PlanSlot,
    ): PlanEntry? {
        val contentEnd = at
        at += 2
        val closingStart = at
        while (at < source.length && source[at] != '>') at++
        if (source.substring(closingStart, at) != label) return null
        at++

        val closing = PlanSlot(source, closingStart, closingStart + label.length)
        val span = PlanSlot(source, contentStart, contentEnd)
        val data = PlanScalar(span, content)
        val value = if (written.isEmpty()) {
            PlanScalar(PlanSlot(source, start, at), content)
        } else {
            PlanMapping(written + PlanEntry(XML_CHARACTER_DATA, emptyList(), data), span)
        }
        return PlanEntry(label, listOf(opening, closing), value)
    }

    /** An element holding character data, whose content runs to its own closing tag. */
    private fun textElement(start: Int, label: String, opening: PlanSlot, contentStart: Int): PlanEntry? {
        var contentEnd = contentStart
        while (contentEnd < source.length && source[contentEnd] != '<') contentEnd++
        at = contentEnd
        if (!source.startsWith("</", at)) return null
        at += 2
        val closingStart = at
        while (at < source.length && source[at] != '>') at++
        if (source.substring(closingStart, at) != label) return null
        at++
        return PlanEntry(
            label,
            listOf(opening, PlanSlot(source, closingStart, closingStart + label.length)),
            PlanScalar(PlanSlot(source, start, at), PlanSlot(source, contentStart, contentEnd)),
        )
    }

    /**
     * The attributes of an element — **entries of it** where this document writes its content in
     * them, and otherwise the one namespace declaration PostgreSQL's printer writes, skipped.
     *
     * `null` where an attribute is written in a shape this reader does not understand, which is the
     * answer everything else here gives a document it cannot parse whole.
     */
    private fun attributes(): List<PlanEntry>? {
        skipSpace()
        if (spelling == XmlSpelling.ELEMENTS) return if (skipNamespace()) emptyList() else null

        val entries = mutableListOf<PlanEntry>()
        while (at < source.length && source[at] != '>' && !source.startsWith("/>", at)) {
            val nameStart = at
            while (at < source.length && isNameCharacter(source[at], prefixed = true)) at++
            if (at == nameStart) return null
            val name = PlanSlot(source, nameStart, at)

            skipSpace()
            if (peek() != '=') return null
            at++
            skipSpace()

            // **Double quotes only.** The printer writes them, and a reader that also took single
            // quotes would be reading a spelling nothing here has a capture of.
            if (peek() != '"') return null
            at++
            val contentStart = at
            while (at < source.length && source[at] != '"') at++
            if (at == source.length) return null
            val contentEnd = at
            at++

            entries += PlanEntry(
                name.written,
                listOf(name),
                PlanScalar(PlanSlot(source, nameStart, at), PlanSlot(source, contentStart, contentEnd)),
            )
            skipSpace()
        }
        return entries
    }

    /**
     * The one attribute PostgreSQL's printer writes in a whole document, skipped — and `false` for
     * anything else, which is a document this reader has not seen rather than one to read past.
     */
    private fun skipNamespace(): Boolean {
        while (at < source.length && source[at] != '>' && !source.startsWith("/>", at)) {
            if (!source.startsWith(NAMESPACE, at)) return false
            at += NAMESPACE.length
            skipSpace()
        }
        return true
    }

    private fun peek(): Char? = source.getOrNull(at)

    private fun skipSpace() {
        while (at < source.length && source[at].isWhitespace()) at++
    }
}

/**
 * What an element or attribute name may be spelled with.
 *
 * @param prefixed whether a `:` may appear in it, which is true of **attribute names only** — the
 *   prefixed namespace declarations SQL Server's writer puts on its root. No element name this
 *   product reads carries one, so nothing widens for the sake of something only attributes do.
 */
private fun isNameCharacter(character: Char, prefixed: Boolean): Boolean =
    character.isLetterOrDigit() || character == '-' || character == '_' || character == '.' ||
        (prefixed && character == ':')

/** The root element of PostgreSQL's XML plan. */
private const val EXPLAIN = "explain"

/** The element one query's plan sits in. */
private const val QUERY = "Query"

/** The element the XML printer writes a list's members as. */
private const val ITEM = "Item"

/** What opens a CDATA section, and what closes one. See [XmlReader.characterDataFrom]. */
private const val CDATA_OPEN = "<![CDATA["

private const val CDATA_CLOSE = "]]>"

/**
 * **The label an element's own character data is entered under**, where the writer puts a value in
 * the element that describes it.
 *
 * It is spelled with a character **no XML name may contain**, so the row can never collide with an
 * attribute the same element carries — the inventory says *this element's content* and cannot be
 * made to say something else by a document that names an attribute after it.
 */
internal const val XML_CHARACTER_DATA = "#text"

/** The one attribute the XML printer writes, byte-exact — see [XmlReader.skipAttributes]. */
private const val NAMESPACE = """xmlns="http://www.postgresql.org/2009/explain""""
