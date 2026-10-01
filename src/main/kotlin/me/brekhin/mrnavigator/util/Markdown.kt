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
 * Markdown → HTML with the parser bundled in the IDE: [gfmToHtml] for descriptions and comments.
 */
object Markdown {
    private val IMG = Regex("""<img src="([^"]*)" alt="([^"]*)" ?/>""")
    private val TASK = Regex("""<input type="checkbox" class="task-list-item-checkbox"( checked)? disabled ?/>""")
    // A ```suggestion block (GitLab "suggestion:-N+0", GitHub plain) is labelled, as on the web.
    private val SUGGESTION = Regex("""<pre><code class="language-suggestion[^"]*">""")
    // A link the OS would hand to some other app (smb:, vscode:, …) — kept as plain text.
    private val FOREIGN_LINK = Regex("""<a href="(?!(?:https?|mailto):)[a-zA-Z][a-zA-Z0-9+.-]*:[^"]*"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    // Neither a scheme ("https:", "mailto:") nor an anchor.
    private val RELATIVE_HREF = Regex("""href="(?![a-zA-Z][a-zA-Z0-9+.-]*:|#)([^"]*)"""")

    /**
     * Full GitHub-flavoured Markdown for descriptions and comments, with the parser bundled in the IDE.
     * Raw HTML of the author is escaped (Swing would interpret `<object>`), images become links
     * (Swing can't load them: uploads need auth), only web and mail links stay clickable,
     * relative links are resolved against [baseUrl] — the project's page.
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
            .replace(SUGGESTION, "<div><i>Suggestion:</i></div><pre><code>")
            // Swing CSS knows no classes from the generator.
            .replace("<span class=\"user-del\">", "<span style=\"text-decoration: line-through\">")
            .replace(FOREIGN_LINK, "$1")
            .replace(RELATIVE_HREF) { "href=\"${resolve(it.groupValues[1], baseUrl.trimEnd('/'))}\"" }
    }

    /** GitLab's /uploads/ and plain relative links belong to the [project], other root-relative ones to its host. */
    private fun resolve(href: String, project: String): String {
        val scheme = project.substringBefore("://")
        return when {
            href.startsWith("//") -> "$scheme:$href"
            href.startsWith("/") && !href.startsWith("/uploads/") -> "$scheme://" + project.substringAfter("://").substringBefore('/') + href
            else -> "$project/${href.trimStart('/')}"
        }
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
}
