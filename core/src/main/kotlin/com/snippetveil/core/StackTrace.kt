package com.snippetveil.core

/**
 * **Reads a plain Java exception trace, whole, or says it is not one.**
 *
 * The second pure function across the seam, beside [anonymize] and never in place of it: it takes
 * text and nothing else — no settings, no ledger — and hands back where the names and the texts are.
 * The plugin resolves the class names it returns, builds a [SnippetPlan] out of them, and calls
 * [anonymize]; that is two calls across the seam, and evidence crosses in one direction only.
 *
 * **The judgment *this text is not a stack trace* is made here and nowhere else.** A plugin that
 * decided it would be deciding by inspecting text, which is the thing this module exists to keep
 * testable in milliseconds with no IDE behind it.
 *
 * ### The input contract is a stack trace, whole
 *
 * | Line | Treatment |
 * |---|---|
 * | `Exception in thread "…" <FQN>: <msg>` | keyword verbatim; the thread name and the message are texts |
 * | `<FQN>: <msg>` | the same, without the thread |
 * | `\tat [<prefix>/]<FQN>.<method>(<file>[:<line>])` | the prefix is **dropped** |
 * | `Caused by: <FQN>: <msg>` | keyword verbatim, the rest as a header |
 * | `\tSuppressed: <FQN>: <msg>` | keyword verbatim, the rest as a header, nesting preserved |
 * | `\t... <N> more` | verbatim, the count included |
 * | `(Native Method)`, `(Unknown Source)` | verbatim: fixed JVM tokens with no file in them |
 *
 * `: <msg>` is optional, because `Throwable.toString` omits it for an exception with no message.
 *
 * **Any line outside that vocabulary refuses the whole paste**, and no line is ever passed through.
 * Emitting an unrecognised line verbatim would be free-text anonymization that resolves in the
 * leaking direction: one log-prefix line already carries `c.a.b.BillingService`. This deliberately
 * diverges from the IDE's own *Analyze Stack Trace*, which scans for `at …` anywhere — a missed line
 * costs nothing in a navigation aid, and in a privacy tool it is the whole failure.
 *
 * Beyond the per-line vocabulary, a trace **starts with its header** and **has at least one frame**:
 * a paste cut mid-stack is not the trace, and a single word on a clipboard is not one either.
 *
 * **The module or classloader prefix is dropped** — `java.base/`, `java.base@21.0.1/`, `app//`,
 * `com.acme.billing/`, `billing-worker//`. A named project module is usually the organisation's
 * package prefix verbatim, and the prefix is runtime deployment metadata rather than a program symbol,
 * so it has no placeholder and no ledger row. The cost is accepted: [StackTrace.text] is not a
 * byte-faithful copy of the paste, and every offset in the reading indexes into it rather than into
 * the paste.
 */
fun parseTrace(text: String): TraceReading {
    val out = StringBuilder()
    val exceptions = mutableListOf<TraceName>()
    val frames = mutableListOf<TraceFrame>()
    val texts = mutableListOf<TraceSpan>()

    val lines = text.split('\n')
    for ((index, raw) in lines.withIndex()) {
        // A line break after the last line is what a copy usually ends with, and it is admitted; an
        // empty line anywhere else is outside the vocabulary.
        if (index == lines.lastIndex && raw.isEmpty() && index > 0) break

        val line = raw.removeSuffix("\r")
        val ending = raw.substring(line.length) + if (index < lines.lastIndex) "\n" else ""
        val depth = line.takeWhile { it == '\t' }.length
        val rest = line.substring(depth)
        out.append(line, 0, depth)

        fun header(keyword: String): Boolean {
            val match = HEADER.matchEntire(rest.substring(keyword.length)) ?: return false
            out.append(keyword)
            readHeader(match, out, exceptions, texts)
            return true
        }

        val admitted = when {
            index == 0 && depth == 0 -> {
                val thread = THREAD.matchEntire(rest)
                if (thread != null) {
                    val name = thread.groups[1]!!
                    out.append(rest, 0, name.range.first)
                    texts += TraceSpan(out.length, out.length + name.value.length)
                    out.append(name.value).append("\" ")
                    val header = HEADER.matchEntire(thread.groups[2]!!.value)!!
                    readHeader(header, out, exceptions, texts)
                    true
                } else {
                    header("")
                }
            }

            index == 0 -> false
            rest.startsWith(CAUSED_BY) -> header(CAUSED_BY)
            depth == 0 -> false
            rest.startsWith(SUPPRESSED) -> header(SUPPRESSED)
            ELIDED.matches(rest) -> {
                out.append(rest)
                true
            }

            rest.startsWith(AT) -> readFrame(rest.substring(AT.length), out.append(AT), frames)
            else -> false
        }
        if (!admitted) return TraceReading.NotATrace
        out.append(ending)
    }

    if (frames.isEmpty()) return TraceReading.NotATrace
    return TraceReading.Read(StackTrace(out.toString(), exceptions, frames, texts))
}

/**
 * **What [parseTrace] made of the paste** — a trace, or the verdict that it is not one.
 *
 * The refusal carries no `String`, for the reason `PlanReading`'s does not: there is nowhere for a
 * line of the user's paste to travel from into a balloon.
 */
sealed class TraceReading {

    /** The trace, with every name and text located. */
    class Read(val trace: StackTrace) : TraceReading()

    /** A line outside the vocabulary, or no trace at all. The clipboard is not to be touched. */
    object NotATrace : TraceReading()
}

/**
 * A stack trace as the grammar read it.
 *
 * @param text the trace with every module and classloader prefix dropped — **the text every offset
 *   below indexes into**, and the text a [SnippetPlan] over this trace is built on
 * @param exceptions the exception class named by each header, in order: the first line's, every
 *   `Caused by:` and every `Suppressed:`
 * @param frames every `at` line, in order
 * @param texts the thread name and every exception message: text nothing owns, which is never
 *   resolved and never a symbol
 */
class StackTrace(
    val text: String,
    val exceptions: List<TraceName>,
    val frames: List<TraceFrame>,
    val texts: List<TraceSpan>,
)

/**
 * One `at` line.
 *
 * @param type the declaring class, as the JVM wrote it — a binary name, so an inner class carries
 *   its `$`
 * @param method the method, or `null` for `<init>` and `<clinit>` — the JVM's own names for a
 *   constructor and an initializer — and for a coroutine's `invokeSuspend`: names the platform
 *   fixed, which name nothing of anybody's
 * @param file the source file name, or `null` for `(Native Method)` and `(Unknown Source)`, which are
 *   fixed JVM tokens with no file in them. A line number is shape rather than domain and is not
 *   reported: it stays where it is.
 */
class TraceFrame(val type: TraceName, val method: TraceName?, val file: TraceName?)

/** A name the trace carries, where it sits in [StackTrace.text], and how it is written there. */
class TraceName(val start: Int, val end: Int, val text: String)

/** A stretch of [StackTrace.text] that is a text rather than a name — a thread name or a message. */
class TraceSpan(val start: Int, val end: Int)

/** Reads `<FQN>[: <msg>]`, appending it to [out] and recording the class and the message. */
private fun readHeader(match: MatchResult, out: StringBuilder, exceptions: MutableList<TraceName>, texts: MutableList<TraceSpan>) {
    val type = match.groups[1]!!.value
    exceptions += TraceName(out.length, out.length + type.length, type)
    out.append(type)
    val separator = (match.groups[2] ?: match.groups[4])?.value ?: return
    out.append(separator)
    val message = match.groups[3]?.value ?: return
    if (message.isNotEmpty()) texts += TraceSpan(out.length, out.length + message.length)
    out.append(message)
}

/** Reads what follows `at `, dropping the prefix, or answers `false` where it is not a frame. */
private fun readFrame(rest: String, out: StringBuilder, frames: MutableList<TraceFrame>): Boolean {
    val match = FRAME.matchEntire(rest) ?: return false
    val type = match.groups[1]!!.value
    val method = match.groups[2]!!.value
    val location = match.groups[3]!!.value

    val typeName = TraceName(out.length, out.length + type.length, type)
    out.append(type).append('.')
    val methodName = TraceName(out.length, out.length + method.length, method).takeUnless { method in LANGUAGE_FIXED_METHODS }
    out.append(method).append('(')

    val file = if (location in FIXED_LOCATIONS) {
        null
    } else {
        val name = location.substringBefore(':')
        TraceName(out.length, out.length + name.length, name)
    }
    out.append(location).append(')')

    frames += TraceFrame(typeName, methodName, file)
    return true
}

private const val CAUSED_BY = "Caused by: "
private const val SUPPRESSED = "Suppressed: "
private const val AT = "at "

private const val IDENTIFIER = """[\p{L}_$][\p{L}\p{N}_$]*"""
private const val QUALIFIED = """$IDENTIFIER(?:\.$IDENTIFIER)*"""

/** `<FQN>`, then optionally `: <msg>` — or a bare `:` where a clipboard trimmed an empty message. */
private val HEADER = Regex("""($QUALIFIED)(?:(: )(.*)|(:))?""")

/** Lazy on the thread name, so a quote inside it or inside the message cannot move the header. */
private val THREAD = Regex("""Exception in thread "(.*?)" ($QUALIFIED(?:: .*|:)?)""")

/**
 * `[<classloader>/][<module>[@<version>]/]<FQN>.<method>(<location>)`. The prefix is matched and not
 * captured: nothing reads it, and nothing of it reaches the output.
 */
private val FRAME = Regex(
    """(?:[^\s/()]+/(?:[^\s/()]*/)?)?($QUALIFIED)\.(<init>|<clinit>|$IDENTIFIER)\((Native Method|Unknown Source|[^\s():/\\]+(?::\d+)?)\)""",
)

private val ELIDED = Regex("""\.\.\. \d+ more""")

private val FIXED_LOCATIONS = setOf("Native Method", "Unknown Source")

/**
 * **The method names a frame reports as no method at all**, because the platform rather than the
 * developer fixed how they are spelled: the JVM's own `<init>` and `<clinit>`, and `invokeSuspend`,
 * the method every coroutine's generated state machine is compiled into. A generated name is silent
 * exactly when the *language* fixes its spelling — the test that keeps `it` and `component1` in a
 * snippet — and none of these names anything of anybody's.
 *
 * A generated name whose spelling a *declared* symbol fixes is not here and never could be:
 * `charge$suspendImpl` carries `charge`, and it is resolved as far as the platform accepts it.
 */
private val LANGUAGE_FIXED_METHODS = setOf("<init>", "<clinit>", "invokeSuspend")
