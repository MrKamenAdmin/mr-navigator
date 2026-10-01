package me.brekhin.mrnavigator.util

/**
 * GitLab suggestions: a comment containing
 *
 *     ```suggestion:-N+0
 *     replacement text
 *     ```
 *
 * replaces lines [new_line - N .. new_line] of the new version (new_line = the commented line,
 * the last one for a range). Same format as GitLab's "Insert suggestion" button
 * (markdown/header.vue: `suggestion:-${lines.length - 1}+0`, lib/gitlab/diff/suggestions_parser.rb).
 * GitHub: the same block without `:-N+0`.
 */
object Suggestion {
    /** Backticks for the fence: longer than any ``` run inside [content] (text_markdown.js repeatCodeBackticks). */
    fun fence(content: String): String {
        val longest = Regex("`{3,}").findAll(content).maxOfOrNull { it.value.length } ?: 0
        return "`".repeat(maxOf(2, longest) + 1)
    }

    /**
     * The block pre-filled with the current [lines] (lines of the new version, top to bottom):
     * GitLab needs the range in the header, GitHub takes it from the comment.
     */
    fun block(lines: List<String>, gitlab: Boolean = true): Block {
        val content = lines.joinToString("\n")
        val fence = fence(content)
        val head = if (gitlab) "${fence}suggestion:-${maxOf(lines.size - 1, 0)}+0\n" else "${fence}suggestion\n"
        val text = head + content + "\n" + fence
        return Block(text, head.length, head.length + content.length)
    }

    /** [text] of the block and where the editable content sits in it (to select it after inserting). */
    data class Block(val text: String, val contentStart: Int, val contentEnd: Int)
}
