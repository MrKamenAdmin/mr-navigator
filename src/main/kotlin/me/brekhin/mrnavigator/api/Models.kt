package me.brekhin.mrnavigator.api

import me.brekhin.mrnavigator.util.*

data class User(val id: Long, val username: String, val name: String) {
    companion object {
        fun from(m: Map<String, Any?>?): User? = m?.let {
            User(it.long("id") ?: 0, it.str("username") ?: "", it.str("name") ?: it.str("username") ?: "")
        }
    }
}

data class DiffRefs(val baseSha: String, val startSha: String, val headSha: String) {
    companion object {
        fun from(m: Map<String, Any?>?): DiffRefs? {
            m ?: return null
            val base = m.str("base_sha") ?: return null
            val head = m.str("head_sha") ?: return null
            return DiffRefs(base, m.str("start_sha") ?: base, head)
        }
    }
}

data class MergeRequest(
    val iid: Long,
    val title: String,
    val description: String,
    val state: String,
    val draft: Boolean,
    val author: User?,
    val sourceBranch: String,
    val targetBranch: String,
    val webUrl: String,
    val sha: String?,
    val diffRefs: DiffRefs?,
    val updatedAt: String?,
    val userNotesCount: Int,
    val hasConflicts: Boolean,
    /** Ref on the server with the head commit, fetched into refs/mr-review/<iid>. */
    val fetchRef: String,
    /** Repository to fetch [fetchRef] from when it is not the project's remote (a Bitbucket Cloud fork). */
    val fetchUrl: String? = null,
) {
    companion object {
        fun from(m: Map<String, Any?>) = MergeRequest(
            iid = m.long("iid") ?: 0,
            title = m.str("title") ?: "",
            description = m.str("description") ?: "",
            state = m.str("state") ?: "",
            draft = m.bool("draft") || m.bool("work_in_progress"),
            author = User.from(m.o("author")),
            sourceBranch = m.str("source_branch") ?: "",
            targetBranch = m.str("target_branch") ?: "",
            webUrl = m.str("web_url") ?: "",
            sha = m.str("sha"),
            diffRefs = DiffRefs.from(m.o("diff_refs")),
            updatedAt = m.str("updated_at"),
            userNotesCount = m.int("user_notes_count") ?: 0,
            hasConflicts = m.bool("has_conflicts"),
            fetchRef = "refs/merge-requests/${m.long("iid") ?: 0}/head",
        )
    }
}

/** One file of the MR (entry of /merge_requests/:iid/diffs). */
data class FileChange(
    val oldPath: String,
    val newPath: String,
    val newFile: Boolean,
    val deletedFile: Boolean,
    val renamedFile: Boolean,
    /** Unified diff of this file (hunks only, without the `diff --git` header); may be empty for huge files. */
    val diff: String,
    val tooLarge: Boolean,
) {
    val displayPath: String get() = if (deletedFile) oldPath else newPath

    /** Added / removed line counts from the hunks (the diff of /diffs has no file headers). */
    val stats: Pair<Int, Int> by lazy {
        var added = 0
        var removed = 0
        var inHunk = false
        for (line in diff.lineSequence()) {
            when {
                line.startsWith("@@") -> inHunk = true
                !inHunk -> Unit
                line.startsWith("+") -> added++
                line.startsWith("-") -> removed++
            }
        }
        added to removed
    }

    companion object {
        fun from(m: Map<String, Any?>) = FileChange(
            oldPath = m.str("old_path") ?: m.str("new_path") ?: "",
            newPath = m.str("new_path") ?: m.str("old_path") ?: "",
            newFile = m.bool("new_file"),
            deletedFile = m.bool("deleted_file"),
            renamedFile = m.bool("renamed_file"),
            diff = m.str("diff") ?: "",
            tooLarge = m.bool("too_large") || m.bool("collapsed"),
        )
    }
}

/**
 * One end of a multi-line comment range, as GitLab expects it:
 * `line_code` = "<sha1(file path)>_<old line counter>_<new line counter>",
 * `type` = "new" for an added line, "old" for a removed one, null for an unchanged one
 * (what GitLab's own web UI sends; the server accepts both null and "old" there).
 */
data class LinePoint(val lineCode: String, val type: String?, val oldLine: Int?, val newLine: Int?) {
    /** "+12" for an added line, "-7" for a removed one, "12" otherwise — like GitLab shows it. */
    val label: String
        get() = when {
            type == "new" && newLine != null -> "+$newLine"
            newLine == null && oldLine != null -> "-$oldLine"
            else -> (newLine ?: oldLine)?.toString() ?: "?"
        }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "line_code" to lineCode,
        "type" to type,
        "old_line" to oldLine,
        "new_line" to newLine,
    )

    companion object {
        fun from(m: Map<String, Any?>?): LinePoint? {
            m ?: return null
            return LinePoint(m.str("line_code") ?: "", m.str("type"), m.int("old_line"), m.int("new_line"))
        }
    }
}

data class LineRange(val start: LinePoint, val end: LinePoint) {
    fun toJson(): Map<String, Any?> = linkedMapOf("start" to start.toJson(), "end" to end.toJson())

    companion object {
        fun from(m: Map<String, Any?>?): LineRange? {
            val start = LinePoint.from(m?.o("start")) ?: return null
            val end = LinePoint.from(m?.o("end")) ?: return null
            return LineRange(start, end)
        }
    }
}

data class Position(
    val baseSha: String?,
    val startSha: String?,
    val headSha: String?,
    val oldPath: String?,
    val newPath: String?,
    /** 1-based line in the old file, null for added lines. For a range — its last line. */
    val oldLine: Int?,
    /** 1-based line in the new file, null for removed lines. For a range — its last line. */
    val newLine: Int?,
    /** Set for multi-line comments. */
    val lineRange: LineRange? = null,
    /** The server says the thread was written for code that has changed since. */
    val outdated: Boolean = false,
) {
    val isMultiLine: Boolean get() = lineRange != null && lineRange.start != lineRange.end

    /** Written for another version of the MR than [head] — its lines no longer match the diff. */
    fun isOutdatedFor(head: String?): Boolean = outdated || (headSha != null && head != null && headSha != head)

    /** "12", "-7", or for a range "-3–+5". */
    fun lineLabel(): String =
        if (isMultiLine) "${lineRange!!.start.label}–${lineRange.end.label}"
        else newLine?.toString() ?: oldLine?.let { "-$it" } ?: "?"

    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "position_type" to "text",
            "base_sha" to baseSha,
            "start_sha" to startSha,
            "head_sha" to headSha,
            "old_path" to oldPath,
            "new_path" to newPath,
            "old_line" to oldLine,
            "new_line" to newLine,
        ).filterValues { it != null }.toMutableMap()
        if (lineRange != null) m["line_range"] = lineRange.toJson()
        return m
    }

    companion object {
        fun from(m: Map<String, Any?>?): Position? {
            m ?: return null
            if (m.str("position_type") != null && m.str("position_type") != "text") return null
            return Position(
                m.str("base_sha"), m.str("start_sha"), m.str("head_sha"),
                m.str("old_path"), m.str("new_path"),
                m.int("old_line"), m.int("new_line"),
                LineRange.from(m.o("line_range")),
            )
        }
    }
}

data class Note(
    val id: Long,
    val body: String,
    val author: User?,
    val createdAt: String?,
    val system: Boolean,
    val resolvable: Boolean,
    val resolved: Boolean,
    val position: Position?,
    val resolvedBy: User? = null,
    val suggestions: List<NoteSuggestion> = emptyList(),
) {
    companion object {
        fun from(m: Map<String, Any?>) = Note(
            id = m.long("id") ?: 0,
            body = m.str("body") ?: "",
            author = User.from(m.o("author")),
            createdAt = m.str("created_at"),
            system = m.bool("system"),
            resolvable = m.bool("resolvable"),
            resolved = m.bool("resolved"),
            position = Position.from(m.o("position")),
            resolvedBy = User.from(m.o("resolved_by")),
            suggestions = m.a("suggestions").map { NoteSuggestion.from(it.obj()) },
        )
    }
}

/** A suggestion of a diff note; [appliable] is GitLab's verdict (false once applied, outdated or the MR is closed). */
data class NoteSuggestion(val id: Long, val appliable: Boolean, val applied: Boolean) {
    companion object {
        fun from(m: Map<String, Any?>) = NoteSuggestion(m.long("id") ?: 0, m.bool("appliable"), m.bool("applied"))
    }
}

data class Discussion(val id: String, val notes: List<Note>, val webUrl: String? = null) {
    val first: Note? get() = notes.firstOrNull()
    val position: Position? get() = first?.position
    val resolvable: Boolean get() = notes.any { it.resolvable }
    val resolved: Boolean get() = resolvable && notes.filter { it.resolvable }.all { it.resolved }
    val isSystem: Boolean get() = notes.isNotEmpty() && notes.all { it.system }
    val resolvedBy: User? get() = notes.firstNotNullOfOrNull { it.resolvedBy }
    /** Time of the last non-system note. */
    val lastActivity: String? get() = notes.lastOrNull { !it.system }?.createdAt

    /** Path the thread belongs to, if it is a line comment. */
    val path: String? get() = position?.let { it.newPath ?: it.oldPath }

    companion object {
        fun from(m: Map<String, Any?>) = Discussion(
            id = m.str("id") ?: "",
            notes = m.a("notes").map { Note.from(it.obj()) },
        )
    }
}

/** GitLab project as seen from a git remote. */
data class ProjectRef(val serverUrl: String, val path: String) {
    val encodedPath: String get() = java.net.URLEncoder.encode(path, Charsets.UTF_8).replace("+", "%20")
}
