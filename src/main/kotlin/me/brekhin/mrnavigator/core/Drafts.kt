package me.brekhin.mrnavigator.core

import me.brekhin.mrnavigator.api.Draft
import me.brekhin.mrnavigator.api.Position
import me.brekhin.mrnavigator.util.Json
import me.brekhin.mrnavigator.util.arr
import me.brekhin.mrnavigator.util.o
import me.brekhin.mrnavigator.util.obj
import me.brekhin.mrnavigator.util.str

/** Drafts of a review as stored in the IDE between restarts: a JSON list of {id, body, position}. */
object Drafts {
    fun encode(drafts: List<Draft>): String =
        Json.write(drafts.map { mapOf("id" to it.id, "body" to it.body, "position" to it.position.toJson()) })

    /** Drafts written for the [head] version (or before versions were known); the others point at moved lines. */
    fun current(drafts: List<Draft>, head: String?): List<Draft> = drafts.filterNot { it.position.isOutdatedFor(head) }

    /** Unreadable entries are dropped: a draft is not worth an error. */
    fun decode(text: String?): List<Draft> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            Json.parse(text).arr().mapNotNull { e ->
                val m = e.obj()
                val id = m.str("id") ?: return@mapNotNull null
                val position = Position.from(m.o("position")) ?: return@mapNotNull null
                Draft(id, m.str("body") ?: "", position)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
