package me.brekhin.mrnavigator.git

import me.brekhin.mrnavigator.api.Connection
import me.brekhin.mrnavigator.api.HostingType

/** Host and project path parsed from a git remote URL. */
data class RemoteUrl(val host: String, val path: String) {
    companion object {
        private val SCP_LIKE = Regex("""^(?:[^@/]+@)?([^:/]+):(?!//)(.+)$""")

        /**
         * Supports
         *  - https://host[:port]/group/sub/project(.git)
         *  - ssh://git@host[:port]/group/project(.git)
         *  - git@host:group/project(.git)
         */
        fun parse(url: String): RemoteUrl? {
            val u = url.trim()
            if (u.isEmpty()) return null
            val (host, rawPath) = if (u.contains("://")) {
                val rest = u.substringAfter("://")
                val authority = rest.substringBefore('/')
                val hostPort = authority.substringAfter('@')
                val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
                host to rest.substringAfter('/', "")
            } else {
                val m = SCP_LIKE.find(u) ?: return null
                m.groupValues[1] to m.groupValues[2]
            }
            val path = rawPath.trim('/').removeSuffix(".git").trim('/')
            if (host.isEmpty() || path.isEmpty() || !path.contains('/')) return null
            return RemoteUrl(host.lowercase(), path)
        }

        /** Host of a configured server URL like https://gitlab.company.ru/ or https://host/gitlab */
        fun serverHost(serverUrl: String): String =
            serverUrl.trim().substringAfter("://").substringBefore('/').substringAfter('@').substringBefore(':').lowercase()

        /** Path prefix of a server installed under a sub-path (https://host/gitlab → "gitlab"). */
        fun serverPathPrefix(serverUrl: String): String =
            serverUrl.trim().substringAfter("://").substringAfter('/', "").trim('/')

        /** Project path for [remote] on the server of [c], or null if the remote is on another host. */
        fun projectPath(remote: RemoteUrl, c: Connection): String? {
            if (remote.host != serverHost(c.url)) return null
            val prefix = serverPathPrefix(c.url)
            val path = if (prefix.isNotEmpty() && remote.path.startsWith("$prefix/")) remote.path.removePrefix("$prefix/") else remote.path
            // Bitbucket DC clones over https from /scm/<project>/<repo>.
            val project = if (c.type == HostingType.BITBUCKET_SERVER) path.removePrefix("scm/") else path
            return project.takeIf { it.contains('/') }
        }
    }
}
