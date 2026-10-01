package me.brekhin.mrnavigator.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import me.brekhin.mrnavigator.api.*
import me.brekhin.mrnavigator.git.GitCli
import me.brekhin.mrnavigator.git.GitException
import me.brekhin.mrnavigator.git.RemoteUrl
import me.brekhin.mrnavigator.git.RepoScanner
import me.brekhin.mrnavigator.settings.MrReviewSettings
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Everything loaded for one opened merge request. */
class MrSession(
    val project: ProjectRef,
    val git: GitCli,
    /** Remote of [git] that points at [project] — MR refs are fetched from it. */
    val remoteName: String,
    val mr: MergeRequest,
    val changes: List<FileChange>,
    @Volatile var discussions: List<Discussion>,
    @Volatile var approvedBy: List<String>,
    /** Paths the user has already looked at in this version of the MR (kept between IDE restarts). */
    val viewed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
) {
    val refs: DiffRefs get() = mr.diffRefs ?: throw GitLabException("У MR нет diff_refs — GitLab ещё не посчитал diff, обновите позже")
    private val lineMaps = HashMap<FileChange, DiffLineMap>()

    fun lineMap(change: FileChange): DiffLineMap = synchronized(lineMaps) { lineMaps.getOrPut(change) { DiffLineMap(change.diff) } }

    fun threadsFor(change: FileChange): List<Discussion> = discussions.filter { d ->
        !d.isSystem && d.position != null &&
            (d.position!!.newPath == change.newPath || (d.position!!.newPath == null && d.position!!.oldPath == change.oldPath))
    }

    /** Threads that are not attached to a line (general discussion). */
    val generalThreads: List<Discussion> get() = discussions.filter { !it.isSystem && it.position == null }
    val lineThreads: List<Discussion> get() = discussions.filter { !it.isSystem && it.position != null }
}

/** What the working copy is on, relative to the MR — shown in the MR card. */
data class CheckoutState(
    /** HEAD is exactly the MR head commit — diffs show local files, navigation works. */
    val onMr: Boolean,
    /** Current branch, or a short sha when HEAD is detached; null if git failed. */
    val current: String?,
    /** Commit of the local `mr/<iid>` branch, if it exists. */
    val mrBranchSha: String?,
    val mrHeadSha: String?,
    val error: String? = null,
)

/** Where to go back after reviewing. */
data class ReturnPoint(val iid: Long, val ref: String, val stashMarker: String?, val root: File)

/** A git repository of the IDE project whose remote points at the configured GitLab. */
class Repo(val root: File, val name: String, val remoteName: String, val project: ProjectRef) {
    override fun toString() = name
    override fun equals(other: Any?) = other is Repo && other.root == root && other.project == project
    override fun hashCode() = root.hashCode()
}

class MrException(message: String) : Exception(message)

/** The plugin is not connected yet (no token, or the configured server does not match the project's remote). */
class SetupNeeded(message: String) : Exception(message)

@Service(Service.Level.PROJECT)
class MrReviewService(private val ideProject: Project) {
    @Volatile var session: MrSession? = null
        private set
    /** Where to go back to, per repository (several repos can have an MR checked out at once). */
    private val returnPoints = java.util.concurrent.ConcurrentHashMap<File, ReturnPoint>()

    fun returnPointFor(s: MrSession?): ReturnPoint? = s?.let { returnPoints[it.git.root] }
    @Volatile private var cachedUser: Pair<String, User>? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** [listener] is called on the EDT whenever the session or its discussions change. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun fireChanged() {
        ApplicationManager.getApplication().invokeLater({ listeners.forEach { it() } }, ideProject.disposed)
    }

    // ------------------------------------------------------------ discovery

    class Located(val git: GitCli, val remoteName: String, val project: ProjectRef)

    /** Repositories found for the server they were matched against (cleared when it changes). */
    @Volatile private var reposCache: Pair<String, List<Repo>>? = null

    /**
     * Git repositories of the IDE project that point at the configured GitLab: the one the project
     * folder is in and the ones inside it (a folder with several repos). Blocking.
     */
    fun repositories(refresh: Boolean = false): List<Repo> {
        val server = MrReviewSettings.getInstance().serverUrl
        if (!refresh) reposCache?.let { (s, list) -> if (s == server) return list }

        val base = File(ideProject.basePath ?: throw MrException("У проекта нет каталога"))
        val roots = RepoScanner.find(base)
        if (roots.isEmpty()) throw MrException("В папке проекта не найдено ни одного git-репозитория")

        val hosts = LinkedHashSet<String>()
        val repos = roots.mapNotNull { root ->
            val remotes = try {
                GitCli(root).remotes()
            } catch (e: Exception) {
                emptyMap()
            }
            remotes.values.mapNotNullTo(hosts) { RemoteUrl.parse(it)?.host }
            remotes.entries.sortedBy { if (it.key == "origin") 0 else 1 }.firstNotNullOfOrNull { (name, url) ->
                val path = RemoteUrl.parse(url)?.let { RemoteUrl.projectPath(it, server) }
                path?.let { Repo(root, RepoScanner.displayName(base, root), name, ProjectRef(server, it)) }
            }
        }
        if (repos.isEmpty()) {
            throw SetupNeeded(
                "Ни один git remote не указывает на $server" +
                    (if (hosts.isNotEmpty()) " (remote'ы: ${hosts.joinToString()})" else "") +
                    ". Укажите адрес вашего GitLab.",
            )
        }
        reposCache = server to repos
        return repos
    }

    private val repoKey get() = "me.brekhin.mrnavigator.repo"

    /** The repository chosen in the tool window (remembered per IDE project), or the first one. */
    fun selectedRepo(repos: List<Repo>): Repo {
        val saved = PropertiesComponent.getInstance(ideProject).getValue(repoKey)
        return repos.firstOrNull { it.root.path == saved } ?: repos.first()
    }

    fun selectRepo(repo: Repo) {
        PropertiesComponent.getInstance(ideProject).setValue(repoKey, repo.root.path)
    }

    /** Repository, remote and GitLab project to work with (the selected repository). Blocking. */
    fun locate(): Located {
        val repo = selectedRepo(repositories())
        return Located(GitCli(repo.root), repo.remoteName, repo.project)
    }

    /** "https://host" for every git remote of the project, origin first — suggestions for the setup form. Blocking. */
    fun detectedServers(): List<String> = try {
        val base = ideProject.basePath?.let { File(it) }
        val roots = base?.let { RepoScanner.find(it) }.orEmpty()
        roots.flatMap { root ->
            GitCli(root).remotes().entries.sortedBy { if (it.key == "origin") 0 else 1 }.mapNotNull { RemoteUrl.parse(it.value)?.host }
        }.distinct().map { "https://$it" }
    } catch (e: Exception) {
        emptyList()
    }

    fun client(): GitLabClient {
        val settings = MrReviewSettings.getInstance()
        val token = settings.getToken() ?: throw SetupNeeded("Не задан токен GitLab")
        return GitLabClient(settings.serverUrl, token)
    }

    fun currentUser(client: GitLabClient): User {
        val server = MrReviewSettings.getInstance().serverUrl
        cachedUser?.let { (s, u) -> if (s == server) return u }
        return client.currentUser().also { cachedUser = server to it }
    }

    /** Current user if already known (no network). */
    fun currentUserCached(): User? = cachedUser?.second

    // --------------------------------------------------------------- loading

    fun loadSession(mr: MergeRequest): MrSession {
        val located = locate()
        val client = client()
        runCatching { currentUser(client) }
        val full = client.mergeRequest(located.project, mr.iid)
        val changes = client.changes(located.project, mr.iid)
        val discussions = client.discussions(located.project, mr.iid)
        val approved = client.approvedBy(located.project, mr.iid)
        val s = MrSession(located.project, located.git, located.remoteName, full, changes, discussions, approved)
        loadViewed(s)
        session = s
        fireChanged()
        return s
    }

    // One key per MR; the first element is the head commit the marks belong to,
    // so new commits in the MR reset them (and old versions don't pile up).
    private fun viewedKey(s: MrSession) = "me.brekhin.mrnavigator.viewed.${s.project.path}!${s.mr.iid}"
    private fun viewedVersion(s: MrSession) = "@" + (s.mr.diffRefs?.headSha ?: "")

    private fun loadViewed(s: MrSession) {
        val stored = PropertiesComponent.getInstance(ideProject).getList(viewedKey(s)) ?: return
        if (stored.firstOrNull() == viewedVersion(s)) s.viewed.addAll(stored.drop(1))
    }

    fun setViewed(s: MrSession, path: String, viewed: Boolean) {
        synchronized(s.viewed) {
            val changed = if (viewed) s.viewed.add(path) else s.viewed.remove(path)
            if (!changed) return
            PropertiesComponent.getInstance(ideProject).setList(viewedKey(s), listOf(viewedVersion(s)) + s.viewed.sorted())
        }
        fireChanged()
    }

    fun refreshDiscussions(s: MrSession) {
        s.discussions = client().discussions(s.project, s.mr.iid)
        fireChanged()
    }

    fun refreshApprovals(s: MrSession) {
        s.approvedBy = client().approvedBy(s.project, s.mr.iid)
        fireChanged()
    }

    // -------------------------------------------------------------- git side

    fun checkoutState(s: MrSession): CheckoutState {
        val head = s.mr.diffRefs?.headSha
        return try {
            val git = s.git
            val sha = git.headSha()
            val current = git.currentBranch() ?: sha.take(8)
            val mrBranch = git.run("rev-parse", "--verify", "--quiet", "refs/heads/mr/${s.mr.iid}", allowFail = true).trim().ifEmpty { null }
            CheckoutState(onMr = head != null && sha == head, current = current, mrBranchSha = mrBranch, mrHeadSha = head)
        } catch (e: Exception) {
            CheckoutState(false, null, null, head, e.message ?: e.toString())
        }
    }

    fun isCheckedOut(s: MrSession): Boolean = try {
        s.git.headSha() == s.refs.headSha
    } catch (e: Exception) {
        false
    }

    /** Makes sure base and head commits exist locally (needed to show file contents). */
    fun ensureCommits(s: MrSession) {
        val refs = s.refs
        val remote = s.remoteName
        if (!s.git.hasCommit(refs.headSha)) {
            s.git.run("fetch", remote, "+refs/merge-requests/${s.mr.iid}/head:refs/mr-review/${s.mr.iid}", timeoutMs = 300_000)
        }
        if (!s.git.hasCommit(refs.baseSha)) {
            s.git.run("fetch", remote, s.mr.targetBranch, timeoutMs = 300_000)
        }
        if (!s.git.hasCommit(refs.headSha) || !s.git.hasCommit(refs.baseSha)) {
            throw GitException("Не удалось получить коммиты MR из remote '$remote'")
        }
    }

    /**
     * Checks out the MR head into the local branch `mr/<iid>`, stashing local changes first.
     * Returns a message for the user.
     */
    fun checkout(s: MrSession): String {
        val git = s.git
        val refs = s.refs
        ensureCommits(s)

        if (git.headSha() == refs.headSha) return "Уже на коммите MR"

        val previous = git.currentBranch() ?: git.headSha()
        var stashMarker: String? = null
        if (git.hasLocalChanges()) {
            if (!MrReviewSettings.getInstance().autoStash) {
                throw MrException("Есть незакоммиченные изменения. Закоммитьте их или включите авто-stash в настройках.")
            }
            stashMarker = "mr-review !${s.mr.iid} ${System.currentTimeMillis()}"
            git.run("stash", "push", "-m", stashMarker)
        }

        val branch = "mr/${s.mr.iid}"
        try {
            git.run("checkout", "-B", branch, refs.headSha)
        } catch (e: GitException) {
            if (stashMarker != null) popStash(git, stashMarker)
            throw e
        }
        refreshFiles(git.root)
        // Keep the very first return point: checking out another MR in the same repo must still lead
        // back to the user's own branch, not to the previous mr/<iid>.
        returnPoints.putIfAbsent(git.root, ReturnPoint(s.mr.iid, previous, stashMarker, git.root))
        fireChanged()
        return "Ветка $branch" + (if (stashMarker != null) ", ваши изменения спрятаны в stash" else "")
    }

    /** Goes back to the branch that was active before [checkout] and restores stashed changes. */
    /** Goes back in the repository of [s] to the branch that was active before the first checkout. */
    fun goBack(s: MrSession): String {
        val point = returnPoints[s.git.root] ?: throw MrException("Некуда возвращаться")
        val git = GitCli(point.root)
        git.run("checkout", point.ref)
        var message = "Вернулись на ${point.ref}"
        if (point.stashMarker != null) {
            message += if (popStash(git, point.stashMarker)) ", изменения из stash восстановлены"
            else ". Stash «${point.stashMarker}» не найден или конфликтует — восстановите вручную (git stash list)"
        }
        refreshFiles(git.root)
        returnPoints.remove(point.root)
        fireChanged()
        return message
    }

    private fun popStash(git: GitCli, marker: String): Boolean {
        val ref = git.run("stash", "list", "--format=%gd%x09%s").lines()
            .firstOrNull { it.contains(marker) }?.substringBefore('\t') ?: return false
        return try {
            git.run("stash", "pop", ref)
            true
        } catch (e: GitException) {
            false
        }
    }

    private fun refreshFiles(root: File) {
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
    }

    // ----------------------------------------------------------- comments

    fun postComment(s: MrSession, body: String, position: Position?) {
        client().createDiscussion(s.project, s.mr.iid, body, position)
        refreshDiscussions(s)
    }

    fun reply(s: MrSession, d: Discussion, body: String) {
        client().reply(s.project, s.mr.iid, d.id, body)
        refreshDiscussions(s)
    }

    fun setResolved(s: MrSession, d: Discussion, resolved: Boolean) {
        client().resolve(s.project, s.mr.iid, d.id, resolved)
        refreshDiscussions(s)
    }

    fun applySuggestions(s: MrSession, ids: List<Long>) {
        client().applySuggestions(ids)
        refreshDiscussions(s)
    }

    companion object {
        fun getInstance(project: Project): MrReviewService = project.service()
    }
}
