package me.brekhin.mrnavigator.core

import me.brekhin.mrnavigator.api.FileChange

/**
 * Splits a raw multi-file `git diff` (Bitbucket's pull request diff) into files with hunks only —
 * the shape GitLab's /diffs and GitHub's /files give, which [DiffLineMap] reads.
 */
object UnifiedDiff {
    private const val DEV_NULL = "/dev/null"
    // git's a/ b/, and src:// dst:// of Bitbucket Data Center.
    private val PREFIXES = listOf("a/", "b/", "src://", "dst://")

    fun split(text: String): List<FileChange> {
        val files = ArrayList<MutableList<String>>()
        for (line in text.replace("\r\n", "\n").split('\n')) {
            if (line.startsWith("diff --git ")) files += mutableListOf(line) else files.lastOrNull()?.add(line)
        }
        return files.map { file(it) }
    }

    private fun file(lines: List<String>): FileChange {
        val firstHunk = lines.indexOfFirst { it.startsWith("@@") }.let { if (it < 0) lines.size else it }
        val header = lines.subList(0, firstHunk)
        fun value(prefix: String) = header.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        val minus = value("--- ")?.let(::path)
        val plus = value("+++ ")?.let(::path)
        val (gitOld, gitNew) = gitPaths(header.first().removePrefix("diff --git "))
        val newFile = value("new file mode") != null || minus == DEV_NULL
        val deleted = value("deleted file mode") != null || plus == DEV_NULL
        val renameFrom = value("rename from ")?.let(::unquote)
        val oldPath = renameFrom ?: minus?.takeIf { it != DEV_NULL } ?: gitOld
        val newPath = value("rename to ")?.let(::unquote) ?: plus?.takeIf { it != DEV_NULL } ?: gitNew
        return FileChange(
            // Like GitLab: a new file has old_path == new_path, a deleted one new_path == old_path.
            oldPath = if (newFile) newPath else oldPath,
            newPath = if (deleted) oldPath else newPath,
            newFile = newFile,
            deletedFile = deleted,
            renamedFile = renameFrom != null,
            diff = lines.subList(firstHunk, lines.size).joinToString("\n").trimEnd('\n'),
            tooLarge = false,
        )
    }

    /** "a/x.go" → "x.go", without the quotes git puts around unusual names and the tab with a timestamp. */
    private fun path(raw: String): String {
        val p = unquote(raw.substringBefore('\t'))
        return PREFIXES.firstOrNull { p.startsWith(it) }?.let { p.removePrefix(it) } ?: p
    }

    private fun unquote(s: String) = s.trim().removeSurrounding("\"")

    /** Paths from "a/x b/y" — for files without ---/+++ lines (binary, mode or rename only). */
    private fun gitPaths(rest: String): Pair<String, String> {
        val at = listOf(" b/", " \"b/", " dst://", " \"dst://").map { rest.indexOf(it) }.filter { it > 0 }.minOrNull()
            ?: return path(rest) to path(rest)
        return path(rest.substring(0, at)) to path(rest.substring(at + 1))
    }
}
