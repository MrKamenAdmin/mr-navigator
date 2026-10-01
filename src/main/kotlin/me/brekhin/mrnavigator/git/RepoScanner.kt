package me.brekhin.mrnavigator.git

import java.io.File

/**
 * Finds git repositories of a project folder:
 *  - the repository the folder itself is in (searching upwards), and
 *  - repositories inside it (a folder with many repos, monorepo with nested repos), a few levels deep.
 */
object RepoScanner {
    /** Heavy or irrelevant folders that never contain the project's own repositories. */
    private val SKIP = setOf("node_modules", "vendor", "build", "out", "target", "dist", "bin", "obj", "venv", "__pycache__")

    fun find(base: File, maxDepth: Int = 4): List<File> {
        val found = LinkedHashSet<File>()
        findRoot(base)?.let { found += canonical(it) }
        walk(base, maxDepth, found)
        return found.toList()
    }

    /** Top-level directory of the repository containing [dir], or null. */
    fun findRoot(dir: File): File? {
        var cur: File? = dir
        while (cur != null) {
            if (File(cur, ".git").exists()) return cur
            cur = cur.parentFile
        }
        return null
    }

    private fun walk(dir: File, depth: Int, found: MutableSet<File>) {
        // `.git` is a directory for normal repos and a file for worktrees and submodules.
        if (File(dir, ".git").exists()) found += canonical(dir)
        if (depth == 0) return
        val children = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && f.name !in SKIP } ?: return
        for (child in children.sortedBy { it.name.lowercase() }) walk(child, depth - 1, found)
    }

    private fun canonical(f: File): File = try {
        f.canonicalFile
    } catch (e: Exception) {
        f.absoluteFile
    }

    /** "services/api" for a repo inside [base], the folder name otherwise. */
    fun displayName(base: File, root: File): String {
        val b = canonical(base)
        val r = canonical(root)
        if (r == b) return r.name
        val rel = try {
            r.relativeTo(b).invariantSeparatorsPath
        } catch (e: IllegalArgumentException) {
            null
        }
        return if (rel == null || rel.startsWith("..")) r.name else rel
    }
}
