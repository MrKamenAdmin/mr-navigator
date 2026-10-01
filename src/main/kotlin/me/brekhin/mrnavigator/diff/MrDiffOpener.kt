package me.brekhin.mrnavigator.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.util.Pair
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.LocalFileSystem
import me.brekhin.mrnavigator.api.FileChange
import me.brekhin.mrnavigator.core.MrReviewService
import me.brekhin.mrnavigator.core.MrSession
import me.brekhin.mrnavigator.ui.Bg
import java.io.File

/** Attached to every diff request we open, so [MrDiffExtension] knows what it is looking at. */
class MrFileContext(
    val session: MrSession,
    val change: FileChange,
    /** Right side is the real project file (branch checked out) — code navigation works there. */
    val rightIsLocal: Boolean,
)

object MrDiffOpener {
    val CONTEXT_KEY: Key<MrFileContext> = Key.create("me.brekhin.mrnavigator.fileContext")

    /**
     * Opens a diff tab for [files] (in this order), starting with [selected];
     * [scrollTo] = side and 0-based line of [selected] to scroll to.
     */
    fun open(project: Project, session: MrSession, files: List<FileChange>, selected: FileChange, scrollTo: kotlin.Pair<Side, Int>? = null) {
        if (files.isEmpty()) return
        val service = MrReviewService.getInstance(project)
        Bg.run(project, "Подготовка diff !${session.mr.iid}", work = {
            service.ensureCommits(session)
            service.isCheckedOut(session)
        }) { checkedOut ->
            val producers = files.map { Producer(project, session, it, checkedOut, scrollTo.takeIf { _ -> it == selected }) }
            val index = files.indexOf(selected).coerceAtLeast(0)
            val chain = SimpleDiffRequestChain.fromProducers(producers, index)
            DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
        }
    }

    private class Producer(
        private val project: Project,
        private val session: MrSession,
        private val change: FileChange,
        private val checkedOut: Boolean,
        private val scrollTo: kotlin.Pair<Side, Int>?,
    ) : DiffRequestProducer {
        override fun getName(): String = change.displayPath

        override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
            try {
                val refs = session.refs
                val git = session.git
                val factory = DiffContentFactory.getInstance()

                val left: DiffContent =
                    if (change.newFile) factory.createEmpty()
                    else bytesContent(git.showFile(refs.baseSha, change.oldPath), change.oldPath)

                var rightIsLocal = false
                val right: DiffContent = when {
                    change.deletedFile -> factory.createEmpty()
                    checkedOut -> {
                        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(git.root, change.newPath))
                        if (vf != null) {
                            rightIsLocal = true
                            factory.create(project, vf)
                        } else {
                            bytesContent(git.showFile(refs.headSha, change.newPath), change.newPath)
                        }
                    }
                    else -> bytesContent(git.showFile(refs.headSha, change.newPath), change.newPath)
                }

                val mr = session.mr
                val leftTitle = if (change.newFile) "(новый файл)" else "${mr.targetBranch} · ${refs.baseSha.take(8)} · ${change.oldPath}"
                val rightTitle = when {
                    change.deletedFile -> "(удалён)"
                    rightIsLocal -> "Локальный файл · ${mr.sourceBranch} · ${change.newPath}"
                    else -> "${mr.sourceBranch} · ${refs.headSha.take(8)} · ${change.newPath} (без checkout переходы не работают)"
                }
                val request = SimpleDiffRequest("!${mr.iid}: ${change.displayPath}", left, right, leftTitle, rightTitle)
                request.putUserData(CONTEXT_KEY, MrFileContext(session, change, rightIsLocal))
                scrollTo?.let { (side, line) -> request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(side, line)) }
                MrReviewService.getInstance(project).setViewed(session, change.displayPath, true)
                return request
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                throw DiffRequestProducerException(e.message ?: e.toString(), e)
            }
        }

        private fun bytesContent(bytes: ByteArray?, path: String): DiffContent {
            val factory = DiffContentFactory.getInstance()
            bytes ?: return factory.createEmpty()
            val name = path.substringAfterLast('/')
            val type = FileTypeManager.getInstance().getFileTypeByFileName(name)
            return factory.createFromBytes(project, bytes, type, name)
        }
    }
}
