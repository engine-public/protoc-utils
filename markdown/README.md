# protoc-utils-markdown

CommonMark helpers shared by Engine's protoc plugins for handling proto doc comments.

```kotlin
implementation("com.engine:protoc-utils-markdown:VERSION")
```

## Reference links

Doc comments refer to other proto elements with CommonMark reference-link syntax: `[Widget]`, `[acme.v1.Widget]`, `[Widget.name]`, `[Status.STATUS_ACTIVE]`, `[WidgetService.GetWidget]`, or the full form `[display text][Widget]`.

- `ReferenceIndex<T>` indexes every message, field, enum, enum value, service, and RPC in a set of files.
  A plugin-supplied function maps each `ReferenceElement` to a target `T` (an href, a rendering decision, …); returning `null` leaves the element unindexed.
  Bare names resolve against the members of the comment's own descriptor before global short names, so a comment on `message Widget` can write `[name]`.
- `ReferenceLinkProcessor<T>` is a commonmark-java `LinkProcessor` that rewrites resolved references as links (`ReferenceRendering.Link`) or code spans (`ReferenceRendering.Code`).
  Inline links, images, code spans, and escaped brackets are left alone.
  Labels in the `overrides` map (parsed from repeated `referenceLink=<label>=<URL>` parameters with `ReferenceLinkProcessor.parseOverrides`) link outside the compile scope, e.g. `google.rpc.Status`.
- `ReferenceLinkMode` selects `NONE` (no resolution), `WARN` (log failures), or `FAIL_ON_INVALID` (log and collect failures).
  Collected `ReferenceLinkFailure`s are formatted for `CodeGeneratorResponse.error` with `ReferenceLinkFailure.summary`.

```kotlin
val index = ReferenceIndex(request.protoFiles) { element -> ReferenceRendering.Link("#${element.fqn}") }
val processor = ReferenceLinkProcessor(index, ReferenceLinkMode.FAIL_ON_INVALID) { it }
val parser = Parser.builder().linkProcessor(processor).build()

val document = processor.withScope("acme.v1.Widget") { parser.parse(comment.cleaned) }
val description = MarkdownText.render(document)

val failures = processor.drainFailures()
if (failures.isNotEmpty()) response.addError("my-plugin failed:\n" + ReferenceLinkFailure.summary(failures))
```

## Plain text

`MarkdownText.toPlainText` flattens a parsed comment to one line of plain text for fields that cannot hold Markdown (titles, summaries), and `MarkdownText.firstSentence` cuts it to the first sentence of the first paragraph.
Parse with the same `ReferenceLinkProcessor` first so bad references in those fields are still reported.
