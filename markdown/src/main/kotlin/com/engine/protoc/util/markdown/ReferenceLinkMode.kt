package com.engine.protoc.util.markdown

/**
 * How a plugin treats CommonMark reference links (`[Name]`, `[text][Name]`) in doc comments.
 */
public enum class ReferenceLinkMode {
    /** References are not resolved; the comment text is left as written, brackets included. */
    NONE,

    /** References are resolved; each unresolved or ambiguous reference is logged at `warn`. */
    WARN,

    /**
     * References are resolved; each unresolved or ambiguous reference is logged at `error` and
     * collected as a [ReferenceLinkFailure] so the plugin can fail the protoc run.
     */
    FAIL_ON_INVALID,
}
