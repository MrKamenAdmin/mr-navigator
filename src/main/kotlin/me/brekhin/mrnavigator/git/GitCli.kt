package me.brekhin.mrnavigator.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.Logger
import me.brekhin.mrnavigator.settings.MrReviewSettings
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class GitException(message: String) : IOException(message)

/**
 * Thin wrapper over the git executable. Deliberately does not use the Git4Idea plugin API
 * (it is internal and was split into modules in 2025.3+), only the command line.
 * All methods block — call them from a background thread.
 */
class GitCli(val root: File) {
    private val exe get() = MrReviewSettings.getInstance().gitExecutable

    private fun commandLine(args: List<String>) = GeneralCommandLine(listOf(exe) + args)
        .withWorkDirectory(root)
        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        .withEnvironment("GIT_TERMINAL_PROMPT", "0")
        .withEnvironment("LC_ALL", "C")
        .withCharset(StandardCharsets.UTF_8)

    fun run(vararg args: String, timeoutMs: Int = 120_000, allowFail: Boolean = false): String {
        LOG.info("git ${args.joinToString(" ")} (in $root)")
        val output = try {
            ExecUtil.execAndGetOutput(commandLine(args.toList()), timeoutMs)
        } catch (e: Exception) {
            throw GitException("Не удалось запустить git: ${e.message}. Укажите путь к git в настройках плагина.")
        }
        if (output.isTimeout) throw GitException("git ${args.firstOrNull()} не завершился за ${timeoutMs / 1000} с")
        if (output.exitCode != 0) LOG.info("git ${args.firstOrNull()} exit ${output.exitCode}: ${output.stderr.trim().take(500)}")
        if (output.exitCode != 0 && !allowFail) {
            throw GitException("git ${args.joinToString(" ")}:\n${output.stderr.ifBlank { output.stdout }.trim()}")
        }
        return output.stdout
    }

    fun ok(vararg args: String): Boolean = try {
        ExecUtil.execAndGetOutput(commandLine(args.toList()), 30_000).exitCode == 0
    } catch (e: Exception) {
        false
    }

    /** Raw bytes of `git show <rev>:<path>` (binary safe), or null if the file does not exist there. */
    fun showFile(rev: String, path: String): ByteArray? {
        val process = commandLine(listOf("show", "$rev:$path")).createProcess()
        val bytes = process.inputStream.use { it.readBytes() }
        process.errorStream.use { it.readBytes() }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GitException("git show $rev:$path завершился по таймауту")
        }
        return if (process.exitValue() == 0) bytes else null
    }

    fun remotes(): Map<String, String> =
        run("remote", "-v").lines()
            .mapNotNull { line -> line.split(Regex("\\s+")).takeIf { it.size >= 2 }?.let { it[0] to it[1] } }
            .toMap()

    fun headSha(): String = run("rev-parse", "HEAD").trim()

    /** Current branch name, or null when HEAD is detached. */
    fun currentBranch(): String? = run("rev-parse", "--abbrev-ref", "HEAD").trim().takeIf { it != "HEAD" && it.isNotEmpty() }

    fun hasCommit(sha: String): Boolean = ok("cat-file", "-e", "$sha^{commit}")

    /** Tracked changes only; untracked files do not block a checkout unless they collide. */
    fun hasLocalChanges(): Boolean = run("status", "--porcelain", "--untracked-files=no").isNotBlank()

    companion object {
        private val LOG = Logger.getInstance(GitCli::class.java)

        /** Top-level directory of the repository containing [dir], or null. */
        fun findRoot(dir: File): File? = RepoScanner.findRoot(dir)
    }
}
