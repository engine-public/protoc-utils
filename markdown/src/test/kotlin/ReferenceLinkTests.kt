import com.engine.protoc.util.extensions.wrap
import com.engine.protoc.util.file.FileDescriptorProtoWrapper
import com.engine.protoc.util.markdown.MarkdownText
import com.engine.protoc.util.markdown.ReferenceElement
import com.engine.protoc.util.markdown.ReferenceIndex
import com.engine.protoc.util.markdown.ReferenceLinkFailure
import com.engine.protoc.util.markdown.ReferenceLinkMode
import com.engine.protoc.util.markdown.ReferenceLinkProcessor
import com.engine.protoc.util.markdown.ReferenceRendering
import com.engine.protoc.util.markdown.UnresolvedReferenceRendering
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto
import com.google.protobuf.compiler.PluginProtos
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.commonmark.parser.Parser

private fun field(
    name: String,
    typeName: String? = null,
): FieldDescriptorProto =
    FieldDescriptorProto.newBuilder().setName(name).setNumber(1).apply {
        if (typeName != null) {
            setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
            setTypeName(typeName)
        } else {
            setType(FieldDescriptorProto.Type.TYPE_STRING)
        }
    }.build()

private fun enum(
    name: String,
    vararg values: String,
): EnumDescriptorProto =
    EnumDescriptorProto.newBuilder().setName(name).apply {
        values.forEachIndexed { i, v -> addValue(EnumValueDescriptorProto.newBuilder().setName(v).setNumber(i)) }
    }.build()

private val widgetFile: FileDescriptorProto =
    FileDescriptorProto.newBuilder()
        .setName("acme/widget.proto")
        .setPackage("acme.v1")
        .addMessageType(
            DescriptorProto.newBuilder()
                .setName("Widget")
                .addField(field("name"))
                .addField(field("gadget", ".acme.v1.Gadget"))
                .addNestedType(DescriptorProto.newBuilder().setName("Part").addField(field("label")))
                .addEnumType(enum("Color", "COLOR_UNKNOWN", "COLOR_RED")),
        )
        .addMessageType(DescriptorProto.newBuilder().setName("Gadget").addField(field("size")))
        .addMessageType(DescriptorProto.newBuilder().setName("GetWidgetRequest").addField(field("widget_id")))
        .addEnumType(enum("Status", "STATUS_UNKNOWN", "STATUS_ACTIVE"))
        .addService(
            ServiceDescriptorProto.newBuilder()
                .setName("WidgetService")
                .addMethod(
                    MethodDescriptorProto.newBuilder()
                        .setName("GetWidget")
                        .setInputType(".acme.v1.GetWidgetRequest")
                        .setOutputType(".acme.v1.Widget"),
                ),
        )
        .build()

// A second package that also declares a `Gadget`, to exercise ambiguity.
private val otherFile: FileDescriptorProto =
    FileDescriptorProto.newBuilder()
        .setName("other/gadget.proto")
        .setPackage("other.v1")
        .addMessageType(DescriptorProto.newBuilder().setName("Gadget"))
        .build()

private fun files(vararg protos: FileDescriptorProto): List<FileDescriptorProtoWrapper> =
    PluginProtos.CodeGeneratorRequest.newBuilder()
        .addAllProtoFile(protos.toList())
        .apply { protos.forEach { addFileToGenerate(it.name) } }
        .build()
        .wrap()
        .protoFiles

/** An index whose target is the element's own FQN, so every distinct element is a distinct target. */
private fun fqnIndex(vararg protos: FileDescriptorProto): ReferenceIndex<String> = ReferenceIndex(files(*protos)) { it.fqn }

private fun ReferenceIndex.Outcome<String>.target(): String? = (this as? ReferenceIndex.Outcome.Resolved)?.target

class ReferenceIndexTests :
    FunSpec({
        val index = fqnIndex(widgetFile)

        test("qualified labels resolve by FQN, package-relative name, and Type.member") {
            index.resolve("acme.v1.Widget", "").target() shouldBe "acme.v1.Widget"
            index.resolve("Widget.Part", "").target() shouldBe "acme.v1.Widget.Part"
            index.resolve("Widget.name", "").target() shouldBe "acme.v1.Widget.name"
            index.resolve("Part.label", "").target() shouldBe "acme.v1.Widget.Part.label"
            index.resolve("Widget.Part.label", "").target() shouldBe "acme.v1.Widget.Part.label"
            index.resolve("Status.STATUS_ACTIVE", "").target() shouldBe "acme.v1.Status.STATUS_ACTIVE"
            index.resolve("WidgetService.GetWidget", "").target() shouldBe "acme.v1.WidgetService.GetWidget"
        }

        test("bare labels resolve to global short names of types and enum values") {
            index.resolve("Widget", "").target() shouldBe "acme.v1.Widget"
            index.resolve("WidgetService", "").target() shouldBe "acme.v1.WidgetService"
            index.resolve("STATUS_ACTIVE", "").target() shouldBe "acme.v1.Status.STATUS_ACTIVE"
        }

        test("bare labels resolve to members of the comment's scope") {
            index.resolve("name", "acme.v1.Widget").target() shouldBe "acme.v1.Widget.name"
            index.resolve("Part", "acme.v1.Widget").target() shouldBe "acme.v1.Widget.Part"
            index.resolve("gadget", "acme.v1.Widget.name").target() shouldBe "acme.v1.Widget.gadget"
            index.resolve("size", "acme.v1.Widget.gadget").target() shouldBe "acme.v1.Gadget.size"
            index.resolve("widget_id", "acme.v1.WidgetService.GetWidget").target() shouldBe "acme.v1.GetWidgetRequest.widget_id"
            index.resolve("GetWidget", "acme.v1.WidgetService").target() shouldBe "acme.v1.WidgetService.GetWidget"
            index.resolve("COLOR_RED", "acme.v1.Widget.Color.COLOR_UNKNOWN").target() shouldBe "acme.v1.Widget.Color.COLOR_RED"
        }

        test("bare member names don't resolve without a scope") {
            index.resolve("name", "") shouldBe ReferenceIndex.Outcome.Unresolved
            index.resolve("Nope", "") shouldBe ReferenceIndex.Outcome.Unresolved
        }

        test("a short name shared by two types is ambiguous unless the scope disambiguates it") {
            val both = fqnIndex(widgetFile, otherFile)
            val outcome = both.resolve("Gadget", "").shouldBeInstanceOf<ReferenceIndex.Outcome.Ambiguous<String>>()
            outcome.candidates shouldBe listOf("message acme.v1.Gadget → acme.v1.Gadget", "message other.v1.Gadget → other.v1.Gadget")
            both.resolve("Gadget", "acme.v1.Widget.gadget").target() shouldBe "acme.v1.Gadget"
            both.resolve("other.v1.Gadget", "").target() shouldBe "other.v1.Gadget"
        }

        test("elements whose target is null are not indexed") {
            val noFields = ReferenceIndex(files(widgetFile)) { it.takeIf { e -> e.kind != ReferenceElement.Kind.FIELD }?.fqn }
            noFields.resolve("Widget.name", "") shouldBe ReferenceIndex.Outcome.Unresolved
            noFields.resolve("Widget", "").target() shouldBe "acme.v1.Widget"
        }

        test("equal targets are not ambiguous") {
            val byFile = ReferenceIndex(files(widgetFile, otherFile)) { "anchor" }
            byFile.resolve("Gadget", "").target() shouldBe "anchor"
        }

        test("fileOf names the declaring file") {
            index.fileOf("acme.v1.Widget.name") shouldBe "acme/widget.proto"
        }
    })

class ReferenceLinkProcessorTests :
    FunSpec({
        val index = ReferenceIndex(files(widgetFile, otherFile)) { element ->
            when (element.kind) {
                ReferenceElement.Kind.ENUM_VALUE -> ReferenceRendering.Code
                else -> ReferenceRendering.Link("#${element.fqn}")
            }
        }

        fun processor(
            mode: ReferenceLinkMode = ReferenceLinkMode.FAIL_ON_INVALID,
            unresolved: UnresolvedReferenceRendering = UnresolvedReferenceRendering.CODE,
        ) = ReferenceLinkProcessor(
            index = index,
            mode = mode,
            overrides = mapOf("google.rpc.Status" to "https://example.com/status"),
            unresolvedRendering = unresolved,
        ) { it }

        fun ReferenceLinkProcessor<ReferenceRendering>.render(
            text: String,
            scope: String = "",
        ): String {
            val parser = Parser.builder().linkProcessor(this).build()
            return MarkdownText.render(withScope(scope) { parser.parse(text) })
        }

        test("resolved references become links and code spans") {
            val p = processor()
            p.render("See [Widget] and [the request][GetWidgetRequest].") shouldBe
                "See [Widget](#acme.v1.Widget) and [the request](#acme.v1.GetWidgetRequest)."
            p.render("Defaults to [STATUS_ACTIVE].") shouldBe "Defaults to `STATUS_ACTIVE`."
            p.render("Bare [name] here.", "acme.v1.Widget") shouldBe "Bare [name](#acme.v1.Widget.name) here."
            p.drainFailures().shouldBeEmpty()
            p.touched shouldBe true
        }

        test("overrides win over the index and link outside the compile scope") {
            processor().render("In [Status.details][google.rpc.Status].") shouldBe
                "In [Status.details](https://example.com/status)."
        }

        test("inline links, code spans, and escaped brackets are untouched") {
            val p = processor()
            p.render("A [link](https://x.test), `[Widget]`, and \\[Widget\\].") shouldBe
                "A [link](https://x.test), `[Widget]`, and \\[Widget\\]."
            p.touched shouldBe false
            p.drainFailures().shouldBeEmpty()
        }

        test("unresolved and ambiguous references are collected under FAIL_ON_INVALID") {
            val p = processor()
            p.render("See [Missing] and [Gadget].", "acme.v1.Widget.Part") shouldBe
                "See `Missing` and [Gadget](#other.v1.Gadget)."
            val failures = p.drainFailures()
            failures shouldHaveSize 2
            failures[0] shouldBe ReferenceLinkFailure("acme/widget.proto", "acme.v1.Widget.Part", "Missing", ReferenceLinkFailure.Reason.Unresolved)
            failures[1].reason.shouldBeInstanceOf<ReferenceLinkFailure.Reason.Ambiguous>()
            p.drainFailures().shouldBeEmpty()
            ReferenceLinkFailure.summary(failures.take(1)) shouldBe
                """
                |1 reference-link failure under resolveReferenceLinksMode=FAIL_ON_INVALID:
                |  - acme/widget.proto :: acme.v1.Widget.Part :: [Missing] — no matching type, field, enum value, or RPC in compile scope
                |
                """.trimMargin()
        }

        test("WARN resolves but collects nothing; LITERAL keeps unresolved brackets") {
            val p = processor(ReferenceLinkMode.WARN, UnresolvedReferenceRendering.LITERAL)
            // The renderer escapes the brackets it leaves as literal text.
            p.render("See [Missing] and [Widget].") shouldBe "See \\[Missing\\] and [Widget](#acme.v1.Widget)."
            p.drainFailures().shouldBeEmpty()
        }

        test("NONE leaves every reference as written") {
            val p = processor(ReferenceLinkMode.NONE)
            p.render("See [Missing] and [Widget].") shouldBe "See \\[Missing\\] and \\[Widget\\]."
            p.touched shouldBe false
        }

        test("parseOverrides keeps order and rejects malformed entries") {
            ReferenceLinkProcessor.parseOverrides(listOf("a=b", "c.d=https://x/y=z")) shouldBe mapOf("a" to "b", "c.d" to "https://x/y=z")
            ReferenceLinkProcessor.parseOverrides(null) shouldBe emptyMap()
            shouldThrow<IllegalArgumentException> { ReferenceLinkProcessor.parseOverrides(listOf("novalue=")) }
            shouldThrow<IllegalArgumentException> { ReferenceLinkProcessor.parseOverrides(listOf("=nolabel")) }
        }
    })

class MarkdownTextTests :
    FunSpec({
        val parser = Parser.builder().build()

        test("toPlainText drops Markdown syntax") {
            MarkdownText.toPlainText(
                parser.parse("Returns a *new* [Widget](#w) for `widget_id`,\nor ![an icon](i.png) **never**."),
            ) shouldBe "Returns a new Widget for widget_id, or an icon never."
        }

        test("firstSentence keeps only the first sentence of the first paragraph") {
            MarkdownText.firstSentence(parser.parse("Gets a [Widget], e.g. v1.2 widgets. More text.\n\nSecond paragraph.")) shouldBe
                "Gets a [Widget], e.g. v1.2 widgets."
            MarkdownText.firstSentence(parser.parse("- a list\n- of items")) shouldBe "a list of items"
            MarkdownText.firstSentence(parser.parse("")) shouldBe ""
        }

        test("render trims trailing whitespace") {
            MarkdownText.render(parser.parse("*line one*   \n\n\n")) shouldBe "*line one*"
        }
    })
