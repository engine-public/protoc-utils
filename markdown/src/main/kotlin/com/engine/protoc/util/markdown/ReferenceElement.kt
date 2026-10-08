package com.engine.protoc.util.markdown

import com.engine.protoc.util.GeneratedMessageWrapper
import com.engine.protoc.util.file.FileDescriptorProtoWrapper

/**
 * A proto element that a doc-comment reference link (`[Name]`) can point at.
 *
 * Instances are created by [ReferenceIndex] while it walks the compile scope and are handed to the
 * plugin's target function, which decides where (or whether) the element is addressable in that
 * plugin's output.
 */
public class ReferenceElement internal constructor(
    /** What kind of proto element this is. */
    public val kind: Kind,
    /** The file that declares the element. */
    public val file: FileDescriptorProtoWrapper,
    /** The proto package of [file], without a leading dot; empty for the default package. */
    public val packageName: String,
    /**
     * The dotted name of the element relative to its package, e.g. `Outer.Inner`,
     * `Outer.Inner.field`, `Enum.VALUE`, or `Service.Method`.
     */
    public val localName: String,
    /** The containing element: the message of a field or nested type, the enum of a value, the service of an RPC. */
    public val parent: ReferenceElement?,
    /** The descriptor wrapper for the element. */
    public val descriptor: GeneratedMessageWrapper<*>,
) {
    /** The fully-qualified name of the element, without a leading dot. */
    public val fqn: String = if (packageName.isEmpty()) localName else "$packageName.$localName"

    /** The last segment of [localName]. */
    public val simpleName: String get() = localName.substringAfterLast('.')

    /** The kinds of element a reference link can resolve to. */
    public enum class Kind(
        /** The human-readable label used in log and failure messages. */
        public val label: String,
    ) {
        MESSAGE("message"),
        ENUM("enum"),
        SERVICE("service"),
        FIELD("field"),
        ENUM_VALUE("enum value"),
        RPC("rpc"),
    }

    override fun toString(): String = "${kind.label} $fqn"
}
