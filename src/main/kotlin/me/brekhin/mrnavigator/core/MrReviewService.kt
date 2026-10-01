package me.brekhin.mrnavigator.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import me.brekhin.mrnavigator.api.*
import me.brekhin.mrnavigator.git.GitCli
import me.brekhin.mrnavigator.git.GitException
import me.brekhin.mrnavigator.git.RemoteUrl
import me.brekhin.mrnavigator.git.RepoScanner
import me.brekhin.mrnavigator.settings.MrReviewSettings
import me.brekhin.mrnavigator.util.msg
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Everything loaded for one opened merge request. */
class MrSession(
    val project: ProjectRef,
    val connection: Connection,
    val git: GitCli,
    /** Remote of [git] that points at [project] — MR refs are fetched from it. */
    val remoteName: String,
    val mr: MergeRequest,
    val changes: List<FileChange>,
    @Volatile var discussions: List<Discussion>,
    @Volatile var reviews: Reviews,
    /** Paths the user has already looked at in this version of the MR (kept between IDE restarts). */
    val viewed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
) {
    val type: HostingType get() = connection.type
    /** "!12" or "#12". */
    val ref: String get() = "${type.prefix}${mr.iid}"

    fun isOutdated(d: Discussion): Boolean = d.position?.isOutdatedFor(mr.diffRefs?.headSha) == true

    /** CI of the head commit, loaded with the session. */
    @Volatile var checks: Checks = Checks.NONE

    /** Where outdated threads sit in the current code (thread id → line of the new version); see MrReviewService.relocate. */
    val relocated = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Merge base computed by git when the server doesn't give it (GitHub, Bitbucket Cloud); set by ensureCommits. */
    @Volatile var localBase: String? = null

    /** The refs as the server gave them — enough for comment positions; the merge base may still be unknown. */
    val diffRefs: DiffRefs get() = mr.diffRefs ?: throw ApiException(msg("error.noDiffRefs", ref, type.title))

    /** Diff refs with the merge base known — for GitHub and Bitbucket Cloud only after [MrReviewService.ensureCommits]. */
    val refs: DiffRefs
        get() {
            val r = diffRefs
            return if (r.baseSha != null) r else r.copy(baseSha = localBase ?: throw ApiException(msg("error.noMergeBase", ref)))
        }

    val base: String get() = refs.baseSha!!
    private val lineMaps = HashMap<FileChange, DiffLineMap>()

    fun lineMap(change: FileChange): DiffLineMap = synchronized(lineMaps) { lineMaps.getOrPut(change) { DiffLineMap(change.diff) } }

    /** Whether a line position belongs to [change] (new path; old path for a deleted file). */
    fun onFile(p: Position, change: FileChange): Boolean =
        p.newPath == change.newPath || (p.newPath == null && p.oldPath == change.oldPath)

    fun threadsFor(change: FileChange): List<Discussion> =
        discussions.filter { d -> !d.isSystem && d.position?.let { onFile(it, change) } == true }

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

/** A git repository of the IDE project whose remote points at one of the connected servers. */
class Repo(val root: File, val name: String, val remoteName: String, val project: ProjectRef, val connection: Connection) {
    override fun toString() = name
    override fun equals(other: Any?) = other is Repo && other.root == root && other.project == project
    override fun hashCode() = root.hashCode()
}

class MrException(message: String) : Exception(message)

/**
 * Nothing is connected yet (null message), no token, the token is rejected, or no remote matches a connection;
 * [connection] — the one to fix.
 */
class SetupNeeded(message: String?, val connection: Connection? = null) : Exception(message)

/**
 * Refreshes the lists after an action the server has done. A failed refresh must not report the action as failed:
 * a retry would post the comment twice. The list stays stale until the next refresh.
 */
internal fun refreshAfterAction(refresh: () -> Unit) {
    try {
        refresh()
    } catch (e: ApiException) {
        logger<MrReviewService>().info("Refresh after an action failed", e)
    }
}

@Service(Service.Level.PROJECT)
class MrReviewService(private val ideProject: Project) {
    @Volatile var session: MrSession? = null
        private set
    /** Where to go back to, per repository (several repos can have an MR checked out at once). */
    private val returnPoints = java.util.concurrent.ConcurrentHashMap<File, ReturnPoint>()

    fun returnPointFor(s: MrSession?): ReturnPoint? = s?.let { returnPoints[it.git.root] }

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

    /** Repositories found for the connections they were matched against (cleared when those change). */
    @Volatile private var reposCache: Pair<List<Connection>, List<Repo>>? = null

    /** Current user per connection URL. */
    private val users = java.util.concurrent.ConcurrentHashMap<String, User>()

    /**
     * Git repositories of the IDE project whose remote points at one of the connected servers: the one
     * the project folder is in and the ones inside it (a folder with several repos). Blocking.
     */
    fun repositories(refresh: Boolean = false): List<Repo> {
        val settings = MrReviewSettings.getInstance()
        settings.migrateLegacy()
        val connections = settings.connections
        if (refresh) users.clear()
        if (!refresh) reposCache?.let { (c, list) -> if (c == connections) return list }
        if (connections.isEmpty()) throw SetupNeeded(null)

        val base = File(ideProject.basePath ?: throw MrException(msg("error.noProjectDir")))
        val roots = RepoScanner.find(base)
        if (roots.isEmpty()) throw MrException(msg("error.noRepos"))

        val hosts = LinkedHashSet<String>()
        val repos = roots.mapNotNull { root ->
            val remotes = try {
                GitCli(root).remotes()
            } catch (e: Exception) {
                emptyMap()
            }
            remotes.values.mapNotNullTo(hosts) { RemoteUrl.parse(it)?.host }
            remotes.entries.sortedBy { if (it.key == "origin") 0 else 1 }.firstNotNullOfOrNull { (name, url) ->
                val remote = RemoteUrl.parse(url) ?: return@firstNotNullOfOrNull null
                connections.firstNotNullOfOrNull { c ->
                    RemoteUrl.projectPath(remote, c)?.let { Repo(root, RepoScanner.displayName(base, root), name, ProjectRef(c.url, it), c) }
                }
            }
        }
        if (repos.isEmpty()) throw SetupNeeded(msg("error.noRemote", connections.joinToString { it.url }, hosts.joinToString()))
        reposCache = connections to repos
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

    /** Hosts of the project's git remotes, origin first — suggestions for the setup form. Blocking. */
    fun detectedHosts(): List<String> = try {
        val base = ideProject.basePath?.let { File(it) }
        val roots = base?.let { RepoScanner.find(it) }.orEmpty()
        roots.flatMap { root ->
            GitCli(root).remotes().entries.sortedBy { if (it.key == "origin") 0 else 1 }.mapNotNull { RemoteUrl.parse(it.value)?.host }
        }.distinct()
    } catch (e: Exception) {
        emptyList()
    }

    fun client(c: Connection): HostingClient {
        val token = MrReviewSettings.getInstance().getToken(c.url) ?: throw SetupNeeded(msg("error.noToken", c.url), c)
        return c.type.client(c, token)
    }

    fun currentUser(c: Connection, client: HostingClient): User = users.getOrPut(c.url) { client.currentUser() }

    /** Current user if already known (no network). */
    fun currentUserCached(c: Connection): User? = users[c.url]

    // --------------------------------------------------------------- loading

    fun loadSession(mr: MergeRequest): MrSession {
        val repo = selectedRepo(repositories())
        val client = client(repo.connection)
        runCatching { currentUser(repo.connection, client) }
        val full = client.mergeRequest(repo.project, mr.iid)
        val changes = client.changes(repo.project, full)
        val discussions = client.discussions(repo.project, full)
        val reviews = client.reviews(repo.project, full)
        val s = MrSession(repo.project, repo.connection, GitCli(repo.root), repo.remoteName, full, changes, discussions, reviews)
        s.checks = Checks.orNone { client.checks(repo.project, full) }
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
        s.discussions = client(s.connection).discussions(s.project, s.mr)
        fireChanged()
    }

    fun refreshReviews(s: MrSession) {
        s.reviews = client(s.connection).reviews(s.project, s.mr)
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
        s.git.headSha() == s.diffRefs.headSha
    } catch (e: Exception) {
        false
    }

    /** Makes sure base and head commits exist locally (needed to show file contents); computes a missing merge base. */
    fun ensureCommits(s: MrSession) {
        val r = s.mr.diffRefs ?: throw ApiException(msg("error.noDiffRefs", s.ref, s.type.title))
        val remote = s.remoteName
        if (!s.git.hasCommit(r.headSha)) {
            s.git.run("fetch", s.mr.fetchUrl ?: remote, "+${s.mr.fetchRef}:refs/mr-review/${s.mr.iid}", timeoutMs = 300_000)
        }
        if (!s.git.hasCommit(r.baseSha ?: s.localBase ?: r.startSha)) {
            s.git.run("fetch", remote, s.mr.targetBranch, timeoutMs = 300_000)
        }
        if (r.baseSha == null && s.localBase == null && s.git.hasCommit(r.headSha)) {
            // After a force-push of the target branch start is gone: its tip fetched above stands in for it.
            val target = r.startSha.takeIf { s.git.hasCommit(it) } ?: "refs/remotes/$remote/${s.mr.targetBranch}"
            s.localBase = s.git.run("merge-base", target, r.headSha, allowFail = true).trim().ifEmpty { null }
        }
        if (!s.git.hasCommit(r.headSha) || !s.git.hasCommit(s.base)) {
            throw GitException(msg("error.fetchFailed", s.type.term, remote))
        }
    }

    /**
     * Checks out the MR head into the local branch `mr/<iid>`, stashing local changes first.
     * Returns a message for the user.
     */
    fun checkout(s: MrSession): String {
        val git = s.git
        ensureCommits(s)
        val refs = s.refs

        if (git.headSha() == refs.headSha) return msg("checkout.alreadyOnMr", s.type.term)

        val previous = git.currentBranch() ?: git.headSha()
        var stashMarker: String? = null
        if (git.hasLocalChanges()) {
            if (!MrReviewSettings.getInstance().autoStash) {
                throw MrException(msg("error.localChanges"))
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
        return msg(if (stashMarker != null) "checkout.branchStashed" else "checkout.branch", branch)
    }

    /** Goes back to the branch that was active before [checkout] and restores stashed changes. */
    /** Goes back in the repository of [s] to the branch that was active before the first checkout. */
    fun goBack(s: MrSession): String {
        val point = returnPoints[s.git.root] ?: throw MrException(msg("error.nowhereToGoBack"))
        val git = GitCli(point.root)
        git.run("checkout", point.ref)
        val message = when {
            point.stashMarker == null -> msg("checkout.returned", point.ref)
            popStash(git, point.stashMarker) -> msg("checkout.returnedRestored", point.ref)
            else -> msg("checkout.returnedStashMissing", point.ref, point.stashMarker)
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
        client(s.connection).createDiscussion(s.project, s.mr, body, position)
        refreshAfterAction { refreshDiscussions(s) }
    }

    fun reply(s: MrSession, d: Discussion, body: String) {
        client(s.connection).reply(s.project, s.mr, d, body)
        refreshAfterAction { refreshDiscussions(s) }
    }

    fun setResolved(s: MrSession, d: Discussion, resolved: Boolean) {
        client(s.connection).resolve(s.project, s.mr, d, resolved)
        refreshAfterAction { refreshDiscussions(s) }
    }

    fun editNote(s: MrSession, d: Discussion, note: Note, body: String) {
        client(s.connection).editNote(s.project, s.mr, d, note, body)
        refreshAfterAction { refreshDiscussions(s) }
    }

    fun deleteNote(s: MrSession, d: Discussion, note: Note) {
        client(s.connection).deleteNote(s.project, s.mr, d, note)
        refreshAfterAction { refreshDiscussions(s) }
    }

    fun applySuggestions(s: MrSession, ids: List<Long>) {
        client(s.connection).applySuggestions(ids)
        refreshAfterAction { refreshDiscussions(s) }
    }

    /**
     * Finds where outdated threads of [change] sit in the current code: a diff from the version they were
     * written on (when that commit is local) maps their line, unless the line itself changed. Blocking.
     */
    fun relocate(s: MrSession, change: FileChange) {
        val head = s.mr.diffRefs?.headSha ?: return
        for (d in s.threadsFor(change)) {
            val p = d.position ?: continue
            val line = p.newLine ?: continue
            val written = p.headSha ?: continue
            if (!s.isOutdated(d) || written == head || s.relocated.containsKey(d.id) || !s.git.hasCommit(written)) continue
            val diff = s.git.run("diff", "-U0", written, head, "--", change.newPath, allowFail = true)
            DiffLineMap(diff).newFor(line)?.let { s.relocated[d.id] = it }
        }
    }

    // ------------------------------------------------------------- review

    private val drafts = java.util.concurrent.ConcurrentHashMap<String, MutableList<Draft>>()
    private fun draftsKey(s: MrSession) = "me.brekhin.mrnavigator.drafts.${s.project.serverUrl}/${s.project.path}!${s.mr.iid}"

    private fun draftList(s: MrSession): MutableList<Draft> = drafts.getOrPut(draftsKey(s)) {
        java.util.Collections.synchronizedList(Drafts.decode(PropertiesComponent.getInstance(ideProject).getValue(draftsKey(s))).toMutableList())
    }

    /** Comments kept in the IDE until the review is submitted; they survive restarts. */
    fun drafts(s: MrSession): List<Draft> = draftList(s).let { synchronized(it) { it.toList() } }

    fun saveDraft(s: MrSession, draft: Draft) = changeDrafts(s) { list ->
        val i = list.indexOfFirst { it.id == draft.id }
        if (i >= 0) list[i] = draft else list += draft
    }

    fun removeDraft(s: MrSession, id: String) = changeDrafts(s) { list -> list.removeAll { it.id == id } }

    private fun changeDrafts(s: MrSession, change: (MutableList<Draft>) -> Unit) {
        val list = draftList(s)
        synchronized(list) {
            change(list)
            PropertiesComponent.getInstance(ideProject).setValue(draftsKey(s), Drafts.encode(list).takeIf { list.isNotEmpty() })
        }
        fireChanged()
    }

    /**
     * Sends the drafts of the current version with [summary] and [verdict]. A draft is dropped only once the
     * server published it — so a retry after a failure doesn't send it twice, and a draft added meanwhile stays.
     */
    fun submitReview(s: MrSession, verdict: Verdict, summary: String) {
        val drafts = Drafts.current(drafts(s), s.mr.diffRefs?.headSha)
        client(s.connection).submitReview(s.project, s.mr, drafts, verdict, summary) { sent ->
            val ids = sent.map { it.id }.toSet()
            changeDrafts(s) { list -> list.removeAll { it.id in ids } }
        }
        refreshAfterAction {
            refreshDiscussions(s)
            refreshReviews(s)
        }
    }

    fun mergeOptions(s: MrSession): MergeOptions = client(s.connection).mergeOptions(s.project, s.mr)

    fun merge(s: MrSession, strategy: String?, deleteBranch: Boolean) = client(s.connection).merge(s.project, s.mr, strategy, deleteBranch)

    fun withdrawChanges(s: MrSession) {
        client(s.connection).withdrawChanges(s.project, s.mr)
        refreshAfterAction { refreshReviews(s) }
    }

    companion object {
        fun getInstance(project: Project): MrReviewService = project.service()
    }
}
