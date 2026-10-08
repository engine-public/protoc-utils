package com.engine.protoc.util.markdown

import com.engine.protoc.util.enums.EnumDescriptorProtoWrapper
import com.engine.protoc.util.file.FileDescriptorProtoWrapper
import com.engine.protoc.util.message.DescriptorProtoWrapper
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ReferenceIndex::class.java)

/**
 * An index of every message, field, enum, enum value, service, and RPC declared in [files], keyed
 * by every name a doc-comment reference link may use for it.
 *
 * Each element is mapped to a plugin-specific target of type [T] by [targetFor] (for example an
 * href, or a description of how to render the reference).  An element for which [targetFor]
 * returns `null` is not indexed, so references to it resolve as [Outcome.Unresolved].  Two keys
 * are ambiguous only when they map to targets that are not equal, so plugins decide what counts
 * as "the same place" through [T]'s `equals`.
 *
 * Resolution order for a label under a comment's scope (see [resolve]):
 *
 *  1. A label containing a dot is looked up only in the qualified index, which holds every FQN,
 *     every dotted name relative to the package (`Outer.Inner`), and every `Type.member` form.
 *  2. Otherwise, the bare-scope candidates of the comment's descriptor: the fields and nested types
 *     of a message, the values of an enum, the RPCs of a service, the sibling fields of a field
 *     (plus the fields of the field's message type and that type itself), the sibling values of an
 *     enum value, and the sibling RPCs of an RPC (plus the fields of its input and output types
 *     and those types themselves).
 *  3. The global short names of messages, enums, and services.
 *  4. The global short names of enum values.
 *
 * The first resolved step wins; otherwise the first ambiguous step; otherwise unresolved.
 */
public class ReferenceIndex<T : Any>(
    files: Iterable<FileDescriptorProtoWrapper>,
    private val describe: (T) -> String = { it.toString() },
    private val targetFor: (ReferenceElement) -> T?,
) {
    /** The result of resolving a reference label. */
    public sealed interface Outcome<out T : Any> {
        /** The label names exactly one target. */
        public data class Resolved<T : Any>(val target: T) : Outcome<T>

        /**
         * The label names more than one distinct target.  [target] is the last one registered, and
         * [candidates] describes each of them as `owner → target`.
         */
        public data class Ambiguous<T : Any>(val target: T, val candidates: List<String>) : Outcome<T>

        /** The label names nothing in the index. */
        public data object Unresolved : Outcome<Nothing>
    }

    private val qualified = AmbiguityAwareMap("qualified")
    private val globalShortTypes = AmbiguityAwareMap("global short type")
    private val globalShortEnumValues = AmbiguityAwareMap("global short enum value")

    private val fieldsByMessage = mutableMapOf<String, MutableMap<String, T>>()
    private val nestedTypesByMessage = mutableMapOf<String, MutableMap<String, T>>()
    private val valuesByEnum = mutableMapOf<String, MutableMap<String, T>>()
    private val rpcsByService = mutableMapOf<String, MutableMap<String, T>>()
    private val parentMessageOfField = mutableMapOf<String, String>()
    private val parentEnumOfValue = mutableMapOf<String, String>()
    private val parentServiceOfRpc = mutableMapOf<String, String>()
    private val fieldTypeOf = mutableMapOf<String, String>()
    private val rpcInputOf = mutableMapOf<String, String>()
    private val rpcOutputOf = mutableMapOf<String, String>()
    private val fileByFqn = mutableMapOf<String, String>()
    private val typeTargetByFqn = mutableMapOf<String, T>()

    init {
        for (file in files) indexFile(file)
    }

    /** The name of the proto file that declares the element [fqn], or `null` when it isn't indexed. */
    public fun fileOf(fqn: String): String? = fileByFqn[fqn]

    /**
     * Resolve [label] for a comment attached to the descriptor [scopeFqn] (without a leading dot),
     * or `""` when the comment has no descriptor scope.
     */
    public fun resolve(
        label: String,
        scopeFqn: String,
    ): Outcome<T> {
        if (label.isEmpty()) return Outcome.Unresolved
        if ('.' in label) return qualified.lookup(label)
        var ambiguous: Outcome.Ambiguous<T>? = null
        for (outcome in sequenceOf(
            { bareScopeOutcome(label, scopeFqn) },
            { globalShortTypes.lookup(label) },
            { globalShortEnumValues.lookup(label) },
        )) {
            when (val it = outcome()) {
                is Outcome.Resolved -> return it
                is Outcome.Ambiguous -> if (ambiguous == null) ambiguous = it
                else -> Unit
            }
        }
        return ambiguous ?: Outcome.Unresolved
    }

    private fun indexFile(file: FileDescriptorProtoWrapper) {
        val pkg = file.`package`?.value.orEmpty()
        val protoFile = file.name ?: "(unnamed)"
        for (m in file.messageTypes) indexMessage(m, file, pkg, protoFile, null)
        for (e in file.enumTypes) indexEnum(e, file, pkg, protoFile, null)
        for (s in file.services) {
            val sname = s.name?.value ?: continue
            val service = ReferenceElement(ReferenceElement.Kind.SERVICE, file, pkg, sname, null, s)
            val sFqn = service.fqn
            fileByFqn[sFqn] = protoFile
            targetFor(service)?.let { target ->
                globalShortTypes.put(sname, target, service.toString())
                qualified.put(sname, target, service.toString())
                qualified.put(sFqn, target, service.toString())
            }
            val rpcMap = rpcsByService.getOrPut(sFqn) { mutableMapOf() }
            for (method in s.methods) {
                val mname = method.name?.value ?: continue
                val rpc = ReferenceElement(ReferenceElement.Kind.RPC, file, pkg, "$sname.$mname", service, method)
                val mFqn = rpc.fqn
                fileByFqn[mFqn] = protoFile
                parentServiceOfRpc[mFqn] = sFqn
                method.inputType?.value?.removePrefix(".")?.let { rpcInputOf[mFqn] = it }
                method.outputType?.value?.removePrefix(".")?.let { rpcOutputOf[mFqn] = it }
                val target = targetFor(rpc) ?: continue
                qualified.put("$sname.$mname", target, rpc.toString())
                qualified.put(mFqn, target, rpc.toString())
                rpcMap[mname] = target
            }
        }
    }

    private fun indexMessage(
        msg: DescriptorProtoWrapper,
        file: FileDescriptorProtoWrapper,
        pkg: String,
        protoFile: String,
        parent: ReferenceElement?,
    ) {
        if (msg.options?.mapEntry?.value == true) return
        val short = msg.name?.value ?: return
        val dotted = parent?.let { "${it.localName}.$short" } ?: short
        val message = ReferenceElement(ReferenceElement.Kind.MESSAGE, file, pkg, dotted, parent, msg)
        val fqn = message.fqn
        fileByFqn[fqn] = protoFile
        targetFor(message)?.let { target ->
            typeTargetByFqn[fqn] = target
            globalShortTypes.put(short, target, message.toString())
            qualified.put(dotted, target, message.toString())
            qualified.put(fqn, target, message.toString())
            if (parent != null) nestedTypesByMessage.getOrPut(parent.fqn) { mutableMapOf() }[short] = target
        }

        val fieldMap = fieldsByMessage.getOrPut(fqn) { mutableMapOf() }
        for (f in msg.fields) {
            val fname = f.name?.value ?: continue
            val field = ReferenceElement(ReferenceElement.Kind.FIELD, file, pkg, "$dotted.$fname", message, f)
            val fFqn = field.fqn
            fileByFqn[fFqn] = protoFile
            parentMessageOfField[fFqn] = fqn
            f.typeName?.value?.removePrefix(".")?.takeIf { it.isNotEmpty() }?.let { fieldTypeOf[fFqn] = it }
            val target = targetFor(field) ?: continue
            qualified.put("$short.$fname", target, field.toString())
            if (parent != null) qualified.put("$dotted.$fname", target, field.toString())
            qualified.put(fFqn, target, field.toString())
            fieldMap[fname] = target
        }

        for (n in msg.nestedTypes) indexMessage(n, file, pkg, protoFile, message)
        for (e in msg.enumTypes) indexEnum(e, file, pkg, protoFile, message)
    }

    private fun indexEnum(
        enum: EnumDescriptorProtoWrapper,
        file: FileDescriptorProtoWrapper,
        pkg: String,
        protoFile: String,
        parent: ReferenceElement?,
    ) {
        val short = enum.name?.value ?: return
        val dotted = parent?.let { "${it.localName}.$short" } ?: short
        val element = ReferenceElement(ReferenceElement.Kind.ENUM, file, pkg, dotted, parent, enum)
        val fqn = element.fqn
        fileByFqn[fqn] = protoFile
        targetFor(element)?.let { target ->
            typeTargetByFqn[fqn] = target
            globalShortTypes.put(short, target, element.toString())
            qualified.put(dotted, target, element.toString())
            qualified.put(fqn, target, element.toString())
            if (parent != null) nestedTypesByMessage.getOrPut(parent.fqn) { mutableMapOf() }[short] = target
        }

        val valueMap = valuesByEnum.getOrPut(fqn) { mutableMapOf() }
        for (v in enum.values) {
            val vname = v.name?.value ?: continue
            val value = ReferenceElement(ReferenceElement.Kind.ENUM_VALUE, file, pkg, "$dotted.$vname", element, v)
            val vFqn = value.fqn
            fileByFqn[vFqn] = protoFile
            parentEnumOfValue[vFqn] = fqn
            val target = targetFor(value) ?: continue
            qualified.put("$short.$vname", target, value.toString())
            if (parent != null) qualified.put("$dotted.$vname", target, value.toString())
            qualified.put(vFqn, target, value.toString())
            valueMap[vname] = target
            globalShortEnumValues.put(vname, target, value.toString())
        }
    }

    private fun bareScopeOutcome(
        label: String,
        scopeFqn: String,
    ): Outcome<T>? {
        if (scopeFqn.isEmpty()) return null
        val sources = bareScopeSources(scopeFqn) ?: return null
        val hits = sources.mapNotNull { (sourceLabel, map) -> map[label]?.let { sourceLabel to it } }
        return when {
            hits.isEmpty() -> null
            hits.distinctBy { it.second }.size == 1 -> Outcome.Resolved(hits[0].second)
            else -> Outcome.Ambiguous(hits[0].second, hits.map { "${it.first} → ${describe(it.second)}" })
        }
    }

    private fun bareScopeSources(scopeFqn: String): List<Pair<String, Map<String, T>>>? {
        fieldsByMessage[scopeFqn]?.let { fields ->
            return buildList {
                add("field of $scopeFqn" to fields)
                nestedTypesByMessage[scopeFqn]?.let { add("nested type of $scopeFqn" to it) }
            }
        }
        valuesByEnum[scopeFqn]?.let { return listOf("value of $scopeFqn" to it) }
        rpcsByService[scopeFqn]?.let { return listOf("rpc of $scopeFqn" to it) }
        parentMessageOfField[scopeFqn]?.let { parentMessage ->
            return buildList {
                fieldsByMessage[parentMessage]?.let { add("sibling field in $parentMessage" to it) }
                fieldTypeOf[scopeFqn]?.let { type ->
                    fieldsByMessage[type]?.let { add("field of referenced type $type" to it) }
                    typeSource("declared type of $scopeFqn", type)?.let(::add)
                }
            }
        }
        parentEnumOfValue[scopeFqn]?.let { parentEnum ->
            valuesByEnum[parentEnum]?.let { return listOf("sibling value in $parentEnum" to it) }
        }
        parentServiceOfRpc[scopeFqn]?.let { parentService ->
            return buildList {
                rpcsByService[parentService]?.let { add("sibling rpc in $parentService" to it) }
                rpcInputOf[scopeFqn]?.let { input ->
                    fieldsByMessage[input]?.let { add("field of input $input" to it) }
                    typeSource("input type of $scopeFqn", input)?.let(::add)
                }
                rpcOutputOf[scopeFqn]?.let { output ->
                    fieldsByMessage[output]?.let { add("field of output $output" to it) }
                    typeSource("output type of $scopeFqn", output)?.let(::add)
                }
            }
        }
        return null
    }

    /** A bare-scope source holding [typeFqn]'s target under its short name, or `null` when the type isn't indexed. */
    private fun typeSource(
        sourceLabel: String,
        typeFqn: String,
    ): Pair<String, Map<String, T>>? = typeTargetByFqn[typeFqn]?.let { sourceLabel to mapOf(typeFqn.substringAfterLast('.') to it) }

    /**
     * A `key → target` map that remembers every owner registering each key, so a key claimed by two
     * distinct targets resolves as [Outcome.Ambiguous] instead of silently keeping one.
     */
    private inner class AmbiguityAwareMap(private val mapLabel: String) {
        private val targetByKey = mutableMapOf<String, T>()
        private val candidatesByKey = mutableMapOf<String, MutableList<Pair<String, T>>>()

        fun put(
            key: String,
            target: T,
            owner: String,
        ) {
            val candidates = candidatesByKey.getOrPut(key) { mutableListOf() }
            if (candidates.any { it.first == owner && it.second == target }) return
            candidates += owner to target
            val prior = targetByKey[key]
            if (prior != null && prior != target && log.isTraceEnabled) {
                log.trace(
                    "reference-link key '{}' in {} is ambiguous — '{}' collides with a prior entry resolving to '{}'; " +
                        "keeping the latest.  Use a qualified form to disambiguate in comments.",
                    key,
                    mapLabel,
                    owner,
                    describe(prior),
                )
            }
            targetByKey[key] = target
        }

        fun lookup(key: String): Outcome<T> {
            val target = targetByKey[key] ?: return Outcome.Unresolved
            val candidates = candidatesByKey[key].orEmpty()
            return if (candidates.map { it.second }.distinct().size <= 1) {
                Outcome.Resolved(target)
            } else {
                Outcome.Ambiguous(target, candidates.map { "${it.first} → ${describe(it.second)}" })
            }
        }
    }
}
