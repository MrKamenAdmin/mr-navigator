package me.brekhin.mrnavigator.ui

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/** Background work with a progress indicator; callbacks run on the EDT. */
object Bg {
    fun <T> run(
        project: Project,
        title: String,
        work: (ProgressIndicator) -> T,
        onError: (Throwable) -> Unit = { if (it !is java.util.concurrent.CancellationException) Notify.error(project, title, it) },
        onOk: (T) -> Unit,
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var result: Result<T>? = null

            override fun run(indicator: ProgressIndicator) {
                result = Result.success(work(indicator))
            }

            override fun onSuccess() {
                result?.let { onOk(it.getOrThrow()) }
            }

            override fun onThrowable(error: Throwable) = onError(error)

            override fun onCancel() = onError(java.util.concurrent.CancellationException("Отменено"))
        })
    }
}

object Notify {
    private const val GROUP = "MR Navigator"

    fun info(project: Project, text: String, vararg actions: Pair<String, () -> Unit>) =
        show(project, text, NotificationType.INFORMATION, actions)

    fun error(project: Project, title: String, e: Throwable) =
        show(project, "$title: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR, emptyArray())

    private fun show(project: Project, text: String, type: NotificationType, actions: Array<out Pair<String, () -> Unit>>) {
        val n = NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(text, type)
        for ((label, action) in actions) n.addAction(NotificationAction.createSimpleExpiring(label) { action() })
        n.notify(project)
    }
}
