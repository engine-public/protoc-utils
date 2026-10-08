package com.engine.protoc.util.markdown

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.renderer.markdown.MarkdownRenderer
import org.commonmark.renderer.text.TextContentRenderer
import java.text.BreakIterator
import java.util.Locale

/**
 * Conversions of parsed doc-comment Markdown into the strings a plugin emits.
 */
public object MarkdownText {
    private val defaultMarkdownRenderer: MarkdownRenderer = MarkdownRenderer.builder().build()
    private val textRenderer: TextContentRenderer = TextContentRenderer.builder().stripNewlines(true).build()

    /**
     * Render [node] back to CommonMark, with trailing whitespace removed from every line and from
     * the end of the result.  A hard line break written as trailing spaces therefore renders as a
     * soft break; cleaned doc comments have already lost trailing spaces, so this only normalizes.
     */
    public fun render(
        node: Node,
        renderer: MarkdownRenderer = defaultMarkdownRenderer,
    ): String =
        renderer.render(node)
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trimEnd()

    /**
     * Flatten [node] to a single line of plain text for fields that cannot hold Markdown (titles,
     * summaries).  Links and images become their visible text, code spans lose their backticks,
     * emphasis markers are dropped, and line breaks become spaces.
     *
     * Mutates [node]; pass a freshly parsed document.
     */
    public fun toPlainText(node: Node): String {
        val replacements = mutableListOf<Node>()
        node.accept(
            object : AbstractVisitor() {
                override fun visit(link: Link) {
                    replacements += link
                    visitChildren(link)
                }

                override fun visit(image: Image) {
                    replacements += image
                    visitChildren(image)
                }

                override fun visit(code: Code) {
                    replacements += code
                }
            },
        )
        for (n in replacements) {
            if (n is Code) {
                n.insertBefore(Text(n.literal))
            } else {
                while (true) {
                    val child = n.firstChild ?: break
                    n.insertBefore(child)
                }
            }
            n.unlink()
        }
        return textRenderer.render(node).trim()
    }

    /**
     * The plain text of [node]'s first paragraph, cut to its first sentence.  A document that does
     * not open with a paragraph (a list, heading, or code block) is flattened whole.  Sentence
     * boundaries come from [BreakIterator], so `e.g.`, decimals, and URLs don't end a sentence.
     *
     * Mutates [node]; pass a freshly parsed document.
     */
    public fun firstSentence(node: Node): String {
        val first = node.firstChild
        val plain = toPlainText(if (first is Paragraph) first else node)
        if (plain.isEmpty()) return ""
        val iterator = BreakIterator.getSentenceInstance(Locale.ROOT)
        iterator.setText(plain)
        val end = iterator.next()
        return if (end == BreakIterator.DONE) plain else plain.substring(0, end).trim()
    }
}
