package me.brekhin.mrnavigator.core

import me.brekhin.mrnavigator.api.FileChange

/** Which MR files count as "noise" and are hidden from the changes tree and the diff. */
class HiddenFiles(suffixes: Collection<String>, val enabled: Boolean = true) {
    private val suffixes = suffixes.map { it.trim() }.filter { it.isNotEmpty() }

    fun isHidden(path: String?): Boolean =
        enabled && !path.isNullOrEmpty() && suffixes.any { path.endsWith(it) }

    /** Renames are judged by the new path: `foo.pb.go → foo.go` stays visible. */
    fun isHidden(change: FileChange): Boolean = isHidden(change.displayPath)

    companion object {
        fun parse(text: String): List<String> =
            text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
