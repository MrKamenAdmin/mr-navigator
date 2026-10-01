package me.brekhin.mrnavigator.util

/** Very small Markdown → HTML for comment bodies (code blocks, inline code, bold, links, lists). */
object Markdown {
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
