package com.snippetveil.core

/**
 * **One of the fixed shapes an engine assembles a rendered line out of** — the engine's own sentence,
 * written here with the fields it fills in named rather than guessed at.
 *
 * ### Why a template and not a parse
 *
 * A rendered node line is prose: `Index lookup on i using visits_by_owner (owner_id = 42)`. Every
 * name in it was appended with nothing around it, so **there is no delimiter to find a name's
 * boundary from** — which is the same fact that refuses MySQL's `TREE` output whole. What makes the
 * line readable at all inside a JSON document is that the document carries the node's **fields** as
 * well, each escaped, and the line was assembled from exactly those.
 *
 * So the line is never read. It is **rebuilt** — from the templates below and the node's own fields —
 * and compared. A line equal to what a template produced is a line whose every name is at a position
 * the template put it, and every other character in it belongs to the engine. A line equal to none of
 * them is a line this product cannot place, and it refuses.
 *
 * > **The line is cross-checked, never scanned.** No rule anywhere looks in a rendered line for
 * > something name-shaped.
 *
 * That is not a stylistic preference. A scan would have to decide which words of an English sentence
 * are the user's, on a line where `on`, `using` and `Sort` are spellings a table can legally have —
 * so a scanner is wrong in both directions at once: it replaces the engine's keyword when a table is
 * called `on`, and it walks past a table called `using` because it took it for the keyword.
 *
 * ### Exactly one template may match
 *
 * Two templates instantiating to one line would mean two readings of where the names in it are, and
 * picking between them is the judgment the exactly-one-vocabulary rule already refuses one layer up.
 * See [renderedBy].
 *
 * @param pattern the engine's sentence, with each field it fills in written `{label}` — the label
 *   being the field's own row in the inventory, so the part is read under exactly the treatment the
 *   field itself takes
 */
internal class PlanTemplate(pattern: String) {

    /** The pattern cut into the engine's own text and the fields it fills in, in order. */
    val parts: List<PlanTemplatePart> = partsOf(pattern)
}

/** One piece of a [PlanTemplate]: text the engine wrote itself, or a field it filled in. */
internal sealed class PlanTemplatePart {

    /** Text the engine wrote itself — a keyword, a separator, a bracket. It names nothing. */
    class Written(val text: String) : PlanTemplatePart()

    /** A field of the node the line was printed on, written into the line exactly as the field is. */
    class Field(val label: String) : PlanTemplatePart()
}

/**
 * **The one template [line] was assembled from, and `null` where it was assembled from none** — or
 * from more than one, which is the same answer for the reason [PlanTemplate] gives.
 *
 * @param values the node's own fields, by label, exactly as the document wrote them. A template
 *   naming a field the node does not carry cannot be the one that produced the line, so it is not a
 *   candidate rather than a failure.
 */
internal fun renderedBy(templates: List<PlanTemplate>, line: String, values: Map<String, String>): PlanTemplate? =
    templates.singleOrNull { instantiate(it, values) == line }

/**
 * The line [template] produces from [values], or `null` where the node does not carry every field the
 * template fills in.
 */
private fun instantiate(template: PlanTemplate, values: Map<String, String>): String? {
    val line = StringBuilder()
    for (part in template.parts) {
        when (part) {
            is PlanTemplatePart.Written -> line.append(part.text)
            is PlanTemplatePart.Field -> line.append(values[part.label] ?: return null)
        }
    }
    return line.toString()
}

/** The pattern cut on `{label}`, so that a template reads as the sentence the engine prints. */
private fun partsOf(pattern: String): List<PlanTemplatePart> {
    val parts = mutableListOf<PlanTemplatePart>()
    var at = 0
    while (at < pattern.length) {
        val open = pattern.indexOf('{', at)
        if (open < 0) {
            parts += PlanTemplatePart.Written(pattern.substring(at))
            return parts
        }
        val close = pattern.indexOf('}', open)
        require(close > open) { "a template field is opened and never closed: $pattern" }

        if (open > at) parts += PlanTemplatePart.Written(pattern.substring(at, open))
        parts += PlanTemplatePart.Field(pattern.substring(open + 1, close))
        at = close + 1
    }
    return parts
}
