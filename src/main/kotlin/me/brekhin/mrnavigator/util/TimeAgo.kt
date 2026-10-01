package me.brekhin.mrnavigator.util

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "5 минут назад", "вчера", "12 сен 2026" — for GitLab ISO timestamps. */
object TimeAgo {
    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru"))

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
            d.isNegative || d.seconds < 60 -> "только что"
            minutes < 60 -> "$minutes ${plural(minutes, "минуту", "минуты", "минут")} назад"
            hours < 24 -> "$hours ${plural(hours, "час", "часа", "часов")} назад"
            days == 1L -> "вчера"
            days < 30 -> "$days ${plural(days, "день", "дня", "дней")} назад"
            else -> DATE.format(time.atZone(zone))
        }
    }

    fun plural(n: Long, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1L && m100 != 11L -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }
}
