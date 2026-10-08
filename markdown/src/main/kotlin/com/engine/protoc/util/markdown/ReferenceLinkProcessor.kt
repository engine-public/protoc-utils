package com.engine.protoc.util.markdown

import org.commonmark.node.Code
import org.commonmark.node.Link
import org.commonmark.parser.InlineParserContext
import org.commonmark.parser.beta.LinkInfo
import org.commonmark.parser.beta.LinkProcessor
import org.commonmark.parser.beta.LinkResult
import org.commonmark.parser.beta.Scanner
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ReferenceLinkProcessor::class.java)

/**
 * How a resolved reference is written into the parsed document.
 */
public sealed interface ReferenceRendering {
    /** Wrap the reference text in a link to [destination]. */
    public data class Link(val destination: String) : ReferenceRendering

    /** Replace the reference with an inline code span of its text, for elements with no addressable target. */
    public data object Code : ReferenceRendering
}

/**
 * How an unresolved reference is written into the parsed document.
 */
public enum class UnresolvedReferenceRendering {
    /** Leave the brackets as literal text, so the failure is visible in the output. */
    LITERAL,

    /** Replace the reference with an inline code span of its text. */
    CODE,
}

/**
 * A CommonMark [LinkProcessor] that resolves proto reference links in doc comments against a
 * [ReferenceIndex].
 *
 * Shortcut (`[label]`), collapsed (`[label][]`), and full (`[text][label]`) reference links are
 * resolved; in the full form `label` is the lookup key and `text` stays as the visible text.
 * Inline links (`[text](url)`), images, code spans, and escaped brackets are left to the core
 * CommonMark parser.  Labels in [overrides] resolve to their URL before the index is consulted,
 * which is how references to types outside the compile scope (e.g. `[google.rpc.Status]`) link
 * to external documentation.
 *
 * Resolution is bound to the descriptor whose comment is being parsed: wrap each parse in
 * [withScope].  Under [ReferenceLinkMode.NONE] the processor leaves every link to the core parser.
 */
public class ReferenceLinkProcessor<T : Any>(
    private val index: ReferenceIndex<T>,
    private val mode: ReferenceLinkMode,
    private val overrides: Map<String, String> = emptyMap(),
    private val unresolvedRendering: UnresolvedReferenceRendering = UnresolvedReferenceRendering.LITERAL,
    private val render: (T) -> ReferenceRendering,
) : LinkProcessor {
    private var currentScope: String = ""
    private val failures = mutableListOf<ReferenceLinkFailure>()

    /** True when the processor rewrote at least one reference since the last [withScope] began. */
    public var touched: Boolean = false
        private set

    /**
     * Run [block] (typically a `parser.parse(...)` call) with references resolving relative to the
     * descriptor [scopeFqn] (without a leading dot), or `""` for no scope.  Resets [touched].
     */
    public fun <R> withScope(
        scopeFqn: String,
        block: () -> R,
    ): R {
        currentScope = scopeFqn
        touched = false
        return try {
            block()
        } finally {
            currentScope = ""
        }
    }

    /** Return and clear the failures recorded under [ReferenceLinkMode.FAIL_ON_INVALID]. */
    public fun drainFailures(): List<ReferenceLinkFailure> {
        val out = failures.toList()
        failures.clear()
        return out
    }

    override fun process(
        linkInfo: LinkInfo,
        scanner: Scanner,
        context: InlineParserContext,
    ): LinkResult? {
        if (mode == ReferenceLinkMode.NONE) return LinkResult.none()
        if (linkInfo.destination() != null || linkInfo.marker() != null) return LinkResult.none()
        val rawLabel = linkInfo.label()
        val text = linkInfo.text()
        // shortcut `[text]` → label null; collapsed `[text][]` → label "" — both use text as the key.
        val key = if (rawLabel.isNullOrEmpty()) text else rawLabel
        if (key.isEmpty()) return LinkResult.none()
        val scopeFqn = currentScope

        overrides[key]?.let { url ->
            touched = true
            return LinkResult.wrapTextIn(Link(url, null), scanner.position())
        }

        val outcome = index.resolve(key, scopeFqn)
        if (log.isTraceEnabled) {
            log.trace(
                "reference-link [{}] identified in {} :: {} — {}",
                key,
                index.fileOf(scopeFqn) ?: "(unknown)",
                scopeFqn.ifEmpty { "(file scope)" },
                outcome,
            )
        }
        return when (outcome) {
            is ReferenceIndex.Outcome.Resolved -> rendered(render(outcome.target), text, scanner)

            is ReferenceIndex.Outcome.Ambiguous -> {
                reportFailure(scopeFqn, key, ReferenceLinkFailure.Reason.Ambiguous(outcome.candidates))
                rendered(render(outcome.target), text, scanner)
            }

            ReferenceIndex.Outcome.Unresolved -> {
                reportFailure(scopeFqn, key, ReferenceLinkFailure.Reason.Unresolved)
                when (unresolvedRendering) {
                    UnresolvedReferenceRendering.LITERAL -> LinkResult.none()
                    UnresolvedReferenceRendering.CODE -> rendered(ReferenceRendering.Code, text, scanner)
                }
            }
        }
    }

    private fun rendered(
        rendering: ReferenceRendering,
        text: String,
        scanner: Scanner,
    ): LinkResult {
        touched = true
        return when (rendering) {
            is ReferenceRendering.Link -> LinkResult.wrapTextIn(Link(rendering.destination, null), scanner.position())
            ReferenceRendering.Code -> LinkResult.replaceWith(Code(text), scanner.position())
        }
    }

    private fun reportFailure(
        scopeFqn: String,
        label: String,
        reason: ReferenceLinkFailure.Reason,
    ) {
        val protoFile = index.fileOf(scopeFqn) ?: "(unknown)"
        val scopeDescription = scopeFqn.ifEmpty { "(file scope)" }
        when (mode) {
            ReferenceLinkMode.NONE -> Unit

            ReferenceLinkMode.WARN ->
                log.warn("reference-link [{}] in {} :: {} — {}", label, protoFile, scopeDescription, reason.description)

            ReferenceLinkMode.FAIL_ON_INVALID -> {
                log.error("reference-link [{}] in {} :: {} — {}", label, protoFile, scopeDescription, reason.description)
                failures += ReferenceLinkFailure(protoFile, scopeFqn, label, reason)
            }
        }
    }

    public companion object {
        /**
         * Parse repeated `<label>=<URL>` plugin parameter entries into an ordered override map.
         *
         * @throws IllegalArgumentException when an entry has no `=`, an empty label, or an empty URL.
         */
        public fun parseOverrides(entries: List<String>?): Map<String, String> {
            if (entries.isNullOrEmpty()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (entry in entries) {
                val idx = entry.indexOf('=')
                require(idx > 0 && idx < entry.length - 1) {
                    "referenceLink entry must be of the form <label>=<URL>, got `$entry`"
                }
                map[entry.substring(0, idx)] = entry.substring(idx + 1)
            }
            return map
        }
    }
}
