package me.brekhin.mrnavigator.util

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "5 minutes ago", "yesterday", "12 Sep 2026" — for ISO timestamps of the APIs. */
object TimeAgo {
    fun format(iso: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        if (iso.isNullOrBlank()) return ""
        val time = try {
            OffsetDateTime.parse(iso).toInstant()
        } catch (e: Exception) {
            return iso.take(16).replace('T', ' ')
        }
        val d = Duration.between(time, now)
        val minutes = d.toMinutes()
        val hours = d.toHours()
        val days = d.toDays()
        return when {
            d.isNegative || d.seconds < 60 -> msg("time.justNow")
            minutes < 60 -> msg("time.ago", minutes, MrBundle.plural(minutes, "time.minute"))
            hours < 24 -> msg("time.ago", hours, MrBundle.plural(hours, "time.hour"))
            days == 1L -> msg("time.yesterday")
            days < 30 -> msg("time.ago", days, MrBundle.plural(days, "time.day"))
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", MrBundle.locale).format(time.atZone(zone))
        }
    }
}
