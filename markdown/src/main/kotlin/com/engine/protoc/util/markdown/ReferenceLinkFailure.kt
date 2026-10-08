package com.engine.protoc.util.markdown

/**
 * A doc-comment reference link that did not resolve cleanly.
 */
public data class ReferenceLinkFailure(
    /** The proto file declaring the descriptor whose comment holds the reference. */
    val protoFile: String,
    /** The FQN of the descriptor whose comment holds the reference, or `""` for no scope. */
    val scopeFqn: String,
    /** The bracketed lookup label. */
    val label: String,
    /** Why the reference failed. */
    val reason: Reason,
) {
    /** Why a reference failed to resolve. */
    public sealed interface Reason {
        /** A human-readable description of the reason. */
        public val description: String

        /** Nothing in the compile scope matches the label. */
        public data object Unresolved : Reason {
            override val description: String = "no matching type, field, enum value, or RPC in compile scope"
        }

        /** More than one distinct target matches the label. */
        public data class Ambiguous(val candidates: List<String>) : Reason {
            override val description: String get() = "ambiguous; candidates: ${candidates.joinToString("; ")}"
        }
    }

    public companion object {
        /**
         * A multi-line summary of [failures], suitable for `CodeGeneratorResponse.error`: a count
         * line followed by one `  - file :: scope :: [label] — reason` bullet per failure.
         */
        public fun summary(failures: List<ReferenceLinkFailure>): String =
            buildString {
                append(failures.size)
                append(if (failures.size == 1) " reference-link failure under " else " reference-link failures under ")
                appendLine("resolveReferenceLinksMode=FAIL_ON_INVALID:")
                for (f in failures) {
                    append("  - ")
                    append(f.protoFile)
                    append(" :: ")
                    append(f.scopeFqn.ifEmpty { "(file scope)" })
                    append(" :: [")
                    append(f.label)
                    append("] — ")
                    append(f.reason.description)
                    appendLine()
                }
            }
    }
}
