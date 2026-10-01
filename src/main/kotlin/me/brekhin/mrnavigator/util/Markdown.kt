package me.brekhin.mrnavigator.util

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown → HTML: [gfmToHtml] for MR descriptions; [toHtml], a very small renderer for comment bodies
 * (code blocks, inline code, bold, links, lists) that labels suggestion blocks.
 */
object Markdown {
    private val IMG = Regex("""<img src="([^"]*)" alt="([^"]*)" ?/>""")
    private val TASK = Regex("""<input type="checkbox" class="task-list-item-checkbox"( checked)? disabled ?/>""")
    // A link the OS would hand to some other app (smb:, vscode:, …) — kept as plain text.
    private val FOREIGN_LINK = Regex("""<a href="(?!(?:https?|mailto):)[a-zA-Z][a-zA-Z0-9+.-]*:[^"]*"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    // Neither a scheme ("https:", "mailto:") nor an anchor.
    private val RELATIVE_HREF = Regex("""href="(?![a-zA-Z][a-zA-Z0-9+.-]*:|#)([^"]*)"""")

    /**
     * Full GitHub-flavoured Markdown for MR descriptions, with the parser bundled in the IDE.
     * Raw HTML of the author is escaped (Swing would interpret `<object>`), images become links
     * (Swing can't load them: uploads need auth), only web and mail links stay clickable,
     * relative links are resolved against [baseUrl].
     */
    fun gfmToHtml(md: String, baseUrl: String): String {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(md)
        val escapeRaw = object : GeneratingProvider {
            override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) =
                visitor.consumeHtml(escape(node.getTextInNode(text).toString()))
        }
        val providers = flavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(tree, md), null) +
            mapOf(MarkdownElementTypes.HTML_BLOCK to escapeRaw, MarkdownTokenTypes.HTML_TAG to escapeRaw)
        return HtmlGenerator(md, tree, providers, false).generateHtml()
            .replace(IMG) { "<a href=\"${it.groupValues[1]}\">${it.groupValues[2].ifEmpty { it.groupValues[1].substringAfterLast('/') }}</a>" }
            .replace(TASK) { if (it.groupValues[1].isEmpty()) "☐ " else "☑ " }
            // Swing CSS knows no classes from the generator.
            .replace("<span class=\"user-del\">", "<span style=\"text-decoration: line-through\">")
            .replace(FOREIGN_LINK, "$1")
            .replace(RELATIVE_HREF) { "href=\"${baseUrl.trimEnd('/')}/${it.groupValues[1].trimStart('/')}\"" }
    }

    fun toHtml(md: String): String {
        val out = StringBuilder()
        val lines = md.replace("\r\n", "\n").split('\n')
        var i = 0
        var inList = false
        fun closeList() {
            if (inList) { out.append("</ul>"); inList = false }
        }
        while (i < lines.size) {
            val line = lines[i]
            val fence = line.trimStart()
            if (fence.startsWith("```")) {
                closeList()
                val ticks = fence.takeWhile { it == '`' }
                val lang = fence.removePrefix(ticks).trim()
                val code = StringBuilder()
                i++
                // The block ends at a line of at least as many backticks (GitLab uses longer fences
                // when the code itself contains ```).
                while (i < lines.size && !lines[i].trim().let { it.startsWith(ticks) && it.all { c -> c == '`' } }) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++ // closing fence
                if (lang.startsWith("suggestion")) out.append("<div><i>Suggestion:</i></div>")
                out.append("<pre><code>").append(escape(code.toString().trimEnd('\n'))).append("</code></pre>")
                continue
            }
            val item = Regex("""^\s*[-*]\s+(.*)$""").find(line)
            if (item != null) {
                if (!inList) { out.append("<ul>"); inList = true }
                out.append("<li>").append(inline(item.groupValues[1])).append("</li>")
            } else {
                closeList()
                if (line.isBlank()) out.append("<br>") else out.append(inline(line)).append("<br>")
            }
            i++
        }
        closeList()
        return out.toString().removeSuffix("<br>")
    }

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private fun inline(text: String): String {
        // Split by inline code first so that nothing inside `code` is formatted.
        val parts = text.split('`')
        return parts.mapIndexed { idx, part ->
            if (idx % 2 == 1 && idx < parts.size - 1) "<code>${escape(part)}</code>"
            else {
                var s = escape(if (idx % 2 == 1) "`$part" else part)
                s = s.replace(Regex("""\*\*(.+?)\*\*"""), "<b>$1</b>")
                s = s.replace(Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)"""), "<a href=\"$2\">$1</a>")
                s
            }
        }.joinToString("")
    }
}
